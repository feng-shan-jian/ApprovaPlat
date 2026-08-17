# WorkflowCollaboration

## 组件简介

`WorkflowCollaboration` 是 Participant/MessageFlow 的运维管理页面，分别展示正式入站台账和 SendTask 事务 outbox，并提供权限隔离的审计、死信补偿和未送达取消操作。页面不保存消息正文、端点密钥或本地状态，激活时从真实 API 刷新。

## 使用方式

组件由菜单路由 `workflow/collaboration/index` 加载，不接收 props，也不对外 emits。

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
- 补偿不会把 `attempt_count` 继续累加到约束之外，而是记录 `compensation_count` 并开启新的有界周期。
- `PROCESSED` 消息不提供取消操作，避免页面承诺无法撤销的外部副作用。

## 最小接入示例

```js
{
  path: 'collaboration',
  component: () => import('@/views/workflow/collaboration/index.vue'),
  meta: { permissions: ['workflow:collaboration:list'] }
}
```
