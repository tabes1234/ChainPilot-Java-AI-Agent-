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
import com.alibaba.cloud.ai.dataagent.supplychain.evaluation.SupplyChainEvaluationDataset.InventoryExpectation;
import com.alibaba.cloud.ai.dataagent.supplychain.evaluation.SupplyChainEvaluationDataset.Kind;
import com.alibaba.cloud.ai.dataagent.supplychain.evaluation.SupplyChainEvaluationDataset.RetrievalExpectation;
import com.alibaba.cloud.ai.dataagent.supplychain.repository.SupplyChainDemoData;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupplyChainEvaluationDatasetTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void curatedDatasetLoadsAndCoversEveryDemoEntity() throws IOException {
        SupplyChainEvaluationDataset dataset = load();
        SupplyChainDemoData demoData = new SupplyChainDemoData();

        SupplyChainEvaluationDatasetValidator.validate(dataset,
                demoData, SupplyChainEvaluationDatasetLoader.knowledgeSourceIds());

        assertEquals("1.0.0", dataset.datasetVersion());
        assertEquals(15, dataset.cases().size());
        assertEquals(4, count(dataset, Kind.ORDER_RISK));
        assertEquals(4, count(dataset, Kind.INVENTORY));
        assertEquals(3, count(dataset, Kind.SUPPLIER));
        assertEquals(3, count(dataset, Kind.RETRIEVAL));
        assertEquals(1, count(dataset, Kind.REPORT_FALLBACK));

        Set<String> orderTargets = targets(dataset, Kind.ORDER_RISK);
        assertEquals(demoData.orders().stream().map(order -> order.orderId())
            .collect(Collectors.toSet()), orderTargets);
        assertEquals(demoData.inventory().stream().map(item -> item.sku())
            .collect(Collectors.toSet()), targets(dataset, Kind.INVENTORY));
        assertEquals(demoData.suppliers().stream().map(supplier -> supplier.supplierId())
            .collect(Collectors.toSet()), targets(dataset, Kind.SUPPLIER));
        assertEquals(dataset.cases().size(),
                dataset.cases().stream().map(EvaluationCase::id).distinct().count());
        assertTrue(dataset.cases().stream().allMatch(evaluationCase -> !evaluationCase.basis().isBlank()));
    }

    @Test
    void duplicateCaseIdsAreRejectedBeforeScoring() throws IOException {
        SupplyChainEvaluationDataset dataset = load();
        EvaluationCase first = dataset.cases().get(0);
        EvaluationCase second = dataset.cases().get(1);
        EvaluationCase duplicate = copyWithId(first, second.id());

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> validate(replace(dataset, 0, duplicate)));

        assertTrue(failure.getMessage().contains("duplicate case id"), failure.getMessage());
    }

    @Test
    void inventoryExpectationMustSatisfyTheAvailableToPromiseFormula() throws IOException {
        SupplyChainEvaluationDataset dataset = load();
        int index = indexOf(dataset, Kind.INVENTORY);
        EvaluationCase original = dataset.cases().get(index);
        InventoryExpectation originalInventory = original.inventory();
        InventoryExpectation invalid = new InventoryExpectation(originalInventory.onHandQuantity(),
                originalInventory.reservedQuantity(), originalInventory.safetyStock(), 999);
        EvaluationCase mutated = new EvaluationCase(original.id(), original.kind(), original.scenario(),
                original.question(), original.targetId(), original.risk(), invalid, original.supplier(),
                original.retrieval(), original.report(), original.basis());

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> validate(replace(dataset, index, mutated)));

        assertTrue(failure.getMessage().contains("availableToPromise must equal max(0"), failure.getMessage());
    }

    @Test
    void retrievalExpectationMustReferenceAnExistingSop() throws IOException {
        SupplyChainEvaluationDataset dataset = load();
        int index = indexOf(dataset, Kind.RETRIEVAL);
        EvaluationCase original = dataset.cases().get(index);
        EvaluationCase mutated = new EvaluationCase(original.id(), original.kind(), original.scenario(),
                original.question(), original.targetId(), original.risk(), original.inventory(),
                original.supplier(), new RetrievalExpectation(List.of("SC-SOP-999-missing.md")),
                original.report(), original.basis());

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> validate(replace(dataset, index, mutated)));

        assertTrue(failure.getMessage().contains("unknown SOP source SC-SOP-999-missing.md"),
                failure.getMessage());
    }

    private void validate(SupplyChainEvaluationDataset dataset) {
        SupplyChainEvaluationDatasetValidator.validate(dataset, new SupplyChainDemoData(),
                knowledgeSourceIds());
    }

    private static Set<String> knowledgeSourceIds() {
        try {
            return SupplyChainEvaluationDatasetLoader.knowledgeSourceIds();
        }
        catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private SupplyChainEvaluationDataset load() throws IOException {
        return SupplyChainEvaluationDatasetLoader.load(mapper);
    }

    private static long count(SupplyChainEvaluationDataset dataset, Kind kind) {
        return dataset.cases().stream().filter(evaluationCase -> evaluationCase.kind() == kind).count();
    }

    private static Set<String> targets(SupplyChainEvaluationDataset dataset, Kind kind) {
        return dataset.cases().stream().filter(evaluationCase -> evaluationCase.kind() == kind)
            .map(EvaluationCase::targetId)
            .collect(Collectors.toSet());
    }

    private static int indexOf(SupplyChainEvaluationDataset dataset, Kind kind) {
        for (int index = 0; index < dataset.cases().size(); index++) {
            if (dataset.cases().get(index).kind() == kind) {
                return index;
            }
        }
        throw new IllegalStateException("No case of kind " + kind);
    }

    private static EvaluationCase copyWithId(EvaluationCase original, String id) {
        return new EvaluationCase(id, original.kind(), original.scenario(), original.question(),
                original.targetId(), original.risk(), original.inventory(), original.supplier(),
                original.retrieval(), original.report(), original.basis());
    }

    private static SupplyChainEvaluationDataset replace(SupplyChainEvaluationDataset dataset, int index,
            EvaluationCase replacement) {
        List<EvaluationCase> cases = new ArrayList<>(dataset.cases());
        cases.set(index, replacement);
        return new SupplyChainEvaluationDataset(dataset.datasetVersion(), dataset.description(), cases);
    }

}
