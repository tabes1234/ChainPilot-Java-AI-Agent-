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

import java.util.Set;

/**
 * Order-level binary risk classification. The positive class is "at risk"
 * ({@code riskLevel != LOW}); the universe is the set of demo orders being evaluated.
 * Predictions outside the universe are ignored so a metric calculation cannot silently
 * mix unrelated identifiers into the sample.
 *
 * <p>Zero denominators produce {@code 0.0}, never NaN: no predicted positive means
 * precision 0, no expected positive means recall 0, and no evaluated orders means all
 * metrics 0. The raw confusion-matrix counts are retained so callers can explain the
 * denominator.
 */
public record RiskClassificationMetrics(long evaluated, long truePositives, long falsePositives,
        long falseNegatives, long trueNegatives, double precision, double recall, double f1, double accuracy) {

    public static RiskClassificationMetrics calculate(Set<String> expectedAtRisk, Set<String> predictedAtRisk,
            Set<String> evaluatedIds) {
        long evaluated = evaluatedIds.size();
        long truePositives = expectedAtRisk.stream()
                .filter(predictedAtRisk::contains)
                .filter(evaluatedIds::contains)
                .count();
        long falsePositives = predictedAtRisk.stream()
                .filter(id -> evaluatedIds.contains(id) && !expectedAtRisk.contains(id))
                .count();
        long falseNegatives = expectedAtRisk.stream()
                .filter(id -> evaluatedIds.contains(id) && !predictedAtRisk.contains(id))
                .count();
        long trueNegatives = Math.max(0, evaluated - truePositives - falsePositives - falseNegatives);
        double precision = ratio(truePositives, truePositives + falsePositives);
        double recall = ratio(truePositives, truePositives + falseNegatives);
        double f1 = precision + recall == 0.0 ? 0.0 : 2.0 * precision * recall / (precision + recall);
        double accuracy = ratio(truePositives + trueNegatives, evaluated);
        return new RiskClassificationMetrics(evaluated, truePositives, falsePositives, falseNegatives,
                trueNegatives, precision, recall, f1, accuracy);
    }

    private static double ratio(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }
}
