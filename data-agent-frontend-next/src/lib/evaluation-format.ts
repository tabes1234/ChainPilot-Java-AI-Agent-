import type {
	EvaluationCaseDefinition,
	EvaluationCaseResult,
	EvaluationCaseStatus,
	EvaluationMetrics,
	EvaluationMode,
	EvaluationRunStatus,
} from '@/lib/api/evaluation';

export const EVALUATION_CATEGORY_LABELS: Record<string, string> = {
	risk: '订单风险',
	inventory: '库存',
	supplier: '供应商',
	rag: 'RAG 检索',
	workflow: '真实工作流',
};

export function categoryLabel(category?: string): string {
	return (category && EVALUATION_CATEGORY_LABELS[category]) || category || '未分类';
}

export function modeLabel(mode?: EvaluationMode): string {
	return mode === 'AGENT' ? '真实工作流' : '离线确定性';
}

export function runStatusLabel(status?: EvaluationRunStatus): string {
	switch (status) {
		case 'RUNNING':
			return '运行中';
		case 'COMPLETED':
			return '已完成';
		case 'FAILED':
			return '失败';
		default:
			return status ?? '未知';
	}
}

export function taskModeLabel(taskMode?: string | null): string {
	return taskMode === 'DECISION' ? '决策任务' : taskMode === 'ANALYSIS' ? '分析任务' : '—';
}

export function comparisonChangeLabel(change?: string): string {
	switch (change) {
		case 'NEW_FAILURE':
			return '新增失败';
		case 'FIXED_FAILURE':
			return '已修复';
		case 'PERSISTENT_FAILURE':
			return '持续失败';
		case 'UNCHANGED_PASS':
			return '保持通过';
		case 'UNCHANGED_UNDETERMINED':
			return '仍无法判定';
		case 'INCOMPARABLE':
			return '不可比较';
		case 'OTHER':
			return '状态变化';
		default:
			return change ?? '—';
	}
}

export function comparisonChangeTone(change?: string): string {
	if (change === 'FIXED_FAILURE' || change === 'UNCHANGED_PASS') return 'border-green-200 bg-green-50 text-green-700';
	if (change === 'NEW_FAILURE' || change === 'PERSISTENT_FAILURE') return 'border-red-200 bg-red-50 text-red-700';
	if (change === 'UNCHANGED_UNDETERMINED') return 'border-amber-200 bg-amber-50 text-amber-700';
	return 'border-slate-200 bg-slate-50 text-slate-600';
}

export function assertionChangeLabel(change?: string): string {
	if (change === 'IMPROVED') return '已通过';
	if (change === 'REGRESSED') return '回归失败';
	if (change === 'VALUE_CHANGED') return '值变化';
	return change ?? '—';
}

export function formatSignedNumber(value?: number | null): string {
	if (value === undefined || value === null || Number.isNaN(value)) return '—';
	return value > 0 ? `+${value}` : String(value);
}

export function formatSignedPercent(value?: number | null): string {
	if (value === undefined || value === null || Number.isNaN(value)) return '—';
	const points = value * 100;
	return `${points > 0 ? '+' : ''}${points.toFixed(1)} pp`;
}

export function formatSignedDuration(value?: number | null): string {
	if (value === undefined || value === null || Number.isNaN(value)) return '—';
	if (value === 0) return '0 ms';
	return `${value > 0 ? '+' : '-'}${formatDuration(Math.abs(value))}`;
}

export function runFailureLabel(run: { status?: string; failureReason?: string | null }): string | null {
	if (run.failureReason) return run.failureReason;
	if (run.status === 'FAILED') return '运行失败，未生成完整结果';
	return null;
}

export function caseStatusLabel(status?: EvaluationCaseStatus): string {
	switch (status) {
		case 'PASSED':
			return '通过';
		case 'FAILED':
			return '失败';
		case 'UNDETERMINED':
			return '无法判定';
		case 'RUNNING':
			return '运行中';
		default:
			return status ?? '未知';
	}
}

export function formatPercent(rate?: number | null): string {
	if (rate === undefined || rate === null || Number.isNaN(rate)) return '—';
	return `${(rate * 100).toFixed(1)}%`;
}

export function formatDuration(ms?: number | null): string {
	if (ms === undefined || ms === null || Number.isNaN(ms)) return '—';
	if (ms < 1000) return `${Math.round(ms)} ms`;
	return `${(ms / 1000).toFixed(2)} s`;
}

export function p95Label(metrics?: Pick<EvaluationMetrics, 'p95DurationMs' | 'p95SampleSize'> | null): string {
	if (!metrics) return '—';
	if (metrics.p95DurationMs === null || metrics.p95DurationMs === undefined) {
		return `样本不足（n=${metrics.p95SampleSize}，至少 5 个）`;
	}
	return `${formatDuration(metrics.p95DurationMs)}（n=${metrics.p95SampleSize}）`;
}

/** A run can only contain cases of one mode; helpers keep the UI honest about that. */
export function selectionMode(
	cases: EvaluationCaseDefinition[],
	selectedIds: string[],
): EvaluationMode | null {
	const selected = cases.filter((item) => selectedIds.includes(item.id));
	if (selected.length === 0) return null;
	const first = selected[0].mode;
	return selected.every((item) => item.mode === first) ? first : null;
}

export function canAddToSelection(
	cases: EvaluationCaseDefinition[],
	selectedIds: string[],
	caseId: string,
): boolean {
	const target = cases.find((item) => item.id === caseId);
	if (!target) return false;
	const mode = selectionMode(cases, selectedIds);
	return mode === null || mode === target.mode;
}

export type TaskLink = { href: string; label: string };

/** Links a real workflow case back to the matching task view using its recorded taskMode. */
export function resolveTaskLink(result: EvaluationCaseResult): TaskLink | null {
	if (!result.taskId) return null;
	let taskMode = result.taskMode ?? 'ANALYSIS';
	if (!result.taskMode) {
		try {
			if (result.observationJson) {
				const observation = JSON.parse(result.observationJson) as { taskMode?: string };
				if (observation.taskMode) taskMode = observation.taskMode;
			}
		} catch {
			/* keep the analysis fallback */
		}
	}
	const taskId = encodeURIComponent(result.taskId);
	return taskMode === 'DECISION'
		? { href: `/supply-chain/decision?taskId=${taskId}`, label: '打开决策任务' }
		: { href: `/supply-chain?taskId=${taskId}`, label: '打开分析任务' };
}

export function runTone(status?: EvaluationRunStatus): string {
	if (status === 'COMPLETED') return 'border-green-200 bg-green-50 text-green-700';
	if (status === 'FAILED') return 'border-red-200 bg-red-50 text-red-700';
	return 'border-blue-200 bg-blue-50 text-blue-700';
}

export function caseTone(status?: EvaluationCaseStatus): string {
	if (status === 'PASSED') return 'border-green-200 bg-green-50 text-green-700';
	if (status === 'FAILED') return 'border-red-200 bg-red-50 text-red-700';
	if (status === 'RUNNING') return 'border-blue-200 bg-blue-50 text-blue-700';
	return 'border-amber-200 bg-amber-50 text-amber-700';
}
