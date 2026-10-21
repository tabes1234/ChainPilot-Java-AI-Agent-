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

import { computed, ref } from 'vue';
import {
	DEFAULT_HISTORY_PAGE_SIZE,
	fetchSupplyChainHistory,
	type SupplyChainHistoryPage,
	type SupplyChainHistoryQuery,
	type SupplyChainHistoryStatus,
	type SupplyChainTaskSummary,
} from '../services/supplyChainHistory';

export type SupplyChainHistoryFetcher = (query: SupplyChainHistoryQuery) => Promise<SupplyChainHistoryPage>;

export function useSupplyChainHistory(fetcher: SupplyChainHistoryFetcher = fetchSupplyChainHistory) {
	const tasks = ref<SupplyChainTaskSummary[]>([]);
	const loading = ref(false);
	const error = ref('');
	const status = ref<SupplyChainHistoryStatus>('ALL');
	const page = ref(1);
	const pageSize = ref(DEFAULT_HISTORY_PAGE_SIZE);
	const totalElements = ref(0);
	const totalPages = ref(0);
	const loaded = ref(false);

	let requestSequence = 0;
	let activeRequestKey = '';

	const canPrevious = computed(() => !loading.value && totalPages.value > 0 && page.value > 1);
	const canNext = computed(
		() => !loading.value && totalPages.value > 0 && page.value < totalPages.value,
	);

	function currentRequestKey() {
		return `${status.value}:${page.value}:${pageSize.value}`;
	}

	function resetResult() {
		tasks.value = [];
		totalElements.value = 0;
		totalPages.value = 0;
		loaded.value = false;
	}

	async function load() {
		const requestKey = currentRequestKey();
		if (loading.value && requestKey === activeRequestKey) {
			return;
		}
		const sequence = ++requestSequence;
		activeRequestKey = requestKey;
		loading.value = true;
		error.value = '';
		try {
			const result = await fetcher({
				status: status.value,
				page: page.value,
				pageSize: pageSize.value,
			});
			if (sequence !== requestSequence) {
				return;
			}
			tasks.value = result.items;
			page.value = result.page;
			pageSize.value = result.pageSize;
			totalElements.value = result.totalElements;
			totalPages.value = result.totalPages;
			loaded.value = true;
		} catch (cause) {
			if (sequence !== requestSequence) {
				return;
			}
			resetResult();
			loaded.value = true;
			error.value =
				cause instanceof Error ? `无法加载任务历史：${cause.message}` : '无法加载任务历史。';
		} finally {
			if (sequence === requestSequence) {
				loading.value = false;
				activeRequestKey = '';
			}
		}
	}

	function setStatus(value: SupplyChainHistoryStatus) {
		if (value === status.value) {
			return;
		}
		status.value = value;
		page.value = 1;
		resetResult();
		return load();
	}

	function setPageSize(value: number) {
		if (!Number.isInteger(value) || value < 1 || value === pageSize.value) {
			return;
		}
		pageSize.value = value;
		page.value = 1;
		resetResult();
		return load();
	}

	function goToPage(value: number) {
		if (!Number.isInteger(value)) {
			return;
		}
		const lastPage = Math.max(totalPages.value, 1);
		const target = Math.min(Math.max(value, 1), lastPage);
		if (target === page.value && !error.value) {
			return;
		}
		page.value = target;
		resetResult();
		return load();
	}

	function previousPage() {
		if (!canPrevious.value) {
			return;
		}
		return goToPage(page.value - 1);
	}

	function nextPage() {
		if (!canNext.value) {
			return;
		}
		return goToPage(page.value + 1);
	}

	return {
		tasks,
		loading,
		error,
		status,
		page,
		pageSize,
		totalElements,
		totalPages,
		loaded,
		canPrevious,
		canNext,
		load,
		setStatus,
		setPageSize,
		previousPage,
		nextPage,
	};
}
