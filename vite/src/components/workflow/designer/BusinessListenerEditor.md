# BusinessListenerEditor

## 组件简介

`BusinessListenerEditor` 编辑执行监听器或用户任务业务监听器。处理器只能从服务端 Java 注册表选择，配置保留为 JSON 字符串并由后端部署校验、版本冻结和校验和保护。

## Props

| 属性 | 类型 | 说明 |
| --- | --- | --- |
| `modelValue` | `Array` | `{ event, extensionKey, config }` 监听器配置列表。 |
| `kind` | `string` | `EXECUTION` 或 `TASK`，决定合法事件集合。 |
| `options` | `Array` | 后端返回的已启用 Java 扩展最新版。 |
| `loading` | `boolean` | 注册表加载状态。 |

## Emits

`update:modelValue` 和 `change` 返回不含客户端临时标识的监听器数组。

## 最小接入示例

```vue
<BusinessListenerEditor
  v-model="state.businessExecutionListeners"
  kind="EXECUTION"
  :options="listenerOptions"
  @change="updateBusinessExecutionListeners"
/>
```

组件只编辑业务监听器，系统身份审计监听器由父设计器和后端自动维护。
