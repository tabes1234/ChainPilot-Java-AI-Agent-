<template>
	<section class="decision-workspace" aria-label="自然语言供应链决策工作台">
		<article class="decision-panel decision-entry">
			<div class="decision-heading">
				<div class="decision-heading-main">
					<div class="decision-emblem"><v-icon :icon="CHAINPILOT_ROLES.planning.icon" size="21" /></div>
					<div>
						<div class="section-kicker">NATURAL LANGUAGE DECISION</div>
						<h2 class="decision-title">自然语言决策 Agent</h2>
						<p class="decision-subtitle">只读分析、建议、模拟与对比会直接调用现有 Java 工具；保存方案、选定方案和启动沙箱演练只会生成待确认提案，必须由你确认后才会执行。</p>
					</div>
				</div>
				<div class="decision-policy">
					<span><v-icon icon="mdi-database-eye-outline" size="14" />真实业务数据只读</span>
					<span><v-icon icon="mdi-shield-check-outline" size="14" />写操作需人工确认</span>
				</div>
			</div>

			<label class="decision-field-label" for="decision-question">决策请求</label>
			<v-textarea
				id="decision-question"
				ref="questionInput"
				v-model="question"
				class="decision-question"
				variant="outlined"
				rows="3"
				auto-grow
				hide-details="auto"
				maxlength="2000"
				:disabled="running"
				placeholder="例如：比较 SKU-MCU-001 补货 50、100、200 件的方案"
			/>
			<div class="decision-examples" aria-label="示例请求">
				<button
					v-for="example in examples"
					:key="example"
					type="button"
					class="example-chip"
					:disabled="running"
					@click="useExample(example)"
				>
					{{ example }}
				</button>
			</div>
			<div class="decision-entry-actions">
				<span class="decision-helper">数值、排名、订单影响与证据均来自后端工具；证据不足时任务会明确说明。</span>
				<v-btn
					class="decision-run-button"
					color="primary"
					prepend-icon="mdi-play"
					:loading="running"
					:disabled="!question.trim() || running"
					@click="submit"
				>
					{{ running ? '执行中' : '运行决策任务' }}
				</v-btn>
			</div>

			<div v-if="running && activeTask" class="decision-task-strip">
				<v-progress-circular indeterminate size="18" width="2" color="primary" />
				<div>
					<strong>{{ activeTask.currentStep || '任务已进入队列' }}</strong>
					<span>任务 {{ activeTask.taskId }} · {{ taskStatusLabel(activeTask.status) }}</span>
				</div>
				<button type="button" class="decision-link" @click="emitTimeline">查看执行轨迹</button>
			</div>
			<v-alert v-if="error" type="error" variant="tonal" density="compact" class="decision-alert">
				<span>{{ error }}</span>
				<button v-if="activeTask" type="button" class="decision-link decision-alert-link" @click="emitTimeline">查看执行轨迹</button>
			</v-alert>
		</article>

		<article v-if="decision" id="decision-result" class="decision-panel decision-result" aria-label="决策结果">
			<div class="result-heading">
				<div>
					<div class="section-kicker">AGENT RESULT</div>
					<h3 class="decision-title decision-title--small">{{ decisionIntentLabel(decision.intent) }}</h3>
					<p class="result-question">{{ decision.question }}</p>
				</div>
				<div class="result-badges">
					<v-chip size="x-small" variant="tonal" :color="narrativeSourceColor(decision.narrativeSource)">
						{{ narrativeSourceLabel(decision.narrativeSource) }}
					</v-chip>
					<v-chip size="x-small" variant="outlined" :color="decision.realDataMutated ? 'error' : 'success'">
						{{ decision.realDataMutated ? '真实数据已变更' : '真实数据未变更' }}
					</v-chip>
					<button type="button" class="decision-link" @click="emitTimeline">执行轨迹</button>
				</div>
			</div>

			<div class="result-layers" aria-label="结果来源分层">
				<span v-if="decision.comparison || decision.simulation" class="result-layer" :style="roleCssVars('simulation')"><v-icon :icon="CHAINPILOT_ROLES.simulation.icon" size="13" />Java 确定性计算</span>
				<span class="result-layer" :style="roleCssVars('data')"><v-icon :icon="CHAINPILOT_ROLES.data.icon" size="13" />真实工具结果与证据</span>
				<span class="result-layer" :style="roleCssVars('planning')"><v-icon :icon="CHAINPILOT_ROLES.planning.icon" size="13" />{{ narrativeSourceLabel(decision.narrativeSource) }}</span>
			</div>

			<div v-if="decision.clarificationRequired" class="clarification-box">
				<div class="clarification-heading"><v-icon icon="mdi-help-circle-outline" size="17" />需要补充信息</div>
				<ul><li v-for="item in decision.clarifications" :key="item">{{ item }}</li></ul>
				<p>Agent 没有套用默认 SKU、供应商或数量。请在输入框中补充上述信息后重新运行。</p>
				<button type="button" class="decision-link" @click="focusQuestion">返回输入框补充</button>
			</div>

			<div class="summary-box">
				<div class="summary-label">
					<v-icon :icon="decision.modelAssisted ? 'mdi-compass-outline' : 'mdi-rule'" size="15" />
					{{ decision.modelAssisted ? '模型解释（数值来自真实工具结果）' : '确定性规则摘要（模型未参与结论）' }}
				</div>
				<p class="summary-text">{{ decision.summary }}</p>
				<div v-if="decision.fallbackUsed" class="summary-warning">
					<v-icon icon="mdi-alert-outline" size="14" />本次回答使用了规则降级；模型失败或返回空内容，业务数值仍来自 Java 工具。
				</div>
			</div>

			<div class="evidence-strip">
				<div class="evidence-block">
					<strong>实际调用的工具</strong>
					<div v-if="decision.toolCalls.length" class="token-list">
						<span v-for="tool in decision.toolCalls" :key="tool" class="evidence-token">{{ tool }}</span>
					</div>
					<span v-else class="muted-text">本次任务没有调用业务工具。</span>
				</div>
				<div v-if="decision.evidenceNotes.length" class="evidence-block">
					<strong>证据说明</strong>
					<ul class="evidence-list"><li v-for="note in decision.evidenceNotes" :key="note">{{ note }}</li></ul>
				</div>
				<div v-if="decision.executionPlan.length" class="evidence-block">
					<strong>执行计划</strong>
					<ol class="evidence-list"><li v-for="step in decision.executionPlan" :key="step">{{ step }}</li></ol>
				</div>
			</div>

			<section v-if="decision.comparison" class="result-section comparison-section" aria-label="确定性多方案对比">
				<div class="result-section-heading">
					<div class="result-heading-main"><ChainPilotRoleIcon role="simulation" :size="15" /><div>
						<div class="section-kicker">DETERMINISTIC JAVA COMPARISON</div>
						<h4>{{ decision.comparison.title }}</h4>
					</div></div>
					<span class="isolation-tag">共享基线 · 候选 {{ decision.comparison.candidates.length }} 个</span>
				</div>
				<div class="comparison-recommendation">
					<div class="recommendation-top">
						<v-chip size="x-small" variant="tonal" color="success">{{ comparisonOutcomeLabel(decision.comparison.recommendation.outcome) }}</v-chip>
						<strong>{{ decision.comparison.recommendation.recommendedLabel }}</strong>
						<span class="rank-pill">排名 {{ decision.comparison.recommendation.recommendedRank ?? '—' }}</span>
					</div>
					<p>{{ decision.comparison.recommendation.summary }}</p>
					<div v-if="decision.comparison.recommendation.keyDeltas.length" class="token-list">
						<span v-for="delta in decision.comparison.recommendation.keyDeltas" :key="delta" class="metric-token">{{ delta }}</span>
					</div>
					<div class="recommendation-detail-grid">
						<div v-if="decision.comparison.recommendation.reasons.length">
							<strong>推荐理由</strong>
							<ul><li v-for="reason in decision.comparison.recommendation.reasons" :key="reason">{{ reason }}</li></ul>
						</div>
						<div v-if="decision.comparison.recommendation.tradeoffs.length">
							<strong>取舍与代价</strong>
							<ul><li v-for="tradeoff in decision.comparison.recommendation.tradeoffs" :key="tradeoff">{{ tradeoff }}</li></ul>
						</div>
						<div v-if="decision.comparison.recommendation.unknowns.length">
							<strong>未知项</strong>
							<ul><li v-for="item in decision.comparison.recommendation.unknowns" :key="item">{{ item }}</li></ul>
						</div>
						<div v-if="decision.comparison.recommendation.manualConfirmation.length">
							<strong>需人工确认</strong>
							<ul><li v-for="item in decision.comparison.recommendation.manualConfirmation" :key="item">{{ item }}</li></ul>
						</div>
					</div>
				</div>
				<div class="baseline-line">
					<span>基线风险订单 <strong>{{ decision.comparison.baseline.atRiskOrders }}</strong></span>
					<span>高风险 <strong>{{ decision.comparison.baseline.highRiskOrders }}</strong></span>
					<span>缺口订单 <strong>{{ decision.comparison.baseline.ordersWithShortage }}</strong></span>
					<span>缺口总量 <strong>{{ decision.comparison.baseline.totalShortageUnits }}</strong></span>
					<span>目标 ATP <strong>{{ decision.comparison.baseline.targetSkuAvailableToPromise ?? '—' }}</strong></span>
				</div>
				<div class="candidate-table-wrap">
					<table class="decision-table">
						<thead><tr><th>候选</th><th>风险订单Δ</th><th>高风险Δ</th><th>缺口订单Δ</th><th>缺口总量Δ</th><th>风险评分Δ</th><th>目标 ATPΔ</th><th>排名</th></tr></thead>
						<tbody>
							<tr v-for="candidate in decision.comparison.candidates" :key="candidate.candidateId" :class="{ 'row-recommended': candidate.candidateId === decision.comparison.recommendation.recommendedCandidateId }">
								<td><strong>{{ candidate.label }}</strong><small>{{ candidate.candidateId }}</small><small>{{ candidateParameter(candidate) }}</small></td>
								<td>{{ signed(candidate.simulation.delta.atRiskOrders) }}</td>
								<td>{{ signed(candidate.simulation.delta.highRiskOrders) }}</td>
								<td>{{ signed(candidate.simulation.delta.ordersWithShortage) }}</td>
								<td>{{ signed(candidate.simulation.delta.totalShortageUnits) }}</td>
								<td>{{ signed(candidate.simulation.delta.totalRiskScore) }}</td>
								<td>{{ signed(candidate.simulation.delta.targetSkuAvailableToPromise) }}</td>
								<td><span class="rank-pill">{{ rankFor(candidate.candidateId) }}</span></td>
							</tr>
						</tbody>
					</table>
				</div>
				<p class="table-note">数值由 Java 对比引擎按共享基线计算；“模拟后 − 基线”中风险指标负值表示改善，目标 ATP 正值表示提升。不是模型生成的数字。</p>
				<div class="ranking-cards">
					<article v-for="ranking in sortedRankings" :key="ranking.candidateId" class="ranking-card" :class="{ 'ranking-card--recommended': ranking.recommended }">
						<div class="ranking-top">
							<span class="rank-pill">{{ ranking.rank === null ? '不可比' : `#${ranking.rank}` }}</span>
							<strong>{{ ranking.label }}</strong>
							<span class="ranking-id">{{ ranking.candidateId }}</span>
						</div>
						<p class="ranking-basis">{{ ranking.rankingBasis }}</p>
						<div v-if="ranking.keyDeltas.length" class="token-list"><span v-for="delta in ranking.keyDeltas" :key="delta" class="metric-token">{{ delta }}</span></div>
						<details v-if="ranking.reasons.length || ranking.tradeoffs.length">
							<summary>查看排名依据与取舍</summary>
							<ul v-if="ranking.reasons.length"><li v-for="reason in ranking.reasons" :key="reason">{{ reason }}</li></ul>
							<ul v-if="ranking.tradeoffs.length"><li v-for="tradeoff in ranking.tradeoffs" :key="tradeoff">{{ tradeoff }}</li></ul>
						</details>
					</article>
				</div>
				<p class="isolation-note"><v-icon icon="mdi-database-lock-outline" size="14" />{{ decision.comparison.disclaimer }}</p>
			</section>

			<section v-if="decision.simulation" class="result-section" aria-label="确定性情景模拟">
				<div class="result-section-heading">
					<div class="result-heading-main"><ChainPilotRoleIcon role="simulation" :size="15" /><div><div class="section-kicker">DETERMINISTIC JAVA SIMULATION</div><h4>{{ scenarioLabel(decision.simulation.scenario) }}模拟</h4></div></div>
					<span class="isolation-tag">{{ decision.simulation.realDataMutated ? '真实数据已变更' : '真实数据未变更' }}</span>
				</div>
				<div class="baseline-line">
					<span>对象 <strong>{{ decision.simulation.sku || decision.simulation.supplierId || '—' }}</strong></span>
					<span>参数 <strong>{{ decision.simulation.replenishmentQuantity ? `${decision.simulation.replenishmentQuantity} 件` : `${decision.simulation.additionalDelayDays ?? '—'} 天` }}</strong></span>
					<span>基线风险订单 <strong>{{ decision.simulation.baseline.atRiskOrders }}</strong></span>
					<span>模拟后 <strong>{{ decision.simulation.simulated.atRiskOrders }}</strong></span>
					<span>变化 <strong>{{ signed(decision.simulation.delta.atRiskOrders) }}</strong></span>
					<span>目标 ATPΔ <strong>{{ signed(decision.simulation.delta.targetSkuAvailableToPromise) }}</strong></span>
				</div>
				<div class="simulation-detail-grid">
					<div v-if="decision.simulation.affectedOrders.length">
						<strong>受影响订单（{{ decision.simulation.affectedOrders.length }}）</strong>
						<ul><li v-for="order in decision.simulation.affectedOrders" :key="order.orderId">{{ order.orderId }} · {{ order.sku }} · 风险 {{ order.baselineRiskScore }} → {{ order.simulatedRiskScore }}</li></ul>
					</div>
					<div v-if="decision.simulation.assumptions.length"><strong>规则假设</strong><ul><li v-for="item in decision.simulation.assumptions" :key="item">{{ item }}</li></ul></div>
					<div v-if="decision.simulation.limitations.length"><strong>局限</strong><ul><li v-for="item in decision.simulation.limitations" :key="item">{{ item }}</li></ul></div>
				</div>
				<p class="isolation-note"><v-icon icon="mdi-information-outline" size="14" />{{ decision.simulation.disclaimer }}</p>
			</section>

			<section v-if="decision.atRiskOrders.length" class="result-section" aria-label="风险订单证据">
				<div class="result-section-heading">
					<div class="result-heading-main"><ChainPilotRoleIcon role="risk" :size="15" /><div><div class="section-kicker">BUSINESS EVIDENCE</div><h4>风险订单证据</h4></div></div>
					<span class="isolation-tag">{{ decision.atRiskOrders.length }} 个订单</span>
				</div>
				<div class="order-evidence-list">
					<article v-for="item in decision.atRiskOrders" :key="item.order.orderId" class="order-evidence">
						<div class="order-evidence-top"><strong>{{ item.order.orderId }}</strong><span>{{ item.order.sku }} · 风险 {{ item.riskScore }} · 缺口 {{ item.inventoryShortage }}</span></div>
						<ul v-if="item.riskFactors.length"><li v-for="factor in item.riskFactors" :key="factor">{{ factor }}</li></ul>
					</article>
				</div>
			</section>

			<section v-if="decision.recommendations.length" class="result-section" aria-label="处置建议">
				<div class="result-section-heading">
					<div class="result-heading-main"><ChainPilotRoleIcon role="risk" :size="15" /><div><div class="section-kicker">RULE-BASED RECOMMENDATIONS</div><h4>确定性处置建议</h4></div></div>
					<span class="isolation-tag">{{ decision.recommendations.length }} 条</span>
				</div>
				<div class="recommendation-list">
					<article v-for="recommendation in decision.recommendations" :key="recommendation.recommendationId" class="decision-recommendation">
						<div class="recommendation-top"><v-chip size="x-small" variant="tonal" :color="riskColor(recommendation.riskLevel)">{{ recommendation.riskLevel }}</v-chip><strong>{{ recommendation.title }}</strong><span class="rank-pill">{{ recommendation.recommendationId }}</span></div>
						<p>{{ recommendation.action }}</p>
						<p class="muted-text">{{ recommendation.rationale }}</p>
						<div v-if="recommendation.businessBasis.length" class="token-list"><span v-for="basis in recommendation.businessBasis" :key="basis" class="evidence-token">{{ basis }}</span></div>
					</article>
				</div>
			</section>

			<section v-if="proposals.length || hasPendingProposals" id="decision-proposals" class="result-section proposal-section" aria-label="待人工确认提案">
				<div class="result-section-heading">
					<div class="result-heading-main"><ChainPilotRoleIcon role="confirmation" :size="15" /><div><div class="section-kicker section-kicker--warning">HUMAN CONFIRMATION REQUIRED</div><h4>待确认行动提案</h4></div></div>
					<div class="proposal-heading-actions">
						<span class="isolation-tag">{{ proposals.length }} 个提案</span>
						<button type="button" class="decision-link" :disabled="running" @click="refreshProposals">刷新提案</button>
					</div>
				</div>
				<p class="proposal-intro">以下操作没有执行。确认时后端会重新校验对象状态、参数、执行资格与幂等键；任何内容变化都会使旧确认失效。</p>
				<v-alert v-if="proposalNotice" type="success" variant="tonal" density="compact" class="decision-alert">{{ proposalNotice }}</v-alert>
				<v-alert v-if="proposalError" type="error" variant="tonal" density="compact" class="decision-alert">{{ proposalError }}</v-alert>
				<div class="proposal-list">
					<article v-for="proposal in proposals" :key="proposal.proposalId" class="proposal-card" :class="`proposal-card--${proposal.status.toLowerCase()}`">
						<div class="proposal-top">
							<div>
								<div class="proposal-title-row">
									<strong>{{ proposal.title }}</strong>
									<v-chip size="x-small" variant="tonal" :color="proposalStatusColor(proposal.status)">{{ proposalStatusLabel(proposal.status) }}</v-chip>
								</div>
								<div class="proposal-meta">
									<span>{{ proposalActionLabel(proposal.actionType) }}</span>
									<span>目标 {{ proposal.targetId }}</span>
									<span>提案 {{ proposal.proposalId }}</span>
								</div>
							</div>
							<span class="proposal-expiry">有效期至 {{ formatTime(proposal.expiresAt) }}</span>
						</div>
						<div class="proposal-body">
							<div class="parameter-grid">
								<div v-for="(value, key) in proposal.parameters" :key="key" class="parameter-item">
									<span>{{ key }}</span><strong>{{ parameterLabel(value) }}</strong>
								</div>
							</div>
							<div v-if="proposal.evidence.length" class="proposal-evidence">
								<strong>提案依据</strong>
								<ul><li v-for="item in proposal.evidence" :key="item">{{ item }}</li></ul>
							</div>
							<div class="content-hash"><v-icon icon="mdi-fingerprint" size="14" />内容哈希 {{ shortHash(proposal.contentHash) }}</div>
							<div v-if="proposal.status === 'CONFIRMED' && proposal.result" class="proposal-result">
								<strong><v-icon icon="mdi-check-decagram-outline" size="14" />后端已确认并执行</strong>
								<div class="result-value-grid">
									<div v-for="(value, key) in proposal.result" :key="key"><span>{{ key }}</span><strong>{{ parameterLabel(value) }}</strong></div>
								</div>
							</div>
							<div v-if="proposal.status === 'FAILED' && proposal.errorMessage" class="proposal-failure">
								<strong>执行失败</strong><span>{{ proposal.errorMessage }}</span>
							</div>
							<div v-if="proposal.status === 'PENDING'" class="proposal-actions">
								<span class="proposal-action-note">确认后才会调用现有业务服务；取消不会产生任何变更。</span>
								<div class="proposal-buttons">
									<v-btn size="small" variant="text" color="primary" :disabled="isProposalBusy(proposal.proposalId)" @click="editProposal(proposal)">返回修改参数</v-btn>
									<v-btn size="small" variant="tonal" color="error" :loading="isBusy(proposal.proposalId, 'CANCEL')" :disabled="isProposalBusy(proposal.proposalId)" @click="cancel(proposal.proposalId)">取消提案</v-btn>
									<v-btn size="small" color="success" :loading="isBusy(proposal.proposalId, 'CONFIRM')" :disabled="isProposalBusy(proposal.proposalId) || isExpired(proposal)" @click="confirm(proposal.proposalId)">确认执行</v-btn>
								</div>
							</div>
						</div>
					</article>
				</div>
			</section>

			<section v-if="decision.plan || decision.execution || decision.sandboxEligibility" class="result-section" aria-label="关联方案与演练">
				<div class="result-section-heading"><div class="result-heading-main"><ChainPilotRoleIcon role="execution" :size="15" /><div><div class="section-kicker">LINKED RECORDS</div><h4>关联方案 / 沙箱演练</h4></div></div></div>
				<div v-if="decision.plan" class="linked-record">
					<strong>处置方案 {{ decision.plan.planId }}</strong>
					<span>{{ decision.plan.title }} · {{ decision.plan.status }} · 建议 {{ decision.plan.recommendations.length }} 条</span>
				</div>
				<div v-if="decision.sandboxEligibility" class="linked-record">
					<strong>沙箱执行资格</strong>
					<span>{{ decision.sandboxEligibility.eligible ? '符合条件' : '不符合条件' }} · {{ decision.sandboxEligibility.reason || '已通过前置校验' }}</span>
				</div>
				<div v-if="decision.execution" class="linked-record">
					<strong>沙箱演练 {{ decision.execution.executionId }}</strong>
					<span>{{ decision.execution.status }} · {{ decision.execution.steps.length }} 步 · 真实数据未变更：{{ decision.execution.realDataMutated ? '否' : '是' }}</span>
				</div>
			</section>

			<section v-if="decision.citations.length" class="result-section" aria-label="SOP 来源">
				<div class="result-section-heading"><div class="result-heading-main"><ChainPilotRoleIcon role="data" :size="15" /><div><div class="section-kicker">KNOWLEDGE CITATIONS</div><h4>SOP 来源</h4></div></div><span class="isolation-tag">{{ decision.citations.length }} 条</span></div>
				<div class="citation-list">
					<details v-for="citation in decision.citations" :key="citation.sourceId" class="citation-item">
						<summary>{{ citation.title }} <span>{{ citation.sourceId }}</span></summary>
						<p>{{ citation.excerpt }}</p>
					</details>
				</div>
			</section>
		</article>

		<article class="decision-panel decision-history" aria-label="决策任务历史">
			<div class="history-heading">
				<div><div class="section-kicker">DECISION HISTORY</div><h3 class="decision-title decision-title--small">决策任务历史</h3></div>
				<div class="history-tools">
					<v-select :model-value="historyStatus" :items="DECISION_HISTORY_STATUS_OPTIONS" item-title="title" item-value="value" aria-label="决策任务状态筛选" density="compact" variant="outlined" hide-details class="history-status" @update:model-value="setHistoryStatus" />
					<v-btn icon="mdi-refresh" size="x-small" variant="text" color="primary" aria-label="刷新决策任务历史" :loading="historyLoading" @click="loadHistory" />
				</div>
			</div>
			<div class="history-list">
				<button v-for="task in history" :key="task.taskId" type="button" class="history-item" :class="{ 'history-item--current': task.taskId === decision?.taskId }" :title="task.question" :disabled="running || !!openingTaskId" @click="openTask(task.taskId)">
					<div class="history-item-top"><v-chip size="x-small" variant="tonal" :color="taskStatusColor(task.status)">{{ taskStatusLabel(task.status) }}</v-chip><span>{{ formatTime(task.updatedAt) }}</span></div>
					<p>{{ task.question }}</p>
					<div class="history-item-foot"><span>{{ task.currentStep }}</span><span>{{ task.taskId }}</span></div>
				</button>
				<div v-if="historyError" class="history-empty history-empty--error">{{ historyError }}</div>
				<div v-else-if="historyLoading && history.length === 0" class="history-empty">正在加载决策任务历史…</div>
				<div v-else-if="history.length === 0" class="history-empty">{{ historyStatus === 'ALL' ? '还没有自然语言决策任务。运行一次后会保存在这里。' : '当前状态下没有决策任务。' }}</div>
			</div>
			<div class="history-pagination">
				<span>{{ historyTotalPages > 0 ? `共 ${historyTotalElements} 条 · 第 ${historyPage} / ${historyTotalPages} 页` : historyLoaded && !historyLoading ? '共 0 条' : '正在加载分页信息…' }}</span>
				<div>
					<v-btn icon="mdi-chevron-left" size="x-small" variant="text" color="primary" aria-label="上一页" :disabled="!canPreviousHistoryPage" @click="previousHistoryPage" />
					<v-btn icon="mdi-chevron-right" size="x-small" variant="text" color="primary" aria-label="下一页" :disabled="!canNextHistoryPage" @click="nextHistoryPage" />
				</div>
			</div>
		</article>
	</section>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue';
import { useSupplyChainDecision } from '~/composables/useSupplyChainDecision';
import {
	DECISION_HISTORY_STATUS_OPTIONS,
	decisionIntentLabel,
	isProposalExpired,
	narrativeSourceColor,
	narrativeSourceLabel,
	parameterLabel,
	proposalActionLabel,
	proposalStatusColor,
	proposalStatusLabel,
	type DecisionProposal,
} from '~/services/supplyChainDecision';
import type { DisposalScenarioCandidate, DisposalScenarioRanking } from '~/services/supplyChainDisposal';
import { roleCssVars, CHAINPILOT_ROLES } from '~/utils/supplyChainAgentRoles';
import type { DecisionWorkspaceSnapshot } from '~/utils/supplyChainWorkbench';

const emit = defineEmits<{
	(event: 'open-timeline', target: { taskId: string; status: string }): void;
	(event: 'proposal-confirmed', proposal: DecisionProposal): void;
	(event: 'state-change', snapshot: DecisionWorkspaceSnapshot): void;
}>();

const examples = [
	'比较 SKU-MCU-001 补货 50、100、200 件的方案',
	'比较供应商 SUP-001 延期 3、5、7 天的方案',
	'查询 SKU-MCU-001 的处置建议和业务证据',
	'查看高风险订单，并说明库存和供应商依据',
];

const question = ref(examples[0] as string);
const questionInput = ref<{ focus?: () => void } | null>(null);
const {
	activeTask,
	decision,
	proposals,
	running,
	openingTaskId,
	error,
	proposalNotice,
	proposalError,
	busyProposalKey,
	history,
	historyLoading,
	historyError,
	historyStatus,
	historyPage,
	historyTotalElements,
	historyTotalPages,
	historyLoaded,
	canPreviousHistoryPage,
	canNextHistoryPage,
	isProposalBusy,
	run,
	openTask: openDecisionTask,
	refreshProposals: refreshTaskProposals,
	confirmProposal,
	cancelProposal,
	loadHistory,
	setHistoryStatus,
	previousHistoryPage,
	nextHistoryPage,
} = useSupplyChainDecision(undefined, {
	onProposalConfirmed: (updated) => emit('proposal-confirmed', updated),
});

const hasPendingProposals = computed(() => proposals.value.some((item) => item.status === 'PENDING'));

watch(
	[activeTask, running, decision, proposals, error, question],
	() => {
		const task = activeTask.value
			? { ...activeTask.value }
			: decision.value
				? {
						taskId: decision.value.taskId,
						status: decision.value.status,
						currentStep: '',
						validationAttempts: 0,
						error: null,
					}
				: null;
		emit('state-change', {
			task,
			question: decision.value?.question || question.value,
			running: running.value,
			decision: decision.value,
			proposals: proposals.value.map((proposal) => ({ ...proposal })),
			error: error.value,
		});
	},
	{ immediate: true },
);

const sortedRankings = computed(() => {
	const rankings = decision.value?.comparison?.rankings ?? [];
	return [...rankings].sort((left, right) => {
		if (left.rank === null) {
			return 1;
		}
		if (right.rank === null) {
			return -1;
		}
		return left.rank - right.rank;
	});
});

function useExample(example: string) {
	question.value = example;
	focusQuestion();
}

function focusQuestion() {
	void nextTick().then(() => questionInput.value?.focus?.());
}

async function submit() {
	const result = await run(question.value);
	if (result) {
		focusQuestion();
	}
}

async function openTask(taskId: string) {
	await openDecisionTask(taskId);
}

async function refreshProposals() {
	await refreshTaskProposals();
}

async function confirm(proposalId: string) {
	await confirmProposal(proposalId);
}

async function cancel(proposalId: string) {
	await cancelProposal(proposalId);
}

function isBusy(proposalId: string, action: 'CONFIRM' | 'CANCEL') {
	return busyProposalKey.value === `${proposalId}:${action}`;
}

function isExpired(proposal: DecisionProposal) {
	return isProposalExpired(proposal);
}

function editProposal(proposal: DecisionProposal) {
	const parameters = proposal.parameters || {};
	const comparisonId = parameterLabel(parameters.comparisonId);
	const planId = parameterLabel(parameters.planId);
	const recommendationId = parameterLabel(parameters.recommendationId);
	if (proposal.actionType === 'SAVE_CANDIDATE_AS_PLAN') {
		question.value = `将对比 ${comparisonId} 的候选 ${proposal.targetId} 保存为处置方案`;
	} else if (proposal.actionType === 'LINK_CANDIDATE_TO_PLAN') {
		question.value = `将对比 ${comparisonId} 的候选 ${proposal.targetId} 关联到方案 ${planId}`;
	} else if (proposal.actionType === 'SELECT_PLAN') {
		question.value = `选定方案 ${proposal.targetId}，采用建议 ${recommendationId}`;
	} else {
		question.value = `启动方案 ${proposal.targetId} 的沙箱演练`;
	}
	focusQuestion();
}

function emitTimeline() {
	const taskId = activeTask.value?.taskId ?? decision.value?.taskId;
	if (!taskId) {
		return;
	}
	emit('open-timeline', {
		taskId,
		status: activeTask.value?.status ?? decision.value?.status ?? 'COMPLETED',
	});
}

const taskStatusLabels: Record<string, string> = {
	COMPLETED: '已完成',
	DONE: '已完成',
	RUNNING: '执行中',
	ACCEPTED: '排队中',
	PENDING: '等待中',
	FAILED: '失败',
	CANCELLED: '已取消',
};

function taskStatusLabel(status: string) {
	return taskStatusLabels[status] ?? status;
}

function taskStatusColor(status: string) {
	if (status === 'COMPLETED' || status === 'DONE') return 'success';
	if (status === 'FAILED') return 'error';
	if (status === 'CANCELLED') return 'grey';
	return 'primary';
}

function riskColor(level: string) {
	return level === 'HIGH' ? 'error' : level === 'MEDIUM' ? 'warning' : 'success';
}

function scenarioLabel(scenario: string) {
	return scenario === 'INVENTORY_REPLENISHMENT' ? '库存补货' : '供应商延期';
}

function comparisonOutcomeLabel(outcome: string) {
	if (outcome === 'RECOMMENDED') {
		return '确定性排名推荐';
	}
	if (outcome === 'TIE_BREAK') {
		return '并列指标 · 最小干预优先';
	}
	return '候选不可比';
}

function candidateParameter(candidate: DisposalScenarioCandidate) {
	if (candidate.scenario === 'INVENTORY_REPLENISHMENT') {
		return `补货 ${candidate.replenishmentQuantity ?? '—'} 件`;
	}
	return `延期 ${candidate.additionalDelayDays ?? '—'} 天`;
}

function rankFor(candidateId: string) {
	const ranking = decision.value?.comparison?.rankings.find((item: DisposalScenarioRanking) => item.candidateId === candidateId);
	return ranking?.rank === null || ranking?.rank === undefined ? '—' : ranking.rank;
}

function signed(value: number | null | undefined) {
	if (value === null || value === undefined) {
		return '—';
	}
	return value > 0 ? `+${value}` : String(value);
}

function shortHash(hash: string) {
	return hash.length > 18 ? `${hash.slice(0, 12)}…${hash.slice(-4)}` : hash;
}

function formatTime(value: string) {
	const date = new Date(value);
	return Number.isNaN(date.getTime())
		? value
		: date.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
}

onMounted(() => {
	void loadHistory();
});
</script>

<style scoped>
.decision-workspace {
	--decision-ink: var(--cp-ink);
	--decision-muted: var(--cp-muted);
	--decision-line: var(--cp-line);
	--decision-blue: var(--cp-primary);
	display: flex;
	flex-direction: column;
	gap: 16px;
	margin-bottom: 28px;
}

.decision-panel {
	min-width: 0;
	padding: 22px 23px;
	border: 1px solid var(--cp-line-soft);
	border-radius: var(--cp-radius-lg);
	background: var(--cp-surface-glass);
	box-shadow: var(--cp-shadow-soft);
	-webkit-backdrop-filter: blur(12px) saturate(1.08);
	backdrop-filter: blur(12px) saturate(1.08);
}

.decision-result {
	animation: decision-result-in var(--cp-duration-slow) var(--cp-ease-out) both;
}

.decision-entry {
	background:
		radial-gradient(ellipse 34rem 16rem at 0% 0%, rgba(228, 241, 255, 0.9), transparent 70%),
		rgba(255, 255, 255, 0.82);
}

.decision-heading,
.result-heading,
.history-heading,
.result-section-heading,
.proposal-top,
.ranking-top,
.recommendation-top,
.order-evidence-top {
	display: flex;
	align-items: flex-start;
	justify-content: space-between;
	gap: 14px;
}

.decision-heading-main {
	display: flex;
	gap: 12px;
	min-width: 0;
}

.decision-emblem {
	display: grid;
	width: 42px;
	height: 42px;
	flex: 0 0 42px;
	place-items: center;
	color: var(--cp-role-planning);
	background: linear-gradient(145deg, var(--cp-role-planning-soft), color-mix(in srgb, var(--cp-role-planning-soft) 68%, #fff));
	border: 1px solid color-mix(in srgb, var(--cp-role-planning) 14%, transparent);
	border-radius: 13px;
}

.section-kicker {
	color: var(--cp-muted);
	font-size: 13px;
	font-weight: 750;
	letter-spacing: 0.14em;
	line-height: 1.5;
}

.section-kicker--warning {
	color: var(--cp-role-confirmation-ink);
}

.decision-title {
	margin-top: 4px;
	color: var(--cp-ink-strong);
	font-size: 19px;
	font-weight: 720;
	letter-spacing: -0.02em;
	line-height: 1.35;
}

.decision-title--small {
	font-size: 16px;
}

.decision-subtitle {
	max-width: 900px;
	margin: 7px 0 0;
	color: var(--decision-muted);
	font-size: 12px;
	line-height: 1.7;
}

.decision-policy {
	display: flex;
	flex-direction: column;
	gap: 6px;
	flex: 0 0 auto;
	color: var(--cp-role-execution-ink);
	font-size: 13px;
}

.decision-policy span {
	display: inline-flex;
	align-items: center;
	gap: 5px;
	padding: 4px 8px;
	background: var(--cp-role-execution-soft);
	border: 1px solid color-mix(in srgb, var(--cp-role-execution) 14%, transparent);
	border-radius: var(--cp-radius-pill);
}

.decision-field-label {
	display: block;
	margin: 18px 0 8px;
	color: #5d748f;
	font-size: 13px;
	font-weight: 650;
}

.decision-question :deep(.v-field) {
	border-radius: 12px;
	background: rgba(249, 252, 255, 0.9);
}

.decision-question :deep(textarea) {
	color: #405a78;
	font-size: 12px;
	line-height: 1.7;
}

.decision-examples {
	display: flex;
	flex-wrap: wrap;
	gap: 7px;
	margin-top: 9px;
}

.example-chip {
	padding: 5px 9px;
	color: #557295;
	font: inherit;
	font-size: 13px;
	line-height: 1.4;
	text-align: left;
	background: #f4f8fe;
	border: 1px solid #e2ecf8;
	border-radius: 8px;
	cursor: pointer;
}

.example-chip:hover,
.example-chip:focus-visible {
	color: #315fad;
	background: #edf5ff;
	border-color: #cfe1f6;
	outline: none;
}

.example-chip:disabled {
	cursor: not-allowed;
	opacity: 0.6;
}

.decision-entry-actions {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 14px;
	margin-top: 13px;
}

.decision-helper {
	color: #66707d;
	font-size: 13px;
	line-height: 1.55;
}

.decision-run-button {
	min-height: 39px;
	padding-inline: 16px;
	color: #fff !important;
	border-radius: 10px;
	background: #3f6fb5 !important;
	box-shadow: 0 5px 12px rgba(63, 111, 181, 0.2);
	font-size: 12px;
	font-weight: 650;
}

.decision-task-strip {
	display: flex;
	align-items: center;
	gap: 10px;
	margin-top: 14px;
	padding: 10px 12px;
	color: #59738e;
	background: #f3f8ff;
	border: 1px solid #e3eefc;
	border-radius: 10px;
}

.decision-task-strip div {
	display: flex;
	flex: 1;
	flex-direction: column;
	gap: 2px;
	min-width: 0;
}

.decision-task-strip strong {
	font-size: 13px;
}

.decision-task-strip span {
	color: #657384;
	font-size: 13px;
	overflow-wrap: anywhere;
}

.decision-alert {
	margin-top: 12px;
	border-radius: 10px;
	font-size: 13px;
}

.decision-alert-link {
	margin-left: 10px;
}

.decision-link {
	padding: 0;
	color: #54749e;
	font: inherit;
	font-size: 13px;
	font-weight: 650;
	line-height: 1.5;
	background: none;
	border: 0;
	cursor: pointer;
}

.decision-link:hover,
.decision-link:focus-visible {
	color: #315fad;
	text-decoration: underline;
	outline: none;
}

.decision-link:disabled {
	cursor: not-allowed;
	opacity: 0.55;
}

.result-heading {
	align-items: flex-start;
}

.result-question {
	margin: 5px 0 0;
	color: #647384;
	font-size: 13px;
	line-height: 1.6;
	overflow-wrap: anywhere;
}

.result-badges {
	display: flex;
	flex-wrap: wrap;
	align-items: center;
	justify-content: flex-end;
	gap: 7px;
}

.result-layers {
	display: flex;
	flex-wrap: wrap;
	gap: 6px;
	margin-top: 14px;
}

.result-layer {
	display: inline-flex;
	align-items: center;
	gap: 4px;
	padding: 3px 8px;
	color: var(--cp-role-ink);
	background: var(--cp-role-soft);
	border: 1px solid color-mix(in srgb, var(--cp-role-color) 15%, transparent);
	border-radius: var(--cp-radius-pill);
	font-size: 13px;
	font-weight: 650;
	line-height: 1.5;
}

.clarification-box {
	margin-top: 16px;
	padding: 13px 15px;
	color: #856c44;
	background: #fffaf0;
	border: 1px solid #f2e5cc;
	border-radius: 11px;
	font-size: 13px;
	line-height: 1.7;
}

.clarification-heading {
	display: flex;
	align-items: center;
	gap: 6px;
	margin-bottom: 6px;
	font-size: 12px;
	font-weight: 700;
}

.clarification-box ul {
	margin: 0 0 7px;
	padding-left: 18px;
}

.clarification-box p {
	margin: 0 0 6px;
	color: #7e6e53;
}

.summary-box {
	margin-top: 16px;
	padding: 15px 17px;
	background: rgba(239, 246, 255, 0.7);
	border: 1px solid #e2edfa;
	border-radius: 12px;
}

.summary-label {
	display: flex;
	align-items: center;
	gap: 6px;
	color: #577392;
	font-size: 13px;
	font-weight: 650;
}

.summary-text {
	margin: 9px 0 0;
	color: #4d6887;
	font-size: 13px;
	line-height: 1.8;
	white-space: pre-wrap;
	overflow-wrap: anywhere;
}

.summary-warning {
	display: flex;
	align-items: flex-start;
	gap: 6px;
	margin-top: 10px;
	padding-top: 9px;
	color: #8c6c3c;
	border-top: 1px dashed #e4d9c2;
	font-size: 13px;
	line-height: 1.6;
}

.evidence-strip {
	display: grid;
	grid-template-columns: repeat(3, minmax(0, 1fr));
	gap: 12px;
	margin-top: 14px;
}

.evidence-block {
	min-width: 0;
	padding: 12px 13px;
	background: rgba(249, 252, 255, 0.8);
	border: 1px solid #e8eff7;
	border-radius: 10px;
}

.evidence-block > strong {
	display: block;
	margin-bottom: 7px;
	color: #5b748e;
	font-size: 13px;
	font-weight: 700;
}

.token-list {
	display: flex;
	flex-wrap: wrap;
	gap: 5px;
	margin-top: 7px;
}

.evidence-token,
.metric-token {
	padding: 3px 7px;
	color: #557295;
	background: #f2f7fd;
	border: 1px solid #e4edf8;
	border-radius: 6px;
	font-size: 13px;
	line-height: 1.5;
	overflow-wrap: anywhere;
}

.evidence-list {
	margin: 0;
	padding-left: 16px;
	color: #627285;
	font-size: 13px;
	line-height: 1.65;
	overflow-wrap: anywhere;
}

.evidence-list li + li {
	margin-top: 3px;
}

.muted-text {
	color: #677381;
	font-size: 13px;
	line-height: 1.6;
}

.result-section {
	margin-top: 20px;
	padding-top: 18px;
	border-top: 1px solid #e9f0f8;
}

.result-section-heading {
	align-items: center;
	flex-wrap: wrap;
	margin-bottom: 12px;
}

.result-heading-main {
	display: flex;
	align-items: flex-start;
	min-width: 0;
	gap: 10px;
}

.result-heading-main :deep(.role-icon) {
	margin-top: 2px;
}

.result-section-heading h4 {
	margin-top: 3px;
	color: var(--cp-ink-2);
	font-size: 14px;
	font-weight: 700;
	line-height: 1.4;
	overflow-wrap: anywhere;
}

.isolation-tag {
	padding: 4px 8px;
	color: #627285;
	background: #f5f8fc;
	border: 1px solid #e7eef7;
	border-radius: 999px;
	font-size: 13px;
	white-space: nowrap;
}

.comparison-recommendation {
	padding: 14px 15px;
	background: linear-gradient(145deg, #f2f9f4, #f7fbf8);
	border: 1px solid #e1efe5;
	border-radius: 12px;
}

.recommendation-top {
	align-items: center;
	justify-content: flex-start;
	flex-wrap: wrap;
	gap: 8px;
}

.recommendation-top strong {
	color: #4c6a59;
	font-size: 12px;
}

.rank-pill {
	padding: 3px 7px;
	color: #587864;
	background: #edf6f0;
	border: 1px solid #dcece2;
	border-radius: 999px;
	font-size: 13px;
	font-weight: 650;
	white-space: nowrap;
}

.comparison-recommendation > p {
	margin: 9px 0 0;
	color: #5e7769;
	font-size: 13px;
	line-height: 1.7;
	white-space: pre-wrap;
}

.recommendation-detail-grid,
.simulation-detail-grid {
	display: grid;
	grid-template-columns: repeat(2, minmax(0, 1fr));
	gap: 12px;
	margin-top: 11px;
}

.recommendation-detail-grid div,
.simulation-detail-grid div {
	min-width: 0;
}

.recommendation-detail-grid strong,
.simulation-detail-grid strong {
	display: block;
	color: #607769;
	font-size: 13px;
	font-weight: 700;
}

.recommendation-detail-grid ul,
.simulation-detail-grid ul {
	margin: 5px 0 0;
	padding-left: 16px;
	color: #64746a;
	font-size: 13px;
	line-height: 1.65;
}

.simulation-detail-grid ul {
	color: #637283;
}

.simulation-detail-grid strong {
	color: #5a738c;
}

.baseline-line {
	display: flex;
	flex-wrap: wrap;
	gap: 7px 15px;
	margin-top: 11px;
	padding: 9px 11px;
	color: #647484;
	background: #f8fbfe;
	border: 1px solid #eaf1f8;
	border-radius: 8px;
	font-size: 13px;
}

.baseline-line strong {
	color: #526d8c;
	font-variant-numeric: tabular-nums;
}

.candidate-table-wrap {
	margin-top: 11px;
	overflow-x: auto;
}

.decision-table {
	width: 100%;
	min-width: 720px;
	border-collapse: collapse;
	color: #58718d;
	font-size: 13px;
}

.decision-table th {
	padding: 8px 9px;
	color: #657281;
	text-align: left;
	background: #f5f9fe;
	border-bottom: 1px solid #e8eff7;
	font-size: 13px;
	font-weight: 700;
	white-space: nowrap;
}

.decision-table td {
	padding: 9px;
	border-bottom: 1px solid #eef3f8;
	font-variant-numeric: tabular-nums;
	vertical-align: top;
}

.decision-table td strong,
.decision-table td small {
	display: block;
}

.decision-table td strong {
	color: #425c79;
	font-size: 13px;
}

.decision-table td small {
	margin-top: 2px;
	color: #66717f;
	font-size: 13px;
}

.row-recommended {
	background: #f5fbf7;
}

.table-note,
.isolation-note {
	margin: 9px 0 0;
	color: #677381;
	font-size: 13px;
	line-height: 1.6;
}

.isolation-note {
	display: flex;
	align-items: flex-start;
	gap: 5px;
}

.ranking-cards {
	display: grid;
	grid-template-columns: repeat(2, minmax(0, 1fr));
	gap: 10px;
	margin-top: 11px;
}

.ranking-card {
	min-width: 0;
	padding: 12px 13px;
	background: rgba(250, 252, 255, 0.85);
	border: 1px solid #e7eef7;
	border-radius: 10px;
}

.ranking-card--recommended {
	border-color: #cfe5d8;
	background: #f7fcf9;
}

.ranking-top {
	align-items: center;
	justify-content: flex-start;
	flex-wrap: wrap;
	gap: 6px;
}

.ranking-top strong {
	color: #4b6683;
	font-size: 13px;
}

.ranking-id {
	color: #66717f;
	font-size: 13px;
	overflow-wrap: anywhere;
}

.ranking-basis {
	margin: 7px 0 0;
	color: #637283;
	font-size: 13px;
	line-height: 1.6;
}

.ranking-card details,
.citation-item {
	margin-top: 8px;
	color: #627285;
	font-size: 13px;
}

.ranking-card summary,
.citation-item summary {
	color: #567292;
	font-weight: 650;
	cursor: pointer;
}

.ranking-card ul {
	margin: 6px 0 0;
	padding-left: 16px;
	line-height: 1.55;
}

.order-evidence-list,
.recommendation-list,
.citation-list {
	display: flex;
	flex-direction: column;
	gap: 8px;
}

.order-evidence,
.decision-recommendation,
.citation-item {
	padding: 11px 12px;
	background: rgba(250, 252, 255, 0.8);
	border: 1px solid #e8eff7;
	border-radius: 9px;
}

.order-evidence-top {
	align-items: center;
	justify-content: flex-start;
	flex-wrap: wrap;
	gap: 8px;
}

.order-evidence-top strong {
	color: #49637f;
	font-size: 13px;
}

.order-evidence-top span {
	color: #667281;
	font-size: 13px;
}

.order-evidence ul {
	margin: 6px 0 0;
	padding-left: 16px;
	color: #647382;
	font-size: 13px;
	line-height: 1.6;
}

.decision-recommendation p {
	margin: 7px 0 0;
	color: #5f7287;
	font-size: 13px;
	line-height: 1.65;
}

.decision-recommendation .muted-text {
	color: #677381;
}

.proposal-section {
	background: linear-gradient(180deg, color-mix(in srgb, var(--cp-role-confirmation-soft) 46%, transparent), transparent 32%);
	border-radius: 12px;
}

.proposal-heading-actions {
	display: flex;
	align-items: center;
	gap: 10px;
}

.proposal-intro {
	margin: 0 0 11px;
	color: color-mix(in srgb, var(--cp-role-confirmation-ink) 82%, #7f8794);
	font-size: 13px;
	line-height: 1.65;
}

.proposal-list {
	display: flex;
	flex-direction: column;
	gap: 10px;
}

.proposal-card {
	padding: 13px 14px;
	background: color-mix(in srgb, var(--cp-role-confirmation-soft) 34%, #fff);
	border: 1px solid color-mix(in srgb, var(--cp-role-confirmation) 18%, var(--cp-line-soft));
	border-radius: 11px;
}

.proposal-card--pending {
	border-color: color-mix(in srgb, var(--cp-role-confirmation) 32%, transparent);
	box-shadow: inset 3px 0 0 var(--cp-role-confirmation);
}

.proposal-card--executing {
	border-color: color-mix(in srgb, var(--cp-role-planning) 28%, transparent);
	box-shadow: inset 3px 0 0 var(--cp-role-planning);
}

.proposal-card--confirmed {
	background: #f8fcf9;
	border-color: #d9ebdf;
}

.proposal-card--failed {
	background: #fff8f8;
	border-color: #f1dadd;
}

.proposal-card--cancelled,
.proposal-card--expired {
	background: #fafbfd;
	border-color: #e8eef6;
}

.proposal-title-row {
	display: flex;
	align-items: center;
	flex-wrap: wrap;
	gap: 8px;
}

.proposal-title-row strong {
	color: var(--cp-role-confirmation-ink);
	font-size: 12px;
}

.proposal-meta {
	display: flex;
	flex-wrap: wrap;
	gap: 5px 12px;
	margin-top: 6px;
	color: #7b7160;
	font-size: 13px;
	line-height: 1.5;
	overflow-wrap: anywhere;
}

.proposal-expiry {
	color: #7b7060;
	font-size: 13px;
	white-space: nowrap;
}

.proposal-body {
	margin-top: 10px;
	padding-top: 10px;
	border-top: 1px dashed #ecdfc9;
}

.parameter-grid,
.result-value-grid {
	display: grid;
	grid-template-columns: repeat(auto-fit, minmax(130px, 1fr));
	gap: 7px;
}

.parameter-item,
.result-value-grid > div {
	min-width: 0;
	padding: 7px 9px;
	background: rgba(255, 255, 255, 0.72);
	border: 1px solid #f0e8da;
	border-radius: 7px;
}

.parameter-item span,
.result-value-grid span {
	display: block;
	color: #7a7060;
	font-size: 13px;
}

.parameter-item strong,
.result-value-grid strong {
	display: block;
	margin-top: 3px;
	color: #6e604c;
	font-size: 13px;
	font-weight: 650;
	overflow-wrap: anywhere;
}

.proposal-evidence {
	margin-top: 9px;
	color: #7c705d;
	font-size: 13px;
	overflow-wrap: anywhere;
}

.proposal-evidence strong {
	font-size: 13px;
}

.proposal-evidence ul {
	margin: 4px 0 0;
	padding-left: 16px;
	line-height: 1.6;
}

.content-hash {
	display: flex;
	align-items: center;
	gap: 4px;
	margin-top: 8px;
	color: #786f61;
	font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
	font-size: 13px;
}

.proposal-result {
	margin-top: 10px;
	padding: 10px;
	color: #567a63;
	background: #f2faf5;
	border: 1px solid #dcece2;
	border-radius: 8px;
	font-size: 13px;
}

.proposal-result > strong {
	display: flex;
	align-items: center;
	gap: 5px;
	margin-bottom: 7px;
}

.proposal-result .result-value-grid > div {
	background: #fbfefc;
	border-color: #e2efe6;
}

.proposal-result .result-value-grid span {
	color: #637569;
}

.proposal-result .result-value-grid strong {
	color: #4f6f5a;
}

.proposal-failure {
	display: flex;
	flex-direction: column;
	gap: 4px;
	margin-top: 10px;
	padding: 9px 10px;
	color: #96656c;
	background: #fff4f5;
	border: 1px solid #f2dfe2;
	border-radius: 8px;
	font-size: 13px;
	line-height: 1.55;
}

.proposal-actions {
	display: flex;
	align-items: center;
	justify-content: space-between;
	flex-wrap: wrap;
	gap: 9px;
	margin-top: 11px;
	padding-top: 9px;
	border-top: 1px dashed #ecdfc9;
}

.proposal-action-note {
	color: #7b7060;
	font-size: 13px;
	line-height: 1.5;
}

.proposal-buttons {
	display: flex;
	flex-wrap: wrap;
	align-items: center;
	gap: 4px;
}

.proposal-buttons :deep(.v-btn) {
	font-size: 13px;
	letter-spacing: 0;
}

.linked-record {
	display: flex;
	flex-direction: column;
	gap: 4px;
	padding: 10px 12px;
	background: #f9fbfe;
	border: 1px solid #e8eff7;
	border-radius: 9px;
}

.linked-record + .linked-record {
	margin-top: 7px;
}

.linked-record strong {
	color: #4c6682;
	font-size: 13px;
}

.linked-record span {
	color: #647383;
	font-size: 13px;
	line-height: 1.55;
}

.citation-item {
	font-size: 13px;
}

.citation-item summary span {
	margin-left: 6px;
	color: #66717f;
	font-size: 13px;
}

.citation-item p {
	margin: 7px 0 0;
	color: #637283;
	line-height: 1.65;
}

.history-heading {
	align-items: center;
	margin-bottom: 12px;
}

.history-tools {
	display: flex;
	align-items: center;
	gap: 5px;
}

.history-status {
	width: 108px;
	flex: 0 0 108px;
}

.history-status :deep(.v-field) {
	border-radius: 9px;
	background: rgba(248, 251, 255, 0.84);
}

.history-status :deep(.v-field__input) {
	min-height: 31px;
	padding-top: 0;
	padding-bottom: 0;
	font-size: 13px;
}

.history-list {
	display: flex;
	flex-direction: column;
	gap: 7px;
}

.history-item {
	display: block;
	width: 100%;
	padding: 10px 12px;
	color: inherit;
	text-align: left;
	background: rgba(251, 253, 255, 0.9);
	border: 1px solid #e7eef7;
	border-radius: 10px;
	cursor: pointer;
}

.history-item:hover,
.history-item:focus-visible {
	background: #f5faff;
	border-color: #cfe0f4;
	outline: none;
}

.history-item--current {
	border-color: #bdd8f7;
	box-shadow: inset 3px 0 0 #6f9fda;
}

.history-item:disabled {
	cursor: wait;
	opacity: 0.65;
}

.history-item-top,
.history-item-foot {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 8px;
}

.history-item-top > span,
.history-item-foot {
	color: #65717e;
	font-size: 13px;
}

.history-item p {
	display: -webkit-box;
	overflow: hidden;
	margin: 7px 0 5px;
	color: #526d8c;
	font-size: 13px;
	line-height: 1.5;
	-webkit-box-orient: vertical;
	-webkit-line-clamp: 2;
}

.history-item-foot {
	gap: 10px;
	overflow-wrap: anywhere;
}

.history-empty {
	padding: 9px 2px;
	color: #667280;
	font-size: 13px;
	line-height: 1.6;
	text-align: center;
}

.history-empty--error {
	color: #93646b;
}

.history-pagination {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 8px;
	margin-top: 10px;
	padding-top: 9px;
	color: #637181;
	border-top: 1px solid #edf2f8;
	font-size: 13px;
}

.history-pagination > div {
	display: flex;
	align-items: center;
}

@keyframes decision-result-in {
	from {
		opacity: 0;
		transform: translateY(6px);
	}
	to {
		opacity: 1;
		transform: translateY(0);
	}
}

@media (max-width: 960px) {
	.evidence-strip,
	.recommendation-detail-grid,
	.simulation-detail-grid,
	.ranking-cards {
		grid-template-columns: 1fr;
	}

	.decision-heading {
		flex-direction: column;
	}

	.decision-policy {
		flex-direction: row;
		flex-wrap: wrap;
	}
}

@media (max-width: 640px) {
	.decision-panel {
		padding: 18px 16px;
	}

	.decision-entry-actions,
	.proposal-actions,
	.result-heading {
		align-items: flex-start;
		flex-direction: column;
	}

	.decision-run-button {
		align-self: stretch;
	}

	.result-badges {
		justify-content: flex-start;
	}

	.proposal-top,
	.history-heading {
		align-items: flex-start;
		flex-direction: column;
	}

	.proposal-buttons {
		width: 100%;
	}
}

@media (max-width: 420px) {
	.result-layers {
		flex-direction: column;
		align-items: flex-start;
	}

	.result-section-heading {
		align-items: flex-start;
		flex-direction: column;
	}

	.proposal-buttons {
		flex-direction: column;
	}

	.proposal-buttons :deep(.v-btn) {
		width: 100%;
	}
}

@media (prefers-reduced-motion: reduce) {
	.decision-result {
		animation: none;
	}
}

@media (prefers-reduced-transparency: reduce) {
	.decision-panel {
		background: #fff;
		-webkit-backdrop-filter: none;
		backdrop-filter: none;
	}
}
</style>
