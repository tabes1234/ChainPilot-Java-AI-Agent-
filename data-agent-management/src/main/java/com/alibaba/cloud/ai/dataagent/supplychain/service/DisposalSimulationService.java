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

import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalSimulation;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalSimulationSummary;
import com.alibaba.cloud.ai.dataagent.supplychain.model.InventoryPosition;
import com.alibaba.cloud.ai.dataagent.supplychain.model.OrderRiskAssessment;
import com.alibaba.cloud.ai.dataagent.supplychain.model.OrderRiskImpact;
import com.alibaba.cloud.ai.dataagent.supplychain.model.OrderStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskLevel;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskEvidence;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SimulationBaselineView;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SimulationDelta;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SimulationBaseline;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SimulationMetrics;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SimulationRequest;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SimulationScenario;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplierRiskProfile;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainOrder;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.DisposalSimulationRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Deterministic what-if calculation. Orders and inventory are copied into local snapshots and
 * re-evaluated with the same risk rule used by the analysis workspace; demo data is never
 * modified and the model never performs arithmetic.
 */
@Service
public class DisposalSimulationService {

    static final String BASELINE_SOURCE = "CURRENT_BUSINESS_DATA";

    static final String DISCLAIMER =
            "模拟结果仅为静态规则测算，不代表实际执行结果；未修改真实库存或订单数据。";

    private final SupplyChainQueryService queryService;

    private final DisposalSimulationRepository simulationRepository;

    public DisposalSimulationService(SupplyChainQueryService queryService,
            DisposalSimulationRepository simulationRepository) {
        this.queryService = queryService;
        this.simulationRepository = simulationRepository;
    }

    /** Pure preview, used by Agent tools and before a plan is saved. */
    public DisposalSimulation simulate(SimulationRequest request) {
        return simulate(request, LocalDate.now());
    }

    DisposalSimulation simulate(SimulationRequest request, LocalDate asOf) {
        return simulate(request, captureBaseline(asOf));
    }

    /**
     * Captures one immutable business-data snapshot. Comparisons call this once and pass the
     * result to every candidate so all deltas share exactly the same baseline.
     */
    public SimulationBaseline captureBaseline(LocalDate asOf) {
        return new SimulationBaseline(queryService.findAllOrders(), queryService.findInventory(null),
                queryService.findSuppliers(), asOf, Instant.now());
    }

    /** Read-only baseline used by the simulation workspace before any parameter is applied. */
    public SimulationBaselineView baseline() {
        LocalDate asOf = LocalDate.now();
        SimulationBaseline snapshot = captureBaseline(asOf);
        List<OrderRiskAssessment> assessments = assessAll(snapshot.orders(), snapshot.inventory(),
                snapshot.suppliers(), asOf);
        return new SimulationBaselineView(BASELINE_SOURCE, asOf, snapshot.capturedAt(),
                metrics(snapshot.orders(), assessments, null), assessments, snapshot.orders(),
                snapshot.inventory(), snapshot.suppliers(), dataGaps(assessments),
                DisposalSimulation.RULE_VERSION, DisposalSimulation.SIMULATION_STATUS, DISCLAIMER);
    }

    /** Simulates against an explicit baseline without reading the demo data again. */
    DisposalSimulation simulate(SimulationRequest request, SimulationBaseline snapshot) {
        SimulationRequest validated = validate(request);
        LocalDate asOf = snapshot.asOfDate();
        List<SupplyChainOrder> baselineOrders = snapshot.orders();
        List<InventoryPosition> baselineInventory = snapshot.inventory();
        List<SupplierRiskProfile> suppliers = snapshot.suppliers();
        if (validated.scenario() == SimulationScenario.INVENTORY_REPLENISHMENT
                && baselineInventory.stream().noneMatch(position -> position.sku().equalsIgnoreCase(validated.sku().trim()))) {
            throw new IllegalArgumentException("Unknown SKU: " + validated.sku());
        }
        if (validated.scenario() == SimulationScenario.SUPPLIER_DELAY
                && suppliers.stream().noneMatch(supplier -> supplier.supplierId().equalsIgnoreCase(validated.supplierId().trim()))) {
            throw new IllegalArgumentException("Unknown supplier: " + validated.supplierId());
        }

        List<SupplyChainOrder> simulatedOrders = baselineOrders;
        List<InventoryPosition> simulatedInventory = baselineInventory;
        if (validated.scenario() == SimulationScenario.INVENTORY_REPLENISHMENT) {
            String sku = validated.sku().trim();
            int quantity = validated.replenishmentQuantity();
            simulatedInventory = baselineInventory.stream()
                    .map(position -> position.sku().equalsIgnoreCase(sku)
                            ? new InventoryPosition(position.sku(), position.productName(),
                                    position.onHandQuantity() + quantity, position.reservedQuantity(),
                                    position.safetyStock())
                            : position)
                    .toList();
        }
        else {
            String supplierId = validated.supplierId().trim();
            int delay = validated.additionalDelayDays();
            simulatedOrders = baselineOrders.stream()
                    .map(order -> order.supplierId().equalsIgnoreCase(supplierId)
                            && order.status() != OrderStatus.DELIVERED
                                    ? new SupplyChainOrder(order.orderId(), order.sku(), order.quantity(),
                                            order.supplierId(), order.requestedDeliveryDate(),
                                            order.estimatedDeliveryDate().plusDays(delay), order.status())
                                    : order)
                    .toList();
        }

        List<OrderRiskAssessment> baselineAssessments = assessAll(baselineOrders, baselineInventory, suppliers, asOf);
        List<OrderRiskAssessment> simulatedAssessments = assessAll(simulatedOrders, simulatedInventory, suppliers, asOf);
        SimulationMetrics baselineMetrics = metrics(baselineOrders, baselineAssessments,
                targetAtp(validated, baselineInventory));
        SimulationMetrics simulatedMetrics = metrics(simulatedOrders, simulatedAssessments,
                targetAtp(validated, simulatedInventory));
        List<OrderRiskImpact> impacts = impacts(baselineAssessments, simulatedAssessments);
        SimulationDelta delta = new SimulationDelta(impacts.size(),
                simulatedMetrics.atRiskOrders() - baselineMetrics.atRiskOrders(),
                simulatedMetrics.highRiskOrders() - baselineMetrics.highRiskOrders(),
                simulatedMetrics.ordersWithShortage() - baselineMetrics.ordersWithShortage(),
                simulatedMetrics.totalShortageUnits() - baselineMetrics.totalShortageUnits(),
                simulatedMetrics.totalRiskScore() - baselineMetrics.totalRiskScore(),
                delta(simulatedMetrics.targetSkuAvailableToPromise(), baselineMetrics.targetSkuAvailableToPromise()));

        String sku = validated.scenario() == SimulationScenario.INVENTORY_REPLENISHMENT ? validated.sku().trim()
                : null;
        String supplierId = validated.scenario() == SimulationScenario.SUPPLIER_DELAY ? validated.supplierId().trim()
                : null;
        return new DisposalSimulation(UUID.randomUUID().toString(), validated.scenario(), sku, supplierId,
                validated.replenishmentQuantity(), validated.additionalDelayDays(), asOf, BASELINE_SOURCE,
                baselineMetrics, simulatedMetrics, delta, impacts, assumptions(validated), limitations(),
                evidence(validated, baselineMetrics, simulatedMetrics), false, DISCLAIMER, Instant.now(),
                DisposalSimulation.RULE_VERSION, DisposalSimulation.SIMULATION_STATUS,
                dataGaps(simulatedAssessments));
    }

    public DisposalSimulation simulateAndSave(SimulationRequest request) {
        return save(simulate(request));
    }

    public DisposalSimulation save(DisposalSimulation simulation) {
        simulationRepository.save(simulation, null);
        return simulation;
    }

    public DisposalSimulation findSimulation(String simulationId) {
        return simulationRepository.find(simulationId)
                .orElseThrow(() -> new SupplyChainResourceNotFoundException("Unknown simulation: " + simulationId));
    }

    public List<DisposalSimulationSummary> history(int limit, String planId) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        return simulationRepository.findRecent(limit, planId);
    }

    public void attachToPlan(String simulationId, String planId) {
        simulationRepository.attachToPlan(simulationId, planId);
    }

    private List<OrderRiskAssessment> assessAll(List<SupplyChainOrder> orders,
            List<InventoryPosition> inventory, List<SupplierRiskProfile> suppliers, LocalDate asOf) {
        return orders.stream()
                .map(order -> queryService.assess(order, inventory, suppliers, asOf))
                .toList();
    }

    private static SimulationMetrics metrics(List<SupplyChainOrder> orders,
            List<OrderRiskAssessment> assessments, Integer targetAtp) {
        return new SimulationMetrics(orders.size(),
                (int) assessments.stream().filter(item -> item.riskLevel() != RiskLevel.LOW).count(),
                (int) assessments.stream().filter(item -> item.riskLevel() == RiskLevel.HIGH).count(),
                (int) assessments.stream().filter(item -> item.inventoryShortage() > 0).count(),
                assessments.stream().mapToInt(OrderRiskAssessment::inventoryShortage).sum(),
                assessments.stream().mapToInt(OrderRiskAssessment::riskScore).sum(), targetAtp);
    }

    private static Integer targetAtp(SimulationRequest request, List<InventoryPosition> inventory) {
        if (request.scenario() != SimulationScenario.INVENTORY_REPLENISHMENT) {
            return null;
        }
        return inventory.stream()
            .filter(position -> position.sku().equalsIgnoreCase(request.sku().trim()))
            .map(InventoryPosition::availableToPromise)
            .findFirst()
            .orElse(null);
    }

    private static List<String> dataGaps(List<OrderRiskAssessment> assessments) {
        return List.copyOf(assessments.stream()
            .flatMap(assessment -> assessment.dataGaps().stream())
            .collect(Collectors.toCollection(LinkedHashSet::new)));
    }

    private static List<OrderRiskImpact> impacts(List<OrderRiskAssessment> baseline,
            List<OrderRiskAssessment> simulated) {
        Map<String, OrderRiskAssessment> simulatedByOrder = simulated.stream()
                .collect(Collectors.toMap(assessment -> assessment.order().orderId(), Function.identity()));
        List<OrderRiskImpact> impacts = new ArrayList<>();
        for (OrderRiskAssessment before : baseline) {
            OrderRiskAssessment after = simulatedByOrder.get(before.order().orderId());
            if (after == null) {
                continue;
            }
            List<String> changes = new ArrayList<>();
            if (!before.order().estimatedDeliveryDate().equals(after.order().estimatedDeliveryDate())) {
                changes.add("estimatedDeliveryDate " + before.order().estimatedDeliveryDate() + " -> "
                        + after.order().estimatedDeliveryDate());
            }
            if (before.riskLevel() != after.riskLevel()) {
                changes.add("riskLevel " + before.riskLevel() + " -> " + after.riskLevel());
            }
            if (before.riskScore() != after.riskScore()) {
                changes.add("riskScore " + before.riskScore() + " -> " + after.riskScore());
            }
            if (before.inventoryShortage() != after.inventoryShortage()) {
                changes.add("inventoryShortage " + before.inventoryShortage() + " -> "
                        + after.inventoryShortage());
            }
            List<String> evidenceChanges = evidenceChanges(before, after);
            if (!changes.isEmpty() || !evidenceChanges.isEmpty()) {
                impacts.add(new OrderRiskImpact(before.order().orderId(), before.order().sku(),
                        before.order().supplierId(), before.order().estimatedDeliveryDate(),
                        after.order().estimatedDeliveryDate(), before.riskLevel(), after.riskLevel(),
                        before.riskScore(), after.riskScore(), before.inventoryShortage(),
                        after.inventoryShortage(), changes, evidenceChanges, after.dataGaps()));
            }
        }
        return List.copyOf(impacts);
    }

    private static List<String> evidenceChanges(OrderRiskAssessment before, OrderRiskAssessment after) {
        Map<String, RiskEvidence> beforeByCode = before.evidence()
            .stream()
            .collect(Collectors.toMap(RiskEvidence::code, Function.identity(), (left, right) -> left,
                    LinkedHashMap::new));
        Map<String, RiskEvidence> afterByCode = after.evidence()
            .stream()
            .collect(Collectors.toMap(RiskEvidence::code, Function.identity(), (left, right) -> left,
                    LinkedHashMap::new));
        LinkedHashSet<String> codes = new LinkedHashSet<>(beforeByCode.keySet());
        codes.addAll(afterByCode.keySet());
        List<String> changes = new ArrayList<>();
        for (String code : codes) {
            RiskEvidence previous = beforeByCode.get(code);
            RiskEvidence current = afterByCode.get(code);
            if (previous == null) {
                changes.add(code + " 新增证据：" + current.label());
                continue;
            }
            if (current == null) {
                changes.add(code + " 证据消失：" + previous.label());
                continue;
            }
            List<String> parts = new ArrayList<>();
            if (previous.scoreContribution() != current.scoreContribution()) {
                parts.add("计分 " + signed(previous.scoreContribution()) + " -> "
                        + signed(current.scoreContribution()));
            }
            if (previous.available() != current.available()) {
                parts.add("可用 " + previous.available() + " -> " + current.available());
            }
            if (!Objects.equals(previous.observedValue(), current.observedValue())) {
                parts.add("观测值 " + previous.observedValue() + " -> " + current.observedValue());
            }
            if (!parts.isEmpty()) {
                changes.add(code + " " + current.label() + "：" + String.join("；", parts));
            }
        }
        return List.copyOf(changes);
    }

    private static SimulationRequest validate(SimulationRequest request) {
        if (request == null || request.scenario() == null) {
            throw new IllegalArgumentException("scenario is required");
        }
        if (request.scenario() == SimulationScenario.INVENTORY_REPLENISHMENT) {
            if (!StringUtils.hasText(request.sku())) {
                throw new IllegalArgumentException("sku is required for INVENTORY_REPLENISHMENT");
            }
            if (request.replenishmentQuantity() == null) {
                throw new IllegalArgumentException("replenishmentQuantity is required for INVENTORY_REPLENISHMENT");
            }
            if (request.replenishmentQuantity() < 1 || request.replenishmentQuantity() > 1_000_000) {
                throw new IllegalArgumentException("replenishmentQuantity must be between 1 and 1000000");
            }
            if (StringUtils.hasText(request.supplierId()) || request.additionalDelayDays() != null) {
                throw new IllegalArgumentException(
                        "supplierId and additionalDelayDays are not valid for INVENTORY_REPLENISHMENT");
            }
        }
        else {
            if (!StringUtils.hasText(request.supplierId())) {
                throw new IllegalArgumentException("supplierId is required for SUPPLIER_DELAY");
            }
            if (request.additionalDelayDays() == null) {
                throw new IllegalArgumentException("additionalDelayDays is required for SUPPLIER_DELAY");
            }
            if (request.additionalDelayDays() < 1 || request.additionalDelayDays() > 90) {
                throw new IllegalArgumentException("additionalDelayDays must be between 1 and 90");
            }
            if (StringUtils.hasText(request.sku()) || request.replenishmentQuantity() != null) {
                throw new IllegalArgumentException("sku and replenishmentQuantity are not valid for SUPPLIER_DELAY");
            }
        }
        return request;
    }

    /** Package-visible validation hook for plan creation and tests. */
    void validateForPlan(SimulationRequest request) {
        validate(request);
    }

    public void requireKnownTargets(SimulationRequest request) {
        requireKnownTargets(request, captureBaseline(LocalDate.now()));
    }

    void requireKnownTargets(SimulationRequest request, SimulationBaseline baseline) {
        SimulationRequest validated = validate(request);
        if (validated.scenario() == SimulationScenario.INVENTORY_REPLENISHMENT
                && baseline.inventory()
                    .stream()
                    .noneMatch(position -> position.sku().equalsIgnoreCase(validated.sku().trim()))) {
            throw new IllegalArgumentException("Unknown SKU: " + validated.sku());
        }
        if (validated.scenario() == SimulationScenario.SUPPLIER_DELAY
                && baseline.suppliers()
                    .stream()
                    .noneMatch(supplier -> supplier.supplierId().equalsIgnoreCase(validated.supplierId().trim()))) {
            throw new IllegalArgumentException("Unknown supplier: " + validated.supplierId());
        }
    }

    /** Plan creation validates a previously saved simulation without replaying it. */
    public void requireCompatible(DisposalSimulation simulation, String sku, String supplierId) {
        if (simulation == null) {
            return;
        }
        if (StringUtils.hasText(sku) && StringUtils.hasText(simulation.sku())
                && !sku.equalsIgnoreCase(simulation.sku())) {
            throw new IllegalArgumentException("Simulation SKU does not match the plan scope");
        }
        if (StringUtils.hasText(supplierId) && StringUtils.hasText(simulation.supplierId())
                && !supplierId.equalsIgnoreCase(simulation.supplierId())) {
            throw new IllegalArgumentException("Simulation supplier does not match the plan scope");
        }
    }

    private static List<String> assumptions(SimulationRequest request) {
        if (request.scenario() == SimulationScenario.INVENTORY_REPLENISHMENT) {
            return List.of("补货数量在最早要求交付日前全部可用",
                    "预留量、安全库存和其他 SKU 的库存保持不变",
                    "模拟期间没有新增订单、取消订单或临时预留");
        }
        return List.of("该供应商所有未交付订单统一增加 " + request.additionalDelayDays() + " 天",
                "其他供应商、库存、需求数量和安全库存保持不变",
                "不考虑加急运输、替代供应商或客户改期");
    }

    private static List<String> limitations() {
        return List.of("静态规则测算，不包含价格、成本、产能和运输约束",
                "缺失库存/供应商只通过 dataGaps 标记，不推断数值，也不把缺失值当作零参与结论",
                "结果不是实际执行结果，需要人工确认后才能落地",
                "风险规则与当前分析工作台完全一致，便于基线对比");
    }

    private static List<String> evidence(SimulationRequest request, SimulationMetrics baseline,
            SimulationMetrics simulated) {
        String parameter = request.scenario() == SimulationScenario.INVENTORY_REPLENISHMENT
                ? "INVENTORY_REPLENISHMENT sku=" + request.sku().trim() + " quantity="
                        + request.replenishmentQuantity()
                : "SUPPLIER_DELAY supplierId=" + request.supplierId().trim() + " additionalDelayDays="
                        + request.additionalDelayDays();
        return List.of(parameter,
                "RULE_VERSION=" + DisposalSimulation.RULE_VERSION + "; STATUS="
                        + DisposalSimulation.SIMULATION_STATUS,
                "BASELINE atRiskOrders=" + baseline.atRiskOrders() + ", totalShortageUnits="
                        + baseline.totalShortageUnits() + ", targetAtp=" + baseline.targetSkuAvailableToPromise(),
                "SIMULATED atRiskOrders=" + simulated.atRiskOrders() + ", totalShortageUnits="
                        + simulated.totalShortageUnits() + ", targetAtp="
                        + simulated.targetSkuAvailableToPromise());
    }

    private static String signed(int value) {
        return value > 0 ? "+" + value : Integer.toString(value);
    }

    private static Integer delta(Integer simulated, Integer baseline) {
        if (simulated == null || baseline == null) {
            return null;
        }
        return simulated - baseline;
    }
}
