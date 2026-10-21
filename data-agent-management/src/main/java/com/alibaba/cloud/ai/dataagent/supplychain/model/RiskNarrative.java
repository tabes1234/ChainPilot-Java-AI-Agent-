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

import java.util.List;

/**
 * Structured model explanation. The model may only describe the evidence supplied by the
 * deterministic analysis and may not introduce business measurements or actions.
 */
public record RiskNarrative(String overview, List<Reason> reasons, List<String> businessImpacts,
        List<String> recommendations, List<String> uncertainties) {

    public RiskNarrative {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        businessImpacts = businessImpacts == null ? List.of() : List.copyOf(businessImpacts);
        recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
        uncertainties = uncertainties == null ? List.of() : List.copyOf(uncertainties);
    }

    public record Reason(String orderId, String summary, List<String> evidenceIds) {

        public Reason {
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        }
    }
}
