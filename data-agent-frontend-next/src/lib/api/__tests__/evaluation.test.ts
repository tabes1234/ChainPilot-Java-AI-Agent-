import { afterEach, describe, expect, it, vi } from 'vitest';
import {
	fetchEvaluationCaseHistory,
	fetchEvaluationCases,
	fetchEvaluationComparison,
	startEvaluationRun,
	type EvaluationCaseDefinition,
	type EvaluationCaseResult,
} from '@/lib/api/evaluation';
import {
	assertionChangeLabel,
	canAddToSelection,
	caseStatusLabel,
	comparisonChangeLabel,
	formatPercent,
	formatSignedDuration,
	formatSignedPercent,
	modeLabel,
	p95Label,
	resolveTaskLink,
	runStatusLabel,
	selectionMode,
} from '@/lib/evaluation-format';
import { NAV_ITEMS } from '@/lib/navigation';
import { resolvePageTheme } from '@/lib/page-theme';

afterEach(() => {
	vi.unstubAllGlobals();
});

function caseDefinition(id: string, mode: 'OFFLINE' | 'AGENT'): EvaluationCaseDefinition {
	return {
		id,
		name: id,
		scenario: '场景',
		category: 'risk',
		kind: 'ORDER_RISK',
		mode,
		question: '问题',
		expectedBehavior: '期望',
		version: '1.0.0',
		enabled: true,
		basis: '依据',
		assertions: ['断言'],
	};
}

function result(overrides: Partial<EvaluationCaseResult> = {}): EvaluationCaseResult {
	return {
		caseId: 'CASE-1',
		name: '案例',
		scenario: '场景',
		category: 'workflow',
		status: 'PASSED',
		durationMs: 12,
		assertions: [],
		...overrides,
	};
}

describe('evaluation formatting', () => {
	it('formats percentages and durations without inventing values', () => {
		expect(formatPercent(0.856)).toBe('85.6%');
		expect(formatPercent(0)).toBe('0.0%');
		expect(formatPercent(undefined)).toBe('—');
		expect(formatPercent(Number.NaN)).toBe('—');
	});

	it('reports P95 only when the sample size supports it', () => {
		expect(p95Label({ p95DurationMs: null, p95SampleSize: 3 })).toContain('样本不足');
		expect(p95Label({ p95DurationMs: 2450, p95SampleSize: 12 })).toContain('2.45 s');
	});

	it('labels statuses and modes from backend enums only', () => {
		expect(modeLabel('OFFLINE')).toBe('离线确定性');
		expect(modeLabel('AGENT')).toBe('真实工作流');
		expect(caseStatusLabel('UNDETERMINED')).toBe('无法判定');
		expect(runStatusLabel('RUNNING')).toBe('运行中');
	});
});

describe('selection rules', () => {
	const cases = [caseDefinition('A', 'OFFLINE'), caseDefinition('B', 'OFFLINE'), caseDefinition('C', 'AGENT')];

	it('keeps one mode per run', () => {
		expect(selectionMode(cases, ['A', 'B'])).toBe('OFFLINE');
		expect(selectionMode(cases, ['A', 'C'])).toBeNull();
		expect(canAddToSelection(cases, ['A'], 'C')).toBe(false);
		expect(canAddToSelection(cases, [], 'C')).toBe(true);
	});
});

describe('comparison formatting', () => {
	it('labels comparison and assertion changes without inventing results', () => {
		expect(comparisonChangeLabel('NEW_FAILURE')).toBe('新增失败');
		expect(comparisonChangeLabel('FIXED_FAILURE')).toBe('已修复');
		expect(comparisonChangeLabel('PERSISTENT_FAILURE')).toBe('持续失败');
		expect(assertionChangeLabel('REGRESSED')).toBe('回归失败');
		expect(formatSignedPercent(0.125)).toBe('+12.5 pp');
		expect(formatSignedPercent(-0.05)).toBe('-5.0 pp');
		expect(formatSignedDuration(-1200)).toBe('-1.20 s');
	});
});

describe('task links', () => {
	it('uses the recorded task mode to open the matching workspace', () => {
		expect(resolveTaskLink(result({ taskId: 't-1', observationJson: '{"taskMode":"DECISION"}' }))).toEqual({
			href: '/supply-chain/decision?taskId=t-1',
			label: '打开决策任务',
		});
		expect(resolveTaskLink(result({ taskId: 't-2' }))).toEqual({
			href: '/supply-chain?taskId=t-2',
			label: '打开分析任务',
		});
		expect(resolveTaskLink(result())).toBeNull();
	});

	it('uses the persisted taskMode without parsing display text', () => {
		expect(resolveTaskLink(result({ taskId: 't-4', taskMode: 'DECISION', observationJson: null }))?.href).toBe(
			'/supply-chain/decision?taskId=t-4',
		);
		expect(resolveTaskLink(result({ taskId: 't-5', taskMode: 'ANALYSIS' }))?.href).toBe(
			'/supply-chain?taskId=t-5',
		);
	});

	it('falls back to analysis when the observation is not valid JSON', () => {
		expect(resolveTaskLink(result({ taskId: 't-3', observationJson: 'not-json' }))?.href).toBe(
			'/supply-chain?taskId=t-3',
		);
	});
});

describe('evaluation API', () => {
	it('loads the real case catalogue with the API envelope', async () => {
		vi.stubGlobal(
			'fetch',
			vi.fn(async () => new Response(JSON.stringify({ success: true, data: [caseDefinition('A', 'OFFLINE')] }), {
				status: 200,
				headers: { 'content-type': 'application/json' },
			})),
		);

		const cases = await fetchEvaluationCases();
		expect(cases).toHaveLength(1);
		expect(cases[0].id).toBe('A');
	});

	it('surfaces business failures even on HTTP 200', async () => {
		vi.stubGlobal(
			'fetch',
			vi.fn(async () => new Response(JSON.stringify({ success: false, message: '后端拒绝启动' }), {
				status: 200,
				headers: { 'content-type': 'application/json' },
			})),
		);

		await expect(startEvaluationRun(['A'], 'OFFLINE')).rejects.toThrow('后端拒绝启动');
	});

	it('posts caseIds and mode to the start endpoint', async () => {
		const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
			expect(url).toBe('http://localhost:8065/api/agent-evaluation/runs');
			expect(init?.method).toBe('POST');
			expect(JSON.parse(String(init?.body))).toEqual({
				caseIds: ['A', 'B'],
				mode: 'OFFLINE',
				confirmAgentRun: false,
			});
			return new Response(
				JSON.stringify({
					success: true,
					data: {
						runId: 'run-1',
						datasetVersion: '1.0.0',
						mode: 'OFFLINE',
						status: 'COMPLETED',
						environment: 'SERVER_IN_PROCESS',
						startedAt: '2026-10-20T00:00:00Z',
						totalCases: 2,
						passed: 2,
						failed: 0,
						undetermined: 0,
						results: [],
					},
				}),
				{ status: 200, headers: { 'content-type': 'application/json' } },
			);
		});
		vi.stubGlobal('fetch', fetchMock);

		const run = await startEvaluationRun(['A', 'B'], 'OFFLINE');
		expect(run.runId).toBe('run-1');
		expect(fetchMock).toHaveBeenCalledTimes(1);
	});

	it('carries the explicit AGENT confirmation flag to the backend', async () => {
		const fetchMock = vi.fn(async (_url: string, init?: RequestInit) => {
			expect(JSON.parse(String(init?.body))).toEqual({
				caseIds: ['WF-ANALYSIS-HIGH-RISK'],
				mode: 'AGENT',
				confirmAgentRun: true,
			});
			return new Response(JSON.stringify({ success: true, data: { runId: 'run-a' } }), {
				status: 200,
				headers: { 'content-type': 'application/json' },
			});
		});
		vi.stubGlobal('fetch', fetchMock);

		await expect(startEvaluationRun(['WF-ANALYSIS-HIGH-RISK'], 'AGENT', true)).resolves.toMatchObject({
			runId: 'run-a',
		});
	});

	it('loads a persisted run comparison instead of comparing client-side arrays', async () => {
		const fetchMock = vi.fn(async (url: string) => {
			expect(url).toBe(
				'http://localhost:8065/api/agent-evaluation/comparisons?baseRunId=base%2F1&targetRunId=target-2',
			);
			return new Response(
				JSON.stringify({
					success: true,
					data: {
						comparable: true,
						baseRun: { runId: 'base/1' },
						targetRun: { runId: 'target-2' },
						newFailures: [],
						fixedFailures: ['CASE-1'],
						persistentFailures: [],
						cases: [],
					},
				}),
				{ status: 200, headers: { 'content-type': 'application/json' } },
			);
		});
		vi.stubGlobal('fetch', fetchMock);

		const comparison = await fetchEvaluationComparison('base/1', 'target-2');
		expect(comparison.fixedFailures).toEqual(['CASE-1']);
	});

	it('encodes the case id in the history endpoint', async () => {
		const fetchMock = vi.fn(async (url: string) => {
			expect(url).toBe('http://localhost:8065/api/agent-evaluation/cases/RISK%2F1/history?limit=5');
			return new Response(JSON.stringify({ success: true, data: [] }), {
				status: 200,
				headers: { 'content-type': 'application/json' },
			});
		});
		vi.stubGlobal('fetch', fetchMock);

		expect(await fetchEvaluationCaseHistory('RISK/1', 5)).toEqual([]);
	});
});

describe('evaluation navigation', () => {
	it('exposes the evaluation center under the Agent engineering group', () => {
		const item = NAV_ITEMS.find((entry) => entry.href === '/agent-evaluation');
		expect(item?.label).toBe('Agent 评测中心');
		expect(item?.group).toBe('Agent 工程');
		expect(resolvePageTheme('/agent-evaluation')).toBe('system');
	});
});
