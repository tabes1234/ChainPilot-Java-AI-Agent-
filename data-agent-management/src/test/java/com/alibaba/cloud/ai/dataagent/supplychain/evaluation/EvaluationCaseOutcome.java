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

public record EvaluationCaseOutcome(String id, String kind, boolean passed, List<EvaluationCheck> checks) {

    public EvaluationCaseOutcome {
        checks = List.copyOf(checks);
    }

    public static EvaluationCaseOutcome of(String id, SupplyChainEvaluationDataset.Kind kind,
            List<EvaluationCheck> checks) {
        return new EvaluationCaseOutcome(id, kind.name(), checks.stream().allMatch(EvaluationCheck::passed), checks);
    }

    public List<EvaluationCheck> failures() {
        return checks.stream().filter(check -> !check.passed()).toList();
    }
}
