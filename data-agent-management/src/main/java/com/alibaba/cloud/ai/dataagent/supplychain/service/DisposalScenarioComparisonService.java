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

import com.alibaba.cloud.ai.dataagent.supplychain.model.CreateComparisonDisposalPlanRequest;
import com.alibaba.cloud.ai.dataagent.supplychain.model.CreateDisposalScenarioComparisonRequest;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalPlan;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalPlanStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioCandidate;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioComparison;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioComparisonOutcome;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioComparisonPage;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioComparisonSummary;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioRankingResult;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioRecommendation;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalSimulation;
import com.alibaba.cloud.ai.dataagent.supplychain.model.InventoryPosition;
import com.alibaba.cloud.ai.dataagent.supplychain.model.LinkComparisonCandidatePlanRequest;
import com.alibaba.cloud.ai.dataagent.supplychain.model.ScenarioComparisonCandidateRequest;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SimulationBaseline;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SimulationMetrics;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SimulationRequest;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SimulationScenario;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplierRiskProfile;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainOrder;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.DisposalScenarioComparisonRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Builds, persists and links multi-candidate comparisons. Every candidate is simulated from
 * the same captured baseline with the existing deterministic engine; ranking is delegated to
 * {@link DisposalScenarioRankingEngine} and never produced by a model.
 */
@Service
public class DisposalScenarioComparisonService {

    private static final int MAX_PAGE_SIZE = 50;

    private static final int MAX_CANDIDATES = 5;

    private final DisposalSimulationService simulationService;

    private final DisposalScenarioRankingEngine rankingEngine;

    private final DisposalScenarioComparisonRepository comparisonRepository;

    private final DisposalPlanService planService;

    private final ObjectMapper mapper;

    public DisposalScenarioComparisonService(DisposalSimulationService simulationService,
            DisposalScenarioRankingEngine rankingEngine,
            DisposalScenarioComparisonRepository comparisonRepository, @Lazy DisposalPlanService planService,
            ObjectMapper mapper) {
        this.simulationService = simulationService;
        this.rankingEngine = rankingEngine;
        this.comparisonRepository = comparisonRepository;
        this.planService = planService;
        this.mapper = mapper;
    }

    @Transactional
    public DisposalScenarioComparison compare(CreateDisposalScenarioComparisonRequest request) {
        if (request == null || !StringUtils.hasText(request.title())) {
            throw new IllegalArgumentException("title is required");
        }
        String title = request.title().trim();
        if (title.length() > 200) {
            throw new IllegalArgumentException("title must be at most 200 characters");
        }
        SimulationScenario scenario = request.scenario();
        if (scenario == null) {
            throw new IllegalArgumentException("scenario is required");
        }
        List<ScenarioComparisonCandidateRequest> candidateRequests = request.candidates();
        if (candidateRequests == null || candidateRequests.size() < 2
                || candidateRequests.size() > MAX_CANDIDATES) {
            throw new IllegalArgumentException("candidates must contain between 2 and " + MAX_CANDIDATES + " items");
        }
        String sku = normalize(request.sku());
        String supplierId = normalize(request.supplierId());
        if (scenario == SimulationScenario.INVENTORY_REPLENISHMENT) {
            if (!StringUtils.hasText(sku)) {
                throw new IllegalArgumentException("sku is required for INVENTORY_REPLENISHMENT");
            }
            if (StringUtils.hasText(supplierId)) {
                throw new IllegalArgumentException("supplierId is not valid for INVENTORY_REPLENISHMENT");
            }
        }
        else {
            if (!StringUtils.hasText(supplierId)) {
                throw new IllegalArgumentException("supplierId is required for SUPPLIER_DELAY");
            }
            if (StringUtils.hasText(sku)) {
                throw new IllegalArgumentException("sku is not valid for SUPPLIER_DELAY");
            }
        }

        SimulationBaseline baseline = simulationService.captureBaseline(LocalDate.now());
        Set<Integer> parameters = new LinkedHashSet<>();
        List<DisposalScenarioCandidate> candidates = new ArrayList<>();
        Instant now = Instant.now();
        for (int index = 0; index < candidateRequests.size(); index++) {
            ScenarioComparisonCandidateRequest candidateRequest = candidateRequests.get(index);
            if (candidateRequest == null) {
                throw new IllegalArgumentException("candidate at position " + (index + 1) + " is required");
            }
            SimulationRequest simulationRequest = new SimulationRequest(scenario, sku,
                    candidateRequest.replenishmentQuantity(), supplierId,
                    candidateRequest.additionalDelayDays());
            simulationService.requireKnownTargets(simulationRequest, baseline);
            int parameter = scenario == SimulationScenario.INVENTORY_REPLENISHMENT
                    ? candidateRequest.replenishmentQuantity() : candidateRequest.additionalDelayDays();
            if (!parameters.add(parameter)) {
                throw new IllegalArgumentException("Candidate parameters must be unique: " + parameter);
            }
            String label = StringUtils.hasText(candidateRequest.label()) ? candidateRequest.label().trim()
                    : defaultLabel(scenario, parameter);
            if (label.length() > 100) {
                throw new IllegalArgumentException("candidate label must be at most 100 characters");
            }
            DisposalSimulation simulation = simulationService.simulate(simulationRequest, baseline);
            if (simulation.realDataMutated()) {
                throw new IllegalStateException("Comparison simulation must not mutate real business data");
            }
            candidates.add(new DisposalScenarioCandidate(UUID.randomUUID().toString(), label, index + 1, scenario,
                    sku, supplierId, candidateRequest.replenishmentQuantity(),
                    candidateRequest.additionalDelayDays(), simulation, null, now));
        }

        SimulationMetrics baselineMetrics = candidates.get(0).simulation().baseline();
        for (DisposalScenarioCandidate candidate : candidates) {
            if (!baselineMetrics.equals(candidate.simulation().baseline())) {
                throw new IllegalStateException("Comparison candidates do not share the same baseline metrics");
            }
        }
        String fingerprint = fingerprint(baseline);
        DisposalScenarioRankingResult rankingResult = rankingEngine.rank(baselineMetrics, candidates);
        DisposalScenarioRecommendation recommendation = rankingResult.recommendation();
        Integer bestAtRiskDelta = recommendation.outcome() == DisposalScenarioComparisonOutcome.INCOMPARABLE ? null
                : candidates.stream().mapToInt(candidate -> candidate.simulation().delta().atRiskOrders()).min()
                    .getAsInt();
        Integer bestShortageDelta = recommendation.outcome() == DisposalScenarioComparisonOutcome.INCOMPARABLE ? null
                : candidates.stream()
                    .mapToInt(candidate -> candidate.simulation().delta().totalShortageUnits())
                    .min()
                    .getAsInt();

        String comparisonId = UUID.randomUUID().toString();
        DisposalScenarioComparison comparison = new DisposalScenarioComparison(comparisonId, title, scenario, sku,
                supplierId, baseline.asOfDate(), DisposalSimulationService.BASELINE_SOURCE, fingerprint,
                baseline.capturedAt(), baselineMetrics, bestAtRiskDelta, bestShortageDelta, candidates,
                rankingResult.rankings(), recommendation, rankingResult.rankingRules(),
                assumptions(candidates, baseline, fingerprint), limitations(candidates),
                evidence(candidates, baselineMetrics, fingerprint, rankingResult), false,
                recommendation.disclaimer(), now, now);
        comparisonRepository.save(comparison);
        return comparisonRepository.find(comparisonId)
            .orElseThrow(() -> new IllegalStateException("Saved comparison could not be reloaded"));
    }

    public DisposalScenarioComparison comparison(String comparisonId) {
        if (!StringUtils.hasText(comparisonId)) {
            throw new IllegalArgumentException("comparisonId is required");
        }
        return comparisonRepository.find(comparisonId.trim())
            .orElseThrow(() -> new SupplyChainResourceNotFoundException(
                    "Unknown disposal scenario comparison: " + comparisonId));
    }

    public DisposalScenarioComparisonPage comparisons(int page, int pageSize) {
        if (page < 1) {
            throw new IllegalArgumentException("page must be at least 1");
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("pageSize must be between 1 and " + MAX_PAGE_SIZE);
        }
        long totalElements = comparisonRepository.count();
        long offset = (long) (page - 1) * pageSize;
        List<DisposalScenarioComparisonSummary> items = comparisonRepository.findPage(pageSize, offset);
        int totalPages = totalElements == 0 ? 0 : (int) ((totalElements + pageSize - 1) / pageSize);
        return new DisposalScenarioComparisonPage(items, page, pageSize, totalElements, totalPages);
    }

    @Transactional
    public DisposalPlan saveCandidateAsPlan(String comparisonId, String candidateId,
            CreateComparisonDisposalPlanRequest request) {
        DisposalScenarioComparison comparison = comparison(comparisonId);
        DisposalScenarioCandidate candidate = requireCandidate(comparison, candidateId);
        if (StringUtils.hasText(candidate.linkedPlanId())) {
            throw new IllegalStateException("Candidate is already linked to a disposal plan");
        }
        if (request == null || !StringUtils.hasText(request.title())) {
            throw new IllegalArgumentException("title is required");
        }
        DisposalPlan plan = planService.createFromSimulation(request.title().trim(), request.taskId(),
                comparison.sku(), comparison.supplierId(), candidate.simulation(), request.generateExplanation());
        comparisonRepository.linkCandidateToPlan(comparison.comparisonId(), candidate.candidateId(), plan.planId(),
                Instant.now());
        return plan;
    }

    @Transactional
    public DisposalScenarioComparison linkCandidateToPlan(String comparisonId, String candidateId,
            LinkComparisonCandidatePlanRequest request) {
        DisposalScenarioComparison comparison = comparison(comparisonId);
        DisposalScenarioCandidate candidate = requireCandidate(comparison, candidateId);
        if (request == null || !StringUtils.hasText(request.planId())) {
            throw new IllegalArgumentException("planId is required");
        }
        String planId = request.planId().trim();
        if (planId.equals(candidate.linkedPlanId())) {
            return comparison;
        }
        DisposalPlan plan = planService.plan(planId);
        if (StringUtils.hasText(candidate.linkedPlanId())) {
            throw new IllegalStateException("Candidate is already linked to another disposal plan");
        }
        if (plan.status() == DisposalPlanStatus.REJECTED) {
            throw new IllegalStateException("A rejected disposal plan cannot be linked");
        }
        validatePlanScope(comparison, candidate, plan);
        comparisonRepository.findLinkedPlanOwner(comparison.comparisonId(), plan.planId()).ifPresent(owner -> {
            if (!owner.equals(candidate.candidateId())) {
                throw new IllegalStateException("Disposal plan is already linked to another candidate");
            }
        });
        comparisonRepository.linkCandidateToPlan(comparison.comparisonId(), candidate.candidateId(), plan.planId(),
                Instant.now());
        return comparison(comparison.comparisonId());
    }

    private static DisposalScenarioCandidate requireCandidate(DisposalScenarioComparison comparison,
            String candidateId) {
        if (!StringUtils.hasText(candidateId)) {
            throw new IllegalArgumentException("candidateId is required");
        }
        return comparison.candidates()
            .stream()
            .filter(candidate -> candidate.candidateId().equals(candidateId.trim()))
            .findFirst()
            .orElseThrow(() -> new SupplyChainResourceNotFoundException(
                    "Unknown comparison candidate: " + candidateId));
    }

    private static void validatePlanScope(DisposalScenarioComparison comparison, DisposalScenarioCandidate candidate,
            DisposalPlan plan) {
        if (comparison.scenario() == SimulationScenario.INVENTORY_REPLENISHMENT) {
            if (!StringUtils.hasText(plan.scopeSku())
                    || !plan.scopeSku().equalsIgnoreCase(candidate.sku())) {
                throw new IllegalArgumentException(
                        "Disposal plan SKU scope does not match the comparison candidate");
            }
        }
        else if (!StringUtils.hasText(plan.scopeSupplierId())
                || !plan.scopeSupplierId().equalsIgnoreCase(candidate.supplierId())) {
            throw new IllegalArgumentException(
                    "Disposal plan supplier scope does not match the comparison candidate");
        }
        DisposalSimulation planSimulation = plan.simulation();
        if (planSimulation == null) {
            return;
        }
        if (planSimulation.scenario() != candidate.scenario()) {
            throw new IllegalArgumentException(
                    "Disposal plan simulation scenario does not match the comparison candidate");
        }
        if (comparison.scenario() == SimulationScenario.INVENTORY_REPLENISHMENT) {
            if (!Objects.equals(planSimulation.replenishmentQuantity(), candidate.replenishmentQuantity())
                    || !StringUtils.hasText(planSimulation.sku())
                    || !planSimulation.sku().equalsIgnoreCase(candidate.sku())) {
                throw new IllegalArgumentException(
                        "Disposal plan simulation does not match the comparison candidate parameters");
            }
        }
        else if (!Objects.equals(planSimulation.additionalDelayDays(), candidate.additionalDelayDays())
                || !StringUtils.hasText(planSimulation.supplierId())
                || !planSimulation.supplierId().equalsIgnoreCase(candidate.supplierId())) {
            throw new IllegalArgumentException(
                    "Disposal plan simulation does not match the comparison candidate parameters");
        }
    }

    private static String defaultLabel(SimulationScenario scenario, int parameter) {
        return scenario == SimulationScenario.INVENTORY_REPLENISHMENT ? "补货 +" + parameter + " 件"
                : "额外延期 " + parameter + " 天";
    }

    private static String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String fingerprint(SimulationBaseline baseline) {
        List<SupplyChainOrder> orders = baseline.orders()
            .stream()
            .sorted(Comparator.comparing(SupplyChainOrder::orderId))
            .toList();
        List<InventoryPosition> inventory = baseline.inventory()
            .stream()
            .sorted(Comparator.comparing(InventoryPosition::sku)
                .thenComparingInt(InventoryPosition::onHandQuantity)
                .thenComparingInt(InventoryPosition::reservedQuantity)
                .thenComparingInt(InventoryPosition::safetyStock))
            .toList();
        List<SupplierRiskProfile> suppliers = baseline.suppliers()
            .stream()
            .sorted(Comparator.comparing(SupplierRiskProfile::supplierId))
            .toList();
        try {
            byte[] payload = mapper.writeValueAsBytes(
                    new BaselineFingerprintPayload(baseline.asOfDate(), orders, inventory, suppliers));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        }
        catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Unable to fingerprint the comparison baseline", exception);
        }
    }

    private static List<String> assumptions(List<DisposalScenarioCandidate> candidates, SimulationBaseline baseline,
            String fingerprint) {
        Set<String> assumptions = new LinkedHashSet<>();
        assumptions.add("全部候选共用同一份基线快照：asOf=" + baseline.asOfDate() + "，指纹 " + fingerprint + "。");
        assumptions.add("候选参数来自请求，模拟指标与排名由确定性 Java 规则计算，模型不得修改。");
        for (DisposalScenarioCandidate candidate : candidates) {
            assumptions.addAll(candidate.simulation().assumptions());
        }
        return List.copyOf(assumptions);
    }

    private static List<String> limitations(List<DisposalScenarioCandidate> candidates) {
        Set<String> limitations = new LinkedHashSet<>(candidates.get(0).simulation().limitations());
        limitations.add("对比只覆盖现有可计算指标：风险订单、高风险订单、缺口订单、缺口总量、ATP 与风险评分。");
        limitations.add("成本、预算、库容、产能、供应商承诺与实际到货日期未提供，排名不构成收益承诺。");
        return List.copyOf(limitations);
    }

    private static List<String> evidence(List<DisposalScenarioCandidate> candidates, SimulationMetrics baseline,
            String fingerprint, DisposalScenarioRankingResult rankingResult) {
        List<String> evidence = new ArrayList<>();
        evidence.add("共享基线指纹 " + fingerprint + "，来源 " + DisposalSimulationService.BASELINE_SOURCE + "。");
        evidence.add("规则版本 " + DisposalSimulation.RULE_VERSION + "；比较结果状态 "
                + DisposalSimulation.SIMULATION_STATUS + "。");
        evidence.add("基线指标：订单 " + baseline.totalOrders() + "，风险订单 " + baseline.atRiskOrders()
                + "，高风险 " + baseline.highRiskOrders() + "，缺口订单 " + baseline.ordersWithShortage()
                + "，缺口总量 " + baseline.totalShortageUnits() + "，目标 ATP "
                + baseline.targetSkuAvailableToPromise());
        for (DisposalScenarioCandidate candidate : candidates) {
            DisposalSimulation simulation = candidate.simulation();
            evidence.add("候选「" + candidate.label() + "」" + parameterSummary(candidate) + "：风险订单 "
                    + simulation.baseline().atRiskOrders() + " → " + simulation.simulated().atRiskOrders()
                    + "（" + signed(simulation.delta().atRiskOrders()) + "），高风险 "
                    + simulation.simulated().highRiskOrders() + "（" + signed(simulation.delta().highRiskOrders())
                    + "），缺口总量 " + simulation.simulated().totalShortageUnits() + "（"
                    + signed(simulation.delta().totalShortageUnits()) + "），受影响订单 "
                    + simulation.affectedOrders().size() + "，模拟编号 " + simulation.simulationId()
                    + "，规则版本 " + simulation.ruleVersion() + "，剩余数据缺口 "
                    + simulation.dataGaps().size() + " 条");
        }
        evidence.addAll(rankingResult.recommendation().evidence());
        evidence.add("排名规则由确定性 Java 引擎生成并持久化，共 " + rankingResult.rankingRules().size() + " 条。");
        return List.copyOf(evidence);
    }

    private static String parameterSummary(DisposalScenarioCandidate candidate) {
        return candidate.scenario() == SimulationScenario.INVENTORY_REPLENISHMENT
                ? "补货 +" + candidate.replenishmentQuantity() + " 件"
                : "额外延期 " + candidate.additionalDelayDays() + " 天";
    }

    private static String signed(int value) {
        return value > 0 ? "+" + value : Integer.toString(value);
    }

    private record BaselineFingerprintPayload(LocalDate asOfDate, List<SupplyChainOrder> orders,
            List<InventoryPosition> inventory, List<SupplierRiskProfile> suppliers) {
    }
}
