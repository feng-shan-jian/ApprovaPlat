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

在仓库根目录运行后端验证：

```powershell
mvn clean verify
git diff --check
```

在 `ruoyi-ui` 目录运行前端验证：

```powershell
npm run lint
npm run test:contracts
npm run build:prod
```

真实 MySQL 测试使用隔离数据库和 `workflow-mysql-it` profile，配置示例见 [README](README.md#开发与测试)。浏览器测试的账号、环境与命令见[工作流浏览器回归](ruoyi-ui/tests/e2e/README.md)。根据改动选择对应验证层，并在 Pull Request 中记录实际执行的命令、结果及未执行项。
