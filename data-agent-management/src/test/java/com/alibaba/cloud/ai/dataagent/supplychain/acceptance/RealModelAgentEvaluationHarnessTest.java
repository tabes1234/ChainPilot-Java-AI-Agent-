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

import com.alibaba.cloud.ai.dataagent.evaluation.EvaluationCatalog;
import com.alibaba.cloud.ai.dataagent.evaluation.WorkflowEvaluationCase;
import com.alibaba.cloud.ai.dataagent.supplychain.ChainPilotTestApplication;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskAnalysisReport;
import com.alibaba.cloud.ai.dataagent.supplychain.service.SupplyChainWorkflowService;
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
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;

/**
 * Local rehearsal of the evaluation-centre real-model acceptance run. It drives the production
 * {@code POST /api/agent-evaluation/runs} entry point, the production AGENT case engine, the database
 * evaluation lease, the production assertion set and the outbound budgets, with the scripted loopback
 * endpoint standing in for the provider.
 *
 * <p>This is deliberately <strong>not</strong> real-model acceptance evidence: the responses are
 * scripted locally and cost nothing. What it proves is that the harness reads the same evidence the
 * authorised run will produce, and that a deterministic degradation can never be recorded as a passed
 * real-model case. The authorised entry
 * ({@link SupplyChainRealModelAgentEvaluationIT}) differs only by pointing the model configuration at
 * a real provider behind the explicit enable switch.
 */
@SpringBootTest(classes = { ChainPilotTestApplication.class,
        SupplyChainRealModelAgentEvaluationIT.RealModelAgentEvaluationConfiguration.class },
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:chainpilot_agent_harness;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;MODE=MySQL",
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
@Timeout(value = 4, unit = TimeUnit.MINUTES)
class RealModelAgentEvaluationHarnessTest {

    private static final String ANALYSIS_CASE = "WF-ANALYSIS-HIGH-RISK";

    private static final String LOCAL_KEY = "local-agent-harness-key-never-leaves-loopback";

    private static final ScriptedOpenAiServer SERVER = ScriptedOpenAiServer.start();

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private RecordingObservationHandler observations;

    /** Both are spies so a single scenario can stub a boundary; they are the injected production beans. */
    @MockitoSpyBean
    private SupplyChainWorkflowService workflow;

    @MockitoSpyBean
    private EvaluationCatalog catalog;

    private int configId;

    @BeforeAll
    void configureLoopbackModelThroughTheProductionApi() {
        // The rehearsal and the authorised provider run must never share rows with anything else, so the
        // same isolation guard the run relies on is exercised here on every ordinary test execution.
        RealModelAcceptanceIsolation.requireIsolated(liveJdbcUrl(), RealModelAcceptanceIsolation.HARNESS_DATABASE);

        jdbcTemplate.update("DELETE FROM chainpilot_evaluation_case_result");
        jdbcTemplate.update("DELETE FROM chainpilot_evaluation_run");
        jdbcTemplate.update("DELETE FROM chainpilot_task_event");
        jdbcTemplate.update("DELETE FROM chainpilot_analysis_task");
        jdbcTemplate.update("DELETE FROM model_config");

        Map<String, Object> payload = Map.of("provider", "custom", "apiKey", LOCAL_KEY, "baseUrl", SERVER.baseUrl(),
                "modelName", "local-agent-harness-model", "modelType", "CHAT", "temperature", 0.0, "maxTokens", 512,
                "isActive", false, "proxyEnabled", false);
        assertTrue(postJson("/api/model-config/add", payload).path("success").asBoolean());
        for (JsonNode node : getJson("/api/model-config/list").path("data")) {
            if (SERVER.baseUrl().equals(node.path("baseUrl").asText())) {
                configId = node.path("id").asInt();
                assertFalse(node.path("apiKey").asText().contains(LOCAL_KEY),
                        "the configuration API must never return the raw key");
            }
        }
        assertTrue(configId > 0, "the loopback model configuration was not persisted");
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
        observations.limitRunHttpAttempts(200);
        SERVER.reset(ScriptedOpenAiServer.Script.allNarrative());
        jdbcTemplate.update("DELETE FROM chainpilot_evaluation_case_result");
        jdbcTemplate.update("DELETE FROM chainpilot_evaluation_run");
        jdbcTemplate.update("DELETE FROM chainpilot_task_event");
        jdbcTemplate.update("DELETE FROM chainpilot_analysis_task");
    }

    /** Gate: an unverified configuration must block the AGENT run before any request is sent. */
    @Test
    @Order(1)
    void agentRunIsBlockedBeforeTheConnectionHasBeenVerifiedAndSendsNothing() {
        Started started = startRun(Map.of("caseIds", List.of(ANALYSIS_CASE), "mode", "AGENT", "confirmAgentRun",
                true));

        assertEquals(412, started.status().value(),
                () -> "expected the readiness pre-flight to refuse, got body " + started.body());
        assertEquals(0, SERVER.requests(), "no request may leave the process before verification");
        assertEquals(0, observations.httpAttempts());
        assertEquals(0, count("SELECT COUNT(*) FROM chainpilot_evaluation_run"),
                "a blocked start must not persist a run");
    }

    /** Gate: the cost confirmation flag is mandatory, and the refusal costs nothing either. */
    @Test
    @Order(2)
    void agentRunRequiresExplicitCostConfirmation() {
        Started started = startRun(Map.of("caseIds", List.of(ANALYSIS_CASE), "mode", "AGENT"));

        assertEquals(400, started.status().value(), () -> "expected a refusal, got " + started.body());
        assertEquals(0, SERVER.requests());
        assertEquals(0, observations.httpAttempts());
        assertEquals(0, count("SELECT COUNT(*) FROM chainpilot_evaluation_run"));
    }

    /** A case may pass only with credited model participation, not with a completed task alone. */
    @Test
    @Order(3)
    void verifiedModelParticipationMakesTheAgentCasePass() {
        verifyConnection();
        SERVER.reset(ScriptedOpenAiServer.Script.allNarrative());

        JsonNode finished = awaitTerminalRun(startRun(agentRunPayload(ANALYSIS_CASE)).runId());

        assertEquals("COMPLETED", finished.path("status").asText());
        assertEquals(1, finished.path("totalCases").asInt());
        assertEquals(1, finished.path("passed").asInt(), () -> "case verdicts: " + finished.path("results"));
        JsonNode caseResult = finished.path("results").get(0);
        assertEquals("PASSED", caseResult.path("status").asText());
        assertTrue(caseResult.path("taskId").asText().length() > 8, "the case must be linked to a real task");
        assertAssertion(caseResult, true, "report-model-assisted");
        assertAssertion(caseResult, true, "report-model-completed");
        assertAssertion(caseResult, true, "task-completed");
        assertAssertion(caseResult, true, "report-at-risk");
        assertAllDeclaredAssertionsRan(ANALYSIS_CASE, caseResult);

        String taskId = caseResult.path("taskId").asText();
        assertEquals("COMPLETED", jdbcTemplate.queryForObject(
                "SELECT status FROM chainpilot_analysis_task WHERE task_id=?", String.class, taskId));
        assertEquals(1, count("SELECT COUNT(*) FROM chainpilot_task_event WHERE task_id='" + taskId
                + "' AND event_type='MODEL_COMPLETED' AND status='COMPLETED' AND stage='GENERATING_REPORT'"),
                "the report stage must have completed successfully");
        assertEquals(1, count("SELECT COUNT(*) FROM chainpilot_task_event WHERE task_id='" + taskId
                + "' AND event_type='MODEL_COMPLETED' AND status='COMPLETED' AND stage='PLANNING'"),
                "the planner stage must have completed successfully");
        assertEquals(0, count("SELECT COUNT(*) FROM chainpilot_task_event WHERE task_id='" + taskId
                + "' AND event_type='FALLBACK_USED'"));
        String observation = jdbcTemplate.queryForObject(
                "SELECT observation_json FROM chainpilot_evaluation_case_result WHERE run_id=?", String.class,
                finished.path("runId").asText());
        assertTrue(observation.contains("\"modelAssisted\":true"),
                "the persisted observation must record the credited model: " + observation);
        assertLockReleased();
    }

    /** A provider failure keeps the business task usable but must fail the real-model case. */
    @Test
    @Order(4)
    void fullyDegradedRunFailsTheRealModelCaseAlthoughTheTaskCompleted() {
        verifyConnection();
        SERVER.reset(script(ScriptedOpenAiServer.Action.STATUS_401, ScriptedOpenAiServer.Action.STATUS_401));

        JsonNode finished = awaitTerminalRun(startRun(agentRunPayload(ANALYSIS_CASE)).runId());
        JsonNode caseResult = finished.path("results").get(0);

        assertEquals("FAILED", caseResult.path("status").asText(),
                "a degraded answer may never be recorded as real-model acceptance");
        assertEquals("ASSERTION_MISMATCH", caseResult.path("failureType").asText());
        assertAssertion(caseResult, false, "report-model-assisted");
        assertAssertion(caseResult, false, "report-model-completed");
        assertAssertion(caseResult, true, "report-exists", "the deterministic report must still exist");

        String taskId = caseResult.path("taskId").asText();
        assertEquals("COMPLETED", jdbcTemplate.queryForObject(
                "SELECT status FROM chainpilot_analysis_task WHERE task_id=?", String.class, taskId),
                "the deterministic degradation path must stay available to the business");
        assertEquals(0, count("SELECT COUNT(*) FROM chainpilot_task_event WHERE task_id='" + taskId
                + "' AND event_type='MODEL_COMPLETED' AND status='COMPLETED'"));
        assertTrue(count("SELECT COUNT(*) FROM chainpilot_task_event WHERE task_id='" + taskId
                + "' AND event_type='MODEL_FAILED'") >= 1, "the failure stays auditable");
        assertTrue(count("SELECT COUNT(*) FROM chainpilot_task_event WHERE task_id='" + taskId
                + "' AND event_type='FALLBACK_USED'") >= 1, "the degradation stays auditable");
        assertEquals(0, finished.path("passed").asInt());
        assertEquals(1, finished.path("failed").asInt());
        assertLockReleased();
    }

    /** A successful planner turn cannot excuse a report stage that never completed. */
    @Test
    @Order(5)
    void plannerSuccessCannotCompensateForAReportStageThatNeverCompleted() {
        verifyConnection();
        SERVER.reset(script(ScriptedOpenAiServer.Action.PLAN, ScriptedOpenAiServer.Action.STATUS_401));

        JsonNode caseResult = awaitTerminalRun(startRun(agentRunPayload(ANALYSIS_CASE)).runId()).path("results").get(0);
        String taskId = caseResult.path("taskId").asText();

        assertEquals(1, count("SELECT COUNT(*) FROM chainpilot_task_event WHERE task_id='" + taskId
                + "' AND event_type='MODEL_COMPLETED' AND status='COMPLETED' AND stage='PLANNING'"),
                "the planner stage really did succeed");
        assertEquals(0, count("SELECT COUNT(*) FROM chainpilot_task_event WHERE task_id='" + taskId
                + "' AND event_type='MODEL_COMPLETED' AND status='COMPLETED' AND stage='GENERATING_REPORT'"),
                "the report stage never completed");
        assertEquals("FAILED", caseResult.path("status").asText());
        assertAssertion(caseResult, false, "report-model-completed");
    }

    /** Credited model participation still cannot rescue a failed business contract. */
    @Test
    @Order(6)
    void creditedModelCannotRescueABrokenBusinessContract() {
        verifyConnection();
        SERVER.reset(ScriptedOpenAiServer.Script.allNarrative());
        doReturn(new RiskAnalysisReport("stubbed", "COMPLETED", "模型摘要", List.of("查询订单风险"), true, List.of(),
                List.of("确认恢复日期"), List.of(), List.of(), true, 1, Instant.now(), null,
                RiskAnalysisReport.NARRATIVE_MODEL_VERIFIED)).when(workflow).report(anyString());

        JsonNode caseResult = awaitTerminalRun(startRun(agentRunPayload(ANALYSIS_CASE)).runId()).path("results").get(0);

        assertEquals("FAILED", caseResult.path("status").asText());
        assertAssertion(caseResult, true, "report-model-assisted");
        assertAssertion(caseResult, true, "report-model-completed");
        assertAssertion(caseResult, false, "report-at-risk");
    }

    /** A declared assertion that no code path produces must be reported, never silently skipped. */
    @Test
    @Order(7)
    void declaredAssertionThatNeverRunsCannotBeIgnored() {
        verifyConnection();
        SERVER.reset(ScriptedOpenAiServer.Script.allNarrative());
        WorkflowEvaluationCase declared = catalog.workflowCase(ANALYSIS_CASE).orElseThrow();
        doReturn(Optional.of(new WorkflowEvaluationCase(declared.id(), declared.name(), declared.scenario(),
                declared.question(), declared.taskMode(), declared.expectedBehavior(), declared.assertions(),
                java.util.stream.Stream.concat(declared.requiredAssertionIds().stream(),
                        java.util.stream.Stream.of("report-model-completed-renamed")).toList())))
            .when(catalog).workflowCase(ANALYSIS_CASE);

        JsonNode caseResult = awaitTerminalRun(startRun(agentRunPayload(ANALYSIS_CASE)).runId()).path("results").get(0);

        assertEquals("FAILED", caseResult.path("status").asText());
        assertEquals("ASSERTION_NOT_EVALUATED", caseResult.path("failureType").asText());
        assertTrue(caseResult.path("failureMessage").asText().contains("report-model-completed-renamed"),
                () -> "got " + caseResult.path("failureMessage").asText());
    }

    /** OFFLINE semantics stay untouched: deterministic, no model, no outbound request at all. */
    @Test
    @Order(8)
    void offlineRunStaysDeterministicAndSendsNoRequest() {
        List<String> caseIds = offlineCaseIds();
        assertEquals(14, caseIds.size(), () -> "expected 14 deterministic cases, got " + caseIds);

        JsonNode finished = awaitTerminalRun(startRun(Map.of("caseIds", caseIds, "mode", "OFFLINE")).runId());

        assertEquals("COMPLETED", finished.path("status").asText());
        assertEquals(14, finished.path("passed").asInt(), () -> "verdicts: " + finished.path("results"));
        assertEquals(0, SERVER.requests(), "OFFLINE must not touch the model endpoint");
        assertEquals(0, observations.httpAttempts());
        String assertions = finished.path("results").get(0).path("assertions").toString();
        assertFalse(assertions.contains("model-assisted"),
                "deterministic cases must not start demanding model participation: " + assertions);
    }

    /** The run ceiling has to bind an AGENT run too, before the next request leaves the process. */
    @Test
    @Order(9)
    void runCeilingBlocksTheNextAgentRequestBeforeItIsSent() {
        verifyConnection();
        SERVER.reset(ScriptedOpenAiServer.Script.allNarrative());
        int baseAttempts = observations.runHttpAttempts();
        int baseRequests = SERVER.requests();
        observations.limitRunHttpAttempts(baseAttempts + 1);

        JsonNode caseResult = awaitTerminalRun(startRun(agentRunPayload(ANALYSIS_CASE)).runId()).path("results").get(0);

        assertEquals(baseRequests + 1, SERVER.requests(), "only the planner request may be served");
        assertEquals(2, observations.runHttpAttempts() - baseAttempts,
                "the refused attempt is counted at the boundary even though it was never sent");
        assertTrue(observations.runBudgetExceeded());
        assertFalse(observations.httpBudgetExceeded(), "the scenario ceiling was not the binding limit");
        assertEquals("FAILED", caseResult.path("status").asText(),
                "a budget-aborted stage degrades, and degradation fails the real-model case");
        assertAssertion(caseResult, false, "report-model-completed");
        assertLockReleased();
    }

    /** The written evidence must keep participation, degradation, verdicts and state apart. */
    @Test
    @Order(10)
    void evidenceSeparatesParticipationDegradationAndVerdicts() throws Exception {
        verifyConnection();
        SERVER.reset(ScriptedOpenAiServer.Script.allNarrative());
        String runId = startRun(agentRunPayload(ANALYSIS_CASE)).runId();
        JsonNode finished = awaitTerminalRun(runId);
        JsonNode caseResult = finished.path("results").get(0);
        String taskId = caseResult.path("taskId").asText();

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("harness.scope", "agent-evaluation-loopback");
        evidence.put("run.status", finished.path("status").asText());
        evidence.put("case.status", caseResult.path("status").asText());
        evidence.put("case.taskStatus", jdbcTemplate.queryForObject(
                "SELECT status FROM chainpilot_analysis_task WHERE task_id=?", String.class, taskId));
        evidence.put("case.modelStartedEvents", count("SELECT COUNT(*) FROM chainpilot_task_event WHERE task_id='"
                + taskId + "' AND event_type='MODEL_STARTED'"));
        evidence.put("case.successfulModelEvents", count("SELECT COUNT(*) FROM chainpilot_task_event WHERE task_id='"
                + taskId + "' AND event_type='MODEL_COMPLETED' AND status='COMPLETED'"));
        evidence.put("case.fallbackEvents", count("SELECT COUNT(*) FROM chainpilot_task_event WHERE task_id='" + taskId
                + "' AND event_type='FALLBACK_USED'"));
        evidence.put("case.modelAssisted", jdbcTemplate.queryForObject(
                "SELECT observation_json LIKE '%\"modelAssisted\":true%' FROM chainpilot_evaluation_case_result"
                        + " WHERE run_id=?", Boolean.class, runId));
        // Per-scenario ceilings only: the run-wide flag is sticky by design and was raised by an
        // earlier scenario in this JVM, which would make this row say the wrong thing.
        evidence.put("budget.withinCeiling",
                observations.httpBudgetExceeded() || observations.budgetExceeded() ? 0 : 1);
        evidence.put("lock.released", !lockHeld());

        Path written = RealModelAcceptanceEvidenceWriter.writeAgentEvidence(loopbackSettings(), evidence, runId,
                "PASSED".equals(caseResult.path("status").asText()));

        String markdown = Files.readString(written, StandardCharsets.UTF_8);
        assertTrue(markdown.contains("模型阶段成功 MODEL_COMPLETED(status=COMPLETED)"), markdown);
        assertTrue(markdown.contains("发生确定性降级 FALLBACK_USED"), markdown);
        assertTrue(markdown.contains("本地回环脚本桩"), markdown);
        assertFalse(markdown.contains(LOCAL_KEY), "the key must never reach the evidence file");
        // A rehearsal file must announce itself as one, so it can never be read as provider evidence.
        assertEquals("agent-harness-loopback-evidence.md", written.getFileName().toString());
        assertTrue(markdown.contains("不构成真实 Provider 验收证据"), markdown);
        String json = Files.readString(written.resolveSibling("agent-harness-loopback-evidence.json"),
                StandardCharsets.UTF_8);
        assertFalse(json.contains(LOCAL_KEY), "the key must never reach the evidence json");
        assertFalse(Files.exists(RealModelAcceptanceEvidenceWriter.OUTPUT_DIRECTORY
                .resolve("agent-real-model-acceptance-evidence.json")),
                "the loopback rehearsal must not write the real-provider evidence file");
        assertTrue(json.contains("\"concepts\""), json);
        // Participation, verdict and degradation must be readable as separate facts, not one "success".
        JsonNode evidenceJson = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        assertEquals("PASSED", evidenceJson.path("evidence").path("case.status").asText());
        assertEquals(2, evidenceJson.path("evidence").path("case.successfulModelEvents").asInt());
        assertEquals(0, evidenceJson.path("evidence").path("case.fallbackEvents").asInt());
        assertEquals("OBSERVED", evidenceJson.path("concepts").path("模型结果被采纳并参与输出").asText());
        assertEquals("OBSERVED", evidenceJson.path("concepts").path("模型阶段成功 MODEL_COMPLETED(status=COMPLETED)")
            .asText());
        assertEquals("NOT_OBSERVED", evidenceJson.path("concepts").path("发生确定性降级 FALLBACK_USED").asText());
        assertEquals("OBSERVED", evidenceJson.path("concepts").path("出站请求未超上限").asText());
        assertEquals("agent-evaluation-loopback", evidenceJson.path("run").path("scope").asText());
        assertFalse(evidenceJson.toString().contains(LOCAL_KEY), "no credential may reach the evidence tree");
    }

    /** Case eligibility comes from the case contract, so a guardrail or fallback case cannot inflate it. */
    @Test
    @Order(11)
    void onlyParticipationDeclaringCasesAreEligibleForRealModelAcceptance() {
        assertTrue(RealModelAgentCaseSelection.eligible(catalog, "WF-ANALYSIS-HIGH-RISK"));
        assertTrue(RealModelAgentCaseSelection.eligible(catalog, "WF-ANALYSIS-INVENTORY-SHORTAGE"));
        assertTrue(RealModelAgentCaseSelection.eligible(catalog, "WF-DECISION-COMPARE"));
        assertTrue(RealModelAgentCaseSelection.eligible(catalog, "WF-DECISION-EVIDENCE"));
        // Deterministic intent parsing, no model call: proving refusal hygiene, not model ability.
        assertFalse(RealModelAgentCaseSelection.eligible(catalog, "WF-DECISION-UNSUPPORTED"),
                "the guardrail case must never be counted as real-model acceptance");
        assertFalse(RealModelAgentCaseSelection.eligible(catalog, "REPORT-FALLBACK-NO-MODEL"),
                "the degradation case exists to prove fallback behaviour, not model ability");
        List<String> refused = RealModelAgentCaseSelection.rejected(catalog,
                List.of("WF-DECISION-UNSUPPORTED", "REPORT-FALLBACK-NO-MODEL", "ORDER-ORD-1001", "WF-NOT-A-CASE",
                        "WF-ANALYSIS-HIGH-RISK"));
        assertEquals(4, refused.size(), () -> "only the eligible case may be absent: " + refused);
        assertEquals("WF-DECISION-UNSUPPORTED (declares no model-participation assertion)", refused.get(0));
        assertTrue(refused.get(1).contains("not a workflow catalogue case"), refused.get(1));
        assertTrue(refused.get(2).contains("an OFFLINE case never calls the model"), refused.get(2));
        assertTrue(refused.get(3).contains("not a known evaluation case"), refused.get(3));
        assertEquals(0, SERVER.requests(), "eligibility is decided from the catalogue, without any request");
    }

    /**
     * The database guard is the only thing standing between a provider run and the user's daily demo
     * database, so its own semantics are tested here rather than trusted to a properties line.
     */
    @Test
    @Order(12)
    void databaseIsolationIsJudgedFromTheLiveUrlNotFromAFileName() {
        assertEquals("h2-mem", RealModelAcceptanceIsolation.kind("jdbc:h2:mem:something;DB_CLOSE_DELAY=-1"));
        assertEquals("h2-file", RealModelAcceptanceIsolation.kind("jdbc:h2:file:./data/nl2sql_database"));
        assertEquals("mysql", RealModelAcceptanceIsolation.kind("jdbc:mysql://127.0.0.1:3306/saa_data_agent"));
        assertEquals("<unrecognised jdbc url>", RealModelAcceptanceIsolation.kind("not-a-jdbc-url"));

        assertTrue(RealModelAcceptanceIsolation
                .problems("jdbc:h2:mem:" + RealModelAcceptanceIsolation.HARNESS_DATABASE + ";DB_CLOSE_DELAY=-1",
                        RealModelAcceptanceIsolation.HARNESS_DATABASE).isEmpty(),
                "the rehearsal must satisfy the same guard as the authorised run");

        List<String> demoFile = RealModelAcceptanceIsolation
                .problems("jdbc:h2:file:./data/nl2sql_database", RealModelAcceptanceIsolation.HARNESS_DATABASE);
        assertEquals(1, demoFile.size(), demoFile.toString());
        assertTrue(demoFile.get(0).contains("h2-file"), demoFile.get(0));
        assertEquals(1, RealModelAcceptanceIsolation
                .problems("jdbc:mysql://127.0.0.1:3306/saa_data_agent", RealModelAcceptanceIsolation.HARNESS_DATABASE)
                .size(), "a shared server database is refused too");
        assertEquals(1, RealModelAcceptanceIsolation.problems("jdbc:h2:mem:some_other_context",
                RealModelAcceptanceIsolation.HARNESS_DATABASE).size(),
                "another context's in-memory database is not the dedicated one");

        // A refusal may describe the kind of database, never the connection string: it can carry a host,
        // a path, or user and password parameters.
        String unsafeUrl = "jdbc:mysql://db.internal:3306/secret_schema?user=svc&password=hunter2";
        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> RealModelAcceptanceIsolation.requireIsolated(unsafeUrl,
                        RealModelAcceptanceIsolation.HARNESS_DATABASE));
        assertFalse(refusal.getMessage().contains("hunter2"), refusal.getMessage());
        assertFalse(refusal.getMessage().contains("db.internal"), refusal.getMessage());
        assertFalse(refusal.getMessage().contains("secret_schema"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("No external model request was made."), refusal.getMessage());

        assertEquals(0, SERVER.requests(), "isolation is decided from the connection, without any request");
    }

    private record Started(HttpStatusCode status, JsonNode body, String runId) {
    }

    private static ScriptedOpenAiServer.Script script(ScriptedOpenAiServer.Action planner,
            ScriptedOpenAiServer.Action report) {
        return new ScriptedOpenAiServer.Script(List.of(planner), List.of(report),
                List.of(ScriptedOpenAiServer.Action.NARRATIVE));
    }

    private static Map<String, Object> agentRunPayload(String caseId) {
        return Map.of("caseIds", List.of(caseId), "mode", "AGENT", "confirmAgentRun", true);
    }

    private void verifyConnection() {
        SERVER.reset(script(ScriptedOpenAiServer.Action.PLAN, ScriptedOpenAiServer.Action.NARRATIVE));
        int before = SERVER.requests();
        JsonNode tested = postJson("/api/model-config/test/" + configId, Map.of());
        assertTrue(tested.path("success").asBoolean(), () -> "loopback connection test failed: " + tested);
        assertEquals(before + 1, SERVER.requests(), "the connection test is itself one counted request");
        assertEquals("VERIFIED", getJson("/api/model-config/check-ready").path("data").path("chatState").asText());
        // Proven here so the authorised run can rely on it: the scope is published by the readiness
        // endpoint, and a run in another process cannot inherit it.
        assertEquals("CURRENT_BACKEND_PROCESS",
                getJson("/api/model-config/check-ready").path("data").path("verificationScope").asText());
    }

    private List<String> offlineCaseIds() {
        List<String> ids = new ArrayList<>();
        getJson("/api/agent-evaluation/cases").path("data").forEach(node -> {
            if ("OFFLINE".equals(node.path("mode").asText())) {
                ids.add(node.path("id").asText());
            }
        });
        return ids;
    }

    private JsonNode awaitTerminalRun(String runId) {
        await().atMost(Duration.ofSeconds(150)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            JsonNode run = getJson("/api/agent-evaluation/runs/" + runId).path("data");
            String status = run.path("status").asText();
            assertTrue(List.of("COMPLETED", "FAILED").contains(status), () -> "run still " + status);
        });
        return getJson("/api/agent-evaluation/runs/" + runId).path("data");
    }

    private void assertAllDeclaredAssertionsRan(String caseId, JsonNode caseResult) {
        List<String> declared = catalog.workflowCase(caseId).orElseThrow().requiredAssertionIds();
        List<String> executed = new ArrayList<>();
        caseResult.path("assertions").forEach(assertion -> executed.add(assertion.path("id").asText()));
        assertTrue(executed.containsAll(declared), () -> "declared " + declared + " but executed " + executed);
    }

    private static void assertAssertion(JsonNode caseResult, boolean expected, String id) {
        assertAssertion(caseResult, expected, id, null);
    }

    private static void assertAssertion(JsonNode caseResult, boolean expected, String id, String note) {
        for (JsonNode assertion : caseResult.path("assertions")) {
            if (id.equals(assertion.path("id").asText())) {
                String context = note == null ? "" : " (" + note + ")";
                if (expected) {
                    assertTrue(assertion.path("passed").asBoolean(),
                            () -> id + " should pass" + context + "; case: " + caseResult);
                }
                else {
                    assertFalse(assertion.path("passed").asBoolean(),
                            () -> id + " should fail" + context + "; case: " + caseResult);
                }
                return;
            }
        }
        throw new AssertionError("assertion " + id + " was not executed; case: " + caseResult);
    }

    private void assertLockReleased() {
        assertFalse(lockHeld(), "the evaluation lease must be released once the run is terminal");
        assertEquals(1, count("SELECT COUNT(*) FROM chainpilot_evaluation_lock WHERE lock_name='AGENT_EVALUATION'"
                + " AND owner_instance_id IS NULL AND run_id IS NULL"));
    }

    private boolean lockHeld() {
        return count("SELECT COUNT(*) FROM chainpilot_evaluation_lock WHERE lock_name='AGENT_EVALUATION'"
                + " AND owner_instance_id IS NOT NULL") > 0;
    }

    /** The URL of a real connection from the pool - the only evidence of where the writes actually go. */
    private String liveJdbcUrl() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getURL();
        }
        catch (SQLException exception) {
            throw new AssertionError("the harness cannot open a connection, so its database cannot be verified",
                    exception);
        }
    }

    private int count(String sql) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    private static RealModelAcceptanceSettings loopbackSettings() {
        return RealModelAcceptanceSettings.from(Map.of(RealModelAcceptanceSettings.ENABLE_PROPERTY, "true",
                RealModelAcceptanceSettings.PROVIDER_PROPERTY, "loopback",
                RealModelAcceptanceSettings.BASE_URL_PROPERTY, SERVER.baseUrl(),
                RealModelAcceptanceSettings.MODEL_NAME_PROPERTY, "local-agent-harness-model",
                RealModelAcceptanceSettings.API_KEY_PROPERTY, LOCAL_KEY));
    }

    private Started startRun(Map<String, Object> payload) {
        var result = webTestClient.post()
            .uri("/api/agent-evaluation/runs")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(payload)
            .exchange()
            .expectBody(JsonNode.class)
            .returnResult();
        JsonNode body = result.getResponseBody();
        assertNotNull(body, "no response body from the evaluation start endpoint");
        String runId = body.path("data").path("runId").asText(null);
        return new Started(result.getStatus(), body, runId);
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

}
