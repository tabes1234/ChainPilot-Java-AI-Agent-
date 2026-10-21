/**
 * Agent management API services. Mirrors AgentController / AgentPresetQuestionController / FileUploadController.
 */

import { api, unwrap } from './client';

export type AgentStatus = 'draft' | 'published' | 'offline';

export type Agent = {
	id?: number;
	name?: string;
	description?: string;
	avatar?: string;
	status: AgentStatus;
	apiKey?: string | null;
	apiKeyEnabled?: number;
	prompt?: string;
	category?: string;
	adminId?: number;
	tags?: string;
	createTime?: string;
	updateTime?: string;
};

export type AgentInput = {
	name: string;
	description?: string;
	avatar?: string;
	status: AgentStatus;
	prompt?: string;
	category?: string;
	tags?: string;
	apiKeyEnabled?: number;
};

export type ApiKeyInfo = {
	apiKey: string | null;
	apiKeyEnabled: number;
};

export type PresetQuestion = {
	id?: number;
	agentId?: number;
	question: string;
	sortOrder?: number;
	isActive?: boolean;
	createTime?: string;
	updateTime?: string;
};

export type UploadResponse = {
	success: boolean;
	message?: string;
	url?: string;
	filename?: string;
};

export async function fetchAgents(status?: string, keyword?: string): Promise<Agent[]> {
	const params = new URLSearchParams();
	if (status) params.set('status', status);
	if (keyword) params.set('keyword', keyword);
	const qs = params.toString();
	return api.get<Agent[]>(`/api/agent/list${qs ? `?${qs}` : ''}`);
}

export async function fetchAgentById(id: number): Promise<Agent> {
	return api.get<Agent>(`/api/agent/${id}`);
}

export async function createAgent(input: AgentInput): Promise<Agent> {
	return api.post<Agent>('/api/agent', input);
}

export async function updateAgent(id: number, patch: Partial<Agent>): Promise<Agent> {
	return api.put<Agent>(`/api/agent/${id}`, patch);
}

export async function deleteAgent(id: number): Promise<void> {
	await api.delete(`/api/agent/${id}`);
}

export async function publishAgent(id: number): Promise<Agent> {
	return api.post<Agent>(`/api/agent/${id}/publish`);
}

export async function offlineAgent(id: number): Promise<Agent> {
	return api.post<Agent>(`/api/agent/${id}/offline`);
}

export async function fetchApiKey(id: number): Promise<ApiKeyInfo> {
	const response = await api.get<{ success: boolean; message?: string; data?: ApiKeyInfo }>(`/api/agent/${id}/api-key`);
	return unwrap(response, '获取 API Key 失败');
}

/** Returns the plaintext key — it is only ever available from generate/reset responses. */
export async function generateApiKey(id: number, reset = false): Promise<ApiKeyInfo> {
	const response = await api.post<{ success: boolean; message?: string; data?: ApiKeyInfo }>(
		`/api/agent/${id}/api-key/${reset ? 'reset' : 'generate'}`,
	);
	return unwrap(response, reset ? '重置 API Key 失败' : '生成 API Key 失败');
}

export async function deleteApiKey(id: number): Promise<ApiKeyInfo> {
	const response = await api.delete<{ success: boolean; message?: string; data?: ApiKeyInfo }>(`/api/agent/${id}/api-key`);
	return unwrap(response, '删除 API Key 失败');
}

export async function setApiKeyEnabled(id: number, enabled: boolean): Promise<ApiKeyInfo> {
	const response = await api.post<{ success: boolean; message?: string; data?: ApiKeyInfo }>(
		`/api/agent/${id}/api-key/enable?enabled=${enabled}`,
	);
	return unwrap(response, '更新 API Key 状态失败');
}

export async function fetchPresetQuestions(agentId: number): Promise<PresetQuestion[]> {
	return api.get<PresetQuestion[]>(`/api/agent/${agentId}/preset-questions`);
}

export async function savePresetQuestions(agentId: number, questions: { question: string; isActive?: boolean }[]): Promise<void> {
	const response = await api.post<{ message?: string; error?: string }>(`/api/agent/${agentId}/preset-questions`, questions);
	if (response?.error) throw new Error(response.error);
}

export async function deletePresetQuestion(agentId: number, questionId: number): Promise<void> {
	const response = await api.delete<{ message?: string; error?: string }>(
		`/api/agent/${agentId}/preset-questions/${questionId}`,
	);
	if (response?.error) throw new Error(response.error);
}

export async function uploadAvatar(file: File): Promise<UploadResponse> {
	const form = new FormData();
	form.append('file', file);
	const response = await api.postForm<UploadResponse>('/api/upload/avatar', form, { timeoutMs: 60_000 });
	if (!response?.success) throw new Error(response?.message || '头像上传失败');
	return response;
}

/**
 * The backend entity defaults apiKeyEnabled to 0, so an update that omits it silently disables
 * existing API access. Every profile edit must carry the known state forward.
 */
export function buildAgentUpdatePayload(
	current: Pick<Agent, 'apiKeyEnabled'>,
	patch: Partial<Agent>,
): Partial<Agent> {
	// Never forward a plaintext key; the backend has dedicated generate/reset endpoints.
	const safePatch: Partial<Agent> = { ...patch };
	delete safePatch.apiKey;
	return { ...safePatch, apiKeyEnabled: safePatch.apiKeyEnabled ?? current.apiKeyEnabled ?? 0 };
}
