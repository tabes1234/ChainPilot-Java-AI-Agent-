'use client';

import { useEffect, useRef, useState } from 'react';
import Link from 'next/link';
import { useQuery } from '@tanstack/react-query';
import { AlertTriangle, FlaskConical, Loader2, Play, RefreshCw, ShieldCheck } from 'lucide-react';
import { toast } from 'sonner';
import { PageHeader } from '@/components/page-header';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Field, Section, StatusBadge, formatTime } from '@/components/supply-chain/common';
import { friendlyErrorMessage } from '@/lib/api/client';
import { useAnalysisTask } from '@/components/supply-chain/use-analysis-task';
import {
	fetchEvaluationCapabilities,
	fetchEvaluationCases,
	fetchEvaluationRun,
	fetchEvaluationRuns,
	startEvaluationRun,
	type EvaluationCaseDefinition,
} from '@/lib/api/evaluation';

type DrillScenario = {
	id: string;
	name: string;
	description: string;
	workflowMode: 'ANALYSIS' | 'DECISION';
	question: string;
	expectedBehavior: string;
	assertionCaseIds: string[];
};

const DRILL_SCENARIOS: DrillScenario[] = [
	{
		id: 'supplier-delay-analysis',
		name: '供应商延迟 → 高风险订单分析',
		description: '提交真实分析任务，执行风险评分与报告生成，并用后端确定性断言校验高风险订单、供应商画像与库存缺口结果。',
		workflowMode: 'ANALYSIS',
		question: '供应商 SUP-ALPHA 出现交付延迟，SKU-MCU-001 库存不足，部分订单可能延期。请分析受影响订单、风险等级和主要证据，并给出可执行的处置建议。',
		expectedBehavior: '任务完成并生成可验证的分析报告：包含延迟与短缺风险订单、供应商高风险证据及处置建议。',
		assertionCaseIds: ['ORDER-ORD-1001', 'ORDER-ORD-1002', 'SUPPLIER-SUP-ALPHA'],
	},
	{
		id: 'shortage-decision',
		name: '库存缺口 → 补货决策建议',
		description: '提交真实决策任务，执行候选方案比较与确定性排名，并用后端确定性断言校验库存可承诺量与风险订单口径。',
		workflowMode: 'DECISION',
		question: '请比较补货与催交付两类处置方案，给出推荐方案和理由。',
		expectedBehavior: '任务完成并生成决策结果：包含至少 2 个候选方案、确定性排名与推荐依据，不修改真实业务数据。',
		assertionCaseIds: ['INVENTORY-SKU-MCU-001', 'ORDER-ORD-1001', 'ORDER-ORD-1004'],
	},
];

const AGENT_SHOWCASE_CASE_ID = 'WF-ANALYSIS-HIGH-RISK';

function CaseStatusBadge({ status }: { status: string }) {
	return <StatusBadge status={status} />;
}

export default function WorkflowEvaluationPage() {
	const capabilitiesQuery = useQuery({
		queryKey: ['workflowDrillCapabilities'],
		queryFn: fetchEvaluationCapabilities,
		staleTime: 30_000,
	});
	const casesQuery = useQuery({
		queryKey: ['workflowDrillCases'],
		queryFn: fetchEvaluationCases,
		staleTime: 60_000,
	});
	const runsHistoryQuery = useQuery({
		queryKey: ['workflowDrillHistory'],
		queryFn: () => fetchEvaluationRuns(8),
	});

	const [scenarioId, setScenarioId] = useState(DRILL_SCENARIOS[0].id);
	const [selectedCaseIds, setSelectedCaseIds] = useState<string[]>(DRILL_SCENARIOS[0].assertionCaseIds);
	const [runId, setRunId] = useState<string | null>(null);
	const [evaluationPending, setEvaluationPending] = useState(false);
	const [agentMessage, setAgentMessage] = useState<{ ok: boolean; text: string } | null>(null);
	const scenario = DRILL_SCENARIOS.find((item) => item.id === scenarioId) ?? DRILL_SCENARIOS[0];
	const taskRunner = useAnalysisTask(null);
	const evaluationStartedRef = useRef(false);

	const runQuery = useQuery({
		queryKey: ['workflowDrillRun', runId],
		queryFn: () => fetchEvaluationRun(runId as string),
		enabled: Boolean(runId),
		refetchInterval: (query) => (query.state.data?.status === 'RUNNING' ? 2500 : false),
	});
	const run = runQuery.data ?? null;

	const startEvaluation = async () => {
		setEvaluationPending(true);
		try {
			const started = await startEvaluationRun(selectedCaseIds, 'OFFLINE');
			setRunId(started.runId);
			toast.info('后端断言评测已启动', { description: `运行 ${started.runId.slice(0, 8)}… · ${selectedCaseIds.length} 个断言案例` });
		} catch (error) {
			toast.error('评测启动失败', { description: friendlyErrorMessage(error, '后端评测接口不可用。') });
			evaluationStartedRef.current = false;
		} finally {
			setEvaluationPending(false);
		}
	};

	useEffect(() => {
		if (taskRunner.task?.status !== 'COMPLETED' || evaluationStartedRef.current || evaluationPending) return;
		evaluationStartedRef.current = true;
		void startEvaluation();
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, [taskRunner.task?.status]);

	const startDrill = () => {
		if (selectedCaseIds.length === 0) {
			toast.error('请至少选择一个断言案例');
			return;
		}
		evaluationStartedRef.current = false;
		setRunId(null);
		setAgentMessage(null);
		void taskRunner.start(scenario.question, scenario.workflowMode);
	};

	const tryAgentRun = async () => {
		setAgentMessage(null);
		try {
			const started = await startEvaluationRun([AGENT_SHOWCASE_CASE_ID], 'AGENT', true);
			setRunId(started.runId);
			setAgentMessage({ ok: true, text: 'AGENT 评测已启动：已提交真实工作流任务，运行状态与断言结果见下方评测报告。' });
		} catch (error) {
			setAgentMessage({ ok: false, text: friendlyErrorMessage(error, 'AGENT 评测启动失败。') });
		}
	};

	const toggleCase = (caseId: string) => {
		setSelectedCaseIds((prev) => (prev.includes(caseId) ? prev.filter((id) => id !== caseId) : [...prev, caseId]));
	};

	const caseById = (id: string): EvaluationCaseDefinition | undefined => casesQuery.data?.find((item) => item.id === id);
	const workflowFinished = taskRunner.task?.status === 'COMPLETED';
	const workflowFailed = taskRunner.task?.status === 'FAILED' || taskRunner.task?.status === 'CANCELLED';

	return (
		<div className="space-y-5">
			<PageHeader
				title="工作流评测演练"
				breadcrumb="风险决策"
				description="选择评测场景，启动真实后端工作流并观察执行过程，最终由后端评测断言计算通过/失败结论。"
				actions={
					<div className="flex flex-wrap gap-2">
						<Button variant="outline" size="sm" onClick={() => { taskRunner.reset(); evaluationStartedRef.current = false; setRunId(null); setAgentMessage(null); }}>
							<RefreshCw className="mr-2 h-4 w-4" />
							重置演练
						</Button>
						<Link href="/agent-evaluation"><Button variant="outline" size="sm">打开评测中心</Button></Link>
					</div>
				}
			/>

			<div className="rounded-xl border border-[#bcd6ee] bg-[#f0f7fd] px-4 py-3">
				<div className="flex flex-wrap items-center gap-1.5 text-[13px] font-medium text-[#2b5583]">
					<ShieldCheck className="h-4 w-4" />
					<span>隔离的工作流评测 · 不修改真实供应链业务数据</span>
				</div>
				<p className="mt-1.5 text-xs leading-relaxed text-[#4a769f]">
					演练会提交真实分析/决策任务（模型未配置时由后端确定性规则回退执行，只读计算），并创建可追溯的评测运行记录；
					不会修改真实订单、库存或供应商数据，不会自动确认提案或执行沙箱。通过/失败结论由后端评测断言计算，不由前端判定。
				</p>
				{capabilitiesQuery.data && (
					<div className="mt-2 flex flex-wrap gap-1.5">
						<Badge variant="outline">数据集 {capabilitiesQuery.data.datasetVersion}</Badge>
						<Badge variant="outline">离线断言案例 {capabilitiesQuery.data.offlineCases}</Badge>
						<Badge variant="outline">Agent 工作流案例 {capabilitiesQuery.data.agentCases}</Badge>
						<Badge variant={capabilitiesQuery.data.agentEvaluationReady ? 'default' : 'secondary'}>
							AGENT 模式{capabilitiesQuery.data.agentEvaluationReady ? '就绪' : '未就绪'}
						</Badge>
					</div>
				)}
			</div>

			<Section title="第一步 · 选择评测场景" description="每个场景对应一次真实后端工作流执行">
				<div className="grid gap-3 md:grid-cols-2">
					{DRILL_SCENARIOS.map((item) => (
						<button
							key={item.id}
							className={`rounded-2xl p-4 text-left ring-1 transition-colors ${item.id === scenarioId ? 'bg-[#f0f7fd] ring-[#bcd6ee]' : 'bg-white/55 ring-white/70 hover:bg-white/85'}`}
							onClick={() => { setScenarioId(item.id); setSelectedCaseIds(item.assertionCaseIds); }}
						>
							<div className="flex items-center gap-2">
								<FlaskConical className="h-4 w-4 text-[#4a769f]" />
								<span className="text-[15px] font-semibold text-[#1c3048]">{item.name}</span>
								<Badge variant="outline" className="ml-auto">{item.workflowMode}</Badge>
							</div>
							<p className="mt-2 text-[13px] leading-relaxed text-[#5a708c]">{item.description}</p>
						</button>
					))}
				</div>
			</Section>

			<Section title="第二步 · 查看输入与配置断言案例" description="输入来自真实业务数据口径；断言案例由后端评测目录提供">
				<div className="space-y-3">
					<Field label="任务输入（真实工作流问题）" value={<p className="whitespace-pre-wrap text-sm text-[#1c3048]">{scenario.question}</p>} />
					<Field label="预期行为" value={<p className="text-sm text-[#5a708c]">{scenario.expectedBehavior}</p>} />
					<div>
						<p className="mb-1.5 text-xs font-medium text-[#33506b]">后端断言案例（勾选参与本次评测，至少 1 个）</p>
						{casesQuery.isPending ? (
							<p className="text-sm text-[#7a8da1]">加载案例目录…</p>
						) : (
							<div className="flex flex-col gap-1.5">
								{scenario.assertionCaseIds.map((caseId) => {
									const definition = caseById(caseId);
									return (
										<label key={caseId} className="flex cursor-pointer items-start gap-2 rounded-lg border border-[#e2e8f0] bg-white/60 px-3 py-2 text-sm hover:bg-white/90">
											<input
												type="checkbox"
												className="mt-1"
												checked={selectedCaseIds.includes(caseId)}
												onChange={() => toggleCase(caseId)}
											/>
											<span className="min-w-0">
												<span className="font-mono text-xs text-[#33506b]">{caseId}</span>
												<span className="ml-2 text-[#1c3048]">{definition?.name ?? ''}</span>
												{definition && <span className="mt-0.5 block text-xs text-[#5a708c]">{definition.expectedBehavior}</span>}
											</span>
										</label>
									);
								})}
							</div>
						)}
					</div>
					<div className="flex flex-wrap items-center gap-2">
						<Button className="bg-[#35629d] hover:bg-[#264c79]" disabled={taskRunner.submitting || taskRunner.running} onClick={startDrill}>
							{taskRunner.submitting || taskRunner.running ? <Loader2 className="mr-2 h-4 w-4 animate-spin" /> : <Play className="mr-2 h-4 w-4" />}
							开始演练
						</Button>
						{taskRunner.running && <span className="text-xs text-[#7a8da1]">工作流执行中，任务状态与事件来自后端轮询</span>}
					</div>
				</div>
			</Section>

			{(taskRunner.task || taskRunner.error) && (
				<Section
					title="第三步 · 真实工作流执行过程"
					description="任务状态、执行事件均来自后端任务与事件接口"
					actions={<StatusBadge status={taskRunner.task?.status} />}
				>
					<div className="space-y-2">
						{taskRunner.error && <p className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-[#8f3b45]">{taskRunner.error}</p>}
						{workflowFailed && (
							<div className="flex items-start gap-2 rounded-md border border-[#ecd9b5] bg-[#fdf8ee] px-3 py-2 text-sm text-[#7a5a22]">
								<AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
								<span>工作流未正常完成，本次演练不会产生评测结论；可调整后重新演练。</span>
							</div>
						)}
						<div className="flex flex-wrap gap-4 text-xs text-[#5a708c]">
							<span className="font-mono">任务 {(taskRunner.taskId ?? '').slice(0, 8)}…</span>
							<span>当前步骤 {taskRunner.task?.currentStep ?? '—'}</span>
							<span>校验尝试 {taskRunner.task?.validationAttempts ?? '—'}</span>
							<span>更新 {formatTime(taskRunner.task?.updatedAt)}</span>
						</div>
						{taskRunner.events.length > 0 ? (
							<details open className="rounded-lg border border-[#e2e8f0] bg-[#f8fbfe] px-3 py-2">
								<summary className="cursor-pointer text-sm font-medium text-[#33506b]">执行事件（{taskRunner.events.length}）· 来自后端事件流</summary>
								<ul className="mt-2 max-h-[240px] space-y-1 overflow-y-auto text-xs text-[#5a708c]">
									{taskRunner.events.map((event) => (
										<li key={event.eventId} className="flex flex-wrap gap-x-2">
											<span className="font-mono">#{event.sequence}</span>
											<StatusBadge status={event.status} />
											<span>{event.stage ?? event.type}</span>
											{event.summary && <span className="text-[#7a8da1]">{event.summary}</span>}
										</li>
									))}
								</ul>
							</details>
						) : (
							<p className="text-xs text-[#7a8da1]">暂无执行事件；任务启动后此处显示后端记录的真实事件流。</p>
						)}
						{evaluationPending && (
							<p className="flex items-center gap-2 text-xs text-[#5a708c]"><Loader2 className="h-3 w-3 animate-spin" />工作流已完成，正在启动后端断言评测…</p>
						)}
					</div>
				</Section>
			)}

			{run && (
				<Section
					title="第四步 · 评测报告"
					description="通过/失败由后端评测断言计算；OFFLINE 断言由确定性生产服务独立重算，AGENT 断言校验真实工作流产物；评测运行已持久化，可追溯"
					actions={
						<div className="flex flex-wrap items-center gap-1.5">
							<StatusBadge status={run.status} />
							<Badge variant="outline">{run.mode}</Badge>
						</div>
					}
				>
					<div className="space-y-3">
						<div className="grid gap-3 sm:grid-cols-4">
							<Field label="评测运行 ID" value={<span className="font-mono text-xs" title={run.runId}>{run.runId.slice(0, 8)}…</span>} />
							<Field label="案例集版本" value={run.mode === 'OFFLINE' ? capabilitiesQuery.data?.offlineCaseSetVersion ?? '—' : capabilitiesQuery.data?.agentCaseSetVersion ?? '—'} />
							<Field label="通过 / 失败 / 未定" value={`${run.passed} / ${run.failed} / ${run.undetermined}`} />
							<Field label="关联工作流任务" value={taskRunner.taskId ? <span className="font-mono text-xs">{taskRunner.taskId.slice(0, 8)}…</span> : '—'} />
						</div>
						{run.failureReason && <p className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-[#8f3b45]">{run.failureReason}</p>}
						{run.status === 'RUNNING' && (
							<p className="flex items-center gap-2 text-sm text-[#5a708c]"><Loader2 className="h-4 w-4 animate-spin" />后端断言执行中…</p>
						)}
						{run.results.map((result) => (
							<div key={result.caseId} className="rounded-lg border border-[#e2e8f0] p-3">
								<div className="flex flex-wrap items-center gap-2">
									<span className="font-medium text-[#1c3048]">{result.name}</span>
									<span className="font-mono text-[11px] text-[#8aa2ba]">{result.caseId}</span>
									<CaseStatusBadge status={result.status} />
									<span className="text-xs text-[#7a8da1]">{result.durationMs} ms</span>
								</div>
								{result.failureMessage && <p className="mt-1 text-xs text-[#8f3b45]">{result.failureMessage}</p>}
								<div className="mt-2 overflow-x-auto">
									<table className="w-full text-xs">
										<thead>
											<tr className="border-b border-[#e2e8f0] text-left text-[#5a708c]">
												<th className="py-1 pr-3">断言</th>
												<th className="py-1 pr-3">预期</th>
												<th className="py-1 pr-3">实际</th>
												<th className="py-1">结果</th>
											</tr>
										</thead>
										<tbody>
											{result.assertions.map((assertion) => (
												<tr key={assertion.id} className="border-b border-[#f1f5f9] align-top">
													<td className="py-1.5 pr-3 text-[#1c3048]">{assertion.description}</td>
													<td className="py-1.5 pr-3 text-[#5a708c]">{assertion.expected}</td>
													<td className="py-1.5 pr-3 text-[#33506b]">{assertion.actual}</td>
													<td className="py-1.5">
														<span className={assertion.passed ? 'font-medium text-[#428766]' : 'font-medium text-[#b9575c]'}>
															{assertion.passed ? '通过' : '失败'}
														</span>
													</td>
												</tr>
											))}
										</tbody>
									</table>
								</div>
								{result.taskId && (
									<p className="mt-1.5 text-xs text-[#5a708c]">
										案例关联任务 {result.taskId.slice(0, 8)}…
										<Link href={`/supply-chain?taskId=${encodeURIComponent(result.taskId)}`} className="ml-1 text-[#35629d] hover:underline">查看任务详情</Link>
									</p>
								)}
							</div>
						))}
						{workflowFinished && (
							<div className="flex flex-wrap gap-2">
								<Link href={scenario.workflowMode === 'DECISION' ? `/supply-chain/decision?taskId=${encodeURIComponent(taskRunner.taskId ?? '')}` : `/supply-chain?taskId=${encodeURIComponent(taskRunner.taskId ?? '')}`}>
									<Button variant="outline" size="sm">查看完整工作流结果</Button>
								</Link>
								<Button variant="outline" size="sm" disabled={taskRunner.running} onClick={startDrill}>
									<Play className="mr-2 h-4 w-4" />
									重新演练
								</Button>
							</div>
						)}
					</div>
				</Section>
			)}

			<Section title="完整 Agent 工作流评测（AGENT 模式）" description="提交真实任务并调用模型，由后端就绪门禁控制；未就绪时如实展示阻断原因">
				<div className="space-y-2">
					<p className="text-sm text-[#5a708c]">
						后端目录内置 {capabilitiesQuery.data?.agentCases ?? 6} 个 AGENT 工作流案例（{AGENT_SHOWCASE_CASE_ID} 等），会执行含模型调用的完整工作流。
						当前状态：{capabilitiesQuery.data?.agentEvaluationReady ? '就绪，可启动。' : capabilitiesQuery.data?.agentEvaluationBlockReason ?? '未就绪。'}
					</p>
					<Button variant="outline" className="whitespace-normal" disabled={capabilitiesQuery.isPending} onClick={() => void tryAgentRun()}>
						<FlaskConical className="mr-2 h-4 w-4" />
						尝试启动 AGENT 评测（{AGENT_SHOWCASE_CASE_ID}）
					</Button>
					{agentMessage && (
						<p className={`rounded-md border px-3 py-2 text-sm ${agentMessage.ok ? 'border-green-200 bg-green-50 text-[#2f6a4a]' : 'border-[#ecd9b5] bg-[#fdf8ee] text-[#7a5a22]'}`}>
							{agentMessage.text}
						</p>
					)}
				</div>
			</Section>

			<Section title="演练运行记录" description="评测运行由后端持久化，每次运行独立 ID，不会相互覆盖">
				{runsHistoryQuery.isPending ? (
					<p className="text-sm text-[#7a8da1]">加载运行记录…</p>
				) : runsHistoryQuery.isError ? (
					<p className="text-sm text-[#8f3b45]">运行记录加载失败。</p>
				) : (runsHistoryQuery.data ?? []).length === 0 ? (
					<p className="text-sm text-[#5a708c]">暂无评测运行记录；完成一次演练后会显示在这里。</p>
				) : (
					<div className="space-y-1.5">
						{(runsHistoryQuery.data ?? []).map((historyRun) => (
							<button
								key={historyRun.runId}
								className="flex w-full flex-wrap items-center gap-x-3 gap-y-1 rounded-lg border border-[#e2e8f0] px-3 py-2 text-left hover:bg-[#f8fbfe]"
								onClick={() => setRunId(historyRun.runId)}
							>
								<span className="font-mono text-xs text-[#33506b]">{historyRun.runId.slice(0, 8)}…</span>
								<StatusBadge status={historyRun.status} />
								<Badge variant="outline">{historyRun.mode}</Badge>
								<span className="text-xs text-[#5a708c]">通过 {historyRun.passed} · 失败 {historyRun.failed} · 未定 {historyRun.undetermined}</span>
								<span className="ml-auto text-xs text-[#7a8da1]">{formatTime(historyRun.startedAt)}</span>
							</button>
						))}
					</div>
				)}
			</Section>
		</div>
	);
}
