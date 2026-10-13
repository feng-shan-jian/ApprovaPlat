# ApprovaPlat

基于 RuoYi、Flowable 8、Spring Boot 4 和 Vue 3 的开源B端流程中后台。

覆盖流程设计、发布、发起、办理、权限、数据与审计。

**中文** | [English](README_EN.md)

[运行效果](#运行效果) · [快速开始](#快速开始) · [目前支持](#目前支持) · [生产验收边界](#生产验收边界)

[![Flowable 8.0.0](https://img.shields.io/badge/Flowable-8.0.0-2f855a?style=flat-square)](https://github.com/flowable/flowable-engine)
[![Spring Boot 4.0.6](https://img.shields.io/badge/Spring%20Boot-4.0.6-6db33f?style=flat-square)](https://spring.io/projects/spring-boot)
[![Vue 3.5.26](https://img.shields.io/badge/Vue-3.5.26-42b883?style=flat-square)](https://vuejs.org/)
[![Status](https://img.shields.io/badge/Status-Early%20Stage-f59e0b?style=flat-square)](#生产验收边界)
[![MIT License](https://img.shields.io/badge/License-MIT-1f2937?style=flat-square)](LICENSE)

![ApprovaPlat 动态多人会签流程设计器](docs/assets/readme/workflow-designer.png)

## 项目简介

ApprovaPlat 是一套基于 Flowable 8 的中后台系统实现。

目前已经跑通：

```text
流程设计
  → 校验与部署
  → 流程发起
  → Flowable 执行
  → 任务办理
  → 历史与审计
```

流程、表单、权限、业务数据和运行状态统一在项目中维护，可用于 Flowable 8 学习、流程系统开发和二次开发参考。

## 为什么做这个项目

我在一次 B 端中后台产品设计与全栈实习中接触到制造业复杂业务场景，也开始关注一套生产级中后台是怎样把极其复杂的业务流程、表单、权限和业务数据需求转化为功能并使用技术串起来的。

在调研 [RuoYi-Vue](https://github.com/yangzongzhuan/RuoYi-Vue)、[芋道](https://github.com/YunaiV/ruoyi-vue-pro)、[Flowable](https://github.com/flowable/flowable-engine) 和 [bpmn.io](https://github.com/bpmn-io/bpmn-js) 等项目后，我希望建立一套技术栈较新、开放范围清晰、代码与数据库资产完整公开的 Flowable 8 工程实现。

ApprovaPlat 因此以 Flowable 8 为运行核心，把设计、部署、发起、办理、权限、数据与审计完整串起来，并将业务规则、SQL、文档和验证入口放在同一仓库持续维护。

项目目前还在早期，核心代码、SQL、文档和测试会保持公开。欢迎各位大佬多提建议，也希望正在开发、准备开发或迁移流程系统的人，能从这里找到一些真正用得上的实现和经验。

## 目前支持

### 流程设计与发布

* 流程分类、动态表单、BPMN 模型和版本管理。
* BPMN.js 流程设计器，支持导入、导出、源码预览、Lint、Token 模拟和节点配置。
* 后端 BPMN 部署校验。
* 部署时保存表单、扩展配置、DMN 和 SLA 快照。
* 新版本发布后挂起旧流程定义，在途实例继续运行。

### 发起与办理

提供：

* 新建流程
* 我的流程
* 待办
* 待签
* 已办
* 抄送

支持：

* 发起、认领、通过
* 退回、驳回、撤回
* 转办、委派
* 取消、终止、挂起
* ALL 会签、ANY 或签
* 动态加签、减签

多人审批和退回等场景包含 revision 冲突处理。

提交表单和部署表单保留对应快照；附件支持权限、配额、绑定、下载、删除和物理清理。

### 复杂流程与集成

目前包括：

* 排他、并行、包容和事件网关
* BPMN Error / Escalation
* Boundary Timer
* 业务日历
* SLA
* DMN 决策
* 带退出条件和最大次数的受控整改循环
* Participant / MessageFlow 多池协作
* CEL
* 受控 Java
* HTTP Connector
* SQL Connector

多池协作支持持久化、幂等、顺序控制、重试、死信和审计。

### 权限与运行

* 区分设计、发起、办理、管理和审计职责。
* 对实例、任务、部署、附件和审计数据进行对象级授权。
* Flowable 数据和业务数据共用主数据源与事务边界。
* 运行状态从 Flowable Runtime 和正式数据库读取。
* 支持重新加载实例、已部署 BPMN 和历史执行路径。
* 提供 Health Check、Runtime Snapshot、Micrometer / Prometheus 指标和运行就绪检查。

## 运行效果

### 采购申请表单

采购事项、金额、到货日期和报价附件，可在表单设计器中配置。

![采购申请表单设计：组件选择、画布与字段属性](docs/assets/readme/form-designer.png)

### 采购审批流程

采购初审、财务与法务并审、金额分支、定时等待和归档子流程。

![综合采购 BPMN 设计：并行审批、条件分支、定时等待与子流程](docs/assets/readme/procurement-designer.png)

### 发起表单与实例列表

填写采购申请，保存草稿或提交审批。

![流程发起：部署表单快照与采购金额填写示例](docs/assets/readme/process-start.png)

在“我的流程”中查询申请，查看当前环节和审批状态。

![我的流程：复杂审批实例的查询与完成状态](docs/assets/readme/process-instances.png)

### 流程执行轨迹

这条采购申请金额为 **3,000**，经财务与法务并审、大额审批、税务复核后归档。绿色标出实际经过的节点与连线。

![已完成的 3000 金额采购审批：大额分支与实际执行路径](docs/assets/readme/procurement-trace.png)

### 审批记录

按节点查看办理人、耗时、认领记录和审批意见。

![审批流转记录：采购初审、候选认领与审批意见](docs/assets/readme/approval-history.png)

### 动态多人会签

![ApprovaPlat 已完成动态多人会签实例](docs/assets/readme/process-trace.png)

## 快速开始

### 环境要求

* JDK 17+
* Maven 3.9+
* Node.js 20+
* npm 10+
* Docker（包含 Docker Compose）
* PowerShell 7.4+（运行仓库验证脚本，命令为 `pwsh`）

### 1. 启动 MySQL 和 Redis

先在当前 PowerShell 进程中配置已有开发环境的连接信息，再在仓库根目录执行启动命令：

```powershell
$env:APPROVAPLAT_MYSQL_VOLUME = '<已有数据卷名称，首次使用时选择新卷名>'
$env:APPROVAPLAT_MYSQL_ROOT_PASSWORD = '<该实例的管理员密码>'
$env:RUOYI_DB_USERNAME = '<开发账号，不能是 root>'
$env:RUOYI_DB_PASSWORD = '<开发账号密码>'
```

已有数据卷应使用原账号和密码；这些变量只在空卷初始化时创建账号，不会修改已有 MySQL 账号。

```powershell
docker compose up -d
docker compose ps
```

指定的数据卷首次初始化时，MySQL 会执行：

[初始化脚本](docker/mysql/init.sh)

等待 MySQL 和 Redis 均显示 `healthy`。已有 Volume 不会重复初始化。

### 2. 安装前端依赖

```powershell
Set-Location ruoyi-ui
npm ci
```

### 3. 启动后端

在 IDEA 中打开仓库根目录并导入根 `pom.xml`。在运行配置中设置同一组 `RUOYI_DB_USERNAME`、`RUOYI_DB_PASSWORD`，然后在 `ruoyi-admin` 中运行：

```text
RuoYiApplication
```

已打开的 IDEA 不会继承后来在 PowerShell 中设置的环境变量。

### 4. 启动前端

```powershell
npm run dev
```

访问：

```text
http://127.0.0.1:1024
```

本地初始账号：

```text
username: admin
password: wang
```

初始凭据仅用于本地开发，对外开放服务前请修改密码。

本地开发需从当前进程提供数据库账号和密码；Druid 和上传目录可使用默认配置。更多开发和测试说明见 [CONTRIBUTING.md](CONTRIBUTING.md)。

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

应用与验收共用本地 MySQL `127.0.0.1:13306/approvaplat`。应用账号和密码由当前进程的 `RUOYI_DB_USERNAME`、`RUOYI_DB_PASSWORD` 提供；Compose 还需明确指定已批准的 `APPROVAPLAT_MYSQL_VOLUME` 和 `APPROVAPLAT_MYSQL_ROOT_PASSWORD`。初始化只用于批准的空卷，不能重放到已有数据的库。Redis 使用 `127.0.0.1:6379` 且无密码。上传目录默认为 `${user.home}/.approvaplat/uploads`。数据库验收需先按 [CONTRIBUTING.md](CONTRIBUTING.md) 登记实例并显式授权本轮测试。

Druid Monitor 默认关闭。需要临时启用时设置 `DRUID_MONITOR_ENABLED=true`，访问 `http://localhost:8080/druid/`，本地默认账号为 `ap`、密码为 `123456`。

### 升级已有数据

当前运行时只读取 v2 工作流制品。包含 v1 部署资源的旧库应先停服，按[工作流制品迁移指南](docs/workflow-artifact-v2-migration.md)检查、备份并迁移，再启动新版本；不要用空库初始化 SQL 覆盖已有业务数据。

当前模块职责与必须保留的事务、权限和并发边界见[架构说明](docs/architecture.md)，已执行验证及适用范围见[验收记录](docs/verification-history.md)。

## 生产验收边界

ApprovaPlat 当前处于 **Early Stage**。

仍需在目标环境验证：

* 异步执行器、定时器与 SLA 的执行协调和监控
* 多节点部署与故障恢复
* 共享附件存储
* HTTP / SQL 等外部调用的幂等与异常恢复
* 容量、备份恢复与长时间稳定性

生产环境使用 `flowable.database-schema-update=false`，数据库结构由仓库 SQL 维护。异步执行器默认关闭，在数据库、拓扑、容量、监控和执行协调验收通过后启用。业务重复审批使用受控整改循环；标准循环保留 XML 往返能力。

## 开发与测试

在仓库根目录执行日常快速检查：

```powershell
pwsh -NoProfile -File ./bin/verify-rules.ps1 -Mode Quick
```

`Quick` 包含后端本地/H2 测试、前端 lint/契约/构建，以及架构和 diff 检查；它在 Maven 子进程中移除外部 MySQL/Redis 测试环境变量。真实 MySQL/Redis 和浏览器验收需使用各自入口及前置条件。直接执行 `mvn clean verify` 时，数据库测试仍按当前环境变量选择，详见 [CONTRIBUTING.md](CONTRIBUTING.md#验证)。

仅检查前端时：

```powershell
Set-Location ruoyi-ui
npm run lint
npm run test:contracts
npm run build:prod
```

现有测试覆盖 Flowable 核心流程、事务、权限、幂等与并发冲突、前端请求契约及浏览器场景。

MySQL 集成测试和浏览器回归的运行方式见 [CONTRIBUTING.md](CONTRIBUTING.md#验证)。

## 技术栈

| 领域     | 技术                                         |
| ------ | ------------------------------------------ |
| 后端     | Java 17、Spring Boot 4.0.6                  |
| 流程与决策  | Flowable 8.0.0 Process / DMN               |
| 数据     | MyBatis、MySQL 8、Redis                      |
| 前端     | Vue 3.5.26、Vite 6.4.3、Element Plus 2.13.1  |
| 设计器    | BPMN.js 18.22.0                            |
| 规则与连接器 | CEL、JSqlParser、受控 Java / HTTP / SQL        |
| 可观测性   | Spring Boot Actuator、Micrometer、Prometheus |
| 测试     | JUnit Jupiter 6.0.3、前端 Contract Test、Browser E2E       |

## 仓库结构

```text
ApprovaPlat/
├── pom.xml        Maven 聚合入口
├── ruoyi-*/       Spring Boot 后端与 Flowable 领域模块
├── ruoyi-ui/      Vue 3 前端、流程设计器与前端测试
├── sql/           数据库基线、业务结构与菜单权限
├── bin/           正式验证、环境登记与制品迁移工具
└── docs/          架构、验收、迁移说明及 README 图片
```

## 参与项目

欢迎提交 Issue 和 Pull Request。

Bug 建议附上运行环境、复现步骤、预期结果和实际结果；功能建议可以直接描述具体的审批场景。

开发规范、验证入口和提交要求见 [CONTRIBUTING.md](CONTRIBUTING.md)。

增加新的 BPMN 元素或审批动作时，请同时说明它如何配置、如何执行、谁能操作、数据保存在哪里，以及如何验证。

## 上游项目与许可证

ApprovaPlat 建立在以下开源项目之上：

* [RuoYi-Vue](https://github.com/yangzongzhuan/RuoYi-Vue)
* [Flowable](https://github.com/flowable/flowable-engine)
* [bpmn.io](https://github.com/bpmn-io/bpmn-js)

项目使用 [MIT License](LICENSE)。
