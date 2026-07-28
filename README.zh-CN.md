# ApprovaPlat

[English](README.md) | **简体中文**

基于 Flowable 8 与 Spring Boot 4 构建的企业级审批平台。

ApprovaPlat 聚焦 Flowable 8 在 Spring Boot 4 技术栈中的工程化集成与生产落地。希望可以完整覆盖流程设计、申请发起、任务审批、会签、退回、抄送、附件、权限隔离和审计追踪的完整审批应用。

希望为新项目选型、系统现代化升级和 Flowable 8 落地提供一套可运行、可验证、可持续演进的参考实现。

## 核心功能

- Flowable 8 流程执行与 Spring Boot 4 工程集成
- 流程建模、部署、版本管理与受控启停
- 申请发起、任务审批、认领、转办、退回与撤回
- 串行和并行多实例审批
- 候选用户、候选组与对象级权限校验
- 不可变表单快照与流程变量校验
- 附件访问控制、存储配额与生命周期管理
- 抄送、审批意见、操作日志与审计追踪
- 流程引擎数据与业务数据的事务一致性

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
`- deployment/   生产配置与发布门禁
```

## 环境要求

- JDK 17 或更高版本
- Maven 3.9 或更高版本
- Node.js 20.12 或更高版本
- npm 10 或更高版本
- MySQL 8
- Redis 6 或更高版本

## 测试账号

以下账号仅用于本地 ApprovaPlat 测试环境：

| 角色 | 账号 | 密码 |
| --- | --- | --- |
| 管理员 | `admin` | `aprova**` |
| 文控 | `document_controller` | `aprova**` |

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

## 许可证

[MIT License](LICENSE)
