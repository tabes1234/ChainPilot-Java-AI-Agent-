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

import com.alibaba.cloud.ai.dataagent.supplychain.service.SupplyChainResourceNotFoundException;
import com.alibaba.cloud.ai.dataagent.vo.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Human feedback and reviewed feedback-case drafts, integrated with evaluation. */
@RestController
@RequestMapping("/api/agent-evaluation/feedback")
public class FeedbackController {

    private static final int DEFAULT_PAGE_SIZE = 8;

    private final FeedbackService service;

    public FeedbackController(FeedbackService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResponse<FeedbackRecord> submit(@Valid @RequestBody FeedbackRequest request) {
        try {
            return ApiResponse.success("反馈已保存", service.submit(request));
        }
        catch (Exception exception) {
            throw translate(exception);
        }
    }

    @GetMapping
    public ApiResponse<FeedbackPage> list(@RequestParam(required = false) String feedbackType,
            @RequestParam(required = false) String resultType,
            @RequestParam(required = false) String narrativeSource, @RequestParam(required = false) String taskId,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer pageSize) {
        int resolvedPage = page == null ? 1 : page;
        int resolvedPageSize = pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
        try {
            long total = service.count(feedbackType, resultType, narrativeSource, taskId);
            List<FeedbackRecord> items = service.list(feedbackType, resultType, narrativeSource, taskId,
                    resolvedPage, resolvedPageSize);
            int totalPages = total == 0 ? 0 : (int) ((total + resolvedPageSize - 1) / resolvedPageSize);
            return ApiResponse.success("查询成功",
                    new FeedbackPage(items, resolvedPage, resolvedPageSize, total, totalPages));
        }
        catch (Exception exception) {
            throw translate(exception);
        }
    }

    @GetMapping("/stats")
    public ApiResponse<FeedbackStats> stats() {
        return ApiResponse.success("查询成功", service.stats());
    }

    @PostMapping("/{feedbackId}/drafts")
    public ApiResponse<FeedbackDraft> createDraft(@PathVariable String feedbackId) {
        try {
            return ApiResponse.success("评测案例草案已创建", service.createDraft(feedbackId));
        }
        catch (Exception exception) {
            throw translate(exception);
        }
    }

    @GetMapping("/drafts")
    public ApiResponse<List<FeedbackDraft>> drafts(@RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "8") int pageSize) {
        try {
            return ApiResponse.success("查询成功", service.drafts(page, pageSize));
        }
        catch (Exception exception) {
            throw translate(exception);
        }
    }

    @GetMapping("/drafts/{draftId}")
    public ApiResponse<FeedbackDraft> draft(@PathVariable String draftId) {
        try {
            return ApiResponse.success("查询成功", service.draft(draftId));
        }
        catch (Exception exception) {
            throw translate(exception);
        }
    }

    @PutMapping("/drafts/{draftId}")
    public ApiResponse<FeedbackDraft> updateDraft(@PathVariable String draftId,
            @Valid @RequestBody FeedbackDraftUpdateRequest request) {
        try {
            return ApiResponse.success("草案已更新", service.updateDraft(draftId, request));
        }
        catch (Exception exception) {
            throw translate(exception);
        }
    }

    @PostMapping("/drafts/{draftId}/review")
    public ApiResponse<FeedbackDraft> review(@PathVariable String draftId) {
        try {
            return ApiResponse.success("草案已提交审核", service.review(draftId));
        }
        catch (Exception exception) {
            throw translate(exception);
        }
    }

    @PostMapping("/drafts/{draftId}/enable")
    public ApiResponse<FeedbackDraft> enable(@PathVariable String draftId) {
        try {
            return ApiResponse.success("评测案例已启用", service.enable(draftId));
        }
        catch (Exception exception) {
            throw translate(exception);
        }
    }

    private static ResponseStatusException translate(Exception exception) {
        if (exception instanceof ResponseStatusException responseStatusException) {
            return responseStatusException;
        }
        if (exception instanceof SupplyChainResourceNotFoundException notFound) {
            return new ResponseStatusException(HttpStatus.NOT_FOUND, notFound.getMessage(), notFound);
        }
        if (exception instanceof IllegalArgumentException invalid) {
            return new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        }
        if (exception instanceof IllegalStateException conflict) {
            return new ResponseStatusException(HttpStatus.CONFLICT, conflict.getMessage(), conflict);
        }
        return new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "反馈处理失败", exception);
    }
}
