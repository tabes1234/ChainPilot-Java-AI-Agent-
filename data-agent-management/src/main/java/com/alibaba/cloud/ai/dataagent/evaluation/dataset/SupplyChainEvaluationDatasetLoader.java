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
package com.alibaba.cloud.ai.dataagent.evaluation.dataset;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

/** Loads the versioned deterministic dataset packaged for the evaluation center. */
@Component
public class SupplyChainEvaluationDatasetLoader {

    private static final String RESOURCE = "evaluation/supply-chain-cases.json";

    private final SupplyChainEvaluationDataset dataset;

    public SupplyChainEvaluationDatasetLoader(ObjectMapper mapper) {
        ClassPathResource resource = new ClassPathResource(RESOURCE);
        try (InputStream input = resource.getInputStream()) {
            this.dataset = mapper.readValue(input, SupplyChainEvaluationDataset.class);
        }
        catch (IOException exception) {
            throw new IllegalStateException("Unable to load evaluation dataset " + RESOURCE, exception);
        }
    }

    public SupplyChainEvaluationDataset dataset() {
        return dataset;
    }

}
