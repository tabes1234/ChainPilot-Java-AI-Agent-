'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { cn } from '@/lib/utils';

const GROUPS: Record<'knowledge' | 'system', { label: string; href: string }[]> = {
	knowledge: [
		{ label: '智能体知识', href: '/knowledge/agents' },
		{ label: '业务术语', href: '/knowledge/business' },
		{ label: '语义模型', href: '/knowledge/semantic-models' },
	],
	system: [
		{ label: '智能体管理', href: '/system/agents' },
		{ label: '模型配置', href: '/system/model-config' },
		{ label: '数据源管理', href: '/system/data-sources' },
		{ label: '提示词配置', href: '/prompt-config' },
	],
};

/** Light cross-navigation inside a business area; routes and APIs stay independent. */
export function SectionNav({ group }: { group: 'knowledge' | 'system' }) {
	const pathname = usePathname();
	const items = GROUPS[group];
	return (
		<nav className="-mt-1 flex flex-wrap items-center gap-1" aria-label={group === 'knowledge' ? '知识管理导航' : '系统配置导航'}>
			{items.map((item) => {
				const active = pathname === item.href;
				return (
					<Link
						key={item.href}
						href={item.href}
						aria-current={active ? 'page' : undefined}
						data-active={active}
						className={cn(
							'cp-section-link',
							'rounded-full px-3 py-1.5 text-[12.5px] font-medium transition-colors duration-200',
							active
								? 'bg-white/80 text-[#2b5583] ring-1 ring-white/80 shadow-[0_1px_2px_rgba(23,42,64,0.04)]'
								: 'text-[#5f7389] hover:bg-white/55 hover:text-[#233448]',
						)}
					>
						{item.label}
					</Link>
				);
			})}
		</nav>
	);
}
