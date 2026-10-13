# 部署业务资源 v1 → v2 迁移

导入旧库后，`/workflow/deploy/list` 和 `/workflow/process/list` 可能返回业务码 500，提示“流程部署业务资源协议版本不受支持”。当前运行代码只接受清单 `schemaVersion=2`；历史库可能仍保存 v1 条件资源。不能只修改 manifest 的版本号，也不能把旧规则替换为设计器当前内容。

`bin/migrate-workflow-artifacts-v2.ps1` 是显式执行的离线数据迁移，不参与应用启动或运行请求。它校验完整资源集合、父子部署关系和 v1 条件摘要，删除条件条目的派生 `celConfigJson`，按当前规则协议重新计算摘要，把 `conditions-v1.json` 改为 `conditions-v2.json`，并将 manifest 升到 v2。原始 `ruleJson`、数组顺序、数值精度、部署和资源 ID 保持不变，其余七类资源逐字节保持不变。未知版本、缺失资源、损坏摘要和读取后发生的修改都会拒绝执行。已是 v2 的库重复执行不写入。

## 执行

先停止连接目标库的应用、异步 worker 和定时任务，保留数据库服务。准备 PowerShell 7、MySQL 8.0.16+ 客户端/服务端；密码通过当前进程环境提供，工具不读取或保存密码文件。`WORKFLOW_MYSQL_CLIENT` 可指定客户端路径。迁移直接维护 Flowable 内部资源表，因此必须在维护窗口离线运行，完成后重启应用以清除已有资源缓存。

```powershell
# 从本地已配置的凭据入口设置这两个进程环境变量，不把密码写进命令历史。
# WORKFLOW_MYSQL_TEST_USERNAME / WORKFLOW_MYSQL_TEST_PASSWORD

# 先从可信数据库连接核实 @@server_uuid，再传入；默认连接 127.0.0.1:13306/approvaplat。
./bin/migrate-workflow-artifacts-v2.ps1 -ServerUuid '<已核实的 UUID>'
./bin/migrate-workflow-artifacts-v2.ps1 -ServerUuid '<同一 UUID>' -Apply
```

默认只做预检查。`-Apply` 在写入前将完整原始业务资源保存到新的 `output/database-backups/artifacts-v2-*` 目录，生成 `resources-before.json`、`rollback.sql` 和 `receipt.json`；已有备份目录不会被覆盖。目标 UUID、数据库名、资源集合、原始字节摘要和 revision 在一个事务中再次校验并加锁，全部资源更新整批提交。SQL 任一错误使 mysql 批处理退出，连接断开后回滚未提交事务。不要通过 `--force` 或会忽略错误的 SQL 客户端运行生成的语句。

回滚同样在停服状态下通过 mysql 批处理执行备份中的 `rollback.sql`。它只接受迁移后的确切资源集合、字节与 revision，原样恢复旧名称和正文，revision 继续递增。若资源在迁移后被改动，须先人工核对，不能强行跳过校验。状态 `committed-verification-failed` 表示事务已经提交、事后核对未完成，应检查数据与备份，不能视为自动回滚。

## 回归验证

```powershell
# 无数据库写入的转换、摘要、精度、损坏数据与幂等测试；也已接入 Quick。
pwsh -NoProfile -File bin/migrate-workflow-artifacts-v2.test.ps1

# 可选 MySQL 集成检查：仅创建本次随机命名的临时测试库，finally 删除。
# 凭据须能创建、删除该测试库；需要独立核实同一服务端 UUID。
pwsh -NoProfile -File bin/migrate-workflow-artifacts-v2.test.ps1 -Integration -ServerUuid '<已核实的 UUID>'

mvn -B -pl ruoyi-flowable -am -Dtest=WorkflowArtifactMigrationContractTest,WorkflowDeploymentResourceContractTest -Dsurefire.failIfNoSpecifiedTests=false test
```

共享的 v1/v2 合成夹具由 PowerShell 检查实际转换结果，Java 合同测试用真实 Flowable 资源仓库和当前条件路由器校验输出、数值边界及 v1 拒绝行为。MySQL 检查覆盖事务中途错误的回滚、旧 revision 拒绝、正确执行、重复预检查与恢复备份。最后登录真实应用，复测部署管理、新建流程、条件分支读取和既有待办；列表成功不能代替完整审批生命周期验收。
