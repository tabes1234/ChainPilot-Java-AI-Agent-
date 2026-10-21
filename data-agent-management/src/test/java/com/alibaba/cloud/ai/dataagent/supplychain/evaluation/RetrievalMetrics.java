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

import java.util.List;

/**
 * Lexical retrieval metrics with a fixed top-3 cutoff. A query with one or more expected
 * source IDs counts as a hit when any expected source appears in the returned citations;
 * its reciprocal rank is the rank of the first matching source. Queries with no expected
 * sources are negative checks and are excluded from the hit-rate denominator.
 *
 * <p>Zero denominators produce {@code 0.0}, never NaN.
 */
public record RetrievalMetrics(int evaluatedQueries, int hits, double hitRateAt3,
        double meanReciprocalRank, int negativeQueries, int negativePasses) {

    public static RetrievalMetrics calculate(List<RetrievalObservation> observations) {
        int evaluated = 0;
        int hits = 0;
        double reciprocalRankSum = 0.0;
        int negativeQueries = 0;
        int negativePasses = 0;
        for (RetrievalObservation observation : observations) {
            if (observation.expectedSourceIds().isEmpty()) {
                negativeQueries++;
                if (observation.actualSourceIds().isEmpty()) {
                    negativePasses++;
                }
                continue;
            }
            evaluated++;
            int rank = firstMatchingRank(observation);
            if (rank > 0) {
                hits++;
                reciprocalRankSum += 1.0 / rank;
            }
        }
        double hitRate = evaluated == 0 ? 0.0 : (double) hits / evaluated;
        double mrr = evaluated == 0 ? 0.0 : reciprocalRankSum / evaluated;
        return new RetrievalMetrics(evaluated, hits, hitRate, mrr, negativeQueries, negativePasses);
    }

    private static int firstMatchingRank(RetrievalObservation observation) {
        for (int index = 0; index < observation.actualSourceIds().size(); index++) {
            if (observation.expectedSourceIds().contains(observation.actualSourceIds().get(index))) {
                return index + 1;
            }
        }
        return 0;
    }

    public record RetrievalObservation(List<String> expectedSourceIds, List<String> actualSourceIds) {

        public RetrievalObservation {
            expectedSourceIds = expectedSourceIds == null ? List.of() : List.copyOf(expectedSourceIds);
            actualSourceIds = actualSourceIds == null ? List.of() : List.copyOf(actualSourceIds);
        }
    }
}
