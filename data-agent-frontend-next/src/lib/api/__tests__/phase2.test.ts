import { readFileSync } from 'node:fs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { PAGE_THEMES, navGroupTint, resolvePageTheme } from '@/lib/page-theme';
import { HOME_DESIGN_INTENT, HOME_FEATURE_GROUPS } from '@/lib/home-content';
import { fetchPresetQuestions, savePresetQuestions } from '@/lib/api/agent';
import { clearChatSessions } from '@/lib/api/chat';

afterEach(() => {
	vi.unstubAllGlobals();
});

function jsonResponse(body: unknown, status = 200) {
	return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
}

describe('route themes', () => {
	it('maps each business area to its own theme', () => {
		expect(resolvePageTheme('/')).toBe('brand');
		expect(resolvePageTheme('/chat')).toBe('chat');
		expect(resolvePageTheme('/supply-chain')).toBe('analysis');
		expect(resolvePageTheme('/supply-chain/decision')).toBe('decision');
		expect(resolvePageTheme('/supply-chain/disposal')).toBe('disposal');
		expect(resolvePageTheme('/supply-chain/history')).toBe('history');
		expect(resolvePageTheme('/knowledge/agents')).toBe('knowledge');
		expect(resolvePageTheme('/system/agents')).toBe('system');
	});

	it('keeps knowledge and system groups internally consistent', () => {
		expect(resolvePageTheme('/knowledge/business')).toBe('knowledge');
		expect(resolvePageTheme('/knowledge/semantic-models')).toBe('knowledge');
		expect(resolvePageTheme('/system/model-config')).toBe('system');
		expect(resolvePageTheme('/system/data-sources')).toBe('system');
		expect(resolvePageTheme('/prompt-config')).toBe('system');
		expect(resolvePageTheme('/agent/new')).toBe('system');
	});

	it('defines a theme layer for every theme', () => {
		expect(new Set(PAGE_THEMES).size).toBe(PAGE_THEMES.length);
		expect(PAGE_THEMES).toContain('brand');
		expect(PAGE_THEMES).toHaveLength(8);
	});

	it('assigns nav hover tints per navigation group', () => {
		expect(navGroupTint('工作台')).toBe('workspace');
		expect(navGroupTint('风险决策')).toBe('risk');
		expect(navGroupTint('知识与智能体')).toBe('knowledge');
		expect(navGroupTint('系统设置')).toBe('system');
		expect(navGroupTint(undefined)).toBe('home');
	});
});

describe('brand home content', () => {
	it('links only to real in-app routes', () => {
		const hrefs = HOME_FEATURE_GROUPS.flatMap((group) => group.features.map((feature) => feature.href));
		expect(hrefs.length).toBeGreaterThanOrEqual(13);
		for (const href of hrefs) {
			expect(href.startsWith('/')).toBe(true);
			expect(href.startsWith('//')).toBe(false);
		}
		expect(new Set(hrefs).size).toBe(hrefs.length);
	});

	it('covers all seven required entry areas', () => {
		const labels = HOME_FEATURE_GROUPS.flatMap((group) => group.features.map((feature) => feature.label));
		expect(labels).toEqual(
			expect.arrayContaining(['供应链工作台', '智能决策', '处置与沙箱', '任务历史', '数据问答', '智能体知识', '模型配置']),
		);
		expect(HOME_DESIGN_INTENT.length).toBeGreaterThanOrEqual(5);
	});

	it('no longer renders a chat composer or submits a question from the home page', () => {
		const source = readFileSync('src/app/page.tsx', 'utf8');
		expect(source).not.toContain('cp-composer');
		expect(source).not.toContain('PromptComposer');
		expect(source).not.toContain('/chat?q=');
		expect(source).not.toContain('useRouter');
		expect(source).not.toContain('分析当前高风险订单');
	});

	it('keeps the chat input empty on first load (no q prefill)', () => {
		const source = readFileSync('src/app/chat/ChatView.tsx', 'utf8');
		expect(source).toContain("useState('')");
		expect(source).not.toContain("useUrlParam('q')");
	});
});

describe('preset questions API', () => {
	it('loads preset questions for an agent', async () => {
		let url = '';
		vi.stubGlobal('fetch', vi.fn(async (input: string) => {
			url = input;
			return jsonResponse([{ id: 1, question: '有哪些风险订单？', isActive: true }]);
		}));

		const questions = await fetchPresetQuestions(3);
		expect(url).toContain('/api/agent/3/preset-questions');
		expect(questions[0].question).toBe('有哪些风险订单？');
	});

	it('saves the full ordered list as a bare JSON array', async () => {
		let body: unknown = null;
		let method = '';
		vi.stubGlobal('fetch', vi.fn(async (_input: string, init?: RequestInit) => {
			method = init?.method ?? '';
			body = JSON.parse(String(init?.body));
			return jsonResponse({ message: '预设问题保存成功' });
		}));

		await savePresetQuestions(3, [
			{ question: '问题一', isActive: true },
			{ question: '问题二', isActive: true },
		]);
		expect(method).toBe('POST');
		expect(body).toEqual([
			{ question: '问题一', isActive: true },
			{ question: '问题二', isActive: true },
		]);
	});
});

describe('clear chat sessions', () => {
	it('uses the real DELETE endpoint', async () => {
		let url = '';
		let method = '';
		vi.stubGlobal('fetch', vi.fn(async (input: string, init?: RequestInit) => {
			url = input;
			method = init?.method ?? '';
			return jsonResponse({ success: true, message: '会话已清空' });
		}));

		await clearChatSessions(1);
		expect(method).toBe('DELETE');
		expect(url).toContain('/api/agent/1/sessions');
	});
});
