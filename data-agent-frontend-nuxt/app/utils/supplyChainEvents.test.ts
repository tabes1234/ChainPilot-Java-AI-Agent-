/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { describe, expect, it, vi } from 'vitest';
import { useSupplyChainEvents } from '../composables/useSupplyChainEvents';
import {
	eventsEndpoint,
	toSupplyChainTaskEvents,
	type SupplyChainTaskEvent,
} from '../services/supplyChainEvents';

function event(overrides: Partial<SupplyChainTaskEvent> = {}): SupplyChainTaskEvent {
	return {
		eventId: 'event-1',
		taskId: 'task-1',
		sequence: 1,
		type: 'TASK_QUEUED',
		stage: 'QUEUE',
		status: 'QUEUED',
		occurredAt: '2026-10-19T10:00:00Z',
		durationMs: null,
		summary: 'task accepted',
		errorType: null,
		...overrides,
	};
}

function deferred<T>() {
	let resolve!: (value: T) => void;
	let reject!: (reason?: unknown) => void;
	const promise = new Promise<T>((res, rej) => {
		resolve = res;
		reject = rej;
	});
	return { promise, resolve, reject };
}

describe('supply chain event service and state', () => {
	it('keeps the backend event payload untouched and accepts empty arrays', () => {
		const payload = [event(), event({ eventId: 'event-2', sequence: 2 })];
		expect(toSupplyChainTaskEvents(payload)).toBe(payload);
		expect(toSupplyChainTaskEvents([])).toEqual([]);
		expect(() => toSupplyChainTaskEvents({ events: [] })).toThrow('Unexpected execution event response.');
		expect(() => toSupplyChainTaskEvents(null)).toThrow();
	});

	it('builds an encoded per-task events endpoint', () => {
		expect(eventsEndpoint('task-1')).toBe('/api/supply-chain/analysis/tasks/task-1/events');
		expect(eventsEndpoint('task/with space')).toBe(
			'/api/supply-chain/analysis/tasks/task%2Fwith%20space/events',
		);
	});

	it('loads events for one task and sorts them by sequence', async () => {
		const fetcher = vi.fn(async () => [
			event({ eventId: 'third', sequence: 3, occurredAt: '2026-10-19T09:00:00Z' }),
			event({ eventId: 'first', sequence: 1, occurredAt: '2026-10-19T12:00:00Z' }),
			event({ eventId: 'second', sequence: 2, occurredAt: '2026-10-19T10:00:00Z' }),
		]);
		const store = useSupplyChainEvents(fetcher);

		await store.load('task-1');

		expect(fetcher).toHaveBeenCalledWith('task-1');
		expect(store.events.value.map((item) => item.sequence)).toEqual([1, 2, 3]);
		expect(store.taskId.value).toBe('task-1');
		expect(store.loading.value).toBe(false);
		expect(store.error.value).toBe('');
	});

	it('treats a successful empty event array as an empty trail rather than an error', async () => {
		const store = useSupplyChainEvents(vi.fn(async () => []));

		await store.load('task-without-events');

		expect(store.events.value).toEqual([]);
		expect(store.loading.value).toBe(false);
		expect(store.error.value).toBe('');
		expect(store.taskId.value).toBe('task-without-events');
	});

	it('shows a friendly failure without raw exception text and recovers on retry', async () => {
		const fetcher = vi
			.fn<(taskId: string) => Promise<SupplyChainTaskEvent[]>>()
			.mockRejectedValueOnce(new Error('connect ECONNREFUSED internal-host:8065 stack trace'))
			.mockResolvedValueOnce([event()]);
		const store = useSupplyChainEvents(fetcher);

		await store.load('task-1');

		expect(store.events.value).toEqual([]);
		expect(store.loading.value).toBe(false);
		expect(store.error.value).toBe('无法加载执行轨迹，请检查后端服务后重试。');
		expect(store.error.value).not.toContain('ECONNREFUSED');

		await store.retry();

		expect(fetcher).toHaveBeenLastCalledWith('task-1');
		expect(store.error.value).toBe('');
		expect(store.events.value).toHaveLength(1);
	});

	it('uses a specific friendly message for missing tasks', async () => {
		const store = useSupplyChainEvents(vi.fn(async () => Promise.reject({ statusCode: 404 })));

		await store.load('missing-task');

		expect(store.error.value).toBe('任务不存在或已被清理，无法读取执行轨迹。');
		expect(store.events.value).toEqual([]);
	});

	it('prevents duplicate requests for the same task while loading', async () => {
		const pending = deferred<SupplyChainTaskEvent[]>();
		const fetcher = vi.fn(() => pending.promise);
		const store = useSupplyChainEvents(fetcher);

		const firstLoad = store.load('task-1');
		expect(store.loading.value).toBe(true);
		await store.load('task-1');
		expect(fetcher).toHaveBeenCalledTimes(1);

		pending.resolve([event()]);
		await firstLoad;

		expect(store.loading.value).toBe(false);
		expect(store.events.value).toHaveLength(1);
	});

	it('never lets a late response from task A overwrite task B', async () => {
		const taskA = deferred<SupplyChainTaskEvent[]>();
		const taskB = deferred<SupplyChainTaskEvent[]>();
		const fetcher = vi
			.fn<(taskId: string) => Promise<SupplyChainTaskEvent[]>>()
			.mockReturnValueOnce(taskA.promise)
			.mockReturnValueOnce(taskB.promise);
		const store = useSupplyChainEvents(fetcher);

		const loadA = store.load('task-a');
		const loadB = store.load('task-b');

		taskB.resolve([event({ eventId: 'b-1', taskId: 'task-b', sequence: 1 })]);
		await loadB;
		taskA.resolve([event({ eventId: 'a-1', taskId: 'task-a', sequence: 1 })]);
		await loadA;

		expect(store.taskId.value).toBe('task-b');
		expect(store.events.value.map((item) => item.taskId)).toEqual(['task-b']);
		expect(store.loading.value).toBe(false);
		expect(store.error.value).toBe('');
	});

	it('clears the previous task immediately when switching tasks', async () => {
		const first = vi.fn(async () => [event({ taskId: 'task-a' })]);
		const pending = deferred<SupplyChainTaskEvent[]>();
		const fetcher = vi
			.fn<(taskId: string) => Promise<SupplyChainTaskEvent[]>>()
			.mockImplementationOnce(first)
			.mockReturnValueOnce(pending.promise);
		const store = useSupplyChainEvents(fetcher);

		await store.load('task-a');
		const loadB = store.load('task-b');

		expect(store.events.value).toEqual([]);
		expect(store.taskId.value).toBe('task-b');
		expect(store.loading.value).toBe(true);

		pending.resolve([event({ taskId: 'task-b' })]);
		await loadB;
		expect(store.events.value.map((item) => item.taskId)).toEqual(['task-b']);
	});

	it('keeps the current events visible while refreshing the same task', async () => {
		const refresh = deferred<SupplyChainTaskEvent[]>();
		const fetcher = vi
			.fn<(taskId: string) => Promise<SupplyChainTaskEvent[]>>()
			.mockResolvedValueOnce([event({ eventId: 'old', sequence: 1 })])
			.mockReturnValueOnce(refresh.promise);
		const store = useSupplyChainEvents(fetcher);

		await store.load('task-1');
		const refreshLoad = store.load('task-1');

		expect(store.loading.value).toBe(true);
		expect(store.events.value.map((item) => item.eventId)).toEqual(['old']);

		refresh.resolve([
			event({ eventId: 'new-2', sequence: 2 }),
			event({ eventId: 'new-1', sequence: 1 }),
		]);
		await refreshLoad;

		expect(store.loading.value).toBe(false);
		expect(store.events.value.map((item) => item.eventId)).toEqual(['new-1', 'new-2']);
	});

	it('discards an in-flight response after the trail is cleared', async () => {
		const pending = deferred<SupplyChainTaskEvent[]>();
		const store = useSupplyChainEvents(vi.fn(() => pending.promise));

		const load = store.load('task-1');
		store.clear();

		expect(store.events.value).toEqual([]);
		expect(store.loading.value).toBe(false);
		expect(store.taskId.value).toBe('');
		expect(store.error.value).toBe('');

		pending.resolve([event()]);
		await load;

		expect(store.events.value).toEqual([]);
		expect(store.taskId.value).toBe('');
		expect(store.loading.value).toBe(false);
	});

	it('does not request anything for a blank task id', async () => {
		const fetcher = vi.fn(async () => [event()]);
		const store = useSupplyChainEvents(fetcher);

		await store.load('   ');

		expect(fetcher).not.toHaveBeenCalled();
		expect(store.events.value).toEqual([]);
		expect(store.taskId.value).toBe('');
		expect(store.loading.value).toBe(false);
	});
});
