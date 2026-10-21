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
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainEventStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainEventType;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.SupplyChainEventRepository;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelToolCallSessionTest {

    private static final String TASK_ID = "task-model-tools";

    @Test
    void exposesOnlyAnnotatedModelFacingTools() {
        SupplyChainEventRecorder recorder = new SupplyChainEventRecorder(mock(SupplyChainEventRepository.class));
        ModelToolCallSession session = ModelToolCallSession.open(
                tools(mock(SupplyChainQueryService.class), recorder), ObservationRegistry.NOOP);

        Set<String> names = Arrays.stream(session.callbacks())
            .map(callback -> callback.getToolDefinition().name())
            .collect(Collectors.toSet());

        assertEquals(Set.of("findAtRiskOrders", "findInventory", "findSuppliers", "findDisposalRecommendations",
                "simulateInventoryReplenishment", "simulateSupplierDelay", "compareDisposalScenarios",
                "compareSupplierDelayScenarios", "findScenarioComparison", "findDisposalPlan",
                "checkSandboxExecutionEligibility", "findSandboxExecution", "findSandboxExecutions"), names);
        assertFalse(names.contains("startSandboxExecution"),
                "sandbox execution must only start after explicit human confirmation");
        assertFalse(names.contains("findInventoryForWorkflow"));
        assertFalse(names.contains("findAtRiskOrdersForWorkflow"));
        assertFalse(names.contains("findSuppliersForWorkflow"));
        assertEquals(List.of(), session.calls());
    }

    @Test
    void recordsModelInvocationsInOrderAndExecutesTheRealToolImplementation() {
        SupplyChainQueryService queryService = mock(SupplyChainQueryService.class);
        when(queryService.findInventory("SKU-MCU-001"))
            .thenReturn(List.of(new InventoryPosition("SKU-MCU-001", "Industrial control module", 55, 15, 20)));
        when(queryService.findSuppliers()).thenReturn(List.of());
        SupplyChainEventRepository repository = mock(SupplyChainEventRepository.class);
        SupplyChainEventRecorder recorder = new SupplyChainEventRecorder(repository);
        SupplyChainAgentTools tools = tools(queryService, recorder);
        ModelToolCallSession session = ModelToolCallSession.open(tools, ObservationRegistry.NOOP);

        String inventoryJson;
        try (SupplyChainEventRecorder.TaskScope ignored = recorder.openTask(TASK_ID)) {
            inventoryJson = callback(session, "findInventory").call("{\"sku\":\"SKU-MCU-001\"}");
            callback(session, "findSuppliers").call("{}");
        }

        assertEquals(List.of("findInventory", "findSuppliers"), session.calls());
        assertTrue(inventoryJson.contains("\"sku\":\"SKU-MCU-001\""));
        assertTrue(inventoryJson.contains("\"availableToPromise\":20"));
        verify(queryService).findInventory("SKU-MCU-001");
        verify(queryService).findSuppliers();
        verify(repository, times(2)).append(eq(TASK_ID), eq(SupplyChainEventType.TOOL_COMPLETED), any(),
                eq(SupplyChainEventStatus.COMPLETED), any(), any(), startsWith("model-initiated tool;"), isNull());
    }

    @Test
    void workflowCallsShareTheToolImplementationButNeverEnterTheModelCallEvidence() {
        SupplyChainQueryService queryService = mock(SupplyChainQueryService.class);
        when(queryService.findInventory("SKU-MCU-001"))
            .thenReturn(List.of(new InventoryPosition("SKU-MCU-001", "Industrial control module", 55, 15, 20)));
        SupplyChainEventRepository repository = mock(SupplyChainEventRepository.class);
        SupplyChainEventRecorder recorder = new SupplyChainEventRecorder(repository);
        SupplyChainAgentTools tools = tools(queryService, recorder);
        ModelToolCallSession session = ModelToolCallSession.open(tools, ObservationRegistry.NOOP);

        try (SupplyChainEventRecorder.TaskScope ignored = recorder.openTask(TASK_ID)) {
            tools.findInventoryForWorkflow("SKU-MCU-001");
        }

        assertEquals(List.of(), session.calls());
        verify(queryService).findInventory("SKU-MCU-001");
        verify(repository).append(eq(TASK_ID), eq(SupplyChainEventType.TOOL_COMPLETED), eq("findInventory"),
                eq(SupplyChainEventStatus.COMPLETED), any(), any(), startsWith("workflow tool;"), isNull());
    }

    @Test
    void failedModelInvocationIsRecordedBeforeTheFailurePropagates() {
        SupplyChainQueryService queryService = mock(SupplyChainQueryService.class);
        when(queryService.findSuppliers()).thenThrow(new IllegalStateException("supplier store down"));
        SupplyChainEventRepository repository = mock(SupplyChainEventRepository.class);
        SupplyChainEventRecorder recorder = new SupplyChainEventRecorder(repository);
        ModelToolCallSession session = ModelToolCallSession.open(tools(queryService, recorder),
                ObservationRegistry.NOOP);

        try (SupplyChainEventRecorder.TaskScope ignored = recorder.openTask(TASK_ID)) {
            ToolCallback suppliers = callback(session, "findSuppliers");
            ToolExecutionException failure = assertThrows(ToolExecutionException.class,
                    () -> suppliers.call("{}"));
            assertEquals("supplier store down", failure.getCause().getMessage());
        }

        assertEquals(List.of("findSuppliers"), session.calls());
        verify(repository).append(eq(TASK_ID), eq(SupplyChainEventType.TOOL_FAILED), eq("findSuppliers"),
                eq(SupplyChainEventStatus.FAILED), any(), any(), eq("model-initiated tool execution failed"),
                eq("IllegalStateException"));
    }

    @Test
    void failedModelInvocationMarksTheActiveToolObservationAsFailed() {
        SupplyChainQueryService queryService = mock(SupplyChainQueryService.class);
        when(queryService.findSuppliers()).thenThrow(new IllegalStateException("supplier store down"));
        SupplyChainEventRecorder recorder = new SupplyChainEventRecorder(mock(SupplyChainEventRepository.class));
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }
        });
        ModelToolCallSession session = ModelToolCallSession.open(tools(queryService, recorder), registry);
        ToolCallback suppliers = callback(session, "findSuppliers");

        Observation observation = Observation.createNotStarted("spring.ai.tool",
                () -> ToolCallingObservationContext.builder()
                    .toolDefinition(suppliers.getToolDefinition())
                    .toolMetadata(suppliers.getToolMetadata())
                    .toolCallArguments("{}")
                    .build(),
                registry);
        observation.start();
        try (Observation.Scope observationScope = observation.openScope();
                SupplyChainEventRecorder.TaskScope ignored = recorder.openTask(TASK_ID)) {
            assertThrows(ToolExecutionException.class, () -> suppliers.call("{}"));
        }
        finally {
            observation.stop();
        }

        assertSame(ToolExecutionException.class, observation.getContext().getError().getClass());
    }

    private static SupplyChainAgentTools tools(SupplyChainQueryService queryService,
            SupplyChainEventRecorder recorder) {
        return new SupplyChainAgentTools(queryService, mock(DisposalRecommendationService.class),
                mock(DisposalSimulationService.class), mock(DisposalExecutionService.class),
                mock(DisposalScenarioComparisonService.class), mock(DisposalPlanService.class), recorder);
    }

    private static ToolCallback callback(ModelToolCallSession session, String name) {
        return Arrays.stream(session.callbacks())
            .filter(callback -> name.equals(callback.getToolDefinition().name()))
            .findFirst()
            .orElseThrow();
    }
}
