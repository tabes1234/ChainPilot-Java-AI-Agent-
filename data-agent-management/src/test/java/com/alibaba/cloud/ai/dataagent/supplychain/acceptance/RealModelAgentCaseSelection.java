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
package com.alibaba.cloud.ai.dataagent.supplychain.acceptance;

import com.alibaba.cloud.ai.dataagent.evaluation.EvaluationCatalog;
import com.alibaba.cloud.ai.dataagent.evaluation.WorkflowEvaluationCase;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationCaseDefinition;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationMode;

import java.util.List;
import java.util.Objects;

/**
 * Which AGENT cases are eligible for a real-model acceptance run, and why.
 *
 * <p>Eligibility is decided by the case's own contract, never by its name or by free text: a case is
 * only accepted when it is an AGENT case that declares a model-participation assertion. That keeps a
 * guardrail case (deterministic intent parsing, no model call) and a degradation case out of the
 * real-model pass rate, so "all selected cases passed" always means "the model took part".
 */
final class RealModelAgentCaseSelection {

    static final List<String> PARTICIPATION_ASSERTIONS = List.of("report-model-assisted", "report-model-completed",
            "decision-model-assisted", "decision-model-completed");

    private RealModelAgentCaseSelection() {
    }

    static boolean eligible(EvaluationCatalog catalog, String caseId) {
        return rejectionReason(catalog, caseId) == null;
    }

    /**
     * @return the ids that must not be part of a real-model acceptance run, each with the reason it was
     *         refused, so the caller can fail before any request is built and say why afterwards.
     */
    static List<String> rejected(EvaluationCatalog catalog, List<String> caseIds) {
        return caseIds.stream()
            .map(caseId -> {
                String reason = rejectionReason(catalog, caseId);
                return reason == null ? null : caseId + " (" + reason + ")";
            })
            .filter(Objects::nonNull)
            .toList();
    }

    /** @return why the case cannot prove model participation, or null when it can. */
    private static String rejectionReason(EvaluationCatalog catalog, String caseId) {
        EvaluationCaseDefinition definition = catalog.find(caseId).orElse(null);
        if (definition == null) {
            return "not a known evaluation case";
        }
        if (definition.mode() != EvaluationMode.AGENT) {
            return "an " + definition.mode() + " case never calls the model";
        }
        WorkflowEvaluationCase workflow = catalog.workflowCase(caseId).orElse(null);
        if (workflow == null) {
            return "is not a workflow catalogue case, so it declares no model-participation assertion";
        }
        return PARTICIPATION_ASSERTIONS.stream().anyMatch(workflow::requiresAssertion) ? null
                : "declares no model-participation assertion";
    }

}
