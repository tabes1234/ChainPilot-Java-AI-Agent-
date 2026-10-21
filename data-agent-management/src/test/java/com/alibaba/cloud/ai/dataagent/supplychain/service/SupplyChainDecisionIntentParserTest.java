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
package com.alibaba.cloud.ai.dataagent.supplychain.service;

import com.alibaba.cloud.ai.dataagent.supplychain.model.DecisionIntent;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DecisionIntentType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupplyChainDecisionIntentParserTest {

    private final SupplyChainDecisionIntentParser parser = new SupplyChainDecisionIntentParser();

    @Test
    void parsesReplenishmentComparisonWithExactCandidates() {
        DecisionIntent intent = parser.parse("比较 SKU-MCU-001 补货 50、100、200 件的方案");

        assertEquals(DecisionIntentType.COMPARE_REPLENISHMENT, intent.type());
        assertEquals("SKU-MCU-001", intent.sku());
        assertEquals(List.of(50, 100, 200), intent.replenishmentQuantities());
        assertFalse(intent.needsClarification());
    }

    @Test
    void parsesSupplierDelayComparisonWithExactCandidates() {
        DecisionIntent intent = parser.parse("比较供应商 SUP-ALPHA 延期 3、5、7 天的方案");

        assertEquals(DecisionIntentType.COMPARE_SUPPLIER_DELAY, intent.type());
        assertEquals("SUP-ALPHA", intent.supplierId());
        assertEquals(List.of(3, 5, 7), intent.additionalDelayDaysOptions());
    }

    @Test
    void comparisonWithoutSkuAsksForItInsteadOfApplyingADefault() {
        DecisionIntent intent = parser.parse("比较补货 50、100、200 件的方案");

        assertEquals(DecisionIntentType.UNSUPPORTED, intent.type());
        assertTrue(intent.needsClarification());
        assertTrue(intent.clarification().contains("SKU"));
        assertNull(intent.sku());
    }

    @Test
    void singleCandidateComparisonIsRejectedInsteadOfSilentlySimulated() {
        DecisionIntent intent = parser.parse("比较 SKU-MCU-001 补货 100 件");

        assertEquals(DecisionIntentType.UNSUPPORTED, intent.type());
        assertTrue(intent.clarification().contains("至少需要 2 个"));
    }

    @Test
    void duplicateOrTooManyCandidatesAreRejected() {
        assertTrue(parser.parse("比较 SKU-MCU-001 补货 50、50 件").clarification().contains("互不相同"));
        assertTrue(parser.parse("比较 SKU-MCU-001 补货 10、20、30、40、50、60 件").clarification()
            .contains("最多支持 5 个"));
    }

    @Test
    void parsesSingleReplenishmentAndDelaySimulations() {
        DecisionIntent replenishment = parser.parse("模拟给 SKU-MCU-001 补货 100 件");
        assertEquals(DecisionIntentType.SIMULATE_REPLENISHMENT, replenishment.type());
        assertEquals(Integer.valueOf(100), replenishment.replenishmentQuantity());

        DecisionIntent delay = parser.parse("如果供应商 SUP-ALPHA 延期 5 天会怎样");
        assertEquals(DecisionIntentType.SIMULATE_SUPPLIER_DELAY, delay.type());
        assertEquals(Integer.valueOf(5), delay.additionalDelayDays());
    }

    @Test
    void mixedReplenishmentAndDelayNumbersAreRejectedAsAmbiguous() {
        DecisionIntent intent = parser.parse("比较 SKU-MCU-001 补货 100 件并延期 5 天");

        assertTrue(intent.needsClarification());
        assertTrue(intent.clarification().contains("分开描述"));
    }

    @Test
    void recommendationWithoutScopeNeedsClarificationUnlessGlobalScopeIsExplicit() {
        DecisionIntent scoped = parser.parse("给我处置建议");
        assertTrue(scoped.needsClarification());
        assertTrue(scoped.clarification().contains("SKU"));

        DecisionIntent global = parser.parse("查看当前全部处置建议");
        assertEquals(DecisionIntentType.DISPOSAL_RECOMMENDATIONS, global.type());
        assertFalse(global.needsClarification());
    }

    @Test
    void proposalIntentsRequireExplicitIdentifiers() {
        DecisionIntent save = parser.parse("把候选保存为方案");
        assertEquals(DecisionIntentType.PROPOSE_SAVE_PLAN, save.type());
        assertTrue(save.clarification().contains("comparisonId"));

        DecisionIntent sandbox = parser.parse("启动沙箱演练");
        assertEquals(DecisionIntentType.PROPOSE_START_SANDBOX, sandbox.type());
        assertTrue(sandbox.clarification().contains("planId"));

        DecisionIntent eligibility = parser.parse("检查方案 12345 的沙箱执行条件");
        assertEquals(DecisionIntentType.LOOKUP_PLAN, eligibility.type());
        assertTrue(eligibility.needsClarification());
    }

    @Test
    void eligibilityQuestionWithExplicitPlanLooksUpInsteadOfProposingExecution() {
        String planId = "11111111-1111-1111-1111-111111111111";
        DecisionIntent intent = parser.parse("检查方案 " + planId + " 能否启动沙箱演练");

        assertEquals(DecisionIntentType.LOOKUP_PLAN, intent.type());
        assertEquals(planId, intent.planId());
        assertFalse(intent.needsClarification());
    }

    @Test
    void explicitUuidNearLabelIsClassifiedByLabel() {
        String comparisonId = "22222222-2222-2222-2222-222222222222";
        DecisionIntent intent = parser.parse("查看对比 " + comparisonId + " 的排名");

        assertEquals(DecisionIntentType.LOOKUP_COMPARISON, intent.type());
        assertEquals(comparisonId, intent.comparisonId());
    }

    @Test
    void emptyQuestionIsClarified() {
        DecisionIntent intent = parser.parse("   ");

        assertTrue(intent.needsClarification());
        assertTrue(intent.clarification().contains("请输入"));
    }
}
