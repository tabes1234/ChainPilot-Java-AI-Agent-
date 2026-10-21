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
package com.alibaba.cloud.ai.dataagent.supplychain.model;

import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationTaskReference;

import java.time.Instant;
import java.util.List;

/**
 * Detailed execution evidence assembled from persisted task events. The UI cannot invent a
 * stage: timeline entries exist only when the backend emitted a real event.
 */
public record ObservabilityRunDetail(ObservabilityRunSummary run, Narrative narrative,
        ReportFacts report, List<TimelineItem> timeline, List<ToolCall> tools,
        List<ModelCall> modelCalls, List<Fallback> fallbacks, List<Diagnostic> diagnostics,
        Links links, String diagnosticMode, String scope) {

    public ObservabilityRunDetail {
        timeline = timeline == null ? List.of() : List.copyOf(timeline);
        tools = tools == null ? List.of() : List.copyOf(tools);
        modelCalls = modelCalls == null ? List.of() : List.copyOf(modelCalls);
        fallbacks = fallbacks == null ? List.of() : List.copyOf(fallbacks);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public record Narrative(String source, boolean modelAssisted, boolean fallbackUsed,
            String riskAuthority, boolean modelCanChangeRisk) {
    }

    public record ReportFacts(int atRiskOrders, int highRiskOrders, int dataGapCount, String source) {
    }

    public record TimelineItem(String eventId, int sequence, String type, String stage, String status,
            Instant occurredAt, Long durationMs, String summary, String errorType, String source,
            boolean executed) {
    }

    public record ToolCall(String name, String initiatedBy, String status, Long durationMs, String summary,
            String errorType, List<String> eventIds, boolean inputRecorded) {

        public ToolCall {
            eventIds = eventIds == null ? List.of() : List.copyOf(eventIds);
        }
    }

    public record ModelCall(String stage, String status, Long durationMs, String summary, String errorType,
            String modelIdentity, Integer promptTokens, Integer completionTokens, boolean usageAvailable,
            List<String> eventIds) {

        public ModelCall {
            eventIds = eventIds == null ? List.of() : List.copyOf(eventIds);
        }
    }

    public record Fallback(String stage, String reason, Instant occurredAt, String eventId) {
    }

    public record Diagnostic(String code, String severity, String title, String detail,
            String recommendation, List<String> eventIds, boolean deterministicRule) {

        public Diagnostic {
            eventIds = eventIds == null ? List.of() : List.copyOf(eventIds);
        }
    }

    public record Links(boolean reportAvailable, boolean decisionAvailable, List<String> proposalIds,
            List<String> planIds, List<EvaluationTaskReference> evaluationRuns) {

        public Links {
            proposalIds = proposalIds == null ? List.of() : List.copyOf(proposalIds);
            planIds = planIds == null ? List.of() : List.copyOf(planIds);
            evaluationRuns = evaluationRuns == null ? List.of() : List.copyOf(evaluationRuns);
        }
    }
}
