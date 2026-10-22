# ChainPilot 数据库发布、备份与回滚指南

## 1. 适用范围与前置结论

ChainPilot 当前没有 Flyway/Liquibase。正式业务表由两类入口创建：

1. `data-agent-management/src/main/resources/sql/schema.sql`：Agent、知识库、数据源、聊天、模型配置等基础表。`mode=never` 时应用不会自动执行。
2. 各正式 Repository 构造器中的 `CREATE TABLE IF NOT EXISTS` / 兼容加列逻辑：分析任务、事件、处置方案/模拟/执行、比较、评测、反馈等 ChainPilot 表。

所有运行时 DDL 都是加法式的，没有自动 `DROP TABLE`、重建业务表或批量删除数据。任务保留清理只删除超过保留期的无引用终态任务及其事件，不属于发布迁移。

## 2. 全新数据库初始化

必须使用 UTF-8 客户端；在 Windows 上不指定字符集时，`mysql` 客户端可能用本地代码页读取 SQL 文件，导致中文默认值报错。

```bash
mysql --default-character-set=utf8mb4 -h 127.0.0.1 -P 3306 -u USER -p \
  -e "CREATE DATABASE chainpilot CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"

mysql --default-character-set=utf8mb4 -h 127.0.0.1 -P 3306 -u USER -p chainpilot \
  < data-agent-management/src/main/resources/sql/schema.sql

# 可选：演示业务样例库（只执行一次）
mysql --default-character-set=utf8mb4 -h 127.0.0.1 -P 3306 -u USER -p chainpilot \
  < data-agent-management/src/main/resources/sql/product_schema.sql
mysql --default-character-set=utf8mb4 -h 127.0.0.1 -P 3306 -u USER -p chainpilot \
  < data-agent-management/src/main/resources/sql/product_data.sql

# ChainPilot 租约/所有权正式 DDL（顺序固定，对空库各执行一次）
mysql --default-character-set=utf8mb4 -h 127.0.0.1 -P 3306 -u USER -p chainpilot \
  < data-agent-management/src/main/resources/sql/agent-evaluation.sql
mysql --default-character-set=utf8mb4 -h 127.0.0.1 -P 3306 -u USER -p chainpilot \
  < data-agent-management/src/main/resources/sql/chainpilot-lease-ownership.sql

# 启动应用；ChainPilot Repository 会创建其余业务表
java -jar data-agent-management/target/chainpilot-management-1.0.0-SNAPSHOT.jar
```

验证要点：

- `schema.sql` 应创建 13 张基础表；应用启动后完整库应有 28 张表（第 29 轮实测值，加锁表之前）。第 35 轮在隔离 MySQL 上用两个真实 JVM 启动后实测为 **29 张表**（13 张 `chainpilot_*`），见 5.3。
- `agent-evaluation.sql` 里的 `CREATE INDEX` 和 `INSERT INTO chainpilot_evaluation_lock ...` 是**仅执行一次**的语句。实测：对已初始化库重复执行时 MySQL 在第 45 行的第一个 `CREATE INDEX` 报 `ERROR 1061 (42000) Duplicate key name 'idx_evaluation_result_run'` 并中止（`mysql` 客户端默认遇错停止），所以锁行不会被重复插入（实测 `lock_rows` 仍为 1）。需要重复执行时只跑 `chainpilot-lease-ownership.sql`——它的每条 ALTER/INSERT 都由 `information_schema` 条件守卫，实测可重复执行且不改变结果。
- 应用启动日志应出现 `Started DataAgentApplication`，且没有建表/加列异常。
- `product_schema.sql` 和 `product_data.sql` 是演示样例，**不是幂等脚本**；第二次执行 `product_schema.sql` 会因 `users` 已存在而失败，只应对空库执行一次。

## 3. 已有数据库的兼容升级

应用启动时执行以下加法式逻辑：

- `SupplyChainTaskRepository`：确保 `task_type`、`decision_json`、`owner_instance_id`、`lease_expires_at`、`fence_version` 列存在。启动时**不再**批量改写非终态任务；是否 abandoned 只由 `TaskRecoveryCoordinator` 依据数据库租约判断——仅 `owner_instance_id` 非空且 `lease_expires_at` 已过期的 `ACCEPTED/RUNNING` 任务被标记为 `FAILED/LEASE_EXPIRED` 并追加一条 `TASK_FAILED` 事件，绝不重放 Graph。没有 owner 的遗留行保持原状，只在日志中计数，等待显式处理。`CANCELLED`、`COMPLETED`、`FAILED` 不会被覆盖。
- `EvaluationRunRepository`：补 `failure_reason`、`case_version`、`task_mode`，安全创建索引/外键；新建 `chainpilot_evaluation_lock`（`lock_name`、`owner_instance_id`、`lease_expires_at`、`fence_version`、`run_id`、`updated_at`）并插入 `AGENT_EVALUATION` 初始行。旧评测数据保留。
- 其他 ChainPilot Repository：`CREATE TABLE IF NOT EXISTS`，不修改已有业务行。
- `SupplyChainEventRepository` 等使用唯一约束和外键；事件序号/取消逻辑依赖这些约束。

升级路径（两条都可用，推荐先做显式迁移）：

- **显式迁移（推荐，可审计）**：对已有库执行 `data-agent-management/src/main/resources/sql/chainpilot-lease-ownership.sql`。该脚本只做加法：给 `chainpilot_analysis_task` 补 `owner_instance_id VARCHAR(64) NULL`、`lease_expires_at BIGINT NULL`、`fence_version BIGINT NOT NULL DEFAULT 0`，创建 `chainpilot_evaluation_lock` 并插入唯一 `AGENT_EVALUATION` 行。每条语句都由 `information_schema` 计数守卫，因此可重复执行；脚本内含 PRE-FLIGHT/POST-CHECK 查询，输出应贴进变更单。旧行结果：`owner_instance_id=NULL`、`lease_expires_at=NULL`、`fence_version=0`，即“从未被租出”，租约回收逻辑会跳过它们。
- **应用惰性加列（兜底）**：不执行脚本时，`SupplyChainTaskRepository` / `EvaluationRunRepository` 会在启动时补齐同样的列与表。它不是正式迁移文档：异常被吞掉、无执行记录、无法证明补全成功，只适合本机演示。生产发布必须以显式脚本 + POST-CHECK 为准。

迁移顺序固定为三段，缺一即视为未完成：① 原始业务 schema（`schema.sql`，13 张基础表）→ ② 评测相关 schema（`agent-evaluation.sql`：run / case_result / lock + 索引 + 初始锁行，只对空库执行一次）→ ③ `chainpilot-lease-ownership.sql`（任务所有权列 + 锁表/锁行的守卫式补齐，可重复执行）。多实例运行依赖第 ③ 步产出的 `owner_instance_id`、`lease_expires_at`、`fence_version` 与 `chainpilot_evaluation_lock`：任一缺失时，横向扩容就不具备所有权语义（可能同时执行同一任务/同一评测），因此未完成 POST-CHECK 的库不得从 1 个实例扩到多个实例。

本文的兼容结论只覆盖**已实测的结构**：第 34/35 轮在隔离 MySQL 8.0.43 上验证了「初版 ChainPilot 表结构（10 列任务表 + 无锁表）→ 迁移后」这一条路径，且验证用的库是本轮新建专用库。不宣称能无损升级所有历史数据库：更老或字段漂移过的结构、非 InnoDB/非 utf8mb4 库、以及存在孤儿行的库都必须在目标库上先跑 PRE-FLIGHT/POST-CHECK 与行数比对后再判定。

发布步骤：

1. 停止应用或切换到维护窗口（迁移期间不允许有写入，避免与旧版本的全量启动改写叠加）。
2. 做完整备份（见第 4 节），并手工执行脚本内注释的 `SELECT status, COUNT(*) FROM chainpilot_analysis_task GROUP BY status` 快照。
3. 执行 `chainpilot-lease-ownership.sql`；脚本可安全重跑，报任何 SQL 错误都必须停下来核对，不能靠应用启动补齐。
4. 执行脚本第 4 节 POST-CHECK：3 个所有权列存在、锁表存在且仅 1 行、两张表 `ENGINE=InnoDB`、`untouched_tasks` 等于迁移前总行数、状态分布与步骤 2 一致。
5. 先启动**一个**新版本实例，确认日志无建表/加列异常且租约调度器已注册；检查关键表。
6. 确认 schema 稳定后再启动其余 JVM。不要在多个实例同时进行首次基础 schema 升级。
7. 用只读查询核对旧任务、方案、评测、反馈行数和关键状态；租约字段只应由执行中的任务写入。

## 4. 备份与恢复

在隔离测试库验证过的命令：

```bash
mysqldump --default-character-set=utf8mb4 --single-transaction --routines --triggers \
  -h 127.0.0.1 -P 3306 -u USER -p chainpilot > chainpilot-before-release.sql
```

验证：

1. 在临时空库导入备份并检查表数量、关键表行数。
2. 应用启动后抽查任务、方案、执行、评测、反馈和事件。
3. 备份文件包含敏感模型 API Key（若数据库中存在），必须按密钥文件保护并限制访问。

恢复/回滚：

```bash
# 先停止应用，确认没有写入
mysql --default-character-set=utf8mb4 -h 127.0.0.1 -P 3306 -u USER -p \
  -e "CREATE DATABASE chainpilot_restore CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
mysql --default-character-set=utf8mb4 -h 127.0.0.1 -P 3306 -u USER -p chainpilot_restore \
  < chainpilot-before-release.sql
# 将应用连接切到恢复库，或按运维流程替换原库后再启动旧版本
```

当前迁移只增加列/表/索引，不删除业务列，因此数据库层通常向后兼容旧应用版本；但旧应用无法理解新业务列，回退前应同时回退应用版本并核对 API 契约。

## 5. 隔离 MySQL 实测结果

环境：MySQL 8.0.43，`127.0.0.1:33306`，InnoDB/utf8mb4，专用测试库；不使用任何正式或共享库。

### 5.1 第 29 轮（基础发布流程）

| 场景 | 结果 |
| --- | --- |
| `schema.sql` 使用默认客户端字符集 | **失败**：8 张表后 `chat_session.title` 中文默认值报错 |
| 使用 `--default-character-set=utf8mb4` 重新执行 | **通过**：13 张基础表 |
| 应用启动到全新库 | **通过**：28 张表，`Started DataAgentApplication`，0 建表异常 |
| 同一全新库再次启动第二 JVM | **通过**：无重复建表错误，表数仍为 28 |
| 旧结构任务表 + 已有数据，两个 JVM 同时启动 | **通过**：两进程均启动；旧行保留，`task_type/decision_json` 补齐 |
| 部分初始化后修正字符集重跑 | **通过**：幂等补齐到 13 张基础表 |
| `product_schema.sql` 重复执行 | **预期失败**：`Table 'users' already exists`；样例脚本只执行一次 |
| 备份/恢复 | **通过**：备份约 49 KB，恢复库 28 张表与源库一致 |

日志与备份：

- `data-agent-management/target/round29-fresh-init.log`
- `data-agent-management/target/round29-fresh-second.log`
- `data-agent-management/target/round29-upgrade-a.log`
- `data-agent-management/target/round29-upgrade-b.log`
- `data-agent-management/target/round29-release-backup.sql`

### 5.2 第 34 轮（所有权、租约与评测锁）

实例身份核对：`mysqld 8.0.43`，`--bind-address=127.0.0.1 --port=33306`，`datadir=C:\cp-mysql\data`，非系统库只有 `chainpilot_*` 测试库；`@@transaction_isolation=REPEATABLE-READ`。

| 场景 | 结果 |
| --- | --- |
| 空库按正式 DDL 顺序执行 `agent-evaluation.sql` → `chainpilot-lease-ownership.sql` | **通过**：4 张表（task/eval run/eval case_result/eval lock），全部 InnoDB + `utf8mb4_unicode_ci` |
| `chainpilot-lease-ownership.sql` 对同一库重复执行 | **通过**：第二次退出码 0，锁行仍为 1 行，无结构变化（守卫生效） |
| `agent-evaluation.sql` 对已初始化库重复执行 | **按设计失败**：第 45 行 `CREATE INDEX` 报 `ERROR 1061 Duplicate key name 'idx_evaluation_result_run'` 并中止；中止点在建索引处，锁行未增加（`lock_rows=1`） |
| 旧结构库（10 列任务表、无锁表）迁移 | **通过**：列数 10 → 13；`fence_version` 旧行回填 0；`owner_instance_id`/`lease_expires_at` 保持 NULL |
| 迁移前已有 5 条任务（ACCEPTED/RUNNING/COMPLETED/FAILED/CANCELLED 各 1）与 2 条评测运行 | **通过**：迁移后行数、状态、`current_step`、`failure_reason` 完全未变；RUNNING 任务未被改写 |
| 所有权列类型与默认值 | **通过**：`varchar(64) NULL` / `bigint NULL` / `bigint NOT NULL DEFAULT 0`；锁表 `lease_expires_at`、`fence_version`、`updated_at` 均 NOT NULL |
| 索引 | **通过**：`PRIMARY(lock_name)`、`uk_evaluation_result_run_case(run_id,case_id)`、`idx_evaluation_result_run`、run 表两个 `(status|mode, started_at)` 索引 |
| 数据库时钟精度 | **实测确认**：`SELECT CURRENT_TIMESTAMP` 在 MySQL 8 返回**秒级** DATETIME（例：`11:28:10`），因此 `databaseNowMillis()` 的毫秒位恒为 000；H2 返回更高精度。租约比较在同一数据库内自洽，最坏偏差 ≤1 s，远小于 30 s 续约与 10/15 min TTL。需要亚秒租约时必须改用 `CURRENT_TIMESTAMP(3)` |
| `EvaluationMySqlPersistenceIT` | **通过**：7 tests，0 failures，0 errors，2.372 s |
| `SupplyChainMySqlPersistenceIT` | **通过**：23 tests，0 failures，0 errors，2.199 s |
| `SELECT … FOR UPDATE` 真实并发 | **通过**：持有者事务内领取租约不提交时，第二 JVM 的领取被行锁阻塞（1.5 s 后仍未完成）；持有者提交后第二 JVM 获得锁行读取权但因活租约被拒绝，`owner_instance_id` 仍为第一个 JVM |
| 同一任务两线程并发 `claimTask` | **通过**：恰好一个成功，`fence_version=1`（只有一条 UPDATE 生效），第三个 JVM 再领取被拒 |
| 任务租约与评测锁互不污染 | **通过**：改写评测锁行不改任务 `fence_version/owner/lease`；任务续约不改评测锁 `fence_version`；两条路径互不解除对方所有权 |

日志：

- `data-agent-management/target/round34-mysql-it-final.log`
- `data-agent-management/target/round34-full-h2.log`

### 5.3 第 35 轮：双 JVM 真实进程验收（已通过）

环境：同一隔离实例（MySQL 8.0.43，`127.0.0.1:33306`），专用库 `chainpilot_r35_dual` 由官方脚本初始化（`schema.sql` + `agent-evaluation.sql` + `chainpilot-lease-ownership.sql`）。JVM-A `--server.port=8065 --chainpilot.instance-id=cp-jvm-a`，JVM-B `--server.port=8066 --chainpilot.instance-id=cp-jvm-b`，两者数据源连接串完全相同；两个进程均输出 `Started DataAgentApplication`，启动后整库 **29 张表**（补上第 34 轮标注的待复测项，其中 13 张 `chainpilot_*`）。为压缩等待时间临时把租约参数调小（任务/评测 TTL 30 s、续约 5 s、回收 5 s；生产默认仍为 15 min / 10 min / 30 s）。模型是 loopback 上的本地假 OpenAI-compatible 服务（固定 15 s 延迟、响应内容显式标注 fake），**未调用任何真实模型 API，因此这不构成真实模型验收证据**。

| 场景 | 实测结果 |
| --- | --- |
| 任务所有权 | A 提交任务后 DB 为 `RUNNING / owner=cp-jvm-a / fence=1`；A 续约把 `lease_expires_at` 由 …184000 推到 …194000；第三个独立 JVM 的 `claimTask`、`renewTaskLease`、`updateOwned` 全部 false，A 的 owner/fence/status 未被修改 |
| 跨实例取消 | JVM-B 调 `POST /api/supply-chain/analysis/tasks/{id}/cancel` → DB `CANCELLED`、`owner=NULL`、`lease=NULL`、`fence 1→2`；JVM-A 日志「task stopped at a cancellation checkpoint」，`report_json` 仍为空 |
| 评测互斥 | A 启动 AGENT 运行后锁行 `owner=cp-jvm-a / fence=1`；B 再启动返回 **HTTP 409**「已有 AGENT 评测持有数据库租约，请等待其完成或租约到期后再启动」，任务计数不变（B 未提交任何真实任务，评测运行表仍只有 A 的一行）；B 轮询同一 run 返回 200 但 DB 仍 `RUNNING / completed_at=NULL / 案例 RUNNING`，即 B 未推进 A 的运行 |
| 正常终态 | A 轮询自身运行 → `COMPLETED`、案例 `PASSED`；锁行同事务变为 `owner=NULL / lease=0 / run=NULL`、`isEvaluationLockHeld=false`；随后 B 能合法启动新运行（HTTP 200） |
| 持有者强制退出 | 杀死 JVM-B 后 T+10 s、T+22 s：其任务仍 `RUNNING / owner=cp-jvm-b`、锁行仍 `cp-jvm-b`（租约未过期，JVM-A 未提前接管）；到期后（T+34 s）JVM-A 调度器把任务置为 `FAILED / LEASE_EXPIRED / owner=NULL / fence 1→2`，把 B 的运行置为 `FAILED`（`failure_reason=评测运行租约过期且没有活跃执行者；未自动重新执行`）、案例 `UNDETERMINED / INTERRUPTED`、锁行 `owner=NULL / fence 2→3`；任务总数不变（无重派），该任务只多出 1 条 `TASK_FAILED / LEASE` 事件且无新的 Graph 节点事件；日志为 `TaskRecoveryCoordinator: recovered 2 task(s)`（被杀实例的 1 个直接任务 + 1 个评测子任务，均符合策略：只标失败、不接管、不重放） |
| 失权后旧实例写入 | 两个独立 JVM 交替持有同一任务：旧 owner（fence 1）的 `updateOwned`、`completeAnalysis`、`renewTaskLease` 均为 false，DB 最终仍是新 owner 的 `step=FROM-D-1 / owner=cp-jvm-d / fence=2 / report_json IS NULL`；评测侧旧 owner 的 `renewEvaluationLock`、`saveOwned` 为 false，且旧 owner 的 `releaseEvaluationLock` 未能解除新 owner 的锁（锁行仍 `owner=cp-jvm-d / fence=6`），只有新 owner 自己释放后锁才变为 `owner=NULL`；正确 owner+fence 的写入则成功（`evalSave=true`），证明拒绝来自 fencing 而非通路故障 |

同时确认两项运行前提与一项观感问题：

- 模型连接验证状态保存在 `ModelReadinessService` 的**进程内内存**里，不入库。因此每个 JVM 都必须各自完成一次连接测试；未测试的实例会以 412 前置条件失败而不是 409。多实例部署时这一点必须写进运维步骤。
- MySQL 的 `SELECT CURRENT_TIMESTAMP` 为秒精度，实测 `dbNow` 毫秒位恒为 000，与第 34 轮结论一致。
- 跨实例取消后的事件时序（第 35 轮记为 P3 未处理项）已在第 36 轮收口：`MODEL_COMPLETED` / `MODEL_FAILED` / `FALLBACK_USED` 的事件类型、耗时与错误类别保持不变（真实发生的调用与降级不隐瞒，模型调用/降级计数口径不变），但 `status` 变为 `CANCELLED`，摘要以 `late response ignored … rawOutcome=…` 标明该响应被忽略、未持久化任何业务结果；判定依据是数据库中的任务终态（跨实例持久化检查），不是进程内标记。见 `docs/DEMO_GUIDE.md` 的「迟到响应的语义」与 `SupplyChainCancellationEventTest`。

日志：`data-agent-management/target/dualjvm/jvm-a.log`、`jvm-b.log`、`fake-model.log`。

### 5.4 仍未执行的 MySQL 验证

- 空库由应用自行创建全部表的“纯首启”路径（第 35 轮的应用是在已执行官方 DDL 的库上启动的，29 张表结论包含脚本创建的 4 张）。
- 生产 MySQL 版本矩阵、主从/HA、TLS、最小权限账号、长时间备份锁、跨版本升级。
- `--default-character-set=utf8mb4` 下的整库备份/恢复复测（含新增租约列与锁表）。
- 双 JVM 场景在**默认租约参数**（TTL 15 min / 10 min、续约 30 s）下的长时程观察；本轮为压缩等待使用了缩短的测试参数。

### 5.5 第 38 轮：迟到模型响应在真实 MySQL 上的持久化验收

环境：同一隔离实例（MySQL 8.0.43，`127.0.0.1:33306`，`datadir=C:\cp-mysql\data`，REPEATABLE-READ）。本轮新建专用库 `chainpilot_r38_event_persistence`，按正式顺序 `schema.sql` → `agent-evaluation.sql` → `chainpilot-lease-ownership.sql` 迁移，结果：17 张表、3 个所有权列、锁表 1 行、engine 唯一（InnoDB）。自动化 IT 因测试守护只能使用专用 `chainpilot_mysql_it`（`SupplyChainMySqlTestSupport` 强制校验库名），两个库都是本轮新建或既有的隔离测试库，未接触任何演示/业务库。

| 场景 | 真实结果 |
| --- | --- |
| A 活跃任务 | `MODEL_STARTED/STARTED`、`MODEL_COMPLETED/COMPLETED`、`FALLBACK_USED/FALLBACK`、`MODEL_FAILED/FAILED`，摘要原样、`duration_ms=48/63`、`error_type=IllegalStateException`，无一行 `status=CANCELLED`，事件序号 1–4 唯一 |
| B 取消先提交、响应后返回（第二连接执行生产取消事务 + 门控假 Provider） | 取消后 DB 即 `CANCELLED / owner=NULL / lease=NULL / fence 1→2`，此时 worker 仍未完成；释放响应后 MySQL 落库为 `MODEL_COMPLETED/CANCELLED` 与 `FALLBACK_USED/CANCELLED`，摘要均为 `late response ignored; the task already reached a terminal state and nothing was persisted from it; rawOutcome=…`，原始拒绝原因与 `reason=INVALID_MODEL_NARRATIVE` 与 `duration_ms=12001`（真实阻塞时长）保留；`report_json=NULL`，无 `TASK_COMPLETED`/`TASK_FAILED`，`TASK_CANCELLED` 仍 1 条，fence 未再变化（无 owner/lease 复活、无 Graph 重放） |
| C 跨实例重复取消 | 第一次 `changed=true`、第二次 `changed=false`（两个连接各试一次也仍为 false）；`TASK_CANCELLED` 恰 1 条，fence 保持 2，终态与 `current_step` 不变 |
| 回归 | `SupplyChainMySqlPersistenceIT` 23/23、`EvaluationMySqlPersistenceIT` 7/7、新 `SupplyChainLateResponseMySqlIT` 3/3，合计 33/33、0 失败 0 错误；`test-compile` BUILD SUCCESS、0 Checkstyle violations；H2 邻居用例（分析/取消/工作流）29/29 |

日志与证据：`data-agent-management/target/round38-mysql-it.log`、`round38-compile.log`；`chainpilot_r38_event_persistence` 内保留 `r38ev-active / r38ev-late / r38ev-repeat` 三组任务与事件行供复核（`r38ev-active` 故意留在 `RUNNING`，用于证明没有任何启动批处理会改写它）。假 Provider 只用于控制响应时序，本轮不构成真实模型 E2E；本轮也未重复完整双 JVM 所有权验收（见 §5.3）。

## 6. 已知风险与发布前事项

- 所有权字段与 `chainpilot_evaluation_lock` 已进入正式 checked-in DDL：`src/main/resources/sql/agent-evaluation.sql`（评测三表 + 锁表 + 初始锁行）和 `src/main/resources/sql/chainpilot-lease-ownership.sql`（守卫式升级脚本，含 PRE-FLIGHT/POST-CHECK 与回滚说明）。第 34 轮已在隔离 MySQL 8.0.43 实测空库初始化、旧结构升级、重复执行与数据保留（5.2），第 35 轮又用两个真实 JVM 在同一专用库上完成启动、互斥、接管与失权验收，整库实测 29 张表（5.3）。残留事项：① “空库完全由应用首启建表” 的纯首启路径仍未测（第 35 轮的应用是在已执行官方 DDL 的库上启动的）；② `agent-evaluation.sql` 的 `CREATE INDEX` 与锁行 `INSERT` 不可重复执行，必须只对空库运行一次；③ 双 JVM 验收使用的是缩短后的租约参数（TTL 30 s / 续约 5 s），默认参数（15 min / 10 min / 30 s）下的长时程观察未做。
- 租约判定使用数据库时钟，但 MySQL 的 `CURRENT_TIMESTAMP` 只有**秒级**精度（见 5.2），H2 精度更高。当前 TTL（任务 15 min、评测 10 min）与续约周期 30 s 下 ≤1 s 截断无实际影响；若将来把租约缩短到秒级，必须改用 `CURRENT_TIMESTAMP(3)` 并重新验收。
- 租约回收依赖 `@Scheduled`；若运维关闭调度或所有实例同时停止，过期任务/评测会保持 `RUNNING` 直到任一实例再次启动。回滚到旧版本前必须接受：旧版本无法理解租约列，仍会在启动时批量改写非终态任务。
- Windows 下必须显式使用 UTF-8 客户端字符集；发布文档和脚本应固定该参数。
- `EvaluationRunRepository` 的兼容迁移会吞掉任意 `RuntimeException`；权限、网络或 SQL 错误可能被掩盖。发布时必须检查启动日志和实际列/索引，而不是只看进程是否启动。
- 运行时不使用分布式迁移锁；两个 JVM 同时对旧结构执行加列存在理论竞争。当前实测两 JVM 均启动成功，但正式发布仍建议“先单实例升级，再扩容”。
- `product_schema.sql`/`product_data.sql` 仅用于空库演示，不是重复执行的迁移脚本。
- H2 的 `continue-on-error: true` 可能隐藏 H2 初始化错误；H2 只用于本机演示，不作为生产迁移证据。
- 未验证 MySQL 主从/HA、TLS、最小权限账号、长时间备份锁和跨版本升级；当前只验证 MySQL 8.0.43 单实例。
- 多实例部署限制（只列事实，不补写能力）：已实测的是隔离库 + 缩短参数（TTL 30 秒、续约/回收 5 秒）下的双 JVM 互斥、fencing、跨实例取消、强杀后过期回收与终态释放锁（§5.3）；**未验证**的至少有——生产默认租约参数（任务 TTL 15 分钟、评测 TTL 10 分钟、续约/回收 30 秒、评测最长持有 30 分钟）下的长时运行与故障恢复、真实模型 Agent E2E、加锁表后的备份/恢复演练、完整生产 HA 与切换、TLS 与最小权限账号、运维监控告警接入、多实例压测与容量。多实例所有权成立不等于生产 HA 验收完成。
- 租约能力依赖 Spring 调度保持开启：`chainpilot.task-lease.*` / `chainpilot.task-recovery.*` / `chainpilot.evaluation-lease.*` 的续约与回收都由 `@Scheduled` 驱动，若部署关闭调度或所有实例同时停止，过期任务/评测会保持原状态直到任一实例再次启动；实例未显式配置 `chainpilot.instance-id` 时启动生成随机 UUID，滚动升级期间同一 Pod 重启会换新持有者，旧租约只能等过期回收。
- 备份包含模型密钥；不得提交备份文件或将其放入公开仓库。

## 7. 发布门禁

数据库发布前必须满足：

- 已生成并验证备份；
- 基础 schema 导入或旧库升级日志无异常；
- 一个实例完成升级后再扩容；
- 关键表行数/约束与发布前一致；
- 应用回退版本和数据库恢复步骤已演练；
- 已对目标库执行 `chainpilot-lease-ownership.sql` 并保存其 PRE-FLIGHT/POST-CHECK 输出（3 个所有权列存在、锁表仅 1 行 `AGENT_EVALUATION`、迁移前后任务状态分布一致）；不得只依赖应用启动的惰性加列；
- 扩容到多个实例前：三段迁移（业务 schema → 评测 schema → 租约所有权脚本）均已对该库执行并通过 POST-CHECK，各实例 `chainpilot.instance-id` 互不相同、连接同一数据库、Spring 调度保持开启，且运维已确认模型连接测试需要在每个实例上各自完成一次；
- 真实模型验收与完整浏览器回归按发布清单完成（当前真实模型仍为 BLOCKED）。
