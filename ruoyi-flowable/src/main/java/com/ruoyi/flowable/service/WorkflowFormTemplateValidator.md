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

`visitFields(root, visitor)` 统一按组件顺序遍历业务字段及其有效用户权限。任一祖先隐藏或不可读，后代均不可读、不可写；任一祖先只读，后代仍可读但不可写。必填仅约束有效可写字段，开始、草稿、任务补丁、受控循环目录和 CallActivity 映射均消费同一事实。布局没有业务变量名时仍会限制后代。

先完整验证每个组件自身的显式 Boolean 和合法组合，再计算祖先约束；隐藏容器不会掩盖缺失权限或非法系统结果声明。`workflowSystemWritable` 是服务器回填资格，不因祖先对用户隐藏而失效；此类字段仍拒绝客户端写入，并继续执行类型与取值约束。CallActivity 来源必须有效可读，只有显式系统输出目标可在用户不可见时接收回填。

节点权限仍冻结原始完整组件树，祖先限制在消费时统一求交，不重写模板正文、已部署快照或草稿的快照字符串，因此不需要数据迁移，不改变 CAS 比较。流程详情的申请、当前任务和历史表单沿用有效可读目录过滤值；附件字段目录保留全部正式声明，实际回显和提交另受读写权限约束。前端仅在渲染描述中收紧权限，隐藏值不进入模型或 `getValues()`，只读值可显示但不提交。

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
