# WorkflowProcessTaskQueryService

## 组件简介与职责

`WorkflowProcessTaskQueryService` 负责当前用户的活动待办、可认领任务、真实已办任务和已办导出。它基于 Flowable 任务事实装载定义、历史实例、部署分类及发起人显示名称。任务认领、审批与撤回由任务生命周期写服务承担，流程实例列表由 `WorkflowProcessInstanceQueryService` 承担。

待办和已办身份始终由服务端固定。待签列表同时要求当前用户具备认领、待办、查询和审批的实时业务权限，菜单权限与业务权限共同构成入口门禁。

## 入口说明

| 方法 | 调用入口 | 返回与约束 |
| --- | --- | --- |
| `listAssigned(filter, pageNum, pageSize)` | `GET /workflow/process/todoList` 及 `POST /workflow/process/todoExport` | 当前用户作为 assignee 的活动任务 |
| `listClaimable(filter, pageNum, pageSize)` | `GET /workflow/process/claimList` 及 `POST /workflow/process/claimExport` | 当前用户或其有效 ROLE/DEPT 候选组可认领且尚未分配的任务 |
| `listCompleted(filter, pageNum, pageSize)` | `GET /workflow/process/finishedList` | `completedBy` 为当前用户的历史任务，并计算正式撤回能力 |
| `listCompletedForExport(filter, pageNum, pageSize)` | `POST /workflow/process/finishedExport` | 与已办列表使用相同身份、筛选和分页口径，并直接生成 Excel 所需字段 |

所有分页页码从 1 开始，单页最多 200 条。HTTP 权限和导出上限由 `WfProcessController` 保持，Service 继续执行实时身份、候选组和关联数据校验。

## 依赖说明

| 依赖 | 用途 |
| --- | --- |
| `WorkflowEngineOperations` | 统一只读事务和 Flowable 异常翻译 |
| `RepositoryService` | 批量读取任务所属定义、部署及正式部署分类 |
| `HistoryService` | 查询真实已办任务和任务所属历史流程实例 |
| `TaskService` | 查询当前办理人任务和候选任务 |
| `WorkflowIdentityResolver` | 解析当前用户、ROLE/DEPT 候选组及实时认领资格 |
| `ISysUserService` | 解析历史发起人的当前显示名称 |
| `WorkflowTaskLifecycleService` | 复用正式撤回准备路径计算已办列表的 `revocable` 能力 |

## 关键设计

- 每个非空任务页分别以一次定义查询、一次历史实例查询和一次部署查询批量装载完整上下文，视图转换直接消费该批次快照。
- 定义缺失返回 404；历史实例、部署缺失或任务关系不一致返回 500；完整上下文校验通过后才生成任务视图。
- 分类筛选使用 `Deployment.category` 对应的部署主键，未知分类返回真实空页。
- `listCompleted` 逐项调用正式撤回校验，保留身份、实例状态、历史竞态和 BPMN 拓扑合同；导出入口只省略 `revocable` 计算。
- 用户记录缺失、昵称为空或历史主键格式异常时回显原始发起人主键，保持历史关联原值。

## 最小接入示例

```java
@Service
class MyTaskFacade
{
    private final WorkflowProcessTaskQueryService queryService;

    MyTaskFacade(WorkflowProcessTaskQueryService queryService)
    {
        this.queryService = queryService;
    }

    PageResult<WorkflowAssignedTaskView> firstPage(WorkflowAssignedTaskQueryDto filter)
    {
        // 当前办理人由服务端认证身份固定，filter 只承载业务筛选条件。
        return queryService.listAssigned(filter, 1, 20);
    }
}
```
