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

import com.alibaba.cloud.ai.dataagent.supplychain.model.DecisionTaskResult;
import com.alibaba.cloud.ai.dataagent.supplychain.model.OrderRiskAssessment;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskAnalysisReport;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskEvidence;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskType;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.DecisionProposalRepository;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.SupplyChainTaskRepository;
import com.alibaba.cloud.ai.dataagent.supplychain.service.SupplyChainResourceNotFoundException;
import com.alibaba.cloud.ai.dataagent.supplychain.service.SupplyChainWorkflowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Human feedback, draft review and enabled feedback cases. */
@Service
public class FeedbackService {

    public static final String DRAFT = "DRAFT";
    public static final String READY_FOR_REVIEW = "READY_FOR_REVIEW";
    public static final String ENABLED = "ENABLED";
    public static final String CASE_VERSION = "feedback-1.0.0";

    private final FeedbackRepository repository;

    private final SupplyChainTaskRepository taskRepository;

    private final SupplyChainWorkflowService workflowService;

    private final DecisionProposalRepository proposalRepository;

    private final ObjectMapper mapper;

    public FeedbackService(FeedbackRepository repository, SupplyChainTaskRepository taskRepository,
            SupplyChainWorkflowService workflowService, DecisionProposalRepository proposalRepository,
            ObjectMapper mapper) {
        this.repository = repository;
        this.taskRepository = taskRepository;
        this.workflowService = workflowService;
        this.proposalRepository = proposalRepository;
        this.mapper = mapper;
    }

    public FeedbackRecord submit(FeedbackRequest request) {
        if (request == null || !StringUtils.hasText(request.taskId())) {
            throw new IllegalArgumentException("taskId is required");
        }
        if (request.feedbackType() == null) {
            throw new IllegalArgumentException("feedbackType is required");
        }
        String taskId = request.taskId().trim();
        String comment = normalizeComment(request.comment());
        if (comment.length() > 1000) {
            throw new IllegalArgumentException("comment must be at most 1000 characters");
        }
        SupplyChainTaskStatus task = workflowService.task(taskId);
        if (task == null) {
            throw new IllegalArgumentException("referenced task does not exist");
        }
        if (!"COMPLETED".equals(task.status())) {
            throw new IllegalArgumentException("feedback can only reference a completed result");
        }
        FeedbackResultType resultType = resultType(taskId);
        String proposalId = StringUtils.hasText(request.proposalId()) ? request.proposalId().trim() : null;
        if (proposalId != null) {
            var proposal = proposalRepository.find(proposalId)
                .orElseThrow(() -> new SupplyChainResourceNotFoundException("Unknown proposal: " + proposalId));
            if (!taskId.equals(proposal.taskId())) {
                throw new IllegalArgumentException("proposal does not belong to the referenced task");
            }
        }
        FeedbackSnapshot snapshot = snapshot(taskId, resultType);
        FeedbackRecord candidate = new FeedbackRecord(UUID.randomUUID().toString(), taskId, resultType,
                proposalId, request.feedbackType(), comment, snapshot.narrativeSource(),
                fingerprint(snapshot), snapshot.evidenceIds(), snapshot, Instant.now());
        FeedbackRecord existing = repository.findByDedupe(dedupeKey(candidate)).orElse(null);
        if (existing != null) {
            return existing;
        }
        repository.saveFeedback(candidate);
        return candidate;
    }

    public List<FeedbackRecord> list(String feedbackType, String resultType, String narrativeSource, String taskId,
            int page, int pageSize) {
        validatePage(page, pageSize);
        return repository.findFeedbackPage(normalizeFilter(feedbackType), normalizeFilter(resultType),
                normalizeFilter(narrativeSource), normalizeTaskFilter(taskId), pageSize,
                (long) (page - 1) * pageSize);
    }

    public long count(String feedbackType, String resultType, String narrativeSource, String taskId) {
        return repository.countFeedback(normalizeFilter(feedbackType), normalizeFilter(resultType),
                normalizeFilter(narrativeSource), normalizeTaskFilter(taskId));
    }

    public FeedbackStats stats() {
        return repository.stats();
    }

    public FeedbackDraft createDraft(String feedbackId) {
        FeedbackRecord feedback = requireFeedback(feedbackId);
        FeedbackDraft existing = repository.findDraftByFeedback(feedback.feedbackId()).orElse(null);
        if (existing != null) {
            return existing;
        }
        Instant now = Instant.now();
        FeedbackSnapshot snapshot = feedback.snapshot();
        FeedbackDraft draft = new FeedbackDraft(UUID.randomUUID().toString(), feedback.feedbackId(), null, DRAFT,
                draftName(snapshot), "", List.of(), suggestions(snapshot), snapshot, feedback.feedbackType(),
                now, now, null);
        repository.saveDraft(draft);
        return draft;
    }

    public List<FeedbackDraft> drafts(int page, int pageSize) {
        validatePage(page, pageSize);
        return repository.findDrafts(pageSize, (long) (page - 1) * pageSize);
    }

    public FeedbackDraft draft(String draftId) {
        return repository.findDraft(draftId)
            .orElseThrow(() -> new SupplyChainResourceNotFoundException("Unknown feedback draft: " + draftId));
    }

    public FeedbackDraft updateDraft(String draftId, FeedbackDraftUpdateRequest request) {
        FeedbackDraft current = draft(draftId);
        if (ENABLED.equals(current.status())) {
            throw new IllegalStateException("Enabled feedback cases cannot be edited");
        }
        if (request == null) {
            throw new IllegalArgumentException("draft update is required");
        }
        String expectedBehavior = request.expectedBehavior() == null ? "" : request.expectedBehavior().trim();
        if (expectedBehavior.length() > 2000) {
            throw new IllegalArgumentException("expectedBehavior must be at most 2000 characters");
        }
        List<FeedbackAssertion> assertions = validatedAssertions(request.assertions(), current.snapshot());
        String status = StringUtils.hasText(expectedBehavior) && !assertions.isEmpty() ? READY_FOR_REVIEW : DRAFT;
        repository.updateDraft(draftId, expectedBehavior, assertions, status, Instant.now());
        return draft(draftId);
    }

    public FeedbackDraft review(String draftId) {
        FeedbackDraft current = draft(draftId);
        if (ENABLED.equals(current.status())) {
            return current;
        }
        if (!StringUtils.hasText(current.expectedBehavior())) {
            throw new IllegalArgumentException("expectedBehavior is required before review");
        }
        List<FeedbackAssertion> assertions = validatedAssertions(current.assertions(), current.snapshot());
        if (assertions.isEmpty()) {
            throw new IllegalArgumentException("at least one executable assertion is required before review");
        }
        repository.updateDraft(draftId, current.expectedBehavior().trim(), assertions, READY_FOR_REVIEW, Instant.now());
        return draft(draftId);
    }

    public FeedbackDraft enable(String draftId) {
        FeedbackDraft current = draft(draftId);
        if (ENABLED.equals(current.status())) {
            return current;
        }
        if (!READY_FOR_REVIEW.equals(current.status())) {
            throw new IllegalStateException("draft must pass review before it can become an evaluation case");
        }
        if (!StringUtils.hasText(current.expectedBehavior())) {
            throw new IllegalArgumentException("expectedBehavior is required");
        }
        List<FeedbackAssertion> assertions = validatedAssertions(current.assertions(), current.snapshot());
        if (assertions.isEmpty()) {
            throw new IllegalArgumentException("at least one executable assertion is required");
        }
        String caseId = "FB-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        String name = StringUtils.hasText(current.name()) ? current.name() : draftName(current.snapshot());
        repository.enableDraft(draftId, caseId, truncate(name, 200), Instant.now());
        return draft(draftId);
    }

    private FeedbackSnapshot snapshot(String taskId, FeedbackResultType resultType) {
        String question = taskRepository.findObservableTask(taskId)
            .map(task -> task.question())
            .orElse("");
        if (resultType == FeedbackResultType.ANALYSIS) {
            RiskAnalysisReport report;
            try {
                report = workflowService.report(taskId);
            }
            catch (RuntimeException exception) {
                throw new IllegalArgumentException("analysis report is not available for feedback", exception);
            }
            List<FeedbackSnapshot.OrderSnapshot> orders = report.atRiskOrders()
                .stream()
                .map(this::orderSnapshot)
                .toList();
            List<String> gaps = report.atRiskOrders().stream()
                .flatMap(item -> item.dataGaps().stream())
                .distinct()
                .toList();
            List<String> evidenceIds = report.atRiskOrders().stream()
                .flatMap(item -> item.evidence().stream())
                .map(RiskEvidence::code)
                .distinct()
                .toList();
            int high = (int) report.atRiskOrders().stream().filter(item -> "HIGH".equals(item.riskLevel().name())).count();
            return new FeedbackSnapshot(taskId, resultType.name(), question, report.summary(),
                    report.narrativeSource(), report.atRiskOrders().size(), high, gaps.size(), orders, gaps,
                    evidenceIds);
        }
        DecisionTaskResult decision;
        try {
            decision = workflowService.decision(taskId);
        }
        catch (RuntimeException exception) {
            throw new IllegalArgumentException("decision result is not available for feedback", exception);
        }
        List<OrderRiskAssessment> assessments = decision.atRiskOrders() == null ? List.of() : decision.atRiskOrders();
        List<FeedbackSnapshot.OrderSnapshot> orders = assessments.stream().map(this::orderSnapshot).toList();
        List<String> gaps = assessments.stream().flatMap(item -> item.dataGaps().stream()).distinct().toList();
        List<String> evidenceIds = assessments.stream()
            .flatMap(item -> item.evidence().stream())
            .map(RiskEvidence::code)
            .distinct()
            .toList();
        int high = (int) assessments.stream().filter(item -> "HIGH".equals(item.riskLevel().name())).count();
        return new FeedbackSnapshot(taskId, resultType.name(), question, decision.summary(),
                decision.narrativeSource(), assessments.size(), high, gaps.size(), orders, gaps, evidenceIds);
    }

    private FeedbackSnapshot.OrderSnapshot orderSnapshot(OrderRiskAssessment assessment) {
        List<String> codes = assessment.evidence() == null ? List.of()
                : assessment.evidence().stream().map(RiskEvidence::code).toList();
        return new FeedbackSnapshot.OrderSnapshot(assessment.order().orderId(), assessment.order().sku(),
                assessment.riskScore(), assessment.riskLevel().name(), assessment.inventoryShortage(), codes,
                assessment.dataGaps());
    }

    private FeedbackResultType resultType(String taskId) {
        String type = taskRepository.findTaskType(taskId)
            .orElseThrow(() -> new SupplyChainResourceNotFoundException("Unknown task: " + taskId));
        if (SupplyChainTaskType.DECISION.equals(type)) {
            return FeedbackResultType.DECISION;
        }
        return FeedbackResultType.ANALYSIS;
    }

    private FeedbackRecord requireFeedback(String feedbackId) {
        return repository.findFeedback(feedbackId)
            .orElseThrow(() -> new SupplyChainResourceNotFoundException("Unknown feedback: " + feedbackId));
    }

    private List<FeedbackAssertion> suggestions(FeedbackSnapshot snapshot) {
        List<FeedbackAssertion> suggestions = new ArrayList<>();
        suggestions.add(new FeedbackAssertion("sug-status", FeedbackAssertionType.TASK_STATUS, null,
                "COMPLETED", "任务状态 = COMPLETED（当前 Java 任务结果）"));
        suggestions.add(new FeedbackAssertion("sug-source", FeedbackAssertionType.NARRATIVE_SOURCE, null,
                snapshot.narrativeSource(), "叙述来源 = " + snapshot.narrativeSource()));
        suggestions.add(new FeedbackAssertion("sug-at-risk", FeedbackAssertionType.AT_RISK_ORDER_COUNT, null,
                String.valueOf(snapshot.atRiskOrderCount()), "风险订单数 = " + snapshot.atRiskOrderCount()));
        suggestions.add(new FeedbackAssertion("sug-high-risk", FeedbackAssertionType.HIGH_RISK_ORDER_COUNT, null,
                String.valueOf(snapshot.highRiskOrderCount()), "高风险订单数 = " + snapshot.highRiskOrderCount()));
        suggestions.add(new FeedbackAssertion("sug-gaps", FeedbackAssertionType.DATA_GAP_COUNT, null,
                String.valueOf(snapshot.dataGapCount()), "dataGap 数 = " + snapshot.dataGapCount()));
        if (!snapshot.orders().isEmpty()) {
            FeedbackSnapshot.OrderSnapshot first = snapshot.orders().get(0);
            suggestions.add(new FeedbackAssertion("sug-score", FeedbackAssertionType.RISK_SCORE, first.orderId(),
                    String.valueOf(first.riskScore()), first.orderId() + " 风险分 = " + first.riskScore()));
            suggestions.add(new FeedbackAssertion("sug-level", FeedbackAssertionType.RISK_LEVEL, first.orderId(),
                    first.riskLevel(), first.orderId() + " 风险等级 = " + first.riskLevel()));
            if (!first.evidenceCodes().isEmpty()) {
                suggestions.add(new FeedbackAssertion("sug-evidence", FeedbackAssertionType.EVIDENCE_CODE,
                        first.orderId(), first.evidenceCodes().get(0),
                        first.orderId() + " 含证据 " + first.evidenceCodes().get(0)));
            }
        }
        return suggestions;
    }

    private List<FeedbackAssertion> validatedAssertions(List<FeedbackAssertion> values, FeedbackSnapshot snapshot) {
        if (values == null) {
            return List.of();
        }
        if (values.size() > 20) {
            throw new IllegalArgumentException("at most 20 assertions are allowed");
        }
        Set<String> ids = new LinkedHashSet<>();
        List<FeedbackAssertion> result = new ArrayList<>();
        for (FeedbackAssertion assertion : values) {
            if (assertion == null || assertion.type() == null) {
                throw new IllegalArgumentException("assertion type is required");
            }
            String expected = assertion.expected() == null ? "" : assertion.expected().trim();
            if (expected.isEmpty() || expected.length() > 200) {
                throw new IllegalArgumentException("assertion expected value must contain 1-200 characters");
            }
            String target = assertion.target() == null ? null : assertion.target().trim();
            FeedbackSnapshot.OrderSnapshot order = target == null ? null
                    : snapshot.orders().stream().filter(item -> item.orderId().equals(target)).findFirst().orElse(null);
            switch (assertion.type()) {
                case TASK_STATUS -> requireOneOf(expected, "COMPLETED", "FAILED");
                case NARRATIVE_SOURCE -> requireOneOf(expected, "MODEL_VERIFIED", "MODEL_LEGACY_UNVERIFIED",
                        "DETERMINISTIC_FALLBACK", "UNKNOWN");
                case AT_RISK_ORDER_COUNT, HIGH_RISK_ORDER_COUNT, DATA_GAP_COUNT -> nonNegativeInt(expected);
                case RISK_SCORE -> {
                    if (order == null) {
                        throw new IllegalArgumentException("risk score assertion requires a known order target");
                    }
                    int score = nonNegativeInt(expected);
                    if (score > 100) {
                        throw new IllegalArgumentException("risk score must be between 0 and 100");
                    }
                }
                case RISK_LEVEL -> {
                    if (order == null) {
                        throw new IllegalArgumentException("risk level assertion requires a known order target");
                    }
                    requireOneOf(expected, "LOW", "MEDIUM", "HIGH");
                }
                case EVIDENCE_CODE -> {
                    if (order == null) {
                        throw new IllegalArgumentException("evidence assertion requires a known order target");
                    }
                }
                case DECISION_INTENT -> {
                    if (!"DECISION".equals(snapshot.resultType())) {
                        throw new IllegalArgumentException("decision intent assertion requires a decision result");
                    }
                }
            }
            String id = StringUtils.hasText(assertion.id()) ? assertion.id() : "assert-" + (ids.size() + 1);
            if (!ids.add(id)) {
                throw new IllegalArgumentException("assertion ids must be unique");
            }
            String description = StringUtils.hasText(assertion.description()) ? assertion.description().trim()
                    : assertion.type().name() + (target == null ? "" : ":" + target);
            result.add(new FeedbackAssertion(id, assertion.type(), target, expected, truncate(description, 200)));
        }
        return List.copyOf(result);
    }

    private static void requireOneOf(String value, String... allowed) {
        for (String candidate : allowed) {
            if (candidate.equalsIgnoreCase(value)) {
                return;
            }
        }
        throw new IllegalArgumentException("unsupported assertion expected value: " + value);
    }

    private static int nonNegativeInt(String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) {
                throw new NumberFormatException("negative");
            }
            return parsed;
        }
        catch (NumberFormatException exception) {
            throw new IllegalArgumentException("assertion expected value must be a non-negative integer");
        }
    }

    private static String draftName(FeedbackSnapshot snapshot) {
        return truncate("反馈回归 · " + (snapshot.question().isBlank() ? snapshot.taskId() : snapshot.question()), 200);
    }

    private static String normalizeComment(String comment) {
        return comment == null ? "" : comment.trim().replaceAll("\\s+", " ");
    }

    private static String normalizeFilter(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : null;
    }

    private static String normalizeTaskFilter(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static void validatePage(int page, int pageSize) {
        if (page < 1) {
            throw new IllegalArgumentException("page must be at least 1");
        }
        if (pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("pageSize must be between 1 and 100");
        }
    }

    private String fingerprint(FeedbackSnapshot snapshot) {
        try {
            return sha256(mapper.writeValueAsString(snapshot));
        }
        catch (Exception exception) {
            throw new IllegalStateException("Unable to fingerprint feedback result", exception);
        }
    }

    private String dedupeKey(FeedbackRecord feedback) {
        return sha256(feedback.taskId() + "|" + feedback.resultType() + "|" + feedback.proposalId() + "|"
                + feedback.feedbackType() + "|" + feedback.comment());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
