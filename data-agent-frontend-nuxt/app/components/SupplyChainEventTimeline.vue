<template>
	<section id="execution-timeline" class="surface-panel timeline-panel mb-7" aria-label="任务执行轨迹">
		<header class="timeline-header">
			<div class="timeline-heading">
				<span class="timeline-icon" :style="roleCssVars('system')"><v-icon icon="mdi-timeline-clock-outline" size="20" /></span>
				<div class="timeline-heading-copy">
					<div class="timeline-kicker">EXECUTION TRAIL</div>
					<h2 class="timeline-title">执行轨迹</h2>
					<p class="timeline-subtitle">
						<span class="timeline-task-id">{{ taskId }}</span>
						<span class="timeline-subtitle-divider" />
						<span>{{ statusLabel }}</span>
						<span v-if="running" class="timeline-running-hint">任务执行中，可刷新查看已产生的事件</span>
					</p>
				</div>
			</div>
			<div class="timeline-actions">
				<v-btn size="small" variant="text" color="primary" prepend-icon="mdi-refresh" :loading="loading" :disabled="loading" @click="emit('retry')">刷新</v-btn>
				<v-btn icon="mdi-close" size="small" variant="text" color="primary" aria-label="关闭执行轨迹" @click="emit('close')" />
			</div>
		</header>

		<div v-if="signals.hasModelCompleted || signals.hasModelFailed || signals.hasFallback" class="timeline-signals">
			<span v-if="signals.hasModelCompleted" class="timeline-signal" :style="roleCssVars('planning')"><v-icon :icon="CHAINPILOT_ROLES.planning.icon" size="13" />模型调用完成</span>
			<span v-if="signals.hasModelFailed" class="timeline-signal" :style="roleCssVars('failure')"><v-icon :icon="CHAINPILOT_ROLES.failure.icon" size="13" />模型调用失败</span>
			<span v-if="signals.hasFallback" class="timeline-signal" :style="roleCssVars('risk')"><v-icon icon="mdi-swap-horizontal" size="13" />规则降级</span>
		</div>

		<v-progress-linear v-if="loading" indeterminate color="primary" height="2" />

		<div v-if="error" class="timeline-state timeline-state--error" role="alert">
			<v-icon icon="mdi-alert-circle-outline" size="21" />
			<div><strong>执行轨迹加载失败</strong><span>{{ error }}</span></div>
			<v-btn size="small" variant="tonal" color="primary" prepend-icon="mdi-replay" :disabled="loading" @click="emit('retry')">重试</v-btn>
		</div>
		<div v-else-if="loading && views.length === 0" class="timeline-state">
			<v-progress-circular indeterminate size="22" width="2" color="primary" />
			<div><strong>正在读取执行轨迹</strong><span>事件将按后端记录的任务内顺序展示。</span></div>
		</div>
		<div v-else-if="views.length === 0" class="timeline-state">
			<v-icon icon="mdi-timeline-clock-outline" size="22" />
			<div>
				<strong>{{ running ? '任务尚未产生可展示事件' : '该任务没有已持久化的执行事件' }}</strong>
				<span>{{ running ? '任务仍在执行或事件尚未写入，可稍后刷新；页面不会补造缺失步骤。' : '事件可能尚未写入或已被清理，页面不会补造缺失步骤。' }}</span>
			</div>
			<v-btn size="small" variant="tonal" color="primary" prepend-icon="mdi-refresh" :disabled="loading" @click="emit('retry')">刷新</v-btn>
		</div>

		<ol v-else class="timeline-list">
			<li
				v-for="(item, index) in views"
				:key="item.id"
				class="timeline-item"
				:class="[`timeline-item--${item.role}`, `timeline-item--${item.tone}`, { 'is-latest': running && index === views.length - 1 }]"
				:style="roleCssVars(item.role)"
			>
				<div class="timeline-rail" aria-hidden="true"><span class="timeline-dot"><v-icon :icon="item.roleIcon" size="13" /></span></div>
				<div class="timeline-body">
					<div class="timeline-row">
						<span class="timeline-sequence">#{{ item.sequence }}</span>
						<strong class="timeline-label">{{ item.typeLabel }}</strong>
						<span class="timeline-role">{{ item.roleLabel }}</span>
						<span class="timeline-status">{{ item.statusLabel }}</span>
						<time class="timeline-time" :datetime="item.occurredAt || undefined">{{ item.timeLabel }}</time>
					</div>
					<div class="timeline-meta">
						<span class="timeline-stage">{{ item.stageLabel }}</span>
						<span v-if="item.durationLabel">耗时 {{ item.durationLabel }}</span>
						<span v-if="item.errorType" class="timeline-error-type">{{ item.errorType }}</span>
					</div>
					<p v-if="item.summary" class="timeline-summary">{{ item.summary }}</p>
				</div>
			</li>
		</ol>

		<p class="timeline-note">仅展示后端实际持久化的事件；未返回的模型调用或规则降级不会被推测补齐。</p>
	</section>
</template>

<script setup lang="ts">
import type { SupplyChainTaskEvent } from '~/services/supplyChainEvents';
import { roleCssVars, CHAINPILOT_ROLES } from '~/utils/supplyChainAgentRoles';
import {
	buildTimelineEventViews,
	summarizeTimelineSignals,
} from '~/utils/supplyChainEventTimeline';

const props = defineProps<{
	taskId: string;
	taskStatus?: string;
	events: SupplyChainTaskEvent[];
	loading: boolean;
	error: string;
}>();

const emit = defineEmits<{ retry: []; close: [] }>();

const TASK_STATUS_LABELS: Record<string, string> = {
	ACCEPTED: '排队中',
	RUNNING: '执行中',
	COMPLETED: '已完成',
	FAILED: '失败',
};

const views = computed(() => buildTimelineEventViews(props.events));
const signals = computed(() => summarizeTimelineSignals(props.events));
const running = computed(() => props.taskStatus === 'ACCEPTED' || props.taskStatus === 'RUNNING');
const statusLabel = computed(
	() => TASK_STATUS_LABELS[props.taskStatus || ''] || props.taskStatus || '状态未知',
);
</script>

<style scoped>
.timeline-panel {
	overflow: hidden;
	padding: 20px 0 14px;
	border: 1px solid var(--cp-line-soft);
	border-radius: var(--cp-radius-lg);
	background: var(--cp-surface-glass);
	box-shadow: var(--cp-shadow-soft);
	-webkit-backdrop-filter: blur(12px) saturate(1.08);
	backdrop-filter: blur(12px) saturate(1.08);
	color: var(--cp-ink);
	animation: timeline-panel-in var(--cp-duration-slow) var(--cp-ease-out) both;
}

.timeline-header {
	display: flex;
	align-items: flex-start;
	justify-content: space-between;
	gap: 16px;
	padding: 0 23px 15px;
}

.timeline-heading {
	display: flex;
	min-width: 0;
	gap: 12px;
}

.timeline-icon {
	display: grid;
	width: 41px;
	height: 41px;
	flex: 0 0 41px;
	place-items: center;
	color: var(--cp-role-color);
	background: var(--cp-role-soft);
	border: 1px solid color-mix(in srgb, var(--cp-role-color) 15%, transparent);
	border-radius: 12px;
}

.timeline-heading-copy {
	min-width: 0;
}

.timeline-kicker {
	color: var(--cp-muted);
	font-size: 13px;
	font-weight: 750;
	letter-spacing: 0.14em;
	line-height: 1.5;
}

.timeline-title {
	margin-top: 3px;
	color: var(--cp-ink-strong);
	font-size: 17px;
	font-weight: 700;
	letter-spacing: -0.02em;
	line-height: 1.4;
}

.timeline-subtitle {
	display: flex;
	align-items: center;
	flex-wrap: wrap;
	gap: 6px;
	margin: 4px 0 0;
	color: #637183;
	font-size: 13px;
	line-height: 1.6;
}

.timeline-task-id {
	color: var(--cp-muted);
	font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
	font-size: 13px;
	overflow-wrap: anywhere;
}

.timeline-subtitle-divider {
	width: 3px;
	height: 3px;
	flex: 0 0 3px;
	border-radius: 50%;
	background: #bac9da;
}

.timeline-running-hint {
	color: var(--cp-role-planning-ink);
}

.timeline-actions {
	display: flex;
	align-items: center;
	flex-shrink: 0;
	gap: 2px;
}

.timeline-actions :deep(.v-btn) {
	font-size: 13px;
}

.timeline-signals {
	display: flex;
	flex-wrap: wrap;
	gap: 6px;
	padding: 0 23px 13px;
}

.timeline-signal {
	display: inline-flex;
	align-items: center;
	gap: 4px;
	padding: 3px 8px;
	color: var(--cp-role-ink);
	background: var(--cp-role-soft);
	border: 1px solid color-mix(in srgb, var(--cp-role-color) 16%, transparent);
	border-radius: var(--cp-radius-pill);
	font-size: 13px;
	font-weight: 650;
	line-height: 1.5;
}

.timeline-list {
	margin: 0;
	padding: 2px 23px 0;
	list-style: none;
}

.timeline-item {
	display: grid;
	grid-template-columns: 26px minmax(0, 1fr);
	gap: 10px;
	animation: timeline-item-in var(--cp-duration-base) var(--cp-ease-out) both;
}

.timeline-item.is-latest .timeline-body {
	border-radius: 8px;
	animation: timeline-latest var(--cp-duration-slow) var(--cp-ease-out) 1;
}

.timeline-rail {
	position: relative;
	display: flex;
	justify-content: center;
}

.timeline-rail::before {
	position: absolute;
	top: 0;
	bottom: -4px;
	width: 1px;
	background: linear-gradient(180deg, var(--cp-line), color-mix(in srgb, var(--cp-role-data) 22%, var(--cp-line)) 55%, var(--cp-line));
	content: '';
	transition: background var(--cp-duration-base) var(--cp-ease-out);
}

.timeline-item:last-child .timeline-rail::before {
	display: none;
}

.timeline-dot {
	position: relative;
	z-index: 1;
	display: grid;
	width: 22px;
	height: 22px;
	margin-top: 2px;
	place-items: center;
	color: var(--cp-role-color);
	background: var(--cp-role-soft);
	border: 1px solid color-mix(in srgb, var(--cp-role-color) 22%, transparent);
	border-radius: 50%;
	box-shadow: 0 0 0 3px var(--cp-surface);
	transition: color var(--cp-duration-base) var(--cp-ease-out), background-color var(--cp-duration-base) var(--cp-ease-out), border-color var(--cp-duration-base) var(--cp-ease-out);
}

.timeline-body {
	min-width: 0;
	padding: 3px 0 15px;
}

.timeline-item:not(:last-child) .timeline-body {
	border-bottom: 1px solid #edf2f8;
	margin-bottom: 12px;
	padding-bottom: 15px;
}

.timeline-row {
	display: flex;
	align-items: center;
	flex-wrap: wrap;
	gap: 5px 8px;
}

.timeline-sequence {
	color: #66717f;
	font-size: 13px;
	font-variant-numeric: tabular-nums;
}

.timeline-label {
	color: var(--cp-ink-2);
	font-size: 12px;
	font-weight: 700;
	line-height: 1.5;
}

.timeline-role {
	padding: 1px 6px;
	color: var(--cp-role-ink);
	background: var(--cp-role-soft);
	border-radius: 5px;
	font-size: 13px;
	font-weight: 650;
	line-height: 1.5;
	white-space: nowrap;
}

.timeline-status {
	padding: 1px 7px;
	border: 1px solid var(--cp-line-soft);
	border-radius: var(--cp-radius-pill);
	color: var(--cp-muted);
	background: rgba(246, 249, 253, 0.86);
	font-size: 13px;
	font-weight: 650;
	white-space: nowrap;
}

.timeline-time {
	margin-left: auto;
	color: #66717f;
	font-size: 13px;
	font-variant-numeric: tabular-nums;
	white-space: nowrap;
}

.timeline-meta {
	display: flex;
	align-items: center;
	flex-wrap: wrap;
	gap: 4px 10px;
	margin-top: 4px;
	color: #637181;
	font-size: 13px;
}

.timeline-stage {
	color: #5b728c;
}

.timeline-error-type {
	max-width: 100%;
	padding: 1px 5px;
	border-radius: 4px;
	color: #95656e;
	background: #fdf4f5;
	font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
	overflow-wrap: anywhere;
}

.timeline-summary {
	margin: 6px 0 0;
	color: #637284;
	font-size: 13px;
	line-height: 1.6;
	overflow-wrap: anywhere;
}

.timeline-item--info .timeline-status {
	color: #4a73a6;
	border-color: #e0ebf9;
	background: #eff5fd;
}

.timeline-item--success .timeline-status {
	color: #467d62;
	border-color: #dcece3;
	background: #edf6f1;
}

.timeline-item--danger .timeline-status {
	color: #9b626d;
	border-color: #f5e2e6;
	background: #fdf2f4;
}

.timeline-item--warning .timeline-status {
	color: #8b6c3d;
	border-color: #f3e9d2;
	background: #fdf8ec;
}

.timeline-state {
	display: flex;
	align-items: center;
	gap: 10px;
	margin: 3px 23px 14px;
	padding: 14px 15px;
	border: 1px solid #e7eef7;
	border-radius: 12px;
	color: #637181;
	background: rgba(249, 252, 255, 0.86);
	font-size: 13px;
	line-height: 1.6;
}

.timeline-state > div {
	display: flex;
	min-width: 0;
	flex: 1;
	flex-direction: column;
	gap: 3px;
}

.timeline-state strong {
	color: #587391;
	font-size: 13px;
	font-weight: 700;
}

.timeline-state span {
	overflow-wrap: anywhere;
}

.timeline-state--error {
	border-color: #f4e3e6;
	color: #806a6f;
	background: #fff9f9;
}

.timeline-state--error :deep(.v-icon) {
	color: #9e616a;
}

.timeline-state--error strong {
	color: #90676f;
}

.timeline-note {
	margin: 10px 23px 0;
	padding-top: 11px;
	border-top: 1px solid #edf2f8;
	color: #66717f;
	font-size: 13px;
	line-height: 1.6;
}

@keyframes timeline-panel-in {
	from {
		opacity: 0;
		transform: translateY(6px);
	}
	to {
		opacity: 1;
		transform: translateY(0);
	}
}

@keyframes timeline-item-in {
	from {
		opacity: 0;
		transform: translateY(3px);
	}
	to {
		opacity: 1;
		transform: translateY(0);
	}
}

@keyframes timeline-latest {
	0% {
		background: color-mix(in srgb, var(--cp-role-color) 8%, transparent);
	}
	100% {
		background: transparent;
	}
}

@media (max-width: 640px) {
	.timeline-panel {
		padding-top: 17px;
	}

	.timeline-header {
		align-items: flex-start;
		padding-inline: 16px;
	}

	.timeline-heading {
		gap: 10px;
	}

	.timeline-actions {
		margin-top: -4px;
	}

	.timeline-signals {
		padding-inline: 16px;
	}

	.timeline-list {
		padding-inline: 16px;
	}

	.timeline-time {
		width: 100%;
		margin-left: 0;
	}

	.timeline-state {
		align-items: flex-start;
		flex-wrap: wrap;
		margin-inline: 16px;
	}

	.timeline-state :deep(.v-btn) {
		margin-left: 31px;
	}

	.timeline-note {
		margin-inline: 16px;
	}
}

@media (max-width: 480px) {
	.timeline-header {
		flex-wrap: wrap;
	}

	.timeline-actions {
		width: 100%;
		justify-content: flex-end;
		padding-top: 2px;
	}

	.timeline-label {
		font-size: 13px;
	}
}

@media (prefers-reduced-transparency: reduce) {
	.timeline-panel {
		background: #fff;
		-webkit-backdrop-filter: none;
		backdrop-filter: none;
	}
}

@media (prefers-reduced-motion: reduce) {
	.timeline-panel,
	.timeline-item,
	.timeline-item.is-latest .timeline-body {
		animation: none;
	}
}
</style>
