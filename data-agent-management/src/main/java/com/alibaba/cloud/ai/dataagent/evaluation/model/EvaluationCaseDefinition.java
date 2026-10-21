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
package com.alibaba.cloud.ai.dataagent.evaluation.model;

import java.util.List;

/** A versioned, enabled case exposed by the evaluation catalog. */
public record EvaluationCaseDefinition(String id, String name, String scenario, String category, String kind,
        EvaluationMode mode, String question, String expectedBehavior, String targetId, String version,
        boolean enabled, String basis, List<String> assertions, boolean embeddingRequired, boolean chatRequired) {

    public EvaluationCaseDefinition(String id, String name, String scenario, String category, String kind,
            EvaluationMode mode, String question, String expectedBehavior, String targetId, String version,
            boolean enabled, String basis, List<String> assertions) {
        this(id, name, scenario, category, kind, mode, question, expectedBehavior, targetId, version, enabled, basis,
                assertions, false, mode == EvaluationMode.AGENT);
    }

    public EvaluationCaseDefinition(String id, String name, String scenario, String category, String kind,
            EvaluationMode mode, String question, String expectedBehavior, String targetId, String version,
            boolean enabled, String basis, List<String> assertions, boolean embeddingRequired) {
        this(id, name, scenario, category, kind, mode, question, expectedBehavior, targetId, version, enabled, basis,
                assertions, embeddingRequired, mode == EvaluationMode.AGENT);
    }

    public EvaluationCaseDefinition {
        if (mode == EvaluationMode.OFFLINE && (chatRequired || embeddingRequired)) {
            throw new IllegalArgumentException("OFFLINE 案例不得声明 CHAT 或 EMBEDDING 依赖");
        }
        if (embeddingRequired && !chatRequired) {
            throw new IllegalArgumentException("需要 EMBEDDING 的案例必须同时声明 CHAT 依赖");
        }
        assertions = assertions == null ? List.of() : List.copyOf(assertions);
    }

}
