# WorkflowProcessInstanceQueryService

## 组件简介与职责

`WorkflowProcessInstanceQueryService` 负责“我发起的流程”、管理员实例列表、抄送列表和抄送首次阅读状态。它统一装载历史实例、实时挂起状态、当前任务、正式部署分类和历史发起人名称，但不查询可发起定义或用户任务工作台。

普通实例和抄送查询始终由服务端固定当前用户。管理员列表只把经过校验的 `startUserId` 当作筛选条件，不把客户端值当作当前身份。

## 入口说明

| 方法 | 调用入口 | 返回与约束 |
| --- | --- | --- |
| `listOwned(filter, pageNum, pageSize)` | `GET /workflow/process/ownList` 及 `POST /workflow/process/ownExport` | 仅当前用户发起的历史与运行实例 |
| `listManaged(filter, pageNum, pageSize)` | `GET /workflow/process/manageList` 及 `POST /workflow/process/manageExport` | 管理员跨用户实例分页；调用前必须经过管理权限入口 |
| `listCopies(filter, pageNum, pageSize)` | `GET /workflow/process/copyList` 及 `POST /workflow/process/copyExport` | 仅正式业务表中接收人为当前用户的抄送记录 |
| `markCopyRead(copyId)` | `PUT /workflow/process/copy/{copyId}/read` | 以 `copy_id + user_id + 未读状态` 原子记录首次阅读，并返回数据库最终状态 |

所有分页页码从 1 开始，单页最多 200 条。Controller 保持既有权限码、DTO、`TableDataInfo` 和导出协议不变。

## 依赖说明

| 依赖 | 用途 |
| --- | --- |
| `WorkflowEngineOperations` | 统一只读查询、首次阅读写事务和异常翻译 |
| `RepositoryService` | 批量读取部署并以 `Deployment.category` 解析正式分类 |
| `HistoryService` | 查询当前用户或管理员范围内的历史流程实例 |
| `RuntimeService` | 批量读取仍在运行实例的实时挂起状态 |
| `TaskService` | 按实例集合一次读取当前任务名称 |
| `WorkflowIdentityResolver` | 解析当前有效用户，禁止信任客户端用户主键 |
| `WfCopyMapper` | 查询当前接收人的抄送记录并原子写入首次阅读状态 |
| `ISysUserService` | 将历史发起人主键解析为当前显示名称 |

## 关键设计

- 当前页实例使用一次有界 `processInstanceIdIn` 任务查询、一次部署查询和一次运行实例查询，避免逐实例 N+1。
- 当前任务查询不添加 `active()`，所以挂起实例仍显示真实当前环节；任务按创建时间和任务主键稳定排序。
- 单个实例最多接受 200 个当前任务，实例、部署或运行状态批量结果异常时整页失败，不返回部分结果。
- 抄送 Mapper 返回后再次核验 `user_id`；不存在与越权统一返回 404，避免记录探测。
- 首次阅读时间由数据库条件更新保护，重复调用保留第一次写入的时间。

## 最小接入示例

```java
@Service
class MyProcessFacade
{
    private final WorkflowProcessInstanceQueryService queryService;

    MyProcessFacade(WorkflowProcessInstanceQueryService queryService)
    {
        this.queryService = queryService;
    }

    PageResult<WorkflowOwnedProcessView> firstPage(WorkflowOwnedProcessQueryDto filter)
    {
        // filter 不含可信 userId；服务根据当前认证身份固定发起人范围。
        return queryService.listOwned(filter, 1, 20);
    }
}
```
