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
 * Immutable business-data snapshot shared by every candidate in one comparison. Simulating
 * all candidates against the same orders, inventory and suppliers makes their deltas
 * directly comparable.
 */
public record SimulationBaseline(List<SupplyChainOrder> orders, List<InventoryPosition> inventory,
        List<SupplierRiskProfile> suppliers, LocalDate asOfDate, Instant capturedAt) {

    public SimulationBaseline {
        orders = List.copyOf(orders);
        inventory = List.copyOf(inventory);
        suppliers = List.copyOf(suppliers);
    }
}
