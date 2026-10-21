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
package com.alibaba.cloud.ai.dataagent.supplychain.repository;

import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalSimulation;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalSimulationSummary;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SimulationRequest;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SimulationScenario;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Persists what-if parameters and results; H2/MySQL-compatible auto-DDL matches task storage. */
@Repository
public class DisposalSimulationRepository {

    private static final String TABLE = "chainpilot_disposal_simulation";

    private final JdbcTemplate jdbc;

    private final ObjectMapper mapper;

    public DisposalSimulationRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                + "simulation_id VARCHAR(36) PRIMARY KEY, scenario VARCHAR(32) NOT NULL, "
                + "sku VARCHAR(64), supplier_id VARCHAR(64), request_json TEXT NOT NULL, "
                + "result_json TEXT NOT NULL, plan_id VARCHAR(36), created_at BIGINT NOT NULL)");
    }

    public void save(DisposalSimulation simulation, String planId) {
        SimulationRequest request = new SimulationRequest(simulation.scenario(), simulation.sku(),
                simulation.replenishmentQuantity(), simulation.supplierId(), simulation.additionalDelayDays());
        try {
            jdbc.update("INSERT INTO " + TABLE + " (simulation_id, scenario, sku, supplier_id, request_json, "
                            + "result_json, plan_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    simulation.simulationId(), simulation.scenario().name(), simulation.sku(),
                    simulation.supplierId(), mapper.writeValueAsString(request),
                    mapper.writeValueAsString(simulation), planId, simulation.createdAt().toEpochMilli());
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to persist disposal simulation", exception);
        }
    }

    public Optional<DisposalSimulation> find(String simulationId) {
        List<String> payloads = jdbc.query("SELECT result_json FROM " + TABLE + " WHERE simulation_id=?",
                (rs, row) -> rs.getString(1), simulationId);
        if (payloads.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(read(payloads.get(0)));
    }

    public List<DisposalSimulationSummary> findRecent(int limit, String planId) {
        List<Object> parameters = new ArrayList<>();
        String where = "";
        if (planId != null && !planId.isBlank()) {
            where = " WHERE plan_id=?";
            parameters.add(planId);
        }
        parameters.add(limit);
        return jdbc.query("SELECT simulation_id, scenario, sku, supplier_id, result_json, plan_id, created_at FROM "
                        + TABLE + where + " ORDER BY created_at DESC, simulation_id DESC LIMIT ?",
                (rs, row) -> {
                    DisposalSimulation simulation = read(rs.getString("result_json"));
                    return new DisposalSimulationSummary(rs.getString("simulation_id"),
                            simulation.scenario(), rs.getString("sku"), rs.getString("supplier_id"),
                            parameterSummary(simulation), simulation.affectedOrders().size(),
                            simulation.delta().atRiskOrders(), rs.getString("plan_id"),
                            Instant.ofEpochMilli(rs.getLong("created_at")));
                }, parameters.toArray());
    }

    public void attachToPlan(String simulationId, String planId) {
        int updated = jdbc.update("UPDATE " + TABLE + " SET plan_id=? "
                + "WHERE simulation_id=? AND (plan_id IS NULL OR plan_id=?)", planId, simulationId, planId);
        if (updated == 0) {
            Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM " + TABLE + " WHERE simulation_id=?",
                    Integer.class, simulationId);
            if (exists == null || exists == 0) {
                throw new IllegalArgumentException("Unknown simulation: " + simulationId);
            }
            throw new IllegalStateException("Simulation is already attached to another plan");
        }
    }

    private DisposalSimulation read(String payload) {
        try {
            return mapper.readValue(payload, DisposalSimulation.class);
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored disposal simulation is unreadable", exception);
        }
    }

    private static String parameterSummary(DisposalSimulation simulation) {
        if (simulation.scenario() == SimulationScenario.INVENTORY_REPLENISHMENT) {
            return simulation.sku() + " +" + simulation.replenishmentQuantity() + " 件";
        }
        return simulation.supplierId() + " +" + simulation.additionalDelayDays() + " 天";
    }
}
