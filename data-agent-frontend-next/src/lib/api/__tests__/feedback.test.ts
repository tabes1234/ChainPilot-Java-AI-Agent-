import { readFileSync } from 'node:fs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
	createFeedbackDraft,
	enableFeedbackDraft,
	reviewFeedbackDraft,
	submitFeedback,
	updateFeedbackDraft,
} from '@/lib/api/feedback';

afterEach(() => vi.unstubAllGlobals());

const panel = readFileSync('src/app/agent-evaluation/FeedbackPanel.tsx', 'utf8');
const dialog = readFileSync('src/components/supply-chain/feedback-dialog.tsx', 'utf8');
const report = readFileSync('src/components/supply-chain/report-view.tsx', 'utf8');
const decision = readFileSync('src/components/supply-chain/decision-view.tsx', 'utf8');

describe('feedback and draft API', () => {
	it('posts feedback and follows the review/enable lifecycle endpoints', async () => {
		const requests: { url: string; method: string; body: string }[] = [];
		vi.stubGlobal('fetch', vi.fn(async (input: string, init?: RequestInit) => {
			requests.push({ url: String(input), method: String(init?.method), body: String(init?.body ?? '') });
			return new Response(JSON.stringify({ success: true, message: 'ok', data: {
				draftId: 'draft-1', feedbackId: 'feedback-1', caseId: 'FB-1', status: 'ENABLED',
				name: 'draft', expectedBehavior: 'expected', assertions: [], suggestedAssertions: [],
				snapshot: {}, feedbackType: 'OTHER',
			} }), { status: 200, headers: { 'content-type': 'application/json' } });
		}));

		await submitFeedback({ taskId: 'task-1', feedbackType: 'INSUFFICIENT_EVIDENCE', comment: '证据不足' });
		await createFeedbackDraft('feedback-1');
		await updateFeedbackDraft('draft-1', { expectedBehavior: 'expected', assertions: [] });
		await reviewFeedbackDraft('draft-1');
		await enableFeedbackDraft('draft-1');

		expect(requests[0].url).toContain('/api/agent-evaluation/feedback');
		expect(requests[0].body).toContain('INSUFFICIENT_EVIDENCE');
		expect(requests[1].url).toContain('/feedback/feedback-1/drafts');
		expect(requests[2].method).toBe('PUT');
		expect(requests[3].url).toContain('/review');
		expect(requests[4].url).toContain('/enable');
	});
});

describe('feedback UI contract', () => {
	it('keeps suggestions separate from expected answers and requires review before enable', () => {
		expect(dialog).toContain('提交反馈');
		expect(dialog).toContain('不会自动生成通过条件');
		expect(report).toContain('FeedbackDialog');
		expect(decision).toContain('FeedbackDialog');
		expect(panel).toContain('应用 Java 快照建议');
		expect(panel).toContain('提交审核');
		expect(panel).toContain('启用评测案例');
		expect(panel).toContain('反馈快照（不是标准答案）');
		expect(panel).toContain('草案初始不包含预期结果或断言');
		expect(panel).toContain('不会自动采用用户评价');
	});
});
