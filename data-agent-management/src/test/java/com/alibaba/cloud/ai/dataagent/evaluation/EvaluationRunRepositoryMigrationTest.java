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
package com.alibaba.cloud.ai.dataagent.evaluation;

import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationAssertionResult;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationCaseResult;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationCaseStatus;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationMode;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationRun;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationRunStatus;
import com.alibaba.cloud.ai.dataagent.evaluation.repository.EvaluationRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Verifies the additive upgrade path from the first evaluation schema. */
class EvaluationRunRepositoryMigrationTest {

    @Test
    void upgradesLegacyTablesWithoutDroppingExistingRuns() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:evaluation-migration-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE chainpilot_evaluation_run ("
                + "run_id VARCHAR(36) PRIMARY KEY, dataset_version VARCHAR(64) NOT NULL, mode VARCHAR(16) NOT NULL, "
                + "status VARCHAR(16) NOT NULL, environment VARCHAR(64) NOT NULL, started_at BIGINT NOT NULL, "
                + "completed_at BIGINT, total_cases INTEGER NOT NULL, passed_count INTEGER NOT NULL, "
                + "failed_count INTEGER NOT NULL, undetermined_count INTEGER NOT NULL, duration_ms BIGINT, "
                + "metrics_json TEXT)");
        jdbc.execute("CREATE TABLE chainpilot_evaluation_case_result ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY, run_id VARCHAR(36) NOT NULL, case_id VARCHAR(80) NOT NULL, "
                + "case_name VARCHAR(300) NOT NULL, scenario VARCHAR(400) NOT NULL, category VARCHAR(32) NOT NULL, "
                + "status VARCHAR(16) NOT NULL, duration_ms BIGINT NOT NULL, task_id VARCHAR(36), "
                + "assertions_json TEXT, observation_json TEXT, failure_type VARCHAR(48), "
                + "failure_message VARCHAR(1000), sort_order INTEGER NOT NULL)");
        long now = Instant.now().toEpochMilli();
        jdbc.update("INSERT INTO chainpilot_evaluation_run VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)", "legacy-run",
                "1.0.0", "OFFLINE", "COMPLETED", "TEST", now, now, 1, 1, 0, 0, 10L, "{}");
        jdbc.update("INSERT INTO chainpilot_evaluation_case_result (run_id,case_id,case_name,scenario,category,status,"
                        + "duration_ms,assertions_json,observation_json,sort_order) VALUES (?,?,?,?,?,?,?,?,?,?)",
                "legacy-run", "CASE-1", "legacy", "legacy", "risk", "PASSED", 5L, "[]", "{}", 0);

        EvaluationRunRepository repository = new EvaluationRunRepository(jdbc, new ObjectMapper());

        EvaluationRun legacy = repository.find("legacy-run").orElseThrow();
        assertNull(legacy.failureReason());
        assertNull(legacy.results().get(0).caseVersion());
        assertNull(legacy.results().get(0).taskMode());

        EvaluationCaseResult upgradedCase = new EvaluationCaseResult("CASE-2", "new", "new", "workflow",
                EvaluationCaseStatus.PASSED, 8L, "task-new",
                List.of(new EvaluationAssertionResult("a", "a", "expected", "actual", true)), "{}", null, null,
                "workflow-1.0.0", "ANALYSIS");
        EvaluationRun upgraded = new EvaluationRun("upgraded-run", "workflow-1.0.0", EvaluationMode.AGENT,
                EvaluationRunStatus.COMPLETED, "TEST", Instant.now(), Instant.now(), 1, 1, 0, 0, 10L, Map.of(),
                List.of(upgradedCase), null);
        repository.save(upgraded);

        EvaluationRun reloaded = repository.find("upgraded-run").orElseThrow();
        assertEquals("workflow-1.0.0", reloaded.results().get(0).caseVersion());
        assertEquals("ANALYSIS", reloaded.results().get(0).taskMode());
        assertEquals(2, repository.findTerminalWithResults(10).size());
    }

}
