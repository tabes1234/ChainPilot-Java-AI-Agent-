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
import com.alibaba.cloud.ai.dataagent.supplychain.model.InventoryPosition;
import com.alibaba.cloud.ai.dataagent.supplychain.model.OrderRiskAssessment;
import com.alibaba.cloud.ai.dataagent.supplychain.model.OrderStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskLevel;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplierRiskProfile;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainOrder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Generates disposal actions from actual order, inventory and supplier fields. Priority is a
 * transparent rule score; the model never chooses or computes it.
 */
@Service
public class DisposalRecommendationService {

    private static final String PENDING_CONFIRMATION = "PENDING_CONFIRMATION";

    private final SupplyChainQueryService queryService;

    public DisposalRecommendationService(SupplyChainQueryService queryService) {
        this.queryService = queryService;
    }

    public List<DisposalRecommendation> generate(String sku, String supplierId) {
        LocalDate asOf = LocalDate.now();
        List<OrderRiskAssessment> assessments = queryService.findOrders(null, null)
                .stream()
                .filter(assessment -> assessment.riskLevel() != RiskLevel.LOW)
                .filter(assessment -> !StringUtils.hasText(sku)
                        || assessment.order().sku().equalsIgnoreCase(sku))
                .filter(assessment -> !StringUtils.hasText(supplierId)
                        || assessment.order().supplierId().equalsIgnoreCase(supplierId))
                .toList();
        return generate(assessments, queryService.findInventory(null), queryService.findSuppliers(), asOf);
    }

    /** Pure generator used by both the live API and the persisted task snapshot. */
    public List<DisposalRecommendation> generate(List<OrderRiskAssessment> assessments,
            List<InventoryPosition> inventory, List<SupplierRiskProfile> suppliers, LocalDate asOf) {
        List<OrderRiskAssessment> atRisk = assessments.stream()
                .filter(assessment -> assessment.riskLevel() != RiskLevel.LOW)
                .toList();
        List<DisposalRecommendation> recommendations = new ArrayList<>();

        Map<String, InventoryPosition> inventoryBySku = inventory.stream()
                .collect(Collectors.toMap(InventoryPosition::sku, Function.identity(), (left, right) -> left,
                        LinkedHashMap::new));
        Map<String, SupplierRiskProfile> supplierById = suppliers.stream()
                .collect(Collectors.toMap(SupplierRiskProfile::supplierId, Function.identity(),
                        (left, right) -> left, LinkedHashMap::new));

        Map<String, List<OrderRiskAssessment>> shortagesBySku = atRisk.stream()
                .filter(assessment -> assessment.inventoryShortage() > 0)
                .filter(assessment -> inventoryBySku.containsKey(assessment.order().sku()))
                .collect(Collectors.groupingBy(assessment -> assessment.order().sku(), LinkedHashMap::new,
                        Collectors.toList()));
        shortagesBySku.forEach((sku, orders) -> recommendations
                .add(replenishment(sku, orders, inventoryBySku.get(sku), asOf)));

        Map<String, List<OrderRiskAssessment>> suppliersToFollowUp = atRisk.stream()
                .filter(assessment -> supplierById.containsKey(assessment.order().supplierId()))
                .filter(assessment -> {
                    SupplierRiskProfile supplier = supplierById.get(assessment.order().supplierId());
                    return supplier.riskLevel() != RiskLevel.LOW
                            || lateDays(assessment.order()) > 0
                            || assessment.order().status() == OrderStatus.DELAYED;
                })
                .collect(Collectors.groupingBy(assessment -> assessment.order().supplierId(),
                        LinkedHashMap::new, Collectors.toList()));
        suppliersToFollowUp.forEach((supplierId, orders) -> recommendations
                .add(supplierFollowUp(supplierId, orders, supplierById.get(supplierId), asOf)));

        atRisk.stream()
                .filter(assessment -> !inventoryBySku.containsKey(assessment.order().sku())
                        || !supplierById.containsKey(assessment.order().supplierId()))
                .forEach(assessment -> recommendations
                        .add(manualConfirmation(assessment, inventoryBySku, supplierById, asOf)));

        return recommendations.stream()
                .sorted(Comparator.comparingInt(DisposalRecommendation::priorityScore)
                        .reversed()
                        .thenComparing(recommendation -> recommendation.affectedOrderIds().get(0))
                        .thenComparing(recommendation -> recommendation.actionType().name()))
                .toList();
    }

    private DisposalRecommendation replenishment(String sku, List<OrderRiskAssessment> orders,
            InventoryPosition position, LocalDate asOf) {
        int totalShortage = orders.stream().mapToInt(OrderRiskAssessment::inventoryShortage).sum();
        int totalDemand = orders.stream().mapToInt(assessment -> assessment.order().quantity()).sum();
        Priority priority = inventoryPriority(orders, position, totalShortage, totalDemand, asOf);
        String orderIds = orders.stream()
                .map(assessment -> assessment.order().orderId())
                .collect(Collectors.joining("、"));
        List<String> basis = new ArrayList<>();
        orders.forEach(assessment -> basis.add(orderEvidence(assessment)));
        basis.add("库存 " + sku + "：现有 " + position.onHandQuantity() + "，预留 "
                + position.reservedQuantity() + "，安全库存 " + position.safetyStock() + "，ATP "
                + position.availableToPromise() + "；ATP = 现有 - 预留 - 安全库存");
        String requestedDate = orders.stream()
                .map(assessment -> assessment.order().requestedDeliveryDate())
                .min(LocalDate::compareTo)
                .orElse(asOf)
                .toString();
        String title = "为 " + sku + " 补货 " + totalShortage + " 件";
        String action = "安排补货 " + totalShortage + " 件，并在 " + requestedDate + " 前确认可用";
        String expectedImprovement = "若补货 " + totalShortage + " 件在最早要求交付日 " + requestedDate
                + " 前可用，按当前预留量和安全库存静态测算，订单 " + orderIds + " 的库存缺口合计可从 " + totalShortage
                + " 降至 0；风险评分需在到货后重新评估，本建议不承诺具体风险等级变化。";
        return new DisposalRecommendation(UUID.randomUUID().toString(), DisposalActionType.REPLENISH_INVENTORY,
                priority.band(), priority.score(), priority.basis(), highestRisk(orders), sku, null,
                orderIds(orders), title, action, "库存缺口是当前订单风险的可量化来源，优先补足可承诺量。",
                basis, expectedImprovement,
                List.of("确认 " + totalShortage + " 件补货可在 " + requestedDate + " 前到货",
                        "确认补货不会被其他订单或临时预留占用"),
                List.of("可能产生加急运输或库存持有成本；演示数据未提供价格和成本字段",
                        "若到货晚于要求交付日，改善效果会延后"),
                List.of("ORDER:" + String.join(",", orderIds(orders)), "INVENTORY:" + sku),
                List.of("其他订单、库存和供应商字段保持不变", "补货数量按当前缺口测算，未使用未提供的采购价格或替代供应商数据"),
                PENDING_CONFIRMATION, Instant.now());
    }

    private DisposalRecommendation supplierFollowUp(String supplierId, List<OrderRiskAssessment> orders,
            SupplierRiskProfile supplier, LocalDate asOf) {
        Priority priority = supplierPriority(orders, supplier, asOf);
        String orderIds = orders.stream()
                .map(assessment -> assessment.order().orderId())
                .collect(Collectors.joining("、"));
        List<String> basis = new ArrayList<>();
        orders.forEach(assessment -> basis.add(orderEvidence(assessment)));
        basis.add("供应商 " + supplier.supplierId() + "：" + supplier.name() + "，风险 "
                + supplier.riskLevel() + "，准时交付率 " + Math.round(supplier.onTimeDeliveryRate() * 100)
                + "%，缺陷率 " + String.format(java.util.Locale.ROOT, "%.1f", supplier.defectRate() * 100)
                + "%，最近事件：" + supplier.latestIncident());
        long maxLateDays = orders.stream()
                .mapToLong(assessment -> lateDays(assessment.order()))
                .max()
                .orElse(0);
        String title = "跟进供应商 " + supplier.supplierId() + " 的恢复计划";
        String action = "联系 " + supplier.name() + " 确认恢复日期、剩余产能和延期原因，再更新订单 " + orderIds
                + " 的预计交付";
        String expectedImprovement = maxLateDays > 0
                ? "当前相关订单最多晚于要求交付 " + maxLateDays
                        + " 天；确认恢复日期后可把交付不确定性转为可核对的承诺，具体缩短天数取决于供应商回复，演示数据未提供该承诺，需人工确认。"
                : "当前供应商风险或订单状态需要人工跟进；确认恢复日期后可把不确定性转为可核对承诺，改善幅度需人工确认。";
        return new DisposalRecommendation(UUID.randomUUID().toString(), DisposalActionType.FOLLOW_UP_SUPPLIER,
                priority.band(), priority.score(), priority.basis(), highestRisk(orders), null, supplierId,
                orderIds(orders), title, action, "供应商履约风险和延期状态已超过规则阈值，需要先确认外部承诺。",
                basis, expectedImprovement,
                List.of("由采购联系供应商确认恢复日期与剩余产能",
                        "确认结果需要回写到订单预计交付字段后才算闭环"),
                List.of("跟进动作本身不改变库存，交付是否改善取决于供应商回复",
                        "可能影响供应关系，需要采购按流程沟通"),
                List.of("ORDER:" + String.join(",", orderIds(orders)), "SUPPLIER:" + supplierId),
                List.of("其他订单、库存和供应商字段保持不变", "未编造替代供应商、报价或供应商承诺日期"),
                PENDING_CONFIRMATION, Instant.now());
    }

    private DisposalRecommendation manualConfirmation(OrderRiskAssessment assessment,
            Map<String, InventoryPosition> inventoryBySku, Map<String, SupplierRiskProfile> supplierById,
            LocalDate asOf) {
        SupplyChainOrder order = assessment.order();
        Priority priority = manualPriority(assessment, asOf);
        List<String> missing = new ArrayList<>();
        if (!inventoryBySku.containsKey(order.sku())) {
            missing.add("缺少 SKU " + order.sku() + " 的库存记录");
        }
        if (!supplierById.containsKey(order.supplierId())) {
            missing.add("缺少供应商 " + order.supplierId() + " 的履约记录");
        }
        return new DisposalRecommendation(UUID.randomUUID().toString(), DisposalActionType.MANUAL_CONFIRMATION,
                priority.band(), priority.score(), priority.basis(), assessment.riskLevel(), order.sku(),
                order.supplierId(), List.of(order.orderId()), "人工核对订单 " + order.orderId() + " 的主数据",
                "补齐缺失的库存或供应商主数据后重新评估，不基于缺失字段给出补货量或供应商结论",
                "当前订单存在风险，但生成确定性处置建议所需的字段不完整。", List.of(orderEvidence(assessment)),
                "补齐主数据后可得到可计算的缺口和建议；当前无法量化改善。", missing,
                List.of("在缺失数据上直接补货或更换供应商可能产生错误决策"), List.of("ORDER:" + order.orderId()),
                List.of("未使用缺失字段进行推断", "所有缺失项都需要人工确认"), PENDING_CONFIRMATION, Instant.now());
    }

    private Priority inventoryPriority(List<OrderRiskAssessment> orders, InventoryPosition position,
            int totalShortage, int totalDemand, LocalDate asOf) {
        int urgency = urgencyPoints(orders, asOf);
        int shortage = totalDemand <= 0 ? 0 : Math.min(30,
                (int) Math.ceil(30.0 * totalShortage / totalDemand));
        int risk = highestRisk(orders) == RiskLevel.HIGH ? 10
                : highestRisk(orders) == RiskLevel.MEDIUM ? 6 : 0;
        int score = Math.min(100, urgency + shortage + risk);
        return new Priority(score, band(score), "紧急度 " + urgency + " + 缺口 " + shortage + "（缺口 "
                + totalShortage + " / 需求 " + totalDemand + "）+ 最高风险 " + risk + " = " + score
                + "；ATP " + position.availableToPromise());
    }

    private Priority supplierPriority(List<OrderRiskAssessment> orders, SupplierRiskProfile supplier,
            LocalDate asOf) {
        int urgency = urgencyPoints(orders, asOf);
        int supplierPoints = supplier.riskLevel() == RiskLevel.HIGH ? 20
                : supplier.riskLevel() == RiskLevel.MEDIUM ? 10 : 0;
        long maxLateDays = orders.stream()
                .mapToLong(assessment -> lateDays(assessment.order()))
                .max()
                .orElse(0);
        int late = maxLateDays > 0 ? Math.min(20, 10 + (int) maxLateDays * 2)
                : orders.stream().anyMatch(assessment -> assessment.order().status() == OrderStatus.DELAYED) ? 10
                        : 0;
        int risk = highestRisk(orders) == RiskLevel.HIGH ? 10
                : highestRisk(orders) == RiskLevel.MEDIUM ? 6 : 0;
        int score = Math.min(100, urgency + supplierPoints + late + risk);
        return new Priority(score, band(score), "紧急度 " + urgency + " + 供应商风险 " + supplierPoints
                + "（" + supplier.riskLevel() + "）+ 延期 " + late + " + 最高风险 " + risk + " = " + score);
    }

    private Priority manualPriority(OrderRiskAssessment assessment, LocalDate asOf) {
        int urgency = urgencyPoints(List.of(assessment), asOf);
        int risk = assessment.riskLevel() == RiskLevel.HIGH ? 10
                : assessment.riskLevel() == RiskLevel.MEDIUM ? 6 : 0;
        int score = Math.min(100, urgency + 20 + risk);
        return new Priority(score, band(score),
                "紧急度 " + urgency + " + 数据缺失 20 + 最高风险 " + risk + " = " + score);
    }

    private static int urgencyPoints(List<OrderRiskAssessment> orders, LocalDate asOf) {
        long days = orders.stream()
                .mapToLong(assessment -> ChronoUnit.DAYS.between(asOf, assessment.order().requestedDeliveryDate()))
                .min()
                .orElse(0);
        if (days < 0) {
            return 45;
        }
        if (days <= 2) {
            return 40;
        }
        if (days <= 4) {
            return 30;
        }
        return days <= 7 ? 20 : 10;
    }

    private static DisposalPriority band(int score) {
        return score >= 75 ? DisposalPriority.P1 : score >= 55 ? DisposalPriority.P2 : DisposalPriority.P3;
    }

    private static RiskLevel highestRisk(List<OrderRiskAssessment> orders) {
        return orders.stream()
                .map(OrderRiskAssessment::riskLevel)
                .max(Comparator.comparingInt(DisposalRecommendationService::riskRank))
                .orElse(RiskLevel.LOW);
    }

    private static int riskRank(RiskLevel level) {
        return switch (level) {
            case LOW -> 0;
            case MEDIUM -> 1;
            case HIGH -> 2;
        };
    }

    private static List<String> orderIds(List<OrderRiskAssessment> orders) {
        return orders.stream().map(assessment -> assessment.order().orderId()).toList();
    }

    private static String orderEvidence(OrderRiskAssessment assessment) {
        SupplyChainOrder order = assessment.order();
        return "订单 " + order.orderId() + "：SKU " + order.sku() + "，需求 " + order.quantity() + " 件，要求交付 "
                + order.requestedDeliveryDate() + "，预计交付 " + order.estimatedDeliveryDate() + "，状态 "
                + order.status() + "，风险 " + assessment.riskLevel() + "（评分 " + assessment.riskScore()
                + "，库存缺口 " + assessment.inventoryShortage() + "）";
    }

    private static long lateDays(SupplyChainOrder order) {
        return Math.max(0,
                ChronoUnit.DAYS.between(order.requestedDeliveryDate(), order.estimatedDeliveryDate()));
    }

    private record Priority(int score, DisposalPriority band, String basis) {
    }
}
