# WorkflowFormTemplateValidator

## 作用

`WorkflowFormTemplateValidator` 是工作流表单 JSON 的统一服务端门禁。表单新增、修改、模型保存和部署快照生成都复用该验证器，并与前端即时校验共同工作。

## 使用方式

通过 Spring 注入验证器，在持久化或部署前调用：

```java
templateValidator.validate(formContent);
```

`validate(String content)` 无返回值。模板不合法时抛出 `ServiceException`，错误码为 `400`，异常消息使用稳定通用文本。

除完整结构校验外，验证器提供四个正式字段投影：

- `extractVariableNames(content)` 返回模板声明的全部业务变量。
- `extractReadableVariableNames(content)` 返回节点权限快照中可见且可读的字段。
- `extractUserIdSourceVariableNames(content)` 返回可见、可读且由单值文本、数值或选择组件承载的字段。`FORM_USER` 和 `FORM_USER_FIELD` 规则从该目录选择字段，并把字段语义明确收窄为若依用户主键；集合、对象、附件、日期、布尔及同名异构声明在模板校验阶段过滤。
- `extractUserIdSourceFieldSignatures(content)` 在上述目录基础上返回受控组件类型签名，供流程级规则校验跨节点同名字段的值形态一致性；动态渲染和组件执行继续使用各自正式入口。

模型显式校验、保存和部署使用同一次权限化表单快照生成上述目录，字段白名单由已应用节点权限的正式模板投影得到。

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

允许布局：`colFormItem`、`rowFormItem`、`raw`。`rowFormItem` 行容器的 `tag` 可为空，其他布局必须设置允许的 `tag`。

允许组件：`el-input`、`el-input-number`、`el-select`、`el-cascader`、`el-radio-group`、`el-checkbox-group`、`el-switch`、`el-slider`、`el-time-picker`、`el-date-picker`、`el-rate`、`el-color-picker`、`el-upload`、`tinymce`、`el-table`、`el-table-column`、`el-button`。

## 安全与资源限制

- UTF-8 内容最大 `1 MiB`。
- 组件节点最多 `500` 个，`__config__.children` 最大嵌套深度为 `20`。
- JSON 最大解析深度为 `100`，字段名保持唯一且正文只包含一个 JSON 根节点。
- 任意层级字段名使用安全键白名单，`__proto__`、`prototype`、`constructor` 返回模板错误。
- 任意文本 URL 使用受控协议白名单，协议识别覆盖大小写及控制空白规范化。

## 接入约束

验证通过表示模板结构具备受控渲染资格。上传组件使用正式文件 API、权限和持久化；渲染器只执行组件白名单中的结构化配置。新增组件或布局时，同步评审前端渲染、服务端白名单、变量校验和安全测试。
