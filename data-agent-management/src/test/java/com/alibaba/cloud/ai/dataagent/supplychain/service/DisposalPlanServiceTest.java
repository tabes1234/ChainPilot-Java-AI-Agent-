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

import com.alibaba.cloud.ai.dataagent.supplychain.model.CreateDisposalPlanRequest;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DecisionTaskResult;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalExplanation;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalPlan;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalPlanPage;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalPlanStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.KnowledgeCitation;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SimulationRequest;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SimulationScenario;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskType;
import com.alibaba.cloud.ai.dataagent.supplychain.model.UpdateDisposalPlanStatusRequest;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.DisposalPlanRepository;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.DisposalSimulationRepository;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.SupplyChainDemoData;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.SupplyChainTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DisposalPlanServiceTest {

    private DisposalSimulationService simulationService;

    private DisposalPlanService planService;

    private SupplyChainTaskRepository taskRepository;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:plan-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=MySQL", "sa", ""));
        SupplyChainQueryService queryService = new SupplyChainQueryService(new SupplyChainDemoData());
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
        taskRepository = mock(SupplyChainTaskRepository.class);
        planService = new DisposalPlanService(queryService, recommendationService, simulationService,
                explanationService, knowledgeService, new DisposalPlanRepository(jdbc, mapper), taskRepository);
    }

    @Test
    void createsAndReloadsAPlanWithRecommendationsAndEvidence() {
        DisposalPlan plan = planService.create(new CreateDisposalPlanRequest("MCU 补货方案", null, "SKU-MCU-001",
                null, null, null, null));

        assertEquals(DisposalPlanStatus.PENDING_CONFIRMATION, plan.status());
        assertNull(plan.selectedRecommendationId());
        assertEquals("SKU-MCU-001", plan.scopeSku());
        assertEquals(2, plan.recommendations().size());
        assertNull(plan.simulation());
        assertEquals(1, plan.evidence().size());
        assertEquals("规则解释", plan.explanation());
        assertEquals("DETERMINISTIC_RULES", plan.explanationSource());
        assertTrue(plan.recommendations()
            .stream()
            .allMatch(recommendation -> recommendation.affectedOrderIds().contains("ORD-1001")));

        DisposalPlan reloaded = planService.plan(plan.planId());
        assertEquals(plan, reloaded);

        DisposalPlanPage page = planService.plans(null, 1, 10);
        assertEquals(1, page.totalElements());
        assertEquals(1, page.items().size());
        assertEquals(2, page.items().get(0).recommendationCount());
        assertNull(page.items().get(0).simulationScenario());
    }

    @Test
    void createsAPlanWithANewSimulationPersistedAndAttached() {
        DisposalPlan plan = planService.create(new CreateDisposalPlanRequest("MCU 补货模拟方案", null, "SKU-MCU-001",
                null, null, new SimulationRequest(SimulationScenario.INVENTORY_REPLENISHMENT, "SKU-MCU-001", 100,
                        null, null),
                null));

        assertNotNull(plan.simulation());
        assertEquals(120, plan.simulation().simulated().targetSkuAvailableToPromise());
        assertEquals(100, plan.simulation().delta().targetSkuAvailableToPromise());
        assertEquals(plan.simulation(),
                simulationService.findSimulation(plan.simulation().simulationId()));
        assertEquals(1, simulationService.history(10, plan.planId()).size());
        assertEquals(plan.simulation().simulationId(),
                simulationService.history(10, plan.planId()).get(0).simulationId());
        assertEquals(plan, planService.plan(plan.planId()));
    }

    @Test
    void reusesAnExistingSimulationAndRejectsScopeMismatches() {
        var saved = simulationService.simulateAndSave(new SimulationRequest(
                SimulationScenario.INVENTORY_REPLENISHMENT, "SKU-MCU-001", 40, null, null));

        DisposalPlan plan = planService.create(new CreateDisposalPlanRequest("复用模拟方案", null, "SKU-MCU-001",
                null, saved.simulationId(), null, false));

        assertEquals(saved.simulationId(), plan.simulation().simulationId());
        assertEquals(1, simulationService.history(10, plan.planId()).size());

        IllegalArgumentException mismatch = assertThrows(IllegalArgumentException.class,
                () -> planService.create(new CreateDisposalPlanRequest("错误范围方案", null, "SKU-SENSOR-002", null,
                        saved.simulationId(), null, true)));
        assertTrue(mismatch.getMessage().contains("does not match"));

        assertThrows(IllegalArgumentException.class,
                () -> planService.create(new CreateDisposalPlanRequest("互斥方案", null, "SKU-MCU-001", null,
                        saved.simulationId(),
                        new SimulationRequest(SimulationScenario.INVENTORY_REPLENISHMENT, "SKU-MCU-001", 10, null,
                                null),
                        true)));
    }

    @Test
    void createsAPlanFromADecisionTaskUsingTheAttachedSimulationScope() {
        var saved = simulationService.simulateAndSave(new SimulationRequest(
                SimulationScenario.INVENTORY_REPLENISHMENT, "SKU-MCU-001", 100, null, null));
        when(taskRepository.findTask("decision-task")).thenReturn(Optional.of(new SupplyChainTaskStatus(
                "decision-task", "COMPLETED", "COMPLETE", 0, null, Instant.now())));
        when(taskRepository.findTaskType("decision-task")).thenReturn(Optional.of(SupplyChainTaskType.DECISION));
        DecisionTaskResult decision = mock(DecisionTaskResult.class);
        when(decision.atRiskOrders()).thenReturn(List.of());
        when(taskRepository.findDecision("decision-task")).thenReturn(Optional.of(decision));

        DisposalPlan plan = planService.create(new CreateDisposalPlanRequest("决策任务方案", "decision-task", null,
                null, saved.simulationId(), null, false));

        assertEquals("SKU-MCU-001", plan.scopeSku());
        assertEquals(saved.simulationId(), plan.simulation().simulationId());
        assertFalse(plan.recommendations().isEmpty());
        assertEquals(plan, planService.plan(plan.planId()));
    }

    @Test
    void updatesPlanStatusWithSelectionRules() {
        DisposalPlan plan = planService.create(new CreateDisposalPlanRequest("状态方案", null, "SKU-MCU-001", null,
                null, null, true));
        String selectedId = plan.recommendations().get(0).recommendationId();

        DisposalPlan selected = planService.updateStatus(plan.planId(),
                new UpdateDisposalPlanStatusRequest(DisposalPlanStatus.SELECTED, selectedId));
        assertEquals(DisposalPlanStatus.SELECTED, selected.status());
        assertEquals(selectedId, selected.selectedRecommendationId());
        assertEquals(selected, planService.plan(plan.planId()));

        assertThrows(IllegalArgumentException.class,
                () -> planService.updateStatus(plan.planId(),
                        new UpdateDisposalPlanStatusRequest(DisposalPlanStatus.SELECTED, "unknown-recommendation")));
        assertThrows(IllegalArgumentException.class,
                () -> planService.updateStatus(plan.planId(),
                        new UpdateDisposalPlanStatusRequest(DisposalPlanStatus.PENDING_CONFIRMATION, selectedId)));

        DisposalPlan rejected = planService.updateStatus(plan.planId(),
                new UpdateDisposalPlanStatusRequest(DisposalPlanStatus.REJECTED, null));
        assertEquals(DisposalPlanStatus.REJECTED, rejected.status());
        assertNull(rejected.selectedRecommendationId());
        assertEquals(1, planService.plans(DisposalPlanStatus.REJECTED, 1, 10).totalElements());
        assertEquals(0, planService.plans(DisposalPlanStatus.SELECTED, 1, 10).totalElements());
    }

    @Test
    void rejectsUnknownPlansTasksAndScopesWithoutAtRiskOrders() {
        assertThrows(SupplyChainResourceNotFoundException.class, () -> planService.plan("missing-plan"));
        assertThrows(SupplyChainResourceNotFoundException.class,
                () -> planService.updateStatus("missing-plan",
                        new UpdateDisposalPlanStatusRequest(DisposalPlanStatus.REJECTED, null)));

        when(taskRepository.findTask("missing-task")).thenReturn(Optional.empty());
        assertThrows(SupplyChainResourceNotFoundException.class,
                () -> planService.create(new CreateDisposalPlanRequest("任务方案", "missing-task", null, null, null,
                        null, true)));

        when(taskRepository.findTask("unfinished-task")).thenReturn(Optional.of(new SupplyChainTaskStatus(
                "unfinished-task", "RUNNING", "LOAD_ORDERS", 0, null, Instant.now())));
        when(taskRepository.findReport("unfinished-task")).thenReturn(Optional.empty());
        assertThrows(IllegalStateException.class,
                () -> planService.create(new CreateDisposalPlanRequest("未完成任务方案", "unfinished-task", null, null,
                        null, null, true)));

        assertThrows(IllegalArgumentException.class,
                () -> planService.create(new CreateDisposalPlanRequest("低风险范围", null, "SKU-VALVE-003", null, null,
                        null, true)));
        assertThrows(IllegalArgumentException.class,
                () -> planService.create(new CreateDisposalPlanRequest(" ", null, "SKU-MCU-001", null, null, null,
                        true)));
        assertThrows(IllegalArgumentException.class, () -> planService.plans(null, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> planService.plans(null, 1, 51));
    }

}
