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

import com.alibaba.cloud.ai.dataagent.service.aimodelconfig.AiModelRegistry;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskAnalysisReport;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainEventType;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskEvent;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskState;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.WorkflowPlan;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.SupplyChainDemoData;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What a model-call budget abort does to task state when it meets cancellation and when it meets a
 * lost lease. The abort is the same {@code IllegalStateException} the real-model acceptance budget
 * throws from {@code ObservationHandler.onStart}, so this exercises the production combination of
 * task ownership (rounds 32-35), cancellation and late responses (rounds 36-38) and the request
 * budget (round 40) instead of each mechanism alone.
 *
 * <p>Unlike the existing workflow tests, this harness attaches a real {@link TaskLeaseManager}, so
 * the fenced write path runs - not the unfenced branch used when no lease manager is present.
 */
class SupplyChainBudgetAbortStateTest {

    private static final String QUESTION = "分析今天有哪些订单可能延期";

    private static final String OWNER = "cp-jvm-a";

    private static final String OTHER_OWNER = "cp-jvm-b";

    private static final long LEASE_TTL_MS = 600_000L;

    private static final String BUDGET_MESSAGE = "Real-model acceptance outbound request budget exceeded at"
            + " the boundary http.client.requests: attempt=4, limit=3. The request was blocked before it was sent";

    private JdbcTemplate jdbc;

    private SupplyChainTaskRepository tasks;

    private SupplyChainEventRepository events;

    private SupplyChainEventRecorder recorder;

    private SupplyChainWorkflowService workflow;

    private SupplyChainAnalysisService analysisService;

    private TaskLeaseManager leaseManager;

    private AiModelRegistry modelRegistry;

    private final AtomicBoolean providerReached = new AtomicBoolean();

    @BeforeEach
    void setUp() throws Exception {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:chainpilot-budget-abort-"
                + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", ""));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        tasks = new SupplyChainTaskRepository(jdbc, mapper);
        events = new SupplyChainEventRepository(jdbc);
        recorder = new SupplyChainEventRecorder(events);
        providerReached.set(false);

        SupplyChainQueryService queryService = new SupplyChainQueryService(new SupplyChainDemoData());
        SupplyChainAgentTools tools = new SupplyChainAgentTools(queryService,
                mock(DisposalRecommendationService.class), mock(DisposalSimulationService.class),
                mock(DisposalExecutionService.class), mock(DisposalScenarioComparisonService.class),
                mock(DisposalPlanService.class), recorder);
        SupplyChainKnowledgeService knowledgeService = mock(SupplyChainKnowledgeService.class);
        when(knowledgeService.retrieve(anyString())).thenReturn(List.of());
        modelRegistry = mock(AiModelRegistry.class);
        analysisService = new SupplyChainAnalysisService(queryService, tools, modelRegistry, knowledgeService,
                recorder, io.micrometer.observation.ObservationRegistry.NOOP, new RiskNarrativeValidator(mapper),
                mapper);

        leaseManager = new TaskLeaseManager(tasks, new InstanceIdentity(OWNER), LEASE_TTL_MS);
        workflow = new SupplyChainWorkflowService(tools, analysisService, mock(SupplyChainDecisionService.class),
                mapper, tasks, events, recorder, Runnable::run);
        workflow.setTransactionManager(new DataSourceTransactionManager(jdbc.getDataSource()));
        workflow.setTaskLeaseManager(leaseManager);
    }

    @AfterEach
    void clearRegistry() {
        TaskCancellationRegistry.reset();
    }

    /**
     * Scenario 1: the budget is exhausted while this instance owns and runs the task. The task must not
     * be credited as model assisted, the abort must stay in the audit trail as a real model failure plus
     * degradation, exactly one terminal event may exist, and a completed row must not be re-claimable.
     */
    @Test
    void budgetAbortDuringExecutionDegradesTruthfullyAndKeepsOneTerminalState() {
        answerWith(() -> {
            throw new IllegalStateException(BUDGET_MESSAGE);
        });

        String taskId = workflow.submit(QUESTION, "ANALYSIS").taskId();
        SupplyChainTaskStatus terminal = workflow.task(taskId);

        assertEquals(SupplyChainTaskState.COMPLETED, terminal.status(),
                "the current contract completes the run on the deterministic plan and summary");
        RiskAnalysisReport report = workflow.report(taskId);
        assertFalse(report.modelAssisted(), "a blocked model turn must never be credited as model assistance");
        assertFalse(report.plannerAssisted(), "the planner turn was blocked too");
        assertEquals(RiskAnalysisReport.NARRATIVE_DETERMINISTIC_FALLBACK, report.narrativeSource());
        assertTrue(report.executionPlan().size() >= 3, "the deterministic plan must still be executed");
        assertFalse(providerReached.get(), "the fake provider callback never produced a response");

        List<SupplyChainTaskEvent> rows = events.findByTaskId(taskId);
        assertEquals(2, count(rows, SupplyChainEventType.MODEL_STARTED), "both model boundaries were attempted");
        assertEquals(2, count(rows, SupplyChainEventType.MODEL_FAILED), "the abort must stay auditable");
        assertEquals(2, count(rows, SupplyChainEventType.FALLBACK_USED), "the degradation must be recorded");
        assertEquals(0, count(rows, SupplyChainEventType.MODEL_COMPLETED),
                "nothing may be recorded as a completed model call when the budget refused it");
        assertEquals(1, count(rows, SupplyChainEventType.TASK_COMPLETED), "exactly one terminal event");
        assertEquals(0, count(rows, SupplyChainEventType.TASK_FAILED));
        assertEquals(0, count(rows, SupplyChainEventType.TASK_CANCELLED));
        assertNotEquals("LEASE_EXPIRED", terminal.currentStep(),
                "a budget abort must not be reported as an expired lease");
        assertTrue(rows.stream()
            .filter(row -> SupplyChainEventType.MODEL_FAILED.equals(row.type()))
            .allMatch(row -> "IllegalStateException".equals(row.errorType())),
                "the recorded failure type must be the real abort, not a generic error");

        long fence = fence(taskId);
        assertEquals(OWNER, owner(taskId), "completion does not clear ownership; only cancel or lease expiry does");
        assertNotNull(leaseExpiry(taskId));
        assertFalse(tasks.claimTask(taskId, OWNER, tasks.databaseNowMillis() + LEASE_TTL_MS),
                "a completed task cannot be re-claimed");
        assertFalse(tasks.renewTaskLease(taskId, OWNER, fence, tasks.databaseNowMillis() + LEASE_TTL_MS),
                "a completed task cannot be renewed");
        assertFalse(tasks.completeAnalysis(taskId, report, "DONE", 0, OWNER, fence),
                "a second terminal write by the same owner must be refused");
        assertFalse(tasks.cancelIfActive(taskId), "cancellation cannot land after the terminal write");
        assertEquals(SupplyChainTaskState.COMPLETED, workflow.task(taskId).status());
        assertEquals(1, count(events.findByTaskId(taskId), SupplyChainEventType.TASK_COMPLETED));
    }

    /**
     * Scenario 2 and 3: cancellation lands while the model call is in flight, and the abort that follows
     * it may not write a report, a success event or a second terminal state.
     */
    @Test
    void cancellationWinsOverTheAbortThatFollowsIt() throws Exception {
        CountDownLatch modelEntered = new CountDownLatch(1);
        CountDownLatch cancelLanded = new CountDownLatch(1);
        AtomicReference<String> runningTaskId = new AtomicReference<>();

        when(modelRegistry.getChatClient()).thenAnswer(invocation -> chatClient(() -> {
            runningTaskId.set(recorder.currentTaskId());
            modelEntered.countDown();
            awaitQuietly(cancelLanded);
            throw new IllegalStateException(BUDGET_MESSAGE);
        }));

        Thread canceller = new Thread(() -> {
            awaitQuietly(modelEntered);
            String taskId = runningTaskId.get();
            assertNotNull(taskId, "the model boundary must know which task it is running");
            assertEquals(SupplyChainTaskState.CANCELLED, workflow.cancel(taskId).status());
            cancelLanded.countDown();
        }, "budget-abort-canceller");
        canceller.start();

        String taskId = workflow.submit(QUESTION, "ANALYSIS").taskId();
        canceller.join(TimeUnit.SECONDS.toMillis(20));
        assertFalse(canceller.isAlive(), "the canceller thread must have finished");

        SupplyChainTaskStatus cancelled = workflow.task(taskId);
        assertEquals(taskId, runningTaskId.get());
        assertEquals(SupplyChainTaskState.CANCELLED, cancelled.status(),
                "a cancellation that lands first must not be overwritten by the abort path");
        assertTrue(tasks.findReport(taskId).isEmpty(), "the aborted response cannot persist a report");
        assertNull(owner(taskId), "cancellation clears the owner");
        assertNull(leaseExpiry(taskId), "cancellation clears the lease");
        assertEquals(2, fence(taskId), "claim bumps the fence once and cancellation bumps it once more");

        List<SupplyChainTaskEvent> rows = events.findByTaskId(taskId);
        assertEquals(1, count(rows, SupplyChainEventType.TASK_CANCELLED));
        assertEquals(0, count(rows, SupplyChainEventType.TASK_COMPLETED));
        assertEquals(0, count(rows, SupplyChainEventType.MODEL_COMPLETED));
        assertTrue(count(rows, SupplyChainEventType.MODEL_STARTED) >= 1,
                "the invocation really started and stays in the trail");
    }

    /**
     * Scenario 3 reversed: once the abort has already completed the task, cancellation is refused and the
     * terminal row keeps its owner, lease and fence.
     */
    @Test
    void cancellationIsRefusedOnceTheAbortAlreadyCompletedTheTask() {
        answerWith(() -> {
            throw new IllegalStateException(BUDGET_MESSAGE);
        });

        String taskId = workflow.submit(QUESTION, "ANALYSIS").taskId();
        long fence = fence(taskId);

        SupplyChainTaskStatus refused = workflow.cancel(taskId);
        assertEquals(SupplyChainTaskState.COMPLETED, refused.status());
        assertEquals(SupplyChainTaskState.COMPLETED, workflow.task(taskId).status());
        assertEquals(fence, fence(taskId), "a refused cancellation may not bump the fence");
        assertEquals(OWNER, owner(taskId));
        assertNotNull(leaseExpiry(taskId));

        List<SupplyChainTaskEvent> rows = events.findByTaskId(taskId);
        assertEquals(0, count(rows, SupplyChainEventType.TASK_CANCELLED));
        assertEquals(1, count(rows, SupplyChainEventType.TASK_COMPLETED));
    }

    /**
     * Scenario 4: a second instance takes the task over (new owner, new lease, fence bumped) while this
     * instance still believes it owns it. Renewal must detect the loss, the late response must be refused
     * before the provider is reached, the displaced owner may not write or revive the row, and the new
     * owner must still be able to complete it - so fencing does not wedge the task.
     */
    @Test
    void lostLeaseRefusesTheLateResponseAndNeverRevivesTheTask() {
        String taskId = "lease-loss-task";
        tasks.create(new SupplyChainTaskStatus(taskId, SupplyChainTaskState.ACCEPTED, "QUEUED", 0, null,
                Instant.now()), QUESTION);
        assertNotNull(leaseManager.claim(taskId), "this instance must own the task it is executing");
        tasks.update(new SupplyChainTaskStatus(taskId, SupplyChainTaskState.RUNNING, "GENERATING_REPORT", 0, null,
                Instant.now()));
        long staleFence = fence(taskId);

        long liveLease = tasks.databaseNowMillis() + LEASE_TTL_MS;
        jdbc.update("UPDATE chainpilot_analysis_task SET owner_instance_id=?, lease_expires_at=?, "
                + "fence_version=fence_version+1 WHERE task_id=?", OTHER_OWNER, liveLease, taskId);
        long newFence = fence(taskId);
        assertEquals(staleFence + 1, newFence);

        leaseManager.renewOwnedLeases();
        assertTrue(leaseManager.isLost(taskId), "a displaced owner must learn that it lost the lease");
        assertTrue(TaskCancellationRegistry.isCancelled(taskId),
                "lease loss is a local cancellation signal, so a late response counts as ignored");

        answerWith(() -> {
            providerReached.set(true);
            return ChatResponseUtil.createPureResponse("model answered after the lease moved away");
        });
        AtomicReference<RiskAnalysisReport> late = new AtomicReference<>();
        SupplyChainTaskCancelledException refused = assertThrows(SupplyChainTaskCancelledException.class, () -> {
            try (SupplyChainEventRecorder.TaskScope scope = recorder.openTask(taskId)) {
                late.set(analysisService.analyze(QUESTION, taskId, List.of(), List.of(), List.of(), 0,
                        new WorkflowPlan(List.of("查询订单风险"), false)));
            }
        });
        assertEquals(taskId, refused.taskId());
        assertFalse(providerReached.get(), "the cancellation guard must stop the call before the provider");
        assertNull(late.get(), "no report may be produced by a displaced owner");

        assertFalse(tasks.completeAnalysis(taskId, report(taskId), "DONE", 0, OWNER, staleFence),
                "the displaced owner may not write the terminal result");
        assertFalse(tasks.updateOwned(new SupplyChainTaskStatus(taskId, SupplyChainTaskState.RUNNING, "ZOMBIE", 0,
                null, Instant.now()), OWNER, staleFence), "the displaced owner may not change the state");
        assertFalse(tasks.renewTaskLease(taskId, OWNER, staleFence, liveLease),
                "the displaced owner may not restore its lease");
        assertFalse(tasks.claimTask(taskId, OWNER, liveLease),
                "the displaced owner may not take the task back while the new lease is live");

        SupplyChainTaskStatus stillRunning = tasks.findTask(taskId).orElseThrow();
        assertEquals(SupplyChainTaskState.RUNNING, stillRunning.status(),
                "no late write may revive, end or re-own the task");
        assertEquals(OTHER_OWNER, owner(taskId));
        assertEquals(newFence, fence(taskId));

        List<SupplyChainTaskEvent> rows = events.findByTaskId(taskId);
        assertEquals(0, count(rows, SupplyChainEventType.TASK_COMPLETED),
                "the displaced instance may not have written any terminal event");
        assertEquals(0, count(rows, SupplyChainEventType.TASK_CANCELLED));
        assertEquals(0, count(rows, SupplyChainEventType.TASK_FAILED));

        assertTrue(tasks.completeAnalysis(taskId, report(taskId), "DONE", 0, OTHER_OWNER, newFence),
                "the real owner must still be able to finish the task");
        assertEquals(SupplyChainTaskState.COMPLETED, tasks.findTask(taskId).orElseThrow().status());
    }

    private void answerWith(Callable<Object> outcome) {
        when(modelRegistry.getChatClient()).thenAnswer(invocation -> chatClient(outcome));
    }

    private ChatClient chatClient(Callable<Object> outcome) {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callResponse = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.toolCallbacks(any(ToolCallback[].class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponse);
        when(callResponse.chatResponse()).thenAnswer(invocation -> outcome.call());
        return chatClient;
    }

    private RiskAnalysisReport report(String taskId) {
        return new RiskAnalysisReport(taskId, SupplyChainTaskState.COMPLETED, "已完成", List.of("查询订单"), false,
                List.of(), List.of(), List.of(), List.of(), false, 0, Instant.now());
    }

    private long fence(String taskId) {
        return jdbc.queryForObject("SELECT fence_version FROM chainpilot_analysis_task WHERE task_id=?",
                Long.class, taskId);
    }

    private String owner(String taskId) {
        return jdbc.queryForObject("SELECT owner_instance_id FROM chainpilot_analysis_task WHERE task_id=?",
                String.class, taskId);
    }

    private Long leaseExpiry(String taskId) {
        return jdbc.queryForObject("SELECT lease_expires_at FROM chainpilot_analysis_task WHERE task_id=?",
                Long.class, taskId);
    }

    private static int count(List<SupplyChainTaskEvent> rows, String type) {
        return (int) rows.stream().filter(row -> type.equals(row.type())).count();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            assertTrue(latch.await(20, TimeUnit.SECONDS), "the other side of the race never arrived");
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

}
