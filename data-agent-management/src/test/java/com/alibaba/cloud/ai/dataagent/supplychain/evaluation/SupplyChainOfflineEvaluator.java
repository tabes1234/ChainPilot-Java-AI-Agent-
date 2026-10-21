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

import com.alibaba.cloud.ai.dataagent.dto.ModelConfigDTO;
import com.alibaba.cloud.ai.dataagent.entity.ModelConfig;
import com.alibaba.cloud.ai.dataagent.enums.ModelType;
import com.alibaba.cloud.ai.dataagent.service.aimodelconfig.AiModelRegistry;
import com.alibaba.cloud.ai.dataagent.service.aimodelconfig.DynamicModelFactory;
import com.alibaba.cloud.ai.dataagent.service.aimodelconfig.ModelConfigDataService;
import com.alibaba.cloud.ai.dataagent.supplychain.evaluation.RetrievalMetrics.RetrievalObservation;
import com.alibaba.cloud.ai.dataagent.supplychain.evaluation.SupplyChainEvaluationDataset.EvaluationCase;
import com.alibaba.cloud.ai.dataagent.supplychain.evaluation.SupplyChainEvaluationDataset.InventoryExpectation;
import com.alibaba.cloud.ai.dataagent.supplychain.evaluation.SupplyChainEvaluationDataset.ReportExpectation;
import com.alibaba.cloud.ai.dataagent.supplychain.evaluation.SupplyChainEvaluationDataset.RiskExpectation;
import com.alibaba.cloud.ai.dataagent.supplychain.evaluation.SupplyChainEvaluationDataset.SupplierExpectation;
import com.alibaba.cloud.ai.dataagent.supplychain.model.InventoryPosition;
import com.alibaba.cloud.ai.dataagent.supplychain.model.KnowledgeCitation;
import com.alibaba.cloud.ai.dataagent.supplychain.model.OrderRiskAssessment;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskAnalysisReport;
import com.alibaba.cloud.ai.dataagent.supplychain.model.RiskLevel;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplierRiskProfile;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainEventType;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskEvent;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskState;
import com.alibaba.cloud.ai.dataagent.supplychain.model.SupplyChainTaskStatus;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.DisposalSimulationRepository;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.SupplyChainDemoData;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.SupplyChainEventRepository;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.SupplyChainTaskRepository;
import com.alibaba.cloud.ai.dataagent.supplychain.service.DisposalExecutionService;
import com.alibaba.cloud.ai.dataagent.supplychain.service.DisposalPlanService;
import com.alibaba.cloud.ai.dataagent.supplychain.service.DisposalRecommendationService;
import com.alibaba.cloud.ai.dataagent.supplychain.service.DisposalScenarioComparisonService;
import com.alibaba.cloud.ai.dataagent.supplychain.service.DisposalSimulationService;
import com.alibaba.cloud.ai.dataagent.supplychain.service.SupplyChainAgentTools;
import com.alibaba.cloud.ai.dataagent.supplychain.service.SupplyChainAnalysisService;
import com.alibaba.cloud.ai.dataagent.supplychain.service.RiskNarrativeValidator;
import com.alibaba.cloud.ai.dataagent.supplychain.service.SupplyChainDecisionService;
import com.alibaba.cloud.ai.dataagent.supplychain.service.SupplyChainEventRecorder;
import com.alibaba.cloud.ai.dataagent.supplychain.service.SupplyChainKnowledgeService;
import com.alibaba.cloud.ai.dataagent.supplychain.service.SupplyChainQueryService;
import com.alibaba.cloud.ai.dataagent.supplychain.service.SupplyChainWorkflowService;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.mockito.Mockito.mock;

/**
 * Runs the hand-curated dataset through the production supply-chain services. No business
 * result is stubbed: orders, inventory, suppliers, risk scoring, lexical retrieval, the
 * StateGraph workflow, task persistence and event recording are all executed for real.
 *
 * <p>The only stubs sit at absent external capabilities: no {@link VectorStore} bean is
 * provided and no model configuration exists, so the evaluator exercises the same lexical
 * and deterministic fallback paths as a local installation without those integrations. It
 * deliberately does not evaluate real model generation quality.
 */
public final class SupplyChainOfflineEvaluator {

    private final SupplyChainEvaluationDataset dataset;

    private final SupplyChainQueryService queryService;

    private final SupplyChainKnowledgeService knowledgeService;

    private final SupplyChainWorkflowService workflowService;

    public SupplyChainOfflineEvaluator(ObjectMapper mapper, SupplyChainEvaluationDataset dataset)
            throws GraphStateException {
        this.dataset = dataset;
        SupplyChainDemoData demoData = new SupplyChainDemoData();
        this.queryService = new SupplyChainQueryService(demoData);

        ObservationRegistry observations = ObservationRegistry.create();
        ResourcePatternResolver resources = new PathMatchingResourcePatternResolver();
        ObjectProvider<VectorStore> noVectorStoreProvider = new StaticListableBeanFactory()
            .getBeanProvider(VectorStore.class);
        this.knowledgeService = new SupplyChainKnowledgeService(noVectorStoreProvider, resources);

        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:chainpilot-evaluation-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        SupplyChainTaskRepository taskRepository = new SupplyChainTaskRepository(jdbc, mapper);
        SupplyChainEventRepository eventRepository = new SupplyChainEventRepository(jdbc);
        SupplyChainEventRecorder eventRecorder = new SupplyChainEventRecorder(eventRepository);

        AiModelRegistry modelRegistry = new AiModelRegistry(new DynamicModelFactory(observations),
                new NoModelConfiguration(), observations);
        SupplyChainAgentTools agentTools = new SupplyChainAgentTools(queryService,
                new DisposalRecommendationService(queryService),
                new DisposalSimulationService(queryService, new DisposalSimulationRepository(jdbc, mapper)),
                mock(DisposalExecutionService.class), mock(DisposalScenarioComparisonService.class),
                mock(DisposalPlanService.class), eventRecorder);
        SupplyChainAnalysisService analysisService = new SupplyChainAnalysisService(queryService, agentTools,
                modelRegistry, knowledgeService, eventRecorder, observations,
                new RiskNarrativeValidator(mapper), mapper);
        this.workflowService = new SupplyChainWorkflowService(agentTools, analysisService,
                mock(SupplyChainDecisionService.class), mapper, taskRepository, eventRepository, eventRecorder,
                Runnable::run);
    }

    public EvaluationOutcome evaluate() {
        List<OrderRiskAssessment> allOrders = queryService.findOrders(null, null);
        Map<String, OrderRiskAssessment> ordersById = allOrders.stream()
            .collect(Collectors.toMap(assessment -> assessment.order().orderId(), Function.identity(),
                    (first, second) -> first, LinkedHashMap::new));
        Set<String> evaluatedOrderIds = dataset.cases().stream()
            .filter(evaluationCase -> evaluationCase.kind() == SupplyChainEvaluationDataset.Kind.ORDER_RISK)
            .map(EvaluationCase::targetId)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> expectedAtRisk = dataset.cases().stream()
            .filter(evaluationCase -> evaluationCase.kind() == SupplyChainEvaluationDataset.Kind.ORDER_RISK)
            .filter(evaluationCase -> Boolean.TRUE.equals(evaluationCase.risk().atRisk()))
            .map(EvaluationCase::targetId)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> predictedAtRisk = allOrders.stream()
            .filter(assessment -> assessment.riskLevel() != RiskLevel.LOW)
            .map(assessment -> assessment.order().orderId())
            .collect(Collectors.toCollection(LinkedHashSet::new));
        RiskClassificationMetrics riskMetrics = RiskClassificationMetrics.calculate(expectedAtRisk,
                predictedAtRisk, evaluatedOrderIds);

        List<EvaluationCaseOutcome> caseOutcomes = new ArrayList<>();
        List<EvaluationCheck> factChecks = new ArrayList<>();
        List<RetrievalObservation> retrievalObservations = new ArrayList<>();
        for (EvaluationCase evaluationCase : dataset.cases()) {
            switch (evaluationCase.kind()) {
                case ORDER_RISK -> {
                    List<EvaluationCheck> checks = orderRiskChecks(evaluationCase,
                            ordersById.get(evaluationCase.targetId()));
                    caseOutcomes.add(EvaluationCaseOutcome.of(evaluationCase.id(), evaluationCase.kind(), checks));
                    factChecks.addAll(checks);
                }
                case INVENTORY -> {
                    List<EvaluationCheck> checks = inventoryChecks(evaluationCase);
                    caseOutcomes.add(EvaluationCaseOutcome.of(evaluationCase.id(), evaluationCase.kind(), checks));
                    factChecks.addAll(checks);
                }
                case SUPPLIER -> {
                    List<EvaluationCheck> checks = supplierChecks(evaluationCase);
                    caseOutcomes.add(EvaluationCaseOutcome.of(evaluationCase.id(), evaluationCase.kind(), checks));
                    factChecks.addAll(checks);
                }
                case RETRIEVAL -> {
                    List<String> sources = knowledgeService.retrieve(evaluationCase.question()).stream()
                        .map(KnowledgeCitation::sourceId)
                        .toList();
                    List<EvaluationCheck> checks = retrievalChecks(evaluationCase, sources);
                    caseOutcomes.add(EvaluationCaseOutcome.of(evaluationCase.id(), evaluationCase.kind(), checks));
                    retrievalObservations.add(
                            new RetrievalObservation(evaluationCase.retrieval().expectedSourceIds(), sources));
                }
                case REPORT_FALLBACK -> {
                    List<EvaluationCheck> checks = reportFallbackChecks(evaluationCase);
                    caseOutcomes.add(EvaluationCaseOutcome.of(evaluationCase.id(), evaluationCase.kind(), checks));
                    factChecks.addAll(checks);
                }
            }
        }
        return EvaluationOutcome.summarize(dataset.datasetVersion(), factChecks, riskMetrics,
                RetrievalMetrics.calculate(retrievalObservations), caseOutcomes);
    }

    private List<EvaluationCheck> orderRiskChecks(EvaluationCase evaluationCase, OrderRiskAssessment actual) {
        List<EvaluationCheck> checks = new ArrayList<>();
        RiskExpectation expected = evaluationCase.risk();
        if (actual == null) {
            checks.add(new EvaluationCheck("order.present", evaluationCase.targetId(), "missing", false));
            return checks;
        }
        checks.add(EvaluationCheck.equal("risk.level", expected.level().name(), actual.riskLevel().name()));
        checks.add(EvaluationCheck.equal("risk.score", expected.score(), actual.riskScore()));
        checks.add(EvaluationCheck.equal("risk.shortage", expected.shortage(), actual.inventoryShortage()));
        checks.add(EvaluationCheck.equal("risk.atRisk", expected.atRisk(), actual.riskLevel() != RiskLevel.LOW));
        String factors = String.join(" | ", actual.riskFactors());
        for (int index = 0; index < expected.factorFragments().size(); index++) {
            checks.add(EvaluationCheck.contains("risk.factor[" + index + "]",
                    expected.factorFragments().get(index), factors));
        }
        String actions = String.join(" | ", actual.recommendedActions());
        for (int index = 0; index < expected.actionFragments().size(); index++) {
            checks.add(EvaluationCheck.contains("risk.action[" + index + "]",
                    expected.actionFragments().get(index), actions));
        }
        return checks;
    }

    private List<EvaluationCheck> inventoryChecks(EvaluationCase evaluationCase) {
        List<EvaluationCheck> checks = new ArrayList<>();
        InventoryExpectation expected = evaluationCase.inventory();
        List<InventoryPosition> matches = queryService.findInventory(evaluationCase.targetId());
        if (matches.isEmpty()) {
            checks.add(new EvaluationCheck("inventory.present", evaluationCase.targetId(), "missing", false));
            return checks;
        }
        InventoryPosition actual = matches.get(0);
        checks.add(EvaluationCheck.equal("inventory.onHandQuantity", expected.onHandQuantity(),
                actual.onHandQuantity()));
        checks.add(EvaluationCheck.equal("inventory.reservedQuantity", expected.reservedQuantity(),
                actual.reservedQuantity()));
        checks.add(EvaluationCheck.equal("inventory.safetyStock", expected.safetyStock(), actual.safetyStock()));
        checks.add(EvaluationCheck.equal("inventory.availableToPromise", expected.availableToPromise(),
                actual.availableToPromise()));
        return checks;
    }

    private List<EvaluationCheck> supplierChecks(EvaluationCase evaluationCase) {
        List<EvaluationCheck> checks = new ArrayList<>();
        SupplierExpectation expected = evaluationCase.supplier();
        SupplierRiskProfile actual = queryService.findSupplier(evaluationCase.targetId()).orElse(null);
        if (actual == null) {
            checks.add(new EvaluationCheck("supplier.present", evaluationCase.targetId(), "missing", false));
            return checks;
        }
        checks.add(EvaluationCheck.equal("supplier.level", expected.level().name(), actual.riskLevel().name()));
        checks.add(EvaluationCheck.close("supplier.onTimeDeliveryRate", expected.onTimeDeliveryRate(),
                actual.onTimeDeliveryRate()));
        checks.add(EvaluationCheck.close("supplier.defectRate", expected.defectRate(), actual.defectRate()));
        return checks;
    }

    private List<EvaluationCheck> retrievalChecks(EvaluationCase evaluationCase, List<String> actualSourceIds) {
        List<EvaluationCheck> checks = new ArrayList<>();
        for (int index = 0; index < evaluationCase.retrieval().expectedSourceIds().size(); index++) {
            checks.add(EvaluationCheck.containsElement("retrieval.hitAt3[" + index + "]",
                    evaluationCase.retrieval().expectedSourceIds().get(index), actualSourceIds));
        }
        return checks;
    }

    private List<EvaluationCheck> reportFallbackChecks(EvaluationCase evaluationCase) {
        List<EvaluationCheck> checks = new ArrayList<>();
        ReportExpectation expected = evaluationCase.report();
        RiskAnalysisReport report = workflowService.analyze(evaluationCase.question());
        SupplyChainTaskStatus task = workflowService.task(report.taskId());
        RiskAnalysisReport persisted = workflowService.report(report.taskId());
        List<SupplyChainTaskEvent> events = workflowService.events(report.taskId());
        List<String> actualOrderIds = report.atRiskOrders().stream()
            .map(assessment -> assessment.order().orderId())
            .toList();
        List<String> actualCitationIds = report.citations().stream().map(KnowledgeCitation::sourceId).toList();

        checks.add(EvaluationCheck.equal("report.status", expected.status(), report.status()));
        checks.add(EvaluationCheck.equal("report.atRiskOrderIds", expected.atRiskOrderIds(), actualOrderIds));
        for (int index = 0; index < expected.summaryFragments().size(); index++) {
            checks.add(EvaluationCheck.contains("report.summary[" + index + "]",
                    expected.summaryFragments().get(index), report.summary()));
        }
        for (int index = 0; index < expected.citationSourceIds().size(); index++) {
            checks.add(EvaluationCheck.containsElement("report.citationSourceIds[" + index + "]",
                    expected.citationSourceIds().get(index), actualCitationIds));
        }
        String recommendations = String.join(" | ", report.recommendations());
        for (int index = 0; index < expected.recommendationFragments().size(); index++) {
            checks.add(EvaluationCheck.contains("report.recommendation[" + index + "]",
                    expected.recommendationFragments().get(index), recommendations));
        }
        checks.add(EvaluationCheck.equal("report.executionPlan", expected.planSteps(), report.executionPlan()));
        checks.add(EvaluationCheck.equal("report.plannerAssisted", expected.plannerAssisted(),
                report.plannerAssisted()));
        checks.add(EvaluationCheck.equal("report.modelAssisted", expected.modelAssisted(), report.modelAssisted()));
        checks.add(EvaluationCheck.equal("report.toolCallsEmpty", expected.toolCallsEmpty(),
                report.toolCalls().isEmpty()));
        checks.add(EvaluationCheck.equal("report.validationAttempts", expected.validationAttempts(),
                report.validationAttempts()));

        checks.add(EvaluationCheck.equal("events.MODEL_FAILED", expected.modelFailedEvents().longValue(),
                countEvents(events, SupplyChainEventType.MODEL_FAILED)));
        checks.add(EvaluationCheck.equal("events.FALLBACK_USED", expected.fallbackEvents().longValue(),
                countEvents(events, SupplyChainEventType.FALLBACK_USED)));
        checks.add(EvaluationCheck.equal("events.MODEL_COMPLETED", expected.modelCompletedEvents().longValue(),
                countEvents(events, SupplyChainEventType.MODEL_COMPLETED)));
        checks.add(EvaluationCheck.equal("events.TASK_COMPLETED", expected.taskCompleted(),
                countEvents(events, SupplyChainEventType.TASK_COMPLETED) == 1));
        checks.add(EvaluationCheck.equal("events.TASK_FAILED", expected.taskFailed(),
                countEvents(events, SupplyChainEventType.TASK_FAILED) > 0));
        checks.add(EvaluationCheck.equal("task.status", SupplyChainTaskState.COMPLETED, task.status()));
        checks.add(EvaluationCheck.equal("task.reportPersisted", true, report.equals(persisted)));
        return checks;
    }

    private static long countEvents(List<SupplyChainTaskEvent> events, String type) {
        return events.stream().filter(event -> type.equals(event.type())).count();
    }

    private static final class NoModelConfiguration implements ModelConfigDataService {

        @Override
        public ModelConfig findById(Integer id) {
            return null;
        }

        @Override
        public void switchActiveStatus(Integer id, ModelType type) {
            throw new UnsupportedOperationException("The offline evaluator does not modify model configuration");
        }

        @Override
        public List<ModelConfigDTO> listConfigs() {
            return List.of();
        }

        @Override
        public void addConfig(ModelConfigDTO dto) {
            throw new UnsupportedOperationException("The offline evaluator does not modify model configuration");
        }

        @Override
        public ModelConfig updateConfigInDb(ModelConfigDTO dto) {
            throw new UnsupportedOperationException("The offline evaluator does not modify model configuration");
        }

        @Override
        public void deleteConfig(Integer id) {
            throw new UnsupportedOperationException("The offline evaluator does not modify model configuration");
        }

        @Override
        public ModelConfigDTO getActiveConfigByType(ModelType modelType) {
            return null;
        }

    }

}
