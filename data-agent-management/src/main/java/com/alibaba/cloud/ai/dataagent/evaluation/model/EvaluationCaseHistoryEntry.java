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

import java.time.Instant;

/** One persisted appearance of a case in a completed evaluation run. */
public record EvaluationCaseHistoryEntry(String runId, EvaluationMode mode, EvaluationRunStatus runStatus,
        Instant startedAt, EvaluationCaseStatus caseStatus, long durationMs, String taskId, String failureType,
        String failureMessage, String caseVersion, String datasetVersion) {

    public EvaluationCaseHistoryEntry(String runId, EvaluationMode mode, EvaluationRunStatus runStatus,
            Instant startedAt, EvaluationCaseStatus caseStatus, long durationMs, String taskId, String failureType,
            String failureMessage) {
        this(runId, mode, runStatus, startedAt, caseStatus, durationMs, taskId, failureType, failureMessage, null, null);
    }

}
