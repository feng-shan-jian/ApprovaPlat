# Flowable 真实浏览器 E2E

本目录是 Flowable P3/P4 的正式 Playwright 基础套件。测试通过登录页调用真实 `/login`，随后使用真实 JWT、Redis 登录态、动态菜单、后端 API 和 MySQL 数据；测试不读取 `accounts.local.md`，不注入 Token，不伪造 `storageState`，不拦截工作流 API，也不使用 mock 或 localStorage 构造业务状态。

## 当前覆盖

- 五个预登记职责分离角色的真实 UI 登录、验证码关闭门禁、唯一角色校验，并在每个用例后通过头像菜单调用真实 `/logout` 回收 Redis Token。
- 11 个菜单页共 55 个角色/页面单元，覆盖七类工作台；允许单元核对真实列表 API 和表格，拒绝单元核对 404、菜单隐藏和零业务 API 调用。
- 十个表格导出，分别核对页面筛选值、最小权限角色数据范围、HTTP 状态、MIME、浏览器文件名、文件大小和 SHA-256。
- 使用 fflate 和 XML DOM 结构化解析 XLSX OOXML，核对冻结列名、列顺序、数据行数、过滤列和对应列表 API 的 `total`。
- `workflow-candidate-eligibility.spec.js` 通过真实模型保存、部署、发起和待签页面验证无人可办配置 fail-closed，并验证具备审批资格的角色候选人可真实认领。
- `workflow-lifecycle-actions.spec.js` 为普通审批生命周期定义独立正式实例，覆盖三级串行通过、退回、驳回、撤回、委派与完成委派、转办、候选认领与取消认领。成功动作通过真实页面触发；对象越权、非法状态和陈旧任务通过真实直接 API 核对稳定业务码，并严格比较拒绝前后的流程状态、任务历史和审计记录，证明零副作用。
- 生命周期套件通过正式 API 创建唯一分类、表单、串行模型、候选模型和部署，通过发起页面创建各场景实例；清理阶段依次激活、终止仍运行实例，删除历史、部署、模型、表单和分类，任一清理失败都会使测试失败。
- `workflow-instance-attachment-actions.spec.js` 覆盖 TEMP 附件上传、回显、下载哈希、越权拒绝、删除和物理清理，以及实例挂起/激活、挂起态非法完成、发起人取消、管理员终止和终态历史删除。
- `workflow-multi-instance-any.spec.js` 创建并清理真实动态多实例模型，覆盖无审批资格用户加签拒绝、UI 加签/减签/再次加签、过期 revision 冲突、ANY 首人完成后 sibling 原子取消，以及 ALL 全员完成和连续 revision/审计。
- `workflow-designer-capabilities.spec.js` 覆盖两种目标视口、导入导出、XML/JSON 预览、Lint、偏好、内嵌表单、注释/关联、活动标准循环和通用扩展属性；标准循环服务端诊断为仅可往返并禁止部署。
- JUnit、JSON、HTML、失败截图、失败视频、脱敏 trace 和每份导出摘要。

所有套件都不会创建或重置账号。权限矩阵和导出套件只消费既有正式数据；生命周期、候选资格、实例附件及动态多实例套件会创建并清理各自的正式工作流 fixture，不使用临时 JSON、mock、localStorage 或预置业务主键。当前浏览器套件不替代复杂设计器往返、批准规模并发、权限强化、故障注入、容量、监控、备份恢复或长稳门禁。

## 强制环境变量

### 数据库基线

正式开发库 `ry-vue` 是唯一业务基准。E2E 不直接写入正式库；启动隔离后端或运行 RBAC HTTP 集成测试前，脚本会执行：

```powershell
& .\deployment\scripts\sync-e2e-baseline.ps1
```

该脚本只允许从 `ry-vue` 重建 `ry_vue_codex_flowable_it`，随后重新注入五个隔离测试身份并校验菜单、角色、工作流业务表和“扩展流程管理”二级菜单。禁止手工长期维护隔离库结构；正式库迁移或菜单更新后，应重新同步再执行 E2E。

用户名变量与 `WorkflowRbacHttpIT` 共用，全部由执行进程注入；五个本地验收账号的密码固定为 `wang`：

```text
FLOWABLE_RBAC_ACCOUNTS_REGISTERED=true
FLOWABLE_RBAC_WORKFLOW_ADMIN_USERNAME
FLOWABLE_RBAC_WORKFLOW_DESIGNER_USERNAME
FLOWABLE_RBAC_WORKFLOW_STARTER_USERNAME
FLOWABLE_RBAC_WORKFLOW_APPROVER_USERNAME
FLOWABLE_RBAC_WORKFLOW_AUDITOR_USERNAME
```

任一变量缺失、五个用户名不唯一、登记门禁不为 `true`、验证码开启、账号角色不唯一或真实登录失败时，测试明确失败，不会 skip。测试账号不得在任务中生成随机密码或另行重置，统一使用 `wang`。

默认由 Playwright 在 `127.0.0.1:1024` 启动 Vite，Vite 继续把 `/dev-api` 代理到真实 `localhost:8080`。隔离环境可通过 `VITE_PROXY_TARGET` 指定另一个真实后端，并用 `FLOWABLE_E2E_BASE_URL` 选择未占用的本机前端端口；自动启动模式只接受本机 HTTP 根地址。验证已部署环境时设置：

```text
FLOWABLE_E2E_START_FRONTEND=false
FLOWABLE_E2E_BASE_URL=https://受控验收域名
```

## 生命周期套件运行前置条件

运行 `workflow-lifecycle-actions.spec.js` 前必须同时满足：

- 五角色账号已经在正式用户、角色、菜单和数据权限中预登记，执行进程只通过上述环境变量注入用户名，密码固定为 `wang`。
- 真实 MySQL、Redis 和后端均已启动，数据库已执行当前工作流正式迁移与校验脚本；后端可通过 Vite 的 `/dev-api` 代理访问，验证码在受控 E2E 环境中关闭。
- 后端已加载正式 `userTaskListener`、任务动作、对象授权、身份目录、模型部署、实例运维和历史删除能力；不得使用旧后端进程验证新前端或新测试代码。
- `workflow_designer` 可创建及删除分类、表单、模型和部署，`workflow_starter` 可发起流程，`workflow_approver` 与 `workflow_admin` 具备各自动作权限，管理员具备失败清理所需的实例终止和历史删除权限。
- Chromium 已安装，前端与后端端口在整次用例执行期间保持稳定；不得在测试运行中重启服务或并行执行会修改相同正式 fixture 的套件。

## 当前执行状态

- 2026-07-28 在隔离 MySQL schema、Redis DB 14、当前候选后端和 Chromium 上集中执行完整套件：6 个 spec、`20/20 passed`，`unexpected=0`、`skipped=0`、`flaky=0`、零重试，总耗时 149.43 秒。
- 分项为候选资格 1/1、十导出 10/10、实例/附件 1/1、普通生命周期 1/1、动态 ANY/ALL 2/2、五角色页面权限 5/5；所有创建型套件的正式资源清理均成功。
- 从操作审计提取 27 个资源 UUID 完成数据库对账：模型、部署、定义、字节资源、运行/历史、任务、变量、身份链、事件、job、`wf_deploy_form`、`wf_copy` 及活动表单/分类均为 0；仅保留表单/分类软删除墓碑各 5 条、操作审计 38 条和 1 条物理文件已删除的附件 `DELETED` 审计墓碑。
- P3-03 的连续两次通过门禁当前仅完成第一次，所以 P3 保持 `in_progress`；扩展并发、故障注入、权限强化、性能、监控、备份恢复、生产彩排和 72 小时观察均为 `not executed`。

## 执行

```powershell
cd D:\ruoyiflowable\vite
npm ci
npx playwright install chromium
npm run test:e2e
```

只运行单个套件：

```powershell
npm run test:e2e -- workflow-rbac.spec.js
npm run test:e2e -- workflow-exports.spec.js
npm run test:e2e -- workflow-candidate-eligibility.spec.js
npm run test:e2e -- workflow-instance-attachment-actions.spec.js
npm run test:e2e -- workflow-lifecycle-actions.spec.js
npm run test:e2e -- workflow-multi-instance-any.spec.js
```

## 证据与保密

全部证据位于 `vite/output/playwright/`，根目录 `.gitignore` 已忽略 `output/`。HTML、JSON、JUnit、下载文件、截图和视频可能包含受对象授权保护的业务数据；只允许转存到受控验收归档，并按发布批次的留存策略销毁。

trace 在真实登录完成后才启动，并关闭 DOM、源码和网络快照，避免账号输入值及 Authorization 头进入 trace。登录证据只保存角色键和验证码门禁，不保存用户名、密码、Token 或响应正文。
