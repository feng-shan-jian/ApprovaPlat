# 多实例流程测试覆盖矩阵

| 覆盖层 | 当前有效测试 | 覆盖场景 | 执行入口 |
| --- | --- | --- | --- |
| 领域规则 | `WfMultiInstanceRoundDomainTest` | 成员快照编解码、用户主键与去重、revision 边界、轮次状态与生命周期组合 | `mvn clean verify` |
| Mapper 公共契约 | `WfMultiInstanceRoundMapperTest` + `WfMultiInstanceRoundMapperContract` | 轮次创建、查询、ACTIVE CAS、退回、重开、唯一键、并发单写者、终止与历史删除；H2 执行数据库中立契约 | `mvn clean verify` |
| MySQL 物理契约 | `WfMultiInstanceRoundMapperMySqlIT` | 在真实 MySQL 8 上复用 Mapper 公共契约，并验证 InnoDB、生成列、排序规则、JSON/CHECK 和真实并发 | `mvn -pl ruoyi-flowable -am -Pworkflow-mysql-it verify` |
| Flowable 引擎契约 | `WorkflowMultiInstanceEngineContractIntegrationTest` | 同节点临时多实例根重建、跨节点多实例执行树迁移 | `mvn clean verify` |
| 整组退回 | `WorkflowMultiInstanceGroupReturnIntegrationTest`、`WorkflowMultiInstanceContinuousReturnIntegrationTest` | ALL/ANY 整组退回、普通节点与多实例节点连续退回、首节点重建、已完成兄弟任务处理 | `mvn clean verify` |
| 整组重提 | `WorkflowMultiInstanceGroupResubmitIntegrationTest` | 先恢复普通首审批再重入 ALL、首个 ANY 组重建及 ANY 完成语义 | `mvn clean verify` |
| 并发 | `WorkflowMultiInstanceGroupConcurrencyIntegrationTest` | 两个线程并发整组退回或重提时仅一个提交成功 | `mvn clean verify` |
| 跨表回滚 | `WorkflowMultiInstanceGroupRollbackIntegrationTest` | 退回 CAS 竞争失败时回滚引擎和变量；新轮监听失败时回滚 Flowable、轮次和附件绑定 | `mvn clean verify` |
| 终止 | `WorkflowMultiInstanceReturnedTerminationIntegrationTest` | 活动多实例轮次终止、已退回首节点申请根终止 | `mvn clean verify` |
| 撤回 | `WorkflowTaskRevokeApplicationServiceIntegrationTest` | 未办理后继撤回、操作者与 BPMN 安全校验、后继并发变化后的事务回滚、只读能力判断 | `mvn clean verify` |
| 退回链兼容 | `WorkflowTaskReturnChainIntegrationTest` | 普通审批退回申请人、重提恢复分配、并行退回失败无部分副作用、业务/流程状态失败关闭 | `mvn clean verify` |
| 保留清理 | `WorkflowMultiInstanceRoundRetentionCleanerTest`、`WorkflowMultiInstanceRoundRetentionMySqlIT` | 精确领取与删除计数、过期已结束实例清理、近期及运行实例保留、真实 MySQL 无孤儿校验 | 普通测试：`mvn clean verify`；MySQL：`workflow-mysql-it` profile |
