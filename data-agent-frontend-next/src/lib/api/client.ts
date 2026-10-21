/**
 * Centralized API client for ChainPilot.
 * In the browser all calls are same-origin (Next rewrites /api and /uploads to the backend).
 * During tests or server-side usage the absolute backend URL is used.
 */

const isBrowser = typeof window !== 'undefined';

export const apiBaseUrl = isBrowser
	? process.env.NEXT_PUBLIC_API_BASE_URL || ''
	: process.env.CHAINPILOT_BACKEND_URL || 'http://localhost:8065';

export type ApiError = {
	status: number;
	message: string;
	code?: string;
	detail?: string;
};

export type ApiEnvelope<T> = {
	success: boolean;
	message?: string;
	code?: string | null;
	data?: T | null;
};

const DEFAULT_TIMEOUT_MS = 30_000;

export function isApiError(error: unknown): error is ApiError {
	return typeof error === 'object' && error !== null && 'status' in error && 'message' in error;
}

/** Human-readable message for any thrown value. Never contains request credentials. */
export function errorMessage(error: unknown, fallback = '请求失败，请稍后重试'): string {
	if (isApiError(error)) return error.detail ? `${error.message}：${error.detail}` : error.message;
	if (error instanceof Error) return error.message || fallback;
	if (typeof error === 'string' && error) return error;
	return fallback;
}

/** Keeps backend business messages, but replaces status-level errors with page-specific copy. */
export function friendlyErrorMessage(
	error: unknown,
	fallback: string,
	statusMessages: Partial<Record<number, string>> = {},
): string {
	if (isApiError(error)) {
		const message = statusMessages[error.status];
		if (message) return message;
	}
	const message = errorMessage(error, fallback);
	if (isApiError(error) && !error.detail && /^HTTP \d+$/.test(message)) return fallback;
	return message;
}

type RequestOptions = RequestInit & { timeoutMs?: number };

async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
	const { timeoutMs = DEFAULT_TIMEOUT_MS, signal, ...init } = options;
	const controller = new AbortController();
	const timer = setTimeout(() => controller.abort(), timeoutMs);
	const onAbort = () => controller.abort();
	signal?.addEventListener('abort', onAbort, { once: true });

	// FormData keeps the browser-generated multipart boundary; bodyless requests must not claim JSON.
	const isForm = init.body instanceof FormData;
	const headers: Record<string, string> = {
		...(init.body != null && !isForm ? { 'Content-Type': 'application/json' } : {}),
		...(init.headers as Record<string, string>),
	};

	try {
		const response = await fetch(`${apiBaseUrl}${path}`, { ...init, headers, signal: controller.signal });

		if (!response.ok) {
			let detail = '';
			let code: string | undefined;
			try {
				const body = await response.json();
				detail = body?.message || body?.error || '';
				code = body?.code || undefined;
			} catch {
				/* non-JSON error body */
			}
			throw { status: response.status, message: `HTTP ${response.status}`, code, detail } as ApiError;
		}

		const contentType = response.headers.get('content-type') ?? '';
		if (contentType.includes('application/json')) {
			const text = await response.text();
			if (!text.trim()) return undefined as T;
			try {
				return JSON.parse(text) as T;
			} catch {
				throw { status: response.status, message: '响应不是有效的 JSON' } as ApiError;
			}
		}
		return (await response.text()) as unknown as T;
	} catch (error) {
		if (typeof error === 'object' && error !== null && 'status' in error) throw error;
		if (error instanceof DOMException && error.name === 'AbortError') {
			if (signal?.aborted) throw { status: 0, message: '请求已取消' } as ApiError;
			throw { status: 0, message: `请求超时（${Math.round(timeoutMs / 1000)} 秒）` } as ApiError;
		}
		if (error instanceof TypeError) throw { status: 0, message: '网络错误，请检查后端服务是否可用' } as ApiError;
		throw {
			status: 0,
			message: error instanceof Error ? error.message : '网络错误，请检查后端服务是否可用',
		} as ApiError;
	} finally {
		clearTimeout(timer);
		signal?.removeEventListener('abort', onAbort);
	}
}

/** Unwraps an ApiResponse envelope; HTTP 200 with `success:false` becomes a thrown Error. */
export function unwrap<T>(response: ApiEnvelope<T> | null | undefined, fallback: string): T {
	if (!response || response.success !== true) {
		throw new Error(response?.message || fallback);
	}
	return response.data as T;
}

/** Enforces `success:true` for endpoints whose payload is irrelevant. */
export function assertSuccess(response: ApiEnvelope<unknown> | null | undefined, fallback: string): void {
	if (!response || response.success !== true) {
		throw new Error(response?.message || fallback);
	}
}

export const api = {
	get: <T>(path: string, options?: Omit<RequestOptions, 'method' | 'body'>) =>
		request<T>(path, { ...options, method: 'GET' }),
	post: <T>(path: string, body?: unknown, options?: Omit<RequestOptions, 'method' | 'body'>) =>
		request<T>(path, { ...options, method: 'POST', body: body === undefined ? undefined : JSON.stringify(body) }),
	postForm: <T>(path: string, body: FormData, options?: Omit<RequestOptions, 'method' | 'body'>) =>
		request<T>(path, { ...options, method: 'POST', body }),
	put: <T>(path: string, body?: unknown, options?: Omit<RequestOptions, 'method' | 'body'>) =>
		request<T>(path, { ...options, method: 'PUT', body: body === undefined ? undefined : JSON.stringify(body) }),
	patch: <T>(path: string, body?: unknown, options?: Omit<RequestOptions, 'method' | 'body'>) =>
		request<T>(path, { ...options, method: 'PATCH', body: body === undefined ? undefined : JSON.stringify(body) }),
	delete: <T>(path: string, options?: Omit<RequestOptions, 'method' | 'body'> & { body?: unknown }) =>
		request<T>(path, {
			...options,
			method: 'DELETE',
			body: options?.body === undefined ? undefined : JSON.stringify(options.body),
		}),
	/** Raw fetch for SSE / streaming endpoints and binary downloads. */
	raw: (path: string, init?: RequestInit) => fetch(`${apiBaseUrl}${path}`, init),
};
