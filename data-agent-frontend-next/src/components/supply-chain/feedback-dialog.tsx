'use client';

import { useState } from 'react';
import { MessageSquareText } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Textarea } from '@/components/ui/textarea';
import { errorMessage } from '@/lib/api/client';
import { FEEDBACK_TYPE_LABELS, submitFeedback, type FeedbackResultType, type FeedbackType } from '@/lib/api/feedback';

const FEEDBACK_ITEMS = Object.fromEntries(Object.entries(FEEDBACK_TYPE_LABELS).map(([value, label]) => [value, label]));

export function FeedbackDialog({
	taskId,
	resultType,
	proposalId,
	narrativeSource,
}: {
	taskId: string;
	resultType: FeedbackResultType;
	proposalId?: string;
	narrativeSource?: string;
}) {
	const [open, setOpen] = useState(false);
	const [type, setType] = useState<FeedbackType>('HELPFUL');
	const [comment, setComment] = useState('');
	const [busy, setBusy] = useState(false);
	const [submitted, setSubmitted] = useState(false);

	const submit = async () => {
		setBusy(true);
		try {
			await submitFeedback({ taskId, proposalId, feedbackType: type, comment: comment.trim() || undefined });
			setSubmitted(true);
			toast.success('反馈已提交', { description: '反馈不会自动成为标准答案，可在评测中心转为待审核案例草案。' });
		} catch (error) {
			toast.error('提交反馈失败', { description: errorMessage(error) });
		} finally {
			setBusy(false);
		}
	};

	return (
		<>
			<Button size="sm" variant="outline" onClick={() => { setOpen(true); setSubmitted(false); }}>
				<MessageSquareText className="mr-1 h-4 w-4" />
				提交反馈
			</Button>
			<Dialog open={open} onOpenChange={setOpen}>
				<DialogContent className="sm:max-w-md">
					<DialogHeader>
						<DialogTitle>评价本次结果</DialogTitle>
						<DialogDescription>
							反馈只关联当前任务及其实际展示结果；不会自动生成通过条件，也不会自动加入评测集。
						</DialogDescription>
					</DialogHeader>
					<div className="space-y-3">
						<div className="space-y-1">
							<Label>反馈类型</Label>
							<Select items={FEEDBACK_ITEMS} value={type} onValueChange={(value) => setType((value ?? 'HELPFUL') as FeedbackType)}>
								<SelectTrigger className="w-full"><SelectValue /></SelectTrigger>
								<SelectContent>
									{Object.entries(FEEDBACK_ITEMS).map(([value, label]) => <SelectItem key={value} value={value}>{label}</SelectItem>)}
								</SelectContent>
							</Select>
						</div>
						<div className="space-y-1">
							<Label>补充说明（可选，最多 1000 字）</Label>
							<Textarea rows={4} maxLength={1000} value={comment} onChange={(event) => setComment(event.target.value)} placeholder="请说明你看到的问题；不要填写无关的敏感信息。" />
							<div className="text-right text-[11px] text-[#8aa2ba]">{comment.length}/1000</div>
						</div>
						<div className="rounded-lg bg-[#f8fbfe] px-3 py-2 text-xs text-[#5a708c] ring-1 ring-[#e6eef7]">
							<div className="font-mono">{taskId}</div>
							<div className="mt-1">结果类型：{resultType === 'ANALYSIS' ? '风险分析报告' : '决策建议'}</div>
							{narrativeSource && <div>叙述来源：{narrativeSource}</div>}
						</div>
					</div>
					<DialogFooter>
						<Button variant="outline" onClick={() => setOpen(false)}>关闭</Button>
						<Button onClick={submit} disabled={busy || submitted}>
							{submitted ? '已提交' : busy ? '提交中…' : '提交反馈'}
						</Button>
					</DialogFooter>
				</DialogContent>
			</Dialog>
		</>
	);
}
