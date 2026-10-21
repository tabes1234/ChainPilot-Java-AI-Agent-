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
package com.alibaba.cloud.ai.dataagent.supplychain.model;

/** Event types emitted only from execution paths that actually ran. */
public final class SupplyChainEventType {

    public static final String TASK_QUEUED = "TASK_QUEUED";

    public static final String TASK_STARTED = "TASK_STARTED";

    public static final String TASK_COMPLETED = "TASK_COMPLETED";

    public static final String TASK_FAILED = "TASK_FAILED";

    public static final String NODE_STARTED = "NODE_STARTED";

    public static final String NODE_COMPLETED = "NODE_COMPLETED";

    public static final String NODE_FAILED = "NODE_FAILED";

    public static final String TOOL_STARTED = "TOOL_STARTED";

    public static final String TOOL_COMPLETED = "TOOL_COMPLETED";

    public static final String TOOL_FAILED = "TOOL_FAILED";

    public static final String MODEL_STARTED = "MODEL_STARTED";

    public static final String MODEL_COMPLETED = "MODEL_COMPLETED";

    public static final String MODEL_FAILED = "MODEL_FAILED";

    public static final String FALLBACK_USED = "FALLBACK_USED";

    public static final String INTENT_RECOGNIZED = "INTENT_RECOGNIZED";

    public static final String CLARIFICATION_REQUIRED = "CLARIFICATION_REQUIRED";

    public static final String PROPOSAL_CREATED = "PROPOSAL_CREATED";

    public static final String PROPOSAL_CONFIRMED = "PROPOSAL_CONFIRMED";

    public static final String PROPOSAL_CANCELLED = "PROPOSAL_CANCELLED";

    public static final String PROPOSAL_FAILED = "PROPOSAL_FAILED";

    private SupplyChainEventType() {
    }
}
