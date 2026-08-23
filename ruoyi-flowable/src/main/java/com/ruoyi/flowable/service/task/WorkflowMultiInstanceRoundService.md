# WorkflowMultiInstanceRoundService

## 作用

`WorkflowMultiInstanceRoundService` 在 Flowable 当前 Spring 事务中维护
`wf_multi_instance_round`。Flowable 变量仍是实时执行事实，业务表只保存每轮有序成员、
模式、revision、根 execution 和生命周期时间，用于审计以及后续整组重建。
所有生命周期时间均由 MySQL `current_timestamp(3)` 生成，应用节点时钟不参与持久化，
从而保证快速状态转换也不会因 JVM 与数据库时钟偏差违反时间顺序约束。

本服务不进入 `WorkflowMultiInstanceHandler`。handler 继续只负责四类成员来源解析与
Flowable 变量初始化；轮次生命周期由固定 `WorkflowUserTaskListener` 的 `create`、
`complete` 事件接入，`assignment` 不写轮次。

## 事件接入

- `create`：参与者规则解析完成后定位任务 execution 的直接多实例根。首次根创建
  `ACTIVE` 第 1 轮；同根加签任务只核对并复用既有轮次。相同实例、相同节点的新根仅在
  旧轮关闭后创建 `round_no + 1`。
- `complete`：正式完成链已经先把 Flowable revision 推进一位。监听器读取本次完成前的
  `nrOfInstances`、`nrOfActiveInstances`、`nrOfCompletedInstances`：ALL 仅最后一个活动
  实例完成整组，ANY 任一实例完成整组。监听器核验 task-local 预留标记及引擎、业务表
  revision 已一致；部分完成保持 `ACTIVE`，整组完成再以当前 revision 把轮次转为
  `COMPLETED`。
- `assignment`：只保留原有身份审计、SLA、抄送和通知，不读取或更新轮次。
- Flowable 原生中断：全局监听器只在当前 `CommandContext` 证明
  `ACTIVITY_CANCELLED`/`MULTI_INSTANCE_ACTIVITY_CANCELLED` 的 execution 是多实例根时关闭
  `ACTIVE` 轮次；ANY 正常结束只取消 sibling child，不会进入根关闭。内部删除的
  CallActivity 子流程还会在 `PROCESS_CANCELLED` 且实例实体已标记 deleted 时，按
  `round_id + expected revision + source status` 逐行关闭残余 `ACTIVE/RETURNED`，并锁定复核
  该实例已无开放轮次。非中断边界不删除根或子流程，因此保持 `ACTIVE`。
- 流程取消、驳回或管理员终止：三个公开入口统一进入
  `WorkflowProcessInstanceService.terminateRootProcessInstance(...)`。取得 Flowable 写锁且执行树
  仍存在时，服务先用非锁查询冻结全部开放轮次，并按 execution 图识别每个活动受控根，
  严格核对部署、定义、实例、节点、根、模式、有序成员、revision 和 `ACTIVE` 状态。根删除
  返回后才按冻结树锁定完全相同的行集合并转为 `TERMINATED`；`terminate_time` 使用数据库
  时钟，`RETURNED` 轮次保留已有退回审计字段。

## 正式读取对账

`WorkflowMultiInstanceService` 每次查询、加签、减签和完成预检都会调用
`requireActiveRound(...)`，逐项核对：

- 流程定义、部署、流程实例和活动节点；
- 当前多实例根 `root_execution_id`；
- 固定 ALL/ANY 模式；
- 有序成员 JSON 与 Flowable 成员变量；
- 业务 revision 与 Flowable revision；
- 同实例节点恰好一条开放且一条 `ACTIVE` 轮次。

缺行、重复行、非法 JSON、生命周期字段组合或任一字段漂移均返回服务端数据异常，运行时
不会猜测、补行或回填历史实例。

## CAS 与事务顺序

加签、减签和完成固定遵循：

1. 推进 Flowable revision；
2. 以 `round_id + expected revision + ACTIVE` 更新业务轮次；
3. 加减签同步 Flowable 有序成员变量，完成则写入 task-local 预留标记；
4. 执行当前 Flowable execution 结构或任务完成动作；
5. 重新读取 task、execution、变量、计数和轮次对账。

业务表 CAS 影响行数不为一时返回 `409`，并携带
`WORKFLOW_MULTI_INSTANCE_REVISION_CONFLICT`。Mapper、Flowable 命令、监听审计或写后对账
任一步失败都会回滚同一事务内的引擎状态和轮次写入。

取消、驳回和管理员终止同样保持全局 `Flowable→业务轮次` 锁序。双写 Flowable 状态取得
引擎写锁后，只用普通 SELECT 完成严格引擎对账并冻结 `sourceStatus` 与全部字段；根实例删除
成功返回后才执行 `FOR UPDATE` current-read。锁定集合必须与令牌完全一致，来源状态或批量
影响数竞争返回 `409`；缺行、额外 ACTIVE、字段损坏或写后 locking-read 仍有开放记录返回
`500`，并回滚整笔事务。

原生中断同样先由 Flowable 当前命令取得 execution/流程实例写锁，再执行单行 CAS。取消事件
派发前引擎可能已开始删除 child 和三个 `nrOf*` 根局部计数，因此异常关闭只把 CommandContext
的根身份与仍存在的流程级成员、模式、revision、部署定义和正式轮次逐项对账；正常任务读写
仍继续实时严格读取三个计数。根取消只接受 `ACTIVE`，活动根关联 `RETURNED` 会按数据漂移返回
`500`；已删除子流程实例的残余关闭允许 `ACTIVE/RETURNED`，但必须逐行核对部署定义、受控节点、
ALL/ANY 模式、成员 JSON、revision 与完整生命周期组合。CAS 影响数不为一返回 `409`，监听器异常由 Flowable
fail-on-exception 语义回滚边界信号、execution、任务、变量和轮次。

## 当前阶段边界

本阶段不提供会签/或签整组退回 API，也不新增成员明细表或前端入口。正式轮次持久化已接入，
但整组退回入口尚未开放，因此阶段交付状态仍为 `partial`。
