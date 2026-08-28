# HttpConnectorEditor

## 组件简介

`HttpConnectorEditor` 编辑受控 HTTP ServiceTask 的作者配置。端点、允许方法、路径前缀、认证类型和超时均来自后端 `wf_connector_endpoint`，作者配置只保存目录键和受控变量映射。

## 使用方式

```vue
<HttpConnectorEditor
  v-model="extensionConfig"
  :endpoints="connectorEndpoints"
  @change="updateControlledTask"
/>
```

## Props

| 属性 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `modelValue` | `string` | `'{}'` | 作者 BPMN 中的受控配置 JSON。 |
| `endpoints` | `Array` | `[]` | 后端返回的已启用端点公开元数据修订。 |

## Emits

| 事件 | 参数 | 说明 |
| --- | --- | --- |
| `update:modelValue` | `(json)` | 输出规范作者配置 JSON。 |
| `change` | `(json)` | 配置发生业务变化，父级应通过 bpmn-js 命令栈写入。 |

## 公开方法

无。

## 关键设计

- 切换端点时，请求方法自动收敛到端点的 `allowedMethods`。
- 相对路径必须处于端点 `pathPrefix` 内，最终仍由部署服务端复核。
- `POST`、`PUT` 和 `PATCH` 可从显式流程变量构造正文；`GET` 和 `DELETE` 固定使用空正文。
- 节点必须启用进入前异步，超时和非成功状态由 Flowable Job 重试并进入原生死信。
- `Idempotency-Key` 固定由 `processInstanceId + executionId + elementId + payloadSha256` 生成，同一载荷重试稳定、载荷变化时隔离。
- 认证区域显示类型和外部密钥引用状态；密钥正文保存在服务端受控密钥系统。

## 最小接入示例

```js
const connectorEndpoints = [{
  endpointId: 1,
  endpointKey: 'finance.api',
  endpointName: '财务系统',
  allowedMethods: 'POST',
  pathPrefix: '/workflow',
  revisionNo: 3,
  authType: 'BEARER',
  networkScope: 'PRIVATE',
  requestTimeoutMs: 5000
}]
```
