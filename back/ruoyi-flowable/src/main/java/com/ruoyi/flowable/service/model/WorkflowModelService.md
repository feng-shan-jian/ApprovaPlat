# WorkflowModelService

## 作用

`WorkflowModelService` 负责 Flowable 模型的查询、版本、BPMN 保存、安全删除和部署。所有引擎读写均通过 `WorkflowEngineOperations`，写操作会在同一 Spring 事务内重新核验当前用户并设置 Flowable 操作人。

## 主要接口

| 方法 | 说明 |
| --- | --- |
| `list(filter, pageNum, pageSize)` | 查询每个模型 key 的最新版本，使用引擎原生 `count/listPage` |
| `historyList(filter, pageNum, pageSize)` | 查询指定 key 的旧版本，不包含当前最新版 |
| `getModel(modelId)` | 返回模型元数据、可选 BPMN 和模型级表单内容 |
| `getBpmnXml(modelId)` | 返回经过安全校验的 BPMN XML |
| `createModel(request)` | 创建未设计模型并写入可信创建人 |
| `updateModel(request)` | 修改名称、分类和兼容 metaInfo，不允许改变模型 key |
| `saveModel(request)` | 保存 BPMN；已部署或历史版本自动创建新的最高版本并返回新模型主键 |
| `promoteToLatest(modelId)` | 将旧版本内容复制为新的最高版本 |
| `deleteModels(modelIds)` | 预检全部模型后删除，拒绝任何已部署或已有定义的模型 |
| `deployModel(modelId)` | 校验 BPMN、分类和全部节点表单，部署并保存不可变表单快照 |

## 关键约束

- BPMN 由 `WorkflowBpmnService` 统一执行 UTF-8、XML 外部实体、危险实现和引擎规则校验。
- 模型 `metaInfo` 使用 Jackson 3 结构化读写，保留未知兼容字段；损坏数据返回稳定服务异常，不回显原文。
- 部署前按每个 `StartEvent/UserTask` 的 `key_<formId>` 读取有效 `wf_form`，并通过共享 `WorkflowFormTemplateValidator` 重新校验真实 JSON；部署后把名称和正文整体写入 `wf_deploy_form`。
- 同一个模型版本只允许成功部署一次；再次保存已部署或历史版本时服务端自动另存为新的最高版本，避免用户手动切换版本。
- 每次保存必须携带 UUID `requestId`。服务端在 `wf_model_save_idempotency` 中绑定可信用户、来源模型和载荷 SHA-256；完成后的同请求重放返回首次 `modelId`，相同主键被不同用户、来源或载荷复用时返回 409。
- 保存事务固定按“幂等请求行、同 key 最早版本稳定锚点、最高版本、来源模型”顺序执行 `FOR UPDATE` 当前读；锚点先串行化同 key 写入，避免最高版本范围锁与新版本插入死锁，版本判断和新版本号只使用锁定投影。
- Flowable 模型、编辑器 BPMN 源码和幂等完成结果在同一可重复读事务提交；任一步骤失败都会整体回滚。
- `wf_deploy_form.content` 是部署时快照，运行时不得回连当前 `wf_form.content` 重建。
- 快照写入数量、部署产生的定义数量与预期不一致时抛出冲突，依靠统一事务回滚 `ACT_*` 和 `wf_*`。
- 模型批量删除先完成全部预检，避免前半批已删除、后半批失败。

## 最小接入示例

```java
WorkflowModelDto request = new WorkflowModelDto();
request.setModelName("报销审批");
request.setModelKey("expense");
request.setCategory("finance");
String modelId = workflowModelService.createModel(request);
```
