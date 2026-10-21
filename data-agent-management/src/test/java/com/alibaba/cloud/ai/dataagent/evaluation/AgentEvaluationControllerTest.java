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
package com.alibaba.cloud.ai.dataagent.evaluation;

import com.alibaba.cloud.ai.dataagent.controller.GlobalExceptionHandler;
import com.alibaba.cloud.ai.dataagent.evaluation.controller.AgentEvaluationController;
import com.alibaba.cloud.ai.dataagent.evaluation.exception.AgentPrerequisiteException;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationCaseDefinition;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationCaseHistoryEntry;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationCaseStatus;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationMetrics;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationMetricsReport;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationMode;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationRun;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationRunComparison;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationRunStatus;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationRunSummary;
import com.alibaba.cloud.ai.dataagent.evaluation.service.AgentEvaluationService;
import com.alibaba.cloud.ai.dataagent.evaluation.service.EvaluationComparisonService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentEvaluationControllerTest {

    private AgentEvaluationService service;

    private EvaluationComparisonService comparisonService;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        service = mock(AgentEvaluationService.class);
        comparisonService = mock(EvaluationComparisonService.class);
        client = WebTestClient.bindToController(new AgentEvaluationController(service, comparisonService))
            .controllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void listsCasesWithApiEnvelope() {
        when(service.cases()).thenReturn(List.of(new EvaluationCaseDefinition("CASE-1", "案例", "场景", "risk",
                "ORDER_RISK", EvaluationMode.OFFLINE, "q", "expected", "ORD-1", "1.0.0", true, "basis",
                List.of("assertion"))));

        client.get()
            .uri("/api/agent-evaluation/cases")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .jsonPath("$.success")
            .isEqualTo(true)
            .jsonPath("$.data[0].id")
            .isEqualTo("CASE-1")
            .jsonPath("$.data[0].mode")
            .isEqualTo("OFFLINE");
    }

    @Test
    void startsRequestedOfflineRun() {
        EvaluationRun run = new EvaluationRun("run-1", "1.0.0", EvaluationMode.OFFLINE, EvaluationRunStatus.COMPLETED,
                "TEST", Instant.now(), Instant.now(), 1, 1, 0, 0, 5L, Map.of(), List.of());
        when(service.start(eq(EvaluationMode.OFFLINE), anyList(), eq(false))).thenReturn(run);

        client.post()
            .uri("/api/agent-evaluation/runs")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"caseIds\":[\"CASE-1\"],\"mode\":\"OFFLINE\"}")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .jsonPath("$.success")
            .isEqualTo(true)
            .jsonPath("$.data.runId")
            .isEqualTo("run-1")
            .jsonPath("$.data.status")
            .isEqualTo("COMPLETED");
    }

    @Test
    void rejectsAgentRunWithoutExplicitConfirmation() {
        client.post()
            .uri("/api/agent-evaluation/runs")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"caseIds\":[\"WF-ANALYSIS-HIGH-RISK\"],\"mode\":\"AGENT\"}")
            .exchange()
            .expectStatus()
            .isBadRequest();

        verify(service, never()).start(eq(EvaluationMode.AGENT), anyList(), eq(false));
    }

    @Test
    void returnsStructuredPrerequisiteCodeWhenTheBackendGateRejectsAnAgentRun() {
        when(service.start(eq(EvaluationMode.AGENT), anyList(), eq(true)))
            .thenThrow(new AgentPrerequisiteException(AgentPrerequisiteException.CHAT_NOT_READY,
                    "AGENT 评测前置条件未满足：未配置激活的 CHAT 模型。"));

        client.post()
            .uri("/api/agent-evaluation/runs")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"caseIds\":[\"WF-ANALYSIS-HIGH-RISK\"],\"mode\":\"AGENT\",\"confirmAgentRun\":true}")
            .exchange()
            .expectStatus()
            .isEqualTo(412)
            .expectBody()
            .jsonPath("$.success")
            .isEqualTo(false)
            .jsonPath("$.code")
            .isEqualTo(AgentPrerequisiteException.CHAT_NOT_READY)
            .jsonPath("$.message")
            .isEqualTo("AGENT 评测前置条件未满足：未配置激活的 CHAT 模型。");
    }

    @Test
    void exposesPerCaseHistory() {
        when(service.caseHistory("CASE-1", 20)).thenReturn(List.of(new EvaluationCaseHistoryEntry("run-1",
                EvaluationMode.OFFLINE, EvaluationRunStatus.COMPLETED, Instant.now(), EvaluationCaseStatus.PASSED, 5L,
                null, null, null, "1.0.0", "1.0.0")));

        client.get()
            .uri("/api/agent-evaluation/cases/CASE-1/history?limit=20")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .jsonPath("$.data[0].runId")
            .isEqualTo("run-1")
            .jsonPath("$.data[0].caseVersion")
            .isEqualTo("1.0.0");
    }

    @Test
    void exposesModeSeparatedCapabilitiesAndMetrics() {
        when(service.capabilities()).thenReturn(Map.of("totalCases", 20, "offlineCases", 14, "agentCases", 6));
        when(service.metrics(20)).thenReturn(new EvaluationMetricsReport(0, 0,
                List.of(new EvaluationMetrics(0, 0, 0, 0, 0, null, null, 0, List.of(), List.of(), null))));

        client.get().uri("/api/agent-evaluation/capabilities").exchange().expectStatus().isOk().expectBody()
            .jsonPath("$.data.totalCases").isEqualTo(20);
        client.get().uri("/api/agent-evaluation/metrics?limit=20").exchange().expectStatus().isOk().expectBody()
            .jsonPath("$.data.byMode[0].caseSamples").isEqualTo(0);
    }

    @Test
    void exposesPersistedRunComparison() {
        EvaluationRunSummary base = new EvaluationRunSummary("base", EvaluationMode.OFFLINE, "1.0.0",
                EvaluationRunStatus.COMPLETED, Instant.now(), Instant.now(), 2, 1, 1, 0, 0.5, 100L, 10.0, null, 2);
        EvaluationRunSummary target = new EvaluationRunSummary("target", EvaluationMode.OFFLINE, "1.0.0",
                EvaluationRunStatus.COMPLETED, Instant.now(), Instant.now(), 2, 2, 0, 0, 1.0, 90L, 10.0, null, 2);
        when(comparisonService.compare("base", "target")).thenReturn(new EvaluationRunComparison(true, null,
                EvaluationMode.OFFLINE, "1.0.0", null, base, target, null, List.of(), List.of(), List.of("CASE-2"),
                List.of(), List.of(), List.of(), List.of()));

        client.get()
            .uri("/api/agent-evaluation/comparisons?baseRunId=base&targetRunId=target")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .jsonPath("$.data.comparable")
            .isEqualTo(true)
            .jsonPath("$.data.fixedFailures[0]")
            .isEqualTo("CASE-2");
    }

}
