<template>
	<section class="disposal-workspace mb-7" aria-label="供应链风险处置与情景模拟">
		<article class="disposal-panel disposal-main-panel">
			<header class="disposal-heading">
				<div class="disposal-heading-main">
					<ChainPilotRoleIcon class="disposal-emblem" role="simulation" :size="21" />
					<div>
						<div class="disposal-kicker">DISPOSAL STUDIO</div>
						<h2 class="disposal-title">风险处置与情景模拟</h2>
						<p class="disposal-subtitle">规则生成处置建议，确定性 Java 引擎模拟补货与供应商延期，选定方案可在本地沙箱中演练执行并审计留痕。</p>
					</div>
				</div>
				<div class="disposal-tags">
					<span class="disposal-tag">规则计算</span>
					<span class="disposal-tag">真实数据隔离</span>
					<span class="disposal-tag">沙箱执行演练</span>
				</div>
			</header>

			<div class="disposal-grid">
				<section class="recommendation-pane" aria-label="处置建议">
					<div class="pane-heading">
						<div class="pane-heading-main">
							<ChainPilotRoleIcon role="risk" :size="15" />
							<div>
								<div class="disposal-kicker">RULE-BASED RECOMMENDATIONS</div>
								<h3 class="pane-title">处置建议</h3>
								<p class="pane-subtitle">优先级来自交付紧迫度、库存缺口与供应商表现，不采用主观模型评分。</p>
							</div>
						</div>
						<v-progress-circular v-if="recommendationsLoading" indeterminate size="20" width="2" color="primary" />
						<span v-else class="count-pill">{{ recommendations.length }} 条</span>
					</div>

					<div class="scope-toolbar">
						<div class="scope-fields">
							<v-select
								v-model="scopeMode"
								:items="scopeModeOptions"
								item-title="title"
								item-value="value"
								label="建议范围"
								density="compact"
								variant="outlined"
								hide-details
								class="disposal-field scope-mode-field"
							/>
							<v-select
								v-if="scopeMode === 'SKU'"
								v-model="scopeSku"
								:items="skuOptions"
								item-title="title"
								item-value="value"
								label="选择 SKU"
								density="compact"
								variant="outlined"
								hide-details
								class="disposal-field scope-target-field"
							/>
							<v-select
								v-else-if="scopeMode === 'SUPPLIER'"
								v-model="scopeSupplierId"
								:items="supplierOptions"
								item-title="title"
								item-value="value"
								label="选择供应商"
								density="compact"
								variant="outlined"
								hide-details
								class="disposal-field scope-target-field"
							/>
							<v-btn class="scope-apply" size="small" variant="tonal" color="primary" :loading="recommendationsLoading" @click="applyScope">应用范围</v-btn>
						</div>
						<div class="scope-meta">
							<span>当前建议范围：{{ appliedScopeLabel }}</span>
							<button type="button" class="disposal-link" :disabled="recommendationsLoading" @click="refreshRecommendations">刷新建议</button>
						</div>
						<p v-if="scopeError" class="inline-error">{{ scopeError }}</p>
					</div>

					<v-alert v-if="recommendationsError" type="error" variant="tonal" class="disposal-alert">
						<div class="alert-row"><span>{{ recommendationsError }}</span><v-btn size="x-small" variant="text" color="error" @click="refreshRecommendations">重试</v-btn></div>
					</v-alert>

					<div v-if="recommendationsLoading && recommendations.length === 0" class="panel-state">
						<v-progress-circular indeterminate size="22" width="2" color="primary" />
						<span>正在生成规则处置建议…</span>
					</div>
					<div v-else-if="recommendations.length === 0 && !recommendationsError" class="panel-state">
						<v-icon icon="mdi-check-decagram-outline" size="21" />
						<span>{{ recommendationsLoaded ? '当前范围内没有需要处置的中高风险订单。' : '正在等待处置建议。' }}</span>
					</div>

					<div v-else class="recommendation-list">
						<article v-for="recommendation in recommendations" :key="recommendation.recommendationId" class="recommendation-card">
							<div class="recommendation-top">
								<span class="priority-chip" :class="priorityChipClass(recommendation.priority)">{{ priorityLabel(recommendation.priority) }}</span>
								<v-chip size="x-small" variant="tonal" :color="riskColor(recommendation.riskLevel)">{{ riskLabel(recommendation.riskLevel) }}</v-chip>
								<span class="recommendation-score">{{ recommendation.priorityScore }} 分</span>
							</div>
							<div class="recommendation-heading">
								<h4>{{ recommendation.title }}</h4>
								<span>{{ actionTypeLabel(recommendation.actionType) }}</span>
							</div>
							<p class="recommendation-action">{{ recommendation.action }}</p>
							<div class="recommendation-meta">
								<span v-if="recommendation.sku" class="meta-token">{{ recommendation.sku }}</span>
								<span v-if="recommendation.supplierId" class="meta-token">{{ recommendation.supplierId }}</span>
								<span v-if="recommendation.affectedOrderIds.length" class="meta-orders">关联 {{ recommendation.affectedOrderIds.join('、') }}</span>
							</div>
							<div class="expected-improvement"><v-icon icon="mdi-trending-up" size="15" /><span><strong>预期改善</strong>{{ recommendation.expectedImprovement }}</span></div>
							<details class="rule-details">
								<summary>查看规则依据、前置条件与来源</summary>
								<div class="rule-details-body">
									<p><strong>优先级依据</strong>{{ recommendation.priorityBasis }}</p>
									<p><strong>业务依据</strong>{{ recommendation.businessBasis.join('；') }}</p>
									<p><strong>处置理由</strong>{{ recommendation.rationale }}</p>
									<p v-if="recommendation.prerequisites.length"><strong>前置条件</strong>{{ recommendation.prerequisites.join('；') }}</p>
									<p v-if="recommendation.sideEffects.length"><strong>潜在影响</strong>{{ recommendation.sideEffects.join('；') }}</p>
									<p v-if="recommendation.sources.length"><strong>数据来源</strong>{{ recommendation.sources.join('；') }}</p>
									<p v-if="recommendation.assumptions.length"><strong>规则假设</strong>{{ recommendation.assumptions.join('；') }}</p>
								</div>
							</details>
							<div class="recommendation-actions">
								<button
									v-if="recommendation.actionType !== 'MANUAL_CONFIRMATION'"
									type="button"
									class="disposal-link disposal-link--strong"
									@click="useRecommendation(recommendation)"
								>带入模拟</button>
								<span v-else class="manual-note">数据缺失，需人工核实</span>
								<span class="recommendation-time">{{ formatTime(recommendation.generatedAt) }}</span>
							</div>
						</article>
					</div>
					<p class="pane-footnote">建议只使用当前订单、库存和供应商字段；没有成本、供应商报价或工时数据，因此不会生成相关承诺。</p>
				</section>

				<section class="simulation-pane" aria-label="情景模拟">
					<div class="pane-heading">
						<div class="pane-heading-main">
							<ChainPilotRoleIcon role="simulation" :size="15" />
							<div>
								<div class="disposal-kicker">WHAT-IF SIMULATION</div>
								<h3 class="pane-title">情景模拟</h3>
								<p class="pane-subtitle">模拟会复制当前订单与库存后重算风险，不写回任何真实业务数据。</p>
							</div>
						</div>
						<span class="engine-badge"><v-icon icon="mdi-cog-transfer-outline" size="14" />JAVA RULES</span>
					</div>

					<div id="disposal-simulation-form" class="simulation-form">
						<v-select
							v-model="draft.scenario"
							:items="DISPOSAL_SCENARIO_OPTIONS"
							item-title="title"
							item-value="value"
							label="模拟场景"
							density="compact"
							variant="outlined"
							hide-details
							class="disposal-field"
						/>
						<div v-if="draft.scenario === 'INVENTORY_REPLENISHMENT'" class="simulation-fields">
							<v-select
								v-model="draft.sku"
								:items="skuOptions"
								item-title="title"
								item-value="value"
								label="补货 SKU"
								density="compact"
								variant="outlined"
								hide-details
								class="disposal-field"
							/>
							<v-text-field
								v-model="draft.replenishmentQuantity"
								label="补货数量（件）"
								type="number"
								min="1"
								max="1000000"
								step="1"
								density="compact"
								variant="outlined"
								hide-details
								class="disposal-field"
							/>
						</div>
						<div v-else class="simulation-fields">
							<v-select
								v-model="draft.supplierId"
								:items="supplierOptions"
								item-title="title"
								item-value="value"
								label="延期供应商"
								density="compact"
								variant="outlined"
								hide-details
								class="disposal-field"
							/>
							<v-text-field
								v-model="draft.additionalDelayDays"
								label="额外延期（天）"
								type="number"
								min="1"
								max="90"
								step="1"
								density="compact"
								variant="outlined"
								hide-details
								class="disposal-field"
							/>
						</div>
						<div class="simulation-submit">
							<span>{{ draft.scenario === 'INVENTORY_REPLENISHMENT' ? '数量范围 1 – 1,000,000 件' : '延期范围 1 – 90 天' }}</span>
							<v-btn color="primary" prepend-icon="mdi-play" size="small" class="simulation-run" :loading="simulationRunning" :disabled="simulationDetailLoading" @click="submitSimulation">运行模拟</v-btn>
						</div>
						<v-alert v-if="draftError || simulationError" type="error" variant="tonal" density="compact" class="disposal-alert">
							<div class="alert-row">
								<span>{{ draftError || simulationError }}</span>
								<v-btn v-if="simulationError && !draftError" size="x-small" variant="text" color="error" @click="submitSimulation">重试</v-btn>
							</div>
						</v-alert>
					</div>

					<article v-if="simulation" id="disposal-simulation-result" class="simulation-result">
						<div class="result-heading">
							<div>
								<div class="result-title-row">
									<h4>{{ scenarioLabel(simulation.scenario) }} · 预期结果</h4>
									<span class="rule-badge">确定性 Java 计算</span>
									<span class="isolation-badge" :class="{ 'isolation-badge--bad': simulation.realDataMutated }">{{ simulation.realDataMutated ? '真实数据已变更' : '真实数据未变更' }}</span>
								</div>
								<p>{{ parameterLabel(simulation) }} · 基准日期 {{ simulation.asOfDate }} · 基线来源 {{ simulation.baselineSource }}</p>
							</div>
							<v-progress-circular v-if="simulationDetailLoading" indeterminate size="18" width="2" color="primary" />
						</div>

						<div class="metric-table-wrap">
							<table class="metric-table">
								<thead><tr><th>指标</th><th>当前基线</th><th>模拟后</th><th>变化</th></tr></thead>
								<tbody>
									<tr><td>风险订单</td><td>{{ simulation.baseline.atRiskOrders }}</td><td>{{ simulation.simulated.atRiskOrders }}</td><td :class="deltaClass(simulation.delta.atRiskOrders)">{{ signed(simulation.delta.atRiskOrders) }}</td></tr>
									<tr><td>高风险订单</td><td>{{ simulation.baseline.highRiskOrders }}</td><td>{{ simulation.simulated.highRiskOrders }}</td><td :class="deltaClass(simulation.delta.highRiskOrders)">{{ signed(simulation.delta.highRiskOrders) }}</td></tr>
									<tr><td>存在缺口的订单</td><td>{{ simulation.baseline.ordersWithShortage }}</td><td>{{ simulation.simulated.ordersWithShortage }}</td><td :class="deltaClass(simulation.delta.ordersWithShortage)">{{ signed(simulation.delta.ordersWithShortage) }}</td></tr>
									<tr><td>缺口总量（件）</td><td>{{ simulation.baseline.totalShortageUnits }}</td><td>{{ simulation.simulated.totalShortageUnits }}</td><td :class="deltaClass(simulation.delta.totalShortageUnits)">{{ signed(simulation.delta.totalShortageUnits) }}</td></tr>
									<tr><td>风险评分合计</td><td>{{ simulation.baseline.totalRiskScore }}</td><td>{{ simulation.simulated.totalRiskScore }}</td><td :class="deltaClass(simulation.delta.totalRiskScore)">{{ signed(simulation.delta.totalRiskScore) }}</td></tr>
									<tr v-if="simulation.simulated.targetSkuAvailableToPromise !== null"><td>目标 SKU 可承诺量</td><td>{{ simulation.baseline.targetSkuAvailableToPromise }}</td><td>{{ simulation.simulated.targetSkuAvailableToPromise }}</td><td :class="deltaClass(simulation.delta.targetSkuAvailableToPromise, false)">{{ signed(simulation.delta.targetSkuAvailableToPromise) }}</td></tr>
								</tbody>
							</table>
						</div>
						<div class="result-summary-line">
							<span>受影响订单 <strong>{{ simulation.affectedOrders.length }}</strong></span>
							<span>模拟编号 <strong>{{ simulation.simulationId }}</strong></span>
							<span v-if="simulationPlanId">已关联方案 <strong>{{ simulationPlanId }}</strong></span>
						</div>

						<div class="impact-list">
							<div v-if="simulation.affectedOrders.length === 0" class="impact-empty">该情景未改变任何订单的风险状态。</div>
							<article v-for="impact in simulation.affectedOrders" :key="impact.orderId" class="impact-card">
								<div class="impact-heading">
									<div><strong>{{ impact.orderId }}</strong><span>{{ impact.sku }} · {{ impact.supplierId }}</span></div>
									<div class="impact-risk">
										<v-chip size="x-small" variant="tonal" :color="riskColor(impact.baselineRiskLevel)">{{ riskLabel(impact.baselineRiskLevel) }} {{ impact.baselineRiskScore }}</v-chip>
										<v-icon icon="mdi-arrow-right" size="13" />
										<v-chip size="x-small" variant="tonal" :color="riskColor(impact.simulatedRiskLevel)">{{ riskLabel(impact.simulatedRiskLevel) }} {{ impact.simulatedRiskScore }}</v-chip>
									</div>
								</div>
								<div class="impact-meta">
									<span>缺口 {{ impact.baselineInventoryShortage }} → <strong :class="impact.simulatedInventoryShortage < impact.baselineInventoryShortage ? 'delta-good' : ''">{{ impact.simulatedInventoryShortage }}</strong></span>
									<span>交付 {{ impact.baselineEstimatedDeliveryDate }} → <strong>{{ impact.simulatedEstimatedDeliveryDate }}</strong></span>
								</div>
								<ul v-if="impact.changes.length" class="impact-changes"><li v-for="change in impact.changes" :key="change">{{ change }}</li></ul>
							</article>
						</div>

						<details class="rule-details result-details">
							<summary>查看模拟假设、限制与数据证据</summary>
							<div class="rule-details-body">
								<p v-if="simulation.assumptions.length"><strong>模拟假设</strong>{{ simulation.assumptions.join('；') }}</p>
								<p v-if="simulation.limitations.length"><strong>限制</strong>{{ simulation.limitations.join('；') }}</p>
								<p v-if="simulation.evidence.length"><strong>数据证据</strong>{{ simulation.evidence.join('；') }}</p>
							</div>
						</details>
						<p class="simulation-disclaimer"><v-icon icon="mdi-information-outline" size="14" />{{ simulation.disclaimer }}</p>
					</article>

					<div v-else class="simulation-empty">
						<v-icon icon="mdi-chart-bell-curve-cumulative" size="24" />
						<div><strong>选择一个情景并运行模拟</strong><span>结果会展示基线、模拟值、差值和受影响订单；不会修改真实库存与订单。</span></div>
					</div>

					<article v-if="simulation || recommendations.length" class="plan-save-card">
						<div class="plan-save-heading">
							<div><div class="disposal-kicker">SAVE PLAN</div><h4>保存处置方案</h4></div>
							<span class="count-pill">持久化</span>
						</div>
						<v-text-field
							v-model="planTitle"
							label="方案标题"
							maxlength="200"
							counter
							density="compact"
							variant="outlined"
							hide-details="auto"
							class="disposal-field"
						/>
						<div class="plan-options">
							<v-checkbox v-model="attachSimulation" density="compact" hide-details label="附带当前模拟结果" :disabled="!simulation || simulationPlanId !== null" class="plan-option" />
							<v-checkbox v-if="taskId" v-model="linkTask" density="compact" hide-details label="关联当前分析报告快照" class="plan-option" />
							<v-switch v-model="generateExplanation" density="compact" hide-details inset color="primary" label="使用模型解释（基于规则结果，失败时规则降级）" class="plan-option plan-option--switch" />
						</div>
						<p v-if="simulationPlanId" class="plan-hint">该模拟已关联方案 {{ simulationPlanId }}，不能重复关联；可再次运行模拟生成新结果。</p>
						<div class="plan-save-actions">
							<span>建议条数：{{ recommendations.length }}</span>
							<v-btn color="primary" size="small" prepend-icon="mdi-content-save-outline" :loading="planSaving" :disabled="!planTitle.trim() || planSaving" @click="submitPlan">保存方案</v-btn>
						</div>
						<v-alert v-if="planFormError" type="error" variant="tonal" density="compact" class="disposal-alert">{{ planFormError }}</v-alert>
						<v-alert v-if="planSaveError" type="error" variant="tonal" density="compact" class="disposal-alert">{{ planSaveError }}</v-alert>
						<v-alert v-if="planSaveNotice" type="success" variant="tonal" density="compact" class="disposal-alert">{{ planSaveNotice }}</v-alert>
					</article>

					<div class="simulation-history">
						<div class="mini-heading">
							<div><div class="disposal-kicker">SIMULATION HISTORY</div><h4>最近模拟</h4></div>
							<button type="button" class="disposal-link" :disabled="simulationHistoryLoading" @click="loadSimulationHistory()">刷新</button>
						</div>
						<v-progress-linear v-if="simulationHistoryLoading" indeterminate color="primary" height="2" />
						<div v-if="simulationHistoryError" class="inline-error">{{ simulationHistoryError }}</div>
						<div v-else-if="simulationHistory.length === 0" class="mini-empty">还没有模拟记录，运行一次情景模拟后会保存在这里。</div>
						<template v-else>
							<button v-for="item in simulationHistory" :key="item.simulationId" type="button" class="history-sim-item" @click="openSimulationFromHistory(item)">
								<div><strong>{{ scenarioLabel(item.scenario) }}</strong><span>{{ item.parameterSummary }}</span></div>
								<div class="history-sim-meta">
									<span :class="deltaClass(item.atRiskOrdersDelta)">风险订单 {{ signed(item.atRiskOrdersDelta) }}</span>
									<span>{{ item.affectedOrderCount }} 单受影响</span>
									<span v-if="item.planId" class="linked-plan">已关联方案</span>
									<span class="history-time">{{ formatTime(item.createdAt) }}</span>
								</div>
							</button>
						</template>
					</div>
				</section>
			</div>
		</article>

		<article class="disposal-panel comparison-panel" aria-label="多方案对比与处置决策推荐">
			<div class="pane-heading comparison-heading">
				<div class="pane-heading-main">
					<ChainPilotRoleIcon role="simulation" :size="15" />
					<div>
						<div class="disposal-kicker">SCENARIO COMPARISON</div>
						<h3 class="pane-title">多方案对比与处置决策推荐</h3>
						<p class="pane-subtitle">2–5 个候选共用同一基线快照，风险指标、排名与推荐全部由确定性 Java 规则计算；推荐只引用真实模拟结果，不估算成本、预算、库容或供应商承诺。</p>
					</div>
				</div>
				<div class="comparison-heading-tools">
					<span class="engine-badge"><v-icon icon="mdi-format-list-numbered" size="14" />JAVA RANKING</span>
					<span v-if="scenarioComparison" class="count-pill">{{ scenarioComparison.candidates.length }} 个候选</span>
				</div>
			</div>

			<div class="comparison-grid">
				<section class="comparison-builder" aria-label="候选方案编辑">
					<div class="simulation-form comparison-form">
						<v-text-field
							v-model="comparisonDraft.title"
							label="对比标题"
							maxlength="200"
							counter
							density="compact"
							variant="outlined"
							hide-details="auto"
							class="disposal-field"
						/>
						<v-select
							v-model="comparisonDraft.scenario"
							:items="DISPOSAL_SCENARIO_OPTIONS"
							item-title="title"
							item-value="value"
							label="对比场景"
							density="compact"
							variant="outlined"
							hide-details
							class="disposal-field"
						/>
						<v-select
							v-if="comparisonDraft.scenario === 'INVENTORY_REPLENISHMENT'"
							v-model="comparisonDraft.sku"
							:items="skuOptions"
							item-title="title"
							item-value="value"
							label="对比 SKU"
							density="compact"
							variant="outlined"
							hide-details
							class="disposal-field"
						/>
						<v-select
							v-else
							v-model="comparisonDraft.supplierId"
							:items="supplierOptions"
							item-title="title"
							item-value="value"
							label="对比供应商"
							density="compact"
							variant="outlined"
							hide-details
							class="disposal-field"
						/>

						<div class="comparison-candidate-heading">
							<span>候选参数</span>
							<span>{{ comparisonDraft.candidates.length }} / {{ MAX_COMPARISON_CANDIDATES }}</span>
						</div>
						<div class="comparison-candidate-list">
							<div v-for="(candidate, index) in comparisonDraft.candidates" :key="index" class="comparison-candidate-row">
								<span class="comparison-candidate-index">{{ index + 1 }}</span>
								<v-text-field
									v-model="candidate.label"
									label="候选名称（可选）"
									maxlength="100"
									density="compact"
									variant="outlined"
									hide-details
									class="disposal-field comparison-candidate-name"
								/>
								<v-text-field
									v-if="comparisonDraft.scenario === 'INVENTORY_REPLENISHMENT'"
									v-model="candidate.replenishmentQuantity"
									label="补货数量（件）"
									type="number"
									min="1"
									max="1000000"
									step="1"
									density="compact"
									variant="outlined"
									hide-details
									class="disposal-field comparison-candidate-value"
								/>
								<v-text-field
									v-else
									v-model="candidate.additionalDelayDays"
									label="额外延期（天）"
									type="number"
									min="1"
									max="90"
									step="1"
									density="compact"
									variant="outlined"
									hide-details
									class="disposal-field comparison-candidate-value"
								/>
								<v-btn
									icon="mdi-close"
									size="x-small"
									variant="text"
									color="error"
									aria-label="移除候选"
									:disabled="comparisonDraft.candidates.length <= MIN_COMPARISON_CANDIDATES || comparisonRunning"
									@click="removeComparisonCandidate(index)"
								/>
							</div>
						</div>
						<div class="comparison-actions">
							<v-btn
								variant="tonal"
								color="primary"
								size="small"
								prepend-icon="mdi-plus"
								:disabled="comparisonDraft.candidates.length >= MAX_COMPARISON_CANDIDATES || comparisonRunning"
								@click="addComparisonCandidate"
							>添加候选</v-btn>
							<v-btn
								color="primary"
								size="small"
								prepend-icon="mdi-compare"
								:loading="comparisonRunning"
								:disabled="comparisonRunning || comparisonDetailLoading"
								@click="submitComparison"
							>运行对比</v-btn>
						</div>
						<p class="comparison-form-note">候选参数必须唯一；指标和排名由 Java 引擎按共享基线计算，不写回真实库存或订单。</p>
						<v-alert v-if="comparisonFormError" type="error" variant="tonal" density="compact" class="disposal-alert">{{ comparisonFormError }}</v-alert>
						<v-alert v-if="comparisonError" type="error" variant="tonal" density="compact" class="disposal-alert">
							<div class="alert-row">
								<span>{{ comparisonError }}</span>
								<v-btn size="x-small" variant="text" color="error" @click="submitComparison">重试</v-btn>
							</div>
						</v-alert>
					</div>

					<div class="comparison-history">
						<div class="mini-heading">
							<div><div class="disposal-kicker">COMPARISON HISTORY</div><h4>最近方案对比</h4></div>
							<button type="button" class="disposal-link" :disabled="comparisonHistoryLoading" @click="loadComparisonHistory()">刷新</button>
						</div>
						<v-progress-linear v-if="comparisonHistoryLoading" indeterminate color="primary" height="2" />
						<div v-if="comparisonHistoryError" class="inline-error">{{ comparisonHistoryError }}</div>
						<div v-else-if="comparisonHistory.length === 0" class="mini-empty">还没有对比记录，运行一次多方案对比后会持久化在这里。</div>
						<template v-else>
							<button v-for="item in comparisonHistory" :key="item.comparisonId" type="button" class="history-sim-item" @click="openComparisonFromHistory(item)">
								<div><strong>{{ item.title }}</strong><span>{{ item.recommendedLabel }} · {{ item.candidateCount }} 个候选 · 指纹 {{ shortFingerprint(item.baselineFingerprint) }}</span></div>
								<div class="history-sim-meta">
									<span>{{ comparisonOutcomeLabel(item.outcome) }}</span>
									<span v-if="item.linkedPlanCount" class="linked-plan">{{ item.linkedPlanCount }} 个已关联方案</span>
									<span class="history-time">{{ formatTime(item.createdAt) }}</span>
								</div>
							</button>
						</template>
					</div>
				</section>

				<section class="comparison-result-section" aria-label="对比结果与推荐">
					<div v-if="comparisonDetailLoading" class="panel-state">
						<v-progress-circular indeterminate size="22" width="2" color="primary" />
						<span>正在读取方案对比…</span>
					</div>
					<div v-else-if="!scenarioComparison" class="comparison-empty">
						<v-icon icon="mdi-scale-balance" size="25" />
						<div><strong>配置 2–5 个候选后运行对比</strong><span>结果会展示共享基线、各候选指标变化、确定性排名、推荐理由、取舍与证据。</span></div>
					</div>
					<template v-else>
						<article id="disposal-comparison-result" class="comparison-result">
							<div class="result-heading">
								<div>
									<div class="result-title-row">
										<h4>{{ scenarioComparison.title }}</h4>
										<span class="rule-badge">确定性 Java 排名</span>
										<span class="isolation-badge" :class="{ 'isolation-badge--bad': scenarioComparison.realDataMutated }">{{ scenarioComparison.realDataMutated ? '真实数据已变更' : '真实数据未变更' }}</span>
									</div>
									<p>{{ scenarioComparison.sku ? `SKU ${scenarioComparison.sku}` : `供应商 ${scenarioComparison.supplierId}` }} · 基准 {{ scenarioComparison.asOfDate }} · 共享基线指纹 <code>{{ shortFingerprint(scenarioComparison.baselineFingerprint) }}</code></p>
								</div>
								<button type="button" class="disposal-link" @click="clearComparison">关闭</button>
							</div>

							<div class="comparison-recommendation" :class="`comparison-recommendation--${scenarioComparison.recommendation.outcome.toLowerCase()}`">
								<div class="comparison-recommendation-top">
									<v-chip size="small" variant="tonal" :color="comparisonOutcomeColor(scenarioComparison.recommendation.outcome)">{{ comparisonOutcomeLabel(scenarioComparison.recommendation.outcome) }}</v-chip>
									<strong>{{ scenarioComparison.recommendation.recommendedLabel }}</strong>
									<span class="rank-pill">{{ rankLabel(rankingFor(scenarioComparison.recommendation.recommendedCandidateId)) }}</span>
								</div>
								<p class="comparison-recommendation-summary">{{ scenarioComparison.recommendation.summary }}</p>
								<div v-if="scenarioComparison.recommendation.keyDeltas.length" class="comparison-chip-list">
									<span v-for="delta in scenarioComparison.recommendation.keyDeltas" :key="delta">{{ delta }}</span>
								</div>
								<div class="comparison-recommendation-lists">
									<div v-if="scenarioComparison.recommendation.reasons.length">
										<strong>推荐理由</strong>
										<ul><li v-for="reason in scenarioComparison.recommendation.reasons" :key="reason">{{ reason }}</li></ul>
									</div>
									<div v-if="scenarioComparison.recommendation.tradeoffs.length">
										<strong>取舍与代价</strong>
										<ul><li v-for="tradeoff in scenarioComparison.recommendation.tradeoffs" :key="tradeoff">{{ tradeoff }}</li></ul>
									</div>
								</div>
								<div v-if="scenarioComparison.recommendation.unknowns.length || scenarioComparison.recommendation.manualConfirmation.length" class="comparison-warning">
									<div v-if="scenarioComparison.recommendation.unknowns.length">
										<strong>未知项</strong>
										<ul><li v-for="item in scenarioComparison.recommendation.unknowns" :key="item">{{ item }}</li></ul>
									</div>
									<div v-if="scenarioComparison.recommendation.manualConfirmation.length">
										<strong>需人工确认</strong>
										<ul><li v-for="item in scenarioComparison.recommendation.manualConfirmation" :key="item">{{ item }}</li></ul>
									</div>
								</div>
								<p class="simulation-disclaimer"><v-icon icon="mdi-information-outline" size="14" />{{ scenarioComparison.recommendation.disclaimer }}</p>
							</div>

							<div class="comparison-baseline-line">
								<span>共享基线 · 风险订单 <strong>{{ scenarioComparison.baseline.atRiskOrders }}</strong></span>
								<span>高风险 <strong>{{ scenarioComparison.baseline.highRiskOrders }}</strong></span>
								<span>缺口订单 <strong>{{ scenarioComparison.baseline.ordersWithShortage }}</strong></span>
								<span>缺口总量 <strong>{{ scenarioComparison.baseline.totalShortageUnits }}</strong></span>
								<span>目标 ATP <strong>{{ scenarioComparison.baseline.targetSkuAvailableToPromise ?? '—' }}</strong></span>
							</div>

							<div class="metric-table-wrap">
								<table class="metric-table comparison-table">
									<thead><tr><th>候选</th><th>风险订单</th><th>高风险</th><th>缺口订单</th><th>缺口总量</th><th>风险评分</th><th>目标 ATP</th><th>排名</th></tr></thead>
									<tbody>
										<tr
											v-for="candidate in scenarioComparison.candidates"
											:key="candidate.candidateId"
											:class="{ 'comparison-row--recommended': candidate.candidateId === scenarioComparison.recommendation.recommendedCandidateId }"
										>
											<td>
												<strong>{{ candidate.label }}</strong>
												<small>{{ comparisonCandidateParameter(candidate) }}</small>
											</td>
											<td :class="deltaClass(candidate.simulation.delta.atRiskOrders)">{{ signed(candidate.simulation.delta.atRiskOrders) }}</td>
											<td :class="deltaClass(candidate.simulation.delta.highRiskOrders)">{{ signed(candidate.simulation.delta.highRiskOrders) }}</td>
											<td :class="deltaClass(candidate.simulation.delta.ordersWithShortage)">{{ signed(candidate.simulation.delta.ordersWithShortage) }}</td>
											<td :class="deltaClass(candidate.simulation.delta.totalShortageUnits)">{{ signed(candidate.simulation.delta.totalShortageUnits) }}</td>
											<td :class="deltaClass(candidate.simulation.delta.totalRiskScore)">{{ signed(candidate.simulation.delta.totalRiskScore) }}</td>
											<td :class="deltaClass(candidate.simulation.delta.targetSkuAvailableToPromise, false)">{{ signed(candidate.simulation.delta.targetSkuAvailableToPromise) }}</td>
											<td><span class="rank-pill">{{ rankLabel(rankingFor(candidate.candidateId)) }}</span></td>
										</tr>
									</tbody>
								</table>
							</div>
							<p class="comparison-delta-note">表中数值为“模拟后 − 共享基线”；风险指标负值表示改善，目标 ATP 正值表示可承诺量提升。</p>

							<div class="comparison-ranking-list">
								<article
									v-for="ranking in sortedComparisonRankings"
									:key="ranking.candidateId"
									class="comparison-ranking-card"
									:class="{ 'comparison-ranking-card--recommended': ranking.recommended }"
								>
									<div class="comparison-ranking-top">
										<span class="rank-badge" :class="{ 'rank-badge--none': ranking.rank === null }">{{ rankLabel(ranking) }}</span>
										<div class="comparison-ranking-title">
											<strong>{{ ranking.label }}</strong>
											<span>{{ ranking.parameterSummary }}</span>
										</div>
										<div class="comparison-ranking-tags">
											<span v-if="ranking.recommended" class="comparison-tag comparison-tag--recommended">推荐</span>
											<span v-if="ranking.tied" class="comparison-tag">指标并列</span>
											<span v-if="!ranking.comparable" class="comparison-tag comparison-tag--warning">不可比</span>
											<span v-if="candidateLinkedPlan(ranking.candidateId)" class="comparison-tag">已关联方案</span>
										</div>
									</div>
									<p class="comparison-ranking-basis">{{ ranking.rankingBasis }}</p>
									<div v-if="ranking.keyDeltas.length" class="comparison-chip-list">
										<span v-for="delta in ranking.keyDeltas" :key="delta">{{ delta }}</span>
									</div>
									<details v-if="ranking.reasons.length || ranking.tradeoffs.length" class="rule-details">
										<summary>查看排名依据与取舍</summary>
										<div class="rule-details-body">
											<p v-if="ranking.reasons.length"><strong>排名依据</strong></p>
											<ul v-if="ranking.reasons.length" class="comparison-bullets"><li v-for="reason in ranking.reasons" :key="reason">{{ reason }}</li></ul>
											<p v-if="ranking.tradeoffs.length"><strong>取舍与代价</strong></p>
											<ul v-if="ranking.tradeoffs.length" class="comparison-bullets"><li v-for="tradeoff in ranking.tradeoffs" :key="tradeoff">{{ tradeoff }}</li></ul>
										</div>
									</details>
									<div class="comparison-ranking-actions">
										<template v-if="candidateLinkedPlan(ranking.candidateId)">
											<span class="comparison-plan-ref">方案 {{ candidateLinkedPlan(ranking.candidateId) }}</span>
											<button type="button" class="disposal-link" @click="openLinkedPlan(candidateLinkedPlan(ranking.candidateId))">打开方案与执行演练</button>
										</template>
										<v-btn
											v-else
											size="x-small"
											variant="tonal"
											color="primary"
											:loading="comparisonSavingCandidateId === ranking.candidateId"
											:disabled="Boolean(comparisonSavingCandidateId)"
											@click="saveComparisonCandidate(ranking.candidateId)"
										>保存为处置方案</v-btn>
									</div>
								</article>
							</div>

							<div class="comparison-link-card">
								<div class="comparison-link-heading">
									<strong>关联已有处置方案</strong>
									<span>选择候选并填写已有方案编号</span>
								</div>
								<div class="comparison-link-fields">
									<v-select
										v-model="linkCandidateId"
										:items="comparisonCandidateOptions"
										item-title="title"
										item-value="value"
										label="候选方案"
										density="compact"
										variant="outlined"
										hide-details
										class="disposal-field"
									/>
									<v-text-field
										v-model="linkPlanId"
										label="已有处置方案编号"
										density="compact"
										variant="outlined"
										hide-details
										class="disposal-field"
									/>
									<v-btn size="small" variant="tonal" color="primary" :loading="comparisonLinking" :disabled="comparisonLinking" @click="submitComparisonLink">关联方案</v-btn>
								</div>
								<p class="comparison-form-note">只允许关联同一 SKU / 供应商范围且模拟参数一致的方案；一个候选只能关联一个方案。</p>
								<p v-if="linkFormError" class="inline-error">{{ linkFormError }}</p>
								<v-alert v-if="comparisonLinkError" type="error" variant="tonal" density="compact" class="disposal-alert">{{ comparisonLinkError }}</v-alert>
								<v-alert v-if="comparisonLinkNotice" type="success" variant="tonal" density="compact" class="disposal-alert">{{ comparisonLinkNotice }}</v-alert>
							</div>

							<v-alert v-if="comparisonSaveError" type="error" variant="tonal" density="compact" class="disposal-alert">{{ comparisonSaveError }}</v-alert>
							<v-alert v-if="comparisonSaveNotice" type="success" variant="tonal" density="compact" class="disposal-alert">{{ comparisonSaveNotice }}</v-alert>

							<details class="rule-details result-details">
								<summary>查看排名规则、共享基线、证据与限制</summary>
								<div class="rule-details-body">
									<p><strong>共享基线指纹</strong><code>{{ scenarioComparison.baselineFingerprint }}</code></p>
									<p><strong>基线来源</strong>{{ scenarioComparison.baselineSource }} · 捕获时间 {{ formatTime(scenarioComparison.baselineCapturedAt) }}</p>
									<p v-if="scenarioComparison.rankingRules.length"><strong>排名规则</strong></p>
									<ul v-if="scenarioComparison.rankingRules.length" class="comparison-bullets"><li v-for="rule in scenarioComparison.rankingRules" :key="rule">{{ rule }}</li></ul>
									<p v-if="scenarioComparison.assumptions.length"><strong>对比假设</strong>{{ scenarioComparison.assumptions.join('；') }}</p>
									<p v-if="scenarioComparison.limitations.length"><strong>限制</strong>{{ scenarioComparison.limitations.join('；') }}</p>
									<p v-if="scenarioComparison.evidence.length"><strong>数据证据</strong>{{ scenarioComparison.evidence.join('；') }}</p>
								</div>
							</details>
							<p class="simulation-disclaimer"><v-icon icon="mdi-information-outline" size="14" />{{ scenarioComparison.disclaimer }}</p>
						</article>
					</template>
				</section>
			</div>
		</article>

		<div class="disposal-records">
			<article class="disposal-panel plan-history-panel">
				<div class="pane-heading plan-history-heading">
					<div class="pane-heading-main">
						<ChainPilotRoleIcon role="confirmation" :size="15" />
						<div>
							<div class="disposal-kicker">PLAN HISTORY</div>
							<h3 class="pane-title">已保存处置方案</h3>
							<p class="pane-subtitle">方案、规则建议、模拟结果和解释一起持久化，可按状态筛选。</p>
						</div>
					</div>
					<div class="plan-history-tools">
						<v-select
							:model-value="planStatus"
							:items="DISPOSAL_PLAN_STATUS_OPTIONS"
							item-title="title"
							item-value="value"
							aria-label="方案状态筛选"
							density="compact"
							variant="outlined"
							hide-details
							class="disposal-field plan-status-field"
							@update:model-value="setPlanStatus"
						/>
						<v-btn icon="mdi-refresh" size="x-small" variant="text" color="primary" aria-label="刷新处置方案" :loading="plansLoading" :disabled="planDetailLoading" @click="loadPlans()" />
					</div>
				</div>
				<v-progress-linear v-if="plansLoading" indeterminate color="primary" height="2" />
				<div v-if="plansError" class="plan-history-empty plan-history-empty--error">
					{{ plansError }}
					<button type="button" class="disposal-link" @click="loadPlans()">重试</button>
				</div>
				<div v-else-if="!plansLoading && plans.length === 0" class="plan-history-empty">
					{{ plansLoaded ? (planStatus === 'ALL' ? '还没有保存的处置方案，完成一次模拟或建议选择后保存。' : '当前状态下没有处置方案。') : '正在加载处置方案…' }}
				</div>
				<div v-else class="plan-history-list">
					<article v-for="item in plans" :key="item.planId" class="plan-history-item" :class="{ 'plan-history-item--active': item.planId === activePlan?.planId }">
						<button type="button" class="plan-history-main" :disabled="planDetailLoading" @click="openPlanAndScroll(item.planId)">
							<div class="plan-history-top">
								<v-chip size="x-small" variant="tonal" :color="planStatusColor(item.status)">{{ disposalPlanStatusLabel(item.status) }}</v-chip>
								<span class="history-time">{{ formatTime(item.updatedAt) }}</span>
							</div>
							<strong class="plan-history-title">{{ item.title }}</strong>
							<div class="plan-history-meta">
								<span>{{ scopeLabel(item.scopeSku, item.scopeSupplierId) }}</span>
								<span>{{ item.recommendationCount }} 条建议</span>
								<span v-if="item.simulationScenario">{{ scenarioLabel(item.simulationScenario) }} · 风险订单 {{ signed(item.atRiskOrdersDelta) }}</span>
							</div>
							<div class="plan-history-foot"><span>方案 {{ item.planId }}</span><span>查看详情</span></div>
						</button>
					</article>
				</div>
				<div class="plan-pagination">
					<span>{{ plansRangeLabel }}</span>
					<div class="plan-pagination-controls">
						<v-select
							:model-value="plansPageSize"
							:items="DISPOSAL_PLAN_PAGE_SIZE_OPTIONS"
							item-title="title"
							item-value="value"
							aria-label="每页方案条数"
							density="compact"
							variant="plain"
							hide-details
							class="plan-page-size"
							@update:model-value="setPlanPageSize"
						/>
						<v-btn icon="mdi-chevron-left" size="x-small" variant="text" color="primary" aria-label="上一页方案" :disabled="!canPlanPrevious" @click="previousPlanPage" />
						<v-btn icon="mdi-chevron-right" size="x-small" variant="text" color="primary" aria-label="下一页方案" :disabled="!canPlanNext" @click="nextPlanPage" />
					</div>
				</div>
			</article>

			<article id="disposal-plan-detail" class="disposal-panel plan-detail-panel">
				<div class="pane-heading plan-detail-heading">
					<div class="pane-heading-main">
						<ChainPilotRoleIcon role="confirmation" :size="15" />
						<div>
							<div class="disposal-kicker">PLAN DETAIL</div>
							<h3 class="pane-title">方案依据</h3>
							<p class="pane-subtitle">展开已保存的规则建议、模拟对比、业务来源和解释文本。</p>
						</div>
					</div>
					<v-progress-circular v-if="planDetailLoading" indeterminate size="20" width="2" color="primary" />
					<button v-else-if="activePlan" type="button" class="disposal-link" @click="clearPlan">关闭</button>
				</div>
				<v-progress-linear v-if="planDetailLoading" indeterminate color="primary" height="2" />
				<div v-if="planDetailError" class="plan-detail-empty plan-detail-empty--error">
					{{ planDetailError }}
					<button v-if="lastRequestedPlanId" type="button" class="disposal-link" @click="openPlanAndScroll(lastRequestedPlanId)">重试</button>
				</div>
				<div v-else-if="!activePlan" class="plan-detail-empty">
					<v-icon icon="mdi-file-document-outline" size="25" />
					<div><strong>选择一份处置方案</strong><span>这里会展示它的完整证据链，并可确认选用、退回待确认或标记不采用。</span></div>
				</div>
				<template v-else>
					<div class="plan-detail-summary">
						<div class="plan-detail-title-row">
							<h4>{{ activePlan.title }}</h4>
							<v-chip size="small" variant="tonal" :color="planStatusColor(activePlan.status)">{{ disposalPlanStatusLabel(activePlan.status) }}</v-chip>
						</div>
						<div class="plan-detail-meta">
							<span>{{ scopeLabel(activePlan.scopeSku, activePlan.scopeSupplierId) }}</span>
							<span>{{ activePlan.recommendations.length }} 条规则建议</span>
							<span v-if="activePlan.taskId">分析任务 {{ activePlan.taskId }}</span>
							<span>{{ formatTime(activePlan.createdAt) }}</span>
						</div>
						<p class="plan-id-line">方案 {{ activePlan.planId }}</p>
					</div>

					<div class="explanation-card" :class="{ 'explanation-card--model': activePlan.explanationModelAssisted }">
						<div class="explanation-heading">
							<div><v-icon :icon="activePlan.explanationModelAssisted ? 'mdi-robot-outline' : 'mdi-rule'" size="17" /><strong>{{ explanationSourceLabel(activePlan) }}</strong></div>
							<span v-if="activePlan.explanationToolCalls.length" class="tool-call-note">工具调用：{{ activePlan.explanationToolCalls.join('、') }}</span>
						</div>
						<p>{{ activePlan.explanation }}</p>
						<span class="explanation-footnote">解释只引用下方规则建议与确定性模拟结果；数值不由模型计算。</span>
					</div>

					<div class="plan-section">
						<div class="plan-section-heading"><h4>确认采用的处置建议</h4><span>点击选择后再提交状态</span></div>
						<div class="plan-recommendation-options">
							<button
								v-for="recommendation in activePlan.recommendations"
								:key="recommendation.recommendationId"
								type="button"
								class="plan-recommendation-option"
								:class="{ 'plan-recommendation-option--selected': recommendation.recommendationId === selectedRecommendationId }"
								:disabled="planStatusUpdating"
								@click="selectedRecommendationId = recommendation.recommendationId"
							>
								<span class="priority-chip" :class="priorityChipClass(recommendation.priority)">{{ recommendation.priority }}</span>
								<span class="plan-option-copy"><strong>{{ recommendation.title }}</strong><small>{{ recommendation.expectedImprovement }}</small></span>
								<v-icon v-if="recommendation.recommendationId === selectedRecommendationId" icon="mdi-check-circle" size="17" color="primary" />
							</button>
						</div>
						<div class="plan-status-actions">
							<v-btn size="small" color="primary" :disabled="activePlan.status === 'SELECTED' || !selectedRecommendationId" :loading="planStatusUpdating" @click="submitPlanStatus('SELECTED')">确认选用</v-btn>
							<v-btn size="small" variant="tonal" color="primary" :disabled="activePlan.status === 'PENDING_CONFIRMATION'" :loading="planStatusUpdating" @click="submitPlanStatus('PENDING_CONFIRMATION')">退回待确认</v-btn>
							<v-btn size="small" variant="text" color="error" :disabled="activePlan.status === 'REJECTED'" :loading="planStatusUpdating" @click="submitPlanStatus('REJECTED')">标记不采用</v-btn>
						</div>
						<v-alert v-if="planStatusError" type="error" variant="tonal" density="compact" class="disposal-alert">{{ planStatusError }}</v-alert>
					</div>

					<div v-if="activePlan.simulation" class="plan-section">
						<div class="plan-section-heading"><h4>方案中的模拟对比</h4><span>Java 规则结果</span></div>
						<div class="plan-simulation-card">
							<div class="plan-simulation-top">
								<strong>{{ scenarioLabel(activePlan.simulation.scenario) }} · {{ parameterLabel(activePlan.simulation) }}</strong>
								<span class="isolation-badge" :class="{ 'isolation-badge--bad': activePlan.simulation.realDataMutated }">{{ activePlan.simulation.realDataMutated ? '真实数据已变更' : '真实数据未变更' }}</span>
							</div>
							<div class="plan-simulation-metrics">
								<div><span>风险订单</span><strong>{{ activePlan.simulation.baseline.atRiskOrders }} → {{ activePlan.simulation.simulated.atRiskOrders }}</strong><small :class="deltaClass(activePlan.simulation.delta.atRiskOrders)">{{ signed(activePlan.simulation.delta.atRiskOrders) }}</small></div>
								<div><span>缺口总量</span><strong>{{ activePlan.simulation.baseline.totalShortageUnits }} → {{ activePlan.simulation.simulated.totalShortageUnits }}</strong><small :class="deltaClass(activePlan.simulation.delta.totalShortageUnits)">{{ signed(activePlan.simulation.delta.totalShortageUnits) }}</small></div>
								<div><span>受影响订单</span><strong>{{ activePlan.simulation.affectedOrders.length }} 单</strong><small>{{ formatTime(activePlan.simulation.createdAt) }}</small></div>
							</div>
							<button type="button" class="disposal-link" @click="viewPlanSimulation(activePlan.simulation)">在模拟工作区查看完整结果</button>
						</div>
					</div>

					<div class="plan-section execution-section">
						<div class="plan-section-heading plan-section-heading--execution">
							<div class="plan-section-title"><ChainPilotRoleIcon role="execution" :size="14" /><h4>执行演练与审计记录</h4></div>
							<span class="sandbox-chip">SANDBOX · 本地演练</span>
						</div>
						<div class="layer-legend" aria-label="数据层级说明">
							<span>规则计算</span>
							<span>情景模拟</span>
							<span>AI 解释</span>
							<span class="layer-legend-current">沙箱执行记录</span>
							<span class="layer-legend-real">真实业务操作：未执行</span>
						</div>
						<p class="execution-intro">执行演练只在本地生成任务草稿和审计记录，不创建采购单、仓库任务，也不会联系供应商。真实库存、订单、供应商与 ERP 状态保持不变；演练完成不等于真实业务已完成。</p>

						<div class="execution-toolbar">
							<div class="execution-eligibility">
								<v-progress-circular v-if="executionEligibilityLoading" indeterminate size="18" width="2" color="primary" />
								<template v-else-if="executionEligibility">
									<v-chip size="small" variant="tonal" :color="executionEligibility.eligible ? 'success' : 'warning'">
										{{ executionEligibility.eligible ? '满足演练条件' : '暂不可演练' }}
									</v-chip>
									<span v-if="executionEligibility.actionType" class="execution-eligibility-action">{{ actionTypeLabel(executionEligibility.actionType) }}</span>
									<span v-if="executionEligibility.reason" class="execution-reason">{{ executionEligibility.reason }}</span>
								</template>
								<span v-else class="execution-reason">尚未取得演练条件检查结果。</span>
							</div>
							<v-btn
								color="primary"
								size="small"
								prepend-icon="mdi-play-circle-outline"
								:loading="executionStarting"
								:disabled="!canStartExecution || executionStarting"
								@click="startPlanExecution"
							>开始执行演练</v-btn>
						</div>
						<p v-if="activePlan.status !== 'SELECTED'" class="execution-hint">只有状态为“已选定”的处置方案可以发起执行演练。</p>
						<ul v-if="executionEligibility?.checks.length" class="execution-checks">
							<li v-for="check in executionEligibility.checks" :key="check">{{ check }}</li>
						</ul>
						<v-alert v-if="executionEligibilityError" type="warning" variant="tonal" density="compact" class="disposal-alert">{{ executionEligibilityError }}</v-alert>
						<v-alert v-if="executionStartError" type="error" variant="tonal" density="compact" class="disposal-alert">
							<div class="alert-row">
								<span>{{ executionStartError }}</span>
								<v-btn v-if="!executionStarting && canStartExecution" size="x-small" variant="text" color="error" @click="startPlanExecution">重试执行演练</v-btn>
							</div>
						</v-alert>
						<v-alert v-if="executionStartNotice" type="success" variant="tonal" density="compact" class="disposal-alert">{{ executionStartNotice }}</v-alert>

						<div v-if="executionDetailLoading" class="mini-loading">正在读取执行演练记录…</div>
						<div v-else-if="executionDetailError" class="inline-error">{{ executionDetailError }}</div>

						<article v-if="execution" id="disposal-execution-result" class="execution-result">
							<div class="execution-result-top">
								<div>
									<h5>{{ actionTypeLabel(execution.actionType) }} · 执行演练记录</h5>
									<p>执行编号 {{ execution.executionId }} · 方案 {{ execution.disposalPlanId }}</p>
								</div>
								<v-chip size="small" variant="tonal" :color="executionStatusColor(execution.status)">{{ sandboxExecutionStatusLabel(execution.status) }}</v-chip>
							</div>
							<div class="execution-meta-grid">
								<div><span>执行模式</span><strong>{{ execution.mode }}</strong></div>
								<div><span>真实数据</span><strong :class="execution.realDataMutated ? 'delta-bad' : 'delta-good'">{{ execution.realDataMutated ? '已变更' : '未变更' }}</strong></div>
								<div><span>来源模拟</span><strong>{{ execution.sourceSimulationId || '无（基于当前证据）' }}</strong></div>
								<div><span>请求编号</span><strong>{{ execution.idempotencyKey }}</strong></div>
								<div><span>创建时间</span><strong>{{ formatTime(execution.createdAt) }}</strong></div>
								<div><span>完成时间</span><strong>{{ execution.completedAt ? formatTime(execution.completedAt) : '—' }}</strong></div>
							</div>
							<p v-if="execution.failureReason" class="execution-failure"><strong>演练失败原因</strong>{{ execution.failureReason }}</p>

							<div class="execution-steps">
								<div class="execution-subheading"><h6>执行步骤</h6><span>以下为代码实际执行并持久化的结果</span></div>
								<ol>
									<li v-for="step in execution.steps" :key="step.sequence" class="execution-step" :class="{ 'execution-step--failed': step.status === 'FAILED' }">
										<div class="execution-step-head">
											<strong>{{ step.sequence }}. {{ step.name }}</strong>
											<v-chip size="x-small" variant="tonal" :color="step.status === 'SUCCEEDED' ? 'success' : 'error'">{{ sandboxStepStatusLabel(step.status) }}</v-chip>
										</div>
										<p><span>输入</span>{{ step.inputSummary }}</p>
										<p><span>校验</span>{{ step.validationResult }}</p>
										<p v-if="step.resultSummary"><span>结果</span>{{ step.resultSummary }}</p>
										<p v-if="step.failureReason" class="execution-step-failure"><span>失败原因</span>{{ step.failureReason }}</p>
										<div class="execution-step-foot"><code>{{ step.step }}</code><span>{{ formatTime(step.startedAt) }} → {{ formatTime(step.completedAt) }}</span></div>
									</li>
								</ol>
							</div>

							<div v-if="execution.taskDraft" class="execution-draft">
								<div class="execution-draft-head">
									<div><h6>{{ sandboxDraftTypeLabel(execution.taskDraft.draftType) }}</h6><p>{{ execution.taskDraft.title }}</p></div>
									<code>{{ execution.taskDraft.taskDraftId }}</code>
								</div>
								<div class="execution-draft-meta">
									<span v-if="execution.taskDraft.sku">SKU {{ execution.taskDraft.sku }}</span>
									<span v-if="execution.taskDraft.supplierId">供应商 {{ execution.taskDraft.supplierId }}</span>
									<span v-if="execution.taskDraft.replenishmentQuantity !== null">补货 {{ execution.taskDraft.replenishmentQuantity }} 件</span>
									<span v-if="execution.taskDraft.currentAvailableToPromise !== null">ATP {{ execution.taskDraft.currentAvailableToPromise }} → {{ execution.taskDraft.targetAvailableToPromise }}</span>
									<span v-if="execution.taskDraft.linkedOrderIds.length">关联 {{ execution.taskDraft.linkedOrderIds.join('、') }}</span>
								</div>
								<div class="execution-draft-lists">
									<div><strong>建议动作</strong><ul><li v-for="action in execution.taskDraft.recommendedActions" :key="action">{{ action }}</li></ul></div>
									<div><strong>待人工确认</strong><ul><li v-for="item in execution.taskDraft.pendingConfirmations" :key="item">{{ item }}</li></ul></div>
								</div>
								<p class="execution-draft-evidence">{{ execution.taskDraft.evidenceSummary }}</p>
							</div>

							<div v-if="execution.finalResult" class="execution-final"><strong>演练结论</strong><p>{{ execution.finalResult }}</p></div>
							<div v-if="execution.unresolvedItems.length" class="execution-unresolved"><strong>未解决事项</strong><ul><li v-for="item in execution.unresolvedItems" :key="item">{{ item }}</li></ul></div>
							<div class="execution-evidence"><strong>来源证据</strong><ul><li v-for="item in execution.evidence" :key="item">{{ item }}</li></ul></div>
							<p class="execution-disclaimer">{{ execution.disclaimer }}</p>
						</article>
						<div v-else-if="!executionDetailLoading && !executionDetailError" class="mini-empty">尚无选中的执行记录。点击“开始执行演练”，或从下方历史打开一条记录。</div>

						<div class="execution-history">
							<div class="mini-heading">
								<div><h6>执行记录</h6><span v-if="executionsTotal" class="history-time">共 {{ executionsTotal }} 条</span></div>
								<button type="button" class="disposal-link" :disabled="executionsLoading" @click="loadExecutions(activePlan.planId)">刷新记录</button>
							</div>
							<v-progress-linear v-if="executionsLoading" indeterminate color="primary" height="2" />
							<div v-if="executionsError" class="inline-error">{{ executionsError }}</div>
							<div v-else-if="executionsLoaded && executions.length === 0" class="mini-empty">该方案还没有执行演练记录。</div>
							<button
								v-for="item in executions"
								:key="item.executionId"
								type="button"
								class="history-sim-item execution-history-item"
								:class="{ 'execution-history-item--active': item.executionId === execution?.executionId }"
								@click="openExecutionFromHistory(item)"
							>
								<div><strong>{{ item.title || actionTypeLabel(item.actionType) }}</strong><span>{{ sandboxDraftTypeLabel(item.draftType) }} · {{ item.stepCount }} 个步骤<span v-if="item.failedStepCount"> · {{ item.failedStepCount }} 个失败</span></span></div>
								<div class="history-sim-meta">
									<v-chip size="x-small" variant="tonal" :color="executionStatusColor(item.status)">{{ sandboxExecutionStatusLabel(item.status) }}</v-chip>
									<span>{{ formatTime(item.createdAt) }}</span>
								</div>
							</button>
							<p v-if="executionsTotal > executions.length" class="execution-history-foot">仅显示最近 {{ executions.length }} 条，共 {{ executionsTotal }} 条。</p>
						</div>
					</div>

					<div class="plan-section">
						<div class="plan-section-heading"><h4>业务证据与来源</h4><span>{{ activePlan.evidence.length }} 条 SOP</span></div>
						<v-expansion-panels v-if="activePlan.evidence.length" variant="accordion" class="plan-citations">
							<v-expansion-panel v-for="citation in activePlan.evidence" :key="citation.sourceId" :title="citation.title">
								<template #text><div class="citation-text"><span>{{ citation.sourceId }}</span>{{ citation.excerpt }}</div></template>
							</v-expansion-panel>
						</v-expansion-panels>
						<div v-else class="mini-empty">该方案保存时未返回 SOP 来源。</div>
					</div>

					<div v-if="activePlanSimulationsLoading" class="mini-loading">正在加载方案关联的模拟记录…</div>
					<div v-else-if="activePlanSimulations.length" class="plan-section">
						<div class="plan-section-heading"><h4>方案关联的模拟记录</h4><span>{{ activePlanSimulations.length }} 条</span></div>
						<button v-for="item in activePlanSimulations" :key="item.simulationId" type="button" class="history-sim-item history-sim-item--compact" @click="openSimulationFromHistory(item)">
							<div><strong>{{ scenarioLabel(item.scenario) }}</strong><span>{{ item.parameterSummary || '历史模拟记录' }}</span></div>
							<span class="history-time">{{ formatTime(item.createdAt) }}</span>
						</button>
					</div>
				</template>
			</article>
		</div>
	</section>
</template>

<script setup lang="ts">
import { useSupplyChainDisposal } from '~/composables/useSupplyChainDisposal';
import { scrollBehavior } from '~/utils/prefersReducedMotion';
import {
	actionTypeLabel,
	buildComparisonRequest,
	buildSimulationRequest,
	comparisonOutcomeLabel,
	DISPOSAL_PLAN_PAGE_SIZE_OPTIONS,
	DISPOSAL_PLAN_STATUS_OPTIONS,
	DISPOSAL_SCENARIO_OPTIONS,
	disposalPlanStatusLabel,
	MAX_COMPARISON_CANDIDATES,
	MIN_COMPARISON_CANDIDATES,
	priorityLabel,
	sandboxDraftTypeLabel,
	sandboxExecutionStatusLabel,
	sandboxStepStatusLabel,
	scenarioLabel,
	validateComparisonDraft,
	validateSimulationDraft,
	type ComparisonDraft,
	type DisposalPlan,
	type DisposalPlanStatus,
	type DisposalRecommendation,
	type DisposalRiskLevel,
	type DisposalScenarioCandidate,
	type DisposalScenarioComparisonOutcome,
	type DisposalScenarioComparisonSummary,
	type DisposalScenarioRanking,
	type DisposalSimulation,
	type DisposalSimulationSummary,
	type RecommendationScope,
	type SandboxExecutionStatus,
	type SandboxExecutionSummary,
	type SimulationDraft,
} from '~/services/supplyChainDisposal';

const props = defineProps<{
	inventory: { sku: string; productName?: string; availableToPromise: number }[];
	suppliers: { supplierId: string; name?: string; riskLevel?: string }[];
	taskId?: string | null;
	loadingContext?: boolean;
}>();

const {
	recommendations,
	recommendationsLoading,
	recommendationsError,
	recommendationsLoaded,
	simulation,
	simulationRunning,
	simulationDetailLoading,
	simulationError,
	simulationHistory,
	simulationHistoryLoading,
	simulationHistoryError,
	simulationPlanId,
	plans,
	plansLoading,
	plansError,
	plansLoaded,
	planStatus,
	plansPageSize,
	canPlanPrevious,
	canPlanNext,
	plansRangeLabel,
	activePlan,
	activePlanSimulations,
	activePlanSimulationsLoading,
	planDetailLoading,
	planDetailError,
	planSaving,
	planSaveError,
	planSaveNotice,
	planStatusUpdating,
	planStatusError,
	executionEligibility,
	executionEligibilityLoading,
	executionEligibilityError,
	executions,
	executionsTotal,
	executionsLoading,
	executionsError,
	executionsLoaded,
	execution,
	executionDetailLoading,
	executionDetailError,
	executionStarting,
	executionStartError,
	executionStartNotice,
	canStartExecution,
	scenarioComparison,
	comparisonRunning,
	comparisonError,
	comparisonHistory,
	comparisonHistoryLoading,
	comparisonHistoryError,
	comparisonDetailLoading,
	comparisonSavingCandidateId,
	comparisonSaveError,
	comparisonSaveNotice,
	comparisonLinking,
	comparisonLinkError,
	comparisonLinkNotice,
	loadRecommendations,
	runSimulation,
	loadSimulationHistory,
	openSimulation,
	runComparison,
	loadComparisonHistory,
	openComparison,
	clearComparison,
	saveComparisonCandidateAsPlan,
	linkComparisonCandidatePlan,
	loadPlans,
	refreshAfterConfirmedProposal,
	setPlanStatus,
	setPlanPageSize,
	previousPlanPage,
	nextPlanPage,
	openPlan,
	clearPlan,
	savePlan,
	updatePlanStatus,
	loadExecutions,
	openExecution,
	startExecution,
	initialize,
} = useSupplyChainDisposal();

const scopeModeOptions: { title: string; value: 'ALL' | 'SKU' | 'SUPPLIER' }[] = [
	{ title: '全部风险订单', value: 'ALL' },
	{ title: '按 SKU', value: 'SKU' },
	{ title: '按供应商', value: 'SUPPLIER' },
];

const scopeMode = ref<'ALL' | 'SKU' | 'SUPPLIER'>('ALL');
const scopeSku = ref('');
const scopeSupplierId = ref('');
const appliedScope = ref<RecommendationScope>({});
const scopeError = ref('');

const draft = reactive<SimulationDraft>({
	scenario: 'INVENTORY_REPLENISHMENT',
	sku: '',
	replenishmentQuantity: 100,
	supplierId: '',
	additionalDelayDays: 7,
});
const draftTouched = ref(false);
const draftError = computed(() => (draftTouched.value ? validateSimulationDraft(draft) : ''));

const planTitle = ref('供应链风险处置方案');
const attachSimulation = ref(false);
const linkTask = ref(false);
const generateExplanation = ref(true);
const planFormError = ref('');
const selectedRecommendationId = ref('');
const lastRequestedPlanId = ref('');

const comparisonDraft = reactive<ComparisonDraft>({
	title: '风险处置补货方案对比',
	scenario: 'INVENTORY_REPLENISHMENT',
	sku: '',
	supplierId: '',
	candidates: [
		{ label: '', replenishmentQuantity: 50, additionalDelayDays: null },
		{ label: '', replenishmentQuantity: 100, additionalDelayDays: null },
		{ label: '', replenishmentQuantity: 200, additionalDelayDays: null },
	],
});
const comparisonTouched = ref(false);
const comparisonFormError = computed(() =>
	comparisonTouched.value ? validateComparisonDraft(comparisonDraft) : '',
);
const linkPlanId = ref('');
const linkCandidateId = ref('');
const linkFormError = ref('');
const comparisonCandidateOptions = computed(() =>
	(scenarioComparison.value?.candidates || []).map((candidate) => ({
		title: `${candidate.label} · ${comparisonCandidateParameter(candidate)}`,
		value: candidate.candidateId,
	})),
);
const sortedComparisonRankings = computed(() => {
	const rankings = scenarioComparison.value?.rankings || [];
	return [...rankings].sort((left, right) => {
		if (left.rank === null) {
			return right.rank === null ? 0 : 1;
		}
		if (right.rank === null) {
			return -1;
		}
		return left.rank - right.rank;
	});
});

const skuOptions = computed(() =>
	props.inventory.map((item) => ({
		title: item.productName ? `${item.sku} · ${item.productName}` : item.sku,
		value: item.sku,
	})),
);
const supplierOptions = computed(() =>
	props.suppliers.map((item) => ({
		title: item.name ? `${item.name} · ${item.supplierId}` : item.supplierId,
		value: item.supplierId,
	})),
);
const appliedScopeLabel = computed(() => {
	if (appliedScope.value.sku) {
		return appliedScope.value.sku;
	}
	if (appliedScope.value.supplierId) {
		return appliedScope.value.supplierId;
	}
	return '全部风险订单';
});

watch(
	() => props.inventory,
	(items) => {
		if (!draft.sku && items.length > 0) {
			draft.sku = items[0]?.sku || '';
		}
		if (!comparisonDraft.sku && items.length > 0) {
			comparisonDraft.sku = items[0]?.sku || '';
		}
	},
	{ immediate: true },
);
watch(
	() => props.suppliers,
	(items) => {
		if (!draft.supplierId && items.length > 0) {
			draft.supplierId = items[0]?.supplierId || '';
		}
		if (!comparisonDraft.supplierId && items.length > 0) {
			comparisonDraft.supplierId = items[0]?.supplierId || '';
		}
	},
	{ immediate: true },
);
watch(
	() => draft.scenario,
	() => {
		draftTouched.value = false;
	},
);
watch(
	() => comparisonDraft.scenario,
	(scenario) => {
		comparisonTouched.value = false;
		comparisonDraft.candidates.forEach((candidate, index) => {
			if (scenario === 'INVENTORY_REPLENISHMENT' && candidate.replenishmentQuantity === null) {
				candidate.replenishmentQuantity = (index + 1) * 50;
			}
			if (scenario === 'SUPPLIER_DELAY' && candidate.additionalDelayDays === null) {
				candidate.additionalDelayDays = (index + 1) * 7;
			}
		});
	},
);
watch(scenarioComparison, (comparison) => {
	linkCandidateId.value =
		comparison?.recommendation.recommendedCandidateId || comparison?.candidates[0]?.candidateId || '';
	linkFormError.value = '';
});
watch(
	() => props.taskId,
	(value) => {
		if (!value) {
			linkTask.value = false;
		}
	},
);
watch(simulation, (value) => {
	attachSimulation.value = Boolean(value) && !simulationPlanId.value;
});
watch(activePlan, (plan) => {
	selectedRecommendationId.value =
		plan?.selectedRecommendationId || plan?.recommendations[0]?.recommendationId || '';
});

function riskColor(level: DisposalRiskLevel) {
	return level === 'HIGH' ? 'error' : level === 'MEDIUM' ? 'warning' : 'success';
}

function riskLabel(level: DisposalRiskLevel) {
	return level === 'HIGH' ? '高风险' : level === 'MEDIUM' ? '中风险' : '低风险';
}

function priorityChipClass(priority: 'P1' | 'P2' | 'P3') {
	return priority === 'P1' ? 'priority-chip--p1' : priority === 'P2' ? 'priority-chip--p2' : 'priority-chip--p3';
}

function planStatusColor(status: DisposalPlanStatus) {
	return status === 'SELECTED' ? 'success' : status === 'REJECTED' ? 'error' : 'warning';
}

function executionStatusColor(status: SandboxExecutionStatus) {
	return status === 'SUCCEEDED' ? 'success' : status === 'FAILED' ? 'error' : 'info';
}

function formatTime(value: string) {
	const date = new Date(value);
	return Number.isNaN(date.getTime())
		? value
		: date.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
}

function signed(value: number | null | undefined) {
	if (value === null || value === undefined) {
		return '—';
	}
	return value > 0 ? `+${value}` : `${value}`;
}

function deltaClass(value: number | null | undefined, lowerIsBetter = true) {
	if (value === null || value === undefined || value === 0) {
		return 'delta-neutral';
	}
	const improving = lowerIsBetter ? value < 0 : value > 0;
	return improving ? 'delta-good' : 'delta-bad';
}

function scopeLabel(sku: string | null, supplierId: string | null) {
	if (sku) {
		return `SKU ${sku}`;
	}
	if (supplierId) {
		return `供应商 ${supplierId}`;
	}
	return '全部风险订单';
}

function parameterLabel(value: DisposalSimulation) {
	return value.scenario === 'INVENTORY_REPLENISHMENT'
		? `${value.sku} 补货 +${value.replenishmentQuantity} 件`
		: `${value.supplierId} 额外延期 ${value.additionalDelayDays} 天`;
}

function explanationSourceLabel(plan: DisposalPlan) {
	return plan.explanationModelAssisted ? '模型解释 · 依据规则结果生成' : '规则降级说明';
}

function comparisonCandidateParameter(candidate: DisposalScenarioCandidate) {
	return candidate.scenario === 'INVENTORY_REPLENISHMENT'
		? `补货 +${candidate.replenishmentQuantity} 件`
		: `额外延期 ${candidate.additionalDelayDays} 天`;
}

function comparisonOutcomeColor(outcome: DisposalScenarioComparisonOutcome) {
	return outcome === 'RECOMMENDED' ? 'success' : outcome === 'TIE_BREAK' ? 'info' : 'warning';
}

function rankingFor(candidateId: string): DisposalScenarioRanking | undefined {
	return scenarioComparison.value?.rankings.find((ranking) => ranking.candidateId === candidateId);
}

function rankLabel(ranking: DisposalScenarioRanking | undefined) {
	if (!ranking) {
		return '—';
	}
	return ranking.rank === null ? '不可比' : `#${ranking.rank}`;
}

function candidateLinkedPlan(candidateId: string) {
	return scenarioComparison.value?.candidates.find((candidate) => candidate.candidateId === candidateId)?.linkedPlanId || null;
}

function shortFingerprint(value: string) {
	return value.length > 12 ? `${value.slice(0, 12)}…` : value;
}

function addComparisonCandidate() {
	if (comparisonDraft.candidates.length >= MAX_COMPARISON_CANDIDATES) {
		return;
	}
	const previous = comparisonDraft.candidates[comparisonDraft.candidates.length - 1];
	if (comparisonDraft.scenario === 'INVENTORY_REPLENISHMENT') {
		const previousQuantity = previous ? Number(previous.replenishmentQuantity) : Number.NaN;
		comparisonDraft.candidates.push({
			label: '',
			replenishmentQuantity: Number.isFinite(previousQuantity) ? previousQuantity + 50 : 50,
			additionalDelayDays: null,
		});
	} else {
		const previousDays = previous ? Number(previous.additionalDelayDays) : Number.NaN;
		comparisonDraft.candidates.push({
			label: '',
			replenishmentQuantity: null,
			additionalDelayDays: Number.isFinite(previousDays) ? previousDays + 7 : 7,
		});
	}
	comparisonTouched.value = false;
}

function removeComparisonCandidate(index: number) {
	if (comparisonDraft.candidates.length <= MIN_COMPARISON_CANDIDATES) {
		return;
	}
	comparisonDraft.candidates.splice(index, 1);
	comparisonTouched.value = false;
}

async function submitComparison() {
	comparisonTouched.value = true;
	if (validateComparisonDraft(comparisonDraft)) {
		return;
	}
	const result = await runComparison(buildComparisonRequest(comparisonDraft));
	if (result) {
		linkPlanId.value = '';
		scrollToId('disposal-comparison-result');
	}
}

async function openComparisonFromHistory(item: DisposalScenarioComparisonSummary) {
	const result = await openComparison(item.comparisonId);
	if (result) {
		scrollToId('disposal-comparison-result');
	}
}

async function saveComparisonCandidate(candidateId: string) {
	const comparison = scenarioComparison.value;
	const candidate = comparison?.candidates.find((item) => item.candidateId === candidateId);
	if (!comparison || !candidate) {
		return;
	}
	const suffix = candidate.label.trim() || comparisonCandidateParameter(candidate);
	const result = await saveComparisonCandidateAsPlan(comparison.comparisonId, candidateId, {
		title: `${comparison.title} · ${suffix}`.slice(0, 200),
		generateExplanation: true,
		...(props.taskId ? { taskId: props.taskId } : {}),
	});
	if (result) {
		scrollToId('disposal-plan-detail');
	}
}

async function submitComparisonLink() {
	linkFormError.value = '';
	const comparison = scenarioComparison.value;
	if (!comparison) {
		return;
	}
	if (!linkCandidateId.value) {
		linkFormError.value = '请选择需要关联的候选方案。';
		return;
	}
	const planId = linkPlanId.value.trim();
	if (!planId) {
		linkFormError.value = '请填写已有处置方案编号。';
		return;
	}
	const result = await linkComparisonCandidatePlan(comparison.comparisonId, linkCandidateId.value, planId);
	if (result) {
		await openPlanAndScroll(planId);
	}
}

function scrollToId(id: string) {
	void nextTick().then(() => document.getElementById(id)?.scrollIntoView({ behavior: scrollBehavior(), block: 'start' }));
}

async function applyScope() {
	scopeError.value = '';
	const nextScope: RecommendationScope = {};
	if (scopeMode.value === 'SKU') {
		if (!scopeSku.value) {
			scopeError.value = '请选择需要查看的 SKU。';
			return;
		}
		nextScope.sku = scopeSku.value;
	}
	if (scopeMode.value === 'SUPPLIER') {
		if (!scopeSupplierId.value) {
			scopeError.value = '请选择需要查看的供应商。';
			return;
		}
		nextScope.supplierId = scopeSupplierId.value;
	}
	appliedScope.value = nextScope;
	await loadRecommendations(nextScope);
}

function refreshRecommendations() {
	return loadRecommendations(appliedScope.value);
}

function useRecommendation(recommendation: DisposalRecommendation) {
	if (recommendation.actionType === 'REPLENISH_INVENTORY') {
		draft.scenario = 'INVENTORY_REPLENISHMENT';
		draft.sku = recommendation.sku || draft.sku;
		draft.replenishmentQuantity = '';
	} else if (recommendation.actionType === 'FOLLOW_UP_SUPPLIER') {
		draft.scenario = 'SUPPLIER_DELAY';
		draft.supplierId = recommendation.supplierId || draft.supplierId;
		draft.additionalDelayDays = 7;
	}
	draftTouched.value = false;
	scrollToId('disposal-simulation-form');
}

async function submitSimulation() {
	draftTouched.value = true;
	if (validateSimulationDraft(draft)) {
		return;
	}
	const request = buildSimulationRequest(draft);
	const result = await runSimulation(request);
	if (result) {
		scrollToId('disposal-simulation-result');
	}
}

async function openSimulationFromHistory(item: DisposalSimulationSummary) {
	const result = await openSimulation(item.simulationId, item.planId);
	if (result) {
		scrollToId('disposal-simulation-result');
	}
}

async function viewPlanSimulation(value: DisposalSimulation) {
	const result = await openSimulation(value.simulationId, activePlan.value?.planId);
	if (result) {
		scrollToId('disposal-simulation-result');
	}
}

async function submitPlan() {
	planFormError.value = '';
	if (!planTitle.value.trim()) {
		planFormError.value = '请填写方案标题。';
		return;
	}
	linkTask.value = linkTask.value && Boolean(props.taskId);
	const attachCurrentSimulation = attachSimulation.value && Boolean(simulation.value) && !simulationPlanId.value;
	const request = {
		title: planTitle.value.trim(),
		generateExplanation: generateExplanation.value,
	} as {
		title: string;
		generateExplanation: boolean;
		taskId?: string;
		sku?: string;
		supplierId?: string;
		simulationId?: string;
	};
	if (linkTask.value && props.taskId) {
		request.taskId = props.taskId;
	}
	if (attachCurrentSimulation && simulation.value) {
		request.simulationId = simulation.value.simulationId;
		if (simulation.value.scenario === 'INVENTORY_REPLENISHMENT' && simulation.value.sku) {
			request.sku = simulation.value.sku;
		}
		if (simulation.value.scenario === 'SUPPLIER_DELAY' && simulation.value.supplierId) {
			request.supplierId = simulation.value.supplierId;
		}
	} else {
		if (appliedScope.value.sku) {
			request.sku = appliedScope.value.sku;
		}
		if (appliedScope.value.supplierId) {
			request.supplierId = appliedScope.value.supplierId;
		}
	}
	const saved = await savePlan(request);
	if (saved) {
		scrollToId('disposal-plan-detail');
	}
}

async function submitPlanStatus(status: DisposalPlanStatus) {
	const plan = activePlan.value;
	if (!plan) {
		return;
	}
	await updatePlanStatus(
		plan.planId,
		status,
		status === 'SELECTED' ? selectedRecommendationId.value : undefined,
	);
}

async function openPlanAndScroll(planId: string) {
	lastRequestedPlanId.value = planId;
	const result = await openPlan(planId);
	if (result) {
		scrollToId('disposal-plan-detail');
	}
}

function openLinkedPlan(planId: string | null) {
	if (planId) {
		void openPlanAndScroll(planId);
	}
}

async function startPlanExecution() {
	const plan = activePlan.value;
	if (!plan) {
		return;
	}
	const result = await startExecution(plan.planId);
	if (result) {
		scrollToId('disposal-execution-result');
	}
}

async function openExecutionFromHistory(item: SandboxExecutionSummary) {
	const result = await openExecution(item.executionId);
	if (result) {
		scrollToId('disposal-execution-result');
	}
}

defineExpose({ refreshAfterConfirmedProposal });

onMounted(() => {
	void initialize();
});
</script>

<style scoped>
.disposal-workspace {
	--dp-ink: var(--cp-ink);
	--dp-muted: var(--cp-muted);
	--dp-line: var(--cp-line-soft);
	display: flex;
	flex-direction: column;
	gap: 18px;
	color: var(--dp-ink);
}

.disposal-panel {
	min-width: 0;
	overflow: hidden;
	background: var(--cp-surface-glass);
	border: 1px solid var(--cp-line-soft);
	border-radius: var(--cp-radius-lg);
	box-shadow: var(--cp-shadow-soft);
	-webkit-backdrop-filter: blur(12px) saturate(1.08);
	backdrop-filter: blur(12px) saturate(1.08);
}

.disposal-heading {
	display: flex;
	align-items: flex-start;
	justify-content: space-between;
	gap: 18px;
	padding: 22px 23px 19px;
	border-bottom: 1px solid var(--cp-line-soft);
}

.disposal-heading-main {
	display: flex;
	align-items: flex-start;
	gap: 13px;
	min-width: 0;
}

.disposal-heading-main > .disposal-emblem {
	width: 43px;
	height: 43px;
	flex: 0 0 43px;
	margin-top: 1px;
	border-radius: 13px;
}

.disposal-kicker {
	color: #5d748b;
	font-size: 13px;
	font-weight: 750;
	letter-spacing: 0.14em;
	line-height: 1.5;
}

.disposal-title {
	margin: 4px 0 0;
	color: #2b435f;
	font-size: 19px;
	font-weight: 720;
	letter-spacing: -0.025em;
	line-height: 1.35;
}

.disposal-subtitle {
	max-width: 720px;
	margin: 5px 0 0;
	color: #627082;
	font-size: 13px;
	line-height: 1.7;
}

.disposal-tags {
	display: flex;
	flex-wrap: wrap;
	gap: 6px;
	justify-content: flex-end;
}

.disposal-tag {
	padding: 5px 8px;
	color: #5f7389;
	background: #f4f8fd;
	border: 1px solid #e6eff9;
	border-radius: 999px;
	font-size: 13px;
	font-weight: 650;
	white-space: nowrap;
}

.disposal-grid {
	display: grid;
	grid-template-columns: minmax(0, 1.02fr) minmax(360px, 0.98fr);
}

.recommendation-pane,
.simulation-pane {
	min-width: 0;
	padding: 21px 22px 20px;
}

.simulation-pane {
	border-left: 1px solid var(--cp-line-soft);
	background: rgba(248, 251, 255, 0.4);
}

.pane-heading {
	display: flex;
	align-items: flex-start;
	justify-content: space-between;
	gap: 14px;
	margin-bottom: 14px;
}

.pane-heading-main {
	display: flex;
	min-width: 0;
	align-items: flex-start;
	gap: 10px;
}

.pane-heading-main > div {
	min-width: 0;
}

.pane-heading-main :deep(.role-icon) {
	margin-top: 1px;
}

.pane-title {
	margin: 3px 0 0;
	color: #3d5777;
	font-size: 15px;
	font-weight: 700;
	letter-spacing: -0.015em;
}

.pane-subtitle {
	max-width: 560px;
	margin: 4px 0 0;
	color: #647181;
	font-size: 13px;
	line-height: 1.65;
}

.count-pill {
	flex: 0 0 auto;
	padding: 4px 8px;
	color: #5f738b;
	background: #f1f7fd;
	border: 1px solid #e3eef9;
	border-radius: 999px;
	font-size: 13px;
	font-weight: 650;
	white-space: nowrap;
}

.engine-badge {
	display: inline-flex;
	align-items: center;
	gap: 4px;
	flex: 0 0 auto;
	padding: 4px 7px;
	color: #4f657e;
	background: #eef5fd;
	border: 1px solid #e0ebf8;
	border-radius: 6px;
	font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
	font-size: 13px;
	font-weight: 700;
	letter-spacing: 0.04em;
}

.scope-toolbar {
	margin-bottom: 13px;
	padding: 11px;
	background: rgba(246, 250, 255, 0.8);
	border: 1px solid #e7eff8;
	border-radius: 11px;
}

.scope-fields {
	display: flex;
	align-items: center;
	gap: 7px;
}

.scope-mode-field { flex: 0 0 156px; }
.scope-target-field { flex: 1 1 160px; }
.scope-apply { flex: 0 0 auto; min-height: 36px; }

.scope-meta {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 8px;
	margin-top: 8px;
	color: #667381;
	font-size: 13px;
}

.disposal-field :deep(.v-field) {
	border-radius: 9px;
	background: rgba(252, 254, 255, 0.92);
	font-size: 13px;
}

.disposal-field :deep(.v-field__outline) { color: #dbe8f6; }
.disposal-field :deep(.v-field__outline__notch) { color: var(--cp-muted); }
.disposal-field :deep(.v-label) { font-size: 13px; opacity: 1; }
.disposal-field :deep(.v-field__input) { min-height: 34px; padding-top: 0; padding-bottom: 0; font-size: 13px; }
.disposal-field :deep(.v-field__append-inner) { padding-top: 6px; }
.disposal-field :deep(.v-counter) { color: #68717b; font-size: 13px; }

.disposal-alert {
	margin-top: 10px;
	border-radius: 9px;
	font-size: 13px;
}

.alert-row {
	display: flex;
	align-items: center;
	justify-content: space-between;
	flex-wrap: wrap;
	gap: 6px;
}

.inline-error {
	margin: 8px 0 0;
	color: #9e5e68;
	font-size: 13px;
	line-height: 1.55;
}

.panel-state {
	display: flex;
	align-items: center;
	justify-content: center;
	gap: 8px;
	min-height: 120px;
	color: #65717f;
	font-size: 13px;
	text-align: center;
}

.recommendation-list {
	display: flex;
	flex-direction: column;
	gap: 10px;
}

.recommendation-card {
	padding: 13px 14px 11px;
	background: var(--cp-surface);
	border: 1px solid var(--cp-line-soft);
	border-radius: var(--cp-radius-sm);
	transition: border-color var(--cp-duration-fast) var(--cp-ease-soft), box-shadow var(--cp-duration-fast) var(--cp-ease-soft);
}

.recommendation-card:hover {
	border-color: var(--cp-line);
	box-shadow: 0 4px 14px rgba(48, 84, 128, 0.045);
}

.recommendation-top {
	display: flex;
	align-items: center;
	gap: 7px;
}

.priority-chip {
	display: inline-flex;
	align-items: center;
	justify-content: center;
	flex: 0 0 auto;
	min-width: 52px;
	padding: 3px 7px;
	border: 1px solid transparent;
	border-radius: 6px;
	font-size: 13px;
	font-weight: 750;
	white-space: nowrap;
}

.priority-chip--p1 { color: #954d5a; background: #fff0f2; border-color: #f6dde1; }
.priority-chip--p2 { color: #8f6b3c; background: #fff7e9; border-color: #f3e6cd; }
.priority-chip--p3 { color: #5e748e; background: #f1f6fc; border-color: #e4eef8; }

.recommendation-score {
	margin-left: auto;
	color: #66727f;
	font-size: 13px;
	font-variant-numeric: tabular-nums;
}

.recommendation-heading {
	display: flex;
	align-items: baseline;
	justify-content: space-between;
	gap: 10px;
	margin-top: 8px;
}

.recommendation-heading h4 {
	min-width: 0;
	color: #405a78;
	font-size: 12px;
	font-weight: 700;
	line-height: 1.45;
}

.recommendation-heading > span {
	flex: 0 0 auto;
	color: #637182;
	font-size: 13px;
}

.recommendation-action {
	margin: 6px 0 0;
	color: #5e748c;
	font-size: 13px;
	line-height: 1.7;
}

.recommendation-meta {
	display: flex;
	align-items: center;
	flex-wrap: wrap;
	gap: 5px;
	margin-top: 8px;
}

.meta-token {
	padding: 2px 6px;
	color: #4a6481;
	background: #f0f6fd;
	border: 1px solid #e3eef9;
	border-radius: 5px;
	font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
	font-size: 13px;
}

.meta-orders {
	color: #677382;
	font-size: 13px;
	overflow-wrap: anywhere;
}

.expected-improvement {
	display: flex;
	align-items: flex-start;
	gap: 6px;
	margin-top: 8px;
	padding: 7px 8px;
	color: #5f7762;
	background: #f3f9f4;
	border-radius: 7px;
	font-size: 13px;
	line-height: 1.6;
}

.expected-improvement :deep(.v-icon) { flex: 0 0 auto; margin-top: 1px; color: #5d7762; }
.expected-improvement strong { margin-right: 5px; color: #59795f; }

.rule-details {
	margin-top: 8px;
	border-top: 1px dashed #edf2f8;
}

.rule-details summary {
	padding-top: 8px;
	color: #5e748d;
	font-size: 13px;
	font-weight: 650;
	cursor: pointer;
	list-style: none;
}

.rule-details summary::-webkit-details-marker { display: none; }
.rule-details summary::before { content: '＋'; margin-right: 4px; color: #6f6f6f; }
.rule-details[open] summary::before { content: '－'; }

.rule-details-body {
	padding: 5px 0 2px;
	color: #647383;
	font-size: 13px;
	line-height: 1.7;
}

.rule-details-body p { margin: 5px 0 0; overflow-wrap: anywhere; }
.rule-details-body strong { display: block; margin-bottom: 1px; color: #60748a; font-size: 13px; font-weight: 700; }

.recommendation-actions {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 8px;
	margin-top: 7px;
	padding-top: 8px;
	border-top: 1px solid #f0f5fa;
}

.disposal-link {
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

.disposal-link:hover,
.disposal-link:focus-visible { color: #315fad; text-decoration: underline; outline: none; }
.disposal-link:disabled { color: #69717b; cursor: wait; text-decoration: none; }
.disposal-link--strong { font-size: 13px; }

.manual-note { color: #8b6c45; font-size: 13px; }
.recommendation-time { color: #69717b; font-size: 13px; }

.pane-footnote {
	margin: 11px 0 0;
	color: #6a737f;
	font-size: 13px;
	line-height: 1.6;
}

.simulation-form {
	padding: 13px;
	background: rgba(252, 254, 255, 0.9);
	border: 1px solid #e6eef8;
	border-radius: 12px;
}

.simulation-fields {
	display: grid;
	grid-template-columns: minmax(0, 1.25fr) minmax(120px, 0.75fr);
	gap: 8px;
	margin-top: 8px;
}

.simulation-submit {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 10px;
	margin-top: 9px;
	color: #677380;
	font-size: 13px;
}

.simulation-run { min-height: 35px; border-radius: 9px; font-size: 13px; font-weight: 650; }

.simulation-result {
	margin-top: 13px;
	padding: 14px;
	background: var(--cp-surface);
	border: 1px solid var(--cp-line);
	border-radius: var(--cp-radius-sm);
}

.result-heading {
	display: flex;
	align-items: flex-start;
	justify-content: space-between;
	gap: 10px;
}

.result-title-row {
	display: flex;
	align-items: center;
	flex-wrap: wrap;
	gap: 6px;
}

.result-title-row h4 { color: #3e5978; font-size: 12px; font-weight: 700; }

.result-heading p { margin: 4px 0 0; color: #657381; font-size: 13px; line-height: 1.55; overflow-wrap: anywhere; }

.rule-badge,
.isolation-badge {
	padding: 3px 6px;
	border-radius: 5px;
	font-size: 13px;
	font-weight: 700;
	white-space: nowrap;
}

.rule-badge { color: #50739a; background: #edf4fd; border: 1px solid #deeaf9; }
.isolation-badge { color: #577865; background: #f0f8f3; border: 1px solid #e0efe6; }
.isolation-badge--bad { color: #a55d68; background: #fff0f2; border-color: #f6dde1; }

.metric-table-wrap { overflow-x: auto; margin-top: 11px; }

.metric-table {
	width: 100%;
	min-width: 340px;
	border-collapse: collapse;
	color: #607288;
	font-size: 13px;
	font-variant-numeric: tabular-nums;
}

.metric-table th {
	padding: 7px 7px;
	color: #657281;
	background: #f4f8fd;
	border-bottom: 1px solid #e7eff8;
	font-size: 13px;
	font-weight: 700;
	text-align: right;
	white-space: nowrap;
}

.metric-table th:first-child,
.metric-table td:first-child { text-align: left; }

.metric-table td {
	padding: 7px 7px;
	border-bottom: 1px solid #eef3f9;
	text-align: right;
	white-space: nowrap;
}

.metric-table tbody tr:last-child td { border-bottom: 0; }
.metric-table td:nth-child(3) { color: #456385; font-weight: 650; }
.delta-good { color: #474747 !important; font-weight: 700; }
.delta-bad { color: #717171 !important; font-weight: 700; }
.delta-neutral { color: #6c6c6c !important; font-weight: 600; }

.result-summary-line {
	display: flex;
	flex-wrap: wrap;
	gap: 6px 12px;
	margin-top: 9px;
	padding-top: 8px;
	border-top: 1px solid #eef3f9;
	color: #66727f;
	font-size: 13px;
}

.result-summary-line strong { margin-left: 3px; color: #5f738a; font-weight: 650; overflow-wrap: anywhere; }

.impact-list { display: flex; flex-direction: column; gap: 7px; margin-top: 10px; }

.impact-card {
	padding: 9px 10px;
	background: #f9fbfe;
	border: 1px solid #eaf1f8;
	border-radius: 8px;
}

.impact-empty { padding: 10px; color: #627664; background: #f4f9f5; border-radius: 8px; font-size: 13px; }

.impact-heading {
	display: flex;
	align-items: flex-start;
	justify-content: space-between;
	gap: 8px;
}

.impact-heading > div:first-child { min-width: 0; }
.impact-heading strong,
.impact-heading span { display: block; }
.impact-heading strong { color: #4c6683; font-size: 13px; }
.impact-heading span { margin-top: 2px; color: #66717d; font-size: 13px; }
.impact-risk { display: flex; align-items: center; gap: 3px; flex: 0 0 auto; }
.impact-risk :deep(.v-chip) { font-size: 13px; }

.impact-meta {
	display: flex;
	flex-wrap: wrap;
	gap: 4px 12px;
	margin-top: 6px;
	color: #657381;
	font-size: 13px;
}

.impact-meta strong { color: #607389; font-weight: 650; }
.impact-changes { margin: 5px 0 0; padding-left: 14px; color: #647181; font-size: 13px; line-height: 1.55; }
.impact-changes li::marker { color: #647382; }

.result-details { margin-top: 8px; border-top: 1px solid #eef3f9; }

.simulation-disclaimer {
	display: flex;
	align-items: flex-start;
	gap: 5px;
	margin: 9px 0 0;
	padding-top: 8px;
	border-top: 1px solid #eef3f9;
	color: #68737d;
	font-size: 13px;
	line-height: 1.55;
}

.simulation-disclaimer :deep(.v-icon) { flex: 0 0 auto; margin-top: 1px; }

.simulation-empty {
	display: flex;
	align-items: flex-start;
	gap: 10px;
	margin-top: 13px;
	padding: 15px;
	color: #64717f;
	background: rgba(245, 250, 255, 0.7);
	border: 1px dashed #dbe8f6;
	border-radius: 11px;
}

.simulation-empty :deep(.v-icon) { color: #5d7288; }
.simulation-empty strong,
.simulation-empty span { display: block; }
.simulation-empty strong { color: #60748a; font-size: 13px; }
.simulation-empty span { margin-top: 3px; font-size: 13px; line-height: 1.65; }

.plan-save-card {
	margin-top: 13px;
	padding: 13px;
	background: var(--cp-surface);
	border: 1px solid var(--cp-line-soft);
	border-radius: var(--cp-radius-sm);
}

.plan-save-heading,
.mini-heading,
.plan-section-heading {
	display: flex;
	align-items: flex-start;
	justify-content: space-between;
	gap: 10px;
}

.plan-save-heading { margin-bottom: 9px; }
.plan-save-heading h4,
.mini-heading h4 { margin: 3px 0 0; color: #456180; font-size: 13px; font-weight: 700; }

.plan-options {
	display: flex;
	flex-wrap: wrap;
	align-items: center;
	gap: 4px 12px;
	margin-top: 8px;
}

.plan-option { flex: 0 0 auto; }
.plan-option :deep(.v-label) { font-size: 13px; opacity: 1; }
.plan-option--switch { flex-basis: 100%; }
.plan-hint { margin: 6px 0 0; color: #6b737d; font-size: 13px; line-height: 1.55; }

.plan-save-actions {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 10px;
	margin-top: 9px;
	color: #677380;
	font-size: 13px;
}

.simulation-history {
	margin-top: 14px;
	padding-top: 12px;
	border-top: 1px solid #e8f0f8;
}

.mini-heading { margin-bottom: 7px; }

.history-sim-item {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 9px;
	width: 100%;
	padding: 8px 9px;
	color: inherit;
	background: rgba(252, 254, 255, 0.9);
	border: 1px solid #eaf1f8;
	border-radius: 8px;
	font: inherit;
	text-align: left;
	cursor: pointer;
}

.history-sim-item + .history-sim-item { margin-top: 5px; }
.history-sim-item:hover,
.history-sim-item:focus-visible { background: #f6fafe; border-color: #d6e5f5; outline: none; }
.history-sim-item > div:first-child { min-width: 0; }
.history-sim-item strong,
.history-sim-item span { display: block; }
.history-sim-item strong { color: #5a7490; font-size: 13px; }
.history-sim-item > div:first-child span { margin-top: 2px; color: #677380; font-size: 13px; overflow-wrap: anywhere; }
.history-sim-meta { display: flex; align-items: center; flex-wrap: wrap; justify-content: flex-end; gap: 3px 7px; flex: 0 0 auto; color: #66707d; font-size: 13px; text-align: right; }
.history-sim-meta > span { display: inline; }
.linked-plan { color: #577492; }
.history-time { color: #69717b; font-size: 13px; white-space: nowrap; }
.mini-empty, .mini-loading { padding: 8px 2px; color: #677380; font-size: 13px; line-height: 1.6; }

.disposal-records {
	display: grid;
	grid-template-columns: minmax(0, 0.92fr) minmax(0, 1.08fr);
	align-items: start;
	gap: 18px;
}

.plan-history-panel,
.plan-detail-panel { padding: 20px 21px; }

.plan-history-heading { margin-bottom: 10px; }
.plan-history-tools { display: flex; align-items: center; gap: 4px; flex: 0 0 auto; }
.plan-status-field { width: 130px; flex: 0 0 130px; }

.plan-history-list { display: flex; flex-direction: column; gap: 7px; }

.plan-history-item {
	overflow: hidden;
	background: rgba(252, 254, 255, 0.9);
	border: 1px solid #e8eff7;
	border-radius: 10px;
	transition: border-color 0.15s ease, background-color 0.15s ease;
}

.plan-history-item:hover, .plan-history-item:focus-within { background: #f7fbff; border-color: #d3e3f4; }
.plan-history-item--active { border-color: #bfd9f6; box-shadow: inset 3px 0 0 #6f9fda; }

.plan-history-main {
	display: block;
	width: 100%;
	padding: 10px 11px;
	color: inherit;
	font: inherit;
	text-align: left;
	background: transparent;
	border: 0;
	cursor: pointer;
}

.plan-history-main:focus-visible { outline: 2px solid #9fc1e8; outline-offset: -2px; }
.plan-history-main:disabled { cursor: wait; opacity: 0.7; }
.plan-history-top { display: flex; align-items: center; justify-content: space-between; gap: 7px; }
.plan-history-title { display: block; margin-top: 6px; color: #4d6682; font-size: 13px; font-weight: 650; line-height: 1.45; }
.plan-history-meta { display: flex; flex-wrap: wrap; gap: 4px 9px; margin-top: 5px; color: #677381; font-size: 13px; line-height: 1.5; }
.plan-history-foot { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-top: 6px; color: #6a737f; font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 13px; }
.plan-history-foot span:last-child { color: #567495; font-family: inherit; font-weight: 650; }
.plan-history-empty { padding: 16px 4px; color: #677382; font-size: 13px; line-height: 1.65; text-align: center; }
.plan-history-empty--error { color: #9e5e68; }
.plan-history-empty :deep(.disposal-link) { margin-left: 6px; }

.plan-pagination {
	display: flex;
	align-items: center;
	justify-content: space-between;
	flex-wrap: wrap;
	gap: 6px 10px;
	margin-top: 10px;
	padding-top: 9px;
	border-top: 1px solid #edf2f8;
	color: #657281;
	font-size: 13px;
	line-height: 1.5;
}

.plan-pagination-controls { display: flex; align-items: center; gap: 2px; }
.plan-page-size { width: 88px; flex: 0 0 88px; }
.plan-page-size :deep(.v-field__input) { min-height: 28px; padding-top: 0; padding-bottom: 0; color: #5e738d; font-size: 13px; }
.plan-pagination-controls :deep(.v-btn) { width: 26px; height: 26px; }

.plan-detail-heading { margin-bottom: 10px; }
.plan-detail-empty {
	display: flex;
	align-items: flex-start;
	gap: 10px;
	min-height: 120px;
	padding: 18px;
	color: #677382;
	background: rgba(247, 251, 255, 0.7);
	border: 1px dashed #dbe8f6;
	border-radius: 10px;
	font-size: 13px;
	line-height: 1.65;
}

.plan-detail-empty--error { color: #9e5e68; background: #fffafa; border-color: #f2dfe2; }
.plan-detail-empty :deep(.v-icon) { flex: 0 0 auto; color: #5d7288; }
.plan-detail-empty strong,
.plan-detail-empty span { display: block; }
.plan-detail-empty strong { color: #60748a; font-size: 13px; }
.plan-detail-empty span { margin-top: 3px; }

.plan-detail-summary {
	padding-bottom: 11px;
	border-bottom: 1px solid var(--cp-line-soft);
}

.plan-detail-title-row { display: flex; align-items: flex-start; justify-content: space-between; gap: 10px; }
.plan-detail-title-row h4 { min-width: 0; color: #3f5875; font-size: 14px; font-weight: 700; line-height: 1.45; }
.plan-detail-meta { display: flex; flex-wrap: wrap; gap: 5px 11px; margin-top: 6px; color: #647180; font-size: 13px; }
.plan-id-line { margin: 6px 0 0; color: #69717b; font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 13px; overflow-wrap: anywhere; }

.explanation-card {
	margin-top: 12px;
	padding: 12px;
	background: #f8fbfe;
	border: 1px solid #e7eef7;
	border-radius: 10px;
}

.explanation-card--model { background: #f7faff; border-color: #dfeaf8; }
.explanation-heading { display: flex; align-items: center; justify-content: space-between; gap: 8px; color: #5c728b; }
.explanation-heading > div { display: flex; align-items: center; gap: 5px; }
.explanation-heading strong { font-size: 13px; font-weight: 700; }
.explanation-card p { margin: 7px 0 0; color: #5c748d; font-size: 13px; line-height: 1.75; white-space: pre-wrap; }
.tool-call-note { color: #64717e; font-size: 13px; text-align: right; overflow-wrap: anywhere; }
.explanation-footnote { display: block; margin-top: 7px; color: #6a737f; font-size: 13px; line-height: 1.55; }

.plan-section { margin-top: 13px; padding-top: 11px; border-top: 1px solid var(--cp-line-soft); }
.plan-section-heading { margin-bottom: 7px; }
.plan-section-heading h4 { color: #59738f; font-size: 13px; font-weight: 700; }
.plan-section-heading > span { color: #67737f; font-size: 13px; }

.plan-section-heading--execution {
	align-items: center;
}

.plan-section-title {
	display: flex;
	min-width: 0;
	align-items: center;
	gap: 7px;
}

.plan-section-title h4 { color: var(--cp-role-execution-ink); }

.plan-section-title :deep(.role-icon) {
	width: 24px;
	height: 24px;
	flex-basis: 24px;
	border-radius: 8px;
}

.plan-recommendation-options { display: flex; flex-direction: column; gap: 5px; }

.plan-recommendation-option {
	display: flex;
	align-items: center;
	gap: 8px;
	width: 100%;
	padding: 8px 9px;
	color: inherit;
	background: #fbfdff;
	border: 1px solid #e9f0f8;
	border-radius: 8px;
	font: inherit;
	text-align: left;
	cursor: pointer;
}

.plan-recommendation-option:hover,
.plan-recommendation-option:focus-visible { background: #f5faff; border-color: #d4e4f5; outline: none; }
.plan-recommendation-option--selected { background: #f1f8ff; border-color: #bcd8f5; }
.plan-recommendation-option:disabled { cursor: wait; opacity: 0.7; }
.plan-option-copy { min-width: 0; flex: 1; }
.plan-option-copy strong,
.plan-option-copy small { display: block; }
.plan-option-copy strong { color: #5b7490; font-size: 13px; font-weight: 650; line-height: 1.45; }
.plan-option-copy small { margin-top: 2px; color: #65707d; font-size: 13px; line-height: 1.45; }

.plan-status-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 7px; margin-top: 9px; }

.plan-simulation-card { padding: 10px; background: #f8fbfe; border: 1px solid #e7eef7; border-radius: 9px; }
.plan-simulation-top { display: flex; align-items: flex-start; justify-content: space-between; gap: 8px; }
.plan-simulation-top strong { color: #59728e; font-size: 13px; line-height: 1.5; }
.plan-simulation-metrics { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 7px; margin: 8px 0; }
.plan-simulation-metrics > div { padding: 7px 8px; background: #fff; border: 1px solid #edf3f9; border-radius: 7px; }
.plan-simulation-metrics span,
.plan-simulation-metrics strong,
.plan-simulation-metrics small { display: block; }
.plan-simulation-metrics span { color: #66727e; font-size: 13px; }
.plan-simulation-metrics strong { margin-top: 2px; color: #58738f; font-size: 13px; font-variant-numeric: tabular-nums; }
.plan-simulation-metrics small { margin-top: 2px; font-size: 13px; }

.plan-citations { border: 1px solid #e8eff7; border-radius: 8px; overflow: hidden; }
.plan-citations :deep(.v-expansion-panel) { background: #fbfdff; }
.plan-citations :deep(.v-expansion-panel-title) { min-height: 36px; font-size: 13px; font-weight: 650; }
.plan-citations :deep(.v-expansion-panel-text__wrapper) { padding: 0 13px 11px; }
.citation-text { color: #647383; font-size: 13px; line-height: 1.7; }
.citation-text span { display: block; margin-bottom: 3px; color: #637283; font-size: 13px; }
.history-sim-item--compact { margin-top: 5px; }

.sandbox-chip {
	padding: 3px 7px;
	color: #5b62a8 !important;
	background: #f1f2ff;
	border: 1px solid #dfe1fa;
	border-radius: 999px;
	font-size: 13px !important;
	font-weight: 700;
	letter-spacing: 0.03em;
}
.layer-legend { display: flex; flex-wrap: wrap; gap: 5px; margin: 2px 0 8px; }
.layer-legend span { padding: 3px 7px; color: #637180; background: #f7fafd; border: 1px solid #e8f0f8; border-radius: 999px; font-size: 13px; }
.layer-legend .layer-legend-current { color: #545ca3; background: #f1f2ff; border-color: #d9ddfa; font-weight: 700; }
.layer-legend .layer-legend-real { color: #8a6b2f; background: #fffaf0; border-color: #f2e6c8; font-weight: 700; }
.execution-intro { margin: 0 0 9px; color: #64717f; font-size: 13px; line-height: 1.65; }
.execution-toolbar { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 8px; padding: 9px 10px; background: #f8fafc; border: 1px solid #e8eff7; border-radius: 9px; }
.execution-eligibility { display: flex; align-items: center; flex-wrap: wrap; gap: 7px; min-width: 0; }
.execution-eligibility-action { color: #5e738b; font-size: 13px; font-weight: 650; }
.execution-reason { color: #886b3c; font-size: 13px; line-height: 1.5; overflow-wrap: anywhere; }
.execution-hint { margin: 6px 0 0; color: #806e4f; font-size: 13px; }
.execution-checks { margin: 7px 0 0; padding-left: 16px; color: #64717f; font-size: 13px; line-height: 1.7; }
.execution-result { margin-top: 10px; padding: 11px; background: #fbfdff; border: 1px solid #e6eef7; border-radius: 10px; }
.execution-result-top { display: flex; align-items: flex-start; justify-content: space-between; gap: 8px; }
.execution-result-top h5 { margin: 0; color: #566f8c; font-size: 13px; font-weight: 700; }
.execution-result-top p { margin: 3px 0 0; color: #66717c; font-size: 13px; overflow-wrap: anywhere; }
.execution-meta-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 6px; margin: 9px 0; }
.execution-meta-grid > div { padding: 7px 8px; background: #fff; border: 1px solid #edf3f9; border-radius: 7px; }
.execution-meta-grid span,
.execution-meta-grid strong { display: block; }
.execution-meta-grid span { color: #67727f; font-size: 13px; }
.execution-meta-grid strong { margin-top: 2px; color: #58738f; font-size: 13px; font-weight: 650; overflow-wrap: anywhere; }
.execution-failure { margin: 8px 0; padding: 8px 9px; color: #a44c4c; background: #fff7f7; border: 1px solid #f5dede; border-radius: 7px; font-size: 13px; line-height: 1.6; }
.execution-failure strong { margin-right: 5px; }
.execution-subheading { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; margin-bottom: 6px; }
.execution-subheading h6,
.execution-draft-head h6,
.execution-history .mini-heading h6 { margin: 0; color: #59738f; font-size: 13px; font-weight: 700; }
.execution-subheading span { color: #67717b; font-size: 13px; }
.execution-steps ol { display: flex; flex-direction: column; gap: 6px; margin: 0; padding: 0; list-style: none; }
.execution-step { padding: 8px 9px; background: #fff; border: 1px solid #eaf1f8; border-left: 3px solid #b8dfc9; border-radius: 8px; }
.execution-step--failed { border-left-color: #e8a7a7; background: #fffafa; }
.execution-step-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.execution-step-head strong { color: #5b738e; font-size: 13px; }
.execution-step p { margin: 4px 0 0; color: #637181; font-size: 13px; line-height: 1.6; }
.execution-step p span { display: inline-block; min-width: 28px; margin-right: 5px; color: #68717c; font-weight: 650; }
.execution-step p.execution-step-failure { color: #a44c4c; }
.execution-step-foot { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-top: 5px; color: #6a737d; font-size: 13px; }
.execution-step-foot code { color: #647182; font-size: 13px; }
.execution-draft { margin-top: 9px; padding: 10px; background: #f7f9ff; border: 1px solid #e1e6fa; border-radius: 9px; }
.execution-draft-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 8px; }
.execution-draft-head p { margin: 3px 0 0; color: #637283; font-size: 13px; }
.execution-draft-head code { color: #69708c; font-size: 13px; overflow-wrap: anywhere; }
.execution-draft-meta { display: flex; flex-wrap: wrap; gap: 5px; margin: 7px 0; }
.execution-draft-meta span { padding: 3px 6px; color: #647290; background: #fff; border: 1px solid #e8ebf8; border-radius: 5px; font-size: 13px; }
.execution-draft-lists { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
.execution-draft-lists strong { color: #5e718c; font-size: 13px; }
.execution-draft-lists ul,
.execution-unresolved ul,
.execution-evidence ul { margin: 4px 0 0; padding-left: 14px; color: #657385; font-size: 13px; line-height: 1.65; }
.execution-draft-evidence { margin: 8px 0 0; color: #65717e; font-size: 13px; line-height: 1.6; }
.execution-final { margin-top: 9px; padding: 9px 10px; background: #f6fbf8; border: 1px solid #e2f0e8; border-radius: 8px; }
.execution-final strong,
.execution-unresolved strong,
.execution-evidence strong { color: #59728c; font-size: 13px; }
.execution-final p { margin: 4px 0 0; color: #617386; font-size: 13px; line-height: 1.65; }
.execution-unresolved { margin-top: 8px; padding: 9px 10px; background: #fffdf6; border: 1px solid #f2ead5; border-radius: 8px; }
.execution-unresolved strong { color: #8a6b2f; }
.execution-evidence { margin-top: 8px; }
.execution-disclaimer { margin: 9px 0 0; padding-top: 8px; color: #6a737c; border-top: 1px dashed #e6edf5; font-size: 13px; line-height: 1.6; }
.execution-history { margin-top: 10px; }
.execution-history .mini-heading > div { display: flex; align-items: baseline; gap: 7px; }
.execution-history .mini-heading h6 { color: #59738f; }
.execution-history-item { width: 100%; font: inherit; text-align: left; cursor: pointer; }
.execution-history-item--active { background: #f1f8ff; border-color: #bcd8f5; }
.execution-history-foot { margin: 6px 0 0; color: #69727c; font-size: 13px; }

.comparison-heading {
	align-items: flex-start;
	margin-bottom: 0;
	padding: 21px 22px 17px;
	border-bottom: 1px solid var(--cp-line-soft);
}

.comparison-heading-tools {
	display: flex;
	align-items: center;
	justify-content: flex-end;
	flex-wrap: wrap;
	gap: 6px;
	flex: 0 0 auto;
}

.comparison-grid {
	display: grid;
	grid-template-columns: minmax(0, 0.92fr) minmax(0, 1.08fr);
}

.comparison-builder {
	min-width: 0;
	padding: 19px 20px;
	background: rgba(249, 252, 255, 0.45);
	border-right: 1px solid var(--cp-line-soft);
}

.comparison-result-section {
	min-width: 0;
	padding: 19px 20px;
}

.comparison-form { padding: 12px; }
.comparison-form :deep(.v-field) { font-size: 13px; }

.comparison-candidate-heading {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 8px;
	margin-top: 12px;
	color: #5e748b;
	font-size: 13px;
	font-weight: 700;
}

.comparison-candidate-heading span:last-child {
	color: #67737f;
	font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
	font-size: 13px;
	font-weight: 600;
}

.comparison-candidate-list {
	display: flex;
	flex-direction: column;
	gap: 6px;
	margin-top: 6px;
}

.comparison-candidate-row {
	display: grid;
	grid-template-columns: 18px minmax(0, 1.15fr) minmax(0, 0.85fr) 24px;
	align-items: center;
	gap: 6px;
	padding: 7px 7px 7px 6px;
	background: #fbfdff;
	border: 1px solid #e9f0f8;
	border-radius: 9px;
}

.comparison-candidate-index {
	display: grid;
	width: 18px;
	height: 18px;
	place-items: center;
	color: #577290;
	background: #eff6fe;
	border: 1px solid #e1ecf9;
	border-radius: 50%;
	font-size: 13px;
	font-weight: 700;
	font-variant-numeric: tabular-nums;
}

.comparison-actions {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 8px;
	margin-top: 10px;
}

.comparison-form-note {
	margin: 9px 0 0;
	color: #66717d;
	font-size: 13px;
	line-height: 1.6;
}

.comparison-history {
	margin-top: 14px;
	padding-top: 12px;
	border-top: 1px solid #e8f0f8;
}

.comparison-empty {
	display: flex;
	align-items: flex-start;
	gap: 10px;
	min-height: 118px;
	padding: 17px;
	color: #677382;
	background: rgba(247, 251, 255, 0.7);
	border: 1px dashed #dbe8f6;
	border-radius: 11px;
}

.comparison-empty :deep(.v-icon) { flex: 0 0 auto; color: #5d7288; }
.comparison-empty strong,
.comparison-empty span { display: block; }
.comparison-empty strong { color: #60748a; font-size: 13px; }
.comparison-empty span { margin-top: 3px; font-size: 13px; line-height: 1.65; }

.comparison-result {
	padding: 14px;
	background: rgba(251, 253, 255, 0.92);
	border: 1px solid #dfeaf7;
	border-radius: 12px;
}

.comparison-recommendation {
	margin-top: 12px;
	padding: 12px;
	background: #f6fafe;
	border: 1px solid #e3eef9;
	border-radius: 10px;
}

.comparison-recommendation--tie_break { background: #fffdf6; border-color: #f2ead5; }
.comparison-recommendation--incomparable { background: #fdfaf6; border-color: #f0e6da; }

.comparison-recommendation-top {
	display: flex;
	align-items: center;
	flex-wrap: wrap;
	gap: 6px;
}

.comparison-recommendation-top strong { color: #46627f; font-size: 13px; }

.comparison-recommendation-summary {
	margin: 7px 0 0;
	color: #5c748d;
	font-size: 13px;
	line-height: 1.7;
}

.comparison-chip-list {
	display: flex;
	flex-wrap: wrap;
	gap: 4px;
	margin-top: 7px;
}

.comparison-chip-list span {
	padding: 3px 7px;
	color: #5a728d;
	background: #fff;
	border: 1px solid #e6eef8;
	border-radius: 999px;
	font-size: 13px;
	line-height: 1.45;
}

.comparison-recommendation-lists {
	display: grid;
	grid-template-columns: repeat(2, minmax(0, 1fr));
	gap: 10px;
	margin-top: 9px;
	padding-top: 8px;
	border-top: 1px dashed #e7eff8;
}

.comparison-recommendation-lists strong,
.comparison-warning strong { color: #60748a; font-size: 13px; }

.comparison-recommendation-lists ul,
.comparison-warning ul {
	margin: 4px 0 0;
	padding-left: 14px;
	color: #647383;
	font-size: 13px;
	line-height: 1.65;
}

.comparison-warning {
	display: grid;
	grid-template-columns: repeat(2, minmax(0, 1fr));
	gap: 10px;
	margin-top: 9px;
	padding: 9px 10px;
	background: #fffdf6;
	border: 1px solid #f2ead5;
	border-radius: 8px;
}

.comparison-warning strong { color: #8a6b2f; }

.comparison-baseline-line {
	display: flex;
	flex-wrap: wrap;
	gap: 5px 13px;
	margin-top: 11px;
	padding: 8px 10px;
	color: #64717f;
	background: #f7fafd;
	border: 1px solid #e9f0f8;
	border-radius: 8px;
	font-size: 13px;
}

.comparison-baseline-line strong {
	margin-left: 3px;
	color: #5b738e;
	font-weight: 700;
	font-variant-numeric: tabular-nums;
}

.comparison-table { min-width: 560px; }
.comparison-table td:first-child small { display: block; margin-top: 2px; color: #66717c; font-size: 13px; }
.comparison-row--recommended td { background: #f2f8ff; }

.comparison-delta-note {
	margin: 6px 0 0;
	color: #68717c;
	font-size: 13px;
	line-height: 1.55;
}

.comparison-ranking-list {
	display: flex;
	flex-direction: column;
	gap: 7px;
	margin-top: 12px;
}

.comparison-ranking-card {
	padding: 10px;
	background: #fbfdff;
	border: 1px solid #e8eff7;
	border-radius: 9px;
}

.comparison-ranking-card--recommended {
	background: #f4f9ff;
	border-color: #cfe2f6;
	box-shadow: inset 3px 0 0 #6f9fda;
}

.comparison-ranking-top {
	display: flex;
	align-items: flex-start;
	gap: 8px;
}

.comparison-ranking-title { min-width: 0; flex: 1; }
.comparison-ranking-title strong,
.comparison-ranking-title span { display: block; }
.comparison-ranking-title strong { color: #4f6a87; font-size: 13px; }
.comparison-ranking-title span { margin-top: 2px; color: #66727e; font-size: 13px; }

.comparison-ranking-tags {
	display: flex;
	flex-wrap: wrap;
	justify-content: flex-end;
	gap: 4px;
	flex: 0 0 auto;
}

.comparison-ranking-basis {
	margin: 6px 0 0;
	color: #617183;
	font-size: 13px;
	line-height: 1.6;
	overflow-wrap: anywhere;
}

.comparison-ranking-actions {
	display: flex;
	align-items: center;
	justify-content: space-between;
	flex-wrap: wrap;
	gap: 7px;
	margin-top: 8px;
	padding-top: 7px;
	border-top: 1px solid #eef3f9;
}

.rank-badge {
	display: grid;
	min-width: 26px;
	height: 26px;
	flex: 0 0 auto;
	padding: 0 6px;
	place-items: center;
	color: #517399;
	background: #eef5fd;
	border: 1px solid #dfeaf8;
	border-radius: 7px;
	font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
	font-size: 13px;
	font-weight: 750;
	white-space: nowrap;
}

.rank-badge--none { color: #7e6e56; background: #fffaf0; border-color: #f2e6c8; }

.rank-pill {
	padding: 2px 6px;
	color: #5e738b;
	background: #f2f7fd;
	border: 1px solid #e5eef8;
	border-radius: 999px;
	font-size: 13px;
	font-weight: 650;
	white-space: nowrap;
}

.comparison-tag {
	padding: 2px 6px;
	color: #647484;
	background: #f4f8fd;
	border: 1px solid #e9f0f8;
	border-radius: 999px;
	font-size: 13px;
	white-space: nowrap;
}

.comparison-tag--recommended { color: #3c73af; background: #edf5ff; border-color: #cfe2f6; font-weight: 700; }
.comparison-tag--warning { color: #886b37; background: #fffaf0; border-color: #f2e6c8; }

.comparison-bullets {
	margin: 4px 0 0;
	padding-left: 14px;
	color: #647383;
	font-size: 13px;
	line-height: 1.65;
}

.comparison-bullets li::marker { color: #647382; }

.comparison-link-card {
	margin-top: 12px;
	padding: 11px;
	background: #f8fbfe;
	border: 1px solid #e7eef7;
	border-radius: 9px;
}

.comparison-link-heading {
	display: flex;
	align-items: baseline;
	justify-content: space-between;
	gap: 8px;
}

.comparison-link-heading strong { color: #59738f; font-size: 13px; }
.comparison-link-heading span { color: #67737f; font-size: 13px; }

.comparison-link-fields {
	display: grid;
	grid-template-columns: minmax(0, 1.2fr) minmax(0, 0.8fr) auto;
	align-items: center;
	gap: 7px;
	margin-top: 8px;
}

.comparison-plan-ref {
	color: #617386;
	font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
	font-size: 13px;
	overflow-wrap: anywhere;
}

@keyframes dp-result-in {
	from {
		opacity: 0;
		transform: translateY(5px);
	}
	to {
		opacity: 1;
		transform: none;
	}
}

.simulation-result,
.comparison-result {
	animation: dp-result-in var(--cp-duration-base) var(--cp-ease-out) both;
}

@media (max-width: 1180px) {
	.disposal-grid { grid-template-columns: 1fr; }
	.simulation-pane { border-left: 0; border-top: 1px solid var(--cp-line-soft); }
	.disposal-records { grid-template-columns: 1fr; }
	.comparison-grid { grid-template-columns: 1fr; }
	.comparison-builder { border-right: 0; border-bottom: 1px solid var(--cp-line-soft); }
}

@media (max-width: 720px) {
	.disposal-heading { flex-direction: column; padding: 18px 16px 15px; }
	.disposal-tags { justify-content: flex-start; }
	.recommendation-pane,
	.simulation-pane { padding: 17px 15px; }
	.scope-fields { flex-wrap: wrap; }
	.scope-mode-field,
	.scope-target-field { flex: 1 1 140px; }
	.scope-apply { flex: 1 1 100%; }
	.simulation-fields { grid-template-columns: 1fr; }
	.result-title-row { align-items: flex-start; }
	.plan-history-panel,
	.plan-detail-panel { padding: 17px 15px; }
	.plan-history-heading { flex-direction: column; }
	.plan-history-tools { width: 100%; }
	.plan-status-field { flex: 1 1 auto; width: auto; }
	.plan-simulation-metrics { grid-template-columns: 1fr; }
	.execution-meta-grid { grid-template-columns: 1fr; }
	.execution-draft-lists { grid-template-columns: 1fr; }
	.execution-toolbar { align-items: flex-start; flex-direction: column; }
	.execution-toolbar .v-btn { width: 100%; }
	.comparison-heading { flex-direction: column; padding: 18px 16px 15px; }
	.comparison-heading-tools { justify-content: flex-start; }
	.comparison-builder,
	.comparison-result-section { padding: 16px 15px; }
	.comparison-candidate-row {
		grid-template-columns: 18px minmax(0, 1fr) 24px;
		grid-template-areas:
			"index name remove"
			". value value";
	}
	.comparison-candidate-index { grid-area: index; }
	.comparison-candidate-name { grid-area: name; }
	.comparison-candidate-value { grid-area: value; }
	.comparison-candidate-row :deep(.v-btn) { grid-area: remove; align-self: start; }
	.comparison-recommendation-lists,
	.comparison-warning,
	.comparison-link-fields { grid-template-columns: 1fr; }
	.comparison-ranking-top { flex-wrap: wrap; }
	.comparison-ranking-tags { justify-content: flex-start; }
}

@media (max-width: 380px) {
	.disposal-heading { padding-inline: 13px; }
	.recommendation-pane,
	.simulation-pane,
	.plan-history-panel,
	.plan-detail-panel,
	.comparison-builder,
	.comparison-result-section { padding-inline: 12px; }
	.pane-heading { flex-wrap: wrap; }
	.disposal-tags { gap: 4px; }
	.disposal-tag { white-space: normal; }
	.result-title-row { flex-direction: column; align-items: flex-start; }
	.recommendation-actions { flex-wrap: wrap; }
	.recommendation-top { flex-wrap: wrap; }
	.comparison-recommendation-top { flex-wrap: wrap; }
}

@media (prefers-reduced-motion: reduce) {
	.simulation-result,
	.comparison-result {
		animation: none;
	}
}

@media (prefers-reduced-transparency: reduce) {
	.disposal-panel { background: #fff; -webkit-backdrop-filter: none; backdrop-filter: none; }
}
</style>
