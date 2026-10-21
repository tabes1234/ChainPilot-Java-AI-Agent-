/**
 * Datasource + agent binding API services. Mirrors DatasourceController / AgentDatasourceController.
 */

import { api, assertSuccess, unwrap } from './client';

export type DatasourceType = {
	code: number;
	typeName: string;
	dialect: string;
	protocol: string;
	displayName: string;
};

export type Datasource = {
	id?: number;
	name: string;
	type: string;
	host?: string;
	port?: number;
	databaseName?: string;
	username?: string;
	password?: string;
	connectionUrl?: string;
	status?: string;
	testStatus?: string;
	description?: string;
	createTime?: string;
	updateTime?: string;
};

export type DatasourceInput = {
	name: string;
	type: string;
	host?: string;
	port?: number;
	databaseName?: string;
	username?: string;
	password?: string;
	description?: string;
};

export type LogicalRelation = {
	id?: number;
	datasourceId?: number;
	sourceTableName: string;
	sourceColumnName: string;
	targetTableName: string;
	targetColumnName: string;
	relationType?: string;
	description?: string;
	createdTime?: string;
	updatedTime?: string;
};

export type AgentDatasource = {
	id?: number;
	agentId?: number;
	datasourceId?: number;
	isActive?: number;
	selectTables?: string[];
	datasource?: Datasource;
};

export async function fetchDatasourceTypes(): Promise<DatasourceType[]> {
	const response = await api.get<{ success: boolean; message?: string; data?: DatasourceType[] }>('/api/datasource/types');
	return unwrap(response, '加载数据源类型失败') ?? [];
}

export async function fetchDatasources(params?: { status?: string; type?: string }): Promise<Datasource[]> {
	const search = new URLSearchParams();
	if (params?.status) search.set('status', params.status);
	if (params?.type) search.set('type', params.type);
	const qs = search.toString();
	return api.get<Datasource[]>(`/api/datasource${qs ? `?${qs}` : ''}`);
}

export async function fetchDatasource(id: number): Promise<Datasource> {
	return api.get<Datasource>(`/api/datasource/${id}`);
}

export async function createDatasource(input: DatasourceInput): Promise<Datasource> {
	return api.post<Datasource>('/api/datasource', input);
}

export async function updateDatasource(id: number, input: DatasourceInput): Promise<Datasource> {
	return api.put<Datasource>(`/api/datasource/${id}`, input);
}

export async function deleteDatasource(id: number): Promise<void> {
	assertSuccess(await api.delete<{ success: boolean; message?: string }>(`/api/datasource/${id}`), '删除数据源失败');
}

export async function testDatasource(id: number): Promise<string> {
	const response = await api.post<{ success: boolean; message?: string }>(`/api/datasource/${id}/test`, undefined, {
		timeoutMs: 60_000,
	});
	if (response.success !== true) throw new Error(response.message || '连接测试失败');
	return response.message || '连接测试成功';
}

export async function fetchDatasourceTables(id: number): Promise<string[]> {
	return api.get<string[]>(`/api/datasource/${id}/tables`);
}

export async function fetchTableColumns(id: number, tableName: string): Promise<string[]> {
	const response = await api.get<{ success: boolean; message?: string; data?: string[] }>(
		`/api/datasource/${id}/tables/${encodeURIComponent(tableName)}/columns`,
	);
	return unwrap(response, '加载字段失败') ?? [];
}

export async function fetchLogicalRelations(datasourceId: number): Promise<LogicalRelation[]> {
	const response = await api.get<{ success: boolean; message?: string; data?: LogicalRelation[] }>(
		`/api/datasource/${datasourceId}/logical-relations`,
	);
	return unwrap(response, '加载逻辑关系失败') ?? [];
}

export async function createLogicalRelation(
	datasourceId: number,
	input: Omit<LogicalRelation, 'id' | 'datasourceId' | 'createdTime' | 'updatedTime'>,
): Promise<LogicalRelation> {
	const response = await api.post<{ success: boolean; message?: string; data?: LogicalRelation }>(
		`/api/datasource/${datasourceId}/logical-relations`,
		input,
	);
	return unwrap(response, '创建逻辑关系失败');
}

export async function deleteLogicalRelation(datasourceId: number, relationId: number): Promise<void> {
	const response = await api.delete<{ success: boolean; message?: string }>(
		`/api/datasource/${datasourceId}/logical-relations/${relationId}`,
	);
	assertSuccess(response, '删除逻辑关系失败');
}

// ── Agent binding ──────────────────────────────────────────────────────────

export async function fetchAgentBindings(agentId: number): Promise<AgentDatasource[]> {
	const response = await api.get<{ success: boolean; message?: string; data?: AgentDatasource[] }>(
		`/api/agent/${agentId}/datasources`,
	);
	return unwrap(response, '加载智能体数据源失败') ?? [];
}

/** Throws (400) when the agent has no active datasource. */
export async function fetchActiveBinding(agentId: number): Promise<AgentDatasource> {
	const response = await api.get<{ success: boolean; message?: string; data?: AgentDatasource }>(
		`/api/agent/${agentId}/datasources/active`,
	);
	return unwrap(response, '该智能体尚未绑定数据源');
}

export async function bindAgentDatasource(agentId: number, datasourceId: number): Promise<AgentDatasource | null> {
	const response = await api.post<{ success: boolean; message?: string; data?: AgentDatasource }>(
		`/api/agent/${agentId}/datasources/${datasourceId}`,
	);
	assertSuccess(response, '绑定数据源失败');
	return response.data ?? null;
}

export async function setAgentDatasourceTables(agentId: number, datasourceId: number, tables: string[]): Promise<void> {
	const response = await api.post<{ success: boolean; message?: string }>(`/api/agent/${agentId}/datasources/tables`, {
		datasourceId,
		tables,
	});
	assertSuccess(response, '保存数据表失败');
}

export async function initAgentDatasourceSchema(agentId: number): Promise<string> {
	const response = await api.post<{ success: boolean; message?: string }>(
		`/api/agent/${agentId}/datasources/init`,
		undefined,
		{ timeoutMs: 120_000 },
	);
	if (response.success !== true) throw new Error(response.message || 'Schema 初始化失败');
	return response.message || 'Schema 初始化成功';
}

export async function removeAgentDatasource(agentId: number, datasourceId: number): Promise<void> {
	const response = await api.delete<{ success: boolean; message?: string }>(
		`/api/agent/${agentId}/datasources/${datasourceId}`,
	);
	assertSuccess(response, '移除数据源失败');
}
