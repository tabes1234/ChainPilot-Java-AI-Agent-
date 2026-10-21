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
import java.util.Objects;

/** One named expected/actual comparison so a failure identifies both the sample and field. */
public record EvaluationCheck(String field, String expected, String actual, boolean passed) {

    public static EvaluationCheck equal(String field, Object expected, Object actual) {
        return new EvaluationCheck(field, stringify(expected), stringify(actual), Objects.equals(expected, actual));
    }

    public static EvaluationCheck contains(String field, String expectedFragment, String actualText) {
        boolean passed = actualText != null && expectedFragment != null && actualText.contains(expectedFragment);
        return new EvaluationCheck(field, "contains " + stringify(expectedFragment), stringify(actualText), passed);
    }

    public static EvaluationCheck containsElement(String field, String expectedElement, List<String> actualElements) {
        List<String> values = actualElements == null ? List.of() : actualElements;
        return new EvaluationCheck(field, "contains " + stringify(expectedElement), stringify(values),
                values.contains(expectedElement));
    }

    public static EvaluationCheck close(String field, double expected, double actual) {
        return new EvaluationCheck(field, stringify(expected), stringify(actual), Math.abs(expected - actual) < 1e-9);
    }

    private static String stringify(Object value) {
        return String.valueOf(value);
    }
}
