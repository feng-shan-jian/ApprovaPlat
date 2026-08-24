# WorkflowTaskReturnApplicationService

## 作用

该服务是普通退回、受控多实例整组退回和退回能力判断的命令入口。重提由独立的 `WorkflowApplicationResubmitApplicationService` 承担，本服务不再装配表单、附件或重提依赖。

## 路由边界

- 普通串行任务：服务端确定真实首审批节点，由 `WorkflowReturnedTaskStateService` 冻结 BPMN 生成的办理配置，迁移为唯一申请人任务，并维护 `returned` 双状态。
- 受控多实例：取得 `MultiInstanceGroupReturnPlan`，单次调用迁移服务原子完成执行树、退回双状态、轮次 CAS 和 SLA；本应用服务只复用返回的申请人任务主键编排审计、抄送和通知。

## 退回写链

1. 校验请求、当前办理人、运行状态与服务端 BPMN 安全路径。
2. 写审计与迁移标记。
3. 执行普通迁移或受控整组迁移。
4. 普通路径写 `returned` 双状态；整组路径在迁移边界内完成状态、SLA、轮次 CAS 和对账。
5. 按原顺序处理抄送和稳定通知。

所有公开方法继续接收原 DTO，并通过 `WorkflowEngineOperations` 建立唯一外层事务或只读快照。客户端仍不能指定成员、模式或目标节点。
