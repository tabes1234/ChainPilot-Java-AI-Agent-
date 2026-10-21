/**
 * Prompt configuration API services. Mirrors PromptConfigController.
 */

import { api, assertSuccess, unwrap } from './client';

export type PromptConfig = {
	id: string;
	name: string;
	promptType: string;
	agentId?: number | null;
	optimizationPrompt?: string;
	systemPrompt?: string;
	enabled?: boolean;
	description?: string;
	creator?: string;
	priority?: number;
	displayOrder?: number;
	createTime?: string;
	updateTime?: string;
};

export type PromptConfigInput = {
	id?: string;
	name: string;
	promptType: string;
	agentId?: number | null;
	optimizationPrompt: string;
	enabled?: boolean;
	description?: string;
	priority?: number;
	displayOrder?: number;
};

type ListEnvelope = { success: boolean; message?: string; data?: PromptConfig[]; total?: number };
type ItemEnvelope = { success: boolean; message?: string; data?: PromptConfig };

export async function fetchPromptConfigsByType(promptType: string, agentId?: number | null): Promise<PromptConfig[]> {
	const params = new URLSearchParams();
	if (agentId !== undefined && agentId !== null) params.set('agentId', String(agentId));
	const qs = params.toString();
	const response = await api.get<ListEnvelope>(`/api/prompt-config/list-by-type/${encodeURIComponent(promptType)}${qs ? `?${qs}` : ''}`);
	return unwrap(response, '加载提示词配置失败') ?? [];
}

export async function fetchAllPromptConfigs(): Promise<PromptConfig[]> {
	const response = await api.get<ListEnvelope>('/api/prompt-config/list');
	return unwrap(response, '加载提示词配置失败') ?? [];
}

export async function fetchPromptTypes(): Promise<string[]> {
	const response = await api.get<{ success: boolean; data?: string[] }>('/api/prompt-config/types');
	return unwrap(response, '加载提示词类型失败') ?? [];
}

export async function fetchPromptConfig(id: string): Promise<PromptConfig> {
	const response = await api.get<ItemEnvelope>(`/api/prompt-config/${encodeURIComponent(id)}`);
	return unwrap(response, '提示词配置不存在');
}

export async function savePromptConfig(input: PromptConfigInput): Promise<PromptConfig> {
	const response = await api.post<ItemEnvelope>('/api/prompt-config/save', input);
	return unwrap(response, '保存提示词配置失败');
}

export async function deletePromptConfig(id: string): Promise<void> {
	const response = await api.delete<{ success: boolean; message?: string }>(`/api/prompt-config/${encodeURIComponent(id)}`);
	assertSuccess(response, '删除提示词配置失败');
}

export async function setPromptEnabled(id: string, enabled: boolean): Promise<void> {
	const response = await api.post<{ success: boolean; message?: string }>(
		`/api/prompt-config/${encodeURIComponent(id)}/${enabled ? 'enable' : 'disable'}`,
	);
	assertSuccess(response, enabled ? '启用失败' : '停用失败');
}

export async function batchSetPromptEnabled(ids: string[], enabled: boolean): Promise<void> {
	const response = await api.post<{ success: boolean; message?: string }>(
		`/api/prompt-config/${enabled ? 'batch-enable' : 'batch-disable'}`,
		ids,
	);
	assertSuccess(response, enabled ? '批量启用失败' : '批量停用失败');
}

export async function setPromptPriority(id: string, priority: number): Promise<void> {
	const response = await api.post<{ success: boolean; message?: string }>(
		`/api/prompt-config/${encodeURIComponent(id)}/priority`,
		{ priority },
	);
	assertSuccess(response, '更新优先级失败');
}

export async function setPromptDisplayOrder(id: string, displayOrder: number): Promise<void> {
	const response = await api.post<{ success: boolean; message?: string }>(
		`/api/prompt-config/${encodeURIComponent(id)}/display-order`,
		{ displayOrder },
	);
	assertSuccess(response, '更新展示顺序失败');
}
