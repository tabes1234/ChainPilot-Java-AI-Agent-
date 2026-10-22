import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { NAV_ITEMS } from '@/lib/navigation';

const home = readFileSync('src/app/page.tsx', 'utf8');
const report = readFileSync('src/components/supply-chain/report-view.tsx', 'utf8');
const evidence = readFileSync('src/components/supply-chain/risk-evidence.tsx', 'utf8');
const decision = readFileSync('src/components/supply-chain/decision-view.tsx', 'utf8');
const disposal = readFileSync('src/app/supply-chain/disposal/DisposalView.tsx', 'utf8');
const recommendation = readFileSync('src/components/supply-chain/recommendation-details.tsx', 'utf8');
const simulationDrill = readFileSync('src/app/supply-chain/simulation/page.tsx', 'utf8');
const workflowDrill = readFileSync('src/app/supply-chain/workflow-evaluation/page.tsx', 'utf8');

describe('home hero copy', () => {
	it('opens with the brand name, motto and three-paragraph introduction', () => {
		expect(home).toContain('ChainPilot');
		expect(home).toContain('让供应链风险可解释，让处置决策可追溯');
		expect(home).toContain('供应链风险分析与 Agent 工作流平台');
		expect(home).toContain('模型输出不会被当作业务事实');
		expect(home).toContain('不修改真实业务数据');
		expect(home).not.toContain('setInterval(');
	});

	it('keeps exactly two hero entries: demo studio and the architecture anchor', () => {
		expect(home).toContain('href="/demo"');
		expect(home).toContain('href="#tech"');
		expect(home).toContain('项目演示');
		expect(home).toContain('了解技术架构');
	});

	it('presents capabilities, architecture, tech stack and engineering value', () => {
		expect(home).toContain('核心能力');
		expect(home).toContain('技术架构');
		expect(home).toContain('技术栈');
		expect(home).toContain('项目特色与工程价值');
		expect(home).toContain('评测断言由后端独立重算');
	});

	it('removes business console content from the home page', () => {
		expect(home).not.toContain('当前供应链风险概览');
		expect(home).not.toContain('高风险订单');
		expect(home).not.toContain('最近任务');
		expect(home).not.toContain('fetchOrders');
		expect(home).not.toContain('fetchTasks');
		expect(home).not.toContain('/supply-chain?demo=supplier-delay');
		expect(home).not.toContain('开始风险分析');
		expect(home).not.toContain('进入演示');
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
		expect(decision).toContain('任务与审计详情');
		expect(decision).toContain('排名第一不等于业务授权');
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

describe('simulation drill page', () => {
	it('runs real backend simulation and comparison APIs instead of static data', () => {
		expect(simulationDrill).toContain('createSimulation(');
		expect(simulationDrill).toContain('createComparison(');
		expect(simulationDrill).toContain('fetchSimulationBaseline');
		expect(simulationDrill).not.toContain('mock');
	});

	it('states the drill isolation boundary and keeps results backend-driven', () => {
		expect(simulationDrill).toContain('演练模式 · 使用模拟数据 · 不修改真实业务数据');
		expect(simulationDrill).toContain('不会自动创建提案、确认方案或执行沙箱');
		expect(simulationDrill).toContain('result.baseline?.atRiskOrders');
		expect(simulationDrill).toContain('result.delta?.totalShortageUnits');
		expect(NAV_ITEMS.some((item) => item.href === '/supply-chain/simulation')).toBe(true);
		expect(home).not.toContain('/supply-chain/simulation');
	});
});

describe('workflow evaluation drill page', () => {
	it('reuses the real backend workflow and evaluation run APIs', () => {
		expect(workflowDrill).toContain('useAnalysisTask(');
		expect(workflowDrill).toContain('startEvaluationRun(');
		expect(workflowDrill).toContain('fetchEvaluationRun(');
		expect(workflowDrill).toContain("refetchInterval: (query) => (query.state.data?.status === 'RUNNING' ? 2500 : false)");
		expect(workflowDrill).not.toContain('mock');
	});

	it('keeps the pass verdict backend-computed and states the isolation boundary', () => {
		expect(workflowDrill).toContain('不修改真实供应链业务数据');
		expect(workflowDrill).toContain('通过/失败由后端评测断言计算');
		expect(workflowDrill).toContain('不会自动确认提案或执行沙箱');
		expect(workflowDrill).toContain('assertion.passed');
		expect(workflowDrill).toContain('重新演练');
	});
});
