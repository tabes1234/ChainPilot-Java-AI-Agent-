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

import com.alibaba.cloud.ai.dataagent.service.aimodelconfig.AiModelRegistry;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalActionType;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalPlan;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalPlanStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalRecommendation;
import com.alibaba.cloud.ai.dataagent.supplychain.model.InventoryPosition;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxDraftType;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxExecution;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxExecutionEligibility;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxExecutionMode;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxExecutionPage;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxExecutionStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxExecutionStep;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxExecutionSummary;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxStepStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxTaskDraft;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplierRiskProfile;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainOrder;
import com.alibaba.cloud.ai.dataagent.supplychain.service.SupplyChainQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * HTTP-level coverage for sandbox rehearsals: selected-only execution, both action drafts,
 * idempotent replay, concurrency, failure retention, pagination and real-data immutability.
 */
@SpringBootTest(classes = { ChainPilotTestApplication.class,
        SupplyChainSandboxExecutionHttpIntegrationTest.DisabledModelConfiguration.class },
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:chainpilot_sandbox_execution_test;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;MODE=MySQL",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.sql.init.mode=never",
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.reactive.ReactiveSecurityAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.security.reactive.ReactiveUserDetailsServiceAutoConfiguration",
                "logging.level.root=warn",
                "logging.level.com.alibaba.cloud.ai.dataagent.supplychain=info" })
class SupplyChainSandboxExecutionHttpIntegrationTest {

    private static final String SKU = "SKU-MCU-001";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private SupplyChainQueryService queryService;

    @BeforeEach
    void clearPersistedState() {
        jdbcTemplate.update("DELETE FROM chainpilot_disposal_execution");
        jdbcTemplate.update("DELETE FROM chainpilot_disposal_plan");
        jdbcTemplate.update("DELETE FROM chainpilot_disposal_simulation");
    }

    @Test
    void selectedPlanRunsPersistedReplenishmentRehearsalWithIdempotentReplay() {
        DisposalPlan plan = select(createReplenishmentPlan(), DisposalActionType.REPLENISH_INVENTORY);
        List<InventoryPosition> inventoryBefore = queryService.findInventory(null);
        List<SupplyChainOrder> ordersBefore = queryService.findAllOrders();
        List<SupplierRiskProfile> suppliersBefore = queryService.findSuppliers();

        SandboxExecutionEligibility eligibility = eligibility(plan.planId());
        assertTrue(eligibility.eligible());
        assertEquals(plan.simulation().simulationId(), eligibility.sourceSimulationId());
        assertEquals(DisposalActionType.REPLENISH_INVENTORY, eligibility.actionType());

        SandboxExecution execution = start(plan.planId(), "rehearsal-replenishment-1", 201);

        assertEquals(SandboxExecutionMode.SANDBOX, execution.mode());
        assertEquals(SandboxExecutionStatus.SUCCEEDED, execution.status());
        assertEquals(plan.planId(), execution.disposalPlanId());
        assertEquals(plan.selectedRecommendationId(), execution.recommendationId());
        assertEquals(plan.simulation().simulationId(), execution.sourceSimulationId());
        assertEquals("rehearsal-replenishment-1", execution.idempotencyKey());
        assertFalse(execution.realDataMutated());
        assertNotNull(execution.startedAt());
        assertNotNull(execution.completedAt());
        assertFalse(execution.evidence().isEmpty());
        assertTrue(execution.finalResult().contains("未写入真实库存"));
        assertFalse(execution.unresolvedItems().isEmpty());
        assertNull(execution.failureReason());

        assertEquals(5, execution.steps().size());
        assertEquals(List.of("PLAN_AND_RECOMMENDATION_VALIDATION", "SIMULATION_AND_EVIDENCE_VALIDATION",
                "ACTION_PARAMETER_VALIDATION", "SANDBOX_TASK_DRAFT_GENERATION", "AUDIT_AND_RESULT_RECORDING"),
                execution.steps().stream().map(SandboxExecutionStep::step).toList());
        assertTrue(execution.steps()
            .stream()
            .allMatch(step -> step.status() == SandboxStepStatus.SUCCEEDED));
        assertTrue(execution.steps()
            .stream()
            .allMatch(step -> step.inputSummary() != null && step.validationResult() != null
                    && step.resultSummary() != null && step.failureReason() == null));

        SandboxTaskDraft draft = execution.taskDraft();
        assertNotNull(draft);
        assertEquals(SandboxDraftType.REPLENISHMENT_TASK_DRAFT, draft.draftType());
        assertEquals(SKU, draft.sku());
        assertEquals(100, draft.replenishmentQuantity());
        assertEquals(20, draft.currentAvailableToPromise());
        assertEquals(120, draft.targetAvailableToPromise());
        assertEquals(55, draft.currentOnHandQuantity());
        assertEquals(155, draft.targetOnHandQuantity());
        assertEquals(List.of("ORD-1001"), draft.linkedOrderIds());
        assertFalse(draft.recommendedActions().isEmpty());
        assertFalse(draft.pendingConfirmations().isEmpty());
        assertEquals(plan.simulation().simulationId(), draft.sourceSimulationId());
        assertTrue(execution.finalResult().contains(draft.taskDraftId()));

        SandboxExecution reloaded = execution(execution.executionId());
        assertEquals(execution, reloaded);

        List<SandboxExecutionStep> steps = webTestClient.get()
            .uri("/api/supply-chain/sandbox-executions/{executionId}/steps", execution.executionId())
            .exchange()
            .expectStatus()
            .isOk()
            .expectBodyList(SandboxExecutionStep.class)
            .returnResult()
            .getResponseBody();
        assertEquals(execution.steps(), steps);

        SandboxExecutionPage page = executions(plan.planId());
        assertEquals(1, page.totalElements());
        assertEquals(1, page.items().size());
        SandboxExecutionSummary summary = page.items().get(0);
        assertEquals(execution.executionId(), summary.executionId());
        assertEquals(SandboxExecutionStatus.SUCCEEDED, summary.status());
        assertEquals(draft.taskDraftId(), summary.taskDraftId());
        assertEquals(5, summary.stepCount());
        assertEquals(0, summary.failedStepCount());

        SandboxExecution replay = start(plan.planId(), "rehearsal-replenishment-1", 200);
        assertEquals(execution.executionId(), replay.executionId());
        assertEquals(1, executions(plan.planId()).totalElements());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chainpilot_disposal_execution", Integer.class));

        webTestClient.post()
            .uri("/api/supply-chain/disposal-plans/{planId}/executions", plan.planId())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("idempotencyKey", "rehearsal-replenishment-2"))
            .exchange()
            .expectStatus()
            .isEqualTo(409);

        assertEquals(inventoryBefore, queryService.findInventory(null));
        assertEquals(ordersBefore, queryService.findAllOrders());
        assertEquals(suppliersBefore, queryService.findSuppliers());
        assertEquals(55, queryService.findInventory(SKU).get(0).onHandQuantity());
        assertEquals(20, queryService.findInventory(SKU).get(0).availableToPromise());
    }

    @Test
    void supplierFollowUpRehearsalProducesSupplierDraftAndLinksRiskOrders() {
        DisposalPlan plan = createPlan("供应商跟进演练", SKU, "SUP-ALPHA",
                Map.of("scenario", "SUPPLIER_DELAY", "supplierId", "SUP-ALPHA", "additionalDelayDays", 10));
        plan = select(plan, DisposalActionType.FOLLOW_UP_SUPPLIER);
        List<SupplyChainOrder> ordersBefore = queryService.findAllOrders();

        SandboxExecution execution = start(plan.planId(), "rehearsal-supplier-1", 201);

        assertEquals(SandboxExecutionStatus.SUCCEEDED, execution.status());
        assertEquals(plan.simulation().simulationId(), execution.sourceSimulationId());
        SandboxTaskDraft draft = execution.taskDraft();
        assertNotNull(draft);
        assertEquals(SandboxDraftType.SUPPLIER_FOLLOW_UP_DRAFT, draft.draftType());
        assertEquals("SUP-ALPHA", draft.supplierId());
        assertEquals(List.of("ORD-1001"), draft.linkedOrderIds());
        assertEquals(3, draft.recommendedActions().size());
        assertTrue(draft.recommendedActions().get(0).contains("模拟延期 10 天"));
        assertEquals(3, draft.pendingConfirmations().size());
        assertEquals(plan.simulation().simulationId(), draft.sourceSimulationId());
        assertTrue(draft.evidenceSummary().contains("履约率 0.72"));
        assertTrue(execution.finalResult().contains("未联系真实供应商"));
        assertTrue(execution.finalResult().contains("未修改真实交付日期"));
        assertEquals(5, execution.steps().size());
        assertTrue(execution.steps()
            .stream()
            .allMatch(step -> step.status() == SandboxStepStatus.SUCCEEDED));
        assertEquals(ordersBefore, queryService.findAllOrders());
    }

    @Test
    void onlySelectedPlansAreEligibleAndRejectedPlansCannotExecute() {
        DisposalPlan pending = createReplenishmentPlan();
        assertEquals(DisposalPlanStatus.PENDING_CONFIRMATION, pending.status());

        SandboxExecutionEligibility pendingEligibility = eligibility(pending.planId());
        assertFalse(pendingEligibility.eligible());
        assertTrue(pendingEligibility.reason().contains("SELECTED"));
        startExpectingConflict(pending.planId(), "pending-key");
        assertEquals(0, executions(pending.planId()).totalElements());

        webTestClient.patch()
            .uri("/api/supply-chain/disposal-plans/{planId}/status", pending.planId())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("status", "REJECTED"))
            .exchange()
            .expectStatus()
            .isOk();

        SandboxExecutionEligibility rejectedEligibility = eligibility(pending.planId());
        assertFalse(rejectedEligibility.eligible());
        assertTrue(rejectedEligibility.reason().contains("SELECTED"));
        startExpectingConflict(pending.planId(), "rejected-key");
        assertEquals(0, executions(pending.planId()).totalElements());
    }

    @Test
    void missingSimulationAndInvalidRequestsFailWithoutExecutionRecords() {
        DisposalPlan plan = select(createPlan("无模拟补货方案", SKU, null, null),
                DisposalActionType.REPLENISH_INVENTORY);

        SandboxExecutionEligibility eligibility = eligibility(plan.planId());
        assertFalse(eligibility.eligible());
        assertTrue(eligibility.reason().contains("requires an attached simulation"));
        startExpectingConflict(plan.planId(), "missing-simulation-key");
        assertEquals(0, executions(plan.planId()).totalElements());

        webTestClient.post()
            .uri("/api/supply-chain/disposal-plans/{planId}/executions", plan.planId())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("idempotencyKey", " "))
            .exchange()
            .expectStatus()
            .isBadRequest();
        webTestClient.get()
            .uri("/api/supply-chain/disposal-plans/{planId}/executions?pageSize=51", plan.planId())
            .exchange()
            .expectStatus()
            .isBadRequest();
        webTestClient.get()
            .uri("/api/supply-chain/disposal-plans/missing/executions")
            .exchange()
            .expectStatus()
            .isNotFound();
        webTestClient.get()
            .uri("/api/supply-chain/sandbox-executions/missing")
            .exchange()
            .expectStatus()
            .isNotFound();
        webTestClient.get()
            .uri("/api/supply-chain/sandbox-executions/missing/steps")
            .exchange()
            .expectStatus()
            .isNotFound();
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chainpilot_disposal_execution", Integer.class));
    }

    @Test
    void midExecutionFailureKeepsCompletedStepsDraftAndAllowsNewKeyRetry() {
        DisposalPlan plan = select(createReplenishmentPlan(), DisposalActionType.REPLENISH_INVENTORY);
        List<SupplierRiskProfile> originalSuppliers = queryService.findSuppliers();
        List<SupplierRiskProfile> changedSuppliers = new ArrayList<>(originalSuppliers);
        SupplierRiskProfile first = changedSuppliers.get(0);
        changedSuppliers.set(0, new SupplierRiskProfile(first.supplierId(), first.name(), first.riskLevel(),
                first.onTimeDeliveryRate(), first.defectRate(), "changed during rehearsal"));
        doReturn(originalSuppliers).doReturn(changedSuppliers).when(queryService).findSuppliers();

        SandboxExecution failed = start(plan.planId(), "rehearsal-failure-1", 201);
        assertEquals(SandboxExecutionStatus.FAILED, failed.status());
        assertEquals(5, failed.steps().size());
        assertTrue(failed.steps()
            .subList(0, 4)
            .stream()
            .allMatch(step -> step.status() == SandboxStepStatus.SUCCEEDED));
        SandboxExecutionStep failedStep = failed.steps().get(4);
        assertEquals(SandboxStepStatus.FAILED, failedStep.status());
        assertTrue(failedStep.failureReason().contains("changed during the sandbox rehearsal"));
        assertNotNull(failed.taskDraft());
        assertTrue(failed.finalResult().contains("未完成"));
        assertFalse(failed.unresolvedItems().isEmpty());
        assertFalse(failed.realDataMutated());
        assertEquals(failed, execution(failed.executionId()));

        doCallRealMethod().when(queryService).findSuppliers();
        SandboxExecution retried = start(plan.planId(), "rehearsal-failure-2", 201);
        assertEquals(SandboxExecutionStatus.SUCCEEDED, retried.status());
        assertEquals(2, executions(plan.planId()).totalElements());
        start(plan.planId(), "rehearsal-failure-2", 200);
    }

    @Test
    void concurrentStartsForTheSamePlanCreateAtMostOneExecution() throws Exception {
        DisposalPlan plan = select(createReplenishmentPlan(), DisposalActionType.REPLENISH_INVENTORY);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (String key : List.of("parallel-key-a", "parallel-key-b")) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await(5, TimeUnit.SECONDS);
                    return webTestClient.post()
                        .uri("/api/supply-chain/disposal-plans/{planId}/executions", plan.planId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("idempotencyKey", key))
                        .exchange()
                        .returnResult(SandboxExecution.class)
                        .getStatus()
                        .value();
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(20, TimeUnit.SECONDS));
            }
            assertEquals(1, statuses.stream().filter(status -> status == 201).count(),
                    "exactly one concurrent request may start the rehearsal: " + statuses);
            assertEquals(1, statuses.stream().filter(status -> status == 409).count(),
                    "the competing request must be rejected: " + statuses);
        }
        finally {
            pool.shutdownNow();
        }
        SandboxExecutionPage page = executions(plan.planId());
        assertEquals(1, page.totalElements());
        assertEquals(SandboxExecutionStatus.SUCCEEDED, page.items().get(0).status());
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
        WebTestClient.ResponseSpec response = webTestClient.post()
            .uri("/api/supply-chain/disposal-plans/{planId}/executions", planId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("idempotencyKey", key))
            .exchange();
        SandboxExecution execution;
        if (expectedStatus == 200) {
            execution = response.expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("X-Idempotent-Replay", "true")
                .expectBody(SandboxExecution.class)
                .returnResult()
                .getResponseBody();
        }
        else {
            execution = response.expectStatus()
                .isEqualTo(expectedStatus)
                .expectBody(SandboxExecution.class)
                .returnResult()
                .getResponseBody();
        }
        assertNotNull(execution);
        return execution;
    }

    private void startExpectingConflict(String planId, String key) {
        webTestClient.post()
            .uri("/api/supply-chain/disposal-plans/{planId}/executions", planId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("idempotencyKey", key))
            .exchange()
            .expectStatus()
            .isEqualTo(409);
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

    @TestConfiguration(proxyBeanMethods = false)
    static class DisabledModelConfiguration {

        @Bean
        AiModelRegistry aiModelRegistry() {
            AiModelRegistry registry = mock(AiModelRegistry.class);
            when(registry.getChatClient())
                .thenThrow(new IllegalStateException("External model calls are disabled in sandbox tests"));
            return registry;
        }
    }
}
