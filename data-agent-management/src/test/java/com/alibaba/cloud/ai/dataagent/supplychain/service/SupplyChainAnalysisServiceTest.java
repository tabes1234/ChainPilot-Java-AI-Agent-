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
package com.alibaba.cloud.ai.dataagent.supplychain.service;

import com.alibaba.cloud.ai.dataagent.service.aimodelconfig.AiModelRegistry;
import com.alibaba.cloud.ai.dataagent.supplychain.model.OrderRiskAssessment;
import com.alibaba.cloud.ai.dataagent.supplychain.model.OrderStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskAnalysisReport;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskEvidence;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskLevel;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainOrder;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainEventStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainEventType;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskEvent;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.WorkflowPlan;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.SupplyChainEventRepository;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.SupplyChainTaskRepository;
import com.alibaba.cloud.ai.dataagent.util.ChatResponseUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.client.ResourceAccessException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SupplyChainAnalysisServiceTest {

    private static final String TASK_ID = "task-analysis-events";

    private AiModelRegistry modelRegistry;

    private ChatClient.ChatClientRequestSpec requestSpec;

    private ChatClient.CallResponseSpec callResponseSpec;

    private SupplyChainAnalysisService analysisService;

    private SupplyChainEventRecorder eventRecorder;

    private SupplyChainEventRepository eventRepository;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:chainpilot-analysis-" + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", ""));
        SupplyChainTaskRepository taskRepository = new SupplyChainTaskRepository(jdbc, objectMapper);
        taskRepository.create(new SupplyChainTaskStatus(TASK_ID, "RUNNING", "PLANNING", 0, null, Instant.now()),
                "分析风险订单");
        eventRepository = new SupplyChainEventRepository(jdbc);
        eventRecorder = new SupplyChainEventRecorder(eventRepository);

        modelRegistry = mock(AiModelRegistry.class);
        ChatClient chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        callResponseSpec = mock(ChatClient.CallResponseSpec.class);
        when(modelRegistry.getChatClient()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.toolCallbacks(any(ToolCallback[].class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);

        SupplyChainKnowledgeService knowledgeService = mock(SupplyChainKnowledgeService.class);
        when(knowledgeService.retrieve(anyString())).thenReturn(List.of());
        analysisService = new SupplyChainAnalysisService(mock(SupplyChainQueryService.class),
                new SupplyChainAgentTools(mock(SupplyChainQueryService.class),
                        mock(DisposalRecommendationService.class), mock(DisposalSimulationService.class),
                        mock(DisposalExecutionService.class), mock(DisposalScenarioComparisonService.class),
                        mock(DisposalPlanService.class), eventRecorder),
                modelRegistry, knowledgeService, eventRecorder, ObservationRegistry.NOOP,
                new RiskNarrativeValidator(objectMapper), objectMapper);
    }

    @Test
    void successfulPlannerAndSummaryRecordCompletedEventsWithoutFallback() {
        when(callResponseSpec.chatResponse()).thenReturn(
                ChatResponseUtil.createPureResponse("1. 查询订单风险\n2. 校验数据"),
                ChatResponseUtil.createPureResponse(
                        "{\"overview\":\"模型摘要\",\"reasons\":[],\"businessImpacts\":[],\"recommendations\":[],\"uncertainties\":[]}"));

        try (SupplyChainEventRecorder.TaskScope ignored = eventRecorder.openTask(TASK_ID)) {
            WorkflowPlan plan = analysisService.createPlan("分析风险订单");
            RiskAnalysisReport report = analysisService.analyze("分析风险订单", TASK_ID, List.of(), List.of(),
                    List.of(), 0, plan);

            assertTrue(plan.modelAssisted());
            assertEquals(List.of("查询订单风险", "校验数据"), plan.steps());
            assertTrue(report.modelAssisted());
            assertEquals("模型摘要", report.summary());
            assertEquals(List.of(), report.toolCalls());
        }

        List<SupplyChainTaskEvent> events = events();
        assertEquals(List.of(SupplyChainEventType.MODEL_STARTED, SupplyChainEventType.MODEL_COMPLETED,
                SupplyChainEventType.MODEL_STARTED, SupplyChainEventType.MODEL_COMPLETED),
                events.stream().map(SupplyChainTaskEvent::type).toList());
        assertEquals(List.of("PLANNING", "PLANNING", "GENERATING_REPORT", "GENERATING_REPORT"),
                events.stream().map(SupplyChainTaskEvent::stage).toList());
        assertTrue(events.stream().filter(event -> SupplyChainEventType.MODEL_COMPLETED.equals(event.type()))
                .allMatch(event -> SupplyChainEventStatus.COMPLETED.equals(event.status())));
        assertTrue(events.stream().noneMatch(event -> SupplyChainEventType.FALLBACK_USED.equals(event.type())));
        assertTrue(events.get(1).summary().startsWith("model plan accepted; steps=2"));
        assertTrue(events.get(1).summary().contains("token usage unavailable"));
        assertTrue(events.get(3).summary().startsWith("model narrative accepted; reasons=0"));
    }

    @Test
    void emptyModelSummaryRecordsCompletedThenFallback() {
        when(callResponseSpec.chatResponse()).thenReturn(ChatResponseUtil.createPureResponse("   "));

        RiskAnalysisReport report;
        try (SupplyChainEventRecorder.TaskScope ignored = eventRecorder.openTask(TASK_ID)) {
            report = analysisService.analyze("分析风险订单", TASK_ID, List.of(), List.of(), List.of(), 0,
                    new WorkflowPlan(List.of("查询订单风险"), false));
        }

        assertFalse(report.modelAssisted());
        assertTrue(report.summary().startsWith("当前模拟数据中未发现延期风险订单。"));
        assertEquals(RiskAnalysisReport.NARRATIVE_DETERMINISTIC_FALLBACK, report.narrativeSource());
        assertEquals(List.of(), report.toolCalls());
        List<SupplyChainTaskEvent> events = events();
        assertEquals(List.of(SupplyChainEventType.MODEL_STARTED, SupplyChainEventType.MODEL_COMPLETED,
                SupplyChainEventType.FALLBACK_USED), events.stream().map(SupplyChainTaskEvent::type).toList());
        assertTrue(events.get(2).summary().contains("EMPTY_MODEL_RESPONSE"));
        assertTrue(events.stream().noneMatch(event -> SupplyChainEventType.MODEL_FAILED.equals(event.type())));
    }

    @Test
    void modelFailureRecordsFailedAndFallbackWithoutModelAssistance() {
        when(modelRegistry.getChatClient()).thenThrow(new IllegalStateException("no active model"));

        RiskAnalysisReport report;
        try (SupplyChainEventRecorder.TaskScope ignored = eventRecorder.openTask(TASK_ID)) {
            report = analysisService.analyze("分析风险订单", TASK_ID, List.of(), List.of(), List.of(), 0,
                    new WorkflowPlan(List.of("查询订单风险"), false));
        }

        assertFalse(report.modelAssisted());
        assertEquals(List.of(), report.toolCalls());
        List<SupplyChainTaskEvent> events = events();
        assertEquals(List.of(SupplyChainEventType.MODEL_STARTED, SupplyChainEventType.MODEL_FAILED,
                SupplyChainEventType.FALLBACK_USED), events.stream().map(SupplyChainTaskEvent::type).toList());
        assertEquals("IllegalStateException", events.get(1).errorType());
        assertTrue(events.stream().noneMatch(event -> SupplyChainEventType.MODEL_COMPLETED.equals(event.type())));
    }

    @Test
    void nonEmptyPlannerResponseRecordsCompletedEvent() {
        when(callResponseSpec.chatResponse()).thenReturn(ChatResponseUtil.createPureResponse("查询订单延期风险\n检查库存"));

        WorkflowPlan plan;
        try (SupplyChainEventRecorder.TaskScope ignored = eventRecorder.openTask(TASK_ID)) {
            plan = analysisService.createPlan("分析风险订单");
        }

        assertTrue(plan.modelAssisted());
        assertEquals(List.of("查询订单延期风险", "检查库存"), plan.steps());
        List<SupplyChainTaskEvent> events = events();
        assertEquals(List.of(SupplyChainEventType.MODEL_STARTED, SupplyChainEventType.MODEL_COMPLETED),
                events.stream().map(SupplyChainTaskEvent::type).toList());
        assertEquals("PLANNING", events.get(1).stage());
    }

    @Test
    void emptyPlannerResponseFallsBackWithoutBeingMarkedModelAssisted() {
        when(callResponseSpec.chatResponse()).thenReturn(ChatResponseUtil.createPureResponse(" "));

        WorkflowPlan plan;
        try (SupplyChainEventRecorder.TaskScope ignored = eventRecorder.openTask(TASK_ID)) {
            plan = analysisService.createPlan("分析风险订单");
        }

        assertFalse(plan.modelAssisted());
        assertEquals(5, plan.steps().size());
        List<SupplyChainTaskEvent> events = events();
        assertEquals(List.of(SupplyChainEventType.MODEL_STARTED, SupplyChainEventType.MODEL_COMPLETED,
                SupplyChainEventType.FALLBACK_USED), events.stream().map(SupplyChainTaskEvent::type).toList());
        assertTrue(events.get(2).summary().contains("EMPTY_PLAN_RESPONSE"));
        assertTrue(events.stream().noneMatch(event -> SupplyChainEventType.MODEL_FAILED.equals(event.type())));
    }

    @Test
    void rateLimitedReportCallIsRetriedAndModelSummaryAccepted() {
        when(callResponseSpec.chatResponse())
                .thenThrow(new RuntimeException("429 Too Many Requests"))
                .thenReturn(ChatResponseUtil.createPureResponse(
                        "{\"overview\":\"重试后的模型摘要\",\"reasons\":[],\"businessImpacts\":[],\"recommendations\":[],\"uncertainties\":[]}"));

        RiskAnalysisReport report;
        try (SupplyChainEventRecorder.TaskScope ignored = eventRecorder.openTask(TASK_ID)) {
            report = analysisService.analyze("分析风险订单", TASK_ID, List.of(), List.of(), List.of(), 0,
                    new WorkflowPlan(List.of("查询订单风险"), false));
        }

        assertTrue(report.modelAssisted());
        assertEquals("重试后的模型摘要", report.summary());
        verify(requestSpec, times(2)).call();
        List<SupplyChainTaskEvent> events = events();
        assertEquals(List.of(SupplyChainEventType.MODEL_STARTED, SupplyChainEventType.MODEL_COMPLETED),
                events.stream().map(SupplyChainTaskEvent::type).toList());
        assertEquals("GENERATING_REPORT", events.get(1).stage());
    }

    @Test
    void cancellationDuringRateLimitBackoffPreventsASecondProviderCall() {
        when(callResponseSpec.chatResponse()).thenAnswer(invocation -> {
            TaskCancellationRegistry.request(TASK_ID);
            throw new RuntimeException("429 Too Many Requests");
        });
        try (SupplyChainEventRecorder.TaskScope ignored = eventRecorder.openTask(TASK_ID)) {
            assertThrows(SupplyChainTaskCancelledException.class, () -> analysisService.analyze("分析风险订单",
                    TASK_ID, List.of(), List.of(), List.of(), 0, new WorkflowPlan(List.of("查询订单风险"), false)));
        }
        finally {
            TaskCancellationRegistry.reset();
        }

        verify(requestSpec, times(1)).call();
        List<SupplyChainTaskEvent> events = events();
        assertEquals(List.of(SupplyChainEventType.MODEL_STARTED),
                events.stream().map(SupplyChainTaskEvent::type).toList());
        assertTrue(events.stream().noneMatch(event -> SupplyChainEventType.MODEL_FAILED.equals(event.type())
                || SupplyChainEventType.FALLBACK_USED.equals(event.type())));
    }

    @Test
    void canceledModelInterruptionIsNotRecordedAsAModelFailure() {
        when(callResponseSpec.chatResponse()).thenAnswer(invocation -> {
            TaskCancellationRegistry.request(TASK_ID);
            throw new RuntimeException("request interrupted by cancellation");
        });
        try (SupplyChainEventRecorder.TaskScope ignored = eventRecorder.openTask(TASK_ID)) {
            assertThrows(SupplyChainTaskCancelledException.class, () -> analysisService.analyze("分析风险订单",
                    TASK_ID, List.of(), List.of(), List.of(), 0, new WorkflowPlan(List.of("查询订单风险"), false)));
        }
        finally {
            TaskCancellationRegistry.reset();
        }

        List<SupplyChainTaskEvent> events = events();
        assertEquals(List.of(SupplyChainEventType.MODEL_STARTED),
                events.stream().map(SupplyChainTaskEvent::type).toList());
    }

    @Test
    void nonRateLimitFailureIsNotRetried() {
        when(callResponseSpec.chatResponse()).thenThrow(new IllegalStateException("provider unavailable"));

        RiskAnalysisReport report;
        try (SupplyChainEventRecorder.TaskScope ignored = eventRecorder.openTask(TASK_ID)) {
            report = analysisService.analyze("分析风险订单", TASK_ID, List.of(), List.of(), List.of(), 0,
                    new WorkflowPlan(List.of("查询订单风险"), false));
        }

        assertFalse(report.modelAssisted());
        verify(requestSpec, times(1)).call();
        List<SupplyChainTaskEvent> events = events();
        assertEquals(List.of(SupplyChainEventType.MODEL_STARTED, SupplyChainEventType.MODEL_FAILED,
                SupplyChainEventType.FALLBACK_USED), events.stream().map(SupplyChainTaskEvent::type).toList());
        assertEquals("IllegalStateException", events.get(1).errorType());
    }


    @Test
    void structuredNarrativeIsAcceptedAndNeverChangesDeterministicRiskScore() {
        OrderRiskAssessment assessment = sampleAssessment();
        String narrative = """
                {"overview":"该订单存在交付延期风险。","reasons":[{"orderId":"ORD-TEST","summary":"预计交付晚于要求日期。","evidenceIds":["DELAY"]}],"businessImpacts":["需要确认新的交付承诺。"],"recommendations":["Confirm a recovery date with the supplier and notify the customer"],"uncertainties":[]}
                """;
        when(callResponseSpec.chatResponse()).thenReturn(ChatResponseUtil.createPureResponse(narrative));

        RiskAnalysisReport report;
        try (SupplyChainEventRecorder.TaskScope ignored = eventRecorder.openTask(TASK_ID)) {
            report = analysisService.analyze("分析风险订单", TASK_ID, List.of(assessment), List.of(), List.of(), 0,
                    new WorkflowPlan(List.of("查询订单风险"), false));
        }

        assertTrue(report.modelAssisted());
        assertNotNull(report.modelNarrative());
        assertEquals(RiskAnalysisReport.NARRATIVE_MODEL_VERIFIED, report.narrativeSource());
        assertEquals("该订单存在交付延期风险。", report.summary());
        assertEquals(30, report.atRiskOrders().get(0).riskScore());
        assertEquals(RiskLevel.MEDIUM, report.atRiskOrders().get(0).riskLevel());
        assertEquals(List.of("Confirm a recovery date with the supplier and notify the customer"),
                report.recommendations());
    }

    @Test
    void unknownEvidenceReferenceIsRejectedAndFallsBackWithoutChangingRisk() {
        OrderRiskAssessment assessment = sampleAssessment();
        String narrative = """
                {"overview":"该订单存在交付延期风险。","reasons":[{"orderId":"ORD-TEST","summary":"引用了不存在的证据。","evidenceIds":["NOT_A_REAL_EVIDENCE"]}],"businessImpacts":[],"recommendations":["Confirm a recovery date with the supplier and notify the customer"],"uncertainties":[]}
                """;
        when(callResponseSpec.chatResponse()).thenReturn(ChatResponseUtil.createPureResponse(narrative));

        RiskAnalysisReport report;
        try (SupplyChainEventRecorder.TaskScope ignored = eventRecorder.openTask(TASK_ID)) {
            report = analysisService.analyze("分析风险订单", TASK_ID, List.of(assessment), List.of(), List.of(), 0,
                    new WorkflowPlan(List.of("查询订单风险"), false));
        }

        assertFalse(report.modelAssisted());
        assertTrue(report.summary().startsWith("发现 1 个存在风险的订单"));
        assertEquals(30, report.atRiskOrders().get(0).riskScore());
        assertEquals(RiskAnalysisReport.NARRATIVE_DETERMINISTIC_FALLBACK, report.narrativeSource());
        List<SupplyChainTaskEvent> events = events();
        assertTrue(events.stream().anyMatch(event -> SupplyChainEventType.FALLBACK_USED.equals(event.type())));
        assertTrue(events.stream().anyMatch(event -> event.summary().contains("INVALID_MODEL_NARRATIVE")));
    }

    @Test
    void malformedModelJsonFallsBackToDeterministicNarrative() {
        OrderRiskAssessment assessment = sampleAssessment();
        when(callResponseSpec.chatResponse()).thenReturn(ChatResponseUtil.createPureResponse("The risk is high because the supplier is late."));

        RiskAnalysisReport report;
        try (SupplyChainEventRecorder.TaskScope ignored = eventRecorder.openTask(TASK_ID)) {
            report = analysisService.analyze("分析风险订单", TASK_ID, List.of(assessment), List.of(), List.of(), 0,
                    new WorkflowPlan(List.of("查询订单风险"), false));
        }

        assertFalse(report.modelAssisted());
        assertNull(report.modelNarrative());
        assertEquals(RiskAnalysisReport.NARRATIVE_DETERMINISTIC_FALLBACK, report.narrativeSource());
        assertTrue(report.summary().startsWith("发现 1 个存在风险的订单"));
        assertEquals(30, report.atRiskOrders().get(0).riskScore());
        assertEquals(RiskLevel.MEDIUM, report.atRiskOrders().get(0).riskLevel());
        assertTrue(events().stream().anyMatch(event -> event.summary() != null
                && event.summary().contains("model narrative rejected; reason=INVALID_JSON")));
    }

    @Test
    void modelTimeoutFallsBackWithoutChangingDeterministicRisk() {
        OrderRiskAssessment assessment = sampleAssessment();
        when(callResponseSpec.chatResponse()).thenThrow(new ResourceAccessException("read timed out",
                new java.net.SocketTimeoutException("read timed out")));

        RiskAnalysisReport report;
        try (SupplyChainEventRecorder.TaskScope ignored = eventRecorder.openTask(TASK_ID)) {
            report = analysisService.analyze("分析风险订单", TASK_ID, List.of(assessment), List.of(), List.of(), 0,
                    new WorkflowPlan(List.of("查询订单风险"), false));
        }

        assertFalse(report.modelAssisted());
        assertNull(report.modelNarrative());
        assertEquals(RiskAnalysisReport.NARRATIVE_DETERMINISTIC_FALLBACK, report.narrativeSource());
        assertTrue(report.summary().startsWith("发现 1 个存在风险的订单"));
        assertEquals(30, report.atRiskOrders().get(0).riskScore());
        assertEquals(RiskLevel.MEDIUM, report.atRiskOrders().get(0).riskLevel());
        List<SupplyChainTaskEvent> events = events();
        assertEquals(List.of(SupplyChainEventType.MODEL_STARTED, SupplyChainEventType.MODEL_FAILED,
                SupplyChainEventType.FALLBACK_USED), events.stream().map(SupplyChainTaskEvent::type).toList());
        assertEquals("ResourceAccessException", events.get(1).errorType());
    }

    @Test
    void legacyModelSummaryWithoutStructuredNarrativeIsMarkedUnverified() {
        RiskAnalysisReport legacy = new RiskAnalysisReport("task-legacy", "COMPLETED", "旧版模型摘要", List.of(), true,
                List.of(), List.of(), List.of(), List.of(), true, 0, Instant.now());

        assertEquals(RiskAnalysisReport.NARRATIVE_MODEL_LEGACY_UNVERIFIED, legacy.narrativeSource());
        assertNull(legacy.modelNarrative());
    }

    private static OrderRiskAssessment sampleAssessment() {
        SupplyChainOrder order = new SupplyChainOrder("ORD-TEST", "SKU-TEST", 10, "SUP-TEST",
                java.time.LocalDate.now(), java.time.LocalDate.now().plusDays(2), OrderStatus.IN_TRANSIT);
        RiskEvidence evidence = new RiskEvidence("DELAY", "交付延期", "lateDays=2; status=IN_TRANSIT",
                "estimatedDeliveryDate <= requestedDeliveryDate", "延期按规则计 30 分", 30, "order dates", true);
        return new OrderRiskAssessment(order, RiskLevel.MEDIUM, 30, 0, List.of("Estimated delivery is 2 day(s) after the requested date"),
                List.of("Confirm a recovery date with the supplier and notify the customer"), List.of(evidence), List.of());
    }

    private List<SupplyChainTaskEvent> events() {
        return eventRepository.findByTaskId(TASK_ID);
    }

}
