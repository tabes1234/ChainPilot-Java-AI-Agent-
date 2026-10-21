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

import com.alibaba.cloud.ai.dataagent.supplychain.model.ObservabilityRunDetail;
import com.alibaba.cloud.ai.dataagent.supplychain.model.ObservabilityRunPage;
import com.alibaba.cloud.ai.dataagent.supplychain.model.ObservabilitySummary;
import com.alibaba.cloud.ai.dataagent.supplychain.service.SupplyChainObservabilityService;
import com.alibaba.cloud.ai.dataagent.supplychain.service.SupplyChainResourceNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.Set;

/**
 * Read-only execution observability. All data comes from persisted tasks, events and
 * existing evaluation/plan records; this controller never mutates business data.
 */
@RestController
@RequestMapping("/api/supply-chain/observability")
public class SupplyChainObservabilityController {

    private static final int DEFAULT_PAGE_SIZE = 8;

    private static final int MAX_PAGE_SIZE = 50;

    private static final Set<String> STATUSES = Set.of("ACCEPTED", "RUNNING", "COMPLETED", "FAILED");

    private static final Set<String> MODES = Set.of("ANALYSIS", "DECISION");

    private static final Set<String> NARRATIVE_SOURCES = Set.of("MODEL_VERIFIED",
            "MODEL_LEGACY_UNVERIFIED", "DETERMINISTIC_FALLBACK", "UNKNOWN");

    private final SupplyChainObservabilityService observabilityService;

    public SupplyChainObservabilityController(SupplyChainObservabilityService observabilityService) {
        this.observabilityService = observabilityService;
    }

    @GetMapping("/summary")
    public ObservabilitySummary summary() {
        return observabilityService.summary();
    }

    @GetMapping("/runs")
    public ResponseEntity<ObservabilityRunPage> runs(@RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize, @RequestParam(required = false) String status,
            @RequestParam(required = false) String mode, @RequestParam(required = false) String narrativeSource,
            @RequestParam(required = false) Boolean fallback, @RequestParam(required = false) String failureType,
            @RequestParam(required = false) String query) {
        int resolvedPage = page == null ? 1 : page;
        int resolvedPageSize = pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
        if (resolvedPageSize < 1 || resolvedPageSize > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "pageSize must be between 1 and " + MAX_PAGE_SIZE);
        }
        String resolvedStatus = enumValue(status, STATUSES, "status");
        String resolvedMode = enumValue(mode, MODES, "mode");
        String resolvedSource = enumValue(narrativeSource, NARRATIVE_SOURCES, "narrativeSource");
        if (failureType != null && failureType.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "failureType must be at most 64 characters");
        }
        if (query != null && query.length() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query must be at most 200 characters");
        }
        ObservabilityRunPage result = observabilityService.runs(resolvedPage, resolvedPageSize, resolvedStatus,
                resolvedMode, resolvedSource, fallback, failureType, query);
        return ResponseEntity.ok()
                .header("X-Total-Count", Long.toString(result.totalElements()))
                .header("X-Page", Integer.toString(result.page()))
                .header("X-Page-Size", Integer.toString(result.pageSize()))
                .header("X-Total-Pages", Integer.toString(result.totalPages()))
                .body(result);
    }

    @GetMapping("/runs/{taskId}")
    public ObservabilityRunDetail detail(@PathVariable String taskId) {
        try {
            return observabilityService.detail(taskId);
        }
        catch (SupplyChainResourceNotFoundException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
        catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    private static String enumValue(String value, Set<String> allowed, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    name + " must be one of " + String.join(", ", allowed));
        }
        return normalized;
    }
}
