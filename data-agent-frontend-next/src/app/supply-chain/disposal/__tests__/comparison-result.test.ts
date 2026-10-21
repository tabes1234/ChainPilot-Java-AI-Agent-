// @vitest-environment jsdom
import { act, createElement } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ComparisonResult } from '@/app/supply-chain/disposal/DisposalView';
import type { ScenarioComparison } from '@/lib/api/supplyChain';

const comparison: ScenarioComparison = {
	comparisonId: 'comparison-1',
	title: '候选方案对比',
	scenario: 'INVENTORY_REPLENISHMENT',
	sku: 'SKU-MCU-001',
	candidates: [
		{
			candidateId: 'candidate-1',
			label: '方案 A',
			position: 1,
			scenario: 'INVENTORY_REPLENISHMENT',
			replenishmentQuantity: 50,
		},
	],
	rankings: [{ candidateId: 'candidate-1', label: '方案 A', rank: 1, recommended: true }],
	realDataMutated: false,
};

describe('ComparisonResult save action', () => {
	let root: Root | null = null;

	beforeEach(() => {
		(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
	});

	afterEach(() => {
		act(() => root?.unmount());
		root = null;
	});

	function render(savingPlan: boolean, linkedPlanId?: string) {
		const container = document.createElement('div');
		root = createRoot(container);
		const data = {
			...comparison,
			candidates: [{ ...comparison.candidates[0], linkedPlanId: linkedPlanId ?? null }],
		};
		act(() => {
			root!.render(createElement(ComparisonResult, {
				comparison: data,
				savingPlan,
				onSavePlan: vi.fn(),
			}));
		});
		return container;
	}

	it('disables the save button while a candidate save is in flight', () => {
		const container = render(true);
		const button = container.querySelector('button');
		expect(button).not.toBeNull();
		expect(button!.disabled).toBe(true);
		expect(button!.textContent).toContain('保存中');
	});

	it('keeps the button disabled after the candidate is linked', () => {
		const container = render(false, 'plan-1');
		expect(container.querySelector('button')!.disabled).toBe(true);
	});

	it('enables one save action when the candidate is not linked', () => {
		const container = render(false);
		const button = container.querySelector('button');
		expect(button).not.toBeNull();
		expect(button!.disabled).toBe(false);
		expect(button!.textContent).toContain('保存为方案');
	});
});
