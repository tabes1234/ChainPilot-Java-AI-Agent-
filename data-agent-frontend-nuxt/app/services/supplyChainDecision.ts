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

import {
	type DisposalPlan,
	type DisposalRecommendation,
	type DisposalScenarioComparison,
	type DisposalSimulation,
	type KnowledgeCitation,
	type SandboxExecution,
	type SandboxExecutionEligibility,
} from './supplyChainDisposal';
import {
	fetchSupplyChainHistory,
	type SupplyChainHistoryPage,
	type SupplyChainHistoryStatus,
	type SupplyChainTaskStatus,
	type SupplyChainTaskSummary,
} from './supplyChainHistory';

export type DecisionIntent =
	| 'ANALYSIS'
	| 'DISPOSAL_RECOMMENDATIONS'
	| 'COMPARE_REPLENISHMENT'
	| 'COMPARE_SUPPLIER_DELAY'
	| 'SIMULATE_REPLENISHMENT'
	| 'SIMULATE_SUPPLIER_DELAY'
	| 'LOOKUP_COMPARISON'
	| 'LOOKUP_PLAN'
	| 'LOOKUP_EXECUTION'
	| 'PROPOSE_SAVE_PLAN'
	| 'PROPOSE_LINK_PLAN'
	| 'PROPOSE_SELECT_PLAN'
	| 'PROPOSE_START_SANDBOX'
	| 'UNSUPPORTED';

export type DecisionNarrativeSource = 'MODEL_GROUNDED' | 'DETERMINISTIC' | 'DETERMINISTIC_FALLBACK';

export type ProposalActionType =
	| 'SAVE_CANDIDATE_AS_PLAN'
	| 'LINK_CANDIDATE_TO_PLAN'
	| 'SELECT_PLAN'
	| 'START_SANDBOX_EXECUTION';

export type ProposalStatus = 'PENDING' | 'EXECUTING' | 'CONFIRMED' | 'CANCELLED' | 'EXPIRED' | 'FAILED';

export type DecisionProposal = {
	proposalId: string;
	taskId: string;
	actionType: ProposalActionType;
	title: string;
	targetId: string;
	parameters: Record<string, unknown>;
	evidence: string[];
	contentHash: string;
	status: ProposalStatus;
	createdAt: string;
	expiresAt: string;
	confirmedAt: string | null;
	result: Record<string, unknown> | null;
	errorMessage: string | null;
};

export type DecisionOrderRisk = {
	order: { orderId: string; sku: string; requestedDeliveryDate: string; estimatedDeliveryDate: string };
	riskLevel: 'LOW' | 'MEDIUM' | 'HIGH';
	riskScore: number;
	inventoryShortage: number;
	riskFactors: string[];
	recommendedActions: string[];
};

export type DecisionTaskResult = {
	taskId: string;
	question: string;
	intent: DecisionIntent;
	status: string;
	summary: string;
	narrativeSource: DecisionNarrativeSource;
	modelAssisted: boolean;
	fallbackUsed: boolean;
	clarificationRequired: boolean;
	clarifications: string[];
	toolCalls: string[];
	executionPlan: string[];
	plannerAssisted: boolean;
	atRiskOrders: DecisionOrderRisk[];
	recommendationNotes: string[];
	recommendations: DisposalRecommendation[];
	simulation: DisposalSimulation | null;
	comparison: DisposalScenarioComparison | null;
	plan: DisposalPlan | null;
	execution: SandboxExecution | null;
	sandboxEligibility: SandboxExecutionEligibility | null;
	proposals: DecisionProposal[];
	citations: KnowledgeCitation[];
	evidenceNotes: string[];
	realDataMutated: boolean;
	createdAt: string;
};

export type DecisionHistoryQuery = {
	status: SupplyChainHistoryStatus;
	page: number;
	pageSize: number;
};

export const DECISION_BASE_ENDPOINT = '/api/supply-chain';

export const DEFAULT_DECISION_HISTORY_PAGE_SIZE = 5;

export const DECISION_HISTORY_STATUS_OPTIONS: { title: string; value: SupplyChainHistoryStatus }[] = [
	{ title: '全部状态', value: 'ALL' },
	{ title: '执行中', value: 'RUNNING' },
	{ title: '已完成', value: 'COMPLETED' },
	{ title: '失败', value: 'FAILED' },
];

const INTENT_LABELS: Record<DecisionIntent, string> = {
	ANALYSIS: '风险分析',
	DISPOSAL_RECOMMENDATIONS: '处置建议查询',
	COMPARE_REPLENISHMENT: '补货方案对比',
	COMPARE_SUPPLIER_DELAY: '供应商延期对比',
	SIMULATE_REPLENISHMENT: '补货情景模拟',
	SIMULATE_SUPPLIER_DELAY: '供应商延期模拟',
	LOOKUP_COMPARISON: '查询方案对比',
	LOOKUP_PLAN: '查询处置方案',
	LOOKUP_EXECUTION: '查询沙箱演练',
	PROPOSE_SAVE_PLAN: '建议保存方案',
	PROPOSE_LINK_PLAN: '建议关联方案',
	PROPOSE_SELECT_PLAN: '建议选定方案',
	PROPOSE_START_SANDBOX: '建议启动沙箱演练',
	UNSUPPORTED: '未识别的请求',
};

const ACTION_LABELS: Record<ProposalActionType, string> = {
	SAVE_CANDIDATE_AS_PLAN: '保存候选为处置方案',
	LINK_CANDIDATE_TO_PLAN: '关联候选到处置方案',
	SELECT_PLAN: '选定处置方案',
	START_SANDBOX_EXECUTION: '启动沙箱演练（仅沙箱）',
};

const STATUS_LABELS: Record<ProposalStatus, string> = {
	PENDING: '待确认',
	EXECUTING: '执行中',
	CONFIRMED: '已确认并执行',
	CANCELLED: '已取消',
	EXPIRED: '已过期',
	FAILED: '执行失败',
};

export function decisionIntentLabel(intent: string): string {
	return INTENT_LABELS[intent as DecisionIntent] || intent || '未知意图';
}

export function proposalActionLabel(actionType: string): string {
	return ACTION_LABELS[actionType as ProposalActionType] || actionType || '未知操作';
}

export function proposalStatusLabel(status: string): string {
	return STATUS_LABELS[status as ProposalStatus] || status || '未知状态';
}

export function proposalStatusColor(status: string): string {
	if (status === 'CONFIRMED') {
		return 'success';
	}
	if (status === 'FAILED' || status === 'EXPIRED') {
		return 'error';
	}
	if (status === 'PENDING' || status === 'EXECUTING') {
		return 'warning';
	}
	return 'default';
}

export function narrativeSourceLabel(source: string): string {
	if (source === 'MODEL_GROUNDED') {
		return '模型解释 · 基于真实工具结果';
	}
	if (source === 'DETERMINISTIC') {
		return '规则摘要 · 未调用模型';
	}
	if (source === 'DETERMINISTIC_FALLBACK') {
		return '规则降级 · 模型不可用';
	}
	return source || '来源未知';
}

export function narrativeSourceColor(source: string): string {
	if (source === 'MODEL_GROUNDED') {
		return 'success';
	}
	if (source === 'DETERMINISTIC_FALLBACK') {
		return 'warning';
	}
	return 'info';
}

export function isProposalExpired(proposal: DecisionProposal): boolean {
	if (proposal.status !== 'PENDING' && proposal.status !== 'EXECUTING') {
		return false;
	}
	const expiresAt = new Date(proposal.expiresAt).getTime();
	return Number.isFinite(expiresAt) && expiresAt <= Date.now();
}

/**
 * Freezes the exact content the user is looking at. The server recomputes the SHA-256 over
 * action, target and parameters; the client additionally refuses to submit a hash that no
 * longer matches the rendered proposal.
 */
export function bindDecisionProposalContentHash(proposal: DecisionProposal): Readonly<DecisionProposal> {
	if (!proposal.contentHash?.trim()) {
		throw new Error('提案缺少内容哈希，不能确认或取消。');
	}
	return Object.freeze({
		...proposal,
		parameters: Object.freeze({ ...proposal.parameters }),
		evidence: Object.freeze([...proposal.evidence]),
	});
}

function assertBoundProposal(
	proposalId: string,
	contentHash: string,
	bound?: Readonly<DecisionProposal>,
): void {
	if (!bound) {
		return;
	}
	if (bound.proposalId !== proposalId || bound.contentHash !== contentHash) {
		throw new Error('提案内容已变化，请刷新后重新确认。');
	}
}

export function parameterLabel(value: unknown): string {
	if (value === null || value === undefined || value === '') {
		return '—';
	}
	if (typeof value === 'object') {
		try {
			return JSON.stringify(value);
		} catch {
			return String(value);
		}
	}
	return String(value);
}

export async function submitDecisionTask(question: string): Promise<SupplyChainTaskStatus> {
	return $fetch<SupplyChainTaskStatus>(`${DECISION_BASE_ENDPOINT}/analysis/tasks`, {
		method: 'POST',
		body: { question: question.trim(), mode: 'DECISION' },
	});
}

export async function fetchDecisionTaskStatus(taskId: string): Promise<SupplyChainTaskStatus> {
	return $fetch<SupplyChainTaskStatus>(
		`${DECISION_BASE_ENDPOINT}/analysis/tasks/${encodeURIComponent(taskId)}`,
	);
}

export async function fetchDecisionTaskResult(taskId: string): Promise<DecisionTaskResult> {
	return $fetch<DecisionTaskResult>(
		`${DECISION_BASE_ENDPOINT}/analysis/tasks/${encodeURIComponent(taskId)}/decision`,
	);
}

export async function fetchDecisionProposals(taskId: string): Promise<DecisionProposal[]> {
	return $fetch<DecisionProposal[]>(`${DECISION_BASE_ENDPOINT}/action-proposals`, {
		query: { taskId },
	});
}

export async function fetchDecisionProposal(proposalId: string): Promise<DecisionProposal> {
	return $fetch<DecisionProposal>(
		`${DECISION_BASE_ENDPOINT}/action-proposals/${encodeURIComponent(proposalId)}`,
	);
}

export async function confirmDecisionProposal(
	proposalId: string,
	contentHash: string,
	bound?: Readonly<DecisionProposal>,
): Promise<DecisionProposal> {
	assertBoundProposal(proposalId, contentHash, bound);
	return $fetch<DecisionProposal>(
		`${DECISION_BASE_ENDPOINT}/action-proposals/${encodeURIComponent(proposalId)}/confirm`,
		{ method: 'POST', body: { contentHash } },
	);
}

export async function cancelDecisionProposal(
	proposalId: string,
	contentHash: string,
	bound?: Readonly<DecisionProposal>,
): Promise<DecisionProposal> {
	assertBoundProposal(proposalId, contentHash, bound);
	return $fetch<DecisionProposal>(
		`${DECISION_BASE_ENDPOINT}/action-proposals/${encodeURIComponent(proposalId)}/cancel`,
		{ method: 'POST', body: { contentHash } },
	);
}

export async function fetchDecisionHistory(query: DecisionHistoryQuery): Promise<SupplyChainHistoryPage> {
	return fetchSupplyChainHistory({
		status: query.status,
		page: query.page,
		pageSize: query.pageSize,
		mode: 'DECISION',
	});
}

export type { SupplyChainHistoryPage, SupplyChainTaskStatus, SupplyChainTaskSummary };
