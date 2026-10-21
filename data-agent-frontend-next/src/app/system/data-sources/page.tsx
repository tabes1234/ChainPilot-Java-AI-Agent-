'use client';

import { useMemo, useState } from 'react';
import { toast } from 'sonner';
import {
	Database,
	GitBranch,
	Link2,
	Loader2,
	Pencil,
	Plus,
	RefreshCw,
	Table2,
	Trash2,
	Unplug,
	Wifi,
} from 'lucide-react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { PageHeader } from '@/components/page-header';
import { SectionNav } from '@/components/section-nav';
import { EmptyState, ErrorState, LoadingState } from '@/components/data-states';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Checkbox } from '@/components/ui/checkbox';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Textarea } from '@/components/ui/textarea';
import { errorMessage } from '@/lib/api/client';
import {
	bindAgentDatasource,
	createDatasource,
	createLogicalRelation,
	deleteDatasource,
	deleteLogicalRelation,
	fetchAgentBindings,
	fetchDatasourceTables,
	fetchDatasourceTypes,
	fetchDatasources,
	fetchLogicalRelations,
	fetchTableColumns,
	initAgentDatasourceSchema,
	removeAgentDatasource,
	setAgentDatasourceTables,
	testDatasource,
	updateDatasource,
	type AgentDatasource,
	type Datasource,
	type DatasourceInput,
	type LogicalRelation,
} from '@/lib/api/datasource';
import { DATASOURCE_TEST_META, normalizeDatasourceTestStatus } from '@/lib/datasource-status';
import { useAgents } from '@/lib/query/hooks';
import type { Agent } from '@/lib/api/agent';

const EMPTY_FORM: DatasourceInput = {
	name: '',
	type: 'mysql',
	host: '',
	port: 3306,
	databaseName: '',
	username: '',
	password: '',
	description: '',
};

const DEFAULT_PORTS: Record<string, number> = {
	mysql: 3306,
	postgresql: 5432,
	dameng: 5236,
	sqlserver: 1433,
	oracle: 1521,
	hive: 10000,
};



export default function DataSourcesPage() {
	const queryClient = useQueryClient();
	const listQuery = useQuery({ queryKey: ['datasources'], queryFn: () => fetchDatasources() });
	const typesQuery = useQuery({ queryKey: ['datasourceTypes'], queryFn: fetchDatasourceTypes });
	const agentsQuery = useAgents();
	const datasources = useMemo(() => listQuery.data ?? [], [listQuery.data]);
	const types = useMemo(() => typesQuery.data ?? [], [typesQuery.data]);
	const agents = useMemo(() => agentsQuery.data ?? [], [agentsQuery.data]);

	const [editorOpen, setEditorOpen] = useState(false);
	const [editing, setEditing] = useState<Datasource | null>(null);
	const [form, setForm] = useState<DatasourceInput>(EMPTY_FORM);
	const [formError, setFormError] = useState<string | null>(null);
	const [saving, setSaving] = useState(false);
	const [pendingDelete, setPendingDelete] = useState<Datasource | null>(null);
	const [busyId, setBusyId] = useState<number | null>(null);
	const [tablesTarget, setTablesTarget] = useState<Datasource | null>(null);
	const [relationsTarget, setRelationsTarget] = useState<Datasource | null>(null);

	const invalidate = () => queryClient.invalidateQueries({ queryKey: ['datasources'] });

	const openCreate = () => {
		setEditing(null);
		setForm(EMPTY_FORM);
		setFormError(null);
		setEditorOpen(true);
	};

	const openEdit = (datasource: Datasource) => {
		setEditing(datasource);
		setForm({
			name: datasource.name ?? '',
			type: datasource.type ?? 'mysql',
			host: datasource.host ?? '',
			port: datasource.port ?? DEFAULT_PORTS[datasource.type ?? 'mysql'] ?? 3306,
			databaseName: datasource.databaseName ?? '',
			username: datasource.username ?? '',
			password: '',
			description: datasource.description ?? '',
		});
		setFormError(null);
		setEditorOpen(true);
	};

	const save = async () => {
		if (!form.name.trim()) {
			setFormError('数据源名称不能为空');
			return;
		}
		if (!form.type) {
			setFormError('请选择数据库类型');
			return;
		}
		setSaving(true);
		setFormError(null);
		try {
			const payload: DatasourceInput = {
				name: form.name.trim(),
				type: form.type,
				host: form.host?.trim(),
				port: form.port ? Number(form.port) : undefined,
				databaseName: form.databaseName?.trim(),
				username: form.username?.trim(),
				// Blank password keeps the stored one on update.
				password: form.password || undefined,
				description: form.description?.trim(),
			};
			if (editing?.id !== undefined) {
				await updateDatasource(editing.id, payload);
				toast.success('数据源已更新');
			} else {
				await createDatasource(payload);
				toast.success('数据源已创建');
			}
			setEditorOpen(false);
			invalidate();
		} catch (error) {
			const message = errorMessage(error);
			setFormError(message);
			toast.error('保存失败', { description: message });
		} finally {
			setSaving(false);
		}
	};

	const test = async (datasource: Datasource) => {
		if (!datasource.id) return;
		setBusyId(datasource.id);
		try {
			const message = await testDatasource(datasource.id);
			toast.success(message);
			invalidate();
		} catch (error) {
			toast.error('连接测试失败', { description: errorMessage(error) });
			invalidate();
		} finally {
			setBusyId(null);
		}
	};

	const runDelete = async () => {
		if (!pendingDelete?.id) return;
		setBusyId(pendingDelete.id);
		try {
			await deleteDatasource(pendingDelete.id);
			toast.success('数据源已删除', { description: '相关智能体绑定已由后端一并移除' });
			setPendingDelete(null);
			invalidate();
			queryClient.invalidateQueries({ queryKey: ['agentDatasources'] });
		} catch (error) {
			toast.error('删除失败', { description: errorMessage(error) });
		} finally {
			setBusyId(null);
		}
	};

	const typeLabel = (typeName?: string) => types.find((type) => type.typeName === typeName)?.displayName ?? typeName ?? '—';

	return (
		<div className="space-y-6">
			<SectionNav group="system" />
			<PageHeader
				title="数据源管理"
				breadcrumb="系统设置"
				description="维护数据库连接，测试连通性，并为智能体选择可用数据表与逻辑关系。密码不会回显到前端。"
				actions={
					<>
						<Button variant="outline" onClick={() => listQuery.refetch()} disabled={listQuery.isFetching}>
							<RefreshCw className={`mr-2 h-4 w-4 ${listQuery.isFetching ? 'animate-spin' : ''}`} />
							刷新
						</Button>
						<Button className="bg-[#35629d] hover:bg-[#264c79]" onClick={openCreate}>
							<Plus className="mr-2 h-4 w-4" />
							新增数据源
						</Button>
					</>
				}
			/>

			<Card>
				<CardHeader><CardTitle className="text-lg">数据源列表</CardTitle></CardHeader>
				<CardContent>
					{listQuery.isPending ? (
						<LoadingState label="加载数据源…" />
					) : listQuery.isError ? (
						<ErrorState message={errorMessage(listQuery.error, '加载数据源失败')} onRetry={() => listQuery.refetch()} />
					) : datasources.length === 0 ? (
						<EmptyState title="暂无数据源" description="添加数据库连接后，智能体才能查询真实业务数据。" action={<Button onClick={openCreate}><Plus className="mr-2 h-4 w-4" />新增数据源</Button>} />
					) : (
						<div className="overflow-x-auto">
							<Table>
								<TableHeader>
									<TableRow>
										<TableHead>名称</TableHead>
										<TableHead>类型</TableHead>
										<TableHead>连接地址</TableHead>
										<TableHead>状态</TableHead>
										<TableHead>连通性</TableHead>
										<TableHead>创建时间</TableHead>
										<TableHead className="text-right">操作</TableHead>
									</TableRow>
								</TableHeader>
								<TableBody>
									{datasources.map((datasource) => {
										const testStatus = DATASOURCE_TEST_META[normalizeDatasourceTestStatus(datasource.testStatus)];
										return (
											<TableRow key={datasource.id}>
												<TableCell>
													<div className="flex items-center gap-2">
														<Database className="h-4 w-4 text-[#35629d]" />
														<div>
															<div className="font-medium text-[#1c3048]">{datasource.name}</div>
															{datasource.description && <div className="max-w-[220px] truncate text-xs text-[#5a708c]" title={datasource.description}>{datasource.description}</div>}
														</div>
													</div>
												</TableCell>
												<TableCell className="text-sm">{typeLabel(datasource.type)}</TableCell>
												<TableCell className="text-xs text-[#5a708c]">
													{datasource.host ? `${datasource.host}:${datasource.port ?? ''}` : '—'}
													{datasource.databaseName ? ` / ${datasource.databaseName}` : ''}
												</TableCell>
												<TableCell>
													<Badge variant="outline" className={datasource.status === 'active' ? 'border-green-200 bg-green-50 text-green-700' : 'border-gray-200 bg-gray-50 text-gray-600'}>
														{datasource.status === 'active' ? '启用' : '停用'}
													</Badge>
												</TableCell>
												<TableCell>
													<Badge variant="outline" className={testStatus.className}>{testStatus.label}</Badge>
												</TableCell>
												<TableCell className="whitespace-nowrap text-xs text-[#5a708c]">{datasource.createTime ?? '—'}</TableCell>
												<TableCell>
													<div className="flex justify-end gap-0.5">
														<Button variant="ghost" size="icon" title="编辑" aria-label="编辑" onClick={() => openEdit(datasource)}>
															<Pencil className="h-4 w-4" />
														</Button>
														<Button variant="ghost" size="icon" title="连接测试" aria-label="连接测试" disabled={busyId === datasource.id} onClick={() => test(datasource)}>
															{busyId === datasource.id ? <Loader2 className="h-4 w-4 animate-spin" /> : <Wifi className="h-4 w-4 text-[#428766]" />}
														</Button>
														<Button variant="ghost" size="icon" title="表与智能体绑定" aria-label="表与智能体绑定" onClick={() => setTablesTarget(datasource)}>
															<Table2 className="h-4 w-4" />
														</Button>
														<Button variant="ghost" size="icon" title="逻辑关系" aria-label="逻辑关系" onClick={() => setRelationsTarget(datasource)}>
															<GitBranch className="h-4 w-4" />
														</Button>
														<Button variant="ghost" size="icon" title="删除" aria-label="删除" onClick={() => setPendingDelete(datasource)}>
															<Trash2 className="h-4 w-4 text-[#b9575c]" />
														</Button>
													</div>
												</TableCell>
											</TableRow>
										);
									})}
								</TableBody>
							</Table>
						</div>
					)}
				</CardContent>
			</Card>

			<Dialog open={editorOpen} onOpenChange={setEditorOpen}>
				<DialogContent className="max-w-2xl">
					<DialogHeader>
						<DialogTitle>{editing ? '编辑数据源' : '新增数据源'}</DialogTitle>
						<DialogDescription>
							{editing ? '密码留空表示保持原密码不变。' : '连接地址由后端根据类型与主机信息生成。'}
						</DialogDescription>
					</DialogHeader>
					<div className="max-h-[62vh] space-y-4 overflow-y-auto py-1 pr-1">
						<div className="grid gap-4 md:grid-cols-2">
							<div className="space-y-2">
								<Label htmlFor="ds-name">名称 *</Label>
								<Input id="ds-name" value={form.name} onChange={(event) => setForm({ ...form, name: event.target.value })} />
							</div>
							<div className="space-y-2">
								<Label>数据库类型</Label>
								<Select
									items={Object.fromEntries(types.map((type) => [type.typeName, type.displayName]))}
									value={form.type}
									onValueChange={(value) => {
										const type = value ?? 'mysql';
										setForm({ ...form, type, port: DEFAULT_PORTS[type] ?? form.port });
									}}
								>
									<SelectTrigger><SelectValue /></SelectTrigger>
									<SelectContent>
										{types.map((type) => <SelectItem key={type.typeName} value={type.typeName}>{type.displayName}</SelectItem>)}
									</SelectContent>
								</Select>
							</div>
							<div className="space-y-2">
								<Label htmlFor="ds-host">主机</Label>
								<Input id="ds-host" value={form.host} placeholder="localhost" onChange={(event) => setForm({ ...form, host: event.target.value })} />
							</div>
							<div className="space-y-2">
								<Label htmlFor="ds-port">端口</Label>
								<Input id="ds-port" type="number" value={form.port ?? ''} onChange={(event) => setForm({ ...form, port: Number(event.target.value) })} />
							</div>
							<div className="space-y-2">
								<Label htmlFor="ds-db">数据库名</Label>
								<Input id="ds-db" value={form.databaseName} onChange={(event) => setForm({ ...form, databaseName: event.target.value })} />
							</div>
							<div className="space-y-2">
								<Label htmlFor="ds-user">用户名</Label>
								<Input id="ds-user" value={form.username} autoComplete="off" onChange={(event) => setForm({ ...form, username: event.target.value })} />
							</div>
							<div className="space-y-2">
								<Label htmlFor="ds-pass">密码 {editing ? '（留空保持）' : ''}</Label>
								<Input id="ds-pass" type="password" autoComplete="new-password" value={form.password} onChange={(event) => setForm({ ...form, password: event.target.value })} />
							</div>
						</div>
						<div className="space-y-2">
							<Label htmlFor="ds-desc">描述</Label>
							<Textarea id="ds-desc" rows={2} value={form.description} onChange={(event) => setForm({ ...form, description: event.target.value })} />
						</div>
						{formError && <p className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-[#8f3b45]">{formError}</p>}
					</div>
					<DialogFooter>
						<Button variant="outline" onClick={() => setEditorOpen(false)}>取消</Button>
						<Button className="bg-[#35629d] hover:bg-[#264c79]" disabled={saving} onClick={save}>
							{saving && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
							保存
						</Button>
					</DialogFooter>
				</DialogContent>
			</Dialog>

			<Dialog open={pendingDelete !== null} onOpenChange={(open) => !open && setPendingDelete(null)}>
				<DialogContent>
					<DialogHeader>
						<DialogTitle>删除数据源</DialogTitle>
						<DialogDescription>
							确定要删除「{pendingDelete?.name}」吗？后端会先删除所有智能体绑定，此操作不可恢复。
						</DialogDescription>
					</DialogHeader>
					<DialogFooter>
						<Button variant="outline" onClick={() => setPendingDelete(null)}>取消</Button>
						<Button variant="destructive" disabled={busyId !== null} onClick={runDelete}>确认删除</Button>
					</DialogFooter>
				</DialogContent>
			</Dialog>

			{tablesTarget && (
				<TablesBindingDialog
					datasource={tablesTarget}
					agents={agents}
					onClose={() => setTablesTarget(null)}
				/>
			)}

			{relationsTarget && (
				<RelationsDialog datasource={relationsTarget} onClose={() => setRelationsTarget(null)} />
			)}
		</div>
	);
}

function TablesBindingDialog({
	datasource,
	agents,
	onClose,
}: {
	datasource: Datasource;
	agents: Agent[];
	onClose: () => void;
}) {
	const queryClient = useQueryClient();
	const [agentId, setAgentId] = useState<number | null>(agents[0]?.id ?? null);
	const [tableSelection, setTableSelection] = useState<{ key: string; tables: string[] }>({ key: '', tables: [] });
	const [previewTable, setPreviewTable] = useState<string | null>(null);
	const [busy, setBusy] = useState(false);

	const tablesQuery = useQuery({
		queryKey: ['datasourceTables', datasource.id],
		queryFn: () => fetchDatasourceTables(datasource.id!),
		enabled: datasource.id !== undefined,
	});

	const bindingsQuery = useQuery({
		queryKey: ['agentDatasources', agentId],
		queryFn: () => fetchAgentBindings(agentId!),
		enabled: agentId !== null,
	});

	const columnsQuery = useQuery({
		queryKey: ['datasourceColumns', datasource.id, previewTable],
		queryFn: () => fetchTableColumns(datasource.id!, previewTable!),
		enabled: datasource.id !== undefined && previewTable !== null,
	});

	const bindings = bindingsQuery.data ?? [];
	const binding: AgentDatasource | undefined = bindings.find((item) => item.datasourceId === datasource.id);
	const activeBinding = bindings.find((item) => item.isActive === 1);

	const selectionKey = `${binding?.id ?? 'none'}|${(binding?.selectTables ?? []).join(',')}`;
	const selectedTables = tableSelection.key === selectionKey ? tableSelection.tables : (binding?.selectTables ?? []);
	const setSelectedTables = (next: string[] | ((prev: string[]) => string[])) => {
		const tables = typeof next === 'function' ? next(selectedTables) : next;
		setTableSelection({ key: selectionKey, tables });
	};

	const invalidate = () => {
		queryClient.invalidateQueries({ queryKey: ['agentDatasources', agentId] });
	};

	const bind = async () => {
		if (!agentId || !datasource.id) return;
		setBusy(true);
		try {
			await bindAgentDatasource(agentId, datasource.id);
			toast.success('已设为该智能体的当前数据源', { description: '重新绑定会清空已选数据表，请重新保存表选择' });
			invalidate();
		} catch (error) {
			toast.error('绑定失败', { description: errorMessage(error) });
		} finally {
			setBusy(false);
		}
	};

	const saveTables = async () => {
		if (!agentId || !datasource.id) return;
		if (!binding) {
			toast.warning('请先将该数据源设为智能体当前数据源');
			return;
		}
		setBusy(true);
		try {
			await setAgentDatasourceTables(agentId, datasource.id, selectedTables);
			toast.success('数据表选择已保存');
			invalidate();
		} catch (error) {
			toast.error('保存失败', { description: errorMessage(error) });
		} finally {
			setBusy(false);
		}
	};

	const initSchema = async () => {
		if (!agentId) return;
		setBusy(true);
		try {
			const message = await initAgentDatasourceSchema(agentId);
			toast.success(message);
		} catch (error) {
			toast.error('初始化失败', { description: errorMessage(error) });
		} finally {
			setBusy(false);
		}
	};

	const unbind = async () => {
		if (!agentId || !datasource.id) return;
		setBusy(true);
		try {
			await removeAgentDatasource(agentId, datasource.id);
			toast.success('已移除绑定');
			invalidate();
		} catch (error) {
			toast.error('移除失败', { description: errorMessage(error) });
		} finally {
			setBusy(false);
		}
	};

	const toggleTable = (table: string, checked: boolean) => {
		setSelectedTables((prev) => (checked ? [...new Set([...prev, table])] : prev.filter((item) => item !== table)));
	};

	return (
		<Dialog open onOpenChange={(open) => !open && onClose()}>
			<DialogContent className="max-w-3xl">
				<DialogHeader>
					<DialogTitle>表与智能体绑定</DialogTitle>
					<DialogDescription>{datasource.name} · 每个智能体只能有一个启用的数据源</DialogDescription>
				</DialogHeader>
				<div className="space-y-4 py-1">
					<div className="flex flex-wrap items-end gap-3">
						<div className="space-y-1">
							<Label>智能体</Label>
							<Select
								items={Object.fromEntries(agents.map((agent) => [String(agent.id), agent.name || `智能体 #${agent.id}`]))}
								value={agentId === null ? undefined : String(agentId)}
								onValueChange={(value) => { if (value) { setAgentId(Number(value)); setPreviewTable(null); } }}
							>
								<SelectTrigger className="w-[240px]"><SelectValue placeholder="选择智能体" /></SelectTrigger>
								<SelectContent>
									{agents.map((agent) => <SelectItem key={agent.id} value={String(agent.id)}>{agent.name || `智能体 #${agent.id}`}</SelectItem>)}
								</SelectContent>
							</Select>
						</div>
						<Badge variant="outline" className={binding ? 'border-green-200 bg-green-50 text-green-700' : 'border-gray-200 bg-gray-50 text-gray-600'}>
							{binding ? (binding.isActive === 1 ? '当前数据源' : '已绑定未启用') : '未绑定'}
						</Badge>
						{activeBinding && activeBinding.datasourceId !== datasource.id && (
							<span className="text-xs text-[#b38340]">该智能体当前使用：{activeBinding.datasource?.name ?? `#${activeBinding.datasourceId}`}</span>
						)}
					</div>

					<div className="flex flex-wrap gap-2">
						<Button size="sm" variant="outline" disabled={busy || !agentId} onClick={bind}>
							<Link2 className="mr-1 h-4 w-4" />设为当前数据源
						</Button>
						<Button size="sm" variant="outline" disabled={busy || !binding} onClick={saveTables}>
							保存表选择（{selectedTables.length}）
						</Button>
						<Button size="sm" variant="outline" disabled={busy || !agentId} onClick={initSchema}>
							<RefreshCw className="mr-1 h-4 w-4" />初始化 Schema
						</Button>
						<Button size="sm" variant="ghost" disabled={busy || !binding} onClick={unbind}>
							<Unplug className="mr-1 h-4 w-4" />移除绑定
						</Button>
					</div>

					<div className="grid gap-3 md:grid-cols-2">
						<div className="rounded-lg border border-[#e2e8f0]">
							<div className="border-b border-[#e2e8f0] px-3 py-2 text-sm font-medium">数据表</div>
							<div className="max-h-[300px] overflow-y-auto p-2">
								{tablesQuery.isPending ? (
									<LoadingState compact label="加载数据表…" />
								) : tablesQuery.isError ? (
									<p className="p-2 text-sm text-[#8f3b45]">{errorMessage(tablesQuery.error, '读取数据表失败')}</p>
								) : (tablesQuery.data ?? []).length === 0 ? (
									<p className="p-2 text-sm text-[#5a708c]">该数据源没有可读取的数据表。</p>
								) : (
									(tablesQuery.data ?? []).map((table) => (
										<div key={table} className="flex items-center gap-2 rounded px-2 py-1.5 hover:bg-[#f2f7fc]">
											<Checkbox
												checked={selectedTables.includes(table)}
												onCheckedChange={(checked) => toggleTable(table, Boolean(checked))}
												aria-label={`选择表 ${table}`}
											/>
											<button className="flex-1 truncate text-left text-sm" onClick={() => setPreviewTable(table)}>{table}</button>
										</div>
									))
								)}
							</div>
						</div>
						<div className="rounded-lg border border-[#e2e8f0]">
							<div className="border-b border-[#e2e8f0] px-3 py-2 text-sm font-medium">
								字段预览 {previewTable ? `· ${previewTable}` : ''}
							</div>
							<div className="max-h-[300px] overflow-y-auto p-3 text-sm">
								{!previewTable ? (
									<p className="text-[#5a708c]">点击左侧表名查看字段。</p>
								) : columnsQuery.isPending ? (
									<LoadingState compact label="加载字段…" />
								) : columnsQuery.isError ? (
									<p className="text-[#8f3b45]">{errorMessage(columnsQuery.error, '读取字段失败')}</p>
								) : (
									<ul className="space-y-1">
										{(columnsQuery.data ?? []).map((column) => <li key={column} className="font-mono text-xs text-[#33506b]">{column}</li>)}
									</ul>
								)}
							</div>
						</div>
					</div>
				</div>
				<DialogFooter>
					<Button variant="outline" onClick={onClose}>关闭</Button>
				</DialogFooter>
			</DialogContent>
		</Dialog>
	);
}

function RelationsDialog({ datasource, onClose }: { datasource: Datasource; onClose: () => void }) {
	const queryClient = useQueryClient();
	const relationsQuery = useQuery({
		queryKey: ['logicalRelations', datasource.id],
		queryFn: () => fetchLogicalRelations(datasource.id!),
		enabled: datasource.id !== undefined,
	});
	const relations = relationsQuery.data ?? [];
	const [form, setForm] = useState<Omit<LogicalRelation, 'id' | 'datasourceId' | 'createdTime' | 'updatedTime'>>({
		sourceTableName: '',
		sourceColumnName: '',
		targetTableName: '',
		targetColumnName: '',
		relationType: '1:N',
		description: '',
	});
	const [busy, setBusy] = useState(false);

	const add = async () => {
		if (!datasource.id || !form.sourceTableName.trim() || !form.sourceColumnName.trim() || !form.targetTableName.trim() || !form.targetColumnName.trim()) {
			toast.warning('请填写源表/字段与目标表/字段');
			return;
		}
		setBusy(true);
		try {
			await createLogicalRelation(datasource.id, {
				sourceTableName: form.sourceTableName.trim(),
				sourceColumnName: form.sourceColumnName.trim(),
				targetTableName: form.targetTableName.trim(),
				targetColumnName: form.targetColumnName.trim(),
				relationType: form.relationType,
				description: form.description?.trim(),
			});
			toast.success('逻辑关系已添加');
			setForm({ sourceTableName: '', sourceColumnName: '', targetTableName: '', targetColumnName: '', relationType: '1:N', description: '' });
			queryClient.invalidateQueries({ queryKey: ['logicalRelations', datasource.id] });
		} catch (error) {
			toast.error('添加失败', { description: errorMessage(error) });
		} finally {
			setBusy(false);
		}
	};

	const remove = async (relation: LogicalRelation) => {
		if (!datasource.id || !relation.id) return;
		setBusy(true);
		try {
			await deleteLogicalRelation(datasource.id, relation.id);
			toast.success('逻辑关系已删除');
			queryClient.invalidateQueries({ queryKey: ['logicalRelations', datasource.id] });
		} catch (error) {
			toast.error('删除失败', { description: errorMessage(error) });
		} finally {
			setBusy(false);
		}
	};

	return (
		<Dialog open onOpenChange={(open) => !open && onClose()}>
			<DialogContent className="max-w-4xl">
				<DialogHeader>
					<DialogTitle>逻辑关系</DialogTitle>
					<DialogDescription>{datasource.name} · 描述表之间的业务关联，指导智能体正确 JOIN。</DialogDescription>
				</DialogHeader>
				<div className="space-y-4 py-1">
					<div className="grid gap-2 md:grid-cols-5">
						<Input placeholder="源表" value={form.sourceTableName} onChange={(event) => setForm({ ...form, sourceTableName: event.target.value })} />
						<Input placeholder="源字段" value={form.sourceColumnName} onChange={(event) => setForm({ ...form, sourceColumnName: event.target.value })} />
						<Input placeholder="目标表" value={form.targetTableName} onChange={(event) => setForm({ ...form, targetTableName: event.target.value })} />
						<Input placeholder="目标字段" value={form.targetColumnName} onChange={(event) => setForm({ ...form, targetColumnName: event.target.value })} />
						<Input placeholder="关系类型" value={form.relationType} onChange={(event) => setForm({ ...form, relationType: event.target.value })} />
					</div>
					<div className="flex gap-2">
						<Input placeholder="说明（可选）" value={form.description} onChange={(event) => setForm({ ...form, description: event.target.value })} />
						<Button disabled={busy} onClick={add}><Plus className="mr-1 h-4 w-4" />添加</Button>
					</div>

					<div className="max-h-[320px] overflow-y-auto rounded-lg border border-[#e2e8f0]">
						{relationsQuery.isPending ? (
							<LoadingState compact label="加载逻辑关系…" />
						) : relationsQuery.isError ? (
							<p className="p-3 text-sm text-[#8f3b45]">{errorMessage(relationsQuery.error, '加载逻辑关系失败')}</p>
						) : relations.length === 0 ? (
							<p className="p-3 text-sm text-[#5a708c]">暂无逻辑关系。</p>
						) : (
							<Table>
								<TableHeader>
									<TableRow>
										<TableHead>源</TableHead>
										<TableHead>目标</TableHead>
										<TableHead>类型</TableHead>
										<TableHead>说明</TableHead>
										<TableHead className="text-right">操作</TableHead>
									</TableRow>
								</TableHeader>
								<TableBody>
									{relations.map((relation) => (
										<TableRow key={relation.id}>
											<TableCell className="font-mono text-xs">{relation.sourceTableName}.{relation.sourceColumnName}</TableCell>
											<TableCell className="font-mono text-xs">{relation.targetTableName}.{relation.targetColumnName}</TableCell>
											<TableCell className="text-sm">{relation.relationType ?? '—'}</TableCell>
											<TableCell className="max-w-[200px] truncate text-xs text-[#5a708c]" title={relation.description}>{relation.description || '—'}</TableCell>
											<TableCell className="text-right">
												<Button variant="ghost" size="icon" title="删除" aria-label="删除" disabled={busy} onClick={() => remove(relation)}>
													<Trash2 className="h-4 w-4 text-[#b9575c]" />
												</Button>
											</TableCell>
										</TableRow>
									))}
								</TableBody>
							</Table>
						)}
					</div>
				</div>
				<DialogFooter>
					<Button variant="outline" onClick={onClose}>关闭</Button>
				</DialogFooter>
			</DialogContent>
		</Dialog>
	);
}
