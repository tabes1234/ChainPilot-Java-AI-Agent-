'use client';

import { useState } from 'react';
import Link from 'next/link';
import { useQuery } from '@tanstack/react-query';
import { Loader2, RefreshCw } from 'lucide-react';
import { toast } from 'sonner';
import { PageHeader } from '@/components/page-header';
import { EmptyState, ErrorState, LoadingState } from '@/components/data-states';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { errorMessage } from '@/lib/api/client';
import { useDemoGuard } from '@/lib/use-demo-guard';
import { cancelTask, fetchTaskEvents, fetchTasks, type AnalysisMode } from '@/lib/api/supplyChain';
import { StatusBadge, TASK_STATUS_LABELS, formatTime } from '@/components/supply-chain/common';

const STATUS_ITEMS: Record<string, string> = {
	ALL: '全部状态',
	ACCEPTED: '已接收',
	RUNNING: '执行中',
	COMPLETED: '已完成',
	FAILED: '失败',
	CANCELLED: '已取消',
};
const MODE_ITEMS: Record<string, string> = { ANALYSIS: '风险分析任务', DECISION: '决策任务' };
const PAGE_SIZES = [5, 8, 10, 20];
const PAGE_SIZE_ITEMS: Record<string, string> = Object.fromEntries(PAGE_SIZES.map((size) => [String(size), `${size} 条/页`]));

export default function HistoryPage() {
	const [mode, setMode] = useState<AnalysisMode>('ANALYSIS');
	const [status, setStatus] = useState('ALL');
	const [page, setPage] = useState(1);
	const [pageSize, setPageSize] = useState(8);
	const [expandedTask, setExpandedTask] = useState<string | null>(null);
	const [cancellingTask, setCancellingTask] = useState<string | null>(null);
	const guardDemo = useDemoGuard();

	const tasksQuery = useQuery({
		queryKey: ['taskHistory', mode, status, page, pageSize],
		queryFn: () => fetchTasks({ page, pageSize, mode, status: status === 'ALL' ? undefined : status }),
	});
	const eventsQuery = useQuery({
		queryKey: ['taskEvents', expandedTask],
		queryFn: () => fetchTaskEvents(expandedTask!),
		enabled: expandedTask !== null,
	});

	const data = tasksQuery.data;

	const cancel = async (taskId: string) => {
		setCancellingTask(taskId);
		try {
			const status = await cancelTask(taskId);
			if (status.status === 'CANCELLED') toast.success('任务已取消');
			else if (status.status === 'COMPLETED') toast.info('任务已完成，无法取消');
			else if (status.status === 'FAILED') toast.info('任务已失败，未执行取消');
			else toast.error('任务未能取消', { description: `当前状态：${status.status}` });
			await tasksQuery.refetch();
			if (expandedTask === taskId) await eventsQuery.refetch();
		} catch (error) {
			toast.error('取消请求失败', { description: errorMessage(error, '任务可能仍在执行') });
		} finally {
			setCancellingTask(null);
		}
	};

	return (
		<div className="space-y-6">
			<PageHeader
				title="任务记录"
				breadcrumb="工作台"
				description="风险分析与决策任务的历史状态、报告入口与执行轨迹。"
				actions={
					<Button variant="outline" onClick={() => tasksQuery.refetch()} disabled={tasksQuery.isFetching}>
						<RefreshCw className={`mr-2 h-4 w-4 ${tasksQuery.isFetching ? 'animate-spin' : ''}`} />
						刷新
					</Button>
				}
			/>

			<Card>
				<CardContent className="flex flex-wrap items-center gap-3 pt-6">
					<Select items={MODE_ITEMS} value={mode} onValueChange={(value) => { setMode((value ?? 'ANALYSIS') as AnalysisMode); setPage(1); }}>
						<SelectTrigger className="w-[170px]"><SelectValue /></SelectTrigger>
						<SelectContent>
							<SelectItem value="ANALYSIS">风险分析任务</SelectItem>
							<SelectItem value="DECISION">决策任务</SelectItem>
						</SelectContent>
					</Select>
					<Select items={STATUS_ITEMS} value={status} onValueChange={(value) => { setStatus(value ?? 'ALL'); setPage(1); }}>
						<SelectTrigger className="w-[150px]"><SelectValue /></SelectTrigger>
						<SelectContent>
							<SelectItem value="ALL">全部状态</SelectItem>
							<SelectItem value="ACCEPTED">已接收</SelectItem>
							<SelectItem value="RUNNING">执行中</SelectItem>
							<SelectItem value="COMPLETED">已完成</SelectItem>
							<SelectItem value="FAILED">失败</SelectItem>
							<SelectItem value="CANCELLED">已取消</SelectItem>
						</SelectContent>
					</Select>
					<Select items={PAGE_SIZE_ITEMS} value={String(pageSize)} onValueChange={(value) => { setPageSize(Number(value ?? 8)); setPage(1); }}>
						<SelectTrigger className="w-[120px]"><SelectValue /></SelectTrigger>
						<SelectContent>
							{PAGE_SIZES.map((size) => <SelectItem key={size} value={String(size)}>{size} 条/页</SelectItem>)}
						</SelectContent>
					</Select>
					<Badge variant="secondary" className="ml-auto">共 {data?.total ?? '—'} 条</Badge>
				</CardContent>
			</Card>

			<Card>
				<CardHeader><CardTitle className="text-lg">任务列表</CardTitle></CardHeader>
				<CardContent>
					{tasksQuery.isPending ? (
						<LoadingState label="加载任务记录…" />
					) : tasksQuery.isError ? (
						<ErrorState message={errorMessage(tasksQuery.error, '加载任务记录失败')} onRetry={() => tasksQuery.refetch()} />
					) : (data?.items.length ?? 0) === 0 ? (
						<EmptyState
							title="暂无任务记录"
							description={mode === 'ANALYSIS' ? '在供应链风险分析中提交任务后会出现在这里。' : '在自然语言决策中提交任务后会出现在这里。'}
							action={
								<Link href={mode === 'ANALYSIS' ? '/supply-chain' : '/supply-chain/decision'}>
									<Button>{mode === 'ANALYSIS' ? '去发起分析' : '去发起决策'}</Button>
								</Link>
							}
						/>
					) : (
						<div className="space-y-3">
							<div className="overflow-x-auto">
								<Table>
									<TableHeader>
										<TableRow>
											<TableHead>问题</TableHead>
											<TableHead>状态</TableHead>
											<TableHead>当前步骤</TableHead>
											<TableHead>校验</TableHead>
											<TableHead>更新时间</TableHead>
											<TableHead className="text-right">操作</TableHead>
										</TableRow>
									</TableHeader>
									<TableBody>
										{(data?.items ?? []).map((item) => (
											<TableRow key={item.taskId}>
												<TableCell className="max-w-[360px]">
													<div className="truncate text-sm text-[#1c3048]" title={item.question}>{item.question || '—'}</div>
													<div className="font-mono text-[11px] text-[#8aa2ba]" title={item.taskId}>{item.taskId.slice(0, 8)}…</div>
													{item.error && <div className="mt-0.5 text-xs text-[#8f3b45]">{item.error}</div>}
												</TableCell>
												<TableCell>
													<StatusBadge status={item.status} />
													<div className="mt-1 text-[11px] text-[#5a708c]">{TASK_STATUS_LABELS[item.status] ?? ''}</div>
												</TableCell>
												<TableCell className="text-xs text-[#5a708c]">{item.currentStep ?? '—'}</TableCell>
												<TableCell className="text-xs">{item.validationAttempts ?? 0}</TableCell>
												<TableCell className="whitespace-nowrap text-xs text-[#5a708c]">{formatTime(item.updatedAt)}</TableCell>
												<TableCell>
													<div className="flex flex-wrap justify-end gap-1">
														{item.reportAvailable ? (
															<Link href={mode === 'ANALYSIS' ? `/supply-chain?taskId=${encodeURIComponent(item.taskId)}` : `/supply-chain/decision?taskId=${encodeURIComponent(item.taskId)}`}>
																<Button size="sm" variant="outline">{mode === 'ANALYSIS' ? '查看报告' : '查看决策'}</Button>
															</Link>
														) : (
															<Button size="sm" variant="outline" disabled>无报告</Button>
														)}
														{mode === 'DECISION' && item.reportAvailable && (
															<Link href={`/supply-chain/disposal?taskId=${encodeURIComponent(item.taskId)}`}>
																<Button size="sm" variant="outline">处置工作台</Button>
															</Link>
														)}
														{(item.status === 'ACCEPTED' || item.status === 'RUNNING') && (
															<Button
																size="sm"
																variant="outline"
																disabled={cancellingTask === item.taskId}
																onClick={() => { if (guardDemo('任务取消')) return; void cancel(item.taskId); }}
															>
																{cancellingTask === item.taskId ? '取消中…' : '取消任务'}
															</Button>
														)}
														<Button
															size="sm"
															variant="ghost"
															onClick={() => setExpandedTask(expandedTask === item.taskId ? null : item.taskId)}
														>
															{expandedTask === item.taskId ? '收起轨迹' : '执行轨迹'}
														</Button>
													</div>
												</TableCell>
											</TableRow>
										))}
									</TableBody>
								</Table>
							</div>

							{expandedTask && (
								<div className="rounded-lg border border-[#e2e8f0] bg-[#f8fbfe] p-3">
									<div className="mb-2 flex items-center justify-between">
										<p className="text-sm font-medium text-[#33506b]" title={expandedTask}>执行轨迹 · {expandedTask.slice(0, 8)}…</p>
										{eventsQuery.isFetching && <Loader2 className="h-4 w-4 animate-spin text-[#35629d]" />}
									</div>
									{eventsQuery.isError ? (
										<p className="text-xs text-[#8f3b45]">{errorMessage(eventsQuery.error, '加载事件失败')}</p>
									) : (eventsQuery.data ?? []).length === 0 ? (
										<p className="text-xs text-[#5a708c]">该任务暂无执行事件。</p>
									) : (
										<ul className="max-h-[320px] space-y-1 overflow-y-auto text-xs text-[#5a708c]">
											{(eventsQuery.data ?? []).map((event) => (
												<li key={event.eventId} className="flex flex-wrap gap-2">
													<span className="font-mono text-[#8aa2ba]">#{event.sequence}</span>
													<span>{formatTime(event.occurredAt)}</span>
													<span className="font-medium text-[#33506b]">{event.stage ?? event.type}</span>
													<span>{event.status ?? '—'}</span>
													<span>{event.summary ?? ''}</span>
													{event.durationMs != null && <span>{event.durationMs}ms</span>}
												</li>
											))}
										</ul>
									)}
								</div>
							)}

							<div className="flex flex-wrap items-center justify-end gap-3 border-t border-[#e6eef7] pt-3">
								<span className="text-sm text-[#5a708c]">
									第 {data?.page ?? 1} / {Math.max(1, data?.totalPages ?? 1)} 页
								</span>
								<Button size="sm" variant="outline" disabled={page <= 1} onClick={() => setPage((current) => Math.max(1, current - 1))}>上一页</Button>
								<Button
									size="sm"
									variant="outline"
									disabled={page >= (data?.totalPages ?? 1)}
									onClick={() => setPage((current) => current + 1)}
								>
									下一页
								</Button>
							</div>
						</div>
					)}
				</CardContent>
			</Card>
		</div>
	);
}
