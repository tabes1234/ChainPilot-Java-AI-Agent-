# ChainPilot 功能状态说明（比赛冻结版）

三种状态严格区分：**比赛演示可用**（本轮在当前环境实际启动、操作、看到结果）、
**已实现但本轮不演示**（代码与测试存在，但缺少演示价值或需要额外环境）、
**尚未完成或未验证**（不得对外宣称已完成）。

判定依据只写本轮实测到的事实；引用历史轮次结果时明确标注「历史隔离环境实测，本轮未重跑」。

## A. 比赛演示可用（本轮实机走通）

| 能力 | 本轮证据 | 演示入口 |
| --- | --- | --- |
| 分析任务创建 → 异步执行 → 状态轮询 → 终态 | `POST /api/supply-chain/analysis/tasks` 200/`ACCEPTED` → `COMPLETED/DONE`（任务 `1cae5020-…`），页面按钮「运行分析任务」实际点击成功 | `/supply-chain` |
| 结构化风险结果（分数、等级、证据、缺口） | 报告含 `ORD-1001`(100/100)、`ORD-1002`(97/100)、`ORD-1004`，含 SKU/数量/供应商/交付日期/库存缺口/订单状态 | 分析页「风险订单」 |
| 叙述来源如实标注 | `narrativeSource=DETERMINISTIC_FALLBACK`、`modelAssisted=false`，页面显示「确定性叙述」并说明模型未参与时如何降级 | 分析页「分析结论」 |
| 执行事件与审计 | 单任务 27 条事件：`TASK_QUEUED`、6 组 `NODE_*`、3 组 `TOOL_*`、2 次 `MODEL_STARTED`→`MODEL_FAILED`→`FALLBACK_USED`、`TASK_COMPLETED` | 分析页「执行事件」/`/agent-observability` |
| 自然语言决策 + 确定性意图解析 + 工具对比 | `INTENT_RECOGNIZED intent=COMPARE_REPLENISHMENT` → `compareDisposalScenarios` 生成 3 候选对比（任务 `98d3bf8c-…`），`realDataMutated=false` | 「进入自然语言决策」 |
| 不认识的请求不伪造结果 | 另一问法返回 `CLARIFICATION_REQUIRED`，任务仍 `COMPLETED` 且无业务写入 | 决策输入框 |
| 处置方案与沙箱边界展示（只读） | 种子方案页面渲染正常，模拟/比较来自只读基线，无真实 ERP 写入 | `/supply-chain/disposal?planId=…` |
| 离线评测（确定性检查，不调模型） | 页面路径实走通：「评测案例 → 全选离线案例（同版本 14）→ 运行所选案例 → 运行离线评测」→ 运行 `e0d2b9b5-…`，`COMPLETED`、14/14 通过；API 路径另有 3 案例运行 `c1a05c61-…`；`metrics.modelCalls=0`；案例目录 21 条（OFFLINE 15 + AGENT 6） | `/agent-evaluation` |
| 人工反馈与案例追溯 | 只读体检：反馈 8 条、已启用反馈案例 1 个，草案页渲染正常 | 评测中心「人工反馈」 |
| 运行观测汇总 | `GET /api/supply-chain/observability/summary` 200，含 `completedTasks`、`degradedTasks`、`fallbackEvents`、`limitations` | `/agent-observability` |
| 模型就绪门禁与安全行为 | `chatState=NOT_CONFIGURED`；AGENT 运行返回 **412 `AGENT_CHAT_NOT_READY`**、缺确认返回 400、OFFLINE 混入 AGENT 返回 400；`agentRunActive=false`（无锁残留） | `/system/model-config` |
| 重启后数据持久 | 杀掉后端进程并重启后，任务状态、27 条事件、评测运行完整可读 | H2 文件库 |
| 演示数据幂等准备 | `scripts/chainpilot-demo-seed.ps1` 输出 `createdCount=0` 且给出全部演示 ID；只读体检 `failures=0` | `scripts/` |

## B. 已实现但本轮不演示

| 能力 | 为什么不演示 | 现状 |
| --- | --- | --- |
| 方案确认 / 提案生成 / 沙箱执行（写操作） | 会改变演示数据状态，现场不可回退 | 代码与 H2 测试覆盖；本轮只做只读展示 |
| 单任务取消、跨实例取消 | 演示中制造「取消态」会干扰主线叙事 | 历史轮次已实现并测试 |
| 数据问答 / Graph 智能体、知识库、语义模型、提示词与数据源配置 | 与「供应链风险决策」主线无关，且部分依赖 Embedding 服务 | 页面可用，Embedding 未配置时知识检索走本地词项匹配 |
| 评测运行对比、失败案例追踪、单案例重跑 | 需要多份运行记录才有意义 | 已实现，H2 测试覆盖 |
| MySQL 持久化与完整 HTTP 链路集成测试 | 需要隔离 MySQL 实例，现场环境没有 | 历史隔离环境实测（3 个 MySQL IT 共 33/33）；本轮未运行 |
| 双 JVM 所有权 / 租约抢占恢复 | 需要两个后端实例与专用库，演示价值低于风险 | 历史隔离环境实测（34/34）；本轮未运行 |
| 真实模型验收 harness（四场景 + 评测中心 AGENT） | 需要凭据与费用授权 | 门禁与回环彩排已实测；真实 Provider 运行从未执行 |

## C. 尚未完成或未验证（禁止对外宣称已完成）

| 项 | 实际状态 |
| --- | --- |
| 真实 Provider 模型 E2E（分析、决策、Tool Calling） | **未完成**。当前环境没有任何 `CHAINPILOT_REAL_MODEL_*` 配置；2026-10-20 的一次历史通过不能作为当前代码结论 |
| 评测中心 AGENT 真实模型验收 | **未执行**。harness 已建成并通过四道零付费门禁 + 一道本机端点门禁，但真实运行从未发生 |
| 真实 embedding / 向量检索质量 | 未验证；演示环境无向量服务 |
| 模型输出质量、成本、大样本统计 | 未评估；评测案例规模是演示级（21 条） |
| 生产级性能、并发容量、HA/主从、TLS、最小权限、备份恢复演练 | 未验证 |
| 生产数据库迁移脚本在真实环境执行 | 未执行；发布前需按 `docs/DATABASE_RELEASE.md` 走显式脚本 + PRE/POST 记录 |
| Langfuse 等外部观测导出 | 未验证；只有本地 Micrometer/OTel 观测证据 |

## D. 测试与检查覆盖边界（本轮）

- 后端全量 H2 回归 **2047/2047**（0 失败 0 错误 0 跳过），Checkstyle **0 violations**——本轮未改后端代码，
  该结果来自同环境同代码的独占重跑。
- 前端：Vitest **118/118**（19 文件，含本轮新增的 5 条案例版本分组测试）、`tsc --noEmit` 无错误、
  ESLint 无告警、`next build` 成功。
- 真实执行验证：HTTP API 与浏览器页面双路径（见 A 表），生产构建 + 系统 Chrome，
  8 个关键页面 + 5 个种子页面 0 失败请求 / 0 console error；工作台与评测中心的写操作按钮均实际点击成功。
- 本轮修复：评测中心「全选离线案例」会跨案例版本选择（14 个数据集案例 + 1 个反馈案例），
  因而每次运行都被后端以 `400 一次运行只能包含同一案例版本的案例` 拒绝；改为只选同版本最大一组并在按钮
  文案中显示数量，配套 `src/lib/evaluation-case-groups.ts` 与单元测试。
- 未覆盖：真实模型、MySQL、双 JVM、性能与容量、外部观测导出。

## E. 演示模式入口收敛（比赛现场可选）

构建前端时设置 `NEXT_PUBLIC_CHAINPILOT_DEMO_MODE=true`（构建期变量，改后需重新构建）即进入比赛演示模式：

- 关闭入口统一收敛到提示页 `/feature-preparing`（文案见 `src/lib/demo-mode.ts`）；
- 关闭路由：`/chat`、`/knowledge/**`、`/system/agents`、`/system/data-sources`、`/prompt-config`、`/agent/**`、`/dashboard`，由 middleware 服务端重定向，刷新/复制链接仍生效；
- 页面内写操作（提案确认/取消、方案保存/确认/状态变更、沙箱执行、情景模拟、任务取消、AGENT 真实评测运行、模型配置增删改/测试、反馈提交、评测案例草案操作）在触发请求前跳转提示页；
- 开放功能不受影响：供应链分析/决策只读/处置只读/历史/观测/离线评测/模型配置展示。

恢复完整功能：不带该变量重新构建即可；需要永久恢复某功能时，从 `src/lib/demo-mode.ts` 的
`DEMO_CLOSED_ROUTES` 移除对应条目并去掉相应按钮上的 `useDemoGuard` 调用（清单集中，便于逐项恢复）。
