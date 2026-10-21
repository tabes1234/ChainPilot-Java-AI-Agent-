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
package com.alibaba.cloud.ai.dataagent.supplychain.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public final class SupplyChainEvaluationDatasetLoader {

    public static final String DATASET_RESOURCE = "/supply-chain-evaluation/cases.json";

    private static final String KNOWLEDGE_PATTERN = "classpath*:knowledge/supply-chain/*.md";

    private SupplyChainEvaluationDatasetLoader() {
    }

    public static SupplyChainEvaluationDataset load(ObjectMapper mapper) throws IOException {
        try (InputStream input = SupplyChainEvaluationDatasetLoader.class.getResourceAsStream(DATASET_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing evaluation dataset resource: " + DATASET_RESOURCE);
            }
            return mapper.readValue(input, SupplyChainEvaluationDataset.class);
        }
    }

    public static Set<String> knowledgeSourceIds() throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver().getResources(KNOWLEDGE_PATTERN);
        return Arrays.stream(resources)
                .map(Resource::getFilename)
                .filter(name -> name != null && !name.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }
}
