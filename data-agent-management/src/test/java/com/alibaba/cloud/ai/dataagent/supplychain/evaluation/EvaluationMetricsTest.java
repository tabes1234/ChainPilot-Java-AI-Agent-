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

import com.alibaba.cloud.ai.dataagent.supplychain.evaluation.RetrievalMetrics.RetrievalObservation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluationMetricsTest {

    @Test
    void riskMetricsRetainConfusionMatrixAndFormulaResults() {
        RiskClassificationMetrics metrics = RiskClassificationMetrics.calculate(
                Set.of("ORDER-A", "ORDER-B"), Set.of("ORDER-A", "ORDER-C"),
                Set.of("ORDER-A", "ORDER-B", "ORDER-C", "ORDER-D"));

        assertEquals(4, metrics.evaluated());
        assertEquals(1, metrics.truePositives());
        assertEquals(1, metrics.falsePositives());
        assertEquals(1, metrics.falseNegatives());
        assertEquals(1, metrics.trueNegatives());
        assertEquals(0.5, metrics.precision());
        assertEquals(0.5, metrics.recall());
        assertEquals(0.5, metrics.f1());
        assertEquals(0.5, metrics.accuracy());
    }

    @Test
    void riskMetricsIgnorePredictionsOutsideTheEvaluatedUniverse() {
        RiskClassificationMetrics metrics = RiskClassificationMetrics.calculate(
                Set.of("ORDER-A"), Set.of("ORDER-A", "ORDER-NOT-IN-DATASET"), Set.of("ORDER-A", "ORDER-B"));

        assertEquals(1, metrics.truePositives());
        assertEquals(0, metrics.falsePositives());
        assertEquals(0, metrics.falseNegatives());
        assertEquals(1, metrics.trueNegatives());
        assertEquals(1.0, metrics.precision());
        assertEquals(1.0, metrics.accuracy());
    }

    @Test
    void riskMetricsUseZeroInsteadOfNaNForEveryEmptyDenominator() {
        RiskClassificationMetrics emptyUniverse = RiskClassificationMetrics.calculate(Set.of(), Set.of(), Set.of());
        assertEquals(0.0, emptyUniverse.precision());
        assertEquals(0.0, emptyUniverse.recall());
        assertEquals(0.0, emptyUniverse.f1());
        assertEquals(0.0, emptyUniverse.accuracy());

        RiskClassificationMetrics noExpectedPositive = RiskClassificationMetrics.calculate(
                Set.of(), Set.of("ORDER-A"), Set.of("ORDER-A"));
        assertEquals(0.0, noExpectedPositive.precision());
        assertEquals(0.0, noExpectedPositive.recall());
        assertEquals(0.0, noExpectedPositive.f1());
        assertEquals(0.0, noExpectedPositive.accuracy());

        RiskClassificationMetrics noPredictedPositive = RiskClassificationMetrics.calculate(
                Set.of("ORDER-A"), Set.of(), Set.of("ORDER-A"));
        assertEquals(0.0, noPredictedPositive.precision());
        assertEquals(0.0, noPredictedPositive.recall());
        assertEquals(0.0, noPredictedPositive.f1());
        assertEquals(0.0, noPredictedPositive.accuracy());
        for (double value : List.of(noPredictedPositive.precision(), noPredictedPositive.recall(),
                noPredictedPositive.f1(), noPredictedPositive.accuracy())) {
            assertFalse(Double.isNaN(value));
        }
    }

    @Test
    void retrievalMetricsUseTopThreeAndRankOfFirstMatch() {
        RetrievalMetrics metrics = RetrievalMetrics.calculate(List.of(
                new RetrievalObservation(List.of("SOP-1"), List.of("SOP-1", "SOP-2")),
                new RetrievalObservation(List.of("SOP-2"), List.of("SOP-1", "SOP-2", "SOP-3")),
                new RetrievalObservation(List.of("SOP-3"), List.of("SOP-1", "SOP-2")),
                new RetrievalObservation(List.of(), List.of())));

        assertEquals(3, metrics.evaluatedQueries());
        assertEquals(2, metrics.hits());
        assertEquals(2.0 / 3.0, metrics.hitRateAt3());
        assertEquals((1.0 + 0.5 + 0.0) / 3.0, metrics.meanReciprocalRank());
        assertEquals(1, metrics.negativeQueries());
        assertEquals(1, metrics.negativePasses());
    }

    @Test
    void retrievalMetricsExcludeNegativeQueriesAndHandleEmptyObservations() {
        RetrievalMetrics negativeOnly = RetrievalMetrics.calculate(
                List.of(new RetrievalObservation(List.of(), List.of("SOP-1"))));

        assertEquals(0, negativeOnly.evaluatedQueries());
        assertEquals(0, negativeOnly.hits());
        assertEquals(0.0, negativeOnly.hitRateAt3());
        assertEquals(0.0, negativeOnly.meanReciprocalRank());
        assertEquals(1, negativeOnly.negativeQueries());
        assertEquals(0, negativeOnly.negativePasses());
        assertFalse(Double.isNaN(negativeOnly.hitRateAt3()));

        RetrievalMetrics withoutObservations = RetrievalMetrics.calculate(List.of());
        assertEquals(0, withoutObservations.evaluatedQueries());
        assertEquals(0.0, withoutObservations.hitRateAt3());
        assertEquals(0.0, withoutObservations.meanReciprocalRank());
    }

    @Test
    void caseOutcomeFailsWhenAnySingleCheckFails() {
        EvaluationCheck passed = EvaluationCheck.equal("risk.score", 100, 100);
        EvaluationCheck failed = EvaluationCheck.contains("risk.factor[0]", "expected factor", "other text");

        EvaluationCaseOutcome outcome = EvaluationCaseOutcome.of("ORDER-1",
                SupplyChainEvaluationDataset.Kind.ORDER_RISK, List.of(passed, failed));

        assertFalse(outcome.passed());
        assertEquals(1, outcome.failures().size());
        assertEquals("risk.factor[0]", outcome.failures().get(0).field());
        assertTrue(outcome.failures().get(0).expected().contains("expected factor"));
        assertEquals("other text", outcome.failures().get(0).actual());
    }

}
