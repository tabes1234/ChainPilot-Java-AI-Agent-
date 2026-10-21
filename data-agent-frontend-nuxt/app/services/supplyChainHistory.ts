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

export type SupplyChainTaskStatusValue = 'ACCEPTED' | 'RUNNING' | 'COMPLETED' | 'FAILED';

export type SupplyChainTaskStatus = {
	taskId: string;
	status: SupplyChainTaskStatusValue;
	currentStep: string;
	validationAttempts: number;
	error: string | null;
	updatedAt?: string;
};

export type SupplyChainTaskSummary = SupplyChainTaskStatus & {
	question: string;
	updatedAt: string;
	reportAvailable: boolean;
};

export type SupplyChainHistoryStatus = 'ALL' | SupplyChainTaskStatusValue;

export type SupplyChainTaskMode = 'ANALYSIS' | 'DECISION';

export type SupplyChainHistoryQuery = {
	status: SupplyChainHistoryStatus;
	page: number;
	pageSize: number;
	mode?: SupplyChainTaskMode;
};

export type SupplyChainHistoryPage = {
	items: SupplyChainTaskSummary[];
	page: number;
	pageSize: number;
	totalElements: number;
	totalPages: number;
};

export const SUPPLY_CHAIN_HISTORY_ENDPOINT = '/api/supply-chain/analysis/tasks';

export const DEFAULT_HISTORY_PAGE_SIZE = 8;

export const HISTORY_STATUS_OPTIONS: { title: string; value: SupplyChainHistoryStatus }[] = [
	{ title: '全部状态', value: 'ALL' },
	{ title: '排队中', value: 'ACCEPTED' },
	{ title: '执行中', value: 'RUNNING' },
	{ title: '已完成', value: 'COMPLETED' },
	{ title: '失败', value: 'FAILED' },
];

export const HISTORY_PAGE_SIZE_OPTIONS: { title: string; value: number }[] = [
	{ title: '5 条/页', value: 5 },
	{ title: '8 条/页', value: 8 },
	{ title: '10 条/页', value: 10 },
	{ title: '20 条/页', value: 20 },
];

export type SupplyChainHistoryRequestQuery = {
	page: number;
	pageSize: number;
	status?: SupplyChainTaskStatusValue;
	mode?: SupplyChainTaskMode;
};

export function buildHistoryQuery(query: SupplyChainHistoryQuery): SupplyChainHistoryRequestQuery {
	const request: SupplyChainHistoryRequestQuery = { page: query.page, pageSize: query.pageSize };
	if (query.status !== 'ALL') {
		request.status = query.status;
	}
	if (query.mode) {
		request.mode = query.mode;
	}
	return request;
}

function readIntegerHeader(headers: Headers, name: string): number | null {
	const raw = headers.get(name);
	if (raw === null || raw.trim() === '') {
		return null;
	}
	const value = Number(raw);
	return Number.isInteger(value) && value >= 0 ? value : null;
}

export function toHistoryPage(items: SupplyChainTaskSummary[], headers: Headers): SupplyChainHistoryPage {
	const page = readIntegerHeader(headers, 'x-page');
	const pageSize = readIntegerHeader(headers, 'x-page-size');
	const totalElements = readIntegerHeader(headers, 'x-total-count');
	const totalPages = readIntegerHeader(headers, 'x-total-pages');
	if (
		page === null ||
		page < 1 ||
		pageSize === null ||
		pageSize < 1 ||
		totalElements === null ||
		totalPages === null
	) {
		throw new Error('任务历史响应缺少有效的分页信息。');
	}
	return { items, page, pageSize, totalElements, totalPages };
}

export async function fetchSupplyChainHistory(query: SupplyChainHistoryQuery): Promise<SupplyChainHistoryPage> {
	const response = await $fetch.raw<SupplyChainTaskSummary[]>(SUPPLY_CHAIN_HISTORY_ENDPOINT, {
		query: buildHistoryQuery(query),
	});
	return toHistoryPage(Array.isArray(response._data) ? response._data : [], response.headers);
}
