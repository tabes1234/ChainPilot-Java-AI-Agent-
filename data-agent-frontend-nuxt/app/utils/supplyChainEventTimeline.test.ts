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
import type { SupplyChainTaskEvent } from '../services/supplyChainEvents';
import {
	buildTimelineEventViews,
	EVENT_STAGE_LABELS,
	EVENT_STATUS_LABELS,
	EVENT_TYPE_LABELS,
	formatEventDuration,
	formatEventTime,
	sortEventsBySequence,
	summarizeTimelineSignals,
	toTimelineEventView,
} from './supplyChainEventTimeline';

function event(overrides: Partial<SupplyChainTaskEvent> = {}): SupplyChainTaskEvent {
	return {
		eventId: 'event-1',
		taskId: 'task-1',
		sequence: 1,
		type: 'TASK_QUEUED',
		stage: 'QUEUE',
		status: 'QUEUED',
		occurredAt: '2026-10-19T10:00:00Z',
		durationMs: null,
		summary: 'task accepted',
		errorType: null,
		...overrides,
	};
}

describe('supply chain event timeline', () => {
	it('orders events by the backend sequence instead of timestamps or payload order', () => {
		const events = [
			event({ eventId: 'third', sequence: 3, occurredAt: '2026-10-19T09:00:00Z' }),
			event({ eventId: 'first', sequence: 1, occurredAt: '2026-10-19T12:00:00Z' }),
			event({ eventId: 'second', sequence: 2, occurredAt: '2026-10-19T10:00:00Z' }),
		];

		expect(buildTimelineEventViews(events).map((view) => view.sequence)).toEqual([1, 2, 3]);
		expect(sortEventsBySequence(events).map((item) => item.eventId)).toEqual([
			'first',
			'second',
			'third',
		]);
		expect(events.map((item) => item.sequence)).toEqual([3, 1, 2]);
	});

	it('maps every known event type and event status to readable labels', () => {
		const mappings: { type: string; status: string; typeLabel: string; statusLabel: string }[] = [
			{ type: 'TASK_QUEUED', status: 'QUEUED', typeLabel: '任务已排队', statusLabel: '排队中' },
			{ type: 'TASK_STARTED', status: 'STARTED', typeLabel: '任务开始执行', statusLabel: '进行中' },
			{ type: 'TASK_COMPLETED', status: 'COMPLETED', typeLabel: '任务完成', statusLabel: '已完成' },
			{ type: 'TASK_FAILED', status: 'FAILED', typeLabel: '任务失败', statusLabel: '失败' },
			{ type: 'NODE_STARTED', status: 'STARTED', typeLabel: '节点开始执行', statusLabel: '进行中' },
			{ type: 'NODE_COMPLETED', status: 'COMPLETED', typeLabel: '节点执行完成', statusLabel: '已完成' },
			{ type: 'NODE_FAILED', status: 'FAILED', typeLabel: '节点执行失败', statusLabel: '失败' },
			{ type: 'TOOL_STARTED', status: 'STARTED', typeLabel: '工具调用开始', statusLabel: '进行中' },
			{ type: 'TOOL_COMPLETED', status: 'COMPLETED', typeLabel: '工具调用完成', statusLabel: '已完成' },
			{ type: 'TOOL_FAILED', status: 'FAILED', typeLabel: '工具调用失败', statusLabel: '失败' },
			{ type: 'MODEL_COMPLETED', status: 'COMPLETED', typeLabel: '模型调用完成', statusLabel: '已完成' },
			{ type: 'MODEL_FAILED', status: 'FAILED', typeLabel: '模型调用失败', statusLabel: '失败' },
			{ type: 'FALLBACK_USED', status: 'FALLBACK', typeLabel: '规则降级', statusLabel: '规则降级' },
			{ type: 'INTENT_RECOGNIZED', status: 'STARTED', typeLabel: '识别决策意图', statusLabel: '进行中' },
			{
				type: 'CLARIFICATION_REQUIRED',
				status: 'COMPLETED',
				typeLabel: '需要补充信息',
				statusLabel: '已完成',
			},
			{ type: 'PROPOSAL_CREATED', status: 'STARTED', typeLabel: '创建待确认提案', statusLabel: '进行中' },
			{
				type: 'PROPOSAL_CONFIRMED',
				status: 'COMPLETED',
				typeLabel: '提案已确认执行',
				statusLabel: '已完成',
			},
			{ type: 'PROPOSAL_CANCELLED', status: 'COMPLETED', typeLabel: '提案已取消', statusLabel: '已完成' },
			{ type: 'PROPOSAL_FAILED', status: 'FAILED', typeLabel: '提案执行失败', statusLabel: '失败' },
		];

		expect(Object.keys(EVENT_TYPE_LABELS)).toEqual(mappings.map((mapping) => mapping.type));
		for (const mapping of mappings) {
			const view = toTimelineEventView(event(mapping));
			expect(view.typeLabel).toBe(mapping.typeLabel);
			expect(view.statusLabel).toBe(mapping.statusLabel);
			expect(view.tone).not.toBe('neutral');
		}
		expect(EVENT_STATUS_LABELS.COMPLETED).toBe('已完成');
	});

	it('maps every known stage, including workflow nodes and tool stages', () => {
		for (const [stage, label] of Object.entries(EVENT_STAGE_LABELS)) {
			expect(toTimelineEventView(event({ stage })).stageLabel).toBe(label);
		}
		expect(EVENT_STAGE_LABELS.QUERYING_ORDERS).toBe('查询风险订单');
		expect(EVENT_STAGE_LABELS.findAtRiskOrders).toBe('订单风险工具');
		expect(EVENT_STAGE_LABELS.DECISION_INTENT).toBe('识别决策意图');
		expect(EVENT_STAGE_LABELS.PROPOSAL).toBe('人工确认提案');
		expect(EVENT_STAGE_LABELS.compareDisposalScenarios).toBe('补货方案对比工具');
		expect(EVENT_STAGE_LABELS.checkSandboxExecutionEligibility).toBe('沙箱执行资格工具');
	});

	it('renders unknown future types safely without inventing model or fallback signals', () => {
		const view = toTimelineEventView(
			event({ type: 'CHECKPOINT_CREATED', stage: 'CHECKPOINT', status: 'SKIPPED' }),
		);

		expect(view.typeLabel).toBe('CHECKPOINT_CREATED');
		expect(view.stageLabel).toBe('CHECKPOINT');
		expect(view.statusLabel).toBe('SKIPPED');
		expect(view.tone).toBe('neutral');
		expect(view.icon).toBe('mdi-help-circle-outline');
		expect(view.isModelEvent).toBe(false);
		expect(view.isModelFailure).toBe(false);
		expect(view.isFallback).toBe(false);

		const empty = toTimelineEventView(event({ type: '', stage: '', status: '' }));
		expect(empty.typeLabel).toBe('未知事件');
		expect(empty.stageLabel).toBe('未标注阶段');
		expect(empty.statusLabel).toBe('状态未知');
	});

	it('keeps one stable role identity for each real event meaning', () => {
		expect(
			toTimelineEventView(event({ type: 'MODEL_COMPLETED', stage: 'GENERATING_REPORT' })),
		).toMatchObject({
			role: 'planning',
			roleLabel: '规划与解释',
			roleIcon: 'mdi-compass-outline',
		});
		expect(
			toTimelineEventView(event({ type: 'TOOL_COMPLETED', stage: 'simulateInventoryReplenishment' })),
		).toMatchObject({
			role: 'simulation',
			roleLabel: '模拟与对比',
			roleIcon: 'mdi-chart-timeline-variant',
		});
		expect(
			toTimelineEventView(event({ type: 'PROPOSAL_CREATED', stage: 'PROPOSAL' })),
		).toMatchObject({
			role: 'confirmation',
			roleLabel: '人工确认',
			roleIcon: 'mdi-account-check-outline',
		});
		expect(
			toTimelineEventView(event({ type: 'TOOL_FAILED', stage: 'QUERYING_INVENTORY' })),
		).toMatchObject({ role: 'failure', roleLabel: '执行异常' });
		expect(toTimelineEventView(event({ type: 'TASK_QUEUED' }))).toMatchObject({
			role: 'system',
			roleLabel: '任务编排',
		});
	});

	it('distinguishes real model events and fallback only when those events exist', () => {
		expect(summarizeTimelineSignals([])).toEqual({
			hasModelCompleted: false,
			hasModelFailed: false,
			hasFallback: false,
		});
		expect(summarizeTimelineSignals([event({ type: 'TASK_COMPLETED', status: 'COMPLETED' })])).toEqual({
			hasModelCompleted: false,
			hasModelFailed: false,
			hasFallback: false,
		});
		expect(summarizeTimelineSignals([event({ type: 'NODE_COMPLETED', status: 'COMPLETED' })])).toEqual({
			hasModelCompleted: false,
			hasModelFailed: false,
			hasFallback: false,
		});
		expect(
			summarizeTimelineSignals([
				event({ type: 'MODEL_COMPLETED', status: 'COMPLETED' }),
				event({ eventId: 'event-2', sequence: 2, type: 'FALLBACK_USED', status: 'FALLBACK' }),
			]),
		).toEqual({ hasModelCompleted: true, hasModelFailed: false, hasFallback: true });
		expect(
			summarizeTimelineSignals([
				event({ type: 'MODEL_FAILED', status: 'FAILED', errorType: 'IllegalStateException' }),
				event({ eventId: 'event-2', sequence: 2, type: 'FALLBACK_USED', status: 'FALLBACK' }),
			]),
		).toEqual({ hasModelCompleted: false, hasModelFailed: true, hasFallback: true });

		const modelCompleted = toTimelineEventView(event({ type: 'MODEL_COMPLETED', status: 'COMPLETED' }));
		expect(modelCompleted).toMatchObject({
			isModelEvent: true,
			isModelFailure: false,
			isFallback: false,
		});
		const modelFailed = toTimelineEventView(event({ type: 'MODEL_FAILED', status: 'FAILED' }));
		expect(modelFailed).toMatchObject({
			isModelEvent: true,
			isModelFailure: true,
			isFallback: false,
		});
	});

	it('keeps safe summaries and error classes without exposing other payload fields', () => {
		const view = toTimelineEventView(
			event({
				summary: 'orders=4, inventory=2, suppliers=2',
				errorType: 'IllegalStateException',
			}),
		);

		expect(view.summary).toBe('orders=4, inventory=2, suppliers=2');
		expect(view.errorType).toBe('IllegalStateException');
		expect(toTimelineEventView(event({ summary: null, errorType: null }))).toMatchObject({
			summary: '',
			errorType: '',
		});
	});

	it('formats occurrence time and only shows duration when a value is provided', () => {
		expect(formatEventDuration(0)).toBe('0 ms');
		expect(formatEventDuration(42)).toBe('42 ms');
		expect(formatEventDuration(999)).toBe('999 ms');
		expect(formatEventDuration(1500)).toBe('1.5 s');
		expect(formatEventDuration(null)).toBe('');
		expect(formatEventDuration(undefined)).toBe('');
		expect(formatEventDuration(-1)).toBe('');

		expect(formatEventTime('')).toBe('时间未知');
		expect(formatEventTime('not-a-date')).toBe('not-a-date');
		expect(formatEventTime('2026-10-19T12:34:56Z')).toMatch(/\d{2}:\d{2}:\d{2}/);

		const view = toTimelineEventView(event({ durationMs: 875, occurredAt: '2026-10-19T12:34:56Z' }));
		expect(view.durationLabel).toBe('875 ms');
		expect(view.timeLabel).toMatch(/\d{2}:\d{2}:\d{2}/);
	});
});
