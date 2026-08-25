# 表单生成器

## 组件简介

该页面是 ApprovaPlat 的 Vue 3 拖拽表单生成器，同时提供工作流表单设计模式。普通模式用于生成 Vue 代码；工作流模式负责读取、预览并通过真实 API 保存 `wf_form` 正式模板。

## 使用方式

普通生成器沿用原入口。工作流模式由隐藏路由进入：

```js
router.push({
  path: '/workflow/form-design',
  query: { workflow: '1', formId: 12 }
})
```

新增表单时省略 `formId`。

## 参数

页面通过路由 query 接收参数：

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `workflow` | `'1'` | 开启工作流设计模式。 |
| `formId` | `string \| number` | 可选，编辑正式流程表单主键。 |

## 事件与公开方法

该页面作为路由页面运行，保存动作直接调用 `POST /workflow/form` 或 `PUT /workflow/form`，成功后使用服务端返回的真实主键更新当前路由。

## 关键设计

- 内部使用 Vue 3 生成器平面字段，加载和保存边界统一转换正式模板的 `__config__/__vModel__` 嵌套格式。
- 保存前递归校验变量名和重复字段，后端继续执行最终结构、组件和危险内容校验。
- 工作流预览使用 `ProcessFormRenderer`，与发起和办理页面共用同一渲染路径。
- 受保护的 `WorkflowAttachmentUpload` 在运行时直接替换生成器公共上传地址。
- 新增模式的保存入口要求 `workflow:form:add`，编辑模式要求 `workflow:form:edit`；后端使用编辑权限读取待编辑正文，使入口和依赖接口权限保持一致。
