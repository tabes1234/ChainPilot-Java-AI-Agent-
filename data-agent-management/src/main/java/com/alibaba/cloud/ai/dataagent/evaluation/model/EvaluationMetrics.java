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
package com.alibaba.cloud.ai.dataagent.evaluation.model;

import java.util.List;

/**
 * Metrics for exactly one evaluation mode. Nullable durations mean "not enough samples".
 * The mode field prevents a deterministic OFFLINE pass rate from being presented as model
 * accuracy.
 */
public record EvaluationMetrics(EvaluationMode mode, int runsAnalyzed, int caseSamples, double passRate,
        double failRate, double undeterminedRate, Double averageDurationMs, Double p95DurationMs,
        int p95SampleSize, Double averageRunDurationMs, Double p95RunDurationMs, int runSampleSize,
        List<ScenarioMetric> byScenario, List<FailureReason> topFailureReasons, RetrievalMetric retrieval) {

    public EvaluationMetrics {
        byScenario = byScenario == null ? List.of() : List.copyOf(byScenario);
        topFailureReasons = topFailureReasons == null ? List.of() : List.copyOf(topFailureReasons);
    }

    public EvaluationMetrics(int runsAnalyzed, int caseSamples, double passRate, double failRate,
            double undeterminedRate, Double averageDurationMs, Double p95DurationMs, int p95SampleSize,
            List<ScenarioMetric> byScenario, List<FailureReason> topFailureReasons, RetrievalMetric retrieval) {
        this(null, runsAnalyzed, caseSamples, passRate, failRate, undeterminedRate, averageDurationMs, p95DurationMs,
                p95SampleSize, null, null, 0, byScenario, topFailureReasons, retrieval);
    }

    public record ScenarioMetric(String category, int total, int passed, int failed, int undetermined,
            double failureRate) {
    }

    public record FailureReason(String failureType, String message, int count) {
    }

    public record RetrievalMetric(int evaluatedQueries, int hits, double hitRateAt3, double mrr, int negativeQueries,
            int negativePasses) {
    }

}
