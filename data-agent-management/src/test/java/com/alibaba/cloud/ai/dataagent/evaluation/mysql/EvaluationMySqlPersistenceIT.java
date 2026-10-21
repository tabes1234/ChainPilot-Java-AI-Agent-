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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in MySQL 8 compatibility test for the evaluation schema. Run with
 * -Dchainpilot.mysql.integration=true and either Docker or a loopback dedicated
 * chainpilot_mysql_it database supplied via -Dchainpilot.mysql.url.
 */
@EnabledIfSystemProperty(named = SupplyChainMySqlTestSupport.ENABLE_PROPERTY, matches = "true")
class EvaluationMySqlPersistenceIT {

    private static final String RUN_TABLE = "chainpilot_evaluation_run";

    private static final String RESULT_TABLE = "chainpilot_evaluation_case_result";

    private static SupplyChainMySqlTestSupport.MySqlTarget mySql;

    private static DataSource dataSource;

    private static JdbcTemplate jdbc;

    private static EvaluationRunRepository repository;

    @BeforeAll
    static void startIsolatedMySql() {
        mySql = SupplyChainMySqlTestSupport.start();
        dataSource = new DriverManagerDataSource(mySql.jdbcUrl(), mySql.username(), mySql.password());
        jdbc = new JdbcTemplate(dataSource);
        assertEquals(SupplyChainMySqlTestSupport.DEDICATED_DATABASE,
                jdbc.queryForObject("SELECT DATABASE()", String.class));
        repository = new EvaluationRunRepository(jdbc, new ObjectMapper());
    }

    @AfterAll
    static void stopIsolatedMySql() {
        if (mySql != null) {
            mySql.close();
        }
    }

    @BeforeEach
    void clearEvaluationRows() {
        jdbc.update("DELETE FROM " + RESULT_TABLE);
        jdbc.update("DELETE FROM " + RUN_TABLE);
    }

    @Test
    void createsTheAdditiveSchemaAndIndexesOnRealMySql() {
        Map<String, Object> versionColumn = jdbc.queryForMap(
                "SELECT DATA_TYPE, IS_NULLABLE FROM information_schema.columns "
                        + "WHERE table_schema=DATABASE() AND table_name=? AND column_name='failure_reason'",
                RUN_TABLE);
        assertEquals("varchar", String.valueOf(versionColumn.get("DATA_TYPE")).toLowerCase());
        assertEquals("YES", versionColumn.get("IS_NULLABLE"));

        for (String column : List.of("case_version", "task_mode")) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() "
                            + "AND table_name=? AND column_name=?",
                    Integer.class, RESULT_TABLE, column);
            assertEquals(1, count, "missing column " + column);
        }
        Integer indexCount = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics "
                        + "WHERE table_schema=DATABASE() AND table_name=?",
                Integer.class, RESULT_TABLE);
        assertTrue(indexCount >= 3);
    }

    @Test
    void persistsCaseVersionTaskModeAndRunFailureReason() {
        EvaluationCaseResult result = new EvaluationCaseResult("WF-ANALYSIS-HIGH-RISK", "高风险订单分析",
                "真实工作流：高风险订单分析", "workflow", EvaluationCaseStatus.PASSED, 42L, "task-1",
                List.of(new EvaluationAssertionResult("report-exists", "报告存在", "present", "present", true)), "{}",
                null, null, "workflow-1.0.0", "ANALYSIS");
        EvaluationRun run = run("run-persist", "workflow-1.0.0", EvaluationRunStatus.COMPLETED, result, null);
        repository.save(run);

        EvaluationRun reloaded = repository.find("run-persist").orElseThrow();
        assertEquals("workflow-1.0.0", reloaded.datasetVersion());
        assertEquals("workflow-1.0.0", reloaded.results().get(0).caseVersion());
        assertEquals("ANALYSIS", reloaded.results().get(0).taskMode());
        assertEquals("task-1", reloaded.results().get(0).taskId());
        assertNotNull(repository.list(5).stream().filter(item -> item.runId().equals("run-persist")).findFirst());
    }

    @Test
    void replacesASavedRunAtomicallyAndEnforcesUniqueCaseRows() {
        EvaluationCaseResult first = result("CASE-1", EvaluationCaseStatus.PASSED);
        EvaluationRun run = run("run-replace", "1.0.0", EvaluationRunStatus.COMPLETED, first, null);
        repository.save(run);
        repository.save(run);

        assertEquals(1, count(RESULT_TABLE));

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO " + RESULT_TABLE + " (run_id,case_id,case_name,scenario,category,status,duration_ms,"
                        + "sort_order) VALUES (?,?,?,?,?,?,?,?)",
                "run-replace", "CASE-1", "dup", "dup", "risk", "PASSED", 1, 99));
    }

    @Test
    void closesInterruptedRunsAndTheirRunningCases() {
        EvaluationCaseResult runningCase = new EvaluationCaseResult("WF-DECISION-COMPARE", "候选方案比较",
                "真实工作流", "workflow", EvaluationCaseStatus.RUNNING, 0, "task-running", List.of(), "{}", null,
                null, "workflow-1.0.0", "DECISION");
        EvaluationRun running = run("run-interrupted", "workflow-1.0.0", EvaluationRunStatus.RUNNING,
                runningCase, null);
        repository.save(running);

        assertEquals(1, repository.markInterruptedRuns("test restart"));

        EvaluationRun recovered = repository.find("run-interrupted").orElseThrow();
        assertEquals(EvaluationRunStatus.FAILED, recovered.status());
        assertEquals("test restart", recovered.failureReason());
        assertEquals(EvaluationCaseStatus.UNDETERMINED, recovered.results().get(0).status());
        assertEquals("INTERRUPTED", recovered.results().get(0).failureType());
    }

    @Test
    void cascadeDeletesCaseRowsWithTheirRun() {
        repository.save(run("run-cascade", "1.0.0", EvaluationRunStatus.COMPLETED,
                result("CASE-1", EvaluationCaseStatus.PASSED), null));
        jdbc.update("DELETE FROM " + RUN_TABLE + " WHERE run_id=?", "run-cascade");
        assertEquals(0, count(RESULT_TABLE));
    }

    private static int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static EvaluationRun run(String runId, String version, EvaluationRunStatus status,
            EvaluationCaseResult result, String failureReason) {
        int passed = result.status() == EvaluationCaseStatus.PASSED ? 1 : 0;
        int failed = result.status() == EvaluationCaseStatus.FAILED ? 1 : 0;
        int undetermined = result.status() == EvaluationCaseStatus.UNDETERMINED ? 1 : 0;
        Instant completedAt = status == EvaluationRunStatus.RUNNING ? null : Instant.now();
        return new EvaluationRun(runId, version,
                EvaluationMode.AGENT, status, "MYSQL_TEST", Instant.now().minusSeconds(2), completedAt, 1, passed,
                failed, undetermined, completedAt == null ? null : 20L, Map.of(), List.of(result), failureReason);
    }

    private static EvaluationCaseResult result(String caseId, EvaluationCaseStatus status) {
        return new EvaluationCaseResult(caseId, caseId, "scenario", "risk", status, 10, null,
                List.of(new EvaluationAssertionResult("a", "assertion", "expected", "actual", status == EvaluationCaseStatus.PASSED)),
                "{}", null, null, "1.0.0", null);
    }

}
