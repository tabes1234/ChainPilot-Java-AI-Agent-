'use client';

import { useEffect, useMemo, useRef, useState } from 'react';
import { toast } from 'sonner';
import {
	Loader2,
	MessageSquarePlus,
	MoreVertical,
	Pin,
	PinOff,
	Trash2,
	Download,
	Pencil,
	ChevronDown,
	AlertTriangle,
	MessagesSquare,
	Sparkles,
} from 'lucide-react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { PageHeader } from '@/components/page-header';
import { EmptyState, ErrorState, LoadingState } from '@/components/data-states';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Sheet, SheetContent, SheetHeader, SheetTitle, SheetTrigger } from '@/components/ui/sheet';
import { errorMessage } from '@/lib/api/client';
import { fetchPresetQuestions } from '@/lib/api/agent';
import {
	buildStreamUrl,
	clearChatSessions,
	createChatSession,
	deleteChatSession,
	downloadChatReport,
	fetchChatMessages,
	fetchChatSessions,
	pinChatSession,
	renameChatSession,
	saveChatMessage,
	stopChatStream,
	type ChatMessage,
	type ChatSession,
	type StreamEvent,
} from '@/lib/api/chat';
import { PromptComposer } from '@/components/prompt-composer';
import { useAgents } from '@/lib/query/hooks';
import { useUrlParam } from '@/lib/use-url-param';

type Live = {
	running: boolean;
	threadId: string | null;
	events: StreamEvent[];
	answer: string;
	finalText: string;
	error: string | null;
	feedbackPrompt: string | null;
	startedAt: string | null;
};

const EMPTY_LIVE: Live = {
	running: false,
	threadId: null,
	events: [],
	answer: '',
	finalText: '',
	error: null,
	feedbackPrompt: null,
	startedAt: null,
};

const MAX_TIMELINE_EVENTS = 300;

export default function ChatView() {
	const queryClient = useQueryClient();
	const agentsQuery = useAgents();
	const agents = useMemo(() => agentsQuery.data ?? [], [agentsQuery.data]);

	const requestedAgentId = useUrlParam('agentId');

	const [manualAgentId, setManualAgentId] = useState<number | null>(null);
	const agentId = useMemo(() => {
		if (agents.length === 0) return null;
		const candidate = manualAgentId ?? Number(requestedAgentId);
		if (candidate && agents.some((agent) => agent.id === candidate)) return candidate;
		return agents.find((agent) => (agent.id ?? 0) > 0)?.id ?? null;
	}, [agents, manualAgentId, requestedAgentId]);

	const sessionsQuery = useQuery({
		queryKey: ['chatSessions', agentId],
		queryFn: () => fetchChatSessions(agentId!),
		enabled: agentId !== null,
	});
	const sessions = useMemo(() => sessionsQuery.data ?? [], [sessionsQuery.data]);

	const [selectedSessionId, setSelectedSessionId] = useState<string | null>(null);
	const activeSessionId = selectedSessionId ?? sessions[0]?.id ?? null;
	const activeSession = sessions.find((session) => session.id === activeSessionId) ?? null;

	const messagesQuery = useQuery({
		queryKey: ['chatMessages', activeSessionId],
		queryFn: () => fetchChatMessages(activeSessionId!),
		enabled: activeSessionId !== null,
	});
	const messages = useMemo(() => messagesQuery.data ?? [], [messagesQuery.data]);

	const presetQuery = useQuery({
		queryKey: ['presetQuestions', agentId],
		queryFn: () => fetchPresetQuestions(agentId!),
		enabled: agentId !== null,
	});
	const presetQuestions = (presetQuery.data ?? []).filter((question) => question.isActive !== false).slice(0, 4);

	const [input, setInput] = useState('');

	const [live, setLive] = useState<Live>(EMPTY_LIVE);
	const liveRef = useRef<Live>(EMPTY_LIVE);
	const esRef = useRef<EventSource | null>(null);
	const lastQueryRef = useRef('');
	const lastSessionRef = useRef<string | null>(null);

	const [renameTarget, setRenameTarget] = useState<ChatSession | null>(null);
	const [renameValue, setRenameValue] = useState('');
	const [deleteTarget, setDeleteTarget] = useState<ChatSession | null>(null);
	const [busy, setBusy] = useState(false);
	const [sessionSheetOpen, setSessionSheetOpen] = useState(false);
	const [clearOpen, setClearOpen] = useState(false);

	const setLiveState = (patch: Partial<Live>) => {
		liveRef.current = { ...liveRef.current, ...patch };
		setLive(liveRef.current);
	};

	// Session title updates are pushed by the backend over SSE.
	useEffect(() => {
		if (agentId === null) return;
		const source = new EventSource(`/api/agent/${agentId}/sessions/stream`);
		const onTitle = (event: MessageEvent) => {
			try {
				const payload = JSON.parse(event.data) as { sessionId?: string; title?: string };
				if (payload.sessionId && payload.title) {
					queryClient.invalidateQueries({ queryKey: ['chatSessions', agentId] });
				}
			} catch {
				/* ignore malformed events */
			}
		};
		source.addEventListener('title-updated', onTitle as EventListener);
		return () => {
			source.removeEventListener('title-updated', onTitle as EventListener);
			source.close();
		};
	}, [agentId, queryClient]);

	useEffect(() => {
		return () => {
			esRef.current?.close();
		};
	}, []);

	const invalidateMessages = (sessionId: string) =>
		queryClient.invalidateQueries({ queryKey: ['chatMessages', sessionId] });
	const invalidateSessions = () =>
		queryClient.invalidateQueries({ queryKey: ['chatSessions', agentId] });

	const finalize = async (sessionId: string, mode: 'complete' | 'error' | 'stopped') => {
		const current = liveRef.current;
		const answer = current.finalText || current.answer;
		try {
			if (mode === 'error') {
				await saveChatMessage(sessionId, {
					role: 'assistant',
					content: current.error || '分析失败，请稍后重试',
					messageType: 'error',
				});
			} else if (mode === 'stopped') {
				await saveChatMessage(sessionId, {
					role: 'assistant',
					content: answer || '已停止本次分析。',
					messageType: 'warning',
				});
			} else {
				await saveChatMessage(sessionId, {
					role: 'assistant',
					content: answer || '分析已完成，但模型未返回文本内容。',
					messageType: 'text',
				});
				if (current.events.length > 0) {
					await saveChatMessage(sessionId, {
						role: 'assistant',
						content: JSON.stringify(
							current.events.slice(0, MAX_TIMELINE_EVENTS).map((event) => ({
								event: event.eventType,
								node: event.nodeName,
								type: event.textType,
								stepId: event.stepId,
								attempt: event.attempt,
								text: (event.text ?? '').slice(0, 4000),
								error: event.error,
							})),
						),
						messageType: 'timeline',
					});
				}
			}
		} catch (error) {
			toast.error('保存会话消息失败', { description: errorMessage(error) });
		} finally {
			invalidateMessages(sessionId);
			invalidateSessions();
		}
	};

	const stopStream = async () => {
		const sessionId = lastSessionRef.current;
		esRef.current?.close();
		esRef.current = null;
		if (sessionId) {
			try {
				await stopChatStream(sessionId, liveRef.current.threadId ?? undefined);
			} catch (error) {
				toast.error('停止请求失败', { description: errorMessage(error) });
			}
			setLiveState({ running: false });
			await finalize(sessionId, 'stopped');
		}
	};

	const startStream = (
		sessionId: string,
		query: string,
		options?: { threadId?: string; humanFeedback?: boolean; humanFeedbackContent?: string; rejectedPlan?: boolean },
	) => {
		esRef.current?.close();
		lastSessionRef.current = sessionId;
		lastQueryRef.current = query;
		setLiveState({ ...EMPTY_LIVE, running: true, startedAt: new Date().toISOString() });

		const source = new EventSource(
			buildStreamUrl({
				agentId: agentId!,
				conversationId: sessionId,
				query,
				threadId: options?.threadId,
				humanFeedback: options?.humanFeedback,
				humanFeedbackContent: options?.humanFeedbackContent,
				rejectedPlan: options?.rejectedPlan,
			}),
		);
		esRef.current = source;

		const handleEvent = (event: StreamEvent) => {
			const current = liveRef.current;
			const events = event.eventType
				? [...current.events, event].slice(-MAX_TIMELINE_EVENTS)
				: current.events;
			let answer = current.answer;
			let finalText = current.finalText;
			let feedbackPrompt = current.feedbackPrompt;
			const threadId = event.threadId ?? current.threadId;

			if (event.eventType === 'HUMAN_FEEDBACK_REQUIRED') {
				feedbackPrompt = event.text || '模型请求人工确认后继续。';
			} else if (event.eventType === 'FINAL_ANSWER' && event.text) {
				finalText = event.text;
			} else if (event.textType === 'MARK_DOWN' && event.text) {
				answer = event.text;
			} else if (event.textType === 'TEXT' && event.text && !finalText) {
				answer = answer ? `${answer}\n${event.text}` : event.text;
			}

			setLiveState({ events, answer, finalText, feedbackPrompt, threadId });
		};

		source.onmessage = (message) => {
			try {
				handleEvent(JSON.parse(message.data) as StreamEvent);
			} catch {
				/* ignore malformed payloads */
			}
		};

		source.addEventListener('complete', () => {
			esRef.current?.close();
			esRef.current = null;
			setLiveState({ running: false });
			void finalize(sessionId, 'complete');
		});

		source.addEventListener('error', (message) => {
			if (message instanceof MessageEvent && message.data) {
				try {
					const payload = JSON.parse(message.data) as StreamEvent;
					esRef.current?.close();
					esRef.current = null;
					setLiveState({ running: false, error: payload.error || payload.text || '分析失败' });
					void finalize(sessionId, 'error');
					return;
				} catch {
					/* fall through */
				}
			}
			if (liveRef.current.running) {
				esRef.current?.close();
				esRef.current = null;
				setLiveState({ running: false, error: '流式连接中断，请稍后重试' });
				void finalize(sessionId, 'error');
			}
		});
	};

	const send = async () => {
		const text = input.trim();
		if (!text || agentId === null || live.running) return;
		setBusy(true);
		try {
			let sessionId = activeSessionId;
			if (!sessionId) {
				const session = await createChatSession(agentId, text.slice(0, 30));
				sessionId = session.id;
				setSelectedSessionId(session.id);
				invalidateSessions();
			}
			const titleNeeded = sessionId !== activeSessionId || messages.length === 0;
			await saveChatMessage(sessionId, { role: 'user', content: text, messageType: 'text', titleNeeded });
			setInput('');
			invalidateMessages(sessionId);
			startStream(sessionId, text);
		} catch (error) {
			toast.error('发送失败', { description: errorMessage(error) });
		} finally {
			setBusy(false);
		}
	};

	const resumeWithFeedback = async (accepted: boolean) => {
		if (!live.feedbackPrompt || !activeSessionId || agentId === null) return;
		const feedback = input.trim() || (accepted ? '同意执行该计划，请继续。' : '拒绝执行该计划。');
		setBusy(true);
		try {
			await saveChatMessage(activeSessionId, { role: 'user', content: feedback, messageType: 'text' });
			setInput('');
			invalidateMessages(activeSessionId);
			startStream(activeSessionId, lastQueryRef.current, {
				threadId: live.threadId ?? undefined,
				humanFeedback: accepted,
				humanFeedbackContent: accepted ? feedback : undefined,
				rejectedPlan: !accepted,
			});
		} catch (error) {
			toast.error('提交反馈失败', { description: errorMessage(error) });
		} finally {
			setBusy(false);
		}
	};

	const newSession = async () => {
		if (agentId === null) return;
		setBusy(true);
		try {
			const session = await createChatSession(agentId);
			setSelectedSessionId(session.id);
			invalidateSessions();
			setSessionSheetOpen(false);
		} catch (error) {
			toast.error('新建会话失败', { description: errorMessage(error) });
		} finally {
			setBusy(false);
		}
	};

	const togglePin = async (session: ChatSession) => {
		try {
			await pinChatSession(session.id, !session.isPinned);
			toast.success(session.isPinned ? '已取消置顶' : '已置顶');
			invalidateSessions();
		} catch (error) {
			toast.error('操作失败', { description: errorMessage(error) });
		}
	};

	const runRename = async () => {
		if (!renameTarget) return;
		if (!renameValue.trim()) {
			toast.warning('标题不能为空');
			return;
		}
		try {
			await renameChatSession(renameTarget.id, renameValue.trim());
			toast.success('会话已重命名');
			setRenameTarget(null);
			invalidateSessions();
		} catch (error) {
			toast.error('重命名失败', { description: errorMessage(error) });
		}
	};

	const clearAllSessions = async () => {
		if (agentId === null) return;
		try {
			await clearChatSessions(agentId);
			setSelectedSessionId(null);
			setLive(EMPTY_LIVE);
			invalidateSessions();
			toast.success('会话已清空');
			setClearOpen(false);
		} catch (error) {
			toast.error('清空会话失败', { description: errorMessage(error) });
		}
	};

	const runDelete = async () => {
		if (!deleteTarget) return;
		try {
			await deleteChatSession(deleteTarget.id);
			toast.success('会话已删除');
			if (deleteTarget.id === activeSessionId) setSelectedSessionId(null);
			setDeleteTarget(null);
			invalidateSessions();
		} catch (error) {
			toast.error('删除失败', { description: errorMessage(error) });
		}
	};

	const downloadReport = async () => {
		if (!activeSessionId) return;
		const htmlMessages = messages.filter((message) => message.messageType === 'text' || message.messageType === 'markdown-report');
		const html = `<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8"><title>${activeSession?.title ?? 'ChainPilot 报告'}</title></head><body>${htmlMessages
			.map((message) => `<article><h3>${message.role}</h3><pre>${escapeHtml(message.content)}</pre></article>`)
			.join('')}</body></html>`;
		try {
			await downloadChatReport(activeSessionId, html);
		} catch (error) {
			toast.error('导出报告失败', { description: errorMessage(error) });
		}
	};

	if (agentsQuery.isPending) return <LoadingState label="加载智能体…" />;
	if (agentsQuery.isError) {
		return <ErrorState message={errorMessage(agentsQuery.error, '智能体列表加载失败')} onRetry={() => agentsQuery.refetch()} />;
	}

	const sessionListProps = {
		sessions,
		activeSessionId,
		loading: sessionsQuery.isPending,
		error: sessionsQuery.isError ? errorMessage(sessionsQuery.error, '加载会话失败') : null,
		onSelect: (id: string) => {
			setSelectedSessionId(id);
			setSessionSheetOpen(false);
		},
		onRename: (session: ChatSession) => {
			setRenameTarget(session);
			setRenameValue(session.title ?? '');
		},
		onDelete: (session: ChatSession) => setDeleteTarget(session),
		onPin: togglePin,
		onNew: newSession,
		newDisabled: busy,
	};

	return (
		<div className="flex min-h-[calc(100vh-120px)] flex-col gap-5">
			<PageHeader
				title="数据问答"
				breadcrumb="工作台"
				description="用自然语言查询业务数据，回答、SQL 与执行过程均来自后端真实分析流程。"
				actions={
					<>
						<Select
							items={Object.fromEntries(agents.map((agent) => [String(agent.id), agent.name || `智能体 #${agent.id}`]))}
							value={agentId === null ? undefined : String(agentId)}
							onValueChange={(value) => {
								if (value) {
									setManualAgentId(Number(value));
									setSelectedSessionId(null);
									setLive(EMPTY_LIVE);
								}
							}}
						>
							<SelectTrigger className="w-[220px] rounded-full"><SelectValue placeholder="选择智能体" /></SelectTrigger>
							<SelectContent>
								{agents.map((agent) => <SelectItem key={agent.id} value={String(agent.id)}>{agent.name || `智能体 #${agent.id}`}</SelectItem>)}
							</SelectContent>
						</Select>
						<Button variant="outline" className="rounded-full" onClick={downloadReport} disabled={!activeSessionId || messages.length === 0}>
							<Download className="mr-2 h-4 w-4" />
							导出报告
						</Button>
					</>
				}
			/>

			{agents.length === 0 ? (
				<EmptyState title="没有可用的智能体" description="请先在智能体管理中创建智能体。" />
			) : (
				<div className="grid min-h-0 flex-1 gap-6 lg:grid-cols-[236px_minmax(0,1fr)]">
					{/* Desktop session rail — in normal flow, never fixed */}
					<aside className="hidden min-w-0 lg:block">
						<div className="mb-2 flex items-center justify-between px-1">
							<span className="cp-eyebrow">会话</span>
							<div className="flex items-center">
								<Button variant="ghost" size="sm" className="h-7 rounded-full px-2 text-xs text-[#4a769f]" onClick={newSession} disabled={busy}>
									<MessageSquarePlus className="mr-1 h-3.5 w-3.5" />
									新建
								</Button>
								{sessions.length > 0 && (
									<Button variant="ghost" size="sm" className="h-7 rounded-full px-2 text-xs text-[#7a8da1]" onClick={() => setClearOpen(true)} title="清空全部会话">
										<Trash2 className="mr-1 h-3.5 w-3.5" />清空
									</Button>
								)}
							</div>
						</div>
						<SessionList {...sessionListProps} />
					</aside>

					<section className="flex min-w-0 flex-col">
						<div className="mx-auto mb-2 flex w-full max-w-4xl items-center justify-between gap-2 px-1">
							<div className="flex min-w-0 items-center gap-2">
								<Sheet open={sessionSheetOpen} onOpenChange={setSessionSheetOpen}>
									<SheetTrigger
										render={
											<Button variant="ghost" size="sm" className="rounded-full lg:hidden">
												<MessagesSquare className="mr-1 h-4 w-4" />
												会话
											</Button>
										}
									/>
									<SheetContent side="left" className="w-[292px] max-w-[86vw] p-0">
										<SheetHeader className="px-4 pt-6">
											<SheetTitle className="flex items-center justify-between text-[15px]">
												会话
												<Button variant="ghost" size="sm" className="h-8 rounded-full text-xs" onClick={newSession} disabled={busy}>
													<MessageSquarePlus className="mr-1 h-3.5 w-3.5" />新建
												</Button>
											</SheetTitle>
										</SheetHeader>
										<div className="px-2 py-2"><SessionList {...sessionListProps} /></div>
									</SheetContent>
								</Sheet>
								<span className="truncate text-[13px] font-medium text-[#50667c]">{activeSession?.title || '新会话'}</span>
							</div>
							<div className="flex items-center gap-2">
								{live.running && (
									<span className="inline-flex items-center gap-1.5 rounded-full bg-white/70 px-3 py-1 text-[11.5px] text-[#4a769f] ring-1 ring-white/80">
										<span className="h-1.5 w-1.5 animate-pulse rounded-full bg-[#4f9c93]" />
										分析中
									</span>
								)}
								{activeSessionId && (
									<Button variant="ghost" size="icon" className="h-8 w-8 rounded-full" title="清除当前会话选择" aria-label="清除当前会话选择" onClick={() => setSelectedSessionId(null)}>
										<MoreVertical className="h-4 w-4" />
									</Button>
								)}
							</div>
						</div>

						<div className="mx-auto flex w-full max-w-4xl flex-1 flex-col">
							<div className="flex-1 space-y-5 py-4">
								{messagesQuery.isPending && activeSessionId ? (
									<LoadingState compact label="加载消息…" />
								) : messages.length === 0 && !live.running && !live.error ? (
									<div className="flex min-h-[300px] flex-col items-center justify-center gap-3 text-center">
										<span className="flex h-12 w-12 items-center justify-center rounded-2xl bg-white/70 ring-1 ring-white/80">
											<Sparkles className="h-5 w-5 text-[#6f8fb8]" strokeWidth={1.8} />
										</span>
										<p className="text-[15px] font-medium text-[#2b3d51]">从一个业务问题开始</p>
										<p className="mx-auto max-w-md text-[13px] leading-relaxed text-[#6a7d91]">
											例如「昨天各品类的订单金额是多少？」。回答会展示真实执行节点、SQL 与结果，失败时会明确提示。
										</p>
										{presetQuestions.length > 0 && (
											<div className="mt-2 flex flex-wrap justify-center gap-2">
												{presetQuestions.map((question) => (
													<button
														key={question.id ?? question.question}
														type="button"
														className="rounded-full border border-white/80 bg-white/55 px-3 py-1.5 text-[12px] text-[#50667c] transition-colors hover:bg-white/85 hover:text-[#2b5583]"
														onClick={() => setInput(question.question)}
													>
														{question.question}
													</button>
												))}
											</div>
										)}
									</div>
								) : (
									messages.map((message) => <MessageBubble key={message.id ?? `${message.role}-${message.createTime}`} message={message} />)
								)}

								{live.error && (
									<div className="rounded-2xl border border-red-200/80 bg-red-50/80 px-4 py-3 text-[13px] text-[#8f3b45]">
										{live.error}
									</div>
								)}

								{live.running && (
									<div className="space-y-2 rounded-2xl bg-white/55 px-4 py-3 ring-1 ring-white/70">
										<div className="flex items-center gap-2 text-[12px] text-[#5b7187]">
											<Loader2 className="h-3.5 w-3.5 animate-spin text-[#4a769f]" />
											正在执行 · 已收到 {live.events.length} 个节点事件
										</div>
										{live.answer && <p className="whitespace-pre-wrap text-[14px] leading-relaxed text-[#243548]">{live.answer}</p>}
										{live.events.length > 0 && (
											<details className="text-[11.5px] text-[#7a8da1]">
												<summary className="cursor-pointer">查看执行过程</summary>
												<ul className="mt-2 space-y-1">
													{live.events.slice(-8).map((event, index) => (
														<li key={`${event.stepId ?? index}-${index}`} className="truncate">
															{event.nodeName ?? '节点'} · {event.textType ?? event.eventType}：{(event.text ?? '').slice(0, 100) || '—'}
														</li>
													))}
												</ul>
											</details>
										)}
									</div>
								)}

								{live.feedbackPrompt && !live.running && (
									<div className="space-y-2 rounded-2xl border border-amber-200/80 bg-amber-50/70 px-4 py-3">
										<div className="flex items-center gap-2 text-[13px] font-medium text-[#8b6328]">
											<AlertTriangle className="h-4 w-4" />需要人工确认
										</div>
										<p className="whitespace-pre-wrap text-[13px] text-[#7a5a26]">{live.feedbackPrompt}</p>
										<div className="flex gap-2">
											<Button size="sm" className="rounded-full" disabled={busy} onClick={() => resumeWithFeedback(true)}>同意并继续</Button>
											<Button size="sm" variant="outline" className="rounded-full" disabled={busy} onClick={() => resumeWithFeedback(false)}>拒绝</Button>
										</div>
									</div>
								)}
							</div>

							<div className="pt-2">
								<PromptComposer
									value={input}
									onChange={setInput}
									onSubmit={() => { void send(); }}
									running={live.running}
									busy={busy}
									onStop={stopStream}
									placeholder={live.running ? '分析进行中，可先停止后再发送…' : '输入问题，Enter 发送，Shift + Enter 换行'}
								/>
							</div>
						</div>
					</section>
				</div>
			)}

			<Dialog open={clearOpen} onOpenChange={setClearOpen}>
				<DialogContent>
					<DialogHeader>
						<DialogTitle>清空会话</DialogTitle>
						<DialogDescription>将软删除该智能体的全部会话与消息（后端 DELETE 接口）。确定继续？</DialogDescription>
					</DialogHeader>
					<DialogFooter>
						<Button variant="outline" className="rounded-full" onClick={() => setClearOpen(false)}>取消</Button>
						<Button variant="destructive" className="rounded-full" onClick={clearAllSessions}>确认清空</Button>
					</DialogFooter>
				</DialogContent>
			</Dialog>

			<Dialog open={renameTarget !== null} onOpenChange={(open) => !open && setRenameTarget(null)}>
				<DialogContent>
					<DialogHeader>
						<DialogTitle>重命名会话</DialogTitle>
						<DialogDescription>标题会立即保存到后端。</DialogDescription>
					</DialogHeader>
					<Input value={renameValue} onChange={(event) => setRenameValue(event.target.value)} />
					<DialogFooter>
						<Button variant="outline" className="rounded-full" onClick={() => setRenameTarget(null)}>取消</Button>
						<Button className="rounded-full" onClick={runRename}>保存</Button>
					</DialogFooter>
				</DialogContent>
			</Dialog>

			<Dialog open={deleteTarget !== null} onOpenChange={(open) => !open && setDeleteTarget(null)}>
				<DialogContent>
					<DialogHeader>
						<DialogTitle>删除会话</DialogTitle>
						<DialogDescription>确定要删除「{deleteTarget?.title || '未命名会话'}」吗？该操作会软删除会话及其消息。</DialogDescription>
					</DialogHeader>
					<DialogFooter>
						<Button variant="outline" className="rounded-full" onClick={() => setDeleteTarget(null)}>取消</Button>
						<Button variant="destructive" className="rounded-full" onClick={runDelete}>确认删除</Button>
					</DialogFooter>
				</DialogContent>
			</Dialog>
		</div>
	);
}

function SessionList({
	sessions,
	activeSessionId,
	loading,
	error,
	onSelect,
	onRename,
	onDelete,
	onPin,
	onNew,
	newDisabled,
}: {
	sessions: ChatSession[];
	activeSessionId: string | null;
	loading: boolean;
	error: string | null;
	onSelect: (id: string) => void;
	onRename: (session: ChatSession) => void;
	onDelete: (session: ChatSession) => void;
	onPin: (session: ChatSession) => void;
	onNew: () => void;
	newDisabled: boolean;
}) {
	if (loading) return <LoadingState compact label="加载会话…" />;
	if (error) return <p className="px-2 py-3 text-sm text-[#8f3b45]">{error}</p>;
	if (sessions.length === 0) {
		return (
			<div className="px-2 py-4 text-[12.5px] leading-relaxed text-[#7a8da1]">
				暂无会话。发送第一条问题会自动创建。
				<Button variant="ghost" size="sm" className="mt-2 w-full justify-start rounded-full px-2 text-xs text-[#4a769f]" onClick={onNew} disabled={newDisabled}>
					<MessageSquarePlus className="mr-1 h-3.5 w-3.5" />新建会话
				</Button>
			</div>
		);
	}
	return (
		<div className="space-y-0.5">
			{sessions.map((session) => {
				const active = session.id === activeSessionId;
				return (
					<div
						key={session.id}
						className={`group rounded-2xl px-2 py-2 transition-colors ${active ? 'bg-white/80 ring-1 ring-white/80' : 'hover:bg-white/55'}`}
					>
						<button className="flex w-full items-center gap-1.5 text-left" onClick={() => onSelect(session.id)}>
							{session.isPinned && <Pin className="h-3 w-3 shrink-0 text-[#4a769f]" />}
							<span className={`min-w-0 flex-1 truncate text-[13px] ${active ? 'font-semibold text-[#233448]' : 'font-medium text-[#3c5064]'}`}>
								{session.title || '未命名会话'}
							</span>
						</button>
						<div className="mt-1 flex items-center justify-between">
							<span className="truncate text-[10.5px] text-[#93a3b5]">{session.updateTime ?? session.createTime ?? ''}</span>
							<div className="flex opacity-0 transition-opacity group-hover:opacity-100">
								<Button variant="ghost" size="icon" className="h-6 w-6 rounded-full" title="重命名" aria-label="重命名会话" onClick={() => onRename(session)}>
									<Pencil className="h-3 w-3" />
								</Button>
								<Button variant="ghost" size="icon" className="h-6 w-6 rounded-full" title={session.isPinned ? '取消置顶' : '置顶'} aria-label={session.isPinned ? '取消置顶' : '置顶'} onClick={() => onPin(session)}>
									{session.isPinned ? <PinOff className="h-3 w-3" /> : <Pin className="h-3 w-3" />}
								</Button>
								<Button variant="ghost" size="icon" className="h-6 w-6 rounded-full" title="删除" aria-label="删除会话" onClick={() => onDelete(session)}>
									<Trash2 className="h-3 w-3 text-[#b9575c]" />
								</Button>
							</div>
						</div>
					</div>
				);
			})}
		</div>
	);
}

function MessageBubble({ message }: { message: ChatMessage }) {
	if (message.messageType === 'timeline') {
		let events: { node?: string; type?: string; event?: string; text?: string }[] = [];
		try {
			events = JSON.parse(message.content) as typeof events;
		} catch {
			events = [];
		}
		return (
			<details className="rounded-2xl bg-white/45 px-4 py-2.5 text-[11.5px] text-[#7a8da1] ring-1 ring-white/60">
				<summary className="flex cursor-pointer items-center gap-1">
					<ChevronDown className="h-3 w-3" />执行轨迹（{events.length} 个事件）
				</summary>
				<ul className="mt-2 max-h-[240px] space-y-1 overflow-y-auto">
					{events.map((event, index) => (
						<li key={index} className="truncate">
							{event.node ?? '节点'} · {event.type ?? event.event ?? '事件'}：{(event.text ?? '').slice(0, 160) || '—'}
						</li>
					))}
				</ul>
			</details>
		);
	}

	const isUser = message.role === 'user';
	const isError = message.messageType === 'error';
	const isWarning = message.messageType === 'warning';

	if (isError || isWarning) {
		return (
			<div className={`rounded-2xl border px-4 py-3 text-[13px] ${isError ? 'border-red-200/80 bg-red-50/75 text-[#8f3b45]' : 'border-amber-200/80 bg-amber-50/70 text-[#8b6328]'}`}>
				<span className="mr-1 font-medium">{isError ? '执行失败' : '已停止'}：</span>
				<span className="whitespace-pre-wrap">{message.content}</span>
			</div>
		);
	}

	return (
		<div className={`flex ${isUser ? 'justify-end' : 'justify-start'}`}>
			<div
				className={`max-w-[min(100%,760px)] whitespace-pre-wrap text-[14px] leading-[1.75] ${
					isUser
						? 'rounded-[22px] rounded-br-md bg-[#e7f0fa] px-4 py-2.5 text-[#233448]'
						: 'px-1 py-1 text-[#243548]'
				}`}
			>
				{message.content}
			</div>
		</div>
	);
}

function escapeHtml(text: string): string {
	return text
		.replace(/&/g, '&amp;')
		.replace(/</g, '&lt;')
		.replace(/>/g, '&gt;')
		.replace(/"/g, '&quot;');
}
