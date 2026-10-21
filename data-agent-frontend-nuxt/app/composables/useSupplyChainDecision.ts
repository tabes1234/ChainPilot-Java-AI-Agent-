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
import { disposalErrorMessage } from '../services/supplyChainDisposal';
import {
	DEFAULT_DECISION_HISTORY_PAGE_SIZE,
	bindDecisionProposalContentHash,
	cancelDecisionProposal,
	confirmDecisionProposal,
	fetchDecisionHistory,
	fetchDecisionProposals,
	fetchDecisionTaskResult,
	fetchDecisionTaskStatus,
	isProposalExpired,
	submitDecisionTask,
	type DecisionHistoryQuery,
	type DecisionProposal,
	type DecisionTaskResult,
	type SupplyChainHistoryPage,
	type SupplyChainHistoryStatus,
	type SupplyChainTaskStatus,
	type SupplyChainTaskSummary,
} from '../services/supplyChainDecision';

export type DecisionApi = {
	submit: (question: string) => Promise<SupplyChainTaskStatus>;
	status: (taskId: string) => Promise<SupplyChainTaskStatus>;
	result: (taskId: string) => Promise<DecisionTaskResult>;
	proposals: (taskId: string) => Promise<DecisionProposal[]>;
	confirm: (
		proposalId: string,
		contentHash: string,
		bound?: Readonly<DecisionProposal>,
	) => Promise<DecisionProposal>;
	cancel: (
		proposalId: string,
		contentHash: string,
		bound?: Readonly<DecisionProposal>,
	) => Promise<DecisionProposal>;
	history: (query: DecisionHistoryQuery) => Promise<SupplyChainHistoryPage>;
};

export type DecisionComposableOptions = {
	wait?: (milliseconds: number) => Promise<void>;
	pollIntervalMs?: number;
	pollTimeoutMs?: number;
	onProposalConfirmed?: (proposal: DecisionProposal) => void | Promise<void>;
};

export const defaultDecisionApi: DecisionApi = {
	submit: submitDecisionTask,
	status: fetchDecisionTaskStatus,
	result: fetchDecisionTaskResult,
	proposals: fetchDecisionProposals,
	confirm: confirmDecisionProposal,
	cancel: cancelDecisionProposal,
	history: fetchDecisionHistory,
};

const ACTIVE_STATUSES = new Set(['ACCEPTED', 'RUNNING']);

function defaultWait(milliseconds: number): Promise<void> {
	return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

export function useSupplyChainDecision(
	api: DecisionApi = defaultDecisionApi,
	options: DecisionComposableOptions = {},
) {
	const wait = options.wait ?? defaultWait;
	const pollIntervalMs = options.pollIntervalMs ?? 500;
	const pollTimeoutMs = options.pollTimeoutMs ?? 120_000;
	const onProposalConfirmed = options.onProposalConfirmed;

	const activeTask = ref<SupplyChainTaskStatus | null>(null);
	const decision = ref<DecisionTaskResult | null>(null);
	const proposals = ref<DecisionProposal[]>([]);
	const running = ref(false);
	const openingTaskId = ref<string | null>(null);
	const error = ref('');
	const proposalNotice = ref('');
	const proposalError = ref('');
	const busyProposalKey = ref('');

	const history = ref<SupplyChainTaskSummary[]>([]);
	const historyLoading = ref(false);
	const historyError = ref('');
	const historyStatus = ref<SupplyChainHistoryStatus>('ALL');
	const historyPage = ref(1);
	const historyPageSize = ref(DEFAULT_DECISION_HISTORY_PAGE_SIZE);
	const historyTotalElements = ref(0);
	const historyTotalPages = ref(0);
	const historyLoaded = ref(false);

	let runSequence = 0;
	let historySequence = 0;
	let activeHistoryKey = '';
	let proposalSnapshots = new Map<string, Readonly<DecisionProposal>>();

	const clarificationMessages = computed(() => decision.value?.clarifications ?? []);
	const hasPendingProposals = computed(() =>
		proposals.value.some((proposal) => proposal.status === 'PENDING'),
	);
	const canPreviousHistoryPage = computed(
		() => !historyLoading.value && historyTotalPages.value > 0 && historyPage.value > 1,
	);
	const canNextHistoryPage = computed(
		() =>
			!historyLoading.value &&
			historyTotalPages.value > 0 &&
			historyPage.value < historyTotalPages.value,
	);

	function isProposalBusy(proposalId: string): boolean {
		return busyProposalKey.value.startsWith(`${proposalId}:`);
	}

	function resetProposalMessages() {
		proposalNotice.value = '';
		proposalError.value = '';
	}

	function setProposals(next: DecisionProposal[]) {
		proposals.value = next;
		proposalSnapshots = new Map(
			next.map((proposal) => [proposal.proposalId, bindDecisionProposalContentHash(proposal)]),
		);
	}

	function applyProposal(updated: DecisionProposal) {
		const index = proposals.value.findIndex((proposal) => proposal.proposalId === updated.proposalId);
		if (index >= 0) {
			proposals.value = proposals.value.map((proposal) =>
				proposal.proposalId === updated.proposalId ? updated : proposal,
			);
		} else {
			proposals.value = [...proposals.value, updated];
		}
		proposalSnapshots.set(updated.proposalId, bindDecisionProposalContentHash(updated));
	}

	function syncPlanFromConfirmedProposal(updated: DecisionProposal) {
		const current = decision.value;
		const result = updated.result;
		if (!current?.plan || !result || result.planId !== current.plan.planId) {
			return;
		}
		const status = result.status;
		if (status !== 'PENDING_CONFIRMATION' && status !== 'SELECTED' && status !== 'REJECTED') {
			return;
		}
		decision.value = {
			...current,
			plan: {
				...current.plan,
				status,
				selectedRecommendationId:
					typeof result.selectedRecommendationId === 'string' && result.selectedRecommendationId
						? result.selectedRecommendationId
						: current.plan.selectedRecommendationId,
			},
		};
	}

	async function pollUntilTerminal(taskId: string): Promise<SupplyChainTaskStatus> {
		const deadline = Date.now() + pollTimeoutMs;
		let current = await api.status(taskId);
		activeTask.value = current;
		while (ACTIVE_STATUSES.has(current.status)) {
			if (Date.now() >= deadline) {
				throw new Error(`任务仍在执行，可稍后通过任务编号继续查询：${taskId}`);
			}
			await wait(pollIntervalMs);
			current = await api.status(taskId);
			activeTask.value = current;
		}
		if (current.status !== 'COMPLETED') {
			throw new Error(current.error || `任务失败，当前步骤：${current.currentStep || '未知'}`);
		}
		return current;
	}

	async function loadDecisionResult(taskId: string) {
		const result = await api.result(taskId);
		decision.value = result;
		const serverProposals = await api.proposals(taskId);
		setProposals(Array.isArray(serverProposals) ? serverProposals : []);
		resetProposalMessages();
	}

	async function run(question: string) {
		const normalized = question.trim();
		if (!normalized) {
			error.value = '请输入要分析或决策的供应链问题。';
			return null;
		}
		if (running.value) {
			return null;
		}
		const sequence = ++runSequence;
		running.value = true;
		error.value = '';
		decision.value = null;
		setProposals([]);
		activeTask.value = null;
		resetProposalMessages();
		try {
			const submitted = await api.submit(normalized);
			if (sequence !== runSequence) {
				return null;
			}
			activeTask.value = submitted;
			const terminal = await pollUntilTerminal(submitted.taskId);
			if (sequence !== runSequence) {
				return null;
			}
			await loadDecisionResult(terminal.taskId);
			await loadHistory();
			return decision.value;
		} catch (cause) {
			if (sequence === runSequence) {
				error.value = disposalErrorMessage(cause, '自然语言决策任务失败。');
			}
			return null;
		} finally {
			if (sequence === runSequence) {
				running.value = false;
			}
		}
	}

	async function openTask(taskId: string) {
		const normalized = taskId.trim();
		if (!normalized || running.value || openingTaskId.value) {
			return null;
		}
		const sequence = ++runSequence;
		openingTaskId.value = normalized;
		error.value = '';
		decision.value = null;
		setProposals([]);
		resetProposalMessages();
		try {
			const current = await api.status(normalized);
			if (sequence !== runSequence) {
				return null;
			}
			activeTask.value = current;
			if (ACTIVE_STATUSES.has(current.status)) {
				await pollUntilTerminal(normalized);
			} else if (current.status !== 'COMPLETED') {
				throw new Error(current.error || `任务失败，当前步骤：${current.currentStep || '未知'}`);
			}
			if (sequence !== runSequence) {
				return null;
			}
			await loadDecisionResult(normalized);
			return decision.value;
		} catch (cause) {
			if (sequence === runSequence) {
				error.value = disposalErrorMessage(cause, '无法加载该决策任务。');
			}
			return null;
		} finally {
			if (sequence === runSequence) {
				openingTaskId.value = null;
			}
		}
	}

	async function refreshProposals() {
		const taskId = activeTask.value?.taskId ?? decision.value?.taskId;
		if (!taskId) {
			return [];
		}
		try {
			setProposals(await api.proposals(taskId));
			return proposals.value;
		} catch (cause) {
			proposalError.value = disposalErrorMessage(cause, '无法刷新待确认提案。');
			return [];
		}
	}

	async function notifyProposalConfirmed(updated: DecisionProposal) {
		if (updated.status !== 'CONFIRMED' || !onProposalConfirmed) {
			return;
		}
		try {
			await onProposalConfirmed(updated);
		} catch {
			// A refresh failure must never turn a successful backend confirmation into an error.
		}
	}

	async function confirmProposal(proposalId: string) {
		const proposal = proposals.value.find((item) => item.proposalId === proposalId);
		if (!proposal || isProposalBusy(proposalId)) {
			return null;
		}
		if (proposal.status !== 'PENDING') {
			proposalError.value = `该提案当前状态为 ${proposal.status}，不能再次确认。`;
			return null;
		}
		if (isProposalExpired(proposal)) {
			proposalError.value = '该提案已超过确认时限，请重新发起决策请求。';
			return null;
		}
		const rendered = proposalSnapshots.get(proposalId);
		if (rendered && rendered.contentHash !== proposal.contentHash) {
			proposalError.value = '提案内容已变化，请刷新提案后重新确认。';
			return null;
		}
		busyProposalKey.value = `${proposalId}:CONFIRM`;
		resetProposalMessages();
		try {
			const bound = rendered ?? bindDecisionProposalContentHash(proposal);
			const updated = await api.confirm(proposalId, proposal.contentHash, bound);
			applyProposal(updated);
			syncPlanFromConfirmedProposal(updated);
			if (updated.status === 'CONFIRMED') {
				proposalNotice.value = `提案已确认并由后端重新校验后执行：${updated.proposalId}。`;
				await notifyProposalConfirmed(updated);
			} else {
				proposalNotice.value = `提案状态已更新为 ${updated.status}。`;
			}
			return updated;
		} catch (cause) {
			proposalError.value = disposalErrorMessage(cause, '确认提案失败，请刷新后重试。');
			return null;
		} finally {
			busyProposalKey.value = '';
		}
	}

	async function cancelProposal(proposalId: string) {
		const proposal = proposals.value.find((item) => item.proposalId === proposalId);
		if (!proposal || isProposalBusy(proposalId)) {
			return null;
		}
		if (proposal.status !== 'PENDING') {
			proposalError.value = `该提案当前状态为 ${proposal.status}，不能取消。`;
			return null;
		}
		const rendered = proposalSnapshots.get(proposalId);
		if (rendered && rendered.contentHash !== proposal.contentHash) {
			proposalError.value = '提案内容已变化，请刷新提案后重新取消。';
			return null;
		}
		busyProposalKey.value = `${proposalId}:CANCEL`;
		resetProposalMessages();
		try {
			const bound = rendered ?? bindDecisionProposalContentHash(proposal);
			const updated = await api.cancel(proposalId, proposal.contentHash, bound);
			applyProposal(updated);
			proposalNotice.value = `提案已取消：${updated.proposalId}，不会产生任何业务变更。`;
			return updated;
		} catch (cause) {
			proposalError.value = disposalErrorMessage(cause, '取消提案失败，请刷新后重试。');
			return null;
		} finally {
			busyProposalKey.value = '';
		}
	}

	function currentHistoryKey() {
		return `${historyStatus.value}:${historyPage.value}:${historyPageSize.value}`;
	}

	async function loadHistory() {
		const key = currentHistoryKey();
		if (historyLoading.value && key === activeHistoryKey) {
			return;
		}
		const sequence = ++historySequence;
		activeHistoryKey = key;
		historyLoading.value = true;
		historyError.value = '';
		try {
			const page = await api.history({
				status: historyStatus.value,
				page: historyPage.value,
				pageSize: historyPageSize.value,
			});
			if (sequence !== historySequence) {
				return;
			}
			history.value = page.items;
			historyPage.value = page.page;
			historyPageSize.value = page.pageSize;
			historyTotalElements.value = page.totalElements;
			historyTotalPages.value = page.totalPages;
			historyLoaded.value = true;
		} catch (cause) {
			if (sequence !== historySequence) {
				return;
			}
			history.value = [];
			historyTotalElements.value = 0;
			historyTotalPages.value = 0;
			historyLoaded.value = true;
			historyError.value = disposalErrorMessage(cause, '无法加载决策任务历史。');
		} finally {
			if (sequence === historySequence) {
				historyLoading.value = false;
				activeHistoryKey = '';
			}
		}
	}

	function setHistoryStatus(status: SupplyChainHistoryStatus) {
		if (status === historyStatus.value) {
			return;
		}
		historyStatus.value = status;
		historyPage.value = 1;
		history.value = [];
		historyLoaded.value = false;
		return loadHistory();
	}

	function setHistoryPageSize(pageSize: number) {
		if (!Number.isInteger(pageSize) || pageSize < 1 || pageSize === historyPageSize.value) {
			return;
		}
		historyPageSize.value = pageSize;
		historyPage.value = 1;
		history.value = [];
		historyLoaded.value = false;
		return loadHistory();
	}

	function goToHistoryPage(page: number) {
		if (!Number.isInteger(page)) {
			return;
		}
		const lastPage = Math.max(historyTotalPages.value, 1);
		const target = Math.min(Math.max(page, 1), lastPage);
		if (target === historyPage.value && !historyError.value) {
			return;
		}
		historyPage.value = target;
		history.value = [];
		historyLoaded.value = false;
		return loadHistory();
	}

	function previousHistoryPage() {
		if (!canPreviousHistoryPage.value) {
			return;
		}
		return goToHistoryPage(historyPage.value - 1);
	}

	function nextHistoryPage() {
		if (!canNextHistoryPage.value) {
			return;
		}
		return goToHistoryPage(historyPage.value + 1);
	}

	function reset() {
		runSequence += 1;
		running.value = false;
		openingTaskId.value = null;
		activeTask.value = null;
		decision.value = null;
		setProposals([]);
		error.value = '';
		resetProposalMessages();
		busyProposalKey.value = '';
	}

	return {
		activeTask,
		decision,
		proposals,
		running,
		openingTaskId,
		error,
		proposalNotice,
		proposalError,
		busyProposalKey,
		clarificationMessages,
		hasPendingProposals,
		history,
		historyLoading,
		historyError,
		historyStatus,
		historyPage,
		historyPageSize,
		historyTotalElements,
		historyTotalPages,
		historyLoaded,
		canPreviousHistoryPage,
		canNextHistoryPage,
		isProposalBusy,
		run,
		openTask,
		refreshProposals,
		confirmProposal,
		cancelProposal,
		loadHistory,
		setHistoryStatus,
		setHistoryPageSize,
		previousHistoryPage,
		nextHistoryPage,
		reset,
	};
}
