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
package com.alibaba.cloud.ai.dataagent.supplychain.repository;

import com.alibaba.cloud.ai.dataagent.supplychain.model.InventoryPosition;
import com.alibaba.cloud.ai.dataagent.supplychain.model.OrderStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskLevel;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainOrder;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplierRiskProfile;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public class SupplyChainDemoData {

    private final List<SupplyChainOrder> orders;

    private final List<InventoryPosition> inventory;

    private final List<SupplierRiskProfile> suppliers;

    public SupplyChainDemoData() {
        LocalDate today = LocalDate.now();
        orders = List.of(
                new SupplyChainOrder("ORD-1001", "SKU-MCU-001", 120, "SUP-ALPHA", today.plusDays(2),
                        today.plusDays(5), OrderStatus.IN_TRANSIT),
                new SupplyChainOrder("ORD-1002", "SKU-SENSOR-002", 80, "SUP-BRAVO", today.plusDays(4),
                        today.plusDays(6), OrderStatus.CONFIRMED),
                new SupplyChainOrder("ORD-1003", "SKU-VALVE-003", 30, "SUP-GAMMA", today.plusDays(8),
                        today.plusDays(8), OrderStatus.CONFIRMED),
                new SupplyChainOrder("ORD-1004", "SKU-BATTERY-004", 200, "SUP-ALPHA", today.plusDays(1),
                        today.plusDays(4), OrderStatus.DELAYED));
        inventory = List.of(
                new InventoryPosition("SKU-MCU-001", "Industrial control module", 55, 15, 20),
                new InventoryPosition("SKU-SENSOR-002", "Pressure sensor", 50, 15, 10),
                new InventoryPosition("SKU-VALVE-003", "Precision valve", 100, 10, 15),
                new InventoryPosition("SKU-BATTERY-004", "Lithium battery pack", 70, 25, 20));
        suppliers = List.of(
                new SupplierRiskProfile("SUP-ALPHA", "Alpha Components", RiskLevel.HIGH, 0.72, 0.041,
                        "Two late shipments reported this month"),
                new SupplierRiskProfile("SUP-BRAVO", "Bravo Sensors", RiskLevel.MEDIUM, 0.89, 0.018,
                        "Capacity utilization is above the agreed threshold"),
                new SupplierRiskProfile("SUP-GAMMA", "Gamma Precision", RiskLevel.LOW, 0.97, 0.006,
                        "No open incidents"));
    }

    public List<SupplyChainOrder> orders() {
        return orders;
    }

    public List<InventoryPosition> inventory() {
        return inventory;
    }

    public List<SupplierRiskProfile> suppliers() {
        return suppliers;
    }
}
