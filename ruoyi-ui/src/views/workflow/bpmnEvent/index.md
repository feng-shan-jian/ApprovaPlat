# 错误、升级与审批 SLA 管理页

## 页面作用

管理正式 BPMN Error/Escalation 编码和审批 SLA 业务日历，查询 BPMN 运行审计、SLA 执行状态及 SLA 生命周期审计。

## 权限

- `workflow:bpmnEvent:list`：进入页面和查询目录。
- `workflow:bpmnEvent:add`：新增稳定编码。
- `workflow:bpmnEvent:edit`：维护名称、通知策略和启停状态。
- `workflow:bpmnEvent:audit`：查询运行审计。
- `workflow:sla:list`：查询业务日历和当前权限范围的 SLA 执行状态。
- `workflow:sla:add`：新增稳定业务日历。
- `workflow:sla:edit`：维护日历规则和启停状态，稳定编码发布后不可修改。
- `workflow:sla:audit`：查询 SLA 创建、提醒、升级、暂停、恢复和完成审计。

## 关键约束

- 类型与编码发布后不可修改，只能停用；历史部署继续使用部署时冻结值。
- 设计器和新部署目录只返回启用编码，已部署流程继续读取冻结快照。
- 审计与 Flowable 历史同时存在：Flowable 记录活动轨迹，本页记录业务编码、来源、捕获结果和中断语义。
- 业务日历使用 IANA 时区、ISO 工作周序号和工作时段，节假日/补班按自然日覆盖；设计器只能选择启用日历。
- SLA 执行和审计均来自正式数据库。
- 暂停和恢复会在运行时冻结并顺延提醒与升级计划，页面通过执行和审计页签回显最终一致状态。
- BPMN 运行审计、SLA 当前执行和 SLA 生命周期审计分别维护独立的服务端分页状态，默认每页 20 条，最大 100 条。
- 三个运维页签均支持关键标识、状态或类型、时间范围筛选，后端返回标准 `rows/total`，翻页时保留当前筛选条件。
