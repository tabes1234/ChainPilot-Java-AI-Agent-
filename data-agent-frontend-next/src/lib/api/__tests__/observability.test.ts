import { readFileSync } from 'node:fs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
	fetchObservabilityDetail,
	fetchObservabilityRuns,
	fetchObservabilitySummary,
	narrativeSourceLabel,
} from '@/lib/api/observability';

afterEach(() => {
	vi.unstubAllGlobals();
});

const page = readFileSync('src/app/agent-observability/page.tsx', 'utf8');
const navigation = readFileSync('src/lib/navigation.ts', 'utf8');

describe('agent observability API', () => {
	it('loads the bounded summary and encodes run filters', async () => {
		const urls: string[] = [];
		vi.stubGlobal('fetch', vi.fn(async (input: string) => {
			urls.push(String(input));
			return new Response(JSON.stringify({
				scope: '最近分析/决策任务窗口',
				windowSize: 0,
				totalTasks: 0,
				completedTasks: 0,
				failedTasks: 0,
				runningTasks: 0,
				cancelledTasks: 0,
				degradedTasks: 0,
				toolCalls: 0,
				toolFailures: 0,
				modelCompletions: 0,
				modelFailures: 0,
				fallbackEvents: 0,
				modelVerifiedTasks: 0,
				legacyUnverifiedTasks: 0,
				deterministicFallbackTasks: 0,
				tasksWithDuration: 0,
				failureTypes: {},
				narrativeSources: {},
				items: [],
			}), { status: 200, headers: { 'content-type': 'application/json' } });
		}));

		await fetchObservabilitySummary();
		const runs = await fetchObservabilityRuns({
			page: 2,
			pageSize: 5,
			status: 'FAILED',
			mode: 'ANALYSIS',
			narrativeSource: 'DETERMINISTIC_FALLBACK',
			fallback: true,
			failureType: 'MODEL_TIMEOUT',
			query: 'task/1',
		});

		expect(urls[0]).toContain('/api/supply-chain/observability/summary');
		expect(urls[1]).toContain('page=2');
		expect(urls[1]).toContain('pageSize=5');
		expect(urls[1]).toContain('status=FAILED');
		expect(urls[1]).toContain('narrativeSource=DETERMINISTIC_FALLBACK');
		expect(urls[1]).toContain('fallback=true');
		expect(urls[1]).toContain('failureType=MODEL_TIMEOUT');
		expect(urls[1]).toContain('query=task%2F1');
		expect(runs.scope).toContain('最近分析/决策任务窗口');
	});

	it('encodes a taskId for the detail endpoint and labels every known source', async () => {
		let requestedUrl = '';
		vi.stubGlobal('fetch', vi.fn(async (input: string) => {
			requestedUrl = String(input);
			return new Response(JSON.stringify({ run: { taskId: 'task/1' }, timeline: [] }), {
				status: 200,
				headers: { 'content-type': 'application/json' },
			});
		}));

		const detail = await fetchObservabilityDetail('task/1');
		expect(requestedUrl).toContain('/api/supply-chain/observability/runs/task%2F1');
		expect(detail.run.taskId).toBe('task/1');
		expect(narrativeSourceLabel('MODEL_VERIFIED')).toBe('模型已验证');
		expect(narrativeSourceLabel('MODEL_LEGACY_UNVERIFIED')).toBe('历史模型未验证');
		expect(narrativeSourceLabel('DETERMINISTIC_FALLBACK')).toBe('确定性回退');
		expect(narrativeSourceLabel('UNKNOWN')).toBe('来源未记录');
	});
});

describe('agent observability page contract', () => {
	it('renders the real event timeline, diagnostics and safe missing-data states', () => {
		expect(page).toContain('Agent 运行观测');
		expect(page).toContain('执行时间线');
		expect(page).toContain('故障诊断');
		expect(page).toContain('确定性规则诊断');
		expect(page).toContain('没有事件的阶段不会显示');
		expect(page).toContain('输入摘要未持久化');
		expect(page).toContain('模型标识 / Token / 费用未返回');
		expect(page).toContain('不补写');
		expect(page).not.toContain('setInterval(');
		expect(navigation).toContain("'/agent-observability'");
	});
});
