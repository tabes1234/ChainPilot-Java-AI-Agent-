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
package com.alibaba.cloud.ai.dataagent.supplychain.acceptance;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Set;

import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;

/**
 * Records model and HTTP observations for the real-model acceptance run and enforces the call
 * budgets before a request reaches the wire.
 *
 * <p>Two different counters are needed because one model stage is not one HTTP request. The
 * provider client retries {@code TransientAiException} (any 5xx) and {@code ResourceAccessException}
 * up to 10 times per stage, and every native tool-calling turn is another stage, so counting
 * {@code gen_ai.client.operation} observations alone can under-report outbound requests by an order
 * of magnitude. {@code http.client.requests} observations are created per outbound attempt, so the
 * HTTP counter bounds the actual requests, including library retries and tool follow-up turns.
 *
 * <p>Only observation names, low-cardinality attributes and response metadata are kept. Request
 * bodies, prompts, tool arguments, tool results and credentials are never recorded.
 */
public final class RecordingObservationHandler implements ObservationHandler<Observation.Context> {

    private static final Object START_NANOS = new Object();

    private static final String MODEL_OPERATION = "gen_ai.client.operation";

    private static final String HTTP_REQUEST = "http.client.requests";

    private final List<ObservationRecord> records = new CopyOnWriteArrayList<>();

    private final Set<String> requestedToolCallIds = ConcurrentHashMap.newKeySet();

    private final AtomicInteger modelStarts = new AtomicInteger();

    private final AtomicInteger modelCallLimit = new AtomicInteger(Integer.MAX_VALUE);

    private final AtomicInteger httpStarts = new AtomicInteger();

    private final AtomicInteger httpAttemptLimit = new AtomicInteger(Integer.MAX_VALUE);

    private final AtomicInteger runHttpStarts = new AtomicInteger();

    private final AtomicInteger runHttpAttemptLimit = new AtomicInteger(Integer.MAX_VALUE);

    private final AtomicBoolean budgetExceeded = new AtomicBoolean();

    private final AtomicBoolean httpBudgetExceeded = new AtomicBoolean();

    private final AtomicBoolean runBudgetExceeded = new AtomicBoolean();

    @Override
    public void onStart(Observation.Context context) {
        String name = context.getName();
        if (MODEL_OPERATION.equals(name)) {
            enforce(MODEL_OPERATION, modelStarts.incrementAndGet(), modelCallLimit.get(), budgetExceeded);
        }
        else if (HTTP_REQUEST.equals(name)) {
            // Both counters advance before either ceiling is checked, so a refused attempt is visible
            // in the scenario trail as well as in the run-wide total.
            int scenarioAttempt = httpStarts.incrementAndGet();
            int runAttempt = runHttpStarts.incrementAndGet();
            enforce("real-model acceptance run", runAttempt, runHttpAttemptLimit.get(), runBudgetExceeded);
            enforce(HTTP_REQUEST, scenarioAttempt, httpAttemptLimit.get(), httpBudgetExceeded);
        }
        context.put(START_NANOS, System.nanoTime());
    }

    /**
     * Throwing from {@code onStart} aborts the observed operation before the transport runs, so an
     * over-budget request is never sent.
     */
    private static void enforce(String boundary, int attempt, int limit, AtomicBoolean exceeded) {
        if (attempt > limit) {
            exceeded.set(true);
            throw new IllegalStateException("Real-model acceptance outbound request budget exceeded at the "
                    + boundary + " boundary: attempt=" + attempt + ", limit=" + limit
                    + ". The request was blocked before it was sent; raise the budget only after reviewing"
                    + " the recorded request trail.");
        }
    }

    @Override
    public void onStop(Observation.Context context) {
        Long started = context.get(START_NANOS);
        long ended = System.nanoTime();
        Map<String, String> attributes = new LinkedHashMap<>();
        context.getAllKeyValues()
            .forEach(keyValue -> attributes.put(keyValue.getKey(), String.valueOf(keyValue.getValue())));

        ChatModelResponseDiagnostics.ModelResponseDiagnostics model = null;
        ChatModelResponseDiagnostics.ToolExecutionDiagnostics tool = null;
        if (context instanceof ChatModelObservationContext chatContext) {
            model = ChatModelResponseDiagnostics.from(chatContext.getRequest(), chatContext.getResponse(),
                    requestedToolCallIds);
            ChatModelResponseDiagnostics.rememberToolCallIds(chatContext.getResponse(), requestedToolCallIds);
        }
        else if (context instanceof ToolCallingObservationContext toolContext) {
            tool = ChatModelResponseDiagnostics.from(toolContext);
        }

        records.add(new ObservationRecord(context.getName(),
                context.getError() == null ? null : context.getError().getClass().getSimpleName(),
                started == null ? -1L : ended - started, Map.copyOf(attributes), model, tool));
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return true;
    }

    /** Clears the per-scenario state; the run-wide request ceiling deliberately survives. */
    public void reset() {
        records.clear();
        requestedToolCallIds.clear();
        modelStarts.set(0);
        httpStarts.set(0);
        budgetExceeded.set(false);
        httpBudgetExceeded.set(false);
    }

    public void limitModelCalls(int limit) {
        modelCallLimit.set(limit);
    }

    public void limitHttpAttempts(int limit) {
        httpAttemptLimit.set(limit);
    }

    public void limitRunHttpAttempts(int limit) {
        runHttpAttemptLimit.set(limit);
    }

    public boolean budgetExceeded() {
        return budgetExceeded.get();
    }

    public boolean httpBudgetExceeded() {
        return httpBudgetExceeded.get();
    }

    public boolean runBudgetExceeded() {
        return runBudgetExceeded.get();
    }

    public int modelOperations() {
        return modelStarts.get();
    }

    public int httpAttempts() {
        return httpStarts.get();
    }

    public int runHttpAttempts() {
        return runHttpStarts.get();
    }

    public List<ObservationRecord> all() {
        return List.copyOf(records);
    }

}
