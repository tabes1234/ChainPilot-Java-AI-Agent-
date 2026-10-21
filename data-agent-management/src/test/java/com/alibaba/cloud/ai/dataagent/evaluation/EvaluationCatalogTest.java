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
package com.alibaba.cloud.ai.dataagent.evaluation;

import com.alibaba.cloud.ai.dataagent.evaluation.dataset.SupplyChainEvaluationDatasetLoader;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationCaseDefinition;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluationCatalogTest {

    private final EvaluationCatalog catalog = new EvaluationCatalog(
            new SupplyChainEvaluationDatasetLoader(new ObjectMapper()));

    @Test
    void exposesTwentyVersionedCasesWithUniqueIds() {
        List<EvaluationCaseDefinition> cases = catalog.allCases();
        assertEquals(20, cases.size());
        Set<String> ids = cases.stream().map(EvaluationCaseDefinition::id).collect(Collectors.toSet());
        assertEquals(cases.size(), ids.size());
        assertTrue(cases.stream().allMatch(EvaluationCaseDefinition::enabled));
        assertTrue(cases.stream().allMatch(definition -> !definition.version().isBlank()));
        assertTrue(cases.stream().allMatch(definition -> !definition.assertions().isEmpty()));
    }

    @Test
    void separatesDeterministicAndWorkflowCases() {
        assertEquals(14, catalog.deterministicCases().size());
        assertEquals(6, catalog.agentCases().size());
        assertTrue(catalog.deterministicCases().stream().allMatch(c -> c.mode() == EvaluationMode.OFFLINE));
        assertTrue(catalog.agentCases().stream().allMatch(c -> c.mode() == EvaluationMode.AGENT));
    }

    @Test
    void coversEveryRequiredScenarioCategory() {
        Set<String> categories = catalog.allCases()
            .stream()
            .map(EvaluationCaseDefinition::category)
            .collect(Collectors.toSet());
        assertEquals(Set.of("risk", "inventory", "supplier", "rag", "workflow"), categories);
        assertFalse(catalog.allCases()
            .stream()
            .filter(c -> c.category().equals("rag"))
            .flatMap(c -> c.assertions().stream())
            .findFirst()
            .orElse("")
            .isBlank());
    }

    @Test
    void workflowCasesDeclareEmbeddingDependencyExplicitly() {
        assertTrue(catalog.agentCases().stream().allMatch(definition -> !definition.embeddingRequired()),
                "current workflow-1.0.0 cases use lexical retrieval and must declare no embedding dependency");
        assertFalse(catalog.workflowCase("WF-ANALYSIS-HIGH-RISK").orElseThrow().embeddingRequired());
        assertTrue(catalog.agentCases().stream().allMatch(EvaluationCaseDefinition::chatRequired),
                "every AGENT case must declare its CHAT dependency explicitly");
        assertFalse(catalog.deterministicCases().stream().anyMatch(EvaluationCaseDefinition::chatRequired));
    }

    @Test
    void rejectsContradictoryOrOfflineModelDependenciesAndKeepsLegacyConstructorsSafe() {
        assertThrows(IllegalArgumentException.class, () -> new EvaluationCaseDefinition("BAD-OFFLINE", "bad", "bad",
                "risk", "KIND", EvaluationMode.OFFLINE, "q", "expected", null, "1.0.0", true, "basis",
                List.of("assertion"), false, true));
        assertThrows(IllegalArgumentException.class, () -> new EvaluationCaseDefinition("BAD-EMBEDDING", "bad", "bad",
                "workflow", "ANALYSIS", EvaluationMode.AGENT, "q", "expected", null, "workflow-1.0.0", true, "basis",
                List.of("assertion"), true, false));
        assertThrows(IllegalArgumentException.class, () -> new WorkflowEvaluationCase("BAD-WORKFLOW", "bad", "bad",
                "q", "ANALYSIS", "expected", List.of("assertion"), true, false));

        EvaluationCaseDefinition legacyOffline = new EvaluationCaseDefinition("LEGACY-OFFLINE", "legacy", "legacy",
                "risk", "ORDER_RISK", EvaluationMode.OFFLINE, "q", "expected", null, "1.0.0", true, "basis",
                List.of("assertion"));
        EvaluationCaseDefinition legacyAgent = new EvaluationCaseDefinition("LEGACY-AGENT", "legacy", "legacy",
                "workflow", "ANALYSIS", EvaluationMode.AGENT, "q", "expected", null, "workflow-1.0.0", true,
                "basis", List.of("assertion"));
        assertFalse(legacyOffline.chatRequired());
        assertFalse(legacyOffline.embeddingRequired());
        assertTrue(legacyAgent.chatRequired(), "legacy AGENT definitions must not silently lose CHAT dependency");
        assertFalse(legacyAgent.embeddingRequired());
    }

    @Test
    void keepsExpectationsOutsideBusinessCode() {
        EvaluationCaseDefinition orderCase = catalog.find("ORDER-ORD-1001").orElseThrow();
        assertEquals("ORD-1001", orderCase.targetId());
        assertTrue(orderCase.basis().contains("ORD-1001"));
        assertTrue(orderCase.assertions().stream().anyMatch(assertion -> assertion.contains("风险等级")));
        assertTrue(catalog.find("REPORT-FALLBACK-NO-MODEL").orElseThrow().mode() == EvaluationMode.AGENT);
    }

}
