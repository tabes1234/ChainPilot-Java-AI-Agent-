import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { PAGE_THEMES, THEME_PALETTES } from '@/lib/page-theme';
import { p95Label } from '@/lib/evaluation-format';

const css = readFileSync('src/app/globals.css', 'utf8');

function blockFor(theme: string): string {
	const start = css.indexOf(`.cp-theme-layer[data-theme="${theme}"]`);
	expect(start).toBeGreaterThan(-1);
	return css.slice(start, css.indexOf('}', start) + 1);
}

describe('light Gemini background baseline', () => {
	it('keeps every theme layer as a low-alpha tint instead of a saturated wash', () => {
		for (const theme of PAGE_THEMES) {
			const block = blockFor(theme);
			const gradients = block.replace(/--cp-theme-tint:[^;]+;/, '');
			const alphas = [...gradients.matchAll(/rgba\([^)]*,\s*(0?\.\d+|\d+(?:\.\d+)?)\)/g)].map((match) =>
				Number(match[1]),
			);
			expect(alphas.length).toBeGreaterThan(3);
			expect(Math.max(...alphas)).toBeLessThanOrEqual(0.26);

			const tint = block.match(/--cp-theme-tint:\s*rgba\([^)]*,\s*(0?\.\d+)\)/);
			expect(tint).not.toBeNull();
			expect(Number(tint![1])).toBeLessThanOrEqual(0.72);
		}
	});

	it('keeps the cross-fade and route wash while lowering its peak tint', () => {
		expect(css).toMatch(/\.cp-theme-layer \{[\s\S]*transition: opacity 700ms/);
		const wash = css.match(/\.cp-route-wash \{[\s\S]*?color-mix\(in srgb, var\(--cp-theme-accent, #3a6ea8\) (\d+)%/);
		expect(wash).not.toBeNull();
		expect(Number(wash![1])).toBeLessThanOrEqual(14);
	});

	it('keeps the navigation accents separate from the light background', () => {
		for (const theme of PAGE_THEMES) {
			expect(THEME_PALETTES[theme].accent).toMatch(/^#[0-9a-f]{6}$/i);
		}
		expect(css).toContain('.cp-nav-item[data-nav-group="engineering"]');
	});
});


describe('white glow background', () => {
	const shell = readFileSync('src/components/AppShell.tsx', 'utf8');

	it('renders three moving white glow layers above an explicit background stack', () => {
		expect(shell).toContain('cp-aurora__glow--one');
		expect(shell).toContain('cp-aurora__glow--two');
		expect(shell).toContain('cp-aurora__glow--three');
		expect(shell).toContain('relative z-10');
		expect(css).toMatch(/\.cp-aurora \{[\s\S]*?z-index: 0;/);
		expect(css).toMatch(/@keyframes cp-glow-a[\s\S]*?translate3d[\s\S]*?opacity/);
		expect(css).toMatch(/\.cp-aurora__glow \{[\s\S]*?will-change: transform, opacity/);
	});

	it('uses near-white neutral values and keeps theme tints below a whisper', () => {
		expect(css).toMatch(/--cp-canvas: #fafafa/);
		expect(css).toContain('rgba(255, 255, 255, 1) 0%');
		for (const theme of PAGE_THEMES) {
			const block = blockFor(theme);
			const tint = block.match(/--cp-theme-tint:\s*rgba\([^)]*,\s*(0?\.\d+)\)/);
			expect(tint).not.toBeNull();
			expect(Number(tint![1])).toBeLessThanOrEqual(0.28);
		}
	});

	it('stops glow motion when reduced motion is requested', () => {
		expect(css).toMatch(/@media \(prefers-reduced-motion: reduce\)[\s\S]*?\.cp-aurora__glow \{ animation: none; opacity: 0\.7; transform: none; \}/);
	});
});

describe('evaluation center safety', () => {
	const source = readFileSync('src/app/agent-evaluation/page.tsx', 'utf8');
	const confirmDialog = readFileSync('src/app/agent-evaluation/RunConfirmDialog.tsx', 'utf8');

	it('requires an explicit confirmation with a cost warning before an AGENT run', () => {
		expect(confirmDialog).toContain('确认启动评测运行');
		expect(confirmDialog).toContain('可能产生模型调用费用');
		expect(confirmDialog).toContain('我已了解费用与环境影响');
		expect(confirmDialog).toContain("mode === 'AGENT' && (!accepted || !prerequisitesReady)");
		expect(source).toContain('confirmAgentRun: runRequest.mode === \'AGENT\'');
		expect(source).not.toContain('setInterval(');
	});

	it('shows honest sample-size handling for P95 and empty metrics', () => {
		expect(source).toContain('p95Label(metrics)');
		expect(source).toContain('暂无可计算的指标');
		expect(p95Label({ p95DurationMs: null, p95SampleSize: 2 })).toContain('样本不足');
	});

	it('separates offline and agent metrics and requires enough runs for comparison', () => {
		const comparison = readFileSync('src/app/agent-evaluation/ComparisonPanel.tsx', 'utf8');
		expect(source).toContain('不合并成一个“模型成功率”');
		expect(source).toContain('暂无该模式下结果完整的 COMPLETED 运行');
		expect(comparison).toContain('至少需要两次已完成运行');
		expect(comparison).toContain('不可比');
	});
});
