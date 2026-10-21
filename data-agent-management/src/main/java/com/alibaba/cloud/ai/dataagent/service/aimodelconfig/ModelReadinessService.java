/*
 * Copyright 2026 the original author or authors.
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
package com.alibaba.cloud.ai.dataagent.service.aimodelconfig;

import com.alibaba.cloud.ai.dataagent.dto.ModelConfigDTO;
import com.alibaba.cloud.ai.dataagent.enums.ModelType;
import com.alibaba.cloud.ai.dataagent.vo.ModelCheckVo;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Tracks the user-initiated connection test for each model configuration.
 *
 * <p>Readiness never calls a provider. The only model request is the one explicitly
 * triggered by {@code POST /api/model-config/test/{id}}. Verification is scoped to the
 * current backend process, so a restart returns the models to "configured but untested"
 * rather than claiming a connection that was never tested by this process.
 */
@Service
public class ModelReadinessService {

    public static final String STATE_NOT_CONFIGURED = "NOT_CONFIGURED";

    public static final String STATE_CONFIGURED_UNVERIFIED = "CONFIGURED_UNVERIFIED";

    public static final String STATE_TEST_FAILED = "TEST_FAILED";

    public static final String STATE_VERIFIED = "VERIFIED";

    public static final String VERIFICATION_SCOPE = "CURRENT_BACKEND_PROCESS";

    public static final String EMBEDDING_DEPENDENCY_NOTE =
            "当前真实工作流不依赖向量检索；EMBEDDING 未配置时使用本地词典检索，不阻断最小 AGENT 验证。";

    private final ModelConfigDataService modelConfigDataService;

    private final ConcurrentMap<Integer, ConnectionCheck> checks = new ConcurrentHashMap<>();

    public ModelReadinessService(ModelConfigDataService modelConfigDataService) {
        this.modelConfigDataService = modelConfigDataService;
    }

    public ModelCheckVo readiness() {
        ModelConfigDTO chat = activeConfig(ModelType.CHAT);
        ModelConfigDTO embedding = activeConfig(ModelType.EMBEDDING);
        ConnectionCheck chatCheck = matchingCheck(chat);
        ConnectionCheck embeddingCheck = matchingCheck(embedding);
        String chatState = state(chat, chatCheck);
        String embeddingState = state(embedding, embeddingCheck);
        boolean chatVerified = STATE_VERIFIED.equals(chatState);
        boolean embeddingVerified = STATE_VERIFIED.equals(embeddingState);
        String blockReason = agentBlockReason(chatState, chatCheck);
        boolean agentReady = chat != null && chatVerified;
        return ModelCheckVo.builder()
            .chatModelReady(chat != null)
            .embeddingModelReady(embedding != null)
            .ready(chat != null && embedding != null)
            .chatConnectionVerified(chatVerified)
            .embeddingConnectionVerified(embeddingVerified)
            .chatState(chatState)
            .embeddingState(embeddingState)
            .chatProvider(chat == null ? null : chat.getProvider())
            .chatModelName(chat == null ? null : chat.getModelName())
            .chatBaseUrl(chat == null ? null : chat.getBaseUrl())
            .embeddingProvider(embedding == null ? null : embedding.getProvider())
            .embeddingModelName(embedding == null ? null : embedding.getModelName())
            .embeddingBaseUrl(embedding == null ? null : embedding.getBaseUrl())
            .chatTestedAt(chatCheck == null ? null : chatCheck.testedAt().toString())
            .embeddingTestedAt(embeddingCheck == null ? null : embeddingCheck.testedAt().toString())
            .chatTestError(chatCheck != null && !chatCheck.success() ? chatCheck.message() : null)
            .embeddingTestError(embeddingCheck != null && !embeddingCheck.success() ? embeddingCheck.message() : null)
            .embeddingRequiredForAgentEvaluation(false)
            .embeddingDependencyNote(EMBEDDING_DEPENDENCY_NOTE)
            .agentEvaluationReady(agentReady)
            .agentEvaluationBlockReason(blockReason)
            .verificationScope(VERIFICATION_SCOPE)
            .build();
    }

    public void recordSuccess(ModelConfigDTO config) {
        record(config, true, null);
    }

    public void recordFailure(ModelConfigDTO config, String message) {
        record(config, false, message);
    }

    public void invalidate(Integer configId) {
        if (configId != null) {
            checks.remove(configId);
        }
    }

    private void record(ModelConfigDTO config, boolean success, String message) {
        if (config == null || config.getId() == null || config.getModelType() == null) {
            return;
        }
        checks.put(config.getId(), new ConnectionCheck(success, Instant.now(),
                success ? null : sanitize(message, config), signature(config)));
    }

    private ModelConfigDTO activeConfig(ModelType type) {
        try {
            return modelConfigDataService.getActiveConfigByType(type);
        }
        catch (RuntimeException ignored) {
            return null;
        }
    }

    private ConnectionCheck matchingCheck(ModelConfigDTO config) {
        if (config == null || config.getId() == null) {
            return null;
        }
        ConnectionCheck check = checks.get(config.getId());
        if (check == null || !signature(config).equals(check.signature())) {
            return null;
        }
        return check;
    }

    private static String state(ModelConfigDTO config, ConnectionCheck check) {
        if (config == null) {
            return STATE_NOT_CONFIGURED;
        }
        if (check == null) {
            return STATE_CONFIGURED_UNVERIFIED;
        }
        return check.success() ? STATE_VERIFIED : STATE_TEST_FAILED;
    }

    private static String agentBlockReason(String chatState, ConnectionCheck chatCheck) {
        return switch (chatState) {
            case STATE_NOT_CONFIGURED -> "未配置激活的 CHAT 模型：请先在模型配置页新增并激活对话模型。";
            case STATE_CONFIGURED_UNVERIFIED -> "CHAT 模型尚未完成手动连接测试：请确认配置后点击「测试」，再启动真实 AGENT 评测。";
            case STATE_TEST_FAILED -> "CHAT 模型最近一次连接测试失败："
                    + (chatCheck == null || !StringUtils.hasText(chatCheck.message()) ? "未知错误" : chatCheck.message())
                    + "。请修正配置后重新测试。";
            default -> null;
        };
    }

    private static String signature(ModelConfigDTO config) {
        return String.join("|", Objects.toString(config.getId(), ""),
                Objects.toString(config.getModelType(), ""), Objects.toString(config.getProvider(), ""),
                Objects.toString(config.getBaseUrl(), ""), Objects.toString(config.getModelName(), ""),
                Objects.toString(config.getCompletionsPath(), ""), Objects.toString(config.getEmbeddingsPath(), ""),
                hashSecret(config.getApiKey()), hashSecret(config.getProxyPassword()),
                Objects.toString(config.getProxyEnabled(), ""), Objects.toString(config.getProxyHost(), ""),
                Objects.toString(config.getProxyPort(), ""), Objects.toString(config.getProxyUsername(), ""));
    }

    public static String sanitize(String message, ModelConfigDTO config) {
        String value = StringUtils.hasText(message) ? message : "连接测试失败";
        value = replaceSecret(value, config.getApiKey());
        value = replaceSecret(value, config.getProxyPassword());
        value = value.replaceAll("(?i)(authorization\\s*[:=]\\s*)(bearer\\s+)?[^\\s,;]+", "$1****");
        value = value.replaceAll("(?i)(https?://)([^\\s/@:]+):([^\\s/@]+)@", "$1****:****@");
        value = value.replaceAll("(?i)((?:api[_-]?key|token|secret|password)\\s*[=:]\\s*)[^\\s&,;]+", "$1****");
        value = value.replaceAll("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+", "$1****");
        value = value.replaceAll("\\s+", " ").trim();
        return value.length() > 500 ? value.substring(0, 500) : value;
    }

    private static String hashSecret(String secret) {
        if (!StringUtils.hasText(secret)) {
            return "";
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(secret.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String replaceSecret(String value, String secret) {
        return StringUtils.hasText(secret) ? value.replace(secret, "****") : value;
    }

    public record ConnectionCheck(boolean success, Instant testedAt, String message, String signature) {
    }

}
