'use client';

import Link from 'next/link';
import { useQuery } from '@tanstack/react-query';
import {
	Activity,
	ArrowRight,
	FlaskConical,
	History,
	Loader2,
	MessageCircleQuestion,
	Radar,
	ShieldCheck,
	SlidersHorizontal,
} from 'lucide-react';
import { fetchEvaluationCapabilities } from '@/lib/api/evaluation';

type FeatureStatus = 'READY' | 'PARTIAL' | 'UNAVAILABLE';

const STATUS_LABEL: Record<FeatureStatus, string> = {
	READY: '完整可运行',
	PARTIAL: '部分可用',
	UNAVAILABLE: '暂不可运行',
};

const STATUS_STYLE: Record<FeatureStatus, string> = {
	READY: 'border-[rgba(53,98,157,0.32)] bg-[#e8f0f8] text-[#35629d]',
	PARTIAL: 'border-[rgba(20,144,168,0.3)] bg-[#e6f6f9] text-[#0e7490]',
	UNAVAILABLE: 'border-[rgba(179,131,64,0.32)] bg-[#faf4e8] text-[#8a6420]',
};

type DemoFeature = {
	id: string;
	name: string;
	description: string;
	note: string;
	icon: React.ElementType;
	href: string;
	status: FeatureStatus;
	dynamicAgentReadiness?: boolean;
	unavailableLabel?: string;
};

const DEMO_FEATURES: DemoFeature[] = [
	{
		id: 'risk-analysis',
		name: '供应链风险分析',
		description: '提交分析问题，后端确定性规则计算风险评分，输出风险等级、证据明细与数据缺口。',
		note: '分析任务实时运行，评分不依赖模型。',
		icon: Radar,
		href: '/supply-chain',
		status: 'READY',
	},
	{
		id: 'simulation',
		name: '情景模拟',
		description: '在隔离环境中建立基线、模拟补货数量并比较处置方案的业务影响。',
		note: '不修改真实业务数据。',
		icon: SlidersHorizontal,
		href: '/supply-chain/simulation',
		status: 'READY',
	},
	{
		id: 'decision',
		name: '自然语言决策',
		description: '用自然语言描述处置意图，生成候选方案与确定性排名。',
		note: '提案确认需人工点击。',
		icon: MessageCircleQuestion,
		href: '/supply-chain/decision',
		status: 'READY',
	},
	{
		id: 'disposal',
		name: '处置与沙箱',
		description: '生成处置方案并在沙箱中演练执行，全程留痕。',
		note: '写操作需人工确认，不确认即不会执行。',
		icon: ShieldCheck,
		href: '/supply-chain/disposal',
		status: 'READY',
	},
	{
		id: 'history',
		name: '历史任务',
		description: '回溯每次任务的状态、执行事件与报告。',
		note: '含失败与回退记录，可逐步复核。',
		icon: History,
		href: '/supply-chain/history',
		status: 'READY',
	},
	{
		id: 'workflow-offline',
		name: '工作流评测 · OFFLINE',
		description: '运行真实工作流演练，评测断言由后端确定性服务独立重算并持久化。',
		note: '不调用模型，无模型费用。',
		icon: FlaskConical,
		href: '/supply-chain/workflow-evaluation',
		status: 'READY',
	},
	{
		id: 'observability',
		name: '观察与分析',
		description: '查看 Agent 运行列表、事件与诊断信息。',
		note: '数据来自真实运行记录。',
		icon: Activity,
		href: '/agent-observability',
		status: 'READY',
	},
	{
		id: 'evaluation-center',
		name: 'Agent 评测中心',
		description: '查看历史评测运行、指标与运行对比。',
		note: 'AGENT 模式运行受模型就绪门禁限制。',
		icon: Activity,
		href: '/agent-evaluation',
		status: 'PARTIAL',
	},
	{
		id: 'workflow-agent',
		name: '工作流评测 · AGENT 模式',
		description: '完整 Agent 工作流评测已实现，需要已配置的 CHAT 模型才能运行。',
		note: '未配置模型时后端门禁会拒绝启动。',
		icon: FlaskConical,
		href: '/supply-chain/workflow-evaluation',
		status: 'UNAVAILABLE',
		dynamicAgentReadiness: true,
		unavailableLabel: '查看 OFFLINE 评测',
	},
];

export default function DemoStudioPage() {
	const capabilitiesQuery = useQuery({
		queryKey: ['demoStudioCapabilities'],
		queryFn: fetchEvaluationCapabilities,
	});

	const agentReady = capabilitiesQuery.data?.agentEvaluationReady === true;
	const agentNote = capabilitiesQuery.isPending
		? '正在读取后端模型就绪状态…'
		: capabilitiesQuery.isError
			? '无法连接后端，无法核实 AGENT 就绪状态。'
			: (capabilitiesQuery.data?.agentEvaluationBlockReason ?? '就绪状态未知。');

	return (
		<div className="mx-auto max-w-6xl">
			<section className="cp-fade-up pt-10 sm:pt-14">
				<h1 className="cp-hero-title text-[clamp(32px,4.2vw,52px)] font-bold leading-none tracking-[-0.02em]">
					ChainPilot Demo Studio
				</h1>
				<p className="mt-4 max-w-3xl text-[16px] leading-relaxed text-[#33506b]">
					探索供应链风险分析、情景模拟与工作流评测。所有演示基于真实后端操作，不预置运行结果。
				</p>
				<div className="mt-5 flex flex-wrap items-center gap-2 text-[12px]">
					{(Object.keys(STATUS_LABEL) as FeatureStatus[]).map((status) => (
						<span
							key={status}
							className={`rounded-full border px-2.5 py-1 font-medium ${STATUS_STYLE[status]}`}
						>
							{STATUS_LABEL[status]}
						</span>
					))}
				</div>
			</section>

			<section className="cp-stagger mt-8 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
				{DEMO_FEATURES.map((feature) => {
					const status: FeatureStatus = feature.dynamicAgentReadiness
						? agentReady
							? 'READY'
							: 'UNAVAILABLE'
						: feature.status;
					const isUnavailable = status === 'UNAVAILABLE';
					return (
						<div
							key={feature.id}
							className="cp-glow-card flex min-h-[200px] flex-col rounded-2xl border border-white/70 bg-white/55 p-5 shadow-[0_1px_2px_rgba(23,42,64,0.04),0_14px_36px_-28px_rgba(23,42,64,0.28)] backdrop-blur-sm"
						>
							<div className="flex items-start justify-between gap-2">
								<div className="flex h-10 w-10 items-center justify-center rounded-xl bg-gradient-to-br from-[#e8f0f8] to-[#e2f1ef] text-[#35629d]">
									<feature.icon className="h-5 w-5" />
								</div>
								<span
									className={`whitespace-nowrap rounded-full border px-2.5 py-1 text-[11px] font-medium ${STATUS_STYLE[status]}`}
								>
									{capabilitiesQuery.isPending && feature.dynamicAgentReadiness
										? '检查中'
										: STATUS_LABEL[status]}
								</span>
							</div>
							<p className="mt-3.5 text-[16px] font-semibold text-[#1f2a37]">{feature.name}</p>
							<p className="mt-1.5 text-[13px] leading-relaxed text-[#5f7389]">{feature.description}</p>
							<p className="mt-1 text-[12px] leading-relaxed text-[#8aa2ba]">
								{feature.dynamicAgentReadiness && !isUnavailable ? '后端模型就绪检查通过。' : feature.note}
							</p>
							<div className="mt-auto pt-4">
								{capabilitiesQuery.isPending && feature.dynamicAgentReadiness ? (
									<span className="inline-flex items-center gap-1.5 text-[12.5px] text-[#8aa2ba]">
										<Loader2 className="h-3.5 w-3.5 animate-spin" />
										正在核实可用状态…
									</span>
								) : isUnavailable && !feature.unavailableLabel ? (
									<span className="inline-flex items-center text-[12.5px] font-medium text-[#8a6420]">
										暂不可运行
									</span>
								) : (
									<Link
										href={feature.href}
										className="inline-flex items-center gap-1.5 text-[13.5px] font-semibold text-[#35629d] transition-colors hover:text-[#264c79]"
									>
										{isUnavailable && feature.unavailableLabel
											? feature.unavailableLabel
											: '开始演示'}
										<ArrowRight className="h-3.5 w-3.5" />
									</Link>
								)}
							</div>
						</div>
					);
				})}
			</section>

			<section className="cp-fade-up mt-10 rounded-2xl border border-white/70 bg-white/45 p-5 backdrop-blur-sm">
				<p className="text-[13px] font-semibold text-[#1f2a37]">可用状态说明</p>
				<ul className="mt-2 space-y-1.5 text-[12.5px] leading-relaxed text-[#5f7389]">
					<li>「完整可运行」：功能当前真实可运行，可直接进入页面操作。</li>
					<li>「部分可用」：页面可查看真实数据，部分操作受门禁限制，以页面内说明为准。</li>
					<li>「暂不可运行」：功能代码已实现但当前环境无法运行，不提供伪装的可运行入口，也不伪造模型结果。</li>
				</ul>
				<p className="mt-3 text-[12px] leading-relaxed text-[#8aa2ba]">
					AGENT 模式就绪状态来自后端接口实时读取：
					{capabilitiesQuery.isPending ? (
						<span className="ml-1 inline-flex items-center gap-1">
							<Loader2 className="h-3 w-3 animate-spin" />
							读取中
						</span>
					) : (
						<span className="ml-1">{agentNote}</span>
					)}
				</p>
			</section>
		</div>
	);
}
