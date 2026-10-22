'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { cn } from '@/lib/utils';
import { ChevronLeft, ChevronRight, PanelLeftClose, Radar } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { NAV_ITEMS, isNavItemActive } from '@/lib/navigation';
import { navGroupTint } from '@/lib/page-theme';
import { visibleNavItems } from '@/lib/demo-mode';

export type SidebarMode = 'expanded' | 'collapsed';

export function SidebarNav({ onNavigate, compact = false }: { onNavigate?: () => void; compact?: boolean }) {
	const pathname = usePathname();
	const items = visibleNavItems(NAV_ITEMS);
	return (
		<nav className="space-y-1.5" aria-label="主导航">
			{items.map((item, index) => {
				const active = isNavItemActive(pathname, item);
				const showGroup = item.group && (index === 0 || items[index - 1].group !== item.group);
				return (
					<div key={item.href}>
						{showGroup && !compact && (
							<div className={cn('px-3 pb-1.5 text-[12.5px] font-semibold tracking-[0.14em] text-[#7d8ea1]', index === 0 ? 'pt-0' : 'pt-5')}>
								{item.group}
							</div>
						)}
						<Link
							href={item.href}
							onClick={onNavigate}
							title={compact ? item.label : undefined}
							aria-current={active ? 'page' : undefined}
							data-active={active}
							data-compact={compact || undefined}
							data-nav-group={navGroupTint(item.group)}
							className={cn(
								'cp-nav-item group flex items-center gap-3 rounded-full px-3.5 py-2.5 text-[15px] font-medium transition-all duration-200',
								active
									? 'bg-white/80 text-[#2b5583] shadow-[0_1px_2px_rgba(23,42,64,0.05)] ring-1 ring-white/80'
									: 'text-[#44586d] hover:text-[#1f2a37]',
								compact && 'justify-center px-2',
							)}
						>
							<item.icon className={cn('h-[18px] w-[18px] shrink-0 transition-colors', active ? 'text-[#3a6ea8]' : 'text-[#71869b] group-hover:text-[#44586d]')} strokeWidth={1.8} />
							{!compact && <span className="truncate">{item.label}</span>}
						</Link>
					</div>
				);
			})}
		</nav>
	);
}

export function AppSidebar({
	mode,
	onToggleMode,
	onHide,
}: {
	mode: SidebarMode;
	onToggleMode: () => void;
	onHide: () => void;
}) {
	const collapsed = mode === 'collapsed';

	return (
		<aside
			className={cn(
				'hidden shrink-0 flex-col py-7 transition-[width,padding] duration-300 ease-out lg:sticky lg:top-0 lg:flex lg:max-h-screen',
				collapsed ? 'w-[84px] pr-2' : 'w-[248px] pr-5',
			)}
			aria-label="侧边导航"
		>
			<Link href="/" className={cn('mb-7 flex items-center gap-3 rounded-2xl transition-colors hover:bg-white/40', collapsed ? 'justify-center px-1 py-1' : 'px-2 py-1')}>
				<span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-2xl bg-gradient-to-br from-[#5d8fc9] via-[#5e9fb4] to-[#4f9c93] shadow-[0_8px_20px_-10px_rgba(53,98,157,0.7)]">
					<Radar className="h-[18px] w-[18px] text-white" strokeWidth={1.9} />
				</span>
				{!collapsed && (
					<span className="min-w-0">
						<span className="block text-[16px] font-semibold leading-tight text-[#1f2a37]">ChainPilot</span>
						<span className="block text-[12px] font-medium text-[#7d8ea1]">供应链智能决策</span>
					</span>
				)}
			</Link>

			<div className="min-h-0 flex-1 overflow-y-auto pb-4">
				<SidebarNav compact={collapsed} />
			</div>

			<div className={cn('mt-2 flex shrink-0 items-center gap-1 border-t border-[var(--cp-line)] pt-3', collapsed && 'flex-col')}>
				<Button
					variant="ghost"
					size="sm"
					onClick={onToggleMode}
					className="justify-center rounded-full text-[#5b6f83] hover:bg-white/60"
					title={collapsed ? '展开导航' : '收起为图标'}
				>
					{collapsed ? <ChevronRight className="h-4 w-4" /> : <><ChevronLeft className="h-4 w-4 mr-1" /><span className="text-xs">收起</span></>}
				</Button>
				{!collapsed && (
					<Button variant="ghost" size="icon" onClick={onHide} className="rounded-full text-[#5b6f83] hover:bg-white/60" title="隐藏导航" aria-label="隐藏导航">
						<PanelLeftClose className="h-4 w-4" />
					</Button>
				)}
			</div>
		</aside>
	);
}
