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
package com.alibaba.cloud.ai.dataagent.supplychain.controller;

import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainEventStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainEventType;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskEvent;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskPage;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskSummary;
import com.alibaba.cloud.ai.dataagent.supplychain.service.SupplyChainWorkflowService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SupplyChainAnalysisControllerTest {

    private final SupplyChainWorkflowService workflowService = mock(SupplyChainWorkflowService.class);

    private final SupplyChainAnalysisController controller = new SupplyChainAnalysisController(workflowService);

    @Test
    void tasksReturnsRecentHistoryForRequestedLimit() {
        SupplyChainTaskSummary summary = new SupplyChainTaskSummary("task-1", "分析延期订单", "COMPLETED", "DONE",
                0, null, Instant.now(), true);
        when(workflowService.tasks("ANALYSIS", null, 1, 5))
                .thenReturn(new SupplyChainTaskPage(List.of(summary), 1, 5, 1, 1));

        ResponseEntity<List<SupplyChainTaskSummary>> response = controller.tasks(5, null, null, null, null);

        assertEquals(List.of(summary), response.getBody());
        assertEquals("1", response.getHeaders().getFirst("X-Total-Count"));
        assertEquals("1", response.getHeaders().getFirst("X-Page"));
        assertEquals("5", response.getHeaders().getFirst("X-Page-Size"));
        assertEquals("1", response.getHeaders().getFirst("X-Total-Pages"));
        verify(workflowService).tasks("ANALYSIS", null, 1, 5);
    }

    @Test
    void pageSizeTakesPrecedenceOverLimitAndStatusIsNormalized() {
        when(workflowService.tasks("ANALYSIS", "COMPLETED", 2, 20))
                .thenReturn(new SupplyChainTaskPage(List.of(), 2, 20, 25, 2));

        ResponseEntity<List<SupplyChainTaskSummary>> response = controller.tasks(5, 2, 20, " completed ", null);

        assertEquals(List.of(), response.getBody());
        assertEquals("25", response.getHeaders().getFirst("X-Total-Count"));
        assertEquals("2", response.getHeaders().getFirst("X-Page"));
        assertEquals("20", response.getHeaders().getFirst("X-Page-Size"));
        assertEquals("2", response.getHeaders().getFirst("X-Total-Pages"));
        verify(workflowService).tasks("ANALYSIS", "COMPLETED", 2, 20);
    }

    @Test
    void tasksRejectsLimitsOutsideSupportedRange() {
        ResponseStatusException tooSmall = assertThrows(ResponseStatusException.class,
                () -> controller.tasks(0, null, null, null, null));
        ResponseStatusException tooLarge = assertThrows(ResponseStatusException.class,
                () -> controller.tasks(51, null, null, null, null));

        assertEquals(HttpStatus.BAD_REQUEST, tooSmall.getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, tooLarge.getStatusCode());
    }

    @Test
    void tasksRejectsInvalidPageSizePageStatusAndMode() {
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> controller.tasks(null, null, 0, null, null)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> controller.tasks(null, null, 51, null, null)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> controller.tasks(null, 0, null, null, null)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> controller.tasks(null, null, null, "UNKNOWN", null)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> controller.tasks(null, null, null, null, "UNKNOWN")).getStatusCode());
    }

    @Test
    void blankStatusMeansNoFilterAndModeIsNormalized() {
        when(workflowService.tasks("ANALYSIS", null, 1, 10))
                .thenReturn(new SupplyChainTaskPage(List.of(), 1, 10, 0, 0));
        when(workflowService.tasks("DECISION", null, 1, 10))
                .thenReturn(new SupplyChainTaskPage(List.of(), 1, 10, 0, 0));

        controller.tasks(null, null, null, " ", null);
        controller.tasks(null, null, null, null, " decision ");

        verify(workflowService).tasks("ANALYSIS", null, 1, 10);
        verify(workflowService).tasks("DECISION", null, 1, 10);
    }

    @Test
    void decisionEndpointMapsUnknownAndIncompleteTasks() {
        when(workflowService.decision("missing")).thenThrow(new IllegalArgumentException("Unknown task"));
        when(workflowService.decision("running"))
                .thenThrow(new IllegalStateException("ChainPilot decision task has not completed"));

        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> controller.decision("missing")).getStatusCode());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> controller.decision("running")).getStatusCode());
    }

    @Test
    void eventsReturnsTaskTrailAndUnknownTaskIsNotFound() {
        SupplyChainTaskEvent event = new SupplyChainTaskEvent("event-1", "task-1", 1,
                SupplyChainEventType.TASK_QUEUED, "QUEUE", SupplyChainEventStatus.QUEUED, Instant.now(), null,
                "task accepted", null);
        when(workflowService.events("task-1")).thenReturn(List.of(event));
        when(workflowService.events("missing-task"))
                .thenThrow(new IllegalArgumentException("Unknown ChainPilot task: missing-task"));

        assertEquals(List.of(event), controller.events("task-1"));
        ResponseStatusException notFound = assertThrows(ResponseStatusException.class,
                () -> controller.events("missing-task"));
        assertEquals(HttpStatus.NOT_FOUND, notFound.getStatusCode());
    }
}
