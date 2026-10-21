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

import com.alibaba.cloud.ai.dataagent.supplychain.model.CreateDisposalScenarioComparisonRequest;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalPlan;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalRecommendation;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioComparison;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalSimulation;
import com.alibaba.cloud.ai.dataagent.supplychain.model.InventoryPosition;
import com.alibaba.cloud.ai.dataagent.supplychain.model.OrderRiskAssessment;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxExecution;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxExecutionEligibility;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxExecutionSummary;
import com.alibaba.cloud.ai.dataagent.supplychain.model.ScenarioComparisonCandidateRequest;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SimulationRequest;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SimulationScenario;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplierRiskProfile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SupplyChainAgentTools {

    private final SupplyChainQueryService queryService;

    private final DisposalRecommendationService recommendationService;

    private final DisposalSimulationService simulationService;

    private final DisposalExecutionService executionService;

    private final DisposalScenarioComparisonService comparisonService;

    private final DisposalPlanService planService;

    private final SupplyChainEventRecorder eventRecorder;

    @Tool(description = "查询所有可能延期或存在库存、供应商风险的订单，并返回风险分数、证据和建议。")
    public List<OrderRiskAssessment> findAtRiskOrders() {
        return invoke("findAtRiskOrders", SupplyChainEventRecorder.ToolSource.MODEL, queryService::findAtRiskOrders);
    }

    @Tool(description = "按 SKU 查询库存、预留量和可承诺库存。SKU 为空时返回全部库存。")
    public List<InventoryPosition> findInventory(String sku) {
        return invoke("findInventory", SupplyChainEventRecorder.ToolSource.MODEL,
                () -> queryService.findInventory(sku));
    }

    @Tool(description = "查询供应商履约率与风险等级。供应商 ID 为空时返回全部供应商。")
    public List<SupplierRiskProfile> findSuppliers() {
        return invoke("findSuppliers", SupplyChainEventRecorder.ToolSource.MODEL, queryService::findSuppliers);
    }

    @Tool(description = "生成基于订单、库存和供应商真实数据的处置建议，包含规则优先级、业务依据、预期改善和前置条件。SKU 或供应商 ID 为空表示不按该条件过滤。")
    public List<DisposalRecommendation> findDisposalRecommendations(String sku, String supplierId) {
        return invoke("findDisposalRecommendations", SupplyChainEventRecorder.ToolSource.MODEL,
                () -> recommendationService.generate(sku, supplierId));
    }

    @Tool(description = "模拟给指定 SKU 补货指定数量后的库存、风险和受影响订单。所有指标由确定性 Java 规则计算，不修改真实库存。")
    public DisposalSimulation simulateInventoryReplenishment(String sku, Integer quantity) {
        return invoke("simulateInventoryReplenishment", SupplyChainEventRecorder.ToolSource.MODEL,
                () -> simulationService.simulate(
                        new SimulationRequest(SimulationScenario.INVENTORY_REPLENISHMENT, sku, quantity, null, null)));
    }

    @Tool(description = "模拟指定供应商所有未交付订单延迟指定天数后的库存、风险和受影响订单。所有指标由确定性 Java 规则计算，不修改真实订单。")
    public DisposalSimulation simulateSupplierDelay(String supplierId, Integer additionalDelayDays) {
        return invoke("simulateSupplierDelay", SupplyChainEventRecorder.ToolSource.MODEL,
                () -> simulationService.simulate(new SimulationRequest(SimulationScenario.SUPPLIER_DELAY, null,
                        null, supplierId, additionalDelayDays)));
    }

    @Tool(description = "对同一 SKU 的 2-5 个补货数量方案做确定性情景对比：所有候选共用同一基线，由 Java 规则计算风险指标、排名和推荐，并持久化对比记录。replenishmentQuantitiesCsv 为逗号分隔的整数，例如 50,100,200。模型只能解释返回的指标与排名，不得自行计算或编造结果。")
    public DisposalScenarioComparison compareDisposalScenarios(String sku, String replenishmentQuantitiesCsv) {
        return invoke("compareDisposalScenarios", SupplyChainEventRecorder.ToolSource.MODEL,
                () -> compareReplenishment(sku, replenishmentQuantitiesCsv));
    }

    @Tool(description = "对同一供应商的 2-5 个额外延期天数方案做确定性情景对比：所有候选共用同一基线，由 Java 规则计算风险指标、排名和推荐，并持久化对比记录。additionalDelayDaysCsv 为逗号分隔的整数，例如 3,5,7。模型只能解释返回的指标与排名，不得自行计算或编造结果。")
    public DisposalScenarioComparison compareSupplierDelayScenarios(String supplierId,
            String additionalDelayDaysCsv) {
        return invoke("compareSupplierDelayScenarios", SupplyChainEventRecorder.ToolSource.MODEL,
                () -> compareSupplierDelay(supplierId, additionalDelayDaysCsv));
    }

    @Tool(description = "按对比编号查询已持久化的多方案情景对比，包括候选指标、排名、推荐、权衡与未知项。只读，不修改任何业务数据。")
    public DisposalScenarioComparison findScenarioComparison(String comparisonId) {
        return invoke("findScenarioComparison", SupplyChainEventRecorder.ToolSource.MODEL,
                () -> comparisonService.comparison(comparisonId));
    }

    @Tool(description = "按方案编号查询已持久化的处置方案，包括建议、模拟证据、解释和当前状态。只读，不会选择或执行方案。")
    public DisposalPlan findDisposalPlan(String planId) {
        return invoke("findDisposalPlan", SupplyChainEventRecorder.ToolSource.MODEL,
                () -> planService.plan(planId));
    }

    @Tool(description = "只读检查某个处置方案是否满足沙箱执行演练条件。不会创建执行记录，也不会修改任何业务数据。")
    public SandboxExecutionEligibility checkSandboxExecutionEligibility(String planId) {
        return invoke("checkSandboxExecutionEligibility", SupplyChainEventRecorder.ToolSource.MODEL,
                () -> executionService.eligibility(planId));
    }

    @Tool(description = "按执行编号查询已持久化的沙箱执行演练结果，包括状态、步骤、任务草稿、最终结果和未解决事项。")
    public SandboxExecution findSandboxExecution(String executionId) {
        return invoke("findSandboxExecution", SupplyChainEventRecorder.ToolSource.MODEL,
                () -> executionService.execution(executionId));
    }

    @Tool(description = "查询某个处置方案的沙箱执行演练历史。limit 为空时返回最近 10 条。")
    public List<SandboxExecutionSummary> findSandboxExecutions(String planId, Integer limit) {
        return invoke("findSandboxExecutions", SupplyChainEventRecorder.ToolSource.MODEL, () -> {
            int resolved = limit == null ? 10 : limit;
            if (resolved < 1 || resolved > 50) {
                throw new IllegalArgumentException("limit must be between 1 and 50");
            }
            return executionService.executions(planId, 1, resolved).items();
        });
    }

    /**
     * Workflow nodes call this method directly; it is intentionally not a {@code @Tool} so the
     * model cannot invoke it and its events are never mistaken for model tool calls.
     */
    public List<OrderRiskAssessment> findAtRiskOrdersForWorkflow() {
        return invoke("findAtRiskOrders", SupplyChainEventRecorder.ToolSource.WORKFLOW,
                queryService::findAtRiskOrders);
    }

    public List<InventoryPosition> findInventoryForWorkflow(String sku) {
        return invoke("findInventory", SupplyChainEventRecorder.ToolSource.WORKFLOW,
                () -> queryService.findInventory(sku));
    }

    public List<SupplierRiskProfile> findSuppliersForWorkflow() {
        return invoke("findSuppliers", SupplyChainEventRecorder.ToolSource.WORKFLOW, queryService::findSuppliers);
    }

    public List<DisposalRecommendation> findDisposalRecommendationsForWorkflow(String sku, String supplierId) {
        return invoke("findDisposalRecommendations", SupplyChainEventRecorder.ToolSource.WORKFLOW,
                () -> recommendationService.generate(sku, supplierId));
    }

    public DisposalSimulation simulateInventoryReplenishmentForWorkflow(String sku, Integer quantity) {
        return invoke("simulateInventoryReplenishment", SupplyChainEventRecorder.ToolSource.WORKFLOW,
                () -> simulationService.simulate(
                        new SimulationRequest(SimulationScenario.INVENTORY_REPLENISHMENT, sku, quantity, null, null)));
    }

    public DisposalSimulation simulateSupplierDelayForWorkflow(String supplierId, Integer additionalDelayDays) {
        return invoke("simulateSupplierDelay", SupplyChainEventRecorder.ToolSource.WORKFLOW,
                () -> simulationService.simulate(new SimulationRequest(SimulationScenario.SUPPLIER_DELAY, null,
                        null, supplierId, additionalDelayDays)));
    }

    public DisposalScenarioComparison compareReplenishmentForWorkflow(String sku, String quantitiesCsv) {
        return invoke("compareDisposalScenarios", SupplyChainEventRecorder.ToolSource.WORKFLOW,
                () -> compareReplenishment(sku, quantitiesCsv));
    }

    public DisposalScenarioComparison compareSupplierDelayForWorkflow(String supplierId, String delayDaysCsv) {
        return invoke("compareSupplierDelayScenarios", SupplyChainEventRecorder.ToolSource.WORKFLOW,
                () -> compareSupplierDelay(supplierId, delayDaysCsv));
    }

    public DisposalScenarioComparison findScenarioComparisonForWorkflow(String comparisonId) {
        return invoke("findScenarioComparison", SupplyChainEventRecorder.ToolSource.WORKFLOW,
                () -> comparisonService.comparison(comparisonId));
    }

    public DisposalPlan findDisposalPlanForWorkflow(String planId) {
        return invoke("findDisposalPlan", SupplyChainEventRecorder.ToolSource.WORKFLOW,
                () -> planService.plan(planId));
    }

    public SandboxExecutionEligibility checkSandboxExecutionEligibilityForWorkflow(String planId) {
        return invoke("checkSandboxExecutionEligibility", SupplyChainEventRecorder.ToolSource.WORKFLOW,
                () -> executionService.eligibility(planId));
    }

    public SandboxExecution findSandboxExecutionForWorkflow(String executionId) {
        return invoke("findSandboxExecution", SupplyChainEventRecorder.ToolSource.WORKFLOW,
                () -> executionService.execution(executionId));
    }

    public List<SandboxExecutionSummary> findSandboxExecutionsForWorkflow(String planId, Integer limit) {
        return invoke("findSandboxExecutions", SupplyChainEventRecorder.ToolSource.WORKFLOW, () -> {
            int resolved = limit == null ? 10 : limit;
            if (resolved < 1 || resolved > 50) {
                throw new IllegalArgumentException("limit must be between 1 and 50");
            }
            return executionService.executions(planId, 1, resolved).items();
        });
    }

    private DisposalScenarioComparison compareReplenishment(String sku, String quantitiesCsv) {
        List<ScenarioComparisonCandidateRequest> candidates = parseCandidateParameters(quantitiesCsv,
                "replenishmentQuantitiesCsv", SimulationScenario.INVENTORY_REPLENISHMENT);
        return comparisonService.compare(new CreateDisposalScenarioComparisonRequest(
                "Agent 补货情景对比：" + (sku == null ? "" : sku.trim()),
                SimulationScenario.INVENTORY_REPLENISHMENT, sku, null, candidates));
    }

    private DisposalScenarioComparison compareSupplierDelay(String supplierId, String delayDaysCsv) {
        List<ScenarioComparisonCandidateRequest> candidates = parseCandidateParameters(delayDaysCsv,
                "additionalDelayDaysCsv", SimulationScenario.SUPPLIER_DELAY);
        return comparisonService.compare(new CreateDisposalScenarioComparisonRequest(
                "Agent 供应商延期情景对比：" + (supplierId == null ? "" : supplierId.trim()),
                SimulationScenario.SUPPLIER_DELAY, null, supplierId, candidates));
    }

    private <T> T invoke(String operation, SupplyChainEventRecorder.ToolSource source, ToolCall<T> call) {
        String taskId = eventRecorder.currentTaskId();
        long started = eventRecorder.toolStarted(taskId, operation, source);
        log.info("ChainPilot {} tool started: {}", source.name().toLowerCase(), operation);
        try {
            T result = call.run();
            eventRecorder.toolCompleted(taskId, operation, started, summarize(result), source);
            log.info("ChainPilot {} tool completed: {}", source.name().toLowerCase(), operation);
            return result;
        }
        catch (RuntimeException exception) {
            eventRecorder.toolFailed(taskId, operation, started, exception, source);
            log.error("ChainPilot {} tool failed: {}", source.name().toLowerCase(), operation, exception);
            throw exception;
        }
    }

    private static List<ScenarioComparisonCandidateRequest> parseCandidateParameters(String csv, String field,
            SimulationScenario scenario) {
        if (!StringUtils.hasText(csv)) {
            throw new IllegalArgumentException(field + " must contain 2 to 5 comma-separated integers");
        }
        String[] parts = csv.split(",", -1);
        if (parts.length < 2 || parts.length > 5) {
            throw new IllegalArgumentException(field + " must contain 2 to 5 comma-separated integers");
        }
        List<ScenarioComparisonCandidateRequest> candidates = new ArrayList<>(parts.length);
        for (String part : parts) {
            String value = part.trim();
            if (value.isEmpty()) {
                throw new IllegalArgumentException(field + " contains an empty value");
            }
            try {
                int parsed = Integer.parseInt(value);
                candidates.add(scenario == SimulationScenario.INVENTORY_REPLENISHMENT
                        ? new ScenarioComparisonCandidateRequest(null, parsed, null)
                        : new ScenarioComparisonCandidateRequest(null, null, parsed));
            }
            catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Invalid candidate value: " + value, exception);
            }
        }
        return List.copyOf(candidates);
    }

    private static String summarize(Object result) {
        if (result instanceof Collection<?> collection) {
            return "returned " + collection.size() + " item(s)";
        }
        if (result instanceof DisposalScenarioComparison comparison) {
            return "comparison=" + comparison.comparisonId() + ", outcome="
                    + comparison.recommendation().outcome() + ", candidates=" + comparison.candidates().size()
                    + ", recommended=" + comparison.recommendation().recommendedCandidateId();
        }
        if (result instanceof DisposalSimulation simulation) {
            return "scenario=" + simulation.scenario() + ", affectedOrders="
                    + simulation.affectedOrders().size();
        }
        if (result instanceof SandboxExecution execution) {
            return "execution=" + execution.executionId() + ", status=" + execution.status()
                    + ", steps=" + execution.steps().size();
        }
        if (result instanceof SandboxExecutionEligibility eligibility) {
            return "plan=" + eligibility.disposalPlanId() + ", eligible=" + eligibility.eligible();
        }
        return "tool execution completed";
    }

    @FunctionalInterface
    private interface ToolCall<T> {
        T run();
    }
}
