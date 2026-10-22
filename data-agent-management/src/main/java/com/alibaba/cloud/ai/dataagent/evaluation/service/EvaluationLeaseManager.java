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
package com.alibaba.cloud.ai.dataagent.evaluation.service;

import com.alibaba.cloud.ai.dataagent.supplychain.service.InstanceIdentity;
import com.alibaba.cloud.ai.dataagent.evaluation.repository.EvaluationRunRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * A database-level, fenced lease for the single active AGENT evaluation run. The lock row is the
 * only authority over who may advance a run; a second JVM is refused while a live lease exists and
 * a former owner is fenced out after a takeover or expiry. Startup is never treated as evidence
 * that a run was abandoned.
 */
@Slf4j
@Component
public class EvaluationLeaseManager {

    private static final String INTERRUPTED_REASON = "评测运行租约过期且没有活跃执行者；未自动重新执行";

    private final EvaluationRunRepository repository;

    private final InstanceIdentity instanceIdentity;

    private final long leaseTtlMillis;

    private final long maxHoldMillis;

    private volatile String activeRunId;

    private volatile long activeFence;

    private volatile long heldSinceMillis;

    private volatile boolean lost;

    public EvaluationLeaseManager(EvaluationRunRepository repository, InstanceIdentity instanceIdentity,
            @Value("${chainpilot.evaluation-lease.ttl-ms:600000}") long leaseTtlMillis,
            @Value("${chainpilot.evaluation-lease.max-hold-ms:1800000}") long maxHoldMillis) {
        this.repository = repository;
        this.instanceIdentity = instanceIdentity;
        this.leaseTtlMillis = leaseTtlMillis;
        this.maxHoldMillis = maxHoldMillis;
    }

    /** Takes the database lock for {@code runId}; false means another instance owns a live lease. */
    public boolean claim(String runId) {
        if (runId == null) {
            return false;
        }
        if (activeRunId != null && !runId.equals(activeRunId)) {
            if (!lost) {
                return false;
            }
            // A lost lease must not block new work; recovery closes the abandoned run.
            release();
        }
        long now = repository.databaseNowMillis();
        Optional<Long> fence = repository.claimEvaluationLock(instanceIdentity.id(), now + leaseTtlMillis, runId);
        if (fence.isEmpty()) {
            log.info("ChainPilot evaluation lease is held elsewhere; run not started. runId={}", runId);
            return false;
        }
        activeRunId = runId;
        activeFence = fence.get();
        heldSinceMillis = now;
        lost = false;
        log.info("ChainPilot evaluation lease claimed. runId={} owner={} fence={}", runId, instanceIdentity.id(),
                activeFence);
        return true;
    }

    public String ownerId() {
        return instanceIdentity.id();
    }

    public long activeFence() {
        return activeFence;
    }

    public String activeRunId() {
        return activeRunId;
    }

    public boolean isLost() {
        return lost;
    }

    /** True only while this instance holds the fence for the given run. */
    public boolean ownsRun(String runId) {
        return !lost && runId != null && runId.equals(activeRunId);
    }

    /** Extends the lease; a rejected or failed renewal permanently marks this instance as lost. */
    public boolean renew() {
        String runId = activeRunId;
        if (runId == null || lost) {
            return false;
        }
        try {
            long now = repository.databaseNowMillis();
            if (!repository.renewEvaluationLock(instanceIdentity.id(), activeFence, now + leaseTtlMillis)) {
                markLost("renewal rejected by owner/fence");
                return false;
            }
            return true;
        }
        catch (RuntimeException exception) {
            markLost("renewal database failure: " + exception.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * Stops claiming ownership without touching the database lock. The lease is left to expire so
     * that recovery can close the run exactly once, which also prevents a live-but-never-polled
     * holder from locking out every later run.
     */
    public void markLost(String reason) {
        if (!lost && activeRunId != null) {
            log.warn("ChainPilot evaluation lease lost. runId={} reason={}", activeRunId, reason);
        }
        lost = true;
    }

    public void release() {
        String runId = activeRunId;
        if (runId != null && !lost) {
            repository.releaseEvaluationLock(instanceIdentity.id(), activeFence);
            log.info("ChainPilot evaluation lease released. runId={}", runId);
        }
        activeRunId = null;
        activeFence = 0;
        heldSinceMillis = 0;
        lost = false;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        recoverExpiredRun();
    }

    @Scheduled(initialDelayString = "${chainpilot.evaluation-lease.recovery-initial-delay-ms:45000}",
            fixedDelayString = "${chainpilot.evaluation-lease.recovery-interval-ms:30000}")
    public void recoverExpiredRun() {
        if (activeRunId != null && !lost) {
            return;
        }
        try {
            Optional<String> expiredRun = repository.expireRunAndMarkInterrupted(repository.databaseNowMillis(),
                    INTERRUPTED_REASON);
            if (expiredRun.isPresent()) {
                log.warn("ChainPilot took over an expired evaluation lease and closed the run. runId={}",
                        expiredRun.get());
            }
        }
        catch (RuntimeException exception) {
            log.error("ChainPilot evaluation lease recovery failed: {}", exception.getClass().getSimpleName());
        }
    }

    @Scheduled(initialDelayString = "${chainpilot.evaluation-lease.renew-initial-delay-ms:30000}",
            fixedDelayString = "${chainpilot.evaluation-lease.renew-interval-ms:30000}")
    public void renewActiveLease() {
        if (activeRunId == null || lost) {
            return;
        }
        try {
            if (repository.databaseNowMillis() - heldSinceMillis > maxHoldMillis) {
                markLost("held beyond the maximum ownership window; letting the lease expire for recovery");
                return;
            }
        }
        catch (RuntimeException exception) {
            markLost("database time lookup failed");
            return;
        }
        renew();
    }
}
