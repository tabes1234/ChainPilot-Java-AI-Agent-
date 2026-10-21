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
package com.alibaba.cloud.ai.dataagent.evaluation.feedback;

import com.alibaba.cloud.ai.dataagent.supplychain.mysql.SupplyChainMySqlTestSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opt-in real MySQL 8 check for feedback and draft tables. */
@EnabledIfSystemProperty(named = SupplyChainMySqlTestSupport.ENABLE_PROPERTY, matches = "true")
class FeedbackMySqlPersistenceIT {

    private static SupplyChainMySqlTestSupport.MySqlTarget mySql;

    private static DataSource dataSource;

    private static JdbcTemplate jdbc;

    private static FeedbackRepository repository;

    @BeforeAll
    static void startIsolatedMySql() {
        mySql = SupplyChainMySqlTestSupport.start();
        dataSource = new DriverManagerDataSource(mySql.jdbcUrl(), mySql.username(), mySql.password());
        jdbc = new JdbcTemplate(dataSource);
        repository = new FeedbackRepository(jdbc, new ObjectMapper().findAndRegisterModules());
    }

    @AfterAll
    static void stopIsolatedMySql() {
        if (mySql != null) {
            mySql.close();
        }
    }

    @Test
    void feedbackAndDraftSurviveARepeatedRepositoryStartupAndEnable() {
        FeedbackSnapshot snapshot = new FeedbackSnapshot("mysql-task-1", "ANALYSIS", "question", "summary",
                "DETERMINISTIC_FALLBACK", 1, 0, 0,
                List.of(new FeedbackSnapshot.OrderSnapshot("ORD-1", "SKU-1", 30, "MEDIUM", 0,
                        List.of("DELAY"), List.of())), List.of(), List.of("DELAY"));
        FeedbackRecord feedback = new FeedbackRecord("mysql-feedback-1", "mysql-task-1",
                FeedbackResultType.ANALYSIS, null, FeedbackType.INSUFFICIENT_EVIDENCE, "needs review",
                "DETERMINISTIC_FALLBACK", "fingerprint", List.of("DELAY"), snapshot, Instant.now());
        repository.saveFeedback(feedback);
        repository.saveDraft(new FeedbackDraft("mysql-draft-1", "mysql-feedback-1", null, "READY_FOR_REVIEW",
                "MySQL feedback case", "task and risk remain", List.of(new FeedbackAssertion("a", FeedbackAssertionType.RISK_SCORE,
                        "ORD-1", "30", "score")), List.of(), snapshot, FeedbackType.INSUFFICIENT_EVIDENCE,
                Instant.now(), Instant.now(), null));
        repository.enableDraft("mysql-draft-1", "FB-MYSQL-1", "MySQL feedback case", Instant.now());
        FeedbackRepository restarted = new FeedbackRepository(jdbc, new ObjectMapper().findAndRegisterModules());
        assertEquals(1, restarted.stats().total());
        assertEquals(1, restarted.stats().enabledCases());
        assertTrue(restarted.findEnabledCase("FB-MYSQL-1").isPresent());
        assertEquals(1, restarted.enabledDefinitions().size());
        assertFalse(restarted.enabledDefinitions().get(0).chatRequired());
        assertFalse(restarted.enabledDefinitions().get(0).embeddingRequired());
    }
}
