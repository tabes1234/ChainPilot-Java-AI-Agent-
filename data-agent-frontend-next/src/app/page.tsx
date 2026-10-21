'use client';

import type { CSSProperties } from 'react';
import Link from 'next/link';
import {
	ArrowRight,
	BookOpen,
	Brain,
	Database,
	GitCompareArrows,
	History,
	ListChecks,
	MessageCircleQuestion,
	Radar,
	ShieldCheck,
	Sparkles,
} from 'lucide-react';
import { HOME_DESIGN_INTENT, HOME_FEATURE_GROUPS, HOME_TECH_STACK } from '@/lib/home-content';

const FEATURE_ICONS: Record<string, React.ElementType> = {
	'/supply-chain': Radar,
	'/supply-chain/decision': MessageCircleQuestion,
	'/supply-chain/disposal': ShieldCheck,
	'/supply-chain/history': History,
	'/chat': Brain,
	'/knowledge/agents': BookOpen,
	'/knowledge/business': Database,
	'/knowledge/semantic-models': GitCompareArrows,
	'/system/agents': ListChecks,
	'/agent/new': Sparkles,
	'/system/model-config': Brain,
	'/system/data-sources': Database,
	'/prompt-config': BookOpen,
};

export default function HomePage() {
	return (
		<div className="relative">
			<div aria-hidden="true" className="pointer-events-none absolute -top-24 left-1/2 -z-[1] h-[560px] w-[min(1100px,95vw)] -translate-x-1/2 rounded-full bg-[radial-gradient(closest-side,rgba(158,176,236,0.32),rgba(168,210,236,0.18),transparent)] blur-2xl" />

			<section className="flex min-h-[calc(100vh-150px)] flex-col justify-center py-12">
				<div className="mx-auto w-full max-w-3xl">
					<div className="mb-5 flex items-center gap-2 text-[13px] font-medium text-[#5d7389]">
						<Sparkles className="h-4 w-4 text-[#6f7fbf]" strokeWidth={1.8} />
						<span>ChainPilot · 供应链智能决策工作台</span>
					</div>

					<h1
						className="home-slogan text-[clamp(38px,6vw,64px)] font-semibold leading-[1.08] tracking-[-0.035em] text-[#1b2b3d]"
						aria-label="让供应链风险可解释，让处置决策可追溯。"
					>
						<span aria-hidden="true" className="home-slogan__line">
							{['让供应链风险', '可解释，'].map((phrase, index) => (
								<span
									key={phrase}
									className="home-slogan__phrase"
									style={{ '--slogan-index': index } as CSSProperties}
								>
									<span className="home-slogan__text">{phrase}</span>
								</span>
							))}
						</span>
						<span aria-hidden="true" className="home-slogan__line home-slogan__line--second">
							{['让处置决策', '可追溯。'].map((phrase, index) => (
								<span
									key={phrase}
									className="home-slogan__phrase"
									style={{ '--slogan-index': index } as CSSProperties}
								>
									<span className="home-slogan__text">{phrase}</span>
								</span>
							))}
						</span>
					</h1>

					<p className="home-intro mt-6 max-w-2xl text-[15.5px] leading-[1.9]">
						ChainPilot 是一个面向供应链业务的 AI Agent 项目。它整合订单、库存与供应商的真实数据，完成风险分析、候选方案比较、人工确认、沙箱演练与任务审计，帮助团队从「<span className="home-intro__key">发现风险</span>」走到「<span className="home-intro__key">确认处置</span>」，并保留每一步的依据与过程。
					</p>

					<div className="mt-6 flex flex-wrap gap-2">
						{HOME_TECH_STACK.map((tech) => (
							<span key={tech} className="rounded-full border border-white/80 bg-white/55 px-3 py-1 text-[12px] text-[#5d7389]">
								{tech}
							</span>
						))}
					</div>

					<div className="mt-8 flex flex-wrap items-center gap-3">
						<Link
							href="/supply-chain?demo=supplier-delay"
							className="inline-flex items-center gap-2 rounded-full bg-[#2b5583] px-5 py-2.5 text-[14px] font-medium text-white shadow-[0_14px_32px_-18px_rgba(43,85,131,0.9)] transition-all hover:bg-[#35629d]"
						>
							<Sparkles className="h-4 w-4" />
							开始体验示例场景
							<ArrowRight className="h-4 w-4" />
						</Link>
						<Link
							href="/supply-chain"
							className="inline-flex items-center gap-2 rounded-full border border-white/80 bg-white/60 px-5 py-2.5 text-[14px] font-medium text-[#33506b] transition-colors hover:bg-white/90"
						>
							<Radar className="h-4 w-4 text-[#4a769f]" />
							进入供应链工作台
						</Link>
						<Link
							href="/chat"
							className="inline-flex items-center gap-2 px-2 py-2.5 text-[13px] font-medium text-[#5d7389] hover:text-[#2b5583]"
						>
							<Brain className="h-4 w-4 text-[#5c67a8]" />
							打开数据问答
						</Link>
						<a href="#intent" className="inline-flex items-center gap-1.5 px-2 py-2.5 text-[13px] font-medium text-[#5d7389] hover:text-[#2b5583]">
							了解设计意图
						</a>
					</div>
					<p className="mt-4 text-[12px] text-[#7a8da1]">
						示例链路：风险分析 → 方案决策 → 模拟/沙箱 → 观测与评测。无需 API Key，使用本地演示数据。
					</p>
				</div>
			</section>

			<section id="intent" className="border-t border-[var(--cp-line)] py-12">
				<div className="grid gap-8 lg:grid-cols-[280px_minmax(0,1fr)]">
					<div>
						<p className="cp-eyebrow">设计意图</p>
						<h2 className="mt-2 text-[24px] font-semibold leading-snug tracking-[-0.02em] text-[#233448]">
							确定性业务逻辑
							<br />
							与 AI 解释的分工
						</h2>
						<p className="mt-3 text-[13px] leading-relaxed text-[#6a7d91]">
							评分、排名、执行资格均由 Java 后端计算；模型负责组织语言与解释，不替代业务规则。
						</p>
					</div>
					<ol className="grid gap-x-8 gap-y-5 sm:grid-cols-2">
						{HOME_DESIGN_INTENT.map((intent, index) => (
							<li key={intent} className="flex min-w-0 gap-3">
								<span className="mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-white/70 text-[11px] font-semibold text-[#5c67a8] ring-1 ring-white/80">
									{String(index + 1).padStart(2, '0')}
								</span>
								<p className="text-[13.5px] leading-relaxed text-[#3f556b]">{intent}</p>
							</li>
						))}
					</ol>
				</div>
			</section>

			<section className="border-t border-[var(--cp-line)] py-12">
				<div className="mb-8">
					<p className="cp-eyebrow">功能入口</p>
					<h2 className="mt-2 text-[24px] font-semibold tracking-[-0.02em] text-[#233448]">按使用目的进入</h2>
				</div>
				<div className="space-y-9">
					{HOME_FEATURE_GROUPS.map((group) => (
						<div key={group.title} className="grid gap-4 lg:grid-cols-[220px_minmax(0,1fr)]">
							<div>
								<h3 className="text-[14px] font-semibold text-[#2b3d51]">{group.title}</h3>
								<p className="mt-1 text-[12px] leading-relaxed text-[#7a8da1]">{group.description}</p>
							</div>
							<div className="grid gap-1 sm:grid-cols-2 xl:grid-cols-3">
								{group.features.map((feature) => {
									const Icon = FEATURE_ICONS[feature.href] ?? Sparkles;
									return (
										<Link
											key={feature.href}
											href={feature.href}
											className="group flex items-start gap-3 rounded-2xl px-3.5 py-3 transition-colors hover:bg-white/60"
										>
											<span className="mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-xl bg-white/70 text-[#4a769f] ring-1 ring-white/80 transition-colors group-hover:text-[#2b5583]">
												<Icon className="h-4 w-4" strokeWidth={1.8} />
											</span>
											<span className="min-w-0">
												<span className="block text-[13.5px] font-medium text-[#233448]">{feature.label}</span>
												<span className="mt-0.5 block text-[12px] leading-relaxed text-[#6a7d91]">{feature.description}</span>
											</span>
										</Link>
									);
								})}
							</div>
						</div>
					))}
				</div>
			</section>

			<section className="border-t border-[var(--cp-line)] py-8">
				<p className="mx-auto max-w-3xl text-center text-[12px] leading-relaxed text-[#7a8da1]">
					沙箱演练仅在受控环境中验证处置步骤，不会写入真实库存、订单或外部系统；所有分析、决策与执行记录均以当前后端返回为准。
				</p>
			</section>
		</div>
	);
}
