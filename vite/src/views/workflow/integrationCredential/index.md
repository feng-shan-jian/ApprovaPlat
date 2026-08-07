# 集成账号页面

## 作用

`index.vue` 管理外部系统发布 Flowable 运行事件所需的正式集成账号。页面只读取脱敏视图，创建和轮换后的明文 Token 仅显示一次。

## 使用方式

路由组件名为 `WorkflowIntegrationCredential`，正式菜单路径为 `workflow/integrationCredential/index`。页面激活时自动重新查询后端，避免缓存页显示过期状态。

## Props 与 Emits

本页面无 props、emits 或公开方法，所有状态来自正式 API。

## 关键设计

- 新增账号需要事件范围、变量白名单和每分钟上限。
- 明文 Token 不写入本地存储、URL 或页面列表，仅存在于一次性响应对话框。
- 轮换后旧 Token 立即失效；吊销不可撤销，历史运行事件保留。
- 页面状态仅用于展示，权限、到期、限流和状态门禁均由后端重新校验。

## 最小接入

```js
{
  path: 'integrationCredential',
  component: () => import('@/views/workflow/integrationCredential/index.vue'),
  name: 'WorkflowIntegrationCredential'
}
```
