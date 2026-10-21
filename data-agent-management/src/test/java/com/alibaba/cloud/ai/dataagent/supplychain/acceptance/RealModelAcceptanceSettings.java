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
import com.alibaba.cloud.ai.dataagent.enums.ModelType;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Test-only settings for the opt-in real-model acceptance run. Only explicitly named
 * {@code CHAINPILOT_REAL_MODEL_*} values are read, so an IDE or coding assistant model
 * configuration can never become ChainPilot's business model by accident.
 */
public final class RealModelAcceptanceSettings {

    public static final String ENABLE_PROPERTY = "chainpilot.real-model.acceptance";

    public static final String PROVIDER_PROPERTY = "chainpilot.real-model.provider";

    public static final String BASE_URL_PROPERTY = "chainpilot.real-model.base-url";

    public static final String MODEL_NAME_PROPERTY = "chainpilot.real-model.model-name";

    public static final String API_KEY_PROPERTY = "chainpilot.real-model.api-key";

    public static final String COMPLETIONS_PATH_PROPERTY = "chainpilot.real-model.completions-path";

    public static final String MAX_TOKENS_PROPERTY = "chainpilot.real-model.max-tokens";

    public static final String TIMEOUT_SECONDS_PROPERTY = "chainpilot.real-model.timeout-seconds";

    public static final String MAX_MODEL_CALLS_PROPERTY = "chainpilot.real-model.max-model-calls";

    private static final Map<String, String> ENVIRONMENT_NAMES = environmentNames();

    // Reasoning models charge reasoning_content against max_tokens; 512 can leave no budget for visible content.
    private static final int DEFAULT_MAX_TOKENS = 2048;

    private static final int MAX_MAX_TOKENS = 2048;

    private static final int DEFAULT_TIMEOUT_SECONDS = 180;

    private static final int MIN_TIMEOUT_SECONDS = 30;

    private static final int MAX_TIMEOUT_SECONDS = 600;

    private static final int DEFAULT_MAX_MODEL_CALLS = 12;

    private static final int MAX_MODEL_CALLS_LIMIT = 50;

    private final boolean enabled;

    private final String provider;

    private final String baseUrl;

    private final String modelName;

    private final String apiKey;

    private final String completionsPath;

    private final int maxTokens;

    private final int timeoutSeconds;

    private final int maxModelCalls;

    private final List<String> problems;

    private RealModelAcceptanceSettings(boolean enabled, String provider, String baseUrl, String modelName,
            String apiKey, String completionsPath, int maxTokens, int timeoutSeconds, int maxModelCalls,
            List<String> problems) {
        this.enabled = enabled;
        this.provider = provider;
        this.baseUrl = baseUrl;
        this.modelName = modelName;
        this.apiKey = apiKey;
        this.completionsPath = completionsPath;
        this.maxTokens = maxTokens;
        this.timeoutSeconds = timeoutSeconds;
        this.maxModelCalls = maxModelCalls;
        this.problems = List.copyOf(problems);
    }

    public static RealModelAcceptanceSettings fromSystem() {
        return from(key -> {
            String systemProperty = System.getProperty(key);
            if (StringUtils.hasText(systemProperty)) {
                return systemProperty;
            }
            String environmentName = ENVIRONMENT_NAMES.get(key);
            return environmentName == null ? null : System.getenv(environmentName);
        });
    }

    static RealModelAcceptanceSettings from(Map<String, String> values) {
        return from(values::get);
    }

    private static RealModelAcceptanceSettings from(Function<String, String> reader) {
        boolean enabled = "true".equalsIgnoreCase(trim(reader.apply(ENABLE_PROPERTY)));
        if (!enabled) {
            return new RealModelAcceptanceSettings(false, null, null, null, null, null,
                    DEFAULT_MAX_TOKENS, DEFAULT_TIMEOUT_SECONDS, DEFAULT_MAX_MODEL_CALLS, List.of());
        }

        List<String> problems = new ArrayList<>();
        String provider = required(reader, PROVIDER_PROPERTY, problems);
        String baseUrl = required(reader, BASE_URL_PROPERTY, problems);
        String modelName = required(reader, MODEL_NAME_PROPERTY, problems);
        String apiKey = required(reader, API_KEY_PROPERTY, problems);
        String completionsPath = trimToNull(reader.apply(COMPLETIONS_PATH_PROPERTY));

        validateBaseUrl(baseUrl, problems);

        int maxTokens = boundedInteger(reader, MAX_TOKENS_PROPERTY, DEFAULT_MAX_TOKENS, 1, MAX_MAX_TOKENS, problems);
        int timeoutSeconds = boundedInteger(reader, TIMEOUT_SECONDS_PROPERTY, DEFAULT_TIMEOUT_SECONDS,
                MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS, problems);
        int maxModelCalls = boundedInteger(reader, MAX_MODEL_CALLS_PROPERTY, DEFAULT_MAX_MODEL_CALLS, 1,
                MAX_MODEL_CALLS_LIMIT, problems);

        return new RealModelAcceptanceSettings(true, provider, baseUrl, modelName, apiKey, completionsPath,
                maxTokens, timeoutSeconds, maxModelCalls, problems);
    }

    public boolean enabled() {
        return enabled;
    }

    public String provider() {
        return provider;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String modelName() {
        return modelName;
    }

    public String apiKey() {
        return apiKey;
    }

    public String completionsPath() {
        return completionsPath;
    }

    public int maxTokens() {
        return maxTokens;
    }

    public int timeoutSeconds() {
        return timeoutSeconds;
    }

    public int maxModelCalls() {
        return maxModelCalls;
    }

    public List<String> problems() {
        return problems;
    }

    public ModelConfigDTO modelConfig() {
        return ModelConfigDTO.builder()
            .provider(provider)
            .apiKey(apiKey)
            .baseUrl(baseUrl)
            .modelName(modelName)
            .modelType(ModelType.CHAT.getCode())
            .completionsPath(completionsPath)
            .temperature(0.0)
            .maxTokens(maxTokens)
            .isActive(false)
            .proxyEnabled(false)
            .build();
    }

    public String endpointDescription() {
        if (!StringUtils.hasText(baseUrl)) {
            return "<missing base URL>";
        }
        try {
            URI uri = URI.create(baseUrl);
            String port = uri.getPort() > 0 ? ":" + uri.getPort() : "";
            return uri.getScheme() + "://" + uri.getHost() + port;
        }
        catch (IllegalArgumentException exception) {
            return "<invalid base URL>";
        }
    }

    public String problemMessage() {
        return "Real-model acceptance configuration is incomplete:\n - "
                + String.join("\n - ", problems)
                + "\nNo external model request was made.";
    }

    public void requireComplete() {
        if (!enabled) {
            throw new IllegalStateException("Real-model acceptance is disabled. Set "
                    + ENABLE_PROPERTY + "=true and provide " + PROVIDER_PROPERTY + ", " + BASE_URL_PROPERTY + ", "
                    + MODEL_NAME_PROPERTY + " and " + API_KEY_PROPERTY + ".");
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException(problemMessage());
        }
    }

    private static String required(Function<String, String> reader, String property, List<String> problems) {
        String value = trimToNull(reader.apply(property));
        if (value == null) {
            problems.add(name(property) + " is required");
        }
        return value;
    }

    private static int boundedInteger(Function<String, String> reader, String property, int defaultValue,
            int minimum, int maximum, List<String> problems) {
        String raw = trimToNull(reader.apply(property));
        if (raw == null) {
            return defaultValue;
        }
        try {
            int value = Integer.parseInt(raw);
            if (value < minimum || value > maximum) {
                problems.add(name(property) + " must be between " + minimum + " and " + maximum);
                return defaultValue;
            }
            return value;
        }
        catch (NumberFormatException exception) {
            problems.add(name(property) + " must be an integer between " + minimum + " and " + maximum);
            return defaultValue;
        }
    }

    private static void validateBaseUrl(String baseUrl, List<String> problems) {
        if (!StringUtils.hasText(baseUrl)) {
            return;
        }
        try {
            URI uri = URI.create(baseUrl);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme)) || !StringUtils.hasText(uri.getHost())) {
                problems.add(name(BASE_URL_PROPERTY) + " must be an absolute http(s) URL");
            }
        }
        catch (IllegalArgumentException exception) {
            problems.add(name(BASE_URL_PROPERTY) + " must be a valid absolute http(s) URL");
        }
    }

    private static String name(String property) {
        String environmentName = ENVIRONMENT_NAMES.get(property);
        return environmentName == null ? property : property + " (env " + environmentName + ")";
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static String trimToNull(String value) {
        String trimmed = trim(value);
        return StringUtils.hasText(trimmed) ? trimmed : null;
    }

    private static Map<String, String> environmentNames() {
        Map<String, String> names = new LinkedHashMap<>();
        names.put(ENABLE_PROPERTY, "CHAINPILOT_REAL_MODEL_ACCEPTANCE");
        names.put(PROVIDER_PROPERTY, "CHAINPILOT_REAL_MODEL_PROVIDER");
        names.put(BASE_URL_PROPERTY, "CHAINPILOT_REAL_MODEL_BASE_URL");
        names.put(MODEL_NAME_PROPERTY, "CHAINPILOT_REAL_MODEL_MODEL_NAME");
        names.put(API_KEY_PROPERTY, "CHAINPILOT_REAL_MODEL_API_KEY");
        names.put(COMPLETIONS_PATH_PROPERTY, "CHAINPILOT_REAL_MODEL_COMPLETIONS_PATH");
        names.put(MAX_TOKENS_PROPERTY, "CHAINPILOT_REAL_MODEL_MAX_TOKENS");
        names.put(TIMEOUT_SECONDS_PROPERTY, "CHAINPILOT_REAL_MODEL_TIMEOUT_SECONDS");
        names.put(MAX_MODEL_CALLS_PROPERTY, "CHAINPILOT_REAL_MODEL_MAX_MODEL_CALLS");
        return Map.copyOf(names);
    }

}
