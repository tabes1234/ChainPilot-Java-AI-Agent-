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
	<v-app id="app">
		<v-main>
			<BaseDrawer v-model="drawer" :drawer-width="280">
				<template #drawer>
					<div class="drawer-shell">
						<div class="drawer-brand">
							<NuxtLink class="brand-link" to="/" @click="closeMobileDrawer">
								<ChainPilotMark :size="40" label="ChainPilot 品牌标识" />
								<span class="brand-link__copy">
									<span class="brand-link__name">ChainPilot</span>
									<span class="brand-link__subtitle">供应链智能决策工作台</span>
								</span>
							</NuxtLink>
						</div>

						<div class="agent-switcher-box">
							<div class="agent-switcher-heading">
								<span>当前智能体</span>
								<span v-if="selectedAgent" class="agent-status" :class="`agent-status--${selectedAgent.status}`">
									{{ agentStatusLabel(selectedAgent.status) }}
								</span>
								<v-btn
									v-else-if="agentsError"
									size="x-small"
									variant="text"
									color="error"
									prepend-icon="mdi-retry"
									@click="loadAgents"
								>
									重试
								</v-btn>
							</div>

							<v-select
								v-model="selectedAgentId"
								:items="agentOptions"
								:loading="loadingAgents || Boolean(switchingAgentId)"
								:disabled="agentOptions.length === 0"
								item-title="title"
								item-value="value"
								variant="outlined"
								density="comfortable"
								hide-details
								placeholder="暂无可用智能体"
								menu-icon="mdi-chevron-down"
								class="agent-switcher"
								:menu-props="{ contentClass: 'agent-switcher-menu', offset: [0, 8] }"
								item-color="primary"
								@update:model-value="handleAgentSwitch"
							>
								<template #selection="{ item }">
									<div class="agent-selection">
										<span class="agent-avatar">
											<v-img v-if="item.raw.avatar" :src="item.raw.avatar" cover />
											<v-icon v-else icon="mdi-robot-outline" size="17" />
										</span>
										<span class="agent-selection__name" :title="item.raw.title">{{ item.raw.title }}</span>
									</div>
								</template>
								<template #item="{ props, item }">
									<v-list-item
										v-bind="props"
										:title="undefined"
										:subtitle="undefined"
										class="agent-option"
										:class="{ 'agent-option--active': item.raw.value === selectedAgentId }"
									>
										<template #prepend>
											<span class="agent-avatar agent-avatar--menu">
												<v-img v-if="item.raw.avatar" :src="item.raw.avatar" cover />
												<v-icon v-else icon="mdi-robot-outline" size="17" />
											</span>
										</template>
										<v-list-item-title class="agent-option__title">{{ item.raw.title }}</v-list-item-title>
										<v-list-item-subtitle class="agent-option__description">
											{{ item.raw.description }}
										</v-list-item-subtitle>
										<template #append>
											<v-icon
												v-if="item.raw.value === selectedAgentId"
												icon="mdi-check-circle"
												color="primary"
												size="18"
											/>
										</template>
									</v-list-item>
								</template>
							</v-select>

							<p v-if="selectedAgent?.description" class="agent-description" :title="selectedAgent.description">
								{{ selectedAgent.description }}
							</p>
							<p v-else-if="agentsError" class="agent-description agent-description--error">
								{{ agentsError }}
							</p>
							<p v-else-if="!loadingAgents" class="agent-description">
								暂无可用智能体，请先在智能体管理中创建。
							</p>

							<p class="agent-scope-note">
								用于知识配置、提示词和数据问答。供应链分析读取当前工作区已配置的工具。
							</p>
						</div>

						<v-list
							v-model:opened="openedGroups"
							density="comfortable"
							nav
							class="drawer-navigation"
						>
							<v-list-item
								prepend-icon="mdi-home-variant-outline"
								title="首页"
								:active="isActive('/')"
								class="navigation-item"
								color="primary"
								@click="navigateToPath('/')"
							/>

							<v-list-subheader class="nav-section-label">工作台</v-list-subheader>
							<v-list-item
								prepend-icon="mdi-radar"
								title="供应链分析"
								:active="isWorkspaceView('analysis')"
								class="navigation-item"
								color="primary"
								@click="navigateToWorkspaceView('analysis')"
							/>
							<v-list-item
								prepend-icon="mdi-chat-processing-outline"
								title="数据问答"
								:active="isActive('/chat')"
								class="navigation-item"
								color="primary"
								@click="navigateToPath('/chat')"
							/>
							<v-list-item
								prepend-icon="mdi-history"
								title="任务与执行记录"
								:active="isWorkspaceView('history')"
								class="navigation-item"
								color="primary"
								@click="navigateToWorkspaceView('history')"
							/>

							<v-list-subheader class="nav-section-label">风险决策</v-list-subheader>
							<v-list-item
								prepend-icon="mdi-comment-processing-outline"
								title="自然语言决策"
								:active="isWorkspaceView('decision')"
								class="navigation-item"
								color="primary"
								@click="navigateToWorkspaceView('decision')"
							/>
							<v-list-item
								prepend-icon="mdi-shield-search"
								title="处置与沙箱"
								:active="isWorkspaceView('disposal')"
								class="navigation-item"
								color="primary"
								@click="navigateToWorkspaceView('disposal')"
							/>

							<v-list-group value="knowledge">
								<template #activator="{ props }">
									<v-list-item
										v-bind="props"
										title="知识与智能体"
										class="nav-group-label"
									/>
								</template>
								<v-list-item
									prepend-icon="mdi-book-open-variant"
									title="业务术语"
									:active="isActive('/knowledge/business')"
									class="navigation-sub-item"
									color="primary"
									@click="navigateToPath('/knowledge/business')"
								/>
								<v-list-item
									prepend-icon="mdi-brain"
									title="智能体知识"
									:active="isActive('/knowledge/agents')"
									class="navigation-sub-item"
									color="primary"
									@click="navigateToPath('/knowledge/agents')"
								/>
								<v-list-item
									prepend-icon="mdi-vector-intersection"
									title="语义模型"
									:active="isActive('/knowledge/semantic-models')"
									class="navigation-sub-item"
									color="primary"
									@click="navigateToPath('/knowledge/semantic-models')"
								/>
								<v-list-item
									prepend-icon="mdi-robot-outline"
									title="智能体管理"
									:active="isActive('/system/agents')"
									class="navigation-sub-item"
									color="primary"
									@click="navigateToPath('/system/agents')"
								/>
								<v-list-item
									prepend-icon="mdi-plus-box-outline"
									title="新建智能体"
									:active="isActive('/agent/new')"
									class="navigation-sub-item"
									color="primary"
									@click="navigateToPath('/agent/new')"
								/>
							</v-list-group>

							<v-list-group value="system">
								<template #activator="{ props }">
									<v-list-item
										v-bind="props"
										title="系统设置"
										class="nav-group-label"
									/>
								</template>
								<v-list-item
									prepend-icon="mdi-cpu-64-bit"
									title="模型配置"
									:active="isActive('/system/model-config')"
									class="navigation-sub-item"
									color="primary"
									@click="navigateToPath('/system/model-config')"
								/>
								<v-list-item
									prepend-icon="mdi-auto-fix"
									title="提示词配置"
									:active="isActive('/prompt-config')"
									class="navigation-sub-item"
									color="primary"
									@click="navigateToPath('/prompt-config')"
								/>
								<v-list-item
									prepend-icon="mdi-database-cog-outline"
									title="数据源管理"
									:active="isActive('/system/data-sources')"
									class="navigation-sub-item"
									color="primary"
									@click="navigateToPath('/system/data-sources')"
								/>
							</v-list-group>
						</v-list>
					</div>
				</template>

				<template #header="{ toggle, isOpen }">
					<v-btn
						icon
						variant="text"
						size="small"
						class="navigation-toggle"
						:aria-label="isOpen ? '收起导航' : '展开导航'"
						:title="isOpen ? '收起导航' : '展开导航'"
						@click="toggle"
					>
						<v-icon :icon="isOpen ? 'mdi-menu-open' : 'mdi-menu'" />
					</v-btn>
					<div class="app-header-context" aria-label="当前位置">
						<span class="app-header-brand">ChainPilot</span>
						<span class="app-header-divider" aria-hidden="true">/</span>
						<span class="app-header-section">{{ currentRouteSection }}</span>
					</div>
					<v-spacer />
				</template>

				<slot />
			</BaseDrawer>
		</v-main>

		<ConfirmDialog
			v-model="dialogState.isVisible"
			:title="dialogState.title"
			:message="dialogState.message"
			:prepend-icon="dialogState.icon"
			:confirm-text="dialogState.confirmText"
			@confirm="handleGlobalConfirm"
		/>
		<Tip />
	</v-app>
</template>

<script setup lang="ts">
import BaseDrawer from '../components/BaseDrawer/index.vue';
import agentService from '~/services/agent/index';
import { useDisplay } from 'vuetify';

const { dialogState, handleGlobalConfirm } = useConfirm();
const { $tip } = useNuxtApp();
const drawer = ref(true);
const { mobile } = useDisplay();
const router = useRouter();
const route = useRoute();

type DrawerAgentOption = {
	id: number;
	title: string;
	value: number;
	description: string;
	status: string;
	avatar?: string;
};

const agents = ref<DrawerAgentOption[]>([]);
const selectedAgentId = ref<number | undefined>(undefined);
const loadingAgents = ref(true);
const agentsError = ref('');
const switchingAgentId = ref<number | undefined>(undefined);
const SELECTED_AGENT_STORAGE_KEY = 'chainpilot:selected-agent-id';

const agentOptions = computed(() => agents.value);
const selectedAgent = computed(() =>
	agents.value.find((agent) => agent.id === selectedAgentId.value),
);
const openedGroups = ref<string[]>([]);

const activeNavGroup = computed(() => {
	if (
		route.path.startsWith('/knowledge/') ||
		route.path.startsWith('/agent/') ||
		route.path === '/system/agents'
	) {
		return 'knowledge';
	}
	if (
		route.path === '/prompt-config' ||
		route.path === '/system/model-config' ||
		route.path.startsWith('/system/data-sources')
	) {
		return 'system';
	}
	return '';
});

watch(
	activeNavGroup,
	(group) => {
		if (group && !openedGroups.value.includes(group)) {
			openedGroups.value = [...openedGroups.value, group];
		}
	},
	{ immediate: true },
);

watch(
	mobile,
	(isMobile) => {
		if (isMobile) drawer.value = false;
	},
	{ immediate: true },
);

type WorkspaceNavView = 'analysis' | 'decision' | 'disposal' | 'history';

function resolveWorkspaceNavView(): WorkspaceNavView {
	const value = route.query.view;
	const view = Array.isArray(value) ? value[0] : value;
	return view === 'decision' || view === 'disposal' || view === 'history' ? view : 'analysis';
}

const currentRouteSection = computed(() => {
	if (route.path === '/') return '首页';
	if (route.path.startsWith('/knowledge/') || route.path.startsWith('/agent/') || route.path === '/system/agents') {
		return '知识与智能体';
	}
	if (route.path.startsWith('/system/') || route.path.startsWith('/prompt-config')) {
		return '系统设置';
	}
	if (route.path === '/supply-chain') {
		const view = resolveWorkspaceNavView();
		return view === 'decision' || view === 'disposal' ? '风险决策' : '工作台';
	}
	if (route.path === '/chat' || route.path === '/dashboard') return '工作台';
	return '工作区';
});

function parseRouteAgentId() {
	const pathId = Number(route.params.id);
	if (
		route.path.startsWith('/agent/') &&
		route.path !== '/agent/new' &&
		Number.isFinite(pathId) &&
		pathId > 0
	) {
		return pathId;
	}
	const queryValue = route.query.agentId;
	const queryId = Number(Array.isArray(queryValue) ? queryValue[0] : queryValue);
	return Number.isFinite(queryId) && queryId > 0 ? queryId : undefined;
}

function getQueryWithAgentId(agentId?: number) {
	const query: Record<string, string> = {};
	Object.keys(route.query).forEach((key) => {
		if (key === 'agentId') return;
		const value = route.query[key];
		if (Array.isArray(value) && value[0]) query[key] = String(value[0]);
		else if (typeof value === 'string') query[key] = value;
	});
	if (agentId) query.agentId = String(agentId);
	return query;
}

function applyAgentToCurrentRoute(agentId: number, replace = false) {
	if (route.path === '/agent/new') return;
	const target =
		route.path.startsWith('/agent/') && route.path !== '/agent/new'
			? { path: `/agent/${agentId}` }
			: { path: route.path, query: getQueryWithAgentId(agentId) };
	const navigation = replace ? router.replace(target) : router.push(target);
	void navigation.catch(() => undefined);
}

function readStoredAgentId() {
	if (!import.meta.client) return undefined;
	const value = Number(localStorage.getItem(SELECTED_AGENT_STORAGE_KEY));
	return Number.isFinite(value) && value > 0 ? value : undefined;
}

function writeStoredAgentId(agentId: number) {
	if (import.meta.client) localStorage.setItem(SELECTED_AGENT_STORAGE_KEY, String(agentId));
}

function setAgentSelection(agentId: number, replace = false) {
	selectedAgentId.value = agentId;
	writeStoredAgentId(agentId);
	if (parseRouteAgentId() !== agentId) applyAgentToCurrentRoute(agentId, replace);
}

function normalizeAgentSelection(replace = false) {
	if (agents.value.length === 0) return;
	const routeAgentId = parseRouteAgentId();
	const storedAgentId = readStoredAgentId();
	const candidate =
		routeAgentId && agents.value.some((agent) => agent.id === routeAgentId)
			? routeAgentId
			: storedAgentId && agents.value.some((agent) => agent.id === storedAgentId)
				? storedAgentId
				: agents.value[0].id;
	if (candidate !== selectedAgentId.value || parseRouteAgentId() !== candidate) {
		setAgentSelection(candidate, replace);
	}
}

function navigateToWorkspaceView(view: WorkspaceNavView) {
	closeMobileDrawer();
	const query: Record<string, string> = {};
	if (selectedAgentId.value) query.agentId = String(selectedAgentId.value);
	if (view !== 'analysis') query.view = view;
	void router.push({ path: '/supply-chain', query }).catch(() => undefined);
}

function navigateToPath(path: string) {
	closeMobileDrawer();
	if (path === '/' || path === '/agent/new') {
		if (route.path !== path) void router.push({ path }).catch(() => undefined);
		return;
	}
	if (path.startsWith('/agent/')) {
		const agentId = selectedAgentId.value;
		void router.push({ path: agentId ? `/agent/${agentId}` : path }).catch(() => undefined);
		return;
	}
	const query = selectedAgentId.value ? { agentId: String(selectedAgentId.value) } : {};
	void router.push({ path, query }).catch(() => undefined);
}

function closeMobileDrawer() {
	if (mobile.value) drawer.value = false;
}

async function handleAgentSwitch(value: number | string | undefined) {
	const nextAgentId = Number(value);
	const nextAgent = agents.value.find((agent) => agent.id === nextAgentId);
	if (!nextAgent) return;

	const previousAgentId = selectedAgentId.value;
	switchingAgentId.value = nextAgentId;
	selectedAgentId.value = nextAgentId;
	writeStoredAgentId(nextAgentId);

	try {
		if (parseRouteAgentId() !== nextAgentId) {
			await router.push(
				route.path.startsWith('/agent/') && route.path !== '/agent/new'
					? { path: `/agent/${nextAgentId}` }
					: { path: route.path, query: getQueryWithAgentId(nextAgentId) },
			);
		}
		$tip(`已切换至「${nextAgent.title}」`, { icon: 'mdi-account-switch-outline' });
	} catch (error) {
		if (previousAgentId) {
			selectedAgentId.value = previousAgentId;
			writeStoredAgentId(previousAgentId);
		}
		const reason = error instanceof Error ? error.message : '页面状态未能完成切换';
		$tip(`智能体切换失败：${reason}`, { color: 'error', icon: 'mdi-alert-circle' });
	} finally {
		switchingAgentId.value = undefined;
	}
}

const isActive = (path: string) => route.path === path;

function isWorkspaceView(view: WorkspaceNavView) {
	return route.path === '/supply-chain' && resolveWorkspaceNavView() === view;
}

function agentStatusLabel(status: string) {
	if (status === 'published') return '已发布';
	if (status === 'draft') return '草稿';
	if (status === 'offline') return '已下线';
	return status || '状态未知';
}

async function loadAgents() {
	loadingAgents.value = true;
	agentsError.value = '';
	try {
		const list = await agentService.list();
		agents.value = list
			.filter((item) => item.id !== undefined && item.id > 0)
			.map((item) => {
				const raw = item as unknown as Record<string, unknown>;
				const description = [raw.description, raw.tags]
					.filter((value): value is string => typeof value === 'string' && Boolean(value.trim()))
					.join(' · ');
				return {
					id: item.id as number,
					title: item.name || `智能体 ${item.id}`,
					value: item.id as number,
					description: description || '暂未配置职责说明',
					status: typeof raw.status === 'string' ? raw.status : 'unknown',
					avatar: typeof raw.avatar === 'string' ? raw.avatar : undefined,
				};
			});
		normalizeAgentSelection(true);
	} catch (error) {
		agents.value = [];
		agentsError.value =
			error instanceof Error ? `智能体列表加载失败：${error.message}` : '智能体列表暂时无法加载。';
	} finally {
		loadingAgents.value = false;
	}
}

onMounted(loadAgents);

watch(
	() => route.fullPath,
	() => normalizeAgentSelection(true),
);
</script>

<style scoped>
.drawer-shell {
	height: 100%;
	display: flex;
	flex-direction: column;
	background: #f5f9fd;
}

.drawer-brand {
	padding: 18px 18px 14px;
	border-bottom: 1px solid #dce8f5;
}

.brand-link {
	display: flex;
	align-items: center;
	gap: 12px;
	min-width: 0;
	color: #1d3854;
	text-decoration: none;
	border-radius: 12px;
	padding: 4px;
	transition: background var(--cp-duration-fast) var(--cp-ease-out);
}

.brand-link:hover { background: #e8f1fb; }
.brand-link__copy { min-width: 0; display: grid; }
.brand-link__name { font-size: 19px; font-weight: 800; line-height: 1.25; }
.brand-link__subtitle { margin-top: 3px; color: #52708e; font-size: 12px; font-weight: 650; }

.agent-switcher-box {
	margin: 14px 16px 10px;
	padding: 13px;
	background: rgba(255, 255, 255, 0.78);
	border: 1px solid #d8e7f4;
	border-radius: 14px;
}

.agent-switcher-heading {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 8px;
	margin-bottom: 9px;
	color: #365876;
	font-size: 12px;
	font-weight: 800;
	letter-spacing: 0.06em;
}

.agent-status {
	padding: 2px 7px;
	border-radius: 999px;
	background: #eaf3fb;
	color: #486b8c;
	font-size: 13px;
	font-weight: 700;
}

.agent-status--published { color: #2f6b4e; background: #eaf5ef; }
.agent-status--draft { color: #8b6328; background: #fff5e5; }
.agent-status--offline { color: #60758f; background: #edf2f7; }

.agent-selection {
	min-width: 0;
	display: flex;
	align-items: center;
	gap: 8px;
}

.agent-selection__name {
	min-width: 0;
	overflow: hidden;
	text-overflow: ellipsis;
	white-space: nowrap;
	font-size: 14px;
	font-weight: 700;
}

.agent-avatar {
	width: 28px;
	height: 28px;
	display: grid;
	place-items: center;
	flex: 0 0 auto;
	overflow: hidden;
	color: #3d71a5;
	background: #e5f0fb;
	border-radius: 9px;
}

.agent-avatar--menu { width: 32px; height: 32px; flex-basis: 32px; }
.agent-description {
	margin: 9px 0 0;
	color: #536d85;
	font-size: 12px;
	line-height: 1.55;
	display: -webkit-box;
	-webkit-line-clamp: 2;
	-webkit-box-orient: vertical;
	overflow: hidden;
}

.agent-description--error { color: #9c4551; }

.agent-scope-note {
	margin: 9px 0 0;
	padding-top: 8px;
	border-top: 1px solid #e4edf6;
	color: #526a80;
	font-size: 12px;
	line-height: 1.5;
}

.drawer-navigation {
	min-height: 0;
	flex: 1;
	padding: 6px 10px 18px;
	overflow-y: auto;
	overscroll-behavior: contain;
}

.navigation-item,
.navigation-sub-item {
	min-height: 40px;
	margin-bottom: 4px;
	border-radius: 11px;
	color: #33506b;
	font-size: 14px;
	font-weight: 680;
}

.navigation-sub-item {
	font-size: 13px;
}

.navigation-item :deep(.v-list-item__overlay),
.navigation-sub-item :deep(.v-list-item__overlay) {
	opacity: 0;
}

.navigation-item:hover :deep(.v-list-item__overlay),
.navigation-sub-item:hover :deep(.v-list-item__overlay),
.navigation-item:focus-visible :deep(.v-list-item__overlay),
.navigation-sub-item:focus-visible :deep(.v-list-item__overlay) {
	opacity: 0.075;
}

.navigation-item--active,
.navigation-item.v-list-item--active,
.navigation-sub-item.v-list-item--active {
	position: relative;
	background: var(--cp-primary-soft);
	border-radius: 12px;
}

.navigation-item.v-list-item--active::before,
.navigation-sub-item.v-list-item--active::before {
	content: '';
	position: absolute;
	left: 6px;
	top: 50%;
	width: 4px;
	height: 20px;
	border-radius: 999px;
	background: var(--cp-primary);
	transform: translateY(-50%);
	transition: height var(--cp-duration-base) var(--cp-ease-out);
}

.nav-section-label {
	height: auto;
	padding: 16px 8px 6px;
	color: #536a80 !important;
	font-size: 12px;
	font-weight: 800;
	letter-spacing: 0.08em;
	line-height: 1.45;
}

.nav-group-label {
	min-height: 38px;
	color: #3b628a !important;
	background: transparent;
	font-size: 13px;
	font-weight: 800;
	letter-spacing: 0.04em;
}

.app-header-context {
	min-width: 0;
	display: flex;
	align-items: center;
	gap: 8px;
	font-size: 13px;
	line-height: 1.4;
}

.app-header-brand {
	color: #5a708c;
	font-weight: 700;
	white-space: nowrap;
}

.app-header-divider { color: #788ca2; }

.app-header-section {
	min-width: 0;
	color: #2b4d6f;
	font-weight: 800;
	white-space: nowrap;
	overflow: hidden;
	text-overflow: ellipsis;
}

.navigation-toggle {
	color: #3b628a;
}

:deep(.agent-switcher .v-field) {
	background: rgba(255, 255, 255, 0.92);
	border: 1px solid #cfdfef;
	border-radius: 11px;
	box-shadow: none;
}

:deep(.agent-switcher .v-field__field),
:deep(.agent-switcher .v-field__input) {
	min-height: 40px;
}

:deep(.agent-switcher .v-field__append-inner) {
	color: #607d99;
}

:deep(.agent-switcher-menu) {
	background: #fff !important;
	border: 1px solid #cfdfef !important;
	border-radius: 12px !important;
	box-shadow: var(--cp-shadow-raised) !important;
	overflow: hidden;
}

:deep(.agent-switcher-menu .v-list) {
	background: #fff !important;
	padding: 5px !important;
}

:deep(.agent-switcher-menu .v-list-item) {
	min-height: 52px;
	border-radius: 9px;
}

.agent-option {
	min-width: 0;
}

.agent-option__title {
	color: #24425f;
	font-size: 13px;
	line-height: 1.3;
	font-weight: 720;
}

.agent-option__description {
	color: #62788d;
	font-size: 13px;
	line-height: 1.35;
}

.agent-option--active .agent-option__title {
	color: var(--cp-primary-deep);
}

:deep(.drawer-navigation .v-list-group__items .v-list-item) {
	padding-inline-start: 18px !important;
}

@media (max-width: 480px) {
	.app-header-brand,
	.app-header-divider {
		display: none;
	}

	.app-header-section { font-size: 13px; }
}

@media (prefers-reduced-motion: reduce) {
	.brand-link,
	.navigation-item,
	.navigation-sub-item,
	.navigation-item.v-list-item--active::before,
	.navigation-sub-item.v-list-item--active::before {
		transition: none;
	}
}
</style>