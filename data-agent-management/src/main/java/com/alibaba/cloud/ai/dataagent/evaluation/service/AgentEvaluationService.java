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
package com.alibaba.cloud.ai.dataagent.evaluation.service;

import com.alibaba.cloud.ai.dataagent.evaluation.EvaluationCatalog;
import com.alibaba.cloud.ai.dataagent.evaluation.WorkflowEvaluationCase;
import com.alibaba.cloud.ai.dataagent.evaluation.dataset.SupplyChainEvaluationDataset;
import com.alibaba.cloud.ai.dataagent.evaluation.exception.AgentPrerequisiteException;
import com.alibaba.cloud.ai.dataagent.evaluation.feedback.FeedbackAssertion;
import com.alibaba.cloud.ai.dataagent.evaluation.feedback.FeedbackCaseRecord;
import com.alibaba.cloud.ai.dataagent.evaluation.feedback.FeedbackRepository;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationAssertionResult;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationCaseDefinition;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationCaseHistoryEntry;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationCaseResult;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationCaseStatus;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationMetrics;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationMetricsReport;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationMode;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationRun;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationRunStatus;
import com.alibaba.cloud.ai.dataagent.evaluation.repository.EvaluationRunRepository;
import com.alibaba.cloud.ai.dataagent.service.aimodelconfig.ModelReadinessService;
import com.alibaba.cloud.ai.dataagent.vo.ModelCheckVo;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DecisionTaskResult;
import com.alibaba.cloud.ai.dataagent.supplychain.model.InventoryPosition;
import com.alibaba.cloud.ai.dataagent.supplychain.model.KnowledgeCitation;
import com.alibaba.cloud.ai.dataagent.supplychain.model.OrderRiskAssessment;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskAnalysisReport;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplierRiskProfile;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainEventStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainEventType;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskEvent;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.service.SupplyChainKnowledgeService;
import com.alibaba.cloud.ai.dataagent.supplychain.service.SupplyChainQueryService;
import com.alibaba.cloud.ai.dataagent.supplychain.service.SupplyChainWorkflowService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Executes the evaluation catalogue.
 *
 * <p>OFFLINE runs the deterministic production services (risk scoring, inventory,
 * suppliers, lexical retrieval) with no model calls and no writes to business tables.
 * AGENT runs must be started explicitly and submit real supply-chain tasks; results are
 * persisted so the UI can poll and link each case back to its task. An AGENT run is guarded
 * by a database lease, so only the current fence holder may advance or finalize it even when
 * several JVMs poll the same run.
 */
@Service
public class AgentEvaluationService {

    public static final int MAX_OFFLINE_CASES = 20;

    public static final int MAX_AGENT_CASES = 5;

    private static final Duration AGENT_TIMEOUT = Duration.ofMinutes(20);

    private static final String OFFLINE_ENVIRONMENT = "SERVER_IN_PROCESS";

    private static final String AGENT_ENVIRONMENT = "CURRENT_BACKEND";

    private static final String RUN_LOCK_SCOPE = "DATABASE_LEASE";

    private static final String TIMEOUT_FAILURE = "AGENT 评测在 " + AGENT_TIMEOUT.toMinutes() + " 分钟内未完成";

    private static final String CANCELLED_FAILURE = "AGENT 评测存在被取消的任务，未获得结论";

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final EvaluationCatalog catalog;

    private final SupplyChainQueryService queryService;

    private final SupplyChainKnowledgeService knowledgeService;

    private final SupplyChainWorkflowService workflowService;

    private final EvaluationRunRepository repository;

    private final EvaluationMetricsCalculator metricsCalculator;

    private final ObjectMapper mapper;

    private final FeedbackRepository feedbackRepository;

    private final ModelReadinessService modelReadinessService;

    private final EvaluationLeaseManager evaluationLease;

    @Autowired
    public AgentEvaluationService(EvaluationCatalog catalog, SupplyChainQueryService queryService,
            SupplyChainKnowledgeService knowledgeService, SupplyChainWorkflowService workflowService,
            EvaluationRunRepository repository, EvaluationMetricsCalculator metricsCalculator,
            ObjectMapper mapper, FeedbackRepository feedbackRepository, ModelReadinessService modelReadinessService,
            EvaluationLeaseManager evaluationLease) {
        this.catalog = catalog;
        this.queryService = queryService;
        this.knowledgeService = knowledgeService;
        this.workflowService = workflowService;
        this.repository = repository;
        this.metricsCalculator = metricsCalculator;
        this.mapper = mapper;
        this.feedbackRepository = feedbackRepository;
        this.modelReadinessService = modelReadinessService;
        this.evaluationLease = evaluationLease;
    }

    public List<EvaluationCaseDefinition> cases() {
        List<EvaluationCaseDefinition> all = new ArrayList<>(catalog.allCases());
        if (feedbackRepository != null) {
            all.addAll(feedbackRepository.enabledDefinitions());
        }
        return List.copyOf(all);
    }

    public EvaluationCaseDefinition caseOrThrow(String caseId) {
        return catalog.find(caseId)
            .or(() -> feedbackRepository == null ? Optional.empty()
                    : feedbackRepository.findEnabledDefinition(caseId))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "评测案例不存在: " + caseId));
    }

    public Map<String, Object> capabilities() {
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("datasetVersion", catalog.datasetVersion());
        capabilities.put("offlineCaseSetVersion", catalog.datasetVersion());
        capabilities.put("agentCaseSetVersion", EvaluationCatalog.WORKFLOW_VERSION);
        int feedbackCases = feedbackRepository == null ? 0 : feedbackRepository.enabledDefinitions().size();
        capabilities.put("feedbackCases", feedbackCases);
        capabilities.put("totalCases", catalog.allCases().size() + feedbackCases);
        capabilities.put("offlineCases", catalog.deterministicCases().size() + feedbackCases);
        capabilities.put("agentCases", catalog.agentCases().size());
        capabilities.put("maxOfflineCasesPerRun", MAX_OFFLINE_CASES);
        capabilities.put("maxAgentCasesPerRun", MAX_AGENT_CASES);
        capabilities.put("agentModeRequiresExplicitStart", true);
        capabilities.put("agentModeRequiresConfirmation", true);
        capabilities.put("agentModeRequiresConnectionTest", true);
        capabilities.put("agentRunLockScope", RUN_LOCK_SCOPE);
        capabilities.put("agentRunActive", repository.isEvaluationLockHeld());
        capabilities.put("agentRunOwnerIsCurrentInstance", evaluationLease.ownsRun(evaluationLease.activeRunId()));
        capabilities.put("agentTimeoutMinutes", AGENT_TIMEOUT.toMinutes());
        capabilities.put("environment", AGENT_ENVIRONMENT);
        capabilities.put("warning", "OFFLINE 模式仅执行确定性检查，不调用模型；AGENT 模式会提交真实任务，可能产生模型调用与向量化费用。两种模式的指标分开统计。");
        boolean catalogRequiresEmbedding = catalog.agentCases()
            .stream()
            .anyMatch(EvaluationCaseDefinition::embeddingRequired);
        capabilities.put("agentCasesRequiringEmbedding", catalog.agentCases()
            .stream()
            .filter(EvaluationCaseDefinition::embeddingRequired)
            .map(EvaluationCaseDefinition::id)
            .toList());
        capabilities.put("agentCasesRequiringChat", catalog.agentCases()
            .stream()
            .filter(EvaluationCaseDefinition::chatRequired)
            .map(EvaluationCaseDefinition::id)
            .toList());
        ModelCheckVo readiness;
        try {
            readiness = modelReadinessService.readiness();
        }
        catch (RuntimeException exception) {
            readiness = ModelCheckVo.builder()
                .chatState(ModelReadinessService.STATE_CONFIGURED_UNVERIFIED)
                .embeddingState(ModelReadinessService.STATE_CONFIGURED_UNVERIFIED)
                .embeddingRequiredForAgentEvaluation(false)
                .agentEvaluationReady(false)
                .agentEvaluationBlockReason("模型就绪检查失败，无法确认 AGENT 前置条件。")
                .verificationScope(ModelReadinessService.VERIFICATION_SCOPE)
                .build();
        }
        capabilities.put("chatModelReady", readiness.isChatModelReady());
        capabilities.put("embeddingModelReady", readiness.isEmbeddingModelReady());
        capabilities.put("chatConnectionVerified", readiness.isChatConnectionVerified());
        capabilities.put("embeddingConnectionVerified", readiness.isEmbeddingConnectionVerified());
        capabilities.put("chatState", readiness.getChatState());
        capabilities.put("embeddingState", readiness.getEmbeddingState());
        capabilities.put("chatTestedAt", readiness.getChatTestedAt());
        capabilities.put("chatTestError", readiness.getChatTestError());
        capabilities.put("embeddingTestedAt", readiness.getEmbeddingTestedAt());
        capabilities.put("embeddingTestError", readiness.getEmbeddingTestError());
        capabilities.put("embeddingRequiredForAgentEvaluation", catalogRequiresEmbedding);
        capabilities.put("embeddingDependencyNote", catalogRequiresEmbedding
                ? "部分 AGENT 案例声明依赖 EMBEDDING 向量检索；选择这些案例时必须先配置并通过 EMBEDDING 连接测试。"
                : readiness.getEmbeddingDependencyNote());
        capabilities.put("agentEvaluationReady", readiness.isAgentEvaluationReady());
        capabilities.put("agentEvaluationBlockReason", readiness.getAgentEvaluationBlockReason());
        capabilities.put("modelVerificationScope", readiness.getVerificationScope());
        capabilities.put("agentModeBlocksUnreadyStart", true);
        capabilities.put("agentModeUsesFallbackWhenModelMissing", true);
        return capabilities;
    }

    public List<EvaluationRun> runs(int limit) {
        return repository.list(limit);
    }

    public EvaluationMetricsReport metrics(int limit) {
        return metricsCalculator.report(repository.findCompletedWithResults(limit));
    }

    /** Recent completed appearances of a single case, derived from persisted runs only. */
    public List<EvaluationCaseHistoryEntry> caseHistory(String caseId, int limit) {
        caseOrThrow(caseId);
        List<EvaluationCaseHistoryEntry> entries = new ArrayList<>();
        for (EvaluationRun run : repository.findTerminalWithResults(limit)) {
            run.results()
                .stream()
                .filter(result -> result.caseId().equals(caseId))
                .findFirst()
                .ifPresent(result -> entries.add(new EvaluationCaseHistoryEntry(run.runId(), run.mode(), run.status(),
                        run.startedAt(), result.status(), result.durationMs(), result.taskId(), result.failureType(),
                        result.failureMessage(), result.caseVersion(), run.datasetVersion())));
        }
        return entries;
    }

    public EvaluationRun getRun(String runId) {
        EvaluationRun run = repository.find(runId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "评测运行不存在: " + runId));
        if (run.mode() == EvaluationMode.AGENT && run.status() == EvaluationRunStatus.RUNNING) {
            return refreshAgentRun(run);
        }
        return run;
    }

    public EvaluationRun start(EvaluationMode mode, List<String> requestedCaseIds, boolean agentConfirmed) {
        List<String> caseIds = requestedCaseIds == null ? List.of()
                : requestedCaseIds.stream().filter(Objects::nonNull).map(String::trim).filter(id -> !id.isEmpty())
                    .distinct().toList();
        if (caseIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "至少选择一个评测案例");
        }
        if (mode == EvaluationMode.AGENT && !agentConfirmed) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "AGENT 运行需要显式确认费用与环境影响");
        }
        List<EvaluationCaseDefinition> definitions = caseIds.stream().map(this::caseOrThrow).toList();
        Set<String> versions = definitions.stream().map(EvaluationCaseDefinition::version)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (versions.size() > 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "一次运行只能包含同一案例版本的案例");
        }
        if (mode == EvaluationMode.OFFLINE) {
            if (definitions.stream().anyMatch(definition -> definition.mode() != EvaluationMode.OFFLINE)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "OFFLINE 运行不能包含 AGENT 案例");
            }
            if (definitions.size() > MAX_OFFLINE_CASES) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "单次 OFFLINE 评测最多 " + MAX_OFFLINE_CASES + " 个案例");
            }
            return runOffline(definitions);
        }
        if (definitions.stream().anyMatch(definition -> definition.mode() != EvaluationMode.AGENT)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "AGENT 运行只能选择工作流案例");
        }
        if (definitions.size() > MAX_AGENT_CASES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "单次 AGENT 评测最多 " + MAX_AGENT_CASES + " 个案例");
        }
        ModelCheckVo readiness = modelReadinessService.readiness();
        if (definitions.stream().anyMatch(EvaluationCaseDefinition::chatRequired)
                && !readiness.isChatConnectionVerified()) {
            throw new AgentPrerequisiteException(AgentPrerequisiteException.CHAT_NOT_READY,
                    "AGENT 评测前置条件未满足：" + chatDependencyBlockReason(readiness));
        }
        if (definitions.stream().anyMatch(EvaluationCaseDefinition::embeddingRequired)
                && !readiness.isEmbeddingConnectionVerified()) {
            throw new AgentPrerequisiteException(AgentPrerequisiteException.EMBEDDING_NOT_READY,
                    embeddingDependencyBlockReason(readiness));
        }
        return startAgentRun(definitions);
    }

    public static String chatDependencyBlockReason(ModelCheckVo readiness) {
        if (readiness.isChatConnectionVerified()) {
            return null;
        }
        String reason = readiness.getAgentEvaluationBlockReason();
        return reason == null || reason.isBlank() ? "CHAT 模型未配置或尚未完成连接测试。" : reason;
    }

    public static String embeddingDependencyBlockReason(ModelCheckVo readiness) {
        if (readiness.isEmbeddingConnectionVerified()) {
            return null;
        }
        if (ModelReadinessService.STATE_NOT_CONFIGURED.equals(readiness.getEmbeddingState())) {
            return "所选 AGENT 案例声明依赖 EMBEDDING 向量检索，但未配置激活的 EMBEDDING 模型。";
        }
        return "所选 AGENT 案例依赖 EMBEDDING 向量检索，但 EMBEDDING 尚未通过手动连接测试"
                + (readiness.getEmbeddingTestError() == null ? "。" : "：" + readiness.getEmbeddingTestError());
    }

    // ── OFFLINE ────────────────────────────────────────────────────────────

    private EvaluationRun runOffline(List<EvaluationCaseDefinition> definitions) {
        Instant startedAt = Instant.now();
        List<EvaluationCaseResult> results = new ArrayList<>();
        for (EvaluationCaseDefinition definition : definitions) {
            try {
                results.add(evaluateDeterministic(definition));
            }
            catch (RuntimeException exception) {
                results.add(failedCase(definition, null, "EVALUATION_ERROR",
                        truncate("离线评测执行异常: " + exception.getMessage(), 900), null));
            }
        }
        Map<String, Object> runMetrics = new LinkedHashMap<>();
        runMetrics.put("offlineDeterministic", true);
        runMetrics.put("modelCalls", 0);
        return completeRun(UUID.randomUUID().toString(), EvaluationMode.OFFLINE, OFFLINE_ENVIRONMENT, startedAt,
                caseSetVersion(EvaluationMode.OFFLINE), results, runMetrics);
    }

    private EvaluationCaseResult evaluateDeterministic(EvaluationCaseDefinition definition) {
        long startedAt = System.nanoTime();
        List<EvaluationAssertionResult> assertions = new ArrayList<>();
        Map<String, Object> observation = new LinkedHashMap<>();
        try {
            if (feedbackRepository != null && feedbackRepository.findEnabledCase(definition.id()).isPresent()) {
                FeedbackCaseRecord feedbackCase = feedbackRepository.findEnabledCase(definition.id()).orElseThrow();
                evaluateFeedbackCase(feedbackCase, assertions, observation);
                return toCaseResult(definition, assertions, observation, durationMs(startedAt),
                        feedbackCase.taskId(), null, null);
            }
            SupplyChainEvaluationDataset.EvaluationCase datasetCase = catalog.datasetCase(definition.id())
                .orElseThrow(() -> new IllegalStateException("数据集案例缺失: " + definition.id()));
            switch (datasetCase.kind()) {
                case ORDER_RISK -> evaluateOrderRisk(datasetCase, assertions, observation);
                case INVENTORY -> evaluateInventory(datasetCase, assertions, observation);
                case SUPPLIER -> evaluateSupplier(datasetCase, assertions, observation);
                case RETRIEVAL -> evaluateRetrieval(datasetCase, assertions, observation);
                default -> assertions.add(new EvaluationAssertionResult("unsupported", "不支持的离线案例类型",
                        "ORDER_RISK/INVENTORY/SUPPLIER/RETRIEVAL", datasetCase.kind().name(), false));
            }
        }
        catch (Exception exception) {
            assertions.add(new EvaluationAssertionResult("evaluation", "评测执行未抛出异常",
                    "no exception", exception.getClass().getSimpleName() + ": " + exception.getMessage(), false));
        }
        return toCaseResult(definition, assertions, observation, durationMs(startedAt), null, null, null);
    }

    private void evaluateFeedbackCase(FeedbackCaseRecord feedbackCase,
            List<EvaluationAssertionResult> assertions, Map<String, Object> observation) {
        SupplyChainTaskStatus task = workflowService.task(feedbackCase.taskId());
        RiskAnalysisReport report = null;
        DecisionTaskResult decision = null;
        if (feedbackCase.resultType() == com.alibaba.cloud.ai.dataagent.evaluation.feedback.FeedbackResultType.ANALYSIS) {
            report = workflowService.report(feedbackCase.taskId());
        }
        else {
            decision = workflowService.decision(feedbackCase.taskId());
        }
        List<OrderRiskAssessment> assessments = report != null ? report.atRiskOrders()
                : decision.atRiskOrders() == null ? List.of() : decision.atRiskOrders();
        String narrativeSource = report != null ? report.narrativeSource()
                : decision != null ? decision.narrativeSource() : "UNKNOWN";
        List<String> gaps = assessments.stream().flatMap(item -> item.dataGaps().stream()).distinct().toList();
        observation.put("feedbackId", feedbackCase.feedbackId());
        observation.put("feedbackCaseId", feedbackCase.caseId());
        observation.put("taskId", feedbackCase.taskId());
        observation.put("resultType", feedbackCase.resultType().name());
        observation.put("narrativeSource", narrativeSource);
        observation.put("atRiskOrderCount", assessments.size());
        observation.put("dataGapCount", gaps.size());
        observation.put("source", "FEEDBACK_REGRESSION");
        for (FeedbackAssertion assertion : feedbackCase.assertions()) {
            String actual;
            boolean passed;
            try {
                switch (assertion.type()) {
                    case TASK_STATUS -> {
                        actual = task.status();
                        passed = assertion.expected().equalsIgnoreCase(actual);
                    }
                    case NARRATIVE_SOURCE -> {
                        actual = narrativeSource;
                        passed = assertion.expected().equalsIgnoreCase(actual);
                    }
                    case AT_RISK_ORDER_COUNT -> {
                        actual = String.valueOf(assessments.size());
                        passed = assertion.expected().equals(actual);
                    }
                    case HIGH_RISK_ORDER_COUNT -> {
                        actual = String.valueOf(assessments.stream()
                            .filter(item -> "HIGH".equals(item.riskLevel().name()))
                            .count());
                        passed = assertion.expected().equals(actual);
                    }
                    case DATA_GAP_COUNT -> {
                        actual = String.valueOf(gaps.size());
                        passed = assertion.expected().equals(actual);
                    }
                    case RISK_SCORE -> {
                        OrderRiskAssessment order = findAssessment(assessments, assertion.target());
                        actual = order == null ? "missing" : String.valueOf(order.riskScore());
                        passed = order != null && assertion.expected().equals(actual);
                    }
                    case RISK_LEVEL -> {
                        OrderRiskAssessment order = findAssessment(assessments, assertion.target());
                        actual = order == null ? "missing" : order.riskLevel().name();
                        passed = order != null && assertion.expected().equalsIgnoreCase(actual);
                    }
                    case EVIDENCE_CODE -> {
                        OrderRiskAssessment order = findAssessment(assessments, assertion.target());
                        actual = order == null ? "missing" : order.evidence().stream()
                            .map(evidence -> evidence.code()).toList().toString();
                        passed = order != null && order.evidence().stream()
                            .anyMatch(evidence -> evidence.code().equals(assertion.expected()));
                    }
                    case DECISION_INTENT -> {
                        actual = decision == null ? "missing" : decision.intent();
                        passed = decision != null && assertion.expected().equalsIgnoreCase(actual);
                    }
                    default -> {
                        actual = "unsupported";
                        passed = false;
                    }
                }
            }
            catch (RuntimeException exception) {
                actual = exception.getClass().getSimpleName() + ": " + exception.getMessage();
                passed = false;
            }
            assertions.add(new EvaluationAssertionResult(assertion.id(),
                    assertion.description() == null ? assertion.type().name() : assertion.description(),
                    assertion.expected(), actual, passed));
        }
    }

    private static OrderRiskAssessment findAssessment(List<OrderRiskAssessment> assessments, String orderId) {
        if (orderId == null) {
            return null;
        }
        return assessments.stream()
            .filter(item -> orderId.equals(item.order().orderId()))
            .findFirst()
            .orElse(null);
    }

    private void evaluateOrderRisk(SupplyChainEvaluationDataset.EvaluationCase datasetCase,
            List<EvaluationAssertionResult> assertions, Map<String, Object> observation) {
        OrderRiskAssessment assessment = queryService.findOrders(null, null)
            .stream()
            .filter(item -> item.order().orderId().equals(datasetCase.targetId()))
            .findFirst()
            .orElse(null);
        assertions.add(new EvaluationAssertionResult("order-exists", "订单存在",
                datasetCase.targetId(), assessment == null ? "missing" : "found", assessment != null));
        if (assessment == null) {
            return;
        }
        var risk = datasetCase.risk();
        observation.put("orderId", assessment.order().orderId());
        observation.put("riskLevel", assessment.riskLevel().name());
        observation.put("riskScore", assessment.riskScore());
        observation.put("inventoryShortage", assessment.inventoryShortage());
        observation.put("riskFactors", assessment.riskFactors());
        observation.put("recommendedActions", assessment.recommendedActions());
        if (risk.level() != null) {
            assertions.add(new EvaluationAssertionResult("risk-level", "风险等级", risk.level().name(),
                    assessment.riskLevel().name(), risk.level() == assessment.riskLevel()));
        }
        if (risk.score() != null) {
            assertions.add(new EvaluationAssertionResult("risk-score", "风险分", String.valueOf(risk.score()),
                    String.valueOf(assessment.riskScore()), risk.score() == assessment.riskScore()));
        }
        if (risk.shortage() != null) {
            assertions.add(new EvaluationAssertionResult("risk-shortage", "库存缺口",
                    String.valueOf(risk.shortage()), String.valueOf(assessment.inventoryShortage()),
                    risk.shortage() == assessment.inventoryShortage()));
        }
        if (risk.atRisk() != null) {
            // The production assessment exposes the score, not an atRisk flag; this assertion
            // states the evaluation threshold explicitly instead of pretending it is a field.
            boolean atRisk = assessment.riskScore() >= 60;
            assertions.add(new EvaluationAssertionResult("risk-flag", "风险标记（评测口径 score>=60）",
                    String.valueOf(risk.atRisk()), String.valueOf(atRisk), risk.atRisk() == atRisk));
        }
        for (String fragment : risk.factorFragments()) {
            boolean contains = assessment.riskFactors().stream().anyMatch(factor -> factor.contains(fragment));
            assertions.add(new EvaluationAssertionResult("risk-factor-" + assertions.size(), "风险因素包含「" + fragment + "」",
                    fragment, String.valueOf(assessment.riskFactors()), contains));
        }
        for (String fragment : risk.actionFragments()) {
            boolean contains = assessment.recommendedActions().stream().anyMatch(action -> action.contains(fragment));
            assertions.add(new EvaluationAssertionResult("risk-action-" + assertions.size(), "建议动作包含「" + fragment + "」",
                    fragment, String.valueOf(assessment.recommendedActions()), contains));
        }
    }

    private void evaluateInventory(SupplyChainEvaluationDataset.EvaluationCase datasetCase,
            List<EvaluationAssertionResult> assertions, Map<String, Object> observation) {
        InventoryPosition position = queryService.findInventory(datasetCase.targetId()).stream().findFirst().orElse(null);
        assertions.add(new EvaluationAssertionResult("inventory-exists", "库存记录存在",
                datasetCase.targetId(), position == null ? "missing" : "found", position != null));
        if (position == null) {
            return;
        }
        var expectation = datasetCase.inventory();
        observation.put("sku", position.sku());
        observation.put("onHandQuantity", position.onHandQuantity());
        observation.put("reservedQuantity", position.reservedQuantity());
        observation.put("safetyStock", position.safetyStock());
        observation.put("availableToPromise", position.availableToPromise());
        assertions.add(new EvaluationAssertionResult("inventory-on-hand", "在库数量",
                String.valueOf(expectation.onHandQuantity()), String.valueOf(position.onHandQuantity()),
                Objects.equals(expectation.onHandQuantity(), position.onHandQuantity())));
        assertions.add(new EvaluationAssertionResult("inventory-reserved", "预留数量",
                String.valueOf(expectation.reservedQuantity()), String.valueOf(position.reservedQuantity()),
                Objects.equals(expectation.reservedQuantity(), position.reservedQuantity())));
        assertions.add(new EvaluationAssertionResult("inventory-safety", "安全库存",
                String.valueOf(expectation.safetyStock()), String.valueOf(position.safetyStock()),
                Objects.equals(expectation.safetyStock(), position.safetyStock())));
        assertions.add(new EvaluationAssertionResult("inventory-atp", "可承诺库存",
                String.valueOf(expectation.availableToPromise()), String.valueOf(position.availableToPromise()),
                Objects.equals(expectation.availableToPromise(), position.availableToPromise())));
    }

    private void evaluateSupplier(SupplyChainEvaluationDataset.EvaluationCase datasetCase,
            List<EvaluationAssertionResult> assertions, Map<String, Object> observation) {
        Optional<SupplierRiskProfile> supplier = queryService.findSupplier(datasetCase.targetId());
        assertions.add(new EvaluationAssertionResult("supplier-exists", "供应商存在",
                datasetCase.targetId(), supplier.isPresent() ? "found" : "missing", supplier.isPresent()));
        if (supplier.isEmpty()) {
            return;
        }
        SupplierRiskProfile profile = supplier.get();
        var expectation = datasetCase.supplier();
        observation.put("supplierId", profile.supplierId());
        observation.put("riskLevel", profile.riskLevel().name());
        observation.put("onTimeDeliveryRate", profile.onTimeDeliveryRate());
        observation.put("defectRate", profile.defectRate());
        assertions.add(new EvaluationAssertionResult("supplier-risk-level", "供应商风险等级",
                expectation.level().name(), profile.riskLevel().name(), expectation.level() == profile.riskLevel()));
        assertions.add(new EvaluationAssertionResult("supplier-on-time", "准时交付率",
                String.valueOf(expectation.onTimeDeliveryRate()), String.valueOf(profile.onTimeDeliveryRate()),
                expectation.onTimeDeliveryRate() != null
                        && Math.abs(expectation.onTimeDeliveryRate() - profile.onTimeDeliveryRate()) < 1e-9));
        assertions.add(new EvaluationAssertionResult("supplier-defect", "缺陷率",
                String.valueOf(expectation.defectRate()), String.valueOf(profile.defectRate()),
                expectation.defectRate() != null
                        && Math.abs(expectation.defectRate() - profile.defectRate()) < 1e-9));
    }

    private void evaluateRetrieval(SupplyChainEvaluationDataset.EvaluationCase datasetCase,
            List<EvaluationAssertionResult> assertions, Map<String, Object> observation) {
        List<KnowledgeCitation> citations = knowledgeService.retrieve(datasetCase.question());
        List<String> actual = citations.stream().map(KnowledgeCitation::sourceId).toList();
        List<String> expected = datasetCase.retrieval() == null ? List.of()
                : datasetCase.retrieval().expectedSourceIds();
        int rank = firstMatchingRank(expected, actual);
        boolean passed = expected.isEmpty() ? actual.isEmpty() : rank > 0;
        assertions.add(new EvaluationAssertionResult("retrieval-hit", "Top-3 检索命中预期来源",
                expected.isEmpty() ? "无来源" : expected.toString(), actual.toString(), passed));
        observation.put("query", datasetCase.question());
        observation.put("expected", expected);
        observation.put("actual", actual);
        observation.put("rank", rank);
        observation.put("topK", 3);
    }

    private static int firstMatchingRank(List<String> expected, List<String> actual) {
        for (int index = 0; index < actual.size(); index++) {
            if (expected.contains(actual.get(index))) {
                return index + 1;
            }
        }
        return 0;
    }

    // ── AGENT ──────────────────────────────────────────────────────────────

    private EvaluationRun startAgentRun(List<EvaluationCaseDefinition> definitions) {
        String runId = UUID.randomUUID().toString();
        if (!evaluationLease.claim(runId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "已有 AGENT 评测持有数据库租约，请等待其完成或租约到期后再启动");
        }
        Instant startedAt = Instant.now();
        boolean persisted = false;
        try {
            List<EvaluationCaseResult> results = new ArrayList<>();
            for (EvaluationCaseDefinition definition : definitions) {
                results.add(submitAgentCase(definition));
            }
            EvaluationRun running = buildRun(runId, EvaluationMode.AGENT, AGENT_ENVIRONMENT, startedAt, null,
                    EvaluationRunStatus.RUNNING, caseSetVersion(EvaluationMode.AGENT), results, agentMetrics(), null);
            if (!repository.saveOwned(running, evaluationLease.ownerId(), evaluationLease.activeFence())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "评测所有权在运行创建前已丢失，请重新发起评测");
            }
            persisted = true;
            return getRun(runId);
        }
        catch (RuntimeException exception) {
            if (!persisted) {
                evaluationLease.release();
            }
            throw exception;
        }
    }

    private EvaluationCaseResult submitAgentCase(EvaluationCaseDefinition definition) {
        try {
            String taskMode = catalog.workflowCase(definition.id())
                .map(WorkflowEvaluationCase::taskMode)
                .orElse("ANALYSIS");
            SupplyChainTaskStatus task = workflowService.submit(definition.question(), taskMode);
            Map<String, Object> observation = new LinkedHashMap<>();
            observation.put("taskId", task.taskId());
            observation.put("taskMode", taskMode);
            observation.put("submittedAt", Instant.now().toString());
            return new EvaluationCaseResult(definition.id(), definition.name(), definition.scenario(),
                    definition.category(), EvaluationCaseStatus.RUNNING, 0, task.taskId(), List.of(), write(observation),
                    null, null, definition.version(), taskMode);
        }
        catch (Exception exception) {
            String taskMode = catalog.workflowCase(definition.id()).map(WorkflowEvaluationCase::taskMode)
                .orElse("ANALYSIS");
            return new EvaluationCaseResult(definition.id(), definition.name(), definition.scenario(),
                    definition.category(), EvaluationCaseStatus.FAILED, 0, null, List.of(), "{}", "SUBMIT_FAILED",
                    truncate("任务提交失败: " + exception.getMessage(), 900), definition.version(), taskMode);
        }
    }

    private synchronized EvaluationRun refreshAgentRun(EvaluationRun run) {
        boolean owned = evaluationLease.ownsRun(run.runId()) ? evaluationLease.renew()
                : evaluationLease.claim(run.runId());
        if (!owned) {
            // Another instance holds the live lease; returning the committed state is the only
            // correct answer, because writing it would race with that owner.
            return run;
        }
        Instant now = Instant.now();
        boolean timeout = Duration.between(run.startedAt(), now).compareTo(AGENT_TIMEOUT) > 0;
        List<EvaluationCaseResult> updated = new ArrayList<>();
        boolean anyRunning = false;
        boolean anyCancelled = false;
        for (EvaluationCaseResult result : run.results()) {
            if (result.status() != EvaluationCaseStatus.RUNNING) {
                updated.add(result);
                continue;
            }
            long elapsed = Duration.between(run.startedAt(), now).toMillis();
            if (timeout) {
                updated.add(withStatus(result, EvaluationCaseStatus.UNDETERMINED, elapsed, List.of(),
                        result.observationJson(), "TIMEOUT", "任务在 " + AGENT_TIMEOUT.toMinutes() + " 分钟内未完成"));
                continue;
            }
            if (result.taskId() == null) {
                updated.add(withStatus(result, EvaluationCaseStatus.FAILED, elapsed, List.of(),
                        result.observationJson(), "SUBMIT_FAILED", "任务未成功提交"));
                continue;
            }
            SupplyChainTaskStatus task;
            try {
                task = workflowService.task(result.taskId());
            }
            catch (Exception exception) {
                updated.add(withStatus(result, EvaluationCaseStatus.UNDETERMINED, elapsed, List.of(),
                        result.observationJson(), "TASK_MISSING", truncate(exception.getMessage(), 900)));
                continue;
            }
            if (task == null) {
                updated.add(withStatus(result, EvaluationCaseStatus.UNDETERMINED, elapsed, List.of(),
                        result.observationJson(), "TASK_MISSING", "任务状态不存在"));
                continue;
            }
            if ("COMPLETED".equals(task.status())) {
                updated.add(evaluateWorkflowCase(result, elapsed, task));
            }
            else if ("FAILED".equals(task.status())) {
                updated.add(withStatus(result, EvaluationCaseStatus.FAILED, elapsed, List.of(),
                        result.observationJson(), "TASK_FAILED",
                        truncate(task.error() == null ? "任务失败" : task.error(), 900)));
            }
            else if ("CANCELLED".equals(task.status())) {
                anyCancelled = true;
                updated.add(withStatus(result, EvaluationCaseStatus.UNDETERMINED, elapsed, List.of(),
                        result.observationJson(), "TASK_CANCELLED",
                        truncate(task.error() == null ? "任务已被取消" : task.error(), 900)));
            }
            else {
                anyRunning = true;
                updated.add(withStatus(result, EvaluationCaseStatus.RUNNING, elapsed, result.assertions(),
                        result.observationJson(), null, null));
            }
        }
        String failureReason = timeout ? TIMEOUT_FAILURE : (anyCancelled ? CANCELLED_FAILURE : null);
        EvaluationRunStatus status = anyRunning ? EvaluationRunStatus.RUNNING
                : (failureReason == null ? EvaluationRunStatus.COMPLETED : EvaluationRunStatus.FAILED);
        EvaluationRun next = buildRun(run.runId(), run.mode(), run.environment(), run.startedAt(),
                status == EvaluationRunStatus.RUNNING ? null : now, status,
                run.datasetVersion() == null || run.datasetVersion().isBlank() ? caseSetVersion(run.mode())
                        : run.datasetVersion(),
                updated, agentMetrics(), failureReason);
        if (!repository.saveOwned(next, evaluationLease.ownerId(), evaluationLease.activeFence())) {
            // The lease moved to another JVM between the renewal and this write.
            evaluationLease.release();
            return repository.find(run.runId()).orElse(next);
        }
        if (status != EvaluationRunStatus.RUNNING) {
            evaluationLease.release();
        }
        return next;
    }

    private EvaluationCaseResult evaluateWorkflowCase(EvaluationCaseResult runningResult, long elapsed,
            SupplyChainTaskStatus task) {
        EvaluationCaseDefinition definition = caseOrThrow(runningResult.caseId());
        List<EvaluationAssertionResult> assertions = new ArrayList<>();
        Map<String, Object> observation = new LinkedHashMap<>();
        observation.put("taskId", runningResult.taskId());
        observation.put("taskStatus", task.status());
        String taskMode = catalog.workflowCase(definition.id())
            .map(WorkflowEvaluationCase::taskMode)
            .orElse("ANALYSIS");
        String failureType = null;
        String failureMessage = null;
        try {
            if ("DECISION".equalsIgnoreCase(taskMode)) {
                DecisionTaskResult decision = workflowService.decision(runningResult.taskId());
                assertions.add(new EvaluationAssertionResult("decision-exists", "决策结果可读取", "present",
                        decision == null ? "missing" : "present", decision != null));
                if (decision == null) {
                    failureType = "DECISION_MISSING";
                    failureMessage = "决策结果不存在";
                }
                else {
                    evaluateDecisionAssertions(definition, decision, runningResult.taskId(), assertions, observation);
                }
            }
            else {
                RiskAnalysisReport report = workflowService.report(runningResult.taskId());
                assertions.add(new EvaluationAssertionResult("report-exists", "分析报告可读取", "present",
                        report == null ? "missing" : "present", report != null));
                if (report == null) {
                    failureType = "REPORT_MISSING";
                    failureMessage = "分析报告不存在";
                }
                else {
                    evaluateReportAssertions(definition, report, runningResult.taskId(), task.status(), assertions,
                            observation);
                }
            }
        }
        catch (Exception exception) {
            assertions.add(new EvaluationAssertionResult("evaluation", "评测执行未抛出异常", "ok",
                    truncate(exception.getMessage(), 300), false));
            failureType = "EVALUATION_ERROR";
            failureMessage = truncate(exception.getMessage(), 900);
        }
        List<EvaluationAssertionResult> failed = assertions.stream().filter(assertion -> !assertion.passed()).toList();
        if (failureType == null && !failed.isEmpty()) {
            failureType = "ASSERTION_MISMATCH";
            failureMessage = truncate(failed.stream().map(EvaluationAssertionResult::description)
                .reduce((left, right) -> left + "；" + right)
                .orElse("断言未通过"), 900);
        }
        List<String> notEvaluated = missingRequiredAssertions(definition.id(), assertions);
        if (failureType == null && !notEvaluated.isEmpty()) {
            failureType = "ASSERTION_NOT_EVALUATED";
            failureMessage = truncate("案例声明的断言未执行，不能判定通过: " + notEvaluated, 900);
        }
        EvaluationCaseStatus status = failureType == null ? EvaluationCaseStatus.PASSED : EvaluationCaseStatus.FAILED;
        return new EvaluationCaseResult(definition.id(), definition.name(), definition.scenario(),
                definition.category(), status, elapsed, runningResult.taskId(), assertions, write(observation),
                failureType, failureMessage, definition.version(), taskMode);
    }

    private void evaluateReportAssertions(EvaluationCaseDefinition definition, RiskAnalysisReport report,
            String taskId, String taskStatus, List<EvaluationAssertionResult> assertions,
            Map<String, Object> observation) {
        List<String> atRiskIds = report.atRiskOrders().stream().map(item -> item.order().orderId()).toList();
        observation.put("summary", report.summary());
        observation.put("modelAssisted", report.modelAssisted());
        observation.put("plannerAssisted", report.plannerAssisted());
        observation.put("atRiskOrderIds", atRiskIds);
        observation.put("citationSourceIds", report.citations().stream().map(KnowledgeCitation::sourceId).toList());
        observation.put("recommendations", report.recommendations());
        observation.put("toolCallCount", report.toolCalls().size());
        assertions.add(new EvaluationAssertionResult("task-completed", "任务状态为 COMPLETED", "COMPLETED",
                String.valueOf(taskStatus), "COMPLETED".equals(taskStatus)));
        assertions.add(new EvaluationAssertionResult("report-at-risk", "报告包含风险订单", ">=1",
                String.valueOf(atRiskIds.size()), !atRiskIds.isEmpty()));
        catalog.datasetCase(definition.id()).ifPresent(datasetCase -> {
            if (datasetCase.kind() == SupplyChainEvaluationDataset.Kind.REPORT_FALLBACK
                    && datasetCase.report() != null && !datasetCase.report().atRiskOrderIds().isEmpty()) {
                List<String> expected = datasetCase.report().atRiskOrderIds();
                boolean containsAll = atRiskIds.containsAll(expected);
                assertions.add(new EvaluationAssertionResult("report-at-risk-expected", "报告包含预期风险订单",
                        expected.toString(), atRiskIds.toString(), containsAll));
            }
        });
        if (requiresAssertion(definition.id(), "report-shortage")) {
            boolean hasShortage = report.atRiskOrders().stream().anyMatch(item -> item.inventoryShortage() > 0);
            assertions.add(new EvaluationAssertionResult("report-shortage", "风险订单中存在库存缺口", ">=1",
                    String.valueOf(report.atRiskOrders().stream().filter(item -> item.inventoryShortage() > 0).count()),
                    hasShortage));
        }
        if (requiresAssertion(definition.id(), "report-model-assisted")) {
            assertions.add(new EvaluationAssertionResult("report-model-assisted", "报告由模型成功生成", "true",
                    "modelAssisted=" + report.modelAssisted() + ", narrativeSource=" + report.narrativeSource(),
                    report.modelAssisted()));
        }
        if (requiresAssertion(definition.id(), "report-model-completed")) {
            long succeeded = successfulModelCompletions(taskId, "GENERATING_REPORT");
            assertions.add(new EvaluationAssertionResult("report-model-completed", "报告阶段记录了成功的模型事件",
                    ">=1", String.valueOf(succeeded), succeeded >= 1));
        }
        if (definition.id().equals("REPORT-FALLBACK-NO-MODEL")) {
            List<SupplyChainTaskEvent> events = workflowService.events(taskId);
            List<String> types = events.stream().map(SupplyChainTaskEvent::type).toList();
            boolean recorded = types.contains("MODEL_COMPLETED") || types.contains("MODEL_FAILED")
                    || types.contains("FALLBACK_USED");
            observation.put("eventTypes", new LinkedHashSet<>(types));
            assertions.add(new EvaluationAssertionResult("model-or-fallback-event", "记录了模型调用或降级事件", "true",
                    String.valueOf(recorded), recorded));
        }
    }

    private void evaluateDecisionAssertions(EvaluationCaseDefinition definition, DecisionTaskResult decision,
            String taskId, List<EvaluationAssertionResult> assertions, Map<String, Object> observation) {
        int candidateCount = decision.comparison() == null || decision.comparison().candidates() == null ? 0
                : decision.comparison().candidates().size();
        int recommendationCount = decision.recommendations() == null ? 0 : decision.recommendations().size();
        int evidenceCount = (decision.evidenceNotes() == null ? 0 : decision.evidenceNotes().size())
                + (decision.citations() == null ? 0 : decision.citations().size())
                + (decision.toolCalls() == null ? 0 : decision.toolCalls().size());
        observation.put("intent", decision.intent());
        observation.put("status", decision.status());
        observation.put("clarificationRequired", decision.clarificationRequired());
        observation.put("candidateCount", candidateCount);
        observation.put("recommendationCount", recommendationCount);
        observation.put("evidenceCount", evidenceCount);
        observation.put("realDataMutated", decision.realDataMutated());
        assertions.add(new EvaluationAssertionResult("real-data-mutated", "未修改真实业务数据", "false",
                String.valueOf(decision.realDataMutated()), !decision.realDataMutated()));
        if (definition.id().equals("WF-DECISION-COMPARE")) {
            assertions.add(new EvaluationAssertionResult("comparison-exists", "包含候选方案比较", ">=2",
                    String.valueOf(candidateCount), candidateCount >= 2));
        }
        else if (definition.id().equals("WF-DECISION-UNSUPPORTED")) {
            String intent = decision.intent() == null ? "" : decision.intent().toUpperCase(Locale.ROOT);
            boolean unsupported = intent.contains("UNSUPPORTED") || decision.clarificationRequired();
            assertions.add(new EvaluationAssertionResult("unsupported-or-clarify", "标记为不支持或需要澄清", "true",
                    "intent=" + decision.intent() + ", clarification=" + decision.clarificationRequired(), unsupported));
        }
        else if (definition.id().equals("WF-DECISION-EVIDENCE")) {
            assertions.add(new EvaluationAssertionResult("recommendations", "至少 1 条处置建议", ">=1",
                    String.valueOf(recommendationCount), recommendationCount >= 1));
            assertions.add(new EvaluationAssertionResult("evidence", "存在证据、引用或工具调用", ">=1",
                    String.valueOf(evidenceCount), evidenceCount >= 1));
        }
        observation.put("narrativeSource", decision.narrativeSource());
        observation.put("fallbackUsed", decision.fallbackUsed());
        if (requiresAssertion(definition.id(), "decision-model-assisted")) {
            assertions.add(new EvaluationAssertionResult("decision-model-assisted", "决策结论由模型生成", "true",
                    "modelAssisted=" + decision.modelAssisted() + ", narrativeSource=" + decision.narrativeSource()
                            + ", fallbackUsed=" + decision.fallbackUsed(),
                    decision.modelAssisted() && !decision.fallbackUsed()));
        }
        if (requiresAssertion(definition.id(), "decision-model-completed")) {
            long succeeded = successfulModelCompletions(taskId, "DECISION_SUMMARY");
            assertions.add(new EvaluationAssertionResult("decision-model-completed", "决策阶段记录了成功的模型事件",
                    ">=1", String.valueOf(succeeded), succeeded >= 1));
        }
    }

    /**
     * Successful model participation, counted from the event trail rather than from task state. A
     * {@code MODEL_STARTED} row only proves an attempt was made, and a response that arrived after the
     * task had already reached a terminal state is recorded with status {@code CANCELLED} and ignored -
     * so neither may satisfy a model-participation assertion.
     */
    private long successfulModelCompletions(String taskId, String stage) {
        return workflowService.events(taskId).stream()
            .filter(event -> SupplyChainEventType.MODEL_COMPLETED.equals(event.type()))
            .filter(event -> stage.equals(event.stage()))
            .filter(event -> SupplyChainEventStatus.COMPLETED.equals(event.status()))
            .count();
    }

    private boolean requiresAssertion(String caseId, String assertionId) {
        return catalog.workflowCase(caseId).map(workflow -> workflow.requiresAssertion(assertionId)).orElse(false);
    }

    /**
     * Assertions the case declares are the contract of the run. When one of them was never
     * produced - unknown category, missing evidence, changed assertion id - the case must fail
     * instead of passing on the assertions that did happen to run.
     */
    private List<String> missingRequiredAssertions(String caseId, List<EvaluationAssertionResult> assertions) {
        List<String> produced = assertions.stream().map(EvaluationAssertionResult::id).toList();
        return catalog.workflowCase(caseId)
            .map(workflow -> workflow.requiredAssertionIds().stream()
                .filter(assertionId -> !produced.contains(assertionId))
                .toList())
            .orElse(List.of());
    }

    private EvaluationCaseResult withStatus(EvaluationCaseResult result, EvaluationCaseStatus status, long durationMs,
            List<EvaluationAssertionResult> assertions, String observationJson, String failureType,
            String failureMessage) {
        return new EvaluationCaseResult(result.caseId(), result.name(), result.scenario(), result.category(), status,
                durationMs, result.taskId(), assertions, observationJson, failureType, failureMessage,
                result.caseVersion(), result.taskMode());
    }

    private Map<String, Object> agentMetrics() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("offlineDeterministic", false);
        metrics.put("modelCallsMayOccur", true);
        metrics.put("environment", AGENT_ENVIRONMENT);
        return metrics;
    }

    private EvaluationRun completeRun(String runId, EvaluationMode mode, String environment, Instant startedAt,
            String caseSetVersion, List<EvaluationCaseResult> results, Map<String, Object> baseMetrics) {
        EvaluationRun preliminary = buildRun(runId, mode, environment, startedAt, Instant.now(),
                EvaluationRunStatus.COMPLETED, caseSetVersion, results, baseMetrics, null);
        EvaluationMetrics metrics = metricsCalculator.calculate(mode, List.of(preliminary));
        Map<String, Object> metricsMap = new LinkedHashMap<>(baseMetrics);
        metricsMap.put("summary", mapper.convertValue(metrics, MAP_TYPE));
        EvaluationRun completed = new EvaluationRun(preliminary.runId(), preliminary.datasetVersion(),
                preliminary.mode(), preliminary.status(), preliminary.environment(), preliminary.startedAt(),
                preliminary.completedAt(), preliminary.totalCases(), preliminary.passed(), preliminary.failed(),
                preliminary.undetermined(), preliminary.durationMs(), metricsMap, preliminary.results(), null);
        repository.save(completed);
        return completed;
    }

    private EvaluationRun buildRun(String runId, EvaluationMode mode, String environment, Instant startedAt,
            Instant completedAt, EvaluationRunStatus status, String caseSetVersion, List<EvaluationCaseResult> results,
            Map<String, Object> metrics, String failureReason) {
        int passed = (int) results.stream().filter(result -> result.status() == EvaluationCaseStatus.PASSED).count();
        int failed = (int) results.stream().filter(result -> result.status() == EvaluationCaseStatus.FAILED).count();
        int undetermined = (int) results.stream()
            .filter(result -> result.status() == EvaluationCaseStatus.UNDETERMINED)
            .count();
        Long durationMs = completedAt == null ? null : Duration.between(startedAt, completedAt).toMillis();
        return new EvaluationRun(runId, caseSetVersion, mode, status, environment, startedAt, completedAt,
                results.size(), passed, failed, undetermined, durationMs, metrics, results, failureReason);
    }

    private String caseSetVersion(EvaluationMode mode) {
        return mode == EvaluationMode.AGENT ? EvaluationCatalog.WORKFLOW_VERSION : catalog.datasetVersion();
    }

    private EvaluationCaseResult failedCase(EvaluationCaseDefinition definition, String taskId, String failureType,
            String failureMessage, String taskMode) {
        return new EvaluationCaseResult(definition.id(), definition.name(), definition.scenario(),
                definition.category(), EvaluationCaseStatus.FAILED, 0, taskId, List.of(), "{}", failureType,
                failureMessage, definition.version(), taskMode);
    }

    private EvaluationCaseResult toCaseResult(EvaluationCaseDefinition definition,
            List<EvaluationAssertionResult> assertions, Map<String, Object> observation, long durationMs, String taskId,
            String failureTypeOverride, String failureMessageOverride) {
        List<EvaluationAssertionResult> failed = assertions.stream().filter(assertion -> !assertion.passed()).toList();
        String failureType = failureTypeOverride;
        String failureMessage = failureMessageOverride;
        if (failureType == null && !failed.isEmpty()) {
            boolean executionError = failed.stream().anyMatch(assertion -> "evaluation".equals(assertion.id()));
            failureType = executionError ? "EVALUATION_ERROR" : "ASSERTION_MISMATCH";
            failureMessage = truncate(failed.stream().map(EvaluationAssertionResult::description)
                .reduce((left, right) -> left + "；" + right)
                .orElse("断言未通过"), 900);
        }
        EvaluationCaseStatus status = failureType == null ? EvaluationCaseStatus.PASSED : EvaluationCaseStatus.FAILED;
        return new EvaluationCaseResult(definition.id(), definition.name(), definition.scenario(),
                definition.category(), status, durationMs, taskId, assertions, write(observation), failureType,
                failureMessage, definition.version(), null);
    }

    private long durationMs(long startedAtNanos) {
        return Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        }
        catch (Exception exception) {
            return "{}";
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

}
