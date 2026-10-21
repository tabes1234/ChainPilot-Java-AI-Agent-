'use client';

import { useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { toast } from 'sonner';
import {
	Globe,
	GlobeLock,
	KeyRound,
	ListChecks,
	Loader2,
	Pencil,
	Plus,
	RefreshCw,
	Search,
	Tag,
	Trash2,
	Copy,
} from 'lucide-react';
import { useQueryClient } from '@tanstack/react-query';
import { PageHeader } from '@/components/page-header';
import { SectionNav } from '@/components/section-nav';
import { EmptyState, ErrorState, LoadingState } from '@/components/data-states';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Textarea } from '@/components/ui/textarea';
import {
	buildAgentUpdatePayload,
	deleteApiKey,
	fetchApiKey,
	fetchPresetQuestions,
	savePresetQuestions,
	generateApiKey,
	setApiKeyEnabled,
	type Agent,
	type AgentStatus,
	type ApiKeyInfo,
} from '@/lib/api/agent';
import { errorMessage } from '@/lib/api/client';
import { useAgents, useDeleteAgent, useOfflineAgent, usePublishAgent, useUpdateAgent } from '@/lib/query/hooks';

const STATUS_LABELS: Record<AgentStatus, string> = { published: '已发布', draft: '草稿', offline: '已下线' };
const STATUS_CLASSES: Record<AgentStatus, string> = {
	published: 'bg-green-50 text-green-700 border-green-200',
	draft: 'bg-amber-50 text-amber-700 border-amber-200',
	offline: 'bg-gray-50 text-gray-600 border-gray-200',
};
const STATUS_ITEMS = { all: '全部智能体', published: '已发布', draft: '草稿', offline: '已下线' };
const PAGE_SIZES = [10, 20, 50];

type StatusFilter = AgentStatus | 'all';
type EditDraft = {
	id: number;
	name: string;
	description: string;
	category: string;
	tags: string;
	prompt: string;
	status: AgentStatus;
	apiKeyEnabled?: number;
	avatar?: string;
};
type ConfirmAction = { kind: 'delete' | 'publish' | 'offline'; agent: Agent } | null;

const parseTags = (tags?: string) =>
	(tags ?? '')
		.split(',')
		.map((tag) => tag.trim())
		.filter(Boolean);

export default function AgentsPage() {
	const queryClient = useQueryClient();
	const [keywordInput, setKeywordInput] = useState('');
	const [debouncedKeyword, setDebouncedKeyword] = useState('');
	const [statusFilter, setStatusFilter] = useState<StatusFilter>('all');
	const [page, setPage] = useState(1);
	const [pageSize, setPageSize] = useState(10);

	useEffect(() => {
		const timer = setTimeout(() => {
			setDebouncedKeyword(keywordInput.trim());
			setPage(1);
		}, 400);
		return () => clearTimeout(timer);
	}, [keywordInput]);

	const hasKeyword = debouncedKeyword.length > 0;
	// The backend lets keyword win over status, so status is applied client-side while searching.
	const queryStatus = hasKeyword ? undefined : statusFilter === 'all' ? undefined : statusFilter;
	const agentsQuery = useAgents(queryStatus, hasKeyword ? debouncedKeyword : undefined);
	const allAgentsQuery = useAgents();

	const agents = useMemo(() => {
		const list = agentsQuery.data ?? [];
		return hasKeyword && statusFilter !== 'all' ? list.filter((agent) => agent.status === statusFilter) : list;
	}, [agentsQuery.data, hasKeyword, statusFilter]);

	const counts = useMemo(() => {
		const all = allAgentsQuery.data ?? [];
		return {
			total: all.length,
			published: all.filter((agent) => agent.status === 'published').length,
			draft: all.filter((agent) => agent.status === 'draft').length,
			offline: all.filter((agent) => agent.status === 'offline').length,
		};
	}, [allAgentsQuery.data]);

	const totalPages = Math.max(1, Math.ceil(agents.length / pageSize));
	const currentPage = Math.min(page, totalPages);
	const pageRows = agents.slice((currentPage - 1) * pageSize, currentPage * pageSize);

	const updateMutation = useUpdateAgent();
	const deleteMutation = useDeleteAgent();
	const publishMutation = usePublishAgent();
	const offlineMutation = useOfflineAgent();

	// ── Dialogs ────────────────────────────────────────────────────────────
	const [editDraft, setEditDraft] = useState<EditDraft | null>(null);
	const [confirm, setConfirm] = useState<ConfirmAction>(null);
	const [tagsAgent, setTagsAgent] = useState<Agent | null>(null);
	const [keyAgent, setKeyAgent] = useState<Agent | null>(null);
	const [keyInfo, setKeyInfo] = useState<ApiKeyInfo | null>(null);
	const [keyLoading, setKeyLoading] = useState(false);
	const [keyBusy, setKeyBusy] = useState(false);
	const [freshKey, setFreshKey] = useState<string | null>(null);

	const [presetAgent, setPresetAgent] = useState<Agent | null>(null);
	const [presetQuestions, setPresetQuestions] = useState<{ id?: number; question: string; isActive?: boolean }[]>([]);
	const [presetLoading, setPresetLoading] = useState(false);
	const [presetNew, setPresetNew] = useState('');
	const [presetBusy, setPresetBusy] = useState(false);

	const openEdit = (agent: Agent) => {
		setEditDraft({
			id: agent.id!,
			name: agent.name ?? '',
			description: agent.description ?? '',
			category: agent.category ?? '',
			tags: agent.tags ?? '',
			prompt: agent.prompt ?? '',
			status: (agent.status ?? 'draft') as AgentStatus,
			apiKeyEnabled: agent.apiKeyEnabled,
			avatar: agent.avatar,
		});
	};

	const saveEdit = async () => {
		if (!editDraft) return;
		if (!editDraft.name.trim()) {
			toast.warning('请输入智能体名称');
			return;
		}
		try {
			// If the list did not return the flag, read the authoritative key state first:
			// omitting it would make the backend reset apiKeyEnabled to 0.
			let apiKeyEnabled = editDraft.apiKeyEnabled;
			if (apiKeyEnabled === undefined) {
				apiKeyEnabled = (await fetchApiKey(editDraft.id)).apiKeyEnabled;
			}
			await updateMutation.mutateAsync({
				id: editDraft.id,
				agent: buildAgentUpdatePayload(
					{ apiKeyEnabled },
					{
						name: editDraft.name.trim(),
						description: editDraft.description.trim(),
						category: editDraft.category.trim(),
						tags: editDraft.tags.trim(),
						prompt: editDraft.prompt,
						status: editDraft.status,
					},
				),
			});
			toast.success('智能体更新成功');
			setEditDraft(null);
		} catch (error) {
			toast.error('更新失败', { description: errorMessage(error) });
		}
	};

	const openPresets = async (agent: Agent) => {
		if (agent.id === undefined) return;
		setPresetAgent(agent);
		setPresetLoading(true);
		setPresetNew('');
		try {
			setPresetQuestions(await fetchPresetQuestions(agent.id));
		} catch (error) {
			toast.error('加载预设问题失败', { description: errorMessage(error) });
			setPresetQuestions([]);
		} finally {
			setPresetLoading(false);
		}
	};

	const addPresetQuestion = () => {
		const question = presetNew.trim();
		if (!question) return;
		setPresetQuestions((prev) => [...prev, { question, isActive: true }]);
		setPresetNew('');
	};

	const savePresets = async () => {
		if (presetAgent?.id === undefined) return;
		setPresetBusy(true);
		try {
			await savePresetQuestions(
				presetAgent.id,
				presetQuestions.filter((question) => question.isActive !== false).map((question) => ({ question: question.question, isActive: true })),
			);
			toast.success('预设问题已保存', { description: '顺序按当前列表保存，后端会整组替换。' });
			setPresetAgent(null);
		} catch (error) {
			toast.error('保存预设问题失败', { description: errorMessage(error) });
		} finally {
			setPresetBusy(false);
		}
	};

	const runConfirm = async () => {
		if (!confirm || confirm.agent.id === undefined) return;
		const agent = confirm.agent;
		try {
			if (confirm.kind === 'delete') {
				await deleteMutation.mutateAsync(agent.id!);
				toast.success('智能体删除成功', { description: '相关知识、向量与头像资源会由后端异步清理' });
			} else if (confirm.kind === 'publish') {
				await publishMutation.mutateAsync(agent.id!);
				toast.success('智能体已发布');
			} else {
				await offlineMutation.mutateAsync(agent.id!);
				toast.success('智能体已下线');
			}
			setConfirm(null);
		} catch (error) {
			toast.error('操作失败', { description: errorMessage(error) });
		}
	};

	const openApiKey = async (agent: Agent) => {
		if (agent.id === undefined) return;
		setKeyAgent(agent);
		setFreshKey(null);
		setKeyInfo(null);
		setKeyLoading(true);
		try {
			setKeyInfo(await fetchApiKey(agent.id));
		} catch (error) {
			toast.error('获取 API Key 状态失败', { description: errorMessage(error) });
		} finally {
			setKeyLoading(false);
		}
	};

	const runKeyAction = async (action: 'generate' | 'reset' | 'delete' | 'enable' | 'disable') => {
		if (keyAgent?.id === undefined) return;
		setKeyBusy(true);
		try {
			let info: ApiKeyInfo;
			if (action === 'generate' || action === 'reset') {
				info = await generateApiKey(keyAgent.id, action === 'reset');
				setFreshKey(info.apiKey ?? null);
				toast.success(action === 'reset' ? 'API Key 已重置' : 'API Key 已生成', {
					description: '明文仅显示一次，请立即复制保存',
				});
			} else if (action === 'delete') {
				info = await deleteApiKey(keyAgent.id);
				setFreshKey(null);
				toast.success('API Key 已删除');
			} else {
				info = await setApiKeyEnabled(keyAgent.id, action === 'enable');
				toast.success(action === 'enable' ? 'API Key 已启用' : 'API Key 已停用');
			}
			setKeyInfo(info);
			await queryClient.invalidateQueries({ queryKey: ['agents'] });
		} catch (error) {
			toast.error('操作失败', { description: errorMessage(error) });
		} finally {
			setKeyBusy(false);
		}
	};

	const copyKey = async () => {
		if (!freshKey) return;
		try {
			await navigator.clipboard.writeText(freshKey);
			toast.success('已复制到剪贴板');
		} catch {
			toast.error('复制失败，请手动选择文本复制');
		}
	};

	const confirmBusy = deleteMutation.isPending || publishMutation.isPending || offlineMutation.isPending;

	return (
		<div className="space-y-6">
			<SectionNav group="system" />
			<PageHeader
				title="智能体管理"
				breadcrumb="知识与智能体"
				description="管理智能体的基本信息、发布状态与对外访问密钥。"
				actions={
					<>
						<Button variant="outline" onClick={() => allAgentsQuery.refetch()} disabled={allAgentsQuery.isFetching}>
							<RefreshCw className={`mr-2 h-4 w-4 ${allAgentsQuery.isFetching ? 'animate-spin' : ''}`} />
							刷新
						</Button>
						<Link href="/agent/new">
							<Button className="bg-[#35629d] hover:bg-[#264c79]">
								<Plus className="mr-2 h-4 w-4" />
								新建智能体
							</Button>
						</Link>
					</>
				}
			/>

			<Card>
				<CardContent className="flex flex-wrap items-center gap-3 pt-6">
					<div className="relative min-w-[240px] max-w-md flex-1">
						<Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-[#5a708c]" />
						<Input
							placeholder="搜索名称、描述或 ID（服务端搜索）"
							className="pl-10"
							value={keywordInput}
							onChange={(event) => setKeywordInput(event.target.value)}
						/>
					</div>
					<Select items={STATUS_ITEMS} value={statusFilter} onValueChange={(value) => { setStatusFilter((value ?? 'all') as StatusFilter); setPage(1); }}>
						<SelectTrigger className="w-[150px]">
							<SelectValue />
						</SelectTrigger>
						<SelectContent>
							<SelectItem value="all">全部智能体</SelectItem>
							<SelectItem value="published">已发布</SelectItem>
							<SelectItem value="draft">草稿</SelectItem>
							<SelectItem value="offline">已下线</SelectItem>
						</SelectContent>
					</Select>
					<div className="ml-auto flex items-center gap-2 text-xs text-[#5a708c]">
						<Badge variant="secondary">总数 {counts.total}</Badge>
						<Badge variant="outline" className={STATUS_CLASSES.published}>已发布 {counts.published}</Badge>
						<Badge variant="outline" className={STATUS_CLASSES.draft}>草稿 {counts.draft}</Badge>
						<Badge variant="outline" className={STATUS_CLASSES.offline}>已下线 {counts.offline}</Badge>
					</div>
					{hasKeyword && statusFilter !== 'all' && (
						<p className="w-full text-xs text-[#b38340]">服务端关键词搜索会覆盖状态参数，当前状态筛选在前端应用。</p>
					)}
				</CardContent>
			</Card>

			<Card>
				<CardHeader className="flex flex-row items-center justify-between">
					<CardTitle className="text-lg">智能体列表</CardTitle>
					<span className="text-sm text-[#5a708c]">当前筛选 {agents.length} 条</span>
				</CardHeader>
				<CardContent>
					{agentsQuery.isPending ? (
						<LoadingState label="加载智能体列表…" />
					) : agentsQuery.isError ? (
						<ErrorState message={errorMessage(agentsQuery.error, '智能体列表加载失败')} onRetry={() => agentsQuery.refetch()} />
					) : agents.length === 0 ? (
						<EmptyState
							title={hasKeyword || statusFilter !== 'all' ? '没有匹配的智能体' : '暂无智能体'}
							description={hasKeyword || statusFilter !== 'all' ? '尝试调整搜索关键词或状态筛选。' : '创建第一个智能体后即可配置知识与提示词。'}
							action={<Link href="/agent/new"><Button className="bg-[#35629d] hover:bg-[#264c79]"><Plus className="mr-2 h-4 w-4" />新建智能体</Button></Link>}
						/>
					) : (
						<>
							<div className="overflow-x-auto">
								<Table>
									<TableHeader>
										<TableRow>
											<TableHead className="w-[56px]">ID</TableHead>
											<TableHead>智能体</TableHead>
											<TableHead>描述</TableHead>
											<TableHead>分类</TableHead>
											<TableHead>标签</TableHead>
											<TableHead>状态</TableHead>
											<TableHead>创建时间</TableHead>
											<TableHead className="text-right">操作</TableHead>
										</TableRow>
									</TableHeader>
									<TableBody>
										{pageRows.map((agent) => {
											const tags = parseTags(agent.tags);
											return (
												<TableRow key={agent.id}>
													<TableCell className="font-mono text-xs text-[#5a708c]">{agent.id}</TableCell>
													<TableCell>
														<div className="flex items-center gap-2">
															<div className="flex h-8 w-8 shrink-0 items-center justify-center overflow-hidden rounded-lg bg-[#e8f0f8] text-xs font-bold text-[#35629d]">
																{agent.avatar ? (
																	// eslint-disable-next-line @next/next/no-img-element
																	<img src={agent.avatar} alt="" className="h-full w-full object-cover" />
																) : (
																	(agent.name ?? 'AI').slice(0, 2).toUpperCase()
																)}
															</div>
															<span className="font-medium text-[#1c3048]">{agent.name}</span>
														</div>
													</TableCell>
													<TableCell className="max-w-[260px] truncate text-sm text-[#5a708c]" title={agent.description}>
														{agent.description || '—'}
													</TableCell>
													<TableCell className="text-sm">{agent.category || '—'}</TableCell>
													<TableCell>
														<div className="flex max-w-[180px] flex-wrap items-center gap-1">
															{tags.slice(0, 2).map((tag) => (
																<Badge key={tag} variant="secondary" className="text-xs">{tag}</Badge>
															))}
															{tags.length > 2 && (
																<button className="text-xs text-[#35629d] underline" onClick={() => setTagsAgent(agent)}>
																	+{tags.length - 2}
																</button>
															)}
															{tags.length === 0 && <span className="text-xs text-[#8aa2ba]">无</span>}
														</div>
													</TableCell>
													<TableCell>
														<Badge variant="outline" className={STATUS_CLASSES[(agent.status ?? 'draft') as AgentStatus]}>
															{STATUS_LABELS[(agent.status ?? 'draft') as AgentStatus]}
														</Badge>
													</TableCell>
													<TableCell className="whitespace-nowrap text-xs text-[#5a708c]">{agent.createTime ?? '—'}</TableCell>
													<TableCell>
														<div className="flex justify-end gap-0.5">
															<Button variant="ghost" size="icon" title="编辑" aria-label="编辑" onClick={() => openEdit(agent)}>
																<Pencil className="h-4 w-4" />
															</Button>
															{agent.status === 'published' ? (
																<Button variant="ghost" size="icon" title="下线" aria-label="下线" onClick={() => setConfirm({ kind: 'offline', agent })}>
																	<GlobeLock className="h-4 w-4 text-[#b38340]" />
																</Button>
															) : (
																<Button variant="ghost" size="icon" title="发布" aria-label="发布" onClick={() => setConfirm({ kind: 'publish', agent })}>
																	<Globe className="h-4 w-4 text-[#428766]" />
																</Button>
															)}
															<Button variant="ghost" size="icon" title="预设问题" aria-label="预设问题" onClick={() => openPresets(agent)}>
																<ListChecks className="h-4 w-4" />
															</Button>
															<Button variant="ghost" size="icon" title="API Key" aria-label="API Key" onClick={() => openApiKey(agent)}>
																<KeyRound className="h-4 w-4" />
															</Button>
															<Button variant="ghost" size="icon" title="查看全部标签" aria-label="查看全部标签" onClick={() => setTagsAgent(agent)}>
																<Tag className="h-4 w-4" />
															</Button>
															<Button variant="ghost" size="icon" title="删除" aria-label="删除" onClick={() => setConfirm({ kind: 'delete', agent })}>
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

							<div className="flex flex-wrap items-center justify-end gap-3 border-t border-[#e6eef7] pt-3">
								<Select
									items={PAGE_SIZE_ITEMS}
									value={String(pageSize)}
									onValueChange={(value) => { setPageSize(Number(value ?? 10)); setPage(1); }}
								>
									<SelectTrigger className="w-[120px]"><SelectValue /></SelectTrigger>
									<SelectContent>
										{PAGE_SIZES.map((size) => (
											<SelectItem key={size} value={String(size)}>{size} 条/页</SelectItem>
										))}
									</SelectContent>
								</Select>
								<span className="text-sm text-[#5a708c]">第 {currentPage} / {totalPages} 页</span>
								<Button variant="outline" size="sm" disabled={currentPage <= 1} onClick={() => setPage(currentPage - 1)}>上一页</Button>
								<Button variant="outline" size="sm" disabled={currentPage >= totalPages} onClick={() => setPage(currentPage + 1)}>下一页</Button>
							</div>
						</>
					)}
				</CardContent>
			</Card>

			{/* Edit dialog */}
			<Dialog open={editDraft !== null} onOpenChange={(open) => !open && setEditDraft(null)}>
				<DialogContent className="max-w-2xl">
					<DialogHeader>
						<DialogTitle>编辑智能体</DialogTitle>
						<DialogDescription>后端按非空字段更新；API Key 状态会原样保留。</DialogDescription>
					</DialogHeader>
					{editDraft && (
						<div className="max-h-[62vh] space-y-4 overflow-y-auto py-1 pr-1">
							<div className="space-y-2">
								<Label htmlFor="edit-name">名称 *</Label>
								<Input id="edit-name" value={editDraft.name} onChange={(event) => setEditDraft({ ...editDraft, name: event.target.value })} />
							</div>
							<div className="space-y-2">
								<Label htmlFor="edit-description">描述</Label>
								<Textarea id="edit-description" rows={3} value={editDraft.description} onChange={(event) => setEditDraft({ ...editDraft, description: event.target.value })} />
							</div>
							<div className="grid gap-4 md:grid-cols-2">
								<div className="space-y-2">
									<Label htmlFor="edit-category">分类</Label>
									<Input id="edit-category" value={editDraft.category} onChange={(event) => setEditDraft({ ...editDraft, category: event.target.value })} />
								</div>
								<div className="space-y-2">
									<Label htmlFor="edit-tags">标签（逗号分隔）</Label>
									<Input id="edit-tags" value={editDraft.tags} onChange={(event) => setEditDraft({ ...editDraft, tags: event.target.value })} />
								</div>
							</div>
							<div className="space-y-2">
								<Label htmlFor="edit-prompt">系统提示词</Label>
								<Textarea id="edit-prompt" rows={5} value={editDraft.prompt} onChange={(event) => setEditDraft({ ...editDraft, prompt: event.target.value })} />
							</div>
							<div className="space-y-2">
								<Label>状态</Label>
								<Select
									items={STATUS_ITEMS}
									value={editDraft.status}
									onValueChange={(value) => setEditDraft({ ...editDraft, status: (value ?? 'draft') as AgentStatus })}
								>
									<SelectTrigger><SelectValue /></SelectTrigger>
									<SelectContent>
										<SelectItem value="draft">草稿</SelectItem>
										<SelectItem value="published">已发布</SelectItem>
										<SelectItem value="offline">已下线</SelectItem>
									</SelectContent>
								</Select>
							</div>
						</div>
					)}
					<DialogFooter>
						<Button variant="outline" onClick={() => setEditDraft(null)}>取消</Button>
						<Button className="bg-[#35629d] hover:bg-[#264c79]" disabled={updateMutation.isPending} onClick={saveEdit}>
							{updateMutation.isPending && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
							保存
						</Button>
					</DialogFooter>
				</DialogContent>
			</Dialog>

			{/* API key dialog */}
			<Dialog open={keyAgent !== null} onOpenChange={(open) => { if (!open) { setKeyAgent(null); setKeyInfo(null); setFreshKey(null); } }}>
				<DialogContent>
					<DialogHeader>
						<DialogTitle>API Key 管理</DialogTitle>
						<DialogDescription>{keyAgent?.name} · 密钥仅用于后端对外接口，请勿写入前端代码或日志。</DialogDescription>
					</DialogHeader>
					<div className="space-y-3 py-2">
						{keyLoading ? (
							<LoadingState compact label="读取密钥状态…" />
						) : (
							<>
								<div className="flex items-center justify-between rounded-lg border border-[#e2e8f0] bg-[#f8fbfe] px-3 py-2 text-sm">
									<span className="text-[#5a708c]">启用状态</span>
									<Badge variant={keyInfo?.apiKeyEnabled ? 'default' : 'secondary'}>
										{keyInfo?.apiKeyEnabled ? '已启用' : '未启用'}
									</Badge>
								</div>
								<div className="rounded-lg border border-[#e2e8f0] px-3 py-2 text-sm">
									<div className="text-[#5a708c]">当前密钥</div>
									<div className="mt-1 font-mono text-[#1c3048]">{keyInfo?.apiKey ?? '尚未生成'}</div>
								</div>
								{freshKey && (
									<div className="space-y-2 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2">
										<p className="text-xs font-medium text-[#8b6328]">新密钥明文仅显示一次，请立即保存：</p>
										<div className="flex items-center gap-2">
											<code className="min-w-0 flex-1 truncate rounded bg-white px-2 py-1 text-xs">{freshKey}</code>
											<Button size="sm" variant="outline" onClick={copyKey}><Copy className="mr-1 h-3 w-3" />复制</Button>
										</div>
									</div>
								)}
							</>
						)}
					</div>
					<DialogFooter className="flex-wrap gap-2">
						{!keyInfo?.apiKey ? (
							<Button disabled={keyBusy || keyLoading} onClick={() => runKeyAction('generate')}>生成密钥</Button>
						) : (
							<>
								<Button variant="outline" disabled={keyBusy} onClick={() => runKeyAction('reset')}>重置密钥</Button>
								<Button
									variant="outline"
									disabled={keyBusy}
									onClick={() => runKeyAction(keyInfo.apiKeyEnabled ? 'disable' : 'enable')}
								>
									{keyInfo.apiKeyEnabled ? '停用' : '启用'}
								</Button>
								<Button variant="destructive" disabled={keyBusy} onClick={() => runKeyAction('delete')}>删除密钥</Button>
							</>
						)}
						<Button variant="ghost" onClick={() => setKeyAgent(null)}>关闭</Button>
					</DialogFooter>
				</DialogContent>
			</Dialog>

			{/* Preset questions dialog */}
			<Dialog open={presetAgent !== null} onOpenChange={(open) => { if (!open) setPresetAgent(null); }}>
				<DialogContent>
					<DialogHeader>
						<DialogTitle>预设问题</DialogTitle>
						<DialogDescription>
							{presetAgent?.name} · 保存后由后端整组替换；数组顺序即展示顺序。聊天空状态会显示已启用的问题。
						</DialogDescription>
					</DialogHeader>
					<div className="space-y-3 py-1">
						{presetLoading ? (
							<LoadingState compact label="加载预设问题…" />
						) : presetQuestions.length === 0 ? (
							<p className="text-sm text-[#5a708c]">暂无预设问题。</p>
						) : (
							<ul className="space-y-1.5">
								{presetQuestions.map((question, index) => (
									<li key={question.id ?? `${index}-${question.question}`} className="flex items-center justify-between gap-2 rounded-xl bg-white/60 px-3 py-2">
										<span className="min-w-0 flex-1 truncate text-sm text-[#233448]" title={question.question}>{question.question}</span>
										<Button variant="ghost" size="icon" className="h-7 w-7 rounded-full" title="移除" aria-label="移除预设问题" onClick={() => setPresetQuestions((prev) => prev.filter((_, i) => i !== index))}>
											<Trash2 className="h-3.5 w-3.5 text-[#b9575c]" />
										</Button>
									</li>
								))}
							</ul>
						)}
						<div className="flex gap-2">
							<Input value={presetNew} placeholder="输入一个预设问题" onChange={(event) => setPresetNew(event.target.value)} onKeyDown={(event) => { if (event.key === 'Enter') { event.preventDefault(); addPresetQuestion(); } }} />
							<Button variant="outline" className="rounded-full" onClick={addPresetQuestion} disabled={!presetNew.trim()}>添加</Button>
						</div>
					</div>
					<DialogFooter>
						<Button variant="outline" className="rounded-full" onClick={() => setPresetAgent(null)}>取消</Button>
						<Button className="rounded-full" disabled={presetBusy} onClick={savePresets}>保存</Button>
					</DialogFooter>
				</DialogContent>
			</Dialog>

			{/* Tags dialog */}
			<Dialog open={tagsAgent !== null} onOpenChange={(open) => !open && setTagsAgent(null)}>
				<DialogContent>
					<DialogHeader>
						<DialogTitle>标签</DialogTitle>
						<DialogDescription>{tagsAgent?.name}</DialogDescription>
					</DialogHeader>
					<div className="flex flex-wrap gap-2 py-2">
						{parseTags(tagsAgent?.tags).map((tag) => (
							<Badge key={tag} variant="secondary">{tag}</Badge>
						))}
						{parseTags(tagsAgent?.tags).length === 0 && <p className="text-sm text-[#5a708c]">该智能体暂无标签。</p>}
					</div>
					<DialogFooter>
						<Button variant="outline" onClick={() => setTagsAgent(null)}>关闭</Button>
					</DialogFooter>
				</DialogContent>
			</Dialog>

			{/* Confirm dialog */}
			<Dialog open={confirm !== null} onOpenChange={(open) => !open && setConfirm(null)}>
				<DialogContent>
					<DialogHeader>
						<DialogTitle>
							{confirm?.kind === 'delete' ? '删除确认' : confirm?.kind === 'publish' ? '发布确认' : '下线确认'}
						</DialogTitle>
						<DialogDescription>
							{confirm?.kind === 'delete'
								? `确定要删除智能体「${confirm.agent.name}」吗？相关知识、向量与头像资源会由后端清理，此操作不可恢复。`
								: confirm?.kind === 'publish'
									? `确定要发布「${confirm.agent.name}」吗？`
									: `确定要让「${confirm?.agent.name}」下线吗？`}
						</DialogDescription>
					</DialogHeader>
					<DialogFooter>
						<Button variant="outline" onClick={() => setConfirm(null)}>取消</Button>
						<Button variant={confirm?.kind === 'delete' ? 'destructive' : 'default'} disabled={confirmBusy} onClick={runConfirm}>
							{confirmBusy && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
							确认
						</Button>
					</DialogFooter>
				</DialogContent>
			</Dialog>
		</div>
	);
}

const PAGE_SIZE_ITEMS: Record<string, string> = Object.fromEntries(PAGE_SIZES.map((size) => [String(size), `${size} 条/页`]));
