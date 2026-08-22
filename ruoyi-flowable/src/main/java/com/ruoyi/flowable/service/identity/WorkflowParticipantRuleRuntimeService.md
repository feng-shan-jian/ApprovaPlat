# WorkflowParticipantRuleRuntimeService

## 组件简介与作用

`WorkflowParticipantRuleRuntimeService` 负责解释不可变参与者部署快照，并在实时组织目录边界上完成流程发起范围和单实例任务参与者解析。它不缓存组织关系，也不读取 Flowable 内部表。

发起范围同时支持两代部署：存在业务资源子部署的是新版受管定义，必须由快照作出正式决定；没有业务资源子部署的是历史未托管定义，由查询或发起入口继续执行 Flowable starter identity link 兼容逻辑。

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

- `resolveManagedStartDecisions(actor, definitions)`：整批读取部署快照并返回受管定义的正式允许或拒绝决定。Map 缺少定义 ID 才表示历史未托管；存在且为 `false` 时禁止历史兜底。同一批多个 `DEPTS` 规则只读取一次当前用户有效部门范围。
- `canStartIfManaged(actor, definition)`：单定义只读判定，委托同一套批量规则选择和匹配逻辑；历史未托管返回 `null`。
- `assertCanStart(actor, definition)`：草稿正式提交写入前复核。允许时返回命中的规则供成功审计，拒绝时记录固定失败指标和脱敏日志后抛出 403；历史未托管返回 `null`。该方法本身不会重新读取或校验 Flowable starter identity link，当前安全边界来自草稿提交在调用它之前通过开始表单装载完成历史门禁，其他调用方不得把它单独视为完整的历史授权检查。
- `resolveCreatedTask(task)`：Flowable 任务创建时按任务规则解析办理人或候选身份，保持原有实时资格、失败审计和事务回滚语义。

## 关键设计

- `PUBLIC`、`USERS`、`ROLES`、`DEPTS` 保持既有匹配语义；角色使用当前身份候选组，部门使用实时有效直属部门及祖先范围。
- 批量授权先按父部署一次取得规则映射，再按定义 ID 生成决定；没有业务资源子部署与受管快照缺规则是两个不同状态。
- 受管快照缺规则、版本不支持、清单或 JSON 资源损坏都直接失败，不转换为拒绝、历史公开或空结果。
- `assertCanStart` 保留规则对象和拒绝审计，不由列表的 boolean 结果替代；真实发起仍在写事务内重新核验。

## 最小接入示例

```java
Map<String, Boolean> managed = participantRuleRuntimeService
        .resolveManagedStartDecisions(actor, scannedDefinitions);
for (ProcessDefinition definition : scannedDefinitions)
{
    if (managed.containsKey(definition.getId()))
    {
        boolean visible = managed.get(definition.getId());
        // false 是正式拒绝；只有 containsKey=false 才读取历史 starter identity link。
    }
}
```
