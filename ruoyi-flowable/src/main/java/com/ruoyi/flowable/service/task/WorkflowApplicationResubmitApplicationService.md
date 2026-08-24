# WorkflowApplicationResubmitApplicationService

## 作用

该服务是 `/workflow/task/resubmit` 对应的独立重提命令服务。它校验原发起人、申请退回态、开始表单补丁和附件投影，并按冻结顺序恢复普通办理配置或重建受控多实例完整审批组。

## 使用方式

`WorkflowTaskLifecycleService.resubmitApplication(request)` 直接委派本服务。请求仍只包含原有表单与附件数据；成员、顺序、ALL/ANY 模式、revision 和目标节点全部来自服务端轮次快照。

## 写入顺序

1. 核验退回任务只能由原发起人办理，并读取正式部署表单和原提交快照。
2. 校验表单补丁并准备附件计划、审计和迁移标记。
3. 绑定附件、写业务变量和正式提交快照。
4. 普通任务恢复冻结办理配置；整组任务先 CAS 重开旧轮，再切换 `running`，最后重建完整执行根和审批任务组。
5. 收口申请人任务 SLA、执行抄送和稳定通知，最后执行写后对账并清除迁移标记。

任一阶段失败均由 `WorkflowEngineOperations` 的同一外层事务回滚。该服务不接收客户端成员、模式或目标节点，也不保留旧 Return/Resubmit combined 路径。

## 最小接入示例

```java
resubmitApplicationService.resubmitApplication(request);
```
