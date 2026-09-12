# 流程引擎 Computer Use 修复后复测与逐步降级报告

测试时间：2026-09-09 晚至 2026-09-10 01:03（Asia/Shanghai）。项目：D:/ruoyiflowable。

## 结论

本轮按照“失败记录 → 每次只降级一个最小配置/元素 → 再测试”的方式完成复测，20 个有效测试实例在前端显示“已完成”。另一个误用旧版本的实例虽完成，但不计入有效覆盖；两个误操作实例已通过前端取消。

修复后的会签、或签可以配置、保存、部署、生成任务并完成；包含内部开始节点的展开子流程可以正式发起并执行到结束。原长流程保留全部审批、网关和子流程，仅移除当前环境无法唤醒的 PT5S 定时器后，小额、大额路径均完成。另补测了包容网关的默认出口。

**完整设计器能力验收仍未全通过。** 调用活动、业务规则任务、错误/升级边界、消息抛出、数据对象、跨池协作、正式模板等存在配置或保存阻塞；接收任务、消息捕获停留在等待外部事件阶段。降级版本完成不能算作这些原功能通过。

## 测试方式与证据范围

- 使用现有 Edge 浏览器，地址 http://127.0.0.1:1024，管理员账号；通过 Windows 原生鼠标、键盘、拖拽、滚轮及剪贴板输入操作前端。
- 本会话没有专用 computeruse 工具，采用 Win32 原生输入和桌面截图作为替代。辅助脚本只执行桌面输入与截图，未用 DOM/evaluate/API/数据库操作创建或推进流程。
- 长流程基础结构来自上轮通过前端创建并复制出的 BPMN 文件，本轮在前端导入、修改身份配置、保存、部署、正式发起并审批。两个高级覆盖模型在前端建立并逐步扩展。
- BPMN 文件均通过设计器“查看 XML → 复制”留存，作为只读证据；没有编写或修改 XML 来绕过前端阻塞。服务任务的 JSON 是在产品公开的配置输入框填写。
- 执行结论来自待签/待办、流程详情、流转图和终态。没有开展本轮 API/数据库交叉验真、并发压测或引擎表审计；截图中的绿色节点不单独作为消息成功投递的证据。
- 所有证据位于 [本轮证据目录](D:/ruoyiflowable/output/computeruse-retest-20260909)。原生操作日志为 [actions.jsonl](D:/ruoyiflowable/output/computeruse-retest-20260909/actions.jsonl)，操作工具为 [desktop.ps1](D:/ruoyiflowable/output/computeruse-retest-20260909/desktop.ps1)。
- 截图顶部红/粉色文字为桌面其他程序的歌词悬浮层，不属于项目。部分截图文件名是操作前命名，结论以本文引用的实际画面为准。

环境只读检查：后端 8080、前端 1024，测试开始时已运行修复后的后端。仓库 HEAD 为 9ddac99；当前源码配置 [application.yml:148](D:/ruoyiflowable/ruoyi-admin/src/main/resources/application.yml:148) 为 `async-executor-activate: false`。未修改该配置，也未重启服务开启调度器；该配置与定时器等待现象一致，但未读取生效配置或引擎作业表来独立证明根因。

## 长流程与修复验证

模型：CU0909R复杂流程复测，key=`cu0909_retest`，节点表单。

V1 有 23 个流程节点、25 条顺序流；包含 11 个用户任务定义、6 个网关、2 个开始、2 个结束、1 个定时捕获、1 个展开子流程。03 会签及 04 或签均通过前端选“指定用户 → 管理员”配置。只有一个实际成员，故不代表多人会签等待或或签竞争已经验证。

主路径：
提交申请(amount) → 01采购初审 → 02候选复核(领取) → 03会签核验 → 04或签确认 → 并行分支05财务/06法务 → 排他分支07小额/08大额 → 定时器 → 包容分支09税务/10安全/默认直达 → 11归档子流程(内部开始、审批、结束) → 主结束。

V1 于 09-09 22:47:25 部署。实例 B 依次到达普通、候选、多实例、并行、小额审批，停在 PT5S，超过 30 秒仍没有下游任务；最终回看仍在等待。[定时器最终画面](D:/ruoyiflowable/output/computeruse-retest-20260909/timer-final-still-waiting.png)

**最小降级 D1：只删除定时器，由前端重连原前后节点。** V2 于 22:52:43 部署，22 个节点、24 条顺序流。会签、或签、所有网关和展开子流程保留。

| 实例 | 输入/版本 | 实际结果 |
|---|---|---|
| B | 500 / V1 | 到定时器，后续未到达，保留复现 |
| C | 500 / V2 | 01、领取02、03、04、05和06、07、09和10、11、结束，已完成 |
| D | 3000 / V2 | 01、领取02、03、04、05和06、08默认大额、仅09、11、结束，已完成 |
| E | 3000 / V3 | 01、领取02、03、04、05和06、08、包容默认直达、11、结束，已完成 |

C、D 合计覆盖全部 11 个用户任务定义。并行网关在先完成一侧时等待另一侧；包容网关在 C 中等待两个命中分支，在 D 中只生成税务任务。[C 完成](D:/ruoyiflowable/output/computeruse-retest-20260909/c-completed-diagram.png)、[D 完成](D:/ruoyiflowable/output/computeruse-retest-20260909/d-completed-diagram.png)、[并行等待](D:/ruoyiflowable/output/computeruse-retest-20260909/b-parallel-waits-finance.png)、[包容等待](D:/ruoyiflowable/output/computeruse-retest-20260909/c-inclusive-waits-tax.png)

V2 的条件 amount>0 与 amount<=2000 总能至少命中一个，无法用输入覆盖默认出口。因此另做 V3 覆盖实验，只把税务条件阈值从 0 调整为 5000，其他流程结构不变，输入 3000。两个条件同时不成立，09/10 均不生成待办，默认线通向归档并结束。这是增加覆盖的配置变体，不是失败降级。[规则面板](D:/ruoyiflowable/output/computeruse-retest-20260909/inclusive-default-rule.png)、[V3 部署](D:/ruoyiflowable/output/computeruse-retest-20260909/inclusive-default-v3-deployed-confirmed.png)、[默认分支完成](D:/ruoyiflowable/output/computeruse-retest-20260909/e-default-completed-confirmed.png)

旧报告中的会签/或签保存失败及子流程发起失败，本轮已在真实用户路径上通过复测。[多实例保存](D:/ruoyiflowable/output/computeruse-retest-20260909/repaired-multisign-save.png)、[会签到达](D:/ruoyiflowable/output/computeruse-retest-20260909/b-all-arrived.png)、[或签到达](D:/ruoyiflowable/output/computeruse-retest-20260909/b-any-arrived.png)、[子流程任务到达](D:/ruoyiflowable/output/computeruse-retest-20260909/c-subprocess-arrived.png)

## 设计器元素覆盖账本

“可见/已建/保存/部署/运行”分别记录，不把画在画布上当成可运行。表中“是”只针对本轮实际配置。图形元素本来不生成待办。

| 元素或能力 | 可见 | 已建/配置 | 保存 | 部署 | 实际运行/限制 |
|---|---|---|---|---|---|
| 普通开始、结束、顺序流 | 是 | 是 | 是 | 是 | 多个实例通过 |
| 用户任务：发起人/指定人 | 是 | 是 | 是 | 是 | 01、11及高级审批通过 |
| 候选用户任务 | 是 | 是 | 是 | 是 | 02在待签领取后办理通过 |
| 会签 ALL | 是 | 是 | 是 | 是 | 03通过，仅单成员 |
| 或签 ANY | 是 | 是 | 是 | 是 | 04通过，仅单成员 |
| 并行网关分叉/汇聚 | 是 | 是 | 是 | 是 | 双任务到达、等待及汇聚通过 |
| 排他网关条件/默认分支 | 是 | 是 | 是 | 是 | 500与3000均正确 |
| 包容网关分叉/汇聚/默认线 | 是 | 是 | 是 | 是 | 多命中、单命中、零命中默认均通过 |
| 展开子流程 | 是 | 是 | 是 | 是 | 内部审批及外部结束通过 |
| 折叠子流程 | 是 | 是 | 是 | 是 | 内部开始→结束通过 |
| 事务子流程 | 是 | 是 | 是 | 是 | 正常路径通过；取消/回滚未验证 |
| 事件子流程 | 是 | 是 | 是 | 是 | 信号开始，中断及非中断两种路径通过 |
| 服务任务 | 是 | 是 | 是 | 是 | 设置流程变量处理器后下游到达并完成；变量值未独立核对 |
| 发送任务 | 是 | 是 | 是 | 是 | 设置变量处理器路径通过；不代表跨池消息投递 |
| 接收任务 | 是 | 是 | 是 | 是 | 到达等待；前端没有人工完成入口，外部触发未执行 |
| 业务规则任务 | 是 | 是 | 否 | 否 | DMN目录空，无法完成绑定 |
| 调用活动 | 是 | 是 | 否 | 否 | 可调用流程目录空，无法完成绑定 |
| 定时捕获 PT5S | 是 | 是 | 是 | 是 | 到达但不唤醒，当前环境受阻 |
| 消息捕获 | 是 | 是 | 是 | 是 | 到达等待，未收到匹配消息 |
| 信号捕获 | 是 | 是 | 是 | 是 | 原等待实例被另一前端发起流程的信号唤醒并完成 |
| 消息抛出 | 是 | 是 | 否 | 否 | BPMN_CONTRACT_INVALID |
| 信号抛出 | 是 | 是 | 是 | 是 | 唤醒独立捕获及事件网关的信号分支 |
| 事件网关 | 是 | 是 | 是 | 是 | 信号/消息两出口，信号胜出并完成；消息胜出未验证 |
| 错误边界 | 是 | 是 | 否 | 否 | 正式业务编码选中后丢失 |
| 升级边界 | 是 | 是 | 否 | 否 | 正式业务编码选中后丢失 |
| 补偿边界+补偿服务+补偿抛出 | 是 | 是 | 是 | 是 | 补偿服务在运行图实际执行，主流程完成 |
| 单池、泳道 | 是 | 是 | 是 | 是 | 附带上述活动运行通过；不是人工任务 |
| 第二池+消息流 | 是 | 是 | 否 | 否 | 第二流程缺发起范围，面板无对应入口 |
| 数据对象 | 是 | 是 | 否 | 否 | 单独加入也触发通用合同校验失败 |
| 数据存储引用 | 是 | 是 | 是 | 是 | 单独保留后流程完成，未测试实际数据库存储 |
| 数据输入关联 | 是 | 是 | 否 | 否 | 与数据对象一起被阻塞，不能判定关联本身有缺陷 |
| 数据输出关联 | 是 | 是 | 是 | 是 | 审批→数据存储的关联可保存部署，实际数据写入未验证 |
| 分组、文本注释、普通关联 | 是 | 是 | 是 | 是 | 设计器保存保留，附带流程可完成；运行图未完整呈现所有装饰元素 |
| 内嵌发起表单 | 是 | 是 | 是 | 是 | 金额、文本输入及正式提交通过 |
| 正式模板/节点字段权限 | 是 | 尝试 | 否 | 否 | qweqw选中后0字段，无法保存该配置 |

工具箱放置、拖拽连接、上下文变更类型、删除、缩放、查看/复制 XML、保存、部署、发起、待签领取、待办审批、流转图及取消流程均有实际操作。没有穷举上下文菜单中所有 BPMN 变体、所有表单控件、参与人规则、业务监听器、扩展属性组合、CEL/HTTP/SQL 连接器、多人并发或权限隔离；这些不标记为通过。故账本覆盖主工具箱/高级菜单的元素类别，不声称整个设计器所有配置组合完成验收。

## 失败与最小降级记录

### R01 定时器不唤醒（环境受阻）

复现：长流程 V1，PT5S，审批到定时器后持续等待，下游09/10/11未生成。D1仅删除定时器，V2小额、大额均完成。未用接口触发作业，也未开启异步执行器掩盖当前环境问题。

证据：[等待](D:/ruoyiflowable/output/computeruse-retest-20260909/b-timer-after-7s.png)、[无下游](D:/ruoyiflowable/output/computeruse-retest-20260909/b-timer-no-downstream.png)、[最终仍等待](D:/ruoyiflowable/output/computeruse-retest-20260909/timer-final-still-waiting.png)。

### R02 调用活动没有可选流程（前端配置受阻）

CU0909R节点覆盖补测中加入“高级02调用子流程”并连接，打开正式调用目录显示无数据；当时已有其他已部署流程。没有将空目录直接归因为引擎不支持调用，可能涉及可调用资格/授权。

D1仅删除该调用活动，继续原测试链。后续最小发送任务版本 V3 完成，但调用活动本身未部署、未运行。

证据：[已建调用节点](D:/ruoyiflowable/output/computeruse-retest-20260909/call-created.png)、[空目录](D:/ruoyiflowable/output/computeruse-retest-20260909/call-catalog.png)。

### R03 业务规则任务无 DMN 可绑定（资源及创作入口受阻）

“高级04业务规则”的决策目录空。DMN管理页0条决策/0版本，部署入口仅上传文件或输入 DMN XML，没有可视化决策编辑入口。

D1仅删除业务规则任务，继续原测试链，发送任务版本最终完成。未手写 XML 代替前端设计。此项记录当前环境无法完成业务规则节点配置，不等于已证明 DMN 引擎有缺陷。

证据：[空决策目录](D:/ruoyiflowable/output/computeruse-retest-20260909/business-rule-catalog.png)、[DMN管理](D:/ruoyiflowable/output/computeruse-retest-20260909/dmn-management.png)、[部署入口](D:/ruoyiflowable/output/computeruse-retest-20260909/dmn-deploy-entry.png)。

### R04 接收任务到达后没有前端推进路径（外部触发未验证）

V1 的“高级05接收任务”保存、部署、发起成功，但停在接收等待。设计器明确提示该节点不生成平台待办，需集成接口及集成令牌；运行事件管理页只有审计查询，没有触发按钮。

D1仅删除接收任务并重连，实际验证生效的 V3 中，发送任务执行后生成审批并完成。中途第一次删除点击未生效，产生 V2 重复等待实例，已取消，不作为新的产品失败。

证据：[等待](D:/ruoyiflowable/output/computeruse-retest-20260909/receive-runtime-wait.png)、[无待办](D:/ruoyiflowable/output/computeruse-retest-20260909/receive-no-todo.png)、[事件管理](D:/ruoyiflowable/output/computeruse-retest-20260909/runtime-event-console.png)、[D1完成](D:/ruoyiflowable/output/computeruse-retest-20260909/send-d1-completed.png)。

### R05 消息捕获未收到消息，信号捕获后来成功

V4：消息捕获→信号捕获→发送任务→审批，停在消息捕获。
D1：只删除消息捕获，V5停在信号捕获。
D2：只删除信号捕获，V6审批完成。

随后补建前端信号抛出流程：V7发出的同名信号使**原V5等待实例**继续到审批并完成。因此信号捕获最终判定通过，不沿用早期“没有前端触发”的受阻结论。原V4消息等待仍保留。

事件网关 V8 另建同名信号捕获与消息捕获两条出口，先进入双等待，再由高级模型 V2 的信号抛出使信号分支胜出并完成。消息分支被取消不能算消息收到；图中被取消节点边框颜色也不单独证明投递。

证据：[消息等待](D:/ruoyiflowable/output/computeruse-retest-20260909/message-runtime-wait.png)、[信号原等待](D:/ruoyiflowable/output/computeruse-retest-20260909/signal-runtime-wait.png)、[V5后来完成](D:/ruoyiflowable/output/computeruse-retest-20260909/signal-catch-completed-v5.png)、[事件网关完成](D:/ruoyiflowable/output/computeruse-retest-20260909/event-gateway-completed-v8.png)。

### R06 消息抛出节点不能保存

在可运行链上添加消息抛出(引用cu0909_message)及信号抛出，保存出现 BPMN_CONTRACT_INVALID，元素列空，通用提示“BPMN流程规则校验失败”，无法定位配置项。

D1仅删除消息抛出，保留信号抛出，V7保存、部署、运行完成，并成功唤醒独立信号捕获实例。

证据：[保存失败](D:/ruoyiflowable/output/computeruse-retest-20260909/throws-save-result.png)、[失败模型](D:/ruoyiflowable/output/computeruse-retest-20260909/ui-throws-failed.bpmn)、[降级完成](D:/ruoyiflowable/output/computeruse-retest-20260909/signal-throw-completed-v7.png)。

### R07 错误边界业务编码选择后丢失

给服务任务附着错误边界，连接到审批，选择“审批业务校验失败 · APPROVAL_BUSINESS_ERROR”。鼠标、键盘选择后回读仍为请选择；保存提示“错误或升级边界必须选择已启用的正式业务编码”。UI复制的 BPMN 有 ErrorEventDefinition，但没有 errorRef。

同时测试了服务任务“产生BPMN业务错误或升级”处理器：可通过业务控件配置 ERROR、APPROVAL_BUSINESS_ERROR、SERVICE_TASK、ALWAYS。
D1仅删除错误边界，保存后部署仍被“受控 BPMN 事件产生节点必须附着唯一精确匹配边界”拦住。这是正确的配对门禁，不记作第二个缺陷。
D2仅将产生错误的处理器改回设置变量处理器，保留其他结构，V3部署、发起、审批完成。

证据：[编码选择回读](D:/ruoyiflowable/output/computeruse-retest-20260909/error-code-after-click.png)、[键盘选择仍失败](D:/ruoyiflowable/output/computeruse-retest-20260909/error-code-keyboard-save.png)、[缺errorRef模型](D:/ruoyiflowable/output/computeruse-retest-20260909/ui-error-boundary-failed.bpmn)、[D1部署门禁](D:/ruoyiflowable/output/computeruse-retest-20260909/error-d1-deploy-failure.png)、[D2完成](D:/ruoyiflowable/output/computeruse-retest-20260909/error-d2-completed.png)。

### R08 升级边界业务编码选择后丢失

附着升级边界并连接到审批，选择“审批升级处理 · APPROVAL_ESCALATION”，等待、失焦后编码仍丢失，保存同样被业务编码门禁拦住。

D1仅删除升级边界，回到已部署V3基线，新发起 CU0909R-ESC-D1 并完成。净模型未变化，因此没有把沿用V3写成部署新版本。

证据：[选项](D:/ruoyiflowable/output/computeruse-retest-20260909/escalation-code-options.png)、[保存失败](D:/ruoyiflowable/output/computeruse-retest-20260909/escalation-save.png)、[回读](D:/ruoyiflowable/output/computeruse-retest-20260909/escalation-code-readback.png)、[D1完成](D:/ruoyiflowable/output/computeruse-retest-20260909/escalation-d1-completed.png)。

### R09 数据对象触发通用合同校验失败

在已通过的补偿/子流程模型加入池、泳道、数据对象、数据存储、输入/输出关联、分组、文本注释和普通关联，保存失败，BPMN_CONTRACT_INVALID，元素列空，通用错误。

逐步降级并每步保存：
1. D1仅删除数据输入关联：仍失败。
2. D2仅删除数据输出关联：仍失败。
3. D3仅删除数据存储：仍失败。
4. D4仅删除数据对象：保存、部署V7、运行完成。

为确认没有误伤其他元素，随后恢复数据存储及输出关联，保存成功；**再次只添加一个数据对象、不加输入关联，仍复现同一失败**。再次只删除该对象，V8保存、部署、运行完成。最小失败增量锁定为当前前端生成的数据对象，未证明输入关联本身有缺陷。

证据：[初始失败](D:/ruoyiflowable/output/computeruse-retest-20260909/graphics-save.png)、[D1](D:/ruoyiflowable/output/computeruse-retest-20260909/graphics-d1-save.png)、[D2](D:/ruoyiflowable/output/computeruse-retest-20260909/graphics-d2-save.png)、[D3](D:/ruoyiflowable/output/computeruse-retest-20260909/graphics-d3-save.png)、[D4完成](D:/ruoyiflowable/output/computeruse-retest-20260909/graphics-d4-completed.png)、[单对象复现](D:/ruoyiflowable/output/computeruse-retest-20260909/dataobject-alone-save-failure.png)、[仅删对象后完成](D:/ruoyiflowable/output/computeruse-retest-20260909/datastore-d1-completed.png)。

### R10 第二个池缺少发起范围配置入口

V9基线上添加第二个池，绑定key cu0909_partner，内部开始→接收任务→结束；将主服务节点改为发送任务，并画命名消息流 cu0909_collaboration 跨池连接到接收任务。

保存失败：“每个可执行流程必须配置完整发起范围”。第二池属性只显示名称、标识、绑定流程定义key、说明，没有发起范围入口。UI复制XML中的第二process没有startScope配置。消息流面板只有基本信息。

D1仅删除消息流：仍报发起范围错误。
D2仅删除第二个池（及池内依附元素），保留主流程发送任务、补偿、事务/事件/折叠子流程、数据存储等，V10保存、部署并完成。

本轮跨池测试未成功配置可靠消息outbox处理器：原发送任务仍保留设置变量处理器。因此这里证明的是保存前的发起范围配置断点，未测试到可靠消息投递阶段，不推断消息引擎失败。

证据：[池属性](D:/ruoyiflowable/output/computeruse-retest-20260909/partner-configuration-entry.png)、[完整错误](D:/ruoyiflowable/output/computeruse-retest-20260909/collaboration-startscope-error.png)、[D1仍失败](D:/ruoyiflowable/output/computeruse-retest-20260909/collaboration-d1-save.png)、[D2已部署](D:/ruoyiflowable/output/computeruse-retest-20260909/collaboration-d2-deployed.png)、[D2完成](D:/ruoyiflowable/output/computeruse-retest-20260909/collaboration-d2-completed.png)、[失败模型](D:/ruoyiflowable/output/computeruse-retest-20260909/ui-collaboration-failed.bpmn)。

### R11 正式模板选中后仍无法形成发起表单

多次进入/保存设计器出现“表单组件必须声明完整节点权限标志”。最终再次隔离验证：V10开始节点将内嵌来源切为正式模板，选择唯一选项qweqw，等待并失焦，名称保留但显示0字段、“绑定正式表单后配置字段权限”；保存仍提示需要发起表单。

D1仅将表单来源切回内嵌。来源切换会重置字段，因此通过UI恢复原probe_input/测试说明，其他流程结构保留。V11保存、部署、正式填表、审批完成。保存成功后仍可看到模板权限错误toast，不能把该toast当作这次模型未保存；也不能据此断言所有正式模板都失败，当前只测试到现有qweqw模板。

证据：[选项](D:/ruoyiflowable/output/computeruse-retest-20260909/formal-final-options.png)、[选择后0字段](D:/ruoyiflowable/output/computeruse-retest-20260909/formal-final-selection.png)、[保存失败](D:/ruoyiflowable/output/computeruse-retest-20260909/formal-final-save-failure.png)、[切回后保存](D:/ruoyiflowable/output/computeruse-retest-20260909/formal-d1-saved.png)、[V11部署](D:/ruoyiflowable/output/computeruse-retest-20260909/formal-d1-deployed.png)、[最终完成](D:/ruoyiflowable/output/computeruse-retest-20260909/formal-d1-completed.png)。

### R12 扩展注册表页面报数据库映射异常

流程管理→扩展流程管理→扩展注册表，页面直接显示MyBatis/ReflectionException、WorkflowExtensionManagementView构造参数不匹配及NoSuchMethodException；“已安装处理器”弹窗为空。

此为管理页面可用性失败，没有通过修改源代码或数据库规避。降级使用设计器中现有可选“设置流程变量”处理器，服务/发送任务可执行。设计器存在6个处理器选项，不能把管理弹窗空解读为后端完全没有处理器。

证据：[注册表错误](D:/ruoyiflowable/output/computeruse-retest-20260909/handler-registry.png)、[处理器弹窗](D:/ruoyiflowable/output/computeruse-retest-20260909/installed-handlers.png)、[服务任务完成](D:/ruoyiflowable/output/computeruse-retest-20260909/service-completed.png)。

另有正确的配置门禁：设置变量处理器默认{}可以保存，但部署提示必须且只能包含targetVariable和value。在公开JSON输入框填写这两个字段并失焦后，部署及运行通过。这是修正测试配置，不作为产品失败降级。

## 高级功能成功证据

高级模型key=`cu0909_advanced`：
- V1：设置变量服务任务→审批→结束。
- V2：增加信号抛出，唤醒另一模型事件网关。
- V3：错误边界降级回归；升级边界删除后沿用V3回归。
- V4：增加事务子流程，正常路径完成。[事务完成](D:/ruoyiflowable/output/computeruse-retest-20260909/transaction-completed.png)
- V5：中断型信号事件子流程被主流程信号激活，主普通路径中断、事件子流程结束，实例完成。[中断事件子流程完成](D:/ruoyiflowable/output/computeruse-retest-20260909/event-subprocess-completed.png)
- V6：改为非中断型事件子流程；服务附补偿边界，通过补偿关联建立补偿服务，并增加补偿抛出。事件子流程、补偿服务、事务及用户审批均执行后完成。[补偿完成](D:/ruoyiflowable/output/computeruse-retest-20260909/compensation-completed.png)
- V7/V8：图形元素逐步降级及数据对象隔离，分别完成。
- V9：增加折叠子流程，内部开始→结束，完成。[折叠完成](D:/ruoyiflowable/output/computeruse-retest-20260909/collapsed-subprocess-completed.png)
- V10/V11：跨池及正式模板最小降级后，保留上述可运行能力，再次完成。

节点覆盖模型key=`cu0909_elements`：发送任务、接收/消息/信号等待、信号抛出、事件网关在各版本中逐步验证。错误、升级边界的失败没有被这些成功路径覆盖掉。

## 20个有效完成实例

以下时间取前端详情；过程耗时含人工操作和观察，不是性能指标。日期09-09，跨零点项标记09-10。

| 模型/版本 | 业务主键 | 实例ID | 开始→结束 |
|---|---|---|---|
| retest V2 | CU0909R-C-500-D1 | 28b7711f-ac5e-11f1-8ac1-005056c00001 | 22:53:03→22:55:55 |
| retest V2 | CU0909R-D-3000-D1 | a455b71c-ac5e-11f1-8ac1-005056c00001 | 22:56:30→22:58:56 |
| retest V3 | CU0909R-E-DEFAULT-V3 | d2c17611-ac6e-11f1-8ac1-005056c00001 | 09-10 00:52:20→01:03:30 |
| advanced V1 | CU0909R-SERVICE-1 | 741950ff-ac60-11f1-8ac1-005056c00001 | 23:09:28→23:09:46 |
| advanced V2 | CU0909R-EMITTER-V2 | bfee96b7-ac63-11f1-8ac1-005056c00001 | 23:33:04→23:34:01 |
| advanced V3 | CU0909R-ERROR-D2-V3 | b2c8e274-ac65-11f1-8ac1-005056c00001 | 23:47:01→23:47:18 |
| advanced V3 | CU0909R-ESC-D1 | 0baec22f-ac66-11f1-8ac1-005056c00001 | 23:49:30→23:49:47 |
| advanced V4 | CU0909R-TRANSACTION-V4 | 5568820a-ac66-11f1-8ac1-005056c00001 | 23:51:34→23:51:52 |
| advanced V5 | CU0909R-EVENTSUB-V5 | b016509d-ac66-11f1-8ac1-005056c00001 | 23:54:06→23:54:06 |
| advanced V6 | CU0909R-COMP-V6 | 423319c4-ac67-11f1-8ac1-005056c00001 | 23:58:11→23:58:24 |
| advanced V7 | CU0909R-GRAPHICS-D4-V7 | 1df7ef77-ac68-11f1-8ac1-005056c00001 | 09-10 00:04:20→00:04:33 |
| advanced V8 | CU0909R-DATAOBJECT-D1-V8 | 93f26aaa-ac68-11f1-8ac1-005056c00001 | 09-10 00:07:38→00:07:53 |
| advanced V9 | CU0909R-COLLAPSED-V9 | dca9398d-ac68-11f1-8ac1-005056c00001 | 09-10 00:09:40→00:09:58 |
| advanced V10 | CU0909R-COLLAB-D2-V10 | 309d3398-ac6c-11f1-8ac1-005056c00001 | 09-10 00:33:29→00:33:53 |
| advanced V11 | CU0909R-FORM-D1-V11 | fcb100f3-ac6c-11f1-8ac1-005056c00001 | 09-10 00:39:12→00:39:45 |
| elements V3 | CU0909R-SEND-D1-V3 | d34c3d44-ac61-11f1-8ac1-005056c00001 | 23:19:18→23:19:37 |
| elements V5 | CU0909R-SIGNAL-V5-D1 | 768b502c-ac62-11f1-8ac1-005056c00001 | 23:23:52→23:27:45 |
| elements V6 | CU0909R-CATCH-D2-V6 | 9ff6f38b-ac62-11f1-8ac1-005056c00001 | 23:25:01→23:25:16 |
| elements V7 | CU0909R-THROW-V7-D1 | f70665a4-ac62-11f1-8ac1-005056c00001 | 23:27:27→23:28:01 |
| elements V8 | CU0909R-EVENTGW-V8-A | 88b44082-ac63-11f1-8ac1-005056c00001 | 23:31:32→23:33:24 |

## 留存、清理与限制

保留3个原始等待实例，供修复后复现：
- 定时器：CU0909R-B-500，859fbc04-ac5d-11f1-8ac1-005056c00001，retest V1。
- 接收任务：CU0909R-RECEIVE-1，5019d008-ac61-11f1-8ac1-005056c00001，elements V1。
- 消息捕获：CU0909R-MESSAGE-V4，54cefe9d-ac62-11f1-8ac1-005056c00001，elements V4。

本轮产生的模型/版本和已完成实例保留。当前最新部署：retest V3（税务阈值为5000，分支名称仍沿用原名，作为默认出口测试模型留存）、advanced V11、elements V8。不是对业务正式模型的发布。

测试操作失误单列：
- CU0909R-A-500误选旧cu0909_procurement，已通过“我的流程→取消”清理。[已取消](D:/ruoyiflowable/output/computeruse-retest-20260909/cleanup-a-cancelled.png)
- CU0909R-SEND-D1为第一次删除接收任务未生效的V2重复等待实例，已取消；V3才是实际降级验证。[已取消](D:/ruoyiflowable/output/computeruse-retest-20260909/cleanup-receive-duplicate-cancelled.png)
- CU0909R-EVENTGW-V8（647a43be-ac63-11f1-8ac1-005056c00001）实际启动的是V7，虽办理完成，不计入事件网关覆盖或20个有效实例；真正V8实例为带-A的业务主键。
- 部分点击因窗口焦点、模态框关闭、属性面板滚动或异步列表更新未生效，均回读画面后纠正，不作为产品缺陷。

本轮没有修改生产/测试源代码，没有暂存、提交或推送。测试开始时已有的12个修改/未跟踪文件均做SHA256回读，全部保持不变：[基线核对](D:/ruoyiflowable/output/computeruse-retest-20260909/baseline-verification.json)。新增内容为本报告和output下的测试证据。未以编译或单元测试代替本轮前端验收。

建议下一轮优先按R07/R08业务编码、R09数据对象、R10第二池发起范围、R11正式模板、R12管理页异常处理，再准备可调用流程/DMN和受控消息触发条件，复测R02/R03/R04/R05/R06及开启正式调度条件后的R01。每项应保留原节点重新完成保存→部署→发起→运行，不能只验降级模型。
