export type DatasourceTestState = 'success' | 'failed' | 'unknown';

/**
 * The backend persists "failed" (AgentDatasourceService writes test_status = failed).
 * Older clients compared against "fail", so both spellings must map to a failure state.
 */
export function normalizeDatasourceTestStatus(status?: string | null): DatasourceTestState {
	if (status === 'success') return 'success';
	if (status === 'failed' || status === 'fail') return 'failed';
	return 'unknown';
}

export const DATASOURCE_TEST_META: Record<DatasourceTestState, { label: string; className: string }> = {
	success: { label: '连接正常', className: 'border-green-200 bg-green-50 text-green-700' },
	failed: { label: '连接失败', className: 'border-red-200 bg-red-50 text-red-700' },
	unknown: { label: '未测试', className: 'border-gray-200 bg-gray-50 text-gray-600' },
};
