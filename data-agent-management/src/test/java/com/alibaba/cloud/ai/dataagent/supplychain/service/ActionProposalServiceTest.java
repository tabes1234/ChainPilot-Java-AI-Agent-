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
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalActionType;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalPlan;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalPlanStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalPriority;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalRecommendation;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioCandidate;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioComparison;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioComparisonOutcome;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioRanking;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioRecommendation;
import com.alibaba.cloud.ai.dataagent.supplychain.model.ProposalActionType;
import com.alibaba.cloud.ai.dataagent.supplychain.model.ProposalStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskLevel;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxExecution;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxExecutionEligibility;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxExecutionMode;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxExecutionStartResult;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxExecutionStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxExecutionStep;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxStepStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SimulationDelta;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SimulationMetrics;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SimulationScenario;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskType;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalSimulation;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.DecisionProposalRepository;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.SupplyChainTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActionProposalServiceTest {

    private static final String TASK_ID = "task-decision";

    private static final String PLAN_ID = "11111111-1111-1111-1111-111111111111";

    private static final String OTHER_PLAN_ID = "22222222-2222-2222-2222-222222222222";

    private static final String COMPARISON_ID = "33333333-3333-3333-3333-333333333333";

    private static final String CANDIDATE_ID = "CAND-100";

    private static final String RECOMMENDATION_ID = "REC-1";

    private DecisionProposalRepository proposalRepository;

    private SupplyChainTaskRepository taskRepository;

    private DisposalScenarioComparisonService comparisonService;

    private DisposalPlanService planService;

    private DisposalExecutionService executionService;

    private SupplyChainEventRecorder eventRecorder;

    private ActionProposalService proposalService;

    private Map<String, DecisionProposal> store;

    @BeforeEach
    void setUp() {
        proposalRepository = mock(DecisionProposalRepository.class);
        taskRepository = mock(SupplyChainTaskRepository.class);
        comparisonService = mock(DisposalScenarioComparisonService.class);
        planService = mock(DisposalPlanService.class);
        executionService = mock(DisposalExecutionService.class);
        eventRecorder = mock(SupplyChainEventRecorder.class);
        proposalService = new ActionProposalService(proposalRepository, taskRepository, comparisonService,
                planService, executionService, eventRecorder);
        store = new LinkedHashMap<>();

        when(taskRepository.findTask(TASK_ID)).thenReturn(Optional.of(
                new SupplyChainTaskStatus(TASK_ID, "COMPLETED", "DONE", 0, null, Instant.now())));
        when(taskRepository.findTaskType(TASK_ID)).thenReturn(Optional.of(SupplyChainTaskType.DECISION));
        when(proposalRepository.find(anyString()))
            .thenAnswer(invocation -> Optional.ofNullable(store.get(invocation.getArgument(0))));
        when(proposalRepository.findPendingByTaskAndHash(anyString(), anyString()))
            .thenAnswer(invocation -> store.values()
                .stream()
                .filter(item -> item.taskId().equals(invocation.getArgument(0))
                        && item.contentHash().equals(invocation.getArgument(1))
                        && item.status() == ProposalStatus.PENDING)
                .findFirst());
        doAnswer(invocation -> {
            DecisionProposal proposal = invocation.getArgument(0);
            store.put(proposal.proposalId(), proposal);
            return null;
        }).when(proposalRepository).save(any(DecisionProposal.class));
        when(proposalRepository.claim(anyString(), any())).thenAnswer(invocation -> {
            DecisionProposal current = store.get(invocation.getArgument(0));
            if (current == null || current.status() != ProposalStatus.PENDING) {
                return 0;
            }
            store.put(current.proposalId(), withStatus(current, ProposalStatus.EXECUTING, null, null, null));
            return 1;
        });
        doAnswer(invocation -> {
            DecisionProposal current = store.get(invocation.getArgument(0));
            store.put(current.proposalId(), withStatus(current, ProposalStatus.CONFIRMED,
                    invocation.getArgument(1), null, invocation.getArgument(2)));
            return null;
        }).when(proposalRepository).markConfirmed(anyString(), anyMap(), any());
        doAnswer(invocation -> {
            DecisionProposal current = store.get(invocation.getArgument(0));
            store.put(current.proposalId(), withStatus(current, ProposalStatus.CANCELLED, null, null,
                    invocation.getArgument(1)));
            return null;
        }).when(proposalRepository).markCancelled(anyString(), any());
        doAnswer(invocation -> {
            DecisionProposal current = store.get(invocation.getArgument(0));
            store.put(current.proposalId(), withStatus(current, ProposalStatus.EXPIRED, null, null, null));
            return null;
        }).when(proposalRepository).markExpired(anyString());
        doAnswer(invocation -> {
            DecisionProposal current = store.get(invocation.getArgument(0));
            store.put(current.proposalId(), withStatus(current, ProposalStatus.FAILED, current.result(),
                    invocation.getArgument(1), current.confirmedAt()));
            return null;
        }).when(proposalRepository).markFailed(anyString(), anyString());

        when(planService.plan(PLAN_ID)).thenReturn(selectedPlan(PLAN_ID));
        when(planService.plan(OTHER_PLAN_ID)).thenReturn(selectedPlan(OTHER_PLAN_ID));
        when(executionService.eligibility(PLAN_ID)).thenReturn(eligible(PLAN_ID));
        when(executionService.eligibility(OTHER_PLAN_ID)).thenReturn(eligible(OTHER_PLAN_ID));
    }

    @Test
    void creatingAProposalDoesNotExecuteTheActionOrCreateExecutionRecords() {
        DecisionProposal proposal = proposalService.create(TASK_ID, ProposalActionType.START_SANDBOX_EXECUTION,
                "启动方案 " + PLAN_ID + " 的沙箱演练", PLAN_ID, Map.of(), List.of(" 方案已选定 "));

        assertEquals(ProposalStatus.PENDING, proposal.status());
        assertEquals(List.of("方案已选定"), proposal.evidence());
        assertTrue(proposal.parameters().isEmpty());
        assertEquals(30 * 60, Duration.between(proposal.createdAt(), proposal.expiresAt()).getSeconds());
        verify(executionService, never()).start(anyString(), any());
        verify(planService, never()).updateStatus(anyString(), any());
        verify(comparisonService, never()).saveCandidateAsPlan(anyString(), anyString(), any());
        verify(eventRecorder).proposalCreated(TASK_ID, proposal.proposalId(), "START_SANDBOX_EXECUTION", PLAN_ID);
    }

    @Test
    void identicalPendingContentReplaysTheSameProposalInsteadOfDuplicatingIt() {
        DecisionProposal first = proposalService.create(TASK_ID, ProposalActionType.START_SANDBOX_EXECUTION,
                "启动沙箱演练", PLAN_ID, Map.of(), List.of());
        DecisionProposal replay = proposalService.create(TASK_ID, ProposalActionType.START_SANDBOX_EXECUTION,
                "启动沙箱演练", PLAN_ID, Map.of(), List.of());

        assertEquals(first.proposalId(), replay.proposalId());
        verify(proposalRepository, times(1)).save(any(DecisionProposal.class));
        verify(eventRecorder, times(1)).proposalCreated(TASK_ID, first.proposalId(), "START_SANDBOX_EXECUTION",
                PLAN_ID);
    }

    @Test
    void proposalsCannotBelongToAnalysisTasks() {
        when(taskRepository.findTaskType(TASK_ID)).thenReturn(Optional.of(SupplyChainTaskType.ANALYSIS));

        assertThrows(IllegalArgumentException.class,
                () -> proposalService.create(TASK_ID, ProposalActionType.START_SANDBOX_EXECUTION, "启动沙箱演练",
                        PLAN_ID, Map.of(), List.of()));

        verify(proposalRepository, never()).save(any(DecisionProposal.class));
        verify(executionService, never()).start(anyString(), any());
    }

    @Test
    void confirmationWithAChangedContentHashIsRejectedWithoutExecuting() {
        DecisionProposal proposal = proposalService.create(TASK_ID, ProposalActionType.START_SANDBOX_EXECUTION,
                "启动沙箱演练", PLAN_ID, Map.of(), List.of());

        assertThrows(IllegalArgumentException.class,
                () -> proposalService.confirm(proposal.proposalId(), "different-content-hash"));

        assertEquals(ProposalStatus.PENDING, store.get(proposal.proposalId()).status());
        verify(executionService, never()).start(anyString(), any());
        verify(proposalRepository, never()).markConfirmed(anyString(), anyMap(), any());
    }

    @Test
    void oldConfirmationCannotAuthorizeAProposalWithDifferentContentOrTarget() {
        DecisionProposal first = proposalService.create(TASK_ID, ProposalActionType.START_SANDBOX_EXECUTION,
                "启动沙箱演练", PLAN_ID, Map.of(), List.of());
        DecisionProposal second = proposalService.create(TASK_ID, ProposalActionType.START_SANDBOX_EXECUTION,
                "启动沙箱演练", OTHER_PLAN_ID, Map.of(), List.of());

        assertNotEquals(first.contentHash(), second.contentHash());
        assertThrows(IllegalArgumentException.class,
                () -> proposalService.confirm(second.proposalId(), first.contentHash()));

        assertEquals(ProposalStatus.PENDING, store.get(second.proposalId()).status());
        verify(executionService, never()).start(anyString(), any());
    }

    @Test
    void confirmationRevalidatesEligibilityAgainstTheCurrentPlanState() {
        when(executionService.eligibility(PLAN_ID)).thenReturn(eligible(PLAN_ID))
            .thenReturn(new SandboxExecutionEligibility(PLAN_ID, DisposalPlanStatus.PENDING_CONFIRMATION, false,
                    null, DisposalActionType.REPLENISH_INVENTORY, null, List.of("plan status must be SELECTED"),
                    "方案已不再满足执行条件", Instant.now()));
        DecisionProposal proposal = proposalService.create(TASK_ID, ProposalActionType.START_SANDBOX_EXECUTION,
                "启动沙箱演练", PLAN_ID, Map.of(), List.of());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> proposalService.confirm(proposal.proposalId(), proposal.contentHash()));

        assertTrue(failure.getMessage().contains("not eligible"));
        assertEquals(ProposalStatus.FAILED, store.get(proposal.proposalId()).status());
        assertTrue(store.get(proposal.proposalId()).errorMessage().contains("not eligible"));
        verify(executionService, never()).start(anyString(), any());
        verify(proposalRepository).markFailed(eq(proposal.proposalId()), anyString());
        verify(eventRecorder).proposalFailed(eq(TASK_ID), eq(proposal.proposalId()), any(Throwable.class));
    }

    @Test
    void confirmationExecutesExactlyOnceAndDuplicateConfirmationReplaysTheResult() {
        SandboxExecution execution = completedExecution();
        when(executionService.start(eq(PLAN_ID), any()))
            .thenReturn(new SandboxExecutionStartResult(execution, true));
        DecisionProposal proposal = proposalService.create(TASK_ID, ProposalActionType.START_SANDBOX_EXECUTION,
                "启动沙箱演练", PLAN_ID, Map.of(), List.of());

        DecisionProposal confirmed = proposalService.confirm(proposal.proposalId(), proposal.contentHash());

        assertEquals(ProposalStatus.CONFIRMED, confirmed.status());
        assertEquals("EXE-1", confirmed.result().get("executionId"));
        assertFalse((Boolean) confirmed.result().get("realDataMutated"));
        ArgumentCaptor<com.alibaba.cloud.ai.dataagent.supplychain.model.CreateSandboxExecutionRequest> request =
                ArgumentCaptor.forClass(
                        com.alibaba.cloud.ai.dataagent.supplychain.model.CreateSandboxExecutionRequest.class);
        verify(executionService).start(eq(PLAN_ID), request.capture());
        assertEquals("proposal-" + TASK_ID + "-" + PLAN_ID, request.getValue().idempotencyKey());
        verify(proposalRepository).markConfirmed(eq(proposal.proposalId()), anyMap(), any());

        DecisionProposal replay = proposalService.confirm(proposal.proposalId(), proposal.contentHash());

        assertEquals(ProposalStatus.CONFIRMED, replay.status());
        assertEquals("EXE-1", replay.result().get("executionId"));
        verify(executionService, times(1)).start(anyString(), any());
        verify(proposalRepository, times(1)).markConfirmed(eq(proposal.proposalId()), anyMap(), any());
    }

    @Test
    void confirmationStopsWhenTheTaskIsNoLongerCompleted() {
        DecisionProposal proposal = proposalService.create(TASK_ID, ProposalActionType.START_SANDBOX_EXECUTION,
                "启动沙箱演练", PLAN_ID, Map.of(), List.of());
        when(taskRepository.findTask(TASK_ID)).thenReturn(Optional.of(
                new SupplyChainTaskStatus(TASK_ID, "RUNNING", "DECISION_EXECUTION", 0, null, Instant.now())));

        assertThrows(IllegalStateException.class,
                () -> proposalService.confirm(proposal.proposalId(), proposal.contentHash()));

        verify(executionService, never()).start(anyString(), any());
    }

    @Test
    void expiredProposalCannotBeConfirmed() {
        String contentHash = ActionProposalService.contentHash(TASK_ID,
                ProposalActionType.START_SANDBOX_EXECUTION, "启动沙箱演练", PLAN_ID, Map.of());
        Instant now = Instant.now();
        DecisionProposal expired = new DecisionProposal("P-EXPIRED", TASK_ID,
                ProposalActionType.START_SANDBOX_EXECUTION, "启动沙箱演练", PLAN_ID, Map.of(), List.of(), contentHash,
                ProposalStatus.PENDING, now.minus(Duration.ofHours(1)), now.minus(Duration.ofMinutes(30)), null, null,
                null);
        store.put(expired.proposalId(), expired);

        assertThrows(IllegalStateException.class,
                () -> proposalService.confirm(expired.proposalId(), expired.contentHash()));

        verify(proposalRepository).markExpired("P-EXPIRED");
        verify(executionService, never()).start(anyString(), any());
    }

    @Test
    void cancelledProposalCannotBeConfirmed() {
        DecisionProposal proposal = proposalService.create(TASK_ID, ProposalActionType.START_SANDBOX_EXECUTION,
                "启动沙箱演练", PLAN_ID, Map.of(), List.of());

        assertThrows(IllegalArgumentException.class,
                () -> proposalService.cancel(proposal.proposalId(), "wrong-hash"));
        assertEquals(ProposalStatus.PENDING, store.get(proposal.proposalId()).status());

        DecisionProposal cancelled = proposalService.cancel(proposal.proposalId(), proposal.contentHash());

        assertEquals(ProposalStatus.CANCELLED, cancelled.status());
        verify(eventRecorder).proposalCancelled(TASK_ID, proposal.proposalId());
        assertThrows(IllegalStateException.class,
                () -> proposalService.confirm(proposal.proposalId(), proposal.contentHash()));
        verify(executionService, never()).start(anyString(), any());
    }

    @Test
    void savingACandidateIsNotExecutedBeforeConfirmationAndRevalidatesAtConfirmation() {
        when(comparisonService.comparison(COMPARISON_ID)).thenReturn(comparison(null));
        Map<String, Object> parameters = Map.of("comparisonId", COMPARISON_ID, "title", "处置方案：MCU 补货对比 / 补货 100 件",
                "generateExplanation", false);
        DecisionProposal proposal = proposalService.create(TASK_ID, ProposalActionType.SAVE_CANDIDATE_AS_PLAN,
                "保存候选 补货 100 件 为处置方案", CANDIDATE_ID, parameters, List.of("对比 CMP-1"));

        verify(comparisonService, never()).saveCandidateAsPlan(anyString(), anyString(), any());
        assertEquals(ProposalStatus.PENDING, proposal.status());

        when(comparisonService.comparison(COMPARISON_ID)).thenReturn(comparison("PLAN-EXISTING"));

        assertThrows(IllegalStateException.class,
                () -> proposalService.confirm(proposal.proposalId(), proposal.contentHash()));

        verify(comparisonService, never()).saveCandidateAsPlan(anyString(), anyString(), any());
        assertEquals(ProposalStatus.FAILED, store.get(proposal.proposalId()).status());
        assertNotNull(store.get(proposal.proposalId()).errorMessage());
    }

    @Test
    void selectPlanProposalRevalidatesTheRecommendationAndPlanState() {
        when(planService.plan(PLAN_ID)).thenReturn(planWithStatus(PLAN_ID, DisposalPlanStatus.PENDING_CONFIRMATION));
        Map<String, Object> parameters = Map.of("recommendationId", RECOMMENDATION_ID);
        DecisionProposal proposal = proposalService.create(TASK_ID, ProposalActionType.SELECT_PLAN,
                "选定方案 " + PLAN_ID + " 的推荐建议", PLAN_ID, parameters, List.of("方案证据"));

        verify(planService, never()).updateStatus(anyString(), any());
        assertEquals(ProposalStatus.PENDING, proposal.status());

        when(planService.plan(PLAN_ID)).thenReturn(planWithStatus(PLAN_ID, DisposalPlanStatus.REJECTED));

        assertThrows(IllegalStateException.class,
                () -> proposalService.confirm(proposal.proposalId(), proposal.contentHash()));

        verify(planService, never()).updateStatus(anyString(), any());
        assertEquals(ProposalStatus.FAILED, store.get(proposal.proposalId()).status());
    }

    private static DecisionProposal withStatus(DecisionProposal proposal, ProposalStatus status,
            Map<String, Object> result, String errorMessage, Instant confirmedAt) {
        return new DecisionProposal(proposal.proposalId(), proposal.taskId(), proposal.actionType(),
                proposal.title(), proposal.targetId(), proposal.parameters(), proposal.evidence(),
                proposal.contentHash(), status, proposal.createdAt(), proposal.expiresAt(), confirmedAt, result,
                errorMessage);
    }

    private static DisposalPlan selectedPlan(String planId) {
        return planWithStatus(planId, DisposalPlanStatus.SELECTED);
    }

    private static DisposalPlan planWithStatus(String planId, DisposalPlanStatus status) {
        Instant now = Instant.now();
        DisposalRecommendation recommendation = new DisposalRecommendation(RECOMMENDATION_ID,
                DisposalActionType.REPLENISH_INVENTORY, DisposalPriority.P1, 90, "高风险订单",
                RiskLevel.HIGH, "SKU-MCU-001", "SUP-ALPHA", List.of("ORD-1"), "紧急补货", "补货 100 件",
                "高风险订单需要补货", List.of("缺货 40 件"), "降低风险", List.of("供应商可到货"),
                List.of("成本待确认"), List.of("模拟 SIM-100"), List.of("假设订单未取消"), "PENDING", now);
        return new DisposalPlan(planId, "MCU 补货方案", TASK_ID, "SKU-MCU-001", null, status,
                status == DisposalPlanStatus.SELECTED ? RECOMMENDATION_ID : null, List.of(recommendation),
                simulation(), List.of(), "规则解释", "DETERMINISTIC_RULES", false, List.of(), now, now);
    }

    private static DisposalSimulation simulation() {
        Instant now = Instant.now();
        return new DisposalSimulation("SIM-100", SimulationScenario.INVENTORY_REPLENISHMENT, "SKU-MCU-001", null,
                100, null, LocalDate.of(2026, 10, 20), "baseline-fingerprint",
                new SimulationMetrics(24, 6, 3, 5, 240, 900, 80), new SimulationMetrics(24, 2, 1, 2, 180, 500, 180),
                new SimulationDelta(4, -4, -2, -2, -60, -140, 100), List.of(), List.of("假设订单未取消"),
                List.of("成本未建模"), List.of("真实模拟记录"), false, "只读沙箱", now);
    }

    private static SandboxExecutionEligibility eligible(String planId) {
        return new SandboxExecutionEligibility(planId, DisposalPlanStatus.SELECTED, true, RECOMMENDATION_ID,
                DisposalActionType.REPLENISH_INVENTORY, "SIM-100", List.of("plan SELECTED", "simulation available"),
                "满足全部条件", Instant.now());
    }

    private static SandboxExecution completedExecution() {
        Instant now = Instant.now();
        SandboxExecutionStep step = new SandboxExecutionStep(1, "VALIDATE", "校验方案", SandboxStepStatus.SUCCEEDED,
                "方案 " + PLAN_ID, "通过", "校验完成", null, now, now);
        return new SandboxExecution("EXE-1", PLAN_ID, RECOMMENDATION_ID, DisposalActionType.REPLENISH_INVENTORY,
                SandboxExecutionMode.SANDBOX, SandboxExecutionStatus.SUCCEEDED, "SIM-100",
                "proposal-" + TASK_ID + "-" + PLAN_ID, List.of(step), null, List.of("方案 " + PLAN_ID),
                "沙箱演练完成", List.of(), null, false, "仅本地沙箱", now, now, now, now);
    }

    private static DisposalScenarioComparison comparison(String linkedPlanId) {
        Instant now = Instant.now();
        LocalDate asOf = LocalDate.of(2026, 10, 20);
        DisposalSimulation simulation = simulation();
        DisposalScenarioCandidate candidate = new DisposalScenarioCandidate(CANDIDATE_ID, "补货 100 件", 1,
                SimulationScenario.INVENTORY_REPLENISHMENT, "SKU-MCU-001", null, 100, null, simulation, linkedPlanId,
                now);
        DisposalScenarioRanking ranking = new DisposalScenarioRanking(CANDIDATE_ID, "补货 100 件", "补货数量=100 件", 1,
                false, true, true, "确定性排序规则", List.of("风险订单Δ=-4"), List.of("高风险订单下降"),
                List.of("成本待确认"));
        DisposalScenarioRecommendation recommendation = new DisposalScenarioRecommendation(
                DisposalScenarioComparisonOutcome.RECOMMENDED, CANDIDATE_ID, "补货 100 件", 1, "推荐补货 100 件。",
                List.of("风险订单Δ=-4"), List.of("高风险订单下降"), List.of("成本待确认"), List.of("假设订单未取消"),
                List.of("供应商产能未建模"), List.of("补货成本需人工确认"), List.of("真实模拟记录"), "只读沙箱");
        return new DisposalScenarioComparison(COMPARISON_ID, "MCU 补货对比",
                SimulationScenario.INVENTORY_REPLENISHMENT, "SKU-MCU-001", null, asOf, "H2 demo baseline",
                "baseline-fingerprint", now, new SimulationMetrics(24, 6, 3, 5, 240, 900, 80), -4, -60,
                List.of(candidate), List.of(ranking), recommendation, List.of("高风险降幅优先"),
                List.of("同一份基线快照"), List.of("成本未建模"), List.of("共享基线"), false, "只读", now, now);
    }

}
