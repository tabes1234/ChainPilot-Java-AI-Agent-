'use client';

import { Fragment, useEffect, useMemo, useState } from 'react';
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
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Textarea } from '@/components/ui/textarea';
import { errorMessage, friendlyErrorMessage } from '@/lib/api/client';
import { fetchOrders, fetchSuppliers, fetchInventory, fetchReport, fetchTasks, fetchSimulationBaseline, type InventoryItem, type RiskLevel } from '@/lib/api/supplyChain';
import { ReportView } from '@/components/supply-chain/report-view';
import { DemoBanner } from '@/components/supply-chain/demo-banner';
import { DEMO_SCENARIO, isDemoScenario } from '@/lib/demo-scenario';
import { RiskEvidencePanel } from '@/components/supply-chain/risk-evidence';
import { BulletList, Metric, RiskBadge, Section, StatusBadge, TASK_STATUS_LABELS, formatTime } from '@/components/supply-chain/common';
import { useAnalysisTask } from '@/components/supply-chain/use-analysis-task';

const EXAMPLES = [
	'请分析当前供应链中交付风险最高的订单，并给出处置建议',
	'哪些订单存在库存缺口？需要优先补货的 SKU 是什么？',
	'评估高风险供应商对当前订单的影响',
];

const RISK_FILTERS: Record<string, string> = { ALL: '全部风险', HIGH: '高风险', MEDIUM: '中风险', LOW: '低风险' };

export default function AnalysisView() {
	const initialTaskId = useUrlParam('taskId');
	const demoParam = useUrlParam('demo');
	const demoMode = isDemoScenario(demoParam);

	const [question, setQuestion] = useState(EXAMPLES[0]);
	const [riskFilter, setRiskFilter] = useState<string>('ALL');
	const [orderKeyword, setOrderKeyword] = useState('');
	const [baselineTab, setBaselineTab] = useState<'orders' | 'suppliers' | 'inventory'>('orders');
	const [expandedOrder, setExpandedOrder] = useState<string | null>(null);
	const taskRunner = useAnalysisTask(initialTaskId);

	useEffect(() => {
		if (!demoMode) return;
		queueMicrotask(() => setQuestion(DEMO_SCENARIO.question));
	}, [demoMode]);

	const ordersQuery = useQuery({ queryKey: ['supplyChainOrders'], queryFn: () => fetchOrders() });
	const suppliersQuery = useQuery({ queryKey: ['supplyChainSuppliers'], queryFn: fetchSuppliers });
	const inventoryQuery = useQuery({ queryKey: ['supplyChainInventory'], queryFn: () => fetchInventory() });
	const baselineQuery = useQuery({ queryKey: ['simulationBaseline'], queryFn: fetchSimulationBaseline, staleTime: 30_000 });
	const recentTasksQuery = useQuery({
		queryKey: ['analysisTasks', 'recent'],
		queryFn: () => fetchTasks({ page: 1, pageSize: 5, mode: 'ANALYSIS' }),
		refetchInterval: taskRunner.running ? 2500 : false,
	});

	const reportQuery = useQuery({
		queryKey: ['analysisReport', taskRunner.taskId],
		queryFn: () => {
			if (!taskRunner.taskId) throw new Error('任务尚未就绪');
			return fetchReport(taskRunner.taskId);
		},
		enabled: Boolean(taskRunner.taskId) && taskRunner.task?.status === 'COMPLETED',
		retry: false,
	});

	const orders = useMemo(() => ordersQuery.data ?? [], [ordersQuery.data]);
	const suppliers = useMemo(() => suppliersQuery.data ?? [], [suppliersQuery.data]);
	const inventory = useMemo<InventoryItem[]>(
		() => (Array.isArray(inventoryQuery.data) ? inventoryQuery.data : inventoryQuery.data ? [inventoryQuery.data] : []),
		[inventoryQuery.data],
	);

	const filteredOrders = useMemo(() => {
		const keyword = orderKeyword.trim().toLowerCase();
		return orders.filter((item) => {
			if (riskFilter !== 'ALL' && item.riskLevel !== (riskFilter as RiskLevel)) return false;
			if (!keyword) return true;
			return (
				item.order.orderId.toLowerCase().includes(keyword) ||
				item.order.sku.toLowerCase().includes(keyword) ||
				item.order.supplierId.toLowerCase().includes(keyword)
			);
		});
	}, [orders, riskFilter, orderKeyword]);

	const nonLowOrders = orders.filter((item) => item.riskLevel !== 'LOW').length;
	const highRiskOrders = orders.filter((item) => item.riskLevel === 'HIGH').length;
	const shortageUnits = orders.reduce((sum, item) => sum + (item.inventoryShortage > 0 ? item.inventoryShortage : 0), 0);
	const riskSuppliers = suppliers.filter((item) => item.riskLevel !== 'LOW');
	const shortInventory = inventory.filter((item) => item.availableToPromise <= 0 || item.onHandQuantity < item.safetyStock);

	const submit = () => {
		if (!question.trim()) return;
		void taskRunner.start(question.trim(), 'ANALYSIS');
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

	return (
		<div className="space-y-5">
			<PageHeader
				title="供应链风险分析"
				breadcrumb="工作台"
				description="基于真实订单、库存与供应商数据执行异步分析任务；风险评分与建议均来自后端执行结果。"
				actions={
					<>
						<Button variant="outline" onClick={() => { ordersQuery.refetch(); suppliersQuery.refetch(); inventoryQuery.refetch(); }}>
							<RefreshCw className="mr-2 h-4 w-4" />
							刷新基线数据
						</Button>
						<Link href={demoMode ? `/supply-chain/decision?demo=${DEMO_SCENARIO.id}` : '/supply-chain/decision'}>
							<Button variant="outline">进入自然语言决策</Button>
						</Link>
					</>
				}
			/>

			{demoMode && <DemoBanner step={1} />}

			<Section title="运行概览" description="以下基线信息全部来自后端实时快照">
				<div className="flex flex-wrap items-center gap-2">
					{baselineQuery.isPending ? (
						<span className="text-xs text-[#7a8da1]">读取基线快照…</span>
					) : baselineQuery.isError || !baselineQuery.data ? (
						<span className="text-xs text-[#7a8da1]">基线快照暂不可用</span>
					) : (
						<>
							<Badge variant="outline" className="font-mono text-[11px]">{baselineQuery.data.baselineSource}</Badge>
							<Badge variant="outline" className="font-mono text-[11px]">{baselineQuery.data.ruleVersion}</Badge>
							<Badge variant="outline" className="text-[11px]">{baselineQuery.data.simulationStatus}</Badge>
							<Badge variant="secondary" className="text-[11px]">只读快照</Badge>
							{baselineQuery.data.asOfDate && <span className="text-xs text-[#5a708c]">数据截止 {baselineQuery.data.asOfDate}</span>}
						</>
					)}
				</div>
			</Section>

			<div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
				<Metric label="订单总数" value={ordersQuery.isPending ? '…' : orders.length} />
				<Metric label="风险订单" value={ordersQuery.isPending ? '…' : nonLowOrders} hint="非低风险" />
				<Metric label="高风险订单" value={ordersQuery.isPending ? '…' : highRiskOrders} />
				<Metric label="缺口总量" value={ordersQuery.isPending ? '…' : shortageUnits} hint="件" />
			</div>

			{(riskSuppliers.length > 0 || shortInventory.length > 0) && (
				<Section title="风险对象" description="仅列出后端评估为非低风险的供应商与库存不足的物料；完整列表在下方基线数据中">
					<div className="grid gap-3 lg:grid-cols-2">
						<div className="space-y-1.5">
							<p className="text-xs font-medium text-[#5a708c]">重点供应商</p>
							{riskSuppliers.slice(0, 4).map((supplier) => (
								<div key={supplier.supplierId} className="flex flex-wrap items-center justify-between gap-2 rounded-xl bg-white/45 px-3 py-2 ring-1 ring-white/60">
									<div className="min-w-0">
										<span className="text-sm font-medium text-[#1c3048]">{supplier.name}</span>
										<span className="ml-2 font-mono text-xs text-[#8aa2ba]">{supplier.supplierId}</span>
									</div>
									<RiskBadge level={supplier.riskLevel} />
								</div>
							))}
							{riskSuppliers.length === 0 && <p className="text-sm text-[#5a708c]">暂无非低风险供应商。</p>}
						</div>
						<div className="space-y-1.5">
							<p className="text-xs font-medium text-[#5a708c]">重点物料</p>
							{shortInventory.slice(0, 4).map((item) => (
								<div key={item.sku} className="flex flex-wrap items-center justify-between gap-2 rounded-xl bg-white/45 px-3 py-2 ring-1 ring-white/60">
									<div className="min-w-0">
										<span className="text-sm font-medium text-[#1c3048]">{item.productName ?? item.sku}</span>
										<span className="ml-2 font-mono text-xs text-[#8aa2ba]">{item.sku}</span>
									</div>
									<span className={`text-sm font-semibold ${item.availableToPromise <= 0 ? 'text-[#b9575c]' : 'text-[#b07f2e]'}`}>ATP {item.availableToPromise}</span>
								</div>
							))}
							{shortInventory.length === 0 && <p className="text-sm text-[#5a708c]">暂无库存不足的物料。</p>}
						</div>
					</div>
				</Section>
			)}

			<Section
				title="发起分析"
				description="后端异步执行，页面每 900ms 轮询真实任务状态与事件"
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
							<Button key={example} size="sm" variant="outline" onClick={() => setQuestion(example)}>{example.slice(0, 18)}…</Button>
						))}
					</div>
					<div className="flex flex-wrap items-center justify-between gap-2">
						<span className="text-xs text-[#5a708c]">{question.length}/2000</span>
						<Button className="bg-[#35629d] hover:bg-[#264c79]" disabled={taskRunner.submitting || taskRunner.running || !question.trim()} onClick={submit}>
							{(taskRunner.submitting || taskRunner.running) ? <Loader2 className="mr-2 h-4 w-4 animate-spin" /> : <Play className="mr-2 h-4 w-4" />}
							{taskRunner.running ? '执行中…' : '运行分析任务'}
						</Button>
					</div>
				</div>
			</Section>

			{(taskRunner.task || taskRunner.error) && (
				<Section
					title="任务状态"
					actions={
						<div className="flex flex-wrap items-center gap-2">
							<StatusBadge status={taskRunner.task?.status} />
							{taskRunner.task?.currentStep && <span className="text-xs text-[#5a708c]">{taskRunner.task.currentStep}</span>}
							{taskRunner.taskId && (
								<span className="font-mono text-[11px] text-[#8aa2ba]" title={taskRunner.taskId}>
									{taskRunner.taskId.slice(0, 8)}…
								</span>
							)}
						</div>
					}
				>
					<div className="space-y-3">
						{taskRunner.error && <p className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-[#8f3b45]">{taskRunner.error}</p>}
						<div className="grid gap-2 sm:grid-cols-3">
							<Metric label="状态" value={TASK_STATUS_LABELS[taskRunner.task?.status ?? ''] ?? taskRunner.task?.status ?? '—'} />
							<Metric label="校验尝试" value={taskRunner.task?.validationAttempts ?? '—'} />
							<Metric label="更新时间" value={formatTime(taskRunner.task?.updatedAt)} />
						</div>
						{taskRunner.events.length > 0 && (
							<details className="rounded-lg border border-[#e2e8f0] bg-[#f8fbfe] px-3 py-2">
								<summary className="cursor-pointer text-sm font-medium text-[#33506b]">执行事件（{taskRunner.events.length}）</summary>
								<ul className="mt-2 max-h-[260px] space-y-1 overflow-y-auto text-xs text-[#5a708c]">
									{taskRunner.events.map((event) => (
										<li key={event.eventId} className="flex gap-2">
											<span className="font-mono text-[#8aa2ba]">#{event.sequence}</span>
											<span className="min-w-0">
												{event.stage ?? event.type} · {event.status ?? '—'} · {event.summary ?? ''}
											</span>
										</li>
									))}
								</ul>
							</details>
						)}
					</div>
				</Section>
			)}

			{reportQuery.isError && (
				<ErrorState
					title="报告不可用"
					message={friendlyErrorMessage(reportQuery.error, '该任务没有可用报告', {
						404: '该任务不存在或已被清理，请从任务记录重新选择。',
						409: '该任务属于决策模式，请到自然语言决策页查看结果。',
					})}
					onRetry={() => reportQuery.refetch()}
				/>
			)}
			{reportQuery.data && (
				<>
					<ReportView report={reportQuery.data} />
					<div className="flex flex-wrap items-center gap-2 border-t border-[var(--cp-line)] pt-4">
						<Link href={`/supply-chain/decision?demo=${DEMO_SCENARIO.id}&sourceTask=${encodeURIComponent(reportQuery.data.taskId)}`}>
							<Button size="sm" className="rounded-full bg-[#35629d] hover:bg-[#264c79]">继续：方案决策</Button>
						</Link>
						<Link href={`/supply-chain/disposal?${new URLSearchParams({ taskId: reportQuery.data.taskId, ...(demoMode ? { demo: DEMO_SCENARIO.id } : {}) }).toString()}`}>
							<Button variant="outline" size="sm" className="rounded-full">用该任务创建处置方案</Button>
						</Link>
						<Link href="/supply-chain/history">
							<Button variant="ghost" size="sm" className="rounded-full">在任务记录中查看</Button>
						</Link>
					</div>
				</>
			)}

			{recentTasksQuery.data && recentTasksQuery.data.items.length > 0 && (
				<Section title="最近分析" description="点击可查看已完成任务的报告">
					<div className="space-y-1.5">
						{recentTasksQuery.data.items.map((item) => (
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
				</Section>
			)}

			<Section
				title="基线数据"
				description="后端实时读取的订单、供应商与库存；筛选在前端执行"
				actions={
					<div className="flex flex-wrap items-center gap-1">
						{([
							{ key: 'orders', label: `订单 ${orders.length}` },
							{ key: 'suppliers', label: `供应商 ${suppliers.length}` },
							{ key: 'inventory', label: `库存 ${inventory.length}` },
						] as const).map((tab) => (
							<button
								key={tab.key}
								type="button"
								onClick={() => setBaselineTab(tab.key)}
								aria-pressed={baselineTab === tab.key}
								className={`rounded-full px-3 py-1.5 text-[12.5px] font-medium transition-colors ${baselineTab === tab.key ? 'bg-white/80 text-[#2b5583] ring-1 ring-white/80' : 'text-[#5f7389] hover:bg-white/55'}`}
							>
								{tab.label}
							</button>
						))}
					</div>
				}
			>
			<details className="group rounded-xl bg-white/40 px-3 py-2 ring-1 ring-white/60">
				<summary className="cursor-pointer text-[13px] font-medium text-[#33506b]">
					展开基线明细（订单 {orders.length} · 供应商 {suppliers.length} · 库存 {inventory.length}）
				</summary>
				<div className="mt-3">
				{baselineTab === 'orders' && (
					<div className="space-y-3">
						<div className="flex flex-wrap gap-2">
							<Input className="w-[200px]" placeholder="搜索订单/SKU/供应商" value={orderKeyword} onChange={(event) => setOrderKeyword(event.target.value)} />
							<Select items={RISK_FILTERS} value={riskFilter} onValueChange={(value) => setRiskFilter(value ?? 'ALL')}>
								<SelectTrigger className="w-[140px]"><SelectValue /></SelectTrigger>
								<SelectContent>
									<SelectItem value="ALL">全部风险</SelectItem>
									<SelectItem value="HIGH">高风险</SelectItem>
									<SelectItem value="MEDIUM">中风险</SelectItem>
									<SelectItem value="LOW">低风险</SelectItem>
								</SelectContent>
							</Select>
						</div>
						{ordersQuery.isPending ? (
							<LoadingState compact label="加载订单风险…" />
						) : ordersQuery.isError ? (
							<ErrorState message={errorMessage(ordersQuery.error, '加载订单失败')} onRetry={() => ordersQuery.refetch()} />
						) : filteredOrders.length === 0 ? (
							<p className="py-6 text-center text-sm text-[#5a708c]">当前筛选条件下没有订单。</p>
						) : (
							<div className="overflow-x-auto">
								<Table>
									<TableHeader>
										<TableRow>
											<TableHead>订单</TableHead>
											<TableHead>SKU</TableHead>
											<TableHead>数量</TableHead>
											<TableHead>供应商</TableHead>
											<TableHead>要求交付</TableHead>
											<TableHead>预计交付</TableHead>
											<TableHead>状态</TableHead>
											<TableHead>风险</TableHead>
											<TableHead>评分</TableHead>
											<TableHead>缺口</TableHead>
											<TableHead className="text-right">证据</TableHead>
										</TableRow>
									</TableHeader>
									<TableBody>
										{filteredOrders.map((item) => {
											const expanded = expandedOrder === item.order.orderId;
											return (
												<Fragment key={item.order.orderId}>
													<TableRow>
														<TableCell className="font-mono text-xs">{item.order.orderId}</TableCell>
														<TableCell className="font-mono text-xs">{item.order.sku}</TableCell>
														<TableCell>{item.order.quantity}</TableCell>
														<TableCell className="text-xs">{item.order.supplierId}</TableCell>
														<TableCell className="text-xs">{item.order.requestedDeliveryDate}</TableCell>
														<TableCell className="text-xs">{item.order.estimatedDeliveryDate}</TableCell>
														<TableCell className="text-xs">{item.order.status}</TableCell>
														<TableCell><RiskBadge level={item.riskLevel} /></TableCell>
														<TableCell>{item.riskScore}</TableCell>
														<TableCell className={item.inventoryShortage > 0 ? 'text-[#b9575c]' : ''}>{item.inventoryShortage}</TableCell>
														<TableCell className="text-right">
															<Button size="sm" variant="ghost" onClick={() => setExpandedOrder(expanded ? null : item.order.orderId)}>
																{expanded ? '收起' : '查看'}
															</Button>
														</TableCell>
													</TableRow>
													{expanded && (
														<TableRow>
															<TableCell colSpan={11} className="bg-[#f8fbfe]/70">
																<div className="space-y-2 py-1">
																	<div className="text-xs text-[#5a708c]">
																		评分 {item.riskScore}/100 · {item.riskLevel}；数值与规则来自后端订单、库存、供应商快照。
																	</div>
																	<RiskEvidencePanel item={item} defaultOpen />
																</div>
															</TableCell>
														</TableRow>
													)}
												</Fragment>
											);
										})}
									</TableBody>
								</Table>
							</div>
						)}
					</div>
				)}

				{baselineTab === 'suppliers' && (
					suppliersQuery.isPending ? <LoadingState compact label="加载供应商…" /> : suppliersQuery.isError ? (
						<ErrorState message={errorMessage(suppliersQuery.error, '加载供应商失败')} onRetry={() => suppliersQuery.refetch()} />
					) : (
						<div className="grid gap-2 sm:grid-cols-2">
							{suppliers.map((supplier) => (
								<div key={supplier.supplierId} className="flex flex-wrap items-center justify-between gap-2 rounded-xl bg-white/45 px-3 py-2 ring-1 ring-white/60">
									<div>
										<div className="text-sm font-medium text-[#1c3048]">{supplier.name}</div>
										<div className="text-xs text-[#5a708c]">准时率 {(supplier.onTimeDeliveryRate * 100).toFixed(1)}% · 缺陷率 {(supplier.defectRate * 100).toFixed(1)}%</div>
									</div>
									<RiskBadge level={supplier.riskLevel} />
								</div>
							))}
						</div>
					)
				)}

				{baselineTab === 'inventory' && (
					inventoryQuery.isPending ? <LoadingState compact label="加载库存…" /> : inventoryQuery.isError ? (
						<ErrorState message={errorMessage(inventoryQuery.error, '加载库存失败')} onRetry={() => inventoryQuery.refetch()} />
					) : inventory.length === 0 ? (
						<p className="text-sm text-[#5a708c]">暂无库存数据。</p>
					) : (
						<div className="grid gap-2 sm:grid-cols-2">
							{inventory.slice(0, 10).map((item) => (
								<div key={item.sku} className="flex flex-wrap items-center justify-between gap-2 rounded-xl bg-white/45 px-3 py-2 ring-1 ring-white/60">
									<div>
										<div className="text-sm font-medium text-[#1c3048]">{item.productName ?? item.sku}</div>
										<div className="text-xs text-[#5a708c]">在库 {item.onHandQuantity} · 预留 {item.reservedQuantity} · 安全库存 {item.safetyStock}</div>
									</div>
									<span className={`text-sm font-semibold ${item.availableToPromise <= 0 ? 'text-[#b9575c]' : 'text-[#428766]'}`}>ATP {item.availableToPromise}</span>
								</div>
							))}
						</div>
					)
				)}
			</div>
			</details>
			</Section>

			<details className="border-t border-[var(--cp-line)] pt-4">
				<summary className="cursor-pointer text-[13px] font-medium text-[#33506b]">分析能力边界</summary>
				<div className="mt-2">
					<BulletList
						items={[
							'分析任务可异步运行；关闭页面后任务仍由后端执行，可通过任务记录重新打开。',
							'风险评分、规则结果与模型叙述都会标注来源，规则降级不会被当作真实模型输出。',
							'供应链分析不接收 agentId，读取的是当前工作区已配置的数据连接。',
						]}
					/>
				</div>
			</details>
		</div>
	);
}
