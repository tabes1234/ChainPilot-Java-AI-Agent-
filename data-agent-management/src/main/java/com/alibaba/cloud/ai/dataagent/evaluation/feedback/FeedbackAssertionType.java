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

/** Supported executable assertions for feedback-derived offline cases. */
public enum FeedbackAssertionType {
    TASK_STATUS,
    NARRATIVE_SOURCE,
    AT_RISK_ORDER_COUNT,
    HIGH_RISK_ORDER_COUNT,
    DATA_GAP_COUNT,
    RISK_SCORE,
    RISK_LEVEL,
    EVIDENCE_CODE,
    DECISION_INTENT
}
