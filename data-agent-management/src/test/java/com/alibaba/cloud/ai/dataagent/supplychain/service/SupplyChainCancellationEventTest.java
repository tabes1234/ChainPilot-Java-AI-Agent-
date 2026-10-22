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
package com.alibaba.cloud.ai.dataagent.supplychain.service;

import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskAnalysisReport;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainEventStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainEventType;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskEvent;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskState;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.WorkflowPlan;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.SupplyChainEventRepository;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.SupplyChainTaskRepository;
import com.alibaba.cloud.ai.dataagent.util.ChatResponseUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Boundary between a cancellation that another instance persisted and a provider response that only
 * arrives afterwards. The invocation and its outcome stay in the audit trail — the type is never
 * rewritten and never suppressed — but the event is marked with the task's real terminal status so
 * the timeline cannot be read as the task resuming.
 */
class SupplyChainCancellationEventTest {

    private static final String TASK_ID = "late-model-response-task";

    private JdbcTemplate jdbc;

    private SupplyChainTaskRepository tasks;

    private SupplyChainEventRepository events;

    private SupplyChainEventRecorder recorder;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:chainpilot-late-response-" + java.util.UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", ""));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        tasks = new SupplyChainTaskRepository(jdbc, mapper);
        events = new SupplyChainEventRepository(jdbc);
        recorder = new SupplyChainEventRecorder(events);
        tasks.create(new SupplyChainTaskStatus(TASK_ID, SupplyChainTaskState.ACCEPTED, "QUEUED", 0, null,
                Instant.now()), "分析风险订单");
        tasks.claimTask(TASK_ID, "cp-jvm-a", tasks.databaseNowMillis() + 600_000);
        tasks.update(new SupplyChainTaskStatus(TASK_ID, SupplyChainTaskState.RUNNING, "GENERATING_REPORT", 0,
                null, Instant.now()));
        // The database, not a process-local flag, decides that the task is gone.
        TaskCancellationRegistry.installPersistentChecker(taskId -> tasks.findTask(taskId)
            .map(status -> !SupplyChainTaskState.ACCEPTED.equals(status.status())
                    && !SupplyChainTaskState.RUNNING.equals(status.status()))
            .orElse(false));
    }

    @AfterEach
    void clearRegistry() {
        TaskCancellationRegistry.reset();
    }

    @Test
    void modelEventsOnAnActiveTaskKeepTheirNormalStatuses() {
        long started = System.nanoTime() - 5_000_000L;
        recorder.modelStarted(TASK_ID, "GENERATING_REPORT");
        recorder.modelCompleted(TASK_ID, "GENERATING_REPORT", started, "model narrative accepted; reasons=1");
        recorder.fallbackUsed(TASK_ID, "GENERATING_REPORT", "EMPTY_MODEL_RESPONSE");
        recorder.modelFailed(TASK_ID, "GENERATING_REPORT", started, new IllegalStateException("provider down"));

        List<SupplyChainTaskEvent> rows = events.findByTaskId(TASK_ID);
        assertEquals(List.of(SupplyChainEventStatus.STARTED, SupplyChainEventStatus.COMPLETED,
                SupplyChainEventStatus.FALLBACK, SupplyChainEventStatus.FAILED),
                rows.stream().map(SupplyChainTaskEvent::status).toList());
        assertEquals(List.of(SupplyChainEventType.MODEL_STARTED, SupplyChainEventType.MODEL_COMPLETED,
                SupplyChainEventType.FALLBACK_USED, SupplyChainEventType.MODEL_FAILED),
                rows.stream().map(SupplyChainTaskEvent::type).toList());
        assertEquals("model narrative accepted; reasons=1", rows.get(1).summary());
        assertEquals("IllegalStateException", rows.get(3).errorType());
        assertTrue(rows.get(1).durationMs() != null && rows.get(1).durationMs() >= 0);
    }

    @Test
    void responsesThatArriveAfterCancellationKeepTheirTypeButAreMarkedIgnored() {
        assertTrue(tasks.cancelIfActive(TASK_ID));
        long started = System.nanoTime() - 5_000_000L;
        recorder.modelCompleted(TASK_ID, "GENERATING_REPORT", started, "model narrative accepted; reasons=1");
        recorder.fallbackUsed(TASK_ID, "GENERATING_REPORT", "INVALID_MODEL_NARRATIVE");
        recorder.modelFailed(TASK_ID, "GENERATING_REPORT", started, new IllegalStateException("provider down"));

        List<SupplyChainTaskEvent> rows = events.findByTaskId(TASK_ID);
        assertEquals(List.of(SupplyChainEventType.MODEL_COMPLETED, SupplyChainEventType.FALLBACK_USED,
                SupplyChainEventType.MODEL_FAILED), rows.stream().map(SupplyChainTaskEvent::type).toList());
        assertTrue(rows.stream().allMatch(row -> SupplyChainEventStatus.CANCELLED.equals(row.status())),
                "every late response event must show the task's real terminal status");
        assertTrue(rows.stream().allMatch(row -> row.summary()
            .contains("late response ignored; the task already reached a terminal state")));
        assertTrue(rows.get(0).summary().contains("rawOutcome=model narrative accepted; reasons=1"),
                "the real provider outcome stays auditable");
        assertTrue(rows.get(1).summary().contains("reason=INVALID_MODEL_NARRATIVE"),
                "a real degradation must not be hidden");
        assertEquals("IllegalStateException", rows.get(2).errorType());
        assertTrue(rows.get(0).durationMs() != null, "the measured provider latency is preserved");
        assertEquals(SupplyChainTaskState.CANCELLED, tasks.findTask(TASK_ID).orElseThrow().status());
    }

    @Test
    void lateCompletionCannotWriteAReportOrChangeTheTerminalState() {
        assertTrue(tasks.cancelIfActive(TASK_ID));
        long fenceAfterCancel = fence(TASK_ID);
        recorder.modelCompleted(TASK_ID, "GENERATING_REPORT", System.nanoTime(), "model narrative accepted");

        assertFalse(tasks.completeAnalysis(TASK_ID, report(), "DONE", 0, "cp-jvm-a", fenceAfterCancel - 1));
        assertFalse(tasks.completeAnalysis(TASK_ID, report(), "DONE", 0));
        assertFalse(tasks.updateOwned(new SupplyChainTaskStatus(TASK_ID, SupplyChainTaskState.RUNNING, "ZOMBIE",
                0, null, Instant.now()), "cp-jvm-a", fenceAfterCancel - 1));
        assertFalse(tasks.renewTaskLease(TASK_ID, "cp-jvm-a", fenceAfterCancel - 1,
                tasks.databaseNowMillis() + 60_000));
        assertFalse(tasks.findReport(TASK_ID).isPresent());
        SupplyChainTaskStatus cancelled = tasks.findTask(TASK_ID).orElseThrow();
        assertEquals(SupplyChainTaskState.CANCELLED, cancelled.status());
        assertEquals("CANCELLED", cancelled.currentStep());
        assertEquals(0, count("SELECT COUNT(*) FROM chainpilot_task_event WHERE task_id='" + TASK_ID
                + "' AND event_type='TASK_COMPLETED'"));
        assertEquals(0, count("SELECT COUNT(*) FROM chainpilot_analysis_task WHERE task_id='" + TASK_ID
                + "' AND report_json IS NOT NULL"));
    }

    @Test
    void concurrentCancellationAndCompletionProduceExactlyOneTerminalOutcome() throws Exception {
        tasks.create(new SupplyChainTaskStatus("race-task", SupplyChainTaskState.ACCEPTED, "QUEUED", 0, null,
                Instant.now()), "race");
        assertTrue(tasks.claimTask("race-task", "cp-jvm-a", tasks.databaseNowMillis() + 600_000));
        tasks.update(new SupplyChainTaskStatus("race-task", SupplyChainTaskState.RUNNING, "GENERATING_REPORT", 0,
                null, Instant.now()));
        long fence = fence("race-task");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<Boolean> canceller = pool.submit(() -> {
                go.await();
                boolean changed = tasks.cancelIfActive("race-task");
                if (changed) {
                    recorder.taskCancelled("race-task", "WORKFLOW");
                }
                return changed;
            });
            Future<Boolean> completer = pool.submit(() -> {
                go.await();
                boolean done = tasks.completeAnalysis("race-task", report("race-task"), "DONE", 0, "cp-jvm-a",
                        fence);
                if (done) {
                    recorder.taskCompleted("race-task", System.nanoTime(), "report generated");
                }
                return done;
            });
            go.countDown();
            boolean cancelled = canceller.get(20, TimeUnit.SECONDS);
            boolean completed = completer.get(20, TimeUnit.SECONDS);

            assertEquals(1, (cancelled ? 1 : 0) + (completed ? 1 : 0), "exactly one terminal write may win");
            assertEquals(1, count("SELECT COUNT(*) FROM chainpilot_analysis_task WHERE task_id='race-task'"
                    + " AND status IN ('CANCELLED','COMPLETED')"));
            assertEquals(1, count("SELECT COUNT(*) FROM chainpilot_task_event WHERE task_id='race-task'"
                    + " AND (event_type='TASK_CANCELLED' OR event_type='TASK_COMPLETED')"),
                    "one terminal state must produce exactly one terminal event");
            assertEquals(cancelled ? 1 : 0, count("SELECT COUNT(*) FROM chainpilot_task_event"
                    + " WHERE task_id='race-task' AND event_type='TASK_CANCELLED'"));
            assertEquals(cancelled ? 0 : 1, count("SELECT COUNT(*) FROM chainpilot_analysis_task"
                    + " WHERE task_id='race-task' AND report_json IS NOT NULL"),
                    "a cancelled task never keeps a business result");
            assertFalse(tasks.cancelIfActive("race-task"), "a terminal task cannot be cancelled again");
            assertFalse(tasks.completeAnalysis("race-task", report("race-task"), "DONE", 0, "cp-jvm-a", fence),
                    "the same fence cannot write a second terminal result");
        }
        finally {
            pool.shutdownNow();
        }
    }

    @Test
    void providerResponseLandingAfterCancellationIsAuditedButNeverPersisted() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        com.alibaba.cloud.ai.dataagent.service.aimodelconfig.AiModelRegistry modelRegistry =
                mock(com.alibaba.cloud.ai.dataagent.service.aimodelconfig.AiModelRegistry.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callResponse = mock(ChatClient.CallResponseSpec.class);
        when(modelRegistry.getChatClient()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.toolCallbacks(any(ToolCallback[].class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponse);
        // The provider answer comes back only after another instance has cancelled the task.
        when(callResponse.chatResponse()).thenAnswer(invocation -> {
            tasks.cancelIfActive(TASK_ID);
            return ChatResponseUtil.createPureResponse("{\"not\":\"a valid narrative contract\"}");
        });

        SupplyChainQueryService queryService = mock(SupplyChainQueryService.class);
        SupplyChainKnowledgeService knowledgeService = mock(SupplyChainKnowledgeService.class);
        when(knowledgeService.retrieve(anyString())).thenReturn(List.of());
        SupplyChainAnalysisService analysisService = new SupplyChainAnalysisService(queryService,
                new SupplyChainAgentTools(queryService, mock(DisposalRecommendationService.class),
                        mock(DisposalSimulationService.class), mock(DisposalExecutionService.class),
                        mock(DisposalScenarioComparisonService.class), mock(DisposalPlanService.class), recorder),
                modelRegistry, knowledgeService, recorder, io.micrometer.observation.ObservationRegistry.NOOP,
                new RiskNarrativeValidator(mapper), mapper);

        RiskAnalysisReport returned = null;
        boolean cancelledOut;
        try (SupplyChainEventRecorder.TaskScope ignored = recorder.openTask(TASK_ID)) {
            returned = analysisService.analyze("分析风险订单", TASK_ID, List.of(), List.of(), List.of(), 0,
                    new WorkflowPlan(List.of("查询订单风险"), false));
            cancelledOut = false;
        }
        catch (SupplyChainTaskCancelledException exception) {
            cancelledOut = true;
        }

        // Boundary invariants, whichever checkpoint observed the cancellation first:
        assertEquals(SupplyChainTaskState.CANCELLED, tasks.findTask(TASK_ID).orElseThrow().status());
        assertFalse(tasks.findReport(TASK_ID).isPresent(), "no analysis result may be committed after cancellation");
        tasks.update(new SupplyChainTaskStatus(TASK_ID, SupplyChainTaskState.RUNNING, "ZOMBIE", 0, null,
                Instant.now()));
        assertEquals(SupplyChainTaskState.CANCELLED, tasks.findTask(TASK_ID).orElseThrow().status(),
                "a late unfenced worker update cannot reactivate the cancelled task");
        assertEquals(0, count("SELECT COUNT(*) FROM chainpilot_analysis_task WHERE task_id='" + TASK_ID
                + "' AND report_json IS NOT NULL"));
        if (returned != null) {
            assertFalse(returned.modelAssisted(), "an ignored response must not mark the result model assisted");
        }
        assertFalse(cancelledOut && returned != null);

        List<SupplyChainTaskEvent> rows = events.findByTaskId(TASK_ID);
        assertTrue(rows.stream().anyMatch(row -> SupplyChainEventType.MODEL_STARTED.equals(row.type())),
                "the invocation really happened and stays in the audit trail");
        List<SupplyChainTaskEvent> lateResponses = rows.stream()
            .filter(row -> SupplyChainEventType.MODEL_COMPLETED.equals(row.type())
                    || SupplyChainEventType.MODEL_FAILED.equals(row.type())
                    || SupplyChainEventType.FALLBACK_USED.equals(row.type()))
            .toList();
        assertTrue(lateResponses.stream().allMatch(row -> SupplyChainEventStatus.CANCELLED.equals(row.status())),
                "no provider outcome recorded after the cancellation may look applied");
        assertTrue(lateResponses.stream().allMatch(row -> row.summary().contains("late response ignored")));
        assertEquals(0, count("SELECT COUNT(*) FROM chainpilot_task_event WHERE task_id='" + TASK_ID
                + "' AND event_type='TASK_COMPLETED'"));
    }

    private RiskAnalysisReport report() {
        return report(TASK_ID);
    }

    private RiskAnalysisReport report(String taskId) {
        return new RiskAnalysisReport(taskId, SupplyChainTaskState.COMPLETED, "已完成", List.of("查询订单"), false,
                List.of(), List.of(), List.of(), List.of(), false, 0, Instant.now());
    }

    private long fence(String taskId) {
        return jdbc.queryForObject("SELECT fence_version FROM chainpilot_analysis_task WHERE task_id=?",
                Long.class, taskId);
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        assertNotNull(value);
        return value.intValue();
    }
}
