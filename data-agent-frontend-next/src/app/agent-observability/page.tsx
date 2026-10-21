'use client';

import { useState } from 'react';
import Link from 'next/link';
import { useQuery } from '@tanstack/react-query';
import {
	Activity,
	ChevronRight,
	ExternalLink,
	FileText,
	Loader2,
	RefreshCw,
	RotateCcw,
} from 'lucide-react';
import { PageHeader } from '@/components/page-header';
import { EmptyState, ErrorState, LoadingState } from '@/components/data-states';
import { InlineStat, SectionHeading, Surface, Toolbar } from '@/components/surface';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { errorMessage } from '@/lib/api/client';
import {
	fetchObservabilityDetail,
	fetchObservabilityRuns,
	fetchObservabilitySummary,
	narrativeSourceLabel,
	type DiagnosticItem,
	type EvaluationTaskReference,
	type ObservabilityRunDetail,
	type ObservabilityRunSummary,
	type TimelineItem,
} from '@/lib/api/observability';
import { formatTime } from '@/components/supply-chain/common';
import { cn } from '@/lib/utils';

const STATUS_ITEMS: Record<string, string> = {
	ALL: '全部状态',
	COMPLETED: '已完成',
	FAILED: '失败',
	CANCELLED: '已取消',
	RUNNING: '执行中',
	ACCEPTED: '已接收',
};
const MODE_ITEMS: Record<string, string> = { ALL: '全部任务', ANALYSIS: '风险分析', DECISION: '决策任务' };
const SOURCE_ITEMS: Record<string, string> = {
	ALL: '全部叙述来源',
	MODEL_VERIFIED: '模型已验证',
	MODEL_LEGACY_UNVERIFIED: '历史模型未验证',
	DETERMINISTIC_FALLBACK: '确定性回退',
	UNKNOWN: '来源未记录',
};
const FALLBACK_ITEMS: Record<string, string> = { ALL: '全部', true: '仅发生降级', false: '仅无降级' };
const PAGE_SIZES = [5, 8, 10, 20];

function statusTone(status?: string) {
	if (status === 'COMPLETED') return 'border-green-200 bg-green-50 text-green-700';
	if (status === 'FAILED') return 'border-red-200 bg-red-50 text-red-700';
	if (status === 'CANCELLED') return 'border-amber-200 bg-amber-50 text-amber-700';
	if (status === 'RUNNING' || status === 'ACCEPTED') return 'border-blue-200 bg-blue-50 text-blue-700';
	return 'border-gray-200 bg-gray-50 text-gray-600';
}

function statusLabel(status?: string) {
	return STATUS_ITEMS[status ?? ''] ?? status ?? '—';
}

function modeLabel(mode?: string) {
	return mode === 'DECISION' ? '决策任务' : mode === 'ANALYSIS' ? '风险分析' : mode ?? '—';
}

function sourceTone(source?: string) {
	if (source === 'MODEL_VERIFIED') return 'border-green-200 bg-green-50 text-green-700';
	if (source === 'MODEL_LEGACY_UNVERIFIED') return 'border-amber-200 bg-amber-50 text-amber-700';
	if (source === 'DETERMINISTIC_FALLBACK') return 'border-blue-200 bg-blue-50 text-blue-700';
	return 'border-gray-200 bg-gray-50 text-gray-600';
}

function severityTone(severity?: string) {
	if (severity === 'ERROR') return 'border-red-200 bg-red-50 text-red-700';
	if (severity === 'WARNING') return 'border-amber-200 bg-amber-50 text-amber-700';
	return 'border-blue-200 bg-blue-50 text-blue-700';
}

function formatDuration(ms?: number | null) {
	if (ms == null) return '—';
	return ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(2)}s`;
}

function sourceLabel(source?: string) {
	switch (source) {
		case 'WORKFLOW': return '工作流';
		case 'TOOL': return '工具';
		case 'MODEL': return '模型';
		case 'FALLBACK': return '回退';
		case 'AGENT': return 'Agent';
		default: return source ?? '—';
	}
}

export default function AgentObservabilityPage() {
	const [status, setStatus] = useState('ALL');
	const [mode, setMode] = useState('ALL');
	const [source, setSource] = useState('ALL');
	const [fallback, setFallback] = useState('ALL');
	const [failureType, setFailureType] = useState('');
	const [query, setQuery] = useState('');
	const [page, setPage] = useState(1);
	const [pageSize, setPageSize] = useState(8);
	const [selectedTaskId, setSelectedTaskId] = useState<string | null>(null);

	const summaryQuery = useQuery({ queryKey: ['observabilitySummary'], queryFn: fetchObservabilitySummary });
	const runsQuery = useQuery({
		queryKey: ['observabilityRuns', status, mode, source, fallback, failureType, query, page, pageSize],
		queryFn: () => fetchObservabilityRuns({
			page,
			pageSize,
			status: status === 'ALL' ? undefined : status,
			mode: mode === 'ALL' ? undefined : mode,
			narrativeSource: source === 'ALL' ? undefined : source,
			fallback: fallback === 'ALL' ? undefined : fallback === 'true',
			failureType: failureType.trim() || undefined,
			query: query.trim() || undefined,
		}),
	});
	const detailQuery = useQuery({
		queryKey: ['observabilityDetail', selectedTaskId],
		queryFn: () => fetchObservabilityDetail(selectedTaskId!),
		enabled: Boolean(selectedTaskId),
	});

	const summary = summaryQuery.data;
	const runs = runsQuery.data;

	const resetFilters = () => {
		setStatus('ALL');
		setMode('ALL');
		setSource('ALL');
		setFallback('ALL');
		setFailureType('');
		setQuery('');
		setPage(1);
	};

	return (
		<div className="space-y-5">
			<PageHeader
				title="Agent 运行观测"
				breadcrumb="Agent 工程"
				description="从真实任务事件追溯 Workflow 阶段、工具调用、模型降级与最终结果；不补写未发生的执行步骤。"
				actions={
					<Button variant="outline" onClick={() => { summaryQuery.refetch(); runsQuery.refetch(); }} disabled={summaryQuery.isFetching || runsQuery.isFetching}>
						<RefreshCw className={cn('mr-2 h-4 w-4', (summaryQuery.isFetching || runsQuery.isFetching) && 'animate-spin')} />
						刷新
					</Button>
				}
			/>

			<Surface>
				<SectionHeading
					title="运行总览"
					description={summary?.scope ?? '加载真实任务窗口…'}
					actions={summaryQuery.isFetching ? <Loader2 className="h-4 w-4 animate-spin text-[#35629d]" /> : null}
				/>
				{summaryQuery.isPending ? (
					<LoadingState compact label="加载运行汇总…" />
				) : summaryQuery.isError ? (
					<ErrorState message={errorMessage(summaryQuery.error, '加载运行汇总失败')} onRetry={() => summaryQuery.refetch()} />
				) : summary ? (
					<div className="space-y-4">
						<div className="flex flex-wrap gap-y-3">
							<InlineStat label="任务总数" value={summary.totalTasks} />
							<InlineStat label="已完成" value={summary.completedTasks} />
							<InlineStat label="失败" value={summary.failedTasks} />
							<InlineStat label="执行中" value={summary.runningTasks} />
							<InlineStat label="已取消" value={summary.cancelledTasks} />
							<InlineStat label="降级任务" value={summary.degradedTasks} />
							<InlineStat label="工具调用 / 失败" value={`${summary.toolCalls} / ${summary.toolFailures}`} />
							<InlineStat label="模型完成 / 失败" value={`${summary.modelCompletions} / ${summary.modelFailures}`} />
							<InlineStat label="平均任务耗时" value={summary.averageDurationMs == null ? '样本不足' : formatDuration(summary.averageDurationMs)} />
						</div>
						<div className="grid gap-3 md:grid-cols-2">
							<div className="rounded-lg border border-[#e6eef7] bg-white/55 px-3 py-2">
								<div className="mb-1.5 text-xs font-medium text-[#33506b]">叙述来源分布</div>
								<div className="flex flex-wrap gap-1.5">
									{Object.entries(summary.narrativeSources).length === 0 ? (
										<span className="text-xs text-[#5a708c]">当前窗口没有叙述来源记录。</span>
									) : Object.entries(summary.narrativeSources).map(([key, value]) => (
										<Badge key={key} variant="outline" className={sourceTone(key)}>
											{narrativeSourceLabel(key)} {value}
										</Badge>
									))}
								</div>
							</div>
							<div className="rounded-lg border border-[#e6eef7] bg-white/55 px-3 py-2">
								<div className="mb-1.5 text-xs font-medium text-[#33506b]">失败类型分布</div>
								{Object.entries(summary.failureTypes).length === 0 ? (
									<span className="text-xs text-[#5a708c]">当前窗口没有失败任务。</span>
								) : (
									<div className="flex flex-wrap gap-1.5">
										{Object.entries(summary.failureTypes).map(([key, value]) => (
											<Badge key={key} variant="outline" className="border-[#e7c8cb] bg-[#fff6f6] text-[#8f3b45]">{key} {value}</Badge>
										))}
									</div>
								)}
							</div>
						</div>
						{summary.limitations?.length ? (
							<details className="rounded-lg border border-[#ecd9b5] bg-[#fdf8ee] px-3 py-2 text-xs text-[#7a5a22]">
								<summary className="cursor-pointer font-medium">统计口径与限制</summary>
								<ul className="mt-1.5 list-disc space-y-1 pl-4">
									{summary.limitations.map((item) => <li key={item}>{item}</li>)}
								</ul>
							</details>
						) : null}
					</div>
				) : null}
			</Surface>

			<Toolbar>
				<Select items={STATUS_ITEMS} value={status} onValueChange={(value) => { setStatus(value ?? 'ALL'); setPage(1); }}>
					<SelectTrigger className="w-[130px]"><SelectValue /></SelectTrigger>
					<SelectContent>{Object.entries(STATUS_ITEMS).map(([value, label]) => <SelectItem key={value} value={value}>{label}</SelectItem>)}</SelectContent>
				</Select>
				<Select items={MODE_ITEMS} value={mode} onValueChange={(value) => { setMode(value ?? 'ALL'); setPage(1); }}>
					<SelectTrigger className="w-[130px]"><SelectValue /></SelectTrigger>
					<SelectContent>{Object.entries(MODE_ITEMS).map(([value, label]) => <SelectItem key={value} value={value}>{label}</SelectItem>)}</SelectContent>
				</Select>
				<Select items={SOURCE_ITEMS} value={source} onValueChange={(value) => { setSource(value ?? 'ALL'); setPage(1); }}>
					<SelectTrigger className="w-[160px]"><SelectValue /></SelectTrigger>
					<SelectContent>{Object.entries(SOURCE_ITEMS).map(([value, label]) => <SelectItem key={value} value={value}>{label}</SelectItem>)}</SelectContent>
				</Select>
				<Select items={FALLBACK_ITEMS} value={fallback} onValueChange={(value) => { setFallback(value ?? 'ALL'); setPage(1); }}>
					<SelectTrigger className="w-[125px]"><SelectValue /></SelectTrigger>
					<SelectContent>{Object.entries(FALLBACK_ITEMS).map(([value, label]) => <SelectItem key={value} value={value}>{label}</SelectItem>)}</SelectContent>
				</Select>
				<Input className="w-[145px]" value={failureType} onChange={(event) => { setFailureType(event.target.value); setPage(1); }} placeholder="失败类型" />
				<Input className="w-[180px]" value={query} onChange={(event) => { setQuery(event.target.value); setPage(1); }} placeholder="搜索 taskId / 问题" />
				<Select items={Object.fromEntries(PAGE_SIZES.map((size) => [String(size), `${size} 条/页`]))} value={String(pageSize)} onValueChange={(value) => { setPageSize(Number(value ?? 8)); setPage(1); }}>
					<SelectTrigger className="w-[110px]"><SelectValue /></SelectTrigger>
					<SelectContent>{PAGE_SIZES.map((size) => <SelectItem key={size} value={String(size)}>{size} 条/页</SelectItem>)}</SelectContent>
				</Select>
				<Button size="sm" variant="ghost" onClick={resetFilters}><RotateCcw className="mr-1 h-4 w-4" />重置</Button>
			</Toolbar>

			<div className="grid gap-4 xl:grid-cols-[minmax(0,0.9fr)_minmax(0,1.35fr)]">
				<Surface className="min-w-0">
					<SectionHeading
						title="运行历史"
						description={runs ? `${runs.scope} · 匹配 ${runs.totalElements} 条` : '加载运行列表…'}
						actions={runsQuery.isFetching ? <Loader2 className="h-4 w-4 animate-spin text-[#35629d]" /> : null}
					/>
					{runsQuery.isPending ? (
						<LoadingState compact label="加载运行记录…" />
					) : runsQuery.isError ? (
						<ErrorState message={errorMessage(runsQuery.error, '加载运行记录失败')} onRetry={() => runsQuery.refetch()} />
					) : (runs?.items.length ?? 0) === 0 ? (
						<EmptyState title="当前筛选没有运行记录" description="调整筛选条件，或先在供应链分析/决策工作台运行一个任务。" />
					) : (
						<div className="space-y-3">
							<div className="space-y-1.5">
								{(runs?.items ?? []).map((item) => (
									<RunListItem key={item.taskId} item={item} selected={item.taskId === selectedTaskId} onSelect={() => setSelectedTaskId(item.taskId)} />
								))}
							</div>
							<div className="flex flex-wrap items-center justify-end gap-2 border-t border-[#e6eef7] pt-3">
								<span className="text-xs text-[#5a708c]">第 {runs?.page ?? 1} / {Math.max(1, runs?.totalPages ?? 1)} 页</span>
								<Button size="sm" variant="outline" disabled={(runs?.page ?? 1) <= 1} onClick={() => setPage((current) => Math.max(1, current - 1))}>上一页</Button>
								<Button size="sm" variant="outline" disabled={(runs?.page ?? 1) >= (runs?.totalPages ?? 1)} onClick={() => setPage((current) => current + 1)}>下一页</Button>
							</div>
						</div>
					)}
				</Surface>

				<Surface className="min-w-0">
					{!selectedTaskId ? (
						<EmptyState title="选择一条运行" description="从左侧打开运行，查看真实事件时间线、工具/模型记录、诊断和关联结果。" />
					) : detailQuery.isPending ? (
						<LoadingState label="加载运行详情…" />
					) : detailQuery.isError ? (
						<ErrorState message={errorMessage(detailQuery.error, '加载运行详情失败')} onRetry={() => detailQuery.refetch()} />
					) : detailQuery.data ? (
						<RunDetail detail={detailQuery.data} />
					) : null}
				</Surface>
			</div>
		</div>
	);
}

function RunListItem({ item, selected, onSelect }: { item: ObservabilityRunSummary; selected: boolean; onSelect: () => void }) {
	return (
		<button
			type="button"
			onClick={onSelect}
			className={cn(
				'w-full rounded-lg border px-3 py-2.5 text-left transition-colors',
				selected ? 'border-[#a9c4e3] bg-[#eef6fd]' : 'border-[#e6eef7] bg-white/45 hover:bg-[#f7fbfe]',
			)}
		>
			<div className="flex flex-wrap items-start justify-between gap-1.5">
				<div className="min-w-0 flex-1">
					<div className="line-clamp-2 text-sm font-medium text-[#1c3048]">{item.question || '未记录问题'}</div>
					<div className="mt-0.5 font-mono text-[10px] text-[#8aa2ba]">{item.taskId}</div>
				</div>
				<ChevronRight className="mt-1 h-4 w-4 shrink-0 text-[#8aa2ba]" />
			</div>
			<div className="mt-2 flex flex-wrap items-center gap-1.5">
				<Badge variant="outline" className={statusTone(item.status)}>{statusLabel(item.status)}</Badge>
				<Badge variant="outline">{modeLabel(item.mode)}</Badge>
				<Badge variant="outline" className={sourceTone(item.narrativeSource)}>{narrativeSourceLabel(item.narrativeSource)}</Badge>
				{item.fallbackUsed && <Badge variant="outline" className="border-amber-200 bg-amber-50 text-amber-700">已降级</Badge>}
				{item.toolFailureCount > 0 && <Badge variant="outline" className="border-red-200 bg-red-50 text-red-700">工具失败{item.toolFailureCount}</Badge>}
			</div>
			<div className="mt-1.5 flex flex-wrap gap-x-3 gap-y-1 text-[11px] text-[#5a708c]">
				<span>{item.eventCount} 个事件</span>
				<span>{item.toolCallCount} 次工具</span>
				<span>{formatDuration(item.durationMs)}</span>
				<span>{formatTime(item.updatedAt)}</span>
			</div>
		</button>
	);
}

function RunDetail({ detail }: { detail: ObservabilityRunDetail }) {
	const { run, narrative, report, links } = detail;
	return (
		<div className="space-y-5">
			<div>
				<div className="flex flex-wrap items-start justify-between gap-2">
					<div className="min-w-0">
						<div className="flex flex-wrap items-center gap-1.5">
							<Badge variant="outline" className={statusTone(run.status)}>{statusLabel(run.status)}</Badge>
							<Badge variant="outline">{modeLabel(run.mode)}</Badge>
							<Badge variant="outline" className={sourceTone(narrative.source)}>{narrativeSourceLabel(narrative.source)}</Badge>
							{narrative.fallbackUsed && <Badge variant="outline" className="border-amber-200 bg-amber-50 text-amber-700">确定性降级</Badge>}
						</div>
						<h2 className="mt-2 line-clamp-2 text-[17px] font-semibold text-[#1c3048]">{run.question || '未记录问题'}</h2>
						<div className="mt-1 font-mono text-[11px] text-[#8aa2ba]">{run.taskId}</div>
					</div>
					<div className="flex flex-wrap gap-1.5">
						{links.reportAvailable && (
							<Link href={`/supply-chain?taskId=${encodeURIComponent(run.taskId)}`}><Button size="sm" variant="outline"><FileText className="mr-1 h-4 w-4" />风险报告</Button></Link>
						)}
						{links.decisionAvailable && (
							<Link href={`/supply-chain/decision?taskId=${encodeURIComponent(run.taskId)}`}><Button size="sm" variant="outline"><Activity className="mr-1 h-4 w-4" />决策结果</Button></Link>
						)}
						{(links.planIds.length > 0 || links.proposalIds.length > 0) && (
							<Link href={`/supply-chain/disposal?taskId=${encodeURIComponent(run.taskId)}`}><Button size="sm" variant="outline"><ExternalLink className="mr-1 h-4 w-4" />处置工作台</Button></Link>
						)}
					</div>
				</div>
				<div className="mt-3 grid gap-2 sm:grid-cols-4">
					<InlineStat label="当前步骤" value={run.currentStep ?? '—'} />
					<InlineStat label="任务耗时" value={formatDuration(run.durationMs)} />
					<InlineStat label="风险订单 / 高风险" value={`${report.atRiskOrders} / ${report.highRiskOrders}`} />
					<InlineStat label="数据缺口" value={report.dataGapCount} />
				</div>
				<p className="mt-2 rounded-lg bg-white/50 px-3 py-2 text-xs leading-relaxed text-[#5a708c] ring-1 ring-white/70">
					最终风险分数、等级与证据由 <strong>Java 确定性规则</strong> 计算；模型只能解释，不得修改。
					当前来源：{narrativeSourceLabel(narrative.source)}；模型参与：{narrative.modelAssisted ? '是' : '否'}。
				</p>
			</div>

			<div>
				<SectionHeading title="故障诊断" description="确定性规则诊断，不调用模型" />
				{detail.diagnostics.length === 0 ? (
					<p className="text-sm text-[#5a708c]">没有诊断项。</p>
				) : (
					<div className="space-y-2">
						{detail.diagnostics.map((item) => <DiagnosticCard key={`${item.code}-${item.title}`} item={item} />)}
					</div>
				)}
			</div>

			<div>
				<SectionHeading title="执行时间线" description={`${detail.timeline.length} 个已持久化事件；没有事件的阶段不会显示`} />
				{detail.timeline.length === 0 ? (
					<div className="rounded-lg border border-[#ecd9b5] bg-[#fdf8ee] px-3 py-2 text-xs text-[#7a5a22]">
						该任务没有执行事件，无法推断实际阶段；不以最终状态补造时间线。
					</div>
				) : (
					<ol className="space-y-1.5">
						{detail.timeline.map((event) => <TimelineRow key={event.eventId} event={event} />)}
					</ol>
				)}
			</div>

			<div className="grid gap-4 lg:grid-cols-2">
				<div>
					<SectionHeading title="工具调用" description={`${detail.tools.length} 次实际结果事件`} />
					{detail.tools.length === 0 ? (
						<p className="text-sm text-[#5a708c]">没有工具调用结果事件。</p>
					) : (
						<div className="space-y-2">
							{detail.tools.map((tool) => (
								<div key={`${tool.name}-${tool.eventIds.join('-')}`} className="rounded-lg border border-[#e6eef7] bg-white/50 px-3 py-2">
									<div className="flex flex-wrap items-center justify-between gap-1.5">
										<span className="font-mono text-xs font-semibold text-[#1c3048]">{tool.name}</span>
										<div className="flex gap-1.5">
											<Badge variant="outline">{tool.initiatedBy === 'MODEL' ? '模型发起' : '工作流'}</Badge>
											<Badge variant="outline" className={statusTone(tool.status)}>{statusLabel(tool.status)}</Badge>
										</div>
									</div>
									<p className="mt-1 text-xs text-[#5a708c]">{tool.summary ?? '无结果摘要'}</p>
									<div className="mt-1 flex flex-wrap gap-2 text-[11px] text-[#8aa2ba]">
										<span>{formatDuration(tool.durationMs)}</span>
										{tool.errorType && <span className="text-[#8f3b45]">{tool.errorType}</span>}
										{!tool.inputRecorded && <span>输入摘要未持久化</span>}
									</div>
								</div>
							))}
						</div>
					)}
				</div>
				<div>
					<SectionHeading title="模型调用" description={`${detail.modelCalls.length} 个实际模型结果事件`} />
					{detail.modelCalls.length === 0 ? (
						<p className="text-sm text-[#5a708c]">本次运行没有模型调用记录；不补写模型成功事件。</p>
					) : (
						<div className="space-y-2">
							{detail.modelCalls.map((call) => (
								<div key={`${call.stage}-${call.eventIds.join('-')}`} className="rounded-lg border border-[#e6eef7] bg-white/50 px-3 py-2">
									<div className="flex flex-wrap items-center justify-between gap-1.5">
										<span className="text-xs font-semibold text-[#1c3048]">{call.stage}</span>
										<Badge variant="outline" className={statusTone(call.status)}>{statusLabel(call.status)}</Badge>
									</div>
									<p className="mt-1 text-xs text-[#5a708c]">{call.summary ?? '无摘要'}</p>
									<div className="mt-1 flex flex-wrap gap-2 text-[11px] text-[#8aa2ba]">
										<span>{formatDuration(call.durationMs)}</span>
										{call.errorType && <span className="text-[#8f3b45]">{call.errorType}</span>}
										<span>模型标识 / Token / 费用未返回</span>
									</div>
								</div>
							))}
						</div>
					)}
				</div>
			</div>

			{detail.fallbacks.length > 0 && (
				<div>
					<SectionHeading title="确定性降级" description="回退原因来自持久化事件" />
					<div className="space-y-2">
						{detail.fallbacks.map((fallback) => (
							<div key={fallback.eventId} className="rounded-lg border border-[#ecd9b5] bg-[#fdf8ee] px-3 py-2">
								<div className="text-xs font-medium text-[#7a5a22]">{fallback.stage}</div>
								<p className="mt-1 text-xs text-[#7a5a22]">{fallback.reason}</p>
							</div>
						))}
					</div>
				</div>
			)}

			<div>
				<SectionHeading title="关联记录" description="只显示实际存在的关联标识" />
				<div className="flex flex-wrap gap-2">
					{links.proposalIds.map((id) => <Badge key={id} variant="outline">提案 {id}</Badge>)}
					{links.planIds.map((id) => <Badge key={id} variant="outline">处置方案 {id}</Badge>)}
					{links.evaluationRuns.length === 0 && links.proposalIds.length === 0 && links.planIds.length === 0 && !links.reportAvailable && !links.decisionAvailable && (
						<span className="text-sm text-[#5a708c]">没有关联记录。</span>
					)}
				</div>
				{links.evaluationRuns.length > 0 && (
					<div className="mt-2 space-y-1.5">
						<div className="text-xs font-medium text-[#33506b]">评测运行</div>
						{links.evaluationRuns.map((link) => <EvaluationLinkRow key={`${link.runId}-${link.caseId}`} link={link} />)}
					</div>
				)}
			</div>
			<p className="border-t border-[#e6eef7] pt-3 text-xs text-[#5a708c]">
				{detail.diagnosticMode === 'DETERMINISTIC_RULES'
					? '诊断由确定性规则根据任务、事件和叙述来源归类；不是模型自动诊断。'
					: '诊断来源未声明。'}
				内容仅包含脱敏摘要，不返回提示词、凭据或完整模型上下文。
			</p>
		</div>
	);
}

function DiagnosticCard({ item }: { item: DiagnosticItem }) {
	return (
		<div className={cn('rounded-lg border px-3 py-2', severityTone(item.severity))}>
			<div className="flex flex-wrap items-center gap-1.5">
				<span className="text-xs font-semibold">{item.title}</span>
				<Badge variant="outline" className="bg-white/60">{item.code}</Badge>
				{item.deterministicRule && <span className="text-[10px] opacity-70">确定性规则</span>}
			</div>
			<p className="mt-1 text-xs leading-relaxed opacity-90">{item.detail}</p>
			<p className="mt-1 text-xs leading-relaxed"><span className="font-medium">建议：</span>{item.recommendation}</p>
		</div>
	);
}

function TimelineRow({ event }: { event: TimelineItem }) {
	return (
		<li className="flex gap-2 rounded-lg border border-[#e6eef7] bg-white/45 px-3 py-2">
			<div className="w-7 shrink-0 pt-0.5 text-center font-mono text-[10px] text-[#8aa2ba]">#{event.sequence}</div>
			<div className="min-w-0 flex-1">
				<div className="flex flex-wrap items-center gap-1.5">
					<Badge variant="outline">{sourceLabel(event.source)}</Badge>
					<span className="text-xs font-semibold text-[#1c3048]">{event.stage}</span>
					<Badge variant="outline" className={statusTone(event.status)}>{statusLabel(event.status)}</Badge>
					<span className="text-[11px] text-[#8aa2ba]">{event.type}</span>
					<span className="ml-auto text-[11px] text-[#8aa2ba]">{formatTime(event.occurredAt)} · {formatDuration(event.durationMs)}</span>
				</div>
				<p className="mt-1 text-xs text-[#5a708c]">{event.summary ?? '无摘要'}</p>
				{event.errorType && <p className="mt-1 text-[11px] text-[#8f3b45]">{event.errorType}</p>}
			</div>
		</li>
	);
}

function EvaluationLinkRow({ link }: { link: EvaluationTaskReference }) {
	return (
		<Link href={`/agent-evaluation?runId=${encodeURIComponent(link.runId)}`} className="flex flex-wrap items-center justify-between gap-1.5 rounded-lg border border-[#e6eef7] bg-white/45 px-3 py-2 text-xs text-[#33506b] hover:bg-[#f7fbfe]">
			<span className="font-mono">{link.runId}</span>
			<span className="text-[#5a708c]">{link.caseName ?? link.caseId} · {link.caseStatus ?? '—'} · {link.mode ?? '—'}</span>
			<ExternalLink className="h-3.5 w-3.5" />
		</Link>
	);
}
