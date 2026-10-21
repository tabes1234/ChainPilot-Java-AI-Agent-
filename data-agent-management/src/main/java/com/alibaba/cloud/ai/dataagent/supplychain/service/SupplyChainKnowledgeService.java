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

import com.alibaba.cloud.ai.dataagent.supplychain.model.KnowledgeCitation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
@Service
public class SupplyChainKnowledgeService {

    private static final String KNOWLEDGE_PATTERN = "classpath*:knowledge/supply-chain/*.md";
    private static final Pattern TOKEN_SPLITTER = Pattern.compile("[\\s,，。；;：:、！？!?]+");
    private static final List<String> CHINESE_TERMS = List.of("订单", "延期", "履约", "库存", "安全库存", "供应商",
            "交付", "恢复", "缺料", "风险", "延迟", "可承诺", "预留量");

    private final List<Document> documents;
    private final Optional<VectorStore> vectorStore;

    public SupplyChainKnowledgeService(ObjectProvider<VectorStore> vectorStore,
            ResourcePatternResolver resourcePatternResolver) {
        this.vectorStore = Optional.ofNullable(vectorStore.getIfAvailable());
        this.documents = loadDocuments(resourcePatternResolver);
        indexDocuments();
    }

    public List<KnowledgeCitation> retrieve(String query) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }
        List<KnowledgeCitation> vectorResults = searchVectorStore(query);
        return vectorResults.isEmpty() ? searchLexically(query, documents) : vectorResults;
    }

    static List<KnowledgeCitation> searchLexically(String query, List<Document> documents) {
        String normalizedQuery = query.toLowerCase();
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : TOKEN_SPLITTER.split(normalizedQuery)) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        CHINESE_TERMS.stream().filter(normalizedQuery::contains).forEach(tokens::add);
        return documents.stream()
                .map(document -> Map.entry(document, score(document.getText().toLowerCase(), tokens)))
                .filter(entry -> entry.getValue() > 0)
                .sorted(Map.Entry.<Document, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(3)
                .map(entry -> toCitation(entry.getKey()))
                .toList();
    }

    private static int score(String content, Set<String> tokens) {
        int score = 0;
        for (String token : tokens) {
            if (token.length() > 1 && content.contains(token)) {
                score++;
            }
        }
        return score;
    }

    private List<KnowledgeCitation> searchVectorStore(String query) {
        if (vectorStore.isEmpty()) {
            return List.of();
        }
        try {
            return vectorStore.get().similaritySearch(SearchRequest.builder().query(query).topK(3)
                    .filterExpression("chainPilotScope == 'supply-chain'").build()).stream()
                    .map(SupplyChainKnowledgeService::toCitation).toList();
        }
        catch (RuntimeException exception) {
            log.debug("Supply-chain vector retrieval unavailable; using local lexical retrieval", exception);
            return List.of();
        }
    }

    private void indexDocuments() {
        if (documents.isEmpty() || vectorStore.isEmpty()) {
            return;
        }
        try {
            vectorStore.get().add(documents);
            log.info("Indexed {} ChainPilot supply-chain knowledge documents", documents.size());
        }
        catch (RuntimeException exception) {
            log.info("Supply-chain SOPs will use local lexical retrieval until an embedding model is configured");
        }
    }

    private static List<Document> loadDocuments(ResourcePatternResolver resolver) {
        List<Document> loaded = new ArrayList<>();
        try {
            for (Resource resource : resolver.getResources(KNOWLEDGE_PATTERN)) {
                String filename = resource.getFilename();
                String text = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                String title = text.lines().filter(line -> line.startsWith("# ")).findFirst()
                        .map(line -> line.substring(2).trim()).orElse(filename);
                loaded.add(new Document(text, Map.of("chainPilotScope", "supply-chain", "sourceId", filename,
                        "title", title)));
            }
        }
        catch (IOException exception) {
            throw new IllegalStateException("Unable to load ChainPilot supply-chain SOP documents", exception);
        }
        return List.copyOf(loaded);
    }

    private static KnowledgeCitation toCitation(Document document) {
        String excerpt = document.getText().replaceAll("\\s+", " ").trim();
        if (excerpt.length() > 240) {
            excerpt = excerpt.substring(0, 237) + "...";
        }
        return new KnowledgeCitation(String.valueOf(document.getMetadata().get("sourceId")),
                String.valueOf(document.getMetadata().get("title")), excerpt);
    }
}
