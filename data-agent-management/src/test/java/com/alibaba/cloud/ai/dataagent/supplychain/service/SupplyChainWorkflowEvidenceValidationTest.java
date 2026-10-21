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
package com.alibaba.cloud.ai.dataagent.supplychain.service;

import com.alibaba.cloud.ai.dataagent.supplychain.model.InventoryPosition;
import com.alibaba.cloud.ai.dataagent.supplychain.model.OrderRiskAssessment;
import com.alibaba.cloud.ai.dataagent.supplychain.model.OrderStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskEvidence;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskLevel;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplierRiskProfile;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainOrder;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupplyChainWorkflowEvidenceValidationTest {

    @Test
    void acceptsOrdersWithMatchingInventoryAndSupplierSnapshots() {
        assertTrue(SupplyChainWorkflowService.hasConsistentEvidence(List.of(assessment(30, List.of(delay()), List.of())),
                List.of(inventory()), List.of(supplier())));
    }

    @Test
    void acceptsMissingInventoryWhenTheAssessmentCarriesAnExplicitGap() {
        RiskEvidence missing = new RiskEvidence("INVENTORY_MISSING", "库存快照缺失", "未找到 SKU SKU-1", "需要 ATP",
                "缺失时计 0 分并标记数据缺口", 0, "inventory", false);

        assertTrue(SupplyChainWorkflowService.hasConsistentEvidence(
                List.of(assessment(30, List.of(delay(), missing), List.of("库存快照缺失：未找到 SKU SKU-1 的库存记录"))),
                List.of(), List.of(supplier())));
    }

    @Test
    void acceptsMissingSupplierWhenTheAssessmentCarriesAnExplicitGap() {
        RiskEvidence missing = new RiskEvidence("SUPPLIER_MISSING", "供应商画像缺失", "未找到供应商 SUP-1", "需要供应商风险等级",
                "缺失时计 0 分并标记数据缺口", 0, "supplier", false);

        assertTrue(SupplyChainWorkflowService.hasConsistentEvidence(
                List.of(assessment(30, List.of(delay(), missing), List.of("供应商画像缺失：未找到供应商 SUP-1"))),
                List.of(inventory()), List.of()));
    }

    @Test
    void rejectsMissingSnapshotThatWasNotDeclaredAsADataGap() {
        assertFalse(SupplyChainWorkflowService.hasConsistentEvidence(
                List.of(assessment(30, List.of(delay()), List.of())), List.of(), List.of(supplier())));
        assertFalse(SupplyChainWorkflowService.hasConsistentEvidence(
                List.of(assessment(30, List.of(delay()), List.of())), List.of(inventory()), List.of()));
    }

    @Test
    void rejectsScoresOutsideTheDeterministicRange() {
        assertFalse(SupplyChainWorkflowService.hasConsistentEvidence(List.of(assessment(101, List.of(delay()), List.of())),
                List.of(inventory()), List.of(supplier())));
    }

    private static RiskEvidence delay() {
        return new RiskEvidence("DELAY", "交付延期", "lateDays=2", "estimated <= requested", "延期计 30 分", 30,
                "order dates", true);
    }

    private static InventoryPosition inventory() {
        return new InventoryPosition("SKU-1", "Test product", 10, 2, 1);
    }

    private static SupplierRiskProfile supplier() {
        return new SupplierRiskProfile("SUP-1", "Test supplier", RiskLevel.LOW, 0.99, 0.001, "No incidents");
    }

    private static OrderRiskAssessment assessment(int score, List<RiskEvidence> evidence, List<String> gaps) {
        LocalDate today = LocalDate.now();
        SupplyChainOrder order = new SupplyChainOrder("ORD-1", "SKU-1", 10, "SUP-1", today.plusDays(1),
                today.plusDays(3), OrderStatus.IN_TRANSIT);
        return new OrderRiskAssessment(order, RiskLevel.HIGH, score, 0, List.of("Estimated delivery is late"),
                List.of("Confirm a recovery date"), evidence, gaps);
    }
}
