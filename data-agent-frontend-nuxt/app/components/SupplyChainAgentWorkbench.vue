<template>
	<section
		id="chainpilot-workbench"
		class="cp-workbench"
		:class="`cp-workbench--${statusTone}`"
		aria-label="ChainPilot AI 工作台"
	>
		<div class="cp-workbench-topline" aria-hidden="true" />
		<header class="cp-wb-header">
			<div class="cp-wb-heading">
				<div class="cp-wb-mark" :class="{ 'is-running': isRunning }">
					<ChainPilotMark :size="46" />
					<span class="cp-wb-mark-ring" aria-hidden="true" />
				</div>
				<div class="cp-wb-heading-copy">
					<div class="cp-wb-kicker">CHAINPILOT AI WORKBENCH</div>
					<div class="cp-wb-title-row">
						<h2 class="cp-wb-title">ChainPilot AI 工作台</h2>
						<span class="cp-wb-state" :class="`cp-wb-state--${statusTone}`" role="status">
							<span class="cp-wb-state-dot" aria-hidden="true" />
							{{ statusLabel }}
						</span>
					</div>
					<p class="cp-wb-subtitle">从业务数据到决策建议，每一步都清晰可见。</p>
				</div>
			</div>
			<div class="cp-wb-header-actions">
				<span v-if="task" class="cp-wb-source">{{ sourceLabel }}</span>
				<span v-if="pendingCount" class="cp-wb-pending-chip">
					<v-icon icon="mdi-account-check-outline" size="13" />{{ pendingCount }} 个提案待确认
				</span>
				<button v-if="task" type="button" class="cp-wb-link" @click="emitTimeline">打开完整轨迹</button>
			</div>
		</header>

		<template v-if="task">
			<div class="cp-wb-taskline">
				<div class="cp-wb-question-block">
					<span class="cp-wb-label">当前任务</span>
					<p class="cp-wb-question">{{ task.question || '后端任务未携带问题文本' }}</p>
				</div>
				<div class="cp-wb-task-meta">
					<span class="cp-wb-task-id">任务 {{ task.taskId }}</span>
					<span v-if="task.updatedAt" class="cp-wb-task-time">{{ formatEventTime(task.updatedAt) }}</span>
					<button type="button" class="cp-wb-link" @click="focusInput">回到输入</button>
				</div>
			</div>

			<div v-if="realSignals.length" class="cp-wb-signals" aria-label="真实模型与工具信号">
				<span
					v-for="signal in realSignals"
					:key="signal.key"
					class="cp-wb-signal"
					:style="roleCssVars(signal.role)"
				>
					<v-icon :icon="signal.icon" size="13" />{{ signal.label }}
				</span>
			</div>

			<ol v-if="stages.length" class="cp-wb-stages" aria-label="后端真实执行阶段">
				<li
					v-for="stage in stages"
					:key="stage.key"
					class="cp-wb-stage"
					:class="[`cp-wb-stage--${stage.state}`, { 'cp-wb-stage--retry': stage.isRetry }]"
					:style="roleCssVars(stage.role)"
				>
					<span class="cp-wb-stage-icon">
						<v-icon :icon="CHAINPILOT_ROLES[stage.role].icon" size="15" />
						<span class="cp-wb-stage-state-icon" aria-hidden="true">
							<v-icon v-if="stage.state === 'completed'" icon="mdi-check" size="9" />
							<v-icon v-else-if="stage.state === 'failed'" icon="mdi-alert" size="9" />
							<span v-else-if="stage.state === 'active'" class="cp-wb-stage-pulse" />
						</span>
					</span>
					<span class="cp-wb-stage-copy">
						<strong>{{ stage.label }}</strong>
						<small>{{ workbenchStageStateLabel(stage.state) }}</small>
					</span>
				</li>
			</ol>
			<p v-else class="cp-wb-stage-empty">任务已受理，等待后端写入首个真实阶段；页面不会补造步骤。</p>

			<div class="cp-wb-body">
				<div class="cp-wb-feed">
					<div class="cp-wb-section-head">
						<span>最近的真实事件</span>
						<span>{{ eventCount }} 条 · 后端序号顺序</span>
					</div>

					<div v-if="eventsError" class="cp-wb-connection" role="alert">
						<v-icon icon="mdi-lan-disconnect" size="18" />
						<div>
							<strong>执行轨迹连接异常</strong>
							<span>{{ eventsError }}</span>
						</div>
						<button type="button" class="cp-wb-link" @click="retryEvents">重试读取</button>
					</div>
					<div v-else-if="loading && recentViews.length === 0" class="cp-wb-feed-state">
						<v-progress-circular indeterminate size="17" width="2" color="primary" />
						<span>正在读取后端任务事件…</span>
					</div>
					<div v-else-if="recentViews.length === 0" class="cp-wb-feed-state">
						<v-icon icon="mdi-timeline-clock-outline" size="18" />
						<span>{{ isRunning ? '任务仍在执行，尚未产生可展示事件。' : '该任务没有已持久化的执行事件。' }}</span>
					</div>
					<ol v-else class="cp-wb-events">
						<li
							v-for="event in recentViews"
							:key="event.id"
							class="cp-wb-event"
							:class="{ 'is-highlight': event.id === highlightEventId }"
							:style="roleCssVars(event.role)"
						>
							<span class="cp-wb-event-icon"><v-icon :icon="event.roleIcon" size="14" /></span>
							<div class="cp-wb-event-main">
								<div class="cp-wb-event-top">
									<strong>{{ event.typeLabel }}</strong>
									<span class="cp-wb-event-role">{{ event.roleLabel }}</span>
									<time :datetime="event.occurredAt || undefined">{{ event.timeLabel }}</time>
								</div>
								<p v-if="event.summary">{{ event.summary }}</p>
								<span class="cp-wb-event-meta">#{{ event.sequence }} · {{ event.stageLabel }} · {{ event.statusLabel }}</span>
							</div>
						</li>
					</ol>

					<div v-if="recentViews.length || eventCount" class="cp-wb-feed-foot">
						<span>{{ loading ? '正在同步最新事件…' : '仅展示后端已写入的事件。' }}</span>
						<button type="button" class="cp-wb-link" @click="emitTimeline">查看全部轨迹</button>
					</div>
				</div>

				<aside class="cp-wb-outcome" aria-label="当前任务结果与下一步">
					<div v-if="pendingCount" class="cp-wb-pending" :style="roleCssVars('confirmation')">
						<span class="cp-wb-pending-kicker"><v-icon icon="mdi-account-check-outline" size="14" />人工确认边界</span>
						<strong>{{ pendingCount }} 个提案等待你确认</strong>
						<p>这些写操作尚未执行。确认时后端会重新校验对象状态、参数、资格与内容哈希。</p>
						<button type="button" class="cp-wb-action cp-wb-action--confirm" @click="focusProposals">前往确认提案</button>
					</div>

					<div v-if="isFailure" class="cp-wb-failure" role="alert">
						<div class="cp-wb-failure-head">
							<span class="cp-wb-failure-icon"><v-icon icon="mdi-alert-circle-outline" size="19" /></span>
							<div>
								<strong>任务执行失败</strong>
								<span>{{ failureStageLabel }}</span>
							</div>
						</div>
						<p class="cp-wb-failure-message">{{ task.error || '后端未返回错误说明，请打开执行轨迹查看失败事件。' }}</p>
						<div class="cp-wb-actions">
							<button type="button" class="cp-wb-action" @click="emitTimeline">查看失败轨迹</button>
							<button v-if="task.source === 'ANALYSIS'" type="button" class="cp-wb-action cp-wb-action--primary" @click="retryAnalysis">重新运行分析</button>
						</div>
					</div>

					<template v-else-if="isQueued">
						<div class="cp-wb-live">
							<span class="cp-wb-label">当前阶段</span>
							<strong class="cp-wb-live-title">任务已进入后端队列</strong>
							<p>尚未写入节点事件。工作台只展示可证明的排队状态，不显示虚构进度。</p>
						</div>
					</template>

					<template v-else-if="isRunning">
						<div class="cp-wb-live">
							<span class="cp-wb-label">当前阶段</span>
							<strong class="cp-wb-live-title">{{ currentStageLabel }}</strong>
							<p>{{ currentStageDescription }}</p>
							<div class="cp-wb-live-grid">
								<div><span>已记录事件</span><strong>{{ eventCount }}</strong></div>
								<div><span>工具调用</span><strong>{{ toolCallCount }}</strong></div>
								<div><span>最近事件</span><strong>{{ latestEventTime }}</strong></div>
							</div>
						</div>
						<div v-if="fallbackUsed" class="cp-wb-fallback">
							<v-icon icon="mdi-swap-horizontal" size="15" />
							<span>已出现规则降级事件；数值仍由后端确定性工具产生。</span>
						</div>
					</template>

					<template v-else-if="isCompleted">
						<div class="cp-wb-result-head">
							<div>
								<span class="cp-wb-label">本次真实结果</span>
								<strong>{{ resultTitle }}</strong>
							</div>
							<span class="cp-wb-result-source">{{ resultSourceLabel }}</span>
						</div>
						<div v-if="metrics.length" class="cp-wb-metrics">
							<div
								v-for="metric in metrics"
								:key="`${metric.role}-${metric.label}`"
								class="cp-wb-metric"
								:style="roleCssVars(metric.role)"
							>
								<span>{{ metric.label }}</span>
								<strong>{{ metric.value }}</strong>
							</div>
						</div>
						<p v-else-if="resultPending" class="cp-wb-muted">任务已完成，完整结果仍在读取；页面不会补造数值。</p>
						<p v-else class="cp-wb-muted">任务已完成，后端没有返回可展示的业务指标。</p>
						<div v-if="fallbackUsed" class="cp-wb-fallback">
							<v-icon icon="mdi-swap-horizontal" size="15" />
							<span>{{ fallbackNote }}</span>
						</div>
						<div class="cp-wb-actions">
							<button type="button" class="cp-wb-action cp-wb-action--primary" @click="focusResult">查看完整结果</button>
							<button v-if="pendingCount" type="button" class="cp-wb-action cp-wb-action--confirm" @click="focusProposals">确认提案</button>
							<button type="button" class="cp-wb-action" @click="emitTimeline">执行轨迹</button>
						</div>
					</template>

					<div v-if="syncError && !isFailure" class="cp-wb-sync-error">
						<v-icon icon="mdi-lan-disconnect" size="16" />
						<div>
							<strong>任务状态连接异常</strong>
							<span>{{ syncError }}</span>
							<small>这是连接问题，不代表任务执行失败；可打开任务历史重新查询真实状态。</small>
						</div>
					</div>
				</aside>
			</div>

			<footer class="cp-wb-footer">
				<span><v-icon icon="mdi-shield-check-outline" size="14" />工作台只读取真实任务状态与后端事件；没有任务时不运行动画或进度。</span>
				<span v-if="latestCompletedNode">最近完成：{{ latestCompletedNode }}</span>
			</footer>
		</template>

		<template v-else>
			<div class="cp-wb-idle">
				<div class="cp-wb-idle-copy">
					<span class="cp-wb-label">等待真实任务</span>
					<strong>还没有正在跟踪的 Agent 任务</strong>
					<p>提交风险分析或自然语言决策后，这里会按后端实际写入的事件展示阶段、工具调用、模型状态与决策结果；无任务时不会出现虚构进度。</p>
					<div class="cp-wb-idle-actions">
						<button type="button" class="cp-wb-action cp-wb-action--primary" @click="focusInput('analysis')">发起风险分析</button>
						<button type="button" class="cp-wb-action" @click="focusInput('decision')">使用自然语言决策</button>
					</div>
				</div>
				<div class="cp-wb-capabilities" aria-label="工作台覆盖的真实环节">
					<span class="cp-wb-capabilities-title">工作台覆盖的真实环节</span>
					<div class="cp-wb-capability-list">
						<span
							v-for="role in capabilityRoles"
							:key="role.id"
							class="cp-wb-capability"
							:style="roleCssVars(role.id)"
						>
							<v-icon :icon="role.icon" size="14" />{{ role.label }}
						</span>
					</div>
				</div>
			</div>
		</template>
	</section>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, watch } from 'vue';
import { useSupplyChainEvents } from '~/composables/useSupplyChainEvents';
import type { DecisionProposal, DecisionTaskResult } from '~/services/supplyChainDecision';
import { formatEventTime, sortEventsBySequence, summarizeTimelineSignals, toTimelineEventView } from '~/utils/supplyChainEventTimeline';
import {
	CHAINPILOT_ROLES,
	ROLE_CAPABILITY_ORDER,
	roleCssVars,
} from '~/utils/supplyChainAgentRoles';
import {
	buildAnalysisOutcomeMetrics,
	buildDecisionOutcomeMetrics,
	buildWorkbenchStages,
	countPendingProposals,
	isWorkbenchTaskActive,
	latestNodeLabel,
	workbenchStageStateLabel,
	workbenchStatusLabel,
	workbenchStatusTone,
	type WorkbenchAnalysisReport,
	type WorkbenchTask,
} from '~/utils/supplyChainWorkbench';

const props = withDefaults(
	defineProps<{
		task: WorkbenchTask | null;
		decision?: DecisionTaskResult | null;
		proposals?: DecisionProposal[];
		analysisReport?: WorkbenchAnalysisReport | null;
		syncError?: string;
	}>(),
	{
		decision: null,
		proposals: () => [],
		analysisReport: null,
		syncError: '',
	},
);

const emit = defineEmits<{
	(event: 'open-timeline', target: { taskId: string; status: string }): void;
	(event: 'retry-analysis', question: string): void;
	(event: 'open-workspace', view: 'analysis' | 'decision'): void;
}>();

const POLL_INTERVAL_MS = 1400;
const {
	events,
	loading,
	error: eventsError,
	load: loadEvents,
	retry: retryEventsSource,
	clear: clearEvents,
} = useSupplyChainEvents();

let refreshTimer: ReturnType<typeof setTimeout> | null = null;
let watchedTaskId = '';
let disposed = false;

const isRunning = computed(() => props.task?.status === 'RUNNING');
const isQueued = computed(() => props.task?.status === 'ACCEPTED');
const isCompleted = computed(() => props.task?.status === 'COMPLETED');
const isFailure = computed(() => props.task?.status === 'FAILED');
const statusTone = computed(() => workbenchStatusTone(props.task?.status || ''));
const statusLabel = computed(() => (props.task ? workbenchStatusLabel(props.task.status) : '待命'));
const sourceLabel = computed(() => (props.task?.source === 'DECISION' ? '自然语言决策' : '供应链风险分析'));
const sortedEvents = computed(() => sortEventsBySequence(events.value));
const recentViews = computed(() => sortedEvents.value.slice(-4).map(toTimelineEventView));
const eventCount = computed(() => sortedEvents.value.length);
const signals = computed(() => summarizeTimelineSignals(sortedEvents.value));
const fallbackUsed = computed(
	() => Boolean(props.decision?.fallbackUsed) || props.decision?.narrativeSource === 'DETERMINISTIC_FALLBACK' || signals.value.hasFallback,
);
const fallbackNote = computed(() => {
	if (props.decision?.narrativeSource === 'DETERMINISTIC_FALLBACK') {
		return '规则降级：模型不可用或返回空内容；业务数值仍来自 Java 工具。';
	}
	return '已出现规则降级事件；业务数值仍来自后端确定性工具。';
});
const toolCallCount = computed(
	() => sortedEvents.value.filter((event) => event.type === 'TOOL_COMPLETED').length,
);
const latestEventTime = computed(() => {
	const last = sortedEvents.value[sortedEvents.value.length - 1];
	return last ? formatEventTime(last.occurredAt) : '—';
});
const latestCompletedNode = computed(() => latestNodeLabel(sortedEvents.value));
const highlightEventId = computed(() => {
	if (!isRunning.value) {
		return '';
	}
	const candidate = [...sortedEvents.value]
		.reverse()
		.find((event) => event.type === 'MODEL_COMPLETED' || event.type === 'TOOL_COMPLETED');
	return candidate
		? toTimelineEventView(candidate).id
		: '';
});
const pendingCount = computed(() => countPendingProposals(props.proposals));
const stages = computed(() => buildWorkbenchStages(props.task, sortedEvents.value));
const activeStage = computed(
	() => stages.value.find((stage) => stage.state === 'active') || stages.value.find((stage) => stage.state === 'failed') || null,
);
const currentStageLabel = computed(() => {
	if (!props.task) {
		return '等待任务';
	}
	if (isCompleted.value) {
		return '全部后端阶段已完成';
	}
	if (activeStage.value) {
		return activeStage.value.label;
	}
	const step = props.task.currentStep;
	if (!step || step === 'QUEUE' || step === 'QUEUED') {
		return '等待后端执行';
	}
	return step;
});
const currentStageDescription = computed(() => {
	if (!props.task) {
		return '';
	}
	if (isQueued.value) {
		return '任务已被后端受理，尚未写入节点事件。';
	}
	if (activeStage.value) {
		return '后端任务仍在执行；阶段状态只随真实事件和任务步骤更新。';
	}
	return '任务仍在执行，等待后端写入下一阶段事件。';
});
const failureStageLabel = computed(() => {
	const failed = stages.value.find((stage) => stage.state === 'failed');
	if (failed) {
		return `${failed.label} 阶段失败`;
	}
	return props.task?.currentStep || '后端未提供失败阶段';
});
const analysisReportForTask = computed(() => {
	if (!props.task || props.task.source !== 'ANALYSIS' || !props.analysisReport) {
		return null;
	}
	return props.analysisReport.taskId === props.task.taskId ? props.analysisReport : null;
});
const metrics = computed(() => {
	if (!props.task) {
		return [];
	}
	if (props.task.source === 'DECISION') {
		return buildDecisionOutcomeMetrics(props.decision, props.proposals);
	}
	return buildAnalysisOutcomeMetrics(analysisReportForTask.value);
});
const resultPending = computed(() => {
	if (!props.task || !isCompleted.value) {
		return false;
	}
	return props.task.source === 'DECISION' ? props.decision === null : analysisReportForTask.value === null;
});
const resultTitle = computed(() => {
	if (!props.task) {
		return '';
	}
	if (props.task.source === 'DECISION') {
		return props.decision ? '决策任务结果' : '决策结果读取中';
	}
	return analysisReportForTask.value ? '风险分析结果' : '分析报告读取中';
});
const resultSourceLabel = computed(() => {
	if (props.task?.source === 'ANALYSIS') {
		const report = analysisReportForTask.value;
		return report ? (report.modelAssisted ? '模型辅助解释' : '确定性规则') : '等待报告';
	}
	if (props.decision?.narrativeSource === 'MODEL_GROUNDED') {
		return '模型解释 · 数值来自工具';
	}
	if (props.decision?.narrativeSource === 'DETERMINISTIC_FALLBACK') {
		return '规则降级';
	}
	if (props.decision?.narrativeSource === 'DETERMINISTIC') {
		return '确定性规则';
	}
	return '等待结果';
});
const realSignals = computed(() => {
	const items: { key: string; label: string; icon: string; role: 'planning' | 'risk' | 'failure' | 'data' }[] = [];
	if (signals.value.hasModelCompleted) {
		items.push({ key: 'model-completed', label: '模型调用完成', icon: CHAINPILOT_ROLES.planning.icon, role: 'planning' });
	}
	if (signals.value.hasModelFailed) {
		items.push({ key: 'model-failed', label: '模型调用失败', icon: CHAINPILOT_ROLES.failure.icon, role: 'failure' });
	}
	if (signals.value.hasFallback) {
		items.push({ key: 'fallback', label: '规则降级', icon: 'mdi-swap-horizontal', role: 'risk' });
	}
	if (sortedEvents.value.some((event) => event.type === 'TOOL_FAILED')) {
		items.push({ key: 'tool-failed', label: '工具调用失败', icon: 'mdi-alert-circle-outline', role: 'failure' });
	}
	return items;
});
const capabilityRoles = ROLE_CAPABILITY_ORDER.map((roleId) => CHAINPILOT_ROLES[roleId]);

function stopRefresh() {
	if (refreshTimer) {
		clearTimeout(refreshTimer);
		refreshTimer = null;
	}
}

function scheduleRefresh() {
	if (disposed || refreshTimer || !props.task || !isWorkbenchTaskActive(props.task) || eventsError.value) {
		return;
	}
	refreshTimer = setTimeout(async () => {
		refreshTimer = null;
		if (disposed || !props.task || !isWorkbenchTaskActive(props.task)) {
			return;
		}
		await loadEvents(props.task.taskId);
		if (!eventsError.value) {
			scheduleRefresh();
		}
	}, POLL_INTERVAL_MS);
}

function syncTask() {
	if (disposed) {
		return;
	}
	const task = props.task;
	const nextTaskId = task?.taskId || '';
	if (!nextTaskId) {
		watchedTaskId = '';
		stopRefresh();
		clearEvents();
		return;
	}
	if (nextTaskId !== watchedTaskId) {
		watchedTaskId = nextTaskId;
		stopRefresh();
		clearEvents();
		void loadEvents(nextTaskId).then(() => {
			if (isWorkbenchTaskActive(props.task)) {
				scheduleRefresh();
			}
		});
		return;
	}
	if (isWorkbenchTaskActive(task)) {
		scheduleRefresh();
	} else {
		stopRefresh();
		void loadEvents(nextTaskId);
	}
}

async function retryEvents() {
	const taskId = props.task?.taskId;
	if (!taskId) {
		return;
	}
	await retryEventsSource();
	if (!eventsError.value) {
		scheduleRefresh();
	}
}

function emitTimeline() {
	if (!props.task) {
		return;
	}
	emit('open-timeline', { taskId: props.task.taskId, status: props.task.status });
}

function retryAnalysis() {
	if (props.task?.source === 'ANALYSIS') {
		emit('retry-analysis', props.task.question);
	}
}

function reducedMotion(): boolean {
	return typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches === true;
}

function scrollToId(id: string) {
	if (typeof document === 'undefined') {
		return;
	}
	document.getElementById(id)?.scrollIntoView({
		behavior: reducedMotion() ? 'auto' : 'smooth',
		block: 'start',
	});
}

async function focusResult() {
	if (!props.task) {
		return;
	}
	const view = props.task.source === 'DECISION' ? 'decision' : 'analysis';
	emit('open-workspace', view);
	await nextTick();
	scrollToId(view === 'decision' ? 'decision-result' : 'analysis-report');
}

async function focusProposals() {
	emit('open-workspace', 'decision');
	await nextTick();
	scrollToId('decision-proposals');
}

async function focusInput(target?: 'analysis' | 'decision') {
	if (typeof document === 'undefined') {
		return;
	}
	const source = target || (props.task?.source === 'DECISION' ? 'decision' : 'analysis');
	emit('open-workspace', source);
	await nextTick();
	const id = source === 'decision' ? 'decision-question' : 'analysis-question';
	const element = document.getElementById(id);
	element?.scrollIntoView({ behavior: reducedMotion() ? 'auto' : 'smooth', block: 'center' });
	if (element instanceof HTMLElement) {
		element.focus({ preventScroll: true });
	}
}

watch(
	() => (props.task ? `${props.task.taskId}:${props.task.status}` : ''),
	() => syncTask(),
	{ immediate: true },
);

onBeforeUnmount(() => {
	disposed = true;
	stopRefresh();
	clearEvents();
});
</script>

<style scoped>
.cp-workbench {
	--wb-ink: var(--cp-ink);
	--wb-muted: var(--cp-muted);
	position: relative;
	min-width: 0;
	overflow: hidden;
	margin-bottom: 28px;
	padding: 0 26px 18px;
	color: var(--wb-ink);
	background:
		radial-gradient(ellipse 30rem 15rem at 2% -8%, rgba(219, 237, 247, 0.82), transparent 70%),
		radial-gradient(ellipse 24rem 14rem at 96% 0%, rgba(241, 235, 218, 0.66), transparent 72%),
		linear-gradient(150deg, rgba(255, 255, 255, 0.93), rgba(246, 250, 252, 0.86));
	border: 1px solid rgba(211, 227, 237, 0.86);
	border-radius: var(--cp-radius-lg);
	box-shadow: var(--cp-shadow-soft);
	-webkit-backdrop-filter: blur(14px) saturate(1.06);
	backdrop-filter: blur(14px) saturate(1.06);
	animation: cp-workbench-in var(--cp-duration-slow) var(--cp-ease-out) both;
}

.cp-workbench-topline {
	height: 2px;
	background: linear-gradient(
		90deg,
		rgba(75, 127, 200, 0.52),
		rgba(62, 148, 135, 0.44) 34%,
		rgba(179, 131, 64, 0.38) 52%,
		rgba(121, 107, 187, 0.4) 72%,
		rgba(189, 115, 97, 0.36)
	);
}

.cp-wb-header {
	display: flex;
	align-items: flex-start;
	justify-content: space-between;
	gap: 18px;
	padding: 22px 0 15px;
}

.cp-wb-heading {
	display: flex;
	min-width: 0;
	gap: 14px;
}

.cp-wb-mark {
	position: relative;
	flex: 0 0 auto;
}

.cp-wb-mark-ring {
	position: absolute;
	inset: -5px;
	border: 1px solid rgba(75, 127, 200, 0.18);
	border-radius: 18px;
	opacity: 0;
}

.cp-wb-mark.is-running .cp-wb-mark-ring {
	opacity: 1;
	animation: cp-wb-ring 2.8s var(--cp-ease-soft) infinite;
}

.cp-wb-heading-copy {
	min-width: 0;
}

.cp-wb-kicker {
	color: var(--cp-muted);
	font-size: 13px;
	font-weight: 750;
	letter-spacing: 0.16em;
	line-height: 1.5;
}

.cp-wb-title-row {
	display: flex;
	align-items: center;
	flex-wrap: wrap;
	gap: 8px 12px;
	margin-top: 3px;
}

.cp-wb-title {
	margin: 0;
	color: var(--cp-ink-strong);
	font-size: clamp(19px, 2.1vw, 23px);
	font-weight: 720;
	letter-spacing: -0.03em;
	line-height: 1.3;
}

.cp-wb-subtitle {
	margin: 4px 0 0;
	color: var(--cp-muted);
	font-size: 13px;
	line-height: 1.65;
}

.cp-wb-state {
	display: inline-flex;
	align-items: center;
	gap: 6px;
	padding: 4px 9px;
	color: var(--cp-role-system-ink);
	background: var(--cp-role-system-soft);
	border: 1px solid rgba(111, 129, 151, 0.15);
	border-radius: var(--cp-radius-pill);
	font-size: 13px;
	font-weight: 700;
	line-height: 1.4;
	white-space: nowrap;
}

.cp-wb-state-dot {
	width: 5px;
	height: 5px;
	flex: 0 0 5px;
	border-radius: 50%;
	background: currentColor;
}

.cp-wb-state--active {
	color: var(--cp-role-planning-ink);
	background: var(--cp-role-planning-soft);
	border-color: rgba(75, 127, 200, 0.18);
}

.cp-wb-state--active .cp-wb-state-dot {
	animation: cp-wb-breathe 1.9s var(--cp-ease-soft) infinite;
}

.cp-wb-state--queued {
	color: var(--cp-role-system-ink);
}

.cp-wb-state--success {
	color: var(--cp-role-execution-ink);
	background: var(--cp-role-execution-soft);
	border-color: rgba(66, 135, 102, 0.18);
}

.cp-wb-state--failure {
	color: var(--cp-role-failure-ink);
	background: var(--cp-role-failure-soft);
	border-color: rgba(185, 87, 92, 0.18);
}

.cp-wb-header-actions {
	display: flex;
	align-items: center;
	justify-content: flex-end;
	flex-wrap: wrap;
	gap: 7px;
	flex: 0 0 auto;
}

.cp-wb-source {
	padding: 4px 8px;
	color: var(--cp-ink-2);
	background: rgba(244, 248, 252, 0.82);
	border: 1px solid var(--cp-line-soft);
	border-radius: var(--cp-radius-pill);
	font-size: 13px;
	font-weight: 650;
	white-space: nowrap;
}

.cp-wb-pending-chip {
	display: inline-flex;
	align-items: center;
	gap: 5px;
	padding: 4px 8px;
	color: var(--cp-role-confirmation-ink);
	background: var(--cp-role-confirmation-soft);
	border: 1px solid rgba(189, 115, 97, 0.16);
	border-radius: var(--cp-radius-pill);
	font-size: 13px;
	font-weight: 700;
	white-space: nowrap;
}

.cp-wb-link {
	margin: -4px -2px;
	padding: 4px 2px;
	color: var(--cp-primary-deep);
	font: inherit;
	font-size: 13px;
	font-weight: 650;
	line-height: 1.4;
	background: none;
	border: 0;
	cursor: pointer;
	transition: color var(--cp-duration-fast) var(--cp-ease-out);
}

.cp-wb-link:hover {
	color: var(--cp-primary);
	text-decoration: underline;
}

.cp-wb-taskline {
	display: flex;
	align-items: flex-start;
	justify-content: space-between;
	flex-wrap: wrap;
	gap: 10px 18px;
	padding: 12px 14px;
	background: rgba(249, 252, 255, 0.72);
	border: 1px solid var(--cp-line-soft);
	border-radius: var(--cp-radius-md);
}

.cp-wb-question-block {
	min-width: 0;
	flex: 1 1 320px;
}

.cp-wb-label {
	display: block;
	color: var(--cp-subtle);
	font-size: 13px;
	font-weight: 700;
	letter-spacing: 0.08em;
	line-height: 1.5;
}

.cp-wb-question {
	display: -webkit-box;
	overflow: hidden;
	margin: 3px 0 0;
	color: var(--cp-ink-2);
	font-size: 12px;
	font-weight: 600;
	line-height: 1.65;
	overflow-wrap: anywhere;
	-webkit-box-orient: vertical;
	-webkit-line-clamp: 2;
}

.cp-wb-task-meta {
	display: flex;
	align-items: center;
	flex-wrap: wrap;
	gap: 7px 10px;
	color: var(--cp-subtle);
	font-size: 13px;
}

.cp-wb-task-id {
	font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
	overflow-wrap: anywhere;
}

.cp-wb-task-time {
	white-space: nowrap;
}

.cp-wb-signals {
	display: flex;
	flex-wrap: wrap;
	gap: 6px;
	margin-top: 10px;
}

.cp-wb-signal {
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

.cp-wb-stages {
	display: flex;
	flex-wrap: wrap;
	gap: 7px;
	margin: 14px 0 0;
	padding: 0;
	list-style: none;
}

.cp-wb-stage {
	position: relative;
	display: flex;
	align-items: center;
	min-width: 0;
	flex: 1 1 132px;
	gap: 8px;
	padding: 9px 10px;
	color: var(--cp-subtle);
	background: rgba(249, 252, 255, 0.52);
	border: 1px solid transparent;
	border-radius: var(--cp-radius-sm);
	transition: background-color var(--cp-duration-base) var(--cp-ease-out), border-color var(--cp-duration-base) var(--cp-ease-out), box-shadow var(--cp-duration-base) var(--cp-ease-out);
}

.cp-wb-stage:not(:first-child)::before {
	position: absolute;
	top: 50%;
	left: -8px;
	width: 8px;
	height: 1px;
	background: var(--cp-line);
	content: '';
}

.cp-workbench--active .cp-wb-stage--completed + .cp-wb-stage::before,
.cp-workbench--active .cp-wb-stage--active + .cp-wb-stage::before {
	background-image: linear-gradient(90deg, rgba(75, 127, 200, 0.18), color-mix(in srgb, var(--cp-role-color) 55%, transparent), rgba(75, 127, 200, 0.14));
	background-size: 200% 100%;
	animation: cp-wb-connector 3.6s linear infinite;
}

.cp-wb-stage--retry {
	flex-basis: 156px;
}

.cp-wb-stage-icon {
	position: relative;
	display: grid;
	width: 28px;
	height: 28px;
	flex: 0 0 28px;
	place-items: center;
	color: var(--cp-subtle);
	background: rgba(238, 244, 250, 0.88);
	border: 1px solid var(--cp-line-soft);
	border-radius: 9px;
	transition: color var(--cp-duration-base) var(--cp-ease-out), background-color var(--cp-duration-base) var(--cp-ease-out), border-color var(--cp-duration-base) var(--cp-ease-out);
}

.cp-wb-stage-state-icon {
	position: absolute;
	right: -3px;
	bottom: -3px;
	display: grid;
	width: 13px;
	height: 13px;
	place-items: center;
	color: #fff;
	background: var(--cp-role-color);
	border: 1.5px solid #fff;
	border-radius: 50%;
}

.cp-wb-stage-pulse {
	width: 5px;
	height: 5px;
	border-radius: 50%;
	background: #fff;
	animation: cp-wb-breathe 1.8s var(--cp-ease-soft) infinite;
}

.cp-wb-stage-copy {
	display: flex;
	min-width: 0;
	flex-direction: column;
	gap: 1px;
}

.cp-wb-stage-copy strong {
	color: var(--cp-muted);
	font-size: 13px;
	font-weight: 650;
	line-height: 1.45;
	overflow-wrap: anywhere;
}

.cp-wb-stage-copy small {
	color: var(--cp-subtle);
	font-size: 13px;
	line-height: 1.4;
}

.cp-wb-stage--completed {
	background: color-mix(in srgb, var(--cp-role-soft) 58%, #fff);
	border-color: color-mix(in srgb, var(--cp-role-color) 13%, transparent);
}

.cp-wb-stage--completed .cp-wb-stage-icon {
	color: var(--cp-role-color);
	background: var(--cp-role-soft);
	border-color: color-mix(in srgb, var(--cp-role-color) 18%, transparent);
}

.cp-wb-stage--completed .cp-wb-stage-copy strong {
	color: var(--cp-ink-2);
}

.cp-wb-stage--active {
	background: #fff;
	border-color: color-mix(in srgb, var(--cp-role-color) 32%, transparent);
	box-shadow: 0 6px 16px rgba(31, 58, 88, 0.07);
}

.cp-wb-stage--active .cp-wb-stage-icon {
	color: var(--cp-role-color);
	background: var(--cp-role-soft);
	border-color: color-mix(in srgb, var(--cp-role-color) 22%, transparent);
	animation: cp-wb-breathe-soft 2.4s var(--cp-ease-soft) infinite;
}

.cp-wb-stage--active .cp-wb-stage-copy strong {
	color: var(--cp-role-ink);
	font-weight: 700;
}

.cp-wb-stage--active .cp-wb-stage-copy small {
	color: var(--cp-role-ink);
	font-weight: 650;
}

.cp-wb-stage--failed {
	background: var(--cp-role-failure-soft);
	border-color: rgba(185, 87, 92, 0.25);
}

.cp-wb-stage--failed .cp-wb-stage-icon {
	color: var(--cp-role-failure);
	background: #fff;
	border-color: rgba(185, 87, 92, 0.18);
}

.cp-wb-stage--failed .cp-wb-stage-copy strong,
.cp-wb-stage--failed .cp-wb-stage-copy small {
	color: var(--cp-role-failure-ink);
}

.cp-wb-stage-empty {
	margin: 12px 0 0;
	padding: 10px 12px;
	color: var(--cp-subtle);
	background: rgba(249, 252, 255, 0.64);
	border: 1px dashed var(--cp-line);
	border-radius: var(--cp-radius-sm);
	font-size: 13px;
	line-height: 1.6;
}

.cp-wb-body {
	display: grid;
	grid-template-columns: minmax(0, 1.32fr) minmax(280px, 0.8fr);
	align-items: start;
	gap: 14px;
	margin-top: 15px;
}

.cp-wb-feed,
.cp-wb-outcome {
	min-width: 0;
	padding: 13px 14px;
	background: rgba(250, 253, 255, 0.7);
	border: 1px solid var(--cp-line-soft);
	border-radius: var(--cp-radius-md);
}

.cp-wb-section-head {
	display: flex;
	align-items: center;
	justify-content: space-between;
	flex-wrap: wrap;
	gap: 5px 10px;
	color: var(--cp-muted);
	font-size: 13px;
	font-weight: 700;
	letter-spacing: 0.045em;
	line-height: 1.5;
}

.cp-wb-section-head > span:last-child {
	color: var(--cp-subtle);
	font-weight: 500;
	letter-spacing: 0;
}

.cp-wb-events {
	margin: 8px 0 0;
	padding: 0;
	list-style: none;
}

.cp-wb-event {
	display: grid;
	grid-template-columns: 24px minmax(0, 1fr);
	gap: 8px;
	padding: 8px 4px;
	border-bottom: 1px solid rgba(231, 239, 247, 0.82);
	animation: cp-wb-event-in var(--cp-duration-base) var(--cp-ease-out) both;
}

.cp-wb-event:last-child {
	border-bottom: 0;
}

.cp-wb-event.is-highlight {
	border-radius: 8px;
	animation: cp-wb-event-in var(--cp-duration-base) var(--cp-ease-out) both, cp-wb-event-glow 1.6s var(--cp-ease-out) 1;
}

.cp-wb-event-icon {
	display: grid;
	width: 24px;
	height: 24px;
	place-items: center;
	color: var(--cp-role-color);
	background: var(--cp-role-soft);
	border: 1px solid color-mix(in srgb, var(--cp-role-color) 14%, transparent);
	border-radius: 8px;
}

.cp-wb-event-main {
	min-width: 0;
}

.cp-wb-event-top {
	display: flex;
	align-items: center;
	flex-wrap: wrap;
	gap: 4px 8px;
}

.cp-wb-event-top strong {
	color: var(--cp-ink-2);
	font-size: 13px;
	font-weight: 700;
	line-height: 1.5;
}

.cp-wb-event-role {
	padding: 1px 5px;
	color: var(--cp-role-ink);
	background: var(--cp-role-soft);
	border-radius: 4px;
	font-size: 13px;
	font-weight: 650;
	line-height: 1.5;
	white-space: nowrap;
}

.cp-wb-event-top time {
	margin-left: auto;
	color: var(--cp-subtle);
	font-size: 13px;
	font-variant-numeric: tabular-nums;
	white-space: nowrap;
}

.cp-wb-event-main p {
	margin: 2px 0 0;
	color: var(--cp-muted);
	font-size: 13px;
	line-height: 1.55;
	overflow-wrap: anywhere;
}

.cp-wb-event-meta {
	display: block;
	margin-top: 2px;
	color: var(--cp-subtle);
	font-size: 13px;
	line-height: 1.5;
	overflow-wrap: anywhere;
}

.cp-wb-feed-state {
	display: flex;
	align-items: center;
	gap: 8px;
	min-height: 86px;
	padding: 10px 2px;
	color: var(--cp-subtle);
	font-size: 13px;
	line-height: 1.6;
}

.cp-wb-feed-foot {
	display: flex;
	align-items: center;
	justify-content: space-between;
	flex-wrap: wrap;
	gap: 6px 10px;
	margin-top: 7px;
	padding-top: 8px;
	border-top: 1px solid rgba(231, 239, 247, 0.82);
	color: var(--cp-subtle);
	font-size: 13px;
	line-height: 1.5;
}

.cp-wb-connection {
	display: flex;
	align-items: flex-start;
	gap: 8px;
	margin-top: 9px;
	padding: 10px;
	color: var(--cp-role-risk-ink);
	background: var(--cp-role-risk-soft);
	border: 1px solid rgba(179, 131, 64, 0.18);
	border-radius: 9px;
	font-size: 13px;
	line-height: 1.55;
}

.cp-wb-connection > div {
	display: flex;
	min-width: 0;
	flex: 1;
	flex-direction: column;
	gap: 2px;
}

.cp-wb-connection strong {
	font-size: 13px;
}

.cp-wb-connection span {
	overflow-wrap: anywhere;
}

.cp-wb-connection .cp-wb-link {
	color: var(--cp-role-risk-ink);
	white-space: nowrap;
}

.cp-wb-outcome {
	background:
		linear-gradient(160deg, rgba(255, 255, 255, 0.88), rgba(247, 251, 253, 0.76));
}

.cp-wb-live {
	display: flex;
	flex-direction: column;
	gap: 5px;
}

.cp-wb-live-title {
	color: var(--cp-ink-strong);
	font-size: 14px;
	font-weight: 700;
	line-height: 1.4;
	overflow-wrap: anywhere;
}

.cp-wb-live p {
	margin: 0;
	color: var(--cp-muted);
	font-size: 13px;
	line-height: 1.65;
}

.cp-wb-live-grid {
	display: grid;
	grid-template-columns: repeat(3, minmax(0, 1fr));
	gap: 6px;
	margin-top: 5px;
}

.cp-wb-live-grid > div {
	display: flex;
	min-width: 0;
	flex-direction: column;
	gap: 2px;
	padding: 7px 8px;
	background: rgba(244, 249, 253, 0.82);
	border: 1px solid var(--cp-line-soft);
	border-radius: 8px;
}

.cp-wb-live-grid span {
	color: var(--cp-subtle);
	font-size: 13px;
	line-height: 1.4;
}

.cp-wb-live-grid strong {
	color: var(--cp-ink-2);
	font-size: 13px;
	font-weight: 700;
	font-variant-numeric: tabular-nums;
	line-height: 1.4;
	overflow-wrap: anywhere;
}

.cp-wb-result-head {
	display: flex;
	align-items: flex-start;
	justify-content: space-between;
	gap: 8px;
}

.cp-wb-result-head strong {
	display: block;
	margin-top: 3px;
	color: var(--cp-ink-strong);
	font-size: 13px;
	font-weight: 700;
	line-height: 1.4;
}

.cp-wb-result-source {
	flex: 0 0 auto;
	padding: 3px 7px;
	color: var(--cp-role-planning-ink);
	background: var(--cp-role-planning-soft);
	border-radius: var(--cp-radius-pill);
	font-size: 13px;
	font-weight: 650;
	line-height: 1.5;
	white-space: nowrap;
}

.cp-wb-metrics {
	display: grid;
	grid-template-columns: repeat(2, minmax(0, 1fr));
	gap: 6px;
	margin-top: 10px;
}

.cp-wb-metric {
	display: flex;
	min-width: 0;
	flex-direction: column;
	gap: 2px;
	padding: 8px 9px;
	background: color-mix(in srgb, var(--cp-role-soft) 55%, #fff);
	border: 1px solid color-mix(in srgb, var(--cp-role-color) 12%, transparent);
	border-radius: 9px;
}

.cp-wb-metric span {
	color: var(--cp-role-ink);
	font-size: 13px;
	font-weight: 600;
	line-height: 1.4;
}

.cp-wb-metric strong {
	color: var(--cp-ink-2);
	font-size: 13px;
	font-weight: 700;
	line-height: 1.5;
	font-variant-numeric: tabular-nums;
	overflow-wrap: anywhere;
}

.cp-wb-muted {
	margin: 10px 0 0;
	color: var(--cp-subtle);
	font-size: 13px;
	line-height: 1.6;
}

.cp-wb-pending {
	margin-bottom: 10px;
	padding: 11px 12px;
	background: color-mix(in srgb, var(--cp-role-soft) 82%, #fff);
	border: 1px solid color-mix(in srgb, var(--cp-role-color) 20%, transparent);
	border-radius: 10px;
}

.cp-wb-pending-kicker {
	display: inline-flex;
	align-items: center;
	gap: 4px;
	color: var(--cp-role-ink);
	font-size: 13px;
	font-weight: 700;
	letter-spacing: 0.04em;
	line-height: 1.5;
}

.cp-wb-pending > strong {
	display: block;
	margin-top: 4px;
	color: var(--cp-role-ink);
	font-size: 12px;
	font-weight: 750;
	line-height: 1.45;
}

.cp-wb-pending > p {
	margin: 4px 0 8px;
	color: color-mix(in srgb, var(--cp-role-ink) 84%, #6b7584);
	font-size: 13px;
	line-height: 1.6;
}

.cp-wb-failure {
	padding: 11px 12px;
	background: var(--cp-role-failure-soft);
	border: 1px solid rgba(185, 87, 92, 0.18);
	border-radius: 10px;
}

.cp-wb-failure-head {
	display: flex;
	align-items: flex-start;
	gap: 8px;
}

.cp-wb-failure-icon {
	display: grid;
	width: 28px;
	height: 28px;
	flex: 0 0 28px;
	place-items: center;
	color: var(--cp-role-failure);
	background: #fff;
	border-radius: 8px;
}

.cp-wb-failure-head > div {
	display: flex;
	min-width: 0;
	flex-direction: column;
	gap: 1px;
}

.cp-wb-failure-head strong {
	color: var(--cp-role-failure-ink);
	font-size: 13px;
	font-weight: 750;
	line-height: 1.45;
}

.cp-wb-failure-head span {
	color: color-mix(in srgb, var(--cp-role-failure-ink) 78%, #7b8291);
	font-size: 13px;
	line-height: 1.5;
	overflow-wrap: anywhere;
}

.cp-wb-failure-message {
	margin: 7px 0 0;
	color: #8a5d62;
	font-size: 13px;
	line-height: 1.65;
	overflow-wrap: anywhere;
}

.cp-wb-actions {
	display: flex;
	flex-wrap: wrap;
	gap: 6px;
	margin-top: 10px;
}

.cp-wb-action {
	min-height: 31px;
	padding: 6px 10px;
	color: var(--cp-ink-2);
	background: rgba(255, 255, 255, 0.82);
	border: 1px solid var(--cp-line);
	border-radius: 8px;
	font: inherit;
	font-size: 13px;
	font-weight: 650;
	line-height: 1.4;
	cursor: pointer;
	transition: border-color var(--cp-duration-fast) var(--cp-ease-out), background-color var(--cp-duration-fast) var(--cp-ease-out), color var(--cp-duration-fast) var(--cp-ease-out), box-shadow var(--cp-duration-fast) var(--cp-ease-out);
}

.cp-wb-action:hover {
	color: var(--cp-primary-deep);
	background: #fff;
	border-color: rgba(75, 127, 200, 0.28);
	box-shadow: 0 3px 8px rgba(31, 58, 88, 0.05);
}

.cp-wb-action--primary {
	color: #fff;
	background: linear-gradient(135deg, #5588cf, #4274b5);
	border-color: transparent;
	box-shadow: 0 5px 12px rgba(75, 127, 200, 0.18);
}

.cp-wb-action--primary:hover {
	color: #fff;
	background: linear-gradient(135deg, #4c7ec2, #3a69a7);
	border-color: transparent;
}

.cp-wb-action--confirm {
	color: var(--cp-role-confirmation-ink);
	background: #fff;
	border-color: rgba(189, 115, 97, 0.28);
}

.cp-wb-action--confirm:hover {
	color: var(--cp-role-confirmation-ink);
	border-color: rgba(189, 115, 97, 0.42);
}

.cp-wb-fallback {
	display: flex;
	align-items: flex-start;
	gap: 6px;
	margin-top: 10px;
	padding: 8px 9px;
	color: var(--cp-role-risk-ink);
	background: var(--cp-role-risk-soft);
	border: 1px solid rgba(179, 131, 64, 0.14);
	border-radius: 8px;
	font-size: 13px;
	line-height: 1.55;
}

.cp-wb-fallback span {
	overflow-wrap: anywhere;
}

.cp-wb-sync-error {
	display: flex;
	align-items: flex-start;
	gap: 7px;
	margin-top: 10px;
	padding: 8px 9px;
	color: var(--cp-role-risk-ink);
	background: rgba(251, 243, 228, 0.72);
	border: 1px solid rgba(179, 131, 64, 0.16);
	border-radius: 8px;
	font-size: 13px;
	line-height: 1.55;
}

.cp-wb-sync-error > div {
	display: flex;
	min-width: 0;
	flex-direction: column;
	gap: 1px;
}

.cp-wb-sync-error strong {
	font-size: 13px;
}

.cp-wb-sync-error span,
.cp-wb-sync-error small {
	overflow-wrap: anywhere;
}

.cp-wb-sync-error small {
	color: color-mix(in srgb, var(--cp-role-risk-ink) 74%, #8a8f99);
	font-size: 13px;
}

.cp-wb-footer {
	display: flex;
	align-items: center;
	justify-content: space-between;
	flex-wrap: wrap;
	gap: 6px 14px;
	margin-top: 10px;
	padding-top: 10px;
	border-top: 1px solid var(--cp-line-soft);
	color: var(--cp-subtle);
	font-size: 13px;
	line-height: 1.55;
}

.cp-wb-footer > span {
	display: inline-flex;
	align-items: center;
	gap: 4px;
	min-width: 0;
	overflow-wrap: anywhere;
}

.cp-wb-idle {
	display: grid;
	grid-template-columns: minmax(0, 1.25fr) minmax(260px, 0.75fr);
	align-items: center;
	gap: 22px;
	padding: 8px 0 6px;
}

.cp-wb-idle-copy {
	min-width: 0;
}

.cp-wb-idle-copy > strong {
	display: block;
	margin-top: 4px;
	color: var(--cp-ink-strong);
	font-size: 16px;
	font-weight: 700;
	line-height: 1.45;
}

.cp-wb-idle-copy > p {
	max-width: 690px;
	margin: 5px 0 0;
	color: var(--cp-muted);
	font-size: 13px;
	line-height: 1.7;
}

.cp-wb-idle-actions {
	display: flex;
	flex-wrap: wrap;
	gap: 7px;
	margin-top: 12px;
}

.cp-wb-capabilities {
	min-width: 0;
	padding: 12px;
	background: rgba(250, 253, 255, 0.66);
	border: 1px solid var(--cp-line-soft);
	border-radius: var(--cp-radius-md);
}

.cp-wb-capabilities-title {
	color: var(--cp-subtle);
	font-size: 13px;
	font-weight: 700;
	letter-spacing: 0.06em;
	line-height: 1.5;
}

.cp-wb-capability-list {
	display: flex;
	flex-wrap: wrap;
	gap: 5px;
	margin-top: 7px;
}

.cp-wb-capability {
	display: inline-flex;
	align-items: center;
	gap: 4px;
	padding: 4px 7px;
	color: var(--cp-role-ink);
	background: var(--cp-role-soft);
	border: 1px solid color-mix(in srgb, var(--cp-role-color) 13%, transparent);
	border-radius: var(--cp-radius-pill);
	font-size: 13px;
	font-weight: 650;
	line-height: 1.5;
	white-space: nowrap;
}

@keyframes cp-workbench-in {
	from {
		opacity: 0;
		transform: translateY(8px);
	}
	to {
		opacity: 1;
		transform: translateY(0);
	}
}

@keyframes cp-wb-ring {
	0%,
	100% {
		transform: scale(0.97);
		opacity: 0.45;
	}
	50% {
		transform: scale(1.04);
		opacity: 0.9;
	}
}

@keyframes cp-wb-breathe {
	0%,
	100% {
		opacity: 0.55;
		transform: scale(0.88);
	}
	50% {
		opacity: 1;
		transform: scale(1.08);
	}
}

@keyframes cp-wb-breathe-soft {
	0%,
	100% {
		box-shadow: 0 0 0 0 rgba(75, 127, 200, 0);
	}
	50% {
		box-shadow: 0 0 0 3px rgba(75, 127, 200, 0.06);
	}
}

@keyframes cp-wb-event-in {
	from {
		opacity: 0;
		transform: translateY(3px);
	}
	to {
		opacity: 1;
		transform: translateY(0);
	}
}

@keyframes cp-wb-event-glow {
	0% {
		background: rgba(234, 242, 253, 0.72);
	}
	100% {
		background: transparent;
	}
}

@keyframes cp-wb-connector {
	from {
		background-position: 0% 50%;
	}
	to {
		background-position: 200% 50%;
	}
}

@media (max-width: 1024px) {
	.cp-workbench {
		padding-inline: 20px;
	}

	.cp-wb-body,
	.cp-wb-idle {
		grid-template-columns: 1fr;
	}

	.cp-wb-stage:not(:first-child)::before {
		display: none;
	}
}

@media (max-width: 720px) {
	.cp-workbench {
		padding: 0 16px 15px;
	}

	.cp-wb-header {
		flex-direction: column;
		gap: 10px;
		padding-top: 18px;
	}

	.cp-wb-header-actions {
		justify-content: flex-start;
	}

	.cp-wb-taskline {
		flex-direction: column;
	}

	.cp-wb-question-block {
		flex-basis: auto;
	}

	.cp-wb-task-meta {
		width: 100%;
	}

	.cp-wb-state {
		font-size: 13px;
	}

	.cp-wb-stage {
		flex: 1 1 calc(50% - 4px);
	}

	.cp-wb-metrics,
	.cp-wb-live-grid {
		grid-template-columns: repeat(2, minmax(0, 1fr));
	}

	.cp-wb-live-grid > div:last-child {
		grid-column: 1 / -1;
	}
}

@media (max-width: 420px) {
	.cp-workbench {
		padding-inline: 13px;
	}

	.cp-wb-heading {
		gap: 10px;
	}

	.cp-wb-title {
		font-size: 18px;
	}

	.cp-wb-subtitle {
		font-size: 13px;
	}

	.cp-wb-stage {
		flex-basis: 100%;
	}

	.cp-wb-metrics,
	.cp-wb-live-grid {
		grid-template-columns: 1fr;
	}

	.cp-wb-live-grid > div:last-child {
		grid-column: auto;
	}

	.cp-wb-action {
		flex: 1 1 100%;
		justify-content: center;
		text-align: center;
	}

	.cp-wb-footer {
		align-items: flex-start;
		flex-direction: column;
	}
}

@media (prefers-reduced-transparency: reduce) {
	.cp-workbench {
		background: #fff;
		-webkit-backdrop-filter: none;
		backdrop-filter: none;
	}
}

@media (prefers-reduced-motion: reduce) {
	.cp-workbench,
	.cp-wb-mark.is-running .cp-wb-mark-ring,
	.cp-wb-state--active .cp-wb-state-dot,
	.cp-wb-stage--active .cp-wb-stage-icon,
	.cp-wb-stage-pulse,
	.cp-wb-stage:not(:first-child)::before,
	.cp-wb-event,
	.cp-wb-event.is-highlight {
		animation: none;
	}

	.cp-wb-event.is-highlight {
		background: rgba(234, 242, 253, 0.45);
	}
}
</style>
