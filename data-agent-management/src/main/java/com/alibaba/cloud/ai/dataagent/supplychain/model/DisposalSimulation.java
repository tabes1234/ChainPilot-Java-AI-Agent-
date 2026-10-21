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
import java.time.LocalDate;
import java.util.List;

/**
 * A deterministic what-if result. Baseline metrics describe current demo data; simulated
 * metrics are rule-based expectations and never actual execution results.
 */
public record DisposalSimulation(String simulationId, SimulationScenario scenario, String sku,
        String supplierId, Integer replenishmentQuantity, Integer additionalDelayDays, LocalDate asOfDate,
        String baselineSource, SimulationMetrics baseline, SimulationMetrics simulated, SimulationDelta delta,
        List<OrderRiskImpact> affectedOrders, List<String> assumptions, List<String> limitations,
        List<String> evidence, boolean realDataMutated, String disclaimer, Instant createdAt,
        String ruleVersion, String simulationStatus, List<String> dataGaps) {

    public static final String RULE_VERSION = "RISK_RULES_V1";

    public static final String SIMULATION_STATUS = "SIMULATED";

    public DisposalSimulation {
        affectedOrders = affectedOrders == null ? List.of() : List.copyOf(affectedOrders);
        assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        ruleVersion = ruleVersion == null ? RULE_VERSION : ruleVersion;
        simulationStatus = simulationStatus == null ? SIMULATION_STATUS : simulationStatus;
        dataGaps = dataGaps == null ? List.of() : List.copyOf(dataGaps);
    }

    /** Keeps older persisted simulation JSON and existing callers readable. */
    public DisposalSimulation(String simulationId, SimulationScenario scenario, String sku, String supplierId,
            Integer replenishmentQuantity, Integer additionalDelayDays, LocalDate asOfDate, String baselineSource,
            SimulationMetrics baseline, SimulationMetrics simulated, SimulationDelta delta,
            List<OrderRiskImpact> affectedOrders, List<String> assumptions, List<String> limitations,
            List<String> evidence, boolean realDataMutated, String disclaimer, Instant createdAt) {
        this(simulationId, scenario, sku, supplierId, replenishmentQuantity, additionalDelayDays, asOfDate,
                baselineSource, baseline, simulated, delta, affectedOrders, assumptions, limitations, evidence,
                realDataMutated, disclaimer, createdAt, RULE_VERSION, SIMULATION_STATUS, List.of());
    }
}
