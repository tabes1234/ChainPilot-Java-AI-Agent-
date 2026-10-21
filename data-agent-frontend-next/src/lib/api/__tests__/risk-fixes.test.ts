import { describe, expect, it } from 'vitest';
import { buildAgentUpdatePayload } from '@/lib/api/agent';
import { normalizeDatasourceTestStatus, DATASOURCE_TEST_META } from '@/lib/datasource-status';
import { AVATAR_MAX_BYTES, validateAvatarFile } from '@/lib/upload';
import { parseUrlParam } from '@/lib/use-url-param';

describe('agent update payload keeps API key state', () => {
	it('carries the existing enabled flag through a profile edit', () => {
		const payload = buildAgentUpdatePayload(
			{ apiKeyEnabled: 1 },
			{ name: '新名称', description: '新描述', tags: 'a,b' },
		);
		expect(payload).toMatchObject({ name: '新名称', apiKeyEnabled: 1 });
	});

	it('keeps a disabled flag instead of inventing one', () => {
		expect(buildAgentUpdatePayload({ apiKeyEnabled: 0 }, { name: 'x' }).apiKeyEnabled).toBe(0);
	});

	it('falls back to 0 only when no state is available at all', () => {
		expect(buildAgentUpdatePayload({ apiKeyEnabled: undefined }, { name: 'x' }).apiKeyEnabled).toBe(0);
	});

	it('does not include plaintext API keys in the payload', () => {
		const payload = buildAgentUpdatePayload({ apiKeyEnabled: 1 }, { name: 'x', apiKey: 'sk-should-not-be-sent' });
		expect(payload.apiKey).toBeUndefined();
	});
});

describe('datasource test status mapping', () => {
	it('maps the backend value "failed" to a failure state', () => {
		expect(normalizeDatasourceTestStatus('failed')).toBe('failed');
		expect(DATASOURCE_TEST_META.failed.label).toBe('连接失败');
	});

	it('also tolerates the legacy "fail" value', () => {
		expect(normalizeDatasourceTestStatus('fail')).toBe('failed');
	});

	it('maps success and unknown values correctly', () => {
		expect(normalizeDatasourceTestStatus('success')).toBe('success');
		expect(normalizeDatasourceTestStatus(undefined)).toBe('unknown');
		expect(normalizeDatasourceTestStatus('')).toBe('unknown');
		expect(DATASOURCE_TEST_META.unknown.label).toBe('未测试');
	});
});

describe('avatar validation', () => {
	it('accepts image files up to the 2MB backend limit', () => {
		expect(validateAvatarFile({ type: 'image/png', size: 1024 })).toEqual({ ok: true });
		expect(validateAvatarFile({ type: 'image/jpeg', size: AVATAR_MAX_BYTES })).toEqual({ ok: true });
	});

	it('rejects files over 2MB before hitting the backend', () => {
		const result = validateAvatarFile({ type: 'image/png', size: AVATAR_MAX_BYTES + 1 });
		expect(result.ok).toBe(false);
		expect(result.ok ? '' : result.message).toContain('2MB');
	});

	it('rejects non-image files, matching the backend content-type check', () => {
		expect(validateAvatarFile({ type: 'application/pdf', size: 1024 }).ok).toBe(false);
		expect(validateAvatarFile({ type: '', size: 1024 }).ok).toBe(false);
	});
});

describe('URL parameter parsing', () => {
	it('reads encoded deep-link parameters used by task and agent routes', () => {
		const search = '?agentId=3&taskId=3810ef98&planId=plan-1&q=%E8%AE%A2%E5%8D%95';
		expect(parseUrlParam(search, 'agentId')).toBe('3');
		expect(parseUrlParam(search, 'taskId')).toBe('3810ef98');
		expect(parseUrlParam(search, 'planId')).toBe('plan-1');
		expect(parseUrlParam(search, 'q')).toBe('订单');
	});

	it('returns null for missing or empty parameters', () => {
		expect(parseUrlParam('', 'agentId')).toBeNull();
		expect(parseUrlParam('?agentId=', 'agentId')).toBe('');
	});
});
