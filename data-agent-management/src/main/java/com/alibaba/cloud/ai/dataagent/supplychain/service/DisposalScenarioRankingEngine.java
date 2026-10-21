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

import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioCandidate;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioComparisonOutcome;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioRanking;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioRankingResult;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioRecommendation;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalSimulation;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SimulationDelta;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SimulationMetrics;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SimulationScenario;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic multi-candidate ranking. All candidates must share one baseline snapshot,
 * scenario and target scope. Ranking is lexicographic over existing simulation metrics; the
 * model never produces or adjusts a rank.
 */
@Service
public class DisposalScenarioRankingEngine {

    public static final List<String> RANKING_RULES = List.of(
            "候选必须为同一场景、同一 SKU/供应商范围，并共用同一份基线快照；否则不生成排名。",
            "主排序：高风险订单变化量（模拟后 - 基线），数值越小越好。",
            "次排序：风险订单变化量，数值越小越好。",
            "第三排序：存在缺口的订单变化量，数值越小越好。",
            "第四排序：缺口总量变化量（件），数值越小越好。",
            "第五排序：风险评分合计变化量，数值越小越好。",
            "第六排序（仅库存补货）：模拟后目标 SKU ATP，数值越小越优先；风险改善相同时按最小干预决胜，"
                    + "避免在缺少成本、库容数据时过度补货。",
            "全部排序指标相同视为并列；并列时按干预量（补货件数/延期天数）更小者推荐，其次按输入顺序。",
            "排序不使用成本、预算、产能、供应商承诺、收益或模型评分等未提供或非确定性数据。");

    private static final String DISCLAIMER =
            "排名与推荐由确定性 Java 规则基于现有订单、库存与供应商指标计算；不代表实际执行结果，也不构成成本或收益承诺。";

    private static final Comparator<CandidateMetric> METRIC_ORDER = Comparator
        .comparingInt(CandidateMetric::highRiskDelta)
        .thenComparingInt(CandidateMetric::atRiskDelta)
        .thenComparingInt(CandidateMetric::shortageOrderDelta)
        .thenComparingInt(CandidateMetric::shortageUnitsDelta)
        .thenComparingInt(CandidateMetric::riskScoreDelta)
        .thenComparing(CandidateMetric::targetAtp, Comparator.nullsLast(Comparator.naturalOrder()));

    private static final Comparator<CandidateMetric> INTERVENTION_ORDER = Comparator
        .comparingInt(CandidateMetric::magnitude)
        .thenComparingInt(metric -> metric.candidate().position());

    public DisposalScenarioRankingResult rank(SimulationMetrics baseline,
            List<DisposalScenarioCandidate> candidates) {
        List<String> issues = comparabilityIssues(baseline, candidates);
        if (!issues.isEmpty()) {
            return incomparable(candidates, issues);
        }
        List<CandidateMetric> metrics = candidates.stream().map(candidate -> metric(candidate)).toList();
        List<CandidateMetric> sorted = new ArrayList<>(metrics);
        sorted.sort(METRIC_ORDER);

        List<List<CandidateMetric>> groups = new ArrayList<>();
        for (CandidateMetric metric : sorted) {
            if (groups.isEmpty() || !sameMetricTuple(groups.get(groups.size() - 1).get(0), metric)) {
                groups.add(new ArrayList<>());
            }
            groups.get(groups.size() - 1).add(metric);
        }
        CandidateMetric winner = groups.get(0).stream().min(INTERVENTION_ORDER).orElseThrow();
        boolean tied = groups.get(0).size() > 1;
        DisposalScenarioComparisonOutcome outcome = tied ? DisposalScenarioComparisonOutcome.TIE_BREAK
                : DisposalScenarioComparisonOutcome.RECOMMENDED;

        Map<String, Integer> ranks = new HashMap<>();
        Map<String, Boolean> tiedFlags = new HashMap<>();
        for (int index = 0; index < groups.size(); index++) {
            List<CandidateMetric> group = groups.get(index);
            for (CandidateMetric metric : group) {
                ranks.put(metric.candidate().candidateId(), index + 1);
                tiedFlags.put(metric.candidate().candidateId(), group.size() > 1);
            }
        }

        List<DisposalScenarioRanking> rankings = metrics.stream()
            .map(metric -> ranking(metric, winner, ranks.get(metric.candidate().candidateId()),
                    tiedFlags.get(metric.candidate().candidateId())))
            .toList();
        DisposalScenarioRecommendation recommendation = recommendation(metrics, groups, winner, outcome);
        return new DisposalScenarioRankingResult(rankings, recommendation, RANKING_RULES);
    }

    private static List<String> comparabilityIssues(SimulationMetrics baseline,
            List<DisposalScenarioCandidate> candidates) {
        List<String> issues = new ArrayList<>();
        if (baseline == null) {
            issues.add("缺少共享基线指标");
        }
        if (candidates == null || candidates.size() < 2 || candidates.size() > 5) {
            issues.add("候选数量必须为 2 到 5 个");
            return List.copyOf(issues);
        }
        SimulationScenario scenario = null;
        String targetSku = null;
        String targetSupplier = null;
        for (DisposalScenarioCandidate candidate : candidates) {
            if (candidate == null || candidate.simulation() == null) {
                issues.add("候选缺少模拟结果");
                continue;
            }
            DisposalSimulation simulation = candidate.simulation();
            if (simulation.baseline() == null || simulation.simulated() == null
                    || simulation.delta() == null) {
                issues.add("候选模拟结果缺少指标");
            }
            if (candidate.scenario() != null && candidate.scenario() != simulation.scenario()) {
                issues.add("候选场景与模拟结果不一致");
            }
            if (scenario == null) {
                scenario = simulation.scenario();
                targetSku = normalize(candidate.sku());
                targetSupplier = normalize(candidate.supplierId());
            }
            else {
                if (simulation.scenario() != scenario) {
                    issues.add("候选场景不一致");
                }
                if (!Objects.equals(targetSku, normalize(candidate.sku()))
                        || !Objects.equals(targetSupplier, normalize(candidate.supplierId()))) {
                    issues.add("候选 SKU/供应商范围不一致");
                }
            }
            if (baseline != null && !baseline.equals(simulation.baseline())) {
                issues.add("候选基线指标不一致");
            }
            if (simulation.scenario() == SimulationScenario.INVENTORY_REPLENISHMENT
                    && candidate.replenishmentQuantity() == null) {
                issues.add("库存补货候选缺少补货数量");
            }
            if (simulation.scenario() == SimulationScenario.SUPPLIER_DELAY
                    && candidate.additionalDelayDays() == null) {
                issues.add("供应商延期候选缺少延期天数");
            }
        }
        return issues.stream().distinct().toList();
    }

    private static DisposalScenarioRankingResult incomparable(List<DisposalScenarioCandidate> candidates,
            List<String> issues) {
        List<DisposalScenarioRanking> rankings = candidates == null ? List.of()
                : candidates.stream()
                    .filter(Objects::nonNull)
                    .map(candidate -> new DisposalScenarioRanking(candidate.candidateId(), label(candidate),
                            parameterSummary(candidate), null, false, false, false,
                            "无法比较：" + String.join("；", issues), List.of(),
                            List.of("候选之间缺少可比条件，未参与排名。"), List.of("请统一场景、范围和基线后重新对比。")))
                    .toList();
        DisposalScenarioRecommendation recommendation = new DisposalScenarioRecommendation(
                DisposalScenarioComparisonOutcome.INCOMPARABLE, null, null, null,
                "候选之间缺少可比条件，未生成推荐。", List.of(), issues,
                List.of("不强行给出绝对胜出者；需要先统一场景、SKU/供应商范围和基线。"), List.of(), issues,
                List.of("人工核对候选的场景、范围与基线快照"), List.of(), DISCLAIMER);
        return new DisposalScenarioRankingResult(rankings, recommendation, RANKING_RULES);
    }

    private static CandidateMetric metric(DisposalScenarioCandidate candidate) {
        SimulationDelta delta = candidate.simulation().delta();
        return new CandidateMetric(candidate, delta.highRiskOrders(), delta.atRiskOrders(),
                delta.ordersWithShortage(), delta.totalShortageUnits(), delta.totalRiskScore(),
                candidate.simulation().simulated().targetSkuAvailableToPromise(), magnitude(candidate));
    }

    private static DisposalScenarioRanking ranking(CandidateMetric metric, CandidateMetric winner, int rank,
            boolean tied) {
        DisposalScenarioCandidate candidate = metric.candidate();
        boolean recommended = candidate.candidateId().equals(winner.candidate().candidateId());
        List<String> reasons = new ArrayList<>();
        if (recommended) {
            reasons.add("在共享基线下，该候选在排序指标上最优。");
            if (tied) {
                reasons.add("与其他候选在全部排序指标上并列，按最小干预规则（干预量更小）推荐。");
            }
        }
        else {
            reasons.add(comparisonReason(metric, winner));
        }
        return new DisposalScenarioRanking(candidate.candidateId(), label(candidate), parameterSummary(candidate),
                rank, tied, recommended, true, rankingBasis(metric, rank, tied, recommended), keyDeltas(metric),
                reasons, tradeoffs(metric, winner, recommended));
    }

    private static String rankingBasis(CandidateMetric metric, int rank, boolean tied, boolean recommended) {
        StringBuilder basis = new StringBuilder("排序键：高风险 ")
            .append(signed(metric.highRiskDelta()))
            .append(" / 风险 ")
            .append(signed(metric.atRiskDelta()))
            .append(" / 缺口订单 ")
            .append(signed(metric.shortageOrderDelta()))
            .append(" / 缺口总量 ")
            .append(signed(metric.shortageUnitsDelta()))
            .append(" / 风险评分 ")
            .append(signed(metric.riskScoreDelta()));
        if (metric.targetAtp() != null) {
            basis.append(" / ATP ").append(metric.targetAtp());
        }
        basis.append("；第 ").append(rank).append(" 名");
        if (tied) {
            basis.append("（并列）");
        }
        if (recommended) {
            basis.append("；最小干预推荐");
        }
        return basis.toString();
    }

    private static List<String> keyDeltas(CandidateMetric metric) {
        DisposalSimulation simulation = metric.candidate().simulation();
        List<String> deltas = new ArrayList<>();
        deltas.add("高风险订单 " + simulation.baseline().highRiskOrders() + " → "
                + simulation.simulated().highRiskOrders() + "（" + signed(metric.highRiskDelta()) + "）");
        deltas.add("风险订单 " + simulation.baseline().atRiskOrders() + " → "
                + simulation.simulated().atRiskOrders() + "（" + signed(metric.atRiskDelta()) + "）");
        deltas.add("存在缺口的订单 " + simulation.baseline().ordersWithShortage() + " → "
                + simulation.simulated().ordersWithShortage() + "（" + signed(metric.shortageOrderDelta()) + "）");
        deltas.add("缺口总量（件） " + simulation.baseline().totalShortageUnits() + " → "
                + simulation.simulated().totalShortageUnits() + "（" + signed(metric.shortageUnitsDelta()) + "）");
        deltas.add("风险评分合计 " + simulation.baseline().totalRiskScore() + " → "
                + simulation.simulated().totalRiskScore() + "（" + signed(metric.riskScoreDelta()) + "）");
        if (metric.targetAtp() != null) {
            deltas.add("目标 SKU ATP " + simulation.baseline().targetSkuAvailableToPromise() + " → "
                    + simulation.simulated().targetSkuAvailableToPromise() + "（"
                    + signed(metric.targetAtp()
                            - simulation.baseline().targetSkuAvailableToPromise())
                    + "）");
        }
        return List.copyOf(deltas);
    }

    private static String comparisonReason(CandidateMetric metric, CandidateMetric winner) {
        if (sameMetricTuple(metric, winner)) {
            return "全部排序指标与推荐候选相同，但干预量更大（" + intervention(metric) + " vs "
                    + intervention(winner) + "），按最小干预规则排在并列组内。";
        }
        if (metric.highRiskDelta() != winner.highRiskDelta()) {
            return "高风险订单改善比推荐候选少 " + difference(metric.highRiskDelta(), winner.highRiskDelta())
                    + "（" + signed(metric.highRiskDelta()) + " vs " + signed(winner.highRiskDelta()) + "）。";
        }
        if (metric.atRiskDelta() != winner.atRiskDelta()) {
            return "风险订单改善比推荐候选少 " + difference(metric.atRiskDelta(), winner.atRiskDelta())
                    + "（" + signed(metric.atRiskDelta()) + " vs " + signed(winner.atRiskDelta()) + "）。";
        }
        if (metric.shortageOrderDelta() != winner.shortageOrderDelta()) {
            return "存在缺口的订单改善比推荐候选少 "
                    + difference(metric.shortageOrderDelta(), winner.shortageOrderDelta()) + "（"
                    + signed(metric.shortageOrderDelta()) + " vs " + signed(winner.shortageOrderDelta()) + "）。";
        }
        if (metric.shortageUnitsDelta() != winner.shortageUnitsDelta()) {
            return "缺口总量改善比推荐候选少 "
                    + difference(metric.shortageUnitsDelta(), winner.shortageUnitsDelta()) + " 件（"
                    + signed(metric.shortageUnitsDelta()) + " vs " + signed(winner.shortageUnitsDelta()) + "）。";
        }
        if (metric.riskScoreDelta() != winner.riskScoreDelta()) {
            return "风险评分改善比推荐候选少 "
                    + difference(metric.riskScoreDelta(), winner.riskScoreDelta()) + "（"
                    + signed(metric.riskScoreDelta()) + " vs " + signed(winner.riskScoreDelta()) + "）。";
        }
        if (metric.targetAtp() != null && winner.targetAtp() != null
                && !metric.targetAtp().equals(winner.targetAtp())) {
            return "风险指标相同，但模拟 ATP 为 " + metric.targetAtp() + "，推荐候选为 " + winner.targetAtp()
                    + "；按最小干预规则（ATP 增量更小）排序。";
        }
        return "与推荐候选存在干预量差异，按最小干预规则排序。";
    }

    private static List<String> tradeoffs(CandidateMetric metric, CandidateMetric winner, boolean recommended) {
        List<String> items = new ArrayList<>();
        if (recommended) {
            items.add("排名只覆盖现有风险与库存指标；补货成本、库容、到货时间与供应商产能未提供，需要人工确认。");
            if (sameMetricTuple(metric, winner) && metric.magnitude() != winner.magnitude()) {
                items.add("该候选因干预量更小而胜出，并非成本或收益最优。");
            }
            return List.copyOf(items);
        }
        if (sameMetricTuple(metric, winner)) {
            items.add("风险指标与推荐候选相同，但干预量更大（" + intervention(metric) + " vs "
                    + intervention(winner) + "），可能带来额外库存占用或更长延期。");
        }
        else {
            items.add("与推荐候选相比改善更小；选择该候选需要人工确认额外风险是否可以接受。");
        }
        if (metric.targetAtp() != null && winner.targetAtp() != null
                && !metric.targetAtp().equals(winner.targetAtp())) {
            items.add("模拟 ATP 为 " + metric.targetAtp() + "（推荐候选 " + winner.targetAtp()
                    + "）；可承诺量与库存占用不同，需要人工权衡。");
        }
        items.add("成本、预算、库容、产能与供应商承诺数据未提供，无法量化净收益。");
        return List.copyOf(items);
    }

    private static DisposalScenarioRecommendation recommendation(List<CandidateMetric> metrics,
            List<List<CandidateMetric>> groups, CandidateMetric winner,
            DisposalScenarioComparisonOutcome outcome) {
        List<String> reasons = new ArrayList<>();
        if (outcome == DisposalScenarioComparisonOutcome.TIE_BREAK) {
            reasons.add("候选「" + label(winner.candidate()) + "」等 " + groups.get(0).size()
                    + " 个候选在全部排序指标上并列。");
            reasons.add("按最小干预规则选择干预量更小的候选；并列候选在库存占用上的差异仍需人工确认。");
        }
        else {
            reasons.add("候选「" + label(winner.candidate()) + "」在共享基线下综合风险改善最优。");
            if (groups.size() > 1) {
                reasons.add("次优候选为「" + label(groups.get(1).get(0).candidate()) + "」，差异见各候选排序依据。");
            }
        }
        List<String> tradeoffs = new ArrayList<>(
                tradeoffs(winner, winner, true));
        if (outcome == DisposalScenarioComparisonOutcome.TIE_BREAK) {
            tradeoffs.add("推荐依据是最小干预，不是成本最优；并列候选之间没有绝对优劣。");
        }
        else if (groups.size() > 1) {
            tradeoffs.add("次优候选「" + label(groups.get(1).get(0).candidate()) + "」的取舍见对应排序依据。");
        }

        Set<String> assumptions = new LinkedHashSet<>();
        for (CandidateMetric metric : metrics) {
            assumptions.addAll(metric.candidate().simulation().assumptions());
        }
        assumptions.add("全部候选使用同一份基线快照，未修改真实库存、订单或供应商数据。");
        assumptions.add("排名规则固定，模型只能解释结果，不能修改候选、指标或名次。");

        List<String> unknowns = List.of(
                "补货/延期的成本、预算、库容与资金占用未提供。",
                "实际到货日期、供应商产能与恢复承诺未提供。",
                "客户改期、替代供应商、加急运输等外部动作未建模。");

        List<String> manualConfirmation = scenarioOf(winner.candidate()) == SimulationScenario.INVENTORY_REPLENISHMENT
                ? List.of("确认补货 " + winner.candidate().replenishmentQuantity() + " 件可在最早要求交付日前到货",
                        "确认补货不会被其他订单或临时预留占用",
                        "人工复核补货成本、库容与资金影响")
                : List.of("确认该供应商所有未交付订单统一增加 " + winner.candidate().additionalDelayDays()
                        + " 天的假设是否成立",
                        "人工确认是否需要加急运输、替代供应商或客户改期",
                        "人工复核延期对客户承诺与成本的影响");

        List<String> evidence = new ArrayList<>();
        SimulationMetrics baseline = winner.candidate().simulation().baseline();
        evidence.add("共享基线：订单 " + baseline.totalOrders() + " 个，风险订单 " + baseline.atRiskOrders()
                + "，高风险 " + baseline.highRiskOrders() + "，缺口总量 " + baseline.totalShortageUnits()
                + " 件，目标 ATP " + baseline.targetSkuAvailableToPromise());
        for (CandidateMetric metric : metrics) {
            evidence.add("候选「" + label(metric.candidate()) + "」" + intervention(metric) + "："
                    + String.join("；", keyDeltas(metric)));
        }
        evidence.add("排序规则共 " + RANKING_RULES.size() + " 条，已随对比记录持久化。");

        return new DisposalScenarioRecommendation(outcome, winner.candidate().candidateId(),
                label(winner.candidate()), 1, summary(winner, groups.get(0).size()), keyDeltas(winner),
                List.copyOf(reasons), List.copyOf(tradeoffs), List.copyOf(assumptions), unknowns,
                manualConfirmation, List.copyOf(evidence), DISCLAIMER);
    }

    private static String summary(CandidateMetric winner, int tiedCandidates) {
        String prefix = tiedCandidates > 1
                ? "候选「" + label(winner.candidate()) + "」等 " + tiedCandidates + " 个候选在现有指标上并列，"
                        + "按最小干预规则推荐「" + label(winner.candidate()) + "」（第 1 名）："
                : "推荐候选「" + label(winner.candidate()) + "」（第 1 名）：";
        return prefix + "高风险订单 " + signed(winner.highRiskDelta()) + "，风险订单 "
                + signed(winner.atRiskDelta()) + "，缺口总量 " + signed(winner.shortageUnitsDelta()) + " 件"
                + (winner.targetAtp() == null ? "。" : "，模拟 ATP " + winner.targetAtp() + "。");
    }

    private static boolean sameMetricTuple(CandidateMetric left, CandidateMetric right) {
        return left.highRiskDelta() == right.highRiskDelta()
                && left.atRiskDelta() == right.atRiskDelta()
                && left.shortageOrderDelta() == right.shortageOrderDelta()
                && left.shortageUnitsDelta() == right.shortageUnitsDelta()
                && left.riskScoreDelta() == right.riskScoreDelta()
                && Objects.equals(left.targetAtp(), right.targetAtp());
    }

    private static int difference(int candidate, int winner) {
        return Math.abs(candidate - winner);
    }

    private static String intervention(CandidateMetric metric) {
        DisposalScenarioCandidate candidate = metric.candidate();
        return scenarioOf(candidate) == SimulationScenario.INVENTORY_REPLENISHMENT
                ? "补货 " + candidate.replenishmentQuantity() + " 件"
                : "额外延期 " + candidate.additionalDelayDays() + " 天";
    }

    private static String parameterSummary(DisposalScenarioCandidate candidate) {
        if (candidate == null) {
            return "未知候选";
        }
        if (scenarioOf(candidate) == SimulationScenario.INVENTORY_REPLENISHMENT) {
            return "补货 +" + candidate.replenishmentQuantity() + " 件";
        }
        return "额外延期 " + candidate.additionalDelayDays() + " 天";
    }

    private static SimulationScenario scenarioOf(DisposalScenarioCandidate candidate) {
        return candidate.scenario() != null ? candidate.scenario() : candidate.simulation().scenario();
    }

    private static int magnitude(DisposalScenarioCandidate candidate) {
        Integer value = scenarioOf(candidate) == SimulationScenario.INVENTORY_REPLENISHMENT
                ? candidate.replenishmentQuantity() : candidate.additionalDelayDays();
        return value == null ? Integer.MAX_VALUE : value;
    }

    private static String label(DisposalScenarioCandidate candidate) {
        if (candidate == null) {
            return "未知候选";
        }
        return candidate.label() == null || candidate.label().isBlank() ? parameterSummary(candidate)
                : candidate.label();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase();
    }

    private static String signed(int value) {
        return value > 0 ? "+" + value : Integer.toString(value);
    }

    private record CandidateMetric(DisposalScenarioCandidate candidate, int highRiskDelta, int atRiskDelta,
            int shortageOrderDelta, int shortageUnitsDelta, int riskScoreDelta, Integer targetAtp,
            int magnitude) {
    }
}
