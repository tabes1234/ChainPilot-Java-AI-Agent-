'use client';

import { useMemo, useState } from 'react';
import { toast } from 'sonner';
import { ArrowUpDown, Loader2, Pencil, Plus, RefreshCw, Search, Trash2 } from 'lucide-react';
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
	batchSetPromptEnabled,
	deletePromptConfig,
	fetchAllPromptConfigs,
	fetchPromptConfigsByType,
	fetchPromptTypes,
	savePromptConfig,
	setPromptEnabled,
	setPromptPriority,
	type PromptConfig,
} from '@/lib/api/prompt';
import { useAgents } from '@/lib/query/hooks';

const ALL_AGENTS = 'ALL';

type Form = {
	id?: string;
	name: string;
	promptType: string;
	agentId: string;
	optimizationPrompt: string;
	description: string;
	enabled: boolean;
	priority: number;
	displayOrder: number;
};

const emptyForm = (promptType: string): Form => ({
	name: '',
	promptType,
	agentId: ALL_AGENTS,
	optimizationPrompt: '',
	description: '',
	enabled: true,
	priority: 0,
	displayOrder: 0,
});

export default function PromptConfigPage() {
	const queryClient = useQueryClient();
	const agentsQuery = useAgents();
	const agents = useMemo(() => agentsQuery.data ?? [], [agentsQuery.data]);

	const typesQuery = useQuery({
		queryKey: ['promptTypes'],
		queryFn: async () => {
			const [declared, all] = await Promise.all([fetchPromptTypes(), fetchAllPromptConfigs()]);
			return [...new Set([...declared, ...all.map((config) => config.promptType).filter(Boolean)])];
		},
	});
	const types = useMemo(() => typesQuery.data ?? [], [typesQuery.data]);

	const [promptType, setPromptType] = useState('');
	const [agentFilter, setAgentFilter] = useState<string>(ALL_AGENTS);
	const [keyword, setKeyword] = useState('');

	const activeType = promptType || types[0] || '';
	const activeAgentId = agentFilter === ALL_AGENTS ? undefined : Number(agentFilter);

	const configsQuery = useQuery({
		queryKey: ['promptConfigs', activeType, activeAgentId ?? null],
		queryFn: () => fetchPromptConfigsByType(activeType, activeAgentId),
		enabled: activeType.length > 0,
	});

	const configs = useMemo(() => {
		const list = configsQuery.data ?? [];
		if (!keyword.trim()) return list;
		const kw = keyword.trim().toLowerCase();
		return list.filter(
			(config) =>
				config.name?.toLowerCase().includes(kw) ||
				config.description?.toLowerCase().includes(kw) ||
				config.optimizationPrompt?.toLowerCase().includes(kw),
		);
	}, [configsQuery.data, keyword]);

	const invalidate = () => {
		queryClient.invalidateQueries({ queryKey: ['promptConfigs'] });
		queryClient.invalidateQueries({ queryKey: ['promptTypes'] });
	};

	const [selected, setSelected] = useState<string[]>([]);
	const [editorOpen, setEditorOpen] = useState(false);
	const [form, setForm] = useState<Form>(emptyForm(''));
	const [formError, setFormError] = useState<string | null>(null);
	const [saving, setSaving] = useState(false);
	const [pendingDelete, setPendingDelete] = useState<PromptConfig | null>(null);
	const [deleting, setDeleting] = useState(false);
	const [priorityTarget, setPriorityTarget] = useState<PromptConfig | null>(null);
	const [priorityValue, setPriorityValue] = useState(0);
	const [busyId, setBusyId] = useState<string | null>(null);

	const openCreate = () => {
		if (!activeType) {
			toast.error('请先选择提示词类型');
			return;
		}
		setForm({
			...emptyForm(activeType),
			agentId: agentFilter === ALL_AGENTS ? ALL_AGENTS : agentFilter,
		});
		setFormError(null);
		setEditorOpen(true);
	};

	const openEdit = (config: PromptConfig) => {
		setForm({
			id: config.id,
			name: config.name ?? '',
			promptType: config.promptType ?? activeType,
			agentId: config.agentId ? String(config.agentId) : ALL_AGENTS,
			optimizationPrompt: config.optimizationPrompt ?? config.systemPrompt ?? '',
			description: config.description ?? '',
			enabled: config.enabled !== false,
			priority: config.priority ?? 0,
			displayOrder: config.displayOrder ?? 0,
		});
		setFormError(null);
		setEditorOpen(true);
	};

	const save = async () => {
		if (!form.name.trim()) {
			setFormError('配置名称不能为空');
			return;
		}
		if (!form.optimizationPrompt.trim()) {
			setFormError('提示词内容不能为空');
			return;
		}
		setSaving(true);
		setFormError(null);
		try {
			await savePromptConfig({
				id: form.id,
				name: form.name.trim(),
				promptType: form.promptType,
				agentId: form.agentId === ALL_AGENTS ? null : Number(form.agentId),
				optimizationPrompt: form.optimizationPrompt,
				description: form.description.trim(),
				enabled: form.enabled,
				priority: Number(form.priority) || 0,
				displayOrder: Number(form.displayOrder) || 0,
			});
			toast.success(form.id ? '提示词配置已更新' : '提示词配置已保存');
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

	const toggleEnabled = async (config: PromptConfig) => {
		setBusyId(config.id);
		try {
			await setPromptEnabled(config.id, config.enabled === false);
			toast.success(config.enabled === false ? '已启用' : '已停用');
			invalidate();
		} catch (error) {
			toast.error('操作失败', { description: errorMessage(error) });
		} finally {
			setBusyId(null);
		}
	};

	const runDelete = async () => {
		if (!pendingDelete) return;
		setDeleting(true);
		try {
			await deletePromptConfig(pendingDelete.id);
			toast.success('提示词配置已删除');
			setSelected((prev) => prev.filter((id) => id !== pendingDelete.id));
			setPendingDelete(null);
			invalidate();
		} catch (error) {
			toast.error('删除失败', { description: errorMessage(error) });
		} finally {
			setDeleting(false);
		}
	};

	const runBatch = async (enabled: boolean) => {
		if (selected.length === 0) return;
		try {
			await batchSetPromptEnabled(selected, enabled);
			toast.success(`已批量${enabled ? '启用' : '停用'} ${selected.length} 条配置`);
			setSelected([]);
			invalidate();
		} catch (error) {
			toast.error('批量操作失败', { description: errorMessage(error) });
		}
	};

	const savePriority = async () => {
		if (!priorityTarget) return;
		try {
			await setPromptPriority(priorityTarget.id, Number(priorityValue) || 0);
			toast.success('优先级已更新');
			setPriorityTarget(null);
			invalidate();
		} catch (error) {
			toast.error('更新失败', { description: errorMessage(error) });
		}
	};

	const agentName = (agentId?: number | null) =>
		agentId ? agents.find((agent) => agent.id === agentId)?.name ?? `智能体 #${agentId}` : '全局';

	const allSelected = configs.length > 0 && configs.every((config) => selected.includes(config.id));

	return (
		<div className="space-y-6">
			<SectionNav group="system" />
			<PageHeader
				title="提示词配置"
				breadcrumb="系统设置"
				description="维护各节点使用的系统提示词，优先级高且启用的配置会参与生效。"
				actions={
					<>
						<Button variant="outline" onClick={() => configsQuery.refetch()} disabled={configsQuery.isFetching}>
							<RefreshCw className={`mr-2 h-4 w-4 ${configsQuery.isFetching ? 'animate-spin' : ''}`} />
							刷新
						</Button>
						<Button className="bg-[#35629d] hover:bg-[#264c79]" onClick={openCreate} disabled={!activeType}>
							<Plus className="mr-2 h-4 w-4" />
							新建配置
						</Button>
					</>
				}
			/>

			<Card>
				<CardContent className="flex flex-wrap items-center gap-3 pt-6">
					<Select items={Object.fromEntries(types.map((type) => [type, type]))} value={activeType || undefined} onValueChange={(value) => { setPromptType(value ?? ''); setSelected([]); }}>
						<SelectTrigger className="w-[220px]"><SelectValue placeholder="提示词类型" /></SelectTrigger>
						<SelectContent>
							{types.map((type) => <SelectItem key={type} value={type}>{type}</SelectItem>)}
						</SelectContent>
					</Select>

					<Select
						items={{ [ALL_AGENTS]: '全部归属', ...Object.fromEntries(agents.map((agent) => [String(agent.id), agent.name || `智能体 #${agent.id}`])) }}
						value={agentFilter}
						onValueChange={(value) => { setAgentFilter(value ?? ALL_AGENTS); setSelected([]); }}
					>
						<SelectTrigger className="w-[220px]"><SelectValue /></SelectTrigger>
						<SelectContent>
							<SelectItem value={ALL_AGENTS}>全部归属</SelectItem>
							{agents.map((agent) => <SelectItem key={agent.id} value={String(agent.id)}>{agent.name || `智能体 #${agent.id}`}</SelectItem>)}
						</SelectContent>
					</Select>

					<div className="relative min-w-[220px] max-w-md flex-1">
						<Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-[#5a708c]" />
						<Input placeholder="按名称、描述或提示词内容过滤" className="pl-10" value={keyword} onChange={(event) => setKeyword(event.target.value)} />
					</div>

					<Badge variant="secondary">当前 {configs.length} 条</Badge>

					{selected.length > 0 && (
						<div className="ml-auto flex gap-2">
							<Button size="sm" variant="outline" onClick={() => runBatch(true)}>批量启用 ({selected.length})</Button>
							<Button size="sm" variant="outline" onClick={() => runBatch(false)}>批量停用</Button>
						</div>
					)}
				</CardContent>
			</Card>

			<Card>
				<CardHeader><CardTitle className="text-lg">{activeType || '提示词'} 配置列表</CardTitle></CardHeader>
				<CardContent>
					{typesQuery.isError ? (
						<ErrorState message={errorMessage(typesQuery.error, '加载提示词类型失败')} onRetry={() => typesQuery.refetch()} />
					) : !activeType ? (
						<LoadingState label="加载提示词类型…" />
					) : configsQuery.isPending ? (
						<LoadingState label="加载提示词配置…" />
					) : configsQuery.isError ? (
						<ErrorState message={errorMessage(configsQuery.error, '加载提示词配置失败')} onRetry={() => configsQuery.refetch()} />
					) : configs.length === 0 ? (
						<EmptyState
							title="暂无提示词配置"
							description={keyword ? '当前过滤条件下没有匹配项。' : '新建配置后即可在该类型下生效。'}
							action={!keyword ? <Button onClick={openCreate}><Plus className="mr-2 h-4 w-4" />新建配置</Button> : undefined}
						/>
					) : (
						<div className="overflow-x-auto">
							<Table>
								<TableHeader>
									<TableRow>
										<TableHead className="w-10">
											<Checkbox
												checked={allSelected}
												onCheckedChange={(checked) =>
													setSelected(checked ? configs.map((config) => config.id) : [])
												}
												aria-label="全选"
											/>
										</TableHead>
										<TableHead>名称</TableHead>
										<TableHead>归属</TableHead>
										<TableHead>状态</TableHead>
										<TableHead>优先级</TableHead>
										<TableHead>更新时间</TableHead>
										<TableHead className="text-right">操作</TableHead>
									</TableRow>
								</TableHeader>
								<TableBody>
									{configs.map((config) => (
										<TableRow key={config.id}>
											<TableCell>
												<Checkbox
													checked={selected.includes(config.id)}
													onCheckedChange={(checked) =>
														setSelected((prev) => (checked ? [...new Set([...prev, config.id])] : prev.filter((id) => id !== config.id)))
													}
													aria-label={`选择 ${config.name}`}
												/>
											</TableCell>
											<TableCell className="max-w-[320px]">
												<div className="font-medium text-[#1c3048]">{config.name}</div>
												{config.description && <div className="truncate text-xs text-[#5a708c]" title={config.description}>{config.description}</div>}
											</TableCell>
											<TableCell className="text-sm">{agentName(config.agentId)}</TableCell>
											<TableCell>
												<Badge variant="outline" className={config.enabled !== false ? 'border-green-200 bg-green-50 text-green-700' : 'border-gray-200 bg-gray-50 text-gray-600'}>
													{config.enabled !== false ? '启用' : '停用'}
												</Badge>
											</TableCell>
											<TableCell className="text-sm">{config.priority ?? 0}</TableCell>
											<TableCell className="whitespace-nowrap text-xs text-[#5a708c]">{config.updateTime ?? config.createTime ?? '—'}</TableCell>
											<TableCell>
												<div className="flex justify-end gap-0.5">
													<Button variant="ghost" size="icon" title="编辑" aria-label="编辑" onClick={() => openEdit(config)}>
														<Pencil className="h-4 w-4" />
													</Button>
													<Button variant="ghost" size="icon" title="调整优先级" aria-label="调整优先级" onClick={() => { setPriorityTarget(config); setPriorityValue(config.priority ?? 0); }}>
														<ArrowUpDown className="h-4 w-4" />
													</Button>
													<Button
														variant="ghost"
														size="sm"
														disabled={busyId === config.id}
														onClick={() => toggleEnabled(config)}
													>
														{config.enabled !== false ? '停用' : '启用'}
													</Button>
													<Button variant="ghost" size="icon" title="删除" aria-label="删除" onClick={() => setPendingDelete(config)}>
														<Trash2 className="h-4 w-4 text-[#b9575c]" />
													</Button>
												</div>
											</TableCell>
										</TableRow>
									))}
								</TableBody>
							</Table>
						</div>
					)}
				</CardContent>
			</Card>

			<Dialog open={editorOpen} onOpenChange={setEditorOpen}>
				<DialogContent className="max-w-2xl">
					<DialogHeader>
						<DialogTitle>{form.id ? '编辑提示词配置' : '新建提示词配置'}</DialogTitle>
						<DialogDescription>
							{form.id ? '后端更新时不会修改提示词类型与创建人。' : '配置保存后可通过启用状态与优先级控制生效。'}
						</DialogDescription>
					</DialogHeader>
					<div className="max-h-[62vh] space-y-4 overflow-y-auto py-1 pr-1">
						<div className="grid gap-4 md:grid-cols-2">
							<div className="space-y-2">
								<Label htmlFor="pc-name">名称 *</Label>
								<Input id="pc-name" value={form.name} onChange={(event) => setForm({ ...form, name: event.target.value })} />
							</div>
							<div className="space-y-2">
								<Label>类型</Label>
								<Select items={Object.fromEntries(types.map((type) => [type, type]))} value={form.promptType} onValueChange={(value) => setForm({ ...form, promptType: value ?? form.promptType })}>
									<SelectTrigger disabled={Boolean(form.id)}><SelectValue /></SelectTrigger>
									<SelectContent>
										{types.map((type) => <SelectItem key={type} value={type}>{type}</SelectItem>)}
									</SelectContent>
								</Select>
							</div>
						</div>
						<div className="space-y-2">
							<Label>归属</Label>
							<Select
								items={{ [ALL_AGENTS]: '全局（不限智能体）', ...Object.fromEntries(agents.map((agent) => [String(agent.id), agent.name || `智能体 #${agent.id}`])) }}
								value={form.agentId}
								onValueChange={(value) => setForm({ ...form, agentId: value ?? ALL_AGENTS })}
							>
								<SelectTrigger><SelectValue /></SelectTrigger>
								<SelectContent>
									<SelectItem value={ALL_AGENTS}>全局（不限智能体）</SelectItem>
									{agents.map((agent) => <SelectItem key={agent.id} value={String(agent.id)}>{agent.name || `智能体 #${agent.id}`}</SelectItem>)}
								</SelectContent>
							</Select>
						</div>
						<div className="space-y-2">
							<Label htmlFor="pc-prompt">提示词内容 *</Label>
							<Textarea id="pc-prompt" rows={10} value={form.optimizationPrompt} onChange={(event) => setForm({ ...form, optimizationPrompt: event.target.value })} />
						</div>
						<div className="space-y-2">
							<Label htmlFor="pc-desc">描述</Label>
							<Textarea id="pc-desc" rows={2} value={form.description} onChange={(event) => setForm({ ...form, description: event.target.value })} />
						</div>
						<label className="flex items-center gap-2 text-sm text-[#33506b]">
							<Checkbox checked={form.enabled} onCheckedChange={(checked) => setForm({ ...form, enabled: Boolean(checked) })} />
							保存后启用
						</label>
						<details className="rounded-xl bg-white/45 px-3 py-2 ring-1 ring-white/60">
							<summary className="cursor-pointer text-[12.5px] font-medium text-[#5f7389]">高级排序设置</summary>
							<div className="mt-3 grid gap-4 md:grid-cols-2">
								<div className="space-y-2">
									<Label htmlFor="pc-priority">优先级</Label>
									<Input id="pc-priority" type="number" value={form.priority} onChange={(event) => setForm({ ...form, priority: Number(event.target.value) })} />
								</div>
								<div className="space-y-2">
									<Label htmlFor="pc-order">展示顺序</Label>
									<Input id="pc-order" type="number" value={form.displayOrder} onChange={(event) => setForm({ ...form, displayOrder: Number(event.target.value) })} />
								</div>
							</div>
						</details>
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
						<DialogTitle>删除确认</DialogTitle>
						<DialogDescription>确定要删除提示词配置「{pendingDelete?.name}」吗？</DialogDescription>
					</DialogHeader>
					<DialogFooter>
						<Button variant="outline" onClick={() => setPendingDelete(null)}>取消</Button>
						<Button variant="destructive" disabled={deleting} onClick={runDelete}>确认删除</Button>
					</DialogFooter>
				</DialogContent>
			</Dialog>

			<Dialog open={priorityTarget !== null} onOpenChange={(open) => !open && setPriorityTarget(null)}>
				<DialogContent>
					<DialogHeader>
						<DialogTitle>调整优先级</DialogTitle>
						<DialogDescription>{priorityTarget?.name} · 数值越大优先级越高</DialogDescription>
					</DialogHeader>
					<Input type="number" value={priorityValue} onChange={(event) => setPriorityValue(Number(event.target.value))} />
					<DialogFooter>
						<Button variant="outline" onClick={() => setPriorityTarget(null)}>取消</Button>
						<Button onClick={savePriority}>保存</Button>
					</DialogFooter>
				</DialogContent>
			</Dialog>
		</div>
	);
}
