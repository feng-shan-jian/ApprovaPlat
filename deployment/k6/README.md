# Flowable k6 固定迭代门禁

## 1. 目标与边界

本目录提供低成本、可重复的真实 HTTP 快速门禁，不按自然时长等待：

- `workflow-read-gate.js` 使用 `shared-iterations`，并发读取当前角色依法可见的核心工作台。
- `workflow-isolated-lifecycle-gate.js` 使用 `per-vu-iterations`，在专用单任务流程上执行真实发起、待办定位、变量读取、审批、终态对账和历史清理。
- `lib/workflow-http.js` 统一处理一次登录、JWT、`AjaxResult.code`、固定低基数标签和脱敏错误。

k6 只替代重复的 API 负载脚本和定时空等，不能证明浏览器交互、多节点 executor、共享附件卷、故障接管、告警送达、备份恢复或生产 24/72 小时观察。未在生产同构环境执行并完成数据对账、阈值审批时，P5-01 仍是 `blocked`。

## 2. 凭据规则

Token、用户名和密码只允许通过进程环境或仓库外受限密钥文件注入。脚本、命令参数、Git、k6 汇总和证据文件不得出现真实值，也不要使用 `--http-debug`。如生成或重置账号/密码，必须在首次使用前写入 `testcount/accounts.local.md`，并确认该文件仍被 Git 忽略。

认证优先级如下：

1. 只读门禁使用 `FLOWABLE_K6_TOKEN`；缺失时使用 `FLOWABLE_K6_USERNAME`、`FLOWABLE_K6_PASSWORD` 在 setup 中真实登录一次。
2. 隔离生命周期分别使用 `FLOWABLE_K6_STARTER_*`、`FLOWABLE_K6_APPROVER_*`、`FLOWABLE_K6_ADMIN_*` 的 `TOKEN`，或对应 `USERNAME`、`PASSWORD`。
3. 脚本只注销自己通过 `/login` 创建的 Token，不注销外部注入 Token。

用户名密码模式要求目标环境已按验收约定关闭验证码；否则必须由受控密钥系统注入 Token。

## 3. 只读快速门禁

角色与默认查询严格对应正式 RBAC 矩阵：

| `FLOWABLE_K6_READ_PROFILE` | 查询范围 |
| --- | --- |
| `admin` | 可发起、我发起、实例管理、待办、可认领、已办、抄送 |
| `starter` | 可发起、我发起、抄送 |
| `approver` | 待办、可认领、已办、抄送 |
| `auditor` | 我发起、待办、可认领、已办、抄送 |

PowerShell 示例只引用已经注入的环境变量，不把凭据值写入命令：

```powershell
$env:FLOWABLE_K6_BASE_URL = 'http://127.0.0.1:8080'
$env:FLOWABLE_K6_READ_PROFILE = 'admin'
$env:FLOWABLE_K6_USERNAME = $env:FLOWABLE_RBAC_WORKFLOW_ADMIN_USERNAME
$env:FLOWABLE_K6_PASSWORD = $env:FLOWABLE_RBAC_WORKFLOW_ADMIN_PASSWORD
$env:FLOWABLE_K6_VUS = '5'
$env:FLOWABLE_K6_ITERATIONS = '50'
& 'C:\Program Files\k6\k6.exe' run .\deployment\k6\workflow-read-gate.js
Remove-Item Env:\FLOWABLE_K6_USERNAME, Env:\FLOWABLE_K6_PASSWORD -ErrorAction SilentlyContinue
```

每个 iteration 对该角色全部查询发起一个真实并发批次。负载总量由 `FLOWABLE_K6_ITERATIONS` 精确决定；`FLOWABLE_K6_MAX_DURATION` 仅是挂死保护上限，不是按时间压测。所有自定义变量都使用 `FLOWABLE_K6_*` 前缀，避免覆盖 k6 自带的 `K6_VUS`、`K6_ITERATIONS` 等保留配置。

## 4. 隔离发起与审批门禁

该脚本默认 `blocked`。只有同时满足以下条件才允许设置 `FLOWABLE_K6_ISOLATED_MUTATION_ACK=isolated-cleanup-approved` 和 `FLOWABLE_K6_ISOLATION_RESET_ACK=isolated-schema-reset-approved`：

- 使用专用、已部署、单用户任务后直接结束的流程定义，且没有业务对象、附件、抄送、timer、异步 job 或外部副作用。
- `FLOWABLE_K6_PROCESS_DEFINITION_ID`、`FLOWABLE_K6_PROCESS_KEY` 和 `FLOWABLE_K6_TASK_DEFINITION_KEY` 精确指向该定义及唯一审批任务。
- 发起人、审批人、流程管理员为预登记真实账号；管理员具备详情、终止和历史删除权限。
- `FLOWABLE_K6_START_VARIABLES_JSON` 与 `FLOWABLE_K6_COMPLETE_VARIABLES_JSON` 满足部署表单 schema。
- 使用本轮唯一 `FLOWABLE_K6_RUN_ID`，并确认失败实例可按该标识人工追踪。
- 目标是可重置的隔离 schema；实例历史由脚本逐次删除，依法保留的 `sys_oper_log`、登录日志等审计数据在整轮证据归档后按批准方案重置。共享验收库或生产库不满足该条件。

```powershell
$env:FLOWABLE_K6_BASE_URL = 'http://127.0.0.1:8080'
$env:FLOWABLE_K6_ISOLATED_MUTATION_ACK = 'isolated-cleanup-approved'
$env:FLOWABLE_K6_ISOLATION_RESET_ACK = 'isolated-schema-reset-approved'
$env:FLOWABLE_K6_RUN_ID = 'release-candidate-001'
$env:FLOWABLE_K6_BUSINESS_KEY_PREFIX = 'k6-gate'
# 隔离 fixture 发布步骤还必须预先注入 FLOWABLE_K6_PROCESS_DEFINITION_ID、
# FLOWABLE_K6_PROCESS_KEY、FLOWABLE_K6_TASK_DEFINITION_KEY 及两个变量 JSON。
$env:FLOWABLE_K6_STARTER_USERNAME = $env:FLOWABLE_RBAC_WORKFLOW_STARTER_USERNAME
$env:FLOWABLE_K6_STARTER_PASSWORD = $env:FLOWABLE_RBAC_WORKFLOW_STARTER_PASSWORD
$env:FLOWABLE_K6_APPROVER_USERNAME = $env:FLOWABLE_RBAC_WORKFLOW_APPROVER_USERNAME
$env:FLOWABLE_K6_APPROVER_PASSWORD = $env:FLOWABLE_RBAC_WORKFLOW_APPROVER_PASSWORD
$env:FLOWABLE_K6_ADMIN_USERNAME = $env:FLOWABLE_RBAC_WORKFLOW_ADMIN_USERNAME
$env:FLOWABLE_K6_ADMIN_PASSWORD = $env:FLOWABLE_RBAC_WORKFLOW_ADMIN_PASSWORD
$env:FLOWABLE_K6_VUS = '1'
$env:FLOWABLE_K6_ITERATIONS_PER_VU = '1'
& 'C:\Program Files\k6\k6.exe' run .\deployment\k6\workflow-isolated-lifecycle-gate.js
```

任一实例只使用发起响应返回的主键清理。运行实例先由管理员终止，再删除已结束历史；清理失败会单独使阈值失败。脚本不会越权删除依法保留的操作审计，因此整轮结束后仍必须执行并验证已批准的隔离 schema 重置。没有上述隔离定义、清理权限和重置方案时，本项必须记录为 `blocked`，不得在共享验收或生产数据上运行。

## 5. 参数与阈值

| 参数 | 只读默认值 | 生命周期默认值 | 说明 |
| --- | ---: | ---: | --- |
| `FLOWABLE_K6_VUS` | 5 | 1 | 固定并发 VU |
| `FLOWABLE_K6_ITERATIONS` | 50 | - | 全部 VU 共享的固定总迭代数 |
| `FLOWABLE_K6_ITERATIONS_PER_VU` | - | 1 | 每个 VU 的固定生命周期数 |
| `FLOWABLE_K6_PAGE_SIZE` | 20 | 100 | 查询页大小，最大 200 |
| `FLOWABLE_K6_P95_MS` | 1500 | 3000 | 候选门禁 P95 上限 |
| `FLOWABLE_K6_P99_MS` | 3000 | 6000 | 候选门禁 P99 上限 |
| `FLOWABLE_K6_REQUEST_TIMEOUT` | 10s | 10s | 单请求挂死保护 |
| `FLOWABLE_K6_MAX_DURATION` | 5m | 10m | 固定迭代整体挂死保护 |

两个入口都要求：

- `checks` 成功率为 100%。
- `workflow_business_success` 为 100%，同时覆盖 HTTP 200、业务码 200 和响应结构。
- 门禁范围 `http_req_failed < 1%`。
- P95/P99 小于注入阈值。
- 生命周期的 iteration 与 cleanup 成功率均为 100%。

默认延迟阈值只用于开发候选快速失败。正式容量结论必须以批准的 SLO 覆盖 `FLOWABLE_K6_P95_MS`、`FLOWABLE_K6_P99_MS`，在生产同构环境执行，并对 MySQL、Flowable runtime/history、`wf_*`、Redis、线程池和连接池做测试前后对账。

## 6. 静态检查与证据

```powershell
& 'C:\Program Files\k6\k6.exe' inspect .\deployment\k6\workflow-read-gate.js
& 'C:\Program Files\k6\k6.exe' inspect .\deployment\k6\workflow-isolated-lifecycle-gate.js
```

需要机器可读结果时，将文件写到仓库已忽略的 `output/` 或仓库外受控目录：

```powershell
& 'C:\Program Files\k6\k6.exe' run `
  --summary-export .\output\k6\workflow-read-summary.json `
  .\deployment\k6\workflow-read-gate.js
```

汇总只保存指标与固定低基数标签，不保存响应正文。执行记录必须注明 Git commit、环境、固定迭代数、VU、数据规模、阈值、结论和未执行门禁。
