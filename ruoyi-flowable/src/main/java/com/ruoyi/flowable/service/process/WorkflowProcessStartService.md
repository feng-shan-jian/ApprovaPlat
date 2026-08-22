# WorkflowProcessStartService

## 作用

`WorkflowProcessStartService` 是流程发起的唯一业务写入口。它不信任客户端提供的部署、发起人、流程状态或变量 schema，而是在一个 Spring 事务内重新读取 Flowable 定义和部署表单快照后执行真实发起。

## 使用方式

注入服务并提交动作专用请求：

```java
WorkflowProcessInstanceSnapshot started = processStartService.start(
        new StartProcessRequest(
                "expense:3:12001",
                "expense-order-42",
                Map.of("amount", 1280, "reason", "采购办公设备")));
```

返回值只包含实例 ID、定义 ID、业务主键和挂起状态，不向调用方暴露可变的 Flowable `ProcessInstance`。

## 发起链路

1. 校验并规范化 `processDefinitionId` 和可选 `businessKey`。
2. 通过 `WorkflowEngineOperations.writeAsCurrentUser(...)` 开启事务，重新核验当前正式用户并设置 Flowable 认证身份。
3. 从 `RepositoryService` 查询定义并取得服务端真实 `deploymentId`。
4. 对真实 `deploymentId` 的 `ACT_RE_DEPLOYMENT` 行执行 `SELECT ... FOR UPDATE`，与受控部署删除形成同一线性化顺序。
5. 直接调用 `WorkflowProcessQueryService.loadStartFormInCurrentTransaction(...)`，复用外层已核验身份和真实定义，一次完成最新版本、激活状态、starter 用户/ROLE/DEPT 授权，并只读取一次 BPMN Model；同一模型同时用于开始节点、表单快照和多实例字段。
6. 使用 `WorkflowStartVariableValidator` 按部署时固化的表单 schema 校验并深度复制客户端变量。
7. 对上传字段锁定当前用户的 `TEMP` 附件，将 UUID 列表替换为六项安全元数据投影。
8. 在真正写入前再次按定义 ID 查询激活状态，阻止校验期间被挂起或删除的定义继续发起。
9. 服务端写入 `initiator=<当前用户ID>`、`processStatus=running`，并把附件安全投影后的开始表单值编码为内部不可变提交快照。
10. 业务变量与内部快照随同一次 `RuntimeService.startProcessInstanceById(...)` 原子写入，避免实例已经创建但开始提交值缺失。
11. 将附件绑定到真实实例和部署快照开始节点；任一绑定失败均回滚附件、内部快照和流程发起。

直接发起只由 `start(...)` 建立一次 `writeAsCurrentUser` 写边界。草稿提交由 `WorkflowProcessDraftService.submit(...)` 建立唯一写边界，包级 `startDraft(...)` 直接接收外层身份、已规范化业务主键和多实例选择，不再开启事务或重新解析身份。两条入口在完成各自准备后统一调用私有引擎写入段：合并业务变量、生成多实例保留变量、写入 `initiator`、`processStatus=running` 和不可变表单提交快照，再且仅再调用一次 `startProcessInstanceById(...)`。

`startDraft(...)` 返回真实实例快照和本次唯一一次 schema 校验得到的规范化变量。草稿服务使用该结果更新 `SUBMITTED`，不会重新解析 JSON 或再次校验变量。

## 权限与数据来源

- 无 starter identity link 的定义视为公开可发起。
- 有 starter 限制时，必须匹配当前用户 ID 或当前有效的 `ROLE<id>` / `DEPT<id>` 候选组。
- deployment 只能来自所选定义，客户端不能提交或替换。
- 表单只能来自 Flowable 业务制品 `approvaplat/forms-v1.json` 的开始节点不可变快照，禁止回连当前 `wf_form`，从而保证旧版本实例使用部署时 schema。

## 变量约束

客户端 `variables` 只能包含开始表单 `__vModel__` 白名单字段。`initiator`、`processStatus`、实例/定义/部署 ID、发起人和业务主键等服务端变量不可覆盖；客户端字段和表单 schema 也不得使用 `__ruoyi_workflow_` 服务端保留前缀。字段必填、类型、字符串长度、集合规模、嵌套深度、节点总数和序列化总大小均由服务端验证。

## 不可变提交快照

部署表单快照解决“使用哪一版 schema”，内部提交快照解决“用户当时提交了什么值”，两者不能互相替代。服务将 deployment ID、form ID、form key、开始节点 key 和附件投影后的安全字段值编码为受限 JSON 字符串，固定写入 `__ruoyi_workflow_form_submission_v1`。该变量仅由服务端生成，不向客户端开放覆盖入口。

详情服务后续从该变量的 `HistoricVariableUpdate` 读取开始提交值与真实写入时间。流程运行中其他节点修改同名全局变量不会改变开始表单的历史回显；升级前实例没有该内部快照时，详情不会用最终变量伪造开始提交值。

## 异常语义

- `400`：请求、业务主键或表单变量不合法。
- `403`：当前有效用户不满足 starter 身份限制。
- `404`：流程定义在校验前或写入前已不存在。
- `409`：定义不是最新版本、已挂起或校验后状态发生变化。
- `500`：部署关系、表单快照或引擎返回存在内部数据异常。

## 接入约束

真实入口为 `WfProcessController` 的 `POST /workflow/process/start/{processDefId}`。Controller 使用 `workflow:process:start` 权限、操作日志和新旧请求协议归一化，只采信路径中的流程定义 ID；不得在 Controller 中补写 `initiator`、`processStatus`，也不得绕过本服务直接调用 `RuntimeService`。
