# WorkflowFormTemplateValidator

## 作用

`WorkflowFormTemplateValidator` 是工作流表单 JSON 的统一服务端门禁。表单新增、修改、模型保存和部署快照生成应复用该验证器，不能只依赖前端设计器校验。

## 使用方式

通过 Spring 注入验证器，在持久化或部署前调用：

```java
templateValidator.validate(formContent);
```

`validate(String content)` 无返回值。模板不合法时抛出 `ServiceException`，错误码为 `400`，异常消息不会包含原始模板内容。

## 模板结构

根节点必须是对象并包含 `fields` 数组。`fields` 及 `__config__.children` 中的每个元素必须是组件对象，并包含 `__config__` 对象。

```json
{
  "formRef": "elForm",
  "formModel": "formData",
  "fields": [
    {
      "__config__": {
        "layout": "colFormItem",
        "tag": "el-input"
      },
      "placeholder": "请输入"
    }
  ]
}
```

## 白名单

允许布局：`colFormItem`、`rowFormItem`、`raw`。`rowFormItem` 行容器可以不设置 `tag`，其他布局必须设置允许的 `tag`。

允许组件：`el-input`、`el-input-number`、`el-select`、`el-cascader`、`el-radio-group`、`el-checkbox-group`、`el-switch`、`el-slider`、`el-time-picker`、`el-date-picker`、`el-rate`、`el-color-picker`、`el-upload`、`tinymce`、`el-table`、`el-table-column`、`el-button`。

## 安全与资源限制

- UTF-8 内容最大 `1 MiB`。
- 组件节点最多 `500` 个，`__config__.children` 最大嵌套深度为 `20`。
- JSON 最大解析深度为 `100`，并拒绝重复字段名和尾随第二个 JSON 根节点。
- 任意层级拒绝 `__proto__`、`prototype`、`constructor` 字段。
- 任意文本值拒绝 `javascript:` 和 `data:` 协议，包括大小写及控制空白混淆形式。

## 接入约束

验证通过仅说明模板结构可被受控渲染。上传组件仍必须使用正式文件 API、权限和持久化；渲染器不得执行模板中的脚本、表达式或任意 HTML。新增组件或布局时，必须同步评审前端渲染、服务端白名单、变量校验和安全测试。
