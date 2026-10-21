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
import java.time.LocalDate;
import java.util.List;

/**
 * Read-only baseline for the simulation lab. It exposes the exact snapshot the deterministic
 * engine will use, including missing-data indicators, but never exposes a write path.
 */
public record SimulationBaselineView(String baselineSource, LocalDate asOfDate, Instant capturedAt,
        SimulationMetrics metrics, List<OrderRiskAssessment> assessments, List<SupplyChainOrder> orders,
        List<InventoryPosition> inventory, List<SupplierRiskProfile> suppliers, List<String> dataGaps,
        String ruleVersion, String simulationStatus, String disclaimer) {

    public SimulationBaselineView {
        assessments = assessments == null ? List.of() : List.copyOf(assessments);
        orders = orders == null ? List.of() : List.copyOf(orders);
        inventory = inventory == null ? List.of() : List.copyOf(inventory);
        suppliers = suppliers == null ? List.of() : List.copyOf(suppliers);
        dataGaps = dataGaps == null ? List.of() : List.copyOf(dataGaps);
    }
}
