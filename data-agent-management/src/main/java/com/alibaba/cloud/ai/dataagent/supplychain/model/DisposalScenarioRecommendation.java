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

import java.util.List;

/**
 * Recommendation produced by the deterministic comparison engine. It names a candidate and
 * rank, but also records tradeoffs, assumptions and manual confirmations so no unmeasured
 * cost, capacity or supplier promise is invented.
 */
public record DisposalScenarioRecommendation(DisposalScenarioComparisonOutcome outcome,
        String recommendedCandidateId, String recommendedLabel, Integer recommendedRank, String summary,
        List<String> keyDeltas, List<String> reasons, List<String> tradeoffs, List<String> assumptions,
        List<String> unknowns, List<String> manualConfirmation, List<String> evidence, String disclaimer) {

    public DisposalScenarioRecommendation {
        keyDeltas = List.copyOf(keyDeltas);
        reasons = List.copyOf(reasons);
        tradeoffs = List.copyOf(tradeoffs);
        assumptions = List.copyOf(assumptions);
        unknowns = List.copyOf(unknowns);
        manualConfirmation = List.copyOf(manualConfirmation);
        evidence = List.copyOf(evidence);
    }
}
