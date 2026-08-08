# WorkflowDraft

## 组件简介

`WorkflowDraft` 是当前登录用户的申请草稿列表页。页面只调用后端对象授权接口，提供流程名称和更新时间筛选、继续编辑及带乐观锁版本的删除操作，不在浏览器保存草稿数据。

## 使用方式

该页面由后端菜单 `/office/draft` 加载：

```text
component: workflow/work/draft
permission: workflow:process:draftList
```

继续编辑使用隐藏路由 `WorkflowProcessDraftEdit`，只传递服务端草稿主键。

## Props

页面组件无 Props。当前用户身份从认证会话取得，禁止由路由或查询参数指定草稿所有者。

## Emits

页面组件无 Emits。查询、删除和继续编辑均直接调用正式接口或受权限路由。

## 公开方法

页面组件不暴露公开方法。

## 关键设计

- 列表固定调用 `GET /workflow/process/draft/list`，服务端负责只返回本人草稿。
- 流程名称在服务端查询，更新时间范围转换为 `updatedAfter/updatedBefore`，不在前端过滤正式结果。
- 删除调用 `DELETE /workflow/process/draft/{id}?expectedVersion=...`；发生 CAS 冲突后立即刷新，不把旧行版本重试为新版本。
- 继续编辑只传 `draftId`，草稿所有权、状态和部署快照关系由详情接口复核。
- 不使用 `localStorage`、`sessionStorage`、Pinia 或浏览器内存冒充持久化草稿；页面内响应状态只服务于本次交互。

## 最小接入示例

```js
router.push({
  name: 'WorkflowProcessDraftEdit',
  params: { draftId: row.draftId }
})
```
