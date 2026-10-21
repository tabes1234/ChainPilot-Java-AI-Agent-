import { afterEach, describe, expect, it, vi } from 'vitest';
import { api, assertSuccess, errorMessage, unwrap } from '@/lib/api/client';

afterEach(() => {
	vi.unstubAllGlobals();
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
});
