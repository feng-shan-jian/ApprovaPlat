# WorkflowProcessStartService

## 作用

`WorkflowProcessStartService` 是草稿正式提交事务中的 Flowable 启动协作服务。用户先创建或保存持久化草稿，再由 `WorkflowProcessDraftService.submit(...)` 建立当前身份和写事务边界；人工启动入口统一收敛在草稿提交链。

## 使用方式

`WorkflowProcessDraftService` 在锁定本人活动草稿、复核乐观锁版本并规范化业务主键与多实例成员后，调用包级 `startDraft(...)`。该方法加入外层现有事务并复用已核验身份和持久化草稿，返回真实实例快照及本次 schema 校验得到的规范化变量，供草稿原子更新为 `SUBMITTED`。

## 草稿提交启动链路

1. 复核草稿所有者、流程定义 key/version/deployment 关系和默认租户约束。
2. 持有最新版定义范围锁，阻止复核后并发部署新版本。
3. 按草稿绑定定义重新执行 starter 授权，并读取同一部署的开始表单和 BPMN Model。
4. 对比草稿持久化的来源、表单主键、表单键、开始节点、正文和 SHA-256；完全一致时继续，漂移时返回稳定冲突。
5. 使用 `WorkflowStartVariableValidator` 按部署快照 schema 校验并深复制正式提交变量。
6. 通过 `prepareDraftSubmissionVariables(...)` 锁定草稿附件，完成新增 `TEMP -> DRAFT`、移除项清理、物理文件校验和安全变量投影。
7. 启动前再次确认定义仍激活且 deployment 关系不变。
8. 服务端生成多实例保留变量，写入 `initiator=<当前用户ID>`、`processStatus=running` 和不可变开始表单提交快照。
9. 仅调用一次 `RuntimeService.startProcessInstanceById(...)` 创建真实实例。
10. 通过 `bindDraftStartAttachments(...)` 将附件原子迁移为 `BOUND`；外层服务随后持久化规范变量、业务主键、成员选择、实例主键和 `SUBMITTED` 状态。任一步失败都会回滚实例、附件和草稿状态。

重复提交已处于 `SUBMITTED` 的草稿时，草稿服务直接返回持久化的原实例主键，以已提交记录完成幂等响应。

## 权限与数据来源

- 包含业务资源子部署的受管定义必须匹配不可变参与者部署快照；其余定义执行 Flowable starter identity link 门禁。
- deployment 来自持久化草稿绑定的真实流程定义，提交请求只携带草稿主键与业务数据。
- Flowable 业务制品 `approvaplat/forms-v1.json` 的开始节点不可变快照是表单唯一来源。
- `workflow:process:start` 仍用于发起页面和开始表单读取；草稿创建、保存与提交继续使用各自正式权限。

## 变量约束

提交 `variables` 包含开始表单 `__vModel__` 白名单字段。`initiator`、`processStatus`、实例/定义/部署 ID、发起人、业务主键和 `__ruoyi_workflow_` 前缀变量由服务端独占生成。字段必填、类型、字符串长度、集合规模、嵌套深度、节点总数和序列化总大小均由服务端验证。

## 不可变提交快照

部署表单快照解决“使用哪一版 schema”，内部提交快照解决“用户当时提交了什么值”。服务将 deployment ID、form ID、form key、开始节点 key 和附件安全投影后的字段值编码为受限 JSON 字符串，固定写入 `__ruoyi_workflow_form_submission_v1`。该变量只由服务端生成。

详情服务后续从该变量的 `HistoricVariableUpdate` 读取开始提交值与真实写入时间。流程运行中其他节点修改同名全局变量时，开始表单仍回显首次提交快照；内部快照缺席的实例在详情中显示“申请表单快照缺失”。

## 异常语义

- `400`：草稿提交参数、业务主键、人员选择或表单变量不合法。
- `403`：草稿所有权或实时 starter 身份校验失败。
- `404`：草稿、流程定义或附件不存在。
- `409`：草稿版本、定义版本、部署、快照、定义激活状态或附件状态发生变化。
- `500`：持久化关联、部署快照或引擎返回存在内部数据异常。

## 接入约束

人工发起统一经过“发起页面 → 创建/保存草稿 → 提交草稿 → `startDraft(...)` → Flowable”。Controller、其他 Service 和 Delegate 复用该唯一生产链创建人工发起实例。
