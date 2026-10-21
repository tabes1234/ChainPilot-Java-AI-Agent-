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

import com.alibaba.cloud.ai.dataagent.service.aimodelconfig.AiModelRegistry;
import com.alibaba.cloud.ai.dataagent.supplychain.model.ObservabilityRunDetail;
import com.alibaba.cloud.ai.dataagent.supplychain.model.ObservabilityRunPage;
import com.alibaba.cloud.ai.dataagent.supplychain.model.ObservabilitySummary;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskAnalysisReport;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainEventType;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskEvent;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskSummary;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.SupplyChainTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Full HTTP-level chain: asynchronous submission, Graph execution, database persistence,
 * filtered/paged history and report retrieval. The model registry is replaced at its boundary so
 * the deterministic planner and rule-based summary are exercised without any external LLM call.
 */
@SpringBootTest(classes = { ChainPilotTestApplication.class,
        SupplyChainAnalysisHttpIntegrationTest.DeterministicModelConfiguration.class },
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:chainpilot_http_test;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;MODE=MySQL",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.sql.init.mode=never",
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.reactive.ReactiveSecurityAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.security.reactive.ReactiveUserDetailsServiceAutoConfiguration",
                "logging.level.root=warn",
                "logging.level.com.alibaba.cloud.ai.dataagent.supplychain=info" })
class SupplyChainAnalysisHttpIntegrationTest {

    private static final Duration TERMINAL_TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AiModelRegistry aiModelRegistry;

    @Autowired
    private SupplyChainTaskRepository taskRepository;

    @BeforeEach
    void clearHistory() {
        jdbcTemplate.update("DELETE FROM chainpilot_task_event");
        jdbcTemplate.update("DELETE FROM chainpilot_analysis_task");
        clearInvocations(aiModelRegistry);
    }

    @Test
    void asyncAnalysisIsPersistedAndReadableThroughHistoryAndReport() {
        String taskId = submit("分析今天有哪些订单可能延期");

        SupplyChainTaskStatus completed = awaitTerminal(taskId);
        assertEquals("COMPLETED", completed.status());
        assertEquals("DONE", completed.currentStep());

        HistoryPage history = history("?status=completed&page=1&pageSize=10");

        assertEquals(1, history.totalElements());
        assertEquals(1, history.items().size());
        SupplyChainTaskSummary summary = history.items().get(0);
        assertEquals(taskId, summary.taskId());
        assertEquals("分析今天有哪些订单可能延期", summary.question());
        assertEquals("COMPLETED", summary.status());
        assertEquals("DONE", summary.currentStep());
        assertEquals(completed.updatedAt(), summary.updatedAt());
        assertTrue(summary.reportAvailable());

        RiskAnalysisReport report = report(taskId);
        assertEquals(taskId, report.taskId());
        assertEquals("COMPLETED", report.status());
        assertFalse(report.plannerAssisted());
        assertFalse(report.modelAssisted());
        assertFalse(report.atRiskOrders().isEmpty());
        assertFalse(report.summary().isBlank());
        assertFalse(report.executionPlan().isEmpty());
        assertEquals(List.of(), report.toolCalls(),
                "Graph nodes call tools directly, so a fallback report must not claim model tool calls");

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
        assertTrue(events.stream().anyMatch(event -> SupplyChainEventType.NODE_STARTED.equals(event.type())));
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
    }

    @Test
    void eventsAreIsolatedPerTaskAndUnknownTaskReturnsNotFound() {
        String first = submit("分析延期订单");
        String second = submit("检查供应商风险");
        awaitTerminal(first);
        awaitTerminal(second);

        List<SupplyChainTaskEvent> firstEvents = events(first);
        List<SupplyChainTaskEvent> secondEvents = events(second);
        assertFalse(firstEvents.isEmpty());
        assertFalse(secondEvents.isEmpty());
        assertTrue(firstEvents.stream().allMatch(event -> first.equals(event.taskId())));
        assertTrue(secondEvents.stream().allMatch(event -> second.equals(event.taskId())));
        Set<String> firstEventIds = firstEvents.stream().map(SupplyChainTaskEvent::eventId)
            .collect(java.util.stream.Collectors.toSet());
        assertTrue(secondEvents.stream().noneMatch(event -> firstEventIds.contains(event.eventId())));

        webTestClient.get()
            .uri("/api/supply-chain/analysis/tasks/{taskId}/events", "missing-task")
            .exchange()
            .expectStatus()
            .isNotFound();

        HistoryPage history = history("?page=1&pageSize=10");
        assertEquals(2, history.totalElements());
        assertEquals(first, report(first).taskId());
        assertEquals(second, report(second).taskId());
    }

    @Test
    void existingTaskWithoutEventsReturnsAnEmptyTrail() {
        taskRepository.create(new SupplyChainTaskStatus("manual-task", "ACCEPTED", "QUEUED", 0, null,
                java.time.Instant.now()), "manual task without execution events");

        List<SupplyChainTaskEvent> events = events("manual-task");

        assertTrue(events.isEmpty());
        webTestClient.get()
            .uri("/api/supply-chain/analysis/tasks/{taskId}/events", "missing-task")
            .exchange()
            .expectStatus()
            .isNotFound();
    }

    @Test
    void historyFiltersAndPaginatesEveryBoundaryInTheDatabase() {
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
        assertEquals(1, secondPage.items().size());

        Set<String> pagedIds = new HashSet<>();
        firstPage.items().forEach(item -> pagedIds.add(item.taskId()));
        secondPage.items().forEach(item -> pagedIds.add(item.taskId()));
        assertEquals(Set.of(first, second, third), pagedIds);

        SupplyChainTaskSummary lastOfFirstPage = firstPage.items().get(firstPage.items().size() - 1);
        assertFalse(secondPage.items().get(0).updatedAt().isAfter(lastOfFirstPage.updatedAt()));

        HistoryPage beyondLastPage = history("?page=3&pageSize=2");
        assertTrue(beyondLastPage.items().isEmpty());
        assertEquals(3, beyondLastPage.totalElements());
        assertEquals(2, beyondLastPage.totalPages());

        HistoryPage completedOnly = history("?status=COMPLETED&page=1&pageSize=2");
        assertEquals(3, completedOnly.totalElements());
        assertTrue(completedOnly.items().stream().allMatch(item -> "COMPLETED".equals(item.status())));

        HistoryPage failedOnly = history("?status=FAILED&page=1&pageSize=2");
        assertTrue(failedOnly.items().isEmpty());
        assertEquals(0, failedOnly.totalElements());
        assertEquals(0, failedOnly.totalPages());

        HistoryPage legacyLimit = history("?limit=2");
        assertEquals(2, legacyLimit.items().size());
        assertEquals(2, legacyLimit.pageSize());
        assertEquals(3, legacyLimit.totalElements());
        assertEquals(2, legacyLimit.totalPages());

        HistoryPage legacyLimitSecondPage = history("?limit=2&page=2");
        assertEquals(1, legacyLimitSecondPage.items().size());
        assertEquals(2, legacyLimitSecondPage.pageSize());

        HistoryPage maxPageSize = history("?pageSize=50");
        assertEquals(3, maxPageSize.items().size());
        assertEquals(50, maxPageSize.pageSize());
    }

    @Test
    void historyRejectsInvalidPagingAndStatusParameters() {
        List<String> invalidQueries = List.of("?page=0", "?page=-1", "?pageSize=0", "?pageSize=51", "?limit=0",
                "?limit=51", "?status=UNKNOWN");

        for (String query : invalidQueries) {
            webTestClient.get()
                .uri("/api/supply-chain/analysis/tasks" + query)
                .exchange()
                .expectStatus()
                .isBadRequest();
        }
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


    @Test
    void observabilitySummaryRunsAndDetailUseTheSamePersistedEvents() {
        String taskId = submit("分析今天有哪些订单可能延期");
        awaitTerminal(taskId);

        ObservabilitySummary summary = webTestClient.get()
            .uri("/api/supply-chain/observability/summary")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(ObservabilitySummary.class)
            .returnResult()
            .getResponseBody();
        assertNotNull(summary);
        assertEquals(1, summary.totalTasks());
        assertEquals(1, summary.completedTasks());
        assertTrue(summary.fallbackEvents() >= 2);
        assertTrue(summary.modelFailures() >= 2);
        assertEquals(1, summary.deterministicFallbackTasks());
        assertEquals(1, summary.tasksWithDuration());
        assertTrue(summary.averageDurationMs() != null);

        ObservabilityRunPage runs = webTestClient.get()
            .uri("/api/supply-chain/observability/runs?page=1&pageSize=5&narrativeSource=DETERMINISTIC_FALLBACK")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(ObservabilityRunPage.class)
            .returnResult()
            .getResponseBody();
        assertNotNull(runs);
        assertEquals(1, runs.totalElements());
        assertEquals(taskId, runs.items().get(0).taskId());
        assertTrue(runs.items().get(0).fallbackUsed());
        assertEquals(3, runs.items().get(0).toolCallCount());

        ObservabilityRunDetail detail = webTestClient.get()
            .uri("/api/supply-chain/observability/runs/{taskId}", taskId)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(ObservabilityRunDetail.class)
            .returnResult()
            .getResponseBody();
        assertNotNull(detail);
        assertEquals(taskId, detail.run().taskId());
        assertEquals("DETERMINISTIC_FALLBACK", detail.narrative().source());
        assertEquals("JAVA_DETERMINISTIC_RULES", detail.narrative().riskAuthority());
        assertFalse(detail.narrative().modelCanChangeRisk());
        assertTrue(detail.timeline().stream()
            .anyMatch(item -> SupplyChainEventType.TOOL_COMPLETED.equals(item.type())));
        assertEquals(2, detail.modelCalls().size());
        assertEquals(2, detail.fallbacks().size());
        assertTrue(detail.diagnostics().stream()
            .anyMatch(item -> "DETERMINISTIC_FALLBACK".equals(item.code())));
        assertTrue(detail.links().reportAvailable());
        assertEquals(3, detail.report().atRiskOrders());
        assertFalse(detail.toString().contains("External model calls are disabled"));

        webTestClient.get()
            .uri("/api/supply-chain/observability/runs?pageSize=100")
            .exchange()
            .expectStatus()
            .isBadRequest();
        webTestClient.get()
            .uri("/api/supply-chain/observability/runs?narrativeSource=INVENTED")
            .exchange()
            .expectStatus()
            .isBadRequest();
        webTestClient.get()
            .uri("/api/supply-chain/observability/runs/missing-task")
            .exchange()
            .expectStatus()
            .isNotFound();
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

    private record HistoryPage(List<SupplyChainTaskSummary> items, int totalElements, int page, int pageSize,
            int totalPages) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DeterministicModelConfiguration {

        @Bean
        AiModelRegistry aiModelRegistry() {
            AiModelRegistry registry = mock(AiModelRegistry.class);
            when(registry.getChatClient()).thenThrow(
                    new IllegalStateException("External model calls are disabled in HTTP integration tests"));
            return registry;
        }
    }
}
