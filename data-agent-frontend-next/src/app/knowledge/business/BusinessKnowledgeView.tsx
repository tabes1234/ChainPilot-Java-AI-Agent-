'use client';

import { useEffect, useMemo, useState } from 'react';
import { useUrlParam } from '@/lib/use-url-param';
import Link from 'next/link';
import { toast } from 'sonner';
import { Loader2, Pencil, Plus, RefreshCw, RotateCw, Search, Trash2 } from 'lucide-react';
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
import { Textarea } from '@/components/ui/textarea';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import type { BusinessKnowledge } from '@/lib/api/businessKnowledge';
import { errorMessage } from '@/lib/api/client';
import {
	useAgents,
	useBusinessKnowledge,
	useCreateBusinessKnowledge,
	useDeleteBusinessKnowledge,
	useRefreshBusinessKnowledgeVectors,
	useRetryBusinessKnowledgeEmbedding,
	useToggleBusinessKnowledgeRecall,
	useUpdateBusinessKnowledge,
} from '@/lib/query/hooks';

const STATUS_LABELS: Record<string, string> = {
	COMPLETED: '已完成',
	PROCESSING: '处理中',
	FAILED: '失败',
	PENDING: '等待中',
};
const PAGE_SIZES = [10, 25, 50];
const PAGE_SIZE_ITEMS: Record<string, string> = Object.fromEntries(PAGE_SIZES.map((size) => [String(size), `${size} 条/页`]));

type Form = { businessTerm: string; description: string; synonyms: string; isRecall: boolean };
const EMPTY_FORM: Form = { businessTerm: '', description: '', synonyms: '', isRecall: true };

export default function BusinessKnowledgeView() {
		const agentsQuery = useAgents();
	const agents = useMemo(() => agentsQuery.data ?? [], [agentsQuery.data]);

	const agentIdParam = useUrlParam('agentId');
	const requestedAgentId = useMemo(() => {
		const raw = Number(agentIdParam);
		return Number.isInteger(raw) && raw > 0 ? raw : null;
	}, [agentIdParam]);

	const [agentFilter, setAgentFilter] = useState<string | null>(null);
	const agentId = useMemo(() => {
		if (agents.length === 0) return null;
		if (agentFilter) return Number(agentFilter);
		if (requestedAgentId && agents.some((agent) => agent.id === requestedAgentId)) return requestedAgentId;
		return agents.find((agent) => (agent.id ?? 0) > 0)?.id ?? null;
	}, [agents, agentFilter, requestedAgentId]);

	const [keywordInput, setKeywordInput] = useState('');
	const [keyword, setKeyword] = useState('');
	const [page, setPage] = useState(1);
	const [pageSize, setPageSize] = useState(10);

	useEffect(() => {
		const timer = setTimeout(() => {
			setKeyword(keywordInput.trim());
			setPage(1);
		}, 400);
		return () => clearTimeout(timer);
	}, [keywordInput]);

	const listQuery = useBusinessKnowledge(agentId ?? 0, keyword || undefined, { enabled: agentId !== null });
	const items = useMemo(() => listQuery.data ?? [], [listQuery.data]);

	const createMutation = useCreateBusinessKnowledge();
	const updateMutation = useUpdateBusinessKnowledge();
	const deleteMutation = useDeleteBusinessKnowledge();
	const recallMutation = useToggleBusinessKnowledgeRecall();
	const retryMutation = useRetryBusinessKnowledgeEmbedding();
	const refreshMutation = useRefreshBusinessKnowledgeVectors();

	const [editorOpen, setEditorOpen] = useState(false);
	const [editing, setEditing] = useState<BusinessKnowledge | null>(null);
	const [form, setForm] = useState<Form>(EMPTY_FORM);
	const [formError, setFormError] = useState<string | null>(null);
	const [pendingDelete, setPendingDelete] = useState<BusinessKnowledge | null>(null);
	const [refreshConfirm, setRefreshConfirm] = useState(false);
	const [retryingId, setRetryingId] = useState<number | null>(null);

	const totalPages = Math.max(1, Math.ceil(items.length / pageSize));
	const currentPage = Math.min(page, totalPages);
	const pageRows = items.slice((currentPage - 1) * pageSize, currentPage * pageSize);

	const openCreate = () => {
		if (agentId === null) {
			toast.error('请先选择智能体');
			return;
		}
		setEditing(null);
		setForm(EMPTY_FORM);
		setFormError(null);
		setEditorOpen(true);
	};

	const openEdit = (item: BusinessKnowledge) => {
		setEditing(item);
		setForm({
			businessTerm: item.businessTerm ?? '',
			description: item.description ?? '',
			synonyms: item.synonyms ?? '',
			isRecall: item.isRecall ?? false,
		});
		setFormError(null);
		setEditorOpen(true);
	};

	const save = async () => {
		if (!form.businessTerm.trim() || !form.description.trim()) {
			setFormError('业务名词与描述为必填项');
			return;
		}
		if (agentId === null) return;
		setFormError(null);
		try {
			if (editing?.id !== undefined) {
				await updateMutation.mutateAsync({
					agentId,
					id: editing.id,
					data: {
						agentId,
						businessTerm: form.businessTerm.trim(),
						description: form.description.trim(),
						synonyms: form.synonyms.trim(),
					},
				});
				toast.success('业务术语已更新', { description: '后端会重新向量化该术语' });
			} else {
				await createMutation.mutateAsync({
					agentId,
					businessTerm: form.businessTerm.trim(),
					description: form.description.trim(),
					synonyms: form.synonyms.trim(),
					isRecall: form.isRecall,
				});
				toast.success('业务术语已创建', { description: '后端已同步向量化状态' });
			}
			setEditorOpen(false);
		} catch (error) {
			const message = errorMessage(error);
			setFormError(message);
			toast.error(editing ? '更新失败' : '创建失败', { description: message });
		}
	};

	const toggleRecall = async (item: BusinessKnowledge) => {
		if (item.id === undefined || agentId === null) return;
		try {
			await recallMutation.mutateAsync({ agentId, id: item.id, isRecall: !item.isRecall });
			toast.success(item.isRecall ? '已取消召回' : '已设为召回');
		} catch (error) {
			toast.error('操作失败', { description: errorMessage(error) });
		}
	};

	const retry = async (item: BusinessKnowledge) => {
		if (item.id === undefined || agentId === null) return;
		if (!item.isRecall) {
			toast.warning('后端要求知识处于召回状态才能重试向量化');
			return;
		}
		setRetryingId(item.id);
		try {
			await retryMutation.mutateAsync({ agentId, id: item.id });
			toast.success('重试请求已发送');
		} catch (error) {
			toast.error('重试失败', { description: errorMessage(error) });
		} finally {
			setRetryingId(null);
		}
	};

	const runDelete = async () => {
		if (!pendingDelete?.id || agentId === null) return;
		try {
			await deleteMutation.mutateAsync({ agentId, id: pendingDelete.id });
			toast.success('业务术语已删除');
			setPendingDelete(null);
		} catch (error) {
			toast.error('删除失败', { description: errorMessage(error) });
		}
	};

	const refreshVectors = async () => {
		if (agentId === null) return;
		try {
			await refreshMutation.mutateAsync(agentId);
			toast.success('向量库同步请求已提交');
			setRefreshConfirm(false);
		} catch (error) {
			toast.error('同步失败', { description: errorMessage(error) });
		}
	};

	if (agentsQuery.isPending) return <div className="p-6"><LoadingState label="加载智能体…" /></div>;
	if (agentsQuery.isError) {
		return <div className="p-6"><ErrorState message={errorMessage(agentsQuery.error, '智能体列表加载失败')} onRetry={() => agentsQuery.refetch()} /></div>;
	}

	return (
		<div className="space-y-6">
			<SectionNav group="knowledge" />
			<PageHeader
				title="业务术语"
				breadcrumb="知识与智能体"
				description="维护业务术语、指标口径与同义词，提升智能体对业务问题的理解与召回准确率。"
				actions={
					<>
						<Button variant="outline" onClick={() => listQuery.refetch()} disabled={listQuery.isFetching}>
							<RefreshCw className={`mr-2 h-4 w-4 ${listQuery.isFetching ? 'animate-spin' : ''}`} />
							刷新
						</Button>
						<Button variant="outline" onClick={() => setRefreshConfirm(true)} disabled={agentId === null || refreshMutation.isPending}>
							<RotateCw className="mr-2 h-4 w-4" />
							同步到向量库
						</Button>
						<Button className="bg-[#35629d] hover:bg-[#264c79]" onClick={openCreate} disabled={agentId === null}>
							<Plus className="mr-2 h-4 w-4" />
							添加业务术语
						</Button>
					</>
				}
			/>

			{agents.length === 0 ? (
				<Card><CardContent className="pt-6">
					<EmptyState title="没有可用的智能体" description="业务术语必须归属于智能体。" action={<Link href="/agent/new"><Button>新建智能体</Button></Link>} />
				</CardContent></Card>
			) : (
				<Card>
					<CardContent className="space-y-4 pt-6">
						<div className="flex flex-wrap items-center gap-3">
							<Select
								items={Object.fromEntries(agents.map((agent) => [String(agent.id), agent.name || `智能体 #${agent.id}`]))}
								value={agentId === null ? undefined : String(agentId)}
								onValueChange={(value) => { if (value) { setAgentFilter(value); setPage(1); } }}
							>
								<SelectTrigger className="w-[250px]"><SelectValue placeholder="选择智能体" /></SelectTrigger>
								<SelectContent>
									{agents.map((agent) => <SelectItem key={agent.id} value={String(agent.id)}>{agent.name || `智能体 #${agent.id}`}</SelectItem>)}
								</SelectContent>
							</Select>
							<div className="relative min-w-[240px] max-w-md flex-1">
								<Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-[#5a708c]" />
								<Input placeholder="搜索业务名词 / 描述 / 同义词" className="pl-10" value={keywordInput} onChange={(event) => setKeywordInput(event.target.value)} />
							</div>
							<Badge variant="secondary">总数 {items.length}</Badge>
							{requestedAgentId && agentId !== requestedAgentId && !agentFilter && (
								<p className="w-full text-xs text-[#b38340]">URL 中的智能体 {requestedAgentId} 不存在，已回退为「{agents.find((a) => a.id === agentId)?.name}」。</p>
							)}
						</div>
					</CardContent>

					<CardHeader className="pt-0"><CardTitle className="text-lg">术语列表</CardTitle></CardHeader>
					<CardContent>
						{listQuery.isPending ? (
							<LoadingState label="加载业务术语…" />
						) : listQuery.isError ? (
							<ErrorState message={errorMessage(listQuery.error, '加载业务术语失败')} onRetry={() => listQuery.refetch()} />
						) : items.length === 0 ? (
							<EmptyState
								title={keyword ? '没有匹配的业务术语' : '暂无业务术语'}
								description={keyword ? '尝试更换关键词。' : '添加术语，让智能体理解业务口径。'}
								action={!keyword ? <Button onClick={openCreate}><Plus className="mr-2 h-4 w-4" />添加业务术语</Button> : undefined}
							/>
						) : (
							<>
								<div className="overflow-x-auto">
									<Table>
										<TableHeader>
											<TableRow>
												<TableHead className="w-[64px]">ID</TableHead>
												<TableHead>业务名词</TableHead>
												<TableHead>描述</TableHead>
												<TableHead>同义词</TableHead>
												<TableHead>向量化状态</TableHead>
												<TableHead>召回状态</TableHead>
												<TableHead>创建时间</TableHead>
												<TableHead className="text-right">操作</TableHead>
											</TableRow>
										</TableHeader>
										<TableBody>
											{pageRows.map((item) => (
												<TableRow key={item.id}>
													<TableCell className="font-mono text-xs text-[#5a708c]">{item.id}</TableCell>
													<TableCell className="font-medium text-[#1c3048]">{item.businessTerm}</TableCell>
													<TableCell className="max-w-[280px] truncate text-sm text-[#5a708c]" title={item.description}>{item.description}</TableCell>
													<TableCell className="max-w-[160px] truncate text-sm text-[#5a708c]" title={item.synonyms}>{item.synonyms || '—'}</TableCell>
													<TableCell>
														<div className="max-w-[200px]">
															<Badge variant="outline" className={statusClass(item.embeddingStatus)}>
																{STATUS_LABELS[item.embeddingStatus ?? ''] ?? item.embeddingStatus ?? '未知'}
															</Badge>
															{item.embeddingStatus === 'FAILED' && item.errorMsg && (
																<p className="mt-1 truncate text-xs text-[#b9575c]" title={item.errorMsg}>{item.errorMsg}</p>
															)}
														</div>
													</TableCell>
													<TableCell>
														<Badge variant={item.isRecall ? 'default' : 'secondary'}>{item.isRecall ? '召回中' : '未召回'}</Badge>
													</TableCell>
													<TableCell className="whitespace-nowrap text-xs text-[#5a708c]">{item.createdTime ?? '—'}</TableCell>
													<TableCell>
														<div className="flex justify-end gap-0.5">
															<Button variant="ghost" size="icon" title="编辑" aria-label="编辑" onClick={() => openEdit(item)}>
																<Pencil className="h-4 w-4" />
															</Button>
															{item.embeddingStatus !== 'PROCESSING' && (
																<Button
																	variant="ghost"
																	size="icon"
																	title="重试向量化"
																	aria-label="重试向量化"
																	disabled={retryingId === item.id}
																	onClick={() => retry(item)}
																>
																	{retryingId === item.id ? <Loader2 className="h-4 w-4 animate-spin" /> : <RotateCw className="h-4 w-4 text-[#b38340]" />}
																</Button>
															)}
															<Button variant="ghost" size="sm" disabled={recallMutation.isPending} onClick={() => toggleRecall(item)}>
																{item.isRecall ? '取消召回' : '设为召回'}
															</Button>
															<Button variant="ghost" size="icon" title="删除" aria-label="删除" onClick={() => setPendingDelete(item)}>
																<Trash2 className="h-4 w-4 text-[#b9575c]" />
															</Button>
														</div>
													</TableCell>
												</TableRow>
											))}
										</TableBody>
									</Table>
								</div>

								<div className="flex flex-wrap items-center justify-end gap-3 border-t border-[#e6eef7] pt-3">
									<Select items={PAGE_SIZE_ITEMS} value={String(pageSize)} onValueChange={(value) => { setPageSize(Number(value ?? 10)); setPage(1); }}>
										<SelectTrigger className="w-[120px]"><SelectValue /></SelectTrigger>
										<SelectContent>
											{PAGE_SIZES.map((size) => <SelectItem key={size} value={String(size)}>{size} 条/页</SelectItem>)}
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
			)}

			<Dialog open={editorOpen} onOpenChange={setEditorOpen}>
				<DialogContent>
					<DialogHeader>
						<DialogTitle>{editing ? '编辑业务术语' : '添加业务术语'}</DialogTitle>
						<DialogDescription>保存后端会重新向量化（更新时不可修改召回状态）。</DialogDescription>
					</DialogHeader>
					<div className="space-y-4 py-1">
						<div className="space-y-2">
							<Label htmlFor="bk-term">业务名词 *</Label>
							<Input id="bk-term" value={form.businessTerm} onChange={(event) => setForm({ ...form, businessTerm: event.target.value })} />
						</div>
						<div className="space-y-2">
							<Label htmlFor="bk-desc">描述 *</Label>
							<Textarea id="bk-desc" rows={4} value={form.description} onChange={(event) => setForm({ ...form, description: event.target.value })} />
						</div>
						<div className="space-y-2">
							<Label htmlFor="bk-syn">同义词（逗号分隔）</Label>
							<Input id="bk-syn" value={form.synonyms} onChange={(event) => setForm({ ...form, synonyms: event.target.value })} />
						</div>
						{!editing && (
							<label className="flex cursor-pointer items-center gap-2 text-sm text-[#33506b]">
								<input
									type="checkbox"
									checked={form.isRecall}
									onChange={(event) => setForm({ ...form, isRecall: event.target.checked })}
									className="h-4 w-4"
								/>
								创建后参与召回（后端默认）
							</label>
						)}
						{formError && <p className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-[#8f3b45]">{formError}</p>}
					</div>
					<DialogFooter>
						<Button variant="outline" onClick={() => setEditorOpen(false)}>取消</Button>
						<Button className="bg-[#35629d] hover:bg-[#264c79]" disabled={createMutation.isPending || updateMutation.isPending} onClick={save}>
							{(createMutation.isPending || updateMutation.isPending) && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
							保存
						</Button>
					</DialogFooter>
				</DialogContent>
			</Dialog>

			<Dialog open={pendingDelete !== null} onOpenChange={(open) => !open && setPendingDelete(null)}>
				<DialogContent>
					<DialogHeader>
						<DialogTitle>删除确认</DialogTitle>
						<DialogDescription>确定要删除业务术语「{pendingDelete?.businessTerm}」吗？后端会同时清理向量数据。</DialogDescription>
					</DialogHeader>
					<DialogFooter>
						<Button variant="outline" onClick={() => setPendingDelete(null)}>取消</Button>
						<Button variant="destructive" disabled={deleteMutation.isPending} onClick={runDelete}>确认删除</Button>
					</DialogFooter>
				</DialogContent>
			</Dialog>

			<Dialog open={refreshConfirm} onOpenChange={setRefreshConfirm}>
				<DialogContent>
					<DialogHeader>
						<DialogTitle>同步到向量库</DialogTitle>
						<DialogDescription>
							后端会根据当前所有「召回中」的术语重建该智能体的向量数据，并清理旧向量。确定继续吗？
						</DialogDescription>
					</DialogHeader>
					<DialogFooter>
						<Button variant="outline" onClick={() => setRefreshConfirm(false)}>取消</Button>
						<Button disabled={refreshMutation.isPending} onClick={refreshVectors}>确认同步</Button>
					</DialogFooter>
				</DialogContent>
			</Dialog>
		</div>
	);
}

function statusClass(status?: string) {
	switch (status) {
		case 'COMPLETED':
			return 'border-green-200 bg-green-50 text-green-700';
		case 'PROCESSING':
			return 'border-blue-200 bg-blue-50 text-blue-700';
		case 'FAILED':
			return 'border-red-200 bg-red-50 text-red-700';
		default:
			return 'border-amber-200 bg-amber-50 text-amber-700';
	}
}
