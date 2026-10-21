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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Process-local cancellation signals. A signal only prevents subsequent checkpoints from
 * running; it cannot abort an already in-flight provider HTTP request.
 */
public final class TaskCancellationRegistry {

    private static final ConcurrentMap<String, AtomicBoolean> SIGNALS = new ConcurrentHashMap<>();

    private TaskCancellationRegistry() {
    }

    public static void begin(String taskId) {
        SIGNALS.put(taskId, new AtomicBoolean(false));
    }

    public static void request(String taskId) {
        SIGNALS.computeIfAbsent(taskId, ignored -> new AtomicBoolean(false)).set(true);
    }

    public static boolean isRequested(String taskId) {
        AtomicBoolean signal = SIGNALS.get(taskId);
        return signal != null && signal.get();
    }

    public static void check(String taskId) {
        if (taskId != null && isRequested(taskId)) {
            throw new SupplyChainTaskCancelledException(taskId);
        }
    }

    public static void end(String taskId) {
        if (taskId != null) {
            SIGNALS.remove(taskId);
        }
    }

    /** Test hook; production code only ends the task that it owns. */
    static void reset() {
        SIGNALS.clear();
    }
}
