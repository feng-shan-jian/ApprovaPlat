# 审批通知管理页面

## 页面作用

页面使用正式后端接口维护审批通知策略、读取授权流程与节点目录，并分页查询脱敏通知 outbox。邮件服务入口打开独立的 `MailConfigDialog`，页面本身不持有或保存 SMTP 授权码。

节点目录读取失败会保留为独立错误状态并展示后端安全提示，不会用空数组伪装成“流程没有用户任务”；错误状态存在时前端校验也会阻止保存节点策略。

## 权限

- `workflow:notification:manage`：查看、新增和编辑通知策略，同时读取当前管理员有权管理的流程与节点目录。
- `workflow:notification:audit`：查看投递运维页签并分页查询脱敏 outbox。
- `workflow:notification:retry`：对当前仍为 `DEAD_LETTER` 的记录发起补偿。
- `workflow:notification:mailManage`：查看、测试和保存 SMTP 邮件服务配置。

页面按权限选择第一个可访问页签；没有对应权限时不会发出策略、outbox 或 SMTP 请求。按钮隐藏只是用户体验约束，服务端权限仍是最终门禁。

## 通知策略

- `GET /workflow/notification/policies` 的 `data` 是策略数组，顶层 `mailChannelAvailable` 是策略编辑人可读取的非敏感邮件通道状态。
- 流程与节点只能来自 `/workflow/notification/catalog/processes` 及其节点目录，不提供手工输入 Key 或浏览器缓存回退。
- 新增与编辑继续复用 `PUT /workflow/notification/policies`；编辑携带读取时的 `expectedRevision`。
- `EMAIL + ENABLED` 在邮件通道不可用时由前端提示并阻止提交，后端仍必须重新校验。
- 只有 `NOTIFICATION_POLICY_REVISION_CONFLICT` 会提示重新加载；重复自然键与 `SMTP_NOT_CONFIGURED` 分别显示对应业务处理，不会把所有 HTTP 409 误报为并发修改。
- 列表搜索和分页只作用于本次正式查询结果，不写入 `localStorage` 或 `sessionStorage`。

流程目录响应字段：

| 字段 | 含义 |
| --- | --- |
| `processDefinitionKey` | 保存策略时使用的稳定流程定义 Key |
| `processName` | 业务显示名称 |
| `version` | 当前授权部署版本 |

节点目录响应字段：

| 字段 | 含义 |
| --- | --- |
| `taskDefinitionKey` | 保存节点策略时使用的用户任务 Key |
| `taskName` | 节点业务显示名称 |

## 投递运维

Outbox 使用若依标准 `rows/total` 分页响应，支持按关键字、通知场景、状态和通知方式查询。页面显示通知对象、通知方式、尝试次数、发生时间及服务端脱敏后的失败原因。

补偿按钮只在同时具备 `workflow:notification:retry`、当前行状态为 `DEAD_LETTER` 且服务端返回 `canCompensate` 时显示。页面使用行级 loading 防止重复点击；补偿成功仅提示“重新进入投递队列”，随后重新读取服务端状态。

## 邮件服务联动

- 只有 `workflow:notification:mailManage` 用户能看到“邮件服务”和策略弹窗内的“配置邮件服务”。
- 无 SMTP 管理权限的策略管理员在邮件不可用时只看到“联系管理员”提示，不会调用受限的 mail-config 接口。
- SMTP 保存成功并重新查询 revision 后，`MailConfigDialog` 通过 `saved` 事件把脱敏的 `configured` 状态同步给当前策略表单。
- 页面不会显示静态“邮件服务正常”状态，也不会根据测试成功提前认定正式配置已保存。
- 保存策略期间若 SMTP 被其他管理员移除，页面按 `SMTP_NOT_CONFIGURED` 立即更新邮件门禁；有权限者可进入配置，无权限者会收到“联系管理员”提示。

## 最小接入示例

页面由后端动态菜单 `workflow/notification/index` 加载，无需添加静态路由：

```text
菜单权限：workflow:notification:policyList
组件名称：WorkflowNotification
组件路径：workflow/notification/index
```
