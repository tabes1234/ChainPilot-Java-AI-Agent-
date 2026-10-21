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
package com.alibaba.cloud.ai.dataagent.supplychain.model;

import java.util.Set;

/** The task states written by the analysis workflow; history filters must stay within this set. */
public final class SupplyChainTaskState {

    public static final String ACCEPTED = "ACCEPTED";

    public static final String RUNNING = "RUNNING";

    public static final String COMPLETED = "COMPLETED";

    public static final String FAILED = "FAILED";

    public static final Set<String> ALL = Set.of(ACCEPTED, RUNNING, COMPLETED, FAILED);

    private SupplyChainTaskState() {
    }
}
