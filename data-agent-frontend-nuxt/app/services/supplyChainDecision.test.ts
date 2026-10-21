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
import {
	bindDecisionProposalContentHash,
	cancelDecisionProposal,
	confirmDecisionProposal,
	decisionIntentLabel,
	DECISION_HISTORY_STATUS_OPTIONS,
	fetchDecisionHistory,
	fetchDecisionProposal,
	fetchDecisionProposals,
	fetchDecisionTaskResult,
	fetchDecisionTaskStatus,
	isProposalExpired,
	narrativeSourceColor,
	narrativeSourceLabel,
	parameterLabel,
	proposalActionLabel,
	proposalStatusColor,
	proposalStatusLabel,
	submitDecisionTask,
	type DecisionProposal,
} from './supplyChainDecision';

function proposal(overrides: Partial<DecisionProposal> = {}): DecisionProposal {
	return {
		proposalId: 'proposal-1',
		taskId: 'task-1',
		actionType: 'START_SANDBOX_EXECUTION',
		title: '启动方案方案-1的沙箱演练',
		targetId: 'plan-1',
		parameters: { recommendationId: 'rec-1' },
		evidence: ['候选 CAND-100 排名 1'],
		contentHash: 'hash-abc',
		status: 'PENDING',
		createdAt: '2026-10-20T08:00:00Z',
		expiresAt: '2026-10-20T08:30:00Z',
		confirmedAt: null,
		result: null,
		errorMessage: null,
		...overrides,
	};
}

afterEach(() => {
	vi.useRealTimers();
	vi.unstubAllGlobals();
});

describe('supplyChainDecision service', () => {
	it('labels intents, proposals and narrative sources for the decision workspace', () => {
		expect(decisionIntentLabel('COMPARE_REPLENISHMENT')).toBe('补货方案对比');
		expect(decisionIntentLabel('PROPOSE_START_SANDBOX')).toBe('建议启动沙箱演练');
		expect(decisionIntentLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
		expect(proposalActionLabel('START_SANDBOX_EXECUTION')).toBe('启动沙箱演练（仅沙箱）');
		expect(proposalActionLabel('SAVE_CANDIDATE_AS_PLAN')).toBe('保存候选为处置方案');
		expect(proposalStatusLabel('PENDING')).toBe('待确认');
		expect(proposalStatusLabel('CONFIRMED')).toBe('已确认并执行');
		expect(proposalStatusColor('CONFIRMED')).toBe('success');
		expect(proposalStatusColor('FAILED')).toBe('error');
		expect(proposalStatusColor('PENDING')).toBe('warning');
		expect(narrativeSourceLabel('MODEL_GROUNDED')).toBe('模型解释 · 基于真实工具结果');
		expect(narrativeSourceLabel('DETERMINISTIC')).toBe('规则摘要 · 未调用模型');
		expect(narrativeSourceLabel('DETERMINISTIC_FALLBACK')).toBe('规则降级 · 模型不可用');
		expect(narrativeSourceColor('MODEL_GROUNDED')).toBe('success');
		expect(narrativeSourceColor('DETERMINISTIC_FALLBACK')).toBe('warning');
		expect(parameterLabel(100)).toBe('100');
		expect(parameterLabel(null)).toBe('—');
		expect(parameterLabel({ comparisonId: 'cmp-1' })).toBe('{"comparisonId":"cmp-1"}');
		expect(DECISION_HISTORY_STATUS_OPTIONS.map((option) => option.value)).toEqual([
			'ALL',
			'RUNNING',
			'COMPLETED',
			'FAILED',
		]);
	});

	it('binds confirmation to the exact proposal content hash', () => {
		const bound = bindDecisionProposalContentHash(proposal());
		expect(bound.contentHash).toBe('hash-abc');
		expect(Object.isFrozen(bound)).toBe(true);
		expect(() => bindDecisionProposalContentHash(proposal({ contentHash: '  ' }))).toThrow(
			'提案缺少内容哈希',
		);
	});

	it('rejects a confirmation whose content hash changed before submission', async () => {
		const bound = bindDecisionProposalContentHash(proposal());
		await expect(
			confirmDecisionProposal('proposal-1', proposal({ contentHash: 'hash-changed' }).contentHash, bound),
		).rejects.toThrow('提案内容已变化');
	});

	it('computes proposal expiry only for pending proposals', () => {
		vi.useFakeTimers();
		vi.setSystemTime(new Date('2026-10-20T08:15:00Z'));
		expect(isProposalExpired(proposal())).toBe(false);
		vi.setSystemTime(new Date('2026-10-20T08:31:00Z'));
		expect(isProposalExpired(proposal())).toBe(true);
		expect(isProposalExpired(proposal({ status: 'CONFIRMED' }))).toBe(false);
	});

	it('wires decision task and proposal calls to the real backend endpoints', async () => {
		const fetchMock = vi.fn(async () => ({}));
		vi.stubGlobal('$fetch', fetchMock);
		const bound = bindDecisionProposalContentHash(proposal({ proposalId: 'proposal/1' }));

		await submitDecisionTask('  比较 SKU-MCU-001 补货 50、100、200 件  ');
		await fetchDecisionTaskStatus('task/1');
		await fetchDecisionTaskResult('task/1');
		await fetchDecisionProposals('task/1');
		await fetchDecisionProposal('proposal/1');
		await confirmDecisionProposal('proposal/1', 'hash-abc', bound);
		await cancelDecisionProposal('proposal/1', 'hash-abc', bound);

		expect(fetchMock).toHaveBeenNthCalledWith(1, '/api/supply-chain/analysis/tasks', {
			method: 'POST',
			body: { question: '比较 SKU-MCU-001 补货 50、100、200 件', mode: 'DECISION' },
		});
		expect(fetchMock).toHaveBeenNthCalledWith(2, '/api/supply-chain/analysis/tasks/task%2F1');
		expect(fetchMock).toHaveBeenNthCalledWith(3, '/api/supply-chain/analysis/tasks/task%2F1/decision');
		expect(fetchMock).toHaveBeenNthCalledWith(4, '/api/supply-chain/action-proposals', {
			query: { taskId: 'task/1' },
		});
		expect(fetchMock).toHaveBeenNthCalledWith(5, '/api/supply-chain/action-proposals/proposal%2F1');
		expect(fetchMock).toHaveBeenNthCalledWith(
			6,
			'/api/supply-chain/action-proposals/proposal%2F1/confirm',
			{ method: 'POST', body: { contentHash: 'hash-abc' } },
		);
		expect(fetchMock).toHaveBeenNthCalledWith(
			7,
			'/api/supply-chain/action-proposals/proposal%2F1/cancel',
			{ method: 'POST', body: { contentHash: 'hash-abc' } },
		);
	});

	it('loads decision history with the DECISION mode filter and pagination', async () => {
		const rawMock = vi.fn(async () => ({
			_data: [],
			headers: new Headers({
				'x-page': '1',
				'x-page-size': '5',
				'x-total-count': '0',
				'x-total-pages': '0',
			}),
		}));
		vi.stubGlobal('$fetch', { raw: rawMock });

		const page = await fetchDecisionHistory({ status: 'COMPLETED', page: 1, pageSize: 5 });

		expect(rawMock).toHaveBeenCalledWith('/api/supply-chain/analysis/tasks', {
			query: { page: 1, pageSize: 5, status: 'COMPLETED', mode: 'DECISION' },
		});
		expect(page.totalElements).toBe(0);
	});
});
