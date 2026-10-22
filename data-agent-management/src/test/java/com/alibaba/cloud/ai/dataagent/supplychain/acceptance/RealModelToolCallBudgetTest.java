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

import com.alibaba.cloud.ai.dataagent.service.aimodelconfig.AiModelRegistry;
import com.alibaba.cloud.ai.dataagent.supplychain.ChainPilotTestApplication;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainEventType;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskEvent;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskStatus;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tool Calling budget acceptance on the real request path, with a loopback OpenAI-compatible
 * endpoint only. The fake server decides protocol responses; the production planner, the report
 * call, Spring AI's internal tool execution, the result feedback and the outbound budgets all run
 * unchanged, so every request counted here is one a paid provider would have received.
 *
 * <p>Two counters are compared throughout: the model-stage count ({@code gen_ai.client.operation})
 * and the outbound request-attempt count ({@code http.client.requests}), plus what the loopback
 * endpoint actually received. A provider-side retry is invisible to the stage counter but not to
 * the request counter, which is exactly the gap the real-model run must not walk into blind.
 */
@SpringBootTest(classes = { ChainPilotTestApplication.class,
        SupplyChainRealModelAcceptanceIT.RealModelAcceptanceConfiguration.class },
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:chainpilot_tool_call_budget;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;MODE=MySQL",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.sql.init.mode=always",
                "spring.sql.init.schema-locations=classpath:sql/h2/schema-h2.sql",
                "spring.sql.init.continue-on-error=true",
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.reactive.ReactiveSecurityAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.security.reactive.ReactiveUserDetailsServiceAutoConfiguration",
                "logging.level.root=warn",
                "logging.level.com.alibaba.cloud.ai.dataagent.supplychain=info" })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class RealModelToolCallBudgetTest {

    private static final String TOOL_CALL_ID = "call_chainpilot_budget_1";

    private static final String INVENTORY_SKU = "SKU-MCU-001";

    private static final String QUESTION = "分析今天有哪些订单可能延期，结合库存和供应商风险，给出处理建议。";

    private static final String LOCAL_KEY = "local-tool-budget-key-never-leaves-loopback";

    private static final ScriptedOpenAiServer SERVER = ScriptedOpenAiServer.start();

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RecordingObservationHandler observations;

    @Autowired
    private AiModelRegistry aiModelRegistry;

    /** Requests already counted by earlier scenarios; the run ceiling is class-wide by design. */
    private int runBase;

    @BeforeAll
    void configureLocalModelThroughTheProductionApi() {
        jdbcTemplate.update("DELETE FROM chainpilot_task_event");
        jdbcTemplate.update("DELETE FROM chainpilot_analysis_task");
        jdbcTemplate.update("DELETE FROM model_config");

        Map<String, Object> payload = Map.of("provider", "custom", "apiKey", LOCAL_KEY, "baseUrl", SERVER.baseUrl(),
                "modelName", "local-tool-budget-model", "modelType", "CHAT", "temperature", 0.0, "maxTokens", 512,
                "isActive", false, "proxyEnabled", false);
        JsonNode added = postJson("/api/model-config/add", payload);
        assertTrue(added.path("success").asBoolean(), () -> "add failed: " + added);
        JsonNode listed = getJson("/api/model-config/list");
        int configId = -1;
        for (JsonNode node : listed.path("data")) {
            if (SERVER.baseUrl().equals(node.path("baseUrl").asText())) {
                configId = node.path("id").asInt();
                assertFalse(node.path("apiKey").asText().contains(LOCAL_KEY),
                        "the list API must never return the raw key used for this probe");
            }
        }
        assertTrue(configId > 0, "the local model configuration was not persisted");
        assertTrue(postJson("/api/model-config/activate/" + configId, Map.of()).path("success").asBoolean());
    }

    @AfterAll
    static void stopServer() {
        SERVER.close();
    }

    @BeforeEach
    void resetState() {
        observations.reset();
        observations.limitModelCalls(12);
        observations.limitHttpAttempts(20);
        observations.limitRunHttpAttempts(60);
        // The run-wide counter and its flag deliberately survive reset(), so every expectation here
        // is expressed as a delta over the value this scenario started with.
        runBase = observations.runHttpAttempts();
        jdbcTemplate.update("DELETE FROM chainpilot_task_event");
        jdbcTemplate.update("DELETE FROM chainpilot_analysis_task");
    }

    @Test
    @Order(1)
    void fullToolLoopStaysInsideTheBudgetAndEveryStageMatchesOneRequest() {
        SERVER.reset(ScriptedOpenAiServer.Script.toolLoop());

        String taskId = submit();
        SupplyChainTaskStatus terminal = awaitTerminal(taskId);

        assertEquals("COMPLETED", terminal.status(), () -> "terminal step=" + terminal.currentStep());
        assertEquals(3, SERVER.requests(), "planner, tool-requesting report turn and result feedback");
        assertEquals(SERVER.requests(), observations.httpAttempts(),
                "every counted attempt must have been served, and vice versa");
        assertEquals(3, observations.modelOperations(), "each tool-calling turn is its own model stage");
        assertEquals(1, SERVER.served("feedback"),
                "the tool result must have been sent back to the model exactly once");
        assertFalse(observations.budgetExceeded());
        assertFalse(observations.httpBudgetExceeded());
        assertFalse(observations.runBudgetExceeded());
        assertEquals(3, observations.runHttpAttempts() - runBase,
                "the run budget must have advanced with the scenario");

        List<SupplyChainTaskEvent> events = events(taskId);
        assertTrue(toolEvents(events, SupplyChainEventType.TOOL_STARTED, "findInventory") == 1,
                "the model-initiated tool must have started once");
        assertTrue(toolEvents(events, SupplyChainEventType.TOOL_COMPLETED, "findInventory") == 1,
                "the real Java tool must have executed once and only once");
        assertTrue(events(events, SupplyChainEventType.MODEL_FAILED).isEmpty(), "no failure in the healthy loop");
        assertTrue(events(events, SupplyChainEventType.FALLBACK_USED).isEmpty());
        assertEquals(1, events(events, SupplyChainEventType.TASK_COMPLETED).size(),
                "exactly one terminal event for a healthy run");
        JsonNode report = getJson("/api/supply-chain/analysis/tasks/" + taskId + "/report");
        assertTrue(report.path("modelAssisted").asBoolean(), "the accepted narrative must be credited");
        assertTrue(report.path("toolCalls").toString().contains("findInventory"),
                () -> "report tool evidence missing: " + report.path("toolCalls"));
    }

    @Test
    @Order(2)
    void followUpRequestAfterTheToolResultIsBlockedBeforeItIsSent() {
        SERVER.reset(ScriptedOpenAiServer.Script.toolLoop());
        observations.limitHttpAttempts(2);

        String taskId = submit();
        SupplyChainTaskStatus terminal = awaitTerminal(taskId);

        assertEquals(3, observations.httpAttempts(), "the third turn is counted when it is attempted");
        assertEquals(2, SERVER.requests(), "the over-budget follow-up must never reach the endpoint");
        assertTrue(observations.httpBudgetExceeded());
        assertFalse(observations.runBudgetExceeded(), "the per-scenario ceiling is the binding limit here");
        assertEquals(3, observations.runHttpAttempts() - runBase,
                "a refused attempt still consumes the run-wide allowance");

        // The business layer catches the abort and degrades; it must not then keep spending.
        await().pollInterval(Duration.ofMillis(200)).atMost(Duration.ofSeconds(2)).untilAsserted(
                () -> assertEquals(2, SERVER.requests(), "no request may be sent after the block"));
        assertEquals("COMPLETED", terminal.status(),
                "the run finishes through the deterministic degradation path, which is the current contract");

        List<SupplyChainTaskEvent> events = events(taskId);
        JsonNode report = getJson("/api/supply-chain/analysis/tasks/" + taskId + "/report");
        assertFalse(report.path("modelAssisted").asBoolean(),
                "a blocked model turn may never be reported as model assistance");
        assertTrue(toolEvents(events, SupplyChainEventType.TOOL_STARTED, "findInventory") == 1,
                "the tool really executed before the budget stopped the feedback turn");
        assertFalse(events(events, SupplyChainEventType.MODEL_FAILED).isEmpty(),
                "the abort must be auditable as a model failure");
        assertFalse(events(events, SupplyChainEventType.FALLBACK_USED).isEmpty(),
                "the degradation must be recorded, not hidden");
        assertEquals(1, events(events, SupplyChainEventType.TASK_COMPLETED).size());
        assertTrue(events(events, SupplyChainEventType.TASK_FAILED).isEmpty());
        assertTrue(events(events, SupplyChainEventType.TASK_CANCELLED).isEmpty());
    }

    @Test
    @Order(4)
    void providerRetriesAndToolTurnsShareOneRunCeiling() {
        SERVER.reset(new ScriptedOpenAiServer.Script(List.of(ScriptedOpenAiServer.Action.PLAN),
                List.of(ScriptedOpenAiServer.Action.STATUS_500, ScriptedOpenAiServer.Action.STATUS_500,
                        ScriptedOpenAiServer.Action.TOOL_CALL),
                List.of(ScriptedOpenAiServer.Action.NARRATIVE)));
        observations.limitHttpAttempts(20);
        observations.limitRunHttpAttempts(runBase + 4);

        String taskId = submit();
        awaitTerminal(taskId);

        // One 5xx retry burst plus one tool follow-up turn must stay inside a single run ceiling.
        assertEquals(4, SERVER.requests(), "retransmissions and tool turns consume the same ceiling");
        assertEquals(5, observations.runHttpAttempts() - runBase, "the fifth attempt is counted then blocked");
        assertEquals(5, observations.httpAttempts(), "the refused turn is still counted at the boundary");
        assertTrue(observations.runBudgetExceeded());
        assertFalse(observations.httpBudgetExceeded(), "the per-scenario ceiling was not the binding limit");
        assertTrue(observations.modelOperations() < observations.runHttpAttempts() - runBase,
                () -> "stages (" + observations.modelOperations() + ") under-report requests ("
                        + (observations.runHttpAttempts() - runBase) + "), which is why both budgets are enforced");
    }

    @Test
    @Order(3)
    void rateLimitedReportIsRetriedOnlyByTheApplicationLayerWithThreeAttemptsTotal() {
        SERVER.reset(new ScriptedOpenAiServer.Script(List.of(ScriptedOpenAiServer.Action.PLAN),
                List.of(ScriptedOpenAiServer.Action.STATUS_429),
                List.of(ScriptedOpenAiServer.Action.NARRATIVE)));

        String taskId = submit();
        awaitTerminal(taskId);

        // 1 planner request + 3 application attempts. The provider client must not add its own
        // retries for 4xx, otherwise this number would be 1 + 3 x library attempts.
        assertEquals(4, SERVER.requests(), () -> "observed trail: " + SERVER.trail());
        assertEquals(4, observations.httpAttempts());
        assertEquals(4, observations.modelOperations(), "each application attempt is its own model stage");
        assertFalse(observations.httpBudgetExceeded());
        assertFalse(observations.budgetExceeded());

        List<SupplyChainTaskEvent> events = events(taskId);
        assertFalse(events(events, SupplyChainEventType.MODEL_FAILED).isEmpty());
        JsonNode report = getJson("/api/supply-chain/analysis/tasks/" + taskId + "/report");
        assertFalse(report.path("modelAssisted").asBoolean(), "a rate-limited turn is never model assistance");
    }

    @Test
    @Order(5)
    void runCeilingSurvivesThePerScenarioReset() {
        SERVER.reset(new ScriptedOpenAiServer.Script(List.of(ScriptedOpenAiServer.Action.PLAIN_TEXT),
                List.of(ScriptedOpenAiServer.Action.PLAIN_TEXT),
                List.of(ScriptedOpenAiServer.Action.PLAIN_TEXT)));
        observations.limitRunHttpAttempts(runBase + 1);
        observations.limitHttpAttempts(20);

        callModelOnce();
        assertEquals(1, observations.httpAttempts());
        assertEquals(1, observations.runHttpAttempts() - runBase);
        assertEquals(1, SERVER.requests());

        // Exactly what the acceptance run does between scenarios: reset the scenario state, keep the
        // run ceiling. The next request is refused even though the scenario counters restarted.
        observations.reset();
        observations.limitModelCalls(12);
        observations.limitHttpAttempts(20);
        callModelOnce();

        assertEquals(1, observations.httpAttempts(),
                "the refused attempt is recorded in the scenario trail even though nothing was sent");
        assertEquals(2, observations.runHttpAttempts() - runBase, "the run-wide counter must not reset");
        assertEquals(1, SERVER.requests(), "the second request was blocked before it was sent");
        assertTrue(observations.runBudgetExceeded(), "a run-wide breach must stay visible after a reset");
        assertFalse(observations.httpBudgetExceeded(), "only the scenario-scoped flags are cleared");
        assertFalse(observations.budgetExceeded());
    }

    @Test
    @Order(6)
    void budgetHarnessConfigurationCannotDriftFromTheAcceptanceRun() {
        SpringBootTest enabled = SupplyChainRealModelAcceptanceIT.class.getAnnotation(SpringBootTest.class);
        SpringBootTest tooling = RealModelToolCallBudgetTest.class.getAnnotation(SpringBootTest.class);
        assertEquals(List.of(enabled.classes()), List.of(tooling.classes()),
                "the tool-calling budget probe must boot the same bean graph as the real-model run");
        assertEquals(filtered(enabled.properties()), filtered(tooling.properties()),
                "the tool-calling budget probe must use the same context settings; only the database name differs");
    }

    private void callModelOnce() {
        try {
            aiModelRegistry.getChatClient()
                .prompt()
                .system("local budget probe")
                .user("reply with the single word ok")
                .call()
                .content();
        }
        catch (RuntimeException expected) {
            // The refusal surfaces here; the counters asserted by the caller are what matter.
        }
    }

    private String submit() {
        SupplyChainTaskStatus accepted = webTestClient.post()
            .uri("/api/supply-chain/analysis/tasks")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("question", QUESTION))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(SupplyChainTaskStatus.class)
            .returnResult()
            .getResponseBody();
        assertNotNull(accepted);
        return accepted.taskId();
    }

    private SupplyChainTaskStatus awaitTerminal(String taskId) {
        await().atMost(Duration.ofSeconds(150)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            SupplyChainTaskStatus status = status(taskId);
            assertTrue(List.of("COMPLETED", "FAILED", "CANCELLED").contains(status.status()),
                    () -> "task still " + status.status() + "/" + status.currentStep());
        });
        return status(taskId);
    }

    private SupplyChainTaskStatus status(String taskId) {
        SupplyChainTaskStatus status = webTestClient.get()
            .uri("/api/supply-chain/analysis/tasks/{taskId}", taskId)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(SupplyChainTaskStatus.class)
            .returnResult()
            .getResponseBody();
        assertNotNull(status);
        return status;
    }

    private List<SupplyChainTaskEvent> events(String taskId) {
        List<SupplyChainTaskEvent> events = webTestClient.get()
            .uri("/api/supply-chain/analysis/tasks/{taskId}/events", taskId)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBodyList(SupplyChainTaskEvent.class)
            .returnResult()
            .getResponseBody();
        assertNotNull(events);
        return events;
    }

    private static List<SupplyChainTaskEvent> events(List<SupplyChainTaskEvent> all, String type) {
        return all.stream().filter(event -> type.equals(event.type())).toList();
    }

    private static long toolEvents(List<SupplyChainTaskEvent> all, String type, String toolName) {
        return all.stream()
            .filter(event -> type.equals(event.type()))
            .filter(event -> toolName.equals(event.stage()))
            .filter(event -> event.summary() != null && event.summary().startsWith("model-initiated tool"))
            .count();
    }

    private JsonNode postJson(String path, Object body) {
        JsonNode response = webTestClient.post()
            .uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(JsonNode.class)
            .returnResult()
            .getResponseBody();
        assertNotNull(response, () -> "no response from " + path);
        return response;
    }

    private JsonNode getJson(String path) {
        JsonNode response = webTestClient.get()
            .uri(path)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(JsonNode.class)
            .returnResult()
            .getResponseBody();
        assertNotNull(response, () -> "no response from " + path);
        return response;
    }

    private static List<String> filtered(String[] properties) {
        return java.util.Arrays.stream(properties)
            .filter(property -> !property.startsWith("spring.datasource.url"))
            .toList();
    }

}
