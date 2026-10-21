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
package com.alibaba.cloud.ai.dataagent.supplychain.model;

import java.time.Instant;

/** History row for the comparison list; full candidate simulations live in the detail record. */
public record DisposalScenarioComparisonSummary(String comparisonId, String title, SimulationScenario scenario,
        String sku, String supplierId, int candidateCount, String baselineSource, String baselineFingerprint,
        DisposalScenarioComparisonOutcome outcome, String recommendedCandidateId, String recommendedLabel,
        Integer recommendedRank, int baselineAtRiskOrders, int baselineTotalShortageUnits,
        Integer bestAtRiskOrdersDelta, Integer bestTotalShortageUnitsDelta, int linkedPlanCount,
        Instant createdAt, Instant updatedAt) {
}
