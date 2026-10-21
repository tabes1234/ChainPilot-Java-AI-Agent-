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

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SupplyChainKnowledgeServiceTest {

    @Test
    void lexicalSearchReturnsRelevantSourceCitation() {
        Document fulfillment = new Document("延期订单需要确认供应商恢复日期并通知客户。",
                Map.of("sourceId", "SC-SOP-001", "title", "订单履约"));
        Document inventory = new Document("可承诺库存应扣除预留量和安全库存。",
                Map.of("sourceId", "SC-SOP-002", "title", "库存分配"));

        var citations = SupplyChainKnowledgeService.searchLexically("订单延期供应商恢复", List.of(fulfillment, inventory));

        assertEquals(1, citations.size());
        assertEquals("SC-SOP-001", citations.get(0).sourceId());
        assertTrue(citations.get(0).excerpt().contains("恢复日期"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void localSopsReturnCitationsForChineseRiskQuestionWithoutEmbeddingModel() {
        ObjectProvider<VectorStore> vectorStore = mock(ObjectProvider.class);
        when(vectorStore.getIfAvailable()).thenReturn(null);
        SupplyChainKnowledgeService knowledgeService = new SupplyChainKnowledgeService(vectorStore,
                new PathMatchingResourcePatternResolver());

        var citations = knowledgeService.retrieve("分析延期订单、库存短缺和供应商风险");

        assertTrue(citations.stream().anyMatch(item -> item.sourceId().contains("SC-SOP-001")));
        assertTrue(citations.stream().anyMatch(item -> item.sourceId().contains("SC-SOP-002")));
        assertTrue(citations.stream().anyMatch(item -> item.sourceId().contains("SC-SOP-003")));
    }
}
