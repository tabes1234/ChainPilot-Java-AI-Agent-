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

import com.alibaba.cloud.ai.dataagent.supplychain.model.InventoryPosition;
import com.alibaba.cloud.ai.dataagent.supplychain.model.OrderRiskAssessment;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskAnalysisReport;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplierRiskProfile;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainEventType;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskEvent;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskSummary;
import com.alibaba.cloud.ai.dataagent.supplychain.model.WorkflowPlan;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.SupplyChainDemoData;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.SupplyChainEventRepository;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.SupplyChainTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SupplyChainWorkflowServiceTest {

    @AfterEach
    void clearCancellationSignals() {
        TaskCancellationRegistry.reset();
    }

    @Test
    void graphRunsQueryValidationAndReportAndTracksTaskStatus() throws Exception {
        WorkflowFixture fixture = fixture(null);
        SupplyChainWorkflowService workflowService = createWorkflowService(Runnable::run, fixture);
        assertFalse(new SupplyChainAgentTools(new SupplyChainQueryService(new SupplyChainDemoData()),
                mock(DisposalRecommendationService.class), mock(DisposalSimulationService.class),
                mock(DisposalExecutionService.class), mock(DisposalScenarioComparisonService.class),
                mock(DisposalPlanService.class), fixture.eventRecorder()).findAtRiskOrdersForWorkflow().isEmpty());

        RiskAnalysisReport report = workflowService.analyze("查找延期订单");

        assertEquals("COMPLETED", report.status());
        assertEquals(List.of("查询订单风险", "校验数据"), report.executionPlan());
        assertFalse(report.atRiskOrders().isEmpty());
        assertEquals("ORD-1001", report.atRiskOrders().get(0).order().orderId());
        assertEquals(List.of(), report.toolCalls());
        assertEquals("COMPLETED", workflowService.task(report.taskId()).status());
        assertEquals("DONE", workflowService.task(report.taskId()).currentStep());
        assertEquals(report, workflowService.report(report.taskId()));

        List<SupplyChainTaskEvent> events = workflowService.events(report.taskId());
        assertFalse(events.isEmpty());
        assertEquals(SupplyChainEventType.TASK_QUEUED, events.get(0).type());
        assertEquals(SupplyChainEventType.TASK_COMPLETED, events.get(events.size() - 1).type());
        for (int index = 0; index < events.size(); index++) {
            assertEquals(index + 1, events.get(index).sequence());
            assertEquals(report.taskId(), events.get(index).taskId());
            assertNotNull(events.get(index).occurredAt());
        }
        assertEquals(events.size(), events.stream().map(SupplyChainTaskEvent::eventId).distinct().count());

        Set<String> completedNodes = events.stream()
            .filter(event -> SupplyChainEventType.NODE_COMPLETED.equals(event.type()))
            .map(SupplyChainTaskEvent::stage)
            .collect(Collectors.toSet());
        assertEquals(Set.of("PLANNING", "QUERYING_ORDERS", "QUERYING_INVENTORY", "QUERYING_SUPPLIERS",
                "VALIDATING", "GENERATING_REPORT"), completedNodes);
        Set<String> completedTools = events.stream()
            .filter(event -> SupplyChainEventType.TOOL_COMPLETED.equals(event.type()))
            .map(SupplyChainTaskEvent::stage)
            .collect(Collectors.toSet());
        assertEquals(Set.of("findAtRiskOrders", "findInventory", "findSuppliers"), completedTools);
        assertTrue(events.stream()
            .filter(event -> SupplyChainEventType.TOOL_COMPLETED.equals(event.type()))
            .allMatch(event -> event.summary().startsWith("workflow tool;")));
        assertTrue(events.stream().noneMatch(event -> SupplyChainEventType.NODE_FAILED.equals(event.type())
                || SupplyChainEventType.TASK_FAILED.equals(event.type())));
    }

    @Test
    void asyncSubmissionCanBePolledAndReturnsReportAfterCompletion() throws Exception {
        WorkflowFixture fixture = fixture(null);
        List<Runnable> pendingJobs = new ArrayList<>();
        SupplyChainWorkflowService workflowService = createWorkflowService(pendingJobs::add, fixture);

        var accepted = workflowService.submit("查找延期订单");

        assertEquals("ACCEPTED", accepted.status());
        assertEquals("QUEUED", workflowService.task(accepted.taskId()).currentStep());
        assertEquals(List.of(SupplyChainEventType.TASK_QUEUED), workflowService.events(accepted.taskId())
            .stream()
            .map(SupplyChainTaskEvent::type)
            .toList());
        pendingJobs.get(0).run();
        assertEquals("COMPLETED", workflowService.task(accepted.taskId()).status());
        assertEquals("DONE", workflowService.task(accepted.taskId()).currentStep());
        assertEquals("COMPLETED", workflowService.report(accepted.taskId()).status());
        var history = workflowService.tasks(5);
        assertEquals(1, history.size());
        assertEquals(accepted.taskId(), history.get(0).taskId());
        assertEquals("查找延期订单", history.get(0).question());
        assertTrue(history.get(0).reportAvailable());
        assertEquals(SupplyChainEventType.TASK_COMPLETED,
                workflowService.events(accepted.taskId()).get(workflowService.events(accepted.taskId()).size() - 1)
                    .type());
    }

    @Test
    void historyPagesExposeTotalsFiltersAndEmptyLastPage() throws Exception {
        WorkflowFixture fixture = fixture(null);
        Instant now = Instant.now();
        fixture.taskRepository().create(new SupplyChainTaskStatus("completed-new", "COMPLETED", "DONE", 0, null,
                now.minusSeconds(1)), "newest");
        fixture.taskRepository().create(new SupplyChainTaskStatus("running-task", "RUNNING", "QUERYING_ORDERS", 0,
                null, now.minusSeconds(2)), "running");
        fixture.taskRepository().create(new SupplyChainTaskStatus("completed-old", "COMPLETED", "DONE", 0, null,
                now.minusSeconds(3)), "oldest");
        SupplyChainWorkflowService workflowService = createWorkflowService(Runnable::run, fixture);

        var firstCompletedPage = workflowService.tasks("COMPLETED", 1, 1);

        assertEquals(List.of("completed-new"),
                firstCompletedPage.items().stream().map(SupplyChainTaskSummary::taskId).toList());
        assertEquals(2, firstCompletedPage.totalElements());
        assertEquals(2, firstCompletedPage.totalPages());
        assertEquals(1, firstCompletedPage.page());
        assertEquals(1, firstCompletedPage.pageSize());

        var secondPage = workflowService.tasks(null, 2, 2);
        assertEquals(List.of("completed-old"),
                secondPage.items().stream().map(SupplyChainTaskSummary::taskId).toList());
        assertEquals(3, secondPage.totalElements());
        assertEquals(2, secondPage.totalPages());

        var beyondLastPage = workflowService.tasks(null, 3, 2);
        assertTrue(beyondLastPage.items().isEmpty());
        assertEquals(3, beyondLastPage.totalElements());
        assertEquals(2, beyondLastPage.totalPages());

        var legacyLimit = workflowService.tasks(2);
        assertEquals(List.of("completed-new", "running-task"),
                legacyLimit.stream().map(SupplyChainTaskSummary::taskId).toList());
    }

    @Test
    void reportNodeFailureRecordsFailureWithoutCompletedTaskEvent() throws Exception {
        SupplyChainAnalysisService analysis = mock(SupplyChainAnalysisService.class);
        when(analysis.createPlan(anyString()))
            .thenReturn(new WorkflowPlan(List.of("查询订单风险", "校验数据"), false));
        when(analysis.analyze(eq("查找延期订单"), anyString(),
                org.mockito.ArgumentMatchers.<List<OrderRiskAssessment>>any(),
                org.mockito.ArgumentMatchers.<List<InventoryPosition>>any(),
                org.mockito.ArgumentMatchers.<List<SupplierRiskProfile>>any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any(WorkflowPlan.class)))
            .thenThrow(new IllegalStateException("simulated report generation failure"));
        WorkflowFixture fixture = fixture(analysis);
        SupplyChainWorkflowService workflowService = createWorkflowService(Runnable::run, fixture);

        var failed = workflowService.submit("查找延期订单");

        assertEquals("FAILED", failed.status());
        assertEquals("FAILED", workflowService.task(failed.taskId()).status());
        assertNotNull(workflowService.task(failed.taskId()).error());
        List<SupplyChainTaskEvent> events = workflowService.events(failed.taskId());
        SupplyChainTaskEvent nodeFailure = events.stream()
            .filter(event -> SupplyChainEventType.NODE_FAILED.equals(event.type())
                    && "GENERATING_REPORT".equals(event.stage()))
            .findFirst()
            .orElseThrow();
        assertEquals("IllegalStateException", nodeFailure.errorType());
        assertNotNull(nodeFailure.durationMs());
        assertTrue(events.stream().anyMatch(event -> SupplyChainEventType.TASK_FAILED.equals(event.type())));
        assertFalse(events.stream().anyMatch(event -> SupplyChainEventType.TASK_COMPLETED.equals(event.type())));
        assertTrue(events.stream().anyMatch(event -> SupplyChainEventType.TOOL_COMPLETED.equals(event.type())));
        assertThrows(IllegalStateException.class, () -> workflowService.report(failed.taskId()));
    }

    @Test
    void toolFailureRecordsFailedToolAndNodeWithoutCompletedTask() throws Exception {
        WorkflowFixture fixture = fixture(null);
        SupplyChainQueryService failingQuery = mock(SupplyChainQueryService.class);
        when(failingQuery.findAtRiskOrders()).thenThrow(new IllegalStateException("simulated tool failure"));
        SupplyChainWorkflowService workflowService = createWorkflowService(Runnable::run, fixture, failingQuery);

        var failed = workflowService.submit("查找延期订单");

        assertEquals("FAILED", failed.status());
        List<SupplyChainTaskEvent> events = workflowService.events(failed.taskId());
        SupplyChainTaskEvent toolFailure = events.stream()
            .filter(event -> SupplyChainEventType.TOOL_FAILED.equals(event.type()))
            .findFirst()
            .orElseThrow();
        assertEquals("findAtRiskOrders", toolFailure.stage());
        assertEquals("IllegalStateException", toolFailure.errorType());
        assertNotNull(toolFailure.durationMs());
        assertTrue(events.stream().anyMatch(event -> SupplyChainEventType.NODE_FAILED.equals(event.type())
                && "QUERYING_ORDERS".equals(event.stage())));
        assertTrue(events.stream().anyMatch(event -> SupplyChainEventType.TASK_FAILED.equals(event.type())));
        assertFalse(events.stream().anyMatch(event -> SupplyChainEventType.TASK_COMPLETED.equals(event.type())));
        assertFalse(events.stream().anyMatch(event -> SupplyChainEventType.NODE_COMPLETED.equals(event.type())
                && "QUERYING_ORDERS".equals(event.stage())));
    }

    @Test
    void cancellingAQueuedTaskPreventsWorkflowExecution() throws Exception {
        WorkflowFixture fixture = fixture(null);
        List<Runnable> pendingJobs = new ArrayList<>();
        SupplyChainWorkflowService workflowService = createWorkflowService(pendingJobs::add, fixture);

        SupplyChainTaskStatus accepted = workflowService.submit("查找延期订单");
        SupplyChainTaskStatus cancelled = workflowService.cancel(accepted.taskId());
        assertEquals("CANCELLED", cancelled.status());
        assertEquals("CANCELLED", workflowService.cancel(accepted.taskId()).status());

        pendingJobs.forEach(Runnable::run);

        assertEquals("CANCELLED", workflowService.task(accepted.taskId()).status());
        assertTrue(fixture.taskRepository().findReport(accepted.taskId()).isEmpty());
        List<SupplyChainTaskEvent> events = workflowService.events(accepted.taskId());
        assertEquals(1, events.stream().filter(event -> SupplyChainEventType.TASK_CANCELLED.equals(event.type())).count());
        assertFalse(events.stream().anyMatch(event -> SupplyChainEventType.TASK_STARTED.equals(event.type())));
        assertFalse(events.stream().anyMatch(event -> SupplyChainEventType.TOOL_STARTED.equals(event.type())));
    }

    @Test
    void cancellingARunningTaskStopsAtTheNextCheckpoint() throws Exception {
        WorkflowFixture fixture = fixture(null);
        CountDownLatch planStarted = new CountDownLatch(1);
        CountDownLatch releasePlan = new CountDownLatch(1);
        when(fixture.analysisService().createPlan(anyString())).thenAnswer(invocation -> {
            planStarted.countDown();
            assertTrue(releasePlan.await(10, TimeUnit.SECONDS));
            return new WorkflowPlan(List.of("查询订单风险", "校验数据"), false);
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            SupplyChainWorkflowService workflowService = createWorkflowService(executor, fixture);
            SupplyChainTaskStatus accepted = workflowService.submit("查找延期订单");
            assertTrue(planStarted.await(10, TimeUnit.SECONDS));

            SupplyChainTaskStatus cancelled = workflowService.cancel(accepted.taskId());
            assertEquals("CANCELLED", cancelled.status());
            releasePlan.countDown();

            long deadline = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < deadline
                    && !workflowService.task(accepted.taskId()).status().equals("CANCELLED")) {
                Thread.sleep(50);
            }
            assertEquals("CANCELLED", workflowService.task(accepted.taskId()).status());
            assertTrue(fixture.taskRepository().findReport(accepted.taskId()).isEmpty());
            List<SupplyChainTaskEvent> events = workflowService.events(accepted.taskId());
            assertTrue(events.stream().anyMatch(event -> SupplyChainEventType.TASK_CANCELLED.equals(event.type())));
            assertFalse(events.stream().anyMatch(event -> SupplyChainEventType.TASK_COMPLETED.equals(event.type())));
            assertTrue(events.stream().noneMatch(event -> SupplyChainEventType.TOOL_STARTED.equals(event.type())));
        }
        finally {
            releasePlan.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void persistentCancellationFromAnotherInstanceStopsExecutionWithoutInProcessSignal() throws Exception {
        WorkflowFixture fixture = fixture(null);
        CountDownLatch planStarted = new CountDownLatch(1);
        CountDownLatch releasePlan = new CountDownLatch(1);
        when(fixture.analysisService().createPlan(anyString())).thenAnswer(invocation -> {
            planStarted.countDown();
            assertTrue(releasePlan.await(10, TimeUnit.SECONDS));
            return new WorkflowPlan(List.of("查询订单风险", "校验数据"), false);
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            SupplyChainWorkflowService workflowService = createWorkflowService(executor, fixture);
            SupplyChainTaskStatus accepted = workflowService.submit("查找延期订单");
            assertTrue(planStarted.await(10, TimeUnit.SECONDS));

            // Simulate the cancellation request landing on another application instance: only the
            // shared database changes; this process never receives TaskCancellationRegistry.request.
            assertTrue(fixture.taskRepository().cancelIfActive(accepted.taskId()));
            releasePlan.countDown();

            long deadline = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < deadline
                    && !workflowService.task(accepted.taskId()).status().equals("CANCELLED")) {
                Thread.sleep(50);
            }
            assertEquals("CANCELLED", workflowService.task(accepted.taskId()).status());
            assertTrue(fixture.taskRepository().findReport(accepted.taskId()).isEmpty());
            assertTrue(workflowService.events(accepted.taskId()).stream()
                .noneMatch(event -> SupplyChainEventType.TASK_COMPLETED.equals(event.type())));
        }
        finally {
            releasePlan.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void cancellationRollsBackWhenTheCancellationEventCannotBePersisted() throws Exception {
        WorkflowFixture fixture = fixture(null);
        List<Runnable> pendingJobs = new ArrayList<>();
        SupplyChainWorkflowService workflowService = createWorkflowService(pendingJobs::add, fixture);
        SupplyChainTaskStatus task = workflowService.submit("查找延期订单");
        assertEquals("ACCEPTED", task.status());
        fixture.jdbc().execute("DROP TABLE chainpilot_task_event");

        assertThrows(RuntimeException.class, () -> workflowService.cancel(task.taskId()));

        assertEquals("ACCEPTED", workflowService.task(task.taskId()).status());
    }

    @Test
    void cancellingACompletedTaskKeepsCompletedOutcome() throws Exception {
        WorkflowFixture fixture = fixture(null);
        SupplyChainWorkflowService workflowService = createWorkflowService(Runnable::run, fixture);
        RiskAnalysisReport report = workflowService.analyze("查找延期订单");

        SupplyChainTaskStatus result = workflowService.cancel(report.taskId());

        assertEquals("COMPLETED", result.status());
        assertEquals("COMPLETED", workflowService.task(report.taskId()).status());
        assertEquals(report, workflowService.report(report.taskId()));
        assertFalse(workflowService.events(report.taskId()).stream()
            .anyMatch(event -> SupplyChainEventType.TASK_CANCELLED.equals(event.type())));
    }

    private WorkflowFixture fixture(SupplyChainAnalysisService analysisService) {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:chainpilot-workflow-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        SupplyChainTaskRepository taskRepository = new SupplyChainTaskRepository(jdbc, objectMapper);
        SupplyChainEventRepository eventRepository = new SupplyChainEventRepository(jdbc);
        SupplyChainEventRecorder eventRecorder = new SupplyChainEventRecorder(eventRepository);
        SupplyChainAnalysisService analysis = analysisService == null ? defaultAnalysisService() : analysisService;
        return new WorkflowFixture(objectMapper, taskRepository, eventRepository, eventRecorder, analysis, jdbc);
    }

    private SupplyChainAnalysisService defaultAnalysisService() {
        SupplyChainAnalysisService analysisService = mock(SupplyChainAnalysisService.class);
        when(analysisService.createPlan(anyString()))
            .thenReturn(new WorkflowPlan(List.of("查询订单风险", "校验数据"), false));
        when(analysisService.analyze(eq("查找延期订单"), anyString(),
                org.mockito.ArgumentMatchers.<List<OrderRiskAssessment>>any(),
                org.mockito.ArgumentMatchers.<List<InventoryPosition>>any(),
                org.mockito.ArgumentMatchers.<List<SupplierRiskProfile>>any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any(WorkflowPlan.class)))
            .thenAnswer(invocation -> new RiskAnalysisReport(invocation.getArgument(1), "COMPLETED", "已生成报告",
                    invocation.<WorkflowPlan>getArgument(6).steps(), false, invocation.getArgument(2),
                    List.of("检查风险订单"), List.of(), List.of(), false,
                    invocation.getArgument(5), Instant.now()));
        return analysisService;
    }

    private SupplyChainWorkflowService createWorkflowService(Executor executor, WorkflowFixture fixture)
            throws Exception {
        return createWorkflowService(executor, fixture,
                new SupplyChainQueryService(new SupplyChainDemoData()));
    }

    private SupplyChainWorkflowService createWorkflowService(Executor executor, WorkflowFixture fixture,
            SupplyChainQueryService queryService) throws Exception {
        SupplyChainAgentTools tools = new SupplyChainAgentTools(queryService,
                mock(DisposalRecommendationService.class), mock(DisposalSimulationService.class),
                mock(DisposalExecutionService.class), mock(DisposalScenarioComparisonService.class),
                mock(DisposalPlanService.class), fixture.eventRecorder());
        SupplyChainWorkflowService service = new SupplyChainWorkflowService(tools, fixture.analysisService(),
                mock(SupplyChainDecisionService.class), fixture.objectMapper(), fixture.taskRepository(),
                fixture.eventRepository(), fixture.eventRecorder(), executor);
        service.setTransactionManager(new DataSourceTransactionManager(fixture.jdbc().getDataSource()));
        return service;
    }

    private record WorkflowFixture(ObjectMapper objectMapper, SupplyChainTaskRepository taskRepository,
            SupplyChainEventRepository eventRepository, SupplyChainEventRecorder eventRecorder,
            SupplyChainAnalysisService analysisService, JdbcTemplate jdbc) {
    }
}
