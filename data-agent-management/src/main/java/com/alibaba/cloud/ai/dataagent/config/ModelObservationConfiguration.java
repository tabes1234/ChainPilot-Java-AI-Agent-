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

import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.trace.Tracer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the {@link ObservationRegistry} consumed by dynamically created Spring AI models.
 *
 * <p>The registry itself only collects observations. Export happens through the existing
 * Langfuse OTLP pipeline in {@link OpenTelemetryConfig}: {@link OtelObservationHandler} turns
 * each observation into a span using that pipeline's tracer. When Langfuse is disabled the
 * tracer is a no-op, so the application still starts and observations stay local.
 */
@Configuration(proxyBeanMethods = false)
public class ModelObservationConfiguration {

	@Bean
	@ConditionalOnMissingBean(ObservationRegistry.class)
	public ObservationRegistry observationRegistry(Tracer langfuseTracer) {
		ObservationRegistry registry = ObservationRegistry.create();
		registry.observationConfig().observationHandler(new OtelObservationHandler(langfuseTracer));
		return registry;
	}

}
