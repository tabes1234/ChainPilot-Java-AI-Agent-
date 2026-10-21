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

/** Deterministic intent recognized for a natural-language supply-chain request. */
public enum DecisionIntentType {

    ANALYSIS,

    DISPOSAL_RECOMMENDATIONS,

    COMPARE_REPLENISHMENT,

    COMPARE_SUPPLIER_DELAY,

    SIMULATE_REPLENISHMENT,

    SIMULATE_SUPPLIER_DELAY,

    LOOKUP_COMPARISON,

    LOOKUP_PLAN,

    LOOKUP_EXECUTION,

    PROPOSE_SAVE_PLAN,

    PROPOSE_LINK_PLAN,

    PROPOSE_SELECT_PLAN,

    PROPOSE_START_SANDBOX,

    UNSUPPORTED

}
