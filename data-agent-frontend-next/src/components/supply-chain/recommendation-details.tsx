'use client';

import { BulletList } from '@/components/supply-chain/common';
import type { Recommendation } from '@/lib/api/supplyChain';

export function RecommendationDetails({ recommendation }: { recommendation: Recommendation }) {
	const hasDetails =
		Boolean(recommendation.rationale || recommendation.priorityBasis || recommendation.expectedImprovement) ||
		(recommendation.businessBasis?.length ?? 0) > 0 ||
		(recommendation.prerequisites?.length ?? 0) > 0 ||
		(recommendation.sideEffects?.length ?? 0) > 0 ||
		(recommendation.assumptions?.length ?? 0) > 0 ||
		(recommendation.sources?.length ?? 0) > 0 ||
		(recommendation.affectedOrderIds?.length ?? 0) > 0;

	if (!hasDetails) return null;

	return (
		<details className="mt-2 rounded-lg border border-[#e6eef7] bg-white/45 px-2.5 py-2">
			<summary className="cursor-pointer text-[11.5px] font-medium text-[#5f7389]">
				建议依据、前置条件与不确定性
			</summary>
			<div className="mt-2 space-y-2 text-xs text-[#5a708c]">
				{recommendation.rationale && (
					<p className="leading-relaxed">
						<span className="font-medium text-[#33506b]">判断理由：</span>
						{recommendation.rationale}
					</p>
				)}
				{recommendation.priorityBasis && (
					<p>
						<span className="font-medium text-[#33506b]">优先级依据：</span>
						{recommendation.priorityBasis}
					</p>
				)}
				{recommendation.expectedImprovement && (
					<p>
						<span className="font-medium text-[#33506b]">预期改善：</span>
						{recommendation.expectedImprovement}
					</p>
				)}
				{(recommendation.affectedOrderIds?.length ?? 0) > 0 && (
					<p>
						<span className="font-medium text-[#33506b]">影响订单：</span>
						{recommendation.affectedOrderIds?.join('、')}
					</p>
				)}
				{(recommendation.businessBasis?.length ?? 0) > 0 && (
					<div>
						<p className="font-medium text-[#33506b]">业务依据</p>
						<BulletList items={recommendation.businessBasis ?? []} />
					</div>
				)}
				{(recommendation.prerequisites?.length ?? 0) > 0 && (
					<div>
						<p className="font-medium text-[#33506b]">前置条件</p>
						<BulletList items={recommendation.prerequisites ?? []} />
					</div>
				)}
				{(recommendation.sideEffects?.length ?? 0) > 0 && (
					<div>
						<p className="font-medium text-[#33506b]">潜在影响</p>
						<BulletList items={recommendation.sideEffects ?? []} />
					</div>
				)}
				{(recommendation.assumptions?.length ?? 0) > 0 && (
					<div>
						<p className="font-medium text-[#33506b]">仍未确认的假设</p>
						<BulletList items={recommendation.assumptions ?? []} />
					</div>
				)}
				{(recommendation.sources?.length ?? 0) > 0 && (
					<div>
						<p className="font-medium text-[#33506b]">来源</p>
						<BulletList items={recommendation.sources ?? []} />
					</div>
				)}
			</div>
		</details>
	);
}
