'use client';

import { useEffect, useMemo, useState, useSyncExternalStore } from 'react';
import { usePathname } from 'next/navigation';
import Link from 'next/link';
import { Menu, PanelLeftOpen, Radar } from 'lucide-react';
import { AppSidebar, SidebarNav, type SidebarMode } from '@/components/AppSidebar';
import { Button } from '@/components/ui/button';
import { Sheet, SheetContent, SheetHeader, SheetTitle, SheetTrigger } from '@/components/ui/sheet';
import { currentNavItem } from '@/lib/navigation';
import { PAGE_THEMES, THEME_PALETTES, resolvePageTheme } from '@/lib/page-theme';
import { cn } from '@/lib/utils';

const STORAGE_KEY = 'chainpilot:sidebar-mode';

type ShellMode = SidebarMode | 'hidden';

function readSidebarMode(): ShellMode {
	if (typeof window === 'undefined') return 'expanded';
	const saved = window.localStorage.getItem(STORAGE_KEY);
	return saved === 'expanded' || saved === 'collapsed' || saved === 'hidden' ? saved : 'expanded';
}

function subscribeSidebar(onChange: () => void) {
	window.addEventListener('storage', onChange);
	return () => window.removeEventListener('storage', onChange);
}

export function AppShell({ children }: { children: React.ReactNode }) {
	const pathname = usePathname();
	const storedMode = useSyncExternalStore(subscribeSidebar, readSidebarMode, () => 'expanded' as ShellMode);
	const [overrideMode, setOverrideMode] = useState<ShellMode | null>(null);
	const mode = overrideMode ?? storedMode;
	const [navOpen, setNavOpen] = useState(false);

	const setMode = (next: ShellMode) => {
		window.localStorage.setItem(STORAGE_KEY, next);
		setOverrideMode(next);
	};

	const activeItem = useMemo(() => currentNavItem(pathname), [pathname]);

	const pageTheme = useMemo(() => resolvePageTheme(pathname), [pathname]);
	const palette = THEME_PALETTES[pageTheme];

	// Portals (mobile drawer, dialogs) render outside the themed wrapper, so mirror the
	// accent variables on <html> for them to inherit the current business theme.
	useEffect(() => {
		const root = document.documentElement;
		root.style.setProperty('--cp-theme-accent', palette.accent);
		root.style.setProperty('--cp-theme-accent-ink', palette.accentInk);
		root.style.setProperty('--cp-theme-accent-soft', palette.accentSoft);
	}, [palette]);

	return (
		<div
			className="relative isolate min-h-screen"
			data-page-theme={pageTheme}
			style={{
				"--cp-theme-accent": palette.accent,
				"--cp-theme-accent-ink": palette.accentInk,
				"--cp-theme-accent-soft": palette.accentSoft,
			} as React.CSSProperties}
		>
			<div className="cp-aurora" aria-hidden="true">
				{PAGE_THEMES.map((theme) => (
					<div key={theme} className="cp-theme-layer" data-theme={theme} data-active={theme === pageTheme} />
				))}
				<div className="cp-aurora__glow cp-aurora__glow--one" />
				<div className="cp-aurora__glow cp-aurora__glow--two" />
				<div className="cp-aurora__glow cp-aurora__glow--three" />
				<div key={pathname} className="cp-route-wash" />
			</div>

			<div className="relative z-10 mx-auto flex w-full max-w-[1680px] items-start">
				{mode !== 'hidden' && (
					<AppSidebar
						mode={mode}
						onToggleMode={() => setMode(mode === 'expanded' ? 'collapsed' : 'expanded')}
						onHide={() => setMode('hidden')}
					/>
				)}

				<div className="min-w-0 flex-1">
					<main id="cp-main" className="cp-main">
						{children}
					</main>
				</div>
			</div>

			{/* Temporary navigation layer: mobile drawer + a restore control when the desktop sidebar is hidden. */}
			<Sheet open={navOpen} onOpenChange={setNavOpen}>
				<SheetTrigger
					render={
						<button
							type="button"
							className={cn(
								'fixed bottom-5 left-5 z-40 inline-flex items-center gap-2 rounded-full border border-white/80 bg-white/85 px-4 py-2.5 text-sm font-medium text-[#2b5583] shadow-[0_18px_50px_-24px_rgba(23,42,64,0.45)] backdrop-blur-md transition-all hover:bg-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[rgba(58,110,168,0.5)]',
								mode === 'hidden' ? '' : 'lg:hidden',
							)}
							aria-label="打开导航"
						>
							{mode === 'hidden' ? <PanelLeftOpen className="h-4 w-4" /> : <Menu className="h-4 w-4" />}
							<span>{activeItem?.label ?? '导航'}</span>
						</button>
					}
				/>
				<SheetContent side="left" className="w-[300px] max-w-[86vw] border-r border-white/70 bg-[rgba(250,252,255,0.96)] p-0 backdrop-blur-xl">
					<SheetHeader className="px-5 pb-2 pt-6">
						<SheetTitle className="flex items-center gap-3 text-[#1f2a37]">
							<span className="flex h-9 w-9 items-center justify-center rounded-2xl bg-gradient-to-br from-[#5d8fc9] via-[#5e9fb4] to-[#4f9c93]">
								<Radar className="h-[18px] w-[18px] text-white" strokeWidth={1.9} />
							</span>
							<span className="text-left leading-tight">
								<span className="block text-[15px] font-semibold">ChainPilot</span>
								<span className="block text-[11px] font-medium text-[#7d8ea1]">供应链智能决策</span>
							</span>
						</SheetTitle>
					</SheetHeader>
					<div className="overflow-y-auto px-3 py-3">
						<SidebarNav onNavigate={() => setNavOpen(false)} />
					</div>
					{mode === 'hidden' && (
						<div className="border-t border-[var(--cp-line)] p-4">
							<Button
								variant="outline"
								className="w-full rounded-full"
								onClick={() => {
									setMode('expanded');
									setNavOpen(false);
								}}
							>
								<PanelLeftOpen className="mr-2 h-4 w-4" />
								显示桌面导航
							</Button>
						</div>
					)}
				</SheetContent>
			</Sheet>

			<Link href="/" className="sr-only" aria-label="返回 ChainPilot 首页">
				ChainPilot 首页
			</Link>
		</div>
	);
}
