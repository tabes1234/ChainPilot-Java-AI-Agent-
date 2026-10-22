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

import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainEventStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainEventType;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.SupplyChainEventRepository;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.SupplyChainTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

/**
 * Recovers only tasks whose database lease has expired. It never uses application startup
 * as evidence that a task is abandoned, and it never re-executes a Graph.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskRecoveryCoordinator {

    private static final String LEASE_STAGE = "LEASE";

    private final SupplyChainTaskRepository taskRepository;

    private final SupplyChainEventRepository eventRepository;

    private final PlatformTransactionManager transactionManager;

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        recoverExpiredTasks();
    }

    @Scheduled(initialDelayString = "${chainpilot.task-recovery.initial-delay-ms:45000}",
            fixedDelayString = "${chainpilot.task-recovery.interval-ms:30000}")
    public void recoverExpiredTasks() {
        long now = taskRepository.databaseNowMillis();
        List<String> expired = taskRepository.findExpiredActiveTaskIds(now);
        long ownerless = taskRepository.countActiveWithoutOwner();
        if (ownerless > 0) {
            log.warn("ChainPilot has {} active task(s) without an owner/lease; leaving them untouched "
                    + "for explicit legacy recovery", ownerless);
        }
        if (expired.isEmpty()) {
            return;
        }
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        int recovered = 0;
        for (String taskId : expired) {
            try {
                Boolean changed = transaction.execute(status -> {
                    long recoveryTime = taskRepository.databaseNowMillis();
                    if (!taskRepository.failExpiredTask(taskId, recoveryTime)) {
                        return false;
                    }
                    eventRepository.append(taskId, SupplyChainEventType.TASK_FAILED, LEASE_STAGE,
                            SupplyChainEventStatus.FAILED, Instant.now(), null,
                            "task lease expired without a live owner; not re-executed", null);
                    return true;
                });
                if (Boolean.TRUE.equals(changed)) {
                    recovered++;
                }
            }
            catch (RuntimeException exception) {
                log.error("ChainPilot lease recovery failed; task remains untouched. taskId={} causeType={}",
                        taskId, exception.getClass().getSimpleName());
            }
        }
        if (recovered > 0) {
            log.warn("ChainPilot recovered {} task(s) with expired leases as FAILED", recovered);
        }
    }
}
