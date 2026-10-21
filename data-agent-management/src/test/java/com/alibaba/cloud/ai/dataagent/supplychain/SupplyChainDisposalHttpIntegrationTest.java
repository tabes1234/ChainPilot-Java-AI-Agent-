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
package com.alibaba.cloud.ai.dataagent.supplychain;

import com.alibaba.cloud.ai.dataagent.service.aimodelconfig.AiModelRegistry;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalActionType;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalPlan;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalPlanPage;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalPlanStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalRecommendation;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioCandidate;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioComparison;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioComparisonOutcome;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioComparisonPage;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioRanking;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalSimulation;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalSimulationSummary;
import com.alibaba.cloud.ai.dataagent.supplychain.model.InventoryPosition;
import com.alibaba.cloud.ai.dataagent.supplychain.model.OrderRiskAssessment;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxExecution;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxExecutionStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SimulationBaselineView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * HTTP-level coverage for the disposal loop: rule recommendations, deterministic simulation,
 * plan persistence, status transitions and business-error mapping. The model boundary is
 * disabled so the plan explanation exercises its explicit deterministic fallback.
 */
@SpringBootTest(classes = { ChainPilotTestApplication.class,
        SupplyChainDisposalHttpIntegrationTest.DisabledModelConfiguration.class },
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:chainpilot_disposal_http_test;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;MODE=MySQL",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.sql.init.mode=never",
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.reactive.ReactiveSecurityAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.security.reactive.ReactiveUserDetailsServiceAutoConfiguration",
                "logging.level.root=warn",
                "logging.level.com.alibaba.cloud.ai.dataagent.supplychain=info" })
class SupplyChainDisposalHttpIntegrationTest {

    private static final String SKU = "SKU-MCU-001";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearPersistedState() {
        jdbcTemplate.update("DELETE FROM chainpilot_disposal_execution");
        jdbcTemplate.update("DELETE FROM chainpilot_disposal_comparison_candidate");
        jdbcTemplate.update("DELETE FROM chainpilot_disposal_comparison");
        jdbcTemplate.update("DELETE FROM chainpilot_disposal_plan");
        jdbcTemplate.update("DELETE FROM chainpilot_disposal_simulation");
    }

    @Test
    void recommendationsAreGeneratedFromRealRiskEvidence() {
        List<DisposalRecommendation> recommendations = webTestClient.get()
            .uri("/api/supply-chain/disposal-recommendations?sku=SKU-MCU-001")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBodyList(DisposalRecommendation.class)
            .returnResult()
            .getResponseBody();

        assertNotNull(recommendations);
        assertEquals(2, recommendations.size());
        DisposalRecommendation replenishment = recommendations.stream()
            .filter(recommendation -> recommendation.actionType() == DisposalActionType.REPLENISH_INVENTORY)
            .findFirst()
            .orElseThrow();
        assertEquals("SKU-MCU-001", replenishment.sku());
        assertTrue(replenishment.affectedOrderIds().contains("ORD-1001"));
        assertTrue(replenishment.priorityScore() > 0);
        assertFalse(replenishment.priorityBasis().isBlank());
        assertFalse(replenishment.businessBasis().isEmpty());
        assertFalse(replenishment.expectedImprovement().isBlank());
        assertFalse(replenishment.prerequisites().isEmpty());
        assertFalse(replenishment.sideEffects().isEmpty());
        assertFalse(replenishment.sources().isEmpty());
        assertFalse(replenishment.assumptions().isEmpty());
        assertEquals("PENDING_CONFIRMATION", replenishment.status());
        assertTrue(recommendations.get(0).priorityScore() >= recommendations.get(1).priorityScore());
    }

    @Test
    void simulationIsPersistedReadableAndNeverMutatesRealInventory() {
        DisposalSimulation simulation = postSimulation(Map.of("scenario", "INVENTORY_REPLENISHMENT", "sku",
                "SKU-MCU-001", "replenishmentQuantity", 100));

        assertEquals(120, simulation.simulated().targetSkuAvailableToPromise());
        assertEquals(100, simulation.delta().targetSkuAvailableToPromise());
        assertEquals(-100, simulation.delta().totalShortageUnits());
        assertFalse(simulation.realDataMutated());
        assertFalse(simulation.evidence().isEmpty());
        assertEquals(DisposalSimulation.RULE_VERSION, simulation.ruleVersion());
        assertEquals(DisposalSimulation.SIMULATION_STATUS, simulation.simulationStatus());
        assertTrue(simulation.dataGaps().isEmpty());
        assertTrue(simulation.affectedOrders().stream().anyMatch(impact -> !impact.evidenceChanges().isEmpty()));

        DisposalSimulation reloaded = webTestClient.get()
            .uri("/api/supply-chain/disposal-simulations/{id}", simulation.simulationId())
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(DisposalSimulation.class)
            .returnResult()
            .getResponseBody();
        assertEquals(simulation, reloaded);

        List<DisposalSimulationSummary> history = webTestClient.get()
            .uri("/api/supply-chain/disposal-simulations?limit=10")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBodyList(DisposalSimulationSummary.class)
            .returnResult()
            .getResponseBody();
        assertNotNull(history);
        assertEquals(1, history.size());
        assertEquals(simulation.simulationId(), history.get(0).simulationId());

        List<InventoryPosition> inventory = webTestClient.get()
            .uri("/api/supply-chain/inventory?sku=SKU-MCU-001")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBodyList(InventoryPosition.class)
            .returnResult()
            .getResponseBody();
        assertNotNull(inventory);
        assertEquals(55, inventory.get(0).onHandQuantity());
        assertEquals(20, inventory.get(0).availableToPromise());

        DisposalSimulation delay = postSimulation(Map.of("scenario", "SUPPLIER_DELAY", "supplierId", "SUP-GAMMA",
                "additionalDelayDays", 10));
        assertEquals(4, delay.simulated().atRiskOrders());
        List<OrderRiskAssessment> lowRisk = webTestClient.get()
            .uri("/api/supply-chain/orders?riskLevel=LOW")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBodyList(OrderRiskAssessment.class)
            .returnResult()
            .getResponseBody();
        assertNotNull(lowRisk);
        assertTrue(lowRisk.stream().anyMatch(assessment -> "ORD-1003".equals(assessment.order().orderId())),
                "the simulated delay must not change the real ORD-1003 risk state");
    }


    @Test
    void baselineExposesTheExactReadOnlySnapshotAndRuleVersion() {
        SimulationBaselineView baseline = webTestClient.get()
            .uri("/api/supply-chain/disposal-simulations/baseline")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(SimulationBaselineView.class)
            .returnResult()
            .getResponseBody();

        assertNotNull(baseline);
        assertEquals("CURRENT_BUSINESS_DATA", baseline.baselineSource());
        assertEquals(DisposalSimulation.RULE_VERSION, baseline.ruleVersion());
        assertEquals(DisposalSimulation.SIMULATION_STATUS, baseline.simulationStatus());
        assertEquals(4, baseline.orders().size());
        assertEquals(4, baseline.assessments().size());
        assertEquals(4, baseline.inventory().size());
        assertEquals(3, baseline.suppliers().size());
        assertEquals(baseline.orders().size(), baseline.metrics().totalOrders());
        assertTrue(baseline.assessments().stream().allMatch(assessment -> !assessment.evidence().isEmpty()));
        assertTrue(baseline.dataGaps().isEmpty());
        assertFalse(baseline.disclaimer().isBlank());
    }

    @Test
    void planIsPersistedSelectedAndLinkedToItsSimulation() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "MCU 补货处置方案");
        body.put("sku", "SKU-MCU-001");
        body.put("generateExplanation", true);
        body.put("simulation",
                Map.of("scenario", "INVENTORY_REPLENISHMENT", "sku", "SKU-MCU-001", "replenishmentQuantity", 100));

        DisposalPlan plan = webTestClient.post()
            .uri("/api/supply-chain/disposal-plans")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()
            .expectStatus()
            .isCreated()
            .expectBody(DisposalPlan.class)
            .returnResult()
            .getResponseBody();
        assertNotNull(plan);
        assertEquals(DisposalPlanStatus.PENDING_CONFIRMATION, plan.status());
        assertNotNull(plan.simulation());
        assertEquals(120, plan.simulation().simulated().targetSkuAvailableToPromise());
        assertTrue(plan.explanation().contains("模拟后风险订单"));
        assertEquals("DETERMINISTIC_RULES", plan.explanationSource());
        assertFalse(plan.explanationModelAssisted());
        assertEquals(List.of(), plan.explanationToolCalls());

        DisposalPlan reloaded = webTestClient.get()
            .uri("/api/supply-chain/disposal-plans/{planId}", plan.planId())
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(DisposalPlan.class)
            .returnResult()
            .getResponseBody();
        assertEquals(plan, reloaded);

        String selected = plan.recommendations()
            .stream()
            .filter(recommendation -> recommendation.actionType() == DisposalActionType.REPLENISH_INVENTORY)
            .findFirst()
            .orElseThrow()
            .recommendationId();
        DisposalPlan selectedPlan = webTestClient.patch()
            .uri("/api/supply-chain/disposal-plans/{planId}/status", plan.planId())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("status", "SELECTED", "selectedRecommendationId", selected))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(DisposalPlan.class)
            .returnResult()
            .getResponseBody();
        assertNotNull(selectedPlan);
        assertEquals(DisposalPlanStatus.SELECTED, selectedPlan.status());
        assertEquals(selected, selectedPlan.selectedRecommendationId());

        DisposalPlanPage page = webTestClient.get()
            .uri("/api/supply-chain/disposal-plans?status=selected&page=1&pageSize=10")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(DisposalPlanPage.class)
            .returnResult()
            .getResponseBody();
        assertNotNull(page);
        assertEquals(1, page.totalElements());
        assertEquals(plan.planId(), page.items().get(0).planId());

        List<DisposalSimulationSummary> linked = webTestClient.get()
            .uri("/api/supply-chain/disposal-plans/{planId}/simulations", plan.planId())
            .exchange()
            .expectStatus()
            .isOk()
            .expectBodyList(DisposalSimulationSummary.class)
            .returnResult()
            .getResponseBody();
        assertNotNull(linked);
        assertEquals(1, linked.size());
        assertEquals(plan.simulation().simulationId(), linked.get(0).simulationId());
    }

    @Test
    void invalidInputsAndMissingResourcesReturnExplicitStatuses() {
        expectSimulationStatus(Map.of("scenario", "INVENTORY_REPLENISHMENT", "sku", "SKU-MCU-001",
                "replenishmentQuantity", 0), 400);
        expectSimulationStatus(Map.of("scenario", "INVENTORY_REPLENISHMENT", "sku", "SKU-UNKNOWN",
                "replenishmentQuantity", 10), 400);
        expectSimulationStatus(Map.of("scenario", "INVENTORY_REPLENISHMENT", "sku", "SKU-MCU-001",
                "replenishmentQuantity", 10, "supplierId", "SUP-ALPHA"), 400);
        expectSimulationStatus(Map.of("scenario", "SUPPLIER_DELAY", "supplierId", "SUP-ALPHA",
                "additionalDelayDays", 91), 400);
        expectSimulationStatus(Map.of("sku", "SKU-MCU-001", "replenishmentQuantity", 10), 400);

        webTestClient.get()
            .uri("/api/supply-chain/disposal-simulations/missing")
            .exchange()
            .expectStatus()
            .isNotFound();
        webTestClient.get()
            .uri("/api/supply-chain/disposal-plans/missing")
            .exchange()
            .expectStatus()
            .isNotFound();
        webTestClient.get()
            .uri("/api/supply-chain/disposal-plans?status=UNKNOWN")
            .exchange()
            .expectStatus()
            .isBadRequest();
        webTestClient.get()
            .uri("/api/supply-chain/disposal-plans?pageSize=51")
            .exchange()
            .expectStatus()
            .isBadRequest();

        webTestClient.post()
            .uri("/api/supply-chain/disposal-plans")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("title", " ", "sku", "SKU-MCU-001"))
            .exchange()
            .expectStatus()
            .isBadRequest();
        webTestClient.post()
            .uri("/api/supply-chain/disposal-plans")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("title", "未知模拟方案", "sku", "SKU-MCU-001", "simulationId", "missing"))
            .exchange()
            .expectStatus()
            .isNotFound();
        webTestClient.post()
            .uri("/api/supply-chain/disposal-plans")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("title", "范围不匹配方案", "sku", "SKU-SENSOR-002", "simulation",
                    Map.of("scenario", "INVENTORY_REPLENISHMENT", "sku", "SKU-MCU-001", "replenishmentQuantity",
                            10)))
            .exchange()
            .expectStatus()
            .isBadRequest();
        webTestClient.patch()
            .uri("/api/supply-chain/disposal-plans/missing/status")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("status", "REJECTED"))
            .exchange()
            .expectStatus()
            .isNotFound();
    }

    @Test
    void comparisonUsesOneSharedBaselineAndPersistsCandidatesSimulationsAndHistory() {
        DisposalScenarioComparison comparison = createReplenishmentComparison(50, 100, 200);

        assertEquals(SKU, comparison.sku());
        assertEquals(3, comparison.candidates().size());
        assertEquals("CURRENT_BUSINESS_DATA", comparison.baselineSource());
        assertFalse(comparison.realDataMutated());
        assertNotNull(comparison.baseline());
        assertTrue(comparison.baselineFingerprint().matches("[0-9a-f]{64}"));
        assertTrue(comparison.candidates()
            .stream()
            .allMatch(candidate -> comparison.baseline().equals(candidate.simulation().baseline())),
                "every candidate must be simulated from the same baseline");
        assertEquals(DisposalScenarioComparisonOutcome.RECOMMENDED, comparison.recommendation().outcome());
        assertEquals("补货 +100 件", comparison.recommendation().recommendedLabel());
        assertEquals(1, comparison.recommendation().recommendedRank());
        assertEquals(comparison.candidates().get(1).candidateId(),
                comparison.recommendation().recommendedCandidateId());
        assertEquals(9, comparison.rankingRules().size());
        assertTrue(comparison.evidence()
            .stream()
            .anyMatch(item -> item.contains(comparison.baselineFingerprint())));
        assertTrue(comparison.recommendation()
            .unknowns()
            .stream()
            .anyMatch(item -> item.contains("成本")));
        assertTrue(comparison.recommendation()
            .manualConfirmation()
            .stream()
            .anyMatch(item -> item.contains("补货")));

        DisposalScenarioRanking q50Ranking = comparison.rankings().get(0);
        DisposalScenarioRanking q100Ranking = comparison.rankings().get(1);
        DisposalScenarioRanking q200Ranking = comparison.rankings().get(2);
        assertEquals(3, q50Ranking.rank());
        assertEquals(1, q100Ranking.rank());
        assertTrue(q100Ranking.recommended());
        assertEquals(2, q200Ranking.rank());
        assertTrue(q200Ranking.rankingBasis().contains("ATP 220"));
        assertFalse(q50Ranking.reasons().isEmpty());
        assertFalse(q200Ranking.tradeoffs().isEmpty());

        assertEquals(comparison, comparison(comparison.comparisonId()));

        DisposalScenarioComparisonPage page = webTestClient.get()
            .uri("/api/supply-chain/disposal-scenario-comparisons?page=1&pageSize=5")
            .exchange()
            .expectStatus()
            .isOk()
            .expectHeader()
            .valueEquals("X-Total-Count", "1")
            .expectHeader()
            .valueEquals("X-Total-Pages", "1")
            .expectBody(DisposalScenarioComparisonPage.class)
            .returnResult()
            .getResponseBody();
        assertNotNull(page);
        assertEquals(1, page.totalElements());
        assertEquals(1, page.items().size());
        assertEquals(3, page.items().get(0).candidateCount());
        assertEquals(0, page.items().get(0).linkedPlanCount());
        assertEquals("补货 +100 件", page.items().get(0).recommendedLabel());
        assertEquals(comparison.baselineFingerprint(), page.items().get(0).baselineFingerprint());

        assertEquals(1, rowCount("chainpilot_disposal_comparison"));
        assertEquals(3, rowCount("chainpilot_disposal_comparison_candidate"));
        assertEquals(comparison.baselineFingerprint(),
                jdbcTemplate.queryForObject(
                        "SELECT baseline_fingerprint FROM chainpilot_disposal_comparison WHERE comparison_id=?",
                        String.class, comparison.comparisonId()));
        assertEquals(3, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM "
                + "chainpilot_disposal_comparison_candidate WHERE comparison_id=? AND simulation_json IS NOT NULL",
                Integer.class, comparison.comparisonId()));
        List<InventoryPosition> inventory = inventory(SKU);
        assertEquals(55, inventory.get(0).onHandQuantity());
        assertEquals(20, inventory.get(0).availableToPromise());
    }

    @Test
    void candidateCanBeSavedAsASelectedPlanAndContinueIntoSandboxExecution() {
        DisposalScenarioComparison comparison = createReplenishmentComparison(50, 100, 200);
        DisposalScenarioCandidate candidate = comparison.candidates().get(1);

        DisposalPlan plan = saveCandidateAsPlan(comparison.comparisonId(), candidate.candidateId(),
                "候选补货 100 件方案");
        assertEquals(DisposalPlanStatus.PENDING_CONFIRMATION, plan.status());
        assertEquals(SKU, plan.scopeSku());
        assertNotNull(plan.simulation());
        assertFalse(candidate.simulation().simulationId().equals(plan.simulation().simulationId()),
                "the saved plan must own a separate simulation record");
        assertEquals(candidate.simulation().baseline(), plan.simulation().baseline());
        assertEquals(candidate.simulation().simulated(), plan.simulation().simulated());
        assertEquals(candidate.simulation().delta(), plan.simulation().delta());
        assertFalse(plan.simulation().realDataMutated());

        DisposalScenarioComparison linked = comparison(comparison.comparisonId());
        assertEquals(plan.planId(), linked.candidates().get(1).linkedPlanId());
        assertNull(linked.candidates().get(0).linkedPlanId());
        DisposalScenarioComparisonPage page = comparisonPage();
        assertEquals(1, page.items().get(0).linkedPlanCount());

        webTestClient.post()
            .uri("/api/supply-chain/disposal-scenario-comparisons/{comparisonId}/candidates/{candidateId}/disposal-plans",
                    comparison.comparisonId(), candidate.candidateId())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("title", "重复保存", "generateExplanation", false))
            .exchange()
            .expectStatus()
            .isEqualTo(409);

        DisposalRecommendation recommendation = plan.recommendations()
            .stream()
            .filter(item -> item.actionType() == DisposalActionType.REPLENISH_INVENTORY)
            .findFirst()
            .orElseThrow();
        DisposalPlan selected = webTestClient.patch()
            .uri("/api/supply-chain/disposal-plans/{planId}/status", plan.planId())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("status", "SELECTED", "selectedRecommendationId",
                    recommendation.recommendationId()))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(DisposalPlan.class)
            .returnResult()
            .getResponseBody();
        assertNotNull(selected);
        assertEquals(DisposalPlanStatus.SELECTED, selected.status());

        SandboxExecution execution = webTestClient.post()
            .uri("/api/supply-chain/disposal-plans/{planId}/executions", plan.planId())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("idempotencyKey", "comparison-sandbox-1"))
            .exchange()
            .expectStatus()
            .isCreated()
            .expectBody(SandboxExecution.class)
            .returnResult()
            .getResponseBody();
        assertNotNull(execution);
        assertEquals(SandboxExecutionStatus.SUCCEEDED, execution.status());
        assertEquals(plan.planId(), execution.disposalPlanId());
        assertEquals(plan.simulation().simulationId(), execution.sourceSimulationId());
        assertFalse(execution.realDataMutated());
        assertEquals(5, execution.steps().size());
        assertEquals(1, rowCount("chainpilot_disposal_execution"));

        List<InventoryPosition> inventory = inventory(SKU);
        assertEquals(55, inventory.get(0).onHandQuantity());
        assertEquals(20, inventory.get(0).availableToPromise());
    }

    @Test
    void comparisonValidationAndMissingResourcesReturnExplicitStatuses() {
        expectComparisonStatus(comparisonBody(List.of(50)), 400);
        expectComparisonStatus(comparisonBody(List.of(10, 20, 30, 40, 50, 60)), 400);
        expectComparisonStatus(comparisonBody(List.of(50, 50)), 400);
        expectComparisonStatus(comparisonBody(List.of(0, 100)), 400);

        Map<String, Object> missingSku = comparisonBody(List.of(50, 100));
        missingSku.remove("sku");
        expectComparisonStatus(missingSku, 400);

        Map<String, Object> unexpectedSupplier = comparisonBody(List.of(50, 100));
        unexpectedSupplier.put("supplierId", "SUP-ALPHA");
        expectComparisonStatus(unexpectedSupplier, 400);

        Map<String, Object> unknownSku = comparisonBody(List.of(50, 100));
        unknownSku.put("sku", "SKU-UNKNOWN");
        expectComparisonStatus(unknownSku, 400);

        Map<String, Object> longLabel = comparisonBody(List.of(50, 100));
        longLabel.put("candidates", List.of(Map.of("replenishmentQuantity", 50, "label", "x".repeat(101)),
                Map.of("replenishmentQuantity", 100)));
        expectComparisonStatus(longLabel, 400);

        DisposalScenarioComparison comparison = createReplenishmentComparison(50, 100);
        webTestClient.post()
            .uri("/api/supply-chain/disposal-scenario-comparisons/{comparisonId}/candidates/{candidateId}/disposal-plans",
                    comparison.comparisonId(), "missing-candidate")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("title", "未知候选方案", "generateExplanation", false))
            .exchange()
            .expectStatus()
            .isNotFound();
        webTestClient.post()
            .uri("/api/supply-chain/disposal-scenario-comparisons/missing/candidates/{candidateId}/disposal-plans",
                    comparison.candidates().get(0).candidateId())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("title", "未知对比方案", "generateExplanation", false))
            .exchange()
            .expectStatus()
            .isNotFound();
        webTestClient.patch()
            .uri("/api/supply-chain/disposal-scenario-comparisons/{comparisonId}/candidates/{candidateId}/plan",
                    comparison.comparisonId(), comparison.candidates().get(0).candidateId())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("planId", "missing-plan"))
            .exchange()
            .expectStatus()
            .isNotFound();
        webTestClient.patch()
            .uri("/api/supply-chain/disposal-scenario-comparisons/missing/candidates/{candidateId}/plan",
                    comparison.candidates().get(0).candidateId())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("planId", "missing-plan"))
            .exchange()
            .expectStatus()
            .isNotFound();

        webTestClient.get()
            .uri("/api/supply-chain/disposal-scenario-comparisons/missing")
            .exchange()
            .expectStatus()
            .isNotFound();
        webTestClient.get()
            .uri("/api/supply-chain/disposal-scenario-comparisons?pageSize=51")
            .exchange()
            .expectStatus()
            .isBadRequest();
        webTestClient.get()
            .uri("/api/supply-chain/disposal-scenario-comparisons?page=0")
            .exchange()
            .expectStatus()
            .isBadRequest();
    }

    @Test
    void existingMatchingPlanCanBeLinkedToAComparisonCandidateExactlyOnce() {
        DisposalScenarioComparison comparison = createReplenishmentComparison(50, 100);
        DisposalPlan plan50 = createReplenishmentPlan(50);
        String q50CandidateId = comparison.candidates().get(0).candidateId();
        String q100CandidateId = comparison.candidates().get(1).candidateId();

        DisposalScenarioComparison linked = linkCandidate(comparison.comparisonId(), q50CandidateId,
                plan50.planId());
        assertEquals(plan50.planId(), linked.candidates().get(0).linkedPlanId());
        assertEquals(1, comparisonPage().items().get(0).linkedPlanCount());

        DisposalScenarioComparison replayed = linkCandidate(comparison.comparisonId(), q50CandidateId,
                plan50.planId());
        assertEquals(plan50.planId(), replayed.candidates().get(0).linkedPlanId());

        webTestClient.patch()
            .uri("/api/supply-chain/disposal-scenario-comparisons/{comparisonId}/candidates/{candidateId}/plan",
                    comparison.comparisonId(), q100CandidateId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("planId", plan50.planId()))
            .exchange()
            .expectStatus()
            .isBadRequest();

        DisposalPlan plan100 = createReplenishmentPlan(100);
        DisposalScenarioComparison secondLink = linkCandidate(comparison.comparisonId(), q100CandidateId,
                plan100.planId());
        assertEquals(plan100.planId(), secondLink.candidates().get(1).linkedPlanId());
        assertEquals(2, comparisonPage().items().get(0).linkedPlanCount());

        webTestClient.patch()
            .uri("/api/supply-chain/disposal-scenario-comparisons/{comparisonId}/candidates/{candidateId}/plan",
                    comparison.comparisonId(), q50CandidateId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("planId", plan100.planId()))
            .exchange()
            .expectStatus()
            .isEqualTo(409);
    }

    private DisposalScenarioComparison createReplenishmentComparison(int... quantities) {
        List<Integer> values = new ArrayList<>();
        for (int quantity : quantities) {
            values.add(quantity);
        }
        return postComparison(comparisonBody(values));
    }

    private Map<String, Object> comparisonBody(List<Integer> quantities) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "MCU 补货数量对比");
        body.put("scenario", "INVENTORY_REPLENISHMENT");
        body.put("sku", SKU);
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (Integer quantity : quantities) {
            Map<String, Object> candidate = new LinkedHashMap<>();
            candidate.put("replenishmentQuantity", quantity);
            candidate.put("label", "补货 +" + quantity + " 件");
            candidates.add(candidate);
        }
        body.put("candidates", candidates);
        return body;
    }

    private DisposalScenarioComparison postComparison(Map<String, Object> body) {
        DisposalScenarioComparison comparison = webTestClient.post()
            .uri("/api/supply-chain/disposal-scenario-comparisons")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()
            .expectStatus()
            .isCreated()
            .expectBody(DisposalScenarioComparison.class)
            .returnResult()
            .getResponseBody();
        assertNotNull(comparison);
        return comparison;
    }

    private void expectComparisonStatus(Map<String, Object> body, int expectedStatus) {
        webTestClient.post()
            .uri("/api/supply-chain/disposal-scenario-comparisons")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()
            .expectStatus()
            .isEqualTo(expectedStatus);
    }

    private DisposalScenarioComparison comparison(String comparisonId) {
        DisposalScenarioComparison comparison = webTestClient.get()
            .uri("/api/supply-chain/disposal-scenario-comparisons/{comparisonId}", comparisonId)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(DisposalScenarioComparison.class)
            .returnResult()
            .getResponseBody();
        assertNotNull(comparison);
        return comparison;
    }

    private DisposalScenarioComparisonPage comparisonPage() {
        DisposalScenarioComparisonPage page = webTestClient.get()
            .uri("/api/supply-chain/disposal-scenario-comparisons?page=1&pageSize=5")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(DisposalScenarioComparisonPage.class)
            .returnResult()
            .getResponseBody();
        assertNotNull(page);
        return page;
    }

    private DisposalPlan saveCandidateAsPlan(String comparisonId, String candidateId, String title) {
        DisposalPlan plan = webTestClient.post()
            .uri("/api/supply-chain/disposal-scenario-comparisons/{comparisonId}/candidates/{candidateId}/disposal-plans",
                    comparisonId, candidateId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("title", title, "generateExplanation", false))
            .exchange()
            .expectStatus()
            .isCreated()
            .expectBody(DisposalPlan.class)
            .returnResult()
            .getResponseBody();
        assertNotNull(plan);
        return plan;
    }

    private DisposalPlan createReplenishmentPlan(int quantity) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "既有补货方案 " + quantity);
        body.put("sku", SKU);
        body.put("generateExplanation", false);
        body.put("simulation",
                Map.of("scenario", "INVENTORY_REPLENISHMENT", "sku", SKU, "replenishmentQuantity", quantity));
        DisposalPlan plan = webTestClient.post()
            .uri("/api/supply-chain/disposal-plans")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()
            .expectStatus()
            .isCreated()
            .expectBody(DisposalPlan.class)
            .returnResult()
            .getResponseBody();
        assertNotNull(plan);
        return plan;
    }

    private DisposalScenarioComparison linkCandidate(String comparisonId, String candidateId, String planId) {
        DisposalScenarioComparison comparison = webTestClient.patch()
            .uri("/api/supply-chain/disposal-scenario-comparisons/{comparisonId}/candidates/{candidateId}/plan",
                    comparisonId, candidateId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("planId", planId))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(DisposalScenarioComparison.class)
            .returnResult()
            .getResponseBody();
        assertNotNull(comparison);
        return comparison;
    }

    private List<InventoryPosition> inventory(String sku) {
        List<InventoryPosition> inventory = webTestClient.get()
            .uri("/api/supply-chain/inventory?sku={sku}", sku)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBodyList(InventoryPosition.class)
            .returnResult()
            .getResponseBody();
        assertNotNull(inventory);
        return inventory;
    }

    private int rowCount(String table) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    private DisposalSimulation postSimulation(Map<String, Object> body) {
        DisposalSimulation simulation = webTestClient.post()
            .uri("/api/supply-chain/disposal-simulations")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()
            .expectStatus()
            .isCreated()
            .expectBody(DisposalSimulation.class)
            .returnResult()
            .getResponseBody();
        assertNotNull(simulation);
        return simulation;
    }

    private void expectSimulationStatus(Map<String, Object> body, int expectedStatus) {
        webTestClient.post()
            .uri("/api/supply-chain/disposal-simulations")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()
            .expectStatus()
            .isEqualTo(expectedStatus);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DisabledModelConfiguration {

        @Bean
        AiModelRegistry aiModelRegistry() {
            AiModelRegistry registry = mock(AiModelRegistry.class);
            when(registry.getChatClient())
                .thenThrow(new IllegalStateException("External model calls are disabled in disposal HTTP tests"));
            return registry;
        }
    }

}
