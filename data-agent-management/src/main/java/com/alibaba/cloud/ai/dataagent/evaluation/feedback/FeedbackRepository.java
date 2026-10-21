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

import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationCaseDefinition;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationMode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** H2/MySQL-compatible persistence for feedback and its reviewed evaluation-case drafts. */
@Repository
public class FeedbackRepository {

    private static final String FEEDBACK_TABLE = "chainpilot_feedback";

    private static final String CASE_TABLE = "chainpilot_feedback_case";

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private static final TypeReference<List<FeedbackAssertion>> ASSERTION_LIST = new TypeReference<>() {
    };

    private static final TypeReference<FeedbackSnapshot> SNAPSHOT = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;

    private final ObjectMapper mapper;

    public FeedbackRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + FEEDBACK_TABLE + " ("
                + "feedback_id VARCHAR(36) PRIMARY KEY, dedupe_key VARCHAR(64) NOT NULL UNIQUE, "
                + "task_id VARCHAR(36) NOT NULL, result_type VARCHAR(16) NOT NULL, "
                + "proposal_id VARCHAR(36), feedback_type VARCHAR(32) NOT NULL, comment VARCHAR(1000), "
                + "narrative_source VARCHAR(32), result_fingerprint VARCHAR(64) NOT NULL, "
                + "evidence_ids_json TEXT, snapshot_json TEXT NOT NULL, created_at BIGINT NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + CASE_TABLE + " ("
                + "draft_id VARCHAR(36) PRIMARY KEY, feedback_id VARCHAR(36) NOT NULL UNIQUE, "
                + "case_id VARCHAR(64) UNIQUE, task_id VARCHAR(36) NOT NULL, result_type VARCHAR(16) NOT NULL, "
                + "name VARCHAR(200) NOT NULL, question VARCHAR(2000) NOT NULL, expected_behavior TEXT, "
                + "assertions_json TEXT, suggested_assertions_json TEXT, snapshot_json TEXT NOT NULL, "
                + "feedback_type VARCHAR(32) NOT NULL, status VARCHAR(24) NOT NULL, version VARCHAR(64) NOT NULL, "
                + "created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL, enabled_at BIGINT)");
    }

    public void saveFeedback(FeedbackRecord feedback) {
        jdbc.update("INSERT INTO " + FEEDBACK_TABLE + " (feedback_id, dedupe_key, task_id, result_type, "
                        + "proposal_id, feedback_type, comment, narrative_source, result_fingerprint, "
                        + "evidence_ids_json, snapshot_json, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                feedback.feedbackId(), dedupeKey(feedback), feedback.taskId(), feedback.resultType().name(),
                feedback.proposalId(), feedback.feedbackType().name(), feedback.comment(),
                feedback.narrativeSource(), feedback.resultFingerprint(), write(feedback.evidenceIds()),
                write(feedback.snapshot()), feedback.createdAt().toEpochMilli());
    }

    public Optional<FeedbackRecord> findFeedback(String feedbackId) {
        return jdbc.query("SELECT * FROM " + FEEDBACK_TABLE + " WHERE feedback_id=?", FEEDBACK_MAPPER, feedbackId)
            .stream()
            .findFirst();
    }

    public Optional<FeedbackRecord> findByDedupe(String dedupeKey) {
        return jdbc.query("SELECT * FROM " + FEEDBACK_TABLE + " WHERE dedupe_key=?", FEEDBACK_MAPPER, dedupeKey)
            .stream()
            .findFirst();
    }

    public List<FeedbackRecord> findFeedbackPage(String feedbackType, String resultType, String narrativeSource,
            String taskId, int limit, long offset) {
        List<Object> parameters = new ArrayList<>();
        String where = feedbackWhere(feedbackType, resultType, narrativeSource, taskId, parameters);
        parameters.add(limit);
        parameters.add(offset);
        return jdbc.query("SELECT * FROM " + FEEDBACK_TABLE + where
                + " ORDER BY created_at DESC, feedback_id DESC LIMIT ? OFFSET ?", FEEDBACK_MAPPER,
                parameters.toArray());
    }

    public long countFeedback(String feedbackType, String resultType, String narrativeSource, String taskId) {
        List<Object> parameters = new ArrayList<>();
        String where = feedbackWhere(feedbackType, resultType, narrativeSource, taskId, parameters);
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + FEEDBACK_TABLE + where, Long.class,
                parameters.toArray());
        return count == null ? 0 : count;
    }

    public FeedbackStats stats() {
        int total = (int) countFeedback(null, null, null, null);
        Map<String, Integer> byType = counts("SELECT feedback_type, COUNT(*) FROM " + FEEDBACK_TABLE
                + " GROUP BY feedback_type");
        Map<String, Integer> bySource = counts("SELECT narrative_source, COUNT(*) FROM " + FEEDBACK_TABLE
                + " GROUP BY narrative_source");
        Map<String, Integer> byResult = counts("SELECT result_type, COUNT(*) FROM " + FEEDBACK_TABLE
                + " GROUP BY result_type");
        int drafts = countDrafts("DRAFT");
        int ready = countDrafts("READY_FOR_REVIEW");
        int enabled = countDrafts("ENABLED");
        return new FeedbackStats(total, drafts, ready, enabled, byType, bySource, byResult);
    }

    public void saveDraft(FeedbackDraft draft) {
        jdbc.update("INSERT INTO " + CASE_TABLE + " (draft_id, feedback_id, case_id, task_id, result_type, name, "
                        + "question, expected_behavior, assertions_json, suggested_assertions_json, snapshot_json, "
                        + "feedback_type, status, version, created_at, updated_at, enabled_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                draft.draftId(), draft.feedbackId(), draft.caseId(), draft.snapshot().taskId(),
                draft.snapshot().resultType(), draft.name(), draft.snapshot().question(), draft.expectedBehavior(),
                write(draft.assertions()), write(draft.suggestedAssertions()), write(draft.snapshot()),
                draft.feedbackType().name(), draft.status(), "feedback-1.0.0",
                draft.createdAt().toEpochMilli(), draft.updatedAt().toEpochMilli(),
                draft.enabledAt() == null ? null : draft.enabledAt().toEpochMilli());
    }

    public Optional<FeedbackDraft> findDraft(String draftId) {
        return jdbc.query("SELECT * FROM " + CASE_TABLE + " WHERE draft_id=?", DRAFT_MAPPER, draftId)
            .stream()
            .findFirst();
    }

    public Optional<FeedbackDraft> findDraftByFeedback(String feedbackId) {
        return jdbc.query("SELECT * FROM " + CASE_TABLE + " WHERE feedback_id=?", DRAFT_MAPPER, feedbackId)
            .stream()
            .findFirst();
    }

    public List<FeedbackDraft> findDrafts(int limit, long offset) {
        return jdbc.query("SELECT * FROM " + CASE_TABLE
                + " ORDER BY created_at DESC, draft_id DESC LIMIT ? OFFSET ?", DRAFT_MAPPER, limit, offset);
    }

    public void updateDraft(String draftId, String expectedBehavior, List<FeedbackAssertion> assertions,
            String status, Instant updatedAt) {
        jdbc.update("UPDATE " + CASE_TABLE + " SET expected_behavior=?, assertions_json=?, status=?, updated_at=? "
                + "WHERE draft_id=?", expectedBehavior, write(assertions), status, updatedAt.toEpochMilli(), draftId);
    }

    public void enableDraft(String draftId, String caseId, String name, Instant enabledAt) {
        jdbc.update("UPDATE " + CASE_TABLE + " SET case_id=?, name=?, status='ENABLED', enabled_at=?, updated_at=? "
                + "WHERE draft_id=?", caseId, name, enabledAt.toEpochMilli(), enabledAt.toEpochMilli(), draftId);
    }

    public List<EvaluationCaseDefinition> enabledDefinitions() {
        return findEnabledCases().stream().map(this::definition).toList();
    }

    public List<FeedbackCaseRecord> findEnabledCases() {
        return jdbc.query("SELECT * FROM " + CASE_TABLE + " WHERE status='ENABLED'", CASE_MAPPER);
    }

    public Optional<FeedbackCaseRecord> findEnabledCase(String caseId) {
        return jdbc.query("SELECT * FROM " + CASE_TABLE + " WHERE status='ENABLED' AND case_id=?",
                CASE_MAPPER, caseId).stream().findFirst();
    }

    public Optional<EvaluationCaseDefinition> findEnabledDefinition(String caseId) {
        return findEnabledCase(caseId).map(this::definition);
    }

    private EvaluationCaseDefinition definition(FeedbackCaseRecord record) {
        return new EvaluationCaseDefinition(record.caseId(), record.name(),
                "人工反馈回归：" + record.feedbackType().name(), "feedback", "FEEDBACK_REGRESSION",
                EvaluationMode.OFFLINE, record.question(), record.expectedBehavior(), record.taskId(),
                record.version(), true,
                "来源 feedbackId=" + record.feedbackId() + "；预期值经人工审核，未自动采用用户评价。",
                record.assertions().stream().map(assertion -> assertion.description() + " = "
                        + assertion.expected()).toList());
    }

    private String feedbackWhere(String feedbackType, String resultType, String narrativeSource, String taskId,
            List<Object> parameters) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (feedbackType != null && !feedbackType.isBlank()) {
            where.append(" AND feedback_type=?");
            parameters.add(feedbackType);
        }
        if (resultType != null && !resultType.isBlank()) {
            where.append(" AND result_type=?");
            parameters.add(resultType);
        }
        if (narrativeSource != null && !narrativeSource.isBlank()) {
            where.append(" AND narrative_source=?");
            parameters.add(narrativeSource);
        }
        if (taskId != null && !taskId.isBlank()) {
            where.append(" AND task_id=?");
            parameters.add(taskId);
        }
        return where.toString();
    }

    private int countDrafts(String status) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + CASE_TABLE + " WHERE status=?",
                Integer.class, status);
        return count == null ? 0 : count;
    }

    private Map<String, Integer> counts(String sql) {
        Map<String, Integer> result = new LinkedHashMap<>();
        jdbc.query(sql, rs -> {
            result.put(rs.getString(1), rs.getInt(2));
        });
        return result;
    }

    private final RowMapper<FeedbackRecord> FEEDBACK_MAPPER = (rs, row) -> new FeedbackRecord(
            rs.getString("feedback_id"), rs.getString("task_id"),
            FeedbackResultType.valueOf(rs.getString("result_type")), rs.getString("proposal_id"),
            FeedbackType.valueOf(rs.getString("feedback_type")), rs.getString("comment"),
            rs.getString("narrative_source"), rs.getString("result_fingerprint"),
            read(rs.getString("evidence_ids_json"), STRING_LIST), read(rs.getString("snapshot_json"), SNAPSHOT),
            Instant.ofEpochMilli(rs.getLong("created_at")));

    private final RowMapper<FeedbackDraft> DRAFT_MAPPER = (rs, row) -> new FeedbackDraft(
            rs.getString("draft_id"), rs.getString("feedback_id"), rs.getString("case_id"),
            rs.getString("status"), rs.getString("name"), rs.getString("expected_behavior"),
            read(rs.getString("assertions_json"), ASSERTION_LIST),
            read(rs.getString("suggested_assertions_json"), ASSERTION_LIST),
            read(rs.getString("snapshot_json"), SNAPSHOT),
            FeedbackType.valueOf(rs.getString("feedback_type")),
            Instant.ofEpochMilli(rs.getLong("created_at")), Instant.ofEpochMilli(rs.getLong("updated_at")),
            rs.getObject("enabled_at") == null ? null : Instant.ofEpochMilli(rs.getLong("enabled_at")));

    private final RowMapper<FeedbackCaseRecord> CASE_MAPPER = (rs, row) -> new FeedbackCaseRecord(
            rs.getString("case_id"), rs.getString("draft_id"), rs.getString("feedback_id"),
            rs.getString("task_id"), FeedbackResultType.valueOf(rs.getString("result_type")),
            rs.getString("name"), rs.getString("question"), rs.getString("expected_behavior"),
            read(rs.getString("assertions_json"), ASSERTION_LIST), read(rs.getString("snapshot_json"), SNAPSHOT),
            FeedbackType.valueOf(rs.getString("feedback_type")), rs.getString("version"),
            rs.getObject("enabled_at") == null ? null : Instant.ofEpochMilli(rs.getLong("enabled_at")));

    private String dedupeKey(FeedbackRecord feedback) {
        return hash(feedback.taskId() + "|" + feedback.resultType() + "|" + feedback.proposalId() + "|"
                + feedback.feedbackType() + "|" + normalize(feedback.comment()));
    }

    private String hash(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        }
        catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize feedback payload", exception);
        }
    }

    private <T> T read(String value, TypeReference<T> type) {
        try {
            return mapper.readValue(value, type);
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored feedback payload is unreadable", exception);
        }
    }
}
