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
package com.alibaba.cloud.ai.dataagent.supplychain.acceptance;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;
import org.springframework.util.StringUtils;

import java.util.Set;

/**
 * Extracts only protocol metadata from a chat response or tool observation. Content,
 * reasoning text, prompts, tool arguments and tool results are deliberately never copied
 * into the diagnostics; the acceptance evidence may state that a value was present and
 * non-blank, but not what it was.
 */
final class ChatModelResponseDiagnostics {

    private ChatModelResponseDiagnostics() {
    }

    static ModelResponseDiagnostics from(Prompt request, ChatResponse response,
            Set<String> previouslyRequestedToolCallIds) {
        ToolFeedback feedback = inspectToolFeedback(request, previouslyRequestedToolCallIds);
        if (response == null) {
            return new ModelResponseDiagnostics(false, true, null, null, null, null, null, 0,
                    feedback.messages(), feedback.responses(), feedback.matchesEarlierCall(), feedback.nonBlank());
        }

        Generation result = response.getResults().isEmpty() ? null : response.getResult();
        AssistantMessage output = result == null ? null : result.getOutput();
        String finishReason = result == null || result.getMetadata() == null ? null
                : result.getMetadata().getFinishReason();

        Integer promptTokens = null;
        Integer completionTokens = null;
        Integer totalTokens = null;
        Integer reasoningTokens = null;
        ChatResponseMetadata metadata = response.getMetadata();
        if (metadata != null && metadata.getUsage() != null) {
            Usage usage = metadata.getUsage();
            promptTokens = usage.getPromptTokens();
            completionTokens = usage.getCompletionTokens();
            totalTokens = usage.getTotalTokens();
            if (usage.getNativeUsage() instanceof OpenAiApi.Usage openAiUsage
                    && openAiUsage.completionTokenDetails() != null) {
                reasoningTokens = openAiUsage.completionTokenDetails().reasoningTokens();
            }
        }

        int toolCallCount = output == null ? 0 : output.getToolCalls().size();
        boolean contentBlank = output == null || !StringUtils.hasText(output.getText());
        return new ModelResponseDiagnostics(true, contentBlank, finishReason, promptTokens, completionTokens,
                totalTokens, reasoningTokens, toolCallCount, feedback.messages(), feedback.responses(),
                feedback.matchesEarlierCall(), feedback.nonBlank());
    }

    static void rememberToolCallIds(ChatResponse response, Set<String> ids) {
        if (response == null) {
            return;
        }
        for (Generation generation : response.getResults()) {
            AssistantMessage output = generation.getOutput();
            if (output == null) {
                continue;
            }
            for (AssistantMessage.ToolCall toolCall : output.getToolCalls()) {
                if (StringUtils.hasText(toolCall.id())) {
                    ids.add(toolCall.id());
                }
            }
        }
    }

    static ToolExecutionDiagnostics from(ToolCallingObservationContext context) {
        String toolName = context.getToolDefinition() == null ? "unknown" : context.getToolDefinition().name();
        String result = context.getToolCallResult();
        return new ToolExecutionDiagnostics(toolName, result != null, result == null || result.isBlank(),
                result == null ? 0 : result.length());
    }

    private static ToolFeedback inspectToolFeedback(Prompt request, Set<String> previouslyRequestedToolCallIds) {
        int messages = 0;
        int responses = 0;
        boolean matchesEarlierCall = false;
        boolean nonBlank = false;
        if (request == null) {
            return new ToolFeedback(messages, responses, matchesEarlierCall, nonBlank);
        }
        for (Message message : request.getInstructions()) {
            if (message instanceof ToolResponseMessage toolResponseMessage) {
                messages++;
                for (ToolResponseMessage.ToolResponse toolResponse : toolResponseMessage.getResponses()) {
                    responses++;
                    matchesEarlierCall |= previouslyRequestedToolCallIds.contains(toolResponse.id());
                    nonBlank |= StringUtils.hasText(toolResponse.responseData());
                }
            }
        }
        return new ToolFeedback(messages, responses, matchesEarlierCall, nonBlank);
    }

    record ModelResponseDiagnostics(boolean responsePresent, boolean contentBlank, String finishReason,
            Integer promptTokens, Integer completionTokens, Integer totalTokens, Integer reasoningTokens,
            int toolCallCount, int toolResponseMessages, int toolResponses, boolean toolResultMatchesEarlierCall,
            boolean toolResultNonBlank) {

        String summary() {
            return "response=" + responsePresent + ", contentBlank=" + contentBlank
                    + ", finish=" + (finishReason == null ? "n/a" : finishReason)
                    + ", tokens=" + value(promptTokens) + "/" + value(completionTokens) + "/" + value(totalTokens)
                    + ", reasoning=" + value(reasoningTokens) + ", toolCalls=" + toolCallCount
                    + ", toolResponses=" + toolResponses + " in " + toolResponseMessages + " message(s)"
                    + ", feedback=" + toolResultMatchesEarlierCall
                    + ", toolResultNonBlank=" + toolResultNonBlank;
        }

        private static String value(Integer value) {
            return value == null ? "n/a" : String.valueOf(value);
        }

    }

    record ToolExecutionDiagnostics(String toolName, boolean resultPresent, boolean resultBlank, int resultChars) {

        String summary() {
            return "tool=" + toolName + ", resultPresent=" + resultPresent + ", resultBlank=" + resultBlank
                    + ", resultChars=" + resultChars;
        }

    }

    private record ToolFeedback(int messages, int responses, boolean matchesEarlierCall, boolean nonBlank) {
    }

}
