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
package com.alibaba.cloud.ai.dataagent.evaluation.feedback;

import java.util.List;

/**
 * Safe snapshot of the result shown when feedback was submitted. It is evidence for a draft,
 * not the user's expected answer; suggestions are kept separate from accepted assertions.
 */
public record FeedbackSnapshot(String taskId, String resultType, String question, String summary,
        String narrativeSource, int atRiskOrderCount, int highRiskOrderCount, int dataGapCount,
        List<OrderSnapshot> orders, List<String> dataGaps, List<String> evidenceIds) {

    public FeedbackSnapshot {
        orders = orders == null ? List.of() : List.copyOf(orders);
        dataGaps = dataGaps == null ? List.of() : List.copyOf(dataGaps);
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
    }

    public record OrderSnapshot(String orderId, String sku, int riskScore, String riskLevel,
            int inventoryShortage, List<String> evidenceCodes, List<String> dataGaps) {

        public OrderSnapshot {
            evidenceCodes = evidenceCodes == null ? List.of() : List.copyOf(evidenceCodes);
            dataGaps = dataGaps == null ? List.of() : List.copyOf(dataGaps);
        }
    }
}
