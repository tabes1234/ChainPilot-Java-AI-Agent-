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

import java.time.Instant;
import java.util.List;

/**
 * Structured result of one natural-language decision task. All business metrics come from
 * deterministic Java services; {@code narrativeSource} states whether the explanation used a
 * model grounded on those results or a deterministic fallback.
 */
public record DecisionTaskResult(String taskId, String question, String intent, String status,
        String summary, String narrativeSource, boolean modelAssisted, boolean fallbackUsed,
        boolean clarificationRequired, List<String> clarifications, List<String> toolCalls,
        List<String> executionPlan, boolean plannerAssisted, List<OrderRiskAssessment> atRiskOrders,
        List<String> recommendationNotes, List<DisposalRecommendation> recommendations,
        DisposalSimulation simulation, DisposalScenarioComparison comparison, DisposalPlan plan,
        SandboxExecution execution, SandboxExecutionEligibility sandboxEligibility,
        List<DecisionProposal> proposals, List<KnowledgeCitation> citations,
        List<String> evidenceNotes, boolean realDataMutated, Instant createdAt) {

    public DecisionTaskResult {
        clarifications = clarifications == null ? List.of() : List.copyOf(clarifications);
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        executionPlan = executionPlan == null ? List.of() : List.copyOf(executionPlan);
        atRiskOrders = atRiskOrders == null ? List.of() : List.copyOf(atRiskOrders);
        recommendationNotes = recommendationNotes == null ? List.of() : List.copyOf(recommendationNotes);
        recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
        proposals = proposals == null ? List.of() : List.copyOf(proposals);
        citations = citations == null ? List.of() : List.copyOf(citations);
        evidenceNotes = evidenceNotes == null ? List.of() : List.copyOf(evidenceNotes);
    }
}
