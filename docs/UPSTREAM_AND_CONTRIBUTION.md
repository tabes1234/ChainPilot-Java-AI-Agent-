# 上游项目与 ChainPilot 贡献边界

## 1. 上游来源

- 上游仓库：<https://github.com/spring-ai-alibaba/DataAgent>
- 本地 `origin`：`https://github.com/spring-ai-alibaba/DataAgent.git`
- 上游许可证：Apache License 2.0，仓库根目录保留完整 [LICENSE](../LICENSE)。
- 未发现独立的 `NOTICE` 文件；本次不伪造或补写不存在的上游版权声明。
- 本地 Git 历史从导入提交开始：
  `c182790 chore: import DataAgent source snapshot`，
  ChainPilot 交付提交为 `a18481c feat: deliver ChainPilot supply chain decision workbench`。
  因此无法通过本地 Git 逐行还原上游每条历史提交的作者或 PR 关系。

> 本文按实际文件、包结构、调用关系和当前工作区差异做归因；不把“出现在仓库中”
> 自动等同于“由 ChainPilot 从零原创”。

## 2. 上游继承或基于其扩展的部分

以下能力来自上游 DataAgent 的应用骨架或在其基础上做了 ChainPilot 适配：

- Spring Boot 应用入口、Web 配置、异常处理骨架、Maven 模块与上游 CI 配置。
- Agent、Chat、知识库、数据源、语义模型、提示词、模型配置等管理 API 和页面。
- `ModelConfigDataService`、`ModelConfigOpsService`、`AiModelRegistry`、
  `DynamicModelFactory`、`EmbeddingModelCompatibilityValidator` 的基础实现；
  ChainPilot 在此基础上增加了就绪状态、错误分类和脱敏。
- Spring AI / Spring AI Alibaba 的 Workflow、StateGraph、工具调用、模型观测基础。
- 原 Nuxt 前端（`data-agent-frontend-nuxt`）及其构建配置。

证据：

- 受 Git 跟踪的上游文件包括 `.github/workflows/*`、`CI/*`、`data-agent-frontend-nuxt/*`、
  `data-agent-management` 下的通用 Agent/知识库/数据源/模型配置包。
- 上游 `README.md` 在初始提交中指向 Spring AI Alibaba DataAgent；当前 README 已由
  ChainPilot 重写，但 LICENSE 和上游源码版权头仍保留。
- 上游 CI 中的 `build-and-test`、`license-check` 等 job 仍以
  `github.repository == 'spring-ai-alibaba/DataAgent'` 为条件，说明这些流程是为上游仓库设计的。

## 3. ChainPilot 新增或实质改造的部分

以下内容在当前工作区中以新增目录、未跟踪文件或明显扩展代码的形式存在，已按实际实现核实：

| 方向 | 主要位置 | 说明 |
| --- | --- | --- |
| 供应链领域 | `data-agent-management/.../supplychain/` | 订单、库存、供应商、风险规则、决策、处置、沙箱、模拟、比较、事件 |
| 确定性规则与证据 | `supplychain/service`、`supplychain/model` | Java 风险分数/等级/证据/dataGap，不依赖模型 |
| 决策与写操作边界 | `supplychain/service/SupplyChainDecisionService`、`Disposal*Service` | 提案、确认、沙箱、模拟隔离 |
| 评测中心 | `data-agent-management/.../evaluation/` | 固定案例、运行持久化、指标、对比、单案例重跑 |
| 反馈闭环 | `evaluation/feedback/` | 反馈 → 草案 → 人工审核 → 启用 → 回归 |
| 运行观测 | `supplychain/service/SupplyChainObservabilityService`、`SupplyChainObservabilityController` | 任务事件、诊断、运行详情 |
| 模型门禁与安全 | `service/aimodelconfig` 的 ChainPilot 新增类 | 四态就绪、案例依赖、分类错误、脱敏 |
| Next.js 前端 | `data-agent-frontend-next/` | 全新 Next.js 工作台，Next 16 + React 19 |
| 文档与验收 | `docs/CHAINPILOT_MODULES.md`、`DEMO_GUIDE.md`、本轮新增架构/简历材料 | ChainPilot 项目文档 |

同时存在对上游跟踪文件的修改，例如：

- `README.md`、`.gitignore`、`pom.xml`、Docker 配置。
- `GlobalExceptionHandler`、`ModelConfigController`、`ModelConfigOpsService`、
  `AiModelRegistry`、`DynamicModelFactory`、`ApiResponse`、`ModelCheckVo` 等。
- Nuxt 前端在早前阶段已有 ChainPilot 改造，本阶段未再修改 Nuxt。

贡献边界按“新增/扩展”表述，不把这些修改宣称为上游官方版本的一部分。

## 4. 可核实与不可核实的事项

可核实：

- 上游仓库和 Apache 2.0 LICENSE 存在。
- 本地仓库没有上游提交历史，只有单一导入快照。
- `data-agent-frontend-next` 当前未被 Git 跟踪；Nuxt 前端被跟踪。
- 供应链、评测、反馈、观测和 Next.js 工作台是当前工作区新增/扩展的主要部分。
- 当前没有真实模型 E2E 证据，也没有真实企业用户或 QPS 数据。

不可核实/不应宣称：

- 不能声称向上游提交过 PR 或已被上游合并。
- 不能声称整个仓库完全从零原创。
- 不能根据文件名断言具体某一行属于谁；需要更完整的上游历史或提交记录。
- 不能把上游已有的 Agent、Chat、知识库、模型配置基础说成本项目原创。

## 5. 发布时的许可证义务

发布到 GitHub 或其他平台时：

1. 保留根目录 `LICENSE` 全文。
2. 保留上游源码中的版权与 Apache 2.0 许可头。
3. 在 README 明确列出上游仓库、继承基础和本项目的二次开发内容。
4. 不删除、替换或伪造上游作者/版权信息。
5. 如未来加入上游原始 `NOTICE` 或第三方声明，应原样保留；本次未发现该文件。
6. 不把上游 Workflow/Agent/模型配置基础宣称为本项目独立实现。

## 6. 相关文件

- [LICENSE](../LICENSE)
- [README.md](../README.md)
- [架构说明](ARCHITECTURE_CHAINPILOT.md)
- [发布前检查清单](PUBLISH_CHECKLIST.md)
- 上游 CI：`.github/workflows/build-and-test.yml`、`.github/workflows/license-check.yml`
- 上游项目：<https://github.com/spring-ai-alibaba/DataAgent>
