# WorkflowProcessCancelApplicationService

## 作用

该服务是流程取消的唯一应用事务入口，保留发起人或管理员授权、active/suspended 校验、完整 CallActivity 树终止、取消 comment、流程状态和通知的既有原子写链。

## 输入与输出

- 输入：原 `WorkflowProcessCancelRequest`。
- 输出：无；失败继续使用既有 400、403、404、409 或 500 契约。

## 事务与顺序

1. `WorkflowEngineOperations` 建立唯一外层写事务并解析当前身份。
2. 读取请求实例并解析正式根实例。
3. 校验发起人或超级管理员权限。
4. 委派 `WorkflowProcessInstanceService` 激活挂起树、写状态、通知并终止完整执行树。
5. 在终止回调中为各子实例活动任务写结构化取消 comment。

该服务不处理附件、重提或多实例整组迁移。
