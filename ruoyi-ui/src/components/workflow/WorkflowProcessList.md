# WorkflowProcessList

## 组件简介

`WorkflowProcessList` 统一承载新建、我的、实例运维、待办、待签、已办和抄送七类工作流列表。组件按 `mode` 使用独立后端 DTO、导出权限和业务动作，流程状态直接采用各正式接口的服务端投影。

## 使用方式

```vue
<WorkflowProcessList mode="todo" />
```

## Props

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `mode` | `'start' \| 'own' \| 'manage' \| 'todo' \| 'claim' \| 'finished' \| 'copy'` | 是 | 选择列表接口、查询字段、表格字段、导出权限及操作。 |

## Emits

真实动作在组件内部调用工作流 API，并在成功后重新查询服务端状态；父页面只负责传入列表模式。

## 公开方法

无公开方法。列表刷新由分页、筛选、动作成功状态，以及从详情页返回后的 KeepAlive 重新激活驱动。

## 关键设计

- 七种模式分别调用 `/workflow/process/list`、`ownList`、`manageList`、`todoList`、`claimList`、`finishedList` 和 `copyList`。
- 抄送模式的 `originatorName` 表示流程发起人名称快照，与 `wf_copy.originator_name`、列表 DTO 和导出列保持一致；抄送接收人由当前登录用户确定，列表中的操作人字段保持为真实业务操作人。
- 抄送列表支持按服务端 `readStatus` 筛选未读/已读，并展示手工、自动或合并来源、触发类型、触发节点快照、首次阅读时间。
- 打开抄送详情前必须先调用 `PUT /workflow/process/copy/{copyId}/read`；接口完成当前接收人对象授权和首次阅读原子更新后才跳转，列表行状态随后从服务端响应刷新。
- 抄送详情传递流程实例主键并使用实例级只读权限；待办、待签和已办等任务模式按服务端行数据传递 `taskId` 并使用任务表单权限。
- 日期范围转换为各 DTO 的 `started*`、`created*` 或 `completed*` 字段，后端请求采用 DTO 字段白名单。
- `manage` 仅由 `workflow:process:manageList` 权限进入，可按实例、发起人和业务条件查询跨用户实例；`running`、`returned`、`suspended` 均按仍有执行树的活动实例处理并开放终止，只有真实终态才开放受引用检查保护的历史删除。
- 激活和挂起入口面向 `running`、`suspended` 两种状态；`returned` 作为发起人修改中的业务暂停态，只展示待修改入口。
- 发起和详情只传定义、部署、实例及可选任务主键，后端继续做对象关系和权限复核。
- 认领、取消认领、取消、撤回、实例状态、终止及历史删除均等待真实接口成功后刷新列表。
- KeepAlive 首次挂载触发的 `activated` 登记激活状态，由初始化请求负责首屏数据；详情页完成退回、重提或其他任务动作并关闭页面后，缓存中的来源列表在后续重新激活时回读服务端。若回页发生在首次分类加载结束前，则在初始化完成后补做一次查询并展示最新任务与流程状态。
- 取消、终止和撤回对话框在异步表单校验前占用提交锁，请求期间保持锁定；失败会保留原因及目标行并展示稳定错误，提交锁串行化点击并保留当前输入。
- 认领、取消认领、撤回、取消、终止、实例状态和历史删除遇到 `403/404/409` 时会立即回读当前服务端列表；原因输入保留在冲突弹窗中，确认按钮进入锁定状态，等待用户基于新快照重新操作。
- 已办撤回图标只在服务端返回 `revocable === true` 时显示；打开和提交动作还会再次检查该字段，后端提交接口继续按实时执行树和任务行锁执行正式状态校验。
- 动作分发仅接受取消、终止和撤回三个固定值，未知状态在调用 API 前返回前端错误并展示真实结果。
- 列表直接展示服务端稳定状态；审批驳回使用独立 `rejected`/“已驳回”，不与管理员终止的 `terminated` 混用。
- 发起人可取消 `running`、`returned` 或 `suspended` 实例；挂起实例由后端在同一事务内短暂激活后写入 `canceled` 终态、审计意见并结束完整执行树，页面始终调用正式引擎入口。
- 取消认领按钮在服务端返回的 `claimedById/claimTime` 证明任务由当前办理人真实 claim 时显示；直接指派、转办和委派任务分别显示其适用动作。
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
