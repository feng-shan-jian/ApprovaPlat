# WorkflowProcessList

## 组件简介

`WorkflowProcessList` 统一承载新建、我的、实例运维、待办、待签、已办和抄送七类工作流列表。组件按 `mode` 使用独立后端 DTO、导出权限和业务动作，不在前端合并或模拟流程状态。

## 使用方式

```vue
<WorkflowProcessList mode="todo" />
```

## Props

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `mode` | `'start' \| 'own' \| 'manage' \| 'todo' \| 'claim' \| 'finished' \| 'copy'` | 是 | 选择列表接口、查询字段、表格字段、导出权限及操作。 |

## Emits

组件不向页面发出业务事件；真实动作在组件内调用工作流 API，并在成功后重新查询服务端状态。

## 公开方法

无公开方法。列表刷新由分页、筛选和动作成功状态驱动。

## 关键设计

- 七种模式分别调用 `/workflow/process/list`、`ownList`、`manageList`、`todoList`、`claimList`、`finishedList` 和 `copyList`。
- 抄送模式的 `originatorName` 表示流程发起人名称快照，与 `wf_copy.originator_name`、列表 DTO 和导出列保持一致；抄送接收人由当前登录用户确定，不在列表中误标为抄送操作人。
- 抄送列表支持按服务端 `readStatus` 筛选未读/已读，并展示手工、自动或合并来源、触发类型、触发节点快照、首次阅读时间。
- 打开抄送详情前必须先调用 `PUT /workflow/process/copy/{copyId}/read`；接口完成当前接收人对象授权和首次阅读原子更新后才跳转，不使用本地访问状态，也不乐观修改列表行。
- 抄送详情只传流程实例主键，不传活动 `taskId`，避免把抄送接收人的实例级只读权限扩大为任务表单权限；待办、待签和已办等任务模式仍按服务端行数据传递 `taskId`。
- 日期范围转换为各 DTO 的 `started*`、`created*` 或 `completed*` 字段，不向后端提交页面临时字段。
- `manage` 仅由 `workflow:process:manageList` 权限进入，可按实例、发起人和业务条件查询跨用户实例；`running`、`returned`、`suspended` 均按仍有执行树的活动实例处理并开放终止，只有真实终态才开放受引用检查保护的历史删除。
- 激活和挂起只在 `running`、`suspended` 之间切换；`returned` 是仍有发起人修改任务的业务暂停态，不显示状态切换入口，避免重复挂起后继续回显为“待修改”。
- 发起和详情只传定义、部署、实例及可选任务主键，后端继续做对象关系和权限复核。
- 认领、取消认领、取消、撤回、实例状态、终止及历史删除均等待真实接口成功后刷新列表。
- 取消、终止和撤回对话框在异步表单校验前占用提交锁，请求期间不可关闭；失败会保留原因及目标行并展示稳定错误，防止双击竞态和输入丢失。
- 认领、取消认领、撤回、取消、终止、实例状态和历史删除遇到 `403/404/409` 时会立即回读当前服务端列表；原因输入仍保留在冲突弹窗中，但确认按钮会停用，避免用户继续提交已经失效的任务或实例快照。
- 已办撤回图标只在服务端返回 `revocable === true` 时显示；打开和提交动作还会再次检查该字段，后端提交接口继续按实时执行树和任务行锁复核，列表快照不会替代正式状态校验。
- 动作分发仅接受取消、终止和撤回三个固定值，未知状态在调用 API 前失败并且不会显示成功提示。
- 列表直接展示服务端稳定状态；审批驳回使用独立 `rejected`/“已驳回”，不与管理员终止的 `terminated` 混用。
- 发起人可取消 `running`、`returned` 或 `suspended` 实例；挂起实例由后端在同一事务内短暂激活后写入 `canceled` 终态、审计意见并结束完整执行树，页面不使用本地状态绕过引擎。
- 取消认领按钮只在服务端返回的 `claimedById/claimTime` 证明任务由当前办理人真实 claim 时显示，直接指派、转办和委派任务不显示该动作。
- 导出使用每种列表对应的对象授权查询与独立按钮权限。

## 最小接入示例

```vue
<script setup>
import WorkflowProcessList from '@/components/workflow/WorkflowProcessList.vue'
</script>

<template>
  <WorkflowProcessList mode="finished" />
</template>
```
