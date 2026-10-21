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
package com.alibaba.cloud.ai.dataagent.evaluation;

import com.alibaba.cloud.ai.dataagent.evaluation.dataset.SupplyChainEvaluationDatasetLoader;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationCaseDefinition;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationCaseHistoryEntry;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationAssertionResult;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationCaseResult;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationCaseStatus;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationMetrics;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationMetricsReport;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationMode;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationRun;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationRunStatus;
import com.alibaba.cloud.ai.dataagent.evaluation.feedback.FeedbackAssertion;
import com.alibaba.cloud.ai.dataagent.evaluation.feedback.FeedbackAssertionType;
import com.alibaba.cloud.ai.dataagent.evaluation.feedback.FeedbackDraft;
import com.alibaba.cloud.ai.dataagent.evaluation.feedback.FeedbackRecord;
import com.alibaba.cloud.ai.dataagent.evaluation.feedback.FeedbackRepository;
import com.alibaba.cloud.ai.dataagent.evaluation.feedback.FeedbackResultType;
import com.alibaba.cloud.ai.dataagent.evaluation.feedback.FeedbackSnapshot;
import com.alibaba.cloud.ai.dataagent.evaluation.feedback.FeedbackType;
import com.alibaba.cloud.ai.dataagent.evaluation.repository.EvaluationRunRepository;
import com.alibaba.cloud.ai.dataagent.evaluation.exception.AgentPrerequisiteException;
import com.alibaba.cloud.ai.dataagent.evaluation.service.AgentEvaluationService;
import com.alibaba.cloud.ai.dataagent.evaluation.service.EvaluationMetricsCalculator;
import com.alibaba.cloud.ai.dataagent.service.aimodelconfig.ModelReadinessService;
import com.alibaba.cloud.ai.dataagent.vo.ModelCheckVo;
import com.alibaba.cloud.ai.dataagent.supplychain.model.OrderRiskAssessment;
import com.alibaba.cloud.ai.dataagent.supplychain.model.OrderStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskAnalysisReport;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskEvidence;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskLevel;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainOrder;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.SupplyChainDemoData;
import com.alibaba.cloud.ai.dataagent.supplychain.service.SupplyChainKnowledgeService;
import com.alibaba.cloud.ai.dataagent.supplychain.service.SupplyChainQueryService;
import com.alibaba.cloud.ai.dataagent.supplychain.service.SupplyChainWorkflowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentEvaluationServiceTest {

    private EvaluationCatalog catalog;

    private EvaluationRunRepository repository;

    private SupplyChainWorkflowService workflow;

    private AgentEvaluationService service;

    private FeedbackRepository feedbackRepository;

    private ModelReadinessService readinessService;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        catalog = new EvaluationCatalog(new SupplyChainEvaluationDatasetLoader(mapper));
        SupplyChainQueryService queryService = new SupplyChainQueryService(new SupplyChainDemoData());
        ObjectProvider<VectorStore> noVectorStore = new StaticListableBeanFactory()
            .getBeanProvider(VectorStore.class);
        SupplyChainKnowledgeService knowledgeService = new SupplyChainKnowledgeService(noVectorStore,
                new PathMatchingResourcePatternResolver());
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:evaluation-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        repository = new EvaluationRunRepository(jdbc, mapper);
        feedbackRepository = new FeedbackRepository(jdbc, mapper);
        workflow = mock(SupplyChainWorkflowService.class);
        readinessService = mock(ModelReadinessService.class);
        when(readinessService.readiness()).thenReturn(verifiedReadiness());
        service = new AgentEvaluationService(catalog, queryService, knowledgeService, workflow, repository,
                new EvaluationMetricsCalculator(mapper), mapper, feedbackRepository, readinessService);
    }

    private static ModelCheckVo verifiedReadiness() {
        return ModelCheckVo.builder()
            .chatModelReady(true)
            .chatConnectionVerified(true)
            .chatState(ModelReadinessService.STATE_VERIFIED)
            .embeddingModelReady(false)
            .embeddingState(ModelReadinessService.STATE_NOT_CONFIGURED)
            .embeddingRequiredForAgentEvaluation(false)
            .agentEvaluationReady(true)
            .verificationScope(ModelReadinessService.VERIFICATION_SCOPE)
            .build();
    }


    @Test
    void enabledFeedbackCaseRunsThroughTheExistingOfflineEvaluationPipeline() {
        String taskId = "feedback-task-1";
        String orderId = "ORD-FEEDBACK-1";
        SupplyChainOrder order = new SupplyChainOrder(orderId, "SKU-MCU-001", 10, "SUP-ALPHA",
                LocalDate.now(), LocalDate.now().plusDays(2), OrderStatus.IN_TRANSIT);
        RiskEvidence evidence = new RiskEvidence("DELAY", "交付延期", "lateDays=2", "estimated <= requested",
                "延期计 30 分", 30, "order dates", true);
        OrderRiskAssessment assessment = new OrderRiskAssessment(order, RiskLevel.MEDIUM, 30, 0,
                List.of("Late delivery"), List.of("Confirm recovery date"), List.of(evidence), List.of());
        RiskAnalysisReport report = new RiskAnalysisReport(taskId, "COMPLETED", "规则摘要", List.of(), false,
                List.of(assessment), List.of("Confirm recovery date"), List.of(), List.of(), false, 0, Instant.now());
        when(workflow.task(taskId)).thenReturn(new SupplyChainTaskStatus(taskId, "COMPLETED", "DONE", 0, null,
                Instant.now()));
        when(workflow.report(taskId)).thenReturn(report);
        FeedbackSnapshot snapshot = new FeedbackSnapshot(taskId, "ANALYSIS", "测试问题", "规则摘要",
                report.narrativeSource(), 1, 0, 0, List.of(new FeedbackSnapshot.OrderSnapshot(orderId,
                        "SKU-MCU-001", 30, "MEDIUM", 0, List.of("DELAY"), List.of())), List.of(),
                List.of("DELAY"));
        FeedbackRecord feedback = new FeedbackRecord("feedback-1", taskId, FeedbackResultType.ANALYSIS, null,
                FeedbackType.INACCURATE_RISK, "需要把该判断纳入回归", report.narrativeSource(), "fingerprint",
                List.of("DELAY"), snapshot, Instant.now());
        feedbackRepository.saveFeedback(feedback);
        FeedbackDraft draft = new FeedbackDraft("draft-1", "feedback-1", null, "READY_FOR_REVIEW",
                "反馈回归案例", "任务完成且风险订单数保持一致",
                List.of(new FeedbackAssertion("a1", FeedbackAssertionType.TASK_STATUS, null, "COMPLETED", "任务完成"),
                        new FeedbackAssertion("a2", FeedbackAssertionType.AT_RISK_ORDER_COUNT, null, "1", "风险订单数"),
                        new FeedbackAssertion("a3", FeedbackAssertionType.RISK_SCORE, orderId, "30", "订单风险分")),
                List.of(), snapshot, FeedbackType.INACCURATE_RISK, Instant.now(), Instant.now(), null);
        feedbackRepository.saveDraft(draft);
        feedbackRepository.enableDraft("draft-1", "FB-TEST", "反馈回归案例", Instant.now());

        assertTrue(service.cases().stream().anyMatch(definition -> definition.id().equals("FB-TEST")));
        EvaluationRun run = service.start(EvaluationMode.OFFLINE, List.of("FB-TEST"), false);
        assertEquals(EvaluationRunStatus.COMPLETED, run.status());
        assertEquals(1, run.passed());
        assertEquals(taskId, run.results().get(0).taskId());
        assertEquals("feedback-1.0.0", run.results().get(0).caseVersion());
        assertEquals(3, run.results().get(0).assertions().size());
    }

    @Test
    void offlineRunExecutesDeterministicChecksAndPersistsResults() {
        List<String> caseIds = service.cases()
            .stream()
            .filter(definition -> definition.mode() == EvaluationMode.OFFLINE)
            .map(EvaluationCaseDefinition::id)
            .toList();

        EvaluationRun run = service.start(EvaluationMode.OFFLINE, caseIds, false);

        assertEquals(EvaluationRunStatus.COMPLETED, run.status());
        assertEquals(14, run.totalCases());
        assertEquals(14, run.passed());
        assertEquals(0, run.failed());
        assertEquals("1.0.0", run.datasetVersion());
        assertTrue(run.results().stream().allMatch(result -> result.status() == EvaluationCaseStatus.PASSED));
        assertTrue(run.results().stream().allMatch(result -> "1.0.0".equals(result.caseVersion())));
        EvaluationRun persisted = repository.find(run.runId()).orElseThrow();
        assertEquals("1.0.0", persisted.datasetVersion());
        assertEquals("1.0.0", persisted.results().get(0).caseVersion());
        assertEquals(run.passed(), persisted.passed());

        List<EvaluationCaseHistoryEntry> history = service.caseHistory(caseIds.get(0), 10);
        assertEquals(1, history.size());
        assertEquals(EvaluationCaseStatus.PASSED, history.get(0).caseStatus());
        assertEquals(run.runId(), history.get(0).runId());
        assertEquals("1.0.0", history.get(0).caseVersion());

        EvaluationMetricsReport report = service.metrics(10);
        EvaluationMetrics metrics = report.byMode()
            .stream()
            .filter(item -> item.mode() == EvaluationMode.OFFLINE)
            .findFirst()
            .orElseThrow();
        assertEquals(1, report.runsAnalyzed());
        assertEquals(14, metrics.caseSamples());
        assertEquals(1.0, metrics.passRate());
        assertEquals(3, metrics.retrieval().evaluatedQueries());
        assertEquals(1.0, metrics.retrieval().hitRateAt3());
        assertEquals(1.0, metrics.retrieval().mrr());
        assertNotNull(metrics.p95DurationMs());
        assertEquals(0, report.byMode().stream().filter(item -> item.mode() == EvaluationMode.AGENT)
            .findFirst()
            .orElseThrow()
            .caseSamples());
    }

    @Test
    void rejectsModeMismatchAndOversizedBatches() {
        List<String> agentIds = catalog.agentCases().stream().map(EvaluationCaseDefinition::id).toList();
        assertThrows(ResponseStatusException.class, () -> service.start(EvaluationMode.OFFLINE, agentIds, false));
        assertThrows(ResponseStatusException.class,
                () -> service.start(EvaluationMode.AGENT,
                        List.of("WF-ANALYSIS-HIGH-RISK", "WF-ANALYSIS-INVENTORY-SHORTAGE", "WF-DECISION-COMPARE",
                                "WF-DECISION-UNSUPPORTED", "WF-DECISION-EVIDENCE", "REPORT-FALLBACK-NO-MODEL"),
                        true));
        assertThrows(ResponseStatusException.class, () -> service.start(EvaluationMode.OFFLINE, List.of(), false));
        assertThrows(ResponseStatusException.class, () -> service.start(EvaluationMode.AGENT,
                List.of("WF-ANALYSIS-HIGH-RISK"), false));
    }

    @Test
    void agentRunIsBlockedBeforeSubmissionWhenChatIsNotVerified() {
        when(readinessService.readiness()).thenReturn(ModelCheckVo.builder()
            .chatModelReady(true)
            .chatConnectionVerified(false)
            .chatState(ModelReadinessService.STATE_CONFIGURED_UNVERIFIED)
            .embeddingState(ModelReadinessService.STATE_NOT_CONFIGURED)
            .embeddingRequiredForAgentEvaluation(false)
            .agentEvaluationReady(false)
            .agentEvaluationBlockReason("CHAT 模型尚未完成手动连接测试")
            .verificationScope(ModelReadinessService.VERIFICATION_SCOPE)
            .build());

        AgentPrerequisiteException exception = assertThrows(AgentPrerequisiteException.class,
                () -> service.start(EvaluationMode.AGENT, List.of("WF-ANALYSIS-HIGH-RISK"), true));

        assertEquals(AgentPrerequisiteException.CHAT_NOT_READY, exception.code());
        assertEquals(HttpStatus.PRECONDITION_FAILED, exception.status());
        assertTrue(exception.getMessage().contains("尚未完成手动连接测试"));
        verify(workflow, never()).submit(anyString(), anyString());
    }

    @Test
    void capabilitiesExposeVerifiedChatAndOptionalEmbedding() {
        Map<String, Object> capabilities = service.capabilities();

        assertEquals(true, capabilities.get("agentEvaluationReady"));
        assertEquals(true, capabilities.get("chatConnectionVerified"));
        assertEquals(false, capabilities.get("embeddingRequiredForAgentEvaluation"));
        assertEquals(ModelReadinessService.STATE_NOT_CONFIGURED, capabilities.get("embeddingState"));
        assertEquals(true, capabilities.get("agentModeBlocksUnreadyStart"));
        assertEquals(ModelReadinessService.VERIFICATION_SCOPE, capabilities.get("modelVerificationScope"));
        assertEquals(List.of(), capabilities.get("agentCasesRequiringEmbedding"));
        assertEquals(6, ((List<?>) capabilities.get("agentCasesRequiringChat")).size());
    }

    @Test
    void embeddingDependencyBlockDistinguishesMissingAndUntestedEmbedding() {
        assertEquals("所选 AGENT 案例声明依赖 EMBEDDING 向量检索，但未配置激活的 EMBEDDING 模型。",
                AgentEvaluationService.embeddingDependencyBlockReason(ModelCheckVo.builder()
                    .embeddingState(ModelReadinessService.STATE_NOT_CONFIGURED)
                    .build()));
        assertTrue(AgentEvaluationService.embeddingDependencyBlockReason(ModelCheckVo.builder()
            .embeddingState(ModelReadinessService.STATE_CONFIGURED_UNVERIFIED)
            .build()).contains("尚未通过手动连接测试"));
        assertTrue(AgentEvaluationService.embeddingDependencyBlockReason(ModelCheckVo.builder()
            .embeddingState(ModelReadinessService.STATE_TEST_FAILED)
            .embeddingTestError("401 invalid key")
            .build()).contains("401 invalid key"));
        assertNull(AgentEvaluationService.embeddingDependencyBlockReason(ModelCheckVo.builder()
            .embeddingState(ModelReadinessService.STATE_VERIFIED)
            .embeddingConnectionVerified(true)
            .build()));
    }

    @Test
    void chatDependencyReasonIsNullWhenVerifiedAndExplicitWhenNotVerified() {
        assertNull(AgentEvaluationService.chatDependencyBlockReason(ModelCheckVo.builder()
            .chatConnectionVerified(true)
            .chatState(ModelReadinessService.STATE_VERIFIED)
            .build()));
        assertTrue(AgentEvaluationService.chatDependencyBlockReason(ModelCheckVo.builder()
            .chatConnectionVerified(false)
            .chatState(ModelReadinessService.STATE_CONFIGURED_UNVERIFIED)
            .agentEvaluationBlockReason("CHAT 模型尚未完成手动连接测试")
            .build()).contains("尚未完成手动连接测试"));
    }

    @Test
    void agentRunIsExplicitCompletesAndBlocksConcurrentRuns() {
        when(workflow.submit(anyString(), anyString()))
            .thenReturn(new SupplyChainTaskStatus("task-1", "RUNNING", "PLANNING", 0, null, Instant.now()));
        when(workflow.task("task-1"))
            .thenReturn(new SupplyChainTaskStatus("task-1", "RUNNING", "PLANNING", 0, null, Instant.now()));

        EvaluationRun running = service.start(EvaluationMode.AGENT, List.of("WF-ANALYSIS-HIGH-RISK"), true);
        assertEquals(EvaluationRunStatus.RUNNING, running.status());
        assertEquals("workflow-1.0.0", running.datasetVersion());
        assertEquals(1, running.results().size());
        assertEquals("task-1", running.results().get(0).taskId());
        assertEquals("workflow-1.0.0", running.results().get(0).caseVersion());
        assertEquals("ANALYSIS", running.results().get(0).taskMode());
        EvaluationRun persistedRunning = repository.find(running.runId()).orElseThrow();
        assertEquals("workflow-1.0.0", persistedRunning.results().get(0).caseVersion());
        assertEquals("ANALYSIS", persistedRunning.results().get(0).taskMode());

        assertThrows(ResponseStatusException.class,
                () -> service.start(EvaluationMode.AGENT, List.of("WF-DECISION-COMPARE"), true));

        when(workflow.task("task-1"))
            .thenReturn(new SupplyChainTaskStatus("task-1", "COMPLETED", "REPORT", 1, null, Instant.now()));
        when(workflow.report("task-1")).thenReturn(report());

        EvaluationRun completed = service.getRun(running.runId());
        assertEquals(EvaluationRunStatus.COMPLETED, completed.status());
        assertEquals(1, completed.passed());
        assertEquals(0, completed.failed());
        assertNotNull(completed.results().get(0).taskId());

        // The guard is released once the run is terminal.
        when(workflow.submit(anyString(), anyString()))
            .thenReturn(new SupplyChainTaskStatus("task-2", "RUNNING", "PLANNING", 0, null, Instant.now()));
        when(workflow.task("task-2"))
            .thenReturn(new SupplyChainTaskStatus("task-2", "RUNNING", "PLANNING", 0, null, Instant.now()));
        assertNotNull(service.start(EvaluationMode.AGENT, List.of("WF-ANALYSIS-HIGH-RISK"), true));
    }

    @Test
    void workflowAssertionFailureKeepsExpectedAndActualEvidence() {
        when(workflow.submit(anyString(), anyString()))
            .thenReturn(new SupplyChainTaskStatus("task-fail", "RUNNING", "PLANNING", 0, null, Instant.now()));
        when(workflow.task("task-fail"))
            .thenReturn(new SupplyChainTaskStatus("task-fail", "RUNNING", "PLANNING", 0, null, Instant.now()));
        EvaluationRun running = service.start(EvaluationMode.AGENT, List.of("WF-ANALYSIS-HIGH-RISK"), true);

        when(workflow.task("task-fail"))
            .thenReturn(new SupplyChainTaskStatus("task-fail", "COMPLETED", "REPORT", 1, null, Instant.now()));
        when(workflow.report("task-fail")).thenReturn(emptyReport("task-fail"));

        EvaluationRun completed = service.getRun(running.runId());
        EvaluationCaseResult result = completed.results().get(0);
        assertEquals(EvaluationCaseStatus.FAILED, result.status());
        assertEquals("ASSERTION_MISMATCH", result.failureType());
        assertTrue(result.failureMessage().contains("报告包含风险订单"));
        EvaluationAssertionResult failedAssertion = result.assertions().stream()
            .filter(assertion -> "report-at-risk".equals(assertion.id()))
            .findFirst()
            .orElseThrow();
        assertFalse(failedAssertion.passed());
        assertEquals(">=1", failedAssertion.expected());
        assertEquals("0", failedAssertion.actual());
        assertEquals("task-fail", result.taskId());
        assertEquals("workflow-1.0.0", result.caseVersion());
    }

    @Test
    void submitFailureIsRecordedWithoutBlockingOtherCases() {
        when(workflow.submit(anyString(), anyString())).thenThrow(new IllegalStateException("queue rejected"));

        EvaluationRun run = service.start(EvaluationMode.AGENT, List.of("WF-DECISION-COMPARE"), true);

        assertEquals(EvaluationRunStatus.COMPLETED, run.status());
        assertEquals(1, run.failed());
        assertEquals("SUBMIT_FAILED", run.results().get(0).failureType());
    }

    @Test
    void rerunningASingleCaseCreatesANewRunAndPreservesTheOldOne() {
        String caseId = "ORDER-ORD-1001";
        EvaluationRun first = service.start(EvaluationMode.OFFLINE, List.of(caseId), false);
        EvaluationRun second = service.start(EvaluationMode.OFFLINE, List.of(caseId), false);

        assertNotEquals(first.runId(), second.runId());
        assertEquals(EvaluationCaseStatus.PASSED, first.results().get(0).status());
        assertTrue(repository.find(first.runId()).isPresent());
        assertTrue(repository.find(second.runId()).isPresent());
        assertEquals(2, service.caseHistory(caseId, 10).size());
    }

    @Test
    void timeoutClosesTheRunAsFailedAndMarksCasesUndetermined() {
        EvaluationCaseResult runningCase = new EvaluationCaseResult("WF-ANALYSIS-HIGH-RISK", "高风险订单分析",
                "真实工作流：高风险订单分析", "workflow", EvaluationCaseStatus.RUNNING, 0, "task-timeout",
                List.of(), "{}", null, null, "workflow-1.0.0", "ANALYSIS");
        EvaluationRun running = new EvaluationRun("run-timeout", "workflow-1.0.0", EvaluationMode.AGENT,
                EvaluationRunStatus.RUNNING, "TEST", Instant.now().minusSeconds(21 * 60), null, 1, 0, 0, 0, null,
                Map.of(), List.of(runningCase));
        repository.save(running);

        EvaluationRun refreshed = service.getRun("run-timeout");

        assertEquals(EvaluationRunStatus.FAILED, refreshed.status());
        assertTrue(refreshed.failureReason().contains("20"));
        assertEquals(EvaluationCaseStatus.UNDETERMINED, refreshed.results().get(0).status());
        assertEquals("TIMEOUT", refreshed.results().get(0).failureType());
        List<EvaluationCaseHistoryEntry> history = service.caseHistory("WF-ANALYSIS-HIGH-RISK", 10);
        assertEquals(EvaluationRunStatus.FAILED, history.get(0).runStatus());
        assertEquals(EvaluationCaseStatus.UNDETERMINED, history.get(0).caseStatus());
    }

    @Test
    void startupRecoveryClosesAnInterruptedRunningRun() {
        EvaluationCaseResult runningCase = new EvaluationCaseResult("WF-ANALYSIS-HIGH-RISK", "高风险订单分析",
                "真实工作流：高风险订单分析", "workflow", EvaluationCaseStatus.RUNNING, 0, "task-lost",
                List.of(), "{}", null, null, "workflow-1.0.0", "ANALYSIS");
        EvaluationRun running = new EvaluationRun("run-interrupted", "workflow-1.0.0", EvaluationMode.AGENT,
                EvaluationRunStatus.RUNNING, "TEST", Instant.now().minusSeconds(60), null, 1, 0, 0, 0, null,
                Map.of(), List.of(runningCase));
        repository.save(running);

        service.recoverInterruptedRuns();

        EvaluationRun recovered = repository.find("run-interrupted").orElseThrow();
        assertEquals(EvaluationRunStatus.FAILED, recovered.status());
        assertNotNull(recovered.failureReason());
        assertEquals(EvaluationCaseStatus.UNDETERMINED, recovered.results().get(0).status());
        assertEquals("INTERRUPTED", recovered.results().get(0).failureType());
    }

    @Test
    void agentCasesReceiveDistinctTaskIdsAndTaskModes() {
        when(workflow.submit(anyString(), anyString()))
            .thenReturn(new SupplyChainTaskStatus("task-a", "RUNNING", "PLANNING", 0, null, Instant.now()))
            .thenReturn(new SupplyChainTaskStatus("task-b", "RUNNING", "PLANNING", 0, null, Instant.now()));
        when(workflow.task(anyString()))
            .thenReturn(new SupplyChainTaskStatus("task-a", "RUNNING", "PLANNING", 0, null, Instant.now()));

        EvaluationRun run = service.start(EvaluationMode.AGENT,
                List.of("WF-ANALYSIS-HIGH-RISK", "WF-DECISION-COMPARE"), true);

        assertEquals(2, run.results().size());
        assertNotEquals(run.results().get(0).taskId(), run.results().get(1).taskId());
        assertEquals("ANALYSIS", run.results().get(0).taskMode());
        assertEquals("DECISION", run.results().get(1).taskMode());
    }

    private static RiskAnalysisReport report() {
        SupplyChainOrder order = new SupplyChainOrder("ORD-1001", "SKU-MCU-001", 120, "SUP-ALPHA",
                LocalDate.now().plusDays(2), LocalDate.now().plusDays(5), OrderStatus.IN_TRANSIT);
        OrderRiskAssessment assessment = new OrderRiskAssessment(order, RiskLevel.HIGH, 100, 100,
                List.of("延期中"), List.of("确认恢复日期"));
        return new RiskAnalysisReport("task-1", "COMPLETED", "存在 1 个风险订单", List.of(), true,
                List.of(assessment), List.of("确认供应商恢复日期"), List.of(), List.of(), false, 1, Instant.now());
    }

    private static RiskAnalysisReport emptyReport(String taskId) {
        return new RiskAnalysisReport(taskId, "COMPLETED", "没有风险订单", List.of(), true, List.of(), List.of(),
                List.of(), List.of(), false, 1, Instant.now());
    }

}
