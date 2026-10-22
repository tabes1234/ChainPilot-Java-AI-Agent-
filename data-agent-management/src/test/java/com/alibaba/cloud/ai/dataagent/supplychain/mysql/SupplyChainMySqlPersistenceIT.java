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

import com.alibaba.cloud.ai.dataagent.supplychain.model.DecisionProposal;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DecisionTaskResult;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalActionType;
import com.alibaba.cloud.ai.dataagent.supplychain.model.ProposalActionType;
import com.alibaba.cloud.ai.dataagent.supplychain.model.ProposalStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskAnalysisReport;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxDraftType;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxExecution;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxExecutionMode;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxExecutionStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxExecutionStep;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxExecutionSummary;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxStepStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxTaskDraft;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainEventStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainEventType;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskEvent;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskPage;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskState;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskSummary;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskType;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.DecisionProposalRepository;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.DisposalExecutionRepository;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.SupplyChainEventRepository;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.SupplyChainTaskRepository;
import com.alibaba.cloud.ai.dataagent.supplychain.service.SupplyChainAgentTools;
import com.alibaba.cloud.ai.dataagent.supplychain.service.SupplyChainAnalysisService;
import com.alibaba.cloud.ai.dataagent.supplychain.service.SupplyChainDecisionService;
import com.alibaba.cloud.ai.dataagent.supplychain.service.SupplyChainEventRecorder;
import com.alibaba.cloud.ai.dataagent.supplychain.service.SupplyChainWorkflowService;
import com.alibaba.cloud.ai.dataagent.supplychain.service.TaskRecoveryCoordinator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Verifies the real ChainPilot SQL against isolated MySQL 8. The test is opt-in because ordinary
 * builds and the H2 suite must not require Docker or a MySQL server. It either starts a temporary
 * container or connects to a pre-provisioned local MySQL when {@code -Dchainpilot.mysql.url} is
 * supplied; the latter must point at the dedicated {@code chainpilot_mysql_it} database.
 */
@EnabledIfSystemProperty(named = SupplyChainMySqlTestSupport.ENABLE_PROPERTY, matches = "true")
class SupplyChainMySqlPersistenceIT {

    private static final String TASK_TABLE = "chainpilot_analysis_task";

    private static final String EVENT_TABLE = "chainpilot_task_event";

    private static final String EXECUTION_TABLE = "chainpilot_disposal_execution";

    private static final String PROPOSAL_TABLE = "chainpilot_action_proposal";

    private static final String DISCLAIMER =
            "本地沙箱执行演练，仅生成任务草稿与审计记录；未修改真实库存、订单、供应商或 ERP 数据。";

    private static SupplyChainMySqlTestSupport.MySqlTarget mySql;

    private static DataSource dataSource;

    private static JdbcTemplate jdbc;

    private static SupplyChainTaskRepository tasks;

    private static SupplyChainEventRepository events;

    private static DisposalExecutionRepository executions;

    private static DecisionProposalRepository proposals;

    private static String jdbcUrl;

    private static String username;

    private static String password;

    @BeforeAll
    static void startIsolatedMySql() {
        mySql = SupplyChainMySqlTestSupport.start();
        jdbcUrl = mySql.jdbcUrl();
        username = mySql.username();
        password = mySql.password();
        dataSource = new DriverManagerDataSource(jdbcUrl, username, password);
        jdbc = new JdbcTemplate(dataSource);
        assertEquals(SupplyChainMySqlTestSupport.DEDICATED_DATABASE,
                jdbc.queryForObject("SELECT DATABASE()", String.class),
                "refusing to run against a database other than " + SupplyChainMySqlTestSupport.DEDICATED_DATABASE);
        tasks = new SupplyChainTaskRepository(jdbc, mapper());
        events = new SupplyChainEventRepository(jdbc);
        executions = new DisposalExecutionRepository(jdbc, mapper());
        proposals = new DecisionProposalRepository(jdbc, mapper());
    }

    @AfterAll
    static void stopIsolatedMySql() {
        if (mySql != null) {
            mySql.close();
        }
    }

    @BeforeEach
    void clearCommittedRows() {
        jdbc.update("DELETE FROM " + EXECUTION_TABLE);
        jdbc.update("DELETE FROM " + PROPOSAL_TABLE);
        jdbc.update("DELETE FROM " + EVENT_TABLE);
        jdbc.update("DELETE FROM " + TASK_TABLE);
    }

    @Test
    void connectsToRealMySqlAndCreatesTheExpectedSchema() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertEquals("MySQL", connection.getMetaData().getDatabaseProductName());
            assertTrue(connection.getMetaData().getURL().startsWith("jdbc:mysql:"));
            assertTrue(connection.getMetaData().getDatabaseProductVersion().startsWith("8."));
        }

        assertEquals("InnoDB", tableEngine(TASK_TABLE));
        assertEquals("InnoDB", tableEngine(EVENT_TABLE));
        assertEquals("InnoDB", tableEngine(EXECUTION_TABLE));
        assertEquals("InnoDB", tableEngine(PROPOSAL_TABLE));

        List<String> decisionColumns = jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME IN "
                + "('task_type','decision_json') ORDER BY COLUMN_NAME", String.class, TASK_TABLE);
        assertEquals(List.of("decision_json", "task_type"), decisionColumns);

        List<String> uniqueExecutionIndexes = jdbc.queryForList("SELECT DISTINCT INDEX_NAME FROM "
                + "information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? "
                + "AND NON_UNIQUE=0 AND INDEX_NAME<>'PRIMARY'", String.class, EXECUTION_TABLE);
        assertEquals(1, uniqueExecutionIndexes.size(),
                "the sandbox execution table must have exactly one business unique key");
        List<String> uniqueExecutionColumns = jdbc.queryForList("SELECT COLUMN_NAME FROM "
                        + "information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? "
                        + "AND INDEX_NAME=? ORDER BY SEQ_IN_INDEX",
                String.class, EXECUTION_TABLE, uniqueExecutionIndexes.get(0));
        assertEquals(List.of("disposal_plan_id", "idempotency_key"), uniqueExecutionColumns);

        Integer foreignKeys = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS "
                        + "WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME=? AND REFERENCED_TABLE_NAME=? "
                        + "AND DELETE_RULE='CASCADE'",
                Integer.class, EVENT_TABLE, TASK_TABLE);
        assertEquals(1, foreignKeys == null ? 0 : foreignKeys.intValue());
        Integer proposalForeignKeys = jdbc.queryForObject("SELECT COUNT(*) FROM "
                        + "information_schema.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() "
                        + "AND TABLE_NAME=? AND REFERENCED_TABLE_NAME=? AND DELETE_RULE='CASCADE'",
                Integer.class, PROPOSAL_TABLE, TASK_TABLE);
        assertEquals(1, proposalForeignKeys == null ? 0 : proposalForeignKeys.intValue());

        List<String> uniqueColumns = jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE "
                        + "WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME=? AND CONSTRAINT_NAME=? "
                        + "ORDER BY ORDINAL_POSITION",
                String.class, EVENT_TABLE, "uq_chainpilot_event_sequence");
        assertEquals(List.of("task_id", "sequence_no"), uniqueColumns);
    }

    @Test
    void taskReportAndUnicodeDataSurviveRepositoryAndConnectionRecreation() {
        Instant updatedAt = Instant.parse("2026-10-20T08:15:30.123Z");
        String question = "分析订单：中文与 emoji 😀 的持久化";
        tasks.create(new SupplyChainTaskStatus("task-unicode", SupplyChainTaskState.RUNNING,
                "GENERATING_REPORT", 1, null, updatedAt), question);
        assertTrue(tasks.findReport("task-unicode").isEmpty());
        assertFalse(tasks.findHistory(null, 10, 0).get(0).reportAvailable());

        RiskAnalysisReport report = new RiskAnalysisReport("task-unicode", SupplyChainTaskState.COMPLETED,
                "高风险订单 3 个，建议补货 😀", List.of("查询订单"), true, List.of(), List.of("补货"),
                List.of("findInventory"), List.of(), true, 1, updatedAt.plusSeconds(5));
        tasks.saveReport(report);
        tasks.update(new SupplyChainTaskStatus("task-unicode", SupplyChainTaskState.COMPLETED, "DONE", 1, null,
                updatedAt.plusSeconds(5)));

        DriverManagerDataSource recreatedDataSource = new DriverManagerDataSource(jdbcUrl, username, password);
        SupplyChainTaskRepository recreatedTasks = new SupplyChainTaskRepository(new JdbcTemplate(recreatedDataSource),
                mapper());

        assertEquals(SupplyChainTaskState.COMPLETED, recreatedTasks.findTask("task-unicode").orElseThrow().status());
        assertEquals(report, recreatedTasks.findReport("task-unicode").orElseThrow());
        SupplyChainTaskSummary summary = recreatedTasks.findHistory(null, 10, 0).get(0);
        assertEquals("task-unicode", summary.taskId());
        assertEquals(question, summary.question());
        assertTrue(summary.reportAvailable());
        assertEquals(updatedAt.plusSeconds(5), summary.updatedAt());
    }

    @Test
    void decisionResultsAndProposalsSurviveRecreationAndEnforceTheConfirmationBoundary() {
        Instant createdAt = Instant.parse("2026-10-20T12:00:00.123Z");
        String taskId = "task-decision";
        String question = "比较 SKU-MCU-001 补货 50、100、200 件的方案";
        tasks.create(new SupplyChainTaskStatus(taskId, SupplyChainTaskState.RUNNING, "DECISION_SUMMARY", 0, null,
                createdAt), question, SupplyChainTaskType.DECISION);
        DecisionTaskResult result = new DecisionTaskResult(taskId, question, "COMPARE_REPLENISHMENT", "COMPLETED",
                "候选 CAND-100 排名 1。", "MODEL_GROUNDED", true, false, false, List.of(),
                List.of("compareDisposalScenarios"), List.of("调用补货方案对比工具"), false, List.of(), List.of(),
                List.of(), null, null, null, null, null, List.of(), List.of(),
                List.of("候选指标来自 Java 对比引擎"), false, createdAt);
        tasks.saveDecision(result);
        tasks.update(new SupplyChainTaskStatus(taskId, SupplyChainTaskState.COMPLETED, "DONE", 0, null, createdAt));
        assertEquals(result, tasks.findDecision(taskId).orElseThrow());
        assertEquals(SupplyChainTaskType.DECISION, tasks.findTaskType(taskId).orElseThrow());
        assertEquals(List.of(taskId), taskIds(tasks.findHistory(SupplyChainTaskType.DECISION, null, 10, 0)));
        assertTrue(tasks.findHistory(SupplyChainTaskType.ANALYSIS, null, 10, 0).isEmpty());

        DecisionProposal pending = new DecisionProposal("proposal-pending", taskId,
                ProposalActionType.START_SANDBOX_EXECUTION, "启动方案 plan-1 的沙箱演练", "plan-1",
                Map.of("recommendationId", "rec-1", "quantity", 100), List.of("方案已选定，执行资格通过"),
                "hash-pending", ProposalStatus.PENDING, createdAt, createdAt.plusSeconds(1800), null, null, null);
        proposals.save(pending);
        assertEquals(pending, proposals.find("proposal-pending").orElseThrow());
        assertEquals(pending, proposals.findPendingByTaskAndHash(taskId, "hash-pending").orElseThrow());

        assertEquals(1, proposals.claim("proposal-pending", createdAt.plusSeconds(60)));
        assertEquals(0, proposals.claim("proposal-pending", createdAt.plusSeconds(61)));
        assertEquals(ProposalStatus.EXECUTING, proposals.find("proposal-pending").orElseThrow().status());
        Map<String, Object> executionResult = Map.of("executionId", "exec-1", "status", "SUCCEEDED",
                "realDataMutated", false);
        proposals.markConfirmed("proposal-pending", executionResult, createdAt.plusSeconds(65));
        DecisionProposal confirmed = proposals.find("proposal-pending").orElseThrow();
        assertEquals(ProposalStatus.CONFIRMED, confirmed.status());
        assertEquals(executionResult, confirmed.result());
        assertEquals(createdAt.plusSeconds(65), confirmed.confirmedAt());
        assertTrue(proposals.findPendingByTaskAndHash(taskId, "hash-pending").isEmpty());

        DecisionProposal expired = proposal("proposal-expired", taskId, "hash-expired", createdAt);
        proposals.save(expired);
        assertEquals(0, proposals.claim("proposal-expired", createdAt.plusSeconds(1801)));
        proposals.markExpired("proposal-expired");
        assertEquals(ProposalStatus.EXPIRED, proposals.find("proposal-expired").orElseThrow().status());

        DecisionProposal cancelled = proposal("proposal-cancelled", taskId, "hash-cancelled", createdAt);
        proposals.save(cancelled);
        proposals.markCancelled("proposal-cancelled", createdAt.plusSeconds(70));
        assertEquals(ProposalStatus.CANCELLED, proposals.find("proposal-cancelled").orElseThrow().status());

        DecisionProposal failed = proposal("proposal-failed", taskId, "hash-failed", createdAt);
        proposals.save(failed);
        proposals.markFailed("proposal-failed", "x".repeat(1200));
        DecisionProposal storedFailure = proposals.find("proposal-failed").orElseThrow();
        assertEquals(ProposalStatus.FAILED, storedFailure.status());
        assertEquals(1000, storedFailure.errorMessage().length());

        DriverManagerDataSource recreatedDataSource = new DriverManagerDataSource(jdbcUrl, username, password);
        JdbcTemplate recreatedJdbc = new JdbcTemplate(recreatedDataSource);
        SupplyChainTaskRepository recreatedTasks = new SupplyChainTaskRepository(recreatedJdbc, mapper());
        DecisionProposalRepository recreatedProposals = new DecisionProposalRepository(recreatedJdbc, mapper());
        assertEquals(result, recreatedTasks.findDecision(taskId).orElseThrow());
        assertEquals(4, recreatedProposals.findByTask(taskId).size());
        assertEquals(ProposalStatus.CONFIRMED, recreatedProposals.find("proposal-pending").orElseThrow().status());
        assertEquals(executionResult, recreatedProposals.find("proposal-pending").orElseThrow().result());

        jdbc.update("DELETE FROM " + TASK_TABLE + " WHERE task_id=?", taskId);
        assertTrue(recreatedProposals.findByTask(taskId).isEmpty());
        assertTrue(recreatedTasks.findDecision(taskId).isEmpty());
    }

    @Test
    void eventsAreOrderedIsolatedAndEnforceTheUniqueAndForeignKeyConstraints() {
        Instant occurredAt = Instant.now();
        seedTask("task-a", "task a", occurredAt);
        seedTask("task-b", "task b", occurredAt);

        events.append("task-a", SupplyChainEventType.TASK_QUEUED, "QUEUE", SupplyChainEventStatus.QUEUED,
                occurredAt, null, "task a queued", null);
        events.append("task-a", SupplyChainEventType.NODE_STARTED, "PLANNING", SupplyChainEventStatus.STARTED,
                occurredAt, null, "node started", null);
        events.append("task-a", SupplyChainEventType.NODE_COMPLETED, "PLANNING", SupplyChainEventStatus.COMPLETED,
                occurredAt, 7L, "steps=2", null);
        events.append("task-b", SupplyChainEventType.TASK_QUEUED, "QUEUE", SupplyChainEventStatus.QUEUED,
                occurredAt, null, "task b queued", null);

        List<SupplyChainTaskEvent> taskAEvents = events.findByTaskId("task-a");
        assertEquals(List.of(1, 2, 3), taskAEvents.stream().map(SupplyChainTaskEvent::sequence).toList());
        assertEquals(List.of(SupplyChainEventType.TASK_QUEUED, SupplyChainEventType.NODE_STARTED,
                SupplyChainEventType.NODE_COMPLETED), taskAEvents.stream().map(SupplyChainTaskEvent::type).toList());
        assertTrue(taskAEvents.stream().allMatch(event -> "task-a".equals(event.taskId())));
        assertEquals(1, events.findByTaskId("task-b").size());
        assertEquals(1, events.findByTaskId("task-b").get(0).sequence());
        assertTrue(events.findByTaskId("task-with-no-events").isEmpty());
        assertTrue(tasks.findTask("task-with-no-events").isEmpty());
        assertTrue(tasks.findReport("task-with-no-events").isEmpty());

        assertThrows(DataIntegrityViolationException.class, () -> events.append("missing-task",
                SupplyChainEventType.TASK_QUEUED, "QUEUE", SupplyChainEventStatus.QUEUED, occurredAt, null,
                "orphan event", null));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO " + EVENT_TABLE
                        + " (event_id, task_id, sequence_no, event_type, stage, status, occurred_at, duration_ms, "
                        + "summary, error_type) VALUES (?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL)",
                UUID.randomUUID().toString(), "task-a", 1, SupplyChainEventType.TASK_QUEUED, "QUEUE",
                SupplyChainEventStatus.QUEUED, occurredAt.toEpochMilli()));

        SupplyChainTaskEvent truncated = events.append("task-a", SupplyChainEventType.NODE_FAILED, "VALIDATING",
                SupplyChainEventStatus.FAILED, occurredAt, null, "x".repeat(800), "y".repeat(300));
        assertEquals(500, truncated.summary().length());
        assertEquals(128, truncated.errorType().length());

        jdbc.update("DELETE FROM " + TASK_TABLE + " WHERE task_id=?", "task-a");
        assertTrue(events.findByTaskId("task-a").isEmpty());
        assertEquals(1, events.findByTaskId("task-b").size());
    }

    @Test
    void historyFilteringOrderingAndPaginationUseMySql() throws Exception {
        Instant base = Instant.now().minusSeconds(30);
        seedTask("task-c", "completed c", base);
        seedTask("task-b", "completed b", base);
        seedTask("task-a", "completed a", base);
        seedTask("task-d", "failed d", base.minusSeconds(1));
        seedTask("task-e", "accepted e", base.minusSeconds(2));
        saveReport("task-c", base);
        saveReport("task-a", base);
        tasks.update(new SupplyChainTaskStatus("task-c", SupplyChainTaskState.COMPLETED, "DONE", 0, null, base));
        tasks.update(new SupplyChainTaskStatus("task-b", SupplyChainTaskState.COMPLETED, "DONE", 0, null, base));
        tasks.update(new SupplyChainTaskStatus("task-a", SupplyChainTaskState.COMPLETED, "DONE", 0, null, base));
        tasks.update(new SupplyChainTaskStatus("task-d", SupplyChainTaskState.FAILED, "DONE", 0, null,
                base.minusSeconds(1)));

        assertEquals(5, tasks.countTasks(null));
        assertEquals(3, tasks.countTasks(SupplyChainTaskState.COMPLETED));
        assertEquals(1, tasks.countTasks(SupplyChainTaskState.FAILED));
        assertEquals(0, tasks.countTasks(SupplyChainTaskState.RUNNING));
        assertEquals(List.of("task-c", "task-b", "task-a", "task-d", "task-e"), taskIds(tasks.findHistory(null, 10, 0)));
        assertEquals(List.of("task-c", "task-b"), taskIds(tasks.findHistory(null, 2, 0)));
        assertEquals(List.of("task-a", "task-d"), taskIds(tasks.findHistory(null, 2, 2)));
        assertEquals(List.of("task-e"), taskIds(tasks.findHistory(null, 2, 4)));
        assertTrue(tasks.findHistory(null, 2, 6).isEmpty());
        assertEquals(List.of("task-c", "task-b", "task-a"),
                taskIds(tasks.findHistory(SupplyChainTaskState.COMPLETED, 10, 0)));
        assertTrue(tasks.findHistory(SupplyChainTaskState.COMPLETED, 10, 10).isEmpty());
        assertTrue(tasks.findHistory(SupplyChainTaskState.RUNNING, 10, 0).isEmpty());

        List<SupplyChainTaskSummary> allHistory = tasks.findHistory(null, 10, 0);
        assertTrue(allHistory.get(0).reportAvailable());
        assertFalse(allHistory.get(1).reportAvailable());
        assertTrue(allHistory.get(2).reportAvailable());

        SupplyChainEventRecorder recorder = new SupplyChainEventRecorder(events);
        SupplyChainWorkflowService service = new SupplyChainWorkflowService(mock(SupplyChainAgentTools.class),
                mock(SupplyChainAnalysisService.class), mock(SupplyChainDecisionService.class), mapper(), tasks,
                events, recorder, Runnable::run);

        SupplyChainTaskPage completedPage = service.tasks(SupplyChainTaskState.COMPLETED, 2, 2);
        assertEquals(2, completedPage.page());
        assertEquals(2, completedPage.pageSize());
        assertEquals(3, completedPage.totalElements());
        assertEquals(2, completedPage.totalPages());
        assertEquals(List.of("task-a"), taskIds(completedPage.items()));

        SupplyChainTaskPage beyondLastPage = service.tasks(SupplyChainTaskState.COMPLETED, 3, 2);
        assertTrue(beyondLastPage.items().isEmpty());
        assertEquals(3, beyondLastPage.totalElements());
        assertEquals(2, beyondLastPage.totalPages());

        SupplyChainTaskPage emptyPage = service.tasks(SupplyChainTaskState.RUNNING, 1, 10);
        assertTrue(emptyPage.items().isEmpty());
        assertEquals(0, emptyPage.totalElements());
        assertEquals(0, emptyPage.totalPages());
    }

    @Test
    void newConnectionReadsCommittedDataAndOnlyRecoversExpiredLeases() {
        Instant now = Instant.now();
        seedTask("task-running", "running", now);
        seedTask("task-stalled", "stalled", now);
        seedTask("task-completed", "completed", now.minusSeconds(1));
        RiskAnalysisReport report = new RiskAnalysisReport("task-completed", SupplyChainTaskState.COMPLETED,
                "已完成", List.of("查询订单"), false, List.of(), List.of(), List.of(), List.of(), false, 0, now);
        tasks.saveReport(report);
        tasks.update(new SupplyChainTaskStatus("task-completed", SupplyChainTaskState.COMPLETED, "DONE", 0, null,
                now));
        events.append("task-completed", SupplyChainEventType.TASK_COMPLETED, "WORKFLOW",
                SupplyChainEventStatus.COMPLETED, now, 1L, "done", null);

        long dbNow = tasks.databaseNowMillis();
        assertTrue(tasks.claimTask("task-running", "jvm-live", dbNow + 600_000));
        assertTrue(tasks.claimTask("task-stalled", "jvm-dead", dbNow - 1_000));
        tasks.update(new SupplyChainTaskStatus("task-running", SupplyChainTaskState.RUNNING, "QUERYING_ORDERS",
                0, null, now));
        tasks.update(new SupplyChainTaskStatus("task-stalled", SupplyChainTaskState.RUNNING, "QUERYING_ORDERS",
                0, null, now));

        DriverManagerDataSource recreatedDataSource = new DriverManagerDataSource(jdbcUrl, username, password);
        JdbcTemplate recreatedJdbc = new JdbcTemplate(recreatedDataSource);
        SupplyChainTaskRepository recreatedTasks = new SupplyChainTaskRepository(recreatedJdbc, mapper());
        SupplyChainEventRepository recreatedEvents = new SupplyChainEventRepository(recreatedJdbc);
        TaskRecoveryCoordinator recovery = new TaskRecoveryCoordinator(recreatedTasks, recreatedEvents,
                new DataSourceTransactionManager(recreatedDataSource));

        // A second JVM coming up must not treat another instance's live task as a restart leftover.
        assertEquals(SupplyChainTaskState.RUNNING, recreatedTasks.findTask("task-running").orElseThrow().status());
        assertEquals(SupplyChainTaskState.RUNNING, recreatedTasks.findTask("task-stalled").orElseThrow().status());
        assertEquals(SupplyChainTaskState.COMPLETED, recreatedTasks.findTask("task-completed").orElseThrow().status());
        assertEquals(report, recreatedTasks.findReport("task-completed").orElseThrow());
        assertEquals(1, recreatedEvents.findByTaskId("task-completed").size());

        recovery.recoverExpiredTasks();

        SupplyChainTaskStatus recovered = recreatedTasks.findTask("task-stalled").orElseThrow();
        assertEquals(SupplyChainTaskState.FAILED, recovered.status());
        assertEquals("LEASE_EXPIRED", recovered.currentStep());
        assertTrue(recovered.error().contains("not re-executed"));
        assertEquals(SupplyChainTaskState.RUNNING, recreatedTasks.findTask("task-running").orElseThrow().status());
        List<SupplyChainTaskEvent> recoveredEvents = recreatedEvents.findByTaskId("task-stalled");
        assertEquals(1, recoveredEvents.size());
        assertEquals(SupplyChainEventType.TASK_FAILED, recoveredEvents.get(0).type());
        assertEquals("LEASE", recoveredEvents.get(0).stage());

        recovery.recoverExpiredTasks();
        assertEquals(1, recreatedEvents.findByTaskId("task-stalled").size());
    }

    @Test
    void pruningExpiredTerminalTasksCascadesTheirEvents() {
        Instant old = Instant.now().minus(Duration.ofDays(2));
        seedTask("task-old", "old", old);
        tasks.update(new SupplyChainTaskStatus("task-old", SupplyChainTaskState.COMPLETED, "DONE", 0, null, old));
        events.append("task-old", SupplyChainEventType.TASK_COMPLETED, "WORKFLOW",
                SupplyChainEventStatus.COMPLETED, old, 1L, "done", null);
        seedTask("task-active", "active", Instant.now());
        events.append("task-active", SupplyChainEventType.TASK_STARTED, "WORKFLOW",
                SupplyChainEventStatus.STARTED, Instant.now(), null, "started", null);

        tasks.prune(Duration.ofHours(24), 1_000);

        assertTrue(tasks.findTask("task-old").isEmpty());
        assertTrue(events.findByTaskId("task-old").isEmpty());
        assertTrue(tasks.findTask("task-active").isPresent());
        assertEquals(1, events.findByTaskId("task-active").size());
    }

    @Test
    void pruningKeepsExpiredTasksReferencedByDurableBusinessRecords() {
        Instant old = Instant.now().minus(Duration.ofDays(2));
        seedTask("task-referenced", "referenced", old);
        tasks.update(new SupplyChainTaskStatus("task-referenced", SupplyChainTaskState.COMPLETED, "DONE", 0, null,
                old));
        proposals.save(new DecisionProposal("proposal-referenced", "task-referenced",
                ProposalActionType.START_SANDBOX_EXECUTION, "先保留该任务", "plan-1", Map.of(), List.of("evidence"),
                "hash-referenced", ProposalStatus.PENDING, old, old.plus(Duration.ofMinutes(30)), null, null, null));
        seedTask("task-unreferenced", "unreferenced", old);
        tasks.update(new SupplyChainTaskStatus("task-unreferenced", SupplyChainTaskState.COMPLETED, "DONE", 0,
                null, old));

        tasks.prune(Duration.ofHours(24), 0);

        assertTrue(tasks.findTask("task-referenced").isPresent());
        assertTrue(tasks.findTask("task-unreferenced").isEmpty());
    }

    @Test
    void sandboxExecutionStepsDraftAndStatusSurviveRepositoryAndConnectionRecreation() {
        Instant createdAt = Instant.parse("2026-10-20T09:00:00.100Z");
        Instant completedAt = createdAt.plusMillis(40);
        String executionId = UUID.randomUUID().toString();
        String planId = "plan-mysql-exec";
        List<String> evidence = List.of("模拟证据：可承诺 20 -> 120", "知识库：库存策略 (kb-1)");

        SandboxExecution running = new SandboxExecution(executionId, planId, "rec-mysql-exec",
                DisposalActionType.REPLENISH_INVENTORY, SandboxExecutionMode.SANDBOX,
                SandboxExecutionStatus.RUNNING, "sim-mysql-exec", "mysql-key-1",
                List.of(step(1, "PLAN_AND_RECOMMENDATION_VALIDATION", "方案与处置建议校验",
                        SandboxStepStatus.SUCCEEDED, "planId=" + planId, "方案仍为 SELECTED", null, createdAt,
                        createdAt.plusMillis(5))),
                null, evidence, null, List.of(), null, false, DISCLAIMER, createdAt, createdAt, null,
                createdAt.plusMillis(5));
        executions.save(running);

        assertEquals(running, executions.find(executionId).orElseThrow());
        assertEquals(running, executions.findByIdempotencyKey(planId, "mysql-key-1").orElseThrow());
        assertEquals(running, executions.findBlocking(planId).orElseThrow());
        assertEquals(1, executions.count(planId));

        SandboxTaskDraft draft = new SandboxTaskDraft("DRAFT-REPL-mysql-1",
                SandboxDraftType.REPLENISHMENT_TASK_DRAFT, "补货任务草稿（沙箱）：SKU-MCU-001 +100",
                "SKU-MCU-001", null, 100, 55, 20, 155, 120, List.of("ORD-1001"),
                List.of("核对供应商可供货量与最早到货日期"), List.of("补货数量 100 件", "预算、供应商与到货时间"),
                "sim-mysql-exec", "模拟 sim-mysql-exec：可承诺 20 -> 120", createdAt.plusMillis(8));
        List<SandboxExecutionStep> finalSteps = List.of(
                step(1, "PLAN_AND_RECOMMENDATION_VALIDATION", "方案与处置建议校验",
                        SandboxStepStatus.SUCCEEDED, "planId=" + planId, "方案仍为 SELECTED", null, createdAt,
                        createdAt.plusMillis(5)),
                step(2, "SIMULATION_AND_EVIDENCE_VALIDATION", "模拟结果与来源证据校验",
                        SandboxStepStatus.SUCCEEDED, "simulationId=sim-mysql-exec, evidence=2",
                        "模拟来源与 2 条证据校验通过", null, createdAt.plusMillis(6), createdAt.plusMillis(12)));
        SandboxExecution finished = new SandboxExecution(executionId, planId, "rec-mysql-exec",
                DisposalActionType.REPLENISH_INVENTORY, SandboxExecutionMode.SANDBOX,
                SandboxExecutionStatus.SUCCEEDED, "sim-mysql-exec", "mysql-key-1", finalSteps, draft, evidence,
                "沙箱演练完成：已生成补货任务草稿 DRAFT-REPL-mysql-1", List.of("补货数量、预算和到货时间需人工复核"),
                null, false, DISCLAIMER, createdAt, createdAt, completedAt, completedAt);
        executions.update(finished);
        assertEquals(finished, executions.find(executionId).orElseThrow());

        DriverManagerDataSource recreatedDataSource = new DriverManagerDataSource(jdbcUrl, username, password);
        DisposalExecutionRepository recreatedExecutions = new DisposalExecutionRepository(
                new JdbcTemplate(recreatedDataSource), mapper());
        assertEquals(finished, recreatedExecutions.find(executionId).orElseThrow());

        List<SandboxExecutionSummary> summaries = recreatedExecutions.findPage(planId, 10, 0);
        assertEquals(1, summaries.size());
        SandboxExecutionSummary summary = summaries.get(0);
        assertEquals(executionId, summary.executionId());
        assertEquals(planId, summary.disposalPlanId());
        assertEquals(DisposalActionType.REPLENISH_INVENTORY, summary.actionType());
        assertEquals(SandboxExecutionMode.SANDBOX, summary.mode());
        assertEquals(SandboxExecutionStatus.SUCCEEDED, summary.status());
        assertEquals("sim-mysql-exec", summary.sourceSimulationId());
        assertEquals("mysql-key-1", summary.idempotencyKey());
        assertEquals("DRAFT-REPL-mysql-1", summary.taskDraftId());
        assertEquals(SandboxDraftType.REPLENISHMENT_TASK_DRAFT, summary.draftType());
        assertEquals(draft.title(), summary.title());
        assertEquals(2, summary.stepCount());
        assertEquals(0, summary.failedStepCount());
        assertEquals(createdAt, summary.createdAt());
        assertEquals(completedAt, summary.completedAt());
    }

    @Test
    void duplicateRequestKeysAreRejectedAndRolledBackExecutionsLeaveNoRows() {
        Instant base = Instant.parse("2026-10-20T10:00:00.100Z");
        SandboxExecution first = execution("exec-dup-1", "plan-mysql-dup", "dup-key-1",
                SandboxExecutionStatus.RUNNING, base);
        executions.save(first);

        assertThrows(DataIntegrityViolationException.class,
                () -> executions.save(execution("exec-dup-2", "plan-mysql-dup", "dup-key-1",
                        SandboxExecutionStatus.RUNNING, base.plusMillis(1))));
        assertEquals(1, executions.count("plan-mysql-dup"));
        assertEquals(first, executions.findByIdempotencyKey("plan-mysql-dup", "dup-key-1").orElseThrow());

        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
            executions.save(execution("exec-rolled-back", "plan-mysql-tx", "rollback-key",
                    SandboxExecutionStatus.RUNNING, base));
            throw new IllegalStateException("simulated failure after insert");
        }));
        assertTrue(executions.find("exec-rolled-back").isEmpty());
        assertEquals(0, executions.count("plan-mysql-tx"));

        transaction.executeWithoutResult(status -> executions.save(execution("exec-committed", "plan-mysql-tx",
                "commit-key", SandboxExecutionStatus.RUNNING, base)));
        assertEquals(1, executions.count("plan-mysql-tx"));
        assertTrue(executions.find("exec-committed").isPresent());
    }

    @Test
    void failedExecutionsDoNotBlockRetryAndHistoryPaginationUsesMySql() {
        Instant base = Instant.parse("2026-10-20T11:00:00.100Z");
        executions.save(execution("exec-failed", "plan-mysql-retry", "retry-key-1",
                SandboxExecutionStatus.FAILED, base));
        assertTrue(executions.findBlocking("plan-mysql-retry").isEmpty());
        assertEquals(1, executions.count("plan-mysql-retry"));

        executions.save(execution("exec-succeeded", "plan-mysql-retry", "retry-key-2",
                SandboxExecutionStatus.SUCCEEDED, base.plusMillis(10)));
        assertEquals("exec-succeeded", executions.findBlocking("plan-mysql-retry").orElseThrow().executionId());
        assertEquals(2, executions.count("plan-mysql-retry"));

        List<SandboxExecutionSummary> firstPage = executions.findPage("plan-mysql-retry", 1, 0);
        assertEquals(List.of("exec-succeeded"),
                firstPage.stream().map(SandboxExecutionSummary::executionId).toList());
        assertEquals(0, firstPage.get(0).failedStepCount());
        List<SandboxExecutionSummary> secondPage = executions.findPage("plan-mysql-retry", 1, 1);
        assertEquals(List.of("exec-failed"),
                secondPage.stream().map(SandboxExecutionSummary::executionId).toList());
        assertEquals(1, secondPage.get(0).failedStepCount());
        assertEquals(1, secondPage.get(0).stepCount());
    }

    private static SandboxExecutionStep step(int sequence, String code, String name, SandboxStepStatus status,
            String inputSummary, String resultSummary, String failureReason, Instant startedAt, Instant completedAt) {
        return new SandboxExecutionStep(sequence, code, name, status, inputSummary,
                status == SandboxStepStatus.SUCCEEDED ? "通过" : "未通过", resultSummary, failureReason, startedAt,
                completedAt);
    }

    private static SandboxExecution execution(String executionId, String planId, String key,
            SandboxExecutionStatus status, Instant createdAt) {
        boolean terminal = status != SandboxExecutionStatus.RUNNING;
        boolean failed = status == SandboxExecutionStatus.FAILED;
        List<SandboxExecutionStep> steps = List.of(step(1, "PLAN_AND_RECOMMENDATION_VALIDATION",
                "方案与处置建议校验", failed ? SandboxStepStatus.FAILED : SandboxStepStatus.SUCCEEDED,
                "planId=" + planId, failed ? null : "方案仍为 SELECTED",
                failed ? "simulated step failure" : null, createdAt, createdAt.plusMillis(2)));
        Instant completedAt = terminal ? createdAt.plusMillis(5) : null;
        return new SandboxExecution(executionId, planId, "rec-" + executionId,
                DisposalActionType.REPLENISH_INVENTORY, SandboxExecutionMode.SANDBOX, status,
                "sim-" + executionId, key, steps, null, List.of("模拟证据：" + executionId),
                failed ? "沙箱演练未完成：simulated step failure"
                        : status == SandboxExecutionStatus.SUCCEEDED ? "沙箱演练完成" : null,
                failed ? List.of("排查原因后使用新的请求编号重试") : List.of(),
                failed ? "simulated step failure" : null, false, DISCLAIMER, createdAt, createdAt, completedAt,
                terminal ? completedAt : createdAt.plusMillis(3));
    }

    private static String tableEngine(String tableName) {
        return jdbc.queryForObject("SELECT ENGINE FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?", String.class, tableName);
    }

    @Test
    void cancellationAndEventCommitAtomicallyAndRepeatedCancelDoesNotDuplicateTheEvent() {
        seedTask("cancel-my", "cancel me", Instant.now());
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        Boolean changed = transaction.execute(status -> {
            boolean cancelled = tasks.cancelIfActive("cancel-my");
            if (cancelled) {
                events.append("cancel-my", SupplyChainEventType.TASK_CANCELLED, "WORKFLOW",
                        SupplyChainEventStatus.CANCELLED, Instant.now(), null, "task cancelled by user", null);
            }
            return cancelled;
        });

        assertTrue(changed);
        assertEquals("CANCELLED", tasks.findTask("cancel-my").orElseThrow().status());
        assertEquals(1, events.findByTaskId("cancel-my").size());
        assertFalse(tasks.cancelIfActive("cancel-my"));
        assertEquals(1, events.findByTaskId("cancel-my").size());
    }

    @Test
    void cancelledTaskRollsBackWhenTheEventInsertCannotBeWritten() {
        seedTask("cancel-rollback", "cancel rollback", Instant.now());
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbc.execute("RENAME TABLE chainpilot_task_event TO chainpilot_task_event_cancel_backup");
        try {
            assertThrows(RuntimeException.class, () -> transaction.executeWithoutResult(status -> {
                assertTrue(tasks.cancelIfActive("cancel-rollback"));
                events.append("cancel-rollback", SupplyChainEventType.TASK_CANCELLED, "WORKFLOW",
                        SupplyChainEventStatus.CANCELLED, Instant.now(), null, "task cancelled by user", null);
            }));
            assertEquals("ACCEPTED", tasks.findTask("cancel-rollback").orElseThrow().status());
        }
        finally {
            jdbc.execute("RENAME TABLE chainpilot_task_event_cancel_backup TO chainpilot_task_event");
        }
    }

    @Test
    void completedTaskCannotBeCancelledOrTurnedIntoACancellationEvent() {
        seedTask("completed-cancel", "completed", Instant.now());
        saveReport("completed-cancel", Instant.now());
        tasks.update(new SupplyChainTaskStatus("completed-cancel", SupplyChainTaskState.COMPLETED, "DONE", 0,
                null, Instant.now()));

        assertFalse(tasks.cancelIfActive("completed-cancel"));

        assertEquals("COMPLETED", tasks.findTask("completed-cancel").orElseThrow().status());
        assertTrue(events.findByTaskId("completed-cancel").isEmpty());
    }

    @Test
    void concurrentEventWritesFromTwoMySqlRepositoryInstancesGetUniqueSequences() throws Exception {
        seedTask("concurrent-events", "concurrent events", Instant.now());
        SupplyChainEventRepository other = new SupplyChainEventRepository(jdbc);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<SupplyChainTaskEvent> first = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return events.append("concurrent-events", SupplyChainEventType.NODE_STARTED, "A",
                        SupplyChainEventStatus.STARTED, Instant.now(), null, "first", null);
            });
            Future<SupplyChainTaskEvent> second = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return other.append("concurrent-events", SupplyChainEventType.NODE_STARTED, "B",
                        SupplyChainEventStatus.STARTED, Instant.now(), null, "second", null);
            });
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        finally {
            pool.shutdownNow();
        }

        List<SupplyChainTaskEvent> restored = events.findByTaskId("concurrent-events");
        assertEquals(2, restored.size());
        assertEquals(List.of(1, 2), restored.stream().map(SupplyChainTaskEvent::sequence).toList());
    }

    @Test
    void independentTransactionsAppendingToTheSameTaskKeepContiguousSequences() throws Exception {
        seedTask("tx-independent", "independent transactions", Instant.now());
        events.append("tx-independent", SupplyChainEventType.TASK_QUEUED, "QUEUE",
                SupplyChainEventStatus.QUEUED, Instant.now(), null, "queued", null);
        TransactionTemplate firstTransaction = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        TransactionTemplate secondTransaction = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        SupplyChainEventRepository other = new SupplyChainEventRepository(jdbc);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<SupplyChainTaskEvent> first = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return firstTransaction.execute(status -> events.append("tx-independent",
                        SupplyChainEventType.NODE_STARTED, "FIRST", SupplyChainEventStatus.STARTED,
                        Instant.now(), null, "first transaction", null));
            });
            Future<SupplyChainTaskEvent> second = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return secondTransaction.execute(status -> other.append("tx-independent",
                        SupplyChainEventType.NODE_STARTED, "SECOND", SupplyChainEventStatus.STARTED,
                        Instant.now(), null, "second transaction", null));
            });
            start.countDown();
            assertNotNull(first.get(10, TimeUnit.SECONDS));
            assertNotNull(second.get(10, TimeUnit.SECONDS));
        }
        finally {
            pool.shutdownNow();
        }

        List<SupplyChainTaskEvent> restored = events.findByTaskId("tx-independent");
        assertEquals(List.of(1, 2, 3), restored.stream().map(SupplyChainTaskEvent::sequence).toList());
        assertEquals(3, restored.stream().map(SupplyChainTaskEvent::eventId).distinct().count());
    }

    @Test
    void cancellationTransactionAndConcurrentEventAppendKeepExactlyOneCancellationEvent() throws Exception {
        seedTask("cancel-parallel", "cancel parallel", Instant.now());
        events.append("cancel-parallel", SupplyChainEventType.TASK_STARTED, "WORKFLOW",
                SupplyChainEventStatus.STARTED, Instant.now(), null, "started", null);
        TransactionTemplate cancellation = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        boolean cancelWon;
        try {
            Future<Boolean> cancelled = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return cancellation.execute(status -> {
                    boolean changed = tasks.cancelIfActive("cancel-parallel");
                    if (changed) {
                        events.append("cancel-parallel", SupplyChainEventType.TASK_CANCELLED, "WORKFLOW",
                                SupplyChainEventStatus.CANCELLED, Instant.now(), null,
                                "task cancelled by user", null);
                    }
                    return changed;
                });
            });
            Future<SupplyChainTaskEvent> parallel = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return new SupplyChainEventRepository(jdbc).append("cancel-parallel",
                        SupplyChainEventType.NODE_COMPLETED, "CONCURRENT", SupplyChainEventStatus.COMPLETED,
                        Instant.now(), 1L, "concurrent", null);
            });
            start.countDown();
            cancelWon = cancelled.get(10, TimeUnit.SECONDS);
            assertNotNull(parallel.get(10, TimeUnit.SECONDS));
        }
        finally {
            pool.shutdownNow();
        }

        assertTrue(cancelWon);
        assertEquals(SupplyChainTaskState.CANCELLED, tasks.findTask("cancel-parallel").orElseThrow().status());
        List<SupplyChainTaskEvent> restored = events.findByTaskId("cancel-parallel");
        assertEquals(3, restored.size());
        assertEquals(List.of(1, 2, 3), restored.stream().map(SupplyChainTaskEvent::sequence).toList());
        assertEquals(1, restored.stream()
            .filter(event -> SupplyChainEventType.TASK_CANCELLED.equals(event.type()))
            .count());
    }

    @Test
    void terminalRacePersistsExactlyOneTerminalEvent() throws Exception {
        seedTask("terminal-events", "terminal events", Instant.now());
        RiskAnalysisReport report = new RiskAnalysisReport("terminal-events", SupplyChainTaskState.COMPLETED,
                "race report", List.of("查询订单"), false, List.of(), List.of(), List.of(), List.of(), false, 0,
                Instant.now());
        TransactionTemplate cancellation = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        TransactionTemplate completion = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        boolean cancelWon;
        boolean completeWon;
        try {
            Future<Boolean> cancelled = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return cancellation.execute(status -> {
                    boolean changed = tasks.cancelIfActive("terminal-events");
                    if (changed) {
                        events.append("terminal-events", SupplyChainEventType.TASK_CANCELLED, "WORKFLOW",
                                SupplyChainEventStatus.CANCELLED, Instant.now(), null,
                                "task cancelled by user", null);
                    }
                    return changed;
                });
            });
            Future<Boolean> completed = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return completion.execute(status -> {
                    boolean changed = tasks.completeAnalysis("terminal-events", report, "DONE", 0);
                    if (changed) {
                        events.append("terminal-events", SupplyChainEventType.TASK_COMPLETED, "WORKFLOW",
                                SupplyChainEventStatus.COMPLETED, Instant.now(), 1L, "done", null);
                    }
                    return changed;
                });
            });
            start.countDown();
            cancelWon = cancelled.get(10, TimeUnit.SECONDS);
            completeWon = completed.get(10, TimeUnit.SECONDS);
        }
        finally {
            pool.shutdownNow();
        }

        assertEquals(1, (cancelWon ? 1 : 0) + (completeWon ? 1 : 0));
        List<SupplyChainTaskEvent> restored = events.findByTaskId("terminal-events");
        assertEquals(1, restored.size());
        assertEquals(cancelWon ? SupplyChainEventType.TASK_CANCELLED : SupplyChainEventType.TASK_COMPLETED,
                restored.get(0).type());
        assertEquals(cancelWon ? SupplyChainTaskState.CANCELLED : SupplyChainTaskState.COMPLETED,
                tasks.findTask("terminal-events").orElseThrow().status());
    }

    @Test
    void cancelledTaskAndEventSurviveRepositoryRecreationWithoutRecoveryOverwrite() {
        seedTask("cancel-restart", "cancel restart", Instant.now());
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        Boolean changed = transaction.execute(status -> {
            boolean cancelled = tasks.cancelIfActive("cancel-restart");
            if (cancelled) {
                events.append("cancel-restart", SupplyChainEventType.TASK_CANCELLED, "WORKFLOW",
                        SupplyChainEventStatus.CANCELLED, Instant.now(), null, "task cancelled by user", null);
            }
            return cancelled;
        });
        assertTrue(changed);

        DriverManagerDataSource recreatedDataSource = new DriverManagerDataSource(jdbcUrl, username, password);
        SupplyChainTaskRepository recreatedTasks = new SupplyChainTaskRepository(
                new JdbcTemplate(recreatedDataSource), mapper());
        SupplyChainEventRepository recreatedEvents = new SupplyChainEventRepository(
                new JdbcTemplate(recreatedDataSource));

        SupplyChainTaskStatus restored = recreatedTasks.findTask("cancel-restart").orElseThrow();
        assertEquals(SupplyChainTaskState.CANCELLED, restored.status());
        assertEquals("CANCELLED", restored.currentStep());
        assertEquals(1, recreatedEvents.findByTaskId("cancel-restart").size());
        assertEquals(SupplyChainEventType.TASK_CANCELLED,
                recreatedEvents.findByTaskId("cancel-restart").get(0).type());
    }

    @Test
    void cancellationAndCompletionCompeteForExactlyOneTerminalOutcome() throws Exception {
        seedTask("terminal-race", "terminal race", Instant.now());
        RiskAnalysisReport report = new RiskAnalysisReport("terminal-race", SupplyChainTaskState.COMPLETED,
                "race report", List.of("查询订单"), false, List.of(), List.of(), List.of(), List.of(), false, 0,
                Instant.now());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        boolean cancelWon;
        boolean completeWon;
        try {
            Future<Boolean> cancelled = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return tasks.cancelIfActive("terminal-race");
            });
            Future<Boolean> completed = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return tasks.completeAnalysis("terminal-race", report, "DONE", 0);
            });
            start.countDown();
            cancelWon = cancelled.get(10, TimeUnit.SECONDS);
            completeWon = completed.get(10, TimeUnit.SECONDS);
        }
        finally {
            pool.shutdownNow();
        }

        assertEquals(1, (cancelWon ? 1 : 0) + (completeWon ? 1 : 0));
        assertEquals(cancelWon ? SupplyChainTaskState.CANCELLED : SupplyChainTaskState.COMPLETED,
                tasks.findTask("terminal-race").orElseThrow().status());
    }

    @Test
    void eventAppendRetriesInsideARepeatableReadTransactionAfterAConcurrentCommit() throws Exception {
        seedTask("tx-event-race", "tx event race", Instant.now());
        events.append("tx-event-race", SupplyChainEventType.TASK_QUEUED, "QUEUE",
                SupplyChainEventStatus.QUEUED, Instant.now(), null, "queued", null);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.setIsolationLevel(Connection.TRANSACTION_REPEATABLE_READ);
        CountDownLatch snapshotTaken = new CountDownLatch(1);
        CountDownLatch concurrentCommitted = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<SupplyChainTaskEvent> concurrent = pool.submit(() -> {
                assertTrue(snapshotTaken.await(10, TimeUnit.SECONDS));
                SupplyChainTaskEvent appended = new SupplyChainEventRepository(jdbc).append("tx-event-race",
                        SupplyChainEventType.NODE_STARTED, "CONCURRENT", SupplyChainEventStatus.STARTED,
                        Instant.now(), null, "concurrent", null);
                concurrentCommitted.countDown();
                return appended;
            });
            transaction.executeWithoutResult(status -> {
                tasks.findTask("tx-event-race").orElseThrow();
                snapshotTaken.countDown();
                try {
                    assertTrue(concurrentCommitted.await(10, TimeUnit.SECONDS));
                }
                catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                events.append("tx-event-race", SupplyChainEventType.TASK_CANCELLED, "WORKFLOW",
                        SupplyChainEventStatus.CANCELLED, Instant.now(), null, "cancelled in transaction", null);
            });
            concurrent.get(10, TimeUnit.SECONDS);
        }
        finally {
            pool.shutdownNow();
        }
        List<SupplyChainTaskEvent> restored = events.findByTaskId("tx-event-race");
        assertEquals(List.of(1, 2, 3), restored.stream().map(SupplyChainTaskEvent::sequence).toList());
    }

    @Test
    void twoThreadsClaimingTheSameTaskLeaveExactlyOneOwner() throws Exception {
        seedTask("claim-race-task", "race", Instant.now());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Callable<Boolean> one = () -> {
                go.await();
                return tasks.claimTask("claim-race-task", "jvm-1", tasks.databaseNowMillis() + 600_000);
            };
            Callable<Boolean> two = () -> {
                go.await();
                return tasks.claimTask("claim-race-task", "jvm-2", tasks.databaseNowMillis() + 600_000);
            };
            Future<Boolean> first = pool.submit(one);
            Future<Boolean> second = pool.submit(two);
            go.countDown();

            int winners = (first.get(30, TimeUnit.SECONDS) ? 1 : 0) + (second.get(30, TimeUnit.SECONDS) ? 1 : 0);

            assertEquals(1, winners, "MySQL must let exactly one UPDATE claim the task row");
            assertEquals(1L, taskFenceVersion("claim-race-task"));
            assertNotNull(taskOwner("claim-race-task"));
            assertFalse(tasks.claimTask("claim-race-task", "jvm-3", tasks.databaseNowMillis() + 600_000));
        }
        finally {
            pool.shutdownNow();
        }
    }

    @Test
    void taskLeasesAndTheEvaluationLockNeverShareState() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS chainpilot_evaluation_lock (lock_name VARCHAR(64) PRIMARY KEY, "
                + "owner_instance_id VARCHAR(64), lease_expires_at BIGINT NOT NULL, fence_version BIGINT NOT NULL, "
                + "run_id VARCHAR(36), updated_at BIGINT NOT NULL)");
        jdbc.update("INSERT IGNORE INTO chainpilot_evaluation_lock (lock_name, owner_instance_id, "
                + "lease_expires_at, fence_version, run_id, updated_at) VALUES ('AGENT_EVALUATION', NULL, 0, 0, "
                + "NULL, ?)", System.currentTimeMillis());
        seedTask("isolation-task", "isolation", Instant.now());
        long dbNow = tasks.databaseNowMillis();
        assertTrue(tasks.claimTask("isolation-task", "jvm-a", dbNow + 600_000));
        long taskFence = taskFenceVersion("isolation-task");
        long lockFence = evaluationLockFence();
        try {
            jdbc.update("UPDATE chainpilot_evaluation_lock SET owner_instance_id='jvm-eval', lease_expires_at=?, "
                    + "run_id='eval-run', fence_version=fence_version+1, updated_at=? WHERE lock_name=?",
                    dbNow + 600_000, dbNow, "AGENT_EVALUATION");

            assertEquals(taskFence, taskFenceVersion("isolation-task"), "an evaluation lock write moved a task fence");
            assertEquals("jvm-a", taskOwner("isolation-task"));
            assertEquals(dbNow + 600_000, taskLeaseExpiry("isolation-task"));

            assertTrue(tasks.renewTaskLease("isolation-task", "jvm-a", taskFence, dbNow + 900_000));
            assertEquals(lockFence + 1, evaluationLockFence(), "a task renewal moved the evaluation lock fence");
            assertEquals(dbNow + 900_000, taskLeaseExpiry("isolation-task"));
            assertFalse(tasks.claimTask("isolation-task", "jvm-b", dbNow + 600_000));
        }
        finally {
            jdbc.update("UPDATE chainpilot_evaluation_lock SET owner_instance_id=NULL, lease_expires_at=0, "
                    + "run_id=NULL WHERE lock_name=?", "AGENT_EVALUATION");
        }
    }

    private long taskFenceVersion(String taskId) {
        return jdbc.queryForObject("SELECT fence_version FROM " + TASK_TABLE + " WHERE task_id=?",
                Long.class, taskId);
    }

    private long taskLeaseExpiry(String taskId) {
        return jdbc.queryForObject("SELECT lease_expires_at FROM " + TASK_TABLE + " WHERE task_id=?",
                Long.class, taskId);
    }

    private String taskOwner(String taskId) {
        return jdbc.queryForObject("SELECT owner_instance_id FROM " + TASK_TABLE + " WHERE task_id=?",
                String.class, taskId);
    }

    private long evaluationLockFence() {
        return jdbc.queryForObject("SELECT fence_version FROM chainpilot_evaluation_lock WHERE lock_name=?",
                Long.class, "AGENT_EVALUATION");
    }

    private static void seedTask(String taskId, String question, Instant updatedAt) {
        tasks.create(new SupplyChainTaskStatus(taskId, SupplyChainTaskState.ACCEPTED, "QUEUED", 0, null,
                updatedAt), question);
    }

    private static DecisionProposal proposal(String proposalId, String taskId, String contentHash,
            Instant createdAt) {
        return new DecisionProposal(proposalId, taskId, ProposalActionType.START_SANDBOX_EXECUTION,
                "启动方案 plan-1 的沙箱演练", "plan-1", Map.of("recommendationId", "rec-1"),
                List.of("方案已选定，执行资格通过"), contentHash, ProposalStatus.PENDING, createdAt,
                createdAt.plusSeconds(1800), null, null, null);
    }

    private static void saveReport(String taskId, Instant createdAt) {
        tasks.saveReport(new RiskAnalysisReport(taskId, SupplyChainTaskState.COMPLETED, "报告 " + taskId,
                List.of("查询订单"), false, List.of(), List.of(), List.of(), List.of(), false, 0, createdAt));
    }

    private static List<String> taskIds(List<SupplyChainTaskSummary> summaries) {
        return summaries.stream().map(SupplyChainTaskSummary::taskId).toList();
    }

    private static ObjectMapper mapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
