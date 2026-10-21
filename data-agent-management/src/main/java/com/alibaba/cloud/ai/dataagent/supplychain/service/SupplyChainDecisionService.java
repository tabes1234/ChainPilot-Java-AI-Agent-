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

import com.alibaba.cloud.ai.dataagent.supplychain.model.DecisionIntent;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DecisionIntentType;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DecisionProposal;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DecisionTaskResult;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalPlan;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalRecommendation;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioCandidate;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioComparison;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioRanking;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalSimulation;
import com.alibaba.cloud.ai.dataagent.supplychain.model.KnowledgeCitation;
import com.alibaba.cloud.ai.dataagent.supplychain.model.OrderRiskAssessment;
import com.alibaba.cloud.ai.dataagent.supplychain.model.ProposalActionType;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskAnalysisReport;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxExecution;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxExecutionEligibility;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxExecutionSummary;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SimulationDelta;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SimulationMetrics;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SimulationScenario;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Deterministic decision orchestrator. Every number, rank and recommendation comes from an
 * existing Java tool before the model is asked to explain it; the model receives no write
 * tools and its answer is always followed by a deterministic evidence block.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupplyChainDecisionService {

    static final String NARRATIVE_MODEL_GROUNDED = "MODEL_GROUNDED";

    static final String NARRATIVE_DETERMINISTIC = "DETERMINISTIC";

    static final String NARRATIVE_DETERMINISTIC_FALLBACK = "DETERMINISTIC_FALLBACK";

    private static final int MAX_SUMMARY_LENGTH = 6000;

    private static final int MAX_EVIDENCE_ITEMS = 14;

    private static final String SYSTEM_PROMPT = "你是 ChainPilot 供应链决策助手。所有订单、库存、供应商、风险、"
            + "模拟、候选排名、推荐和执行状态都已由 Java 工具计算并给出。你只能引用给定证据中的事实和编号，"
            + "不得编造或推算候选 ID、指标、排名、推荐、库存、订单或执行结果；证据不足时必须明确说明。"
            + "不得声称已执行写操作。回答使用简洁中文，比较类问题必须点名实际候选 ID、排名和关键指标。";

    private final SupplyChainDecisionIntentParser intentParser;

    private final SupplyChainAgentTools agentTools;

    private final SupplyChainKnowledgeService knowledgeService;

    private final SupplyChainAnalysisService analysisService;

    private final ActionProposalService proposalService;

    private final SupplyChainEventRecorder eventRecorder;

    public DecisionIntent classify(String taskId, String question) {
        DecisionIntent intent = intentParser.parse(question);
        eventRecorder.intentRecognized(taskId, intent.type().name(), intentDescription(intent));
        return intent;
    }

    public DecisionTaskResult decide(String taskId, String question) {
        return decide(taskId, question, classify(taskId, question));
    }

    public DecisionTaskResult decide(String taskId, String question, DecisionIntent intent) {
        Collection collected = new Collection();
        collected.toolCalls.add("intent:" + intent.type().name());
        if (intent.needsClarification()) {
            return clarificationResult(taskId, question, intent, collected, intent.clarification());
        }
        try {
            return switch (intent.type()) {
                case ANALYSIS -> analysisResult(taskId, question, intent, collected);
                case DISPOSAL_RECOMMENDATIONS -> recommendations(taskId, question, intent, collected);
                case SIMULATE_REPLENISHMENT, SIMULATE_SUPPLIER_DELAY -> simulation(taskId, question, intent,
                        collected);
                case COMPARE_REPLENISHMENT, COMPARE_SUPPLIER_DELAY -> comparison(taskId, question, intent, collected);
                case LOOKUP_COMPARISON -> lookupComparison(taskId, question, intent, collected);
                case LOOKUP_PLAN -> lookupPlan(taskId, question, intent, collected);
                case LOOKUP_EXECUTION -> lookupExecution(taskId, question, intent, collected);
                case PROPOSE_SAVE_PLAN -> proposeSavePlan(taskId, question, intent, collected);
                case PROPOSE_LINK_PLAN -> proposeLinkPlan(taskId, question, intent, collected);
                case PROPOSE_SELECT_PLAN -> proposeSelectPlan(taskId, question, intent, collected);
                case PROPOSE_START_SANDBOX -> proposeStartSandbox(taskId, question, intent, collected);
                case UNSUPPORTED -> clarificationResult(taskId, question, intent, collected,
                        StringUtils.hasText(intent.clarification()) ? intent.clarification()
                                : "当前请求暂不支持。");
            };
        }
        catch (RuntimeException exception) {
            eventRecorder.fallbackUsed(taskId, "DECISION", exception.getClass().getSimpleName());
            throw exception;
        }
    }

    public DecisionTaskResult fromAnalysisResult(String taskId, String question, RiskAnalysisReport report) {
        DecisionIntent intent = intentParser.parse(question);
        List<OrderRiskAssessment> scoped = filterOrders(report.atRiskOrders(), intent.sku(), intent.supplierId());
        List<String> evidence = new ArrayList<>();
        evidence.add("分析工具返回 " + report.atRiskOrders().size() + " 个风险订单，当前范围 "
                + scoped.size() + " 个；高风险 " + scoped.stream()
                    .filter(item -> item.riskLevel().name().equals("HIGH"))
                    .count() + " 个。");
        if (StringUtils.hasText(intent.sku())) {
            evidence.add("范围 SKU：" + intent.sku());
        }
        if (StringUtils.hasText(intent.supplierId())) {
            evidence.add("范围供应商：" + intent.supplierId());
        }
        evidence.add("规则建议 " + report.recommendations().size() + " 条；知识引用 "
                + report.citations().size() + " 条；工具调用 " + report.toolCalls().size() + " 次。");
        String footer = footer(evidence);
        String summary = appendFooter(report.summary(), footer);
        boolean modelAssisted = report.modelAssisted();
        return new DecisionTaskResult(taskId, question, DecisionIntentType.ANALYSIS.name(), "COMPLETED", summary,
                modelAssisted ? NARRATIVE_MODEL_GROUNDED : NARRATIVE_DETERMINISTIC_FALLBACK, modelAssisted,
                !modelAssisted, false, List.of(), report.toolCalls(), report.executionPlan(), report.plannerAssisted(),
                scoped, report.recommendations(), List.of(), null, null, null, null, null, List.of(),
                report.citations(), evidence, false, Instant.now());
    }

    private DecisionTaskResult recommendations(String taskId, String question, DecisionIntent intent,
            Collection collected) {
        List<DisposalRecommendation> recommendations = call(collected, "findDisposalRecommendations",
                () -> agentTools.findDisposalRecommendationsForWorkflow(intent.sku(), intent.supplierId()));
        List<OrderRiskAssessment> orders = call(collected, "findAtRiskOrders",
                agentTools::findAtRiskOrdersForWorkflow);
        List<OrderRiskAssessment> scoped = filterOrders(orders, intent.sku(), intent.supplierId());
        List<KnowledgeCitation> citations = knowledgeService.retrieve(question);
        List<String> evidence = new ArrayList<>();
        evidence.add("范围：SKU=" + display(intent.sku()) + "，供应商=" + display(intent.supplierId())
                + "；订单风险工具返回 " + scoped.size() + " 个风险订单。");
        appendRecommendationEvidence(evidence, recommendations);
        appendCitationEvidence(evidence, citations);
        String deterministic = recommendations.isEmpty()
                ? "当前范围内没有生成处置建议。"
                : "基于真实订单、库存和供应商数据生成 " + recommendations.size() + " 条处置建议；最高优先级建议为 "
                        + recommendations.get(0).title() + "（" + recommendations.get(0).recommendationId() + "）。";
        Narrative narrative = narrate(taskId, question, intent, evidence, deterministic);
        return result(taskId, question, intent, collected, narrative, scoped, recommendations, null, null, null, null,
                null, null, citations, evidence, List.of());
    }

    private DecisionTaskResult simulation(String taskId, String question, DecisionIntent intent,
            Collection collected) {
        DisposalSimulation simulation;
        if (intent.type() == DecisionIntentType.SIMULATE_REPLENISHMENT) {
            simulation = call(collected, "simulateInventoryReplenishment", () -> agentTools
                .simulateInventoryReplenishmentForWorkflow(intent.sku(), intent.replenishmentQuantity()));
        }
        else {
            simulation = call(collected, "simulateSupplierDelay", () -> agentTools
                .simulateSupplierDelayForWorkflow(intent.supplierId(), intent.additionalDelayDays()));
        }
        List<KnowledgeCitation> citations = knowledgeService.retrieve(question);
        List<String> evidence = new ArrayList<>();
        evidence.add(simulationEvidence(simulation));
        appendCitationEvidence(evidence, citations);
        String deterministic = simulationSummary(simulation);
        Narrative narrative = narrate(taskId, question, intent, evidence, deterministic);
        return result(taskId, question, intent, collected, narrative, List.of(), List.of(), simulation, null, null,
                null, null, null, citations, evidence, List.of());
    }

    private DecisionTaskResult comparison(String taskId, String question, DecisionIntent intent,
            Collection collected) {
        DisposalScenarioComparison comparison;
        if (intent.type() == DecisionIntentType.COMPARE_REPLENISHMENT) {
            String csv = intent.replenishmentQuantities().stream().map(String::valueOf)
                .collect(Collectors.joining(","));
            comparison = call(collected, "compareDisposalScenarios", () -> agentTools
                .compareReplenishmentForWorkflow(intent.sku(), csv));
        }
        else {
            String csv = intent.additionalDelayDaysOptions().stream().map(String::valueOf)
                .collect(Collectors.joining(","));
            comparison = call(collected, "compareSupplierDelayScenarios", () -> agentTools
                .compareSupplierDelayForWorkflow(intent.supplierId(), csv));
        }
        List<KnowledgeCitation> citations = knowledgeService.retrieve(question);
        List<String> evidence = new ArrayList<>();
        appendComparisonEvidence(evidence, comparison);
        appendCitationEvidence(evidence, citations);
        String deterministic = comparisonSummary(comparison);
        Narrative narrative = narrate(taskId, question, intent, evidence, deterministic);
        return result(taskId, question, intent, collected, narrative, List.of(), List.of(), null, comparison, null,
                null, null, null, citations, evidence, List.of());
    }

    private DecisionTaskResult lookupComparison(String taskId, String question, DecisionIntent intent,
            Collection collected) {
        DisposalScenarioComparison comparison = call(collected, "findScenarioComparison",
                () -> agentTools.findScenarioComparisonForWorkflow(intent.comparisonId()));
        List<String> evidence = new ArrayList<>();
        appendComparisonEvidence(evidence, comparison);
        String deterministic = comparisonSummary(comparison);
        return result(taskId, question, intent, collected, Narrative.deterministic(deterministic), List.of(),
                List.of(), null, comparison, null, null, null, null, List.of(), evidence, List.of());
    }

    private DecisionTaskResult lookupPlan(String taskId, String question, DecisionIntent intent,
            Collection collected) {
        DisposalPlan plan = call(collected, "findDisposalPlan",
                () -> agentTools.findDisposalPlanForWorkflow(intent.planId()));
        SandboxExecutionEligibility eligibility = call(collected, "checkSandboxExecutionEligibility",
                () -> agentTools.checkSandboxExecutionEligibilityForWorkflow(plan.planId()));
        List<SandboxExecutionSummary> executions = call(collected, "findSandboxExecutions",
                () -> agentTools.findSandboxExecutionsForWorkflow(plan.planId(), 5));
        List<String> evidence = new ArrayList<>();
        evidence.add(planEvidence(plan));
        evidence.add(eligibilityEvidence(eligibility));
        evidence.add("最近沙箱执行记录 " + executions.size() + " 条"
                + (executions.isEmpty() ? "。" : "：" + executions.stream()
                    .map(summary -> summary.executionId() + "(" + summary.status() + ")")
                    .collect(Collectors.joining("、")) + "。"));
        String deterministic = "方案 " + plan.planId() + " 当前状态 " + plan.status() + "，共 "
                + plan.recommendations().size() + " 条推荐建议；沙箱执行条件："
                + (eligibility.eligible() ? "满足" : "不满足（" + display(eligibility.reason()) + "）") + "。";
        return result(taskId, question, intent, collected, Narrative.deterministic(deterministic), List.of(),
                plan.recommendations(), null, null, plan, null, eligibility, null, List.of(), evidence, List.of());
    }

    private DecisionTaskResult lookupExecution(String taskId, String question, DecisionIntent intent,
            Collection collected) {
        SandboxExecution execution;
        DisposalPlan plan = null;
        if (StringUtils.hasText(intent.executionId())) {
            execution = call(collected, "findSandboxExecution",
                    () -> agentTools.findSandboxExecutionForWorkflow(intent.executionId()));
        }
        else {
            List<SandboxExecutionSummary> executions = call(collected, "findSandboxExecutions",
                    () -> agentTools.findSandboxExecutionsForWorkflow(intent.planId(), 5));
            if (executions.isEmpty()) {
                List<String> evidence = List.of("方案 " + intent.planId() + " 暂无沙箱执行记录。");
                String summary = "方案 " + intent.planId() + " 没有已持久化的沙箱执行记录。";
                return result(taskId, question, intent, collected, Narrative.deterministic(summary), List.of(),
                        List.of(), null, null, null, null, null, null, List.of(), evidence, List.of());
            }
            execution = call(collected, "findSandboxExecution", () -> agentTools
                .findSandboxExecutionForWorkflow(executions.get(0).executionId()));
        }
        if (StringUtils.hasText(execution.disposalPlanId())) {
            try {
                plan = call(collected, "findDisposalPlan",
                        () -> agentTools.findDisposalPlanForWorkflow(execution.disposalPlanId()));
            }
            catch (RuntimeException exception) {
                log.info("Linked disposal plan unavailable while reading execution; continuing with execution only");
            }
        }
        List<String> evidence = new ArrayList<>();
        evidence.add(executionEvidence(execution));
        String deterministic = "沙箱执行 " + execution.executionId() + " 当前状态 " + execution.status()
                + "，共 " + execution.steps().size() + " 个步骤；最终结果："
                + display(execution.finalResult()) + "。";
        return result(taskId, question, intent, collected, Narrative.deterministic(deterministic), List.of(),
                plan == null ? List.of() : plan.recommendations(), null, null, plan, execution, null, null,
                List.of(), evidence, List.of());
    }

    private DecisionTaskResult proposeSavePlan(String taskId, String question, DecisionIntent intent,
            Collection collected) {
        DisposalScenarioComparison comparison = call(collected, "findScenarioComparison",
                () -> agentTools.findScenarioComparisonForWorkflow(intent.comparisonId()));
        String candidateId = intent.candidateId();
        List<String> notes = new ArrayList<>();
        if (!StringUtils.hasText(candidateId)) {
            candidateId = comparison.recommendation().recommendedCandidateId();
            if (!StringUtils.hasText(candidateId)) {
                return clarificationResult(taskId, question, intent, collected,
                        "对比结果没有唯一推荐候选，请提供要保存的候选编号（candidateId）。");
            }
            notes.add("未指定候选，使用确定性对比推荐的候选 " + candidateId + "。");
        }
        DisposalScenarioCandidate candidate = requireCandidate(comparison, candidateId);
        List<String> evidence = new ArrayList<>();
        appendComparisonEvidence(evidence, comparison);
        evidence.addAll(notes);
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("comparisonId", comparison.comparisonId());
        parameters.put("title", "处置方案：" + comparison.title() + " / " + candidate.label());
        parameters.put("generateExplanation", false);
        DecisionProposal proposal = tryPropose(taskId, ProposalActionType.SAVE_CANDIDATE_AS_PLAN,
                "保存候选 " + candidate.label() + " 为处置方案", candidate.candidateId(), parameters, evidence);
        return proposalResult(taskId, question, intent, collected, proposal, comparison, null, null, evidence,
                List.of());
    }

    private DecisionTaskResult proposeLinkPlan(String taskId, String question, DecisionIntent intent,
            Collection collected) {
        DisposalScenarioComparison comparison = call(collected, "findScenarioComparison",
                () -> agentTools.findScenarioComparisonForWorkflow(intent.comparisonId()));
        DisposalScenarioCandidate candidate = requireCandidate(comparison, intent.candidateId());
        DisposalPlan plan = call(collected, "findDisposalPlan",
                () -> agentTools.findDisposalPlanForWorkflow(intent.planId()));
        List<String> evidence = new ArrayList<>();
        appendComparisonEvidence(evidence, comparison);
        evidence.add(planEvidence(plan));
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("comparisonId", comparison.comparisonId());
        parameters.put("planId", plan.planId());
        DecisionProposal proposal = tryPropose(taskId, ProposalActionType.LINK_CANDIDATE_TO_PLAN,
                "关联候选 " + candidate.label() + " 到方案 " + plan.planId(), candidate.candidateId(), parameters,
                evidence);
        return proposalResult(taskId, question, intent, collected, proposal, comparison, plan, null, evidence,
                List.of());
    }

    private DecisionTaskResult proposeSelectPlan(String taskId, String question, DecisionIntent intent,
            Collection collected) {
        DisposalPlan plan = call(collected, "findDisposalPlan",
                () -> agentTools.findDisposalPlanForWorkflow(intent.planId()));
        String recommendationId = intent.recommendationId();
        if (!StringUtils.hasText(recommendationId)) {
            if (plan.recommendations().size() == 1) {
                recommendationId = plan.recommendations().get(0).recommendationId();
            }
            else {
                return clarificationResult(taskId, question, intent, collected,
                        "方案 " + plan.planId() + " 包含 " + plan.recommendations().size()
                                + " 条推荐建议，请提供要选定的推荐编号（recommendationId）。");
            }
        }
        List<String> evidence = new ArrayList<>();
        evidence.add(planEvidence(plan));
        evidence.add("待选定推荐编号：" + recommendationId + "。");
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("recommendationId", recommendationId);
        DecisionProposal proposal = tryPropose(taskId, ProposalActionType.SELECT_PLAN,
                "选定方案 " + plan.planId() + " 的推荐建议", plan.planId(), parameters, evidence);
        return proposalResult(taskId, question, intent, collected, proposal, null, plan, null, evidence, List.of());
    }

    private DecisionTaskResult proposeStartSandbox(String taskId, String question, DecisionIntent intent,
            Collection collected) {
        DisposalPlan plan = call(collected, "findDisposalPlan",
                () -> agentTools.findDisposalPlanForWorkflow(intent.planId()));
        SandboxExecutionEligibility eligibility = call(collected, "checkSandboxExecutionEligibility",
                () -> agentTools.checkSandboxExecutionEligibilityForWorkflow(plan.planId()));
        List<String> evidence = new ArrayList<>();
        evidence.add(planEvidence(plan));
        evidence.add(eligibilityEvidence(eligibility));
        if (!eligibility.eligible()) {
            String summary = "当前不满足沙箱执行条件：" + display(eligibility.reason())
                    + "。未创建执行提案，也不会有任何执行记录被写入。";
            return result(taskId, question, intent, collected, Narrative.deterministic(summary), List.of(),
                    plan.recommendations(), null, null, plan, null, eligibility, null, List.of(), evidence,
                    List.of());
        }
        DecisionProposal proposal = tryPropose(taskId, ProposalActionType.START_SANDBOX_EXECUTION,
                "启动方案 " + plan.planId() + " 的沙箱演练", plan.planId(), Map.of(), evidence);
        return proposalResult(taskId, question, intent, collected, proposal, null, plan, eligibility, evidence,
                List.of());
    }

    private DecisionTaskResult analysisResult(String taskId, String question, DecisionIntent intent,
            Collection collected) {
        RiskAnalysisReport report = analysisService.analyze(question, taskId);
        collected.toolCalls.addAll(report.toolCalls());
        return fromAnalysisResult(taskId, question, report);
    }

    private DecisionProposal tryPropose(String taskId, ProposalActionType actionType, String title, String targetId,
            Map<String, Object> parameters, List<String> evidence) {
        try {
            TaskCancellationRegistry.check(taskId);
            var proposal = proposalService.create(taskId, actionType, title, targetId, parameters, evidence);
            try {
                TaskCancellationRegistry.check(taskId);
            }
            catch (SupplyChainTaskCancelledException exception) {
                proposalService.cancel(proposal.proposalId(), proposal.contentHash());
                throw exception;
            }
            return proposal;
        }
        catch (SupplyChainTaskCancelledException exception) {
            throw exception;
        }
        catch (RuntimeException exception) {
            log.info("ChainPilot action proposal was not created. taskId={} action={} causeType={} message={}",
                    taskId, actionType, exception.getClass().getSimpleName(), exception.getMessage());
            return null;
        }
    }

    private DecisionTaskResult proposalResult(String taskId, String question, DecisionIntent intent,
            Collection collected, DecisionProposal proposal, DisposalScenarioComparison comparison,
            DisposalPlan plan, SandboxExecutionEligibility eligibility, List<String> evidence,
            List<KnowledgeCitation> citations) {
        List<DecisionProposal> proposals = proposal == null ? List.of() : List.of(proposal);
        String deterministic;
        if (proposal == null) {
            deterministic = "未创建待确认提案。请检查对象状态与参数后重试；本请求没有执行任何写操作。";
        }
        else {
            deterministic = "已创建待确认提案 " + proposal.proposalId() + "：" + proposal.title()
                    + "。该提案不会自动执行，请在处置工作台确认或取消；确认时后端会重新校验对象状态、参数和幂等性。";
        }
        return result(taskId, question, intent, collected, Narrative.deterministic(deterministic), List.of(),
                plan == null ? List.of() : plan.recommendations(), null, comparison, plan, null, eligibility,
                proposals, citations, evidence, List.of());
    }

    private DecisionTaskResult clarificationResult(String taskId, String question, DecisionIntent intent,
            Collection collected, String message) {
        eventRecorder.clarificationRequired(taskId, intent.type().name(), message);
        List<String> evidence = List.of("未调用任何写服务；需要用户补充信息后才能继续。");
        return new DecisionTaskResult(taskId, question, intent.type().name(), "COMPLETED", message,
                NARRATIVE_DETERMINISTIC, false, false, true, List.of(message), collected.toolCalls,
                List.of("识别请求类型", "检查必要参数", "等待用户补充信息"), false, List.of(), List.of(), List.of(),
                null, null, null, null, null, List.of(), List.of(), evidence, false, Instant.now());
    }

    private DecisionTaskResult result(String taskId, String question, DecisionIntent intent, Collection collected,
            Narrative narrative, List<OrderRiskAssessment> atRiskOrders, List<DisposalRecommendation> recommendations,
            DisposalSimulation simulation, DisposalScenarioComparison comparison, DisposalPlan plan,
            SandboxExecution execution, SandboxExecutionEligibility eligibility, List<DecisionProposal> proposals,
            List<KnowledgeCitation> citations, List<String> evidence, List<String> clarifications) {
        List<String> recommendationNotes = recommendations.stream()
            .map(recommendation -> recommendation.recommendationId() + " | " + recommendation.priority() + " | "
                    + recommendation.title() + " | " + recommendation.expectedImprovement())
            .toList();
        List<String> planSteps = switch (intent.type()) {
            case COMPARE_REPLENISHMENT, COMPARE_SUPPLIER_DELAY -> List.of("识别对比场景与候选参数",
                    "调用确定性情景对比工具", "引用实际候选 ID、排名与指标生成解释");
            case SIMULATE_REPLENISHMENT, SIMULATE_SUPPLIER_DELAY -> List.of("识别模拟场景与参数",
                    "调用确定性情景模拟工具", "解释基线与模拟后的指标变化");
            case DISPOSAL_RECOMMENDATIONS -> List.of("识别 SKU/供应商范围", "调用处置建议与订单工具",
                    "基于业务依据生成解释");
            case PROPOSE_SAVE_PLAN, PROPOSE_LINK_PLAN, PROPOSE_SELECT_PLAN, PROPOSE_START_SANDBOX -> List.of(
                    "识别处置操作", "调用只读工具核对对象", "创建待人工确认的结构化提案");
            default -> List.of("识别查询意图", "调用只读工具", "返回持久化结果");
        };
        return new DecisionTaskResult(taskId, question, intent.type().name(), "COMPLETED", narrative.summary(),
                narrative.source(), narrative.modelAssisted(), narrative.fallbackUsed(), false, clarifications,
                List.copyOf(collected.toolCalls), planSteps, false, atRiskOrders, recommendationNotes,
                recommendations, simulation, comparison, plan, execution, eligibility, proposals, citations,
                evidence, false, Instant.now());
    }

    private Narrative narrate(String taskId, String question, DecisionIntent intent, List<String> evidence,
            String deterministicSummary) {
        String footer = footer(evidence);
        if (!usesModel(intent.type())) {
            return Narrative.deterministic(appendFooter(deterministicSummary, footer));
        }
        String evidenceTaskId = eventRecorder.currentTaskId();
        long started = System.nanoTime();
        eventRecorder.modelStarted(evidenceTaskId, "DECISION_SUMMARY");
        try {
            SupplyChainAnalysisService.ModelInvocation completion = analysisService.complete(SYSTEM_PROMPT,
                    narrativePrompt(question, intent, evidence), "DECISION_SUMMARY");
            String modelSummary = completion.content();
            if (StringUtils.hasText(modelSummary)) {
                eventRecorder.modelCompleted(evidenceTaskId, "DECISION_SUMMARY", started,
                        "decision narrative accepted; chars=" + modelSummary.length() + "; "
                                + completion.usageSummary());
                String trimmed = modelSummary.trim();
                if (trimmed.length() > MAX_SUMMARY_LENGTH) {
                    trimmed = trimmed.substring(0, MAX_SUMMARY_LENGTH);
                }
                return new Narrative(appendFooter(trimmed, footer), NARRATIVE_MODEL_GROUNDED, true, false);
            }
            eventRecorder.modelCompleted(evidenceTaskId, "DECISION_SUMMARY", started,
                    "model returned an empty decision narrative; " + completion.usageSummary());
            eventRecorder.fallbackUsed(evidenceTaskId, "DECISION_SUMMARY", "EMPTY_MODEL_RESPONSE");
        }
        catch (RuntimeException exception) {
            eventRecorder.modelFailed(evidenceTaskId, "DECISION_SUMMARY", started, exception);
            eventRecorder.fallbackUsed(evidenceTaskId, "DECISION_SUMMARY", exception.getClass().getSimpleName());
            log.info("ChainPilot decision narrative unavailable; using deterministic summary. taskId={} causeType={}",
                    taskId, exception.getClass().getSimpleName());
        }
        return new Narrative(appendFooter(deterministicSummary, footer), NARRATIVE_DETERMINISTIC_FALLBACK, false,
                true);
    }

    private static boolean usesModel(DecisionIntentType type) {
        return switch (type) {
            case DISPOSAL_RECOMMENDATIONS, SIMULATE_REPLENISHMENT, SIMULATE_SUPPLIER_DELAY,
                    COMPARE_REPLENISHMENT, COMPARE_SUPPLIER_DELAY -> true;
            default -> false;
        };
    }

    private static String narrativePrompt(String question, DecisionIntent intent, List<String> evidence) {
        return "用户请求：" + question + "\n意图：" + intent.type().name() + "\n\n确定性工具结果：\n"
                + String.join("\n", evidence) + "\n\n请只基于以上结果给出不超过 200 字的中文结论。"
                + "比较类请求必须引用实际候选 ID、排名和关键指标；如果没有推荐，明确说明不可比或缺少证据。"
                + "不要提出已执行任何操作，任何写操作都需要用户在前端确认提案。";
    }

    private static String footer(List<String> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return "证据：未取得业务数据。本结果未修改真实库存、订单、供应商或 ERP 数据。";
        }
        List<String> lines = evidence.stream().filter(StringUtils::hasText).limit(MAX_EVIDENCE_ITEMS).toList();
        return "确定性证据：\n- " + String.join("\n- ", lines)
                + "\n本结果只读；任何方案保存、选定或沙箱执行都需要用户确认，且不会修改真实业务数据。";
    }

    private static String appendFooter(String summary, String footer) {
        String base = StringUtils.hasText(summary) ? summary.trim() : "未生成结论。";
        return base + "\n\n" + footer;
    }

    private static List<OrderRiskAssessment> filterOrders(List<OrderRiskAssessment> orders, String sku,
            String supplierId) {
        return orders.stream()
            .filter(assessment -> !StringUtils.hasText(sku)
                    || sku.equalsIgnoreCase(assessment.order().sku()))
            .filter(assessment -> !StringUtils.hasText(supplierId)
                    || supplierId.equalsIgnoreCase(assessment.order().supplierId()))
            .toList();
    }

    private static DisposalScenarioCandidate requireCandidate(DisposalScenarioComparison comparison, String id) {
        return comparison.candidates()
            .stream()
            .filter(candidate -> candidate.candidateId().equals(id))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown comparison candidate: " + id));
    }

    private static void appendRecommendationEvidence(List<String> evidence,
            List<DisposalRecommendation> recommendations) {
        recommendations.stream().limit(8).forEach(recommendation -> evidence.add("建议 " + recommendation
            .recommendationId() + "｜" + recommendation.actionType() + "｜" + recommendation.priority()
            + "｜分数 " + recommendation.priorityScore() + "｜" + recommendation.title() + "｜依据："
            + String.join("；", recommendation.businessBasis()) + "｜预期：" + recommendation.expectedImprovement()));
    }

    private static void appendCitationEvidence(List<String> evidence, List<KnowledgeCitation> citations) {
        citations.stream().forEach(citation -> evidence.add("知识引用 " + citation.sourceId() + "《"
                + citation.title() + "》：" + citation.excerpt()));
    }

    private static void appendComparisonEvidence(List<String> evidence, DisposalScenarioComparison comparison) {
        evidence.add("对比 " + comparison.comparisonId() + "｜场景 " + comparison.scenario() + "｜共享基线 "
                + comparison.baselineFingerprint() + "｜候选 " + comparison.candidates().size() + " 个。");
        Map<String, DisposalScenarioCandidate> candidates = new LinkedHashMap<>();
        comparison.candidates().forEach(candidate -> candidates.put(candidate.candidateId(), candidate));
        for (DisposalScenarioRanking ranking : comparison.rankings()) {
            DisposalScenarioCandidate candidate = candidates.get(ranking.candidateId());
            SimulationDelta delta = candidate == null ? null : candidate.simulation().delta();
            evidence.add("候选 " + ranking.candidateId() + "｜" + ranking.label() + "｜排名 "
                    + (ranking.rank() == null ? "不可比" : ranking.rank()) + "｜推荐=" + ranking.recommended()
                    + "｜" + ranking.parameterSummary() + "｜风险订单Δ=" + deltaValue(delta, "atRiskOrders")
                    + "｜高风险Δ=" + deltaValue(delta, "highRiskOrders") + "｜缺货件Δ="
                    + deltaValue(delta, "totalShortageUnits") + "｜目标ATPΔ="
                    + deltaValue(delta, "targetSkuAvailableToPromise") + "｜依据：" + ranking.rankingBasis()
                    + "｜权衡：" + String.join("；", ranking.tradeoffs()));
        }
        evidence.add("对比结论：" + comparison.recommendation().summary());
        evidence.add("未知项：" + String.join("；", comparison.recommendation().unknowns()));
        evidence.add("需要人工确认：" + String.join("；", comparison.recommendation().manualConfirmation()));
    }

    private static String comparisonSummary(DisposalScenarioComparison comparison) {
        String recommendation = comparison.recommendation().recommendedCandidateId();
        if (!StringUtils.hasText(recommendation)) {
            return "对比 " + comparison.comparisonId() + " 完成，共 " + comparison.candidates().size()
                    + " 个候选；确定性结果为不可比或没有唯一推荐，请查看候选指标、权衡与未知项。";
        }
        DisposalScenarioRanking ranking = comparison.rankings()
            .stream()
            .filter(item -> item.candidateId().equals(recommendation))
            .findFirst()
            .orElse(null);
        return "对比 " + comparison.comparisonId() + " 完成，共 " + comparison.candidates().size()
                + " 个候选；确定性推荐候选 " + recommendation + "（" + (ranking == null ? "推荐"
                        : ranking.label() + "，排名 " + ranking.rank()) + "）。";
    }

    private static String simulationSummary(DisposalSimulation simulation) {
        SimulationMetrics baseline = simulation.baseline();
        SimulationMetrics simulated = simulation.simulated();
        SimulationDelta delta = simulation.delta();
        return "情景 " + simulation.scenario() + " 模拟完成：" + parameterText(simulation)
                + "；风险订单 " + baseline.atRiskOrders() + " → " + simulated.atRiskOrders()
                + "（Δ" + sign(delta.atRiskOrders()) + "），高风险 " + baseline.highRiskOrders() + " → "
                + simulated.highRiskOrders() + "（Δ" + sign(delta.highRiskOrders()) + "），缺货件 "
                + baseline.totalShortageUnits() + " → " + simulated.totalShortageUnits() + "（Δ"
                + sign(delta.totalShortageUnits()) + "）。";
    }

    private static String simulationEvidence(DisposalSimulation simulation) {
        SimulationMetrics baseline = simulation.baseline();
        SimulationMetrics simulated = simulation.simulated();
        SimulationDelta delta = simulation.delta();
        return "模拟 " + simulation.simulationId() + "｜" + simulation.scenario() + "｜" + parameterText(simulation)
                + "｜基线指纹 " + simulation.baselineSource() + "｜风险订单 " + baseline.atRiskOrders() + "→"
                + simulated.atRiskOrders() + "｜高风险 " + baseline.highRiskOrders() + "→"
                + simulated.highRiskOrders() + "｜缺货订单 " + baseline.ordersWithShortage() + "→"
                + simulated.ordersWithShortage() + "｜缺货件 " + baseline.totalShortageUnits() + "→"
                + simulated.totalShortageUnits() + "｜目标ATP " + displayMetric(baseline.targetSkuAvailableToPromise())
                + "→" + displayMetric(simulated.targetSkuAvailableToPromise()) + "｜受影响订单 "
                + simulation.affectedOrders().stream().map(impact -> impact.orderId())
                    .limit(10).collect(Collectors.joining("、")) + "｜Δ" + delta.atRiskOrders() + "/"
                + delta.highRiskOrders() + "/" + delta.totalShortageUnits();
    }

    private static String planEvidence(DisposalPlan plan) {
        return "方案 " + plan.planId() + "｜" + plan.title() + "｜状态 " + plan.status() + "｜SKU "
                + display(plan.scopeSku()) + "｜供应商 " + display(plan.scopeSupplierId()) + "｜推荐 "
                + plan.recommendations().size() + " 条｜模拟 "
                + (plan.simulation() == null ? "无" : plan.simulation().simulationId()) + "。";
    }

    private static String eligibilityEvidence(SandboxExecutionEligibility eligibility) {
        return "沙箱执行条件 " + (eligibility.eligible() ? "满足" : "不满足") + "：" + display(eligibility.reason())
                + "；检查项：" + String.join("；", eligibility.checks());
    }

    private static String executionEvidence(SandboxExecution execution) {
        return "执行 " + execution.executionId() + "｜方案 " + execution.disposalPlanId() + "｜状态 "
                + execution.status() + "｜动作 " + execution.actionType() + "｜步骤 " + execution.steps().size()
                + " 个（失败 " + execution.steps().stream()
                    .filter(step -> step.status().name().equals("FAILED")).count() + "）｜最终结果："
                + display(execution.finalResult()) + "｜未解决项："
                + String.join("；", execution.unresolvedItems());
    }

    private static String parameterText(DisposalSimulation simulation) {
        if (simulation.scenario() == SimulationScenario.INVENTORY_REPLENISHMENT) {
            return "补货数量 " + displayMetric(simulation.replenishmentQuantity()) + " 件，SKU "
                    + display(simulation.sku());
        }
        return "额外延期 " + displayMetric(simulation.additionalDelayDays()) + " 天，供应商 "
                + display(simulation.supplierId());
    }

    private static String deltaValue(SimulationDelta delta, String field) {
        if (delta == null) {
            return "无";
        }
        return switch (field) {
            case "atRiskOrders" -> sign(delta.atRiskOrders());
            case "highRiskOrders" -> sign(delta.highRiskOrders());
            case "totalShortageUnits" -> sign(delta.totalShortageUnits());
            case "targetSkuAvailableToPromise" -> delta.targetSkuAvailableToPromise() == null ? "无"
                    : sign(delta.targetSkuAvailableToPromise());
            default -> "无";
        };
    }

    private static String sign(int value) {
        return value > 0 ? "+" + value : String.valueOf(value);
    }

    private static String displayMetric(Integer value) {
        return value == null ? "无" : String.valueOf(value);
    }

    private static String display(String value) {
        return StringUtils.hasText(value) ? value : "无";
    }

    private static String intentDescription(DecisionIntent intent) {
        return switch (intent.type()) {
            case COMPARE_REPLENISHMENT -> "补货对比 SKU=" + display(intent.sku()) + "，数量="
                    + intent.replenishmentQuantities();
            case COMPARE_SUPPLIER_DELAY -> "供应商延期对比 supplier=" + display(intent.supplierId())
                    + "，天数=" + intent.additionalDelayDaysOptions();
            case SIMULATE_REPLENISHMENT -> "补货模拟 SKU=" + display(intent.sku()) + "，数量="
                    + displayMetric(intent.replenishmentQuantity());
            case SIMULATE_SUPPLIER_DELAY -> "供应商延期模拟 supplier=" + display(intent.supplierId())
                    + "，天数=" + displayMetric(intent.additionalDelayDays());
            case DISPOSAL_RECOMMENDATIONS -> "处置建议 SKU=" + display(intent.sku()) + "，supplier="
                    + display(intent.supplierId());
            default -> "target=" + firstText(intent.planId(), intent.comparisonId(), intent.executionId());
        };
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return "无";
    }

    private <T> T call(Collection collected, String toolName, Supplier<T> action) {
        collected.toolCalls.add(toolName);
        return action.get();
    }

    private record Narrative(String summary, String source, boolean modelAssisted, boolean fallbackUsed) {

        static Narrative deterministic(String summary) {
            return new Narrative(summary, NARRATIVE_DETERMINISTIC, false, false);
        }

    }

    private static final class Collection {

        private final List<String> toolCalls = new ArrayList<>();

    }

}
