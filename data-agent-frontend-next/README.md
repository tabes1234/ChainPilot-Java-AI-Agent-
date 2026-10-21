# ChainPilot Next.js 工作台

这是 ChainPilot 的独立 Next.js 前端。项目整体说明、后端启动、模型配置和测试命令见
[根目录 README](../README.md)。

## 本地启动

后端默认运行在 `http://localhost:8065`。本目录使用 Next.js 重写规则把 `/api/**` 和
`/uploads/**` 代理到 `CHAINPILOT_BACKEND_URL`（默认 `http://localhost:8065`）。

```bash
npm ci
npm run dev
```

打开 `http://localhost:3000`。

生产构建：

```bash
npm run build
npm run start -- -p 3002
```

如果后端使用其他端口：

```bash
CHAINPILOT_BACKEND_URL=http://127.0.0.1:8066 npm run dev
```

## 页面

- `/`：项目首页与能力概览
- `/supply-chain`：供应链分析、报告和事件
- `/supply-chain/decision`：自然语言决策与提案确认
- `/supply-chain/disposal`：情景推演、方案比较与沙箱
- `/supply-chain/history`：任务历史
- `/agent-evaluation`：评测中心、运行对比、指标和人工反馈
- `/agent-observability`：Agent 运行观测与诊断
- `/system/model-config`：模型配置与连接测试
- `/knowledge/*`、`/system/*`：上游 DataAgent 知识与系统管理能力

## 目录

```text
src/app/                  Next.js 路由
src/components/           共享组件、供应链报告和决策界面
src/lib/api/              REST API 客户端
src/lib/query/            TanStack Query hooks
src/lib/api/__tests__/    API 与 UI 契约测试
```

## 测试与检查

```bash
npm test
npx tsc --noEmit
npm run lint
npm run build
```

API 测试会读取源码做 HTTP 契约和 UI 安全边界检查；不会调用真实模型。

## 视觉与验收证据

历史视觉验收截图位于本目录 `.visual-check/`（本地证据，不一定纳入 Git），
可发布的精选截图位于 [`docs/assets/screenshots`](../docs/assets/screenshots)。
当前页面保留近白背景、白色光晕、主题切换和响应式布局；详细说明见根 README 和
[docs/SCREENSHOTS.md](../docs/SCREENSHOTS.md)。
