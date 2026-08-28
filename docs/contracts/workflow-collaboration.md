# Participant、MessageFlow 与多池协作运行契约

ApprovaPlat 将协作图作为跨流程编排声明，并通过 Participant、MessageFlow、事务 outbox、消息 API 和正式台账共同实现运行能力。

## 支持边界

- Participant 绑定一个存在且 `isExecutable=true` 的流程定义，同一协作图中的池标识保持唯一。
- MessageFlow 使用唯一消息名称，可执行起点为绑定 `COLLABORATION_OUTBOX_V1` 的 `SendTask`，终点为 `ReceiveTask` 或消息捕获事件，且两端属于不同 Participant。消息抛出事件保留标准 BPMN 表达；事务 outbox 绑定完成后获得部署运行资格。
- 消息名称与 Flowable Message Catch 订阅一致；部署前服务端解析模型并调用 Flowable 官方校验器，全部校验通过后进入部署事务。
- Lane、Group、Annotation、Association 承担建模和说明职责；正式网关入口提供排他、并行、包容和事件网关。

## 运行语义

SendTask 在 Flowable 当前事务内登记 `wf_collaboration_outbox` 后完成。后台 worker 使用冻结的 HTTP 端点、网络范围和外部环境密钥投递到 `POST /workflow/runtime-event/collaboration/message`；接收端凭据需要 `MESSAGE` 范围，变量同时受发送节点和接收凭据两层标量白名单限制。

- `messageId` 是发送部署、实例、execution 和活动确定的小写 UUID，也是两端幂等主键；相同载荷重放返回首次结果，不同载荷返回冲突。
- `targetProcessDefinitionKey` 加 `correlationKey`（业务键）或 `targetProcessInstanceId`（实例键）唯一确定接收流程实例；匹配数量等于一时进入消费事务，其余数量返回稳定冲突。
- 服务在同一 Flowable 事务登记 `wf_collaboration_message`，唯一消费消息订阅并写入目标实例/执行主键。消息消费和台账更新原子提交或整体回滚。
- 出站和入站失败都按 `maxAttempts`（1～20，默认 5）进行自动有界指数退避；worker 使用数据库租约支持多节点接管，超过上限进入 `DEAD_LETTER`，后续只能由具备权限的管理员重新开启有界周期。
- `wf_collaboration_channel` 为同一目标流程和关联键分配连续序号。出站 worker 只有在所有前序消息完成或明确取消后才领取后序；接收端只消费当前期望序号，重复消息幂等返回，乱序消息持久化等待前序完成。
- 每次登记、领取、成功、重试、死信、补偿和取消都写入 `wf_collaboration_message_audit`，并发布固定标签集合的低基数指标。

跨系统认证、权限隔离、变量白名单、幂等、状态和死信信息都落在正式数据库结构；通过上述全部门禁的元素标记为可执行。
