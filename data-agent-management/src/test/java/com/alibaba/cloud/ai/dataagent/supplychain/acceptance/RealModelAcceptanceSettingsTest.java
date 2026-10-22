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

import com.alibaba.cloud.ai.dataagent.dto.ModelConfigDTO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealModelAcceptanceSettingsTest {

    private static final String SECRET = "unit-test-secret-value";

    @Test
    void acceptanceIsDisabledUnlessExplicitlyRequested() {
        RealModelAcceptanceSettings settings = RealModelAcceptanceSettings.from(Map.of());

        assertFalse(settings.enabled());
        assertTrue(settings.problems().isEmpty());
        assertThrows(IllegalStateException.class, settings::requireComplete);
    }

    @Test
    void enabledWithoutConfigurationNamesEveryRequiredItem() {
        RealModelAcceptanceSettings settings = RealModelAcceptanceSettings.from(Map.of(
                RealModelAcceptanceSettings.ENABLE_PROPERTY, "true"));

        assertTrue(settings.enabled());
        assertEquals(4, settings.problems().size());
        String message = settings.problemMessage();
        assertTrue(message.contains(RealModelAcceptanceSettings.PROVIDER_PROPERTY));
        assertTrue(message.contains(RealModelAcceptanceSettings.BASE_URL_PROPERTY));
        assertTrue(message.contains(RealModelAcceptanceSettings.MODEL_NAME_PROPERTY));
        assertTrue(message.contains(RealModelAcceptanceSettings.API_KEY_PROPERTY));
        assertTrue(message.contains("CHAINPILOT_REAL_MODEL_API_KEY"));
        assertTrue(message.contains("No external model request was made."));
        assertThrows(IllegalStateException.class, settings::requireComplete);
    }

    @Test
    void completeConfigurationBuildsTheExistingChatConfigWithoutLeakingTheSecret() {
        RealModelAcceptanceSettings settings = completeSettings();

        assertEquals(0, settings.problems().size());
        ModelConfigDTO config = settings.modelConfig();
        assertEquals("provider-a", config.getProvider());
        assertEquals("https://model.example.test", config.getBaseUrl());
        assertEquals("chat-model-a", config.getModelName());
        assertEquals(SECRET, config.getApiKey());
        assertEquals("CHAT", config.getModelType());
        assertEquals(2048, config.getMaxTokens());
        assertFalse(config.getIsActive());

        assertFalse(settings.endpointDescription().contains(SECRET));
        assertFalse(settings.problemMessage().contains(SECRET));
        assertTrue(settings.endpointDescription().contains("model.example.test"));
    }

    @Test
    void invalidBoundsAndNonHttpEndpointsAreRejectedWithoutEchoingValues() {
        Map<String, String> values = new java.util.LinkedHashMap<>(completeValues());
        values.put(RealModelAcceptanceSettings.BASE_URL_PROPERTY, "ftp://model.example.test");
        values.put(RealModelAcceptanceSettings.MAX_TOKENS_PROPERTY, "0");
        values.put(RealModelAcceptanceSettings.TIMEOUT_SECONDS_PROPERTY, "5");
        values.put(RealModelAcceptanceSettings.MAX_MODEL_CALLS_PROPERTY, "0");

        RealModelAcceptanceSettings settings = RealModelAcceptanceSettings.from(values);

        assertEquals(4, settings.problems().size());
        String message = settings.problemMessage();
        assertTrue(message.contains(RealModelAcceptanceSettings.BASE_URL_PROPERTY));
        assertTrue(message.contains(RealModelAcceptanceSettings.MAX_TOKENS_PROPERTY));
        assertTrue(message.contains(RealModelAcceptanceSettings.TIMEOUT_SECONDS_PROPERTY));
        assertTrue(message.contains(RealModelAcceptanceSettings.MAX_MODEL_CALLS_PROPERTY));
        assertFalse(message.contains(SECRET));
        assertFalse(message.contains("ftp://model.example.test"));
    }

    @Test
    void requestBudgetDefaultsAreBoundedAndIndependentOfTheStageBudget() {
        RealModelAcceptanceSettings settings = completeSettings();

        assertEquals(12, settings.maxModelCalls(), "stage ceiling counts gen_ai client operations");
        assertEquals(20, settings.maxHttpAttempts(), "one stage may span up to 10 provider-side attempts");
        assertEquals(60, settings.maxRunHttpAttempts(), "the run-wide ceiling must outlive every scenario reset");
        assertTrue(settings.maxHttpAttempts() <= settings.maxRunHttpAttempts());
    }

    @Test
    void scenarioBudgetAboveTheRunBudgetIsRejected() {
        Map<String, String> values = new java.util.LinkedHashMap<>(completeValues());
        values.put(RealModelAcceptanceSettings.MAX_HTTP_ATTEMPTS_PROPERTY, "30");
        values.put(RealModelAcceptanceSettings.MAX_RUN_HTTP_ATTEMPTS_PROPERTY, "10");

        RealModelAcceptanceSettings settings = RealModelAcceptanceSettings.from(values);

        assertEquals(1, settings.problems().size(), () -> "got " + settings.problems());
        assertTrue(settings.problemMessage().contains(RealModelAcceptanceSettings.MAX_HTTP_ATTEMPTS_PROPERTY));
        assertTrue(settings.problemMessage().contains(RealModelAcceptanceSettings.MAX_RUN_HTTP_ATTEMPTS_PROPERTY));
        assertThrows(IllegalStateException.class, settings::requireComplete);
    }

    @Test
    void outOfRangeRequestBudgetsFallBackToTheSafeDefaults() {
        Map<String, String> values = new java.util.LinkedHashMap<>(completeValues());
        values.put(RealModelAcceptanceSettings.MAX_HTTP_ATTEMPTS_PROPERTY, "400");
        values.put(RealModelAcceptanceSettings.MAX_RUN_HTTP_ATTEMPTS_PROPERTY, "not-a-number");

        RealModelAcceptanceSettings settings = RealModelAcceptanceSettings.from(values);

        assertEquals(2, settings.problems().size(), () -> "got " + settings.problems());
        assertEquals(20, settings.maxHttpAttempts());
        assertEquals(60, settings.maxRunHttpAttempts());
        assertThrows(IllegalStateException.class, settings::requireComplete);
    }

    @Test
    void agentCaseSelectionDefaultsParsesAndRejectsOverload() {
        assertEquals(List.of(RealModelAcceptanceSettings.DEFAULT_AGENT_CASES), completeSettings().agentCases());

        Map<String, String> values = new java.util.LinkedHashMap<>(completeValues());
        values.put(RealModelAcceptanceSettings.AGENT_CASES_PROPERTY,
                " WF-ANALYSIS-HIGH-RISK ,WF-DECISION-EVIDENCE,WF-ANALYSIS-HIGH-RISK");
        RealModelAcceptanceSettings parsed = RealModelAcceptanceSettings.from(values);
        assertEquals(0, parsed.problems().size(), () -> "got " + parsed.problems());
        assertEquals(List.of("WF-ANALYSIS-HIGH-RISK", "WF-DECISION-EVIDENCE"), parsed.agentCases());

        Map<String, String> blank = new java.util.LinkedHashMap<>(completeValues());
        blank.put(RealModelAcceptanceSettings.AGENT_CASES_PROPERTY, " , ");
        RealModelAcceptanceSettings rejectedBlank = RealModelAcceptanceSettings.from(blank);
        assertEquals(List.of(RealModelAcceptanceSettings.AGENT_CASES_PROPERTY),
                rejectedBlank.problems().stream().map(problem -> problem.split(" ")[0]).toList(),
                () -> "got " + rejectedBlank.problems());
        assertThrows(IllegalStateException.class, rejectedBlank::requireComplete);

        Map<String, String> tooMany = new java.util.LinkedHashMap<>(completeValues());
        tooMany.put(RealModelAcceptanceSettings.AGENT_CASES_PROPERTY, "A,B,C,D,E,F");
        assertEquals(1, RealModelAcceptanceSettings.from(tooMany).problems().size());

        // Disabled runs still report the default, so a misconfiguration cannot silently widen scope.
        assertEquals(List.of(RealModelAcceptanceSettings.DEFAULT_AGENT_CASES),
                RealModelAcceptanceSettings.from(Map.of()).agentCases());
    }

    private static RealModelAcceptanceSettings completeSettings() {
        return RealModelAcceptanceSettings.from(completeValues());
    }

    /** Only an external endpoint may produce provider evidence; a local port is always a rehearsal. */
    @Test
    void endpointClassificationDecidesWhatMayBeCalledProviderEvidence() {
        assertTrue(completeSettings().endpointIsExternal(), "a named host is an external endpoint");

        Map<String, String> values = new java.util.LinkedHashMap<>(completeValues());
        for (String local : List.of("http://127.0.0.1:59999", "http://localhost:8080", "http://[::1]:9000",
                "http://0.0.0.0:8080", "http://stub.localhost:8080")) {
            values.put(RealModelAcceptanceSettings.BASE_URL_PROPERTY, local);
            assertFalse(RealModelAcceptanceSettings.from(values).endpointIsExternal(),
                    local + " must never be labelled provider evidence");
            assertEquals(0, RealModelAcceptanceSettings.from(values).problems().size(),
                    "a local endpoint is still a valid configuration to rehearse against");
        }

        values.put(RealModelAcceptanceSettings.BASE_URL_PROPERTY, "not a url");
        assertFalse(RealModelAcceptanceSettings.from(values).endpointIsExternal());
    }

    private static Map<String, String> completeValues() {
        return Map.of(
                RealModelAcceptanceSettings.ENABLE_PROPERTY, "true",
                RealModelAcceptanceSettings.PROVIDER_PROPERTY, "provider-a",
                RealModelAcceptanceSettings.BASE_URL_PROPERTY, "https://model.example.test",
                RealModelAcceptanceSettings.MODEL_NAME_PROPERTY, "chat-model-a",
                RealModelAcceptanceSettings.API_KEY_PROPERTY, SECRET);
    }

}
