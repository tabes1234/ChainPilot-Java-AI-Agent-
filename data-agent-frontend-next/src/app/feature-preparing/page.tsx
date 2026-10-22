'use client';

import { Suspense } from 'react';
import Link from 'next/link';
import { useSearchParams } from 'next/navigation';
import { Compass } from 'lucide-react';
import { Button } from '@/components/ui/button';
import {
	DEMO_NOTICE_BODY,
	DEMO_NOTICE_RETURN_LABEL,
	DEMO_NOTICE_TITLE,
} from '@/lib/demo-mode';

const OPEN_FEATURES = [
	{ href: '/supply-chain', label: '供应链分析' },
	{ href: '/supply-chain/decision', label: '自然语言决策' },
	{ href: '/supply-chain/disposal', label: '处置与沙箱' },
] as const;

function NoticeBody() {
	const params = useSearchParams();
	const feature = params.get('feature');

	return (
		<div className="mx-auto flex min-h-[calc(100vh-150px)] max-w-xl flex-col items-center justify-center gap-4 p-10 text-center">
			<span className="flex h-14 w-14 items-center justify-center rounded-3xl bg-white/75 shadow-[0_18px_50px_-28px_rgba(23,42,64,0.45)] ring-1 ring-white/80">
				<Compass className="h-6 w-6 text-[#4a769f]" strokeWidth={1.7} />
			</span>
			<h1 className="text-[24px] font-semibold leading-snug tracking-[-0.02em] text-[#1c3048]">{DEMO_NOTICE_TITLE}</h1>
			{feature && <p className="text-[13.5px] font-medium text-[#4a769f]">「{feature}」</p>}
			<div className="mt-1 space-y-2">
				{DEMO_NOTICE_BODY.map((line) => (
					<p key={line} className="text-[14px] leading-relaxed text-[#5a708c]">
						{line}
					</p>
				))}
			</div>
			<div className="mt-3 flex flex-col items-center gap-2">
				<p className="text-[13px] font-medium text-[#33506b]">当前已开放的能力</p>
				<div className="flex flex-wrap items-center justify-center gap-2">
					{OPEN_FEATURES.map((item) => (
						<Link
							key={item.href}
							href={item.href}
							className="rounded-full bg-white/70 px-4 py-1.5 text-[13px] font-medium text-[#2b5583] shadow-sm ring-1 ring-white/80 transition-colors hover:bg-[#eef6fd]"
						>
							{item.label}
						</Link>
					))}
				</div>
			</div>
			<Link href="/" className="mt-3">
				<Button size="lg" className="bg-[#2b5583] hover:bg-[#35629d]">
					{DEMO_NOTICE_RETURN_LABEL}
				</Button>
			</Link>
		</div>
	);
}

export default function FeaturePreparingPage() {
	return (
		<Suspense fallback={null}>
			<NoticeBody />
		</Suspense>
	);
}
