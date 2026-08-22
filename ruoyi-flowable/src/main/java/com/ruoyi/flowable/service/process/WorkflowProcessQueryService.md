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
PageResult<WorkflowAssignedTaskView> page = processQueryService.listAssigned(
        new WorkflowAssignedTaskQueryDto(processKey, processName, category,
                taskName, createdAfter, createdBefore),
        pageNum,
        pageSize);
```

分页结果中的 `rows` 已复制为不可变集合，Controller 只负责转换为目标框架 `TableDataInfo`。
`listOwned` 与 `listManaged` 会在视图转换前一次性装载当前页事实：按实例 ID 批量读取
`RuntimeService` 挂起状态，按部署 ID 批量读取 `Deployment.category`，并使用一个
`processInstanceIdIn` 的 `TaskQuery` 批量读取当前任务。任务查询按 `taskCreateTime ASC、taskId ASC`
稳定排序，不使用会排除挂起任务的 `active()`，因此挂起前后的状态、任务名称和操作按钮保持一致。
批量查询使用“当前页实例数 × 200 + 1”的有界 `listPage`；当前页任务总量或任一实例任务数
超过安全上限时返回稳定数据异常，没有当前任务的实例返回空列表。

`listAssigned`、`listClaimable` 和 `listCompleted` 在取得当前页任务后统一批量装载
`TaskContext`：分别使用一次 `processDefinitionIds`、一次 `processInstanceIds` 和一次
`deploymentIds` 查询流程定义、历史实例与部署，再按任务主键映射给视图转换。视图转换阶段
不再访问这三类引擎查询。任务、定义、实例和部署的主键、唯一性及相互关系会在整页返回前
统一核验；定义缺失保持 `404`，历史实例、部署缺失或关系不一致保持 `500`，不会返回缺失
上下文的空行。流程分类仍只读取 `Deployment.category`。

发起人名称暂不纳入引擎关联对象批量化：当前页仅缓存既有逐用户查询结果，`nickName` 有值时
显示昵称；用户不存在、昵称为空或历史 `userId` 非数字时回显原始 `userId`，空
`startUserId` 返回 `null`。已办列表仍逐项调用 `WorkflowTaskLifecycleService.isProcessRevocable`，
不从历史字段或本页映射推导撤回能力。

## 查询方法

| 方法 | DTO | 返回视图 | 身份范围 |
| --- | --- | --- | --- |
| `listStartable` | `WorkflowStartableProcessQueryDto` | `WorkflowStartableDefinitionView` | 当前用户可真实发起 |
| `listStartableForExport` | `WorkflowStartableProcessQueryDto` | `List<WorkflowStartableDefinitionView>` | 当前用户可真实发起的有界导出全集 |
| `listOwned` | `WorkflowOwnedProcessQueryDto` | `WorkflowOwnedProcessView` | `startedBy=当前用户` |
| `listManaged` | `WorkflowManagedProcessQueryDto` | `WorkflowManagedProcessView` | 流程管理员跨用户实例 |
| `listAssigned` | `WorkflowAssignedTaskQueryDto` | `WorkflowAssignedTaskView` | `active + assignee=当前用户` |
| `listClaimable` | `WorkflowClaimableTaskQueryDto` | `WorkflowClaimableTaskView` | 当前用户具备完整五项认领权限，且任务为 `active + unassigned + 当前 user/ROLE/DEPT` |
| `listCompleted` | `WorkflowCompletedTaskQueryDto` | `WorkflowCompletedTaskView` | `finished + completedBy=当前用户`，逐行返回服务端 `revocable` |
| `listCompletedForExport` | `WorkflowCompletedTaskQueryDto` | `WorkflowCompletedTaskExportView` | 与已办列表相同分页和上下文，跳过 `revocable` 计算 |
| `listCopies` | `WorkflowCopyQueryDto` | `WorkflowCopyView` | `wf_copy.user_id=当前用户` |
| `getProcessForm` | `WorkflowProcessFormQueryDto` | `WorkflowProcessFormView` | 可发起或实例对象授权 |
| `getBpmnXml` | `WorkflowBpmnXmlQueryDto` | `String` | 可发起或实例对象授权 |

页码从 1 开始，单页上限为 200。页码、页大小、时间范围或查询文本不合法时返回稳定 400 业务异常。
`revocable` 调用 `WorkflowTaskLifecycleService` 与正式撤回命令共享的只读准备路径，覆盖真实完成人、实例状态、后继任务副作用、历史竞态、执行树和 BPMN 拓扑。该字段只是当前快照，正式提交仍重新校验并获取任务行锁。

待签菜单权限本身不代表任务可执行。`listClaimable` 会先按正式用户、角色和菜单数据实时复核当前用户同时具备 `claimList`、`claim`、`todoList`、`query`、`approval`；缺少任一项时返回真实空页 `rows=[]、total=0`，不会展示点击后必然被 `claim` 拒绝的任务。身份主数据查询异常仍按 `500` 失败，不伪造空页。

流程分类以 Flowable `Deployment.category` 中发布时冻结的业务分类编码为准，再由前端通过正式分类目录映射名称。可发起、我发起的、管理员实例、待办、待签和已办的分类筛选都先按该字段解析部署主键，再约束定义、实例或任务查询；分类下没有部署时直接返回空页，禁止空集合退化为全量结果。空页短路前仍会完整校验其他查询参数。实例列表当前页只执行一次 `deploymentIds` 批量查询，页面不回退 `ProcessDefinition.category` 或历史实例分类；部署记录缺失、分类为空或被错误写成 BPMN `targetNamespace` 绝对 URI 时返回空分类，避免旧历史数据造成整页失败或显示无法按相同口径筛选的伪分类。

完整实例详情不在本服务拼装。`/workflow/process/detail` 统一调用
`WorkflowProcessDetailService`，由该服务先完成实例和任务对象授权，再读取表单值、
历史活动、意见、BPMN 与 Viewer 状态，避免列表查询与敏感正文读取共享宽松边界。

## Controller 与导出边界

`WfProcessController` 保留七类列表、七类导出、`getProcessForm`、`bpmnXml` 和
`detail` 共 17 个只读接口。列表返回若依 `TableDataInfo`，并明确设置 `code=200`、
`msg=查询成功` 和真实 `total`。

可发起导出调用 `listStartableForExport` 一次：一次解析当前身份、一次完整授权扫描，
最多返回 10000 条，并与列表复用同一个内部扫描实现。其余六类导出继续每页固定读取
200 条，并在只读事务内校验后续页 `total` 与第一页一致。已办导出调用
`listCompletedForExport`，与列表复用历史任务分页、分类筛选和 `TaskContext` 装载，但直接生成
现有 `WorkflowCompletedTaskExportView`，不会触发任何撤回能力计算。超过上限返回 400；其余导出
分页期间总量漂移、缺行或超量返回 409，不生成看似成功但内容不完整的 Excel。

## 可发起权限

Flowable 8 的 `startableByUserOrGroups` 只返回存在匹配 starter identity link 的定义，会漏掉没有 starter 限制的公开定义。因此服务按以下固定步骤查询：

1. 使用 Flowable 原生查询限定最新、激活和业务筛选条件，并执行 `count`；
2. 基础定义超过 10000 条时拒绝查询，要求增加筛选条件；
3. 按流程 key、定义 ID 的确定顺序，以 200 条分块执行 `listPage` 并保存最多 10000 条有界定义；
4. 整批定义只调用一次部署快照授权：Map 中存在定义 ID 的 `true/false` 是新版正式决定，`false` 不进入历史兜底；
5. 只有 Map 中缺少的历史未托管定义才逐定义读取 starter identity link；无链接视为公开，用户或有效角色、部门候选组命中时可见；
6. 扫描完整有界结果后返回真实 `total` 和当前页，不截断伪造分页总数；
7. 当前页部署元数据只执行一次 `deploymentIds(...)` 查询，导出超过 200 个部署时按 200 分块，视图转换不再逐行查询部署。

## 表单快照

`getProcessForm` 必须同时提供 `definitionId` 和 `deploymentId`。首次发起时，定义还必须是当前用户可发起的最新激活版本；重新查看实例时，先调用 `WorkflowProcessAccessService` 完成对象授权，再核验实例、定义、部署三者关系。

开始节点从该定义的 BPMN 公共模型中确定，返回内容只读取 Flowable 业务制品 `approvaplat/forms-v1.json` 中的 `content`。服务不会查询或回连当前 `wf_form`，所以模板后续编辑不会改变旧部署和在途实例的表单快照。

正式写链使用包级 `loadStartFormInCurrentTransaction(...)`：调用方传入外层事务已经核验的 `WorkflowCurrentIdentity` 和真实 `ProcessDefinition`，该方法不再进入 `engineOperations.read(...)`，也不重新解析身份。一次取得的 `BpmnModel` 同时供开始节点定位、部署表单快照和多实例字段描述使用，并与表单视图组成简单数据载体返回发起服务。

授权分为两个明确边界：`getProcessForm(...)` 和 `getBpmnXml(...)` 使用纯只读 `canStartIfManaged(...)`，拒绝保持既有“当前用户无权发起该流程”的 `403` 且不携带 `subCode`，也不累计正式发起失败指标；正式写链只调用一次 `assertCanStart(...)`，受管拒绝保留 `PROCESS_START_SCOPE_DENIED` 和失败指标。两条路径都只有在结果为 `null`、明确表示历史未托管部署时，才检查 Flowable starter identity link；受管 `false` 或异常拒绝都不能进入历史兼容路径。

## BPMN XML

首次发起预览使用当前用户可发起规则的纯只读判定，不调用正式写入授权或产生发起失败指标。详情预览必须携带实例 ID，并通过实例对象授权及定义、部署关系核验。授权通过后统一调用 `WorkflowDeploymentService.getBpmnXml`，由其执行大小限制、UTF-8 解码、安全 XML 解析及 Flowable 校验。

## 异常与一致性

- `400`：分页、文本长度、时间范围或必填参数不合法；
- `403`：当前用户不能发起目标定义，或实例对象授权拒绝；
- `404`：流程定义或授权服务查询的对象不存在；
- `409`：客户端声明的定义、部署、实例关系不一致，或定义不是最新激活版本；
- `500`：Flowable 定义、实例、任务与部署快照的内部关联异常，任务上下文中的定义、实例、部署关系不一致，任务数量超过安全上限，或分页结果违反计数契约。实例列表允许旧历史记录缺少部署，此时仅返回空分类；待办、待签和已办任务缺少部署时整页失败。

七类列表均使用 `count + listPage` 或业务 Mapper 的等价计数/分页 SQL。任何关联异常都会停止整页返回，避免把不完整或未授权数据伪装为正常结果。
