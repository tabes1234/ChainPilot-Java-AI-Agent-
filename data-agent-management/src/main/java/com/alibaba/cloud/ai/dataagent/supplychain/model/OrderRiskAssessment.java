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

import java.util.List;

public record OrderRiskAssessment(SupplyChainOrder order, RiskLevel riskLevel, int riskScore,
        int inventoryShortage, List<String> riskFactors, List<String> recommendedActions,
        List<RiskEvidence> evidence, List<String> dataGaps) {

    public OrderRiskAssessment {
        riskFactors = riskFactors == null ? List.of() : List.copyOf(riskFactors);
        recommendedActions = recommendedActions == null ? List.of() : List.copyOf(recommendedActions);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        dataGaps = dataGaps == null ? List.of() : List.copyOf(dataGaps);
    }

    /** Keeps older construction sites and persisted reports readable. */
    public OrderRiskAssessment(SupplyChainOrder order, RiskLevel riskLevel, int riskScore, int inventoryShortage,
            List<String> riskFactors, List<String> recommendedActions) {
        this(order, riskLevel, riskScore, inventoryShortage, riskFactors, recommendedActions, List.of(), List.of());
    }
}
