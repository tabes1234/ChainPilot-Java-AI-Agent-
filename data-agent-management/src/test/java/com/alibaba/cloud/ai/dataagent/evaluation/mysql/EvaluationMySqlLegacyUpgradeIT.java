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
package com.alibaba.cloud.ai.dataagent.evaluation.mysql;

import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationAssertionResult;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationCaseResult;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationCaseStatus;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationMode;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationRun;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationRunStatus;
import com.alibaba.cloud.ai.dataagent.evaluation.repository.EvaluationRunRepository;
import com.alibaba.cloud.ai.dataagent.supplychain.mysql.SupplyChainMySqlTestSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real MySQL 8 upgrade test from the first evaluation schema. It builds the actual old
 * tables in the dedicated test database, then runs the repository's startup upgrade path
 * (not a copied hand-written ALTER script) and checks data preservation and constraints.
 */
@EnabledIfSystemProperty(named = SupplyChainMySqlTestSupport.ENABLE_PROPERTY, matches = "true")
class EvaluationMySqlLegacyUpgradeIT {

    private static final String RUN_TABLE = "chainpilot_evaluation_run";

    private static final String RESULT_TABLE = "chainpilot_evaluation_case_result";

    private static SupplyChainMySqlTestSupport.MySqlTarget mySql;

    private static JdbcTemplate jdbc;

    private static EvaluationRunRepository repository;

    @BeforeAll
    static void buildLegacySchemaAndUpgrade() {
        mySql = SupplyChainMySqlTestSupport.start();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(mySql.jdbcUrl(), mySql.username(), mySql.password()));
        assertEquals(SupplyChainMySqlTestSupport.DEDICATED_DATABASE,
                jdbc.queryForObject("SELECT DATABASE()", String.class));

        jdbc.execute("DROP TABLE IF EXISTS " + RESULT_TABLE);
        jdbc.execute("DROP TABLE IF EXISTS " + RUN_TABLE);
        jdbc.execute("CREATE TABLE " + RUN_TABLE + " ("
                + "run_id VARCHAR(36) PRIMARY KEY, dataset_version VARCHAR(64) NOT NULL, "
                + "mode VARCHAR(16) NOT NULL, status VARCHAR(16) NOT NULL, environment VARCHAR(64) NOT NULL, "
                + "started_at BIGINT NOT NULL, completed_at BIGINT, total_cases INTEGER NOT NULL, "
                + "passed_count INTEGER NOT NULL, failed_count INTEGER NOT NULL, "
                + "undetermined_count INTEGER NOT NULL, duration_ms BIGINT, metrics_json TEXT)");
        jdbc.execute("CREATE TABLE " + RESULT_TABLE + " ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY, run_id VARCHAR(36) NOT NULL, "
                + "case_id VARCHAR(80) NOT NULL, case_name VARCHAR(300) NOT NULL, scenario VARCHAR(400) NOT NULL, "
                + "category VARCHAR(32) NOT NULL, status VARCHAR(16) NOT NULL, duration_ms BIGINT NOT NULL, "
                + "task_id VARCHAR(36), assertions_json TEXT, observation_json TEXT, "
                + "failure_type VARCHAR(48), failure_message VARCHAR(1000), sort_order INTEGER NOT NULL)");
        jdbc.execute("CREATE INDEX idx_evaluation_result_run ON " + RESULT_TABLE + " (run_id)");

        long started = Instant.now().minusSeconds(30).toEpochMilli();
        long completed = Instant.now().minusSeconds(20).toEpochMilli();
        jdbc.update("INSERT INTO " + RUN_TABLE + " (run_id,dataset_version,mode,status,environment,started_at,"
                        + "completed_at,total_cases,passed_count,failed_count,undetermined_count,duration_ms,"
                        + "metrics_json) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                "legacy-run", "1.0.0", "OFFLINE", "COMPLETED", "LEGACY_TEST", started, completed, 1, 1, 0, 0,
                1000L, "{}");
        jdbc.update("INSERT INTO " + RESULT_TABLE + " (run_id,case_id,case_name,scenario,category,status,"
                        + "duration_ms,task_id,assertions_json,observation_json,failure_type,failure_message,"
                        + "sort_order) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                "legacy-run", "ORDER-ORD-1001", "legacy order", "legacy", "risk", "PASSED", 12L, null, "[]", "{}",
                null, null, 0);

        // This constructor is the actual startup migration path used by the application.
        repository = new EvaluationRunRepository(jdbc, new ObjectMapper());
    }

    @AfterAll
    static void stopIsolatedMySql() {
        if (mySql != null) {
            mySql.close();
        }
    }

    @Test
    void upgradesLegacyMySqlTablesWithoutLosingRunsAndEnforcesNewConstraints() {
        EvaluationRun legacy = repository.find("legacy-run").orElseThrow();
        assertEquals("1.0.0", legacy.datasetVersion());
        assertEquals(EvaluationRunStatus.COMPLETED, legacy.status());
        assertEquals(1, legacy.passed());
        assertEquals("legacy order", legacy.results().get(0).name());
        assertNull(legacy.failureReason());
        assertNull(legacy.results().get(0).caseVersion());
        assertNull(legacy.results().get(0).taskMode());

        for (String column : List.of("failure_reason")) {
            assertEquals(1, columnCount(RUN_TABLE, column));
        }
        for (String column : List.of("case_version", "task_mode")) {
            assertEquals(1, columnCount(RESULT_TABLE, column));
        }

        EvaluationCaseResult result = new EvaluationCaseResult("ORDER-ORD-1001", "new", "new", "risk",
                EvaluationCaseStatus.FAILED, 15L, "task-real", List.of(new EvaluationAssertionResult("a", "a",
                        "expected", "actual", false)), "{}", "ASSERTION_MISMATCH", "fixture failure", "1.0.0", null);
        EvaluationRun upgraded = new EvaluationRun("upgraded-run", "1.0.0", EvaluationMode.OFFLINE,
                EvaluationRunStatus.FAILED, "LEGACY_TEST", Instant.now().minusSeconds(10), Instant.now(), 1, 0, 1, 0,
                5L, Map.of(), List.of(result), "legacy upgrade write check");
        repository.save(upgraded);

        EvaluationRun reloaded = repository.find("upgraded-run").orElseThrow();
        assertEquals("legacy upgrade write check", reloaded.failureReason());
        assertEquals("1.0.0", reloaded.results().get(0).caseVersion());

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO " + RESULT_TABLE + " (run_id,case_id,case_name,scenario,category,status,duration_ms,"
                        + "sort_order) VALUES (?,?,?,?,?,?,?,?)",
                "upgraded-run", "ORDER-ORD-1001", "dup", "dup", "risk", "PASSED", 1, 99));

        jdbc.update("DELETE FROM " + RUN_TABLE + " WHERE run_id=?", "upgraded-run");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + RESULT_TABLE + " WHERE run_id=?", Integer.class, "upgraded-run"));

        // Re-running the upgrade path must be idempotent and must not remove the old row.
        EvaluationRunRepository upgradedAgain = new EvaluationRunRepository(jdbc, new ObjectMapper());
        assertTrue(upgradedAgain.find("legacy-run").isPresent());
        assertEquals(1, columnCount(RESULT_TABLE, "case_version"));
        assertEquals(1, columnCount(RESULT_TABLE, "task_mode"));
    }

    private static int columnCount(String table, String column) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() "
                        + "AND table_name=? AND column_name=?",
                Integer.class, table, column);
    }

}
