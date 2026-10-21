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

import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskEvent;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Persists the observable execution trail. The event table is created alongside the task
 * table using only H2/MySQL-compatible DDL. Events are deleted together with their task by
 * the foreign-key cascade.
 */
@Repository
@DependsOn("supplyChainTaskRepository")
public class SupplyChainEventRepository {

    private static final String TABLE = "chainpilot_task_event";

    private static final int MAX_SUMMARY_LENGTH = 500;

    private static final int MAX_ERROR_TYPE_LENGTH = 128;

    private static final RowMapper<SupplyChainTaskEvent> EVENT_MAPPER = (rs, row) -> {
        long duration = rs.getLong("duration_ms");
        Long durationMs = rs.wasNull() ? null : duration;
        return new SupplyChainTaskEvent(rs.getString("event_id"), rs.getString("task_id"),
                rs.getInt("sequence_no"), rs.getString("event_type"), rs.getString("stage"),
                rs.getString("status"), Instant.ofEpochMilli(rs.getLong("occurred_at")),
                durationMs, rs.getString("summary"), rs.getString("error_type"));
    };

    private final JdbcTemplate jdbc;

    public SupplyChainEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                + "event_id VARCHAR(36) PRIMARY KEY, task_id VARCHAR(36) NOT NULL, "
                + "sequence_no INTEGER NOT NULL, event_type VARCHAR(32) NOT NULL, "
                + "stage VARCHAR(64) NOT NULL, status VARCHAR(16) NOT NULL, "
                + "occurred_at BIGINT NOT NULL, duration_ms BIGINT, summary VARCHAR(500), "
                + "error_type VARCHAR(128), "
                + "CONSTRAINT uq_chainpilot_event_sequence UNIQUE (task_id, sequence_no), "
                + "CONSTRAINT fk_chainpilot_event_task FOREIGN KEY (task_id) "
                + "REFERENCES chainpilot_analysis_task (task_id) ON DELETE CASCADE)");
    }

    /**
     * Assigns the next per-task sequence and saves the event. Synchronizing in the repository
     * prevents two events for the same task from racing for a sequence in this process.
     */
    public synchronized SupplyChainTaskEvent append(String taskId, String type, String stage, String status,
            Instant occurredAt, Long durationMs, String summary, String errorType) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        Integer current = jdbc.queryForObject(
                "SELECT COALESCE(MAX(sequence_no), 0) FROM " + TABLE + " WHERE task_id=?", Integer.class, taskId);
        int sequence = (current == null ? 0 : current) + 1;
        SupplyChainTaskEvent event = new SupplyChainTaskEvent(UUID.randomUUID().toString(), taskId, sequence,
                type, stage, status, occurredAt, durationMs, truncate(summary, MAX_SUMMARY_LENGTH),
                truncate(errorType, MAX_ERROR_TYPE_LENGTH));
        jdbc.update("INSERT INTO " + TABLE + " (event_id, task_id, sequence_no, event_type, stage, status, "
                        + "occurred_at, duration_ms, summary, error_type) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                event.eventId(), event.taskId(), event.sequence(), event.type(), event.stage(), event.status(),
                event.occurredAt().toEpochMilli(), event.durationMs(), event.summary(), event.errorType());
        return event;
    }

    /** Single query per task; sequence ordering does not depend on timestamp resolution. */
    public List<SupplyChainTaskEvent> findByTaskId(String taskId) {
        return jdbc.query("SELECT event_id, task_id, sequence_no, event_type, stage, status, occurred_at, "
                        + "duration_ms, summary, error_type FROM " + TABLE
                        + " WHERE task_id=? ORDER BY sequence_no ASC",
                EVENT_MAPPER, taskId);
    }

    /** One query for a bounded task window used by the observability center. */
    public List<SupplyChainTaskEvent> findByTaskIds(List<String> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(taskIds.size(), "?"));
        return jdbc.query("SELECT event_id, task_id, sequence_no, event_type, stage, status, occurred_at, "
                        + "duration_ms, summary, error_type FROM " + TABLE + " WHERE task_id IN (" + placeholders + ") "
                        + "ORDER BY task_id ASC, sequence_no ASC",
                EVENT_MAPPER, taskIds.toArray());
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }
}
