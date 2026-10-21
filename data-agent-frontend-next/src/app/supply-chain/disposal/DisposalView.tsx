'use client';

import { useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { useUrlParam } from '@/lib/use-url-param';
import { toast } from 'sonner';
import { Loader2, Play, Plus, RefreshCw, Trash2, X } from 'lucide-react';
import { useQuery } from '@tanstack/react-query';
import { PageHeader } from '@/components/page-header';
import { ErrorState, LoadingState } from '@/components/data-states';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { errorMessage, friendlyErrorMessage } from '@/lib/api/client';
import {
	createComparison,
	createExecution,
	createPlan,
	createPlanFromCandidate,
	createSimulation,
	fetchComparison,
	fetchComparisons,
	fetchEligibility,
	fetchExecution,
	fetchExecutions,
	fetchPlan,
	fetchPlans,
	fetchRecommendations,
	fetchSimulationBaseline,
	updatePlanStatus,
	type DisposalPlan,
	type ExecutionEligibility,
	type PlanStatus,
	type Recommendation,
	type SandboxExecution,
	type SandboxExecutionSummary,
	type ScenarioComparison,
	type Simulation,
	type SimulationBaselineView,
	type SimulationRequest,
	type SimulationScenario,
} from '@/lib/api/supplyChain';
import {
	BulletList,
	Field,
	Metric,
	RiskBadge,
	Section,
	StatusBadge,
	formatTime,
	signed,
} from '@/components/supply-chain/common';
import { SimulationMetrics } from '@/components/supply-chain/decision-view';
import { DemoBanner } from '@/components/supply-chain/demo-banner';
import { DEMO_SCENARIO, isDemoScenario } from '@/lib/demo-scenario';
import { RecommendationDetails } from '@/components/supply-chain/recommendation-details';
import { RiskEvidencePanel } from '@/components/supply-chain/risk-evidence';

const PLAN_STATUS_ITEMS: Record<string, string> = {
	ALL: '全部状态',
	PENDING_CONFIRMATION: '待确认',
	SELECTED: '已选用',
	REJECTED: '不采用',
};
const ACTION_LABELS: Record<string, string> = {
	REPLENISH_INVENTORY: '补货',
	FOLLOW_UP_SUPPLIER: '催交付',
	MANUAL_CONFIRMATION: '人工确认',
};
const SCENARIO_LABELS: Record<SimulationScenario, string> = {
	INVENTORY_REPLENISHMENT: '库存补货',
	SUPPLIER_DELAY: '供应商延迟',
};

type SimForm = { scenario: SimulationScenario; sku: string; quantity: string; supplierId: string; delayDays: string };

export default function DisposalView() {
	const linkedTaskId = useUrlParam('taskId') ?? undefined;
	const initialPlanId = useUrlParam('planId');
	const demoParam = useUrlParam('demo');
	const demoMode = isDemoScenario(demoParam);

	const [scope, setScope] = useState<'ALL' | 'SKU' | 'SUPPLIER'>('ALL');
	const [scopeValue, setScopeValue] = useState('');
	const [appliedScope, setAppliedScope] = useState<{ sku?: string; supplierId?: string }>({});

	const recommendationsQuery = useQuery({
		queryKey: ['disposalRecommendations', appliedScope.sku ?? '', appliedScope.supplierId ?? ''],
		queryFn: () => fetchRecommendations(appliedScope),
	});

	const baselineQuery = useQuery({ queryKey: ['simulationBaseline'], queryFn: fetchSimulationBaseline });
	const [simForm, setSimForm] = useState<SimForm>({ scenario: 'INVENTORY_REPLENISHMENT', sku: '', quantity: '50', supplierId: '', delayDays: '7' });
	const [comparisonSku, setComparisonSku] = useState('');
	const [candidates, setCandidates] = useState<{ label: string; value: string }[]>([
		{ label: '方案 A', value: '30' },
		{ label: '方案 B', value: '60' },
	]);
	useEffect(() => {
		if (!demoMode) return;
		queueMicrotask(() => {
			setSimForm((current) => ({ ...current, sku: DEMO_SCENARIO.simulationSku, quantity: String(DEMO_SCENARIO.simulationQuantity) }));
			setComparisonSku(DEMO_SCENARIO.simulationSku);
			setCandidates(DEMO_SCENARIO.comparisonCandidates.map((value, index) => ({ label: `方案 ${String.fromCharCode(65 + index)}`, value })));
		});
	}, [demoMode]);
	const [simulation, setSimulation] = useState<Simulation | null>(null);
	const [simRunning, setSimRunning] = useState(false);

	const [planTitle, setPlanTitle] = useState('供应链风险处置方案');
	const [attachSimulation, setAttachSimulation] = useState(true);
	const [linkTask, setLinkTask] = useState(Boolean(linkedTaskId));
	const [generateExplanation, setGenerateExplanation] = useState(true);
	const [planBusy, setPlanBusy] = useState(false);

	const [planStatusFilter, setPlanStatusFilter] = useState<string>('ALL');
	const [planPage, setPlanPage] = useState(1);
	const plansQuery = useQuery({
		queryKey: ['disposalPlans', planStatusFilter, planPage],
		queryFn: () => fetchPlans({ page: planPage, pageSize: 5, status: planStatusFilter === 'ALL' ? undefined : (planStatusFilter as PlanStatus) }),
	});

	// `initialPlanId` is empty during server rendering and becomes available after
	// hydration; keep it as the live fallback until the user explicitly picks/closes a plan.
	const [planOverride, setPlanOverride] = useState<string | null | undefined>(undefined);
	const detailPlanId = planOverride === undefined ? initialPlanId : planOverride;
	const setDetailPlanId = setPlanOverride;
	const planQuery = useQuery({
		queryKey: ['disposalPlan', detailPlanId],
		queryFn: () => fetchPlan(detailPlanId!),
		enabled: detailPlanId !== null,
		retry: false,
	});
	const eligibilityQuery = useQuery({
		queryKey: ['executionEligibility', detailPlanId],
		queryFn: () => fetchEligibility(detailPlanId!),
		enabled: detailPlanId !== null && planQuery.isSuccess,
		retry: false,
	});
	const executionsQuery = useQuery({
		queryKey: ['disposalExecutions', detailPlanId],
		queryFn: () => fetchExecutions(detailPlanId!, { page: 1, pageSize: 10 }),
		enabled: detailPlanId !== null && planQuery.isSuccess,
		retry: false,
	});
	const [detailExecution, setDetailExecution] = useState<SandboxExecution | null>(null);
	const [executionBusy, setExecutionBusy] = useState(false);
	const [planActionBusy, setPlanActionBusy] = useState(false);

	// Comparison form
	const [comparisonTitle, setComparisonTitle] = useState('补货数量方案对比');
	const [comparisonScenario, setComparisonScenario] = useState<SimulationScenario>('INVENTORY_REPLENISHMENT');
	const [comparisonSupplier, setComparisonSupplier] = useState('');
	const [comparison, setComparison] = useState<ScenarioComparison | null>(null);
	const [comparisonBusy, setComparisonBusy] = useState(false);
	const [candidateSaveBusy, setCandidateSaveBusy] = useState(false);
	const comparisonsQuery = useQuery({
		queryKey: ['disposalComparisons'],
		queryFn: () => fetchComparisons({ page: 1, pageSize: 5 }),
	});

	const recommendations = useMemo(() => recommendationsQuery.data ?? [], [recommendationsQuery.data]);

	const applyScope = () => {
		if (scope === 'ALL') {
			setAppliedScope({});
		} else if (scope === 'SKU') {
			setAppliedScope({ sku: scopeValue.trim() || undefined });
		} else {
			setAppliedScope({ supplierId: scopeValue.trim() || undefined });
		}
	};

	const prefillSimulation = (recommendation: Recommendation) => {
		if (recommendation.actionType === 'FOLLOW_UP_SUPPLIER') {
			setSimForm({
				scenario: 'SUPPLIER_DELAY',
				sku: '',
				quantity: '50',
				supplierId: recommendation.supplierId ?? '',
				delayDays: '7',
			});
		} else {
			setSimForm({
				scenario: 'INVENTORY_REPLENISHMENT',
				sku: recommendation.sku ?? '',
				quantity: '50',
				supplierId: '',
				delayDays: '7',
			});
		}
		toast.success('已带入模拟参数', { description: '请核对参数后运行模拟' });
	};

	const runSimulation = async () => {
		const request: SimulationRequest = simForm.scenario === 'INVENTORY_REPLENISHMENT'
			? { scenario: 'INVENTORY_REPLENISHMENT', sku: simForm.sku.trim(), replenishmentQuantity: Number(simForm.quantity) }
			: { scenario: 'SUPPLIER_DELAY', supplierId: simForm.supplierId.trim(), additionalDelayDays: Number(simForm.delayDays) };
		if (request.scenario === 'INVENTORY_REPLENISHMENT' && !request.sku) {
			toast.warning('请输入 SKU');
			return;
		}
		if (request.scenario === 'SUPPLIER_DELAY' && !request.supplierId) {
			toast.warning('请输入供应商 ID');
			return;
		}
		setSimRunning(true);
		setSimulation(null);
		try {
			setSimulation(await createSimulation(request));
			toast.success('模拟完成');
		} catch (error) {
			toast.error('模拟失败', { description: errorMessage(error) });
		} finally {
			setSimRunning(false);
		}
	};

	const savePlan = async () => {
		if (!planTitle.trim()) {
			toast.warning('请输入方案标题');
			return;
		}
		setPlanBusy(true);
		try {
			const plan = await createPlan({
				title: planTitle.trim(),
				taskId: linkTask ? linkedTaskId : undefined,
				simulationId: attachSimulation && simulation ? simulation.simulationId : undefined,
				generateExplanation,
			});
			toast.success('处置方案已保存', { description: '状态为待确认，可在方案列表中确认选用' });
			setDetailPlanId(plan.planId);
			plansQuery.refetch();
		} catch (error) {
			toast.error('保存方案失败', { description: errorMessage(error) });
		} finally {
			setPlanBusy(false);
		}
	};

	const updatePlan = async (status: PlanStatus, selectedRecommendationId?: string) => {
		if (!detailPlanId) return;
		setPlanActionBusy(true);
		try {
			await updatePlanStatus(detailPlanId, status, selectedRecommendationId);
			toast.success(status === 'SELECTED' ? '已确认选用方案' : status === 'REJECTED' ? '已标记不采用' : '已退回待确认');
			planQuery.refetch();
			eligibilityQuery.refetch();
			plansQuery.refetch();
		} catch (error) {
			toast.error('更新方案状态失败', { description: errorMessage(error) });
		} finally {
			setPlanActionBusy(false);
		}
	};

	const startExecution = async () => {
		if (!detailPlanId) return;
		setExecutionBusy(true);
		try {
			const key = `sandbox-${crypto.randomUUID()}`;
			const { execution, replayed } = await createExecution(detailPlanId, key);
			setDetailExecution(execution);
			toast.success(execution.status === 'SUCCEEDED' ? '沙箱执行完成（真实数据未变更）' : '沙箱执行结束，请查看结果', {
				description: replayed ? '后端识别为幂等重放' : undefined,
			});
			executionsQuery.refetch();
		} catch (error) {
			toast.error('启动沙箱执行失败', { description: errorMessage(error) });
		} finally {
			setExecutionBusy(false);
		}
	};

	const runComparison = async () => {
		if (!comparisonTitle.trim()) {
			toast.warning('请输入对比标题');
			return;
		}
		if (comparisonScenario === 'INVENTORY_REPLENISHMENT' && !comparisonSku.trim()) {
			toast.warning('请输入 SKU');
			return;
		}
		if (comparisonScenario === 'SUPPLIER_DELAY' && !comparisonSupplier.trim()) {
			toast.warning('请输入供应商 ID');
			return;
		}
		const validCandidates = candidates.filter((candidate) => candidate.value.trim() !== '');
		if (validCandidates.length < 2) {
			toast.warning('至少需要两个候选参数');
			return;
		}
		setComparisonBusy(true);
		setComparison(null);
		try {
			const result = await createComparison({
				title: comparisonTitle.trim(),
				scenario: comparisonScenario,
				sku: comparisonScenario === 'INVENTORY_REPLENISHMENT' ? comparisonSku.trim() || undefined : undefined,
				supplierId: comparisonScenario === 'SUPPLIER_DELAY' ? comparisonSupplier.trim() || undefined : undefined,
				candidates: validCandidates.map((candidate, index) => ({
					label: candidate.label.trim() || `方案 ${index + 1}`,
					...(comparisonScenario === 'INVENTORY_REPLENISHMENT'
						? { replenishmentQuantity: Number(candidate.value) }
						: { additionalDelayDays: Number(candidate.value) }),
				})),
			});
			setComparison(result);
			comparisonsQuery.refetch();
			toast.success('方案对比完成');
		} catch (error) {
			toast.error('对比失败', { description: errorMessage(error) });
		} finally {
			setComparisonBusy(false);
		}
	};

	const saveCandidateAsPlan = async (comparisonId: string, candidateId: string, label: string) => {
		if (candidateSaveBusy) return;
		setCandidateSaveBusy(true);
		try {
			const plan = await createPlanFromCandidate(comparisonId, candidateId, {
				title: `${comparisonTitle || '方案对比'} · ${label}`,
				taskId: linkedTaskId,
				generateExplanation: true,
			});
			toast.success('候选方案已保存为处置方案');
			setDetailPlanId(plan.planId);
			plansQuery.refetch();
			if (comparison) {
				const refreshed = await fetchComparison(comparisonId);
				setComparison(refreshed);
			}
		} catch (error) {
			toast.error('保存失败', { description: errorMessage(error) });
		} finally {
			setCandidateSaveBusy(false);
		}
	};

	const openExecution = async (executionId: string) => {
		try {
			setDetailExecution(await fetchExecution(executionId));
		} catch (error) {
			toast.error('加载执行详情失败', { description: errorMessage(error) });
		}
	};

	const plan = planQuery.data;

	return (
		<div className="space-y-5">
			<PageHeader
				title="处置与沙箱"
				breadcrumb="风险决策"
				description="处置方案、模拟与沙箱执行均由后端持久化；沙箱演练不会写入真实业务系统。"
				actions={
					<Button variant="outline" onClick={() => { recommendationsQuery.refetch(); plansQuery.refetch(); }}>
						<RefreshCw className="mr-2 h-4 w-4" />
						刷新
					</Button>
				}
			/>

			{demoMode && <DemoBanner step={3} />}

			<Section
				title="处置建议"
				description="来自 Java 确定性规则引擎"
				actions={
					<div className="flex flex-wrap items-center gap-2">
						<Select items={{ ALL: '全部范围', SKU: '按 SKU', SUPPLIER: '按供应商' }} value={scope} onValueChange={(value) => { setScope((value ?? 'ALL') as typeof scope); setScopeValue(''); }}>
							<SelectTrigger className="w-[130px]"><SelectValue /></SelectTrigger>
							<SelectContent>
								<SelectItem value="ALL">全部范围</SelectItem>
								<SelectItem value="SKU">按 SKU</SelectItem>
								<SelectItem value="SUPPLIER">按供应商</SelectItem>
							</SelectContent>
						</Select>
						{scope !== 'ALL' && (
							<Input className="w-[160px]" placeholder={scope === 'SKU' ? 'SKU' : '供应商 ID'} value={scopeValue} onChange={(event) => setScopeValue(event.target.value)} />
						)}
						<Button size="sm" variant="outline" onClick={applyScope}>应用范围</Button>
					</div>
				}
			>
				{recommendationsQuery.isPending ? (
					<LoadingState compact label="加载建议…" />
				) : recommendationsQuery.isError ? (
					<ErrorState message={errorMessage(recommendationsQuery.error, '加载处置建议失败')} onRetry={() => recommendationsQuery.refetch()} />
				) : recommendations.length === 0 ? (
					<p className="text-sm text-[#5a708c]">当前范围没有处置建议。</p>
				) : (
					<div className="grid gap-3 lg:grid-cols-2">
						{recommendations.map((recommendation) => (
							<div key={recommendation.recommendationId} className="rounded-lg border border-[#e2e8f0] p-3">
								<div className="flex flex-wrap items-center gap-1.5">
									<span className="font-semibold text-[#1c3048]">{recommendation.title}</span>
									<Badge variant="outline">{recommendation.priority}</Badge>
									<RiskBadge level={recommendation.riskLevel} />
									<Badge variant="secondary">{ACTION_LABELS[recommendation.actionType] ?? recommendation.actionType}</Badge>
								</div>
								<p className="mt-1 text-sm text-[#33506b]">{recommendation.action}</p>
								<div className="mt-2 grid gap-2 text-xs text-[#5a708c] sm:grid-cols-2">
									<span>SKU：{recommendation.sku ?? '—'}</span>
									<span>供应商：{recommendation.supplierId ?? '—'}</span>
									<span>优先级评分：{recommendation.priorityScore}</span>
									<span>影响订单：{recommendation.affectedOrderIds?.length ?? 0}</span>
								</div>
								{recommendation.expectedImprovement && <p className="mt-1 text-xs text-[#428766]">{recommendation.expectedImprovement}</p>}
								<details className="mt-2 text-xs text-[#5a708c]">
									<summary className="cursor-pointer">依据与前提</summary>
									<div className="mt-2 space-y-2">
										<BulletList items={recommendation.businessBasis} empty="无业务依据" />
										<BulletList items={recommendation.prerequisites} empty="无前置条件" />
									</div>
								</details>
								<div className="mt-2">
									<Button size="sm" variant="outline" onClick={() => prefillSimulation(recommendation)}>带入模拟</Button>
								</div>
							</div>
						))}
					</div>
				)}
			</Section>

			<div className="grid gap-4 xl:grid-cols-2">
				<Section title="方案模拟" description="基于当前真实数据快照；模拟结果不会覆盖正式评分，也不会修改业务数据">
					<div className="space-y-3">
						{baselineQuery.isPending ? (
							<LoadingState compact label="加载真实基线…" />
						) : baselineQuery.isError ? (
							<ErrorState message={errorMessage(baselineQuery.error, '加载模拟基线失败')} onRetry={() => baselineQuery.refetch()} />
						) : baselineQuery.data ? (
							<BaselinePanel baseline={baselineQuery.data} onPickInventory={(sku) => setSimForm({ ...simForm, scenario: 'INVENTORY_REPLENISHMENT', sku })} onPickSupplier={(supplierId) => setSimForm({ ...simForm, scenario: 'SUPPLIER_DELAY', supplierId })} />
						) : null}
						<div className="grid gap-3 sm:grid-cols-2">
							<div className="space-y-1">
								<Label>场景</Label>
								<Select items={SCENARIO_LABELS} value={simForm.scenario} onValueChange={(value) => setSimForm({ ...simForm, scenario: (value ?? 'INVENTORY_REPLENISHMENT') as SimulationScenario })}>
									<SelectTrigger><SelectValue /></SelectTrigger>
									<SelectContent>
										<SelectItem value="INVENTORY_REPLENISHMENT">库存补货</SelectItem>
										<SelectItem value="SUPPLIER_DELAY">供应商延迟</SelectItem>
									</SelectContent>
								</Select>
							</div>
							{simForm.scenario === 'INVENTORY_REPLENISHMENT' ? (
								<div className="grid grid-cols-2 gap-3">
									<div className="space-y-1">
										<Label>SKU</Label>
										<Input value={simForm.sku} onChange={(event) => setSimForm({ ...simForm, sku: event.target.value })} />
									</div>
									<div className="space-y-1">
										<Label>补货数量</Label>
										<Input type="number" value={simForm.quantity} onChange={(event) => setSimForm({ ...simForm, quantity: event.target.value })} />
									</div>
								</div>
							) : (
								<div className="grid grid-cols-2 gap-3">
									<div className="space-y-1">
										<Label>供应商 ID</Label>
										<Input value={simForm.supplierId} onChange={(event) => setSimForm({ ...simForm, supplierId: event.target.value })} />
									</div>
									<div className="space-y-1">
										<Label>延迟天数</Label>
										<Input type="number" value={simForm.delayDays} onChange={(event) => setSimForm({ ...simForm, delayDays: event.target.value })} />
									</div>
								</div>
							)}
						</div>
						<Button className="bg-[#35629d] hover:bg-[#264c79]" disabled={simRunning} onClick={runSimulation}>
							{simRunning ? <Loader2 className="mr-2 h-4 w-4 animate-spin" /> : <Play className="mr-2 h-4 w-4" />}
							运行模拟
						</Button>

						{simulation && (
							<div className="space-y-3">
								<div className="grid gap-2 sm:grid-cols-3">
									<Metric label="模拟 ID" value={<span className="font-mono text-xs">{simulation.simulationId}</span>} />
									<Metric label="场景" value={SCENARIO_LABELS[simulation.scenario]} />
									<Metric label="真实数据变更" value={simulation.realDataMutated ? '是' : '否'} />
								</div>
								<SimulationMetrics simulation={simulation} />
								<div className="grid gap-2 sm:grid-cols-2">
									<Input value={planTitle} onChange={(event) => setPlanTitle(event.target.value)} placeholder="处置方案标题" />
									<div className="flex flex-wrap items-center gap-3 text-sm">
										<label className="flex items-center gap-1.5">
											<input type="checkbox" className="h-4 w-4" checked={attachSimulation} onChange={(event) => setAttachSimulation(event.target.checked)} disabled={Boolean(simulation.simulationId && planQuery.data?.simulation?.simulationId === simulation.simulationId)} />
											附带当前模拟
										</label>
										<label className="flex items-center gap-1.5">
											<input type="checkbox" className="h-4 w-4" checked={linkTask} onChange={(event) => setLinkTask(event.target.checked)} disabled={!linkedTaskId} />
											关联当前分析报告
										</label>
										<label className="flex items-center gap-1.5">
											<input type="checkbox" className="h-4 w-4" checked={generateExplanation} onChange={(event) => setGenerateExplanation(event.target.checked)} />
											生成模型解释
										</label>
									</div>
								</div>
								<Button variant="outline" disabled={planBusy} onClick={savePlan}>
									{planBusy && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
									保存为处置方案
								</Button>
							</div>
						)}
					</div>
				</Section>

				<Section
					title="处置方案"
					description="方案状态由用户确认，执行前需通过后端资格校验"
					actions={
						<Select items={PLAN_STATUS_ITEMS} value={planStatusFilter} onValueChange={(value) => { setPlanStatusFilter(value ?? 'ALL'); setPlanPage(1); }}>
							<SelectTrigger className="w-[130px]"><SelectValue /></SelectTrigger>
							<SelectContent>
								<SelectItem value="ALL">全部状态</SelectItem>
								<SelectItem value="PENDING_CONFIRMATION">待确认</SelectItem>
								<SelectItem value="SELECTED">已选用</SelectItem>
								<SelectItem value="REJECTED">不采用</SelectItem>
							</SelectContent>
						</Select>
					}
				>
					{plansQuery.isPending ? (
						<LoadingState compact label="加载方案…" />
					) : plansQuery.isError ? (
						<ErrorState message={errorMessage(plansQuery.error, '加载方案失败')} onRetry={() => plansQuery.refetch()} />
					) : (plansQuery.data?.items ?? []).length === 0 ? (
						<p className="text-sm text-[#5a708c]">暂无处置方案。</p>
					) : (
						<div className="space-y-2">
							{(plansQuery.data?.items ?? []).map((summary) => (
								<button
									key={summary.planId}
									className={`w-full rounded-lg border px-3 py-2 text-left ${detailPlanId === summary.planId ? 'border-[#35629d] bg-[#f0f7fd]' : 'border-[#e2e8f0] hover:bg-[#f8fbfe]'}`}
									onClick={() => { setDetailPlanId(summary.planId); setDetailExecution(null); }}
								>
									<div className="flex flex-wrap items-center justify-between gap-2">
										<span className="text-sm font-medium text-[#1c3048]">{summary.title}</span>
										<StatusBadge status={summary.status} />
									</div>
									<div className="mt-1 text-xs text-[#5a708c]">
										建议 {summary.recommendationCount} 条 · 风险订单变化 {signed(summary.atRiskOrdersDelta)} · {formatTime(summary.updatedAt)}
									</div>
								</button>
							))}
							<div className="flex items-center justify-end gap-2 pt-1">
								<Button size="sm" variant="outline" disabled={planPage <= 1} onClick={() => setPlanPage((page) => page - 1)}>上一页</Button>
								<span className="text-xs text-[#5a708c]">
									{plansQuery.data?.page} / {Math.max(1, plansQuery.data?.totalPages ?? 1)}（共 {plansQuery.data?.total ?? 0}）
								</span>
								<Button size="sm" variant="outline" disabled={planPage >= (plansQuery.data?.totalPages ?? 1)} onClick={() => setPlanPage((page) => page + 1)}>下一页</Button>
							</div>
						</div>
					)}
				</Section>
			</div>

			{detailPlanId && (
				<Section
					title="方案详情与执行"
					actions={<Button size="sm" variant="ghost" onClick={() => { setDetailPlanId(null); setDetailExecution(null); }}><X className="mr-1 h-4 w-4" />关闭</Button>}
				>
					{planQuery.isPending ? (
						<LoadingState compact label="加载方案详情…" />
					) : planQuery.isError ? (
						<ErrorState
							message={friendlyErrorMessage(planQuery.error, '加载方案详情失败', {
								404: '方案不存在或已被清理，请从处置方案列表重新选择。',
							})}
							onRetry={() => planQuery.refetch()}
						/>
					) : plan ? (
						<PlanDetail
							key={plan.planId}
							plan={plan}
							eligibility={eligibilityQuery.data}
							eligibilityError={eligibilityQuery.isError ? errorMessage(eligibilityQuery.error, '读取执行资格失败') : null}
							executions={executionsQuery.data?.items ?? []}
							execution={detailExecution}
							busy={planActionBusy}
							executionBusy={executionBusy}
							onSelect={(recommendationId) => updatePlan('SELECTED', recommendationId)}
							onReject={() => updatePlan('REJECTED')}
							onReopen={() => updatePlan('PENDING_CONFIRMATION')}
							onExecute={startExecution}
							onOpenExecution={openExecution}
							onRefreshEligibility={() => eligibilityQuery.refetch()}
						/>
					) : null}
				</Section>
			)}

			<Section
				title="方案对比"
				description="2–5 个候选参数由后端确定性排名"
				actions={<Button size="sm" variant="ghost" onClick={() => comparisonsQuery.refetch()}><RefreshCw className="mr-1 h-4 w-4" />刷新历史</Button>}
			>
				<div className="grid gap-4 lg:grid-cols-[380px_1fr]">
					<div className="min-w-0 space-y-3">
						<Input value={comparisonTitle} onChange={(event) => setComparisonTitle(event.target.value)} placeholder="对比标题" />
						<Select items={SCENARIO_LABELS} value={comparisonScenario} onValueChange={(value) => setComparisonScenario((value ?? 'INVENTORY_REPLENISHMENT') as SimulationScenario)}>
							<SelectTrigger><SelectValue /></SelectTrigger>
							<SelectContent>
								<SelectItem value="INVENTORY_REPLENISHMENT">库存补货</SelectItem>
								<SelectItem value="SUPPLIER_DELAY">供应商延迟</SelectItem>
							</SelectContent>
						</Select>
						{comparisonScenario === 'INVENTORY_REPLENISHMENT' ? (
							<Input value={comparisonSku} onChange={(event) => setComparisonSku(event.target.value)} placeholder="SKU（必填）" />
						) : (
							<Input value={comparisonSupplier} onChange={(event) => setComparisonSupplier(event.target.value)} placeholder="供应商 ID（必填）" />
						)}
						<div className="space-y-2">
							{candidates.map((candidate, index) => (
								<div key={index} className="flex min-w-0 gap-2">
									<Input className="min-w-0 flex-1 sm:w-[120px] sm:flex-none" value={candidate.label} placeholder={`方案 ${index + 1}`} onChange={(event) => setCandidates((prev) => prev.map((item, i) => (i === index ? { ...item, label: event.target.value } : item)))} />
									<Input className="min-w-0 flex-1" type="number" value={candidate.value} placeholder={comparisonScenario === 'INVENTORY_REPLENISHMENT' ? '补货数量' : '延迟天数'} onChange={(event) => setCandidates((prev) => prev.map((item, i) => (i === index ? { ...item, value: event.target.value } : item)))} />
									<Button size="icon" variant="ghost" disabled={candidates.length <= 2} onClick={() => setCandidates((prev) => prev.filter((_, i) => i !== index))}>
										<Trash2 className="h-4 w-4 text-[#b9575c]" />
									</Button>
								</div>
							))}
							<Button size="sm" variant="outline" disabled={candidates.length >= 5} onClick={() => setCandidates((prev) => [...prev, { label: `方案 ${prev.length + 1}`, value: '' }])}>
								<Plus className="mr-1 h-4 w-4" />添加候选
							</Button>
						</div>
						<Button className="bg-[#35629d] hover:bg-[#264c79]" disabled={comparisonBusy} onClick={runComparison}>
							{comparisonBusy && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
							运行对比
						</Button>
						<div className="space-y-1">
							<p className="text-xs font-medium text-[#5a708c]">最近对比</p>
							{(comparisonsQuery.data?.items ?? []).map((item) => (
								<button key={item.comparisonId} className="block w-full truncate rounded px-2 py-1 text-left text-xs text-[#33506b] hover:bg-[#f2f7fc]" onClick={() => fetchComparison(item.comparisonId).then(setComparison).catch((error) => toast.error('加载对比失败', { description: errorMessage(error) }))}>
									{item.title} · {SCENARIO_LABELS[item.scenario]} · {item.candidateCount} 个候选
								</button>
							))}
						</div>
					</div>
					<div className="min-w-0">
						{comparison ? (
							<ComparisonResult comparison={comparison} onSavePlan={saveCandidateAsPlan} savingPlan={candidateSaveBusy} />
						) : (
							<p className="text-sm text-[#5a708c]">运行或选择一次对比后显示排名结果。</p>
						)}
					</div>
				</div>
			</Section>
		</div>
	);
}


function BaselinePanel({
	baseline,
	onPickInventory,
	onPickSupplier,
}: {
	baseline: SimulationBaselineView;
	onPickInventory: (sku: string) => void;
	onPickSupplier: (supplierId: string) => void;
}) {
	return (
		<div className="space-y-3 rounded-lg border border-[#dbe6f2] bg-[#f8fbfe]/80 p-3">
			<div className="flex flex-wrap items-center justify-between gap-2">
				<div>
					<div className="text-sm font-semibold text-[#1c3048]">当前真实基线</div>
					<p className="text-xs text-[#5a708c]">
						{baseline.baselineSource} · asOf {baseline.asOfDate ?? '—'} · 捕获 {formatTime(baseline.capturedAt)}
					</p>
				</div>
				<div className="flex flex-wrap gap-1.5">
					<Badge variant="outline">{baseline.simulationStatus}</Badge>
					<Badge variant="outline">规则 {baseline.ruleVersion}</Badge>
					<Badge variant="secondary">只读快照</Badge>
				</div>
			</div>
			<div className="grid gap-2 sm:grid-cols-4">
				<Metric label="订单" value={baseline.metrics.totalOrders} />
				<Metric label="风险订单" value={baseline.metrics.atRiskOrders} />
				<Metric label="高风险" value={baseline.metrics.highRiskOrders} />
				<Metric label="缺口总量" value={baseline.metrics.totalShortageUnits} />
			</div>
			{baseline.dataGaps.length > 0 && (
				<div className="rounded-lg border border-[#ecd9b5] bg-[#fdf8ee] px-3 py-2">
					<div className="text-xs font-medium text-[#7a5a22]">基线数据完整性提示</div>
					<BulletList items={baseline.dataGaps} />
				</div>
			)}
			<details className="rounded-lg border border-[#e6eef7] bg-white/55 px-3 py-2">
				<summary className="cursor-pointer text-xs font-medium text-[#33506b]">
					基线风险与证据（{baseline.assessments.length} 个订单）
				</summary>
				<div className="mt-2 space-y-2">
					{baseline.assessments.map((item) => (
						<div key={item.order.orderId} className="rounded-lg border border-[#e2e8f0] bg-white/60 p-2">
							<div className="flex flex-wrap items-center justify-between gap-2">
								<span className="font-mono text-xs font-semibold text-[#1c3048]">{item.order.orderId}</span>
								<div className="flex items-center gap-2">
									<RiskBadge level={item.riskLevel} />
									<span className="text-xs font-semibold text-[#1c3048]">{item.riskScore}/100</span>
								</div>
							</div>
							<RiskEvidencePanel item={item} />
						</div>
					))}
				</div>
			</details>
			<details className="rounded-lg border border-[#e6eef7] bg-white/55 px-3 py-2">
				<summary className="cursor-pointer text-xs font-medium text-[#33506b]">可模拟对象（来自真实快照）</summary>
				<div className="mt-2 space-y-2 text-xs text-[#5a708c]">
					<div>
						<div className="mb-1">库存 SKU（点击带入补货场景）</div>
						<div className="flex flex-wrap gap-1.5">
							{baseline.inventory.map((item) => (
								<button key={item.sku} type="button" className="rounded-full border border-[#dfe8f1] bg-white/70 px-2.5 py-1 text-[#33506b] hover:bg-[#f2f7fc]" onClick={() => onPickInventory(item.sku)}>
									{item.sku} · ATP {item.availableToPromise}
								</button>
							))}
						</div>
					</div>
					<div>
						<div className="mb-1">供应商（点击带入延迟场景）</div>
						<div className="flex flex-wrap gap-1.5">
							{baseline.suppliers.map((supplier) => (
								<button key={supplier.supplierId} type="button" className="rounded-full border border-[#dfe8f1] bg-white/70 px-2.5 py-1 text-[#33506b] hover:bg-[#f2f7fc]" onClick={() => onPickSupplier(supplier.supplierId)}>
									{supplier.supplierId} · {supplier.riskLevel}
								</button>
							))}
						</div>
					</div>
				</div>
			</details>
			{baseline.disclaimer && <p className="text-xs text-[#5a708c]">{baseline.disclaimer}</p>}
		</div>
	);
}

function PlanDetail({
	plan,
	eligibility,
	eligibilityError,
	executions,
	execution,
	busy,
	executionBusy,
	onSelect,
	onReject,
	onReopen,
	onExecute,
	onOpenExecution,
	onRefreshEligibility,
}: {
	plan: DisposalPlan;
	eligibility?: ExecutionEligibility;
	eligibilityError: string | null;
	executions: SandboxExecutionSummary[];
	execution: SandboxExecution | null;
	busy: boolean;
	executionBusy: boolean;
	onSelect: (recommendationId: string) => void;
	onReject: () => void;
	onReopen: () => void;
	onExecute: () => void;
	onOpenExecution: (id: string) => void;
	onRefreshEligibility: () => void;
}) {
	const [selectedRecommendation, setSelectedRecommendation] = useState(plan.selectedRecommendationId ?? '');
	const canExecute = plan.status === 'SELECTED' && eligibility?.eligible === true;

	return (
		<div className="space-y-4">
			<div className="grid gap-3 sm:grid-cols-4">
				<Field label="方案 ID" value={<span className="font-mono text-xs">{plan.planId}</span>} />
				<Field label="状态" value={<StatusBadge status={plan.status} />} />
				<Field label="范围" value={`${plan.scopeSku ? `SKU ${plan.scopeSku}` : ''}${plan.scopeSupplierId ? ` 供应商 ${plan.scopeSupplierId}` : ''}` || '—'} />
				<Field label="关联任务" value={plan.taskId ? <span className="font-mono text-xs">{plan.taskId}</span> : '未关联'} />
				<Field label="创建时间" value={formatTime(plan.createdAt)} />
				<Field label="状态更新时间" value={formatTime(plan.updatedAt)} />
			</div>
			{plan.taskId && (
				<Link href={`/supply-chain/decision?taskId=${encodeURIComponent(plan.taskId)}`} className="text-xs font-medium text-[#35629d] hover:underline">
					查看关联决策任务与事件时间线
				</Link>
			)}

			<div className="space-y-2">
				<p className="text-sm font-medium text-[#33506b]">选择处置建议</p>
				{plan.recommendations.map((recommendation) => (
					<div key={recommendation.recommendationId} className="rounded-lg border border-[#e2e8f0] px-3 py-2">
						<label className="flex cursor-pointer items-start gap-2">
							<input
								type="radio"
								name="plan-recommendation"
								className="mt-1"
								checked={selectedRecommendation === recommendation.recommendationId}
								onChange={() => setSelectedRecommendation(recommendation.recommendationId)}
							/>
							<span className="min-w-0">
								<span className="flex flex-wrap items-center gap-1.5">
									<span className="text-sm font-medium text-[#1c3048]">{recommendation.title}</span>
									<Badge variant="outline">{recommendation.priority}</Badge>
									<RiskBadge level={recommendation.riskLevel} />
								</span>
								<span className="mt-0.5 block text-xs text-[#5a708c]">{recommendation.action}</span>
							</span>
						</label>
						<RecommendationDetails recommendation={recommendation} />
					</div>
				))}
			</div>

			<div className="flex flex-wrap gap-2">
				<Button size="sm" disabled={busy || !selectedRecommendation || plan.status === 'SELECTED'} onClick={() => onSelect(selectedRecommendation)}>
					确认选用
				</Button>
				<Button size="sm" variant="outline" disabled={busy || plan.status === 'PENDING_CONFIRMATION'} onClick={onReopen}>退回待确认</Button>
				<Button size="sm" variant="outline" disabled={busy || plan.status === 'REJECTED'} onClick={onReject}>标记不采用</Button>
			</div>

			{plan.simulation && <SimulationMetrics simulation={plan.simulation} />}
			{plan.explanation && (
				<div className="rounded-lg border border-[#e2e8f0] bg-[#f8fbfe] p-3 text-sm text-[#33506b]">
					<div className="mb-1 flex items-center gap-2 text-xs text-[#5a708c]">
						<span>方案解释</span>
						{plan.explanationSource && <Badge variant="outline">{plan.explanationSource}</Badge>}
						<Badge variant="outline">{plan.explanationModelAssisted ? '模型解释' : '规则解释'}</Badge>
					</div>
					<p className="whitespace-pre-wrap">{plan.explanation}</p>
				</div>
			)}

			{(plan.evidence?.length ?? 0) > 0 && (
				<details className="rounded-lg border border-[#e2e8f0] bg-white/45 px-3 py-2">
					<summary className="cursor-pointer text-xs font-medium text-[#33506b]">方案知识依据（{plan.evidence?.length}）</summary>
					<ul className="mt-2 space-y-1 text-xs text-[#5a708c]">
						{(plan.evidence ?? []).map((citation) => (
							<li key={citation.sourceId}>
								<span className="font-medium text-[#33506b]">{citation.title || citation.sourceId}</span>：{citation.excerpt}
							</li>
						))}
					</ul>
				</details>
			)}

			<div className="rounded-lg border border-[#e2e8f0] p-3">
				<div className="flex flex-wrap items-center justify-between gap-2">
					<p className="text-sm font-medium text-[#33506b]">沙箱执行资格</p>
					<Button size="sm" variant="ghost" onClick={onRefreshEligibility}><RefreshCw className="mr-1 h-3 w-3" />重新校验</Button>
				</div>
				{eligibilityError ? (
					<p className="mt-1 text-xs text-[#8f3b45]">{eligibilityError}</p>
				) : eligibility ? (
					<div className="mt-2 space-y-2 text-xs text-[#5a708c]">
						<div className="flex flex-wrap items-center gap-2">
							<Badge variant="outline" className={eligibility.eligible ? 'border-green-200 bg-green-50 text-green-700' : 'border-amber-200 bg-amber-50 text-amber-700'}>
								{eligibility.eligible ? '可执行' : '不可执行'}
							</Badge>
							<span>方案状态 {eligibility.planStatus}</span>
							<span>动作 {eligibility.actionType ?? '—'}</span>
						</div>
						<BulletList items={eligibility.checks} />
						{eligibility.reason && <p>{eligibility.reason}</p>}
					</div>
				) : (
					<LoadingState compact label="校验中…" />
				)}
				<div className="mt-3">
					<Button size="sm" className="bg-[#35629d] hover:bg-[#264c79]" disabled={!canExecute || executionBusy} onClick={onExecute}>
						{executionBusy ? <Loader2 className="mr-2 h-4 w-4 animate-spin" /> : <Play className="mr-2 h-4 w-4" />}
						开始沙箱执行
					</Button>
					{!canExecute && <span className="ml-2 text-xs text-[#5a708c]">需方案已选用且后端校验通过</span>}
				</div>
			</div>

			{execution && (
				<div className="rounded-lg border border-[#e2e8f0] p-3">
					<div className="flex flex-wrap items-center gap-2">
						<p className="text-sm font-medium text-[#33506b]">本次执行结果</p>
						<StatusBadge status={execution.status} />
						<Badge variant="outline">{execution.mode ?? 'SANDBOX'}</Badge>
						<Badge variant="outline">{execution.realDataMutated ? '真实数据已变更' : '真实数据未变更'}</Badge>
					</div>
					<div className="mt-2 grid gap-2 sm:grid-cols-2">
						<Field label="执行 ID" value={<span className="font-mono text-xs">{execution.executionId}</span>} />
						<Field label="幂等键" value={<span className="font-mono text-xs">{execution.idempotencyKey}</span>} />
						<Field label="开始" value={formatTime(execution.startedAt)} />
						<Field label="完成" value={formatTime(execution.completedAt)} />
					</div>
					<ExecutionSteps execution={execution} />
					{execution.taskDraft && (
						<div className="mt-3 rounded bg-[#f8fbfe] p-2 text-xs text-[#33506b]">
							<p className="font-medium">任务草稿：{execution.taskDraft.title ?? execution.taskDraft.taskDraftId}</p>
							<BulletList items={execution.taskDraft.recommendedActions} empty="无推荐动作" />
							<p className="mt-1 text-[#5a708c]">待人工确认：{(execution.taskDraft.pendingConfirmations ?? []).join('；') || '无'}</p>
						</div>
					)}
					{execution.disclaimer && <p className="mt-2 text-xs text-[#5a708c]">{execution.disclaimer}</p>}
				</div>
			)}

			<details className="rounded-xl bg-white/45 px-3 py-2 ring-1 ring-white/60">
				<summary className="cursor-pointer text-[12.5px] font-medium text-[#5f7389]">执行历史（{executions.length}）</summary>
				{executions.length === 0 ? (
					<p className="mt-1 text-xs text-[#5a708c]">暂无执行记录。</p>
				) : (
					<div className="mt-2 space-y-1">
						{executions.map((item) => (
							<button key={item.executionId} className="flex w-full flex-wrap items-center justify-between gap-2 rounded px-2 py-1 text-left text-xs hover:bg-[#f2f7fc]" onClick={() => onOpenExecution(item.executionId)}>
								<span className="font-mono">{item.executionId}</span>
								<StatusBadge status={item.status} />
								<span className="text-[#5a708c]">步骤 {item.stepCount} · 失败 {item.failedStepCount}</span>
								<span className="text-[#5a708c]">{formatTime(item.createdAt)}</span>
							</button>
						))}
					</div>
				)}
			</details>
		</div>
	);
}

function ExecutionSteps({ execution }: { execution: SandboxExecution }) {
	return (
		<div className="mt-3 space-y-1">
			{execution.steps.map((step) => (
				<div key={`${step.sequence}-${step.step}`} className="rounded border border-[#eef3f8] px-2 py-1.5 text-xs">
					<div className="flex flex-wrap items-center gap-2">
						<span className="font-mono text-[#5a708c]">#{step.sequence}</span>
						<span className="font-medium text-[#1c3048]">{step.name ?? step.step}</span>
						<StatusBadge status={step.status} />
					</div>
					{step.result && <p className="mt-1 text-[#5a708c]">{step.result}</p>}
					{step.failureReason && <p className="mt-1 text-[#8f3b45]">{step.failureReason}</p>}
				</div>
			))}
			{execution.finalResult && <p className="mt-1 text-xs text-[#33506b]">最终结果：{execution.finalResult}</p>}
			{(execution.unresolvedItems ?? []).length > 0 && <BulletList items={execution.unresolvedItems} />}
		</div>
	);
}

function ComparisonResult({
	comparison,
	onSavePlan,
	savingPlan,
}: {
	comparison: ScenarioComparison;
	onSavePlan: (comparisonId: string, candidateId: string, label: string) => void;
	savingPlan: boolean;
}) {
	const sampleSimulation = comparison.candidates.find((candidate) => candidate.simulation)?.simulation;
	const ruleVersion = sampleSimulation?.ruleVersion ?? 'RISK_RULES_V1';
	const simulationStatus = sampleSimulation?.simulationStatus ?? 'SIMULATED';
	return (
		<div className="space-y-3">
			<div className="flex flex-wrap items-center justify-between gap-2">
				<div className="text-sm font-medium text-[#1c3048]">共享基线比较</div>
				<div className="flex flex-wrap gap-1.5">
					<Badge variant="outline">{simulationStatus}</Badge>
					<Badge variant="outline">规则 {ruleVersion}</Badge>
					<Badge variant="secondary">真实数据未变更</Badge>
				</div>
			</div>
			<div className="grid gap-2 sm:grid-cols-4">
				<Metric label="基线风险订单" value={comparison.baseline?.atRiskOrders ?? '—'} />
				<Metric label="基线缺口总量" value={comparison.baseline?.totalShortageUnits ?? '—'} />
				<Metric label="最佳风险订单变化" value={signed(comparison.bestAtRiskOrdersDelta)} />
				<Metric label="最佳缺口变化" value={signed(comparison.bestTotalShortageUnitsDelta)} />
			</div>
			{comparison.recommendation?.summary && (
				<div className="rounded-lg border border-[#dbe6f2] bg-[#f8fbfe] p-3 text-sm text-[#33506b]">
					<div className="mb-1 flex flex-wrap items-center gap-1.5">
						<Badge variant="outline">{comparison.recommendation.outcome ?? '—'}</Badge>
						{comparison.recommendation.recommendedLabel && <span className="font-medium text-[#1c3048]">推荐 {comparison.recommendation.recommendedLabel}</span>}
					</div>
					<p className="whitespace-pre-wrap">{comparison.recommendation.summary}</p>
					<BulletList items={comparison.recommendation.manualConfirmation} empty="" />
				</div>
			)}
			<div className="overflow-x-auto">
				<table className="w-full text-sm">
					<thead>
						<tr className="border-b border-[#e2e8f0] text-left text-xs text-[#5a708c]">
							<th className="py-1.5 pr-3">候选</th>
							<th className="py-1.5 pr-3">排名</th>
							<th className="py-1.5 pr-3">参数</th>
							<th className="py-1.5 pr-3">关键变化</th>
							<th className="py-1.5 pr-3">方案</th>
							<th className="py-1.5">操作</th>
						</tr>
					</thead>
					<tbody>
						{comparison.rankings.map((ranking) => {
							const candidate = comparison.candidates.find((item) => item.candidateId === ranking.candidateId);
							return (
								<tr key={ranking.candidateId} className="border-b border-[#f1f5f9]">
									<td className="py-1.5 pr-3">{ranking.label ?? ranking.candidateId}{ranking.recommended ? '（推荐）' : ''}</td>
									<td className="py-1.5 pr-3">{ranking.rank ?? '不可比'}</td>
									<td className="py-1.5 pr-3 text-xs text-[#5a708c]">{ranking.parameterSummary ?? '—'}</td>
									<td className="py-1.5 pr-3 text-xs">{(ranking.keyDeltas ?? []).join('；') || '—'}</td>
									<td className="py-1.5 pr-3 text-xs">{candidate?.linkedPlanId ?? '—'}</td>
									<td className="py-1.5">
										<Button
											size="sm"
											variant="outline"
											disabled={savingPlan || Boolean(candidate?.linkedPlanId)}
											onClick={() => onSavePlan(comparison.comparisonId, ranking.candidateId, ranking.label ?? ranking.candidateId)}
										>
											{savingPlan ? '保存中…' : '保存为方案'}
										</Button>
									</td>
								</tr>
							);
						})}
					</tbody>
				</table>
			</div>
			<div className="space-y-2">
				<div className="text-xs font-medium text-[#33506b]">候选证据变化与剩余数据缺口</div>
				{comparison.candidates.map((candidate) => {
					const simulation = candidate.simulation;
					if (!simulation) return null;
					const changes = (simulation.affectedOrders ?? []).flatMap((impact) =>
						(impact.evidenceChanges ?? []).map((change) => `${impact.orderId}：${change}`),
					);
					const gaps = simulation.dataGaps ?? [];
					return (
						<details key={candidate.candidateId} className="rounded-lg border border-[#e6eef7] bg-white/55 px-3 py-2">
							<summary className="cursor-pointer text-xs font-medium text-[#33506b]">
								{candidate.label ?? candidate.candidateId} · 证据变化 {changes.length} · 剩余缺口 {gaps.length}
							</summary>
							<div className="mt-2 space-y-2">
								<BulletList items={changes} empty="本次模拟没有证据变化。" />
								{gaps.length > 0 && (
									<div className="rounded-lg border border-[#ecd9b5] bg-[#fdf8ee] px-2.5 py-2">
										<div className="text-xs font-medium text-[#7a5a22]">模拟后仍然存在的数据缺口</div>
										<BulletList items={gaps} />
									</div>
								)}
								<p className="text-xs text-[#5a708c]">
									模拟 {simulation.simulationId} · 状态 {simulation.simulationStatus ?? 'SIMULATED'} · 真实数据未变更
								</p>
							</div>
						</details>
					);
				})}
			</div>
			<p className="text-xs text-[#5a708c]">保存候选只创建待确认处置方案，不代表已执行；进入沙箱演练后也必须以执行记录为准，真实业务数据不会被模拟修改。</p>
			{comparison.disclaimer && <p className="text-xs text-[#5a708c]">{comparison.disclaimer}</p>}
		</div>
	);
}
