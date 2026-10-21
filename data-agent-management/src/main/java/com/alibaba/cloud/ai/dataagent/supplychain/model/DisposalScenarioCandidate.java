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

/**
 * One simulated candidate inside a comparison. The embedded simulation uses the comparison's
 * shared baseline and may be saved as a regular disposal plan later.
 */
public record DisposalScenarioCandidate(String candidateId, String label, int position,
        SimulationScenario scenario, String sku, String supplierId, Integer replenishmentQuantity,
        Integer additionalDelayDays, DisposalSimulation simulation, String linkedPlanId, Instant createdAt) {

    public DisposalScenarioCandidate withLinkedPlanId(String planId) {
        return new DisposalScenarioCandidate(candidateId, label, position, scenario, sku, supplierId,
                replenishmentQuantity, additionalDelayDays, simulation, planId, createdAt);
    }
}
