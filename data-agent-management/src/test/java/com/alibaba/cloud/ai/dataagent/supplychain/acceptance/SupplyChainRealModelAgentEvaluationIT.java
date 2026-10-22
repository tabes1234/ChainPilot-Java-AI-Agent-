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

import com.alibaba.cloud.ai.dataagent.controller.GlobalExceptionHandler;
import com.alibaba.cloud.ai.dataagent.evaluation.EvaluationCatalog;
import com.alibaba.cloud.ai.dataagent.evaluation.controller.AgentEvaluationController;
import com.alibaba.cloud.ai.dataagent.evaluation.dataset.SupplyChainEvaluationDatasetLoader;
import com.alibaba.cloud.ai.dataagent.evaluation.feedback.FeedbackRepository;
import com.alibaba.cloud.ai.dataagent.evaluation.repository.EvaluationRunRepository;
import com.alibaba.cloud.ai.dataagent.evaluation.service.AgentEvaluationService;
import com.alibaba.cloud.ai.dataagent.evaluation.service.EvaluationComparisonService;
import com.alibaba.cloud.ai.dataagent.evaluation.service.EvaluationLeaseManager;
import com.alibaba.cloud.ai.dataagent.evaluation.service.EvaluationMetricsCalculator;
import com.alibaba.cloud.ai.dataagent.supplychain.ChainPilotTestApplication;
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
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The authorised evaluation-centre AGENT acceptance run. It is the same entry point the evaluation
 * centre uses in production - {@code POST /api/agent-evaluation/runs} in AGENT mode, guarded by the
 * readiness pre-flight and the database lease - with the configured real provider behind it.
 *
 * <p>Three gates keep it dormant: the class name ends in {@code IT} so Surefire does not pick it up, the
 * whole class requires {@code -Dchainpilot.real-model.acceptance=true}, and {@code @BeforeAll} verifies
 * from the live connection that the run sits in its own in-memory database before it writes anything.
 * Model settings come from {@link RealModelAcceptanceSettings}; a missing or invalid value fails in
 * {@code @BeforeAll} before any request is built, and no external request is then made. The run is
 * bounded twice: model stages by {@code max-model-calls} and actual outbound requests by
 * {@code max-http-attempts} per case set and {@code max-run-http-attempts} for the whole run, all
 * enforced before a request leaves the process.
 *
 * <p>Nothing here may be satisfied by a mock, a fake or the loopback stub: the assertions demand a
 * credited model stage plus a successful model event, so a deterministic degradation completes the
 * business task yet fails the acceptance case. That distinction is the point of this class.
 */
@EnabledIfSystemProperty(named = RealModelAcceptanceSettings.ENABLE_PROPERTY, matches = "true")
@SpringBootTest(classes = { ChainPilotTestApplication.class,
        SupplyChainRealModelAgentEvaluationIT.RealModelAgentEvaluationConfiguration.class },
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:" + RealModelAcceptanceIsolation.AGENT_ACCEPTANCE_DATABASE
                        + ";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;MODE=MySQL",
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
@Timeout(value = 25, unit = TimeUnit.MINUTES)
class SupplyChainRealModelAgentEvaluationIT {

    @LocalServerPort
    private int port;

    private WebTestClient webTestClient;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private RecordingObservationHandler observations;

    @Autowired
    private EvaluationCatalog catalog;

    private final Map<String, Object> evidence = new LinkedHashMap<>();

    private RealModelAcceptanceSettings settings;

    private boolean preFlightAccepted;

    private int configId;

    private int runBase;

    @BeforeAll
    void configureRealModelWithoutCallingIt() {
        settings = RealModelAcceptanceSettings.fromSystem();
        if (!settings.problems().isEmpty()) {
            fail(settings.problemMessage());
        }
        List<String> rejected = RealModelAgentCaseSelection.rejected(catalog, settings.agentCases());
        assertTrue(rejected.isEmpty(),
                () -> "these cases cannot prove real-model participation and must not be selected for this run: "
                        + rejected);

        // Isolation has to be read off the connection this JVM actually opened. The properties below are
        // the intent; a profile, an environment override or a copy-paste could silently point the run at
        // the file-backed demo database, and by then the model configuration write would already have
        // changed the user's daily environment.
        String liveUrl = liveJdbcUrl();
        RealModelAcceptanceIsolation.requireIsolated(liveUrl,
                RealModelAcceptanceIsolation.AGENT_ACCEPTANCE_DATABASE);
        evidence.put("database.kind", RealModelAcceptanceIsolation.kind(liveUrl));
        evidence.put("database.isolated", true);

        // WebTestClient gives up after five seconds by default, and a real provider can take minutes to
        // answer a connection test. Left at the default, the client would report a harness failure for a
        // provider that is merely slow, so the ceiling follows the configured run window.
        assertTrue(port > 0, "the random test server port must be resolvable before the client is bound");
        webTestClient = WebTestClient.bindToServer()
            .baseUrl("http://127.0.0.1:" + port)
            .responseTimeout(Duration.ofSeconds(settings.timeoutSeconds() + 30L))
            .build();

        jdbcTemplate.update("DELETE FROM chainpilot_evaluation_case_result");
        jdbcTemplate.update("DELETE FROM chainpilot_evaluation_run");
        jdbcTemplate.update("DELETE FROM chainpilot_task_event");
        jdbcTemplate.update("DELETE FROM chainpilot_analysis_task");
        jdbcTemplate.update("DELETE FROM model_config");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("provider", settings.provider());
        payload.put("apiKey", settings.apiKey());
        payload.put("baseUrl", settings.baseUrl());
        payload.put("modelName", settings.modelName());
        payload.put("modelType", "CHAT");
        payload.put("temperature", 0.0);
        payload.put("maxTokens", settings.maxTokens());
        payload.put("isActive", false);
        payload.put("proxyEnabled", false);
        if (settings.completionsPath() != null) {
            payload.put("completionsPath", settings.completionsPath());
        }
        assertTrue(postJson("/api/model-config/add", payload).path("success").asBoolean());
        for (JsonNode node : getJson("/api/model-config/list").path("data")) {
            if (settings.baseUrl().equals(node.path("baseUrl").asText())) {
                configId = node.path("id").asInt();
                assertFalse(node.path("apiKey").asText().contains(settings.apiKey()),
                        "the configuration API must return a masked key, never the credential");
            }
        }
        assertTrue(configId > 0, "the real model configuration was not persisted");
        assertTrue(postJson("/api/model-config/activate/" + configId, Map.of()).path("success").asBoolean());

        // Seeing the write on the connection that was just verified is the isolation proof: this database
        // was emptied above, so it may contain nothing but the configuration this run created and activated.
        int configRows = count("SELECT COUNT(*) FROM model_config WHERE is_deleted=0");
        int activeChatRows = count(
                "SELECT COUNT(*) FROM model_config WHERE is_deleted=0 AND model_type='CHAT' AND is_active=1");
        assertEquals(1, configRows, "the isolated database must hold only the configuration this run wrote");
        assertEquals(1, activeChatRows, "the activated model must be the only active CHAT configuration here");
        evidence.put("database.modelConfigRows", configRows);
        evidence.put("database.activeChatRows", activeChatRows);

        evidence.put("target.provider", settings.provider());
        evidence.put("target.model", settings.modelName());
        evidence.put("target.endpoint", settings.endpointDescription());
        evidence.put("provider.external", settings.endpointIsExternal());
        evidence.put("target.agentCases", String.join(",", settings.agentCases()));
        evidence.put("target.maxModelCalls", settings.maxModelCalls());
        evidence.put("target.maxHttpAttemptsPerScenario", settings.maxHttpAttempts());
        evidence.put("target.maxRunHttpAttempts", settings.maxRunHttpAttempts());
        preFlightAccepted = true;
    }

    /**
     * Rejected configuration and rejected case selection abort in the pre-flight, before anything is
     * dialled. Those runs must not leave behind an evidence file that looks like a provider run.
     */
    @AfterAll
    void writeEvidence() throws Exception {
        if (settings == null || !settings.problems().isEmpty() || !preFlightAccepted) {
            return;
        }
        evidence.put("budget.withinCeiling",
                observations.budgetExceeded() || observations.httpBudgetExceeded() || observations.runBudgetExceeded()
                        ? 0 : 1);
        evidence.put("harness.scope", "agent-evaluation-real-provider");
        RealModelAcceptanceEvidenceWriter.writeAgentEvidence(settings, evidence, (String) evidence.get("run.id"),
                Boolean.TRUE.equals(evidence.get("accepted")));
    }

    @BeforeEach
    void armBudgets() {
        observations.reset();
        observations.limitModelCalls(settings.maxModelCalls());
        observations.limitHttpAttempts(settings.maxHttpAttempts());
        observations.limitRunHttpAttempts(settings.maxRunHttpAttempts());
        runBase = observations.runHttpAttempts();
    }

    /** The only permitted credential touch: one explicit connection test, counted in the budget. */
    @Test
    @Order(1)
    void connectionTestMakesReadinessVerifiableWithExactlyOneRequest() {
        JsonNode tested = postJson("/api/model-config/test/" + configId, Map.of());
        assertTrue(tested.path("success").asBoolean(),
                () -> "the configured model could not be verified: " + tested.path("message").asText());

        assertEquals("VERIFIED", getJson("/api/model-config/check-ready").path("data").path("chatState").asText());
        // A successful connectivity probe must be one stage and one outbound request: if the provider
        // client retransmitted, that belongs in the evidence rather than being absorbed silently.
        assertEquals(1, observations.modelOperations(), "the connection test is one model stage");
        assertEquals(1, observations.httpAttempts(), "the connection test sent exactly one request");
        assertFalse(observations.budgetExceeded());
        assertFalse(observations.httpBudgetExceeded());
        evidence.put("connection.verified", true);
        evidence.put("connection.requests", observations.httpAttempts());
    }

    /** The authorised run itself: an AGENT case passes only on credited model participation. */
    @Test
    @Order(2)
    void realModelAgentRunPassesOnlyOnCreditedModelParticipation() {
        JsonNode started = postJson("/api/agent-evaluation/runs", Map.of("caseIds", settings.agentCases(),
                "mode", "AGENT", "confirmAgentRun", true));
        assertTrue(started.path("success").asBoolean(), () -> "start refused: " + started.path("message").asText());
        String runId = started.path("data").path("runId").asText();
        evidence.put("run.id", runId);

        JsonNode finished = awaitTerminalRun(runId);
        List<JsonNode> cases = new java.util.ArrayList<>();
        finished.path("results").forEach(cases::add);
        assertEquals(settings.agentCases().size(), finished.path("totalCases").asInt());

        int modelStarted = 0;
        int modelSucceeded = 0;
        int degraded = 0;
        for (JsonNode caseResult : cases) {
            String caseId = caseResult.path("caseId").asText();
            String taskId = caseResult.path("taskId").asText();
            assertEquals("PASSED", caseResult.path("status").asText(),
                    () -> caseId + " verdict " + caseResult.path("status").asText() + " / "
                            + caseResult.path("failureType").asText() + " " + caseResult.path("failureMessage").asText());
            assertAllDeclaredAssertionsRan(caseId, caseResult);
            RealModelAgentCaseSelection.PARTICIPATION_ASSERTIONS.stream()
                .filter(id -> declares(caseId, id))
                .forEach(id -> assertAssertion(caseResult, id));
            assertTrue(taskId.length() > 8, () -> caseId + " is not linked to a real task");
            assertEquals("COMPLETED", jdbcTemplate.queryForObject(
                    "SELECT status FROM chainpilot_analysis_task WHERE task_id=?", String.class, taskId));
            assertEquals(0, count("SELECT COUNT(*) FROM chainpilot_task_event WHERE task_id='" + taskId
                    + "' AND event_type='FALLBACK_USED'"),
                    "a real-model acceptance case may not carry a deterministic degradation");
            assertTrue(count("SELECT COUNT(*) FROM chainpilot_task_event WHERE task_id='" + taskId
                    + "' AND event_type='MODEL_COMPLETED' AND status='COMPLETED'") >= 1,
                    "the provider stage must have completed successfully");
            assertTrue(jdbcTemplate.queryForObject(
                    "SELECT observation_json FROM chainpilot_evaluation_case_result WHERE run_id=? AND case_id=?",
                    String.class, runId, caseId).contains("\"modelAssisted\":true"));
            modelStarted += count("SELECT COUNT(*) FROM chainpilot_task_event WHERE task_id='" + taskId
                    + "' AND event_type='MODEL_STARTED'");
            modelSucceeded += count("SELECT COUNT(*) FROM chainpilot_task_event WHERE task_id='" + taskId
                    + "' AND event_type='MODEL_COMPLETED' AND status='COMPLETED'");
            degraded += count("SELECT COUNT(*) FROM chainpilot_task_event WHERE task_id='" + taskId
                    + "' AND event_type='FALLBACK_USED'");
        }

        assertEquals(0, finished.path("failed").asInt(), () -> "results: " + finished.path("results"));
        assertEquals("COMPLETED", finished.path("status").asText());
        assertEquals(0, count("SELECT COUNT(*) FROM chainpilot_evaluation_lock WHERE owner_instance_id IS NOT NULL"),
                "the terminal write must have released the evaluation lease");
        int used = observations.runHttpAttempts() - runBase;
        assertTrue(used <= settings.maxHttpAttempts(), () -> "scenario used " + used + " requests");
        assertFalse(observations.httpBudgetExceeded());
        assertFalse(observations.runBudgetExceeded());
        assertFalse(observations.budgetExceeded());

        evidence.put("run.status", finished.path("status").asText());
        evidence.put("run.passed", finished.path("passed").asInt());
        evidence.put("run.failed", finished.path("failed").asInt());
        evidence.put("run.undetermined", finished.path("undetermined").asInt());
        evidence.put("case.count", cases.size());
        evidence.put("case.status", "PASSED");
        evidence.put("case.taskStatus", "COMPLETED");
        evidence.put("case.modelAssisted", true);
        evidence.put("case.modelStartedEvents", modelStarted);
        evidence.put("case.successfulModelEvents", modelSucceeded);
        evidence.put("case.fallbackEvents", degraded);
        evidence.put("lock.released", true);
        evidence.put("requests.modelOperations", observations.modelOperations());
        evidence.put("requests.outbound", used);
        evidence.put("accepted", true);
    }

    /** Read-only proof that the guards the run relies on are the ones actually in force. */
    @Test
    @Order(3)
    void capabilitiesReportTheGuardsInForce() {
        JsonNode capabilities = getJson("/api/agent-evaluation/capabilities").path("data");
        JsonNode readiness = getJson("/api/model-config/check-ready").path("data");

        assertEquals("DATABASE_LEASE", capabilities.path("agentRunLockScope").asText());
        assertEquals(5, capabilities.path("maxAgentCasesPerRun").asInt());
        assertEquals(20, capabilities.path("agentTimeoutMinutes").asInt());
        assertEquals("VERIFIED", capabilities.path("chatState").asText());
        // The verification scope is published by the readiness endpoint only; the evaluation capabilities
        // payload has never carried it, so asserting it there would fail on a field that does not exist.
        assertEquals("CURRENT_BACKEND_PROCESS", readiness.path("verificationScope").asText());
        assertFalse(capabilities.path("agentRunActive").asBoolean(),
                "a finished acceptance run must not leave the lease held");
        evidence.put("guards.lockScope", capabilities.path("agentRunLockScope").asText());
        evidence.put("guards.verificationScope", readiness.path("verificationScope").asText());
    }

    private boolean declares(String caseId, String assertionId) {
        return catalog.workflowCase(caseId).map(declared -> declared.requiresAssertion(assertionId)).orElse(false);
    }

    private JsonNode awaitTerminalRun(String runId) {
        await().atMost(Duration.ofSeconds(Math.min(settings.timeoutSeconds() * 4L, 1200L)))
            .pollInterval(Duration.ofSeconds(2))
            .untilAsserted(() -> {
                JsonNode run = getJson("/api/agent-evaluation/runs/" + runId).path("data");
                String status = run.path("status").asText();
                assertTrue(List.of("COMPLETED", "FAILED").contains(status), () -> "run still " + status);
            });
        return getJson("/api/agent-evaluation/runs/" + runId).path("data");
    }

    private void assertAllDeclaredAssertionsRan(String caseId, JsonNode caseResult) {
        List<String> declared = catalog.workflowCase(caseId).orElseThrow().requiredAssertionIds();
        List<String> executed = new java.util.ArrayList<>();
        caseResult.path("assertions").forEach(assertion -> executed.add(assertion.path("id").asText()));
        assertTrue(executed.containsAll(declared), () -> caseId + " declared " + declared + " but ran " + executed);
    }

    private static void assertAssertion(JsonNode caseResult, String id) {
        for (JsonNode assertion : caseResult.path("assertions")) {
            if (id.equals(assertion.path("id").asText())) {
                assertTrue(assertion.path("passed").asBoolean(),
                        () -> id + " must pass for real-model acceptance; case: " + caseResult);
                return;
            }
        }
        throw new AssertionError("required assertion " + id + " never ran; case: " + caseResult);
    }

    private int count(String sql) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    /** The URL of a real connection taken from the pool, which is the only proof of where writes go. */
    private String liveJdbcUrl() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getURL();
        }
        catch (SQLException exception) {
            throw new AssertionError("the acceptance run cannot open a connection, so its database cannot be "
                    + "verified; no external model request was made", exception);
        }
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

    /**
     * The bean graph the evaluation-centre real-model run needs, built by reusing the existing
     * model-configuration acceptance wiring instead of duplicating it.
     *
     * <p>{@link com.alibaba.cloud.ai.dataagent.supplychain.ChainPilotTestApplication} component-scans the
     * whole supply-chain test package, so this configuration has to stay a nested class: a top-level
     * {@code @Configuration} here would be picked up by every unrelated supply-chain HTTP context and
     * register a second {@code AiModelRegistry} next to those contexts' own disabled-model bean.
     *
     * <p>Everything on the model side - configuration API, registry, dynamic factory, readiness service
     * and the recording observation handler with its outbound budgets - comes from
     * {@link SupplyChainRealModelAcceptanceIT.RealModelAcceptanceConfiguration}, the same graph the
     * single-task real-model IT boots. The evaluation beans are the production ones; nothing here is
     * mocked, so an authorised run exercises the same start gate, database lease, assertion engine and
     * persistence the deployed application uses. {@link GlobalExceptionHandler} is the advice that turns
     * a blocked start into the deployed 412/409/400 instead of a generic 500.
     */
    @Configuration(proxyBeanMethods = false)
    @Import({ SupplyChainRealModelAcceptanceIT.RealModelAcceptanceConfiguration.class,
            SupplyChainEvaluationDatasetLoader.class, EvaluationCatalog.class, EvaluationRunRepository.class,
            EvaluationMetricsCalculator.class, EvaluationComparisonService.class, FeedbackRepository.class,
            EvaluationLeaseManager.class, AgentEvaluationService.class, AgentEvaluationController.class,
            GlobalExceptionHandler.class })
    static class RealModelAgentEvaluationConfiguration {
    }

}
