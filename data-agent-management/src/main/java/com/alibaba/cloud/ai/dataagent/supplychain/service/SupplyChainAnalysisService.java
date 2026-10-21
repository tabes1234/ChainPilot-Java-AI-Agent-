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
import com.alibaba.cloud.ai.dataagent.supplychain.model.InventoryPosition;
import com.alibaba.cloud.ai.dataagent.supplychain.model.OrderRiskAssessment;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskAnalysisReport;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskNarrative;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplierRiskProfile;
import com.alibaba.cloud.ai.dataagent.supplychain.model.WorkflowPlan;
import com.alibaba.cloud.ai.dataagent.util.ChatResponseUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class SupplyChainAnalysisService {

    private static final List<String> DEFAULT_PLAN = List.of("查询延期和高风险订单", "核对相关 SKU 可承诺库存",
            "检查相关供应商履约风险", "校验订单、库存和供应商证据", "检索供应链 SOP 并生成建议");

    private static final Pattern PLAN_PREFIX = Pattern.compile("^(?:\\d+[.)、]|[-*•])\\s*");

    private static final int MAX_RATE_LIMIT_ATTEMPTS = 3;

    private static final long RATE_LIMIT_BACKOFF_MILLIS = 2_000L;

    private final SupplyChainQueryService queryService;
    private final SupplyChainAgentTools agentTools;
    private final AiModelRegistry modelRegistry;
    private final SupplyChainKnowledgeService knowledgeService;
    private final SupplyChainEventRecorder eventRecorder;
    private final ObservationRegistry observationRegistry;
    private final RiskNarrativeValidator narrativeValidator;
    private final ObjectMapper mapper;

    public RiskAnalysisReport analyze(String question) {
        return analyze(question, UUID.randomUUID().toString());
    }

    public RiskAnalysisReport analyze(String question, String taskId) {
        WorkflowPlan plan = createPlan(question);
        return analyze(question, taskId, queryService.findAtRiskOrders(), queryService.findInventory(null),
                queryService.findSuppliers(), 0, plan);
    }

    public RiskAnalysisReport analyze(String question, String taskId, List<OrderRiskAssessment> assessments,
            List<InventoryPosition> inventory, List<SupplierRiskProfile> suppliers, int validationAttempts,
            WorkflowPlan plan) {
        var citations = knowledgeService.retrieve(question);
        List<String> recommendations = assessments.stream()
                .flatMap(assessment -> assessment.recommendedActions().stream())
                .distinct()
                .toList();
        String deterministicSummary = assessments.isEmpty()
                ? "当前模拟数据中未发现延期风险订单。"
                : "发现 " + assessments.size() + " 个存在风险的订单，其中高风险 "
                        + assessments.stream().filter(item -> item.riskLevel().name().equals("HIGH")).count()
                        + " 个。风险依据来自交付日期、可承诺库存及供应商履约风险。";

        boolean modelAssisted = false;
        String summary = deterministicSummary;
        RiskNarrative modelNarrative = null;
        List<String> toolCalls = List.of();
        String eventTaskId = eventRecorder.currentTaskId();
        long modelStarted = System.nanoTime();
        eventRecorder.modelStarted(eventTaskId, "GENERATING_REPORT");
        try {
            ModelToolCallSession toolSession = ModelToolCallSession.open(agentTools, observationRegistry);
            try {
                String context = modelContext(taskId, assessments, inventory, suppliers, citations,
                        recommendations, plan);
                ModelInvocation invocation = callWithRateLimitRetry(() -> modelRegistry.getChatClient()
                        .prompt()
                        .system(MODEL_SYSTEM_PROMPT)
                        .user(question + "\n\n只依据以下确定性上下文回答，必须返回 JSON：\n" + context)
                        .toolCallbacks(toolSession.callbacks())
                        .call()
                        .chatResponse(), "GENERATING_REPORT");
                String modelOutput = invocation.content();
                if (!StringUtils.hasText(modelOutput)) {
                    eventRecorder.modelCompleted(eventTaskId, "GENERATING_REPORT", modelStarted,
                            "model returned an empty summary; " + invocation.usageSummary());
                    eventRecorder.fallbackUsed(eventTaskId, "GENERATING_REPORT", "EMPTY_MODEL_RESPONSE");
                }
                else {
                    try {
                        RiskNarrative accepted = narrativeValidator.validate(modelOutput, taskId, assessments,
                                recommendations);
                        summary = accepted.overview();
                        modelNarrative = accepted;
                        modelAssisted = true;
                        eventRecorder.modelCompleted(eventTaskId, "GENERATING_REPORT", modelStarted,
                                "model narrative accepted; reasons=" + accepted.reasons().size()
                                        + "; evidenceRefs="
                                        + accepted.reasons().stream().mapToInt(reason -> reason.evidenceIds().size()).sum()
                                        + "; " + invocation.usageSummary());
                    }
                    catch (RiskNarrativeValidator.InvalidNarrativeException invalid) {
                        eventRecorder.modelCompleted(eventTaskId, "GENERATING_REPORT", modelStarted,
                                "model narrative rejected; reason=" + invalid.reason() + "; "
                                        + invocation.usageSummary());
                        eventRecorder.fallbackUsed(eventTaskId, "GENERATING_REPORT", "INVALID_MODEL_NARRATIVE");
                        log.info("ChainPilot model narrative rejected. taskId={} reason={}", taskId,
                                invalid.reason());
                    }
                }
            }
            finally {
                toolCalls = toolSession.calls();
            }
        }
        catch (Exception exception) {
            eventRecorder.modelFailed(eventTaskId, "GENERATING_REPORT", modelStarted, exception);
            eventRecorder.fallbackUsed(eventTaskId, "GENERATING_REPORT", exception.getClass().getSimpleName());
            log.warn("ChainPilot model analysis unavailable; returning verified rule-based result. taskId={} causeType={}",
                    taskId, exception.getClass().getSimpleName());
        }

        return new RiskAnalysisReport(taskId, "COMPLETED", summary, plan.steps(), plan.modelAssisted(),
                assessments, recommendations, toolCalls, citations,
                modelAssisted, validationAttempts, Instant.now(), modelNarrative);
    }

    private static final String MODEL_SYSTEM_PROMPT = """
            你是供应链风险解释助手。风险分数、等级、证据和业务建议已经由 Java 确定性规则计算完成。
            你只能解释提供的证据，绝不能修改或重新计算风险分数/等级，也不能编造订单、库存、供应商、成本、概率、收益或交付数据。
            不得声称已经执行、修改真实数据或调用了未提供的工具。
            必须只输出一个 JSON 对象，不要 Markdown，不要额外文字，结构如下：
            {
              "overview": "对已完成确定性结论的简要解释",
              "reasons": [{"orderId": "必须来自 assessments", "summary": "解释该订单的证据", "evidenceIds": ["必须来自该订单的 evidence.code"]}],
              "businessImpacts": ["只能基于已有证据的影响说明"],
              "recommendations": ["必须逐字复制 allowedRecommendations 中的一项"],
              "uncertainties": ["如果 dataGaps 非空，必须逐字包含每一条 dataGap；也可以列出其他证据限制"]
            }
            所有出现的数字都必须来自上下文，不得新增数值。每个风险订单至少给出一条 reason，并覆盖所有风险订单。
            """;

    private String modelContext(String taskId, List<OrderRiskAssessment> assessments,
            List<InventoryPosition> inventory, List<SupplierRiskProfile> suppliers,
            List<com.alibaba.cloud.ai.dataagent.supplychain.model.KnowledgeCitation> citations,
            List<String> recommendations, WorkflowPlan plan) throws Exception {
        Map<String, Object> context = new java.util.LinkedHashMap<>();
        context.put("taskId", taskId);
        context.put("assessments", assessments);
        context.put("inventorySnapshot", inventory);
        context.put("supplierSnapshot", suppliers);
        context.put("citations", citations);
        context.put("allowedRecommendations", recommendations);
        context.put("executionPlan", plan.steps());
        context.put("dataGaps", assessments.stream().flatMap(item -> item.dataGaps().stream()).toList());
        return mapper.writeValueAsString(context);
    }

    /**
     * Reusable single-shot completion for the deterministic decision flow. Business facts are
     * computed before this call; the model only turns verified results into an explanation.
     */
    public ModelInvocation complete(String systemPrompt, String userPrompt, String stage) {
        return callWithRateLimitRetry(() -> modelRegistry.getChatClient()
                .prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .chatResponse(), stage);
    }

    public WorkflowPlan createPlan(String question) {        String taskId = eventRecorder.currentTaskId();
        long modelStarted = System.nanoTime();
        eventRecorder.modelStarted(taskId, "PLANNING");
        try {
            ModelInvocation invocation = callWithRateLimitRetry(() -> modelRegistry.getChatClient()
                    .prompt()
                    .system("你是供应链风险分析任务规划器。针对问题，只输出简短有序步骤。可用步骤：查询订单延期风险、查询库存与可承诺量、查询供应商履约风险、校验数据、检索 SOP 并生成有依据的建议。不要输出事实或执行任何写操作。")
                    .user(question)
                    .call()
                    .chatResponse(), "PLANNING");
            String plan = invocation.content();
            if (StringUtils.hasText(plan)) {
                List<String> steps = plan.lines().map(String::trim).filter(StringUtils::hasText)
                        .map(line -> PLAN_PREFIX.matcher(line).replaceFirst("")).limit(8).toList();
                if (!steps.isEmpty()) {
                    eventRecorder.modelCompleted(taskId, "PLANNING", modelStarted,
                            "model plan accepted; steps=" + steps.size() + "; " + invocation.usageSummary());
                    return new WorkflowPlan(steps, true);
                }
                eventRecorder.modelCompleted(taskId, "PLANNING", modelStarted,
                        "model returned an empty plan; " + invocation.usageSummary());
            }
            else {
                eventRecorder.modelCompleted(taskId, "PLANNING", modelStarted,
                        "model returned an empty response; " + invocation.usageSummary());
            }
            eventRecorder.fallbackUsed(taskId, "PLANNING", "EMPTY_PLAN_RESPONSE");
        }
        catch (Exception exception) {
            eventRecorder.modelFailed(taskId, "PLANNING", modelStarted, exception);
            eventRecorder.fallbackUsed(taskId, "PLANNING", exception.getClass().getSimpleName());
            log.info("ChainPilot planner unavailable; using the deterministic analysis plan. causeType={}",
                    exception.getClass().getSimpleName());
        }
        return new WorkflowPlan(DEFAULT_PLAN, false);
    }

    private ModelInvocation callWithRateLimitRetry(Supplier<ChatResponse> call, String stage) {
        for (int attempt = 1;; attempt++) {
            try {
                return ModelInvocation.from(call.get());
            }
            catch (RuntimeException exception) {
                if (attempt >= MAX_RATE_LIMIT_ATTEMPTS || !isRateLimited(exception)) {
                    throw exception;
                }
                log.info("ChainPilot model rate limited; retrying. stage={} attempt={}/{}", stage, attempt,
                        MAX_RATE_LIMIT_ATTEMPTS);
                try {
                    Thread.sleep(RATE_LIMIT_BACKOFF_MILLIS * attempt);
                }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw exception;
                }
            }
        }
    }

    public record ModelInvocation(String content, Integer promptTokens, Integer completionTokens,
            Integer totalTokens) {

        public ModelInvocation(String content) {
            this(content, null, null, null);
        }

        static ModelInvocation from(ChatResponse response) {
            String content = response == null ? null : ChatResponseUtil.getText(response);
            Usage usage = response == null || response.getMetadata() == null ? null
                    : response.getMetadata().getUsage();
            if (usage == null) {
                return new ModelInvocation(content, null, null, null);
            }
            Integer prompt = usage.getPromptTokens();
            Integer completion = usage.getCompletionTokens();
            Integer total = usage.getTotalTokens();
            boolean available = (prompt != null && prompt > 0) || (completion != null && completion > 0)
                    || (total != null && total > 0);
            return available ? new ModelInvocation(content, prompt, completion, total)
                    : new ModelInvocation(content, null, null, null);
        }

        public String usageSummary() {
            if (promptTokens == null && completionTokens == null && totalTokens == null) {
                return "token usage unavailable";
            }
            return "tokens prompt=" + promptTokens + ", completion=" + completionTokens
                    + ", total=" + totalTokens;
        }
    }

    private static boolean isRateLimited(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof RestClientResponseException responseException
                    && responseException.getStatusCode().value() == 429) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && (message.contains("429") || message.contains("1305")
                    || message.toLowerCase(Locale.ROOT).contains("rate limit"))) {
                return true;
            }
        }
        return false;
    }
}
