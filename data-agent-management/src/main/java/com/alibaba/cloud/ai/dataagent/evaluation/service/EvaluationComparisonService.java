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

import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationAssertionChange;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationAssertionResult;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationCaseComparison;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationCaseResult;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationCaseStatus;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationComparisonDelta;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationRun;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationRunComparison;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationRunSummary;
import com.alibaba.cloud.ai.dataagent.evaluation.repository.EvaluationRunRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compares two persisted complete runs. A comparison is only like-for-like when mode,
 * case-set version and case ids agree; assertion ids must also agree per case before
 * assertion-level differences are treated as meaningful.
 */
@Service
public class EvaluationComparisonService {

    private static final int P95_MIN_SAMPLES = 5;

    private final EvaluationRunRepository repository;

    public EvaluationComparisonService(EvaluationRunRepository repository) {
        this.repository = repository;
    }

    public EvaluationRunComparison compare(String baseRunId, String targetRunId) {
        if (baseRunId == null || baseRunId.isBlank() || targetRunId == null || targetRunId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须提供 baseRunId 和 targetRunId");
        }
        if (baseRunId.equals(targetRunId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能将同一次运行与自身比较");
        }
        EvaluationRun base = load(baseRunId);
        EvaluationRun target = load(targetRunId);
        EvaluationRunSummary baseSummary = summary(base);
        EvaluationRunSummary targetSummary = summary(target);

        if (!EvaluationMetricsCalculator.isCompleteRun(base)) {
            return incomparable("基线运行不是结果完整的 COMPLETED 运行", base, target, baseSummary, targetSummary,
                    List.of(), List.of(), List.of());
        }
        if (!EvaluationMetricsCalculator.isCompleteRun(target)) {
            return incomparable("对比运行不是结果完整的 COMPLETED 运行", base, target, baseSummary, targetSummary,
                    List.of(), List.of(), List.of());
        }
        if (base.mode() != target.mode()) {
            return incomparable("运行模式不同（" + base.mode() + " / " + target.mode() + "），不能合并比较",
                    base, target, baseSummary, targetSummary, List.of(), List.of(), List.of());
        }
        if (!equalsVersion(base.datasetVersion(), target.datasetVersion())) {
            return incomparable("案例集版本不同（" + base.datasetVersion() + " / " + target.datasetVersion()
                    + "），当前没有跨版本兼容规则", base, target, baseSummary, targetSummary, List.of(), List.of(),
                    List.of());
        }

        Map<String, EvaluationCaseResult> baseCases = byCaseId(base);
        Map<String, EvaluationCaseResult> targetCases = byCaseId(target);
        List<String> missingInTarget = baseCases.keySet().stream().filter(id -> !targetCases.containsKey(id)).toList();
        List<String> extraInTarget = targetCases.keySet().stream().filter(id -> !baseCases.containsKey(id)).toList();
        if (!missingInTarget.isEmpty() || !extraInTarget.isEmpty()) {
            return incomparable("两次运行的案例 ID 集合不一致，无法形成逐案例对比", base, target, baseSummary,
                    targetSummary, missingInTarget, extraInTarget, List.of());
        }

        List<String> assertionSetMismatches = new ArrayList<>();
        List<String> caseVersionMismatches = new ArrayList<>();
        List<EvaluationCaseComparison> cases = new ArrayList<>();
        List<String> newFailures = new ArrayList<>();
        List<String> fixedFailures = new ArrayList<>();
        List<String> persistentFailures = new ArrayList<>();

        for (Map.Entry<String, EvaluationCaseResult> entry : baseCases.entrySet()) {
            EvaluationCaseResult baseCase = entry.getValue();
            EvaluationCaseResult targetCase = targetCases.get(entry.getKey());
            boolean assertionCompatible = assertionIds(baseCase).equals(assertionIds(targetCase));
            boolean versionCompatible = equalsVersion(baseCase.caseVersion(), targetCase.caseVersion());
            if (!assertionCompatible) {
                assertionSetMismatches.add(baseCase.caseId());
            }
            if (!versionCompatible) {
                caseVersionMismatches.add(baseCase.caseId() + " (" + displayVersion(baseCase.caseVersion()) + " / "
                        + displayVersion(targetCase.caseVersion()) + ")");
            }
            String change = caseChange(baseCase.status(), targetCase.status());
            if ("NEW_FAILURE".equals(change)) {
                newFailures.add(baseCase.caseId());
            }
            else if ("FIXED_FAILURE".equals(change)) {
                fixedFailures.add(baseCase.caseId());
            }
            else if ("PERSISTENT_FAILURE".equals(change)) {
                persistentFailures.add(baseCase.caseId());
            }
            List<EvaluationAssertionChange> assertionChanges = assertionCompatible
                    ? assertionChanges(baseCase, targetCase) : List.of();
            String note = null;
            if (!assertionCompatible) {
                note = "断言集合不一致，不能把断言差异解释为能力变化";
            }
            else if (!versionCompatible) {
                note = "案例版本不一致，状态差异仅作参考";
            }
            cases.add(new EvaluationCaseComparison(baseCase.caseId(), baseCase.name(), baseCase.caseVersion(),
                    targetCase.caseVersion(), baseCase.status(), targetCase.status(),
                    assertionCompatible && versionCompatible ? change : "INCOMPARABLE", assertionCompatible,
                    assertionChanges, note));
        }

        String versionNote = caseVersionMismatches.isEmpty() ? null
                : "以下案例版本不一致: " + String.join(", ", caseVersionMismatches);
        boolean comparable = assertionSetMismatches.isEmpty() && caseVersionMismatches.isEmpty();
        String reason = comparable ? null : "断言集合或案例版本不一致，请先确认测试集兼容性";
        // An incomparable run must not expose "new/fixed failure" lists that look like a valid diff.
        return new EvaluationRunComparison(comparable, reason, base.mode(), base.datasetVersion(), versionNote,
                baseSummary, targetSummary, delta(baseSummary, targetSummary), cases,
                comparable ? newFailures : List.of(), comparable ? fixedFailures : List.of(),
                comparable ? persistentFailures : List.of(), missingInTarget, extraInTarget,
                assertionSetMismatches);
    }

    private EvaluationRunComparison incomparable(String reason, EvaluationRun base, EvaluationRun target,
            EvaluationRunSummary baseSummary, EvaluationRunSummary targetSummary, List<String> missingInTarget,
            List<String> extraInTarget, List<String> assertionSetMismatches) {
        return new EvaluationRunComparison(false, reason, base.mode() == target.mode() ? base.mode() : null,
                equalsVersion(base.datasetVersion(), target.datasetVersion()) ? base.datasetVersion() : null, null,
                baseSummary, targetSummary, delta(baseSummary, targetSummary), List.of(), List.of(), List.of(),
                List.of(), missingInTarget, extraInTarget, assertionSetMismatches);
    }

    private EvaluationRun load(String runId) {
        return repository.find(runId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "评测运行不存在: " + runId));
    }

    private static Map<String, EvaluationCaseResult> byCaseId(EvaluationRun run) {
        Map<String, EvaluationCaseResult> map = new LinkedHashMap<>();
        run.results().forEach(result -> map.put(result.caseId(), result));
        return map;
    }

    private static Set<String> assertionIds(EvaluationCaseResult result) {
        return new LinkedHashSet<>(result.assertions().stream().map(EvaluationAssertionResult::id).toList());
    }

    private static String caseChange(EvaluationCaseStatus base, EvaluationCaseStatus target) {
        if (base == EvaluationCaseStatus.PASSED && target == EvaluationCaseStatus.PASSED) {
            return "UNCHANGED_PASS";
        }
        if (base != EvaluationCaseStatus.FAILED && target == EvaluationCaseStatus.FAILED) {
            return "NEW_FAILURE";
        }
        if (base == EvaluationCaseStatus.FAILED && target == EvaluationCaseStatus.PASSED) {
            return "FIXED_FAILURE";
        }
        if (base == EvaluationCaseStatus.FAILED && target == EvaluationCaseStatus.FAILED) {
            return "PERSISTENT_FAILURE";
        }
        if (base == EvaluationCaseStatus.UNDETERMINED && target == EvaluationCaseStatus.UNDETERMINED) {
            return "UNCHANGED_UNDETERMINED";
        }
        return "OTHER";
    }

    private static List<EvaluationAssertionChange> assertionChanges(EvaluationCaseResult base,
            EvaluationCaseResult target) {
        Map<String, EvaluationAssertionResult> targetById = new LinkedHashMap<>();
        target.assertions().forEach(assertion -> targetById.put(assertion.id(), assertion));
        List<EvaluationAssertionChange> changes = new ArrayList<>();
        for (EvaluationAssertionResult baseAssertion : base.assertions()) {
            EvaluationAssertionResult targetAssertion = targetById.get(baseAssertion.id());
            if (targetAssertion == null) {
                continue;
            }
            boolean valueChanged = !equalsValue(baseAssertion.expected(), targetAssertion.expected())
                    || !equalsValue(baseAssertion.actual(), targetAssertion.actual());
            if (baseAssertion.passed() == targetAssertion.passed() && !valueChanged) {
                continue;
            }
            String change = "VALUE_CHANGED";
            if (!baseAssertion.passed() && targetAssertion.passed()) {
                change = "IMPROVED";
            }
            else if (baseAssertion.passed() && !targetAssertion.passed()) {
                change = "REGRESSED";
            }
            changes.add(new EvaluationAssertionChange(baseAssertion.id(), baseAssertion.description(),
                    baseAssertion.passed(), targetAssertion.passed(), baseAssertion.expected(),
                    targetAssertion.expected(), baseAssertion.actual(), targetAssertion.actual(), change));
        }
        return changes;
    }

    private static EvaluationRunSummary summary(EvaluationRun run) {
        int total = run.results().size();
        int passed = count(run, EvaluationCaseStatus.PASSED);
        int failed = count(run, EvaluationCaseStatus.FAILED);
        int undetermined = count(run, EvaluationCaseStatus.UNDETERMINED);
        List<Long> durations = run.results().stream()
            .map(EvaluationCaseResult::durationMs)
            .filter(duration -> duration >= 0)
            .sorted()
            .toList();
        Double average = durations.isEmpty() ? null
                : round(durations.stream().mapToLong(Long::longValue).average().orElse(0));
        Double p95 = durations.size() < P95_MIN_SAMPLES ? null : (double) durations.get(p95Index(durations.size()));
        return new EvaluationRunSummary(run.runId(), run.mode(), run.datasetVersion(), run.status(), run.startedAt(),
                run.completedAt(), total, passed, failed, undetermined, rate(passed, total), run.durationMs(),
                average, p95, durations.size());
    }

    private static int count(EvaluationRun run, EvaluationCaseStatus status) {
        return (int) run.results().stream().filter(result -> result.status() == status).count();
    }

    private static EvaluationComparisonDelta delta(EvaluationRunSummary base, EvaluationRunSummary target) {
        return new EvaluationComparisonDelta(target.passed() - base.passed(), target.failed() - base.failed(),
                target.undetermined() - base.undetermined(), round(target.passRate() - base.passRate()),
                subtract(target.runDurationMs(), base.runDurationMs()),
                subtract(target.averageCaseDurationMs(), base.averageCaseDurationMs()),
                subtract(target.p95CaseDurationMs(), base.p95CaseDurationMs()),
                target.p95SampleSize() - base.p95SampleSize());
    }

    private static Long subtract(Long target, Long base) {
        return target == null || base == null ? null : target - base;
    }

    private static Double subtract(Double target, Double base) {
        return target == null || base == null ? null : round(target - base);
    }

    private static boolean equalsVersion(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static String displayVersion(String version) {
        return version == null || version.isBlank() ? "旧记录无版本" : version;
    }

    private static boolean equalsValue(String left, String right) {
        return left == null ? right == null : left.equals(right);
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

}
