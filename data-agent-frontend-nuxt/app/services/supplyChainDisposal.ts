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

export type DisposalRiskLevel = 'LOW' | 'MEDIUM' | 'HIGH';
export type DisposalPriority = 'P1' | 'P2' | 'P3';
export type DisposalActionType = 'REPLENISH_INVENTORY' | 'FOLLOW_UP_SUPPLIER' | 'MANUAL_CONFIRMATION';
export type SimulationScenario = 'INVENTORY_REPLENISHMENT' | 'SUPPLIER_DELAY';
export type DisposalPlanStatus = 'PENDING_CONFIRMATION' | 'SELECTED' | 'REJECTED';

export type DisposalRecommendation = {
	recommendationId: string;
	actionType: DisposalActionType;
	priority: DisposalPriority;
	priorityScore: number;
	priorityBasis: string;
	riskLevel: DisposalRiskLevel;
	sku: string | null;
	supplierId: string | null;
	affectedOrderIds: string[];
	title: string;
	action: string;
	rationale: string;
	businessBasis: string[];
	expectedImprovement: string;
	prerequisites: string[];
	sideEffects: string[];
	sources: string[];
	assumptions: string[];
	status: string;
	generatedAt: string;
};

export type SimulationRequest = {
	scenario: SimulationScenario;
	sku?: string;
	replenishmentQuantity?: number;
	supplierId?: string;
	additionalDelayDays?: number;
};

export type SimulationMetrics = {
	totalOrders: number;
	atRiskOrders: number;
	highRiskOrders: number;
	ordersWithShortage: number;
	totalShortageUnits: number;
	totalRiskScore: number;
	targetSkuAvailableToPromise: number | null;
};

export type SimulationDelta = SimulationMetrics;

export type OrderRiskImpact = {
	orderId: string;
	sku: string;
	supplierId: string;
	baselineEstimatedDeliveryDate: string;
	simulatedEstimatedDeliveryDate: string;
	baselineRiskLevel: DisposalRiskLevel;
	simulatedRiskLevel: DisposalRiskLevel;
	baselineRiskScore: number;
	simulatedRiskScore: number;
	baselineInventoryShortage: number;
	simulatedInventoryShortage: number;
	changes: string[];
};

export type DisposalSimulation = {
	simulationId: string;
	scenario: SimulationScenario;
	sku: string | null;
	supplierId: string | null;
	replenishmentQuantity: number | null;
	additionalDelayDays: number | null;
	asOfDate: string;
	baselineSource: string;
	baseline: SimulationMetrics;
	simulated: SimulationMetrics;
	delta: SimulationDelta;
	affectedOrders: OrderRiskImpact[];
	assumptions: string[];
	limitations: string[];
	evidence: string[];
	realDataMutated: boolean;
	disclaimer: string;
	createdAt: string;
};

export type DisposalSimulationSummary = {
	simulationId: string;
	scenario: SimulationScenario;
	sku: string | null;
	supplierId: string | null;
	parameterSummary: string;
	affectedOrderCount: number;
	atRiskOrdersDelta: number;
	planId: string | null;
	createdAt: string;
};

export type KnowledgeCitation = {
	sourceId: string;
	title: string;
	excerpt: string;
};

export type DisposalPlan = {
	planId: string;
	title: string;
	taskId: string | null;
	scopeSku: string | null;
	scopeSupplierId: string | null;
	status: DisposalPlanStatus;
	selectedRecommendationId: string | null;
	recommendations: DisposalRecommendation[];
	simulation: DisposalSimulation | null;
	evidence: KnowledgeCitation[];
	explanation: string;
	explanationSource: string;
	explanationModelAssisted: boolean;
	explanationToolCalls: string[];
	createdAt: string;
	updatedAt: string;
};

export type SandboxExecutionMode = 'SANDBOX';
export type SandboxExecutionStatus = 'RUNNING' | 'SUCCEEDED' | 'FAILED';
export type SandboxStepStatus = 'SUCCEEDED' | 'FAILED';
export type SandboxDraftType = 'REPLENISHMENT_TASK_DRAFT' | 'SUPPLIER_FOLLOW_UP_DRAFT';

export type SandboxExecutionStep = {
	sequence: number;
	step: string;
	name: string;
	status: SandboxStepStatus;
	inputSummary: string;
	validationResult: string;
	resultSummary: string | null;
	failureReason: string | null;
	startedAt: string;
	completedAt: string;
};

export type SandboxTaskDraft = {
	taskDraftId: string;
	draftType: SandboxDraftType;
	title: string;
	sku: string | null;
	supplierId: string | null;
	replenishmentQuantity: number | null;
	currentOnHandQuantity: number | null;
	currentAvailableToPromise: number | null;
	targetOnHandQuantity: number | null;
	targetAvailableToPromise: number | null;
	linkedOrderIds: string[];
	recommendedActions: string[];
	pendingConfirmations: string[];
	sourceSimulationId: string | null;
	evidenceSummary: string;
	createdAt: string;
};

export type SandboxExecution = {
	executionId: string;
	disposalPlanId: string;
	recommendationId: string;
	actionType: DisposalActionType;
	mode: SandboxExecutionMode;
	status: SandboxExecutionStatus;
	sourceSimulationId: string | null;
	idempotencyKey: string;
	steps: SandboxExecutionStep[];
	taskDraft: SandboxTaskDraft | null;
	evidence: string[];
	finalResult: string | null;
	unresolvedItems: string[];
	failureReason: string | null;
	realDataMutated: boolean;
	disclaimer: string;
	createdAt: string;
	startedAt: string;
	completedAt: string | null;
	updatedAt: string;
};

export type SandboxExecutionSummary = {
	executionId: string;
	disposalPlanId: string;
	actionType: DisposalActionType;
	mode: SandboxExecutionMode;
	status: SandboxExecutionStatus;
	sourceSimulationId: string | null;
	idempotencyKey: string;
	taskDraftId: string | null;
	draftType: SandboxDraftType | null;
	title: string | null;
	stepCount: number;
	failedStepCount: number;
	createdAt: string;
	completedAt: string | null;
};

export type SandboxExecutionPage = {
	items: SandboxExecutionSummary[];
	page: number;
	pageSize: number;
	totalElements: number;
	totalPages: number;
};

export type SandboxExecutionEligibility = {
	disposalPlanId: string;
	planStatus: DisposalPlanStatus;
	eligible: boolean;
	recommendationId: string | null;
	actionType: DisposalActionType | null;
	sourceSimulationId: string | null;
	checks: string[];
	reason: string | null;
	checkedAt: string;
};

export type DisposalPlanSummary = {
	planId: string;
	title: string;
	status: DisposalPlanStatus;
	taskId: string | null;
	scopeSku: string | null;
	scopeSupplierId: string | null;
	recommendationCount: number;
	simulationScenario: SimulationScenario | null;
	atRiskOrdersDelta: number | null;
	createdAt: string;
	updatedAt: string;
};

export type DisposalPlanPage = {
	items: DisposalPlanSummary[];
	page: number;
	pageSize: number;
	totalElements: number;
	totalPages: number;
};

export type DisposalScenarioComparisonOutcome = 'RECOMMENDED' | 'TIE_BREAK' | 'INCOMPARABLE';

export type DisposalScenarioCandidate = {
	candidateId: string;
	label: string;
	position: number;
	scenario: SimulationScenario;
	sku: string | null;
	supplierId: string | null;
	replenishmentQuantity: number | null;
	additionalDelayDays: number | null;
	simulation: DisposalSimulation;
	linkedPlanId: string | null;
	createdAt: string;
};

export type DisposalScenarioRanking = {
	candidateId: string;
	label: string;
	parameterSummary: string;
	rank: number | null;
	tied: boolean;
	recommended: boolean;
	comparable: boolean;
	rankingBasis: string;
	keyDeltas: string[];
	reasons: string[];
	tradeoffs: string[];
};

export type DisposalScenarioRecommendation = {
	outcome: DisposalScenarioComparisonOutcome;
	recommendedCandidateId: string;
	recommendedLabel: string;
	recommendedRank: number | null;
	summary: string;
	keyDeltas: string[];
	reasons: string[];
	tradeoffs: string[];
	assumptions: string[];
	unknowns: string[];
	manualConfirmation: string[];
	evidence: string[];
	disclaimer: string;
};

export type DisposalScenarioComparison = {
	comparisonId: string;
	title: string;
	scenario: SimulationScenario;
	sku: string | null;
	supplierId: string | null;
	asOfDate: string;
	baselineSource: string;
	baselineFingerprint: string;
	baselineCapturedAt: string;
	baseline: SimulationMetrics;
	bestAtRiskOrdersDelta: number | null;
	bestTotalShortageUnitsDelta: number | null;
	candidates: DisposalScenarioCandidate[];
	rankings: DisposalScenarioRanking[];
	recommendation: DisposalScenarioRecommendation;
	rankingRules: string[];
	assumptions: string[];
	limitations: string[];
	evidence: string[];
	realDataMutated: boolean;
	disclaimer: string;
	createdAt: string;
	updatedAt: string;
};

export type DisposalScenarioComparisonSummary = {
	comparisonId: string;
	title: string;
	scenario: SimulationScenario;
	sku: string | null;
	supplierId: string | null;
	candidateCount: number;
	baselineSource: string;
	baselineFingerprint: string;
	outcome: DisposalScenarioComparisonOutcome;
	recommendedCandidateId: string;
	recommendedLabel: string;
	recommendedRank: number | null;
	baselineAtRiskOrders: number;
	baselineTotalShortageUnits: number;
	bestAtRiskOrdersDelta: number | null;
	bestTotalShortageUnitsDelta: number | null;
	linkedPlanCount: number;
	createdAt: string;
	updatedAt: string;
};

export type DisposalScenarioComparisonPage = {
	items: DisposalScenarioComparisonSummary[];
	page: number;
	pageSize: number;
	totalElements: number;
	totalPages: number;
};

export type ComparisonCandidateDraft = {
	label: string;
	replenishmentQuantity: number | string | null;
	additionalDelayDays: number | string | null;
};

export type ComparisonDraft = {
	title: string;
	scenario: SimulationScenario;
	sku: string;
	supplierId: string;
	candidates: ComparisonCandidateDraft[];
};

export type ComparisonCandidateRequest = {
	label?: string;
	replenishmentQuantity?: number;
	additionalDelayDays?: number;
};

export type CreateDisposalScenarioComparisonRequest = {
	title: string;
	scenario: SimulationScenario;
	sku?: string;
	supplierId?: string;
	candidates: ComparisonCandidateRequest[];
};

export type CreateComparisonDisposalPlanRequest = {
	title: string;
	taskId?: string;
	generateExplanation?: boolean;
};

export type CreateDisposalPlanRequest = {
	title: string;
	taskId?: string;
	sku?: string;
	supplierId?: string;
	simulationId?: string;
	simulation?: SimulationRequest;
	generateExplanation?: boolean;
};

export type UpdateDisposalPlanStatusRequest = {
	status: DisposalPlanStatus;
	selectedRecommendationId?: string;
};

export type RecommendationScope = { sku?: string; supplierId?: string };

export type DisposalPlanQuery = {
	status: 'ALL' | DisposalPlanStatus;
	page: number;
	pageSize: number;
};

export type SimulationDraft = {
	scenario: SimulationScenario;
	sku: string;
	replenishmentQuantity: number | string | null;
	supplierId: string;
	additionalDelayDays: number | string | null;
};

export const DISPOSAL_BASE_ENDPOINT = '/api/supply-chain';

export const DISPOSAL_SCENARIO_OPTIONS: { title: string; value: SimulationScenario }[] = [
	{ title: '库存补货', value: 'INVENTORY_REPLENISHMENT' },
	{ title: '供应商延期', value: 'SUPPLIER_DELAY' },
];

export const DISPOSAL_PLAN_STATUS_OPTIONS: { title: string; value: 'ALL' | DisposalPlanStatus }[] = [
	{ title: '全部方案', value: 'ALL' },
	{ title: '待人工确认', value: 'PENDING_CONFIRMATION' },
	{ title: '已选定', value: 'SELECTED' },
	{ title: '不采用', value: 'REJECTED' },
];

export const DISPOSAL_PLAN_PAGE_SIZE_OPTIONS: { title: string; value: number }[] = [
	{ title: '5 条/页', value: 5 },
	{ title: '10 条/页', value: 10 },
	{ title: '20 条/页', value: 20 },
];

export const DEFAULT_DISPOSAL_PLAN_PAGE_SIZE = 5;

export const MAX_REPLENISHMENT_QUANTITY = 1_000_000;

export const MAX_ADDITIONAL_DELAY_DAYS = 90;

export const DEFAULT_SIMULATION_HISTORY_LIMIT = 6;

export const DEFAULT_EXECUTION_HISTORY_LIMIT = 10;

export const MIN_COMPARISON_CANDIDATES = 2;

export const MAX_COMPARISON_CANDIDATES = 5;

export const DEFAULT_COMPARISON_HISTORY_LIMIT = 5;

export function comparisonOutcomeLabel(outcome: DisposalScenarioComparisonOutcome): string {
	if (outcome === 'RECOMMENDED') {
		return '确定性排名推荐';
	}
	if (outcome === 'TIE_BREAK') {
		return '并列指标 · 最小干预优先';
	}
	return '候选不可比';
}

export function scenarioLabel(scenario: SimulationScenario): string {
	return scenario === 'INVENTORY_REPLENISHMENT' ? '库存补货' : '供应商延期';
}

export function actionTypeLabel(actionType: DisposalActionType): string {
	if (actionType === 'REPLENISH_INVENTORY') {
		return '库存补货';
	}
	if (actionType === 'FOLLOW_UP_SUPPLIER') {
		return '供应商跟进';
	}
	return '人工核实';
}

export function disposalPlanStatusLabel(status: DisposalPlanStatus): string {
	if (status === 'SELECTED') {
		return '已选定';
	}
	if (status === 'REJECTED') {
		return '不采用';
	}
	return '待人工确认';
}

export function priorityLabel(priority: DisposalPriority): string {
	return priority === 'P1' ? 'P1 紧急' : priority === 'P2' ? 'P2 较高' : 'P3 关注';
}

export function sandboxExecutionStatusLabel(status: SandboxExecutionStatus): string {
	if (status === 'SUCCEEDED') {
		return '演练完成';
	}
	if (status === 'FAILED') {
		return '演练失败';
	}
	return '执行中';
}

export function sandboxStepStatusLabel(status: SandboxStepStatus): string {
	return status === 'SUCCEEDED' ? '成功' : '失败';
}

export function sandboxDraftTypeLabel(draftType: SandboxDraftType | null): string {
	if (draftType === 'REPLENISHMENT_TASK_DRAFT') {
		return '补货任务草稿';
	}
	if (draftType === 'SUPPLIER_FOLLOW_UP_DRAFT') {
		return '供应商跟进草稿';
	}
	return '任务草稿';
}

export function createSandboxIdempotencyKey(): string {
	const cryptoApi = globalThis.crypto;
	if (cryptoApi && typeof cryptoApi.randomUUID === 'function') {
		return `sandbox-${cryptoApi.randomUUID()}`;
	}
	return `sandbox-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
}

function parseInteger(value: number | string | null): number | null {
	if (typeof value === 'number') {
		return Number.isInteger(value) ? value : null;
	}
	if (typeof value !== 'string' || value.trim() === '') {
		return null;
	}
	const parsed = Number(value.trim());
	return Number.isInteger(parsed) ? parsed : null;
}

export function validateSimulationDraft(draft: SimulationDraft): string {
	if (draft.scenario === 'INVENTORY_REPLENISHMENT') {
		if (!draft.sku.trim()) {
			return '请选择需要补货的 SKU。';
		}
		const quantity = parseInteger(draft.replenishmentQuantity);
		if (quantity === null) {
			return '补货数量必须是整数。';
		}
		if (quantity < 1 || quantity > MAX_REPLENISHMENT_QUANTITY) {
			return `补货数量必须在 1 到 ${MAX_REPLENISHMENT_QUANTITY.toLocaleString('en-US')} 之间。`;
		}
		return '';
	}
	if (draft.scenario === 'SUPPLIER_DELAY') {
		if (!draft.supplierId.trim()) {
			return '请选择需要评估的供应商。';
		}
		const days = parseInteger(draft.additionalDelayDays);
		if (days === null) {
			return '额外延期天数必须是整数。';
		}
		if (days < 1 || days > MAX_ADDITIONAL_DELAY_DAYS) {
			return `额外延期天数必须在 1 到 ${MAX_ADDITIONAL_DELAY_DAYS} 之间。`;
		}
		return '';
	}
	return '请选择有效的模拟场景。';
}

export function buildSimulationRequest(draft: SimulationDraft): SimulationRequest {
	const error = validateSimulationDraft(draft);
	if (error) {
		throw new Error(error);
	}
	if (draft.scenario === 'INVENTORY_REPLENISHMENT') {
		return {
			scenario: draft.scenario,
			sku: draft.sku.trim(),
			replenishmentQuantity: parseInteger(draft.replenishmentQuantity) as number,
		};
	}
	return {
		scenario: draft.scenario,
		supplierId: draft.supplierId.trim(),
		additionalDelayDays: parseInteger(draft.additionalDelayDays) as number,
	};
}

export function validateComparisonDraft(draft: ComparisonDraft): string {
	const title = draft.title.trim();
	if (!title) {
		return '请填写对比标题。';
	}
	if (title.length > 200) {
		return '对比标题不能超过 200 个字符。';
	}
	if (draft.scenario === 'INVENTORY_REPLENISHMENT') {
		if (!draft.sku.trim()) {
			return '请选择需要对比补货方案的 SKU。';
		}
	} else if (draft.scenario === 'SUPPLIER_DELAY') {
		if (!draft.supplierId.trim()) {
			return '请选择需要对比延期方案的供应商。';
		}
	} else {
		return '请选择有效的对比场景。';
	}
	if (draft.candidates.length < MIN_COMPARISON_CANDIDATES) {
		return `至少需要 ${MIN_COMPARISON_CANDIDATES} 个候选方案。`;
	}
	if (draft.candidates.length > MAX_COMPARISON_CANDIDATES) {
		return `最多只能比较 ${MAX_COMPARISON_CANDIDATES} 个候选方案。`;
	}
	const seen = new Set<number>();
	for (let index = 0; index < draft.candidates.length; index += 1) {
		const candidate = draft.candidates[index];
		if (!candidate) {
			return `第 ${index + 1} 个候选方案缺失。`;
		}
		const isReplenishment = draft.scenario === 'INVENTORY_REPLENISHMENT';
		const value = isReplenishment
			? parseInteger(candidate.replenishmentQuantity)
			: parseInteger(candidate.additionalDelayDays);
		const fieldLabel = isReplenishment ? '补货数量' : '额外延期天数';
		if (value === null) {
			return `第 ${index + 1} 个候选的${fieldLabel}必须是整数。`;
		}
		const maximum = isReplenishment ? MAX_REPLENISHMENT_QUANTITY : MAX_ADDITIONAL_DELAY_DAYS;
		if (value < 1 || value > maximum) {
			return `第 ${index + 1} 个候选的${fieldLabel}必须在 1 到 ${maximum.toLocaleString('en-US')} 之间。`;
		}
		if (seen.has(value)) {
			return `候选参数不能重复：${value}。`;
		}
		seen.add(value);
		if ((candidate.label || '').trim().length > 100) {
			return `第 ${index + 1} 个候选的名称不能超过 100 个字符。`;
		}
	}
	return '';
}

export function buildComparisonRequest(draft: ComparisonDraft): CreateDisposalScenarioComparisonRequest {
	const error = validateComparisonDraft(draft);
	if (error) {
		throw new Error(error);
	}
	const isReplenishment = draft.scenario === 'INVENTORY_REPLENISHMENT';
	const candidates = draft.candidates.map((candidate) => {
		const label = (candidate.label || '').trim();
		const request: ComparisonCandidateRequest = label ? { label } : {};
		if (isReplenishment) {
			request.replenishmentQuantity = parseInteger(candidate.replenishmentQuantity) as number;
		} else {
			request.additionalDelayDays = parseInteger(candidate.additionalDelayDays) as number;
		}
		return request;
	});
	return {
		title: draft.title.trim(),
		scenario: draft.scenario,
		...(isReplenishment ? { sku: draft.sku.trim() } : { supplierId: draft.supplierId.trim() }),
		candidates,
	};
}

export function buildDisposalPlanQuery(query: DisposalPlanQuery): Record<string, string | number> {
	const request: Record<string, string | number> = { page: query.page, pageSize: query.pageSize };
	if (query.status !== 'ALL') {
		request.status = query.status;
	}
	return request;
}

export async function fetchDisposalRecommendations(
	scope: RecommendationScope = {},
): Promise<DisposalRecommendation[]> {
	const query: RecommendationScope = {};
	if (scope.sku?.trim()) {
		query.sku = scope.sku.trim();
	}
	if (scope.supplierId?.trim()) {
		query.supplierId = scope.supplierId.trim();
	}
	return $fetch<DisposalRecommendation[]>(`${DISPOSAL_BASE_ENDPOINT}/disposal-recommendations`, { query });
}

export async function createDisposalSimulation(request: SimulationRequest): Promise<DisposalSimulation> {
	return $fetch<DisposalSimulation>(`${DISPOSAL_BASE_ENDPOINT}/disposal-simulations`, {
		method: 'POST',
		body: request,
	});
}

export async function fetchDisposalSimulations(
	limit = DEFAULT_SIMULATION_HISTORY_LIMIT,
	planId?: string,
): Promise<DisposalSimulationSummary[]> {
	const query: Record<string, string | number> = { limit };
	if (planId?.trim()) {
		query.planId = planId.trim();
	}
	return $fetch<DisposalSimulationSummary[]>(`${DISPOSAL_BASE_ENDPOINT}/disposal-simulations`, { query });
}

export async function fetchDisposalSimulation(simulationId: string): Promise<DisposalSimulation> {
	return $fetch<DisposalSimulation>(
		`${DISPOSAL_BASE_ENDPOINT}/disposal-simulations/${encodeURIComponent(simulationId)}`,
	);
}

export async function fetchDisposalPlans(query: DisposalPlanQuery): Promise<DisposalPlanPage> {
	return $fetch<DisposalPlanPage>(`${DISPOSAL_BASE_ENDPOINT}/disposal-plans`, {
		query: buildDisposalPlanQuery(query),
	});
}

export async function fetchDisposalPlan(planId: string): Promise<DisposalPlan> {
	return $fetch<DisposalPlan>(`${DISPOSAL_BASE_ENDPOINT}/disposal-plans/${encodeURIComponent(planId)}`);
}

export async function fetchDisposalPlanSimulations(
	planId: string,
	limit = DEFAULT_SIMULATION_HISTORY_LIMIT,
): Promise<DisposalSimulationSummary[]> {
	return $fetch<DisposalSimulationSummary[]>(
		`${DISPOSAL_BASE_ENDPOINT}/disposal-plans/${encodeURIComponent(planId)}/simulations`,
		{ query: { limit } },
	);
}

export async function createDisposalPlan(request: CreateDisposalPlanRequest): Promise<DisposalPlan> {
	return $fetch<DisposalPlan>(`${DISPOSAL_BASE_ENDPOINT}/disposal-plans`, {
		method: 'POST',
		body: request,
	});
}

export async function createDisposalScenarioComparison(
	request: CreateDisposalScenarioComparisonRequest,
): Promise<DisposalScenarioComparison> {
	return $fetch<DisposalScenarioComparison>(`${DISPOSAL_BASE_ENDPOINT}/disposal-scenario-comparisons`, {
		method: 'POST',
		body: request,
	});
}

export async function fetchDisposalScenarioComparisons(
	limit = DEFAULT_COMPARISON_HISTORY_LIMIT,
	page = 1,
): Promise<DisposalScenarioComparisonPage> {
	return $fetch<DisposalScenarioComparisonPage>(`${DISPOSAL_BASE_ENDPOINT}/disposal-scenario-comparisons`, {
		query: { page, pageSize: limit },
	});
}

export async function fetchDisposalScenarioComparison(
	comparisonId: string,
): Promise<DisposalScenarioComparison> {
	return $fetch<DisposalScenarioComparison>(
		`${DISPOSAL_BASE_ENDPOINT}/disposal-scenario-comparisons/${encodeURIComponent(comparisonId)}`,
	);
}

export async function saveComparisonCandidateAsDisposalPlan(
	comparisonId: string,
	candidateId: string,
	request: CreateComparisonDisposalPlanRequest,
): Promise<DisposalPlan> {
	return $fetch<DisposalPlan>(
		`${DISPOSAL_BASE_ENDPOINT}/disposal-scenario-comparisons/${encodeURIComponent(comparisonId)}/candidates/${encodeURIComponent(candidateId)}/disposal-plans`,
		{ method: 'POST', body: request },
	);
}

export async function linkComparisonCandidateToDisposalPlan(
	comparisonId: string,
	candidateId: string,
	planId: string,
): Promise<DisposalScenarioComparison> {
	return $fetch<DisposalScenarioComparison>(
		`${DISPOSAL_BASE_ENDPOINT}/disposal-scenario-comparisons/${encodeURIComponent(comparisonId)}/candidates/${encodeURIComponent(candidateId)}/plan`,
		{ method: 'PATCH', body: { planId } },
	);
}

export async function updateDisposalPlanStatus(
	planId: string,
	request: UpdateDisposalPlanStatusRequest,
): Promise<DisposalPlan> {
	return $fetch<DisposalPlan>(
		`${DISPOSAL_BASE_ENDPOINT}/disposal-plans/${encodeURIComponent(planId)}/status`,
		{ method: 'PATCH', body: request },
	);
}

export async function fetchSandboxExecutionEligibility(
	planId: string,
): Promise<SandboxExecutionEligibility> {
	return $fetch<SandboxExecutionEligibility>(
		`${DISPOSAL_BASE_ENDPOINT}/disposal-plans/${encodeURIComponent(planId)}/execution-eligibility`,
	);
}

export async function startSandboxExecution(
	planId: string,
	idempotencyKey: string,
): Promise<SandboxExecution> {
	return $fetch<SandboxExecution>(
		`${DISPOSAL_BASE_ENDPOINT}/disposal-plans/${encodeURIComponent(planId)}/executions`,
		{ method: 'POST', body: { idempotencyKey } },
	);
}

export async function fetchSandboxExecutions(
	planId: string,
	limit = DEFAULT_EXECUTION_HISTORY_LIMIT,
): Promise<SandboxExecutionPage> {
	return $fetch<SandboxExecutionPage>(
		`${DISPOSAL_BASE_ENDPOINT}/disposal-plans/${encodeURIComponent(planId)}/executions`,
		{ query: { page: 1, pageSize: limit } },
	);
}

export async function fetchSandboxExecution(executionId: string): Promise<SandboxExecution> {
	return $fetch<SandboxExecution>(
		`${DISPOSAL_BASE_ENDPOINT}/sandbox-executions/${encodeURIComponent(executionId)}`,
	);
}

function statusCodeOf(cause: unknown): number {
	if (typeof cause !== 'object' || cause === null) {
		return 0;
	}
	const record = cause as Record<string, unknown>;
	const response =
		typeof record.response === 'object' && record.response !== null
			? (record.response as Record<string, unknown>)
			: null;
	for (const value of [record.statusCode, record.status, response?.status]) {
		if (typeof value === 'number') {
			return value;
		}
	}
	return 0;
}

function errorDetailOf(cause: unknown): string {
	if (typeof cause !== 'object' || cause === null) {
		return '';
	}
	const record = cause as Record<string, unknown>;
	const data = typeof record.data === 'object' && record.data !== null ? (record.data as Record<string, unknown>) : null;
	for (const value of [data?.message, data?.detail, record.message]) {
		if (typeof value === 'string' && value.trim() && !/no message available/i.test(value)) {
			return value.trim();
		}
	}
	return '';
}

export function disposalErrorMessage(cause: unknown, fallback: string): string {
	const status = statusCodeOf(cause);
	const detail = errorDetailOf(cause);
	if (status === 400) {
		return detail ? `参数校验失败：${detail}` : '参数校验失败，请检查输入范围和关联数据。';
	}
	if (status === 404) {
		return detail ? `目标不存在：${detail}` : '目标不存在或已被清理，请刷新后重试。';
	}
	if (status === 409) {
		return detail ? `当前状态不允许此操作：${detail}` : '当前状态不允许此操作，请刷新后重试。';
	}
	if (status >= 500) {
		return detail ? `后端处理失败：${detail}` : '后端处理失败，请稍后重试。';
	}
	if (cause instanceof Error && cause.message) {
		return cause.message;
	}
	return fallback;
}
