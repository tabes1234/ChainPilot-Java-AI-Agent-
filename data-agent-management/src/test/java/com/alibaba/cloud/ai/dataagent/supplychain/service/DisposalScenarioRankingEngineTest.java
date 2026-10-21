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

import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioCandidate;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioComparisonOutcome;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioRanking;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioRankingResult;
import com.alibaba.cloud.ai.dataagent.supplychain.model.InventoryPosition;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SimulationBaseline;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SimulationRequest;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SimulationScenario;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.DisposalSimulationRepository;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.SupplyChainDemoData;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisposalScenarioRankingEngineTest {

    private final DisposalScenarioRankingEngine engine = new DisposalScenarioRankingEngine();

    private DisposalSimulationService simulationService;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:ranking-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=MySQL", "sa", ""));
        simulationService = new DisposalSimulationService(
                new SupplyChainQueryService(new SupplyChainDemoData()),
                new DisposalSimulationRepository(jdbc, new ObjectMapper().findAndRegisterModules()));
    }

    @Test
    void ranksRealReplenishmentSimulationsByRiskThenAtpAndExplainsEachTradeoff() {
        DisposalScenarioCandidate q50 = replenishmentCandidate(1, 50);
        DisposalScenarioCandidate q100 = replenishmentCandidate(2, 100);
        DisposalScenarioCandidate q200 = replenishmentCandidate(3, 200);

        DisposalScenarioRankingResult result = engine.rank(q50.simulation().baseline(), List.of(q50, q100, q200));

        assertEquals(DisposalScenarioComparisonOutcome.RECOMMENDED, result.recommendation().outcome());
        assertEquals(q100.candidateId(), result.recommendation().recommendedCandidateId());
        assertEquals(1, result.recommendation().recommendedRank());
        assertTrue(result.recommendation().summary().contains("推荐候选"));
        assertEquals(DisposalScenarioRankingEngine.RANKING_RULES, result.rankingRules());
        assertTrue(result.recommendation().disclaimer().contains("确定性 Java 规则"));

        DisposalScenarioRanking q100Ranking = ranking(result, q100);
        assertEquals(1, q100Ranking.rank());
        assertFalse(q100Ranking.tied());
        assertTrue(q100Ranking.recommended());
        assertTrue(q100Ranking.comparable());
        assertTrue(q100Ranking.keyDeltas().contains("缺口总量（件） 330 → 230（-100）"));
        assertTrue(q100Ranking.keyDeltas().contains("高风险订单 3 → 3（0）"));
        assertTrue(q100Ranking.reasons().stream().anyMatch(reason -> reason.contains("最优")));
        assertTrue(q100Ranking.rankingBasis().contains("ATP 120"));

        DisposalScenarioRanking q200Ranking = ranking(result, q200);
        assertEquals(2, q200Ranking.rank());
        assertFalse(q200Ranking.tied());
        assertFalse(q200Ranking.recommended());
        assertTrue(q200Ranking.rankingBasis().contains("ATP 220"));
        assertTrue(q200Ranking.reasons().stream().anyMatch(reason -> reason.contains("ATP")));
        assertEquals(q200.simulation().delta().totalShortageUnits(),
                q100.simulation().delta().totalShortageUnits());
        assertTrue(q200Ranking.tradeoffs().stream().anyMatch(tradeoff -> tradeoff.contains("库存占用")));

        DisposalScenarioRanking q50Ranking = ranking(result, q50);
        assertEquals(3, q50Ranking.rank());
        assertFalse(q50Ranking.tied());
        assertFalse(q50Ranking.recommended());
        assertTrue(q50Ranking.reasons().stream().anyMatch(reason -> reason.contains("存在缺口的订单")),
                () -> q50Ranking.reasons().toString());
        assertTrue(q50Ranking.tradeoffs().stream().anyMatch(tradeoff -> tradeoff.contains("改善更小")));
    }

    @Test
    void supplierDelayCandidatesTieOnExistingMetricsAndPreferTheSmallestDelay() {
        DisposalScenarioCandidate delay5 = supplierDelayCandidate(1, 5);
        DisposalScenarioCandidate delay10 = supplierDelayCandidate(2, 10);
        DisposalScenarioCandidate delay20 = supplierDelayCandidate(3, 20);

        DisposalScenarioRankingResult result = engine.rank(delay5.simulation().baseline(),
                List.of(delay5, delay10, delay20));

        assertEquals(DisposalScenarioComparisonOutcome.TIE_BREAK, result.recommendation().outcome());
        assertEquals(delay5.candidateId(), result.recommendation().recommendedCandidateId());
        assertEquals(1, ranking(result, delay5).rank());
        assertEquals(1, ranking(result, delay10).rank());
        assertEquals(1, ranking(result, delay20).rank());
        assertTrue(ranking(result, delay10).tied());
        assertTrue(ranking(result, delay10).reasons().stream().anyMatch(reason -> reason.contains("最小干预")));
        assertNull(delay5.simulation().simulated().targetSkuAvailableToPromise());
        assertEquals(4, delay5.simulation().simulated().atRiskOrders());
    }

    @Test
    void differentBaselinesAreIncomparableAndNeverForcedIntoARanking() {
        SimulationBaseline baseline = simulationService.captureBaseline(LocalDate.now());
        List<InventoryPosition> changedInventory = baseline.inventory()
            .stream()
            .map(position -> position.sku().equals("SKU-MCU-001")
                    ? new InventoryPosition(position.sku(), position.productName(),
                            position.onHandQuantity() + 500, position.reservedQuantity(),
                            position.safetyStock())
                    : position)
            .toList();
        SimulationBaseline changed = new SimulationBaseline(baseline.orders(), changedInventory,
                baseline.suppliers(), baseline.asOfDate(), Instant.now());
        DisposalScenarioCandidate normal = candidate(1, 100, baseline);
        DisposalScenarioCandidate otherBaseline = candidate(2, 200, changed);

        DisposalScenarioRankingResult result = engine.rank(normal.simulation().baseline(),
                List.of(normal, otherBaseline));

        assertEquals(DisposalScenarioComparisonOutcome.INCOMPARABLE, result.recommendation().outcome());
        assertNull(result.recommendation().recommendedCandidateId());
        assertNull(result.recommendation().recommendedRank());
        assertTrue(result.recommendation().summary().contains("未生成推荐"));
        assertTrue(result.recommendation().unknowns().stream()
            .anyMatch(unknown -> unknown.contains("基线")));
        assertTrue(result.rankings().stream().noneMatch(DisposalScenarioRanking::comparable));
        assertTrue(result.rankings().stream().allMatch(ranking -> ranking.rank() == null));
        assertTrue(result.rankings().stream()
            .allMatch(ranking -> ranking.rankingBasis().contains("无法比较")));
        assertTrue(result.rankings().stream()
            .allMatch(ranking -> ranking.reasons().stream()
                .anyMatch(reason -> reason.contains("缺少可比条件"))));
    }

    @Test
    void mixedScenariosScopesAndMissingSimulationsAreIncomparable() {
        DisposalScenarioCandidate replenishment = replenishmentCandidate(1, 100);
        DisposalScenarioCandidate supplierDelay = supplierDelayCandidate(2, 10);
        DisposalScenarioRankingResult mixed = engine.rank(replenishment.simulation().baseline(),
                List.of(replenishment, supplierDelay));
        assertEquals(DisposalScenarioComparisonOutcome.INCOMPARABLE, mixed.recommendation().outcome());
        assertTrue(mixed.recommendation().reasons().stream()
            .anyMatch(reason -> reason.contains("场景") || reason.contains("范围")));

        DisposalScenarioCandidate missingSimulation = new DisposalScenarioCandidate("missing", "缺失模拟", 1,
                SimulationScenario.INVENTORY_REPLENISHMENT, "SKU-MCU-001", null, 100, null, null, null,
                Instant.now());
        DisposalScenarioRankingResult missing = engine.rank(replenishment.simulation().baseline(),
                List.of(missingSimulation, replenishmentCandidate(2, 200)));
        assertEquals(DisposalScenarioComparisonOutcome.INCOMPARABLE, missing.recommendation().outcome());
        assertTrue(missing.rankings().stream().allMatch(ranking -> ranking.rank() == null));

        DisposalScenarioRankingResult tooFew = engine.rank(replenishment.simulation().baseline(),
                List.of(replenishment));
        assertEquals(DisposalScenarioComparisonOutcome.INCOMPARABLE, tooFew.recommendation().outcome());
        assertTrue(tooFew.recommendation().reasons().stream()
            .anyMatch(reason -> reason.contains("2 到 5")));

        DisposalScenarioRankingResult noBaseline = engine.rank(null,
                List.of(replenishmentCandidate(1, 50), replenishmentCandidate(2, 100)));
        assertEquals(DisposalScenarioComparisonOutcome.INCOMPARABLE,
                noBaseline.recommendation().outcome());
        assertTrue(noBaseline.recommendation().unknowns().stream()
            .anyMatch(unknown -> unknown.contains("基线")));
    }

    @Test
    void rankingRulesDocumentPriorityDirectionAndUnknownCostData() {
        assertTrue(DisposalScenarioRankingEngine.RANKING_RULES.get(0).contains("同一份基线快照"));
        assertTrue(DisposalScenarioRankingEngine.RANKING_RULES.stream()
            .anyMatch(rule -> rule.contains("高风险订单变化量") && rule.contains("越小越好")));
        assertTrue(DisposalScenarioRankingEngine.RANKING_RULES.stream()
            .anyMatch(rule -> rule.contains("ATP") && rule.contains("最小干预")));
        assertTrue(DisposalScenarioRankingEngine.RANKING_RULES.stream()
            .anyMatch(rule -> rule.contains("并列")));
        assertTrue(DisposalScenarioRankingEngine.RANKING_RULES.stream()
            .anyMatch(rule -> rule.contains("成本") && rule.contains("不使用")));
    }

    private DisposalScenarioCandidate replenishmentCandidate(int position, int quantity) {
        SimulationBaseline baseline = simulationService.captureBaseline(LocalDate.now());
        return candidate(position, quantity, baseline);
    }

    private DisposalScenarioCandidate candidate(int position, int quantity, SimulationBaseline baseline) {
        var simulation = simulationService.simulate(new SimulationRequest(
                SimulationScenario.INVENTORY_REPLENISHMENT, "SKU-MCU-001", quantity, null, null), baseline);
        return new DisposalScenarioCandidate(UUID.randomUUID().toString(), "补货 +" + quantity + " 件", position,
                SimulationScenario.INVENTORY_REPLENISHMENT, "SKU-MCU-001", null, quantity, null, simulation, null,
                Instant.now());
    }

    private DisposalScenarioCandidate supplierDelayCandidate(int position, int days) {
        var simulation = simulationService.simulate(new SimulationRequest(SimulationScenario.SUPPLIER_DELAY, null,
                null, "SUP-GAMMA", days));
        return new DisposalScenarioCandidate(UUID.randomUUID().toString(), "延期 +" + days + " 天", position,
                SimulationScenario.SUPPLIER_DELAY, null, "SUP-GAMMA", null, days, simulation, null, Instant.now());
    }

    private static DisposalScenarioRanking ranking(DisposalScenarioRankingResult result,
            DisposalScenarioCandidate candidate) {
        return result.rankings()
            .stream()
            .filter(ranking -> ranking.candidateId().equals(candidate.candidateId()))
            .findFirst()
            .orElseThrow();
    }
}
