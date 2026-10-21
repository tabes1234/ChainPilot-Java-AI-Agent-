'use client';

import { Badge } from '@/components/ui/badge';
import { BulletList, Field, RiskBadge, Section, formatTime } from '@/components/supply-chain/common';
import { RiskEvidencePanel } from '@/components/supply-chain/risk-evidence';
import { FeedbackDialog } from '@/components/supply-chain/feedback-dialog';
import type { RiskAnalysisReport } from '@/lib/api/supplyChain';

export function ReportView({ report }: { report: RiskAnalysisReport }) {
	const narrative = report.modelNarrative ?? null;
	const narrativeSource = report.narrativeSource
		?? (narrative && report.modelAssisted ? 'MODEL_VERIFIED' : 'DETERMINISTIC_FALLBACK');
	const narrativeVerified = narrativeSource === 'MODEL_VERIFIED' && narrative != null;
	const narrativeLegacy = narrativeSource === 'MODEL_LEGACY_UNVERIFIED';
	return (
		<div className="space-y-4">
			<Section
				title="分析结论"
				actions={
					<div className="flex flex-wrap gap-1.5">
						<Badge variant="outline" className="border-green-200 bg-green-50 text-green-700">{report.status}</Badge>
						<Badge variant={narrativeVerified ? 'default' : 'secondary'}>
							{narrativeVerified ? '模型解释已校验' : narrativeLegacy ? '旧版模型摘要' : '确定性叙述'}
						</Badge>
						{report.plannerAssisted && <Badge variant="outline">规划器辅助</Badge>}
						<Badge variant="outline">校验尝试 {report.validationAttempts}</Badge>
						<FeedbackDialog taskId={report.taskId} resultType="ANALYSIS" narrativeSource={report.narrativeSource ?? undefined} />
					</div>
				}
			>
				<div className="space-y-3">
					<p className="whitespace-pre-wrap text-sm leading-relaxed text-[#1c3048]">{report.summary || '后端未返回摘要。'}</p>
					<p className="rounded-lg bg-white/50 px-3 py-2 text-xs leading-relaxed text-[#5a708c] ring-1 ring-white/70">
						{narrativeVerified
							? '叙述来源：模型解释。模型只解释 Java 规则已生成的风险证据，分数、等级与建议未被模型修改；证据 ID、数值和推荐范围均已通过服务端校验。'
							: narrativeLegacy
								? '叙述来源：旧版模型摘要。该报告生成时尚未启用结构化证据校验，摘要未经证据 ID 与数值校验，风险分数和证据仍来自 Java 规则。'
								: '叙述来源：Java 确定性规则摘要。模型未参与，或模型输出未通过证据/数值/建议校验时自动降级，风险分数与证据不受影响。'}
					</p>
					<div className="grid gap-3 sm:grid-cols-3">
						<Field label="任务 ID" value={<span className="font-mono text-xs">{report.taskId}</span>} />
						<Field label="风险订单" value={report.atRiskOrders?.length ?? 0} />
						<Field label="生成时间" value={formatTime(report.createdAt)} />
					</div>
				</div>
			</Section>

			{narrativeVerified && narrative && (
				<Section
					title="模型解释（证据约束）"
					description="仅展示已通过服务端校验的模型叙述；风险数值仍以下方确定性评估为准"
				>
					<div className="space-y-3">
						{(narrative.reasons?.length ?? 0) > 0 && (
							<div className="space-y-2">
								<div className="text-xs font-medium text-[#33506b]">按订单解释与证据引用</div>
								{(narrative.reasons ?? []).map((reason) => (
									<div key={`${reason.orderId}-${reason.summary}`} className="rounded-lg border border-[#e6eef7] bg-white/55 px-3 py-2">
										<div className="flex flex-wrap items-center justify-between gap-2">
											<span className="font-mono text-xs font-semibold text-[#1c3048]">{reason.orderId}</span>
											<div className="flex flex-wrap gap-1">
												{(reason.evidenceIds ?? []).map((evidenceId) => (
													<Badge key={evidenceId} variant="outline" className="border-[#dfe8f1] bg-white/70 text-[#33506b]">
														{evidenceId}
													</Badge>
												))}
											</div>
										</div>
										<p className="mt-1.5 text-xs leading-relaxed text-[#5a708c]">{reason.summary}</p>
									</div>
								))}
							</div>
						)}
						<div className="grid gap-3 lg:grid-cols-2">
							<div>
								<div className="mb-1 text-xs font-medium text-[#33506b]">业务影响</div>
								<BulletList items={narrative.businessImpacts} empty="模型未补充业务影响。" />
							</div>
							<div>
								<div className="mb-1 text-xs font-medium text-[#33506b]">模型建议（逐字来自规则建议）</div>
								<BulletList items={narrative.recommendations} empty="模型未重复列出建议。" />
							</div>
						</div>
						<div className="rounded-lg border border-[#ecd9b5] bg-[#fdf8ee] px-3 py-2">
							<div className="text-xs font-medium text-[#7a5a22]">不确定性与数据缺口</div>
							<BulletList items={narrative.uncertainties} empty="模型未声明额外不确定性。" />
						</div>
					</div>
				</Section>
			)}

			<Section title="风险订单" description="来自后端对当前订单/库存/供应商数据的真实评估">
				<p className="mb-3 rounded-lg bg-white/50 px-3 py-2 text-xs leading-relaxed text-[#5a708c] ring-1 ring-white/70">
					风险分由 Java 确定性规则计算：score ≥ 60 为高风险，30–59 为中风险，&lt; 30 为低风险；各项贡献封顶 100。
					模型只参与叙述，不生成库存值、供应商表现或风险分数。
				</p>
				{(report.atRiskOrders ?? []).length === 0 ? (
					<p className="text-sm text-[#5a708c]">当前没有识别到风险订单。</p>
				) : (
					<div className="grid gap-3 lg:grid-cols-2">
						{(report.atRiskOrders ?? []).map((item) => (
							<div key={item.order.orderId} className="rounded-lg border border-[#e2e8f0] p-3">
								<div className="flex flex-wrap items-center justify-between gap-2">
									<div>
										<div className="font-semibold text-[#1c3048]">{item.order.orderId}</div>
										<div className="text-xs text-[#5a708c]">
											SKU {item.order.sku} · 数量 {item.order.quantity} · 供应商 {item.order.supplierId}
										</div>
									</div>
									<div className="flex items-center gap-2">
										<RiskBadge level={item.riskLevel} />
										<span className="text-sm font-semibold text-[#1c3048]">{item.riskScore}/100</span>
									</div>
								</div>
								<div className="mt-2 grid gap-2 text-xs text-[#5a708c] sm:grid-cols-2">
									<span>要求交付：{item.order.requestedDeliveryDate ?? '—'}</span>
									<span>预计交付：{item.order.estimatedDeliveryDate ?? '—'}</span>
									<span>库存缺口：{item.inventoryShortage}</span>
									<span>订单状态：{item.order.status}</span>
								</div>
								<div className="mt-2">
									<RiskEvidencePanel item={item} />
								</div>
								{item.riskFactors?.length > 0 && (
									<div className="mt-2">
										<div className="text-xs font-medium text-[#33506b]">风险依据</div>
										<BulletList items={item.riskFactors} />
									</div>
								)}
								{item.recommendedActions?.length > 0 && (
									<div className="mt-2">
										<div className="text-xs font-medium text-[#33506b]">建议动作</div>
										<BulletList items={item.recommendedActions} />
									</div>
								)}
							</div>
						))}
					</div>
				)}
			</Section>

			<div className="grid gap-4 lg:grid-cols-2">
				<Section title="建议">
					<BulletList items={report.recommendations} empty="后端未返回建议。" />
				</Section>
				<Section title="执行计划">
					<BulletList items={report.executionPlan} empty="后端未返回执行计划。" />
				</Section>
			</div>

			<div className="grid gap-4 lg:grid-cols-2">
				<Section title="知识引用" description="来自业务术语与知识库的真实检索结果">
					{(report.citations ?? []).length === 0 ? (
						<p className="text-sm text-[#5a708c]">本次分析未引用知识条目。</p>
					) : (
						<div className="space-y-2">
							{(report.citations ?? []).map((citation) => (
								<details key={citation.sourceId} className="rounded-lg border border-[#e2e8f0] px-3 py-2">
									<summary className="cursor-pointer text-sm font-medium text-[#1c3048]">{citation.title || citation.sourceId}</summary>
									<p className="mt-2 whitespace-pre-wrap text-xs text-[#5a708c]">{citation.excerpt}</p>
								</details>
							))}
						</div>
					)}
				</Section>
				<Section title="工具调用" description={`${report.toolCalls?.length ?? 0} 次`}>
					{(report.toolCalls ?? []).length === 0 ? (
						<p className="text-sm text-[#5a708c]">本次分析未记录工具调用。</p>
					) : (
						<pre className="max-h-[220px] overflow-auto whitespace-pre-wrap rounded-lg bg-[#f8fbfe] p-2 text-xs text-[#33506b]">
							{(report.toolCalls ?? []).join('\n')}
						</pre>
					)}
				</Section>
			</div>
		</div>
	);
}
