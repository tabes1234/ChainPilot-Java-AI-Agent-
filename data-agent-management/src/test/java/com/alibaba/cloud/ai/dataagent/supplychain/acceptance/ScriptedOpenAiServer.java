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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Scripted OpenAI-compatible endpoint bound to 127.0.0.1 only. It exists so the real request path
 * (production model factory, Spring AI tool execution, provider retries, outbound budgets and the
 * evaluation assertions) can be exercised without a provider: every request the stub answers is one
 * the harness counted, and nothing ever leaves the machine.
 *
 * <p>A script lists the response for each successive request of one request kind. The kind is derived
 * from the request itself, which keeps the script independent of prompt wording:
 *
 * <ul>
 * <li>{@code planner} - a stage without tool definitions (the ChainPilot planner call);</li>
 * <li>{@code report} - a stage that offered tools and has not received a tool result yet;</li>
 * <li>{@code feedback} - a stage that already carries a tool result, i.e. the follow-up turn.</li>
 * </ul>
 *
 * <p>The last action of a list repeats, so {@code [500, 500, TOOL_CALL]} reproduces provider-side
 * retransmission followed by a tool call on the third attempt. Responses never contain prompts,
 * reasoning text or credentials.
 */
final class ScriptedOpenAiServer implements AutoCloseable {

    /** One served response. {@link #CONTENT} answers with the marker as plain assistant text. */
    enum Action {
        PLAN,
        NARRATIVE,
        TOOL_CALL,
        STATUS_401,
        STATUS_429,
        STATUS_500,
        PLAIN_TEXT
    }

    /** Per-kind response script. */
    record Script(List<Action> planner, List<Action> report, List<Action> feedback) {

        static Script allNarrative() {
            return new Script(List.of(Action.PLAN), List.of(Action.NARRATIVE), List.of(Action.NARRATIVE));
        }

        static Script toolLoop() {
            return new Script(List.of(Action.PLAN), List.of(Action.TOOL_CALL), List.of(Action.NARRATIVE));
        }
    }

    static final String TOOL_CALL_ID = "call_chainpilot_scripted_1";

    static final String INVENTORY_SKU = "SKU-MCU-001";

    private static final List<String> DEMO_RISK_ORDER_IDS = List.of("ORD-1001", "ORD-1002", "ORD-1004");

    private final ObjectMapper mapper = new ObjectMapper();

    private final HttpServer server;

    private final AtomicInteger requests = new AtomicInteger();

    private final List<String> trail = new ArrayList<>();

    private final AtomicInteger plannerCount = new AtomicInteger();

    private final AtomicInteger reportCount = new AtomicInteger();

    private final AtomicInteger feedbackCount = new AtomicInteger();

    private volatile Script script = Script.allNarrative();

    private volatile String narrativeMarker = "SCRIPTED_MODEL_OK";

    private ScriptedOpenAiServer(HttpServer server) {
        this.server = server;
    }

    static ScriptedOpenAiServer start() {
        try {
            HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ScriptedOpenAiServer stub = new ScriptedOpenAiServer(http);
            http.createContext("/v1/chat/completions", stub::handle);
            http.setExecutor(null);
            http.start();
            return stub;
        }
        catch (IOException exception) {
            throw new IllegalStateException("the scripted loopback model endpoint could not start", exception);
        }
    }

    void reset(Script nextScript) {
        reset(nextScript, "SCRIPTED_MODEL_OK");
    }

    void reset(Script nextScript, String nextNarrativeMarker) {
        script = nextScript;
        narrativeMarker = nextNarrativeMarker;
        requests.set(0);
        plannerCount.set(0);
        reportCount.set(0);
        feedbackCount.set(0);
        synchronized (trail) {
            trail.clear();
        }
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    int requests() {
        return requests.get();
    }

    List<String> trail() {
        synchronized (trail) {
            return List.copyOf(trail);
        }
    }

    long served(String kind) {
        return trail().stream().filter(entry -> entry.contains(":" + kind + "=")).count();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        int attempt = requests.incrementAndGet();
        JsonNode payload = mapper.readTree(new String(exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8));
        String kind = classify(payload);
        Action action = next(kind);
        synchronized (trail) {
            trail.add(attempt + ":" + kind + "=" + action);
        }

        int status = 200;
        byte[] body;
        switch (action) {
            case STATUS_401 -> {
                status = 401;
                body = error("scripted unauthorized");
            }
            case STATUS_429 -> {
                status = 429;
                body = error("scripted rate limit");
            }
            case STATUS_500 -> {
                status = 500;
                body = error("scripted server error");
            }
            case TOOL_CALL -> body = toolCall();
            case NARRATIVE -> body = narrative(narrativeMarker);
            case PLAN -> body = plan();
            default -> body = plainText(narrativeMarker);
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream stream = exchange.getResponseBody()) {
            stream.write(body);
        }
    }

    private Action next(String kind) {
        Script current = script;
        List<Action> actions = switch (kind) {
            case "planner" -> current.planner();
            case "report" -> current.report();
            default -> current.feedback();
        };
        if (actions.isEmpty()) {
            return Action.PLAIN_TEXT;
        }
        int seen = switch (kind) {
            case "planner" -> plannerCount.getAndIncrement();
            case "report" -> reportCount.getAndIncrement();
            default -> feedbackCount.getAndIncrement();
        };
        return actions.get(Math.min(seen, actions.size() - 1));
    }

    private String classify(JsonNode payload) {
        boolean tools = payload.path("tools").isArray() && !payload.path("tools").isEmpty();
        boolean feedback = false;
        for (JsonNode message : payload.path("messages")) {
            if ("tool".equals(message.path("role").asText())) {
                feedback = true;
            }
        }
        if (!tools) {
            return "planner";
        }
        return feedback ? "feedback" : "report";
    }

    private byte[] error(String message) {
        return ("{\"error\":{\"message\":\"" + message + "\"}}").getBytes(StandardCharsets.UTF_8);
    }

    private byte[] plan() throws IOException {
        return mapper.writeValueAsBytes(completion("1. 查询订单延期风险\n2. 核对库存与可承诺量\n3. 检查供应商履约风险",
                "stop"));
    }

    private byte[] plainText(String content) throws IOException {
        return mapper.writeValueAsBytes(completion(content, "stop"));
    }

    /**
     * The report narrative is an evidence-backed JSON contract: its reasons must cover every
     * deterministic risk order of the demo dataset, otherwise the production validator rejects it and
     * the run degrades. The scripted success therefore has to honour that contract.
     */
    private byte[] narrative(String overview) throws IOException {
        ObjectNode root = mapper.createObjectNode();
        root.put("overview", overview);
        ArrayNode reasons = root.putArray("reasons");
        for (String orderId : DEMO_RISK_ORDER_IDS) {
            ObjectNode reason = reasons.addObject();
            reason.put("orderId", orderId);
            reason.put("summary", "该订单的确定性风险证据已由 Java 规则计算。");
            reason.putArray("evidenceIds").add("DELAY");
        }
        root.putArray("businessImpacts");
        root.putArray("recommendations");
        root.putArray("uncertainties");
        return mapper.writeValueAsBytes(completion(root.toString(), "stop"));
    }

    private byte[] toolCall() throws IOException {
        ObjectNode root = completion(null, "tool_calls");
        ObjectNode message = (ObjectNode) root.path("choices").get(0).path("message");
        ObjectNode toolCall = message.putArray("tool_calls").addObject();
        toolCall.put("id", TOOL_CALL_ID);
        toolCall.put("type", "function");
        ObjectNode function = toolCall.putObject("function");
        function.put("name", "findInventory");
        function.put("arguments", "{\"sku\":\"" + INVENTORY_SKU + "\"}");
        return mapper.writeValueAsBytes(root);
    }

    private ObjectNode completion(String content, String finishReason) {
        ObjectNode root = mapper.createObjectNode();
        root.put("id", "chatcmpl-scripted-" + Instant.now().getEpochSecond() + '-' + requests.get());
        root.put("object", "chat.completion");
        root.put("created", Instant.now().getEpochSecond());
        root.put("model", "scripted-loopback-model");
        ObjectNode choice = root.putArray("choices").addObject();
        choice.put("index", 0);
        ObjectNode message = choice.putObject("message");
        message.put("role", "assistant");
        if (content == null) {
            message.putNull("content");
        }
        else {
            message.put("content", content);
        }
        choice.put("finish_reason", finishReason);
        ObjectNode usage = root.putObject("usage");
        usage.put("prompt_tokens", 1);
        usage.put("completion_tokens", 1);
        usage.put("total_tokens", 2);
        return root;
    }

}
