'use client';

import { useMemo, useState } from 'react';
import Link from 'next/link';
import { useQuery } from '@tanstack/react-query';
import { CheckCircle2, FilePlus2, Play, RefreshCw, ShieldCheck } from 'lucide-react';
import { toast } from 'sonner';
import { InlineStat, SectionHeading, Surface, Toolbar } from '@/components/surface';
import { EmptyState, ErrorState, LoadingState } from '@/components/data-states';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Textarea } from '@/components/ui/textarea';
import { errorMessage } from '@/lib/api/client';
import {
	ASSERTION_TYPE_LABELS,
	FEEDBACK_TYPE_LABELS,
	createFeedbackDraft,
	enableFeedbackDraft,
	fetchFeedbackDrafts,
	fetchFeedbackPage,
	fetchFeedbackStats,
	reviewFeedbackDraft,
	updateFeedbackDraft,
	type FeedbackAssertion,
	type FeedbackDraft,
} from '@/lib/api/feedback';
import { startEvaluationRun, type EvaluationRun } from '@/lib/api/evaluation';
import { formatTime } from '@/components/supply-chain/common';

const TYPE_FILTERS: Record<string, string> = { ALL: '全部反馈类型', ...FEEDBACK_TYPE_LABELS };
const RESULT_FILTERS: Record<string, string> = { ALL: '全部分析/决策', ANALYSIS: '风险分析', DECISION: '决策建议' };
const SOURCE_FILTERS: Record<string, string> = {
	ALL: '全部叙述来源',
	MODEL_VERIFIED: '模型已验证',
	MODEL_LEGACY_UNVERIFIED: '历史模型未验证',
	DETERMINISTIC_FALLBACK: '确定性回退',
	UNKNOWN: '来源未记录',
};
const STATUS_LABELS: Record<string, string> = {
	DRAFT: '待补充预期结果',
	READY_FOR_REVIEW: '待审核',
	ENABLED: '已启用评测案例',
};

export function FeedbackPanel({ onChanged }: { onChanged?: () => void }) {
	const [type, setType] = useState('ALL');
	const [resultType, setResultType] = useState('ALL');
	const [source, setSource] = useState('ALL');
	const [page, setPage] = useState(1);
	const [selectedDraft, setSelectedDraft] = useState<FeedbackDraft | null>(null);
	const [busy, setBusy] = useState(false);
	const [run, setRun] = useState<EvaluationRun | null>(null);

	const feedbackQuery = useQuery({
		queryKey: ['feedbackPage', type, resultType, source, page],
		queryFn: () => fetchFeedbackPage({
			page,
			pageSize: 8,
			feedbackType: type === 'ALL' ? undefined : type,
			resultType: resultType === 'ALL' ? undefined : resultType,
			narrativeSource: source === 'ALL' ? undefined : source,
		}),
	});
	const statsQuery = useQuery({ queryKey: ['feedbackStats'], queryFn: fetchFeedbackStats });
	const draftsQuery = useQuery({ queryKey: ['feedbackDrafts'], queryFn: () => fetchFeedbackDrafts(1, 100) });
	const draftByFeedback = useMemo(
		() => new Map((draftsQuery.data ?? []).map((draft) => [draft.feedbackId, draft])),
		[draftsQuery.data],
	);

	const openDraft = async (feedbackId: string) => {
		const existing = draftByFeedback.get(feedbackId);
		if (existing) {
			setSelectedDraft(existing);
			setRun(null);
			return;
		}
		setBusy(true);
		try {
			const draft = await createFeedbackDraft(feedbackId);
			setSelectedDraft(draft);
			setRun(null);
			await Promise.all([draftsQuery.refetch(), statsQuery.refetch()]);
			onChanged?.();
		} catch (error) {
			toast.error('创建案例草案失败', { description: errorMessage(error) });
		} finally {
			setBusy(false);
		}
	};

	const saveDraft = async (next: Partial<FeedbackDraft>) => {
		if (!selectedDraft) return;
		setBusy(true);
		try {
			const updated = await updateFeedbackDraft(selectedDraft.draftId, {
				expectedBehavior: next.expectedBehavior ?? selectedDraft.expectedBehavior,
				assertions: next.assertions ?? selectedDraft.assertions,
			});
			setSelectedDraft(updated);
			await Promise.all([draftsQuery.refetch(), statsQuery.refetch()]);
		} catch (error) {
			toast.error('保存草案失败', { description: errorMessage(error) });
		} finally {
			setBusy(false);
		}
	};

	const review = async () => {
		if (!selectedDraft) return;
		setBusy(true);
		try {
			const updated = await reviewFeedbackDraft(selectedDraft.draftId);
			setSelectedDraft(updated);
			await Promise.all([draftsQuery.refetch(), statsQuery.refetch()]);
			toast.success('草案已提交审核');
		} catch (error) {
			toast.error('提交审核失败', { description: errorMessage(error) });
		} finally {
			setBusy(false);
		}
	};

	const enable = async () => {
		if (!selectedDraft) return;
		setBusy(true);
		try {
			const updated = await enableFeedbackDraft(selectedDraft.draftId);
			setSelectedDraft(updated);
			await Promise.all([draftsQuery.refetch(), statsQuery.refetch(), feedbackQuery.refetch()]);
			onChanged?.();
			toast.success('评测案例已启用', { description: updated.caseId ?? '' });
		} catch (error) {
			toast.error('启用评测案例失败', { description: errorMessage(error) });
		} finally {
			setBusy(false);
		}
	};

	const runCase = async () => {
		if (!selectedDraft?.caseId) return;
		setBusy(true);
		try {
			const evaluationRun = await startEvaluationRun([selectedDraft.caseId], 'OFFLINE');
			setRun(evaluationRun);
			onChanged?.();
			toast.success('评测已执行', { description: `${evaluationRun.passed} 通过 / ${evaluationRun.failed} 失败` });
		} catch (error) {
			toast.error('执行评测失败', { description: errorMessage(error) });
		} finally {
			setBusy(false);
		}
	};

	const updateAssertion = (index: number, expected: string) => {
		if (!selectedDraft) return;
		const assertions = selectedDraft.assertions.map((item, itemIndex) => itemIndex === index ? { ...item, expected } : item);
		setSelectedDraft({ ...selectedDraft, assertions });
	};

	return (
		<div className="space-y-4">
			<Surface>
				<SectionHeading
					title="人工反馈与评测闭环"
					description="反馈只记录用户评价；生成草案必须人工补充预期与断言，审核启用后才能执行。"
					actions={<Button size="sm" variant="outline" onClick={() => { feedbackQuery.refetch(); statsQuery.refetch(); draftsQuery.refetch(); }}><RefreshCw className="mr-1 h-4 w-4" />刷新</Button>}
				/>
				{statsQuery.isPending ? <LoadingState compact label="加载反馈统计…" /> : statsQuery.isError ? (
					<ErrorState message={errorMessage(statsQuery.error, '加载反馈统计失败')} onRetry={() => statsQuery.refetch()} />
				) : statsQuery.data ? (
					<div className="space-y-3">
						<div className="flex flex-wrap gap-y-3">
							<InlineStat label="反馈总数" value={statsQuery.data.total} />
							<InlineStat label="待补充草案" value={statsQuery.data.drafts} />
							<InlineStat label="待审核" value={statsQuery.data.readyForReview} />
							<InlineStat label="已启用案例" value={statsQuery.data.enabledCases} />
							{Object.entries(statsQuery.data.byType).map(([key, value]) => (
								<InlineStat key={key} label={FEEDBACK_TYPE_LABELS[key] ?? key} value={value} />
							))}
						</div>
						<p className="text-xs text-[#6a7d91]">
							统计仅来自已持久化反馈；样本较少时不展示比例结论。反馈来源不等同于标准答案。
						</p>
					</div>
				) : null}
			</Surface>

			<Toolbar>
				<Select items={TYPE_FILTERS} value={type} onValueChange={(value) => { setType(value ?? 'ALL'); setPage(1); }}>
					<SelectTrigger className="w-[170px]"><SelectValue /></SelectTrigger>
					<SelectContent>{Object.entries(TYPE_FILTERS).map(([value, label]) => <SelectItem key={value} value={value}>{label}</SelectItem>)}</SelectContent>
				</Select>
				<Select items={RESULT_FILTERS} value={resultType} onValueChange={(value) => { setResultType(value ?? 'ALL'); setPage(1); }}>
					<SelectTrigger className="w-[140px]"><SelectValue /></SelectTrigger>
					<SelectContent>{Object.entries(RESULT_FILTERS).map(([value, label]) => <SelectItem key={value} value={value}>{label}</SelectItem>)}</SelectContent>
				</Select>
				<Select items={SOURCE_FILTERS} value={source} onValueChange={(value) => { setSource(value ?? 'ALL'); setPage(1); }}>
					<SelectTrigger className="w-[160px]"><SelectValue /></SelectTrigger>
					<SelectContent>{Object.entries(SOURCE_FILTERS).map(([value, label]) => <SelectItem key={value} value={value}>{label}</SelectItem>)}</SelectContent>
				</Select>
			</Toolbar>

			<div className="grid gap-4 xl:grid-cols-[minmax(0,1fr)_minmax(0,1.2fr)]">
				<Surface className="min-w-0">
					<SectionHeading title="反馈列表" description={feedbackQuery.data ? `共 ${feedbackQuery.data.totalElements} 条` : '加载中…'} />
					{feedbackQuery.isPending ? <LoadingState compact label="加载反馈…" /> : feedbackQuery.isError ? (
						<ErrorState message={errorMessage(feedbackQuery.error, '加载反馈失败')} onRetry={() => feedbackQuery.refetch()} />
					) : (feedbackQuery.data?.items.length ?? 0) === 0 ? (
						<EmptyState title="当前筛选没有反馈" description="在风险分析报告或决策结论中提交反馈后会显示在这里。" />
					) : (
						<div className="space-y-3">
							<div className="space-y-2">
								{(feedbackQuery.data?.items ?? []).map((feedback) => {
									const draft = draftByFeedback.get(feedback.feedbackId);
									return (
										<div key={feedback.feedbackId} className="rounded-lg border border-[#e6eef7] bg-white/50 px-3 py-2.5">
											<div className="flex flex-wrap items-start justify-between gap-2">
												<div className="min-w-0">
													<div className="line-clamp-2 text-sm font-medium text-[#1c3048]">{feedback.snapshot.question || feedback.taskId}</div>
													<div className="mt-1 font-mono text-[10px] text-[#8aa2ba]">{feedback.taskId}</div>
												</div>
												<Badge variant="outline">{FEEDBACK_TYPE_LABELS[feedback.feedbackType] ?? feedback.feedbackType}</Badge>
											</div>
											{feedback.comment && <p className="mt-1 text-xs text-[#5a708c]">{feedback.comment}</p>}
											<div className="mt-1 flex flex-wrap gap-1.5 text-[11px] text-[#5a708c]">
												<span>{feedback.resultType === 'ANALYSIS' ? '风险分析' : '决策建议'}</span>
												<span>来源 {feedback.narrativeSource ?? 'UNKNOWN'}</span>
												<span>{formatTime(feedback.createdAt)}</span>
												{draft && <Badge variant="outline">{STATUS_LABELS[draft.status] ?? draft.status}</Badge>}
											</div>
											<div className="mt-2 flex flex-wrap gap-1.5">
												<Link href={feedback.resultType === 'ANALYSIS' ? `/supply-chain?taskId=${encodeURIComponent(feedback.taskId)}` : `/supply-chain/decision?taskId=${encodeURIComponent(feedback.taskId)}`}>
													<Button size="sm" variant="ghost">查看原结果</Button>
												</Link>
												<Button size="sm" variant="outline" disabled={busy} onClick={() => void openDraft(feedback.feedbackId)}>
													<FilePlus2 className="mr-1 h-4 w-4" />
													{draft ? '查看草案' : '生成案例草案'}
												</Button>
											</div>
										</div>
									);
								})}
							</div>
							<div className="flex flex-wrap items-center justify-end gap-2 border-t border-[#e6eef7] pt-3">
								<span className="text-xs text-[#5a708c]">第 {feedbackQuery.data?.page ?? 1} / {Math.max(1, feedbackQuery.data?.totalPages ?? 1)} 页</span>
								<Button size="sm" variant="outline" disabled={(feedbackQuery.data?.page ?? 1) <= 1} onClick={() => setPage((current) => Math.max(1, current - 1))}>上一页</Button>
								<Button size="sm" variant="outline" disabled={(feedbackQuery.data?.page ?? 1) >= (feedbackQuery.data?.totalPages ?? 1)} onClick={() => setPage((current) => current + 1)}>下一页</Button>
							</div>
						</div>
					)}
				</Surface>

				<Surface className="min-w-0">
					{!selectedDraft ? (
						<EmptyState title="选择反馈生成草案" description="草案初始不包含预期结果或断言；规则建议仅作为待确认的参考。" />
					) : (
						<DraftEditor
							draft={selectedDraft}
							busy={busy}
							run={run}
							onExpectedChange={(value) => setSelectedDraft({ ...selectedDraft, expectedBehavior: value })}
							onAssertionChange={updateAssertion}
							onApplySuggestions={() => setSelectedDraft({ ...selectedDraft, assertions: selectedDraft.suggestedAssertions })}
							onRemoveAssertion={(index) => setSelectedDraft({ ...selectedDraft, assertions: selectedDraft.assertions.filter((_, itemIndex) => itemIndex !== index) })}
							onAddAssertion={(assertion) => setSelectedDraft({ ...selectedDraft, assertions: [...selectedDraft.assertions, assertion] })}
							onSave={() => void saveDraft({})}
							onReview={() => void review()}
							onEnable={() => void enable()}
							onRun={() => void runCase()}
						/>
					)}
				</Surface>
			</div>
		</div>
	);
}

function DraftEditor({
	draft,
	busy,
	run,
	onExpectedChange,
	onAssertionChange,
	onApplySuggestions,
	onRemoveAssertion,
	onAddAssertion,
	onSave,
	onReview,
	onEnable,
	onRun,
}: {
	draft: FeedbackDraft;
	busy: boolean;
	run: EvaluationRun | null;
	onExpectedChange: (value: string) => void;
	onAssertionChange: (index: number, expected: string) => void;
	onApplySuggestions: () => void;
	onRemoveAssertion: (index: number) => void;
	onAddAssertion: (assertion: FeedbackAssertion) => void;
	onSave: () => void;
	onReview: () => void;
	onEnable: () => void;
	onRun: () => void;
}) {
	return (
		<div className="space-y-4">
			<div>
				<div className="flex flex-wrap items-center gap-1.5">
					<Badge variant="outline">{STATUS_LABELS[draft.status] ?? draft.status}</Badge>
					<Badge variant="secondary">{FEEDBACK_TYPE_LABELS[draft.feedbackType] ?? draft.feedbackType}</Badge>
					{draft.caseId && <Badge variant="outline" className="font-mono">{draft.caseId}</Badge>}
				</div>
				<h3 className="mt-2 text-[16px] font-semibold text-[#1c3048]">{draft.name}</h3>
				<div className="mt-1 font-mono text-[10px] text-[#8aa2ba]">反馈 {draft.feedbackId} · 任务 {draft.snapshot.taskId}</div>
			</div>
			<div className="rounded-lg border border-[#e6eef7] bg-white/50 px-3 py-2 text-xs text-[#5a708c]">
				<div className="font-medium text-[#33506b]">反馈快照（不是标准答案）</div>
				<p className="mt-1 line-clamp-2">{draft.snapshot.summary || '无摘要'}</p>
				<div className="mt-1 flex flex-wrap gap-2">
					<span>风险订单 {draft.snapshot.atRiskOrderCount}</span>
					<span>高风险 {draft.snapshot.highRiskOrderCount}</span>
					<span>dataGap {draft.snapshot.dataGapCount}</span>
					<span>来源 {draft.snapshot.narrativeSource}</span>
				</div>
				{draft.snapshot.dataGaps.length > 0 && <p className="mt-1 text-[#7a5a22]">数据缺口：{draft.snapshot.dataGaps.join('；')}</p>}
			</div>
			<div className="space-y-1">
				<Label>预期表现（人工填写）</Label>
				<Textarea rows={3} maxLength={2000} value={draft.expectedBehavior} disabled={draft.status === 'ENABLED'} onChange={(event) => onExpectedChange(event.target.value)} placeholder="例如：任务完成，风险订单数保持为 3；不能把用户反馈直接当作标准答案。" />
			</div>
			<div className="space-y-2">
				<div className="flex flex-wrap items-center justify-between gap-2">
					<div className="text-xs font-medium text-[#33506b]">可执行断言（{draft.assertions.length}）</div>
					<Button size="sm" variant="outline" disabled={draft.status === 'ENABLED' || draft.suggestedAssertions.length === 0} onClick={onApplySuggestions}>
						应用 Java 快照建议（需人工确认）
					</Button>
				</div>
				{draft.assertions.length === 0 ? <p className="text-xs text-[#5a708c]">尚未填写断言；草案不能直接审核或启用。</p> : (
					<div className="space-y-1.5">
						{draft.assertions.map((assertion, index) => (
							<div key={`${assertion.id}-${index}`} className="flex flex-wrap items-center gap-2 rounded-lg border border-[#e6eef7] bg-white/45 px-2.5 py-1.5">
								<Badge variant="outline">{ASSERTION_TYPE_LABELS[assertion.type] ?? assertion.type}</Badge>
								{assertion.target && <span className="font-mono text-[11px] text-[#5a708c]">{assertion.target}</span>}
								<Input className="min-w-[120px] flex-1" value={assertion.expected} disabled={draft.status === 'ENABLED'} onChange={(event) => onAssertionChange(index, event.target.value)} />
								<Button size="sm" variant="ghost" disabled={draft.status === 'ENABLED'} onClick={() => onRemoveAssertion(index)}>移除</Button>
							</div>
						))}
					</div>
				)}
				<AddAssertion onAdd={onAddAssertion} disabled={draft.status === 'ENABLED'} />
			</div>
			<div className="flex flex-wrap gap-2">
				<Button size="sm" variant="outline" disabled={busy || draft.status === 'ENABLED'} onClick={onSave}>保存草案</Button>
				<Button size="sm" variant="outline" disabled={busy || draft.status === 'ENABLED'} onClick={onReview}><ShieldCheck className="mr-1 h-4 w-4" />提交审核</Button>
				<Button size="sm" disabled={busy || draft.status !== 'READY_FOR_REVIEW'} onClick={onEnable}><CheckCircle2 className="mr-1 h-4 w-4" />启用评测案例</Button>
				<Button size="sm" variant="outline" disabled={busy || !draft.caseId} onClick={onRun}><Play className="mr-1 h-4 w-4" />运行该案例</Button>
			</div>
			{draft.status === 'ENABLED' && (
				<p className="text-xs text-[#428766]">已启用；只有此时才会出现在评测中心的正式案例目录中。运行结果仍通过既有 Evaluation Center 持久化。</p>
			)}
			{run && (
				<div className="rounded-lg border border-[#dbe6f2] bg-[#f8fbfe] px-3 py-2 text-xs text-[#33506b]">
					<div className="font-medium">评测运行 {run.runId}</div>
					<div className="mt-1">{run.passed} 通过 / {run.failed} 失败 / {run.undetermined} 无法判定</div>
					<Link href={`/agent-evaluation?runId=${encodeURIComponent(run.runId)}`} className="mt-1 inline-block font-medium text-[#35629d] hover:underline">查看运行详情</Link>
				</div>
			)}
			<p className="border-t border-[#e6eef7] pt-2 text-[11px] text-[#7a8da1]">「应用 Java 快照建议」只复制 Java 规则快照中的可计算值供人工确认，不会自动采用用户评价，也不会自动审核。</p>
		</div>
	);
}

function AddAssertion({ onAdd, disabled }: { onAdd: (assertion: FeedbackAssertion) => void; disabled: boolean }) {
	const [type, setType] = useState('TASK_STATUS');
	const [target, setTarget] = useState('');
	const [expected, setExpected] = useState('');
	return (
		<div className="flex flex-wrap items-end gap-1.5 rounded-lg border border-dashed border-[#dfe8f1] p-2">
			<Select items={ASSERTION_TYPE_LABELS} value={type} onValueChange={(value) => setType(value ?? 'TASK_STATUS')} disabled={disabled}>
				<SelectTrigger className="w-[145px]"><SelectValue /></SelectTrigger>
				<SelectContent>{Object.entries(ASSERTION_TYPE_LABELS).map(([value, label]) => <SelectItem key={value} value={value}>{label}</SelectItem>)}</SelectContent>
			</Select>
			<Input className="w-[110px]" value={target} onChange={(event) => setTarget(event.target.value)} placeholder="订单 ID（如需）" disabled={disabled} />
			<Input className="w-[110px]" value={expected} onChange={(event) => setExpected(event.target.value)} placeholder="预期值" disabled={disabled} />
			<Button size="sm" variant="outline" disabled={disabled || !expected.trim()} onClick={() => {
				onAdd({ id: `manual-${Date.now()}`, type: type as FeedbackAssertion['type'], target: target.trim() || null, expected: expected.trim(), description: `${type}${target ? `:${target}` : ''}` });
				setExpected('');
			}}>添加断言</Button>
		</div>
	);
}
