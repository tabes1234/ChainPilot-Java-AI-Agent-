import type { EvaluationCaseDefinition } from './api/evaluation';

/**
 * One evaluation run may only contain cases of a single case-set version; the backend rejects a mixed
 * selection with "一次运行只能包含同一案例版本的案例". Feedback-approved cases carry their own version, so
 * a shortcut that grabs every offline case is guaranteed to fail as soon as one exists.
 */
export function offlineCaseGroupsByVersion(cases: EvaluationCaseDefinition[]): { version: string; ids: string[] }[] {
	const groups = new Map<string, string[]>();
	cases
		.filter((item) => item.mode === 'OFFLINE')
		.forEach((item) => {
			const version = item.version ?? '';
			groups.set(version, [...(groups.get(version) ?? []), item.id]);
		});
	return [...groups.entries()]
		.map(([version, ids]) => ({ version, ids }))
		.sort((left, right) => right.ids.length - left.ids.length || left.version.localeCompare(right.version));
}

/** The largest same-version offline group, i.e. the selection a one-click run can actually execute. */
export function largestOfflineCaseIds(cases: EvaluationCaseDefinition[]): string[] {
	return offlineCaseGroupsByVersion(cases)[0]?.ids ?? [];
}
