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

仓库外 Java 调用方需要沿用旧入口时，使用精确兼容签名：

```java
processStartService.startProcessByDefKey(
        "expense",
        Map.of("amount", 1280, "reason", "采购办公设备"));
```

该方法按旧契约返回 `void`。需要业务主键时可调用
`startProcessByDefKey(String procDefKey, String businessKey, Map<String, Object> variables)`；
实例结果仍通过正式运行中/历史查询入口读取，不向兼容调用方返回可变引擎对象。

## 发起链路

1. 校验并规范化 `processDefinitionId` 和可选 `businessKey`。
2. 通过 `WorkflowEngineOperations.writeAsCurrentUser(...)` 开启事务，重新核验当前正式用户并设置 Flowable 认证身份。
3. 从 `RepositoryService` 查询定义并取得服务端真实 `deploymentId`。
4. 对真实 `deploymentId` 的 `ACT_RE_DEPLOYMENT` 行执行 `SELECT ... FOR UPDATE`，与受控部署删除形成同一线性化顺序。
5. 复用 `WorkflowProcessQueryService.getProcessForm(...)` 校验同租户最新版本、激活状态、starter 用户/ROLE/DEPT 身份，并精确读取 BPMN 开始节点的 `wf_deploy_form.content`。
6. 使用 `WorkflowStartVariableValidator` 按部署时固化的表单 schema 校验并深度复制客户端变量。
7. 对上传字段锁定当前用户的 `TEMP` 附件，将 UUID 列表替换为六项安全元数据投影。
8. 在真正写入前再次按定义 ID 查询激活状态，阻止校验期间被挂起或删除的定义继续发起。
9. 服务端写入 `initiator=<当前用户ID>`、`processStatus=running`，并把附件安全投影后的开始表单值编码为内部不可变提交快照。
10. 业务变量与内部快照随同一次 `RuntimeService.startProcessInstanceById(...)` 原子写入，避免实例已经创建但开始提交值缺失。
11. 将附件绑定到真实实例和部署快照开始节点；任一绑定失败均回滚附件、内部快照和流程发起。

## 按定义 key 兼容链

1. 在显式 `REPEATABLE_READ` 外层写事务及 `WorkflowEngineOperations.writeAsCurrentUser(...)` 的同一认证边界内解析 key，禁止事务外先查定义再发起，且不会以低隔离外层事务削弱统一引擎写边界的 revision 并发契约。
2. 查询条件固定为精确 `processDefinitionKey`、`processDefinitionWithoutTenantId` 和 `latestVersion`，不会跨租户选择定义，也不会回退到旧激活版本。
3. 最新版不存在返回 `404`，最新版挂起返回 `409`。
4. 解析出 definitionId 后调用与 `start(StartProcessRequest)` 相同的 starter、部署快照、变量、附件、身份和审计链。
5. 先取得目标 deployment 行锁，再在真正调用 `RuntimeService` 前通过 `ACT_UNIQ_PROCDEF` 对默认租户该 key 的当前最新版执行有界 `FOR UPDATE` 当前读；它能看见变量校验期间已提交的新版本，并把锁后的并发部署串行化到本次发起事务结束。definitionId、deploymentId 或激活状态变化时返回 `409`，旧版本不会产生新实例。

## 权限与数据来源

- 无 starter identity link 的定义视为公开可发起。
- 有 starter 限制时，必须匹配当前用户 ID 或当前有效的 `ROLE<id>` / `DEPT<id>` 候选组。
- deployment 只能来自所选定义，客户端不能提交或替换。
- 表单只能来自 `wf_deploy_form` 的开始节点不可变快照，禁止回连当前 `wf_form`，从而保证旧版本实例使用部署时 schema。

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

key 兼容入口沿用以上错误语义；key 为空或过长返回 `400`，默认租户无定义返回
`404`，最新版挂起或解析期间并发部署返回 `409`。

## 接入约束

真实入口为 `WfProcessController` 的 `POST /workflow/process/start/{processDefId}`。Controller 使用 `workflow:process:start` 权限、操作日志和新旧请求协议归一化，只采信路径中的流程定义 ID；不得在 Controller 中补写 `initiator`、`processStatus`，也不得绕过本服务直接调用 `RuntimeService`。

`startProcessByDefKey` 仅用于受信任的仓库外 Java 兼容调用，不新增匿名 HTTP 入口。调用方必须处于若依已登录用户上下文；服务会在事务内重新核验该用户及 starter 对象权限。
