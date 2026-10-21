/**
 * Semantic model API services. Mirrors SemanticModelController.
 */

import { api, apiBaseUrl, assertSuccess, unwrap, type ApiEnvelope } from './client';

export type SemanticModel = {
	id?: number;
	agentId: number;
	datasourceId?: number;
	tableName: string;
	columnName: string;
	businessName: string;
	synonyms?: string;
	businessDescription?: string;
	columnComment?: string;
	dataType: string;
	status: number; // 0 disabled / 1 enabled
	createdTime?: string;
	updateTime?: string;
	promptInfo?: string;
};

export type SemanticModelInput = {
	agentId: number;
	tableName: string;
	columnName: string;
	businessName: string;
	dataType: string;
	synonyms?: string;
	businessDescription?: string;
	columnComment?: string;
};

export type SemanticModelImportItem = {
	tableName: string;
	columnName: string;
	businessName: string;
	dataType: string;
	synonyms?: string;
	businessDescription?: string;
	columnComment?: string;
	createTime?: string;
};

export type BatchImportResult = {
	total: number;
	successCount: number;
	failCount: number;
	errors: string[];
};

/** keyword wins over agentId server-side (global search), exactly as implemented by the backend. */
export async function fetchSemanticModels(agentId?: number, keyword?: string): Promise<SemanticModel[]> {
	const params = new URLSearchParams();
	if (agentId !== undefined && agentId > 0) params.set('agentId', String(agentId));
	if (keyword) params.set('keyword', keyword);
	const qs = params.toString();
	const response = await api.get<ApiEnvelope<SemanticModel[]>>(`/api/semantic-model${qs ? `?${qs}` : ''}`);
	return unwrap(response, '加载语义模型失败') ?? [];
}

export async function createSemanticModel(input: SemanticModelInput): Promise<void> {
	const response = await api.post<ApiEnvelope<boolean>>('/api/semantic-model', input);
	assertSuccess(response, '创建语义模型失败');
}

export async function updateSemanticModel(id: number, input: Partial<SemanticModel>): Promise<void> {
	const response = await api.put<ApiEnvelope<SemanticModel>>(`/api/semantic-model/${id}`, input);
	assertSuccess(response, '更新语义模型失败');
}

export async function deleteSemanticModel(id: number): Promise<void> {
	const response = await api.delete<ApiEnvelope<boolean>>(`/api/semantic-model/${id}`);
	assertSuccess(response, '删除语义模型失败');
}

export async function batchDeleteSemanticModels(ids: number[]): Promise<void> {
	const response = await api.delete<ApiEnvelope<boolean>>('/api/semantic-model/batch', { body: ids });
	assertSuccess(response, '批量删除失败');
}

export async function enableSemanticModels(ids: number[]): Promise<void> {
	const response = await api.put<ApiEnvelope<boolean>>('/api/semantic-model/enable', ids);
	assertSuccess(response, '启用失败');
}

export async function disableSemanticModels(ids: number[]): Promise<void> {
	const response = await api.put<ApiEnvelope<boolean>>('/api/semantic-model/disable', ids);
	assertSuccess(response, '停用失败');
}

export async function batchImportSemanticModels(agentId: number, items: SemanticModelImportItem[]): Promise<BatchImportResult> {
	const response = await api.post<ApiEnvelope<BatchImportResult>>('/api/semantic-model/batch-import', { agentId, items });
	return unwrap(response, '批量导入失败');
}

export async function importSemanticModelExcel(agentId: number, file: File): Promise<BatchImportResult> {
	const form = new FormData();
	form.append('file', file);
	form.append('agentId', String(agentId));
	const response = await api.postForm<ApiEnvelope<BatchImportResult>>('/api/semantic-model/import/excel', form, {
		timeoutMs: 120_000,
	});
	return unwrap(response, 'Excel 导入失败');
}

/** Downloads the template through the same-origin proxy and triggers a browser download. */
export async function downloadSemanticModelTemplate(): Promise<void> {
	const response = await api.raw('/api/semantic-model/template/download');
	if (!response.ok) throw new Error(`模板下载失败（HTTP ${response.status}）`);
	const blob = await response.blob();
	const url = URL.createObjectURL(blob);
	const link = document.createElement('a');
	link.href = url;
	link.download = 'semantic_model_template.xlsx';
	document.body.appendChild(link);
	link.click();
	link.remove();
	URL.revokeObjectURL(url);
}

export { apiBaseUrl };
