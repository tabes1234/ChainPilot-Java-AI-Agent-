import { existsSync, readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { NAV_ITEMS } from '@/lib/navigation';

const demoPage = readFileSync('src/app/demo/page.tsx', 'utf8');

const REAL_ROUTE_PREFIXES = [
	'/',
	'/demo',
	'/supply-chain',
	'/agent-evaluation',
	'/agent-observability',
	'/system/model-config',
];

const HREF_PATTERN = /\thref: '([^']+)'/g;

function collectCardHrefs(): string[] {
	const hrefs: string[] = [];
	for (const match of demoPage.matchAll(HREF_PATTERN)) {
		hrefs.push(match[1]);
	}
	return hrefs;
}

describe('demo studio function cards', () => {
	it('routes every demo card to a real business page', () => {
		const hrefs = collectCardHrefs();
		expect(hrefs.length).toBeGreaterThanOrEqual(9);
		for (const href of hrefs) {
			expect(href.startsWith('/')).toBe(true);
			expect(
				REAL_ROUTE_PREFIXES.some(
					(prefix) => href === prefix || href.startsWith(`${prefix}?`) || href.startsWith(`${prefix}/`),
				),
			).toBe(true);
		}
	});

	it('covers the core demo features with names and descriptions', () => {
		for (const name of [
			'供应链风险分析',
			'情景模拟',
			'自然语言决策',
			'处置与沙箱',
			'历史任务',
			'工作流评测 · OFFLINE',
			'观察与分析',
			'Agent 评测中心',
			'工作流评测 · AGENT 模式',
		]) {
			expect(demoPage).toContain(`name: '${name}'`);
		}
		expect(demoPage).toContain('探索供应链风险分析、情景模拟与工作流评测');
	});

	it('presents availability honestly with three tiers', () => {
		expect(demoPage).toContain('完整可运行');
		expect(demoPage).toContain('部分可用');
		expect(demoPage).toContain('暂不可运行');
		expect(demoPage).toContain('不提供伪装的可运行入口，也不伪造模型结果');
	});

	it('resolves AGENT readiness from the live backend instead of hardcoding success', () => {
		const agent = demoPage.includes("id: 'workflow-agent'");
		expect(agent).toBe(true);
		expect(demoPage).toContain('dynamicAgentReadiness');
		expect(demoPage).toContain('fetchEvaluationCapabilities');
		expect(demoPage).toContain('agentEvaluationReady');
		expect(demoPage).toContain('agentEvaluationBlockReason');
		expect(demoPage).not.toContain('AGENT 已成功运行');
	});
});

describe('demo studio cleanup of legacy machinery', () => {
	it('removes the old route/checklist/progress implementation', () => {
		expect(existsSync('src/lib/demo/routes.ts')).toBe(false);
		expect(existsSync('src/lib/demo/capabilities.ts')).toBe(false);
		expect(demoPage).not.toContain('DEMO_ROUTES');
		expect(demoPage).not.toContain('DEMO_CHECKLIST');
		expect(demoPage).not.toContain('chainpilot.demo.progress.v1');
		expect(demoPage).not.toContain('chainpilot.demo.checklist.v1');
		expect(demoPage).not.toContain('演示准备清单');
		expect(demoPage).not.toContain('localStorage');
	});

	it('never auto-runs business workflows from the studio', () => {
		expect(demoPage).not.toContain('startEvaluationRun');
		expect(demoPage).not.toContain('submitTask');
		expect(demoPage).not.toContain('createSimulation');
	});

	it('keeps the 项目演示 entry in global navigation', () => {
		const navItem = NAV_ITEMS.find((item) => item.href === '/demo');
		expect(navItem?.label).toBe('项目演示');
	});
});
