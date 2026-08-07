# 工作流测试与验收

## 验收层级

工作流验收按以下层级执行，后一级不能被前一级替代：

1. 编译和静态契约：确认源码、SQL、菜单、权限矩阵和配置没有结构漂移。
2. 真实数据库：在隔离 MySQL 中从空 schema 安装 86 表（若依 20、Quartz 11、Flowable 36、ApprovaPlat `wf_*` 19），执行约束、备份恢复和三组共 39 项只读验收。
3. 真实服务 API：启动 Spring Boot、MySQL、Redis 和附件目录，通过真实登录与 HTTP 调用验证状态和副作用。
4. 浏览器 E2E：通过真实页面执行发起、审批动作、工作台、导出、附件和权限可见性。
5. 并发与故障：验证重复提交、竞态、事务回滚、连接器失败、executor、清理锁和存储异常。
6. 非功能与发布：验证性能、容量、多节点、长稳、监控、告警、备份、彩排和 24/72 小时观察。

任何未执行层级必须明确标记为 `not executed`，不能用构建成功、静态搜索或门禁 fixture 宣称真实业务已通过。

## 数据库契约测试

数据库基线相关测试位于 `back/ruoyi-flowable/src/test/java/com/ruoyi/flowable/mapper`，覆盖：

- 正式业务 DDL、附件、模型保存幂等和设计器偏好
- 扩展、连接器、DMN 和运行事件结构
- 菜单数量、树结构、职责角色和只读验收 SQL

定向执行：

```powershell
cd back
mvn -pl ruoyi-flowable -am `
  "-Dtest=WorkflowBusinessDdlContractTest,WorkflowAttachmentContractTest,WorkflowModelSaveDdlContractTest,WorkflowDesignerPreferenceDdlContractTest,WorkflowExtensionDdlContractTest,WorkflowMenuSqlContractTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
```

## RBAC 矩阵

`WorkflowRbacMatrixContractTest` 冻结 16 个登录用户 Controller、103 个入口和五角色 515 个权限单元。机器调用的 3 个运行事件入口使用独立集成 Token 测试，不计入登录用户矩阵。

静态矩阵执行：

```powershell
cd back
mvn -pl ruoyi-admin -am `
  "-Dtest=WorkflowRbacMatrixContractTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
```

真实 HTTP 集成测试必须使用隔离 MySQL、专用 Redis database、五个不同的非超级管理员账号和真实 `/login`。允许请求必须核对业务结果，拒绝请求必须核对传输状态、业务码以及数据库、Flowable、附件和审计零副作用。

## 发布门禁测试

在 WSL2 Ubuntu 中执行：

```bash
cd /mnt/d/ruoyiflowable
bash deployment/scripts/tests/workflow-release-gate-test.sh
```

该测试验证发布包完整性、SQL 顺序、配置红线、敏感信息、证据清单、安装、彩排、生产、回滚和观察反例。它使用受控 fixture 验证门禁逻辑，不等同于真实生产环境执行。

## 真实闭环判定

核心功能只有在页面、API、数据库、Flowable 状态、权限、审计和附件结果一致时才能关闭。写操作必须同时验证成功持久化和失败零副作用；导出必须解析实际文件内容；附件必须验证物理文件、元数据、授权和清理结果。
