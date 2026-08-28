# WorkflowBpmnService

## 作用

`WorkflowBpmnService` 是模型保存、模型部署和已部署 XML 读取共用的安全边界。调用方只能使用 `validate(byte[])`，成功后获得 `WorkflowBpmnDocument` 和已解析的节点表单引用。

## 输入边界

- BPMN 最大为 2 MiB。
- 字节必须是严格 UTF-8；解码器遇到非法字节立即返回 `400`。
- StAX 解析器以 DTD、外部实体和实体替换关闭模式运行，并安装只返回空外部资源的 `XMLResolver`。
- 安全 `XMLStreamReader` 直接交给 Flowable 8 公共 `BpmnXMLConverter`。

## 业务校验

- 至少包含一个 `isExecutable=true` 的流程。
- 每个流程递归范围内恰好一个开始节点，且开始节点必须配置表单。
- 开始节点和用户任务的表单键必须严格匹配 `key_<正Long>`。
- 递归出现的 `ScriptTask` 统一返回受控元素验证错误。
- 服务任务和监听器 class 仅允许 `com.ruoyi.flowable.delegate.*`、`com.ruoyi.flowable.listener.*`。
- 服务任务和执行监听器的 delegateExpression 仅允许 `${workflowBeanName}` 或 `#{workflowBeanName}` 形式的受控 Bean 引用。
- 每个用户任务各配置一次 `delegateExpression="${userTaskListener}"` 的 `create`、`assignment`、`complete` 三个事件；完整且唯一的固定监听器集合通过验证。静态、表达式、委派、转办及动态多实例产生的 assignee 都在同一引擎事务内实时核验办理资格。
- 动态多实例使用同步、跳过关闭的并行用户任务和 `${multiInstanceHandler.getUserIds(execution)}`，并同时固定 `assignee` 元素变量、`${assignee}` 办理人以及 ALL/ANY 完成条件；目标节点保持排他、同步、无边界事件和无补偿语义。
- 动态多实例必须由主流程中的同步普通用户任务直接初始化；该前驱必须恰有一条入边和一条无条件出边，阻止开始节点、网关、服务任务或并行 token 产生无法提交 `nextUserIds` 的执行树。
- 原始 XML 中受控 handler 方法表达式的出现次数与完整受控节点一一对应，且只位于映射后的多实例字段。
- 串行或并行静态集合多实例继续使用标准 collection 配置；可实例化 `collectionHandler` 返回校验错误。动态多实例配置固定为受控 collection、completion condition 和 handler，loop cardinality、索引变量、聚合及异步离开配置返回校验错误。
- 普通表达式语法白名单包含变量、属性、索引、字面量和运算符。
- 最后调用 `RepositoryService.validateProcess`，结果只含 warning 或为空时进入保存或部署。

所有失败均返回 HTTP 400 对应的 `ServiceException`，对外提示使用稳定业务消息；原始 XML、解析器消息和内部类名仅保留在受控服务端诊断中。

## 返回数据

```java
WorkflowBpmnDocument document = workflowBpmnService.validate(bpmnBytes);
for (WorkflowBpmnFormReference reference : document.formReferences()) {
    Long formId = reference.formId();
    String nodeKey = reference.nodeKey();
}
```

`formReferences` 在构造时复制，部署服务据此读取 `wf_form` 并生成写入 Flowable 业务制品 `approvaplat/forms-v1.json` 的不可变快照。
