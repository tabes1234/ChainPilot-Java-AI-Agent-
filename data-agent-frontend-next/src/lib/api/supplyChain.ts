/**
 * Supply chain API services. Mirrors the four supply-chain controllers.
 * All success responses are unwrapped JSON; errors use ApiResponse {success,message}.
 */

import { api, errorMessage } from './client';

export type RiskLevel = 'LOW' | 'MEDIUM' | 'HIGH';
export type OrderStatus = 'CREATED' | 'CONFIRMED' | 'IN_TRANSIT' | 'DELAYED' | 'DELIVERED';
export type AnalysisMode = 'ANALYSIS' | 'DECISION';
export type TaskState = 'ACCEPTED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
export type SimulationScenario = 'INVENTORY_REPLENISHMENT' | 'SUPPLIER_DELAY';
export type DisposalPriority = 'P1' | 'P2' | 'P3';
export type DisposalActionType = 'REPLENISH_INVENTORY' | 'FOLLOW_UP_SUPPLIER' | 'MANUAL_CONFIRMATION';
export type PlanStatus = 'PENDING_CONFIRMATION' | 'SELECTED' | 'REJECTED';
export type ProposalStatus = 'PENDING' | 'EXECUTING' | 'CONFIRMED' | 'CANCELLED' | 'EXPIRED' | 'FAILED';
export type ProposalAction =
	| 'SAVE_CANDIDATE_AS_PLAN'
	| 'LINK_CANDIDATE_TO_PLAN'
	| 'SELECT_PLAN'
	| 'START_SANDBOX_EXECUTION';

export type SupplyChainOrder = {
	orderId: string;
	sku: string;
	quantity: number;
	supplierId: string;
	requestedDeliveryDate: string;
	estimatedDeliveryDate: string;
	status: OrderStatus;
};

export type RiskEvidence = {
	code: string;
	label: string;
	observedValue: string;
	expectedValue: string;
	rule: string;
	scoreContribution: number;
	source: string;
	available: boolean;
};

export type OrderRisk = {
	order: SupplyChainOrder;
	riskLevel: RiskLevel;
	riskScore: number;
	inventoryShortage: number;
	riskFactors: string[];
	recommendedActions: string[];
	evidence?: RiskEvidence[];
	dataGaps?: string[];
};

export type InventoryItem = {
	sku: string;
	productName?: string;
	onHandQuantity: number;
	reservedQuantity: number;
	safetyStock: number;
	availableToPromise: number;
};

export type Supplier = {
	supplierId: string;
	name: string;
	riskLevel: RiskLevel;
	onTimeDeliveryRate: number;
	defectRate: number;
	latestIncident?: string;
};

export type Citation = { sourceId: string; title: string; excerpt: string };

export type TaskStatus = {
	taskId: string;
	status: TaskState;
	currentStep?: string;
	validationAttempts?: number;
	error?: string | null;
	updatedAt?: string;
};

export type TaskSummary = TaskStatus & {
	question: string;
	reportAvailable?: boolean;
};

export type TaskEvent = {
	eventId: string;
	taskId: string;
	sequence: number;
	type: string;
	stage?: string;
	status?: string;
	occurredAt?: string;
	durationMs?: number | null;
	summary?: string;
	errorType?: string;
};

export type RiskNarrativeReason = {
	orderId: string;
	summary: string;
	evidenceIds: string[];
};

export type RiskNarrative = {
	overview: string;
	reasons: RiskNarrativeReason[];
	businessImpacts: string[];
	recommendations: string[];
	uncertainties: string[];
};

export type RiskAnalysisReport = {
	taskId: string;
	status: string;
	summary: string;
	executionPlan?: string[];
	plannerAssisted?: boolean;
	atRiskOrders: OrderRisk[];
	recommendations: string[];
	toolCalls?: string[];
	citations?: Citation[];
	modelAssisted: boolean;
	validationAttempts: number;
	createdAt?: string;
	modelNarrative?: RiskNarrative | null;
	narrativeSource?: 'MODEL_VERIFIED' | 'MODEL_LEGACY_UNVERIFIED' | 'DETERMINISTIC_FALLBACK' | null;
};

export type SimulationMetrics = {
	totalOrders: number;
	atRiskOrders: number;
	highRiskOrders: number;
	ordersWithShortage: number;
	totalShortageUnits: number;
	totalRiskScore: number;
	targetSkuAvailableToPromise?: number | null;
};

export type SimulationDelta = {
	affectedOrderCount: number;
	atRiskOrders: number;
	highRiskOrders: number;
	ordersWithShortage: number;
	totalShortageUnits: number;
	totalRiskScore: number;
	targetSkuAvailableToPromise?: number | null;
};

export type OrderRiskImpact = {
	orderId: string;
	sku: string;
	supplierId?: string;
	baselineEstimatedDeliveryDate?: string;
	simulatedEstimatedDeliveryDate?: string;
	baselineRiskLevel: RiskLevel;
	simulatedRiskLevel: RiskLevel;
	baselineRiskScore: number;
	simulatedRiskScore: number;
	baselineInventoryShortage: number;
	simulatedInventoryShortage: number;
	changes?: string[];
	evidenceChanges?: string[];
	remainingDataGaps?: string[];
};

export type Recommendation = {
	recommendationId: string;
	actionType: DisposalActionType;
	priority: DisposalPriority;
	priorityScore: number;
	priorityBasis?: string;
	riskLevel: RiskLevel;
	sku?: string;
	supplierId?: string;
	affectedOrderIds?: string[];
	title: string;
	action: string;
	rationale?: string;
	businessBasis?: string[];
	expectedImprovement?: string;
	prerequisites?: string[];
	sideEffects?: string[];
	sources?: string[];
	assumptions?: string[];
	status?: string;
	generatedAt?: string;
};

export type Simulation = {
	simulationId: string;
	scenario: SimulationScenario;
	sku?: string;
	supplierId?: string;
	replenishmentQuantity?: number;
	additionalDelayDays?: number;
	asOfDate?: string;
	baselineSource?: string;
	baseline?: SimulationMetrics;
	simulated?: SimulationMetrics;
	delta?: SimulationDelta;
	affectedOrders?: OrderRiskImpact[];
	assumptions?: string[];
	limitations?: string[];
	evidence?: string[];
	realDataMutated: boolean;
	disclaimer?: string;
	createdAt?: string;
	ruleVersion?: string;
	simulationStatus?: string;
	dataGaps?: string[];
};

export type SimulationBaselineView = {
	baselineSource: string;
	asOfDate?: string;
	capturedAt?: string;
	metrics: SimulationMetrics;
	assessments: OrderRisk[];
	orders: SupplyChainOrder[];
	inventory: InventoryItem[];
	suppliers: Supplier[];
	dataGaps: string[];
	ruleVersion: string;
	simulationStatus: string;
	disclaimer?: string;
};

export type SimulationSummary = {
	simulationId: string;
	scenario: SimulationScenario;
	sku?: string;
	supplierId?: string;
	replenishmentQuantity?: number;
	additionalDelayDays?: number;
	atRiskOrdersDelta?: number | null;
	totalShortageUnitsDelta?: number | null;
	createdAt?: string;
};

export type PlanRecommendation = Recommendation;

export type DisposalPlan = {
	planId: string;
	title: string;
	taskId?: string;
	scopeSku?: string;
	scopeSupplierId?: string;
	status: PlanStatus;
	selectedRecommendationId?: string | null;
	recommendations: Recommendation[];
	simulation?: Simulation | null;
	evidence?: Citation[];
	explanation?: string | null;
	explanationSource?: string;
	explanationModelAssisted?: boolean;
	explanationToolCalls?: string[];
	createdAt?: string;
	updatedAt?: string;
};

export type DisposalPlanSummary = {
	planId: string;
	title: string;
	status: PlanStatus;
	taskId?: string;
	scopeSku?: string;
	scopeSupplierId?: string;
	recommendationCount: number;
	simulationScenario?: SimulationScenario;
	atRiskOrdersDelta?: number | null;
	createdAt?: string;
	updatedAt?: string;
};

export type ScenarioCandidate = {
	candidateId: string;
	label?: string;
	position: number;
	scenario: SimulationScenario;
	sku?: string;
	supplierId?: string;
	replenishmentQuantity?: number;
	additionalDelayDays?: number;
	simulation?: Simulation;
	linkedPlanId?: string | null;
	createdAt?: string;
};

export type ScenarioRanking = {
	candidateId: string;
	label?: string;
	parameterSummary?: string;
	rank?: number | null;
	tied?: boolean;
	recommended?: boolean;
	comparable?: boolean;
	rankingBasis?: string;
	keyDeltas?: string[];
	reasons?: string[];
	tradeoffs?: string[];
};

export type ScenarioRecommendation = {
	outcome?: string;
	recommendedCandidateId?: string;
	recommendedLabel?: string;
	recommendedRank?: number | null;
	summary?: string;
	keyDeltas?: string[];
	reasons?: string[];
	tradeoffs?: string[];
	assumptions?: string[];
	unknowns?: string[];
	manualConfirmation?: string[];
	evidence?: string[];
	disclaimer?: string;
};

export type ScenarioComparison = {
	comparisonId: string;
	title: string;
	scenario: SimulationScenario;
	sku?: string;
	supplierId?: string;
	asOfDate?: string;
	baselineSource?: string;
	baseline?: SimulationMetrics;
	bestAtRiskOrdersDelta?: number | null;
	bestTotalShortageUnitsDelta?: number | null;
	candidates: ScenarioCandidate[];
	rankings: ScenarioRanking[];
	recommendation?: ScenarioRecommendation;
	rankingRules?: string[];
	assumptions?: string[];
	limitations?: string[];
	evidence?: string[];
	realDataMutated: boolean;
	disclaimer?: string;
	createdAt?: string;
	updatedAt?: string;
};

export type ScenarioComparisonSummary = {
	comparisonId: string;
	title: string;
	scenario: SimulationScenario;
	sku?: string;
	supplierId?: string;
	candidateCount: number;
	outcome?: string;
	recommendedCandidateId?: string;
	recommendedLabel?: string;
	recommendedRank?: number | null;
	linkedPlanCount?: number;
	createdAt?: string;
};

export type SandboxStep = {
	sequence: number;
	step: string;
	name?: string;
	status: 'SUCCEEDED' | 'FAILED';
	input?: string;
	validation?: string;
	result?: string;
	failureReason?: string;
	startedAt?: string;
	completedAt?: string;
};

export type SandboxTaskDraft = {
	taskDraftId: string;
	draftType?: string;
	title?: string;
	sku?: string;
	supplierId?: string;
	replenishmentQuantity?: number;
	currentOnHandQuantity?: number;
	currentAvailableToPromise?: number;
	targetOnHandQuantity?: number;
	targetAvailableToPromise?: number;
	linkedOrderIds?: string[];
	recommendedActions?: string[];
	pendingConfirmations?: string[];
	sourceSimulationId?: string;
	evidenceSummary?: string;
	createdAt?: string;
};

export type SandboxExecution = {
	executionId: string;
	disposalPlanId: string;
	recommendationId?: string;
	actionType?: DisposalActionType;
	mode?: 'SANDBOX';
	status: 'RUNNING' | 'SUCCEEDED' | 'FAILED';
	sourceSimulationId?: string;
	idempotencyKey?: string;
	steps: SandboxStep[];
	taskDraft?: SandboxTaskDraft;
	evidence?: string[];
	finalResult?: string;
	unresolvedItems?: string[];
	failureReason?: string;
	realDataMutated: boolean;
	disclaimer?: string;
	createdAt?: string;
	startedAt?: string;
	completedAt?: string;
	updatedAt?: string;
};

export type SandboxExecutionSummary = {
	executionId: string;
	disposalPlanId: string;
	actionType?: DisposalActionType;
	mode?: string;
	status: 'RUNNING' | 'SUCCEEDED' | 'FAILED';
	sourceSimulationId?: string;
	idempotencyKey?: string;
	taskDraftId?: string;
	draftType?: string;
	title?: string;
	stepCount: number;
	failedStepCount: number;
	createdAt?: string;
	completedAt?: string;
};

export type ExecutionEligibility = {
	disposalPlanId: string;
	planStatus: PlanStatus;
	eligible: boolean;
	recommendationId?: string;
	actionType?: DisposalActionType;
	sourceSimulationId?: string;
	checks?: string[];
	reason?: string;
	checkedAt?: string;
};

export type Proposal = {
	proposalId: string;
	taskId: string;
	actionType: ProposalAction;
	title: string;
	targetId?: string;
	parameters?: Record<string, unknown>;
	evidence?: string[];
	contentHash: string;
	status: ProposalStatus;
	createdAt?: string;
	expiresAt?: string;
	confirmedAt?: string;
	result?: Record<string, unknown>;
	errorMessage?: string;
};

export type DecisionResult = {
	taskId: string;
	question: string;
	intent?: string;
	status: string;
	summary?: string;
	narrativeSource?: string;
	modelAssisted?: boolean;
	fallbackUsed?: boolean;
	clarificationRequired?: boolean;
	clarifications?: string[];
	toolCalls?: string[];
	executionPlan?: string[];
	plannerAssisted?: boolean;
	atRiskOrders?: OrderRisk[];
	recommendationNotes?: string[];
	recommendations?: Recommendation[];
	simulation?: Simulation | null;
	comparison?: ScenarioComparison | null;
	plan?: DisposalPlan | null;
	execution?: SandboxExecution | null;
	sandboxEligibility?: ExecutionEligibility | null;
	proposals?: Proposal[];
	citations?: Citation[];
	evidenceNotes?: string[];
	realDataMutated: boolean;
	createdAt?: string;
};

export type PageData<T> = { items: T[]; page: number; pageSize: number; total: number; totalPages: number };

// ── Baseline data ──────────────────────────────────────────────────────────

export async function fetchOrders(params?: { status?: string; riskLevel?: string }): Promise<OrderRisk[]> {
	const search = new URLSearchParams();
	if (params?.status) search.set('status', params.status);
	if (params?.riskLevel) search.set('riskLevel', params.riskLevel);
	const qs = search.toString();
	return api.get<OrderRisk[]>(`/api/supply-chain/orders${qs ? `?${qs}` : ''}`);
}

export async function fetchInventory(sku?: string): Promise<InventoryItem[] | InventoryItem> {
	const qs = sku ? `?sku=${encodeURIComponent(sku)}` : '';
	return api.get<InventoryItem[] | InventoryItem>(`/api/supply-chain/inventory${qs}`);
}

/** The inventory endpoint returns a single object when sku is provided. */
export async function fetchInventoryItem(sku: string): Promise<InventoryItem> {
	return api.get<InventoryItem>(`/api/supply-chain/inventory?sku=${encodeURIComponent(sku)}`);
}

export async function fetchSuppliers(): Promise<Supplier[]> {
	return api.get<Supplier[]>('/api/supply-chain/suppliers');
}

export async function fetchSupplierRisk(supplierId: string): Promise<unknown> {
	return api.get<unknown>(`/api/supply-chain/suppliers/${encodeURIComponent(supplierId)}/risk`);
}

// ── Analysis tasks ─────────────────────────────────────────────────────────

export async function submitTask(question: string, mode: AnalysisMode = 'ANALYSIS'): Promise<TaskStatus> {
	return api.post<TaskStatus>(
		'/api/supply-chain/analysis/tasks',
		mode === 'DECISION' ? { question, mode } : { question },
		{ timeoutMs: 60_000 },
	);
}

export async function fetchTasks(params: {
	page?: number;
	pageSize?: number;
	status?: string;
	mode?: AnalysisMode;
}): Promise<PageData<TaskSummary>> {
	const search = new URLSearchParams();
	if (params.page) search.set('page', String(params.page));
	if (params.pageSize) search.set('pageSize', String(params.pageSize));
	if (params.status) search.set('status', params.status);
	if (params.mode) search.set('mode', params.mode);
	const response = await api.raw(`/api/supply-chain/analysis/tasks?${search.toString()}`);
	if (!response.ok) throw new Error(`加载任务列表失败（HTTP ${response.status}）`);
	const items = (await response.json()) as TaskSummary[];
	const total = Number(response.headers.get('x-total-count') ?? items.length);
	const page = Number(response.headers.get('x-page') ?? params.page ?? 1);
	const pageSize = Number(response.headers.get('x-page-size') ?? params.pageSize ?? 8);
	const totalPages = Number(response.headers.get('x-total-pages') ?? Math.max(1, Math.ceil(total / pageSize)));
	return { items, page, pageSize, total, totalPages };
}

export async function fetchTask(taskId: string, options?: { signal?: AbortSignal }): Promise<TaskStatus> {
	return api.get<TaskStatus>(`/api/supply-chain/analysis/tasks/${encodeURIComponent(taskId)}`, options);
}

export async function fetchTaskEvents(taskId: string, options?: { signal?: AbortSignal }): Promise<TaskEvent[]> {
	return api.get<TaskEvent[]>(`/api/supply-chain/analysis/tasks/${encodeURIComponent(taskId)}/events`, options);
}

export async function cancelTask(taskId: string): Promise<TaskStatus> {
	return api.post<TaskStatus>(`/api/supply-chain/analysis/tasks/${encodeURIComponent(taskId)}/cancel`);
}

export async function fetchReport(taskId: string): Promise<RiskAnalysisReport> {
	return api.get<RiskAnalysisReport>(`/api/supply-chain/analysis/tasks/${encodeURIComponent(taskId)}/report`);
}

export async function fetchDecision(taskId: string): Promise<DecisionResult> {
	return api.get<DecisionResult>(`/api/supply-chain/analysis/tasks/${encodeURIComponent(taskId)}/decision`);
}

// ── Proposals ──────────────────────────────────────────────────────────────

export async function fetchProposals(taskId: string): Promise<Proposal[]> {
	return api.get<Proposal[]>(`/api/supply-chain/action-proposals?taskId=${encodeURIComponent(taskId)}`);
}

export async function fetchProposal(proposalId: string): Promise<Proposal> {
	return api.get<Proposal>(`/api/supply-chain/action-proposals/${encodeURIComponent(proposalId)}`);
}

export async function confirmProposal(proposalId: string, contentHash: string): Promise<Proposal> {
	return api.post<Proposal>(`/api/supply-chain/action-proposals/${encodeURIComponent(proposalId)}/confirm`, { contentHash });
}

export async function cancelProposal(proposalId: string, contentHash: string): Promise<Proposal> {
	return api.post<Proposal>(`/api/supply-chain/action-proposals/${encodeURIComponent(proposalId)}/cancel`, { contentHash });
}

// ── Disposal ───────────────────────────────────────────────────────────────

export async function fetchRecommendations(params?: { sku?: string; supplierId?: string }): Promise<Recommendation[]> {
	const search = new URLSearchParams();
	if (params?.sku) search.set('sku', params.sku);
	if (params?.supplierId) search.set('supplierId', params.supplierId);
	const qs = search.toString();
	return api.get<Recommendation[]>(`/api/supply-chain/disposal-recommendations${qs ? `?${qs}` : ''}`);
}

export type SimulationRequest =
	| { scenario: 'INVENTORY_REPLENISHMENT'; sku: string; replenishmentQuantity: number }
	| { scenario: 'SUPPLIER_DELAY'; supplierId: string; additionalDelayDays: number };

export async function createSimulation(input: SimulationRequest): Promise<Simulation> {
	return api.post<Simulation>('/api/supply-chain/disposal-simulations', input, { timeoutMs: 60_000 });
}

export async function fetchSimulationBaseline(): Promise<SimulationBaselineView> {
	return api.get<SimulationBaselineView>('/api/supply-chain/disposal-simulations/baseline');
}

export async function fetchSimulations(params?: { limit?: number; planId?: string }): Promise<SimulationSummary[]> {
	const search = new URLSearchParams();
	if (params?.limit) search.set('limit', String(params.limit));
	if (params?.planId) search.set('planId', params.planId);
	const qs = search.toString();
	return api.get<SimulationSummary[]>(`/api/supply-chain/disposal-simulations${qs ? `?${qs}` : ''}`);
}

export async function fetchSimulation(id: string): Promise<Simulation> {
	return api.get<Simulation>(`/api/supply-chain/disposal-simulations/${encodeURIComponent(id)}`);
}

export type CreatePlanRequest = {
	title: string;
	taskId?: string;
	sku?: string;
	supplierId?: string;
	simulationId?: string;
	simulation?: SimulationRequest;
	generateExplanation?: boolean;
};

export async function createPlan(input: CreatePlanRequest): Promise<DisposalPlan> {
	return api.post<DisposalPlan>('/api/supply-chain/disposal-plans', input, { timeoutMs: 120_000 });
}

export async function fetchPlans(params?: { page?: number; pageSize?: number; status?: PlanStatus }): Promise<PageData<DisposalPlanSummary>> {
	const search = new URLSearchParams();
	if (params?.page) search.set('page', String(params.page));
	if (params?.pageSize) search.set('pageSize', String(params.pageSize));
	if (params?.status) search.set('status', params.status);
	const qs = search.toString();
	const data = await api.get<{ items: DisposalPlanSummary[]; page: number; pageSize: number; totalElements: number; totalPages: number }>(
		`/api/supply-chain/disposal-plans${qs ? `?${qs}` : ''}`,
	);
	return { items: data.items, page: data.page, pageSize: data.pageSize, total: data.totalElements, totalPages: data.totalPages };
}

export async function fetchPlan(planId: string): Promise<DisposalPlan> {
	return api.get<DisposalPlan>(`/api/supply-chain/disposal-plans/${encodeURIComponent(planId)}`);
}

export async function fetchPlanSimulations(planId: string, limit = 20): Promise<SimulationSummary[]> {
	return api.get<SimulationSummary[]>(`/api/supply-chain/disposal-plans/${encodeURIComponent(planId)}/simulations?limit=${limit}`);
}

export async function updatePlanStatus(planId: string, status: PlanStatus, selectedRecommendationId?: string): Promise<DisposalPlan> {
	return api.patch<DisposalPlan>(`/api/supply-chain/disposal-plans/${encodeURIComponent(planId)}/status`, {
		status,
		selectedRecommendationId,
	});
}

export type CreateComparisonRequest = {
	title: string;
	scenario: SimulationScenario;
	sku?: string;
	supplierId?: string;
	candidates: { label?: string; replenishmentQuantity?: number; additionalDelayDays?: number }[];
};

export async function createComparison(input: CreateComparisonRequest): Promise<ScenarioComparison> {
	return api.post<ScenarioComparison>('/api/supply-chain/disposal-scenario-comparisons', input, { timeoutMs: 120_000 });
}

export async function fetchComparisons(params?: { page?: number; pageSize?: number }): Promise<PageData<ScenarioComparisonSummary>> {
	const search = new URLSearchParams();
	if (params?.page) search.set('page', String(params.page));
	if (params?.pageSize) search.set('pageSize', String(params.pageSize));
	const qs = search.toString();
	const data = await api.get<{ items: ScenarioComparisonSummary[]; page: number; pageSize: number; totalElements: number; totalPages: number }>(
		`/api/supply-chain/disposal-scenario-comparisons${qs ? `?${qs}` : ''}`,
	);
	return { items: data.items, page: data.page, pageSize: data.pageSize, total: data.totalElements, totalPages: data.totalPages };
}

export async function fetchComparison(id: string): Promise<ScenarioComparison> {
	return api.get<ScenarioComparison>(`/api/supply-chain/disposal-scenario-comparisons/${encodeURIComponent(id)}`);
}

export async function createPlanFromCandidate(
	comparisonId: string,
	candidateId: string,
	input: { title: string; taskId?: string; generateExplanation?: boolean },
): Promise<DisposalPlan> {
	return api.post<DisposalPlan>(
		`/api/supply-chain/disposal-scenario-comparisons/${encodeURIComponent(comparisonId)}/candidates/${encodeURIComponent(candidateId)}/disposal-plans`,
		input,
		{ timeoutMs: 120_000 },
	);
}

export async function linkPlanToCandidate(comparisonId: string, candidateId: string, planId: string): Promise<ScenarioComparison> {
	return api.patch<ScenarioComparison>(
		`/api/supply-chain/disposal-scenario-comparisons/${encodeURIComponent(comparisonId)}/candidates/${encodeURIComponent(candidateId)}/plan`,
		{ planId },
	);
}

export async function fetchEligibility(planId: string): Promise<ExecutionEligibility> {
	return api.get<ExecutionEligibility>(`/api/supply-chain/disposal-plans/${encodeURIComponent(planId)}/execution-eligibility`);
}

/** Returns the execution plus whether the backend replayed an idempotent request. */
export async function createExecution(planId: string, idempotencyKey: string): Promise<{ execution: SandboxExecution; replayed: boolean }> {
	const response = await api.raw(`/api/supply-chain/disposal-plans/${encodeURIComponent(planId)}/executions`, {
		method: 'POST',
		headers: { 'Content-Type': 'application/json' },
		body: JSON.stringify({ idempotencyKey }),
	});
	if (!response.ok) {
		let message = `启动执行失败（HTTP ${response.status}）`;
		try {
			const body = await response.json();
			message = body?.message || message;
		} catch {
			/* ignore */
		}
		throw new Error(message);
	}
	return {
		execution: (await response.json()) as SandboxExecution,
		replayed: response.headers.get('x-idempotent-replay') === 'true',
	};
}

export async function fetchExecutions(planId: string, params?: { page?: number; pageSize?: number }): Promise<PageData<SandboxExecutionSummary>> {
	const search = new URLSearchParams();
	if (params?.page) search.set('page', String(params.page));
	if (params?.pageSize) search.set('pageSize', String(params.pageSize));
	const qs = search.toString();
	const data = await api.get<{
		items: SandboxExecutionSummary[];
		page: number;
		pageSize: number;
		totalElements: number;
		totalPages: number;
	}>(`/api/supply-chain/disposal-plans/${encodeURIComponent(planId)}/executions${qs ? `?${qs}` : ''}`);
	return { items: data.items, page: data.page, pageSize: data.pageSize, total: data.totalElements, totalPages: data.totalPages };
}

export async function fetchExecution(id: string): Promise<SandboxExecution> {
	return api.get<SandboxExecution>(`/api/supply-chain/sandbox-executions/${encodeURIComponent(id)}`);
}

export async function fetchExecutionSteps(id: string): Promise<SandboxStep[]> {
	return api.get<SandboxStep[]>(`/api/supply-chain/sandbox-executions/${encodeURIComponent(id)}/steps`);
}

export function supplyChainErrorMessage(error: unknown, fallback = '请求失败'): string {
	return errorMessage(error, fallback);
}
