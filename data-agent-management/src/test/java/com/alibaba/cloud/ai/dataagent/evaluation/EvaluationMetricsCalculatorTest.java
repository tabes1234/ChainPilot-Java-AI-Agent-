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
package com.alibaba.cloud.ai.dataagent.evaluation;

import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationAssertionResult;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationCaseResult;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationCaseStatus;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationMetrics;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationMetricsReport;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationMode;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationRun;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationRunStatus;
import com.alibaba.cloud.ai.dataagent.evaluation.service.EvaluationMetricsCalculator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluationMetricsCalculatorTest {

    private final EvaluationMetricsCalculator calculator = new EvaluationMetricsCalculator(new ObjectMapper());

    @Test
    void separatesOfflineAndAgentMetricsInsteadOfMergingThem() {
        EvaluationRun offline = run("offline", EvaluationMode.OFFLINE,
                result("risk-1", "risk", EvaluationCaseStatus.PASSED, 100, null, null, null),
                result("risk-2", "risk", EvaluationCaseStatus.PASSED, 200, null, null, null));
        EvaluationRun agent = run("agent", EvaluationMode.AGENT,
                result("wf-1", "workflow", EvaluationCaseStatus.FAILED, 400, "ASSERTION_MISMATCH", "报告缺失", null),
                result("wf-2", "workflow", EvaluationCaseStatus.UNDETERMINED, 500, "TIMEOUT", "任务超时", null));

        EvaluationMetricsReport report = calculator.report(List.of(offline, agent));

        assertEquals(2, report.runsAnalyzed());
        assertEquals(0, report.excludedIncompleteRuns());
        EvaluationMetrics offlineMetrics = byMode(report, EvaluationMode.OFFLINE);
        EvaluationMetrics agentMetrics = byMode(report, EvaluationMode.AGENT);
        assertEquals(2, offlineMetrics.caseSamples());
        assertEquals(1.0, offlineMetrics.passRate());
        assertEquals(0.0, offlineMetrics.failRate());
        assertEquals(2, agentMetrics.caseSamples());
        assertEquals(0.0, agentMetrics.passRate());
        assertEquals(0.5, agentMetrics.failRate());
        assertEquals(0.5, agentMetrics.undeterminedRate());
        assertNotNull(offlineMetrics.averageRunDurationMs());
        assertNotNull(agentMetrics.averageRunDurationMs());
    }

    @Test
    void computesRatesScenarioFailuresAndReasons() {
        EvaluationMetrics metrics = calculator.calculate(EvaluationMode.OFFLINE, List.of(run("run-1",
                EvaluationMode.OFFLINE,
                result("risk-1", "risk", EvaluationCaseStatus.PASSED, 100, null, null, null),
                result("risk-2", "risk", EvaluationCaseStatus.FAILED, 200, "ASSERTION_MISMATCH", "风险等级不一致", null),
                result("rag-1", "rag", EvaluationCaseStatus.UNDETERMINED, 300, "TIMEOUT", "任务超时", null))));

        assertEquals(EvaluationMode.OFFLINE, metrics.mode());
        assertEquals(1, metrics.runsAnalyzed());
        assertEquals(3, metrics.caseSamples());
        assertEquals(0.3333, metrics.passRate());
        assertEquals(0.3333, metrics.failRate());
        assertEquals(0.3333, metrics.undeterminedRate());
        assertEquals(200.0, metrics.averageDurationMs());
        assertNull(metrics.p95DurationMs());
        assertEquals(3, metrics.p95SampleSize());
        assertEquals(2, metrics.byScenario().size());
        assertEquals("risk", metrics.byScenario().get(1).category());
        assertEquals(0.5, metrics.byScenario().get(1).failureRate());
        assertEquals(1, metrics.topFailureReasons().size());
        assertEquals("ASSERTION_MISMATCH", metrics.topFailureReasons().get(0).failureType());
    }

    @Test
    void reportsP95OnlyWithAtLeastFiveTimedCaseSamples() {
        EvaluationRun offline = run("run-1", EvaluationMode.OFFLINE, fiveResults(100, "offline"));
        EvaluationRun agent = run("run-2", EvaluationMode.AGENT,
                result("a-1", "workflow", EvaluationCaseStatus.PASSED, 10, null, null, null),
                result("a-2", "workflow", EvaluationCaseStatus.PASSED, 20, null, null, null));

        EvaluationMetrics offlineMetrics = calculator.calculate(EvaluationMode.OFFLINE, List.of(offline));
        EvaluationMetrics agentMetrics = calculator.calculate(EvaluationMode.AGENT, List.of(agent));

        assertEquals(5, offlineMetrics.p95SampleSize());
        assertEquals(500.0, offlineMetrics.p95DurationMs());
        assertEquals(2, agentMetrics.p95SampleSize());
        assertNull(agentMetrics.p95DurationMs());
    }

    @Test
    void computesRetrievalHitRateAndMrrFromRealObservations() {
        String hitObservation = "{\"expected\":[\"SC-SOP-001.md\"],\"actual\":[\"OTHER.md\",\"SC-SOP-001.md\"],\"rank\":2,\"topK\":3}";
        String negativeObservation = "{\"expected\":[],\"actual\":[],\"rank\":0,\"topK\":3}";
        String missObservation = "{\"expected\":[\"SC-SOP-002.md\"],\"actual\":[\"OTHER.md\"],\"rank\":0,\"topK\":3}";
        EvaluationMetrics metrics = calculator.calculate(EvaluationMode.OFFLINE, List.of(run("run-1",
                EvaluationMode.OFFLINE,
                result("rag-1", "rag", EvaluationCaseStatus.PASSED, 10, null, null, hitObservation),
                result("rag-2", "rag", EvaluationCaseStatus.PASSED, 10, null, null, negativeObservation),
                result("rag-3", "rag", EvaluationCaseStatus.FAILED, 10, "ASSERTION_MISMATCH", "未命中", missObservation))));

        assertNotNull(metrics.retrieval());
        assertEquals(2, metrics.retrieval().evaluatedQueries());
        assertEquals(1, metrics.retrieval().hits());
        assertEquals(0.5, metrics.retrieval().hitRateAt3());
        assertEquals(0.25, metrics.retrieval().mrr());
        assertEquals(1, metrics.retrieval().negativeQueries());
        assertEquals(1, metrics.retrieval().negativePasses());
    }

    @Test
    void hidesRetrievalMetricsWhenThereAreNoReliableRetrievalSamples() {
        EvaluationMetrics metrics = calculator.calculate(EvaluationMode.AGENT, List.of(run("run-1",
                EvaluationMode.AGENT, result("wf", "workflow", EvaluationCaseStatus.PASSED, 10, null, null, null))));

        assertNull(metrics.retrieval());
        assertEquals(1, metrics.caseSamples());
    }

    @Test
    void excludesIncompleteAndInconsistentRunsFromTheDenominator() {
        EvaluationRun complete = run("complete", EvaluationMode.OFFLINE,
                result("risk-1", "risk", EvaluationCaseStatus.PASSED, 100, null, null, null));
        EvaluationRun running = runWithStatus("running", EvaluationRunStatus.RUNNING, List.of(
                result("risk-2", "risk", EvaluationCaseStatus.PASSED, 100, null, null, null)), 1, 1, 0, 0);
        EvaluationRun missingResult = new EvaluationRun("missing", "1.0.0", EvaluationMode.OFFLINE,
                EvaluationRunStatus.COMPLETED, "TEST", Instant.now(), Instant.now(), 2, 1, 0, 0, 10L, Map.of(),
                List.of(result("risk-3", "risk", EvaluationCaseStatus.PASSED, 100, null, null, null)));
        EvaluationRun inconsistentCounts = new EvaluationRun("inconsistent", "1.0.0", EvaluationMode.OFFLINE,
                EvaluationRunStatus.COMPLETED, "TEST", Instant.now(), Instant.now(), 1, 0, 0, 0, 10L, Map.of(),
                List.of(result("risk-4", "risk", EvaluationCaseStatus.PASSED, 100, null, null, null)));

        EvaluationMetricsReport report = calculator.report(List.of(complete, running, missingResult,
                inconsistentCounts));

        assertEquals(1, report.runsAnalyzed());
        assertEquals(3, report.excludedIncompleteRuns());
        assertEquals(1, byMode(report, EvaluationMode.OFFLINE).caseSamples());
        assertTrue(EvaluationMetricsCalculator.isCompleteRun(complete));
        assertFalse(EvaluationMetricsCalculator.isCompleteRun(running));
        assertFalse(EvaluationMetricsCalculator.isCompleteRun(missingResult));
        assertFalse(EvaluationMetricsCalculator.isCompleteRun(inconsistentCounts));
    }

    @Test
    void zeroSamplesNeverProduceNaN() {
        EvaluationMetricsReport report = calculator.report(List.of());
        assertEquals(0, report.runsAnalyzed());
        assertEquals(0, report.excludedIncompleteRuns());
        for (EvaluationMetrics metrics : report.byMode()) {
            assertEquals(0, metrics.caseSamples());
            assertEquals(0.0, metrics.passRate());
            assertNull(metrics.averageDurationMs());
            assertNull(metrics.p95DurationMs());
            assertNull(metrics.retrieval());
            assertTrue(metrics.topFailureReasons().isEmpty());
        }
    }

    private static EvaluationMetrics byMode(EvaluationMetricsReport report, EvaluationMode mode) {
        return report.byMode().stream().filter(metrics -> metrics.mode() == mode).findFirst().orElseThrow();
    }

    private static EvaluationCaseResult[] fiveResults(long start, String prefix) {
        List<EvaluationCaseResult> results = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            results.add(result(prefix + index, "risk", EvaluationCaseStatus.PASSED, start * (index + 1), null, null,
                    null));
        }
        return results.toArray(EvaluationCaseResult[]::new);
    }

    private static EvaluationRun run(String runId, EvaluationMode mode, EvaluationCaseResult... results) {
        int passed = (int) List.of(results).stream().filter(r -> r.status() == EvaluationCaseStatus.PASSED).count();
        int failed = (int) List.of(results).stream().filter(r -> r.status() == EvaluationCaseStatus.FAILED).count();
        int undetermined = (int) List.of(results).stream()
            .filter(r -> r.status() == EvaluationCaseStatus.UNDETERMINED)
            .count();
        return runWithStatus(runId, EvaluationRunStatus.COMPLETED, List.of(results), results.length, passed, failed,
                undetermined);
    }

    private static EvaluationRun runWithStatus(String runId, EvaluationRunStatus status,
            List<EvaluationCaseResult> results, int total, int passed, int failed, int undetermined) {
        Instant completedAt = status == EvaluationRunStatus.COMPLETED ? Instant.now() : null;
        String version = status == EvaluationRunStatus.COMPLETED ? "1.0.0" : "1.0.0";
        return new EvaluationRun(runId, version, runMode(results), status, "TEST", Instant.now(), completedAt, total,
                passed, failed, undetermined, 10L, Map.of(), results);
    }

    private static EvaluationMode runMode(List<EvaluationCaseResult> results) {
        return results.stream().anyMatch(result -> "workflow".equals(result.category()))
                ? EvaluationMode.AGENT : EvaluationMode.OFFLINE;
    }

    private static EvaluationCaseResult result(String caseId, String category, EvaluationCaseStatus status,
            long duration, String failureType, String failureMessage, String observation) {
        return new EvaluationCaseResult(caseId, caseId, caseId, category, status, duration, null,
                List.of(new EvaluationAssertionResult("a", "assertion", "expected", "actual",
                        status == EvaluationCaseStatus.PASSED)),
                observation, failureType, failureMessage, "1.0.0", "workflow".equals(category) ? "ANALYSIS" : null);
    }

}
