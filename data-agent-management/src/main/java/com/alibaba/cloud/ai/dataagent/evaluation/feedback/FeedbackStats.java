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
package com.alibaba.cloud.ai.dataagent.evaluation.feedback;

import java.util.Map;

/** Counts from persisted feedback and draft rows only. */
public record FeedbackStats(int total, int drafts, int readyForReview, int enabledCases,
        Map<String, Integer> byType, Map<String, Integer> bySource, Map<String, Integer> byResultType) {

    public FeedbackStats {
        byType = byType == null ? Map.of() : Map.copyOf(byType);
        bySource = bySource == null ? Map.of() : Map.copyOf(bySource);
        byResultType = byResultType == null ? Map.of() : Map.copyOf(byResultType);
    }
}
