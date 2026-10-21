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
	defaultDisposalApi,
	useSupplyChainDisposal,
	type DisposalApi,
} from './useSupplyChainDisposal';
import type {
	CreateDisposalScenarioComparisonRequest,
	DisposalPlan,
	DisposalPlanPage,
	DisposalPlanQuery,
	DisposalPlanSummary,
	DisposalRecommendation,
	DisposalScenarioCandidate,
	DisposalScenarioComparison,
	DisposalScenarioComparisonPage,
	DisposalScenarioComparisonSummary,
	DisposalScenarioRanking,
	DisposalSimulation,
	DisposalSimulationSummary,
	SandboxExecution,
	SandboxExecutionEligibility,
	SandboxExecutionPage,
	SandboxExecutionSummary,
	SimulationRequest,
} from '../services/supplyChainDisposal';

const metrics = {
	totalOrders: 4,
	atRiskOrders: 3,
	highRiskOrders: 3,
	ordersWithShortage: 3,
	totalShortageUnits: 330,
	totalRiskScore: 297,
	targetSkuAvailableToPromise: 20,
};

function recommendation(overrides: Partial<DisposalRecommendation> = {}): DisposalRecommendation {
	return {
		recommendationId: 'rec-1',
		actionType: 'REPLENISH_INVENTORY',
		priority: 'P1',
		priorityScore: 75,
		priorityBasis: '紧急度 45；库存缺口 20；最高风险 10',
		riskLevel: 'HIGH',
		sku: 'SKU-MCU-001',
		supplierId: 'SUP-ALPHA',
		affectedOrderIds: ['ORD-1001'],
		title: '补货 100 件',
		action: '补货 100 件并复核 ORD-1001 的交付承诺。',
		rationale: '可用量低于订单需求。',
		businessBasis: ['ATP 为 20，订单需求 120'],
		expectedImprovement: '缺口从 100 降至 0',
		prerequisites: ['确认补货在要求交付日前到货'],
		sideEffects: ['占用补货预算'],
		sources: ['SUPPLY-CHAIN-SOP#inventory'],
		assumptions: ['模拟期内没有新增订单'],
		status: 'OPEN',
		generatedAt: '2026-10-20T08:00:00Z',
		...overrides,
	};
}

function simulation(overrides: Partial<DisposalSimulation> = {}): DisposalSimulation {
	return {
		simulationId: 'sim-1',
		scenario: 'INVENTORY_REPLENISHMENT',
		sku: 'SKU-MCU-001',
		supplierId: null,
		replenishmentQuantity: 100,
		additionalDelayDays: null,
		asOfDate: '2026-10-20',
		baselineSource: 'DEMO_DATA',
		baseline: metrics,
		simulated: {
			...metrics,
			atRiskOrders: 2,
			highRiskOrders: 2,
			totalShortageUnits: 230,
			totalRiskScore: 272,
			targetSkuAvailableToPromise: 120,
		},
		delta: {
			totalOrders: 0,
			atRiskOrders: -1,
			highRiskOrders: -1,
			ordersWithShortage: -1,
			totalShortageUnits: -100,
			totalRiskScore: -25,
			targetSkuAvailableToPromise: 100,
		},
		affectedOrders: [
			{
				orderId: 'ORD-1001',
				sku: 'SKU-MCU-001',
				supplierId: 'SUP-ALPHA',
				baselineEstimatedDeliveryDate: '2026-10-25',
				simulatedEstimatedDeliveryDate: '2026-10-25',
				baselineRiskLevel: 'HIGH',
				simulatedRiskLevel: 'MEDIUM',
				baselineRiskScore: 75,
				simulatedRiskScore: 50,
				baselineInventoryShortage: 100,
				simulatedInventoryShortage: 0,
				changes: ['inventoryShortage 100 -> 0', 'riskLevel HIGH -> MEDIUM'],
			},
		],
		assumptions: ['补货数量在最早要求交付日前全部可用'],
		limitations: ['不模拟新增订单'],
		evidence: ['SKU-MCU-001 当前 ATP 20'],
		realDataMutated: false,
		disclaimer: '模拟结果不是实际执行结果。',
		createdAt: '2026-10-20T08:05:00Z',
		...overrides,
	};
}

function simulationSummary(overrides: Partial<DisposalSimulationSummary> = {}): DisposalSimulationSummary {
	return {
		simulationId: 'sim-1',
		scenario: 'INVENTORY_REPLENISHMENT',
		sku: 'SKU-MCU-001',
		supplierId: null,
		parameterSummary: 'SKU-MCU-001 +100 件',
		affectedOrderCount: 1,
		atRiskOrdersDelta: -1,
		planId: null,
		createdAt: '2026-10-20T08:05:00Z',
		...overrides,
	};
}

function planSummary(overrides: Partial<DisposalPlanSummary> = {}): DisposalPlanSummary {
	return {
		planId: 'plan-1',
		title: 'MCU 补货方案',
		status: 'PENDING_CONFIRMATION',
		taskId: null,
		scopeSku: 'SKU-MCU-001',
		scopeSupplierId: null,
		recommendationCount: 1,
		simulationScenario: 'INVENTORY_REPLENISHMENT',
		atRiskOrdersDelta: -1,
		createdAt: '2026-10-20T08:06:00Z',
		updatedAt: '2026-10-20T08:06:00Z',
		...overrides,
	};
}

function plan(overrides: Partial<DisposalPlan> = {}): DisposalPlan {
	return {
		planId: 'plan-1',
		title: 'MCU 补货方案',
		taskId: null,
		scopeSku: 'SKU-MCU-001',
		scopeSupplierId: null,
		status: 'PENDING_CONFIRMATION',
		selectedRecommendationId: null,
		recommendations: [recommendation()],
		simulation: null,
		evidence: [],
		explanation: '规则说明。',
		explanationSource: 'DETERMINISTIC_RULES',
		explanationModelAssisted: false,
		explanationToolCalls: [],
		createdAt: '2026-10-20T08:06:00Z',
		updatedAt: '2026-10-20T08:06:00Z',
		...overrides,
	};
}

function sandboxEligibility(
	overrides: Partial<SandboxExecutionEligibility> = {},
): SandboxExecutionEligibility {
	return {
		disposalPlanId: 'plan-1',
		planStatus: 'SELECTED',
		eligible: true,
		recommendationId: 'rec-1',
		actionType: 'REPLENISH_INVENTORY',
		sourceSimulationId: 'sim-1',
		checks: ['方案状态：SELECTED', '当前没有进行中或已成功的执行记录'],
		reason: null,
		checkedAt: '2026-10-20T08:07:00Z',
		...overrides,
	};
}

function sandboxExecution(overrides: Partial<SandboxExecution> = {}): SandboxExecution {
	return {
		executionId: 'exec-1',
		disposalPlanId: 'plan-1',
		recommendationId: 'rec-1',
		actionType: 'REPLENISH_INVENTORY',
		mode: 'SANDBOX',
		status: 'SUCCEEDED',
		sourceSimulationId: 'sim-1',
		idempotencyKey: 'key-1',
		steps: [],
		taskDraft: null,
		evidence: [],
		finalResult: '沙箱演练完成，未修改真实业务数据。',
		unresolvedItems: [],
		failureReason: null,
		realDataMutated: false,
		disclaimer: '本地沙箱执行演练。',
		createdAt: '2026-10-20T08:07:00Z',
		startedAt: '2026-10-20T08:07:00Z',
		completedAt: '2026-10-20T08:07:01Z',
		updatedAt: '2026-10-20T08:07:01Z',
		...overrides,
	};
}

function executionSummary(overrides: Partial<SandboxExecutionSummary> = {}): SandboxExecutionSummary {
	return {
		executionId: 'exec-1',
		disposalPlanId: 'plan-1',
		actionType: 'REPLENISH_INVENTORY',
		mode: 'SANDBOX',
		status: 'SUCCEEDED',
		sourceSimulationId: 'sim-1',
		idempotencyKey: 'key-1',
		taskDraftId: 'draft-1',
		draftType: 'REPLENISHMENT_TASK_DRAFT',
		title: '补货任务草稿',
		stepCount: 5,
		failedStepCount: 0,
		createdAt: '2026-10-20T08:07:00Z',
		completedAt: '2026-10-20T08:07:01Z',
		...overrides,
	};
}

function executionPage(
	items: SandboxExecutionSummary[],
	totalElements = items.length,
): SandboxExecutionPage {
	return { items, page: 1, pageSize: 10, totalElements, totalPages: 1 };
}

function page(items: DisposalPlanSummary[], query: DisposalPlanQuery, totalPages = 3): DisposalPlanPage {
	return {
		items,
		page: query.page,
		pageSize: query.pageSize,
		totalElements: 12,
		totalPages,
	};
}

function comparisonCandidate(
	overrides: Partial<DisposalScenarioCandidate> = {},
): DisposalScenarioCandidate {
	return {
		candidateId: 'cand-100',
		label: '补货 100 件',
		position: 0,
		scenario: 'INVENTORY_REPLENISHMENT',
		sku: 'SKU-MCU-001',
		supplierId: null,
		replenishmentQuantity: 100,
		additionalDelayDays: null,
		simulation: simulation({ simulationId: 'sim-cand-100' }),
		linkedPlanId: null,
		createdAt: '2026-10-20T08:10:00Z',
		...overrides,
	};
}

function comparisonRanking(
	overrides: Partial<DisposalScenarioRanking> = {},
): DisposalScenarioRanking {
	return {
		candidateId: 'cand-100',
		label: '补货 100 件',
		parameterSummary: '补货 +100 件',
		rank: 1,
		tied: false,
		recommended: true,
		comparable: true,
		rankingBasis: '按高风险订单降幅、风险订单降幅、缺口订单、缺口总量、风险评分依次比较。',
		keyDeltas: ['高风险订单 -1', '缺口总量 -100'],
		reasons: ['高风险订单减少 1 单。'],
		tradeoffs: ['需要补货 100 件。'],
		...overrides,
	};
}

function comparison(overrides: Partial<DisposalScenarioComparison> = {}): DisposalScenarioComparison {
	return {
		comparisonId: 'comparison-1',
		title: '风险处置补货方案对比',
		scenario: 'INVENTORY_REPLENISHMENT',
		sku: 'SKU-MCU-001',
		supplierId: null,
		asOfDate: '2026-10-20',
		baselineSource: 'DEMO_DATA',
		baselineFingerprint: 'a'.repeat(64),
		baselineCapturedAt: '2026-10-20T08:09:00Z',
		baseline: metrics,
		bestAtRiskOrdersDelta: -1,
		bestTotalShortageUnitsDelta: -100,
		candidates: [comparisonCandidate()],
		rankings: [comparisonRanking()],
		recommendation: {
			outcome: 'RECOMMENDED',
			recommendedCandidateId: 'cand-100',
			recommendedLabel: '补货 100 件',
			recommendedRank: 1,
			summary: '推荐补货 100 件，因为高风险订单与缺口总量同时下降。',
			keyDeltas: ['高风险订单 -1', '缺口总量 -100'],
			reasons: ['高风险订单减少 1 单。'],
			tradeoffs: ['需要补货 100 件。'],
			assumptions: ['补货在要求交付日前可用。'],
			unknowns: ['实际到货时间未知。'],
			manualConfirmation: ['确认供应商交期。'],
			evidence: ['共享基线 ATP 20。'],
			disclaimer: '对比结果不是实际执行结果。',
		},
		rankingRules: ['先比较高风险订单降幅，再比较风险订单、缺口订单、缺口总量与风险评分。'],
		assumptions: ['所有候选共享同一基线快照。'],
		limitations: ['不估算成本、预算与库容。'],
		evidence: ['共享基线指纹 aaaaaaaaaaaa。'],
		realDataMutated: false,
		disclaimer: '对比结果不是实际执行结果。',
		createdAt: '2026-10-20T08:10:00Z',
		updatedAt: '2026-10-20T08:10:00Z',
		...overrides,
	};
}

function comparisonSummary(
	overrides: Partial<DisposalScenarioComparisonSummary> = {},
): DisposalScenarioComparisonSummary {
	return {
		comparisonId: 'comparison-1',
		title: '风险处置补货方案对比',
		scenario: 'INVENTORY_REPLENISHMENT',
		sku: 'SKU-MCU-001',
		supplierId: null,
		candidateCount: 3,
		baselineSource: 'DEMO_DATA',
		baselineFingerprint: 'a'.repeat(64),
		outcome: 'RECOMMENDED',
		recommendedCandidateId: 'cand-100',
		recommendedLabel: '补货 100 件',
		recommendedRank: 1,
		baselineAtRiskOrders: 3,
		baselineTotalShortageUnits: 330,
		bestAtRiskOrdersDelta: -1,
		bestTotalShortageUnitsDelta: -100,
		linkedPlanCount: 0,
		createdAt: '2026-10-20T08:10:00Z',
		updatedAt: '2026-10-20T08:10:00Z',
		...overrides,
	};
}

function comparisonPage(
	items: DisposalScenarioComparisonSummary[],
	pageSize = 5,
): DisposalScenarioComparisonPage {
	return {
		items,
		page: 1,
		pageSize,
		totalElements: items.length,
		totalPages: 1,
	};
}

function makeApi(overrides: Partial<DisposalApi> = {}): DisposalApi {
	return {
		recommendations: vi.fn(async () => []),
		createSimulation: vi.fn(async () => simulation()),
		simulations: vi.fn(async () => []),
		simulation: vi.fn(async () => simulation()),
		plans: vi.fn(async (query: DisposalPlanQuery) => page([planSummary()], query)),
		plan: vi.fn(async () => plan()),
		planSimulations: vi.fn(async () => []),
		createPlan: vi.fn(async () => plan()),
		updatePlanStatus: vi.fn(async (planId: string, request) =>
			plan({
				planId,
				status: request.status,
				selectedRecommendationId: request.selectedRecommendationId ?? null,
			}),
		),
		createComparison: vi.fn(async () => comparison()),
		comparisons: vi.fn(async () => comparisonPage([comparisonSummary()])),
		comparison: vi.fn(async () => comparison()),
		saveComparisonCandidateAsPlan: vi.fn(async () =>
			plan({
				planId: 'plan-from-candidate',
				simulation: simulation({ simulationId: 'sim-candidate-copy' }),
			}),
		),
		linkComparisonCandidatePlan: vi.fn(async () =>
			comparison({ candidates: [comparisonCandidate({ linkedPlanId: 'plan-existing' })] }),
		),
		executionEligibility: vi.fn(async (planId: string) =>
			sandboxEligibility({ disposalPlanId: planId }),
		),
		startExecution: vi.fn(async () => sandboxExecution()),
		executions: vi.fn(async () => executionPage([executionSummary()])),
		execution: vi.fn(async () => sandboxExecution()),
		...overrides,
	};
}

function deferred<T>() {
	let resolve!: (value: T) => void;
	let reject!: (reason?: unknown) => void;
	const promise = new Promise<T>((res, rej) => {
		resolve = res;
		reject = rej;
	});
	return { promise, resolve, reject };
}

describe('useSupplyChainDisposal', () => {
	it('exposes the production API binding for every disposal operation', () => {
		expect(Object.keys(defaultDisposalApi).sort()).toEqual([
			'comparison',
			'comparisons',
			'createComparison',
			'createPlan',
			'createSimulation',
			'execution',
			'executionEligibility',
			'executions',
			'linkComparisonCandidatePlan',
			'plan',
			'planSimulations',
			'plans',
			'recommendations',
			'saveComparisonCandidateAsPlan',
			'simulation',
			'simulations',
			'startExecution',
			'updatePlanStatus',
		]);
	});

	it('initializes recommendations, plans, simulation and comparison history from the backend', async () => {
		const api = makeApi({
			recommendations: vi.fn(async () => [recommendation()]),
			plans: vi.fn(async (query: DisposalPlanQuery) => page([planSummary()], query, 1)),
			simulations: vi.fn(async () => [simulationSummary()]),
		});
		const disposal = useSupplyChainDisposal(api);

		await disposal.initialize();

		expect(api.recommendations).toHaveBeenCalledWith({});
		expect(api.plans).toHaveBeenCalledWith({ status: 'ALL', page: 1, pageSize: 5 });
		expect(api.simulations).toHaveBeenCalledWith(6);
		expect(api.comparisons).toHaveBeenCalledWith(5, 1);
		expect(disposal.recommendations.value).toHaveLength(1);
		expect(disposal.plans.value).toHaveLength(1);
		expect(disposal.simulationHistory.value).toHaveLength(1);
		expect(disposal.comparisonHistory.value).toHaveLength(1);
		expect(disposal.recommendationsLoaded.value).toBe(true);
		expect(disposal.plansLoaded.value).toBe(true);
	});

	it('loads recommendations for a scope and ignores a stale earlier response', async () => {
		const stale = deferred<DisposalRecommendation[]>();
		const fresh = deferred<DisposalRecommendation[]>();
		const recommendations = vi
			.fn<(scope: { sku?: string; supplierId?: string }) => Promise<DisposalRecommendation[]>>()
			.mockReturnValueOnce(stale.promise)
			.mockReturnValueOnce(fresh.promise);
		const api = makeApi({ recommendations });
		const disposal = useSupplyChainDisposal(api);

		const firstLoad = disposal.loadRecommendations();
		const secondLoad = disposal.loadRecommendations({ sku: ' SKU-MCU-001 ' });

		fresh.resolve([recommendation({ recommendationId: 'fresh' })]);
		await secondLoad;
		stale.resolve([recommendation({ recommendationId: 'stale' })]);
		await firstLoad;

		expect(recommendations).toHaveBeenNthCalledWith(1, {});
		expect(recommendations).toHaveBeenNthCalledWith(2, { sku: 'SKU-MCU-001' });
		expect(disposal.recommendations.value.map((item) => item.recommendationId)).toEqual(['fresh']);
		expect(disposal.recommendationsLoading.value).toBe(false);
	});

	it('shows recommendation load failures and clears them on retry', async () => {
		const recommendations = vi
			.fn<() => Promise<DisposalRecommendation[]>>()
			.mockRejectedValueOnce(new Error('backend offline'))
			.mockResolvedValueOnce([recommendation()]);
		const disposal = useSupplyChainDisposal(makeApi({ recommendations }));

		await disposal.loadRecommendations();

		expect(disposal.recommendations.value).toEqual([]);
		expect(disposal.recommendationsError.value).toContain('backend offline');
		expect(disposal.recommendationsLoaded.value).toBe(true);

		await disposal.loadRecommendations();

		expect(disposal.recommendationsError.value).toBe('');
		expect(disposal.recommendations.value).toHaveLength(1);
	});

	it('runs a deterministic simulation and refreshes persisted history', async () => {
		const createSimulation = vi.fn(async (_request: SimulationRequest) => simulation());
		const simulations = vi.fn(async () => [simulationSummary()]);
		const disposal = useSupplyChainDisposal(makeApi({ createSimulation, simulations }));

		const result = await disposal.runSimulation({
			scenario: 'INVENTORY_REPLENISHMENT',
			sku: 'SKU-MCU-001',
			replenishmentQuantity: 100,
		});

		expect(createSimulation).toHaveBeenCalledWith({
			scenario: 'INVENTORY_REPLENISHMENT',
			sku: 'SKU-MCU-001',
			replenishmentQuantity: 100,
		});
		expect(result?.simulationId).toBe('sim-1');
		expect(disposal.simulation.value?.delta.targetSkuAvailableToPromise).toBe(100);
		expect(disposal.simulationPlanId.value).toBeNull();
		expect(simulations).toHaveBeenCalledWith(6);
		expect(disposal.simulationRunning.value).toBe(false);
	});

	it('surfaces backend validation failures for simulations', async () => {
		const createSimulation = vi.fn(async () => {
			throw Object.assign(new Error('bad request'), {
				statusCode: 400,
				data: { message: 'additionalDelayDays must be at most 90' },
			});
		});
		const disposal = useSupplyChainDisposal(makeApi({ createSimulation }));

		const result = await disposal.runSimulation({
			scenario: 'SUPPLIER_DELAY',
			supplierId: 'SUP-GAMMA',
			additionalDelayDays: 91,
		});

		expect(result).toBeNull();
		expect(disposal.simulation.value).toBeNull();
		expect(disposal.simulationError.value).toContain('additionalDelayDays');
		expect(disposal.simulationError.value).toContain('参数校验失败');
		expect(disposal.simulationRunning.value).toBe(false);
	});

	it('loads and reopens a persisted simulation with its plan link', async () => {
		const api = makeApi({ simulation: vi.fn(async () => simulation({ simulationId: 'sim-history' })) });
		const disposal = useSupplyChainDisposal(api);

		const result = await disposal.openSimulation('sim-history', 'plan-9');

		expect(api.simulation).toHaveBeenCalledWith('sim-history');
		expect(result?.simulationId).toBe('sim-history');
		expect(disposal.simulation.value?.simulationId).toBe('sim-history');
		expect(disposal.simulationPlanId.value).toBe('plan-9');
	});

	it('runs a multi-candidate comparison, stores the deterministic result and refreshes history', async () => {
		const createComparison = vi.fn(async (_request: CreateDisposalScenarioComparisonRequest) =>
			comparison(),
		);
		const comparisons = vi.fn(async () => comparisonPage([comparisonSummary()]));
		const disposal = useSupplyChainDisposal(makeApi({ createComparison, comparisons }));
		const request: CreateDisposalScenarioComparisonRequest = {
			title: '风险处置补货方案对比',
			scenario: 'INVENTORY_REPLENISHMENT',
			sku: 'SKU-MCU-001',
			candidates: [
				{ replenishmentQuantity: 50 },
				{ label: '补货 100 件', replenishmentQuantity: 100 },
				{ replenishmentQuantity: 200 },
			],
		};

		const result = await disposal.runComparison(request);

		expect(createComparison).toHaveBeenCalledWith(request);
		expect(result?.comparisonId).toBe('comparison-1');
		expect(disposal.scenarioComparison.value?.recommendation.recommendedCandidateId).toBe(
			'cand-100',
		);
		expect(disposal.scenarioComparison.value?.realDataMutated).toBe(false);
		expect(comparisons).toHaveBeenCalledWith(5, 1);
		expect(disposal.comparisonHistory.value).toHaveLength(1);
		expect(disposal.comparisonRunning.value).toBe(false);
	});

	it('surfaces comparison failures and ignores duplicate submissions while one is running', async () => {
		const pending = deferred<DisposalScenarioComparison>();
		const createComparison = vi.fn(() => pending.promise);
		const disposal = useSupplyChainDisposal(makeApi({ createComparison }));
		const request: CreateDisposalScenarioComparisonRequest = {
			title: '风险处置补货方案对比',
			scenario: 'INVENTORY_REPLENISHMENT',
			sku: 'SKU-MCU-001',
			candidates: [{ replenishmentQuantity: 50 }, { replenishmentQuantity: 100 }],
		};

		const first = disposal.runComparison(request);
		const duplicate = await disposal.runComparison(request);

		expect(duplicate).toBeNull();
		expect(createComparison).toHaveBeenCalledTimes(1);

		pending.reject(
			Object.assign(new Error('bad request'), {
				statusCode: 400,
				data: { message: 'candidates must contain between 2 and 5 entries' },
			}),
		);
		await first;

		expect(disposal.scenarioComparison.value).toBeNull();
		expect(disposal.comparisonError.value).toContain(
			'candidates must contain between 2 and 5 entries',
		);
		expect(disposal.comparisonRunning.value).toBe(false);
	});

	it('opens a persisted comparison and keeps the newest history response', async () => {
		const stale = deferred<DisposalScenarioComparisonPage>();
		const fresh = deferred<DisposalScenarioComparisonPage>();
		const comparisons = vi
			.fn<(limit: number, page: number) => Promise<DisposalScenarioComparisonPage>>()
			.mockReturnValueOnce(stale.promise)
			.mockReturnValueOnce(fresh.promise);
		const comparisonApi = vi.fn(async () => comparison({ comparisonId: 'comparison-history' }));
		const disposal = useSupplyChainDisposal(
			makeApi({ comparisons, comparison: comparisonApi }),
		);

		const firstLoad = disposal.loadComparisonHistory();
		const secondLoad = disposal.loadComparisonHistory(3);

		fresh.resolve(comparisonPage([comparisonSummary({ comparisonId: 'fresh' })], 3));
		await secondLoad;
		stale.resolve(comparisonPage([comparisonSummary({ comparisonId: 'stale' })]));
		await firstLoad;

		expect(disposal.comparisonHistory.value.map((item) => item.comparisonId)).toEqual([
			'fresh',
		]);

		const opened = await disposal.openComparison(' comparison-history ');

		expect(comparisonApi).toHaveBeenCalledWith('comparison-history');
		expect(opened?.comparisonId).toBe('comparison-history');
		expect(disposal.scenarioComparison.value?.comparisonId).toBe('comparison-history');
		expect(disposal.comparisonDetailLoading.value).toBe(false);
	});

	it('clears the active comparison and discards an in-flight response', async () => {
		const pending = deferred<DisposalScenarioComparison>();
		const createComparison = vi.fn(() => pending.promise);
		const disposal = useSupplyChainDisposal(makeApi({ createComparison }));

		const running = disposal.runComparison({
			title: '风险处置补货方案对比',
			scenario: 'INVENTORY_REPLENISHMENT',
			sku: 'SKU-MCU-001',
			candidates: [{ replenishmentQuantity: 50 }, { replenishmentQuantity: 100 }],
		});
		disposal.clearComparison();
		pending.resolve(comparison());
		await running;

		expect(disposal.scenarioComparison.value).toBeNull();
		expect(disposal.comparisonRunning.value).toBe(false);
		expect(disposal.comparisonError.value).toBe('');
	});

	it('saves a candidate as a copied plan and refreshes plan and execution views', async () => {
		const savedPlan = plan({
			planId: 'plan-from-candidate',
			simulation: simulation({ simulationId: 'sim-candidate-copy' }),
		});
		const saveComparisonCandidateAsPlan = vi.fn(async () => savedPlan);
		const plans = vi.fn(async (query: DisposalPlanQuery) =>
			page([planSummary({ planId: 'plan-from-candidate' })], query, 1),
		);
		const executions = vi.fn(async () => executionPage([executionSummary({ disposalPlanId: 'plan-from-candidate' })]));
		const executionEligibility = vi.fn(async (planId: string) =>
			sandboxEligibility({ disposalPlanId: planId }),
		);
		const api = makeApi({
			saveComparisonCandidateAsPlan,
			plans,
			executions,
			executionEligibility,
		});
		const disposal = useSupplyChainDisposal(api);
		disposal.scenarioComparison.value = comparison();

		const result = await disposal.saveComparisonCandidateAsPlan(
			'comparison-1',
			'cand-100',
			{ title: '补货 100 件', generateExplanation: true },
		);

		expect(saveComparisonCandidateAsPlan).toHaveBeenCalledWith('comparison-1', 'cand-100', {
			title: '补货 100 件',
			generateExplanation: true,
		});
		expect(result?.planId).toBe('plan-from-candidate');
		expect(disposal.scenarioComparison.value?.candidates[0]?.linkedPlanId).toBe(
			'plan-from-candidate',
		);
		expect(disposal.activePlan.value?.planId).toBe('plan-from-candidate');
		expect(disposal.activePlanSimulations.value[0]?.planId).toBe('plan-from-candidate');
		expect(disposal.comparisonSaveNotice.value).toContain('plan-from-candidate');
		expect(plans).toHaveBeenCalled();
		expect(api.comparisons).toHaveBeenCalledWith(5, 1);
		expect(executions).toHaveBeenCalledWith('plan-from-candidate', 10);
		expect(executionEligibility).toHaveBeenCalledWith('plan-from-candidate');
		expect(disposal.comparisonSavingCandidateId.value).toBeNull();
	});

	it('ignores a second candidate save while the first request is pending', async () => {
		const pending = deferred<DisposalPlan>();
		const saveComparisonCandidateAsPlan = vi.fn(() => pending.promise);
		const disposal = useSupplyChainDisposal(makeApi({ saveComparisonCandidateAsPlan }));
		disposal.scenarioComparison.value = comparison();

		const first = disposal.saveComparisonCandidateAsPlan('comparison-1', 'cand-100', {
			title: '补货 100 件',
		});
		const duplicate = await disposal.saveComparisonCandidateAsPlan(
			'comparison-1',
			'cand-100',
			{ title: '补货 100 件' },
		);

		expect(duplicate).toBeNull();
		expect(saveComparisonCandidateAsPlan).toHaveBeenCalledTimes(1);

		pending.resolve(plan({ planId: 'plan-from-candidate' }));
		await first;

		expect(disposal.comparisonSavingCandidateId.value).toBeNull();
	});

	it('links a candidate to an existing plan and reloads comparison history', async () => {
		const linked = comparison({
			candidates: [comparisonCandidate({ linkedPlanId: 'plan-existing' })],
		});
		const linkComparisonCandidatePlan = vi.fn(async () => linked);
		const api = makeApi({ linkComparisonCandidatePlan });
		const disposal = useSupplyChainDisposal(api);
		disposal.scenarioComparison.value = comparison();

		const result = await disposal.linkComparisonCandidatePlan(
			'comparison-1',
			'cand-100',
			'plan-existing',
		);

		expect(linkComparisonCandidatePlan).toHaveBeenCalledWith(
			'comparison-1',
			'cand-100',
			'plan-existing',
		);
		expect(result?.candidates[0]?.linkedPlanId).toBe('plan-existing');
		expect(disposal.comparisonLinkNotice.value).toContain('plan-existing');
		expect(api.comparisons).toHaveBeenCalledWith(5, 1);
		expect(disposal.comparisonLinking.value).toBe(false);
	});

	it('reports comparison link conflicts without replacing the comparison view', async () => {
		const linkComparisonCandidatePlan = vi.fn(async () => {
			throw Object.assign(new Error('conflict'), {
				statusCode: 409,
				data: { message: 'candidate is already linked to another plan' },
			});
		});
		const disposal = useSupplyChainDisposal(makeApi({ linkComparisonCandidatePlan }));
		const existing = comparison();
		disposal.scenarioComparison.value = existing;

		const result = await disposal.linkComparisonCandidatePlan(
			'comparison-1',
			'cand-100',
			'plan-existing',
		);

		expect(result).toBeNull();
		expect(disposal.comparisonLinkError.value).toContain(
			'candidate is already linked to another plan',
		);
		expect(disposal.scenarioComparison.value?.comparisonId).toBe(existing.comparisonId);
		expect(disposal.scenarioComparison.value?.candidates[0]?.linkedPlanId).toBeNull();
		expect(disposal.comparisonLinking.value).toBe(false);
	});

	it('paginates plans and resets to page one when the status filter changes', async () => {
		const plans = vi.fn(async (query: DisposalPlanQuery) => page([planSummary()], query));
		const disposal = useSupplyChainDisposal(makeApi({ plans }));

		await disposal.loadPlans();
		expect(plans).toHaveBeenLastCalledWith({ status: 'ALL', page: 1, pageSize: 5 });
		expect(disposal.canPlanPrevious.value).toBe(false);
		expect(disposal.canPlanNext.value).toBe(true);

		await disposal.nextPlanPage();
		expect(plans).toHaveBeenLastCalledWith({ status: 'ALL', page: 2, pageSize: 5 });

		await disposal.setPlanStatus('SELECTED');
		expect(plans).toHaveBeenLastCalledWith({ status: 'SELECTED', page: 1, pageSize: 5 });
		expect(disposal.plansPage.value).toBe(1);
		expect(disposal.planStatus.value).toBe('SELECTED');
	});

	it('loads one plan together with its linked simulation history', async () => {
		const planSimulations = vi.fn(async () => [simulationSummary({ planId: 'plan-1' })]);
		const api = makeApi({
			plan: vi.fn(async () => plan({ planId: 'plan-open' })),
			planSimulations,
		});
		const disposal = useSupplyChainDisposal(api);

		const result = await disposal.openPlan(' plan-open ');

		expect(api.plan).toHaveBeenCalledWith('plan-open');
		expect(planSimulations).toHaveBeenCalledWith('plan-open', 6);
		expect(result?.planId).toBe('plan-open');
		expect(disposal.activePlan.value?.planId).toBe('plan-open');
		expect(disposal.activePlanSimulations.value).toHaveLength(1);
	});

	it('saves a plan with an existing simulation and marks it as attached', async () => {
		const created = plan({
			planId: 'plan-saved',
			simulation: simulation({ simulationId: 'sim-1' }),
		});
		const createPlan = vi.fn(async () => created);
		const plans = vi.fn(async (query: DisposalPlanQuery) => page([planSummary()], query, 1));
		const simulations = vi.fn(async () => [simulationSummary({ planId: 'plan-saved' })]);
		const disposal = useSupplyChainDisposal(makeApi({ createPlan, plans, simulations }));

		disposal.simulation.value = simulation({ simulationId: 'sim-1' });
		const result = await disposal.savePlan({
			title: 'MCU 补货方案',
			sku: 'SKU-MCU-001',
			simulationId: 'sim-1',
			generateExplanation: false,
		});

		expect(createPlan).toHaveBeenCalledWith({
			title: 'MCU 补货方案',
			sku: 'SKU-MCU-001',
			simulationId: 'sim-1',
			generateExplanation: false,
		});
		expect(result?.planId).toBe('plan-saved');
		expect(disposal.activePlan.value?.planId).toBe('plan-saved');
		expect(disposal.simulationPlanId.value).toBe('plan-saved');
		expect(disposal.planSaveNotice.value).toContain('plan-saved');
		expect(plans).toHaveBeenCalled();
		expect(simulations).toHaveBeenCalled();
	});

	it('requires a recommendation before marking a plan as selected', async () => {
		const updatePlanStatus = vi.fn(async (planId: string, request) =>
			plan({
				planId,
				status: request.status,
				selectedRecommendationId: request.selectedRecommendationId ?? null,
			}),
		);
		const disposal = useSupplyChainDisposal(makeApi({ updatePlanStatus }));
		disposal.activePlan.value = plan({ planId: 'plan-1' });

		const missing = await disposal.updatePlanStatus('plan-1', 'SELECTED');

		expect(missing).toBeNull();
		expect(updatePlanStatus).not.toHaveBeenCalled();
		expect(disposal.planStatusError.value).toContain('请先选择');

		const updated = await disposal.updatePlanStatus('plan-1', 'SELECTED', 'rec-1');

		expect(updatePlanStatus).toHaveBeenCalledWith('plan-1', {
			status: 'SELECTED',
			selectedRecommendationId: 'rec-1',
		});
		expect(updated?.status).toBe('SELECTED');
		expect(disposal.activePlan.value?.selectedRecommendationId).toBe('rec-1');
		expect(disposal.planStatusUpdating.value).toBe(false);
	});

	it('omits the selected recommendation for pending and rejected transitions', async () => {
		const updatePlanStatus = vi.fn(async (planId: string, request) =>
			plan({ planId, status: request.status, selectedRecommendationId: null }),
		);
		const disposal = useSupplyChainDisposal(makeApi({ updatePlanStatus }));
		disposal.activePlan.value = plan({ planId: 'plan-1', status: 'SELECTED', selectedRecommendationId: 'rec-1' });

		await disposal.updatePlanStatus('plan-1', 'PENDING_CONFIRMATION');
		await disposal.updatePlanStatus('plan-1', 'REJECTED');

		expect(updatePlanStatus).toHaveBeenNthCalledWith(1, 'plan-1', { status: 'PENDING_CONFIRMATION' });
		expect(updatePlanStatus).toHaveBeenNthCalledWith(2, 'plan-1', { status: 'REJECTED' });
		expect(disposal.activePlan.value?.status).toBe('REJECTED');
	});

	it('reports plan load failures without inventing totals', async () => {
		const plans = vi.fn(async () => {
			throw new Error('plan service unavailable');
		});
		const disposal = useSupplyChainDisposal(makeApi({ plans }));

		await disposal.loadPlans();

		expect(disposal.plans.value).toEqual([]);
		expect(disposal.plansTotalElements.value).toBe(0);
		expect(disposal.plansTotalPages.value).toBe(0);
		expect(disposal.plansError.value).toContain('plan service unavailable');
		expect(disposal.plansRangeLabel.value).toBe('分页信息不可用');
		expect(disposal.canPlanNext.value).toBe(false);
	});

	it('loads execution eligibility and persisted history together with a plan', async () => {
		const executionEligibility = vi.fn(async (planId: string) =>
			sandboxEligibility({ disposalPlanId: planId, planStatus: 'SELECTED' }),
		);
		const executions = vi.fn(async () => executionPage([executionSummary()]));
		const api = makeApi({
			plan: vi.fn(async () => plan({ planId: 'plan-open', status: 'SELECTED', selectedRecommendationId: 'rec-1' })),
			executionEligibility,
			executions,
		});
		const disposal = useSupplyChainDisposal(api);

		await disposal.openPlan('plan-open');

		expect(executionEligibility).toHaveBeenCalledWith('plan-open');
		expect(executions).toHaveBeenCalledWith('plan-open', 10);
		expect(disposal.executionEligibility.value?.eligible).toBe(true);
		expect(disposal.executions.value.map((item) => item.executionId)).toEqual(['exec-1']);
		expect(disposal.canStartExecution.value).toBe(true);
	});

	it('refreshes plan history after a confirmed proposal and ignores the older in-flight response', async () => {
		const first = deferred<DisposalPlanPage>();
		const second = deferred<DisposalPlanPage>();
		let requestCount = 0;
		const plans = vi.fn(() => {
			requestCount += 1;
			return requestCount === 1 ? first.promise : second.promise;
		});
		const disposal = useSupplyChainDisposal(makeApi({ plans }));

		const initial = disposal.loadPlans();
		const refresh = disposal.refreshAfterConfirmedProposal('plan-new');
		expect(plans).toHaveBeenCalledTimes(2);

		second.resolve(page([planSummary({ planId: 'plan-new' })], { status: 'ALL', page: 1, pageSize: 5 }));
		await refresh;
		first.resolve(page([planSummary({ planId: 'plan-old' })], { status: 'ALL', page: 1, pageSize: 5 }));
		await initial;

		expect(disposal.plans.value.map((item) => item.planId)).toEqual(['plan-new']);
		expect(disposal.plansLoading.value).toBe(false);
		expect(disposal.plansError.value).toBe('');
	});

	it('refreshes the open plan, eligibility and executions after its proposal is confirmed', async () => {
		const planApi = vi.fn(async () =>
			plan({ planId: 'plan-1', status: 'SELECTED', selectedRecommendationId: 'rec-1' }),
		);
		const executionEligibility = vi.fn(async (planId: string) =>
			sandboxEligibility({ disposalPlanId: planId, planStatus: 'SELECTED' }),
		);
		const executions = vi.fn(async () => executionPage([executionSummary()]));
		const plans = vi.fn(async (query: DisposalPlanQuery) => page([planSummary()], query));
		const disposal = useSupplyChainDisposal(
			makeApi({ plan: planApi, plans, executionEligibility, executions }),
		);
		disposal.activePlan.value = plan({ planId: 'plan-1' });

		await disposal.refreshAfterConfirmedProposal('plan-1');

		expect(plans).toHaveBeenCalledTimes(1);
		expect(planApi).toHaveBeenCalledWith('plan-1');
		expect(executionEligibility).toHaveBeenCalledWith('plan-1');
		expect(executions).toHaveBeenCalledWith('plan-1', 10);
		expect(disposal.activePlan.value?.status).toBe('SELECTED');
		expect(disposal.executionEligibility.value?.disposalPlanId).toBe('plan-1');
		expect(disposal.planDetailError.value).toBe('');
		expect(disposal.planDetailLoading.value).toBe(false);
	});

	it('does not leave plan detail loading stuck when a refresh supersedes an open request', async () => {
		const pendingOpen = deferred<DisposalPlan>();
		let planRequests = 0;
		const planApi = vi.fn(() => {
			planRequests += 1;
			return planRequests === 1
				? pendingOpen.promise
				: Promise.resolve(plan({ planId: 'plan-1', status: 'SELECTED' }));
		});
		const plans = vi.fn(async (query: DisposalPlanQuery) => page([planSummary()], query));
		const disposal = useSupplyChainDisposal(
			makeApi({
				plan: planApi,
				plans,
				executionEligibility: vi.fn(async (planId: string) =>
					sandboxEligibility({ disposalPlanId: planId, planStatus: 'SELECTED' }),
				),
				executions: vi.fn(async () => executionPage([])),
			}),
		);
		disposal.activePlan.value = plan({ planId: 'plan-1' });

		const opening = disposal.openPlan('plan-1');
		await disposal.refreshAfterConfirmedProposal('plan-1');

		expect(disposal.planDetailLoading.value).toBe(false);
		pendingOpen.resolve(plan({ planId: 'plan-1', status: 'PENDING_CONFIRMATION' }));
		await opening;

		expect(disposal.planDetailLoading.value).toBe(false);
		expect(disposal.activePlan.value?.status).toBe('SELECTED');
	});

	it('does not reload the open plan when a different plan was confirmed', async () => {
		const planApi = vi.fn(async () => plan());
		const executionEligibility = vi.fn(async () => sandboxEligibility());
		const executions = vi.fn(async () => executionPage([]));
		const plans = vi.fn(async (query: DisposalPlanQuery) => page([planSummary()], query));
		const disposal = useSupplyChainDisposal(
			makeApi({ plan: planApi, plans, executionEligibility, executions }),
		);
		disposal.activePlan.value = plan({ planId: 'plan-1' });

		await disposal.refreshAfterConfirmedProposal('plan-new');

		expect(plans).toHaveBeenCalledTimes(1);
		expect(planApi).not.toHaveBeenCalled();
		expect(executionEligibility).not.toHaveBeenCalled();
		expect(executions).not.toHaveBeenCalled();
	});

	it('ignores repeated clicks while one sandbox rehearsal is in flight', async () => {
		const pending = deferred<SandboxExecution>();
		const startExecution = vi.fn(() => pending.promise);
		const disposal = useSupplyChainDisposal(makeApi({ startExecution }), {
			createExecutionKey: () => 'key-1',
		});
		disposal.activePlan.value = plan({
			planId: 'plan-1',
			status: 'SELECTED',
			selectedRecommendationId: 'rec-1',
		});
		disposal.executionEligibility.value = sandboxEligibility({ disposalPlanId: 'plan-1' });

		const first = disposal.startExecution('plan-1');
		const duplicate = await disposal.startExecution('plan-1');

		expect(duplicate).toBeNull();
		expect(startExecution).toHaveBeenCalledTimes(1);
		expect(startExecution).toHaveBeenCalledWith('plan-1', 'key-1');

		pending.resolve(sandboxExecution({ executionId: 'exec-1' }));
		await first;

		expect(disposal.execution.value?.executionId).toBe('exec-1');
		expect(disposal.executionStartNotice.value).toContain('exec-1');
		expect(disposal.executionIdempotencyKey.value).toBeNull();
	});

	it('reuses the request key after a transport failure and starts a new key after a persisted failure', async () => {
		const keys = ['key-1', 'key-2'];
		const startExecution = vi
			.fn<() => Promise<SandboxExecution>>()
			.mockRejectedValueOnce(new Error('network down'))
			.mockResolvedValueOnce(sandboxExecution({ status: 'FAILED', failureReason: 'audit mismatch' }))
			.mockResolvedValueOnce(sandboxExecution({ executionId: 'exec-2', idempotencyKey: 'key-2' }));
		const disposal = useSupplyChainDisposal(makeApi({ startExecution }), {
			createExecutionKey: () => keys.shift() ?? 'unexpected-key',
		});
		disposal.activePlan.value = plan({
			planId: 'plan-1',
			status: 'SELECTED',
			selectedRecommendationId: 'rec-1',
		});
		disposal.executionEligibility.value = sandboxEligibility({ disposalPlanId: 'plan-1' });

		await disposal.startExecution('plan-1');

		expect(startExecution).toHaveBeenNthCalledWith(1, 'plan-1', 'key-1');
		expect(disposal.executionStartError.value).toContain('network down');
		expect(disposal.executionIdempotencyKey.value).toBe('key-1');

		await disposal.startExecution('plan-1');

		expect(startExecution).toHaveBeenNthCalledWith(2, 'plan-1', 'key-1');
		expect(disposal.executionStartError.value).toContain('audit mismatch');
		expect(disposal.executionIdempotencyKey.value).toBeNull();

		await disposal.startExecution('plan-1');

		expect(startExecution).toHaveBeenNthCalledWith(3, 'plan-1', 'key-2');
	});

	it('refuses to rehearse plans that are not selected or not eligible', async () => {
		const startExecution = vi.fn();
		const disposal = useSupplyChainDisposal(makeApi({ startExecution }), {
			createExecutionKey: () => 'key-1',
		});
		disposal.activePlan.value = plan({ planId: 'plan-1', status: 'PENDING_CONFIRMATION' });

		await disposal.startExecution('plan-1');

		expect(startExecution).not.toHaveBeenCalled();
		expect(disposal.executionStartError.value).toContain('已选定');

		disposal.activePlan.value = plan({
			planId: 'plan-1',
			status: 'SELECTED',
			selectedRecommendationId: 'rec-1',
		});
		disposal.executionEligibility.value = sandboxEligibility({
			disposalPlanId: 'plan-1',
			eligible: false,
			reason: '模拟结果缺失',
		});

		await disposal.startExecution('plan-1');

		expect(startExecution).not.toHaveBeenCalled();
		expect(disposal.executionStartError.value).toContain('模拟结果缺失');
	});

	it('opens a persisted execution record by id', async () => {
		const executionApi = vi.fn(async () =>
			sandboxExecution({ executionId: 'exec-failed', status: 'FAILED', failureReason: 'audit mismatch' }),
		);
		const disposal = useSupplyChainDisposal(makeApi({ execution: executionApi }));

		const result = await disposal.openExecution(' exec-failed ');

		expect(executionApi).toHaveBeenCalledWith('exec-failed');
		expect(result?.status).toBe('FAILED');
		expect(disposal.execution.value?.failureReason).toBe('audit mismatch');
	});
});
