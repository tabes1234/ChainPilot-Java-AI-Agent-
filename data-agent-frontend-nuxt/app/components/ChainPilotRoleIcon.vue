<template>
	<span
		class="role-icon"
		:class="`role-icon--${role}`"
		:style="roleCssVars(role)"
		:title="label || definition.label"
	>
		<v-icon :icon="definition.icon" :size="size" aria-hidden="true" />
		<span v-if="label" class="role-icon-label">{{ label }}</span>
	</span>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import {
	CHAINPILOT_ROLES,
	roleCssVars,
	type ChainPilotRoleId,
} from '~/utils/supplyChainAgentRoles';

const props = withDefaults(
	defineProps<{
		role: ChainPilotRoleId;
		size?: number;
		label?: string;
	}>(),
	{
		size: 16,
		label: '',
	},
);

const definition = computed(() => CHAINPILOT_ROLES[props.role] || CHAINPILOT_ROLES.system);
</script>

<style scoped>
.role-icon {
	display: inline-grid;
	width: 28px;
	height: 28px;
	flex: 0 0 28px;
	place-items: center;
	color: var(--cp-role-color);
	background: var(--cp-role-soft);
	border: 1px solid color-mix(in srgb, var(--cp-role-color) 16%, transparent);
	border-radius: 9px;
}

.role-icon :deep(.v-icon) {
	color: inherit;
}

.role-icon:has(.role-icon-label) {
	display: inline-flex;
	width: auto;
	padding-right: 9px;
	gap: 6px;
}

.role-icon-label {
	color: var(--cp-role-ink);
	font-size: 12px;
	font-weight: 650;
	line-height: 1;
	white-space: nowrap;
}
</style>
