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

import com.alibaba.cloud.ai.dataagent.service.aimodelconfig.AiModelRegistry;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalExplanation;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalRecommendation;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DisposalSimulation;
import com.alibaba.cloud.ai.dataagent.supplychain.model.KnowledgeCitation;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Produces the plan narrative. The model only explains deterministic recommendation and
 * simulation results; if no model is configured or the call fails, the deterministic text is
 * returned and explicitly marked as such. No number in either path is computed by the model.
 */
@Slf4j
@Service
public class DisposalExplanationService {

    private final AiModelRegistry modelRegistry;

    private final SupplyChainAgentTools agentTools;

    private final ObservationRegistry observationRegistry;

    public DisposalExplanationService(AiModelRegistry modelRegistry,
            @Lazy SupplyChainAgentTools agentTools, ObservationRegistry observationRegistry) {
        this.modelRegistry = modelRegistry;
        this.agentTools = agentTools;
        this.observationRegistry = observationRegistry;
    }

    public DisposalExplanation explain(String title, List<DisposalRecommendation> recommendations,
            DisposalSimulation simulation, List<KnowledgeCitation> citations) {
        String deterministic = deterministicExplanation(title, recommendations, simulation);
        long started = System.nanoTime();
        try {
            ModelToolCallSession toolSession = ModelToolCallSession.open(agentTools, observationRegistry);
            String content = modelRegistry.getChatClient()
                    .prompt()
                    .system("你是供应链处置方案解释助手。只能依据给定的规则建议和工具返回的确定性结果做解释，"
                            + "不得自行计算、修改或编造任何数字、订单、库存、供应商、成本、收益或替代来源。"
                            + "如对数据有疑问，先调用处置建议或模拟工具核对，再给出简明、可执行的中文说明。")
                    .user(prompt(title, recommendations, simulation, citations))
                    .toolCallbacks(toolSession.callbacks())
                    .call()
                    .content();
            if (StringUtils.hasText(content)) {
                log.info("ChainPilot disposal explanation accepted from model; chars={} toolCalls={}",
                        content.length(), toolSession.calls().size());
                return DisposalExplanation.model(content.trim(), toolSession.calls());
            }
            log.info("ChainPilot disposal explanation model returned empty content; using deterministic text");
        }
        catch (RuntimeException exception) {
            log.info("ChainPilot disposal explanation model unavailable; using deterministic text. causeType={} "
                    + "durationMs={}", exception.getClass().getSimpleName(),
                    (System.nanoTime() - started) / 1_000_000);
        }
        return DisposalExplanation.deterministic(deterministic);
    }

    /** Used when plan creation explicitly asks to skip the model call. */
    public DisposalExplanation deterministicOnly(String title, List<DisposalRecommendation> recommendations,
            DisposalSimulation simulation) {
        return DisposalExplanation.deterministic(deterministicExplanation(title, recommendations, simulation));
    }

    private static String prompt(String title, List<DisposalRecommendation> recommendations,
            DisposalSimulation simulation, List<KnowledgeCitation> citations) {
        return "方案标题：" + title + "\n\n规则建议（优先级和数字已由 Java 计算）：\n"
                + recommendations.stream()
                    .map(recommendation -> "- " + recommendation.priority() + "（"
                            + recommendation.priorityScore() + " 分）" + recommendation.title() + "；动作："
                            + recommendation.action() + "；涉及订单："
                            + String.join("、", recommendation.affectedOrderIds()) + "；规则依据："
                            + recommendation.priorityBasis() + "；预期：" + recommendation.expectedImprovement())
                    .collect(Collectors.joining("\n"))
                + "\n\n模拟结果（确定性规则，未修改真实数据）：\n"
                + (simulation == null ? "本方案尚未关联模拟。" : simulation.toString())
                + "\n\n可引用的 SOP 证据：\n"
                + citations.stream()
                    .map(citation -> "- " + citation.sourceId() + " " + citation.title() + "："
                            + citation.excerpt())
                    .collect(Collectors.joining("\n"))
                + "\n\n请输出一段 150-300 字的处置解释，说明优先级依据、建议动作、模拟前后差异、"
                + "需要人工确认的前置条件和风险边界。不要输出 JSON。";
    }

    private static String deterministicExplanation(String title, List<DisposalRecommendation> recommendations,
            DisposalSimulation simulation) {
        if (recommendations.isEmpty()) {
            return "方案「" + title + "」当前没有可执行的规则建议。请先确认风险范围和数据完整性。";
        }
        DisposalRecommendation top = recommendations.get(0);
        StringBuilder text = new StringBuilder("方案「").append(title).append("」包含 ")
                .append(recommendations.size())
                .append(" 条规则处置建议，最高优先级为 ")
                .append(top.priority())
                .append("（")
                .append(top.priorityScore())
                .append(" 分）：")
                .append(top.title())
                .append("。优先级依据：")
                .append(top.priorityBasis())
                .append("。");
        if (simulation != null) {
            text.append("模拟场景为 ")
                    .append(simulation.scenario())
                    .append("；基线风险订单 ")
                    .append(simulation.baseline().atRiskOrders())
                    .append(" 个、库存缺口合计 ")
                    .append(simulation.baseline().totalShortageUnits())
                    .append(" 件，模拟后风险订单 ")
                    .append(simulation.simulated().atRiskOrders())
                    .append(" 个、库存缺口合计 ")
                    .append(simulation.simulated().totalShortageUnits())
                    .append(" 件，受影响订单 ")
                    .append(simulation.affectedOrders().size())
                    .append(" 个。");
        }
        else {
            text.append("本方案尚未关联情景模拟，建议先选择补货或供应商延迟场景量化影响。");
        }
        text.append("以上数字均由确定性 Java 规则计算，模拟不代表实际执行结果；补货到货时间、供应商恢复日期、"
                + "成本和替代来源仍需人工确认。");
        return text.toString();
    }
}
