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
import type { DecisionProposal, DecisionTaskResult } from '../services/supplyChainDecision';
import { roleForStage, type ChainPilotRoleId } from './supplyChainAgentRoles';
import { EVENT_STAGE_LABELS, sortEventsBySequence } from './supplyChainEventTimeline';

export type WorkbenchTaskSource = 'ANALYSIS' | 'DECISION';

export type WorkbenchTask = {
	taskId: string;
	status: string;
	currentStep: string;
	question: string;
	source: WorkbenchTaskSource;
	updatedAt?: string;
	error?: string | null;
};

export type WorkbenchAnalysisReport = {
	taskId: string;
	status: string;
	summary: string;
	atRiskOrders: unknown[];
	citations: unknown[];
	modelAssisted: boolean;
	validationAttempts: number;
};

export type DecisionWorkspaceSnapshot = {
	task: {
		taskId: string;
		status: string;
		currentStep: string;
		validationAttempts: number;
		error: string | null;
		updatedAt?: string;
	} | null;
	question: string;
	running: boolean;
	decision: DecisionTaskResult | null;
	proposals: DecisionProposal[];
	error: string;
};

export type WorkbenchStageState = 'completed' | 'active' | 'upcoming' | 'failed';

export type WorkbenchStage = {
	key: string;
	label: string;
	state: WorkbenchStageState;
	role: ChainPilotRoleId;
	isRetry: boolean;
};

export type WorkbenchMetric = {
	label: string;
	value: string;
	role: ChainPilotRoleId;
};

type StageDefinition = {
	key: string;
	role: ChainPilotRoleId;
	isRetry?: boolean;
};

const ANALYSIS_STAGE_KEYS = new Set([
	'PLANNING',
	'QUERYING_ORDERS',
	'QUERYING_INVENTORY',
	'QUERYING_SUPPLIERS',
	'VALIDATING',
	'GENERATING_REPORT',
]);

const NODE_EVENT_TYPES = new Set(['NODE_STARTED', 'NODE_COMPLETED', 'NODE_FAILED']);

const ANALYSIS_PIPELINE: StageDefinition[] = [
	{ key: 'PLANNING', role: 'planning' },
	{ key: 'QUERYING_ORDERS', role: 'data' },
	{ key: 'QUERYING_INVENTORY', role: 'data' },
	{ key: 'QUERYING_SUPPLIERS', role: 'data' },
	{ key: 'VALIDATING', role: 'risk' },
	{ key: 'GENERATING_REPORT', role: 'planning' },
];

const DECISION_PIPELINE: StageDefinition[] = [
	{ key: 'DECISION_INTENT', role: 'planning' },
	{ key: 'DECISION_EXECUTION', role: 'planning' },
	{ key: 'VALIDATING_DECISION', role: 'risk' },
];

export function isWorkbenchTaskActive(task: Pick<WorkbenchTask, 'status'> | null | undefined): boolean {
	return task?.status === 'ACCEPTED' || task?.status === 'RUNNING';
}

function updatedAtMs(task: WorkbenchTask): number {
	const value = Date.parse(task.updatedAt || '');
	return Number.isFinite(value) ? value : 0;
}

export function pickWorkbenchFocus(
	analysisTask: WorkbenchTask | null,
	decisionTask: WorkbenchTask | null,
	preferredTaskId = '',
): WorkbenchTask | null {
	const candidates = [decisionTask, analysisTask].filter((task): task is WorkbenchTask => Boolean(task));
	const active = candidates.filter((task) => isWorkbenchTaskActive(task));
	if (active.length) {
		return [...active].sort((left, right) => updatedAtMs(right) - updatedAtMs(left))[0] ?? null;
	}
	if (preferredTaskId) {
		const preferred = candidates.find((task) => task.taskId === preferredTaskId);
		if (preferred) {
			return preferred;
		}
	}
	return [...candidates].sort((left, right) => updatedAtMs(right) - updatedAtMs(left))[0] ?? null;
}

export function workbenchStatusLabel(status: string): string {
	if (status === 'ACCEPTED') {
		return '排队中';
	}
	if (status === 'RUNNING') {
		return 'AI 正在工作';
	}
	if (status === 'COMPLETED') {
		return '已完成';
	}
	if (status === 'FAILED') {
		return '执行失败';
	}
	return status || '状态未知';
}

export function workbenchStatusTone(status: string): 'idle' | 'queued' | 'active' | 'success' | 'failure' {
	if (status === 'ACCEPTED') {
		return 'queued';
	}
	if (status === 'RUNNING') {
		return 'active';
	}
	if (status === 'COMPLETED') {
		return 'success';
	}
	if (status === 'FAILED') {
		return 'failure';
	}
	return 'idle';
}

export function workbenchStageStateLabel(state: WorkbenchStageState): string {
	if (state === 'completed') {
		return '已完成';
	}
	if (state === 'active') {
		return '进行中';
	}
	if (state === 'failed') {
		return '失败';
	}
	return '待执行';
}

function normalizeCurrentStage(stage: string): string {
	if (!stage) {
		return '';
	}
	if (stage === 'QUEUE' || stage === 'QUEUED') {
		return '';
	}
	if (stage === 'VALIDATION_FAILED') {
		return 'VALIDATING';
	}
	return stage;
}

export function buildWorkbenchStages(
	task: WorkbenchTask | null,
	events: SupplyChainTaskEvent[],
): WorkbenchStage[] {
	if (!task) {
		return [];
	}
	const sorted = sortEventsBySequence(events);
	const nodeEvents = sorted.filter((event) => NODE_EVENT_TYPES.has(event.type));
	const observedCompleted = new Set(
		nodeEvents.filter((event) => event.type === 'NODE_COMPLETED').map((event) => event.stage),
	);
	const observedFailed = new Set(
		nodeEvents.filter((event) => event.type === 'NODE_FAILED').map((event) => event.stage),
	);
	const normalizedStep = normalizeCurrentStage(task.currentStep);
	const observedAnalysisPath = nodeEvents.some((event) => ANALYSIS_STAGE_KEYS.has(event.stage));
	const currentStepIsAnalysis = ANALYSIS_STAGE_KEYS.has(normalizedStep);
	const analysisPath =
		task.source === 'ANALYSIS' ||
		(task.source === 'DECISION' && (observedAnalysisPath || currentStepIsAnalysis));
	const shouldShowRetry =
		nodeEvents.some((event) => event.stage === 'RETRYING_DATA_CHECK') ||
		task.currentStep === 'RETRYING_DATA_CHECK';

	let pipeline: StageDefinition[];
	if (task.source === 'ANALYSIS') {
		pipeline = [...ANALYSIS_PIPELINE];
	} else if (analysisPath) {
		pipeline = [{ key: 'DECISION_INTENT', role: 'planning' }, ...ANALYSIS_PIPELINE];
	} else {
		pipeline = [...DECISION_PIPELINE];
	}
	if (shouldShowRetry) {
		const validatingIndex = pipeline.findIndex((stage) => stage.key === 'VALIDATING');
		const insertAt = validatingIndex >= 0 ? validatingIndex + 1 : pipeline.length;
		pipeline.splice(insertAt, 0, { key: 'RETRYING_DATA_CHECK', role: 'risk', isRetry: true });
	}

	const activeKey = pipeline.some((stage) => stage.key === normalizedStep) ? normalizedStep : '';
	const activeIndex = pipeline.findIndex((stage) => stage.key === activeKey);
	const failedKey =
		task.status === 'FAILED'
			? activeKey || [...observedFailed][0] || ''
			: [...observedFailed][0] || '';
	const failedIndex = pipeline.findIndex((stage) => stage.key === failedKey);

	return pipeline.map((stage, index) => {
		let state: WorkbenchStageState = 'upcoming';
		if (task.status === 'COMPLETED') {
			state = 'completed';
		} else if (failedIndex >= 0) {
			if (index < failedIndex) {
				state = 'completed';
			} else if (index === failedIndex) {
				state = 'failed';
			}
		} else {
			if (observedCompleted.has(stage.key) && index !== activeIndex) {
				state = 'completed';
			}
			if (index === activeIndex) {
				state = 'active';
			} else if (activeIndex >= 0 && index < activeIndex) {
				state = 'completed';
			}
		}
		return {
			key: stage.key,
			label: EVENT_STAGE_LABELS[stage.key] || stage.key,
			state,
			role: stage.role || roleForStage(stage.key),
			isRetry: stage.isRetry === true,
		};
	});
}

function signed(value: number | null | undefined): string {
	if (value === null || value === undefined) {
		return '—';
	}
	return value > 0 ? `+${value}` : String(value);
}

export function countPendingProposals(proposals: DecisionProposal[]): number {
	return proposals.filter((proposal) => proposal.status === 'PENDING').length;
}

export function countConfirmedProposals(proposals: DecisionProposal[]): number {
	return proposals.filter((proposal) => proposal.status === 'CONFIRMED').length;
}

export function buildDecisionOutcomeMetrics(
	decision: DecisionTaskResult | null,
	proposals: DecisionProposal[] = [],
): WorkbenchMetric[] {
	if (!decision) {
		return [];
	}
	const metrics: WorkbenchMetric[] = [];
	if (decision.comparison) {
		metrics.push(
			{
				label: '候选方案',
				value: `${decision.comparison.candidates.length} 个`,
				role: 'simulation',
			},
			{
				label: '确定性推荐',
				value: decision.comparison.recommendation.recommendedLabel || '—',
				role: 'simulation',
			},
			{
				label: '推荐排名',
				value:
					decision.comparison.recommendation.recommendedRank === null
						? '不可比'
						: `#${decision.comparison.recommendation.recommendedRank}`,
				role: 'simulation',
			},
			{
				label: '基线风险订单',
				value: String(decision.comparison.baseline.atRiskOrders),
				role: 'risk',
			},
		);
	} else if (decision.simulation) {
		const simulation = decision.simulation;
		metrics.push(
			{
				label: '模拟对象',
				value: simulation.sku || simulation.supplierId || '—',
				role: 'data',
			},
			{
				label: '模拟参数',
				value:
					simulation.replenishmentQuantity !== null && simulation.replenishmentQuantity !== undefined
						? `${simulation.replenishmentQuantity} 件`
						: `${simulation.additionalDelayDays ?? '—'} 天`,
				role: 'simulation',
			},
			{
				label: '风险订单变化',
				value: `${simulation.baseline.atRiskOrders} → ${simulation.simulated.atRiskOrders}（${signed(simulation.delta.atRiskOrders)}）`,
				role: 'risk',
			},
			{
				label: '目标 ATP 变化',
				value: signed(simulation.delta.targetSkuAvailableToPromise),
				role: 'data',
			},
		);
	}
	if (decision.atRiskOrders.length) {
		metrics.push({
			label: '风险订单证据',
			value: `${decision.atRiskOrders.length} 个`,
			role: 'risk',
		});
	}
	if (decision.recommendations.length) {
		metrics.push({
			label: '处置建议',
			value: `${decision.recommendations.length} 条`,
			role: 'risk',
		});
	}
	if (decision.plan) {
		metrics.push({
			label: '关联处置方案',
			value: `${decision.plan.planId} · ${decision.plan.status}`,
			role: 'planning',
		});
	}
	if (decision.execution) {
		metrics.push({
			label: '沙箱演练',
			value: `${decision.execution.status} · ${decision.execution.steps.length} 步`,
			role: 'execution',
		});
	}
	const pendingCount = countPendingProposals(proposals);
	if (pendingCount) {
		metrics.push({
			label: '待确认提案',
			value: `${pendingCount} 个`,
			role: 'confirmation',
		});
	} else {
		const confirmedCount = countConfirmedProposals(proposals);
		if (confirmedCount) {
			metrics.push({
				label: '已确认提案',
				value: `${confirmedCount} 个`,
				role: 'confirmation',
			});
		}
	}
	return metrics;
}

export function buildAnalysisOutcomeMetrics(
	report: WorkbenchAnalysisReport | null,
): WorkbenchMetric[] {
	if (!report) {
		return [];
	}
	return [
		{ label: '风险订单', value: `${report.atRiskOrders.length} 个`, role: 'risk' },
		{ label: 'SOP 引用', value: `${report.citations.length} 条`, role: 'data' },
		{
			label: '执行方式',
			value: report.modelAssisted ? '模型辅助' : '确定性规则',
			role: 'planning',
		},
		{ label: '校验重试', value: `${report.validationAttempts} 次`, role: 'system' },
	];
}

export function latestNodeLabel(events: SupplyChainTaskEvent[]): string {
	const completed = sortEventsBySequence(events)
		.filter((event) => event.type === 'NODE_COMPLETED')
		.slice(-1)[0];
	return completed ? EVENT_STAGE_LABELS[completed.stage] || completed.stage : '';
}
