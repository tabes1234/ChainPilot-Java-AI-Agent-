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
package com.alibaba.cloud.ai.dataagent.supplychain.service;

import com.alibaba.cloud.ai.dataagent.supplychain.model.CreateComparisonDisposalPlanRequest;
import com.alibaba.cloud.ai.dataagent.supplychain.model.CreateDisposalPlanRequest;
import com.alibaba.cloud.ai.dataagent.supplychain.model.CreateDisposalScenarioComparisonRequest;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalExplanation;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalPlan;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalPlanStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioCandidate;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioComparison;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioComparisonOutcome;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioComparisonPage;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioRecommendation;
import com.alibaba.cloud.ai.dataagent.supplychain.model.KnowledgeCitation;
import com.alibaba.cloud.ai.dataagent.supplychain.model.LinkComparisonCandidatePlanRequest;
import com.alibaba.cloud.ai.dataagent.supplychain.model.ScenarioComparisonCandidateRequest;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SimulationRequest;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SimulationScenario;
import com.alibaba.cloud.ai.dataagent.supplychain.model.UpdateDisposalPlanStatusRequest;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.DisposalPlanRepository;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.DisposalScenarioComparisonRepository;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.DisposalSimulationRepository;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.SupplyChainDemoData;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.SupplyChainTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DisposalScenarioComparisonServiceTest {

    private SupplyChainQueryService queryService;

    private DisposalSimulationService simulationService;

    private DisposalPlanService planService;

    private DisposalScenarioComparisonService comparisonService;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:comparison-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=MySQL", "sa", ""));
        queryService = new SupplyChainQueryService(new SupplyChainDemoData());
        DisposalRecommendationService recommendationService = new DisposalRecommendationService(queryService);
        simulationService = new DisposalSimulationService(queryService,
                new DisposalSimulationRepository(jdbc, mapper));
        DisposalExplanationService explanationService = mock(DisposalExplanationService.class);
        when(explanationService.explain(anyString(), anyList(), any(), anyList()))
            .thenReturn(new DisposalExplanation("规则解释", "DETERMINISTIC_RULES", false, List.of()));
        when(explanationService.deterministicOnly(anyString(), anyList(), any()))
            .thenReturn(new DisposalExplanation("规则解释", "DETERMINISTIC_RULES", false, List.of()));
        SupplyChainKnowledgeService knowledgeService = mock(SupplyChainKnowledgeService.class);
        when(knowledgeService.retrieve(anyString()))
            .thenReturn(List.of(new KnowledgeCitation("SOP-01", "补货 SOP", "补货前需确认到货日")));
        planService = new DisposalPlanService(queryService, recommendationService, simulationService,
                explanationService, knowledgeService, new DisposalPlanRepository(jdbc, mapper),
                mock(SupplyChainTaskRepository.class));
        comparisonService = new DisposalScenarioComparisonService(simulationService,
                new DisposalScenarioRankingEngine(), new DisposalScenarioComparisonRepository(jdbc, mapper),
                planService, mapper);
    }

    @Test
    void comparesRealSimulationsOnOneSharedBaselineAndPersistsTheFullRecord() {
        var inventoryBefore = queryService.findInventory(null);
        var ordersBefore = queryService.findAllOrders();

        DisposalScenarioComparison comparison = compareReplenishment("MCU 补货对比", 50, 100, 200);

        assertEquals(SimulationScenario.INVENTORY_REPLENISHMENT, comparison.scenario());
        assertEquals("SKU-MCU-001", comparison.sku());
        assertNull(comparison.supplierId());
        assertEquals(3, comparison.candidates().size());
        assertEquals(DisposalScenarioComparisonOutcome.RECOMMENDED, comparison.recommendation().outcome());
        assertEquals("补货 +100 件", comparison.recommendation().recommendedLabel());
        assertEquals(1, comparison.recommendation().recommendedRank());
        assertEquals(330, comparison.baseline().totalShortageUnits());
        assertEquals(3, comparison.baseline().atRiskOrders());
        assertFalse(comparison.realDataMutated());
        assertTrue(comparison.baselineFingerprint().matches("[0-9a-f]{64}"));
        assertEquals(3, comparison.rankings().size());
        assertEquals(DisposalScenarioRankingEngine.RANKING_RULES, comparison.rankingRules());
        assertTrue(comparison.assumptions().stream().anyMatch(item -> item.contains("同一份基线快照")));
        assertTrue(comparison.limitations().stream().anyMatch(item -> item.contains("成本")));
        assertTrue(comparison.evidence().stream().anyMatch(item -> item.contains(comparison.baselineFingerprint())));

        DisposalScenarioCandidate q50 = candidateForQuantity(comparison, 50);
        DisposalScenarioCandidate q100 = candidateForQuantity(comparison, 100);
        DisposalScenarioCandidate q200 = candidateForQuantity(comparison, 200);
        assertEquals(q100.candidateId(), comparison.recommendation().recommendedCandidateId());
        comparison.candidates()
            .forEach(candidate -> assertEquals(comparison.baseline(), candidate.simulation().baseline()));
        assertEquals(70, q50.simulation().simulated().targetSkuAvailableToPromise());
        assertEquals(120, q100.simulation().simulated().targetSkuAvailableToPromise());
        assertEquals(220, q200.simulation().simulated().targetSkuAvailableToPromise());
        assertEquals(-100, q100.simulation().delta().totalShortageUnits());
        assertFalse(q50.simulation().realDataMutated());
        assertFalse(q100.simulation().realDataMutated());
        assertFalse(q200.simulation().realDataMutated());

        DisposalScenarioRecommendation recommendation = comparison.recommendation();
        assertFalse(recommendation.reasons().isEmpty());
        assertFalse(recommendation.tradeoffs().isEmpty());
        assertFalse(recommendation.unknowns().isEmpty());
        assertFalse(recommendation.manualConfirmation().isEmpty());
        assertTrue(recommendation.keyDeltas().stream().anyMatch(delta -> delta.contains("缺口总量")));
        assertTrue(recommendation.evidence().stream().anyMatch(item -> item.contains("共享基线")));

        assertEquals(comparison, comparisonService.comparison(comparison.comparisonId()));
        assertEquals(inventoryBefore, queryService.findInventory(null));
        assertEquals(ordersBefore, queryService.findAllOrders());
        assertEquals(20, queryService.findInventory("SKU-MCU-001").get(0).availableToPromise());

        DisposalScenarioComparisonPage page = comparisonService.comparisons(1, 10);
        assertEquals(1, page.totalElements());
        assertEquals(1, page.totalPages());
        assertEquals(3, page.items().get(0).candidateCount());
        assertEquals(DisposalScenarioComparisonOutcome.RECOMMENDED, page.items().get(0).outcome());
        assertNotNull(page.items().get(0).baselineFingerprint());
        assertEquals(3, page.items().get(0).baselineAtRiskOrders());
        assertEquals(330, page.items().get(0).baselineTotalShortageUnits());
        assertEquals(0, page.items().get(0).bestAtRiskOrdersDelta());
        assertEquals(-100, page.items().get(0).bestTotalShortageUnitsDelta());
        assertEquals(0, page.items().get(0).linkedPlanCount());
    }

    @Test
    void acceptsTwoAndFiveCandidatesAndRejectsCountParameterAndScopeErrors() {
        assertEquals(2, compareReplenishment("两个候选", 50, 100).candidates().size());
        assertEquals(5, compareReplenishment("五个候选", 10, 20, 30, 40, 50).candidates().size());

        assertThrows(IllegalArgumentException.class,
                () -> compareReplenishment("只有一个", 100));
        assertThrows(IllegalArgumentException.class,
                () -> compareReplenishment("六个候选", 10, 20, 30, 40, 50, 60));
        assertThrows(IllegalArgumentException.class,
                () -> comparisonService.compare(new CreateDisposalScenarioComparisonRequest("重复参数",
                        SimulationScenario.INVENTORY_REPLENISHMENT, "SKU-MCU-001", null,
                        List.of(new ScenarioComparisonCandidateRequest(null, 100, null),
                                new ScenarioComparisonCandidateRequest("再次 100", 100, null)))));
        assertThrows(IllegalArgumentException.class,
                () -> comparisonService.compare(new CreateDisposalScenarioComparisonRequest(" ",
                        SimulationScenario.INVENTORY_REPLENISHMENT, "SKU-MCU-001", null,
                        List.of(new ScenarioComparisonCandidateRequest(null, 50, null),
                                new ScenarioComparisonCandidateRequest(null, 100, null)))));
        assertThrows(IllegalArgumentException.class,
                () -> comparisonService.compare(new CreateDisposalScenarioComparisonRequest("缺少 SKU",
                        SimulationScenario.INVENTORY_REPLENISHMENT, null, null,
                        List.of(new ScenarioComparisonCandidateRequest(null, 50, null),
                                new ScenarioComparisonCandidateRequest(null, 100, null)))));
        assertThrows(IllegalArgumentException.class,
                () -> comparisonService.compare(new CreateDisposalScenarioComparisonRequest("错误供应商",
                        SimulationScenario.INVENTORY_REPLENISHMENT, "SKU-MCU-001", "SUP-ALPHA",
                        List.of(new ScenarioComparisonCandidateRequest(null, 50, null),
                                new ScenarioComparisonCandidateRequest(null, 100, null)))));
        assertThrows(IllegalArgumentException.class,
                () -> comparisonService.compare(new CreateDisposalScenarioComparisonRequest("未知 SKU",
                        SimulationScenario.INVENTORY_REPLENISHMENT, "SKU-UNKNOWN", null,
                        List.of(new ScenarioComparisonCandidateRequest(null, 50, null),
                                new ScenarioComparisonCandidateRequest(null, 100, null)))));
        assertThrows(IllegalArgumentException.class,
                () -> comparisonService.compare(new CreateDisposalScenarioComparisonRequest("数量越界",
                        SimulationScenario.INVENTORY_REPLENISHMENT, "SKU-MCU-001", null,
                        List.of(new ScenarioComparisonCandidateRequest(null, 0, null),
                                new ScenarioComparisonCandidateRequest(null, 100, null)))));
        assertThrows(IllegalArgumentException.class,
                () -> comparisonService.compare(new CreateDisposalScenarioComparisonRequest("错误参数",
                        SimulationScenario.INVENTORY_REPLENISHMENT, "SKU-MCU-001", null,
                        List.of(new ScenarioComparisonCandidateRequest(null, 50, 3),
                                new ScenarioComparisonCandidateRequest(null, 100, null)))));
        assertThrows(IllegalArgumentException.class,
                () -> comparisonService.compare(new CreateDisposalScenarioComparisonRequest("超长标签",
                        SimulationScenario.INVENTORY_REPLENISHMENT, "SKU-MCU-001", null,
                        List.of(new ScenarioComparisonCandidateRequest("x".repeat(101), 50, null),
                                new ScenarioComparisonCandidateRequest(null, 100, null)))));
    }

    @Test
    void supportsSupplierDelayCandidatesWithTheSameDeterministicRules() {
        DisposalScenarioComparison comparison = comparisonService.compare(
                new CreateDisposalScenarioComparisonRequest("SUP-GAMMA 延期对比",
                        SimulationScenario.SUPPLIER_DELAY, null, "SUP-GAMMA",
                        List.of(new ScenarioComparisonCandidateRequest("延期 5 天", null, 5),
                                new ScenarioComparisonCandidateRequest("延期 10 天", null, 10),
                                new ScenarioComparisonCandidateRequest("延期 20 天", null, 20))));

        assertEquals(SimulationScenario.SUPPLIER_DELAY, comparison.scenario());
        assertNull(comparison.sku());
        assertEquals("SUP-GAMMA", comparison.supplierId());
        assertEquals(DisposalScenarioComparisonOutcome.TIE_BREAK, comparison.recommendation().outcome());
        assertEquals("延期 5 天", comparison.recommendation().recommendedLabel());
        assertNull(comparison.baseline().targetSkuAvailableToPromise());
        assertTrue(comparison.candidates()
            .stream()
            .allMatch(candidate -> "SUP-GAMMA".equals(candidate.supplierId())));
        assertTrue(comparison.candidates()
            .stream()
            .allMatch(candidate -> candidate.simulation().simulated().atRiskOrders() == 4));
        assertTrue(comparison.rankings().stream().allMatch(ranking -> ranking.rank() == 1));
        assertEquals(comparison, comparisonService.comparison(comparison.comparisonId()));
    }

    @Test
    void savesTheSelectedCandidateAsAPlanWithoutCollapsingTheComparison() {
        DisposalScenarioComparison comparison = compareReplenishment("保存候选方案", 100, 200);
        DisposalScenarioCandidate candidate = candidateForQuantity(comparison, 100);

        DisposalPlan plan = comparisonService.saveCandidateAsPlan(comparison.comparisonId(),
                candidate.candidateId(), new CreateComparisonDisposalPlanRequest("候选 100 处置方案", null, false));

        assertEquals(DisposalPlanStatus.PENDING_CONFIRMATION, plan.status());
        assertEquals("SKU-MCU-001", plan.scopeSku());
        assertNotNull(plan.simulation());
        assertEquals(100, plan.simulation().replenishmentQuantity());
        assertNotEquals(candidate.simulation().simulationId(), plan.simulation().simulationId());
        assertEquals(candidate.simulation().baseline(), plan.simulation().baseline());
        assertEquals(candidate.simulation().simulated(), plan.simulation().simulated());
        assertEquals(candidate.simulation().delta(), plan.simulation().delta());
        assertEquals(plan.simulation(), simulationService.findSimulation(plan.simulation().simulationId()));
        assertEquals(1, simulationService.history(10, plan.planId()).size());
        assertFalse(plan.recommendations().isEmpty());
        assertFalse(plan.explanation().isBlank());

        DisposalScenarioComparison reloaded = comparisonService.comparison(comparison.comparisonId());
        assertEquals(2, reloaded.candidates().size());
        assertEquals(plan.planId(), candidateForQuantity(reloaded, 100).linkedPlanId());
        assertNull(candidateForQuantity(reloaded, 200).linkedPlanId());
        assertEquals(1, comparisonService.comparisons(1, 10).items().get(0).linkedPlanCount());

        assertThrows(IllegalStateException.class,
                () -> comparisonService.saveCandidateAsPlan(comparison.comparisonId(), candidate.candidateId(),
                        new CreateComparisonDisposalPlanRequest("重复保存", null, false)));

        String selectedId = plan.recommendations().get(0).recommendationId();
        DisposalPlan selected = planService.updateStatus(plan.planId(),
                new UpdateDisposalPlanStatusRequest(DisposalPlanStatus.SELECTED, selectedId));
        assertEquals(DisposalPlanStatus.SELECTED, selected.status());
        assertNotNull(selected.selectedRecommendationId());
    }

    @Test
    void linksAnExistingPlanAndEnforcesScopeSimulationAndStatusRules() {
        DisposalScenarioComparison comparison = compareReplenishment("关联已有方案", 100, 200);
        DisposalScenarioCandidate q100 = candidateForQuantity(comparison, 100);
        DisposalScenarioCandidate q200 = candidateForQuantity(comparison, 200);
        DisposalPlan matchingPlan = planService.create(new CreateDisposalPlanRequest("已有 100 方案", null,
                "SKU-MCU-001", null, null,
                new SimulationRequest(SimulationScenario.INVENTORY_REPLENISHMENT, "SKU-MCU-001", 100, null, null),
                false));

        DisposalScenarioComparison linked = comparisonService.linkCandidateToPlan(comparison.comparisonId(),
                q100.candidateId(), new LinkComparisonCandidatePlanRequest(matchingPlan.planId()));
        assertEquals(matchingPlan.planId(), candidateForQuantity(linked, 100).linkedPlanId());
        assertEquals(1, comparisonService.comparisons(1, 10).items().get(0).linkedPlanCount());
        assertEquals(matchingPlan.planId(),
                comparisonService.linkCandidateToPlan(comparison.comparisonId(), q100.candidateId(),
                        new LinkComparisonCandidatePlanRequest(matchingPlan.planId())).candidates()
                    .stream()
                    .filter(candidate -> candidate.candidateId().equals(q100.candidateId()))
                    .findFirst()
                    .orElseThrow()
                    .linkedPlanId());

        DisposalPlan differentQuantity = planService.create(new CreateDisposalPlanRequest("已有 50 方案", null,
                "SKU-MCU-001", null, null,
                new SimulationRequest(SimulationScenario.INVENTORY_REPLENISHMENT, "SKU-MCU-001", 50, null, null),
                false));
        assertThrows(IllegalArgumentException.class,
                () -> comparisonService.linkCandidateToPlan(comparison.comparisonId(), q200.candidateId(),
                        new LinkComparisonCandidatePlanRequest(differentQuantity.planId())));

        DisposalPlan differentScope = planService.create(new CreateDisposalPlanRequest("传感器方案", null,
                "SKU-SENSOR-002", null, null, null, false));
        assertThrows(IllegalArgumentException.class,
                () -> comparisonService.linkCandidateToPlan(comparison.comparisonId(), q200.candidateId(),
                        new LinkComparisonCandidatePlanRequest(differentScope.planId())));

        DisposalPlan noSimulation = planService.create(new CreateDisposalPlanRequest("无模拟方案", null,
                "SKU-MCU-001", null, null, null, false));
        comparisonService.linkCandidateToPlan(comparison.comparisonId(), q200.candidateId(),
                new LinkComparisonCandidatePlanRequest(noSimulation.planId()));

        DisposalScenarioComparison second = compareReplenishment("第二个对比", 150, 250);
        DisposalScenarioCandidate q150 = candidateForQuantity(second, 150);
        DisposalScenarioCandidate q250 = candidateForQuantity(second, 250);
        comparisonService.linkCandidateToPlan(second.comparisonId(), q150.candidateId(),
                new LinkComparisonCandidatePlanRequest(noSimulation.planId()));
        assertThrows(IllegalStateException.class,
                () -> comparisonService.linkCandidateToPlan(second.comparisonId(), q250.candidateId(),
                        new LinkComparisonCandidatePlanRequest(noSimulation.planId())));

        DisposalPlan rejected = planService.create(new CreateDisposalPlanRequest("将被拒绝的方案", null,
                "SKU-MCU-001", null, null, null, false));
        planService.updateStatus(rejected.planId(),
                new UpdateDisposalPlanStatusRequest(DisposalPlanStatus.REJECTED, null));
        assertThrows(IllegalStateException.class,
                () -> comparisonService.linkCandidateToPlan(second.comparisonId(), q250.candidateId(),
                        new LinkComparisonCandidatePlanRequest(rejected.planId())));

        assertThrows(SupplyChainResourceNotFoundException.class,
                () -> comparisonService.linkCandidateToPlan(comparison.comparisonId(), q200.candidateId(),
                        new LinkComparisonCandidatePlanRequest("missing-plan")));
        assertThrows(SupplyChainResourceNotFoundException.class,
                () -> comparisonService.linkCandidateToPlan("missing-comparison", q200.candidateId(),
                        new LinkComparisonCandidatePlanRequest(matchingPlan.planId())));
        assertThrows(SupplyChainResourceNotFoundException.class,
                () -> comparisonService.linkCandidateToPlan(comparison.comparisonId(), "missing-candidate",
                        new LinkComparisonCandidatePlanRequest(matchingPlan.planId())));
        assertThrows(SupplyChainResourceNotFoundException.class,
                () -> comparisonService.saveCandidateAsPlan(comparison.comparisonId(), "missing-candidate",
                        new CreateComparisonDisposalPlanRequest("方案", null, false)));
    }

    @Test
    void comparisonHistoryIsPagedInReverseCreationOrder() {
        DisposalScenarioComparisonPage empty = comparisonService.comparisons(1, 10);
        assertEquals(0, empty.totalElements());
        assertEquals(0, empty.totalPages());

        compareReplenishment("第一个对比", 50, 100);
        compareReplenishment("第二个对比", 100, 200);

        DisposalScenarioComparisonPage first = comparisonService.comparisons(1, 1);
        assertEquals(2, first.totalElements());
        assertEquals(2, first.totalPages());
        assertEquals(1, first.items().size());
        assertEquals("第二个对比", first.items().get(0).title());
        DisposalScenarioComparisonPage second = comparisonService.comparisons(2, 1);
        assertEquals("第一个对比", second.items().get(0).title());

        assertThrows(IllegalArgumentException.class, () -> comparisonService.comparisons(0, 10));
        assertThrows(IllegalArgumentException.class, () -> comparisonService.comparisons(1, 0));
        assertThrows(IllegalArgumentException.class, () -> comparisonService.comparisons(1, 51));
        assertThrows(SupplyChainResourceNotFoundException.class, () -> comparisonService.comparison("missing"));
    }

    private DisposalScenarioComparison compareReplenishment(String title, int... quantities) {
        List<ScenarioComparisonCandidateRequest> candidates = Arrays.stream(quantities)
            .mapToObj(quantity -> new ScenarioComparisonCandidateRequest(null, quantity, null))
            .toList();
        return comparisonService.compare(new CreateDisposalScenarioComparisonRequest(title,
                SimulationScenario.INVENTORY_REPLENISHMENT, "SKU-MCU-001", null, candidates));
    }

    private static DisposalScenarioCandidate candidateForQuantity(DisposalScenarioComparison comparison,
            int quantity) {
        return comparison.candidates()
            .stream()
            .filter(candidate -> Integer.valueOf(quantity).equals(candidate.replenishmentQuantity()))
            .findFirst()
            .orElseThrow();
    }
}
