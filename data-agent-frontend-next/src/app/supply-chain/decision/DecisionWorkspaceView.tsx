'use client';

import { useEffect, useState } from 'react';
import { useUrlParam } from '@/lib/use-url-param';
import Link from 'next/link';
import { useQuery } from '@tanstack/react-query';
import { Loader2, Play, RefreshCw, Square } from 'lucide-react';
import { toast } from 'sonner';
import { PageHeader } from '@/components/page-header';
import { ErrorState, LoadingState } from '@/components/data-states';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { errorMessage, friendlyErrorMessage } from '@/lib/api/client';
import { fetchDecision, fetchTasks } from '@/lib/api/supplyChain';
import { DecisionView } from '@/components/supply-chain/decision-view';
import { DemoBanner } from '@/components/supply-chain/demo-banner';
import { DEMO_SCENARIO, isDemoScenario } from '@/lib/demo-scenario';
import { Section, StatusBadge, formatTime } from '@/components/supply-chain/common';
import { useAnalysisTask } from '@/components/supply-chain/use-analysis-task';

const EXAMPLES = [
	'针对当前高风险订单，比较补货与催交付两种方案并给出建议',
	'如果供应商延迟 7 天，哪些订单会受影响？给出处置建议',
	'为库存缺口最大的 SKU 生成处置方案',
];

export default function DecisionWorkspaceView() {
	const initialTaskId = useUrlParam('taskId');
	const demoParam = useUrlParam('demo');
	const sourceTask = useUrlParam('sourceTask');
	const demoMode = isDemoScenario(demoParam);
	const [question, setQuestion] = useState(EXAMPLES[0]);
	const [keyword, setKeyword] = useState('');
	const taskRunner = useAnalysisTask(initialTaskId);

	useEffect(() => {
		if (!demoMode) return;
		queueMicrotask(() => setQuestion(DEMO_SCENARIO.decisionQuestion));
	}, [demoMode]);

	const decisionQuery = useQuery({
		queryKey: ['decision', taskRunner.taskId],
		queryFn: () => {
			if (!taskRunner.taskId) throw new Error('任务尚未就绪');
			return fetchDecision(taskRunner.taskId);
		},
		enabled: Boolean(taskRunner.taskId) && taskRunner.task?.status === 'COMPLETED',
		retry: false,
	});

	const recentTasksQuery = useQuery({
		queryKey: ['decisionTasks', 'recent'],
		queryFn: () => fetchTasks({ page: 1, pageSize: 6, mode: 'DECISION' }),
		refetchInterval: taskRunner.running ? 2500 : false,
	});

	const submit = () => {
		if (!question.trim()) return;
		void taskRunner.start(question.trim(), 'DECISION');
	};

	const cancel = async () => {
		const status = await taskRunner.cancel();
		if (!status) {
			toast.error('取消请求失败', { description: '任务可能仍在执行，请刷新任务状态确认。' });
			return;
		}
		if (status.status === 'CANCELLED') {
			toast.success('任务已取消');
			void recentTasksQuery.refetch();
		}
		else if (status.status === 'COMPLETED') {
			toast.info('任务已完成，无法取消');
		}
		else if (status.status === 'FAILED') {
			toast.info('任务已失败，未执行取消');
		}
		else {
			toast.error('任务未能取消', { description: `当前状态：${status.status}` });
		}
	};

	const recentTasks = (recentTasksQuery.data?.items ?? []).filter((item) =>
		keyword.trim() ? item.question.toLowerCase().includes(keyword.trim().toLowerCase()) : true,
	);

	return (
		<div className="space-y-5">
			<PageHeader
				title="自然语言决策"
				breadcrumb="风险决策"
				description="通过真实决策任务获得确定性方案对比与处置建议；提案确认需要服务端签发的 contentHash。"
				actions={
					<>
						<Button variant="outline" onClick={() => { taskRunner.reset(); decisionQuery.refetch(); }}>
							<RefreshCw className="mr-2 h-4 w-4" />
							重置
						</Button>
						<Link href={`/supply-chain/disposal?${new URLSearchParams({ ...(taskRunner.taskId ? { taskId: taskRunner.taskId } : {}), ...(demoMode ? { demo: DEMO_SCENARIO.id } : {}) }).toString()}`}>
							<Button variant="outline">打开处置工作台</Button>
						</Link>
					</>
				}
			/>

			{demoMode && <DemoBanner step={2} />}
			{demoMode && sourceTask && (
				<p className="text-xs text-[#7a8da1]">
					从分析任务继续：
					<Link href={`/supply-chain?taskId=${encodeURIComponent(sourceTask)}`} className="font-mono text-[#35629d] hover:underline">
						{sourceTask}
					</Link>
				</p>
			)}

			<Section
				title="决策输入"
				description="任务以后端 DECISION 模式异步执行"
				actions={taskRunner.running ? (
					<div className="flex flex-wrap items-center gap-2">
						<Badge variant="secondary" className="gap-1"><Loader2 className="h-3 w-3 animate-spin" />执行中</Badge>
						<Button size="sm" variant="outline" disabled={taskRunner.cancelling} onClick={() => void cancel()}>
							<Square className="mr-1 h-3 w-3" />
							{taskRunner.cancelling ? '取消中…' : '停止任务'}
						</Button>
					</div>
				) : undefined}
			>
				<div className="space-y-3">
					<Textarea rows={3} maxLength={2000} value={question} onChange={(event) => setQuestion(event.target.value)} />
					<div className="flex flex-wrap gap-2">
						{EXAMPLES.map((example) => (
							<Button key={example} size="sm" variant="outline" onClick={() => setQuestion(example)}>{example.slice(0, 20)}…</Button>
						))}
					</div>
					<div className="flex flex-wrap items-center justify-between gap-2">
						<span className="text-xs text-[#5a708c]">多方案比较由 Java 确定性引擎计算，模型只负责解释</span>
						<Button className="bg-[#35629d] hover:bg-[#264c79]" disabled={taskRunner.submitting || taskRunner.running || !question.trim()} onClick={submit}>
							{taskRunner.submitting || taskRunner.running ? <Loader2 className="mr-2 h-4 w-4 animate-spin" /> : <Play className="mr-2 h-4 w-4" />}
							运行决策任务
						</Button>
					</div>
				</div>
			</Section>

			{(taskRunner.task || taskRunner.error) && (
				<Section
					title="任务状态"
					actions={<StatusBadge status={taskRunner.task?.status} />}
				>
					<div className="space-y-2">
						{taskRunner.error && <p className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-[#8f3b45]">{taskRunner.error}</p>}
						<div className="flex flex-wrap gap-4 text-xs text-[#5a708c]">
							<span>任务 {taskRunner.taskId}</span>
							<span>当前步骤 {taskRunner.task?.currentStep ?? '—'}</span>
							<span>校验尝试 {taskRunner.task?.validationAttempts ?? '—'}</span>
							<span>更新 {formatTime(taskRunner.task?.updatedAt)}</span>
						</div>
						{taskRunner.events.length > 0 && (
							<details className="rounded-lg border border-[#e2e8f0] bg-[#f8fbfe] px-3 py-2">
								<summary className="cursor-pointer text-sm font-medium text-[#33506b]">执行事件（{taskRunner.events.length}）</summary>
								<ul className="mt-2 max-h-[200px] space-y-1 overflow-y-auto text-xs text-[#5a708c]">
									{taskRunner.events.map((event) => (
										<li key={event.eventId}>#{event.sequence} {event.stage ?? event.type} · {event.status ?? '—'} · {event.summary ?? ''}</li>
									))}
								</ul>
							</details>
						)}
					</div>
				</Section>
			)}

			{decisionQuery.isPending && taskRunner.task?.status === 'COMPLETED' && <LoadingState label="加载决策结果…" />}
			{decisionQuery.isError && (
				<ErrorState
					title="决策结果不可用"
					message={friendlyErrorMessage(decisionQuery.error, '该任务没有决策结果', {
						404: '该任务不存在或已被清理，请从任务记录重新选择。',
						409: '该任务属于风险分析模式，请到供应链分析页查看报告。',
					})}
					onRetry={() => decisionQuery.refetch()}
				/>
			)}
			{decisionQuery.data && <DecisionView decision={decisionQuery.data} onChanged={() => decisionQuery.refetch()} />}

			<Section
				title="最近决策任务"
				description="点击可重新打开任务结果"
				actions={<Input className="w-[220px]" placeholder="过滤任务问题" value={keyword} onChange={(event) => setKeyword(event.target.value)} />}
			>
				{recentTasksQuery.isPending ? (
					<LoadingState compact label="加载任务…" />
				) : recentTasksQuery.isError ? (
					<ErrorState message={errorMessage(recentTasksQuery.error, '加载任务失败')} onRetry={() => recentTasksQuery.refetch()} />
				) : recentTasks.length === 0 ? (
					<p className="text-sm text-[#5a708c]">暂无决策任务。</p>
				) : (
					<div className="space-y-1.5">
						{recentTasks.map((item) => (
							<button
								key={item.taskId}
								className="flex w-full flex-wrap items-center justify-between gap-2 rounded-lg border border-[#e2e8f0] px-3 py-2 text-left hover:bg-[#f8fbfe]"
								onClick={() => taskRunner.attach(item)}
							>
								<span className="min-w-0 flex-1 truncate text-sm text-[#1c3048]">{item.question}</span>
								<StatusBadge status={item.status} />
								<span className="text-xs text-[#5a708c]">{formatTime(item.updatedAt)}</span>
							</button>
						))}
					</div>
				)}
			</Section>
		</div>
	);
}
