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
package com.alibaba.cloud.ai.dataagent.config;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OtelObservationHandlerTest {

    private InMemorySpanExporter exporter;

    private SdkTracerProvider tracerProvider;

    private ObservationRegistry registry;

    @BeforeEach
    void setUp() {
        exporter = new InMemorySpanExporter();
        tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build();
        Tracer tracer = tracerProvider.get("chainpilot-observation-test");
        registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new OtelObservationHandler(tracer));
    }

    @AfterEach
    void tearDown() {
        tracerProvider.close();
    }

    @Test
    void successfulObservationExportsContextualNameStatusAndDuration() {
        Observation.createNotStarted("spring.ai.chat.client", registry)
            .contextualName("chat local-model")
            .start()
            .stop();

        SpanData span = singleSpan();
        assertEquals("chat local-model", span.getName());
        assertEquals(StatusCode.OK, span.getStatus().getStatusCode());
        assertTrue(span.getEndEpochNanos() > span.getStartEpochNanos());
    }

    @Test
    void chatModelObservationIsMarkedAsALangfuseGenerationAndKeepsAttributes() {
        ChatModelObservationContext context = ChatModelObservationContext.builder()
            .prompt(new Prompt("unexported prompt body"))
            .provider("openai")
            .build();
        context.addLowCardinalityKeyValue(KeyValue.of("gen_ai.provider.name", "openai"));

        Observation.createNotStarted("gen_ai.client.operation", () -> context, registry).start().stop();

        SpanData span = singleSpan();
        assertEquals(SpanKind.INTERNAL, span.getKind());
        assertEquals("generation", span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.type")));
        assertEquals("openai", span.getAttributes().get(AttributeKey.stringKey("gen_ai.provider.name")));
        assertFalse(span.getAttributes().asMap().toString().contains("unexported prompt body"));
    }

    @Test
    void childObservationUsesTheParentObservationSpan() {
        Observation parent = Observation.createNotStarted("spring.ai.chat.client", registry).start();
        try (Observation.Scope ignored = parent.openScope()) {
            Observation.createNotStarted("http.client.requests", registry).start().stop();
        }
        parent.stop();

        SpanData parentSpan = span("spring.ai.chat.client");
        SpanData childSpan = span("http.client.requests");
        assertEquals(parentSpan.getSpanId(), childSpan.getParentSpanId());
        assertEquals(SpanKind.INTERNAL, parentSpan.getKind());
        assertEquals(SpanKind.CLIENT, childSpan.getKind());
        assertEquals(StatusCode.OK, childSpan.getStatus().getStatusCode());
    }

    @Test
    void failedObservationExportsOnlyTheErrorType() {
        IllegalArgumentException failure = new IllegalArgumentException("secret-token-should-not-be-exported");
        Observation observation = Observation.createNotStarted("gen_ai.client.operation", registry);
        observation.error(failure);
        observation.start();
        observation.stop();

        SpanData span = singleSpan();
        assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
        assertEquals("IllegalArgumentException", span.getAttributes().get(AttributeKey.stringKey("error.type")));
        assertFalse(span.getStatus().getDescription().contains(failure.getMessage()));
        assertFalse(span.getAttributes().asMap().toString().contains(failure.getMessage()));
    }

    @Test
    void handlerWorksWithTheNoopTracerUsedWhenLangfuseIsDisabled() {
        ObservationRegistry noopRegistry = ObservationRegistry.create();
        noopRegistry.observationConfig()
            .observationHandler(new OtelObservationHandler(io.opentelemetry.api.OpenTelemetry.noop()
                .getTracer("chainpilot-noop")));

        Observation.createNotStarted("spring.ai.chat.client", noopRegistry).observe(() -> {
        });

        assertTrue(exporter.finishedSpans().isEmpty());
    }

    private SpanData singleSpan() {
        List<SpanData> spans = exporter.finishedSpans();
        assertEquals(1, spans.size(), () -> "expected one exported span but got " + spans);
        return spans.get(0);
    }

    private SpanData span(String name) {
        return exporter.finishedSpans()
            .stream()
            .filter(candidate -> name.equals(candidate.getName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No span named " + name));
    }

    private static final class InMemorySpanExporter implements SpanExporter {

        private final List<SpanData> spans = new CopyOnWriteArrayList<>();

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            this.spans.addAll(spans);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }

        List<SpanData> finishedSpans() {
            return List.copyOf(spans);
        }
    }
}
