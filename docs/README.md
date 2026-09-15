# ApprovaPlat 项目文档

本目录说明项目架构、业务契约和数据库安装升级。开发环境、验证命令与贡献方式见[贡献指南](../CONTRIBUTING.md)。

## 文档入口

| 分类 | 文档 | 作用 |
| --- | --- | --- |
| 架构 | [architecture/workflow-platform.md](architecture/workflow-platform.md) | 定义审批平台定位、模块边界、数据流和安全边界 |
| 业务契约 | [contracts/workflow-behavior.md](contracts/workflow-behavior.md) | 定义审批动作、复杂 BPMN、附件、连接器和运行事件行为 |
| 协作契约 | [contracts/workflow-collaboration.md](contracts/workflow-collaboration.md) | 定义 Participant、MessageFlow 与多池消息运行语义 |
| 扩展管理 | [contracts/workflow-extension-deletion.md](contracts/workflow-extension-deletion.md) | 定义扩展目录读取、删除核验和最终删除边界 |
| 数据库 | [database/workflow-baseline.md](database/workflow-baseline.md) | 说明数据库结构约束和附件上传升级规则 |
| 浏览器回归 | [工作流浏览器回归](../ruoyi-ui/tests/e2e/README.md) | 说明组件回归、登录集成回归及运行命令 |

## 维护规则

- 每份文档承担一种长期职责，文件名直接表达稳定主题。
- 文档描述当前正式实现；替代提交同时删除被替代方案和本地开发迁移历史。
- 代码、SQL 和自动测试提供可执行验证入口，文档说明其业务目的和使用边界。
- 文档、代码和测试出现冲突时，以项目负责人确认的目标语义为准，并在同一变更中同步全部受影响资产。
- Git 跟踪源码、构建配置、正式测试及夹具、数据库脚本、许可证、产品说明图片和面向使用者与贡献者的文档。
- 任务安排和问题进展通过 Issue、Pull Request 及提交记录维护。测试运行产物写入已忽略的输出目录；本机凭据和环境配置保存在本地。
- 方法、参数和内部实现约束由代码类型与必要注释维护；跨模块协议在本目录集中说明，相关变更同步更新。
