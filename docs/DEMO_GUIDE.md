# ChainPilot 最终演示流程

> **当前前端入口（2026-10-21）**：使用 `data-agent-frontend-next`（Next.js 16）。
> 本文后面仍保留早前 Nuxt 阶段的详细验证记录作为历史证据；日常演示请以本节和根目录
> [README](../README.md) 为准，不要再用旧 Nuxt 启动命令代替当前流程。
> 需要 3–5 分钟面试演示时，优先使用 [DEMO_WALKTHROUGH.md](DEMO_WALKTHROUGH.md)。

## 当前 Next.js 本地启动

1. 使用 JDK 17 启动后端：

   ```powershell
   .\mvnw.cmd -pl data-agent-management spring-boot:run "-Dspring-boot.run.profiles=h2"
   ```

2. 在另一个终端启动 Next.js：

   ```powershell
   cd data-agent-frontend-next
   npm ci
   npm run dev
   ```

3. 新数据库没有记录时，运行 `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\chainpilot-demo-seed.ps1`
   初始化一条可重复使用的完整演示链；脚本不会调用模型或清空数据。

4. 浏览 `http://localhost:3000`。如需生产构建验收，使用：

   ```powershell
   npm run build
   npm run start -- -p 3002
   ```

4. 后端默认 `http://localhost:8065`；Next 将 `/api/**`、`/uploads/**` 代理到
   `CHAINPILOT_BACKEND_URL`。

5. 只读检查：

   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\chainpilot-smoke-test.ps1
   ```

   默认只执行 GET 请求，不写数据库、不调用连接测试、不调用真实模型。

## 历史 Nuxt 启动方式（已不再是当前入口）

以下命令仅作为早前 Nuxt 验收记录，当前开发与演示请使用 Next.js：

```powershell
cd data-agent-frontend-nuxt
pnpm install --frozen-lockfile
pnpm dev
```

## 展示供应链分析

1. 查看列表中的风险分数、风险等级与业务证据。
2. 使用默认问题“分析今天有哪些订单可能延期，结合库存和供应商风险，给出处理建议。”提交任务。
3. 观察任务 ID、Graph 步骤、校验次数和完成状态。
4. 查看订单风险依据、对应处置建议以及 `SC-SOP-001/002/003` 来源引用。
5. 在分析历史、执行中面板或报告的任务编号旁点击“执行轨迹”，展示实际节点、工具、模型失败与降级事件；运行中的任务可刷新查看已写入的部分事件。也可以使用返回的任务 ID 查询 `/api/supply-chain/analysis/tasks/{taskId}/events`。
6. 在右侧“最近分析”中确认任务已进入历史列表，点击“查看报告”可重新加载刚完成的报告。
7. 打开原有 DataAgent 的数据问答、数据源和模型配置页面，展示上游功能入口仍然保留。

## 展示风险处置与情景模拟

“风险处置与情景模拟”工作区嵌在供应链页面中，位于订单风险清单和分析面板之后，不是独立的静态演示页。所有按钮都调用真实后端接口，所有数字来自确定性 Java 规则。

### 页面操作

1. 在“处置建议”区域选择“全部风险订单”“按 SKU”或“按供应商”，点击“应用范围”重新生成建议。建议按 P1/P2/P3 排序，卡片展示规则优先级、业务依据、预期改善、前置条件、副作用、来源和生成时间；展开详情可查看完整规则依据。
2. 点击建议上的“带入模拟”，会把该 SKU 和缺口数量或该供应商带入下方表单。也可以手动选择“库存补货”或“供应商延期”场景；补货数量范围 1–1,000,000 件，额外延期范围 1–90 天。
3. 点击“运行模拟”，结果区展示基线、模拟后和差值：风险订单数、高风险订单数、存在缺口订单数、缺口总量、风险评分合计、目标 SKU 可承诺量，以及逐单变化（交付日期、风险等级、评分、缺口）。同时显示“确定性 Java 计算”“真实数据未变更”、模拟假设、限制和数据证据。
4. 在“保存处置方案”中填写标题，可选择附带当前模拟、关联当前已完成分析任务、是否使用模型解释，然后点击“保存方案”。方案会持久化建议、模拟、SOP 引用和解释文本。
5. “最近模拟”可重新打开任一模拟；“已保存处置方案”支持状态筛选、分页、查看详情、选择建议并提交“确认选用 / 退回待确认 / 标记不采用”。方案详情中的“在模拟工作区查看完整结果”会重新加载关联模拟。
6. 页面明确区分原始业务数据、规则计算结果和 AI 解释：模拟结果标记为“预期结果”，解释标记为“模型解释”或“规则降级说明”，真实库存和订单区域不会因模拟而变化。
7. 只有状态为 `SELECTED` 的方案可以发起“执行演练”。演练区先显示后端返回的资格检查结果；点击“开始执行演练”后，按钮在请求期间禁用，重复点击复用同一个幂等键，不会生成第二份草稿或执行记录。执行详情展示状态、模式、来源模拟、请求编号、创建/完成时间、5 个实际执行步骤（输入摘要、校验、结果、失败原因）、任务草稿、演练结论、未解决事项和来源证据；历史记录区可重新打开任一执行详情。

### 多方案对比与处置决策推荐

- 入口是工作区中的“多方案对比与处置决策推荐”面板：选择对比场景、SKU（或供应商），编辑 2–5 个候选参数（补货件数或额外延期天数），点击“运行对比”。候选参数必须唯一；库存补货为 1–1,000,000 件，供应商延期为 1–90 天。
- 对比引擎在一次请求内捕获同一份共享基线快照，并为每个候选复用现有确定性模拟引擎生成独立模拟记录；`baselineFingerprint` 是订单、库存、供应商与基准日期排序后的 SHA-256 指纹。所有候选必须共用该基线，否则不生成排名。
- 排名为确定性 Java 字典序：高风险订单变化量 → 风险订单变化量 → 存在缺口订单变化量 → 缺口总量变化量 → 风险评分合计变化量 → 模拟后目标 SKU ATP（库存补货场景）；全部指标相同视为并列，按干预量更小、再按输入顺序推荐。不估算成本、预算、产能、供应商承诺或收益。
- 结果区展示共享基线、候选指标（模拟后 − 基线）、逐候选排名/理由/取舍、推荐结论、未知项和人工确认项，并明确标注“真实数据未变更”。`RECOMMENDED`、`TIE_BREAK`、`INCOMPARABLE` 三种结果都不会强行给出绝对胜出者。
- 推荐候选可直接“保存为处置方案”：后端复制该候选模拟（新模拟 ID）再保存方案，避免复用“一个模拟只能关联一个方案”的约束；也可把候选关联到已有同范围、同模拟参数的方案。两种操作都会刷新方案历史与执行资格。
- “最近方案对比”可重新打开历史详情；对比、候选及其模拟分别持久化在 `chainpilot_disposal_comparison`、`chainpilot_disposal_comparison_candidate` 与既有模拟表，重启后可查询。

### 执行演练与审计追踪

“执行”是本地沙箱中的业务流程演练，不是对真实库存、订单、供应商或 ERP 的写操作。页面用层级图例区分“规则计算 / 情景模拟 / AI 解释 / 沙箱执行记录 / 真实业务操作：未执行”，并重复展示免责声明。

- **资格**：方案必须为 `SELECTED`，选中的建议必须与方案记录一致，动作必须为 `REPLENISH_INVENTORY` 或 `FOLLOW_UP_SUPPLIER`，且方案必须带有模拟结果与证据。人工确认类动作（`MANUAL_CONFIRMATION`）不可演练。
- **步骤**：`PLAN_AND_RECOMMENDATION_VALIDATION` → `SIMULATION_AND_EVIDENCE_VALIDATION` → `ACTION_PARAMETER_VALIDATION` → `SANDBOX_TASK_DRAFT_GENERATION` → `AUDIT_AND_RESULT_RECORDING`。每一步由 Java 代码实际执行，输入摘要、校验结论、结果和失败原因随进度写入数据库。
- **产物**：补货动作生成补货任务草稿（SKU、补货数量、当前/目标现货与可承诺量、关联订单、建议动作、待人工确认事项）；供应商动作生成供应商跟进草稿（供应商、延期订单、建议动作、待确认事项）。草稿只存在于执行记录 JSON 中，不创建采购单、入库任务或供应商通知。
- **状态**：`RUNNING → SUCCEEDED | FAILED`。失败时保留已完成/失败步骤、失败原因和可恢复状态；失败记录不阻塞同一方案使用新请求编号重试。同一方案已有 `RUNNING` 或 `SUCCEEDED` 记录时，新的请求编号返回 409；同一请求编号重放返回原记录并带 `X-Idempotent-Replay: true`，HTTP 状态为 200（首次为 201）。
- **并发与事务**：启动时对方案行 `SELECT ... FOR UPDATE`，在同一本地 JDBC 事务中检查幂等键与阻塞记录；唯一索引 `(disposal_plan_id, idempotency_key)` 兜底。只提供单库本地事务语义，不声称分布式 exactly-once。
- **数据隔离**：执行前后对库存、订单、供应商数据做指纹比对，`realDataMutated=false` 只有在两侧一致时才成立；审计步骤会再次核对并关联来源证据。

### 确定性规则

- 基线风险沿用现有 `SupplyChainQueryService.assess`：ATP = `max(0, onHand - reserved - safetyStock)`；风险等级 HIGH ≥ 60、MEDIUM ≥ 30，其余为 LOW。
- 库存补货只复制目标 SKU 的库存并把 `onHandQuantity + 补货数量`，其他 SKU、订单和真实数据不变。
- 供应商延期只复制该供应商未交付订单，把 `estimatedDeliveryDate + 额外延期天数`；已交付订单不变。
- 建议优先级：
  - 紧急度：最早要求交付日相对当前日期已逾期 45 分、≤2 天 40 分、≤4 天 30 分、≤7 天 20 分、其余 10 分。
  - 库存补货：紧急度 + `min(30, ceil(30 × 缺口 / 需求))` + 最高风险分（HIGH 10 / MEDIUM 6），上限 100。
  - 供应商跟进：紧急度 + 供应商风险分（HIGH 20 / MEDIUM 10）+ 延期分（DELAYED 10，否则 `min(20, 10 + lateDays × 2)`）+ 最高风险分。
  - 数据缺失的人工确认建议：紧急度 + 20 + 最高风险分。
  - P1 ≥ 75，P2 ≥ 55，其余 P3。模型不参与打分。
- 模拟指标：订单总数、风险订单数、高风险订单数、存在缺口订单数、缺口总量、风险评分合计、目标 SKU ATP；差值 = 模拟值 − 基线值，受影响订单 = 任一字段发生变化的订单。全部由 Java 重算，重复运行同一参数结果一致（模拟 ID 和生成时间除外）。
- 非法参数（缺字段、数量/天数越界、未知 SKU/供应商、场景与参数不匹配）返回 400；未找到的方案、模拟或任务返回 404；重复关联模拟或非法状态迁移返回 409。模拟不会写回库存或订单表。

### REST API

```bash
# 建议
curl "http://localhost:8065/api/supply-chain/disposal-recommendations?sku=SKU-MCU-001"
curl "http://localhost:8065/api/supply-chain/disposal-recommendations?supplierId=SUP-ALPHA"

# 模拟
curl -X POST http://localhost:8065/api/supply-chain/disposal-simulations \
  -H "Content-Type: application/json" \
  -d "{\"scenario\":\"INVENTORY_REPLENISHMENT\",\"sku\":\"SKU-MCU-001\",\"replenishmentQuantity\":100}"
curl "http://localhost:8065/api/supply-chain/disposal-simulations?limit=6"
curl "http://localhost:8065/api/supply-chain/disposal-simulations/{simulationId}"

# 方案
curl -X POST http://localhost:8065/api/supply-chain/disposal-plans \
  -H "Content-Type: application/json" \
  -d "{\"title\":\"MCU 补货方案\",\"sku\":\"SKU-MCU-001\",\"simulationId\":\"{simulationId}\",\"generateExplanation\":false}"
curl "http://localhost:8065/api/supply-chain/disposal-plans?status=PENDING_CONFIRMATION&page=1&pageSize=5"
curl "http://localhost:8065/api/supply-chain/disposal-plans/{planId}"
curl "http://localhost:8065/api/supply-chain/disposal-plans/{planId}/simulations"
curl -X PATCH http://localhost:8065/api/supply-chain/disposal-plans/{planId}/status \
  -H "Content-Type: application/json" \
  -d "{\"status\":\"SELECTED\",\"selectedRecommendationId\":\"{recommendationId}\"}"

# 多方案对比
curl -X POST http://localhost:8065/api/supply-chain/disposal-scenario-comparisons \
  -H "Content-Type: application/json" \
  -d "{\"title\":\"MCU 补货对比\",\"scenario\":\"INVENTORY_REPLENISHMENT\",\"sku\":\"SKU-MCU-001\",\"candidates\":[{\"replenishmentQuantity\":50},{\"replenishmentQuantity\":100},{\"replenishmentQuantity\":200}]}"
curl "http://localhost:8065/api/supply-chain/disposal-scenario-comparisons?page=1&pageSize=5"
curl "http://localhost:8065/api/supply-chain/disposal-scenario-comparisons/{comparisonId}"
curl -X POST "http://localhost:8065/api/supply-chain/disposal-scenario-comparisons/{comparisonId}/candidates/{candidateId}/disposal-plans" \
  -H "Content-Type: application/json" \
  -d "{\"title\":\"MCU 补货 100 件\",\"generateExplanation\":false}"
curl -X PATCH "http://localhost:8065/api/supply-chain/disposal-scenario-comparisons/{comparisonId}/candidates/{candidateId}/plan" \
  -H "Content-Type: application/json" \
  -d "{\"planId\":\"{planId}\"}"

# 执行演练
curl "http://localhost:8065/api/supply-chain/disposal-plans/{planId}/execution-eligibility"
curl -X POST "http://localhost:8065/api/supply-chain/disposal-plans/{planId}/executions" \
  -H "Content-Type: application/json" \
  -d "{\"idempotencyKey\":\"demo-rehearsal-1\"}"
curl "http://localhost:8065/api/supply-chain/disposal-plans/{planId}/executions?page=1&pageSize=10"
curl "http://localhost:8065/api/supply-chain/sandbox-executions/{executionId}"
curl "http://localhost:8065/api/supply-chain/sandbox-executions/{executionId}/steps"
```

方案列表响应体包含 `items` 与分页信息，同时返回 `X-Total-Count`、`X-Page`、`X-Page-Size`、`X-Total-Pages` 响应头。方案、模拟、执行记录和方案对比持久化在 `chainpilot_disposal_plan`、`chainpilot_disposal_simulation`、`chainpilot_disposal_execution`、`chainpilot_disposal_comparison`、`chainpilot_disposal_comparison_candidate` 表；执行记录包含关联方案、来源模拟、幂等键、步骤 JSON、草稿 JSON、最终结果与未解决事项，对比记录包含共享基线指纹、基线/排名/推荐 JSON 和候选关联方案，重启后仍可查询。

### Agent 工具与解释边界

模型可调用处置相关工具：`findDisposalRecommendations`、`simulateInventoryReplenishment`、`simulateSupplierDelay`、`compareDisposalScenarios`、`compareSupplierDelayScenarios`、`findScenarioComparison`、`findDisposalPlan`、`checkSandboxExecutionEligibility`、`findSandboxExecution`、`findSandboxExecutions`；`startSandboxExecution` 不在模型工具集中，只能通过人工确认的提案执行。工具直接调用上述 Java 服务，结果进入工具事件和 Tool Calling 证据；`compareDisposalScenarios` 只接收 SKU 与 2–5 个补货数量，由同一个 Java 对比/排名引擎生成并持久化结果，模型只能解释工具返回的推荐、排名与指标，不能自行编造候选、指标、排名或证据。沙箱工具只做资格检查、受限演练和记录查询，不能写真实库存、创建采购单或联系供应商。方案解释由 `DisposalExplanationService` 生成：模型只拿到已计算好的建议、模拟和 SOP 摘要，并要求在疑问时先调用工具核对；没有可用模型、调用失败或返回空内容时，明确写入“规则降级说明”，不会伪造模型解释。方案保存可选择 `generateExplanation=false` 完全跳过模型。

### 本地验证

```bash
cd data-agent-management
JAVA_HOME="C:/Program Files/Microsoft/jdk-17.0.12.7-hotspot" ../mvnw -o -pl data-agent-management \
  -DforkCount=0 -Dtest="DisposalExplanationServiceTest,DisposalSimulationServiceTest,DisposalRecommendationServiceTest,DisposalPlanServiceTest,DisposalExecutionServiceTest,DisposalScenarioRankingEngineTest,DisposalScenarioComparisonServiceTest,SupplyChainDisposalHttpIntegrationTest,SupplyChainSandboxExecutionHttpIntegrationTest,SupplyChainToolCallingHttpIntegrationTest" test
```

单元测试覆盖补货/延期的正常、边界、非法参数、未知目标和真实数据隔离，建议优先级与受影响订单，方案保存/查询/状态迁移，模型解释引用确定性数值与失败降级，执行资格/幂等/并发/中途失败恢复，多方案 2–5 边界、共享基线、并列/不可比、排名与理由一致性、候选保存/关联和重复请求，以及 HTTP 全链路和模型工具结果。2026-10-20 的验收结果见下方“验证记录”；浏览器验收完成了补货动作的沙箱演练，验证了双点击只产生一条记录、从历史打开详情、页面刷新后仍可查询，供应商跟进动作由 HTTP 与 MySQL 集成测试覆盖。

当前边界：沙箱执行依赖本地单库事务和行锁，只适用于单实例或共享同一数据库的并发请求，不声称分布式 exactly-once；其他 MySQL 版本、多实例压测、生产网络/TLS 和最小权限账号未验证。若某供应商没有任何非 LOW 风险订单，按该供应商创建方案会因“没有匹配的处置范围”返回 400，这是当前设计限制；可先按 SKU 创建方案或补充风险订单数据。

### 验证记录（2026-10-20）

- 后端全量 H2 回归：`Tests run: 1882, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`。自然语言决策相关测试：`SupplyChainDecisionHttpIntegrationTest` 8/8（新增“候选保存提案确认后才创建方案、错误哈希被拒、幂等重放不重复建方案”回归）、`SupplyChainDecisionServiceTest` 9/9、`SupplyChainDecisionIntentParserTest` 12/12、`ActionProposalServiceTest` 12/12、`ModelToolCallSessionTest` 5/5。定向处置相关单元/HTTP 测试 50/50 通过。多方案对比相关测试：`DisposalScenarioRankingEngineTest` 5/5、`DisposalScenarioComparisonServiceTest` 6/6、`SupplyChainDisposalHttpIntegrationTest` 8/8（含对比创建、历史、保存为方案、关联与冲突）、`SupplyChainToolCallingHttpIntegrationTest` 7/7（含 `compareDisposalScenarios` 工具）；执行演练相关的 `SupplyChainSandboxExecutionHttpIntegrationTest` 6/6、`DisposalExecutionServiceTest` 1/1 同时通过。
- 早前 MySQL 增量：真实 MySQL 8.0.43（临时回环实例、重建的干净专用库 `chainpilot_mysql_it`）：运行 `SupplyChainMySqlHttpIntegrationIT` 5/5、`SupplyChainMySqlPersistenceIT` 10/10，合并 15/15 通过，`BUILD SUCCESS`。HTTP IT 覆盖对比记录与候选在 MySQL 中的创建、历史查询、保存为方案、关联和重复请求；持久化 IT 覆盖执行表 DDL/唯一索引、步骤与草稿 JSON 读写、连接重建、重复键回滚、失败重试和分页，以及 DECISION 任务结果与提案表在 MySQL 中的持久化、确认边界和级联删除验证。稳定性收尾轮在同一专用库重跑并新增 1 个 DECISION 保存提案用例，结果为 HTTP 6/6、持久化 10/10、合并 16/16，详见“MySQL 持久化与 HTTP 链路验证”。Docker/Testcontainers 路径未执行。
- 早前沙箱增量中的真实进程重启：创建方案 `30a2186d-c029-4c0f-b3b7-2af9f2177030` 与执行 `fcc7632c-7d66-464c-b79d-00fc79544ac1`（`SUCCEEDED`、5 步、草稿 `DRAFT-REPL-8943a051-a961-4d3c-8bbb-c9fcf94c7e34`、`realDataMutated=false`），停止并重启 Spring Boot 后重新查询成功；方案历史仍为 1 条，使用同一请求编号重放返回 200 与 `X-Idempotent-Replay: true`，MySQL 原始行确认 `mode=SANDBOX`、`real_data_mutated=0`、步骤数 5。该轮未重复该重启实验，对比记录的持久化由 H2 页面历史重开和 MySQL IT 覆盖。
- 早前前端增量：`test:unit` 105/105 通过（含决策服务、composable、确认哈希、重复点击守卫和确认后方案状态同步），`npm run build` 成功。既有对比浏览器验收使用 3 个补货候选（50/100/200），确认推荐为 `补货 +100 件`、排名 #2 为 200、#3 为 50，页面展示共享基线、排序键、理由、取舍、未知项与人工确认项；保存为方案返回 201（方案 `1baa348d-b8db-4899-ab34-27bd96a741c8`），候选显示已关联方案并刷新方案历史；将方案确认选用为 `SELECTED` 后，资格检查通过并成功发起本地沙箱演练 `3557573a-01d6-408d-9994-cdd84e6313ff`（`SUCCEEDED`、5 步、草稿 `DRAFT-REPL-1216f0c9-2aeb-4443-8bac-35a1eec65d7c`、`realDataMutated=false`）；从历史重新打开对比触发详情 GET 200。浏览器截图受当前内嵌视口不可用限制，改用可访问性快照、DOM 文本和网络请求核对。
- 早前自然语言决策浏览器验收（该轮尚未配置聊天模型）：任务 `5f21f622-7272-4098-8829-d3852e9391d4` 经 `compareDisposalScenarios` 生成对比 `8cfdb54d-2dcd-48b5-be72-9b8f324f0dc1`，候选 50/100/200（ID `67b95018-5b3a-4105-8f21-36da2fa1c43d` / `d25aa839-a0c3-4a5f-a280-82e0aefc1168` / `7799f0ca-995c-44da-933f-ddc873cb8845`）排名 #3/#1/#2，推荐补货 +100 件，缺口 330→230、风险评分 297→272、ATP 20→120；页面展示共享基线、理由、取舍、未知项及“规则降级说明”。另一任务 `6cf7562b-a2fd-451a-ada3-a2b4295933ad` 生成 `SELECT_PLAN` 提案 `87580375-8611-4f74-bff5-bebcbd91fca7`（`PENDING`、内容哈希 `b85335160f20…4eaa`，确认前无执行记录）；确认后后端重新校验并返回 `status SELECTED`、`selectedRecommendationId 2ef3131f-ba98-468f-bddb-b54986b6bde4`、`realDataMutated=false`，页面同步刷新关联方案状态。浏览器截图受隐藏视口限制，改用可访问性快照、DOM 文本和网络请求核对；该验收只写入本地 H2 演示数据。
- 早前 DeepSeek 真实模型自然语言决策验收（2026-10-20，非本轮重跑）：运行时活动 CHAT 模型通过既有模型配置 API 写入（未改测试环境变量），配置为 `provider=custom`、Base URL `https://api.deepseek.com`、模型 `deepseek-flash`、completion path `/chat/completions`、`max-tokens=2048`、`timeout=180s`、`max-model-calls=12`。使用用户指定问题“帮我分析 SKU-SENSOR-002 的缺货风险，比较补货 50、100、200 件的方案，告诉我推荐哪个，并说明理由。”提交决策任务 `36473222-2d65-4552-820a-986da4be06a4`：意图 `COMPARE_REPLENISHMENT`，经 `compareDisposalScenarios` 生成对比 `a437ffc5-329a-437e-beff-e1dbda055d25`，三个候选 `772bb0e8-…`（+50，排名 3）、`2a30d0ca-…`（+100，排名 1、推荐）、`bcf3c66f-…`（+200，排名 2）；缺口变化 -55、模拟 ATP 75/125/225。推荐由 Java 排名引擎产生，模型只解释工具结果：`narrativeSource=MODEL_GROUNDED`、`modelAssisted=true`、`fallbackUsed=false`、`clarificationRequired=false`、`realDataMutated=false`，回答引用全部候选 ID、排名与指标，未出现未知 UUID 或写操作声明。事件为 1 次 `INTENT_RECOGNIZED`、1 次 `MODEL_COMPLETED`（`DECISION_SUMMARY`）、1 次 `TOOL_COMPLETED`（`compareDisposalScenarios`）、1 次 `TASK_COMPLETED`，无 `MODEL_FAILED` / `FALLBACK_USED` / `TOOL_FAILED` / `PROPOSAL_CREATED`；页面展示模型解释、候选表、工具调用与“真实数据未变更”，未显示降级提示。该轮真实模型调用共 2 次（一次连通性检查、一次决策解释），未重复付费调用；完整 Prompt、模型回答与推理内容未写入日志或文档。运行时活动模型配置保存在临时内存 H2，重启后已清除，原始任务/对比不再可查询，但证据 JSON 已保留。
- 保存提案确认缺陷与修复：上述真实任务生成保存提案 `e6e47cee-1ecb-4e08-b6f8-2b5a5a38cb7f`（`SAVE_CANDIDATE_AS_PLAN`，目标 `2a30d0ca-3eea-4649-9508-860e2e6b93b4`），确认前为 `PENDING`，无方案与执行记录；确认时后端返回 HTTP 400 `No at-risk order matches the requested plan scope`，提案变为 `FAILED`。根因是 `COMPARE_REPLENISHMENT` 决策任务只持久化排名候选、`atRiskOrders` 为空，`DisposalPlanService.resolveAssessments` 取不到订单。修复为仅对 DECISION 任务在该列表为空时，回退到当前确定性评估并严格限定在候选模拟的 `affectedOrders` 内；非 DECISION 任务行为不变。新增 `SupplyChainDecisionHttpIntegrationTest.saveCandidateProposalCreatesAPlanOnlyAfterConfirmationAndReplaysIdempotently` 覆盖“确认前无方案、错误哈希拒绝、确认后建方案、重复确认幂等”。
- 修复后真实确认链路复验（本地 H2，未调用模型）：对比 `0d423da0-9f33-4b92-a0b8-02a07830ea84`（SKU-SENSOR-002，候选 +50/`2768c24d-…`、+100/`5dcb36d5-…` 推荐、+200/`f1168b27-…`）创建保存提案 `6c171680-69b2-46c3-84b0-58aa9c812196`，确认前无方案与执行；确认后生成方案 `1d50fa01-4e06-4bc2-a5cb-d14e4c63eabd`（`PENDING_CONFIRMATION`、2 条建议、模拟 `445a8bf5-…`、补货 100、`realDataMutated=false`）并关联候选，重复确认返回同一方案。方案确认选用后，沙箱提案 `20d1e03f-2039-4c74-85c4-1697dc93cbc6` 在确认前不执行；确认后执行 `e0d4f69e-2d8c-4f8b-8145-2c5817ca03a1` 为 `SUCCEEDED`、5 步、`mode=SANDBOX`、`realDataMutated=false`，重复确认返回同一执行。保存与沙箱任务均为 `DETERMINISTIC` 叙述且无模型事件，证明确认边界与幂等语义未因修复改变。
- 早前浏览器确认复验（自动刷新修复前，2026-10-20）：在页面提交“将对比 `0d423da0-…` 的候选 `f1168b27-…` 保存为处置方案”，任务 `3028fe91-d11c-4465-babf-c3a190c2025e` 完成，页面显示“规则摘要 · 未调用模型”“真实数据未变更”、工具 `intent:PROPOSE_SAVE_PLAN` 与 `findScenarioComparison`、待确认提案 `26cc981d-d13d-4125-ad1b-0eceb688d98a`；确认前方案历史仍为 1，无执行。点击“确认执行”后页面显示“已确认并执行”，后端返回方案 `e1aca5ed-951d-47cb-9bc0-454336fbdde1`（`PENDING_CONFIRMATION`、2 条建议、模拟 `99fc96c6-…`、补货 200、`realDataMutated=false`）。持久化 API 查询方案总数为 2；页面方案历史没有自动刷新，点击“刷新处置方案”后显示两条，这是当前交互行为，不是持久化失败。决策历史中打开沙箱任务 `0339081b-511e-4ba4-9f1e-67c30127c956`，页面展示已确认提案 `20d1e03f-…` 与执行 `e0d4f69e-…`（`SUCCEEDED`、5 步、`mode=SANDBOX`、`realDataMutated=false`），执行步骤 API 也返回 5/5 `SUCCEEDED`。本轮所有确认链路只写本地 H2 演示数据，未调用付费模型，未修改真实业务数据。
- 稳定性收尾（本轮，前端确认后自动刷新）：保存方案提案确认成功后，`useSupplyChainDecision` 仅在响应状态为 `CONFIRMED` 时触发确认回调；页面用结果中的 `planId`（缺失时回退 `targetId`）调用处置工作区暴露的 `refreshAfterConfirmedProposal`。刷新会强制重载方案列表；若关联方案正是当前详情，则并行重载方案详情、执行资格与执行记录。确认失败不触发刷新并保留后端错误，刷新失败也不会把已成功的确认改写成失败；方案列表和详情刷新继续使用序列号，过期响应不会覆盖新状态，重复确认仍由原有 busy 状态与提案状态流转拦截。前端定向测试 47/47（决策 composable 17、处置 composable 30），全量 `test:unit` 113/113（13 个文件）、`npm run build` 成功。本轮严格限定为离线测试与数据库集成测试，未重新启动浏览器做人工点击验收，也未调用真实模型。
- 早前“本轮未配置聊天模型”的说明已被上述 DeepSeek 验收取代：当时 shell 中的 `CHAINPILOT_REAL_MODEL_API_KEY`、`CHAINPILOT_REAL_MODEL_ACCEPTANCE`、`DEEPSEEK_API_KEY`、`DATAAGENT_LIVE_DEEPSEEK_API_KEY` 均未设置；后续运行时模型配置通过界面/API 临时写入内存 H2，重启即失效。API Key 未出现在源码、文档、日志、Git 或报告中。未执行 Git commit；临时后端、前端与 MySQL 实例已停止，临时 H2 目录已清理。

## 展示自然语言决策 Agent

供应链页面中的“自然语言决策”面板是决策 Agent 的真实入口，不是静态演示：输入中文问题后，前端提交 `POST /api/supply-chain/analysis/tasks`（`mode=DECISION`），轮询任务状态，完成后读取 `/tasks/{taskId}/decision` 与 `/tasks/{taskId}/events`，并展示真实工具调用、证据、提案和历史记录。页面用不同标签区分原始业务数据、Java 规则结果、模拟/排名、模型解释、规则降级、待确认提案与已确认执行结果。

### 意图、工具与证据

- 意图由确定性 `SupplyChainDecisionIntentParser` 解析，不依赖模型猜测。支持风险分析、处置建议、补货/延期模拟、补货多候选对比、供应商延期多候选对比、对比/方案/执行查询，以及四类写操作提案。缺 SKU、供应商、数量或候选范围的请求会返回澄清，不会套用无关默认值；少于 2 个、重复或超过 5 个候选也会被拒绝。
- 比较类请求（2–5 个互不相同的候选）通过真实工具 `compareDisposalScenarios` / `compareSupplierDelayScenarios` 调用同一个 Java 排名引擎；模拟通过 `simulateInventoryReplenishment` / `simulateSupplierDelay`；查询通过 `findDisposalRecommendations`、`findScenarioComparison`、`findDisposalPlan`、`checkSandboxExecutionEligibility`、`findSandboxExecution`、`findSandboxExecutions`。这些工具只读，不写真实库存、订单、供应商、ERP 或采购单。
- 决策链路先由 Java 确定性执行工具，再把工具返回的候选 ID、指标、排名、推荐和证据交给模型生成解释；解释模型不持有工具或写权限。`narrativeSource` 标记 `MODEL_GROUNDED`、`DETERMINISTIC` 或 `DETERMINISTIC_FALLBACK`；模型失败时保留确定性对比证据并记录 `MODEL_FAILED` / `FALLBACK_USED`，工具失败时任务标记失败，绝不用模型补造候选或指标。
- 事件轨迹复用既有异步任务和事件表：`DECISION_INTENT`、`DECISION_EXECUTION`、`VALIDATING_DECISION`、`DECISION_SUMMARY`、`PROPOSAL` 以及各工具阶段都记录安全摘要、结果状态和错误类型，不写完整 Prompt、模型回答、敏感工具参数、凭证或 Authorization 头。每个结果都可追溯到任务 ID、实际工具、引用模拟/方案/证据和最终回答状态。

### 人工确认边界

- 自然语言请求可以提出“保存候选为方案”“关联方案”“选定方案”“启动沙箱演练”，但只会创建 `chainpilot_action_proposal` 中的 `PENDING` 提案；模型没有写工具，不能直接执行。
- 提案绑定任务、动作类型、目标 ID、参数和 SHA-256 `contentHash`，有效期 30 分钟。前端展示目标与参数，确认/取消时提交原 `contentHash`；目标或参数变化会使旧哈希失效。同一任务下内容相同的待确认提案会去重。
- 后端确认时重新校验任务已完成、目标存在、方案/候选/模拟状态与沙箱资格，再调用既有 `DisposalPlanService` / `DisposalExecutionService` 持久化真实结果。确认通过原子 `PENDING → EXECUTING` 抢占，重复确认返回原结果或 409，不生成第二条记录；沙箱仍保留 `SELECTED` 资格、幂等键、行锁与 `realDataMutated=false` 证据。
- 所有操作只写本地 H2/MySQL 业务记录；“编辑”会把提案参数带回自然语言输入，重新分析后生成新提案，不会修改旧提案或绕过确认。任何自然语言请求都不会写入真实库存、订单、供应商或 ERP。

### REST API

```bash
# 提交自然语言决策任务
curl -X POST http://localhost:8065/api/supply-chain/analysis/tasks \
  -H "Content-Type: application/json" \
  -d "{\"question\":\"比较 SKU-MCU-001 补货 50、100、200 件的方案\",\"mode\":\"DECISION\"}"

# 状态、结构化决策结果、事件轨迹
curl "http://localhost:8065/api/supply-chain/analysis/tasks/{taskId}"
curl "http://localhost:8065/api/supply-chain/analysis/tasks/{taskId}/decision"
curl "http://localhost:8065/api/supply-chain/analysis/tasks/{taskId}/events"

# 待确认提案
curl "http://localhost:8065/api/supply-chain/action-proposals?taskId={taskId}"
curl -X POST "http://localhost:8065/api/supply-chain/action-proposals/{proposalId}/confirm" \
  -H "Content-Type: application/json" -d "{\"contentHash\":\"{contentHash}\"}"
curl -X POST "http://localhost:8065/api/supply-chain/action-proposals/{proposalId}/cancel" \
  -H "Content-Type: application/json" -d "{\"contentHash\":\"{contentHash}\"}"
```

### 本地验证

```bash
cd data-agent-management
JAVA_HOME="C:/Program Files/Microsoft/jdk-17.0.12.7-hotspot" ../mvnw -o -pl data-agent-management \
  -DforkCount=0 -Dtest="SupplyChainDecisionIntentParserTest,SupplyChainDecisionServiceTest,ActionProposalServiceTest,SupplyChainDecisionHttpIntegrationTest,ModelToolCallSessionTest" test
```

`SupplyChainDecisionHttpIntegrationTest` 使用本地假模型与真实 Java 服务，覆盖自然语言比较引用真实候选/排名、澄清不调用模型、模型失败保留确定性证据、工具失败不伪造结果、沙箱提案必须人工确认、重复确认幂等、取消后不可执行和内容哈希不符被拒绝。前端决策相关单元测试覆盖服务、composable、确认哈希和重复点击守卫；稳定性收尾新增“确认成功后仅刷新一次方案列表”“确认失败不刷新且保留真实错误”“在途期间重复确认不重复刷新”“刷新异常不反转已确认结果”以及处置方案刷新竞态用例。

## 展示真实模型模式

在原有“模型配置”页面新增 OpenAI 兼容聊天模型，填写服务商、Base URL、模型名称与有效 API Key，执行连接测试并激活。重新提交分析后，查看报告的 `plannerAssisted` 与 `modelAssisted` 标记。工作流的订单、库存和供应商查询节点先通过 `@Tool` 对应的 Java 查询工具取数，数据校验通过后传入报告节点；报告摘要调用聊天模型时也注册了这组工具。模型凭证未配置时，规划和摘要分别回到固定计划、确定性业务摘要。

真实模型验收需要有效的聊天模型 API Key、可访问的 Base URL 和模型名。Embedding 服务不是供应链分析必需条件；没有向量服务时，知识检索使用本地词项匹配。不要在演示录像、截图、控制台输出或仓库中显示 API Key。完整的可选验收命令、调用上限和证据判读见下文“真实模型端到端验收”。

## 模型就绪预检与真实 Agent 评测准备（2026-10-21）

### 配置与手动连接测试

在 `/system/model-config` 新增并激活模型配置。CHAT 用于真实 Agent 工作流；EMBEDDING 用于向量检索，但当前供应链工作流在缺少 EMBEDDING 或 VectorStore 时会使用本地词典检索，因此不阻断最小真实 Agent 验证。

现有配置 API：

- `GET /api/model-config/list`：返回配置列表，`apiKey` 与代理密码只返回掩码。
- `POST /api/model-config/add`、`PUT /api/model-config/update`、`DELETE /api/model-config/{id}`：新增、修改、删除配置。
- `POST /api/model-config/activate/{id}`：激活同类配置中的一项。
- `POST /api/model-config/test/{id}`：对已保存配置发起一次真实连接测试。该操作只能由用户点击「测试」并确认后触发，可能产生少量供应商费用。
- `GET /api/model-config/check-ready`：只读返回配置、手动连接测试和 AGENT 前置条件，**不会调用模型**。

`check-ready` 的关键字段：`chatState`、`embeddingState`、`chatConnectionVerified`、`embeddingConnectionVerified`、`chatTestedAt`、`chatTestError`、`embeddingTestedAt`、`embeddingTestError`、`embeddingRequiredForAgentEvaluation`、`agentEvaluationReady`、`agentEvaluationBlockReason`、`verificationScope`。评测能力接口额外返回 `agentCasesRequiringChat`、`agentCasesRequiringEmbedding`，每个案例定义包含 `chatRequired` 与 `embeddingRequired`。当前 `workflow-1.0.0` 的 AGENT 案例都声明 `chatRequired=true`、`embeddingRequired=false`，因此 CHAT 必须已验证，EMBEDDING 可选；如果所选案例声明 `embeddingRequired=true`，后端会要求 EMBEDDING 已激活且在本次进程内连接测试通过。

后端按**所选案例的显式依赖**做最终门禁：需要 CHAT 而未验证时返回 `412` + `AGENT_CHAT_NOT_READY`；需要 EMBEDDING 而未验证时返回 `412` + `AGENT_EMBEDDING_NOT_READY`。OFFLINE 案例不得声明模型依赖；需要 EMBEDDING 的案例必须同时声明 CHAT；矛盾声明在构造案例时直接拒绝。测试结果与时间按当前设计保存在进程内，不使用数据库中的长期成功布尔值。

状态含义：

| 状态 | 含义 |
| --- | --- |
| `NOT_CONFIGURED` | 该类型没有激活配置。 |
| `CONFIGURED_UNVERIFIED` | 已配置并激活，但当前后端进程内尚未通过手动连接测试。 |
| `TEST_FAILED` | 最近一次手动连接测试失败，页面显示脱敏后的错误与测试时间。 |
| `VERIFIED` | 当前后端进程内最近一次手动连接测试成功。 |

验证结果保存在 `CURRENT_BACKEND_PROCESS` 作用域，后端重启后回到 `CONFIGURED_UNVERIFIED`，需要重新测试。页面加载和刷新只读取状态，不使用定时器或后台任务自动请求模型。修改配置会改变配置签名并使旧的测试结果失效；签名包含 Base URL、模型名、接口路径以及认证/代理参数的不可逆摘要，因此仅修改 API Key 或代理密码也不会继续复用旧的成功状态。删除配置会清除验证记录。

连接测试失败时，API 返回 `success=false`、安全 `message` 和分类 `code`，不返回原始异常或请求头。当前分类包括 `AUTHENTICATION_FAILED`、`CONNECTION_TIMEOUT`、`ENDPOINT_UNAVAILABLE`、`INVALID_MODEL_CONFIGURATION`、`UNSUPPORTED_RESPONSE`、`RATE_LIMITED`、`CONNECTION_TEST_FAILED`。服务端日志只记录错误类别、异常类型和经过密钥/Authorization/URL 凭据/敏感参数过滤的诊断摘要；不会记录原始异常全文。

### 真实 AGENT 评测操作路径

1. 在模型配置页新增/激活 CHAT 配置；需要向量检索时再配置 EMBEDDING。
2. 点击「测试」并确认，等待真实连接测试返回。
3. 进入 `/agent-evaluation`，选择工作流案例。前置条件面板显示 CHAT 是否已验证、EMBEDDING 是否可选。
4. 点击运行后，确认框显示将运行的案例、CHAT/EMBEDDING 状态、单次案例上限、超时和可能的费用范围，并明确：连接测试成功不等于工作流或断言成功；系统没有供应商价格表，不展示虚构金额。
5. 勾选费用影响确认后启动。后端会再次校验 `confirmAgentRun=true`、所选案例显式声明的 CHAT/EMBEDDING 依赖是否满足；未满足时返回结构化 `412` 错误码，不会提交任务。
6. 执行沿用现有保护：真实工作流单次最多 5 个案例、20 分钟超时、进程内单实例锁，并持久化运行、任务 ID、事件和断言结果。
7. 在评测中心查看运行详情；在 Agent 运行观测中心查看任务事件、模型/工具/回退诊断，并跳转原任务报告。

必须区分以下五层状态，不能用其中一层替代另一层：

- 模型配置存在；
- 连接测试成功；
- 真实 Agent Workflow 任务执行成功；
- 模型结构化输出通过证据校验；
- 最终业务任务与评测断言通过。

### 模型失败与可观测性

真实模型调用的开始和结束会分别记录 `MODEL_STARTED`、`MODEL_COMPLETED` / `MODEL_FAILED`；空输出、超时、不可用和格式错误会记录真实错误类别或回退原因，并关联当前任务。模型返回的 usage 会在供应商提供时写入事件摘要（如 `tokens prompt=..., completion=..., total=...`）；没有可信 usage 时明确写 `token usage unavailable`，不伪造 token、费用或耗时。

模型输出仍由 `RiskNarrativeValidator` 校验：未知订单/证据、无依据数值、执行声明、缺失 dataGap 或非法推荐都会被拒绝，来源回退为 `DETERMINISTIC_FALLBACK`，不会标为 `MODEL_VERIFIED`；Java 风险分数、等级和证据不因模型失败而改变。

**当前状态（2026-10-21）**：模型配置列表为空，`chatModelReady=false`，没有执行任何真实模型调用，也没有产生模型费用。真实 Agent Workflow 端到端验收仍因缺少用户授权的模型配置而阻塞；本轮只完成预检、门禁、假模型/测试替身覆盖和操作路径准备。

## 验证任务历史筛选与分页

完成若干次分析后，可以通过同一历史接口演示状态筛选和分页。响应体仍是任务数组，现有页面无需修改；分页元数据在响应头中：

```bash
curl -i "http://localhost:8065/api/supply-chain/analysis/tasks?status=COMPLETED&page=1&pageSize=2"
```

- `status` 支持 `ACCEPTED`、`RUNNING`、`COMPLETED`、`FAILED`，大小写不敏感。
- `page` 默认 1；`pageSize` 默认 10、范围 1–50；旧参数 `limit` 仍可用，同时提供时 `pageSize` 优先。
- 响应头 `X-Total-Count`、`X-Page`、`X-Page-Size`、`X-Total-Pages` 给出总数和分页信息。
- 排序固定为 `updated_at DESC, task_id DESC`；请求超出末页的页码会得到空数组，但总数元数据不变。
- `page=0`、`pageSize=0/51`、`limit=0/51` 或未知状态会返回 HTTP 400。

## 验证执行事件轨迹

### 在页面查看执行轨迹

1. 在右侧“分析历史”中，每条任务底部显示完整任务 ID 和“执行轨迹”入口；执行中任务可在“Agent 正在执行工作流”面板打开，已完成任务也可从报告的任务编号旁打开。
2. 事件按需加载：只有点击“执行轨迹”时才请求该任务的事件，不会为全部历史任务预先发请求。
3. 时间线严格按后端 `sequence` 升序展示，不按浏览器本地时间重排。每条事件显示序号、可读事件名称、阶段、状态、发生时间、后端实际提供的耗时以及安全处理后的摘要；失败事件额外显示异常类名。
4. 运行中的任务可以先查看已写入的部分事件，点击轨迹面板右上角“刷新”获取后续步骤；任务在该面板打开的状态下到达终态时，面板会再读取一次完整轨迹。
5. 顶部“模型调用完成”“模型调用失败”“规则降级”标记只在对应真实事件存在时出现。没有模型事件时不会推测模型是否被调用或失败；成功完成任务本身不会被当作模型调用证据。
6. 任务存在但没有事件时显示空状态并可刷新；任务不存在或已被清理时显示错误和“重试”；关闭轨迹不会影响报告、分页和状态筛选。
7. 未知的后续事件类型会以原始类型名和中性样式安全展示，不会导致页面崩溃。

### 通过 API 验证

完成一次分析后，用任务 ID 查询实际执行事件：

```bash
curl "http://localhost:8065/api/supply-chain/analysis/tasks/{taskId}/events"
```

默认未配置模型时，一次首轮校验通过的任务会产生 25 条事件，顺序如下：

1. `TASK_QUEUED`（阶段 `QUEUE`）。
2. `TASK_STARTED`（阶段 `WORKFLOW`）。
3. `PLANNING` 节点的 `NODE_STARTED`、`MODEL_FAILED`、`FALLBACK_USED`、`NODE_COMPLETED`。模型未配置时按真实失败记录，不会伪装成模型成功。
4. `QUERYING_ORDERS`、`QUERYING_INVENTORY`、`QUERYING_SUPPLIERS` 三个节点各自产生 `NODE_STARTED`、工具 `TOOL_STARTED`/`TOOL_COMPLETED`、`NODE_COMPLETED`。工具阶段名分别为 `findAtRiskOrders`、`findInventory`、`findSuppliers`。
5. `VALIDATING` 节点的 `NODE_STARTED`、`NODE_COMPLETED`。
6. `GENERATING_REPORT` 节点的 `NODE_STARTED`、`MODEL_FAILED`、`FALLBACK_USED`、`NODE_COMPLETED`。
7. `TASK_COMPLETED`（阶段 `WORKFLOW`）。

事件按 `sequence` 从 1 递增，`occurredAt` 为发生时间，`durationMs` 为可测量的毫秒耗时。若模型正常返回，对应阶段会记录 `MODEL_COMPLETED` 而不是 `MODEL_FAILED`；若模型返回空内容，会记录 `MODEL_COMPLETED` 后再记录 `FALLBACK_USED`。任务失败时可在对应阶段看到 `NODE_FAILED` / `TOOL_FAILED` / `MODEL_FAILED` 以及末尾的 `TASK_FAILED`，不会出现 `TASK_COMPLETED`。

边界行为：

- 未知任务 ID 返回 HTTP 404；任务存在但没有任何事件时返回 `200 []`。
- 事件只属于当前任务，不能用任务 A 的 ID 读取任务 B 的事件。
- 服务重启后已完成任务的事件仍保存在 H2 数据库中，可再次查询；重启时被标记为 `FAILED / INTERRUPTED_BY_RESTART` 的任务不会补写虚构事件。
- 事件表不包含问题原文、提示词、模型输出、工具参数、异常 message、API Key 或数据库凭据；错误事件只保留异常类名。
- 事件写入失败不会改变分析结果。若事件缺失，可检查应用日志中的 `ChainPilot event persistence failed` 告警，其中包含任务 ID、事件类型、阶段和异常类别。

## 验证模型与工具观测

供应链分析除了写入上面的业务事件，还通过 Spring AI 原生 Micrometer 观测记录模型与工具调用。两层信息用途不同：业务事件说明任务发生了什么、是否降级，观测用于耗时、状态和调用层级分析。

### 观测链路

1. `ModelObservationConfiguration` 提供一个应用级 `ObservationRegistry`，动态创建的 `OpenAiChatModel`、`DefaultToolCallingManager`、`ChatClient`、`RestClient` 和 `WebClient` 都使用同一个注册表；模型供应商、Base URL、模型名和凭证语义不变。
2. `OtelObservationHandler` 把 Micrometer 观测转换为 OpenTelemetry span，并复用 `OpenTelemetryConfig` 已有的 Langfuse `Tracer` 与 OTLP HTTP exporter。注册表本身不导出数据，导出发生在该桥接器和已有 exporter 中。
3. 一次带工具调用的模型请求会形成 `spring.ai.chat.client` → `spring.ai.advisor` → `gen_ai.client.operation` / `spring.ai.tool` 的父子关系；每次模型 HTTP 请求产生子级 `http.client.requests`。模型调用使用 `gen_ai.client.operation`，工具使用 `spring.ai.tool`，没有重复手写 span。
4. 模型已开始但 HTTP 失败时，模型观测记录错误状态；工具执行失败时，`spring.ai.tool` 记录错误，而 Spring AI 仍把工具错误结果交回模型。若模型随后给出有效回答，模型观测可以是成功，业务事件则同时保留 `TOOL_FAILED`。二者不会被互相改写。
5. Graph 节点直接调用的 `findInventoryForWorkflow` 等方法和模型 Tool Calling 共用业务实现，但不经过 `DefaultToolCallingManager`，因此只产生业务事件，不产生 `spring.ai.tool` 观测，也不会进入报告的 `toolCalls`。
6. 任务完成不代表模型成功：模型失败或空响应仍按真实情况记录 `MODEL_FAILED` / `FALLBACK_USED`；降级本身不是成功的模型观测。任务 ID 没有写入 span 属性，避免高基数；任务与追踪的关联仍以业务事件中的任务 ID 为准。
7. 未配置可用聊天模型时，请求不会进入 Spring AI `ChatModel`，因此不会产生模型或工具 span；业务事件仍记录 `MODEL_FAILED` / `FALLBACK_USED`，Graph 节点直接调用的工具只产生业务事件。

### 本地验证

```bash
cd data-agent-management
../mvnw -DforkCount=0 -Dtest="OtelObservationHandlerTest,SupplyChainToolCallingHttpIntegrationTest" test
```

- `OtelObservationHandlerTest` 使用内存 span exporter 验证成功/失败状态、耗时、`langfuse.observation.type=generation`、父子 span 和错误类型脱敏；不访问 Langfuse。
- `SupplyChainToolCallingHttpIntegrationTest` 使用仅绑定回环地址的本地假模型，验证客户端、模型、工具和 HTTP 观测的实际创建、次数、父子关系、工具成功与失败，以及报告标记和业务事件不变；不调用外部或付费模型。
- 当前自动化测试只验证本地观测和桥接结果，没有连接真实 Langfuse 实例做端到端导出验证。开启 `LANGFUSE_ENABLED=true` 并配置 `LANGFUSE_HOST`、`LANGFUSE_PUBLIC_KEY`、`LANGFUSE_SECRET_KEY` 后，span 由现有 Langfuse OTLP exporter 异步发送；未配置时 tracer 为 no-op，应用仍可正常启动和分析。

### 不采集的内容

观测属性只包含 Spring AI/Spring HTTP 约定提供的模型、供应商、工具名、工具描述/schema、token 用量、HTTP 方法和状态等元数据。未注册 Spring AI 内容过滤器，因此不采集完整系统提示词、问题原文、完整模型回答、工具参数或工具结果；错误只导出异常类名，不导出异常 message、请求体或响应体。API Key、访问令牌和数据库凭据不会写入观测属性。

## 可复现的供应链离线评测基线

评测数据位于 `data-agent-management/src/test/resources/supply-chain-evaluation/cases.json`，共 15 个手工核定样本：4 个订单风险、4 个库存、3 个供应商、3 个 SOP 检索、1 个无模型完整工作流。每个样本包含业务场景、目标业务 ID、期望结果和 `basis` 依据；期望值只存在于测试数据中，不进入生产实现。

```bash
cd data-agent-management
JAVA_HOME="C:/Program Files/Microsoft/jdk-17.0.12.7-hotspot" ../mvnw -DforkCount=0 -Dtest="SupplyChainOfflineEvaluationTest" test
```

测试通过真实的 `SupplyChainDemoData`、`SupplyChainQueryService`、`SupplyChainAgentTools`、`SupplyChainKnowledgeService`、`SupplyChainAnalysisService`、StateGraph 工作流、H2 任务仓库和事件仓库执行评测。模型配置边界不提供任何 CHAT 配置，因此规划和报告走真实的确定性降级路径；不提供 VectorStore，因此检索走真实的本地词法路径。评测不使用 Mock 返回预制通过结果。

指标定义：

- 订单风险：正类为 `riskLevel != LOW`，对象粒度为订单，全集为 4 个 `ORDER_RISK` 样本覆盖的演示订单。Precision = TP / (TP + FP)，Recall = TP / (TP + FN)，F1 = 2PR / (P + R)，Accuracy = (TP + TN) / 全集；分母为 0 时记 0，不产生 NaN。
- 事实一致性：`ORDER_RISK`、`INVENTORY`、`SUPPLIER`、`REPORT_FALLBACK` 样本的全部字段检查通过数 / 检查总数。报告样本检查摘要、风险订单 ID、SOP 引用、建议、执行计划、助手标记、空 `toolCalls`、校验次数、`MODEL_FAILED` / `FALLBACK_USED` / `MODEL_COMPLETED` / `TASK_COMPLETED` / `TASK_FAILED` 事件计数、任务状态以及持久化报告一致性。
- 词法检索：Top-K 固定为 3；有期望来源的查询命中任一来源即算 Hit@3，MRR 取首个命中排名倒数。当前 3 个查询的来源集合由真实词法匹配结果验证，不涉及 embedding、向量库或语义相似度。
- 失败必须使测试失败。失败输出包含样本 ID、字段名、期望值和实际值；`SupplyChainOfflineEvaluationTest` 还通过修改单个期望分数验证失败定位，并通过连续两次运行比较报告字节验证可复现性。

报告写入 `data-agent-management/target/chainpilot-evaluation/supply-chain-evaluation-report.md` 与 `supply-chain-evaluation-results.json`。`target/` 已被 Git 忽略。报告不包含时间戳或任务 ID。

评测边界：本轮验证的是确定性业务规则、演示数据、词法检索和无模型降级，不是真实模型生成质量评测；没有使用 LLM-as-a-judge，也没有验证真实 embedding/向量检索、真实付费模型或生产模型输出质量。真实的模型 Tool Calling 由本地假模型集成测试单独覆盖，二者结论不可互相替代。

## Agent 评测中心（持久化运行）

评测中心把确定性基线扩展为可在工作台反复使用的评测能力：

- 前端入口：`/agent-evaluation`（导航分组「Agent 工程」）。
- 案例目录：共 20 个带版本的案例，14 个离线确定性案例和 6 个真实工作流案例。离线案例来自 `src/main/resources/evaluation/supply-chain-cases.json`，真实工作流案例定义在 `EvaluationCatalog`。
- 运行 API：`GET /api/agent-evaluation/cases`、`GET /api/agent-evaluation/cases/{caseId}`、`GET /api/agent-evaluation/cases/{caseId}/history`、`GET /api/agent-evaluation/capabilities`、`GET /api/agent-evaluation/runs`、`GET /api/agent-evaluation/runs/{runId}`、`GET /api/agent-evaluation/metrics`、`GET /api/agent-evaluation/comparisons?baseRunId=&targetRunId=`、`POST /api/agent-evaluation/runs`。
- 启动请求体：`{"caseIds":["..."],"mode":"OFFLINE"|"AGENT","confirmAgentRun":true|false}`。一次运行只能包含同一案例集版本的案例；`OFFLINE` 单次最多 20 个，`AGENT` 单次最多 5 个。`AGENT` 必须携带 `confirmAgentRun=true`，服务端会拒绝未确认请求。
- 案例集版本：`OFFLINE` 使用 `evaluation/supply-chain-cases.json` 的 `datasetVersion`（当前 `1.0.0`），`AGENT` 使用工作流案例版本（当前 `workflow-1.0.0`）。每个案例结果额外持久化 `case_version` 与 `task_mode`；运行级 `datasetVersion` 表示该模式的案例集版本，不再混用离线数据集版本。
- `OFFLINE`：在当前进程内调用真实的 `SupplyChainQueryService` 和词典检索 `SupplyChainKnowledgeService`，不调用模型、不写入业务表，可安全重复运行。期望值来自手工核定数据集，实际值来自被测服务，不使用被测函数生成期望值。
- `AGENT`：必须由用户显式启动，提交真实供应链分析/决策任务，可能产生模型调用与向量化费用；进程内单实例锁保证同一时间只允许一个真实工作流评测，任务在 20 分钟后超时。查询运行详情时惰性刷新任务、报告、决策和事件结果，并按每个案例独立保存 `taskId` 与 `taskMode`。任务提交失败只影响该案例；评测不会执行沙箱，也不会修改真实业务数据。
- 状态口径：断言全部通过为 `PASSED`；断言不通过或真实任务失败为 `FAILED`；任务超时、应用重启中断、任务状态缺失为 `UNDETERMINED`。超时或被重启中断的运行整体标记为 `FAILED` 并写入 `failure_reason`，不会留下永久 `RUNNING` 记录。重启恢复只适用于单实例部署，不声称跨实例分布式控制。
- 运行与每案例结果持久化在 `chainpilot_evaluation_run`、`chainpilot_evaluation_case_result`。应用启动时通过 `EvaluationRunRepository` 创建表并做增量升级（新增 `failure_reason`、`case_version`、`task_mode` 列，索引、唯一约束和外键）；不删除或重建业务表。参考 DDL 与升级语句为 `data-agent-management/src/main/resources/sql/agent-evaluation.sql`。
- 指标按 `OFFLINE` 与 `AGENT` 分开返回（`byMode`），**不合并成一个 Agent/模型成功率**。只有状态为 `COMPLETED`、`completedAt` 存在、结果数量等于 `totalCases`、计数与结果状态一致且没有 `RUNNING` 结果的运行才计入；不满足的运行计入 `excludedIncompleteRuns`。案例级与运行级平均耗时、P95 分开统计，P95 少于 5 个样本时返回 `null`。RAG 指标只在存在检索样本时计算 HitRate@3 与 MRR，否则返回 `null`；不采集 Precision@K 时不展示。
- 运行对比只读取两次持久化运行：要求同一模式、同一案例集版本、完整结果和相同案例 ID 集合；逐案例断言 ID 集合也必须一致。不满足时返回 `comparable=false` 并列出缺失/多出案例或断言集合差异，不允许把不同测试集当作同一集直接比较。差异包括新增失败、已修复失败、持续失败、未变化通过、仍无法判定以及断言级期望/实际变化。
- 前端提供案例筛选与多选、模式警告与运行确认、运行历史轮询、分模式指标、运行对比、单案例断言/失败原因/版本/taskId、真实任务事件时间线、任务报告跳转、单案例历史以及“重新运行此案例”。重跑会创建新的运行并保留旧记录；`AGENT` 重跑继续要求费用确认。
- 离线案例没有 `taskId`，不会生成虚假任务、报告或事件链接；真实工作流结果通过真实 `taskId` 打开现有任务页和事件接口。

后端评测测试：

```bash
cd data-agent-management
JAVA_HOME="C:/Program Files/Microsoft/jdk-17.0.12.7-hotspot" ../mvnw -DforkCount=0 -Dtest="EvaluationCatalogTest,EvaluationMetricsCalculatorTest,AgentEvaluationServiceTest,AgentEvaluationControllerTest,EvaluationComparisonServiceTest,EvaluationRunRepositoryMigrationTest" test
```

MySQL 8 兼容性测试（需要 Docker，或显式指向本地专用 `chainpilot_mysql_it` 数据库）：

```bash
cd data-agent-management
JAVA_HOME="C:/Program Files/Microsoft/jdk-17.0.12.7-hotspot" ../mvnw -DforkCount=0 \
  -Dchainpilot.mysql.integration=true \
  -Dchainpilot.mysql.url='jdbc:mysql://127.0.0.1:33306/chainpilot_mysql_it?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC' \
  -Dchainpilot.mysql.username=root -Dchainpilot.mysql.password= \
  -Dtest='EvaluationMySqlPersistenceIT,EvaluationMySqlLegacyUpgradeIT' test
```

在 MySQL 8.0.43（本机 `127.0.0.1:33306`，专用 `chainpilot_mysql_it`）上实测：评测持久化 5/5、旧版评测表真实 MySQL 升级 1/1、既有供应链 MySQL IT 16/16，共 22/22 通过。旧表升级测试先创建初版评测表并插入旧运行/结果，再直接实例化 `EvaluationRunRepository` 走应用启动迁移逻辑，验证旧数据保留、新列可读写、唯一约束/外键/级联与重复启动幂等；不会对生产库执行 DROP 或数据清理。该临时 MySQL 实例仅在验证期间启动，验证后关闭并移除；不把 H2 结果当作 MySQL 结果。

评测中心不改变原有离线评测测试及其独立报告。真实工作流评测不会在测试或启动时自动执行；没有配置模型时，任务按现有确定性降级路径完成，但结果仍按真实断言评估。

## 真实模型端到端验收（可选，可能产生外部调用费用）

普通 `mvn test`、全量后端回归、离线评测和本地假模型测试都不会调用外部模型。真实模型验收使用独立类 `SupplyChainRealModelAcceptanceIT`，有两道门禁：类名以 `IT` 结尾，不在 Surefire 默认测试匹配内；类上还有 `@EnabledIfSystemProperty`，只有显式传入 `-Dchainpilot.real-model.acceptance=true` 才执行。未开启时显示为 skipped，不会发出任何请求。`@EnabledIfSystemProperty` 只读取 JVM 系统属性，因此仅设置 `CHAINPILOT_REAL_MODEL_ACCEPTANCE=true` 环境变量不会启用该测试类；启用参数必须出现在 Maven 命令行中。

### 复用的配置链路

验收不创建独立的模型客户端，而是复用现有业务链路：通过现有 `POST /api/model-config/add` 保存配置，`GET /api/model-config/list` 回读并检查 API Key 已脱敏，`POST /api/model-config/activate/{id}` 激活，再由 `ModelConfigDataService.getActiveConfigByType(CHAT)` → `AiModelRegistry` → `DynamicModelFactory` → Spring AI `ChatClient` 发起调用。`provider` 只是现有模型配置的显示/兼容字段，不决定请求协议；不会自动读取 Qoder 或 IDE 使用的编程模型配置。测试使用独立的内存 H2 数据库，不会修改本地演示数据库。

必需配置如下。系统属性优先于环境变量；环境变量适合放密钥，避免出现在命令行参数和 shell 历史中。

| 系统属性 | 环境变量 | 必需 | 说明 |
| --- | --- | --- | --- |
| `chainpilot.real-model.provider` | `CHAINPILOT_REAL_MODEL_PROVIDER` | 是 | 模型配置的厂商标识，例如 `openai-compatible`；不硬编码供应商。 |
| `chainpilot.real-model.base-url` | `CHAINPILOT_REAL_MODEL_BASE_URL` | 是 | OpenAI 兼容服务的绝对 `http(s)` 地址，例如 `https://<host>`；不要把真实密钥拼进 URL。 |
| `chainpilot.real-model.model-name` | `CHAINPILOT_REAL_MODEL_MODEL_NAME` | 是 | 服务端模型名。 |
| `chainpilot.real-model.api-key` | `CHAINPILOT_REAL_MODEL_API_KEY` | 是 | 只通过环境变量或系统属性传入，不写入源码、测试资源、README、日志或验收报告。 |
| `chainpilot.real-model.completions-path` | `CHAINPILOT_REAL_MODEL_COMPLETIONS_PATH` | 否 | 自建兼容服务需要时填写；默认沿用现有 OpenAI 兼容路径。 |
| `chainpilot.real-model.max-tokens` | `CHAINPILOT_REAL_MODEL_MAX_TOKENS` | 否 | 默认 2048，范围 1–2048。DeepSeek 思考模式等推理模型的 `reasoning_content` 也计入该预算；预算过小可能出现 `finish_reason=length` 且可见内容为空。 |
| `chainpilot.real-model.timeout-seconds` | `CHAINPILOT_REAL_MODEL_TIMEOUT_SECONDS` | 否 | 默认 180，范围 30–600；用于测试等待任务终态，不是生产任务的硬超时。 |
| `chainpilot.real-model.max-model-calls` | `CHAINPILOT_REAL_MODEL_MAX_MODEL_CALLS` | 否 | 默认 12，范围 1–50；每个验收场景的模型请求硬上限。 |

运行方式：

```bash
cd data-agent-management
CHAINPILOT_REAL_MODEL_PROVIDER="<provider-label>" \
CHAINPILOT_REAL_MODEL_BASE_URL="https://<host>" \
CHAINPILOT_REAL_MODEL_MODEL_NAME="<model-name>" \
CHAINPILOT_REAL_MODEL_API_KEY="<api-key>" \
JAVA_HOME="C:/Program Files/Microsoft/jdk-17.0.12.7-hotspot" \
../mvnw -DforkCount=0 -Dchainpilot.real-model.acceptance=true \
  -Dtest="SupplyChainRealModelAcceptanceIT" test
```

PowerShell 请先用 `$env:CHAINPILOT_REAL_MODEL_* = "..."` 设置环境变量，再执行同样的 Maven 命令。不要把真实 API Key 作为 `-D` 参数传入。

启用后如果缺少必需配置，`@BeforeAll` 会失败并逐项列出缺失或非法属性，末尾明确写出 `No external model request was made.`。该失败不会回退到本地假模型，也不会把跳过或降级冒充成验收成功。

### 四个验收场景

1. **基础模型调用**：直接通过激活配置的 `ChatClient` 发起一次请求，要求非空回答；至少产生一次 `gen_ai.client.operation` 和一次 `http.client.requests` 观测；检查观测属性中没有 API Key 或提示词原文。
2. **真实报告生成**：提交标准供应链问题，要求任务 `COMPLETED`，`plannerAssisted=true`、`modelAssisted=true`，`PLANNING` 和 `GENERATING_REPORT` 各恰好一次 `MODEL_COMPLETED`，没有 `MODEL_FAILED` / `FALLBACK_USED`；摘要、执行计划、建议和引用非空；风险订单严格为 `ORD-1001`、`ORD-1002`、`ORD-1004`，摘要不得出现演示数据之外的订单号；报告可从历史接口重新读取；至少两次模型观测和两次 HTTP 请求。
3. **真实模型 Tool Calling**：提示模型先调用 `findInventory` 查询 `SKU-SENSOR-002`，再依据工具结果用阿拉伯数字回答可承诺数量。测试要求 `report.toolCalls` 包含 `findInventory`，同时存在 `model-initiated tool` 摘要的 `TOOL_STARTED` / `TOOL_COMPLETED` 事件，工具结果非空，后续模型请求中出现与先前 `tool_call_id` 匹配的工具响应，摘要包含工具返回的 `25`（`SupplyChainDemoData` 中该 SKU 的 ATP），至少一次成功的 `spring.ai.tool` 观测，且模型请求数至少为 3（规划、发起工具调用、工具结果后的后续回合）。模型未发起工具调用、服务不支持原生 Tool Calling 或工具失败时，测试会失败并明确写出 `Native Tool Calling is NOT verified`；不会伪造工具名或工具结果。
4. **真实 HTTP 失败与降级**：不使用外部模型，而是在回环地址启动一个对所有请求返回 HTTP 401 的本地端点，确认规划器和报告调用确实到达该端点（至少两次请求），任务仍 `COMPLETED`，但 `plannerAssisted=false`、`modelAssisted=false`、`toolCalls` 为空，摘要为确定性文本“发现 3 个存在风险的订单”，事件恰好两次 `MODEL_FAILED`、两次 `FALLBACK_USED`、零次 `MODEL_COMPLETED`。4xx 在 Spring AI 默认策略中不可重试；若底层策略重试传输错误或 5xx，每次尝试仍受模型调用上限约束。该场景验证降级语义，不代表已经验证真实供应商的每一种鉴权、配额或 5xx 行为。

### 如何判读证据

真实运行完成全部四个场景后，证据写入 `data-agent-management/target/chainpilot-real-model-acceptance/real-model-acceptance-evidence.json` 与同名 `.md`。只运行部分测试方法时写入带 `.partial` 后缀的文件，并在报告中标明“不能视为完整验收”；场景集有失败时 `complete=false`，避免把部分或有失败的运行误当成完整验收。

- **请求是否真的到达配置端点**：查看 `gen_ai.client.operation` 与子级 `http.client.requests` 的存在和次数、非空回答，以及验收证据中的 `basic.*`、`observability.*`。这些是客户端观测；要独立确认请求到达供应商，仍需查看供应商控制台、网关请求日志或配额用量。本地观测不能证明 Langfuse 外部导出成功。
- **助手标记是否真实**：`plannerAssisted` / `modelAssisted` 只有模型返回非空内容时才为 `true`；失败或空响应必须同时出现 `MODEL_FAILED` 或 `FALLBACK_USED`。不要用任务 `COMPLETED` 或 HTTP 200 代替模型成功证据。
- **工具调用是否真实发生**：`report.toolCalls` 只应包含模型实际发起、经 Spring AI `DefaultToolCallingManager` 执行并留下 `model-initiated tool` 事件的工具。Workflow 直接调用的 `findInventoryForWorkflow` 等不会进入 `toolCalls`，也不会产生 `spring.ai.tool` 观测。摘要中的 `25` 必须来自工具查询，而不是提示词或固定摘要。
- **最终报告是否使用业务数据**：风险订单 ID、风险等级、库存 ATP、供应商风险和建议来自确定性的 `SupplyChainQueryService` / `SupplyChainAgentTools`，不因模型成功而改变；模型负责生成本次运行的摘要和执行计划。测试同时检查报告持久化、历史接口可读、引用存在和未知订单号，避免只检查“输出非空”。
- **失败是否如实降级**：场景 4 的本地 401 端点产生真实的模型边界异常，事件、标记、摘要和 `toolCalls` 必须一致。不要把规则降级摘要描述为模型生成，也不要把本地 401 写成外部真实模型失败。
- **观测是否安全**：测试断言观测属性不包含 API Key、标准问题标记或基础连通性提示词。验收证据只记录计数、布尔值、订单 ID、工具名、端点 `scheme://host:port`、耗时上限，以及不含正文的模型协议诊断（`finish_reason`、token 计数、工具调用/结果计数、结果回传匹配和空值布尔），不记录提示词、完整回答、`reasoning_content`、工具参数或结果。

**边界与费用**：每个测试方法有 10 分钟 JUnit 超时；基础、报告、工具调用三个外部场景各自受 `max-model-calls` 上限约束（默认最多 12 次），场景 4 只访问本地端点，不产生付费调用；任务轮询受 `timeout-seconds` 限制，单次模型 HTTP 读取沿用现有 `DynamicModelFactory` 的 3 分钟超时。实际账单取决于供应商计价、重试和模型输出长度，默认值只是控制手段，不是费用上限承诺。不要把该验收接入 CI 默认流程、应用启动或配置加载；需要时显式运行，关闭时不传 `-Dchainpilot.real-model.acceptance=true` 即可。

**当前状态（2026-10-20）**：已使用 DeepSeek `deepseek-flash` 完成四场景真实验收，配置为 `provider=custom`、Base URL `https://api.deepseek.com`、Completion Path `/chat/completions`、`max-tokens=2048`、`max-model-calls=12`。实际结果为 `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`，测试类耗时 24.97 s。基础调用、模型报告生成、原生 Tool Calling 和本地 401 降级均通过；报告场景中 `plannerAssisted=true`、`modelAssisted=true`，`PLANNING` 与 `GENERATING_REPORT` 各一次 `MODEL_COMPLETED`，无 `MODEL_FAILED` / `FALLBACK_USED`；Tool Calling 场景中 `findInventory` 由模型发起，工具结果非空，后续请求中的工具响应与先前 `tool_call_id` 匹配，最终摘要包含工具返回的 ATP `25`。证据文件为 `data-agent-management/target/chainpilot-real-model-acceptance/real-model-acceptance-evidence.json`，其中 `complete=true`、`fullScope=true`。本次 DeepSeek 思考模式保持默认开启，观测到的 `finish_reason` 为 `STOP` 或 `TOOL_CALLS`，未出现 `length`，Tool Calling 闭环完成，因此未添加 `thinking` 参数或修改生产请求选项。该结论只代表本次 DeepSeek 配置与本地环境，不代表其他供应商、生产 Langfuse 导出链路或其他模型已经验证。

## 验证持久化

完成一次分析后记录任务 ID。重启后端并刷新页面，右侧“最近分析”仍会列出该任务，点击即可重新打开报告；也可以调用 `GET /api/supply-chain/analysis/tasks` 查看近期任务，再通过 `GET /api/supply-chain/analysis/tasks/{taskId}` 与 `GET /api/supply-chain/analysis/tasks/{taskId}/report` 读取状态和报告。H2 演示数据位于应用工作目录的 `data/nl2sql_database.mv.db`，该文件已被 Git 忽略。若服务在 Graph 执行中关闭，重启后该未完成任务会记录为 `FAILED`，步骤为 `INTERRUPTED_BY_RESTART`，不会伪装成已恢复执行。

## MySQL 持久化与 HTTP 链路验证（可选）

普通演示和 `mvn test` 使用 H2；H2 测试通过不等于 MySQL 已验证。真实 MySQL 验证有两个显式启用的 IT：

- `SupplyChainMySqlPersistenceIT`：直接验证 Repository、DDL、约束、历史分页、事件和连接重建。
- `SupplyChainMySqlHttpIntegrationIT`：用随机端口启动生产入口 `DataAgentApplication`，真实经过 HTTP Controller、异步 Workflow/StateGraph、业务工具、报告生成、Repository 和 MySQL，再通过 HTTP 与独立 JDBC 核对任务、报告、历史和执行事件。

两个类默认不执行，必须传入 `-Dchainpilot.mysql.integration=true`；只接受 Testcontainers `mysql:8.0` 或回环地址上的专用 `chainpilot_mysql_it` 数据库，不会回退 H2，也不会操作开发库。Docker/Testcontainers 命令、连接本机 MySQL 的参数和隔离规则见 README 的“MySQL 集成测试（显式启用，含完整 HTTP 链路）”一节。

```powershell
Push-Location data-agent-management
..\mvnw.cmd -Pintegration -DforkCount=0 -Dchainpilot.mysql.integration=true `
  -Dit.test="SupplyChainMySql*IT" `
  test-compile failsafe:integration-test failsafe:verify
Pop-Location
```

HTTP 测试使用 30 秒上限轮询到终态，验证任务 ID、终态、`updatedAt`、报告可用性、历史筛选/分页头、报告字段和事件序号；报告风险订单必须等于确定性 `SupplyChainQueryService.findAtRiskOrders()` 输出，`plannerAssisted=false`、`modelAssisted=false`、`toolCalls` 为空，事件中恰有 `MODEL_FAILED` 和 `FALLBACK_USED` 各两次且没有 `MODEL_COMPLETED`。`AiModelRegistry` 仅在模型边界被替换为抛出异常的测试替身，No real model request is made；数据库确认没有 `model_config` 表，不导入开发环境模型配置或 API Key。测试还使用新的数据源和 Repository 读取已提交结果，但这只是连接重建，不是进程级重启验收。

**当前状态（2026-10-20 稳定性收尾）**：已用临时本地 MySQL 8.0.43（InnoDB、utf8mb4，仅回环监听）在 `chainpilot_mysql_it` 专用库上重新运行两个 IT，HTTP 6/6（含新增 `decisionComparisonSaveProposalCreatesOneLinkedPlanOnlyAfterConfirmationInMySql`）、持久化 10/10、合并 16/16 通过，`BUILD SUCCESS`；同轮全量 H2 回归 1882/1882 通过。新增 MySQL 用例覆盖：DECISION 任务通过 `compareDisposalScenarios` 生成 3 个候选；保存候选提案创建后、确认前没有方案；错误内容哈希返回 400 且仍无方案；确认后恰好生成 1 个 `PENDING_CONFIRMATION` 方案，方案任务归属提案任务、持有独立 simulation 副本且补货数量与模拟指标同来源候选一致、候选 `linkedPlanId` 正确；重复确认返回同一 `planId`，不新增方案或提案；随后直接 JDBC 核对提案、方案与候选行，并用新数据源和新 Repository 重读。HTTP IT 其余用例继续覆盖方案对比及候选的 MySQL 持久化、历史、保存为方案和关联；持久化 IT 继续覆盖 DECISION 任务结果、提案确认边界与级联删除、执行表约束与 JSON 读写、连接重建、重复键回滚、失败重试和分页。另执行过真实进程级重启：创建方案与 `SUCCEEDED` 执行后停止并重启 Spring Boot，执行详情、5 个步骤、草稿和历史仍可查询，幂等重放返回原记录（早前沙箱增量，本轮未重复）。本轮未调用真实模型。当前环境没有 Docker，因此 Testcontainers 路径未执行；未验证其它 MySQL 版本、多实例并发、生产网络/TLS、HA/主从和最小权限账号。验证后临时 MySQL 实例已停止。

## 五个业务验收场景

自动化测试覆盖正常订单、1 天延期、库存不足、中风险供应商、延期加缺货加高风险供应商。测试同时检查风险分数、等级、证据文字、建议动作及匹配的 SOP 来源。测试使用模拟数据和规则降级，不会调用付费模型。

HTTP 集成测试 `SupplyChainAnalysisHttpIntegrationTest` 另外覆盖完整链路：异步 HTTP 提交、轮询到终态、数据库持久化、状态筛选、分页边界、报告任务 ID 校验、非法参数、执行事件查询、任务间事件隔离以及无事件任务的空轨迹。该测试使用随机端口和独立内存 H2，仅在模型注册边界注入故障替身，真实执行 Controller、Workflow、Repository 和数据库，不访问外部模型服务。MySQL 下的同类完整 HTTP 链路由显式启用的 `SupplyChainMySqlHttpIntegrationIT` 验证，见上文。`SupplyChainEventRepositoryTest` 使用 H2 验证事件顺序、任务隔离、空结果和重建 Repository 后的事件持久化；`SupplyChainWorkflowServiceTest` 验证节点成功、节点失败、模型降级对应的真实事件，且不改变原有任务终态与报告逻辑。

## 环境配置要点

- 本地 H2 使用 `CHAINPILOT_H2_USERNAME` / `CHAINPILOT_H2_PASSWORD`，缺省为 `sa` 和空密码；只供本地演示，勿暴露 H2 Console。
- MySQL profile 要求 `DATA_AGENT_DATASOURCE_PASSWORD`。
- Docker Compose 要求提供 `CHAINPILOT_MYSQL_PASSWORD` 与 `CHAINPILOT_POSTGRES_PASSWORD`。
- DataAgent 模型配置密钥写入配置数据库；数据库文件及其备份应限制访问，并按部署要求轮换密钥。
