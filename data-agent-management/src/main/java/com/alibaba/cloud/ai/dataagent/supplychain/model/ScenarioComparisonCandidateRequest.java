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
import jakarta.validation.constraints.Size;

/**
 * One candidate parameter set. Which numeric field is required depends on the comparison
 * scenario and is enforced by the comparison service.
 */
public record ScenarioComparisonCandidateRequest(@Size(max = 100) String label,
        @Min(1) @Max(1_000_000) Integer replenishmentQuantity,
        @Min(1) @Max(90) Integer additionalDelayDays) {
}
