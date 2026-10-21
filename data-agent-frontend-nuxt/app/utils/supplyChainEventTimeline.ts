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

import type { SupplyChainTaskEvent } from '../services/supplyChainEvents';
import {
	CHAINPILOT_ROLES,
	roleForEvent,
	type ChainPilotRoleId,
} from './supplyChainAgentRoles';

export type TimelineTone = 'neutral' | 'info' | 'success' | 'warning' | 'danger';

export type SupplyChainTimelineEvent = {
	id: string;
	sequence: number;
	type: string;
	typeLabel: string;
	stage: string;
	stageLabel: string;
	status: string;
	statusLabel: string;
	tone: TimelineTone;
	icon: string;
	role: ChainPilotRoleId;
	roleLabel: string;
	roleIcon: string;
	occurredAt: string;
	timeLabel: string;
	durationMs: number | null;
	durationLabel: string;
	summary: string;
	errorType: string;
	isModelEvent: boolean;
	isModelFailure: boolean;
	isFallback: boolean;
};

export type SupplyChainTimelineSignals = {
	hasModelCompleted: boolean;
	hasModelFailed: boolean;
	hasFallback: boolean;
};

export const EVENT_TYPE_LABELS: Record<string, string> = {
	TASK_QUEUED: '任务已排队',
	TASK_STARTED: '任务开始执行',
	TASK_COMPLETED: '任务完成',
	TASK_FAILED: '任务失败',
	NODE_STARTED: '节点开始执行',
	NODE_COMPLETED: '节点执行完成',
	NODE_FAILED: '节点执行失败',
	TOOL_STARTED: '工具调用开始',
	TOOL_COMPLETED: '工具调用完成',
	TOOL_FAILED: '工具调用失败',
	MODEL_COMPLETED: '模型调用完成',
	MODEL_FAILED: '模型调用失败',
	FALLBACK_USED: '规则降级',
	INTENT_RECOGNIZED: '识别决策意图',
	CLARIFICATION_REQUIRED: '需要补充信息',
	PROPOSAL_CREATED: '创建待确认提案',
	PROPOSAL_CONFIRMED: '提案已确认执行',
	PROPOSAL_CANCELLED: '提案已取消',
	PROPOSAL_FAILED: '提案执行失败',
};

export const EVENT_STAGE_LABELS: Record<string, string> = {
	QUEUE: '任务队列',
	WORKFLOW: '工作流',
	PLANNING: '制定执行计划',
	QUERYING_ORDERS: '查询风险订单',
	QUERYING_INVENTORY: '查询库存',
	QUERYING_SUPPLIERS: '查询供应商',
	VALIDATING: '校验业务证据',
	RETRYING_DATA_CHECK: '重新校验数据',
	GENERATING_REPORT: '生成分析报告',
	VALIDATION_FAILED: '校验未通过',
	DECISION_INTENT: '识别决策意图',
	DECISION_EXECUTION: '执行决策工具与规则',
	VALIDATING_DECISION: '校验决策结果',
	DECISION_SUMMARY: '生成决策说明',
	PROPOSAL: '人工确认提案',
	findAtRiskOrders: '订单风险工具',
	findInventory: '库存查询工具',
	findSuppliers: '供应商查询工具',
	findDisposalRecommendations: '处置建议工具',
	simulateInventoryReplenishment: '补货模拟工具',
	simulateSupplierDelay: '供应商延期模拟工具',
	compareDisposalScenarios: '补货方案对比工具',
	compareSupplierDelayScenarios: '供应商延期对比工具',
	findScenarioComparison: '方案对比查询工具',
	findDisposalPlan: '处置方案查询工具',
	checkSandboxExecutionEligibility: '沙箱执行资格工具',
	findSandboxExecution: '沙箱演练查询工具',
	findSandboxExecutions: '沙箱演练列表工具',
};

export const EVENT_STATUS_LABELS: Record<string, string> = {
	QUEUED: '排队中',
	STARTED: '进行中',
	COMPLETED: '已完成',
	FAILED: '失败',
	FALLBACK: '规则降级',
};

const EVENT_TYPE_TONES: Record<string, TimelineTone> = {
	TASK_QUEUED: 'info',
	TASK_STARTED: 'info',
	TASK_COMPLETED: 'success',
	TASK_FAILED: 'danger',
	NODE_STARTED: 'info',
	NODE_COMPLETED: 'success',
	NODE_FAILED: 'danger',
	TOOL_STARTED: 'info',
	TOOL_COMPLETED: 'success',
	TOOL_FAILED: 'danger',
	MODEL_COMPLETED: 'success',
	MODEL_FAILED: 'danger',
	FALLBACK_USED: 'warning',
	INTENT_RECOGNIZED: 'info',
	CLARIFICATION_REQUIRED: 'warning',
	PROPOSAL_CREATED: 'info',
	PROPOSAL_CONFIRMED: 'success',
	PROPOSAL_CANCELLED: 'warning',
	PROPOSAL_FAILED: 'danger',
};

const EVENT_TYPE_ICONS: Record<string, string> = {
	TASK_QUEUED: 'mdi-timer-sand',
	TASK_STARTED: 'mdi-play-circle-outline',
	TASK_COMPLETED: 'mdi-check-circle-outline',
	TASK_FAILED: 'mdi-alert-circle-outline',
	NODE_STARTED: 'mdi-sitemap-outline',
	NODE_COMPLETED: 'mdi-checkbox-marked-circle-outline',
	NODE_FAILED: 'mdi-alert-octagon-outline',
	TOOL_STARTED: 'mdi-wrench-outline',
	TOOL_COMPLETED: 'mdi-check-decagram-outline',
	TOOL_FAILED: 'mdi-wrench-outline',
	MODEL_COMPLETED: 'mdi-robot-outline',
	MODEL_FAILED: 'mdi-robot-outline',
	FALLBACK_USED: 'mdi-swap-horizontal',
	INTENT_RECOGNIZED: 'mdi-transit-connection-variant',
	CLARIFICATION_REQUIRED: 'mdi-help-circle-outline',
	PROPOSAL_CREATED: 'mdi-file-sign',
	PROPOSAL_CONFIRMED: 'mdi-check-decagram-outline',
	PROPOSAL_CANCELLED: 'mdi-cancel',
	PROPOSAL_FAILED: 'mdi-alert-octagon-outline',
};

const UNKNOWN_TYPE_LABEL = '未知事件';
const UNKNOWN_STAGE_LABEL = '未标注阶段';
const UNKNOWN_STATUS_LABEL = '状态未知';
const UNKNOWN_ICON = 'mdi-help-circle-outline';

const TIME_FORMATTER = new Intl.DateTimeFormat('zh-CN', {
	month: '2-digit',
	day: '2-digit',
	hour: '2-digit',
	minute: '2-digit',
	second: '2-digit',
	hour12: false,
});

function text(value: unknown): string {
	return typeof value === 'string' ? value.trim() : '';
}

export function sortEventsBySequence(events: SupplyChainTaskEvent[]): SupplyChainTaskEvent[] {
	return [...events].sort((left, right) => {
		const leftSequence = Number.isFinite(left.sequence) ? left.sequence : Number.MAX_SAFE_INTEGER;
		const rightSequence = Number.isFinite(right.sequence) ? right.sequence : Number.MAX_SAFE_INTEGER;
		return leftSequence - rightSequence;
	});
}

export function formatEventTime(value: string): string {
	const raw = text(value);
	if (!raw) {
		return '时间未知';
	}
	const date = new Date(raw);
	return Number.isNaN(date.getTime()) ? raw : TIME_FORMATTER.format(date);
}

export function formatEventDuration(durationMs: number | null | undefined): string {
	if (typeof durationMs !== 'number' || !Number.isFinite(durationMs) || durationMs < 0) {
		return '';
	}
	if (durationMs < 1000) {
		return `${Math.round(durationMs)} ms`;
	}
	return `${(durationMs / 1000).toFixed(1)} s`;
}

export function toTimelineEventView(event: SupplyChainTaskEvent): SupplyChainTimelineEvent {
	const type = text(event.type);
	const stage = text(event.stage);
	const status = text(event.status);
	const sequence = Number.isFinite(event.sequence) ? event.sequence : 0;
	const role = roleForEvent({ type, stage });
	return {
		id: text(event.eventId) || `${text(event.taskId)}:${sequence}:${type}`,
		sequence,
		type,
		typeLabel: EVENT_TYPE_LABELS[type] || type || UNKNOWN_TYPE_LABEL,
		stage,
		stageLabel: EVENT_STAGE_LABELS[stage] || stage || UNKNOWN_STAGE_LABEL,
		status,
		statusLabel: EVENT_STATUS_LABELS[status] || status || UNKNOWN_STATUS_LABEL,
		tone: EVENT_TYPE_TONES[type] || 'neutral',
		icon: EVENT_TYPE_ICONS[type] || UNKNOWN_ICON,
		role,
		roleLabel: CHAINPILOT_ROLES[role].label,
		roleIcon: CHAINPILOT_ROLES[role].icon,
		occurredAt: text(event.occurredAt),
		timeLabel: formatEventTime(text(event.occurredAt)),
		durationMs: typeof event.durationMs === 'number' ? event.durationMs : null,
		durationLabel: formatEventDuration(event.durationMs),
		summary: text(event.summary),
		errorType: text(event.errorType),
		isModelEvent: type === 'MODEL_COMPLETED' || type === 'MODEL_FAILED',
		isModelFailure: type === 'MODEL_FAILED',
		isFallback: type === 'FALLBACK_USED',
	};
}

export function buildTimelineEventViews(events: SupplyChainTaskEvent[]): SupplyChainTimelineEvent[] {
	return sortEventsBySequence(events).map(toTimelineEventView);
}

export function summarizeTimelineSignals(events: SupplyChainTaskEvent[]): SupplyChainTimelineSignals {
	return {
		hasModelCompleted: events.some((event) => event.type === 'MODEL_COMPLETED'),
		hasModelFailed: events.some((event) => event.type === 'MODEL_FAILED'),
		hasFallback: events.some((event) => event.type === 'FALLBACK_USED'),
	};
}
