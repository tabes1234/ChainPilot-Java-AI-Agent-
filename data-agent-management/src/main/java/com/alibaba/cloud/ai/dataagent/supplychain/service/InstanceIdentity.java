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
package com.alibaba.cloud.ai.dataagent.supplychain.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.UUID;

/** Unique identity for one running application instance. */
@Component
public class InstanceIdentity {

    private final String id;

    public InstanceIdentity(@Value("${chainpilot.instance-id:}") String configuredId) {
        this.id = StringUtils.hasText(configuredId) ? configuredId.trim() : UUID.randomUUID().toString();
    }

    public String id() {
        return id;
    }
}
