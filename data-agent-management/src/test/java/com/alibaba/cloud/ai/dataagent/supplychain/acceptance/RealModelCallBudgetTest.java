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
import com.alibaba.cloud.ai.dataagent.service.aimodelconfig.DynamicModelFactory;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the real-model acceptance budgets at the outbound HTTP boundary using a loopback endpoint
 * only. Spring AI 1.1.2 re-sends any 5xx or connection failure up to 10 times per model stage
 * ({@code RetryUtils.DEFAULT_RETRY_TEMPLATE}: maxAttempts 10, exponential backoff 2 s, x5, max 180 s),
 * so a budget that only counts {@code gen_ai.client.operation} stages does not bound paid requests.
 * These tests measure that amplification and show the request is blocked before it is sent.
 *
 * <p>Nothing here reaches a provider: the base URL is always a 127.0.0.1 test server and the API key
 * is a fixed dummy value that is never sent outside the loopback.
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class RealModelCallBudgetTest {

    private static final String DUMMY_KEY = "budget-test-key-local-only";

    private static final String SERVER_ERROR_BODY = "{\"error\":{\"message\":\"server error\"}}";

    private static final String RATE_LIMIT_BODY = "{\"error\":{\"message\":\"rate limit\"}}";

    private static final String OK_BODY = """
            {"id":"1","object":"chat.completion","created":1,"model":"budget-probe",
             "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
            """;

    private final List<HttpServer> servers = new ArrayList<>();

    @AfterEach
    void stopServers() {
        servers.forEach(server -> server.stop(0));
        servers.clear();
    }

    @Test
    void httpBudgetBlocksTheNextAttemptBeforeItIsSent() throws IOException {
        AtomicInteger served = new AtomicInteger();
        Probe probe = probe(endpoint(served, 500, SERVER_ERROR_BODY));
        probe.handler().limitModelCalls(40);
        probe.handler().limitHttpAttempts(2);
        probe.handler().limitRunHttpAttempts(40);

        assertThrows(RuntimeException.class, () -> probe.model().call(new Prompt("budget probe")));

        // The provider client really did retry at the HTTP layer (attempt 2 was served) and attempt 3
        // was counted then refused, so the endpoint never saw a third request.
        assertEquals(1, probe.handler().modelOperations(), "one model stage spans several HTTP attempts");
        assertEquals(3, probe.handler().httpAttempts(), "the blocked attempt is still counted at the boundary");
        assertEquals(2, served.get(), "the over-budget request must never reach the endpoint");
        assertTrue(probe.handler().httpBudgetExceeded());
        assertFalse(probe.handler().runBudgetExceeded());
        assertFalse(probe.handler().budgetExceeded());
    }

    @Test
    void runBudgetIsEnforcedIndependentlyOfThePerScenarioBudget() throws IOException {
        AtomicInteger served = new AtomicInteger();
        Probe probe = probe(endpoint(served, 500, SERVER_ERROR_BODY));
        probe.handler().limitModelCalls(40);
        probe.handler().limitHttpAttempts(40);
        probe.handler().limitRunHttpAttempts(1);

        assertThrows(RuntimeException.class, () -> probe.model().call(new Prompt("budget probe")));

        assertEquals(1, served.get());
        assertTrue(probe.handler().runBudgetExceeded(), "the run-wide ceiling must stop the run");
        assertFalse(probe.handler().httpBudgetExceeded(), "the per-scenario ceiling was not the binding limit");
    }

    @Test
    void successfulCallConsumesExactlyOneHttpRequestAndTripsNoBudget() throws IOException {
        AtomicInteger served = new AtomicInteger();
        Probe probe = probe(endpoint(served, 200, OK_BODY));
        probe.handler().limitModelCalls(12);
        probe.handler().limitHttpAttempts(20);
        probe.handler().limitRunHttpAttempts(60);

        assertEquals("ok", probe.model().call(new Prompt("budget probe")).getResult().getOutput().getText());

        assertEquals(1, served.get(), "a successful stage must not look like a retry storm");
        assertEquals(1, probe.handler().httpAttempts());
        assertEquals(1, probe.handler().modelOperations());
        assertFalse(probe.handler().budgetExceeded());
        assertFalse(probe.handler().httpBudgetExceeded());
        assertFalse(probe.handler().runBudgetExceeded());
    }

    @Test
    void rateLimitedResponsesAreNotRetriedByTheProviderClient() throws IOException {
        AtomicInteger served = new AtomicInteger();
        Probe probe = probe(endpoint(served, 429, RATE_LIMIT_BODY));
        probe.handler().limitModelCalls(1);
        probe.handler().limitHttpAttempts(20);
        probe.handler().limitRunHttpAttempts(60);

        assertThrows(RuntimeException.class, () -> probe.model().call(new Prompt("budget probe")));

        // 4xx maps to NonTransientAiException, so the provider client does not retry it. The
        // application-level rate-limit retry is what multiplies these, and each retry is its own
        // model stage, so both budgets have to be enforced together.
        assertEquals(1, served.get());
        assertEquals(1, probe.handler().httpAttempts());
        assertFalse(probe.handler().httpBudgetExceeded());
    }

    private Probe probe(String baseUrl) throws IOException {
        ObservationRegistry registry = ObservationRegistry.create();
        RecordingObservationHandler handler = new RecordingObservationHandler();
        registry.observationConfig().observationHandler(handler);
        ModelConfigDTO config = ModelConfigDTO.builder()
            .provider("custom")
            .apiKey(DUMMY_KEY)
            .baseUrl(baseUrl)
            .modelName("budget-probe")
            .modelType(ModelType.CHAT.getCode())
            .temperature(0.0)
            .maxTokens(64)
            .isActive(true)
            .proxyEnabled(false)
            .build();
        // The production factory and production model client are used unchanged, so the budget is
        // measured on the same request path the acceptance run takes.
        return new Probe(new DynamicModelFactory(registry).createChatModel(config), handler);
    }

    private record Probe(ChatModel model, RecordingObservationHandler handler) {
    }

    private String endpoint(AtomicInteger served, int status, String body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            served.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream stream = exchange.getResponseBody()) {
                stream.write(payload);
            }
        });
        server.start();
        servers.add(server);
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

}
