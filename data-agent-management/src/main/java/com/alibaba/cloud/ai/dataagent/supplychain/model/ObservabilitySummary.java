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
import java.util.List;
import java.util.Map;

/** Read-only aggregate over a bounded window of analysis and decision tasks. */
public record ObservabilitySummary(Instant generatedAt, String scope, int windowSize, int totalTasks,
        int completedTasks, int failedTasks, int runningTasks, int cancelledTasks, int degradedTasks, int toolCalls,
        int toolFailures, int modelCompletions, int modelFailures, int fallbackEvents,
        int modelVerifiedTasks, int legacyUnverifiedTasks, int deterministicFallbackTasks,
        int tasksWithDuration, Double averageDurationMs, Map<String, Integer> failureTypes,
        Map<String, Integer> narrativeSources, List<String> limitations) {

    public ObservabilitySummary {
        failureTypes = failureTypes == null ? Map.of() : Map.copyOf(failureTypes);
        narrativeSources = narrativeSources == null ? Map.of() : Map.copyOf(narrativeSources);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
    }
}
