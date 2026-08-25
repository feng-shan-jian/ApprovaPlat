# WorkflowAttachmentUpload

## 组件简介

`WorkflowAttachmentUpload` 是流程表单的私有附件控件。它通过 `/workflow/attachment/**` 完成临时上传、授权下载和未绑定附件删除，不生成可公开访问的静态文件地址。移除草稿或流程已绑定附件时只解除当前表单字段引用，正式解绑或审计保留由后端事务决定。

## 使用方式

```vue
<WorkflowAttachmentUpload
  v-model="attachments"
  field-name="evidenceFiles"
  :limit="5"
  accept=".pdf,.png,.jpg"
  @busy-change="uploading = $event"
  @error="showError"
/>
```

## Props

| 参数 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `fieldName` | `string` | 必填 | 表单变量名，后端绑定流程实例时校验附件字段归属。 |
| `modelValue` | `array` | `[]` | 后端返回的附件安全元数据数组。 |
| `disabled` | `boolean` | `false` | 禁止上传和移除；已经绑定的附件仍可授权下载。 |
| `limit` | `number` | `10` | 当前字段允许的最大附件数量。 |
| `accept` | `string` | `''` | 浏览器文件选择过滤规则，服务端继续执行最终校验。 |
| `maxSizeMb` | `number` | `50` | 客户端即时文件大小上限，单位 MiB。 |

## Emits

| 事件 | 参数 | 说明 |
| --- | --- | --- |
| `update:modelValue` | `array` | 上传、临时附件删除或已绑定附件解除字段引用后的安全元数据。 |
| `busy-change` | `boolean` | 是否存在尚未完成的真实上传或临时附件删除请求。 |
| `error` | `Error` | 上传、下载、删除请求失败，或附件状态不允许移除。 |

## 公开方法

| 方法 | 返回值 | 说明 |
| --- | --- | --- |
| `isUploading()` | `boolean` | 判断当前是否仍有上传或临时附件删除请求，用于提交前门禁。 |

## 关键设计

- 上传结果只保存附件 UUID 和安全元数据，不暴露服务器路径或存储键。
- `TEMP` 附件移除时必须先调用真实删除接口，只有后端删除成功后才更新当前字段值。
- `DRAFT` 附件移除时只从当前 `v-model` 删除引用，下一次带乐观锁的草稿保存负责原子解绑和清理；保存失败时服务端草稿引用保持不变。
- `BOUND` 附件移除时只从当前 `v-model` 中删除引用，不调用删除接口；服务端文件和附件元数据继续保留用于流程审计。
- `disabled` 时不挂载文件输入，只展示禁用的上传提示和独立下载列表；文件名仍由后端按当前用户和流程对象执行授权校验。
- 下载成功响应统一为 `application/octet-stream` 并在保存前通过 Blob 类型校验；后端返回 JSON 业务错误时只显示错误消息，禁止把错误正文保存成附件，合法 JSON 附件仍可正常下载。
- 附件状态不是 `TEMP`、`DRAFT` 或 `BOUND` 时拒绝移除，避免对异常生命周期数据执行错误操作。
- 客户端的数量、类型和大小限制仅用于即时反馈，最终约束由后端强制执行。
- 同一字段的上传、删除和引用移除按顺序处理；每次等待父级 `v-model` 回写后再处理下一项，避免并发完成覆盖附件数组。
- 父表单提交时应把附件元数据转换为 UUID 数组；开始表单必须先保存到持久化草稿，再由草稿提交事务校验、绑定并创建实例，任务表单则在任务完成事务中处理。

## 最小接入示例

```js
const attachmentIds = attachments.value.map(item => item.attachmentId)
const created = await createProcessDraft({
  processDefinitionId,
  variables: { evidenceFiles: attachmentIds }
})
await submitProcessDraft(created.data.draftId, {
  expectedVersion: created.data.revisionNo,
  variables: { evidenceFiles: attachmentIds }
})
```

编辑后续任务表单时，用户移除 `BOUND` 项只会改变上述 `attachmentIds`；原附件仍可在既有流程历史中通过授权接口查询和下载。
