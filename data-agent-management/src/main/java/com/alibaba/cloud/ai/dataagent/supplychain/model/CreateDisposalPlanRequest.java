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

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Creates a plan from current (or task-snapshotted) risk evidence. Exactly one of
 * {@code simulationId} or {@code simulation} may be supplied.
 */
public record CreateDisposalPlanRequest(@NotBlank @Size(max = 200) String title,
        @Size(max = 36) String taskId, @Size(max = 64) String sku, @Size(max = 64) String supplierId,
        @Size(max = 36) String simulationId, @Valid SimulationRequest simulation, Boolean generateExplanation) {
}
