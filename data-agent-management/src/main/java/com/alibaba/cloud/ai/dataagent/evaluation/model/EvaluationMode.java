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

/**
 * OFFLINE runs deterministic checks against production services with no model calls.
 * AGENT submits real supply-chain workflow tasks and must be started explicitly.
 */
public enum EvaluationMode {

    OFFLINE,

    AGENT;

    public static EvaluationMode parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("评测模式不能为空");
        }
        try {
            return valueOf(value.trim().toUpperCase());
        }
        catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("评测模式只能是 OFFLINE 或 AGENT");
        }
    }

}
