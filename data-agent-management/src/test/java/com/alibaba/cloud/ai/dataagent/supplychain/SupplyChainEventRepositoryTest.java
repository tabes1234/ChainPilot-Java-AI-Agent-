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

import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainEventStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainEventType;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskEvent;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.SupplyChainEventRepository;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.SupplyChainTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupplyChainEventRepositoryTest {

    @Test
    void eventsAreOrderedPerTaskAndDifferentTasksStayIsolated() {
        JdbcTemplate jdbc = jdbc();
        SupplyChainTaskRepository tasks = new SupplyChainTaskRepository(jdbc, mapper());
        SupplyChainEventRepository events = new SupplyChainEventRepository(jdbc);
        seedTasks(tasks);

        Instant sameInstant = Instant.parse("2026-01-01T00:00:00Z");
        events.append("task-a", SupplyChainEventType.TASK_QUEUED, "QUEUE", SupplyChainEventStatus.QUEUED,
                sameInstant, null, "task accepted", null);
        events.append("task-a", SupplyChainEventType.NODE_STARTED, "PLANNING", SupplyChainEventStatus.STARTED,
                sameInstant, null, "node execution started", null);
        events.append("task-a", SupplyChainEventType.NODE_COMPLETED, "PLANNING", SupplyChainEventStatus.COMPLETED,
                sameInstant, 7L, "steps=2", null);
        events.append("task-b", SupplyChainEventType.TASK_QUEUED, "QUEUE", SupplyChainEventStatus.QUEUED,
                sameInstant, null, "task accepted", null);

        List<SupplyChainTaskEvent> firstTaskEvents = events.findByTaskId("task-a");
        List<SupplyChainTaskEvent> secondTaskEvents = events.findByTaskId("task-b");

        assertEquals(List.of(1, 2, 3), firstTaskEvents.stream().map(SupplyChainTaskEvent::sequence).toList());
        assertEquals(List.of(SupplyChainEventType.TASK_QUEUED, SupplyChainEventType.NODE_STARTED,
                SupplyChainEventType.NODE_COMPLETED), firstTaskEvents.stream().map(SupplyChainTaskEvent::type).toList());
        assertTrue(firstTaskEvents.stream().allMatch(event -> "task-a".equals(event.taskId())));
        assertEquals(1, secondTaskEvents.size());
        assertEquals(1, secondTaskEvents.get(0).sequence());
        assertEquals("task-b", secondTaskEvents.get(0).taskId());
        assertFalse(firstTaskEvents.get(0).eventId().equals(firstTaskEvents.get(1).eventId()));
        assertEquals(7L, firstTaskEvents.get(2).durationMs());
        assertNull(firstTaskEvents.get(0).durationMs());
        assertTrue(events.findByTaskId("task-with-no-events").isEmpty());
    }

    @Test
    void eventsSurviveRepositoryRecreationAndLongSummariesAreTruncated() {
        JdbcTemplate jdbc = jdbc();
        SupplyChainTaskRepository tasks = new SupplyChainTaskRepository(jdbc, mapper());
        SupplyChainEventRepository events = new SupplyChainEventRepository(jdbc);
        seedTasks(tasks);
        events.append("task-persist", SupplyChainEventType.TASK_QUEUED, "QUEUE", SupplyChainEventStatus.QUEUED,
                Instant.now(), null, "x".repeat(800), "y".repeat(300));

        SupplyChainEventRepository afterRecreation = new SupplyChainEventRepository(jdbc);
        List<SupplyChainTaskEvent> restored = afterRecreation.findByTaskId("task-persist");

        assertEquals(1, restored.size());
        assertEquals(500, restored.get(0).summary().length());
        assertEquals(128, restored.get(0).errorType().length());
    }

    private static void seedTasks(SupplyChainTaskRepository repository) {
        repository.create(new SupplyChainTaskStatus("task-a", "ACCEPTED", "QUEUED", 0, null, Instant.now()),
                "task a");
        repository.create(new SupplyChainTaskStatus("task-b", "ACCEPTED", "QUEUED", 0, null, Instant.now()),
                "task b");
        repository.create(new SupplyChainTaskStatus("task-persist", "ACCEPTED", "QUEUED", 0, null, Instant.now()),
                "task persist");
    }

    private static JdbcTemplate jdbc() {
        return new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:chainpilot-events-" + UUID.randomUUID()
                        + ";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;MODE=MySQL",
                "sa", ""));
    }

    private static ObjectMapper mapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
