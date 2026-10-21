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
package com.alibaba.cloud.ai.dataagent.supplychain.mysql;

import com.alibaba.cloud.ai.dataagent.DataAgentApplication;
import com.alibaba.cloud.ai.dataagent.service.aimodelconfig.AiModelRegistry;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DecisionProposal;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DecisionTaskResult;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalActionType;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalPlan;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalPlanStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalRecommendation;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioCandidate;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioComparison;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioComparisonOutcome;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioComparisonPage;
import com.alibaba.cloud.ai.dataagent.supplychain.model.InventoryPosition;
import com.alibaba.cloud.ai.dataagent.supplychain.model.ProposalStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskAnalysisReport;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxDraftType;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxExecution;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxExecutionEligibility;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxExecutionPage;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxExecutionStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxStepStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxTaskDraft;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplierRiskProfile;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainEventType;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainOrder;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskEvent;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskSummary;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.DecisionProposalRepository;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.DisposalExecutionRepository;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.DisposalPlanRepository;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.DisposalScenarioComparisonRepository;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.SupplyChainEventRepository;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.SupplyChainTaskRepository;
import com.alibaba.cloud.ai.dataagent.supplychain.service.SupplyChainQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Full production Spring Boot HTTP chain against real MySQL 8: submit, asynchronous StateGraph
 * execution, repository persistence, history/report/event reads and a rebuilt
 * connection/repository. The production {@link DataAgentApplication} context is started on a
 * random port; optional DataAgent infrastructure that is irrelevant to this chain is isolated with
 * test properties (in-memory graph checkpoint, no SQL initialization and no vector-store
 * serialization in the source tree). The model registry is replaced only at that boundary so the
 * deterministic planner and rule-based report are used without any external model call. Opt-in and
 * isolated exactly like {@link SupplyChainMySqlPersistenceIT}; there is no H2 fallback and ordinary
 * builds never start MySQL.
 */
@EnabledIfSystemProperty(named = SupplyChainMySqlTestSupport.ENABLE_PROPERTY, matches = "true")
@SpringBootTest(classes = DataAgentApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                "spring.sql.init.mode=never",
                "spring.ai.alibaba.data-agent.checkpoint.type=memory",
                "spring.ai.chat.memory.repository.jdbc.initialize-schema=never",
                "spring.ai.alibaba.data-agent.vector-store.file-path=target/chainpilot-it-vectorstore.json",
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.reactive.ReactiveSecurityAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.security.reactive.ReactiveUserDetailsServiceAutoConfiguration",
                "logging.level.root=warn",
                "logging.level.com.alibaba.cloud.ai.dataagent.supplychain=info" })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SupplyChainMySqlHttpIntegrationIT {

    private static final Duration TERMINAL_TIMEOUT = Duration.ofSeconds(30);

    private static final String TASK_TABLE = "chainpilot_analysis_task";

    private static final String EVENT_TABLE = "chainpilot_task_event";

    private static final String PLAN_TABLE = "chainpilot_disposal_plan";

    private static final String SIMULATION_TABLE = "chainpilot_disposal_simulation";

    private static final String EXECUTION_TABLE = "chainpilot_disposal_execution";

    private static final String COMPARISON_TABLE = "chainpilot_disposal_comparison";

    private static final String COMPARISON_CANDIDATE_TABLE = "chainpilot_disposal_comparison_candidate";

    private static final String PROPOSAL_TABLE = "chainpilot_action_proposal";

    private static final String SKU = "SKU-MCU-001";

    private static SupplyChainMySqlTestSupport.MySqlTarget mySql;

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private AiModelRegistry aiModelRegistry;

    @Autowired
    private SupplyChainQueryService queryService;

    @DynamicPropertySource
    static void mySqlProperties(DynamicPropertyRegistry registry) {
        mySql = SupplyChainMySqlTestSupport.start();
        registry.add("spring.datasource.url", mySql::jdbcUrl);
        registry.add("spring.datasource.username", mySql::username);
        registry.add("spring.datasource.password", mySql::password);
    }

    @BeforeAll
    void verifyDedicatedMySqlAndNoChatModelConfiguration() throws Exception {
        try (Connection connection = jdbcTemplate.getDataSource().getConnection()) {
            assertEquals("MySQL", connection.getMetaData().getDatabaseProductName());
            assertTrue(connection.getMetaData().getURL().startsWith("jdbc:mysql:"));
        }
        assertEquals(SupplyChainMySqlTestSupport.DEDICATED_DATABASE,
                jdbcTemplate.queryForObject("SELECT DATABASE()", String.class));
        Integer modelConfigTables = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='model_config'", Integer.class);
        assertEquals(0, modelConfigTables == null ? 0 : modelConfigTables.intValue(),
                "the dedicated MySQL test database must not contain a DataAgent model configuration");
    }

    @BeforeEach
    void clearCommittedChainPilotRows() {
        jdbcTemplate.update("DELETE FROM " + EXECUTION_TABLE);
        jdbcTemplate.update("DELETE FROM " + COMPARISON_CANDIDATE_TABLE);
        jdbcTemplate.update("DELETE FROM " + COMPARISON_TABLE);
        jdbcTemplate.update("DELETE FROM " + PLAN_TABLE);
        jdbcTemplate.update("DELETE FROM " + SIMULATION_TABLE);
        jdbcTemplate.update("DELETE FROM " + EVENT_TABLE);
        jdbcTemplate.update("DELETE FROM " + PROPOSAL_TABLE);
        jdbcTemplate.update("DELETE FROM " + TASK_TABLE);
        clearInvocations(aiModelRegistry);
        when(aiModelRegistry.getChatClient()).thenThrow(
                new IllegalStateException("External model calls are disabled in HTTP integration tests"));
    }

    @AfterAll
    void stopMySql() {
        if (mySql != null) {
            mySql.close();
        }
    }

    @Test
    void fullHttpChainPersistsTaskReportEventsAndHistoryInMySql() {
        String question = "分析今天有哪些订单可能延期";
        String taskId = submit(question);

        SupplyChainTaskStatus completed = awaitTerminal(taskId);
        assertEquals("COMPLETED", completed.status());
        assertEquals("DONE", completed.currentStep());
        assertEquals(taskId, status(taskId).taskId());

        Map<String, Object> taskRow = jdbcTemplate.queryForMap("SELECT question, status, current_step, updated_at, "
                + "report_json FROM " + TASK_TABLE + " WHERE task_id=?", taskId);
        assertEquals(question, taskRow.get("question"));
        assertEquals("COMPLETED", taskRow.get("status"));
        assertEquals("DONE", taskRow.get("current_step"));
        assertEquals(completed.updatedAt().toEpochMilli(), ((Number) taskRow.get("updated_at")).longValue());
        assertNotNull(taskRow.get("report_json"));

        RiskAnalysisReport report = report(taskId);
        assertEquals(taskId, report.taskId());
        assertEquals("COMPLETED", report.status());
        assertFalse(report.summary().isBlank());
        assertFalse(report.executionPlan().isEmpty());
        assertFalse(report.atRiskOrders().isEmpty());
        assertEquals(queryService.findAtRiskOrders(), report.atRiskOrders(),
                "the report must match the deterministic business tool output");
        assertFalse(report.plannerAssisted());
        assertFalse(report.modelAssisted());
        assertEquals(List.of(), report.toolCalls());
        assertEquals(report, storedReport(taskRow.get("report_json")),
                "the HTTP report must be the report committed to MySQL");

        HistoryPage history = history("?status=COMPLETED&page=1&pageSize=10");
        assertEquals(1, history.totalElements());
        assertEquals(1, history.totalPages());
        assertEquals(1, history.items().size());
        SupplyChainTaskSummary summary = history.items().get(0);
        assertEquals(taskId, summary.taskId());
        assertEquals(question, summary.question());
        assertEquals("COMPLETED", summary.status());
        assertEquals("DONE", summary.currentStep());
        assertEquals(completed.updatedAt(), summary.updatedAt());
        assertTrue(summary.reportAvailable());

        List<SupplyChainTaskEvent> events = events(taskId);
        assertFalse(events.isEmpty());
        assertEquals(SupplyChainEventType.TASK_QUEUED, events.get(0).type());
        assertEquals(SupplyChainEventType.TASK_COMPLETED, events.get(events.size() - 1).type());
        for (int index = 0; index < events.size(); index++) {
            SupplyChainTaskEvent event = events.get(index);
            assertEquals(index + 1, event.sequence());
            assertEquals(taskId, event.taskId());
            assertNotNull(event.occurredAt());
        }
        assertTrue(events.stream().anyMatch(event -> SupplyChainEventType.TASK_STARTED.equals(event.type())));
        assertEquals(Set.of("PLANNING", "QUERYING_ORDERS", "QUERYING_INVENTORY", "QUERYING_SUPPLIERS",
                "VALIDATING", "GENERATING_REPORT"), events.stream()
            .filter(event -> SupplyChainEventType.NODE_COMPLETED.equals(event.type()))
            .map(SupplyChainTaskEvent::stage)
            .collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of("findAtRiskOrders", "findInventory", "findSuppliers"), events.stream()
            .filter(event -> SupplyChainEventType.TOOL_COMPLETED.equals(event.type()))
            .map(SupplyChainTaskEvent::stage)
            .collect(java.util.stream.Collectors.toSet()));
        assertEquals(2, events.stream().filter(event -> SupplyChainEventType.MODEL_FAILED.equals(event.type())).count());
        assertEquals(2, events.stream().filter(event -> SupplyChainEventType.FALLBACK_USED.equals(event.type())).count());
        assertFalse(events.stream().anyMatch(event -> SupplyChainEventType.MODEL_COMPLETED.equals(event.type())));
        assertTrue(events.stream().noneMatch(event -> SupplyChainEventType.NODE_FAILED.equals(event.type())
                || SupplyChainEventType.TASK_FAILED.equals(event.type())));
        verify(aiModelRegistry, atLeast(2)).getChatClient();

        Integer persistedEvents = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + EVENT_TABLE
                + " WHERE task_id=?", Integer.class, taskId);
        assertEquals(events.size(), persistedEvents.intValue());
        List<Integer> persistedSequences = jdbcTemplate.queryForList("SELECT sequence_no FROM " + EVENT_TABLE
                + " WHERE task_id=? ORDER BY sequence_no", Integer.class, taskId);
        assertEquals(events.stream().map(SupplyChainTaskEvent::sequence).toList(), persistedSequences);
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + EVENT_TABLE + " WHERE task_id<>?",
                Integer.class, taskId));
    }

    @Test
    void historyFilteringAndPaginationRunInMySqlAndKeepTheResponseContract() {
        String first = submit("分析延期订单");
        String second = submit("检查库存短缺");
        String third = submit("检查供应商风险");
        awaitTerminal(first);
        awaitTerminal(second);
        awaitTerminal(third);

        HistoryPage firstPage = history("?page=1&pageSize=2");
        assertEquals(3, firstPage.totalElements());
        assertEquals(2, firstPage.totalPages());
        assertEquals(1, firstPage.page());
        assertEquals(2, firstPage.pageSize());
        assertEquals(2, firstPage.items().size());

        HistoryPage secondPage = history("?page=2&pageSize=2");
        assertEquals(3, secondPage.totalElements());
        assertEquals(2, secondPage.totalPages());
        assertEquals(2, secondPage.page());
        assertEquals(1, secondPage.items().size());

        Set<String> pagedIds = new HashSet<>();
        firstPage.items().forEach(item -> pagedIds.add(item.taskId()));
        secondPage.items().forEach(item -> pagedIds.add(item.taskId()));
        assertEquals(Set.of(first, second, third), pagedIds);
        assertEquals(taskIds(firstPage.items()), jdbcTemplate.queryForList("SELECT task_id FROM " + TASK_TABLE
                + " ORDER BY updated_at DESC, task_id DESC LIMIT 2 OFFSET 0", String.class));
        assertEquals(taskIds(secondPage.items()), jdbcTemplate.queryForList("SELECT task_id FROM " + TASK_TABLE
                + " ORDER BY updated_at DESC, task_id DESC LIMIT 2 OFFSET 2", String.class));
        assertFalse(secondPage.items().get(0).updatedAt()
            .isAfter(firstPage.items().get(firstPage.items().size() - 1).updatedAt()));

        HistoryPage beyondLastPage = history("?page=3&pageSize=2");
        assertTrue(beyondLastPage.items().isEmpty());
        assertEquals(3, beyondLastPage.totalElements());
        assertEquals(2, beyondLastPage.totalPages());

        HistoryPage completedOnly = history("?status=COMPLETED&page=1&pageSize=2");
        assertEquals(3, completedOnly.totalElements());
        assertTrue(completedOnly.items().stream().allMatch(item -> "COMPLETED".equals(item.status())));
        assertTrue(completedOnly.items().stream().allMatch(SupplyChainTaskSummary::reportAvailable));

        HistoryPage failedOnly = history("?status=FAILED&page=1&pageSize=2");
        assertTrue(failedOnly.items().isEmpty());
        assertEquals(0, failedOnly.totalElements());
        assertEquals(0, failedOnly.totalPages());

        HistoryPage legacyLimit = history("?limit=2");
        assertEquals(2, legacyLimit.items().size());
        assertEquals(2, legacyLimit.pageSize());
        assertEquals(3, legacyLimit.totalElements());
        assertEquals(2, legacyLimit.totalPages());
    }

    @Test
    void rebuiltJdbcConnectionAndRepositoriesReadTheCommittedHttpResults() {
        String taskId = submit("重建连接后读取已提交结果");
        SupplyChainTaskStatus completed = awaitTerminal(taskId);
        RiskAnalysisReport httpReport = report(taskId);
        List<SupplyChainTaskEvent> httpEvents = events(taskId);
        SupplyChainTaskSummary httpSummary = history("?page=1&pageSize=10").items().get(0);

        DriverManagerDataSource rebuiltDataSource = new DriverManagerDataSource(mySql.jdbcUrl(), mySql.username(),
                mySql.password());
        JdbcTemplate rebuiltJdbc = new JdbcTemplate(rebuiltDataSource);
        assertEquals(SupplyChainMySqlTestSupport.DEDICATED_DATABASE,
                rebuiltJdbc.queryForObject("SELECT DATABASE()", String.class));
        SupplyChainTaskRepository rebuiltTasks = new SupplyChainTaskRepository(rebuiltJdbc,
                new ObjectMapper().findAndRegisterModules());
        SupplyChainEventRepository rebuiltEvents = new SupplyChainEventRepository(rebuiltJdbc);

        assertEquals(completed, rebuiltTasks.findTask(taskId).orElseThrow());
        assertEquals(httpReport, rebuiltTasks.findReport(taskId).orElseThrow());
        assertEquals(httpSummary, rebuiltTasks.findHistory(null, 10, 0).get(0));
        assertEquals(1, rebuiltTasks.countTasks(null));
        assertEquals(httpEvents, rebuiltEvents.findByTaskId(taskId));
        assertEquals(1, rebuiltTasks.countTasks("COMPLETED"));
    }

    @Test
    void sandboxRehearsalPersistsThroughHttpInMySqlAndSurvivesRepositoryRecreation() {
        DisposalPlan plan = select(createReplenishmentPlan(), DisposalActionType.REPLENISH_INVENTORY);
        List<InventoryPosition> inventoryBefore = queryService.findInventory(null);
        List<SupplyChainOrder> ordersBefore = queryService.findAllOrders();
        List<SupplierRiskProfile> suppliersBefore = queryService.findSuppliers();

        SandboxExecutionEligibility eligibility = eligibility(plan.planId());
        assertTrue(eligibility.eligible());
        assertEquals(plan.simulation().simulationId(), eligibility.sourceSimulationId());

        SandboxExecution execution = start(plan.planId(), "mysql-rehearsal-1", 201);
        assertEquals(SandboxExecutionStatus.SUCCEEDED, execution.status());
        assertEquals(plan.planId(), execution.disposalPlanId());
        assertEquals(plan.selectedRecommendationId(), execution.recommendationId());
        assertEquals(plan.simulation().simulationId(), execution.sourceSimulationId());
        assertEquals("mysql-rehearsal-1", execution.idempotencyKey());
        assertFalse(execution.realDataMutated());
        assertNull(execution.failureReason());
        assertEquals(5, execution.steps().size());
        assertTrue(execution.steps().stream().allMatch(step -> step.status() == SandboxStepStatus.SUCCEEDED));
        SandboxTaskDraft draft = execution.taskDraft();
        assertNotNull(draft);
        assertEquals(SandboxDraftType.REPLENISHMENT_TASK_DRAFT, draft.draftType());
        assertEquals(100, draft.replenishmentQuantity());
        assertEquals(List.of("ORD-1001"), draft.linkedOrderIds());
        assertFalse(execution.evidence().isEmpty());

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT disposal_plan_id, source_simulation_id, "
                + "mode, status, idempotency_key, real_data_mutated, steps_json, task_draft_json, final_result "
                + "FROM " + EXECUTION_TABLE + " WHERE execution_id=?", execution.executionId());
        assertEquals(plan.planId(), row.get("disposal_plan_id"));
        assertEquals(plan.simulation().simulationId(), row.get("source_simulation_id"));
        assertEquals("SANDBOX", row.get("mode"));
        assertEquals("SUCCEEDED", row.get("status"));
        assertEquals("mysql-rehearsal-1", row.get("idempotency_key"));
        assertEquals(0, ((Number) row.get("real_data_mutated")).intValue());
        assertNotNull(row.get("steps_json"));
        assertNotNull(row.get("task_draft_json"));
        assertEquals(execution.finalResult(), row.get("final_result"));

        SandboxExecution replay = start(plan.planId(), "mysql-rehearsal-1", 200);
        assertEquals(execution.executionId(), replay.executionId());
        assertEquals(1, executionCount());
        webTestClient.post()
            .uri("/api/supply-chain/disposal-plans/{planId}/executions", plan.planId())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("idempotencyKey", "mysql-rehearsal-2"))
            .exchange()
            .expectStatus()
            .isEqualTo(409);
        assertEquals(1, executionCount());

        assertEquals(execution, execution(execution.executionId()));
        SandboxExecutionPage page = executions(plan.planId());
        assertEquals(1, page.totalElements());
        assertEquals(1, page.items().size());
        assertEquals(draft.taskDraftId(), page.items().get(0).taskDraftId());
        assertEquals(5, page.items().get(0).stepCount());

        DriverManagerDataSource rebuiltDataSource = new DriverManagerDataSource(mySql.jdbcUrl(), mySql.username(),
                mySql.password());
        DisposalExecutionRepository rebuiltExecutions = new DisposalExecutionRepository(
                new JdbcTemplate(rebuiltDataSource), new ObjectMapper().findAndRegisterModules());
        assertEquals(execution, rebuiltExecutions.find(execution.executionId()).orElseThrow());
        assertEquals(1, rebuiltExecutions.count(plan.planId()));

        DisposalPlan persistedPlan = webTestClient.get()
            .uri("/api/supply-chain/disposal-plans/{planId}", plan.planId())
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(DisposalPlan.class)
            .returnResult()
            .getResponseBody();
        assertNotNull(persistedPlan);
        assertEquals(DisposalPlanStatus.SELECTED, persistedPlan.status());
        assertEquals(plan.selectedRecommendationId(), persistedPlan.selectedRecommendationId());
        assertEquals(plan.simulation().simulationId(), persistedPlan.simulation().simulationId());

        assertEquals(inventoryBefore, queryService.findInventory(null));
        assertEquals(ordersBefore, queryService.findAllOrders());
        assertEquals(suppliersBefore, queryService.findSuppliers());
    }

    @Test
    void comparisonPersistsSharedBaselineCandidatesAndContinuesThroughSelectedPlanAndSandbox() {
        List<InventoryPosition> inventoryBefore = queryService.findInventory(null);
        List<SupplyChainOrder> ordersBefore = queryService.findAllOrders();
        List<SupplierRiskProfile> suppliersBefore = queryService.findSuppliers();

        DisposalScenarioComparison comparison = createComparison(50, 100, 200);
        assertEquals(SKU, comparison.sku());
        assertNull(comparison.supplierId());
        assertEquals(3, comparison.candidates().size());
        assertEquals(3, comparison.rankings().size());
        assertEquals(9, comparison.rankingRules().size());
        assertEquals(64, comparison.baselineFingerprint().length());
        assertEquals(DisposalScenarioComparisonOutcome.RECOMMENDED, comparison.recommendation().outcome());
        assertFalse(comparison.realDataMutated());
        DisposalScenarioCandidate recommended = comparison.candidates()
            .stream()
            .filter(candidate -> candidate.candidateId().equals(comparison.recommendation().recommendedCandidateId()))
            .findFirst()
            .orElseThrow();
        assertEquals(comparison.recommendation().recommendedLabel(), recommended.label());
        for (int index = 1; index < comparison.candidates().size(); index++) {
            DisposalScenarioCandidate candidate = comparison.candidates().get(index);
            assertEquals(comparison.candidates().get(0).simulation().baseline(),
                    candidate.simulation().baseline(), "all candidates must share one baseline");
            assertNotEquals(comparison.candidates().get(0).simulation().simulationId(),
                    candidate.simulation().simulationId());
        }

        Map<String, Object> comparisonRow = jdbcTemplate.queryForMap("SELECT baseline_fingerprint, candidate_count, "
                + "outcome, recommended_candidate_id, baseline_json, rankings_json, recommendation_json, "
                + "real_data_mutated FROM " + COMPARISON_TABLE + " WHERE comparison_id=?",
                comparison.comparisonId());
        assertEquals(comparison.baselineFingerprint(), comparisonRow.get("baseline_fingerprint"));
        assertEquals(3, ((Number) comparisonRow.get("candidate_count")).intValue());
        assertEquals("RECOMMENDED", comparisonRow.get("outcome"));
        assertEquals(recommended.candidateId(), comparisonRow.get("recommended_candidate_id"));
        assertEquals(0, ((Number) comparisonRow.get("real_data_mutated")).intValue());
        assertNotNull(comparisonRow.get("baseline_json"));
        assertNotNull(comparisonRow.get("rankings_json"));
        assertNotNull(comparisonRow.get("recommendation_json"));
        Integer candidateRows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + COMPARISON_CANDIDATE_TABLE
                + " WHERE comparison_id=?", Integer.class, comparison.comparisonId());
        assertNotNull(candidateRows);
        assertEquals(3, candidateRows.intValue());

        DisposalPlan plan = webTestClient.post()
            .uri("/api/supply-chain/disposal-scenario-comparisons/{comparisonId}/candidates/{candidateId}"
                    + "/disposal-plans", comparison.comparisonId(), recommended.candidateId())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("title", "MySQL 对比选定方案", "generateExplanation", false))
            .exchange()
            .expectStatus()
            .isCreated()
            .expectBody(DisposalPlan.class)
            .returnResult()
            .getResponseBody();
        assertNotNull(plan);
        assertEquals(SKU, plan.scopeSku());
        assertEquals(recommended.simulation().simulated(), plan.simulation().simulated());
        assertNotEquals(recommended.simulation().simulationId(), plan.simulation().simulationId(),
                "the plan must copy the candidate simulation instead of reusing it");
        webTestClient.post()
            .uri("/api/supply-chain/disposal-scenario-comparisons/{comparisonId}/candidates/{candidateId}"
                    + "/disposal-plans", comparison.comparisonId(), recommended.candidateId())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("title", "重复保存", "generateExplanation", false))
            .exchange()
            .expectStatus()
            .isEqualTo(409);

        DisposalPlan selected = select(plan, DisposalActionType.REPLENISH_INVENTORY);
        SandboxExecution execution = start(selected.planId(), "mysql-comparison-1", 201);
        assertEquals(SandboxExecutionStatus.SUCCEEDED, execution.status());
        assertEquals(plan.planId(), execution.disposalPlanId());
        assertFalse(execution.realDataMutated());

        DisposalScenarioComparison refreshed = comparisonDetail(comparison.comparisonId());
        assertEquals(plan.planId(), refreshed.candidates()
            .stream()
            .filter(candidate -> candidate.candidateId().equals(recommended.candidateId()))
            .findFirst()
            .orElseThrow()
            .linkedPlanId());

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
        assertEquals(1, page.items().get(0).linkedPlanCount());
        assertEquals(recommended.candidateId(), page.items().get(0).recommendedCandidateId());

        DriverManagerDataSource rebuiltDataSource = new DriverManagerDataSource(mySql.jdbcUrl(), mySql.username(),
                mySql.password());
        DisposalScenarioComparisonRepository rebuiltComparisons = new DisposalScenarioComparisonRepository(
                new JdbcTemplate(rebuiltDataSource), new ObjectMapper().findAndRegisterModules());
        assertEquals(refreshed, rebuiltComparisons.find(comparison.comparisonId()).orElseThrow());
        assertEquals(1, rebuiltComparisons.count());

        assertEquals(inventoryBefore, queryService.findInventory(null));
        assertEquals(ordersBefore, queryService.findAllOrders());
        assertEquals(suppliersBefore, queryService.findSuppliers());
    }

    @Test
    void decisionComparisonSaveProposalCreatesOneLinkedPlanOnlyAfterConfirmationInMySql() {
        List<InventoryPosition> inventoryBefore = queryService.findInventory(null);
        List<SupplyChainOrder> ordersBefore = queryService.findAllOrders();
        List<SupplierRiskProfile> suppliersBefore = queryService.findSuppliers();

        String comparisonTaskId = submitDecision("比较 " + SKU + " 补货 50、100、200 件的方案");
        assertEquals("COMPLETED", awaitTerminal(comparisonTaskId).status());
        DecisionTaskResult comparisonDecision = decision(comparisonTaskId);
        assertEquals("COMPARE_REPLENISHMENT", comparisonDecision.intent());
        DisposalScenarioComparison comparison = comparisonDecision.comparison();
        assertNotNull(comparison);
        assertEquals(3, comparison.candidates().size());
        assertEquals(1, comparisonCount());
        assertEquals(0, planCount());
        assertEquals(0, proposalCount());
        String comparisonId = comparison.comparisonId();
        String candidateId = comparison.recommendation().recommendedCandidateId();
        assertNotNull(candidateId);
        DisposalScenarioCandidate sourceCandidate = candidate(comparison, candidateId);
        assertNull(sourceCandidate.linkedPlanId());
        assertEquals(100, sourceCandidate.simulation().replenishmentQuantity());

        String proposalTaskId = submitDecision("将对比 " + comparisonId + " 的候选 "
                + candidateId + " 保存为处置方案");
        assertEquals("COMPLETED", awaitTerminal(proposalTaskId).status());
        DecisionTaskResult proposalDecision = decision(proposalTaskId);
        assertEquals("PROPOSE_SAVE_PLAN", proposalDecision.intent());
        assertEquals(1, proposalDecision.proposals().size());
        DecisionProposal proposal = proposalDecision.proposals().get(0);
        assertEquals(ProposalStatus.PENDING, proposal.status());
        assertEquals("SAVE_CANDIDATE_AS_PLAN", proposal.actionType().name());
        assertEquals(candidateId, proposal.targetId());
        assertEquals(0, planCount(), "proposal creation must not persist a plan before confirmation");
        assertEquals(0, executionCount());
        assertEquals(1, proposalCount());
        assertNull(candidate(comparisonDetail(comparisonId), candidateId).linkedPlanId());

        webTestClient.post()
            .uri("/api/supply-chain/action-proposals/{proposalId}/confirm", proposal.proposalId())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("contentHash", "wrong-hash"))
            .exchange()
            .expectStatus()
            .isBadRequest();
        assertEquals(0, planCount());

        DecisionProposal confirmed = confirm(proposal);
        assertEquals(ProposalStatus.CONFIRMED, confirmed.status());
        assertNotNull(confirmed.confirmedAt());
        String planId = (String) confirmed.result().get("planId");
        assertNotNull(planId);
        assertEquals(Boolean.FALSE, confirmed.result().get("realDataMutated"));
        assertEquals(1, planCount());
        assertEquals(0, executionCount(), "confirming a saved plan must not start a sandbox execution");

        DisposalPlan plan = disposalPlan(planId);
        assertEquals(DisposalPlanStatus.PENDING_CONFIRMATION, plan.status());
        assertEquals(proposalTaskId, plan.taskId());
        assertEquals(SKU, plan.scopeSku());
        assertFalse(plan.recommendations().isEmpty());
        assertNotNull(plan.simulation());
        assertEquals(sourceCandidate.simulation().replenishmentQuantity(),
                plan.simulation().replenishmentQuantity());
        assertEquals(sourceCandidate.simulation().simulated(), plan.simulation().simulated());
        assertNotEquals(sourceCandidate.simulation().simulationId(), plan.simulation().simulationId(),
                "the plan must persist its own simulation copy");
        assertFalse(plan.simulation().realDataMutated());
        assertEquals(planId, candidate(comparisonDetail(comparisonId), candidateId).linkedPlanId());

        DecisionProposal replay = confirm(proposal);
        assertEquals(planId, replay.result().get("planId"));
        assertEquals(1, planCount(), "duplicate confirmation must not create a second plan");
        assertEquals(1, proposalCount());

        Map<String, Object> proposalRow = jdbcTemplate.queryForMap("SELECT status, confirmed_at, result_json "
                + "FROM " + PROPOSAL_TABLE + " WHERE proposal_id=?", proposal.proposalId());
        assertEquals("CONFIRMED", proposalRow.get("status"));
        assertNotNull(proposalRow.get("confirmed_at"));
        assertNotNull(proposalRow.get("result_json"));
        Map<String, Object> planRow = jdbcTemplate.queryForMap("SELECT task_id, status, simulation_json "
                + "FROM " + PLAN_TABLE + " WHERE plan_id=?", planId);
        assertEquals(proposalTaskId, planRow.get("task_id"));
        assertEquals("PENDING_CONFIRMATION", planRow.get("status"));
        assertNotNull(planRow.get("simulation_json"));
        assertEquals(planId, jdbcTemplate.queryForObject("SELECT linked_plan_id FROM "
                + COMPARISON_CANDIDATE_TABLE + " WHERE comparison_id=? AND candidate_id=?",
                String.class, comparisonId, candidateId));

        DriverManagerDataSource rebuiltDataSource = new DriverManagerDataSource(mySql.jdbcUrl(), mySql.username(),
                mySql.password());
        ObjectMapper rebuiltMapper = new ObjectMapper().findAndRegisterModules();
        DisposalPlanRepository rebuiltPlans = new DisposalPlanRepository(
                new JdbcTemplate(rebuiltDataSource), rebuiltMapper);
        DecisionProposalRepository rebuiltProposals = new DecisionProposalRepository(
                new JdbcTemplate(rebuiltDataSource), rebuiltMapper);
        assertEquals(plan, rebuiltPlans.find(planId).orElseThrow());
        assertEquals(confirmed, rebuiltProposals.find(proposal.proposalId()).orElseThrow());
        assertEquals(1, rebuiltPlans.count(null));

        assertEquals(inventoryBefore, queryService.findInventory(null));
        assertEquals(ordersBefore, queryService.findAllOrders());
        assertEquals(suppliersBefore, queryService.findSuppliers());
    }

    private DisposalScenarioComparison createComparison(int... quantities) {
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (int quantity : quantities) {
            candidates.add(Map.of("replenishmentQuantity", quantity));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "MySQL 多方案对比");
        body.put("scenario", "INVENTORY_REPLENISHMENT");
        body.put("sku", SKU);
        body.put("candidates", candidates);
        DisposalScenarioComparison comparison = webTestClient.post()
            .uri("/api/supply-chain/disposal-scenario-comparisons")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()
            .expectStatus()
            .isCreated()
            .expectBody(DisposalScenarioComparison.class)
            .returnResult()
            .getResponseBody();
        assertNotNull(comparison);
        return comparison;
    }

    private DisposalScenarioComparison comparisonDetail(String comparisonId) {
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

    private DisposalPlan createReplenishmentPlan() {
        return createPlan("MCU 补货演练", SKU, null,
                Map.of("scenario", "INVENTORY_REPLENISHMENT", "sku", SKU, "replenishmentQuantity", 100));
    }

    private DisposalPlan createPlan(String title, String sku, String supplierId, Map<String, Object> simulation) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        if (sku != null) {
            body.put("sku", sku);
        }
        if (supplierId != null) {
            body.put("supplierId", supplierId);
        }
        body.put("generateExplanation", false);
        if (simulation != null) {
            body.put("simulation", simulation);
        }
        DisposalPlan plan = webTestClient.post()
            .uri("/api/supply-chain/disposal-plans")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()
            .expectStatus()
            .isCreated()
            .expectBody(DisposalPlan.class)
            .returnResult()
            .getResponseBody();
        assertNotNull(plan);
        return plan;
    }

    private DisposalPlan select(DisposalPlan plan, DisposalActionType actionType) {
        DisposalRecommendation recommendation = plan.recommendations()
            .stream()
            .filter(item -> item.actionType() == actionType)
            .findFirst()
            .orElseThrow();
        DisposalPlan selected = webTestClient.patch()
            .uri("/api/supply-chain/disposal-plans/{planId}/status", plan.planId())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("status", "SELECTED", "selectedRecommendationId",
                    recommendation.recommendationId()))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(DisposalPlan.class)
            .returnResult()
            .getResponseBody();
        assertNotNull(selected);
        assertEquals(DisposalPlanStatus.SELECTED, selected.status());
        return selected;
    }

    private SandboxExecutionEligibility eligibility(String planId) {
        SandboxExecutionEligibility eligibility = webTestClient.get()
            .uri("/api/supply-chain/disposal-plans/{planId}/execution-eligibility", planId)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(SandboxExecutionEligibility.class)
            .returnResult()
            .getResponseBody();
        assertNotNull(eligibility);
        return eligibility;
    }

    private SandboxExecution start(String planId, String key, int expectedStatus) {
        SandboxExecution execution;
        if (expectedStatus == 200) {
            execution = webTestClient.post()
                .uri("/api/supply-chain/disposal-plans/{planId}/executions", planId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("idempotencyKey", key))
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("X-Idempotent-Replay", "true")
                .expectBody(SandboxExecution.class)
                .returnResult()
                .getResponseBody();
        }
        else {
            execution = webTestClient.post()
                .uri("/api/supply-chain/disposal-plans/{planId}/executions", planId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("idempotencyKey", key))
                .exchange()
                .expectStatus()
                .isEqualTo(expectedStatus)
                .expectBody(SandboxExecution.class)
                .returnResult()
                .getResponseBody();
        }
        assertNotNull(execution);
        return execution;
    }

    private SandboxExecution execution(String executionId) {
        SandboxExecution execution = webTestClient.get()
            .uri("/api/supply-chain/sandbox-executions/{executionId}", executionId)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(SandboxExecution.class)
            .returnResult()
            .getResponseBody();
        assertNotNull(execution);
        return execution;
    }

    private SandboxExecutionPage executions(String planId) {
        SandboxExecutionPage page = webTestClient.get()
            .uri("/api/supply-chain/disposal-plans/{planId}/executions", planId)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(SandboxExecutionPage.class)
            .returnResult()
            .getResponseBody();
        assertNotNull(page);
        return page;
    }

    private int executionCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + EXECUTION_TABLE, Integer.class);
        return count == null ? 0 : count;
    }

    private int planCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + PLAN_TABLE, Integer.class);
        return count == null ? 0 : count;
    }

    private int proposalCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + PROPOSAL_TABLE, Integer.class);
        return count == null ? 0 : count;
    }

    private int comparisonCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + COMPARISON_TABLE, Integer.class);
        return count == null ? 0 : count;
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

    private String submitDecision(String question) {
        SupplyChainTaskStatus accepted = webTestClient.post()
            .uri("/api/supply-chain/analysis/tasks")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("question", question, "mode", "DECISION"))
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

    private DecisionTaskResult decision(String taskId) {
        DecisionTaskResult result = webTestClient.get()
            .uri("/api/supply-chain/analysis/tasks/{taskId}/decision", taskId)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(DecisionTaskResult.class)
            .returnResult()
            .getResponseBody();
        assertNotNull(result);
        return result;
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

    private DisposalPlan disposalPlan(String planId) {
        DisposalPlan plan = webTestClient.get()
            .uri("/api/supply-chain/disposal-plans/{planId}", planId)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(DisposalPlan.class)
            .returnResult()
            .getResponseBody();
        assertNotNull(plan);
        return plan;
    }

    private static DisposalScenarioCandidate candidate(DisposalScenarioComparison comparison, String candidateId) {
        return comparison.candidates()
            .stream()
            .filter(item -> item.candidateId().equals(candidateId))
            .findFirst()
            .orElseThrow();
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

    private HistoryPage history(String query) {
        EntityExchangeResult<List<SupplyChainTaskSummary>> result = webTestClient.get()
            .uri("/api/supply-chain/analysis/tasks" + query)
            .exchange()
            .expectStatus()
            .isOk()
            .expectHeader()
            .exists("X-Total-Count")
            .expectHeader()
            .exists("X-Page")
            .expectHeader()
            .exists("X-Page-Size")
            .expectHeader()
            .exists("X-Total-Pages")
            .expectBodyList(SupplyChainTaskSummary.class)
            .returnResult();
        List<SupplyChainTaskSummary> items = result.getResponseBody();
        assertNotNull(items);
        return new HistoryPage(items, header(result, "X-Total-Count"), header(result, "X-Page"),
                header(result, "X-Page-Size"), header(result, "X-Total-Pages"));
    }

    private static int header(EntityExchangeResult<?> result, String name) {
        String value = result.getResponseHeaders().getFirst(name);
        assertNotNull(value, () -> "Missing response header " + name);
        return Integer.parseInt(value);
    }

    private static RiskAnalysisReport storedReport(Object payload) {
        try {
            return new ObjectMapper().findAndRegisterModules().readValue((String) payload, RiskAnalysisReport.class);
        }
        catch (Exception exception) {
            throw new AssertionError("Stored MySQL report is unreadable", exception);
        }
    }

    private static List<String> taskIds(List<SupplyChainTaskSummary> summaries) {
        return summaries.stream().map(SupplyChainTaskSummary::taskId).toList();
    }

    private record HistoryPage(List<SupplyChainTaskSummary> items, int totalElements, int page, int pageSize,
            int totalPages) {
    }
}
