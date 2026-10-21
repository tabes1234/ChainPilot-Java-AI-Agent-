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

import com.alibaba.cloud.ai.dataagent.supplychain.model.CreateSandboxExecutionRequest;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalActionType;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalPlan;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalPlanStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalPriority;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalRecommendation;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskLevel;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxExecution;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxExecutionEligibility;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.DisposalExecutionRepository;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.DisposalPlanRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DisposalExecutionServiceTest {

    private final DisposalPlanRepository planRepository = mock(DisposalPlanRepository.class);

    private final DisposalExecutionRepository executionRepository = mock(DisposalExecutionRepository.class);

    private final SupplyChainQueryService queryService = mock(SupplyChainQueryService.class);

    private final DisposalExecutionService service = new DisposalExecutionService(planRepository,
            executionRepository, queryService, mock(PlatformTransactionManager.class));

    @Test
    void manualConfirmationActionsAreIneligibleAndNeverCreateAnExecutionRecord() {
        DisposalPlan plan = manualPlan();
        when(planRepository.find("plan-manual")).thenReturn(Optional.of(plan));
        when(planRepository.findForUpdate("plan-manual")).thenReturn(Optional.of(plan));
        when(executionRepository.findByIdempotencyKeyForUpdate("plan-manual", "manual-key"))
            .thenReturn(Optional.empty());

        SandboxExecutionEligibility eligibility = service.eligibility("plan-manual");

        assertFalse(eligibility.eligible());
        assertTrue(eligibility.reason().contains("MANUAL_CONFIRMATION"));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service.start("plan-manual", new CreateSandboxExecutionRequest("manual-key")));

        assertTrue(failure.getMessage().contains("MANUAL_CONFIRMATION"));
        verify(executionRepository, never()).save(any(SandboxExecution.class));
        verifyNoInteractions(queryService);
    }

    private static DisposalPlan manualPlan() {
        Instant now = Instant.parse("2026-10-20T12:00:00Z");
        DisposalRecommendation recommendation = new DisposalRecommendation("rec-manual",
                DisposalActionType.MANUAL_CONFIRMATION, DisposalPriority.P1, 80, "缺失主数据", RiskLevel.HIGH,
                "SKU-MCU-001", "SUP-ALPHA", List.of("ORD-1001"), "人工核对订单 ORD-1001 的主数据",
                "补齐缺失的库存或供应商主数据后重新评估", "当前订单存在风险，但主数据不完整。",
                List.of("缺少供应商履约记录"), "补齐主数据后重新评估", List.of("补齐库存与供应商主数据"),
                List.of("暂不自动处置"), List.of("订单主数据查询"), List.of("主数据可能缺失"), "OPEN", now);
        return new DisposalPlan("plan-manual", "人工核对方案", null, "SKU-MCU-001", null,
                DisposalPlanStatus.SELECTED, "rec-manual", List.of(recommendation), null, List.of(),
                "规则解释：需要人工核对主数据。", "RULE", false, List.of(), now, now);
    }
}
