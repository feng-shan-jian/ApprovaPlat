# WorkflowBpmnIdentityValidator

## 组件简介与作用

`WorkflowBpmnIdentityValidator` 在模型保存、校验和部署前批量核验作者模型中的正式参与者规则、指定会签/或签身份及自动抄送身份。它确认身份格式、主数据状态和实时办理资格，阻止会生成无人可办、无法认领或越权可读任务的定义进入 Flowable 正式部署表。

该组件由 `WorkflowModelService.deployModel(...)` 在安全 XML、BPMN 结构和表单引用校验之后调用，校验通过后才允许 `RepositoryService` 创建真实部署。

## 公开方法

| 方法 | 入参 | 返回值 |
| --- | --- | --- |
| `validate(WorkflowBpmnDocument)` | 已完成安全解析和 Flowable 规则校验的 BPMN 文档 | 无；全部用户任务身份通过后正常返回 |

## 正式身份类型与资格

| 平台规则来源 | 静态格式 | 必须资格 |
| --- | --- | --- |
| 普通任务办理人规则 | 无前导零的正数用户、角色或部门 ID | 直接办理用户具备 `approval`，候选用户或组具备完整 `claim` 资格 |
| 指定会签/或签身份 | 正式 `identityType + identityIds` 属性 | 展开后 1 至 100 名用户均具备 `approval` |
| 自动抄送身份 | 正式抄送规则中的用户、角色或部门 ID | 接收人具备抄送列表与详情读取权限 |

普通用户任务必须配置完整平台办理人规则。作者模型中的 `flowable:assignee`、`flowable:candidateUsers`、`flowable:candidateGroups` 和 `flowable:owner` 会在更早的作者门禁被拒绝；这些原生字段只允许由服务端编译到执行 BPMN。任一正式引用缺失、停用、删除或资格不足时，整次保存、校验或部署返回错误并保持零写入。

会签和或签以已通过 BPMN 结构门禁的受控成员来源满足身份配置要求，不需要普通办理规则或原生办理人字段。指定用户、角色和部门仍执行本组件的身份有效性、审批资格及展开人数校验；发起时选择和办理时选择由各自的运行时入口复核成员。

## 表达式与运行时校验

服务端把通过校验的平台参与者规则编译为执行 BPMN，并冻结同一部署规则快照。任务 `create` 监听只从快照和实时组织目录解析最终办理人或候选身份；快照缺失、资格漂移或解析为空都会回滚任务创建事务。

## 编码和异常语义

- 身份 ID 使用 `long` 正整数规范字符串。执行态候选组前缀区分大小写，并采用 `ROLE<id>` 或 `DEPT<id>` 的唯一格式。
- 用户任务名称或节点 ID 会进入稳定业务错误，便于设计者定位。
- 文档为空、身份格式非法、主数据无效或资格不足时，在创建部署、流程定义和任务之前返回 `409`。数据库/映射层技术异常保留为 `500`。

## 关键设计

校验器先分类收集所有流程和子流程中的正式规则身份，再执行有界批量查询。存在性与资格分开校验，使错误能区分“主数据失效”和“无法走通真实业务入口”。角色和部门按每个组独立核验，每个候选组都必须具备至少一名可认领用户。

## 最小接入示例

```java
WorkflowBpmnDocument document = bpmnValidationService.validate(bpmnXml);
bpmnIdentityValidator.validate(document);

// 只有上述校验通过后才允许调用 Flowable 部署公共 API。
repositoryService.createDeployment()
        .addString(resourceName, document.bpmnXml())
        .deploy();
```
