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

import com.alibaba.cloud.ai.dataagent.supplychain.model.DecisionProposal;
import com.alibaba.cloud.ai.dataagent.supplychain.model.ProposalConfirmationRequest;
import com.alibaba.cloud.ai.dataagent.supplychain.service.ActionProposalService;
import com.alibaba.cloud.ai.dataagent.supplychain.service.SupplyChainResourceNotFoundException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.function.Supplier;

/** Human-confirmation surface for write actions proposed by the decision agent. */
@RestController
@RequestMapping("/api/supply-chain/action-proposals")
@RequiredArgsConstructor
public class SupplyChainDecisionController {

    private final ActionProposalService proposalService;

    @GetMapping
    public List<DecisionProposal> proposals(@RequestParam String taskId) {
        return duringBusinessCall(() -> proposalService.proposals(taskId));
    }

    @GetMapping("/{proposalId}")
    public DecisionProposal proposal(@PathVariable String proposalId) {
        return duringBusinessCall(() -> proposalService.proposal(proposalId));
    }

    @PostMapping("/{proposalId}/confirm")
    public DecisionProposal confirm(@PathVariable String proposalId,
            @Valid @RequestBody ProposalConfirmationRequest request) {
        return duringBusinessCall(() -> proposalService.confirm(proposalId, request.contentHash()));
    }

    @PostMapping("/{proposalId}/cancel")
    public DecisionProposal cancel(@PathVariable String proposalId,
            @Valid @RequestBody ProposalConfirmationRequest request) {
        return duringBusinessCall(() -> proposalService.cancel(proposalId, request.contentHash()));
    }

    private static <T> T duringBusinessCall(Supplier<T> action) {
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
