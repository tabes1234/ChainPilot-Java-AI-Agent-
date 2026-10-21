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

import com.alibaba.cloud.ai.dataagent.supplychain.evaluation.SupplyChainEvaluationDataset.EvaluationCase;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Writes a deterministic report under the build output. The report contains no timestamps,
 * task IDs, or other run-specific values, so two runs over the same code and dataset can be
 * compared byte for byte.
 */
public final class SupplyChainEvaluationReportWriter {

    public static final Path OUTPUT_DIRECTORY = Path.of("target", "chainpilot-evaluation");

    public static final String MARKDOWN_FILE = "supply-chain-evaluation-report.md";

    public static final String JSON_FILE = "supply-chain-evaluation-results.json";

    private SupplyChainEvaluationReportWriter() {
    }

    public static Path writeJson(ObjectMapper mapper, EvaluationOutcome outcome) throws IOException {
        Files.createDirectories(OUTPUT_DIRECTORY);
        Path output = OUTPUT_DIRECTORY.resolve(JSON_FILE);
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), outcome);
        return output;
    }

    public static Path writeMarkdown(SupplyChainEvaluationDataset dataset, EvaluationOutcome outcome)
            throws IOException {
        Files.createDirectories(OUTPUT_DIRECTORY);
        Path output = OUTPUT_DIRECTORY.resolve(MARKDOWN_FILE);
        Files.writeString(output, markdown(dataset, outcome), StandardCharsets.UTF_8);
        return output;
    }

    private static String markdown(SupplyChainEvaluationDataset dataset, EvaluationOutcome outcome) {
        StringBuilder report = new StringBuilder();
        report.append("# ChainPilot 供应链分析离线评测报告\n\n");
        report.append("数据集版本：").append(outcome.datasetVersion()).append("\n\n");
        report.append("## 评测边界\n\n");
        report.append("- 本报告评测的是真实执行的确定性业务规则、演示数据查询、SOP 词法检索、StateGraph 工作流及无模型降级路径。\n");
        report.append("- 当前环境没有 VectorStore，也没有配置 CHAT 模型；检索仅经过 `SupplyChainKnowledgeService` 的本地词法路径，不代表 embedding 或向量语义检索质量。\n");
        report.append("- 不包含 LLM-as-a-judge、真实模型生成质量、真实模型 Tool Calling 或付费模型调用；这些属于独立的测试/评测边界。\n");
        report.append("- 每个样本的期望值来自 `cases.json` 的人工核定结果，不参与生产实现。\n\n");

        report.append("## 汇总\n\n");
        report.append("| 项目 | 值 |\n| --- | --- |\n");
        row(report, "样本总数", String.valueOf(outcome.totalCases()));
        row(report, "通过样本", String.valueOf(outcome.passedCases()));
        row(report, "失败样本", String.valueOf(outcome.failedCases()));
        row(report, "事实一致性检查", outcome.factChecksPassed() + " / " + outcome.factChecks());
        row(report, "事实一致率", decimal(outcome.factConsistency()));
        report.append('\n');
        report.append("事实一致性检查来自 `ORDER_RISK`、`INVENTORY`、`SUPPLIER`、`REPORT_FALLBACK` 样本，每个期望字段计一项，")
            .append("检索命中由下面的 Hit@3 单独计算。\n\n");

        RiskClassificationMetrics risk = outcome.riskMetrics();
        RetrievalMetrics retrieval = outcome.retrievalMetrics();
        report.append("### 风险分类指标\n\n");
        report.append("正类定义为 `riskLevel != LOW`；对象粒度为订单；评测全集为 `ORDER_RISK` 样本覆盖的全部演示订单（")
            .append(risk.evaluated()).append(" 个）。预测结果以真实 `SupplyChainQueryService.findOrders` 的输出为准；")
            .append("FP 表示误报，FN 表示漏报，不在评测全集中的预测 ID 不计入混淆矩阵。\n\n");
        report.append("| 指标 | 值 |\n| --- | --- |\n");
        row(report, "TP / FP / FN / TN", risk.truePositives() + " / " + risk.falsePositives() + " / "
                + risk.falseNegatives() + " / " + risk.trueNegatives());
        row(report, "Precision = TP / (TP + FP)", decimal(risk.precision()));
        row(report, "Recall = TP / (TP + FN)", decimal(risk.recall()));
        row(report, "F1 = 2PR / (P + R)", decimal(risk.f1()));
        row(report, "Accuracy = (TP + TN) / 全集", decimal(risk.accuracy()));
        report.append("\n零分母规则：分母为 0 时该指标记为 0.0000，不产生 NaN 或异常；混淆矩阵原始计数始终保留。\n\n");

        report.append("### 词法检索指标\n\n");
        report.append("Top-K 固定为 3。每个有期望来源的查询，只要任一期望 SOP 出现在返回结果中即计为命中；MRR 取首个命中来源排名倒数。\n\n");
        report.append("| 指标 | 值 |\n| --- | --- |\n");
        row(report, "有效查询数", String.valueOf(retrieval.evaluatedQueries()));
        row(report, "命中数", String.valueOf(retrieval.hits()));
        row(report, "Hit@3", decimal(retrieval.hitRateAt3()));
        row(report, "MRR", decimal(retrieval.meanReciprocalRank()));
        row(report, "负向查询 / 通过数", retrieval.negativeQueries() + " / " + retrieval.negativePasses());
        report.append("\n零分母规则：没有有效查询时 Hit@3 与 MRR 记为 0.0000；负向查询不计入命中率分母。\n\n");

        report.append("## 检索明细\n\n");
        report.append("| 样本 | 期望来源 | 实际返回来源 |\n| --- | --- | --- |\n");
        for (EvaluationCaseOutcome evaluationCase : outcome.cases()) {
            if (!SupplyChainEvaluationDataset.Kind.RETRIEVAL.name().equals(evaluationCase.kind())) {
                continue;
            }
            EvaluationCase sample = findCase(dataset, evaluationCase.id());
            String actual = evaluationCase.checks().isEmpty() ? "[]" : evaluationCase.checks().get(0).actual();
            report.append("| ").append(evaluationCase.id()).append(" | ")
                .append(sample == null ? "" : escape(sample.retrieval().expectedSourceIds().toString()))
                .append(" | ").append(escape(actual)).append(" |\n");
        }
        report.append('\n');

        report.append("## 样本结果\n\n");
        report.append("| 样本 | 类型 | 结果 |\n| --- | --- | --- |\n");
        for (EvaluationCaseOutcome evaluationCase : outcome.cases()) {
            report.append("| ").append(evaluationCase.id()).append(" | ").append(evaluationCase.kind())
                .append(" | ").append(evaluationCase.passed() ? "PASS" : "FAIL").append(" |\n");
        }
        report.append('\n');

        report.append("## 失败明细\n\n");
        if (outcome.failures().isEmpty()) {
            report.append("无失败样本。\n\n");
        }
        else {
            for (EvaluationCaseOutcome evaluationCase : outcome.failures()) {
                report.append("### ").append(evaluationCase.id()).append(" (").append(evaluationCase.kind())
                    .append(")\n\n");
                for (EvaluationCheck check : evaluationCase.failures()) {
                    report.append("- 字段 `").append(check.field()).append("`: 期望 `")
                        .append(check.expected()).append("`，实际 `").append(check.actual()).append("`\n");
                }
                report.append('\n');
            }
        }

        report.append("## 样本依据\n\n");
        report.append("| 样本 | 场景 | 依据 |\n| --- | --- | --- |\n");
        for (EvaluationCase evaluationCase : dataset.cases()) {
            report.append("| ").append(evaluationCase.id()).append(" | ").append(escape(evaluationCase.scenario()))
                .append(" | ").append(escape(evaluationCase.basis())).append(" |\n");
        }
        report.append('\n');

        report.append("## 复现方式\n\n");
        report.append("在 `data-agent-management` 目录执行：\n\n");
        report.append("```bash\nJAVA_HOME=\"C:/Program Files/Microsoft/jdk-17.0.12.7-hotspot\" ")
            .append("../mvnw -DforkCount=0 -Dtest=\"SupplyChainOfflineEvaluationTest\" test\n```\n\n");
        report.append("输出文件：`target/chainpilot-evaluation/")
            .append(MARKDOWN_FILE).append("` 与 `target/chainpilot-evaluation/").append(JSON_FILE).append("`。\n");
        return report.toString();
    }

    private static EvaluationCase findCase(SupplyChainEvaluationDataset dataset, String id) {
        return dataset.cases().stream().filter(evaluationCase -> evaluationCase.id().equals(id)).findFirst()
            .orElse(null);
    }

    private static void row(StringBuilder report, String label, String value) {
        report.append("| ").append(label).append(" | ").append(value).append(" |\n");
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
    }

}
