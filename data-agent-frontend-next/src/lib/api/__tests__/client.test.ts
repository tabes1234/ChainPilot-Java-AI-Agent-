import { afterEach, describe, expect, it, vi } from 'vitest';
import { api, assertSuccess, errorMessage, friendlyErrorMessage, unwrap } from '@/lib/api/client';

afterEach(() => {
	vi.unstubAllGlobals();
	vi.useRealTimers();
});

describe('envelope helpers', () => {
	it('returns data when success is true', () => {
		expect(unwrap({ success: true, data: { id: 1 } }, 'fallback')).toEqual({ id: 1 });
	});

	it('throws the server message when success is false on HTTP 200', () => {
		expect(() => unwrap({ success: false, message: '业务失败' }, 'fallback')).toThrow('业务失败');
	});

	it('assertSuccess throws for failed envelopes', () => {
		expect(() => assertSuccess({ success: false, message: '删除失败' }, 'fallback')).toThrow('删除失败');
		expect(() => assertSuccess({ success: true }, 'fallback')).not.toThrow();
	});

	it('formats API errors with detail and plain errors', () => {
		expect(errorMessage({ status: 500, message: 'HTTP 500', detail: '服务器内部错误' })).toBe('HTTP 500：服务器内部错误');
		expect(errorMessage(new Error('boom'))).toBe('boom');
		expect(errorMessage(undefined, '兜底')).toBe('兜底');
	});

	it('replaces status-level failures with friendly copy while keeping business messages', () => {
		const notFound = { status: 404, message: 'Unknown disposal plan: missing-plan' };
		expect(friendlyErrorMessage(notFound, '加载方案详情失败', { 404: '方案不存在或已被清理' })).toBe('方案不存在或已被清理');
		expect(friendlyErrorMessage({ ...notFound, status: 400 }, '加载方案详情失败', { 404: '方案不存在或已被清理' }))
			.toBe('Unknown disposal plan: missing-plan');
		expect(friendlyErrorMessage({ status: 400, message: 'HTTP 400', detail: 'title is required' }, '保存失败'))
			.toBe('HTTP 400：title is required');
		expect(friendlyErrorMessage({ status: 500, message: 'HTTP 500', detail: '' }, '加载失败')).toBe('加载失败');
		expect(friendlyErrorMessage({ status: 409, message: 'HTTP 409', detail: '' }, '加载失败', { 409: '任务类型不匹配' }))
			.toBe('任务类型不匹配');
		expect(friendlyErrorMessage(undefined, '加载失败')).toBe('加载失败');
	});
});

describe('api client', () => {
	it('surfaces HTTP failures with the backend message as detail', async () => {
		vi.stubGlobal(
			'fetch',
			vi.fn(async () => new Response(JSON.stringify({ success: false, message: '资源不存在' }), {
				status: 404,
				headers: { 'content-type': 'application/json' },
			})),
		);

		await expect(api.get('/api/demo')).rejects.toMatchObject({ status: 404, detail: '资源不存在' });
	});

	it('does not set a JSON content-type for FormData bodies', async () => {
		const fetchMock = vi.fn(async (_url: string, init?: RequestInit) => {
			expect(init?.body instanceof FormData).toBe(true);
			const headers = new Headers(init?.headers);
			expect(headers.get('content-type')).toBeNull();
			return new Response(JSON.stringify({ success: true }), {
				status: 200,
				headers: { 'content-type': 'application/json' },
			});
		});
		vi.stubGlobal('fetch', fetchMock);

		const form = new FormData();
		form.append('a', '1');
		await api.postForm('/api/demo', form);
		expect(fetchMock).toHaveBeenCalledTimes(1);
	});

	it('serializes JSON bodies and sets the JSON content-type', async () => {
		const fetchMock = vi.fn(async (_url: string, init?: RequestInit) => {
			expect(new Headers(init?.headers).get('content-type')).toBe('application/json');
			expect(init?.body).toBe('{"q":1}');
			return new Response(JSON.stringify({ success: true }), {
				status: 200,
				headers: { 'content-type': 'application/json' },
			});
		});
		vi.stubGlobal('fetch', fetchMock);

		await api.post('/api/demo', { q: 1 });
	});

	it('handles empty, malformed and non-JSON success responses', async () => {
		vi.stubGlobal('fetch', vi.fn(async () => new Response('', {
			status: 200,
			headers: { 'content-type': 'application/json' },
		})));
		await expect(api.get('/api/empty')).resolves.toBeUndefined();

		vi.stubGlobal('fetch', vi.fn(async () => new Response('not-json', {
			status: 200,
			headers: { 'content-type': 'application/json' },
		})));
		await expect(api.get('/api/malformed')).rejects.toMatchObject({ status: 200, message: '响应不是有效的 JSON' });

		vi.stubGlobal('fetch', vi.fn(async () => new Response('plain text', {
			status: 200,
			headers: { 'content-type': 'text/plain' },
		})));
		await expect(api.get('/api/text')).resolves.toBe('plain text');
	});

	it('keeps status and empty detail for empty/non-JSON error bodies', async () => {
		vi.stubGlobal('fetch', vi.fn(async () => new Response('', {
			status: 500,
			headers: { 'content-type': 'application/json' },
		})));
		await expect(api.get('/api/empty-error')).rejects.toMatchObject({ status: 500, detail: '' });

		vi.stubGlobal('fetch', vi.fn(async () => new Response('bad gateway', {
			status: 502,
			headers: { 'content-type': 'text/plain' },
		})));
		await expect(api.get('/api/text-error')).rejects.toMatchObject({ status: 502, detail: '' });
	});

	it('normalizes network failures to a stable Chinese message', async () => {
		vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('Failed to fetch'); }));
		await expect(api.get('/api/offline')).rejects.toMatchObject({
			status: 0,
			message: '网络错误，请检查后端服务是否可用',
		});
	});

	it('distinguishes request timeouts from caller cancellation', async () => {
		vi.useFakeTimers();
		vi.stubGlobal('fetch', vi.fn((_url: string, init?: RequestInit) => new Promise((_resolve, reject) => {
			init?.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')));
		})));

		const timedOut = api.get('/api/slow', { timeoutMs: 1000 });
		const timeoutAssertion = expect(timedOut).rejects.toMatchObject({ status: 0, message: '请求超时（1 秒）' });
		await vi.advanceTimersByTimeAsync(1000);
		await timeoutAssertion;

		const controller = new AbortController();
		const cancelled = api.get('/api/cancel', { timeoutMs: 5000, signal: controller.signal });
		const cancelAssertion = expect(cancelled).rejects.toMatchObject({ status: 0, message: '请求已取消' });
		controller.abort();
		await cancelAssertion;
	});
});
