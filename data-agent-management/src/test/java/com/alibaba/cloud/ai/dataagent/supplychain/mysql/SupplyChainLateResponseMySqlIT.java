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

import com.alibaba.cloud.ai.dataagent.service.aimodelconfig.AiModelRegistry;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskAnalysisReport;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainEventStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainEventType;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskEvent;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskState;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.WorkflowPlan;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.SupplyChainEventRepository;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.SupplyChainTaskRepository;
import com.alibaba.cloud.ai.dataagent.supplychain.service.DisposalExecutionService;
import com.alibaba.cloud.ai.dataagent.supplychain.service.DisposalPlanService;
import com.alibaba.cloud.ai.dataagent.supplychain.service.DisposalRecommendationService;
import com.alibaba.cloud.ai.dataagent.supplychain.service.DisposalScenarioComparisonService;
import com.alibaba.cloud.ai.dataagent.supplychain.service.DisposalSimulationService;
import com.alibaba.cloud.ai.dataagent.supplychain.service.RiskNarrativeValidator;
import com.alibaba.cloud.ai.dataagent.supplychain.service.SupplyChainAgentTools;
import com.alibaba.cloud.ai.dataagent.supplychain.service.SupplyChainAnalysisService;
import com.alibaba.cloud.ai.dataagent.supplychain.service.SupplyChainEventRecorder;
import com.alibaba.cloud.ai.dataagent.supplychain.service.SupplyChainKnowledgeService;
import com.alibaba.cloud.ai.dataagent.supplychain.service.SupplyChainQueryService;
import com.alibaba.cloud.ai.dataagent.supplychain.service.SupplyChainTaskCancelledException;
import com.alibaba.cloud.ai.dataagent.supplychain.service.TaskCancellationRegistry;
import com.alibaba.cloud.ai.dataagent.util.ChatResponseUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MySQL persistence acceptance for the late-model-response event semantics introduced in round 36.
 * Everything below runs against real MySQL through the production repositories, the production
 * {@link SupplyChainEventRecorder} and the production analysis service; a second {@link DataSource}
 * plays the other instance, so the cancellation is a genuinely committed cross-connection state
 * change. The provider is a gate-controlled fake, so nothing here is real-model acceptance.
 */
@EnabledIfSystemProperty(named = SupplyChainMySqlTestSupport.ENABLE_PROPERTY, matches = "true")
class SupplyChainLateResponseMySqlIT {

    private static final String TASK_PREFIX = "r38-late-";

    private static SupplyChainMySqlTestSupport.MySqlTarget mySql;

    private static DataSource workerSource;

    private static DataSource cancellerSource;

    private static JdbcTemplate jdbc;

    private static SupplyChainTaskRepository tasks;

    private static SupplyChainEventRepository events;

    private static SupplyChainEventRecorder recorder;

    private static SupplyChainAnalysisService analysisService;

    private static ChatClient.ChatClientRequestSpec requestSpec;

    private static ChatClient.CallResponseSpec callResponseSpec;

    @BeforeAll
    static void startIsolatedMySql() {
        mySql = SupplyChainMySqlTestSupport.start();
        workerSource = new DriverManagerDataSource(mySql.jdbcUrl(), mySql.username(), mySql.password());
        // A second pool stands for another JVM: nothing is shared in memory except the database.
        cancellerSource = new DriverManagerDataSource(mySql.jdbcUrl(), mySql.username(), mySql.password());
        jdbc = new JdbcTemplate(workerSource);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        tasks = new SupplyChainTaskRepository(jdbc, mapper);
        events = new SupplyChainEventRepository(jdbc);
        recorder = new SupplyChainEventRecorder(events);
        // Same database-driven rule the production workflow installs; no in-process cancel signal is used.
        TaskCancellationRegistry.installPersistentChecker(taskId -> tasks.findTask(taskId)
            .map(status -> !SupplyChainTaskState.ACCEPTED.equals(status.status())
                    && !SupplyChainTaskState.RUNNING.equals(status.status()))
            .orElse(false));

        AiModelRegistry modelRegistry = mock(AiModelRegistry.class);
        ChatClient chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        callResponseSpec = mock(ChatClient.CallResponseSpec.class);
        when(modelRegistry.getChatClient()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.toolCallbacks(any(ToolCallback[].class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);

        SupplyChainQueryService queryService = mock(SupplyChainQueryService.class);
        SupplyChainKnowledgeService knowledgeService = mock(SupplyChainKnowledgeService.class);
        when(knowledgeService.retrieve(anyString())).thenReturn(List.of());
        analysisService = new SupplyChainAnalysisService(queryService,
                new SupplyChainAgentTools(queryService, mock(DisposalRecommendationService.class),
                        mock(DisposalSimulationService.class), mock(DisposalExecutionService.class),
                        mock(DisposalScenarioComparisonService.class), mock(DisposalPlanService.class), recorder),
                modelRegistry, knowledgeService, recorder, ObservationRegistry.NOOP,
                new RiskNarrativeValidator(mapper), mapper);
    }

    @AfterAll
    static void stopIsolatedMySql() {
        // This class never raises an in-process cancel signal; dropping the DB checker is enough and
        // keeps the shared registry clean for the next test class in the same JVM.
        TaskCancellationRegistry.installPersistentChecker(null);
        if (mySql != null) {
            mySql.close();
        }
    }

    @BeforeEach
    @AfterEach
    void cleanOwnRows() {
        jdbc.update("DELETE FROM chainpilot_task_event WHERE task_id LIKE '" + TASK_PREFIX + "%'");
        jdbc.update("DELETE FROM chainpilot_analysis_task WHERE task_id LIKE '" + TASK_PREFIX + "%'");
    }

    @Test
    void activeTaskKeepsNormalModelStatusesOnRealMySql() {
        String taskId = TASK_PREFIX + "active";
        seedRunningTask(taskId);
        long started = System.nanoTime() - 5_000_000L;
        recorder.modelStarted(taskId, "GENERATING_REPORT");
        recorder.modelCompleted(taskId, "GENERATING_REPORT", started, "model narrative accepted; reasons=2");
        recorder.fallbackUsed(taskId, "GENERATING_REPORT", "EMPTY_MODEL_RESPONSE");
        recorder.modelFailed(taskId, "GENERATING_REPORT", started, new IllegalStateException("provider down"));

        List<SupplyChainTaskEvent> rows = events.findByTaskId(taskId);
        assertEquals(List.of(SupplyChainEventType.MODEL_STARTED, SupplyChainEventType.MODEL_COMPLETED,
                SupplyChainEventType.FALLBACK_USED, SupplyChainEventType.MODEL_FAILED),
                rows.stream().map(SupplyChainTaskEvent::type).toList());
        assertEquals(List.of(SupplyChainEventStatus.STARTED, SupplyChainEventStatus.COMPLETED,
                SupplyChainEventStatus.FALLBACK, SupplyChainEventStatus.FAILED),
                rows.stream().map(SupplyChainTaskEvent::status).toList());
        assertTrue(rows.stream().noneMatch(row -> SupplyChainEventStatus.CANCELLED.equals(row.status())),
                "an active task must never have its model events marked as cancelled");
        assertEquals("model narrative accepted; reasons=2", rows.get(1).summary());
        assertEquals("deterministic fallback selected; reason=EMPTY_MODEL_RESPONSE", rows.get(2).summary());
        assertEquals("IllegalStateException", rows.get(3).errorType());
        assertTrue(rows.get(1).durationMs() >= 0 && rows.get(3).durationMs() >= 0);
        assertEquals(List.of(1, 2, 3, 4), rows.stream().map(SupplyChainTaskEvent::sequence).toList());
    }

    @Test
    void cancellationCommittedElsewhereMarksOnlyTheLateResponseEventsInMySql() throws Exception {
        String taskId = TASK_PREFIX + "race";
        seedRunningTask(taskId);
        long fenceBeforeCancel = number("SELECT fence_version FROM chainpilot_analysis_task WHERE task_id='"
                + taskId + "'");
        CountDownLatch responseGate = new CountDownLatch(1);
        CountDownLatch providerEntered = new CountDownLatch(1);
        AtomicBoolean responded = new AtomicBoolean();
        when(callResponseSpec.chatResponse()).thenAnswer(invocation -> {
            providerEntered.countDown();
            assertTrue(responseGate.await(60, TimeUnit.SECONDS), "the fake provider must stay blocked until cancelled");
            responded.set(true);
            // A real payload that the validator rejects, exactly like the round-35 dual-JVM observation.
            return ChatResponseUtil.createPureResponse("{\"unexpected\":\"shape\"}");
        });

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<RiskAnalysisReport> worker = pool.submit(() -> {
                try (SupplyChainEventRecorder.TaskScope scope = recorder.openTask(taskId)) {
                    return analysisService.analyze("分析风险订单", taskId, List.of(), List.of(), List.of(), 0,
                            new WorkflowPlan(List.of("查询订单风险"), false));
                }
            });

            // The request must be genuinely in flight inside the provider before the cancellation lands:
            // production calls checkCancellation() before each provider attempt, so cancelling earlier
            // would legitimately prevent the call altogether instead of producing a late response.
            assertTrue(providerEntered.await(60, TimeUnit.SECONDS), "the fake provider request never started");
            awaitEventType(taskId, SupplyChainEventType.MODEL_STARTED);
            Map<String, Object> cancelled = cancelThroughSecondConnection(taskId);
            assertEquals(Boolean.TRUE, cancelled.get("changed"));
            assertTaskState(taskId, "CANCELLED", "CANCELLED", null, null, fenceBeforeCancel + 1);
            assertFalse(worker.isDone(), "the provider call must still be blocked when the cancellation lands");

            responseGate.countDown();
            RiskAnalysisReport report = null;
            try {
                report = awaitWorker(worker);
            }
            catch (ExecutionException exception) {
                Throwable cause = exception.getCause();
                assertTrue(cause instanceof SupplyChainTaskCancelledException,
                        "only cancellation may abort the worker, got: " + cause);
            }

            assertTrue(responded.get(), "the provider really answered after the cancellation");
            if (report != null) {
                assertFalse(report.modelAssisted(), "an ignored response must not mark the result model assisted");
            }

            List<SupplyChainTaskEvent> rows = events.findByTaskId(taskId);
            List<SupplyChainTaskEvent> late = rows.stream()
                .filter(row -> SupplyChainEventType.MODEL_COMPLETED.equals(row.type())
                        || SupplyChainEventType.FALLBACK_USED.equals(row.type())
                        || SupplyChainEventType.MODEL_FAILED.equals(row.type()))
                .toList();
            assertEquals(2, late.size(), "the late model completion and the real fallback must both be audited");
            assertTrue(rows.stream()
                .filter(row -> SupplyChainEventType.MODEL_STARTED.equals(row.type()))
                .allMatch(row -> SupplyChainEventStatus.STARTED.equals(row.status())),
                    "the invocation that started before the cancellation must keep its own status");
            assertEquals(List.of(SupplyChainEventType.MODEL_COMPLETED, SupplyChainEventType.FALLBACK_USED),
                    late.stream().map(SupplyChainTaskEvent::type).toList());
            assertTrue(late.stream().allMatch(row -> SupplyChainEventStatus.CANCELLED.equals(row.status())),
                    "every event recorded after the persisted cancellation must carry the task terminal status");
            assertTrue(late.stream().allMatch(row -> row.summary()
                .contains("late response ignored; the task already reached a terminal state")));
            assertTrue(late.get(0).summary().contains("rawOutcome=model narrative rejected"),
                    "the real provider outcome must stay readable: " + late.get(0).summary());
            assertTrue(late.get(1).summary().contains("reason=INVALID_MODEL_NARRATIVE"),
                    "the real degradation reason must not be hidden: " + late.get(1).summary());
            assertTrue(late.get(0).durationMs() > 0, "the blocked provider latency is preserved");

            // Boundary: the late response may not resurrect the task, its owner or its lease.
            assertTaskState(taskId, "CANCELLED", "CANCELLED", null, null, fenceBeforeCancel + 1);
            assertEquals(0, number("SELECT COUNT(*) FROM chainpilot_analysis_task WHERE task_id='" + taskId
                    + "' AND report_json IS NOT NULL"), "no analysis result may be persisted after cancellation");
            assertEquals(0, number("SELECT COUNT(*) FROM chainpilot_task_event WHERE task_id='" + taskId
                    + "' AND event_type='TASK_COMPLETED'"));
            assertEquals(0, number("SELECT COUNT(*) FROM chainpilot_task_event WHERE task_id='" + taskId
                    + "' AND event_type='TASK_FAILED'"));
            assertEquals(1, number("SELECT COUNT(*) FROM chainpilot_task_event WHERE task_id='" + taskId
                    + "' AND event_type='TASK_CANCELLED'"));
            assertEquals(rows.size(), new java.util.HashSet<>(rows.stream()
                .map(SupplyChainTaskEvent::sequence)
                .toList()).size(), "event sequences stay unique");
        }
        finally {
            responseGate.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void repeatedCancellationKeepsOneEventOneFenceAndOneTerminalState() {
        String taskId = TASK_PREFIX + "repeat";
        seedRunningTask(taskId);
        long fenceBeforeCancel = number("SELECT fence_version FROM chainpilot_analysis_task WHERE task_id='"
                + taskId + "'");

        assertEquals(Boolean.TRUE, cancelThroughSecondConnection(taskId).get("changed"));
        Map<String, Object> second = cancelThroughSecondConnection(taskId);
        assertFalse((Boolean) second.get("changed"), "a terminal task cannot be cancelled again");
        assertFalse(tasks.cancelIfActive(taskId));

        assertTaskState(taskId, "CANCELLED", "CANCELLED", null, null, fenceBeforeCancel + 1);
        assertEquals(1, number("SELECT COUNT(*) FROM chainpilot_task_event WHERE task_id='" + taskId
                + "' AND event_type='TASK_CANCELLED'"));
        assertEquals(0, number("SELECT COUNT(*) FROM chainpilot_task_event WHERE task_id='" + taskId
                + "' AND event_type IN ('TASK_COMPLETED','TASK_FAILED')"));
        assertEquals(0, number("SELECT COUNT(*) FROM chainpilot_analysis_task WHERE task_id='" + taskId
                + "' AND owner_instance_id IS NOT NULL"));
    }

    /** Production cancellation shape, executed on the other instance's connection and committed. */
    private static Map<String, Object> cancelThroughSecondConnection(String taskId) {
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(cancellerSource));
        SupplyChainTaskRepository otherTasks = new SupplyChainTaskRepository(
                new JdbcTemplate(cancellerSource), new ObjectMapper().findAndRegisterModules());
        SupplyChainEventRepository otherEvents = new SupplyChainEventRepository(new JdbcTemplate(cancellerSource));
        Boolean changed = transaction.execute(status -> {
            boolean cancelled = otherTasks.cancelIfActive(taskId);
            if (cancelled) {
                otherEvents.append(taskId, SupplyChainEventType.TASK_CANCELLED, "WORKFLOW",
                        SupplyChainEventStatus.CANCELLED, Instant.now(), null, "task cancelled by user", null);
            }
            return cancelled;
        });
        return Map.of("changed", Boolean.TRUE.equals(changed));
    }

    private static void seedRunningTask(String taskId) {
        tasks.create(new SupplyChainTaskStatus(taskId, SupplyChainTaskState.ACCEPTED, "QUEUED", 0, null,
                Instant.now()), "分析风险订单");
        assertTrue(tasks.claimTask(taskId, "cp-r38-worker", tasks.databaseNowMillis() + 600_000));
        tasks.update(new SupplyChainTaskStatus(taskId, SupplyChainTaskState.RUNNING, "GENERATING_REPORT", 0, null,
                Instant.now()));
    }

    private static void assertTaskState(String taskId, String status, String step, String owner, Long lease,
            long fence) {
        Map<String, Object> row = jdbc.queryForMap("SELECT status, current_step, owner_instance_id, "
                + "lease_expires_at, fence_version FROM chainpilot_analysis_task WHERE task_id=?", taskId);
        assertEquals(status, row.get("status"));
        assertEquals(step, row.get("current_step"));
        assertEquals(fence, ((Number) row.get("fence_version")).longValue());
        if (owner == null) {
            assertNull(row.get("owner_instance_id"), "the late response must not restore an owner");
        }
        if (lease == null) {
            assertNull(row.get("lease_expires_at"), "the late response must not restore a lease");
        }
    }

    private static void awaitEventType(String taskId, String type) throws InterruptedException {
        for (int attempt = 0; attempt < 120; attempt++) {
            if (number("SELECT COUNT(*) FROM chainpilot_task_event WHERE task_id='" + taskId + "' AND event_type='"
                    + type + "'") > 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("timed out waiting for event " + type + " on task " + taskId);
    }

    private static RiskAnalysisReport awaitWorker(Future<RiskAnalysisReport> worker) throws Exception {
        return worker.get(60, TimeUnit.SECONDS);
    }

    private static long number(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        assertNotNull(value);
        return value;
    }
}
