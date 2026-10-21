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
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainOrder;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplierRiskProfile;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.SupplyChainDemoData;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class SupplyChainQueryService {

    private final SupplyChainDemoData data;

    public SupplyChainQueryService(SupplyChainDemoData data) {
        this.data = data;
    }

    public List<OrderRiskAssessment> findOrders(OrderStatus status, RiskLevel riskLevel) {
        return data.orders().stream()
                .filter(order -> status == null || order.status() == status)
                .map(this::assess)
                .filter(assessment -> riskLevel == null || assessment.riskLevel() == riskLevel)
                .toList();
    }

    public List<OrderRiskAssessment> findAtRiskOrders() {
        return findOrders(null, null).stream()
                .filter(assessment -> assessment.riskLevel() != RiskLevel.LOW)
                .toList();
    }

    public List<InventoryPosition> findInventory(String sku) {
        return data.inventory().stream()
                .filter(item -> sku == null || item.sku().equalsIgnoreCase(sku))
                .toList();
    }

    public List<SupplierRiskProfile> findSuppliers() {
        return data.suppliers();
    }

    /** Raw orders are exposed for deterministic what-if copies; the demo list stays immutable. */
    public List<SupplyChainOrder> findAllOrders() {
        return data.orders();
    }

    public Optional<SupplierRiskProfile> findSupplier(String supplierId) {
        return data.suppliers().stream()
                .filter(supplier -> supplier.supplierId().equalsIgnoreCase(supplierId))
                .findFirst();
    }

    public OrderRiskAssessment assess(SupplyChainOrder order) {
        return assess(order, data.inventory(), data.suppliers(), LocalDate.now());
    }

    /**
     * The same risk rule applied to an explicit snapshot. Simulations pass copied orders and
     * inventory here, so no real demo collection is ever mutated.
     */
    public OrderRiskAssessment assess(SupplyChainOrder order, List<InventoryPosition> inventory,
            List<SupplierRiskProfile> suppliers, LocalDate asOf) {
        InventoryPosition position = inventory.stream()
                .filter(item -> item.sku().equalsIgnoreCase(order.sku()))
                .findFirst()
                .orElse(null);
        SupplierRiskProfile supplier = suppliers.stream()
                .filter(item -> item.supplierId().equalsIgnoreCase(order.supplierId()))
                .findFirst()
                .orElse(null);
        boolean inventoryAvailable = position != null;
        int shortage = position == null ? 0
                : Math.max(0, order.quantity() - position.availableToPromise());
        long lateDays = Math.max(0,
                ChronoUnit.DAYS.between(order.requestedDeliveryDate(), order.estimatedDeliveryDate()));
        int score = 0;
        ArrayList<String> factors = new ArrayList<>();
        ArrayList<String> actions = new ArrayList<>();
        ArrayList<RiskEvidence> evidence = new ArrayList<>();
        ArrayList<String> dataGaps = new ArrayList<>();

        if (order.status() == OrderStatus.DELIVERED) {
            return new OrderRiskAssessment(order, RiskLevel.LOW, 0, 0,
                    List.of("Order is delivered"), List.of("No action required"),
                    List.of(new RiskEvidence("ORDER_DELIVERED", "订单已送达", "status=DELIVERED",
                            "status=DELIVERED", "已送达订单不再计算未履约风险", 0, "order.status", true)),
                    List.of());
        }

        int delayScore = 0;
        if (lateDays > 0 || order.status() == OrderStatus.DELAYED) {
            delayScore = order.status() == OrderStatus.DELAYED ? 30
                    : Math.max(30, 20 + (int) Math.min(20, lateDays * 5));
            score += delayScore;
            String observed = lateDays > 0
                    ? "lateDays=" + lateDays + "; status=" + order.status() + "; requested="
                            + order.requestedDeliveryDate() + "; estimated=" + order.estimatedDeliveryDate()
                    : "lateDays=0; status=DELAYED; requested=" + order.requestedDeliveryDate() + "; estimated="
                            + order.estimatedDeliveryDate();
            factors.add(lateDays > 0
                    ? "Estimated delivery is " + lateDays + " day(s) after the requested date"
                    : "Order status is DELAYED; confirm the supplier recovery date");
            actions.add("Confirm a recovery date with the supplier and notify the customer");
            evidence.add(new RiskEvidence("DELAY", "交付延期", observed,
                    "estimatedDeliveryDate <= requestedDeliveryDate 且 status != DELAYED",
                    lateDays > 0 ? "延期天数按 min(20, lateDays * 5) 计分，最低 30 分"
                            : "DELAYED 状态固定计 30 分",
                    delayScore, "order.status / order.requestedDeliveryDate / order.estimatedDeliveryDate", true));
        }
        else {
            evidence.add(new RiskEvidence("DELAY_OK", "交付时间", "lateDays=0; status=" + order.status(),
                    "无延期", "预计交付不晚于要求日期，计 0 分", 0,
                    "order.status / order.requestedDeliveryDate / order.estimatedDeliveryDate", true));
        }

        if (inventoryAvailable) {
            int inventoryScore = shortage > 0
                    ? Math.min(40, 30 + (int) Math.ceil(10.0 * shortage / Math.max(1, order.quantity())))
                    : 0;
            score += inventoryScore;
            if (shortage > 0) {
                factors.add("Projected available inventory is short by " + shortage + " unit(s)");
                actions.add("Check alternate stock locations or approve a replenishment transfer");
            }
            evidence.add(new RiskEvidence(shortage > 0 ? "INVENTORY_SHORTAGE" : "INVENTORY_OK", "库存可承诺量",
                    "onHand=" + position.onHandQuantity() + "; reserved=" + position.reservedQuantity()
                            + "; safetyStock=" + position.safetyStock() + "; availableToPromise="
                            + position.availableToPromise() + "; requested=" + order.quantity() + "; shortage="
                            + shortage,
                    "availableToPromise >= requested",
                    "ATP = max(0, onHand - reserved - safetyStock); shortage = max(0, requested - ATP); 缺口按 30–40 分计",
                    inventoryScore, "inventory.onHandQuantity / reservedQuantity / safetyStock", true));
        }
        else {
            dataGaps.add("库存快照缺失：未找到 SKU " + order.sku() + " 的库存记录，库存缺口未计入评分");
            evidence.add(new RiskEvidence("INVENTORY_MISSING", "库存快照缺失",
                    "未找到 SKU " + order.sku() + " 的库存记录", "需要 ATP 与缺口数据",
                    "库存缺失时不得推断缺口，计 0 分并标记数据缺口", 0, "inventory", false));
        }

        if (supplier != null) {
            int supplierScore = supplier.riskLevel() == RiskLevel.HIGH ? 40
                    : supplier.riskLevel() == RiskLevel.MEDIUM ? 30 : 0;
            score += supplierScore;
            if (supplierScore > 0) {
                factors.add("Supplier risk is " + supplier.riskLevel() + " (on-time rate "
                        + Math.round(supplier.onTimeDeliveryRate() * 100) + "%)");
                actions.add(supplier.riskLevel() == RiskLevel.HIGH
                        ? "Escalate with the supplier and evaluate an approved backup source"
                        : "Request a capacity confirmation from the supplier");
            }
            evidence.add(new RiskEvidence(supplierScore > 0 ? "SUPPLIER_RISK" : "SUPPLIER_OK", "供应商风险",
                    "riskLevel=" + supplier.riskLevel() + "; onTimeRate="
                            + Math.round(supplier.onTimeDeliveryRate() * 100) + "%; defectRate="
                            + Math.round(supplier.defectRate() * 100) + "%; latestIncident="
                            + (supplier.latestIncident() == null ? "—" : supplier.latestIncident()),
                    "riskLevel=LOW", "LOW 计 0 分；MEDIUM 计 30 分；HIGH 计 40 分", supplierScore,
                    "supplier.riskLevel / onTimeDeliveryRate / defectRate", true));
        }
        else {
            dataGaps.add("供应商画像缺失：未找到供应商 " + order.supplierId() + "，供应商风险未计入评分");
            evidence.add(new RiskEvidence("SUPPLIER_MISSING", "供应商画像缺失",
                    "未找到供应商 " + order.supplierId(), "需要供应商风险等级与交付表现",
                    "供应商缺失时不得推断供应商风险，计 0 分并标记数据缺口", 0, "supplier", false));
        }

        if (order.estimatedDeliveryDate().isBefore(asOf)) {
            score += 10;
            factors.add("Estimated delivery date has passed");
            evidence.add(new RiskEvidence("OVERDUE", "预计交付已过期",
                    "estimated=" + order.estimatedDeliveryDate() + "; asOf=" + asOf, "estimated >= asOf",
                    "预计交付早于评估日期时额外计 10 分", 10,
                    "order.estimatedDeliveryDate / assessment.asOfDate", true));
        }
        else {
            evidence.add(new RiskEvidence("OVERDUE_OK", "交付日期未过期",
                    "estimated=" + order.estimatedDeliveryDate() + "; asOf=" + asOf, "estimated >= asOf",
                    "未过期，计 0 分", 0, "order.estimatedDeliveryDate / assessment.asOfDate", true));
        }

        if (factors.isEmpty()) {
            factors.add("No delay, inventory, or supplier risk indicator found");
            actions.add("Continue routine monitoring");
            evidence.add(new RiskEvidence("NO_RISK", "未触发风险规则", "所有已执行检查均未命中",
                    "无风险信号", "延期、库存、供应商和过期检查均通过", 0, "deterministic assessment", true));
        }
        int finalScore = Math.min(100, score);
        RiskLevel riskLevel = finalScore >= 60 ? RiskLevel.HIGH
                : finalScore >= 30 ? RiskLevel.MEDIUM : RiskLevel.LOW;
        return new OrderRiskAssessment(order, riskLevel, finalScore, shortage, factors, actions,
                List.copyOf(evidence), List.copyOf(dataGaps));
    }
}
