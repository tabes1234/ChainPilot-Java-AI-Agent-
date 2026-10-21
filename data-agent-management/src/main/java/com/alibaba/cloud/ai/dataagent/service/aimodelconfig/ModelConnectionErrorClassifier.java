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
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;

/**
 * Classifies provider failures by exception/status type, not by matching provider prose.
 * User-visible messages are fixed safe messages; raw diagnostics are only used after
 * credential sanitization for server-side logging.
 */
public final class ModelConnectionErrorClassifier {

    public static final String AUTHENTICATION_FAILED = "AUTHENTICATION_FAILED";

    public static final String CONNECTION_TIMEOUT = "CONNECTION_TIMEOUT";

    public static final String ENDPOINT_UNAVAILABLE = "ENDPOINT_UNAVAILABLE";

    public static final String INVALID_MODEL_CONFIGURATION = "INVALID_MODEL_CONFIGURATION";

    public static final String UNSUPPORTED_RESPONSE = "UNSUPPORTED_RESPONSE";

    public static final String RATE_LIMITED = "RATE_LIMITED";

    public static final String CONNECTION_TEST_FAILED = "CONNECTION_TEST_FAILED";

    private static final String AUTH_MESSAGE = "鉴权失败，请检查 API Key 或访问权限。";

    private static final String TIMEOUT_MESSAGE = "连接模型超时，请检查网络、代理或模型服务状态。";

    private static final String ENDPOINT_MESSAGE = "模型端点不可用，请检查 Base URL、接口路径和网络连接。";

    private static final String INVALID_CONFIG_MESSAGE = "模型配置无效，请检查 Base URL、模型名称和认证配置。";

    private static final String UNSUPPORTED_MESSAGE = "模型返回内容为空或格式不受支持。";

    private static final String RATE_LIMIT_MESSAGE = "模型服务限流或额度不足，请稍后重试或检查供应商额度。";

    private static final String GENERIC_MESSAGE = "连接测试失败，请检查模型配置或稍后重试。";

    private ModelConnectionErrorClassifier() {
    }

    public static ModelConnectionTestException classify(Throwable error) {
        if (error instanceof ModelConnectionTestException classified) {
            return classified;
        }
        if (hasCause(error, SocketTimeoutException.class) || hasCause(error, HttpTimeoutException.class)
                || hasCause(error, TimeoutException.class)) {
            return new ModelConnectionTestException(CONNECTION_TIMEOUT, TIMEOUT_MESSAGE);
        }
        if (error instanceof RestClientResponseException responseException) {
            int status = responseException.getStatusCode().value();
            if (status == 401 || status == 403) {
                return new ModelConnectionTestException(AUTHENTICATION_FAILED, AUTH_MESSAGE);
            }
            if (status == 404) {
                return new ModelConnectionTestException(ENDPOINT_UNAVAILABLE, ENDPOINT_MESSAGE);
            }
            if (status == 429) {
                return new ModelConnectionTestException(RATE_LIMITED, RATE_LIMIT_MESSAGE);
            }
            if (status >= 500) {
                return new ModelConnectionTestException(ENDPOINT_UNAVAILABLE, ENDPOINT_MESSAGE);
            }
            return new ModelConnectionTestException(CONNECTION_TEST_FAILED, GENERIC_MESSAGE);
        }
        if (error instanceof IllegalArgumentException) {
            return new ModelConnectionTestException(INVALID_MODEL_CONFIGURATION, INVALID_CONFIG_MESSAGE);
        }
        if (error instanceof ResourceAccessException
                || hasCause(error, ConnectException.class)
                || hasCause(error, UnknownHostException.class)) {
            return new ModelConnectionTestException(ENDPOINT_UNAVAILABLE, ENDPOINT_MESSAGE);
        }
        return new ModelConnectionTestException(CONNECTION_TEST_FAILED, GENERIC_MESSAGE);
    }

    /** Safe diagnostic for server logs: credentials are removed and the message is bounded. */
    public static String diagnostic(Throwable error, ModelConfigDTO config) {
        if (error == null) {
            return "UnknownFailure";
        }
        String message = error.getMessage() == null ? "" : error.getMessage();
        String value = error.getClass().getSimpleName()
                + (message.isBlank() ? "" : ": " + message);
        return ModelReadinessService.sanitize(value, config);
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable current = error; current != null && current.getCause() != current;
                current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }

}
