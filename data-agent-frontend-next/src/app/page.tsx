import Link from 'next/link';
import {
	ArrowRight,
	FlaskConical,
	MessageCircleQuestion,
	PlayCircle,
	Radar,
	SlidersHorizontal,
} from 'lucide-react';

const CAPABILITY_CARDS = [
	{
		icon: Radar,
		title: '供应链风险分析',
		description: '基于真实订单、库存与供应商数据执行确定性风险评分，输出风险等级、证据明细与数据缺口，模型只承担解释性叙述。',
	},
	{
		icon: SlidersHorizontal,
		title: '情景模拟',
		description: '在隔离环境中建立基线、模拟补货数量并比较处置方案的业务影响，不修改真实业务数据。',
	},
	{
		icon: MessageCircleQuestion,
		title: '决策与工作流',
		description: '自然语言决策、处置方案与沙箱执行全程留痕；候选方案排名由后端确定性引擎计算，写操作需人工确认。',
	},
	{
		icon: FlaskConical,
		title: '评测与验证',
		description: '工作流评测的 OFFLINE 断言由后端确定性服务独立重算并持久化；AGENT 模式受模型就绪门禁控制，未配置时不运行。',
	},
	{
		icon: PlayCircle,
		title: '演示中心',
		description: '按功能直达真实业务页面，每一步都基于真实后端操作，不预置运行结果，可用状态如实标注。',
	},
];

const ARCHITECTURE_LAYERS = [
	{
		name: '用户交互层',
		stack: 'Next.js · React · TypeScript',
		role: '页面只负责提交请求与展示后端结果，风险评分、方案排名等业务数值全部由后端计算。',
	},
	{
		name: '业务服务层',
		stack: 'Java 17 · Spring Boot · Spring AI',
		role: '承载风险分析、情景模拟、任务编排与评测断言的确定性执行，事件与状态全程记录。',
	},
	{
		name: '数据持久层',
		stack: 'MySQL · H2（演示环境）',
		role: '任务、执行事件与评测运行记录持久化，支持按运行 ID 回溯与对比。',
	},
];

const TECH_GROUPS = [
	{
		name: '后端',
		items: ['Java 17', 'Spring Boot', 'Spring AI', 'MyBatis', 'Maven', 'JUnit'],
	},
	{
		name: '前端',
		items: ['Next.js', 'React', 'TypeScript', 'Tailwind CSS', 'TanStack Query', 'Vitest'],
	},
	{
		name: '数据与部署',
		items: ['MySQL', 'H2（演示）', 'Docker（部署配置已备）'],
	},
];

const ENGINEERING_FEATURES = [
	{
		title: '可解释的风险结论',
		detail: '每个风险都附观测值、期望值、规则与来源四类证据；模型叙述与业务数值分离，模型输出不会被当作事实。',
	},
	{
		title: '可追溯的执行过程',
		detail: '任务、执行事件与报告持久化，模型调用失败与确定性回退同样如实记录，可逐步复核。',
	},
	{
		title: '评测驱动的质量验证',
		detail: '评测断言由后端独立重算并持久化，可按运行 ID 对比回归；任务执行完成不等于断言通过。',
	},
	{
		title: '有测试支撑的工程实践',
		detail: '后端 2047 个测试用例、前端 138 个测试用例覆盖核心链路，关键演示流程经过真实浏览器验收。',
	},
];

export default function HomePage() {
	return (
		<div className="mx-auto max-w-6xl">
			<section className="cp-fade-up relative flex min-h-[68vh] flex-col items-center justify-center py-16 text-center sm:py-24">
				<div
					aria-hidden="true"
					className="pointer-events-none absolute left-1/2 top-1/2 -z-[1] h-[440px] w-[min(880px,92vw)] -translate-x-1/2 -translate-y-1/2 rounded-full bg-[radial-gradient(closest-side,rgba(53,98,157,0.12),rgba(63,139,136,0.09),transparent)] blur-2xl"
				/>
				<p className="text-[13px] font-semibold tracking-[0.2em] text-[#35629d] sm:text-[14px]">
					供应链风险分析与 Agent 工作流平台
				</p>
				<h1 className="cp-hero-title mt-6 text-[clamp(52px,8vw,104px)] font-bold leading-none tracking-[-0.03em]">
					ChainPilot
				</h1>
				<p className="cp-hero-subtitle mt-6 text-[clamp(18px,2.4vw,26px)] font-medium leading-snug">
					让供应链风险可解释，让处置决策可追溯。
				</p>
				<div className="mx-auto mt-8 max-w-3xl space-y-3.5 text-[15px] leading-relaxed text-[#5f7389]">
					<p>
						ChainPilot
						是一个供应链风险分析与 Agent
						工作流平台：基于真实订单、库存与供应商数据识别风险，比较处置方案，并通过可追溯的任务、事件与评测记录验证每一步判断。
					</p>
					<p>
						平台的风险评分、方案排名与评测断言由 Java 后端确定性规则计算，模型只承担解释性叙述；每个结论都附带证据明细，模型输出不会被当作业务事实。
					</p>
					<p className="hidden sm:block">
						所有分析与演练在隔离环境中执行，不修改真实业务数据；任务执行、事件流转与评测结果全程持久化，可回溯、可复核。
					</p>
				</div>
				<div className="mt-10 flex flex-wrap items-center justify-center gap-3">
					<Link href="/demo" className="cp-cta-primary">
						项目演示
						<ArrowRight className="h-4 w-4" />
					</Link>
					<a href="#tech" className="cp-cta-secondary">
						了解技术架构
					</a>
				</div>
			</section>

			<section className="cp-fade-up border-t border-[var(--cp-line)] pt-12">
				<h2 className="text-[22px] font-semibold tracking-[-0.01em] text-[#233448]">项目背景</h2>
				<div className="mt-4 max-w-4xl space-y-3 text-[14.5px] leading-relaxed text-[#5f7389]">
					<p>
						在供应链场景中，订单、库存与供应商数据分散在不同系统里：风险发现依赖个人经验，处置决策缺少统一依据，执行过程事后难以追溯。
					</p>
					<p>
						ChainPilot 从这个真实问题出发，把风险识别、方案比较、执行留痕与结果评测放在同一条工作流里，让每一次判断都能回答「依据是什么、谁执行的、结果如何验证」。
					</p>
				</div>
			</section>

			<section className="cp-fade-up mt-14">
				<h2 className="text-[22px] font-semibold tracking-[-0.01em] text-[#233448]">核心能力</h2>
				<p className="mt-2 text-[14px] text-[#5f7389]">以下能力均对应可运行的真实页面，能力边界如实标注。</p>
				<div className="cp-stagger mt-6 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
					{CAPABILITY_CARDS.map((card) => (
						<div
							key={card.title}
							className="cp-glow-card flex min-h-[168px] flex-col rounded-2xl border border-white/70 bg-white/55 p-5 shadow-[0_1px_2px_rgba(23,42,64,0.04),0_14px_36px_-28px_rgba(23,42,64,0.28)] backdrop-blur-sm"
						>
							<div className="flex h-10 w-10 items-center justify-center rounded-xl bg-gradient-to-br from-[#e8f0f8] to-[#e2f1ef] text-[#35629d]">
								<card.icon className="h-5 w-5" />
							</div>
							<p className="mt-3.5 text-[16px] font-semibold text-[#1f2a37]">{card.title}</p>
							<p className="mt-1.5 text-[13px] leading-relaxed text-[#5f7389]">{card.description}</p>
						</div>
					))}
				</div>
			</section>

			<section id="tech" className="cp-fade-up mt-14 scroll-mt-28">
				<h2 className="text-[22px] font-semibold tracking-[-0.01em] text-[#233448]">技术架构</h2>
				<p className="mt-2 text-[14px] text-[#5f7389]">三层职责分离：前端展示、后端计算、数据持久化。</p>
				<div className="cp-stagger mt-6 space-y-3">
					{ARCHITECTURE_LAYERS.map((layer, index) => (
						<div
							key={layer.name}
							className="cp-glow-card flex flex-col gap-2 rounded-2xl border border-white/70 bg-white/55 p-5 backdrop-blur-sm sm:flex-row sm:items-center sm:gap-6"
						>
							<div className="flex items-center gap-3 sm:w-64 sm:shrink-0">
								<span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-[#e8f0f8] text-[13px] font-bold text-[#35629d]">
									{index + 1}
								</span>
								<div>
									<p className="text-[15.5px] font-semibold text-[#1f2a37]">{layer.name}</p>
									<p className="text-[12.5px] font-medium text-[#35629d]">{layer.stack}</p>
								</div>
							</div>
							<p className="text-[13px] leading-relaxed text-[#5f7389]">{layer.role}</p>
						</div>
					))}
				</div>
			</section>

			<section id="stack" className="cp-fade-up mt-14 scroll-mt-28">
				<h2 className="text-[22px] font-semibold tracking-[-0.01em] text-[#233448]">技术栈</h2>
				<p className="mt-2 text-[14px] text-[#5f7389]">项目实际使用的技术依赖，按职责分组。</p>
				<div className="cp-stagger mt-6 grid gap-4 md:grid-cols-3">
					{TECH_GROUPS.map((group) => (
						<div
							key={group.name}
							className="cp-glow-card rounded-2xl border border-white/70 bg-white/55 p-5 backdrop-blur-sm"
						>
							<p className="text-[13px] font-semibold tracking-[0.08em] text-[#35629d]">{group.name}</p>
							<div className="mt-3 flex flex-wrap gap-2">
								{group.items.map((item) => (
									<span
										key={item}
										className="rounded-full border border-[rgba(53,98,157,0.22)] bg-gradient-to-r from-[#f3f8fc] to-[#e9f5f3] px-3 py-1 text-[12.5px] font-medium text-[#31446b] transition-colors hover:border-[rgba(53,98,157,0.45)] hover:text-[#264c79]"
									>
										{item}
									</span>
								))}
							</div>
						</div>
					))}
				</div>
			</section>

			<section className="cp-fade-up mt-14">
				<h2 className="text-[22px] font-semibold tracking-[-0.01em] text-[#233448]">项目特色与工程价值</h2>
				<div className="cp-stagger mt-6 grid gap-4 sm:grid-cols-2">
					{ENGINEERING_FEATURES.map((feature) => (
						<div
							key={feature.title}
							className="cp-glow-card rounded-2xl border border-white/70 bg-white/55 p-5 backdrop-blur-sm"
						>
							<p className="text-[15.5px] font-semibold text-[#1f2a37]">{feature.title}</p>
							<p className="mt-1.5 text-[13px] leading-relaxed text-[#5f7389]">{feature.detail}</p>
						</div>
					))}
				</div>
			</section>

			<footer className="mt-16 border-t border-[var(--cp-line)] pb-2 pt-8">
				<div className="flex flex-col gap-5 sm:flex-row sm:items-center sm:justify-between">
					<div>
						<p className="cp-hero-title text-[22px] font-bold tracking-[-0.02em]">ChainPilot</p>
						<p className="mt-1 text-[13px] text-[#5f7389]">让供应链风险可解释，让处置决策可追溯。</p>
					</div>
					<div className="flex flex-wrap items-center gap-3">
						<Link href="/demo" className="cp-cta-primary !px-5 !py-2 !text-[13px]">
							项目演示
							<ArrowRight className="h-3.5 w-3.5" />
						</Link>
						<div className="flex flex-wrap gap-1.5">
							{['Next.js', 'Spring Boot', 'MySQL'].map((item) => (
								<span
									key={item}
									className="rounded-full border border-[rgba(31,48,70,0.1)] bg-white/60 px-2.5 py-1 text-[11.5px] text-[#5a708c]"
								>
									{item}
								</span>
							))}
						</div>
					</div>
				</div>
			</footer>
		</div>
	);
}
