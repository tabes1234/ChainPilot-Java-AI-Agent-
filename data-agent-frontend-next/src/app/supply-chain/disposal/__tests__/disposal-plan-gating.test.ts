// @vitest-environment jsdom
import { act, createElement } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
	fetchComparisons,
	fetchEligibility,
	fetchExecutions,
	fetchPlan,
	fetchPlans,
	fetchRecommendations,
	fetchSimulationBaseline,
} from '@/lib/api/supplyChain';
import DisposalView from '@/app/supply-chain/disposal/DisposalView';

vi.mock('next/link', () => ({
	default: ({ href, children }: { href: string; children: React.ReactNode }) => createElement('a', { href }, children),
}));

vi.mock('@/lib/api/supplyChain', async (importOriginal) => {
	const actual = await importOriginal<typeof import('@/lib/api/supplyChain')>();
	return {
		...actual,
		fetchPlan: vi.fn(),
		fetchEligibility: vi.fn(),
		fetchExecutions: vi.fn(),
		fetchRecommendations: vi.fn(),
		fetchSimulationBaseline: vi.fn(),
		fetchPlans: vi.fn(),
		fetchComparisons: vi.fn(),
	};
});

describe('disposal plan missing-resource gating', () => {
	let root: Root | null = null;

	beforeEach(() => {
		(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
		window.history.replaceState({}, '', '/supply-chain/disposal?planId=missing-plan');
		vi.mocked(fetchPlan).mockRejectedValue({ status: 404, message: 'HTTP 404', detail: 'Unknown disposal plan' });
		vi.mocked(fetchRecommendations).mockResolvedValue([]);
		vi.mocked(fetchSimulationBaseline).mockReturnValue(new Promise(() => {}) as never);
		vi.mocked(fetchPlans).mockResolvedValue({ items: [], page: 1, pageSize: 5, total: 0, totalPages: 0 });
		vi.mocked(fetchComparisons).mockResolvedValue({ items: [], page: 1, pageSize: 5, total: 0, totalPages: 0 });
		vi.mocked(fetchEligibility).mockResolvedValue({} as never);
		vi.mocked(fetchExecutions).mockResolvedValue({ items: [], page: 1, pageSize: 10, total: 0, totalPages: 0 });
	});

	afterEach(() => {
		act(() => root?.unmount());
		root = null;
		vi.clearAllMocks();
	});

	it('shows a friendly plan error and does not request eligibility or executions', async () => {
		const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
		const container = document.createElement('div');
		root = createRoot(container);
		await act(async () => {
			root!.render(createElement(QueryClientProvider, { client }, createElement(DisposalView)));
		});
		for (let index = 0; index < 40 && !container.textContent?.includes('方案不存在或已被清理'); index += 1) {
			await act(async () => {
				await new Promise((resolve) => setTimeout(resolve, 50));
			});
		}

		expect(fetchPlan).toHaveBeenCalledTimes(1);
		expect(fetchEligibility).not.toHaveBeenCalled();
		expect(fetchExecutions).not.toHaveBeenCalled();
		expect(container.textContent).toContain('方案不存在或已被清理');
	});
});
