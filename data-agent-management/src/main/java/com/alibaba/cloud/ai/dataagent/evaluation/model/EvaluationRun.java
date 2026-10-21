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
import java.util.List;
import java.util.Map;

/**
 * One persisted evaluation run. datasetVersion is the case-set version for the run's mode
 * (OFFLINE dataset or AGENT workflow catalogue); failureReason explains interrupted or
 * timed-out runs.
 */
public record EvaluationRun(String runId, String datasetVersion, EvaluationMode mode, EvaluationRunStatus status,
        String environment, Instant startedAt, Instant completedAt, int totalCases, int passed, int failed,
        int undetermined, Long durationMs, Map<String, Object> metrics, List<EvaluationCaseResult> results,
        String failureReason) {

    public EvaluationRun {
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
        results = results == null ? List.of() : List.copyOf(results);
    }

    public EvaluationRun(String runId, String datasetVersion, EvaluationMode mode, EvaluationRunStatus status,
            String environment, Instant startedAt, Instant completedAt, int totalCases, int passed, int failed,
            int undetermined, Long durationMs, Map<String, Object> metrics, List<EvaluationCaseResult> results) {
        this(runId, datasetVersion, mode, status, environment, startedAt, completedAt, totalCases, passed, failed,
                undetermined, durationMs, metrics, results, null);
    }

}
