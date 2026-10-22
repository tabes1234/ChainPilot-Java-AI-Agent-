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

import com.alibaba.cloud.ai.dataagent.controller.ModelConfigController;
import com.alibaba.cloud.ai.dataagent.dto.ModelConfigDTO;
import com.alibaba.cloud.ai.dataagent.enums.ModelType;
import com.alibaba.cloud.ai.dataagent.mapper.ModelConfigMapper;
import com.alibaba.cloud.ai.dataagent.properties.DataAgentProperties;
import com.alibaba.cloud.ai.dataagent.service.aimodelconfig.AiModelRegistry;
import com.alibaba.cloud.ai.dataagent.service.aimodelconfig.DynamicModelFactory;
import com.alibaba.cloud.ai.dataagent.service.aimodelconfig.EmbeddingModelCompatibilityValidator;
import com.alibaba.cloud.ai.dataagent.service.aimodelconfig.ModelConfigDataService;
import com.alibaba.cloud.ai.dataagent.service.aimodelconfig.ModelConfigDataServiceImpl;
import com.alibaba.cloud.ai.dataagent.service.aimodelconfig.ModelConfigOpsService;
import com.alibaba.cloud.ai.dataagent.service.aimodelconfig.ModelReadinessService;
import com.alibaba.cloud.ai.dataagent.supplychain.ChainPilotTestApplication;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskAnalysisReport;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskLevel;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainEventType;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskEvent;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskSummary;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.extension.TestWatcher;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Opt-in end-to-end acceptance against the configured real chat model. This class is not a
 * default Surefire test: it is an IT, requires the explicit enable flag, and creates its
 * model configuration through the existing model-config API and database.
 *
 * <p>The run makes a bounded number of paid model calls. It never substitutes the local
 * fake model. If the configured model has no native Tool Calling support, the Tool Calling
 * test fails and records the limitation instead of fabricating a successful call.
 */
@EnabledIfSystemProperty(named = RealModelAcceptanceSettings.ENABLE_PROPERTY, matches = "true")
@SpringBootTest(classes = { ChainPilotTestApplication.class,
        SupplyChainRealModelAcceptanceIT.RealModelAcceptanceConfiguration.class },
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:chainpilot_real_model_acceptance;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;MODE=MySQL",
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
@Timeout(value = 10, unit = TimeUnit.MINUTES)
class SupplyChainRealModelAcceptanceIT {

    private static final Set<String> EXPECTED_AT_RISK_ORDER_IDS = Set.of("ORD-1001", "ORD-1002", "ORD-1004");

    private static final Set<String> KNOWN_ORDER_IDS = Set.of("ORD-1001", "ORD-1002", "ORD-1003", "ORD-1004");

    private static final Set<String> KNOWN_TOOL_NAMES = Set.of("findAtRiskOrders", "findInventory", "findSuppliers",
            "findDisposalRecommendations", "simulateInventoryReplenishment", "simulateSupplierDelay");

    private static final Pattern ORDER_ID_PATTERN = Pattern.compile("ORD-\\d{4}");

    private static final String QUESTION_MARKER = "结合库存和供应商风险";

    private static final String BASIC_PROMPT_MARKER = "Confirm that the configured chat model is reachable.";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ModelConfigDataService modelConfigDataService;

    @Autowired
    private AiModelRegistry aiModelRegistry;

    @Autowired
    private RecordingObservationHandler observations;

    private final Map<String, Object> evidence = new LinkedHashMap<>();

    private final Set<String> executedTests = new LinkedHashSet<>();

    private final Set<String> failedTests = new LinkedHashSet<>();

    @RegisterExtension
    final TestWatcher testResults = new TestWatcher() {
        @Override
        public void testFailed(ExtensionContext context, Throwable cause) {
            failedTests.add(context.getRequiredTestMethod().getName());
        }
    };

    private RealModelAcceptanceSettings settings;

    private int realConfigId;

    private LocalFailureServer fallbackFailureServer;

    @BeforeAll
    void setUp() throws IOException {
        settings = RealModelAcceptanceSettings.fromSystem();
        if (!settings.problems().isEmpty()) {
            fail(settings.problemMessage());
        }
        fallbackFailureServer = LocalFailureServer.start();

        clearDatabase();
        configureRealModelThroughExistingApi();

        evidence.put("target.provider", settings.provider());
        evidence.put("target.model", settings.modelName());
        evidence.put("target.endpoint", settings.endpointDescription());
        evidence.put("target.maxTokens", settings.maxTokens());
        evidence.put("target.timeoutSeconds", settings.timeoutSeconds());
        evidence.put("target.maxModelCalls", settings.maxModelCalls());
        evidence.put("target.maxHttpAttemptsPerScenario", settings.maxHttpAttempts());
        evidence.put("target.maxRunHttpAttempts", settings.maxRunHttpAttempts());
        evidence.put("config.activeChatConfigId", realConfigId);
        evidence.put("safety.hardModelCallCap", settings.maxModelCalls());
        // The run-wide ceiling survives every per-scenario reset, so the whole acceptance run
        // cannot exceed it no matter how many scenarios are executed.
        observations.limitRunHttpAttempts(settings.maxRunHttpAttempts());
    }

    @BeforeEach
    void resetObservations(TestInfo testInfo) {
        executedTests.add(testInfo.getTestMethod().map(method -> method.getName()).orElse("unknown"));
        observations.reset();
        observations.limitModelCalls(settings.maxModelCalls());
        observations.limitHttpAttempts(settings.maxHttpAttempts());
    }

    @AfterEach
    void assertModelCallBudgetWasNotExceeded() {
        assertFalse(observations.budgetExceeded(),
                () -> "The scenario attempted more model stages than the configured hard cap of "
                        + settings.maxModelCalls() + " and aborted the extra request. Investigate a possible "
                        + "tool-calling loop before raising -Dchainpilot.real-model.max-model-calls.");
        assertFalse(observations.httpBudgetExceeded(),
                () -> "The scenario attempted more outbound HTTP requests than the configured hard cap of "
                        + settings.maxHttpAttempts() + " and blocked the next request before it was sent. "
                        + "Provider-side retries (any 5xx or a connection/read timeout is retried up to 10 times "
                        + "per stage by Spring AI's default retry template) are counted here even though they are "
                        + "not separate model stages. Investigate the retry storm before raising "
                        + "-Dchainpilot.real-model.max-http-attempts.");
        assertFalse(observations.runBudgetExceeded(),
                () -> "The whole acceptance run exceeded its outbound request ceiling of "
                        + settings.maxRunHttpAttempts() + " (observed attempts: " + observations.runHttpAttempts()
                        + "). This budget is never reset between scenarios, so the run must stop here instead of "
                        + "continuing to spend.");
    }

    @AfterEach
    void recordModelCallDiagnostics(TestInfo testInfo) {
        String testName = testInfo.getTestMethod().map(java.lang.reflect.Method::getName).orElse("unknown");
        evidence.put("diagnostics." + testName + ".modelCalls", modelCallTrail());
        evidence.put("diagnostics." + testName + ".toolObservations", toolObservationTrail());
        evidence.put("diagnostics." + testName + ".httpAttempts", observations.httpAttempts());
        evidence.put("diagnostics." + testName + ".runHttpAttempts", observations.runHttpAttempts());
    }

    @AfterAll
    void writeEvidence() throws IOException {
        try {
            if (settings != null && settings.problems().isEmpty() && !executedTests.isEmpty()) {
                RealModelAcceptanceEvidenceWriter.write(settings, evidence, List.copyOf(executedTests),
                        executedTests.size() == 4 && failedTests.isEmpty());
            }
        }
        finally {
            if (fallbackFailureServer != null) {
                fallbackFailureServer.stop();
            }
        }
    }

    @Test
    @Order(1)
    void configuredRealChatModelAnswersThroughTheActiveConfiguration() {
        String answer;
        try {
            answer = aiModelRegistry.getChatClient()
                .prompt()
                .system("You are a connectivity check. Reply with one short, neutral sentence.")
                .user(BASIC_PROMPT_MARKER)
                .call()
                .content();
        }
        catch (RuntimeException exception) {
            fail("The configured real chat model could not be called. Check provider, base URL, model name, "
                    + "credential and quota. cause=" + exception.getClass().getSimpleName());
            return;
        }

        assertTrue(StringUtils.hasText(answer), "The configured real chat model returned empty content.");
        long modelOperations = observationsNamed("gen_ai.client.operation").size();
        long httpRequests = observationsNamed("http.client.requests").size();
        assertTrue(modelOperations >= 1,
                () -> "No gen_ai.client.operation observation was recorded; the request was not observed as a model call");
        assertTrue(httpRequests >= 1,
                () -> "No HTTP client observation was recorded; the configured endpoint was not reached");
        assertTrue(modelOperations <= settings.maxModelCalls(),
                () -> "Observed " + modelOperations + " model calls, above the configured bound "
                        + settings.maxModelCalls());
        assertNoSensitiveObservationAttributes();

        evidence.put("basic.contentNonBlank", true);
        evidence.put("basic.answerChars", answer.length());
        evidence.put("basic.modelOperations", modelOperations);
        evidence.put("basic.httpClientObservations", httpRequests);
        evidence.put("observability.modelOperations", modelOperations);
        evidence.put("observability.httpRequests", httpRequests);
        evidence.put("observability.noSecretOrPromptAttributes", true);
    }

    @Test
    @Order(2)
    void realModelProducesPersistedReportFromBusinessData() {
        String taskId = submit("分析今天有哪些订单可能延期，结合库存和供应商风险，给出处理建议。");
        SupplyChainTaskStatus terminal = awaitTerminal(taskId);
        assertEquals("COMPLETED", terminal.status());

        RiskAnalysisReport report = report(taskId);
        List<SupplyChainTaskEvent> events = events(taskId);
        Set<String> atRiskOrderIds = report.atRiskOrders()
            .stream()
            .map(assessment -> assessment.order().orderId())
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> mentionedOrderIds = mentionedOrderIds(report.summary());
        Set<String> unknownOrderIds = new LinkedHashSet<>(mentionedOrderIds);
        unknownOrderIds.removeAll(KNOWN_ORDER_IDS);
        long planningCompleted = count(events, SupplyChainEventType.MODEL_COMPLETED, "PLANNING");
        long reportCompleted = count(events, SupplyChainEventType.MODEL_COMPLETED, "GENERATING_REPORT");
        long modelFailed = count(events, SupplyChainEventType.MODEL_FAILED);
        long fallbackUsed = count(events, SupplyChainEventType.FALLBACK_USED);
        long modelOperations = observationsNamed("gen_ai.client.operation").size();
        long httpRequests = observationsNamed("http.client.requests").size();
        boolean historyReportAvailable = history().stream()
            .anyMatch(item -> taskId.equals(item.taskId()) && item.reportAvailable());

        evidence.put("report.taskCompleted", true);
        evidence.put("report.persisted", true);
        evidence.put("report.plannerAssisted", report.plannerAssisted());
        evidence.put("report.modelAssisted", report.modelAssisted());
        evidence.put("report.executionPlanSteps", report.executionPlan().size());
        evidence.put("report.atRiskOrderIds", String.join(",", atRiskOrderIds));
        evidence.put("report.atRiskOrdersMatch", EXPECTED_AT_RISK_ORDER_IDS.equals(atRiskOrderIds));
        evidence.put("report.citations", report.citations().size());
        evidence.put("report.toolCalls", String.join(",", report.toolCalls()));
        evidence.put("report.modelCompletedEvents", planningCompleted + reportCompleted);
        evidence.put("report.modelFailedEvents", modelFailed);
        evidence.put("report.fallbackEvents", fallbackUsed);
        evidence.put("report.flagsRecorded", report.plannerAssisted() && report.modelAssisted());
        evidence.put("report.eventCountsConsistent",
                planningCompleted == 1 && reportCompleted == 1 && modelFailed == 0 && fallbackUsed == 0);
        evidence.put("report.noUnknownOrderIds", unknownOrderIds.isEmpty());
        evidence.put("report.historyReportAvailable", historyReportAvailable);
        evidence.put("observability.modelOperations", modelOperations);
        evidence.put("observability.httpRequests", httpRequests);
        evidence.put("observability.noSecretOrPromptAttributes", true);
        evidence.put("observability.modelCallDiagnostics", modelCallTrail());

        assertAll("real-model report",
                () -> assertTrue(report.plannerAssisted(),
                        () -> "plannerAssisted=false after a configured real-model run; model calls="
                                + modelCallTrail() + "; events=" + eventTrail(events)),
                () -> assertTrue(report.modelAssisted(),
                        () -> "modelAssisted=false after a configured real-model run; model calls="
                                + modelCallTrail() + "; events=" + eventTrail(events) + diagnosticHint()),
                () -> assertEquals(1, planningCompleted,
                        () -> "Expected one completed planner model event; events=" + eventTrail(events)),
                () -> assertEquals(1, reportCompleted,
                        () -> "Expected one completed report model event; events=" + eventTrail(events)),
                () -> assertEquals(0, modelFailed, () -> "Model failures recorded: " + eventTrail(events)),
                () -> assertEquals(0, fallbackUsed, () -> "Fallbacks recorded: " + eventTrail(events)),
                () -> assertFalse(anyModelCallHitLengthLimit(),
                        () -> "At least one model call finished with finish_reason=length; the output budget, "
                                + "including reasoning tokens, was exhausted. model calls=" + modelCallTrail()),
                () -> assertTrue(StringUtils.hasText(report.summary()), "The report summary is blank"),
                () -> assertFalse(report.executionPlan().isEmpty(), "The execution plan is empty"),
                () -> assertFalse(report.recommendations().isEmpty(), "The recommendations are empty"),
                () -> assertFalse(report.citations().isEmpty(), "The knowledge citations are empty"),
                () -> assertEquals(EXPECTED_AT_RISK_ORDER_IDS, atRiskOrderIds,
                        () -> "At-risk orders differ from the deterministic business data: " + atRiskOrderIds),
                () -> assertTrue(report.atRiskOrders().stream()
                    .allMatch(assessment -> assessment.riskLevel() != RiskLevel.LOW),
                        "Every at-risk order must be MEDIUM or HIGH"),
                () -> assertTrue(report.atRiskOrders().stream()
                    .allMatch(assessment -> !assessment.riskFactors().isEmpty()
                            && !assessment.recommendedActions().isEmpty()),
                        "Every at-risk order must carry risk factors and recommendations"),
                () -> assertTrue(unknownOrderIds.isEmpty(),
                        () -> "The model summary mentions order IDs outside the demo data: " + unknownOrderIds),
                () -> assertToolCallEvidenceIsTruthful(report, events),
                () -> assertTrue(historyReportAvailable,
                        "The completed report was not available through the persisted history API"),
                () -> assertTrue(modelOperations >= 2,
                        () -> "Expected at least planner and report model operations, observed " + modelOperations
                                + "; model calls=" + modelCallTrail()),
                () -> assertTrue(httpRequests >= 2,
                        () -> "Expected at least two HTTP model requests, observed " + httpRequests),
                () -> assertTrue(modelOperations <= settings.maxModelCalls(),
                        () -> "Observed " + modelOperations + " model calls, above the configured bound "
                                + settings.maxModelCalls()),
                this::assertNoSensitiveObservationAttributes);
    }

    @Test
    @Order(3)
    void realModelInitiatesToolCallThatExecutesAndFeedsBack() {
        String taskId = submit("请先调用 findInventory 工具查询 SKU-SENSOR-002 的可承诺库存，"
                + "然后只根据工具返回结果回答：SKU-SENSOR-002 的可承诺数量是多少？请用阿拉伯数字回答。");
        SupplyChainTaskStatus terminal = awaitTerminal(taskId);
        assertEquals("COMPLETED", terminal.status());

        RiskAnalysisReport report = report(taskId);
        List<SupplyChainTaskEvent> events = events(taskId);

        long findInventoryCompleted = events.stream()
            .filter(event -> SupplyChainEventType.TOOL_COMPLETED.equals(event.type()))
            .filter(event -> "findInventory".equals(event.stage()))
            .filter(event -> event.summary() != null && event.summary().startsWith("model-initiated tool;"))
            .count();
        long findInventoryFailed = events.stream()
            .filter(event -> SupplyChainEventType.TOOL_FAILED.equals(event.type()))
            .filter(event -> "findInventory".equals(event.stage()))
            .filter(event -> event.summary() != null && event.summary().startsWith("model-initiated tool;"))
            .count();
        Set<String> atRiskOrderIds = report.atRiskOrders()
            .stream()
            .map(assessment -> assessment.order().orderId())
            .collect(Collectors.toCollection(LinkedHashSet::new));
        long modelOperations = observationsNamed("gen_ai.client.operation").size();
        long httpRequests = observationsNamed("http.client.requests").size();
        long toolObservations = observationsNamed("spring.ai.tool").size();
        boolean toolObservationNoError = observationsNamed("spring.ai.tool").stream()
            .allMatch(record -> record.errorType() == null);
        boolean toolResultFedBack = observationsNamed("gen_ai.client.operation").stream()
            .map(ObservationRecord::model)
            .filter(Objects::nonNull)
            .anyMatch(ChatModelResponseDiagnostics.ModelResponseDiagnostics::toolResultMatchesEarlierCall);
        boolean toolResultNonBlank = observationsNamed("spring.ai.tool").stream()
            .map(ObservationRecord::tool)
            .filter(Objects::nonNull)
            .anyMatch(diagnostics -> diagnostics.resultPresent() && !diagnostics.resultBlank());
        boolean summaryContainsToolValue = report.summary().contains("25");

        evidence.put("tooling.modelInitiatedFindInventory", report.toolCalls().contains("findInventory"));
        evidence.put("tooling.findInventoryCompletedEvents", findInventoryCompleted);
        evidence.put("tooling.findInventoryFailedEvents", findInventoryFailed);
        evidence.put("tooling.followUpModelTurns", modelOperations);
        evidence.put("tooling.summaryContainsToolValue", summaryContainsToolValue);
        evidence.put("tooling.toolResultFedBackToModel", toolResultFedBack);
        evidence.put("tooling.toolResultNonBlank", toolResultNonBlank);
        evidence.put("tooling.requestedTools", String.join(",", report.toolCalls()));
        evidence.put("tooling.toolObservations", toolObservations);
        evidence.put("tooling.modelCallDiagnostics", modelCallTrail());
        evidence.put("tooling.toolObservationDiagnostics", toolObservationTrail());
        evidence.put("observability.modelOperations", modelOperations);
        evidence.put("observability.httpRequests", httpRequests);
        evidence.put("observability.toolObservations", toolObservations);
        evidence.put("observability.noSecretOrPromptAttributes", true);

        assertAll("real-model tool calling",
                () -> assertTrue(report.plannerAssisted(),
                        () -> "plannerAssisted=false after a configured real-model run; model calls="
                                + modelCallTrail() + "; events=" + eventTrail(events)),
                () -> assertTrue(report.modelAssisted(),
                        () -> "modelAssisted=false after a configured real-model run; model calls="
                                + modelCallTrail() + "; events=" + eventTrail(events) + diagnosticHint()),
                () -> assertTrue(report.toolCalls().contains("findInventory"),
                        () -> "The configured model did not request findInventory. Native Tool Calling is NOT verified "
                                + "for this model/provider. report.toolCalls=" + report.toolCalls()
                                + "; model calls=" + modelCallTrail() + "; events=" + eventTrail(events)),
                () -> assertTrue(findInventoryCompleted >= 1,
                        () -> "No model-initiated findInventory completion event was recorded; events="
                                + eventTrail(events)),
                () -> assertEquals(0, findInventoryFailed, "The model-initiated findInventory call failed"),
                () -> assertToolCallEvidenceIsTruthful(report, events),
                () -> assertFalse(anyModelCallHitLengthLimit(),
                        () -> "At least one model call finished with finish_reason=length; the output budget, "
                                + "including reasoning tokens, was exhausted. model calls=" + modelCallTrail()),
                () -> assertTrue(summaryContainsToolValue,
                        () -> "The model answer did not use the tool-confirmed available quantity 25; summary chars="
                                + report.summary().length()),
                () -> assertEquals(EXPECTED_AT_RISK_ORDER_IDS, atRiskOrderIds,
                        () -> "At-risk orders differ from the deterministic business data: " + atRiskOrderIds),
                () -> assertTrue(modelOperations >= 3,
                        () -> "Expected planner turn, tool-requesting report turn and a follow-up model turn after the "
                                + "tool result; observed " + modelOperations + "; model calls=" + modelCallTrail()),
                () -> assertTrue(httpRequests >= 3,
                        () -> "Expected at least three HTTP model requests including the follow-up after the tool "
                                + "result; observed " + httpRequests),
                () -> assertTrue(toolObservations >= 1,
                        () -> "No spring.ai.tool observation was recorded for the model-initiated tool call"),
                () -> assertTrue(toolObservationNoError, "The native tool observation reports an error"),
                () -> assertTrue(toolResultNonBlank,
                        () -> "The model-initiated tool completed without a non-blank result; tool observations="
                                + toolObservationTrail()),
                () -> assertTrue(toolResultFedBack,
                        () -> "No follow-up model request contained a tool response whose id matched an earlier "
                                + "model-requested tool call; model calls=" + modelCallTrail()
                                + "; tool observations=" + toolObservationTrail()),
                () -> assertTrue(modelOperations <= settings.maxModelCalls(),
                        () -> "Observed " + modelOperations + " model calls, above the configured bound "
                                + settings.maxModelCalls()),
                this::assertNoSensitiveObservationAttributes);
    }

    @Test
    @Order(4)
    void realHttpFailureFallsBackToDeterministicReportWithoutClaimingModelAssistance() {
        int failingConfigId = addFailingChatConfig();
        String taskId;
        try {
            activate(failingConfigId);
            taskId = submit("分析今天有哪些订单可能延期，结合库存和供应商风险，给出处理建议。");
            SupplyChainTaskStatus terminal = awaitTerminal(taskId, Duration.ofSeconds(settings.timeoutSeconds()));
            assertEquals("COMPLETED", terminal.status());

            RiskAnalysisReport report = report(taskId);
            List<SupplyChainTaskEvent> events = events(taskId);

            assertTrue(fallbackFailureServer.requestCount() >= 2,
                    "Expected the planner and report model calls to reach the failing endpoint; requests="
                            + fallbackFailureServer.requestCount());
            assertEquals("/v1/chat/completions", fallbackFailureServer.lastPath(),
                    "The failing endpoint did not receive an OpenAI-compatible chat completion request");
            assertFalse(report.plannerAssisted(), "A failed planner must not be marked as model-assisted");
            assertFalse(report.modelAssisted(), "A failed report model call must not be marked as model-assisted");
            assertTrue(report.summary().contains("发现 3 个存在风险的订单"),
                    "The deterministic fallback summary was not used");
            assertEquals(List.of(), report.toolCalls(),
                    "A model that never completed a tool call must not claim tool calls");
            assertEquals(EXPECTED_AT_RISK_ORDER_IDS, report.atRiskOrders()
                .stream()
                .map(assessment -> assessment.order().orderId())
                .collect(Collectors.toCollection(LinkedHashSet::new)));

            assertEquals(2, count(events, SupplyChainEventType.MODEL_FAILED));
            assertEquals(2, count(events, SupplyChainEventType.FALLBACK_USED));
            assertEquals(0, count(events, SupplyChainEventType.MODEL_COMPLETED));
            assertEquals(1, count(events, SupplyChainEventType.TASK_COMPLETED));

            evidence.put("fallback.taskStatus", terminal.status());
            evidence.put("fallback.localHttpRequests", fallbackFailureServer.requestCount());
            evidence.put("fallback.httpStatus", 401);
            evidence.put("fallback.plannerAssisted", report.plannerAssisted());
            evidence.put("fallback.modelAssisted", report.modelAssisted());
            evidence.put("fallback.modelFailedEvents", count(events, SupplyChainEventType.MODEL_FAILED));
            evidence.put("fallback.fallbackEvents", count(events, SupplyChainEventType.FALLBACK_USED));
            evidence.put("fallback.flagsAndEventsRecorded", true);
        }
        finally {
            activate(realConfigId);
        }
    }

    private void configureRealModelThroughExistingApi() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("provider", settings.provider());
        payload.put("apiKey", settings.apiKey());
        payload.put("baseUrl", settings.baseUrl());
        payload.put("modelName", settings.modelName());
        payload.put("modelType", ModelType.CHAT.getCode());
        payload.put("temperature", 0.0);
        payload.put("maxTokens", settings.maxTokens());
        payload.put("isActive", false);
        payload.put("proxyEnabled", false);
        if (settings.completionsPath() != null) {
            payload.put("completionsPath", settings.completionsPath());
        }

        assertSuccess(postJson("/api/model-config/add", payload), "add");
        Integer configId = findConfigId(settings.provider(), settings.modelName(), settings.baseUrl());
        assertNotNull(configId, "The real model configuration was not persisted");
        activate(configId);
        realConfigId = configId;

        ModelConfigDTO active = modelConfigDataService.getActiveConfigByType(ModelType.CHAT);
        assertNotNull(active, "The real model configuration was not activated");
        assertEquals(settings.provider(), active.getProvider());
        assertEquals(settings.baseUrl(), active.getBaseUrl());
        assertEquals(settings.modelName(), active.getModelName());

        JsonNode listed = configList().stream()
            .filter(node -> node.path("id").asInt() == configId)
            .findFirst()
            .orElseThrow(() -> new AssertionError("The activated configuration is missing from the list API"));
        String masked = listed.path("apiKey").asText();
        assertNotEquals(settings.apiKey(), masked, "The model list API must not return the raw API key");
        assertFalse(masked.contains(settings.apiKey()), "The model list API must not return the raw API key");
        assertTrue(masked.contains("****"), "The model list API must return a masked API key");

        evidence.put("config.apiAddAndActivate", true);
        evidence.put("config.maskedInApi", true);
    }

    private int addFailingChatConfig() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("provider", "custom");
        payload.put("apiKey", "acceptance-fallback-" + UUID.randomUUID());
        payload.put("baseUrl", fallbackFailureServer.baseUrl());
        payload.put("modelName", "failing-real-model-acceptance");
        payload.put("modelType", ModelType.CHAT.getCode());
        payload.put("temperature", 0.0);
        payload.put("maxTokens", 64);
        payload.put("isActive", false);
        payload.put("proxyEnabled", false);
        assertSuccess(postJson("/api/model-config/add", payload), "add fallback");
        Integer configId = findConfigId("custom", "failing-real-model-acceptance",
                fallbackFailureServer.baseUrl());
        assertNotNull(configId, "The fallback configuration was not persisted");
        return configId;
    }

    private void activate(int configId) {
        assertSuccess(postJson("/api/model-config/activate/" + configId, Map.of()), "activate");
    }

    private Integer findConfigId(String provider, String modelName, String baseUrl) {
        for (JsonNode node : configList()) {
            if (provider.equals(node.path("provider").asText())
                    && modelName.equals(node.path("modelName").asText())
                    && baseUrl.equals(node.path("baseUrl").asText())) {
                return node.path("id").asInt();
            }
        }
        return null;
    }

    private List<JsonNode> configList() {
        JsonNode response = getJson("/api/model-config/list");
        assertSuccess(response, "list");
        List<JsonNode> configs = new ArrayList<>();
        response.path("data").forEach(configs::add);
        return configs;
    }

    private void clearDatabase() {
        jdbcTemplate.update("DELETE FROM chainpilot_task_event");
        jdbcTemplate.update("DELETE FROM chainpilot_analysis_task");
        jdbcTemplate.update("DELETE FROM model_config");
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
        assertNotNull(response, () -> "No JSON response from " + path);
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
        assertNotNull(response, () -> "No JSON response from " + path);
        return response;
    }

    private static void assertSuccess(JsonNode response, String action) {
        assertNotNull(response, () -> "Model config " + action + " returned no response");
        assertTrue(response.path("success").asBoolean(),
                () -> "Model config " + action + " failed: " + response.path("message").asText());
    }

    private String submit(String question) {
        SupplyChainTaskStatus accepted = webTestClient.post()
            .uri("/api/supply-chain/analysis/tasks")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("question", question))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(SupplyChainTaskStatus.class)
            .returnResult()
            .getResponseBody();
        assertNotNull(accepted);
        assertNotNull(accepted.taskId());
        return accepted.taskId();
    }

    private SupplyChainTaskStatus awaitTerminal(String taskId) {
        return awaitTerminal(taskId, Duration.ofSeconds(settings.timeoutSeconds()));
    }

    private SupplyChainTaskStatus awaitTerminal(String taskId, Duration timeout) {
        await().atMost(timeout).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            SupplyChainTaskStatus status = status(taskId);
            assertTrue(Set.of("COMPLETED", "FAILED").contains(status.status()),
                    () -> "Task " + taskId + " is still " + status.status() + "/" + status.currentStep());
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

    private RiskAnalysisReport report(String taskId) {
        RiskAnalysisReport report = webTestClient.get()
            .uri("/api/supply-chain/analysis/tasks/{taskId}/report", taskId)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(RiskAnalysisReport.class)
            .returnResult()
            .getResponseBody();
        assertNotNull(report);
        return report;
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

    private List<SupplyChainTaskSummary> history() {
        EntityExchangeResult<List<SupplyChainTaskSummary>> result = webTestClient.get()
            .uri("/api/supply-chain/analysis/tasks?page=1&pageSize=50")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBodyList(SupplyChainTaskSummary.class)
            .returnResult();
        List<SupplyChainTaskSummary> items = result.getResponseBody();
        assertNotNull(items);
        return items;
    }

    private long count(List<SupplyChainTaskEvent> events, String type) {
        return events.stream().filter(event -> type.equals(event.type())).count();
    }

    private long count(List<SupplyChainTaskEvent> events, String type, String stage) {
        return events.stream()
            .filter(event -> type.equals(event.type()))
            .filter(event -> stage.equals(event.stage()))
            .count();
    }

    private List<ObservationRecord> observationsNamed(String name) {
        return observations.all().stream().filter(record -> name.equals(record.name())).toList();
    }

    private String modelCallTrail() {
        return observationsNamed("gen_ai.client.operation").stream()
            .map(record -> record.model() == null ? "no-chat-context" : record.model().summary())
            .collect(Collectors.joining(" | "));
    }

    private String toolObservationTrail() {
        return observationsNamed("spring.ai.tool").stream()
            .map(record -> record.tool() == null ? "no-tool-context" : record.tool().summary())
            .collect(Collectors.joining(" | "));
    }

    private String diagnosticHint() {
        return anyModelCallHitLengthLimit()
                ? "; finish_reason=length means the output budget, including reasoning tokens, was exhausted "
                        + "before final content"
                : "";
    }

    private boolean anyModelCallHitLengthLimit() {
        return observationsNamed("gen_ai.client.operation").stream()
            .map(ObservationRecord::model)
            .filter(Objects::nonNull)
            .anyMatch(model -> "length".equals(model.finishReason()));
    }

    private void assertToolCallEvidenceIsTruthful(RiskAnalysisReport report, List<SupplyChainTaskEvent> events) {
        Set<String> modelInitiatedStarted = events.stream()
            .filter(event -> SupplyChainEventType.TOOL_STARTED.equals(event.type()))
            .filter(event -> event.summary() != null && event.summary().startsWith("model-initiated tool"))
            .map(SupplyChainTaskEvent::stage)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> modelInitiatedCompleted = events.stream()
            .filter(event -> SupplyChainEventType.TOOL_COMPLETED.equals(event.type()))
            .filter(event -> event.summary() != null && event.summary().startsWith("model-initiated tool"))
            .map(SupplyChainTaskEvent::stage)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> modelInitiatedFailed = events.stream()
            .filter(event -> SupplyChainEventType.TOOL_FAILED.equals(event.type()))
            .filter(event -> event.summary() != null && event.summary().startsWith("model-initiated tool"))
            .map(SupplyChainTaskEvent::stage)
            .collect(Collectors.toCollection(LinkedHashSet::new));

        assertTrue(KNOWN_TOOL_NAMES.containsAll(report.toolCalls()),
                () -> "Report claims unknown model tool calls: " + report.toolCalls());
        assertTrue(modelInitiatedStarted.containsAll(report.toolCalls()),
                () -> "Report toolCalls contain names without model-initiated TOOL_STARTED evidence: "
                        + report.toolCalls() + " vs " + modelInitiatedStarted);
        assertTrue(report.toolCalls().containsAll(modelInitiatedCompleted),
                () -> "A model-initiated tool completed without appearing in report.toolCalls: "
                        + modelInitiatedCompleted + " vs " + report.toolCalls());
        assertTrue(report.toolCalls().containsAll(modelInitiatedFailed),
                () -> "A model-initiated tool failed without appearing in report.toolCalls: "
                        + modelInitiatedFailed + " vs " + report.toolCalls());
    }

    private static Set<String> mentionedOrderIds(String summary) {
        Set<String> ids = new LinkedHashSet<>();
        Matcher matcher = ORDER_ID_PATTERN.matcher(summary);
        while (matcher.find()) {
            ids.add(matcher.group());
        }
        return ids;
    }

    private void assertNoSensitiveObservationAttributes() {
        List<String> values = observations.all()
            .stream()
            .flatMap(record -> record.attributes().values().stream())
            .filter(StringUtils::hasText)
            .toList();
        assertFalse(values.stream().anyMatch(value -> value.contains(settings.apiKey())),
                "The API key must not be exported as an observation attribute");
        assertFalse(values.stream().anyMatch(value -> value.contains(QUESTION_MARKER)),
                "Prompt content must not be exported as an observation attribute");
        assertFalse(values.stream().anyMatch(value -> value.contains(BASIC_PROMPT_MARKER)),
                "Prompt content must not be exported as an observation attribute");
    }

    private static String eventTrail(List<SupplyChainTaskEvent> events) {
        return events.stream()
            .map(event -> event.type() + "/" + event.stage())
            .collect(Collectors.joining(", "));
    }

    /**
     * Loopback endpoint that answers every chat-completion request with a non-retryable 401.
     * This proves the fallback path without a paid call, connection-refusal retries or an
     * external dependency.
     */
    private static final class LocalFailureServer {

        private final HttpServer server;

        private final ExecutorService executor;

        private final AtomicInteger requests = new AtomicInteger();

        private volatile String lastPath;

        private LocalFailureServer(HttpServer server, ExecutorService executor) {
            this.server = server;
            this.executor = executor;
        }

        static LocalFailureServer start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "chainpilot-acceptance-failure-server");
                thread.setDaemon(true);
                return thread;
            });
            LocalFailureServer failureServer = new LocalFailureServer(server, executor);
            server.createContext("/v1/chat/completions", failureServer::handle);
            server.setExecutor(executor);
            server.start();
            return failureServer;
        }

        private void handle(HttpExchange exchange) throws IOException {
            requests.incrementAndGet();
            lastPath = exchange.getRequestURI().getPath();
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"error\":{\"message\":\"acceptance fallback endpoint\"}}"
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(401, body.length);
            try (OutputStream stream = exchange.getResponseBody()) {
                stream.write(body);
            }
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        int requestCount() {
            return requests.get();
        }

        String lastPath() {
            return lastPath;
        }

        void stop() {
            server.stop(0);
            executor.shutdownNow();
        }

    }

    @TestConfiguration(proxyBeanMethods = false)
    @MapperScan(basePackageClasses = ModelConfigMapper.class)
    @Import({ ModelConfigDataServiceImpl.class, ModelConfigOpsService.class, ModelConfigController.class,
            DynamicModelFactory.class, AiModelRegistry.class, EmbeddingModelCompatibilityValidator.class,
            ModelReadinessService.class })
    static class RealModelAcceptanceConfiguration {

        @Bean
        DataAgentProperties dataAgentProperties() {
            return new DataAgentProperties();
        }

        @Bean
        RecordingObservationHandler recordingObservationHandler(ObservationRegistry observationRegistry) {
            RecordingObservationHandler handler = new RecordingObservationHandler();
            observationRegistry.observationConfig().observationHandler(handler);
            return handler;
        }

    }

}
