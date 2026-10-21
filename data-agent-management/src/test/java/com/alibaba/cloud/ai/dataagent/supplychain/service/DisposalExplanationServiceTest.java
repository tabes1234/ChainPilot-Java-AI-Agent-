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

import com.alibaba.cloud.ai.dataagent.service.aimodelconfig.AiModelRegistry;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalActionType;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalExplanation;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalPriority;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalRecommendation;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskLevel;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DisposalExplanationServiceTest {

    private AiModelRegistry modelRegistry;

    private ChatClient.ChatClientRequestSpec requestSpec;

    private ChatClient.CallResponseSpec callResponseSpec;

    private DisposalExplanationService service;

    @BeforeEach
    void setUp() {
        modelRegistry = mock(AiModelRegistry.class);
        ChatClient chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        callResponseSpec = mock(ChatClient.CallResponseSpec.class);
        when(modelRegistry.getChatClient()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.toolCallbacks(any(ToolCallback[].class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);

        SupplyChainAgentTools tools = new SupplyChainAgentTools(mock(SupplyChainQueryService.class),
                mock(DisposalRecommendationService.class), mock(DisposalSimulationService.class),
                mock(DisposalExecutionService.class), mock(DisposalScenarioComparisonService.class),
                mock(DisposalPlanService.class), mock(SupplyChainEventRecorder.class));
        service = new DisposalExplanationService(modelRegistry, tools, ObservationRegistry.NOOP);
    }

    @Test
    void modelNarrativeReceivesOnlyDeterministicRecommendationValues() {
        when(callResponseSpec.content()).thenReturn("优先处理 P1 建议，补货数量以系统计算为准。");

        DisposalExplanation explanation = service.explain("MCU 补货方案", List.of(recommendation()), null,
                List.of());

        verify(requestSpec).toolCallbacks(any(ToolCallback[].class));
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).user(prompt.capture());
        assertTrue(prompt.getValue().contains("P1（86 分）"));
        assertTrue(prompt.getValue().contains("紧急度 40 + 缺口 30（缺口 100 / 需求 100）+ 最高风险 10 = 86"));
        assertTrue(prompt.getValue().contains("SKU-MCU-001"));
        assertTrue(prompt.getValue().contains("ORD-1001"));

        assertEquals("MODEL", explanation.source());
        assertTrue(explanation.modelAssisted());
        assertEquals("优先处理 P1 建议，补货数量以系统计算为准。", explanation.text());
        assertEquals(List.of(), explanation.toolCalls());
    }

    @Test
    void modelFailureReturnsExplicitDeterministicNarrative() {
        when(modelRegistry.getChatClient()).thenThrow(new IllegalStateException("no active model"));

        DisposalExplanation explanation = service.explain("MCU 补货方案", List.of(recommendation()), null,
                List.of());

        assertEquals("DETERMINISTIC_RULES", explanation.source());
        assertFalse(explanation.modelAssisted());
        assertEquals(List.of(), explanation.toolCalls());
        assertTrue(explanation.text().contains("86 分"));
        assertTrue(explanation.text().contains("以上数字均由确定性 Java 规则计算"));
    }

    private static DisposalRecommendation recommendation() {
        return new DisposalRecommendation("rec-1", DisposalActionType.REPLENISH_INVENTORY, DisposalPriority.P1, 86,
                "紧急度 40 + 缺口 30（缺口 100 / 需求 100）+ 最高风险 10 = 86", RiskLevel.HIGH, "SKU-MCU-001",
                null, List.of("ORD-1001"), "为 SKU-MCU-001 补货 100 件", "安排补货 100 件，并在 2026-10-22 前确认可用",
                "订单存在库存缺口，补货可降低缺口。", List.of("ORD-1001 缺口 100 件"), "缺口可从 100 降至 0",
                List.of("到货时间需人工确认"), List.of("补货成本未提供"), List.of("SC-SOP-002"),
                List.of("按当前库存静态测算"), "PENDING_CONFIRMATION", Instant.parse("2026-10-20T07:00:00Z"));
    }
}
