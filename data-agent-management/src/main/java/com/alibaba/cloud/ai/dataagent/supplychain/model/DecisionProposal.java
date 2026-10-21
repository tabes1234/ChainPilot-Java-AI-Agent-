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

/**
 * A structured, non-executed action proposal. The content hash binds the exact action,
 * target and parameters; a confirmation for different content is rejected.
 */
public record DecisionProposal(String proposalId, String taskId, ProposalActionType actionType,
        String title, String targetId, Map<String, Object> parameters, List<String> evidence,
        String contentHash, ProposalStatus status, Instant createdAt, Instant expiresAt,
        Instant confirmedAt, Map<String, Object> result, String errorMessage) {

    public DecisionProposal {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        result = result == null ? null : Map.copyOf(result);
    }
}
