'use client';

import { useState } from 'react';
import Link from 'next/link';
import { toast } from 'sonner';
import { Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { BulletList, Field, Metric, RiskBadge, Section, StatusBadge, formatTime, signed } from '@/components/supply-chain/common';
import { RecommendationDetails } from '@/components/supply-chain/recommendation-details';
import { RiskEvidencePanel } from '@/components/supply-chain/risk-evidence';
import { FeedbackDialog } from '@/components/supply-chain/feedback-dialog';
import { errorMessage } from '@/lib/api/client';
import {
	cancelProposal,
	confirmProposal,
	type DecisionResult,
	type Proposal,
	type ProposalAction,
	type Simulation,
} from '@/lib/api/supplyChain';

const ACTION_LABELS: Record<ProposalAction, string> = {
	SAVE_CANDIDATE_AS_PLAN: '保存候选为处置方案',
	LINK_CANDIDATE_TO_PLAN: '关联候选与已有方案',
	SELECT_PLAN: '选用处置方案',
	START_SANDBOX_EXECUTION: '启动沙箱执行',
};

export function DecisionView({ decision, onChanged }: { decision: DecisionResult; onChanged: () => void }) {
	const proposals = decision.proposals ?? [];
	const [pending, setPending] = useState<{ proposal: Proposal; kind: 'confirm' | 'cancel' } | null>(null);
	const [busy, setBusy] = useState(false);

	const run = async () => {
		if (!pending) return;
		setBusy(true);
		try {
			const result = pending.kind === 'confirm'
				? await confirmProposal(pending.proposal.proposalId, pending.proposal.contentHash)
				: await cancelProposal(pending.proposal.proposalId, pending.proposal.contentHash);
			toast.success(pending.kind === 'confirm' ? '提案已确认' : '提案已取消', {
				description: result?.status ? `当前状态：${result.status}` : undefined,
			});
			setPending(null);
			onChanged();
		} catch (error) {
			toast.error(pending.kind === 'confirm' ? '确认失败' : '取消失败', { description: errorMessage(error) });
		} finally {
			setBusy(false);
		}
	};

	return (
		<div className="space-y-4">
			<Section
				title="决策结论"
				actions={
					<div className="flex flex-wrap gap-1.5">
						<StatusBadge status={decision.status} />
						{decision.intent && <BadgeText label={`意图 ${decision.intent}`} />}
						{decision.narrativeSource && <BadgeText label={decision.narrativeSource} />}
						<BadgeText label={decision.modelAssisted ? '模型参与' : '确定性结果'} />
						{decision.fallbackUsed && <BadgeText label="模型降级" />}
						<BadgeText label={decision.realDataMutated ? '数据已变更' : '真实数据未变更'} />
						<FeedbackDialog taskId={decision.taskId} resultType="DECISION" narrativeSource={decision.narrativeSource ?? undefined} />
					</div>
				}
			>
				<div className="space-y-3">
					<p className="whitespace-pre-wrap text-sm leading-relaxed text-[#1c3048]">{decision.summary || '后端未返回摘要。'}</p>
					<div className="grid gap-3 sm:grid-cols-3">
						<Field label="任务 ID" value={<span className="font-mono text-xs">{decision.taskId}</span>} />
						<Field label="生成时间" value={formatTime(decision.createdAt)} />
						<Field label="需澄清" value={decision.clarificationRequired ? '是' : '否'} />
					</div>
					{decision.clarificationRequired && <BulletList items={decision.clarifications} empty="无澄清项" />}
				</div>
			</Section>

			<Section
				title="处置追溯链"
				description="生成建议不等于执行；以下状态均来自后端任务、提案、方案和执行记录"
			>
				<div className="grid gap-3 sm:grid-cols-4">
					<Field label="决策任务" value={<span className="font-mono text-xs">{decision.taskId}</span>} />
					<Field
						label="提案"
						value={proposals.length === 0 ? '当前无提案' : `${proposals.length} 条 · ${proposals.map((item) => item.status).join(' / ')}`}
					/>
					<Field
						label="关联方案"
						value={decision.plan ? <span className="inline-flex flex-wrap items-center gap-1"><StatusBadge status={decision.plan.status} /> <span className="font-mono text-xs">{decision.plan.planId}</span></span> : '尚未生成'}
					/>
					<Field
						label="沙箱执行"
						value={decision.execution
							? <span className="inline-flex flex-wrap items-center gap-1"><StatusBadge status={decision.execution.status} /><span className="text-xs">{decision.execution.mode ?? 'SANDBOX'}</span></span>
							: '尚未执行'}
					/>
				</div>
				<div className="mt-2 flex flex-wrap gap-2 text-xs">
					{decision.plan && (
						<Link href={`/supply-chain/disposal?planId=${encodeURIComponent(decision.plan.planId)}&taskId=${encodeURIComponent(decision.taskId)}`} className="font-medium text-[#35629d] hover:underline">
							查看处置方案与执行记录
						</Link>
					)}
					<Link href="/supply-chain/history" className="font-medium text-[#35629d] hover:underline">
						在任务历史中查看审计记录
					</Link>
				</div>
			</Section>

			{decision.comparison && (
				<Section title="方案对比" description={`基线来源：${decision.comparison.baselineSource ?? '—'} · 真实数据未变更`}>
					<div className="space-y-3">
						<div className="grid gap-2 sm:grid-cols-4">
							<Metric label="基线风险订单" value={decision.comparison.baseline?.atRiskOrders ?? '—'} />
							<Metric label="基线缺口总量" value={decision.comparison.baseline?.totalShortageUnits ?? '—'} />
							<Metric label="最佳风险订单变化" value={signed(decision.comparison.bestAtRiskOrdersDelta)} />
							<Metric label="最佳缺口变化" value={signed(decision.comparison.bestTotalShortageUnitsDelta)} />
						</div>
						{decision.comparison.recommendation && (
							<div className="rounded-lg border border-[#dbe6f2] bg-[#f8fbfe] p-3 text-sm">
								<div className="flex flex-wrap items-center gap-2">
									<BadgeText label={`结果 ${decision.comparison.recommendation.outcome ?? '—'}`} />
									{decision.comparison.recommendation.recommendedLabel && (
										<span className="font-semibold text-[#1c3048]">推荐：{decision.comparison.recommendation.recommendedLabel}</span>
									)}
								</div>
								{decision.comparison.recommendation.summary && (
									<p className="mt-1.5 whitespace-pre-wrap text-[#33506b]">{decision.comparison.recommendation.summary}</p>
								)}
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
										<th className="py-1.5">已关联方案</th>
									</tr>
								</thead>
								<tbody>
									{(decision.comparison.rankings ?? []).map((ranking) => {
										const candidate = decision.comparison?.candidates?.find((item) => item.candidateId === ranking.candidateId);
										return (
											<tr key={ranking.candidateId} className="border-b border-[#f1f5f9]">
												<td className="py-1.5 pr-3">
													{ranking.label ?? ranking.candidateId}
													{ranking.recommended && <span className="ml-1 text-xs text-[#428766]">（推荐）</span>}
												</td>
												<td className="py-1.5 pr-3">{ranking.rank ?? '不可比'}</td>
												<td className="py-1.5 pr-3 text-xs text-[#5a708c]">{ranking.parameterSummary ?? '—'}</td>
												<td className="py-1.5 pr-3 text-xs">{(ranking.keyDeltas ?? []).join('；') || '—'}</td>
												<td className="py-1.5 text-xs">{candidate?.linkedPlanId ?? '—'}</td>
											</tr>
										);
									})}
								</tbody>
							</table>
						</div>
					</div>
				</Section>
			)}

			{decision.simulation && <SimulationMetrics simulation={decision.simulation} />}

			{(decision.atRiskOrders?.length ?? 0) > 0 && (
				<Section title="风险依据" description="决策所基于的订单风险评分与原始业务证据由后端提供">
					<div className="space-y-2">
						{(decision.atRiskOrders ?? []).map((item) => (
							<div key={item.order.orderId} className="rounded-lg border border-[#e2e8f0] p-3">
								<div className="flex flex-wrap items-center justify-between gap-2">
									<div>
										<span className="font-medium text-[#1c3048]">{item.order.orderId}</span>
										<span className="ml-2 text-xs text-[#5a708c]">{item.order.sku} · {item.order.supplierId}</span>
									</div>
									<span className="flex items-center gap-2">
										<RiskBadge level={item.riskLevel} />
										<span className="text-xs font-semibold text-[#1c3048]">{item.riskScore}/100</span>
									</span>
								</div>
								<div className="mt-2">
									<RiskEvidencePanel item={item} />
								</div>
							</div>
						))}
					</div>
				</Section>
			)}

			<div className="grid gap-4 lg:grid-cols-2">
				<Section title="处置建议" description={`${decision.recommendations?.length ?? 0} 条确定性建议`}>
					{(decision.recommendations ?? []).length === 0 ? (
						<p className="text-sm text-[#5a708c]">本次决策未生成处置建议。</p>
					) : (
						<div className="space-y-2">
							{(decision.recommendations ?? []).map((recommendation) => (
								<div key={recommendation.recommendationId} className="rounded-lg border border-[#e2e8f0] p-2.5">
									<div className="flex flex-wrap items-center gap-1.5">
										<span className="font-medium text-[#1c3048]">{recommendation.title}</span>
										<BadgeText label={recommendation.priority} />
										<RiskBadge level={recommendation.riskLevel} />
									</div>
									<p className="mt-1 text-xs text-[#5a708c]">{recommendation.action}</p>
									<RecommendationDetails recommendation={recommendation} />
								</div>
							))}
						</div>
					)}
				</Section>
				<Section title="分析依据">
					<BulletList items={decision.evidenceNotes} empty="后端未返回补充依据。" />
					{(decision.citations ?? []).length > 0 && (
						<div className="mt-3 space-y-1">
							{(decision.citations ?? []).map((citation) => (
								<p key={citation.sourceId} className="text-xs text-[#5a708c]">
									<span className="font-medium text-[#33506b]">{citation.title}</span>：{citation.excerpt}
								</p>
							))}
						</div>
					)}
				</Section>
			</div>

			{decision.plan && (
				<Section
					title="关联处置方案"
					actions={<Link href={`/supply-chain/disposal?planId=${encodeURIComponent(decision.plan.planId)}&taskId=${encodeURIComponent(decision.taskId)}`}><Button size="sm" variant="outline">打开处置工作台</Button></Link>}
				>
					<div className="grid gap-3 sm:grid-cols-3">
						<Field label="方案 ID" value={<span className="font-mono text-xs">{decision.plan.planId}</span>} />
						<Field label="标题" value={decision.plan.title} />
						<Field label="状态" value={<StatusBadge status={decision.plan.status} />} />
						<Field label="关联任务" value={<span className="font-mono text-xs">{decision.plan.taskId ?? decision.taskId}</span>} />
						<Field label="用户选定建议" value={decision.plan.selectedRecommendationId ?? '尚未选择'} />
						<Field label="创建时间" value={formatTime(decision.plan.createdAt)} />
						<Field label="状态更新时间" value={formatTime(decision.plan.updatedAt)} />
					</div>
				</Section>
			)}

			{decision.execution && (
				<Section title="沙箱执行结果" description="仅演练，不写入真实业务系统">
					<div className="grid gap-3 sm:grid-cols-4">
						<Field label="执行 ID" value={<span className="font-mono text-xs">{decision.execution.executionId}</span>} />
						<Field label="状态" value={<StatusBadge status={decision.execution.status} />} />
						<Field label="模式" value={decision.execution.mode ?? 'SANDBOX'} />
						<Field label="真实数据变更" value={decision.execution.realDataMutated ? '是' : '否'} />
						<Field label="开始时间" value={formatTime(decision.execution.startedAt)} />
						<Field label="完成时间" value={formatTime(decision.execution.completedAt)} />
						<Field label="步骤数" value={decision.execution.steps?.length ?? 0} />
					</div>
					{decision.execution.disclaimer && (
						<p className="mt-2 text-xs text-[#7a5a22]">{decision.execution.disclaimer}</p>
					)}
				</Section>
			)}

			<Section title="待确认提案" description="确认操作需要服务端签发的 contentHash；前端不会自行计算">
				{proposals.length === 0 ? (
					<p className="text-sm text-[#5a708c]">当前没有待处理提案。</p>
				) : (
					<div className="space-y-3">
						{proposals.map((proposal) => (
							<div key={proposal.proposalId} className="rounded-lg border border-[#e2e8f0] p-3">
								<div className="flex flex-wrap items-center justify-between gap-2">
									<div className="flex flex-wrap items-center gap-1.5">
										<span className="font-medium text-[#1c3048]">{proposal.title}</span>
										<BadgeText label={ACTION_LABELS[proposal.actionType] ?? proposal.actionType} />
										<StatusBadge status={proposal.status} />
									</div>
									<span className="text-[11px] text-[#5a708c]">过期：{formatTime(proposal.expiresAt)}</span>
								</div>
								<div className="mt-1 flex flex-wrap gap-x-3 gap-y-1 text-[11px] text-[#5a708c]">
									{proposal.targetId && <span>目标 {proposal.targetId}</span>}
									<span>创建 {formatTime(proposal.createdAt)}</span>
									{proposal.confirmedAt && <span>确认 {formatTime(proposal.confirmedAt)}</span>}
								</div>
								{proposal.parameters && (
									<pre className="mt-2 max-h-[160px] overflow-auto whitespace-pre-wrap rounded bg-[#f8fbfe] p-2 text-xs text-[#33506b]">
										{JSON.stringify(proposal.parameters, null, 2)}
									</pre>
								)}
								<BulletList items={proposal.evidence} empty="" />
								{proposal.errorMessage && <p className="mt-1 text-xs text-[#8f3b45]">{proposal.errorMessage}</p>}
								<div className="mt-2 flex flex-wrap gap-2">
									<Button
										size="sm"
										disabled={proposal.status !== 'PENDING'}
										onClick={() => setPending({ proposal, kind: 'confirm' })}
									>
										确认执行
									</Button>
									<Button
										size="sm"
										variant="outline"
										disabled={proposal.status !== 'PENDING'}
										onClick={() => setPending({ proposal, kind: 'cancel' })}
									>
										取消提案
									</Button>
								</div>
								<p className="mt-1 font-mono text-[10px] text-[#8aa2ba]">hash {proposal.contentHash?.slice(0, 16)}…</p>
							</div>
						))}
					</div>
				)}
			</Section>

			<Dialog open={pending !== null} onOpenChange={(open) => !open && setPending(null)}>
				<DialogContent>
					<DialogHeader>
						<DialogTitle>{pending?.kind === 'confirm' ? '确认提案' : '取消提案'}</DialogTitle>
						<DialogDescription>
							{pending?.kind === 'confirm'
								? `确认后将由后端执行「${pending?.proposal.title}」对应的动作。确定继续？`
								: `确定要取消「${pending?.proposal.title}」吗？`}
						</DialogDescription>
					</DialogHeader>
					<DialogFooter>
						<Button variant="outline" onClick={() => setPending(null)}>返回</Button>
						<Button variant={pending?.kind === 'confirm' ? 'default' : 'destructive'} disabled={busy} onClick={run}>
							{busy && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
							确定
						</Button>
					</DialogFooter>
				</DialogContent>
			</Dialog>
		</div>
	);
}

function BadgeText({ label }: { label: string }) {
	return <span className="rounded-full border border-[#e2e8f0] bg-white px-2 py-0.5 text-[11px] text-[#33506b]">{label}</span>;
}

export function SimulationMetrics({ simulation }: { simulation: Simulation }) {
	const baseline = simulation.baseline;
	const simulated = simulation.simulated;
	const delta = simulation.delta;
	if (!baseline || !simulated) return null;
	const rows = [
		{ label: '风险订单', baseline: baseline.atRiskOrders, simulated: simulated.atRiskOrders, delta: delta?.atRiskOrders },
		{ label: '高风险订单', baseline: baseline.highRiskOrders, simulated: simulated.highRiskOrders, delta: delta?.highRiskOrders },
		{ label: '缺口订单', baseline: baseline.ordersWithShortage, simulated: simulated.ordersWithShortage, delta: delta?.ordersWithShortage },
		{ label: '缺口总量', baseline: baseline.totalShortageUnits, simulated: simulated.totalShortageUnits, delta: delta?.totalShortageUnits },
		{ label: '风险评分', baseline: baseline.totalRiskScore, simulated: simulated.totalRiskScore, delta: delta?.totalRiskScore },
	];
	const evidenceChanges = (simulation.affectedOrders ?? []).flatMap((impact) =>
		(impact.evidenceChanges ?? []).map((change) => `${impact.orderId}：${change}`),
	);
	const gaps = simulation.dataGaps ?? [];
	return (
		<Section
			title="模拟结果"
			description={`场景 ${simulation.scenario} · 真实数据未变更`}
			actions={
				<div className="flex flex-wrap gap-1.5">
					<Badge variant="outline">{simulation.simulationStatus ?? 'SIMULATED'}</Badge>
					<Badge variant="outline">规则 {simulation.ruleVersion ?? 'RISK_RULES_V1'}</Badge>
					<Badge variant="secondary">{simulation.realDataMutated ? '真实数据已变更' : '真实数据未变更'}</Badge>
				</div>
			}
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
						{rows.map((row) => (
							<tr key={row.label} className="border-b border-[#f1f5f9]">
								<td className="py-1.5 pr-3">{row.label}</td>
								<td className="py-1.5 pr-3">{row.baseline}</td>
								<td className="py-1.5 pr-3">{row.simulated}</td>
								<td className={`py-1.5 ${(row.delta ?? 0) < 0 ? 'text-[#428766]' : (row.delta ?? 0) > 0 ? 'text-[#b9575c]' : ''}`}>
									{signed(row.delta)}
								</td>
							</tr>
						))}
					</tbody>
				</table>
			</div>
			{evidenceChanges.length > 0 && (
				<details className="mt-3 rounded-lg border border-[#e6eef7] bg-white/55 px-3 py-2" open>
					<summary className="cursor-pointer text-xs font-medium text-[#33506b]">
						发生变化的风险证据（{evidenceChanges.length}）
					</summary>
					<BulletList items={evidenceChanges} />
				</details>
			)}
			{gaps.length > 0 && (
				<div className="mt-3 rounded-lg border border-[#ecd9b5] bg-[#fdf8ee] px-3 py-2">
					<div className="text-xs font-medium text-[#7a5a22]">仍然存在的数据缺口</div>
					<BulletList items={gaps} />
				</div>
			)}
			{simulation.disclaimer && <p className="mt-2 text-xs text-[#5a708c]">{simulation.disclaimer}</p>}
		</Section>
	);
}
