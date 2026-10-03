# 贡献指南

欢迎通过 Issue 报告问题、讨论功能，并通过 Pull Request 提交改进。

## 开发环境

使用 Java 17、Maven 和 Node.js。前后端启动步骤见 [README](README.md)。前端在 `ruoyi-ui` 下执行 `npm ci`，使用仓库中的锁文件安装依赖。

Windows 示例使用 PowerShell 7。Windows 与 WSL 分别安装依赖，避免共用 `node_modules`。

## 报告问题

请提供使用版本、运行环境、最小复现步骤、预期行为和实际结果。日志与截图应去除账号凭据、令牌及业务隐私数据。功能建议请说明使用场景、目标用户及预期行为。

## 提交变更

- 一次 Pull Request 聚焦一个问题，并说明行为变化、验证方法及适用范围。
- 保持接口、权限、事务和数据协议稳定；协议变化同步更新调用方、迁移 SQL、测试和文档。
- 修复业务缺陷时提供能覆盖原始失败场景的回归测试。
- 仅提交本次变更涉及的文件。源码、配置、正式测试和可复用文档纳入版本控制；本地凭据、日志、构建产物和一次性验证材料保留在忽略目录。

## 验证

现有测试按运行条件分为：

- Flowable/H2 与 Spring 事务：模型编译部署、流程发起及详情、任务完成、退回重提和运行时事实复用，覆盖幂等、revision 冲突、权限与回滚。
- MySQL/Redis：身份快照与条件写、资源删除交错、协作投递与频道锁、认领/催办资格、附件租约、SQL 外库提交与同一 Job 重放，使用隔离库并精确清理合成数据。
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

提供 `WORKFLOW_MYSQL_TEST_URL`、`WORKFLOW_MYSQL_TEST_USERNAME` 和 `WORKFLOW_MYSQL_TEST_PASSWORD` 后，后端测试会执行 MySQL 场景；未提供 URL 时跳过。完整套件要求连接已初始化、名称以 `_it` 结尾的可销毁专库，账号拥有该库建表及精确清理权限。空库初始化的唯一清单与顺序在 [docker/mysql/init.sh](docker/mysql/init.sh)，执行全部正式基线，不能复用结构未知的旧库。

身份快照测试还需隔离 Redis：`WORKFLOW_REDIS_TEST_HOST`（默认 127.0.0.1）、`WORKFLOW_REDIS_TEST_PORT`，需要密码时设置 `WORKFLOW_REDIS_TEST_PASSWORD`。Redis 与数据库必须由本轮独占或使用明确隔离的数据范围。直接 Maven 缺环境可跳过；正式 `MySQL` 模式会检查连接、结构与结果，任何跳过均判失败。

### 浏览器回归

启动隔离的前后端，关闭该测试环境验证码，提供模型/部署/草稿/实例管理权限及至少一个启用分类。在 `ruoyi-ui` 下执行：

```powershell
$env:WORKFLOW_E2E_BASE_URL = 'http://127.0.0.1:1024'
$env:WORKFLOW_E2E_USERNAME = '<测试账号>'
$env:WORKFLOW_E2E_PASSWORD = '<测试密码>'
$env:PLAYWRIGHT_CHANNEL = 'msedge'
npm run test:e2e
```

默认浏览器为已安装的 Playwright Chromium；Windows 可使用上述 Edge channel。申请草稿回归验证页面恢复、revision 冲突及两次 HTTP 提交只创建一个实例。测试精确删除自己的模型、部署和实例；草稿终态按正常保留策略处理，运行环境必须可销毁。

申请草稿回归缺少账号会失败；受控响应的浏览器用例不证明后端权限、持久化或真实引擎行为。`test:contracts` 和 `playwright test --list` 不代表浏览器已通过。提交说明记录实际执行结果和未执行项，运行产物及临时数据在完成后清理。

### 四种可复现入口

在项目根目录通过 PowerShell 7 执行：

```powershell
./bin/verify-rules.ps1 -Mode Quick
./bin/verify-rules.ps1 -Mode MySQL
./bin/verify-rules.ps1 -Mode BrowserControlled
./bin/verify-rules.ps1 -Mode BrowserReal
```

`Quick` 调用后端测试、前端 lint/契约/构建和 diff 检查；按当前环境明确记录可选测试的跳过。`MySQL` 执行 `mvn -B clean verify`，缺数据库、Redis 或出现 skipped 即失败。`BrowserControlled` 要求先启动前端，运行页面复用、观察、附件和请求提交权；`BrowserReal` 要求同一隔离后端、库、Redis、合成账号均就绪，运行真实三链与权限/作者规则。浏览器模式均拒绝零执行和跳过。入口不负责安装依赖、初始化数据库或启动服务。

真实环境装配按以下顺序进行：

1. 初始化专用 `_it` 库，启动独立 Redis，配置上述测试变量。密码只从环境注入。
2. 为后端设置 `RUOYI_DB_URL/USERNAME/PASSWORD` 指向该专库、`spring.data.redis.host/port/password` 指向同一独立 Redis；`RUOYI_PROFILE` 和 `RUOYI_TOKEN_SECRET_FILE` 使用本轮独立目录。打包并按 README 启动后端，启用 `timers` profile；通知 worker 必须开启，快速失败链可设置 `APPROVAPLAT_NOTIFICATION_WORKER_DELAY=3000`、`APPROVAPLAT_NOTIFICATION_MAX_RETRY_DELAY=PT1S`。
3. 启动 Vite，设置 `WORKFLOW_E2E_BASE_URL`、`WORKFLOW_E2E_USERNAME/PASSWORD` 和 `WORKFLOW_E2E_REDIS_PORT`。数据库 URL 必须与后端实际连接一致。真实套件在专库临时关闭验证码并恢复原值；SMTP 配置应为空，测试仅连接回环 SMTP。合成管理员须有系统与工作流管理权限。
4. 运行所需模式，查看 `output/verification/<Mode>.json` 的 HEAD、模式、执行数量、跳过和失败；浏览器另有对应 Playwright JSON。报告只反映本次执行的用例，不把历史结果或 skipped 算作通过。
5. 确认 `afterAll`/`finally` 清理通过，按报告中的 ID 核对合成资源。停止本轮 PID、移除本轮专库/单库授权/独立 Redis/上传目录，删除本轮 `output/verification`、Playwright/Surefire 临时报告及运行日志。保留源码、正式测试与常规构建产物。
