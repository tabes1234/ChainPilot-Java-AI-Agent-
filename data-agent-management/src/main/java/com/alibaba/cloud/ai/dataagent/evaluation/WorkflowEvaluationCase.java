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

import java.util.List;

/**
 * A real workflow case. The agent evaluation mode submits the question through the backend.
 * {@code requiredAssertionIds} names the assertions the evaluation must actually execute; a
 * declared assertion that was never produced fails the case instead of passing silently.
 */
public record WorkflowEvaluationCase(String id, String name, String scenario, String question, String taskMode,
        String expectedBehavior, List<String> assertions, List<String> requiredAssertionIds,
        boolean embeddingRequired, boolean chatRequired) {

    public WorkflowEvaluationCase {
        if (embeddingRequired && !chatRequired) {
            throw new IllegalArgumentException("需要 EMBEDDING 的工作流案例必须同时声明 CHAT 依赖");
        }
        assertions = assertions == null ? List.of() : List.copyOf(assertions);
        if (requiredAssertionIds == null || requiredAssertionIds.isEmpty()) {
            throw new IllegalArgumentException("工作流案例必须声明需要执行的断言: " + id);
        }
        requiredAssertionIds = List.copyOf(requiredAssertionIds);
    }

    public WorkflowEvaluationCase(String id, String name, String scenario, String question, String taskMode,
            String expectedBehavior, List<String> assertions, List<String> requiredAssertionIds) {
        this(id, name, scenario, question, taskMode, expectedBehavior, assertions, requiredAssertionIds, false, true);
    }

    public boolean requiresAssertion(String assertionId) {
        return requiredAssertionIds.contains(assertionId);
    }

}
