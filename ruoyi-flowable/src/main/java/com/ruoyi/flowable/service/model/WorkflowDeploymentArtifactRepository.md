# WorkflowDeploymentArtifactRepository

## 组件作用

`WorkflowDeploymentArtifactRepository` 将表单、条件、受控循环、参与者、扩展、DMN、调用活动和 SLA 的不可变部署快照保存为 Flowable 子部署资源。业务代码只通过 `RepositoryService` 使用官方存储，不直接访问 Flowable 内部表。

## 使用方式

发布流程时先完成全部业务校验和 DMN 冻结，再构造 `WorkflowDeploymentArtifacts` 并调用 `persist`。运行时按父流程 `deploymentId` 调用对应的 `select*` 方法读取不可变资源。

## 公开方法

- `persist(deploymentId, artifacts)`：在当前发布事务内创建唯一业务资源子部署。
- `selectForms`、`selectConditionRules`、`selectControlledLoops`、`selectParticipantRules`：读取运行时规则。
- `selectExtensionSnapshots`、`selectDmnSnapshots`、`selectCallActivitySnapshots`、`selectTaskSlaSnapshots`：读取扩展和依赖资源。
- `hasFormReference`、`countExtensionVersionReferences`、`countDmnSourceReferences`：执行低频删除保护。
- `delete(deploymentId)`：删除父部署拥有的业务资源子部署。

## 关键设计

- 子部署通过 `parentDeploymentId` 与可执行流程部署建立生命周期关系。
- 每类资源使用独立、带版本号的 JSON 文件，未知版本会 fail-closed。
- 单资源和总资源均有字节上限，读取使用有界缓冲。
- 原数据库唯一键由持久化前的自然键校验替代。
- 参与者规则的审计标识由部署主键、规则自然键和规则摘要生成稳定正整数。

## 最小示例

```java
WorkflowDeploymentArtifacts artifacts = new WorkflowDeploymentArtifacts(
        forms, conditions, loops, participants, extensions, dmn, calls, sla);
artifactRepository.persist(processDeploymentId, artifacts);

List<WfDeployForm> frozenForms = artifactRepository.selectForms(processDeploymentId);
```
