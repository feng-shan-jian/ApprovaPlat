# WorkflowStartVariableValidator

## 作用

`WorkflowStartVariableValidator` 将部署时固化的开始表单 JSON 转换为服务端变量 schema，并对客户端变量执行字段白名单、必填、类型和资源边界校验。返回值是深度复制后的不可修改 JSON 数据，与客户端可变集合完全隔离。

## 使用方式

```java
Map<String, Object> variables = variableValidator.validateAndNormalize(
        deployFormSnapshot.getContent(), request.variables());
```

数据库快照会先经过 `WorkflowFormTemplateValidator` 的严格 JSON、组件白名单和安全校验。快照损坏返回服务端数据异常，客户端变量不合法返回 `400`。

## 字段 schema

变量名来自组件 `__vModel__`，格式为 ASCII 字母或下划线开头，后续使用字母、数字和下划线，最长 128 个字符。字段保持唯一、避开服务端保留字并使用输入类型白名单，全部通过后生成部署快照。

支持的数据形态：

- `el-input`、`tinymce`、`el-color-picker`：字符串。
- `el-input-number`、`el-rate`：有限数值，并应用可选 `min/max`。
- `el-slider`：单个有限数值；`range=true` 时必须是两个数值。
- `el-switch`：布尔值。
- `el-radio-group`、单选 `el-select`：字符串、数值或布尔值。
- 多选 `el-select`、`el-checkbox-group`：标量数组。
- `el-cascader`：标量路径数组，允许最多三层受限嵌套路径。
- `el-time-picker`、`el-date-picker`：字符串；范围模式必须是两个字符串。
- `el-upload`：流程变量固定接收 `null` 或空数组；附件通过正式附件表、字段绑定和独立 `attachmentId` 请求链提交。
- `el-table`：对象数组，仍执行对象字段、集合规模、嵌套深度和总负载限制。

`__config__.required=true` 时，字段必须存在并包含有效非空值。组件的 `minlength/maxlength`、`min/max`、`limit` 和 `multiple-limit` 在服务端硬上限内进一步收紧。

## 保留变量

以下变量由服务端独占声明：`initiator`、`processStatus`、`processInstanceId`、`processDefinitionId`、`deploymentId`、`startUserId`、`authenticatedUserId`、`businessKey`。

## 资源限制

- 顶层字段最多 500 个。
- 任意集合最多 100 个元素，任意对象最多 50 个字段。
- 值树最多 10,000 个节点、20 层嵌套。
- 单个普通文本绝对上限 65,535 个字符。
- 规范化变量 JSON 总大小最多 1 MiB。
- JSON 值白名单包含字符串、布尔、标准有限数值、`null`、集合和安全字符串键对象；其余类型返回稳定变量错误。

## 设计约束

本验证器验证部署开始表单的数据契约，并以部署快照为输入。后续任务表单提交使用任务节点自己的部署快照和对象级任务权限，各节点保持独立 schema。

上传字段通过正式附件表、服务端签发的 `attachmentId`、当前用户归属校验和流程实例绑定形成完整链路；表单变量继续只保存空占位，正式附件引用在独立绑定请求中传递。
