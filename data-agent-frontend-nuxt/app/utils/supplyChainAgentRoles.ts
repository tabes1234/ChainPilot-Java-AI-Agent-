/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import type { CSSProperties } from 'vue';
import type { SupplyChainTaskEvent } from '../services/supplyChainEvents';

export type ChainPilotRoleId =
	| 'planning'
	| 'data'
	| 'risk'
	| 'simulation'
	| 'confirmation'
	| 'execution'
	| 'system'
	| 'failure';

export type ChainPilotRole = {
	id: ChainPilotRoleId;
	label: string;
	icon: string;
	color: string;
	soft: string;
	ink: string;
};

export const CHAINPILOT_ROLES: Record<ChainPilotRoleId, ChainPilotRole> = {
	planning: {
		id: 'planning',
		label: '规划与解释',
		icon: 'mdi-compass-outline',
		color: '#4b7fc8',
		soft: '#eaf2fd',
		ink: '#315b92',
	},
	data: {
		id: 'data',
		label: '业务数据',
		icon: 'mdi-database-outline',
		color: '#3e9487',
		soft: '#e8f5f1',
		ink: '#2c7066',
	},
	risk: {
		id: 'risk',
		label: '风险分析',
		icon: 'mdi-shield-alert-outline',
		color: '#b38340',
		soft: '#fbf3e4',
		ink: '#8b6328',
	},
	simulation: {
		id: 'simulation',
		label: '模拟与对比',
		icon: 'mdi-chart-timeline-variant',
		color: '#796bbb',
		soft: '#f1eefb',
		ink: '#5d5199',
	},
	confirmation: {
		id: 'confirmation',
		label: '人工确认',
		icon: 'mdi-account-check-outline',
		color: '#bd7361',
		soft: '#fbefeb',
		ink: '#985344',
	},
	execution: {
		id: 'execution',
		label: '沙箱执行与审计',
		icon: 'mdi-play-circle-outline',
		color: '#428766',
		soft: '#eaf4ee',
		ink: '#2f6b4e',
	},
	system: {
		id: 'system',
		label: '任务编排',
		icon: 'mdi-transit-connection-variant',
		color: '#6f8197',
		soft: '#f1f5f9',
		ink: '#526475',
	},
	failure: {
		id: 'failure',
		label: '执行异常',
		icon: 'mdi-alert-circle-outline',
		color: '#b9575c',
		soft: '#fbedee',
		ink: '#94484c',
	},
};

export const ROLE_CAPABILITY_ORDER: ChainPilotRoleId[] = [
	'data',
	'risk',
	'simulation',
	'confirmation',
	'execution',
];

const STAGE_ROLES: Record<string, ChainPilotRoleId> = {
	QUEUE: 'system',
	WORKFLOW: 'system',
	PLANNING: 'planning',
	QUERYING_ORDERS: 'data',
	QUERYING_INVENTORY: 'data',
	QUERYING_SUPPLIERS: 'data',
	VALIDATING: 'risk',
	RETRYING_DATA_CHECK: 'risk',
	GENERATING_REPORT: 'planning',
	VALIDATION_FAILED: 'failure',
	DECISION_INTENT: 'planning',
	DECISION_EXECUTION: 'planning',
	VALIDATING_DECISION: 'risk',
	DECISION_SUMMARY: 'planning',
	PROPOSAL: 'confirmation',
	findAtRiskOrders: 'risk',
	findInventory: 'data',
	findSuppliers: 'data',
	findDisposalRecommendations: 'risk',
	simulateInventoryReplenishment: 'simulation',
	simulateSupplierDelay: 'simulation',
	compareDisposalScenarios: 'simulation',
	compareSupplierDelayScenarios: 'simulation',
	findScenarioComparison: 'simulation',
	findDisposalPlan: 'planning',
	checkSandboxExecutionEligibility: 'execution',
	findSandboxExecution: 'execution',
	findSandboxExecutions: 'execution',
};

const FAILURE_TYPES = new Set([
	'TASK_FAILED',
	'NODE_FAILED',
	'TOOL_FAILED',
	'MODEL_FAILED',
	'PROPOSAL_FAILED',
]);

const CONFIRMATION_TYPES = new Set([
	'CLARIFICATION_REQUIRED',
	'PROPOSAL_CREATED',
	'PROPOSAL_CONFIRMED',
	'PROPOSAL_CANCELLED',
]);

export function roleForStage(stage: string | null | undefined): ChainPilotRoleId {
	return (stage && STAGE_ROLES[stage]) || 'system';
}

export function roleForEventType(type: string | null | undefined): ChainPilotRoleId {
	if (!type) {
		return 'system';
	}
	if (FAILURE_TYPES.has(type)) {
		return 'failure';
	}
	if (CONFIRMATION_TYPES.has(type)) {
		return 'confirmation';
	}
	if (type === 'FALLBACK_USED') {
		return 'risk';
	}
	if (type === 'INTENT_RECOGNIZED' || type === 'MODEL_COMPLETED') {
		return 'planning';
	}
	return 'system';
}

export function roleForEvent(event: Pick<SupplyChainTaskEvent, 'type' | 'stage'>): ChainPilotRoleId {
	const byType = roleForEventType(event.type);
	if (byType !== 'system' || event.type === 'TASK_QUEUED' || event.type === 'TASK_STARTED' || event.type === 'TASK_COMPLETED') {
		return byType;
	}
	return roleForStage(event.stage);
}

export function roleCssVars(roleId: ChainPilotRoleId): CSSProperties {
	const role = CHAINPILOT_ROLES[roleId] || CHAINPILOT_ROLES.system;
	return {
		'--cp-role-color': role.color,
		'--cp-role-soft': role.soft,
		'--cp-role-ink': role.ink,
	} as CSSProperties;
}
