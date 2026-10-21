/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { describe, expect, it, vi } from 'vitest';
import {
	actionTypeLabel,
	buildComparisonRequest,
	buildDisposalPlanQuery,
	buildSimulationRequest,
	comparisonOutcomeLabel,
	createDisposalScenarioComparison,
	createSandboxIdempotencyKey,
	DISPOSAL_PLAN_STATUS_OPTIONS,
	disposalErrorMessage,
	disposalPlanStatusLabel,
	fetchDisposalScenarioComparison,
	fetchDisposalScenarioComparisons,
	fetchSandboxExecution,
	fetchSandboxExecutionEligibility,
	fetchSandboxExecutions,
	linkComparisonCandidateToDisposalPlan,
	priorityLabel,
	sandboxDraftTypeLabel,
	sandboxExecutionStatusLabel,
	sandboxStepStatusLabel,
	saveComparisonCandidateAsDisposalPlan,
	scenarioLabel,
	startSandboxExecution,
	validateComparisonDraft,
	validateSimulationDraft,
	type ComparisonDraft,
	type SimulationDraft,
} from './supplyChainDisposal';

function replenishmentDraft(overrides: Partial<SimulationDraft> = {}): SimulationDraft {
	return {
		scenario: 'INVENTORY_REPLENISHMENT',
		sku: 'SKU-MCU-001',
		replenishmentQuantity: 100,
		supplierId: '',
		additionalDelayDays: null,
		...overrides,
	};
}

function delayDraft(overrides: Partial<SimulationDraft> = {}): SimulationDraft {
	return {
		scenario: 'SUPPLIER_DELAY',
		sku: '',
		replenishmentQuantity: null,
		supplierId: 'SUP-GAMMA',
		additionalDelayDays: 10,
		...overrides,
	};
}

function comparisonDraft(overrides: Partial<ComparisonDraft> = {}): ComparisonDraft {
	return {
		title: '风险处置补货方案对比',
		scenario: 'INVENTORY_REPLENISHMENT',
		sku: 'SKU-MCU-001',
		supplierId: '',
		candidates: [
			{ label: '补货 50 件', replenishmentQuantity: 50, additionalDelayDays: null },
			{ label: '', replenishmentQuantity: 100, additionalDelayDays: null },
			{ label: '', replenishmentQuantity: 200, additionalDelayDays: null },
		],
		...overrides,
	};
}

describe('supply chain disposal service helpers', () => {
	it('builds a replenishment request with only scenario-relevant fields', () => {
		const request = buildSimulationRequest(replenishmentDraft());

		expect(request).toEqual({
			scenario: 'INVENTORY_REPLENISHMENT',
			sku: 'SKU-MCU-001',
			replenishmentQuantity: 100,
		});
		expect(request).not.toHaveProperty('supplierId');
		expect(request).not.toHaveProperty('additionalDelayDays');
	});

	it('builds a supplier-delay request with only scenario-relevant fields', () => {
		const request = buildSimulationRequest(delayDraft());

		expect(request).toEqual({
			scenario: 'SUPPLIER_DELAY',
			supplierId: 'SUP-GAMMA',
			additionalDelayDays: 10,
		});
		expect(request).not.toHaveProperty('sku');
		expect(request).not.toHaveProperty('replenishmentQuantity');
	});

	it('accepts boundary values and trims identifiers', () => {
		expect(validateSimulationDraft(replenishmentDraft({ sku: '  SKU-MCU-001  ', replenishmentQuantity: 1 }))).toBe('');
		expect(
			validateSimulationDraft(replenishmentDraft({ replenishmentQuantity: 1_000_000 })),
		).toBe('');
		expect(validateSimulationDraft(delayDraft({ supplierId: ' SUP-GAMMA ', additionalDelayDays: 90 }))).toBe('');
		expect(buildSimulationRequest(delayDraft({ supplierId: ' SUP-GAMMA ' }))).toMatchObject({
			supplierId: 'SUP-GAMMA',
		});
	});

	it('rejects missing, non-integer and out-of-range parameters', () => {
		expect(validateSimulationDraft(replenishmentDraft({ sku: '   ' }))).toContain('SKU');
		expect(validateSimulationDraft(replenishmentDraft({ replenishmentQuantity: '' }))).toContain('整数');
		expect(validateSimulationDraft(replenishmentDraft({ replenishmentQuantity: 1.5 }))).toContain('整数');
		expect(validateSimulationDraft(replenishmentDraft({ replenishmentQuantity: 0 }))).toContain('1 到 1,000,000');
		expect(validateSimulationDraft(replenishmentDraft({ replenishmentQuantity: 1_000_001 }))).toContain('1 到 1,000,000');
		expect(validateSimulationDraft(delayDraft({ supplierId: '' }))).toContain('供应商');
		expect(validateSimulationDraft(delayDraft({ additionalDelayDays: 'abc' }))).toContain('整数');
		expect(validateSimulationDraft(delayDraft({ additionalDelayDays: 0 }))).toContain('1 到 90');
		expect(validateSimulationDraft(delayDraft({ additionalDelayDays: 91 }))).toContain('1 到 90');
		expect(() => buildSimulationRequest(replenishmentDraft({ replenishmentQuantity: 0 }))).toThrow('1 到 1,000,000');
	});

	it('accepts two to five unique comparison candidates and trims identifiers', () => {
		expect(validateComparisonDraft(comparisonDraft())).toBe('');
		expect(
			validateComparisonDraft(
				comparisonDraft({
					title: '  对比  ',
					sku: '  SKU-MCU-001  ',
					candidates: [
						{ replenishmentQuantity: 1 },
						{ replenishmentQuantity: 1_000_000 },
					],
				}),
			),
		).toBe('');
		expect(
			validateComparisonDraft(
				comparisonDraft({
					candidates: [
						{ replenishmentQuantity: 10 },
						{ replenishmentQuantity: 20 },
						{ replenishmentQuantity: 30 },
						{ replenishmentQuantity: 40 },
						{ replenishmentQuantity: 50 },
					],
				}),
			),
		).toBe('');

		const request = buildComparisonRequest(
			comparisonDraft({
				title: '  对比  ',
				sku: '  SKU-MCU-001  ',
			}),
		);

		expect(request).toEqual({
			title: '对比',
			scenario: 'INVENTORY_REPLENISHMENT',
			sku: 'SKU-MCU-001',
			candidates: [
				{ label: '补货 50 件', replenishmentQuantity: 50 },
				{ replenishmentQuantity: 100 },
				{ replenishmentQuantity: 200 },
			],
		});
	});

	it('rejects comparison drafts outside the candidate and parameter boundaries', () => {
		expect(validateComparisonDraft(comparisonDraft({ title: '   ' }))).toContain('对比标题');
		expect(validateComparisonDraft(comparisonDraft({ title: 'x'.repeat(201) }))).toContain('200');
		expect(validateComparisonDraft(comparisonDraft({ sku: '   ' }))).toContain('SKU');
		expect(
			validateComparisonDraft(comparisonDraft({ candidates: [{ replenishmentQuantity: 50 }] })),
		).toContain('至少需要 2');
		expect(
			validateComparisonDraft(
				comparisonDraft({
					candidates: Array.from({ length: 6 }, (_, index) => ({
						replenishmentQuantity: (index + 1) * 10,
					})),
				}),
			),
		).toContain('最多只能比较 5');
		expect(
			validateComparisonDraft(
				comparisonDraft({
					candidates: [{ replenishmentQuantity: 50 }, { replenishmentQuantity: 50 }],
				}),
			),
		).toContain('不能重复');
		expect(
			validateComparisonDraft(
				comparisonDraft({
					candidates: [{ replenishmentQuantity: 'abc' }, { replenishmentQuantity: 100 }],
				}),
			),
		).toContain('整数');
		expect(
			validateComparisonDraft(
				comparisonDraft({
					candidates: [{ replenishmentQuantity: 0 }, { replenishmentQuantity: 100 }],
				}),
			),
		).toContain('1 到 1,000,000');
		expect(
			validateComparisonDraft(
				comparisonDraft({
					candidates: [
						{ label: 'x'.repeat(101), replenishmentQuantity: 50 },
						{ replenishmentQuantity: 100 },
					],
				}),
			),
		).toContain('100');
		expect(() =>
			buildComparisonRequest(
				comparisonDraft({ candidates: [{ replenishmentQuantity: 50 }] }),
			),
		).toThrow('至少需要 2');
	});

	it('builds supplier-delay comparison requests with only relevant fields', () => {
		const request = buildComparisonRequest(
			comparisonDraft({
				scenario: 'SUPPLIER_DELAY',
				sku: '',
				supplierId: '  SUP-GAMMA  ',
				candidates: [
					{ label: '', replenishmentQuantity: null, additionalDelayDays: 7 },
					{ label: '延期 90 天', replenishmentQuantity: null, additionalDelayDays: 90 },
				],
			}),
		);

		expect(request).toEqual({
			title: '风险处置补货方案对比',
			scenario: 'SUPPLIER_DELAY',
			supplierId: 'SUP-GAMMA',
			candidates: [{ additionalDelayDays: 7 }, { label: '延期 90 天', additionalDelayDays: 90 }],
		});
		expect(request).not.toHaveProperty('sku');
		expect(validateComparisonDraft(
			comparisonDraft({
				scenario: 'SUPPLIER_DELAY',
				sku: '',
				supplierId: '   ',
				candidates: [
					{ replenishmentQuantity: null, additionalDelayDays: 7 },
					{ replenishmentQuantity: null, additionalDelayDays: 14 },
				],
			}),
		)).toContain('供应商');
		expect(validateComparisonDraft(
			comparisonDraft({
				scenario: 'SUPPLIER_DELAY',
				sku: '',
				supplierId: 'SUP-GAMMA',
				candidates: [
					{ replenishmentQuantity: null, additionalDelayDays: 1 },
					{ replenishmentQuantity: null, additionalDelayDays: 91 },
				],
			}),
		)).toContain('1 到 90');
	});

	it('omits the ALL plan status but keeps explicit status filters', () => {
		expect(buildDisposalPlanQuery({ status: 'ALL', page: 1, pageSize: 5 })).toEqual({ page: 1, pageSize: 5 });
		expect(buildDisposalPlanQuery({ status: 'SELECTED', page: 2, pageSize: 10 })).toEqual({
			status: 'SELECTED',
			page: 2,
			pageSize: 10,
		});
	});

	it('keeps UI labels aligned with backend enums', () => {
		expect(scenarioLabel('INVENTORY_REPLENISHMENT')).toBe('库存补货');
		expect(scenarioLabel('SUPPLIER_DELAY')).toBe('供应商延期');
		expect(actionTypeLabel('REPLENISH_INVENTORY')).toBe('库存补货');
		expect(actionTypeLabel('FOLLOW_UP_SUPPLIER')).toBe('供应商跟进');
		expect(actionTypeLabel('MANUAL_CONFIRMATION')).toBe('人工核实');
		expect(disposalPlanStatusLabel('PENDING_CONFIRMATION')).toBe('待人工确认');
		expect(disposalPlanStatusLabel('SELECTED')).toBe('已选定');
		expect(disposalPlanStatusLabel('REJECTED')).toBe('不采用');
		expect(comparisonOutcomeLabel('RECOMMENDED')).toBe('确定性排名推荐');
		expect(comparisonOutcomeLabel('TIE_BREAK')).toContain('最小干预');
		expect(comparisonOutcomeLabel('INCOMPARABLE')).toBe('候选不可比');
		expect(priorityLabel('P1')).toContain('P1');
		expect(DISPOSAL_PLAN_STATUS_OPTIONS.map((option) => option.value)).toEqual([
			'ALL',
			'PENDING_CONFIRMATION',
			'SELECTED',
			'REJECTED',
		]);
	});

	it('keeps sandbox execution labels and idempotency keys explicit', () => {
		expect(sandboxExecutionStatusLabel('RUNNING')).toBe('执行中');
		expect(sandboxExecutionStatusLabel('SUCCEEDED')).toBe('演练完成');
		expect(sandboxExecutionStatusLabel('FAILED')).toBe('演练失败');
		expect(sandboxStepStatusLabel('SUCCEEDED')).toBe('成功');
		expect(sandboxStepStatusLabel('FAILED')).toBe('失败');
		expect(sandboxDraftTypeLabel('REPLENISHMENT_TASK_DRAFT')).toBe('补货任务草稿');
		expect(sandboxDraftTypeLabel('SUPPLIER_FOLLOW_UP_DRAFT')).toBe('供应商跟进草稿');
		expect(sandboxDraftTypeLabel(null)).toBe('任务草稿');

		const first = createSandboxIdempotencyKey();
		const second = createSandboxIdempotencyKey();
		expect(first).toMatch(/^sandbox-/);
		expect(second).toMatch(/^sandbox-/);
		expect(first).not.toBe(second);
	});

	it('wires sandbox execution calls to the persisted backend endpoints', async () => {
		const fetchMock = vi.fn(async () => ({}));
		vi.stubGlobal('$fetch', fetchMock);
		try {
			await fetchSandboxExecutionEligibility('plan/1');
			await startSandboxExecution('plan/1', 'key 1');
			await fetchSandboxExecutions('plan/1', 10);
			await fetchSandboxExecution('exec/1');

			expect(fetchMock).toHaveBeenNthCalledWith(
				1,
				'/api/supply-chain/disposal-plans/plan%2F1/execution-eligibility',
			);
			expect(fetchMock).toHaveBeenNthCalledWith(2, '/api/supply-chain/disposal-plans/plan%2F1/executions', {
				method: 'POST',
				body: { idempotencyKey: 'key 1' },
			});
			expect(fetchMock).toHaveBeenNthCalledWith(3, '/api/supply-chain/disposal-plans/plan%2F1/executions', {
				query: { page: 1, pageSize: 10 },
			});
			expect(fetchMock).toHaveBeenNthCalledWith(4, '/api/supply-chain/sandbox-executions/exec%2F1');
		} finally {
			vi.unstubAllGlobals();
		}
	});

	it('wires scenario comparison calls to the persisted backend endpoints', async () => {
		const fetchMock = vi.fn(async () => ({}));
		vi.stubGlobal('$fetch', fetchMock);
		try {
			const request = {
				title: '对比',
				scenario: 'INVENTORY_REPLENISHMENT' as const,
				sku: 'SKU/1',
				candidates: [{ replenishmentQuantity: 50 }, { replenishmentQuantity: 100 }],
			};

			await createDisposalScenarioComparison(request);
			await fetchDisposalScenarioComparisons(3, 2);
			await fetchDisposalScenarioComparison('cmp/1');
			await saveComparisonCandidateAsDisposalPlan('cmp/1', 'cand/1', {
				title: '方案',
				generateExplanation: true,
			});
			await linkComparisonCandidateToDisposalPlan('cmp/1', 'cand/1', 'plan/9');

			expect(fetchMock).toHaveBeenNthCalledWith(
				1,
				'/api/supply-chain/disposal-scenario-comparisons',
				{ method: 'POST', body: request },
			);
			expect(fetchMock).toHaveBeenNthCalledWith(
				2,
				'/api/supply-chain/disposal-scenario-comparisons',
				{ query: { page: 2, pageSize: 3 } },
			);
			expect(fetchMock).toHaveBeenNthCalledWith(
				3,
				'/api/supply-chain/disposal-scenario-comparisons/cmp%2F1',
			);
			expect(fetchMock).toHaveBeenNthCalledWith(
				4,
				'/api/supply-chain/disposal-scenario-comparisons/cmp%2F1/candidates/cand%2F1/disposal-plans',
				{ method: 'POST', body: { title: '方案', generateExplanation: true } },
			);
			expect(fetchMock).toHaveBeenNthCalledWith(
				5,
				'/api/supply-chain/disposal-scenario-comparisons/cmp%2F1/candidates/cand%2F1/plan',
				{ method: 'PATCH', body: { planId: 'plan/9' } },
			);
		} finally {
			vi.unstubAllGlobals();
		}
	});

	it('maps backend validation, conflict and availability errors to actionable messages', () => {
		const validation = Object.assign(new Error('bad request'), {
			statusCode: 400,
			data: { message: 'replenishmentQuantity must be at most 1000000' },
		});
		const conflict = Object.assign(new Error('conflict'), {
			statusCode: 409,
			data: { message: 'Simulation is already attached to another plan' },
		});
		const missing = Object.assign(new Error('missing'), { statusCode: 404, data: {} });
		const offline = new Error('fetch failed');

		expect(disposalErrorMessage(validation, 'fallback')).toBe(
			'参数校验失败：replenishmentQuantity must be at most 1000000',
		);
		expect(disposalErrorMessage(conflict, 'fallback')).toContain('Simulation is already attached');
		expect(disposalErrorMessage(missing, 'fallback')).toContain('目标不存在');
		expect(disposalErrorMessage(offline, 'fallback')).toBe('fetch failed');
	});
});
