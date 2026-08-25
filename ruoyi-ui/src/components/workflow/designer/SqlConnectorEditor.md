# SqlConnectorEditor

## 组件简介与作用

`SqlConnectorEditor` 编辑 SQL ServiceTask 的作者配置。数据源来自正式 `wf_sql_datasource`，页面写入稳定键、单条命名参数模板、流程变量映射和有界结果配置；JDBC URL、用户名与密码由服务端数据源记录管理。

## 使用方式

```vue
<SqlConnectorEditor
  v-model="extensionConfig"
  :data-sources="sqlDataSources"
  @change="updateControlledTask"
/>
```

## Props

| 属性 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `modelValue` | `string` | `'{}'` | BPMN 中的作者配置 JSON。 |
| `dataSources` | `Array` | `[]` | 后端返回的已启用数据源公开元数据修订。 |

## Emits

| 事件 | 参数 | 说明 |
| --- | --- | --- |
| `update:modelValue` | `(json)` | 输出规范作者配置。 |
| `change` | `(json)` | 通知父级经 bpmn-js 命令栈持久化。 |

## 公开方法

无。

## 关键设计思路

- SQL 参数名与流程变量显式一对一映射，运行时仅解析命名参数白名单。
- HTTP/SQL 连接器节点必须启用进入前异步，失败由 Flowable Job 重试并最终进入原生死信。
- 外库写只接受 `INSERT ... ON DUPLICATE KEY UPDATE idempotency_column = idempotency_column` 形式；`idempotencyColumn` 必须对应目标表真实唯一约束。
- `idempotencyKey` 由运行时基于流程实例、execution 和节点稳定生成，页面只配置业务参数映射。
- 查询最多返回 1000 行，部署端仍会通过 JSqlParser 复核单语句、操作类型和表白名单。
- 外库凭据正文由服务端受控密钥系统持有；已部署流程使用冻结的数据源修订与校验和。

## 最小接入示例

```js
const extensionConfig = JSON.stringify({
  dataSourceKey: 'workflow-primary',
  sql: 'select status from wf_business_status where business_id = :businessId',
  parameters: { businessId: 'businessId' },
  resultVariable: 'queryResult',
  maxRows: 10
})
```

外库幂等写最小示例：

```js
const extensionConfig = JSON.stringify({
  dataSourceKey: 'finance-external',
  sql: 'insert into payment_request(request_id, amount) values (:idempotencyKey, :amount) on duplicate key update request_id = request_id',
  parameters: { amount: 'amount' },
  idempotencyColumn: 'request_id',
  maxRows: 1
})
```
