# WorkflowApplicationResubmitApplicationService

## 作用

该服务是 `/workflow/task/resubmit` 对应的独立重提命令与 capability 权威服务。它校验原发起人、申请退回态、开始表单补丁和附件投影，并按冻结顺序从可信历史首审批节点重新开始：普通首审批恢复原办理配置，受控首审批从自身权威来源重新建组。

## 使用方式

`WorkflowTaskLifecycleService.resubmitApplication(request)` 直接委派写命令，`isApplicationResubmitAllowed(taskId)` 委派只读能力查询。请求只包含原有表单与附件数据；首审批目标、路径、部署模式和来源轮次对账事实全部来自服务端。新轮成员从重新进入节点自己的开始选择、部署配置或正式身份目录解析。

## 共享准备链与能力语义

写命令和能力查询唯一复用 `requireResubmitPreparation`。该无副作用准备链在同一事务快照内核验活动任务、活动实例、当前 assignee、原发起人、Flowable business status 与 `processStatus` 双 returned 状态、申请人局部标记、owner/委派/候选关系、冻结办理配置、服务端多实例重开计划、部署开始表单和原正式提交快照。

能力查询使用正式当前身份和 `REPEATABLE_READ` 只读事务。既有 `400`、`403`、`404`、`409` 表示当前不可重提并归一为 `false`；正式快照损坏等 `500` 和未知异常继续抛出。查询链不会校验本次表单补丁，不会准备或绑定附件，也不会写变量、comment、审计、通知、轮次或 Flowable 状态。

## 写入顺序

1. 重新执行共享准备链，核验退回任务只能由原发起人办理，并读取正式部署表单和原提交快照。
2. 校验表单补丁并准备附件计划、审计和迁移标记。
3. 绑定附件、写业务变量和正式提交快照。
4. 普通首审批保持同一当前 task/execution，来源轮次只做 CAS 关闭并恢复冻结办理配置；受控首审批取消临时根，从该节点权威来源创建完整执行根和任务组。后续受控节点在自然到达时重新初始化各自轮次。
5. 收口申请人任务 SLA、执行抄送和稳定通知，并清除迁移标记。

任一阶段失败均由 `WorkflowEngineOperations` 的同一外层事务回滚。该服务直接替换并删除 Return/Resubmit combined 装配路径，客户端契约继续只承载表单与附件。

`resubmitAllowed` 只是某次详情读取的权威快照；写命令不会信任该字段，仍在写事务内完整重跑准备链，原权限、错误信息、子码和副作用顺序保持不变。

## 最小接入示例

```java
resubmitApplicationService.resubmitApplication(request);
boolean allowed = resubmitApplicationService.isApplicationResubmitAllowed(taskId);
```
