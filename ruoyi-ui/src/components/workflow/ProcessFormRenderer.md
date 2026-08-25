# ProcessFormRenderer

## 组件简介

`ProcessFormRenderer` 渲染部署时固化的正式表单 JSON 快照，并执行快照内的节点字段隐藏、只读、可编辑和必填表现。提交时返回当前节点可写字段，后端以同一部署快照再次强制执行字段权限和类型规则。上传组件使用工作流私有附件接口。

## 使用方式

```vue
<ProcessFormRenderer
  ref="formRendererRef"
  v-model="formValues"
  :content="formSnapshot.content"
  @error="showError"
/>
```

保存并提交真实草稿链：

```js
await formRendererRef.value.ensureAttachmentsIdle()
const variables = formRendererRef.value.getValues()
const created = await createProcessDraft({ processDefinitionId, businessKey, variables })

await formRendererRef.value.validate()
await submitProcessDraft(created.data.draftId, {
  expectedVersion: created.data.revisionNo,
  businessKey,
  variables: formRendererRef.value.getValues()
})
```

## Props

| 参数 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `content` | `string \| object` | 必填 | Flowable 业务制品 `approvaplat/forms-v1.json` 中的不可变 JSON 快照。 |
| `modelValue` | `object` | `{}` | 当前表单值；附件字段可包含后端返回的安全元数据。 |
| `readonly` | `boolean` | `false` | 历史详情模式，字段和附件进入只读展示状态。 |

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
| `validate()` | `Promise<boolean>` | 校验必填项；附件全部完成后返回可提交结果。 |
| `getValues()` | `object` | 只获取当前节点可写变量；上传字段转换为服务端 UUID 数组。 |
| `reset()` | `void` | 恢复模板默认值并清除校验。 |

## 关键设计

- 渲染面固定为服务端白名单中的组件和静态属性；模板 URL、事件和脚本在规范化阶段过滤。
- `workflowHidden` 字段从页面模型中排除，`workflowWritable=false` 字段只回显；服务端授权继续作为最终字段门禁。
- 同时读取嵌套 `__config__/__vModel__` 快照和 Vue 3 生成器平面字段，两种正式模板格式均可编辑并规范化。
- 附件上传后仅保存 UUID 和安全元数据；绑定、过期、所有者和字段一致性由后端事务强制校验。
- 草稿保存调用 `ensureAttachmentsIdle()` 检查附件状态，正式提交调用 `validate()` 执行完整必填校验；两种动作都等待附件写请求完成后读取稳定值。
- 行容器中的附件字段会逐层透传真实字段名和忙碌状态，上传或删除完成后立即解除提交门禁。
- 只读模式允许通过受保护接口下载已绑定附件，响应仅包含授权文件流和安全元数据。
- 小于 `768px` 的视口把所有字段展开为 24 栅格并将标签置于控件上方，移动端规则直接覆盖模板的固定 `span/labelWidth`。
