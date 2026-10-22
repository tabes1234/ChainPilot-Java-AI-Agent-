# 第三方资源说明（Third-Party Notices）

本文档记录 ChainPilot 实际使用的第三方资源、来源、用途与许可证。版本以当前
`pom.xml`（后端）与 `package.json` / `package-lock.json`（前端）为准。许可证信息
以各官方仓库声明的 LICENSE 为最终依据；本文无法完全核实或存在多许可方案的条目
已标注「待核实」。

> 上游基线：[spring-ai-alibaba/DataAgent](https://github.com/spring-ai-alibaba/DataAgent)，
> Apache License 2.0（见根目录 [LICENSE](../LICENSE)）。ChainPilot 在其应用骨架上扩展，
> 上游传递依赖未在本文逐一列出。

## 1. 后端（Maven，`data-agent-management/pom.xml`）

| 依赖 | 版本 | 用途 | 许可证 |
| --- | --- | --- | --- |
| Spring Boot（parent） | 3.4.8 | 应用框架、Web、校验、Actuator | Apache-2.0 |
| Spring AI | 1.1.2 | 模型抽象、Chat/Embedding、结构化输出 | Apache-2.0 |
| Spring AI Alibaba（dashscope / graph-core / sandbox） | 随 BOM | DashScope 模型接入、工作流图、沙箱 | Apache-2.0（以上游仓库 LICENSE 为准） |
| dashscope-sdk-java | 2.15.1 | 阿里云百炼模型 API SDK | Apache-2.0（待核实，以官方仓库为准） |
| mybatis-spring-boot-starter | 随 Spring Boot BOM | ORM 持久层 | Apache-2.0 |
| Druid | 随 BOM | 数据库连接池 | Apache-2.0 |
| H2 Database | 随 BOM | 本地演示/测试文件数据库 | EPL-1.0 / MPL-2.0 双许可 |
| mysql-connector-j | 随 BOM | MySQL 驱动（隔离集成测试与 MySQL 部署） | GPL-2.0-with-universal-FOSS-exception |
| postgresql / mssql-jdbc | 随 BOM | 数据源连通性支持（PostgreSQL / SQL Server） | BSD-2-Clause / MIT |
| ojdbc8 | 随 BOM | Oracle 数据源连通性支持 | Oracle Free Use Terms and Conditions（**待核实**，商业环境使用前需自行确认条款） |
| hive-jdbc / hive-llap-server | 随 BOM | Hive 数据源连通性支持 | Apache-2.0 |
| gpdb20160503 | 随 BOM | 阿里云 AnalyticDB 数据源支持 | Apache-2.0（待核实，以官方仓库为准） |
| guava / commons-lang3 | 随 BOM | 通用工具库 | Apache-2.0 |
| springdoc-openapi-starter-webflux-ui | 随 BOM | OpenAPI 文档 | Apache-2.0 |
| OpenTelemetry（api/sdk/otlp/autoconfigure) | 随 BOM | 运行观测链路导出 | Apache-2.0 |
| Micrometer / micrometer-observation | 随 BOM | 观测 API | Apache-2.0 |
| spring-ai-transformers | 1.1.2 | 本地 Transformer Embedding 能力（未内置模型权重） | Apache-2.0 |
| JUnit Jupiter / Testcontainers | 随 BOM | 单元/集成测试 | EPL-2.0 / Apache-2.0 |

说明：以上「随 BOM」条目的精确版本由 Spring Boot / Spring AI 依赖管理决定，可用
`mvn dependency:tree` 输出为准；本文不复制未核实的版本号。

## 2. 前端（npm，`data-agent-frontend-next/package.json`）

| 依赖 | 版本 | 用途 | 许可证 |
| --- | --- | --- | --- |
| next | 16.4.2 | App Router 框架、构建与生产服务 | MIT |
| react / react-dom | 19.3.0 | UI 运行时 | MIT |
| @tanstack/react-query | ^5.104.5 | 服务端状态与请求缓存 | MIT |
| tailwindcss / @tailwindcss/turbopack | ^4 | 样式系统 | MIT |
| lucide-react | ^1.60.0 | 图标（全部界面图标来源） | ISC |
| @base-ui/react | ^1.9.0 | 无头 UI 组件 | MIT（待核实，以仓库 LICENSE 为准） |
| @radix-ui/react-slot | ^1.5.0 | 组件组合原语 | MIT |
| react-hook-form | ^7.90.0 | 表单状态 | MIT |
| zod | ^4.6.7 | 运行时校验 | MIT |
| next-themes | ^0.4.6 | 主题切换 | MIT |
| sonner | ^2.0.8 | Toast 通知 | MIT |
| clsx / tailwind-merge | ^2.1.1 / ^3.7.0 | className 组合 | MIT |
| class-variance-authority | ^0.7.1 | 组件变体 | **待核实**（以官方仓库 LICENSE 为准） |
| tw-animate-css | ^1.4.0 | 动画工具类 | **待核实** |
| cn | ^0.5.0 | 样式工具 | **待核实** |
| shadcn（devDependencies） | ^4.22.1 | 组件生成 CLI（仅开发期使用） | Apache-2.0（待核实） |
| typescript / eslint / eslint-config-next | ^5 / ^9 / 16.4.2 | 类型检查与代码规范 | Apache-2.0 / MIT |
| vitest / jsdom | ^3.2.7 / ^26.1.0 | 单元测试 | MIT |

未在 `package.json` 中声明的库（例如动画库）未引入；页面动效全部由原生 CSS
Keyframes 实现。

## 3. AI 模型与模型服务

- 项目**不内置、不分发任何模型权重**。模型通过 OpenAI 兼容接口或 DashScope
  按用户自配的连接调用；API Key 仅存于后端模型配置（接口返回掩码），不写入仓库。
- 结构化风险评分、方案排名与评测断言全部由 Java 确定性规则计算，模型仅承担
  解释性叙述并接受校验；模型失败时回退确定性结果。
- 本地 Embedding（`spring-ai-transformers`）如启用，模型文件按配置从外部加载，
  仓库不包含权重文件。

## 4. 数据与示例数据

- 订单、库存、供应商数据为**项目内置的本地模拟数据**（SQL 初始化脚本），用于
  学习与演示，不来自真实企业系统，不包含个人信息。
- 评测案例集为人工核定的固定案例（见 `docs/` 与评测中心页面），项目自产。

## 5. 媒体与字体

- `docs/assets/screenshots/` 截图均为本项目自产。
- 界面图标全部来自 lucide-react（ISC），无下载的图片/音视频素材。
- 未自托管第三方字体，前端使用系统字体栈。

## 6. 许可证合规要点

- 根目录 LICENSE 为 Apache-2.0（继承上游 DataAgent）；二次分发需保留其声明与
  NOTICE 要求。
- H2（EPL/MPL 双许可）与 MySQL Connector/J（GPL + FOSS 例外）以各自官方条款为
  准，作为依赖使用通常不传染应用代码，但商业化再分发前建议复核。
- ojdbc8 为 Oracle 专有条款，随源码公开仓库再分发前需确认其当前再分发条款。
- 表中所有「待核实」条目在正式发布/商业使用前应逐一打开对应仓库核对 LICENSE 文本。
