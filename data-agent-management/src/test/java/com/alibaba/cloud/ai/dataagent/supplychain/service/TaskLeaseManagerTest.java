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

import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskState;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.SupplyChainTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskLeaseManagerTest {

    private static final long LEASE_TTL_MS = 600_000L;

    private static final String TABLE = "chainpilot_analysis_task";

    private final JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
            "jdbc:h2:mem:chainpilot-lease-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));

    private final SupplyChainTaskRepository tasks = new SupplyChainTaskRepository(jdbc,
            new ObjectMapper().findAndRegisterModules());

    @Test
    void claimRecordsTheFenceAndRenewalKeepsTheLeaseExclusive() {
        seed("lease-task");
        TaskLeaseManager manager = new TaskLeaseManager(tasks, new InstanceIdentity("jvm-a"), LEASE_TTL_MS);
        assertNull(manager.current("lease-task"));

        TaskLease lease = manager.claim("lease-task");

        assertNotNull(lease);
        assertEquals("jvm-a", lease.ownerId());
        assertEquals("lease-task", lease.taskId());
        assertTrue(lease.fenceVersion() > 0);
        assertEquals(lease, manager.current("lease-task"));
        assertFalse(manager.isLost("lease-task"));
        assertEquals(lease.fenceVersion(), tasks.findFenceVersion("lease-task", "jvm-a").orElseThrow());

        long expiryBefore = leaseExpiry("lease-task");
        manager.renewOwnedLeases();
        assertTrue(leaseExpiry("lease-task") >= expiryBefore);
        assertFalse(manager.isLost("lease-task"));
        assertNull(new TaskLeaseManager(tasks, new InstanceIdentity("jvm-b"), LEASE_TTL_MS).claim("lease-task"));
        assertEquals("jvm-a", taskOwner("lease-task"));

        manager.release("lease-task");
        assertNull(manager.current("lease-task"));
    }

    @Test
    void renewalLossSignalsTheLocalWorkerToStop() {
        seed("lost-task");
        TaskLeaseManager manager = new TaskLeaseManager(tasks, new InstanceIdentity("jvm-a"), LEASE_TTL_MS);
        TaskCancellationRegistry.begin("lost-task");
        try {
            TaskLease lease = manager.claim("lost-task");
            assertNotNull(lease);
            // A takeover by another JVM bumps the fence; the stale worker must stop writing.
            jdbc.update("UPDATE " + TABLE + " SET owner_instance_id=?, fence_version=fence_version+1, "
                    + "lease_expires_at=? WHERE task_id=?", "jvm-b", tasks.databaseNowMillis() + LEASE_TTL_MS,
                    "lost-task");

            manager.renewOwnedLeases();

            assertTrue(manager.isLost("lost-task"));
            assertTrue(TaskCancellationRegistry.isRequested("lost-task"));
            assertEquals("jvm-b", taskOwner("lost-task"));
            assertFalse(tasks.renewTaskLease("lost-task", "jvm-a", lease.fenceVersion(),
                    tasks.databaseNowMillis() + LEASE_TTL_MS));
        }
        finally {
            TaskCancellationRegistry.end("lost-task");
        }
    }

    private void seed(String taskId) {
        tasks.create(new SupplyChainTaskStatus(taskId, SupplyChainTaskState.ACCEPTED, "QUEUED", 0, null,
                Instant.now()), taskId);
    }

    private long leaseExpiry(String taskId) {
        return jdbc.queryForObject("SELECT lease_expires_at FROM " + TABLE + " WHERE task_id=?",
                Long.class, taskId);
    }

    private String taskOwner(String taskId) {
        return jdbc.queryForObject("SELECT owner_instance_id FROM " + TABLE + " WHERE task_id=?",
                String.class, taskId);
    }
}
