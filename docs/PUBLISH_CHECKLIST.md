# GitHub 发布前检查清单

本清单记录的是一个**未提交工作区**的发布准备状态。本轮不会自动 stage、commit 或 push。

## 1. 分支与历史

- 分支：`feature/chainpilot`
- HEAD：`c182790 chore: import DataAgent source snapshot`
- 本地只有单一导入提交，没有完整的上游提交历史。
- `origin` 指向 `https://github.com/spring-ai-alibaba/DataAgent.git`。
- 没有可核实的上游 PR 或合并记录；README/归因文档不得宣称已向上游提交贡献。

## 2. Git 跟踪状态（审计快照）

审计时工作区约有：

- 54 个已跟踪修改文件；
- 61 个未跟踪文件/目录（包含新增文档、脚本、CI、Next.js 目录等）；
- 27 个已跟踪的 Nuxt 相关修改文件（属于早前前端阶段，本阶段未修改）。

发布前请重新执行：

```bash
git status --short
git status --ignored --short
```

重点：

- `data-agent-frontend-next/` 整个目录目前**未跟踪**，包含当前 Next.js 工作台、测试、`package-lock.json` 和文档。
  其 126 个源/配置文件（排除 `node_modules`、`.next`、`.visual-check`）均为可跟踪候选，主要包括：
  `package.json`、`package-lock.json`、`next.config.ts`、`tsconfig.json`、`eslint.config.mjs`、
  `src/app/**`、`src/components/**`、`src/lib/**`、`public/**`、`README.md`。
- `data-agent-frontend-nuxt/` 被 Git 跟踪，既有上游基础也有早前 ChainPilot 修改；发布时需要决定是否保留为历史前端。
- 当前 README、架构文档、脚本、CI 和求职材料是新增或修改内容，需人工选择纳入版本控制。
- 本轮不自动执行 `git add`。

## 3. 应排除的内容

| 内容 | 状态/建议 |
| --- | --- |
| `data-agent-frontend-next/node_modules/` | 已被 Next `.gitignore` 忽略 |
| `data-agent-frontend-next/.next/` | 已被 Next `.gitignore` 忽略 |
| `data-agent-frontend-next/.visual-check/` | 已在 Next `.gitignore` 中忽略；保留本地证据，发布精选图到 `docs/assets/screenshots/` |
| `data-agent-frontend-next/dev-*.log`、`prod-server.log` | 已在 Next `.gitignore` 中通过 `*.log` 忽略 |
| `data-agent-frontend-next/AGENTS.md` | 本地代理说明；建议排除或人工审查 |
| `docs/superpowers/` | 已跟踪的上游学习/计划材料；含虚构测试片段，发布前人工决定去留 |
| H2 `data/`、`*.mv.db`、`*.trace.db` | 与本地运行数据相关，不应发布 |
| `.env*` | Next `.gitignore` 已忽略；后端密钥使用环境变量 |
| `target/`、`logs/`、`checkstyle-report.xml` | 构建/检查产物，不应发布 |
| 临时数据库、Chrome profile、验收脚本 | 位于系统临时目录或 `.visual-check`，不应发布 |
| 真实 API Key、数据库密码、Authorization、URL 凭据 | 禁止出现在仓库、文档、日志和截图 |

`docs/assets/screenshots/` 的精选截图不含密钥或数据库密码；`model-readiness.png`
展示“未配置模型”的阻塞状态，不是真实模型成功证据。

秘密扫描（路径级）只命中 3 个既有的合成测试/学习夹具文件：

- `data-agent-management/src/test/java/.../util/ApiKeyUtilTest.java`
- `data-agent-management/src/test/java/.../security/ApiKeyCredentialServiceTest.java`
- `docs/superpowers/plans/2026-04-02-tdd-test-implementation.md`

这些是虚构的 `sk-` 测试字符串，不是真实凭据；发布前应确认扫描器白名单或移除学习计划中的示例。
未发现 `.env` 文件，也没有把任何真实 Key 写入文档或截图。

## 4. 许可证与归因

- 根目录 `LICENSE` 为 Apache License 2.0，必须保留。
- 当前未发现独立 `NOTICE`；不要补写或伪造不存在的版权声明。
- README 和 [UPSTREAM_AND_CONTRIBUTION.md](UPSTREAM_AND_CONTRIBUTION.md) 已列出上游仓库、
  采用的基础和本项目的扩展边界。
- 保留上游源码版权头，不删除/替换原作者信息。
- 不把上游 Agent、Chat、知识库、模型配置基础宣称为本项目从零原创。
- 不声称已向上游提交 PR。

## 5. CI 现状

上游已有 `.github/workflows/build-and-test.yml`、`frontend-check.yml` 等，但：

- 主要 job 通过 `github.repository == 'spring-ai-alibaba/DataAgent'` 限制在上游仓库；
- `frontend-check.yml` 指向历史 Nuxt 前端；
- 上游 `live-model-test.yml` 需要外部模型 secrets，不能作为默认 CI。

本轮新增 `.github/workflows/chainpilot-ci.yml`：

- JDK 17：`test-compile` + `surefire:test`（H2），避免 JaCoCo agent 在部分非 ASCII 路径失败；
- Node 22 + `npm ci`、`npm test`、`npx tsc --noEmit`、`npm run lint`、`npm run build`；
- 不包含 MySQL、外部模型、密钥或真实连接测试。

YAML 已用本地解析器校验，但**尚未在 GitHub Actions 上实际运行**。发布前应在目标仓库手动触发一次。

## 6. 本地发布前验证

```bash
# 后端 H2
bash mvnw -B -pl data-agent-management test

# 前端
cd data-agent-frontend-next
npm ci
npm test
npx tsc --noEmit
npm run lint
npm run build
```

MySQL IT 需要隔离库或 Testcontainers，不应在开发数据库上执行：

```bash
bash mvnw -pl data-agent-management surefire:test \
  -Dtest="SupplyChainMySqlPersistenceIT,SupplyChainMySqlHttpIntegrationIT,EvaluationMySqlPersistenceIT,EvaluationMySqlLegacyUpgradeIT,FeedbackMySqlPersistenceIT" \
  -Dchainpilot.mysql.integration=true \
  -Dchainpilot.mysql.url="jdbc:mysql://127.0.0.1:3306/chainpilot_mysql_it?useSSL=false&allowPublicKeyRetrieval=true" \
  -Dchainpilot.mysql.username=YOUR_TEST_USER \
  -Dchainpilot.mysql.password=YOUR_TEST_PASSWORD
```

真实模型连接测试和 AGENT E2E 不是发布默认步骤；必须由用户配置凭据并明确授权费用。

## 7. 人工决定项

发布到个人 GitHub 仓库前，需要人工确认：

1. 是否提交当前 Next.js 目录及其 `package-lock.json`。
2. 是否保留、归档或移除修改过的 Nuxt 前端。
3. 是否提交 `docs/assets/screenshots` 精选截图（不要提交 `.visual-check` 全量证据）。
4. 是否排除日志、AGENTS.md、H2 文件和临时脚本。
5. 是否将上游 README 的徽章/社区链接调整为个人仓库说明，同时保留上游归因和 LICENSE。
6. 是否配置 GitHub Actions、仓库描述、Topics 和演示部署地址。
7. 是否仍保留“真实模型未 E2E”的限制说明，不能为发布而删除。

## 8. 真实模型 E2E 状态

当前模型配置为空，真实模型 Agent E2E 仍被阻塞。发布材料必须明确：

- 已完成的是安全预检、Mock/离线回归、评测与观测能力；
- 未完成的是真实供应商模型连接、真实 Agent Workflow、真实 `MODEL_VERIFIED` 和真实 usage/费用验证；
- 完成它需要用户配置并激活模型、手动连接测试、选择最小案例并明确授权费用。
