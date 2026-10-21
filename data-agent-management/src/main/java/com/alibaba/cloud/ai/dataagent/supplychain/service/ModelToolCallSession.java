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
package com.alibaba.cloud.ai.dataagent.supplychain.service;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Wraps the real Spring AI tool callbacks used by a single model invocation so the report can
 * state which tools the model actually requested. The wrapper sits at the same boundary
 * {@code DefaultToolCallingManager} invokes, therefore a recorded name always means Spring AI
 * executed that Java tool for the model. Workflow nodes use separate methods and never pass
 * through these callbacks.
 */
final class ModelToolCallSession {

    private final List<String> calls = new CopyOnWriteArrayList<>();

    private final ToolCallback[] callbacks;

    private final ObservationRegistry observationRegistry;

    private ModelToolCallSession(ToolCallback[] delegates, ObservationRegistry observationRegistry) {
        this.observationRegistry = observationRegistry;
        this.callbacks = Arrays.stream(delegates).map(this::wrap).toArray(ToolCallback[]::new);
    }

    static ModelToolCallSession open(Object toolObject, ObservationRegistry observationRegistry) {
        ToolCallback[] delegates = MethodToolCallbackProvider.builder()
                .toolObjects(toolObject)
                .build()
                .getToolCallbacks();
        return new ModelToolCallSession(delegates, observationRegistry);
    }

    ToolCallback[] callbacks() {
        return callbacks.clone();
    }

    List<String> calls() {
        return List.copyOf(calls);
    }

    private ToolCallback wrap(ToolCallback delegate) {
        return new ToolCallback() {

            @Override
            public ToolDefinition getToolDefinition() {
                return delegate.getToolDefinition();
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return delegate.getToolMetadata();
            }

            @Override
            public String call(String toolInput) {
                return recordAndDelegate(delegate, toolInput, null);
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                return recordAndDelegate(delegate, toolInput, toolContext);
            }
        };
    }

    private String recordAndDelegate(ToolCallback delegate, String toolInput, ToolContext toolContext) {
        calls.add(delegate.getToolDefinition().name());
        try {
            return delegate.call(toolInput, toolContext);
        }
        catch (RuntimeException exception) {
            markCurrentToolObservationFailed(exception);
            throw exception;
        }
    }

    /**
     * Spring AI's default exception processor converts a tool failure into a normal tool
     * response so the model can react to it. Marking the current tool observation here keeps
     * that behavior while still reporting the execution as failed to observers.
     */
    private void markCurrentToolObservationFailed(Throwable failure) {
        Observation current = observationRegistry.getCurrentObservation();
        if (current != null && current.getContext() instanceof ToolCallingObservationContext) {
            current.error(failure);
        }
    }
}
