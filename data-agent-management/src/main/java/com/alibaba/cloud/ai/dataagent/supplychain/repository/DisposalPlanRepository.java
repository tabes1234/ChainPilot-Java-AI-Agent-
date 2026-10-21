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

import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalPlan;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalPlanStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalPlanSummary;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalRecommendation;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalSimulation;
import com.alibaba.cloud.ai.dataagent.supplychain.model.KnowledgeCitation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Saves plans with their recommendation, simulation and explanation evidence as JSON. */
@Repository
public class DisposalPlanRepository {

    private static final String TABLE = "chainpilot_disposal_plan";

    private static final TypeReference<List<DisposalRecommendation>> RECOMMENDATIONS =
            new TypeReference<>() {
            };

    private static final TypeReference<List<KnowledgeCitation>> CITATIONS = new TypeReference<>() {
    };

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;

    private final ObjectMapper mapper;

    public DisposalPlanRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                + "plan_id VARCHAR(36) PRIMARY KEY, title VARCHAR(200) NOT NULL, task_id VARCHAR(36), "
                + "scope_sku VARCHAR(64), scope_supplier_id VARCHAR(64), status VARCHAR(32) NOT NULL, "
                + "selected_recommendation_id VARCHAR(36), recommendations_json TEXT NOT NULL, "
                + "simulation_json TEXT, evidence_json TEXT NOT NULL, explanation TEXT NOT NULL, "
                + "explanation_source VARCHAR(32) NOT NULL, explanation_model_assisted INTEGER NOT NULL, "
                + "explanation_tool_calls_json TEXT NOT NULL, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL)");
    }

    public void save(DisposalPlan plan) {
        try {
            jdbc.update("INSERT INTO " + TABLE + " (plan_id, title, task_id, scope_sku, scope_supplier_id, "
                            + "status, selected_recommendation_id, recommendations_json, simulation_json, "
                            + "evidence_json, explanation, explanation_source, explanation_model_assisted, "
                            + "explanation_tool_calls_json, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    plan.planId(), plan.title(), plan.taskId(), plan.scopeSku(), plan.scopeSupplierId(),
                    plan.status().name(), plan.selectedRecommendationId(),
                    mapper.writeValueAsString(plan.recommendations()),
                    plan.simulation() == null ? null : mapper.writeValueAsString(plan.simulation()),
                    mapper.writeValueAsString(plan.evidence()), plan.explanation(), plan.explanationSource(),
                    plan.explanationModelAssisted() ? 1 : 0,
                    mapper.writeValueAsString(plan.explanationToolCalls()),
                    plan.createdAt().toEpochMilli(), plan.updatedAt().toEpochMilli());
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to persist disposal plan", exception);
        }
    }

    public List<String> findIdsByTask(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return List.of();
        }
        return jdbc.query("SELECT plan_id FROM " + TABLE + " WHERE task_id=? ORDER BY created_at ASC",
                (rs, row) -> rs.getString(1), taskId);
    }

    public Optional<DisposalPlan> find(String planId) {
        return jdbc.query("SELECT * FROM " + TABLE + " WHERE plan_id=?", (rs, row) -> mapPlan(rs), planId)
            .stream()
            .findFirst();
    }

    /**
     * Locks the plan row so eligibility, idempotency lookup and execution insertion form one
     * serialized transaction for a plan. The lock must be acquired inside a transaction.
     */
    public Optional<DisposalPlan> findForUpdate(String planId) {
        return jdbc.query("SELECT * FROM " + TABLE + " WHERE plan_id=? FOR UPDATE", (rs, row) -> mapPlan(rs),
                planId).stream().findFirst();
    }

    private DisposalPlan mapPlan(ResultSet rs) throws SQLException {
        return new DisposalPlan(rs.getString("plan_id"), rs.getString("title"), rs.getString("task_id"),
                rs.getString("scope_sku"), rs.getString("scope_supplier_id"),
                DisposalPlanStatus.valueOf(rs.getString("status")),
                rs.getString("selected_recommendation_id"), read(rs.getString("recommendations_json"),
                        RECOMMENDATIONS),
                rs.getString("simulation_json") == null ? null
                        : read(rs.getString("simulation_json"), DisposalSimulation.class),
                read(rs.getString("evidence_json"), CITATIONS), rs.getString("explanation"),
                rs.getString("explanation_source"), rs.getInt("explanation_model_assisted") == 1,
                read(rs.getString("explanation_tool_calls_json"), STRINGS),
                Instant.ofEpochMilli(rs.getLong("created_at")),
                Instant.ofEpochMilli(rs.getLong("updated_at")));
    }

    public List<DisposalPlanSummary> findPage(DisposalPlanStatus status, int limit, long offset) {
        List<Object> parameters = new ArrayList<>();
        String where = "";
        if (status != null) {
            where = " WHERE status=?";
            parameters.add(status.name());
        }
        parameters.add(limit);
        parameters.add(offset);
        return jdbc.query("SELECT plan_id, title, task_id, scope_sku, scope_supplier_id, status, "
                        + "recommendations_json, simulation_json, created_at, updated_at FROM " + TABLE + where
                        + " ORDER BY updated_at DESC, plan_id DESC LIMIT ? OFFSET ?",
                (rs, row) -> {
                    List<DisposalRecommendation> recommendations = read(rs.getString("recommendations_json"),
                            RECOMMENDATIONS);
                    DisposalSimulation simulation = rs.getString("simulation_json") == null ? null
                            : read(rs.getString("simulation_json"), DisposalSimulation.class);
                    return new DisposalPlanSummary(rs.getString("plan_id"), rs.getString("title"),
                            DisposalPlanStatus.valueOf(rs.getString("status")), rs.getString("task_id"),
                            rs.getString("scope_sku"), rs.getString("scope_supplier_id"),
                            recommendations.size(), simulation == null ? null : simulation.scenario(),
                            simulation == null ? null : simulation.delta().atRiskOrders(),
                            Instant.ofEpochMilli(rs.getLong("created_at")),
                            Instant.ofEpochMilli(rs.getLong("updated_at")));
                }, parameters.toArray());
    }

    public long count(DisposalPlanStatus status) {
        Long count = status == null
                ? jdbc.queryForObject("SELECT COUNT(*) FROM " + TABLE, Long.class)
                : jdbc.queryForObject("SELECT COUNT(*) FROM " + TABLE + " WHERE status=?", Long.class,
                        status.name());
        return count == null ? 0 : count;
    }

    public void updateStatus(String planId, DisposalPlanStatus status, String selectedRecommendationId,
            Instant updatedAt) {
        jdbc.update("UPDATE " + TABLE + " SET status=?, selected_recommendation_id=?, updated_at=? WHERE plan_id=?",
                status.name(), selectedRecommendationId, updatedAt.toEpochMilli(), planId);
    }

    private <T> T read(String payload, Class<T> type) {
        try {
            return mapper.readValue(payload, type);
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored disposal plan is unreadable", exception);
        }
    }

    private <T> T read(String payload, TypeReference<T> type) {
        try {
            return mapper.readValue(payload, type);
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored disposal plan is unreadable", exception);
        }
    }
}
