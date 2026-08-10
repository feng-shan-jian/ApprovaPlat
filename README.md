<div align="center">

# ApprovaPlat


一个基于 RuoYi、Flowable 8、Spring Boot 4 和 Vue 3，仍在持续演进的开源审批中台。

**中文** | [English](README_EN.md)

[快速开始](#快速开始) · [当前能力](#现在能做什么) · [当前边界](#当前边界) · [项目文档](docs/README.md)

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

当时我想找一个技术栈较新、基于 Flowable 8 的开源项目作为参考。找了一圈，相对完整的还是[芋道](https://github.com/YunaiV/ruoyi-vue-pro)，但它并不是 Flowable 8 项目。它的 README 写着“现在、未来都不会有商业版本，所有代码全部开源”，官方[工作流文档](https://doc.iocoder.cn/bpm/)中的 BPM SQL 却注明“仅芋道星球成员可使用和商用”。

为了继续看相关文档，我还用过 [`Fuck-Yudao`](https://github.com/AntHubTC/AntHubTC.github.io/blob/master/tampermonkey-script/Fuck-Yudao.js) 油猴脚本，也看到过第三方仓库[“芋道源码（无遮羞布版）”](https://github.com/talkpoin/ruoyi-spring-boot-all)。这也是我不认同的地方：既然把“所有代码全部开源”作为承诺，开放范围和收费边界就应该一开始说清楚。

找不到合适的项目，我就开始做 ApprovaPlat。它不是简单地替换 Flowable 版本，而是希望基于 Flowable 8，把设计、部署、发起、办理、权限、数据与审计完整串起来。

项目目前还在早期，核心代码、SQL、文档和测试会保持公开。欢迎各位大佬多提建议，也希望正在开发、准备开发或迁移审批系统的人，能从这里找到一些真正用得上的实现和经验。

## 项目现在是什么状态

ApprovaPlat 离成熟的审批中台还有不少工作。下面列的是仓库里已经实现的能力。

目前，流程设计、部署、发起、办理、历史和审计已经可以走通。流程由 Flowable 8 执行，业务数据写入 MySQL，登录状态和缓存使用 Redis。它已经能作为学习、验证和二次开发的基础，但还不适合未经目标环境验收就直接用于生产。

现在更适合这些场景：

- 学习 Flowable 8，以及一套审批系统如何把前端、后端、权限和数据接起来。
- 作为新审批项目的参考实现或二次开发基础。
- 为旧版 Flowable 或其他流程引擎的迁移做验证和能力对照。
- 在正式上线前开展试点，并按目标环境补齐部署、容量和故障验收。

## 现在能做什么

### 设计与发布

- 管理流程分类、动态表单、BPMN 模型、版本和部署状态。
- 在 BPMN.js 设计器中导入、导出、预览源码、执行 Lint 和 Token 模拟，并配置节点属性。
- 部署前由后端校验 BPMN；部署时冻结表单、扩展、DMN 与 SLA 快照，并自动挂起旧定义，在途实例继续运行。

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
- Flowable 数据和业务数据共用主数据源与事务边界，前端不保存第二份权威流程状态。
- 提供健康检查、运行快照、Micrometer/Prometheus 指标、附件清理锁和运行就绪校验。
- 仓库包含生产配置、systemd、Nginx、数据库只读验收和发布门禁资产。

## 项目截图

下面是一条在真实前端、后端、Flowable、MySQL 和 Redis 环境中完成的动态多人会签流程。页面重新读取实例状态和已经部署的 BPMN，并高亮实际走过的路径。

![ApprovaPlat 已完成动态多人会签实例](docs/assets/readme/process-trace.png)

## 快速开始

### 环境要求

- JDK 17+
- Maven 3.9+
- Node.js 20+
- npm 10+
- MySQL 8
- Redis 6+

### 1. 初始化数据库

创建一个空的 MySQL schema，并严格按照[数据库基线](docs/database/workflow-baseline.md)中的顺序执行 RuoYi、Quartz、Flowable 与 ApprovaPlat SQL。所有 MySQL 客户端连接都应显式使用 `utf8mb4`。

当前首发基线包含破坏性的全新安装脚本。不要对已有业务库直接执行，也不要开启 Flowable 自动建表来绕过缺失结构。

### 2. 配置并启动后端

在 PowerShell 中为当前进程设置本地环境变量，值由你自己的 MySQL 环境提供：

```powershell
$env:RUOYI_DB_URL = 'jdbc:mysql://127.0.0.1:3306/approvaplat?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia%2FShanghai'
$env:RUOYI_DB_USERNAME = '<应用账号>'
$env:RUOYI_DB_PASSWORD = '<应用密码>'
$env:DRUID_MONITOR_USERNAME = 'local'
$env:DRUID_MONITOR_PASSWORD = '<独立监控密码>'
$env:RUOYI_PROFILE = Join-Path $env:LOCALAPPDATA 'ApprovaPlat\uploads'

mvn -pl ruoyi-admin -am -DskipTests package
java -jar .\ruoyi-admin\target\ruoyi-admin.jar --server.address=127.0.0.1
```

`-DskipTests` 只用于缩短本地首次启动。未显式设置 `RUOYI_TOKEN_SECRET` 时，单节点环境会在用户私有目录中生成并复用随机 HS512 密钥；生产密钥与多节点要求见[部署文档](docs/operations/workflow-deployment.md)。

### 3. 启动前端

打开新的 PowerShell：

```powershell
Set-Location ruoyi-ui
npm ci
$env:VITE_OPEN_BROWSER = 'false'
npm run dev -- --host 127.0.0.1 --port 1024
```

访问 `http://127.0.0.1:1024`。全新本地基线账号为 `admin`，初始密码为 `wang`；它只用于本机开发，对外开放服务前必须更换。

如果希望快速体验仓库里的审批样例，可以按[审批样例置备](deployment/samples/workflow/README.md)通过平台 API 创建，不需要直接修改 Flowable 表或业务表。

生产安装请使用[工作流安装与运行](docs/operations/workflow-deployment.md)和[发布与回滚](docs/operations/workflow-release.md)，不要直接照搬本地启动命令。

## 开发与测试

常用开发验证：

```powershell
mvn clean verify

Set-Location ruoyi-ui
npm run test:contracts
npm run build:prod
```

真实 MySQL、Redis、角色、API 和浏览器验收需要额外环境变量与隔离库，执行方式见[测试与验收](docs/testing/workflow-acceptance.md)和 [E2E 说明](ruoyi-ui/tests/e2e/README.md)。

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
| 验证         | JUnit 5、Playwright、k6、数据库只读验收      |

## 仓库结构

```text
ApprovaPlat/
|- pom.xml       Maven 聚合入口
|- ruoyi-*/      Spring Boot 后端模块与 Flowable 领域模块
|- ruoyi-ui/     Vue 3 前端、契约测试与真实浏览器 E2E
|- sql/          数据库基线、业务结构与只读验收脚本
|- docs/         架构、业务契约、数据库、运维与验收文档
`- deployment/   生产配置、systemd、Nginx、样例与发布门禁
```

## 文档

| 想了解什么                 | 从这里开始                                                                                           |
| -------------------------- | ---------------------------------------------------------------------------------------------------- |
| 系统边界和数据流           | [审批平台架构](docs/architecture/workflow-platform.md)                                               |
| 每个审批动作的约束         | [审批业务行为契约](docs/contracts/workflow-behavior.md)                                              |
| Participant 与 MessageFlow | [多池协作运行契约](docs/contracts/workflow-collaboration.md)                                         |
| 空库安装和正式迁移         | [工作流数据库基线](docs/database/workflow-baseline.md)                                               |
| 安装、运行、发布和回滚     | [部署文档](docs/operations/workflow-deployment.md) / [发布文档](docs/operations/workflow-release.md) |
| 测试和真实环境验收         | [工作流测试与验收](docs/testing/workflow-acceptance.md)                                              |

## 参与项目

欢迎提交 Issue 和 Pull Request，也欢迎把真实审批场景、迁移问题和踩过的坑带进来。

如果准备增加新的 BPMN 元素或审批动作，请同时说明它如何编辑、如何执行、谁能操作、数据写到哪里、失败以后会留下什么，以及准备怎样验证。

## 上游项目与许可证

ApprovaPlat 建立在 [RuoYi-Vue](https://github.com/yangzongzhuan/RuoYi-Vue)、[Flowable](https://github.com/flowable/flowable-engine) 和 [bpmn.io](https://github.com/bpmn-io/bpmn-js) 等开源项目之上。

这是一个独立开源项目，不是上述项目的官方组件。代码使用 [MIT License](LICENSE)。

> 愿我们都能在 AI 时代找到自己的方向，做成想做的事，事业有成，一路顺遂。
