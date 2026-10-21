<template>
	<span
		class="chainpilot-mark"
		:style="{ width: `${size}px`, height: `${size}px` }"
		:role="label ? 'img' : undefined"
		:aria-label="label || undefined"
		:aria-hidden="label ? undefined : 'true'"
	>
		<svg viewBox="0 0 48 48" xmlns="http://www.w3.org/2000/svg" focusable="false">
			<defs>
				<linearGradient :id="gradientId" x1="6" y1="4" x2="42" y2="45" gradientUnits="userSpaceOnUse">
					<stop stop-color="#6f9fe0" />
					<stop offset="0.55" stop-color="#557fc4" />
					<stop offset="1" stop-color="#4a8f88" />
				</linearGradient>
				<linearGradient :id="sheenId" x1="10" y1="6" x2="34" y2="38" gradientUnits="userSpaceOnUse">
					<stop stop-color="#ffffff" stop-opacity="0.48" />
					<stop offset="1" stop-color="#ffffff" stop-opacity="0" />
				</linearGradient>
			</defs>
			<rect x="1" y="1" width="46" height="46" rx="14" :fill="`url(#${gradientId})`" />
			<rect x="2.4" y="2.4" width="43.2" height="43.2" rx="12.8" fill="none" stroke="#ffffff" stroke-opacity="0.34" stroke-width="0.9" />
			<circle cx="24" cy="24" r="14.4" fill="#ffffff" fill-opacity="0.08" stroke="#ffffff" stroke-opacity="0.26" stroke-width="0.9" />
			<path d="M12.4 11.6c4.6-3.3 10.4-4.7 15.6-3.6" :stroke="`url(#${sheenId})`" stroke-width="5.4" stroke-linecap="round" />
			<path d="M30.1 16.7a9.5 9.5 0 1 0 0.1 14.7" fill="none" stroke="#ffffff" stroke-width="3" stroke-linecap="round" />
			<circle cx="30.8" cy="24" r="2.7" fill="#ffffff" />
			<path d="M33.5 24h5.1" stroke="#ffffff" stroke-width="1.8" stroke-linecap="round" stroke-opacity="0.76" />
			<circle cx="39.2" cy="17" r="1.15" fill="#edf8f5" fill-opacity="0.92" />
		</svg>
	</span>
</template>

<script setup lang="ts">
import { useId } from 'vue';

withDefaults(
	defineProps<{
		size?: number;
		label?: string;
	}>(),
	{
		size: 36,
		label: '',
	},
);

const uid = useId().replace(/[^a-zA-Z0-9_-]/g, '');
const gradientId = `cp-mark-gradient-${uid}`;
const sheenId = `cp-mark-sheen-${uid}`;
</script>

<style scoped>
.chainpilot-mark {
	display: inline-grid;
	flex: 0 0 auto;
	place-items: center;
	line-height: 0;
}

.chainpilot-mark svg {
	display: block;
	width: 100%;
	height: 100%;
	filter: drop-shadow(0 5px 10px rgba(49, 95, 157, 0.18));
}
</style>
