# ApprovaPlat 项目文档

本目录只保存能够长期约束项目实现和上线质量的正式文档。阶段计划、某次任务的执行过程、日期化进度和临时验收摘要不进入正式文档；这些信息应保存在任务系统或独立证据归档中。

## 文档入口

| 分类 | 文档 | 作用 |
| --- | --- | --- |
| 架构 | [architecture/workflow-platform.md](architecture/workflow-platform.md) | 定义审批平台定位、模块边界、数据流和安全边界 |
| 业务契约 | [contracts/workflow-behavior.md](contracts/workflow-behavior.md) | 定义审批动作、复杂 BPMN、附件、连接器和运行事件行为 |
| 协作契约 | [contracts/workflow-collaboration.md](contracts/workflow-collaboration.md) | 定义 Participant、MessageFlow 与多池消息运行语义 |
| 数据库 | [database/workflow-baseline.md](database/workflow-baseline.md) | 定义首个正式数据库基线、安装顺序和未来迁移规则 |
| 决策记录 | [project-clarifications.md](project-clarifications.md) | 记录已经由项目负责人确认的范围与治理结论 |

## 维护规则

- 每份文档只承担一种长期职责，不使用任务阶段编号或日期作为文件名。
- 文档描述当前正式目标，不保留已经被替代的实现方案和本地开发迁移历史。
- 代码、SQL 和自动测试提供可执行证据，文档说明其业务目的和使用边界。
- 如果文档、代码和测试出现冲突，必须先确认目标语义，再同步修改所有受影响资产，不能默认为任一方自动正确。
- 真实测试报告、环境身份、账号、密码、Token、备份文件和发布签字不得提交到 Git。
- 高复用前端组件的接入说明继续与组件源码同目录维护，不在本目录重复建立第二套组件手册。
