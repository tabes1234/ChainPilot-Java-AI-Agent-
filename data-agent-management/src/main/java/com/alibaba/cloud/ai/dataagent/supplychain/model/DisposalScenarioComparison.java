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
import java.time.LocalDate;
import java.util.List;

/**
 * A persisted multi-candidate comparison. All candidates were simulated from the same
 * baseline fingerprint; metrics and ranking are computed by deterministic Java rules.
 */
public record DisposalScenarioComparison(String comparisonId, String title, SimulationScenario scenario,
        String sku, String supplierId, LocalDate asOfDate, String baselineSource, String baselineFingerprint,
        Instant baselineCapturedAt, SimulationMetrics baseline, Integer bestAtRiskOrdersDelta,
        Integer bestTotalShortageUnitsDelta, List<DisposalScenarioCandidate> candidates,
        List<DisposalScenarioRanking> rankings, DisposalScenarioRecommendation recommendation,
        List<String> rankingRules, List<String> assumptions, List<String> limitations, List<String> evidence,
        boolean realDataMutated, String disclaimer, Instant createdAt, Instant updatedAt) {

    public DisposalScenarioComparison {
        candidates = List.copyOf(candidates);
        rankings = List.copyOf(rankings);
        rankingRules = List.copyOf(rankingRules);
        assumptions = List.copyOf(assumptions);
        limitations = List.copyOf(limitations);
        evidence = List.copyOf(evidence);
    }
}
