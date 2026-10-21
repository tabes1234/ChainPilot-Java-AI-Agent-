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

import { computed, ref } from 'vue';
import {
	DEFAULT_COMPARISON_HISTORY_LIMIT,
	DEFAULT_DISPOSAL_PLAN_PAGE_SIZE,
	DEFAULT_EXECUTION_HISTORY_LIMIT,
	DEFAULT_SIMULATION_HISTORY_LIMIT,
	createDisposalPlan,
	createDisposalScenarioComparison,
	createDisposalSimulation,
	createSandboxIdempotencyKey,
	disposalErrorMessage,
	fetchDisposalPlan,
	fetchDisposalPlanSimulations,
	fetchDisposalPlans,
	fetchDisposalRecommendations,
	fetchDisposalScenarioComparison,
	fetchDisposalScenarioComparisons,
	fetchDisposalSimulation,
	fetchDisposalSimulations,
	fetchSandboxExecution,
	fetchSandboxExecutionEligibility,
	fetchSandboxExecutions,
	linkComparisonCandidateToDisposalPlan,
	saveComparisonCandidateAsDisposalPlan,
	startSandboxExecution,
	updateDisposalPlanStatus,
	type CreateComparisonDisposalPlanRequest,
	type CreateDisposalPlanRequest,
	type CreateDisposalScenarioComparisonRequest,
	type DisposalPlan,
	type DisposalPlanPage,
	type DisposalPlanQuery,
	type DisposalPlanStatus,
	type DisposalPlanSummary,
	type DisposalRecommendation,
	type DisposalScenarioComparison,
	type DisposalScenarioComparisonSummary,
	type DisposalSimulation,
	type DisposalSimulationSummary,
	type RecommendationScope,
	type SandboxExecution,
	type SandboxExecutionEligibility,
	type SandboxExecutionPage,
	type SandboxExecutionSummary,
	type SimulationRequest,
	type UpdateDisposalPlanStatusRequest,
} from '../services/supplyChainDisposal';

export type DisposalApi = {
	recommendations: (scope: RecommendationScope) => Promise<DisposalRecommendation[]>;
	createSimulation: (request: SimulationRequest) => Promise<DisposalSimulation>;
	simulations: (limit: number, planId?: string) => Promise<DisposalSimulationSummary[]>;
	simulation: (simulationId: string) => Promise<DisposalSimulation>;
	plans: (query: DisposalPlanQuery) => Promise<DisposalPlanPage>;
	plan: (planId: string) => Promise<DisposalPlan>;
	planSimulations: (planId: string, limit: number) => Promise<DisposalSimulationSummary[]>;
	createPlan: (request: CreateDisposalPlanRequest) => Promise<DisposalPlan>;
	updatePlanStatus: (planId: string, request: UpdateDisposalPlanStatusRequest) => Promise<DisposalPlan>;
	createComparison: (request: CreateDisposalScenarioComparisonRequest) => Promise<DisposalScenarioComparison>;
	comparisons: (limit: number, page: number) => Promise<{
		items: DisposalScenarioComparisonSummary[];
		page: number;
		pageSize: number;
		totalElements: number;
		totalPages: number;
	}>;
	comparison: (comparisonId: string) => Promise<DisposalScenarioComparison>;
	saveComparisonCandidateAsPlan: (
		comparisonId: string,
		candidateId: string,
		request: CreateComparisonDisposalPlanRequest,
	) => Promise<DisposalPlan>;
	linkComparisonCandidatePlan: (
		comparisonId: string,
		candidateId: string,
		planId: string,
	) => Promise<DisposalScenarioComparison>;
	executionEligibility: (planId: string) => Promise<SandboxExecutionEligibility>;
	startExecution: (planId: string, idempotencyKey: string) => Promise<SandboxExecution>;
	executions: (planId: string, limit: number) => Promise<SandboxExecutionPage>;
	execution: (executionId: string) => Promise<SandboxExecution>;
};

export type DisposalComposableOptions = {
	createExecutionKey?: () => string;
};

export const defaultDisposalApi: DisposalApi = {
	recommendations: fetchDisposalRecommendations,
	createSimulation: createDisposalSimulation,
	simulations: fetchDisposalSimulations,
	simulation: fetchDisposalSimulation,
	plans: fetchDisposalPlans,
	plan: fetchDisposalPlan,
	planSimulations: fetchDisposalPlanSimulations,
	createPlan: createDisposalPlan,
	updatePlanStatus: updateDisposalPlanStatus,
	createComparison: createDisposalScenarioComparison,
	comparisons: fetchDisposalScenarioComparisons,
	comparison: fetchDisposalScenarioComparison,
	saveComparisonCandidateAsPlan: saveComparisonCandidateAsDisposalPlan,
	linkComparisonCandidatePlan: linkComparisonCandidateToDisposalPlan,
	executionEligibility: fetchSandboxExecutionEligibility,
	startExecution: startSandboxExecution,
	executions: fetchSandboxExecutions,
	execution: fetchSandboxExecution,
};

function normalizeScope(scope: RecommendationScope): RecommendationScope {
	const normalized: RecommendationScope = {};
	if (scope.sku?.trim()) {
		normalized.sku = scope.sku.trim();
	}
	if (scope.supplierId?.trim()) {
		normalized.supplierId = scope.supplierId.trim();
	}
	return normalized;
}

function scopeKey(scope: RecommendationScope): string {
	return `${scope.sku || ''}:${scope.supplierId || ''}`;
}

export function useSupplyChainDisposal(
	api: DisposalApi = defaultDisposalApi,
	options: DisposalComposableOptions = {},
) {
	const createExecutionKey = options.createExecutionKey ?? createSandboxIdempotencyKey;
	const recommendations = ref<DisposalRecommendation[]>([]);
	const recommendationsLoading = ref(false);
	const recommendationsError = ref('');
	const recommendationsLoaded = ref(false);
	let recommendationSequence = 0;
	let activeRecommendationScopeKey = '';

	const simulation = ref<DisposalSimulation | null>(null);
	const simulationRunning = ref(false);
	const simulationDetailLoading = ref(false);
	const simulationError = ref('');
	const simulationHistory = ref<DisposalSimulationSummary[]>([]);
	const simulationHistoryLoading = ref(false);
	const simulationHistoryError = ref('');
	const simulationHistoryLimit = ref(DEFAULT_SIMULATION_HISTORY_LIMIT);
	const simulationPlanId = ref<string | null>(null);
	let simulationSequence = 0;
	let simulationHistorySequence = 0;

	const plans = ref<DisposalPlanSummary[]>([]);
	const plansLoading = ref(false);
	const plansError = ref('');
	const plansLoaded = ref(false);
	const planStatus = ref<'ALL' | DisposalPlanStatus>('ALL');
	const plansPage = ref(1);
	const plansPageSize = ref(DEFAULT_DISPOSAL_PLAN_PAGE_SIZE);
	const plansTotalElements = ref(0);
	const plansTotalPages = ref(0);
	let plansSequence = 0;
	let activePlansKey = '';

	const activePlan = ref<DisposalPlan | null>(null);
	const activePlanSimulations = ref<DisposalSimulationSummary[]>([]);
	const activePlanSimulationsLoading = ref(false);
	const planDetailLoading = ref(false);
	const planDetailError = ref('');
	let planDetailSequence = 0;

	const planSaving = ref(false);
	const planSaveError = ref('');
	const planSaveNotice = ref('');
	const planStatusUpdating = ref(false);
	const planStatusError = ref('');

	const executionEligibility = ref<SandboxExecutionEligibility | null>(null);
	const executionEligibilityLoading = ref(false);
	const executionEligibilityError = ref('');
	const executions = ref<SandboxExecutionSummary[]>([]);
	const executionsTotal = ref(0);
	const executionsLoading = ref(false);
	const executionsError = ref('');
	const executionsLoaded = ref(false);
	const execution = ref<SandboxExecution | null>(null);
	const executionDetailLoading = ref(false);
	const executionDetailError = ref('');
	const executionStarting = ref(false);
	const executionStartError = ref('');
	const executionStartNotice = ref('');
	const executionIdempotencyKey = ref<string | null>(null);
	const executionKeyPlanId = ref<string | null>(null);
	let eligibilitySequence = 0;
	let executionsSequence = 0;
	let executionDetailSequence = 0;
	let executionStartSequence = 0;

	const scenarioComparison = ref<DisposalScenarioComparison | null>(null);
	const comparisonRunning = ref(false);
	const comparisonError = ref('');
	const comparisonHistory = ref<DisposalScenarioComparisonSummary[]>([]);
	const comparisonHistoryLoading = ref(false);
	const comparisonHistoryError = ref('');
	const comparisonHistoryLimit = ref(DEFAULT_COMPARISON_HISTORY_LIMIT);
	const comparisonDetailLoading = ref(false);
	const comparisonSavingCandidateId = ref<string | null>(null);
	const comparisonSaveError = ref('');
	const comparisonSaveNotice = ref('');
	const comparisonLinking = ref(false);
	const comparisonLinkError = ref('');
	const comparisonLinkNotice = ref('');
	let comparisonSequence = 0;
	let comparisonHistorySequence = 0;
	let comparisonDetailSequence = 0;

	const canStartExecution = computed(() => {
		const plan = activePlan.value;
		const eligibility = executionEligibility.value;
		return Boolean(
			plan &&
				plan.status === 'SELECTED' &&
				eligibility?.disposalPlanId === plan.planId &&
				eligibility.eligible &&
				!executionStarting.value &&
				!executionEligibilityLoading.value,
		);
	});

	const canPlanPrevious = computed(
		() => !plansLoading.value && plansTotalPages.value > 0 && plansPage.value > 1,
	);
	const canPlanNext = computed(
		() => !plansLoading.value && plansTotalPages.value > 0 && plansPage.value < plansTotalPages.value,
	);
	const plansRangeLabel = computed(() => {
		if (plansError.value) {
			return '分页信息不可用';
		}
		if (plansTotalPages.value > 0) {
			return `共 ${plansTotalElements.value} 条 · 第 ${plansPage.value} / ${plansTotalPages.value} 页`;
		}
		if (plansLoaded.value && !plansLoading.value) {
			return '共 0 条';
		}
		return '正在加载分页信息…';
	});

	async function loadRecommendations(scope: RecommendationScope = {}) {
		const normalized = normalizeScope(scope);
		const key = scopeKey(normalized);
		if (recommendationsLoading.value && key === activeRecommendationScopeKey) {
			return;
		}
		const sequence = ++recommendationSequence;
		activeRecommendationScopeKey = key;
		recommendationsLoading.value = true;
		recommendationsError.value = '';
		try {
			const result = await api.recommendations(normalized);
			if (sequence !== recommendationSequence) {
				return;
			}
			recommendations.value = result;
			recommendationsLoaded.value = true;
		} catch (cause) {
			if (sequence !== recommendationSequence) {
				return;
			}
			recommendations.value = [];
			recommendationsLoaded.value = true;
			recommendationsError.value = disposalErrorMessage(cause, '无法加载处置建议。');
		} finally {
			if (sequence === recommendationSequence) {
				recommendationsLoading.value = false;
				activeRecommendationScopeKey = '';
			}
		}
	}

	async function runSimulation(request: SimulationRequest) {
		if (simulationRunning.value) {
			return null;
		}
		const sequence = ++simulationSequence;
		simulationRunning.value = true;
		simulationError.value = '';
		try {
			const result = await api.createSimulation(request);
			if (sequence !== simulationSequence) {
				return null;
			}
			simulation.value = result;
			simulationPlanId.value = null;
			await loadSimulationHistory();
			return result;
		} catch (cause) {
			if (sequence === simulationSequence) {
				simulationError.value = disposalErrorMessage(cause, '模拟运行失败。');
			}
			return null;
		} finally {
			if (sequence === simulationSequence) {
				simulationRunning.value = false;
			}
		}
	}

	async function loadSimulationHistory(limit = simulationHistoryLimit.value) {
		const sequence = ++simulationHistorySequence;
		simulationHistoryLoading.value = true;
		simulationHistoryError.value = '';
		try {
			const result = await api.simulations(limit);
			if (sequence !== simulationHistorySequence) {
				return;
			}
			simulationHistory.value = result;
			simulationHistoryLimit.value = limit;
		} catch (cause) {
			if (sequence !== simulationHistorySequence) {
				return;
			}
			simulationHistory.value = [];
			simulationHistoryError.value = disposalErrorMessage(cause, '无法加载模拟历史。');
		} finally {
			if (sequence === simulationHistorySequence) {
				simulationHistoryLoading.value = false;
			}
		}
	}

	async function openSimulation(simulationId: string, planId?: string | null) {
		if (!simulationId.trim() || simulationDetailLoading.value) {
			return null;
		}
		const sequence = ++simulationSequence;
		simulationDetailLoading.value = true;
		simulationError.value = '';
		try {
			const result = await api.simulation(simulationId.trim());
			if (sequence !== simulationSequence) {
				return null;
			}
			simulation.value = result;
			simulationPlanId.value = planId?.trim() || null;
			return result;
		} catch (cause) {
			if (sequence === simulationSequence) {
				simulationError.value = disposalErrorMessage(cause, '无法读取模拟结果。');
			}
			return null;
		} finally {
			if (sequence === simulationSequence) {
				simulationDetailLoading.value = false;
			}
		}
	}

	function clearSimulation() {
		simulationSequence += 1;
		simulation.value = null;
		simulationError.value = '';
		simulationPlanId.value = null;
	}

	async function runComparison(request: CreateDisposalScenarioComparisonRequest) {
		if (comparisonRunning.value) {
			return null;
		}
		const sequence = ++comparisonSequence;
		comparisonRunning.value = true;
		comparisonError.value = '';
		comparisonSaveError.value = '';
		comparisonSaveNotice.value = '';
		comparisonLinkError.value = '';
		comparisonLinkNotice.value = '';
		try {
			const result = await api.createComparison(request);
			if (sequence !== comparisonSequence) {
				return null;
			}
			scenarioComparison.value = result;
			await loadComparisonHistory();
			return result;
		} catch (cause) {
			if (sequence === comparisonSequence) {
				comparisonError.value = disposalErrorMessage(cause, '方案对比失败。');
			}
			return null;
		} finally {
			if (sequence === comparisonSequence) {
				comparisonRunning.value = false;
			}
		}
	}

	async function loadComparisonHistory(limit = comparisonHistoryLimit.value) {
		const sequence = ++comparisonHistorySequence;
		comparisonHistoryLoading.value = true;
		comparisonHistoryError.value = '';
		try {
			const result = await api.comparisons(limit, 1);
			if (sequence !== comparisonHistorySequence) {
				return;
			}
			comparisonHistory.value = result.items;
			comparisonHistoryLimit.value = limit;
		} catch (cause) {
			if (sequence !== comparisonHistorySequence) {
				return;
			}
			comparisonHistory.value = [];
			comparisonHistoryError.value = disposalErrorMessage(cause, '无法加载方案对比历史。');
		} finally {
			if (sequence === comparisonHistorySequence) {
				comparisonHistoryLoading.value = false;
			}
		}
	}

	async function openComparison(comparisonId: string) {
		const normalized = comparisonId.trim();
		if (!normalized || comparisonDetailLoading.value) {
			return null;
		}
		const sequence = ++comparisonDetailSequence;
		comparisonDetailLoading.value = true;
		comparisonError.value = '';
		comparisonSaveError.value = '';
		comparisonSaveNotice.value = '';
		comparisonLinkError.value = '';
		comparisonLinkNotice.value = '';
		try {
			const result = await api.comparison(normalized);
			if (sequence !== comparisonDetailSequence) {
				return null;
			}
			scenarioComparison.value = result;
			return result;
		} catch (cause) {
			if (sequence === comparisonDetailSequence) {
				comparisonError.value = disposalErrorMessage(cause, '无法读取方案对比。');
			}
			return null;
		} finally {
			if (sequence === comparisonDetailSequence) {
				comparisonDetailLoading.value = false;
			}
		}
	}

	function clearComparison() {
		comparisonSequence += 1;
		comparisonDetailSequence += 1;
		comparisonRunning.value = false;
		comparisonDetailLoading.value = false;
		scenarioComparison.value = null;
		comparisonError.value = '';
		comparisonSaveError.value = '';
		comparisonSaveNotice.value = '';
		comparisonLinkError.value = '';
		comparisonLinkNotice.value = '';
	}

	async function saveComparisonCandidateAsPlan(
		comparisonId: string,
		candidateId: string,
		request: CreateComparisonDisposalPlanRequest,
	) {
		if (comparisonSavingCandidateId.value) {
			return null;
		}
		comparisonSavingCandidateId.value = candidateId;
		comparisonSaveError.value = '';
		comparisonSaveNotice.value = '';
		comparisonLinkError.value = '';
		comparisonLinkNotice.value = '';
		try {
			const result = await api.saveComparisonCandidateAsPlan(comparisonId, candidateId, request);
			if (scenarioComparison.value?.comparisonId === comparisonId) {
				scenarioComparison.value = {
					...scenarioComparison.value,
					candidates: scenarioComparison.value.candidates.map((candidate) =>
						candidate.candidateId === candidateId
							? { ...candidate, linkedPlanId: result.planId }
							: candidate,
					),
				};
			}
			activePlan.value = result;
			resetExecutionState();
			activePlanSimulations.value = result.simulation
				? [
						{
							simulationId: result.simulation.simulationId,
							scenario: result.simulation.scenario,
							sku: result.simulation.sku,
							supplierId: result.simulation.supplierId,
							parameterSummary: '',
							affectedOrderCount: result.simulation.affectedOrders.length,
							atRiskOrdersDelta: result.simulation.delta.atRiskOrders,
							planId: result.planId,
							createdAt: result.simulation.createdAt,
						},
					]
				: [];
			comparisonSaveNotice.value = `候选已保存为处置方案 ${result.planId}，可在下方方案依据中确认并执行演练。`;
			await Promise.all([
				loadPlans(),
				loadComparisonHistory(),
				loadExecutions(result.planId),
				loadExecutionEligibility(result.planId),
			]);
			return result;
		} catch (cause) {
			comparisonSaveError.value = disposalErrorMessage(cause, '无法将候选保存为处置方案。');
			return null;
		} finally {
			comparisonSavingCandidateId.value = null;
		}
	}

	async function linkComparisonCandidatePlan(comparisonId: string, candidateId: string, planId: string) {
		if (comparisonLinking.value) {
			return null;
		}
		comparisonLinking.value = true;
		comparisonLinkError.value = '';
		comparisonLinkNotice.value = '';
		comparisonSaveError.value = '';
		comparisonSaveNotice.value = '';
		try {
			const result = await api.linkComparisonCandidatePlan(comparisonId, candidateId, planId);
			scenarioComparison.value = result;
			comparisonLinkNotice.value = `候选已关联处置方案 ${planId.trim()}。`;
			await loadComparisonHistory();
			return result;
		} catch (cause) {
			comparisonLinkError.value = disposalErrorMessage(cause, '无法关联已有处置方案。');
			return null;
		} finally {
			comparisonLinking.value = false;
		}
	}

	async function loadExecutionEligibility(planId: string) {
		const normalized = planId.trim();
		if (!normalized) {
			return null;
		}
		const sequence = ++eligibilitySequence;
		executionEligibilityLoading.value = true;
		executionEligibilityError.value = '';
		try {
			const result = await api.executionEligibility(normalized);
			if (sequence !== eligibilitySequence || activePlan.value?.planId !== normalized) {
				return null;
			}
			executionEligibility.value = result;
			return result;
		} catch (cause) {
			if (sequence === eligibilitySequence) {
				executionEligibility.value = null;
				executionEligibilityError.value = disposalErrorMessage(cause, '无法检查执行演练条件。');
			}
			return null;
		} finally {
			if (sequence === eligibilitySequence) {
				executionEligibilityLoading.value = false;
			}
		}
	}

	async function loadExecutions(planId: string, limit = DEFAULT_EXECUTION_HISTORY_LIMIT) {
		const normalized = planId.trim();
		if (!normalized) {
			return [];
		}
		const sequence = ++executionsSequence;
		executionsLoading.value = true;
		executionsError.value = '';
		try {
			const result = await api.executions(normalized, limit);
			if (sequence !== executionsSequence || activePlan.value?.planId !== normalized) {
				return [];
			}
			executions.value = result.items;
			executionsTotal.value = result.totalElements;
			executionsLoaded.value = true;
			return result.items;
		} catch (cause) {
			if (sequence === executionsSequence) {
				executions.value = [];
				executionsTotal.value = 0;
				executionsLoaded.value = true;
				executionsError.value = disposalErrorMessage(cause, '无法加载执行演练记录。');
			}
			return [];
		} finally {
			if (sequence === executionsSequence) {
				executionsLoading.value = false;
			}
		}
	}

	async function openExecution(executionId: string) {
		const normalized = executionId.trim();
		if (!normalized || executionDetailLoading.value) {
			return null;
		}
		const sequence = ++executionDetailSequence;
		executionDetailLoading.value = true;
		executionDetailError.value = '';
		try {
			const result = await api.execution(normalized);
			if (sequence !== executionDetailSequence) {
				return null;
			}
			execution.value = result;
			return result;
		} catch (cause) {
			if (sequence === executionDetailSequence) {
				executionDetailError.value = disposalErrorMessage(cause, '无法读取执行演练详情。');
			}
			return null;
		} finally {
			if (sequence === executionDetailSequence) {
				executionDetailLoading.value = false;
			}
		}
	}

	function resetExecutionState() {
		eligibilitySequence += 1;
		executionsSequence += 1;
		executionDetailSequence += 1;
		executionStartSequence += 1;
		executionEligibility.value = null;
		executionEligibilityLoading.value = false;
		executionEligibilityError.value = '';
		executions.value = [];
		executionsTotal.value = 0;
		executionsLoading.value = false;
		executionsError.value = '';
		executionsLoaded.value = false;
		execution.value = null;
		executionDetailLoading.value = false;
		executionDetailError.value = '';
		executionStarting.value = false;
		executionStartError.value = '';
		executionStartNotice.value = '';
		executionIdempotencyKey.value = null;
		executionKeyPlanId.value = null;
	}

	async function startExecution(planId: string) {
		const normalized = planId.trim();
		const plan = activePlan.value;
		if (!normalized || !plan || plan.planId !== normalized) {
			executionStartError.value = '请先打开对应的处置方案。';
			return null;
		}
		if (executionStarting.value) {
			return null;
		}
		if (plan.status !== 'SELECTED') {
			executionStartError.value = '只有已选定的处置方案可以发起执行演练。';
			return null;
		}
		if (executionEligibility.value?.disposalPlanId === normalized && !executionEligibility.value.eligible) {
			executionStartError.value =
				executionEligibility.value.reason || '当前方案不满足执行演练前置条件，请刷新后重试。';
			return null;
		}
		if (!executionIdempotencyKey.value || executionKeyPlanId.value !== normalized) {
			executionIdempotencyKey.value = createExecutionKey();
			executionKeyPlanId.value = normalized;
		}
		const requestKey = executionIdempotencyKey.value as string;
		const sequence = ++executionStartSequence;
		executionStarting.value = true;
		executionStartError.value = '';
		executionStartNotice.value = '';
		try {
			const result = await api.startExecution(normalized, requestKey);
			if (sequence !== executionStartSequence) {
				return result;
			}
			execution.value = result;
			executionIdempotencyKey.value = null;
			executionKeyPlanId.value = null;
			if (result.status === 'FAILED') {
				executionStartError.value =
					result.failureReason || '执行演练未完成，请查看失败步骤后使用新的请求编号重试。';
			} else {
				executionStartNotice.value = `执行演练已完成，审计记录 ${result.executionId}；未修改真实业务数据。`;
			}
			await Promise.all([loadExecutions(normalized), loadExecutionEligibility(normalized)]);
			return result;
		} catch (cause) {
			if (sequence === executionStartSequence) {
				executionStartError.value = disposalErrorMessage(cause, '执行演练失败，请稍后重试。');
				await Promise.all([loadExecutions(normalized), loadExecutionEligibility(normalized)]);
			}
			return null;
		} finally {
			if (sequence === executionStartSequence) {
				executionStarting.value = false;
			}
		}
	}

	function currentPlansKey() {
		return `${planStatus.value}:${plansPage.value}:${plansPageSize.value}`;
	}

	function resetPlans() {
		plans.value = [];
		plansTotalElements.value = 0;
		plansTotalPages.value = 0;
		plansLoaded.value = false;
	}

	async function loadPlans(force = false) {
		const key = currentPlansKey();
		if (!force && plansLoading.value && key === activePlansKey) {
			return;
		}
		const sequence = ++plansSequence;
		activePlansKey = key;
		plansLoading.value = true;
		plansError.value = '';
		try {
			const result = await api.plans({
				status: planStatus.value,
				page: plansPage.value,
				pageSize: plansPageSize.value,
			});
			if (sequence !== plansSequence) {
				return;
			}
			plans.value = result.items;
			plansPage.value = result.page;
			plansPageSize.value = result.pageSize;
			plansTotalElements.value = result.totalElements;
			plansTotalPages.value = result.totalPages;
			plansLoaded.value = true;
		} catch (cause) {
			if (sequence !== plansSequence) {
				return;
			}
			resetPlans();
			plansLoaded.value = true;
			plansError.value = disposalErrorMessage(cause, '无法加载处置方案。');
		} finally {
			if (sequence === plansSequence) {
				plansLoading.value = false;
				activePlansKey = '';
			}
		}
	}

	function setPlanStatus(status: 'ALL' | DisposalPlanStatus) {
		if (status === planStatus.value) {
			return;
		}
		planStatus.value = status;
		plansPage.value = 1;
		resetPlans();
		return loadPlans();
	}

	function setPlanPageSize(value: number) {
		if (!Number.isInteger(value) || value < 1 || value === plansPageSize.value) {
			return;
		}
		plansPageSize.value = value;
		plansPage.value = 1;
		resetPlans();
		return loadPlans();
	}

	function goToPlanPage(value: number) {
		if (!Number.isInteger(value)) {
			return;
		}
		const lastPage = Math.max(plansTotalPages.value, 1);
		const target = Math.min(Math.max(value, 1), lastPage);
		if (target === plansPage.value && !plansError.value) {
			return;
		}
		plansPage.value = target;
		resetPlans();
		return loadPlans();
	}

	function previousPlanPage() {
		if (!canPlanPrevious.value) {
			return;
		}
		return goToPlanPage(plansPage.value - 1);
	}

	function nextPlanPage() {
		if (!canPlanNext.value) {
			return;
		}
		return goToPlanPage(plansPage.value + 1);
	}

	async function refreshActivePlanAfterConfirmation(planId: string) {
		const sequence = ++planDetailSequence;
		planDetailLoading.value = true;
		try {
			const detail = await api.plan(planId).catch((cause) => {
				if (sequence === planDetailSequence && activePlan.value?.planId === planId) {
					planDetailError.value = disposalErrorMessage(cause, '无法刷新处置方案。');
				}
				return null;
			});
			if (sequence !== planDetailSequence || activePlan.value?.planId !== planId) {
				return;
			}
			if (detail) {
				activePlan.value = detail;
				planDetailError.value = '';
			}
			await Promise.all([loadExecutionEligibility(planId), loadExecutions(planId)]);
		} finally {
			if (sequence === planDetailSequence) {
				planDetailLoading.value = false;
			}
		}
	}

	async function refreshAfterConfirmedProposal(planId?: string | null) {
		const normalized = planId?.trim() ?? '';
		const refreshActive = Boolean(normalized) && activePlan.value?.planId === normalized;
		const tasks: Promise<unknown>[] = [loadPlans(true)];
		if (refreshActive) {
			tasks.push(refreshActivePlanAfterConfirmation(normalized));
		}
		await Promise.all(tasks);
	}

	async function openPlan(planId: string) {
		if (!planId.trim() || planDetailLoading.value) {
			return null;
		}
		const sequence = ++planDetailSequence;
		planDetailLoading.value = true;
		planDetailError.value = '';
		planStatusError.value = '';
		resetExecutionState();
		try {
			const result = await api.plan(planId.trim());
			if (sequence !== planDetailSequence) {
				return null;
			}
			activePlan.value = result;
			activePlanSimulationsLoading.value = true;
			try {
				const simulations = await api.planSimulations(planId.trim(), DEFAULT_SIMULATION_HISTORY_LIMIT);
				if (sequence === planDetailSequence) {
					activePlanSimulations.value = simulations;
				}
			} catch {
				if (sequence === planDetailSequence) {
					activePlanSimulations.value = [];
				}
			} finally {
				if (sequence === planDetailSequence) {
					activePlanSimulationsLoading.value = false;
				}
			}
			if (sequence === planDetailSequence) {
				await Promise.all([loadExecutions(result.planId), loadExecutionEligibility(result.planId)]);
			}
			return result;
		} catch (cause) {
			if (sequence === planDetailSequence) {
				planDetailError.value = disposalErrorMessage(cause, '无法读取处置方案。');
			}
			return null;
		} finally {
			if (sequence === planDetailSequence) {
				planDetailLoading.value = false;
			}
		}
	}

	function clearPlan() {
		planDetailSequence += 1;
		activePlan.value = null;
		activePlanSimulations.value = [];
		activePlanSimulationsLoading.value = false;
		planDetailError.value = '';
		planStatusError.value = '';
		resetExecutionState();
	}

	async function savePlan(request: CreateDisposalPlanRequest) {
		if (planSaving.value) {
			return null;
		}
		planSaving.value = true;
		planSaveError.value = '';
		planSaveNotice.value = '';
		planStatusError.value = '';
		try {
			const result = await api.createPlan(request);
			activePlan.value = result;
			resetExecutionState();
			planSaveNotice.value = `处置方案已保存：${result.planId}`;
			if (request.simulationId) {
				simulationPlanId.value = result.planId;
			}
			if (result.simulation) {
				simulation.value = result.simulation;
				simulationPlanId.value = result.planId;
			}
			activePlanSimulations.value = result.simulation
				? [
						{
							simulationId: result.simulation.simulationId,
							scenario: result.simulation.scenario,
							sku: result.simulation.sku,
							supplierId: result.simulation.supplierId,
							parameterSummary: '',
							affectedOrderCount: result.simulation.affectedOrders.length,
							atRiskOrdersDelta: result.simulation.delta.atRiskOrders,
							planId: result.planId,
							createdAt: result.simulation.createdAt,
						},
					]
				: [];
			await Promise.all([
				loadPlans(),
				loadSimulationHistory(),
				loadExecutions(result.planId),
				loadExecutionEligibility(result.planId),
			]);
			return result;
		} catch (cause) {
			planSaveError.value = disposalErrorMessage(cause, '无法保存处置方案。');
			return null;
		} finally {
			planSaving.value = false;
		}
	}

	async function updatePlanStatus(
		planId: string,
		status: DisposalPlanStatus,
		selectedRecommendationId?: string,
	) {
		if (planStatusUpdating.value) {
			return null;
		}
		const payload: UpdateDisposalPlanStatusRequest = { status };
		if (status === 'SELECTED') {
			if (!selectedRecommendationId?.trim()) {
				planStatusError.value = '请先选择要确认采用的处置建议。';
				return null;
			}
			payload.selectedRecommendationId = selectedRecommendationId.trim();
		}
		planStatusUpdating.value = true;
		planStatusError.value = '';
		try {
			const updated = await api.updatePlanStatus(planId, payload);
			if (activePlan.value?.planId === updated.planId) {
				activePlan.value = updated;
				executionStartError.value = '';
				executionStartNotice.value = '';
				await loadExecutionEligibility(updated.planId);
			}
			await loadPlans();
			return updated;
		} catch (cause) {
			planStatusError.value = disposalErrorMessage(cause, '无法更新方案状态。');
			return null;
		} finally {
			planStatusUpdating.value = false;
		}
	}

	function initialize() {
		return Promise.all([
			loadRecommendations(),
			loadPlans(),
			loadSimulationHistory(),
			loadComparisonHistory(),
		]);
	}

	return {
		recommendations,
		recommendationsLoading,
		recommendationsError,
		recommendationsLoaded,
		simulation,
		simulationRunning,
		simulationDetailLoading,
		simulationError,
		simulationHistory,
		simulationHistoryLoading,
		simulationHistoryError,
		simulationHistoryLimit,
		simulationPlanId,
		plans,
		plansLoading,
		plansError,
		plansLoaded,
		planStatus,
		plansPage,
		plansPageSize,
		plansTotalElements,
		plansTotalPages,
		canPlanPrevious,
		canPlanNext,
		plansRangeLabel,
		activePlan,
		activePlanSimulations,
		activePlanSimulationsLoading,
		planDetailLoading,
		planDetailError,
		planSaving,
		planSaveError,
		planSaveNotice,
		planStatusUpdating,
		planStatusError,
		executionEligibility,
		executionEligibilityLoading,
		executionEligibilityError,
		executions,
		executionsTotal,
		executionsLoading,
		executionsError,
		executionsLoaded,
		execution,
		executionDetailLoading,
		executionDetailError,
		executionStarting,
		executionStartError,
		executionStartNotice,
		executionIdempotencyKey,
		canStartExecution,
		scenarioComparison,
		comparisonRunning,
		comparisonError,
		comparisonHistory,
		comparisonHistoryLoading,
		comparisonHistoryError,
		comparisonHistoryLimit,
		comparisonDetailLoading,
		comparisonSavingCandidateId,
		comparisonSaveError,
		comparisonSaveNotice,
		comparisonLinking,
		comparisonLinkError,
		comparisonLinkNotice,
		loadRecommendations,
		runSimulation,
		loadSimulationHistory,
		openSimulation,
		clearSimulation,
		runComparison,
		loadComparisonHistory,
		openComparison,
		clearComparison,
		saveComparisonCandidateAsPlan,
		linkComparisonCandidatePlan,
		loadPlans,
		refreshAfterConfirmedProposal,
		setPlanStatus,
		setPlanPageSize,
		previousPlanPage,
		nextPlanPage,
		openPlan,
		clearPlan,
		savePlan,
		updatePlanStatus,
		loadExecutionEligibility,
		loadExecutions,
		openExecution,
		startExecution,
		initialize,
	};
}
