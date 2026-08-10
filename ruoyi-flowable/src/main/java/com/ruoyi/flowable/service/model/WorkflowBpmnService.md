# WorkflowBpmnService

## 作用

`WorkflowBpmnService` 是模型保存、模型部署和已部署 XML 读取共用的安全边界。调用方只能使用 `validate(byte[])`，成功后获得 `WorkflowBpmnDocument` 和已解析的节点表单引用。

## 输入边界

- BPMN 最大为 2 MiB。
- 字节必须是严格 UTF-8；非法字节不会被替换。
- StAX 解析器关闭 DTD、外部实体和实体替换，并安装拒绝外部资源的 `XMLResolver`。
- 安全 `XMLStreamReader` 直接交给 Flowable 8 公共 `BpmnXMLConverter`。

## 业务校验

- 至少包含一个 `isExecutable=true` 的流程。
- 每个流程递归范围内恰好一个开始节点，且开始节点必须配置表单。
- 开始节点和用户任务的表单键必须严格匹配 `key_<正Long>`。
- 所有递归 `ScriptTask` 均被拒绝。
- 服务任务和监听器 class 仅允许 `com.ruoyi.flowable.delegate.*`、`com.ruoyi.flowable.listener.*`。
- 服务任务和执行监听器的 delegateExpression 仅允许 `${workflowBeanName}` 或 `#{workflowBeanName}` 形式的受控 Bean 引用。
- 每个用户任务必须各配置一次 `delegateExpression="${userTaskListener}"` 的 `create`、`assignment`、`complete` 三个事件；缺失、重复或额外事件均拒绝。字段注入、脚本、事务回调和自定义属性解析器也全部被拒绝，使静态、表达式、委派、转办及动态多实例产生的 assignee 都在同一引擎事务内实时核验办理资格。
- 动态多实例只允许同步、不可跳过的并行用户任务使用 `${multiInstanceHandler.getUserIds(execution)}`，并同时固定 `assignee` 元素变量、`${assignee}` 办理人以及 ALL/ANY 完成条件；目标节点的 async、async leave、非排他、skip、边界事件和补偿语义均被拒绝。
- 动态多实例必须由主流程中的同步普通用户任务直接初始化；该前驱必须恰有一条入边和一条无条件出边，阻止开始节点、网关、服务任务或并行 token 产生无法提交 `nextUserIds` 的执行树。
- 原始 XML 中受控 handler 方法表达式的出现次数必须与完整受控节点一一对应，不能藏入条件、文档或未映射扩展字段。
- 串行或并行静态集合多实例保持兼容，但不允许使用可实例化的 `collectionHandler`；动态多实例不允许 loop cardinality、索引变量、聚合或异步离开配置。
- 普通表达式只允许变量、属性、索引、字面量和运算符，禁止方法调用、反射、运行时和容器对象访问。
- 最后调用 `RepositoryService.validateProcess`，任何非 warning 错误都会拒绝保存或部署。

所有失败均返回 HTTP 400 对应的 `ServiceException`，原始 XML、解析器消息和内部类名不会进入对外提示。

## 返回数据

```java
WorkflowBpmnDocument document = workflowBpmnService.validate(bpmnBytes);
for (WorkflowBpmnFormReference reference : document.formReferences()) {
    Long formId = reference.formId();
    String nodeKey = reference.nodeKey();
}
```

`formReferences` 在构造时复制，部署服务据此读取 `wf_form` 并生成 `wf_deploy_form` 不可变快照。
