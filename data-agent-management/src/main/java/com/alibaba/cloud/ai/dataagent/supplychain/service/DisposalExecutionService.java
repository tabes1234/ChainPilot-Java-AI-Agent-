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

import com.alibaba.cloud.ai.dataagent.supplychain.model.CreateSandboxExecutionRequest;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalActionType;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalPlan;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalPlanStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalRecommendation;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalSimulation;
import com.alibaba.cloud.ai.dataagent.supplychain.model.InventoryPosition;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxDraftType;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxExecution;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxExecutionEligibility;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxExecutionMode;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxExecutionPage;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxExecutionStartResult;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxExecutionStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxExecutionStep;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxExecutionSummary;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxStepStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxTaskDraft;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SimulationScenario;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplierRiskProfile;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainOrder;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.DisposalExecutionRepository;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.DisposalPlanRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Runs a disposal plan as a local sandbox rehearsal. Every step is executed by Java against
 * current business data; the only output is a persisted task draft and audit record. No real
 * inventory, order, supplier or procurement state is written.
 */
@Service
public class DisposalExecutionService {

    private static final int MAX_PAGE_SIZE = 50;

    private static final int MAX_REASON_LENGTH = 300;

    private static final int MAX_EVIDENCE_ITEMS = 20;

    private static final String DISCLAIMER =
            "本地沙箱执行演练，仅生成任务草稿与审计记录；未修改真实库存、订单、供应商或 ERP 数据。";

    private final DisposalPlanRepository planRepository;

    private final DisposalExecutionRepository executionRepository;

    private final SupplyChainQueryService queryService;

    private final TransactionTemplate transactionTemplate;

    public DisposalExecutionService(DisposalPlanRepository planRepository,
            DisposalExecutionRepository executionRepository, SupplyChainQueryService queryService,
            PlatformTransactionManager transactionManager) {
        this.planRepository = planRepository;
        this.executionRepository = executionRepository;
        this.queryService = queryService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Starts a rehearsal. The plan row lock, idempotency lookup and RUNNING insertion share one
     * short transaction; steps then persist their progress independently so a failed rehearal
     * remains inspectable and retryable with a new request key.
     */
    public SandboxExecutionStartResult start(String planId, CreateSandboxExecutionRequest request) {
        String idempotencyKey = normalizeKey(request);
        PreparedStart prepared = transactionTemplate.execute(status -> {
            DisposalPlan plan = planRepository.findForUpdate(planId)
                .orElseThrow(() -> new SupplyChainResourceNotFoundException("Unknown disposal plan: " + planId));
            Optional<SandboxExecution> replay = executionRepository.findByIdempotencyKeyForUpdate(planId,
                    idempotencyKey);
            if (replay.isPresent()) {
                return new PreparedStart(replay.get(), null, false);
            }
            Preparation preparation = prepare(plan);
            executionRepository.findBlockingForUpdate(planId).ifPresent(blocking -> {
                throw new IllegalStateException(blocking.status() == SandboxExecutionStatus.RUNNING
                        ? "Disposal plan already has a running sandbox execution: " + blocking.executionId()
                        : "Disposal plan already has a successful sandbox execution: " + blocking.executionId());
            });
            Instant startedAt = now();
            SandboxExecution running = new SandboxExecution(UUID.randomUUID().toString(), plan.planId(),
                    preparation.recommendation().recommendationId(), preparation.recommendation().actionType(),
                    SandboxExecutionMode.SANDBOX, SandboxExecutionStatus.RUNNING,
                    preparation.simulation() == null ? null : preparation.simulation().simulationId(),
                    idempotencyKey, List.of(), null, preparation.evidence(), null, List.of(), null, false,
                    DISCLAIMER, startedAt, startedAt, null, startedAt);
            try {
                executionRepository.save(running);
            }
            catch (DuplicateKeyException exception) {
                throw new IllegalStateException(
                        "A sandbox execution already exists for this request key; reload the plan executions",
                        exception);
            }
            return new PreparedStart(running, preparation, true);
        });
        if (prepared == null) {
            throw new IllegalStateException("Unable to prepare sandbox execution");
        }
        if (!prepared.created()) {
            return new SandboxExecutionStartResult(prepared.execution(), false);
        }
        return new SandboxExecutionStartResult(execute(prepared.execution(), prepared.preparation()), true);
    }

    public SandboxExecutionEligibility eligibility(String planId) {
        DisposalPlan plan = planRepository.find(planId)
            .orElseThrow(() -> new SupplyChainResourceNotFoundException("Unknown disposal plan: " + planId));
        DisposalRecommendation selected = selectedRecommendation(plan);
        List<String> checks = new ArrayList<>();
        checks.add("方案状态：" + plan.status());
        if (selected != null) {
            checks.add("已选处置建议：" + selected.recommendationId() + " / " + selected.actionType());
        }
        try {
            Preparation preparation = prepare(plan);
            checks.addAll(preparation.checks());
            Optional<SandboxExecution> blocking = executionRepository.findBlocking(planId);
            if (blocking.isPresent()) {
                SandboxExecution execution = blocking.get();
                checks.add("执行记录：" + execution.executionId() + " / " + execution.status());
                String reason = execution.status() == SandboxExecutionStatus.RUNNING
                        ? "A sandbox execution is already running for this plan"
                        : "The plan already has a successful sandbox execution";
                return eligibility(plan, selected, preparation.simulation(), false, checks, reason);
            }
            checks.add("当前没有进行中或已成功的执行记录");
            return eligibility(plan, selected, preparation.simulation(), true, checks, null);
        }
        catch (RuntimeException exception) {
            checks.add("校验未通过：" + safeReason(exception));
            return eligibility(plan, selected, null, false, checks, safeReason(exception));
        }
    }

    public SandboxExecution execution(String executionId) {
        return executionRepository.find(executionId)
            .orElseThrow(() -> new SupplyChainResourceNotFoundException(
                    "Unknown sandbox execution: " + executionId));
    }

    public List<SandboxExecutionStep> steps(String executionId) {
        return execution(executionId).steps();
    }

    public SandboxExecutionPage executions(String planId, int page, int pageSize) {
        planRepository.find(planId)
            .orElseThrow(() -> new SupplyChainResourceNotFoundException("Unknown disposal plan: " + planId));
        if (page < 1) {
            throw new IllegalArgumentException("page must be at least 1");
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("pageSize must be between 1 and " + MAX_PAGE_SIZE);
        }
        long totalElements = executionRepository.count(planId);
        long offset = (long) (page - 1) * pageSize;
        List<SandboxExecutionSummary> items = executionRepository.findPage(planId, pageSize, offset);
        int totalPages = totalElements == 0 ? 0 : (int) ((totalElements + pageSize - 1) / pageSize);
        return new SandboxExecutionPage(items, page, pageSize, totalElements, totalPages);
    }

    private Preparation prepare(DisposalPlan plan) {
        if (plan.status() != DisposalPlanStatus.SELECTED) {
            throw new IllegalStateException(
                    "Disposal plan must be SELECTED before sandbox execution; current status is " + plan.status());
        }
        DisposalRecommendation recommendation = selectedRecommendation(plan);
        if (recommendation == null) {
            throw new IllegalStateException("Selected recommendation does not belong to this disposal plan");
        }
        List<String> checks = new ArrayList<>();
        checks.add("处置建议与方案选中项一致");
        if (recommendation.actionType() == null) {
            throw new IllegalStateException("Selected recommendation has no action type");
        }
        return switch (recommendation.actionType()) {
            case REPLENISH_INVENTORY -> prepareReplenishment(plan, recommendation, checks);
            case FOLLOW_UP_SUPPLIER -> prepareSupplierFollowUp(plan, recommendation, checks);
            case MANUAL_CONFIRMATION -> throw new IllegalStateException(
                    "Sandbox execution does not support MANUAL_CONFIRMATION actions");
        };
    }

    private Preparation prepareReplenishment(DisposalPlan plan, DisposalRecommendation recommendation,
            List<String> checks) {
        String sku = normalize(recommendation.sku());
        if (!StringUtils.hasText(sku)) {
            throw new IllegalStateException("Selected replenishment recommendation has no SKU");
        }
        DisposalSimulation simulation = plan.simulation();
        if (simulation == null) {
            throw new IllegalStateException(
                    "Inventory replenishment sandbox execution requires an attached simulation");
        }
        if (simulation.scenario() != SimulationScenario.INVENTORY_REPLENISHMENT) {
            throw new IllegalStateException(
                    "Attached simulation is not an inventory replenishment simulation");
        }
        if (StringUtils.hasText(simulation.sku()) && !simulation.sku().equalsIgnoreCase(sku)) {
            throw new IllegalStateException("Attached simulation SKU does not match the selected recommendation");
        }
        Integer quantity = simulation.replenishmentQuantity();
        if (quantity == null || quantity < 1 || quantity > 1_000_000) {
            throw new IllegalStateException("Attached simulation has an invalid replenishment quantity");
        }
        InventoryPosition inventory = queryService.findInventory(sku)
            .stream()
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("SKU no longer exists in current inventory: " + sku));
        if (simulation.baseline() == null || simulation.baseline().targetSkuAvailableToPromise() == null
                || simulation.simulated() == null
                || simulation.simulated().targetSkuAvailableToPromise() == null) {
            throw new IllegalStateException("Attached simulation has no comparable stock metrics");
        }
        if (inventory.availableToPromise() != simulation.baseline().targetSkuAvailableToPromise()) {
            throw new IllegalStateException(
                    "Attached simulation is stale: current available-to-promise differs from its baseline");
        }
        checks.add("模拟来源：" + simulation.simulationId() + "，补货数量 " + quantity);
        checks.add("当前库存 " + inventory.onHandQuantity() + " 件，可承诺 "
                + inventory.availableToPromise() + " 件");
        List<String> evidence = evidence(plan, recommendation, simulation, null);
        return new Preparation(recommendation, simulation, inventory, null, recommendation.affectedOrderIds(),
                evidence, List.copyOf(checks));
    }

    private Preparation prepareSupplierFollowUp(DisposalPlan plan, DisposalRecommendation recommendation,
            List<String> checks) {
        String supplierId = normalize(recommendation.supplierId());
        if (!StringUtils.hasText(supplierId)) {
            throw new IllegalStateException("Selected supplier follow-up recommendation has no supplier");
        }
        SupplierRiskProfile supplier = queryService.findSupplier(supplierId)
            .orElseThrow(() -> new IllegalStateException("Supplier no longer exists: " + supplierId));
        DisposalSimulation simulation = compatibleSupplierSimulation(plan, supplierId);
        List<SupplyChainOrder> linkedOrders = linkedOrders(recommendation.affectedOrderIds(), supplierId);
        if (linkedOrders.isEmpty()) {
            throw new IllegalStateException(
                    "Selected supplier follow-up recommendation has no linked risk orders");
        }
        checks.add("供应商：" + supplier.supplierId() + " / 风险 " + supplier.riskLevel());
        checks.add("关联风险订单：" + linkedOrders.stream().map(SupplyChainOrder::orderId).toList());
        if (simulation != null) {
            checks.add("模拟来源：" + simulation.simulationId() + "，延期 "
                    + simulation.additionalDelayDays() + " 天");
        }
        List<String> evidence = evidence(plan, recommendation, simulation, supplier);
        return new Preparation(recommendation, simulation, null, supplier,
                linkedOrders.stream().map(SupplyChainOrder::orderId).toList(), evidence, List.copyOf(checks));
    }

    private DisposalSimulation compatibleSupplierSimulation(DisposalPlan plan, String supplierId) {
        DisposalSimulation simulation = plan.simulation();
        if (simulation == null) {
            return null;
        }
        if (simulation.scenario() != SimulationScenario.SUPPLIER_DELAY) {
            return null;
        }
        if (!StringUtils.hasText(simulation.supplierId())
                || !simulation.supplierId().equalsIgnoreCase(supplierId)) {
            throw new IllegalStateException(
                    "Attached supplier-delay simulation does not match the selected recommendation");
        }
        Integer delay = simulation.additionalDelayDays();
        if (delay == null || delay < 1 || delay > 90) {
            throw new IllegalStateException("Attached simulation has an invalid additional delay");
        }
        return simulation;
    }

    private List<SupplyChainOrder> linkedOrders(List<String> referencedOrderIds, String supplierId) {
        Set<String> expected = new LinkedHashSet<>(referencedOrderIds);
        List<SupplyChainOrder> linked = queryService.findAllOrders()
            .stream()
            .filter(order -> expected.contains(order.orderId()))
            .toList();
        if (linked.size() != expected.size()) {
            Set<String> found = linked.stream().map(SupplyChainOrder::orderId).collect(java.util.stream.Collectors.toSet());
            expected.removeAll(found);
            throw new IllegalStateException("Linked risk orders no longer exist: " + expected);
        }
        List<SupplyChainOrder> mismatched = linked.stream()
            .filter(order -> !supplierId.equalsIgnoreCase(order.supplierId()))
            .toList();
        if (!mismatched.isEmpty()) {
            throw new IllegalStateException("Linked risk orders do not belong to the selected supplier: "
                    + mismatched.stream().map(SupplyChainOrder::orderId).toList());
        }
        return linked;
    }

    private List<String> evidence(DisposalPlan plan, DisposalRecommendation recommendation,
            DisposalSimulation simulation, SupplierRiskProfile supplier) {
        Set<String> evidence = new LinkedHashSet<>();
        recommendation.sources().forEach(source -> evidence.add("处置依据：" + source));
        plan.evidence()
            .stream()
            .limit(3)
            .forEach(citation -> evidence.add("知识库：" + citation.title() + " (" + citation.sourceId() + ")"));
        if (supplier != null) {
            evidence.add("供应商风险：" + supplier.name() + " / " + supplier.riskLevel() + " / 履约率 "
                    + supplier.onTimeDeliveryRate() + " / 最近事件 " + supplier.latestIncident());
        }
        if (simulation != null) {
            simulation.evidence().stream().limit(5).forEach(item -> evidence.add("模拟证据：" + item));
        }
        if (evidence.isEmpty()) {
            throw new IllegalStateException("Disposal plan has no source evidence for sandbox execution");
        }
        return evidence.stream().limit(MAX_EVIDENCE_ITEMS).toList();
    }

    private SandboxExecution execute(SandboxExecution execution, Preparation preparation) {
        List<SandboxExecutionStep> steps = new ArrayList<>();
        SandboxTaskDraft draft = null;
        DataFingerprint before = fingerprint();
        try {
            validatePlanStep(steps, execution);
            persistProgress(execution, steps, null);
            validateEvidenceStep(steps, execution, preparation);
            persistProgress(execution, steps, null);
            ActionParameters parameters = validateParametersStep(steps, execution, preparation);
            persistProgress(execution, steps, null);
            draft = generateDraftStep(steps, execution, preparation, parameters);
            persistProgress(execution, steps, draft);
            AuditResult audit = auditStep(steps, execution, preparation, parameters, draft, before);
            return finish(execution, SandboxExecutionStatus.SUCCEEDED, steps, draft, audit.finalResult(),
                    audit.unresolvedItems(), null);
        }
        catch (RuntimeException exception) {
            String reason = safeReason(exception);
            if (!(exception instanceof StepFailure)) {
                Instant failedAt = now();
                steps.add(new SandboxExecutionStep(steps.size() + 1, "SANDBOX_EXECUTION", "沙箱执行",
                        SandboxStepStatus.FAILED, "executionId=" + execution.executionId(), "未通过", null,
                        reason, failedAt, failedAt));
            }
            return finish(execution, SandboxExecutionStatus.FAILED, steps, draft,
                    "沙箱演练未完成：" + reason,
                    List.of("未创建任何真实采购单、库存变更或供应商通知", "排查原因后可使用新的请求编号重新发起演练"),
                    reason);
        }
    }

    private void validatePlanStep(List<SandboxExecutionStep> steps, SandboxExecution execution) {
        runStep(steps, "PLAN_AND_RECOMMENDATION_VALIDATION", "方案与处置建议校验",
                "planId=" + execution.disposalPlanId() + ", executionId=" + execution.executionId()
                        + ", recommendationId=" + execution.recommendationId(),
                () -> {
                    DisposalPlan plan = planRepository.find(execution.disposalPlanId())
                        .orElseThrow(() -> new IllegalStateException("Disposal plan no longer exists"));
                    if (plan.status() != DisposalPlanStatus.SELECTED) {
                        throw new IllegalStateException("Disposal plan is no longer SELECTED: " + plan.status());
                    }
                    DisposalRecommendation selected = selectedRecommendation(plan);
                    if (selected == null
                            || !selected.recommendationId().equals(execution.recommendationId())) {
                        throw new IllegalStateException(
                                "Selected recommendation no longer matches the execution record");
                    }
                    if (selected.actionType() != execution.actionType()) {
                        throw new IllegalStateException(
                                "Selected recommendation action changed during sandbox execution");
                    }
                    return new StepResult<>(selected,
                            "方案仍为 SELECTED，建议 " + selected.recommendationId() + " 与执行记录一致");
                });
    }

    private void validateEvidenceStep(List<SandboxExecutionStep> steps, SandboxExecution execution,
            Preparation preparation) {
        runStep(steps, "SIMULATION_AND_EVIDENCE_VALIDATION", "模拟结果与来源证据校验",
                "simulationId=" + (execution.sourceSimulationId() == null ? "none"
                        : execution.sourceSimulationId()) + ", evidence=" + preparation.evidence().size() + " 条",
                () -> {
                    if (preparation.evidence().isEmpty()) {
                        throw new IllegalStateException("No source evidence is available");
                    }
                    if (execution.actionType() == DisposalActionType.REPLENISH_INVENTORY) {
                        DisposalSimulation simulation = preparation.simulation();
                        if (simulation == null
                                || !Objects.equals(simulation.simulationId(), execution.sourceSimulationId())) {
                            throw new IllegalStateException("Source simulation is missing or no longer linked");
                        }
                        if (simulation.realDataMutated()) {
                            throw new IllegalStateException("Source simulation reports a real data mutation");
                        }
                        if (simulation.baseline() == null || simulation.simulated() == null) {
                            throw new IllegalStateException("Source simulation has incomplete metrics");
                        }
                    }
                    return new StepResult<>(null,
                            "模拟来源与 " + preparation.evidence().size() + " 条证据校验通过");
                });
    }

    private ActionParameters validateParametersStep(List<SandboxExecutionStep> steps,
            SandboxExecution execution, Preparation preparation) {
        return runStep(steps, "ACTION_PARAMETER_VALIDATION", "动作参数与当前业务数据校验",
                "action=" + execution.actionType() + ", sku=" + value(preparation.recommendation().sku())
                        + ", supplierId=" + value(preparation.recommendation().supplierId()),
                () -> {
                    if (execution.actionType() == DisposalActionType.REPLENISH_INVENTORY) {
                        String sku = preparation.recommendation().sku();
                        InventoryPosition inventory = queryService.findInventory(sku)
                            .stream()
                            .findFirst()
                            .orElseThrow(() -> new IllegalStateException(
                                    "SKU no longer exists in current inventory: " + sku));
                        DisposalSimulation simulation = preparation.simulation();
                        if (inventory.availableToPromise() != simulation.baseline()
                            .targetSkuAvailableToPromise()) {
                            throw new IllegalStateException(
                                    "Simulation is stale: current available-to-promise changed before execution");
                        }
                        int quantity = simulation.replenishmentQuantity();
                        int targetOnHand = inventory.onHandQuantity() + quantity;
                        int targetAtp = inventory.availableToPromise() + quantity;
                        if (targetAtp != simulation.simulated().targetSkuAvailableToPromise()) {
                            throw new IllegalStateException(
                                    "Simulation target does not match current stock plus replenishment quantity");
                        }
                        return new StepResult<>(new ActionParameters(inventory, null,
                                preparation.linkedOrderIds(), quantity, targetOnHand, targetAtp, null),
                                "补货数量 " + quantity + " 有效；目标现货 " + targetOnHand + " 件，目标可承诺 "
                                        + targetAtp + " 件");
                    }
                    String supplierId = preparation.recommendation().supplierId();
                    SupplierRiskProfile supplier = queryService.findSupplier(supplierId)
                        .orElseThrow(() -> new IllegalStateException(
                                "Supplier no longer exists: " + supplierId));
                    List<SupplyChainOrder> linked = linkedOrders(preparation.linkedOrderIds(), supplierId);
                    Integer delay = preparation.simulation() == null ? null
                            : preparation.simulation().additionalDelayDays();
                    return new StepResult<>(new ActionParameters(null, supplier,
                            linked.stream().map(SupplyChainOrder::orderId).toList(), null, null, null, delay),
                            "供应商 " + supplier.name() + " 存在；关联风险订单 " + linked.size() + " 个"
                                    + (delay == null ? "" : "；模拟延期 " + delay + " 天"));
                });
    }

    private SandboxTaskDraft generateDraftStep(List<SandboxExecutionStep> steps, SandboxExecution execution,
            Preparation preparation, ActionParameters parameters) {
        return runStep(steps, "SANDBOX_TASK_DRAFT_GENERATION", "沙箱任务草稿生成",
                "action=" + execution.actionType() + ", linkedOrders=" + parameters.linkedOrderIds().size(),
                () -> {
                    SandboxTaskDraft draft = execution.actionType() == DisposalActionType.REPLENISH_INVENTORY
                            ? replenishmentDraft(execution, preparation, parameters)
                            : supplierDraft(execution, preparation, parameters);
                    if (!StringUtils.hasText(draft.taskDraftId()) || !StringUtils.hasText(draft.title())
                            || draft.linkedOrderIds().isEmpty() || draft.pendingConfirmations().isEmpty()) {
                        throw new IllegalStateException("Generated sandbox draft is incomplete");
                    }
                    return new StepResult<>(draft, "已生成沙箱任务草稿 " + draft.taskDraftId()
                            + "，待确认事项 " + draft.pendingConfirmations().size() + " 项");
                });
    }

    private AuditResult auditStep(List<SandboxExecutionStep> steps, SandboxExecution execution,
            Preparation preparation, ActionParameters parameters, SandboxTaskDraft draft,
            DataFingerprint before) {
        return runStep(steps, "AUDIT_AND_RESULT_RECORDING", "结果与审计记录",
                "draftId=" + draft.taskDraftId() + ", realDataMutated=false",
                () -> {
                    DataFingerprint after = fingerprint();
                    if (!after.equals(before)) {
                        throw new IllegalStateException(
                                "Real inventory, order or supplier data changed during the sandbox rehearsal");
                    }
                    String finalResult = execution.actionType() == DisposalActionType.REPLENISH_INVENTORY
                            ? "沙箱演练完成：已生成补货任务草稿 " + draft.taskDraftId() + "，SKU "
                                    + draft.sku() + " 补货 " + parameters.replenishmentQuantity() + " 件，目标现货 "
                                    + parameters.targetOnHandQuantity() + " 件。未写入真实库存，未创建真实采购单。"
                            : "沙箱演练完成：已生成供应商跟进任务草稿 " + draft.taskDraftId() + "，供应商 "
                                    + draft.supplierId() + "，关联 " + draft.linkedOrderIds().size()
                                    + " 个风险订单。未联系真实供应商，未修改真实交付日期。";
                    List<String> unresolved = execution.actionType() == DisposalActionType.REPLENISH_INVENTORY
                            ? List.of("补货数量、预算和到货时间需人工复核", "尚未创建真实采购申请或入库任务",
                                    "供应商产能与价格需线下确认")
                            : List.of("供应商新交期与补偿方案需人工确认", "尚未向真实供应商发送跟进通知",
                                    "替代供应、加急运输或客户改期方案未确定");
                    return new StepResult<>(new AuditResult(finalResult, unresolved),
                            "审计通过：库存、订单与供应商快照未发生变化；" + preparation.evidence().size()
                                    + " 条来源证据已关联");
                });
    }

    private SandboxTaskDraft replenishmentDraft(SandboxExecution execution, Preparation preparation,
            ActionParameters parameters) {
        DisposalRecommendation recommendation = preparation.recommendation();
        DisposalSimulation simulation = preparation.simulation();
        InventoryPosition inventory = parameters.inventory();
        return new SandboxTaskDraft("DRAFT-REPL-" + UUID.randomUUID().toString(),
                SandboxDraftType.REPLENISHMENT_TASK_DRAFT,
                "补货任务草稿（沙箱）：" + recommendation.sku() + " +" + parameters.replenishmentQuantity(),
                recommendation.sku(), null, parameters.replenishmentQuantity(), inventory.onHandQuantity(),
                inventory.availableToPromise(), parameters.targetOnHandQuantity(),
                parameters.targetAvailableToPromise(), parameters.linkedOrderIds(),
                List.of("核对供应商可供货量与最早到货日期", "由采购负责人复核数量、预算和到货要求",
                        "到货后重新计算可承诺库存并复核风险订单"),
                List.of("补货数量 " + parameters.replenishmentQuantity() + " 件",
                        "目标现货 " + parameters.targetOnHandQuantity() + " 件", "预算、供应商与到货时间"),
                simulation.simulationId(),
                "模拟 " + simulation.simulationId() + "：可承诺 " + inventory.availableToPromise() + " -> "
                        + parameters.targetAvailableToPromise() + "；关联订单 "
                        + parameters.linkedOrderIds().size() + " 个",
                now());
    }

    private SandboxTaskDraft supplierDraft(SandboxExecution execution, Preparation preparation,
            ActionParameters parameters) {
        DisposalRecommendation recommendation = preparation.recommendation();
        SupplierRiskProfile supplier = parameters.supplier();
        Integer delay = parameters.additionalDelayDays();
        String delayText = delay == null ? "" : "（模拟延期 " + delay + " 天）";
        return new SandboxTaskDraft("DRAFT-SUP-" + UUID.randomUUID().toString(),
                SandboxDraftType.SUPPLIER_FOLLOW_UP_DRAFT,
                "供应商跟进任务草稿（沙箱）：" + supplier.name() + " / " + supplier.supplierId(),
                recommendation.sku(), supplier.supplierId(), null, null, null, null, null,
                parameters.linkedOrderIds(),
                List.of("联系供应商确认最新交付日期与分批交付计划" + delayText,
                        "要求供应商说明延期原因并提供纠正措施", "评估替代供应商、加急运输或客户改期方案"),
                List.of("供应商确认的新交付日期", "是否需要加急或切换替代供应", "受影响订单的客户沟通与补偿方案"),
                preparation.simulation() == null ? null : preparation.simulation().simulationId(),
                "供应商 " + supplier.name() + " 风险 " + supplier.riskLevel() + "，履约率 "
                        + supplier.onTimeDeliveryRate() + "；关联风险订单 " + parameters.linkedOrderIds().size()
                        + " 个",
                now());
    }

    private SandboxExecution finish(SandboxExecution execution, SandboxExecutionStatus status,
            List<SandboxExecutionStep> steps, SandboxTaskDraft draft, String finalResult,
            List<String> unresolvedItems, String failureReason) {
        Instant completedAt = now();
        SandboxExecution finished = new SandboxExecution(execution.executionId(), execution.disposalPlanId(),
                execution.recommendationId(), execution.actionType(), execution.mode(), status,
                execution.sourceSimulationId(), execution.idempotencyKey(), steps, draft, execution.evidence(),
                finalResult, unresolvedItems, failureReason, false, DISCLAIMER, execution.createdAt(),
                execution.startedAt(), completedAt, completedAt);
        executionRepository.update(finished);
        return finished;
    }

    private void persistProgress(SandboxExecution execution, List<SandboxExecutionStep> steps,
            SandboxTaskDraft draft) {
        SandboxExecution progress = new SandboxExecution(execution.executionId(), execution.disposalPlanId(),
                execution.recommendationId(), execution.actionType(), execution.mode(),
                SandboxExecutionStatus.RUNNING, execution.sourceSimulationId(), execution.idempotencyKey(),
                steps, draft, execution.evidence(), null, List.of(), null, false, DISCLAIMER,
                execution.createdAt(), execution.startedAt(), null, now());
        executionRepository.update(progress);
    }

    private DataFingerprint fingerprint() {
        return new DataFingerprint(queryService.findInventory(null), queryService.findAllOrders(),
                queryService.findSuppliers());
    }

    private DisposalRecommendation selectedRecommendation(DisposalPlan plan) {
        if (!StringUtils.hasText(plan.selectedRecommendationId())) {
            return null;
        }
        return plan.recommendations()
            .stream()
            .filter(recommendation -> recommendation.recommendationId()
                .equals(plan.selectedRecommendationId()))
            .findFirst()
            .orElse(null);
    }

    private SandboxExecutionEligibility eligibility(DisposalPlan plan, DisposalRecommendation recommendation,
            DisposalSimulation simulation, boolean eligible, List<String> checks, String reason) {
        return new SandboxExecutionEligibility(plan.planId(), plan.status(), eligible,
                recommendation == null ? null : recommendation.recommendationId(),
                recommendation == null ? null : recommendation.actionType(),
                simulation == null ? null : simulation.simulationId(), checks, reason, now());
    }

    private static <T> T runStep(List<SandboxExecutionStep> steps, String code, String name,
            String inputSummary, Supplier<StepResult<T>> action) {
        Instant started = now();
        try {
            StepResult<T> result = action.get();
            steps.add(new SandboxExecutionStep(steps.size() + 1, code, name, SandboxStepStatus.SUCCEEDED,
                    inputSummary, "通过", result.summary(), null, started, now()));
            return result.value();
        }
        catch (RuntimeException exception) {
            String reason = safeReason(exception);
            steps.add(new SandboxExecutionStep(steps.size() + 1, code, name, SandboxStepStatus.FAILED,
                    inputSummary, "未通过", null, reason, started, now()));
            throw new StepFailure(reason, exception);
        }
    }

    private static Instant now() {
        return Instant.ofEpochMilli(System.currentTimeMillis());
    }

    private static String normalizeKey(CreateSandboxExecutionRequest request) {
        if (request == null || !StringUtils.hasText(request.idempotencyKey())) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }
        String key = request.idempotencyKey().trim();
        if (key.length() > 100) {
            throw new IllegalArgumentException("idempotencyKey must be at most 100 characters");
        }
        return key;
    }

    private static String safeReason(Throwable exception) {
        if (exception instanceof StepFailure failure) {
            return failure.reason();
        }
        if (exception instanceof IllegalArgumentException || exception instanceof IllegalStateException) {
            String message = exception.getMessage();
            if (StringUtils.hasText(message)) {
                String singleLine = message.replaceAll("\\s+", " ").trim();
                return singleLine.length() <= MAX_REASON_LENGTH ? singleLine
                        : singleLine.substring(0, MAX_REASON_LENGTH);
            }
        }
        return "步骤执行异常，请查看服务日志并使用新的请求编号重试";
    }

    private static String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static String value(String value) {
        return value == null ? "none" : value;
    }

    private record PreparedStart(SandboxExecution execution, Preparation preparation, boolean created) {
    }

    private record Preparation(DisposalRecommendation recommendation, DisposalSimulation simulation,
            InventoryPosition inventory, SupplierRiskProfile supplier, List<String> linkedOrderIds,
            List<String> evidence, List<String> checks) {
    }

    private record ActionParameters(InventoryPosition inventory, SupplierRiskProfile supplier,
            List<String> linkedOrderIds, Integer replenishmentQuantity, Integer targetOnHandQuantity,
            Integer targetAvailableToPromise, Integer additionalDelayDays) {
    }

    private record AuditResult(String finalResult, List<String> unresolvedItems) {
    }

    private record DataFingerprint(List<InventoryPosition> inventory, List<SupplyChainOrder> orders,
            List<SupplierRiskProfile> suppliers) {
    }

    private record StepResult<T>(T value, String summary) {
    }

    private static final class StepFailure extends RuntimeException {

        private final String reason;

        private StepFailure(String reason, Throwable cause) {
            super(reason, cause);
            this.reason = reason;
        }

        String reason() {
            return reason;
        }
    }
}
