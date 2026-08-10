# WorkflowProcessQueryService

## 作用

`WorkflowProcessQueryService` 是流程工作台的只读领域边界，承接以下能力：

- 当前用户可发起的最新激活流程定义；
- 当前用户发起的流程实例；
- 流程管理员按独立权限查询和运维的跨用户实例；
- 当前用户作为 `assignee` 的活动待办；
- 当前用户或其有效 `ROLE<id>`、`DEPT<id>` 候选组可认领的未分配任务；
- Flowable 记录为当前用户真实完成的历史任务，以及复用正式撤回规则计算的实时 `revocable` 能力；
- 正式 `wf_copy` 表中抄送给当前用户的记录；
- 经过定义、部署、实例关系核验的部署表单快照；
- 经过可发起或实例对象授权的安全 BPMN XML。

普通工作台查询不接收或信任客户端 `userId`。管理员 `listManaged` 只在独立
`workflow:process:manageList` 接口后使用经过正整数校验的 `startUserId` 作为筛选条件，
不会把该值当作当前身份。服务不使用 PageHelper，也不返回 Flowable 可变运行时对象或
`wf_*` 可变实体。

## 接入方式

Controller 注入该服务，并把查询参数转换为对应动作 DTO：

```java
WorkflowPageResult<WorkflowAssignedTaskView> page = processQueryService.listAssigned(
        new WorkflowAssignedTaskQueryDto(processKey, processName, category,
                taskName, createdAfter, createdBefore),
        pageNum,
        pageSize);
```

分页结果中的 `rows` 已复制为不可变集合，Controller 只负责转换为目标框架 `TableDataInfo`。
`listOwned` 与 `listManaged` 会按当前页实例 ID 批量读取 `RuntimeService`：运行中实例以实时
`isSuspended()` 生成 `running/suspended`，已结束实例才使用历史状态。当前环节查询不使用
会排除挂起任务的 `TaskQuery.active()`，因此挂起前后的状态、任务名称和操作按钮保持一致。

旧前端的日期范围使用 `params[beginTime]`、`params[endTime]` 和
`yyyy-MM-dd HH:mm:ss` 格式。`WfProcessController` 按项目固定 `GMT+8` 转成
`Instant` 后再调用本服务；新接口仍可直接使用各 DTO 的 ISO-8601
`started/created/completedAfter`、`Before` 字段。同一请求的新旧时间值不一致时返回
400，不会静默覆盖。抄送查询同时保留旧字段 `processId`，但客户端 `userId` 始终被忽略。

## 查询方法

| 方法 | DTO | 返回视图 | 身份范围 |
| --- | --- | --- | --- |
| `listStartable` | `WorkflowStartableProcessQueryDto` | `WorkflowStartableDefinitionView` | 当前用户可真实发起 |
| `listOwned` | `WorkflowOwnedProcessQueryDto` | `WorkflowOwnedProcessView` | `startedBy=当前用户` |
| `listManaged` | `WorkflowManagedProcessQueryDto` | `WorkflowManagedProcessView` | 流程管理员跨用户实例 |
| `listAssigned` | `WorkflowAssignedTaskQueryDto` | `WorkflowAssignedTaskView` | `active + assignee=当前用户` |
| `listClaimable` | `WorkflowClaimableTaskQueryDto` | `WorkflowClaimableTaskView` | 当前用户具备完整五项认领权限，且任务为 `active + unassigned + 当前 user/ROLE/DEPT` |
| `listCompleted` | `WorkflowCompletedTaskQueryDto` | `WorkflowCompletedTaskView` | `finished + completedBy=当前用户`，逐行返回服务端 `revocable` |
| `listCopies` | `WorkflowCopyQueryDto` | `WorkflowCopyView` | `wf_copy.user_id=当前用户` |
| `getProcessForm` | `WorkflowProcessFormQueryDto` | `WorkflowProcessFormView` | 可发起或实例对象授权 |
| `getBpmnXml` | `WorkflowBpmnXmlQueryDto` | `String` | 可发起或实例对象授权 |

页码从 1 开始，单页上限为 200。页码、页大小、时间范围或查询文本不合法时返回稳定 400 业务异常。
`revocable` 调用 `WorkflowTaskLifecycleService` 与正式撤回命令共享的只读准备路径，覆盖真实完成人、实例状态、后继任务副作用、历史竞态、执行树和 BPMN 拓扑。该字段只是当前快照，正式提交仍重新校验并获取任务行锁。

待签菜单权限本身不代表任务可执行。`listClaimable` 会先按正式用户、角色和菜单数据实时复核当前用户同时具备 `claimList`、`claim`、`todoList`、`query`、`approval`；缺少任一项时返回真实空页 `rows=[]、total=0`，不会展示点击后必然被 `claim` 拒绝的任务。身份主数据查询异常仍按 `500` 失败，不伪造空页。

完整实例详情不在本服务拼装。`/workflow/process/detail` 统一调用
`WorkflowProcessDetailService`，由该服务先完成实例和任务对象授权，再读取表单值、
历史活动、意见、BPMN 与 Viewer 状态，避免列表查询与敏感正文读取共享宽松边界。

## Controller 与导出边界

`WfProcessController` 保留七类列表、七类导出、`getProcessForm`、`bpmnXml` 和
`detail` 共 17 个只读接口。列表返回若依 `TableDataInfo`，并明确设置 `code=200`、
`msg=查询成功` 和真实 `total`。

七类导出复用相同领域查询和身份范围，每页固定读取 200 条，最多导出 10000 条，
并在只读事务内校验后续页 `total` 与第一页一致。超过上限返回 400；分页期间总量漂移、
缺行或超量返回 409，不生成看似成功但内容不完整的 Excel。

## 可发起权限

Flowable 8 的 `startableByUserOrGroups` 只返回存在匹配 starter identity link 的定义，会漏掉没有 starter 限制的公开定义。因此服务按以下固定步骤查询：

1. 使用 Flowable 原生查询限定最新、激活和业务筛选条件，并执行 `count`；
2. 基础定义超过 10000 条时拒绝查询，要求增加筛选条件；
3. 按流程 key、定义 ID 的确定顺序分块执行 `listPage`；
4. 没有 starter identity link 的定义视为公开；
5. 存在 starter identity link 时，仅当前用户或其有效角色、部门候选组命中才可见；
6. 扫描完整有界结果后返回真实 `total` 和当前页，不截断伪造分页总数。

## 表单快照

`getProcessForm` 必须同时提供 `definitionId` 和 `deploymentId`。首次发起时，定义还必须是当前用户可发起的最新激活版本；重新查看实例时，先调用 `WorkflowProcessAccessService` 完成对象授权，再核验实例、定义、部署三者关系。

开始节点从该定义的 BPMN 公共模型中确定，返回内容只读取 `wf_deploy_form.content`。服务不会查询或回连当前 `wf_form`，所以模板后续编辑不会改变旧部署和在途实例的表单快照。

## BPMN XML

首次发起预览使用当前用户可发起规则。详情预览必须携带实例 ID，并通过实例对象授权及定义、部署关系核验。授权通过后统一调用 `WorkflowDeploymentService.getBpmnXml`，由其执行大小限制、UTF-8 解码、安全 XML 解析及 Flowable 校验。

## 异常与一致性

- `400`：分页、文本长度、时间范围或必填参数不合法；
- `403`：当前用户不能发起目标定义，或实例对象授权拒绝；
- `404`：流程定义或授权服务查询的对象不存在；
- `409`：客户端声明的定义、部署、实例关系不一致，或定义不是最新激活版本；
- `500`：Flowable 定义、实例、任务与部署快照的内部关联缺失或分页结果违反计数契约。

七类列表均使用 `count + listPage` 或业务 Mapper 的等价计数/分页 SQL。任何关联异常都会停止整页返回，避免把不完整或未授权数据伪装为正常结果。
