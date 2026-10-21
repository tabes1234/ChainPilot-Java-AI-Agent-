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
package com.alibaba.cloud.ai.dataagent.supplychain.controller;

import com.alibaba.cloud.ai.dataagent.supplychain.model.DecisionTaskResult;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskAnalysisReport;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskAnalysisRequest;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskEvent;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskPage;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskState;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskSummary;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskType;
import com.alibaba.cloud.ai.dataagent.supplychain.service.SupplyChainWorkflowService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/supply-chain/analysis")
@RequiredArgsConstructor
public class SupplyChainAnalysisController {

    private static final int DEFAULT_PAGE_SIZE = 10;

    private static final int MAX_PAGE_SIZE = 50;

    private static final String SUPPORTED_STATUSES = String.join(", ",
            SupplyChainTaskState.ALL.stream().sorted().toList());

    private final SupplyChainWorkflowService workflowService;

    @PostMapping
    public Mono<RiskAnalysisReport> analyze(@Valid @RequestBody RiskAnalysisRequest request) {
        requireAnalysisMode(request.mode());
        return Mono.fromCallable(() -> workflowService.analyze(request.question()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/tasks")
    public SupplyChainTaskStatus submit(@Valid @RequestBody RiskAnalysisRequest request) {
        try {
            return workflowService.submit(request.question(), request.mode());
        }
        catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
        catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Analysis queue is unavailable; retry later", exception);
        }
    }

    /**
     * The body remains a JSON array so the existing demo page keeps working unchanged. Paging
     * metadata is returned in {@code X-Total-Count}, {@code X-Page}, {@code X-Page-Size} and
     * {@code X-Total-Pages}. {@code limit} is kept as the page-size alias for page 1; when both
     * {@code pageSize} and {@code limit} are supplied, {@code pageSize} takes precedence.
     */
    @GetMapping("/tasks")
    public ResponseEntity<List<SupplyChainTaskSummary>> tasks(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String mode) {
        validatePageSize("limit", limit);
        validatePageSize("pageSize", pageSize);
        int resolvedPage = page == null ? 1 : page;
        if (resolvedPage < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be at least 1");
        }
        int resolvedPageSize = pageSize != null ? pageSize : (limit != null ? limit : DEFAULT_PAGE_SIZE);
        String resolvedStatus = resolveStatus(status);
        String resolvedMode = resolveMode(mode);
        SupplyChainTaskPage taskPage = workflowService.tasks(resolvedMode, resolvedStatus, resolvedPage,
                resolvedPageSize);
        return ResponseEntity.ok()
                .header("X-Total-Count", Long.toString(taskPage.totalElements()))
                .header("X-Page", Integer.toString(taskPage.page()))
                .header("X-Page-Size", Integer.toString(taskPage.pageSize()))
                .header("X-Total-Pages", Integer.toString(taskPage.totalPages()))
                .body(taskPage.items());
    }

    private static void validatePageSize(String parameter, Integer value) {
        if (value != null && (value < 1 || value > MAX_PAGE_SIZE)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    parameter + " must be between 1 and " + MAX_PAGE_SIZE);
        }
    }

    private static String resolveStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!SupplyChainTaskState.ALL.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "status must be one of " + SUPPORTED_STATUSES);
        }
        return normalized;
    }

    private static String resolveMode(String mode) {
        try {
            return SupplyChainTaskType.normalize(mode);
        }
        catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "mode must be one of ANALYSIS, DECISION", exception);
        }
    }

    private static void requireAnalysisMode(String mode) {
        if (mode != null && !mode.isBlank() && !SupplyChainTaskType.ANALYSIS.equals(
                resolveMode(mode))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Synchronous analysis only supports mode=ANALYSIS; use POST /tasks with mode=DECISION");
        }
    }

    @GetMapping("/tasks/{taskId}")
    public SupplyChainTaskStatus task(@PathVariable String taskId) {
        try {
            return workflowService.task(taskId);
        }
        catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Analysis task not found", exception);
        }
    }

    @PostMapping("/tasks/{taskId}/cancel")
    public SupplyChainTaskStatus cancel(@PathVariable String taskId) {
        try {
            return workflowService.cancel(taskId);
        }
        catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Analysis task not found", exception);
        }
    }

    @GetMapping("/tasks/{taskId}/events")
    public List<SupplyChainTaskEvent> events(@PathVariable String taskId) {
        try {
            return workflowService.events(taskId);
        }
        catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Analysis task not found", exception);
        }
    }

    @GetMapping("/tasks/{taskId}/report")
    public RiskAnalysisReport report(@PathVariable String taskId) {
        try {
            return workflowService.report(taskId);
        }
        catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Analysis task not found", exception);
        }
        catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Analysis task has not completed", exception);
        }
    }

    @GetMapping("/tasks/{taskId}/decision")
    public DecisionTaskResult decision(@PathVariable String taskId) {
        try {
            return workflowService.decision(taskId);
        }
        catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Decision task not found", exception);
        }
        catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Decision task has not completed", exception);
        }
    }
}
