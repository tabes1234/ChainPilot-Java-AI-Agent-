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
package com.alibaba.cloud.ai.dataagent.supplychain.service;

import com.alibaba.cloud.ai.dataagent.supplychain.model.OrderRiskAssessment;
import com.alibaba.cloud.ai.dataagent.supplychain.model.OrderStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskEvidence;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskLevel;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainOrder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskNarrativeValidatorTest {

    private final RiskNarrativeValidator validator = new RiskNarrativeValidator(new ObjectMapper());

    @Test
    void acceptsEvidenceBackedNarrativeAndExactRecommendation() {
        var narrative = validator.validate(validJson(), "task-1", List.of(assessment(List.of())), List.of(ACTION));

        assertEquals("订单存在延期风险。", narrative.overview());
        assertEquals("DELAY", narrative.reasons().get(0).evidenceIds().get(0));
        assertEquals(ACTION, narrative.recommendations().get(0));
    }

    @Test
    void rejectsUnknownEvidenceAndUnknownRecommendation() {
        var invalidEvidence = validJson().replace("\"DELAY\"", "\"MISSING\"");
        var exception = assertThrows(RiskNarrativeValidator.InvalidNarrativeException.class,
                () -> validator.validate(invalidEvidence, "task-1", List.of(assessment(List.of())), List.of(ACTION)));
        assertEquals("UNKNOWN_EVIDENCE", exception.reason());

        var invalidRecommendation = validJson().replace(ACTION, "执行一个未定义的真实采购操作");
        exception = assertThrows(RiskNarrativeValidator.InvalidNarrativeException.class,
                () -> validator.validate(invalidRecommendation, "task-1", List.of(assessment(List.of())),
                        List.of(ACTION)));
        assertEquals("UNKNOWN_RECOMMENDATION", exception.reason());
    }

    @Test
    void rejectsInventedNumbersAndExecutionClaims() {
        var inventedNumber = validJson().replace("订单存在延期风险。", "订单存在 999 件缺口。");
        var exception = assertThrows(RiskNarrativeValidator.InvalidNarrativeException.class,
                () -> validator.validate(inventedNumber, "task-1", List.of(assessment(List.of())), List.of(ACTION)));
        assertEquals("NUMERIC_CLAIM_NOT_IN_EVIDENCE", exception.reason());

        var executionClaim = validJson().replace("订单存在延期风险。", "订单已执行，真实数据已变更。");
        exception = assertThrows(RiskNarrativeValidator.InvalidNarrativeException.class,
                () -> validator.validate(executionClaim, "task-1", List.of(assessment(List.of())), List.of(ACTION)));
        assertEquals("EXECUTION_CLAIM", exception.reason());
    }

    @Test
    void requiresEveryDataGapToRemainExplicit() {
        String gap = "库存快照缺失：未找到 SKU SKU-1 的库存记录";
        var exception = assertThrows(RiskNarrativeValidator.InvalidNarrativeException.class,
                () -> validator.validate(validJson(), "task-1", List.of(assessment(List.of(gap))), List.of(ACTION)));
        assertEquals("MISSING_DATA_GAP", exception.reason());

        String withGap = validJson().replace("\"uncertainties\":[]", "\"uncertainties\":[\"" + gap + "\"]");
        var narrative = validator.validate(withGap, "task-1", List.of(assessment(List.of(gap))), List.of(ACTION));
        assertEquals(List.of(gap), narrative.uncertainties());
    }

    @Test
    void rejectsMalformedJsonAndMissingReasons() {
        assertThrows(RiskNarrativeValidator.InvalidNarrativeException.class,
                () -> validator.validate("not-json", "task-1", List.of(), List.of()));
        var noReasons = validJson().replace("\"reasons\":[{\"orderId\":\"ORD-TEST\",\"summary\":\"订单存在延期风险。\",\"evidenceIds\":[\"DELAY\"]}]",
                "\"reasons\":[]");
        var exception = assertThrows(RiskNarrativeValidator.InvalidNarrativeException.class,
                () -> validator.validate(noReasons, "task-1", List.of(assessment(List.of())), List.of(ACTION)));
        assertEquals("MISSING_REASONS", exception.reason());
        assertTrue(exception.reason().contains("MISSING"));
    }

    private static final String ACTION = "Confirm a recovery date with the supplier and notify the customer";

    private static String validJson() {
        return """
                {"overview":"订单存在延期风险。","reasons":[{"orderId":"ORD-TEST","summary":"订单存在延期风险。","evidenceIds":["DELAY"]}],"businessImpacts":[],"recommendations":["Confirm a recovery date with the supplier and notify the customer"],"uncertainties":[]}
                """;
    }

    private static OrderRiskAssessment assessment(List<String> gaps) {
        SupplyChainOrder order = new SupplyChainOrder("ORD-TEST", "SKU-TEST", 10, "SUP-TEST", LocalDate.now(),
                LocalDate.now().plusDays(2), OrderStatus.IN_TRANSIT);
        RiskEvidence evidence = new RiskEvidence("DELAY", "交付延期", "lateDays=2", "0", "延期计 30 分", 30,
                "order dates", true);
        return new OrderRiskAssessment(order, RiskLevel.MEDIUM, 30, 0, List.of("延期"), List.of(ACTION),
                List.of(evidence), gaps);
    }
}
