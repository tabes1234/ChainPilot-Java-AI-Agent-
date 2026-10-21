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
package com.alibaba.cloud.ai.dataagent.evaluation.controller;

import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationCaseDefinition;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationCaseHistoryEntry;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationMetricsReport;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationMode;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationRun;
import com.alibaba.cloud.ai.dataagent.evaluation.model.EvaluationRunComparison;
import com.alibaba.cloud.ai.dataagent.evaluation.service.AgentEvaluationService;
import com.alibaba.cloud.ai.dataagent.evaluation.service.EvaluationComparisonService;
import com.alibaba.cloud.ai.dataagent.vo.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Agent evaluation center API.
 *
 * <p>Case definitions are read-only and versioned. Runs are persisted; AGENT runs must be
 * started explicitly and are refreshed lazily when queried.
 */
@RestController
@RequestMapping("/api/agent-evaluation")
@CrossOrigin(origins = "*")
public class AgentEvaluationController {

    private final AgentEvaluationService service;

    private final EvaluationComparisonService comparisonService;

    public AgentEvaluationController(AgentEvaluationService service, EvaluationComparisonService comparisonService) {
        this.service = service;
        this.comparisonService = comparisonService;
    }

    @GetMapping("/cases")
    public ApiResponse<List<EvaluationCaseDefinition>> cases() {
        return ApiResponse.success("查询成功", service.cases());
    }

    @GetMapping("/cases/{caseId}")
    public ApiResponse<EvaluationCaseDefinition> caseDetail(@PathVariable String caseId) {
        return ApiResponse.success("查询成功", service.caseOrThrow(caseId));
    }

    @GetMapping("/cases/{caseId}/history")
    public ApiResponse<List<EvaluationCaseHistoryEntry>> caseHistory(@PathVariable String caseId,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success("查询成功", service.caseHistory(caseId, limit));
    }

    @GetMapping("/capabilities")
    public ApiResponse<Map<String, Object>> capabilities() {
        return ApiResponse.success("查询成功", service.capabilities());
    }

    @GetMapping("/runs")
    public ApiResponse<List<EvaluationRun>> runs(@RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success("查询成功", service.runs(limit));
    }

    @GetMapping("/runs/{runId}")
    public ApiResponse<EvaluationRun> run(@PathVariable String runId) {
        return ApiResponse.success("查询成功", service.getRun(runId));
    }

    @GetMapping("/metrics")
    public ApiResponse<EvaluationMetricsReport> metrics(@RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success("查询成功", service.metrics(limit));
    }

    @GetMapping("/comparisons")
    public ApiResponse<EvaluationRunComparison> compare(@RequestParam String baseRunId,
            @RequestParam String targetRunId) {
        return ApiResponse.success("查询成功", comparisonService.compare(baseRunId, targetRunId));
    }

    @PostMapping("/runs")
    public ApiResponse<EvaluationRun> start(@RequestBody(required = false) StartRunRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
        }
        EvaluationMode mode = EvaluationMode.parse(request.mode());
        boolean confirmed = Boolean.TRUE.equals(request.confirmAgentRun());
        if (mode == EvaluationMode.AGENT && !confirmed) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "AGENT 运行需要显式确认费用与环境影响");
        }
        return ApiResponse.success("评测运行已启动", service.start(mode, request.caseIds(), confirmed));
    }

    public record StartRunRequest(List<String> caseIds, String mode, Boolean confirmAgentRun) {
    }

}
