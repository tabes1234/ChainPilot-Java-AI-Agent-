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

import com.alibaba.cloud.ai.dataagent.supplychain.model.CreateDisposalPlanRequest;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DecisionTaskResult;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalExplanation;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalPlan;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalPlanPage;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalPlanStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalPlanSummary;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalRecommendation;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalSimulation;
import com.alibaba.cloud.ai.dataagent.supplychain.model.KnowledgeCitation;
import com.alibaba.cloud.ai.dataagent.supplychain.model.OrderRiskAssessment;
import com.alibaba.cloud.ai.dataagent.supplychain.model.OrderRiskImpact;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskAnalysisReport;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskLevel;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SimulationScenario;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskType;
import com.alibaba.cloud.ai.dataagent.supplychain.model.UpdateDisposalPlanStatusRequest;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.DisposalPlanRepository;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.SupplyChainTaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Creates, persists and transitions disposal plans from real risk evidence. */
@Service
public class DisposalPlanService {

    private static final int MAX_PAGE_SIZE = 50;

    private final SupplyChainQueryService queryService;

    private final DisposalRecommendationService recommendationService;

    private final DisposalSimulationService simulationService;

    private final DisposalExplanationService explanationService;

    private final SupplyChainKnowledgeService knowledgeService;

    private final DisposalPlanRepository planRepository;

    private final SupplyChainTaskRepository taskRepository;

    public DisposalPlanService(SupplyChainQueryService queryService,
            DisposalRecommendationService recommendationService, DisposalSimulationService simulationService,
            DisposalExplanationService explanationService, SupplyChainKnowledgeService knowledgeService,
            DisposalPlanRepository planRepository, SupplyChainTaskRepository taskRepository) {
        this.queryService = queryService;
        this.recommendationService = recommendationService;
        this.simulationService = simulationService;
        this.explanationService = explanationService;
        this.knowledgeService = knowledgeService;
        this.planRepository = planRepository;
        this.taskRepository = taskRepository;
    }

    @Transactional
    public DisposalPlan create(CreateDisposalPlanRequest request) {
        if (request == null || !StringUtils.hasText(request.title())) {
            throw new IllegalArgumentException("title is required");
        }
        if (StringUtils.hasText(request.simulationId()) && request.simulation() != null) {
            throw new IllegalArgumentException("Provide either simulationId or simulation, not both");
        }
        String sku = normalize(request.sku());
        String supplierId = normalize(request.supplierId());
        if (StringUtils.hasText(request.simulationId())) {
            DisposalSimulation attached = simulationService.findSimulation(request.simulationId().trim());
            if (!StringUtils.hasText(sku) && attached.scenario() == SimulationScenario.INVENTORY_REPLENISHMENT) {
                sku = normalize(attached.sku());
            }
            if (!StringUtils.hasText(supplierId) && attached.scenario() == SimulationScenario.SUPPLIER_DELAY) {
                supplierId = normalize(attached.supplierId());
            }
        }

        DisposalSimulation simulation = resolveSimulation(request, sku, supplierId);
        if (simulation != null) {
            if (!StringUtils.hasText(sku)) {
                sku = normalize(simulation.sku());
            }
            if (!StringUtils.hasText(supplierId)) {
                supplierId = normalize(simulation.supplierId());
            }
        }

        List<OrderRiskAssessment> assessments = resolveAssessments(request.taskId(), sku, supplierId);
        if (assessments.isEmpty() && simulation != null && isDecisionTask(request.taskId())) {
            assessments = resolveSimulationAssessments(simulation, sku, supplierId);
        }
        List<DisposalRecommendation> recommendations = recommendationService.generate(assessments,
                queryService.findInventory(null), queryService.findSuppliers(), LocalDate.now());
        if (recommendations.isEmpty()) {
            throw new IllegalArgumentException("No at-risk order matches the requested plan scope");
        }

        List<KnowledgeCitation> citations = knowledgeService.retrieve(
                request.title() + " 库存 补货 供应商 交付 风险");
        DisposalExplanation explanation = Boolean.FALSE.equals(request.generateExplanation())
                ? explanationService.deterministicOnly(request.title().trim(), recommendations, simulation)
                : explanationService.explain(request.title().trim(), recommendations, simulation, citations);

        String planId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        DisposalPlan plan = new DisposalPlan(planId, request.title().trim(), normalize(request.taskId()), sku,
                supplierId, DisposalPlanStatus.PENDING_CONFIRMATION, null, recommendations, simulation, citations,
                explanation.text(), explanation.source(), explanation.modelAssisted(), explanation.toolCalls(), now,
                now);
        planRepository.save(plan);
        if (simulation != null) {
            simulationService.attachToPlan(simulation.simulationId(), planId);
        }
        return planRepository.find(planId)
            .orElseThrow(() -> new IllegalStateException("Saved disposal plan could not be reloaded"));
    }

    /**
     * Saves a comparison candidate as a regular disposal plan. The candidate's simulation is
     * copied with its own id so the existing one-simulation-one-plan rule still holds without
     * collapsing the candidate comparison.
     */
    @Transactional
    public DisposalPlan createFromSimulation(String title, String taskId, String sku, String supplierId,
            DisposalSimulation simulation, Boolean generateExplanation) {
        if (!StringUtils.hasText(title)) {
            throw new IllegalArgumentException("title is required");
        }
        if (simulation == null) {
            throw new IllegalArgumentException("simulation is required");
        }
        String resolvedSku = normalize(sku);
        String resolvedSupplierId = normalize(supplierId);
        if (simulation.scenario() == SimulationScenario.INVENTORY_REPLENISHMENT
                && !StringUtils.hasText(resolvedSku)) {
            resolvedSku = normalize(simulation.sku());
        }
        if (simulation.scenario() == SimulationScenario.SUPPLIER_DELAY && !StringUtils.hasText(resolvedSupplierId)) {
            resolvedSupplierId = normalize(simulation.supplierId());
        }
        simulationService.requireCompatible(simulation, resolvedSku, resolvedSupplierId);

        List<OrderRiskAssessment> assessments = resolveAssessments(taskId, resolvedSku, resolvedSupplierId);
        if (assessments.isEmpty() && isDecisionTask(taskId)) {
            assessments = resolveSimulationAssessments(simulation, resolvedSku, resolvedSupplierId);
        }
        List<DisposalRecommendation> recommendations = recommendationService.generate(assessments,
                queryService.findInventory(null), queryService.findSuppliers(), LocalDate.now());
        if (recommendations.isEmpty()) {
            throw new IllegalArgumentException("No at-risk order matches the requested plan scope");
        }
        List<KnowledgeCitation> citations = knowledgeService.retrieve(
                title.trim() + " 库存 补货 供应商 交付 风险");
        DisposalExplanation explanation = Boolean.FALSE.equals(generateExplanation)
                ? explanationService.deterministicOnly(title.trim(), recommendations, simulation)
                : explanationService.explain(title.trim(), recommendations, simulation, citations);

        DisposalSimulation planSimulation = new DisposalSimulation(UUID.randomUUID().toString(),
                simulation.scenario(), simulation.sku(), simulation.supplierId(),
                simulation.replenishmentQuantity(), simulation.additionalDelayDays(), simulation.asOfDate(),
                simulation.baselineSource(), simulation.baseline(), simulation.simulated(), simulation.delta(),
                simulation.affectedOrders(), simulation.assumptions(), simulation.limitations(),
                simulation.evidence(), simulation.realDataMutated(), simulation.disclaimer(),
                simulation.createdAt(), simulation.ruleVersion(), simulation.simulationStatus(),
                simulation.dataGaps());
        simulationService.save(planSimulation);

        String planId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        DisposalPlan plan = new DisposalPlan(planId, title.trim(), normalize(taskId), resolvedSku,
                resolvedSupplierId, DisposalPlanStatus.PENDING_CONFIRMATION, null, recommendations, planSimulation,
                citations, explanation.text(), explanation.source(), explanation.modelAssisted(),
                explanation.toolCalls(), now, now);
        planRepository.save(plan);
        simulationService.attachToPlan(planSimulation.simulationId(), planId);
        return planRepository.find(planId)
            .orElseThrow(() -> new IllegalStateException("Saved disposal plan could not be reloaded"));
    }

    public DisposalPlan plan(String planId) {
        return planRepository.find(planId)
            .orElseThrow(() -> new SupplyChainResourceNotFoundException("Unknown disposal plan: " + planId));
    }

    public DisposalPlanPage plans(DisposalPlanStatus status, int page, int pageSize) {
        if (page < 1) {
            throw new IllegalArgumentException("page must be at least 1");
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("pageSize must be between 1 and " + MAX_PAGE_SIZE);
        }
        long totalElements = planRepository.count(status);
        long offset = (long) (page - 1) * pageSize;
        List<DisposalPlanSummary> items = planRepository.findPage(status, pageSize, offset);
        int totalPages = totalElements == 0 ? 0 : (int) ((totalElements + pageSize - 1) / pageSize);
        return new DisposalPlanPage(items, page, pageSize, totalElements, totalPages);
    }

    @Transactional
    public DisposalPlan updateStatus(String planId, UpdateDisposalPlanStatusRequest request) {
        DisposalPlan plan = plan(planId);
        if (request == null || request.status() == null) {
            throw new IllegalArgumentException("status is required");
        }
        String selected = normalize(request.selectedRecommendationId());
        if (request.status() == DisposalPlanStatus.SELECTED) {
            if (!StringUtils.hasText(selected)) {
                throw new IllegalArgumentException("selectedRecommendationId is required when status is SELECTED");
            }
            boolean belongsToPlan = plan.recommendations()
                .stream()
                .anyMatch(recommendation -> recommendation.recommendationId().equals(selected));
            if (!belongsToPlan) {
                throw new IllegalArgumentException("selectedRecommendationId does not belong to this plan");
            }
        }
        else if (StringUtils.hasText(selected)) {
            throw new IllegalArgumentException("selectedRecommendationId is only valid when status is SELECTED");
        }
        planRepository.updateStatus(planId, request.status(), selected, Instant.now());
        return plan(planId);
    }

    private boolean isDecisionTask(String taskId) {
        if (!StringUtils.hasText(taskId)) {
            return false;
        }
        return SupplyChainTaskType.DECISION.equals(taskRepository.findTaskType(taskId.trim())
            .orElse(SupplyChainTaskType.ANALYSIS));
    }

    /**
     * Comparison decision tasks persist ranked candidates instead of per-order assessments,
     * so their decision result has no at-risk orders. Fall back to the current deterministic
     * assessments for exactly the orders the candidate simulation touched.
     */
    private List<OrderRiskAssessment> resolveSimulationAssessments(DisposalSimulation simulation, String sku,
            String supplierId) {
        if (simulation.affectedOrders().isEmpty()) {
            return List.of();
        }
        Set<String> affectedOrderIds = simulation.affectedOrders()
            .stream()
            .map(OrderRiskImpact::orderId)
            .collect(Collectors.toSet());
        return resolveAssessments(null, sku, supplierId).stream()
            .filter(assessment -> affectedOrderIds.contains(assessment.order().orderId()))
            .toList();
    }

    private List<OrderRiskAssessment> resolveAssessments(String taskId, String sku, String supplierId) {
        List<OrderRiskAssessment> assessments;
        if (StringUtils.hasText(taskId)) {
            String resolvedTaskId = taskId.trim();
            taskRepository.findTask(resolvedTaskId)
                .orElseThrow(() -> new SupplyChainResourceNotFoundException("Unknown analysis task: " + taskId));
            String taskType = taskRepository.findTaskType(resolvedTaskId)
                .orElse(SupplyChainTaskType.ANALYSIS);
            if (SupplyChainTaskType.DECISION.equals(taskType)) {
                DecisionTaskResult decision = taskRepository.findDecision(resolvedTaskId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Decision task has no completed result: " + taskId));
                assessments = decision.atRiskOrders();
            }
            else {
                RiskAnalysisReport report = taskRepository.findReport(resolvedTaskId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Analysis task has no completed report: " + taskId));
                assessments = report.atRiskOrders();
            }
        }
        else {
            assessments = queryService.findOrders(null, null);
        }
        return assessments.stream()
            .filter(assessment -> assessment.riskLevel() != RiskLevel.LOW)
            .filter(assessment -> !StringUtils.hasText(sku)
                    || assessment.order().sku().equalsIgnoreCase(sku))
            .filter(assessment -> !StringUtils.hasText(supplierId)
                    || assessment.order().supplierId().equalsIgnoreCase(supplierId))
            .toList();
    }

    private DisposalSimulation resolveSimulation(CreateDisposalPlanRequest request, String sku,
            String supplierId) {
        if (StringUtils.hasText(request.simulationId())) {
            DisposalSimulation simulation = simulationService.findSimulation(request.simulationId().trim());
            if (simulation.scenario() == SimulationScenario.INVENTORY_REPLENISHMENT
                    && !StringUtils.hasText(sku)) {
                throw new IllegalArgumentException("sku is required when attaching a replenishment simulation");
            }
            if (simulation.scenario() == SimulationScenario.SUPPLIER_DELAY && !StringUtils.hasText(supplierId)) {
                throw new IllegalArgumentException("supplierId is required when attaching a supplier-delay simulation");
            }
            simulationService.requireCompatible(simulation, sku, supplierId);
            return simulation;
        }
        if (request.simulation() != null) {
            simulationService.requireKnownTargets(request.simulation());
            DisposalSimulation simulation = simulationService.simulate(request.simulation());
            simulationService.requireCompatible(simulation, sku, supplierId);
            return simulationService.save(simulation);
        }
        return null;
    }

    private static String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
