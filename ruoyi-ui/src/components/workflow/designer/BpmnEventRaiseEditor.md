# BpmnEventRaiseEditor

## 组件作用

编辑受控 `RAISE_BPMN_EVENT` 服务任务配置。组件只保存正式目录编码、业务来源、条件变量和可选消息变量；事件名称与通知策略由后端部署时冻结。

## 使用方式

```vue
<BpmnEventRaiseEditor
  v-model="state.extensionConfig"
  :error-options="errorOptions"
  :escalation-options="escalationOptions"
  @change="saveServiceTask"
/>
```

## Props

- `modelValue: string`：作者 BPMN 中的 JSON 配置。
- `errorOptions: array`：真实后端返回的启用错误编码目录。
- `escalationOptions: array`：真实后端返回的启用升级编码目录。

## Emits

- `update:modelValue`：输出规范 JSON。
- `change`：字段变更后通知父级写入 bpmn-js 命令栈。

## 关键设计

- 不允许手填目录外编码。
- `sourceType` 明确记录事件来自服务任务、HTTP、SQL、DMN 或人工业务判断。
- 条件只读取一个标量流程变量，不执行表达式、脚本或方法调用。
- 普通 Java 异常不会被转换为 BPMN Error；部署时后端还会核验同活动唯一精确匹配边界。
