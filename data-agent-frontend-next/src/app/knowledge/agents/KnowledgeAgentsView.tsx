'use client';

import { useEffect, useMemo, useState } from 'react';
import { useUrlParam } from '@/lib/use-url-param';
import Link from 'next/link';
import { toast } from 'sonner';
import {
	AlertTriangle,
	Brain,
	ChevronLeft,
	ChevronRight,
	Filter,
	Loader2,
	Pencil,
	Plus,
	RefreshCw,
	RotateCw,
	Search,
	Trash2,
	X,
} from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import {
	Dialog,
	DialogContent,
	DialogDescription,
	DialogFooter,
	DialogHeader,
	DialogTitle,
} from '@/components/ui/dialog';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Textarea } from '@/components/ui/textarea';
import type {
	AgentKnowledge,
	KnowledgeCreateInput,
	KnowledgePage,
	KnowledgePageQuery,
	KnowledgeType,
} from '@/lib/api/agentKnowledge';
import {
	useAgents,
	useCreateAgentKnowledge,
	useDeleteAgentKnowledge,
	useKnowledgePage,
	useRetryKnowledgeEmbedding,
	useUpdateAgentKnowledge,
	useUpdateKnowledgeRecall,
} from '@/lib/query/hooks';
import { SectionNav } from '@/components/section-nav';

const ALL = 'ALL';

const TYPE_OPTIONS: { value: KnowledgeType; label: string }[] = [
	{ value: 'DOCUMENT', label: '文档' },
	{ value: 'QA', label: '问答对' },
	{ value: 'FAQ', label: '常见问题' },
];

const toLabelMap = (options: { value: string; label: string }[]) => Object.fromEntries(options.map((option) => [option.value, option.label]));

const TYPE_LABELS = toLabelMap(TYPE_OPTIONS);

// base-ui renders the raw value in the trigger unless the Root is handed the item labels.
const TYPE_ITEMS = { ...TYPE_LABELS, [ALL]: '全部类型' };

const TYPE_HINTS: Record<KnowledgeType, string> = {
	DOCUMENT: '建议上传数据库表结构、码表映射字典或业务说明文档，便于 AI 检索字段含义。',
	QA: '请录入具体分析需求作为问题，并在答案中写出详细思考步骤与数据查找逻辑。',
	FAQ: '请针对业务术语、指标口径或常见歧义进行问答定义，用于统一 AI 判断标准。',
};

const STATUS_OPTIONS = [
	{ value: 'COMPLETED', label: '已完成' },
	{ value: 'PROCESSING', label: '处理中' },
	{ value: 'FAILED', label: '失败' },
	{ value: 'PENDING', label: '等待中' },
];

const STATUS_LABELS: Record<string, string> = {
	COMPLETED: '已完成',
	PROCESSING: '处理中',
	FAILED: '失败',
	PENDING: '等待中',
};

// Values accepted by the backend SplitterType enum; unmapped values fall back to token server-side.
const SPLITTER_OPTIONS = [
	{ value: 'token', label: 'Token 分块' },
	{ value: 'recursive', label: '递归分块' },
	{ value: 'sentence', label: '句子分块' },
	{ value: 'paragraph', label: '段落分块' },
	{ value: 'semantic', label: '语义分块' },
];

const PAGE_SIZES = [10, 20, 50, 100];

const STATUS_ITEMS = { ...STATUS_LABELS, [ALL]: '全部状态' };
const SPLITTER_ITEMS = toLabelMap(SPLITTER_OPTIONS);
const PAGE_SIZE_ITEMS: Record<string, string> = Object.fromEntries(PAGE_SIZES.map((size) => [String(size), `${size} 条/页`]));

const EMPTY_PAGE: KnowledgePage = { items: [], total: 0, pageNum: 1, pageSize: 10, totalPages: 0 };

type Draft = {
	type: KnowledgeType;
	title: string;
	question: string;
	content: string;
	splitterType: string;
	file: File | null;
};

const newDraft = (over?: Partial<Draft>): Draft => ({
	type: 'DOCUMENT',
	title: '',
	question: '',
	content: '',
	splitterType: 'token',
	file: null,
	...over,
});

export default function KnowledgeAgentsView() {
		const agentsQuery = useAgents();
	const agents = useMemo(() => agentsQuery.data ?? [], [agentsQuery.data]);

	// ── Working agent: URL ?agentId first, then the first real agent. Never a hardcoded ID. ──
	const agentIdParam = useUrlParam('agentId');
	const requestedAgentId = useMemo(() => {
		const raw = Number(agentIdParam);
		return Number.isInteger(raw) && raw > 0 ? raw : null;
	}, [agentIdParam]);

	const [manualAgentId, setManualAgentId] = useState<number | null>(null);

	const workingAgent = useMemo(() => {
		if (agents.length === 0) return null;
		const candidate = manualAgentId ?? requestedAgentId;
		if (candidate !== null) {
			const matched = agents.find((agent) => agent.id === candidate);
			if (matched) return matched;
		}
		return agents.find((agent) => (agent.id ?? 0) > 0) ?? null;
	}, [agents, manualAgentId, requestedAgentId]);

	const agentId = workingAgent?.id ?? null;

	// ── List query state ───────────────────────────────────────────────────
	const [titleInput, setTitleInput] = useState('');
	const [debouncedTitle, setDebouncedTitle] = useState('');
	const [typeFilter, setTypeFilter] = useState<KnowledgeType | typeof ALL>(ALL);
	const [statusFilter, setStatusFilter] = useState<string>(ALL);
	const [pageNum, setPageNum] = useState(1);
	const [pageSize, setPageSize] = useState(10);
	const [filtersOpen, setFiltersOpen] = useState(false);

	const handleAgentChange = (value: string | null) => {
		setManualAgentId(value ? Number(value) : null);
		setPageNum(1);
	};

	const handleTypeFilterChange = (value: string | null) => {
		setTypeFilter((value ?? ALL) as KnowledgeType | typeof ALL);
		setPageNum(1);
	};

	const handleStatusFilterChange = (value: string | null) => {
		setStatusFilter(value ?? ALL);
		setPageNum(1);
	};

	useEffect(() => {
		const timer = setTimeout(() => {
			setDebouncedTitle(titleInput.trim());
			setPageNum(1);
		}, 400);
		return () => clearTimeout(timer);
	}, [titleInput]);

	const query: KnowledgePageQuery = useMemo(
		() => ({
			title: debouncedTitle || undefined,
			type: typeFilter === ALL ? undefined : typeFilter,
			embeddingStatus: statusFilter === ALL ? undefined : statusFilter,
			pageNum,
			pageSize,
		}),
		[debouncedTitle, typeFilter, statusFilter, pageNum, pageSize],
	);

	const pageQuery = useKnowledgePage(agentId ?? 0, query, { enabled: agentId !== null });
	const page = pageQuery.data ?? EMPTY_PAGE;
	const rows = page.items;
	const lastPage = Math.max(1, page.totalPages || 0);

	// ── Mutations ──────────────────────────────────────────────────────────
	const createMutation = useCreateAgentKnowledge();
	const updateMutation = useUpdateAgentKnowledge();
	const deleteMutation = useDeleteAgentKnowledge();
	const recallMutation = useUpdateKnowledgeRecall();
	const retryMutation = useRetryKnowledgeEmbedding();

	// ── Editor ─────────────────────────────────────────────────────────────
	const [editorOpen, setEditorOpen] = useState(false);
	const [editing, setEditing] = useState<AgentKnowledge | null>(null);
	const [draft, setDraft] = useState<Draft>(newDraft());

	const openCreate = () => {
		if (agentId === null) {
			toast.error('当前没有可用智能体，请先创建智能体');
			return;
		}
		setEditing(null);
		setDraft(newDraft());
		setEditorOpen(true);
	};

	const openEdit = (knowledge: AgentKnowledge) => {
		setEditing(knowledge);
		setDraft(
			newDraft({
				type: knowledge.type ?? 'DOCUMENT',
				title: knowledge.title ?? '',
				question: knowledge.question ?? '',
				content: knowledge.content ?? '',
				splitterType: knowledge.splitterType ?? 'token',
			}),
		);
		setEditorOpen(true);
	};

	const saveDraft = async () => {
		if (agentId === null) return;

		if (!draft.title.trim()) {
			toast.warning('请输入知识标题');
			return;
		}
		if (draft.type === 'DOCUMENT') {
			if (!editing && !draft.file) {
				toast.warning('请上传文件');
				return;
			}
		} else if (!editing) {
			if (!draft.question.trim()) {
				toast.warning('请输入问题');
				return;
			}
			if (!draft.content.trim()) {
				toast.warning('请输入答案');
				return;
			}
		}

		const input: KnowledgeCreateInput = {
			agentId,
			title: draft.title,
			type: draft.type,
			question: draft.question,
			content: draft.content,
			file: draft.file,
			splitterType: draft.type === 'DOCUMENT' ? draft.splitterType : undefined,
		};

		try {
			if (editing?.id !== undefined) {
				// UpdateKnowledgeDTO only carries title and content; documents keep their stored file.
				await updateMutation.mutateAsync({
					agentId,
					id: editing.id,
					data:
						draft.type === 'DOCUMENT'
							? { title: draft.title.trim() }
							: { title: draft.title.trim(), content: draft.content.trim() },
				});
				toast.success('更新成功', {
					description: draft.type === 'DOCUMENT' ? undefined : '内容变更后可点击「重新向量化」同步向量库',
				});
			} else {
				await createMutation.mutateAsync(input);
				toast.success('创建成功', { description: '后台向量存储开始更新，请耐心等待' });
			}
			setEditorOpen(false);
		} catch (error) {
			toast.error(editing ? '更新失败' : '创建失败', {
				description: error instanceof Error ? error.message : '请求未成功，请稍后重试',
			});
		}
	};

	// ── Confirm dialogs ────────────────────────────────────────────────────
	type Pending = { kind: 'delete' | 'recall'; knowledge: AgentKnowledge } | null;
	const [pending, setPending] = useState<Pending>(null);

	const runPending = async () => {
		if (!pending || agentId === null || pending.knowledge.id === undefined) return;
		const id = pending.knowledge.id;
		try {
			if (pending.kind === 'delete') {
				await deleteMutation.mutateAsync({ agentId, id });
				if (rows.length === 1 && pageNum > 1) setPageNum((current) => current - 1);
				toast.success('删除成功', { description: '后台正在清理向量与文件资源' });
			} else {
				const nextRecall = !pending.knowledge.isRecall;
				await recallMutation.mutateAsync({ agentId, id, isRecall: nextRecall });
				toast.success(nextRecall ? '已设为召回' : '已取消召回');
			}
			setPending(null);
		} catch (error) {
			toast.error('操作失败', { description: error instanceof Error ? error.message : '请求未成功' });
		}
	};

	const retryEmbedding = async (knowledge: AgentKnowledge) => {
		if (agentId === null || knowledge.id === undefined) return;
		try {
			const message = await retryMutation.mutateAsync({ agentId, id: knowledge.id });
			toast.success(message);
		} catch (error) {
			toast.error('重试失败', { description: error instanceof Error ? error.message : '请求未成功' });
		}
	};

	// ── Render ─────────────────────────────────────────────────────────────
	if (agentsQuery.isPending) return <CenteredLoader />;

	if (agentsQuery.isError) {
		return (
			<ErrorCard
				title="智能体列表加载失败"
				message={agentsQuery.error instanceof Error ? agentsQuery.error.message : '未知错误'}
				onRetry={() => agentsQuery.refetch()}
			/>
		);
	}

	if (agents.length === 0) {
		return (
			<div className="space-y-6">
				<SectionNav group="knowledge" />
				<PageHeader agentName={null} onCreate={openCreate} createDisabled />
				<Card>
					<CardContent className="pt-6 flex items-start gap-3">
						<AlertTriangle className="w-5 h-5 mt-0.5 shrink-0 text-[#b38340]" />
						<div className="space-y-1.5">
							<p className="text-sm text-[#1c3048]">后端没有返回任何智能体，知识列表无法查询。</p>
							<Link href="/system/agents" className="text-sm text-[#264c79] underline">
								前往系统智能体管理创建智能体
							</Link>
						</div>
					</CardContent>
				</Card>
			</div>
		);
	}

	const hasFilters = debouncedTitle !== '' || typeFilter !== ALL || statusFilter !== ALL;

	return (
		<div className="space-y-6">
			<SectionNav group="knowledge" />
			<PageHeader
				agentName={workingAgent?.name ?? null}
				onCreate={openCreate}
				onRefresh={() => pageQuery.refetch()}
				refreshing={pageQuery.isFetching}
				filtersOpen={filtersOpen}
				onToggleFilters={() => setFiltersOpen((open) => !open)}
			/>

			<Card>
				<CardContent className="pt-6 space-y-4">
					<div className="flex flex-wrap items-center gap-3">
						<Select
							items={agents.map((agent) => ({ value: String(agent.id), label: agent.name || `智能体 #${agent.id}` }))} value={agentId === null ? undefined : String(agentId)}
							onValueChange={handleAgentChange}
						>
							<SelectTrigger className="w-[260px]">
								<SelectValue placeholder="选择智能体" />
							</SelectTrigger>
							<SelectContent>
								{agents.map((agent) => (
									<SelectItem key={agent.id} value={String(agent.id)}>
										{agent.name || `智能体 #${agent.id}`}
									</SelectItem>
								))}
							</SelectContent>
						</Select>

						<div className="relative flex-1 min-w-[240px] max-w-md">
							<Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-[#5a708c]" />
							<Input
								placeholder="搜索知识标题（停止输入后自动查询）"
								className="pl-10"
								value={titleInput}
								onChange={(event) => setTitleInput(event.target.value)}
							/>
						</div>

						<Badge variant="secondary" className="ml-auto">总数 {page.total}</Badge>
					</div>

					{requestedAgentId !== null && workingAgent?.id !== requestedAgentId && (
						<p className="text-xs text-[#b38340]">
							URL 中的智能体 {requestedAgentId} 不存在，已回退为「{workingAgent?.name}」。
						</p>
					)}

					{filtersOpen && (
						<div className="flex flex-wrap items-center gap-3 pt-4 border-t border-[#e2e8f0]">
							<Select items={TYPE_ITEMS} value={typeFilter} onValueChange={handleTypeFilterChange}>
								<SelectTrigger className="w-[160px]">
									<SelectValue placeholder="知识类型" />
								</SelectTrigger>
								<SelectContent>
									<SelectItem value={ALL}>全部类型</SelectItem>
									{TYPE_OPTIONS.map((option) => (
										<SelectItem key={option.value} value={option.value}>{option.label}</SelectItem>
									))}
								</SelectContent>
							</Select>

							<Select items={STATUS_ITEMS} value={statusFilter} onValueChange={handleStatusFilterChange}>
								<SelectTrigger className="w-[160px]">
									<SelectValue placeholder="处理状态" />
								</SelectTrigger>
								<SelectContent>
									<SelectItem value={ALL}>全部状态</SelectItem>
									{STATUS_OPTIONS.map((option) => (
										<SelectItem key={option.value} value={option.value}>{option.label}</SelectItem>
									))}
								</SelectContent>
							</Select>

							{hasFilters && (
								<Button
									variant="outline"
									size="sm"
									onClick={() => {
										setTitleInput('');
										setTypeFilter(ALL);
										setStatusFilter(ALL);
										setPageNum(1);
									}}
								>
									<X className="w-4 h-4 mr-1" />
									清空筛选
								</Button>
							)}
						</div>
					)}
				</CardContent>
			</Card>

			{pageQuery.isError && (
				<ErrorCard
					title="知识列表加载失败"
					message={pageQuery.error instanceof Error ? pageQuery.error.message : '未知错误'}
					onRetry={() => pageQuery.refetch()}
				/>
			)}

			<Card>
				<CardHeader>
					<CardTitle className="flex items-center gap-2">
						<Brain className="w-5 h-5 text-[#796bbb]" />
						知识列表
					</CardTitle>
				</CardHeader>
				<CardContent className="space-y-4">
					{pageQuery.isFetching && rows.length === 0 ? (
						<div className="flex items-center justify-center py-12">
							<Loader2 className="w-7 h-7 animate-spin text-[#35629d]" />
						</div>
					) : rows.length === 0 ? (
						<div className="text-center py-12 space-y-2">
							<p className="text-[#1c3048]">暂无智能体知识</p>
							<p className="text-sm text-[#5a708c]">
								{hasFilters ? '当前筛选条件下没有匹配的知识记录。' : `点击「添加知识」为「${workingAgent?.name}」补充知识资源。`}
							</p>
							{!hasFilters && (
								<Button className="bg-[#35629d] hover:bg-[#264c79] mt-2" onClick={openCreate}>
									<Plus className="w-4 h-4 mr-2" />
									添加知识
								</Button>
							)}
						</div>
					) : (
						<Table>
							<TableHeader>
								<TableRow>
									<TableHead>标题</TableHead>
									<TableHead>类型</TableHead>
									<TableHead>处理状态</TableHead>
									<TableHead>召回状态</TableHead>
									<TableHead>创建时间</TableHead>
									<TableHead className="text-right">操作</TableHead>
								</TableRow>
							</TableHeader>
							<TableBody>
								{rows.map((knowledge) => (
									<TableRow key={knowledge.id}>
										<TableCell className="font-medium text-[#1c3048] max-w-[300px]">
											<div>{knowledge.title}</div>
											{knowledge.question && (
												<div className="text-xs font-normal text-[#5a708c] mt-0.5 truncate">{knowledge.question}</div>
											)}
										</TableCell>
										<TableCell>
											<Badge variant="outline" className={typeBadge(knowledge.type)}>
												{TYPE_LABELS[knowledge.type ?? ''] ?? knowledge.type ?? '未知'}
											</Badge>
										</TableCell>
										<TableCell>
											<div className="max-w-[220px]">
												<Badge variant="outline" className={statusBadge(knowledge.embeddingStatus)}>
													{STATUS_LABELS[knowledge.embeddingStatus ?? ''] ?? knowledge.embeddingStatus ?? '未知'}
												</Badge>
												{knowledge.embeddingStatus === 'FAILED' && knowledge.errorMsg && (
													<div className="text-xs text-[#b9575c] mt-1 truncate" title={knowledge.errorMsg}>
														{knowledge.errorMsg}
													</div>
												)}
											</div>
										</TableCell>
										<TableCell>
											<Badge variant={knowledge.isRecall ? 'default' : 'secondary'}>
												{knowledge.isRecall ? '已召回' : '未召回'}
											</Badge>
										</TableCell>
										<TableCell className="text-sm text-[#5a708c] whitespace-nowrap">
											{knowledge.createdTime ?? '-'}
										</TableCell>
										<TableCell>
											<div className="flex gap-1 justify-end">
												<Button variant="ghost" size="icon" title="编辑知识" aria-label="编辑知识" onClick={() => openEdit(knowledge)}>
													<Pencil className="w-4 h-4" />
												</Button>
												{knowledge.embeddingStatus !== 'PROCESSING' && (
													<Button
														variant="ghost"
														size="icon"
														title="重新向量化"
														aria-label="重新向量化"
														disabled={retryMutation.isPending}
														onClick={() => retryEmbedding(knowledge)}
													>
														<RotateCw className="w-4 h-4 text-[#b38340]" />
													</Button>
												)}
												<Button
													variant="ghost"
													size="sm"
													disabled={recallMutation.isPending}
													onClick={() => setPending({ kind: 'recall', knowledge })}
												>
													{knowledge.isRecall ? '取消召回' : '设为召回'}
												</Button>
												<Button
													variant="ghost"
													size="icon"
													title="删除知识"
													aria-label="删除知识"
													onClick={() => setPending({ kind: 'delete', knowledge })}
												>
													<Trash2 className="w-4 h-4 text-[#b9575c]" />
												</Button>
											</div>
										</TableCell>
									</TableRow>
								))}
							</TableBody>
						</Table>
					)}

					<div className="flex flex-wrap items-center justify-end gap-3 pt-2 border-t border-[#e2e8f0]">
						<Select
							items={PAGE_SIZE_ITEMS} value={String(pageSize)}
							onValueChange={(value) => {
								setPageSize(Number(value));
								setPageNum(1);
							}}
						>
							<SelectTrigger className="w-[120px]">
								<SelectValue />
							</SelectTrigger>
							<SelectContent>
								{PAGE_SIZES.map((size) => (
									<SelectItem key={size} value={String(size)}>{size} 条/页</SelectItem>
								))}
							</SelectContent>
						</Select>
						<div className="flex items-center gap-2">
							<Button
								variant="outline"
								size="icon"
								aria-label="上一页"
								title="上一页"
								disabled={pageNum <= 1 || pageQuery.isFetching}
								onClick={() => setPageNum((current) => Math.max(1, current - 1))}
							>
								<ChevronLeft className="w-4 h-4" />
							</Button>
							<span className="text-sm text-[#5a708c] min-w-[72px] text-center">
								{pageQuery.isFetching ? <Loader2 className="w-4 h-4 animate-spin inline" /> : `${pageNum} / ${lastPage}`}
							</span>
							<Button
								variant="outline"
								size="icon"
								aria-label="下一页"
								title="下一页"
								disabled={pageNum >= lastPage || pageQuery.isFetching}
								onClick={() => setPageNum((current) => Math.min(lastPage, current + 1))}
							>
								<ChevronRight className="w-4 h-4" />
							</Button>
						</div>
					</div>
				</CardContent>
			</Card>

			<Dialog open={editorOpen} onOpenChange={setEditorOpen}>
				<DialogContent className="max-w-2xl">
					<DialogHeader>
						<DialogTitle>{editing ? '编辑知识' : '添加新知识'}</DialogTitle>
						<DialogDescription>
							{editing
								? '后端仅支持更新标题与内容，问题（question）不可修改。'
								: `新知识将归属于「${workingAgent?.name ?? ''}」，创建后由后台异步向量化。`}
						</DialogDescription>
					</DialogHeader>

					<div className="space-y-4 py-1 max-h-[62vh] overflow-y-auto pr-1">
						<div className="space-y-2">
							<Label>知识类型 *</Label>
							<Select
								items={TYPE_ITEMS} value={draft.type}
								onValueChange={(value) =>
									setDraft((prev) => ({ ...prev, type: (value ?? 'DOCUMENT') as KnowledgeType, content: '', question: '', file: null }))
								}
							>
								<SelectTrigger disabled={editing !== null}>
									<SelectValue />
								</SelectTrigger>
								<SelectContent>
									{TYPE_OPTIONS.map((option) => (
										<SelectItem key={option.value} value={option.value}>{option.label}</SelectItem>
									))}
								</SelectContent>
							</Select>
							<p className="text-xs text-[#5a708c]">{TYPE_HINTS[draft.type]}</p>
						</div>

						<div className="space-y-2">
							<Label htmlFor="knowledge-title">知识标题 *</Label>
							<Input
								id="knowledge-title"
								value={draft.title}
								placeholder="为这份知识起一个易于识别的名称"
								onChange={(event) => setDraft((prev) => ({ ...prev, title: event.target.value }))}
							/>
						</div>

						{draft.type === 'DOCUMENT' && !editing && (
							<>
								<div className="space-y-2">
									<Label>分块策略</Label>
									<Select
										items={SPLITTER_ITEMS} value={draft.splitterType}
										onValueChange={(value) => setDraft((prev) => ({ ...prev, splitterType: value ?? 'token' }))}
									>
										<SelectTrigger>
											<SelectValue />
										</SelectTrigger>
										<SelectContent>
											{SPLITTER_OPTIONS.map((option) => (
												<SelectItem key={option.value} value={option.value}>{option.label}</SelectItem>
											))}
										</SelectContent>
									</Select>
									<p className="text-xs text-[#5a708c]">文档向量化时按所选策略分块。</p>
								</div>

								<div className="space-y-2">
									<Label htmlFor="knowledge-file">上传文件 *</Label>
									<Input
										id="knowledge-file"
										type="file"
										accept=".pdf,.doc,.docx,.txt,.md"
										onChange={(event) =>
											setDraft((prev) => ({ ...prev, file: event.target.files?.[0] ?? null }))
										}
									/>
									<p className="text-xs text-[#5a708c]">
										{draft.file ? `已选择：${draft.file.name}（${Math.ceil(draft.file.size / 1024)} KB）` : '支持 pdf / doc / docx / txt / md'}
									</p>
								</div>
							</>
						)}

						{draft.type === 'DOCUMENT' && editing && (
							<p className="text-xs text-[#264c79] bg-[#e8f0f8] rounded-md px-3 py-2">
								文档类型知识不支持在此修改文件内容，如需更换文件请删除后重新创建；这里仅能修改标题。
							</p>
						)}

						{(draft.type === 'QA' || draft.type === 'FAQ') && (
							<>
								<div className="space-y-2">
									<Label htmlFor="knowledge-question">问题 *</Label>
									<Textarea
										id="knowledge-question"
										rows={2}
										value={draft.question}
										placeholder="输入用户可能会问的问题"
										disabled={editing !== null}
										onChange={(event) => setDraft((prev) => ({ ...prev, question: event.target.value }))}
									/>
									{editing && <p className="text-xs text-[#5a708c]">问题不可修改；如需更换问题请删除后重新创建。</p>}
								</div>
								<div className="space-y-2">
									<Label htmlFor="knowledge-answer">答案 *</Label>
									<Textarea
										id="knowledge-answer"
										rows={5}
										value={draft.content}
										placeholder="输入标准答案"
										onChange={(event) => setDraft((prev) => ({ ...prev, content: event.target.value }))}
									/>
								</div>
							</>
						)}
					</div>

					<DialogFooter>
						<Button variant="outline" onClick={() => setEditorOpen(false)}>取消</Button>
						<Button
							className="bg-[#35629d] hover:bg-[#264c79]"
							disabled={createMutation.isPending || updateMutation.isPending}
							onClick={saveDraft}
						>
							{createMutation.isPending || updateMutation.isPending
								? '提交中...'
								: editing
									? '保存更新'
									: '添加并处理'}
						</Button>
					</DialogFooter>
				</DialogContent>
			</Dialog>

			<Dialog open={pending !== null} onOpenChange={(open) => !open && setPending(null)}>
				<DialogContent>
					<DialogHeader>
						<DialogTitle>{pending?.kind === 'delete' ? '删除确认' : '状态变更确认'}</DialogTitle>
						<DialogDescription>
							{pending?.kind === 'delete'
								? `确定要删除知识「${pending.knowledge.title}」吗？此操作不可恢复。`
								: `确定要${pending?.knowledge.isRecall ? '取消召回' : '设为召回'}「${pending?.knowledge.title}」吗？`}
						</DialogDescription>
					</DialogHeader>
					<DialogFooter>
						<Button variant="outline" onClick={() => setPending(null)}>取消</Button>
						<Button
							variant={pending?.kind === 'delete' ? 'destructive' : 'default'}
							disabled={deleteMutation.isPending || recallMutation.isPending}
							onClick={runPending}
						>
							确认
						</Button>
					</DialogFooter>
				</DialogContent>
			</Dialog>
		</div>
	);
}

// ── Presentational helpers ─────────────────────────────────────────────────

function CenteredLoader() {
	return (
		<div className="p-6 max-w-[1600px] mx-auto flex items-center justify-center min-h-[400px]">
			<Loader2 className="w-8 h-8 animate-spin text-[#35629d]" />
		</div>
	);
}

function ErrorCard({ title, message, onRetry }: { title: string; message: string; onRetry: () => void }) {
	return (
		<div className="p-6 max-w-[1600px] mx-auto">
			<Card className="border-red-200 bg-red-50">
				<CardContent className="pt-6">
					<p className="text-red-700">
						{title}：{message}
					</p>
					<Button variant="outline" size="sm" className="mt-2" onClick={onRetry}>
						<RefreshCw className="w-4 h-4 mr-2" />
						重试
					</Button>
				</CardContent>
			</Card>
		</div>
	);
}

function PageHeader({
	agentName,
	onCreate,
	createDisabled,
	refreshing,
	onRefresh,
	filtersOpen,
	onToggleFilters,
}: {
	agentName: string | null;
	onCreate: () => void;
	createDisabled?: boolean;
	refreshing?: boolean;
	onRefresh?: () => void;
	filtersOpen?: boolean;
	onToggleFilters?: () => void;
}) {
	return (
		<div className="flex items-start justify-between gap-4">
			<div>
				<h1 className="text-[clamp(28px,2.7vw,38px)] font-bold text-[#1c3048] tracking-tight">智能体知识</h1>
				<p className="mt-2 text-[#5a708c] text-base leading-relaxed max-w-3xl">
					按智能体维护文档、问答和知识资源，支持上传、向量召回与内容更新。
					{agentName ? `当前智能体：${agentName}。` : ''}
				</p>
			</div>
			<div className="flex gap-2 shrink-0">
				{onToggleFilters && (
					<Button variant={filtersOpen ? 'secondary' : 'outline'} onClick={onToggleFilters}>
						<Filter className="w-4 h-4 mr-2" />
						筛选
					</Button>
				)}
				{onRefresh && (
					<Button variant="outline" onClick={onRefresh} disabled={refreshing}>
						<RefreshCw className={`w-4 h-4 mr-2 ${refreshing ? 'animate-spin' : ''}`} />
						刷新
					</Button>
				)}
				<Button className="bg-[#35629d] hover:bg-[#264c79]" onClick={onCreate} disabled={createDisabled}>
					<Plus className="w-4 h-4 mr-2" />
					添加知识
				</Button>
			</div>
		</div>
	);
}

function typeBadge(type?: string): string {
	switch (type) {
		case 'DOCUMENT':
			return 'bg-blue-50 text-blue-700 border-blue-200';
		case 'QA':
			return 'bg-indigo-50 text-indigo-700 border-indigo-200';
		case 'FAQ':
			return 'bg-cyan-50 text-cyan-700 border-cyan-200';
		default:
			return 'bg-gray-50 text-gray-600 border-gray-200';
	}
}

function statusBadge(status?: string): string {
	switch (status) {
		case 'COMPLETED':
			return 'bg-green-50 text-green-700 border-green-200';
		case 'PROCESSING':
			return 'bg-blue-50 text-blue-700 border-blue-200';
		case 'FAILED':
			return 'bg-red-50 text-red-700 border-red-200';
		case 'PENDING':
			return 'bg-amber-50 text-amber-700 border-amber-200';
		default:
			return 'bg-gray-50 text-gray-600 border-gray-200';
	}
}
