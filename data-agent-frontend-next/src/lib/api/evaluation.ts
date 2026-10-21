/**
 * Agent evaluation center API. Mirrors AgentEvaluationController.
 */

import { api, unwrap } from './client';

export type EvaluationMode = 'OFFLINE' | 'AGENT';
export type EvaluationRunStatus = 'RUNNING' | 'COMPLETED' | 'FAILED';
export type EvaluationCaseStatus = 'PASSED' | 'FAILED' | 'UNDETERMINED' | 'RUNNING';

export type EvaluationCaseDefinition = {
	id: string;
	name: string;
	scenario: string;
	category: 'risk' | 'inventory' | 'supplier' | 'rag' | 'workflow' | string;
	kind: string;
	mode: EvaluationMode;
	question: string;
	expectedBehavior: string;
	targetId?: string | null;
	version: string;
	enabled: boolean;
	basis: string;
	assertions: string[];
	embeddingRequired?: boolean;
	chatRequired?: boolean;
};

export type EvaluationAssertionResult = {
	id: string;
	description: string;
	expected: string;
	actual: string;
	passed: boolean;
};

export type EvaluationCaseResult = {
	caseId: string;
	name: string;
	scenario: string;
	category: string;
	status: EvaluationCaseStatus;
	durationMs: number;
	taskId?: string | null;
	assertions: EvaluationAssertionResult[];
	observationJson?: string | null;
	failureType?: string | null;
	failureMessage?: string | null;
	caseVersion?: string | null;
	taskMode?: string | null;
};

export type EvaluationCaseHistoryEntry = {
	runId: string;
	mode: EvaluationMode;
	runStatus: EvaluationRunStatus;
	startedAt: string;
	caseStatus: EvaluationCaseStatus;
	durationMs: number;
	taskId?: string | null;
	failureType?: string | null;
	failureMessage?: string | null;
	caseVersion?: string | null;
	datasetVersion?: string | null;
};

export type EvaluationRun = {
	runId: string;
	datasetVersion: string;
	mode: EvaluationMode;
	status: EvaluationRunStatus;
	environment: string;
	startedAt: string;
	completedAt?: string | null;
	totalCases: number;
	passed: number;
	failed: number;
	undetermined: number;
	durationMs?: number | null;
	failureReason?: string | null;
	metrics?: Record<string, unknown>;
	results: EvaluationCaseResult[];
};

export type EvaluationCapabilities = {
	datasetVersion: string;
	offlineCaseSetVersion: string;
	agentCaseSetVersion: string;
	totalCases: number;
	offlineCases: number;
	agentCases: number;
	feedbackCases?: number;
	maxOfflineCasesPerRun: number;
	maxAgentCasesPerRun: number;
	agentModeRequiresExplicitStart: boolean;
	agentModeRequiresConfirmation: boolean;
	agentRunLockScope: string;
	agentRunActive: boolean;
	environment: string;
	warning: string;
	chatModelReady: boolean;
	embeddingModelReady: boolean;
	agentModeUsesFallbackWhenModelMissing: boolean;
	agentModeRequiresConnectionTest?: boolean;
	agentModeBlocksUnreadyStart?: boolean;
	agentTimeoutMinutes?: number;
	chatConnectionVerified?: boolean;
	embeddingConnectionVerified?: boolean;
	chatState?: string;
	embeddingState?: string;
	chatTestedAt?: string | null;
	chatTestError?: string | null;
	embeddingTestedAt?: string | null;
	embeddingTestError?: string | null;
	embeddingRequiredForAgentEvaluation?: boolean;
	embeddingDependencyNote?: string | null;
	agentCasesRequiringEmbedding?: string[];
	agentCasesRequiringChat?: string[];
	agentEvaluationReady?: boolean;
	agentEvaluationBlockReason?: string | null;
	modelVerificationScope?: string;
};

export type EvaluationScenarioMetric = {
	category: string;
	total: number;
	passed: number;
	failed: number;
	undetermined: number;
	failureRate: number;
};

export type EvaluationMetrics = {
	mode: EvaluationMode;
	runsAnalyzed: number;
	caseSamples: number;
	passRate: number;
	failRate: number;
	undeterminedRate: number;
	averageDurationMs: number | null;
	p95DurationMs: number | null;
	p95SampleSize: number;
	averageRunDurationMs: number | null;
	p95RunDurationMs: number | null;
	runSampleSize: number;
	byScenario: EvaluationScenarioMetric[];
	topFailureReasons: { failureType: string; message: string; count: number }[];
	retrieval: {
		evaluatedQueries: number;
		hits: number;
		hitRateAt3: number;
		mrr: number;
		negativeQueries: number;
		negativePasses: number;
	} | null;
};

export type EvaluationMetricsReport = {
	runsAnalyzed: number;
	excludedIncompleteRuns: number;
	byMode: EvaluationMetrics[];
};

export type EvaluationRunSummary = {
	runId: string;
	mode: EvaluationMode;
	caseSetVersion: string;
	status: EvaluationRunStatus;
	startedAt: string;
	completedAt?: string | null;
	totalCases: number;
	passed: number;
	failed: number;
	undetermined: number;
	passRate: number;
	runDurationMs?: number | null;
	averageCaseDurationMs?: number | null;
	p95CaseDurationMs?: number | null;
	p95SampleSize: number;
};

export type EvaluationAssertionChange = {
	id: string;
	description: string;
	basePassed: boolean;
	targetPassed: boolean;
	baseExpected: string;
	targetExpected: string;
	baseActual: string;
	targetActual: string;
	change: 'IMPROVED' | 'REGRESSED' | 'VALUE_CHANGED' | string;
};

export type EvaluationCaseComparison = {
	caseId: string;
	name: string;
	baseCaseVersion?: string | null;
	targetCaseVersion?: string | null;
	baseStatus: EvaluationCaseStatus;
	targetStatus: EvaluationCaseStatus;
	change: 'NEW_FAILURE' | 'FIXED_FAILURE' | 'PERSISTENT_FAILURE' | 'UNCHANGED_PASS' | 'UNCHANGED_UNDETERMINED' | 'OTHER' | 'INCOMPARABLE' | string;
	assertionSetsCompatible: boolean;
	assertionChanges: EvaluationAssertionChange[];
	note?: string | null;
};

export type EvaluationComparisonDelta = {
	passed: number;
	failed: number;
	undetermined: number;
	passRate: number;
	runDurationMs?: number | null;
	averageCaseDurationMs?: number | null;
	p95CaseDurationMs?: number | null;
	p95SampleSize: number;
};

export type EvaluationRunComparison = {
	comparable: boolean;
	reason?: string | null;
	mode?: EvaluationMode | null;
	caseSetVersion?: string | null;
	versionNote?: string | null;
	baseRun: EvaluationRunSummary;
	targetRun: EvaluationRunSummary;
	delta: EvaluationComparisonDelta;
	cases: EvaluationCaseComparison[];
	newFailures: string[];
	fixedFailures: string[];
	persistentFailures: string[];
	missingInTarget: string[];
	extraInTarget: string[];
	assertionSetMismatches: string[];
};

type Envelope<T> = { success: boolean; message?: string; data?: T };

export async function fetchEvaluationCases(): Promise<EvaluationCaseDefinition[]> {
	const response = await api.get<Envelope<EvaluationCaseDefinition[]>>('/api/agent-evaluation/cases');
	return unwrap(response, '加载评测案例失败') ?? [];
}

export async function fetchEvaluationCaseHistory(caseId: string, limit = 20): Promise<EvaluationCaseHistoryEntry[]> {
	const response = await api.get<Envelope<EvaluationCaseHistoryEntry[]>>(
		`/api/agent-evaluation/cases/${encodeURIComponent(caseId)}/history?limit=${limit}`,
	);
	return unwrap(response, '加载案例历史失败') ?? [];
}

export async function fetchEvaluationCapabilities(): Promise<EvaluationCapabilities> {
	const response = await api.get<Envelope<EvaluationCapabilities>>('/api/agent-evaluation/capabilities');
	return unwrap(response, '加载评测能力失败');
}

export async function fetchEvaluationRuns(limit = 20): Promise<EvaluationRun[]> {
	const response = await api.get<Envelope<EvaluationRun[]>>(`/api/agent-evaluation/runs?limit=${limit}`);
	return unwrap(response, '加载评测运行失败') ?? [];
}

export async function fetchEvaluationRun(runId: string): Promise<EvaluationRun> {
	const response = await api.get<Envelope<EvaluationRun>>(`/api/agent-evaluation/runs/${encodeURIComponent(runId)}`);
	return unwrap(response, '加载评测运行详情失败');
}

export async function startEvaluationRun(
	caseIds: string[],
	mode: EvaluationMode,
	confirmAgentRun = false,
): Promise<EvaluationRun> {
	const response = await api.post<Envelope<EvaluationRun>>('/api/agent-evaluation/runs', {
		caseIds,
		mode,
		confirmAgentRun,
	});
	return unwrap(response, '启动评测失败');
}

export async function fetchEvaluationMetrics(limit = 20): Promise<EvaluationMetricsReport> {
	const response = await api.get<Envelope<EvaluationMetricsReport>>(`/api/agent-evaluation/metrics?limit=${limit}`);
	return unwrap(response, '加载评测指标失败');
}

export async function fetchEvaluationComparison(
	baseRunId: string,
	targetRunId: string,
): Promise<EvaluationRunComparison> {
	const response = await api.get<Envelope<EvaluationRunComparison>>(
		`/api/agent-evaluation/comparisons?baseRunId=${encodeURIComponent(baseRunId)}&targetRunId=${encodeURIComponent(targetRunId)}`,
	);
	return unwrap(response, '加载运行对比失败');
}
