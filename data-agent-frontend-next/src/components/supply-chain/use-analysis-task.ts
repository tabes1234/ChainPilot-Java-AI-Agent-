'use client';

import { useEffect, useRef, useState } from 'react';
import {
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
	const timerRef = useRef<number | null>(null);
	const mountedRef = useRef(true);

	useEffect(() => {
		mountedRef.current = true;
		return () => {
			mountedRef.current = false;
			if (timerRef.current) window.clearTimeout(timerRef.current);
		};
	}, []);

	const stopPolling = () => {
		if (timerRef.current) {
			window.clearTimeout(timerRef.current);
			timerRef.current = null;
		}
	};

	const poll = async (id: string) => {
		try {
			const status = await fetchTask(id);
			if (!mountedRef.current) return;
			setTask(status);
			setError(status.error ?? null);
			try {
				const taskEvents = await fetchTaskEvents(id);
				if (mountedRef.current) setEvents(taskEvents);
			} catch {
				/* events are optional for the status display */
			}
			if (status.status === 'ACCEPTED' || status.status === 'RUNNING') {
				timerRef.current = window.setTimeout(() => void poll(id), POLL_MS);
			}
		} catch (pollError) {
			if (!mountedRef.current) return;
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
		void (async () => {
			try {
				const status = await fetchTask(initialTaskId);
				if (!mountedRef.current) return;
				setTaskId(initialTaskId);
				setTask(status);
				setError(status.error ?? null);
				if (status.status === 'ACCEPTED' || status.status === 'RUNNING') {
					void poll(initialTaskId);
				} else {
					const taskEvents = await fetchTaskEvents(initialTaskId).catch(() => []);
					if (mountedRef.current) setEvents(taskEvents);
				}
			} catch (loadError) {
				if (mountedRef.current) {
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
		try {
			const status = await submitTask(question, mode);
			if (!mountedRef.current) return;
			setTaskId(status.taskId);
			setTask(status);
			void poll(status.taskId);
		} catch (submitError) {
			setError(errorMessage(submitError, '提交任务失败'));
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

	const reset = () => {
		stopPolling();
		setTaskId(null);
		setTask(null);
		setEvents([]);
		setError(null);
	};

	const running = task?.status === 'ACCEPTED' || task?.status === 'RUNNING';

	return { taskId, task, events, error, submitting, running, start, attach, reset, stopPolling };
}
