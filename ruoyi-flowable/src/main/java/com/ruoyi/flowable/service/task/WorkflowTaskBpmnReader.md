# WorkflowTaskBpmnReader

## 作用

按流程定义读取部署 BPMN 模型与主流程，返回不可变 `WorkflowTaskBpmnSnapshot`。缺失定义保持 404，部署、模型或主流程关联异常保持 500。

## 使用与边界

组件只读取 `RepositoryService`，不做路径决策。撤回、完成和退回应用服务在当前事务快照内读取后，由各自业务规则解释节点。

```java
WorkflowTaskBpmnSnapshot snapshot = bpmnReader.require(processDefinitionId);
```
