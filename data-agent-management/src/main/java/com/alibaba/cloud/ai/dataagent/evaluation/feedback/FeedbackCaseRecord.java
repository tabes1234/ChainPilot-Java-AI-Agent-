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

import java.util.List;

/** Enabled custom case content used by the existing deterministic evaluation runner. */
public record FeedbackCaseRecord(String caseId, String draftId, String feedbackId, String taskId,
        FeedbackResultType resultType, String name, String question, String expectedBehavior,
        List<FeedbackAssertion> assertions, FeedbackSnapshot snapshot, FeedbackType feedbackType,
        String version, java.time.Instant enabledAt) {

    public FeedbackCaseRecord {
        assertions = assertions == null ? List.of() : List.copyOf(assertions);
    }
}
