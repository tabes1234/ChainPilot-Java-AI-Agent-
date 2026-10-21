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

    private static RealModelAcceptanceSettings completeSettings() {
        return RealModelAcceptanceSettings.from(completeValues());
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
