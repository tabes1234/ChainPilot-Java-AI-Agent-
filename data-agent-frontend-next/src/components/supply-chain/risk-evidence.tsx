'use client';

import { Badge } from '@/components/ui/badge';
import { BulletList } from '@/components/supply-chain/common';
import type { OrderRisk } from '@/lib/api/supplyChain';

export function RiskEvidencePanel({ item, defaultOpen = false }: { item: OrderRisk; defaultOpen?: boolean }) {
	return (
		<div className="space-y-2">
			{(item.dataGaps?.length ?? 0) > 0 && (
				<div className="rounded-lg border border-[#ecd9b5] bg-[#fdf8ee] px-2.5 py-2 text-xs leading-relaxed text-[#7a5a22]">
					<div className="font-medium">数据不足，以下数值未被推断</div>
					<BulletList items={item.dataGaps ?? []} />
				</div>
			)}
			{(item.evidence?.length ?? 0) > 0 && (
				<details open={defaultOpen} className="rounded-lg border border-[#e6eef7] bg-white/55 px-2.5 py-2">
					<summary className="cursor-pointer text-xs font-medium text-[#33506b]">
						业务数值与规则证据（{item.evidence?.length}）
					</summary>
					<ul className="mt-2 space-y-2">
						{(item.evidence ?? []).map((evidence) => (
							<li
								key={evidence.code}
								className={evidence.available ? 'text-xs' : 'rounded border border-[#ecd9b5] bg-[#fdf8ee] px-2 py-1.5 text-xs'}
							>
								<div className="flex flex-wrap items-center justify-between gap-2">
									<span className="font-medium text-[#33506b]">{evidence.label}</span>
									<Badge
										variant="outline"
										className={
											evidence.scoreContribution > 0
												? 'border-[#e7c8cb] bg-[#fff6f6] text-[#8f3b45]'
												: 'border-[#dfe8f1] bg-white/60 text-[#5a708c]'
										}
									>
										{evidence.scoreContribution > 0 ? `+${evidence.scoreContribution} 分` : '0 分'}
									</Badge>
								</div>
								<div className="mt-1 space-y-0.5 text-[#5a708c]">
									<div><span className="text-[#33506b]">观测值：</span>{evidence.observedValue}</div>
									<div><span className="text-[#33506b]">期望：</span>{evidence.expectedValue}</div>
									<div><span className="text-[#33506b]">规则：</span>{evidence.rule}</div>
									<div><span className="text-[#33506b]">来源：</span>{evidence.source}</div>
								</div>
							</li>
						))}
					</ul>
				</details>
			)}
		</div>
	);
}
