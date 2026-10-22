import { beforeEach, describe, expect, it, vi } from 'vitest';

async function loadDemoMode(demoEnabled: boolean) {
	vi.resetModules();
	vi.stubEnv('NEXT_PUBLIC_CHAINPILOT_DEMO_MODE', demoEnabled ? 'true' : '');
	return await import('../demo-mode');
}

beforeEach(() => {
	vi.unstubAllEnvs();
});

describe('demo mode closed routes', () => {
	it('keeps every route open when the flag is absent', async () => {
		const mod = await loadDemoMode(false);
		expect(mod.DEMO_MODE_ENABLED).toBe(false);
		expect(mod.demoClosedFeature('/chat')).toBeNull();
		expect(mod.demoClosedFeature('/knowledge/agents')).toBeNull();
		expect(mod.demoClosedFeature('/agent/new')).toBeNull();
		expect(mod.visibleNavItems([{ href: '/chat' }, { href: '/' }])).toHaveLength(2);
	});

	it('closes exactly the listed routes in demo mode', async () => {
		const mod = await loadDemoMode(true);
		expect(mod.DEMO_MODE_ENABLED).toBe(true);
		expect(mod.demoClosedFeature('/chat')).toBe('数据问答');
		expect(mod.demoClosedFeature('/knowledge/business')).toBe('业务术语');
		expect(mod.demoClosedFeature('/knowledge/agents')).toBe('智能体知识');
		expect(mod.demoClosedFeature('/system/agents')).toBe('智能体管理');
		expect(mod.demoClosedFeature('/system/data-sources')).toBe('数据源管理');
		expect(mod.demoClosedFeature('/prompt-config')).toBe('提示词配置');
		expect(mod.demoClosedFeature('/agent')).toBe('智能体详情');
		expect(mod.demoClosedFeature('/agent/new')).toBe('新建智能体');
		expect(mod.demoClosedFeature('/dashboard')).toBe('数据看板');
	});

	it('keeps every demo open feature reachable', async () => {
		const mod = await loadDemoMode(true);
		const openRoutes = [
			'/',
			'/supply-chain',
			'/supply-chain/decision',
			'/supply-chain/disposal',
			'/supply-chain/history',
			'/agent-evaluation',
			'/agent-observability',
			'/system/model-config',
			'/feature-preparing',
		];
		for (const route of openRoutes) {
			expect(mod.demoClosedFeature(route)).toBeNull();
		}
	});

	it('does not treat /agent-evaluation as the closed /agent route', async () => {
		const mod = await loadDemoMode(true);
		expect(mod.demoClosedFeature('/agent-evaluation')).toBeNull();
		expect(mod.demoClosedFeature('/agent')).toBe('智能体详情');
	});

	it('matches the most specific closed prefix', async () => {
		const mod = await loadDemoMode(true);
		expect(mod.demoClosedFeature('/knowledge')).toBe('知识库');
		expect(mod.demoClosedFeature('/knowledge/agents')).toBe('智能体知识');
		expect(mod.demoClosedFeature('/knowledge/semantic-models')).toBe('语义模型');
	});

	it('handles null and unknown paths without closing anything', async () => {
		const mod = await loadDemoMode(true);
		expect(mod.demoClosedFeature(null)).toBeNull();
		expect(mod.demoClosedFeature('/not-a-route')).toBeNull();
	});

	it('builds an encoded notice href', async () => {
		const mod = await loadDemoMode(true);
		expect(mod.demoNoticeHref('数据问答')).toBe('/feature-preparing?feature=%E6%95%B0%E6%8D%AE%E9%97%AE%E7%AD%94');
	});

	it('filters navigation entries in demo mode while keeping open features', async () => {
		const mod = await loadDemoMode(true);
		const items = [
			{ href: '/', label: '首页' },
			{ href: '/supply-chain', label: '供应链分析' },
			{ href: '/chat', label: '数据问答' },
			{ href: '/system/model-config', label: '模型配置' },
			{ href: '/agent-evaluation', label: '评测中心' },
			{ href: '/knowledge/business', label: '业务术语' },
			{ href: '/agent/new', label: '新建智能体' },
		];
		const visible = mod.visibleNavItems(items);
		expect(visible.map((item: { href: string }) => item.href)).toEqual([
			'/',
			'/supply-chain',
			'/system/model-config',
			'/agent-evaluation',
		]);
	});

	it('keeps the unified notice copy free of failure wording', async () => {
		const mod = await loadDemoMode(true);
		const copy = [mod.DEMO_NOTICE_TITLE, ...mod.DEMO_NOTICE_BODY, mod.DEMO_NOTICE_RETURN_LABEL].join('\n');
		for (const banned of ['异常', '失败', '错误', '接口', '500', '404']) {
			expect(copy).not.toContain(banned);
		}
		expect(mod.DEMO_NOTICE_TITLE).toBe('这段旅程，暂时还在准备中');
		expect(mod.DEMO_NOTICE_RETURN_LABEL).toBe('返回已开放功能');
	});
});
