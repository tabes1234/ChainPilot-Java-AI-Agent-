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

<template>
	<section class="collapsible-section" :class="{ 'is-open': isOpen }">
		<button
			type="button"
			class="collapsible-section__trigger"
			:aria-expanded="isOpen"
			:aria-controls="contentId"
			@click="toggle"
		>
			<span v-if="icon" class="collapsible-section__icon">
				<v-icon :icon="icon" size="17" />
			</span>

			<span class="collapsible-section__copy">
				<span class="collapsible-section__title">{{ title }}</span>
				<span v-if="summary" class="collapsible-section__summary">{{ summary }}</span>
			</span>

			<span v-if="$slots.meta" class="collapsible-section__meta">
				<slot name="meta" />
			</span>

			<span class="collapsible-section__control" aria-hidden="true">
				<span>{{ isOpen ? '收起' : '展开' }}</span>
				<v-icon icon="mdi-chevron-down" size="19" />
			</span>
		</button>

		<div
			:id="contentId"
			class="collapsible-section__content"
			:inert="!isOpen"
			:aria-hidden="!isOpen"
		>
			<div class="collapsible-section__content-inner">
				<div class="collapsible-section__body">
					<slot />
				</div>
			</div>
		</div>
	</section>
</template>

<script setup lang="ts">
import { useId } from 'vue';

const props = withDefaults(
	defineProps<{
		title: string;
		summary?: string;
		icon?: string;
		modelValue?: boolean;
	}>(),
	{ modelValue: false },
);

const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>();
const contentId = `collapsible-${useId().replace(/[^a-zA-Z0-9_-]/g, '')}`;
const isOpen = computed(() => props.modelValue);

function toggle() {
	emit('update:modelValue', !isOpen.value);
}
</script>

<style scoped>
.collapsible-section {
	overflow: hidden;
	background: rgba(255, 255, 255, 0.84);
	border: 1px solid var(--cp-line);
	border-radius: var(--cp-radius-md);
	box-shadow: var(--cp-shadow-soft);
}

.collapsible-section__trigger {
	width: 100%;
	display: flex;
	align-items: center;
	gap: 12px;
	padding: 15px 17px;
	color: var(--cp-ink-strong);
	background: transparent;
	border: 0;
	text-align: left;
	cursor: pointer;
	transition: background var(--cp-duration-fast) var(--cp-ease-out);
}

.collapsible-section__trigger:hover { background: var(--cp-primary-soft); }
.collapsible-section__trigger:focus-visible { outline: 2px solid var(--cp-focus); outline-offset: -3px; }

.collapsible-section__icon {
	width: 34px;
	height: 34px;
	display: grid;
	place-items: center;
	flex: 0 0 auto;
	color: var(--cp-primary-deep);
	background: var(--cp-primary-soft);
	border-radius: 10px;
}

.collapsible-section__copy {
	min-width: 0;
	flex: 1;
	display: grid;
	gap: 3px;
}

.collapsible-section__title {
	font-size: 16px;
	font-weight: 750;
	line-height: 1.4;
}

.collapsible-section__summary {
	overflow: hidden;
	color: var(--cp-muted);
	font-size: 13px;
	line-height: 1.45;
	text-overflow: ellipsis;
	white-space: nowrap;
}

.collapsible-section__meta {
	min-width: 0;
	display: flex;
	align-items: center;
	gap: 8px;
	color: var(--cp-muted);
	font-size: 13px;
}

.collapsible-section__control {
	display: inline-flex;
	align-items: center;
	gap: 5px;
	flex: 0 0 auto;
	color: #48698c;
	font-size: 13px;
	font-weight: 720;
}

.collapsible-section__control .v-icon {
	transition: transform var(--cp-duration-base) var(--cp-ease-out);
}

.collapsible-section.is-open .collapsible-section__control .v-icon {
	transform: rotate(180deg);
}

.collapsible-section__content {
	display: grid;
	grid-template-rows: 0fr;
	transition: grid-template-rows var(--cp-duration-base) var(--cp-ease-out);
}

.collapsible-section.is-open .collapsible-section__content {
	grid-template-rows: 1fr;
}

.collapsible-section__content-inner {
	min-height: 0;
	overflow: hidden;
	border-top: 1px solid var(--cp-line-soft);
}

.collapsible-section__body {
	padding: 16px;
}

@media (max-width: 720px) {
	.collapsible-section__trigger { padding: 13px 12px; }
	.collapsible-section__summary { white-space: normal; }
	.collapsible-section__control > span:first-child { display: none; }
}

@media (prefers-reduced-motion: reduce) {
	.collapsible-section__content,
	.collapsible-section__control .v-icon { transition: none; }
}
</style>