'use client';

import { useState } from 'react';
import Link from 'next/link';
import { useQuery } from '@tanstack/react-query';
import { ArrowRight, ClipboardCheck, FlaskConical, Home, Loader2, Play, RefreshCw, ShieldCheck, Sparkles } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { PageHeader } from '@/components/page-header';
import { ErrorState, LoadingState } from '@/components/data-states';
import { BulletList, Metric, Section, StatusBadge, formatTime, signed } from '@/components/supply-chain/common';
import { RiskBadge } from '@/components/supply-chain/common';
import { useAnalysisTask } from '@/components/supply-chain/use-analysis-task';
import { friendlyErrorMessage } from '@/lib/api/client';
import {
	createComparison,
	fetchReport,
	fetchSimulationBaseline,
	type ScenarioComparison,
} from '@/lib/api/supplyChain';
import { DEMO_SCENARIO } from '@/lib/demo-scenario';

const DEMO_STEPS = [
	{ id: 1, label: '供应链风险分析' },
	{ id: 2, label: '补货方案对比' },
	{ id: 3, label: '查看决策依据' },
] as const;

const BOUNDARY_NOTES = [
	'推荐仅依据当前风险与库存指标计算。',
	'后端未提供补货成本、预算、库容、供应商产能与到货日期，这些不在排名依据内。',
	'排名第一不等于方案已获得业务授权；进入执行必须先经提案确认。',
	'需要人工确认补货成本、到货时间以及库存占用。',
];

export default function SupplyChainDemoPage() {
	const taskRunner = useAnalysisTask(null);
	const [comparison, setComparison] = useState<ScenarioComparison | null>(null);
	const [comparing, setComparing] = useState(false);
	const [comparisonError, setComparisonError] = useState<string | null>(null);

	const reportQuery = useQuery({
		queryKey: ['demoReport', taskRunner.taskId],
		queryFn: () => {
			if (!taskRunner.taskId) throw new Error('任务尚未就绪');
			return fetchReport(taskRunner.taskId);
		},
		enabled: Boolean(taskRunner.taskId) && taskRunner.task?.status === 'COMPLETED',
		retry: false,
	});

	const baselineQuery = useQuery({
		queryKey: ['simulationBaseline'],
		queryFn: fetchSimulationBaseline,
		staleTime: 30_000,
	});

	const step1Done = taskRunner.task?.status === 'COMPLETED' && reportQuery.isSuccess;
	const step2Done = comparison !== null;
	const currentStep = step2Done ? 3 : step1Done ? 2 : 1;

	const runAnalysis = () => {
		void taskRunner.start(DEMO_SCENARIO.question, 'ANALYSIS');
	};

	const runComparison = async () => {
		setComparing(true);
		setComparisonError(null);
		try {
			const result = await createComparison({
				title: `演示补货对比：${DEMO_SCENARIO.simulationSku}`,
				scenario: 'INVENTORY_REPLENISHMENT',
				sku: DEMO_SCENARIO.simulationSku,
				candidates: DEMO_SCENARIO.comparisonCandidates.map((quantity) => ({
					label: `补货 +${quantity} 件`,
					replenishmentQuantity: Number(quantity),
				})),
			});
			setComparison(result);
		} catch (error) {
			setComparisonError(friendlyErrorMessage(error, '方案对比失败', {
				0: '无法连接后端服务，请确认 :8065 服务已启动。',
			}));
		} finally {
			setComparing(false);
		}
	};

	const resetDemo = () => {
		taskRunner.reset();
		setComparison(null);
		setComparisonError(null);
	};

	const recommendation = comparison?.recommendation ?? null;

	return (
		<div className="space-y-5">
			<PageHeader
				title="业务演示"
				breadcrumb="演示"
				description="一条可运行的供应链决策流程：风险分析 → 补货方案对比 → 决策依据，全程由真实后端计算。"
			/>

			<div className="rounded-2xl border border-[#dce8f2] bg-white/70 px-4 py-3.5">
				<div className="flex flex-wrap items-center gap-2">
					<Badge variant="outline" className="gap-1 border-[#cfdff0] bg-[#f5f9fd] text-[#35629d]">
						<FlaskConical className="h-3 w-3" />
						内置演示数据 · 只读演示
					</Badge>
					<Badge variant="outline" className="gap-1 border-[#cfe8da] bg-[#f4faf7] text-[#428766]">
						<ShieldCheck className="h-3 w-3" />
						不修改真实业务数据
					</Badge>
					<Badge variant="outline" className="gap-1 border-[#e5dcc8] bg-[#fcfaf4] text-[#8a6a34]">
						<Sparkles className="h-3 w-3" />
						真实模型未配置 · 确定性回退
					</Badge>
				</div>
				<p className="mt-2 text-[13px] leading-relaxed text-[#5a708c]">
					演示使用内置场景（供应商 {DEMO_SCENARIO.context[0]?.replace('重点供应商：', '')}）。
					运行过程会在后端创建真实的分析任务与对比记录，但不修改真实库存、订单或外部系统；方案确认与沙箱执行不会自动发生。
				</p>
				<div className="mt-3 flex flex-wrap items-center gap-1.5 border-t border-[#e8f0f7] pt-2.5">
					{DEMO_STEPS.map((step) => {
						const done = step.id < currentStep;
						const active = step.id === currentStep;
						return (
							<span
								key={step.id}
								className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-[11.5px] font-medium ${
									active ? 'bg-[#2b5583] text-white' : done ? 'bg-[#eef6f0] text-[#428766]' : 'bg-white/60 text-[#7a8da1]'
								}`}
							>
								<span className={`flex h-4 w-4 items-center justify-center rounded-full text-[9px] ${active ? 'bg-white/20' : 'bg-[#edf4fa]'}`}>
									{done ? '✓' : step.id}
								</span>
								{step.label}
							</span>
						);
					})}
				</div>
			</div>

			<Section
				title="第一步：供应链风险分析"
				description={`使用后端真实分析任务接口，场景：${DEMO_SCENARIO.title}`}
				actions={
					taskRunner.running ? (
						<Badge variant="secondary" className="gap-1"><Loader2 className="h-3 w-3 animate-spin" />分析任务执行中</Badge>
					) : (
						<Button onClick={runAnalysis} disabled={taskRunner.submitting}>
							{taskRunner.submitting ? <Loader2 className="mr-2 h-4 w-4 animate-spin" /> : <Play className="mr-2 h-4 w-4" />}
							{taskRunner.taskId ? '重新运行分析' : '运行风险分析'}
						</Button>
					)
				}
			>
				<div className="space-y-3">
					{taskRunner.error && <p className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-[#8f3b45]">{taskRunner.error}</p>}
					{taskRunner.task && (
						<div className="flex flex-wrap items-center gap-3 text-xs text-[#5a708c]">
							<StatusBadge status={taskRunner.task.status} />
							<span className="font-mono" title={taskRunner.taskId ?? undefined}>任务 {(taskRunner.taskId ?? '').slice(0, 8)}…</span>
							<span>当前步骤 {taskRunner.task.currentStep ?? '—'}</span>
							<span>更新 {formatTime(taskRunner.task.updatedAt)}</span>
						</div>
					)}
					{taskRunner.running && <LoadingState compact label="后端正在分析订单风险与库存缺口…" />}
					{reportQuery.isPending && taskRunner.task?.status === 'COMPLETED' && <LoadingState compact label="加载分析报告…" />}
					{reportQuery.isError && (
						<ErrorState
							title="分析报告不可用"
							message={friendlyErrorMessage(reportQuery.error, '报告尚未生成', { 404: '任务记录不存在，请重新运行分析。' })}
							onRetry={() => reportQuery.refetch()}
						/>
					)}
					{reportQuery.data && (
						<div className="space-y-3">
							<p className="whitespace-pre-wrap break-words text-sm leading-relaxed text-[#1c3048]">{reportQuery.data.summary || '后端未返回摘要。'}</p>
							<div className="grid gap-2 sm:grid-cols-4">
								<Metric label="订单总数" value={baselineQuery.data?.metrics.totalOrders ?? '—'} />
								<Metric label="风险订单" value={baselineQuery.data?.metrics.atRiskOrders ?? reportQuery.data.atRiskOrders.length} hint="非低风险" />
								<Metric label="高风险订单" value={baselineQuery.data?.metrics.highRiskOrders ?? '—'} />
								<Metric label="缺口总量" value={baselineQuery.data?.metrics.totalShortageUnits ?? '—'} hint="件" />
							</div>
							<div className="space-y-2">
								{reportQuery.data.atRiskOrders.slice(0, 5).map((item) => (
									<div key={item.order.orderId} className="flex flex-wrap items-center justify-between gap-2 rounded-lg border border-[#e2e8f0] px-3 py-2">
										<span className="text-sm text-[#1c3048]">
											{item.order.orderId}
											<span className="ml-2 text-xs text-[#5a708c]">{item.order.sku} · {item.order.supplierId}</span>
										</span>
										<span className="flex items-center gap-2">
											<RiskBadge level={item.riskLevel} />
											<span className="text-xs font-semibold text-[#1c3048]">{item.riskScore}/100</span>
										</span>
									</div>
								))}
							</div>
							{step1Done && (
								<p className="text-xs text-[#428766]">第一步完成；可继续第二步，或<Link href={`/supply-chain?taskId=${encodeURIComponent(taskRunner.taskId ?? '')}`} className="font-medium text-[#35629d] hover:underline">查看完整分析报告</Link>。</p>
							)}
						</div>
					)}
					{!taskRunner.taskId && !taskRunner.error && (
						<p className="text-sm text-[#5a708c]">点击「运行风险分析」后，后端会创建一个真实的分析任务并返回风险订单、缺口与库存数据。</p>
					)}
				</div>
			</Section>

			<Section
				title="第二步：补货方案对比"
				description={`对 ${DEMO_SCENARIO.simulationSku} 的三个补货候选做确定性对比（+${DEMO_SCENARIO.comparisonCandidates.join(' / +')} 件）`}
				actions={
					comparing ? (
						<Badge variant="secondary" className="gap-1"><Loader2 className="h-3 w-3 animate-spin" />对比计算中</Badge>
					) : (
						<Button variant="outline" onClick={() => { void runComparison(); }} disabled={comparing}>
							{comparison ? <RefreshCw className="mr-2 h-4 w-4" /> : <Play className="mr-2 h-4 w-4" />}
							{comparison ? '重新运行对比' : '运行三个候选对比'}
						</Button>
					)
				}
			>
				<div className="space-y-3">
					{comparisonError && <p className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-[#8f3b45]">{comparisonError}</p>}
					{!comparison && !comparing && (
						<p className="text-sm text-[#5a708c]">
							点击后由后端 Java 确定性引擎基于当前业务快照逐一模拟三个候选并排名；会保存一条对比记录，但不修改真实业务数据。
						</p>
					)}
					{comparison && (
						<>
							<div className="hidden sm:block">
								<table className="w-full text-sm">
									<thead>
										<tr className="border-b border-[#e2e8f0] text-left text-xs text-[#5a708c]">
											<th className="py-1.5 pr-3">候选</th>
											<th className="py-1.5 pr-3">排名</th>
											<th className="py-1.5 pr-3">缺口变化</th>
											<th className="py-1.5 pr-3">模拟 ATP</th>
											<th className="py-1.5">关键变化</th>
										</tr>
									</thead>
									<tbody>
										{(comparison.rankings ?? []).map((ranking) => {
											const candidate = comparison.candidates?.find((item) => item.candidateId === ranking.candidateId);
											return (
												<tr key={ranking.candidateId} className={`border-b border-[#f1f5f9] ${ranking.recommended ? 'bg-[#f0f7fd]' : ''}`}>
													<td className="py-1.5 pr-3 font-medium">
														{ranking.label ?? ranking.candidateId}
														{ranking.recommended && <span className="ml-1 text-xs text-[#428766]">（推荐）</span>}
													</td>
													<td className="py-1.5 pr-3">{ranking.rank ?? '不可比'}</td>
													<td className={`py-1.5 pr-3 font-medium ${(candidate?.simulation?.delta?.totalShortageUnits ?? 0) < 0 ? 'text-[#428766]' : ''}`}>
														{signed(candidate?.simulation?.delta?.totalShortageUnits)}
													</td>
													<td className="py-1.5 pr-3">{candidate?.simulation?.simulated?.targetSkuAvailableToPromise ?? '—'}</td>
													<td className="py-1.5 text-xs">{(ranking.keyDeltas ?? []).join('；') || '—'}</td>
												</tr>
											);
										})}
									</tbody>
								</table>
							</div>
							<div className="space-y-2 sm:hidden">
								{(comparison.rankings ?? []).map((ranking) => {
									const candidate = comparison.candidates?.find((item) => item.candidateId === ranking.candidateId);
									return (
										<div key={ranking.candidateId} className={`rounded-xl px-3 py-2.5 ring-1 ${ranking.recommended ? 'bg-[#f0f7fd] ring-[#bcd6ee]' : 'bg-white/60 ring-white/70'}`}>
											<div className="flex flex-wrap items-center justify-between gap-2">
												<span className="text-sm font-semibold text-[#1c3048]">
													{ranking.label ?? ranking.candidateId}
													{ranking.recommended && <span className="ml-1 text-xs text-[#428766]">（推荐）</span>}
												</span>
												<span className="text-xs text-[#5a708c]">排名 {ranking.rank ?? '不可比'}</span>
											</div>
											<div className="mt-1.5 flex flex-wrap gap-x-4 gap-y-0.5 text-xs text-[#33506b]">
												<span>缺口变化 {signed(candidate?.simulation?.delta?.totalShortageUnits)}</span>
												<span>模拟 ATP {candidate?.simulation?.simulated?.targetSkuAvailableToPromise ?? '—'}</span>
											</div>
											{(ranking.keyDeltas ?? []).length > 0 && <p className="mt-1 text-xs text-[#5a708c]">{(ranking.keyDeltas ?? []).join('；')}</p>}
										</div>
									);
								})}
							</div>
							<p className="text-xs text-[#5a708c]">
								以上数字全部来自后端本次真实计算（基线 {comparison.baseline?.totalShortageUnits ?? '—'} 件缺口 · 风险订单 {comparison.baseline?.atRiskOrders ?? '—'} 个 · 当前 ATP {comparison.baseline?.targetSkuAvailableToPromise ?? '—'} 件）。
							</p>
						</>
					)}
				</div>
			</Section>

			{comparison && (
				<Section
					title="第三步：查看决策依据"
					description="推荐理由、候选权衡、未知项与人工确认清单均来自后端对比结果"
				>
					<div className="space-y-3">
						{recommendation?.summary && (
							<p className="whitespace-pre-wrap break-words text-sm leading-relaxed text-[#1c3048]">{recommendation.summary}</p>
						)}
						<div>
							<p className="text-[13px] font-semibold text-[#233448]">推荐理由</p>
							<BulletList items={recommendation?.reasons} empty="后端未返回推荐理由。" />
						</div>
						<div>
							<p className="text-[13px] font-semibold text-[#233448]">候选权衡</p>
							<BulletList items={recommendation?.tradeoffs} empty="后端未返回权衡说明。" />
						</div>
						<div>
							<p className="text-[13px] font-semibold text-[#233448]">未知项</p>
							<BulletList items={recommendation?.unknowns} empty="后端未返回未知项。" />
						</div>
						<div>
							<p className="text-[13px] font-semibold text-[#233448]">需要人工确认</p>
							<BulletList items={recommendation?.manualConfirmation} empty="后端未返回人工确认项。" />
						</div>
						<div className="rounded-lg border border-[#ecd9b5] bg-[#fdf8ee] px-3 py-2.5">
							<p className="flex items-center gap-1.5 text-xs font-medium text-[#7a5a22]">
								<ClipboardCheck className="h-3.5 w-3.5" />
								边界说明
							</p>
							<BulletList items={BOUNDARY_NOTES} />
						</div>
						{recommendation?.disclaimer && <p className="text-xs text-[#7a5a22]">{recommendation.disclaimer}</p>}
					</div>
				</Section>
			)}

			<Section title="演示结束后" description="不会自动生成提案、确认方案或执行沙箱">
				<div className="flex flex-wrap gap-2">
					<Link href="/"><Button variant="outline"><Home className="mr-2 h-4 w-4" />返回首页</Button></Link>
					<Button variant="outline" onClick={resetDemo}><RefreshCw className="mr-2 h-4 w-4" />重新运行演示</Button>
					{taskRunner.taskId && (
						<Link href={`/supply-chain?taskId=${encodeURIComponent(taskRunner.taskId)}`}>
							<Button variant="outline">查看完整任务结果<ArrowRight className="ml-2 h-4 w-4" /></Button>
						</Link>
					)}
					{comparison && (
						<Link href={`/supply-chain/disposal?taskId=${encodeURIComponent(taskRunner.taskId ?? '')}`}>
							<Button variant="outline">打开处置工作台<ArrowRight className="ml-2 h-4 w-4" /></Button>
						</Link>
					)}
					<Link href="/supply-chain/history"><Button variant="outline">查看任务历史</Button></Link>
					<Link href="/supply-chain/simulation"><Button variant="outline">进入情景模拟<ArrowRight className="ml-2 h-4 w-4" /></Button></Link>
				</div>
			</Section>
		</div>
	);
}
