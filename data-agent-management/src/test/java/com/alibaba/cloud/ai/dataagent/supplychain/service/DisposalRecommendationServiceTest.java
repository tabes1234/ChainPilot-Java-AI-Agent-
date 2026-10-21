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
package com.alibaba.cloud.ai.dataagent.supplychain.service;

import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalActionType;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalPriority;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalRecommendation;
import com.alibaba.cloud.ai.dataagent.supplychain.model.OrderRiskAssessment;
import com.alibaba.cloud.ai.dataagent.supplychain.model.OrderStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskLevel;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainOrder;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.SupplyChainDemoData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisposalRecommendationServiceTest {

    private SupplyChainQueryService queryService;

    private DisposalRecommendationService recommendationService;

    @BeforeEach
    void setUp() {
        queryService = new SupplyChainQueryService(new SupplyChainDemoData());
        recommendationService = new DisposalRecommendationService(queryService);
    }

    @Test
    void derivesRulePriorityAndActionsFromLiveRiskEvidence() {
        List<DisposalRecommendation> recommendations = recommendationService.generate(null, null);

        assertEquals(5, recommendations.size());
        assertEquals(5, recommendations.stream().map(DisposalRecommendation::recommendationId).distinct().count());
        assertTrue(recommendations.stream()
            .map(DisposalRecommendation::priorityScore)
            .toList()
            .stream()
            .sorted((left, right) -> Integer.compare(right, left))
            .toList()
            .equals(recommendations.stream().map(DisposalRecommendation::priorityScore).toList()));

        DisposalRecommendation top = recommendations.get(0);
        assertEquals(DisposalActionType.FOLLOW_UP_SUPPLIER, top.actionType());
        assertEquals("SUP-ALPHA", top.supplierId());
        assertEquals(DisposalPriority.P1, top.priority());
        assertEquals(86, top.priorityScore());
        assertTrue(top.priorityBasis().contains("紧急度 40"));
        assertTrue(top.priorityBasis().contains("供应商风险 20"));
        assertTrue(top.priorityBasis().contains("最高风险 10"));

        DisposalRecommendation mcu = find(recommendations, DisposalActionType.REPLENISH_INVENTORY, "SKU-MCU-001");
        assertEquals(DisposalPriority.P1, mcu.priority());
        assertEquals(75, mcu.priorityScore());
        assertEquals(Set.of("ORD-1001"), Set.copyOf(mcu.affectedOrderIds()));
        assertTrue(mcu.title().contains("补货 100 件"));
        assertTrue(mcu.priorityBasis().contains("缺口 25（缺口 100 / 需求 120）"));
        assertTrue(mcu.businessBasis().stream().anyMatch(line -> line.contains("ATP = 现有 - 预留 - 安全库存")));
        assertTrue(mcu.expectedImprovement().contains("从 100 降至 0"));
        assertFalse(mcu.prerequisites().isEmpty());
        assertTrue(mcu.sideEffects().stream().anyMatch(line -> line.contains("未提供价格和成本字段")));
        assertTrue(mcu.sources().contains("INVENTORY:SKU-MCU-001"));
        assertTrue(mcu.assumptions().stream().anyMatch(line -> line.contains("未使用未提供的采购价格")));
        assertEquals("PENDING_CONFIRMATION", mcu.status());

        DisposalRecommendation sensor = find(recommendations, DisposalActionType.REPLENISH_INVENTORY,
                "SKU-SENSOR-002");
        assertEquals(DisposalPriority.P2, sensor.priority());
        assertEquals(61, sensor.priorityScore());

        DisposalRecommendation bravo = findSupplier(recommendations, "SUP-BRAVO");
        assertEquals(DisposalPriority.P2, bravo.priority());
        assertEquals(64, bravo.priorityScore());
    }

    @Test
    void everyGeneratedReferenceExistsInTheSourceData() {
        Set<String> knownSuppliers = queryService.findSuppliers()
            .stream()
            .map(supplier -> supplier.supplierId())
            .collect(Collectors.toSet());
        Set<String> knownSkus = queryService.findInventory(null)
            .stream()
            .map(position -> position.sku())
            .collect(Collectors.toSet());
        Set<String> knownOrders = queryService.findAllOrders()
            .stream()
            .map(order -> order.orderId())
            .collect(Collectors.toSet());

        for (DisposalRecommendation recommendation : recommendationService.generate(null, null)) {
            assertTrue(recommendation.supplierId() == null || knownSuppliers.contains(recommendation.supplierId()));
            assertTrue(recommendation.sku() == null || knownSkus.contains(recommendation.sku()));
            assertTrue(knownOrders.containsAll(recommendation.affectedOrderIds()));
            assertTrue(recommendation.sources().stream().allMatch(source -> source.startsWith("ORDER:")
                    || source.startsWith("INVENTORY:") || source.startsWith("SUPPLIER:")));
        }
    }

    @Test
    void filtersBySkuAndSupplierWithoutMixingScopes() {
        List<DisposalRecommendation> battery = recommendationService.generate("SKU-BATTERY-004", null);

        assertTrue(battery.stream().allMatch(recommendation -> recommendation.sku() == null
                || recommendation.sku().equals("SKU-BATTERY-004")));
        assertTrue(battery.stream().anyMatch(recommendation -> recommendation.affectedOrderIds()
            .contains("ORD-1004")));
        assertTrue(recommendationService.generate("SKU-UNKNOWN", null).isEmpty());
        assertTrue(recommendationService.generate(null, "SUP-GAMMA").isEmpty());
    }

    @Test
    void missingSnapshotDataProducesManualConfirmationInsteadOfInventedNumbers() {
        SupplyChainOrder order = new SupplyChainOrder("ORD-X", "SKU-X", 10, "SUP-X", LocalDate.now().plusDays(1),
                LocalDate.now().plusDays(2), OrderStatus.DELAYED);
        OrderRiskAssessment assessment = new OrderRiskAssessment(order, RiskLevel.HIGH, 80, 10,
                List.of("Order status is DELAYED"), List.of("Confirm recovery date"));

        List<DisposalRecommendation> recommendations = recommendationService.generate(List.of(assessment),
                List.of(), List.of(), LocalDate.now());

        assertEquals(1, recommendations.size());
        DisposalRecommendation manual = recommendations.get(0);
        assertEquals(DisposalActionType.MANUAL_CONFIRMATION, manual.actionType());
        assertEquals(DisposalPriority.P2, manual.priority());
        assertEquals(70, manual.priorityScore());
        assertEquals(Set.of("ORD-X"), Set.copyOf(manual.affectedOrderIds()));
        assertTrue(manual.prerequisites().stream().anyMatch(line -> line.contains("缺少 SKU SKU-X 的库存记录")));
        assertTrue(manual.prerequisites().stream().anyMatch(line -> line.contains("缺少供应商 SUP-X 的履约记录")));
        assertTrue(manual.expectedImprovement().contains("无法量化"));
        assertTrue(manual.action().contains("不基于缺失字段"));
    }

    private static DisposalRecommendation findSupplier(List<DisposalRecommendation> recommendations,
            String supplierId) {
        return recommendations.stream()
            .filter(recommendation -> recommendation.actionType() == DisposalActionType.FOLLOW_UP_SUPPLIER)
            .filter(recommendation -> supplierId.equals(recommendation.supplierId()))
            .findFirst()
            .orElseThrow();
    }

    private static DisposalRecommendation find(List<DisposalRecommendation> recommendations,
            DisposalActionType actionType, String sku) {
        return recommendations.stream()
            .filter(recommendation -> recommendation.actionType() == actionType)
            .filter(recommendation -> sku == null || sku.equals(recommendation.sku()))
            .findFirst()
            .orElseThrow();
    }

}
