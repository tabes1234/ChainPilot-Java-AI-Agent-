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
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ModelConnectionErrorClassifierTest {

    private static final ModelConfigDTO CONFIG = config();

    @Test
    void classifiesByExceptionTypeAndStatusRatherThanProviderProse() {
        assertEquals(ModelConnectionErrorClassifier.AUTHENTICATION_FAILED,
                ModelConnectionErrorClassifier.classify(
                        HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "no", new HttpHeaders(),
                                new byte[0], StandardCharsets.UTF_8)).code());
        assertEquals(ModelConnectionErrorClassifier.ENDPOINT_UNAVAILABLE,
                ModelConnectionErrorClassifier.classify(
                        HttpClientErrorException.create(HttpStatus.NOT_FOUND, "no", new HttpHeaders(),
                                new byte[0], StandardCharsets.UTF_8)).code());
        assertEquals(ModelConnectionErrorClassifier.RATE_LIMITED,
                ModelConnectionErrorClassifier.classify(
                        HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "no", new HttpHeaders(),
                                new byte[0], StandardCharsets.UTF_8)).code());
        assertEquals(ModelConnectionErrorClassifier.CONNECTION_TIMEOUT,
                ModelConnectionErrorClassifier.classify(
                        new ResourceAccessException("provider failed", new SocketTimeoutException("read timed out")))
                    .code());
        assertEquals(ModelConnectionErrorClassifier.ENDPOINT_UNAVAILABLE,
                ModelConnectionErrorClassifier.classify(
                        new ResourceAccessException("provider failed", new ConnectException("connection refused")))
                    .code());
        assertEquals(ModelConnectionErrorClassifier.INVALID_MODEL_CONFIGURATION,
                ModelConnectionErrorClassifier.classify(new IllegalArgumentException("bad config")).code());
        assertEquals(ModelConnectionErrorClassifier.UNSUPPORTED_RESPONSE,
                ModelConnectionErrorClassifier.classify(new ModelConnectionTestException(
                        ModelConnectionErrorClassifier.UNSUPPORTED_RESPONSE, "empty payload")).code());
        assertEquals(ModelConnectionErrorClassifier.CONNECTION_TEST_FAILED,
                ModelConnectionErrorClassifier.classify(new RuntimeException("provider prose 401")).code());
    }

    @Test
    void sanitizedDiagnosticRetainsDiagnosisButRemovesCredentials() {
        String message = "request failed: Authorization: Bearer canary-auth "
                + "https://canary-user:canary-pass@example.invalid/v1?api_key=canary-key&token=canary-token "
                + "key=canary-secret";
        String diagnostic = ModelConnectionErrorClassifier.diagnostic(
                new RuntimeException(message), CONFIG);

        assertFalse(diagnostic.contains("canary-auth"));
        assertFalse(diagnostic.contains("canary-user"));
        assertFalse(diagnostic.contains("canary-pass"));
        assertFalse(diagnostic.contains("canary-key"));
        assertFalse(diagnostic.contains("canary-token"));
        assertFalse(diagnostic.contains("canary-secret"));
    }

    private static ModelConfigDTO config() {
        ModelConfigDTO config = new ModelConfigDTO();
        config.setId(1);
        config.setModelType("CHAT");
        config.setApiKey("canary-secret");
        config.setProxyPassword("canary-proxy-pass");
        return config;
    }

}
