# Flowable 8 复杂审批兼容契约

## 1. 契约标识与适用范围

- 契约版本：`WF-COMPAT-1.0`
- 实施基线：`244def405fdcdc260896fa95d43f1a87c3eb4f39`
- 适用模块：`ruoyi-flowable`、`ruoyi-admin`、`vite` 和受支持 BPMN 模型
- 参考工程：仅用于识别旧入口和 XML 约定，不继承其异常吞并、自由脚本、未校验身份或无审计的实现

本契约冻结动态多实例、`multiInstanceHandler`、`userTaskListener`、
`startProcessByDefKey` 以及并行/多实例执行树动作的正式语义。模型、API、
领域服务、数据库状态、历史和浏览器交互必须共同遵守本契约；不满足安全前置的
请求必须在引擎写命令前拒绝，或由同一 Spring 事务完整回滚。

## 2. 统一错误、权限与审计

| 场景 | HTTP 语义 | 副作用要求 |
| --- | ---: | --- |
| 参数格式、空集合、停用或不存在用户 | `400` | 不创建/删除 task 或 execution，不写成员快照 |
| 未登录或缺少 URL 权限 | `401/403` | 不进入领域写服务 |
| 有 URL 权限但不是当前任务办理人 | `403` | runtime、history、comment 和业务表均不变化 |
| 对象不存在且无可见历史 | `404` | 零副作用 |
| 过期 task、revision 不一致、非法状态或不安全执行树 | `409` | 零副作用；客户端刷新服务端状态后重试 |
| 引擎或持久化数据不一致 | `500` | 当前事务回滚并保留服务端关联日志 |

动态多实例调整复用 `workflow:process:approval` URL 权限；对象权限进一步要求
操作人是同一多实例根下的当前活动任务办理人。所有成功写动作同时写入结构化
Flowable comment 和若依操作日志。comment 至少包含固定动作、操作人、节点、
调整前后 revision、目标用户/任务和业务意见，不记录 Token、密码或表单正文。

## 3. 多实例模型契约

### 3.1 固定 XML 语义

受控动态会签/或签只支持并行 `UserTask`，并固定使用：

```xml
<bpmn:userTask id="approveTask" flowable:assignee="${assignee}">
  <bpmn:multiInstanceLoopCharacteristics
      isSequential="false"
      flowable:collection="${multiInstanceHandler.getUserIds(execution)}"
      flowable:elementVariable="assignee">
    <bpmn:completionCondition xsi:type="bpmn:tFormalExpression">${nrOfCompletedInstances == nrOfInstances}</bpmn:completionCondition>
  </bpmn:multiInstanceLoopCharacteristics>
</bpmn:userTask>
```

- 会签 `ALL`：完成条件固定为 `${nrOfCompletedInstances == nrOfInstances}`。
- 或签 `ANY`：完成条件固定为 `${nrOfCompletedInstances &gt; 0}`。
- 集合表达式、元素变量和办理人表达式不接受其他动态方法或脚本。
- 串行多实例仍可使用受控静态集合执行，但不开放运行时加签/减签；调整请求返回 `409`。
- 子流程、CallActivity、补偿、事件子流程或多个同 key 活动形成的歧义执行树不开放动态调整。

受控动态多实例的初始成员只能由一个同步、非多实例、非补偿、无边界事件且
无 skip/async 配置的普通 `UserTask` 提交。该前驱必须只有一条出边，动态节点也
必须只有这一条入边，且该 `SequenceFlow` 必须无 condition 和 skip 表达式并直接
连接两项任务。`StartEvent`、网关、服务任务、子流程或其他节点直连动态多实例，
以及前驱分支或多入边模型，必须在草稿保存、正式保存和部署前返回 `400`，不能
允许模型在真实进入节点时才因缺少成员集合失败。

动态多实例的任一后继顺序流或可达边界事件路径不得回到同一动态活动。服务端
在保存和部署门禁中遍历全部可达后继；任何能够再次进入同一 `activityId` 的回路
均返回 `400`。这是因为成员快照、revision 和模式以活动 ID 存在流程实例作用域，
在没有轮次隔离前不得跨轮复用。

### 3.2 集合变量和服务端快照

`multiInstanceHandler` 只读取 `wfMiUsers_<activityId>`。`activityId` 必须符合
受控 BPMN ID 语法，变量值只允许包含 1 至 100 个正整数用户 ID 的集合。
处理器通过正式 `sys_user` 主数据重新解析用户，删除、停用和不存在用户使整次
命令失败；有效结果保持首次出现顺序并去重。

处理器在同一引擎事务内建立以下服务端保留变量：

- `_wfMiMembers_<activityId>`：当前正式成员的不可变顺序快照。
- `_wfMiRevision_<activityId>`：从 `0` 开始的调整版本。
- `_wfMiMode_<activityId>`：`ALL` 或 `ANY`。

保留变量不进入普通表单回显，不允许客户端直接提交或修改。前序任务通过
`nextUserIds` 指定动态办理人时，服务端先校验用户，再写入批准集合变量，随后
完成来源任务；多实例创建失败时两项写入共同回滚。

### 3.3 动态加签

加签请求包含当前活动 `taskId`、`expectedRevision`、业务意见和待加入用户集合。
服务端依次核验 URL 权限、当前办理人、活动实例、并行多实例模型、revision、
正式用户、当前成员和引擎计数。目标用户不得已存在于成员快照，不得超过 100 人
上限。每个用户通过 `RuntimeService.addMultiInstanceExecution` 创建真实 execution
和 task，最后更新成员快照与 revision，并重新查询 task、execution、计数和办理人。

同一 revision 的并发请求只允许一个提交；其余请求必须因 Flowable optimistic
lock 或 revision 前置校验返回 `409`。不允许通过直接修改 `nrOfInstances`、
`nrOfActiveInstances`、`nrOfCompletedInstances` 或 `loopCounter` 实现加签。

### 3.4 动态减签

减签请求包含当前活动 `taskId`、`targetTaskId`、`expectedRevision` 和业务意见。
目标必须是同一多实例根下尚未完成的活动 sibling task，且不存在 owner、委派或
挂起状态。已完成成员、历史 execution、其他节点、其他实例和过期 task 均返回
`409`。减签前必须至少存在两个活动实例，保证删除后仍有合法办理路径。

服务端使用 `RuntimeService.deleteMultiInstanceExecution(targetExecutionId, false)`
删除目标 execution，随后更新成员快照与 revision，并重新核对活动 task、execution
和 Flowable 计数。不得物理删除已完成历史，也不得把减签伪装成任务完成。

## 4. userTaskListener 契约

参考设计器可生成 `class`、`expression`、`delegateExpression` 和脚本四类任务监听器，
事件列表包含 `create`、`assignment`、`complete`、`delete`、`update`、`timeout`；
参考仓库没有实际引用 `userTaskListener` 的 BPMN/XML 样本。目标平台只把下述
`delegateExpression` 形式及三个事件纳入兼容范围，其余参考能力均按不受支持处理。

模型只允许 `delegateExpression="${userTaskListener}"`，且仅允许以下事件：

| 事件 | 领域行为 |
| --- | --- |
| `create` | 核验初始 assignee/owner 为正式启用用户，写创建审计 |
| `assignment` | 核验变更后的 assignee/owner，写分配审计 |
| `complete` | 保留任务完成事实和操作人审计，不改写业务终态 |

`delete`、`timeout`、脚本、任意 class、任意 expression、字段注入和自定义属性解析器
均不属于兼容范围，模型保存或部署前返回 `400`。监听器只委托受控领域服务，不
执行用户输入脚本，不访问 Spring 容器，不发起外部网络调用，也不写
`processStatus`。取消、终止和驳回形成的业务终态始终高于自然完成监听逻辑。

## 5. startProcessByDefKey 契约

仓库外 Java 调用方使用稳定签名：

```java
void startProcessByDefKey(String procDefKey, Map<String, Object> variables)
```

目标内部服务可使用不同方法名返回 `WorkflowProcessInstanceSnapshot`，可选重载也
可以传入 `businessKey`，但上述兼容签名及返回类型不得改变，且任何入口均不得绕过
标准发起链。服务端在当前登录身份和
同一事务中解析默认租户下最新激活定义，然后复用按 definitionId 发起的 starter
授权、部署表单快照、变量 schema、附件绑定、`initiator`、`processStatus` 和
提交快照逻辑。无定义返回 `404`；只有挂起定义或解析期间最新版发生变化返回
`409`；变量、身份和附件错误沿用标准发起错误语义。兼容方法不新增匿名 HTTP
入口，不捕获后打印异常，也不把底层 SQL/Flowable 信息返回调用方。

生产 Bean 不得另行暴露原始 BPMN 字节部署或 `RuntimeService.startProcessInstanceById`
薄封装。模型发布只允许进入 `WorkflowModelService` 的校验、版本和部署表单链；标准及
兼容发起只允许进入 `WorkflowProcessStartService`。测试 fixture 可在测试源码中直接
使用 Flowable 公共 API，但该能力不得进入生产组件或 Controller。

`WorkflowProcessEngineAdapter` 是仓库内部任务命令组件，不属于冻结的仓库外 Java API。
基线中曾公开但只有仓库测试使用的原始 BPMN 部署和按 definitionId 薄封装，在仓库调用方
迁移到上述正式链后删除；禁止外部调用方继续链接这两个方法。仓库外唯一冻结的发起兼容
签名仍是本节定义的 `WorkflowProcessStartService.startProcessByDefKey(String, Map)`，
删除低层方法不得改变该签名、权限、事务或持久化语义。

## 6. 复杂执行树动作

### 6.1 退回

退回只支持能够证明目标唯一、位于同一主流程安全作用域且不存在活动 sibling
分支的执行树。目标必须是当前节点之前真实完成的用户任务，并通过部署 BPMN
可达性和历史顺序双重验证。并行、多实例、子流程、CallActivity、事件边界、
补偿或目标歧义统一返回 `409`，runtime、history、comment 和业务表零变化。

### 6.2 驳回

驳回是整实例原子业务终止，不是单 execution 移动。当前办理人可在普通、并行或
多实例活动任务上驳回；服务端冻结全部活动 task/execution，写入唯一业务终态
`rejected`、结构化意见和抄送审计，然后终止整个运行实例。所有活动 sibling 和
多实例 execution 必须一并结束，历史实例、任务、comment 和 `processStatus` 保留，
自然完成监听器不得把 `rejected` 覆盖为 `completed`。重复驳回返回 `409`。

### 6.3 撤回

撤回要求来源任务由当前用户真实完成，且来源之后的全部直接后继仍处于活动、
未认领、未开始、未委派、未完成状态，不存在 timer/async/服务任务/附件绑定等
不可逆副作用。若直接后继为多个安全并行用户任务，服务端可使用一次
`moveExecutionsToSingleActivityId` 原子合并回来源节点；多实例根、子流程、
CallActivity、已处理后继或拓扑歧义返回 `409`。写后必须只存在一个恢复后的来源
任务，并重新核对 runtime、history 和审批意见。

### 6.4 取消

取消的客户端 `procInsId` 可以是根实例或任意活动 CallActivity
子实例，但服务端必须校验 `rootProcessInstanceId` 和 `superExecutionId`
后提升为根业务实例。对象授权以根实例发起人为准；授权通过后冻结
整棵活动执行树，将结构化取消意见写入每个任务真实所属实例，仅对根
实例写入 `canceled` 并执行删除。写后必须确认根/子实例、execution 和 task
零残留，且根历史终态唯一为 `canceled`；禁止单独删除子实例。

## 7. 状态优先级

稳定流程状态为 `running`、`suspended`、`completed`、`canceled`、`rejected`、
`terminated`。优先级为显式业务终态 `canceled/rejected/terminated` 高于 Flowable
自然完成状态；自然完成只允许把 `running` 收敛为 `completed`。未知或重复状态变量
属于数据一致性错误，必须回滚当前命令。

## 8. 强制验收矩阵

| 编号 | 场景 | 预期结果 |
| --- | --- | --- |
| `MI-00` | 合法普通用户任务直连；开始/网关/服务任务直连；多入边、分支、条件流和回路 | 仅合法直连可保存部署；其余 `400` 且不产生运行实例 |
| `MI-01` | handler 正常集合、重复 ID、停用/不存在用户、空集、超限 | 正常集合有序去重；非法输入 `400` 且不创建实例 |
| `MI-02` | ALL/ANY 初始执行和部分完成 | 任务数、完成条件、历史和终态与模式一致 |
| `MI-03` | 加签、减签、重复用户、过期 target、最后活动实例 | 成功真实改变 execution；拒绝分支 `400/409` 零副作用 |
| `MI-04` | 同 revision 并发加签/减签/完成 | 仅契约允许的一个结果提交，其余 `409` |
| `UL-01` | 三个批准 listener 事件 | 身份校验和审计持久化，不改业务终态 |
| `UL-02` | delete/timeout/脚本/任意 Bean | 保存或部署前拒绝，实例不启动 |
| `SK-01` | key 与 definitionId 发起同一最新版 | starter、变量、附件、快照和审计结果一致 |
| `SK-02` | 无定义、挂起、并发部署、非法变量 | `404/409/400`，无实例和附件半绑定 |
| `MV-01` | 串行安全退回 | 唯一目标恢复，意见和历史完整 |
| `MV-02` | 并行/多实例退回 | `409` 且 runtime/history/comment 零变化 |
| `MV-03` | 普通/并行/多实例驳回 | 整实例 `rejected`，无活动 sibling 或幽灵 execution |
| `MV-04` | 单后继/安全并行后继撤回 | 原子恢复唯一来源任务 |
| `MV-05` | 后继已认领/处理或含不可逆副作用 | `409` 且零副作用 |

以上用例必须同时具备领域单元测试、真实 MySQL Flowable 集成测试、直接 API 与
真实浏览器证据；静态 XML、构建成功或 mock 调用不能单独关闭门禁。
