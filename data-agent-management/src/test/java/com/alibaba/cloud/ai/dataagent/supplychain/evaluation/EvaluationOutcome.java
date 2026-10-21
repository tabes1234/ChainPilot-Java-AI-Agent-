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

import java.util.List;

public record EvaluationOutcome(String datasetVersion, int totalCases, int passedCases, int failedCases,
        int factChecks, int factChecksPassed, double factConsistency, RiskClassificationMetrics riskMetrics,
        RetrievalMetrics retrievalMetrics, List<EvaluationCaseOutcome> cases) {

    public EvaluationOutcome {
        cases = List.copyOf(cases);
    }

    public static EvaluationOutcome summarize(String datasetVersion, List<EvaluationCheck> factChecks,
            RiskClassificationMetrics riskMetrics, RetrievalMetrics retrievalMetrics,
            List<EvaluationCaseOutcome> cases) {
        int passedChecks = (int) factChecks.stream().filter(EvaluationCheck::passed).count();
        int passedCases = (int) cases.stream().filter(EvaluationCaseOutcome::passed).count();
        int failedCases = cases.size() - passedCases;
        double consistency = factChecks.isEmpty() ? 0.0 : (double) passedChecks / factChecks.size();
        return new EvaluationOutcome(datasetVersion, cases.size(), passedCases, failedCases, factChecks.size(),
                passedChecks, consistency, riskMetrics, retrievalMetrics, cases);
    }

    public List<EvaluationCaseOutcome> failures() {
        return cases.stream().filter(evaluationCase -> !evaluationCase.passed()).toList();
    }
}
