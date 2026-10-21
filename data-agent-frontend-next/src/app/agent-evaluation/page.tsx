'use client';

import { Fragment, useEffect, useMemo, useRef, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useQuery } from '@tanstack/react-query';
import { toast } from 'sonner';
import {
	Activity,
	MessageSquareText,
	AlertTriangle,
	BarChart3,
	CheckCircle2,
	ChevronDown,
	ChevronRight,
	ExternalLink,
	FileText,
	GitCompareArrows,
	History,
	ListChecks,
	Loader2,
	Play,
	RefreshCw,
	RotateCcw,
	Search,
	XCircle,
} from 'lucide-react';
import { PageHeader } from '@/components/page-header';
import { InlineStat, SectionHeading, Surface, Toolbar } from '@/components/surface';
import { EmptyState, ErrorState, LoadingState } from '@/components/data-states';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { errorMessage } from '@/lib/api/client';
import { readinessStateLabel } from '@/lib/api/modelConfig';
import type {
	EvaluationCapabilities,
	EvaluationCaseDefinition,
	EvaluationMetrics,
	EvaluationMetricsReport,
	EvaluationMode,
	EvaluationRun,
} from '@/lib/api/evaluation';
import { fetchTaskEvents } from '@/lib/api/supplyChain';
import {
	caseStatusLabel,
	caseTone,
	categoryLabel,
	formatDuration,
	formatPercent,
	modeLabel,
	p95Label,
	resolveTaskLink,
	runFailureLabel,
	runStatusLabel,
	runTone,
	selectionMode,
	canAddToSelection,
	taskModeLabel,
} from '@/lib/evaluation-format';
import {
	useEvaluationCapabilities,
	useEvaluationCaseHistory,
	useEvaluationCases,
	useEvaluationMetrics,
	useEvaluationRun,
	useEvaluationRuns,
	useStartEvaluationRun,
} from '@/lib/query/hooks';
import { useUrlParam } from '@/lib/use-url-param';
import { formatTime } from '@/components/supply-chain/common';
import { cn } from '@/lib/utils';
import { ComparisonPanel } from './ComparisonPanel';
import { FeedbackPanel } from './FeedbackPanel';
import { RunConfirmDialog } from './RunConfirmDialog';

type View = 'cases' | 'runs' | 'compare' | 'metrics' | 'feedback';

type RunRequest = {
	caseIds: string[];
	mode: EvaluationMode;
	label?: string;
};

const VIEWS: { id: View; label: string; icon: typeof ListChecks }[] = [
	{ id: 'cases', label: '评测案例', icon: ListChecks },
	{ id: 'runs', label: '运行记录', icon: History },
	{ id: 'compare', label: '运行对比', icon: GitCompareArrows },
	{ id: 'metrics', label: '指标', icon: BarChart3 },
	{ id: 'feedback', label: '人工反馈', icon: MessageSquareText },
];

const CATEGORY_FILTERS: Record<string, string> = {
	ALL: '全部场景',
	risk: '订单风险',
	inventory: '库存',
	supplier: '供应商',
	rag: 'RAG 检索',
	workflow: '真实工作流',
};

const MODE_FILTERS: Record<string, string> = {
	ALL: '全部模式',
	OFFLINE: '离线确定性',
	AGENT: '真实工作流',
};

export default function AgentEvaluationPage() {
	const router = useRouter();
	const runIdParam = useUrlParam('runId');

	const [view, setView] = useState<View>('cases');
	const [keyword, setKeyword] = useState('');
	const [category, setCategory] = useState('ALL');
	const [modeFilter, setModeFilter] = useState('ALL');
	const [selectedIds, setSelectedIds] = useState<string[]>([]);
	const [caseDetail, setCaseDetail] = useState<EvaluationCaseDefinition | null>(null);
	const [runRequest, setRunRequest] = useState<RunRequest | null>(null);
	const [expandedResult, setExpandedResult] = useState<string | null>(null);
	const lastRunId = useRef<string | null>(null);

	const casesQuery = useEvaluationCases();
	const capabilitiesQuery = useEvaluationCapabilities();
	const runsQuery = useEvaluationRuns(20);
	const metricsQuery = useEvaluationMetrics(20);
	const startRun = useStartEvaluationRun();

	const cases = useMemo(() => casesQuery.data ?? [], [casesQuery.data]);
	const runs = useMemo(() => runsQuery.data ?? [], [runsQuery.data]);
	const capabilities = capabilitiesQuery.data;

	const selectedRunId = runIdParam ?? runs[0]?.runId ?? null;
	const runDetailQuery = useEvaluationRun(selectedRunId);

	const selectedCases = useMemo(
		() => cases.filter((item) => selectedIds.includes(item.id)),
		[cases, selectedIds],
	);
	const activeMode = selectionMode(cases, selectedIds);

	const filteredCases = useMemo(() => {
		const kw = keyword.trim().toLowerCase();
		return cases.filter((item) => {
			if (category !== 'ALL' && item.category !== category) return false;
			if (modeFilter !== 'ALL' && item.mode !== modeFilter) return false;
			if (!kw) return true;
			return (
				item.name.toLowerCase().includes(kw) ||
				item.id.toLowerCase().includes(kw) ||
				item.scenario.toLowerCase().includes(kw) ||
				item.question.toLowerCase().includes(kw)
			);
		});
	}, [cases, category, modeFilter, keyword]);

	// Deep links and browser history keep the run log in sync with ?runId=.
	useEffect(() => {
		if (runIdParam && runIdParam !== lastRunId.current) {
			setView('runs');
			lastRunId.current = runIdParam;
		}
		if (!runIdParam) lastRunId.current = null;
	}, [runIdParam]);

	const openRun = (runId: string) => {
		setView('runs');
		router.replace(`/agent-evaluation?runId=${encodeURIComponent(runId)}`);
	};

	const toggleCase = (item: EvaluationCaseDefinition) => {
		if (!canAddToSelection(cases, selectedIds, item.id)) {
			toast.error('一次运行只能包含同一模式的案例', {
				description: '请先清除选择，再切换为离线或真实工作流案例。',
			});
			return;
		}
		setSelectedIds((current) =>
			current.includes(item.id) ? current.filter((id) => id !== item.id) : [...current, item.id],
		);
	};

	const selectAllOffline = () => {
		setSelectedIds(cases.filter((item) => item.mode === 'OFFLINE').map((item) => item.id));
	};

	const openConfirm = () => {
		if (selectedCases.length === 0 || !activeMode) return;
		setRunRequest({ caseIds: selectedCases.map((item) => item.id), mode: activeMode });
	};

	const requestRerun = (caseId: string, mode: EvaluationMode) => {
		const definition = cases.find((item) => item.id === caseId);
		setRunRequest({ caseIds: [caseId], mode, label: definition?.name ?? caseId });
	};

	const submitRun = () => {
		if (!runRequest) return;
		startRun.mutate(
			{
				caseIds: runRequest.caseIds,
				mode: runRequest.mode,
				confirmAgentRun: runRequest.mode === 'AGENT',
			},
			{
				onSuccess: (run) => {
					setRunRequest(null);
					toast.success(runRequest.label ? '已创建新的案例运行' : '评测运行已启动', {
						description:
							run.mode === 'AGENT'
								? '真实工作流任务已提交，运行结果会在后台逐步更新。'
								: `离线确定性评测完成：${run.passed} 通过 / ${run.failed} 失败 / ${run.undetermined} 无法判定。`,
					});
					openRun(run.runId);
				},
				onError: (error) => {
					toast.error(errorMessage(error, '启动评测失败'));
				},
			},
		);
	};

	const latestRun = runs[0];
	const runningRun = runs.find((run) => run.status === 'RUNNING');

	return (
		<div className="space-y-6">
			<PageHeader
				title="Agent 评测中心"
				breadcrumb="Agent 工程"
				description="用带版本的评测案例检查供应链 Agent 的确定性行为与真实工作流。运行结果持久化保存，指标只基于已完成的真实运行计算。"
				actions={
					<Button
						variant="outline"
						onClick={() => {
							casesQuery.refetch();
							capabilitiesQuery.refetch();
							runsQuery.refetch();
							metricsQuery.refetch();
						}}
						disabled={casesQuery.isFetching || runsQuery.isFetching}
					>
						<RefreshCw className={cn('mr-2 h-4 w-4', (casesQuery.isFetching || runsQuery.isFetching) && 'animate-spin')} />
						刷新
					</Button>
				}
			/>

			<Surface className="flex flex-col gap-4">
				<div className="flex flex-wrap items-center gap-x-8 gap-y-4">
					<InlineStat label="离线案例集" value={capabilities?.offlineCaseSetVersion ?? capabilities?.datasetVersion ?? '—'} />
					<InlineStat label="工作流案例集" value={capabilities?.agentCaseSetVersion ?? '—'} />
					<InlineStat label="案例总数" value={capabilities?.totalCases ?? '—'} />
					<InlineStat label="离线 / 工作流" value={`${capabilities?.offlineCases ?? '—'} / ${capabilities?.agentCases ?? '—'}`} />
					<InlineStat
						label="最近一次运行"
						value={
							latestRun ? (
								<span className="flex items-center gap-2">
									<Badge variant="outline" className={runTone(latestRun.status)}>
										{runStatusLabel(latestRun.status)}
									</Badge>
									<span className="text-xs font-normal text-[#6a7d91]">{modeLabel(latestRun.mode)} · {formatTime(latestRun.startedAt)}</span>
								</span>
							) : (
								'尚无运行'
							)
						}
					/>
				</div>
				{runningRun && (
					<div className="flex items-start gap-2 rounded-xl border border-[#cfe0f2] bg-[#f2f8fd]/80 px-3 py-2 text-xs text-[#3c5f82]">
						<Loader2 className="mt-0.5 h-3.5 w-3.5 shrink-0 animate-spin" />
						<span>
							运行 <span className="font-mono">{runningRun.runId}</span> 正在进行
							{runningRun.mode === 'AGENT' ? '，真实工作流任务完成后会自动更新结果。' : '。'}
						</span>
					</div>
				)}
				{capabilities?.agentRunActive && !runningRun && (
					<p className="text-xs text-[#8a6a34]">后端记录到一个进行中的真实工作流评测；同一时间只允许一个。</p>
				)}
			</Surface>

			<AgentReadinessPanel capabilities={capabilities} />

			<nav className="flex flex-wrap items-center gap-1" aria-label="评测中心视图">
				{VIEWS.map((item) => (
					<button
						key={item.id}
						type="button"
						onClick={() => setView(item.id)}
						aria-current={view === item.id ? 'page' : undefined}
						data-active={view === item.id}
						className={cn(
							'cp-section-link inline-flex items-center gap-1.5 rounded-full px-3 py-1.5 text-[12.5px] font-medium transition-colors duration-200',
							view === item.id
								? 'bg-white/80 text-[#2b5583] ring-1 ring-white/80 shadow-[0_1px_2px_rgba(23,42,64,0.04)]'
								: 'text-[#5f7389] hover:bg-white/55 hover:text-[#233448]',
						)}
					>
						<item.icon className="h-3.5 w-3.5" />
						{item.label}
						{item.id === 'runs' && runs.length > 0 && <span className="text-[11px] text-[#7a8da1]">{runs.length}</span>}
					</button>
				))}
			</nav>

			{view === 'cases' && (
				<div className="space-y-4">
					<Toolbar>
						<div className="relative min-w-[200px] flex-1">
							<Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-[#8aa2ba]" />
							<Input
								className="pl-9"
								placeholder="搜索案例名称、编号或问题"
								value={keyword}
								onChange={(event) => setKeyword(event.target.value)}
							/>
						</div>
						<Select items={CATEGORY_FILTERS} value={category} onValueChange={(value) => setCategory(value ?? 'ALL')}>
							<SelectTrigger className="w-[140px]">
								<SelectValue />
							</SelectTrigger>
							<SelectContent>
								{Object.entries(CATEGORY_FILTERS).map(([value, label]) => (
									<SelectItem key={value} value={value}>
										{label}
									</SelectItem>
								))}
							</SelectContent>
						</Select>
						<Select items={MODE_FILTERS} value={modeFilter} onValueChange={(value) => setModeFilter(value ?? 'ALL')}>
							<SelectTrigger className="w-[150px]">
								<SelectValue />
							</SelectTrigger>
							<SelectContent>
								{Object.entries(MODE_FILTERS).map(([value, label]) => (
									<SelectItem key={value} value={value}>
										{label}
									</SelectItem>
								))}
							</SelectContent>
						</Select>
						<div className="ml-auto flex flex-wrap items-center gap-2">
							<span className="text-xs text-[#5f7389]">
								已选 {selectedCases.length} 个{activeMode ? ` · ${modeLabel(activeMode)}` : ''}
							</span>
							<Button size="sm" variant="ghost" onClick={() => setSelectedIds([])} disabled={selectedIds.length === 0}>
								清除
							</Button>
							<Button size="sm" variant="outline" onClick={selectAllOffline} disabled={(capabilities?.offlineCases ?? 0) === 0}>
								全选离线案例
							</Button>
							<Button size="sm" onClick={openConfirm} disabled={selectedCases.length === 0}>
								<Play className="mr-1.5 h-3.5 w-3.5" />
								运行所选案例
							</Button>
						</div>
					</Toolbar>

					{casesQuery.isPending ? (
						<LoadingState label="加载评测案例…" />
					) : casesQuery.isError ? (
						<ErrorState message={errorMessage(casesQuery.error, '加载评测案例失败')} onRetry={() => casesQuery.refetch()} />
					) : filteredCases.length === 0 ? (
						<EmptyState title="没有匹配的评测案例" description="调整搜索或筛选条件后重试。" />
					) : (
						<Surface className="overflow-x-auto p-0 sm:p-0">
							<Table>
								<TableHeader>
									<TableRow>
										<TableHead className="w-10 pl-4">
											<span className="sr-only">选择</span>
										</TableHead>
										<TableHead>案例</TableHead>
										<TableHead>场景</TableHead>
										<TableHead>模式</TableHead>
										<TableHead>数据集断言</TableHead>
										<TableHead>版本</TableHead>
										<TableHead className="text-right">操作</TableHead>
									</TableRow>
								</TableHeader>
								<TableBody>
									{filteredCases.map((item) => {
										const checked = selectedIds.includes(item.id);
										const compatible = canAddToSelection(cases, selectedIds, item.id);
										return (
											<TableRow key={item.id} data-state={checked ? 'selected' : undefined}>
												<TableCell className="pl-4">
													<Checkbox
														checked={checked}
														disabled={!compatible && !checked}
														title={!compatible && !checked ? '一次运行只能包含同一模式的案例' : undefined}
														onCheckedChange={() => toggleCase(item)}
														aria-label={`选择 ${item.name}`}
													/>
												</TableCell>
												<TableCell className="max-w-[340px]">
													<div className="truncate text-sm font-medium text-[#1f2a37]" title={item.name}>
														{item.name}
													</div>
													<div className="font-mono text-[11px] text-[#8aa2ba]">{item.id}</div>
												</TableCell>
												<TableCell className="text-xs text-[#5f7389]">{categoryLabel(item.category)}</TableCell>
												<TableCell>
													<Badge variant="outline" className={item.mode === 'AGENT' ? 'border-[#d8d3f4] bg-[#f5f2fd] text-[#5a4ea8]' : 'border-[#d6e4ef] bg-[#f3f8fc] text-[#3c6186]'}>
														{modeLabel(item.mode)}
													</Badge>
												</TableCell>
												<TableCell className="text-xs text-[#5f7389]">{item.assertions.length} 条</TableCell>
												<TableCell className="font-mono text-[11px] text-[#7a8da1]">{item.version}</TableCell>
												<TableCell className="text-right">
													<Button size="sm" variant="ghost" onClick={() => setCaseDetail(item)}>
														详情
													</Button>
												</TableCell>
											</TableRow>
										);
									})}
								</TableBody>
							</Table>
						</Surface>
					)}

					<p className="text-xs leading-relaxed text-[#6a7d91]">
						离线案例调用后端确定性的风险、库存、供应商与词典检索服务，不调用模型，也不写入业务表。真实工作流案例会提交当前后端的分析或决策任务。
					</p>
				</div>
			)}

			{view === 'runs' && (
				<div className="grid min-w-0 gap-4 lg:grid-cols-[minmax(0,340px)_minmax(0,1fr)]">
					<Surface className="min-w-0 p-3 sm:p-4">
						<SectionHeading title="运行记录" description="结果来自持久化的评测运行，不显示预置成绩。" />
						{runsQuery.isPending ? (
							<LoadingState compact label="加载运行记录…" />
						) : runsQuery.isError ? (
							<ErrorState message={errorMessage(runsQuery.error, '加载运行记录失败')} onRetry={() => runsQuery.refetch()} />
						) : runs.length === 0 ? (
							<div className="py-6">
								<EmptyState
									title="尚无评测运行"
									description="选择案例并手动启动一次离线评测后，结果会出现在这里。"
									action={<Button size="sm" onClick={() => setView('cases')}>前往选择案例</Button>}
								/>
							</div>
						) : (
							<ul className="max-h-[560px] space-y-1 overflow-y-auto pr-1">
								{runs.map((run) => {
									const active = selectedRunId === run.runId;
									return (
										<li key={run.runId}>
											<button
												type="button"
												onClick={() => openRun(run.runId)}
												data-active={active}
												className={cn(
													'w-full min-w-0 rounded-xl px-3 py-2.5 text-left transition-colors duration-200',
													active ? 'bg-white/85 shadow-[0_1px_2px_rgba(23,42,64,0.05)]' : 'hover:bg-white/55',
												)}
											>
												<div className="flex min-w-0 items-center justify-between gap-2">
													<span className="min-w-0 flex-1 truncate font-mono text-[11px] text-[#7a8da1]">{run.runId}</span>
													<Badge variant="outline" className={runTone(run.status)}>
														{runStatusLabel(run.status)}
													</Badge>
												</div>
												<div className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-[#5f7389]">
													<span>{modeLabel(run.mode)}</span>
													<span className="font-mono">{run.datasetVersion}</span>
													<span>{run.totalCases} 案例</span>
													<span className="text-[#3f7a5d]">{run.passed} 通过</span>
													<span className="text-[#a45151]">{run.failed} 失败</span>
													{run.undetermined > 0 && <span className="text-[#9a772f]">{run.undetermined} 无法判定</span>}
												</div>
												{runFailureLabel(run) && (
													<div className="mt-1 break-words text-[11px] text-[#a45151]">{runFailureLabel(run)}</div>
												)}
												<div className="mt-1 text-[11px] text-[#8aa2ba]">{formatTime(run.startedAt)}</div>
											</button>
										</li>
									);
								})}
							</ul>
						)}
					</Surface>

					<Surface className="min-w-0">
						{!selectedRunId ? (
							<EmptyState title="选择一个运行" description="左侧列表展示当前后端保存的评测运行。" />
						) : runDetailQuery.isPending ? (
							<LoadingState label="加载运行详情…" />
						) : runDetailQuery.isError ? (
							<ErrorState message={errorMessage(runDetailQuery.error, '加载运行详情失败')} onRetry={() => runDetailQuery.refetch()} />
						) : runDetailQuery.data ? (
							<RunDetail
								run={runDetailQuery.data}
								definitions={cases}
								expandedResult={expandedResult}
								onExpand={setExpandedResult}
								onRefresh={() => runDetailQuery.refetch()}
								onRerun={requestRerun}
							/>
						) : null}
					</Surface>
				</div>
			)}

			{view === 'compare' && (
				runsQuery.isPending ? (
					<LoadingState label="加载运行记录…" />
				) : runsQuery.isError ? (
					<ErrorState message={errorMessage(runsQuery.error, '加载运行记录失败')} onRetry={() => runsQuery.refetch()} />
				) : (
					<ComparisonPanel runs={runs} />
				)
			)}

			{view === 'feedback' && <FeedbackPanel onChanged={() => { casesQuery.refetch(); capabilitiesQuery.refetch(); runsQuery.refetch(); }} />}

			{view === 'metrics' && (
				metricsQuery.isPending ? (
					<LoadingState label="加载评测指标…" />
				) : metricsQuery.isError ? (
					<ErrorState message={errorMessage(metricsQuery.error, '加载评测指标失败')} onRetry={() => metricsQuery.refetch()} />
				) : (metricsQuery.data?.runsAnalyzed ?? 0) === 0 ? (
					<Surface>
						<EmptyState
							title="暂无可计算的指标"
							description="指标只统计已完成的真实评测运行。先运行一次离线评测，再回来查看分模式的通过率、失败率、P95 与场景结果。"
							action={<Button size="sm" onClick={() => setView('cases')}>前往选择案例</Button>}
						/>
					</Surface>
				) : (
					<MetricsView report={metricsQuery.data!} />
				)
			)}

			<Dialog open={caseDetail !== null} onOpenChange={(open) => !open && setCaseDetail(null)}>
				<DialogContent className="max-h-[86vh] overflow-y-auto sm:max-w-2xl">
					{caseDetail && (
						<CaseDetail
							definition={caseDetail}
							onOpenRun={(runId) => {
								setCaseDetail(null);
								openRun(runId);
							}}
						/>
					)}
				</DialogContent>
			</Dialog>

			<RunConfirmDialog
				key={runRequest ? `${runRequest.mode}-${runRequest.caseIds.join('|')}` : 'closed'}
				open={runRequest !== null}
				mode={runRequest?.mode ?? 'OFFLINE'}
				caseCount={runRequest?.caseIds.length ?? 0}
				caseLabel={runRequest?.label}
				caseNames={(runRequest?.caseIds ?? []).map((id) => cases.find((item) => item.id === id)?.name ?? id)}
				embeddingRequiredBySelection={(runRequest?.caseIds ?? []).some((id) => cases.find((item) => item.id === id)?.embeddingRequired === true)}
				chatRequiredBySelection={(runRequest?.caseIds ?? []).some((id) => cases.find((item) => item.id === id)?.chatRequired !== false)}
				capabilities={capabilities}
				pending={startRun.isPending}
				onCancel={() => setRunRequest(null)}
				onConfirm={submitRun}
			/>
		</div>
	);
}

function AgentReadinessPanel({ capabilities }: { capabilities?: EvaluationCapabilities }) {
	if (!capabilities) return null;
	const ready = capabilities.agentEvaluationReady === true;
	return (
		<Surface>
			<div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
				<div className="flex min-w-0 gap-3">
					<div className={cn(
						'flex h-9 w-9 shrink-0 items-center justify-center rounded-2xl ring-1',
						ready ? 'bg-[#eef7f1] text-[#3f7a5d] ring-[#d5e9db]' : 'bg-[#fdf8ee] text-[#9a772f] ring-[#ecd9b5]',
					)}>
						{ready ? <CheckCircle2 className="h-4 w-4" /> : <AlertTriangle className="h-4 w-4" />}
					</div>
					<div className="min-w-0">
						<div className="flex flex-wrap items-center gap-2">
							<span className="text-sm font-medium text-[#1f2a37]">真实 AGENT 前置条件</span>
							<Badge variant="outline" className={ready ? 'border-green-200 bg-green-50 text-green-700' : 'border-amber-200 bg-amber-50 text-amber-700'}>
								{ready ? '已满足' : '未满足'}
							</Badge>
						</div>
						<p className="mt-1 text-xs leading-relaxed text-[#5f7389]">
							CHAT：{readinessStateLabel(capabilities.chatState)}
							{capabilities.chatTestedAt ? `（测试于 ${formatTime(capabilities.chatTestedAt)}）` : ''}
							{' · '}EMBEDDING：{readinessStateLabel(capabilities.embeddingState)}
							{capabilities.embeddingRequiredForAgentEvaluation ? '（必需）' : '（可选）'}
						</p>
						{capabilities.chatTestError && (
							<p className="mt-1 break-words text-xs text-[#a45151]">最近一次连接测试失败：{capabilities.chatTestError}</p>
						)}
						{!ready && capabilities.agentEvaluationBlockReason && (
							<p className="mt-1 text-xs leading-relaxed text-[#8a6a34]">{capabilities.agentEvaluationBlockReason}</p>
						)}
						{ready && (
							<p className="mt-1 text-xs leading-relaxed text-[#6a7d91]">
								连接测试成功只证明模型端点可调用；真实工作流是否成功、结构化输出是否通过校验，仍以运行记录为准。
							</p>
						)}
						{capabilities.embeddingDependencyNote && (
							<p className="mt-1 text-xs leading-relaxed text-[#7a8da1]">{capabilities.embeddingDependencyNote}</p>
						)}
					</div>
				</div>
				{!ready && (
					<Link href="/system/model-config" className="shrink-0 text-xs font-medium text-[#35629d] hover:underline">
						前往模型配置与测试
					</Link>
				)}
			</div>
		</Surface>
	);
}

function RunDetail({
	run,
	definitions,
	expandedResult,
	onExpand,
	onRefresh,
	onRerun,
}: {
	run: EvaluationRun;
	definitions: EvaluationCaseDefinition[];
	expandedResult: string | null;
	onExpand: (caseId: string | null) => void;
	onRefresh: () => void;
	onRerun: (caseId: string, mode: EvaluationMode) => void;
}) {
	const definitionById = useMemo(
		() => new Map(definitions.map((definition) => [definition.id, definition])),
		[definitions],
	);
	const failureReason = runFailureLabel(run);

	return (
		<div className="space-y-4">
			<div className="flex flex-wrap items-start justify-between gap-3">
				<div className="min-w-0">
					<p className="break-all font-mono text-xs text-[#7a8da1]">{run.runId}</p>
					<div className="mt-1 flex flex-wrap items-center gap-2">
						<Badge variant="outline" className={runTone(run.status)}>
							{runStatusLabel(run.status)}
						</Badge>
						<Badge variant="outline">{modeLabel(run.mode)}</Badge>
						<span className="text-xs text-[#5f7389]">案例集 {run.datasetVersion}</span>
					</div>
				</div>
				<div className="text-right text-xs text-[#7a8da1]">
					<div>开始 {formatTime(run.startedAt)}</div>
					{run.completedAt && <div>完成 {formatTime(run.completedAt)}</div>}
					<div>耗时 {formatDuration(run.durationMs)}</div>
				</div>
			</div>

			{failureReason && (
				<div className="flex items-start gap-2 rounded-xl border border-[#ecd9b5] bg-[#fdf8ee] px-3 py-2 text-xs text-[#7a5a22]">
					<AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
					<span>{failureReason}</span>
				</div>
			)}

			<div className="flex flex-wrap gap-x-8 gap-y-3 border-y border-[#e6eef7] py-3">
				<InlineStat label="案例" value={run.totalCases} />
				<InlineStat label="通过" value={<span className="text-[#3f7a5d]">{run.passed}</span>} />
				<InlineStat label="失败" value={<span className="text-[#a45151]">{run.failed}</span>} />
				<InlineStat label="无法判定" value={<span className="text-[#9a772f]">{run.undetermined}</span>} />
				<InlineStat label="环境" value={<span className="text-xs font-medium">{run.environment}</span>} />
			</div>

			{run.status === 'RUNNING' && (
				<div className="flex items-center gap-2 text-xs text-[#3c5f82]">
					<Loader2 className="h-3.5 w-3.5 animate-spin" />
					真实工作流任务仍在执行，结果会随轮询自动更新。
				</div>
			)}

			{run.results.length === 0 ? (
				<EmptyState title="运行尚未产生案例结果" description="真实工作流评测提交任务后，结果会逐步写入。" />
			) : (
				<div className="overflow-x-auto">
					<Table>
						<TableHeader>
							<TableRow>
								<TableHead className="w-8" />
								<TableHead>案例</TableHead>
								<TableHead>状态</TableHead>
								<TableHead>版本</TableHead>
								<TableHead>耗时</TableHead>
								<TableHead>断言</TableHead>
								<TableHead className="text-right">任务</TableHead>
							</TableRow>
						</TableHeader>
						<TableBody>
							{run.results.map((result) => {
								const expanded = expandedResult === result.caseId;
								const taskLink = resolveTaskLink(result);
								const definition = definitionById.get(result.caseId);
								const passedAssertions = result.assertions.filter((item) => item.passed).length;
								return (
									<Fragment key={result.caseId}>
										<TableRow>
											<TableCell>
												<button
													type="button"
													onClick={() => onExpand(expanded ? null : result.caseId)}
													className="rounded-md p-1 text-[#6a7d91] hover:bg-white/70"
													aria-label={expanded ? '收起案例详情' : '展开案例详情'}
												>
													{expanded ? <ChevronDown className="h-4 w-4" /> : <ChevronRight className="h-4 w-4" />}
												</button>
											</TableCell>
											<TableCell className="max-w-[300px]">
												<div className="truncate text-sm text-[#1f2a37]" title={result.name}>{result.name}</div>
												<div className="text-[11px] text-[#8aa2ba]">{categoryLabel(result.category)} · {result.caseId}</div>
												{result.failureMessage && (
													<div className="mt-1 break-words text-[11px] text-[#a45151]">{result.failureMessage}</div>
												)}
											</TableCell>
											<TableCell>
												<Badge variant="outline" className={caseTone(result.status)}>
													{caseStatusLabel(result.status)}
												</Badge>
											</TableCell>
											<TableCell className="whitespace-nowrap font-mono text-[11px] text-[#7a8da1]">
												{result.caseVersion ?? '—'}
											</TableCell>
											<TableCell className="whitespace-nowrap text-xs text-[#5f7389]">{formatDuration(result.durationMs)}</TableCell>
											<TableCell className="whitespace-nowrap text-xs text-[#5f7389]">
												{result.assertions.length === 0 ? '—' : `${passedAssertions}/${result.assertions.length}`}
											</TableCell>
											<TableCell className="text-right">
												{taskLink ? (
													<Link href={taskLink.href} className="inline-flex items-center gap-1 text-xs font-medium text-[#35629d] hover:underline">
														{taskLink.label}
														<ExternalLink className="h-3 w-3" />
													</Link>
												) : (
													<span className="text-xs text-[#9aabbc]">—</span>
												)}
											</TableCell>
										</TableRow>
										{expanded && (
											<TableRow key={`${result.caseId}-detail`}>
												<TableCell colSpan={7} className="bg-[#f8fbfe]/70">
													<div className="space-y-4 py-2">
														<div className="grid gap-3 text-xs sm:grid-cols-2">
															<div>
																<div className="font-medium text-[#7a8da1]">案例版本 / 任务类型</div>
																<div className="mt-0.5 text-[#33506b]">
																	{result.caseVersion ?? '—'} · {taskModeLabel(result.taskMode)}
																</div>
															</div>
															<div>
																<div className="font-medium text-[#7a8da1]">失败类型</div>
																<div className="mt-0.5 text-[#33506b]">{result.failureType ?? '—'}</div>
															</div>
															{definition && (
																<>
																	<div className="sm:col-span-2">
																		<div className="font-medium text-[#7a8da1]">问题 / 输入</div>
																		<div className="mt-0.5 rounded-lg bg-white/60 px-2 py-1.5 text-[#33506b]">
																			{definition.question || (definition.targetId ? `确定性数据集案例：${definition.targetId}` : '—')}
																		</div>
																	</div>
																	<div className="sm:col-span-2">
																		<div className="font-medium text-[#7a8da1]">预期行为</div>
																		<div className="mt-0.5 text-[#33506b]">{definition.expectedBehavior}</div>
																	</div>
																	<div className="sm:col-span-2">
																		<div className="font-medium text-[#7a8da1]">评测依据</div>
																		<div className="mt-0.5 text-[#5f7389]">{definition.basis}</div>
																	</div>
																</>
															)}
														</div>

														{result.failureMessage && (
															<div className="rounded-lg border border-[#f0d7d9] bg-[#fff6f6] px-2.5 py-2 text-xs text-[#8f3b45]">
																<div className="font-medium">{result.failureType ?? 'FAILED'}</div>
																<div className="mt-0.5 break-words">{result.failureMessage}</div>
															</div>
														)}

														<div>
															<div className="mb-1 text-xs font-medium text-[#7a8da1]">具体断言</div>
															{result.assertions.length === 0 ? (
																<p className="text-xs text-[#6a7d91]">该结果没有断言记录。</p>
															) : (
																<ul className="space-y-2">
																	{result.assertions.map((assertion) => (
																		<li key={assertion.id} className="flex items-start gap-2 text-xs">
																			{assertion.passed ? (
																				<CheckCircle2 className="mt-0.5 h-3.5 w-3.5 shrink-0 text-[#3f7a5d]" />
																			) : (
																				<XCircle className="mt-0.5 h-3.5 w-3.5 shrink-0 text-[#a45151]" />
																			)}
																			<div className="min-w-0">
																				<div className="font-medium text-[#33506b]">{assertion.description}</div>
																				<div className="mt-0.5 break-words text-[#5f7389]">
																					期望：{assertion.expected} · 实际：{assertion.actual}
																				</div>
																			</div>
																		</li>
																	))}
																</ul>
															)}
														</div>

														<div className="flex flex-wrap items-center gap-2">
															{taskLink && (
																<Link href={taskLink.href}>
																	<Button size="sm" variant="outline">
																		<FileText className="mr-1.5 h-3.5 w-3.5" />
																		{run.mode === 'AGENT' ? '打开任务与报告' : '打开任务'}
																	</Button>
																</Link>
															)}
															<Button size="sm" variant="outline" onClick={() => onRerun(result.caseId, run.mode)}>
																<RotateCcw className="mr-1.5 h-3.5 w-3.5" />
																重新运行此案例
															</Button>
														</div>

														<TaskTrace taskId={result.taskId ?? null} taskLink={taskLink?.href ?? null} />
													</div>
												</TableCell>
											</TableRow>
										)}
									</Fragment>
								);
							})}
						</TableBody>
					</Table>
				</div>
			)}

			{run.metrics && Object.keys(run.metrics).length > 0 && (
				<details className="rounded-xl border border-[#e2e8f0] bg-white/50 px-3 py-2">
					<summary className="cursor-pointer text-xs font-medium text-[#33506b]">运行时的聚合指标快照</summary>
					<pre className="mt-2 max-h-[220px] overflow-auto whitespace-pre-wrap text-[11px] text-[#5f7389]">
						{JSON.stringify(run.metrics, null, 2)}
					</pre>
				</details>
			)}

			<Button size="sm" variant="outline" onClick={onRefresh}>
				<RefreshCw className="mr-1.5 h-3.5 w-3.5" />
				重新读取结果
			</Button>
		</div>
	);
}

function TaskTrace({ taskId, taskLink }: { taskId: string | null; taskLink: string | null }) {
	const eventsQuery = useQuery({
		queryKey: ['taskEvents', taskId],
		queryFn: () => fetchTaskEvents(taskId!),
		enabled: taskId !== null,
	});

	if (!taskId) {
		return (
			<p className="text-xs text-[#7a8da1]">
				离线确定性案例没有 taskId，因此不生成任务、报告或事件链接。
			</p>
		);
	}

	return (
		<div className="rounded-lg border border-[#e2e8f0] bg-white/55 px-2.5 py-2">
			<div className="flex flex-wrap items-center justify-between gap-2">
				<div className="flex items-center gap-1.5 text-xs font-medium text-[#33506b]">
					<Activity className="h-3.5 w-3.5" />
					任务事件时间线
				</div>
				{taskLink && (
					<Link href={taskLink} className="text-xs font-medium text-[#35629d] hover:underline">
						打开任务页
					</Link>
				)}
			</div>
			{eventsQuery.isPending ? (
				<LoadingState compact label="加载任务事件…" />
			) : eventsQuery.isError ? (
				<p className="mt-1 text-xs text-[#8f3b45]">{errorMessage(eventsQuery.error, '加载任务事件失败')}</p>
			) : (eventsQuery.data ?? []).length === 0 ? (
				<p className="mt-1 text-xs text-[#6a7d91]">该任务暂无可查询的事件记录。</p>
			) : (
				<ul className="mt-1 max-h-[180px] space-y-1 overflow-y-auto text-[11px] text-[#5f7389]">
					{(eventsQuery.data ?? []).map((event) => (
						<li key={event.eventId} className="flex flex-wrap gap-2">
							<span className="font-mono text-[#8aa2ba]">#{event.sequence}</span>
							<span>{event.occurredAt ? formatTime(event.occurredAt) : '—'}</span>
							<span className="font-medium text-[#33506b]">{event.stage ?? event.type}</span>
							<span>{event.status ?? '—'}</span>
							{event.summary && <span className="min-w-0 break-words">{event.summary}</span>}
							{event.errorType && <span className="text-[#a45151]">{event.errorType}</span>}
						</li>
					))}
				</ul>
			)}
		</div>
	);
}

function CaseDetail({
	definition,
	onOpenRun,
}: {
	definition: EvaluationCaseDefinition;
	onOpenRun: (runId: string) => void;
}) {
	const historyQuery = useEvaluationCaseHistory(definition.id, 20);

	return (
		<div className="space-y-4">
			<DialogHeader>
				<DialogTitle className="flex flex-wrap items-center gap-2">
					{definition.name}
					<Badge variant="outline" className={definition.mode === 'AGENT' ? 'border-[#d8d3f4] bg-[#f5f2fd] text-[#5a4ea8]' : ''}>
						{modeLabel(definition.mode)}
					</Badge>
				</DialogTitle>
				<DialogDescription>
					{definition.id} · {categoryLabel(definition.category)} · 版本 {definition.version}
				</DialogDescription>
			</DialogHeader>

			<dl className="grid gap-3 text-sm">
				<div>
					<dt className="text-xs font-medium text-[#7a8da1]">问题 / 输入</dt>
					<dd className="mt-1 rounded-xl bg-[#f6f9fc] px-3 py-2 text-[#33506b]">{definition.question || '—'}</dd>
				</div>
				<div>
					<dt className="text-xs font-medium text-[#7a8da1]">预期行为</dt>
					<dd className="mt-1 text-[#33506b]">{definition.expectedBehavior}</dd>
				</div>
				<div>
					<dt className="text-xs font-medium text-[#7a8da1]">依据</dt>
					<dd className="mt-1 text-[#5f7389]">{definition.basis}</dd>
				</div>
				{definition.targetId && (
					<div>
						<dt className="text-xs font-medium text-[#7a8da1]">目标数据</dt>
						<dd className="mt-1 font-mono text-xs text-[#33506b]">{definition.targetId}</dd>
					</div>
				)}
				<div>
					<dt className="text-xs font-medium text-[#7a8da1]">数据集断言（{definition.assertions.length}）</dt>
					<dd className="mt-1">
						<ul className="list-disc space-y-1 pl-5 text-[#33506b]">
							{definition.assertions.map((assertion) => (
								<li key={assertion}>{assertion}</li>
							))}
						</ul>
					</dd>
				</div>
			</dl>

			<div className="border-t border-[#e6eef7] pt-3">
				<SectionHeading title="该案例的历史" description="只展示已持久化运行中的真实结果。" />
				{historyQuery.isPending ? (
					<LoadingState compact label="加载案例历史…" />
				) : historyQuery.isError ? (
					<ErrorState message={errorMessage(historyQuery.error, '加载案例历史失败')} onRetry={() => historyQuery.refetch()} />
				) : (historyQuery.data ?? []).length === 0 ? (
					<p className="text-xs text-[#6a7d91]">该案例尚未出现在任何已终结的评测运行中。</p>
				) : (
					<ul className="space-y-1.5">
						{(historyQuery.data ?? []).map((entry) => (
							<li
								key={`${entry.runId}-${entry.startedAt}`}
								className="flex flex-wrap items-center justify-between gap-2 rounded-lg border border-[#e6eef7] px-2.5 py-1.5 text-xs"
							>
								<div className="flex flex-wrap items-center gap-2">
									<Badge variant="outline" className={caseTone(entry.caseStatus)}>
										{caseStatusLabel(entry.caseStatus)}
									</Badge>
									<span className="text-[#5f7389]">{modeLabel(entry.mode)}</span>
									<span className="font-mono text-[#7a8da1]">{entry.caseVersion ?? '—'}</span>
									<span className="text-[#8aa2ba]">{formatTime(entry.startedAt)}</span>
									<span className="text-[#7a8da1]">{formatDuration(entry.durationMs)}</span>
								</div>
								<button
									type="button"
									onClick={() => onOpenRun(entry.runId)}
									className="font-medium text-[#35629d] hover:underline"
								>
									查看运行
								</button>
							</li>
						))}
					</ul>
				)}
			</div>
		</div>
	);
}

function MetricsView({ report }: { report: EvaluationMetricsReport }) {
	const offline = report.byMode.find((metrics) => metrics.mode === 'OFFLINE');
	const agent = report.byMode.find((metrics) => metrics.mode === 'AGENT');
	return (
		<div className="space-y-4">
			<Surface className="flex flex-col gap-3">
				<div className="flex flex-wrap gap-x-8 gap-y-4">
					<InlineStat label="计入统计的运行" value={report.runsAnalyzed} />
					<InlineStat label="排除的不完整运行" value={report.excludedIncompleteRuns} />
				</div>
				<p className="text-xs leading-relaxed text-[#7a8da1]">
					离线确定性结果与真实工作流结果分开统计，不合并成一个“模型成功率”。只有结果为完整终态、且案例数与计数一致的 COMPLETED 运行会计入分母。
				</p>
			</Surface>

			{offline && <ModeMetrics metrics={offline} description="确定性的风险、库存、供应商与词典检索检查；不调用模型。" showRetrieval />}
			{agent && <ModeMetrics metrics={agent} description="真实提交供应链分析/决策任务；只有在明确确认后才由用户启动。RAG 指标不在此模式下重复展示。" />}
		</div>
	);
}

function ModeMetrics({
	metrics,
	description,
	showRetrieval = false,
}: {
	metrics: EvaluationMetrics;
	description: string;
	showRetrieval?: boolean;
}) {
	if (metrics.caseSamples === 0) {
		return (
			<Surface>
				<SectionHeading title={modeLabel(metrics.mode)} description={description} />
				<p className="text-sm text-[#6a7d91]">
					暂无该模式下结果完整的 COMPLETED 运行，因此不展示通过率、耗时或 P95。
				</p>
			</Surface>
		);
	}

	return (
		<Surface>
			<SectionHeading
				title={modeLabel(metrics.mode)}
				description={description}
				actions={
					<Badge variant="outline" className={metrics.mode === 'OFFLINE' ? 'border-[#d6e4ef] bg-[#f3f8fc] text-[#3c6186]' : 'border-[#d8d3f4] bg-[#f5f2fd] text-[#5a4ea8]'}>
						{metrics.runsAnalyzed} 次完整运行 · {metrics.caseSamples} 个案例样本
					</Badge>
				}
			/>
			<div className="mb-4 flex flex-wrap gap-x-8 gap-y-3 border-b border-[#e6eef7] pb-4">
				<InlineStat label="通过率" value={<span className="text-[#3f7a5d]">{formatPercent(metrics.passRate)}</span>} />
				<InlineStat label="失败率" value={<span className="text-[#a45151]">{formatPercent(metrics.failRate)}</span>} />
				<InlineStat label="无法判定率" value={<span className="text-[#9a772f]">{formatPercent(metrics.undeterminedRate)}</span>} />
				<InlineStat label="案例平均耗时" value={formatDuration(metrics.averageDurationMs)} />
				<InlineStat label="案例 P95" value={<span className="text-[13px] font-medium">{p95Label(metrics)}</span>} />
				<InlineStat label="平均运行耗时" value={formatDuration(metrics.averageRunDurationMs)} />
				<InlineStat
					label="运行 P95"
					value={
						<span className="text-[13px] font-medium">
							{metrics.p95RunDurationMs === null || metrics.p95RunDurationMs === undefined
								? `样本不足（n=${metrics.runSampleSize}，至少 5 个）`
								: `${formatDuration(metrics.p95RunDurationMs)}（n=${metrics.runSampleSize}）`}
						</span>
					}
				/>
			</div>

			<div className="grid gap-4 lg:grid-cols-2">
				<div>
					<SectionHeading title="按场景分类的结果" description="失败率按该场景中失败样本 / 总样本计算。" />
					{metrics.byScenario.length === 0 ? (
						<p className="text-sm text-[#6a7d91]">暂无场景样本。</p>
					) : (
						<div className="overflow-x-auto">
							<Table>
								<TableHeader>
									<TableRow>
										<TableHead>场景</TableHead>
										<TableHead>样本</TableHead>
										<TableHead>通过</TableHead>
										<TableHead>失败</TableHead>
										<TableHead>无法判定</TableHead>
										<TableHead>失败率</TableHead>
									</TableRow>
								</TableHeader>
								<TableBody>
									{metrics.byScenario.map((scenario) => (
										<TableRow key={scenario.category}>
											<TableCell className="text-sm text-[#1f2a37]">{categoryLabel(scenario.category)}</TableCell>
											<TableCell>{scenario.total}</TableCell>
											<TableCell className="text-[#3f7a5d]">{scenario.passed}</TableCell>
											<TableCell className="text-[#a45151]">{scenario.failed}</TableCell>
											<TableCell className="text-[#9a772f]">{scenario.undetermined}</TableCell>
											<TableCell>{formatPercent(scenario.failureRate)}</TableCell>
										</TableRow>
									))}
								</TableBody>
							</Table>
						</div>
					)}
				</div>

				<div>
					<SectionHeading title="常见失败原因" description="来自已持久化失败样本的前 5 项。" />
					{metrics.topFailureReasons.length === 0 ? (
						<p className="text-sm text-[#6a7d91]">当前样本中没有失败案例。</p>
					) : (
						<ul className="space-y-2">
							{metrics.topFailureReasons.map((reason) => (
								<li
									key={`${reason.failureType}-${reason.message}`}
									className="flex items-start justify-between gap-3 rounded-xl border border-[#e6eef7] px-3 py-2"
								>
									<div className="min-w-0">
										<div className="text-sm font-medium text-[#33506b]">{reason.failureType}</div>
										<div className="mt-0.5 break-words text-xs text-[#5f7389]">{reason.message}</div>
									</div>
									<Badge variant="secondary">{reason.count} 次</Badge>
								</li>
							))}
						</ul>
					)}
				</div>
			</div>

			{showRetrieval && (
				<div className="mt-4 border-t border-[#e6eef7] pt-4">
					<SectionHeading
						title="RAG 检索指标（仅离线）"
						description="仅在离线运行包含检索样本且数据支持时展示；不补齐缺失指标。"
					/>
					{!metrics.retrieval ? (
						<p className="text-sm text-[#6a7d91]">当前样本没有可计算的检索查询。</p>
					) : (
						<div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
							<InlineStat label="评估查询" value={metrics.retrieval.evaluatedQueries} />
							<InlineStat label="前 3 命中" value={`${metrics.retrieval.hits} / ${metrics.retrieval.evaluatedQueries}`} />
							<InlineStat label="HitRate@3" value={formatPercent(metrics.retrieval.hitRateAt3)} />
							<InlineStat label="MRR" value={metrics.retrieval.mrr.toFixed(3)} />
							<InlineStat label="负样本查询" value={metrics.retrieval.negativeQueries} />
							<InlineStat label="负样本通过" value={metrics.retrieval.negativePasses} />
						</div>
					)}
					<p className="mt-3 text-xs leading-relaxed text-[#7a8da1]">
						检索指标来自离线词典检索样本（无向量模型时使用后端确定性检索），不代表生产向量检索效果。Precision@K 未在后端数据中单独采集，因此不展示。
					</p>
				</div>
			)}
		</Surface>
	);
}
