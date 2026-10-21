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

import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskAnalysisReport;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskSummary;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.SupplyChainTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupplyChainTaskRepositoryTest {

    @Test
    void completedTaskAndReportSurviveRepositoryRecreation() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:chainpilot-persistence;DB_CLOSE_DELAY=-1", "sa", ""));
		SupplyChainTaskRepository repository = new SupplyChainTaskRepository(jdbc,
				new ObjectMapper().findAndRegisterModules());
        Instant now = Instant.now();
        repository.create(new SupplyChainTaskStatus("task-persist", "ACCEPTED", "QUEUED", 0, null, now),
                "analyze orders");
        RiskAnalysisReport report = new RiskAnalysisReport("task-persist", "COMPLETED", "verified",
                List.of("query"), false, List.of(), List.of(), List.of(), List.of(), false, 0, now);
        repository.saveReport(report);
        repository.update(new SupplyChainTaskStatus("task-persist", "COMPLETED", "DONE", 0, null, now));

		SupplyChainTaskRepository afterRestart = new SupplyChainTaskRepository(jdbc,
				new ObjectMapper().findAndRegisterModules());

        assertEquals("COMPLETED", afterRestart.findTask("task-persist").orElseThrow().status());
        assertEquals(report, afterRestart.findReport("task-persist").orElseThrow());
    }

    @Test
    void retentionDeletesExpiredTerminalJobsButNeverRunningJobs() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:chainpilot-retention;DB_CLOSE_DELAY=-1", "sa", ""));
		SupplyChainTaskRepository repository = new SupplyChainTaskRepository(jdbc,
				new ObjectMapper().findAndRegisterModules());
        Instant old = Instant.now().minus(Duration.ofDays(2));
        repository.create(new SupplyChainTaskStatus("active-task", "ACCEPTED", "QUEUED", 0, null, old), "active");
        repository.create(new SupplyChainTaskStatus("done-task", "ACCEPTED", "QUEUED", 0, null, old), "done");
        repository.update(new SupplyChainTaskStatus("done-task", "COMPLETED", "DONE", 0, null, old));

        repository.prune(Duration.ofHours(24), 0);

        assertTrue(repository.findTask("active-task").isPresent());
        assertFalse(repository.findTask("done-task").isPresent());
    }

    @Test
    void retentionKeepsTasksReferencedByDurableBusinessRecords() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:chainpilot-referenced-retention;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE chainpilot_disposal_plan (task_id VARCHAR(36))");
        jdbc.execute("CREATE TABLE chainpilot_feedback (task_id VARCHAR(36))");
        jdbc.execute("CREATE TABLE chainpilot_task_event (task_id VARCHAR(36))");
        SupplyChainTaskRepository repository = new SupplyChainTaskRepository(jdbc,
                new ObjectMapper().findAndRegisterModules());
        Instant old = Instant.now().minus(Duration.ofDays(2));
        for (String id : List.of("plan-task", "feedback-task", "free-task")) {
            repository.create(new SupplyChainTaskStatus(id, "ACCEPTED", "QUEUED", 0, null, old), id);
            repository.update(new SupplyChainTaskStatus(id, "COMPLETED", "DONE", 0, null, old));
        }
        jdbc.update("INSERT INTO chainpilot_disposal_plan (task_id) VALUES (?)", "plan-task");
        jdbc.update("INSERT INTO chainpilot_feedback (task_id) VALUES (?)", "feedback-task");
        jdbc.update("INSERT INTO chainpilot_task_event (task_id) VALUES (?)", "free-task");

        repository.prune(Duration.ofHours(24), 1_000);

        assertTrue(repository.findTask("plan-task").isPresent());
        assertTrue(repository.findTask("feedback-task").isPresent());
        assertFalse(repository.findTask("free-task").isPresent());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM chainpilot_task_event", Integer.class));
    }

	@Test
	void restartMarksInterruptedWorkAsFailedWithoutDeletingIt() {
		JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
				"jdbc:h2:mem:chainpilot-restart;DB_CLOSE_DELAY=-1", "sa", ""));
		SupplyChainTaskRepository beforeRestart = new SupplyChainTaskRepository(jdbc,
				new ObjectMapper().findAndRegisterModules());
		beforeRestart.create(new SupplyChainTaskStatus("interrupted-task", "ACCEPTED", "QUEUED", 0, null,
				Instant.now()), "analysis in progress");
		beforeRestart.update(new SupplyChainTaskStatus("interrupted-task", "RUNNING", "QUERYING_ORDERS", 0, null,
				Instant.now()));

		SupplyChainTaskRepository afterRestart = new SupplyChainTaskRepository(jdbc,
				new ObjectMapper().findAndRegisterModules());

		SupplyChainTaskStatus recovered = afterRestart.findTask("interrupted-task").orElseThrow();
		assertEquals("FAILED", recovered.status());
		assertEquals("INTERRUPTED_BY_RESTART", recovered.currentStep());
		assertTrue(recovered.error().contains("restarted"));
	}

	@Test
	void recentTaskHistoryIsNewestFirstAndFlagsAvailableReports() {
		JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
				"jdbc:h2:mem:chainpilot-history-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
		SupplyChainTaskRepository repository = new SupplyChainTaskRepository(jdbc,
				new ObjectMapper().findAndRegisterModules());
		Instant now = Instant.now();
		Instant old = now.minus(Duration.ofHours(2));
		Instant completedAt = now.minus(Duration.ofHours(1));
		repository.create(new SupplyChainTaskStatus("old-task", "ACCEPTED", "QUEUED", 0, null, old), "old question");
		repository.create(new SupplyChainTaskStatus("running-task", "ACCEPTED", "QUEUED", 0, null, now),
				"running question");
		repository.create(new SupplyChainTaskStatus("completed-task", "ACCEPTED", "QUEUED", 0, null, old),
				"completed question");
		RiskAnalysisReport report = new RiskAnalysisReport("completed-task", "COMPLETED", "verified",
				List.of("query"), false, List.of(), List.of(), List.of(), List.of(), false, 0, completedAt);
		repository.saveReport(report);
		repository.update(new SupplyChainTaskStatus("completed-task", "COMPLETED", "DONE", 0, null, completedAt));

		List<SupplyChainTaskSummary> history = repository.findRecent(10);

		assertEquals(List.of("running-task", "completed-task", "old-task"),
				history.stream().map(SupplyChainTaskSummary::taskId).toList());
		assertFalse(history.get(0).reportAvailable());
		assertEquals("running question", history.get(0).question());
		assertTrue(history.get(1).reportAvailable());
		assertEquals("completed question", history.get(1).question());
		assertEquals(1, repository.findRecent(1).size());
	}

	@Test
	void historyFiltersAndPaginatesWithStableOrdering() {
		JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
				"jdbc:h2:mem:chainpilot-history-page-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
		SupplyChainTaskRepository repository = new SupplyChainTaskRepository(jdbc,
				new ObjectMapper().findAndRegisterModules());
		Instant base = Instant.parse("2026-01-01T00:00:00Z");
		seedTask(repository, "completed-new", "newest completed", "COMPLETED", base.plusSeconds(4));
		seedTask(repository, "running-task", "still running", "RUNNING", base.plusSeconds(3));
		seedTask(repository, "failed-task", "failed question", "FAILED", base.plusSeconds(2));
		seedTask(repository, "completed-old", "old completed", "COMPLETED", base.plusSeconds(1));
		repository.saveReport(new RiskAnalysisReport("completed-new", "COMPLETED", "verified", List.of("query"),
				false, List.of(), List.of(), List.of(), List.of(), false, 0, base.plusSeconds(4)));
		repository.saveReport(new RiskAnalysisReport("completed-old", "COMPLETED", "verified", List.of("query"),
				false, List.of(), List.of(), List.of(), List.of(), false, 0, base.plusSeconds(1)));

		assertEquals(4, repository.countTasks(null));
		assertEquals(2, repository.countTasks("COMPLETED"));
		assertEquals(1, repository.countTasks("FAILED"));
		assertEquals(0, repository.countTasks("ACCEPTED"));

		List<SupplyChainTaskSummary> firstPage = repository.findHistory(null, 2, 0);
		assertEquals(List.of("completed-new", "running-task"),
				firstPage.stream().map(SupplyChainTaskSummary::taskId).toList());
		assertTrue(firstPage.get(0).reportAvailable());
		assertFalse(firstPage.get(1).reportAvailable());

		List<SupplyChainTaskSummary> secondPage = repository.findHistory(null, 2, 2);
		assertEquals(List.of("failed-task", "completed-old"),
				secondPage.stream().map(SupplyChainTaskSummary::taskId).toList());

		List<SupplyChainTaskSummary> completed = repository.findHistory("COMPLETED", 10, 0);
		assertEquals(List.of("completed-new", "completed-old"),
				completed.stream().map(SupplyChainTaskSummary::taskId).toList());
		assertTrue(repository.findHistory("COMPLETED", 10, 10).isEmpty());
	}

	private static void seedTask(SupplyChainTaskRepository repository, String taskId, String question,
			String status, Instant updatedAt) {
		repository.create(new SupplyChainTaskStatus(taskId, status, "DONE", 0, null, updatedAt), question);
	}
}
