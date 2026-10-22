/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.cloud.ai.dataagent.evaluation.repository;

import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationAssertionResult;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationCaseResult;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationCaseStatus;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationMode;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationRun;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationRunStatus;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationTaskReference;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persists evaluation runs and case results. Tables are created and upgraded additively on
 * startup following the existing supply-chain repository pattern, so the feature works on
 * both H2 and MySQL without Flyway. Existing databases receive new nullable columns and
 * indexes; no business table is dropped or rebuilt.
 */
@Repository
public class EvaluationRunRepository {

    private static final String RUN_TABLE = "chainpilot_evaluation_run";

    private static final String RESULT_TABLE = "chainpilot_evaluation_case_result";

    private static final String LOCK_TABLE = "chainpilot_evaluation_lock";

    private static final String LOCK_NAME = "AGENT_EVALUATION";

    private static final TypeReference<List<EvaluationAssertionResult>> ASSERTION_LIST = new TypeReference<>() {
    };

    private static final TypeReference<Map<String, Object>> METRICS_MAP = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;

    private final ObjectMapper mapper;

    private final RowMapper<EvaluationRun> runMapper = this::mapRun;

    private final RowMapper<EvaluationCaseResult> resultMapper = this::mapResult;

    public EvaluationRunRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        createTables();
        upgradeExistingTables();
        ensureLockRow();
    }

    private void ensureLockRow() {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM " + LOCK_TABLE + " WHERE lock_name=?",
                Integer.class, LOCK_NAME) > 0) {
            return;
        }
        try {
            jdbc.update("INSERT INTO " + LOCK_TABLE + " (lock_name,owner_instance_id,lease_expires_at,"
                            + "fence_version,run_id,updated_at) VALUES (?,NULL,0,0,NULL,?)",
                    LOCK_NAME, databaseNowMillis());
        }
        catch (RuntimeException ignored) {
            // Another JVM initialized the same lock row concurrently.
        }
    }

    public long databaseNowMillis() {
        Timestamp timestamp = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class);
        if (timestamp == null) {
            throw new IllegalStateException("Database returned no current timestamp");
        }
        return timestamp.getTime();
    }

    /** Claims the database-level AGENT evaluation lock; returns the new fence or empty if busy. */
    @Transactional
    public Optional<Long> claimEvaluationLock(String owner, long leaseExpiresAt, String runId) {
        long now = databaseNowMillis();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT owner_instance_id, lease_expires_at FROM " + LOCK_TABLE + " WHERE lock_name=? FOR UPDATE",
                LOCK_NAME);
        if (rows.isEmpty()) {
            jdbc.update("INSERT INTO " + LOCK_TABLE + " (lock_name,owner_instance_id,lease_expires_at,"
                            + "fence_version,run_id,updated_at) VALUES (?,?,?,1,?,?)",
                    LOCK_NAME, owner, leaseExpiresAt, runId, now);
            return Optional.of(1L);
        }
        Map<String, Object> row = rows.get(0);
        Object currentOwner = row.get("owner_instance_id");
        Number lease = (Number) row.get("lease_expires_at");
        if (currentOwner != null && lease != null && lease.longValue() >= now) {
            return Optional.empty();
        }
        jdbc.update("UPDATE " + LOCK_TABLE + " SET owner_instance_id=?, lease_expires_at=?, "
                        + "fence_version=fence_version+1, run_id=?, updated_at=? WHERE lock_name=?",
                owner, leaseExpiresAt, runId, now, LOCK_NAME);
        Long fence = jdbc.queryForObject("SELECT fence_version FROM " + LOCK_TABLE + " WHERE lock_name=?",
                Long.class, LOCK_NAME);
        return Optional.of(fence == null ? 1L : fence);
    }

    public boolean renewEvaluationLock(String owner, long fenceVersion, long leaseExpiresAt) {
        return jdbc.update("UPDATE " + LOCK_TABLE + " SET lease_expires_at=?, updated_at=? "
                        + "WHERE lock_name=? AND owner_instance_id=? AND fence_version=?",
                leaseExpiresAt, databaseNowMillis(), LOCK_NAME, owner, fenceVersion) > 0;
    }

    public void releaseEvaluationLock(String owner, long fenceVersion) {
        jdbc.update("UPDATE " + LOCK_TABLE + " SET owner_instance_id=NULL, lease_expires_at=0, run_id=NULL, "
                        + "updated_at=? WHERE lock_name=? AND owner_instance_id=? AND fence_version=?",
                databaseNowMillis(), LOCK_NAME, owner, fenceVersion);
    }

    /** Atomically claims an expired lock and returns the run it owned, if any. */
    @Transactional
    public Optional<String> claimExpiredEvaluationRun(long now) {
        return takeExpiredLock(now);
    }

    @Transactional
    public boolean markRunInterrupted(String runId, String reason) {
        return interruptRun(runId, reason);
    }

    /**
     * Takes an expired lease and closes the run it owned in one transaction. Doing the two steps
     * separately could leave a RUNNING run with no owner and no lease, which no later instance
     * would be able to recover.
     */
    @Transactional
    public Optional<String> expireRunAndMarkInterrupted(long now, String reason) {
        Optional<String> runId = takeExpiredLock(now);
        runId.ifPresent(id -> interruptRun(id, reason));
        return runId;
    }

    private Optional<String> takeExpiredLock(long now) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT owner_instance_id, lease_expires_at, run_id FROM " + LOCK_TABLE
                        + " WHERE lock_name=? FOR UPDATE", LOCK_NAME);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.get(0);
        Object runId = row.get("run_id");
        Object owner = row.get("owner_instance_id");
        Number lease = (Number) row.get("lease_expires_at");
        if (runId == null || owner == null || lease == null || lease.longValue() >= now) {
            return Optional.empty();
        }
        jdbc.update("UPDATE " + LOCK_TABLE + " SET owner_instance_id=NULL, lease_expires_at=0, run_id=NULL, "
                        + "fence_version=fence_version+1, updated_at=? WHERE lock_name=?",
                now, LOCK_NAME);
        return Optional.of(String.valueOf(runId));
    }

    private boolean interruptRun(String runId, String reason) {
        long now = databaseNowMillis();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT started_at FROM " + RUN_TABLE + " WHERE run_id=? AND status='RUNNING'", runId);
        if (rows.isEmpty()) {
            return false;
        }
        Number startedAt = (Number) rows.get(0).get("started_at");
        long duration = Math.max(0, now - (startedAt == null ? now : startedAt.longValue()));
        jdbc.update("UPDATE " + RESULT_TABLE + " SET status=?, failure_type=?, failure_message=? "
                        + "WHERE run_id=? AND status=?",
                EvaluationCaseStatus.UNDETERMINED.name(), "INTERRUPTED", reason, runId,
                EvaluationCaseStatus.RUNNING.name());
        jdbc.update("UPDATE " + RUN_TABLE + " SET status=?, completed_at=?, duration_ms=?, failure_reason=? "
                        + "WHERE run_id=? AND status='RUNNING'",
                EvaluationRunStatus.FAILED.name(), now, duration, reason, runId);
        return true;
    }

    private void createTables() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + RUN_TABLE + " ("
                + "run_id VARCHAR(36) PRIMARY KEY, dataset_version VARCHAR(64) NOT NULL, "
                + "mode VARCHAR(16) NOT NULL, status VARCHAR(16) NOT NULL, environment VARCHAR(64) NOT NULL, "
                + "started_at BIGINT NOT NULL, completed_at BIGINT, total_cases INTEGER NOT NULL, "
                + "passed_count INTEGER NOT NULL, failed_count INTEGER NOT NULL, "
                + "undetermined_count INTEGER NOT NULL, duration_ms BIGINT, metrics_json TEXT, "
                + "failure_reason VARCHAR(1000))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + RESULT_TABLE + " ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY, run_id VARCHAR(36) NOT NULL, "
                + "case_id VARCHAR(80) NOT NULL, case_name VARCHAR(300) NOT NULL, scenario VARCHAR(400) NOT NULL, "
                + "category VARCHAR(32) NOT NULL, status VARCHAR(16) NOT NULL, duration_ms BIGINT NOT NULL, "
                + "task_id VARCHAR(36), assertions_json TEXT, observation_json TEXT, "
                + "failure_type VARCHAR(48), failure_message VARCHAR(1000), sort_order INTEGER NOT NULL, "
                + "case_version VARCHAR(64), task_mode VARCHAR(16))");
        createIndexSafely("CREATE INDEX idx_evaluation_result_run ON " + RESULT_TABLE + " (run_id)");
        createIndexSafely("CREATE UNIQUE INDEX uk_evaluation_result_run_case ON " + RESULT_TABLE
                + " (run_id, case_id)");
        createIndexSafely("CREATE INDEX idx_evaluation_run_status_started ON " + RUN_TABLE
                + " (status, started_at)");
        createIndexSafely("CREATE INDEX idx_evaluation_run_mode_started ON " + RUN_TABLE + " (mode, started_at)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + LOCK_TABLE + " ("
                + "lock_name VARCHAR(64) PRIMARY KEY, owner_instance_id VARCHAR(64), "
                + "lease_expires_at BIGINT NOT NULL, fence_version BIGINT NOT NULL, "
                + "run_id VARCHAR(36), updated_at BIGINT NOT NULL)");
    }

    /** Additive upgrade for evaluation tables created by earlier ChainPilot builds. */
    private void upgradeExistingTables() {
        addColumnIfMissing(RUN_TABLE, "failure_reason", "VARCHAR(1000)");
        addColumnIfMissing(RESULT_TABLE, "case_version", "VARCHAR(64)");
        addColumnIfMissing(RESULT_TABLE, "task_mode", "VARCHAR(16)");
        addForeignKeySafely();
        createIndexSafely("CREATE UNIQUE INDEX uk_evaluation_result_run_case ON " + RESULT_TABLE
                + " (run_id, case_id)");
        createIndexSafely("CREATE INDEX idx_evaluation_run_status_started ON " + RUN_TABLE
                + " (status, started_at)");
        createIndexSafely("CREATE INDEX idx_evaluation_run_mode_started ON " + RUN_TABLE + " (mode, started_at)");
    }

    private void addColumnIfMissing(String table, String column, String definition) {
        try {
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
        catch (RuntimeException ignored) {
            // Column already exists (or the dialect rejected a redundant ADD COLUMN).
        }
    }

    private void createIndexSafely(String sql) {
        try {
            jdbc.execute(sql);
        }
        catch (RuntimeException ignored) {
            // Index already exists on MySQL; H2 has no portable IF NOT EXISTS for indexes.
        }
    }

    private void addForeignKeySafely() {
        try {
            jdbc.execute("ALTER TABLE " + RESULT_TABLE + " ADD CONSTRAINT fk_evaluation_result_run "
                    + "FOREIGN KEY (run_id) REFERENCES " + RUN_TABLE + "(run_id) ON DELETE CASCADE");
        }
        catch (RuntimeException ignored) {
            // Constraint already exists or pre-existing orphan rows prevent it; save() still deletes
            // child rows before the parent and runs in one transaction.
        }
    }

    /** Replaces a run snapshot atomically; a partial result set is never committed. */
    @Transactional
    public void save(EvaluationRun run) {
        writeSnapshot(run);
    }

    /**
     * Lease-fenced snapshot write used by AGENT runs. The lock row is locked first, so a
     * concurrent takeover cannot interleave with the rewrite, and a former owner whose fence
     * was replaced can neither advance nor finalize the run. A terminal snapshot releases the
     * lease in the same transaction, so no window leaves a closed run still holding the lock.
     */
    @Transactional
    public boolean saveOwned(EvaluationRun run, String owner, long fenceVersion) {
        if (owner == null) {
            return false;
        }
        List<String> held = jdbc.queryForList("SELECT lock_name FROM " + LOCK_TABLE
                + " WHERE lock_name=? AND owner_instance_id=? AND fence_version=? FOR UPDATE",
                String.class, LOCK_NAME, owner, fenceVersion);
        if (held.isEmpty()) {
            return false;
        }
        writeSnapshot(run);
        if (run.status() != EvaluationRunStatus.RUNNING) {
            releaseEvaluationLock(owner, fenceVersion);
        }
        return true;
    }

    /** Cross-instance truth: some JVM still holds a live AGENT evaluation lease. */
    public boolean isEvaluationLockHeld() {
        Long held = jdbc.queryForObject("SELECT COUNT(*) FROM " + LOCK_TABLE
                + " WHERE lock_name=? AND owner_instance_id IS NOT NULL AND lease_expires_at >= ?",
                Long.class, LOCK_NAME, databaseNowMillis());
        return held != null && held > 0;
    }

    private void writeSnapshot(EvaluationRun run) {
        jdbc.update("DELETE FROM " + RESULT_TABLE + " WHERE run_id=?", run.runId());
        jdbc.update("DELETE FROM " + RUN_TABLE + " WHERE run_id=?", run.runId());
        jdbc.update("INSERT INTO " + RUN_TABLE + " (run_id,dataset_version,mode,status,environment,started_at,"
                        + "completed_at,total_cases,passed_count,failed_count,undetermined_count,duration_ms,"
                        + "metrics_json,failure_reason) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                run.runId(), run.datasetVersion(), run.mode().name(), run.status().name(), run.environment(),
                run.startedAt().toEpochMilli(), run.completedAt() == null ? null : run.completedAt().toEpochMilli(),
                run.totalCases(), run.passed(), run.failed(), run.undetermined(), run.durationMs(),
                write(run.metrics()), run.failureReason());
        int order = 0;
        for (EvaluationCaseResult result : run.results()) {
            jdbc.update("INSERT INTO " + RESULT_TABLE + " (run_id,case_id,case_name,scenario,category,status,"
                            + "duration_ms,task_id,assertions_json,observation_json,failure_type,failure_message,"
                            + "sort_order,case_version,task_mode) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    run.runId(), result.caseId(), result.name(), result.scenario(), result.category(),
                    result.status().name(), result.durationMs(), result.taskId(), write(result.assertions()),
                    result.observationJson(), result.failureType(), result.failureMessage(), order++,
                    result.caseVersion(), result.taskMode());
        }
    }

    /**
     * Links a workflow task to persisted evaluation runs/cases without exposing result payloads.
     * A task can be used by more than one evaluation case over time.
     */
    public List<EvaluationTaskReference> findTaskReferences(String taskId) {
        return jdbc.query("SELECT r.run_id, r.mode, r.status AS run_status, r.started_at, c.case_id, c.case_name, "
                        + "c.status AS case_status, c.failure_type FROM " + RESULT_TABLE + " c JOIN " + RUN_TABLE
                        + " r ON r.run_id=c.run_id WHERE c.task_id=? ORDER BY r.started_at DESC, c.sort_order ASC",
                (rs, row) -> new EvaluationTaskReference(rs.getString("run_id"), rs.getString("mode"),
                        rs.getString("run_status"), rs.getString("case_id"), rs.getString("case_name"),
                        rs.getString("case_status"), rs.getString("failure_type"),
                        Instant.ofEpochMilli(rs.getLong("started_at"))), taskId);
    }

    public Optional<EvaluationRun> find(String runId) {
        List<EvaluationRun> runs = jdbc.query("SELECT * FROM " + RUN_TABLE + " WHERE run_id=?", runMapper, runId);
        if (runs.isEmpty()) {
            return Optional.empty();
        }
        EvaluationRun run = runs.get(0);
        List<EvaluationCaseResult> results = jdbc.query(
                "SELECT * FROM " + RESULT_TABLE + " WHERE run_id=? ORDER BY sort_order", resultMapper, runId);
        return Optional.of(new EvaluationRun(run.runId(), run.datasetVersion(), run.mode(), run.status(),
                run.environment(), run.startedAt(), run.completedAt(), run.totalCases(), run.passed(), run.failed(),
                run.undetermined(), run.durationMs(), run.metrics(), results, run.failureReason()));
    }

    public List<EvaluationRun> list(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return jdbc.query("SELECT * FROM " + RUN_TABLE + " ORDER BY started_at DESC LIMIT ?", runMapper, safeLimit);
    }

    public List<EvaluationRun> findCompletedWithResults(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 50));
        List<EvaluationRun> runs = jdbc.query(
                "SELECT * FROM " + RUN_TABLE + " WHERE status='COMPLETED' ORDER BY started_at DESC LIMIT ?", runMapper,
                safeLimit);
        return runs.stream()
            .map(run -> find(run.runId()).orElse(run))
            .toList();
    }

    /** Case history includes failed/interrupted terminal runs for troubleshooting, not metrics. */
    public List<EvaluationRun> findTerminalWithResults(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 50));
        List<EvaluationRun> runs = jdbc.query(
                "SELECT * FROM " + RUN_TABLE + " WHERE status IN ('COMPLETED','FAILED') "
                        + "ORDER BY started_at DESC LIMIT ?",
                runMapper, safeLimit);
        return runs.stream()
            .map(run -> find(run.runId()).orElse(run))
            .toList();
    }

    private EvaluationRun mapRun(ResultSet rs, int rowNum) throws SQLException {
        Long completedAt = rs.getObject("completed_at") == null ? null : rs.getLong("completed_at");
        Long duration = rs.getObject("duration_ms") == null ? null : rs.getLong("duration_ms");
        return new EvaluationRun(rs.getString("run_id"), rs.getString("dataset_version"),
                EvaluationMode.valueOf(rs.getString("mode")), EvaluationRunStatus.valueOf(rs.getString("status")),
                rs.getString("environment"), Instant.ofEpochMilli(rs.getLong("started_at")),
                completedAt == null ? null : Instant.ofEpochMilli(completedAt), rs.getInt("total_cases"),
                rs.getInt("passed_count"), rs.getInt("failed_count"), rs.getInt("undetermined_count"), duration,
                readMetrics(rs.getString("metrics_json")), List.of(), rs.getString("failure_reason"));
    }

    private EvaluationCaseResult mapResult(ResultSet rs, int rowNum) throws SQLException {
        return new EvaluationCaseResult(rs.getString("case_id"), rs.getString("case_name"), rs.getString("scenario"),
                rs.getString("category"), EvaluationCaseStatus.valueOf(rs.getString("status")),
                rs.getLong("duration_ms"), rs.getString("task_id"), readAssertions(rs.getString("assertions_json")),
                rs.getString("observation_json"), rs.getString("failure_type"), rs.getString("failure_message"),
                rs.getString("case_version"), rs.getString("task_mode"));
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        }
        catch (IOException exception) {
            throw new IllegalStateException("Unable to serialize evaluation payload", exception);
        }
    }

    private List<EvaluationAssertionResult> readAssertions(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, ASSERTION_LIST);
        }
        catch (IOException exception) {
            return List.of();
        }
    }

    private Map<String, Object> readMetrics(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return mapper.readValue(json, METRICS_MAP);
        }
        catch (IOException exception) {
            return new LinkedHashMap<>();
        }
    }

}
