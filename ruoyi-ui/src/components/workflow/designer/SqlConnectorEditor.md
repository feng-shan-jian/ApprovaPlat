# SqlConnectorEditor

## 组件简介与作用

`SqlConnectorEditor` 编辑 SQL ServiceTask 的作者配置。数据源来自正式 `wf_sql_datasource`，页面只写稳定键、单条命名参数模板、流程变量映射和有界结果配置，不接收 JDBC URL、用户名或密码正文。

## 使用方式

```vue
<SqlConnectorEditor
  v-model="extensionConfig"
  :data-sources="sqlDataSources"
  @change="updateServiceTask"
/>
```

## Props

| 属性 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `modelValue` | `string` | `'{}'` | BPMN 中的作者配置 JSON。 |
| `dataSources` | `Array` | `[]` | 后端返回的已启用数据源修订，不含凭据正文。 |

## Emits

| 事件 | 参数 | 说明 |
| --- | --- | --- |
| `update:modelValue` | `(json)` | 输出规范作者配置。 |
| `change` | `(json)` | 通知父级经 bpmn-js 命令栈持久化。 |

## 公开方法

无。

## 关键设计思路

- SQL 参数名与流程变量显式一对一映射，禁止表达式和任意 Bean 读取。
- `idempotencyKey` 由外库写入运行时自动注入，不在页面映射。
- 查询最多返回 1000 行，部署端仍会通过 JSqlParser 复核单语句、操作类型和表白名单。
- 页面永远不接触外库凭据正文，历史部署使用冻结的数据源修订与校验和。

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
