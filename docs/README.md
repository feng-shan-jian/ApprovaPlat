# ApprovaPlat 项目文档

本目录按长期职责保存约束项目实现和上线质量的正式文档。任务系统和独立证据归档分别保存阶段计划、执行过程、日期化进度与验收摘要。

## 文档入口

| 分类 | 文档 | 作用 |
| --- | --- | --- |
| 架构 | [architecture/workflow-platform.md](architecture/workflow-platform.md) | 定义审批平台定位、模块边界、数据流和安全边界 |
| 业务契约 | [contracts/workflow-behavior.md](contracts/workflow-behavior.md) | 定义审批动作、复杂 BPMN、附件、连接器和运行事件行为 |
| 协作契约 | [contracts/workflow-collaboration.md](contracts/workflow-collaboration.md) | 定义 Participant、MessageFlow 与多池消息运行语义 |
| 数据库 | [database/workflow-baseline.md](database/workflow-baseline.md) | 定义首个正式数据库基线、安装顺序和未来迁移规则 |

## 维护规则

- 每份文档承担一种长期职责，文件名直接表达稳定主题。
- 文档描述当前正式实现；替代提交同时删除被替代方案和本地开发迁移历史。
- 代码、SQL 和自动测试提供可执行证据，文档说明其业务目的和使用边界。
- 文档、代码和测试出现冲突时，以项目负责人确认的目标语义为准，并在同一变更中同步全部受影响资产。
- 真实测试报告、环境身份、账号、密码、Token、备份文件和发布签字保存在受控证据系统；Git 只保存可公开复现的验证入口。
- 高复用前端组件的接入说明与组件源码同目录维护，并作为该组件的唯一手册。
