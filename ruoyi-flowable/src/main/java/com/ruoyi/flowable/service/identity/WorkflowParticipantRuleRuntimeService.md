# WorkflowParticipantRuleRuntimeService

## 组件简介与作用

`WorkflowParticipantRuleRuntimeService` 负责解释不可变参与者部署快照，并在实时组织目录边界上完成流程发起范围和单实例任务参与者解析。组织关系按请求实时读取，流程引擎信息通过 Flowable 公共 API 获取。

发起范围按部署资源分流：存在业务资源子部署的受管定义由快照作出正式决定；其余定义由查询或发起入口执行 Flowable starter identity link 授权。

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

- `resolveManagedStartDecisions(actor, definitions)`：整批读取部署快照并返回受管定义的正式决定。Map 中 `true` 表示授权命中，`false` 表示授权范围未命中，定义 ID 缺席表示使用 Flowable starter identity link。同一批多个 `DEPTS` 规则共用一次当前用户有效部门范围读取。
- `canStartIfManaged(actor, definition)`：单定义只读判定，委托同一套批量规则选择和匹配逻辑；使用 Flowable starter identity link 的定义返回 `null`。
- `assertCanStart(actor, definition)`：草稿正式提交写入前复核。授权命中时返回规则供成功审计；授权范围未命中时记录固定失败指标和脱敏日志并抛出 403；使用 Flowable starter identity link 的定义返回 `null`。草稿提交先通过开始表单装载完成 Flowable starter 门禁，再调用本方法完成受管快照门禁，两者共同构成完整授权检查。
- `resolveCreatedTask(task)`：Flowable 任务创建时按任务规则解析办理人或候选身份，保持原有实时资格、失败审计和事务回滚语义。

## 关键设计

- `PUBLIC`、`USERS`、`ROLES`、`DEPTS` 保持既有匹配语义；角色使用当前身份候选组，部门使用实时有效直属部门及祖先范围。
- 批量授权先按父部署一次取得规则映射，再按定义 ID 生成决定；业务资源子部署缺席表示使用 Flowable starter identity link，受管快照缺规则表示数据异常。
- 受管快照缺规则、版本超出白名单、清单或 JSON 资源损坏时直接返回数据异常。
- `assertCanStart` 保留规则对象和授权失败审计，真实发起在写事务内重新核验；列表 boolean 承担展示过滤。

## 最小接入示例

```java
Map<String, Boolean> managed = participantRuleRuntimeService
        .resolveManagedStartDecisions(actor, scannedDefinitions);
for (ProcessDefinition definition : scannedDefinitions)
{
    if (managed.containsKey(definition.getId()))
    {
        boolean visible = managed.get(definition.getId());
        // containsKey=true 表示正式受管决定；缺席时读取 Flowable starter identity link。
    }
}
```
