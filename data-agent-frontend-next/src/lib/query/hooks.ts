/**
 * TanStack Query hooks for ChainPilot API services.
 */

import { useQuery, useMutation, useQueryClient, keepPreviousData } from '@tanstack/react-query';
import * as agentKnowledgeApi from '@/lib/api/agentKnowledge';
import * as semanticModelApi from '@/lib/api/semanticModel';
import * as businessKnowledgeApi from '@/lib/api/businessKnowledge';
import * as agentApi from '@/lib/api/agent';
import * as evaluationApi from '@/lib/api/evaluation';

// ── Agent Knowledge Queries ────────────────────────────────────────────────

const knowledgePageKey = (agentId: number) => ['agentKnowledge', 'page', agentId] as const;

export function useKnowledgePage(
	agentId: number,
	query: agentKnowledgeApi.KnowledgePageQuery,
	options?: { enabled?: boolean },
) {
	return useQuery({
		queryKey: [...knowledgePageKey(agentId), query],
		queryFn: () => agentKnowledgeApi.queryKnowledgePage(agentId, query),
		enabled: options?.enabled !== false && agentId > 0,
		placeholderData: keepPreviousData,
	});
}

export function useCreateAgentKnowledge() {
	const queryClient = useQueryClient();
	return useMutation({
		mutationFn: (input: agentKnowledgeApi.KnowledgeCreateInput) =>
			agentKnowledgeApi.createAgentKnowledge(input),
		onSuccess: (knowledge) => {
			if (knowledge?.agentId) {
				queryClient.invalidateQueries({ queryKey: knowledgePageKey(knowledge.agentId) });
			}
			queryClient.invalidateQueries({ queryKey: ['agentKnowledge', 'detail'] });
		},
	});
}

export function useUpdateAgentKnowledge() {
	const queryClient = useQueryClient();
	return useMutation({
		mutationFn: ({
			id,
			data,
		}: {
			agentId: number;
			id: number;
			data: { title?: string; content?: string };
		}) => agentKnowledgeApi.updateAgentKnowledge(id, data),
		onSuccess: (_knowledge, { agentId }) => {
			queryClient.invalidateQueries({ queryKey: knowledgePageKey(agentId) });
		},
	});
}

export function useUpdateKnowledgeRecall() {
	const queryClient = useQueryClient();
	return useMutation({
		mutationFn: ({ id, isRecall }: { agentId: number; id: number; isRecall: boolean }) =>
			agentKnowledgeApi.updateRecallStatus(id, isRecall),
		onSuccess: (_knowledge, { agentId }) => {
			queryClient.invalidateQueries({ queryKey: knowledgePageKey(agentId) });
		},
	});
}

export function useDeleteAgentKnowledge() {
	const queryClient = useQueryClient();
	return useMutation({
		mutationFn: ({ id }: { agentId: number; id: number }) =>
			agentKnowledgeApi.deleteAgentKnowledge(id),
		onSuccess: (_data, { agentId }) => {
			queryClient.invalidateQueries({ queryKey: knowledgePageKey(agentId) });
		},
	});
}

export function useRetryKnowledgeEmbedding() {
	const queryClient = useQueryClient();
	return useMutation({
		mutationFn: ({ id }: { agentId: number; id: number }) =>
			agentKnowledgeApi.retryEmbedding(id),
		onSuccess: (_message, { agentId }) => {
			queryClient.invalidateQueries({ queryKey: knowledgePageKey(agentId) });
		},
	});
}

// ── Semantic Model Queries ─────────────────────────────────────────────────

export function useSemanticModels(agentId?: number, keyword?: string, options?: { enabled?: boolean }) {
	return useQuery({
		queryKey: ['semanticModels', agentId ?? null, keyword ?? ''],
		queryFn: () => semanticModelApi.fetchSemanticModels(agentId, keyword),
		// A keyword search is global on the backend, so it is allowed without a selected agent.
		enabled: options?.enabled !== false && (!!keyword || (!!agentId && agentId > 0)),
	});
}

export function useCreateSemanticModel() {
	const queryClient = useQueryClient();
	return useMutation({
		mutationFn: semanticModelApi.createSemanticModel,
		onSuccess: () => {
			queryClient.invalidateQueries({ queryKey: ['semanticModels'] });
		},
	});
}

export function useUpdateSemanticModel() {
	const queryClient = useQueryClient();
	return useMutation({
		mutationFn: ({ id, model }: { id: number; model: Partial<semanticModelApi.SemanticModel> }) =>
			semanticModelApi.updateSemanticModel(id, model),
		onSuccess: () => {
			queryClient.invalidateQueries({ queryKey: ['semanticModels'] });
		},
	});
}

export function useDeleteSemanticModel() {
	const queryClient = useQueryClient();
	return useMutation({
		mutationFn: semanticModelApi.deleteSemanticModel,
		onSuccess: () => {
			queryClient.invalidateQueries({ queryKey: ['semanticModels'] });
		},
	});
}

export function useBatchDeleteSemanticModels() {
	const queryClient = useQueryClient();
	return useMutation({
		mutationFn: semanticModelApi.batchDeleteSemanticModels,
		onSuccess: () => {
			queryClient.invalidateQueries({ queryKey: ['semanticModels'] });
		},
	});
}

export function useToggleSemanticModels() {
	const queryClient = useQueryClient();
	return useMutation({
		mutationFn: ({ ids, enabled }: { ids: number[]; enabled: boolean }) =>
			enabled ? semanticModelApi.enableSemanticModels(ids) : semanticModelApi.disableSemanticModels(ids),
		onSuccess: () => {
			queryClient.invalidateQueries({ queryKey: ['semanticModels'] });
		},
	});
}

export function useImportSemanticModelExcel() {
	const queryClient = useQueryClient();
	return useMutation({
		mutationFn: ({ agentId, file }: { agentId: number; file: File }) =>
			semanticModelApi.importSemanticModelExcel(agentId, file),
		onSuccess: () => {
			queryClient.invalidateQueries({ queryKey: ['semanticModels'] });
		},
	});
}

// ── Business Knowledge Queries ─────────────────────────────────────────────

const businessKey = (agentId: number) => ['businessKnowledge', agentId] as const;

export function useBusinessKnowledge(agentId: number, keyword?: string, options?: { enabled?: boolean }) {
	return useQuery({
		queryKey: [...businessKey(agentId), keyword ?? ''],
		queryFn: () => businessKnowledgeApi.fetchBusinessKnowledge(agentId, keyword),
		enabled: options?.enabled !== false && agentId > 0,
		placeholderData: keepPreviousData,
	});
}

export function useCreateBusinessKnowledge() {
	const queryClient = useQueryClient();
	return useMutation({
		mutationFn: businessKnowledgeApi.createBusinessKnowledge,
		onSuccess: (knowledge) => {
			if (knowledge?.agentId) queryClient.invalidateQueries({ queryKey: businessKey(knowledge.agentId) });
		},
	});
}

export function useUpdateBusinessKnowledge() {
	const queryClient = useQueryClient();
	return useMutation({
		mutationFn: ({
			id,
			data,
		}: {
			agentId: number;
			id: number;
			data: { agentId: number; businessTerm: string; description: string; synonyms?: string };
		}) => businessKnowledgeApi.updateBusinessKnowledge(id, data),
		onSuccess: (_knowledge, { agentId }) => {
			queryClient.invalidateQueries({ queryKey: businessKey(agentId) });
		},
	});
}

export function useDeleteBusinessKnowledge() {
	const queryClient = useQueryClient();
	return useMutation({
		mutationFn: ({ id }: { agentId: number; id: number }) => businessKnowledgeApi.deleteBusinessKnowledge(id),
		onSuccess: (_data, { agentId }) => {
			queryClient.invalidateQueries({ queryKey: businessKey(agentId) });
		},
	});
}

export function useToggleBusinessKnowledgeRecall() {
	const queryClient = useQueryClient();
	return useMutation({
		mutationFn: ({ id, isRecall }: { agentId: number; id: number; isRecall: boolean }) =>
			businessKnowledgeApi.setBusinessKnowledgeRecall(id, isRecall),
		onSuccess: (_data, { agentId }) => {
			queryClient.invalidateQueries({ queryKey: businessKey(agentId) });
		},
	});
}

export function useRefreshBusinessKnowledgeVectors() {
	const queryClient = useQueryClient();
	return useMutation({
		mutationFn: (agentId: number) => businessKnowledgeApi.refreshBusinessKnowledgeVectorStore(agentId),
		onSuccess: (_data, agentId) => {
			queryClient.invalidateQueries({ queryKey: businessKey(agentId) });
		},
	});
}

export function useRetryBusinessKnowledgeEmbedding() {
	const queryClient = useQueryClient();
	return useMutation({
		mutationFn: ({ id }: { agentId: number; id: number }) =>
			businessKnowledgeApi.retryBusinessKnowledgeEmbedding(id),
		onSuccess: (_data, { agentId }) => {
			queryClient.invalidateQueries({ queryKey: businessKey(agentId) });
		},
	});
}

// ── Agent Management Queries ───────────────────────────────────────────────

export function useAgents(status?: string, keyword?: string) {
	return useQuery({
		queryKey: ['agents', status, keyword],
		queryFn: () => agentApi.fetchAgents(status, keyword),
	});
}

export function useAgentById(id: number) {
	return useQuery({
		queryKey: ['agent', id],
		queryFn: () => agentApi.fetchAgentById(id),
		enabled: !!id,
	});
}

export function useCreateAgent() {
	const queryClient = useQueryClient();
	return useMutation({
		mutationFn: agentApi.createAgent,
		onSuccess: () => {
			queryClient.invalidateQueries({ queryKey: ['agents'] });
		},
	});
}

export function useUpdateAgent() {
	const queryClient = useQueryClient();
	return useMutation({
		mutationFn: ({ id, agent }: { id: number; agent: Partial<agentApi.Agent> }) =>
			agentApi.updateAgent(id, agent),
		onSuccess: (_, { id }) => {
			queryClient.invalidateQueries({ queryKey: ['agents'] });
			queryClient.invalidateQueries({ queryKey: ['agent', id] });
		},
	});
}

export function useDeleteAgent() {
	const queryClient = useQueryClient();
	return useMutation({
		mutationFn: agentApi.deleteAgent,
		onSuccess: () => {
			queryClient.invalidateQueries({ queryKey: ['agents'] });
		},
	});
}

export function usePublishAgent() {
	const queryClient = useQueryClient();
	return useMutation({
		mutationFn: agentApi.publishAgent,
		onSuccess: (_, id) => {
			queryClient.invalidateQueries({ queryKey: ['agents'] });
			queryClient.invalidateQueries({ queryKey: ['agent', id] });
		},
	});
}

export function useOfflineAgent() {
	const queryClient = useQueryClient();
	return useMutation({
		mutationFn: agentApi.offlineAgent,
		onSuccess: (_, id) => {
			queryClient.invalidateQueries({ queryKey: ['agents'] });
			queryClient.invalidateQueries({ queryKey: ['agent', id] });
		},
	});
}


// ── Agent Evaluation Queries ───────────────────────────────────────────────

export function useEvaluationCases() {
	return useQuery({
		queryKey: ['evaluation', 'cases'],
		queryFn: evaluationApi.fetchEvaluationCases,
	});
}

export function useEvaluationCapabilities() {
	return useQuery({
		queryKey: ['evaluation', 'capabilities'],
		queryFn: evaluationApi.fetchEvaluationCapabilities,
	});
}

export function useEvaluationRuns(limit = 20) {
	return useQuery({
		queryKey: ['evaluation', 'runs', limit],
		queryFn: () => evaluationApi.fetchEvaluationRuns(limit),
		refetchInterval: (query) => {
			const data = query.state.data as evaluationApi.EvaluationRun[] | undefined;
			return data?.some((run) => run.status === 'RUNNING') ? 3000 : false;
		},
	});
}

export function useEvaluationCaseHistory(caseId: string | null, limit = 20) {
	return useQuery({
		queryKey: ['evaluation', 'caseHistory', caseId, limit],
		queryFn: () => evaluationApi.fetchEvaluationCaseHistory(caseId!, limit),
		enabled: caseId !== null,
	});
}

export function useEvaluationRun(runId: string | null) {
	return useQuery({
		queryKey: ['evaluation', 'run', runId],
		queryFn: () => evaluationApi.fetchEvaluationRun(runId!),
		enabled: runId !== null,
		retry: false,
		refetchInterval: (query) => {
			const data = query.state.data as { status?: string } | undefined;
			return data?.status === 'RUNNING' ? 2000 : false;
		},
	});
}

export function useEvaluationMetrics(limit = 20) {
	return useQuery({
		queryKey: ['evaluation', 'metrics', limit],
		queryFn: () => evaluationApi.fetchEvaluationMetrics(limit),
	});
}

export function useEvaluationComparison(baseRunId: string | null, targetRunId: string | null) {
	return useQuery({
		queryKey: ['evaluation', 'comparison', baseRunId, targetRunId],
		queryFn: () => evaluationApi.fetchEvaluationComparison(baseRunId!, targetRunId!),
		enabled: baseRunId !== null && targetRunId !== null && baseRunId !== targetRunId,
	});
}

export function useStartEvaluationRun() {
	const queryClient = useQueryClient();
	return useMutation({
		mutationFn: ({
			caseIds,
			mode,
			confirmAgentRun = false,
		}: {
			caseIds: string[];
			mode: evaluationApi.EvaluationMode;
			confirmAgentRun?: boolean;
		}) => evaluationApi.startEvaluationRun(caseIds, mode, confirmAgentRun),
		onSuccess: () => {
			queryClient.invalidateQueries({ queryKey: ['evaluation', 'runs'] });
			queryClient.invalidateQueries({ queryKey: ['evaluation', 'metrics'] });
			queryClient.invalidateQueries({ queryKey: ['evaluation', 'capabilities'] });
			queryClient.invalidateQueries({ queryKey: ['evaluation', 'caseHistory'] });
		},
	});
}
