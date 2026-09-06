# WorkflowFormTemplateValidator

## 作用

`WorkflowFormTemplateValidator` 是工作流表单 JSON 的统一服务端门禁。表单新增、修改、模型保存和部署快照生成都复用该验证器，并与前端即时校验共同工作。

## 使用方式

通过 Spring 注入验证器，在持久化或部署前调用：

```java
JsonNode root = templateValidator.validate(formContent);
Set<String> variables = templateValidator.extractVariableNames(root);
```

`validate(String content)` 返回通过全部校验的 `JsonNode` 根节点；只需校验的调用方可以忽略返回值。模板不合法时抛出 `ServiceException`，错误码为 `400`，异常消息使用稳定通用文本。

除完整结构校验外，验证器提供四个正式字段投影：

- `extractVariableNames(root)` 返回模板声明的全部业务变量。
- `extractReadableVariableNames(root)` 返回节点权限快照中可见且可读的字段。
- `extractAttachmentVariableNames(root)` 返回 `el-upload` 声明的附件字段。
- `extractUserIdSourceFieldSignatures(root)` 返回可见、可读的单值文本、数值或选择字段及受控组件类型签名，供 `FORM_USER`、`FORM_USER_FIELD` 规则选择用户主键来源，并校验跨节点同名字段的值形态一致性；集合、对象、附件、日期、布尔及同名异构声明在投影时排除。

四种投影只遍历本次 `validate` 返回且未经修改的根节点，不解析文本或重复执行全量校验，返回集合保持字段顺序、去重和不可修改性。原始 JSON 必须先经过正式校验，解析结果仅限本次调用只读复用。

模型显式校验、保存和部署使用同一次权限化表单快照生成上述目录，字段白名单由已应用节点权限的正式模板投影得到。

## 模板结构

根节点必须是对象并包含 `fields` 数组。`fields` 及行容器 `children` 中的每个元素必须是平面组件对象；`layout`、`tag`、`vModel` 和字段权限均直接属于该对象。

每个字段都必须显式包含布尔值 `workflowHidden`、`workflowReadable`、`workflowWritable`。正式表单生成器在组件进入画布及保存序列化时统一补齐默认值 `false/true/true`；后端不再把缺失权限解释为默认状态，避免不可读数据进入部署快照。

```json
{
  "formRef": "elForm",
  "formModel": "formData",
  "fields": [
    {
      "layout": "colFormItem",
      "tag": "el-input",
      "vModel": "reason",
      "workflowHidden": false,
      "workflowReadable": true,
      "workflowWritable": true,
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
- 组件节点最多 `500` 个，行容器 `children` 最大嵌套深度为 `20`。
- JSON 最大解析深度为 `100`，字段名保持唯一且正文只包含一个 JSON 根节点。
- 任意层级字段名使用安全键白名单，`__proto__`、`prototype`、`constructor` 返回模板错误。
- 任意文本 URL 使用受控协议白名单，协议识别覆盖大小写及控制空白规范化。

## 接入约束

验证通过表示模板结构具备受控渲染资格。上传组件使用正式文件 API、权限和持久化；渲染器只执行组件白名单中的结构化配置。新增组件或布局时，同步评审前端渲染、服务端白名单、变量校验和安全测试。
