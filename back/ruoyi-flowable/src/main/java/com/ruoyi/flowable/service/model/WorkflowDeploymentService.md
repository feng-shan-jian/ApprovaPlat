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
| `deleteDeployments(deploymentIds)` | 仅删除没有运行实例和历史实例的部署 |

## 删除事务

部署删除按以下顺序执行：

1. 批量操作先确认每个部署都存在。
2. 查询运行实例和历史实例，任一计数非零即返回 409。
3. 固定读取部署自己的 `wf_deploy_form` 快照和关联模型。
4. 写入前再次查询实例引用，缩小并发窗口。
5. 物理删除该部署自己的表单快照，受影响行数必须与预检一致。
6. 清空模型的最近部署关联。
7. 调用 `RepositoryService.deleteDeployment(id)`，永不使用 `cascade=true`。

上述步骤位于同一个 Spring 事务。快照行数变化、并发产生实例或引擎外键冲突都会使业务表和引擎表一起回滚。

## 状态约束

- 请求状态只接受 `active` 和 `suspended`。
- 激活已经激活的定义、挂起已经挂起的定义均返回 409，不返回无业务效果的成功。
- 状态命令沿用旧业务语义，会同步激活或挂起该定义下的运行实例。

## 最小接入示例

```java
WorkflowPageResult<WorkflowDeploymentView> page =
        workflowDeploymentService.listLatest(filter, 1, 20);
workflowDeploymentService.changeState("expense:3:1201", "suspended");
```
