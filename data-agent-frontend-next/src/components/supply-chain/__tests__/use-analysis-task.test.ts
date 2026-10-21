// @vitest-environment jsdom
import { act, createElement } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fetchTask, fetchTaskEvents } from '@/lib/api/supplyChain';
import { useAnalysisTask } from '@/components/supply-chain/use-analysis-task';

vi.mock('@/lib/api/supplyChain', () => ({
	fetchTask: vi.fn(),
	fetchTaskEvents: vi.fn(),
	submitTask: vi.fn(),
}));

function Probe({ taskId }: { taskId: string }) {
	const state = useAnalysisTask(taskId);
	return createElement('div', null, state.error ?? '');
}

describe('useAnalysisTask polling lifecycle', () => {
	let root: Root | null = null;

	beforeEach(() => {
		(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
		vi.useFakeTimers();
		vi.mocked(fetchTask).mockReset();
		vi.mocked(fetchTaskEvents).mockReset().mockResolvedValue([]);
	});

	afterEach(() => {
		act(() => root?.unmount());
		root = null;
		vi.useRealTimers();
	});

	async function render(taskId: string) {
		const container = document.createElement('div');
		root = createRoot(container);
		await act(async () => {
			root!.render(createElement(Probe, { taskId }));
			await Promise.resolve();
		});
		await act(async () => {
			await vi.advanceTimersByTimeAsync(0);
		});
		return container;
	}

	it('stops scheduling polls after a 404 and keeps the friendly error', async () => {
		vi.mocked(fetchTask)
			.mockResolvedValueOnce({
				taskId: 'task-1', status: 'RUNNING', currentStep: 'QUERYING_ORDERS',
				validationAttempts: 0, error: null, updatedAt: '2026-10-21T00:00:00Z',
			})
			.mockRejectedValueOnce({ status: 404, message: 'HTTP 404' });

		const container = await render('task-1');
		expect(fetchTask).toHaveBeenCalledTimes(2);
		expect(container.textContent).toContain('任务不存在或已被清理');

		await act(async () => {
			await vi.advanceTimersByTimeAsync(10_000);
		});
		expect(fetchTask).toHaveBeenCalledTimes(2);
	});

	it('retries transient failures with backoff instead of stopping', async () => {
		vi.mocked(fetchTask)
			.mockResolvedValueOnce({
				taskId: 'task-2', status: 'RUNNING', currentStep: 'QUERYING_ORDERS',
				validationAttempts: 0, error: null, updatedAt: '2026-10-21T00:00:00Z',
			})
			.mockRejectedValue({ status: 500, message: 'HTTP 500' });

		await render('task-2');
		expect(fetchTask).toHaveBeenCalledTimes(2);
		await act(async () => {
			await vi.advanceTimersByTimeAsync(1_800);
		});
		expect(fetchTask).toHaveBeenCalledTimes(3);
	});

	it('clears the pending timer, aborts the active request and stops after unmount', async () => {
		const signals: Array<AbortSignal | undefined> = [];
		vi.mocked(fetchTask).mockImplementation(async (_id, options) => {
			signals.push(options?.signal);
			return {
				taskId: 'task-3', status: 'RUNNING', currentStep: 'QUERYING_ORDERS',
				validationAttempts: 0, error: null, updatedAt: '2026-10-21T00:00:00Z',
			};
		});

		await render('task-3');
		const callsBeforeUnmount = vi.mocked(fetchTask).mock.calls.length;
		expect(callsBeforeUnmount).toBeGreaterThan(0);
		const activeSignal = signals.at(-1);
		expect(activeSignal?.aborted).toBe(false);
		act(() => root?.unmount());
		root = null;

		expect(activeSignal?.aborted).toBe(true);
		await act(async () => {
			await vi.advanceTimersByTimeAsync(10_000);
		});
		expect(fetchTask).toHaveBeenCalledTimes(callsBeforeUnmount);
	});
});
