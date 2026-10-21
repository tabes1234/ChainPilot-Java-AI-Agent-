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

import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalSimulation;
import com.alibaba.cloud.ai.dataagent.supplychain.model.InventoryPosition;
import com.alibaba.cloud.ai.dataagent.supplychain.model.OrderRiskImpact;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SimulationBaselineView;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SimulationRequest;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SimulationScenario;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplierRiskProfile;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.DisposalSimulationRepository;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.SupplyChainDemoData;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisposalSimulationServiceTest {

    private SupplyChainQueryService queryService;

    private DisposalSimulationService simulationService;

    private DisposalSimulationRepository repository;

    @BeforeEach
    void setUp() {
        queryService = new SupplyChainQueryService(new SupplyChainDemoData());
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:simulation-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=MySQL", "sa", ""));
        repository = new DisposalSimulationRepository(jdbc, new ObjectMapper().findAndRegisterModules());
        simulationService = new DisposalSimulationService(queryService, repository);
    }

    @Test
    void baselineUsesTheSameRiskRuleAsTheAnalysisWorkspace() {
        DisposalSimulation simulation = simulationService.simulate(
                new SimulationRequest(SimulationScenario.INVENTORY_REPLENISHMENT, "SKU-MCU-001", 100, null, null),
                LocalDate.now());

        assertEquals(4, simulation.baseline().totalOrders());
        assertEquals(3, simulation.baseline().atRiskOrders());
        assertEquals(3, simulation.baseline().highRiskOrders());
        assertEquals(3, simulation.baseline().ordersWithShortage());
        assertEquals(330, simulation.baseline().totalShortageUnits());
        assertEquals(297, simulation.baseline().totalRiskScore());
        assertEquals(20, simulation.baseline().targetSkuAvailableToPromise());
        assertEquals("CURRENT_BUSINESS_DATA", simulation.baselineSource());
        assertFalse(simulation.realDataMutated());
        assertTrue(simulation.disclaimer().contains("未修改真实库存或订单数据"));
        assertEquals(DisposalSimulation.RULE_VERSION, simulation.ruleVersion());
        assertEquals(DisposalSimulation.SIMULATION_STATUS, simulation.simulationStatus());
        assertTrue(simulation.dataGaps().isEmpty());
    }

    @Test
    void replenishmentOnlyChangesTheTargetSkuAndKeepsDeterministicDeltas() {
        DisposalSimulation simulation = simulationService.simulate(
                new SimulationRequest(SimulationScenario.INVENTORY_REPLENISHMENT, "SKU-MCU-001", 100, null, null),
                LocalDate.now());

        assertEquals(120, simulation.simulated().targetSkuAvailableToPromise());
        assertEquals(100, simulation.delta().targetSkuAvailableToPromise());
        assertEquals(2, simulation.simulated().ordersWithShortage());
        assertEquals(230, simulation.simulated().totalShortageUnits());
        assertEquals(-100, simulation.delta().totalShortageUnits());
        assertEquals(272, simulation.simulated().totalRiskScore());
        assertEquals(-25, simulation.delta().totalRiskScore());
        assertEquals(0, simulation.delta().atRiskOrders());
        assertEquals(0, simulation.delta().highRiskOrders());
        assertEquals(-1, simulation.delta().ordersWithShortage());
        assertEquals(1, simulation.affectedOrders().size());

        OrderRiskImpact impact = simulation.affectedOrders().get(0);
        assertEquals("ORD-1001", impact.orderId());
        assertEquals(100, impact.baselineInventoryShortage());
        assertEquals(0, impact.simulatedInventoryShortage());
        assertEquals(75, impact.simulatedRiskScore());
        assertTrue(impact.changes().contains("inventoryShortage 100 -> 0"));
        assertTrue(impact.evidenceChanges().stream()
            .anyMatch(change -> change.contains("INVENTORY_SHORTAGE") || change.contains("INVENTORY_OK")));
        assertTrue(impact.remainingDataGaps().isEmpty());
    }

    @Test
    void supplierDelayRaisesRiskForOrdersThatWerePreviouslyBelowTheThreshold() {
        DisposalSimulation simulation = simulationService.simulate(
                new SimulationRequest(SimulationScenario.SUPPLIER_DELAY, null, null, "SUP-GAMMA", 10),
                LocalDate.now());

        assertNull(simulation.simulated().targetSkuAvailableToPromise());
        assertNull(simulation.delta().targetSkuAvailableToPromise());
        assertEquals(4, simulation.simulated().atRiskOrders());
        assertEquals(1, simulation.delta().atRiskOrders());
        assertEquals(337, simulation.simulated().totalRiskScore());
        assertEquals(40, simulation.delta().totalRiskScore());
        assertEquals(1, simulation.affectedOrders().size());
        OrderRiskImpact impact = simulation.affectedOrders().get(0);
        assertEquals("ORD-1003", impact.orderId());
        assertEquals(10, java.time.temporal.ChronoUnit.DAYS.between(
                impact.baselineEstimatedDeliveryDate(), impact.simulatedEstimatedDeliveryDate()));
        assertEquals("MEDIUM", impact.simulatedRiskLevel().name());
        assertTrue(impact.changes().contains("riskLevel LOW -> MEDIUM"));
    }


    @Test
    void baselineAndSimulationExposeMissingDataWithoutInventingValues() {
        SupplyChainDemoData sparseData = new SupplyChainDemoData() {
            @Override
            public List<InventoryPosition> inventory() {
                return super.inventory()
                    .stream()
                    .filter(position -> !"SKU-MCU-001".equalsIgnoreCase(position.sku()))
                    .toList();
            }

            @Override
            public List<SupplierRiskProfile> suppliers() {
                return super.suppliers()
                    .stream()
                    .filter(supplier -> !"SUP-BRAVO".equalsIgnoreCase(supplier.supplierId()))
                    .toList();
            }
        };
        DisposalSimulationService sparseService = new DisposalSimulationService(
                new SupplyChainQueryService(sparseData), repository);

        SimulationBaselineView baseline = sparseService.baseline();
        assertEquals(DisposalSimulation.RULE_VERSION, baseline.ruleVersion());
        assertEquals(DisposalSimulation.SIMULATION_STATUS, baseline.simulationStatus());
        assertEquals("CURRENT_BUSINESS_DATA", baseline.baselineSource());
        assertTrue(baseline.dataGaps().stream().anyMatch(gap -> gap.contains("SKU-MCU-001")));
        assertTrue(baseline.dataGaps().stream().anyMatch(gap -> gap.contains("SUP-BRAVO")));

        DisposalSimulation simulation = sparseService.simulate(new SimulationRequest(
                SimulationScenario.INVENTORY_REPLENISHMENT, "SKU-SENSOR-002", 50, null, null), LocalDate.now());
        assertTrue(simulation.dataGaps().stream().anyMatch(gap -> gap.contains("SKU-MCU-001")));
        assertTrue(simulation.dataGaps().stream().anyMatch(gap -> gap.contains("SUP-BRAVO")));
        assertTrue(simulation.affectedOrders().stream().anyMatch(impact -> !impact.remainingDataGaps().isEmpty()));
        assertFalse(simulation.realDataMutated());
    }

    @Test
    void simulationIsRepeatableAndNeverMutatesRealData() {
        SimulationRequest request = new SimulationRequest(SimulationScenario.INVENTORY_REPLENISHMENT,
                "SKU-MCU-001", 40, null, null);
        LocalDate asOf = LocalDate.now();
        var ordersBefore = queryService.findAllOrders();
        var inventoryBefore = queryService.findInventory(null);

        DisposalSimulation first = simulationService.simulate(request, asOf);
        DisposalSimulation second = simulationService.simulate(request, asOf);

        assertEquals(first.simulated(), second.simulated());
        assertEquals(first.delta(), second.delta());
        assertEquals(first.affectedOrders(), second.affectedOrders());
        assertEquals(ordersBefore, queryService.findAllOrders());
        assertEquals(inventoryBefore, queryService.findInventory(null));
        assertEquals(20, queryService.findInventory("SKU-MCU-001").get(0).availableToPromise());
        assertEquals(55, queryService.findInventory("SKU-MCU-001").get(0).onHandQuantity());
    }

    @Test
    void rejectsMissingOutOfRangeAndIrrelevantParameters() {
        LocalDate asOf = LocalDate.now();

        assertThrows(IllegalArgumentException.class, () -> simulationService.simulate(null, asOf));
        assertThrows(IllegalArgumentException.class,
                () -> simulationService.simulate(new SimulationRequest(null, "SKU-MCU-001", 10, null, null), asOf));
        assertThrows(IllegalArgumentException.class,
                () -> simulationService.simulate(
                        new SimulationRequest(SimulationScenario.INVENTORY_REPLENISHMENT, null, 10, null, null), asOf));
        assertThrows(IllegalArgumentException.class,
                () -> simulationService.simulate(
                        new SimulationRequest(SimulationScenario.INVENTORY_REPLENISHMENT, "SKU-MCU-001", null, null,
                                null),
                        asOf));
        assertThrows(IllegalArgumentException.class,
                () -> simulationService.simulate(
                        new SimulationRequest(SimulationScenario.INVENTORY_REPLENISHMENT, "SKU-MCU-001", 0, null,
                                null),
                        asOf));
        assertThrows(IllegalArgumentException.class,
                () -> simulationService.simulate(new SimulationRequest(
                        SimulationScenario.INVENTORY_REPLENISHMENT, "SKU-MCU-001", 1_000_001, null, null), asOf));
        assertThrows(IllegalArgumentException.class,
                () -> simulationService.simulate(new SimulationRequest(
                        SimulationScenario.INVENTORY_REPLENISHMENT, "SKU-MCU-001", 10, "SUP-ALPHA", null), asOf));
        assertThrows(IllegalArgumentException.class,
                () -> simulationService.simulate(
                        new SimulationRequest(SimulationScenario.SUPPLIER_DELAY, null, null, "SUP-ALPHA", null), asOf));
        assertThrows(IllegalArgumentException.class,
                () -> simulationService.simulate(
                        new SimulationRequest(SimulationScenario.SUPPLIER_DELAY, null, null, "SUP-ALPHA", 0), asOf));
        assertThrows(IllegalArgumentException.class,
                () -> simulationService.simulate(
                        new SimulationRequest(SimulationScenario.SUPPLIER_DELAY, null, null, "SUP-ALPHA", 91), asOf));
        assertThrows(IllegalArgumentException.class,
                () -> simulationService.simulate(new SimulationRequest(SimulationScenario.SUPPLIER_DELAY,
                        "SKU-MCU-001", null, "SUP-ALPHA", 5), asOf));
    }

    @Test
    void rejectsUnknownTargetsWithoutChangingAnything() {
        IllegalArgumentException unknownSku = assertThrows(IllegalArgumentException.class,
                () -> simulationService.simulate(
                        new SimulationRequest(SimulationScenario.INVENTORY_REPLENISHMENT, "SKU-UNKNOWN", 10, null,
                                null),
                        LocalDate.now()));
        assertTrue(unknownSku.getMessage().contains("Unknown SKU"));

        IllegalArgumentException unknownSupplier = assertThrows(IllegalArgumentException.class,
                () -> simulationService.simulate(
                        new SimulationRequest(SimulationScenario.SUPPLIER_DELAY, null, null, "SUP-UNKNOWN", 5),
                        LocalDate.now()));
        assertTrue(unknownSupplier.getMessage().contains("Unknown supplier"));
    }

    @Test
    void savedSimulationCanBeReloadedListedAndAttachedOnce() {
        DisposalSimulation simulation = simulationService.simulateAndSave(new SimulationRequest(
                SimulationScenario.INVENTORY_REPLENISHMENT, "SKU-MCU-001", 100, null, null));

        assertEquals(simulation, simulationService.findSimulation(simulation.simulationId()));
        assertEquals(1, simulationService.history(10, null).size());
        assertEquals(0, simulationService.history(10, "plan-1").size());

        simulationService.attachToPlan(simulation.simulationId(), "plan-1");
        assertEquals(1, simulationService.history(10, "plan-1").size());
        simulationService.attachToPlan(simulation.simulationId(), "plan-1");
        assertThrows(IllegalStateException.class,
                () -> simulationService.attachToPlan(simulation.simulationId(), "plan-2"));
        assertThrows(SupplyChainResourceNotFoundException.class,
                () -> simulationService.findSimulation("missing-simulation"));
        assertThrows(IllegalArgumentException.class, () -> simulationService.history(0, null));
        assertThrows(IllegalArgumentException.class, () -> simulationService.history(101, null));
    }

    @Test
    void boundaryQuantitiesAreAccepted() {
        assertEquals(1_000_020,
                simulationService.simulate(new SimulationRequest(SimulationScenario.INVENTORY_REPLENISHMENT,
                        "SKU-MCU-001", 1_000_000, null, null), LocalDate.now())
                    .simulated()
                    .targetSkuAvailableToPromise());
        assertEquals(76,
                simulationService.simulate(new SimulationRequest(SimulationScenario.INVENTORY_REPLENISHMENT,
                        "SKU-VALVE-003", 1, null, null), LocalDate.now())
                    .simulated()
                    .targetSkuAvailableToPromise());
        assertEquals(90, simulationService.simulate(new SimulationRequest(SimulationScenario.SUPPLIER_DELAY, null,
                null, "SUP-ALPHA", 90), LocalDate.now()).additionalDelayDays());
    }

}
