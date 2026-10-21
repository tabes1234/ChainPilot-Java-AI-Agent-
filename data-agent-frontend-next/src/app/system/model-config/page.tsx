'use client';

import { useMemo, useState } from 'react';
import Link from 'next/link';
import { toast } from 'sonner';
import { AlertTriangle, Loader2, Pencil, Plus, RefreshCw, ShieldCheck, Trash2, Zap } from 'lucide-react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { PageHeader } from '@/components/page-header';
import { SectionNav } from '@/components/section-nav';
import { EmptyState, ErrorState, LoadingState } from '@/components/data-states';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { errorMessage } from '@/lib/api/client';
import { formatTime } from '@/components/supply-chain/common';
import {
	activateModelConfig,
	addModelConfig,
	deleteModelConfig,
	fetchModelConfigs,
	fetchModelReadiness,
	readinessStateLabel,
	testModelConfig,
	updateModelConfig,
	type ModelConfig,
	type ModelReadiness,
	type ModelType,
} from '@/lib/api/modelConfig';

type Form = {
	id?: number;
	provider: string;
	apiKey: string;
	baseUrl: string;
	modelName: string;
	modelType: ModelType;
	completionsPath: string;
	embeddingsPath: string;
	temperature: number;
	maxTokens: number;
	proxyEnabled: boolean;
	proxyHost: string;
	proxyPort: string;
	proxyUsername: string;
	proxyPassword: string;
};

const EMPTY_FORM: Form = {
	provider: 'deepseek',
	apiKey: '',
	baseUrl: 'https://api.deepseek.com',
	modelName: 'deepseek-chat',
	modelType: 'CHAT',
	completionsPath: '/chat/completions',
	embeddingsPath: '',
	temperature: 0,
	maxTokens: 2000,
	proxyEnabled: false,
	proxyHost: '',
	proxyPort: '',
	proxyUsername: '',
	proxyPassword: '',
};

const PROVIDERS = ['deepseek', 'qwen', 'openai', 'siliconflow', 'custom'];
const PRESETS: Record<string, { baseUrl: string; chatModel: string; embeddingModel: string }> = {
	deepseek: { baseUrl: 'https://api.deepseek.com', chatModel: 'deepseek-chat', embeddingModel: 'deepseek-embedding' },
	qwen: { baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', chatModel: 'qwen-plus', embeddingModel: 'text-embedding-v3' },
	openai: { baseUrl: 'https://api.openai.com/v1', chatModel: 'gpt-4o-mini', embeddingModel: 'text-embedding-3-small' },
	siliconflow: { baseUrl: 'https://api.siliconflow.cn/v1', chatModel: 'deepseek-ai/DeepSeek-V3', embeddingModel: 'BAAI/bge-m3' },
};
const MODEL_TYPE_ITEMS: Record<string, string> = { CHAT: '对话模型', EMBEDDING: '向量模型' };

export default function ModelConfigPage() {
	const queryClient = useQueryClient();
	const listQuery = useQuery({ queryKey: ['modelConfigs'], queryFn: fetchModelConfigs });
	const readinessQuery = useQuery({ queryKey: ['modelReadiness'], queryFn: fetchModelReadiness });
	const readiness = readinessQuery.data;
	const configs = useMemo(() => listQuery.data ?? [], [listQuery.data]);

	const [tab, setTab] = useState<ModelType>('CHAT');
	const tabConfigs = configs.filter((config) => config.modelType === tab);

	const [editorOpen, setEditorOpen] = useState(false);
	const [form, setForm] = useState<Form>(EMPTY_FORM);
	const [formError, setFormError] = useState<string | null>(null);
	const [saving, setSaving] = useState(false);
	const [pendingDelete, setPendingDelete] = useState<ModelConfig | null>(null);
	const [pendingActivate, setPendingActivate] = useState<ModelConfig | null>(null);
	const [pendingTest, setPendingTest] = useState<ModelConfig | null>(null);
	const [testError, setTestError] = useState<string | null>(null);
	const [busyId, setBusyId] = useState<number | null>(null);

	const invalidate = () => {
		queryClient.invalidateQueries({ queryKey: ['modelConfigs'] });
		queryClient.invalidateQueries({ queryKey: ['modelReadiness'] });
	};

	const openCreate = () => {
		setForm({ ...EMPTY_FORM, modelType: tab, completionsPath: tab === 'CHAT' ? '/chat/completions' : '', embeddingsPath: tab === 'EMBEDDING' ? '/embeddings' : '' });
		setFormError(null);
		setEditorOpen(true);
	};

	const openEdit = (config: ModelConfig) => {
		setForm({
			id: config.id,
			provider: config.provider ?? 'custom',
			// Keep blank to preserve the stored key; the list only returns a mask.
			apiKey: '',
			baseUrl: config.baseUrl ?? '',
			modelName: config.modelName ?? '',
			modelType: config.modelType,
			completionsPath: config.completionsPath ?? '',
			embeddingsPath: config.embeddingsPath ?? '',
			temperature: config.temperature ?? 0,
			maxTokens: config.maxTokens ?? 2000,
			proxyEnabled: config.proxyEnabled ?? false,
			proxyHost: config.proxyHost ?? '',
			proxyPort: config.proxyPort ? String(config.proxyPort) : '',
			proxyUsername: config.proxyUsername ?? '',
			proxyPassword: '',
		});
		setFormError(null);
		setEditorOpen(true);
	};

	const applyProvider = (provider: string) => {
		const preset = PRESETS[provider];
		setForm((prev) => ({
			...prev,
			provider,
			...(preset
				? {
						baseUrl: preset.baseUrl,
						modelName: prev.modelType === 'CHAT' ? preset.chatModel : preset.embeddingModel,
					}
				: {}),
		}));
	};

	const save = async () => {
		if (!form.baseUrl.trim() || !form.modelName.trim()) {
			setFormError('Base URL 与模型名称为必填项');
			return;
		}
		if (!form.id && !form.apiKey.trim()) {
			setFormError('新增模型配置时必须填写 API Key');
			return;
		}
		setSaving(true);
		setFormError(null);
		const payload = {
			provider: form.provider.trim(),
			apiKey: form.apiKey.trim(),
			baseUrl: form.baseUrl.trim(),
			modelName: form.modelName.trim(),
			modelType: form.modelType,
			completionsPath: form.completionsPath.trim() || undefined,
			embeddingsPath: form.embeddingsPath.trim() || undefined,
			temperature: Number(form.temperature) || 0,
			maxTokens: Number(form.maxTokens) || 2000,
			isActive: false,
			proxyEnabled: form.proxyEnabled,
			proxyHost: form.proxyEnabled ? form.proxyHost.trim() : undefined,
			proxyPort: form.proxyEnabled && form.proxyPort ? Number(form.proxyPort) : undefined,
			proxyUsername: form.proxyEnabled ? form.proxyUsername.trim() : undefined,
			proxyPassword: form.proxyEnabled ? form.proxyPassword : undefined,
		};
		try {
			if (form.id) {
				// Blank secret fields are preserved by the backend.
				await updateModelConfig({ id: form.id, ...payload });
				toast.success('模型配置已更新');
			} else {
				await addModelConfig(payload);
				toast.success('模型配置已保存', { description: '新配置默认未激活，请在列表中使用「激活」切换。' });
			}
			setEditorOpen(false);
			invalidate();
		} catch (error) {
			const message = errorMessage(error);
			setFormError(message);
			toast.error('保存失败', { description: message });
		} finally {
			setSaving(false);
		}
	};

	const runActivate = async () => {
		if (!pendingActivate?.id) return;
		setBusyId(pendingActivate.id);
		try {
			await activateModelConfig(pendingActivate.id);
			toast.success('模型切换成功');
			setPendingActivate(null);
			invalidate();
		} catch (error) {
			toast.error('切换失败', { description: errorMessage(error) });
		} finally {
			setBusyId(null);
		}
	};

	const runTest = async () => {
		if (!pendingTest?.id) return;
		setBusyId(pendingTest.id);
		setTestError(null);
		try {
			const message = await testModelConfig(pendingTest.id);
			await queryClient.invalidateQueries({ queryKey: ['modelReadiness'] });
			toast.success(message, { description: `本次测试时间：${new Date().toLocaleString('zh-CN', { hour12: false })}` });
			setPendingTest(null);
		} catch (error) {
			const message = errorMessage(error);
			await queryClient.invalidateQueries({ queryKey: ['modelReadiness'] });
			setTestError(message);
			toast.error('连接测试失败', { description: message });
		} finally {
			setBusyId(null);
		}
	};

	const runDelete = async () => {
		if (!pendingDelete?.id) return;
		setBusyId(pendingDelete.id);
		try {
			await deleteModelConfig(pendingDelete.id);
			toast.success('模型配置已删除');
			setPendingDelete(null);
			invalidate();
		} catch (error) {
			toast.error('删除失败', { description: errorMessage(error) });
		} finally {
			setBusyId(null);
		}
	};

	return (
		<div className="space-y-6">
			<SectionNav group="system" />
			<PageHeader
				title="模型配置"
				breadcrumb="系统设置"
				description="管理对话模型与向量模型连接，激活后立即切换后端使用的模型。密钥仅提交给后端，界面只显示掩码。"
				actions={
					<>
						<Button variant="outline" onClick={() => { listQuery.refetch(); readinessQuery.refetch(); }} disabled={listQuery.isFetching}>
							<RefreshCw className={`mr-2 h-4 w-4 ${listQuery.isFetching ? 'animate-spin' : ''}`} />
							刷新
						</Button>
						<Button className="bg-[#35629d] hover:bg-[#264c79]" onClick={openCreate}>
							<Plus className="mr-2 h-4 w-4" />
							新增配置
						</Button>
					</>
				}
			/>

			<div className="grid gap-3 lg:grid-cols-3">
				<ReadinessCard
					label="对话模型 · CHAT"
					configured={readiness?.chatModelReady}
					state={readiness?.chatState}
					provider={readiness?.chatProvider}
					modelName={readiness?.chatModelName}
					testedAt={readiness?.chatTestedAt}
					errorMessage={readiness?.chatTestError}
					queryError={readinessQuery.isError}
					note="真实 AGENT 评测必需：已激活且本次后端进程内连接测试成功。"
				/>
				<ReadinessCard
					label="向量模型 · EMBEDDING"
					configured={readiness?.embeddingModelReady}
					state={readiness?.embeddingState}
					provider={readiness?.embeddingProvider}
					modelName={readiness?.embeddingModelName}
					testedAt={readiness?.embeddingTestedAt}
					errorMessage={readiness?.embeddingTestError}
					queryError={readinessQuery.isError}
					note="可选：当前真实工作流不依赖向量检索，未配置时使用本地词典检索。"
				/>
				<Card>
					<CardContent className="flex h-full flex-col gap-3 pt-4">
						<div className="flex items-start justify-between gap-3">
							<span className="text-sm text-[#5a708c]">真实 Agent 评测前置条件</span>
							<Badge
								variant="outline"
								className={readiness?.agentEvaluationReady
									? 'border-green-200 bg-green-50 text-green-700'
									: 'border-amber-200 bg-amber-50 text-amber-700'}
							>
								{readiness?.agentEvaluationReady ? '已满足' : '未满足'}
							</Badge>
						</div>
						<p className="text-xs leading-relaxed text-[#6a7d91]">
							要求 CHAT 已激活并通过手动连接测试；不要求 EMBEDDING。连接测试成功只证明模型端点可调用，不代表工作流或断言会通过。
						</p>
						{!readiness?.agentEvaluationReady && readiness?.agentEvaluationBlockReason && (
							<p className="rounded-lg border border-amber-200 bg-amber-50/80 px-2.5 py-2 text-xs leading-relaxed text-[#7a5a22]">
								{readiness.agentEvaluationBlockReason}
							</p>
						)}
						<Link href="/agent-evaluation" className="mt-auto text-xs font-medium text-[#35629d] hover:underline">
							前往 Agent 评测中心
						</Link>
					</CardContent>
				</Card>
			</div>

			<div className="flex items-start gap-2 rounded-xl border border-[#d6e4ef] bg-white/55 px-3 py-2.5 text-xs leading-relaxed text-[#5f7389]">
				<ShieldCheck className="mt-0.5 h-4 w-4 shrink-0 text-[#4f7da8]" />
				<span>
					打开页面或刷新只读取状态，不会调用模型；只有点击「测试」并确认后才会发起一次真实请求。
					测试结果只保存在当前后端进程内，后端重启后需要重新测试；不会按定时器自动请求模型。
				</span>
			</div>

			<Card>
				<CardHeader className="flex flex-row items-center justify-between">
					<CardTitle className="text-lg">模型列表</CardTitle>
					<div className="flex gap-2">
						{(['CHAT', 'EMBEDDING'] as ModelType[]).map((type) => (
							<Button key={type} size="sm" variant={tab === type ? 'secondary' : 'ghost'} onClick={() => setTab(type)}>
								{MODEL_TYPE_ITEMS[type]}
							</Button>
						))}
					</div>
				</CardHeader>
				<CardContent>
					{listQuery.isPending ? (
						<LoadingState label="加载模型配置…" />
					) : listQuery.isError ? (
						<ErrorState message={errorMessage(listQuery.error, '加载模型配置失败')} onRetry={() => listQuery.refetch()} />
					) : tabConfigs.length === 0 ? (
						<EmptyState title={`暂无${MODEL_TYPE_ITEMS[tab]}配置`} description="新增配置并激活后，后端才会使用该模型。" action={<Button onClick={openCreate}><Plus className="mr-2 h-4 w-4" />新增配置</Button>} />
					) : (
						<div className="overflow-x-auto">
							<Table>
								<TableHeader>
									<TableRow>
										<TableHead>供应商</TableHead>
										<TableHead>模型</TableHead>
										<TableHead>Base URL</TableHead>
										<TableHead>参数</TableHead>
										<TableHead>状态</TableHead>
										<TableHead className="text-right">操作</TableHead>
									</TableRow>
								</TableHeader>
								<TableBody>
									{tabConfigs.map((config) => (
										<TableRow key={config.id}>
											<TableCell className="font-medium">{config.provider}</TableCell>
											<TableCell>
												<div className="font-medium text-[#1c3048]">{config.modelName}</div>
												<div className="text-xs text-[#5a708c]">{config.completionsPath || config.embeddingsPath || '—'}</div>
											</TableCell>
											<TableCell className="max-w-[260px] truncate text-xs text-[#5a708c]" title={config.baseUrl}>{config.baseUrl}</TableCell>
											<TableCell className="text-xs text-[#5a708c]">
												{config.modelType === 'CHAT' ? `temperature ${config.temperature ?? 0} · maxTokens ${config.maxTokens ?? 0}` : `maxTokens ${config.maxTokens ?? 0}`}
											</TableCell>
											<TableCell>
												<Badge variant="outline" className={config.isActive ? 'border-green-200 bg-green-50 text-green-700' : 'border-gray-200 bg-gray-50 text-gray-600'}>
													{config.isActive ? '使用中' : '未激活'}
												</Badge>
											</TableCell>
											<TableCell>
												<div className="flex justify-end gap-0.5">
													<Button variant="ghost" size="icon" title="编辑" aria-label="编辑" onClick={() => openEdit(config)}>
														<Pencil className="h-4 w-4" />
													</Button>
													<Button variant="ghost" size="icon" title="激活" aria-label="激活" disabled={config.isActive || busyId === config.id} onClick={() => setPendingActivate(config)}>
														<Zap className="h-4 w-4 text-[#b38340]" />
													</Button>
													<Button variant="ghost" size="sm" disabled={busyId === config.id} onClick={() => { setTestError(null); setPendingTest(config); }}>
														测试
													</Button>
													<Button variant="ghost" size="icon" title="删除" aria-label="删除" disabled={config.isActive} onClick={() => setPendingDelete(config)}>
														<Trash2 className="h-4 w-4 text-[#b9575c]" />
													</Button>
												</div>
											</TableCell>
										</TableRow>
									))}
								</TableBody>
							</Table>
						</div>
					)}
				</CardContent>
			</Card>

			<Dialog open={editorOpen} onOpenChange={setEditorOpen}>
				<DialogContent className="max-w-2xl">
					<DialogHeader>
						<DialogTitle>{form.id ? '编辑模型配置' : '新增模型配置'}</DialogTitle>
						<DialogDescription>
							{form.id ? 'API Key 留空表示保持原密钥不变。' : '密钥只发送到后端保存，前端不会记录明文。'}
						</DialogDescription>
					</DialogHeader>
					<div className="max-h-[62vh] space-y-4 overflow-y-auto py-1 pr-1">
						<div className="grid gap-4 md:grid-cols-2">
							<div className="space-y-2">
								<Label>供应商</Label>
								<Select items={Object.fromEntries(PROVIDERS.map((provider) => [provider, provider]))} value={form.provider} onValueChange={(value) => applyProvider(value ?? 'custom')}>
									<SelectTrigger><SelectValue /></SelectTrigger>
									<SelectContent>
										{PROVIDERS.map((provider) => <SelectItem key={provider} value={provider}>{provider}</SelectItem>)}
									</SelectContent>
								</Select>
							</div>
							<div className="space-y-2">
								<Label>模型类型</Label>
								<Select items={MODEL_TYPE_ITEMS} value={form.modelType} onValueChange={(value) => setForm({ ...form, modelType: (value ?? 'CHAT') as ModelType })}>
									<SelectTrigger disabled={Boolean(form.id)}><SelectValue /></SelectTrigger>
									<SelectContent>
										<SelectItem value="CHAT">对话模型</SelectItem>
										<SelectItem value="EMBEDDING">向量模型</SelectItem>
									</SelectContent>
								</Select>
							</div>
						</div>
						<div className="space-y-2">
							<Label htmlFor="mc-key">API Key {form.id ? '（留空保持）' : '*'}</Label>
							<Input id="mc-key" type="password" autoComplete="new-password" value={form.apiKey} onChange={(event) => setForm({ ...form, apiKey: event.target.value })} />
						</div>
						<div className="space-y-2">
							<Label htmlFor="mc-url">Base URL *</Label>
							<Input id="mc-url" value={form.baseUrl} onChange={(event) => setForm({ ...form, baseUrl: event.target.value })} />
						</div>
						<div className="grid gap-4 md:grid-cols-2">
							<div className="space-y-2">
								<Label htmlFor="mc-model">模型名称 *</Label>
								<Input id="mc-model" value={form.modelName} onChange={(event) => setForm({ ...form, modelName: event.target.value })} />
							</div>
							<div className="space-y-2">
								<Label htmlFor="mc-path">{form.modelType === 'CHAT' ? 'Completions Path' : 'Embeddings Path'}</Label>
								<Input
									id="mc-path"
									value={form.modelType === 'CHAT' ? form.completionsPath : form.embeddingsPath}
									onChange={(event) =>
										setForm(form.modelType === 'CHAT'
											? { ...form, completionsPath: event.target.value }
											: { ...form, embeddingsPath: event.target.value })
									}
								/>
							</div>
						</div>
						<div className="grid gap-4 md:grid-cols-2">
							{form.modelType === 'CHAT' && (
								<div className="space-y-2">
									<Label htmlFor="mc-temp">Temperature（0–2）</Label>
									<Input id="mc-temp" type="number" step="0.1" min="0" max="2" value={form.temperature} onChange={(event) => setForm({ ...form, temperature: Number(event.target.value) })} />
								</div>
							)}
							<div className="space-y-2">
								<Label htmlFor="mc-max">Max Tokens</Label>
								<Input id="mc-max" type="number" value={form.maxTokens} onChange={(event) => setForm({ ...form, maxTokens: Number(event.target.value) })} />
							</div>
						</div>
						<details className="rounded-xl bg-white/45 px-3 py-2 ring-1 ring-white/60">
							<summary className="cursor-pointer text-[12.5px] font-medium text-[#5f7389]">高级：代理设置</summary>
							<label className="mt-2 flex items-center gap-2 text-sm text-[#33506b]">
								<input type="checkbox" className="h-4 w-4" checked={form.proxyEnabled} onChange={(event) => setForm({ ...form, proxyEnabled: event.target.checked })} />
								通过代理访问
							</label>
						{form.proxyEnabled && (
							<div className="mt-3 grid gap-4 md:grid-cols-2">
								<div className="space-y-2">
									<Label htmlFor="mc-ph">代理主机</Label>
									<Input id="mc-ph" value={form.proxyHost} onChange={(event) => setForm({ ...form, proxyHost: event.target.value })} />
								</div>
								<div className="space-y-2">
									<Label htmlFor="mc-pp">代理端口</Label>
									<Input id="mc-pp" type="number" value={form.proxyPort} onChange={(event) => setForm({ ...form, proxyPort: event.target.value })} />
								</div>
								<div className="space-y-2">
									<Label htmlFor="mc-pu">代理用户名</Label>
									<Input id="mc-pu" value={form.proxyUsername} onChange={(event) => setForm({ ...form, proxyUsername: event.target.value })} />
								</div>
								<div className="space-y-2">
									<Label htmlFor="mc-pw">代理密码 {form.id ? '（留空保持）' : ''}</Label>
									<Input id="mc-pw" type="password" autoComplete="new-password" value={form.proxyPassword} onChange={(event) => setForm({ ...form, proxyPassword: event.target.value })} />
								</div>
							</div>
						)}
						</details>
						{formError && <p className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-[#8f3b45]">{formError}</p>}
					</div>
					<DialogFooter>
						<Button variant="outline" onClick={() => setEditorOpen(false)}>取消</Button>
						<Button className="bg-[#35629d] hover:bg-[#264c79]" disabled={saving} onClick={save}>
							{saving && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
							保存
						</Button>
					</DialogFooter>
				</DialogContent>
			</Dialog>

			<Dialog open={pendingActivate !== null} onOpenChange={(open) => !open && setPendingActivate(null)}>
				<DialogContent>
					<DialogHeader>
						<DialogTitle>切换模型确认</DialogTitle>
						<DialogDescription>
							激活「{pendingActivate?.modelName}」会停用同类型其他配置并重新加载模型，是否继续？
						</DialogDescription>
					</DialogHeader>
					<DialogFooter>
						<Button variant="outline" onClick={() => setPendingActivate(null)}>取消</Button>
						<Button disabled={busyId !== null} onClick={runActivate}>确认切换</Button>
					</DialogFooter>
				</DialogContent>
			</Dialog>

			<Dialog open={pendingTest !== null} onOpenChange={(open) => { if (!open) { setPendingTest(null); setTestError(null); } }}>
				<DialogContent>
					<DialogHeader>
						<DialogTitle>连接测试</DialogTitle>
						<DialogDescription>
							将使用已保存的配置发起一次真实模型调用（可能产生少量费用），确定继续？
							测试成功只代表模型端点可调用，不代表真实 Agent 工作流会成功。
						</DialogDescription>
					</DialogHeader>
					{testError && (
						<div className="flex items-start gap-2 rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-xs leading-relaxed text-[#8f3b45]">
							<AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
							<span>{testError}</span>
						</div>
					)}
					<p className="text-xs leading-relaxed text-[#6a7d91]">
						本次结果与时间会记录在当前后端进程内，用于 AGENT 评测前置检查；后端重启后需要重新测试。
					</p>
					<DialogFooter>
						<Button variant="outline" onClick={() => setPendingTest(null)}>取消</Button>
						<Button disabled={busyId !== null} onClick={runTest}>
							{busyId === pendingTest?.id && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
							{testError ? '重新测试' : '开始测试'}
						</Button>
					</DialogFooter>
				</DialogContent>
			</Dialog>

			<Dialog open={pendingDelete !== null} onOpenChange={(open) => !open && setPendingDelete(null)}>
				<DialogContent>
					<DialogHeader>
						<DialogTitle>删除确认</DialogTitle>
						<DialogDescription>确定要删除模型配置「{pendingDelete?.modelName}」吗？使用中的配置无法删除。</DialogDescription>
					</DialogHeader>
					<DialogFooter>
						<Button variant="outline" onClick={() => setPendingDelete(null)}>取消</Button>
						<Button variant="destructive" disabled={busyId !== null} onClick={runDelete}>确认删除</Button>
					</DialogFooter>
				</DialogContent>
			</Dialog>
		</div>
	);
}

type ReadinessCardProps = {
	label: string;
	configured?: boolean;
	state?: ModelReadiness['chatState'];
	provider?: string | null;
	modelName?: string | null;
	testedAt?: string | null;
	errorMessage?: string | null;
	queryError?: boolean;
	note: string;
};

function readinessTone(state?: string, queryError?: boolean) {
	if (queryError) return 'border-red-200 bg-red-50 text-red-700';
	switch (state) {
		case 'VERIFIED':
			return 'border-green-200 bg-green-50 text-green-700';
		case 'TEST_FAILED':
			return 'border-red-200 bg-red-50 text-red-700';
		case 'CONFIGURED_UNVERIFIED':
			return 'border-amber-200 bg-amber-50 text-amber-700';
		default:
			return 'border-slate-200 bg-slate-50 text-slate-600';
	}
}

function ReadinessCard({ label, configured, state, provider, modelName, testedAt, errorMessage, queryError, note }: ReadinessCardProps) {
	return (
		<Card>
			<CardContent className="flex h-full flex-col gap-2 pt-4">
				<div className="flex items-start justify-between gap-3">
					<span className="text-sm text-[#5a708c]">{label}</span>
					{queryError ? (
						<Badge variant="outline" className={readinessTone(state, true)}>读取失败</Badge>
					) : (
						<Badge variant="outline" className={readinessTone(state)}>{readinessStateLabel(state)}</Badge>
					)}
				</div>
				<div className="text-[13px] font-medium text-[#1f2a37]">
					{configured && modelName ? `${provider ?? 'custom'} / ${modelName}` : '尚未配置激活配置'}
				</div>
				{testedAt ? (
					<div className="text-[11px] text-[#6a7d91]">最近测试：{formatTime(testedAt)}</div>
				) : (
					<div className="text-[11px] text-[#8a9aab]">尚无本进程内的连接测试记录</div>
				)}
				{errorMessage && (
					<div className="rounded-lg border border-red-100 bg-red-50/70 px-2 py-1.5 text-[11px] leading-relaxed text-[#8f3b45]" title={errorMessage}>
						{errorMessage}
					</div>
				)}
				<div className="mt-auto text-[11px] leading-relaxed text-[#7a8da1]">{note}</div>
			</CardContent>
		</Card>
	);
}
