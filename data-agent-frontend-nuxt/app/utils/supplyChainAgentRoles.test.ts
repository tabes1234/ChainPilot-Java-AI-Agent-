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

import { describe, expect, it } from 'vitest';
import {
	CHAINPILOT_ROLES,
	ROLE_CAPABILITY_ORDER,
	roleCssVars,
	roleForEvent,
	roleForEventType,
	roleForStage,
	type ChainPilotRoleId,
} from './supplyChainAgentRoles';

describe('supply chain agent roles', () => {
	it('defines one complete, uniquely identifiable role for each capability', () => {
		const ids = Object.keys(CHAINPILOT_ROLES) as ChainPilotRoleId[];
		expect(ids.sort()).toEqual([
			'confirmation',
			'data',
			'execution',
			'failure',
			'planning',
			'risk',
			'simulation',
			'system',
		]);
		const icons = new Set<string>();
		for (const id of ids) {
			const role = CHAINPILOT_ROLES[id];
			expect(role.id).toBe(id);
			expect(role.label.length).toBeGreaterThan(0);
			expect(role.icon).toMatch(/^mdi-/);
			expect(role.color).toMatch(/^#[0-9a-f]{6}$/i);
			expect(role.soft).toMatch(/^#[0-9a-f]{6}$/i);
			expect(role.ink).toMatch(/^#[0-9a-f]{6}$/i);
			icons.add(role.icon);
		}
		expect(icons.size).toBe(ids.length);
	});

	it('keeps capability order free of orchestration and failure pseudo roles', () => {
		expect(ROLE_CAPABILITY_ORDER).toEqual([
			'data',
			'risk',
			'simulation',
			'confirmation',
			'execution',
		]);
		for (const role of ROLE_CAPABILITY_ORDER) {
			expect(CHAINPILOT_ROLES[role]).toBeDefined();
		}
	});

	it('maps backend workflow stages and tools to the matching role', () => {
		expect(roleForStage('PLANNING')).toBe('planning');
		expect(roleForStage('QUERYING_ORDERS')).toBe('data');
		expect(roleForStage('QUERYING_INVENTORY')).toBe('data');
		expect(roleForStage('QUERYING_SUPPLIERS')).toBe('data');
		expect(roleForStage('VALIDATING')).toBe('risk');
		expect(roleForStage('RETRYING_DATA_CHECK')).toBe('risk');
		expect(roleForStage('GENERATING_REPORT')).toBe('planning');
		expect(roleForStage('VALIDATION_FAILED')).toBe('failure');
		expect(roleForStage('DECISION_INTENT')).toBe('planning');
		expect(roleForStage('VALIDATING_DECISION')).toBe('risk');
		expect(roleForStage('PROPOSAL')).toBe('confirmation');
		expect(roleForStage('findInventory')).toBe('data');
		expect(roleForStage('simulateInventoryReplenishment')).toBe('simulation');
		expect(roleForStage('compareDisposalScenarios')).toBe('simulation');
		expect(roleForStage('checkSandboxExecutionEligibility')).toBe('execution');
		expect(roleForStage('findSandboxExecution')).toBe('execution');
		expect(roleForStage('UNKNOWN_STAGE')).toBe('system');
		expect(roleForStage('')).toBe('system');
		expect(roleForStage(null)).toBe('system');
	});

	it('maps failure, confirmation, fallback and model event types by their real meaning', () => {
		expect(roleForEventType('TASK_FAILED')).toBe('failure');
		expect(roleForEventType('NODE_FAILED')).toBe('failure');
		expect(roleForEventType('TOOL_FAILED')).toBe('failure');
		expect(roleForEventType('MODEL_FAILED')).toBe('failure');
		expect(roleForEventType('PROPOSAL_FAILED')).toBe('failure');
		expect(roleForEventType('PROPOSAL_CREATED')).toBe('confirmation');
		expect(roleForEventType('PROPOSAL_CONFIRMED')).toBe('confirmation');
		expect(roleForEventType('PROPOSAL_CANCELLED')).toBe('confirmation');
		expect(roleForEventType('CLARIFICATION_REQUIRED')).toBe('confirmation');
		expect(roleForEventType('FALLBACK_USED')).toBe('risk');
		expect(roleForEventType('INTENT_RECOGNIZED')).toBe('planning');
		expect(roleForEventType('MODEL_COMPLETED')).toBe('planning');
		expect(roleForEventType('TOOL_COMPLETED')).toBe('system');
		expect(roleForEventType('')).toBe('system');
		expect(roleForEventType(null)).toBe('system');
	});

	it('prefers explicit event semantics over the underlying stage', () => {
		expect(roleForEvent({ type: 'MODEL_FAILED', stage: 'QUERYING_ORDERS' })).toBe('failure');
		expect(roleForEvent({ type: 'MODEL_COMPLETED', stage: 'QUERYING_ORDERS' })).toBe('planning');
		expect(roleForEvent({ type: 'PROPOSAL_CREATED', stage: 'DECISION_EXECUTION' })).toBe(
			'confirmation',
		);
		expect(roleForEvent({ type: 'FALLBACK_USED', stage: 'GENERATING_REPORT' })).toBe('risk');
		expect(roleForEvent({ type: 'TASK_COMPLETED', stage: 'GENERATING_REPORT' })).toBe('system');
		expect(roleForEvent({ type: 'NODE_COMPLETED', stage: 'VALIDATING' })).toBe('risk');
		expect(roleForEvent({ type: 'TOOL_STARTED', stage: 'compareSupplierDelayScenarios' })).toBe(
			'simulation',
		);
		expect(roleForEvent({ type: 'NODE_STARTED', stage: 'checkSandboxExecutionEligibility' })).toBe(
			'execution',
		);
	});

	it('exposes role variables for scoped styling without business risk colors', () => {
		const vars = roleCssVars('simulation');
		expect(vars).toEqual({
			'--cp-role-color': CHAINPILOT_ROLES.simulation.color,
			'--cp-role-soft': CHAINPILOT_ROLES.simulation.soft,
			'--cp-role-ink': CHAINPILOT_ROLES.simulation.ink,
		});
		expect(roleCssVars('confirmation')).toMatchObject({
			'--cp-role-color': '#bd7361',
			'--cp-role-ink': '#985344',
		});
		expect(roleCssVars('failure')).toMatchObject({ '--cp-role-color': '#b9575c' });
	});
});
