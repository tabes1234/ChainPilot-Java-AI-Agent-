import { afterEach, describe, expect, it, vi } from 'vitest';
import { fetchTasks, confirmProposal, fetchSimulationBaseline } from '@/lib/api/supplyChain';

afterEach(() => {
	vi.unstubAllGlobals();
});

describe('supply chain task pagination', () => {
	it('reads page metadata from the backend response headers', async () => {
		vi.stubGlobal(
			'fetch',
			vi.fn(async () => new Response(JSON.stringify([{ taskId: 't1', question: 'q', status: 'COMPLETED' }]), {
				status: 200,
				headers: {
					'content-type': 'application/json',
					'x-total-count': '23',
					'x-page': '2',
					'x-page-size': '8',
					'x-total-pages': '3',
				},
			})),
		);

		const page = await fetchTasks({ page: 2, pageSize: 8, mode: 'ANALYSIS' });
		expect(page).toMatchObject({ total: 23, page: 2, pageSize: 8, totalPages: 3 });
		expect(page.items[0]).toMatchObject({ taskId: 't1', status: 'COMPLETED' });
	});

	it('throws when the task list request fails', async () => {
		vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status: 500 })));
		await expect(fetchTasks({ page: 1 })).rejects.toThrow('加载任务列表失败（HTTP 500）');
	});
});

describe('proposal confirmation', () => {
	it('posts the server-issued contentHash', async () => {
		let body: unknown = null;
		vi.stubGlobal(
			'fetch',
			vi.fn(async (_input: string, init?: RequestInit) => {
				body = JSON.parse(String(init?.body));
				return new Response(JSON.stringify({ proposalId: 'p1', status: 'CONFIRMED' }), {
					status: 200,
					headers: { 'content-type': 'application/json' },
				});
			}),
		);

		const result = await confirmProposal('p1', 'hash-123');
		expect(body).toEqual({ contentHash: 'hash-123' });
		expect(result.status).toBe('CONFIRMED');
	});
});

describe('scenario simulation baseline', () => {
	it('calls the read-only baseline endpoint and returns its snapshot contract', async () => {
		let requestedUrl = '';
		vi.stubGlobal(
			'fetch',
			vi.fn(async (input: string) => {
				requestedUrl = String(input);
				return new Response(JSON.stringify({
					baselineSource: 'CURRENT_BUSINESS_DATA',
					ruleVersion: 'RISK_RULES_V1',
					simulationStatus: 'SIMULATED',
					metrics: { totalOrders: 4, atRiskOrders: 3, highRiskOrders: 3, ordersWithShortage: 3, totalShortageUnits: 330, totalRiskScore: 297 },
					assessments: [],
					orders: [],
					inventory: [],
					suppliers: [],
					dataGaps: [],
				}), { status: 200, headers: { 'content-type': 'application/json' } });
			}),
		);

		const baseline = await fetchSimulationBaseline();
		expect(requestedUrl).toContain('/api/supply-chain/disposal-simulations/baseline');
		expect(baseline).toMatchObject({ ruleVersion: 'RISK_RULES_V1', simulationStatus: 'SIMULATED' });
	});
});
