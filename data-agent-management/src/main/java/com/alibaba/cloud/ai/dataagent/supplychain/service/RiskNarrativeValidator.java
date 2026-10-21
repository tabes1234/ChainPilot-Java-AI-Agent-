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
package com.alibaba.cloud.ai.dataagent.supplychain.service;

import com.alibaba.cloud.ai.dataagent.supplychain.model.OrderRiskAssessment;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskEvidence;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskNarrative;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates a model's structured risk explanation against the deterministic snapshot.
 * Invalid output is rejected rather than merely displayed with a warning.
 */
@Component
public class RiskNarrativeValidator {

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");

    private static final Pattern EXECUTION_CLAIM = Pattern.compile("已执行|已经执行|已实际执行|真实数据已变更|已修改真实");

    private final ObjectMapper mapper;

    public RiskNarrativeValidator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public RiskNarrative validate(String raw, String taskId, List<OrderRiskAssessment> assessments,
            List<String> allowedRecommendations) {
        RiskNarrative narrative;
        try {
            narrative = mapper.readValue(extractJson(raw), RiskNarrative.class);
        }
        catch (Exception exception) {
            throw new InvalidNarrativeException("INVALID_JSON");
        }
        if (!StringUtils.hasText(narrative.overview())) {
            throw new InvalidNarrativeException("EMPTY_OVERVIEW");
        }

        Map<String, OrderRiskAssessment> byOrder = new LinkedHashMap<>();
        assessments.forEach(assessment -> byOrder.put(assessment.order().orderId(), assessment));
        if (byOrder.isEmpty()) {
            if (!narrative.reasons().isEmpty()) {
                throw new InvalidNarrativeException("UNEXPECTED_EVIDENCE");
            }
        }
        else if (narrative.reasons().isEmpty()) {
            throw new InvalidNarrativeException("MISSING_REASONS");
        }

        Set<String> coveredOrders = new LinkedHashSet<>();
        for (RiskNarrative.Reason reason : narrative.reasons()) {
            if (!StringUtils.hasText(reason.orderId()) || !byOrder.containsKey(reason.orderId())) {
                throw new InvalidNarrativeException("UNKNOWN_ORDER");
            }
            if (!StringUtils.hasText(reason.summary())) {
                throw new InvalidNarrativeException("EMPTY_REASON");
            }
            if (reason.evidenceIds().isEmpty()) {
                throw new InvalidNarrativeException("MISSING_EVIDENCE_ID");
            }
            Set<String> availableIds = byOrder.get(reason.orderId())
                .evidence()
                .stream()
                .map(RiskEvidence::code)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            reason.evidenceIds().forEach(id -> {
                if (!availableIds.contains(id)) {
                    throw new InvalidNarrativeException("UNKNOWN_EVIDENCE");
                }
            });
            coveredOrders.add(reason.orderId());
        }
        if (!byOrder.isEmpty() && !coveredOrders.containsAll(byOrder.keySet())) {
            throw new InvalidNarrativeException("MISSING_RISK_ORDER");
        }

        Set<String> allowed = allowedRecommendations.stream()
            .filter(StringUtils::hasText)
            .map(RiskNarrativeValidator::normalize)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (String recommendation : narrative.recommendations()) {
            if (!allowed.contains(normalize(recommendation))) {
                throw new InvalidNarrativeException("UNKNOWN_RECOMMENDATION");
            }
        }

        List<String> dataGaps = assessments.stream()
            .flatMap(assessment -> assessment.dataGaps().stream())
            .toList();
        Set<String> uncertaintySet = narrative.uncertainties()
            .stream()
            .map(RiskNarrativeValidator::normalize)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (String gap : dataGaps) {
            if (!uncertaintySet.contains(normalize(gap))) {
                throw new InvalidNarrativeException("MISSING_DATA_GAP");
            }
        }

        Set<String> allowedNumbers = allowedNumbers(taskId, assessments, allowedRecommendations);
        List<String> prose = new java.util.ArrayList<>();
        prose.add(narrative.overview());
        narrative.reasons().forEach(reason -> prose.add(reason.summary()));
        prose.addAll(narrative.businessImpacts());
        prose.addAll(narrative.uncertainties());
        for (String text : prose) {
            if (EXECUTION_CLAIM.matcher(text).find()) {
                throw new InvalidNarrativeException("EXECUTION_CLAIM");
            }
            Matcher matcher = NUMBER.matcher(text);
            while (matcher.find()) {
                if (!allowedNumbers.contains(matcher.group())) {
                    throw new InvalidNarrativeException("NUMERIC_CLAIM_NOT_IN_EVIDENCE");
                }
            }
        }
        return narrative;
    }

    private Set<String> allowedNumbers(String taskId, List<OrderRiskAssessment> assessments,
            List<String> allowedRecommendations) {
        Set<String> values = new LinkedHashSet<>();
        values.add("0");
        values.add(String.valueOf(assessments.size()));
        values.add(String.valueOf(allowedRecommendations.size()));
        addNumbers(values, taskId);
        try {
            addNumbers(values, mapper.writeValueAsString(assessments));
        }
        catch (Exception ignored) {
            // Validation will fail closed only if a model supplies an ungrounded number.
        }
        allowedRecommendations.forEach(value -> addNumbers(values, value));
        return values;
    }

    private static void addNumbers(Set<String> values, String text) {
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find()) {
            values.add(matcher.group());
        }
    }

    private static String extractJson(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim();
        if (value.startsWith("```")) {
            int firstLine = value.indexOf('\n');
            int lastFence = value.lastIndexOf("```");
            if (firstLine >= 0 && lastFence > firstLine) {
                value = value.substring(firstLine + 1, lastFence).trim();
            }
        }
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        return start >= 0 && end > start ? value.substring(start, end + 1) : value;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    public static class InvalidNarrativeException extends RuntimeException {

        private final String reason;

        public InvalidNarrativeException(String reason) {
            super(reason);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }
}
