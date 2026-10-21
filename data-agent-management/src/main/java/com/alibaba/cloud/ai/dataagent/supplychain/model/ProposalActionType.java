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

/**
 * Write-like operations that a natural-language request may propose. Proposals never execute
 * on behalf of the model; they require an explicit user confirmation against their content
 * hash.
 */
public enum ProposalActionType {

    SAVE_CANDIDATE_AS_PLAN,

    LINK_CANDIDATE_TO_PLAN,

    SELECT_PLAN,

    START_SANDBOX_EXECUTION

}
