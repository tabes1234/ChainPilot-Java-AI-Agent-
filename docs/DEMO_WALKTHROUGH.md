# ChainPilot 3–5 分钟演示脚本

目标：让面试官在 3–5 分钟内理解“Java 确定性规则 + 受约束模型叙述 + Agent 评测/观测”的闭环。
默认使用本地 H2 中已有记录，不调用真实模型。

> 当前前端是 `data-agent-frontend-next`。启动命令和端口见根 [README](../README.md)。
> 新环境的空数据库可先运行 `scripts/chainpilot-demo-seed.ps1`，它会幂等创建带
> `[DEMO-SEED]` 标记的分析、决策、方案、沙箱、评测和反馈记录；不会调用模型，也不会清空数据。
> 脚本拒绝在已有任务但没有演示标记的数据库上写入，除非显式传入 `-AllowExistingData`。

## 0:00–0:20 首页

打开 `http://localhost:3000`（生产验收使用 `http://localhost:3002`）。

讲解重点：

- ChainPilot 是供应链风险决策工作台，不是通用聊天壳。
- 订单/库存/供应商是本地模拟数据，不会写真实 ERP。

## 0:20–1:20 风险分析与证据

打开 `/supply-chain?taskId=<一个已有的 COMPLETED taskId>`，或从 `/supply-chain/history` 打开已有任务。

指出：

- 风险分数、等级、`ORD-*`、证据和 dataGap 来自 Java 确定性规则。
- 当前示例记录显示 `DETERMINISTIC_FALLBACK`，说明模型未配置/未参与，但规则结果仍可用。
- 在“执行事件”中可以看到真实任务事件；如果模型曾失败，会看到 `MODEL_FAILED`/`FALLBACK_USED`。
- 该示例的风险订单数为 3，dataGap 为 0；不要把它说成“没有风险”或“模型已验证”。

## 1:20–2:10 处置、方案与沙箱边界

打开 `/supply-chain/disposal?planId=<已有 planId>` 或 `/supply-chain/disposal`。

指出：

- “当前真实基线”是**模拟业务数据的只读基线**，不是生产库存。
- 模拟结果标记 `SIMULATED`，比较结果由 Java 排名引擎产生，模型只能解释。
- 选中方案是 `SELECTED`；存在的沙箱执行状态是 `SUCCEEDED`，但再次执行会因已有成功执行而不可行。
- 沙箱是演练，不是真实 ERP/WMS 写入；`realDataMutated=false`。
- 不要点击“确认采用”“运行模拟”“开始沙箱执行”等写操作。

可选：打开 `/supply-chain/decision?taskId=<已有 DECISION taskId>` 展示候选方案和叙述来源。

## 2:10–3:10 评测中心与人工反馈

打开 `/agent-evaluation?runId=<已有 runId>`，再切换到「人工反馈」。

指出：

- 运行记录是 persisted OFFLINE evaluation，`modelCalls=0`；不是真实模型运行。
- 反馈列表显示任务、类型、来源和 `已启用评测案例`。
- 点击「查看草案」后可以看到：
  - 反馈快照明确标注“不是标准答案”；
  - 8 条人工确认的可执行断言；
  - 反馈关联任务和已完成案例。
- 不要点击“运行该案例”“提交审核”“启用评测案例”。

当前只读验收使用的反馈记录（仅作为本地示例）：

- taskId `b4526361-fe33-4e44-84ff-9e23024dd94b`
- feedbackId `f01645b1-5252-41e3-b603-b3c33af378ba`
- caseId `FB-69FEFD90`
- evaluation runId `5ff937de-7e36-4aae-988c-839ee0b909b0`

## 3:10–3:50 运行观测

打开 `/agent-observability`，选择已有任务。

指出：

- 汇总只统计已持久化任务/事件；模型/工具/回退诊断来自真实事件。
- 当前示例可以看到 `MODEL_FAILED`、`FALLBACK_USED` 和确定性来源；没有真实 provider usage。
- 任务、报告、评测运行和反馈记录可以相互追溯。

## 3:50–4:20 模型就绪与安全门禁

打开 `/system/model-config`，再回 `/agent-evaluation`。

指出：

- CHAT/EMBEDDING 均为未配置；AGENT 前置条件“未满足”。
- 连接测试是用户手动触发的真实调用，不会自动轮询。
- 当前没有真实模型 E2E；不能把连接测试、Mock 或离线评测说成真实模型成功。
- 如果配置 CHAT，必须手动测试、选择案例、确认费用后才可能启动 AGENT。

## 4:20–4:30 收尾

一句话总结：

> ChainPilot 把正式风险决策交给可回归的 Java 规则，把模型限制在证据解释层，再用评测、观测和
> 人工审核保证每一步可追溯；真实模型供应商 E2E 目前仍待外部配置和授权。

## 演示禁止事项

- 不调用真实模型、不点击连接测试。
- 不提交新的反馈、任务、模拟、比较或评测。
- 不确认/执行方案或沙箱写操作。
- 不把模拟数据、Java 规则结果或确定性降级说成真实模型生成。
- 不把测试数量或演示截图当作业务效果指标。

## 可发布截图

- `docs/assets/screenshots/home.png`
- `docs/assets/screenshots/analysis.png`
- `docs/assets/screenshots/observability.png`
- `docs/assets/screenshots/feedback-evaluation.png`
- `docs/assets/screenshots/model-readiness.png`

本轮只读浏览器证据在 `.visual-check/round15-*.png` 和 `round15-demo-readonly.json`
（本地证据，不自动发布）。
