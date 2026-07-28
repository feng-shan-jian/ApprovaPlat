# ApprovaPlat

[English](README.md) | **简体中文**

基于 Flowable 8 与 Spring Boot 4 构建的企业级审批平台。

ApprovaPlat 聚焦 Flowable 8 在 Spring Boot 4 技术栈中的工程化集成与生产落地。它不是一个仅能演示流程流转的示例项目，而是覆盖流程设计、申请发起、任务审批、会签、退回、抄送、附件、权限隔离和审计追踪的完整审批应用。

ApprovaPlat 希望为新项目选型、系统现代化升级和 Flowable 8 落地提供一套可运行、可验证、可持续演进的参考实现。

## 为什么选择 ApprovaPlat

许多工作流项目止步于流程引擎基础 API 演示，而生产级审批平台还必须在并发和异常场景下保证引擎状态、业务状态、权限、附件与审计记录一致。

ApprovaPlat 将这些问题作为核心能力实现：

- Flowable 8 流程执行与 Spring Boot 4 工程集成
- 流程建模、部署、版本管理与受控启停
- 申请发起、任务审批、认领、转办、退回与撤回
- 串行和并行多实例审批
- 候选用户、候选组与对象级权限校验
- 不可变表单快照与流程变量校验
- 附件访问控制、存储配额与生命周期管理
- 抄送、审批意见、操作日志与审计追踪
- 流程引擎数据与业务数据的事务一致性
- 真实服务集成测试与生产发布门禁

## 技术栈

| 领域 | 技术 |
| --- | --- |
| 后端 | Spring Boot 4.0.6、Java 17 |
| 工作流 | Flowable 8.0.0 |
| 持久化 | MyBatis、MySQL 8 |
| 安全 | Spring Security、JWT、Redis |
| 前端 | Vue 3、Vite、Element Plus |
| 流程设计器 | BPMN.js |
| 可观测性 | Spring Boot Actuator、Micrometer、Prometheus |
| 验证 | JUnit 5、Playwright、k6 |

## 项目结构

```text
ApprovaPlat/
|- back/         Spring Boot 后端与数据库脚本
|- vite/         Vue 3 前端
|- deployment/   生产配置与发布门禁
|- docs/         架构、安装、验收和回滚文档
`- testcount/    受控的本地测试账号文档
```

## 环境要求

- JDK 17 或更高版本
- Maven 3.9 或更高版本
- Node.js 20.12 或更高版本
- npm 10 或更高版本
- MySQL 8
- Redis 6 或更高版本

## 构建

构建后端：

```powershell
cd back
mvn clean package -DskipTests
```

构建前端：

```powershell
cd vite
npm install
npm run build:prod
```

数据库初始化、运行配置、首位管理员初始化、部署、验收和回滚必须遵循完整生产流程。禁止将真实凭据写入受 Git 管理的配置文件。

## 文档

- [Flowable 8 生产文档](docs/workflow-production/README.md)
- [全新安装手册](docs/workflow-production/04-fresh-install-runbook.md)
- [发布与回滚手册](docs/workflow-production/05-release-rollback-runbook.md)
- [Flowable 8 数据库脚本](back/sql/flowable/README.md)
- [发布门禁脚本](deployment/scripts/workflow-release-gate.sh)
- [负载与生命周期验收](deployment/k6/README.md)

## 许可证

ApprovaPlat 使用 [MIT License](LICENSE)。
