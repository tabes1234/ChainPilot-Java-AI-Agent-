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
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationMode;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationRun;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationRunComparison;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationRunStatus;
import com.alibaba.cloud.ai.dataagent.evaluation.repository.EvaluationRunRepository;
import com.alibaba.cloud.ai.dataagent.evaluation.service.EvaluationComparisonService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EvaluationComparisonServiceTest {

    private EvaluationRunRepository repository;

    private EvaluationComparisonService service;

    @BeforeEach
    void setUp() {
        repository = mock(EvaluationRunRepository.class);
        service = new EvaluationComparisonService(repository);
    }

    @Test
    void identifiesFixedNewAndPersistentFailuresWithAssertionChanges() {
        EvaluationRun base = run("base", EvaluationMode.OFFLINE,
                caseResult("A", EvaluationCaseStatus.PASSED, assertion("a", true, "1", "1")),
                caseResult("B", EvaluationCaseStatus.FAILED, assertion("b", false, "2", "1")),
                caseResult("C", EvaluationCaseStatus.FAILED, assertion("c", false, "3", "1")));
        EvaluationRun target = run("target", EvaluationMode.OFFLINE,
                caseResult("A", EvaluationCaseStatus.PASSED, assertion("a", true, "1", "1")),
                caseResult("B", EvaluationCaseStatus.PASSED, assertion("b", true, "2", "2")),
                caseResult("C", EvaluationCaseStatus.FAILED, assertion("c", false, "3", "1")));
        stub(base, target);

        EvaluationRunComparison comparison = service.compare("base", "target");

        assertTrue(comparison.comparable());
        assertEquals(List.of("B"), comparison.fixedFailures());
        assertEquals(List.of("C"), comparison.persistentFailures());
        assertTrue(comparison.newFailures().isEmpty());
        assertEquals(1, comparison.cases().stream().filter(item -> "B".equals(item.caseId())).findFirst()
            .orElseThrow()
            .assertionChanges()
            .size());
        assertEquals("IMPROVED", comparison.cases().stream().filter(item -> "B".equals(item.caseId())).findFirst()
            .orElseThrow()
            .assertionChanges()
            .get(0)
            .change());
        assertEquals(1, comparison.delta().passed());
        assertEquals(-1, comparison.delta().failed());
    }

    @Test
    void identifiesANewFailureAndAValueOnlyAssertionChange() {
        EvaluationRun base = run("base", EvaluationMode.OFFLINE,
                caseResult("A", EvaluationCaseStatus.PASSED, assertion("a", true, "1", "1")),
                caseResult("B", EvaluationCaseStatus.PASSED, assertion("b", true, "same", "old")));
        EvaluationRun target = run("target", EvaluationMode.OFFLINE,
                caseResult("A", EvaluationCaseStatus.FAILED, assertion("a", false, "1", "0")),
                caseResult("B", EvaluationCaseStatus.PASSED, assertion("b", true, "same", "new")));
        stub(base, target);

        EvaluationRunComparison comparison = service.compare("base", "target");

        assertTrue(comparison.comparable());
        assertEquals(List.of("A"), comparison.newFailures());
        assertEquals("REGRESSED", comparison.cases().stream().filter(item -> "A".equals(item.caseId())).findFirst()
            .orElseThrow()
            .assertionChanges()
            .get(0)
            .change());
        assertEquals("VALUE_CHANGED", comparison.cases().stream().filter(item -> "B".equals(item.caseId())).findFirst()
            .orElseThrow()
            .assertionChanges()
            .get(0)
            .change());
    }

    @Test
    void refusesToCompareDifferentModesOrVersions() {
        EvaluationRun offline = run("offline", EvaluationMode.OFFLINE,
                caseResult("A", EvaluationCaseStatus.PASSED, assertion("a", true, "1", "1")));
        EvaluationRun agent = run("agent", EvaluationMode.AGENT,
                caseResult("A", EvaluationCaseStatus.PASSED, assertion("a", true, "1", "1")));
        stub(offline, agent);
        EvaluationRunComparison modeMismatch = service.compare("offline", "agent");
        assertFalse(modeMismatch.comparable());
        assertTrue(modeMismatch.reason().contains("模式不同"));

        EvaluationRun base = run("base", EvaluationMode.OFFLINE, "1.0.0",
                caseResult("A", EvaluationCaseStatus.PASSED, assertion("a", true, "1", "1")));
        EvaluationRun target = run("target", EvaluationMode.OFFLINE, "2.0.0",
                caseResult("A", EvaluationCaseStatus.PASSED, assertion("a", true, "1", "1")));
        stub(base, target);
        EvaluationRunComparison versionMismatch = service.compare("base", "target");
        assertFalse(versionMismatch.comparable());
        assertTrue(versionMismatch.reason().contains("版本不同"));
    }

    @Test
    void reportsMissingAndExtraCasesInsteadOfMatchingByName() {
        EvaluationRun base = run("base", EvaluationMode.OFFLINE,
                caseResult("A", EvaluationCaseStatus.PASSED, assertion("a", true, "1", "1")));
        EvaluationRun target = run("target", EvaluationMode.OFFLINE,
                caseResult("B", EvaluationCaseStatus.PASSED, assertion("a", true, "1", "1")));
        stub(base, target);

        EvaluationRunComparison comparison = service.compare("base", "target");

        assertFalse(comparison.comparable());
        assertEquals(List.of("A"), comparison.missingInTarget());
        assertEquals(List.of("B"), comparison.extraInTarget());
        assertTrue(comparison.cases().isEmpty());
    }

    @Test
    void refusesAssertionSetMismatchesButStillExposesTheReason() {
        EvaluationRun base = run("base", EvaluationMode.OFFLINE,
                caseResult("A", EvaluationCaseStatus.PASSED, assertion("a", true, "1", "1")));
        EvaluationRun target = run("target", EvaluationMode.OFFLINE,
                caseResult("A", EvaluationCaseStatus.PASSED, assertion("different-id", true, "1", "1")));
        stub(base, target);

        EvaluationRunComparison comparison = service.compare("base", "target");

        assertFalse(comparison.comparable());
        assertEquals(List.of("A"), comparison.assertionSetMismatches());
        assertEquals(1, comparison.cases().size());
        assertEquals("INCOMPARABLE", comparison.cases().get(0).change());
        assertFalse(comparison.cases().get(0).assertionSetsCompatible());
    }

    @Test
    void refusesIncompleteRunsAndSelfComparison() {
        EvaluationRun running = new EvaluationRun("running", "1.0.0", EvaluationMode.OFFLINE,
                EvaluationRunStatus.RUNNING, "TEST", Instant.now(), null, 2, 1, 0, 0, null, Map.of(),
                List.of(caseResult("A", EvaluationCaseStatus.PASSED, assertion("a", true, "1", "1"))));
        EvaluationRun complete = run("complete", EvaluationMode.OFFLINE,
                caseResult("A", EvaluationCaseStatus.PASSED, assertion("a", true, "1", "1")));
        stub(running, complete);

        EvaluationRunComparison comparison = service.compare("running", "complete");

        assertFalse(comparison.comparable());
        assertTrue(comparison.reason().contains("COMPLETED"));
        assertThrows(ResponseStatusException.class, () -> service.compare("complete", "complete"));
    }

    @Test
    void keepsP95SampleSizesExplicitWhenThereAreTooFewCases() {
        EvaluationRun base = run("base", EvaluationMode.OFFLINE,
                caseResult("A", EvaluationCaseStatus.PASSED, assertion("a", true, "1", "1")));
        EvaluationRun target = run("target", EvaluationMode.OFFLINE,
                caseResult("A", EvaluationCaseStatus.PASSED, assertion("a", true, "1", "1")));
        stub(base, target);

        EvaluationRunComparison comparison = service.compare("base", "target");

        assertNull(comparison.baseRun().p95CaseDurationMs());
        assertNull(comparison.targetRun().p95CaseDurationMs());
        assertEquals(1, comparison.baseRun().p95SampleSize());
        assertNull(comparison.delta().p95CaseDurationMs());
    }

    private void stub(EvaluationRun base, EvaluationRun target) {
        when(repository.find(base.runId())).thenReturn(Optional.of(base));
        when(repository.find(target.runId())).thenReturn(Optional.of(target));
    }

    private static EvaluationRun run(String runId, EvaluationMode mode, EvaluationCaseResult... results) {
        return run(runId, mode, "1.0.0", results);
    }

    private static EvaluationRun run(String runId, EvaluationMode mode, String version,
            EvaluationCaseResult... results) {
        int passed = (int) List.of(results).stream().filter(r -> r.status() == EvaluationCaseStatus.PASSED).count();
        int failed = (int) List.of(results).stream().filter(r -> r.status() == EvaluationCaseStatus.FAILED).count();
        int undetermined = (int) List.of(results).stream()
            .filter(r -> r.status() == EvaluationCaseStatus.UNDETERMINED)
            .count();
        return new EvaluationRun(runId, version, mode, EvaluationRunStatus.COMPLETED, "TEST", Instant.now(),
                Instant.now(), results.length, passed, failed, undetermined, 100L, Map.of(), List.of(results));
    }

    private static EvaluationCaseResult caseResult(String caseId, EvaluationCaseStatus status,
            EvaluationAssertionResult... assertions) {
        return new EvaluationCaseResult(caseId, caseId, caseId, "risk", status, 10, null, List.of(assertions), "{}",
                status == EvaluationCaseStatus.FAILED ? "ASSERTION_MISMATCH" : null,
                status == EvaluationCaseStatus.FAILED ? "失败" : null, "1.0.0", null);
    }

    private static EvaluationAssertionResult assertion(String id, boolean passed, String expected, String actual) {
        return new EvaluationAssertionResult(id, id, expected, actual, passed);
    }

}
