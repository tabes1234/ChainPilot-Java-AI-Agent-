import { api } from './client';

export type NarrativeSource = 'MODEL_VERIFIED' | 'MODEL_LEGACY_UNVERIFIED' | 'DETERMINISTIC_FALLBACK' | 'UNKNOWN' | string;

export type ObservabilitySummary = {
	generatedAt?: string;
	scope: string;
	windowSize: number;
	totalTasks: number;
	completedTasks: number;
	failedTasks: number;
	runningTasks: number;
	cancelledTasks: number;
	degradedTasks: number;
	toolCalls: number;
	toolFailures: number;
	modelCompletions: number;
	modelFailures: number;
	fallbackEvents: number;
	modelVerifiedTasks: number;
	legacyUnverifiedTasks: number;
	deterministicFallbackTasks: number;
	tasksWithDuration: number;
	averageDurationMs?: number | null;
	failureTypes: Record<string, number>;
	narrativeSources: Record<string, number>;
	limitations?: string[];
};

export type ObservabilityRunSummary = {
	taskId: string;
	question: string;
	mode: string;
	status: string;
	currentStep?: string;
	updatedAt?: string;
	narrativeSource: NarrativeSource;
	modelAssisted: boolean;
	fallbackUsed: boolean;
	degraded: boolean;
	eventCount: number;
	toolCallCount: number;
	toolFailureCount: number;
	modelFailureCount: number;
	durationMs?: number | null;
	failureType?: string | null;
	reportAvailable: boolean;
	decisionAvailable: boolean;
};

export type ObservabilityRunPage = {
	items: ObservabilityRunSummary[];
	page: number;
	pageSize: number;
	totalElements: number;
	totalPages: number;
	windowSize: number;
	scope: string;
};

export type EvaluationTaskReference = {
	runId: string;
	mode?: string;
	runStatus?: string;
	caseId?: string;
	caseName?: string;
	caseStatus?: string;
	failureType?: string | null;
	startedAt?: string;
};

export type ObservabilityRunDetail = {
	run: ObservabilityRunSummary;
	narrative: {
		source: NarrativeSource;
		modelAssisted: boolean;
		fallbackUsed: boolean;
		riskAuthority: string;
		modelCanChangeRisk: boolean;
	};
	report: {
		atRiskOrders: number;
		highRiskOrders: number;
		dataGapCount: number;
		source: NarrativeSource;
	};
	timeline: TimelineItem[];
	tools: ToolCall[];
	modelCalls: ModelCall[];
	fallbacks: FallbackItem[];
	diagnostics: DiagnosticItem[];
	links: {
		reportAvailable: boolean;
		decisionAvailable: boolean;
		proposalIds: string[];
		planIds: string[];
		evaluationRuns: EvaluationTaskReference[];
	};
	diagnosticMode?: string;
	scope: string;
};

export type TimelineItem = {
	eventId: string;
	sequence: number;
	type: string;
	stage: string;
	status: string;
	occurredAt?: string;
	durationMs?: number | null;
	summary?: string;
	errorType?: string | null;
	source: 'WORKFLOW' | 'TOOL' | 'MODEL' | 'FALLBACK' | 'AGENT' | string;
	executed: boolean;
};

export type ToolCall = {
	name: string;
	initiatedBy: string;
	status: string;
	durationMs?: number | null;
	summary?: string;
	errorType?: string | null;
	eventIds: string[];
	inputRecorded: boolean;
};

export type ModelCall = {
	stage: string;
	status: string;
	durationMs?: number | null;
	summary?: string;
	errorType?: string | null;
	modelIdentity?: string | null;
	promptTokens?: number | null;
	completionTokens?: number | null;
	usageAvailable: boolean;
	eventIds: string[];
};

export type FallbackItem = {
	stage: string;
	reason: string;
	occurredAt?: string;
	eventId: string;
};

export type DiagnosticItem = {
	code: string;
	severity: 'INFO' | 'WARNING' | 'ERROR' | string;
	title: string;
	detail: string;
	recommendation: string;
	eventIds: string[];
	deterministicRule: boolean;
};

export async function fetchObservabilitySummary(): Promise<ObservabilitySummary> {
	return api.get<ObservabilitySummary>('/api/supply-chain/observability/summary');
}

export async function fetchObservabilityRuns(params: {
	page?: number;
	pageSize?: number;
	status?: string;
	mode?: string;
	narrativeSource?: string;
	fallback?: boolean;
	failureType?: string;
	query?: string;
}): Promise<ObservabilityRunPage> {
	const search = new URLSearchParams();
	if (params.page) search.set('page', String(params.page));
	if (params.pageSize) search.set('pageSize', String(params.pageSize));
	if (params.status) search.set('status', params.status);
	if (params.mode) search.set('mode', params.mode);
	if (params.narrativeSource) search.set('narrativeSource', params.narrativeSource);
	if (params.fallback !== undefined) search.set('fallback', String(params.fallback));
	if (params.failureType) search.set('failureType', params.failureType);
	if (params.query) search.set('query', params.query);
	const qs = search.toString();
	return api.get<ObservabilityRunPage>(`/api/supply-chain/observability/runs${qs ? `?${qs}` : ''}`);
}

export async function fetchObservabilityDetail(taskId: string): Promise<ObservabilityRunDetail> {
	return api.get<ObservabilityRunDetail>(`/api/supply-chain/observability/runs/${encodeURIComponent(taskId)}`);
}

export const NARRATIVE_SOURCE_LABELS: Record<string, string> = {
	MODEL_VERIFIED: '模型已验证',
	MODEL_LEGACY_UNVERIFIED: '历史模型未验证',
	DETERMINISTIC_FALLBACK: '确定性回退',
	UNKNOWN: '来源未记录',
};

export function narrativeSourceLabel(source?: string): string {
	return NARRATIVE_SOURCE_LABELS[source ?? 'UNKNOWN'] ?? source ?? '来源未记录';
}
