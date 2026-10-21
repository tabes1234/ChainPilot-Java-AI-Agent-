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
import { useSupplyChainHistory } from '../composables/useSupplyChainHistory';
import {
	buildHistoryQuery,
	HISTORY_PAGE_SIZE_OPTIONS,
	HISTORY_STATUS_OPTIONS,
	toHistoryPage,
	type SupplyChainHistoryPage,
	type SupplyChainHistoryQuery,
	type SupplyChainTaskSummary,
} from '../services/supplyChainHistory';

function task(overrides: Partial<SupplyChainTaskSummary> = {}): SupplyChainTaskSummary {
	return {
		taskId: 'task-1',
		status: 'COMPLETED',
		currentStep: 'DONE',
		validationAttempts: 0,
		error: null,
		question: '分析今天可能延期的订单',
		updatedAt: '2026-10-19T12:00:00Z',
		reportAvailable: true,
		...overrides,
	};
}

function page(
	items: SupplyChainTaskSummary[],
	meta: Partial<Omit<SupplyChainHistoryPage, 'items'>> = {},
): SupplyChainHistoryPage {
	return {
		items,
		page: 1,
		pageSize: 8,
		totalElements: items.length,
		totalPages: items.length > 0 ? 1 : 0,
		...meta,
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

describe('supply chain history', () => {
	it('loads the default first page and keeps the existing status wording', async () => {
		const requests: SupplyChainHistoryQuery[] = [];
		const fetcher = vi.fn(async (query: SupplyChainHistoryQuery) => {
			requests.push(query);
			return page([task()]);
		});
		const history = useSupplyChainHistory(fetcher);

		await history.load();

		expect(requests).toEqual([{ status: 'ALL', page: 1, pageSize: 8 }]);
		expect(history.tasks.value).toHaveLength(1);
		expect(history.loading.value).toBe(false);
		expect(history.error.value).toBe('');
		expect(history.totalElements.value).toBe(1);
		expect(HISTORY_STATUS_OPTIONS.map((option) => option.title)).toEqual([
			'全部状态',
			'排队中',
			'执行中',
			'已完成',
			'失败',
		]);
	});

	it('builds page/pageSize parameters without the legacy limit parameter', () => {
		expect(buildHistoryQuery({ status: 'ALL', page: 2, pageSize: 5 })).toEqual({
			page: 2,
			pageSize: 5,
		});
		expect(buildHistoryQuery({ status: 'FAILED', page: 1, pageSize: 10 })).toEqual({
			status: 'FAILED',
			page: 1,
			pageSize: 10,
		});
	});

	it('resets to the first page when the status filter changes', async () => {
		const requests: SupplyChainHistoryQuery[] = [];
		const fetcher = vi.fn(async (query: SupplyChainHistoryQuery) => {
			requests.push(query);
			return page([task()], {
				page: query.page,
				pageSize: query.pageSize,
				totalElements: 12,
				totalPages: 3,
			});
		});
		const history = useSupplyChainHistory(fetcher);

		await history.load();
		await history.nextPage();
		expect(requests.at(-1)).toEqual({ status: 'ALL', page: 2, pageSize: 8 });

		await history.setStatus('COMPLETED');

		expect(requests.at(-1)).toEqual({ status: 'COMPLETED', page: 1, pageSize: 8 });
		expect(history.status.value).toBe('COMPLETED');
		expect(history.page.value).toBe(1);
	});

	it('requests the selected page and page size', async () => {
		const requests: SupplyChainHistoryQuery[] = [];
		const fetcher = vi.fn(async (query: SupplyChainHistoryQuery) => {
			requests.push(query);
			return page([task()], {
				page: query.page,
				pageSize: query.pageSize,
				totalElements: 23,
				totalPages: 5,
			});
		});
		const history = useSupplyChainHistory(fetcher);

		await history.load();
		await history.nextPage();
		expect(requests.at(-1)).toEqual({ status: 'ALL', page: 2, pageSize: 8 });

		await history.setPageSize(20);

		expect(requests.at(-1)).toEqual({ status: 'ALL', page: 1, pageSize: 20 });
		expect(history.pageSize.value).toBe(20);
		expect(HISTORY_PAGE_SIZE_OPTIONS.map((option) => option.value)).toEqual([5, 8, 10, 20]);
	});

	it('reads pagination metadata from response headers instead of the item list', () => {
		const items = [task({ taskId: 'task-from-header' })];
		const headers = new Headers({
			'X-Total-Count': '23',
			'X-Page': '2',
			'X-Page-Size': '5',
			'X-Total-Pages': '5',
		});

		expect(toHistoryPage(items, headers)).toEqual({
			items,
			page: 2,
			pageSize: 5,
			totalElements: 23,
			totalPages: 5,
		});
		expect(() => toHistoryPage(items, new Headers({ 'X-Total-Count': '1' }))).toThrow('分页信息');
	});

	it('derives first, last and empty button states from page metadata', async () => {
		const first = useSupplyChainHistory(
			vi.fn(async (query: SupplyChainHistoryQuery) =>
				page([task()], {
					page: query.page,
					pageSize: query.pageSize,
					totalElements: 21,
					totalPages: 3,
				}),
			),
		);
		await first.load();
		expect(first.page.value).toBe(1);
		expect(first.canPrevious.value).toBe(false);
		expect(first.canNext.value).toBe(true);

		const last = useSupplyChainHistory(
			vi.fn(async () => page([task()], { page: 3, totalElements: 21, totalPages: 3 })),
		);
		await last.load();
		expect(last.page.value).toBe(3);
		expect(last.canPrevious.value).toBe(true);
		expect(last.canNext.value).toBe(false);

		const empty = useSupplyChainHistory(
			vi.fn(async () => page([], { page: 1, totalElements: 0, totalPages: 0 })),
		);
		await empty.load();
		expect(empty.tasks.value).toEqual([]);
		expect(empty.totalElements.value).toBe(0);
		expect(empty.totalPages.value).toBe(0);
		expect(empty.canPrevious.value).toBe(false);
		expect(empty.canNext.value).toBe(false);
	});

	it('shows load failures and clears them after a successful retry', async () => {
		const fetcher = vi.fn<(query: SupplyChainHistoryQuery) => Promise<SupplyChainHistoryPage>>();
		fetcher.mockRejectedValueOnce(new Error('backend offline')).mockResolvedValueOnce(page([task()]));
		const history = useSupplyChainHistory(fetcher);

		await history.load();

		expect(history.error.value).toContain('backend offline');
		expect(history.tasks.value).toEqual([]);
		expect(history.totalElements.value).toBe(0);
		expect(history.totalPages.value).toBe(0);
		expect(history.loading.value).toBe(false);
		expect(history.canNext.value).toBe(false);

		await history.load();

		expect(history.error.value).toBe('');
		expect(history.tasks.value).toHaveLength(1);
	});

	it('prevents duplicate requests for the same page while loading', async () => {
		const pending = deferred<SupplyChainHistoryPage>();
		const fetcher = vi.fn(() => pending.promise);
		const history = useSupplyChainHistory(fetcher);

		const firstLoad = history.load();
		expect(history.loading.value).toBe(true);
		expect(history.canNext.value).toBe(false);
		await history.load();
		expect(fetcher).toHaveBeenCalledTimes(1);

		pending.resolve(page([task()]));
		await firstLoad;

		expect(history.loading.value).toBe(false);
	});

	it('ignores a stale response that resolves after a newer filter request', async () => {
		const staleRequest = deferred<SupplyChainHistoryPage>();
		const filteredRequest = deferred<SupplyChainHistoryPage>();
		const fetcher = vi
			.fn<(query: SupplyChainHistoryQuery) => Promise<SupplyChainHistoryPage>>()
			.mockReturnValueOnce(staleRequest.promise)
			.mockReturnValueOnce(filteredRequest.promise);
		const history = useSupplyChainHistory(fetcher);

		const staleLoad = history.load();
		const filteredLoad = history.setStatus('COMPLETED');
		expect(fetcher).toHaveBeenCalledTimes(2);
		expect(fetcher).toHaveBeenLastCalledWith({
			status: 'COMPLETED',
			page: 1,
			pageSize: 8,
		});

		filteredRequest.resolve(page([task({ taskId: 'completed-task' })]));
		await filteredLoad;
		staleRequest.resolve(page([task({ taskId: 'stale-task' })]));
		await staleLoad;

		expect(history.tasks.value.map((item) => item.taskId)).toEqual(['completed-task']);
		expect(history.status.value).toBe('COMPLETED');
	});

	it('keeps the server-reported out-of-range page empty without inventing totals', async () => {
		const history = useSupplyChainHistory(
			vi.fn(async () => page([], { page: 99, pageSize: 8, totalElements: 7, totalPages: 1 })),
		);

		await history.load();

		expect(history.page.value).toBe(99);
		expect(history.totalElements.value).toBe(7);
		expect(history.totalPages.value).toBe(1);
		expect(history.tasks.value).toEqual([]);
		expect(history.canPrevious.value).toBe(true);
		expect(history.canNext.value).toBe(false);
	});

	it('keeps report availability and task details intact for the report entry', async () => {
		const item = task({
			taskId: 'task-report',
			status: 'FAILED',
			currentStep: 'GENERATING_REPORT',
			error: '模型与规则结果不一致',
			question: '为什么订单延迟？',
			reportAvailable: true,
		});
		const history = useSupplyChainHistory(vi.fn(async () => page([item])));

		await history.load();

		expect(history.tasks.value[0]).toMatchObject({
			taskId: 'task-report',
			status: 'FAILED',
			currentStep: 'GENERATING_REPORT',
			error: '模型与规则结果不一致',
			question: '为什么订单延迟？',
			reportAvailable: true,
		});
	});
});
