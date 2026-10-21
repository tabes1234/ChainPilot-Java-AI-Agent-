'use client';

import { useEffect, useMemo, useRef, useState } from 'react';
import { useUrlParam } from '@/lib/use-url-param';
import Link from 'next/link';
import { toast } from 'sonner';
import {
	Download,
	FileSpreadsheet,
	Loader2,
	Pencil,
	Plus,
	RefreshCw,
	Search,
	Trash2,
	Upload,
} from 'lucide-react';
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
	downloadSemanticModelTemplate,
	type BatchImportResult,
	type SemanticModel,
} from '@/lib/api/semanticModel';
import {
	useAgents,
	useBatchDeleteSemanticModels,
	useCreateSemanticModel,
	useDeleteSemanticModel,
	useImportSemanticModelExcel,
	useSemanticModels,
	useToggleSemanticModels,
	useUpdateSemanticModel,
} from '@/lib/query/hooks';

type ModelForm = {
	tableName: string;
	columnName: string;
	businessName: string;
	dataType: string;
	synonyms: string;
	businessDescription: string;
	columnComment: string;
};

const EMPTY_FORM: ModelForm = {
	tableName: '',
	columnName: '',
	businessName: '',
	dataType: '',
	synonyms: '',
	businessDescription: '',
	columnComment: '',
};

const PAGE_SIZES = [10, 20, 50];
const PAGE_SIZE_ITEMS: Record<string, string> = Object.fromEntries(PAGE_SIZES.map((size) => [String(size), `${size} 条/页`]));

export default function SemanticModelsView() {
		const agentsQuery = useAgents();
	const agents = useMemo(() => agentsQuery.data ?? [], [agentsQuery.data]);

	const agentIdParam = useUrlParam('agentId');
	const requestedAgentId = useMemo(() => {
		const raw = Number(agentIdParam);
		return Number.isInteger(raw) && raw > 0 ? raw : null;
	}, [agentIdParam]);

	const agentId = useMemo(() => {
		if (agents.length === 0) return null;
		if (requestedAgentId && agents.some((agent) => agent.id === requestedAgentId)) return requestedAgentId;
		return agents.find((agent) => (agent.id ?? 0) > 0)?.id ?? null;
	}, [agents, requestedAgentId]);

	const [agentFilter, setAgentFilter] = useState<string | null>(null);
	const activeAgentId = agentFilter !== null ? Number(agentFilter) : agentId;

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

	// keyword wins over agentId on the backend, so a search is explicitly global.
	const globalsearch = keyword.length > 0;
	const modelsQuery = useSemanticModels(globalsearch ? undefined : activeAgentId ?? undefined, keyword || undefined, {
		enabled: globalsearch || activeAgentId !== null,
	});
	const models = useMemo(() => modelsQuery.data ?? [], [modelsQuery.data]);

	const createMutation = useCreateSemanticModel();
	const updateMutation = useUpdateSemanticModel();
	const deleteMutation = useDeleteSemanticModel();
	const batchDeleteMutation = useBatchDeleteSemanticModels();
	const toggleMutation = useToggleSemanticModels();
	const importMutation = useImportSemanticModelExcel();

	// ── UI state ───────────────────────────────────────────────────────────
	const [selectedIds, setSelectedIds] = useState<number[]>([]);
	const [editorOpen, setEditorOpen] = useState(false);
	const [editing, setEditing] = useState<SemanticModel | null>(null);
	const [form, setForm] = useState<ModelForm>(EMPTY_FORM);
	const [formError, setFormError] = useState<string | null>(null);
	const [pendingDelete, setPendingDelete] = useState<SemanticModel | null>(null);
	const [batchConfirmOpen, setBatchConfirmOpen] = useState(false);
	const [toggleTarget, setToggleTarget] = useState<SemanticModel | null>(null);
	const [importOpen, setImportOpen] = useState(false);
	const [importFile, setImportFile] = useState<File | null>(null);
	const [importResult, setImportResult] = useState<BatchImportResult | null>(null);
	const fileRef = useRef<HTMLInputElement>(null);

	const totalPages = Math.max(1, Math.ceil(models.length / pageSize));
	const currentPage = Math.min(page, totalPages);
	const pageRows = models.slice((currentPage - 1) * pageSize, currentPage * pageSize);
	const pageIds = pageRows.map((model) => model.id).filter((id): id is number => id !== undefined);
	const allPageSelected = pageIds.length > 0 && pageIds.every((id) => selectedIds.includes(id));

	const toggleSelectAll = (checked: boolean) => {
		setSelectedIds((prev) =>
			checked ? [...new Set([...prev, ...pageIds])] : prev.filter((id) => !pageIds.includes(id)),
		);
	};

	const toggleSelect = (id: number, checked: boolean) => {
		setSelectedIds((prev) => (checked ? [...new Set([...prev, id])] : prev.filter((item) => item !== id)));
	};

	const openCreate = () => {
		if (activeAgentId === null) {
			toast.error('请先选择智能体');
			return;
		}
		setEditing(null);
		setForm(EMPTY_FORM);
		setFormError(null);
		setEditorOpen(true);
	};

	const openEdit = (model: SemanticModel) => {
		setEditing(model);
		setForm({
			tableName: model.tableName ?? '',
			columnName: model.columnName ?? '',
			businessName: model.businessName ?? '',
			dataType: model.dataType ?? '',
			synonyms: model.synonyms ?? '',
			businessDescription: model.businessDescription ?? '',
			columnComment: model.columnComment ?? '',
		});
		setFormError(null);
		setEditorOpen(true);
	};

	const saveModel = async () => {
		if (!form.tableName.trim() || !form.columnName.trim() || !form.businessName.trim() || !form.dataType.trim()) {
			setFormError('表名、字段名、业务名称和数据类型为必填项');
			return;
		}
		if (activeAgentId === null) {
			setFormError('请先选择智能体');
			return;
		}
		setFormError(null);
		try {
			if (editing?.id !== undefined) {
				await updateMutation.mutateAsync({
					id: editing.id,
					model: {
						tableName: form.tableName.trim(),
						columnName: form.columnName.trim(),
						businessName: form.businessName.trim(),
						dataType: form.dataType.trim(),
						synonyms: form.synonyms.trim(),
						businessDescription: form.businessDescription.trim(),
						columnComment: form.columnComment.trim(),
					},
				});
				toast.success('语义模型已更新');
			} else {
				await createMutation.mutateAsync({
					agentId: activeAgentId,
					tableName: form.tableName.trim(),
					columnName: form.columnName.trim(),
					businessName: form.businessName.trim(),
					dataType: form.dataType.trim(),
					synonyms: form.synonyms.trim(),
					businessDescription: form.businessDescription.trim(),
					columnComment: form.columnComment.trim(),
				});
				toast.success('语义模型已创建');
			}
			setEditorOpen(false);
		} catch (error) {
			const message = errorMessage(error);
			setFormError(message);
			toast.error(editing ? '更新失败' : '创建失败', { description: message });
		}
	};

	const runDelete = async () => {
		if (!pendingDelete?.id) return;
		try {
			await deleteMutation.mutateAsync(pendingDelete.id);
			toast.success('语义模型已删除');
			setSelectedIds((prev) => prev.filter((id) => id !== pendingDelete.id));
			setPendingDelete(null);
		} catch (error) {
			toast.error('删除失败', { description: errorMessage(error) });
		}
	};

	const runBatchDelete = async () => {
		if (selectedIds.length === 0) return;
		try {
			await batchDeleteMutation.mutateAsync(selectedIds);
			toast.success(`已删除 ${selectedIds.length} 条语义模型`);
			setSelectedIds([]);
			setBatchConfirmOpen(false);
		} catch (error) {
			toast.error('批量删除失败', { description: errorMessage(error) });
		}
	};

	const runToggle = async () => {
		if (!toggleTarget?.id) return;
		const enable = toggleTarget.status !== 1;
		try {
			await toggleMutation.mutateAsync({ ids: [toggleTarget.id], enabled: enable });
			toast.success(enable ? '已启用' : '已停用');
			setToggleTarget(null);
		} catch (error) {
			toast.error('操作失败', { description: errorMessage(error) });
		}
	};

	const runImport = async () => {
		if (activeAgentId === null) {
			toast.error('请先选择智能体');
			return;
		}
		if (!importFile) {
			toast.warning('请选择 Excel 文件');
			return;
		}
		setImportResult(null);
		try {
			const result = await importMutation.mutateAsync({ agentId: activeAgentId, file: importFile });
			setImportResult(result);
			toast.success('导入完成', {
				description: `成功 ${result.successCount} 条，失败 ${result.failCount} 条`,
			});
		} catch (error) {
			toast.error('导入失败', { description: errorMessage(error) });
		}
	};

	const downloadTemplate = async () => {
		try {
			await downloadSemanticModelTemplate();
		} catch (error) {
			toast.error('模板下载失败', { description: errorMessage(error) });
		}
	};

	const busy = deleteMutation.isPending || batchDeleteMutation.isPending || toggleMutation.isPending;

	if (agentsQuery.isPending) return <div className="p-6"><LoadingState label="加载智能体…" /></div>;
	if (agentsQuery.isError) {
		return (
			<div className="p-6">
				<ErrorState message={errorMessage(agentsQuery.error, '智能体列表加载失败')} onRetry={() => agentsQuery.refetch()} />
			</div>
		);
	}

	return (
		<div className="space-y-6">
			<SectionNav group="knowledge" />
			<PageHeader
				title="语义模型"
				breadcrumb="知识与智能体"
				description="维护表字段的业务名称、同义词与口径说明，供智能体生成 SQL 时参考。"
				actions={
					<>
						<Button variant="outline" onClick={() => modelsQuery.refetch()} disabled={modelsQuery.isFetching}>
							<RefreshCw className={`mr-2 h-4 w-4 ${modelsQuery.isFetching ? 'animate-spin' : ''}`} />
							刷新
						</Button>
						<Button variant="outline" onClick={() => { setImportOpen(true); setImportResult(null); }}>
							<Upload className="mr-2 h-4 w-4" />
							批量导入
						</Button>
						<Button className="bg-[#35629d] hover:bg-[#264c79]" onClick={openCreate} disabled={activeAgentId === null}>
							<Plus className="mr-2 h-4 w-4" />
							添加语义模型
						</Button>
					</>
				}
			/>

			{agents.length === 0 ? (
				<Card>
					<CardContent className="pt-6">
						<EmptyState
							title="没有可用的智能体"
							description="语义模型必须归属于智能体，请先创建智能体。"
							action={<Link href="/agent/new"><Button>新建智能体</Button></Link>}
						/>
					</CardContent>
				</Card>
			) : (
				<>
					<Card>
						<CardContent className="flex flex-wrap items-center gap-3 pt-6">
							<Select
								items={Object.fromEntries(agents.map((agent) => [String(agent.id), agent.name || `智能体 #${agent.id}`]))}
								value={String(activeAgentId ?? '') || undefined}
								onValueChange={(value) => { if (value) { setAgentFilter(value); setPage(1); setSelectedIds([]); } }}
							>
								<SelectTrigger className="w-[250px]"><SelectValue placeholder="选择智能体" /></SelectTrigger>
								<SelectContent>
									{agents.map((agent) => (
										<SelectItem key={agent.id} value={String(agent.id)}>{agent.name || `智能体 #${agent.id}`}</SelectItem>
									))}
								</SelectContent>
							</Select>

							<div className="relative min-w-[240px] max-w-md flex-1">
								<Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-[#5a708c]" />
								<Input
									placeholder="搜索表名 / 字段名 / 业务名称"
									className="pl-10"
									value={keywordInput}
									onChange={(event) => setKeywordInput(event.target.value)}
								/>
							</div>

							<Badge variant="secondary">总数 {models.length}</Badge>
							{selectedIds.length > 0 && (
								<Button
									variant="destructive"
									size="sm"
									className="ml-auto"
									onClick={() => setBatchConfirmOpen(true)}
								>
									<Trash2 className="mr-1 h-4 w-4" />
									批量删除 ({selectedIds.length})
								</Button>
							)}
							{(requestedAgentId && agentId !== requestedAgentId) && (
								<p className="w-full text-xs text-[#b38340]">
									URL 中的智能体 {requestedAgentId} 不存在，已回退为「{agents.find((a) => a.id === agentId)?.name}」。
								</p>
							)}
							{globalsearch && (
								<p className="w-full text-xs text-[#5a708c]">后端关键词搜索会忽略智能体参数，当前结果来自全部智能体。</p>
							)}
						</CardContent>
					</Card>

					<Card>
						<CardHeader className="flex flex-row items-center justify-between">
							<CardTitle className="text-lg">语义模型列表</CardTitle>
							<span className="text-sm text-[#5a708c]">当前 {models.length} 条</span>
						</CardHeader>
						<CardContent>
							{modelsQuery.isPending ? (
								<LoadingState label="加载语义模型…" />
							) : modelsQuery.isError ? (
								<ErrorState message={errorMessage(modelsQuery.error, '加载语义模型失败')} onRetry={() => modelsQuery.refetch()} />
							) : models.length === 0 ? (
								<EmptyState
									title={globalsearch ? '没有匹配的语义模型' : '暂无语义模型'}
									description={globalsearch ? '尝试更换关键词。' : '点击「添加语义模型」录入字段的业务含义。'}
									action={!globalsearch ? <Button onClick={openCreate}><Plus className="mr-2 h-4 w-4" />添加语义模型</Button> : undefined}
								/>
							) : (
								<>
									<div className="overflow-x-auto">
										<Table>
											<TableHeader>
												<TableRow>
													<TableHead className="w-10">
														<Checkbox checked={allPageSelected} onCheckedChange={(checked) => toggleSelectAll(Boolean(checked))} aria-label="全选当前页" />
													</TableHead>
													<TableHead>表名</TableHead>
													<TableHead>字段名</TableHead>
													<TableHead>业务名称</TableHead>
													<TableHead>同义词</TableHead>
													<TableHead>数据类型</TableHead>
													<TableHead>状态</TableHead>
													<TableHead>创建时间</TableHead>
													<TableHead className="text-right">操作</TableHead>
												</TableRow>
											</TableHeader>
											<TableBody>
												{pageRows.map((model) => (
													<TableRow key={model.id}>
														<TableCell>
															<Checkbox
																checked={selectedIds.includes(model.id!)}
																onCheckedChange={(checked) => model.id !== undefined && toggleSelect(model.id, Boolean(checked))}
																aria-label={`选择 ${model.businessName}`}
															/>
														</TableCell>
														<TableCell className="font-mono text-xs">{model.tableName}</TableCell>
														<TableCell className="font-mono text-xs">{model.columnName}</TableCell>
														<TableCell className="font-medium text-[#1c3048]">{model.businessName}</TableCell>
														<TableCell className="max-w-[180px] truncate text-sm text-[#5a708c]" title={model.synonyms}>
															{model.synonyms || '—'}
														</TableCell>
														<TableCell className="text-sm">{model.dataType}</TableCell>
														<TableCell>
															<Badge variant="outline" className={model.status === 1 ? 'border-green-200 bg-green-50 text-green-700' : 'border-gray-200 bg-gray-50 text-gray-600'}>
																{model.status === 1 ? '启用' : '停用'}
															</Badge>
														</TableCell>
														<TableCell className="whitespace-nowrap text-xs text-[#5a708c]">{model.createdTime ?? model.updateTime ?? '—'}</TableCell>
														<TableCell>
															<div className="flex justify-end gap-0.5">
																<Button variant="ghost" size="icon" title="编辑" aria-label="编辑" onClick={() => openEdit(model)}>
																	<Pencil className="h-4 w-4" />
																</Button>
																<Button
																	variant="ghost"
																	size="sm"
																	disabled={busy}
																	onClick={() => setToggleTarget(model)}
																>
																	{model.status === 1 ? '停用' : '启用'}
																</Button>
																<Button variant="ghost" size="icon" title="删除" aria-label="删除" onClick={() => setPendingDelete(model)}>
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
				</>
			)}

			{/* Create / edit dialog */}
			<Dialog open={editorOpen} onOpenChange={setEditorOpen}>
				<DialogContent className="max-w-2xl">
					<DialogHeader>
						<DialogTitle>{editing ? '编辑语义模型' : '添加语义模型'}</DialogTitle>
						<DialogDescription>
							保存后端要求智能体已绑定数据源；字段名需与物理表一致。
						</DialogDescription>
					</DialogHeader>
					<div className="max-h-[62vh] space-y-4 overflow-y-auto py-1 pr-1">
						<div className="grid gap-4 md:grid-cols-2">
							<div className="space-y-2">
								<Label htmlFor="sm-table">表名 *</Label>
								<Input id="sm-table" value={form.tableName} onChange={(event) => setForm({ ...form, tableName: event.target.value })} />
							</div>
							<div className="space-y-2">
								<Label htmlFor="sm-column">字段名 *</Label>
								<Input id="sm-column" value={form.columnName} onChange={(event) => setForm({ ...form, columnName: event.target.value })} />
							</div>
							<div className="space-y-2">
								<Label htmlFor="sm-business">业务名称 *</Label>
								<Input id="sm-business" value={form.businessName} onChange={(event) => setForm({ ...form, businessName: event.target.value })} />
							</div>
							<div className="space-y-2">
								<Label htmlFor="sm-type">数据类型 *</Label>
								<Input id="sm-type" value={form.dataType} onChange={(event) => setForm({ ...form, dataType: event.target.value })} />
							</div>
						</div>
						<div className="space-y-2">
							<Label htmlFor="sm-synonyms">同义词（逗号分隔）</Label>
							<Input id="sm-synonyms" value={form.synonyms} onChange={(event) => setForm({ ...form, synonyms: event.target.value })} />
						</div>
						<div className="space-y-2">
							<Label htmlFor="sm-desc">业务描述</Label>
							<Textarea id="sm-desc" rows={3} value={form.businessDescription} onChange={(event) => setForm({ ...form, businessDescription: event.target.value })} />
						</div>
						<div className="space-y-2">
							<Label htmlFor="sm-comment">字段注释</Label>
							<Textarea id="sm-comment" rows={2} value={form.columnComment} onChange={(event) => setForm({ ...form, columnComment: event.target.value })} />
						</div>
						{formError && <p className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-[#8f3b45]">{formError}</p>}
					</div>
					<DialogFooter>
						<Button variant="outline" onClick={() => setEditorOpen(false)}>取消</Button>
						<Button
							className="bg-[#35629d] hover:bg-[#264c79]"
							disabled={createMutation.isPending || updateMutation.isPending}
							onClick={saveModel}
						>
							{(createMutation.isPending || updateMutation.isPending) && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
							保存
						</Button>
					</DialogFooter>
				</DialogContent>
			</Dialog>

			{/* Import dialog */}
			<Dialog open={importOpen} onOpenChange={(open) => { setImportOpen(open); if (!open) setImportResult(null); }}>
				<DialogContent>
					<DialogHeader>
						<DialogTitle>批量导入语义模型</DialogTitle>
						<DialogDescription>支持 .xlsx / .xls，表头需与模板一致；导入归属当前智能体。</DialogDescription>
					</DialogHeader>
					<div className="space-y-3 py-2">
						<div className="flex flex-wrap items-center gap-2">
							<Button variant="outline" size="sm" onClick={downloadTemplate}>
								<Download className="mr-1 h-4 w-4" />下载模板
							</Button>
							<input
								ref={fileRef}
								type="file"
								accept=".xlsx,.xls"
								className="hidden"
								onChange={(event) => setImportFile(event.target.files?.[0] ?? null)}
							/>
							<Button variant="outline" size="sm" onClick={() => fileRef.current?.click()}>
								<FileSpreadsheet className="mr-1 h-4 w-4" />选择文件
							</Button>
							<span className="text-sm text-[#5a708c]">{importFile ? importFile.name : '尚未选择文件'}</span>
						</div>
						{importResult && (
							<div className="space-y-1 rounded-lg border border-[#e2e8f0] bg-[#f8fbfe] px-3 py-2 text-sm">
								<p>总行数 {importResult.total}，成功 {importResult.successCount}，失败 {importResult.failCount}</p>
								{importResult.errors?.slice(0, 5).map((error) => (
									<p key={error} className="text-xs text-[#8f3b45]">{error}</p>
								))}
							</div>
						)}
					</div>
					<DialogFooter>
						<Button variant="outline" onClick={() => setImportOpen(false)}>关闭</Button>
						<Button className="bg-[#35629d] hover:bg-[#264c79]" disabled={importMutation.isPending} onClick={runImport}>
							{importMutation.isPending && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
							开始导入
						</Button>
					</DialogFooter>
				</DialogContent>
			</Dialog>

			{/* Delete single */}
			<Dialog open={pendingDelete !== null} onOpenChange={(open) => !open && setPendingDelete(null)}>
				<DialogContent>
					<DialogHeader>
						<DialogTitle>删除确认</DialogTitle>
						<DialogDescription>确定要删除语义模型「{pendingDelete?.businessName}」吗？此操作不可恢复。</DialogDescription>
					</DialogHeader>
					<DialogFooter>
						<Button variant="outline" onClick={() => setPendingDelete(null)}>取消</Button>
						<Button variant="destructive" disabled={deleteMutation.isPending} onClick={runDelete}>确认删除</Button>
					</DialogFooter>
				</DialogContent>
			</Dialog>

			{/* Batch delete */}
			<Dialog open={batchConfirmOpen} onOpenChange={setBatchConfirmOpen}>
				<DialogContent>
					<DialogHeader>
						<DialogTitle>批量删除确认</DialogTitle>
						<DialogDescription>确定要删除选中的 {selectedIds.length} 条语义模型吗？</DialogDescription>
					</DialogHeader>
					<DialogFooter>
						<Button variant="outline" onClick={() => setBatchConfirmOpen(false)}>取消</Button>
						<Button variant="destructive" disabled={batchDeleteMutation.isPending} onClick={runBatchDelete}>确认删除</Button>
					</DialogFooter>
				</DialogContent>
			</Dialog>

			{/* Toggle status */}
			<Dialog open={toggleTarget !== null} onOpenChange={(open) => !open && setToggleTarget(null)}>
				<DialogContent>
					<DialogHeader>
						<DialogTitle>{toggleTarget?.status === 1 ? '停用' : '启用'}确认</DialogTitle>
						<DialogDescription>
							确定要{toggleTarget?.status === 1 ? '停用' : '启用'}语义模型「{toggleTarget?.businessName}」吗？
						</DialogDescription>
					</DialogHeader>
					<DialogFooter>
						<Button variant="outline" onClick={() => setToggleTarget(null)}>取消</Button>
						<Button disabled={toggleMutation.isPending} onClick={runToggle}>确认</Button>
					</DialogFooter>
				</DialogContent>
			</Dialog>
		</div>
	);
}
