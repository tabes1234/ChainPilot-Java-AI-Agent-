/**
 * Chat session / message / streaming API services. Mirrors ChatController, SessionEventController, GraphController.
 */

import { api, assertSuccess } from './client';

export type ChatSession = {
	id: string;
	agentId?: number;
	title?: string;
	status?: string;
	isPinned?: boolean;
	userId?: number;
	createTime?: string;
	updateTime?: string;
};

export type ChatMessage = {
	id?: number;
	sessionId?: string;
	role: string;
	content: string;
	messageType?: string;
	metadata?: string | null;
	createTime?: string;
};

export type StreamEvent = {
	agentId?: number;
	threadId?: string;
	eventType?: 'NODE_OUTPUT' | 'FINAL_ANSWER' | 'HUMAN_FEEDBACK_REQUIRED' | string;
	stepId?: string;
	attempt?: number;
	nodeName?: string;
	textType?: 'JSON' | 'PYTHON' | 'SQL' | 'HTML' | 'MARK_DOWN' | 'RESULT_SET' | 'TEXT' | string;
	text?: string;
	error?: string;
	complete?: boolean;
};

export async function fetchChatSessions(agentId: number): Promise<ChatSession[]> {
	return api.get<ChatSession[]>(`/api/agent/${agentId}/sessions`);
}

export async function createChatSession(agentId: number, title?: string): Promise<ChatSession> {
	return api.post<ChatSession>(`/api/agent/${agentId}/sessions`, { title });
}

export async function clearChatSessions(agentId: number): Promise<void> {
	assertSuccess(await api.delete<{ success: boolean; message?: string }>(`/api/agent/${agentId}/sessions`), '清空会话失败');
}

export async function fetchChatMessages(sessionId: string): Promise<ChatMessage[]> {
	return api.get<ChatMessage[]>(`/api/sessions/${sessionId}/messages`);
}

export async function saveChatMessage(
	sessionId: string,
	message: { role: string; content: string; messageType?: string; metadata?: string; titleNeeded?: boolean },
): Promise<ChatMessage> {
	return api.post<ChatMessage>(`/api/sessions/${sessionId}/messages`, message);
}

export async function pinChatSession(sessionId: string, isPinned: boolean): Promise<void> {
	assertSuccess(
		await api.put<{ success: boolean; message?: string }>(`/api/sessions/${sessionId}/pin?isPinned=${isPinned}`),
		'更新置顶失败',
	);
}

export async function renameChatSession(sessionId: string, title: string): Promise<void> {
	assertSuccess(
		await api.put<{ success: boolean; message?: string }>(
			`/api/sessions/${sessionId}/rename?title=${encodeURIComponent(title)}`,
		),
		'重命名失败',
	);
}

export async function deleteChatSession(sessionId: string): Promise<void> {
	assertSuccess(await api.delete<{ success: boolean; message?: string }>(`/api/sessions/${sessionId}`), '删除会话失败');
}

export async function downloadChatReport(sessionId: string, html: string): Promise<void> {
	const response = await api.raw(`/api/sessions/${sessionId}/reports/html`, {
		method: 'POST',
		headers: { 'Content-Type': 'text/plain' },
		body: html,
	});
	if (!response.ok) throw new Error(`报告生成失败（HTTP ${response.status}）`);
	const blob = await response.blob();
	const url = URL.createObjectURL(blob);
	const link = document.createElement('a');
	link.href = url;
	link.download = `report_${Date.now()}.html`;
	document.body.appendChild(link);
	link.click();
	link.remove();
	URL.revokeObjectURL(url);
}

export type StreamParams = {
	agentId: number;
	conversationId: string;
	query: string;
	threadId?: string;
	humanFeedback?: boolean;
	humanFeedbackContent?: string;
	rejectedPlan?: boolean;
	nl2sqlOnly?: boolean;
};

export function buildStreamUrl(params: StreamParams): string {
	const search = new URLSearchParams({
		agentId: String(params.agentId),
		conversationId: params.conversationId,
		query: params.query,
	});
	if (params.threadId) search.set('threadId', params.threadId);
	if (params.humanFeedback !== undefined) search.set('humanFeedback', String(params.humanFeedback));
	if (params.humanFeedbackContent) search.set('humanFeedbackContent', params.humanFeedbackContent);
	if (params.rejectedPlan !== undefined) search.set('rejectedPlan', String(params.rejectedPlan));
	if (params.nl2sqlOnly !== undefined) search.set('nl2sqlOnly', String(params.nl2sqlOnly));
	return `/api/stream/search?${search.toString()}`;
}

export async function stopChatStream(conversationId: string, threadId?: string): Promise<void> {
	const search = new URLSearchParams({ conversationId });
	if (threadId) search.set('threadId', threadId);
	await api.post(`/api/stream/stop?${search.toString()}`);
}
