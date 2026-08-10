# CollaborationMessageEditor

## 组件简介

`CollaborationMessageEditor` 编辑 MessageFlow 源 SendTask 的事务 outbox 作者配置。它只允许选择正式 HTTP 端点目录，配置消息名、目标流程、关联变量、投递变量白名单和有界尝试次数；端点修订与认证快照由后端部署事务冻结。

## 使用方式

```vue
<CollaborationMessageEditor
  v-model="state.extensionConfig"
  :endpoints="connectorEndpoints"
  @change="saveServiceTask"
/>
```

## Props

| 属性 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `modelValue` | `string` | `{}` | outbox 作者配置 JSON |
| `endpoints` | `array` | `[]` | 后端返回的已启用 HTTP 端点修订，不含密钥 |

## Emits

| 事件 | 参数 | 说明 |
| --- | --- | --- |
| `update:modelValue` | `string` | 规范化配置 JSON |
| `change` | `string` | 用户修改后的配置 JSON |

## 公开方法

无。

## 关键设计

- 留空关联变量时使用源流程业务键，保证调用链中的关联键稳定。
- 投递变量只保存名称白名单，真实值在 SendTask 执行事务中冻结到 outbox。
- 组件不保存 Token、密钥引用、端点 URL 或端点修订快照。

## 最小接入示例

```js
const config = ref(JSON.stringify({
  endpointKey: 'approval-partner',
  path: '/workflow/runtime-event/collaboration/message',
  messageName: 'approval.requested',
  targetProcessDefinitionKey: 'partnerApproval',
  variableNames: ['amount'],
  maxAttempts: 5
}))
```
