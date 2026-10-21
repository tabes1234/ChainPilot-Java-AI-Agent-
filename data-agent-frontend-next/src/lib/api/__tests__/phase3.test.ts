import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { PAGE_THEMES, THEME_PALETTES, resolvePageTheme } from '@/lib/page-theme';
import { parseUrlParam } from '@/lib/use-url-param';

const css = readFileSync('src/app/globals.css', 'utf8');

function hueOf(hex: string): number {
	const value = hex.replace('#', '');
	const r = parseInt(value.slice(0, 2), 16) / 255;
	const g = parseInt(value.slice(2, 4), 16) / 255;
	const b = parseInt(value.slice(4, 6), 16) / 255;
	const max = Math.max(r, g, b);
	const min = Math.min(r, g, b);
	const delta = max - min;
	if (delta === 0) return 0;
	let hue: number;
	if (max === r) hue = ((g - b) / delta) % 6;
	else if (max === g) hue = (b - r) / delta + 2;
	else hue = (r - g) / delta + 4;
	const degrees = hue * 60;
	return degrees < 0 ? degrees + 360 : degrees;
}

describe('stronger theme palette', () => {
	it('defines a distinct accent for every business area', () => {
		const accents = PAGE_THEMES.map((theme) => THEME_PALETTES[theme].accent.toLowerCase());
		expect(new Set(accents).size).toBe(PAGE_THEMES.length);
		const inks = PAGE_THEMES.map((theme) => THEME_PALETTES[theme].accentInk.toLowerCase());
		expect(new Set(inks).size).toBe(PAGE_THEMES.length);
	});

	it('spreads accents across several hue families instead of blue shades only', () => {
		const hues = PAGE_THEMES.map((theme) => hueOf(THEME_PALETTES[theme].accent));
		const buckets = new Set(hues.map((hue) => Math.round(hue / 45) % 8));
		expect(buckets.size).toBeGreaterThanOrEqual(4);
		// Brand, analysis, disposal and knowledge must not all share one hue family.
		expect(hueOf(THEME_PALETTES.brand.accent)).not.toBeCloseTo(hueOf(THEME_PALETTES.disposal.accent), 0);
	});

	it('keeps every route mapped to the intended area after the palette update', () => {
		const expected: Record<string, string> = {
			'/': 'brand',
			'/chat': 'chat',
			'/supply-chain': 'analysis',
			'/supply-chain/decision': 'decision',
			'/supply-chain/disposal': 'disposal',
			'/supply-chain/history': 'history',
			'/knowledge/agents': 'knowledge',
			'/knowledge/business': 'knowledge',
			'/knowledge/semantic-models': 'knowledge',
			'/system/agents': 'system',
			'/system/model-config': 'system',
			'/system/data-sources': 'system',
			'/prompt-config': 'system',
			'/agent/new': 'system',
		};
		for (const [path, theme] of Object.entries(expected)) {
			expect(resolvePageTheme(path)).toBe(theme);
		}
	});

	it('keeps /supply-chain/disposal from being classified as analysis', () => {
		expect(resolvePageTheme('/supply-chain/disposal')).not.toBe(resolvePageTheme('/supply-chain'));
	});
});

describe('transition and theme CSS', () => {
	it('renders all eight theme layers with a 700ms cross-fade', () => {
		for (const theme of PAGE_THEMES) {
			expect(css).toContain(`.cp-theme-layer[data-theme="${theme}"]`);
		}
		expect(css).toMatch(/\.cp-theme-layer \{[\s\S]*transition: opacity 700ms/);
		expect(css).toContain('.cp-theme-layer[data-active="true"]');
	});

	it('uses a clearly visible page-enter animation (420ms, scale + translate)', () => {
		expect(css).toMatch(/animation: cp-page-in 420ms/);
		expect(css).toMatch(/@keyframes cp-page-in \{\s*0% \{ opacity: 0; transform: translateY\(18px\) scale\(0\.985\)/);
		expect(css).toContain('.cp-route-wash');
		expect(css).toMatch(/animation: cp-route-wash 900ms/);
	});

	it('strengthens nav hover and active states without boxed frames', () => {
		expect(css).toMatch(/\.cp-nav-item:hover::before \{\s*opacity: 1/);
		expect(css).toMatch(/\.cp-nav-item\[data-active="true"\]/);
		expect(css).toMatch(/\.cp-nav-item\[data-active="true"\]::after/);
		expect(css).toMatch(/\.cp-section-link\[data-active="true"\]/);
		// No reintroduced permanent fixed sidebar.
		expect(css).not.toMatch(/\.cp-nav-item \{[^}]*position: fixed/);
	});

	it('mirrors theme accents onto the document root for portal content', () => {
		const shell = readFileSync('src/components/AppShell.tsx', 'utf8');
		expect(shell).toContain('document.documentElement');
		expect(shell).toContain("setProperty('--cp-theme-accent'");
		expect(shell).toContain('PAGE_THEMES.map');
	});

	it('keeps the URL parameter mechanism without useSearchParams', () => {
		const shell = readFileSync('src/components/AppShell.tsx', 'utf8');
		expect(shell).not.toContain('useSearchParams');
		expect(parseUrlParam('?taskId=t-1&planId=p-2', 'taskId')).toBe('t-1');
	});
});
