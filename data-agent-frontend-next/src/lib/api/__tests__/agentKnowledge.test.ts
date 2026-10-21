import { afterEach, describe, expect, it, vi } from 'vitest';
import { createAgentKnowledge, queryKnowledgePage } from '@/lib/api/agentKnowledge';

afterEach(() => {
	vi.unstubAllGlobals();
});

function jsonResponse(body: unknown, status = 200) {
	return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
}

describe('queryKnowledgePage', () => {
	it('posts the real DTO and maps the page envelope', async () => {
		const fetchMock = vi.fn(async (_url: string, init?: RequestInit) => {
			expect(init?.method).toBe('POST');
			expect(JSON.parse(String(init?.body))).toEqual({
				agentId: 2,
				title: '订单',
				type: 'QA',
				embeddingStatus: 'COMPLETED',
				pageNum: 2,
				pageSize: 10,
			});
			return jsonResponse({
				success: true,
				data: [{ id: 1, title: 'A', type: 'QA' }],
				total: 12,
				pageNum: 2,
				pageSize: 10,
				totalPages: 2,
			});
		});
		vi.stubGlobal('fetch', fetchMock);

		const page = await queryKnowledgePage(2, {
			title: '订单',
			type: 'QA',
			embeddingStatus: 'COMPLETED',
			pageNum: 2,
			pageSize: 10,
		});

		expect(page).toMatchObject({ total: 12, pageNum: 2, totalPages: 2, items: [{ id: 1, title: 'A', type: 'QA' }] });
	});

	it('throws the backend message when the envelope reports failure', async () => {
		vi.stubGlobal('fetch', vi.fn(async () => jsonResponse({ success: false, message: '分页查询失败' })));

		await expect(queryKnowledgePage(1, { pageNum: 1, pageSize: 10 })).rejects.toThrow('分页查询失败');
	});
});

describe('createAgentKnowledge', () => {
	it('sends a QA knowledge as multipart with the controller part names', async () => {
		let form: FormData | null = null;
		vi.stubGlobal(
			'fetch',
			vi.fn(async (_url: string, init?: RequestInit) => {
				form = init?.body as FormData;
				return jsonResponse({ success: true, data: { id: 9 } });
			}),
		);

		await createAgentKnowledge({
			agentId: 1,
			title: '标题',
			type: 'QA',
			question: '问题',
			content: '答案',
		});

		expect(form).not.toBeNull();
		const entries = Object.fromEntries((form as unknown as FormData).entries());
		expect(entries).toMatchObject({ agentId: '1', title: '标题', type: 'QA', question: '问题', content: '答案' });
	});

	it('requires a file for document knowledge before calling the backend', async () => {
		const fetchMock = vi.fn();
		vi.stubGlobal('fetch', fetchMock);

		await expect(createAgentKnowledge({ agentId: 1, title: '文档', type: 'DOCUMENT' })).rejects.toThrow('必须上传文件');
		expect(fetchMock).not.toHaveBeenCalled();
	});
});
