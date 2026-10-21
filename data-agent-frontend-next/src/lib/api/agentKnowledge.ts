/**
 * Agent Knowledge API services.
 * Mirrors AgentKnowledgeController: paged query, multipart-only create, title/content update,
 * recall switch, soft delete and embedding retry. No JSON create endpoint exists on the backend.
 */

import { api } from './client';

export type KnowledgeType = 'DOCUMENT' | 'QA' | 'FAQ';

export type AgentKnowledge = {
	id?: number;
	agentId?: number;
	title?: string;
	content?: string;
	type?: KnowledgeType;
	question?: string;
	isRecall?: boolean;
	embeddingStatus?: string;
	errorMsg?: string;
	splitterType?: string;
	createdTime?: string;
	updatedTime?: string;
};

export type KnowledgePageQuery = {
	title?: string;
	type?: string;
	embeddingStatus?: string;
	pageNum: number;
	pageSize: number;
};

export type KnowledgePage = {
	items: AgentKnowledge[];
	total: number;
	pageNum: number;
	pageSize: number;
	totalPages: number;
};

export type KnowledgeCreateInput = {
	agentId: number;
	title: string;
	type: KnowledgeType;
	question?: string;
	content?: string;
	file?: File | null;
	splitterType?: string;
};

type Envelope<T> = {
	success: boolean;
	message?: string;
	data?: T | null;
};

type PageEnvelope = Envelope<AgentKnowledge[]> & {
	total?: number;
	pageNum?: number;
	pageSize?: number;
	totalPages?: number;
};

function unwrap<T>(response: Envelope<T>, fallbackMessage: string): T {
	if (!response || response.success !== true) {
		throw new Error(response?.message || fallbackMessage);
	}
	return response.data as T;
}

/** POST /api/agent-knowledge/query/page — the only list endpoint the backend exposes. */
export async function queryKnowledgePage(agentId: number, query: KnowledgePageQuery): Promise<KnowledgePage> {
	const response = await api.post<PageEnvelope>('/api/agent-knowledge/query/page', {
		agentId,
		title: query.title || undefined,
		type: query.type || undefined,
		embeddingStatus: query.embeddingStatus || undefined,
		pageNum: query.pageNum,
		pageSize: query.pageSize,
	});

	if (response.success !== true) {
		throw new Error(response.message || '加载知识列表失败');
	}

	return {
		items: response.data ?? [],
		total: response.total ?? 0,
		pageNum: response.pageNum ?? query.pageNum,
		pageSize: response.pageSize ?? query.pageSize,
		totalPages: response.totalPages ?? 0,
	};
}

export async function getKnowledgeById(id: number): Promise<AgentKnowledge> {
	const response = await api.get<Envelope<AgentKnowledge>>(`/api/agent-knowledge/${id}`);
	return unwrap(response, '查询知识详情失败');
}

/** POST /api/agent-knowledge/create — multipart/form-data only. */
export async function createAgentKnowledge(input: KnowledgeCreateInput): Promise<AgentKnowledge> {
	const title = input.title.trim();
	if (!title) throw new Error('知识标题不能为空');

	const form = new FormData();
	form.append('agentId', String(input.agentId));
	form.append('title', title);
	form.append('type', input.type);

	if (input.type === 'DOCUMENT') {
		if (!input.file) throw new Error('文档类型知识必须上传文件');
		form.append('file', input.file);
		if (input.splitterType) form.append('splitterType', input.splitterType);
	} else {
		const question = input.question?.trim();
		const content = input.content?.trim();
		if (!question) throw new Error('问答对 / 常见问题必须填写问题');
		if (!content) throw new Error('问答对 / 常见问题必须填写答案');
		form.append('question', question);
		form.append('content', content);
	}

	const response = await api.postForm<Envelope<AgentKnowledge>>('/api/agent-knowledge/create', form);
	return unwrap(response, '创建知识失败');
}

/** PUT /api/agent-knowledge/{id} — UpdateKnowledgeDTO only carries title and content. */
export async function updateAgentKnowledge(
	id: number,
	data: { title?: string; content?: string },
): Promise<AgentKnowledge> {
	const response = await api.put<Envelope<AgentKnowledge>>(`/api/agent-knowledge/${id}`, data);
	return unwrap(response, '更新知识失败');
}

/** PUT /api/agent-knowledge/recall/{id}?isRecall= */
export async function updateRecallStatus(id: number, isRecall: boolean): Promise<AgentKnowledge> {
	const response = await api.put<Envelope<AgentKnowledge>>(
		`/api/agent-knowledge/recall/${id}?isRecall=${isRecall}`,
	);
	return unwrap(response, '召回状态更新失败');
}

/** DELETE /api/agent-knowledge/{id} — soft delete, resources are cleaned asynchronously. */
export async function deleteAgentKnowledge(id: number): Promise<void> {
	const response = await api.delete<Envelope<boolean>>(`/api/agent-knowledge/${id}`);
	unwrap(response, '删除知识失败');
}

/** POST /api/agent-knowledge/retry-embedding/{id} */
export async function retryEmbedding(id: number): Promise<string> {
	const response = await api.post<Envelope<AgentKnowledge>>(`/api/agent-knowledge/retry-embedding/${id}`);
	if (response.success !== true) throw new Error(response.message || '重试向量化失败');
	return response.message || '重试请求已发送';
}
