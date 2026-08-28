# WorkflowModelService

## 作用

`WorkflowModelService` 负责 Flowable 模型的查询、版本、BPMN 保存、安全删除和部署。所有引擎读写均通过 `WorkflowEngineOperations`，写操作会在同一 Spring 事务内重新核验当前用户并设置 Flowable 操作人。

## 主要接口

| 方法 | 说明 |
| --- | --- |
| `list(filter, pageNum, pageSize)` | 查询每个模型 key 的最新版本，使用引擎原生 `count/listPage` |
| `historyList(filter, pageNum, pageSize)` | 查询指定 key 的先前版本，当前最新版由主列表展示 |
| `getModel(modelId)` | 返回模型元数据、可选 BPMN 和模型级表单内容 |
| `getBpmnXml(modelId)` | 返回经过安全校验的 BPMN XML |
| `createModel(request)` | 创建未设计模型并写入可信创建人 |
| `updateModel(request)` | 修改名称、分类和结构化 metaInfo；模型 key 在创建后保持不可变 |
| `saveModel(request)` | 按 Flowable revision 保存；返回真实模型主键、版本和最新修订号 |
| `promoteToLatest(modelId)` | 将指定先前版本内容复制为新的最高版本 |
| `deleteModels(modelIds)` | 预检全部模型后删除；已部署或已有定义的模型返回稳定冲突 |
| `deployModel(modelId)` | 校验 BPMN、分类和全部节点表单，部署、保存不可变表单快照并停用历史定义 |

## 关键约束

- BPMN 由 `WorkflowBpmnService` 统一执行 UTF-8、XML 外部实体、危险实现和引擎规则校验。
- 模型 `metaInfo` 使用 Jackson 3 结构化读写，保留现有扩展字段；损坏数据返回稳定服务异常，对外响应使用通用消息。
- 部署前按每个 `StartEvent/UserTask` 的 `key_<formId>` 读取有效 `wf_form`，并通过共享 `WorkflowFormTemplateValidator` 重新校验真实 JSON；部署后把名称和正文整体写入 Flowable 业务制品 `approvaplat/forms-v1.json`。
- 同一个模型版本只允许成功部署一次；再次保存已部署或先前版本时服务端自动另存为新的最高版本，版本切换由服务端完成。
- 新版本部署成功后，同流程标识的先前活动定义会在同一事务自动挂起。挂起定义停止承接新实例，其中已有流程实例继续办理。
- 模型加载返回 Flowable `REV_` 对应的 `revision`；保存必须携带该值作为 `expectedRevision`，Flowable revision 是唯一并发版本源。
- 提交内容与当前内容相同会直接返回当前模型并保持数据库原值；内容发生变化且 revision 已变化时返回 HTTP 409 和 `WORKFLOW_MODEL_VERSION_CONFLICT`。
- 更新同一模型依赖 Flowable revision 乐观锁；新建版本依赖模型自然版本唯一约束。唯一键或 revision 竞争失败方直接返回稳定 409，提交方根据最新模型快照明确重试。
- 保存会在 Flowable 模型或编辑器源码写入前完成 BPMN、正式表单、自动抄送、参与者身份目标、条件和调用活动作者校验；任何无效作者引用均保证模型源码零变化。
- `ACT_RE_MODEL` 由 Flowable ModelService 独占读写和加锁；异常翻译使用异常类型与引擎语义，数据库索引名和错误文本仅用于内部诊断。
- `approvaplat/forms-v1.json` 中的 `content` 是部署时快照，也是运行时表单的唯一来源。
- 业务制品资源数量、部署产生的定义数量与预期不一致时抛出冲突，依靠统一事务回滚流程部署和制品子部署。
- 自动抄送 `FORM_USER_FIELD` 与参与者 `FORM_USER` 共用 `WorkflowAuthorFormFieldCatalog`：任务规则按节点权限化快照隔离，流程完成抄送按流程汇总；字段必须存在、可见、可读且为单值，同名字段在任一节点为隐藏或复合类型时流程级规则失败关闭。
- 部署编译固定按“服务扩展 -> 条件分支 -> 受控循环 -> 参与者 -> DMN -> 调用活动 -> SLA”传递字节，最终字节再次通过 Flowable 编译部署门禁后才写入部署资源；每一阶段快照与表单权限快照在同一事务写入唯一业务制品子部署。
- 模型批量删除先完成全部预检，再在同一事务删除整批。

## 最小接入示例

```java
WorkflowModelDto request = new WorkflowModelDto();
request.setModelName("报销审批");
request.setModelKey("expense");
request.setCategory("finance");
String modelId = workflowModelService.createModel(request);
```
