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
package com.alibaba.cloud.ai.dataagent.supplychain.evaluation;

import com.alibaba.cloud.ai.dataagent.supplychain.evaluation.SupplyChainEvaluationDataset.EvaluationCase;
import com.alibaba.cloud.ai.dataagent.supplychain.evaluation.SupplyChainEvaluationDataset.InventoryExpectation;
import com.alibaba.cloud.ai.dataagent.supplychain.evaluation.SupplyChainEvaluationDataset.Kind;
import com.alibaba.cloud.ai.dataagent.supplychain.evaluation.SupplyChainEvaluationDataset.ReportExpectation;
import com.alibaba.cloud.ai.dataagent.supplychain.evaluation.SupplyChainEvaluationDataset.RetrievalExpectation;
import com.alibaba.cloud.ai.dataagent.supplychain.evaluation.SupplyChainEvaluationDataset.RiskExpectation;
import com.alibaba.cloud.ai.dataagent.supplychain.evaluation.SupplyChainEvaluationDataset.SupplierExpectation;
import com.alibaba.cloud.ai.dataagent.supplychain.model.InventoryPosition;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskLevel;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainOrder;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplierRiskProfile;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.SupplyChainDemoData;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Checks the evaluation file itself before any business result is scored. These checks
 * answer "is this a complete, self-consistent dataset?" rather than "did the business
 * rule change?".
 */
public final class SupplyChainEvaluationDatasetValidator {

    private static final int MIN_CASES = 8;

    private static final int MAX_CASES = 15;

    private SupplyChainEvaluationDatasetValidator() {
    }

    public static void validate(SupplyChainEvaluationDataset dataset, SupplyChainDemoData demoData,
            Set<String> knownSourceIds) {
        if (dataset == null) {
            throw new IllegalArgumentException("Evaluation dataset must not be null");
        }
        List<String> errors = new ArrayList<>();
        requireText(dataset.datasetVersion(), "datasetVersion", errors);
        requireText(dataset.description(), "description", errors);
        if (dataset.cases().size() < MIN_CASES || dataset.cases().size() > MAX_CASES) {
            errors.add("cases must contain between " + MIN_CASES + " and " + MAX_CASES + " samples, got "
                    + dataset.cases().size());
        }

        Set<String> caseIds = new HashSet<>();
        Set<String> orderTargets = new LinkedHashSet<>();
        Set<String> inventoryTargets = new LinkedHashSet<>();
        Set<String> supplierTargets = new LinkedHashSet<>();
        Set<String> demoOrderIds = demoData.orders().stream().map(SupplyChainOrder::orderId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> demoInventorySkus = demoData.inventory().stream().map(InventoryPosition::sku)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> demoSupplierIds = demoData.suppliers().stream().map(SupplierRiskProfile::supplierId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        boolean hasPositiveOrder = false;
        boolean hasNegativeOrder = false;
        long retrievalCases = 0;
        long reportCases = 0;

        for (EvaluationCase evaluationCase : dataset.cases()) {
            if (evaluationCase == null) {
                errors.add("cases must not contain null entries");
                continue;
            }
            String prefix = evaluationCase.id() == null ? "<missing-id>" : evaluationCase.id();
            requireText(evaluationCase.id(), prefix + ".id", errors);
            requireText(evaluationCase.scenario(), prefix + ".scenario", errors);
            requireText(evaluationCase.basis(), prefix + ".basis", errors);
            if (evaluationCase.id() != null && !caseIds.add(evaluationCase.id())) {
                errors.add(prefix + ": duplicate case id");
            }
            Kind kind = evaluationCase.kind();
            if (kind == null) {
                errors.add(prefix + ": kind is required");
                continue;
            }
            switch (kind) {
                case ORDER_RISK -> {
                    RiskExpectation risk = evaluationCase.risk();
                    if (risk == null) {
                        errors.add(prefix + ": risk expectation is required");
                        break;
                    }
                    String target = evaluationCase.targetId();
                    if (!requireTarget(target, demoOrderIds, prefix + ".targetId", errors)) {
                        break;
                    }
                    if (!orderTargets.add(target)) {
                        errors.add(prefix + ": duplicate ORDER_RISK target " + target);
                    }
                    if (risk.level() == null) {
                        errors.add(prefix + ": risk.level is required");
                    }
                    if (risk.score() == null || risk.score() < 0 || risk.score() > 100) {
                        errors.add(prefix + ": risk.score must be between 0 and 100");
                    }
                    if (risk.shortage() == null || risk.shortage() < 0) {
                        errors.add(prefix + ": risk.shortage must be zero or greater");
                    }
                    if (risk.atRisk() == null) {
                        errors.add(prefix + ": risk.atRisk is required");
                    }
                    else if (risk.level() != null && risk.atRisk() != (risk.level() != RiskLevel.LOW)) {
                        errors.add(prefix + ": risk.atRisk must match risk.level");
                    }
                    if (risk.factorFragments().isEmpty()) {
                        errors.add(prefix + ": risk.factorFragments must not be empty");
                    }
                    if (risk.actionFragments().isEmpty()) {
                        errors.add(prefix + ": risk.actionFragments must not be empty");
                    }
                    if (Boolean.TRUE.equals(risk.atRisk())) {
                        hasPositiveOrder = true;
                    }
                    if (Boolean.FALSE.equals(risk.atRisk())) {
                        hasNegativeOrder = true;
                    }
                }
                case INVENTORY -> {
                    InventoryExpectation inventory = evaluationCase.inventory();
                    if (inventory == null) {
                        errors.add(prefix + ": inventory expectation is required");
                        break;
                    }
                    String target = evaluationCase.targetId();
                    if (!requireTarget(target, demoInventorySkus, prefix + ".targetId", errors)) {
                        break;
                    }
                    if (!inventoryTargets.add(target)) {
                        errors.add(prefix + ": duplicate INVENTORY target " + target);
                    }
                    validateInventory(inventory, prefix, errors);
                }
                case SUPPLIER -> {
                    SupplierExpectation supplier = evaluationCase.supplier();
                    if (supplier == null) {
                        errors.add(prefix + ": supplier expectation is required");
                        break;
                    }
                    String target = evaluationCase.targetId();
                    if (!requireTarget(target, demoSupplierIds, prefix + ".targetId", errors)) {
                        break;
                    }
                    if (!supplierTargets.add(target)) {
                        errors.add(prefix + ": duplicate SUPPLIER target " + target);
                    }
                    if (supplier.level() == null) {
                        errors.add(prefix + ": supplier.level is required");
                    }
                    validateRate(supplier.onTimeDeliveryRate(), prefix + ".supplier.onTimeDeliveryRate", errors);
                    validateRate(supplier.defectRate(), prefix + ".supplier.defectRate", errors);
                }
                case RETRIEVAL -> {
                    retrievalCases++;
                    RetrievalExpectation retrieval = evaluationCase.retrieval();
                    requireText(evaluationCase.question(), prefix + ".question", errors);
                    if (retrieval == null || retrieval.expectedSourceIds().isEmpty()) {
                        errors.add(prefix + ": retrieval.expectedSourceIds must not be empty");
                        break;
                    }
                    if (new HashSet<>(retrieval.expectedSourceIds()).size() != retrieval.expectedSourceIds().size()) {
                        errors.add(prefix + ": retrieval.expectedSourceIds must be unique");
                    }
                    for (String sourceId : retrieval.expectedSourceIds()) {
                        if (!knownSourceIds.contains(sourceId)) {
                            errors.add(prefix + ": unknown SOP source " + sourceId);
                        }
                    }
                }
                case REPORT_FALLBACK -> {
                    reportCases++;
                    validateReport(evaluationCase, demoOrderIds, knownSourceIds, prefix, errors);
                }
            }
        }

        if (!orderTargets.equals(demoOrderIds)) {
            errors.add("ORDER_RISK targets must cover every demo order exactly once; expected=" + demoOrderIds
                    + " actual=" + orderTargets);
        }
        if (!inventoryTargets.equals(demoInventorySkus)) {
            errors.add("INVENTORY targets must cover every demo SKU exactly once; expected=" + demoInventorySkus
                    + " actual=" + inventoryTargets);
        }
        if (!supplierTargets.equals(demoSupplierIds)) {
            errors.add("SUPPLIER targets must cover every demo supplier exactly once; expected=" + demoSupplierIds
                    + " actual=" + supplierTargets);
        }
        if (!hasPositiveOrder || !hasNegativeOrder) {
            errors.add("ORDER_RISK samples must include both at-risk and non-at-risk orders");
        }
        if (retrievalCases == 0) {
            errors.add("at least one RETRIEVAL sample is required");
        }
        if (reportCases == 0) {
            errors.add("at least one REPORT_FALLBACK sample is required");
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Invalid evaluation dataset:\n - "
                    + String.join("\n - ", errors));
        }
    }

    private static void validateInventory(InventoryExpectation inventory, String prefix, List<String> errors) {
        if (inventory.onHandQuantity() == null || inventory.onHandQuantity() < 0) {
            errors.add(prefix + ": inventory.onHandQuantity must be zero or greater");
        }
        if (inventory.reservedQuantity() == null || inventory.reservedQuantity() < 0) {
            errors.add(prefix + ": inventory.reservedQuantity must be zero or greater");
        }
        if (inventory.safetyStock() == null || inventory.safetyStock() < 0) {
            errors.add(prefix + ": inventory.safetyStock must be zero or greater");
        }
        if (inventory.availableToPromise() == null || inventory.availableToPromise() < 0) {
            errors.add(prefix + ": inventory.availableToPromise must be zero or greater");
        }
        else if (inventory.onHandQuantity() != null && inventory.reservedQuantity() != null
                && inventory.safetyStock() != null) {
            int calculated = Math.max(0, inventory.onHandQuantity() - inventory.reservedQuantity()
                    - inventory.safetyStock());
            if (calculated != inventory.availableToPromise()) {
                errors.add(prefix + ": inventory.availableToPromise must equal max(0, onHand - reserved - safety), expected "
                        + calculated + " got " + inventory.availableToPromise());
            }
        }
    }

    private static void validateReport(EvaluationCase evaluationCase, Set<String> demoOrderIds,
            Set<String> knownSourceIds, String prefix, List<String> errors) {
        ReportExpectation report = evaluationCase.report();
        requireText(evaluationCase.question(), prefix + ".question", errors);
        if (report == null) {
            errors.add(prefix + ": report expectation is required");
            return;
        }
        requireText(report.status(), prefix + ".report.status", errors);
        if (report.atRiskOrderIds().isEmpty()) {
            errors.add(prefix + ": report.atRiskOrderIds must not be empty");
        }
        if (new HashSet<>(report.atRiskOrderIds()).size() != report.atRiskOrderIds().size()) {
            errors.add(prefix + ": report.atRiskOrderIds must be unique");
        }
        for (String orderId : report.atRiskOrderIds()) {
            if (!demoOrderIds.contains(orderId)) {
                errors.add(prefix + ": report.atRiskOrderIds contains unknown order " + orderId);
            }
        }
        if (report.citationSourceIds().isEmpty()) {
            errors.add(prefix + ": report.citationSourceIds must not be empty");
        }
        for (String sourceId : report.citationSourceIds()) {
            if (!knownSourceIds.contains(sourceId)) {
                errors.add(prefix + ": report.citationSourceIds contains unknown SOP source " + sourceId);
            }
        }
        if (report.summaryFragments().isEmpty()) {
            errors.add(prefix + ": report.summaryFragments must not be empty");
        }
        if (report.recommendationFragments().isEmpty()) {
            errors.add(prefix + ": report.recommendationFragments must not be empty");
        }
        if (report.planSteps().isEmpty()) {
            errors.add(prefix + ": report.planSteps must not be empty");
        }
        if (report.plannerAssisted() == null || report.modelAssisted() == null || report.toolCallsEmpty() == null) {
            errors.add(prefix + ": report assistance flags are required");
        }
        if (report.validationAttempts() == null || report.validationAttempts() < 0) {
            errors.add(prefix + ": report.validationAttempts must be zero or greater");
        }
        if (report.modelFailedEvents() == null || report.modelFailedEvents() < 0
                || report.fallbackEvents() == null || report.fallbackEvents() < 0
                || report.modelCompletedEvents() == null || report.modelCompletedEvents() < 0) {
            errors.add(prefix + ": report event counts must be zero or greater");
        }
        if (report.taskCompleted() == null || report.taskFailed() == null) {
            errors.add(prefix + ": report task outcome flags are required");
        }
        else if (report.taskCompleted() && report.taskFailed()) {
            errors.add(prefix + ": report cannot both complete and fail");
        }
    }

    private static void validateRate(Double value, String field, List<String> errors) {
        if (value == null || value < 0.0 || value > 1.0) {
            errors.add(field + " must be between 0 and 1");
        }
    }

    private static boolean requireTarget(String target, Set<String> available, String field, List<String> errors) {
        if (target == null || target.isBlank()) {
            errors.add(field + " is required");
            return false;
        }
        if (!available.contains(target)) {
            errors.add(field + " references unknown demo identifier " + target);
            return false;
        }
        return true;
    }

    private static void requireText(String value, String field, List<String> errors) {
        if (value == null || value.isBlank()) {
            errors.add(field + " is required");
        }
    }
}
