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
| `deployModel(modelId)` | 校验 BPMN、分类和全部节点表单，部署、保存不可变表单快照并停用历史定义 |

## 关键约束

- BPMN 由 `WorkflowBpmnService` 统一执行 UTF-8、XML 外部实体、危险实现和引擎规则校验。
- 模型 `metaInfo` 使用 Jackson 3 结构化读写，保留未知兼容字段；损坏数据返回稳定服务异常，不回显原文。
- 部署前按每个 `StartEvent/UserTask` 的 `key_<formId>` 读取有效 `wf_form`，并通过共享 `WorkflowFormTemplateValidator` 重新校验真实 JSON；部署后把名称和正文整体写入 Flowable 业务制品 `approvaplat/forms-v1.json`。
- 同一个模型版本只允许成功部署一次；再次保存已部署或历史版本时服务端自动另存为新的最高版本，避免用户手动切换版本。
- 部署与模型保存共用同 key 最早版本稳定锚点锁；新版本部署成功后，同流程标识的旧活动定义会在同一事务自动挂起。挂起仅阻止旧定义承接新实例，不会冻结旧版本中仍在办理的流程实例。
- 每次保存必须携带 UUID `requestId`。服务端在 `wf_model_save_idempotency` 中绑定可信用户、来源模型和载荷 SHA-256；完成后的同请求重放返回首次 `modelId`，相同主键被不同用户、来源或载荷复用时返回 409。
- 保存会在创建 `wf_model_save_idempotency` 行、保存 Flowable 模型或写编辑器源码前，先完成 BPMN、正式表单、自动抄送、参与者身份目标、条件和调用活动作者校验；任何无效作者引用均保证模型源码和幂等记录零变化。
- 保存事务固定按“幂等请求行、同 key 最早版本稳定锚点、最高版本、来源模型”顺序执行 `FOR UPDATE` 当前读；锚点先串行化同 key 写入，避免最高版本范围锁与新版本插入死锁，版本判断和新版本号只使用锁定投影。
- Flowable 模型、编辑器 BPMN 源码和幂等完成结果在同一可重复读事务提交；任一步骤失败都会整体回滚。
- `approvaplat/forms-v1.json` 中的 `content` 是部署时快照，运行时不得回连当前 `wf_form.content` 重建。
- 业务制品资源数量、部署产生的定义数量与预期不一致时抛出冲突，依靠统一事务回滚流程部署和制品子部署。
- 自动抄送 `FORM_USER_FIELD` 与参与者 `FORM_USER` 共用 `WorkflowAuthorFormFieldCatalog`：任务规则按节点权限化快照隔离，流程完成抄送按流程汇总；字段必须存在、可见、可读且为单值，同名字段在任一节点为隐藏或复合类型时流程级规则失败关闭。
- 部署编译固定按“服务扩展 -> 条件分支 -> 受控循环 -> 参与者 -> DMN -> 调用活动 -> SLA”传递字节，最终字节再次通过 Flowable 编译部署门禁后才写入部署资源；每一阶段快照与表单权限快照在同一事务写入唯一业务制品子部署。
- 模型批量删除先完成全部预检，避免前半批已删除、后半批失败。

## 最小接入示例

```java
WorkflowModelDto request = new WorkflowModelDto();
request.setModelName("报销审批");
request.setModelKey("expense");
request.setCategory("finance");
String modelId = workflowModelService.createModel(request);
```
