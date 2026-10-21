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
package com.alibaba.cloud.ai.dataagent.supplychain;

import com.alibaba.cloud.ai.dataagent.config.ModelObservationConfiguration;
import com.alibaba.cloud.ai.dataagent.evaluation.repository.EvaluationRunRepository;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/** Minimal web context for HTTP-level ChainPilot tests; production wiring stays in DataAgentApplication. */
@SpringBootApplication(scanBasePackages = "com.alibaba.cloud.ai.dataagent.supplychain")
@Import({ ModelObservationConfiguration.class, EvaluationRunRepository.class })
public class ChainPilotTestApplication {

    @Bean
    Tracer testLangfuseTracer() {
        return OpenTelemetry.noop().getTracer("chainpilot-test");
    }

}
