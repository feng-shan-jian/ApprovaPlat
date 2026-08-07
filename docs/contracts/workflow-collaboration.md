# Participant、MessageFlow 与多池协作运行契约

ApprovaPlat 将协作图视为跨流程编排的声明，不把 Participant 或 MessageFlow 图形/XML 往返当作运行能力。

## 支持边界

- Participant 必须绑定一个存在且 `isExecutable=true` 的流程定义；同一协作图中不能重复声明池标识。
- MessageFlow 必须有唯一消息名称，当前可执行起点为绑定 `COLLABORATION_OUTBOX_V1` 的 `SendTask`，终点为 `ReceiveTask` 或消息捕获事件，且两端属于不同 Participant。消息抛出事件只保留标准 BPMN 表达，不在没有事务 outbox 的情况下标记为可部署运行。
- 消息名称必须与 Flowable Message Catch 订阅一致；部署前服务端解析并调用 Flowable 官方校验器，失败不得部署。
- Lane、Group、Annotation、Association 仅承担建模和说明职责；ComplexGateway 不进入正式工具入口。

## 运行语义

SendTask 在 Flowable 当前事务内登记 `wf_collaboration_outbox` 后完成，不直接执行网络调用。后台 worker 使用冻结的 HTTP 端点、网络范围和外部环境密钥投递到 `POST /workflow/runtime-event/collaboration/message`；接收端凭据需要 `MESSAGE` 范围，变量同时受发送节点和接收凭据两层标量白名单限制。

- `messageId` 是发送部署、实例、execution 和活动确定的小写 UUID，也是两端幂等主键；相同载荷重放返回首次结果，不同载荷返回冲突。
- `targetProcessDefinitionKey` 加 `correlationKey`（业务键）或 `targetProcessInstanceId`（实例键）唯一确定接收流程实例；零个或多个匹配均拒绝。
- 服务在同一 Flowable 事务登记 `wf_collaboration_message`，唯一消费消息订阅并写入目标实例/执行主键。消息消费和台账更新不能出现单边成功。
- 出站和入站失败都按 `maxAttempts`（1～20，默认 5）进行自动有界指数退避；worker 使用数据库租约支持多节点接管，超过上限进入 `DEAD_LETTER`，后续只能由具备权限的管理员重新开启有界周期。
- `wf_collaboration_channel` 为同一目标流程和关联键分配连续序号。出站 worker 只有在所有前序消息完成或明确取消后才领取后序；接收端只消费当前期望序号，重复消息幂等返回，乱序消息持久化等待前序完成。
- 每次登记、领取、成功、重试、死信、补偿和取消都写入 `wf_collaboration_message_audit`，并发布不含消息名、实例或关联键标签的低基数指标。

跨系统认证、权限隔离、变量白名单、幂等、状态和死信信息都落在正式数据库结构；任何仅能绘制或导出 XML 而不能通过上述门禁的元素均不可标记为可执行。
