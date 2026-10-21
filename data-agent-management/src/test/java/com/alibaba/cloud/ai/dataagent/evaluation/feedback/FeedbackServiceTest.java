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
package com.alibaba.cloud.ai.dataagent.evaluation.feedback;

import com.alibaba.cloud.ai.dataagent.supplychain.model.OrderRiskAssessment;
import com.alibaba.cloud.ai.dataagent.supplychain.model.OrderStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskAnalysisReport;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskEvidence;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskLevel;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainOrder;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.DecisionProposalRepository;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.SupplyChainTaskRepository;
import com.alibaba.cloud.ai.dataagent.supplychain.service.SupplyChainWorkflowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FeedbackServiceTest {

    private static final String TASK_ID = "feedback-task-1";

    private FeedbackRepository repository;

    private FeedbackService service;

    private RiskAnalysisReport report;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:feedback-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        repository = new FeedbackRepository(jdbc, mapper);
        SupplyChainTaskRepository taskRepository = mock(SupplyChainTaskRepository.class);
        SupplyChainWorkflowService workflow = mock(SupplyChainWorkflowService.class);
        DecisionProposalRepository proposalRepository = mock(DecisionProposalRepository.class);
        when(taskRepository.findTaskType(TASK_ID)).thenReturn(Optional.of("ANALYSIS"));
        when(taskRepository.findObservableTask(TASK_ID)).thenReturn(Optional.of(
                new SupplyChainTaskRepository.ObservableTask(TASK_ID, "测试问题", "COMPLETED", "DONE", 0, null,
                        Instant.now(), "ANALYSIS", null, null)));
        when(workflow.task(TASK_ID)).thenReturn(new SupplyChainTaskStatus(TASK_ID, "COMPLETED", "DONE", 0, null,
                Instant.now()));
        report = report();
        when(workflow.report(TASK_ID)).thenReturn(report);
        service = new FeedbackService(repository, taskRepository, workflow, proposalRepository, mapper);
    }

    @Test
    void feedbackIsDeduplicatedAndCarriesTheDisplayedResultSnapshot() {
        FeedbackRequest request = new FeedbackRequest(TASK_ID, null, FeedbackType.INACCURATE_RISK,
                "  判断依据需要复核  ");

        FeedbackRecord first = service.submit(request);
        FeedbackRecord second = service.submit(request);

        assertEquals(first.feedbackId(), second.feedbackId());
        assertEquals(1, service.count(null, null, null, TASK_ID));
        assertEquals(1, service.stats().total());
        assertEquals("DETERMINISTIC_FALLBACK", first.narrativeSource());
        assertEquals(1, first.snapshot().orders().size());
        assertEquals("ORD-FEEDBACK-1", first.snapshot().orders().get(0).orderId());
        assertEquals(List.of("DELAY"), first.evidenceIds());
        assertFalse(first.snapshot().summary().isBlank());
    }

    @Test
    void draftStartsWithoutExpectedAnswerAndRequiresReviewBeforeEnable() {
        FeedbackRecord feedback = service.submit(new FeedbackRequest(TASK_ID, null,
                FeedbackType.INSUFFICIENT_EVIDENCE, "证据还不够完整"));

        FeedbackDraft draft = service.createDraft(feedback.feedbackId());
        assertEquals(FeedbackService.DRAFT, draft.status());
        assertTrue(draft.expectedBehavior().isBlank());
        assertTrue(draft.assertions().isEmpty());
        assertFalse(draft.suggestedAssertions().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> service.review(draft.draftId()));

        FeedbackDraft updated = service.updateDraft(draft.draftId(), new FeedbackDraftUpdateRequest(
                "风险订单数量保持为 1",
                List.of(new FeedbackAssertion("a1", FeedbackAssertionType.AT_RISK_ORDER_COUNT, null, "1", "风险订单数"),
                        new FeedbackAssertion("a2", FeedbackAssertionType.RISK_SCORE, "ORD-FEEDBACK-1", "30",
                                "订单风险分"))));
        assertEquals(FeedbackService.READY_FOR_REVIEW, updated.status());
        FeedbackDraft enabled = service.enable(updated.draftId());
        assertEquals(FeedbackService.ENABLED, enabled.status());
        assertNotNull(enabled.caseId());
        assertTrue(repository.findEnabledCase(enabled.caseId()).isPresent());
        assertEquals(1, service.stats().enabledCases());
    }

    @Test
    void invalidFeedbackAndUnreviewedDraftCannotBecomeACase() {
        assertThrows(IllegalArgumentException.class, () -> service.submit(
                new FeedbackRequest(TASK_ID, null, null, "没有类型")));
        assertThrows(IllegalArgumentException.class, () -> service.submit(
                new FeedbackRequest("missing-task", null, FeedbackType.OTHER, "")));
        FeedbackRecord feedback = service.submit(new FeedbackRequest(TASK_ID, null, FeedbackType.OTHER, ""));
        FeedbackDraft draft = service.createDraft(feedback.feedbackId());
        assertThrows(IllegalStateException.class, () -> service.enable(draft.draftId()));
        assertThrows(IllegalArgumentException.class, () -> service.updateDraft(draft.draftId(),
                new FeedbackDraftUpdateRequest("x", List.of(new FeedbackAssertion("bad",
                        FeedbackAssertionType.RISK_LEVEL, "UNKNOWN-ORDER", "HIGH", "bad")))));
    }

    private static RiskAnalysisReport report() {
        LocalDate today = LocalDate.now();
        SupplyChainOrder order = new SupplyChainOrder("ORD-FEEDBACK-1", "SKU-MCU-001", 10, "SUP-ALPHA", today,
                today.plusDays(2), OrderStatus.IN_TRANSIT);
        RiskEvidence evidence = new RiskEvidence("DELAY", "交付延期", "lateDays=2", "estimated <= requested",
                "延期计 30 分", 30, "order dates", true);
        OrderRiskAssessment assessment = new OrderRiskAssessment(order, RiskLevel.MEDIUM, 30, 0,
                List.of("Late delivery"), List.of("Confirm recovery date"), List.of(evidence), List.of());
        return new RiskAnalysisReport(TASK_ID, "COMPLETED", "规则摘要", List.of(), false, List.of(assessment),
                List.of("Confirm recovery date"), List.of(), List.of(), false, 0, Instant.now());
    }
}
