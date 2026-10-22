'use client';

import { useState } from 'react';
import Link from 'next/link';
import { toast } from 'sonner';
import { ArrowRight, FlaskConical, Home, Loader2, Play, RefreshCw, RotateCcw } from 'lucide-react';
import { PageHeader } from '@/components/page-header';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Input } from '@/components/ui/input';
import { BulletList, Field, Metric, Section, signed } from '@/components/supply-chain/common';
import { friendlyErrorMessage } from '@/lib/api/client';
import {
	createComparison,
	createSimulation,
	fetchSimulationBaseline,
	type ScenarioComparison,
	type Simulation,
} from '@/lib/api/supplyChain';
import { useQuery } from '@tanstack/react-query';

const DRILL_SKU = 'SKU-MCU-001';
const PRESET_QUANTITIES = [50, 100, 200];
const QUANTITY_MAX = 1_000_000;

type DrillRun = {
	quantity: number;
	deltaShortage: number | undefined;
	deltaAtRisk: number | undefined;
	simulatedAtp: number | null | undefined;
	baselineAtp: number | null | undefined;
	at: string;
};

function parseQuantity(raw: string): number | null {
	const trimmed = raw.trim();
	if (!/^\d+$/.test(trimmed)) return null;
	const value = Number(trimmed);
	if (!Number.isInteger(value) || value < 1 || value > QUANTITY_MAX) return null;
	return value;
}

function MetricRow({ label, baseline, simulated, delta }: { label: string; baseline: number | undefined | null; simulated: number | undefined | null; delta: number | undefined | null }) {
	return (
		<tr className="border-b border-[#f1f5f9]">
			<td className="py-1.5 pr-3">{label}</td>
			<td className="py-1.5 pr-3">{baseline ?? '—'}</td>
			<td className="py-1.5 pr-3">{simulated ?? '—'}</td>
			<td className={`py-1.5 ${(delta ?? 0) < 0 ? 'text-[#428766]' : (delta ?? 0) > 0 ? 'text-[#b9575c]' : ''}`}>{signed(delta ?? undefined)}</td>
		</tr>
	);
}

export default function SimulationDrillPage() {
	const baselineQuery = useQuery({
		queryKey: ['simulationDrillBaseline'],
		queryFn: fetchSimulationBaseline,
		staleTime: 30_000,
	});

	const [quantityText, setQuantityText] = useState('100');
	const [running, setRunning] = useState(false);
	const [result, setResult] = useState<Simulation | null>(null);
	const [runs, setRuns] = useState<DrillRun[]>([]);
	const [comparing, setComparing] = useState(false);
	const [comparison, setComparison] = useState<ScenarioComparison | null>(null);

	const baseline = baselineQuery.data?.metrics;
	const baselineAtp = result?.baseline?.targetSkuAvailableToPromise ?? null;

	const runSimulation = async () => {
		const quantity = parseQuantity(quantityText);
		if (quantity === null) {
			toast.error('补货数量无效', { description: `请输入 1 到 ${QUANTITY_MAX.toLocaleString()} 之间的整数。` });
			return;
		}
		setRunning(true);
		try {
			const simulation = await createSimulation({ scenario: 'INVENTORY_REPLENISHMENT', sku: DRILL_SKU, replenishmentQuantity: quantity });
			setResult(simulation);
			setRuns((prev) => [
				{
					quantity,
					deltaShortage: simulation.delta?.totalShortageUnits,
					deltaAtRisk: simulation.delta?.atRiskOrders,
					simulatedAtp: simulation.simulated?.targetSkuAvailableToPromise,
					baselineAtp: simulation.baseline?.targetSkuAvailableToPromise,
					at: simulation.createdAt ?? new Date().toISOString(),
				},
				...prev.slice(0, 4),
			]);
			toast.success('模拟完成', { description: `补货 +${quantity} 件 · 缺口变化 ${signed(simulation.delta?.totalShortageUnits)}` });
		} catch (error) {
			toast.error('模拟失败', { description: friendlyErrorMessage(error, '后端模拟接口不可用，请确认服务已启动。') });
		} finally {
			setRunning(false);
		}
	};

	const runComparison = async () => {
		setComparing(true);
		try {
			const next = await createComparison({
				title: `情景演练对比：${DRILL_SKU}`,
				scenario: 'INVENTORY_REPLENISHMENT',
				sku: DRILL_SKU,
				candidates: PRESET_QUANTITIES.map((value) => ({ label: `补货 +${value} 件`, replenishmentQuantity: value })),
			});
			setComparison(next);
			toast.success('候选对比完成', { description: next.recommendation?.recommendedLabel ? `推荐：${next.recommendation.recommendedLabel}` : undefined });
		} catch (error) {
			toast.error('候选对比失败', { description: friendlyErrorMessage(error, '后端对比接口不可用，请确认服务已启动。') });
		} finally {
			setComparing(false);
		}
	};

	const resetDrill = () => {
		setResult(null);
		setRuns([]);
		setComparison(null);
		toast.info('已清除本页演练展示', { description: '后端已有的演练记录不受影响，真实业务数据始终未被修改。' });
	};

	const evidenceChanges = (result?.affectedOrders ?? []).flatMap((impact) =>
		(impact.evidenceChanges ?? []).map((change) => `${impact.orderId}：${change}`),
	);

	return (
		<div className="space-y-5">
			<PageHeader
				title="供应链情景模拟"
				breadcrumb="风险决策"
				description="在隔离的演练环境中调整供应链条件，运行模拟并查看不同决策的业务影响。"
				actions={
					<div className="flex flex-wrap gap-2">
						<Link href="/"><Button variant="outline" size="sm"><Home className="mr-2 h-4 w-4" />返回首页</Button></Link>
						<Link href="/supply-chain/demo"><Button variant="outline" size="sm">返回演示<ArrowRight className="ml-2 h-4 w-4" /></Button></Link>
					</div>
				}
			/>

			<div className="rounded-xl border border-[#bcd6ee] bg-[#f0f7fd] px-4 py-3">
				<div className="flex flex-wrap items-center gap-1.5 text-[13px] font-medium text-[#2b5583]">
					<FlaskConical className="h-4 w-4" />
					<span>演练模式 · 使用模拟数据 · 不修改真实业务数据</span>
					<Badge variant="outline">确定性规则计算</Badge>
				</div>
				<p className="mt-1.5 text-xs leading-relaxed text-[#4a769f]">
					运行模拟会调用后端真实演练接口并产生演练记录（只读快照计算），但不会修改真实库存、订单或供应商数据；
					不会自动创建提案、确认方案或执行沙箱。当前开通的模拟能力是库存补货演练与补货方案对比。
				</p>
			</div>

			<Section title="第一步 · 选择演练场景" description="库存补货演练（当前唯一开通场景）">
				<div className="grid gap-3 sm:grid-cols-4">
					<Field label="演练场景" value="库存补货演练" />
					<Field label="目标 SKU" value={<span className="font-mono text-xs">{DRILL_SKU}</span>} />
					<Field label="基线快照日期" value={baselineQuery.data?.asOfDate ?? '—'} />
					<Field label="基线来源" value={baselineQuery.data?.baselineSource ?? '—'} />
					<Metric label="订单总数" value={baseline?.totalOrders ?? '—'} />
					<Metric label="风险订单" value={baseline?.atRiskOrders ?? '—'} />
					<Metric label="库存缺口总量" value={baseline ? `${baseline.totalShortageUnits} 件` : '—'} />
					<Metric label="目标 SKU 当前 ATP" value={baselineAtp ?? baseline?.targetSkuAvailableToPromise ?? '—'} />
				</div>
				<p className="mt-2 text-xs text-[#7a8da1]">
					基线指标由后端 {baselineQuery.data?.ruleVersion ?? '确定性规则'} 基于当前业务数据快照计算；ATP 在首次模拟后由后端基线回显。
				</p>
			</Section>

			<Section title="第二步 · 调整补货参数并运行模拟" description="参数仅用于演练计算，不写入任何真实业务数据">
				<div className="space-y-3">
					<div className="flex flex-wrap items-center gap-2">
						{PRESET_QUANTITIES.map((value) => (
							<Button key={value} size="sm" variant="outline" onClick={() => setQuantityText(String(value))}>
								+{value} 件
							</Button>
						))}
						<span className="text-xs text-[#7a8da1]">或输入自定义数量（后端支持 1 – 1,000,000 件）</span>
					</div>
					<div className="flex flex-wrap items-center gap-2">
						<div className="flex items-center gap-2">
							<span className="text-sm text-[#33506b]">补货数量</span>
							<span className="text-sm text-[#7a8da1]">+</span>
							<Input
								className="w-32"
								inputMode="numeric"
								value={quantityText}
								onChange={(event) => setQuantityText(event.target.value)}
								placeholder="例如 150"
							/>
							<span className="text-sm text-[#33506b]">件</span>
						</div>
						<Button className="bg-[#35629d] hover:bg-[#264c79]" disabled={running} onClick={() => void runSimulation()}>
							{running ? <Loader2 className="mr-2 h-4 w-4 animate-spin" /> : <Play className="mr-2 h-4 w-4" />}
							运行模拟
						</Button>
					</div>
					{result && (
						<div className="flex flex-wrap items-center gap-1.5 text-xs">
							<Badge variant="outline">{result.simulationStatus ?? 'SIMULATED'}</Badge>
							<Badge variant="secondary">{result.realDataMutated ? '真实数据已变更' : '真实数据未变更'}</Badge>
							{result.ruleVersion && <Badge variant="outline">规则 {result.ruleVersion}</Badge>}
							<span className="text-[#7a8da1]">演练 ID {result.simulationId.slice(0, 8)}…</span>
						</div>
					)}
				</div>
			</Section>

			{result && (
				<Section
					title="第三步 · 模拟结果"
					description={`本次参数：补货 +${result.replenishmentQuantity ?? '—'} 件 · ${result.sku ?? DRILL_SKU} · 结果由后端确定性计算`}
				>
					<div className="overflow-x-auto">
						<table className="w-full text-sm">
							<thead>
								<tr className="border-b border-[#e2e8f0] text-left text-xs text-[#5a708c]">
									<th className="py-1.5 pr-3">指标</th>
									<th className="py-1.5 pr-3">基线</th>
									<th className="py-1.5 pr-3">模拟后</th>
									<th className="py-1.5">变化</th>
								</tr>
							</thead>
							<tbody>
								<MetricRow label="风险订单" baseline={result.baseline?.atRiskOrders} simulated={result.simulated?.atRiskOrders} delta={result.delta?.atRiskOrders} />
								<MetricRow label="高风险订单" baseline={result.baseline?.highRiskOrders} simulated={result.simulated?.highRiskOrders} delta={result.delta?.highRiskOrders} />
								<MetricRow label="缺口订单" baseline={result.baseline?.ordersWithShortage} simulated={result.simulated?.ordersWithShortage} delta={result.delta?.ordersWithShortage} />
								<MetricRow label="缺口总量（件）" baseline={result.baseline?.totalShortageUnits} simulated={result.simulated?.totalShortageUnits} delta={result.delta?.totalShortageUnits} />
								<MetricRow label="风险评分合计" baseline={result.baseline?.totalRiskScore} simulated={result.simulated?.totalRiskScore} delta={result.delta?.totalRiskScore} />
								<MetricRow label="目标 SKU ATP（件）" baseline={result.baseline?.targetSkuAvailableToPromise} simulated={result.simulated?.targetSkuAvailableToPromise} delta={result.delta?.targetSkuAvailableToPromise} />
							</tbody>
						</table>
					</div>
					{evidenceChanges.length > 0 && (
						<details className="mt-3 rounded-lg border border-[#e6eef7] bg-white/55 px-3 py-2">
							<summary className="cursor-pointer text-xs font-medium text-[#33506b]">发生变化的风险证据（{evidenceChanges.length}）</summary>
							<BulletList items={evidenceChanges} />
						</details>
					)}
					{(result.assumptions?.length ?? 0) > 0 && (
						<details className="mt-2 rounded-lg border border-[#e6eef7] bg-white/55 px-3 py-2">
							<summary className="cursor-pointer text-xs font-medium text-[#33506b]">模拟假设与局限</summary>
							<BulletList items={[...(result.assumptions ?? []), ...(result.limitations ?? [])]} />
						</details>
					)}
					{(result.dataGaps?.length ?? 0) > 0 && (
						<div className="mt-2 rounded-lg border border-[#ecd9b5] bg-[#fdf8ee] px-3 py-2">
							<div className="text-xs font-medium text-[#7a5a22]">仍然存在的数据缺口</div>
							<BulletList items={result.dataGaps ?? []} />
						</div>
					)}
					{result.disclaimer && <p className="mt-2 text-xs text-[#7a5a22]">{result.disclaimer}</p>}
				</Section>
			)}

			<Section title="候选方案对比与推荐" description="由后端确定性排名引擎对 +50 / +100 / +200 三个候选补货方案进行对比">
				{!comparison ? (
					<div className="flex flex-wrap items-center gap-2">
						<Button variant="outline" disabled={comparing} onClick={() => void runComparison()}>
							{comparing ? <Loader2 className="mr-2 h-4 w-4 animate-spin" /> : <Play className="mr-2 h-4 w-4" />}
							运行三方案对比
						</Button>
						<span className="text-xs text-[#7a8da1]">对比同样只读演练，不修改真实数据</span>
					</div>
				) : (
					<div className="space-y-3">
						<div className="overflow-x-auto">
							<table className="w-full text-sm">
								<thead>
									<tr className="border-b border-[#e2e8f0] text-left text-xs text-[#5a708c]">
										<th className="py-1.5 pr-3">候选</th>
										<th className="py-1.5 pr-3">排名</th>
										<th className="py-1.5 pr-3">缺口变化</th>
										<th className="py-1.5 pr-3">模拟后 ATP</th>
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
												<td className="py-1.5 pr-3">{signed(candidate?.simulation?.delta?.totalShortageUnits)}</td>
												<td className="py-1.5 pr-3">{candidate?.simulation?.simulated?.targetSkuAvailableToPromise ?? '—'}</td>
												<td className="py-1.5 text-xs text-[#5a708c]">{(ranking.keyDeltas ?? []).join('；') || '—'}</td>
											</tr>
										);
									})}
								</tbody>
							</table>
						</div>
						{comparison.recommendation && (
							<div className="rounded-lg border border-[#dbe6f2] bg-[#f8fbfe] p-3 text-sm">
								<div className="flex flex-wrap items-center gap-2">
									<Badge variant="outline">{comparison.recommendation.outcome ?? '—'}</Badge>
									{comparison.recommendation.recommendedLabel && (
										<span className="font-semibold text-[#1c3048]">推荐：{comparison.recommendation.recommendedLabel}</span>
									)}
								</div>
								{comparison.recommendation.summary && <p className="mt-1.5 whitespace-pre-wrap text-[#33506b]">{comparison.recommendation.summary}</p>}
								<div className="mt-2 space-y-2">
									{(comparison.recommendation.reasons?.length ?? 0) > 0 && (
										<details className="rounded-lg border border-[#e2e8f0] bg-white/70 px-3 py-2">
											<summary className="cursor-pointer text-xs font-medium text-[#33506b]">推荐依据（{comparison.recommendation.reasons?.length} 条）</summary>
											<BulletList items={comparison.recommendation.reasons ?? []} />
										</details>
									)}
									{(comparison.recommendation.unknowns?.length ?? 0) > 0 && (
										<details className="rounded-lg border border-[#e2e8f0] bg-white/70 px-3 py-2">
											<summary className="cursor-pointer text-xs font-medium text-[#33506b]">未知项（后端明确标注缺少的数据）</summary>
											<BulletList items={comparison.recommendation.unknowns ?? []} />
										</details>
									)}
									{(comparison.recommendation.manualConfirmation?.length ?? 0) > 0 && (
										<details className="rounded-lg border border-[#e2e8f0] bg-white/70 px-3 py-2">
											<summary className="cursor-pointer text-xs font-medium text-[#33506b]">需要人工确认的事项</summary>
											<BulletList items={comparison.recommendation.manualConfirmation ?? []} />
										</details>
									)}
								</div>
								{comparison.recommendation.disclaimer && <p className="mt-2 text-xs text-[#7a5a22]">{comparison.recommendation.disclaimer}</p>}
							</div>
						)}
						<div className="flex flex-wrap gap-2">
							<Button variant="outline" disabled={comparing} onClick={() => void runComparison()}>
								<RefreshCw className="mr-2 h-4 w-4" />
								重新对比
							</Button>
						</div>
					</div>
				)}
			</Section>

			<Section
				title="演练记录（本页会话）"
				description="每次运行都会与当前基线对比；重置仅清除本页展示，后端演练记录保留且真实业务数据不受影响"
				actions={
					<Button size="sm" variant="outline" onClick={resetDrill} disabled={runs.length === 0 && !result && !comparison}>
						<RotateCcw className="mr-2 h-4 w-4" />
						重置演练
					</Button>
				}
			>
				{runs.length === 0 ? (
					<p className="text-sm text-[#5a708c]">尚未运行模拟；调整参数后点击「运行模拟」开始演练。</p>
				) : (
					<ul className="space-y-1.5">
						{runs.map((run, index) => (
							<li key={`${run.at}-${index}`} className="flex flex-wrap items-center gap-x-3 gap-y-1 rounded-lg border border-[#e2e8f0] px-3 py-2 text-sm">
								<span className="font-medium text-[#1c3048]">补货 +{run.quantity} 件</span>
								<span className="text-xs text-[#5a708c]">缺口变化 <span className={((run.deltaShortage ?? 0) < 0 ? 'text-[#428766]' : '') + ' font-medium'}>{signed(run.deltaShortage)}</span></span>
								<span className="text-xs text-[#5a708c]">风险订单变化 {signed(run.deltaAtRisk)}</span>
								<span className="text-xs text-[#5a708c]">模拟后 ATP {run.simulatedAtp ?? '—'}{run.baselineAtp != null ? `（基线 ${run.baselineAtp}）` : ''}</span>
							</li>
						))}
					</ul>
				)}
			</Section>
		</div>
	);
}
