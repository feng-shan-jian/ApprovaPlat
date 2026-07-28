# WorkflowTaskLifecycleService

## 作用

`WorkflowTaskLifecycleService` 实现任务完成、驳回、退回、取消流程和已办撤回。所有写动作都通过 `WorkflowEngineOperations.writeAsCurrentUser` 在同一 Spring 事务中重新解析登录身份、查询真实引擎状态、写入结构化 comment 并改变 Flowable 状态。

## 权限与状态

| 动作 | 对象级权限 | 关键状态门禁 |
| --- | --- | --- |
| 完成 | 当前用户必须是活动任务 assignee | 实例活动；PENDING 委派禁止直接完成；变量通过部署快照 schema；动态下一办理人仅允许唯一无条件直接后继用户任务 |
| 驳回 | 当前用户必须是任一活动任务 assignee | 冻结全部活动任务并整实例原子终止；普通、并行和多实例统一形成 `rejected` 终态 |
| 退回 | 当前用户必须是活动任务 assignee | 只允许单一安全串行 execution，目标必须来自同一事务实时重算的可退节点列表；多实例和并行来源返回 `409` |
| 查询可退节点 | 当前用户必须是活动任务 assignee | 与退回使用同一模型、历史和单一安全执行树门禁；多实例来源返回 `409` |
| 投影退回能力 | 当前用户必须是活动任务 assignee | 与查询可退节点复用完整准备链；至少存在一个合法目标才返回 `true`，预期 `400/403/404/409` 归一为 `false` |
| 取消 | 当前用户是根实例发起人，或命中若依超级管理员规则 | 根实例及 CallActivity 子实例必须全部活动且父子边界完整；取消原因写入各任务真实所属实例的 comment 和根历史删除原因 |
| 撤回 | 当前用户是指定历史任务的真实完成人 | 来源正常完成；仅允许一个直接用户任务后继，或由一个并行分流网关产生的安全直接用户任务后继；最多 100 个后继 |

服务不会在完成、驳回、退回或撤回前调用 `setAssignee`。任务对象已结束时，重复提交返回状态冲突；真正不存在的主键返回不存在。

## 变量

完成任务时从任务所属流程定义解析部署 ID、BPMN `formKey` 和节点 key，再从 `wf_deploy_form` 读取唯一不可变部署快照。客户端变量复用 `WorkflowStartVariableValidator` 的字段白名单、类型、大小、嵌套深度和保留字段门禁；客户端字段和表单 schema 均不得使用 `__ruoyi_workflow_` 服务端保留前缀。上传字段还会经 `WorkflowAttachmentService` 锁行校验和安全投影：当前用户可绑定自己的 `TEMP` 附件，也可复用同实例同字段的 `BOUND` 附件。节点声明 `localScope=true` 或 `localScope=1` 时，业务变量通过 Flowable 的局部变量完成重载。

部署表单快照只固化 schema；任务提交值另行编码为内部字符串变量 `__ruoyi_workflow_form_submission_v1`。快照包含 deployment、form、node、真实 task ID、业务字段是否 localScope 和本次安全投影值。无论业务字段使用全局还是局部作用域，内部提交快照始终调用 `TaskService.setVariableLocal` 写到当前任务，确保 `HistoricVariableUpdate.taskId` 能与本次提交强关联，并避免内部字段污染流程业务变量。

写入顺序固定为：生成审批 comment、绑定附件、写入 task-local 内部快照、调用显式携带当前用户 ID 的 `TaskService.complete`、应用并复核动态下一办理人、写入抄送记录。Flowable 8 由该 userId 写入历史任务 `completedBy`，已办列表、对象授权、撤回校验、导出和审计都以此为真实办理人依据。后续任务覆盖同名全局字段时，详情仍从各任务自己的内部快照返回提交当时的值；多个 localScope 任务的同名字段也不会串值。升级前已经完成但没有内部提交快照的任务，不会用最终全局变量伪造历史表单。

## 抄送与动态下一办理人

完成、驳回和退回可携带最多 100 个 `copyUserIds`。服务在动作前通过正式用户目录拒绝不存在、停用、删除、重复或非法用户，并以 `动作类型:来源任务ID:任务revision` 生成稳定事件键；引擎动作成功后才批量写入 `wf_copy`，数据库唯一键保证同一事件不能重复抄送同一用户。驳回会把同一结构化意见写入全部活动 sibling task，设置 `processStatus/businessStatus=rejected` 后调用 Flowable 整实例删除命令；写后重新核对运行实例已消失且历史业务终态为 `rejected`，任何漂移都会回滚。

退回只兼容普通安全串行任务并迁移唯一 execution。动态会签/或签的成员快照、revision 和模式以 activityId 保存在流程实例作用域，未实现轮次隔离前不能退回后再次进入同一节点；因此动态与静态、串行与并行多实例来源统一在 comment、抄送和引擎状态写入前返回 `409`。异步边界、子流程和无关并行分支同样拒绝，保证失败请求零副作用。

详情页的 `returnAllowed` 由 `isTaskReturnAllowed` 计算。该方法与 `/workflow/task/returnList` 复用办理人、运行实例、委派状态、BPMN 来源、唯一执行树和历史目标计算，不从 `multiInstanceState` 推断。权限、对象不存在和不支持结构只关闭入口；历史或执行数据损坏等 `500` 不会被隐藏。

完成请求可额外携带最多 100 个 `nextUserIds`。只有流程实例当前恰好一个活动任务，且 BPMN 当前节点只有一条无条件、非多实例的直接后继用户任务时才允许动态指定。单人选择写为 assignee，多人选择清空 assignee 并写为 candidate users；服务会删除 BPMN 静态候选用户和候选组，并在写后重新查询任务与 identity link 复核。并行、条件分支、网关、子流程边界、多实例或实际后继不一致均返回 `409`，不会部分完成任务。

## 撤回安全边界

撤回只接受一个活动直接用户任务后继，或由一个并行分流网关产生的 2 至 100 个活动直接用户任务后继。所有后继必须保持 Flowable `CREATED` 状态，且未认领、未开始、未委派、未挂起、未完成，不得存在附件、子任务或业务 comment。`userTaskListener` 在任务创建和初始分配时生成的受控 JSON 审计不视为人工办理副作用；其他 comment 即使类型相同也会使撤回返回 `409`。

多实例、子流程、`CallActivity`、timer、async、服务任务、边界事件、补偿、条件或拓扑歧义、同节点重复 execution、运行执行树与 BPMN 直接后继不一致均禁止撤回。服务在任何引擎写命令前完成上述检查；串行后继使用 `moveExecutionToActivityId`，安全并行后继使用一次 `moveExecutionsToSingleActivityId` 原子合并回来源节点。

引擎命令后必须重新查询并确认：实例仅存在一个恢复后的来源任务、活动节点只包含来源节点、所有原后继都进入已结束历史、每个原后继都写入关联来源任务的撤回 comment。任一结果不一致都会抛错并回滚整个 Spring 事务。

已办列表的 `revocable` 字段调用与正式撤回完全相同的只读准备路径。预期的参数、权限、对象不存在和状态冲突分别按 `400/403/404/409` 降级为 `false`；关联数据损坏等 `500` 不会被隐藏。该字段不代替提交校验，撤回命令仍在写事务中重新解析身份、重建计划、取得后继任务行锁并二次核验。

取消请求可传入根实例或活动 CallActivity 子实例 ID，但服务始终以根实例为业务授权和终止边界。服务在写入前冻结根实例及全部活动子实例，仅删除根实例以便 Flowable 级联结束子流程；写后重新核对整棵树不存在运行实例、execution 或 task，根历史 `businessStatus` 必须为 `canceled`。任何父子关系漂移、部分删除或终态不一致都会回滚整个 Spring 事务。

## 审计与事务

comment 类型继续兼容旧系统：完成 `1`、退回 `2`、驳回 `3`、取消 `6`、撤回 `7`。正文是服务端生成的 JSON，包含固定 `action`、当前 `actorUserId`、受控 `opinion`；退回只增加 `targetNodeKey`，撤回只增加 `sourceTaskId`，任务主键统一由 Flowable comment 外层关系保存。任务完成时 comment、附件实例/任务/节点绑定、内部提交快照、业务变量、动态下一办理人、抄送记录和引擎状态变更共享同一事务，任一步失败都会整体回滚。
