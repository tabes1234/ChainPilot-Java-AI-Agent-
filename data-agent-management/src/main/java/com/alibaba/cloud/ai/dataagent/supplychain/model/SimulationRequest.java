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

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Input for a deterministic what-if calculation. Scenario-specific fields are validated by
 * the simulation service so missing and irrelevant parameters produce explicit errors.
 */
public record SimulationRequest(@NotNull SimulationScenario scenario,
        @Size(max = 64) String sku,
        @Min(1) @Max(1_000_000) Integer replenishmentQuantity,
        @Size(max = 64) String supplierId,
        @Min(1) @Max(90) Integer additionalDelayDays) {
}
