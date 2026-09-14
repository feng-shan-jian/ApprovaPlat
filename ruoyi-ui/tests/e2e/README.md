# 工作流浏览器回归

`npm run test:e2e:designer` 同时运行组件浏览器回归和登录集成回归。任何产品断言失败或登录测试条件缺失，命令都会非零退出。不能只运行 `test:contracts` 后宣称设计器通过。

`workflow-designer-regressions.spec.js` 在真实 Edge/Chromium 中加载正式 `ProcessDesigner.vue`、Element Plus 和 bpmn-js，使用真实选择、输入、失焦、命令栈和导出 XML。外部目录由固定 HTTP 数据提供，模板含有效和缺少权限元数据的历史样本。覆盖错误与升级编码、撤销重做、相邻边界定时器、数据对象语义名称，以及模板切换失败后界面与 BPMN 的一致性，并用有效模板切换作对照。每个用例附导出 XML，失败保留截图和 trace。这一层不证明登录、服务端保存、部署或运行通过。

`workflow-designer.spec.js` 连接真实前端、后端和数据库，创建隔离的未部署模型，执行 BPMN 文件导入、属性编辑、命令栈撤销、旧 `ManualTask` 明确拒绝、正式扩展与 DMN 选择、服务端校验、保存与页面重载，并在 `finally` 中按真实主键精确删除测试模型和临时 DMN 部署。缺账号时明确报告“缺少登录测试条件”，不再静默跳过。

PowerShell 7 本地运行示例：

```powershell
$env:WORKFLOW_E2E_BASE_URL = 'http://127.0.0.1:1024'
$env:WORKFLOW_E2E_USERNAME = '<具备 workflow:model 权限的账号>'
$env:WORKFLOW_E2E_PASSWORD = '<账号密码>'
$env:PLAYWRIGHT_CHANNEL = 'msedge'
npm run test:e2e:designer
```

使用 Playwright Chromium 时，`PLAYWRIGHT_CHANNEL` 保持空值，并在执行前安装对应浏览器。测试账号至少需要模型查询、创建、设计、保存、校验和删除权限；测试环境至少需要一个启用的流程分类。

只复现组件交互缺陷时，先启动本仓库 Vite 开发服务，再运行：

```powershell
$env:PLAYWRIGHT_CHANNEL = 'msedge'
npx playwright test tests/e2e/workflow-designer-regressions.spec.js
```
