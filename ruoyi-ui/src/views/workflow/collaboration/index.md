# WorkflowCollaboration

## 组件简介

`WorkflowCollaboration` 是 Participant/MessageFlow 的运维管理页面，分别展示正式入站台账和 SendTask 事务 outbox，并提供权限隔离的审计、死信补偿和待送达取消操作。页面激活时从真实 API 刷新脱敏台账，消息正文和端点密钥保留在服务端受控边界。

## 使用方式

组件由菜单路由 `workflow/collaboration/index` 加载，页面上下文来自当前登录会话和后端 API。

## 权限

| 权限 | 作用 |
| --- | --- |
| `workflow:collaboration:list` | 查看脱敏入站和 outbox 台账 |
| `workflow:collaboration:audit` | 查看单条消息逐次状态历史 |
| `workflow:collaboration:retry` | 重新开启有界重试周期 |
| `workflow:collaboration:cancel` | 取消尚未送达的 outbox |

## 关键设计

- 页面只展示后端明确投影的消息标识、流程关系、顺序、状态和错误摘要。
- 入站与 outbox 按当前页签分别执行服务端分页，默认每页 20 条；状态、消息/流程/关联键和创建时间均下推到后端筛选并返回 `rows/total`。
- 补偿记录 `compensation_count` 并开启新的有界投递周期，`attempt_count` 保持在数据库约束范围内。
- `PROCESSED` 消息进入只读终态，取消操作只面向仍可逆的待处理状态。

## 最小接入示例

```js
{
  path: 'collaboration',
  component: () => import('@/views/workflow/collaboration/index.vue'),
  meta: { permissions: ['workflow:collaboration:list'] }
}
```
