import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { DEMO_SCENARIO, isDemoScenario } from '@/lib/demo-scenario';

const home = readFileSync('src/app/page.tsx', 'utf8');
const analysis = readFileSync('src/app/supply-chain/AnalysisView.tsx', 'utf8');
const decision = readFileSync('src/app/supply-chain/decision/DecisionWorkspaceView.tsx', 'utf8');
const disposal = readFileSync('src/app/supply-chain/disposal/DisposalView.tsx', 'utf8');

describe('guided demo scenario', () => {
	it('defines a concrete supplier-delay scenario with a decision question', () => {
		expect(isDemoScenario('supplier-delay')).toBe(true);
		expect(isDemoScenario('1')).toBe(true);
		expect(isDemoScenario(null)).toBe(false);
		expect(DEMO_SCENARIO.question).toContain('SUP-ALPHA');
		expect(DEMO_SCENARIO.question).toContain('SKU-MCU-001');
		expect(DEMO_SCENARIO.decisionQuestion).toContain('补货 50、100、200 件');
		expect(DEMO_SCENARIO.comparisonCandidates).toEqual(['50', '100', '200']);
		expect(DEMO_SCENARIO.simulationSku).toBe('SKU-MCU-001');
	});

	it('keeps the home page brand-only while workbench pages stay wired', () => {
		expect(home).not.toContain('/supply-chain?demo=supplier-delay');
		expect(home).not.toContain('示例场景可一键运行');
		expect(analysis).toContain('DemoBanner');
		expect(analysis).toContain('继续：方案决策');
		expect(analysis).toContain('DEMO_SCENARIO.question');
		expect(decision).toContain('DemoBanner');
		expect(decision).toContain('DEMO_SCENARIO.decisionQuestion');
		expect(decision).toContain('sourceTask');
		expect(disposal).toContain('DemoBanner');
		expect(disposal).toContain('DEMO_SCENARIO.simulationSku');
		expect(disposal).toContain('DEMO_SCENARIO.comparisonCandidates');
		expect(disposal).not.toContain('localStorage');
	});
});
