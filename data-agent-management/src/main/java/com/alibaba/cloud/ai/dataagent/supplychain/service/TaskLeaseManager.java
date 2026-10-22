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

import com.alibaba.cloud.ai.dataagent.supplychain.repository.SupplyChainTaskRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns the database leases for tasks executing in this JVM. The database remains the
 * source of truth for fencing; this registry only drives heartbeat renewal and local
 * stop signals.
 */
@Slf4j
@Component
public class TaskLeaseManager {

    private final SupplyChainTaskRepository taskRepository;

    private final InstanceIdentity instanceIdentity;

    private final long leaseTtlMillis;

    private final Map<String, TaskLease> active = new ConcurrentHashMap<>();

    private final Set<String> lost = ConcurrentHashMap.newKeySet();

    public TaskLeaseManager(SupplyChainTaskRepository taskRepository, InstanceIdentity instanceIdentity,
            @Value("${chainpilot.task-lease.ttl-ms:900000}") long leaseTtlMillis) {
        this.taskRepository = taskRepository;
        this.instanceIdentity = instanceIdentity;
        this.leaseTtlMillis = leaseTtlMillis;
    }

    public TaskLease claim(String taskId) {
        long now = taskRepository.databaseNowMillis();
        String owner = instanceIdentity.id();
        if (!taskRepository.claimTask(taskId, owner, now + leaseTtlMillis)) {
            return null;
        }
        long fence = taskRepository.findFenceVersion(taskId, owner)
            .orElseThrow(() -> new IllegalStateException("Claimed task has no fence version: " + taskId));
        TaskLease lease = new TaskLease(taskId, owner, fence, now + leaseTtlMillis);
        active.put(taskId, lease);
        lost.remove(taskId);
        log.info("ChainPilot task lease claimed. taskId={} owner={} fence={}", taskId, owner, fence);
        return lease;
    }

    public TaskLease current(String taskId) {
        return active.get(taskId);
    }

    public boolean isLost(String taskId) {
        return lost.contains(taskId);
    }

    public void markLost(String taskId, String reason) {
        if (taskId == null) {
            return;
        }
        if (lost.add(taskId)) {
            log.warn("ChainPilot task lease lost; stopping local worker. taskId={} reason={}", taskId, reason);
            TaskCancellationRegistry.request(taskId);
        }
    }

    public void release(String taskId) {
        active.remove(taskId);
        lost.remove(taskId);
    }

    @Scheduled(initialDelayString = "${chainpilot.task-lease.initial-delay-ms:30000}",
            fixedDelayString = "${chainpilot.task-lease.renew-interval-ms:30000}")
    public void renewOwnedLeases() {
        List<TaskLease> leases = List.copyOf(active.values());
        if (leases.isEmpty()) {
            return;
        }
        long now;
        try {
            now = taskRepository.databaseNowMillis();
        }
        catch (RuntimeException exception) {
            leases.forEach(lease -> markLost(lease.taskId(), "database time lookup failed"));
            return;
        }
        for (TaskLease lease : leases) {
            try {
                if (!taskRepository.renewTaskLease(lease.taskId(), lease.ownerId(), lease.fenceVersion(),
                        now + leaseTtlMillis)) {
                    markLost(lease.taskId(), "renewal rejected by owner/fence/state");
                }
            }
            catch (RuntimeException exception) {
                markLost(lease.taskId(), "renewal database failure: " + exception.getClass().getSimpleName());
            }
        }
    }
}
