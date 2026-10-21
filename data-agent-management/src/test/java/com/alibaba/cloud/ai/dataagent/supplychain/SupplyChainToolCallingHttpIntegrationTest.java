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
package com.alibaba.cloud.ai.dataagent.supplychain;

import com.alibaba.cloud.ai.dataagent.dto.ModelConfigDTO;
import com.alibaba.cloud.ai.dataagent.enums.ModelType;
import com.alibaba.cloud.ai.dataagent.service.aimodelconfig.AiModelRegistry;
import com.alibaba.cloud.ai.dataagent.service.aimodelconfig.DynamicModelFactory;
import com.alibaba.cloud.ai.dataagent.service.aimodelconfig.ModelConfigDataService;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioCandidate;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioComparison;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioComparisonPage;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioRanking;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskAnalysisReport;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainEventType;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskEvent;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.service.SupplyChainQueryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.ObservationView;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Exercises the real Spring AI tool-calling loop against a loopback OpenAI-compatible server.
 * The fake model only decides the protocol responses; tool execution, result serialization and
 * feedback happen inside Spring AI's {@code ChatClient} and the production Java tool.
 */
@SpringBootTest(classes = { ChainPilotTestApplication.class,
        SupplyChainToolCallingHttpIntegrationTest.LocalFakeModelConfiguration.class },
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:chainpilot_tool_calling_test;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;MODE=MySQL",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.sql.init.mode=never",
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.reactive.ReactiveSecurityAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.security.reactive.ReactiveUserDetailsServiceAutoConfiguration",
                "logging.level.root=warn",
                "logging.level.com.alibaba.cloud.ai.dataagent.supplychain=info" })
class SupplyChainToolCallingHttpIntegrationTest {

    private static final Duration TERMINAL_TIMEOUT = Duration.ofSeconds(30);

    private static final String TOOL_CALL_ID = "call_chainpilot_inventory_1";

    private static final String SKU = "SKU-MCU-001";

    private static final String FAILING_SKU = "SKU-FAIL-001";

    private static final LocalOpenAiServer FAKE_MODEL = LocalOpenAiServer.start();

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RecordingObservationHandler observations;

    @MockitoSpyBean
    private SupplyChainQueryService queryService;

    @BeforeEach
    void resetState() {
        FAKE_MODEL.reset(Mode.TOOL_LOOP);
        observations.reset();
        jdbcTemplate.update("DELETE FROM chainpilot_task_event");
        jdbcTemplate.update("DELETE FROM chainpilot_analysis_task");
        jdbcTemplate.update("DELETE FROM chainpilot_disposal_execution");
        jdbcTemplate.update("DELETE FROM chainpilot_disposal_comparison_candidate");
        jdbcTemplate.update("DELETE FROM chainpilot_disposal_comparison");
        jdbcTemplate.update("DELETE FROM chainpilot_disposal_plan");
        jdbcTemplate.update("DELETE FROM chainpilot_disposal_simulation");
    }

    @AfterAll
    static void stopFakeModel() {
        FAKE_MODEL.stop();
    }

    @DynamicPropertySource
    static void fakeModelProperties(DynamicPropertyRegistry registry) {
        registry.add("chainpilot.fake-model.base-url", FAKE_MODEL::baseUrl);
    }

    @Test
    void modelToolCallRunsTheRealJavaToolAndItsResultIsFedBackToTheModel() throws Exception {
        String taskId = submit("分析 SKU-MCU-001 的库存风险");

        SupplyChainTaskStatus completed = awaitTerminal(taskId);
        assertEquals("COMPLETED", completed.status());

        List<RecordedRequest> requests = FAKE_MODEL.requests();
        assertEquals(3, requests.size(), "expected planner, tool-call and tool-result requests");
        assertFalse(requests.get(0).hasTools(), "planner request must not expose tools");
        Set<String> exposedTools = requests.get(1).toolNames();
        assertEquals(Set.of("findAtRiskOrders", "findInventory", "findSuppliers", "findDisposalRecommendations",
                "simulateInventoryReplenishment", "simulateSupplierDelay", "checkSandboxExecutionEligibility",
                "findSandboxExecution", "findSandboxExecutions", "compareDisposalScenarios",
                "compareSupplierDelayScenarios", "findScenarioComparison", "findDisposalPlan"), exposedTools);
        assertFalse(exposedTools.contains("startSandboxExecution"),
                "write actions must never be exposed as model tools");
        assertEquals(0, executionRows(), "a read-only analysis must not create sandbox execution records");

        List<RecordedRequest> reportRequests = requests.stream().filter(RecordedRequest::hasTools).toList();
        assertEquals(2, reportRequests.size());
        assertEquals(0, reportRequests.get(0).toolMessages().size());
        JsonNode toolResultMessage = reportRequests.get(1).toolMessages().get(0);
        assertEquals(TOOL_CALL_ID, toolResultMessage.path("tool_call_id").asText());

        JsonNode toolResult = new ObjectMapper().readTree(toolResultMessage.path("content").asText());
        assertEquals(SKU, toolResult.get(0).path("sku").asText());
        assertEquals(20, toolResult.get(0).path("availableToPromise").asInt(),
                "Spring AI must serialize the real Java tool result");
        assertTrue(requests.get(2).body().toString().contains(TOOL_CALL_ID),
                "the follow-up request must reference the original tool call");

        RiskAnalysisReport report = report(taskId);
        assertEquals("LOCAL_TOOL_LOOP_OK sku=" + SKU + " available=20", report.summary());
        assertTrue(report.plannerAssisted());
        assertTrue(report.modelAssisted());
        assertEquals(List.of("findInventory"), report.toolCalls());
        assertEquals(List.of("查询订单延期风险", "核对库存与可承诺量", "检查供应商履约风险"), report.executionPlan());
        assertFalse(report.atRiskOrders().isEmpty());

        List<SupplyChainTaskEvent> events = events(taskId);
        assertEquals(1, count(events, SupplyChainEventType.TOOL_COMPLETED, "findInventory", "model-initiated"));
        assertEquals(1, count(events, SupplyChainEventType.TOOL_COMPLETED, "findInventory", "workflow"));
        assertEquals(3, events.stream()
            .filter(event -> SupplyChainEventType.TOOL_COMPLETED.equals(event.type()))
            .filter(event -> event.summary().startsWith("workflow tool;"))
            .count());
        assertEquals(2, events.stream().filter(event -> SupplyChainEventType.MODEL_COMPLETED.equals(event.type())).count());
        assertEquals(2, events.stream().filter(event -> SupplyChainEventType.MODEL_STARTED.equals(event.type())).count());
        List<SupplyChainTaskEvent> completedModelEvents = events.stream()
            .filter(event -> SupplyChainEventType.MODEL_COMPLETED.equals(event.type()))
            .toList();
        assertTrue(completedModelEvents.get(0).summary().contains("tokens prompt=1, completion=1, total=2"),
            "planner usage must come from the provider response: " + completedModelEvents.get(0).summary());
        assertTrue(completedModelEvents.get(1).summary().contains("tokens prompt=2, completion=2, total=4"),
            "report usage must include both provider turns: " + completedModelEvents.get(1).summary());
        assertFalse(events.stream().anyMatch(event -> SupplyChainEventType.MODEL_FAILED.equals(event.type())));
        assertFalse(events.stream().anyMatch(event -> SupplyChainEventType.FALLBACK_USED.equals(event.type())));
        assertFalse(events.stream().anyMatch(event -> event.summary() != null
                && event.summary().contains("local-test-key")));

        assertEquals(2, recordsNamed("spring.ai.chat.client").size(), "planner and report client calls");
        assertEquals(2, recordsNamed("spring.ai.advisor").size(), "one advisor chain per client call");
        assertEquals(3, recordsNamed("gen_ai.client.operation").size(), "planner and both tool-loop model turns");
        assertEquals(1, recordsNamed("spring.ai.tool").size(), "exactly one model-initiated tool execution");
        assertEquals(3, recordsNamed("http.client.requests").size(), "one HTTP child per model turn");
        assertObservationsAreComplete();
        assertEquals(Set.of("spring.ai.chat.client"), parentNamesOf("spring.ai.advisor"));
        assertEquals(Set.of("spring.ai.advisor"), parentNamesOf("gen_ai.client.operation"));
        assertEquals(Set.of("gen_ai.client.operation"), parentNamesOf("http.client.requests"));
        assertEquals(Set.of("spring.ai.advisor"), parentNamesOf("spring.ai.tool"));
        assertTrue(recordsNamed("spring.ai.chat.client").stream().allMatch(record -> record.parentName() == null));
        assertEquals("findInventory",
                single("spring.ai.tool").attributes().get("spring.ai.tool.definition.name"));
        assertNoPromptOrCredentialAttributes();
    }

    @Test
    void modelReceivesDeterministicSimulationResultsFromTheDisposalTool() throws Exception {
        FAKE_MODEL.reset(Mode.DISPOSAL_TOOL);

        String taskId = submit("模拟给 SKU-MCU-001 补货 100 件");
        awaitTerminal(taskId);

        RiskAnalysisReport report = report(taskId);
        assertEquals("LOCAL_DISPOSAL_TOOL_OK", report.summary());
        assertEquals(List.of("simulateInventoryReplenishment"), report.toolCalls());

        List<RecordedRequest> reportRequests = FAKE_MODEL.requests().stream()
            .filter(RecordedRequest::hasTools)
            .toList();
        assertEquals(2, reportRequests.size());
        JsonNode toolMessage = reportRequests.get(1).toolMessages().get(0);
        assertEquals(TOOL_CALL_ID, toolMessage.path("tool_call_id").asText());
        JsonNode result = new ObjectMapper().readTree(toolMessage.path("content").asText());
        assertEquals("INVENTORY_REPLENISHMENT", result.path("scenario").asText());
        assertTrue(result.path("sku").asText().equals(SKU));
        assertEquals(120, result.path("simulated").path("targetSkuAvailableToPromise").asInt());
        assertEquals(230, result.path("simulated").path("totalShortageUnits").asInt());
        assertFalse(result.path("realDataMutated").asBoolean());

        List<SupplyChainTaskEvent> events = events(taskId);
        assertEquals(1, count(events, SupplyChainEventType.TOOL_COMPLETED,
                "simulateInventoryReplenishment", "model-initiated"));
        assertTrue(events.stream()
            .filter(event -> SupplyChainEventType.TOOL_COMPLETED.equals(event.type()))
            .filter(event -> "simulateInventoryReplenishment".equals(event.stage()))
            .anyMatch(event -> event.summary().contains("affectedOrders=1")));
        assertFalse(events.stream().anyMatch(event -> SupplyChainEventType.MODEL_FAILED.equals(event.type())));
    }

    @Test
    void modelReceivesRuleRecommendationsInsteadOfRecomputingPriority() throws Exception {
        FAKE_MODEL.reset(Mode.RECOMMENDATION_TOOL);

        String taskId = submit("给我 SKU-MCU-001 的处置建议");
        awaitTerminal(taskId);

        RiskAnalysisReport report = report(taskId);
        assertEquals("LOCAL_RECOMMENDATION_TOOL_OK top=FOLLOW_UP_SUPPLIER", report.summary());
        assertEquals(List.of("findDisposalRecommendations"), report.toolCalls());

        List<RecordedRequest> reportRequests = FAKE_MODEL.requests().stream()
            .filter(RecordedRequest::hasTools)
            .toList();
        JsonNode result = new ObjectMapper().readTree(
                reportRequests.get(1).toolMessages().get(0).path("content").asText());
        assertEquals(2, result.size());
        assertEquals("FOLLOW_UP_SUPPLIER", result.get(0).path("actionType").asText());
        assertEquals("P1", result.get(0).path("priority").asText());
        assertEquals(86, result.get(0).path("priorityScore").asInt());
        assertFalse(result.get(0).path("priorityBasis").asText().isBlank());
        assertTrue(result.get(0).path("businessBasis").size() > 0);

        List<SupplyChainTaskEvent> events = events(taskId);
        assertEquals(1, count(events, SupplyChainEventType.TOOL_COMPLETED, "findDisposalRecommendations",
                "model-initiated"));
        assertFalse(events.stream().anyMatch(event -> SupplyChainEventType.MODEL_FAILED.equals(event.type())));
    }

    @Test
    void modelExplainsThePersistedComparisonInsteadOfRecomputingTheRanking() throws Exception {
        FAKE_MODEL.reset(Mode.COMPARISON_TOOL);

        String taskId = submit("比较 SKU-MCU-001 补货 50/100/200 件的方案");
        awaitTerminal(taskId);

        DisposalScenarioComparison comparison = latestComparison();
        assertEquals(3, comparison.candidates().size());
        assertEquals(64, comparison.baselineFingerprint().length());
        assertFalse(comparison.realDataMutated());
        assertEquals(3, comparison.rankings().size());
        DisposalScenarioCandidate recommended = comparison.candidates()
            .stream()
            .filter(candidate -> candidate.candidateId().equals(comparison.recommendation().recommendedCandidateId()))
            .findFirst()
            .orElseThrow();
        DisposalScenarioRanking recommendedRanking = comparison.rankings()
            .stream()
            .filter(ranking -> ranking.candidateId().equals(recommended.candidateId()))
            .findFirst()
            .orElseThrow();
        assertTrue(recommendedRanking.recommended());
        assertEquals(Integer.valueOf(1), recommendedRanking.rank());
        assertEquals(comparison.recommendation().recommendedLabel(), recommended.label());

        RiskAnalysisReport report = report(taskId);
        assertEquals("LOCAL_COMPARISON_TOOL_OK recommended=" + recommended.label(),
                report.summary(), "the model may only explain the comparison returned by Java");
        assertEquals(List.of("compareDisposalScenarios"), report.toolCalls());

        List<RecordedRequest> reportRequests = FAKE_MODEL.requests().stream()
            .filter(RecordedRequest::hasTools)
            .toList();
        assertEquals(2, reportRequests.size());
        JsonNode result = new ObjectMapper().readTree(
                reportRequests.get(1).toolMessages().get(0).path("content").asText());
        assertEquals(comparison.comparisonId(), result.path("comparisonId").asText());
        assertEquals(comparison.baselineFingerprint(), result.path("baselineFingerprint").asText());
        assertEquals(3, result.path("candidates").size());
        assertEquals(comparison.recommendation().recommendedCandidateId(),
                result.path("recommendation").path("recommendedCandidateId").asText());
        assertEquals(recommended.label(), result.path("recommendation").path("recommendedLabel").asText());
        assertEquals(1, result.path("recommendation").path("recommendedRank").asInt());
        assertFalse(result.path("realDataMutated").asBoolean());

        List<SupplyChainTaskEvent> events = events(taskId);
        assertEquals(1, count(events, SupplyChainEventType.TOOL_COMPLETED, "compareDisposalScenarios",
                "model-initiated"));
        assertTrue(events.stream()
            .filter(event -> SupplyChainEventType.TOOL_COMPLETED.equals(event.type()))
            .filter(event -> "compareDisposalScenarios".equals(event.stage()))
            .anyMatch(event -> event.summary().contains("candidates=3")
                    && event.summary().contains("recommended=" + recommended.candidateId())));
        assertFalse(events.stream().anyMatch(event -> SupplyChainEventType.MODEL_FAILED.equals(event.type())));
    }

    @Test
    void modelAnswerWithoutToolCallsLeavesToolEvidenceEmpty() {
        FAKE_MODEL.reset(Mode.NO_TOOL);

        String taskId = submit("直接回答，无需查询工具");
        awaitTerminal(taskId);

        RiskAnalysisReport report = report(taskId);
        assertEquals("NO_TOOL_OK model answered from the given context", report.summary());
        assertTrue(report.modelAssisted());
        assertEquals(List.of(), report.toolCalls());

        List<SupplyChainTaskEvent> events = events(taskId);
        assertTrue(events.stream()
            .filter(event -> SupplyChainEventType.TOOL_COMPLETED.equals(event.type()))
            .allMatch(event -> event.summary().startsWith("workflow tool;")));
        assertFalse(events.stream().anyMatch(event -> SupplyChainEventType.MODEL_FAILED.equals(event.type())));
        assertFalse(events.stream().anyMatch(event -> SupplyChainEventType.FALLBACK_USED.equals(event.type())));

        assertEquals(2, recordsNamed("spring.ai.chat.client").size());
        assertEquals(2, recordsNamed("spring.ai.advisor").size());
        assertEquals(2, recordsNamed("gen_ai.client.operation").size());
        assertEquals(0, recordsNamed("spring.ai.tool").size());
        assertEquals(2, recordsNamed("http.client.requests").size());
        assertObservationsAreComplete();
        assertEquals(List.of(), recordsNamed("spring.ai.tool"));
    }

    @Test
    void toolThatRunsBeforeAFailedFollowUpIsStillReportedTruthfully() {
        FAKE_MODEL.reset(Mode.FAIL_AFTER_TOOL);

        String taskId = submit("触发工具后让模型后续调用失败");
        awaitTerminal(taskId);

        List<RecordedRequest> reportRequests = FAKE_MODEL.requests().stream()
            .filter(RecordedRequest::hasTools)
            .toList();
        assertEquals(2, reportRequests.size(), "the tool result must have been sent back before the failure");
        assertEquals(TOOL_CALL_ID, reportRequests.get(1).toolMessages().get(0).path("tool_call_id").asText());

        RiskAnalysisReport report = report(taskId);
        assertFalse(report.modelAssisted());
        assertTrue(report.summary().startsWith("发现 "));
        assertEquals(List.of("findInventory"), report.toolCalls(),
                "a tool the model actually requested must stay visible even when the final answer fails");

        List<SupplyChainTaskEvent> events = events(taskId);
        assertEquals(1, count(events, SupplyChainEventType.TOOL_COMPLETED, "findInventory", "model-initiated"));
        assertEquals(1, events.stream().filter(event -> SupplyChainEventType.MODEL_FAILED.equals(event.type())).count());
        assertEquals(1, events.stream().filter(event -> SupplyChainEventType.FALLBACK_USED.equals(event.type())).count());

        assertEquals(3, recordsNamed("gen_ai.client.operation").size());
        assertEquals(1, recordsNamed("spring.ai.tool").size());
        assertNull(single("spring.ai.tool").errorType(), "the tool itself completed before the follow-up failed");
        assertEquals(1, recordsNamed("spring.ai.chat.client").stream()
            .filter(record -> record.errorType() != null)
            .count(),
                "only the report client call failed; the fallback is not a successful model observation");
        assertEquals(1, recordsNamed("gen_ai.client.operation").stream()
            .filter(record -> record.errorType() != null)
            .count(),
                "the failed model turn must carry an error");
        assertObservationsAreComplete();
    }

    @Test
    void toolFailureIsReportedByTheToolObservationWhileTheModelStillCompletes() {
        FAKE_MODEL.reset(Mode.TOOL_FAILURE);
        doThrow(new IllegalStateException("inventory store down")).when(queryService).findInventory(FAILING_SKU);

        String taskId = submit("触发工具失败但让模型继续回答");
        awaitTerminal(taskId);

        RiskAnalysisReport report = report(taskId);
        assertEquals("TOOL_FAILURE_HANDLED the model received the tool error", report.summary());
        assertTrue(report.plannerAssisted());
        assertTrue(report.modelAssisted());
        assertEquals(List.of("findInventory"), report.toolCalls());

        List<RecordedRequest> reportRequests = FAKE_MODEL.requests().stream()
            .filter(RecordedRequest::hasTools)
            .toList();
        assertEquals(2, reportRequests.size());
        String toolMessage = reportRequests.get(1).toolMessages().get(0).path("content").asText();
        assertTrue(toolMessage.contains("inventory store down") || toolMessage.contains("IllegalStateException"),
                () -> "the model-visible tool response should describe the failure: " + toolMessage);

        List<SupplyChainTaskEvent> events = events(taskId);
        assertEquals(1, count(events, SupplyChainEventType.TOOL_FAILED, "findInventory", "model-initiated"));
        assertEquals(2, events.stream().filter(event -> SupplyChainEventType.MODEL_COMPLETED.equals(event.type())).count());
        assertFalse(events.stream().anyMatch(event -> SupplyChainEventType.MODEL_FAILED.equals(event.type())));
        assertFalse(events.stream().anyMatch(event -> SupplyChainEventType.FALLBACK_USED.equals(event.type())));

        ObservationRecord toolObservation = single("spring.ai.tool");
        assertEquals("ToolExecutionException", toolObservation.errorType(),
                "the native tool observation must not look successful");
        assertEquals(3, recordsNamed("gen_ai.client.operation").size());
        assertTrue(recordsNamed("gen_ai.client.operation").stream().allMatch(record -> record.errorType() == null),
                "the model call itself succeeded after observing the tool error");
        assertTrue(recordsNamed("spring.ai.chat.client").stream().allMatch(record -> record.errorType() == null));
        assertObservationsAreComplete();
    }

    private List<ObservationRecord> recordsNamed(String name) {
        return observations.all().stream().filter(record -> name.equals(record.name())).toList();
    }

    private ObservationRecord single(String name) {
        List<ObservationRecord> matches = recordsNamed(name);
        assertEquals(1, matches.size(), () -> "expected one " + name + " observation but got " + observations.all());
        return matches.get(0);
    }

    private Set<String> parentNamesOf(String name) {
        return recordsNamed(name).stream().map(ObservationRecord::parentName).collect(Collectors.toSet());
    }

    private void assertObservationsAreComplete() {
        assertFalse(observations.all().isEmpty(), "no native observation was recorded");
        assertTrue(observations.all().stream().allMatch(record -> record.durationNanos() > 0),
                () -> "every observation must cover start and stop: " + observations.all());
    }

    private void assertNoPromptOrCredentialAttributes() {
        List<String> values = observations.all()
            .stream()
            .flatMap(record -> record.attributes().values().stream())
            .toList();
        assertTrue(values.stream().noneMatch(value -> value.contains("local-test-key")),
                "API keys must not be exported as observation attributes");
        assertTrue(values.stream().noneMatch(value -> value.contains("执行计划：")),
                "full prompts must not be exported as observation attributes");
        assertTrue(values.stream().noneMatch(value -> value.contains("LOCAL_TOOL_LOOP_OK")),
                "full model answers must not be exported as observation attributes");
    }

    private static long count(List<SupplyChainTaskEvent> events, String type, String stage, String summaryPrefix) {
        return events.stream()
            .filter(event -> type.equals(event.type()))
            .filter(event -> stage.equals(event.stage()))
            .filter(event -> event.summary() != null && event.summary().startsWith(summaryPrefix))
            .count();
    }

    private int executionRows() {
        Integer rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chainpilot_disposal_execution",
                Integer.class);
        return rows == null ? 0 : rows;
    }

    private DisposalScenarioComparison latestComparison() {
        DisposalScenarioComparisonPage page = webTestClient.get()
            .uri("/api/supply-chain/disposal-scenario-comparisons?page=1&pageSize=10")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(DisposalScenarioComparisonPage.class)
            .returnResult()
            .getResponseBody();
        assertNotNull(page);
        assertEquals(1, page.totalElements());
        DisposalScenarioComparison comparison = webTestClient.get()
            .uri("/api/supply-chain/disposal-scenario-comparisons/{comparisonId}",
                    page.items().get(0).comparisonId())
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(DisposalScenarioComparison.class)
            .returnResult()
            .getResponseBody();
        assertNotNull(comparison);
        return comparison;
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
        await().atMost(TERMINAL_TIMEOUT).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
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

    private enum Mode {
        TOOL_LOOP, NO_TOOL, FAIL_AFTER_TOOL, TOOL_FAILURE, DISPOSAL_TOOL, RECOMMENDATION_TOOL,
        COMPARISON_TOOL
    }

    private record RecordedRequest(JsonNode body) {

        boolean hasTools() {
            return body.path("tools").isArray() && !body.path("tools").isEmpty();
        }

        Set<String> toolNames() {
            return StreamSupport.stream(body.path("tools").spliterator(), false)
                .map(tool -> tool.path("function").path("name").asText())
                .collect(Collectors.toSet());
        }

        List<JsonNode> messages() {
            return StreamSupport.stream(body.path("messages").spliterator(), false).toList();
        }

        List<JsonNode> toolMessages() {
            return messages().stream().filter(message -> "tool".equals(message.path("role").asText())).toList();
        }
    }

    private static final class LocalOpenAiServer {

        private final HttpServer server;

        private final ExecutorService executor;

        private final ObjectMapper mapper = new ObjectMapper();

        private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();

        private final AtomicInteger responseSequence = new AtomicInteger();

        private volatile Mode mode = Mode.TOOL_LOOP;

        private LocalOpenAiServer(HttpServer server, ExecutorService executor) {
            this.server = server;
            this.executor = executor;
        }

        static LocalOpenAiServer start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "local-fake-model");
                    thread.setDaemon(true);
                    return thread;
                });
                LocalOpenAiServer fake = new LocalOpenAiServer(server, executor);
                server.createContext("/v1/chat/completions", fake::handle);
                server.setExecutor(executor);
                server.start();
                return fake;
            }
            catch (IOException exception) {
                throw new IllegalStateException("Unable to start the loopback fake model", exception);
            }
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void reset(Mode nextMode) {
            requests.clear();
            mode = nextMode;
        }

        List<RecordedRequest> requests() {
            return List.copyOf(requests);
        }

        void stop() {
            server.stop(0);
            executor.shutdownNow();
        }

        private void handle(HttpExchange exchange) throws IOException {
            try {
                if (!"POST".equals(exchange.getRequestMethod()) || !"/v1/chat/completions"
                    .equals(exchange.getRequestURI().getPath())) {
                    send(exchange, 404, mapper.writeValueAsBytes(Map.of("error", "not found")));
                    return;
                }
                JsonNode request = mapper.readTree(exchange.getRequestBody());
                requests.add(new RecordedRequest(request));
                JsonNode response = switch (mode) {
                    case NO_TOOL -> noToolResponse(request);
                    case FAIL_AFTER_TOOL -> failAfterToolResponse(request);
                    case TOOL_FAILURE -> toolFailureResponse(request);
                    case TOOL_LOOP -> toolLoopResponse(request);
                    case DISPOSAL_TOOL -> disposalToolResponse(request);
                    case RECOMMENDATION_TOOL -> recommendationToolResponse(request);
                    case COMPARISON_TOOL -> comparisonToolResponse(request);
                };
                if (response == null) {
                    send(exchange, 400, mapper.writeValueAsBytes(Map.of("error",
                            Map.of("message", "simulated follow-up failure", "type", "invalid_request_error"))));
                    return;
                }
                send(exchange, 200, mapper.writeValueAsBytes(response));
            }
            catch (RuntimeException exception) {
                send(exchange, 500, mapper.writeValueAsBytes(Map.of("error", "fake model failure")));
            }
            finally {
                exchange.close();
            }
        }

        private JsonNode noToolResponse(JsonNode request) throws IOException {
            if (!hasTools(request)) {
                return planResponse();
            }
            return narrativeCompletion("NO_TOOL_OK model answered from the given context");
        }

        private JsonNode failAfterToolResponse(JsonNode request) throws IOException {
            if (!hasTools(request)) {
                return planResponse();
            }
            if (toolMessages(request).isEmpty()) {
                return toolCallResponse(SKU);
            }
            return null;
        }

        private JsonNode toolFailureResponse(JsonNode request) throws IOException {
            if (!hasTools(request)) {
                return planResponse();
            }
            if (toolMessages(request).isEmpty()) {
                return toolCallResponse(FAILING_SKU);
            }
            return narrativeCompletion("TOOL_FAILURE_HANDLED the model received the tool error");
        }

        private JsonNode toolLoopResponse(JsonNode request) throws IOException {
            if (!hasTools(request)) {
                return planResponse();
            }
            List<JsonNode> toolMessages = toolMessages(request);
            if (toolMessages.isEmpty()) {
                return toolCallResponse(SKU);
            }
            JsonNode toolResult = mapper.readTree(toolMessages.get(toolMessages.size() - 1)
                .path("content")
                .asText());
            JsonNode position = toolResult.get(0);
            return narrativeCompletion("LOCAL_TOOL_LOOP_OK sku=" + position.path("sku").asText() + " available="
                    + position.path("availableToPromise").asInt());
        }

        private JsonNode disposalToolResponse(JsonNode request) throws IOException {
            if (!hasTools(request)) {
                return planResponse();
            }
            List<JsonNode> toolMessages = toolMessages(request);
            if (toolMessages.isEmpty()) {
                return toolCallResponse("simulateInventoryReplenishment",
                        "{\"sku\":\"" + SKU + "\",\"quantity\":100}");
            }
            JsonNode result = mapper.readTree(toolMessages.get(toolMessages.size() - 1).path("content").asText());
            return narrativeCompletion("LOCAL_DISPOSAL_TOOL_OK");
        }

        private JsonNode recommendationToolResponse(JsonNode request) throws IOException {
            if (!hasTools(request)) {
                return planResponse();
            }
            List<JsonNode> toolMessages = toolMessages(request);
            if (toolMessages.isEmpty()) {
                return toolCallResponse("findDisposalRecommendations", "{\"sku\":\"" + SKU + "\"}");
            }
            JsonNode result = mapper.readTree(toolMessages.get(toolMessages.size() - 1).path("content").asText());
            JsonNode top = result.get(0);
            return narrativeCompletion("LOCAL_RECOMMENDATION_TOOL_OK top=" + top.path("actionType").asText());
        }

        private JsonNode comparisonToolResponse(JsonNode request) throws IOException {
            if (!hasTools(request)) {
                return planResponse();
            }
            List<JsonNode> toolMessages = toolMessages(request);
            if (toolMessages.isEmpty()) {
                return toolCallResponse("compareDisposalScenarios",
                        "{\"sku\":\"" + SKU + "\",\"replenishmentQuantitiesCsv\":\"50,100,200\"}");
            }
            JsonNode result = mapper.readTree(toolMessages.get(toolMessages.size() - 1).path("content").asText());
            JsonNode recommendation = result.path("recommendation");
            return narrativeCompletion("LOCAL_COMPARISON_TOOL_OK recommended="
                    + recommendation.path("recommendedLabel").asText());
        }

        private JsonNode planResponse() {
            return completion("1. 查询订单延期风险\n2. 核对库存与可承诺量\n3. 检查供应商履约风险");
        }

        /** The report model contract is evidence-backed JSON; reasons must cover every deterministic risk order. */
        private JsonNode narrativeCompletion(String overview) throws IOException {
            ObjectNode root = mapper.createObjectNode();
            root.put("overview", overview);
            ArrayNode reasons = root.putArray("reasons");
            for (String orderId : List.of("ORD-1001", "ORD-1002", "ORD-1004")) {
                ObjectNode reason = reasons.addObject();
                reason.put("orderId", orderId);
                reason.put("summary", "该订单的确定性风险证据已由 Java 规则计算。");
                reason.putArray("evidenceIds").add("DELAY");
            }
            root.putArray("businessImpacts");
            root.putArray("recommendations");
            root.putArray("uncertainties");
            return completion(mapper.writeValueAsString(root));
        }

        private JsonNode toolCallResponse(String sku) {
            return toolCallResponse("findInventory", "{\"sku\":\"" + sku + "\"}");
        }

        private JsonNode toolCallResponse(String name, String arguments) {
            ObjectNode root = baseCompletion(null);
            ObjectNode choice = (ObjectNode) root.path("choices").get(0);
            choice.put("finish_reason", "tool_calls");
            ObjectNode message = (ObjectNode) choice.path("message");
            message.putNull("content");
            ObjectNode toolCall = message.putArray("tool_calls").addObject();
            toolCall.put("id", TOOL_CALL_ID);
            toolCall.put("type", "function");
            ObjectNode function = toolCall.putObject("function");
            function.put("name", name);
            function.put("arguments", arguments);
            return root;
        }

        private JsonNode completion(String content) {
            return baseCompletion(content);
        }

        private ObjectNode baseCompletion(String content) {
            ObjectNode root = mapper.createObjectNode();
            root.put("id", "chatcmpl-local-" + responseSequence.incrementAndGet());
            root.put("object", "chat.completion");
            root.put("created", Instant.now().getEpochSecond());
            root.put("model", "local-fake-model");
            ArrayNode choices = root.putArray("choices");
            ObjectNode choice = choices.addObject();
            choice.put("index", 0);
            ObjectNode message = choice.putObject("message");
            message.put("role", "assistant");
            if (content == null) {
                message.putNull("content");
            }
            else {
                message.put("content", content);
            }
            choice.put("finish_reason", "stop");
            ObjectNode usage = root.putObject("usage");
            usage.put("prompt_tokens", 1);
            usage.put("completion_tokens", 1);
            usage.put("total_tokens", 2);
            return root;
        }

        private boolean hasTools(JsonNode request) {
            return request.path("tools").isArray() && !request.path("tools").isEmpty();
        }

        private List<JsonNode> toolMessages(JsonNode request) {
            List<JsonNode> toolMessages = new ArrayList<>();
            for (JsonNode message : request.path("messages")) {
                if ("tool".equals(message.path("role").asText())) {
                    toolMessages.add(message);
                }
            }
            return toolMessages;
        }

        private void send(HttpExchange exchange, int status, byte[] body) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        }
    }

    record ObservationRecord(String name, String contextualName, String parentName, String errorType,
            long durationNanos, Map<String, String> attributes) {
    }

    private static final class RecordingObservationHandler implements ObservationHandler<Observation.Context> {

        private static final Object START_NANOS = new Object();

        private final List<ObservationRecord> records = new CopyOnWriteArrayList<>();

        @Override
        public void onStart(Observation.Context context) {
            context.put(START_NANOS, System.nanoTime());
        }

        @Override
        public void onStop(Observation.Context context) {
            ObservationView parent = context.getParentObservation();
            Long started = context.get(START_NANOS);
            Map<String, String> attributes = new LinkedHashMap<>();
            context.getAllKeyValues()
                .forEach(keyValue -> attributes.put(keyValue.getKey(), String.valueOf(keyValue.getValue())));
            records.add(new ObservationRecord(context.getName(), context.getContextualName(),
                    parent == null ? null : parent.getContextView().getName(),
                    context.getError() == null ? null : context.getError().getClass().getSimpleName(),
                    started == null ? -1L : System.nanoTime() - started, Map.copyOf(attributes)));
        }

        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }

        void reset() {
            records.clear();
        }

        List<ObservationRecord> all() {
            return List.copyOf(records);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class LocalFakeModelConfiguration {

        @Bean
        RecordingObservationHandler recordingObservationHandler(ObservationRegistry observationRegistry) {
            RecordingObservationHandler handler = new RecordingObservationHandler();
            observationRegistry.observationConfig().observationHandler(handler);
            return handler;
        }

        @Bean
        DynamicModelFactory dynamicModelFactory(ObservationRegistry observationRegistry) {
            return new DynamicModelFactory(observationRegistry);
        }

        @Bean
        AiModelRegistry aiModelRegistry(DynamicModelFactory modelFactory, ModelConfigDataService dataService,
                ObservationRegistry observationRegistry) {
            return new AiModelRegistry(modelFactory, dataService, observationRegistry);
        }

        @Bean
        ModelConfigDataService modelConfigDataService(
                @Value("${chainpilot.fake-model.base-url}") String baseUrl) {
            ModelConfigDTO config = ModelConfigDTO.builder()
                .provider("custom")
                .apiKey("local-test-key")
                .baseUrl(baseUrl)
                .modelName("local-fake-model")
                .modelType("CHAT")
                .temperature(0.0)
                .maxTokens(512)
                .isActive(true)
                .proxyEnabled(false)
                .build();
            ModelConfigDataService dataService = mock(ModelConfigDataService.class);
            when(dataService.getActiveConfigByType(ModelType.CHAT)).thenReturn(config);
            return dataService;
        }
    }
}
