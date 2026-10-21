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

export type SupplyChainTaskEvent = {
	eventId: string;
	taskId: string;
	sequence: number;
	type: string;
	stage: string;
	status: string;
	occurredAt: string;
	durationMs: number | null;
	summary: string | null;
	errorType: string | null;
};

export function eventsEndpoint(taskId: string): string {
	return `/api/supply-chain/analysis/tasks/${encodeURIComponent(taskId)}/events`;
}

export function toSupplyChainTaskEvents(value: unknown): SupplyChainTaskEvent[] {
	if (!Array.isArray(value)) {
		throw new Error('Unexpected execution event response.');
	}
	return value as SupplyChainTaskEvent[];
}

export async function fetchSupplyChainTaskEvents(taskId: string): Promise<SupplyChainTaskEvent[]> {
	const response = await $fetch<unknown>(eventsEndpoint(taskId));
	return toSupplyChainTaskEvents(response);
}
