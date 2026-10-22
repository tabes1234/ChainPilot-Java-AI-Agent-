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

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes opt-in acceptance evidence under the build output. The evidence contains counts,
 * flags, business identifiers and endpoint metadata only: no prompts, model answers, tool
 * arguments, tool results or credentials.
 */
public final class RealModelAcceptanceEvidenceWriter {

    public static final Path OUTPUT_DIRECTORY = Path.of("target", "chainpilot-real-model-acceptance");

    private RealModelAcceptanceEvidenceWriter() {
    }

    public static Path write(RealModelAcceptanceSettings settings, Map<String, Object> evidence,
            List<String> executedTests, boolean complete) throws IOException {
        Files.createDirectories(OUTPUT_DIRECTORY);
        boolean fullScope = executedTests.size() == 4;
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("executedAt", Instant.now().toString());
        report.put("run", Map.of(
                "complete", complete,
                "fullScope", fullScope,
                "scope", String.join(",", executedTests)));
        report.put("target", Map.of(
                "provider", settings.provider(),
                "model", settings.modelName(),
                "endpoint", settings.endpointDescription(),
                "maxTokens", settings.maxTokens(),
                "timeoutSeconds", settings.timeoutSeconds(),
                "maxModelCalls", settings.maxModelCalls(),
                "maxHttpAttemptsPerScenario", settings.maxHttpAttempts(),
                "maxRunHttpAttempts", settings.maxRunHttpAttempts()));
        report.put("checklist", checklist(evidence));
        report.put("evidence", evidence);

        String baseName = fullScope ? "real-model-acceptance-evidence"
                : "real-model-acceptance-evidence.partial";
        Path json = OUTPUT_DIRECTORY.resolve(baseName + ".json");
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(json.toFile(), report);

        Path markdown = OUTPUT_DIRECTORY.resolve(baseName + ".md");
        Files.writeString(markdown, markdown(settings, evidence, report), StandardCharsets.UTF_8);
        return markdown;
    }

    /**
     * Evidence for the evaluation-centre AGENT acceptance run. The keys deliberately separate the six
     * notions that must never be conflated: task completion, model stage started, model stage
     * completed, model result credited into the answer, deterministic degradation, and case verdict.
     */
    public static Path writeAgentEvidence(RealModelAcceptanceSettings settings, Map<String, Object> evidence,
            String runId, boolean complete) throws IOException {
        Files.createDirectories(OUTPUT_DIRECTORY);
        boolean providerEndpoint = settings.endpointIsExternal();
        String scope = String.valueOf(evidence.getOrDefault("harness.scope", "agent-evaluation"));
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("executedAt", Instant.now().toString());
        report.put("run", Map.of("complete", complete, "agentEvaluationRunId", runId == null ? "" : runId, "scope",
                scope, "providerEvidence", providerEndpoint));
        report.put("target", Map.of("provider", settings.provider(), "model", settings.modelName(),
                "endpoint", settings.endpointDescription(), "maxTokens", settings.maxTokens(),
                "timeoutSeconds", settings.timeoutSeconds(), "maxModelCalls", settings.maxModelCalls(),
                "maxHttpAttemptsPerScenario", settings.maxHttpAttempts(),
                "maxRunHttpAttempts", settings.maxRunHttpAttempts(),
                "agentCases", String.join(",", settings.agentCases())));
        report.put("concepts", agentConcepts(evidence));
        report.put("evidence", evidence);

        Path json = OUTPUT_DIRECTORY.resolve(baseName(scope, providerEndpoint) + ".json");
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(json.toFile(), report);

        StringBuilder markdown = new StringBuilder();
        markdown.append(providerEndpoint ? "# ChainPilot 评测中心 AGENT 真实模型验收证据\n\n"
                : "# ChainPilot 评测中心 AGENT 本地端点彩排证据（非真实 Provider）\n\n");
        if (!complete) {
            markdown.append("> 本次运行存在失败案例或未完成场景，不能视为验收通过。\n\n");
        }
        markdown.append("执行时间：").append(report.get("executedAt")).append("\n\n");
        markdown.append("## 目标与上限\n\n| 项目 | 值 |\n| --- | --- |\n");
        report.forEach((key, value) -> appendFlat(markdown, key, value));
        markdown.append('\n');
        markdown.append("## 概念区分（不得互相替代）\n\n| 概念 | 观测 |\n| --- | --- |\n");
        for (Map.Entry<String, String> entry : agentConcepts(evidence).entrySet()) {
            row(markdown, entry.getKey(), entry.getValue());
        }
        markdown.append('\n');
        markdown.append("## 边界\n\n");
        markdown.append("- 本报告不记录 API Key、提示词、模型原文回答、推理内容、工具参数或工具结果。\n");
        markdown.append("- 只有真实 Provider 的本次运行才能作为真实模型验收证据；"
                + "本地回环脚本桩与 Mock 结果只能证明 Harness 逻辑，不能替代真实 Provider 验收。\n");
        markdown.append("- 评测运行完成（run COMPLETED）不等于案例通过；案例通过（PASSED）要求模型参与断言与业务断言全部执行且成立。\n");
        markdown.append(providerEndpoint ? "- 本文件由配置在外部端点上的授权验收运行写出。\n"
                : "- 本文件由非外部端点（回环或本机地址）的运行写出，不构成真实 Provider 验收证据，且与真实 Provider 证据分文件存放。\n");
        Path markdownPath = OUTPUT_DIRECTORY.resolve(baseName(scope, providerEndpoint) + ".md");
        Files.writeString(markdownPath, markdown, StandardCharsets.UTF_8);
        return markdownPath;
    }

    /**
     * The label follows the endpoint the run actually dialled, not the intent written in the scope string:
     * a rehearsal against a local port must not be able to produce the provider artifact simply by calling
     * its scope "real-provider".
     */
    private static String baseName(String scope, boolean providerEndpoint) {
        if (providerEndpoint) {
            return "agent-real-model-acceptance-evidence";
        }
        return scope.contains("loopback") ? "agent-harness-loopback-evidence" : "agent-local-endpoint-evidence";
    }

    /** The six distinct acceptance concepts, resolved from observed evidence only. */
    private static Map<String, String> agentConcepts(Map<String, Object> evidence) {
        Map<String, String> concepts = new LinkedHashMap<>();
        concepts.put("任务 COMPLETED（业务完成）", observed(evidence, "case.taskStatus"));
        concepts.put("模型阶段已发起 MODEL_STARTED", observed(evidence, "case.modelStartedEvents"));
        concepts.put("模型阶段成功 MODEL_COMPLETED(status=COMPLETED)", observed(evidence, "case.successfulModelEvents"));
        concepts.put("模型结果被采纳并参与输出", observed(evidence, "case.modelAssisted"));
        concepts.put("发生确定性降级 FALLBACK_USED", observed(evidence, "case.fallbackEvents"));
        concepts.put("评测案例判定", observed(evidence, "case.status"));
        concepts.put("评测运行判定", observed(evidence, "run.status"));
        concepts.put("出站请求未超上限", observed(evidence, "budget.withinCeiling"));
        concepts.put("评测锁已释放", observed(evidence, "lock.released"));
        return concepts;
    }

    private static void appendFlat(StringBuilder markdown, String key, Object value) {
        if (value instanceof Map<?, ?> map) {
            map.forEach((inner, innerValue) -> row(markdown, key + "." + inner, String.valueOf(innerValue)));
            return;
        }
        row(markdown, key, String.valueOf(value));
    }

    private static Map<String, String> checklist(Map<String, Object> evidence) {
        Map<String, String> checklist = new LinkedHashMap<>();
        checklist.put("model request reached the configured endpoint",
                observed(evidence, "basic.contentNonBlank", "basic.httpClientObservations"));
        checklist.put("planner/report assistance flags follow actual model events",
                observed(evidence, "report.flagsRecorded", "report.eventCountsConsistent"));
        checklist.put("real model action executed SupplyChainAgentTools",
                observed(evidence, "tooling.modelInitiatedFindInventory"));
        checklist.put("tool result was followed by another model turn",
                observed(evidence, "tooling.followUpModelTurns", "tooling.toolResultFedBackToModel",
                        "tooling.summaryContainsToolValue"));
        checklist.put("final report facts match deterministic business data",
                observed(evidence, "report.atRiskOrdersMatch", "report.noUnknownOrderIds"));
        checklist.put("HTTP failure falls back without claiming model assistance",
                observed(evidence, "fallback.flagsAndEventsRecorded"));
        checklist.put("model/tool observations are explainable and secret-free",
                observed(evidence, "observability.modelOperations", "observability.httpRequests",
                        "observability.noSecretOrPromptAttributes"));
        return checklist;
    }

    private static String observed(Map<String, Object> evidence, String... keys) {
        for (String key : keys) {
            Object value = evidence.get(key);
            if (value == null) {
                return "NOT_OBSERVED";
            }
            if (value instanceof Boolean booleanValue && !booleanValue) {
                return "NOT_OBSERVED";
            }
            if (value instanceof Number number && number.longValue() <= 0) {
                return "NOT_OBSERVED";
            }
        }
        return "OBSERVED";
    }

    private static String markdown(RealModelAcceptanceSettings settings, Map<String, Object> evidence,
            Map<String, Object> report) {
        @SuppressWarnings("unchecked")
        Map<String, Object> run = (Map<String, Object>) report.get("run");
        boolean fullScope = Boolean.TRUE.equals(run.get("fullScope"));
        boolean complete = Boolean.TRUE.equals(run.get("complete"));

        StringBuilder markdown = new StringBuilder();
        markdown.append(fullScope ? "# ChainPilot 真实模型端到端验收证据\n\n"
                : "# ChainPilot 真实模型部分场景验收证据\n\n");
        if (!fullScope) {
            markdown.append("> 本次只执行了部分验收场景，不能视为完整验收。\n\n");
        }
        else if (!complete) {
            markdown.append("> 本次场景集存在失败用例，不能视为验收通过。\n\n");
        }
        markdown.append("执行时间：").append(report.get("executedAt")).append("\n\n");
        markdown.append("## 验收目标\n\n");
        markdown.append("| 项目 | 值 |\n| --- | --- |\n");
        row(markdown, "Provider", settings.provider());
        row(markdown, "Model", settings.modelName());
        row(markdown, "Endpoint", settings.endpointDescription());
        row(markdown, "Max tokens", String.valueOf(settings.maxTokens()));
        row(markdown, "Task timeout (seconds)", String.valueOf(settings.timeoutSeconds()));
        row(markdown, "Max model calls", String.valueOf(settings.maxModelCalls()));
        row(markdown, "HTTP attempts per scenario", String.valueOf(settings.maxHttpAttempts()));
        row(markdown, "HTTP attempts per run", String.valueOf(settings.maxRunHttpAttempts()));
        row(markdown, "Run complete", String.valueOf(complete));
        row(markdown, "Run scope", String.valueOf(run.get("scope")));
        markdown.append('\n');
        markdown.append("本报告只记录本次真实运行观察到的事实，不计算准确率或可靠性分数，也不代表其他模型、")
            .append("其他供应商或生产 Langfuse 导出链路已经验证。\n\n");

        @SuppressWarnings("unchecked")
        Map<String, String> checklist = (Map<String, String>) report.get("checklist");
        markdown.append("## 验收集清单\n\n");
        markdown.append("| 维度 | 状态 |\n| --- | --- |\n");
        for (Map.Entry<String, String> entry : checklist.entrySet()) {
            row(markdown, entry.getKey(), entry.getValue());
        }
        markdown.append('\n');

        markdown.append("## 原始观测证据\n\n");
        markdown.append("| 键 | 值 |\n| --- | --- |\n");
        for (Map.Entry<String, Object> entry : evidence.entrySet()) {
            row(markdown, entry.getKey(), String.valueOf(entry.getValue()));
        }
        markdown.append('\n');
        markdown.append("## 边界\n\n");
        markdown.append("- 未记录 API Key、完整提示词、完整模型回答、工具参数或工具结果。\n");
        markdown.append("- `spring.ai.tool` 与 `gen_ai.client.operation` 为本地 Micrometer 观测；本报告不推断外部 Langfuse 导出成功。\n");
        markdown.append("- 本地假模型测试与离线评测不构成本报告的真实模型证据。\n");
        markdown.append("- 模型未发起工具调用时，Tool Calling 维度必须是 NOT_OBSERVED，而不是 PASS。\n");
        markdown.append("- 模型阶段上限统计 `gen_ai.client.operation`；出站请求上限统计每一次实际尝试（含库层重试与"
                + "工具后续回合），两者同时记录，超上限时后一次请求在发出前被拦截。\n");
        return markdown.toString();
    }

    private static void row(StringBuilder markdown, String key, String value) {
        markdown.append("| ").append(escape(key)).append(" | ").append(escape(value)).append(" |\n");
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
    }

}
