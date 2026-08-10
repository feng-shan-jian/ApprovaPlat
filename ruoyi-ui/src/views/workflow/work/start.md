# WorkflowProcessStart

## 组件简介

`WorkflowProcessStart` 同时承载新申请和本人草稿继续编辑。新申请从流程定义读取不可变部署表单快照；继续编辑只读取草稿绑定的正式快照、字段值和乐观锁版本。保存、删除和提交均调用真实后端接口，不在浏览器持久化业务数据。

## 使用方式

新申请路由：

```text
/workflow/process-start/:definitionId?deploymentId=:deploymentId
```

继续编辑路由：

```text
/workflow/process-draft/:draftId
```

## Props

页面组件无 Props。流程定义、部署和草稿主键来自受控路由，所有关系和对象权限由后端复核。

## Emits

页面组件无 Emits。成功提交后关闭当前页并进入真实流程实例详情。

## 公开方法

页面组件不暴露公开方法。

## 关键设计

- 保存草稿允许缺少正式必填项，但会等待所有附件上传或删除请求完成。
- 首次保存调用 `POST /workflow/process/draft`；继续保存、删除和正式提交都携带服务端 `revisionNo` 作为 `expectedVersion`。
- `version` 表示流程定义版本，绝不作为 CAS 版本使用。
- CAS 冲突保留当前页面输入并锁定写操作，只有用户确认后才重新加载服务器版本。
- 正式提交重新执行表单必填校验，并把当前最终值交给后端提交事务；首次直接提交会先建立可恢复草稿。
- 发起时会签或或签成员使用独立的 `multiInstanceUserIds` 契约保存和回显，不混入普通表单变量；草稿可暂时少选，正式提交重新校验部署节点、人数和最新审批资格。
- 草稿附件 UUID 按不可变表单快照识别字段后，通过授权元数据接口水合回显；保存和提交时重新转换为 UUID。
- 定义停用或删除导致流程图不可读取时，草稿表单仍可回显和删除，不以流程图失败覆盖草稿稳定状态。
- 页面离开时提示未保存修改，不使用 `localStorage`、`sessionStorage` 或 Pinia 保存表单值。

## 最小接入示例

```js
router.push({
  name: 'WorkflowProcessDraftEdit',
  params: { draftId }
})
```
