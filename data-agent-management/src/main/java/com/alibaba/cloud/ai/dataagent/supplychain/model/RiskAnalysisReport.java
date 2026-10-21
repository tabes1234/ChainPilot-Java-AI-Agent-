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
package com.alibaba.cloud.ai.dataagent.supplychain.model;

import java.time.Instant;
import java.util.List;

public record RiskAnalysisReport(String taskId, String status, String summary,
        List<String> executionPlan, boolean plannerAssisted, List<OrderRiskAssessment> atRiskOrders,
        List<String> recommendations, List<String> toolCalls,
        List<KnowledgeCitation> citations,
        boolean modelAssisted, int validationAttempts, Instant createdAt,
        RiskNarrative modelNarrative, String narrativeSource) {

    public static final String NARRATIVE_MODEL_VERIFIED = "MODEL_VERIFIED";

    public static final String NARRATIVE_MODEL_LEGACY_UNVERIFIED = "MODEL_LEGACY_UNVERIFIED";

    public static final String NARRATIVE_DETERMINISTIC_FALLBACK = "DETERMINISTIC_FALLBACK";

    public RiskAnalysisReport {
        atRiskOrders = atRiskOrders == null ? List.of() : List.copyOf(atRiskOrders);
        executionPlan = executionPlan == null ? List.of() : List.copyOf(executionPlan);
        recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        citations = citations == null ? List.of() : List.copyOf(citations);
        if (narrativeSource == null) {
            narrativeSource = !modelAssisted ? NARRATIVE_DETERMINISTIC_FALLBACK
                    : modelNarrative == null ? NARRATIVE_MODEL_LEGACY_UNVERIFIED : NARRATIVE_MODEL_VERIFIED;
        }
    }

    /** Keeps persisted reports and existing callers compatible when no structured narrative exists. */
    public RiskAnalysisReport(String taskId, String status, String summary, List<String> executionPlan,
            boolean plannerAssisted, List<OrderRiskAssessment> atRiskOrders, List<String> recommendations,
            List<String> toolCalls, List<KnowledgeCitation> citations, boolean modelAssisted, int validationAttempts,
            Instant createdAt, RiskNarrative modelNarrative) {
        this(taskId, status, summary, executionPlan, plannerAssisted, atRiskOrders, recommendations, toolCalls,
                citations, modelAssisted, validationAttempts, createdAt, modelNarrative, null);
    }

    public RiskAnalysisReport(String taskId, String status, String summary, List<String> executionPlan,
            boolean plannerAssisted, List<OrderRiskAssessment> atRiskOrders, List<String> recommendations,
            List<String> toolCalls, List<KnowledgeCitation> citations, boolean modelAssisted, int validationAttempts,
            Instant createdAt) {
        this(taskId, status, summary, executionPlan, plannerAssisted, atRiskOrders, recommendations, toolCalls,
                citations, modelAssisted, validationAttempts, createdAt, null, null);
    }
}
