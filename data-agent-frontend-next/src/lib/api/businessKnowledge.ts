/**
 * Business knowledge (业务术语) API services. Mirrors BusinessKnowledgeController.
 */

import { api, assertSuccess, unwrap, type ApiEnvelope } from './client';

export type BusinessKnowledge = {
	id?: number;
	agentId?: number;
	businessTerm: string;
	description: string;
	synonyms?: string;
	isRecall?: boolean;
	embeddingStatus?: string;
	errorMsg?: string | null;
	createdTime?: string;
	updatedTime?: string;
};

export type BusinessKnowledgeInput = {
	agentId: number;
	businessTerm: string;
	description: string;
	synonyms?: string;
	isRecall?: boolean;
};

export async function fetchBusinessKnowledge(agentId: number, keyword?: string): Promise<BusinessKnowledge[]> {
	const params = new URLSearchParams({ agentId: String(agentId) });
	if (keyword) params.set('keyword', keyword);
	const response = await api.get<ApiEnvelope<BusinessKnowledge[]>>(`/api/business-knowledge?${params.toString()}`);
	return unwrap(response, '加载业务术语失败') ?? [];
}

export async function createBusinessKnowledge(input: BusinessKnowledgeInput): Promise<BusinessKnowledge> {
	const response = await api.post<ApiEnvelope<BusinessKnowledge>>('/api/business-knowledge', input, { timeoutMs: 60_000 });
	return unwrap(response, '创建业务术语失败');
}

export async function updateBusinessKnowledge(
	id: number,
	input: { agentId: number; businessTerm: string; description: string; synonyms?: string },
): Promise<BusinessKnowledge> {
	const response = await api.put<ApiEnvelope<BusinessKnowledge>>(`/api/business-knowledge/${id}`, input, {
		timeoutMs: 60_000,
	});
	return unwrap(response, '更新业务术语失败');
}

export async function deleteBusinessKnowledge(id: number): Promise<void> {
	const response = await api.delete<ApiEnvelope<unknown>>(`/api/business-knowledge/${id}`);
	assertSuccess(response, '删除业务术语失败');
}

export async function setBusinessKnowledgeRecall(id: number, isRecall: boolean): Promise<void> {
	const response = await api.post<ApiEnvelope<boolean>>(`/api/business-knowledge/recall/${id}?isRecall=${isRecall}`);
	assertSuccess(response, '更新召回状态失败');
}

export async function refreshBusinessKnowledgeVectorStore(agentId: number): Promise<void> {
	const response = await api.post<ApiEnvelope<boolean>>(
		`/api/business-knowledge/refresh-vector-store?agentId=${agentId}`,
		undefined,
		{ timeoutMs: 120_000 },
	);
	assertSuccess(response, '同步向量库失败');
}

export async function retryBusinessKnowledgeEmbedding(id: number): Promise<void> {
	const response = await api.post<ApiEnvelope<boolean>>(`/api/business-knowledge/retry-embedding/${id}`, undefined, {
		timeoutMs: 60_000,
	});
	assertSuccess(response, '重试向量化失败');
}
