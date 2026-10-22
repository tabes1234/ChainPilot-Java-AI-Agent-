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

import com.alibaba.cloud.ai.dataagent.supplychain.model.DecisionIntent;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DecisionIntentType;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DecisionTaskResult;
import com.alibaba.cloud.ai.dataagent.supplychain.model.OrderRiskAssessment;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskAnalysisReport;
import com.alibaba.cloud.ai.dataagent.supplychain.model.InventoryPosition;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplierRiskProfile;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainEventStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainEventType;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskEvent;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskPage;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskState;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskSummary;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskType;
import com.alibaba.cloud.ai.dataagent.supplychain.model.WorkflowPlan;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.SupplyChainEventRepository;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.SupplyChainTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.action.EdgeAction;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;

@Slf4j
@Service
public class SupplyChainWorkflowService {

    private static final int MAX_VALIDATION_RETRIES = 1;
    private static final int MAX_RETAINED_TASKS = 1_000;
    private static final Duration TASK_RETENTION = Duration.ofHours(24);

    private final SupplyChainAgentTools agentTools;
    private final SupplyChainAnalysisService analysisService;
    private final SupplyChainDecisionService decisionService;
    private final ObjectMapper objectMapper;
    private final SupplyChainTaskRepository taskRepository;
    private final SupplyChainEventRepository eventRepository;
    private final SupplyChainEventRecorder eventRecorder;
    private final Executor analysisExecutor;
    private final Map<String, Future<?>> runningFutures = new ConcurrentHashMap<>();
    private final CompiledGraph workflow;
    private TransactionTemplate transactionTemplate;
    private TaskLeaseManager taskLeaseManager;

    public SupplyChainWorkflowService(SupplyChainAgentTools agentTools,
            SupplyChainAnalysisService analysisService, SupplyChainDecisionService decisionService,
            ObjectMapper objectMapper, SupplyChainTaskRepository taskRepository,
            SupplyChainEventRepository eventRepository, SupplyChainEventRecorder eventRecorder,
            @Qualifier("supplyChainAnalysisExecutor") Executor analysisExecutor)
            throws GraphStateException {
        this.agentTools = agentTools;
        this.analysisService = analysisService;
        this.decisionService = decisionService;
        this.objectMapper = objectMapper;
        this.taskRepository = taskRepository;
        this.eventRepository = eventRepository;
        this.eventRecorder = eventRecorder;
        this.analysisExecutor = analysisExecutor;
        TaskCancellationRegistry.installPersistentChecker(taskId -> taskRepository.findTask(taskId)
            .map(status -> !SupplyChainTaskState.ACCEPTED.equals(status.status())
                    && !SupplyChainTaskState.RUNNING.equals(status.status()))
            .orElse(false));
        KeyStrategyFactory keyStrategies = () -> {
            Map<String, KeyStrategy> strategies = new HashMap<>();
            for (String key : List.of("taskId", "question", "taskType", "plan", "orders", "inventory", "suppliers",
                "retryCount", "isValid", "plannerAssisted", "report", "decisionIntent", "decisionAnalysis",
                "decision")) {
                strategies.put(key, KeyStrategy.REPLACE);
            }
            return strategies;
        };
        StateGraph graph = new StateGraph("ChainPilot supply-chain risk workflow", keyStrategies)
                .addNode("plan", node("PLANNING", state -> {
                    String taskId = state.value("taskId", "");
                    updateTask(taskId, SupplyChainTaskState.RUNNING, "PLANNING", state.value("retryCount", 0), null);
                    WorkflowPlan plan = analysisService.createPlan(state.value("question", ""));
                    return new NodeResult(Map.of("plan", plan.steps(), "plannerAssisted", plan.modelAssisted()),
                            "steps=" + plan.steps().size() + ", plannerAssisted=" + plan.modelAssisted());
                }))
                .addNode("orders", node("QUERYING_ORDERS", state -> {
                    updateTask(state.value("taskId", ""), SupplyChainTaskState.RUNNING, "QUERYING_ORDERS",
                            state.value("retryCount", 0), null);
                    List<OrderRiskAssessment> orders = agentTools.findAtRiskOrdersForWorkflow();
                    return new NodeResult(Map.of("orders", orders), "orders=" + orders.size());
                }))
                .addNode("inventory", node("QUERYING_INVENTORY", state -> {
                    updateTask(state.value("taskId", ""), SupplyChainTaskState.RUNNING, "QUERYING_INVENTORY",
                            state.value("retryCount", 0), null);
                    List<InventoryPosition> inventory = agentTools.findInventoryForWorkflow(null);
                    return new NodeResult(Map.of("inventory", inventory), "inventory=" + inventory.size());
                }))
                .addNode("suppliers", node("QUERYING_SUPPLIERS", state -> {
                    updateTask(state.value("taskId", ""), SupplyChainTaskState.RUNNING, "QUERYING_SUPPLIERS",
                            state.value("retryCount", 0), null);
                    List<SupplierRiskProfile> suppliers = agentTools.findSuppliersForWorkflow();
                    return new NodeResult(Map.of("suppliers", suppliers), "suppliers=" + suppliers.size());
                }))
                .addNode("validate", node("VALIDATING", state -> {
                    List<OrderRiskAssessment> orders = stateList(state, "orders", OrderRiskAssessment.class);
                    List<InventoryPosition> inventory = stateList(state, "inventory", InventoryPosition.class);
                    List<SupplierRiskProfile> suppliers = stateList(state, "suppliers", SupplierRiskProfile.class);
                    boolean valid = hasConsistentEvidence(orders, inventory, suppliers);
                    int retries = state.value("retryCount", 0);
                    updateTask(state.value("taskId", ""), SupplyChainTaskState.RUNNING, "VALIDATING", retries, null);
                    return new NodeResult(Map.of("isValid", valid), "valid=" + valid + ", retryCount=" + retries);
                }))
                .addNode("retry", node("RETRYING_DATA_CHECK", state -> {
                    int retryCount = state.value("retryCount", 0) + 1;
                    updateTask(state.value("taskId", ""), SupplyChainTaskState.RUNNING, "RETRYING_DATA_CHECK", retryCount, null);
                    List<OrderRiskAssessment> orders = agentTools.findAtRiskOrdersForWorkflow();
                    List<InventoryPosition> inventory = agentTools.findInventoryForWorkflow(null);
                    List<SupplierRiskProfile> suppliers = agentTools.findSuppliersForWorkflow();
                    return new NodeResult(Map.of("retryCount", retryCount, "orders", orders,
                            "inventory", inventory, "suppliers", suppliers),
                            "retryCount=" + retryCount + ", orders=" + orders.size());
                }))
                .addNode("report", node("GENERATING_REPORT", state -> {
                    updateTask(state.value("taskId", ""), SupplyChainTaskState.RUNNING, "GENERATING_REPORT",
                            state.value("retryCount", 0), null);
                    RiskAnalysisReport report = analysisService.analyze(state.value("question", ""),
                            state.value("taskId", ""), stateList(state, "orders", OrderRiskAssessment.class),
                            stateList(state, "inventory", InventoryPosition.class),
                            stateList(state, "suppliers", SupplierRiskProfile.class),
                            state.value("retryCount", 0), new WorkflowPlan(state.value("plan", List.of()),
                                    state.value("plannerAssisted", false)));
                    return new NodeResult(Map.of("report", objectMapper.writeValueAsString(report)),
                            "modelAssisted=" + report.modelAssisted() + ", citations=" + report.citations().size());
                }))
                .addNode("failed", node("VALIDATION_FAILED", state -> {
                    String taskId = state.value("taskId", "");
                    updateTask(taskId, SupplyChainTaskState.FAILED, "VALIDATION_FAILED", state.value("retryCount", 0),
                            "Risk evidence did not pass validation after one retry");
                    return new NodeResult(Map.of(), "validation failed after retry");
                }))
                .addNode("classify", node("DECISION_INTENT", state -> {
                    String taskId = state.value("taskId", "");
                    updateTask(taskId, SupplyChainTaskState.RUNNING, "DECISION_INTENT", 0, null);
                    DecisionIntent intent = decisionService.classify(taskId, state.value("question", ""));
                    boolean analysisPath = intent.type() == DecisionIntentType.ANALYSIS
                            && !intent.needsClarification();
                    return new NodeResult(Map.of("decisionIntent", intent, "decisionAnalysis", analysisPath),
                            "intent=" + intent.type() + ", analysisPath=" + analysisPath);
                }))
                .addNode("decide", node("DECISION_EXECUTION", state -> {
                    String taskId = state.value("taskId", "");
                    updateTask(taskId, SupplyChainTaskState.RUNNING, "DECISION_EXECUTION", 0, null);
                    DecisionIntent intent = state.value("decisionIntent")
                        .filter(DecisionIntent.class::isInstance)
                        .map(DecisionIntent.class::cast)
                        .orElseThrow(() -> new IllegalStateException("Decision intent is missing from graph state"));
                    DecisionTaskResult decision = decisionService.decide(taskId, state.value("question", ""), intent);
                    return new NodeResult(Map.of("decision", objectMapper.writeValueAsString(decision)),
                            "intent=" + decision.intent() + ", narrativeSource=" + decision.narrativeSource()
                                    + ", proposals=" + decision.proposals().size());
                }))
                .addNode("decisionCheck", node("VALIDATING_DECISION", state -> {
                    String taskId = state.value("taskId", "");
                    updateTask(taskId, SupplyChainTaskState.RUNNING, "VALIDATING_DECISION", 0, null);
                    String payload = state.value("decision", "");
                    if (payload.isBlank()) {
                        throw new IllegalStateException("Decision execution produced no result");
                    }
                    DecisionTaskResult decision;
                    try {
                        decision = objectMapper.readValue(payload, DecisionTaskResult.class);
                    }
                    catch (IOException exception) {
                        throw new IllegalStateException("Unable to restore the decision result", exception);
                    }
                    if (decision.realDataMutated()
                            || (decision.comparison() != null && decision.comparison().realDataMutated())
                            || (decision.simulation() != null && decision.simulation().realDataMutated())
                            || (decision.execution() != null && decision.execution().realDataMutated())) {
                        throw new IllegalStateException("Decision result claims to have mutated real business data");
                    }
                    if (decision.comparison() != null
                            && (decision.comparison().candidates().size() < 2
                                    || decision.comparison().candidates().size() > 5)) {
                        throw new IllegalStateException("Decision comparison returned an invalid candidate count");
                    }
                    return new NodeResult(Map.of("decision", payload), "decision result validated; no real data mutated");
                }));
        EdgeAction validationRoute = state -> {
            if (state.value("isValid", false)) {
                return "report";
            }
            return state.value("retryCount", 0) < MAX_VALIDATION_RETRIES ? "retry" : "failed";
        };
        EdgeAction taskTypeRoute = state -> SupplyChainTaskType.DECISION.equals(
                state.value("taskType", SupplyChainTaskType.ANALYSIS)) ? "classify" : "plan";
        EdgeAction decisionRoute = state -> state.value("decisionAnalysis", false) ? "plan" : "decide";
        graph.addConditionalEdges(START, edge_async(taskTypeRoute), Map.of("classify", "classify", "plan", "plan"))
                .addConditionalEdges("classify", edge_async(decisionRoute), Map.of("plan", "plan", "decide", "decide"))
                .addEdge("plan", "orders")
                .addEdge("orders", "inventory")
                .addEdge("inventory", "suppliers")
                .addEdge("suppliers", "validate")
                .addConditionalEdges("validate", edge_async(validationRoute),
                        Map.of("report", "report", "retry", "retry", "failed", "failed"))
                .addEdge("retry", "validate")
                .addEdge("report", END)
                .addEdge("decide", "decisionCheck")
                .addEdge("decisionCheck", END)
                .addEdge("failed", END);
        this.workflow = graph.compile(CompileConfig.builder().build());
    }

    @Autowired(required = false)
    public void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Autowired(required = false)
    public void setTaskLeaseManager(TaskLeaseManager taskLeaseManager) {
        this.taskLeaseManager = taskLeaseManager;
    }

    public RiskAnalysisReport analyze(String question) {
        validateQuestion(question);
        String taskId = UUID.randomUUID().toString();
        createTask(taskId, question, SupplyChainTaskType.ANALYSIS);
        execute(taskId, question, SupplyChainTaskType.ANALYSIS);
        return report(taskId);
    }

    public DecisionTaskResult decide(String question) {
        validateQuestion(question);
        String taskId = UUID.randomUUID().toString();
        pruneTasks();
        createTask(taskId, question, SupplyChainTaskType.DECISION);
        execute(taskId, question, SupplyChainTaskType.DECISION);
        return decision(taskId);
    }

    public SupplyChainTaskStatus submit(String question) {
        return submit(question, SupplyChainTaskType.ANALYSIS);
    }

    public SupplyChainTaskStatus submit(String question, String mode) {
        validateQuestion(question);
        String taskType = SupplyChainTaskType.normalize(mode);
        String taskId = UUID.randomUUID().toString();
        pruneTasks();
        createTask(taskId, question, taskType);
        TaskCancellationRegistry.begin(taskId);
        long queueStarted = System.nanoTime();
        Runnable runnable = () -> {
            try {
                execute(taskId, question, taskType);
            }
            catch (RuntimeException exception) {
                log.warn("ChainPilot asynchronous task failed. taskId={} type={}", taskId, taskType, exception);
            }
            finally {
                runningFutures.remove(taskId);
                TaskCancellationRegistry.end(taskId);
            }
        };
        try {
            if (analysisExecutor instanceof ThreadPoolTaskExecutor executor) {
                runningFutures.put(taskId, executor.submit(runnable));
            }
            else {
                analysisExecutor.execute(runnable);
            }
        }
        catch (RuntimeException exception) {
            TaskCancellationRegistry.end(taskId);
            updateTask(taskId, SupplyChainTaskState.FAILED, "QUEUE_REJECTED", 0, "Analysis queue is full; retry later");
            eventRecorder.taskFailed(taskId, "QUEUE", queueStarted, exception);
            throw exception;
        }
        return task(taskId);
    }

    /**
     * Cancels an active task. The database transition is the source of truth; the in-memory
     * signal and Future interrupt only stop subsequent checkpoints. In-flight provider calls
     * that cannot be aborted are allowed to return, but their result is never persisted.
     */
    public SupplyChainTaskStatus cancel(String taskId) {
        SupplyChainTaskStatus current = task(taskId);
        if (SupplyChainTaskState.CANCELLED.equals(current.status())
                || SupplyChainTaskState.COMPLETED.equals(current.status())
                || SupplyChainTaskState.FAILED.equals(current.status())) {
            return current;
        }
        CancelOutcome outcome = persistCancellation(taskId);
        if (!outcome.changed()) {
            return outcome.status();
        }
        TaskCancellationRegistry.request(taskId);
        Future<?> future = runningFutures.remove(taskId);
        if (future != null) {
            future.cancel(true);
        }
        return outcome.status();
    }

    private CancelOutcome persistCancellation(String taskId) {
        Supplier<CancelOutcome> action = () -> {
            SupplyChainTaskStatus current = taskRepository.findTask(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown ChainPilot task: " + taskId));
            if (SupplyChainTaskState.CANCELLED.equals(current.status())
                    || SupplyChainTaskState.COMPLETED.equals(current.status())
                    || SupplyChainTaskState.FAILED.equals(current.status())) {
                return new CancelOutcome(current, false);
            }
            boolean changed = taskRepository.cancelIfActive(taskId);
            if (changed) {
                eventRepository.append(taskId, SupplyChainEventType.TASK_CANCELLED, "WORKFLOW",
                        SupplyChainEventStatus.CANCELLED, Instant.now(), null, "task cancelled by user", null);
            }
            return new CancelOutcome(taskRepository.findTask(taskId).orElseThrow(), changed);
        };
        if (transactionTemplate != null) {
            return transactionTemplate.execute(status -> action.get());
        }
        return action.get();
    }

    private void execute(String taskId, String question, String taskType) {
        TaskLease lease = taskLeaseManager == null ? null : taskLeaseManager.claim(taskId);
        if (taskLeaseManager != null && lease == null) {
            log.info("ChainPilot task was not claimed by this instance. taskId={}", taskId);
            return;
        }
        try {
            executeClaimed(taskId, question, taskType);
        }
        finally {
            if (lease != null) {
                taskLeaseManager.release(taskId);
            }
        }
    }

    private void executeClaimed(String taskId, String question, String taskType) {
        SupplyChainTaskStatus initial = taskRepository.findTask(taskId).orElse(null);
        if (initial == null || SupplyChainTaskState.CANCELLED.equals(initial.status())
                || TaskCancellationRegistry.isCancelled(taskId)) {
            return;
        }
        long started = eventRecorder.taskStarted(taskId);
        try {
            OverAllState result = workflow.invoke(Map.of("taskId", taskId, "question", question,
                    "taskType", taskType, "retryCount", 0),
                    RunnableConfig.builder().threadId(taskId).build()).orElseThrow();
            ensureActive(taskId);
            if (SupplyChainTaskType.DECISION.equals(taskType)) {
                DecisionTaskResult decision = restoreDecision(taskId, question, result);
                ensureActive(taskId);
                TaskLease lease = currentLease(taskId);
                boolean completed = lease == null
                        ? taskRepository.completeDecision(taskId, decision, "DONE", 0)
                        : taskRepository.completeDecision(taskId, decision, "DONE", 0, lease.ownerId(),
                                lease.fenceVersion());
                if (!completed) {
                    return;
                }
                eventRecorder.taskCompleted(taskId, started, "decision intent=" + decision.intent()
                        + ", narrativeSource=" + decision.narrativeSource() + ", proposals="
                        + decision.proposals().size());
                return;
            }
            Optional<String> report = result.value("report");
            if (report.isEmpty()) {
                updateTask(taskId, SupplyChainTaskState.FAILED, "NO_REPORT", task(taskId).validationAttempts(),
                        "Workflow completed without a validated report");
                throw new IllegalStateException("ChainPilot workflow did not produce a report");
            }
            try {
                RiskAnalysisReport restored = objectMapper.readValue(report.get(), RiskAnalysisReport.class);
                ensureActive(taskId);
                TaskLease lease = currentLease(taskId);
                boolean completed = lease == null
                        ? taskRepository.completeAnalysis(taskId, restored, "DONE", task(taskId).validationAttempts())
                        : taskRepository.completeAnalysis(taskId, restored, "DONE",
                                task(taskId).validationAttempts(), lease.ownerId(), lease.fenceVersion());
                if (!completed) {
                    return;
                }
                eventRecorder.taskCompleted(taskId, started, "report generated; modelAssisted="
                        + restored.modelAssisted() + ", plannerAssisted=" + restored.plannerAssisted());
            }
            catch (IOException exception) {
                throw new IllegalStateException("Unable to restore the validated ChainPilot report", exception);
            }
        }
        catch (RuntimeException exception) {
            if (exception instanceof SupplyChainTaskCancelledException || isCancelled(taskId)) {
                log.info("ChainPilot task stopped at a cancellation checkpoint. taskId={}", taskId);
                return;
            }
            SupplyChainTaskStatus current = task(taskId);
            if (!SupplyChainTaskState.FAILED.equals(current.status())
                    && !SupplyChainTaskState.COMPLETED.equals(current.status())) {
                log.warn("ChainPilot task failed. taskId={} step={} causeType={}", taskId,
                        current.currentStep(), exception.getClass().getSimpleName());
                updateTask(taskId, SupplyChainTaskState.FAILED, current.currentStep(), current.validationAttempts(),
                        "Analysis failed; check application logs using the task ID");
                current = task(taskId);
            }
            if (SupplyChainTaskState.FAILED.equals(current.status())) {
                eventRecorder.taskFailed(taskId, current.currentStep(), started, exception);
            }
            throw exception;
        }
    }

    private record CancelOutcome(SupplyChainTaskStatus status, boolean changed) {
    }

    private void ensureActive(String taskId) {
        TaskCancellationRegistry.check(taskId);
        if (taskLeaseManager != null && taskLeaseManager.isLost(taskId)) {
            throw new SupplyChainTaskCancelledException(taskId);
        }
        SupplyChainTaskStatus status = taskRepository.findTask(taskId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown ChainPilot task: " + taskId));
        if (SupplyChainTaskState.CANCELLED.equals(status.status())
                || SupplyChainTaskState.FAILED.equals(status.status())
                || SupplyChainTaskState.COMPLETED.equals(status.status())) {
            throw new SupplyChainTaskCancelledException(taskId);
        }
    }

    private TaskLease currentLease(String taskId) {
        return taskLeaseManager == null ? null : taskLeaseManager.current(taskId);
    }

    private boolean isCancelled(String taskId) {
        if (TaskCancellationRegistry.isRequested(taskId)) {
            return true;
        }
        return taskRepository.findTask(taskId)
            .map(status -> !SupplyChainTaskState.ACCEPTED.equals(status.status())
                    && !SupplyChainTaskState.RUNNING.equals(status.status()))
            .orElse(false);
    }

    private DecisionTaskResult restoreDecision(String taskId, String question, OverAllState result) {
        Optional<String> payload = result.value("decision");
        if (payload.isPresent()) {
            try {
                return objectMapper.readValue(payload.get(), DecisionTaskResult.class);
            }
            catch (IOException exception) {
                throw new IllegalStateException("Unable to restore the ChainPilot decision result", exception);
            }
        }
        Optional<String> report = result.value("report");
        if (report.isEmpty()) {
            updateTask(taskId, SupplyChainTaskState.FAILED, "NO_DECISION_RESULT", 0,
                    "Workflow completed without a decision result");
            throw new IllegalStateException("ChainPilot decision workflow did not produce a result");
        }
        try {
            RiskAnalysisReport analysisReport = objectMapper.readValue(report.get(), RiskAnalysisReport.class);
            return decisionService.fromAnalysisResult(taskId, question, analysisReport);
        }
        catch (IOException exception) {
            throw new IllegalStateException("Unable to restore the analysis report for a decision task", exception);
        }
    }

    public SupplyChainTaskStatus task(String taskId) {
        pruneTasks();
        return taskRepository.findTask(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown ChainPilot task: " + taskId));
    }

    public List<SupplyChainTaskSummary> tasks(int limit) {
        return tasks(null, 1, limit).items();
    }

    public List<SupplyChainTaskSummary> tasks(String mode, int limit) {
        return tasks(mode, null, 1, limit).items();
    }

    public SupplyChainTaskPage tasks(String status, int page, int pageSize) {
        return tasks(null, status, page, pageSize);
    }

    /**
     * History pages are ordered by {@code updated_at DESC, task_id DESC}. A page beyond the last
     * one returns an empty item list while still reporting the total element count.
     */
    public SupplyChainTaskPage tasks(String mode, String status, int page, int pageSize) {
        pruneTasks();
        String taskType = SupplyChainTaskType.normalize(mode);
        long totalElements = taskRepository.countTasks(taskType, status);
        long offset = (long) (page - 1) * pageSize;
        List<SupplyChainTaskSummary> items = taskRepository.findHistory(taskType, status, pageSize, offset);
        int totalPages = totalElements == 0 ? 0 : (int) ((totalElements + pageSize - 1) / pageSize);
        return new SupplyChainTaskPage(items, page, pageSize, totalElements, totalPages);
    }

    public RiskAnalysisReport report(String taskId) {
        task(taskId);
        if (SupplyChainTaskType.DECISION.equals(taskRepository.findTaskType(taskId)
                .orElse(SupplyChainTaskType.ANALYSIS))) {
            throw new IllegalStateException("Task was created in decision mode; read /decision instead: " + taskId);
        }
        RiskAnalysisReport report = taskRepository.findReport(taskId).orElse(null);
        if (report == null) {
            throw new IllegalStateException("ChainPilot task has not completed: " + taskId);
        }
        return report;
    }

    public DecisionTaskResult decision(String taskId) {
        task(taskId);
        if (!SupplyChainTaskType.DECISION.equals(taskRepository.findTaskType(taskId)
                .orElse(SupplyChainTaskType.ANALYSIS))) {
            throw new IllegalStateException("Task was created in analysis mode; read /report instead: " + taskId);
        }
        return taskRepository.findDecision(taskId)
                .orElseThrow(() -> new IllegalStateException("ChainPilot decision task has not completed: " + taskId));
    }

    /** Returns only this task's events, ordered by the persisted per-task sequence. */
    public List<SupplyChainTaskEvent> events(String taskId) {
        task(taskId);
        return eventRepository.findByTaskId(taskId);
    }

    /**
     * A risk order needs either a matching snapshot or an explicit deterministic data gap.
     * Missing inventory/suppliers must not fail the whole analysis or be silently scored as zero.
     */
    static boolean hasConsistentEvidence(List<OrderRiskAssessment> orders, List<InventoryPosition> inventory,
            List<SupplierRiskProfile> suppliers) {
        return orders.stream().allMatch(item -> item != null && item.riskScore() >= 0 && item.riskScore() <= 100
                && !item.riskFactors().isEmpty()
                && (inventory.stream().anyMatch(stock -> stock.sku().equalsIgnoreCase(item.order().sku()))
                        || hasExplicitGap(item, "INVENTORY_MISSING", "库存快照缺失"))
                && (suppliers.stream()
                    .anyMatch(supplier -> supplier.supplierId().equalsIgnoreCase(item.order().supplierId()))
                        || hasExplicitGap(item, "SUPPLIER_MISSING", "供应商画像缺失")));
    }

    private static boolean hasExplicitGap(OrderRiskAssessment item, String evidenceCode, String gapPrefix) {
        return item.evidence()
            .stream()
            .anyMatch(evidence -> evidenceCode.equals(evidence.code()) && !evidence.available())
                && item.dataGaps().stream().anyMatch(gap -> gap.startsWith(gapPrefix));
    }

    private synchronized void pruneTasks() {
        taskRepository.prune(TASK_RETENTION, MAX_RETAINED_TASKS);
    }

    private void createTask(String taskId, String question, String taskType) {
        taskRepository.create(
                new SupplyChainTaskStatus(taskId, SupplyChainTaskState.ACCEPTED, "QUEUED", 0, null, Instant.now()),
                question, taskType);
        eventRecorder.taskQueued(taskId);
    }

    private void updateTask(String taskId, String status, String step, int attempts, String error) {
        if (taskId != null && !taskId.isBlank()) {
            TaskLease lease = currentLease(taskId);
            if (lease == null) {
                taskRepository.update(new SupplyChainTaskStatus(taskId, status, step, attempts, error, Instant.now()));
            }
            else if (!taskRepository.updateOwned(
                    new SupplyChainTaskStatus(taskId, status, step, attempts, error, Instant.now()),
                    lease.ownerId(), lease.fenceVersion())) {
                taskLeaseManager.markLost(taskId, "task state update rejected by owner/fence/state");
                throw new SupplyChainTaskCancelledException(taskId);
            }
            log.info("ChainPilot task {} status={} step={} validationAttempts={}", taskId, status, step, attempts);
        }
    }

    private AsyncNodeAction node(String stage, InstrumentedNode body) {
        return AsyncNodeAction.node_async(state -> {
            String taskId = state.value("taskId", "");
            try (SupplyChainEventRecorder.TaskScope ignored = eventRecorder.openTask(taskId)) {
                TaskCancellationRegistry.check(taskId);
                long started = eventRecorder.nodeStarted(taskId, stage);
                try {
                    NodeResult result = body.run(state);
                    TaskCancellationRegistry.check(taskId);
                    eventRecorder.nodeCompleted(taskId, stage, started, result.summary());
                    return result.output();
                }
                catch (Exception exception) {
                    if (exception instanceof SupplyChainTaskCancelledException) {
                        throw exception;
                    }
                    eventRecorder.nodeFailed(taskId, stage, started, exception);
                    throw exception;
                }
            }
        });
    }

    private record NodeResult(Map<String, Object> output, String summary) {
    }

    @FunctionalInterface
    private interface InstrumentedNode {

        NodeResult run(OverAllState state) throws Exception;
    }

    private void validateQuestion(String question) {
        if (question == null || question.isBlank() || question.length() > 2_000) {
            throw new IllegalArgumentException("Question must contain 1 to 2000 characters");
        }
    }

    private <T> List<T> stateList(OverAllState state, String key, Class<T> elementType) throws IOException {
        Object value = state.value(key, List.of());
        return objectMapper.readValue(objectMapper.writeValueAsBytes(value),
                objectMapper.getTypeFactory().constructCollectionType(List.class, elementType));
    }
}
