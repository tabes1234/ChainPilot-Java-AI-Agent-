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

import com.alibaba.cloud.ai.dataagent.supplychain.repository.SupplyChainEventRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class SupplyChainEventRecorderTest {

    @Test
    void persistenceFailureIsLoggedAndNeverChangesWorkflowOutcome() {
        SupplyChainEventRepository repository = mock(SupplyChainEventRepository.class);
        doThrow(new IllegalStateException("event database unavailable")).when(repository)
            .append(anyString(), anyString(), anyString(), anyString(), any(), any(), any(), any());
        SupplyChainEventRecorder recorder = new SupplyChainEventRecorder(repository);

        assertDoesNotThrow(() -> {
            recorder.taskQueued("task-1");
            recorder.taskStarted("task-1");
            recorder.nodeStarted("task-1", "PLANNING");
            recorder.toolStarted("task-1", "findAtRiskOrders");
            recorder.modelFailed("task-1", "PLANNING", System.nanoTime(),
                    new IllegalStateException("model unavailable"));
            recorder.fallbackUsed("task-1", "PLANNING", "MODEL_UNAVAILABLE");
        });
        verify(repository, times(6)).append(anyString(), anyString(), anyString(), anyString(), any(), any(),
                any(), any());
    }

    @Test
    void taskContextIsScopedAndRestored() {
        SupplyChainEventRecorder recorder = new SupplyChainEventRecorder(mock(SupplyChainEventRepository.class));

        assertNull(recorder.currentTaskId());
        try (SupplyChainEventRecorder.TaskScope ignored = recorder.openTask("task-1")) {
            assertEquals("task-1", recorder.currentTaskId());
        }
        assertNull(recorder.currentTaskId());
    }
}
