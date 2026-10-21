/**
 * Model configuration API services. Mirrors ModelConfigController.
 * Secrets (apiKey / proxyPassword) are only ever sent to the backend; list responses are masked.
 */

import { api, assertSuccess, unwrap } from './client';

export type ModelType = 'CHAT' | 'EMBEDDING';

export type ModelConfig = {
	id?: number;
	provider: string;
	apiKey?: string;
	baseUrl: string;
	modelName: string;
	modelType: ModelType;
	completionsPath?: string;
	embeddingsPath?: string;
	temperature?: number;
	maxTokens?: number;
	isActive?: boolean;
	proxyEnabled?: boolean;
	proxyHost?: string;
	proxyPort?: number;
	proxyUsername?: string;
	proxyPassword?: string;
	createTime?: string;
	updateTime?: string;
};

export type ModelConfigInput = Omit<ModelConfig, 'id' | 'createTime' | 'updateTime'> & { id?: number };

export type ModelReadinessState = 'NOT_CONFIGURED' | 'CONFIGURED_UNVERIFIED' | 'TEST_FAILED' | 'VERIFIED';

export type ModelReadiness = {
	chatModelReady: boolean;
	embeddingModelReady: boolean;
	ready: boolean;
	chatConnectionVerified: boolean;
	embeddingConnectionVerified: boolean;
	chatState: ModelReadinessState;
	embeddingState: ModelReadinessState;
	chatProvider?: string | null;
	chatModelName?: string | null;
	chatBaseUrl?: string | null;
	embeddingProvider?: string | null;
	embeddingModelName?: string | null;
	embeddingBaseUrl?: string | null;
	chatTestedAt?: string | null;
	embeddingTestedAt?: string | null;
	chatTestError?: string | null;
	embeddingTestError?: string | null;
	embeddingRequiredForAgentEvaluation: boolean;
	embeddingDependencyNote?: string | null;
	agentEvaluationReady: boolean;
	agentEvaluationBlockReason?: string | null;
	verificationScope: string;
};

export const MODEL_READINESS_STATE_LABELS: Record<ModelReadinessState, string> = {
	NOT_CONFIGURED: '未配置',
	CONFIGURED_UNVERIFIED: '未测试',
	TEST_FAILED: '测试失败',
	VERIFIED: '连接已验证',
};

export function readinessStateLabel(state?: string | null): string {
	return MODEL_READINESS_STATE_LABELS[state as ModelReadinessState] ?? state ?? '未知';
}

export async function fetchModelConfigs(): Promise<ModelConfig[]> {
	const response = await api.get<{ success: boolean; message?: string; data?: ModelConfig[] }>('/api/model-config/list');
	return unwrap(response, '加载模型配置失败') ?? [];
}

export async function addModelConfig(input: ModelConfigInput): Promise<void> {
	assertSuccess(await api.post<{ success: boolean; message?: string }>('/api/model-config/add', input), '保存模型配置失败');
}

export async function updateModelConfig(input: ModelConfigInput & { id: number }): Promise<void> {
	assertSuccess(await api.put<{ success: boolean; message?: string }>('/api/model-config/update', input), '更新模型配置失败');
}

export async function deleteModelConfig(id: number): Promise<void> {
	assertSuccess(await api.delete<{ success: boolean; message?: string }>(`/api/model-config/${id}`), '删除模型配置失败');
}

export async function activateModelConfig(id: number): Promise<void> {
	assertSuccess(await api.post<{ success: boolean; message?: string }>(`/api/model-config/activate/${id}`), '切换模型失败');
}

/** Performs a real provider call with the stored config; the message may contain a provider error. */
export async function testModelConfig(id: number): Promise<string> {
	const response = await api.post<{ success: boolean; message?: string; code?: string | null }>(`/api/model-config/test/${id}`, undefined, {
		timeoutMs: 120_000,
	});
	if (response.success !== true) {
		const code = response.code ? `（${response.code}）` : '';
		throw new Error(`${response.message || '连接测试失败'}${code}`);
	}
	return response.message || '连接测试成功';
}

export async function fetchModelReadiness(): Promise<ModelReadiness> {
	const response = await api.get<{ success: boolean; message?: string; data?: ModelReadiness }>('/api/model-config/check-ready');
	return unwrap(response, '读取模型就绪状态失败');
}
