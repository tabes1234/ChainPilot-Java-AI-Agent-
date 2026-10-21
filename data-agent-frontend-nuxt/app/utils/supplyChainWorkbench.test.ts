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

import { describe, expect, it } from 'vitest';
import type { DecisionProposal, DecisionTaskResult } from '../services/supplyChainDecision';
import type { SupplyChainTaskEvent } from '../services/supplyChainEvents';
import {
	buildAnalysisOutcomeMetrics,
	buildDecisionOutcomeMetrics,
	buildWorkbenchStages,
	countConfirmedProposals,
	countPendingProposals,
	isWorkbenchTaskActive,
	latestNodeLabel,
	pickWorkbenchFocus,
	workbenchStageStateLabel,
	workbenchStatusLabel,
	workbenchStatusTone,
	type WorkbenchTask,
} from './supplyChainWorkbench';

function task(overrides: Partial<WorkbenchTask> = {}): WorkbenchTask {
	return {
		taskId: 'task-analysis',
		status: 'RUNNING',
		currentStep: '',
		question: '分析可能延期的订单',
		source: 'ANALYSIS',
		updatedAt: '2026-10-20T10:00:00Z',
		error: null,
		...overrides,
	};
}

function event(overrides: Partial<SupplyChainTaskEvent> = {}): SupplyChainTaskEvent {
	return {
		eventId: 'event-1',
		taskId: 'task-analysis',
		sequence: 1,
		type: 'NODE_COMPLETED',
		stage: 'PLANNING',
		status: 'COMPLETED',
		occurredAt: '2026-10-20T10:00:00Z',
		durationMs: 12,
		summary: null,
		errorType: null,
		...overrides,
	};
}

function proposal(status: DecisionProposal['status'], id = 'proposal-1'): DecisionProposal {
	return { proposalId: id, status } as unknown as DecisionProposal;
}

function decision(overrides: Partial<DecisionTaskResult> = {}): DecisionTaskResult {
	return {
		taskId: 'task-decision',
		question: '补货 200 件是否足够？',
		status: 'COMPLETED',
		atRiskOrders: [],
		recommendations: [],
		comparison: null,
		simulation: null,
		plan: null,
		execution: null,
		...overrides,
	} as unknown as DecisionTaskResult;
}

describe('supply chain workbench state mapping', () => {
	it('recognizes only queued and running tasks as active', () => {
		expect(isWorkbenchTaskActive(task({ status: 'ACCEPTED' }))).toBe(true);
		expect(isWorkbenchTaskActive(task({ status: 'RUNNING' }))).toBe(true);
		expect(isWorkbenchTaskActive(task({ status: 'COMPLETED' }))).toBe(false);
		expect(isWorkbenchTaskActive(task({ status: 'FAILED' }))).toBe(false);
		expect(isWorkbenchTaskActive(null)).toBe(false);
	});

	it('prefers a live task, then an explicitly selected task, then the latest known task', () => {
		const completed = task({
			taskId: 'completed',
			status: 'COMPLETED',
			updatedAt: '2026-10-20T12:00:00Z',
		});
		const running = task({
			taskId: 'running',
			status: 'RUNNING',
			updatedAt: '2026-10-20T09:00:00Z',
		});
		expect(pickWorkbenchFocus(completed, running)?.taskId).toBe('running');
		expect(pickWorkbenchFocus(running, completed)?.taskId).toBe('running');

		const older = task({
			taskId: 'older',
			status: 'COMPLETED',
			updatedAt: '2026-10-20T08:00:00Z',
		});
		expect(pickWorkbenchFocus(older, completed, 'older')?.taskId).toBe('older');
		expect(pickWorkbenchFocus(older, completed, 'missing')?.taskId).toBe('completed');
		expect(pickWorkbenchFocus(null, null, 'anything')).toBeNull();
		expect(
			pickWorkbenchFocus(
				task({ taskId: 'no-date', updatedAt: undefined }),
				task({ taskId: 'dated', updatedAt: '2026-10-20T10:00:00Z' }),
			)?.taskId,
		).toBe('dated');
	});

	it('maps status labels and tones from backend statuses only', () => {
		expect(workbenchStatusLabel('ACCEPTED')).toBe('排队中');
		expect(workbenchStatusLabel('RUNNING')).toBe('AI 正在工作');
		expect(workbenchStatusLabel('COMPLETED')).toBe('已完成');
		expect(workbenchStatusLabel('FAILED')).toBe('执行失败');
		expect(workbenchStatusLabel('')).toBe('状态未知');
		expect(workbenchStatusTone('ACCEPTED')).toBe('queued');
		expect(workbenchStatusTone('RUNNING')).toBe('active');
		expect(workbenchStatusTone('COMPLETED')).toBe('success');
		expect(workbenchStatusTone('FAILED')).toBe('failure');
		expect(workbenchStatusTone('')).toBe('idle');
		expect(workbenchStageStateLabel('completed')).toBe('已完成');
		expect(workbenchStageStateLabel('active')).toBe('进行中');
		expect(workbenchStageStateLabel('failed')).toBe('失败');
		expect(workbenchStageStateLabel('upcoming')).toBe('待执行');
	});

	it('derives analysis stages only from observed nodes and the real current step', () => {
		const stages = buildWorkbenchStages(
			task({ currentStep: 'QUERYING_INVENTORY' }),
			[
				event({ eventId: 'e1', sequence: 1, stage: 'PLANNING' }),
				event({ eventId: 'e2', sequence: 2, stage: 'QUERYING_ORDERS' }),
			],
		);

		expect(stages.map((stage) => stage.key)).toEqual([
			'PLANNING',
			'QUERYING_ORDERS',
			'QUERYING_INVENTORY',
			'QUERYING_SUPPLIERS',
			'VALIDATING',
			'GENERATING_REPORT',
		]);
		expect(stages.map((stage) => stage.state)).toEqual([
			'completed',
			'completed',
			'active',
			'upcoming',
			'upcoming',
			'upcoming',
		]);
		expect(stages.map((stage) => stage.role)).toEqual([
			'planning',
			'data',
			'data',
			'data',
			'risk',
			'planning',
		]);
		expect(buildWorkbenchStages(null, [])).toEqual([]);
	});

	it('does not invent an active stage when only a queued task exists', () => {
		const stages = buildWorkbenchStages(
			task({ status: 'ACCEPTED', currentStep: 'QUEUE' }),
			[],
		);
		expect(stages.every((stage) => stage.state === 'upcoming')).toBe(true);
	});

	it('inserts the real retry stage only when retry evidence exists', () => {
		const baseEvents = [
			event({ eventId: 'e1', sequence: 1, stage: 'PLANNING' }),
			event({ eventId: 'e2', sequence: 2, stage: 'QUERYING_ORDERS' }),
			event({ eventId: 'e3', sequence: 3, stage: 'QUERYING_INVENTORY' }),
			event({ eventId: 'e4', sequence: 4, stage: 'QUERYING_SUPPLIERS' }),
		];
		const withoutRetry = buildWorkbenchStages(task({ currentStep: 'VALIDATING' }), baseEvents);
		expect(withoutRetry.some((stage) => stage.isRetry)).toBe(false);

		const withRetry = buildWorkbenchStages(
			task({ currentStep: 'VALIDATING' }),
			[
				...baseEvents,
				event({ eventId: 'e5', sequence: 5, stage: 'RETRYING_DATA_CHECK', type: 'NODE_STARTED', status: 'STARTED' }),
			],
		);
		const retry = withRetry.find((stage) => stage.isRetry);
		expect(retry).toMatchObject({
			key: 'RETRYING_DATA_CHECK',
			label: '重新校验数据',
			role: 'risk',
			isRetry: true,
		});
		expect(withRetry.findIndex((stage) => stage.key === 'RETRYING_DATA_CHECK')).toBe(
			withRetry.findIndex((stage) => stage.key === 'VALIDATING') + 1,
		);
	});

	it('marks the real failure stage and never completes later stages', () => {
		const stages = buildWorkbenchStages(
			task({ status: 'FAILED', currentStep: 'VALIDATING', error: '校验未通过' }),
			[
				event({ eventId: 'e1', sequence: 1, stage: 'PLANNING' }),
				event({ eventId: 'e2', sequence: 2, stage: 'QUERYING_ORDERS' }),
				event({ eventId: 'e3', sequence: 3, stage: 'QUERYING_INVENTORY' }),
				event({ eventId: 'e4', sequence: 4, stage: 'QUERYING_SUPPLIERS' }),
				event({ eventId: 'e5', sequence: 5, stage: 'VALIDATING', type: 'NODE_FAILED', status: 'FAILED' }),
			],
		);
		expect(stages.at(-1)?.state).toBe('upcoming');
		expect(stages.find((stage) => stage.key === 'VALIDATING')?.state).toBe('failed');
		expect(stages.filter((stage) => stage.state === 'failed')).toHaveLength(1);
	});

	it('uses the decision pipeline for a decision task that did not route into analysis', () => {
		const stages = buildWorkbenchStages(
			task({
				taskId: 'task-decision',
				source: 'DECISION',
				currentStep: 'DECISION_EXECUTION',
			}),
			[event({ eventId: 'e1', sequence: 1, stage: 'DECISION_INTENT' })],
		);
		expect(stages.map((stage) => stage.key)).toEqual([
			'DECISION_INTENT',
			'DECISION_EXECUTION',
			'VALIDATING_DECISION',
		]);
		expect(stages.map((stage) => stage.state)).toEqual(['completed', 'active', 'upcoming']);
	});

	it('recognizes decision tasks that actually route into the analysis workflow', () => {
		const stages = buildWorkbenchStages(
			task({
				taskId: 'task-decision',
				source: 'DECISION',
				currentStep: 'QUERYING_INVENTORY',
			}),
			[
				event({ eventId: 'e1', sequence: 1, stage: 'DECISION_INTENT' }),
				event({ eventId: 'e2', sequence: 2, stage: 'PLANNING' }),
			],
		);
		expect(stages.map((stage) => stage.key)).toEqual([
			'DECISION_INTENT',
			'PLANNING',
			'QUERYING_ORDERS',
			'QUERYING_INVENTORY',
			'QUERYING_SUPPLIERS',
			'VALIDATING',
			'GENERATING_REPORT',
		]);
		expect(stages.filter((stage) => stage.state === 'completed')).toHaveLength(3);
		expect(stages.find((stage) => stage.key === 'QUERYING_ORDERS')?.state).toBe('completed');
		expect(stages.find((stage) => stage.key === 'QUERYING_INVENTORY')?.state).toBe('active');
	});

	it('marks every applicable stage completed only for a completed task', () => {
		const stages = buildWorkbenchStages(task({ status: 'COMPLETED', currentStep: '' }), []);
		expect(stages.length).toBeGreaterThan(0);
		expect(stages.every((stage) => stage.state === 'completed')).toBe(true);
	});

	it('reports the latest completed backend node without extrapolating', () => {
		expect(latestNodeLabel([])).toBe('');
		expect(
			latestNodeLabel([
				event({ eventId: 'e1', sequence: 1, stage: 'PLANNING' }),
				event({ eventId: 'e2', sequence: 2, stage: 'QUERYING_SUPPLIERS' }),
				event({ eventId: 'e3', sequence: 3, stage: 'VALIDATING', type: 'NODE_STARTED', status: 'STARTED' }),
			]),
		).toBe('查询供应商');
	});

	it('counts proposals by their persisted status', () => {
		expect(countPendingProposals([])).toBe(0);
		expect(
			countPendingProposals([
				proposal('PENDING', 'p1'),
				proposal('CONFIRMED', 'p2'),
				proposal('EXPIRED', 'p3'),
			]),
		).toBe(1);
		expect(
			countConfirmedProposals([
				proposal('CONFIRMED', 'p1'),
				proposal('CONFIRMED', 'p2'),
				proposal('PENDING', 'p3'),
			]),
		).toBe(2);
	});
});

describe('supply chain workbench result metrics', () => {
	it('returns no decision metrics without a decision result', () => {
		expect(buildDecisionOutcomeMetrics(null, [proposal('PENDING')])).toEqual([]);
	});

	it('summarizes real comparison candidates, recommendation and baseline', () => {
		const metrics = buildDecisionOutcomeMetrics(
			decision({
				comparison: {
					candidates: [{}, {}, {}],
					baseline: { atRiskOrders: 5 },
					recommendation: {
						recommendedLabel: '补货 200 件',
						recommendedRank: 1,
					},
				},
			} as unknown as Partial<DecisionTaskResult>),
		);
		expect(metrics).toEqual([
			{ label: '候选方案', value: '3 个', role: 'simulation' },
			{ label: '确定性推荐', value: '补货 200 件', role: 'simulation' },
			{ label: '推荐排名', value: '#1', role: 'simulation' },
			{ label: '基线风险订单', value: '5', role: 'risk' },
		]);
	});

	it('shows incomparable recommendations honestly and adds pending confirmations', () => {
		const metrics = buildDecisionOutcomeMetrics(
			decision({
				comparison: {
					candidates: [{}],
					baseline: { atRiskOrders: 0 },
					recommendation: {
						recommendedLabel: '',
						recommendedRank: null,
					},
				},
			} as unknown as Partial<DecisionTaskResult>),
			[proposal('PENDING', 'p1'), proposal('PENDING', 'p2')],
		);
		expect(metrics).toContainEqual({ label: '确定性推荐', value: '—', role: 'simulation' });
		expect(metrics).toContainEqual({ label: '推荐排名', value: '不可比', role: 'simulation' });
		expect(metrics).toContainEqual({ label: '待确认提案', value: '2 个', role: 'confirmation' });
	});

	it('summarizes a real simulation with signed deltas', () => {
		const metrics = buildDecisionOutcomeMetrics(
			decision({
				simulation: {
					scenario: 'INVENTORY_REPLENISHMENT',
					sku: 'SKU-1',
					supplierId: null,
					replenishmentQuantity: 200,
					additionalDelayDays: null,
					baseline: { atRiskOrders: 4 },
					simulated: { atRiskOrders: 2 },
					delta: { atRiskOrders: -2, targetSkuAvailableToPromise: 10 },
				},
			} as unknown as Partial<DecisionTaskResult>),
		);
		expect(metrics).toContainEqual({ label: '模拟对象', value: 'SKU-1', role: 'data' });
		expect(metrics).toContainEqual({ label: '模拟参数', value: '200 件', role: 'simulation' });
		expect(metrics).toContainEqual({
			label: '风险订单变化',
			value: '4 → 2（-2）',
			role: 'risk',
		});
		expect(metrics).toContainEqual({ label: '目标 ATP 变化', value: '+10', role: 'data' });
	});

	it('adds confirmed proposals, plans and sandbox steps only when present', () => {
		const metrics = buildDecisionOutcomeMetrics(
			decision({
				plan: { planId: 'PLAN-1', status: 'SELECTED' },
				execution: { status: 'SUCCEEDED', steps: [{}, {}] },
			} as unknown as Partial<DecisionTaskResult>),
			[proposal('CONFIRMED')],
		);
		expect(metrics).toContainEqual({
			label: '关联处置方案',
			value: 'PLAN-1 · SELECTED',
			role: 'planning',
		});
		expect(metrics).toContainEqual({
			label: '沙箱演练',
			value: 'SUCCEEDED · 2 步',
			role: 'execution',
		});
		expect(metrics).toContainEqual({ label: '已确认提案', value: '1 个', role: 'confirmation' });
		expect(metrics.some((metric) => metric.label === '待确认提案')).toBe(false);
	});

	it('summarizes only the analysis report values that were actually returned', () => {
		expect(buildAnalysisOutcomeMetrics(null)).toEqual([]);
		expect(
			buildAnalysisOutcomeMetrics({
				taskId: 'task-analysis',
				status: 'COMPLETED',
				summary: 'summary',
				atRiskOrders: [{}, {}],
				citations: [{}, {}, {}],
				modelAssisted: true,
				validationAttempts: 1,
			}),
		).toEqual([
			{ label: '风险订单', value: '2 个', role: 'risk' },
			{ label: 'SOP 引用', value: '3 条', role: 'data' },
			{ label: '执行方式', value: '模型辅助', role: 'planning' },
			{ label: '校验重试', value: '1 次', role: 'system' },
		]);
	});
});
