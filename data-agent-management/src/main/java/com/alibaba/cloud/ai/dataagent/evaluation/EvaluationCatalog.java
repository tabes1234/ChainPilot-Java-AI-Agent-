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
package com.alibaba.cloud.ai.dataagent.evaluation;

import com.alibaba.cloud.ai.dataagent.evaluation.dataset.SupplyChainEvaluationDataset;
import com.alibaba.cloud.ai.dataagent.evaluation.dataset.SupplyChainEvaluationDataset.Kind;
import com.alibaba.cloud.ai.dataagent.evaluation.dataset.SupplyChainEvaluationDatasetLoader;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationCaseDefinition;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationMode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Merges the versioned deterministic dataset with the explicit workflow cases.
 *
 * <p>14 deterministic cases (risk / inventory / supplier / retrieval) plus 6 workflow
 * cases form the first catalogue version. Deterministic expectations are read from the
 * packaged dataset, never from the business code under test.
 */
@Service
public class EvaluationCatalog {

    public static final String WORKFLOW_VERSION = "workflow-1.0.0";

    private static final List<WorkflowEvaluationCase> WORKFLOW_CASES = List.of(
            new WorkflowEvaluationCase("WF-ANALYSIS-HIGH-RISK", "高风险订单分析",
                    "真实工作流：高风险订单分析", "请分析当前供应链中的高风险订单，并给出需要优先处理的建议。", "ANALYSIS",
                    "真实执行分析任务并生成包含风险订单的报告",
                    List.of("任务状态为 COMPLETED", "生成分析报告", "报告包含至少 1 个风险订单")),
            new WorkflowEvaluationCase("WF-ANALYSIS-INVENTORY-SHORTAGE", "库存缺口分析",
                    "真实工作流：库存缺口分析", "哪些订单存在库存缺口？请说明受影响的 SKU 和缺口数量。", "ANALYSIS",
                    "真实执行分析任务，报告中的风险订单应包含库存缺口",
                    List.of("任务状态为 COMPLETED", "生成分析报告", "风险订单中存在库存缺口")),
            new WorkflowEvaluationCase("WF-DECISION-COMPARE", "候选方案比较",
                    "真实工作流：决策候选方案比较", "请比较补货与催交付两类处置方案，给出推荐方案和理由。", "DECISION",
                    "真实执行决策任务，返回确定性排名的候选方案比较",
                    List.of("决策结果可读取", "包含至少 2 个候选方案", "未修改真实业务数据")),
            new WorkflowEvaluationCase("WF-DECISION-UNSUPPORTED", "不支持请求处理",
                    "真实工作流：不支持的业务请求", "帮我写一首关于春天的诗。", "DECISION",
                    "不支持的请求应被明确识别，不得伪造业务结果",
                    List.of("决策结果可读取", "标记为不支持或需要澄清", "未修改真实业务数据")),
            new WorkflowEvaluationCase("WF-DECISION-EVIDENCE", "决策证据完整性",
                    "真实工作流：决策证据与工具调用", "针对当前高风险订单生成处置建议，并列出你的依据。", "DECISION",
                    "决策结果应包含处置建议以及可追溯的依据",
                    List.of("决策结果可读取", "至少 1 条处置建议", "存在证据、引用或工具调用")));

    private final SupplyChainEvaluationDataset dataset;

    private final List<EvaluationCaseDefinition> deterministicCases;

    private final List<EvaluationCaseDefinition> agentCases;

    private final Map<String, EvaluationCaseDefinition> definitionsById;

    private final Map<String, SupplyChainEvaluationDataset.EvaluationCase> datasetCasesById;

    private final Map<String, WorkflowEvaluationCase> workflowCasesById;

    public EvaluationCatalog(SupplyChainEvaluationDatasetLoader loader) {
        this.dataset = loader.dataset();
        List<EvaluationCaseDefinition> deterministic = new ArrayList<>();
        Map<String, SupplyChainEvaluationDataset.EvaluationCase> datasetById = new LinkedHashMap<>();
        for (SupplyChainEvaluationDataset.EvaluationCase evaluationCase : dataset.cases()) {
            datasetById.put(evaluationCase.id(), evaluationCase);
            if (evaluationCase.kind() != Kind.REPORT_FALLBACK) {
                deterministic.add(toDefinition(evaluationCase));
            }
        }
        this.deterministicCases = List.copyOf(deterministic);
        this.datasetCasesById = Map.copyOf(datasetById);

        Map<String, WorkflowEvaluationCase> workflowById = new LinkedHashMap<>();
        WORKFLOW_CASES.forEach(workflow -> workflowById.put(workflow.id(), workflow));
        this.workflowCasesById = Map.copyOf(workflowById);

        List<EvaluationCaseDefinition> agent = new ArrayList<>();
        dataset.cases().stream()
            .filter(evaluationCase -> evaluationCase.kind() == Kind.REPORT_FALLBACK)
            .findFirst()
            .ifPresent(evaluationCase -> agent.add(toFallbackDefinition(evaluationCase)));
        WORKFLOW_CASES.forEach(workflow -> agent.add(toDefinition(workflow)));
        this.agentCases = List.copyOf(agent);

        Map<String, EvaluationCaseDefinition> byId = new LinkedHashMap<>();
        deterministic.forEach(definition -> byId.put(definition.id(), definition));
        agent.forEach(definition -> byId.put(definition.id(), definition));
        this.definitionsById = Map.copyOf(byId);
    }

    private EvaluationCaseDefinition toDefinition(SupplyChainEvaluationDataset.EvaluationCase evaluationCase) {
        List<String> assertions = new ArrayList<>();
        if (evaluationCase.risk() != null) {
            var risk = evaluationCase.risk();
            if (risk.level() != null) {
                assertions.add("风险等级为 " + risk.level());
            }
            if (risk.score() != null) {
                assertions.add("风险分为 " + risk.score());
            }
            if (risk.shortage() != null) {
                assertions.add("库存缺口为 " + risk.shortage());
            }
            if (risk.atRisk() != null) {
                assertions.add("风险标记为 " + risk.atRisk() + "（评测口径 score>=60）");
            }
            risk.factorFragments().forEach(fragment -> assertions.add("风险因素包含「" + fragment + "」"));
            risk.actionFragments().forEach(fragment -> assertions.add("建议动作包含「" + fragment + "」"));
        }
        if (evaluationCase.inventory() != null) {
            var inventory = evaluationCase.inventory();
            assertions.add("在库数量为 " + inventory.onHandQuantity());
            assertions.add("预留数量为 " + inventory.reservedQuantity());
            assertions.add("安全库存为 " + inventory.safetyStock());
            assertions.add("ATP 为 " + inventory.availableToPromise());
        }
        if (evaluationCase.supplier() != null) {
            var supplier = evaluationCase.supplier();
            assertions.add("供应商风险等级为 " + supplier.level());
            assertions.add("准时交付率为 " + supplier.onTimeDeliveryRate());
            assertions.add("缺陷率为 " + supplier.defectRate());
        }
        if (evaluationCase.retrieval() != null) {
            assertions.add("Top-3 检索命中 " + evaluationCase.retrieval().expectedSourceIds());
        }
        String name = evaluationCase.scenario() == null || evaluationCase.scenario().isBlank() ? evaluationCase.id()
                : evaluationCase.scenario();
        return new EvaluationCaseDefinition(evaluationCase.id(), name, evaluationCase.scenario(),
                category(evaluationCase.kind()), evaluationCase.kind().name(), EvaluationMode.OFFLINE,
                evaluationCase.question(), "全部断言通过（期望值来自评测数据集）", evaluationCase.targetId(),
                dataset.datasetVersion(), true, evaluationCase.basis(), assertions);
    }

    private EvaluationCaseDefinition toFallbackDefinition(
            SupplyChainEvaluationDataset.EvaluationCase evaluationCase) {
        List<String> assertions = new ArrayList<>();
        assertions.add("任务状态为 COMPLETED");
        assertions.add("存在分析报告");
        if (evaluationCase.report() != null && !evaluationCase.report().atRiskOrderIds().isEmpty()) {
            assertions.add("报告包含风险订单 " + evaluationCase.report().atRiskOrderIds());
        }
        assertions.add("记录了模型调用或降级事件");
        return new EvaluationCaseDefinition(evaluationCase.id(), evaluationCase.scenario(),
                "真实工作流：无模型配置时的确定性降级", "workflow", Kind.REPORT_FALLBACK.name(),
                EvaluationMode.AGENT, evaluationCase.question(), "模型不可用时仍完成任务并生成可验证的确定性报告",
                null, WORKFLOW_VERSION, true, evaluationCase.basis(), assertions);
    }

    private EvaluationCaseDefinition toDefinition(WorkflowEvaluationCase workflow) {
        return new EvaluationCaseDefinition(workflow.id(), workflow.name(), workflow.scenario(), "workflow",
                workflow.taskMode(), EvaluationMode.AGENT, workflow.question(), workflow.expectedBehavior(), null,
                WORKFLOW_VERSION, true, "真实调用当前后端供应链工作流，断言基于返回的任务、报告、决策与事件",
                workflow.assertions(), workflow.embeddingRequired(), workflow.chatRequired());
    }

    private static String category(Kind kind) {
        return switch (kind) {
            case ORDER_RISK -> "risk";
            case INVENTORY -> "inventory";
            case SUPPLIER -> "supplier";
            case RETRIEVAL -> "rag";
            case REPORT_FALLBACK -> "workflow";
        };
    }

    public String datasetVersion() {
        return dataset.datasetVersion();
    }

    public List<EvaluationCaseDefinition> allCases() {
        return List.copyOf(definitionsById.values());
    }

    public List<EvaluationCaseDefinition> deterministicCases() {
        return deterministicCases;
    }

    public List<EvaluationCaseDefinition> agentCases() {
        return agentCases;
    }

    public Optional<EvaluationCaseDefinition> find(String caseId) {
        return Optional.ofNullable(definitionsById.get(caseId));
    }

    public Optional<SupplyChainEvaluationDataset.EvaluationCase> datasetCase(String caseId) {
        return Optional.ofNullable(datasetCasesById.get(caseId));
    }

    public Optional<WorkflowEvaluationCase> workflowCase(String caseId) {
        return Optional.ofNullable(workflowCasesById.get(caseId));
    }

}
