import { describe, expect, it } from 'vitest';
import type { EvaluationCaseDefinition } from '@/lib/api/evaluation';
import { largestOfflineCaseIds, offlineCaseGroupsByVersion } from '@/lib/evaluation-case-groups';

const item = (id: string, mode: 'OFFLINE' | 'AGENT', version: string): EvaluationCaseDefinition => ({
	id,
	name: id,
	scenario: 'unit',
	category: 'risk',
	kind: 'RISK',
	mode,
	question: 'q',
	expectedBehavior: 'b',
	version,
	enabled: true,
	basis: 'demo',
	assertions: ['全部断言通过'],
});

const CASES: EvaluationCaseDefinition[] = [
	item('ORDER-1', 'OFFLINE', '1.0.0'),
	item('ORDER-2', 'OFFLINE', '1.0.0'),
	item('SUPPLIER-1', 'OFFLINE', '1.0.0'),
	item('FB-1', 'OFFLINE', 'feedback-1.0.0'),
	item('WF-ANALYSIS-HIGH-RISK', 'AGENT', 'workflow-1.0.0'),
];

describe('offline case grouping for one-click runs', () => {
	it('never mixes case-set versions, because the backend rejects a mixed run', () => {
		const groups = offlineCaseGroupsByVersion(CASES);
		expect(groups.map((group) => `${group.version}:${group.ids.length}`)).toEqual(['1.0.0:3', 'feedback-1.0.0:1']);
		expect(groups.every((group) => new Set(group.ids.map((id) => id.startsWith('FB-'))).size === 1)).toBe(true);
	});

	it('ignores AGENT cases when building offline selections', () => {
		expect(offlineCaseGroupsByVersion(CASES).flatMap((group) => group.ids)).not.toContain('WF-ANALYSIS-HIGH-RISK');
	});

	it('selects the largest same-version group so the run can start', () => {
		expect(largestOfflineCaseIds(CASES)).toEqual(['ORDER-1', 'ORDER-2', 'SUPPLIER-1']);
	});

	it('falls back to a single feedback case when it is the only offline case', () => {
		expect(largestOfflineCaseIds([CASES[3], CASES[4]])).toEqual(['FB-1']);
	});

	it('returns an empty selection when no offline case exists', () => {
		expect(largestOfflineCaseIds([CASES[4]])).toEqual([]);
	});
});
