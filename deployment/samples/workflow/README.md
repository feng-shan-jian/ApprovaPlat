# 审批样例置备

`workflow-samples.json` 定义可直接验收的测试部门、角色、用户、流程分类、表单、流程节点和身份自然键。置备脚本只调用平台正式 API，不直接写 Flowable、系统业务表或临时数据文件，也不包含账号密码和固定数据库主键。

## 执行方式

本地前后端服务和正式数据库启动后执行。管理员密码与测试身份密码只通过当前 PowerShell 进程环境变量传入，脚本不会输出或持久化密码：

```powershell
$env:APPROVA_SAMPLE_ADMIN_PASSWORD = '<管理员密码>'
$env:APPROVA_SAMPLE_IDENTITY_PASSWORD = '<测试身份统一密码>'
node .\deployment\scripts\provision-workflow-samples.mjs --username admin
Remove-Item Env:APPROVA_SAMPLE_ADMIN_PASSWORD, Env:APPROVA_SAMPLE_IDENTITY_PASSWORD
```

脚本先通过 `/system/dept`、`/system/role` 和 `/system/user` 创建或核验正式测试身份，再通过角色菜单增量授权 API 为审批参与者角色追加待办、待签、详情、审批、认领权限及必要父菜单。增量授权只插入缺失关联，不替换或删除角色已有菜单。随后脚本创建或复用分类及表单，保存经过后端安全校验的 BPMN，最后部署未部署的样例模型。

重复执行时，部门层级、角色自然键、测试账号资料与角色绑定、分类名称、同名表单 JSON、模型元数据、模型 BPMN、部署快照和已部署 BPMN 都必须与目录一致。任何重复名称、标识碰撞或内容漂移都会明确失败，不会静默接管正式资产；已经一致的已部署模型会返回真实 `deploymentId` 并跳过重复部署。

## 内置测试身份

所有账号使用执行时传入的统一测试密码。普通测试身份不会绑定 `admin` 或 `workflow_admin`。

| 账号 | 身份 | 部门 | 主要能力 |
| --- | --- | --- | --- |
| `sample_employee` | 示例员工 | 业务部 | 发起流程 |
| `department_manager` | 部门负责人 | 业务部 | 发起、认领、审批、选择会签人员 |
| `finance_manager` | 财务负责人 | 财务部 | 发起、财务审批 |
| `purchase_manager` | 采购负责人 | 采购部 | 发起、采购审批、选择或签人员 |
| `hr_manager` | 人力负责人 | 人力资源部 | 发起、人事审批、并行汇总 |
| `it_manager` | IT 负责人 | 信息技术部 | 发起、IT 审批 |
| `executive_office` | 总经办专员 | 总经办 | 发起、行政审批 |
| `general_manager` | 总经理 | 总经办 | 发起、终审、审计查看 |
| `document_controller` | 文控专员 | 总经办 | 发起、文控审批；复用并纳管已有同名测试账号 |

## 内置流程能力矩阵

| 模型 | 复杂度 | 展示能力 |
| --- | --- | --- |
| 快速请示 | 简单 | 单级角色候选审批 |
| 请假申请 | 简单 | 两级角色串行审批、退回路径 |
| 费用报销 | 中等 | 部门候选认领、指定用户终审 |
| 采购申请 | 中等 | 三级指定用户串行审批 |
| 合同审批 | 中等 | 文控、总经办、总经理多角色协同 |
| 用印申请 | 中等 | 角色候选认领与行政审批 |
| 金额条件审批 | 复杂 | 表单金额变量、排他网关、阈值路由 |
| 并行入职准备 | 复杂 | 并行网关拆分、双分支办理、汇聚确认 |
| 动态多人会签 | 复杂 | 前置任务动态选人、并行多实例、全部通过 |
| 动态多人或签 | 复杂 | 前置任务动态选人、并行多实例、任一通过 |
| 受控自动化审批 | 复杂 | 扩展注册表、受控 ServiceTask、不可变执行快照 |

动态会签和或签不会从开始表单伪造人员变量。办理前置“选择人员”任务时，系统通过正式下一节点人员接口写入 `wfMiUsers_{activityId}`，随后固定的 `multiInstanceHandler` 创建真实多实例任务。受控自动化样例只引用系统已安装的 `approva.set-variable` 扩展，不允许任意 `delegateExpression` 或类名。
