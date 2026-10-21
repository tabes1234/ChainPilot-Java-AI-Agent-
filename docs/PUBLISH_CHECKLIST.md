# GitHub 发布前检查清单

本清单记录 ChainPilot 最终验收轮后的发布状态（2026-10-21）。工作区已在本地提交，未推送远程。

## 1. 分支与历史

- 分支：`feature/chainpilot`
- ChainPilot 交付提交：`a18481c feat: deliver ChainPilot supply chain decision workbench`（当前 HEAD 以 `git log -1` 为准，后续可能还有文档提交）
- 历史提交：`c182790 chore: import DataAgent source snapshot`（上游源码导入快照）
- `origin` 指向 `https://github.com/spring-ai-alibaba/DataAgent.git`，不是个人 fork。
- 因此本轮只做本地提交，**没有 push**；不得把提交推到上游仓库，也不宣称已提交上游 PR。
- 发布到个人仓库前，应显式把 `origin` 指向自己的 fork/仓库，再单独确认推送。

## 2. Git 跟踪状态

- 本地提交包含 Next.js 工作台、后端供应链/评测/反馈模块、Nuxt 历史前端修改、文档、CI、Smoke Test、演示数据脚本与精选截图。
- `data-agent-frontend-next/` 已纳入 Git，包含 `package.json`、`package-lock.json`、`next.config.ts`、`tsconfig.json`、`src/**`、`public/**`、测试与 README。
- H2 运行库、`target/`、`node_modules/`、`.next/`、`.visual-check/`、日志和 `.env*` 均被忽略，未进入提交。
- 本轮提交后 `git status --short` 为空。

## 3. 应排除的内容

| 内容 | 状态 |
| --- | --- |
| `data-agent-frontend-next/node_modules/` | 已被 Next `.gitignore` 忽略 |
| `data-agent-frontend-next/.next/` | 已被 Next `.gitignore` 忽略 |
| `data-agent-frontend-next/.visual-check/` | 本地验收证据，不提交；精选图在 `docs/assets/screenshots/` |
| `data-agent-frontend-next/*.log` | 已通过 `*.log` 忽略 |
| H2 `data/`、`*.mv.db`、`*.trace.db` | 已忽略，未提交 |
| `.env*` | Next `.gitignore` 已忽略；未发现实际文件 |
| `target/`、`logs/`、`checkstyle-report.xml` | 构建产物，不提交 |
| 真实 API Key、数据库密码、Authorization | 未发现；不要写入仓库或截图 |

`data-agent-frontend-next/AGENTS.md` 作为项目内代理说明已随源码提交，内容不含凭据。
秘密扫描仅命中既有的合成测试串：

- `data-agent-management/src/test/java/.../util/ApiKeyUtilTest.java`
- `data-agent-management/src/test/java/.../security/ApiKeyCredentialServiceTest.java`
- `docs/superpowers/plans/2026-04-02-tdd-test-implementation.md`

这些是虚构的 `sk-` 示例，不是真实凭据。未发现 `.env` 文件或真实 Key。

## 4. 许可证与归因

- 根目录 `LICENSE` 为 Apache License 2.0，必须保留。
- 当前未发现独立 `NOTICE`；不要补写或伪造不存在的版权声明。
- README 与 [UPSTREAM_AND_CONTRIBUTION.md](UPSTREAM_AND_CONTRIBUTION.md) 已列明上游仓库、采用基础和扩展边界。
- 保留上游源码版权头；不把上游 Agent、Chat、知识库、模型配置基础宣称为本项目从零原创。
- 不声称已向上游提交 PR。

## 5. 验证记录（本轮实际执行）

| 项目 | 结果 |
| --- | --- |
| 后端 H2 全量 | 1963/1963 通过 |
| 前端 Vitest | 100/100 通过 |
| TypeScript / ESLint / Next build | 通过 |
| 全站路由巡检 | 17/17 打开，0 控制台错误、0 个 HTTP 4xx/5xx |
| 可见交互巡检 | 35/35 通过（筛选、分页、轨迹、观测、模型校验、聊天门禁、反馈刷新） |
| 完整写链路 | 18/18 通过（分析 → 决策 → 对比 → 模拟 → 方案 → 沙箱 → 刷新 → 观测 → 评测 → 反馈） |
| 异常与重复提交 | 缺失任务/方案/评测、模式错配、空参数、双击提交均通过；缺失方案不再请求派生资源；候选方案双击只写 1 条 |
| 342px 移动端 | 9 个主页面无水平溢出 |
| Smoke Test | 10 PASS / 1 INFO / 1 BLOCKED / 0 FAIL |
| 独立启动 | PowerShell `mvnw.cmd` + Next 生产启动通过；重启后旧任务/方案/执行/评测/反馈可读 |
| 演示数据初始化 | 隔离空 H2 首次创建完整链路，第二次运行 createdCount=0；浏览器 12/12 通过 |
| MySQL IT | 上一阶段 23/23；本轮无 schema 变更，未重跑 |

后端命令使用 JDK 17 和 `test-compile` + `surefire:test`，避免 JaCoCo agent 在非 ASCII 工作区路径下失败；执行同一批 JUnit 测试，不跳过测试。

## 6. CI 现状

新增 `.github/workflows/chainpilot-ci.yml`：

- JDK 17：`test-compile` + `surefire:test`（H2）；
- Node 22 + `npm ci`、`npm test`、`npx tsc --noEmit`、`npm run lint`、`npm run build`；
- 不包含 MySQL、外部模型、密钥或真实连接测试。

上游原有 job 多数限定在 `spring-ai-alibaba/DataAgent` 仓库，且 `frontend-check.yml` 仍指向历史 Nuxt 前端。新增工作流已在本地按等价命令验证，但**尚未在 GitHub Actions 上实际运行**；发布到个人仓库后需要手动触发一次。

## 7. 发布前仍需人工决定

1. 把 `origin` 改为个人 GitHub fork/仓库，并确认是否推送 `feature/chainpilot`。
2. 是否在新仓库启用 `chainpilot-ci.yml` 并手动触发首次运行。
3. 是否配置仓库描述、Topics、演示部署地址和截图。
4. 是否保留历史 Nuxt 前端与 `docs/superpowers/` 上游材料。
5. 是否提供真实模型凭据并明确授权费用；在此之前保留“真实模型未 E2E”的限制说明。

## 8. 真实模型 E2E 状态

当前模型配置为空，真实模型 Agent E2E 仍被阻塞。已完成的证据是安全预检、Mock/离线回归、评测、反馈、观测和确定性供应链闭环；未完成的是真实供应商连接、真实 Agent Workflow、`MODEL_VERIFIED` 和真实 usage/费用验证。完成它需要配置并激活模型、手动连接测试、选择最小案例并明确授权费用。
