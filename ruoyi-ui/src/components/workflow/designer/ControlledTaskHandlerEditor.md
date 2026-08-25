# ControlledTaskHandlerEditor

## 组件简介

`ControlledTaskHandlerEditor` 为 ServiceTask 和 SendTask 提供共用的正式扩展目录选择与结构化配置入口。组件不接受 Bean、Java 类名、委托表达式或任意 URL；可选项、端点、SQL 数据源及事件编码全部由父组件从真实后端目录传入。

## 使用方式

```vue
<ControlledTaskHandlerEditor
  :state="state"
  :options="extensionOptions"
  :connector-endpoints="connectorEndpoints"
  :sql-data-sources="sqlDataSources"
  @selection-change="updateControlledTaskSelection"
  @config-update="updateControlledTaskConfig"
  @change="updateControlledTask"
/>
```

## Props

| 属性 | 类型 | 说明 |
| --- | --- | --- |
| `state` | `object` | 包含 `extensionKey` 与 `extensionConfig` 的父面板响应式状态。 |
| `options` | `array` | 服务端正式扩展目录返回的已启用精确版本。 |
| `connectorEndpoints` | `array` | 正式 HTTP 端点目录，不包含认证密钥正文。 |
| `sqlDataSources` | `array` | 正式 SQL 数据源目录，不包含连接凭据。 |
| `errorEventOptions` | `array` | 正式错误事件编码目录。 |
| `escalationEventOptions` | `array` | 正式升级事件编码目录。 |
| `loading` | `boolean` | 正式目录加载状态。 |

## Emits

| 事件 | 参数 | 说明 |
| --- | --- | --- |
| `selection-change` | `extensionKey: string` | 正式扩展键变化后触发，父组件负责建立受控默认配置并写入命令栈。 |
| `config-update` | `config: string` | 结构化子编辑器输出 JSON 草稿时触发，父组件先同步面板状态。 |
| `change` | 无 | 结构化配置变化后触发，父组件负责写入 BPMN 作者字段。 |

## 公开方法

无。组件只通过 props 与 emits 协作。

## 关键设计

- 处理器类型与实现键只用于选择对应的结构化编辑器，不决定后端是否允许执行。
- MessageFlow 源 SendTask 是否必须绑定事务 outbox，由服务端保存和部署校验决定，组件不按画布连线猜测或过滤目录。
- 所有变更由父组件写入 bpmn-js 命令栈，因而可撤销、重做、保存和重载。

## 最小接入示例

```js
function updateControlledTask() {
  modeling.updateModdleProperties(element, businessObject, {
    'flowable:delegateExpression': '${workflowExtensionDelegate}'
  })
}
```
