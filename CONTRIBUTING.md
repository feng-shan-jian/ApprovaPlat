# 贡献指南

欢迎通过 Issue 报告问题、讨论功能，并通过 Pull Request 提交改进。

## 开发环境

使用 Java 17、Maven 和 Node.js。前后端启动步骤见 [README](README.md)。前端在 `ruoyi-ui` 下执行 `npm ci`，使用仓库中的锁文件安装依赖。

Windows 示例使用 PowerShell 7。Windows 与 WSL 分别安装依赖，避免共用 `node_modules`。

当前职责边界见[架构说明](docs/architecture.md)，历史验证及适用范围见[验收记录](docs/verification-history.md)。已有 v1 部署资源按[迁移指南](docs/workflow-artifact-v2-migration.md)升级；空库 SQL 清单不能替代旧数据迁移。

## 报告问题

请提供使用版本、运行环境、最小复现步骤、预期行为和实际结果。日志与截图应去除账号凭据、令牌及业务隐私数据。功能建议请说明使用场景、目标用户及预期行为。

## 提交变更

- 一次 Pull Request 聚焦一个问题，并说明行为变化、验证方法及适用范围。
- 保持接口、权限、事务和数据协议稳定；协议变化同步更新调用方、迁移 SQL、测试和文档。
- 修复业务缺陷时提供能覆盖原始失败场景的回归测试。
- 仅提交本次变更涉及的文件。源码、配置、正式测试和可复用文档纳入版本控制；本地凭据和恢复备份保留在忽略目录。一次性脚本、日志、调试文件和测试输出在核对结果后删除，测试数据按精确标识回收。

## 验证

现有测试按运行条件分为：

- Flowable/H2 与 Spring 事务：模型编译部署、流程发起及详情、任务完成、退回重提和运行时事实复用，覆盖幂等、revision 冲突、权限与回滚。
- MySQL/Redis：身份快照与条件写、资源删除交错、协作投递与频道锁、认领/催办资格、附件租约、SQL 外库提交与同一 Job 重放。使用登记后的开发库与隔离 Redis，运行条件见下文；有活动业务的开发库不满足全量验收前置条件。
- 前端契约：请求防重、列表与弹窗响应提交权、字典在途请求及失效、自动抄送纯规则、详情观察和草稿恢复。
- 浏览器受控响应：真实 Vue 页面上的交错成功/失败、关闭与卸载、切对象和确认期间切方向。
- 浏览器真实业务：设计器目录→保存/预检/发布→附件草稿→发起/办理/历史；不同账号退回重提；动态会签与 revision 冲突、隐藏恢复、通知死信补偿；多 Token 权限/数据范围/账户状态和分类、表单作者规则。

测试通过仅证明实际执行的场景；H2 通过不代表 MySQL、Redis 或目标部署环境已经验证。修改其他功能时，按具体风险验证。

在仓库根目录运行：

```powershell
mvn clean verify
git diff --check
```

在 `ruoyi-ui` 目录运行：

```powershell
npm run lint
npm run test:contracts
npm run build:prod
```

### 可选 MySQL 验证

应用和验收共用唯一开发库 `127.0.0.1:13306/approvaplat`。直接 Maven 仅在未提供 MySQL URL 时跳过可选数据库场景；提供 URL 后必须通过同一登记、安全门与本轮显式授权，配置错误直接失败。空库初始化的唯一清单与顺序在 [sql/baseline-files.txt](sql/baseline-files.txt)，由 Docker 的既有初始化脚本消费。14 个 SQL 只能在已批准的空卷上初始化，不能向有数据的旧库重放。

身份快照测试还需隔离 Redis：`WORKFLOW_REDIS_TEST_HOST`（默认 127.0.0.1）、`WORKFLOW_REDIS_TEST_PORT`，需要密码时设置 `WORKFLOW_REDIS_TEST_PASSWORD`。Redis 与数据库必须由本轮独占或使用明确隔离的数据范围。直接 Maven 缺环境可跳过；正式 `MySQL` 模式会检查连接、结构与结果，任何跳过均判失败。

### 浏览器回归

真实浏览器回归先完成下文的开发库登记、`StartServices` 与 `Load -EnableDevelopmentTests`，由登记工具提供前端地址，再从当前进程传入具有系统与工作流管理权限的应用账号。各真实 suite 自建分类，不依赖已有业务数据或执行顺序。在仓库根目录执行：

```powershell
$env:WORKFLOW_E2E_USERNAME = '<测试账号>'
$env:WORKFLOW_E2E_PASSWORD = '<测试密码>'
$env:PLAYWRIGHT_CHANNEL = 'msedge'
./bin/verify-rules.ps1 -Mode BrowserReal
```

默认浏览器为已安装的 Playwright Chromium；Windows 可使用上述 Edge channel。申请草稿回归验证页面恢复、revision 冲突及两次 HTTP 提交只创建一个实例。测试精确删除自己的模型、部署和实例；草稿终态按正常保留策略处理，运行后核对合成数据回收情况，保留共用库及原有业务数据。

申请草稿回归缺少账号会失败；受控响应的浏览器用例不证明后端权限、持久化或真实引擎行为。`test:contracts` 和 `playwright test --list` 不代表浏览器已通过。提交说明记录实际执行结果和未执行项，运行产物及临时数据在完成后清理。

### 四种可复现入口

在项目根目录通过 PowerShell 7 执行：

```powershell
./bin/verify-rules.ps1 -Mode Quick
./bin/verify-rules.ps1 -Mode MySQL
./bin/verify-rules.ps1 -Mode BrowserControlled
./bin/verify-rules.ps1 -Mode BrowserReal
```

`Quick` 在 Maven 子进程中屏蔽 `WORKFLOW_MYSQL_TEST_*`、`WORKFLOW_REDIS_TEST_*`，稳定选择本地合同与 H2，并调用前端 lint/契约/构建及架构、diff 检查；调用者环境保持原样。`MySQL` 前检登记、服务器 UUID、完整表清单及活动队列/流程，再执行 `mvn -B clean verify`；前检仅查询，CREATE/DML/DROP 权限由实际用例使用，缺权限、缺 Redis 或出现 skipped 即失败。

浏览器 spec 统一登记在 [bin/verification-suites.json](bin/verification-suites.json)，遗漏、重复或失效条目使所有模式失败。`BrowserControlled` 需要前端，组件 suite 自备真实 Vite 宿主；`BrowserReal` 需要下述工具登记的后端、前端、数据库与 Redis。浏览器模式拒绝零执行和 skipped；重试通过单列 `passed-with-retries`，不计首次稳定通过。报告保存 HEAD、dirty、源码差异 SHA256、套件与场景数量、skip 原因、产物哈希及实际服务 jar/前端源码标识。

### 登记共用开发库并运行验收

Windows PowerShell 7 使用 [bin/workflow-test-environment.ps1](bin/workflow-test-environment.ps1)。先由已授权管理员准备唯一业务库 `approvaplat`，通过正常登录确认 `SELECT DATABASE(), @@server_uuid`，再登记经过确认的 UUID。工具不创建数据库或账号、不初始化数据、不读取或保存密码文件；`Register` 只写本仓库的非敏感状态。状态有效期 8 小时，每次只有一个活动登记。

正常应用从当前进程读取 `RUOYI_DB_USERNAME/RUOYI_DB_PASSWORD`；验收从当前进程读取 `WORKFLOW_MYSQL_TEST_USERNAME/PASSWORD`，应指向同一已批准的开发账号。浏览器另需现有应用账号的 `WORKFLOW_E2E_USERNAME/PASSWORD`。不要将这些值提交到仓库。Compose 还要求显式选择已批准的 `APPROVAPLAT_MYSQL_VOLUME` 并提供管理员环境变量；本代码变更不代表已获得创建卷或保存凭据的授权。

```powershell
./bin/workflow-test-environment.ps1 -Action Register -ServerUuid '<正常登录核实的 MySQL UUID>'
$state = '<本轮输出的 state.json 绝对路径>'
try {
    ./bin/workflow-test-environment.ps1 -Action Load -StateFile $state -EnableDevelopmentTests
    ./bin/workflow-test-environment.ps1 -Action Validate -StateFile $state
    # 先离线/本地构建 jar；依赖应已显式安装。
    mvn -B '-DskipTests' package
    ./bin/workflow-test-environment.ps1 -Action StartServices -StateFile $state
    ./bin/workflow-test-environment.ps1 -Action Load -StateFile $state -EnableDevelopmentTests
    # MySQL 验收应在应用后台工作器停止时执行；见下方前置条件。
    ./bin/verify-rules.ps1 -Mode BrowserReal
} finally {
    ./bin/workflow-test-environment.ps1 -Action Cleanup -StateFile $state
}
```

#### 将已有认证加载到本次验收进程

在已通过正常开发启动流程设置 `RUOYI_DB_USERNAME/RUOYI_DB_PASSWORD` 的 PowerShell 7 进程中，使用：

```powershell
./bin/workflow-test-environment.ps1 -Action Load -StateFile $state -EnableDevelopmentTests -UseApplicationEnvironment
```

如果使用标准 PowerShell 凭据提示或已批准的凭据提供程序，可以直接传入 `PSCredential`；下面的提示由本机终端接收，不要把密码写成命令字符串或发送到聊天：

```powershell
$databaseCredential = Get-Credential -UserName 'ap' -Message '现有开发库应用账号：127.0.0.1:13306/approvaplat'
$browserCredential = Get-Credential -Message '现有 ApprovaPlat 网页登录账号（BrowserReal 使用）'
./bin/workflow-test-environment.ps1 -Action Load -StateFile $state -EnableDevelopmentTests `
    -DatabaseCredential $databaseCredential -BrowserCredential $browserCredential
Remove-Variable databaseCredential, browserCredential
```

两种数据库来源只能选一种。加载时先核对登记和显式授权，再通过一次 `SELECT DATABASE(), @@server_uuid` 验证已有账号与目标；成功后只向当前进程设置 `WORKFLOW_MYSQL_TEST_*`，后续 Maven、服务和浏览器子进程正常继承。`-BrowserCredential` 仅加载网页登录信息，实际登录由 BrowserReal 检验。缺值、错误目标或认证失败会恢复加载前的进程环境，不保留半加载状态。普通不带凭据参数的 `Load` 保持原来的只加载目标行为。

该入口不查找密码文件、不导出容器口令、不创建或修改账号、不写 User/Machine 环境或登记文件；关闭该 PowerShell 会结束这份进程环境。全局队列、保留表和活动流程预检继续生效，成功加载凭据不等于数据已满足全量验收条件。在同一 PowerShell 中依照上一节顺序运行 MySQL 和 BrowserReal，不能指望另一个已启动的终端或 Codex 进程自动取得这些环境变量。

无真实凭据的加载回归：`pwsh -NoProfile -File bin/verification-credentials.test.ps1`，已纳入 Quick；只使用合成凭据和客户端 mock。

`Load -EnableDevelopmentTests` 仅在当前进程设置 `WORKFLOW_DEVELOPMENT_TEST_OPT_IN=approvaplat:<本轮 runId>`；普通 `Load` 清除该授权。直接 IDE/Maven 执行也必须继承登记路径和本轮授权。Java 测试在原安全门位置核对登记路径、库名、地址、UUID 和有效期，再执行 fixture；BrowserReal 的自动 worker fixture 在任何 `beforeAll` 写入前检查数据库身份以及登记的前端、后端、Redis 进程和产物哈希。必须单 worker 运行，三个真实套件都使用这个入口。`BrowserControlled` 无数据库要求。

共用开发数据不是可随意销毁的测试数据。验收前停止日常应用、其他测试和后台消费者，禁止并行使用此库；确认全局通知/协作队列、上传恢复账本 `wf_attachment_upload` 和运行中流程为空。保留清理测试及启用定时器的 StartServices 还要求其 12 张保留清理目标表以及上传恢复账本为空，不自动清理旧数据；服务启动也检查已有 Flowable 作业。现有测试包含全局扫描和临时系统配置修改，不能声称全部写入都按 runId 隔离。BrowserReal 保存并恢复验证码配置，但进程被强制终止时仍需检查恢复情况。附件 MySQL 测试的直接 Maven 入口也在首次写入前检查上传恢复账本；清理批次只允许本用例成功登记的 storageKey，发现其他记录时在任何认领或文件删除前拒绝整个批次，保留既有记录。

MySQL 模式需要独立 Redis，使用当前进程的 `WORKFLOW_REDIS_TEST_HOST/PORT/PASSWORD` 指向已批准的 Redis；在后台应用停止时运行 `./bin/verify-rules.ps1 -Mode MySQL`。`StartServices` 会启动本轮独立 Redis 和已构建的应用服务，启用通知工作器供 BrowserReal 使用，因此不要同时运行 MySQL 全局队列/保留清理用例。两种验收应顺序执行。

`Cleanup` 核对本轮目录标记及精确 PID/启动时间/可执行文件，只停止本轮服务并关闭登记，保留业务库、账号、数据、运行目录和证据；不会 DROP DATABASE/DROP USER，也不会停止系统 MySQL。凭据不写状态或报告。归档日志前检查敏感内容；过期状态可 Cleanup 后重新登记。源码变化会阻断旧服务的浏览器验收，必须重新构建并启动。

停止服务失败时登记保持 `cleanup-failed`，保留原进程信息并阻止新一轮登记。排除失败原因后对同一 `StateFile` 重试 `Cleanup`；全部停止成功才变为 `closed`。

Java 数据库安全门统一位于 `ruoyi-common/src/test/java/com/ruoyi/common/testsupport`，通过仅含守卫类的 `target-guard` 测试制品供 system、flowable、admin 使用，避免 system 反向依赖 flowable。正式应用制品不包含该测试守卫。

不连接数据库的安全门回归：`pwsh -NoProfile -File bin/verification-target.test.ps1`，同时验证 Windows 进程路径延迟就绪及身份不匹配的拒绝行为；Java `WorkflowMysqlTargetGuardTest` 只使用 JDBC mock；前端 `real-database-target.test.js` 只检验合成登记。Quick 已包含这些测试。真实数据库与 BrowserReal 的通过结果必须另行记录，不得用这些单测代替。
