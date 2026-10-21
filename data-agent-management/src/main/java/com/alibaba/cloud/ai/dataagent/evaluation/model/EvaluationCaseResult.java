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
 * Result of one case inside a run. The case version is the exact definition used for
 * this execution; taskId/taskMode only exist for real workflow cases.
 */
public record EvaluationCaseResult(String caseId, String name, String scenario, String category,
        EvaluationCaseStatus status, long durationMs, String taskId, List<EvaluationAssertionResult> assertions,
        String observationJson, String failureType, String failureMessage, String caseVersion, String taskMode) {

    public EvaluationCaseResult {
        assertions = assertions == null ? List.of() : List.copyOf(assertions);
    }

    /** Keeps older construction sites explicit about not knowing the version/task mode. */
    public EvaluationCaseResult(String caseId, String name, String scenario, String category,
            EvaluationCaseStatus status, long durationMs, String taskId, List<EvaluationAssertionResult> assertions,
            String observationJson, String failureType, String failureMessage) {
        this(caseId, name, scenario, category, status, durationMs, taskId, assertions, observationJson, failureType,
                failureMessage, null, null);
    }

}
