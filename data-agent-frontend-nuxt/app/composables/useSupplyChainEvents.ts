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

import { ref } from 'vue';
import { fetchSupplyChainTaskEvents, type SupplyChainTaskEvent } from '../services/supplyChainEvents';
import { sortEventsBySequence } from '../utils/supplyChainEventTimeline';

export type SupplyChainEventsFetcher = (taskId: string) => Promise<SupplyChainTaskEvent[]>;

function statusCodeOf(cause: unknown): number {
	if (typeof cause !== 'object' || cause === null) {
		return 0;
	}
	const record = cause as Record<string, unknown>;
	const response =
		typeof record.response === 'object' && record.response !== null
			? (record.response as Record<string, unknown>)
			: null;
	for (const value of [record.statusCode, record.status, response?.status]) {
		if (typeof value === 'number') {
			return value;
		}
	}
	return 0;
}

export function eventsErrorMessage(cause: unknown): string {
	return statusCodeOf(cause) === 404
		? '任务不存在或已被清理，无法读取执行轨迹。'
		: '无法加载执行轨迹，请检查后端服务后重试。';
}

export function useSupplyChainEvents(
	fetcher: SupplyChainEventsFetcher = fetchSupplyChainTaskEvents,
) {
	const events = ref<SupplyChainTaskEvent[]>([]);
	const loading = ref(false);
	const error = ref('');
	const taskId = ref('');

	let requestSequence = 0;

	async function load(nextTaskId: string) {
		const normalized = typeof nextTaskId === 'string' ? nextTaskId.trim() : '';
		if (!normalized) {
			return;
		}
		if (loading.value && taskId.value === normalized) {
			return;
		}
		const sequence = ++requestSequence;
		const sameTask = taskId.value === normalized;
		taskId.value = normalized;
		if (!sameTask) {
			events.value = [];
		}
		error.value = '';
		loading.value = true;
		try {
			const result = await fetcher(normalized);
			if (sequence !== requestSequence) {
				return;
			}
			events.value = sortEventsBySequence(result);
		} catch (cause) {
			if (sequence !== requestSequence) {
				return;
			}
			events.value = [];
			error.value = eventsErrorMessage(cause);
		} finally {
			if (sequence === requestSequence) {
				loading.value = false;
			}
		}
	}

	function retry() {
		return taskId.value ? load(taskId.value) : Promise.resolve();
	}

	function clear() {
		requestSequence += 1;
		events.value = [];
		loading.value = false;
		error.value = '';
		taskId.value = '';
	}

	return {
		events,
		loading,
		error,
		taskId,
		load,
		retry,
		clear,
	};
}
