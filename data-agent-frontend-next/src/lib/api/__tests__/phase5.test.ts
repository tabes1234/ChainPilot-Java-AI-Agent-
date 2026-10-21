import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const css = readFileSync('src/app/globals.css', 'utf8');
const home = readFileSync('src/app/page.tsx', 'utf8');
const report = readFileSync('src/components/supply-chain/report-view.tsx', 'utf8');
const evidence = readFileSync('src/components/supply-chain/risk-evidence.tsx', 'utf8');
const decision = readFileSync('src/components/supply-chain/decision-view.tsx', 'utf8');
const disposal = readFileSync('src/app/supply-chain/disposal/DisposalView.tsx', 'utf8');
const recommendation = readFileSync('src/components/supply-chain/recommendation-details.tsx', 'utf8');

describe('home slogan motion', () => {
	it('keeps the full phrase accessible while animating phrase spans with CSS only', () => {
		expect(home).toContain('aria-label="让供应链风险可解释，让处置决策可追溯。"');
		expect(home).toContain('home-slogan__phrase');
		expect(home).toContain("'让供应链风险'");
		expect(home).toContain("'让处置决策'");
		expect(home).not.toContain('setInterval(');
		expect(css).toMatch(/@keyframes home-slogan-in[\s\S]*?filter: blur\(6px\)[\s\S]*?translateY\(14px\)/);
		expect(css).toMatch(/@keyframes home-slogan-sheen/);
	});

	it('disables the slogan motion for reduced-motion users', () => {
		expect(css).toMatch(/prefers-reduced-motion: reduce[\s\S]*?\.home-slogan__phrase \{ animation: none; opacity: 1; filter: none; transform: none; \}/);
	});
});

describe('project intro colour motion', () => {
	it('animates the whole intro paragraph with a CSS gradient and keeps the copy intact', () => {
		expect(home).toContain('home-intro');
		expect(home.match(/home-intro__key/g)).toHaveLength(2);
		expect(home).toContain('发现风险');
		expect(home).toContain('确认处置');
		expect(css).toMatch(/\.home-intro \{[\s\S]*?color: #405d78;[\s\S]*?\}/);
		expect(css).toMatch(/@keyframes home-intro-flow/);
		expect(css).toMatch(/background-clip: text/);
		expect(css).toMatch(/animation: home-intro-flow 22s/);
		expect(css).toContain('.home-intro::selection');
		expect(home).not.toContain('setInterval(');
	});

	it('keeps a readable static colour when reduced motion is requested', () => {
		expect(css).toMatch(/prefers-reduced-motion: reduce[\s\S]*?\.home-intro \{[\s\S]*?animation: none;[\s\S]*?background-image: none;[\s\S]*?color: #405d78;/);
	});
});

describe('risk evidence presentation', () => {
	it('renders observed values, expected values, rules, source and score contribution', () => {
		for (const text of ['观测值', '期望', '规则', '来源', 'scoreContribution']) {
			expect(evidence).toContain(text);
		}
		expect(report).toContain('RiskEvidencePanel');
		expect(evidence).toContain('业务数值与规则证据');
		expect(evidence).toContain('数据不足');
		expect(report).toContain('风险分由 Java 确定性规则计算');
	});

	it('shows risk evidence in the baseline order table and decision result', () => {
		const analysis = readFileSync('src/app/supply-chain/AnalysisView.tsx', 'utf8');
		expect(analysis).toContain('RiskEvidencePanel');
		expect(analysis).toContain('expandedOrder');
		expect(decision).toContain('风险依据');
		expect(decision).toContain('RiskEvidencePanel');
	});
});

describe('model narrative presentation', () => {
	it('marks the effective narrative source and renders validated evidence references', () => {
		expect(report).toContain('modelNarrative');
		expect(report).toContain('narrativeSource');
		expect(report).toContain('模型解释已校验');
		expect(report).toContain('叙述来源');
		expect(report).toContain('自动降级');
		expect(report).toContain('旧版模型摘要');
		expect(report).toContain('未经证据 ID 与数值校验');
		expect(report).toContain('evidenceIds');
		expect(report).toContain('逐字来自规则建议');
		expect(report).toContain('不确定性与数据缺口');
	});
});

describe('scenario simulation evidence and data gaps', () => {
	it('surfaces the real baseline, evidence changes and remaining gaps in the disposal workspace', () => {
		expect(disposal).toContain('基线风险与证据');
		expect(disposal).toContain('可模拟对象');
		expect(disposal).toContain('候选证据变化与剩余数据缺口');
		expect(decision).toContain('发生变化的风险证据');
		expect(decision).toContain('仍然存在的数据缺口');
		expect(decision).toContain('simulation.ruleVersion');
	});
});

describe('decision and disposal traceability', () => {
	it('separates proposals, plan and sandbox execution with timestamps and links', () => {
		expect(decision).toContain('处置追溯链');
		expect(decision).toContain('生成建议不等于执行');
		expect(decision).toContain('proposal.createdAt');
		expect(decision).toContain('proposal.confirmedAt');
		expect(decision).toContain('decision.execution.startedAt');
		expect(decision).toContain('decision.execution.realDataMutated');
		expect(decision).toContain('查看处置方案与执行记录');
	});

	it('reuses recommendation business basis and flags simulated execution', () => {
		expect(recommendation).toContain('businessBasis');
		expect(recommendation).toContain('prerequisites');
		expect(recommendation).toContain('assumptions');
		expect(recommendation).toContain('sideEffects');
		expect(decision).toContain('RecommendationDetails');
		expect(disposal).toContain('RecommendationDetails');
		expect(disposal).toContain('plan.taskId');
		expect(disposal).toContain('真实数据未变更');
		expect(disposal).toContain('planOverride === undefined ? initialPlanId : planOverride');
	});
});
