# ProcessFormRenderer

## 组件简介

`ProcessFormRenderer` 渲染部署时固化的旧版表单 JSON 快照，并执行快照内的节点字段隐藏、只读、可编辑和必填表现。提交时只返回当前节点可写字段，真正的字段权限和类型规则仍由后端以同一部署快照强制执行。上传组件使用工作流私有附件接口，不使用 `/profile/**` 静态地址。

## 使用方式

```vue
<ProcessFormRenderer
  ref="formRendererRef"
  v-model="formValues"
  :content="formSnapshot.content"
  @error="showError"
/>
```

提交真实发起接口前：

```js
await formRendererRef.value.validate()
const variables = formRendererRef.value.getValues()
await startProcess(definitionId, { businessKey, variables })
```

## Props

| 参数 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `content` | `string \| object` | 必填 | Flowable 业务制品 `approvaplat/forms-v1.json` 中的不可变 JSON 快照。 |
| `modelValue` | `object` | `{}` | 当前表单值；附件字段可包含后端返回的安全元数据。 |
| `readonly` | `boolean` | `false` | 历史详情模式，禁止修改和删除。 |

## Emits

| 事件 | 参数 | 说明 |
| --- | --- | --- |
| `update:modelValue` | `object` | 内部表单值变化。 |
| `change` | `object` | 用户修改字段。 |
| `error` | `Error` | 附件上传、下载或删除失败。 |

## 公开方法

| 方法 | 返回值 | 说明 |
| --- | --- | --- |
| `ensureAttachmentsIdle()` | `Promise<boolean>` | 仅确认附件上传和删除请求已完成；保存允许缺少正式必填项的草稿前使用。 |
| `validate()` | `Promise<boolean>` | 校验必填项，并拒绝附件上传中提交。 |
| `getValues()` | `object` | 只获取当前节点可写变量；上传字段转换为服务端 UUID 数组。 |
| `reset()` | `void` | 恢复模板默认值并清除校验。 |

## 关键设计

- 只渲染服务端白名单中的组件和静态属性，不执行模板中的 URL、事件或脚本。
- `workflowHidden` 字段不进入页面模型，`workflowWritable=false` 字段只回显不提交，不能依赖前端 `disabled` 代替服务端授权。
- 兼容旧项目 `__config__/__vModel__` 快照，也能读取当前 Vue 3 生成器的平面字段用于迁移编辑。
- 附件上传后仅保存 UUID 和安全元数据；绑定、过期、所有者和字段一致性由后端事务强制校验。
- 草稿保存调用 `ensureAttachmentsIdle()` 而不执行正式必填校验；正式提交仍调用 `validate()`，两种动作都不会在附件写请求未完成时读取漂移值。
- 行容器中的附件字段会逐层透传真实字段名和忙碌状态，上传或删除完成后立即解除提交门禁。
- 只读模式仍允许通过受保护接口下载已绑定附件，但不会暴露内部存储键。
- 小于 `768px` 的视口把所有字段展开为 24 栅格并将标签置于控件上方，旧模板的固定 `span/labelWidth` 不会挤压移动端输入区域。
