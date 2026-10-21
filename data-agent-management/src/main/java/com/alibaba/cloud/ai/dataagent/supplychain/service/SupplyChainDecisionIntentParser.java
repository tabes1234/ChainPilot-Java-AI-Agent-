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

import com.alibaba.cloud.ai.dataagent.supplychain.model.DecisionIntent;
import com.alibaba.cloud.ai.dataagent.supplychain.model.DecisionIntentType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic intent parser for the natural-language decision entry. It extracts exact SKU,
 * supplier and record identifiers and never invents missing business parameters: incomplete
 * requests carry a clarification and are not executed.
 */
@Component
public class SupplyChainDecisionIntentParser {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b");

    private static final Pattern SKU_PATTERN = Pattern.compile("(?i)\\bSKU-[A-Z0-9]+(?:-[A-Z0-9]+)*\\b");

    private static final Pattern SUPPLIER_PATTERN = Pattern.compile("(?i)\\bSUP-[A-Z0-9]+(?:-[A-Z0-9]+)*\\b");

    private static final Pattern DATE_PATTERN = Pattern.compile("\\d{4}-\\d{1,2}-\\d{1,2}");

    private static final Pattern INTEGER_PATTERN = Pattern.compile("\\d+");

    private static final int MAX_COMPARISON_CANDIDATES = 5;

    private static final int MAX_REPLENISHMENT_QUANTITY = 1_000_000;

    private static final int MAX_DELAY_DAYS = 90;

    private static final String SUPPORTED_OPERATIONS = "当前支持：分析供应链风险、查询处置建议、模拟补货或供应商延期、"
            + "比较 2-5 个补货数量或延期天数方案、查询对比/方案/沙箱执行记录，以及提出保存方案、关联方案、选定方案或"
            + "启动沙箱演练的待确认提案。";

    public DecisionIntent parse(String question) {
        if (!StringUtils.hasText(question)) {
            return unsupported("请输入供应链决策问题。" + SUPPORTED_OPERATIONS);
        }
        String text = normalize(question);
        List<String> uuids = findAll(UUID_PATTERN, text);
        Map<String, String> identifiers = classifyIdentifiers(text, uuids);
        String sku = firstMatch(SKU_PATTERN, text);
        String supplierId = firstMatch(SUPPLIER_PATTERN, text);
        String comparisonId = identifiers.get("comparison");
        String candidateId = identifiers.get("candidate");
        String planId = identifiers.get("plan");
        String recommendationId = identifiers.get("recommendation");
        String executionId = identifiers.get("execution");

        String pronounClarification = pronounClarification(text, sku, supplierId);
        if (pronounClarification != null) {
            return intent(DecisionIntentType.UNSUPPORTED, sku, supplierId, null, List.of(), null, List.of(),
                    comparisonId, candidateId, planId, recommendationId, executionId, null,
                    pronounClarification);
        }

        boolean startSandbox = containsAny(text, "沙箱", "演练")
                && containsAny(text, "启动", "开始", "执行", "运行");
        boolean eligibilityQuestion = containsAny(text, "能否", "能不能", "是否满足", "执行条件", "执行资格",
                "是否可执行");
        if (startSandbox && eligibilityQuestion) {
            if (!StringUtils.hasText(planId)) {
                return intent(DecisionIntentType.LOOKUP_PLAN, sku, supplierId, null, List.of(), null, List.of(),
                        comparisonId, candidateId, null, recommendationId, executionId, null,
                        "检查沙箱执行条件需要明确的方案编号（planId）。");
            }
            return intent(DecisionIntentType.LOOKUP_PLAN, sku, supplierId, null, List.of(), null, List.of(),
                    comparisonId, candidateId, planId, recommendationId, executionId, null, null);
        }
        if (startSandbox) {
            if (!StringUtils.hasText(planId)) {
                return intent(DecisionIntentType.PROPOSE_START_SANDBOX, sku, supplierId, null, List.of(), null,
                        List.of(), comparisonId, candidateId, null, recommendationId, executionId, null,
                        "启动沙箱演练需要明确的方案编号（planId）。请提供要执行演练的处置方案编号。");
            }
            return intent(DecisionIntentType.PROPOSE_START_SANDBOX, sku, supplierId, null, List.of(), null,
                    List.of(), comparisonId, candidateId, planId, recommendationId, executionId,
                    "启动方案 " + planId + " 的沙箱演练", null);
        }

        boolean select = StringUtils.hasText(planId) && containsAny(text, "选定", "选择", "采纳");
        if (select) {
            return intent(DecisionIntentType.PROPOSE_SELECT_PLAN, sku, supplierId, null, List.of(), null, List.of(),
                    comparisonId, candidateId, planId, recommendationId, executionId,
                    "选定方案 " + planId + " 的推荐建议", null);
        }

        boolean save = containsAny(text, "保存", "落地", "转为方案", "生成方案", "创建方案");
        if (save && (StringUtils.hasText(candidateId) || text.contains("候选"))) {
            if (!StringUtils.hasText(comparisonId)) {
                return intent(DecisionIntentType.PROPOSE_SAVE_PLAN, sku, supplierId, null, List.of(), null,
                        List.of(), null, candidateId, planId, recommendationId, executionId, null,
                        "把候选保存为方案需要明确的对比编号（comparisonId）和候选编号（candidateId）。");
            }
            return intent(DecisionIntentType.PROPOSE_SAVE_PLAN, sku, supplierId, null, List.of(), null, List.of(),
                    comparisonId, candidateId, planId, recommendationId, executionId, null, null);
        }

        boolean link = containsAny(text, "关联", "链接", "挂接", "绑定")
                && (StringUtils.hasText(candidateId) || text.contains("候选"));
        if (link) {
            List<String> missing = new ArrayList<>();
            if (!StringUtils.hasText(comparisonId)) {
                missing.add("对比编号（comparisonId）");
            }
            if (!StringUtils.hasText(candidateId)) {
                missing.add("候选编号（candidateId）");
            }
            if (!StringUtils.hasText(planId)) {
                missing.add("方案编号（planId）");
            }
            if (!missing.isEmpty()) {
                return intent(DecisionIntentType.PROPOSE_LINK_PLAN, sku, supplierId, null, List.of(), null,
                        List.of(), comparisonId, candidateId, planId, recommendationId, executionId, null,
                        "关联候选到方案需要：" + String.join("、", missing) + "。");
            }
            return intent(DecisionIntentType.PROPOSE_LINK_PLAN, sku, supplierId, null, List.of(), null, List.of(),
                    comparisonId, candidateId, planId, recommendationId, executionId,
                    "关联候选 " + candidateId + " 到方案 " + planId, null);
        }

        if (StringUtils.hasText(executionId)) {
            return intent(DecisionIntentType.LOOKUP_EXECUTION, sku, supplierId, null, List.of(), null, List.of(),
                    comparisonId, candidateId, planId, recommendationId, executionId, null, null);
        }
        if (StringUtils.hasText(planId) && containsAny(text, "执行记录", "执行历史", "执行情况", "执行结果",
                "沙箱记录", "演练记录")) {
            return intent(DecisionIntentType.LOOKUP_EXECUTION, sku, supplierId, null, List.of(), null, List.of(),
                    comparisonId, candidateId, planId, recommendationId, null, null, null);
        }
        if (StringUtils.hasText(comparisonId)) {
            return intent(DecisionIntentType.LOOKUP_COMPARISON, sku, supplierId, null, List.of(), null, List.of(),
                    comparisonId, candidateId, planId, recommendationId, executionId, null, null);
        }
        if (StringUtils.hasText(planId)) {
            return intent(DecisionIntentType.LOOKUP_PLAN, sku, supplierId, null, List.of(), null, List.of(),
                    comparisonId, candidateId, planId, recommendationId, executionId, null, null);
        }

        boolean delayContext = containsAny(text, "延期", "延迟", "延误", "推迟", "delay");
        boolean replenishmentContext = containsAny(text, "补货", "库存", "备货", "可承诺", "缺料");
        List<Integer> delayNumbers = numbersNear(text, List.of("延期", "延迟", "延误", "推迟", "天"));
        List<Integer> replenishmentNumbers = numbersNear(text, List.of("补货", "备货", "件", "库存"));
        if (!delayNumbers.isEmpty() && !replenishmentNumbers.isEmpty()) {
            return unsupported("检测到补货数量和延期天数两类数字，请分开描述，以便确定是补货方案还是供应商延期方案。"
                    + SUPPORTED_OPERATIONS);
        }

        boolean compareVerb = containsAny(text, "对比", "比较", "排名", "哪个方案", "哪个数量", "选哪个", "孰优");
        boolean simulateVerb = containsAny(text, "模拟", "假设", "如果", "会怎样", "影响", "情形");
        boolean delayScenario = delayContext || (!delayNumbers.isEmpty() && containsAny(text, "天"));
        boolean replenishmentScenario = replenishmentContext;

        if (delayScenario && !replenishmentScenario) {
            return delayIntent(text, supplierId, delayNumbers, compareVerb, simulateVerb);
        }
        if (replenishmentScenario && !delayScenario) {
            return replenishmentIntent(text, sku, replenishmentNumbers, compareVerb, simulateVerb);
        }
        if (delayScenario) {
            if (!delayNumbers.isEmpty()) {
                return delayIntent(text, supplierId, delayNumbers, compareVerb, simulateVerb);
            }
            return replenishmentIntent(text, sku, replenishmentNumbers, compareVerb, simulateVerb);
        }
        if (compareVerb && (!delayNumbers.isEmpty() || !replenishmentNumbers.isEmpty())) {
            return unsupported("检测到候选数值，但无法确定是补货数量还是供应商延期天数。请明确说明“补货 … 件”或"
                    + "“供应商延期 … 天”。");
        }

        if (containsAny(text, "建议", "推荐", "处置", "怎么办", "如何处理", "处理方案")) {
            return recommendationIntent(text, sku, supplierId);
        }
        if (containsAny(text, "分析", "风险", "订单", "交付", "履约", "供应商", "sku", "库存")) {
            return intent(DecisionIntentType.ANALYSIS, sku, supplierId, null, List.of(), null, List.of(),
                    null, null, null, null, null, null, null);
        }
        return unsupported("无法识别该请求的操作类型。" + SUPPORTED_OPERATIONS);
    }

    private DecisionIntent delayIntent(String text, String supplierId, List<Integer> numbers, boolean compareVerb,
            boolean simulateVerb) {
        if (!StringUtils.hasText(supplierId)) {
            return intent(DecisionIntentType.UNSUPPORTED, null, null, null, List.of(), null, List.of(), null, null,
                    null, null, null, null, "供应商延期模拟需要明确的供应商编号（例如 SUP-ALPHA）。");
        }
        if (numbers.isEmpty()) {
            return intent(DecisionIntentType.UNSUPPORTED, null, supplierId, null, List.of(), null, List.of(), null,
                    null, null, null, null, null,
                    "请提供供应商 " + supplierId + " 的额外延期天数，或 2-5 个用于比较的延期天数。");
        }
        String validation = validateNumbers(numbers, MAX_DELAY_DAYS, "延期天数");
        if (validation != null) {
            return intent(DecisionIntentType.UNSUPPORTED, null, supplierId, null, List.of(), null, List.of(), null,
                    null, null, null, null, null, validation);
        }
        if (numbers.size() == 1 && !compareVerb) {
            return intent(DecisionIntentType.SIMULATE_SUPPLIER_DELAY, null, supplierId, null, List.of(),
                    numbers.get(0), List.of(), null, null, null, null, null, null, null);
        }
        if (numbers.size() < 2) {
            return intent(DecisionIntentType.UNSUPPORTED, null, supplierId, null, List.of(), null, List.of(), null,
                    null, null, null, null, null,
                    "方案对比至少需要 2 个不同的延期天数，例如 3、5、7 天。");
        }
        if (numbers.size() > MAX_COMPARISON_CANDIDATES) {
            return intent(DecisionIntentType.UNSUPPORTED, null, supplierId, null, List.of(), null, List.of(), null,
                    null, null, null, null, null, "方案对比最多支持 " + MAX_COMPARISON_CANDIDATES + " 个候选。");
        }
        Set<Integer> unique = new LinkedHashSet<>(numbers);
        if (unique.size() != numbers.size()) {
            return intent(DecisionIntentType.UNSUPPORTED, null, supplierId, null, List.of(), null, List.of(), null,
                    null, null, null, null, null, "对比候选的延期天数必须互不相同。");
        }
        if (!compareVerb && !simulateVerb) {
            return intent(DecisionIntentType.UNSUPPORTED, null, supplierId, null, List.of(), null, List.of(), null,
                    null, null, null, null, null,
                    "请说明是模拟单个延期天数，还是比较多个延期天数方案。");
        }
        return intent(DecisionIntentType.COMPARE_SUPPLIER_DELAY, null, supplierId, null, List.of(), null, numbers,
                null, null, null, null, null,
                "比较供应商 " + supplierId + " 的延期方案：" + joinNumbers(numbers, " 天"), null);
    }

    private DecisionIntent replenishmentIntent(String text, String sku, List<Integer> numbers, boolean compareVerb,
            boolean simulateVerb) {
        if (!StringUtils.hasText(sku)) {
            return intent(DecisionIntentType.UNSUPPORTED, null, null, null, List.of(), null, List.of(), null, null,
                    null, null, null, null,
                    "补货模拟或对比需要明确的 SKU（例如 SKU-MCU-001）。请补充要分析的 SKU。");
        }
        if (numbers.isEmpty()) {
            return intent(DecisionIntentType.UNSUPPORTED, sku, null, null, List.of(), null, List.of(), null, null,
                    null, null, null, null,
                    "请提供 SKU " + sku + " 的补货数量，或 2-5 个用于比较的补货数量。");
        }
        String validation = validateNumbers(numbers, MAX_REPLENISHMENT_QUANTITY, "补货数量");
        if (validation != null) {
            return intent(DecisionIntentType.UNSUPPORTED, sku, null, null, List.of(), null, List.of(), null, null,
                    null, null, null, null, validation);
        }
        if (numbers.size() == 1 && !compareVerb) {
            return intent(DecisionIntentType.SIMULATE_REPLENISHMENT, sku, null, numbers.get(0), List.of(), null,
                    List.of(), null, null, null, null, null, null, null);
        }
        if (numbers.size() < 2) {
            return intent(DecisionIntentType.UNSUPPORTED, sku, null, null, List.of(), null, List.of(), null, null,
                    null, null, null, null, "补货方案对比至少需要 2 个不同的补货数量，例如 50、100、200 件。");
        }
        if (numbers.size() > MAX_COMPARISON_CANDIDATES) {
            return intent(DecisionIntentType.UNSUPPORTED, sku, null, null, List.of(), null, List.of(), null, null,
                    null, null, null, null, "方案对比最多支持 " + MAX_COMPARISON_CANDIDATES + " 个候选。");
        }
        Set<Integer> unique = new LinkedHashSet<>(numbers);
        if (unique.size() != numbers.size()) {
            return intent(DecisionIntentType.UNSUPPORTED, sku, null, null, List.of(), null, List.of(), null, null,
                    null, null, null, null, "对比候选的补货数量必须互不相同。");
        }
        if (!compareVerb && !simulateVerb) {
            return intent(DecisionIntentType.UNSUPPORTED, sku, null, null, List.of(), null, List.of(), null, null,
                    null, null, null, null,
                    "请说明是模拟单个补货数量，还是比较多个补货数量方案。");
        }
        return intent(DecisionIntentType.COMPARE_REPLENISHMENT, sku, null, null, numbers, null, List.of(), null,
                null, null, null, null, "比较 " + sku + " 的补货方案：" + joinNumbers(numbers, " 件"), null);
    }

    private DecisionIntent recommendationIntent(String text, String sku, String supplierId) {
        boolean global = containsAny(text, "全部", "所有", "整体", "当前", "有哪些", "现存", "现有");
        if (!StringUtils.hasText(sku) && !StringUtils.hasText(supplierId) && !global) {
            return intent(DecisionIntentType.DISPOSAL_RECOMMENDATIONS, null, null, null, List.of(), null,
                    List.of(), null, null, null, null, null, null,
                    "查询处置建议需要明确的 SKU、供应商编号，或说明要查看当前全部范围。");
        }
        return intent(DecisionIntentType.DISPOSAL_RECOMMENDATIONS, sku, supplierId, null, List.of(), null,
                List.of(), null, null, null, null, null, null, null);
    }

    private static String pronounClarification(String text, String sku, String supplierId) {
        if (!StringUtils.hasText(sku) && text.matches(".*(这个|该|上述|前述|它)\\s*(sku|物料|产品).*")) {
            return "请提供要分析的明确 SKU（例如 SKU-MCU-001），不能根据“这个 SKU”猜测对象。";
        }
        if (!StringUtils.hasText(supplierId) && text.matches(".*(这个|该|上述|前述|它)\\s*(供应商|supplier).*")) {
            return "请提供要分析的明确供应商编号（例如 SUP-ALPHA），不能根据“该供应商”猜测对象。";
        }
        if (text.contains("上次") && !text.contains("SKU-") && !text.contains("SUP-")
                && text.indexOf('-') < 0) {
            return "请提供上次对比、方案或执行记录的明确编号，不能根据“上次”自动选择记录。";
        }
        return null;
    }

    private static Map<String, String> classifyIdentifiers(String text, List<String> uuids) {
        Map<String, String> identifiers = new LinkedHashMap<>();
        Map<String, List<String>> labels = Map.of("candidate", List.of("候选", "candidate"),
                "recommendation", List.of("推荐", "建议", "recommendation"),
                "comparison", List.of("对比", "比较", "comparison"),
                "plan", List.of("方案", "计划", "plan"),
                "execution", List.of("执行", "演练", "execution"));
        for (String uuid : uuids) {
            int position = text.indexOf(uuid);
            String bestGroup = null;
            int bestDistance = Integer.MAX_VALUE;
            for (Map.Entry<String, List<String>> entry : labels.entrySet()) {
                int distance = labelDistance(text, position, uuid.length(), entry.getValue());
                if (distance < bestDistance && distance <= 40) {
                    bestDistance = distance;
                    bestGroup = entry.getKey();
                }
            }
            if (bestGroup != null) {
                identifiers.putIfAbsent(bestGroup, uuid);
            }
        }
        return identifiers;
    }

    private static int labelDistance(String text, int uuidPosition, int uuidLength, List<String> labels) {
        int best = Integer.MAX_VALUE;
        for (String label : labels) {
            int index = text.indexOf(label);
            while (index >= 0) {
                int distance;
                if (index + label.length() <= uuidPosition) {
                    distance = uuidPosition - (index + label.length());
                }
                else if (index >= uuidPosition + uuidLength) {
                    distance = index - (uuidPosition + uuidLength);
                }
                else {
                    distance = 0;
                }
                best = Math.min(best, distance);
                index = text.indexOf(label, index + 1);
            }
        }
        return best;
    }

    private static List<Integer> numbersNear(String text, List<String> keywords) {
        String cleaned = DATE_PATTERN.matcher(UUID_PATTERN.matcher(SKU_PATTERN.matcher(SUPPLIER_PATTERN
            .matcher(text).replaceAll(" ")).replaceAll(" ")).replaceAll(" ")).replaceAll(" ");
        for (String keyword : keywords) {
            int index = cleaned.indexOf(keyword);
            while (index >= 0) {
                List<Integer> after = integersIn(segmentAfter(cleaned, index + keyword.length(), 30));
                if (!after.isEmpty()) {
                    return after;
                }
                List<Integer> before = integersIn(segmentBefore(cleaned, index, 30));
                if (!before.isEmpty()) {
                    return before;
                }
                index = cleaned.indexOf(keyword, index + keyword.length());
            }
        }
        return List.of();
    }

    private static String segmentAfter(String text, int start, int maxLength) {
        if (start >= text.length()) {
            return "";
        }
        String segment = text.substring(start, Math.min(text.length(), start + maxLength));
        return cutAtBoundary(segment);
    }

    private static String segmentBefore(String text, int end, int maxLength) {
        String segment = text.substring(Math.max(0, end - maxLength), end);
        int boundary = Math.max(segment.lastIndexOf('。'), Math.max(segment.lastIndexOf(';'),
                Math.max(segment.lastIndexOf('；'), segment.lastIndexOf('\n'))));
        return boundary >= 0 ? segment.substring(boundary + 1) : segment;
    }

    private static String cutAtBoundary(String segment) {
        int boundary = segment.length();
        for (char marker : new char[] { '。', ';', '；', '\n' }) {
            int index = segment.indexOf(marker);
            if (index >= 0) {
                boundary = Math.min(boundary, index);
            }
        }
        return segment.substring(0, boundary);
    }

    private static List<Integer> integersIn(String segment) {
        List<Integer> numbers = new ArrayList<>();
        Matcher matcher = INTEGER_PATTERN.matcher(segment);
        while (matcher.find()) {
            try {
                numbers.add(Integer.parseInt(matcher.group()));
            }
            catch (NumberFormatException exception) {
                return List.of();
            }
        }
        return numbers;
    }

    private static String validateNumbers(List<Integer> numbers, int max, String field) {
        for (int number : numbers) {
            if (number < 1 || number > max) {
                return field + "必须在 1 到 " + max + " 之间。";
            }
        }
        return null;
    }

    private static String joinNumbers(List<Integer> numbers, String suffix) {
        return String.join("、", numbers.stream().map(String::valueOf).toList()) + suffix;
    }

    private static DecisionIntent unsupported(String clarification) {
        return intent(DecisionIntentType.UNSUPPORTED, null, null, null, List.of(), null, List.of(), null, null, null,
                null, null, null, clarification);
    }

    private static DecisionIntent intent(DecisionIntentType type, String sku, String supplierId,
            Integer replenishmentQuantity, List<Integer> replenishmentQuantities, Integer additionalDelayDays,
            List<Integer> additionalDelayDaysOptions, String comparisonId, String candidateId, String planId,
            String recommendationId, String executionId, String title, String clarification) {
        return new DecisionIntent(type, sku, supplierId, replenishmentQuantity, replenishmentQuantities,
                additionalDelayDays, additionalDelayDaysOptions, comparisonId, candidateId, planId, recommendationId,
                executionId, title, clarification);
    }

    private static String normalize(String question) {
        StringBuilder normalized = new StringBuilder(question.length());
        for (char character : question.trim().toCharArray()) {
            if (character >= '０' && character <= '９') {
                normalized.append((char) ('0' + (character - '０')));
            }
            else if (character == '，' || character == '、' || character == '；') {
                normalized.append(',');
            }
            else {
                normalized.append(character);
            }
        }
        return normalized.toString().toLowerCase(Locale.ROOT);
    }

    private static String firstMatch(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group().toUpperCase(Locale.ROOT) : null;
    }

    private static List<String> findAll(Pattern pattern, String text) {
        List<String> matches = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            matches.add(matcher.group());
        }
        return matches;
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
