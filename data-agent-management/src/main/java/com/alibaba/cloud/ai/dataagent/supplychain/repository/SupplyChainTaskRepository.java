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
package com.alibaba.cloud.ai.dataagent.supplychain.repository;

import com.alibaba.cloud.ai.dataagent.supplychain.model.DecisionTaskResult;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskAnalysisReport;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskSummary;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Persists demo analysis jobs and reports in the application's configured SQL database. */
@Repository
public class SupplyChainTaskRepository {

    private static final String TABLE = "chainpilot_analysis_task";

    private static final String SUMMARY_COLUMNS = "task_id,question,status,current_step,validation_attempts,"
            + "error_message,updated_at,"
            + "(CASE WHEN task_type='DECISION' AND decision_json IS NOT NULL THEN 1 "
            + "WHEN task_type<>'DECISION' AND report_json IS NOT NULL THEN 1 ELSE 0 END) AS report_available";

    private static final RowMapper<SupplyChainTaskSummary> SUMMARY_MAPPER = (rs, row) ->
            new SupplyChainTaskSummary(rs.getString("task_id"), rs.getString("question"),
                    rs.getString("status"), rs.getString("current_step"), rs.getInt("validation_attempts"),
                    rs.getString("error_message"), Instant.ofEpochMilli(rs.getLong("updated_at")),
                    rs.getBoolean("report_available"));

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public SupplyChainTaskRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                + "task_id VARCHAR(36) PRIMARY KEY, question VARCHAR(2000) NOT NULL, "
                + "status VARCHAR(24) NOT NULL, current_step VARCHAR(64) NOT NULL, "
                + "validation_attempts INTEGER NOT NULL, error_message VARCHAR(1000), "
                + "updated_at BIGINT NOT NULL, report_json TEXT, "
                + "task_type VARCHAR(24) NOT NULL DEFAULT 'ANALYSIS', decision_json TEXT)");
        ensureColumn("task_type", "VARCHAR(24) NOT NULL DEFAULT 'ANALYSIS'");
        ensureColumn("decision_json", "TEXT");
        // A process restart cannot resume an in-flight Graph invocation. Make this explicit in history.
        jdbc.update("UPDATE " + TABLE + " SET status='FAILED', current_step='INTERRUPTED_BY_RESTART', "
                + "error_message='Application restarted before analysis completed', updated_at=? "
                + "WHERE status NOT IN ('COMPLETED','FAILED')", Instant.now().toEpochMilli());
    }

    /** Adds a column to databases created by an earlier release; CREATE TABLE IF NOT EXISTS skips them. */
    private void ensureColumn(String column, String definition) {
        Boolean exists = jdbc.execute((ConnectionCallback<Boolean>) connection -> {
            DatabaseMetaData metadata = connection.getMetaData();
            for (String tableName : List.of(TABLE, TABLE.toUpperCase(Locale.ROOT))) {
                try (ResultSet columns = metadata.getColumns(connection.getCatalog(), null, tableName, null)) {
                    while (columns.next()) {
                        if (column.equalsIgnoreCase(columns.getString("COLUMN_NAME"))) {
                            return Boolean.TRUE;
                        }
                    }
                }
            }
            return Boolean.FALSE;
        });
        if (!Boolean.TRUE.equals(exists)) {
            jdbc.execute("ALTER TABLE " + TABLE + " ADD COLUMN " + column + " " + definition);
        }
    }

    public void create(SupplyChainTaskStatus status, String question) {
        create(status, question, SupplyChainTaskType.ANALYSIS);
    }

    public void create(SupplyChainTaskStatus status, String question, String taskType) {
        jdbc.update("INSERT INTO " + TABLE + " (task_id, question, status, current_step, validation_attempts, "
                        + "error_message, updated_at, report_json, task_type, decision_json) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, NULL)",
                status.taskId(), question, status.status(), status.currentStep(), status.validationAttempts(),
                status.error(), status.updatedAt().toEpochMilli(), SupplyChainTaskType.normalize(taskType));
    }

    public void update(SupplyChainTaskStatus status) {
        jdbc.update("UPDATE " + TABLE + " SET status=?, current_step=?, validation_attempts=?, error_message=?, "
                        + "updated_at=? WHERE task_id=? AND status NOT IN ('COMPLETED','FAILED')",
                status.status(), status.currentStep(), status.validationAttempts(), status.error(),
                status.updatedAt().toEpochMilli(), status.taskId());
    }

    public List<SupplyChainTaskSummary> findRecent(int limit) {
        return findHistory(null, limit, 0);
    }

    /** Filtering and paging stay in SQL so large task histories are never loaded into memory. */
    public List<SupplyChainTaskSummary> findHistory(String status, int limit, long offset) {
        return findHistory(SupplyChainTaskType.ANALYSIS, status, limit, offset);
    }

    public List<SupplyChainTaskSummary> findHistory(String taskType, String status, int limit, long offset) {
        List<Object> parameters = new ArrayList<>(4);
        String where = " WHERE task_type=?";
        parameters.add(SupplyChainTaskType.normalize(taskType));
        if (status != null) {
            where += " AND status=?";
            parameters.add(status);
        }
        parameters.add(limit);
        parameters.add(offset);
        return jdbc.query("SELECT " + SUMMARY_COLUMNS + " FROM " + TABLE + where
                        + " ORDER BY updated_at DESC, task_id DESC LIMIT ? OFFSET ?",
                SUMMARY_MAPPER, parameters.toArray());
    }

    public long countTasks(String status) {
        return countTasks(SupplyChainTaskType.ANALYSIS, status);
    }

    public long countTasks(String taskType, String status) {
        String type = SupplyChainTaskType.normalize(taskType);
        Long count = status == null
                ? jdbc.queryForObject("SELECT COUNT(*) FROM " + TABLE + " WHERE task_type=?", Long.class, type)
                : jdbc.queryForObject("SELECT COUNT(*) FROM " + TABLE + " WHERE task_type=? AND status=?",
                        Long.class, type, status);
        return count == null ? 0 : count;
    }

    /** Bounded read model for the observability center; report/decision JSON is parsed by the service. */
    public record ObservableTask(String taskId, String question, String status, String currentStep,
            int validationAttempts, String errorMessage, Instant updatedAt, String taskType,
            String reportJson, String decisionJson) {
    }

    public List<ObservableTask> findObservableWindow(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        return jdbc.query("SELECT task_id, question, status, current_step, validation_attempts, error_message, "
                        + "updated_at, task_type, report_json, decision_json FROM " + TABLE
                        + " ORDER BY updated_at DESC, task_id DESC LIMIT ?",
                OBSERVABLE_TASK_MAPPER, limit);
    }

    public Optional<ObservableTask> findObservableTask(String taskId) {
        return jdbc.query("SELECT task_id, question, status, current_step, validation_attempts, error_message, "
                        + "updated_at, task_type, report_json, decision_json FROM " + TABLE + " WHERE task_id=?",
                OBSERVABLE_TASK_MAPPER, taskId).stream().findFirst();
    }

    private static final RowMapper<ObservableTask> OBSERVABLE_TASK_MAPPER = (rs, row) ->
            new ObservableTask(rs.getString("task_id"), rs.getString("question"), rs.getString("status"),
                    rs.getString("current_step"), rs.getInt("validation_attempts"), rs.getString("error_message"),
                    Instant.ofEpochMilli(rs.getLong("updated_at")),
                    SupplyChainTaskType.normalize(rs.getString("task_type")), rs.getString("report_json"),
                    rs.getString("decision_json"));

    public Optional<String> findTaskType(String taskId) {
        return jdbc.query("SELECT task_type FROM " + TABLE + " WHERE task_id=?", (rs, row) -> rs.getString(1),
                taskId).stream().findFirst().map(SupplyChainTaskType::normalize);
    }

    public Optional<SupplyChainTaskStatus> findTask(String taskId) {
        return jdbc.query("SELECT task_id,status,current_step,validation_attempts,error_message,updated_at FROM "
                        + TABLE + " WHERE task_id=?", (rs, row) -> new SupplyChainTaskStatus(
                                rs.getString("task_id"), rs.getString("status"), rs.getString("current_step"),
                                rs.getInt("validation_attempts"), rs.getString("error_message"),
                                Instant.ofEpochMilli(rs.getLong("updated_at"))), taskId)
                .stream().findFirst();
    }

    public void saveReport(RiskAnalysisReport report) {
        try {
            jdbc.update("UPDATE " + TABLE + " SET report_json=? WHERE task_id=? AND task_type='ANALYSIS' "
                            + "AND status NOT IN ('FAILED')",
                    mapper.writeValueAsString(report), report.taskId());
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to persist ChainPilot report", exception);
        }
    }

    public Optional<RiskAnalysisReport> findReport(String taskId) {
        List<String> payloads = jdbc.query("SELECT report_json FROM " + TABLE
                        + " WHERE task_id=? AND task_type='ANALYSIS'",
                (rs, row) -> rs.getString(1), taskId);
        if (payloads.isEmpty() || payloads.get(0) == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(payloads.get(0), RiskAnalysisReport.class));
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored ChainPilot report is unreadable", exception);
        }
    }

    public void saveDecision(DecisionTaskResult result) {
        try {
            jdbc.update("UPDATE " + TABLE + " SET decision_json=? WHERE task_id=? AND task_type='DECISION' "
                            + "AND status NOT IN ('FAILED')",
                    mapper.writeValueAsString(result), result.taskId());
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to persist ChainPilot decision result", exception);
        }
    }

    public Optional<DecisionTaskResult> findDecision(String taskId) {
        List<String> payloads = jdbc.query("SELECT decision_json FROM " + TABLE
                        + " WHERE task_id=? AND task_type='DECISION'",
                (rs, row) -> rs.getString(1), taskId);
        if (payloads.isEmpty() || payloads.get(0) == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(payloads.get(0), DecisionTaskResult.class));
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored ChainPilot decision result is unreadable", exception);
        }
    }

    /** Retention applies only to terminal records; active jobs are never pruned. */
    public void prune(Duration retention, int maxRetained) {
        long cutoff = Instant.now().minus(retention).toEpochMilli();
        jdbc.update("DELETE FROM " + TABLE + " WHERE status IN ('COMPLETED','FAILED') AND updated_at < ?", cutoff);
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + TABLE, Integer.class);
        int excess = Math.max(0, (count == null ? 0 : count) - maxRetained);
        if (excess > 0) {
            List<String> terminalIds = jdbc.query("SELECT task_id FROM " + TABLE
                            + " WHERE status IN ('COMPLETED','FAILED') ORDER BY updated_at ASC",
                    (rs, row) -> rs.getString(1));
            terminalIds.stream().limit(excess).forEach(id -> jdbc.update("DELETE FROM " + TABLE
                    + " WHERE task_id=? AND status IN ('COMPLETED','FAILED')", id));
        }
    }
}
