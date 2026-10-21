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
package com.alibaba.cloud.ai.dataagent.supplychain;

import com.alibaba.cloud.ai.dataagent.dto.ModelConfigDTO;
import com.alibaba.cloud.ai.dataagent.enums.ModelType;
import com.alibaba.cloud.ai.dataagent.service.aimodelconfig.AiModelRegistry;
import com.alibaba.cloud.ai.dataagent.service.aimodelconfig.DynamicModelFactory;
import com.alibaba.cloud.ai.dataagent.service.aimodelconfig.ModelConfigDataService;
import com.alibaba.cloud.ai.dataagent.supplychain.model.CreateDisposalScenarioComparisonRequest;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DecisionProposal;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DecisionTaskResult;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalPlan;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioCandidate;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioComparison;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioRanking;
import com.alibaba.cloud.ai.dataagent.supplychain.model.ProposalStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskAnalysisReport;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxExecution;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxExecutionStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainEventType;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskEvent;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskSummary;
import com.alibaba.cloud.ai.dataagent.supplychain.service.DisposalScenarioComparisonService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end HTTP coverage for the natural-language decision entry. Two model behaviours are
 * fake (narrative success and narrative failure); every inventory number, candidate metric,
 * rank, proposal and sandbox execution comes from the real deterministic Java services.
 */
@SpringBootTest(classes = { ChainPilotTestApplication.class,
        SupplyChainDecisionHttpIntegrationTest.LocalFakeModelConfiguration.class },
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:chainpilot_decision_test;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;MODE=MySQL",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.sql.init.mode=never",
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.reactive.ReactiveSecurityAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.security.reactive.ReactiveUserDetailsServiceAutoConfiguration",
                "logging.level.root=warn",
                "logging.level.com.alibaba.cloud.ai.dataagent.supplychain=info" })
class SupplyChainDecisionHttpIntegrationTest {

    private static final Duration TERMINAL_TIMEOUT = Duration.ofSeconds(30);

    private static final String SKU = "SKU-MCU-001";

    private static final LocalFakeModel FAKE_MODEL = LocalFakeModel.start();

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private DisposalScenarioComparisonService comparisonService;

    @BeforeEach
    void resetState() {
        FAKE_MODEL.reset(Mode.NARRATIVE);
        jdbcTemplate.update("DELETE FROM chainpilot_action_proposal");
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
    void naturalLanguageComparisonRunsTheRealComparatorAndCitesItsCandidatesInTheAnswer() {
        String taskId = submitDecision("比较 " + SKU + " 补货 50、100、200 件的方案");

        SupplyChainTaskStatus completed = awaitTerminal(taskId);
        assertEquals("COMPLETED", completed.status());

        DecisionTaskResult decision = decision(taskId);
        assertEquals("COMPARE_REPLENISHMENT", decision.intent());
        assertEquals("COMPLETED", decision.status());
        assertEquals("MODEL_GROUNDED", decision.narrativeSource());
        assertTrue(decision.modelAssisted());
        assertFalse(decision.fallbackUsed());
        assertFalse(decision.realDataMutated());
        assertFalse(decision.clarificationRequired());
        assertTrue(decision.toolCalls().contains("compareDisposalScenarios"));
        assertNotNull(decision.comparison());
        assertEquals(3, decision.comparison().candidates().size());
        assertEquals(SKU, decision.comparison().sku());

        String recommendedId = decision.comparison().recommendation().recommendedCandidateId();
        assertNotNull(recommendedId, "the deterministic engine must produce a candidate recommendation");
        DisposalScenarioRanking recommended = decision.comparison()
            .rankings()
            .stream()
            .filter(ranking -> ranking.candidateId().equals(recommendedId))
            .findFirst()
            .orElseThrow();
        assertNotNull(recommended.rank());
        assertEquals(1, recommended.rank());

        assertTrue(decision.summary().startsWith("DECISION_MODEL_OK"));
        for (DisposalScenarioCandidate candidate : decision.comparison().candidates()) {
            assertTrue(decision.summary().contains("候选 " + candidate.candidateId()),
                    () -> "the final answer must cite actual candidate ids: " + decision.summary());
            assertTrue(decision.summary().contains(candidate.label()));
        }
        assertTrue(decision.summary().contains("排名 1"));
        assertTrue(decision.summary().contains("风险订单Δ="));
        assertTrue(decision.summary().contains("确定性证据"));
        assertTrue(decision.summary().contains("本结果只读"));

        List<FakeRequest> requests = FAKE_MODEL.requests();
        assertEquals(1, requests.size(), "a deterministic comparison needs exactly one narrative model call");
        FakeRequest narrative = requests.get(0);
        assertFalse(narrative.hasTools(), "the narrative model call must not expose write or tool access");
        String prompt = narrative.messages().toString();
        assertTrue(prompt.contains(recommendedId));
        assertTrue(prompt.contains("排名 1"));
        assertTrue(prompt.contains("风险订单Δ="));
        assertTrue(prompt.contains("未知项："));
        assertTrue(prompt.contains("不要提出已执行任何操作"));

        DisposalScenarioComparison persisted = webTestClient.get()
            .uri("/api/supply-chain/disposal-scenario-comparisons/{comparisonId}",
                    decision.comparison().comparisonId())
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(DisposalScenarioComparison.class)
            .returnResult()
            .getResponseBody();
        assertNotNull(persisted);
        assertEquals(3, persisted.candidates().size());

        List<SupplyChainTaskEvent> events = events(taskId);
        assertEquals(1, count(events, SupplyChainEventType.INTENT_RECOGNIZED, "DECISION_INTENT"));
        assertEquals(1, count(events, SupplyChainEventType.MODEL_COMPLETED, "DECISION_SUMMARY"));
        assertEquals(1, count(events, SupplyChainEventType.TASK_COMPLETED, "WORKFLOW"));
        assertTrue(events.stream()
            .anyMatch(event -> SupplyChainEventType.TOOL_COMPLETED.equals(event.type())
                    && "compareDisposalScenarios".equals(event.stage())
                    && event.summary() != null && event.summary().startsWith("workflow tool;")));
        assertFalse(events.stream().anyMatch(event -> SupplyChainEventType.MODEL_FAILED.equals(event.type())));
        assertFalse(events.stream().anyMatch(event -> SupplyChainEventType.FALLBACK_USED.equals(event.type())));
        assertFalse(events.stream().anyMatch(event -> SupplyChainEventType.TOOL_FAILED.equals(event.type())));
        assertTrue(events.stream().noneMatch(event -> event.summary() != null
                && (event.summary().contains("local-test-key") || event.summary().contains("确定性工具结果"))));

        webTestClient.get()
            .uri("/api/supply-chain/analysis/tasks/{taskId}/report", taskId)
            .exchange()
            .expectStatus()
            .isEqualTo(409);
        assertEquals(0, executionRows());
        assertEquals(0, proposalRows());

        List<SupplyChainTaskSummary> decisionHistory = history("DECISION");
        assertTrue(decisionHistory.stream().anyMatch(item -> item.taskId().equals(taskId)));
        List<SupplyChainTaskSummary> analysisHistory = history("ANALYSIS");
        assertTrue(analysisHistory.stream().noneMatch(item -> item.taskId().equals(taskId)));
        List<SupplyChainTaskSummary> defaultHistory = history(null);
        assertTrue(defaultHistory.stream().noneMatch(item -> item.taskId().equals(taskId)),
                "existing default history stays analysis-only");
    }

    @Test
    void ambiguousComparisonAsksForTheSkuWithoutCallingTheModelOrPersistingBusinessData() {
        String taskId = submitDecision("比较补货 50、100、200 件的方案");

        awaitTerminal(taskId);
        DecisionTaskResult decision = decision(taskId);

        assertEquals("UNSUPPORTED", decision.intent());
        assertTrue(decision.clarificationRequired());
        assertTrue(decision.clarifications().get(0).contains("SKU"));
        assertFalse(decision.summary().contains("候选"));
        assertTrue(FAKE_MODEL.requests().isEmpty(), "ambiguous requests must not spend a model call");
        assertEquals(0, comparisonRows());
        assertEquals(0, proposalRows());
        assertEquals(0, executionRows());

        List<SupplyChainTaskEvent> events = events(taskId);
        assertEquals(1, count(events, SupplyChainEventType.CLARIFICATION_REQUIRED, "DECISION_INTENT"));
        assertEquals(1, count(events, SupplyChainEventType.TASK_COMPLETED, "WORKFLOW"));
    }

    @Test
    void modelFailureKeepsTheTaskTruthfulAndStillReturnsTheDeterministicComparisonEvidence() {
        FAKE_MODEL.reset(Mode.FAIL);

        String taskId = submitDecision("比较 " + SKU + " 补货 50、100、200 件的方案");
        SupplyChainTaskStatus completed = awaitTerminal(taskId);

        assertEquals("COMPLETED", completed.status(),
                "the deterministic business result remains available even if the narrative model fails");
        DecisionTaskResult decision = decision(taskId);
        assertEquals("DETERMINISTIC_FALLBACK", decision.narrativeSource());
        assertFalse(decision.modelAssisted());
        assertTrue(decision.fallbackUsed());
        assertTrue(decision.summary().contains("确定性推荐候选"));
        assertTrue(decision.summary().contains("风险订单Δ="));
        assertEquals(3, decision.comparison().candidates().size());
        for (DisposalScenarioCandidate candidate : decision.comparison().candidates()) {
            assertTrue(decision.summary().contains(candidate.candidateId()));
        }

        List<SupplyChainTaskEvent> events = events(taskId);
        assertEquals(1, count(events, SupplyChainEventType.MODEL_FAILED, "DECISION_SUMMARY"));
        assertTrue(events.stream()
            .anyMatch(event -> SupplyChainEventType.FALLBACK_USED.equals(event.type())
                    && "DECISION_SUMMARY".equals(event.stage())
                    && event.summary() != null && event.summary().contains("reason=")));
        assertTrue(events.stream().noneMatch(event -> event.summary() != null
                && (event.summary().contains("local-test-key") || event.summary().contains("DECISION_MODEL_OK"))));
        assertEquals(0, proposalRows());
        assertEquals(0, executionRows());
    }

    @Test
    void toolFailureMarksTheTaskFailedAndNeverStoresFabricatedComparisonResults() {
        doThrow(new IllegalStateException("deterministic comparison unavailable"))
            .when(comparisonService)
            .compare(any(CreateDisposalScenarioComparisonRequest.class));

        String taskId = submitDecision("比较 " + SKU + " 补货 50、100、200 件的方案");
        SupplyChainTaskStatus failed = awaitTerminal(taskId);

        assertEquals("FAILED", failed.status());
        assertEquals(0, comparisonRows());
        assertEquals(0, proposalRows());
        assertEquals(0, executionRows());
        assertTrue(FAKE_MODEL.requests().isEmpty(), "a failed deterministic tool must not be replaced by the model");

        webTestClient.get()
            .uri("/api/supply-chain/analysis/tasks/{taskId}/decision", taskId)
            .exchange()
            .expectStatus()
            .isEqualTo(409);

        List<SupplyChainTaskEvent> events = events(taskId);
        assertEquals(1, count(events, SupplyChainEventType.TOOL_FAILED, "compareDisposalScenarios"));
        assertEquals(1, count(events, SupplyChainEventType.TASK_FAILED, "DECISION_EXECUTION"));
        assertTrue(events.stream().noneMatch(event -> SupplyChainEventType.TASK_COMPLETED.equals(event.type())));
    }

    @Test
    void saveCandidateProposalCreatesAPlanOnlyAfterConfirmationAndReplaysIdempotently() {
        DisposalScenarioComparison comparison = createReplenishmentComparison();
        String recommendedId = comparison.recommendation().recommendedCandidateId();
        assertNotNull(recommendedId);
        DisposalScenarioCandidate candidate = candidate(comparison, recommendedId);

        String taskId = submitDecision("将对比 " + comparison.comparisonId() + " 的候选 "
                + candidate.candidateId() + " 保存为处置方案");
        awaitTerminal(taskId);
        DecisionTaskResult decision = decision(taskId);

        assertEquals("PROPOSE_SAVE_PLAN", decision.intent());
        assertEquals(1, decision.proposals().size());
        DecisionProposal proposal = decision.proposals().get(0);
        assertEquals(ProposalStatus.PENDING, proposal.status());
        assertEquals("SAVE_CANDIDATE_AS_PLAN", proposal.actionType().name());
        assertEquals(candidate.candidateId(), proposal.targetId());
        assertTrue(FAKE_MODEL.requests().isEmpty(), "proposal creation is deterministic and needs no model call");
        assertEquals(0, planRows(), "a proposal must not persist a plan before confirmation");
        assertEquals(0, executionRows());
        assertNull(candidate(comparison(comparison.comparisonId()), candidate.candidateId()).linkedPlanId());

        webTestClient.post()
            .uri("/api/supply-chain/action-proposals/{proposalId}/confirm", proposal.proposalId())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("contentHash", "wrong-hash"))
            .exchange()
            .expectStatus()
            .isBadRequest();
        assertEquals(0, planRows());

        DecisionProposal confirmed = confirm(proposal);
        assertEquals(ProposalStatus.CONFIRMED, confirmed.status());
        String planId = (String) confirmed.result().get("planId");
        assertNotNull(planId);
        assertEquals(Boolean.FALSE, confirmed.result().get("realDataMutated"));
        assertEquals(1, planRows());
        assertEquals(0, executionRows(), "saving a plan must not start the sandbox rehearsal");

        DisposalPlan plan = webTestClient.get()
            .uri("/api/supply-chain/disposal-plans/{planId}", planId)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(DisposalPlan.class)
            .returnResult()
            .getResponseBody();
        assertNotNull(plan);
        assertEquals("PENDING_CONFIRMATION", plan.status().name());
        assertEquals(SKU, plan.scopeSku());
        assertFalse(plan.recommendations().isEmpty());
        assertNotNull(plan.simulation());
        assertEquals(100, plan.simulation().replenishmentQuantity());
        assertFalse(plan.simulation().realDataMutated());
        assertEquals(planId, candidate(comparison(comparison.comparisonId()), candidate.candidateId()).linkedPlanId());

        DecisionProposal replay = confirm(proposal);
        assertEquals(planId, replay.result().get("planId"));
        assertEquals(1, planRows(), "duplicate confirmation must not create a second plan");

        List<SupplyChainTaskEvent> events = events(taskId);
        assertEquals(1, count(events, SupplyChainEventType.PROPOSAL_CREATED, "PROPOSAL"));
        assertEquals(1, count(events, SupplyChainEventType.PROPOSAL_CONFIRMED, "PROPOSAL"));
    }

    @Test
    void sandboxActionIsOnlyExecutedAfterHumanConfirmationAndReplaysIdempotently() {
        DisposalPlan plan = selectedPlanFromComparison();
        String question = "启动方案 " + plan.planId() + " 的沙箱演练";

        String taskId = submitDecision(question);
        awaitTerminal(taskId);
        DecisionTaskResult decision = decision(taskId);

        assertEquals("PROPOSE_START_SANDBOX", decision.intent());
        assertEquals(1, decision.proposals().size());
        DecisionProposal proposal = decision.proposals().get(0);
        assertEquals(ProposalStatus.PENDING, proposal.status());
        assertEquals(plan.planId(), proposal.targetId());
        assertEquals("START_SANDBOX_EXECUTION", proposal.actionType().name());
        assertTrue(decision.summary().contains("不会自动执行"));
        assertNull(decision.execution());
        assertEquals(0, executionRows(), "a proposal must not start the sandbox rehearsal");
        assertTrue(FAKE_MODEL.requests().isEmpty(), "proposal creation is deterministic and needs no model call");

        List<DecisionProposal> pending = proposals(taskId);
        assertEquals(1, pending.size());
        assertEquals(proposal.proposalId(), pending.get(0).proposalId());

        webTestClient.post()
            .uri("/api/supply-chain/action-proposals/{proposalId}/confirm", proposal.proposalId())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("contentHash", "stale-or-wrong-hash"))
            .exchange()
            .expectStatus()
            .isBadRequest();
        assertEquals(ProposalStatus.PENDING, proposal(proposal.proposalId()).status());
        assertEquals(0, executionRows());

        DecisionProposal confirmed = confirm(proposal);
        assertEquals(ProposalStatus.CONFIRMED, confirmed.status());
        assertNotNull(confirmed.result().get("executionId"));
        assertEquals(Boolean.FALSE, confirmed.result().get("realDataMutated"));
        assertEquals(1, executionRows());

        DecisionProposal replay = confirm(proposal);
        assertEquals(confirmed.result().get("executionId"), replay.result().get("executionId"));
        assertEquals(1, executionRows(), "duplicate confirmation must not duplicate the execution record");

        SandboxExecution execution = webTestClient.get()
            .uri("/api/supply-chain/sandbox-executions/{executionId}", confirmed.result().get("executionId").toString())
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(SandboxExecution.class)
            .returnResult()
            .getResponseBody();
        assertNotNull(execution);
        assertEquals(SandboxExecutionStatus.SUCCEEDED, execution.status());
        assertEquals(plan.planId(), execution.disposalPlanId());
        assertFalse(execution.realDataMutated());

        List<SupplyChainTaskEvent> events = events(taskId);
        assertEquals(1, count(events, SupplyChainEventType.PROPOSAL_CREATED, "PROPOSAL"));
        assertEquals(1, count(events, SupplyChainEventType.PROPOSAL_CONFIRMED, "PROPOSAL"));
    }

    @Test
    void aPendingProposalCanBeCancelledByHashAndThenNeverExecutes() {
        DisposalPlan plan = selectedPlanFromComparison();
        String taskId = submitDecision("启动方案 " + plan.planId() + " 的沙箱演练");
        awaitTerminal(taskId);
        DecisionProposal proposal = decision(taskId).proposals().get(0);

        webTestClient.post()
            .uri("/api/supply-chain/action-proposals/{proposalId}/cancel", proposal.proposalId())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("contentHash", "wrong-hash"))
            .exchange()
            .expectStatus()
            .isBadRequest();

        DecisionProposal cancelled = webTestClient.post()
            .uri("/api/supply-chain/action-proposals/{proposalId}/cancel", proposal.proposalId())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("contentHash", proposal.contentHash()))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(DecisionProposal.class)
            .returnResult()
            .getResponseBody();
        assertNotNull(cancelled);
        assertEquals(ProposalStatus.CANCELLED, cancelled.status());

        webTestClient.post()
            .uri("/api/supply-chain/action-proposals/{proposalId}/confirm", proposal.proposalId())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("contentHash", proposal.contentHash()))
            .exchange()
            .expectStatus()
            .isEqualTo(409);
        assertEquals(0, executionRows());
        assertEquals(1, count(events(taskId), SupplyChainEventType.PROPOSAL_CANCELLED, "PROPOSAL"));
    }

    @Test
    void analysisTasksStayOnTheAnalysisReportEndpointAndDecisionTasksOnTheDecisionEndpoint() {
        FAKE_MODEL.reset(Mode.ANALYSIS);

        String analysisTaskId = submit("分析 " + SKU + " 的库存风险", "ANALYSIS");
        SupplyChainTaskStatus completed = awaitTerminal(analysisTaskId);
        assertEquals("COMPLETED", completed.status());

        RiskAnalysisReport report = webTestClient.get()
            .uri("/api/supply-chain/analysis/tasks/{taskId}/report", analysisTaskId)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(RiskAnalysisReport.class)
            .returnResult()
            .getResponseBody();
        assertNotNull(report);
        assertEquals("ANALYSIS_MODEL_OK", report.summary());

        webTestClient.get()
            .uri("/api/supply-chain/analysis/tasks/{taskId}/decision", analysisTaskId)
            .exchange()
            .expectStatus()
            .isEqualTo(409);

        FAKE_MODEL.reset(Mode.NARRATIVE);
        String decisionTaskId = submitDecision("查看当前全部处置建议");
        awaitTerminal(decisionTaskId);
        assertTrue(history("DECISION").stream().anyMatch(item -> item.taskId().equals(decisionTaskId)));
        assertTrue(history("ANALYSIS").stream().noneMatch(item -> item.taskId().equals(decisionTaskId)));
    }

    private String submitDecision(String question) {
        return submit(question, "DECISION");
    }

    private String submit(String question, String mode) {
        SupplyChainTaskStatus accepted = webTestClient.post()
            .uri("/api/supply-chain/analysis/tasks")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("question", question, "mode", mode))
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

    private DecisionTaskResult decision(String taskId) {
        DecisionTaskResult decision = webTestClient.get()
            .uri("/api/supply-chain/analysis/tasks/{taskId}/decision", taskId)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(DecisionTaskResult.class)
            .returnResult()
            .getResponseBody();
        assertNotNull(decision);
        return decision;
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

    private List<SupplyChainTaskSummary> history(String mode) {
        String uri = mode == null ? "/api/supply-chain/analysis/tasks?page=1&pageSize=20"
                : "/api/supply-chain/analysis/tasks?page=1&pageSize=20&mode=" + mode;
        List<SupplyChainTaskSummary> items = webTestClient.get()
            .uri(uri)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBodyList(SupplyChainTaskSummary.class)
            .returnResult()
            .getResponseBody();
        assertNotNull(items);
        return items;
    }

    private List<DecisionProposal> proposals(String taskId) {
        List<DecisionProposal> proposals = webTestClient.get()
            .uri("/api/supply-chain/action-proposals?taskId={taskId}", taskId)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBodyList(DecisionProposal.class)
            .returnResult()
            .getResponseBody();
        assertNotNull(proposals);
        return proposals;
    }

    private DecisionProposal proposal(String proposalId) {
        DecisionProposal proposal = webTestClient.get()
            .uri("/api/supply-chain/action-proposals/{proposalId}", proposalId)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(DecisionProposal.class)
            .returnResult()
            .getResponseBody();
        assertNotNull(proposal);
        return proposal;
    }

    private DecisionProposal confirm(DecisionProposal proposal) {
        DecisionProposal confirmed = webTestClient.post()
            .uri("/api/supply-chain/action-proposals/{proposalId}/confirm", proposal.proposalId())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("contentHash", proposal.contentHash()))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(DecisionProposal.class)
            .returnResult()
            .getResponseBody();
        assertNotNull(confirmed);
        return confirmed;
    }

    private DisposalScenarioComparison createReplenishmentComparison() {
        Map<String, Object> comparisonBody = new LinkedHashMap<>();
        comparisonBody.put("title", "决策提案验收对比");
        comparisonBody.put("scenario", "INVENTORY_REPLENISHMENT");
        comparisonBody.put("sku", SKU);
        comparisonBody.put("candidates", List.of(Map.of("label", "50 件", "replenishmentQuantity", 50),
                Map.of("label", "100 件", "replenishmentQuantity", 100),
                Map.of("label", "200 件", "replenishmentQuantity", 200)));
        DisposalScenarioComparison comparison = webTestClient.post()
            .uri("/api/supply-chain/disposal-scenario-comparisons")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(comparisonBody)
            .exchange()
            .expectStatus()
            .isCreated()
            .expectBody(DisposalScenarioComparison.class)
            .returnResult()
            .getResponseBody();
        assertNotNull(comparison);
        return comparison;
    }

    private DisposalScenarioComparison comparison(String comparisonId) {
        DisposalScenarioComparison comparison = webTestClient.get()
            .uri("/api/supply-chain/disposal-scenario-comparisons/{comparisonId}", comparisonId)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(DisposalScenarioComparison.class)
            .returnResult()
            .getResponseBody();
        assertNotNull(comparison);
        return comparison;
    }

    private static DisposalScenarioCandidate candidate(DisposalScenarioComparison comparison, String candidateId) {
        return comparison.candidates()
            .stream()
            .filter(item -> item.candidateId().equals(candidateId))
            .findFirst()
            .orElseThrow();
    }

    private DisposalPlan selectedPlanFromComparison() {
        DisposalScenarioComparison comparison = createReplenishmentComparison();
        String candidateId = comparison.recommendation().recommendedCandidateId();
        assertNotNull(candidateId);
        DisposalPlan plan = webTestClient.post()
            .uri("/api/supply-chain/disposal-scenario-comparisons/{comparisonId}/candidates/{candidateId}/disposal-plans",
                    comparison.comparisonId(), candidateId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("title", "决策提案沙箱方案", "generateExplanation", false))
            .exchange()
            .expectStatus()
            .isCreated()
            .expectBody(DisposalPlan.class)
            .returnResult()
            .getResponseBody();
        assertNotNull(plan);
        assertFalse(plan.recommendations().isEmpty());

        DisposalPlan selected = webTestClient.patch()
            .uri("/api/supply-chain/disposal-plans/{planId}/status", plan.planId())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("status", "SELECTED", "selectedRecommendationId",
                    plan.recommendations().get(0).recommendationId()))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(DisposalPlan.class)
            .returnResult()
            .getResponseBody();
        assertNotNull(selected);
        assertEquals("SELECTED", selected.status().name());
        return selected;
    }

    private static long count(List<SupplyChainTaskEvent> events, String type, String stage) {
        return events.stream().filter(event -> type.equals(event.type())).filter(event -> stage.equals(event.stage())).count();
    }

    private int executionRows() {
        Integer rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chainpilot_disposal_execution",
                Integer.class);
        return rows == null ? 0 : rows;
    }

    private int planRows() {
        Integer rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chainpilot_disposal_plan", Integer.class);
        return rows == null ? 0 : rows;
    }

    private int comparisonRows() {
        Integer rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chainpilot_disposal_comparison",
                Integer.class);
        return rows == null ? 0 : rows;
    }

    private int proposalRows() {
        Integer rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chainpilot_action_proposal", Integer.class);
        return rows == null ? 0 : rows;
    }

    private enum Mode {
        NARRATIVE, FAIL, ANALYSIS
    }

    private record FakeRequest(JsonNode body) {

        boolean hasTools() {
            return body.path("tools").isArray() && !body.path("tools").isEmpty();
        }

        JsonNode messages() {
            return body.path("messages");
        }

        boolean hasToolMessages() {
            for (JsonNode message : body.path("messages")) {
                if ("tool".equals(message.path("role").asText())) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class LocalFakeModel {

        private final HttpServer server;

        private final ExecutorService executor;

        private final ObjectMapper mapper = new ObjectMapper();

        private final List<FakeRequest> requests = new CopyOnWriteArrayList<>();

        private final AtomicInteger responseSequence = new AtomicInteger();

        private volatile Mode mode = Mode.NARRATIVE;

        private LocalFakeModel(HttpServer server, ExecutorService executor) {
            this.server = server;
            this.executor = executor;
        }

        static LocalFakeModel start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "decision-fake-model");
                    thread.setDaemon(true);
                    return thread;
                });
                LocalFakeModel fake = new LocalFakeModel(server, executor);
                server.createContext("/v1/chat/completions", fake::handle);
                server.setExecutor(executor);
                server.start();
                return fake;
            }
            catch (IOException exception) {
                throw new IllegalStateException("Unable to start the decision fake model", exception);
            }
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void reset(Mode nextMode) {
            requests.clear();
            mode = nextMode;
        }

        List<FakeRequest> requests() {
            return List.copyOf(requests);
        }

        void stop() {
            server.stop(0);
            executor.shutdownNow();
        }

        private void handle(HttpExchange exchange) throws IOException {
            try {
                if (!"POST".equals(exchange.getRequestMethod())
                        || !"/v1/chat/completions".equals(exchange.getRequestURI().getPath())) {
                    send(exchange, 404, mapper.writeValueAsBytes(Map.of("error", "not found")));
                    return;
                }
                JsonNode request = mapper.readTree(exchange.getRequestBody());
                FakeRequest recorded = new FakeRequest(request);
                requests.add(recorded);
                if (mode == Mode.FAIL) {
                    send(exchange, 400, mapper.writeValueAsBytes(Map.of("error",
                            Map.of("message", "simulated narrative failure", "type", "invalid_request_error"))));
                    return;
                }
                JsonNode response = mode == Mode.ANALYSIS ? analysisResponse(recorded) : narrativeResponse(recorded);
                send(exchange, 200, mapper.writeValueAsBytes(response));
            }
            catch (RuntimeException exception) {
                send(exchange, 500, mapper.writeValueAsBytes(Map.of("error", "decision fake model failure")));
            }
            finally {
                exchange.close();
            }
        }

        private JsonNode narrativeResponse(FakeRequest request) {
            if (request.hasTools()) {
                return completion("DECISION_MODEL_OK 仅引用确定性工具结果。");
            }
            return completion("DECISION_MODEL_OK 请查看确定性证据中的候选编号、排名与指标。");
        }

        private JsonNode analysisResponse(FakeRequest request) {
            if (!request.hasTools()) {
                return completion("1. 查询订单延期风险\n2. 核对库存与可承诺量\n3. 检查供应商履约风险");
            }
            if (!request.hasToolMessages()) {
                return toolCallResponse("findInventory", "{\"sku\":\"" + SKU + "\"}");
            }
            return analysisNarrative("ANALYSIS_MODEL_OK");
        }

        private JsonNode analysisNarrative(String overview) {
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
            return completion(root.toString());
        }

        private JsonNode toolCallResponse(String name, String arguments) {
            ObjectNode root = baseCompletion(null);
            ObjectNode choice = (ObjectNode) root.path("choices").get(0);
            choice.put("finish_reason", "tool_calls");
            ObjectNode message = (ObjectNode) choice.path("message");
            message.putNull("content");
            ObjectNode toolCall = message.putArray("tool_calls").addObject();
            toolCall.put("id", "call_decision_inventory_1");
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
            root.put("id", "chatcmpl-decision-" + responseSequence.incrementAndGet());
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

        private void send(HttpExchange exchange, int status, byte[] body) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class LocalFakeModelConfiguration {

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
