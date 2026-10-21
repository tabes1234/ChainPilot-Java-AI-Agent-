'use client';

import Link from 'next/link';
import { ArrowRight, FlaskConical, ShieldCheck } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { DEMO_SCENARIO } from '@/lib/demo-scenario';
import { cn } from '@/lib/utils';

const STEPS = [
	{ id: 1, label: '风险分析', href: `/supply-chain?demo=${DEMO_SCENARIO.id}` },
	{ id: 2, label: '方案决策', href: `/supply-chain/decision?demo=${DEMO_SCENARIO.id}` },
	{ id: 3, label: '模拟与沙箱', href: `/supply-chain/disposal?demo=${DEMO_SCENARIO.id}` },
];

export function DemoBanner({ step }: { step: 1 | 2 | 3 }) {
	return (
		<div className="rounded-2xl border border-[#dce8f2] bg-white/70 px-4 py-3.5 shadow-[0_12px_36px_-30px_rgba(23,42,64,0.45)] backdrop-blur-sm">
			<div className="flex flex-wrap items-start justify-between gap-3">
				<div className="min-w-0">
					<div className="flex flex-wrap items-center gap-2">
						<Badge variant="outline" className="gap-1 border-[#cfdff0] bg-[#f5f9fd] text-[#35629d]">
							<FlaskConical className="h-3 w-3" />
							示例场景 · 演示模式
						</Badge>
						<Badge variant="outline" className="gap-1 border-[#e5dcc8] bg-[#fcfaf4] text-[#8a6a34]">
							<ShieldCheck className="h-3 w-3" />
							Java 确定性规则
						</Badge>
						<span className="text-[11px] text-[#7a8da1]">真实模型未配置；不会自动调用收费模型</span>
					</div>
					<h2 className="mt-2 text-[15px] font-semibold text-[#233448]">{DEMO_SCENARIO.title}</h2>
					<p className="mt-1 text-xs leading-relaxed text-[#5f7389]">{DEMO_SCENARIO.next}</p>
					<div className="mt-2 flex flex-wrap gap-1.5">
						{DEMO_SCENARIO.context.map((item) => (
							<span key={item} className="rounded-full border border-[#e4edf5] bg-white/65 px-2.5 py-1 text-[11px] text-[#5f7389]">
								{item}
							</span>
						))}
					</div>
				</div>
				<Link href="/" className="inline-flex items-center gap-1 text-xs font-medium text-[#35629d] hover:underline">
					返回首页
					<ArrowRight className="h-3.5 w-3.5" />
				</Link>
			</div>
			<div className="mt-3 flex flex-wrap items-center gap-1.5 border-t border-[#e8f0f7] pt-2.5">
				{STEPS.map((item) => (
					<Link
						key={item.id}
						href={item.href}
						className={cn(
							'inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-[11.5px] font-medium transition-colors',
							step === item.id
								? 'bg-[#2b5583] text-white'
								: 'bg-white/60 text-[#5f7389] hover:bg-white hover:text-[#2b5583]',
						)}
					>
						<span className={cn('flex h-4 w-4 items-center justify-center rounded-full text-[9px]', step === item.id ? 'bg-white/20' : 'bg-[#edf4fa] text-[#35629d]')}>
							{item.id}
						</span>
						{item.label}
					</Link>
				))}
				<span className="ml-auto text-[11px] text-[#8a9aab]">数据为本地模拟，不写真实 ERP/WMS</span>
			</div>
		</div>
	);
}
