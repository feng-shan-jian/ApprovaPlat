# WorkflowDeploymentArtifactRepository

## 组件作用

`WorkflowDeploymentArtifactRepository` 将表单、条件、受控循环、参与者、扩展、DMN、调用活动和 SLA 的不可变部署快照保存为 Flowable 子部署资源。业务代码统一通过 `RepositoryService` 使用官方存储，Flowable 内部表由引擎独占管理。

## 使用方式

发布流程时先完成全部业务校验和 DMN 冻结，再构造 `WorkflowDeploymentArtifacts` 并调用 `persist`。运行时按父流程 `deploymentId` 调用对应的 `select*` 方法读取不可变资源。

## 公开方法

- `persist(deploymentId, artifacts)`：在当前发布事务内创建唯一业务资源子部署。
- `selectForms`、`selectConditionRules`、`selectControlledLoops`、`selectParticipantRules`：读取单个父部署的运行时规则。
- `selectStartParticipantRulesByDeploymentIds(deploymentIds)`：按最多 200 个父部署分块读取发起规则；外层按父部署 ID、内层按流程 key 返回。
- `selectExtensionSnapshots`、`selectDmnSnapshots`、`selectCallActivitySnapshots`、`selectTaskSlaSnapshots`：读取扩展和依赖资源。
- `hasFormReference`、`countExtensionVersionReferences`、`countDmnSourceReferences`：执行低频删除保护。
- `delete(deploymentId)`：删除父部署拥有的业务资源子部署。

## 关键设计

- 子部署通过 `parentDeploymentId` 与可执行流程部署建立生命周期关系。
- 发起范围批量读取使用 Flowable 8 官方 `parentDeploymentIds(...)`；`ACT_*` 表由 Flowable 独占管理。同一父部署存在多个业务资源子部署时按数据错误失败。
- 批量结果外层缺少父部署表示该定义沿用 Flowable 原生授权；外层存在但内层缺少流程规则表示受管快照损坏，并返回数据异常。
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

Map<String, Map<String, WfDeployParticipantRule>> startRules =
        artifactRepository.selectStartParticipantRulesByDeploymentIds(deploymentIds);
```
