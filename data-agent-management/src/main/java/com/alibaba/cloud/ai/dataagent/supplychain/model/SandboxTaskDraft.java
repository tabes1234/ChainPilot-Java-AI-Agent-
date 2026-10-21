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

/**
 * A sandbox task draft produced by the rehearsal. It is audit material only: no purchase
 * order, warehouse task or supplier notification is created from it.
 */
public record SandboxTaskDraft(String taskDraftId, SandboxDraftType draftType, String title, String sku,
        String supplierId, Integer replenishmentQuantity, Integer currentOnHandQuantity,
        Integer currentAvailableToPromise, Integer targetOnHandQuantity, Integer targetAvailableToPromise,
        List<String> linkedOrderIds, List<String> recommendedActions, List<String> pendingConfirmations,
        String sourceSimulationId, String evidenceSummary, Instant createdAt) {

    public SandboxTaskDraft {
        linkedOrderIds = List.copyOf(linkedOrderIds);
        recommendedActions = List.copyOf(recommendedActions);
        pendingConfirmations = List.copyOf(pendingConfirmations);
    }
}
