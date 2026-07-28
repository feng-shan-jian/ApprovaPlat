# 五角色真实 HTTP 权限矩阵

## 1. 资产与边界

本测试资产冻结并交叉校验以下正式契约：

- `WfAttachmentController`、`WfCategoryController`、`WfDeployController`、
  `WfFormController`、`WfIdentityController`、`WfInstanceController`、
  `WfModelController`、`WfProcessController`、`WfTaskController` 共 9 个
  Controller。
- 方法级 mapping 分布为 `4+7+5+6+1+2+11+19+15=70`。
- `workflow_admin`、`workflow_designer`、`workflow_starter`、
  `workflow_approver`、`workflow_auditor` 共 5 个职责分离角色。
- 机器可读矩阵位于
  `back/ruoyi-admin/src/test/resources/workflow-rbac-matrix.csv`，共 70 行、
  350 个角色与入口单元。
- `WorkflowRbacMatrixContractTest` 反射比对 Controller、handler、HTTP 动词、
  完整路径、权限模式和权限集合，并从
  `back/sql/flowable/menu/8.0.0__workflow_menu.sql` 反向计算 350 个单元。
- `WorkflowRbacHttpIT` 使用 `RANDOM_PORT` 启动真实 Tomcat，通过真实 `/login`、
  `AuthenticationManager`、MySQL 密码散列、JWT 过滤器和 Redis 登录缓存发起请求；
  不使用 standalone `MockMvc`，也不直接构造 `SecurityContext`。

当前测试已为 166 个 URL 层允许单元准备正式模型、部署、表单、流程实例、任务、
附件和历史数据 fixture，并与 184 个 URL 权限拒绝单元一起通过真实 HTTP 执行。
每个允许单元核对实际响应和业务副作用，拒绝单元核对传输状态、业务码和零副作用；
fixture 清理后还会把数据库、Flowable 运行/历史表、附件和审计行数与执行前快照逐表对账。

## 2. 强制环境门禁

执行前必须先在隔离 MySQL 中创建五个不同、启用、非 `user_id=1` 的测试账号，
分别只绑定一个受管工作流角色，并完成正式菜单 SQL。账号或密码在生成或重置后、
首次使用前，必须按项目规则登记到忽略文件 `testcount/accounts.local.md`。测试代码
不会创建、修改、重置账号，也不会读取该文件。

隔离 schema 必须预先把 `sys.account.captchaEnabled` 配置为 `false`。测试只读取并
校验该值，不会修改 `sys_config`。Redis 必须使用专用且非 0 的 database；测试不会
清空 Redis database，只会通过真实 `/logout` 删除本次创建的五个 Token。

以下环境变量全部由执行进程注入，不得写入 Git、命令历史、测试报告或 Maven 日志：

| 环境变量 | 含义 |
|---|---|
| `FLOWABLE_RBAC_JDBC_URL` | 隔离 MySQL JDBC URL |
| `FLOWABLE_RBAC_DB_USERNAME` | 隔离 MySQL DML 账号 |
| `FLOWABLE_RBAC_DB_PASSWORD` | 隔离 MySQL DML 密码 |
| `FLOWABLE_RBAC_EXPECTED_SCHEMA` | JDBC 连接必须命中的 schema 名 |
| `FLOWABLE_RBAC_REDIS_HOST` | 隔离 Redis 地址 |
| `FLOWABLE_RBAC_REDIS_PORT` | 隔离 Redis 端口 |
| `FLOWABLE_RBAC_REDIS_PASSWORD` | Redis 密码；无密码时允许空值 |
| `FLOWABLE_RBAC_REDIS_DATABASE` | 专用且非 0 的 Redis database |
| `FLOWABLE_RBAC_TOKEN_SECRET` | 当前进程使用且不少于 64 UTF-8 字节的 Token 密钥 |
| `FLOWABLE_RBAC_ACCOUNTS_REGISTERED` | 五账号已在首次使用前登记时必须为 `true` |
| `FLOWABLE_RBAC_WORKFLOW_ADMIN_USERNAME` | 流程管理员预登记用户名 |
| `FLOWABLE_RBAC_WORKFLOW_ADMIN_PASSWORD` | 流程管理员预登记密码 |
| `FLOWABLE_RBAC_WORKFLOW_DESIGNER_USERNAME` | 流程设计者预登记用户名 |
| `FLOWABLE_RBAC_WORKFLOW_DESIGNER_PASSWORD` | 流程设计者预登记密码 |
| `FLOWABLE_RBAC_WORKFLOW_STARTER_USERNAME` | 流程发起人预登记用户名 |
| `FLOWABLE_RBAC_WORKFLOW_STARTER_PASSWORD` | 流程发起人预登记密码 |
| `FLOWABLE_RBAC_WORKFLOW_APPROVER_USERNAME` | 流程审批人预登记用户名 |
| `FLOWABLE_RBAC_WORKFLOW_APPROVER_PASSWORD` | 流程审批人预登记密码 |
| `FLOWABLE_RBAC_WORKFLOW_AUDITOR_USERNAME` | 流程审计查看者预登记用户名 |
| `FLOWABLE_RBAC_WORKFLOW_AUDITOR_PASSWORD` | 流程审计查看者预登记密码 |

任一强制变量缺失、账号不存在、账号停用或删除、五账号不唯一、使用超级管理员、
受管角色不唯一、Token 中 workflow 权限与正式 SQL 不一致时，测试显式失败，不会
`skip`。

## 3. 执行与证据

不需要外部服务或凭据的静态契约测试：

```powershell
cd D:\ruoyiflowable\back
mvn -pl ruoyi-admin -am "-Dtest=WorkflowRbacMatrixContractTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
```

五角色真实 HTTP 集成测试：

```powershell
cd D:\ruoyiflowable\back
mvn -pl ruoyi-admin -am -Pflowable-it `
  "-Dtest=WorkflowRbacMatrixContractTest,WfDeployControllerTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" `
  "-Dit.test=WorkflowRbacHttpIT" verify
```

真实 HTTP 测试成功启动后会生成：

`back/ruoyi-admin/target/workflow-rbac/workflow-rbac-http-report.json`

报告逐单元保存 `expectedAccess`、`executionStatus`、`transportStatus`、
`bodyCode` 和稳定原因，不保存用户名、密码、Token、响应正文或业务对象数据。
本项目权限异常当前采用传输层 HTTP 200 加 `AjaxResult.code=403`，拒绝单元必须
同时满足这两个条件。报告还保存执行前后全部 `ACT_*`、`wf_*` 和
`sys_oper_log` 的行数快照；任一行数变化都会使测试失败。

## 4. 当前验收结果与剩余边界

2026-07-28 的隔离环境报告实际执行 350 个单元：166 个 `ALLOW`、184 个 `DENY`，
`passed=350`、`failed=0`、`notExecuted=0`、`zeroSideEffects=true`。隔离 MySQL schema
为 `ry_vue_flowable_it`，Redis 使用专用 database `14`；报告生成时间为
`2026-07-28T06:53:21.927678100Z`。账号、密码、Token 和业务响应正文均未进入报告。

同一候选的 Chromium 回归另以 5 个角色用例覆盖 11 个菜单页、七工作台、菜单隐藏、直接 URL
拒绝和零业务 API 调用，并作为 20/20 候选报告的一部分通过。该结果关闭五角色与 70 个
Controller 入口的 URL 权限矩阵及当前浏览器页面权限主链，不单独替代同角色错误对象关系、
全部非法状态组合、扩展并发/幂等、故障注入、权限强化或 P3 第二次连续回归；这些门禁当前
保持 `not executed` 或 `in_progress`。
