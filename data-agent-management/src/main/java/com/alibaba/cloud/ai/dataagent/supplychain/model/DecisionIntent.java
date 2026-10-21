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

import java.util.List;

/**
 * Parsed parameters for one decision request. {@code clarification} is populated when the
 * request is actionable but incomplete or ambiguous; no business default is applied silently.
 */
public record DecisionIntent(DecisionIntentType type, String sku, String supplierId,
        Integer replenishmentQuantity, List<Integer> replenishmentQuantities, Integer additionalDelayDays,
        List<Integer> additionalDelayDaysOptions, String comparisonId, String candidateId, String planId,
        String recommendationId, String executionId, String title, String clarification) {

    public DecisionIntent {
        replenishmentQuantities = replenishmentQuantities == null ? List.of()
                : List.copyOf(replenishmentQuantities);
        additionalDelayDaysOptions = additionalDelayDaysOptions == null ? List.of()
                : List.copyOf(additionalDelayDaysOptions);
    }

    public boolean needsClarification() {
        return clarification != null && !clarification.isBlank();
    }

    public DecisionIntent withClarification(String message) {
        return new DecisionIntent(type, sku, supplierId, replenishmentQuantity, replenishmentQuantities,
                additionalDelayDays, additionalDelayDaysOptions, comparisonId, candidateId, planId,
                recommendationId, executionId, title, message);
    }
}
