export const DEMO_NOTICE_PATH = '/feature-preparing';

// Evaluated at build time in Next bundles (NEXT_PUBLIC_* is inlined); in Vitest it reads
// the real process.env so tests can flip the flag with vi.stubEnv + dynamic import.
export const DEMO_MODE_ENABLED = process.env.NEXT_PUBLIC_CHAINPILOT_DEMO_MODE === 'true';

export const DEMO_NOTICE_TITLE = '这段旅程，暂时还在准备中';

export const DEMO_NOTICE_BODY = [
	'每一个可靠的决策，都需要经过充分验证。',
	'这项能力目前尚未开放，我们正在继续完善它的边界与可靠性。感谢你的理解，欢迎先体验已经准备好的功能。',
] as const;

export const DEMO_NOTICE_RETURN_LABEL = '返回已开放功能';

// Routes that stay closed during the competition demo. Values are the feature names shown
// under the notice title; this map is the single place to re-open an entry after the demo.
const DEMO_CLOSED_ROUTES: Record<string, string> = {
	'/chat': '数据问答',
	'/knowledge': '知识库',
	'/knowledge/business': '业务术语',
	'/knowledge/agents': '智能体知识',
	'/knowledge/semantic-models': '语义模型',
	'/system/agents': '智能体管理',
	'/system/data-sources': '数据源管理',
	'/prompt-config': '提示词配置',
	'/agent': '智能体详情',
	'/agent/new': '新建智能体',
	'/dashboard': '数据看板',
};

export function demoClosedFeature(pathname: string | null | undefined): string | null {
	if (!pathname) return null;
	if (!DEMO_MODE_ENABLED) return null;
	let best: string | null = null;
	for (const route of Object.keys(DEMO_CLOSED_ROUTES)) {
		const closed = pathname === route || pathname.startsWith(`${route}/`);
		if (closed && (!best || route.length > best.length)) best = route;
	}
	return best ? DEMO_CLOSED_ROUTES[best] : null;
}

export function demoNoticeHref(feature: string): string {
	return `${DEMO_NOTICE_PATH}?feature=${encodeURIComponent(feature)}`;
}

export function visibleNavItems<T extends { href: string }>(items: T[]): T[] {
	if (!DEMO_MODE_ENABLED) return items;
	return items.filter((item) => demoClosedFeature(item.href) === null);
}
