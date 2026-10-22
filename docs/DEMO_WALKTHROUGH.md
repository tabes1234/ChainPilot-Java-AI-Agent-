# ChainPilot 比赛演示操作指南（功能冻结版）

本指南是比赛演示的唯一操作文档。它只包含**在当前环境中实际执行成功过**的命令、页面与结果，
所有数字与界面文案都来自 2026-10-22 的实机验收（见文末「本轮验收证据」）。

演示定位：**不依赖真实模型**。ChainPilot 的正式风险结论由 Java 确定性规则产生，模型只在被配置并
手动连接测试通过后才参与叙述；演示环境不配置模型，因此页面上会明确显示「确定性叙述」而不是模型生成。
讲解时必须区分：真实模型生成 / Java 确定性规则 / 预置演示数据。禁止把规则结果说成 LLM 推理。

## 1. 环境要求

| 组件 | 版本 / 端口 | 说明 |
| --- | --- | --- |
| JDK | 17（实测 `Microsoft jdk-17.0.12.7-hotspot`） | 后端编译与运行 |
| Maven | 仓库自带 `./mvnw`（3.9.12） | 无需系统 Maven |
| Node | 22+（实测 v24.20.0 可用） | 前端构建与运行 |
| 后端端口 | `8065` | Spring Boot，profile `h2` |
| 前端端口 | 开发 `3000`，生产验收 `3002` | Next.js（`data-agent-frontend-next`） |
| 数据库 | `data-agent-management/data/nl2sql_database.mv.db`（H2 文件库） | 演示数据与任务记录持久化在此，重启不丢 |

后端默认 profile 指向共享 MySQL，**演示必须显式使用 `h2` profile**，否则会连到 `saa_data_agent`。

## 2. 启动

终端 A（后端，仓库根目录）：

```bash
./mvnw -o -pl data-agent-management spring-boot:run -Dspring-boot.run.profiles=h2
```

PowerShell 用 `.\mvnw.cmd`。启动成功的标志：日志出现 `Started DataAgentApplication`，且

```bash
curl -s http://localhost:8065/api/supply-chain/orders | head -c 120
```

返回 JSON。

终端 B（前端，生产模式，推荐演示用）：

```bash
cd data-agent-frontend-next
CHAINPILOT_BACKEND_URL=http://localhost:8065 npm run build
npm run start -- -p 3002
```

注意两点实测结论：代理目标在 **`npm run build` 时写入路由清单**，换后端地址必须重新构建；
端口必须写成 `npm run start -- -p 3002`（漏掉 `--` 时 Next 会把 `3002` 当成项目目录并报
`Invalid project directory`，已实测）。等价的直接写法是 `npx next start -p 3002`。

临时改端口跑开发模式（不需要构建）：

```bash
CHAINPILOT_BACKEND_URL=http://localhost:8065 npm run dev   # http://localhost:3000
```

## 3. 演示数据准备与恢复

数据准备（幂等，只创建缺失的 `[DEMO-SEED]` 标记记录，不调用模型、不清库）：

```bash
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/chainpilot-demo-seed.ps1 -BackendUrl http://localhost:8065
```

脚本输出 `analysisTaskId / decisionTaskId / planId / executionId / feedbackId / evaluationRunId`
和 `createdCount`。**演示前把这几个 ID 抄下来**，页面链接要用它们；已就绪时 `createdCount=0`。
脚本在「库里已有任务但没有演示标记」时会拒绝写入，除非显式加 `-AllowExistingData`。

必须知道的一条自动行为：每次提交分析/决策任务都会触发保留期清理，删除该库中**超过 24 小时的终态任务及其
事件**（`SupplyChainWorkflowService.pruneTasks`）。所以种子记录不会永久存在——**比赛当天开场前先跑一次
数据准备脚本**，并优先使用脚本当场打印的 ID；实测该清理确实会缩小 H2 文件（本轮验收后从 3.6 MB 降到
2.5 MB），但 `[DEMO-SEED]` 记录在准备脚本与页面检查中均确认仍可读取。

只读体检（不写库、不调模型，把模型未就绪当作外部条件而不是失败）：

```bash
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/chainpilot-smoke-test.ps1 \
  -BackendUrl http://localhost:8065 -FrontendUrl http://localhost:3002
```

期望：`failures=0`，`blocked external conditions=1`（真实 AGENT E2E 因未配置模型被标记为 BLOCKED）。

恢复初始状态：演示只新增任务/评测记录，不删除业务数据。若演示中创建了多余任务，直接回到
`/supply-chain/history` 忽略它即可；需要回到干净环境时，停止后端、删除
`data-agent-management/data/nl2sql_database.mv.db` 再重启（会重建空库），然后重跑一次上面的数据准备脚本。
**比赛现场不要做删库动作**——用 `?taskId=` 精确链接比清理环境更可靠。

## 4. 演示主流程（约 5 分钟）

### 步骤 1 工作台与演示模式（0:00–0:40）

打开 `http://localhost:3002/supply-chain?demo=supplier-delay`。

页面应看到：`示例场景：演示模式`、`Java 确定性规则`、`真实模型未配置；不会自动调用收费模型`，
场景卡「关键供应商交付延迟 + MCU 库存缺口」，基线计数 `订单总数 4 / 非低风险订单 3 / 存在库存缺口 3 /
非低风险供应商 2`，以及三步流程标签「1 风险分析 → 2 方案决策 → 3 模拟与沙箱」。

讲法：这是一个供应链风险决策工作台，数据是本地模拟的订单/库存/供应商，不写真实 ERP。

### 步骤 2 现场创建一个真实任务（0:40–1:40）

在「发起分析」输入框保留或输入：

```
分析今天有哪些订单可能延期，结合库存和供应商风险，给出处理建议。
```

点击 **运行分析任务**。页面每 900ms 轮询真实状态，几秒内出现：

- `任务状态`：`COMPLETED` / `DONE` + 任务 ID；
- `执行事件（27）`：可展开，含 `TASK_QUEUED`、6 组成对的 `NODE_STARTED/NODE_COMPLETED`、
  3 组成对的 `TOOL_STARTED/TOOL_COMPLETED`、2 次 `MODEL_STARTED` → `MODEL_FAILED` → `FALLBACK_USED`、
  最后一条 `TASK_COMPLETED`；
- `分析结论`：徽标 `确定性叙述`，摘要「发现 3 个存在风险的订单，其中高风险 3 个。」，
  风险订单 `ORD-1001`（100/100）、`ORD-1002`（97/100）、`ORD-1004`，含 SKU、数量、供应商、
  要求交付/预计交付、库存缺口、订单状态；
- 明确提示：「叙述来源：Java 确定性规则摘要。模型未参与，或模型输出未通过证据/数值/建议校验时自动降级，
  风险分数与证据不受影响。」

讲法（这是全项目最该讲清楚的一点）：**模型失败没有让业务失败**。规划与叙述两次调用都如实记录
`MODEL_FAILED` 并降级为确定性摘要，风险分数、证据、缺口数量完全来自 Java 规则，可回归、可复现。

### 步骤 3 决策与方案对比（1:40–2:40）

点右上角「进入自然语言决策」，或在输入框使用能命中确定性意图解析的问法（必须带 SKU、数量与
「比较/推荐」字样，例如）：

```
帮我分析 SKU-SENSOR-002 的缺货风险，比较补货 50、100、200 件的方案，告诉我推荐哪个，并说明理由。
```

预期：`INTENT_RECOGNIZED intent=COMPARE_REPLENISHMENT` → 工具 `compareDisposalScenarios` 生成对比
（3 个候选，含排名、补货数量、缺口变化、模拟 ATP、权重说明）→ 叙述阶段 `MODEL_FAILED` + `FALLBACK_USED`
→ 任务 `COMPLETED`，`realDataMutated=false`。

也可以直接打开种子数据链接：`/supply-chain/decision?taskId=<decisionTaskId>`。

讲法：推荐由 Java 排名引擎产生，模型只能解释工具结果；不认识的问法会被判为需要澄清
（`CLARIFICATION_REQUIRED`），不会伪造业务结果。

### 步骤 4 处置与沙箱边界（2:40–3:20）

打开 `/supply-chain/disposal?planId=<planId>`（或从侧栏「处置与沙箱」进入）。

指出：当前真实基线是**模拟业务数据的只读基线**；模拟结果标记 `SIMULATED`；方案对比由排名引擎产生；
沙箱执行是演练，`realDataMutated=false`。

**现场不要点击**「确认采用」「运行模拟」「开始沙箱执行」等写操作——它们会改变演示数据状态。

### 步骤 5 离线评测（3:20–4:00）

打开 `/agent-evaluation`，切到「评测案例」标签页，点击 **全选离线案例**（当前显示
「全选离线案例（同版本 14）」），再点 **运行所选案例**，在确认框里点 **运行离线评测**。

预期：确认框写明「本次将运行 14 个案例，模式为 离线确定性…不调用模型、不写入业务表，可安全重复运行」；
运行结束后 `运行记录` 出现新的 `COMPLETED` 运行，14/14 通过，`modelCalls=0`，案例列表可展开看断言明细。

必须理解的规则：**一次运行只能包含同一案例版本的案例**。15 个离线案例里有 14 个属于数据集版本
`1.0.0`、1 个来自人工审核启用的反馈案例（版本 `feedback-1.0.0`），把它们混在一次运行会被后端以
`400 一次运行只能包含同一案例版本的案例` 拒绝——这是评测完整性设计，不是故障。因此全选按钮只选同版本
的最大一组；想单独演示反馈案例，就只勾选它。

也可以用 API 复现同一结果：

```bash
curl -s -X POST http://localhost:8065/api/agent-evaluation/runs \
  -H 'Content-Type: application/json; charset=utf-8' \
  -d '{"caseIds":["ORDER-ORD-1001","INVENTORY-SKU-MCU-001","SUPPLIER-SUP-ALPHA"],"mode":"OFFLINE"}'
```

讲法：OFFLINE 是确定性检查，不调用模型，指标与 AGENT 分开统计，不会把离线通过率说成模型能力。
案例目录共 **21 条（OFFLINE 15 + AGENT 6）**。

### 步骤 6 运行观测与追溯（4:00–4:30）

打开 `/agent-observability`：汇总只统计已持久化的任务与事件，能看到 `MODEL_FAILED`、`FALLBACK_USED`、
确定性来源与 `limitations`；任务、报告、评测运行、反馈之间可相互追溯。

### 步骤 7 安全门禁收尾（4:30–5:00）

打开 `/system/model-config`：CHAT/EMBEDDING 均 `NOT_CONFIGURED`。回到 `/agent-evaluation` 尝试 AGENT
模式会被拒绝。实测拒绝行为：

| 操作 | 结果 |
| --- | --- |
| AGENT 运行（模型未验证） | `HTTP 412`，`code=AGENT_CHAT_NOT_READY`，不创建任务/租约 |
| AGENT 运行但缺 `confirmAgentRun` | `HTTP 400` |
| OFFLINE 运行混入 AGENT 案例 | `HTTP 400` |
| 运行后 `capabilities.agentRunActive` | `false`（评测锁未残留） |

讲法：连接测试是用户手动触发的真实调用，系统不会自动轮询、不会自动降级到其他模型；
真实模型 E2E 需要外部配置与授权，当前明确标注为未完成，而不是隐藏。

## 5. 现场故障处理

| 现象 | 处理 |
| --- | --- |
| 页面报 500/空白或接口全红 | 后端没起或 profile 不对：确认 `curl http://localhost:8065/api/supply-chain/orders` 返回 JSON |
| 中文问题提交后返回 `400 Failed to read HTTP message` | 是终端编码问题（Windows 控制台 GBK），不是应用缺陷；用页面输入，或把 JSON 存成 UTF-8 文件后 `--data-binary @file` |
| 前端页面能开但接口 404 | 生产构建的后端地址写错：重新 `CHAINPILOT_BACKEND_URL=... npm run build` 后再 `npx next start` |
| 决策问题被判为需要澄清 | 换成步骤 3 的问法（含 SKU、数量、比较/推荐） |
| 演示数据被误改 | 用 `?taskId=` 打开另一个已完成任务继续讲，不要现场清库 |
| 控制台输出中文乱码 | 终端编码所致；接口响应本身是 UTF-8（已实测） |

## 6. 本轮验收证据（2026-10-22，Windows 11 + JDK 17 + Node 24，H2 文件库）

- 后端 `spring-boot:run -Dspring-boot.run.profiles=h2` 启动成功；`GET /api/model-config/check-ready`
  返回 `chatState=NOT_CONFIGURED`。
- 现场创建分析任务 `1cae5020-1feb-4147-8f7d-e2c8a769f7fd`：`POST` 返回 200/`ACCEPTED`，终态
  `COMPLETED/DONE`，27 条事件（含 2 次 `MODEL_FAILED` + 2 次 `FALLBACK_USED`），报告含
  `ORD-1001/1002/1004`，`narrativeSource=DETERMINISTIC_FALLBACK`、`modelAssisted=false`。
- 现场创建决策任务 `98d3bf8c-d64e-4568-b258-5b8a17d0aaab`：`COMPARE_REPLENISHMENT` +
  `compareDisposalScenarios`（3 候选）+ 叙述降级，`realDataMutated=false`。
- 离线评测运行 `c1a05c61-d7b6-484f-8053-a94ea7d7b30f`：3/3 `PASSED`，`modelCalls=0`。
- 停止并重启后端后，上述任务状态、27 条事件与评测运行仍完整可读（文件库持久化）。
- 浏览器（系统 Chrome + puppeteer-core，生产构建 3002）逐页检查
  `/`、`/supply-chain?taskId=…`、`/supply-chain/decision?taskId=…`、`/supply-chain/disposal`、
  `/supply-chain/history`、`/agent-evaluation?runId=…`、`/agent-observability`、`/system/model-config`：
  8/8 页面非空白、关键业务标识存在、**0 个失败请求、0 个 HTTP≥400、0 条 console error**；
  种子数据 5 个页面同样 0 问题，且页面不含任何「模型生成」误导表述。
- 页面真实操作：工作台点击「运行分析任务」创建任务成功（POST 200 → `COMPLETED`，页面渲染风险订单与
  「确定性叙述」）；评测中心点击「评测案例 → 全选离线案例（同版本 14）→ 运行所选案例 → 运行离线评测」
  得到运行 `e0d2b9b5-…`，`COMPLETED`、14/14 通过、`modelCalls=0`。
- 本轮修掉一个会当场阻断演示的缺陷：「全选离线案例」原先会同时选中数据集案例与反馈案例（两个案例版本），
  运行必然被后端以 `400 一次运行只能包含同一案例版本的案例` 拒绝；现在只选同版本最大一组并在按钮上显示数量，
  并补了 `src/lib/evaluation-case-groups.ts` 与 5 条单元测试。
- 只读体检脚本：`failures=0, blocked external conditions=1`；数据准备脚本 `createdCount=0`（幂等）。
- 前端：Vitest **118/118**（19 文件）、`tsc --noEmit` 无错误、ESLint 无告警、`next build` 成功。
- 后端：全量 H2 回归 **2047/2047**、Checkstyle **0 violations**（本轮未改后端代码）。

未在本轮执行：真实 Provider 调用、MySQL 集成测试、双 JVM 验收、`npm run dev` 模式复跑。
