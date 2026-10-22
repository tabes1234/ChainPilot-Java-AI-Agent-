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

import com.alibaba.cloud.ai.dataagent.supplychain.model.DecisionProposal;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DecisionTaskResult;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalActionType;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalPlan;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalPlanStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalRecommendation;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalPriority;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioCandidate;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioComparison;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioComparisonOutcome;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioRanking;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioRecommendation;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalSimulation;
import com.alibaba.cloud.ai.dataagent.supplychain.model.ProposalActionType;
import com.alibaba.cloud.ai.dataagent.supplychain.model.ProposalStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskAnalysisReport;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskLevel;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxExecutionEligibility;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SimulationDelta;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SimulationMetrics;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SimulationScenario;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainOrder;
import com.alibaba.cloud.ai.dataagent.supplychain.model.OrderRiskAssessment;
import com.alibaba.cloud.ai.dataagent.supplychain.model.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SupplyChainDecisionServiceTest {

    private SupplyChainAgentTools agentTools;

    private SupplyChainKnowledgeService knowledgeService;

    private SupplyChainAnalysisService analysisService;

    private ActionProposalService proposalService;

    private SupplyChainEventRecorder eventRecorder;

    private SupplyChainDecisionService decisionService;

    @BeforeEach
    void setUp() {
        agentTools = mock(SupplyChainAgentTools.class);
        knowledgeService = mock(SupplyChainKnowledgeService.class);
        analysisService = mock(SupplyChainAnalysisService.class);
        proposalService = mock(ActionProposalService.class);
        eventRecorder = mock(SupplyChainEventRecorder.class);
        decisionService = new SupplyChainDecisionService(new SupplyChainDecisionIntentParser(), agentTools,
                knowledgeService, analysisService, proposalService, eventRecorder);
        when(knowledgeService.retrieve(anyString())).thenReturn(List.of());
    }

    @Test
    void naturalLanguageComparisonCallsTheDeterministicToolAndGroundsTheModelOnItsRealMetrics() {
        DisposalScenarioComparison comparison = replenishmentComparison();
        when(agentTools.compareReplenishmentForWorkflow("SKU-MCU-001", "50,100,200")).thenReturn(comparison);
        when(analysisService.complete(anyString(), anyString(), eq("DECISION_SUMMARY")))
            .thenReturn(new SupplyChainAnalysisService.ModelInvocation(
                    "推荐 CAND-100：三个候选中风险订单下降最多，且干预量小于 CAND-200。"));

        DecisionTaskResult result = decisionService.decide("task-compare",
                "比较 SKU-MCU-001 补货 50、100、200 件的方案");

        assertEquals("COMPARE_REPLENISHMENT", result.intent());
        assertEquals("COMPLETED", result.status());
        assertTrue(result.toolCalls().contains("compareDisposalScenarios"));
        assertFalse(result.clarificationRequired());
        assertEquals("MODEL_GROUNDED", result.narrativeSource());
        assertTrue(result.modelAssisted());
        assertFalse(result.fallbackUsed());
        assertSame(comparison, result.comparison());
        assertNull(result.simulation());
        assertFalse(result.realDataMutated());
        assertTrue(result.summary().startsWith("推荐 CAND-100"));
        assertTrue(result.summary().contains("候选 CAND-50｜补货 50 件｜排名 3"));
        assertTrue(result.summary().contains("候选 CAND-100｜补货 100 件｜排名 1"));
        assertTrue(result.summary().contains("候选 CAND-200｜补货 200 件｜排名 2"));
        assertTrue(result.summary().contains("风险订单Δ=-6"));
        assertTrue(result.summary().contains("对比结论：推荐补货 100 件"));
        assertTrue(result.summary().contains("未知项：供应商产能未建模"));
        assertTrue(result.summary().contains("本结果只读"));

        verify(agentTools).compareReplenishmentForWorkflow("SKU-MCU-001", "50,100,200");
        verify(agentTools, never()).compareSupplierDelayForWorkflow(anyString(), anyString());
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(analysisService).complete(anyString(), prompt.capture(), eq("DECISION_SUMMARY"));
        assertTrue(prompt.getValue().contains("CAND-50"));
        assertTrue(prompt.getValue().contains("CAND-100"));
        assertTrue(prompt.getValue().contains("排名 1"));
        assertTrue(prompt.getValue().contains("风险订单Δ=-6"));
        assertTrue(prompt.getValue().contains("未知项：供应商产能未建模"));
        verify(eventRecorder).intentRecognized("task-compare", "COMPARE_REPLENISHMENT",
                "补货对比 SKU=SKU-MCU-001，数量=[50, 100, 200]");
    }

    @Test
    void supplierDelayComparisonUsesTheDelayToolAndTheActualDelayCandidates() {
        DisposalScenarioComparison comparison = supplierDelayComparison();
        when(agentTools.compareSupplierDelayForWorkflow("SUP-ALPHA", "3,5,7")).thenReturn(comparison);
        when(analysisService.complete(anyString(), anyString(), eq("DECISION_SUMMARY")))
            .thenReturn(new SupplyChainAnalysisService.ModelInvocation("延期 3 天候选表现最好。"));

        DecisionTaskResult result = decisionService.decide("task-delay",
                "比较供应商 SUP-ALPHA 延期 3、5、7 天的方案");

        assertEquals("COMPARE_SUPPLIER_DELAY", result.intent());
        assertTrue(result.toolCalls().contains("compareSupplierDelayScenarios"));
        assertEquals(3, result.comparison().candidates().size());
        assertTrue(result.summary().contains("延期 3 天"));
        verify(agentTools).compareSupplierDelayForWorkflow("SUP-ALPHA", "3,5,7");
        verify(agentTools, never()).compareReplenishmentForWorkflow(anyString(), anyString());
    }

    @Test
    void modelFailureFallsBackToDeterministicEvidenceWithoutFabricatingResults() {
        when(agentTools.compareReplenishmentForWorkflow("SKU-MCU-001", "50,100,200"))
            .thenReturn(replenishmentComparison());
        when(analysisService.complete(anyString(), anyString(), eq("DECISION_SUMMARY")))
            .thenThrow(new IllegalStateException("model unavailable"));

        DecisionTaskResult result = decisionService.decide("task-fallback",
                "比较 SKU-MCU-001 补货 50、100、200 件的方案");

        assertEquals("DETERMINISTIC_FALLBACK", result.narrativeSource());
        assertFalse(result.modelAssisted());
        assertTrue(result.fallbackUsed());
        assertTrue(result.summary().contains("确定性推荐候选 CAND-100"));
        assertTrue(result.summary().contains("候选 CAND-200｜补货 200 件｜排名 2"));
        assertSame(SimulationScenario.INVENTORY_REPLENISHMENT, result.comparison().scenario());
        verify(eventRecorder).modelFailed(any(), eq("DECISION_SUMMARY"), anyLong(), any(Throwable.class));
        verify(eventRecorder).fallbackUsed(any(), eq("DECISION_SUMMARY"), eq("IllegalStateException"));
    }

    @Test
    void cancellationFromDecisionNarrativeIsPropagatedInsteadOfFallingBack() {
        when(agentTools.compareReplenishmentForWorkflow("SKU-MCU-001", "50,100,200"))
            .thenReturn(replenishmentComparison());
        when(analysisService.complete(anyString(), anyString(), eq("DECISION_SUMMARY")))
            .thenThrow(new SupplyChainTaskCancelledException("task-cancelled"));

        assertThrows(SupplyChainTaskCancelledException.class,
                () -> decisionService.decide("task-cancelled", "比较 SKU-MCU-001 补货 50、100、200 件的方案"));
    }

    @Test
    void emptyModelResponseFallsBackInsteadOfAcceptingBlankEvidence() {
        when(agentTools.simulateInventoryReplenishmentForWorkflow("SKU-MCU-001", 100))
            .thenReturn(replenishmentSimulation());
        when(analysisService.complete(anyString(), anyString(), eq("DECISION_SUMMARY")))
            .thenReturn(new SupplyChainAnalysisService.ModelInvocation("   "));

        DecisionTaskResult result = decisionService.decide("task-empty", "模拟给 SKU-MCU-001 补货 100 件");

        assertEquals("SIMULATE_REPLENISHMENT", result.intent());
        assertEquals("DETERMINISTIC_FALLBACK", result.narrativeSource());
        assertTrue(result.fallbackUsed());
        assertTrue(result.summary().contains("风险订单 6 → 2"));
        verify(eventRecorder).modelCompleted(any(), eq("DECISION_SUMMARY"), anyLong(),
                eq("model returned an empty decision narrative; token usage unavailable"));
        verify(eventRecorder).fallbackUsed(any(), eq("DECISION_SUMMARY"), eq("EMPTY_MODEL_RESPONSE"));
        verifyNoInteractions(proposalService);
    }

    @Test
    void ambiguousComparisonAsksForTheMissingSkuWithoutCallingAnyBusinessTool() {
        DecisionTaskResult result = decisionService.decide("task-ambiguous", "比较补货 50、100、200 件的方案");

        assertEquals("UNSUPPORTED", result.intent());
        assertTrue(result.clarificationRequired());
        assertTrue(result.clarifications().get(0).contains("SKU"));
        assertTrue(result.toolCalls().stream().noneMatch(call -> call.startsWith("compare")));
        assertEquals(List.of("intent:UNSUPPORTED"), result.toolCalls());
        verifyNoInteractions(agentTools);
        verifyNoInteractions(analysisService);
        verify(proposalService, never()).create(anyString(), any(), anyString(), anyString(), anyMap(), anyList());
        verify(eventRecorder).clarificationRequired("task-ambiguous", "UNSUPPORTED", result.summary());
    }

    @Test
    void deterministicToolFailurePropagatesWithoutAFabricatedComparison() {
        when(agentTools.compareReplenishmentForWorkflow("SKU-MCU-001", "50,100,200"))
            .thenThrow(new IllegalStateException("comparison store unavailable"));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> decisionService.decide("task-tool-failure", "比较 SKU-MCU-001 补货 50、100、200 件的方案"));

        assertEquals("comparison store unavailable", failure.getMessage());
        verifyNoInteractions(analysisService);
        verify(eventRecorder).fallbackUsed("task-tool-failure", "DECISION", "IllegalStateException");
    }

    @Test
    void sandboxRequestOnlyCreatesAPendingProposalAndNeverStartsExecution() {
        String planId = UUID.randomUUID().toString();
        DisposalPlan plan = selectedPlan(planId);
        SandboxExecutionEligibility eligibility = eligible(planId);
        when(agentTools.findDisposalPlanForWorkflow(planId)).thenReturn(plan);
        when(agentTools.checkSandboxExecutionEligibilityForWorkflow(planId)).thenReturn(eligibility);
        DecisionProposal proposal = proposal(planId);
        when(proposalService.create(eq("task-sandbox"), eq(ProposalActionType.START_SANDBOX_EXECUTION),
                eq("启动方案 " + planId + " 的沙箱演练"), eq(planId), anyMap(), anyList())).thenReturn(proposal);

        DecisionTaskResult result = decisionService.decide("task-sandbox", "启动方案 " + planId + " 的沙箱演练");

        assertEquals("PROPOSE_START_SANDBOX", result.intent());
        assertEquals(1, result.proposals().size());
        assertEquals(ProposalStatus.PENDING, result.proposals().get(0).status());
        assertTrue(result.summary().contains(proposal.proposalId()));
        assertTrue(result.summary().contains("不会自动执行"));
        assertNull(result.execution());
        assertTrue(result.sandboxEligibility().eligible());
        verify(agentTools, never()).findSandboxExecutionsForWorkflow(anyString(), anyInt());
        verifyNoInteractions(analysisService);
        verify(proposalService).create(eq("task-sandbox"), eq(ProposalActionType.START_SANDBOX_EXECUTION),
                eq("启动方案 " + planId + " 的沙箱演练"), eq(planId), anyMap(), anyList());
    }

    @Test
    void cancellationAfterProposalCreationCompensatesTheProposal() {
        String planId = UUID.randomUUID().toString();
        DisposalPlan plan = selectedPlan(planId);
        SandboxExecutionEligibility eligibility = eligible(planId);
        when(agentTools.findDisposalPlanForWorkflow(planId)).thenReturn(plan);
        when(agentTools.checkSandboxExecutionEligibilityForWorkflow(planId)).thenReturn(eligibility);
        DecisionProposal proposal = proposal(planId);
        when(proposalService.create(anyString(), any(), anyString(), anyString(), anyMap(), anyList()))
            .thenAnswer(invocation -> {
                TaskCancellationRegistry.request("task-proposal-cancel");
                return proposal;
            });

        try {
            assertThrows(SupplyChainTaskCancelledException.class,
                    () -> decisionService.decide("task-proposal-cancel", "启动方案 " + planId + " 的沙箱演练"));
        }
        finally {
            TaskCancellationRegistry.reset();
        }

        verify(proposalService).cancel(proposal.proposalId(), proposal.contentHash());
    }

    @Test
    void ineligibleSandboxPlanIsReportedWithoutCreatingAProposal() {
        String planId = UUID.randomUUID().toString();
        DisposalPlan plan = selectedPlan(planId);
        when(agentTools.findDisposalPlanForWorkflow(planId)).thenReturn(plan);
        when(agentTools.checkSandboxExecutionEligibilityForWorkflow(planId)).thenReturn(new SandboxExecutionEligibility(
                planId, DisposalPlanStatus.PENDING_CONFIRMATION, false, null, DisposalActionType.REPLENISH_INVENTORY,
                null, List.of("plan status must be SELECTED"), "方案尚未选定", Instant.now()));

        DecisionTaskResult result = decisionService.decide("task-ineligible", "启动方案 " + planId + " 的沙箱演练");

        assertTrue(result.proposals().isEmpty());
        assertFalse(result.sandboxEligibility().eligible());
        assertTrue(result.summary().contains("不满足沙箱执行条件"));
        assertTrue(result.summary().contains("方案尚未选定"));
        verify(proposalService, never()).create(anyString(), any(), anyString(), anyString(), anyMap(), anyList());
    }

    @Test
    void analysisIntentDelegatesToTheExistingAnalysisServiceAndScopesTheResult() {
        SupplyChainOrder mcuOrder = new SupplyChainOrder("ORD-1", "SKU-MCU-001", 120, "SUP-ALPHA",
                LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 30), OrderStatus.CONFIRMED);
        SupplyChainOrder otherOrder = new SupplyChainOrder("ORD-2", "SKU-SENSOR-002", 80, "SUP-BETA",
                LocalDate.of(2026, 10, 26), LocalDate.of(2026, 10, 28), OrderStatus.CONFIRMED);
        RiskAnalysisReport report = new RiskAnalysisReport("task-analysis", "COMPLETED", "分析摘要",
                List.of("查询订单", "评估风险"), true,
                List.of(new OrderRiskAssessment(mcuOrder, RiskLevel.HIGH, 75, 40, List.of("库存不足"),
                        List.of("补货")),
                        new OrderRiskAssessment(otherOrder, RiskLevel.MEDIUM, 40, 10, List.of("交期接近"),
                                List.of("跟进供应商"))),
                List.of("REC-1"), List.of("findAtRiskOrders", "findInventory"), List.of(), false, 2, Instant.now());
        when(analysisService.analyze("分析 SKU-MCU-001 的风险", "task-analysis")).thenReturn(report);

        DecisionTaskResult result = decisionService.decide("task-analysis", "分析 SKU-MCU-001 的风险");

        assertEquals("ANALYSIS", result.intent());
        assertEquals(1, result.atRiskOrders().size());
        assertEquals("ORD-1", result.atRiskOrders().get(0).order().orderId());
        assertEquals("DETERMINISTIC_FALLBACK", result.narrativeSource());
        assertTrue(result.summary().contains("分析工具返回 2 个风险订单，当前范围 1 个；高风险 1 个"));
        assertTrue(result.summary().contains("范围 SKU：SKU-MCU-001"));
        assertTrue(result.toolCalls().containsAll(List.of("findAtRiskOrders", "findInventory")));
        verify(analysisService).analyze("分析 SKU-MCU-001 的风险", "task-analysis");
        verifyNoInteractions(proposalService);
    }

    private static DisposalScenarioComparison replenishmentComparison() {
        Instant now = Instant.now();
        LocalDate asOf = LocalDate.of(2026, 10, 20);
        DisposalSimulation baseline50 = simulation("SIM-50", 50, metrics(24, 6, 3, 240, 900, 80),
                metrics(24, 4, 2, 220, 880, 130), new SimulationDelta(2, -2, -1, -1, -20, -40, 50));
        DisposalSimulation baseline100 = simulation("SIM-100", 100, metrics(24, 6, 3, 240, 900, 80),
                metrics(24, 0, 0, 170, 700, 180), new SimulationDelta(4, -6, -3, -2, -70, -200, 100));
        DisposalSimulation baseline200 = simulation("SIM-200", 200, metrics(24, 6, 3, 240, 900, 80),
                metrics(24, 0, 0, 170, 700, 280), new SimulationDelta(4, -6, -3, -2, -70, -200, 200));
        List<DisposalScenarioCandidate> candidates = List.of(
                candidate("CAND-50", "补货 50 件", 1, 50, baseline50),
                candidate("CAND-100", "补货 100 件", 2, 100, baseline100),
                candidate("CAND-200", "补货 200 件", 3, 200, baseline200));
        List<DisposalScenarioRanking> rankings = List.of(
                ranking("CAND-200", "补货 200 件", 2, false, "补货数量=200 件", -6),
                ranking("CAND-100", "补货 100 件", 1, true, "补货数量=100 件", -6),
                ranking("CAND-50", "补货 50 件", 3, false, "补货数量=50 件", -2));
        DisposalScenarioRecommendation recommendation = new DisposalScenarioRecommendation(
                DisposalScenarioComparisonOutcome.RECOMMENDED, "CAND-100", "补货 100 件", 1,
                "推荐补货 100 件：在共同基线下风险订单降幅最大，且干预量小于 CAND-200。",
                List.of("风险订单Δ=-6", "缺货件Δ=-70"), List.of("高风险订单降至 0"),
                List.of("补货 200 件未带来额外风险改善"), List.of("假设供应商可按时到货"),
                List.of("供应商产能未建模"), List.of("补货成本需人工确认"), List.of("真实模拟记录 SIM-100"), "只读沙箱");
        return new DisposalScenarioComparison("CMP-1", "MCU 补货对比", SimulationScenario.INVENTORY_REPLENISHMENT,
                "SKU-MCU-001", null, asOf, "H2 demo baseline", "baseline-fingerprint", now,
                metrics(24, 6, 3, 240, 900, 80), -6, -70, candidates, rankings, recommendation,
                List.of("高风险降幅优先"), List.of("同一份基线快照"), List.of("成本未建模"), List.of("共享基线"),
                false, "只读", now, now);
    }

    private static DisposalScenarioComparison supplierDelayComparison() {
        Instant now = Instant.now();
        LocalDate asOf = LocalDate.of(2026, 10, 20);
        List<DisposalScenarioCandidate> candidates = List.of(
                candidate("CAND-3D", "延期 3 天", 1, null, delaySimulation("SIM-3D", 3)),
                candidate("CAND-5D", "延期 5 天", 2, null, delaySimulation("SIM-5D", 5)),
                candidate("CAND-7D", "延期 7 天", 3, null, delaySimulation("SIM-7D", 7)));
        List<DisposalScenarioRanking> rankings = List.of(
                new DisposalScenarioRanking("CAND-3D", "延期 3 天", "额外延期=3 天", 1, false, true, true,
                        "风险订单Δ最小", List.of("风险订单Δ=-2"), List.of("交期可控"), List.of("需供应商确认")),
                new DisposalScenarioRanking("CAND-5D", "延期 5 天", "额外延期=5 天", 2, false, false, true,
                        "风险订单Δ较小", List.of("风险订单Δ=-4"), List.of("影响部分订单"), List.of("需供应商确认")),
                new DisposalScenarioRanking("CAND-7D", "延期 7 天", "额外延期=7 天", 3, false, false, true,
                        "风险订单Δ最大", List.of("风险订单Δ=-6"), List.of("影响更多订单"), List.of("需供应商确认")));
        DisposalScenarioRecommendation recommendation = new DisposalScenarioRecommendation(
                DisposalScenarioComparisonOutcome.RECOMMENDED, "CAND-3D", "延期 3 天", 1, "推荐延期 3 天。",
                List.of("风险订单Δ=-2"), List.of("影响最小"), List.of("仍需供应商确认"), List.of("假设订单未取消"),
                List.of("供应商实际产能未建模"), List.of("需人工确认"), List.of("真实模拟记录"), "只读沙箱");
        return new DisposalScenarioComparison("CMP-2", "供应商延期对比", SimulationScenario.SUPPLIER_DELAY, null,
                "SUP-ALPHA", asOf, "H2 demo baseline", "baseline-fingerprint-delay", now,
                metrics(24, 6, 3, 240, 900, null), -2, -10, candidates, rankings, recommendation,
                List.of("高风险降幅优先"), List.of("同一份基线快照"), List.of("成本未建模"), List.of("共享基线"),
                false, "只读", now, now);
    }

    private static DisposalSimulation replenishmentSimulation() {
        return simulation("SIM-100", 100, metrics(24, 6, 3, 240, 900, 80),
                metrics(24, 2, 1, 180, 760, 180), new SimulationDelta(4, -4, -2, -2, -60, -140, 100));
    }

    private static DisposalSimulation simulation(String id, Integer quantity, SimulationMetrics baseline,
            SimulationMetrics simulated, SimulationDelta delta) {
        return new DisposalSimulation(id, SimulationScenario.INVENTORY_REPLENISHMENT, "SKU-MCU-001", null, quantity,
                null, LocalDate.of(2026, 10, 20), "baseline-fingerprint", baseline, simulated, delta, List.of(),
                List.of("假设订单未取消"), List.of("成本未建模"), List.of("真实模拟记录"), false, "只读沙箱",
                Instant.now());
    }

    private static DisposalSimulation delaySimulation(String id, int days) {
        SimulationMetrics baseline = metrics(24, 6, 3, 240, 900, null);
        SimulationMetrics simulated = metrics(24, 6 - days / 2, 3 - days / 3, 240, 900 - days * 10, null);
        return new DisposalSimulation(id, SimulationScenario.SUPPLIER_DELAY, null, "SUP-ALPHA", null, days,
                LocalDate.of(2026, 10, 20), "baseline-fingerprint-delay", baseline, simulated,
                new SimulationDelta(1, -days / 2, -days / 3, 0, -days * 10, -days * 20, null), List.of(),
                List.of("假设订单未取消"), List.of("成本未建模"), List.of("真实模拟记录"), false, "只读沙箱",
                Instant.now());
    }

    private static SimulationMetrics metrics(int totalOrders, int atRiskOrders, int highRiskOrders,
            int ordersWithShortage, int totalShortageUnits, Integer targetAtp) {
        return new SimulationMetrics(totalOrders, atRiskOrders, highRiskOrders, ordersWithShortage,
                totalShortageUnits, atRiskOrders * 20, targetAtp);
    }

    private static DisposalScenarioCandidate candidate(String id, String label, int position, Integer quantity,
            DisposalSimulation simulation) {
        return new DisposalScenarioCandidate(id, label, position, simulation.scenario(), simulation.sku(),
                simulation.supplierId(), quantity, simulation.additionalDelayDays(), simulation, null, Instant.now());
    }

    private static DisposalScenarioRanking ranking(String id, String label, Integer rank, boolean recommended,
            String parameterSummary, int atRiskDelta) {
        return new DisposalScenarioRanking(id, label, parameterSummary, rank, false, recommended, true, "确定性排序规则",
                List.of("风险订单Δ=" + atRiskDelta), List.of("高风险订单变化"), List.of("权衡说明"));
    }

    private static DisposalPlan selectedPlan(String planId) {
        Instant now = Instant.now();
        DisposalRecommendation recommendation = new DisposalRecommendation("REC-1", DisposalActionType.REPLENISH_INVENTORY,
                DisposalPriority.P1, 90, "高风险订单", RiskLevel.HIGH, "SKU-MCU-001", "SUP-ALPHA",
                List.of("ORD-1"), "紧急补货", "补货 100 件", "高风险订单需要补货", List.of("缺货 40 件"), "降低风险",
                List.of("供应商可到货"), List.of("成本待确认"), List.of("模拟 SIM-100"), List.of("假设订单未取消"),
                "PENDING", now);
        return new DisposalPlan(planId, "MCU 补货方案", "task-sandbox", "SKU-MCU-001", null,
                DisposalPlanStatus.SELECTED, "REC-1", List.of(recommendation), replenishmentSimulation(),
                List.of(), "规则解释", "DETERMINISTIC_RULES", false, List.of(), now, now);
    }

    private static SandboxExecutionEligibility eligible(String planId) {
        return new SandboxExecutionEligibility(planId, DisposalPlanStatus.SELECTED, true, "REC-1",
                DisposalActionType.REPLENISH_INVENTORY, "SIM-100", List.of("plan SELECTED", "simulation available"),
                "满足全部条件", Instant.now());
    }

    private static DecisionProposal proposal(String planId) {
        Instant now = Instant.now();
        return new DecisionProposal("PROP-1", "task-sandbox", ProposalActionType.START_SANDBOX_EXECUTION,
                "启动方案 " + planId + " 的沙箱演练", planId, Map.of(), List.of("方案 " + planId), "content-hash",
                ProposalStatus.PENDING, now, now.plusSeconds(1800), null, null, null);
    }

}
