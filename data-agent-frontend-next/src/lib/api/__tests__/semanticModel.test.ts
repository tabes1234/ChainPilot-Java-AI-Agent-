import { afterEach, describe, expect, it, vi } from 'vitest';
import { batchDeleteSemanticModels, fetchSemanticModels } from '@/lib/api/semanticModel';
import { buildStreamUrl } from '@/lib/api/chat';

afterEach(() => {
	vi.unstubAllGlobals();
});

function jsonResponse(body: unknown, status = 200) {
	return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
}

describe('semantic models', () => {
	it('sends agentId and keyword together as the backend expects', async () => {
		let url = '';
		vi.stubGlobal(
			'fetch',
			vi.fn(async (input: string) => {
				url = input;
				return jsonResponse({ success: true, data: [] });
			}),
		);

		await fetchSemanticModels(3, '订单');
		expect(url).toContain('/api/semantic-model?');
		expect(url).toContain('agentId=3');
		expect(url).toContain(`keyword=${encodeURIComponent('订单')}`);
	});

	it('omits agentId when only a keyword is provided (global search)', async () => {
		let url = '';
		vi.stubGlobal(
			'fetch',
			vi.fn(async (input: string) => {
				url = input;
				return jsonResponse({ success: true, data: [] });
			}),
		);

		await fetchSemanticModels(undefined, 'abc');
		expect(url).not.toContain('agentId');
		expect(url).toContain('keyword=abc');
	});

	it('batch delete uses DELETE with a bare id array body', async () => {
		let method = '';
		let body: unknown = null;
		vi.stubGlobal(
			'fetch',
			vi.fn(async (_input: string, init?: RequestInit) => {
				method = init?.method ?? '';
				body = JSON.parse(String(init?.body));
				return jsonResponse({ success: true, data: true });
			}),
		);

		await batchDeleteSemanticModels([1, 2]);
		expect(method).toBe('DELETE');
		expect(body).toEqual([1, 2]);
	});
});

describe('chat stream url', () => {
	it('encodes conversation and feedback parameters', () => {
		const url = buildStreamUrl({
			agentId: 5,
			conversationId: 'session-1',
			query: '今天订单多少？',
			threadId: 'thread-9',
			humanFeedback: true,
			humanFeedbackContent: '同意',
			rejectedPlan: false,
		});

		expect(url).toContain('/api/stream/search?');
		expect(url).toContain('agentId=5');
		expect(url).toContain('conversationId=session-1');
		expect(url).toContain(`query=${encodeURIComponent('今天订单多少？')}`);
		expect(url).toContain('threadId=thread-9');
		expect(url).toContain('humanFeedback=true');
		expect(url).toContain(`humanFeedbackContent=${encodeURIComponent('同意')}`);
		expect(url).toContain('rejectedPlan=false');
	});
});
