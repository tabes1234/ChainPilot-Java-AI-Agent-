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

import com.alibaba.cloud.ai.dataagent.supplychain.model.DecisionProposal;
import com.alibaba.cloud.ai.dataagent.supplychain.model.ProposalActionType;
import com.alibaba.cloud.ai.dataagent.supplychain.model.ProposalStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Persists content-hash-bound action proposals awaiting human confirmation. */
@Repository
@DependsOn("supplyChainTaskRepository")
public class DecisionProposalRepository {

    private static final String TABLE = "chainpilot_action_proposal";

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;

    private final ObjectMapper mapper;

    private final RowMapper<DecisionProposal> rowMapper = (rs, row) -> {
        long confirmedAt = rs.getLong("confirmed_at");
        boolean confirmedAtNull = rs.wasNull();
        return new DecisionProposal(rs.getString("proposal_id"), rs.getString("task_id"),
                ProposalActionType.valueOf(rs.getString("action_type")), rs.getString("title"),
                rs.getString("target_id"), readMap(rs.getString("parameters_json")),
                readStringList(rs.getString("evidence_json")), rs.getString("content_hash"),
                ProposalStatus.valueOf(rs.getString("status")), Instant.ofEpochMilli(rs.getLong("created_at")),
                Instant.ofEpochMilli(rs.getLong("expires_at")),
                confirmedAtNull ? null : Instant.ofEpochMilli(confirmedAt),
                readMap(rs.getString("result_json")), rs.getString("error_message"));
    };

    public DecisionProposalRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                + "proposal_id VARCHAR(36) PRIMARY KEY, task_id VARCHAR(36) NOT NULL, "
                + "action_type VARCHAR(40) NOT NULL, title VARCHAR(200) NOT NULL, target_id VARCHAR(64) NOT NULL, "
                + "parameters_json TEXT NOT NULL, evidence_json TEXT NOT NULL, content_hash VARCHAR(64) NOT NULL, "
                + "status VARCHAR(24) NOT NULL, created_at BIGINT NOT NULL, expires_at BIGINT NOT NULL, "
                + "confirmed_at BIGINT, result_json TEXT, error_message VARCHAR(1000), "
                + "CONSTRAINT fk_chainpilot_proposal_task FOREIGN KEY (task_id) "
                + "REFERENCES chainpilot_analysis_task (task_id) ON DELETE CASCADE)");
    }

    public void save(DecisionProposal proposal) {
        jdbc.update("INSERT INTO " + TABLE + " (proposal_id, task_id, action_type, title, target_id, "
                        + "parameters_json, evidence_json, content_hash, status, created_at, expires_at, "
                        + "confirmed_at, result_json, error_message) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                proposal.proposalId(), proposal.taskId(), proposal.actionType().name(), proposal.title(),
                proposal.targetId(), write(proposal.parameters()), write(proposal.evidence()),
                proposal.contentHash(), proposal.status().name(), proposal.createdAt().toEpochMilli(),
                proposal.expiresAt().toEpochMilli(),
                proposal.confirmedAt() == null ? null : proposal.confirmedAt().toEpochMilli(),
                proposal.result() == null ? null : write(proposal.result()), proposal.errorMessage());
    }

    public Optional<DecisionProposal> find(String proposalId) {
        return jdbc.query("SELECT * FROM " + TABLE + " WHERE proposal_id=?", rowMapper, proposalId)
                .stream()
                .findFirst();
    }

    public Optional<DecisionProposal> findPendingByTaskAndHash(String taskId, String contentHash) {
        return jdbc.query("SELECT * FROM " + TABLE + " WHERE task_id=? AND content_hash=? AND status='PENDING'",
                        rowMapper, taskId, contentHash)
                .stream()
                .findFirst();
    }

    public List<DecisionProposal> findByTask(String taskId) {
        return jdbc.query("SELECT * FROM " + TABLE + " WHERE task_id=? ORDER BY created_at ASC, proposal_id ASC",
                rowMapper, taskId);
    }

    public int claim(String proposalId, Instant now) {
        return jdbc.update("UPDATE " + TABLE + " SET status='EXECUTING', error_message=NULL "
                        + "WHERE proposal_id=? AND status='PENDING' AND expires_at>?",
                proposalId, now.toEpochMilli());
    }

    public void markConfirmed(String proposalId, Map<String, Object> result, Instant confirmedAt) {
        jdbc.update("UPDATE " + TABLE + " SET status='CONFIRMED', confirmed_at=?, result_json=?, error_message=NULL "
                        + "WHERE proposal_id=?",
                confirmedAt.toEpochMilli(), write(result), proposalId);
    }

    public void markCancelled(String proposalId, Instant cancelledAt) {
        jdbc.update("UPDATE " + TABLE + " SET status='CANCELLED', confirmed_at=?, error_message=NULL "
                + "WHERE proposal_id=?", cancelledAt.toEpochMilli(), proposalId);
    }

    public void markExpired(String proposalId) {
        jdbc.update("UPDATE " + TABLE + " SET status='EXPIRED' WHERE proposal_id=? AND status='PENDING'",
                proposalId);
    }

    public void markFailed(String proposalId, String error) {
        jdbc.update("UPDATE " + TABLE + " SET status='FAILED', error_message=? WHERE proposal_id=?",
                truncate(error, 1000), proposalId);
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to persist proposal JSON", exception);
        }
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, MAP_TYPE);
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored proposal JSON is unreadable", exception);
        }
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, STRING_LIST_TYPE);
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored proposal evidence is unreadable", exception);
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }
}
