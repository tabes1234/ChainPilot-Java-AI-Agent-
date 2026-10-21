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
                "maxModelCalls", settings.maxModelCalls()));
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
        return markdown.toString();
    }

    private static void row(StringBuilder markdown, String key, String value) {
        markdown.append("| ").append(escape(key)).append(" | ").append(escape(value)).append(" |\n");
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
    }

}
