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
package com.alibaba.cloud.ai.dataagent.supplychain;

import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskLevel;
import com.alibaba.cloud.ai.dataagent.supplychain.model.OrderStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.InventoryPosition;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplierRiskProfile;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainOrder;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.SupplyChainDemoData;
import com.alibaba.cloud.ai.dataagent.supplychain.service.SupplyChainQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;

class SupplyChainQueryServiceTest {

    private SupplyChainQueryService queryService;

    @BeforeEach
    void setUp() {
        queryService = new SupplyChainQueryService(new SupplyChainDemoData());
    }

    @Test
    void reportsHighRiskOrdersWithInventoryAndSupplierEvidence() {
        var atRiskOrders = queryService.findAtRiskOrders();

        assertEquals(3, atRiskOrders.size());
        assertTrue(atRiskOrders.stream().allMatch(order -> order.riskLevel() != RiskLevel.LOW));
        var firstOrder = queryService.findOrders(null, RiskLevel.HIGH).get(0);
        assertEquals("ORD-1001", firstOrder.order().orderId());
        assertTrue(firstOrder.inventoryShortage() > 0);
        assertTrue(firstOrder.riskFactors().stream().anyMatch(factor -> factor.contains("Supplier risk")));
        assertFalse(firstOrder.recommendedActions().isEmpty());
    }

    @Test
    void filtersInventoryBySkuAndFindsSupplierRisk() {
        assertEquals(1, queryService.findInventory("SKU-MCU-001").size());
        assertEquals(0, queryService.findInventory("unknown-sku").size());
        assertEquals(RiskLevel.HIGH, queryService.findSupplier("SUP-ALPHA").orElseThrow().riskLevel());
    }

    @Test
    void inventoryApiRepresentationIncludesAvailableToPromise() throws Exception {
        var inventory = queryService.findInventory("SKU-MCU-001").get(0);
        ObjectMapper objectMapper = new ObjectMapper();

        assertEquals(20, inventory.availableToPromise());
        var json = objectMapper.valueToTree(inventory);
        assertEquals(20, json.get("availableToPromise").asInt());
        assertEquals(inventory, objectMapper.treeToValue(json, inventory.getClass()));
    }

    @Test
    void delayedStatusWithoutDateDifferenceDoesNotClaimLateDeliveryDays() {
        LocalDate deliveryDate = LocalDate.now().plusDays(2);
        SupplyChainDemoData demoData = new SupplyChainDemoData() {
            @Override
            public List<SupplyChainOrder> orders() {
                return List.of(new SupplyChainOrder("ORD-DELAYED", "SKU-1", 1, "SUP-1", deliveryDate,
                        deliveryDate, OrderStatus.DELAYED));
            }

            @Override
            public List<InventoryPosition> inventory() {
                return List.of(new InventoryPosition("SKU-1", "Test item", 10, 0, 0));
            }

            @Override
            public List<SupplierRiskProfile> suppliers() {
                return List.of(new SupplierRiskProfile("SUP-1", "Test supplier", RiskLevel.LOW, 0.99, 0.01,
                        "No incident"));
            }
        };

        var assessment = new SupplyChainQueryService(demoData).findOrders(null, null).get(0);

        assertEquals(30, assessment.riskScore());
        assertTrue(assessment.riskFactors().contains("Order status is DELAYED; confirm the supplier recovery date"));
        assertFalse(assessment.riskFactors().stream().anyMatch(factor -> factor.contains("after the requested date")));
    }
}
