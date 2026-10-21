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

import com.alibaba.cloud.ai.dataagent.supplychain.model.InventoryPosition;
import com.alibaba.cloud.ai.dataagent.supplychain.model.OrderStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.OrderRiskAssessment;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskLevel;
import com.alibaba.cloud.ai.dataagent.supplychain.model.WorkflowPlan;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainOrder;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplierRiskProfile;
import com.alibaba.cloud.ai.dataagent.service.aimodelconfig.AiModelRegistry;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.SupplyChainDemoData;
import com.alibaba.cloud.ai.dataagent.supplychain.service.DisposalExecutionService;
import com.alibaba.cloud.ai.dataagent.supplychain.service.DisposalPlanService;
import com.alibaba.cloud.ai.dataagent.supplychain.service.DisposalRecommendationService;
import com.alibaba.cloud.ai.dataagent.supplychain.service.DisposalScenarioComparisonService;
import com.alibaba.cloud.ai.dataagent.supplychain.service.DisposalSimulationService;
import com.alibaba.cloud.ai.dataagent.supplychain.service.SupplyChainAgentTools;
import com.alibaba.cloud.ai.dataagent.supplychain.service.SupplyChainAnalysisService;
import com.alibaba.cloud.ai.dataagent.supplychain.service.RiskNarrativeValidator;
import com.alibaba.cloud.ai.dataagent.supplychain.service.SupplyChainEventRecorder;
import com.alibaba.cloud.ai.dataagent.supplychain.service.SupplyChainKnowledgeService;
import com.alibaba.cloud.ai.dataagent.supplychain.service.SupplyChainQueryService;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SupplyChainBusinessAcceptanceTest {

    private static final LocalDate TODAY = LocalDate.now();

    @Test
    void normalOrderHasNoRiskAndRoutineAction() {
        var result = assess(10, 100, RiskLevel.LOW, TODAY.plusDays(5), TODAY.plusDays(5), OrderStatus.CONFIRMED);
        assertEquals(0, result.riskScore());
        assertEquals(RiskLevel.LOW, result.riskLevel());
        assertTrue(result.riskFactors().stream().anyMatch(f -> f.contains("No delay")));
        assertTrue(result.recommendedActions().contains("Continue routine monitoring"));
        assertTrue(result.evidence().stream().anyMatch(e -> "DELAY_OK".equals(e.code())));
        assertTrue(result.evidence().stream().anyMatch(e -> "INVENTORY_OK".equals(e.code())));
        assertTrue(result.evidence().stream().anyMatch(e -> "SUPPLIER_OK".equals(e.code())));
        assertTrue(result.evidence().stream().anyMatch(e -> "NO_RISK".equals(e.code())));
        assertTrue(result.dataGaps().isEmpty());
        assertReportEvidence("正常订单供应商风险例行监控", result, RiskLevel.LOW, "SC-SOP-003");
    }

    @Test
    void imminentDelayIsMediumAndIncludesSupplierRecoveryAction() {
        var result = assess(10, 100, RiskLevel.LOW, TODAY.plusDays(2), TODAY.plusDays(3), OrderStatus.CONFIRMED);
        assertEquals(30, result.riskScore());
        assertEquals(RiskLevel.MEDIUM, result.riskLevel());
        assertTrue(result.riskFactors().stream().anyMatch(f -> f.contains("1 day(s)")));
        assertTrue(result.recommendedActions().stream().anyMatch(a -> a.contains("recovery date")));
        assertReportEvidence("订单延期，确认供应商恢复日期", result, RiskLevel.LOW, "SC-SOP-001");
    }

    @Test
    void inventoryShortageIsMediumAndExplainsTheQuantityAndMitigation() {
        var result = assess(100, 60, RiskLevel.LOW, TODAY.plusDays(5), TODAY.plusDays(5), OrderStatus.CONFIRMED);
        assertEquals(34, result.riskScore());
        assertEquals(RiskLevel.MEDIUM, result.riskLevel());
        assertEquals(40, result.inventoryShortage());
        assertTrue(result.riskFactors().stream().anyMatch(f -> f.contains("short by 40")));
        assertTrue(result.recommendedActions().stream().anyMatch(a -> a.contains("alternate stock")));
        assertTrue(result.evidence().stream().anyMatch(e -> "INVENTORY_SHORTAGE".equals(e.code())
                && e.observedValue().contains("availableToPromise=60")
                && e.observedValue().contains("shortage=40")
                && e.scoreContribution() == 34));
        assertEquals(result.riskScore(), result.evidence().stream().mapToInt(e -> e.scoreContribution()).sum());
        assertReportEvidence("库存短缺，检查可承诺库存和补货", result, RiskLevel.LOW, "SC-SOP-002");
    }

    @Test
    void supplierIssueIsMediumAndCallsForCapacityConfirmation() {
        var result = assess(10, 100, RiskLevel.MEDIUM, TODAY.plusDays(5), TODAY.plusDays(5), OrderStatus.CONFIRMED);
        assertEquals(30, result.riskScore());
        assertEquals(RiskLevel.MEDIUM, result.riskLevel());
        assertTrue(result.riskFactors().stream().anyMatch(f -> f.contains("Supplier risk is MEDIUM")));
        assertTrue(result.recommendedActions().stream().anyMatch(a -> a.contains("capacity confirmation")));
        assertReportEvidence("供应商履约风险，需要产能确认", result, RiskLevel.MEDIUM, "SC-SOP-003");
    }

    @Test
    void simultaneousDelayShortageAndSupplierIncidentIsHighWithAllEvidenceAndActions() {
        var result = assess(100, 60, RiskLevel.HIGH, TODAY.plusDays(1), TODAY.plusDays(3), OrderStatus.IN_TRANSIT);
        assertEquals(100, result.riskScore());
        assertEquals(RiskLevel.HIGH, result.riskLevel());
        assertEquals(3, result.riskFactors().size());
        assertEquals(3, result.recommendedActions().size());
        assertTrue(result.riskFactors().stream().anyMatch(f -> f.contains("2 day(s)")));
        assertTrue(result.riskFactors().stream().anyMatch(f -> f.contains("short by 40")));
        assertTrue(result.riskFactors().stream().anyMatch(f -> f.contains("Supplier risk is HIGH")));
        assertReportEvidence("订单延期库存短缺供应商风险", result, RiskLevel.HIGH, "SC-SOP-001", "SC-SOP-002",
                "SC-SOP-003");
    }

    @Test
    void missingInventoryAndSupplierAreDataGapsInsteadOfFabricatedNumbers() {
        SupplyChainOrder order = new SupplyChainOrder("ORD-MISSING", "SKU-MISSING", 100, "SUP-MISSING",
                TODAY.plusDays(5), TODAY.plusDays(5), OrderStatus.CONFIRMED);
        SupplyChainDemoData data = new SupplyChainDemoData() {
            @Override
            public List<SupplyChainOrder> orders() {
                return List.of(order);
            }

            @Override
            public List<InventoryPosition> inventory() {
                return List.of();
            }

            @Override
            public List<SupplierRiskProfile> suppliers() {
                return List.of();
            }
        };
        var result = new SupplyChainQueryService(data).findOrders(null, null).get(0);

        assertEquals(0, result.inventoryShortage());
        assertEquals(0, result.riskScore());
        assertTrue(result.dataGaps().stream().anyMatch(gap -> gap.contains("库存快照缺失")));
        assertTrue(result.dataGaps().stream().anyMatch(gap -> gap.contains("供应商画像缺失")));
        assertTrue(result.evidence().stream().anyMatch(e -> "INVENTORY_MISSING".equals(e.code())
                && !e.available() && e.scoreContribution() == 0));
        assertTrue(result.evidence().stream().anyMatch(e -> "SUPPLIER_MISSING".equals(e.code())
                && !e.available() && e.scoreContribution() == 0));
    }

    private com.alibaba.cloud.ai.dataagent.supplychain.model.OrderRiskAssessment assess(int quantity,
            int available, RiskLevel supplierRisk, LocalDate requested, LocalDate estimated, OrderStatus status) {
        SupplyChainDemoData data = new SupplyChainDemoData() {
            @Override
            public List<SupplyChainOrder> orders() {
                return List.of(new SupplyChainOrder("ORD-ACCEPTANCE", "SKU-ACCEPTANCE", quantity,
                        "SUP-ACCEPTANCE", requested, estimated, status));
            }

            @Override
            public List<InventoryPosition> inventory() {
                return List.of(new InventoryPosition("SKU-ACCEPTANCE", "Acceptance item", available, 0, 0));
            }

            @Override
            public List<SupplierRiskProfile> suppliers() {
                return List.of(new SupplierRiskProfile("SUP-ACCEPTANCE", "Acceptance supplier", supplierRisk,
                        0.82, 0.02, "Acceptance scenario supplier signal"));
            }
        };
        return new SupplyChainQueryService(data).findOrders(null, null).get(0);
    }

    @SuppressWarnings("unchecked")
    private void assertReportEvidence(String question, OrderRiskAssessment assessment, RiskLevel supplierRisk,
            String... expectedSources) {
        ObjectProvider<VectorStore> vectorStore = mock(ObjectProvider.class);
        when(vectorStore.getIfAvailable()).thenReturn(null);
        SupplyChainKnowledgeService knowledge = new SupplyChainKnowledgeService(vectorStore,
                new PathMatchingResourcePatternResolver());
        SupplyChainQueryService query = new SupplyChainQueryService(new SupplyChainDemoData());
        AiModelRegistry modelRegistry = mock(AiModelRegistry.class);
        when(modelRegistry.getChatClient()).thenThrow(new IllegalStateException("No model configured"));
        SupplyChainEventRecorder eventRecorder = mock(SupplyChainEventRecorder.class);
        SupplyChainAnalysisService analysis = new SupplyChainAnalysisService(query,
                new SupplyChainAgentTools(query, mock(DisposalRecommendationService.class),
                        mock(DisposalSimulationService.class), mock(DisposalExecutionService.class),
                        mock(DisposalScenarioComparisonService.class), mock(DisposalPlanService.class),
                        eventRecorder),
                modelRegistry, knowledge, eventRecorder, ObservationRegistry.NOOP,
                new RiskNarrativeValidator(new ObjectMapper()), new ObjectMapper());
        var order = assessment.order();
        int available = order.quantity() - assessment.inventoryShortage();
        var report = analysis.analyze(question, "acceptance-task", List.of(assessment),
                List.of(new InventoryPosition(order.sku(), "Acceptance item", available, 0, 0)),
                List.of(new SupplierRiskProfile(order.supplierId(), "Acceptance supplier", supplierRisk,
                        0.82, 0.02, "Acceptance scenario supplier signal")), 0,
                new WorkflowPlan(List.of("Query evidence", "Generate recommendations"), false));

        assertFalse(report.modelAssisted());
		assertEquals(assessment.recommendedActions(), report.recommendations());
        for (String expectedSource : expectedSources) {
            assertTrue(report.citations().stream().anyMatch(citation -> citation.sourceId().contains(expectedSource)),
                    "Expected report to cite " + expectedSource + " for " + question);
        }
    }
}
