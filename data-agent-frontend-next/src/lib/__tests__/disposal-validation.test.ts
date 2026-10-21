import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const disposal = readFileSync('src/app/supply-chain/disposal/DisposalView.tsx', 'utf8');

describe('disposal comparison validation', () => {
	it('requires the same scope fields as the backend before submitting', () => {
		expect(disposal).toContain("comparisonScenario === 'INVENTORY_REPLENISHMENT' && !comparisonSku.trim()");
		expect(disposal).toContain("comparisonScenario === 'SUPPLIER_DELAY' && !comparisonSupplier.trim()");
		expect(disposal).toContain("toast.warning('请输入 SKU')");
		expect(disposal).toContain("toast.warning('请输入供应商 ID')");
	});

	it('labels the required comparison scope explicitly', () => {
		expect(disposal).toContain('SKU（必填）');
		expect(disposal).toContain('供应商 ID（必填）');
	});

	it('disables candidate plan saving while a save is in flight', () => {
		expect(disposal).toContain('const [candidateSaveBusy, setCandidateSaveBusy]');
		expect(disposal).toContain('if (candidateSaveBusy) return;');
		expect(disposal).toContain('disabled={savingPlan || Boolean(candidate?.linkedPlanId)}');
		expect(disposal).toContain("savingPlan ? '保存中…' : '保存为方案'");
	});
});
