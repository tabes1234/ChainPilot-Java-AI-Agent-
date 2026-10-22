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

import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainEventType;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskEvent;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskState;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.SupplyChainEventRepository;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.SupplyChainTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskRecoveryCoordinatorTest {

    private static final long LEASE_TTL_MS = 600_000L;

    private final JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
            "jdbc:h2:mem:chainpilot-recovery-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));

    private final SupplyChainTaskRepository tasks = new SupplyChainTaskRepository(jdbc,
            new ObjectMapper().findAndRegisterModules());

    private final SupplyChainEventRepository events = new SupplyChainEventRepository(jdbc);

    private final TaskRecoveryCoordinator coordinator = new TaskRecoveryCoordinator(tasks, events,
            new DataSourceTransactionManager(jdbc.getDataSource()));

    @Test
    void anExpiredLeaseFailsOnceWithOneEventAndIsNeverReexecuted() {
        seed("expired-task", "jvm-dead", tasks.databaseNowMillis() - 1);
        seed("live-task", "jvm-live", tasks.databaseNowMillis() + LEASE_TTL_MS);
        seedWithoutOwner("legacy-task");

        coordinator.recoverExpiredTasks();

        SupplyChainTaskStatus expired = tasks.findTask("expired-task").orElseThrow();
        assertEquals(SupplyChainTaskState.FAILED, expired.status());
        assertEquals("LEASE_EXPIRED", expired.currentStep());
        assertTrue(expired.error().contains("not re-executed"));
        List<SupplyChainTaskEvent> expiredEvents = events.findByTaskId("expired-task");
        assertEquals(1, expiredEvents.size());
        assertEquals(SupplyChainEventType.TASK_FAILED, expiredEvents.get(0).type());
        assertEquals("LEASE", expiredEvents.get(0).stage());
        assertTrue(expiredEvents.get(0).summary().contains("not re-executed"));

        assertEquals(SupplyChainTaskState.RUNNING, tasks.findTask("live-task").orElseThrow().status());
        assertEquals(SupplyChainTaskState.RUNNING, tasks.findTask("legacy-task").orElseThrow().status());
        assertTrue(events.findByTaskId("live-task").isEmpty());
        assertTrue(events.findByTaskId("legacy-task").isEmpty());

        coordinator.recoverExpiredTasks();
        assertEquals(1, events.findByTaskId("expired-task").size());
    }

    @Test
    void aStartingInstanceLeavesAnotherInstancesLiveTaskRunning() {
        seed("other-instance-task", "jvm-other", tasks.databaseNowMillis() + LEASE_TTL_MS);

        coordinator.recoverOnStartup();

        SupplyChainTaskStatus untouched = tasks.findTask("other-instance-task").orElseThrow();
        assertEquals(SupplyChainTaskState.RUNNING, untouched.status());
        assertEquals("QUERYING_ORDERS", untouched.currentStep());
        assertTrue(events.findByTaskId("other-instance-task").isEmpty());
        assertEquals(0, tasks.countActiveWithoutOwner());
    }

    private void seed(String taskId, String owner, long leaseExpiresAt) {
        Instant now = Instant.now();
        tasks.create(new SupplyChainTaskStatus(taskId, SupplyChainTaskState.ACCEPTED, "QUEUED", 0, null, now),
                taskId);
        assertTrue(tasks.claimTask(taskId, owner, leaseExpiresAt));
        tasks.update(new SupplyChainTaskStatus(taskId, SupplyChainTaskState.RUNNING, "QUERYING_ORDERS", 0, null,
                now));
    }

    private void seedWithoutOwner(String taskId) {
        Instant now = Instant.now();
        tasks.create(new SupplyChainTaskStatus(taskId, SupplyChainTaskState.ACCEPTED, "QUEUED", 0, null, now),
                taskId);
        tasks.update(new SupplyChainTaskStatus(taskId, SupplyChainTaskState.RUNNING, "QUERYING_ORDERS", 0, null,
                now));
    }
}
