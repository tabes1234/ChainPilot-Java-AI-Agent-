# 简历项目简介：ChainPilot

> 用途：Java 后端、AI Agent 应用研发、AgentOps/评测方向实习简历。
> 所有描述与当前代码一致；未完成真实模型 E2E，不把确定性降级或 Mock 说成真实模型生成。

## 一句话描述

在 Spring AI Alibaba DataAgent 基础上二次开发供应链智能决策工作台，使用 Java 确定性规则保证风险结论可验证，
以受约束的模型叙述、Agent 评测、运行观测和人工反馈闭环支撑可追溯的供应链决策。

## 技术栈

Java 17、Spring Boot 3.4、Spring AI 1.1、Spring AI Alibaba 1.1、StateGraph、Spring AI `@Tool`、
JdbcTemplate/MyBatis、H2/MySQL、Next.js 16、React 19、TanStack Query、JUnit 5、Mockito、Vitest。

## 核心亮点

1. **确定性风险内核与模型解释分离**
   - Java 规则计算风险分数、等级、评分贡献和结构化证据；模型只解释已有证据。
   - 结构化叙述校验订单、evidenceId、数值、推荐和 dataGap；失败回退 `DETERMINISTIC_FALLBACK`。
   - 证据：`supplychain/service/SupplyChainAnalysisService.java`、
     `supplychain/service/RiskNarrativeValidator.java`、
     `SupplyChainAnalysisServiceTest`、`RiskNarrativeValidatorTest`。

2. **决策与写操作的显式安全边界**
   - 自然语言决策先生成 `PENDING_CONFIRMATION` 提案，人工确认后才进入受控执行。
   - 情景推演、基线、多候选比较和沙箱记录与真实库存/订单隔离；`realDataMutated` 由后端控制。
   - 证据：`SupplyChainDecisionService`、`Disposal*Service`、`ActionProposalService` 及对应 HTTP/单元测试。

3. **Agent Evaluation Center 与人工反馈闭环**
   - 版本化 OFFLINE/AGENT 案例、运行持久化、指标、对比、单案例重跑。
   - 反馈可以生成草案，但草案初始不包含预期和断言；人工补充、审核并启用后才进入回归。
   - 追踪链 `feedbackId → evaluationCaseId → evaluationRunId → result`。
   - 证据：`evaluation/`、`evaluation/feedback/`、`docs/DEMO_GUIDE.md`、
     `FeedbackServiceTest`、`AgentEvaluationServiceTest`。

4. **Agent 运行观测、模型门禁与凭据安全**
   - 任务/工具/模型/回退事件关联 taskId；观测中心从真实持久化事件生成诊断。
   - 模型就绪区分未配置、未测试、测试失败、已验证；案例显式声明 CHAT/EMBEDDING 依赖，
     后端在启动前校验并返回结构化 412 错误码。
   - 连接异常按类型分类，API、日志和就绪状态统一脱敏；使用虚构 canary 覆盖响应和日志泄露测试。
   - 证据：`SupplyChainObservabilityService`、`ModelReadinessService`、
     `ModelConnectionErrorClassifier`、`ModelConfigOpsServiceTest`、`ModelConnectionErrorClassifierTest`。

5. **Next.js 工作台与前端契约测试**
   - 将工作台迁移到独立 Next.js 16 应用，覆盖首页、供应链分析、决策、处置、历史、评测、观测和系统配置。
   - 前端 API 层与 Vitest 契约测试覆盖模型门禁、反馈草案、评测运行和 UI 安全边界。
   - 证据：`data-agent-frontend-next/src/app`、`src/lib/api`、`src/lib/api/__tests__`。

## 工程质量证据

最终验收轮本机回归（2026-10-21）：

- 后端 H2：**1963/1963**。
- 隔离 MySQL 8 IT：**23/23**（上一阶段结果；本轮无 schema 变更，未重跑）。
- 前端 Vitest：**96/96**；TypeScript、ESLint、Next build 通过。
- 真实浏览器：完整写链路 **18/18**、全站路由 **17/17**、可见交互 **35/35**，0 控制台错误、0 个 HTTP 4xx/5xx。
- 假模型集成测试覆盖真实 HTTP 工具调用、模型事件和 provider usage；不代表真实供应商 E2E。
- 这些数字只用于说明回归范围，不等同于代码质量、业务效果或生产性能。

## 开源基础与二次开发

项目基于 [Spring AI Alibaba DataAgent](https://github.com/spring-ai-alibaba/DataAgent) 的应用骨架，
保留其 Apache-2.0 许可证、版权头和 Agent/模型/知识库基础能力。ChainPilot 主要新增/实质改造：

- 供应链领域、确定性规则、报告、决策、沙箱和情景比较。
- Agent Evaluation Center、反馈转评测案例。
- Agent 观测、模型就绪门禁、连接错误分类和脱敏。
- Next.js 工作台及对应前端测试。

未声称向 DataAgent 上游提交 PR；详细边界见
[docs/UPSTREAM_AND_CONTRIBUTION.md](UPSTREAM_AND_CONTRIBUTION.md)。

## 当前限制

- 模型配置为空，真实模型 Agent E2E 尚未完成；没有真实 provider 成功率、费用或延迟数据。
- 供应链订单/库存/供应商是模拟数据，沙箱不代表真实 ERP/WMS 写入。
- 未验证生产级并发、HA、TLS、企业用户规模或 MySQL 多版本。
- 连接测试验证状态是当前后端进程作用域，重启后需重测。

## 适合的岗位表述

**Java 后端 / AI Agent 应用研发实习生**：强调 Java 规则内核、Spring AI 工具调用、状态/事件持久化、
评测与模型安全边界。

**AgentOps / 评测方向实习生**：强调运行观测、事件驱动诊断、评测案例版本化、反馈审核门禁、
模型就绪与调用保护。

## 关键文档

- [架构说明](ARCHITECTURE_CHAINPILOT.md)
- [面试准备](INTERVIEW_GUIDE.md)
- [演示指南](DEMO_GUIDE.md)
- [上游与贡献边界](UPSTREAM_AND_CONTRIBUTION.md)
