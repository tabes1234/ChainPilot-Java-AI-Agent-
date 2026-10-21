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

import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskLevel;

import java.util.List;

/**
 * Hand-curated offline evaluation data. Expectations live outside the business
 * implementation so a rule change is reported as an evaluation failure rather than
 * silently copied into the test.
 */
public record SupplyChainEvaluationDataset(String datasetVersion, String description, List<EvaluationCase> cases) {

    public SupplyChainEvaluationDataset {
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    public enum Kind {
        ORDER_RISK,
        INVENTORY,
        SUPPLIER,
        RETRIEVAL,
        REPORT_FALLBACK
    }

    public record EvaluationCase(String id, Kind kind, String scenario, String question, String targetId,
            RiskExpectation risk, InventoryExpectation inventory, SupplierExpectation supplier,
            RetrievalExpectation retrieval, ReportExpectation report, String basis) {
    }

    public record RiskExpectation(RiskLevel level, Integer score, Integer shortage, Boolean atRisk,
            List<String> factorFragments, List<String> actionFragments) {

        public RiskExpectation {
            factorFragments = factorFragments == null ? List.of() : List.copyOf(factorFragments);
            actionFragments = actionFragments == null ? List.of() : List.copyOf(actionFragments);
        }
    }

    public record InventoryExpectation(Integer onHandQuantity, Integer reservedQuantity, Integer safetyStock,
            Integer availableToPromise) {
    }

    public record SupplierExpectation(RiskLevel level, Double onTimeDeliveryRate, Double defectRate) {
    }

    public record RetrievalExpectation(List<String> expectedSourceIds) {

        public RetrievalExpectation {
            expectedSourceIds = expectedSourceIds == null ? List.of() : List.copyOf(expectedSourceIds);
        }
    }

    public record ReportExpectation(String status, List<String> atRiskOrderIds, List<String> summaryFragments,
            List<String> citationSourceIds, List<String> recommendationFragments, List<String> planSteps,
            Boolean plannerAssisted, Boolean modelAssisted, Boolean toolCallsEmpty, Integer validationAttempts,
            Integer modelFailedEvents, Integer fallbackEvents, Integer modelCompletedEvents, Boolean taskCompleted,
            Boolean taskFailed) {

        public ReportExpectation {
            atRiskOrderIds = atRiskOrderIds == null ? List.of() : List.copyOf(atRiskOrderIds);
            summaryFragments = summaryFragments == null ? List.of() : List.copyOf(summaryFragments);
            citationSourceIds = citationSourceIds == null ? List.of() : List.copyOf(citationSourceIds);
            recommendationFragments = recommendationFragments == null ? List.of() : List.copyOf(recommendationFragments);
            planSteps = planSteps == null ? List.of() : List.copyOf(planSteps);
        }
    }
}
