export type PageTheme = 'brand' | 'chat' | 'analysis' | 'decision' | 'disposal' | 'history' | 'knowledge' | 'system';

export const PAGE_THEMES: PageTheme[] = [
	'brand',
	'chat',
	'analysis',
	'decision',
	'disposal',
	'history',
	'knowledge',
	'system',
];

/** One theme per business area; same-area routes share a palette. */
export function resolvePageTheme(pathname: string | null): PageTheme {
	if (!pathname || pathname === '/') return 'brand';
	if (pathname.startsWith('/chat')) return 'chat';
	if (pathname.startsWith('/supply-chain/history')) return 'history';
	if (pathname.startsWith('/supply-chain/decision')) return 'decision';
	if (pathname.startsWith('/supply-chain/disposal')) return 'disposal';
	if (pathname.startsWith('/supply-chain')) return 'analysis';
	if (pathname.startsWith('/knowledge')) return 'knowledge';
	if (pathname.startsWith('/agent-evaluation')) return 'system';
	if (pathname.startsWith('/agent-observability')) return 'system';
	if (
		pathname.startsWith('/system') ||
		pathname.startsWith('/prompt-config') ||
		pathname.startsWith('/agent') ||
		pathname.startsWith('/dashboard')
	) {
		return 'system';
	}
	return 'brand';
}

export type NavTint = 'home' | 'workspace' | 'risk' | 'knowledge' | 'engineering' | 'system';

export function navGroupTint(group?: string): NavTint {
	switch (group) {
		case '工作台':
			return 'workspace';
		case '风险决策':
			return 'risk';
		case '知识与智能体':
			return 'knowledge';
		case 'Agent 工程':
			return 'engineering';
		case '系统设置':
			return 'system';
		default:
			return 'home';
	}
}

export type ThemePalette = {
	/** Strong accent used for active navigation, section tabs and focus rings. */
	accent: string;
	/** Readable ink for accent text on light surfaces. */
	accentInk: string;
	/** Very light accent wash for active pills. */
	accentSoft: string;
};

export const THEME_PALETTES: Record<PageTheme, ThemePalette> = {
	brand: { accent: '#6b7cf0', accentInk: '#4a55b8', accentSoft: 'rgba(126, 140, 240, 0.18)' },
	chat: { accent: '#8a6fe8', accentInk: '#5b3cb0', accentSoft: 'rgba(154, 124, 236, 0.2)' },
	analysis: { accent: '#2a9fc0', accentInk: '#146f8c', accentSoft: 'rgba(64, 178, 205, 0.18)' },
	decision: { accent: '#6a5bd8', accentInk: '#4433a8', accentSoft: 'rgba(122, 100, 224, 0.2)' },
	disposal: { accent: '#1a9a63', accentInk: '#0d6b43', accentSoft: 'rgba(45, 170, 115, 0.18)' },
	history: { accent: '#55789f', accentInk: '#365577', accentSoft: 'rgba(110, 143, 177, 0.18)' },
	knowledge: { accent: '#2d9b74', accentInk: '#146a4d', accentSoft: 'rgba(70, 178, 133, 0.18)' },
	system: { accent: '#647ba6', accentInk: '#425a82', accentSoft: 'rgba(120, 140, 176, 0.18)' },
};
