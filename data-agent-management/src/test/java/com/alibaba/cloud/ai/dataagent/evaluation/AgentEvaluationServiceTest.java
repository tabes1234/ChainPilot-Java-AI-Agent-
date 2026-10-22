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
import com.alibaba.cloud.ai.dataagent.evaluation.service.EvaluationLeaseManager;
import com.alibaba.cloud.ai.dataagent.evaluation.service.EvaluationMetricsCalculator;
import com.alibaba.cloud.ai.dataagent.service.aimodelconfig.ModelReadinessService;
import com.alibaba.cloud.ai.dataagent.vo.ModelCheckVo;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DecisionTaskResult;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalActionType;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalPriority;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalRecommendation;
import com.alibaba.cloud.ai.dataagent.supplychain.model.OrderRiskAssessment;
import com.alibaba.cloud.ai.dataagent.supplychain.model.OrderStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskAnalysisReport;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskEvidence;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskLevel;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainEventStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainEventType;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskEvent;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainOrder;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.SupplyChainDemoData;
import com.alibaba.cloud.ai.dataagent.supplychain.service.InstanceIdentity;
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
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentEvaluationServiceTest {

    private static final long LEASE_TTL_MS = 600_000L;

    private static final long MAX_HOLD_MS = 1_800_000L;

    private static final String LOCK_TABLE = "chainpilot_evaluation_lock";

    private EvaluationCatalog catalog;

    private EvaluationRunRepository repository;

    private SupplyChainWorkflowService workflow;

    private AgentEvaluationService service;

    private FeedbackRepository feedbackRepository;

    private ModelReadinessService readinessService;

    private JdbcTemplate jdbc;

    private ObjectMapper mapper;

    private SupplyChainQueryService queryService;

    private SupplyChainKnowledgeService knowledgeService;

    private EvaluationMetricsCalculator metricsCalculator;

    private EvaluationLeaseManager lease;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        catalog = new EvaluationCatalog(new SupplyChainEvaluationDatasetLoader(mapper));
        queryService = new SupplyChainQueryService(new SupplyChainDemoData());
        ObjectProvider<VectorStore> noVectorStore = new StaticListableBeanFactory()
            .getBeanProvider(VectorStore.class);
        knowledgeService = new SupplyChainKnowledgeService(noVectorStore,
                new PathMatchingResourcePatternResolver());
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:evaluation-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        repository = new EvaluationRunRepository(jdbc, mapper);
        feedbackRepository = new FeedbackRepository(jdbc, mapper);
        workflow = mock(SupplyChainWorkflowService.class);
        readinessService = mock(ModelReadinessService.class);
        when(readinessService.readiness()).thenReturn(verifiedReadiness());
        metricsCalculator = new EvaluationMetricsCalculator(mapper);
        lease = newLease("jvm-a");
        service = newService(lease);
    }

    /** A second lease manager is a second JVM: same database, different instance identity. */
    private EvaluationLeaseManager newLease(String instanceId) {
        return new EvaluationLeaseManager(repository, new InstanceIdentity(instanceId), LEASE_TTL_MS, MAX_HOLD_MS);
    }

    private AgentEvaluationService newService(EvaluationLeaseManager manager) {
        return new AgentEvaluationService(catalog, queryService, knowledgeService, workflow, repository,
                metricsCalculator, mapper, feedbackRepository, readinessService, manager);
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
        assertEquals("DATABASE_LEASE", capabilities.get("agentRunLockScope"));
        assertEquals(false, capabilities.get("agentRunActive"));
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
        when(workflow.events("task-1")).thenReturn(modelCompletedEvents("task-1", "GENERATING_REPORT"));

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
        assertFalse(repository.isEvaluationLockHeld(), "提交异常不得留下永久锁");
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
    void expiredLeaseRecoveryClosesOnlyTheRunItsOwnerStoppedRenewing() {
        saveRunningAgentRun("run-expired", "task-lost");
        assertTrue(newLease("jvm-dead").claim("run-expired"));
        jdbc.update("UPDATE " + LOCK_TABLE + " SET lease_expires_at=1 WHERE lock_name='AGENT_EVALUATION'");

        newLease("jvm-rescuer").recoverOnStartup();

        EvaluationRun recovered = repository.find("run-expired").orElseThrow();
        assertEquals(EvaluationRunStatus.FAILED, recovered.status());
        assertNotNull(recovered.failureReason());
        assertEquals(EvaluationCaseStatus.UNDETERMINED, recovered.results().get(0).status());
        assertEquals("INTERRUPTED", recovered.results().get(0).failureType());
        assertFalse(repository.isEvaluationLockHeld());
    }

    @Test
    void startupDoesNotCloseARunAnotherInstanceStillOwns() {
        saveRunningAgentRun("run-live", "task-live");
        assertTrue(newLease("jvm-live").claim("run-live"));

        EvaluationLeaseManager newcomer = newLease("jvm-new");
        newcomer.recoverOnStartup();

        assertEquals(EvaluationRunStatus.RUNNING, repository.find("run-live").orElseThrow().status());
        assertFalse(newcomer.claim("run-live"));
        assertFalse(newcomer.claim("run-other"));
        assertEquals("jvm-live", lockOwner());
    }

    @Test
    void rejectedInstanceSubmitsNoTasksAndKeepsNoLease() {
        EvaluationRun running = startRunningAgentRun("task-1");
        assertTrue(lease.ownsRun(running.runId()));

        EvaluationLeaseManager leaseB = newLease("jvm-b");
        AgentEvaluationService other = newService(leaseB);
        ResponseStatusException conflict = assertThrows(ResponseStatusException.class,
                () -> other.start(EvaluationMode.AGENT, List.of("WF-DECISION-COMPARE"), true));

        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        assertNull(leaseB.activeRunId());
        verify(workflow, times(1)).submit(anyString(), anyString());
    }

    @Test
    void terminalRunReleasesTheLeaseSoTheNextRunCanStart() {
        EvaluationRun running = startRunningAgentRun("task-rel");
        assertTrue(repository.isEvaluationLockHeld());

        when(workflow.task("task-rel"))
            .thenReturn(new SupplyChainTaskStatus("task-rel", "COMPLETED", "REPORT", 1, null, Instant.now()));
        when(workflow.report("task-rel")).thenReturn(report());
        when(workflow.events("task-rel")).thenReturn(modelCompletedEvents("task-rel", "GENERATING_REPORT"));

        EvaluationRun completed = service.getRun(running.runId());

        assertEquals(EvaluationRunStatus.COMPLETED, completed.status());
        assertFalse(lease.ownsRun(running.runId()));
        assertFalse(repository.isEvaluationLockHeld());
    }

    @Test
    void cancelledTaskClosesTheRunAndReleasesTheLease() {
        EvaluationRun running = startRunningAgentRun("task-cancel");
        when(workflow.task("task-cancel"))
            .thenReturn(new SupplyChainTaskStatus("task-cancel", "CANCELLED", "CANCELLED", 0, "Cancelled by user",
                    Instant.now()));

        EvaluationRun closed = service.getRun(running.runId());

        assertEquals(EvaluationRunStatus.FAILED, closed.status());
        assertEquals(EvaluationCaseStatus.UNDETERMINED, closed.results().get(0).status());
        assertEquals("TASK_CANCELLED", closed.results().get(0).failureType());
        assertNotNull(closed.failureReason());
        assertFalse(repository.isEvaluationLockHeld());
    }

    @Test
    void renewalExtendsTheLeaseAndKeepsTheOwnerWriting() {
        EvaluationRun running = startRunningAgentRun("task-renew");
        jdbc.update("UPDATE " + LOCK_TABLE + " SET lease_expires_at=1 WHERE lock_name='AGENT_EVALUATION'");

        assertTrue(lease.renew());
        Long expiresAt = jdbc.queryForObject("SELECT lease_expires_at FROM " + LOCK_TABLE
                + " WHERE lock_name='AGENT_EVALUATION'", Long.class);
        assertTrue(expiresAt > repository.databaseNowMillis());
        assertEquals(EvaluationRunStatus.RUNNING, service.getRun(running.runId()).status());
        assertTrue(repository.isEvaluationLockHeld());
    }

    @Test
    void formerOwnerCannotAdvanceTheRunAfterAnotherInstanceTakesTheLease() {
        saveRunningAgentRun("run-taken", "task-taken");
        assertTrue(lease.claim("run-taken"));
        long lostFence = lease.activeFence();
        jdbc.update("UPDATE " + LOCK_TABLE + " SET owner_instance_id='jvm-b', fence_version=fence_version+1, "
                + "lease_expires_at=? WHERE lock_name='AGENT_EVALUATION'", repository.databaseNowMillis() + 600_000L);

        assertFalse(lease.renew());
        EvaluationRun refreshed = service.getRun("run-taken");

        assertEquals("run-taken", refreshed.runId());
        assertEquals(EvaluationRunStatus.RUNNING, repository.find("run-taken").orElseThrow().status());
        assertFalse(repository.saveOwned(repository.find("run-taken").orElseThrow(), lease.ownerId(), lostFence));
        assertEquals("jvm-b", lockOwner());
        verify(workflow, never()).task(anyString());
    }

    @Test
    void aTerminalSnapshotReleasesTheLeaseInTheSameWrite() {
        saveRunningAgentRun("run-terminal", "task-terminal");
        assertTrue(lease.claim("run-terminal"));
        EvaluationRun persisted = repository.find("run-terminal").orElseThrow();
        EvaluationRun terminal = new EvaluationRun(persisted.runId(), persisted.datasetVersion(), persisted.mode(),
                EvaluationRunStatus.COMPLETED, persisted.environment(), persisted.startedAt(), Instant.now(),
                persisted.totalCases(), persisted.passed(), persisted.failed(), persisted.undetermined(), 1L,
                Map.of(), persisted.results(), null);

        assertTrue(repository.saveOwned(terminal, lease.ownerId(), lease.activeFence()));

        assertFalse(repository.isEvaluationLockHeld());
        assertEquals(EvaluationRunStatus.COMPLETED, repository.find("run-terminal").orElseThrow().status());
    }

    @Test
    void expiryClosesTheRunAndFreesTheLockInOneStep() {
        saveRunningAgentRun("run-atomic", "task-atomic");
        assertTrue(lease.claim("run-atomic"));
        jdbc.update("UPDATE " + LOCK_TABLE + " SET lease_expires_at=1 WHERE lock_name='AGENT_EVALUATION'");

        assertEquals("run-atomic",
                repository.expireRunAndMarkInterrupted(repository.databaseNowMillis(), "test expiry").orElseThrow());

        EvaluationRun closed = repository.find("run-atomic").orElseThrow();
        assertEquals(EvaluationRunStatus.FAILED, closed.status());
        assertEquals("test expiry", closed.failureReason());
        assertEquals(EvaluationCaseStatus.UNDETERMINED, closed.results().get(0).status());
        assertFalse(repository.isEvaluationLockHeld());
        assertTrue(repository.expireRunAndMarkInterrupted(repository.databaseNowMillis(), "again").isEmpty());
    }

    private void saveRunningAgentRun(String runId, String taskId) {
        EvaluationCaseResult runningCase = new EvaluationCaseResult("WF-ANALYSIS-HIGH-RISK", "高风险订单分析",
                "真实工作流：高风险订单分析", "workflow", EvaluationCaseStatus.RUNNING, 0, taskId,
                List.of(), "{}", null, null, "workflow-1.0.0", "ANALYSIS");
        repository.save(new EvaluationRun(runId, "workflow-1.0.0", EvaluationMode.AGENT,
                EvaluationRunStatus.RUNNING, "TEST", Instant.now().minusSeconds(60), null, 1, 0, 0, 0, null,
                Map.of(), List.of(runningCase)));
    }

    private EvaluationRun startRunningAgentRun(String taskId) {
        when(workflow.submit(anyString(), anyString()))
            .thenReturn(new SupplyChainTaskStatus(taskId, "RUNNING", "PLANNING", 0, null, Instant.now()));
        when(workflow.task(taskId))
            .thenReturn(new SupplyChainTaskStatus(taskId, "RUNNING", "PLANNING", 0, null, Instant.now()));
        return service.start(EvaluationMode.AGENT, List.of("WF-ANALYSIS-HIGH-RISK"), true);
    }

    private String lockOwner() {
        return jdbc.queryForObject("SELECT owner_instance_id FROM " + LOCK_TABLE
                + " WHERE lock_name='AGENT_EVALUATION'", String.class);
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

    @Test
    void inventoryShortageCaseEvaluatesTheShortageAssertionAgainstRealEvidence() {
        when(workflow.submit(anyString(), anyString()))
            .thenReturn(new SupplyChainTaskStatus("task-short", "RUNNING", "PLANNING", 0, null, Instant.now()));
        when(workflow.task("task-short"))
            .thenReturn(new SupplyChainTaskStatus("task-short", "COMPLETED", "REPORT", 1, null, Instant.now()));
        when(workflow.report("task-short")).thenReturn(reportWithShortage("task-short", 80, 0));
        when(workflow.events("task-short")).thenReturn(modelCompletedEvents("task-short", "GENERATING_REPORT"));

        EvaluationRun run = service.getRun(service.start(EvaluationMode.AGENT,
                List.of("WF-ANALYSIS-INVENTORY-SHORTAGE"), true).runId());
        EvaluationCaseResult result = run.results().get(0);

        assertEquals(EvaluationCaseStatus.PASSED, result.status(),
                () -> "expected the declared shortage assertion to pass, got " + result.failureType()
                        + " / " + result.failureMessage());
        EvaluationAssertionResult shortage = assertion(result, "report-shortage");
        assertTrue(shortage.passed(), "the report really carries an inventory gap for ORD-1001");
        assertEquals("1", shortage.actual(), "only the order with a positive shortage may count");
    }

    @Test
    void inventoryShortageCaseFailsWhenTheReportHasNoRealGap() {
        when(workflow.submit(anyString(), anyString()))
            .thenReturn(new SupplyChainTaskStatus("task-nogap", "RUNNING", "PLANNING", 0, null, Instant.now()));
        when(workflow.task("task-nogap"))
            .thenReturn(new SupplyChainTaskStatus("task-nogap", "COMPLETED", "REPORT", 1, null, Instant.now()));
        when(workflow.report("task-nogap")).thenReturn(reportWithShortage("task-nogap", 0, 0));
        when(workflow.events("task-nogap")).thenReturn(modelCompletedEvents("task-nogap", "GENERATING_REPORT"));

        EvaluationRun run = service.getRun(service.start(EvaluationMode.AGENT,
                List.of("WF-ANALYSIS-INVENTORY-SHORTAGE"), true).runId());
        EvaluationCaseResult result = run.results().get(0);

        assertEquals(EvaluationCaseStatus.FAILED, result.status(),
                "a shortage case without a real gap must not be counted as passed");
        assertEquals("ASSERTION_MISMATCH", result.failureType());
        assertTrue(result.failureMessage().contains("库存缺口"), () -> "got " + result.failureMessage());
        assertFalse(assertion(result, "report-shortage").passed());
        assertEquals(List.of("report-shortage"), result.assertions().stream()
            .filter(assertion -> !assertion.passed())
            .map(EvaluationAssertionResult::id)
            .toList(), "model participation passed, so the only failure may be the missing gap");
    }

    @Test
    void highRiskCaseDoesNotEvaluateTheInventoryShortageAssertion() {
        when(workflow.submit(anyString(), anyString()))
            .thenReturn(new SupplyChainTaskStatus("task-risk", "RUNNING", "PLANNING", 0, null, Instant.now()));
        when(workflow.task("task-risk"))
            .thenReturn(new SupplyChainTaskStatus("task-risk", "COMPLETED", "REPORT", 1, null, Instant.now()));
        when(workflow.report("task-risk")).thenReturn(reportWithShortage("task-risk", 80, 0));
        when(workflow.events("task-risk")).thenReturn(modelCompletedEvents("task-risk", "GENERATING_REPORT"));

        EvaluationRun run = service.getRun(service.start(EvaluationMode.AGENT,
                List.of("WF-ANALYSIS-HIGH-RISK"), true).runId());
        EvaluationCaseResult result = run.results().get(0);

        // Assertions are bound to the case that declares them, not to the report content: a shortage
        // present in the data must not add an assertion the high-risk case never promised.
        assertEquals(EvaluationCaseStatus.PASSED, result.status());
        assertTrue(findAssertion(result, "report-shortage").isEmpty(),
                () -> "unexpected assertion: " + result.assertions().stream()
                    .map(EvaluationAssertionResult::id).toList());
    }

    @Test
    void missingReportEvidenceIsNotCountedAsPassed() {
        when(workflow.submit(anyString(), anyString()))
            .thenReturn(new SupplyChainTaskStatus("task-noreport", "RUNNING", "PLANNING", 0, null, Instant.now()));
        when(workflow.task("task-noreport"))
            .thenReturn(new SupplyChainTaskStatus("task-noreport", "COMPLETED", "REPORT", 1, null, Instant.now()));
        when(workflow.report("task-noreport")).thenReturn(null);

        EvaluationRun run = service.getRun(service.start(EvaluationMode.AGENT,
                List.of("WF-ANALYSIS-INVENTORY-SHORTAGE"), true).runId());
        EvaluationCaseResult result = run.results().get(0);

        assertNotEquals(EvaluationCaseStatus.PASSED, result.status(),
                "absent evidence may never be recorded as a pass");
        assertEquals("REPORT_MISSING", result.failureType());
        assertEquals(1, run.failed());
    }

    @Test
    void declaredAssertionThatNeverRunsFailsTheCaseInsteadOfPassingSilently() {
        EvaluationCatalog drifting = spy(catalog);
        WorkflowEvaluationCase declared = catalog.workflowCase("WF-ANALYSIS-HIGH-RISK").orElseThrow();
        doReturn(Optional.of(new WorkflowEvaluationCase(declared.id(), declared.name(), declared.scenario(),
                declared.question(), declared.taskMode(), declared.expectedBehavior(), declared.assertions(),
                List.of("report-exists", "task-completed", "report-at-risk", "never-produced")))).when(drifting)
            .workflowCase("WF-ANALYSIS-HIGH-RISK");
        AgentEvaluationService driftService = new AgentEvaluationService(drifting, queryService, knowledgeService,
                workflow, repository, metricsCalculator, mapper, feedbackRepository, readinessService,
                newLease("jvm-drift"));
        when(workflow.submit(anyString(), anyString()))
            .thenReturn(new SupplyChainTaskStatus("task-drift", "RUNNING", "PLANNING", 0, null, Instant.now()));
        when(workflow.task("task-drift"))
            .thenReturn(new SupplyChainTaskStatus("task-drift", "COMPLETED", "REPORT", 1, null, Instant.now()));
        when(workflow.report("task-drift")).thenReturn(reportWithShortage("task-drift", 80, 0));
        when(workflow.events("task-drift")).thenReturn(modelCompletedEvents("task-drift", "GENERATING_REPORT"));

        EvaluationRun run = driftService.getRun(driftService.start(EvaluationMode.AGENT,
                List.of("WF-ANALYSIS-HIGH-RISK"), true).runId());
        EvaluationCaseResult result = run.results().get(0);

        // This is the regression the round-39 audit found: a declared assertion that no code path
        // produces used to leave the case PASSED.
        assertEquals(EvaluationCaseStatus.FAILED, result.status());
        assertEquals("ASSERTION_NOT_EVALUATED", result.failureType());
        assertTrue(result.failureMessage().contains("never-produced"), () -> "got " + result.failureMessage());
    }

    /** Scenario 1: a genuinely model-assisted analysis run satisfies the participation assertions. */
    @Test
    void realModelAnalysisCasePassesOnlyWithSuccessfulModelParticipation() {
        String taskId = startRunningTask("task-pass");
        when(workflow.task(taskId))
            .thenReturn(new SupplyChainTaskStatus(taskId, "COMPLETED", "REPORT", 1, null, Instant.now()));
        when(workflow.report(taskId)).thenReturn(reportWithShortage(taskId, 80, 0));
        when(workflow.events(taskId)).thenReturn(modelCompletedEvents(taskId, "GENERATING_REPORT"));

        EvaluationRun run = service.getRun(service.start(EvaluationMode.AGENT,
                List.of("WF-ANALYSIS-HIGH-RISK"), true).runId());
        EvaluationCaseResult result = run.results().get(0);

        assertEquals(EvaluationCaseStatus.PASSED, result.status(),
                () -> "expected a pass, got " + result.failureType() + " / " + result.failureMessage());
        assertTrue(assertion(result, "report-model-assisted").passed());
        assertTrue(assertion(result, "report-model-completed").passed());
        assertEquals(1, run.passed());
    }

    /** Scenario 2: task COMPLETED through deterministic degradation must not pass the model case. */
    @Test
    void degradedModelRunCannotPassTheRealModelAnalysisCase() {
        String taskId = startRunningTask("task-degraded");
        when(workflow.task(taskId))
            .thenReturn(new SupplyChainTaskStatus(taskId, "COMPLETED", "REPORT", 1, null, Instant.now()));
        when(workflow.report(taskId)).thenReturn(reportWithShortage(taskId, 80, 0, false));
        when(workflow.events(taskId)).thenReturn(degradedModelEvents(taskId, "GENERATING_REPORT"));

        EvaluationRun run = service.getRun(service.start(EvaluationMode.AGENT,
                List.of("WF-ANALYSIS-HIGH-RISK"), true).runId());
        EvaluationCaseResult result = run.results().get(0);

        assertEquals("COMPLETED", workflow.task(taskId).status(),
                "the business layer still completes with the deterministic report");
        assertEquals(EvaluationCaseStatus.FAILED, result.status(),
                "a degraded run may never be recorded as real-model acceptance");
        assertEquals("ASSERTION_MISMATCH", result.failureType());
        assertTrue(result.failureMessage().contains("报告由模型成功生成"), () -> "got " + result.failureMessage());
        assertEquals(List.of("report-model-assisted", "report-model-completed"), result.assertions().stream()
            .filter(assertion -> !assertion.passed())
            .map(EvaluationAssertionResult::id)
            .toList(), "the business assertions must still pass, only model participation may fail");
    }

    /** Scenario 2 for the decision route: grounded narrative missing means the case cannot pass. */
    @Test
    void degradedDecisionRunCannotPassTheRealModelDecisionCase() {
        String taskId = startRunningTask("task-decision-degraded");
        when(workflow.task(taskId))
            .thenReturn(new SupplyChainTaskStatus(taskId, "COMPLETED", "DONE", 1, null, Instant.now()));
        when(workflow.decision(taskId)).thenReturn(decision(taskId, false, true));
        when(workflow.events(taskId)).thenReturn(degradedModelEvents(taskId, "DECISION_SUMMARY"));

        EvaluationRun run = service.getRun(service.start(EvaluationMode.AGENT,
                List.of("WF-DECISION-EVIDENCE"), true).runId());
        EvaluationCaseResult result = run.results().get(0);

        assertEquals(EvaluationCaseStatus.FAILED, result.status());
        assertFalse(assertion(result, "decision-model-assisted").passed());
        assertFalse(assertion(result, "decision-model-completed").passed());
        assertTrue(assertion(result, "recommendations").passed(), "the deterministic advice is still usable");
        assertTrue(assertion(result, "evidence").passed());
        assertTrue(assertion(result, "real-data-mutated").passed());
    }

    /** Scenario 3: only MODEL_STARTED, or a late ignored response, is not model participation. */
    @Test
    void lateOrIncompleteModelEvidenceDoesNotCountAsParticipation() {
        String taskId = startRunningTask("task-late");
        when(workflow.task(taskId))
            .thenReturn(new SupplyChainTaskStatus(taskId, "COMPLETED", "REPORT", 1, null, Instant.now()));
        when(workflow.report(taskId)).thenReturn(reportWithShortage(taskId, 80, 0));
        when(workflow.events(taskId)).thenReturn(lateCancelledEvents(taskId, "GENERATING_REPORT"));

        EvaluationRun run = service.getRun(service.start(EvaluationMode.AGENT,
                List.of("WF-ANALYSIS-HIGH-RISK"), true).runId());
        EvaluationCaseResult result = run.results().get(0);

        assertEquals(EvaluationCaseStatus.FAILED, result.status(),
                "a model completion that arrived after the task was terminal was ignored, not applied");
        assertEquals(List.of("report-model-completed"), result.assertions().stream()
            .filter(assertion -> !assertion.passed())
            .map(EvaluationAssertionResult::id)
            .toList());
    }

    /** Scenario 4: model participation alone cannot rescue a broken business contract. */
    @Test
    void modelParticipationCannotCompensateForABrokenBusinessContract() {
        String taskId = startRunningTask("task-contract");
        when(workflow.task(taskId))
            .thenReturn(new SupplyChainTaskStatus(taskId, "COMPLETED", "REPORT", 1, null, Instant.now()));
        when(workflow.report(taskId)).thenReturn(modelAssistedEmptyReport(taskId));
        when(workflow.events(taskId)).thenReturn(modelCompletedEvents(taskId, "GENERATING_REPORT"));

        EvaluationRun run = service.getRun(service.start(EvaluationMode.AGENT,
                List.of("WF-ANALYSIS-HIGH-RISK"), true).runId());
        EvaluationCaseResult result = run.results().get(0);

        assertEquals(EvaluationCaseStatus.FAILED, result.status());
        assertTrue(assertion(result, "report-model-assisted").passed());
        assertTrue(assertion(result, "report-model-completed").passed());
        assertEquals(List.of("report-at-risk"), result.assertions().stream()
            .filter(assertion -> !assertion.passed())
            .map(EvaluationAssertionResult::id)
            .toList(), "the business contract is what must fail here");
    }

    /** Scenario 5: OFFLINE cases keep their deterministic semantics and ask for no model. */
    @Test
    void offlineCasesStillRunDeterministicallyAndRequireNoModelParticipation() {
        List<String> caseIds = service.cases()
            .stream()
            .filter(definition -> definition.mode() == EvaluationMode.OFFLINE)
            .map(EvaluationCaseDefinition::id)
            .toList();

        EvaluationRun run = service.start(EvaluationMode.OFFLINE, caseIds, false);

        assertEquals(14, run.totalCases());
        assertEquals(14, run.passed());
        assertTrue(run.results()
            .stream()
            .flatMap(result -> result.assertions().stream())
            .map(EvaluationAssertionResult::id)
            .noneMatch(id -> id.equals("report-model-assisted") || id.equals("report-model-completed")
                    || id.equals("decision-model-assisted") || id.equals("decision-model-completed")),
                "deterministic evaluation must not start demanding model participation");
        verify(workflow, never()).submit(anyString(), anyString());
    }

    /** Scenario 6: a declared participation assertion that no code path produces must be reported. */
    @Test
    void declaredParticipationAssertionThatNeverRunsIsReportedAsMissing() {
        EvaluationCatalog drifting = spy(catalog);
        WorkflowEvaluationCase declared = catalog.workflowCase("WF-ANALYSIS-HIGH-RISK").orElseThrow();
        doReturn(Optional.of(new WorkflowEvaluationCase(declared.id(), declared.name(), declared.scenario(),
                declared.question(), declared.taskMode(), declared.expectedBehavior(), declared.assertions(),
                java.util.stream.Stream.concat(declared.requiredAssertionIds().stream(),
                        java.util.stream.Stream.of("decision-model-completed")).toList()))).when(drifting)
            .workflowCase("WF-ANALYSIS-HIGH-RISK");
        AgentEvaluationService driftService = new AgentEvaluationService(drifting, queryService, knowledgeService,
                workflow, repository, metricsCalculator, mapper, feedbackRepository, readinessService,
                newLease("jvm-drift-2"));
        String taskId = startRunningTask("task-drift-2");
        when(workflow.task(taskId))
            .thenReturn(new SupplyChainTaskStatus(taskId, "COMPLETED", "REPORT", 1, null, Instant.now()));
        when(workflow.report(taskId)).thenReturn(reportWithShortage(taskId, 80, 0));
        when(workflow.events(taskId)).thenReturn(modelCompletedEvents(taskId, "GENERATING_REPORT"));

        EvaluationRun run = driftService.getRun(driftService.start(EvaluationMode.AGENT,
                List.of("WF-ANALYSIS-HIGH-RISK"), true).runId());
        EvaluationCaseResult result = run.results().get(0);

        assertEquals(EvaluationCaseStatus.FAILED, result.status());
        assertEquals("ASSERTION_NOT_EVALUATED", result.failureType());
        assertTrue(result.failureMessage().contains("decision-model-completed"),
                () -> "got " + result.failureMessage());
    }

    private String startRunningTask(String taskId) {
        when(workflow.submit(anyString(), anyString()))
            .thenReturn(new SupplyChainTaskStatus(taskId, "RUNNING", "PLANNING", 0, null, Instant.now()));
        return taskId;
    }

    private static RiskAnalysisReport modelAssistedEmptyReport(String taskId) {
        return new RiskAnalysisReport(taskId, "COMPLETED", "没有风险订单", List.of("查询订单风险"), true, List.of(),
                List.of("确认恢复日期"), List.of(), List.of(), true, 1, Instant.now(), null,
                RiskAnalysisReport.NARRATIVE_MODEL_VERIFIED);
    }

    private static EvaluationAssertionResult assertion(EvaluationCaseResult result, String id) {
        return findAssertion(result, id).orElseThrow(
                () -> new AssertionError("断言 " + id + " 未执行，实际执行了 " + result.assertions().stream()
                    .map(EvaluationAssertionResult::id).toList()));
    }

    private static Optional<EvaluationAssertionResult> findAssertion(EvaluationCaseResult result, String id) {
        return result.assertions().stream().filter(assertion -> id.equals(assertion.id())).findFirst();
    }

    private static RiskAnalysisReport reportWithShortage(String taskId, int firstShortage, int secondShortage) {
        return reportWithShortage(taskId, firstShortage, secondShortage, true);
    }

    private static RiskAnalysisReport reportWithShortage(String taskId, int firstShortage, int secondShortage,
            boolean modelAssisted) {
        SupplyChainOrder first = new SupplyChainOrder("ORD-1001", "SKU-MCU-001", 120, "SUP-ALPHA",
                LocalDate.now().plusDays(2), LocalDate.now().plusDays(5), OrderStatus.IN_TRANSIT);
        SupplyChainOrder second = new SupplyChainOrder("ORD-1002", "SKU-SENSOR-002", 60, "SUP-BETA",
                LocalDate.now().plusDays(1), LocalDate.now().plusDays(4), OrderStatus.CONFIRMED);
        return new RiskAnalysisReport(taskId, "COMPLETED", "存在 2 个风险订单", List.of(), true,
                List.of(new OrderRiskAssessment(first, RiskLevel.HIGH, 100, firstShortage, List.of("延期中"),
                        List.of("确认恢复日期")),
                        new OrderRiskAssessment(second, RiskLevel.MEDIUM, 60, secondShortage, List.of("交付风险"),
                                List.of("催交"))),
                List.of("确认供应商恢复日期"), List.of(), List.of(), modelAssisted, 1, Instant.now(), null,
                modelAssisted ? RiskAnalysisReport.NARRATIVE_MODEL_VERIFIED
                        : RiskAnalysisReport.NARRATIVE_DETERMINISTIC_FALLBACK);
    }

    /** The event trail of a model stage that really succeeded. */
    private static List<SupplyChainTaskEvent> modelCompletedEvents(String taskId, String stage) {
        return List.of(new SupplyChainTaskEvent("ev-start", taskId, 1, SupplyChainEventType.MODEL_STARTED, stage,
                SupplyChainEventStatus.STARTED, Instant.now(), null, "model call started", null),
                new SupplyChainTaskEvent("ev-done", taskId, 2, SupplyChainEventType.MODEL_COMPLETED, stage,
                        SupplyChainEventStatus.COMPLETED, Instant.now(), 1200L,
                        "model narrative accepted; reasons=3", null));
    }

    /** A budget abort or provider failure: the stage started, never completed, and degraded. */
    private static List<SupplyChainTaskEvent> degradedModelEvents(String taskId, String stage) {
        return List.of(new SupplyChainTaskEvent("ev-start", taskId, 1, SupplyChainEventType.MODEL_STARTED, stage,
                SupplyChainEventStatus.STARTED, Instant.now(), null, "model call started", null),
                new SupplyChainTaskEvent("ev-failed", taskId, 2, SupplyChainEventType.MODEL_FAILED, stage,
                        SupplyChainEventStatus.FAILED, Instant.now(), 50L, "model call failed",
                        "IllegalStateException"),
                new SupplyChainTaskEvent("ev-fallback", taskId, 3, SupplyChainEventType.FALLBACK_USED, stage,
                        SupplyChainEventStatus.FALLBACK, Instant.now(), 0L, "reason=IllegalStateException", null));
    }

    /** A response that landed after the task was already terminal: recorded, but marked CANCELLED. */
    private static List<SupplyChainTaskEvent> lateCancelledEvents(String taskId, String stage) {
        return List.of(new SupplyChainTaskEvent("ev-start", taskId, 1, SupplyChainEventType.MODEL_STARTED, stage,
                SupplyChainEventStatus.STARTED, Instant.now(), null, "model call started", null),
                new SupplyChainTaskEvent("ev-late", taskId, 2, SupplyChainEventType.MODEL_COMPLETED, stage,
                        SupplyChainEventStatus.CANCELLED, Instant.now(), 9000L,
                        "late response ignored; the task already reached a terminal state; rawOutcome=ok", null));
    }

    private static DecisionTaskResult decision(String taskId, boolean modelAssisted, boolean fallbackUsed) {
        Instant now = Instant.now();
        DisposalRecommendation recommendation = new DisposalRecommendation("rec-eval-1",
                DisposalActionType.REPLENISH_INVENTORY, DisposalPriority.P1, 90, "高风险订单", RiskLevel.HIGH,
                "SKU-MCU-001", "SUP-ALPHA", List.of("ORD-1001"), "紧急补货", "补货 100 件", "高风险订单需要补货",
                List.of("缺货 40 件"), "降低风险", List.of("供应商可到货"), List.of("成本待确认"), List.of(),
                List.of("假设订单未取消"), "PENDING", now);
        return new DecisionTaskResult(taskId, "针对当前高风险订单生成处置建议", "DISPOSAL_RECOMMENDATIONS", "COMPLETED",
                "建议补货 100 件。", modelAssisted ? "MODEL_GROUNDED" : "DETERMINISTIC_FALLBACK", modelAssisted,
                fallbackUsed, false, List.of(), List.of("findAtRiskOrders"), List.of("查询风险订单"), true, List.of(),
                List.of(), List.of(recommendation), null, null, null, null, null, List.of(), List.of(),
                List.of("确定性证据：缺货 40 件"), false, now);
    }

    private static RiskAnalysisReport report() {
        SupplyChainOrder order = new SupplyChainOrder("ORD-1001", "SKU-MCU-001", 120, "SUP-ALPHA",
                LocalDate.now().plusDays(2), LocalDate.now().plusDays(5), OrderStatus.IN_TRANSIT);
        OrderRiskAssessment assessment = new OrderRiskAssessment(order, RiskLevel.HIGH, 100, 100,
                List.of("延期中"), List.of("确认恢复日期"));
        return new RiskAnalysisReport("task-1", "COMPLETED", "存在 1 个风险订单", List.of(), true,
                List.of(assessment), List.of("确认供应商恢复日期"), List.of(), List.of(), true, 1, Instant.now(), null,
                RiskAnalysisReport.NARRATIVE_MODEL_VERIFIED);
    }

    private static RiskAnalysisReport emptyReport(String taskId) {
        return new RiskAnalysisReport(taskId, "COMPLETED", "没有风险订单", List.of(), true, List.of(), List.of(),
                List.of(), List.of(), false, 1, Instant.now());
    }

}
