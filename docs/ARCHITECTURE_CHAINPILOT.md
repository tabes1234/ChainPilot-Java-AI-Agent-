# ChainPilot 架构说明

本文描述当前仓库实际实现的 ChainPilot 架构。基础应用骨架来自
[Spring AI Alibaba DataAgent](https://github.com/spring-ai-alibaba/DataAgent)；
供应链、评测、反馈、观测和 Next.js 工作台是本地新增或实质改造部分。贡献边界见
[UPSTREAM_AND_CONTRIBUTION.md](UPSTREAM_AND_CONTRIBUTION.md)。

## 1. 总体架构

```mermaid
flowchart TB
    subgraph Browser["浏览器"]
        Next["Next.js 16 工作台<br/>首页 / 分析 / 决策 / 处置 / 评测 / 观测 / 系统"]
    end

    subgraph Backend["Spring Boot 3.4.8 / Java 17"]
        API["REST Controllers<br/>/api/supply-chain, /api/agent-evaluation,<br/>/api/model-config, /api/supply-chain/observability"]
        Upstream["上游 DataAgent 基础层<br/>Agent / Chat / 数据源 / 知识库 / 模型配置 / StateGraph"]
        SC["供应链领域层<br/>订单·库存·供应商·风险规则·决策·沙箱·模拟"]
        Eval["评测领域层<br/>案例目录·运行·指标·反馈·草案"]
        Obs["观测与诊断<br/>任务事件·诊断·运行详情"]
        Ready["模型就绪与安全门禁<br/>四态检查·错误分类·密钥脱敏"]
    end

    subgraph Model["模型边界（可选）"]
        Chat["OpenAI 兼容 CHAT 模型"]
        Embed["EMBEDDING（可选）"]
        Vector["VectorStore（可选）"]
    end

    subgraph Data["持久化"]
        H2[("H2 文件库<br/>本地演示")]
        MySQL[("MySQL 8<br/>可选/集成测试")]
    end

    Next -->|"同源 /api 与 /uploads 代理"| API
    API --> Upstream
    API --> SC
    API --> Eval
    API --> Obs
    API --> Ready
    SC --> Upstream
    SC --> Chat
    SC --> Embed
    Upstream --> Vector
    Ready --> Chat
    Ready --> Embed
    Upstream --> H2
    SC --> H2
    Eval --> H2
    Obs --> H2
    H2 -.->|"同一 JdbcTemplate 抽象"| MySQL
```

- Next.js 只通过 HTTP API 访问后端；浏览器同源代理由
  `data-agent-frontend-next/next.config.ts` 配置。
- Java 17、Spring Boot 3.4.8、Spring AI 1.1.2、Spring AI Alibaba 1.1.2.2 是当前构建版本。
- H2 是本地演示默认 profile；MySQL 用于隔离集成测试和可选部署，不要求演示时启动。
- CHAT/EMBEDDING 是可选外部依赖；没有模型时确定性路径仍然可以运行。

## 2. 上游基础与 ChainPilot 扩展边界

```mermaid
flowchart LR
    subgraph Upstream["上游 DataAgent / Spring AI Alibaba 基础（Apache-2.0）"]
        U1["应用入口与 Web 配置"]
        U2["Agent / Chat / 知识库 / 数据源管理"]
        U3["模型配置、DynamicModelFactory、AiModelRegistry"]
        U4["StateGraph / Workflow 基础能力"]
        U5["原 Nuxt 前端与文档"]
    end

    subgraph ChainPilot["ChainPilot 新增或实质改造"]
        C1["供应链领域模型与模拟数据"]
        C2["确定性风险规则 + 结构化风险证据"]
        C3["决策提案 / 处置方案 / 沙箱执行"]
        C4["情景推演 / 基线 / 多候选比较"]
        C5["Evaluation Center + 反馈转案例"]
        C6["运行观测与故障诊断"]
        C7["模型就绪门禁 / 错误分类 / 脱敏"]
        C8["Next.js 工作台"]
    end

    Upstream --> ChainPilot
    C1 -. "复用" .-> U2
    C2 -. "使用查询与工作流能力" .-> U4
    C3 -. "使用事件/持久化基础" .-> U4
    C5 -. "复用评测期任务执行" .-> U4
    C6 -. "复用真实任务事件" .-> U4
    C7 -. "复用模型配置与注册" .-> U3
    C8 -. "通过 REST" .-> U2
```

边界说明：

- 不删除或替换上游 LICENSE、版权声明和工作流/模型基础设施。
- 不把上游已有的 Agent、Chat、知识库、数据源管理页面宣称为本项目原创。
- ChainPilot 的重点新增是供应链业务层、评测闭环、观测层和新的 Next.js 工作台。
- 未发现可验证的上游 PR 记录；不能声称已向原仓库提交贡献。

## 3. 风险分析主流程

```mermaid
sequenceDiagram
    participant U as 用户
    participant API as SupplyChain Controller
    participant T as Task/Event Repository
    participant Q as SupplyChainQueryService
    participant R as Java 风险规则
    participant W as Workflow / Agent
    participant M as CHAT 模型（可选）
    participant V as RiskNarrativeValidator
    participant D as H2/MySQL

    U->>API: POST /api/supply-chain/analysis/tasks
    API->>T: 创建任务与 TASK_QUEUED 事件
    API->>W: 异步执行分析工作流
    W->>Q: 查询订单/库存/供应商（真实 Java 工具）
    Q->>D: 读取模拟业务数据
    W->>R: 计算风险分数/等级/证据/dataGap
    R-->>W: 确定性结果
    W->>M: 请求结构化解释（可选）
    M-->>W: overview/reasons/evidenceIds/recommendations
    W->>V: 校验订单、证据 ID、数值、推荐、dataGap
    alt 校验通过
        V-->>W: MODEL_VERIFIED 风险叙述
    else 校验失败或模型不可用
        V-->>W: DETERMINISTIC_FALLBACK
    end
    W->>T: 记录 MODEL_STARTED/COMPLETED/FAILED、FALLBACK_USED
    W->>D: 持久化报告、任务终态和事件
    U->>API: GET 任务/报告/事件
    API-->>U: 状态、报告、叙述来源、事件
```

重要不变量：

1. 风险分数、等级、评分贡献和证据由 Java 规则产生。
2. 模型只能解释已有上下文，不能修改正式评分。
3. 未知订单/证据、无依据数值、执行声明、缺失 dataGap、非法推荐都拒绝。
4. 校验失败或异常回退 `DETERMINISTIC_FALLBACK`，不标记 `MODEL_VERIFIED`。
5. `MODEL_STARTED`、`MODEL_COMPLETED`、`MODEL_FAILED`、`FALLBACK_USED` 关联同一 taskId。任务已被其他实例取消时，在途响应仍按真实类型记录，但 `status=CANCELLED` 且摘要带 `late response ignored … rawOutcome=…`；该判定读的是数据库终态，不改变模型调用与降级计数。

## 4. 决策、情景推演与沙箱边界

```mermaid
flowchart TD
    Q["自然语言决策请求"] --> I["意图识别/澄清"]
    I --> R["只读工具查询"]
    R --> C["风险规则与证据"]
    C --> P["候选提案 PENDING_CONFIRMATION"]
    P --> H{"用户明确确认？"}
    H -->|否| N["仅保留提案，不写业务数据"]
    H -->|是| S["沙箱/受控执行记录"]
    S --> E["执行状态与步骤持久化"]
    C --> SIM["情景推演与共享基线"]
    SIM --> CMP["多候选比较与确定性排名"]
    CMP --> P
    CMP --> O["只读比较记录"]
```

隔离原则：

- 订单、库存、供应商是 `SupplyChainDemoData` 的模拟数据，不是真实生产数据。
- 模拟执行和候选比较只写 ChainPilot 自己的模拟/比较表，不写真实库存或订单。
- 沙箱执行有显式的确认和资格边界；成功记录仍不代表真实 ERP/WMS 写入。
- `realDataMutated=false` 是决策结果的一部分，不能被模型改写成“已执行”。

## 5. 评测与人工反馈闭环

```mermaid
flowchart LR
    C["版本化评测案例集"] --> RUN["Evaluation Run"]
    RUN -->|OFFLINE| DET["确定性规则/库存/供应商/检索断言"]
    RUN -->|AGENT| WF["真实工作流任务"]
    WF --> TASK["taskId + 报告/决策"]
    TASK --> ASSERT["工作流断言"]
    DET --> STORE[("run + result 持久化")]
    ASSERT --> STORE
    STORE --> UI["评测中心：历史/对比/指标/重跑"]
    F["风险报告/决策上的用户反馈"] --> FB[("feedback 持久化")]
    FB --> DRAFT["案例草案（初始无预期/断言）"]
    DRAFT --> HUMAN["人工填写预期与可执行断言"]
    HUMAN --> REVIEW["审核"]
    REVIEW --> ENABLE["启用 FB-XXXXXXXX"]
    ENABLE --> C
    ENABLE --> RUN
```

- OFFLINE 运行不调用模型，最多 20 个案例。
- AGENT 运行必须显式确认，最多 5 个案例、20 分钟超时、数据库租约锁（`chainpilot_evaluation_lock` 的 owner + fence）。
- 反馈不是标准答案；草案初始不自动生成通过条件。
- 追踪链：`feedbackId → evaluationCaseId → evaluationRunId → 实际结果`。
- 当前 14 个 OFFLINE、6 个 AGENT 内置案例和已启用反馈案例使用同一执行入口。
- 案例目录声明每个案例"必须执行的断言 ID"，评测按声明生成断言：未执行的声明断言会使案例 `FAILED`
  （`ASSERTION_NOT_EVALUATED`）。需要模型参与的案例还声明模型参与断言——分析阶段要求 `modelAssisted=true`
  与该阶段一条 `status=COMPLETED` 的 `MODEL_COMPLETED` 事件，决策阶段额外要求 `fallbackUsed=false`；
  因此确定性降级产生的 `COMPLETED` 任务不会通过真实模型验收案例。`MODEL_STARTED`、请求计数与迟到
  （`status=CANCELLED`）响应都不算模型参与证据。OFFLINE、反馈回归与 `REPORT-FALLBACK-NO-MODEL`
  保持确定性降级语义，不声明模型参与断言。

## 6. 模型就绪、依赖与安全门禁

```mermaid
flowchart TD
    C["模型配置（CHAT/EMBEDDING）"] --> A["激活配置"]
    A --> T{"用户手动连接测试？"}
    T -->|否| UN["CONFIGURED_UNVERIFIED"]
    T -->|成功| V["VERIFIED（进程内）"]
    T -->|失败| F["TEST_FAILED + 安全错误码"]
    CFG["配置签名<br/>地址/模型/路径/认证/代理摘要"] --> V
    V --> G{"所选 AGENT 案例依赖满足？"}
    UN --> G
    F --> G
    G -->|CHAT 未验证| R1["412 AGENT_CHAT_NOT_READY"]
    G -->|EMBEDDING 必需但未验证| R2["412 AGENT_EMBEDDING_NOT_READY"]
    G -->|满足| OK["启动 AGENT 工作流"]
```

- 连接测试结果保存在当前后端进程；重启后回到未验证，不读取长期成功布尔值。
- 配置变化（包括 API Key/代理凭据）会使签名失效。
- 前端禁用只是提示；后端按所选案例依赖强制校验，不能通过直接 API 绕过。
- 连接错误分类为 `AUTHENTICATION_FAILED`、`CONNECTION_TIMEOUT`、`ENDPOINT_UNAVAILABLE`、
  `INVALID_MODEL_CONFIGURATION`、`UNSUPPORTED_RESPONSE`、`RATE_LIMITED`、
  `CONNECTION_TEST_FAILED`；消息和日志经过凭据脱敏。

## 7. 观测与事件模型

事件写入 `chainpilot_task_event`，包含事件 ID、taskId、序号、类型、阶段、状态、时间、
耗时、简短摘要和错误类型。典型事件：

```text
TASK_QUEUED → TASK_STARTED → NODE_STARTED/COMPLETED
  → TOOL_STARTED/COMPLETED/FAILED
  → MODEL_STARTED → MODEL_COMPLETED/FAILED → FALLBACK_USED
  → TASK_COMPLETED/FAILED
```

- 事件来自实际执行路径，不使用前端伪造。
- Observability 汇总只基于持久化任务和事件；模型 usage 在 provider 返回时记录，
  否则写 `token usage unavailable`，不估算 token 或成本。
- 前端提供汇总、运行列表、单任务时间线、模型/工具/回退诊断和任务详情跳转。

## 8. 数据与部署边界

| 数据 | 用途 | 是否真实业务数据 |
| --- | --- | --- |
| `SupplyChainDemoData` | 订单、库存、供应商演示 | 否，本地模拟 |
| `chainpilot_analysis_task` / 事件表 | 任务与事件追溯；`owner_instance_id` / `lease_expires_at` / `fence_version` 承载任务所有权 | ChainPilot 自身运行数据 |
| `chainpilot_disposal_*` | 决策/方案/模拟/比较/执行 | ChainPilot 模拟记录 |
| `chainpilot_evaluation_run` / `chainpilot_evaluation_case_result` | 评测运行与结果 | ChainPilot 评测记录 |
| `chainpilot_evaluation_lock` | 单行 `AGENT_EVALUATION` 锁，承载 AGENT 评测所有权 | ChainPilot 运行数据 |
| `chainpilot_feedback*` | 用户反馈与草案 | ChainPilot 运行数据 |
| H2 文件库 | 本地演示默认持久化 | 本地数据，不提交 |
| MySQL `chainpilot_mysql_it` | 隔离集成测试 | 专用测试库，不作为生产库 |

部署端口：后端 `8065`；Next 开发默认 `3000`，生产验收使用 `3002`。Next 将
`/api/**`、`/uploads/**` 反代到 `CHAINPILOT_BACKEND_URL`。

### 8.1 所有权与租约术语（统一口径）

| 术语 | 实现 | 语义 |
| --- | --- | --- |
| `owner_instance_id` | `chainpilot_analysis_task` 列 / `chainpilot_evaluation_lock` 列 | 当前持有者；值为 `chainpilot.instance-id`（未配置则启动时生成随机 UUID），`NULL` 表示无人持有 |
| `lease_expires_at` | 同上，BIGINT 毫秒 | 租约到期时刻，与 `SELECT CURRENT_TIMESTAMP` 返回的**数据库时钟**比较，不比较 JVM 时钟 |
| `fence_version` | 同上，BIGINT | 单调递增的写入门槛：领取、接管、取消、过期回收都会 +1；只有 owner 与 fence 同时匹配的写入才被接受 |
| 租约续期 | `TaskLeaseManager.renewOwnedLeases` / `EvaluationLeaseManager.renewActiveLease`，均为 `@Scheduled`（默认每 30 秒；任务 TTL 15 分钟、评测 TTL 10 分钟、评测另有 30 分钟最长持有时限） | 续约失败即判定失权，本实例立即停止推进并放弃后续写入 |
| 过期回收 | `TaskRecoveryCoordinator` / `EvaluationLeaseManager` 的回收调度（默认每 30 秒） | 只处理「owner 非空且租约已过期」的对象：任务标记 `FAILED / LEASE_EXPIRED`，评测运行标记 `FAILED` 且 RUNNING 案例转 `UNDETERMINED / INTERRUPTED`；绝不重放 Graph、绝不重派任务；`owner` 为空的历史遗留行不动，只在日志计数 |
| 旧 owner 写入拒绝 | `updateOwned` / `completeAnalysis` / `completeDecision` / `saveOwned` 的 owner+fence 条件更新 | 被接管或被回收后，前持有者的状态写入与结果写入在数据库层被拒绝，无法复活对象或覆盖新持有者的结果 |
| 取消后的迟到响应 | `SupplyChainEventRecorder` 依据数据库终态判定 | `MODEL_COMPLETED` / `MODEL_FAILED` / `FALLBACK_USED` 类型与耗时、错误类别保留（真实发生），但 `status=CANCELLED` 且摘要带 `late response ignored … rawOutcome=…`；业务结果不写入 |

全局互斥一律由数据库行/条件更新保证，不存在“依靠 JVM 内存锁保证跨进程互斥”的设计；进程内标记（`TaskCancellationRegistry`
的信号表、`ModelReadinessService` 的连接验证结果）只用于降低同实例的发现延迟或记录本进程的验证状态，
因此多实例部署时每个 JVM 都要各自完成一次模型连接测试，未测试的实例会以 `412` 前置条件失败而不是 `409`。

## 9. 当前状态与不可宣称事项

- 模型配置列表为空；没有完成真实模型 Agent E2E，也没有真实 provider usage 或费用证据。
- H2/MySQL/假模型/前端测试通过，只能说明确定性路径、持久化、Mock 边界和 UI 契约；
  不能替代真实模型质量或生产性能验证。
- 多实例：已在隔离 MySQL 上用两个真实 JVM 实测任务/评测所有权、租约互斥与续约、fencing 拒写、
  跨实例取消与持有者被强杀后的过期回收（见 `docs/DATABASE_RELEASE.md` §5.3）；这不等于生产 HA/主从、
  默认租约参数下的长时稳定性、多实例压测或备份恢复已经验证。第 35 轮实测使用的是缩短的测试参数
  （TTL 30 秒、续约/回收 5 秒）。
- 沙箱不是真实 ERP/WMS 执行器。
- 连接测试状态不是跨重启持久事实，也不是跨进程共享事实（每个 JVM 各自持有）。
- 当前没有可核实的上游 PR 记录。

## 10. 代码入口

| 领域 | 入口 |
| --- | --- |
| 供应链查询与规则 | `data-agent-management/src/main/java/.../supplychain` |
| 评测/反馈 | `data-agent-management/src/main/java/.../evaluation` |
| 模型配置与就绪 | `data-agent-management/src/main/java/.../service/aimodelconfig` |
| 工作流与工具 | `data-agent-management/src/main/java/.../workflow`、`supplychain/service` |
| 前端页面 | `data-agent-frontend-next/src/app` |
| 前端 API | `data-agent-frontend-next/src/lib/api` |
| 只读 Smoke Test | `scripts/chainpilot-smoke-test.ps1` |
| 评测案例资源 | `data-agent-management/src/main/resources/evaluation` |
