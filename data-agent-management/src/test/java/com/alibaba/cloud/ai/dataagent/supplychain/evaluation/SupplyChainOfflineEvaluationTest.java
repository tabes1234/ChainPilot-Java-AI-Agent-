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
package com.alibaba.cloud.ai.dataagent.supplychain.evaluation;

import com.alibaba.cloud.ai.dataagent.supplychain.evaluation.SupplyChainEvaluationDataset.EvaluationCase;
import com.alibaba.cloud.ai.dataagent.supplychain.evaluation.SupplyChainEvaluationDataset.Kind;
import com.alibaba.cloud.ai.dataagent.supplychain.evaluation.SupplyChainEvaluationDataset.RiskExpectation;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.SupplyChainDemoData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupplyChainOfflineEvaluationTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void offlineBaselinePassesEndToEndAndWritesReports() throws Exception {
        SupplyChainEvaluationDataset dataset = loadAndValidate();
        EvaluationOutcome outcome = new SupplyChainOfflineEvaluator(mapper, dataset).evaluate();

        Path markdown = SupplyChainEvaluationReportWriter.writeMarkdown(dataset, outcome);
        Path json = SupplyChainEvaluationReportWriter.writeJson(mapper, outcome);

        assertTrue(Files.exists(markdown), "markdown report must be written");
        assertTrue(Files.exists(json), "JSON report must be written");
        assertEquals(15, outcome.totalCases());
        assertEquals(0, outcome.failedCases(), () -> failureDetails(outcome));
        assertEquals(15, outcome.passedCases());
        assertTrue(outcome.factChecks() > 0, "business fact checks must have run");
        assertEquals(1.0, outcome.factConsistency(), 1e-9);

        RiskClassificationMetrics risk = outcome.riskMetrics();
        assertEquals(4, risk.evaluated());
        assertEquals(3, risk.truePositives());
        assertEquals(0, risk.falsePositives());
        assertEquals(0, risk.falseNegatives());
        assertEquals(1, risk.trueNegatives());
        assertEquals(1.0, risk.precision(), 1e-9);
        assertEquals(1.0, risk.recall(), 1e-9);
        assertEquals(1.0, risk.f1(), 1e-9);
        assertEquals(1.0, risk.accuracy(), 1e-9);

        RetrievalMetrics retrieval = outcome.retrievalMetrics();
        assertEquals(3, retrieval.evaluatedQueries());
        assertEquals(3, retrieval.hits());
        assertEquals(1.0, retrieval.hitRateAt3(), 1e-9);
        assertEquals(1.0, retrieval.meanReciprocalRank(), 1e-9);
        assertEquals(0, retrieval.negativeQueries());

        String report = Files.readString(markdown, StandardCharsets.UTF_8);
        assertTrue(report.contains("## 失败明细"));
        assertTrue(report.contains("词法"));
        assertTrue(report.contains("Hit@3"));
        assertTrue(Files.readString(json, StandardCharsets.UTF_8).contains("\"datasetVersion\" : \"1.0.0\""));
    }

    @Test
    void markdownReportMatchesJsonPropertyValuesAndConfusionMatrix() throws Exception {
        SupplyChainEvaluationDataset dataset = loadAndValidate();
        EvaluationOutcome outcome = new SupplyChainOfflineEvaluator(mapper, dataset).evaluate();
        String markdown = Files.readString(
                SupplyChainEvaluationReportWriter.writeMarkdown(dataset, outcome), StandardCharsets.UTF_8);
        JsonNode json = mapper.readTree(SupplyChainEvaluationReportWriter.writeJson(mapper, outcome).toFile());

        assertTrue(markdown.contains("| 样本总数 | " + json.path("totalCases").asInt() + " |"));
        assertTrue(markdown.contains("| 通过样本 | " + json.path("passedCases").asInt() + " |"));
        assertTrue(markdown.contains("| 失败样本 | " + json.path("failedCases").asInt() + " |"));
        assertTrue(markdown.contains("| 事实一致性检查 | " + json.path("factChecksPassed").asInt() + " / "
                + json.path("factChecks").asInt() + " |"));
        assertTrue(markdown.contains("| 事实一致率 | " + decimal(json.path("factConsistency").asDouble()) + " |"));

        JsonNode risk = json.path("riskMetrics");
        assertTrue(markdown.contains("| TP / FP / FN / TN | " + risk.path("truePositives").asInt() + " / "
                + risk.path("falsePositives").asInt() + " / " + risk.path("falseNegatives").asInt() + " / "
                + risk.path("trueNegatives").asInt() + " |"));
        assertTrue(markdown.contains("| Precision = TP / (TP + FP) | " + decimal(risk.path("precision").asDouble())
                + " |"));
        assertTrue(markdown.contains("| Recall = TP / (TP + FN) | " + decimal(risk.path("recall").asDouble()) + " |"));
        assertTrue(markdown.contains("| F1 = 2PR / (P + R) | " + decimal(risk.path("f1").asDouble()) + " |"));
        assertTrue(markdown.contains(
                "| Accuracy = (TP + TN) / 全集 | " + decimal(risk.path("accuracy").asDouble()) + " |"));

        JsonNode retrieval = json.path("retrievalMetrics");
        assertTrue(markdown.contains("| 有效查询数 | " + retrieval.path("evaluatedQueries").asInt() + " |"));
        assertTrue(markdown.contains("| 命中数 | " + retrieval.path("hits").asInt() + " |"));
        assertTrue(markdown.contains("| Hit@3 | " + decimal(retrieval.path("hitRateAt3").asDouble()) + " |"));
        assertTrue(markdown.contains("| MRR | " + decimal(retrieval.path("meanReciprocalRank").asDouble()) + " |"));
        assertTrue(markdown.contains("| 负向查询 / 通过数 | " + retrieval.path("negativeQueries").asInt() + " / "
                + retrieval.path("negativePasses").asInt() + " |"));

        assertEquals(risk.path("truePositives").asInt() + risk.path("trueNegatives").asInt(), risk.path("evaluated")
            .asInt());
    }

    @Test
    void fallbackSampleProvesDeterministicCompletionWithoutModelCalls() throws Exception {
        EvaluationOutcome outcome = new SupplyChainOfflineEvaluator(mapper, loadAndValidate()).evaluate();
        EvaluationCaseOutcome fallback = outcome.cases().stream()
            .filter(evaluationCase -> Kind.REPORT_FALLBACK.name().equals(evaluationCase.kind()))
            .findFirst()
            .orElseThrow();

        assertTrue(fallback.passed(), () -> failureDetails(outcome));
        assertTrue(checkPassed(fallback, "report.modelAssisted"));
        assertTrue(checkPassed(fallback, "report.plannerAssisted"));
        assertTrue(checkPassed(fallback, "report.toolCallsEmpty"));
        assertTrue(checkPassed(fallback, "events.MODEL_FAILED"));
        assertTrue(checkPassed(fallback, "events.FALLBACK_USED"));
        assertTrue(checkPassed(fallback, "events.MODEL_COMPLETED"));
        assertTrue(checkPassed(fallback, "events.TASK_COMPLETED"));
        assertTrue(checkPassed(fallback, "events.TASK_FAILED"));
        assertTrue(checkPassed(fallback, "task.reportPersisted"));
    }

    @Test
    void changedExpectationFailsOnlyTheAffectedSampleAndField() throws Exception {
        SupplyChainEvaluationDataset dataset = loadAndValidate();
        int index = indexOf(dataset, Kind.ORDER_RISK);
        EvaluationCase original = dataset.cases().get(index);
        RiskExpectation risk = original.risk();
        RiskExpectation changed = new RiskExpectation(risk.level(), 99, risk.shortage(), risk.atRisk(),
                risk.factorFragments(), risk.actionFragments());
        EvaluationCase mutated = new EvaluationCase(original.id(), original.kind(), original.scenario(),
                original.question(), original.targetId(), changed, original.inventory(), original.supplier(),
                original.retrieval(), original.report(), original.basis());
        List<EvaluationCase> cases = new ArrayList<>(dataset.cases());
        cases.set(index, mutated);

        EvaluationOutcome outcome = new SupplyChainOfflineEvaluator(mapper,
                new SupplyChainEvaluationDataset(dataset.datasetVersion(), dataset.description(), cases))
            .evaluate();

        assertEquals(1, outcome.failedCases(), () -> failureDetails(outcome));
        EvaluationCaseOutcome failed = outcome.failures().get(0);
        assertEquals(original.id(), failed.id());
        assertEquals(1, failed.failures().size());
        EvaluationCheck check = failed.failures().get(0);
        assertEquals("risk.score", check.field());
        assertEquals("99", check.expected());
        assertEquals("100", check.actual());
        assertFalse(check.passed());
    }

    @Test
    void reportArtifactsAreReproducibleAcrossRuns() throws Exception {
        SupplyChainEvaluationDataset dataset = loadAndValidate();
        EvaluationOutcome first = new SupplyChainOfflineEvaluator(mapper, dataset).evaluate();
        Path firstMarkdown = SupplyChainEvaluationReportWriter.writeMarkdown(dataset, first);
        Path firstJson = SupplyChainEvaluationReportWriter.writeJson(mapper, first);
        String firstMarkdownText = Files.readString(firstMarkdown, StandardCharsets.UTF_8);
        String firstJsonText = Files.readString(firstJson, StandardCharsets.UTF_8);

        EvaluationOutcome second = new SupplyChainOfflineEvaluator(mapper, dataset).evaluate();
        SupplyChainEvaluationReportWriter.writeMarkdown(dataset, second);
        SupplyChainEvaluationReportWriter.writeJson(mapper, second);

        assertEquals(firstMarkdownText, Files.readString(firstMarkdown, StandardCharsets.UTF_8));
        assertEquals(firstJsonText, Files.readString(firstJson, StandardCharsets.UTF_8));
    }

    private SupplyChainEvaluationDataset loadAndValidate() throws IOException {
        SupplyChainEvaluationDataset dataset = SupplyChainEvaluationDatasetLoader.load(mapper);
        SupplyChainEvaluationDatasetValidator.validate(dataset, new SupplyChainDemoData(),
                SupplyChainEvaluationDatasetLoader.knowledgeSourceIds());
        return dataset;
    }

    private static boolean checkPassed(EvaluationCaseOutcome outcome, String field) {
        return outcome.checks().stream().anyMatch(check -> field.equals(check.field()) && check.passed());
    }

    private static int indexOf(SupplyChainEvaluationDataset dataset, Kind kind) {
        for (int index = 0; index < dataset.cases().size(); index++) {
            if (dataset.cases().get(index).kind() == kind) {
                return index;
            }
        }
        throw new IllegalStateException("No case of kind " + kind);
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private static String failureDetails(EvaluationOutcome outcome) {
        return outcome.failures().stream()
            .map(evaluationCase -> evaluationCase.id() + " -> " + evaluationCase.failures().stream()
                .map(check -> check.field() + " expected=" + check.expected() + " actual=" + check.actual())
                .collect(Collectors.joining("; ")))
            .collect(Collectors.joining("\n"));
    }

}
