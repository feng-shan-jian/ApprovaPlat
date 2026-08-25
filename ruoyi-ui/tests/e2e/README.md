# 工作流浏览器回归

设计器用例连接真实前端、后端和数据库，创建隔离的未部署模型，执行 BPMN 文件导入、属性编辑、命令栈撤销、服务端校验、保存与页面重载，并在 `finally` 中按真实 `modelId` 精确删除测试模型。

PowerShell 7 本地运行示例：

```powershell
$env:WORKFLOW_E2E_BASE_URL = 'http://127.0.0.1:1024'
$env:WORKFLOW_E2E_USERNAME = '<具备 workflow:model 权限的账号>'
$env:WORKFLOW_E2E_PASSWORD = '<账号密码>'
$env:PLAYWRIGHT_CHANNEL = 'msedge'
npm run test:e2e:designer
```

CI 浏览器固定为 Playwright Chromium，`PLAYWRIGHT_CHANNEL` 保持空值，并在执行前安装对应浏览器。测试账号至少需要模型查询、创建、设计、保存、校验和删除权限；测试环境至少需要一个启用的流程分类。
