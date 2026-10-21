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
	<main class="home-shell">
		<section class="hero">
			<picture class="hero__media" aria-hidden="true">
				<source type="image/webp" srcset="/images/home/chainpilot-hero.webp" />
				<img
					class="hero__image"
					src="/images/home/chainpilot-hero.png"
					alt=""
					width="1792"
					height="1024"
					decoding="async"
				/>
			</picture>
			<div class="hero__content">
				<div class="hero__eyebrow">
					<ChainPilotMark :size="42" label="ChainPilot 品牌标识" />
					<span>ChainPilot · 供应链智能决策工作台</span>
				</div>

				<h1 class="hero__title">
					让每一次<em class="gradient-word gradient-word--decision">供应链决策</em>，<br />
					<em class="gradient-word gradient-word--evidence">都有据可依</em>。
				</h1>

				<p class="hero__subtitle">
					连接订单、库存与供应商数据，分析风险、比较方案，让每一步判断、确认与执行过程都清晰留痕。
				</p>

				<p class="hero__slogan" aria-label="看清风险，比较选择，稳步决策">
					<span class="slogan-word">看清风险</span>
					<span class="slogan-dot" aria-hidden="true">·</span>
					<span class="slogan-word">比较选择</span>
					<span class="slogan-dot" aria-hidden="true">·</span>
					<span class="slogan-word">稳步决策</span>
				</p>

				<div class="hero__actions">
					<NuxtLink class="hero-button hero-button--primary" to="/supply-chain">
						<v-icon icon="mdi-rocket-launch-outline" size="20" />
						进入供应链分析
					</NuxtLink>
					<a class="hero-button hero-button--secondary" href="#home-workflow">
						<v-icon icon="mdi-sitemap-outline" size="20" />
						查看工作流程
					</a>
				</div>
			</div>

			<div class="hero__scroll-hint" aria-hidden="true">
				<span />
			</div>
		</section>

		<section class="home-section capabilities" aria-labelledby="capabilities-title">
			<div class="section-heading">
				<p class="section-kicker">产品能力</p>
				<h2 id="capabilities-title" class="section-title">从看清风险到完成决策</h2>
				<p class="section-description">
					四个核心工作区覆盖分析、决策、模拟和记录，所有结果都以当前项目已接入的数据与工具为依据。
				</p>
			</div>

			<div class="capability-grid">
				<NuxtLink
					v-for="capability in capabilities"
					:key="capability.title"
					class="capability-card"
					:style="roleCssVars(capability.role)"
					:to="capability.to"
				>
					<div class="capability-card__icon">
						<v-icon :icon="capability.icon" size="22" />
					</div>
					<h3>{{ capability.title }}</h3>
					<p>{{ capability.description }}</p>
					<span class="capability-card__action">
						{{ capability.action }}
						<v-icon icon="mdi-arrow-right" size="16" />
					</span>
				</NuxtLink>
			</div>
		</section>

		<section id="home-workflow" class="home-section workflow" aria-labelledby="workflow-title">
			<div class="section-heading">
				<p class="section-kicker">使用流程</p>
				<h2 id="workflow-title" class="section-title">一条完整的决策路径</h2>
				<p class="section-description">
					系统展示每一步的依据，关键写入操作保留人工确认，演练结果与真实业务执行清晰区分。
				</p>
			</div>

			<ol class="workflow-list">
				<li
					v-for="(step, index) in workflow"
					:key="step.title"
					class="workflow-step"
					:style="{ '--workflow-index': index + 1 }"
				>
					<span class="workflow-step__number">{{ String(index + 1).padStart(2, '0') }}</span>
					<h3>{{ step.title }}</h3>
					<p>{{ step.description }}</p>
				</li>
			</ol>

			<p class="workflow-note">
				沙箱演练仅在项目受控环境中模拟执行，不会直接修改真实库存、订单或外部系统。
			</p>
		</section>

		<section class="home-section entries" aria-labelledby="entries-title">
			<div class="entry-panel">
				<div>
					<p class="section-kicker">继续工作</p>
					<h2 id="entries-title" class="section-title">进入你最常用的位置</h2>
					<p class="section-description">
						所有业务入口都直达真实页面，配置、知识和数据源也保留在同一导航体系中。
					</p>
				</div>
				<div class="entry-actions">
					<NuxtLink class="text-link" to="/chat">数据问答</NuxtLink>
					<NuxtLink class="text-link" to="/supply-chain?view=decision">自然语言决策</NuxtLink>
					<NuxtLink class="text-link" to="/supply-chain?view=disposal">处置与沙箱</NuxtLink>
					<NuxtLink class="text-link" to="/system/agents">智能体管理</NuxtLink>
				</div>
			</div>
		</section>

		<footer class="home-footer">
			<span>ChainPilot · 供应链智能决策工作台</span>
			<span>以当前业务数据和可用知识来源为依据</span>
		</footer>
	</main>
</template>

<script setup lang="ts">
import { roleCssVars, type ChainPilotRoleId } from '~/utils/supplyChainAgentRoles';

type HomeCapability = {
	title: string;
	description: string;
	action: string;
	icon: string;
	to: string;
	role: ChainPilotRoleId;
};

const capabilities: HomeCapability[] = [
	{
		title: '供应链分析',
		description: '把订单、库存和供应商信号放在同一视图，快速识别需要重点关注的履约风险。',
		action: '开始分析',
		icon: 'mdi-radar',
		to: '/supply-chain',
		role: 'risk',
	},
	{
		title: '智能决策',
		description: '用自然语言提出问题，由 Agent 调用已配置工具，并展示结果背后的业务依据。',
		action: '提出决策问题',
		icon: 'mdi-comment-processing-outline',
		to: '/supply-chain?view=decision',
		role: 'planning',
	},
	{
		title: '方案模拟',
		description: '比较补货、延期和处置方案对关键指标的影响，帮助理解不同选择之间的差异。',
		action: '进入处置沙箱',
		icon: 'mdi-chart-line',
		to: '/supply-chain?view=disposal',
		role: 'simulation',
	},
	{
		title: '可追溯执行',
		description: '保存经确认的方案，在沙箱中演练，并通过事件时间线回看真实执行记录。',
		action: '查看任务记录',
		icon: 'mdi-history',
		to: '/supply-chain?view=history',
		role: 'execution',
	},
];

const workflow = [
	{ title: '业务数据', description: '读取订单、库存与供应商信息。' },
	{ title: '风险分析', description: '识别交付压力与潜在履约风险。' },
	{ title: '方案比较', description: '模拟处置选择并比较关键指标。' },
	{ title: '人工确认', description: '由业务人员决定采用哪个方案。' },
	{ title: '沙箱演练', description: '验证执行步骤并保留过程证据。' },
];
</script>

<style scoped>
.home-shell {
	--home-title-size: clamp(40px, 5.4vw, 64px);
	min-height: 100%;
	color: var(--cp-ink);
	background:
		radial-gradient(ellipse 60rem 32rem at 18% 0%, rgba(218, 236, 255, 0.72), transparent 68%),
		radial-gradient(ellipse 48rem 28rem at 94% 16%, rgba(227, 245, 239, 0.68), transparent 70%),
		linear-gradient(145deg, #fbfdff 0%, #f2f8fc 50%, #f8f5ef 100%);
}

.hero {
	position: relative;
	min-height: min(760px, calc(100svh - 56px));
	display: grid;
	align-items: center;
	overflow: hidden;
	padding: clamp(56px, 8vw, 108px) clamp(20px, 6vw, 84px) clamp(80px, 10vw, 128px);
	isolation: isolate;
}

.hero__media {
	position: absolute;
	inset: 0;
	z-index: -2;
	display: block;
	overflow: hidden;
}

.hero__image {
	width: 100%;
	height: 100%;
	display: block;
	object-fit: cover;
	opacity: 0.68;
	transform: scale(1.015);
	animation: hero-enter 1.4s var(--cp-ease-out) both;
}

.hero::after {
	content: '';
	position: absolute;
	inset: 0;
	z-index: -1;
	background:
		linear-gradient(105deg, rgba(251, 253, 255, 0.95) 0%, rgba(247, 251, 255, 0.78) 44%, rgba(241, 249, 246, 0.38) 100%),
		linear-gradient(180deg, rgba(255, 255, 255, 0.36), rgba(249, 251, 255, 0.86));
}

.hero__content {
	position: relative;
	width: min(1040px, 100%);
}

.hero__eyebrow {
	display: flex;
	align-items: center;
	gap: 14px;
	color: #3c6186;
	font-size: 15px;
	font-weight: 750;
	letter-spacing: 0.08em;
	animation: rise-in 0.7s var(--cp-ease-out) 60ms both;
}

.hero__title {
	max-width: 920px;
	margin: clamp(24px, 4vw, 44px) 0 20px;
	color: #1a314a;
	font-size: var(--home-title-size);
	font-weight: 780;
	line-height: 1.16;
	letter-spacing: -0.055em;
	text-wrap: balance;
	animation: rise-in 0.8s var(--cp-ease-out) 160ms both;
}

.gradient-word {
	font-style: normal;
	background-clip: text;
	-webkit-background-clip: text;
	color: transparent;
	-webkit-text-fill-color: transparent;
}

.gradient-word--decision {
	background-image: linear-gradient(115deg, #24679f 8%, #3f81aa 48%, #2d8378 94%);
}

.gradient-word--evidence {
	background-image: linear-gradient(115deg, #276599 6%, #397f91 48%, #347f70 94%);
}

.hero__subtitle {
	max-width: 720px;
	margin: 0;
	color: #405b75;
	font-size: clamp(17px, 1.35vw, 20px);
	line-height: 1.8;
	animation: rise-in 0.8s var(--cp-ease-out) 270ms both;
}

.hero__slogan {
	display: flex;
	align-items: center;
	flex-wrap: wrap;
	gap: 12px;
	margin: 30px 0 38px;
	font-size: clamp(16px, 1.5vw, 21px);
	font-weight: 720;
	animation: rise-in 0.8s var(--cp-ease-out) 380ms both;
}

.slogan-word {
	background: linear-gradient(110deg, #26699f 0%, #347f92 45%, #2d8378 90%);
	background-size: 220% 100%;
	background-clip: text;
	-webkit-background-clip: text;
	color: transparent;
	-webkit-text-fill-color: transparent;
	animation: slogan-sheen 2.6s var(--cp-ease-out) 800ms 1 both;
}

.slogan-word:nth-child(3) { animation-delay: 1s; }
.slogan-word:nth-child(5) { animation-delay: 1.2s; }
.slogan-dot { color: #7b8f9e; }

.hero__actions {
	display: flex;
	align-items: center;
	flex-wrap: wrap;
	gap: 14px;
	animation: rise-in 0.8s var(--cp-ease-out) 480ms both;
}

.hero-button {
	min-height: 52px;
	display: inline-flex;
	align-items: center;
	justify-content: center;
	gap: 10px;
	padding: 0 26px;
	border-radius: 14px;
	color: #285c8b;
	font-size: 16px;
	font-weight: 720;
	text-decoration: none;
	transition: transform var(--cp-duration-fast) var(--cp-ease-out), box-shadow var(--cp-duration-base) var(--cp-ease-out), background var(--cp-duration-fast) var(--cp-ease-out);
}

.hero-button--primary {
	color: #f7fbff;
	background: linear-gradient(135deg, #3f76ad, #33847c);
	box-shadow: 0 12px 28px rgba(48, 111, 156, 0.22);
}

.hero-button--secondary {
	background: rgba(255, 255, 255, 0.78);
	border: 1px solid rgba(84, 126, 168, 0.24);
	box-shadow: 0 6px 18px rgba(49, 95, 157, 0.07);
	backdrop-filter: blur(10px);
}

.hero-button:hover { transform: translateY(-2px); }
.hero-button:active { transform: translateY(1px) scale(0.99); }
.hero-button--primary:hover { box-shadow: 0 16px 32px rgba(48, 111, 156, 0.27); }
.hero-button:focus-visible,
.text-link:focus-visible,
.capability-card:focus-visible { outline: 3px solid var(--cp-focus); outline-offset: 4px; }

.hero__scroll-hint {
	position: absolute;
	right: clamp(22px, 6vw, 80px);
	bottom: 28px;
	width: 1px;
	height: 62px;
	background: linear-gradient(to bottom, transparent, #7c9ab4, transparent);
	overflow: hidden;
}

.hero__scroll-hint span {
	position: absolute;
	inset-inline: -2px;
	height: 18px;
	background: #4a7ba6;
	animation: scroll-trace 2.8s ease-in-out infinite;
}

.home-section {
	padding: clamp(56px, 7vw, 96px) clamp(20px, 6vw, 84px);
}

.section-heading {
	max-width: 860px;
	margin-bottom: 40px;
}

.section-kicker {
	margin: 0 0 10px;
	color: #46719a;
	font-size: 13px;
	font-weight: 800;
	letter-spacing: 0.12em;
}

.section-title,
.section-description {
	margin: 0;
}

.section-title {
	color: #1d3550;
	font-size: clamp(28px, 3vw, 40px);
	font-weight: 760;
	line-height: 1.25;
	letter-spacing: -0.03em;
}

.section-description {
	max-width: 760px;
	margin-top: 14px;
	color: var(--cp-muted);
	font-size: 16px;
	line-height: 1.75;
}

.capabilities {
	background: rgba(252, 253, 255, 0.7);
	border-block: 1px solid rgba(205, 222, 239, 0.75);
}

.capability-grid {
	display: grid;
	grid-template-columns: repeat(4, minmax(0, 1fr));
	gap: 16px;
}

.capability-card {
	position: relative;
	min-height: 250px;
	display: flex;
	flex-direction: column;
	padding: 24px;
	color: var(--cp-ink);
	background: rgba(255, 255, 255, 0.78);
	border: 1px solid rgba(200, 221, 241, 0.85);
	border-radius: 18px;
	box-shadow: var(--cp-shadow-soft);
	text-decoration: none;
	overflow: hidden;
	transition: transform var(--cp-duration-base) var(--cp-ease-out), border-color var(--cp-duration-base) var(--cp-ease-out), box-shadow var(--cp-duration-base) var(--cp-ease-out);
}

.capability-card::before {
	content: '';
	position: absolute;
	inset: 0 0 auto;
	height: 3px;
	background: var(--cp-role-color);
	opacity: 0.62;
}

.capability-card:hover {
	transform: translateY(-4px);
	border-color: color-mix(in srgb, var(--cp-role-color) 35%, white);
	box-shadow: var(--cp-shadow-raised);
}

.capability-card:active { transform: translateY(0) scale(0.995); }

.capability-card__icon {
	width: 46px;
	height: 46px;
	display: grid;
	place-items: center;
	color: var(--cp-role-ink);
	background: var(--cp-role-soft);
	border: 1px solid color-mix(in srgb, var(--cp-role-color) 22%, white);
	border-radius: 13px;
	margin-bottom: 18px;
}

.capability-card h3 {
	margin: 0 0 12px;
	color: #1f3853;
	font-size: 21px;
	line-height: 1.35;
}

.capability-card p {
	margin: 0;
	flex: 1;
	color: #546b81;
	font-size: 15px;
	line-height: 1.72;
}

.capability-card__action {
	display: inline-flex;
	align-items: center;
	gap: 7px;
	margin-top: 18px;
	color: var(--cp-role-ink);
	font-size: 14px;
	font-weight: 750;
}

.workflow {
	background: linear-gradient(135deg, rgba(241, 248, 255, 0.82), rgba(242, 250, 246, 0.78));
}

.workflow-list {
	display: grid;
	grid-template-columns: repeat(5, minmax(0, 1fr));
	gap: 12px;
	padding: 0;
	margin: 0;
	list-style: none;
	counter-reset: workflow;
}

.workflow-step {
	position: relative;
	padding: 22px 18px;
	background: rgba(255, 255, 255, 0.8);
	border: 1px solid rgba(206, 223, 240, 0.78);
	border-radius: 16px;
	box-shadow: 0 7px 18px rgba(41, 74, 112, 0.045);
}

.workflow-step:not(:last-child)::after {
	content: '';
	position: absolute;
	right: -12px;
	top: 51px;
	width: 12px;
	height: 1px;
	background: #b4c8dd;
}

.workflow-step__number {
	display: block;
	margin-bottom: 14px;
	color: #6f88a2;
	font-size: 13px;
	font-weight: 850;
	letter-spacing: 0.12em;
}

.workflow-step h3 {
	margin: 0 0 8px;
	color: #223d59;
	font-size: 17px;
}

.workflow-step p {
	margin: 0;
	color: #5d7187;
	font-size: 13px;
	line-height: 1.62;
}

.workflow-note {
	max-width: 760px;
	margin: 28px auto 0;
	padding: 13px 18px;
	color: #456785;
	background: rgba(255, 255, 255, 0.68);
	border: 1px solid rgba(193, 215, 235, 0.78);
	border-radius: 12px;
	font-size: 14px;
	line-height: 1.65;
	text-align: center;
}

.entries {
	padding-bottom: clamp(72px, 9vw, 116px);
}

.entry-panel {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 32px;
	padding: clamp(26px, 4vw, 44px);
	background: linear-gradient(110deg, rgba(255, 255, 255, 0.92), rgba(236, 245, 251, 0.82));
	border: 1px solid rgba(199, 220, 239, 0.82);
	border-radius: 24px;
	box-shadow: var(--cp-shadow-soft);
}

.entry-actions {
	display: flex;
	flex-wrap: wrap;
	justify-content: flex-end;
	gap: 10px 18px;
}

.text-link {
	display: inline-flex;
	align-items: center;
	min-height: 42px;
	padding: 0 16px;
	border-radius: 12px;
	color: #2c6398;
	background: rgba(255, 255, 255, 0.58);
	border: 1px solid rgba(177, 206, 231, 0.7);
	font-size: 15px;
	font-weight: 720;
	text-decoration: none;
	transition: transform var(--cp-duration-fast) var(--cp-ease-out), background var(--cp-duration-fast) var(--cp-ease-out), border-color var(--cp-duration-fast) var(--cp-ease-out);
}

.text-link:hover {
	background: #f4f9ff;
	border-color: #9bbddd;
	transform: translateY(-1px);
}

.home-footer {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 24px;
	padding: 28px clamp(20px, 6vw, 84px) 40px;
	color: #66798c;
	background: rgba(248, 251, 255, 0.84);
	border-top: 1px solid rgba(214, 227, 241, 0.78);
	font-size: 13px;
}

@keyframes hero-enter {
	from { opacity: 0.36; transform: scale(1.055); }
	to { opacity: 0.68; transform: scale(1.015); }
}

@keyframes rise-in {
	from { opacity: 0; transform: translate3d(0, 16px, 0); }
	to { opacity: 1; transform: translate3d(0, 0, 0); }
}

@keyframes slogan-sheen {
	from { background-position: 130% 50%; }
	to { background-position: 0% 50%; }
}

@keyframes scroll-trace {
	0% { transform: translateY(-18px); opacity: 0; }
	30% { opacity: 1; }
	100% { transform: translateY(62px); opacity: 0; }
}

@media (max-width: 1100px) {
	.capability-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
	.workflow-list { grid-template-columns: repeat(2, minmax(0, 1fr)); }
	.workflow-step:not(:last-child)::after { display: none; }
}

@media (max-width: 720px) {
	.hero { min-height: auto; padding-top: 54px; }
	.hero__title { letter-spacing: -0.035em; }
	.hero__actions { align-items: stretch; }
	.hero-button { width: 100%; }
	.hero__scroll-hint { display: none; }
	.home-section { padding-block: 56px; }
	.capability-grid,
	.workflow-list { grid-template-columns: 1fr; }
	.capability-card { min-height: 218px; }
	.entry-panel { align-items: flex-start; flex-direction: column; }
	.entry-actions { justify-content: flex-start; }
	.home-footer { align-items: flex-start; flex-direction: column; }
}

@media (prefers-reduced-motion: reduce) {
	.hero__image,
	.hero__eyebrow,
	.hero__title,
	.hero__subtitle,
	.hero__slogan,
	.hero__actions,
	.slogan-word,
	.hero-button,
	.capability-card,
	.text-link,
	.hero__scroll-hint span {
		animation: none !important;
		transition: none !important;
		opacity: 1;
		transform: none;
	}

	.hero__image { opacity: 0.68; }
	.gradient-word,
	.slogan-word { color: #22618d; -webkit-text-fill-color: initial; }
	.hero__scroll-hint { display: none; }
}
</style>