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
 * Deterministic rank of one candidate. {@code rank} is null when the candidate cannot be
 * compared with the others; {@code tied} means another candidate shares the same metric tuple.
 */
public record DisposalScenarioRanking(String candidateId, String label, String parameterSummary,
        Integer rank, boolean tied, boolean recommended, boolean comparable, String rankingBasis,
        List<String> keyDeltas, List<String> reasons, List<String> tradeoffs) {

    public DisposalScenarioRanking {
        keyDeltas = List.copyOf(keyDeltas);
        reasons = List.copyOf(reasons);
        tradeoffs = List.copyOf(tradeoffs);
    }
}
