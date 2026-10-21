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

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.metadata.DefaultToolMetadata;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatModelResponseDiagnosticsTest {

    private static final String ANSWER_CONTENT = "SECRET-ANSWER-CONTENT";

    private static final String TOOL_RESULT = "SECRET-TOOL-RESULT-SKU-25";

    @Test
    void reportsBlankContentAndTokenBudgetWithoutCopyingContent() {
        Prompt request = new Prompt(List.of(new SystemMessage("system"),
                toolResponse("call-1", "{\"sku\":\"SECRET-SKU\",\"availableToPromise\":25}")));

        ChatModelResponseDiagnostics.ModelResponseDiagnostics diagnostics = ChatModelResponseDiagnostics.from(
                request, response("   ", "length", 0, 90), Set.of("call-1"));

        assertTrue(diagnostics.responsePresent());
        assertTrue(diagnostics.contentBlank());
        assertEquals("length", diagnostics.finishReason());
        assertEquals(10, diagnostics.promptTokens());
        assertEquals(90, diagnostics.completionTokens());
        assertEquals(100, diagnostics.totalTokens());
        assertEquals(80, diagnostics.reasoningTokens());
        assertEquals(1, diagnostics.toolResponseMessages());
        assertEquals(1, diagnostics.toolResponses());
        assertTrue(diagnostics.toolResultMatchesEarlierCall());
        assertTrue(diagnostics.toolResultNonBlank());

        String summary = diagnostics.summary();
        assertTrue(summary.contains("finish=length"));
        assertTrue(summary.contains("reasoning=80"));
        assertFalse(summary.contains("SECRET-SKU"));
        assertFalse(summary.contains("availableToPromise"));
        assertFalse(summary.contains(ANSWER_CONTENT));
    }

    @Test
    void reportsToolCallsAndRemembersTheirIdsForFollowUpFeedback() {
        ChatResponse toolCallResponse = response(ANSWER_CONTENT, "tool_calls", 1, 20);
        Set<String> requestedIds = new LinkedHashSet<>();

        ChatModelResponseDiagnostics.rememberToolCallIds(toolCallResponse, requestedIds);
        ChatModelResponseDiagnostics.ModelResponseDiagnostics toolCallDiagnostics = ChatModelResponseDiagnostics
            .from(new Prompt("question"), toolCallResponse, requestedIds);

        assertEquals(Set.of("call-9"), requestedIds);
        assertFalse(toolCallDiagnostics.contentBlank());
        assertEquals("tool_calls", toolCallDiagnostics.finishReason());
        assertEquals(1, toolCallDiagnostics.toolCallCount());
        assertFalse(toolCallDiagnostics.toolResultMatchesEarlierCall());

        Prompt followUp = new Prompt(List.of(toolResponse("call-9", TOOL_RESULT)));
        ChatModelResponseDiagnostics.ModelResponseDiagnostics followUpDiagnostics = ChatModelResponseDiagnostics
            .from(followUp, response("final", "stop", 0, 30), requestedIds);

        assertTrue(followUpDiagnostics.toolResultMatchesEarlierCall());
        assertTrue(followUpDiagnostics.toolResultNonBlank());
        assertEquals(1, followUpDiagnostics.toolResponses());
        assertFalse(followUpDiagnostics.summary().contains(TOOL_RESULT));
    }

    @Test
    void reportsMissingResponseWithoutInventingContentOrFeedback() {
        ChatModelResponseDiagnostics.ModelResponseDiagnostics diagnostics = ChatModelResponseDiagnostics.from(
                new Prompt(List.of(toolResponse("call-x", TOOL_RESULT))), null, Set.of("call-1"));

        assertFalse(diagnostics.responsePresent());
        assertTrue(diagnostics.contentBlank());
        assertNull(diagnostics.finishReason());
        assertNull(diagnostics.reasoningTokens());
        assertEquals(1, diagnostics.toolResponses());
        assertFalse(diagnostics.toolResultMatchesEarlierCall());
        assertFalse(diagnostics.summary().contains(TOOL_RESULT));
    }

    @Test
    void toolDiagnosticsKeepOnlyPresenceAndSize() {
        ToolCallingObservationContext context = ToolCallingObservationContext.builder()
            .toolDefinition(DefaultToolDefinition.builder()
                .name("findInventory")
                .description("inventory")
                .inputSchema("{}")
                .build())
            .toolMetadata(DefaultToolMetadata.builder().returnDirect(false).build())
            .toolCallArguments("{\"sku\":\"SECRET-SKU\"}")
            .toolCallResult(TOOL_RESULT)
            .build();

        ChatModelResponseDiagnostics.ToolExecutionDiagnostics diagnostics = ChatModelResponseDiagnostics
            .from(context);

        assertEquals("findInventory", diagnostics.toolName());
        assertTrue(diagnostics.resultPresent());
        assertFalse(diagnostics.resultBlank());
        assertEquals(TOOL_RESULT.length(), diagnostics.resultChars());
        assertFalse(diagnostics.summary().contains(TOOL_RESULT));
        assertFalse(diagnostics.summary().contains("SECRET-SKU"));
    }

    private static ToolResponseMessage toolResponse(String id, String body) {
        return ToolResponseMessage.builder()
            .responses(List.of(new ToolResponseMessage.ToolResponse(id, "findInventory", body)))
            .build();
    }

    private static ChatResponse response(String content, String finishReason, int toolCalls, int completionTokens) {
        AssistantMessage.Builder message = AssistantMessage.builder().content(content);
        if (toolCalls > 0) {
            message.toolCalls(List.of(new AssistantMessage.ToolCall("call-9", "function", "findInventory",
                    "{\"sku\":\"SECRET-SKU\"}")));
        }
        OpenAiApi.Usage nativeUsage = new OpenAiApi.Usage(10, completionTokens, 10 + completionTokens, null,
                new OpenAiApi.Usage.CompletionTokenDetails(Math.max(0, completionTokens - 10), 0, 0, 0));
        return ChatResponse.builder()
            .generations(List.of(new Generation(message.build(),
                    ChatGenerationMetadata.builder().finishReason(finishReason).build())))
            .metadata(ChatResponseMetadata.builder()
                .usage(new DefaultUsage(10, completionTokens, 10 + completionTokens, nativeUsage))
                .build())
            .build();
    }

}
