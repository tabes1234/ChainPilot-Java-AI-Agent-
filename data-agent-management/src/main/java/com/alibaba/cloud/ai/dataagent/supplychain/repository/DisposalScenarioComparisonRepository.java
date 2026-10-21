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

import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioCandidate;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioComparison;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioComparisonOutcome;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioComparisonSummary;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioRanking;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioRecommendation;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalSimulation;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SimulationMetrics;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SimulationScenario;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Persists comparisons and their candidates separately from single disposal plans, so one
 * simulation per plan remains intact while candidates keep their shared comparison baseline.
 */
@Repository
public class DisposalScenarioComparisonRepository {

    private static final String TABLE = "chainpilot_disposal_comparison";

    private static final String CANDIDATE_TABLE = "chainpilot_disposal_comparison_candidate";

    private static final TypeReference<List<DisposalScenarioRanking>> RANKINGS = new TypeReference<>() {
    };

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;

    private final ObjectMapper mapper;

    public DisposalScenarioComparisonRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                + "comparison_id VARCHAR(36) PRIMARY KEY, title VARCHAR(200) NOT NULL, "
                + "scenario VARCHAR(32) NOT NULL, sku VARCHAR(64), supplier_id VARCHAR(64), "
                + "as_of_date VARCHAR(10) NOT NULL, baseline_source VARCHAR(32) NOT NULL, "
                + "baseline_fingerprint VARCHAR(64) NOT NULL, baseline_captured_at BIGINT NOT NULL, "
                + "baseline_json TEXT NOT NULL, candidate_count INTEGER NOT NULL, "
                + "baseline_at_risk_orders INTEGER NOT NULL, baseline_total_shortage_units INTEGER NOT NULL, "
                + "outcome VARCHAR(32) NOT NULL, recommended_candidate_id VARCHAR(36), "
                + "recommended_label VARCHAR(100), "
                + "recommended_rank INTEGER, best_at_risk_delta INTEGER, best_total_shortage_delta INTEGER, "
                + "rankings_json TEXT NOT NULL, recommendation_json TEXT NOT NULL, "
                + "ranking_rules_json TEXT NOT NULL, assumptions_json TEXT NOT NULL, "
                + "limitations_json TEXT NOT NULL, evidence_json TEXT NOT NULL, "
                + "real_data_mutated INTEGER NOT NULL, disclaimer TEXT NOT NULL, "
                + "created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + CANDIDATE_TABLE + " ("
                + "candidate_id VARCHAR(36) PRIMARY KEY, comparison_id VARCHAR(36) NOT NULL, "
                + "position_no INTEGER NOT NULL, label VARCHAR(100), scenario VARCHAR(32) NOT NULL, "
                + "sku VARCHAR(64), supplier_id VARCHAR(64), replenishment_quantity INTEGER, "
                + "additional_delay_days INTEGER, simulation_json TEXT NOT NULL, linked_plan_id VARCHAR(36), "
                + "created_at BIGINT NOT NULL, CONSTRAINT uk_comparison_candidate_position "
                + "UNIQUE (comparison_id, position_no))");
    }

    public void save(DisposalScenarioComparison comparison) {
        try {
            DisposalScenarioRecommendation recommendation = comparison.recommendation();
            jdbc.update("INSERT INTO " + TABLE + " (comparison_id, title, scenario, sku, supplier_id, "
                            + "as_of_date, baseline_source, baseline_fingerprint, baseline_captured_at, "
                            + "baseline_json, candidate_count, baseline_at_risk_orders, "
                            + "baseline_total_shortage_units, outcome, recommended_candidate_id, "
                            + "recommended_label, recommended_rank, best_at_risk_delta, best_total_shortage_delta, "
                            + "rankings_json, recommendation_json, ranking_rules_json, assumptions_json, "
                            + "limitations_json, evidence_json, real_data_mutated, disclaimer, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    comparison.comparisonId(), comparison.title(), comparison.scenario().name(),
                    comparison.sku(), comparison.supplierId(), comparison.asOfDate().toString(),
                    comparison.baselineSource(), comparison.baselineFingerprint(),
                    comparison.baselineCapturedAt().toEpochMilli(),
                    mapper.writeValueAsString(comparison.baseline()), comparison.candidates().size(),
                    comparison.baseline().atRiskOrders(), comparison.baseline().totalShortageUnits(),
                    recommendation.outcome().name(), recommendation.recommendedCandidateId(),
                    recommendation.recommendedLabel(), recommendation.recommendedRank(),
                    comparison.bestAtRiskOrdersDelta(), comparison.bestTotalShortageUnitsDelta(),
                    mapper.writeValueAsString(comparison.rankings()),
                    mapper.writeValueAsString(recommendation),
                    mapper.writeValueAsString(comparison.rankingRules()),
                    mapper.writeValueAsString(comparison.assumptions()),
                    mapper.writeValueAsString(comparison.limitations()),
                    mapper.writeValueAsString(comparison.evidence()),
                    comparison.realDataMutated() ? 1 : 0, comparison.disclaimer(),
                    comparison.createdAt().toEpochMilli(), comparison.updatedAt().toEpochMilli());
            for (DisposalScenarioCandidate candidate : comparison.candidates()) {
                jdbc.update("INSERT INTO " + CANDIDATE_TABLE + " (candidate_id, comparison_id, position_no, "
                                + "label, scenario, sku, supplier_id, replenishment_quantity, "
                                + "additional_delay_days, simulation_json, linked_plan_id, created_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        candidate.candidateId(), comparison.comparisonId(), candidate.position(),
                        candidate.label(), candidate.scenario().name(), candidate.sku(),
                        candidate.supplierId(), candidate.replenishmentQuantity(),
                        candidate.additionalDelayDays(), mapper.writeValueAsString(candidate.simulation()),
                        candidate.linkedPlanId(), candidate.createdAt().toEpochMilli());
            }
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to persist disposal scenario comparison", exception);
        }
    }

    public Optional<DisposalScenarioComparison> find(String comparisonId) {
        List<DisposalScenarioCandidate> candidates = findCandidates(comparisonId);
        return jdbc.query("SELECT * FROM " + TABLE + " WHERE comparison_id=?",
                (rs, row) -> mapComparison(rs, candidates), comparisonId).stream().findFirst();
    }

    public Optional<DisposalScenarioCandidate> findCandidate(String comparisonId, String candidateId) {
        return jdbc.query("SELECT * FROM " + CANDIDATE_TABLE + " WHERE comparison_id=? AND candidate_id=?",
                (rs, row) -> mapCandidate(rs), comparisonId, candidateId).stream().findFirst();
    }

    public Optional<String> findLinkedPlanOwner(String comparisonId, String planId) {
        return jdbc.query("SELECT candidate_id FROM " + CANDIDATE_TABLE + " WHERE comparison_id=? AND linked_plan_id=?",
                (rs, row) -> rs.getString(1), comparisonId, planId).stream().findFirst();
    }

    public List<DisposalScenarioComparisonSummary> findPage(int limit, long offset) {
        return jdbc.query("SELECT c.comparison_id, c.title, c.scenario, c.sku, c.supplier_id, c.candidate_count, "
                        + "c.baseline_source, c.baseline_fingerprint, c.outcome, c.recommended_candidate_id, "
                        + "c.recommended_label, c.recommended_rank, c.baseline_at_risk_orders, "
                        + "c.baseline_total_shortage_units, c.best_at_risk_delta, "
                        + "c.best_total_shortage_delta, c.created_at, c.updated_at, "
                        + "(SELECT COUNT(*) FROM " + CANDIDATE_TABLE + " k WHERE k.comparison_id=c.comparison_id "
                        + "AND k.linked_plan_id IS NOT NULL) AS linked_plan_count "
                        + "FROM " + TABLE + " c ORDER BY c.created_at DESC, c.comparison_id DESC LIMIT ? OFFSET ?",
                (rs, row) -> new DisposalScenarioComparisonSummary(rs.getString("comparison_id"),
                        rs.getString("title"), SimulationScenario.valueOf(rs.getString("scenario")),
                        rs.getString("sku"), rs.getString("supplier_id"), rs.getInt("candidate_count"),
                        rs.getString("baseline_source"), rs.getString("baseline_fingerprint"),
                        DisposalScenarioComparisonOutcome.valueOf(rs.getString("outcome")),
                        rs.getString("recommended_candidate_id"), rs.getString("recommended_label"),
                        nullableInt(rs, "recommended_rank"), rs.getInt("baseline_at_risk_orders"),
                        rs.getInt("baseline_total_shortage_units"), nullableInt(rs, "best_at_risk_delta"),
                        nullableInt(rs, "best_total_shortage_delta"), rs.getInt("linked_plan_count"),
                        Instant.ofEpochMilli(rs.getLong("created_at")),
                        Instant.ofEpochMilli(rs.getLong("updated_at"))),
                limit, offset);
    }

    public long count() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + TABLE, Long.class);
        return count == null ? 0 : count;
    }

    public void linkCandidateToPlan(String comparisonId, String candidateId, String planId, Instant updatedAt) {
        int updated = jdbc.update("UPDATE " + CANDIDATE_TABLE + " SET linked_plan_id=? "
                + "WHERE comparison_id=? AND candidate_id=? AND (linked_plan_id IS NULL OR linked_plan_id=?)",
                planId, comparisonId, candidateId, planId);
        if (updated == 0) {
            Optional<String> current = findLinkedPlanOwnerForCandidate(comparisonId, candidateId);
            if (current.isEmpty()) {
                throw new IllegalArgumentException("Unknown comparison candidate: " + candidateId);
            }
            throw new IllegalStateException("Candidate is already linked to another disposal plan");
        }
        jdbc.update("UPDATE " + TABLE + " SET updated_at=? WHERE comparison_id=?", updatedAt.toEpochMilli(),
                comparisonId);
    }

    private Optional<String> findLinkedPlanOwnerForCandidate(String comparisonId, String candidateId) {
        return jdbc.query("SELECT linked_plan_id FROM " + CANDIDATE_TABLE
                        + " WHERE comparison_id=? AND candidate_id=? AND linked_plan_id IS NOT NULL",
                (rs, row) -> rs.getString(1), comparisonId, candidateId).stream().findFirst();
    }

    private DisposalScenarioComparison mapComparison(ResultSet rs, List<DisposalScenarioCandidate> candidates)
            throws SQLException {
        return new DisposalScenarioComparison(rs.getString("comparison_id"), rs.getString("title"),
                SimulationScenario.valueOf(rs.getString("scenario")), rs.getString("sku"),
                rs.getString("supplier_id"), LocalDate.parse(rs.getString("as_of_date")),
                rs.getString("baseline_source"), rs.getString("baseline_fingerprint"),
                Instant.ofEpochMilli(rs.getLong("baseline_captured_at")),
                read(rs.getString("baseline_json"), SimulationMetrics.class),
                nullableInt(rs, "best_at_risk_delta"), nullableInt(rs, "best_total_shortage_delta"), candidates,
                read(rs.getString("rankings_json"), RANKINGS),
                read(rs.getString("recommendation_json"), DisposalScenarioRecommendation.class),
                read(rs.getString("ranking_rules_json"), STRINGS),
                read(rs.getString("assumptions_json"), STRINGS),
                read(rs.getString("limitations_json"), STRINGS), read(rs.getString("evidence_json"), STRINGS),
                rs.getInt("real_data_mutated") == 1, rs.getString("disclaimer"),
                Instant.ofEpochMilli(rs.getLong("created_at")), Instant.ofEpochMilli(rs.getLong("updated_at")));
    }

    private List<DisposalScenarioCandidate> findCandidates(String comparisonId) {
        return jdbc.query("SELECT * FROM " + CANDIDATE_TABLE + " WHERE comparison_id=? ORDER BY position_no",
                (rs, row) -> mapCandidate(rs), comparisonId);
    }

    private DisposalScenarioCandidate mapCandidate(ResultSet rs) throws SQLException {
        return new DisposalScenarioCandidate(rs.getString("candidate_id"), rs.getString("label"),
                rs.getInt("position_no"), SimulationScenario.valueOf(rs.getString("scenario")),
                rs.getString("sku"), rs.getString("supplier_id"), nullableInt(rs, "replenishment_quantity"),
                nullableInt(rs, "additional_delay_days"),
                read(rs.getString("simulation_json"), DisposalSimulation.class), rs.getString("linked_plan_id"),
                Instant.ofEpochMilli(rs.getLong("created_at")));
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private <T> T read(String payload, Class<T> type) {
        try {
            return mapper.readValue(payload, type);
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored disposal scenario comparison is unreadable", exception);
        }
    }

    private <T> T read(String payload, TypeReference<T> type) {
        try {
            return mapper.readValue(payload, type);
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored disposal scenario comparison is unreadable", exception);
        }
    }
}
