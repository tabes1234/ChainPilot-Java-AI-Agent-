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

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationView;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.util.StringUtils;

/**
 * Bridges Micrometer observations to the application's existing OTel {@link Tracer}.
 *
 * <p>Only observation metadata is exported: the low/high-cardinality key values provided by
 * Spring AI and Spring's HTTP client conventions. Prompt, completion, tool argument and tool
 * result content is not registered through a content filter, so it never reaches this handler.
 * Errors are reduced to the exception type to avoid exporting response bodies or credentials.
 */
public class OtelObservationHandler implements ObservationHandler<Observation.Context> {

	private static final Object SPAN_KEY = new Object();

	private static final AttributeKey<String> ERROR_TYPE = AttributeKey.stringKey("error.type");

	private static final AttributeKey<String> LANGFUSE_OBSERVATION_TYPE = AttributeKey
		.stringKey("langfuse.observation.type");

	private static final String GENERATION = "generation";

	private static final String HTTP_CLIENT_PREFIX = "http.client";

	private final Tracer tracer;

	public OtelObservationHandler(Tracer tracer) {
		this.tracer = tracer;
	}

	@Override
	public void onStart(Observation.Context context) {
		String spanName = StringUtils.hasText(context.getContextualName()) ? context.getContextualName()
				: context.getName();
		SpanBuilder builder = tracer.spanBuilder(spanName).setSpanKind(spanKind(context));
		Span parent = parentSpan(context);
		builder.setParent(parent == null ? Context.current() : Context.current().with(parent));
		Span span = builder.startSpan();
		context.put(SPAN_KEY, span);
		applyAttributes(span, context);
		if (context instanceof ChatModelObservationContext) {
			span.setAttribute(LANGFUSE_OBSERVATION_TYPE, GENERATION);
		}
	}

	@Override
	public void onStop(Observation.Context context) {
		Span span = context.get(SPAN_KEY);
		if (span == null) {
			return;
		}
		try {
			applyAttributes(span, context);
			Throwable error = context.getError();
			if (error == null) {
				span.setStatus(StatusCode.OK);
			}
			else {
				String errorType = error.getClass().getSimpleName();
				span.setAttribute(ERROR_TYPE, errorType);
				span.setStatus(StatusCode.ERROR, errorType);
			}
		}
		finally {
			span.end();
		}
	}

	@Override
	public boolean supportsContext(Observation.Context context) {
		return true;
	}

	private static void applyAttributes(Span span, Observation.Context context) {
		context.getAllKeyValues().forEach(keyValue -> {
			if (keyValue.getValue() != null) {
				span.setAttribute(keyValue.getKey(), keyValue.getValue());
			}
		});
	}

	private static Span parentSpan(Observation.Context context) {
		ObservationView parent = context.getParentObservation();
		if (parent == null) {
			return null;
		}
		Object span = parent.getContextView().get(SPAN_KEY);
		return span instanceof Span parentSpan ? parentSpan : null;
	}

	private static SpanKind spanKind(Observation.Context context) {
		String name = context.getName();
		return name != null && name.startsWith(HTTP_CLIENT_PREFIX) ? SpanKind.CLIENT : SpanKind.INTERNAL;
	}

}
