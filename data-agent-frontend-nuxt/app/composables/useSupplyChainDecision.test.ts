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

import { afterEach, describe, expect, it, vi } from 'vitest';
import { useSupplyChainDecision, type DecisionApi } from './useSupplyChainDecision';
import type {
	DecisionProposal,
	DecisionTaskResult,
	SupplyChainHistoryPage,
	SupplyChainTaskStatus,
	SupplyChainTaskSummary,
} from '../services/supplyChainDecision';
import type { DisposalPlan } from '../services/supplyChainDisposal';

function taskStatus(
	taskId: string,
	status: SupplyChainTaskStatus['status'],
	overrides: Partial<SupplyChainTaskStatus> = {},
): SupplyChainTaskStatus {
	return {
		taskId,
		status,
		currentStep: status === 'COMPLETED' ? 'DONE' : 'DECISION_EXECUTION',
		validationAttempts: 0,
		error: null,
		...overrides,
	};
}

function proposal(overrides: Partial<DecisionProposal> = {}): DecisionProposal {
	return {
		proposalId: 'proposal-1',
		taskId: 'task-1',
		actionType: 'START_SANDBOX_EXECUTION',
		title: '启动方案 plan-1 的沙箱演练',
		targetId: 'plan-1',
		parameters: { recommendationId: 'rec-1' },
		evidence: ['方案已选定，执行资格通过'],
		contentHash: 'hash-abc',
		status: 'PENDING',
		createdAt: '2026-10-20T08:00:00Z',
		expiresAt: new Date(Date.now() + 30 * 60 * 1000).toISOString(),
		confirmedAt: null,
		result: null,
		errorMessage: null,
		...overrides,
	};
}

function decisionResult(overrides: Partial<DecisionTaskResult> = {}): DecisionTaskResult {
	return {
		taskId: 'task-1',
		question: '比较 SKU-MCU-001 补货 50、100、200 件的方案',
		intent: 'COMPARE_REPLENISHMENT',
		status: 'COMPLETED',
		summary: '候选 CAND-100 排名 1。',
		narrativeSource: 'MODEL_GROUNDED',
		modelAssisted: true,
		fallbackUsed: false,
		clarificationRequired: false,
		clarifications: [],
		toolCalls: ['compareDisposalScenarios'],
		executionPlan: ['调用补货方案对比工具'],
		plannerAssisted: false,
		atRiskOrders: [],
		recommendationNotes: [],
		recommendations: [],
		simulation: null,
		comparison: null,
		plan: null,
		execution: null,
		sandboxEligibility: null,
		proposals: [],
		citations: [],
		evidenceNotes: ['候选指标来自 Java 对比引擎'],
		realDataMutated: false,
		createdAt: '2026-10-20T08:00:00Z',
		...overrides,
	};
}

function disposalPlan(overrides: Partial<DisposalPlan> = {}): DisposalPlan {
	return {
		planId: 'plan-1',
		title: 'MCU 补货方案',
		taskId: 'task-1',
		scopeSku: 'SKU-MCU-001',
		scopeSupplierId: null,
		status: 'PENDING_CONFIRMATION',
		selectedRecommendationId: null,
		recommendations: [],
		simulation: null,
		evidence: [],
		explanation: '规则说明',
		explanationSource: 'DETERMINISTIC',
		explanationModelAssisted: false,
		explanationToolCalls: [],
		createdAt: '2026-10-20T08:00:00Z',
		updatedAt: '2026-10-20T08:00:00Z',
		...overrides,
	};
}

function historyPage(items: SupplyChainTaskSummary[] = []): SupplyChainHistoryPage {
	return {
		items,
		page: 1,
		pageSize: 5,
		totalElements: items.length,
		totalPages: items.length > 0 ? 1 : 0,
	};
}

function historyTask(status: SupplyChainTaskSummary['status'] = 'COMPLETED'): SupplyChainTaskSummary {
	return {
		taskId: 'task-1',
		status,
		currentStep: 'DONE',
		validationAttempts: 0,
		error: null,
		question: '比较 SKU-MCU-001 补货 50、100、200 件的方案',
		updatedAt: '2026-10-20T08:00:00Z',
		reportAvailable: false,
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

function apiWith(overrides: Partial<DecisionApi> = {}): DecisionApi {
	return {
		submit: vi.fn(async () => taskStatus('task-1', 'ACCEPTED')),
		status: vi.fn(async () => taskStatus('task-1', 'COMPLETED')),
		result: vi.fn(async () => decisionResult()),
		proposals: vi.fn(async () => []),
		confirm: vi.fn(async () => proposal({ status: 'CONFIRMED' })),
		cancel: vi.fn(async () => proposal({ status: 'CANCELLED' })),
		history: vi.fn(async () => historyPage()),
		...overrides,
	};
}

afterEach(() => {
	vi.useRealTimers();
});

describe('useSupplyChainDecision', () => {
	it('submits a DECISION task, polls it and loads the grounded result and proposals', async () => {
		const proposals = [proposal()];
		const api = apiWith({
			status: vi
				.fn()
				.mockResolvedValueOnce(taskStatus('task-1', 'RUNNING'))
				.mockResolvedValueOnce(taskStatus('task-1', 'COMPLETED')),
			result: vi.fn(async () =>
				decisionResult({
					summary: '候选 CAND-100 排名 1，风险订单Δ=-6。',
					toolCalls: ['compareDisposalScenarios'],
				}),
			),
			proposals: vi.fn(async () => proposals),
		});
		const state = useSupplyChainDecision(api, { wait: async () => undefined });

		const result = await state.run('  比较 SKU-MCU-001 补货 50、100、200 件的方案  ');

		expect(api.submit).toHaveBeenCalledWith('比较 SKU-MCU-001 补货 50、100、200 件的方案');
		expect(api.status).toHaveBeenCalledTimes(2);
		expect(api.result).toHaveBeenCalledWith('task-1');
		expect(api.proposals).toHaveBeenCalledWith('task-1');
		expect(api.history).toHaveBeenCalled();
		expect(result?.summary).toContain('CAND-100');
		expect(state.decision.value?.narrativeSource).toBe('MODEL_GROUNDED');
		expect(state.proposals.value).toHaveLength(1);
		expect(state.hasPendingProposals.value).toBe(true);
		expect(state.running.value).toBe(false);
		expect(state.error.value).toBe('');
	});

	it('keeps an ambiguous request as a clarification result without inventing business data', async () => {
		const api = apiWith({
			result: vi.fn(async () =>
				decisionResult({
					intent: 'UNSUPPORTED',
					clarificationRequired: true,
					clarifications: ['请提供需要比较补货方案的 SKU。'],
					summary: '需要补充 SKU 后才能比较补货方案。',
					modelAssisted: false,
					narrativeSource: 'DETERMINISTIC',
					toolCalls: [],
				}),
			),
		});
		const state = useSupplyChainDecision(api, { wait: async () => undefined });

		await state.run('比较补货 50、100、200 件的方案');

		expect(state.error.value).toBe('');
		expect(state.clarificationMessages.value).toEqual(['请提供需要比较补货方案的 SKU。']);
		expect(state.decision.value?.toolCalls).toEqual([]);
		expect(state.decision.value?.summary).toContain('需要补充 SKU');
	});

	it('exposes the deterministic fallback source instead of pretending the model succeeded', async () => {
		const api = apiWith({
			result: vi.fn(async () =>
				decisionResult({
					narrativeSource: 'DETERMINISTIC_FALLBACK',
					modelAssisted: false,
					fallbackUsed: true,
					summary: '确定性对比摘要：候选 CAND-100 排名 1。',
				}),
			),
		});
		const state = useSupplyChainDecision(api, { wait: async () => undefined });

		await state.run('比较 SKU-MCU-001 补货 50、100、200 件的方案');

		expect(state.decision.value?.fallbackUsed).toBe(true);
		expect(state.decision.value?.narrativeSource).toBe('DETERMINISTIC_FALLBACK');
		expect(state.decision.value?.summary).toContain('CAND-100');
		expect(state.error.value).toBe('');
	});

	it('reports a failed tool task without loading fabricated results', async () => {
		const api = apiWith({
			status: vi
				.fn()
				.mockResolvedValueOnce(taskStatus('task-1', 'RUNNING'))
				.mockResolvedValueOnce(
					taskStatus('task-1', 'FAILED', {
						currentStep: 'DECISION_EXECUTION',
						error: 'deterministic comparison unavailable',
					}),
				),
		});
		const state = useSupplyChainDecision(api, { wait: async () => undefined });

		const result = await state.run('比较 SKU-MCU-001 补货 50、100、200 件的方案');

		expect(result).toBeNull();
		expect(api.result).not.toHaveBeenCalled();
		expect(api.proposals).not.toHaveBeenCalled();
		expect(state.error.value).toContain('deterministic comparison unavailable');
		expect(state.decision.value).toBeNull();
		expect(state.running.value).toBe(false);
	});

	it('guards against duplicate submissions while a task is running', async () => {
		let resolveSubmit: (value: SupplyChainTaskStatus) => void = () => undefined;
		const submit = vi.fn(
			() =>
				new Promise<SupplyChainTaskStatus>((resolve) => {
					resolveSubmit = resolve;
				}),
		);
		const api = apiWith({ submit });
		const state = useSupplyChainDecision(api, { wait: async () => undefined });

		const first = state.run('比较 SKU-MCU-001 补货 50、100、200 件的方案');
		const second = await state.run('比较 SKU-MCU-001 补货 50、100、200 件的方案');
		expect(second).toBeNull();
		expect(submit).toHaveBeenCalledTimes(1);

		resolveSubmit(taskStatus('task-1', 'ACCEPTED'));
		await first;
		expect(submit).toHaveBeenCalledTimes(1);
	});

	it('confirms a pending proposal with its rendered content hash exactly once', async () => {
		let resolveConfirm: (value: DecisionProposal) => void = () => undefined;
		const confirm = vi.fn(
			() =>
				new Promise<DecisionProposal>((resolve) => {
					resolveConfirm = resolve;
				}),
		);
		const api = apiWith({ proposals: vi.fn(async () => [proposal()]), confirm });
		const state = useSupplyChainDecision(api, { wait: async () => undefined });
		await state.run('启动方案 plan-1 的沙箱演练');

		const first = state.confirmProposal('proposal-1');
		const duplicate = await state.confirmProposal('proposal-1');
		expect(duplicate).toBeNull();
		expect(confirm).toHaveBeenCalledTimes(1);
		expect(confirm.mock.calls[0]?.[0]).toBe('proposal-1');
		expect(confirm.mock.calls[0]?.[1]).toBe('hash-abc');

		resolveConfirm(
			proposal({
				status: 'CONFIRMED',
				confirmedAt: '2026-10-20T08:10:00Z',
				result: { executionId: 'exec-1', status: 'SUCCEEDED', realDataMutated: false },
			}),
		);
		await first;
		expect(state.proposals.value[0]?.status).toBe('CONFIRMED');
		expect(state.proposalNotice.value).toContain('后端重新校验后执行');
		expect(state.busyProposalKey.value).toBe('');
	});

	it('reflects a confirmed plan selection in the linked plan without a second fetch', async () => {
		const selectProposal = proposal({
			actionType: 'SELECT_PLAN',
			title: '确认选用方案 plan-1 的建议 rec-1',
			targetId: 'plan-1',
			parameters: { recommendationId: 'rec-1' },
		});
		const api = apiWith({
			proposals: vi.fn(async () => [selectProposal]),
			result: vi.fn(async () =>
				decisionResult({ intent: 'PROPOSE_SELECT_PLAN', plan: disposalPlan() }),
			),
			confirm: vi.fn(async () =>
				proposal({
					actionType: 'SELECT_PLAN',
					title: selectProposal.title,
					targetId: 'plan-1',
					parameters: { recommendationId: 'rec-1' },
					status: 'CONFIRMED',
					confirmedAt: '2026-10-20T08:10:00Z',
					result: {
						planId: 'plan-1',
						status: 'SELECTED',
						selectedRecommendationId: 'rec-1',
						realDataMutated: false,
					},
				}),
			),
		});
		const state = useSupplyChainDecision(api, { wait: async () => undefined });
		await state.run('确认选用方案 plan-1 的建议 rec-1');

		await state.confirmProposal('proposal-1');

		expect(api.result).toHaveBeenCalledTimes(1);
		expect(state.decision.value?.plan?.status).toBe('SELECTED');
		expect(state.decision.value?.plan?.selectedRecommendationId).toBe('rec-1');
		expect(state.decision.value?.realDataMutated).toBe(false);
	});

	it('refreshes related plan state exactly once after the backend confirms the proposal', async () => {
		const onProposalConfirmed = vi.fn(async () => undefined);
		const api = apiWith({
			proposals: vi.fn(async () => [proposal()]),
			confirm: vi.fn(async () =>
				proposal({
					status: 'CONFIRMED',
					confirmedAt: '2026-10-20T08:10:00Z',
					result: { planId: 'plan-new', realDataMutated: false },
				}),
			),
		});
		const state = useSupplyChainDecision(api, {
			wait: async () => undefined,
			onProposalConfirmed,
		});
		await state.run('启动方案 plan-1 的沙箱演练');

		const result = await state.confirmProposal('proposal-1');

		expect(result?.status).toBe('CONFIRMED');
		expect(onProposalConfirmed).toHaveBeenCalledTimes(1);
		expect(onProposalConfirmed).toHaveBeenCalledWith(
			expect.objectContaining({ proposalId: 'proposal-1', status: 'CONFIRMED' }),
		);
		expect(state.proposalError.value).toBe('');
	});

	it('keeps the real confirmation error and skips the refresh when the backend rejects', async () => {
		const onProposalConfirmed = vi.fn(async () => undefined);
		const api = apiWith({
			proposals: vi.fn(async () => [proposal()]),
			confirm: vi.fn(async () => {
				throw Object.assign(new Error('Conflict'), { statusCode: 409 });
			}),
		});
		const state = useSupplyChainDecision(api, {
			wait: async () => undefined,
			onProposalConfirmed,
		});
		await state.run('启动方案 plan-1 的沙箱演练');

		const result = await state.confirmProposal('proposal-1');

		expect(result).toBeNull();
		expect(onProposalConfirmed).not.toHaveBeenCalled();
		expect(state.proposals.value[0]?.status).toBe('PENDING');
		expect(state.proposalError.value).toContain('当前状态不允许此操作');
		expect(state.proposalNotice.value).toBe('');
	});

	it('does not confirm or refresh twice while the first confirmation is in flight', async () => {
		const pending = deferred<DecisionProposal>();
		const confirm = vi.fn(() => pending.promise);
		const onProposalConfirmed = vi.fn(async () => undefined);
		const api = apiWith({ proposals: vi.fn(async () => [proposal()]), confirm });
		const state = useSupplyChainDecision(api, {
			wait: async () => undefined,
			onProposalConfirmed,
		});
		await state.run('启动方案 plan-1 的沙箱演练');

		const first = state.confirmProposal('proposal-1');
		const duplicate = await state.confirmProposal('proposal-1');

		expect(duplicate).toBeNull();
		expect(confirm).toHaveBeenCalledTimes(1);
		expect(onProposalConfirmed).not.toHaveBeenCalled();

		pending.resolve(
			proposal({
				status: 'CONFIRMED',
				confirmedAt: '2026-10-20T08:10:00Z',
				result: { planId: 'plan-1', realDataMutated: false },
			}),
		);
		await first;
		expect(onProposalConfirmed).toHaveBeenCalledTimes(1);
	});

	it('does not report a successful confirmation as failed when the refresh throws', async () => {
		const onProposalConfirmed = vi.fn(async () => {
			throw new Error('plan refresh unavailable');
		});
		const api = apiWith({
			proposals: vi.fn(async () => [proposal()]),
			confirm: vi.fn(async () =>
				proposal({
					status: 'CONFIRMED',
					confirmedAt: '2026-10-20T08:10:00Z',
					result: { planId: 'plan-1', realDataMutated: false },
				}),
			),
		});
		const state = useSupplyChainDecision(api, {
			wait: async () => undefined,
			onProposalConfirmed,
		});
		await state.run('启动方案 plan-1 的沙箱演练');

		const result = await state.confirmProposal('proposal-1');

		expect(result?.status).toBe('CONFIRMED');
		expect(state.proposalError.value).toBe('');
		expect(state.proposalNotice.value).toContain('后端重新校验后执行');
	});

	it('does not confirm after the rendered proposal content hash changed', async () => {
		const confirm = vi.fn(async () => proposal({ status: 'CONFIRMED' }));
		const api = apiWith({ proposals: vi.fn(async () => [proposal()]), confirm });
		const state = useSupplyChainDecision(api, { wait: async () => undefined });
		await state.run('启动方案 plan-1 的沙箱演练');

		state.proposals.value = [{ ...proposal(), contentHash: 'hash-tampered' }];
		const result = await state.confirmProposal('proposal-1');

		expect(result).toBeNull();
		expect(confirm).not.toHaveBeenCalled();
		expect(state.proposalError.value).toContain('提案内容已变化');
	});

	it('keeps a proposal pending when the backend rejects confirmation', async () => {
		const confirm = vi.fn(async () => {
			throw Object.assign(new Error('Conflict'), { statusCode: 409 });
		});
		const api = apiWith({ proposals: vi.fn(async () => [proposal()]), confirm });
		const state = useSupplyChainDecision(api, { wait: async () => undefined });
		await state.run('启动方案 plan-1 的沙箱演练');

		const result = await state.confirmProposal('proposal-1');

		expect(result).toBeNull();
		expect(state.proposals.value[0]?.status).toBe('PENDING');
		expect(state.proposalError.value).toContain('当前状态不允许此操作');
		expect(state.busyProposalKey.value).toBe('');
	});

	it('cancels a pending proposal with the exact content hash and never confirms it afterwards', async () => {
		const cancel = vi.fn(async () => proposal({ status: 'CANCELLED' }));
		const confirm = vi.fn(async () => proposal({ status: 'CONFIRMED' }));
		const api = apiWith({ proposals: vi.fn(async () => [proposal()]), cancel, confirm });
		const state = useSupplyChainDecision(api, { wait: async () => undefined });
		await state.run('启动方案 plan-1 的沙箱演练');

		await state.cancelProposal('proposal-1');
		const confirmAfterCancel = await state.confirmProposal('proposal-1');

		expect(cancel).toHaveBeenCalledWith('proposal-1', 'hash-abc', expect.anything());
		expect(confirm).not.toHaveBeenCalled();
		expect(confirmAfterCancel).toBeNull();
		expect(state.proposals.value[0]?.status).toBe('CANCELLED');
		expect(state.proposalNotice.value).toContain('不会产生任何业务变更');
		expect(state.proposalError.value).toContain('不能再次确认');
	});

	it('refuses to confirm an expired proposal before calling the backend', async () => {
		vi.useFakeTimers();
		vi.setSystemTime(new Date('2026-10-20T09:00:00Z'));
		const expired = proposal({
			createdAt: '2026-10-20T08:00:00Z',
			expiresAt: '2026-10-20T08:30:00Z',
		});
		const confirm = vi.fn(async () => proposal({ status: 'CONFIRMED' }));
		const api = apiWith({ proposals: vi.fn(async () => [expired]), confirm });
		const state = useSupplyChainDecision(api, { wait: async () => undefined });
		await state.run('启动方案 plan-1 的沙箱演练');

		const result = await state.confirmProposal('proposal-1');

		expect(result).toBeNull();
		expect(confirm).not.toHaveBeenCalled();
		expect(state.proposalError.value).toContain('超过确认时限');
	});

	it('loads a historical DECISION task and refreshes its proposals from the server', async () => {
		const api = apiWith({
			result: vi.fn(async () => decisionResult({ taskId: 'task-9', intent: 'LOOKUP_PLAN' })),
			proposals: vi.fn(async () => [proposal({ taskId: 'task-9', status: 'CANCELLED' })]),
		});
		const state = useSupplyChainDecision(api, { wait: async () => undefined });

		const result = await state.openTask('task-9');

		expect(api.result).toHaveBeenCalledWith('task-9');
		expect(result?.intent).toBe('LOOKUP_PLAN');
		expect(state.hasPendingProposals.value).toBe(false);
		expect(state.proposals.value[0]?.status).toBe('CANCELLED');
	});

	it('loads decision history with status filters and pagination', async () => {
		const history = vi.fn(async () => historyPage([historyTask()]));
		const api = apiWith({ history });
		const state = useSupplyChainDecision(api, { wait: async () => undefined });

		await state.loadHistory();
		await state.setHistoryStatus('COMPLETED');

		expect(history).toHaveBeenLastCalledWith({ status: 'COMPLETED', page: 1, pageSize: 5 });
		expect(state.history.value).toHaveLength(1);
		expect(state.historyTotalElements.value).toBe(1);
	});
});
