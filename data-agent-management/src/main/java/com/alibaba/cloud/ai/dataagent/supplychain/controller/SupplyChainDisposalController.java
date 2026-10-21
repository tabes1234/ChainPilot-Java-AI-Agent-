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
package com.alibaba.cloud.ai.dataagent.supplychain.controller;

import com.alibaba.cloud.ai.dataagent.supplychain.model.CreateComparisonDisposalPlanRequest;
import com.alibaba.cloud.ai.dataagent.supplychain.model.CreateDisposalPlanRequest;
import com.alibaba.cloud.ai.dataagent.supplychain.model.CreateDisposalScenarioComparisonRequest;
import com.alibaba.cloud.ai.dataagent.supplychain.model.CreateSandboxExecutionRequest;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalPlan;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalPlanPage;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalPlanStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalRecommendation;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioComparison;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalScenarioComparisonPage;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalSimulation;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalSimulationSummary;
import com.alibaba.cloud.ai.dataagent.supplychain.model.LinkComparisonCandidatePlanRequest;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxExecution;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxExecutionEligibility;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxExecutionPage;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxExecutionStartResult;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SandboxExecutionStep;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SimulationRequest;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SimulationBaselineView;
import com.alibaba.cloud.ai.dataagent.supplychain.model.UpdateDisposalPlanStatusRequest;
import com.alibaba.cloud.ai.dataagent.supplychain.service.DisposalExecutionService;
import com.alibaba.cloud.ai.dataagent.supplychain.service.DisposalPlanService;
import com.alibaba.cloud.ai.dataagent.supplychain.service.DisposalRecommendationService;
import com.alibaba.cloud.ai.dataagent.supplychain.service.DisposalScenarioComparisonService;
import com.alibaba.cloud.ai.dataagent.supplychain.service.DisposalSimulationService;
import com.alibaba.cloud.ai.dataagent.supplychain.service.SupplyChainResourceNotFoundException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;

/** REST surface for deterministic disposal advice, what-if simulation and persisted plans. */
@RestController
@RequestMapping("/api/supply-chain")
@RequiredArgsConstructor
public class SupplyChainDisposalController {

    private static final int DEFAULT_HISTORY_LIMIT = 20;

    private static final int DEFAULT_PAGE_SIZE = 10;

    private static final int MAX_PAGE_SIZE = 50;

    private final DisposalRecommendationService recommendationService;

    private final DisposalSimulationService simulationService;

    private final DisposalPlanService planService;

    private final DisposalExecutionService executionService;

    private final DisposalScenarioComparisonService comparisonService;

    @GetMapping("/disposal-recommendations")
    public List<DisposalRecommendation> recommendations(@RequestParam(required = false) String sku,
            @RequestParam(required = false) String supplierId) {
        return duringBusinessCall(() -> recommendationService.generate(sku, supplierId));
    }

    @PostMapping("/disposal-simulations")
    public ResponseEntity<DisposalSimulation> simulate(@Valid @RequestBody SimulationRequest request) {
        DisposalSimulation simulation = duringBusinessCall(() -> simulationService.simulateAndSave(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(simulation);
    }

    @GetMapping("/disposal-simulations")
    public List<DisposalSimulationSummary> simulationHistory(@RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String planId) {
        int resolvedLimit = limit == null ? DEFAULT_HISTORY_LIMIT : limit;
        return duringBusinessCall(() -> simulationService.history(resolvedLimit, normalize(planId)));
    }

    @GetMapping("/disposal-simulations/baseline")
    public SimulationBaselineView simulationBaseline() {
        return duringBusinessCall(simulationService::baseline);
    }

    @GetMapping("/disposal-simulations/{simulationId}")
    public DisposalSimulation simulation(@PathVariable String simulationId) {
        return duringBusinessCall(() -> simulationService.findSimulation(simulationId));
    }

    @PostMapping("/disposal-plans")
    public ResponseEntity<DisposalPlan> createPlan(@Valid @RequestBody CreateDisposalPlanRequest request) {
        DisposalPlan plan = duringBusinessCall(() -> planService.create(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(plan);
    }

    @GetMapping("/disposal-plans")
    public ResponseEntity<DisposalPlanPage> plans(@RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize) {
        int resolvedPage = page == null ? 1 : page;
        int resolvedPageSize = pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
        if (resolvedPageSize < 1 || resolvedPageSize > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "pageSize must be between 1 and " + MAX_PAGE_SIZE);
        }
        DisposalPlanStatus resolvedStatus = resolveStatus(status);
        DisposalPlanPage result = duringBusinessCall(
                () -> planService.plans(resolvedStatus, resolvedPage, resolvedPageSize));
        return ResponseEntity.ok()
                .header("X-Total-Count", Long.toString(result.totalElements()))
                .header("X-Page", Integer.toString(result.page()))
                .header("X-Page-Size", Integer.toString(result.pageSize()))
                .header("X-Total-Pages", Integer.toString(result.totalPages()))
                .body(result);
    }

    @GetMapping("/disposal-plans/{planId}")
    public DisposalPlan plan(@PathVariable String planId) {
        return duringBusinessCall(() -> planService.plan(planId));
    }

    @GetMapping("/disposal-plans/{planId}/simulations")
    public List<DisposalSimulationSummary> planSimulations(@PathVariable String planId,
            @RequestParam(required = false) Integer limit) {
        int resolvedLimit = limit == null ? DEFAULT_HISTORY_LIMIT : limit;
        return duringBusinessCall(() -> {
            planService.plan(planId);
            return simulationService.history(resolvedLimit, planId);
        });
    }

    @PatchMapping("/disposal-plans/{planId}/status")
    public DisposalPlan updatePlanStatus(@PathVariable String planId,
            @Valid @RequestBody UpdateDisposalPlanStatusRequest request) {
        return duringBusinessCall(() -> planService.updateStatus(planId, request));
    }

    @PostMapping("/disposal-scenario-comparisons")
    public ResponseEntity<DisposalScenarioComparison> compareScenarios(
            @Valid @RequestBody CreateDisposalScenarioComparisonRequest request) {
        DisposalScenarioComparison comparison = duringBusinessCall(() -> comparisonService.compare(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(comparison);
    }

    @GetMapping("/disposal-scenario-comparisons")
    public ResponseEntity<DisposalScenarioComparisonPage> comparisons(
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize) {
        int resolvedPage = page == null ? 1 : page;
        int resolvedPageSize = pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
        if (resolvedPageSize < 1 || resolvedPageSize > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "pageSize must be between 1 and " + MAX_PAGE_SIZE);
        }
        DisposalScenarioComparisonPage result = duringBusinessCall(
                () -> comparisonService.comparisons(resolvedPage, resolvedPageSize));
        return ResponseEntity.ok()
                .header("X-Total-Count", Long.toString(result.totalElements()))
                .header("X-Page", Integer.toString(result.page()))
                .header("X-Page-Size", Integer.toString(result.pageSize()))
                .header("X-Total-Pages", Integer.toString(result.totalPages()))
                .body(result);
    }

    @GetMapping("/disposal-scenario-comparisons/{comparisonId}")
    public DisposalScenarioComparison comparison(@PathVariable String comparisonId) {
        return duringBusinessCall(() -> comparisonService.comparison(comparisonId));
    }

    @PostMapping("/disposal-scenario-comparisons/{comparisonId}/candidates/{candidateId}/disposal-plans")
    public ResponseEntity<DisposalPlan> saveCandidateAsPlan(@PathVariable String comparisonId,
            @PathVariable String candidateId,
            @Valid @RequestBody CreateComparisonDisposalPlanRequest request) {
        DisposalPlan plan = duringBusinessCall(
                () -> comparisonService.saveCandidateAsPlan(comparisonId, candidateId, request));
        return ResponseEntity.status(HttpStatus.CREATED).body(plan);
    }

    @PatchMapping("/disposal-scenario-comparisons/{comparisonId}/candidates/{candidateId}/plan")
    public DisposalScenarioComparison linkCandidatePlan(@PathVariable String comparisonId,
            @PathVariable String candidateId,
            @Valid @RequestBody LinkComparisonCandidatePlanRequest request) {
        return duringBusinessCall(
                () -> comparisonService.linkCandidateToPlan(comparisonId, candidateId, request));
    }

    @GetMapping("/disposal-plans/{planId}/execution-eligibility")
    public SandboxExecutionEligibility executionEligibility(@PathVariable String planId) {
        return duringBusinessCall(() -> executionService.eligibility(planId));
    }

    @PostMapping("/disposal-plans/{planId}/executions")
    public ResponseEntity<SandboxExecution> startSandboxExecution(@PathVariable String planId,
            @Valid @RequestBody CreateSandboxExecutionRequest request) {
        SandboxExecutionStartResult result = duringBusinessCall(
                () -> executionService.start(planId, request));
        if (result.created()) {
            return ResponseEntity.status(HttpStatus.CREATED).body(result.execution());
        }
        return ResponseEntity.ok().header("X-Idempotent-Replay", "true").body(result.execution());
    }

    @GetMapping("/disposal-plans/{planId}/executions")
    public ResponseEntity<SandboxExecutionPage> sandboxExecutions(@PathVariable String planId,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize) {
        int resolvedPage = page == null ? 1 : page;
        int resolvedPageSize = pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
        if (resolvedPageSize < 1 || resolvedPageSize > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "pageSize must be between 1 and " + MAX_PAGE_SIZE);
        }
        SandboxExecutionPage result = duringBusinessCall(
                () -> executionService.executions(planId, resolvedPage, resolvedPageSize));
        return ResponseEntity.ok()
                .header("X-Total-Count", Long.toString(result.totalElements()))
                .header("X-Page", Integer.toString(result.page()))
                .header("X-Page-Size", Integer.toString(result.pageSize()))
                .header("X-Total-Pages", Integer.toString(result.totalPages()))
                .body(result);
    }

    @GetMapping("/sandbox-executions/{executionId}")
    public SandboxExecution sandboxExecution(@PathVariable String executionId) {
        return duringBusinessCall(() -> executionService.execution(executionId));
    }

    @GetMapping("/sandbox-executions/{executionId}/steps")
    public List<SandboxExecutionStep> sandboxExecutionSteps(@PathVariable String executionId) {
        return duringBusinessCall(() -> executionService.steps(executionId));
    }

    private static DisposalPlanStatus resolveStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return DisposalPlanStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "status must be one of PENDING_CONFIRMATION, SELECTED, REJECTED", exception);
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static <T> T duringBusinessCall(java.util.function.Supplier<T> action) {
        try {
            return action.get();
        }
        catch (SupplyChainResourceNotFoundException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
        catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
        catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage(), exception);
        }
    }

}
