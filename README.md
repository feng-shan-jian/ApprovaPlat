<div align="center">

# ApprovaPlat


一个基于 RuoYi、Flowable 8、Spring Boot 4 和 Vue 3，覆盖设计、运行、权限、数据与审计的开源审批中台。

**中文** | [English](README_EN.md)

[快速开始](#快速开始) · [当前能力](#现在能做什么) · [生产验收边界](#生产验收边界) · [项目文档](docs/README.md)

[![Flowable 8.0.0](https://img.shields.io/badge/Flowable-8.0.0-2f855a?style=flat-square)](https://github.com/flowable/flowable-engine) [![Spring Boot 4.0.6](https://img.shields.io/badge/Spring%20Boot-4.0.6-6db33f?style=flat-square)](https://spring.io/projects/spring-boot) [![Vue 3.5.26](https://img.shields.io/badge/Vue-3.5.26-42b883?style=flat-square)](https://vuejs.org/) [![Project status](https://img.shields.io/badge/Status-Early%20Stage-f59e0b?style=flat-square)](#项目现在是什么状态) [![MIT License](https://img.shields.io/badge/License-MIT-1f2937?style=flat-square)](LICENSE)

</div>

<p align="center">
  <img src="docs/assets/readme/workflow-designer.png" width="100%" alt="ApprovaPlat 动态多人会签流程设计器" />
  <br />
  <sub>动态多人会签流程设计器</sub>
</p>

<table>
  <tr>
    <td width="33%" align="center"><strong>流程设计与发布</strong><br />BPMN、表单、版本与部署</td>
    <td width="33%" align="center"><strong>审批发起与办理</strong><br />会签、退回、撤回与审计</td>
    <td width="33%" align="center"><strong>工程化与迁移</strong><br />权限、数据、测试与运维</td>
  </tr>
</table>

## 为什么会有 ApprovaPlat

我在一次 B 端 AI 产品实习中接触到企业审批，也开始关注一套审批中台是怎样把流程、表单、权限和业务数据串起来的。

在调研 [RuoYi-Vue](https://github.com/yangzongzhuan/RuoYi-Vue)、[芋道](https://github.com/YunaiV/ruoyi-vue-pro)、[Flowable](https://github.com/flowable/flowable-engine) 和 [bpmn.io](https://github.com/bpmn-io/bpmn-js) 等项目后，我希望建立一套技术栈较新、开放范围清晰、代码与数据库资产完整公开的 Flowable 8 工程实现。

ApprovaPlat 因此以 Flowable 8 为运行核心，把设计、部署、发起、办理、权限、数据与审计完整串起来，并将业务规则、SQL、文档和验证入口放在同一仓库持续维护。

项目目前还在早期，核心代码、SQL、文档和测试会保持公开。欢迎各位大佬多提建议，也希望正在开发、准备开发或迁移审批系统的人，能从这里找到一些真正用得上的实现和经验。

## 项目现在是什么状态

ApprovaPlat 当前处于早期阶段。下面列出仓库里已经实现的能力。

目前，流程设计、部署、发起、办理、历史和审计已经可以走通。流程由 Flowable 8 执行，业务数据写入 MySQL，登录状态和缓存使用 Redis。它可以作为学习、验证和二次开发基础；生产使用以目标环境完成部署、容量、故障与恢复验收为准。

现在更适合这些场景：

- 学习 Flowable 8，以及一套审批系统如何把前端、后端、权限和数据接起来。
- 作为新审批项目的参考实现或二次开发基础。
- 验证 Flowable 版本升级或其他流程引擎迁移的目标能力。
- 在正式上线前开展试点，并按目标环境补齐部署、容量和故障验收。

## 现在能做什么

### 设计与发布

- 管理流程分类、动态表单、BPMN 模型、版本和部署状态。
- 在 BPMN.js 设计器中导入、导出、预览源码、执行 Lint 和 Token 模拟，并配置节点属性。
- 部署前由后端校验 BPMN；部署时冻结表单、扩展、DMN 与 SLA 快照，并自动挂起上一版本定义，在途实例继续运行。

### 发起与办理

- 提供新建流程、我的流程、待办、待签、已办和抄送工作台。
- 支持发起、认领、通过、委派、转办、退回、驳回、撤回、取消、终止和挂起等审批动作。
- 支持 ALL 会签、ANY 或签、动态加签与减签，并处理并发修改冲突。
- 保存提交表单与部署表单快照；附件具备权限、配额、绑定、下载、删除和物理清理能力。

### 复杂流程与集成

- 支持带退出条件和最大次数的重复审批，并记录每一轮运行轨迹。
- 支持 BPMN Error、Escalation、边界定时器、业务日历、审批 SLA 与 DMN 决策。
- 提供受控 Java/CEL、HTTP/SQL 连接器、集成凭据和运行事件入口。
- Participant、MessageFlow 与多池协作具备持久化、幂等、顺序、重试、死信和审计处理。

### 权限、数据与运维

- 区分设计、发起、办理、管理和审计职责，并对实例、任务、部署、附件和审计数据做对象级授权。
- Flowable 数据和业务数据共用主数据源与事务边界，后端运行态与正式数据库构成唯一权威流程状态。
- 提供健康检查、运行快照、Micrometer/Prometheus 指标、附件清理锁和运行就绪校验。

## 项目截图

下面是一条在真实前端、后端、Flowable、MySQL 和 Redis 环境中完成的动态多人会签流程。页面重新读取实例状态和已经部署的 BPMN，并高亮实际走过的路径。

![ApprovaPlat 已完成动态多人会签实例](docs/assets/readme/process-trace.png)

## 生产验收边界

- 首个生产数据库基线面向空 schema 安装；已安装 8.0.0 正式基线的环境通过 `8.0.1__workflow_mail_config.sql` 前向升级。
- 生产配置固定 `flowable.database-schema-update=false`，数据库结构统一通过仓库维护的 SQL 和运行核验更新。
- 正式网关入口覆盖排他、并行、包容和事件网关；业务重复审批使用受控整改循环，标准循环保留 XML 往返能力。
- 异步执行器在数据库、拓扑、容量、监控和唯一执行协调全部验收通过后启用，用于定时器、SLA 和后台任务。
- 多节点、共享附件存储、真实外部副作用、备份恢复与长时间稳定性在目标环境完成验收后进入生产发布。

精确业务边界见[审批业务行为契约](docs/contracts/workflow-behavior.md)和[多池协作运行契约](docs/contracts/workflow-collaboration.md)。

## 快速开始

### 环境要求

- JDK 17+
- Maven 3.9+
- Node.js 20+
- npm 10+
- Docker（包含 Docker Compose）

### 首次开发

在仓库根目录启动本地 MySQL 8 和 Redis：

```powershell
docker compose up -d
docker compose ps
```

首次创建 `approvaplat-mysql-data` Volume 时，MySQL 会严格按照[数据库基线](docs/database/workflow-baseline.md)自动执行仓库现有的十个 SQL 文件。等待 `docker compose ps` 中 MySQL 和 Redis 均显示 `healthy` 后再启动应用；已有 Volume 不会重复初始化。

安装前端依赖：

```powershell
Set-Location ruoyi-ui
npm install
```

在 IDEA 中打开 `ruoyi-admin`，运行 `RuoYiApplication`。本地开发无需设置数据库、Druid 或上传目录环境变量。

在 `ruoyi-ui` 目录启动前端：

```powershell
npm run dev
```

访问 `http://127.0.0.1:1024`。全新本地基线账号为 `admin`，初始密码为 `wang`；本机开发使用该初始凭据，对外开放服务前完成密码轮换。

### 日常开发

在仓库根目录执行：

```powershell
docker compose up -d
```

随后在 IDEA 中运行 `RuoYiApplication`，并在 `ruoyi-ui` 目录执行：

```powershell
npm run dev
```

### 日常停止与本地默认值

普通停止只执行：

```powershell
docker compose down
```

本地 MySQL 固定使用 `127.0.0.1:3306`、数据库 `approvaplat`、账号 `root`、密码 `root123`；Redis 使用 `127.0.0.1:6379` 且无密码。上传目录默认为 `${user.home}/.approvaplat/uploads`。

Druid Monitor 默认关闭。需要临时启用时设置 `DRUID_MONITOR_ENABLED=true`，访问 `http://localhost:8080/druid/`，本地默认账号为 `ap`、密码为 `123456`。

## 开发与测试

常用开发验证：

```powershell
mvn clean verify

Set-Location ruoyi-ui
npm run test:contracts
npm run build:prod
```

真实 MySQL `*IT` 使用独立的 opt-in Failsafe profile。CI 先准备专用 `approvaplat_it` schema，再显式提供以下三个环境变量；profile 在变量完整时连接该隔离 MySQL，变量缺失时直接失败。普通 `mvn test` / `mvn verify` 运行单元和本地集成测试：

```powershell
$env:WORKFLOW_MYSQL_TEST_URL = 'jdbc:mysql://127.0.0.1:3306/approvaplat_it?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia%2FShanghai'
$env:WORKFLOW_MYSQL_TEST_USERNAME = '<隔离验收库账号>'
$env:WORKFLOW_MYSQL_TEST_PASSWORD = '<隔离验收库密码>'

mvn -pl ruoyi-flowable -am -Pworkflow-mysql-it verify
```

真实 MySQL、Redis、角色和 API 验证需要准备对应的运行环境与隔离数据。

## 技术栈

| 领域         | 技术                                         |
| ------------ | -------------------------------------------- |
| 后端         | Java 17、Spring Boot 4.0.6                   |
| 流程与决策   | Flowable 8.0.0 Process / DMN                 |
| 数据         | MyBatis、MySQL 8、Redis                      |
| 前端         | Vue 3.5.26、Vite 6.4.3、Element Plus 2.13.1  |
| 设计器       | BPMN.js 18.22.0                              |
| 规则与连接器 | CEL、JSqlParser、受控 Java / HTTP / SQL      |
| 可观测性     | Spring Boot Actuator、Micrometer、Prometheus |
| 验证         | JUnit 5、前端契约测试与生产构建              |

## 仓库结构

```text
ApprovaPlat/
|- pom.xml       Maven 聚合入口
|- ruoyi-*/      Spring Boot 后端模块与 Flowable 领域模块
|- ruoyi-ui/     Vue 3 前端、工作流设计器与契约测试
|- sql/          数据库基线、业务结构与菜单权限
`- docs/         架构、业务契约与数据库文档
```

## 文档

| 想了解什么                 | 从这里开始                                                                                           |
| -------------------------- | ---------------------------------------------------------------------------------------------------- |
| 系统边界和数据流           | [审批平台架构](docs/architecture/workflow-platform.md)                                               |
| 每个审批动作的约束         | [审批业务行为契约](docs/contracts/workflow-behavior.md)                                              |
| Participant 与 MessageFlow | [多池协作运行契约](docs/contracts/workflow-collaboration.md)                                         |
| 空库安装和正式迁移         | [工作流数据库基线](docs/database/workflow-baseline.md)                                               |

## 参与项目

欢迎提交 Issue 和 Pull Request，也欢迎把真实审批场景、迁移问题和踩过的坑带进来。

如果准备增加新的 BPMN 元素或审批动作，请同时说明它如何编辑、如何执行、谁能操作、数据写到哪里、失败以后会留下什么，以及准备怎样验证。

## 上游项目与许可证

ApprovaPlat 建立在 [RuoYi-Vue](https://github.com/yangzongzhuan/RuoYi-Vue)、[Flowable](https://github.com/flowable/flowable-engine) 和 [bpmn.io](https://github.com/bpmn-io/bpmn-js) 等开源项目之上。

这是一个独立开源项目，代码使用 [MIT License](LICENSE)，并独立维护自身发布与支持边界。

> 愿我们都能在 AI 时代找到自己的方向，做成想做的事，事业有成，一路顺遂。
