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
 * Comparison computed only from two persisted completed runs. comparable=false means the
 * differences must not be interpreted as a like-for-like test-set comparison.
 */
public record EvaluationRunComparison(boolean comparable, String reason, EvaluationMode mode, String caseSetVersion,
        String versionNote, EvaluationRunSummary baseRun, EvaluationRunSummary targetRun,
        EvaluationComparisonDelta delta, List<EvaluationCaseComparison> cases, List<String> newFailures,
        List<String> fixedFailures, List<String> persistentFailures, List<String> missingInTarget,
        List<String> extraInTarget, List<String> assertionSetMismatches) {

    public EvaluationRunComparison {
        cases = cases == null ? List.of() : List.copyOf(cases);
        newFailures = newFailures == null ? List.of() : List.copyOf(newFailures);
        fixedFailures = fixedFailures == null ? List.of() : List.copyOf(fixedFailures);
        persistentFailures = persistentFailures == null ? List.of() : List.copyOf(persistentFailures);
        missingInTarget = missingInTarget == null ? List.of() : List.copyOf(missingInTarget);
        extraInTarget = extraInTarget == null ? List.of() : List.copyOf(extraInTarget);
        assertionSetMismatches = assertionSetMismatches == null ? List.of() : List.copyOf(assertionSetMismatches);
    }

}
