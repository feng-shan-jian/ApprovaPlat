# WorkflowDraft

## 组件简介

`WorkflowDraft` 是当前登录用户的申请草稿列表页。页面调用后端对象授权接口，提供流程名称和更新时间筛选、继续编辑及带乐观锁版本的删除操作；草稿数据统一持久化到服务端。

## 使用方式

该页面由后端菜单 `/office/draft` 加载：

```text
component: workflow/work/draft
permission: workflow:process:draftList
```

继续编辑使用隐藏路由 `WorkflowProcessDraftEdit`，只传递服务端草稿主键。

## Props

页面组件无 Props。当前用户身份唯一来自认证会话，路由和查询参数仅承载草稿主键及业务筛选条件。

## Emits

页面组件无 Emits。查询、删除和继续编辑均直接调用正式接口或受权限路由。

## 公开方法

页面组件通过内部方法完成查询、删除和路由跳转。

## 关键设计

- 列表固定调用 `GET /workflow/process/draft/list`，服务端负责只返回本人草稿。
- 流程名称在服务端查询，更新时间范围转换为 `updatedAfter/updatedBefore`，正式结果由服务端完成筛选。
- 删除调用 `DELETE /workflow/process/draft/{id}?expectedVersion=...`；发生 CAS 冲突后立即刷新，下一次删除使用服务端返回的新版本。
- 继续编辑只传 `draftId`，草稿所有权、状态和部署快照关系由详情接口复核。
- 服务端草稿持久化直接替换 `localStorage`、`sessionStorage`、Pinia 和浏览器内存草稿；页面响应状态仅承载本次交互。

## 最小接入示例

```js
router.push({
  name: 'WorkflowProcessDraftEdit',
  params: { draftId: row.draftId }
})
```
