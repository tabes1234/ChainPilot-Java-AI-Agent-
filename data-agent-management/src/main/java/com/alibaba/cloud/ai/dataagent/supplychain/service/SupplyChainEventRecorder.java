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

import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainEventStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainEventType;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.SupplyChainEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Best-effort execution trail. Event persistence failures are logged and never propagated,
 * because observability must not change the outcome of an analysis task. Callers keep the
 * existing task/report behavior unchanged.
 */
@Slf4j
@Component
public class SupplyChainEventRecorder {

    private final SupplyChainEventRepository repository;

    private final ThreadLocal<String> taskContext = new ThreadLocal<>();

    public SupplyChainEventRecorder(SupplyChainEventRepository repository) {
        this.repository = repository;
    }

    /**
     * Makes the task ID available to tools and the model boundary executed on the current
     * workflow thread.
     */
    public TaskScope openTask(String taskId) {
        String previous = taskContext.get();
        taskContext.set(taskId);
        return () -> {
            if (previous == null) {
                taskContext.remove();
            }
            else {
                taskContext.set(previous);
            }
        };
    }

    public String currentTaskId() {
        return taskContext.get();
    }

    public void taskQueued(String taskId) {
        append(taskId, SupplyChainEventType.TASK_QUEUED, "QUEUE", SupplyChainEventStatus.QUEUED, null,
                "task accepted", null);
    }

    public long taskStarted(String taskId) {
        append(taskId, SupplyChainEventType.TASK_STARTED, "WORKFLOW", SupplyChainEventStatus.STARTED, null,
                "workflow execution started", null);
        return System.nanoTime();
    }

    public void taskCompleted(String taskId, long startedNanos, String summary) {
        append(taskId, SupplyChainEventType.TASK_COMPLETED, "WORKFLOW", SupplyChainEventStatus.COMPLETED,
                elapsedMillis(startedNanos), summary, null);
    }

    public void taskFailed(String taskId, String stage, long startedNanos, Throwable failure) {
        append(taskId, SupplyChainEventType.TASK_FAILED, stage, SupplyChainEventStatus.FAILED,
                elapsedMillis(startedNanos), "task execution failed", errorType(failure));
    }

    public void taskCancelled(String taskId, String stage) {
        append(taskId, SupplyChainEventType.TASK_CANCELLED, stage, SupplyChainEventStatus.CANCELLED, null,
                "task cancelled by user", null);
    }

    public long nodeStarted(String taskId, String stage) {
        append(taskId, SupplyChainEventType.NODE_STARTED, stage, SupplyChainEventStatus.STARTED, null,
                "node execution started", null);
        return System.nanoTime();
    }

    public void nodeCompleted(String taskId, String stage, long startedNanos, String summary) {
        append(taskId, SupplyChainEventType.NODE_COMPLETED, stage, SupplyChainEventStatus.COMPLETED,
                elapsedMillis(startedNanos), summary, null);
    }

    public void nodeFailed(String taskId, String stage, long startedNanos, Throwable failure) {
        append(taskId, SupplyChainEventType.NODE_FAILED, stage, SupplyChainEventStatus.FAILED,
                elapsedMillis(startedNanos), "node execution failed", errorType(failure));
    }

    public long toolStarted(String taskId, String tool) {
        return toolStarted(taskId, tool, ToolSource.WORKFLOW);
    }

    public long toolStarted(String taskId, String tool, ToolSource source) {
        append(taskId, SupplyChainEventType.TOOL_STARTED, tool, SupplyChainEventStatus.STARTED, null,
                sourceLabel(source) + " tool execution started", null);
        return System.nanoTime();
    }

    public void toolCompleted(String taskId, String tool, long startedNanos, String summary) {
        toolCompleted(taskId, tool, startedNanos, summary, ToolSource.WORKFLOW);
    }

    public void toolCompleted(String taskId, String tool, long startedNanos, String summary, ToolSource source) {
        append(taskId, SupplyChainEventType.TOOL_COMPLETED, tool, SupplyChainEventStatus.COMPLETED,
                elapsedMillis(startedNanos), sourceLabel(source) + " tool; " + summary, null);
    }

    public void toolFailed(String taskId, String tool, long startedNanos, Throwable failure) {
        toolFailed(taskId, tool, startedNanos, failure, ToolSource.WORKFLOW);
    }

    public void toolFailed(String taskId, String tool, long startedNanos, Throwable failure, ToolSource source) {
        append(taskId, SupplyChainEventType.TOOL_FAILED, tool, SupplyChainEventStatus.FAILED,
                elapsedMillis(startedNanos), sourceLabel(source) + " tool execution failed", errorType(failure));
    }

    public void modelStarted(String taskId, String stage) {
        append(taskId, SupplyChainEventType.MODEL_STARTED, stage, SupplyChainEventStatus.STARTED, null,
                "model invocation started", null);
    }

    public void modelCompleted(String taskId, String stage, long startedNanos, String summary) {
        append(taskId, SupplyChainEventType.MODEL_COMPLETED, stage, SupplyChainEventStatus.COMPLETED,
                elapsedMillis(startedNanos), summary, null);
    }

    public void modelFailed(String taskId, String stage, long startedNanos, Throwable failure) {
        append(taskId, SupplyChainEventType.MODEL_FAILED, stage, SupplyChainEventStatus.FAILED,
                elapsedMillis(startedNanos), "model invocation failed", errorType(failure));
    }

    public void fallbackUsed(String taskId, String stage, String reason) {
        append(taskId, SupplyChainEventType.FALLBACK_USED, stage, SupplyChainEventStatus.FALLBACK, null,
                "deterministic fallback selected; reason=" + reason, null);
    }

    public void intentRecognized(String taskId, String intent, String summary) {
        append(taskId, SupplyChainEventType.INTENT_RECOGNIZED, "DECISION_INTENT", SupplyChainEventStatus.COMPLETED,
                null, "intent=" + intent + "; " + summary, null);
    }

    public void clarificationRequired(String taskId, String intent, String message) {
        append(taskId, SupplyChainEventType.CLARIFICATION_REQUIRED, "DECISION_INTENT",
                SupplyChainEventStatus.FALLBACK, null, "intent=" + intent + "; clarification=" + message, null);
    }

    public void proposalCreated(String taskId, String proposalId, String actionType, String targetId) {
        append(taskId, SupplyChainEventType.PROPOSAL_CREATED, "PROPOSAL", SupplyChainEventStatus.COMPLETED, null,
                "proposal=" + proposalId + ", action=" + actionType + ", target=" + targetId + ", status=PENDING",
                null);
    }

    public void proposalConfirmed(String taskId, String proposalId, String actionType, String resultSummary) {
        append(taskId, SupplyChainEventType.PROPOSAL_CONFIRMED, "PROPOSAL", SupplyChainEventStatus.COMPLETED, null,
                "proposal=" + proposalId + ", action=" + actionType + "; " + resultSummary, null);
    }

    public void proposalCancelled(String taskId, String proposalId) {
        append(taskId, SupplyChainEventType.PROPOSAL_CANCELLED, "PROPOSAL", SupplyChainEventStatus.COMPLETED, null,
                "proposal=" + proposalId + " cancelled by user", null);
    }

    public void proposalFailed(String taskId, String proposalId, Throwable failure) {
        append(taskId, SupplyChainEventType.PROPOSAL_FAILED, "PROPOSAL", SupplyChainEventStatus.FAILED, null,
                "proposal=" + proposalId + " confirmation failed", errorType(failure));
    }

    private void append(String taskId, String type, String stage, String status, Long durationMs,
            String summary, String errorType) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        try {
            repository.append(taskId, type, stage, status, Instant.now(), durationMs, summary, errorType);
        }
        catch (RuntimeException exception) {
            log.warn("ChainPilot event persistence failed; analysis continues. taskId={} type={} stage={} "
                            + "causeType={} message={}", taskId, type, stage, exception.getClass().getSimpleName(),
                    exception.getMessage(), exception);
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
    }

    private static String errorType(Throwable failure) {
        return failure == null ? "UnknownFailure" : failure.getClass().getSimpleName();
    }

    private static String sourceLabel(ToolSource source) {
        return source == ToolSource.MODEL ? "model-initiated" : "workflow";
    }

    public enum ToolSource {
        MODEL, WORKFLOW
    }

    @FunctionalInterface
    public interface TaskScope extends AutoCloseable {

        @Override
        void close();
    }
}
