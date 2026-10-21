'use client';

import { useState } from 'react';
import Link from 'next/link';
import { AlertTriangle, CheckCircle2, Loader2, Play, ShieldCheck } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { formatTime } from '@/components/supply-chain/common';
import type { EvaluationCapabilities, EvaluationMode } from '@/lib/api/evaluation';
import { readinessStateLabel } from '@/lib/api/modelConfig';
import { modeLabel } from '@/lib/evaluation-format';

export function RunConfirmDialog({
	open,
	mode,
	caseCount,
	caseLabel,
	caseNames,
	embeddingRequiredBySelection,
	chatRequiredBySelection,
	capabilities,
	pending,
	onCancel,
	onConfirm,
}: {
	open: boolean;
	mode: EvaluationMode;
	caseCount: number;
	caseLabel?: string;
	caseNames?: string[];
	embeddingRequiredBySelection?: boolean;
	chatRequiredBySelection?: boolean;
	capabilities?: EvaluationCapabilities;
	pending: boolean;
	onCancel: () => void;
	onConfirm: () => void;
}) {
	const [accepted, setAccepted] = useState(false);
	const chatRequired = chatRequiredBySelection !== false;
	const embeddingRequired = embeddingRequiredBySelection === true;
	const chatReady = !chatRequired || capabilities?.chatConnectionVerified === true;
	const embeddingReady = !embeddingRequired || capabilities?.embeddingConnectionVerified === true;
	const prerequisitesReady = chatReady && embeddingReady;

	return (
		<Dialog
			open={open}
			onOpenChange={(next) => {
				if (!next) onCancel();
			}}
		>
			<DialogContent className="sm:max-w-xl max-h-[85vh] overflow-y-auto">
				<DialogHeader>
					<DialogTitle>{caseLabel ? '重新运行评测案例' : '确认启动评测运行'}</DialogTitle>
					<DialogDescription>
						{caseLabel ? (
							<>
								将对「{caseLabel}」创建一次新的运行，历史运行与结果不会被覆盖。模式为 {modeLabel(mode)}。
							</>
						) : (
							<>
								本次将运行 {caseCount} 个案例，模式为 {modeLabel(mode)}。
							</>
						)}
					</DialogDescription>
				</DialogHeader>

				{mode === 'OFFLINE' ? (
					<div className="flex items-start gap-2 rounded-xl border border-[#d6e4ef] bg-[#f4f9fd] px-3 py-2.5 text-xs leading-relaxed text-[#3c5f82]">
						<CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0" />
						<span>离线评测调用当前后端的确定性服务，不调用模型、不写入业务表，可安全重复运行。</span>
					</div>
				) : (
					<div className="space-y-3">
						<div className="flex items-start gap-2 rounded-xl border border-[#ecd9b5] bg-[#fdf8ee] px-3 py-2.5 text-xs leading-relaxed text-[#7a5a22]">
							<AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
							<span>
								真实工作流评测会向后端提交任务，可能产生模型调用费用并写入任务与事件记录。它不会执行沙箱，也不会修改真实业务数据。
								单次最多 {capabilities?.maxAgentCasesPerRun ?? 5} 个案例、{capabilities?.agentTimeoutMinutes ?? 20} 分钟超时；当前为进程内单实例锁，同一时间只允许一个真实工作流评测。
							</span>
						</div>

						{caseNames && caseNames.length > 0 && (
							<div className="rounded-xl border border-[#e6eef7] bg-white/60 px-3 py-2.5">
								<div className="text-xs font-medium text-[#33506b]">本次将运行</div>
								<ul className="mt-1 space-y-0.5 text-xs text-[#5f7389]">
									{caseNames.map((name) => <li key={name}>· {name}</li>)}
								</ul>
							</div>
						)}

						<div className="space-y-1.5 rounded-xl border border-[#e6eef7] bg-white/60 px-3 py-2.5 text-xs leading-relaxed text-[#5f7389]">
							<div className="flex items-center gap-1.5 font-medium text-[#33506b]">
								<ShieldCheck className="h-3.5 w-3.5" />
								模型前置条件
								<Badge variant="outline" className={prerequisitesReady ? 'border-green-200 bg-green-50 text-green-700' : 'border-amber-200 bg-amber-50 text-amber-700'}>
									{prerequisitesReady ? '已满足' : '未满足'}
								</Badge>
							</div>
							<p>
								CHAT：{readinessStateLabel(capabilities?.chatState)}
								{capabilities?.chatTestedAt ? `（测试于 ${formatTime(capabilities.chatTestedAt)}）` : ''}
								{' · '}EMBEDDING：{readinessStateLabel(capabilities?.embeddingState)}
								{embeddingRequired ? '（所选案例必需）' : '（可选，不阻断当前验证）'}
							</p>
							{capabilities?.chatTestError && <p className="text-[#a45151]">最近测试失败：{capabilities.chatTestError}</p>}
							{chatRequired && !chatReady && capabilities?.agentEvaluationBlockReason && (
								<p className="rounded-lg border border-amber-200 bg-amber-50/80 px-2 py-1.5 text-[#7a5a22]">
									{capabilities.agentEvaluationBlockReason}
								</p>
							)}
							{embeddingRequired && !embeddingReady && (
								<p className="rounded-lg border border-amber-200 bg-amber-50/80 px-2 py-1.5 text-[#7a5a22]">
									所选案例声明依赖 EMBEDDING 向量检索；请先配置 EMBEDDING 并在当前后端进程内完成手动连接测试。
								</p>
							)}
							{!prerequisitesReady && (
								<Link href="/system/model-config" className="inline-block font-medium text-[#35629d] hover:underline">
									前往模型配置并手动测试
								</Link>
							)}
							<p className="text-[11px] text-[#7a8da1]">
								打开页面和刷新不会调用模型；连接测试结果仅保存在当前后端进程，后端重启后需重新测试。
							</p>
						</div>

						<div className="rounded-xl border border-[#e6eef7] bg-[#f8fbfe]/80 px-3 py-2.5 text-xs leading-relaxed text-[#5f7389]">
							<div className="font-medium text-[#33506b]">费用与调用范围</div>
							<p className="mt-1">
								每个案例至少包含规划与叙述生成的模型调用，实际次数还取决于工具调用轮次与供应商重试。
								系统未接入供应商价格表，无法给出可信金额预估；费用以供应商按实际 token 的计费为准，不展示虚构估算。
							</p>
							<p className="mt-1">
								连接测试成功不等于工作流成功；任务完成、结构化输出校验通过和断言通过都以运行记录为准。
							</p>
						</div>

						<label className="flex items-start gap-2 text-xs text-[#4a6178]">
							<Checkbox
								checked={accepted}
								disabled={!prerequisitesReady}
								onCheckedChange={(checked) => setAccepted(Boolean(checked))}
								className="mt-0.5"
							/>
							<span>我已了解费用与环境影响，并确认现在启动真实工作流评测。</span>
						</label>
					</div>
				)}

				<DialogFooter>
					<Button variant="outline" onClick={onCancel} disabled={pending}>
						取消
					</Button>
					<Button onClick={onConfirm} disabled={pending || (mode === 'AGENT' && (!accepted || !prerequisitesReady))}>
						{pending ? <Loader2 className="mr-1.5 h-4 w-4 animate-spin" /> : <Play className="mr-1.5 h-4 w-4" />}
						{mode === 'AGENT' ? '启动真实工作流评测' : caseLabel ? '重新运行此案例' : '运行离线评测'}
					</Button>
				</DialogFooter>
				{caseCount > 1 && (
					<Badge variant="secondary" className="w-fit">
						本次 {caseCount} 个案例
					</Badge>
				)}
			</DialogContent>
		</Dialog>
	);
}
