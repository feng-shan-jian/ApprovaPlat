# 集成账号页面

## 作用

`index.vue` 管理外部系统发布 Flowable 运行事件所需的正式集成账号。页面只读取脱敏视图，创建和轮换后的明文 Token 仅显示一次。

## 使用方式

路由组件名为 `WorkflowIntegrationCredential`，正式菜单路径为 `workflow/integrationCredential/index`。页面激活时自动重新查询后端并展示最新凭据状态。

## Props 与 Emits

本页面无 props、emits 或公开方法，所有状态来自正式 API。

## 关键设计

- 新增账号需要事件范围、变量白名单和每分钟上限。
- 明文 Token 仅存在于创建或轮换的一次性响应对话框；本地存储、URL 和页面列表只保存非敏感元数据。
- 状态筛选和状态标签直接消费后端 `status`，前端不根据吊销时间或到期时间推导账号状态。
- 轮换和吊销按钮分别直接消费后端能力快照 `rotateAllowed`、`revokeAllowed`。
- 轮换后上一枚 Token 立即失效；吊销是终态，历史运行事件保留。
- `v-hasPermi` 负责前端权限展示；权限、行锁、到期边界和最终状态仍由后端重新校验。

## 最小接入

```js
{
  path: 'integrationCredential',
  component: () => import('@/views/workflow/integrationCredential/index.vue'),
  name: 'WorkflowIntegrationCredential'
}
```
