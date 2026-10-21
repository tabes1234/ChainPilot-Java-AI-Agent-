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

import java.time.LocalDate;
import java.util.List;

/**
 * Baseline versus simulated state for one order. {@code changes} carries human-readable
 * evidence such as {@code inventoryShortage 100 -> 0}.
 */
public record OrderRiskImpact(String orderId, String sku, String supplierId,
        LocalDate baselineEstimatedDeliveryDate, LocalDate simulatedEstimatedDeliveryDate,
        RiskLevel baselineRiskLevel, RiskLevel simulatedRiskLevel, int baselineRiskScore,
        int simulatedRiskScore, int baselineInventoryShortage, int simulatedInventoryShortage,
        List<String> changes, List<String> evidenceChanges, List<String> remainingDataGaps) {

    public OrderRiskImpact {
        changes = changes == null ? List.of() : List.copyOf(changes);
        evidenceChanges = evidenceChanges == null ? List.of() : List.copyOf(evidenceChanges);
        remainingDataGaps = remainingDataGaps == null ? List.of() : List.copyOf(remainingDataGaps);
    }
}
