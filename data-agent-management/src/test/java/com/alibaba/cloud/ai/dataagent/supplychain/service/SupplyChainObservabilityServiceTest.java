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
package com.alibaba.cloud.ai.dataagent.supplychain.service;

import com.alibaba.cloud.ai.dataagent.evaluation.repository.EvaluationRunRepository;
import com.alibaba.cloud.ai.dataagent.supplychain.model.ObservabilityRunDetail;
import com.alibaba.cloud.ai.dataagent.supplychain.model.ObservabilityRunPage;
import com.alibaba.cloud.ai.dataagent.supplychain.model.ObservabilitySummary;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskAnalysisReport;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskNarrative;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainEventStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainEventType;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.DecisionProposalRepository;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.DisposalPlanRepository;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.SupplyChainEventRepository;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.SupplyChainTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupplyChainObservabilityServiceTest {

    private SupplyChainTaskRepository taskRepository;

    private SupplyChainEventRepository eventRepository;

    private SupplyChainObservabilityService service;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:observability-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=MySQL", "sa", ""));
        taskRepository = new SupplyChainTaskRepository(jdbc, mapper);
        eventRepository = new SupplyChainEventRepository(jdbc);
        service = new SupplyChainObservabilityService(taskRepository, eventRepository,
                new DecisionProposalRepository(jdbc, mapper), new DisposalPlanRepository(jdbc, mapper),
                new EvaluationRunRepository(jdbc, mapper), mapper);
    }

    @Test
    void summaryAndDetailUsePersistedEventsAndRealNarrativeSource() {
        String taskId = createTask("COMPLETED", "DONE");
        RiskAnalysisReport report = new RiskAnalysisReport(taskId, "COMPLETED", "规则摘要", List.of(), false,
                List.of(), List.of(), List.of(), List.of(), true, 0, Instant.now(),
                new RiskNarrative("模型解释", List.of(), List.of(), List.of(), List.of()));
        taskRepository.saveReport(report);
        append(taskId, SupplyChainEventType.TASK_QUEUED, "QUEUE", SupplyChainEventStatus.QUEUED, null, "accepted", null);
        append(taskId, SupplyChainEventType.TASK_STARTED, "WORKFLOW", SupplyChainEventStatus.STARTED, null, "started", null);
        append(taskId, SupplyChainEventType.NODE_STARTED, "GENERATING_REPORT", SupplyChainEventStatus.STARTED, null,
                "node started", null);
        append(taskId, SupplyChainEventType.MODEL_FAILED, "GENERATING_REPORT", SupplyChainEventStatus.FAILED, 12L,
                "model invocation failed", "IllegalStateException");
        append(taskId, SupplyChainEventType.FALLBACK_USED, "GENERATING_REPORT", SupplyChainEventStatus.FALLBACK, null,
                "deterministic fallback selected; reason=IllegalStateException", null);
        append(taskId, SupplyChainEventType.TASK_COMPLETED, "WORKFLOW", SupplyChainEventStatus.COMPLETED, 238L,
                "report generated", null);

        ObservabilitySummary summary = service.summary();
        assertEquals(1, summary.totalTasks());
        assertEquals(1, summary.completedTasks());
        assertEquals(1, summary.fallbackEvents());
        assertEquals(1, summary.modelFailures());
        assertEquals(1, summary.modelVerifiedTasks());
        assertEquals(1, summary.degradedTasks());
        assertEquals(1, summary.tasksWithDuration());
        assertEquals(238.0, summary.averageDurationMs());

        ObservabilityRunDetail detail = service.detail(taskId);
        assertEquals("MODEL_VERIFIED", detail.narrative().source());
        assertEquals("JAVA_DETERMINISTIC_RULES", detail.narrative().riskAuthority());
        assertFalse(detail.narrative().modelCanChangeRisk());
        assertEquals(6, detail.timeline().size());
        assertTrue(detail.modelCalls().stream().anyMatch(call -> call.status().equals("FAILED")));
        assertEquals(1, detail.fallbacks().size());
        assertEquals("IllegalStateException", detail.fallbacks().get(0).reason());
        assertTrue(detail.diagnostics().stream().anyMatch(item -> item.code().equals("DETERMINISTIC_FALLBACK")));
        assertTrue(detail.diagnostics().stream().anyMatch(item -> item.code().equals("MODEL_INVOCATION_FAILED")));
        assertTrue(detail.diagnostics().stream().anyMatch(item -> item.code().equals("MODEL_VERIFIED")));
        assertEquals(6, detail.run().eventCount());
        assertTrue(detail.run().durationMs() == 238L);
    }

    @Test
    void failedTaskProducesFailedStepAndDoesNotInventModelOrToolEvents() {
        String taskId = createTask("FAILED", "VALIDATION_FAILED");
        append(taskId, SupplyChainEventType.TASK_STARTED, "WORKFLOW", SupplyChainEventStatus.STARTED, null, "started", null);
        append(taskId, SupplyChainEventType.NODE_FAILED, "VALIDATING", SupplyChainEventStatus.FAILED, 4L,
                "node execution failed", "IllegalStateException");
        append(taskId, SupplyChainEventType.TASK_FAILED, "VALIDATING", SupplyChainEventStatus.FAILED, 9L,
                "task execution failed", "IllegalStateException");

        ObservabilityRunDetail detail = service.detail(taskId);
        assertEquals("FAILED", detail.run().status());
        assertEquals("IllegalStateException", detail.run().failureType());
        assertTrue(detail.modelCalls().isEmpty());
        assertTrue(detail.tools().isEmpty());
        assertTrue(detail.diagnostics().stream().anyMatch(item -> item.code().equals("NODE_FAILED")));
        assertTrue(detail.diagnostics().stream().noneMatch(item -> item.code().equals("MODEL_VERIFIED")));
        assertFalse(detail.narrative().modelAssisted());
    }

    @Test
    void runsCanBeFilteredAndPagedBySourceAndFallback() {
        String verified = createTask("COMPLETED", "DONE");
        taskRepository.saveReport(new RiskAnalysisReport(verified, "COMPLETED", "规则摘要", List.of(), false,
                List.of(), List.of(), List.of(), List.of(), true, 0, Instant.now(),
                new RiskNarrative("模型解释", List.of(), List.of(), List.of(), List.of())));
        append(verified, SupplyChainEventType.TASK_COMPLETED, "WORKFLOW", SupplyChainEventStatus.COMPLETED, 10L,
                "report generated", null);

        String fallback = createTask("COMPLETED", "DONE");
        append(fallback, SupplyChainEventType.FALLBACK_USED, "GENERATING_REPORT", SupplyChainEventStatus.FALLBACK, null,
                "deterministic fallback selected; reason=IllegalStateException", null);
        append(fallback, SupplyChainEventType.TASK_COMPLETED, "WORKFLOW", SupplyChainEventStatus.COMPLETED, 20L,
                "report generated", null);

        ObservabilityRunPage verifiedPage = service.runs(1, 10, null, null, "MODEL_VERIFIED", null, null, null);
        assertEquals(1, verifiedPage.totalElements());
        assertEquals(verified, verifiedPage.items().get(0).taskId());
        ObservabilityRunPage fallbackPage = service.runs(1, 10, null, null, null, true, null, null);
        assertEquals(1, fallbackPage.totalElements());
        assertEquals(fallback, fallbackPage.items().get(0).taskId());
        assertTrue(fallbackPage.items().get(0).degraded());
        ObservabilityRunPage emptyPage = service.runs(2, 10, null, null, null, null, null, null);
        assertTrue(emptyPage.items().isEmpty());
        assertEquals(2, emptyPage.totalElements());
        ObservabilityRunPage upperQuery = service.runs(1, 10, null, null, null, null, null,
                verified.substring(0, 8).toUpperCase());
        assertEquals(1, upperQuery.totalElements());
        assertEquals(verified, upperQuery.items().get(0).taskId());
    }

    @Test
    void summaryDoesNotExposeReportBodyOrCredentials() {
        String taskId = createTask("COMPLETED", "DONE");
        taskRepository.saveReport(new RiskAnalysisReport(taskId, "COMPLETED",
                "contains secret sk-test-do-not-expose", List.of(), false, List.of(), List.of(), List.of(),
                List.of(), false, 0, Instant.now()));
        append(taskId, SupplyChainEventType.TASK_COMPLETED, "WORKFLOW", SupplyChainEventStatus.COMPLETED, 5L,
                "report generated", null);

        String serialized = String.valueOf(service.detail(taskId));
        assertFalse(serialized.contains("sk-test-do-not-expose"));
        assertFalse(serialized.toLowerCase().contains("authorization"));
    }

    private String createTask(String status, String step) {
        String taskId = UUID.randomUUID().toString();
        taskRepository.create(new SupplyChainTaskStatus(taskId, status, step, 0, null, Instant.now()),
                "可观测性测试任务 " + taskId, "ANALYSIS");
        return taskId;
    }

    private void append(String taskId, String type, String stage, String status, Long duration, String summary,
            String errorType) {
        eventRepository.append(taskId, type, stage, status, Instant.now(), duration, summary, errorType);
    }
}
