# WorkflowParticipantRuleRuntimeService

## 组件简介与作用

`WorkflowParticipantRuleRuntimeService` 负责解释不可变参与者部署快照，并在实时组织目录边界上完成流程发起范围和单实例任务参与者解析。组织关系按请求实时读取，流程引擎信息通过 Flowable 公共 API 获取。

所有可发起定义都必须具备完整部署快照。快照是发起范围和普通单实例任务办理规则的唯一权威来源；普通任务缺失部署资源或规则时按数据一致性错误失败关闭。平台受控多实例成员由多实例冻结协议负责，SLA 编译器生成的升级任务由其冻结 `assignee` 负责，两类任务按部署 BPMN 的精确标志识别且不要求普通任务规则快照。

## 使用方式

```java
Map<String, Boolean> decisions = participantRuleRuntimeService
        .resolveManagedStartDecisions(actor, definitions);

Boolean singleDecision = participantRuleRuntimeService
        .canStartIfManaged(actor, definition);

WfDeployParticipantRule matchedRule = participantRuleRuntimeService
        .assertCanStart(actor, definition);
```

## 公开方法

- `resolveManagedStartDecisions(actor, definitions)`：整批读取部署快照并返回覆盖全部定义的正式决定。Map 中 `true` 表示授权命中，`false` 表示授权范围未命中；同一批多个 `DEPTS` 规则共用一次当前用户有效部门范围读取。
- `canStartIfManaged(actor, definition)`：单定义只读判定，委托同一套批量规则选择和匹配逻辑；快照缺失直接抛出 `PROCESS_START_SCOPE_SNAPSHOT_MISSING`。
- `assertCanStart(actor, definition)`：草稿正式提交写入前复核。授权命中时返回规则供成功审计；授权范围未命中时记录固定失败指标和脱敏日志并抛出 403；快照缺失直接失败。
- `resolveCreatedTask(task)`：Flowable 任务创建时先读取当前部署中的真实 `UserTask`。平台受控多实例和带唯一 SLA 来源属性及冻结办理人的升级任务交给各自正式协议；其余任务按任务规则解析办理人或候选身份，规则缺失抛出 `TASK_PARTICIPANT_SNAPSHOT_MISSING`。

## 关键设计

- `PUBLIC`、`USERS`、`ROLES`、`DEPTS` 保持既有匹配语义；角色使用当前身份候选组，部门使用实时有效直属部门及祖先范围。
- 批量授权先按父部署一次取得规则映射，再按定义 ID 生成决定；业务资源子部署或流程 key 缺席均直接视为数据异常。
- 快照缺规则、版本超出白名单、清单或 JSON 资源损坏时直接返回数据异常。
- 非受控循环、多重或损坏的 SLA 来源属性不能借特殊任务分支绕过普通参与者门禁。
- `assertCanStart` 保留规则对象和授权失败审计，真实发起在写事务内重新核验；列表 boolean 承担展示过滤。

## 最小接入示例

```java
Map<String, Boolean> managed = participantRuleRuntimeService
        .resolveManagedStartDecisions(actor, scannedDefinitions);
for (ProcessDefinition definition : scannedDefinitions)
{
    boolean visible = managed.get(definition.getId());
    // Map 必须覆盖整批定义；缺少决定属于部署快照数据异常。
}
```
