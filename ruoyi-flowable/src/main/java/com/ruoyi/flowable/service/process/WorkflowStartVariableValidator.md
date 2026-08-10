# WorkflowStartVariableValidator

## 作用

`WorkflowStartVariableValidator` 将部署时固化的开始表单 JSON 转换为服务端变量 schema，并对客户端变量执行字段白名单、必填、类型和资源边界校验。返回值是深度复制后的不可修改 JSON 兼容数据，不复用客户端可变集合。

## 使用方式

```java
Map<String, Object> variables = variableValidator.validateAndNormalize(
        deployFormSnapshot.getContent(), request.variables());
```

数据库快照会先经过 `WorkflowFormTemplateValidator` 的严格 JSON、组件白名单和安全校验。快照损坏返回服务端数据异常，客户端变量不合法返回 `400`。

## 字段 schema

变量名来自组件 `__vModel__`，格式为 ASCII 字母或下划线开头，后续只允许字母、数字和下划线，最长 128 个字符。重复字段、保留字段或不支持输入类型会使部署快照校验失败。

支持的数据形态：

- `el-input`、`tinymce`、`el-color-picker`：字符串。
- `el-input-number`、`el-rate`：有限数值，并应用可选 `min/max`。
- `el-slider`：单个有限数值；`range=true` 时必须是两个数值。
- `el-switch`：布尔值。
- `el-radio-group`、单选 `el-select`：字符串、数值或布尔值。
- 多选 `el-select`、`el-checkbox-group`：标量数组。
- `el-cascader`：标量路径数组，允许最多三层受限嵌套路径。
- `el-time-picker`、`el-date-picker`：字符串；范围模式必须是两个字符串。
- `el-upload`：当前仅允许 `null` 或空数组。目标框架尚无可校验上传人、归属关系和业务绑定状态的正式附件记录，因此客户端 `fileList`、`url` 或对象数组一律拒绝进入流程变量。
- `el-table`：对象数组，仍执行对象字段、集合规模、嵌套深度和总负载限制。

`__config__.required=true` 时，字段必须存在且不能是 `null`、空白字符串、空集合或空对象。组件的 `minlength/maxlength`、`min/max`、`limit` 和 `multiple-limit` 只能在服务端硬上限内进一步收紧。

## 保留变量

客户端和表单 schema 均不能声明：`initiator`、`processStatus`、`processInstanceId`、`processDefinitionId`、`deploymentId`、`startUserId`、`authenticatedUserId`、`businessKey`。

## 资源限制

- 顶层字段最多 500 个。
- 任意集合最多 100 个元素，任意对象最多 50 个字段。
- 值树最多 10,000 个节点、20 层嵌套。
- 单个普通文本绝对上限 65,535 个字符。
- 规范化变量 JSON 总大小最多 1 MiB。
- 仅允许 JSON 字符串、布尔、标准有限数值、`null`、集合和字符串键对象；拒绝自定义 Java 对象、NaN、无穷值及原型污染键。

## 设计约束

本验证器只验证部署开始表单的数据契约，不读取当前 `wf_form`。后续任务表单提交需要使用任务节点自己的部署快照和对象级任务权限，不能直接把开始表单 schema 复用于所有节点。

上传字段必须等正式附件表、服务端签发的 `attachmentId`、当前用户归属校验和流程实例绑定形成完整链路后，才能从“只允许空值”调整为接收附件引用；不能直接信任通用上传接口返回的路径。
