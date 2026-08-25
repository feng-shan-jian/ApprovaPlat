# HttpConnectorEditor

## 组件简介

`HttpConnectorEditor` 编辑受控 HTTP ServiceTask 的作者配置。端点、允许方法、路径前缀、认证类型和超时均来自后端 `wf_connector_endpoint`；组件不接受任意 URL、请求头或密钥正文。

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
| `endpoints` | `Array` | `[]` | 后端返回的已启用端点修订，不含密钥正文。 |

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
- `GET` 和 `DELETE` 不允许正文变量；正文只能来自显式流程变量。
- 节点必须启用进入前异步，超时和非成功状态由 Flowable Job 重试并进入原生死信。
- `Idempotency-Key` 固定由 `processInstanceId + executionId + elementId + payloadSha256` 生成，同一载荷重试稳定、载荷变化时隔离。
- 认证只显示类型和外部密钥引用状态，密钥正文不进入浏览器、BPMN 或接口响应。

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
