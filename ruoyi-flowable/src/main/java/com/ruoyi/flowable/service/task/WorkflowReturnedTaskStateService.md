# WorkflowReturnedTaskStateService

## 作用

该服务是申请退回态的唯一写入和核验边界。它管理普通退回办理配置快照、申请人局部变量、候选用户/候选组/owner、任务指派、受控迁移标记，以及流程变量与 Flowable `businessStatus` 的 `returned/running` 双状态。

它不处理审计、附件、抄送、通知、SLA、轮次 CAS 或 Flowable 执行树迁移；这些副作用仍由对应 ApplicationService 和多实例迁移服务按冻结顺序编排。

## 使用方式

- 普通退回：`markTransition` → Flowable 迁移 → `enterOrdinaryReturned` → 外部副作用 → `clearTransition`。
- 整组退回：`markTransition` → Flowable 整组迁移 → `enterGroupReturned` → 轮次 CAS → `clearTransition`。
- 普通重提：`requireReturnedApplicant` → `requireOrdinaryAssignment` → `restoreOrdinary`。
- 整组重提：`requireReturnedApplicant` → 轮次计划核验 → `prepareGroupRunning` → Flowable 重建。

## 公开方法

| 方法 | 输入 | 结果 |
| --- | --- | --- |
| `requireRunning` | 流程实例 ID | 要求 `processStatus=running`；兼容 `businessStatus` 为空或 `running`，明确终态仍冲突 |
| `markTransition` / `clearTransition` | 流程实例 ID、稳定标记 | 建立或清除单命令迁移标记 |
| `enterOrdinaryReturned` | 任务、实例、申请人 ID | 返回不可变原办理配置快照 |
| `enterGroupReturned` | 任务、实例、申请人 ID | 建立无普通办理配置的整组退回态 |
| `requireReturnedApplicant` | 任务、实例、申请人 ID | 核验独占指派、无候选关系、无 owner/委派及 `returned` 双状态 |
| `requireOrdinaryAssignment` | 任务 ID | 解码普通退回办理配置 |
| `restoreOrdinary` | 任务、实例、办理配置 | 恢复原办理配置并切换 `running` |
| `prepareGroupRunning` | 任务、实例 | 清除退回协议变量并切换 `running` |

## 关键约束

所有公开调用只接收主键、稳定标记或不可变快照，不向调用方暴露 `TaskService`、`RuntimeService` 或可变 Flowable 对象。状态变更必须位于 `WorkflowEngineOperations` 打开的同一可重复读写事务中，任一步失败由外层事务整体回滚。

`requireRunning` 的空 `businessStatus` 兼容仅适用于尚未进入退回协议的普通运行实例。写入口保留写前任务、申请人和状态校验；同步 TaskService/RuntimeService 写入正常返回即交由外层事务提交，不再回读刚写入的任务指派、局部变量或双状态自证成功。

## 最小接入示例

```java
returnedTaskStateService.markTransition(processInstanceId,
        WorkflowReturnedApplicationProtocol.RETURN_TRANSITION_MARKER);
runtimeService.createChangeActivityStateBuilder()
        .processInstanceId(processInstanceId)
        .moveExecutionToActivityId(executionId, targetActivityId)
        .changeState();
returnedTaskStateService.enterOrdinaryReturned(
        returnedTaskId, processInstanceId, applicantUserId);
returnedTaskStateService.clearTransition(processInstanceId);
```
