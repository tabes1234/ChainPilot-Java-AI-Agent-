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
	<p class="legacy-redirect-note">正在前往智能体管理…</p>
</template>

<script setup lang="ts">
definePageMeta({
	middleware: () => navigateTo('/system/agents', { redirectCode: 302 }),
});
</script>

<style scoped>
.legacy-redirect-note {
	padding: 32px;
	color: #536a80;
	font-size: 15px;
}
</style>
