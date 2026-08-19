# WorkflowDeploymentService

## 作用

`WorkflowDeploymentService` 负责最新流程定义列表、发布历史、激活/挂起、BPMN 读取和受控部署删除。服务只使用 Flowable 8 公共 API，并通过 `WorkflowEngineOperations` 统一事务、当前用户身份和引擎异常翻译。

## 主要接口

| 方法 | 说明 |
| --- | --- |
| `listLatest(filter, pageNum, pageSize)` | 查询每个流程 key 的最新定义和部署信息 |
| `publishList(processKey, pageNum, pageSize)` | 查询某个流程 key 的全部发布版本 |
| `changeState(definitionId, state)` | 激活或挂起定义及其运行实例，相同状态返回 409 |
| `getBpmnXml(definitionId)` | 有界读取并重新安全校验已部署 BPMN |
| `deleteDeployments(deploymentIds)` | 仅删除没有活动申请草稿、运行实例和历史实例的部署 |

## 删除事务

部署删除按以下顺序执行：

1. 将部署主键去重并升序排列，逐个对 `ACT_RE_DEPLOYMENT.ID_` 执行 `SELECT ... FOR UPDATE`；缺失部署返回 404。
2. 在部署行锁保护下，对活动申请草稿、运行实例和历史实例分别执行 `LIMIT 1 FOR UPDATE` 当前读，任一引用存在即返回 409；草稿只作为删除保护，不级联删除。
3. 删除判断不采用普通 count；身份核验可能已经建立 `REPEATABLE_READ` 旧快照，只有锁定当前读能够看见等待部署锁期间提交的草稿、运行实例和快速结束历史。
4. 固定读取部署自己的表单、扩展、DMN、循环、参与者、条件、CallActivity 快照，统计 SLA 快照行数，并读取关联模型。
5. 写入前再次以当前读查询实例与活动草稿引用；部署行锁会阻塞所有正式草稿创建和流程发起入口。
6. 物理删除该部署自己的全部业务快照，受影响行数必须与预检一致。
7. 清空模型的最近部署关联。
8. 调用 `RepositoryService.deleteDeployment(id)`，永不使用 `cascade=true`。

上述步骤位于同一个 Spring 事务。草稿创建同样先锁 `ACT_RE_DEPLOYMENT` 再写 `wf_process_draft`，因此创建先提交时删除会看到 ACTIVE 草稿并拒绝，删除先提交时等待中的创建会发现部署锁行消失并拒绝。活动草稿始终保留；快照行数变化、并发产生实例、引擎删除失败都会使业务表、模型关联和引擎表一起回滚。

## 状态约束

- 请求状态只接受 `active` 和 `suspended`。
- 激活已经激活的定义、挂起已经挂起的定义均返回 409，不返回无业务效果的成功。
- 状态命令沿用旧业务语义，会同步激活或挂起该定义下的运行实例。

## 最小接入示例

```java
PageResult<WorkflowDeploymentView> page =
        workflowDeploymentService.listLatest(filter, 1, 20);
workflowDeploymentService.changeState("expense:3:1201", "suspended");
```
