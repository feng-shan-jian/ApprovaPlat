# WorkflowBpmnIdentityValidator

## 组件简介与作用

`WorkflowBpmnIdentityValidator` 在模型部署前批量校验 BPMN 用户任务的静态办理身份。它同时确认身份格式、主数据状态和实时办理资格，阻止会生成无人可办或无法认领任务的流程定义进入 Flowable 正式部署表。

该组件由 `WorkflowModelService.deployModel(...)` 在安全 XML、BPMN 结构和表单引用校验之后调用，校验通过后才允许 `RepositoryService` 创建真实部署。

## 公开方法

| 方法 | 入参 | 返回值 |
| --- | --- | --- |
| `validate(WorkflowBpmnDocument)` | 已完成安全解析和 Flowable 规则校验的 BPMN 文档 | 无；全部用户任务身份通过后正常返回 |

## 分配类型与资格

| BPMN 字段 | 静态格式 | 必须资格 |
| --- | --- | --- |
| `assignee` | 无前导零的正数用户 ID | `approval` 三项权限 |
| `owner` | 无前导零的正数用户 ID | `approval` 三项权限 |
| `candidateUsers` | 每项为无前导零的正数用户 ID | 每名用户都具备完整 `claim` 五项权限 |
| `candidateGroups` | 每项为 `ROLE<id>` 或 `DEPT<id>` | 每个角色/部门都至少有一名完整 `claim` 资格成员 |

用户任务至少配置 `assignee`、`candidateUsers` 或 `candidateGroups` 之一，`owner` 作为委派归属信息与真实办理身份共同存在。任一静态引用缺失、停用、删除或资格不足时，整次部署返回验证错误并保持零写入。

## 表达式与运行时校验

包含 `${...}` 或 `#{...}` 的身份由 Flowable 在运行时解析，部署校验保留表达式原值。任务 `create` 监听由 `WorkflowUserTaskAuditService` 读取引擎已生成的 candidate identity links，对动态候选用户和每个候选组重新执行完整 `claim` 资格校验；校验失败会回滚任务创建事务。

## 编码和异常语义

- 静态 ID 使用 `long` 正整数规范字符串。候选组前缀区分大小写，并采用 `ROLE<id>` 或 `DEPT<id>` 的唯一格式。
- 用户任务名称或节点 ID 会进入稳定业务错误，便于设计者定位。
- 文档为空、身份格式非法、主数据无效或资格不足时，在创建部署、流程定义和任务之前返回 `409`。数据库/映射层技术异常保留为 `500`。

## 关键设计

校验器先分类收集所有流程和子流程中的用户任务身份，再执行有界批量查询。存在性与资格分开校验，使错误能区分“主数据失效”和“无法走通真实业务入口”。角色和部门按每个组独立核验，每个候选组都必须具备至少一名可认领用户。

## 最小接入示例

```java
WorkflowBpmnDocument document = bpmnValidationService.validate(bpmnXml);
bpmnIdentityValidator.validate(document);

// 只有上述校验通过后才允许调用 Flowable 部署公共 API。
repositoryService.createDeployment()
        .addString(resourceName, document.bpmnXml())
        .deploy();
```
