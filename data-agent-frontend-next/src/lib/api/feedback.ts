import { api, unwrap } from './client';

export type FeedbackType = 'HELPFUL' | 'UNHELPFUL' | 'INACCURATE_RISK' | 'INSUFFICIENT_EVIDENCE'
	| 'INACTIONABLE' | 'INCONSISTENT' | 'OTHER';
export type FeedbackResultType = 'ANALYSIS' | 'DECISION';
export type FeedbackAssertionType = 'TASK_STATUS' | 'NARRATIVE_SOURCE' | 'AT_RISK_ORDER_COUNT'
	| 'HIGH_RISK_ORDER_COUNT' | 'DATA_GAP_COUNT' | 'RISK_SCORE' | 'RISK_LEVEL' | 'EVIDENCE_CODE'
	| 'DECISION_INTENT';

export type FeedbackAssertion = {
	id: string;
	type: FeedbackAssertionType;
	target?: string | null;
	expected: string;
	description?: string | null;
};

export type FeedbackOrderSnapshot = {
	orderId: string;
	sku?: string;
	riskScore: number;
	riskLevel: string;
	inventoryShortage: number;
	evidenceCodes: string[];
	dataGaps: string[];
};

export type FeedbackSnapshot = {
	taskId: string;
	resultType: FeedbackResultType;
	question: string;
	summary: string;
	narrativeSource: string;
	atRiskOrderCount: number;
	highRiskOrderCount: number;
	dataGapCount: number;
	orders: FeedbackOrderSnapshot[];
	dataGaps: string[];
	evidenceIds: string[];
};

export type FeedbackRecord = {
	feedbackId: string;
	taskId: string;
	resultType: FeedbackResultType;
	proposalId?: string | null;
	feedbackType: FeedbackType;
	comment?: string;
	narrativeSource?: string;
	resultFingerprint: string;
	evidenceIds: string[];
	snapshot: FeedbackSnapshot;
	createdAt?: string;
};

export type FeedbackDraft = {
	draftId: string;
	feedbackId: string;
	caseId?: string | null;
	status: 'DRAFT' | 'READY_FOR_REVIEW' | 'ENABLED' | string;
	name: string;
	expectedBehavior: string;
	assertions: FeedbackAssertion[];
	suggestedAssertions: FeedbackAssertion[];
	snapshot: FeedbackSnapshot;
	feedbackType: FeedbackType;
	createdAt?: string;
	updatedAt?: string;
	enabledAt?: string | null;
};

export type FeedbackStats = {
	total: number;
	drafts: number;
	readyForReview: number;
	enabledCases: number;
	byType: Record<string, number>;
	bySource: Record<string, number>;
	byResultType: Record<string, number>;
};

type Envelope<T> = { success: boolean; message?: string; data?: T };

export const FEEDBACK_TYPE_LABELS: Record<string, string> = {
	HELPFUL: '有帮助',
	UNHELPFUL: '无帮助',
	INACCURATE_RISK: '风险判断不准确',
	INSUFFICIENT_EVIDENCE: '证据或数据不足',
	INACTIONABLE: '建议不可执行',
	INCONSISTENT: '建议与业务事实不一致',
	OTHER: '其他问题',
};

export const ASSERTION_TYPE_LABELS: Record<string, string> = {
	TASK_STATUS: '任务状态',
	NARRATIVE_SOURCE: '叙述来源',
	AT_RISK_ORDER_COUNT: '风险订单数',
	HIGH_RISK_ORDER_COUNT: '高风险订单数',
	DATA_GAP_COUNT: '数据缺口数',
	RISK_SCORE: '订单风险分',
	RISK_LEVEL: '订单风险等级',
	EVIDENCE_CODE: '订单证据代码',
	DECISION_INTENT: '决策意图',
};

export async function submitFeedback(input: {
	taskId: string;
	proposalId?: string;
	feedbackType: FeedbackType;
	comment?: string;
}): Promise<FeedbackRecord> {
	const response = await api.post<Envelope<FeedbackRecord>>('/api/agent-evaluation/feedback', input);
	return unwrap(response, '提交反馈失败');
}

export async function fetchFeedbackPage(params: {
	page?: number;
	pageSize?: number;
	feedbackType?: string;
	resultType?: string;
	narrativeSource?: string;
	taskId?: string;
}): Promise<{ items: FeedbackRecord[]; page: number; pageSize: number; totalElements: number; totalPages: number }> {
	const search = new URLSearchParams();
	Object.entries(params).forEach(([key, value]) => {
		if (value !== undefined && value !== '') search.set(key, String(value));
	});
	const response = await api.get<Envelope<{ items: FeedbackRecord[]; page: number; pageSize: number; totalElements: number; totalPages: number }>>(
		`/api/agent-evaluation/feedback?${search.toString()}`,
	);
	return unwrap(response, '加载反馈失败');
}

export async function fetchFeedbackStats(): Promise<FeedbackStats> {
	const response = await api.get<Envelope<FeedbackStats>>('/api/agent-evaluation/feedback/stats');
	return unwrap(response, '加载反馈统计失败');
}

export async function createFeedbackDraft(feedbackId: string): Promise<FeedbackDraft> {
	const response = await api.post<Envelope<FeedbackDraft>>(`/api/agent-evaluation/feedback/${encodeURIComponent(feedbackId)}/drafts`);
	return unwrap(response, '创建案例草案失败');
}

export async function fetchFeedbackDrafts(page = 1, pageSize = 50): Promise<FeedbackDraft[]> {
	const response = await api.get<Envelope<FeedbackDraft[]>>(
		`/api/agent-evaluation/feedback/drafts?page=${page}&pageSize=${pageSize}`,
	);
	return unwrap(response, '加载案例草案失败') ?? [];
}

export async function updateFeedbackDraft(draftId: string, input: {
	expectedBehavior?: string;
	assertions?: FeedbackAssertion[];
}): Promise<FeedbackDraft> {
	const response = await api.put<Envelope<FeedbackDraft>>(
		`/api/agent-evaluation/feedback/drafts/${encodeURIComponent(draftId)}`,
		input,
	);
	return unwrap(response, '保存案例草案失败');
}

export async function reviewFeedbackDraft(draftId: string): Promise<FeedbackDraft> {
	const response = await api.post<Envelope<FeedbackDraft>>(
		`/api/agent-evaluation/feedback/drafts/${encodeURIComponent(draftId)}/review`,
	);
	return unwrap(response, '提交审核失败');
}

export async function enableFeedbackDraft(draftId: string): Promise<FeedbackDraft> {
	const response = await api.post<Envelope<FeedbackDraft>>(
		`/api/agent-evaluation/feedback/drafts/${encodeURIComponent(draftId)}/enable`,
	);
	return unwrap(response, '启用评测案例失败');
}
