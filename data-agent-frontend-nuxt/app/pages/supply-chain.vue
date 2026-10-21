<template>
	<v-container fluid class="supply-chain-page pa-4 pa-md-8">
		<header class="workspace-header mb-7">
			<div class="workspace-heading">
				<div class="eyebrow"><span class="eyebrow-mark"><v-icon icon="mdi-chart-timeline-variant" size="15" /></span> ChainPilot · 供应链工作区</div>
				<h1 class="page-title">{{ workspacePageCopy.title }}</h1>
				<p class="page-description">{{ workspacePageCopy.description }}</p>
			</div>
			<div class="header-actions">
				<div class="data-status"><span class="status-dot" :class="{ 'status-dot--loading': loadingData, 'status-dot--waiting': !loadingData && !dataLoaded }" />{{ loadingData ? '正在加载数据' : dataLoaded ? '模拟数据已就绪' : '等待数据' }}</div>
				<v-btn class="refresh-button" prepend-icon="mdi-refresh" :loading="loadingData" :disabled="analyzing" @click="loadData">刷新数据</v-btn>
			</div>
		</header>

		<v-alert v-if="pageError" type="error" variant="tonal" class="feedback-alert mb-5" closable @click:close="pageError = ''">
			{{ pageError }}
		</v-alert>

		<nav class="workspace-view-nav" aria-label="供应链工作区导航">
			<button
				v-for="view in WORKSPACE_VIEWS"
				:key="view.key"
				type="button"
				class="workspace-view-tab"
				:class="{ 'is-active': workspaceView === view.key }"
				:style="roleCssVars(view.role)"
				:aria-current="workspaceView === view.key ? 'page' : undefined"
				@click="selectWorkspaceView(view.key)"
			>
				<span class="workspace-view-tab__icon"><v-icon :icon="view.icon" size="17" /></span>
				<span class="workspace-view-tab__copy"><strong>{{ view.label }}</strong><small>{{ view.hint }}</small></span>
				<span v-if="view.key === 'history' && historyTotalElements > 0" class="workspace-view-tab__count">{{ historyTotalElements }}</span>
			</button>
		</nav>

		<section v-show="workspaceView === 'analysis'" class="workspace-view">
		<section class="metrics-grid mb-7" aria-label="供应链概览">
			<article class="metric-card">
				<div class="metric-icon metric-icon--data"><v-icon icon="mdi-package-variant-closed" size="19" /></div>
				<div class="metric-copy"><span class="metric-label">监控订单</span><strong class="metric-value">{{ loadingData && orders.length === 0 ? '—' : orders.length }}</strong><span class="metric-note">当前样例订单总数</span></div>
				<span class="metric-index">01</span>
			</article>
			<article class="metric-card">
				<div class="metric-icon metric-icon--high"><v-icon icon="mdi-alert-outline" size="19" /></div>
				<div class="metric-copy"><span class="metric-label">待关注风险</span><strong class="metric-value">{{ loadingData && orders.length === 0 ? '—' : atRiskOrders.length }}</strong><span class="metric-note">中高风险订单</span></div>
				<span class="metric-index">02</span>
			</article>
			<article class="metric-card">
				<div class="metric-icon metric-icon--medium"><v-icon icon="mdi-truck-check-outline" size="19" /></div>
				<div class="metric-copy"><span class="metric-label">关注供应商</span><strong class="metric-value">{{ loadingData && suppliers.length === 0 ? '—' : riskySuppliers.length }}</strong><span class="metric-note">履约表现需留意</span></div>
				<span class="metric-index">03</span>
			</article>
		</section>

		<section class="primary-grid mb-7">
			<article class="surface-panel risk-panel">
				<div class="panel-heading">
					<div><div class="section-kicker">ORDER MONITOR</div><h2 class="panel-title">订单风险清单</h2><p class="panel-subtitle">优先查看可能影响履约的订单</p></div>
					<v-select v-model="riskFilter" :items="riskFilters" item-title="title" item-value="value" label="风险等级" density="compact" variant="outlined" hide-details class="filter-select" />
				</div>
				<div class="table-wrap">
					<v-table class="data-table desktop-data-table" density="comfortable">
						<thead><tr><th>订单 / SKU</th><th>预计交付</th><th>风险等级</th><th>评分</th><th>库存缺口</th></tr></thead>
						<tbody>
							<tr v-for="item in filteredOrders" :key="item.order.orderId">
								<td><strong class="table-primary">{{ item.order.orderId }}</strong><span class="table-secondary">{{ item.order.sku }}</span></td>
								<td><strong class="table-primary">{{ item.order.estimatedDeliveryDate }}</strong><span class="table-secondary">要求 {{ item.order.requestedDeliveryDate }}</span></td>
								<td><v-chip size="small" :color="riskColor(item.riskLevel)" variant="tonal" class="risk-chip">{{ riskLabel(item.riskLevel) }}</v-chip></td>
								<td><strong class="score-value">{{ item.riskScore }}</strong><span class="table-secondary">/ 100</span></td>
								<td><span :class="item.inventoryShortage > 0 ? 'shortage-value' : 'neutral-value'">{{ item.inventoryShortage }}</span></td>
							</tr>
							<tr v-if="loadingData && orders.length === 0"><td colspan="5" class="empty-cell"><v-progress-circular indeterminate size="22" width="2" color="primary" /><span>正在加载订单数据…</span></td></tr>
							<tr v-else-if="filteredOrders.length === 0"><td colspan="5" class="empty-cell"><v-icon icon="mdi-inbox-outline" size="22" /><span>{{ orders.length ? '当前筛选条件下没有订单' : '暂无订单数据，请刷新或检查服务连接' }}</span><v-btn v-if="!orders.length" size="small" variant="text" color="primary" :disabled="loadingData" @click="loadData">重新加载</v-btn></td></tr>
						</tbody>
					</v-table>
					<div class="mobile-data-list order-mobile-list">
						<article v-for="item in filteredOrders" :key="item.order.orderId" class="mobile-data-card">
							<div class="mobile-card-heading"><div><strong>{{ item.order.orderId }}</strong><span>{{ item.order.sku }}</span></div><v-chip size="small" :color="riskColor(item.riskLevel)" variant="tonal" class="risk-chip">{{ riskLabel(item.riskLevel) }}</v-chip></div>
							<div class="mobile-delivery"><span>预计交付 <strong>{{ item.order.estimatedDeliveryDate }}</strong></span><span>要求 {{ item.order.requestedDeliveryDate }}</span></div>
							<div class="mobile-stats"><div><span>风险评分</span><strong>{{ item.riskScore }}<small>/ 100</small></strong></div><div><span>库存缺口</span><strong :class="item.inventoryShortage > 0 ? 'shortage-value' : 'neutral-value'">{{ item.inventoryShortage }}</strong></div></div>
						</article>
						<div v-if="loadingData && orders.length === 0" class="mobile-empty"><v-progress-circular indeterminate size="20" width="2" color="primary" />正在加载订单数据…</div>
						<div v-else-if="filteredOrders.length === 0" class="mobile-empty"><v-icon icon="mdi-inbox-outline" size="20" />{{ orders.length ? '当前筛选条件下没有订单' : '暂无订单数据' }}<v-btn v-if="!orders.length" size="small" variant="text" color="primary" :disabled="loadingData" @click="loadData">重新加载</v-btn></div>
					</div>
				</div>
				<div class="panel-footnote"><span class="footnote-dot" />风险评分结合交付日期、库存可用量与供应商履约表现</div>
			</article>

			<div class="agent-column">
				<article class="surface-panel agent-panel">
					<div class="agent-panel-top"><div class="agent-emblem"><v-icon icon="mdi-compass-outline" size="20" /></div><div><div class="section-kicker">CHAINPILOT ASK</div><h2 class="panel-title">发起风险分析</h2></div><span class="agent-mode" :class="`agent-mode--${analysisMode.tone}`" role="status" aria-live="polite"><span aria-hidden="true" />{{ analysisMode.label }}</span></div>
					<p class="panel-subtitle agent-description">描述你关心的问题，Agent 将查询业务数据、检索履约规则并生成带依据的建议。</p>
					<label class="field-label" for="analysis-question">分析任务</label>
					<v-textarea id="analysis-question" v-model="question" class="question-field" variant="outlined" rows="3" auto-grow hide-details="auto" :disabled="analyzing" maxlength="2000" placeholder="例如：分析今天可能延期的订单，结合库存和供应商风险给出建议" />
					<div class="agent-actions"><span class="helper-text">支持订单、库存与供应商联合分析</span><v-btn class="run-button" color="primary" prepend-icon="mdi-play" :loading="analyzing" :disabled="!question.trim() || analyzing" @click="runAnalysis">{{ analyzing ? '分析中' : '开始分析' }}</v-btn></div>
					<div v-if="activeTask && analyzing" class="agent-live" role="status">
						<span class="agent-live-icon"><v-icon icon="mdi-progress-clock" size="17" /></span>
						<div class="agent-live-copy"><strong>工作台正在跟踪真实执行</strong><span>{{ activeTask.currentStep || '任务已进入队列' }} · {{ taskStatusLabel(activeTask.status) }}</span></div>
						<button type="button" class="trail-link" @click="openTimeline(activeTask)">执行轨迹</button>
					</div>
					<div v-else-if="analysisError" class="agent-live agent-live--failed" role="alert">
						<span class="agent-live-icon"><v-icon icon="mdi-alert-circle-outline" size="17" /></span>
						<div class="agent-live-copy"><strong>{{ activeTask?.status === 'COMPLETED' ? '结果读取出现问题' : '本次分析未能完成' }}</strong><span>{{ analysisError }}</span></div>
						<div class="agent-live-actions">
							<button type="button" class="trail-link" :disabled="!question.trim()" @click="runAnalysis">重新尝试</button>
							<button v-if="activeTask" type="button" class="trail-link" @click="openTimeline(activeTask)">执行轨迹</button>
						</div>
					</div>
					<div v-else class="workflow-hint"><div class="workflow-hint-icon"><v-icon icon="mdi-shield-check-outline" size="19" /></div><div><strong>建议有据可查</strong><span>分析会关联订单、库存和供应商数据，并附上 SOP 来源。</span></div></div>
				</article>

			</div>
		</section>

		<SupplyChainAgentWorkbench
			:task="workbenchTask"
			:decision="workbenchDecision"
			:proposals="workbenchProposals"
			:analysis-report="workbenchReport"
			:sync-error="workbenchSyncError"
			@open-timeline="openTimeline"
			@retry-analysis="retryAnalysis"
			@open-workspace="selectWorkspaceView"
		/>

		<section v-if="report" id="analysis-report" class="surface-panel report-panel mb-7">
			<div class="report-header"><div class="report-heading"><div class="report-icon"><v-icon icon="mdi-file-document-check-outline" size="21" /></div><div><div class="section-kicker">ANALYSIS REPORT</div><h2 class="panel-title">风险分析报告</h2></div></div><div class="report-badges"><v-chip size="small" color="success" variant="tonal" prepend-icon="mdi-check-circle-outline">{{ taskStatusLabel(report.status) }}</v-chip><v-chip size="small" variant="outlined">{{ report.modelAssisted ? '模型辅助' : '规则降级' }}</v-chip></div></div>
			<div class="report-summary">{{ report.summary }}</div>
			<div class="report-meta">任务 {{ report.taskId }} <span /> 校验重试 {{ report.validationAttempts }} 次<button type="button" class="trail-link report-trail-link" @click="openTimeline({ taskId: report.taskId, status: report.status })">查看执行轨迹</button></div>
			<div v-if="report.atRiskOrders.length" class="report-orders">
				<article v-for="item in report.atRiskOrders" :key="item.order.orderId" class="report-order">
					<div class="report-order-heading"><div><strong>{{ item.order.orderId }}</strong><span>{{ item.order.sku }}</span></div><v-chip size="small" :color="riskColor(item.riskLevel)" variant="tonal" class="risk-chip">{{ riskLabel(item.riskLevel) }} · {{ item.riskScore }}</v-chip></div>
					<ul class="factor-list"><li v-for="factor in item.riskFactors" :key="factor">{{ factor }}</li></ul>
					<div class="recommendation"><v-icon icon="mdi-lightbulb-outline" size="16" /><span><strong>建议</strong>{{ item.recommendedActions.join('；') }}</span></div>
				</article>
			</div>
			<div v-else class="report-empty"><v-icon icon="mdi-check-decagram-outline" size="20" />当前分析未发现需要关注的风险订单。</div>
			<div v-if="report.citations.length" class="citation-section"><div class="citation-heading"><v-icon icon="mdi-book-open-page-variant-outline" size="18" /><h3>SOP 来源</h3><span>引用 {{ report.citations.length }} 条</span></div><v-expansion-panels class="citation-panels" variant="accordion"><v-expansion-panel v-for="citation in report.citations" :key="citation.sourceId" :title="citation.title"><template #text><div class="citation-text"><span>{{ citation.sourceId }}</span>{{ citation.excerpt }}</div></template></v-expansion-panel></v-expansion-panels></div>
			<div v-else class="citation-empty"><v-icon icon="mdi-book-outline" size="17" />本次分析未返回 SOP 引用。</div>
		</section>
		</section>

		<section v-show="workspaceView === 'decision'" class="workspace-view">
			<SupplyChainDecisionWorkspace @open-timeline="openTimeline" @proposal-confirmed="handleProposalConfirmed" @state-change="decisionState = $event" />
		</section>

		<section v-show="workspaceView === 'disposal'" class="workspace-view">
			<SupplyChainDisposalWorkspace
				ref="disposalWorkspaceRef"
				:inventory="inventory"
				:suppliers="suppliers"
				:task-id="report?.taskId ?? null"
				:loading-context="loadingData"
			/>
			<CollapsibleSection
				v-model="baselineExpanded"
				class="baseline-collapsible"
				title="业务基线数据"
				icon="mdi-database-outline"
				:summary="`库存 ${inventory.length} 项 · 供应商 ${suppliers.length} 家`"
			>
				<section class="secondary-grid secondary-grid--nested">
					<article class="surface-panel data-panel">
						<div class="panel-heading panel-heading--compact"><div><h2 class="panel-title">库存可承诺量</h2></div><span class="count-badge">{{ inventory.length }} SKU</span></div>
						<div class="table-wrap"><v-table class="data-table data-table--compact desktop-data-table" density="comfortable"><thead><tr><th>SKU / 产品</th><th>现有库存</th><th>已预留</th><th>安全库存</th><th>可承诺</th></tr></thead><tbody>
							<tr v-for="item in inventory" :key="item.sku"><td><strong class="table-primary">{{ item.sku }}</strong><span class="table-secondary">{{ item.productName }}</span></td><td>{{ item.onHandQuantity }}</td><td>{{ item.reservedQuantity }}</td><td>{{ item.safetyStock }}</td><td><strong :class="item.availableToPromise < item.safetyStock ? 'shortage-value' : 'available-value'">{{ item.availableToPromise }}</strong></td></tr>
							<tr v-if="loadingData && inventory.length === 0"><td colspan="5" class="empty-cell"><v-progress-circular indeterminate size="20" width="2" color="primary" /><span>正在加载库存…</span></td></tr><tr v-else-if="inventory.length === 0"><td colspan="5" class="empty-cell"><v-icon icon="mdi-inbox-outline" size="20" /><span>暂无库存数据</span></td></tr>
						</tbody></v-table><div class="mobile-data-list">
							<article v-for="item in inventory" :key="item.sku" class="mobile-data-card"><div class="mobile-card-heading"><div><strong>{{ item.sku }}</strong><span>{{ item.productName }}</span></div></div><div class="mobile-stats mobile-stats--four"><div><span>现有库存</span><strong>{{ item.onHandQuantity }}</strong></div><div><span>已预留</span><strong>{{ item.reservedQuantity }}</strong></div><div><span>安全库存</span><strong>{{ item.safetyStock }}</strong></div><div><span>可承诺</span><strong :class="item.availableToPromise < item.safetyStock ? 'shortage-value' : 'available-value'">{{ item.availableToPromise }}</strong></div></div></article>
							<div v-if="loadingData && inventory.length === 0" class="mobile-empty"><v-progress-circular indeterminate size="20" width="2" color="primary" />正在加载库存…</div><div v-else-if="inventory.length === 0" class="mobile-empty"><v-icon icon="mdi-inbox-outline" size="20" />暂无库存数据</div>
						</div></div>
					</article>
					<article class="surface-panel data-panel">
						<div class="panel-heading panel-heading--compact"><div><h2 class="panel-title">供应商履约风险</h2></div><span class="count-badge">{{ suppliers.length }} 家</span></div>
						<div class="table-wrap"><v-table class="data-table data-table--compact desktop-data-table" density="comfortable"><thead><tr><th>供应商</th><th>风险</th><th>准时率</th><th>缺陷率</th></tr></thead><tbody>
							<tr v-for="item in suppliers" :key="item.supplierId"><td><strong class="table-primary">{{ item.name }}</strong><span class="table-secondary">{{ item.supplierId }} · {{ item.latestIncident }}</span></td><td><v-chip size="small" :color="riskColor(item.riskLevel)" variant="tonal" class="risk-chip">{{ riskLabel(item.riskLevel) }}</v-chip></td><td>{{ Math.round(item.onTimeDeliveryRate * 100) }}%</td><td>{{ (item.defectRate * 100).toFixed(1) }}%</td></tr>
							<tr v-if="loadingData && suppliers.length === 0"><td colspan="4" class="empty-cell"><v-progress-circular indeterminate size="20" width="2" color="primary" /><span>正在加载供应商…</span></td></tr><tr v-else-if="suppliers.length === 0"><td colspan="4" class="empty-cell"><v-icon icon="mdi-inbox-outline" size="20" /><span>暂无供应商数据</span></td></tr>
						</tbody></v-table><div class="mobile-data-list">
							<article v-for="item in suppliers" :key="item.supplierId" class="mobile-data-card"><div class="mobile-card-heading"><div><strong>{{ item.name }}</strong><span>{{ item.supplierId }}</span></div><v-chip size="small" :color="riskColor(item.riskLevel)" variant="tonal" class="risk-chip">{{ riskLabel(item.riskLevel) }}</v-chip></div><div class="mobile-supplier-incident">{{ item.latestIncident }}</div><div class="mobile-stats"><div><span>准时率</span><strong>{{ Math.round(item.onTimeDeliveryRate * 100) }}%</strong></div><div><span>缺陷率</span><strong>{{ (item.defectRate * 100).toFixed(1) }}%</strong></div></div></article>
							<div v-if="loadingData && suppliers.length === 0" class="mobile-empty"><v-progress-circular indeterminate size="20" width="2" color="primary" />正在加载供应商…</div><div v-else-if="suppliers.length === 0" class="mobile-empty"><v-icon icon="mdi-inbox-outline" size="20" />暂无供应商数据</div>
						</div></div>
					</article>
				</section>
			</CollapsibleSection>
		</section>

		<section v-show="workspaceView === 'history'" class="workspace-view" aria-label="分析任务历史">
			<CollapsibleSection
				v-model="historyExpanded"
				class="history-collapsible"
				title="分析历史"
				icon="mdi-history"
				:summary="historySummary"
			>
				<div class="history-toolbar history-toolbar--inline">
					<v-select :model-value="historyStatus" :items="HISTORY_STATUS_OPTIONS" item-title="title" item-value="value" aria-label="状态筛选" density="compact" variant="outlined" hide-details class="history-status-select" @update:model-value="setHistoryStatus" />
					<v-btn icon="mdi-refresh" size="small" variant="text" color="primary" aria-label="刷新任务历史" :loading="loadingHistory" :disabled="loadingReport" @click="loadTasks" />
				</div>
				<v-progress-linear v-if="loadingHistory || loadingReport" indeterminate color="primary" height="2" />
				<div class="history-list history-list--full">
					<article v-for="task in recentTasks" :key="task.taskId" class="history-item" :class="{ 'history-item--current': task.taskId === activeTask?.taskId || task.taskId === report?.taskId }">
						<button type="button" class="history-item-main" :disabled="loadingReport || analyzing" @click="openTask(task)">
							<div class="history-item-top">
								<v-chip size="small" :color="taskStatusColor(task.status)" variant="tonal">{{ taskStatusLabel(task.status) }}</v-chip>
								<span class="history-time">{{ formatTaskTime(task.updatedAt) }}</span>
							</div>
							<p class="history-question">{{ task.question }}</p>
							<div class="history-item-foot">
								<span>{{ task.currentStep }}</span>
								<span v-if="task.status === 'COMPLETED'" class="history-open">查看报告</span>
								<span v-else-if="task.status === 'FAILED'" class="history-open history-open--error">查看原因</span>
								<span v-else class="history-open">继续跟踪</span>
							</div>
						</button>
						<div class="history-item-actions">
							<span class="history-task-id">任务 {{ task.taskId }}</span>
							<button type="button" class="history-trail-link" :class="{ 'history-trail-link--active': timelineTarget?.taskId === task.taskId }" @click="openTimeline(task)">执行轨迹</button>
						</div>
					</article>
					<div v-if="historyError" class="history-empty history-empty--error">{{ historyError }}</div>
					<div v-else-if="loadingHistory && recentTasks.length === 0" class="history-empty">正在加载任务历史…</div>
					<div v-else-if="recentTasks.length === 0" class="history-empty history-empty--full">
						{{ historyTotalElements > 0 ? '当前页没有任务，请返回上一页。' : historyStatus === 'ALL' ? '还没有分析任务，回到「供应链分析」提交一次分析后会保存在这里。' : '当前状态下没有分析任务。' }}
					</div>
				</div>
				<div class="history-pagination">
					<span v-if="historyError" class="history-range">分页信息不可用</span>
					<span v-else-if="historyTotalPages > 0" class="history-range">共 {{ historyTotalElements }} 条 · 第 {{ historyPage }} / {{ historyTotalPages }} 页</span>
					<span v-else-if="historyLoaded && !loadingHistory" class="history-range">共 0 条</span>
					<span v-else class="history-range">正在加载分页信息…</span>
					<div class="history-pagination-controls">
						<v-select :model-value="historyPageSize" :items="HISTORY_PAGE_SIZE_OPTIONS" item-title="title" item-value="value" aria-label="每页条数" density="compact" variant="plain" hide-details class="history-page-size" @update:model-value="setHistoryPageSize" />
						<v-btn icon="mdi-chevron-left" size="small" variant="text" color="primary" aria-label="上一页" :disabled="!historyCanPrevious" @click="previousHistoryPage" />
						<v-btn icon="mdi-chevron-right" size="small" variant="text" color="primary" aria-label="下一页" :disabled="!historyCanNext" @click="nextHistoryPage" />
					</div>
				</div>
			</CollapsibleSection>
		</section>

		<SupplyChainEventTimeline
			v-if="timelineTarget"
			:task-id="timelineTarget.taskId"
			:task-status="timelineStatus"
			:events="timelineEvents"
			:loading="timelineLoading"
			:error="timelineError"
			@retry="refreshTimeline"
			@close="closeTimeline"
		/>

		<footer class="page-footer"><span>CHAINPILOT SUPPLY CHAIN WORKSPACE</span><span>风险分析以当前业务数据和可用知识来源为依据</span></footer>
	</v-container>
</template>

<script setup lang="ts">
import { useSupplyChainEvents } from '~/composables/useSupplyChainEvents';
import { useSupplyChainHistory } from '~/composables/useSupplyChainHistory';
import {
	HISTORY_PAGE_SIZE_OPTIONS,
	HISTORY_STATUS_OPTIONS,
	type SupplyChainTaskStatus as TaskStatus,
	type SupplyChainTaskSummary as TaskHistoryItem,
} from '~/services/supplyChainHistory';
import type { DecisionProposal } from '~/services/supplyChainDecision';
import { roleCssVars, type ChainPilotRoleId } from '~/utils/supplyChainAgentRoles';
import { scrollBehavior } from '~/utils/prefersReducedMotion';
import {
	pickWorkbenchFocus,
	type DecisionWorkspaceSnapshot,
	type WorkbenchTask,
} from '~/utils/supplyChainWorkbench';

type RiskLevel = 'LOW' | 'MEDIUM' | 'HIGH';

type OrderRisk = {
	order: { orderId: string; sku: string; requestedDeliveryDate: string; estimatedDeliveryDate: string };
	riskLevel: RiskLevel;
	riskScore: number;
	inventoryShortage: number;
	riskFactors: string[];
	recommendedActions: string[];
};

type Supplier = { supplierId: string; riskLevel: RiskLevel };
type SupplierDetail = Supplier & { name: string; onTimeDeliveryRate: number; defectRate: number; latestIncident: string };
type Inventory = { sku: string; productName: string; onHandQuantity: number; reservedQuantity: number; safetyStock: number; availableToPromise: number };
type Citation = { sourceId: string; title: string; excerpt: string };
type AnalysisReport = {
	taskId: string;
	status: string;
	summary: string;
	atRiskOrders: OrderRisk[];
	citations: Citation[];
	modelAssisted: boolean;
	validationAttempts: number;
};
type TimelineTarget = { taskId: string; status: string };
type DisposalWorkspaceHandle = {
	refreshAfterConfirmedProposal: (planId?: string | null) => Promise<void>;
};
type WorkspaceView = 'analysis' | 'decision' | 'disposal' | 'history';
type WorkspaceViewDefinition = {
	key: WorkspaceView;
	label: string;
	hint: string;
	icon: string;
	role: ChainPilotRoleId;
};

const WORKSPACE_VIEW_KEYS: WorkspaceView[] = ['analysis', 'decision', 'disposal', 'history'];
const WORKSPACE_VIEWS: WorkspaceViewDefinition[] = [
	{ key: 'analysis', label: '供应链分析', hint: '风险与报告', icon: 'mdi-radar', role: 'risk' },
	{ key: 'decision', label: '智能决策', hint: '工具与依据', icon: 'mdi-comment-processing-outline', role: 'planning' },
	{ key: 'disposal', label: '处置沙箱', hint: '模拟与演练', icon: 'mdi-play-circle-outline', role: 'execution' },
	{ key: 'history', label: '任务记录', hint: '分析与执行轨迹', icon: 'mdi-history', role: 'system' },
];

function resolveWorkspaceView(value: unknown): WorkspaceView {
	return typeof value === 'string' && WORKSPACE_VIEW_KEYS.includes(value as WorkspaceView)
		? (value as WorkspaceView)
		: 'analysis';
}

const WORKSPACE_PAGE_COPY: Record<WorkspaceView, { title: string; description: string }> = {
	analysis: {
		title: '供应链分析',
		description: '汇总订单、库存与供应商信号，让每条风险和建议都有可核对的业务依据。',
	},
	decision: {
		title: '自然语言决策',
		description: '提出问题后，Agent 调用已配置工具完成分析、模拟和比较；写入操作仍由人工确认。',
	},
	disposal: {
		title: '处置与沙箱',
		description: '生成处置建议，模拟补货与延期影响；确认方案后，只在受控沙箱中演练执行。',
	},
	history: {
		title: '任务与执行记录',
		description: '回看分析任务、执行轨迹和真实终态；历史记录不会覆盖当前任务。',
	},
};

const disposalWorkspaceRef = ref<DisposalWorkspaceHandle | null>(null);
const route = useRoute();
const router = useRouter();
const workspaceView = ref<WorkspaceView>(resolveWorkspaceView(route.query.view));
const workspacePageCopy = computed(() => WORKSPACE_PAGE_COPY[workspaceView.value]);

function selectWorkspaceView(view: WorkspaceView) {
	if (workspaceView.value === view) {
		return;
	}
	workspaceView.value = view;
	const query = { ...route.query };
	if (view === 'analysis') {
		delete query.view;
	} else {
		query.view = view;
	}
	void router.replace({ query });
}

watch(
	() => route.query.view,
	(value) => {
		workspaceView.value = resolveWorkspaceView(value);
	},
);

watch(workspaceView, async () => {
	await nextTick();
	document.querySelector<HTMLElement>('.base-drawer__content')?.scrollTo({ top: 0, behavior: scrollBehavior() });
});

const orders = ref<OrderRisk[]>([]);
const inventory = ref<Inventory[]>([]);
const suppliers = ref<SupplierDetail[]>([]);
const riskFilter = ref<'ALL' | RiskLevel>('ALL');
const riskFilters = [
	{ title: '全部等级', value: 'ALL' },
	{ title: '高风险', value: 'HIGH' },
	{ title: '中风险', value: 'MEDIUM' },
	{ title: '低风险', value: 'LOW' },
];
const question = ref('分析今天可能延期的订单，结合库存和供应商风险给出建议');
const report = ref<AnalysisReport | null>(null);
const activeTask = ref<TaskStatus | null>(null);
const loadingData = ref(false);
const analyzing = ref(false);
const pageError = ref('');
const analysisError = ref('');
const loadingReport = ref(false);
const analysisMode = computed(() => {
	if (analyzing.value) {
		if (!activeTask.value) {
			return { label: '提交中', tone: 'active' };
		}
		if (activeTask.value.status === 'ACCEPTED') {
			return { label: '排队中', tone: 'active' };
		}
		return { label: '分析中', tone: 'active' };
	}
	if (loadingReport.value) {
		return { label: '读取结果', tone: 'active' };
	}
	if (analysisError.value) {
		return { label: '需处理', tone: 'failure' };
	}
	if (report.value && activeTask.value?.status === 'COMPLETED') {
		return { label: '已完成', tone: 'success' };
	}
	return { label: '就绪', tone: 'idle' };
});
const {
	tasks: recentTasks,
	loading: loadingHistory,
	error: historyError,
	status: historyStatus,
	page: historyPage,
	pageSize: historyPageSize,
	totalElements: historyTotalElements,
	totalPages: historyTotalPages,
	loaded: historyLoaded,
	canPrevious: historyCanPrevious,
	canNext: historyCanNext,
	load: loadTasks,
	setStatus: setHistoryStatus,
	setPageSize: setHistoryPageSize,
	previousPage: previousHistoryPage,
	nextPage: nextHistoryPage,
} = useSupplyChainHistory();
const {
	events: timelineEvents,
	loading: timelineLoading,
	error: timelineError,
	load: loadTimelineEvents,
	retry: retryTimelineEvents,
	clear: clearTimelineEvents,
} = useSupplyChainEvents();

const historyExpanded = ref(false);
const baselineExpanded = ref(false);
const historySummary = computed(() => {
	if (historyError.value) return '任务记录暂时不可用';
	if (loadingHistory.value && !historyLoaded.value) return '正在读取任务记录…';
	if (!historyLoaded.value) return '等待读取任务记录';
	if (historyTotalElements.value === 0) return '当前筛选下暂无任务';
	return `共 ${historyTotalElements.value} 条任务 · 第 ${historyPage.value} / ${historyTotalPages.value} 页`;
});

const decisionState = ref<DecisionWorkspaceSnapshot | null>(null);
const analysisTaskQuestion = ref('');
const preferredWorkbenchTaskId = ref('');

const timelineTarget = ref<TimelineTarget | null>(null);
const timelineStatus = computed(() => {
	const target = timelineTarget.value;
	if (!target) {
		return '';
	}
	if (activeTask.value?.taskId === target.taskId) {
		return activeTask.value.status;
	}
	return recentTasks.value.find((task) => task.taskId === target.taskId)?.status || target.status;
});

const analysisWorkbenchTask = computed<WorkbenchTask | null>(() => {
	const task = activeTask.value;
	if (!task) {
		return null;
	}
	return {
		taskId: task.taskId,
		status: task.status,
		currentStep: task.currentStep,
		question: analysisTaskQuestion.value || question.value,
		source: 'ANALYSIS',
		updatedAt: task.updatedAt,
		error: task.error,
	};
});

const decisionWorkbenchTask = computed<WorkbenchTask | null>(() => {
	const snapshot = decisionState.value;
	if (!snapshot?.task) {
		return null;
	}
	return {
		taskId: snapshot.task.taskId,
		status: snapshot.task.status,
		currentStep: snapshot.task.currentStep,
		question: snapshot.decision?.question || snapshot.question,
		source: 'DECISION',
		updatedAt: snapshot.task.updatedAt,
		error: snapshot.task.error || (snapshot.task.status === 'FAILED' ? snapshot.error : null),
	};
});

const workbenchTask = computed(() =>
	pickWorkbenchFocus(analysisWorkbenchTask.value, decisionWorkbenchTask.value, preferredWorkbenchTaskId.value),
);
const workbenchIsDecision = computed(() => workbenchTask.value?.source === 'DECISION');
const workbenchDecision = computed(() =>
	workbenchIsDecision.value ? decisionState.value?.decision ?? null : null,
);
const workbenchProposals = computed(() =>
	workbenchIsDecision.value ? decisionState.value?.proposals ?? [] : [],
);
const workbenchReport = computed(() => (workbenchTask.value?.source === 'ANALYSIS' ? report.value : null));
const workbenchSyncError = computed(() => {
	if (!workbenchTask.value) {
		return '';
	}
	return workbenchIsDecision.value ? decisionState.value?.error ?? '' : analysisError.value;
});

watch(
	() => decisionState.value?.task?.taskId ?? '',
	(next, previous) => {
		if (next && next !== previous) {
			preferredWorkbenchTaskId.value = next;
		}
	},
);

const atRiskOrders = computed(() => orders.value.filter((item) => item.riskLevel !== 'LOW'));
const riskySuppliers = computed(() => suppliers.value.filter((supplier) => supplier.riskLevel !== 'LOW'));
const dataLoaded = computed(() => orders.value.length > 0 && inventory.value.length > 0 && suppliers.value.length > 0);
const filteredOrders = computed(() =>
	orders.value.filter((item) => riskFilter.value === 'ALL' || item.riskLevel === riskFilter.value),
);

function riskColor(level: RiskLevel) {
	return level === 'HIGH' ? 'error' : level === 'MEDIUM' ? 'warning' : 'success';
}

function riskLabel(level: RiskLevel) {
	return level === 'HIGH' ? '高风险' : level === 'MEDIUM' ? '中风险' : '低风险';
}

function taskStatusLabel(status: string) {
	return status === 'COMPLETED' ? '已完成' : status === 'RUNNING' ? '执行中' : status === 'ACCEPTED' ? '排队中' : status === 'FAILED' ? '失败' : status;
}

function taskStatusColor(status: string) {
	return status === 'COMPLETED' ? 'success' : status === 'FAILED' ? 'error' : 'primary';
}

function formatTaskTime(value: string) {
	const date = new Date(value);
	return Number.isNaN(date.getTime())
		? value
		: date.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
}

async function loadData() {
	loadingData.value = true;
	pageError.value = '';
	try {
		const [orderData, inventoryData, supplierData] = await Promise.all([
			$fetch<OrderRisk[]>('/api/supply-chain/orders'),
			$fetch<Inventory[]>('/api/supply-chain/inventory'),
			$fetch<SupplierDetail[]>('/api/supply-chain/suppliers'),
		]);
		orders.value = orderData;
		inventory.value = inventoryData;
		suppliers.value = supplierData;
	} catch (error) {
		pageError.value = error instanceof Error ? `无法加载供应链数据：${error.message}` : '无法加载供应链数据，请确认后端已启动。';
	} finally {
		loadingData.value = false;
	}
}

async function pollActiveTask() {
	const deadline = Date.now() + 120_000;
	while (activeTask.value && (activeTask.value.status === 'ACCEPTED' || activeTask.value.status === 'RUNNING')) {
		if (Date.now() >= deadline) {
			throw new Error(`任务仍在执行，可稍后通过任务编号查询：${activeTask.value.taskId}`);
		}
		await new Promise((resolve) => setTimeout(resolve, 500));
		activeTask.value = await $fetch<TaskStatus>(`/api/supply-chain/analysis/tasks/${activeTask.value.taskId}`);
	}
	const current = activeTask.value;
	if (
		current &&
		(current.status === 'COMPLETED' || current.status === 'FAILED') &&
		timelineTarget.value?.taskId === current.taskId
	) {
		await loadTimelineEvents(current.taskId);
	}
	if (!current || current.status !== 'COMPLETED') {
		throw new Error(current?.error || `任务失败，当前步骤：${current?.currentStep || '未知'}`);
	}
	return current;
}

async function loadReport(taskId: string) {
	report.value = await $fetch<AnalysisReport>(`/api/supply-chain/analysis/tasks/${taskId}/report`);
}

function openTimeline(target: TimelineTarget) {
	if (!target?.taskId) {
		return;
	}
	timelineTarget.value = { taskId: target.taskId, status: target.status };
	void loadTimelineEvents(target.taskId);
	void nextTick().then(() => {
		document.getElementById('execution-timeline')?.scrollIntoView({ behavior: scrollBehavior(), block: 'start' });
	});
}

function refreshTimeline() {
	return retryTimelineEvents();
}

function closeTimeline() {
	timelineTarget.value = null;
	clearTimelineEvents();
}

function handleProposalConfirmed(proposal: DecisionProposal) {
	const resultPlanId = proposal.result?.planId;
	const planId =
		typeof resultPlanId === 'string' && resultPlanId.trim()
			? resultPlanId
			: proposal.targetId;
	void disposalWorkspaceRef.value?.refreshAfterConfirmedProposal(planId);
}

async function openTask(task: TaskHistoryItem) {
	if (analyzing.value || loadingReport.value) {
		return;
	}
	selectWorkspaceView('analysis');
	if (timelineTarget.value && timelineTarget.value.taskId !== task.taskId) {
		closeTimeline();
	}
	pageError.value = '';
	analysisError.value = '';
	analysisTaskQuestion.value = task.question;
	preferredWorkbenchTaskId.value = task.taskId;
	if (task.status === 'FAILED') {
		report.value = null;
		activeTask.value = task;
		question.value = task.question;
		analysisError.value = task.error || `任务失败，当前步骤：${task.currentStep}`;
		return;
	}
	if (task.status === 'ACCEPTED' || task.status === 'RUNNING') {
		analyzing.value = true;
		report.value = null;
		activeTask.value = task;
		question.value = task.question;
		try {
			const current = await pollActiveTask();
			await loadReport(current.taskId);
			await loadData();
		} catch (error) {
			analysisError.value = error instanceof Error ? error.message : '任务查询失败，请检查后端日志。';
		} finally {
			analyzing.value = false;
			await loadTasks();
		}
		return;
	}
	loadingReport.value = true;
	report.value = null;
	activeTask.value = task;
	try {
		await loadReport(task.taskId);
		await nextTick();
		document.getElementById('analysis-report')?.scrollIntoView({ behavior: scrollBehavior(), block: 'start' });
	} catch (error) {
		analysisError.value = error instanceof Error ? error.message : '无法加载历史报告。';
	} finally {
		loadingReport.value = false;
	}
}

async function runAnalysis() {
	const submittedQuestion = question.value.trim();
	if (!submittedQuestion) {
		return;
	}
	selectWorkspaceView('analysis');
	analyzing.value = true;
	pageError.value = '';
	analysisError.value = '';
	report.value = null;
	activeTask.value = null;
	analysisTaskQuestion.value = submittedQuestion;
	preferredWorkbenchTaskId.value = '';
	closeTimeline();
	try {
		activeTask.value = await $fetch<TaskStatus>('/api/supply-chain/analysis/tasks', {
			method: 'POST',
			body: { question: submittedQuestion },
		});
		preferredWorkbenchTaskId.value = activeTask.value.taskId;
		const task = await pollActiveTask();
		await loadReport(task.taskId);
		await loadData();
	} catch (error) {
		analysisError.value = error instanceof Error ? error.message : '分析失败，请检查后端服务和日志。';
	} finally {
		analyzing.value = false;
		await loadTasks();
	}
}

function retryAnalysis(taskQuestion: string) {
	if (taskQuestion.trim()) {
		question.value = taskQuestion;
	}
	void runAnalysis();
}

onMounted(() => {
	loadData();
	loadTasks();
});
</script>

<style scoped>
.supply-chain-page {
	position: relative;
	width: 100%;
	max-width: 1600px;
	min-height: 100%;
	margin: 0 auto;
	color: var(--cp-ink);
	background:
		radial-gradient(ellipse 52rem 22rem at 12% -4%, rgba(220, 235, 255, 0.58), transparent 70%),
		radial-gradient(ellipse 42rem 20rem at 94% 4%, rgba(232, 245, 241, 0.6), transparent 72%),
		linear-gradient(145deg, var(--cp-bg) 0%, #f3f8ff 46%, var(--cp-bg-warm) 100%);
}

.workspace-header { display: flex; align-items: flex-end; justify-content: space-between; gap: 24px; }
.workspace-heading { min-width: 0; }
.eyebrow, .section-kicker { color: #5a7290; font-size: 13px; font-weight: 750; letter-spacing: 0.14em; line-height: 1.5; }
.eyebrow { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; }
.eyebrow-mark { display: inline-grid; width: 25px; height: 25px; place-items: center; color: var(--cp-primary); background: var(--cp-primary-soft); border: 1px solid color-mix(in srgb, var(--cp-primary) 14%, transparent); border-radius: var(--cp-radius-xs); }
.eyebrow-divider { color: #68727e; }
.page-title { margin: 0; color: var(--cp-ink-strong); font-size: var(--cp-page-title-size); font-weight: 720; letter-spacing: -0.03em; line-height: 1.25; }
.page-description { max-width: 720px; margin: 8px 0 0; color: var(--cp-muted); font-size: var(--cp-page-description-size); line-height: 1.7; }
.header-actions { display: flex; align-items: center; gap: 14px; flex-shrink: 0; }
.data-status { display: flex; align-items: center; gap: 8px; color: var(--cp-muted); font-size: 12px; font-weight: 600; }
.status-dot, .agent-mode > span, .footnote-dot { width: 7px; height: 7px; flex: 0 0 7px; border-radius: 50%; background: var(--cp-role-execution); box-shadow: 0 0 0 3px color-mix(in srgb, var(--cp-role-execution) 13%, transparent); }
.status-dot--loading { background: var(--cp-primary); animation: pulse 1.4s ease-in-out infinite; }
.status-dot--waiting { background: #a8b5c4; box-shadow: 0 0 0 3px rgba(168, 181, 196, 0.13); }
.refresh-button { min-height: 40px; padding-inline: 16px; color: var(--cp-primary-deep) !important; background: var(--cp-surface); border-color: var(--cp-line) !important; border-radius: var(--cp-radius-sm); box-shadow: var(--cp-shadow-soft); }
.feedback-alert { border-radius: 13px; }
.workspace-view-nav {
	display: grid;
	grid-template-columns: repeat(4, minmax(0, 1fr));
	gap: 6px;
	margin-bottom: 24px;
	padding: 6px;
	background: rgba(255, 255, 255, 0.72);
	border: 1px solid var(--cp-line-soft);
	border-radius: 16px;
	box-shadow: var(--cp-shadow-soft);
}
.workspace-view-tab {
	--cp-role-color: var(--cp-primary);
	--cp-role-soft: var(--cp-primary-soft);
	--cp-role-ink: var(--cp-primary-deep);
	display: flex;
	align-items: center;
	gap: 10px;
	min-width: 0;
	padding: 10px 12px;
	color: var(--cp-muted);
	font: inherit;
	text-align: left;
	background: transparent;
	border: 1px solid transparent;
	border-radius: 12px;
	cursor: pointer;
	transition: color var(--cp-duration-fast) var(--cp-ease-soft), background-color var(--cp-duration-fast) var(--cp-ease-soft), border-color var(--cp-duration-fast) var(--cp-ease-soft);
}
.workspace-view-tab:hover { color: var(--cp-role-ink); background: color-mix(in srgb, var(--cp-role-soft) 55%, #fff); }
.workspace-view-tab:focus-visible { outline: 2px solid color-mix(in srgb, var(--cp-role-color) 55%, #fff); outline-offset: 2px; }
.workspace-view-tab.is-active {
	color: var(--cp-role-ink);
	background: color-mix(in srgb, var(--cp-role-soft) 78%, #fff);
	border-color: color-mix(in srgb, var(--cp-role-color) 24%, transparent);
}
.workspace-view-tab__icon {
	display: grid;
	width: 32px;
	height: 32px;
	flex: 0 0 32px;
	place-items: center;
	color: var(--cp-role-color);
	background: var(--cp-role-soft);
	border: 1px solid color-mix(in srgb, var(--cp-role-color) 16%, transparent);
	border-radius: 10px;
	transition: color var(--cp-duration-fast) var(--cp-ease-soft), background-color var(--cp-duration-fast) var(--cp-ease-soft);
}
.workspace-view-tab.is-active .workspace-view-tab__icon { color: #fff; background: var(--cp-role-color); border-color: var(--cp-role-color); }
.workspace-view-tab__copy { display: flex; min-width: 0; flex: 1; flex-direction: column; gap: 1px; }
.workspace-view-tab__copy strong { overflow: hidden; color: inherit; font-size: 13px; font-weight: 700; line-height: 1.4; text-overflow: ellipsis; white-space: nowrap; }
.workspace-view-tab__copy small { overflow: hidden; color: var(--cp-subtle); font-size: 13px; line-height: 1.4; text-overflow: ellipsis; white-space: nowrap; }
.workspace-view-tab.is-active .workspace-view-tab__copy small { color: color-mix(in srgb, var(--cp-role-ink) 72%, var(--cp-muted)); }
.workspace-view-tab__count {
	min-width: 22px;
	margin-left: 2px;
	padding: 2px 7px;
	color: var(--cp-role-ink);
	background: #fff;
	border: 1px solid color-mix(in srgb, var(--cp-role-color) 22%, transparent);
	border-radius: 999px;
	font-size: 13px;
	font-variant-numeric: tabular-nums;
	font-weight: 700;
	line-height: 1.5;
	text-align: center;
}
.workspace-view { min-width: 0; }
.view-section-heading { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; margin: 30px 0 16px; }
.metrics-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; }
.metric-card, .surface-panel { border: 1px solid var(--cp-line-soft); background: var(--cp-surface); box-shadow: var(--cp-shadow-soft); -webkit-backdrop-filter: blur(12px) saturate(1.08); backdrop-filter: blur(12px) saturate(1.08); }
.metric-card { position: relative; display: flex; align-items: center; gap: 15px; min-height: 116px; padding: 21px 22px; overflow: hidden; border-radius: var(--cp-radius-lg); }
.metric-icon { display: grid; width: 43px; height: 43px; flex: 0 0 43px; place-items: center; border: 1px solid transparent; border-radius: 13px; }
.metric-icon--data { color: var(--cp-role-data); background: var(--cp-role-data-soft); border-color: color-mix(in srgb, var(--cp-role-data) 14%, transparent); }
.metric-icon--high { color: var(--cp-risk-high); background: color-mix(in srgb, var(--cp-risk-high) 8%, #fff); border-color: color-mix(in srgb, var(--cp-risk-high) 14%, transparent); }
.metric-icon--medium { color: var(--cp-risk-medium); background: color-mix(in srgb, var(--cp-risk-medium) 9%, #fff); border-color: color-mix(in srgb, var(--cp-risk-medium) 15%, transparent); }
.metric-copy { display: flex; min-width: 0; flex-direction: column; }
.metric-label { color: var(--cp-muted); font-size: 12px; font-weight: 600; }
.metric-value { margin-top: 2px; color: var(--cp-ink); font-size: 29px; font-variant-numeric: tabular-nums; font-weight: 710; letter-spacing: -0.04em; line-height: 1.15; }
.metric-note { margin-top: 4px; color: var(--cp-subtle); font-size: 13px; }
.metric-index { position: absolute; top: 17px; right: 19px; color: #696f78; font-size: 13px; font-weight: 700; letter-spacing: 0.08em; }
.primary-grid { display: grid; grid-template-columns: minmax(0, 1.8fr) minmax(300px, 0.8fr); align-items: start; gap: 18px; }
.surface-panel { min-width: 0; border-color: var(--cp-line-soft); border-radius: var(--cp-radius-lg); }
.risk-panel, .data-panel, .report-panel { overflow: hidden; }
.panel-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 22px 23px 18px; }
.panel-heading--compact { padding-bottom: 15px; }
.panel-title { margin-top: 4px; color: var(--cp-ink); font-size: 17px; font-weight: 700; letter-spacing: -0.02em; line-height: 1.4; }
.panel-subtitle { margin: 4px 0 0; color: #637183; font-size: 12px; line-height: 1.65; }
.filter-select { max-width: 148px; flex: 0 0 148px; }
.filter-select :deep(.v-field) { border-radius: 10px; background: rgba(248, 251, 255, 0.84); }
.table-wrap { width: 100%; overflow-x: auto; }
.data-table { color: #526982; background: transparent; }
.data-table :deep(table) { min-width: 560px; }
.data-table :deep(th), .data-table :deep(td) { padding-inline: 10px; }
.data-table :deep(thead th) { height: 40px; color: #657281; background: rgba(245, 249, 254, 0.82); border-bottom: 1px solid #eaf0f7; font-size: 13px; font-weight: 700; letter-spacing: 0.04em; white-space: nowrap; }
.data-table :deep(tbody td) { height: 62px; border-bottom: 1px solid rgba(228, 237, 247, 0.8); font-size: 12px; white-space: nowrap; }
.data-table :deep(tbody tr:last-child td) { border-bottom: 0; }
.data-table :deep(tbody tr:hover) { background: rgba(237, 245, 255, 0.42); }
.table-primary, .table-secondary { display: block; }
.table-primary { color: #3f5875; font-size: 12px; font-weight: 650; }
.table-secondary { margin-top: 4px; color: #66707d; font-size: 13px; }
.risk-chip { font-weight: 650; letter-spacing: 0.01em; }
.score-value { color: #405e80; font-size: 13px; font-variant-numeric: tabular-nums; }
.shortage-value { color: #9f5e68; font-weight: 700; font-variant-numeric: tabular-nums; }
.neutral-value { color: #667382; font-variant-numeric: tabular-nums; }
.available-value { color: #547866; font-weight: 700; font-variant-numeric: tabular-nums; }
.empty-cell { height: 116px !important; color: #6c6c6c !important; text-align: center; }
.empty-cell :deep(.v-icon), .empty-cell :deep(.v-progress-circular) { margin-right: 8px; vertical-align: middle; }
.empty-cell :deep(.v-btn) { margin-left: 6px; }
.panel-footnote { display: flex; align-items: center; gap: 9px; padding: 12px 23px 15px; color: #667281; font-size: 13px; }
.footnote-dot { width: 6px; height: 6px; flex-basis: 6px; background: #8eb7e7; box-shadow: none; }
.agent-column { display: flex; min-width: 0; flex-direction: column; gap: 14px; }
.agent-panel { padding: 22px; }
.agent-panel-top, .task-heading, .report-header, .report-heading, .report-order-heading { display: flex; align-items: center; }
.agent-panel-top { gap: 12px; }
.agent-emblem, .report-icon { display: grid; width: 41px; height: 41px; flex: 0 0 41px; place-items: center; color: var(--cp-role-planning); background: var(--cp-role-planning-soft); border: 1px solid color-mix(in srgb, var(--cp-role-planning) 15%, transparent); border-radius: 13px; }
.agent-mode { display: inline-flex; align-items: center; gap: 7px; margin-left: auto; padding: 6px 9px; color: var(--cp-role-system-ink); background: var(--cp-role-system-soft); border: 1px solid color-mix(in srgb, var(--cp-role-system) 14%, transparent); border-radius: var(--cp-radius-pill); font-size: 13px; font-weight: 650; white-space: nowrap; transition: color var(--cp-duration-base) var(--cp-ease-out), background-color var(--cp-duration-base) var(--cp-ease-out), border-color var(--cp-duration-base) var(--cp-ease-out); }
.agent-mode > span { width: 6px; height: 6px; flex-basis: 6px; background: var(--cp-role-system); box-shadow: none; }
.agent-mode--active { color: var(--cp-role-planning-ink); background: var(--cp-role-planning-soft); border-color: color-mix(in srgb, var(--cp-role-planning) 18%, transparent); }
.agent-mode--active > span { background: var(--cp-role-planning); animation: pulse 1.6s ease-in-out infinite; }
.agent-mode--success { color: var(--cp-role-execution-ink); background: var(--cp-role-execution-soft); border-color: color-mix(in srgb, var(--cp-role-execution) 16%, transparent); }
.agent-mode--success > span { background: var(--cp-role-execution); }
.agent-mode--failure { color: var(--cp-role-failure-ink); background: var(--cp-role-failure-soft); border-color: color-mix(in srgb, var(--cp-role-failure) 18%, transparent); }
.agent-mode--failure > span { background: var(--cp-role-failure); }
.agent-description { margin: 16px 0 18px; }
.field-label { display: block; margin-bottom: 8px; color: #5d748f; font-size: 13px; font-weight: 650; }
.question-field :deep(.v-field) { align-items: flex-start; border-radius: 12px; background: rgba(249, 252, 255, 0.85); }
.question-field :deep(textarea) { color: #405a78; font-size: 12px; line-height: 1.7; }
.question-field :deep(.v-field__outline) { color: #dce8f7; }
.question-field :deep(.v-field__outline__notch) { color: var(--cp-muted); }
.agent-actions { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 15px; }
.helper-text { color: #66707d; font-size: 13px; line-height: 1.5; }
.run-button { min-height: 39px; padding-inline: 15px; color: #fff !important; border-radius: var(--cp-radius-sm); background: #3f6fb5 !important; box-shadow: 0 5px 12px color-mix(in srgb, var(--cp-primary) 22%, transparent); font-size: 12px; font-weight: 650; }
.agent-live { display: flex; align-items: flex-start; gap: 9px; margin-top: 13px; padding: 10px 11px; background: color-mix(in srgb, var(--cp-role-planning-soft) 72%, #fff); border: 1px solid color-mix(in srgb, var(--cp-role-planning) 16%, transparent); border-radius: 11px; animation: agent-live-in var(--cp-duration-base) var(--cp-ease-out) both; }
.agent-live-icon { display: grid; width: 27px; height: 27px; flex: 0 0 27px; place-items: center; color: var(--cp-role-planning); background: #fff; border-radius: 8px; }
.agent-live-copy { display: flex; min-width: 0; flex: 1; flex-direction: column; gap: 2px; }
.agent-live-copy strong { color: var(--cp-role-planning-ink); font-size: 13px; font-weight: 700; line-height: 1.5; }
.agent-live-copy span { color: var(--cp-muted); font-size: 13px; line-height: 1.55; overflow-wrap: anywhere; }
.agent-live .trail-link { flex: 0 0 auto; font-size: 13px; white-space: nowrap; }
.agent-live-actions { display: flex; flex: 0 0 auto; flex-direction: column; align-items: flex-end; gap: 3px; }
.agent-live--failed { background: color-mix(in srgb, var(--cp-role-failure-soft) 70%, #fff); border-color: color-mix(in srgb, var(--cp-role-failure) 18%, transparent); }
.agent-live--failed .agent-live-icon { color: var(--cp-role-failure); }
.agent-live--failed .agent-live-copy strong { color: var(--cp-role-failure-ink); }
.agent-live--failed .trail-link { color: var(--cp-role-failure-ink); }
.workflow-hint { display: flex; align-items: flex-start; gap: 12px; padding: 15px 16px; border: 1px solid rgba(220, 232, 247, 0.78); background: rgba(243, 248, 255, 0.66); border-radius: 14px; }
.workflow-hint-icon { display: grid; width: 31px; height: 31px; flex: 0 0 31px; place-items: center; color: #4f729a; background: #eaf3ff; border-radius: 10px; }
.workflow-hint strong, .workflow-hint span { display: block; }
.workflow-hint strong { color: #587391; font-size: 13px; font-weight: 700; }
.workflow-hint span { margin-top: 4px; color: #637181; font-size: 13px; line-height: 1.55; }
.history-panel { overflow: hidden; }
.history-panel--full { margin-bottom: 24px; }
.history-list { display: flex; flex-direction: column; gap: 8px; padding: 0 14px 15px; }
.history-list--full { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; padding: 16px 20px 20px; }
.history-list--full .history-item { display: flex; flex-direction: column; }
.history-list--full .history-item-main { flex: 1; }
.history-list--full .history-item-actions { margin-top: auto; }
.history-list--full .history-question { display: block; overflow: visible; -webkit-line-clamp: unset; }
.history-empty--full { grid-column: 1 / -1; padding: 38px 16px; }
.history-item { display: block; width: 100%; overflow: hidden; background: var(--cp-surface); border: 1px solid var(--cp-line-soft); border-radius: var(--cp-radius-sm); transition: border-color var(--cp-duration-fast) var(--cp-ease-soft), background-color var(--cp-duration-fast) var(--cp-ease-soft); }
.history-item:hover, .history-item:focus-within { background: #f5faff; border-color: var(--cp-line); }
.history-item--current { border-color: color-mix(in srgb, var(--cp-primary) 32%, var(--cp-line)); box-shadow: inset 3px 0 0 var(--cp-primary); }
.history-item-main { display: block; width: 100%; padding: 11px 12px 7px; color: inherit; font: inherit; text-align: left; background: transparent; border: 0; cursor: pointer; }
.history-item-main:focus-visible { outline: 2px solid #9fc1e8; outline-offset: -2px; }
.history-item-main:disabled { cursor: wait; opacity: 0.68; }
.history-item-actions { display: flex; align-items: flex-start; justify-content: space-between; gap: 8px; padding: 0 12px 9px; }
.history-task-id { min-width: 0; color: #66707d; font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 13px; line-height: 1.45; overflow-wrap: anywhere; }
.history-trail-link { flex: 0 0 auto; padding: 0; color: #54749e; font: inherit; font-size: 13px; font-weight: 650; line-height: 1.45; background: none; border: 0; cursor: pointer; }
.history-trail-link:hover, .history-trail-link:focus-visible { color: #315fad; text-decoration: underline; outline: none; }
.history-trail-link--active { color: #315fad; }
.history-item-top, .history-item-foot { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.history-time { color: #66707d; font-size: 13px; white-space: nowrap; }
.history-question { display: -webkit-box; overflow: hidden; margin: 7px 0 6px; color: #526d8c; font-size: 13px; line-height: 1.55; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.history-item-foot { color: #65717e; font-size: 13px; }
.history-open { color: #537399; font-weight: 650; white-space: nowrap; }
.history-open--error { color: #93646b; }
.history-empty { padding: 6px 2px 3px; color: #667280; font-size: 13px; line-height: 1.6; text-align: center; }
.history-empty--error { color: #93646b; }
.history-toolbar { display: flex; align-items: center; gap: 6px; flex-shrink: 0; }
.history-status-select { width: 118px; flex: 0 0 118px; }
.history-status-select :deep(.v-field) { border-radius: 10px; background: rgba(248, 251, 255, 0.84); }
.history-status-select :deep(.v-field__input) { min-height: 32px; padding-top: 0; padding-bottom: 0; font-size: 13px; }
.history-pagination { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 6px 10px; padding: 11px 14px 13px; border-top: 1px solid #edf2f8; }
.history-range { color: #637181; font-size: 13px; line-height: 1.5; }
.history-pagination-controls { display: flex; align-items: center; gap: 2px; flex-shrink: 0; }
.history-page-size { width: 92px; flex: 0 0 92px; }
.history-page-size :deep(.v-field) { font-size: 13px; }
.history-page-size :deep(.v-field__input) { min-height: 30px; padding-top: 0; padding-bottom: 0; color: #5e738d; font-size: 13px; }
.history-pagination-controls :deep(.v-btn) { width: 28px; height: 28px; }
.history-collapsible { margin-bottom: 24px; }
.history-collapsible :deep(.collapsible-section__body) { padding: 0; }
.baseline-collapsible { margin-top: 20px; }
.baseline-collapsible :deep(.collapsible-section__body) { padding: 16px; }
.secondary-grid--nested { margin-bottom: 0; }
.history-toolbar--inline { padding: 12px 16px 0; }
.history-list--full { padding-top: 12px; }
.task-panel { padding: 18px; }
.task-panel--running { background: rgba(246, 250, 255, 0.88); border-color: #dceaff; }
.task-panel--failed { background: rgba(255, 249, 249, 0.87); border-color: #f4e3e6; }
.task-heading { gap: 10px; }
.task-pulse, .failure-icon { display: grid; width: 34px; height: 34px; flex: 0 0 34px; place-items: center; color: #4271b8; background: #eaf3ff; border-radius: 11px; }
.failure-icon { color: #9e616a; background: #fff0f2; }
.section-kicker--error { color: #93646b; }
.task-title { margin-top: 3px; color: #3e5978; font-size: 13px; font-weight: 700; }
.task-heading .v-chip { margin-left: auto; }
.task-progress { border-radius: 999px; }
.task-step { display: flex; justify-content: space-between; gap: 12px; margin-top: 13px; color: #637182; font-size: 13px; }
.task-step strong { color: #587391; font-weight: 650; text-align: right; }
.task-meta, .report-meta { display: flex; align-items: center; gap: 8px; margin-top: 9px; color: #66707d; font-size: 13px; }
.report-meta { flex-wrap: wrap; }
.task-meta > span, .report-meta > span { width: 3px; height: 3px; background: #bac9da; border-radius: 50%; }
.task-trail-row { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 8px 10px; margin-top: 11px; padding-top: 9px; border-top: 1px solid #e8eff7; color: #66707d; font-size: 13px; line-height: 1.5; }
.trail-link { padding: 0; color: #54749e; font: inherit; font-size: 13px; font-weight: 650; line-height: 1.5; background: none; border: 0; cursor: pointer; }
.trail-link:hover, .trail-link:focus-visible { color: #315fad; text-decoration: underline; outline: none; }
.report-trail-link { margin-left: auto; }
.failure-message { margin: 12px 0; color: #826b71; font-size: 13px; line-height: 1.65; overflow-wrap: anywhere; }
.secondary-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 18px; }
.count-badge { padding: 5px 9px; color: #5f738b; background: #f2f7fd; border: 1px solid #e5eef9; border-radius: 999px; font-size: 13px; font-weight: 650; white-space: nowrap; }
.data-table--compact :deep(table) { min-width: 500px; }
.mobile-data-list { display: none; }
.mobile-data-card { padding: 14px; border: 1px solid #e7eef7; background: rgba(252, 253, 255, 0.88); border-radius: 12px; }
.mobile-card-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 10px; }
.mobile-card-heading > div { min-width: 0; }
.mobile-card-heading strong, .mobile-card-heading span { display: block; }
.mobile-card-heading strong { overflow: hidden; color: #405a78; font-size: 12px; font-weight: 700; text-overflow: ellipsis; }
.mobile-card-heading span { margin-top: 4px; color: #647383; font-size: 13px; line-height: 1.5; }
.mobile-delivery { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 4px 12px; margin-top: 12px; color: #637182; font-size: 13px; }
.mobile-delivery strong { margin-left: 3px; color: #526d8c; font-weight: 650; }
.mobile-stats { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; margin-top: 12px; padding-top: 11px; border-top: 1px solid #edf2f8; }
.mobile-stats > div { display: flex; align-items: center; justify-content: space-between; gap: 8px; min-width: 0; }
.mobile-stats span { color: #667282; font-size: 13px; }
.mobile-stats strong { color: #536e8c; font-size: 12px; font-variant-numeric: tabular-nums; }
.mobile-stats small { margin-left: 2px; color: #69717b; font-size: 13px; font-weight: 500; }
.mobile-stats--four { grid-template-columns: repeat(2, minmax(0, 1fr)); row-gap: 9px; }
.mobile-supplier-incident { margin-top: 10px; color: #657280; font-size: 13px; line-height: 1.55; }
.mobile-empty { display: flex; align-items: center; justify-content: center; gap: 8px; min-height: 86px; color: #657281; font-size: 13px; }
.report-panel { padding: 23px; }
.report-header { justify-content: space-between; gap: 20px; }
.report-heading { gap: 12px; }
.report-icon { border-radius: 13px; }
.report-badges { display: flex; flex-wrap: wrap; gap: 8px; justify-content: flex-end; }
.report-badges :deep(.v-chip) { font-size: 13px; }
.report-summary { margin-top: 20px; padding: 15px 17px; color: #506b89; background: var(--cp-surface-muted); border: 1px solid var(--cp-line-soft); border-radius: var(--cp-radius-sm); font-size: 13px; line-height: 1.8; }
.report-meta { margin-top: 11px; }
.report-orders { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; margin-top: 18px; }
.report-order { min-width: 0; padding: 15px; background: var(--cp-surface); border: 1px solid var(--cp-line-soft); border-radius: var(--cp-radius-sm); }
.report-order-heading { justify-content: space-between; gap: 10px; }
.report-order-heading > div { min-width: 0; }
.report-order-heading strong, .report-order-heading span { display: block; }
.report-order-heading strong { color: #405a78; font-size: 12px; }
.report-order-heading span { margin-top: 4px; color: #677380; font-size: 13px; }
.factor-list { margin: 12px 0 0; padding-left: 17px; color: #617288; font-size: 13px; line-height: 1.75; }
.factor-list li::marker { color: #5a7390; }
.recommendation { display: flex; align-items: flex-start; gap: 7px; margin-top: 12px; padding: 10px 11px; color: #627183; background: #f4f8fd; border-radius: 9px; font-size: 13px; line-height: 1.65; }
.recommendation :deep(.v-icon) { flex: 0 0 auto; color: #886d43; }
.recommendation strong { margin-right: 6px; color: #5e738d; }
.citation-section { margin-top: 22px; padding-top: 19px; border-top: 1px solid #e8eff7; }
.citation-heading { display: flex; align-items: center; gap: 8px; margin-bottom: 11px; color: #597290; }
.citation-heading h3 { color: #526d8c; font-size: 12px; font-weight: 700; }
.citation-heading > span { margin-left: auto; color: #66707d; font-size: 13px; }
.citation-panels { overflow: hidden; border: 1px solid var(--cp-line-soft); border-radius: var(--cp-radius-sm); }
.citation-panels :deep(.v-expansion-panel) { color: #5d748c; background: rgba(250, 252, 255, 0.86); }
.citation-panels :deep(.v-expansion-panel-title) { min-height: 42px; font-size: 13px; font-weight: 650; }
.citation-panels :deep(.v-expansion-panel-text__wrapper) { padding: 0 16px 14px; }
.citation-text { color: #637183; font-size: 13px; line-height: 1.7; }
.citation-text span { display: block; margin-bottom: 4px; color: #637284; font-size: 13px; }
.citation-empty, .report-empty { display: flex; align-items: center; gap: 8px; margin-top: 17px; color: #677381; font-size: 13px; }
.report-empty { padding: 13px 15px; color: #597868; background: #f3f9f5; border: 1px solid #e5f0e9; border-radius: 11px; }
.page-footer { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 0 2px; color: #66717f; font-size: 13px; letter-spacing: 0.025em; }
.page-footer span:first-child { font-size: 13px; font-weight: 700; letter-spacing: 0.1em; }

@keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.45; } }
@keyframes agent-live-in { from { opacity: 0; transform: translateY(3px); } to { opacity: 1; transform: translateY(0); } }

@media (max-width: 1100px) {
	.primary-grid { grid-template-columns: 1fr; }
	.secondary-grid { grid-template-columns: 1fr; }
	.panel-heading { padding-inline: 18px; }
	.agent-panel { padding: 19px; }
}

@media (min-width: 1101px) and (max-width: 1400px) {
	.secondary-grid { grid-template-columns: 1fr; }
}

@media (max-width: 900px) {
	.primary-grid { grid-template-columns: 1fr; }
	.agent-column { display: block; }
	.workspace-view-nav { grid-template-columns: repeat(2, minmax(0, 1fr)); }
	.history-list--full { grid-template-columns: 1fr; }
	.workflow-hint { align-self: stretch; }
}

@media (max-width: 640px) {
	.supply-chain-page { padding: 20px 14px 26px !important; }
	.workspace-view-nav { grid-template-columns: 1fr; gap: 4px; margin-bottom: 18px; padding: 4px; }
	.workspace-view-tab { padding: 9px 10px; }
	.workspace-view-tab__icon { width: 30px; height: 30px; flex-basis: 30px; }
	.workspace-view-tab__copy strong { white-space: normal; }
	.workspace-view-tab__copy small { white-space: normal; }
	.view-section-heading { margin: 22px 0 13px; }
	.history-panel--full .panel-heading { flex-wrap: wrap; }
	.history-list--full { padding: 12px 14px 16px; }
	.desktop-data-table { display: none; }
	.table-wrap { overflow: visible; }
	.mobile-data-list { display: grid; gap: 9px; padding: 0 12px 13px; }
	.workspace-header { align-items: flex-start; flex-direction: column; gap: 16px; }
	.header-actions { width: 100%; justify-content: space-between; }
	.metrics-grid { grid-template-columns: 1fr; gap: 10px; }
	.metric-card { min-height: 90px; padding: 15px 17px; }
	.metric-value { font-size: 25px; }
	.secondary-grid, .agent-column { grid-template-columns: 1fr; }
	.panel-heading { align-items: flex-start; padding: 18px 16px 14px; }
	.filter-select { max-width: 130px; flex-basis: 130px; }
	.panel-footnote { padding-inline: 16px; line-height: 1.55; }
	.agent-panel { padding: 18px 16px; }
	.agent-actions { align-items: flex-start; flex-direction: column; }
	.run-button { align-self: stretch; }
	.history-toolbar { gap: 4px; }
	.history-status-select { width: 116px; flex-basis: 116px; }
	.history-status-select :deep(.v-field__input) { padding-inline: 8px; font-size: 13px; }
	.history-status-select :deep(.v-field__append-inner) { padding-inline-start: 0; }
	.report-panel { padding: 18px 15px; }
	.report-header { align-items: flex-start; flex-direction: column; }
	.report-badges { justify-content: flex-start; }
	.report-orders { grid-template-columns: 1fr; }
	.page-footer { align-items: flex-start; flex-direction: column; gap: 6px; }
}

@media (max-width: 380px) {
	.supply-chain-page { padding: 16px 11px 22px !important; }
	.workspace-header { gap: 12px; }
	.header-actions { flex-wrap: wrap; gap: 8px; }
	.page-description { font-size: 13px; }
	.panel-heading { flex-wrap: wrap; gap: 10px; }
	.filter-select { max-width: none; flex: 1 1 100%; }
	.agent-live { flex-wrap: wrap; }
	.agent-live-copy { flex-basis: calc(100% - 40px); }
	.agent-live-actions { flex-direction: row; margin-left: 36px; }
	.history-item-actions { flex-wrap: wrap; }
	.history-item-top, .history-item-foot { flex-wrap: wrap; }
	.history-toolbar { width: 100%; }
	.history-status-select { flex: 1 1 auto; width: auto; }
	.report-meta { gap: 6px; }
	.citation-heading { flex-wrap: wrap; }
	.citation-heading > span { margin-left: 0; }
}

@media (prefers-reduced-transparency: reduce) {
	.metric-card, .surface-panel { background: #fff; -webkit-backdrop-filter: none; backdrop-filter: none; }
}

@media (prefers-reduced-motion: reduce) {
	.status-dot--loading, .agent-live, .agent-mode--active > span { animation: none; }
}
</style>
