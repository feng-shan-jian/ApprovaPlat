# 审批通知运维页面

## 页面作用

该页面维护审批通知策略，并按服务端分页查询脱敏 notification outbox。页面不读取通知正文、收件地址或凭据，也不在浏览器保存投递状态。

## 权限

- `workflow:notification:manage`：查看和维护通知策略。
- `workflow:notification:audit`：分页查看 notification outbox 运维状态。
- `workflow:notification:retry`：将死信重新开启为一个有界投递周期。

## Outbox 查询参数

| 参数 | 含义 | 默认值 |
| --- | --- | --- |
| `pageNum` | 从 1 开始的页码 | `1` |
| `pageSize` | 每页数量，服务端最大 100 | `20` |
| `status` | 投递状态 | 全部 |
| `sourceType` | `APPROVAL`、`SLA` 或 `BPMN_EVENT` | 全部 |
| `eventType` | 通知业务事件 | 全部 |
| `channel` | `EMAIL` 或 `SMS` | 全部 |
| `keyword` | outbox、来源、流程、任务或错误码关键字 | 空 |
| `beginTime` / `endTime` | 创建时间范围 | 空 |

## 关键设计

- 后端返回若依标准 `rows/total`，排序固定为 `create_time desc, outbox_id desc`。
- 查询投影只包含运维所需的状态、累计尝试、最近错误和业务关联，不返回敏感投递内容。
- 补偿操作只对 `DEAD_LETTER` 开放，真实状态校验与状态迁移由后端 outbox 服务执行。

## 最小接入示例

```js
const response = await listWorkflowNotificationOutbox({
  pageNum: 1,
  pageSize: 20,
  status: 'DEAD_LETTER',
  sourceType: 'APPROVAL'
})

rows.value = response.rows
total.value = response.total
```
