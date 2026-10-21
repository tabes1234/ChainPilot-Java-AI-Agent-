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

import com.alibaba.cloud.ai.dataagent.supplychain.model.CreateComparisonDisposalPlanRequest;
import com.alibaba.cloud.ai.dataagent.supplychain.model.CreateSandboxExecutionRequest;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DecisionProposal;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalPlan;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalPlanStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioCandidate;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioComparison;
import com.alibaba.cloud.ai.dataagent.supplychain.model.LinkComparisonCandidatePlanRequest;
import com.alibaba.cloud.ai.dataagent.supplychain.model.ProposalActionType;
import com.alibaba.cloud.ai.dataagent.supplychain.model.ProposalStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxExecution;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxExecutionEligibility;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxExecutionStartResult;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskType;
import com.alibaba.cloud.ai.dataagent.supplychain.model.UpdateDisposalPlanStatusRequest;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.DecisionProposalRepository;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.SupplyChainTaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Creates content-hash-bound proposals and executes them only after explicit confirmation.
 * The model never receives a write tool; every state change is revalidated against the
 * current business objects before the existing deterministic services are called.
 */
@Service
public class ActionProposalService {

    static final Duration PROPOSAL_TTL = Duration.ofMinutes(30);

    private static final int MAX_EVIDENCE_ITEMS = 20;

    private final DecisionProposalRepository proposalRepository;

    private final SupplyChainTaskRepository taskRepository;

    private final DisposalScenarioComparisonService comparisonService;

    private final DisposalPlanService planService;

    private final DisposalExecutionService executionService;

    private final SupplyChainEventRecorder eventRecorder;

    public ActionProposalService(DecisionProposalRepository proposalRepository,
            SupplyChainTaskRepository taskRepository, DisposalScenarioComparisonService comparisonService,
            DisposalPlanService planService, DisposalExecutionService executionService,
            SupplyChainEventRecorder eventRecorder) {
        this.proposalRepository = proposalRepository;
        this.taskRepository = taskRepository;
        this.comparisonService = comparisonService;
        this.planService = planService;
        this.executionService = executionService;
        this.eventRecorder = eventRecorder;
    }

    public DecisionProposal create(String taskId, ProposalActionType actionType, String title, String targetId,
            Map<String, Object> parameters, List<String> evidence) {
        String resolvedTaskId = requireText(taskId, "taskId");
        if (actionType == null) {
            throw new IllegalArgumentException("actionType is required");
        }
        String resolvedTargetId = requireText(targetId, "targetId");
        String resolvedTitle = requireText(title, "title");
        if (resolvedTitle.length() > 200) {
            throw new IllegalArgumentException("title must be at most 200 characters");
        }
        SupplyChainTaskStatus task = taskRepository.findTask(resolvedTaskId)
            .orElseThrow(() -> new SupplyChainResourceNotFoundException("Unknown analysis task: " + resolvedTaskId));
        String taskType = taskRepository.findTaskType(resolvedTaskId).orElse(SupplyChainTaskType.ANALYSIS);
        if (!SupplyChainTaskType.DECISION.equals(taskType)) {
            throw new IllegalArgumentException("Action proposals can only belong to decision tasks");
        }
        Map<String, Object> resolvedParameters = normalizeParameters(parameters);
        validateTarget(actionType, resolvedTargetId, resolvedParameters);

        String contentHash = contentHash(resolvedTaskId, actionType, resolvedTitle, resolvedTargetId,
                resolvedParameters);
        Instant now = Instant.now();
        DecisionProposal existing = proposalRepository
            .findPendingByTaskAndHash(resolvedTaskId, contentHash)
            .orElse(null);
        if (existing != null) {
            if (existing.expiresAt().isAfter(now)) {
                return existing;
            }
            proposalRepository.markExpired(existing.proposalId());
        }
        DecisionProposal proposal = new DecisionProposal(UUID.randomUUID().toString(), resolvedTaskId, actionType,
                resolvedTitle, resolvedTargetId, resolvedParameters, normalizeEvidence(evidence), contentHash,
                ProposalStatus.PENDING, now, now.plus(PROPOSAL_TTL), null, null, null);
        proposalRepository.save(proposal);
        eventRecorder.proposalCreated(resolvedTaskId, proposal.proposalId(), actionType.name(), resolvedTargetId);
        return proposal;
    }

    public DecisionProposal proposal(String proposalId) {
        String resolvedId = requireText(proposalId, "proposalId");
        DecisionProposal proposal = proposalRepository.find(resolvedId)
            .orElseThrow(() -> new SupplyChainResourceNotFoundException("Unknown action proposal: " + resolvedId));
        if (proposal.status() == ProposalStatus.PENDING && !proposal.expiresAt().isAfter(Instant.now())) {
            proposalRepository.markExpired(resolvedId);
            return proposalRepository.find(resolvedId).orElse(proposal);
        }
        return proposal;
    }

    public List<DecisionProposal> proposals(String taskId) {
        String resolvedTaskId = requireText(taskId, "taskId");
        taskRepository.findTask(resolvedTaskId)
            .orElseThrow(() -> new SupplyChainResourceNotFoundException("Unknown analysis task: " + resolvedTaskId));
        return proposalRepository.findByTask(resolvedTaskId);
    }

    public DecisionProposal confirm(String proposalId, String contentHash) {
        DecisionProposal proposal = proposal(proposalId);
        requireHash(proposal, contentHash);
        if (proposal.status() == ProposalStatus.CONFIRMED) {
            return proposal;
        }
        if (proposal.status() != ProposalStatus.PENDING) {
            throw new IllegalStateException("Proposal is not pending confirmation: " + proposal.status());
        }
        if (!proposal.expiresAt().isAfter(Instant.now())) {
            proposalRepository.markExpired(proposal.proposalId());
            throw new IllegalStateException("Proposal has expired");
        }
        SupplyChainTaskStatus task = taskRepository.findTask(proposal.taskId())
            .orElseThrow(() -> new IllegalStateException("Proposal task no longer exists"));
        if (!"COMPLETED".equals(task.status())) {
            throw new IllegalStateException("Proposal task is not completed: " + task.status());
        }
        if (proposalRepository.claim(proposal.proposalId(), Instant.now()) != 1) {
            DecisionProposal current = proposal(proposal.proposalId());
            if (current.status() == ProposalStatus.CONFIRMED) {
                return current;
            }
            throw new IllegalStateException("Proposal is already being confirmed: " + current.status());
        }
        try {
            Map<String, Object> result = execute(proposal);
            proposalRepository.markConfirmed(proposal.proposalId(), result, Instant.now());
            eventRecorder.proposalConfirmed(proposal.taskId(), proposal.proposalId(), proposal.actionType().name(),
                    summarizeResult(result));
            return proposal(proposal.proposalId());
        }
        catch (RuntimeException exception) {
            proposalRepository.markFailed(proposal.proposalId(), failureMessage(exception));
            eventRecorder.proposalFailed(proposal.taskId(), proposal.proposalId(), exception);
            throw exception;
        }
    }

    public DecisionProposal cancel(String proposalId, String contentHash) {
        DecisionProposal proposal = proposal(proposalId);
        requireHash(proposal, contentHash);
        if (proposal.status() == ProposalStatus.CANCELLED) {
            return proposal;
        }
        if (proposal.status() != ProposalStatus.PENDING) {
            throw new IllegalStateException("Proposal is not pending confirmation: " + proposal.status());
        }
        proposalRepository.markCancelled(proposal.proposalId(), Instant.now());
        eventRecorder.proposalCancelled(proposal.taskId(), proposal.proposalId());
        return proposal(proposal.proposalId());
    }

    private Map<String, Object> execute(DecisionProposal proposal) {
        return switch (proposal.actionType()) {
            case SAVE_CANDIDATE_AS_PLAN -> saveCandidateAsPlan(proposal);
            case LINK_CANDIDATE_TO_PLAN -> linkCandidateToPlan(proposal);
            case SELECT_PLAN -> selectPlan(proposal);
            case START_SANDBOX_EXECUTION -> startSandboxExecution(proposal);
        };
    }

    private Map<String, Object> saveCandidateAsPlan(DecisionProposal proposal) {
        String comparisonId = requireParameter(proposal.parameters(), "comparisonId");
        String title = requireParameter(proposal.parameters(), "title");
        DisposalScenarioComparison comparison = comparisonService.comparison(comparisonId);
        DisposalScenarioCandidate candidate = requireCandidate(comparison, proposal.targetId());
        if (StringUtils.hasText(candidate.linkedPlanId())) {
            throw new IllegalStateException("Candidate is already linked to a disposal plan");
        }
        boolean generateExplanation = Boolean.TRUE
            .equals(proposal.parameters().get("generateExplanation"));
        DisposalPlan plan = comparisonService.saveCandidateAsPlan(comparisonId, candidate.candidateId(),
                new CreateComparisonDisposalPlanRequest(title, proposal.taskId(), generateExplanation));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planId", plan.planId());
        result.put("title", plan.title());
        result.put("status", plan.status().name());
        result.put("candidateId", candidate.candidateId());
        result.put("comparisonId", comparisonId);
        result.put("simulationId", plan.simulation() == null ? null : plan.simulation().simulationId());
        result.put("recommendationCount", plan.recommendations().size());
        result.put("realDataMutated", false);
        return result;
    }

    private Map<String, Object> linkCandidateToPlan(DecisionProposal proposal) {
        String comparisonId = requireParameter(proposal.parameters(), "comparisonId");
        String planId = requireParameter(proposal.parameters(), "planId");
        DisposalScenarioComparison comparison = comparisonService.comparison(comparisonId);
        DisposalScenarioCandidate candidate = requireCandidate(comparison, proposal.targetId());
        DisposalPlan plan = planService.plan(planId);
        if (plan.status() == DisposalPlanStatus.REJECTED) {
            throw new IllegalStateException("A rejected disposal plan cannot be linked");
        }
        DisposalScenarioComparison linked = comparisonService.linkCandidateToPlan(comparisonId,
                candidate.candidateId(), new LinkComparisonCandidatePlanRequest(planId));
        String linkedPlanId = linked.candidates()
            .stream()
            .filter(candidateView -> candidateView.candidateId().equals(candidate.candidateId()))
            .map(DisposalScenarioCandidate::linkedPlanId)
            .findFirst()
            .orElse(null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("comparisonId", comparisonId);
        result.put("candidateId", candidate.candidateId());
        result.put("planId", planId);
        result.put("linkedPlanId", linkedPlanId);
        result.put("realDataMutated", false);
        return result;
    }

    private Map<String, Object> selectPlan(DecisionProposal proposal) {
        String recommendationId = requireParameter(proposal.parameters(), "recommendationId");
        DisposalPlan plan = planService.plan(proposal.targetId());
        if (plan.status() != DisposalPlanStatus.PENDING_CONFIRMATION) {
            throw new IllegalStateException("Disposal plan is not pending confirmation: " + plan.status());
        }
        boolean belongsToPlan = plan.recommendations()
            .stream()
            .anyMatch(recommendation -> recommendation.recommendationId().equals(recommendationId));
        if (!belongsToPlan) {
            throw new IllegalArgumentException("selectedRecommendationId does not belong to this plan");
        }
        DisposalPlan selected = planService.updateStatus(plan.planId(),
                new UpdateDisposalPlanStatusRequest(DisposalPlanStatus.SELECTED, recommendationId));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planId", selected.planId());
        result.put("status", selected.status().name());
        result.put("selectedRecommendationId", selected.selectedRecommendationId());
        result.put("realDataMutated", false);
        return result;
    }

    private Map<String, Object> startSandboxExecution(DecisionProposal proposal) {
        DisposalPlan plan = planService.plan(proposal.targetId());
        SandboxExecutionEligibility eligibility = executionService.eligibility(plan.planId());
        if (!eligibility.eligible()) {
            throw new IllegalStateException("Sandbox execution is not eligible: " + eligibility.reason());
        }
        if (plan.status() != DisposalPlanStatus.SELECTED) {
            throw new IllegalStateException("Disposal plan must be SELECTED before sandbox execution");
        }
        SandboxExecutionStartResult started = executionService.start(plan.planId(),
                new CreateSandboxExecutionRequest(idempotencyKey(proposal)));
        SandboxExecution execution = started.execution();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("executionId", execution.executionId());
        result.put("planId", execution.disposalPlanId());
        result.put("status", execution.status().name());
        result.put("mode", execution.mode().name());
        result.put("actionType", execution.actionType().name());
        result.put("idempotencyKey", execution.idempotencyKey());
        result.put("created", started.created());
        result.put("stepCount", execution.steps().size());
        result.put("realDataMutated", execution.realDataMutated());
        return result;
    }

    private void validateTarget(ProposalActionType actionType, String targetId, Map<String, Object> parameters) {
        switch (actionType) {
            case SAVE_CANDIDATE_AS_PLAN -> {
                DisposalScenarioComparison comparison = comparisonService
                    .comparison(requireParameter(parameters, "comparisonId"));
                requireCandidate(comparison, targetId);
                String title = requireParameter(parameters, "title");
                if (title.length() > 200) {
                    throw new IllegalArgumentException("title must be at most 200 characters");
                }
            }
            case LINK_CANDIDATE_TO_PLAN -> {
                DisposalScenarioComparison comparison = comparisonService
                    .comparison(requireParameter(parameters, "comparisonId"));
                DisposalScenarioCandidate candidate = requireCandidate(comparison, targetId);
                DisposalPlan plan = planService.plan(requireParameter(parameters, "planId"));
                if (StringUtils.hasText(candidate.linkedPlanId())) {
                    throw new IllegalStateException("Candidate is already linked to a disposal plan");
                }
                if (plan.status() == DisposalPlanStatus.REJECTED) {
                    throw new IllegalStateException("A rejected disposal plan cannot be linked");
                }
            }
            case SELECT_PLAN -> {
                DisposalPlan plan = planService.plan(targetId);
                String recommendationId = requireParameter(parameters, "recommendationId");
                boolean belongsToPlan = plan.recommendations()
                    .stream()
                    .anyMatch(recommendation -> recommendation.recommendationId().equals(recommendationId));
                if (!belongsToPlan) {
                    throw new IllegalArgumentException("selectedRecommendationId does not belong to this plan");
                }
            }
            case START_SANDBOX_EXECUTION -> {
                DisposalPlan plan = planService.plan(targetId);
                SandboxExecutionEligibility eligibility = executionService.eligibility(plan.planId());
                if (!eligibility.eligible()) {
                    throw new IllegalStateException("Sandbox execution is not eligible: " + eligibility.reason());
                }
            }
        }
    }

    private static DisposalScenarioCandidate requireCandidate(DisposalScenarioComparison comparison,
            String candidateId) {
        return comparison.candidates()
            .stream()
            .filter(candidate -> candidate.candidateId().equals(candidateId))
            .findFirst()
            .orElseThrow(() -> new SupplyChainResourceNotFoundException(
                    "Unknown comparison candidate: " + candidateId));
    }

    private static String idempotencyKey(DecisionProposal proposal) {
        return "proposal-" + proposal.taskId() + "-" + proposal.targetId();
    }

    static String contentHash(String taskId, ProposalActionType actionType, String title, String targetId,
            Map<String, Object> parameters) {
        StringBuilder canonical = new StringBuilder();
        canonical.append(requireText(taskId, "taskId"))
            .append('\n')
            .append(actionType == null ? "" : actionType.name())
            .append('\n')
            .append(requireText(targetId, "targetId"))
            .append('\n')
            .append(requireText(title, "title"))
            .append('\n');
        new TreeMap<>(parameters == null ? Map.of() : parameters).forEach((key, value) -> canonical.append(key)
            .append('=')
            .append(value == null ? "" : String.valueOf(value))
            .append('\n'));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of()
                .formatHex(digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static void requireHash(DecisionProposal proposal, String contentHash) {
        if (!StringUtils.hasText(contentHash)
                || !MessageDigest.isEqual(proposal.contentHash().getBytes(StandardCharsets.UTF_8),
                        contentHash.trim().getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("Confirmation does not match the proposed action content");
        }
    }

    private static Map<String, Object> normalizeParameters(Map<String, Object> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        new TreeMap<>(parameters).forEach((key, value) -> {
            if (key == null || key.isBlank()) {
                return;
            }
            if (value instanceof String text) {
                normalized.put(key, text.trim());
            }
            else if (value != null) {
                normalized.put(key, value);
            }
        });
        return Map.copyOf(normalized);
    }

    private static List<String> normalizeEvidence(List<String> evidence) {
        if (evidence == null) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String item : evidence) {
            if (StringUtils.hasText(item)) {
                normalized.add(item.trim());
            }
            if (normalized.size() >= MAX_EVIDENCE_ITEMS) {
                break;
            }
        }
        return List.copyOf(normalized);
    }

    private static String requireParameter(Map<String, Object> parameters, String name) {
        Object value = parameters == null ? null : parameters.get(name);
        return requireText(value instanceof String text ? text : null, name);
    }

    private static String requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String summarizeResult(Map<String, Object> result) {
        List<String> parts = new ArrayList<>();
        for (String key : List.of("planId", "comparisonId", "candidateId", "recommendationId", "executionId",
                "status")) {
            Object value = result.get(key);
            if (value != null) {
                parts.add(key + "=" + value);
            }
        }
        return String.join(", ", parts);
    }

    private static String failureMessage(Throwable failure) {
        String message = failure.getMessage();
        String type = failure.getClass().getSimpleName();
        if (!StringUtils.hasText(message)) {
            return type;
        }
        return Objects.requireNonNullElse(type + ": " + message, type);
    }

}
