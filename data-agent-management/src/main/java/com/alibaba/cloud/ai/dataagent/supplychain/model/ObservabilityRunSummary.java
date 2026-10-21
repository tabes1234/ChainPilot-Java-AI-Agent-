/*
 * Copyright 2026 the original author or authors.
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
package com.alibaba.cloud.ai.dataagent.supplychain.model;

import java.time.Instant;

/** Safe summary of one observable task; no report body, prompt or credential is exposed. */
public record ObservabilityRunSummary(String taskId, String question, String mode, String status,
        String currentStep, Instant updatedAt, String narrativeSource, boolean modelAssisted,
        boolean fallbackUsed, boolean degraded, int eventCount, int toolCallCount,
        int toolFailureCount, int modelFailureCount, Long durationMs, String failureType,
        boolean reportAvailable, boolean decisionAvailable) {
}
