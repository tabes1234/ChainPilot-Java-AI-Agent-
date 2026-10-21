import { readFileSync } from 'node:fs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fetchModelReadiness, readinessStateLabel } from '@/lib/api/modelConfig';

afterEach(() => vi.unstubAllGlobals());

const modelPage = readFileSync('src/app/system/model-config/page.tsx', 'utf8');
const evaluationPage = readFileSync('src/app/agent-evaluation/page.tsx', 'utf8');
const confirmDialog = readFileSync('src/app/agent-evaluation/RunConfirmDialog.tsx', 'utf8');

describe('model readiness API', () => {
	it('reads configured, verified and agent-gate states without triggering a model call', async () => {
		const requests: string[] = [];
		vi.stubGlobal('fetch', vi.fn(async (input: string) => {
			requests.push(String(input));
			return new Response(JSON.stringify({
				success: true,
				message: 'ok',
				data: {
					chatModelReady: true,
					embeddingModelReady: false,
					ready: false,
					chatConnectionVerified: true,
					embeddingConnectionVerified: false,
					chatState: 'VERIFIED',
					embeddingState: 'NOT_CONFIGURED',
					chatTestedAt: '2026-10-21T09:00:00Z',
					embeddingRequiredForAgentEvaluation: false,
					agentEvaluationReady: true,
					verificationScope: 'CURRENT_BACKEND_PROCESS',
				},
			}), { status: 200, headers: { 'content-type': 'application/json' } });
		}));

		const readiness = await fetchModelReadiness();

		expect(requests).toHaveLength(1);
		expect(requests[0]).toContain('/api/model-config/check-ready');
		expect(readiness.chatConnectionVerified).toBe(true);
		expect(readiness.embeddingRequiredForAgentEvaluation).toBe(false);
		expect(readiness.agentEvaluationReady).toBe(true);
		expect(readiness.verificationScope).toBe('CURRENT_BACKEND_PROCESS');
	});

	it('labels all four readiness states explicitly', () => {
		expect(readinessStateLabel('NOT_CONFIGURED')).toBe('未配置');
		expect(readinessStateLabel('CONFIGURED_UNVERIFIED')).toBe('未测试');
		expect(readinessStateLabel('TEST_FAILED')).toBe('测试失败');
		expect(readinessStateLabel('VERIFIED')).toBe('连接已验证');
	});
});

describe('model readiness UI contract', () => {
	it('distinguishes configuration from a manual connection test and optional embedding', () => {
		expect(modelPage).toContain('真实 Agent 评测前置条件');
		expect(modelPage).toContain('连接测试');
		expect(modelPage).toContain('打开页面或刷新只读取状态，不会调用模型');
		expect(modelPage).toContain('不会按定时器自动请求模型');
		expect(modelPage).toContain('不要求 EMBEDDING');
		expect(modelPage).toContain('本次结果与时间会记录在当前后端进程内');
	});

	it('requires readiness before starting AGENT and explains cost and verification limits', () => {
		expect(evaluationPage).toContain('AgentReadinessPanel');
		expect(evaluationPage).toContain('真实 AGENT 前置条件');
		expect(confirmDialog).toContain('费用与调用范围');
		expect(confirmDialog).toContain('无法给出可信金额预估');
		expect(confirmDialog).toContain('连接测试成功不等于工作流成功');
		expect(confirmDialog).toContain('前往模型配置并手动测试');
		expect(confirmDialog).toContain('chatRequiredBySelection');
		expect(confirmDialog).toContain('!prerequisitesReady');
	});
});
