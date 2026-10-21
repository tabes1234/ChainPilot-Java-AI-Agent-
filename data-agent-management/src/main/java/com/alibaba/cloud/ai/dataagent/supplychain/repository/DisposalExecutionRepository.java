/*
 * Copyright 2026 the original author or authors.
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

import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalActionType;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxExecution;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxExecutionMode;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxExecutionStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxExecutionStep;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxExecutionSummary;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxStepStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxTaskDraft;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persists sandbox rehearsals with their ordered steps, task draft and evidence. The unique
 * key on (plan, idempotency key) is the last line of defence against duplicate execution.
 */
@Repository
public class DisposalExecutionRepository {

    private static final String TABLE = "chainpilot_disposal_execution";

    private static final TypeReference<List<SandboxExecutionStep>> STEPS = new TypeReference<>() {
    };

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;

    private final ObjectMapper mapper;

    public DisposalExecutionRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                + "execution_id VARCHAR(36) PRIMARY KEY, disposal_plan_id VARCHAR(36) NOT NULL, "
                + "recommendation_id VARCHAR(36) NOT NULL, action_type VARCHAR(32) NOT NULL, "
                + "source_simulation_id VARCHAR(36), mode VARCHAR(16) NOT NULL, status VARCHAR(16) NOT NULL, "
                + "idempotency_key VARCHAR(100) NOT NULL, steps_json TEXT NOT NULL, task_draft_json TEXT, "
                + "evidence_json TEXT NOT NULL, final_result TEXT, unresolved_json TEXT NOT NULL, "
                + "failure_reason VARCHAR(500), real_data_mutated INTEGER NOT NULL, disclaimer VARCHAR(500) NOT NULL, "
                + "created_at BIGINT NOT NULL, started_at BIGINT NOT NULL, completed_at BIGINT, "
                + "updated_at BIGINT NOT NULL, UNIQUE (disposal_plan_id, idempotency_key))");
    }

    public void save(SandboxExecution execution) {
        try {
            jdbc.update("INSERT INTO " + TABLE + " (execution_id, disposal_plan_id, recommendation_id, "
                            + "action_type, source_simulation_id, mode, status, idempotency_key, steps_json, "
                            + "task_draft_json, evidence_json, final_result, unresolved_json, failure_reason, "
                            + "real_data_mutated, disclaimer, created_at, started_at, completed_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    execution.executionId(), execution.disposalPlanId(), execution.recommendationId(),
                    execution.actionType().name(), execution.sourceSimulationId(), execution.mode().name(),
                    execution.status().name(), execution.idempotencyKey(), mapper.writeValueAsString(execution.steps()),
                    execution.taskDraft() == null ? null : mapper.writeValueAsString(execution.taskDraft()),
                    mapper.writeValueAsString(execution.evidence()), execution.finalResult(),
                    mapper.writeValueAsString(execution.unresolvedItems()), execution.failureReason(),
                    execution.realDataMutated() ? 1 : 0, execution.disclaimer(),
                    execution.createdAt().toEpochMilli(), execution.startedAt().toEpochMilli(),
                    execution.completedAt() == null ? null : execution.completedAt().toEpochMilli(),
                    execution.updatedAt().toEpochMilli());
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to persist sandbox execution", exception);
        }
    }

    public void update(SandboxExecution execution) {
        try {
            jdbc.update("UPDATE " + TABLE + " SET status=?, steps_json=?, task_draft_json=?, final_result=?, "
                            + "unresolved_json=?, failure_reason=?, completed_at=?, updated_at=? WHERE execution_id=?",
                    execution.status().name(), mapper.writeValueAsString(execution.steps()),
                    execution.taskDraft() == null ? null : mapper.writeValueAsString(execution.taskDraft()),
                    execution.finalResult(), mapper.writeValueAsString(execution.unresolvedItems()),
                    execution.failureReason(),
                    execution.completedAt() == null ? null : execution.completedAt().toEpochMilli(),
                    execution.updatedAt().toEpochMilli(), execution.executionId());
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to update sandbox execution", exception);
        }
    }

    public Optional<SandboxExecution> find(String executionId) {
        return jdbc.query("SELECT * FROM " + TABLE + " WHERE execution_id=?", (rs, row) -> read(rs), executionId)
            .stream()
            .findFirst();
    }

    public Optional<SandboxExecution> findByIdempotencyKey(String planId, String idempotencyKey) {
        return jdbc.query("SELECT * FROM " + TABLE + " WHERE disposal_plan_id=? AND idempotency_key=?",
                (rs, row) -> read(rs), planId, idempotencyKey).stream().findFirst();
    }

    /**
     * Current read inside the start transaction. Without it a repeatable-read snapshot could
     * miss an execution committed while this request waited for the plan row lock.
     */
    public Optional<SandboxExecution> findByIdempotencyKeyForUpdate(String planId, String idempotencyKey) {
        return jdbc.query("SELECT * FROM " + TABLE + " WHERE disposal_plan_id=? AND idempotency_key=? FOR UPDATE",
                (rs, row) -> read(rs), planId, idempotencyKey).stream().findFirst();
    }

    /** A plan may hold at most one running or successful rehearsal; failed ones can be retried. */
    public Optional<SandboxExecution> findBlocking(String planId) {
        return jdbc.query("SELECT * FROM " + TABLE
                        + " WHERE disposal_plan_id=? AND status IN ('RUNNING','SUCCEEDED') "
                        + "ORDER BY created_at DESC, execution_id DESC LIMIT 1",
                (rs, row) -> read(rs), planId).stream().findFirst();
    }

    public Optional<SandboxExecution> findBlockingForUpdate(String planId) {
        return jdbc.query("SELECT * FROM " + TABLE
                        + " WHERE disposal_plan_id=? AND status IN ('RUNNING','SUCCEEDED')"
                        + " ORDER BY created_at DESC, execution_id DESC FOR UPDATE",
                (rs, row) -> read(rs), planId).stream().findFirst();
    }

    public List<SandboxExecutionSummary> findPage(String planId, int limit, long offset) {
        return jdbc.query("SELECT execution_id, disposal_plan_id, action_type, mode, status, "
                        + "source_simulation_id, idempotency_key, steps_json, task_draft_json, created_at, "
                        + "completed_at FROM " + TABLE + " WHERE disposal_plan_id=? "
                        + "ORDER BY created_at DESC, execution_id DESC LIMIT ? OFFSET ?",
                (rs, row) -> {
                    List<SandboxExecutionStep> steps = read(rs.getString("steps_json"), STEPS);
                    SandboxTaskDraft draft = rs.getString("task_draft_json") == null ? null
                            : read(rs.getString("task_draft_json"), SandboxTaskDraft.class);
                    return new SandboxExecutionSummary(rs.getString("execution_id"),
                            rs.getString("disposal_plan_id"),
                            DisposalActionType.valueOf(rs.getString("action_type")),
                            SandboxExecutionMode.valueOf(rs.getString("mode")),
                            SandboxExecutionStatus.valueOf(rs.getString("status")),
                            rs.getString("source_simulation_id"), rs.getString("idempotency_key"),
                            draft == null ? null : draft.taskDraftId(),
                            draft == null ? null : draft.draftType(), draft == null ? null : draft.title(),
                            steps.size(),
                            (int) steps.stream().filter(step -> step.status() == SandboxStepStatus.FAILED).count(),
                            Instant.ofEpochMilli(rs.getLong("created_at")),
                            rs.getObject("completed_at") == null ? null
                                    : Instant.ofEpochMilli(rs.getLong("completed_at")));
                }, planId, limit, offset);
    }

    public long count(String planId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + TABLE + " WHERE disposal_plan_id=?",
                Long.class, planId);
        return count == null ? 0 : count;
    }

    private SandboxExecution read(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SandboxExecution(rs.getString("execution_id"), rs.getString("disposal_plan_id"),
                rs.getString("recommendation_id"), DisposalActionType.valueOf(rs.getString("action_type")),
                SandboxExecutionMode.valueOf(rs.getString("mode")),
                SandboxExecutionStatus.valueOf(rs.getString("status")), rs.getString("source_simulation_id"),
                rs.getString("idempotency_key"), read(rs.getString("steps_json"), STEPS),
                rs.getString("task_draft_json") == null ? null
                        : read(rs.getString("task_draft_json"), SandboxTaskDraft.class),
                read(rs.getString("evidence_json"), STRINGS), rs.getString("final_result"),
                read(rs.getString("unresolved_json"), STRINGS), rs.getString("failure_reason"),
                rs.getInt("real_data_mutated") == 1, rs.getString("disclaimer"),
                Instant.ofEpochMilli(rs.getLong("created_at")), Instant.ofEpochMilli(rs.getLong("started_at")),
                rs.getObject("completed_at") == null ? null : Instant.ofEpochMilli(rs.getLong("completed_at")),
                Instant.ofEpochMilli(rs.getLong("updated_at")));
    }

    private <T> T read(String payload, Class<T> type) {
        try {
            return mapper.readValue(payload, type);
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored sandbox execution is unreadable", exception);
        }
    }

    private <T> T read(String payload, TypeReference<T> type) {
        try {
            return mapper.readValue(payload, type);
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored sandbox execution is unreadable", exception);
        }
    }
}
