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

import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationTaskReference;
import com.alibaba.cloud.ai.dataagent.evaluation.repository.EvaluationRunRepository;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DecisionTaskResult;
import com.alibaba.cloud.ai.dataagent.supplychain.model.ObservabilityRunDetail;
import com.alibaba.cloud.ai.dataagent.supplychain.model.ObservabilityRunPage;
import com.alibaba.cloud.ai.dataagent.supplychain.model.ObservabilityRunSummary;
import com.alibaba.cloud.ai.dataagent.supplychain.model.ObservabilitySummary;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskAnalysisReport;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainEventStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainEventType;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskEvent;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskType;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.DecisionProposalRepository;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.DisposalPlanRepository;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.SupplyChainEventRepository;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.SupplyChainTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Read-only observability over the existing task and event trail. It deliberately does not
 * persist a second copy of execution data and never returns prompts, credentials, raw model
 * output or report bodies.
 */
@Service
public class SupplyChainObservabilityService {

    public static final String SCOPE_PREFIX = "最近分析/决策任务窗口";

    private static final int WINDOW_SIZE = 200;

    private static final int MAX_PAGE_SIZE = 50;

    private static final String UNKNOWN = "UNKNOWN";

    private static final String RISK_AUTHORITY = "JAVA_DETERMINISTIC_RULES";

    private final SupplyChainTaskRepository taskRepository;

    private final SupplyChainEventRepository eventRepository;

    private final DecisionProposalRepository proposalRepository;

    private final DisposalPlanRepository planRepository;

    private final EvaluationRunRepository evaluationRunRepository;

    private final ObjectMapper mapper;

    public SupplyChainObservabilityService(SupplyChainTaskRepository taskRepository,
            SupplyChainEventRepository eventRepository, DecisionProposalRepository proposalRepository,
            DisposalPlanRepository planRepository, EvaluationRunRepository evaluationRunRepository,
            ObjectMapper mapper) {
        this.taskRepository = taskRepository;
        this.eventRepository = eventRepository;
        this.proposalRepository = proposalRepository;
        this.planRepository = planRepository;
        this.evaluationRunRepository = evaluationRunRepository;
        this.mapper = mapper;
    }

    public ObservabilitySummary summary() {
        List<SupplyChainTaskRepository.ObservableTask> tasks = taskRepository.findObservableWindow(WINDOW_SIZE);
        Map<String, List<SupplyChainTaskEvent>> eventMap = eventsByTask(tasks);
        List<ParsedTask> parsed = tasks.stream().map(task -> parse(task, eventMap.getOrDefault(task.taskId(), List.of()))).toList();

        int completed = 0;
        int failed = 0;
        int running = 0;
        int degraded = 0;
        int toolCalls = 0;
        int toolFailures = 0;
        int modelCompletions = 0;
        int modelFailures = 0;
        int fallbackEvents = 0;
        int modelVerified = 0;
        int legacy = 0;
        int deterministic = 0;
        int tasksWithDuration = 0;
        long durationTotal = 0;
        Map<String, Integer> failureTypes = new LinkedHashMap<>();
        Map<String, Integer> narrativeSources = new LinkedHashMap<>();

        for (ParsedTask task : parsed) {
            if ("COMPLETED".equals(task.task().status())) {
                completed++;
            }
            else if ("FAILED".equals(task.task().status())) {
                failed++;
                if (StringUtils.hasText(task.summary().failureType())) {
                    failureTypes.merge(task.summary().failureType(), 1, Integer::sum);
                }
            }
            else {
                running++;
            }
            if (task.summary().degraded()) {
                degraded++;
            }
            toolCalls += task.summary().toolCallCount();
            toolFailures += task.summary().toolFailureCount();
            modelCompletions += count(task.events(), SupplyChainEventType.MODEL_COMPLETED);
            modelFailures += count(task.events(), SupplyChainEventType.MODEL_FAILED);
            fallbackEvents += count(task.events(), SupplyChainEventType.FALLBACK_USED);
            narrativeSources.merge(task.summary().narrativeSource(), 1, Integer::sum);
            switch (task.summary().narrativeSource()) {
                case "MODEL_VERIFIED" -> modelVerified++;
                case "MODEL_LEGACY_UNVERIFIED" -> legacy++;
                case "DETERMINISTIC_FALLBACK" -> deterministic++;
                default -> {
                }
            }
            if (task.summary().durationMs() != null) {
                tasksWithDuration++;
                durationTotal += task.summary().durationMs();
            }
        }
        Double averageDuration = tasksWithDuration == 0 ? null
                : Math.round((double) durationTotal / tasksWithDuration * 10.0) / 10.0;
        return new ObservabilitySummary(Instant.now(), scope(tasks.size()), tasks.size(), parsed.size(), completed,
                failed, running, degraded, toolCalls, toolFailures, modelCompletions, modelFailures, fallbackEvents,
                modelVerified, legacy, deterministic, tasksWithDuration, averageDuration, failureTypes,
                narrativeSources, limitations());
    }

    public ObservabilityRunPage runs(int page, int pageSize, String status, String mode,
            String narrativeSource, Boolean fallback, String failureType, String query) {
        if (page < 1) {
            throw new IllegalArgumentException("page must be at least 1");
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("pageSize must be between 1 and " + MAX_PAGE_SIZE);
        }
        List<SupplyChainTaskRepository.ObservableTask> tasks = taskRepository.findObservableWindow(WINDOW_SIZE);
        Map<String, List<SupplyChainTaskEvent>> eventMap = eventsByTask(tasks);
        String normalizedStatus = normalize(status);
        String normalizedMode = normalize(mode);
        String normalizedSource = normalize(narrativeSource);
        String normalizedFailure = normalize(failureType);
        String normalizedQuery = normalize(query);
        String normalizedQueryLower = normalizedQuery == null ? null : normalizedQuery.toLowerCase(Locale.ROOT);

        List<ObservabilityRunSummary> filtered = tasks.stream()
                .map(task -> parse(task, eventMap.getOrDefault(task.taskId(), List.of())))
                .filter(task -> normalizedStatus == null || normalizedStatus.equals(task.task().status()))
                .filter(task -> normalizedMode == null || normalizedMode.equals(task.task().taskType()))
                .filter(task -> normalizedSource == null
                        || normalizedSource.equals(task.summary().narrativeSource()))
                .filter(task -> fallback == null || fallback == task.summary().fallbackUsed())
                .filter(task -> normalizedFailure == null || (task.summary().failureType() != null
                        && task.summary().failureType().toUpperCase(Locale.ROOT).contains(normalizedFailure)))
                .filter(task -> normalizedQueryLower == null
                        || task.task().taskId().toLowerCase(Locale.ROOT).contains(normalizedQueryLower)
                        || task.task().question().toLowerCase(Locale.ROOT).contains(normalizedQueryLower))
                .map(ParsedTask::summary)
                .sorted(Comparator.comparing(ObservabilityRunSummary::updatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        long total = filtered.size();
        long offset = (long) (page - 1) * pageSize;
        List<ObservabilityRunSummary> items = offset >= total ? List.of()
                : filtered.subList((int) offset, (int) Math.min(total, offset + pageSize));
        int totalPages = total == 0 ? 0 : (int) ((total + pageSize - 1) / pageSize);
        return new ObservabilityRunPage(items, page, pageSize, total, totalPages, tasks.size(), scope(tasks.size()));
    }

    public ObservabilityRunDetail detail(String taskId) {
        if (!StringUtils.hasText(taskId)) {
            throw new IllegalArgumentException("taskId is required");
        }
        SupplyChainTaskRepository.ObservableTask task = taskRepository.findObservableTask(taskId.trim())
                .orElseThrow(() -> new SupplyChainResourceNotFoundException("Unknown task: " + taskId));
        List<SupplyChainTaskEvent> events = eventRepository.findByTaskId(task.taskId());
        ParsedTask parsed = parse(task, events);
        List<ObservabilityRunDetail.ToolCall> tools = tools(events);
        List<ObservabilityRunDetail.ModelCall> modelCalls = modelCalls(events);
        List<ObservabilityRunDetail.Fallback> fallbacks = fallbacks(events);
        List<ObservabilityRunDetail.Diagnostic> diagnostics = diagnostics(parsed, tools, modelCalls, fallbacks);
        List<EvaluationTaskReference> evaluationLinks = evaluationRunRepository.findTaskReferences(task.taskId());
        Set<String> proposalIds = proposalRepository.findByTask(task.taskId())
                .stream()
                .map(proposal -> proposal.proposalId())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> planIds = new LinkedHashSet<>(planRepository.findIdsByTask(task.taskId()));
        if (parsed.decision() != null && parsed.decision().plan() != null) {
            planIds.add(parsed.decision().plan().planId());
        }
        ObservabilityRunDetail.Narrative narrative = new ObservabilityRunDetail.Narrative(
                parsed.summary().narrativeSource(), parsed.summary().modelAssisted(),
                parsed.summary().fallbackUsed(), RISK_AUTHORITY, false);
        return new ObservabilityRunDetail(parsed.summary(), narrative, parsed.reportFacts(),
                events.stream().map(this::timeline).toList(), tools, modelCalls, fallbacks, diagnostics,
                new ObservabilityRunDetail.Links(parsed.summary().reportAvailable(),
                        parsed.summary().decisionAvailable(), List.copyOf(proposalIds), List.copyOf(planIds),
                        evaluationLinks),
                "DETERMINISTIC_RULES", scope(taskRepository.findObservableWindow(WINDOW_SIZE).size()));
    }

    private ParsedTask parse(SupplyChainTaskRepository.ObservableTask task, List<SupplyChainTaskEvent> events) {
        RiskAnalysisReport report = null;
        DecisionTaskResult decision = null;
        if (SupplyChainTaskType.ANALYSIS.equals(task.taskType()) && task.reportJson() != null) {
            report = read(task.reportJson(), RiskAnalysisReport.class);
        }
        if (SupplyChainTaskType.DECISION.equals(task.taskType()) && task.decisionJson() != null) {
            decision = read(task.decisionJson(), DecisionTaskResult.class);
        }
        String source = report != null ? report.narrativeSource()
                : decision != null ? decision.narrativeSource() : UNKNOWN;
        source = normalizeSource(source);
        boolean modelAssisted = report != null ? report.modelAssisted()
                : decision != null && decision.modelAssisted();
        boolean fallbackEvent = events.stream().anyMatch(event -> SupplyChainEventType.FALLBACK_USED.equals(event.type()));
        boolean fallbackUsed = fallbackEvent || "DETERMINISTIC_FALLBACK".equals(source)
                || "MODEL_LEGACY_UNVERIFIED".equals(source);
        int toolCalls = (int) events.stream().filter(SupplyChainObservabilityService::isToolResult).count();
        int toolFailures = (int) events.stream().filter(event -> SupplyChainEventType.TOOL_FAILED.equals(event.type())).count();
        int modelFailures = count(events, SupplyChainEventType.MODEL_FAILED);
        Long duration = events.stream()
                .filter(event -> SupplyChainEventType.TASK_COMPLETED.equals(event.type())
                        || SupplyChainEventType.TASK_FAILED.equals(event.type()))
                .map(SupplyChainTaskEvent::durationMs)
                .filter(Objects::nonNull)
                .reduce((first, second) -> second)
                .orElse(null);
        String failureType = failureType(task, events);
        int atRisk = report != null ? report.atRiskOrders().size()
                : decision != null && decision.atRiskOrders() != null ? decision.atRiskOrders().size() : 0;
        int highRisk = report != null
                ? (int) report.atRiskOrders().stream().filter(item -> "HIGH".equals(item.riskLevel().name())).count()
                : decision != null && decision.atRiskOrders() != null
                        ? (int) decision.atRiskOrders().stream().filter(item -> "HIGH".equals(item.riskLevel().name())).count() : 0;
        int gaps = report != null
                ? (int) report.atRiskOrders().stream().flatMap(item -> item.dataGaps().stream()).distinct().count()
                : 0;
        ObservabilityRunSummary summary = new ObservabilityRunSummary(task.taskId(), task.question(),
                task.taskType(), task.status(), task.currentStep(), task.updatedAt(), source, modelAssisted,
                fallbackUsed, fallbackUsed, events.size(), toolCalls, toolFailures, modelFailures, duration,
                failureType, task.reportJson() != null, task.decisionJson() != null);
        return new ParsedTask(task, report, decision, summary,
                new ObservabilityRunDetail.ReportFacts(atRisk, highRisk, gaps, source), events);
    }

    private static String failureType(SupplyChainTaskRepository.ObservableTask task,
            List<SupplyChainTaskEvent> events) {
        if (!"FAILED".equals(task.status())) {
            return null;
        }
        return events.stream()
                .filter(event -> SupplyChainEventStatus.FAILED.equals(event.status()))
                .map(SupplyChainTaskEvent::errorType)
                .filter(StringUtils::hasText)
                .reduce((first, second) -> second)
                .orElse(StringUtils.hasText(task.currentStep()) ? task.currentStep() : "TASK_FAILED");
    }

    private List<ObservabilityRunDetail.ToolCall> tools(List<SupplyChainTaskEvent> events) {
        return events.stream().filter(SupplyChainObservabilityService::isToolResult)
                .map(event -> new ObservabilityRunDetail.ToolCall(event.stage(),
                        event.summary() != null && event.summary().startsWith("model-initiated")
                                ? "MODEL" : "WORKFLOW",
                        event.status(), event.durationMs(), event.summary(), event.errorType(),
                        List.of(event.eventId()), false))
                .toList();
    }

    private static boolean isToolResult(SupplyChainTaskEvent event) {
        return SupplyChainEventType.TOOL_COMPLETED.equals(event.type())
                || SupplyChainEventType.TOOL_FAILED.equals(event.type());
    }

    private List<ObservabilityRunDetail.ModelCall> modelCalls(List<SupplyChainTaskEvent> events) {
        return events.stream()
                .filter(event -> SupplyChainEventType.MODEL_COMPLETED.equals(event.type())
                        || SupplyChainEventType.MODEL_FAILED.equals(event.type()))
                .map(event -> new ObservabilityRunDetail.ModelCall(event.stage(), event.status(), event.durationMs(),
                        event.summary(), event.errorType(), null, null, null, false, List.of(event.eventId())))
                .toList();
    }

    private List<ObservabilityRunDetail.Fallback> fallbacks(List<SupplyChainTaskEvent> events) {
        return events.stream()
                .filter(event -> SupplyChainEventType.FALLBACK_USED.equals(event.type()))
                .map(event -> new ObservabilityRunDetail.Fallback(event.stage(),
                        reason(event.summary()), event.occurredAt(), event.eventId()))
                .toList();
    }

    private List<ObservabilityRunDetail.Diagnostic> diagnostics(ParsedTask task,
            List<ObservabilityRunDetail.ToolCall> tools, List<ObservabilityRunDetail.ModelCall> modelCalls,
            List<ObservabilityRunDetail.Fallback> fallbacks) {
        List<ObservabilityRunDetail.Diagnostic> diagnostics = new ArrayList<>();
        List<SupplyChainTaskEvent> failedEvents = task.events().stream()
                .filter(event -> SupplyChainEventStatus.FAILED.equals(event.status()))
                .toList();
        if ("INTERRUPTED_BY_RESTART".equals(task.task().currentStep())) {
            diagnostics.add(diagnostic("INTERRUPTED_BY_RESTART", "WARNING", "任务被重启中断",
                    "应用重启时任务仍在运行，后端将其标记为失败。", "重新提交任务；不要推断未执行阶段已经完成。",
                    List.of()));
        }
        else if (!failedEvents.isEmpty() && "FAILED".equals(task.task().status())) {
            SupplyChainTaskEvent last = failedEvents.stream()
                    .filter(event -> !SupplyChainEventType.TASK_FAILED.equals(event.type()))
                    .reduce((first, second) -> second)
                    .orElse(failedEvents.get(failedEvents.size() - 1));
            String code = switch (last.type()) {
                case SupplyChainEventType.MODEL_FAILED -> "MODEL_INVOCATION_FAILED";
                case SupplyChainEventType.TOOL_FAILED -> "TOOL_INVOCATION_FAILED";
                case SupplyChainEventType.NODE_FAILED -> "NODE_FAILED";
                case SupplyChainEventType.TASK_FAILED -> "TASK_FAILED";
                default -> "EXECUTION_FAILED";
            };
            String recommendation = switch (code) {
                case "MODEL_INVOCATION_FAILED" -> "检查模型配置、连接与超时；若已有确定性回退，规则报告仍可作为可信结果。";
                case "TOOL_INVOCATION_FAILED" -> "检查该工具依赖的数据源、参数与权限；工具失败不会修改真实业务数据。";
                case "NODE_FAILED" -> "查看该阶段输入与数据缺口，修复后重新执行任务。";
                default -> "查看失败事件和任务当前步骤；不要根据最终状态推断未执行的阶段。";
            };
            diagnostics.add(diagnostic(code, "ERROR", "任务在 " + last.stage() + " 阶段失败",
                    "事件 #" + last.sequence() + " · errorType=" + safe(last.errorType()) + " · "
                            + safe(last.summary()), recommendation, List.of(last.eventId())));
        }
        else if ("FAILED".equals(task.task().status())) {
            diagnostics.add(diagnostic("TASK_FAILED_WITHOUT_EVENT", "ERROR", "任务失败但缺少失败事件",
                    "当前步骤：" + safe(task.task().currentStep()) + "；不能推断具体异常原因。",
                    "检查任务记录和事件持久化；不要补写不存在的执行阶段。",
                    List.of()));
        }
        if (!"FAILED".equals(task.task().status())) {
            for (SupplyChainTaskEvent failure : task.events()) {
                if (SupplyChainEventType.MODEL_FAILED.equals(failure.type())) {
                    diagnostics.add(diagnostic("MODEL_INVOCATION_FAILED", "WARNING", "模型调用失败",
                            "阶段 " + safe(failure.stage()) + " · errorType=" + safe(failure.errorType())
                                    + " · duration=" + (failure.durationMs() == null ? "—" : failure.durationMs() + "ms"),
                            "检查模型配置、连接与超时；是否继续以确定性回退事件为准，风险规则结果仍可审计。",
                            List.of(failure.eventId())));
                }
            }
        }
        for (ObservabilityRunDetail.Fallback fallback : fallbacks) {
            diagnostics.add(diagnostic("DETERMINISTIC_FALLBACK", "INFO", "已使用确定性回退",
                    "阶段 " + safe(fallback.stage()) + "；原因 " + safe(fallback.reason()) + "。",
                    "这是受控降级；风险分数、等级和证据仍由 Java 规则提供。检查模型配置或校验失败原因。",
                    List.of(fallback.eventId())));
        }
        if ("MODEL_VERIFIED".equals(task.summary().narrativeSource())) {
            diagnostics.add(diagnostic("MODEL_VERIFIED", "INFO", "模型叙述已通过证据校验",
                    "模型只解释 Java 规则结果，风险分数与证据未被模型修改。", "无需处理；如需审计可查看关联报告与事件。",
                    List.of()));
        }
        else if ("MODEL_LEGACY_UNVERIFIED".equals(task.summary().narrativeSource())) {
            diagnostics.add(diagnostic("MODEL_LEGACY_UNVERIFIED", "WARNING", "历史模型叙述未验证",
                    "报告生成时没有结构化证据校验证据，不能直接认定模型叙述可信。",
                    "以报告中的 Java 风险分数与证据为准；建议重新运行当前结构化校验流程。", List.of()));
        }
        else if ("DETERMINISTIC_FALLBACK".equals(task.summary().narrativeSource()) && fallbacks.isEmpty()) {
            diagnostics.add(diagnostic("FALLBACK_WITHOUT_EVENT", "INFO", "报告标记为确定性回退",
                    "历史报告没有持久化对应的回退事件，无法定位具体模型失败阶段。",
                    "查看任务事件或重新执行；不要补写不存在的模型调用。", List.of()));
        }
        if (task.reportFacts() != null && task.reportFacts().dataGapCount() > 0) {
            diagnostics.add(diagnostic("DATA_GAP", "WARNING", "结果包含数据缺口",
                    "报告包含 " + task.reportFacts().dataGapCount() + " 条去重后的 dataGap；缺失值没有被推断为零。",
                    "补齐库存/供应商主数据后重新评估；在补齐前以报告中的 dataGaps 为准。", List.of()));
        }
        if (task.events().isEmpty()) {
            diagnostics.add(diagnostic("NO_EVENTS", "WARNING", "没有可用的执行事件",
                    "后端没有为该任务保存任何事件；不能判断阶段、工具或模型是否执行。",
                    "检查事件持久化是否可用；不要根据最终文字补造时间线。", List.of()));
        }
        else if (isActive(task.task().status())) {
            diagnostics.add(diagnostic("TASK_RUNNING", "INFO", "任务仍在执行",
                    "当前步骤：" + safe(task.task().currentStep()) + "；结果尚未最终化。",
                    "等待任务进入 COMPLETED 或 FAILED；当前时间线只包含已经发生的事件。", List.of()));
        }
        if (diagnostics.isEmpty() && "COMPLETED".equals(task.task().status())) {
            diagnostics.add(diagnostic("NO_DIAGNOSTIC_ISSUE", "INFO", "未发现异常",
                    "任务已完成，事件中没有失败或回退记录。", "无需处理。", List.of()));
        }
        return diagnostics;
    }

    private ObservabilityRunDetail.TimelineItem timeline(SupplyChainTaskEvent event) {
        return new ObservabilityRunDetail.TimelineItem(event.eventId(), event.sequence(), event.type(),
                event.stage(), event.status(), event.occurredAt(), event.durationMs(), event.summary(),
                event.errorType(), source(event.type()), true);
    }

    private static String source(String type) {
        if (type.startsWith("TOOL_")) {
            return "TOOL";
        }
        if (type.startsWith("MODEL_")) {
            return "MODEL";
        }
        if (SupplyChainEventType.FALLBACK_USED.equals(type)) {
            return "FALLBACK";
        }
        if (type.startsWith("TASK_") || type.startsWith("NODE_")) {
            return "WORKFLOW";
        }
        return "AGENT";
    }

    private static ObservabilityRunDetail.Diagnostic diagnostic(String code, String severity, String title,
            String detail, String recommendation, List<String> eventIds) {
        return new ObservabilityRunDetail.Diagnostic(code, severity, title, detail, recommendation,
                eventIds, true);
    }

    private Map<String, List<SupplyChainTaskEvent>> eventsByTask(
            List<SupplyChainTaskRepository.ObservableTask> tasks) {
        List<String> taskIds = tasks.stream().map(SupplyChainTaskRepository.ObservableTask::taskId).toList();
        return eventRepository.findByTaskIds(taskIds)
                .stream()
                .collect(Collectors.groupingBy(SupplyChainTaskEvent::taskId, LinkedHashMap::new,
                        Collectors.toList()));
    }

    private static int count(List<SupplyChainTaskEvent> events, String type) {
        return (int) events.stream().filter(event -> type.equals(event.type())).count();
    }

    private static String reason(String summary) {
        if (summary == null) {
            return UNKNOWN;
        }
        int index = summary.indexOf("reason=");
        return index < 0 ? summary : summary.substring(index + "reason=".length());
    }

    private static String safe(String value) {
        return StringUtils.hasText(value) ? value : "—";
    }

    private static boolean isActive(String status) {
        return "ACCEPTED".equals(status) || "RUNNING".equals(status);
    }

    private static String normalizeSource(String source) {
        if (!StringUtils.hasText(source)) {
            return UNKNOWN;
        }
        // Older decision records persisted the shorter label before the explicit fallback contract.
        return "DETERMINISTIC".equalsIgnoreCase(source) ? "DETERMINISTIC_FALLBACK" : source;
    }

    private static String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : null;
    }

    private static String scope(int size) {
        return SCOPE_PREFIX + "（最多 " + WINDOW_SIZE + " 条，实际 " + size + " 条；按更新时间倒序）";
    }

    private static List<String> limitations() {
        return List.of("统计窗口为最近 " + WINDOW_SIZE + " 条分析与决策任务，不代表全库历史或时间趋势。",
                "平均耗时只使用 TASK_COMPLETED/TASK_FAILED 事件中实际记录的 durationMs；没有样本时不显示平均值。",
                "现有事件不包含模型标识、Token 或费用；本接口不会猜测或伪造这些数据。",
                "工具事件只保留经过截断的执行摘要；历史事件没有单独保存工具输入，因此 inputRecorded=false。");
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        }
        catch (Exception exception) {
            return null;
        }
    }

    private record ParsedTask(SupplyChainTaskRepository.ObservableTask task, RiskAnalysisReport report,
            DecisionTaskResult decision, ObservabilityRunSummary summary,
            ObservabilityRunDetail.ReportFacts reportFacts, List<SupplyChainTaskEvent> events) {

        ParsedTask {
            events = events == null ? List.of() : List.copyOf(events);
        }
    }
}
