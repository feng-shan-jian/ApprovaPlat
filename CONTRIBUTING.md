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

测试仅保留六个入口：模型编译部署、流程发起、任务完成、退回重提、前端请求防重，以及申请草稿浏览器回归。后端使用真实 Flowable/H2 和 Spring 事务，保留幂等、revision 冲突、权限与回滚断言。

测试通过仅证明保留场景，不代表全部功能或真实 MySQL/Redis 环境已经验证。修改其他功能时，按具体风险验证。

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

缺少账号会失败；`test:contracts` 和 `playwright test --list` 不代表浏览器已通过。提交说明记录实际执行结果和未执行项，运行产物及临时数据在完成后清理。
