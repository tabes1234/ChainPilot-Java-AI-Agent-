'use client';

import { useEffect, useRef, useState } from 'react';
import { toast } from 'sonner';
import {
	cancelTask,
	fetchTask,
	fetchTaskEvents,
	submitTask,
	type AnalysisMode,
	type TaskEvent,
	type TaskStatus,
	type TaskSummary,
} from '@/lib/api/supplyChain';
import { errorMessage, friendlyErrorMessage, isApiError } from '@/lib/api/client';

const POLL_MS = 900;

/** Submits an async analysis/decision task and polls the real task status + events until it is terminal. */
export function useAnalysisTask(initialTaskId?: string | null) {
	const [taskId, setTaskId] = useState<string | null>(initialTaskId ?? null);
	const [task, setTask] = useState<TaskStatus | null>(null);
	const [events, setEvents] = useState<TaskEvent[]>([]);
	const [error, setError] = useState<string | null>(null);
	const [submitting, setSubmitting] = useState(false);
	const [cancelling, setCancelling] = useState(false);
	const timerRef = useRef<number | null>(null);
	const requestRef = useRef<AbortController | null>(null);
	const mountedRef = useRef(true);
	const labelRef = useRef('任务');
	const isAbort = (error: unknown) => isApiError(error) && error.status === 0 && error.message === '请求已取消';

	const stopPolling = () => {
		if (timerRef.current) {
			window.clearTimeout(timerRef.current);
			timerRef.current = null;
		}
		requestRef.current?.abort();
		requestRef.current = null;
	};

	useEffect(() => {
		mountedRef.current = true;
		return () => {
			mountedRef.current = false;
			stopPolling();
		};
	}, []);

	const beginRequest = () => {
		requestRef.current?.abort();
		const controller = new AbortController();
		requestRef.current = controller;
		return controller;
	};

	const poll = async (id: string) => {
		const controller = beginRequest();
		try {
			const status = await fetchTask(id, { signal: controller.signal });
			if (!mountedRef.current || controller.signal.aborted) return;
			setTask(status);
			setError(status.error ?? null);
			try {
				const taskEvents = await fetchTaskEvents(id, { signal: controller.signal });
				if (mountedRef.current && !controller.signal.aborted) setEvents(taskEvents);
			} catch (eventsError) {
				if (isAbort(eventsError)) return;
				/* events are optional for the status display */
			}
			if (status.status === 'ACCEPTED' || status.status === 'RUNNING') {
				timerRef.current = window.setTimeout(() => void poll(id), POLL_MS);
			} else if (status.status === 'COMPLETED') {
				toast.success(`${labelRef.current}已完成`, { description: '结果与执行事件已就绪，可在下方查看。' });
			} else if (status.status === 'FAILED') {
				toast.error(`${labelRef.current}执行失败`, { description: status.error ?? '请查看任务状态中的失败原因。' });
			} else if (status.status === 'CANCELLED') {
				toast.info(`${labelRef.current}已取消`);
			}
		} catch (pollError) {
			if (!mountedRef.current || isAbort(pollError)) return;
			setError(friendlyErrorMessage(pollError, '查询任务状态失败', {
				404: '任务不存在或已被清理，请从任务记录重新打开。',
			}));
			if (isApiError(pollError) && pollError.status === 404) {
				stopPolling();
				return;
			}
			timerRef.current = window.setTimeout(() => void poll(id), POLL_MS * 2);
		}
	};

	// Deep links (?taskId=) attach to an existing task and resume polling if it is still running.
	useEffect(() => {
		if (!initialTaskId) return;
		stopPolling();
		const controller = beginRequest();
		void (async () => {
			try {
				const status = await fetchTask(initialTaskId, { signal: controller.signal });
				if (!mountedRef.current || controller.signal.aborted) return;
				setTaskId(initialTaskId);
				setTask(status);
				setError(status.error ?? null);
				if (status.status === 'ACCEPTED' || status.status === 'RUNNING') {
					void poll(initialTaskId);
				} else {
					const taskEvents = await fetchTaskEvents(initialTaskId, { signal: controller.signal }).catch(() => []);
					if (mountedRef.current && !controller.signal.aborted) setEvents(taskEvents);
				}
			} catch (loadError) {
				if (mountedRef.current && !isAbort(loadError)) {
					setError(friendlyErrorMessage(loadError, '加载任务失败', {
						404: '任务不存在或已被清理，请从任务记录重新打开。',
					}));
				}
			}
		})();
		// eslint-disable-next-line react-hooks/exhaustive-deps
	}, [initialTaskId]);

	const start = async (question: string, mode: AnalysisMode = 'ANALYSIS') => {
		stopPolling();
		setSubmitting(true);
		setError(null);
		setEvents([]);
		setTask(null);
		setTaskId(null);
		labelRef.current = mode === 'DECISION' ? '决策任务' : '分析任务';
		try {
			const status = await submitTask(question, mode);
			if (!mountedRef.current) return;
			setTaskId(status.taskId);
			setTask(status);
			toast.info(`${labelRef.current}已提交`, { description: '后端正在执行，可随时查看任务状态与执行事件。' });
			void poll(status.taskId);
		} catch (submitError) {
			if (mountedRef.current) {
				const message = errorMessage(submitError, '提交任务失败');
				setError(message);
				toast.error('任务提交失败', { description: message });
			}
		} finally {
			if (mountedRef.current) setSubmitting(false);
		}
	};

	const attach = (summary: Pick<TaskSummary, 'taskId' | 'status' | 'currentStep' | 'validationAttempts' | 'error' | 'updatedAt'>) => {
		stopPolling();
		setTaskId(summary.taskId);
		setTask(summary);
		setError(summary.error ?? null);
		setEvents([]);
		if (summary.status === 'ACCEPTED' || summary.status === 'RUNNING') {
			void poll(summary.taskId);
		}
	};

	const cancel = async () => {
		if (!taskId) return null;
		setCancelling(true);
		try {
			const status = await cancelTask(taskId);
			if (!mountedRef.current) return status;
			if (status.status === 'CANCELLED' || status.status === 'COMPLETED' || status.status === 'FAILED') {
				stopPolling();
				setTask(status);
				setError(status.error ?? null);
			}
			return status;
		} catch (cancelError) {
			if (mountedRef.current) {
				setError(friendlyErrorMessage(cancelError, '取消任务失败', {
					404: '任务不存在或已被清理，请刷新任务记录。',
				}));
			}
			return null;
		} finally {
			if (mountedRef.current) setCancelling(false);
		}
	};

	const reset = () => {
		stopPolling();
		setTaskId(null);
		setTask(null);
		setEvents([]);
		setError(null);
	};

	const running = task?.status === 'ACCEPTED' || task?.status === 'RUNNING';

	return { taskId, task, events, error, submitting, cancelling, running, start, attach, cancel, reset, stopPolling };
}
