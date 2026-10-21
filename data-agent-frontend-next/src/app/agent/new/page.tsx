'use client';

import { useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import Link from 'next/link';
import { toast } from 'sonner';
import { ArrowLeft, Loader2, Save, Upload } from 'lucide-react';
import { PageHeader } from '@/components/page-header';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Textarea } from '@/components/ui/textarea';
import { uploadAvatar, type AgentStatus } from '@/lib/api/agent';
import { errorMessage } from '@/lib/api/client';
import { useCreateAgent } from '@/lib/query/hooks';
import { validateAvatarFile } from '@/lib/upload';

const STATUS_ITEMS: Record<string, string> = { draft: '草稿', published: '已发布', offline: '已下线' };

type CreateForm = {
	name: string;
	category: string;
	tags: string;
	description: string;
	prompt: string;
	status: AgentStatus;
	avatar: string;
};

const INITIAL: CreateForm = {
	name: '',
	category: '',
	tags: '',
	description: '',
	prompt: '',
	status: 'draft',
	avatar: '',
};

export default function NewAgentPage() {
	const router = useRouter();
	const createMutation = useCreateAgent();
	const fileInputRef = useRef<HTMLInputElement>(null);
	const [form, setForm] = useState<CreateForm>(INITIAL);
	const [uploading, setUploading] = useState(false);
	const [error, setError] = useState<string | null>(null);

	const set = <K extends keyof CreateForm>(key: K, value: CreateForm[K]) =>
		setForm((prev) => ({ ...prev, [key]: value }));

	const pickAvatar = async (file: File | null) => {
		if (!file) return;
		const validation = validateAvatarFile(file);
		if (!validation.ok) {
			toast.error(validation.message);
			return;
		}
		setUploading(true);
		try {
			const result = await uploadAvatar(file);
			set('avatar', result.url ?? '');
			toast.success('头像上传成功');
		} catch (uploadError) {
			toast.error('头像上传失败', { description: errorMessage(uploadError) });
		} finally {
			setUploading(false);
			if (fileInputRef.current) fileInputRef.current.value = '';
		}
	};

	const submit = async () => {
		if (createMutation.isPending || uploading) return;
		setError(null);
		if (!form.name.trim()) {
			toast.warning('请输入智能体名称');
			return;
		}
		if (!form.category.trim()) {
			toast.warning('请输入分类');
			return;
		}
		if (!form.tags.trim()) {
			toast.warning('请填写标签（逗号分隔）');
			return;
		}

		try {
			const agent = await createMutation.mutateAsync({
				name: form.name.trim(),
				category: form.category.trim(),
				tags: form.tags.trim(),
				description: form.description.trim(),
				prompt: form.prompt.trim(),
				status: form.status,
				avatar: form.avatar || undefined,
			});
			toast.success('智能体创建成功', { description: agent?.name ?? form.name.trim() });
			router.push('/system/agents');
		} catch (createError) {
			const message = errorMessage(createError);
			setError(message);
			toast.error('创建失败', { description: message });
		}
	};

	return (
		<div className="mx-auto max-w-4xl space-y-6">
			<PageHeader
				title="新建智能体"
				breadcrumb="知识与智能体 / 智能体管理 / 新建"
				description="创建后可在智能体管理中配置知识、提示词与可用数据源。"
				actions={
					<Link href="/system/agents">
						<Button variant="outline">
							<ArrowLeft className="mr-2 h-4 w-4" />
							返回列表
						</Button>
					</Link>
				}
			/>

			<Card>
				<CardHeader>
					<CardTitle className="text-lg">基本信息</CardTitle>
				</CardHeader>
				<CardContent className="space-y-5">
					<div className="grid gap-5 md:grid-cols-2">
						<div className="space-y-2">
							<Label htmlFor="agent-name">名称 *</Label>
							<Input
								id="agent-name"
								value={form.name}
								placeholder="例如：电商订单分析智能体"
								maxLength={100}
								onChange={(event) => set('name', event.target.value)}
							/>
						</div>
						<div className="space-y-2">
							<Label htmlFor="agent-category">分类 *</Label>
							<Input
								id="agent-category"
								value={form.category}
								placeholder="例如：电商分析"
								maxLength={50}
								onChange={(event) => set('category', event.target.value)}
							/>
						</div>
					</div>

					<div className="space-y-2">
						<Label htmlFor="agent-tags">标签 *</Label>
						<Input
							id="agent-tags"
							value={form.tags}
							placeholder="用逗号分隔，例如：订单分析,商品分析,用户分析"
							onChange={(event) => set('tags', event.target.value)}
						/>
					</div>

					<div className="space-y-2">
						<Label htmlFor="agent-description">描述</Label>
						<Textarea
							id="agent-description"
							rows={3}
							value={form.description}
							placeholder="说明该智能体的业务范围"
							onChange={(event) => set('description', event.target.value)}
						/>
					</div>

					<div className="space-y-2">
						<Label htmlFor="agent-prompt">系统提示词</Label>
						<Textarea
							id="agent-prompt"
							rows={5}
							value={form.prompt}
							placeholder="约束智能体的角色、数据范围和回答方式"
							onChange={(event) => set('prompt', event.target.value)}
						/>
						<p className="text-xs text-[#5a708c]">提示词会作为该智能体对话的系统约束，保存后立即生效。</p>
					</div>

					<div className="grid gap-5 md:grid-cols-2">
						<div className="space-y-2">
							<Label>状态</Label>
							<Select items={STATUS_ITEMS} value={form.status} onValueChange={(value) => set('status', (value ?? 'draft') as AgentStatus)}>
								<SelectTrigger>
									<SelectValue />
								</SelectTrigger>
								<SelectContent>
									<SelectItem value="draft">草稿</SelectItem>
									<SelectItem value="published">已发布</SelectItem>
									<SelectItem value="offline">已下线</SelectItem>
								</SelectContent>
							</Select>
							<p className="text-xs text-[#5a708c]">状态由后端字段直接保存，发布/下线操作可在列表中随时调整。</p>
						</div>

						<div className="space-y-2">
							<Label>头像（可选，≤2MB）</Label>
							<div className="flex items-center gap-3">
								<div className="flex h-14 w-14 items-center justify-center overflow-hidden rounded-xl border border-[#dbe6f2] bg-[#f2f7fc]">
									{form.avatar ? (
										// eslint-disable-next-line @next/next/no-img-element
										<img src={form.avatar} alt="头像预览" className="h-full w-full object-cover" />
									) : (
										<Upload className="h-5 w-5 text-[#8aa2ba]" />
									)}
								</div>
								<input
									ref={fileInputRef}
									type="file"
									accept="image/*"
									className="hidden"
									onChange={(event) => pickAvatar(event.target.files?.[0] ?? null)}
								/>
								<Button variant="outline" type="button" disabled={uploading} onClick={() => fileInputRef.current?.click()}>
									{uploading ? <Loader2 className="mr-2 h-4 w-4 animate-spin" /> : <Upload className="mr-2 h-4 w-4" />}
									{uploading ? '上传中…' : '上传头像'}
								</Button>
							</div>
						</div>
					</div>

					{error && (
						<div className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-[#8f3b45]">
							{error}
						</div>
					)}

					<div className="flex justify-end gap-2 border-t border-[#e6eef7] pt-4">
						<Link href="/system/agents">
							<Button variant="outline">取消</Button>
						</Link>
						<Button
							className="bg-[#35629d] hover:bg-[#264c79]"
							disabled={createMutation.isPending || uploading}
							onClick={submit}
						>
							{createMutation.isPending ? <Loader2 className="mr-2 h-4 w-4 animate-spin" /> : <Save className="mr-2 h-4 w-4" />}
							{createMutation.isPending ? '创建中…' : '创建智能体'}
						</Button>
					</div>
				</CardContent>
			</Card>
		</div>
	);
}
