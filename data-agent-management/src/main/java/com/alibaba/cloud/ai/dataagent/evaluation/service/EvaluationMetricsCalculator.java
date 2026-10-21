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
package com.alibaba.cloud.ai.dataagent.evaluation.service;

import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationCaseResult;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationCaseStatus;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationMetrics;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationMetrics.FailureReason;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationMetrics.RetrievalMetric;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationMetrics.ScenarioMetric;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationMetricsReport;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationMode;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationRun;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationRunStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes metrics per evaluation mode. Only completed runs whose persisted result count
 * matches the run's declared case count contribute. P95 requires at least five samples;
 * otherwise it is null and the sample size is reported instead. OFFLINE and AGENT results
 * are never merged into one pass rate.
 */
@Component
public class EvaluationMetricsCalculator {

    private static final int P95_MIN_SAMPLES = 5;

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper mapper;

    public EvaluationMetricsCalculator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public EvaluationMetricsReport report(List<EvaluationRun> runs) {
        List<EvaluationRun> complete = runs.stream().filter(EvaluationMetricsCalculator::isCompleteRun).toList();
        List<EvaluationMetrics> byMode = new ArrayList<>();
        for (EvaluationMode mode : EvaluationMode.values()) {
            List<EvaluationRun> modeRuns = complete.stream().filter(run -> run.mode() == mode).toList();
            byMode.add(calculate(mode, modeRuns));
        }
        return new EvaluationMetricsReport(complete.size(), runs.size() - complete.size(), byMode);
    }

    public EvaluationMetrics calculate(EvaluationMode mode, List<EvaluationRun> inputRuns) {
        List<EvaluationRun> runs = inputRuns.stream()
            .filter(EvaluationMetricsCalculator::isCompleteRun)
            .filter(run -> run.mode() == mode)
            .toList();
        List<EvaluationCaseResult> results = runs.stream().flatMap(run -> run.results().stream()).toList();
        int total = results.size();
        long passed = results.stream().filter(result -> result.status() == EvaluationCaseStatus.PASSED).count();
        long failed = results.stream().filter(result -> result.status() == EvaluationCaseStatus.FAILED).count();
        long undetermined = results.stream().filter(result -> result.status() == EvaluationCaseStatus.UNDETERMINED).count();

        List<Long> caseDurations = results.stream()
            .filter(result -> result.status() != EvaluationCaseStatus.RUNNING)
            .map(EvaluationCaseResult::durationMs)
            .filter(duration -> duration >= 0)
            .sorted()
            .toList();
        Double caseAverage = caseDurations.isEmpty() ? null
                : round(caseDurations.stream().mapToLong(Long::longValue).average().orElse(0));
        Double caseP95 = caseDurations.size() < P95_MIN_SAMPLES ? null
                : (double) caseDurations.get(p95Index(caseDurations.size()));

        List<Long> runDurations = runs.stream()
            .map(EvaluationRun::durationMs)
            .filter(duration -> duration != null && duration >= 0)
            .sorted()
            .toList();
        Double runAverage = runDurations.isEmpty() ? null
                : round(runDurations.stream().mapToLong(Long::longValue).average().orElse(0));
        Double runP95 = runDurations.size() < P95_MIN_SAMPLES ? null
                : (double) runDurations.get(p95Index(runDurations.size()));

        Map<String, int[]> scenarioTotals = new LinkedHashMap<>();
        Map<String, String> failureKeys = new LinkedHashMap<>();
        Map<String, Integer> failureCounts = new LinkedHashMap<>();
        for (EvaluationCaseResult result : results) {
            int[] counts = scenarioTotals.computeIfAbsent(result.category() == null ? "unknown" : result.category(),
                    key -> new int[4]);
            counts[0]++;
            if (result.status() == EvaluationCaseStatus.PASSED) {
                counts[1]++;
            }
            else if (result.status() == EvaluationCaseStatus.FAILED) {
                counts[2]++;
            }
            else if (result.status() == EvaluationCaseStatus.UNDETERMINED) {
                counts[3]++;
            }
            if (result.status() == EvaluationCaseStatus.FAILED) {
                String type = result.failureType() == null ? "UNKNOWN" : result.failureType();
                String message = truncate(result.failureMessage(), 120);
                String key = type + "|" + message;
                failureKeys.putIfAbsent(key, type);
                failureCounts.merge(key, 1, Integer::sum);
            }
        }

        List<ScenarioMetric> byScenario = new ArrayList<>();
        scenarioTotals.forEach((category, counts) -> byScenario.add(new ScenarioMetric(category, counts[0], counts[1],
                counts[2], counts[3], rate(counts[2], counts[0]))));
        byScenario.sort(Comparator.comparing(ScenarioMetric::category));

        List<FailureReason> reasons = failureCounts.entrySet()
            .stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(5)
            .map(entry -> {
                String key = entry.getKey();
                int split = key.indexOf('|');
                String message = split >= 0 ? key.substring(split + 1) : key;
                return new FailureReason(failureKeys.get(key), message, entry.getValue());
            })
            .toList();

        return new EvaluationMetrics(mode, runs.size(), total, rate(passed, total), rate(failed, total),
                rate(undetermined, total), caseAverage, caseP95, caseDurations.size(), runAverage, runP95,
                runDurations.size(), byScenario, reasons, retrievalMetric(results));
    }

    /** A run contributes only when its declared cases, counters and persisted results agree. */
    public static boolean isCompleteRun(EvaluationRun run) {
        if (run == null || run.status() != EvaluationRunStatus.COMPLETED || run.completedAt() == null
                || run.totalCases() <= 0 || run.results().size() != run.totalCases()
                || run.results().stream().anyMatch(result -> result.status() == EvaluationCaseStatus.RUNNING)) {
            return false;
        }
        int passed = (int) run.results().stream()
            .filter(result -> result.status() == EvaluationCaseStatus.PASSED)
            .count();
        int failed = (int) run.results().stream()
            .filter(result -> result.status() == EvaluationCaseStatus.FAILED)
            .count();
        int undetermined = (int) run.results().stream()
            .filter(result -> result.status() == EvaluationCaseStatus.UNDETERMINED)
            .count();
        return run.passed() == passed && run.failed() == failed && run.undetermined() == undetermined;
    }

    private RetrievalMetric retrievalMetric(List<EvaluationCaseResult> results) {
        int evaluated = 0;
        int hits = 0;
        double reciprocalRankSum = 0;
        int negativeQueries = 0;
        int negativePasses = 0;
        for (EvaluationCaseResult result : results) {
            if (!"rag".equals(result.category()) || result.observationJson() == null) {
                continue;
            }
            Map<String, Object> observation;
            try {
                observation = mapper.readValue(result.observationJson(), MAP_TYPE);
            }
            catch (Exception exception) {
                continue;
            }
            List<String> expected = strings(observation.get("expected"));
            List<String> actual = strings(observation.get("actual"));
            if (expected.isEmpty()) {
                negativeQueries++;
                if (actual.isEmpty()) {
                    negativePasses++;
                }
                continue;
            }
            evaluated++;
            int rank = firstMatchingRank(expected, actual);
            if (rank > 0) {
                hits++;
                reciprocalRankSum += 1.0 / rank;
            }
        }
        if (evaluated == 0 && negativeQueries == 0) {
            return null;
        }
        return new RetrievalMetric(evaluated, hits, rate(hits, evaluated), round(evaluated == 0 ? 0
                : reciprocalRankSum / evaluated), negativeQueries, negativePasses);
    }

    private static int firstMatchingRank(List<String> expected, List<String> actual) {
        for (int index = 0; index < actual.size(); index++) {
            if (expected.contains(actual.get(index))) {
                return index + 1;
            }
        }
        return 0;
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(item -> item != null).map(String::valueOf).toList();
    }

    private static int p95Index(int sampleSize) {
        return Math.max(0, (int) Math.ceil(sampleSize * 0.95) - 1);
    }

    private static double rate(long numerator, long denominator) {
        return denominator == 0 ? 0 : round((double) numerator / denominator);
    }

    private static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private static String truncate(String value, int max) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

}
