# 业务规则归一与职责收口执行结果

2026-10-03。执行基线为 `main@70b2c0a1357c56ab1c7e645ef1410046c5a303c4`，首次实施的生产提交为 `ae0e3f5`，首次真实浏览器验收提交为 `edd5147`。B00—B18 和 C01 已实施；两轮审查后的修复与独立验收分别列于文末，最新一轮以 `d7fb78f` 为基线。提交留在本地，没有推送。

## 范围与基线

开始时暂存区为空，仅继承两份未跟踪文件：本轮[计划](architecture-rule-consolidation-plan-20261003.md)和[覆盖记录](architecture-rule-consolidation-coverage-20261003.md)。两份文档作为 B00 单独纳入版本管理，其他改动均来自本轮。原有 155 项后端测试先通过；新权限竞争、分类/表单作者规则、Quartz、字典和异步响应用例分别建立失败证据后修复。

环境使用独立数据库 `approvaplat_rules_20261003_it`、Redis 兼容实例端口 16379、实际 Spring Boot/Flowable 后端和 Edge 浏览器。数据库依现有 `docker/mysql/init.sh` 的 14 个 SQL 文件初始化，没有复制第二套初始化顺序。原有 `approvaplat_it`、MySQL 服务及 Memurai 服务状态不作部署变更。测试账号、表单、模型、实例、外库表、附件均为合成数据；凭据只通过环境变量提供。

## 分包结果

以下各包均已完成；B06-A/B/C 合并为一个前端提交，保持唯一请求代次工具。混合文件按内容分别暂存，B02/B17/C01 和 B05/B06 没有混为一个提交。

| 包 | 本地提交 | 最终规则所有者及验证 |
|---|---|---|
| B00 | `760915d` | 固定基线、覆盖清单及隔离环境；历史测试结果与本轮结果分开记录。 |
| B01 | `cf29d67` | `SysPermissionService.loadIdentity` 同一事务读取完整身份；`TokenService` 统一提交后刷新、全部在线 Token 和 Redis CAS。续期不写回旧角色，资料 SQL 只更新四个资料字段。31 项专项覆盖真实 Mapper/MySQL/Redis、刷新入口、撤权竞争与资料竞争。 |
| B02 | `82f448b` | `WfCategoryServiceImpl.updateCategory` 拒绝改码，Mapper 不再写 code；真实 Mapper 验证改名、404、重复新增、引用删除及 API 绕过。 |
| B03 | `3520278` | `WorkflowHttpExecution` 映射超限失败；Outbox 沿既有失败状态机重试/死信。最终次数的过期租约直接收敛死信，保留 CAS、补偿与取消。受控 HTTP、实际 worker 和真实 MySQL 验证超限/超时/坏 JSON/503、租约竞争。 |
| B04 | `6611d38` | `ScheduleUtils` 完整构造后替换；Service 不再提前删 Job。6 项真实 RAM Scheduler 测试覆盖非法策略/表达式、同组/跨组、暂停、过期 cron、立即运行/删除，以及注入故障后的恢复。 |
| B05 | `a57646f` | 字典 store 持有按 key 唯一缓存和在途加载资格；`useDict` 不提交失效结果，CRUD 冻结旧新 key 后失效。正式契约覆盖并发、清空、覆盖、晚到与失败重试。 |
| B06 | `66febec` | 19 行 `requestSequence.js` 提供请求资格；37/37 页面（A7/B7/C23）逐项核查，独立页签/弹窗拥有独立代次。旧响应不能写数据、错误或 loading，协作确认和凭据目标冻结。6 项新浏览器回归与既有页面套件通过。 |
| B07 | `0513f13` | 只读撤回资格迁入 `WorkflowTaskRevokePreparation`；列表传本页 actor/历史任务，写应用保留锁、重验、审计和迁移。原查询对写应用的注入及旧准备实现已删除。4 项专项及现有命令链通过。 |
| B08 | `cd9eb13` | 人工催办候选查询移入 `WorkflowIdentityMapper`，复用认领资格片段，删除 Service 内同义 SQL。真实 MySQL 验证撤权/候选与锁边界；保留 2001 条查询哨兵、2000 名接收人上限、排序和身份共享锁。 |
| B09 | `878ccd5` | 上传台账仅将存储操作异常纳入重试；普通/完整性异常传播，重试登记失败作为 suppressed 保留原因。清理协调器 15 项通过。 |
| B10 | `070c92f` | 邮件 sender 在同一 monitor 中复用当次配置行；通知 Outbox 无用返回值改为 void。邮件事实复用 2 项通过。 |
| B11 | `c2fe45e` | 入站消息复用首次锁定频道，不重复取得频道事实；3 项真实 MySQL 测试覆盖新消息、重放、冲突、等待、补偿和频道隔离。 |
| B12 | `bb5d8e1` | 扩展快照摘要迁到 `WorkflowExtensionChecksum`，Delegate/Listener 不再依赖部署服务计算摘要。2 项固定摘要测试保持旧字节合同。 |
| B13 | `a1b94ab` | 生成器 preview/zip/custom 共用准备与单模板渲染。2 项真实模板测试覆盖 CRUD/tree/sub、Vue2/Vue3/TS、导出内容、聚合入口及自定义路径。 |
| B14 | `ce3209f` | 4 项 SQL 事务/重放测试：PRIMARY 事务回滚/提交，EXTERNAL 已提交后同 Job 重试、不覆盖原行、并发身份幂等、非法模板执行前拒绝。冻结与运行时共用 `requireReplayableResult`，修复外库写结果变量的运行时漏验。 |
| B15 | `c7cb063` | `bin/verify-rules.ps1` 提供 Quick/MySQL/BrowserControlled/BrowserReal 四模式，报告实际计数与未执行项。真实模式缺配置即失败；失败或跳过不报告通过。增加真实浏览器权限/作者合同场景。 |
| B17 | `b3474c4` | 作者输入在 `WorkflowFormTemplateValidator.validateAuthorTemplate` 收口，保存/协议检查/字段目录/初始权限共用；前端复用业务变量规则。作者与分类共 13 项，原编译/版本/目录测试同步验证。历史 decode 入口保持原合同。 |
| B18 | `8e4c487` | 编辑器和写入域共用 `autoCopyRules.js`；原重复校验已删除，保留上下文适配、历史读取、空数组删除及原子 modeling。契约与设计器撤销/重做/未知属性浏览器场景通过。 |
| C01 | `ae0e3f5` | 先真实复现两事务均提交导致悬空引用，再建立[独立修复包](architecture-resource-reference-repair-20261003.md)。资源共享/排他锁、引用检查和读已提交删除边界统一；覆盖创建、保存另存版本、提升版本及 XML/元数据引用并集。5 项真实竞争测试和9项相关模型测试通过。 |
| B16 | `edd5147` 及本文所在提交 | 统一整仓验收、旧实现关闭检查、范围统计、环境精确清理和最终交付；修正原真实浏览器用例在路由提交前刷新的测试竞态。 |

## 行为变化与恢复边界

- 授权变更成功提交后，后续请求使用同一份新身份事实；停用/删除账户会失效现有会话。已进入业务的请求不被中断。数据库提交后的缓存刷新失败会记录并使相关会话失效，不伪称数据库回滚。个人资料提交不能还原管理员已更新的部门、密码和账户状态。
- 分类改码返回 409，名称和备注仍可编辑。非法作者变量在保存阶段返回 400，并在作者协议状态中显示问题；不自动修改已有正文，也不向冻结部署快照新增作者校验。
- 可预期的 HTTP 超限进入协作失败合同；最后一次投递进程中断后，过期租约以 `COLLAB_OUTBOX_LEASE_EXPIRED` 收敛死信，仍可按原合同补偿。数据库状态、leaseOwner 和 revision 保持同一所有者。
- Quartz 先构造再修改 scheduler；后续调用失败会尝试恢复旧定义/触发器/暂停状态，恢复错误保留为 suppressed。内存 scheduler 不参与数据库事务；若 scheduler 连恢复操作也不可用，需要恢复服务并按持久化定义初始化，不能撤销已经触发的任务。
- C01 创建先持锁时，删除等待后以 409 拒绝；删除先提交时，新引用创建以 404 拒绝。模型先分类、再按表单 ID 升序锁定，删除各自只锁本类资源。无数据库字段迁移和全局锁。

## 首次实施验证

后端最终验收的 HEAD 为 `ae0e3f5`；此前 240 项全仓验收已通过，随后新增旧版本另存竞争用例，并重新执行为 241 项。前端静态和受控浏览器在最终前端工作树上运行，当时 HEAD 仍为基线，相关内容随后原样分包提交。真实浏览器最终在 `edd5147` 运行，后端使用前述 clean verify 的新 JAR，SHA256 为 `AD6C7C019332B684021A1DA05CFD1C029987D5E7A7D3977E93ACC6BD919799EB`。

| 验证入口 | 最终结果 |
|---|---|
| `pwsh ./bin/verify-rules.ps1 -Mode MySQL` | 241 通过，0 失败/跳过；7 个模块 clean verify 成功，13:55:31 完成 |
| `npm --prefix ruoyi-ui run lint` | 通过 |
| `npm --prefix ruoyi-ui run test:contracts` | 45 通过，0 失败/跳过 |
| `npm --prefix ruoyi-ui run build:prod` | 通过 |
| `pwsh ./bin/verify-rules.ps1 -Mode BrowserControlled` | 31 通过，0 失败/跳过；Edge、受控响应，覆盖新6项及原25项 |
| `pwsh ./bin/verify-rules.ps1 -Mode BrowserReal` | 5 通过，0 失败/跳过/重试；14:01:32—14:03:07，原三链及新增两项均执行 |
| 缺少真实环境的 MySQL 模式 | 预检按预期失败，报告为 failed，无绿色跳过 |
| `git diff --check`、关闭检查 | 通过；flowable 未增加 framework 依赖，旧规则调用/注入已移除 |

真实浏览器包含原有三链：设计/部署/附件草稿/办理/历史详情，不同申请人和审批人退回重提/恢复身份，动态会签双页面 revision 冲突/隐藏恢复/通知补偿；另加同 Token 多会话授权变更和分类/作者变量直接 API 合同。页面操作使用浏览器，API/SQL用于夹具和证据核对。HTTP 协作投递与 SQL 重放由真实后端专项测试验证，不将受控响应浏览器测试计作这些能力的真实验证。租约中断恢复由“领取后不提交完成、推进租约过期”模拟，没有实际终止 worker 进程。

没有执行外部邮件厂商投递、目标部署多节点故障恢复、容量/长时间稳定性和备份恢复。B11 并发证据覆盖已存在 WAITING 消息的同频道串行/不同频道并行；新主键缺失时的原有 MySQL gap-lock 行为未在本轮重构中改写。

真实浏览器首轮为 1 失败、2 未执行、2 通过，入口如实报告 failed。trace 显示草稿响应中的标题/附件正确，但测试收到 HTTP 响应后立即 reload，实际刷新仍访问旧的新建路由。`edd5147` 仅增加准确草稿地址和保存成功提示的等待，原标题/附件恢复断言不变；重跑全套 5 项通过，没有调整产品逻辑或通过重试隐藏失败。

## 首次实施统计与清理

以下为 `git diff --numstat 70b2c0a edd5147`；B16 本文与计划状态更新另计。新增/删除是物理差异，迁移行不会被解释为净删冗余收益。

| 类别 | 文件数 | 新增行 | 删除行 |
|---|---:|---:|---:|
| 生产 Java/XML/前端 | 120 | 2104 | 1224 |
| 正式测试 | 27 | 3580 | 13 |
| Maven 测试依赖 | 2 | 14 | 2 |
| 验证入口 | 1 | 115 | 0 |
| 文档（含两份继承计划/覆盖记录） | 4 | 564 | 4 |

生产新增中，`WorkflowTaskRevokePreparation` 380 行承接原撤回准备职责，`autoCopyRules.js` 71 行承接两份重复作者校验，`requestSequence.js` 19 行提供各页共享的提交资格。其他必要新增主要为身份 CAS、资源锁定、scheduler 恢复和页面提交检查；保持原有 7 个 Maven 模块与对外调用协议。

清理已完成。停止并核对本任务后端 PID 127140、Vite PID 127920、Redis PID 129560，端口 8080/1024/16379 均无监听；删除专用数据库 `approvaplat_rules_20261003_it` 及本次授予该库的权限，原 `approvaplat_it` 仍存在。MySQL84 保持 Running、Memurai 服务保持 Stopped，与执行前一致。

各正式测试通过 finally/afterAll 按主键清理数据并恢复验证码等单键配置；随后销毁专用库和隔离 Redis，移除任务私有附件目录。已删除 `.task/rule-consolidation`、`output/verification`、Playwright/Surefire 报告、本次 Vite 日志和确认本次新建的三个应用日志。正式回归测试、最终执行报告和常规构建产物保留。清理没有删除已有账号、凭据文件、其他数据库或用户进程。

## 审查补修：`49c963d` 之后

审查指出的 4 项 P2 均按原场景复现后修复，补丁生产与正式测试固定为 `009fb5d`。没有数据库结构迁移或 HTTP 协议变更。

| 审查项 | 修复与正式证据 | 提交 |
|---|---|---|
| 菜单并发编辑漏刷新 | [SysMenuController](D:/ruoyiflowable/ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/SysMenuController.java) 删除未加锁的 before 比较，成功写入即刷新关联用户。真实 MySQL/Redis 复现 B 读 P、A 写 Q 并刷新、B 写回 P 后两个 Token 仍 Q；修复后两个 Token 均为 P。写 0 行、写失败、独立排序入口保持不刷新。 | `a7f3107` |
| 部署删除与分类删除读偏斜 | [WorkflowDeploymentService](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/model/WorkflowDeploymentService.java) 在任何删除副作用前，经 ModelService 对整批恢复模型先锁分类、再锁 XML/元数据表单并集，各自按数值主键排序。分类与元数据独有表单的两条真实交错先失败；[DeploymentResourceRaceMysqlTest](D:/ruoyiflowable/ruoyi-flowable/src/test/java/com/ruoyi/flowable/service/model/WorkflowDeploymentResourceRaceMysqlTest.java) 最终覆盖 5 个真实交错和 1 个历史/安全解析合同。 | `009fb5d` |
| 当前业务静默请求屏蔽 401 | [request.js](D:/ruoyiflowable/ruoyi-ui/src/utils/request.js) 将请求过期与业务静默分开。当前 401 保留重新登录提示，只有过期请求抑制该提示；其他业务/网络错误的静默范围不变。真实 Axios 生产拦截器回归先失败，再验证当前/无代次/过期请求与静默开关组合。 | `63155fd` |
| 卸载后异步续链恢复资格 | [requestSequence.js](D:/ruoyiflowable/ruoyi-ui/src/utils/requestSequence.js) 增加终态 dispose，Vue 卸载后 begin 永远无提交资格；普通 invalidate 仍允许重新加载。正式测试使用真实 Vue mount/unmount 和生产 `WorkflowProcessList.runListMutation → loadList`，覆盖挂起写请求完成后刷新成功/失败，结果不回填。 | `63155fd` |

该轮边界：分类删除先持锁时仍看得到流程定义，删除返回 409，部署恢复随后可成功；恢复先持锁时，分类、元数据表单和 XML 表单的删除等待后返回 409。元数据独有表单先被删除时返回 404 的实现后来确认为 R1 清理死路，由下一节修复替代；只验证回滚不足以证明恢复可用。历史 XML 仅安全提取引用，不重新执行作者变量或内嵌表单规则，DTD 仍拒绝。

补修使用新库 `approvaplat_review_20261003_it`，按同一 14 份正式基线初始化 103 张表，测试账号通过实际 CREATE/DROP 权限探针；旧测试库不作结构修改。Redis 使用独立端口 16379。菜单专项 33 项、部署相关 22 项均通过且无跳过。

补丁统一验收全部通过，失败和跳过均为 0：

| 验证 | 本轮结果 |
|---|---|
| `verify-rules.ps1 -Mode MySQL` | `009fb5d`，249 项；18:32:05—18:33:35，完整 7 模块 clean verify 成功 |
| 前端 lint / contracts / build:prod | lint、构建通过，48 项契约通过 |
| `verify-rules.ps1 -Mode BrowserControlled` | 31 项；使用最终前端工作树运行，随后原样提交为 `63155fd` |
| `verify-rules.ps1 -Mode BrowserReal` | `009fb5d`，5 项；18:34:38—18:36:14，原真实三链和权限/作者场景全部执行 |
| 补丁只读终检 / diff 检查 | `49c963d..009fb5d` 共 12 文件，未发现新的可确认问题；diff 检查通过 |

真实浏览器使用本轮 clean verify 的新 JAR，SHA256 为 `E2744BF8B1C220AC3DB23B7C61805762CA9B606476A344BF4C9971D63978AA85`。本轮无测试重试，也没有将审查时旧库的字段/权限错误计为通过；新隔离库的完整 MySQL 验收已执行。

补修环境已清理：停止后端 PID 135744、Vite PID 125240、Redis PID 135496，确认端口 8080/1024/16379 无监听；删除 `approvaplat_review_20261003_it` 与该库授权、隔离 Redis 数据、私有上传目录、`.task/rule-review-20261003`、运行日志和本轮临时测试报告。旧 `approvaplat_it` 仍存在，MySQL84 保持 Running，Memurai 服务保持 Stopped。四项原始触发场景在该轮关闭；随后的审查又发现 R1—R5，当前阶段结论以后一轮记录为准。补修提交均留在本地，未推送。

## 第二轮审查补修：`d7fb78f` 之后

本轮处理 5 项 P2：R1 是首次补修引入的清理回归，R2—R5 为原阶段遗漏入口。开始时工作区干净。新库 `approvaplat_review2_20261003_it` 由同一 14 份正式 SQL 初始化 103 张表，测试账号完成 CREATE/DROP 探针；Redis 继续使用独立端口 16379。

| 审查项 | 当前行为与正式回归 | 提交 |
|---|---|---|
| R1 旧表单缺失阻断部署清理 | 表单删除统一检查所有模型的元数据与 XML，包括已部署模型；有效 F1 元数据引用使删除返回 409。历史已缺失 F1 且整批 XML 均不引用它时，部署恢复事务移除该无效 formId，并将 formType 0 转为既有模式 2；源 XML、有效 F2 和其他元数据保留。公开部署服务删除后，模型 get/update/delete 能完整清理。后续删除失败时修复、模型关联及制品一并回滚；真实 XML 引用或分类缺失仍拒绝。 | `e8b0328` |
| R2 菜单删除与角色授予交错 | 角色新增/编辑在写角色和关系前按菜单 ID 升序取共享锁；菜单删除同一行取排他锁，再以 READ_COMMITTED 复核。授权先完成返回 601 拒删；删除先完成则返回 404 拒绝授权，无悬空关系。实际删除成功后刷新受影响会话，失败/零行不刷新。排序写采用相同锁序并保留重复 ID 的末次排序值。 | `142338b` |
| R3 集成账号错绑端点 | 在校验前冻结端点 ID 与完整账号内容；改选端点、关闭重开、离开或卸载后，旧请求无权发出绑定写入。真实 Vue 生命周期契约及浏览器关闭 A 后创建 B 的回归通过。 | `15fd6e8` |
| R4 一次性 Token 相互覆盖 | 创建、绑定、轮换共用在途互斥，成功 Token 带账号名称和 ID 保留到“我已保存”；期间禁止再次操作或路由离开。确认取消/校验失败/请求失败释放互斥，不丢弃迟到的成功结果。 | `15fd6e8` |
| R5 迟到初始化覆盖新查询 | 字典详情与调度任务初始化参与各自列表资格；用户查询后旧初始化不能覆写筛选或再发列表请求。字典路由默认值单独保留，用于显式重置，卸载后同样失效。两页均有成功、失败、卸载契约与受控浏览器回归。 | `a5a5716` |

R1 正式旧实现回归为 11 项中 4 项失败，分别证明元数据引用删除漏检、旧数据公开清理 404 和修复回滚链尚不可达；R2 真实 MySQL/Redis 原实现直接复现删除后 Token 仍有权限。前端原实现复现了跨端点提交、异步校验后的内容漂移、重叠轮换，以及两页 A 初始化覆盖 B 筛选。验证使用生产函数与真实 Vue mount/unmount，没有以重写测试逻辑代替生产代码。

R2 权限专项 43 项、R1 相关专项 27 项均通过且无跳过。R1 首轮扩展清理验证出现 3 项夹具错误：原最简竞争 XML 缺少正式发起范围，补齐后又暴露缺少默认字段权限；最终使用符合正式规则的夹具并在部署前调用 `validateDraft`，公开 get/update/delete 断言全部保留，生产作者校验没有放宽。

本轮前端最终 lint、61 项契约和生产构建通过；受控 Edge 浏览器 35 项通过，0 失败/跳过/重试，时间为 19:39:01—19:40:25。该次在最终前端工作树上执行，随后原样分包提交。首轮 33 通过、2 失败来自新增测试把实际 `* 账号名称` 定位成精确的 `账号名称`；按可访问快照修正定位后重跑完整组，未放宽业务断言。

`verify-rules.ps1 -Mode MySQL` 在 `e8b0328` 执行，19:46:40—19:48:22 完成全部模块 clean verify：264 项通过，0 失败/跳过。真实浏览器使用该次新 JAR，SHA256 为 `AB9C07F6B4A171A51AD7DE82B37E9E3559892CC4E5C6C89E4E1AB19BD46B9BCD`。

`verify-rules.ps1 -Mode BrowserReal` 同样在 `e8b0328` 执行，19:49:09—19:50:48，5 项通过，0 失败/跳过/重试：原三条完整业务链及两项权限/分类/作者合同场景全部重跑。独立只读复核检查了 R1 清理与回滚、R2 所有菜单授予入口及锁序、前端资格与重置边界；最终 diff 检查通过。外部邮件厂商、多节点故障、容量和长时间运行仍不在此次验收范围。

本轮范围为 13 个生产文件（新增 254 行、删除 67 行）、7 个正式测试文件（新增 867 行、删除 29 行）、1 个验证入口和 3 份文档；没有数据库字段迁移、HTTP 路径或请求响应结构变更。

环境已清理：停止本轮后端 PID 53436、Vite PID 132924、Redis PID 121676，确认 8080/1024/16379 无监听；删除 `approvaplat_review2_20261003_it` 与该库授权，复查库和授权均为 0、原 `approvaplat_it` 仍为 1。MySQL84 保持 Running、Memurai 服务保持 Stopped。`.task/rule-review2-20261003`、私有上传与日志、Playwright/Surefire 和验证报告已删除并逐路径确认不存在；正式测试、常规构建产物及本文保留。R1—R5 全部关闭，本阶段重新完成验收。4 个修复提交及本文所在文档提交保留本地，未推送。
