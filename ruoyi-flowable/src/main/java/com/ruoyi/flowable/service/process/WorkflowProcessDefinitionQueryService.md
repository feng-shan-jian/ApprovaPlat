# WorkflowProcessDefinitionQueryService

## 组件简介与职责

`WorkflowProcessDefinitionQueryService` 负责可发起流程定义、部署表单快照和安全 BPMN 预览查询，并为草稿校验与正式发起提供同一套发起授权和部署制品读取边界。它不查询待办、历史任务、流程实例列表或抄送记录。

流程分类只使用发布时冻结的 `Deployment.category`。受管发起范围以部署快照判定为准；明确拒绝时不会回退到历史 starter identity link，只有快照决定缺席时才使用历史授权。

## 入口说明

| 方法 | 调用入口 | 返回与约束 |
| --- | --- | --- |
| `listStartable(filter, pageNum, pageSize)` | `GET /workflow/process/list` | 当前身份可发起的最新激活定义分页；页码从 1 开始，单页最多 200 条 |
| `listStartableForExport(filter)` | `POST /workflow/process/startExport` | 与列表相同身份和顺序的有界全集，基础定义最多扫描 10000 条 |
| `getProcessForm(request)` | `GET /workflow/process/getProcessForm`、`WorkflowProcessDraftService` | 只读取部署制品中的不可变开始表单快照，并核验定义、部署及可选实例关系 |
| `getBpmnXml(request)` | `GET /workflow/process/bpmnXml/{processDefId}` | 发起场景校验可发起权限；实例场景校验对象读取权限；输出经安全校验的 UTF-8 XML |
| `loadStartFormInCurrentTransaction(actor, definition)` | 同包 `WorkflowProcessStartService` | 在既有写事务中一次装载 BPMN 与开始表单，保持正式发起的授权和快照一致性 |

HTTP 权限仍由 `WfProcessController` 的 `@PreAuthorize` 控制；服务内部继续解析当前有效身份并执行对象级、发起范围和部署关系校验。

## 依赖说明

| 依赖 | 用途 |
| --- | --- |
| `WorkflowEngineOperations` | 统一只读事务和 Flowable 异常翻译；正式发起装载复用调用方既有写事务 |
| `RepositoryService` | 查询流程定义、部署元数据和 BPMN 模型 |
| `WorkflowIdentityResolver` | 解析当前有效用户、角色和部门候选组 |
| `WorkflowProcessAccessService` | 在实例预览场景执行对象级读取授权 |
| `WorkflowDeploymentService` | 读取并校验 BPMN XML 的大小、编码、安全性和 Flowable 合法性 |
| `WorkflowDeploymentArtifactRepository` | 读取不可变部署表单资源 `approvaplat/forms-v1.json` |
| `WorkflowParticipantRuleRuntimeService` | 批量解析冻结的受管发起范围决定 |

包内 `WorkflowProcessQuerySupport` 只提供分页、文本、时间、分类与稳定异常等无状态规则，不是该组件的替代入口。

## 关键设计

- 可发起列表先查询最新激活定义，再按固定 200 条分块装载并批量执行发起范围判定，避免公开定义被 Flowable 历史授权查询漏掉。
- `getProcessForm` 不回连当前模板；部署表单、字段权限和 BPMN 必须来自同一正式部署。
- 正式发起通过 `loadStartFormInCurrentTransaction` 复用同一次 BPMN 读取结果，调用方不得自行拼接表单或多实例配置。
- 定义、部署、实例关系缺失或漂移时失败关闭，不返回不完整快照。

## 最小接入示例

```java
@Service
class StartableDefinitionFacade
{
    private final WorkflowProcessDefinitionQueryService queryService;

    StartableDefinitionFacade(WorkflowProcessDefinitionQueryService queryService)
    {
        this.queryService = queryService;
    }

    PageResult<WorkflowStartableDefinitionView> firstPage(
            WorkflowStartableProcessQueryDto filter)
    {
        // 调用方必须先经过对应 HTTP 或应用层权限入口，身份始终由服务端解析。
        return queryService.listStartable(filter, 1, 20);
    }
}
```
