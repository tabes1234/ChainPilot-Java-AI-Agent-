# ChainPilot 面试准备指南

以下回答基于当前仓库实际实现。真实模型未配置；不要把离线评测、假模型测试或连接测试说成真实
Agent E2E。

## Q1：为什么正式风险评分采用 Java 确定性规则，而不是让模型打分？

因为供应链风险需要可解释、可复现、可回归。模型适合把已有证据转成自然语言，但不适合成为正式分数来源。
ChainPilot 把职责拆开：

- Java 规则计算风险分、等级、评分贡献、证据和 dataGap。
- 模型只解释这些结果，不能修改正式字段。
- 报告中的 `narrativeSource` 区分 `MODEL_VERIFIED`、`MODEL_LEGACY_UNVERIFIED` 和
  `DETERMINISTIC_FALLBACK`。

这样在模型超时或输出异常时，业务结果仍然稳定，评测也能用确定性断言验证。
来源：`supplychain/service/SupplyChainAnalysisService.java`、
`supplychain/service/RiskNarrativeValidator.java`、`supplychain/model/RiskAnalysisReport.java`。

## Q2：如何验证模型叙述中的证据 ID 和数值？

`RiskNarrativeValidator` 会：

- 解析严格的 JSON 结构；
- 校验每个 `orderId` 必须来自当前 assessments；
- 校验 `evidenceIds` 必须属于对应订单的真实证据；
- 要求覆盖所有风险订单；
- 校验推荐必须是允许推荐之一；
- 要求所有 dataGap 出现在 uncertainties；
- 拒绝执行声明；
- 只允许来自任务 ID、证据 JSON 和允许推荐的数字。

任一失败都抛 `InvalidNarrativeException`，由分析服务回退确定性摘要，不标记为已验证。
来源：`supplychain/service/RiskNarrativeValidator.java`、
`RiskNarrativeValidatorTest`。

## Q3：模型调用失败时如何回退，如何证明事件和任务关联？

模型边界记录 `MODEL_STARTED`、`MODEL_COMPLETED` 或 `MODEL_FAILED`，失败后记录
`FALLBACK_USED`；事件包含同一 taskId。报告最终带：
`modelAssisted=false`、`narrativeSource=DETERMINISTIC_FALLBACK`，但风险分数和证据保持不变。

失败类型可以是空输出、超时、不可用、非法 JSON/证据/数值等；不能把真实失败伪装成成功。
来源：`SupplyChainEventRecorder`、`SupplyChainAnalysisService`、
`SupplyChainAnalysisServiceTest`、`SupplyChainToolCallingHttpIntegrationTest`。

## Q4：如何区分“模型配置成功”“连接测试成功”和“Agent E2E 成功”？

这是三个不同层次：

1. **配置存在**：有激活的 CHAT 配置，不代表端点可用。
2. **连接测试成功**：用户手动触发一次真实调用成功，只代表模型端点可调用。
3. **Agent E2E 成功**：任务完成、报告/决策可读、结构化输出通过校验、断言通过，并且运行记录与事件一致。

另外还需要区分 `MODEL_VERIFIED` 和业务任务成功：模型叙述通过校验，不等于最终业务判断正确。
来源：`ModelReadinessService`、`AgentEvaluationService`、`AgentEvaluationController`。

## Q5：为什么情景模拟和沙箱不能写入真实库存？

模拟用于比较方案，不应当改变真实业务状态。ChainPilot 的模拟记录、比较候选和沙箱执行记录写入
自己的持久化表；决策结果显式包含 `realDataMutated=false`。真正确认提案后才进入受控执行路径，
而沙箱仍不代表连接真实 ERP/WMS。

如果让模型直接调用写库存工具，既无法保证权限和确认，也无法保证可回滚审计。
来源：`SupplyChainDisposalController`、`DisposalSimulationService`、
`DisposalScenarioComparisonService`、`SupplyChainDecisionService`。

## Q6：如何设计评测案例的人工审核门禁？

用户反馈不是标准答案。流程为：

1. 反馈持久化：taskId、类型、评论、叙述来源、结果指纹、证据和快照。
2. 生成草案：`expectedBehavior` 为空，`assertions` 为空，建议单独保存。
3. 人工填写预期和可执行断言。
4. 审核通过后才允许启用，生成 `FB-XXXXXXXX` 案例。
5. 启用案例通过现有 `AgentEvaluationService` 执行并持久化。

没有自动审核、自动通过条件或把用户评价直接转成断言。
来源：`evaluation/feedback/FeedbackService.java`、`FeedbackRepository.java`、
`FeedbackServiceTest`、`docs/DEMO_GUIDE.md`。

## Q7：Agent 观测指标如何从真实事件计算？

观测汇总读取持久化任务与事件，不伪造事件或 token。模型 usage 只有 provider 返回时才写入；
没有可信 usage 时显式写 `token usage unavailable`。诊断根据事件类型和状态计算，例如模型失败、
工具失败、回退、结构化校验拒绝。

评测指标只统计完整终态运行，P95 在样本不足时返回空值，不把少量样本包装成稳定结论。
来源：`SupplyChainObservabilityService`、`SupplyChainEventRecorder`、
`EvaluationMetricsCalculator`、`docs/ARCHITECTURE_CHAINPILOT.md`。

## Q8：MySQL 旧表迁移如何保证兼容和幂等？

运行时 DDL 使用 `CREATE TABLE IF NOT EXISTS`；新增列使用可重复执行的 `addColumnIfMissing`；
索引和外键创建做了安全处理。测试覆盖真实 MySQL 的持久化、旧库升级、重复启动、幂等和专用测试库校验。
没有在开发库上做破坏性迁移。

来源：`EvaluationRunRepository`、`FeedbackRepository`、
`EvaluationMySqlLegacyUpgradeIT`、`EvaluationMySqlPersistenceIT`、
`FeedbackMySqlPersistenceIT`。

## Q9：如何保护模型凭据并脱敏连接异常？

- 模型列表只返回掩码。
- 连接测试从服务端读取配置，不接受前端回传明文 Key。
- 连接错误按异常类型/HTTP 状态分类，响应只返回固定安全消息和 code。
- 日志和就绪状态都经过 API Key、代理密码、Authorization、URL 用户信息、token/api_key 参数脱敏。
- 使用虚构 canary 测试响应、就绪状态和 Logback 日志，确保敏感内容不泄漏。

来源：`ModelConfigController`、`ModelConfigOpsService`、`ModelReadinessService`、
`ModelConnectionErrorClassifier`、`ModelConnectionErrorClassifierTest`。

## Q10：这个项目从 DataAgent 继承了什么，真正扩展了什么？

上游提供 Spring Boot 应用骨架、Agent/Chat/知识库/数据源/模型配置和 StateGraph 基础。
ChainPilot 扩展了供应链领域、确定性风险规则、决策/沙箱、情景比较、评测中心、反馈闭环、
Agent 观测、模型门禁和 Next.js 工作台。没有向上游提交 PR 的可验证记录，不能声称整个项目从零原创。

来源：`docs/UPSTREAM_AND_CONTRIBUTION.md`、Git 历史、`supplychain/`、`evaluation/`、
`data-agent-frontend-next/`。

## Q11：如果现在要完成真实模型 E2E，还需要什么？

需要用户：

1. 在模型配置页配置并激活 CHAT，手动测试连接。
2. 选择最小案例（如 `WF-ANALYSIS-HIGH-RISK`）。
3. 明确授权本次真实调用和费用。
4. 检查 runId、taskId、报告 `narrativeSource`、事件、usage 和观测中心。

当前模型列表为空，因此仍阻塞；不能使用本仓库的 Mock 测试代替这一步。
来源：`README.md`、`DEMO_GUIDE.md`、`AgentEvaluationService`。

## Q12：你认为当前最大的工程风险是什么？

不是“模型能不能回答”，而是边界和可追溯性：

- 模型失败是否会污染正式业务数据；
- 评测是否只统计真实运行，是否伪造成功率；
- 连接测试/AGENT 门禁是否可绕过；
- 反馈是否会被误当成 ground truth；
- 日志是否泄露凭据；
- 旧数据库迁移是否破坏用户数据。

ChainPilot 的设计选择是“先保证可验证和可回退，再追求模型能力”；真实模型质量仍待授权验证。
