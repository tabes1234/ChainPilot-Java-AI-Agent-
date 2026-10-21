'use client';

import { Fragment, useState } from 'react';
import { AlertTriangle, GitCompareArrows, RefreshCw } from 'lucide-react';
import { EmptyState, ErrorState, LoadingState } from '@/components/data-states';
import { InlineStat, SectionHeading, Surface, Toolbar } from '@/components/surface';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { errorMessage } from '@/lib/api/client';
import type { EvaluationRun, EvaluationRunComparison } from '@/lib/api/evaluation';
import {
	assertionChangeLabel,
	caseStatusLabel,
	caseTone,
	comparisonChangeLabel,
	comparisonChangeTone,
	formatDuration,
	formatPercent,
	formatSignedDuration,
	formatSignedNumber,
	formatSignedPercent,
	modeLabel,
} from '@/lib/evaluation-format';
import { useEvaluationComparison } from '@/lib/query/hooks';
import { formatTime } from '@/components/supply-chain/common';
import { cn } from '@/lib/utils';

function runLabel(run: EvaluationRun): string {
	return `${modeLabel(run.mode)} · ${run.passed}/${run.totalCases} 通过 · ${formatTime(run.startedAt)} · ${run.runId.slice(0, 8)}`;
}

export function ComparisonPanel({ runs }: { runs: EvaluationRun[] }) {
	const completed = runs.filter((run) => run.status === 'COMPLETED');
	const [baseChoice, setBaseChoice] = useState<string | null>(null);
	const [targetChoice, setTargetChoice] = useState<string | null>(null);
	const [expandedCase, setExpandedCase] = useState<string | null>(null);

	// Prefer the newest pair that can be like-for-like; a single-case rerun should not
	// become the default target against a 14-case run.
	let defaultBaseRunId = completed[1]?.runId ?? null;
	let defaultTargetRunId = completed[0]?.runId ?? null;
	for (let index = 0; index < completed.length; index++) {
		const target = completed[index];
		const base = completed.slice(index + 1).find(
			(candidate) =>
				candidate.mode === target.mode &&
				candidate.datasetVersion === target.datasetVersion &&
				candidate.totalCases === target.totalCases,
		);
		if (base) {
			defaultBaseRunId = base.runId;
			defaultTargetRunId = target.runId;
			break;
		}
	}

	const runExists = (runId: string | null) => runId !== null && completed.some((run) => run.runId === runId);
	const baseRunId = runExists(baseChoice) ? baseChoice : defaultBaseRunId;
	const targetRunId = runExists(targetChoice) ? targetChoice : defaultTargetRunId;
	const comparisonQuery = useEvaluationComparison(baseRunId, targetRunId);
	const comparison = comparisonQuery.data;
	const selectItems = Object.fromEntries(completed.map((run) => [run.runId, runLabel(run)]));

	return (
		<div className="space-y-4">
			<Toolbar>
				<div className="flex min-w-0 flex-1 flex-wrap items-center gap-2">
					<span className="text-xs font-medium text-[#5f7389]">基线</span>
					<Select items={selectItems} value={baseRunId ?? ''} onValueChange={(value) => setBaseChoice(value ?? null)}>
						<SelectTrigger className="w-full min-w-[220px] sm:w-[320px]">
							<SelectValue placeholder="选择较早的运行" />
						</SelectTrigger>
						<SelectContent>
							{completed.map((run) => (
								<SelectItem key={run.runId} value={run.runId}>
									{runLabel(run)}
								</SelectItem>
							))}
						</SelectContent>
					</Select>
					<GitCompareArrows className="h-4 w-4 shrink-0 text-[#7a8da1]" />
					<span className="text-xs font-medium text-[#5f7389]">对比</span>
					<Select items={selectItems} value={targetRunId ?? ''} onValueChange={(value) => setTargetChoice(value ?? null)}>
						<SelectTrigger className="w-full min-w-[220px] sm:w-[320px]">
							<SelectValue placeholder="选择较新的运行" />
						</SelectTrigger>
						<SelectContent>
							{completed.map((run) => (
								<SelectItem key={run.runId} value={run.runId}>
									{runLabel(run)}
								</SelectItem>
							))}
						</SelectContent>
					</Select>
				</div>
				<Button
					variant="outline"
					size="sm"
					onClick={() => comparisonQuery.refetch()}
					disabled={!baseRunId || !targetRunId || baseRunId === targetRunId || comparisonQuery.isFetching}
				>
					<RefreshCw className={cn('mr-1.5 h-3.5 w-3.5', comparisonQuery.isFetching && 'animate-spin')} />
					重新对比
				</Button>
			</Toolbar>

			{completed.length < 2 ? (
				<Surface>
					<EmptyState
						title="至少需要两次已完成运行"
						description="运行对比只读取已持久化且结果完整的 COMPLETED 运行。当前可比较的运行数量不足。"
					/>
				</Surface>
			) : !baseRunId || !targetRunId || baseRunId === targetRunId ? (
				<Surface>
					<EmptyState title="请选择两次不同的运行" description="基线应为较早的运行，对比应为较新的运行。" />
				</Surface>
			) : comparisonQuery.isPending ? (
				<LoadingState label="计算运行差异…" />
			) : comparisonQuery.isError ? (
				<ErrorState message={errorMessage(comparisonQuery.error, '加载运行对比失败')} onRetry={() => comparisonQuery.refetch()} />
			) : comparison ? (
				<ComparisonResult comparison={comparison} expandedCase={expandedCase} onExpand={setExpandedCase} />
			) : null}
		</div>
	);
}

function ComparisonResult({
	comparison,
	expandedCase,
	onExpand,
}: {
	comparison: EvaluationRunComparison;
	expandedCase: string | null;
	onExpand: (caseId: string | null) => void;
}) {
	const { baseRun, targetRun, delta } = comparison;
	return (
		<div className="space-y-4">
			{!comparison.comparable && (
				<Surface className="border-[#ecd9b5] bg-[#fdf8ee]/80">
					<div className="flex items-start gap-2 text-sm text-[#7a5a22]">
						<AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
						<div className="min-w-0 space-y-1">
							<div className="font-medium">两次运行不能作为同一测试集直接比较</div>
							<div className="text-xs leading-relaxed">{comparison.reason ?? '案例集或断言集合不一致。'}</div>
							{comparison.versionNote && <div className="text-xs">{comparison.versionNote}</div>}
							{comparison.missingInTarget.length > 0 && (
								<div className="text-xs">对比运行缺少案例：{comparison.missingInTarget.join('、')}</div>
							)}
							{comparison.extraInTarget.length > 0 && (
								<div className="text-xs">对比运行多出案例：{comparison.extraInTarget.join('、')}</div>
							)}
							{comparison.assertionSetMismatches.length > 0 && (
								<div className="text-xs">断言集合不一致：{comparison.assertionSetMismatches.join('、')}</div>
							)}
						</div>
					</div>
				</Surface>
			)}

			<Surface>
				<SectionHeading
					title="运行指标对比"
					description={`${modeLabel(comparison.mode ?? baseRun.mode)} · 案例集 ${comparison.caseSetVersion ?? baseRun.caseSetVersion}`}
					actions={
						<Badge variant="outline" className={comparison.comparable ? 'border-green-200 bg-green-50 text-green-700' : 'border-amber-200 bg-amber-50 text-amber-700'}>
							{comparison.comparable ? '可比' : '不可比'}
						</Badge>
					}
				/>
				<div className="mb-4 flex flex-wrap gap-x-8 gap-y-3">
					<InlineStat label="基线运行" value={<span className="font-mono text-xs">{baseRun.runId.slice(0, 12)}</span>} />
					<InlineStat label="对比运行" value={<span className="font-mono text-xs">{targetRun.runId.slice(0, 12)}</span>} />
					<InlineStat label="基线通过率" value={formatPercent(baseRun.passRate)} />
					<InlineStat label="对比通过率" value={formatPercent(targetRun.passRate)} />
					<InlineStat label="通过率变化" value={formatSignedPercent(delta.passRate)} />
				</div>
				<div className="overflow-x-auto">
					<Table>
						<TableHeader>
							<TableRow>
								<TableHead>指标</TableHead>
								<TableHead>基线</TableHead>
								<TableHead>对比</TableHead>
								<TableHead>变化（对比 - 基线）</TableHead>
							</TableRow>
						</TableHeader>
						<TableBody>
							<ComparisonRow label="案例总数" base={baseRun.totalCases} target={targetRun.totalCases} />
							<ComparisonRow label="通过" base={baseRun.passed} target={targetRun.passed} deltaValue={formatSignedNumber(delta.passed)} />
							<ComparisonRow label="失败" base={baseRun.failed} target={targetRun.failed} deltaValue={formatSignedNumber(delta.failed)} />
							<ComparisonRow label="无法判定" base={baseRun.undetermined} target={targetRun.undetermined} deltaValue={formatSignedNumber(delta.undetermined)} />
							<ComparisonRow label="通过率" base={formatPercent(baseRun.passRate)} target={formatPercent(targetRun.passRate)} deltaValue={formatSignedPercent(delta.passRate)} />
							<ComparisonRow label="运行总耗时" base={formatDuration(baseRun.runDurationMs)} target={formatDuration(targetRun.runDurationMs)} deltaValue={formatSignedDuration(delta.runDurationMs)} />
							<ComparisonRow label="案例平均耗时" base={formatDuration(baseRun.averageCaseDurationMs)} target={formatDuration(targetRun.averageCaseDurationMs)} deltaValue={formatSignedDuration(delta.averageCaseDurationMs)} />
							<ComparisonRow
								label="案例 P95"
								base={`${formatDuration(baseRun.p95CaseDurationMs)} (n=${baseRun.p95SampleSize})`}
								target={`${formatDuration(targetRun.p95CaseDurationMs)} (n=${targetRun.p95SampleSize})`}
								deltaValue={formatSignedDuration(delta.p95CaseDurationMs)}
							/>
						</TableBody>
					</Table>
				</div>
				<p className="mt-2 text-xs text-[#7a8da1]">
					P95 仅在对应样本量≥5 时计算；样本不足时显示“—”，不填补估算值。运行总耗时、案例耗时分别展示。
				</p>
			</Surface>

			{comparison.comparable && (
				<div className="grid gap-4 md:grid-cols-3">
					<FailureList title="新增失败" empty="没有新增失败" cases={comparison.newFailures} tone="text-[#a45151]" />
					<FailureList title="已修复失败" empty="没有已修复失败" cases={comparison.fixedFailures} tone="text-[#3f7a5d]" />
					<FailureList title="持续失败" empty="没有持续失败" cases={comparison.persistentFailures} tone="text-[#9a772f]" />
				</div>
			)}

			{comparison.comparable && (
			<Surface>
				<SectionHeading title="逐案例状态与断言差异" description="差异只来自两次持久化运行结果，不依据案例名称匹配。" />
				{comparison.cases.length === 0 ? (
					<p className="text-sm text-[#6a7d91]">没有可用于逐案例比较的数据。</p>
				) : (
					<div className="overflow-x-auto">
						<Table>
							<TableHeader>
								<TableRow>
									<TableHead>案例</TableHead>
									<TableHead>基线状态</TableHead>
									<TableHead>对比状态</TableHead>
									<TableHead>变化</TableHead>
									<TableHead>断言变化</TableHead>
								</TableRow>
							</TableHeader>
							<TableBody>
								{comparison.cases.map((item) => {
									const expanded = expandedCase === item.caseId;
									return (
										<Fragment key={item.caseId}>
											<TableRow>
												<TableCell className="max-w-[300px]">
													<div className="truncate text-sm text-[#1f2a37]" title={item.name}>{item.name}</div>
													<div className="font-mono text-[11px] text-[#8aa2ba]">{item.caseId}</div>
													{item.note && <div className="mt-1 text-[11px] text-[#8a6a34]">{item.note}</div>}
												</TableCell>
												<TableCell><Badge variant="outline" className={caseTone(item.baseStatus)}>{caseStatusLabel(item.baseStatus)}</Badge></TableCell>
												<TableCell><Badge variant="outline" className={caseTone(item.targetStatus)}>{caseStatusLabel(item.targetStatus)}</Badge></TableCell>
												<TableCell>
													<Badge variant="outline" className={comparisonChangeTone(item.change)}>{comparisonChangeLabel(item.change)}</Badge>
												</TableCell>
												<TableCell>
													{item.assertionChanges.length === 0 ? (
														<span className="text-xs text-[#9aabbc]">无变化</span>
													) : (
														<button
															type="button"
															onClick={() => onExpand(expanded ? null : item.caseId)}
															className="text-xs font-medium text-[#35629d] hover:underline"
														>
															{expanded ? '收起' : `查看 ${item.assertionChanges.length} 条`}
														</button>
													)}
												</TableCell>
											</TableRow>
											{expanded && (
												<TableRow key={`${item.caseId}-diff`}>
													<TableCell colSpan={5} className="bg-[#f8fbfe]/70">
														<ul className="space-y-2 py-1">
															{item.assertionChanges.map((assertion) => (
																<li key={assertion.id} className="text-xs">
																	<div className="font-medium text-[#33506b]">{assertion.description}</div>
																	<div className="mt-0.5 text-[#5f7389]">
																		{assertionChangeLabel(assertion.change)} · 期望 {assertion.baseExpected} → {assertion.targetExpected} · 实际 {assertion.baseActual} → {assertion.targetActual}
																	</div>
																</li>
															))}
														</ul>
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
			</Surface>
			)}
		</div>
	);
}

function ComparisonRow({
	label,
	base,
	target,
	deltaValue,
}: {
	label: string;
	base: React.ReactNode;
	target: React.ReactNode;
	deltaValue?: React.ReactNode;
}) {
	return (
		<TableRow>
			<TableCell className="text-sm text-[#33506b]">{label}</TableCell>
			<TableCell className="text-sm">{base}</TableCell>
			<TableCell className="text-sm">{target}</TableCell>
			<TableCell className="text-sm">{deltaValue ?? '—'}</TableCell>
		</TableRow>
	);
}

function FailureList({
	title,
	empty,
	cases,
	tone,
}: {
	title: string;
	empty: string;
	cases: string[];
	tone: string;
}) {
	return (
		<Surface>
			<SectionHeading title={title} />
			{cases.length === 0 ? (
				<p className="text-xs text-[#6a7d91]">{empty}</p>
			) : (
				<ul className={cn('space-y-1 text-xs font-medium', tone)}>
					{cases.map((caseId) => (
						<li key={caseId} className="font-mono">{caseId}</li>
					))}
				</ul>
			)}
		</Surface>
	);
}
