# ChainPilot 学习指南

## 1. 项目定位与版本

ChainPilot 是在开源 DataAgent 上改造的供应链风险分析演示系统。它将原有的数据分析平台能力与订单、库存、供应商履约风险示例结合，展示 Java 后端、Spring Boot、Spring AI Alibaba、StateGraph、工具调用和 RAG 的组合方式。

当前仓库以 Java 17、Spring Boot 3.4.8、Spring AI 1.1.2 和 Spring AI Alibaba 1.1.2.2 为基础。供应链业务数据来自代码中的本地模拟数据，不是 ERP 或数据库实时数据。开发配置使用 H2，服务端口为 8065。

## 2. 原 DataAgent 架构与改造边界

DataAgent 原本是一个自然语言数据分析应用，已有管理端 API、数据源及 Schema 管理、NL2SQL 工作流、语义模型、查询执行、报表与可视化、模型配置、MCP、知识检索、Python 代码执行以及前端。后端代码主要位于 `data-agent-management`，前端位于 `data-agent-frontend-nuxt`，项目使用 Maven 多模块构建。

ChainPilot 保留了这些既有模块，没有用供应链演示替换整个产品。改造集中在：项目名称和说明、管理端中的 `supplychain` 业务包、供应链 SOP 资源、专项测试，以及 README 和模块文档。原 DataAgent 的数据分析、数据库访问和工作流能力仍留在仓库中。

模块职责概览：

| 模块 | 职责 | ChainPilot 变化 |
|---|---|---|
| `data-agent-management` | Spring Boot 管理端、原数据分析后端与 API | 新增供应链领域模型、查询、Agent 工具、Graph 工作流、分析 API |
| `data-agent-frontend-nuxt` | 原 DataAgent Web UI | 新增供应链分析页并调用供应链查询、异步任务及报告 API；原管理和问答页面保留 |
| `docker-file` | 容器构建文件 | 后端制品名称与启动目标适配 ChainPilot |
| 根目录 Maven / README | 多模块构建和项目入口说明 | 项目名称、构建说明与产品描述更新 |

## 3. 供应链包的核心类

路径前缀：`data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/supplychain/`

| 类 | 职责 |
|---|---|
| `controller/SupplyChainController.java` | 提供订单、库存、供应商和单个供应商风险的查询 API；只负责 HTTP 参数与服务调用 |
| `controller/SupplyChainAnalysisController.java` | 提供同步兼容接口、异步任务提交、状态、任务历史（状态筛选与分页）及报告查询；同步接口使用 Reactor bounded elastic，异步任务由有界线程池执行 |
| `SupplyChainAsyncConfiguration.java` | 配置有限线程数和有界队列的异步分析执行器 |
| `model/SupplyChainOrder.java`、`InventoryPosition.java`、`SupplierRiskProfile.java` | 订单、库存和供应商履约信息的不可变记录 |
| `model/OrderRiskAssessment.java` | 单个订单风险等级、分数、库存短缺、风险因素和建议 |
| `model/RiskAnalysisReport.java` | 面向调用方的报告结构，包含计划、风险订单、工具名称、SOP 引用和辅助状态 |
| `model/SupplyChainTaskStatus.java` | 任务状态、当前步骤、校验重试次数、错误信息和更新时间 |
| `model/SupplyChainTaskSummary.java` | 任务历史条目，包含问题、状态、当前步骤、更新时间和报告是否可用 |
| `model/SupplyChainTaskPage.java` | 历史查询的分页结果，包含当前页条目、页码、每页条数、总数和总页数 |
| `model/SupplyChainTaskState.java` | 工作流实际写入的四种状态常量：`ACCEPTED`、`RUNNING`、`COMPLETED`、`FAILED`；历史筛选复用该集合 |
| `repository/SupplyChainTaskRepository.java` | 使用 `JdbcTemplate` 持久化任务与报告；在 SQL 中按状态过滤、排序、`LIMIT/OFFSET` 分页和计数，并清理过期终态任务、在启动时标记中断任务 |
| `repository/SupplyChainDemoData.java` | 提供固定的本地演示订单、库存及供应商数据 |
| `service/SupplyChainQueryService.java` | 业务查询、关联模拟数据并计算订单风险分数与建议 |
| `service/SupplyChainAgentTools.java` | 用 `@Tool` 暴露订单、库存、供应商查询；记录工具开始、成功和异常日志 |
| `service/SupplyChainAnalysisService.java` | 生成计划、检索 SOP、调用已配置 ChatClient 生成摘要；模型不可用时返回规则摘要 |
| `service/SupplyChainKnowledgeService.java` | 检索供应链 SOP，输出带来源的引用；向量检索不可用时使用词项匹配降级 |
| `service/SupplyChainWorkflowService.java` | 构造并运行 StateGraph，更新任务状态，校验证据，执行最多一次数据校验重试并生成报告 |

## 4. 一次分析请求的完整调用链

1. 演示页面 `POST /api/supply-chain/analysis/tasks`，JSON 请求体为 `{"question":"分析今天有哪些订单可能延期"}`。
2. `RiskAnalysisRequest` 通过 `@NotBlank` 拒绝空白问题；`SupplyChainWorkflowService.submit` 建立 UUID 任务并立即返回 `ACCEPTED / QUEUED`。
3. `SupplyChainAsyncConfiguration` 提供 2 个核心线程、最多 4 个工作线程和 20 个排队任务的有界执行器；队列饱和时拒绝新任务。工作线程以 taskId 作为 StateGraph threadId 启动图。原 `POST /api/supply-chain/analysis` 同步接口仍保留。
4. `plan` 节点调用 `SupplyChainAnalysisService.createPlan`。有可用模型时取得简短计划；模型配置缺失或调用失败时采用固定计划，并在报告中标记 `plannerAssisted=false`。
5. `orders` 节点通过 `SupplyChainAgentTools.findAtRiskOrders` 查询并计算订单风险；随后 `inventory` 和 `suppliers` 节点分别通过工具取得模拟库存和供应商信息。各节点推进任务步骤，并在实际执行位置写入节点事件；工具调用边界写入开始、完成或失败事件。
6. `validate` 检查订单分数范围、风险因素，以及每个风险订单是否能匹配库存 SKU 与供应商。若失败则进入 `retry`，重新读取业务快照并再校验一次；超过一次仍失败会进入 `FAILED / VALIDATION_FAILED`，不会继续生成报告。
7. 通过校验后，`report` 节点将 Graph 状态中的对象转换为领域类型，调用 `SupplyChainAnalysisService.analyze`。该服务根据订单风险生成结构化建议，检索 SOP 来源，并尝试用配置的 ChatClient 生成摘要。模型失败时使用确定性摘要和已校验的业务数据。
8. 报告序列化为 Graph 状态字符串，恢复成 `RiskAnalysisReport` 并与 `COMPLETED / DONE` 状态一并写入数据库。前端轮询 `GET /api/supply-chain/analysis/tasks/{taskId}` 展示步骤，完成后从 `GET /api/supply-chain/analysis/tasks/{taskId}/report` 读取报告；“最近分析”通过 `GET /api/supply-chain/analysis/tasks` 恢复刷新或重启后的任务列表。

历史接口在数据库层完成筛选和分页：`status` 可选值来自 `SupplyChainTaskState`，`page` 从 1 开始，`pageSize` 范围 1–50 且默认 10；旧参数 `limit` 是分页大小别名，二者同时出现时 `pageSize` 优先。响应体保持数组以兼容现有前端，`X-Total-Count`、`X-Page`、`X-Page-Size`、`X-Total-Pages` 响应头提供总数和页码信息。查询固定使用 `updated_at DESC, task_id DESC`，报告可用性通过与列表同一条 SQL 的 `report_json IS NOT NULL` 计算，避免逐条查询；页码超出末页返回空数组但保留真实总数。非法页码、分页大小或状态由 Controller 转换为 HTTP 400。

执行轨迹由 `SupplyChainEventRecorder` 在真实执行位置写入 `chainpilot_task_event`，再由 `GET /api/supply-chain/analysis/tasks/{taskId}/events` 按任务内 `sequence` 升序返回。事件覆盖任务排队/开始/完成/失败、节点开始/完成/失败、工具开始/完成/失败、模型完成/失败以及真实发生的确定性降级。模型未配置、返回空内容或调用异常时会记录失败或降级事件，并把报告中的 `plannerAssisted` / `modelAssisted` 保持为 `false`；不会把降级结果标成模型生成。事件只保存计数、固定摘要和异常类名，不保存问题原文、提示词、模型输出、工具参数或凭据。事件写入失败只记录告警日志，不改变分析结果、任务终态或报告。

订单、库存和供应商属于模拟数据；本流程不会对供应链系统执行写操作。计划文本目前用于展示规划结果，Graph 节点执行顺序是固定的“计划—订单—库存—供应商—校验—报告”，并非 LLM 可自由改写的动态图。

## 5. 风险计算与可追溯性

`SupplyChainQueryService.assess` 基于订单的请求/预计交期、订单状态、SKU 的可承诺库存以及关联供应商风险计算分数。库存短缺量为 `max(0, 订单数量 - 可承诺库存)`；缺失库存时按整单短缺处理。已交付订单直接标为 LOW、0 分。

非已交付订单的分数由可解释规则累加并封顶 100：订单状态为 DELAYED 时加 30 分；预计交付日期晚于请求交期时加 `max(30, 20 + min(20, 延期天数 × 5))` 分（与 DELAYED 分支不重复累加）；库存短缺按 `30 + ceil(10 × 短缺量 / 订单数量)` 加分，最多 40 分；中风险供应商加 30 分、高风险供应商加 40 分；预计交付日期已过再加 10 分。分数达到 60 为 HIGH、达到 30 为 MEDIUM，否则为 LOW。风险因素和建议随 `OrderRiskAssessment` 一起返回，因此调用方能查看主要触发依据。

风险证据由查询服务对 `SupplyChainDemoData` 的真实读取结果产生，模型只参与自然语言摘要，不决定订单集合、分数或风险等级。无模型配置时规则摘要仍可用，报告会体现模型未参与。执行计划是否由模型生成也由 `plannerAssisted` 单独标记。

## 6. Agent、工具、工作流和 RAG 的关系

- **Planner**：`createPlan` 生成可读的任务步骤；当前不决定图的动态路由。
- **Graph 节点**：`SupplyChainWorkflowService` 维护业务执行顺序、状态推进、校验与有限重试。
- **工具**：`SupplyChainAgentTools` 把查询服务暴露为 Spring AI 工具，并在 `@Tool` 方法内部写入实际调用的开始、完成或失败事件。Graph 节点显式调用这些工具；摘要模型请求也可通过 `.tools(agentTools)` 使用它们。
- **任务状态**：任务和报告保存在配置的 SQL 数据库中，H2 演示配置使用文件数据库，刷新页面或单机重启后可恢复已完成记录；实例启动不再批量改写非终态任务，只有 `owner_instance_id` 非空且数据库租约（`lease_expires_at`，以 DB 时钟判定）已过期的任务才会被 `TaskRecoveryCoordinator` 标记为 `FAILED / LEASE_EXPIRED`。最近任务通过 `GET /api/supply-chain/analysis/tasks` 查询。
- **SOP 检索**：`SupplyChainKnowledgeService` 检索 `src/main/resources/knowledge/supply-chain/` 中的履约规则和 SOP，引用保留来源标识。向量存储不可用时词项检索作为降级路径。
- **报告生成**：确定性业务字段由查询数据和规则产生；可选模型只负责摘要文字。建议取自订单风险规则，引用来自知识检索。

## 7. API 与本地运行

开发配置下服务端口为 8065，`application-h2.yml` 使用文件 H2 保存 DataAgent 自身的应用数据与 ChainPilot 任务记录；供应链示例数据仍由 `SupplyChainDemoData` 提供。

- `GET /api/supply-chain/orders`：订单风险评估，可按现有 Controller 参数筛选。
- `GET /api/supply-chain/inventory`：库存查询，可传 SKU，响应包含可承诺库存 `availableToPromise`。
- `GET /api/supply-chain/suppliers`：供应商列表。
- `GET /api/supply-chain/suppliers/{supplierId}/risk`：指定供应商风险。
- `POST /api/supply-chain/analysis`：执行一次风险分析。
- `POST /api/supply-chain/analysis/tasks`：异步提交分析，立即返回 `ACCEPTED / QUEUED` 任务。
- `GET /api/supply-chain/analysis/tasks?status=COMPLETED&page=1&pageSize=10`：按状态筛选并分页查询任务历史；`limit` 为兼容旧页面的分页大小别名，分页元数据位于响应头。
- `GET /api/supply-chain/analysis/tasks/{taskId}`：查询任务状态与当前步骤。
- `GET /api/supply-chain/analysis/tasks/{taskId}/report`：读取已完成任务的报告。
- `GET /api/supply-chain/analysis/tasks/{taskId}/events`：按任务内稳定顺序读取执行事件；未知任务返回 404，存在但无事件返回 `200 []`。

使用 Maven Wrapper 在项目根目录构建：`./mvnw -pl data-agent-management -am test`。本地启动可使用 `./mvnw -pl data-agent-management spring-boot:run -Dspring-boot.run.profiles=h2`。如需真实模型，应通过原项目的模型配置流程或环境/外部配置提供凭据；不要将 API Key 写入源码。`SupplyChainAnalysisHttpIntegrationTest` 使用随机端口和内存 H2 真实经过 HTTP、Controller、Workflow、Repository 和数据库，验证异步提交、轮询、历史筛选/分页、报告读取、事件查询、任务隔离和未知任务 404；只在模型注册边界使用故障替身，确保自动化测试不会调用外部模型。`SupplyChainEventRepositoryTest` 验证事件顺序、隔离与持久化，`SupplyChainWorkflowServiceTest` 验证节点/工具成功与失败事件，并确认失败任务不会产生 `TASK_COMPLETED`。

## 8. 上游已有能力与 ChainPilot 改造

**DataAgent 上游已有**：Spring Boot 多模块结构、NL2SQL 与工作流、数据源/Schema 管理、模型配置注册、Spring AI Alibaba 集成、图工作流依赖、知识检索基础设施、MCP、Python 沙箱、原 Web UI 和大量原项目测试。

**ChainPilot 新增或修改**：供应链领域记录和固定样例数据、订单/库存/供应商查询、风险评分、`@Tool` 查询工具、供应链 StateGraph、任务状态与历史查询、SOP 文档及引用、规则摘要与可选模型摘要、供应链专项测试、品牌和学习文档。

可以用 `git diff` 和 `git status` 查看实际改动；供应链新代码集中在上述 `supplychain` 包，不代表原项目已有这些业务能力。

## 9. 当前限制与未实现事项

- 数据是少量固定模拟记录，没有真实 ERP、采购、仓储或物流集成。
- 任务状态和报告持久化在单机配置的 SQL 数据库中；终态任务最多保留 24 小时和 1000 条，未完成任务不会因容量清理而被驱逐。服务重启会保留已完成记录，但无法恢复执行中的 Graph。
- 异步执行队列有明确容量，队列饱和会拒绝新分析；当前不是持久化作业队列，进程突然退出时进行中的任务会丢失。
- 计划由模型生成时也不会改变固定 Graph 拓扑；当前不是多个专职 Agent 之间的协作系统。
- 未配置模型时 Planner 和摘要均降级运行。需要检查运行时模型设置，不能仅凭依赖存在推断模型已接通。
- SOP 检索在向量服务不可用时降级为词项匹配；当前引用来自本地演示文档，不是企业知识库。
- 风险分数是演示规则，不是经历史数据校准的预测模型；阈值与权重不能解释为生产风险概率。
- 供应链 API 的认证与原 DataAgent 整体安全配置一致；上线前需要依据部署环境配置访问控制。
- 供应链页面覆盖订单、库存、供应商和分析报告演示；还没有订单编辑、审批或真实系统操作界面。

## 10. Java Agent 实习面试技术总结

可以这样介绍：

> 我在 DataAgent 的 Spring Boot / Spring AI Alibaba 项目基础上实现了一个供应链风险分析链路。后端用领域模型承载订单、库存和供应商数据，通过查询服务计算可追溯的延期、库存短缺和供应商风险因素；再把查询能力注册为 Spring AI 工具，并使用 StateGraph 编排计划、数据查询、校验、有限重试和报告生成。报告事实来自模拟业务数据，模型只负责可选的计划和摘要；没有模型时可以走确定性降级路径。SOP 检索返回来源引用，任务状态与历史 API 展示工作流步骤并可恢复持久化报告。当前重点是可学习的端到端原型，供应链业务数据仍为模拟数据。

面试时应主动说明：没有真实 ERP 集成、没有生产级持久任务队列，也没有用模型分数冒充预测准确率。可进一步讨论工具参数约束、工作流幂等、多实例任务抢占、风险规则校准、模型输出校验、RAG 召回评估和可观测性设计。

## 11. 建议的深入阅读顺序

1. `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/supplychain/controller/SupplyChainAnalysisController.java`
2. `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/supplychain/service/SupplyChainWorkflowService.java`
3. `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/supplychain/service/SupplyChainQueryService.java`
4. `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/supplychain/service/SupplyChainAgentTools.java`
5. `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/supplychain/service/SupplyChainAnalysisService.java`

