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
 * One explainable disposal action. Priority, quantities and expected improvement are produced
 * by deterministic rules; the model may only explain them later.
 */
public record DisposalRecommendation(String recommendationId, DisposalActionType actionType,
        DisposalPriority priority, int priorityScore, String priorityBasis, RiskLevel riskLevel, String sku,
        String supplierId, List<String> affectedOrderIds, String title, String action, String rationale,
        List<String> businessBasis, String expectedImprovement, List<String> prerequisites,
        List<String> sideEffects, List<String> sources, List<String> assumptions, String status,
        Instant generatedAt) {

    public DisposalRecommendation {
        affectedOrderIds = List.copyOf(affectedOrderIds);
        businessBasis = List.copyOf(businessBasis);
        prerequisites = List.copyOf(prerequisites);
        sideEffects = List.copyOf(sideEffects);
        sources = List.copyOf(sources);
        assumptions = List.copyOf(assumptions);
    }
}
