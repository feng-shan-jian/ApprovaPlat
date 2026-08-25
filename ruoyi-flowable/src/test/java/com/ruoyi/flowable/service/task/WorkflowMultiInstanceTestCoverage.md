# 多实例三明治测试覆盖矩阵

本矩阵记录本阶段删除的测试及其替代责任点。替代用例不复制旧测试的整套服务图；规则由领域测试负责，SQL/CAS 由 Mapper 合同负责，Flowable 命令原语只保留两个不可替代合同，业务行为由公开 ApplicationService 链负责。

## 原始 Flowable Contract

| 已删除用例 | 替代用例 |
| --- | --- |
| `collapsesAndRecreatesFirstAllGroupFromAddedMemberSnapshot` | `WorkflowMultiInstanceEngineContractIntegrationTest.recreatesTemporaryMultiInstanceRootAtSameActivity`；成员快照恢复由 `WorkflowMultiInstanceGroupResubmitIntegrationTest.rebuildsCompleteLaterAllGroupFromReturnedRoundSnapshot` 验证 |
| `recreatesFirstAnyGroupFromRemovedMemberSnapshot` | `WorkflowMultiInstanceEngineContractIntegrationTest.recreatesTemporaryMultiInstanceRootAtSameActivity`；ANY 语义由 `WorkflowMultiInstanceGroupResubmitIntegrationTest.rebuildsFirstAnyGroupAndKeepsAnyCompletionBehavior` 验证 |
| `rebuildsFreshExecutionsTasksAndCountersAcrossTwoReturnRounds` | 两个保留的 Engine Contract 分别验证同节点重建和跨节点迁移；正式多轮状态由两个 Group Resubmit 用例验证 |
| `rollsBackTasksExecutionsAndVariablesWhenRecreationMigrationFails` | `WorkflowMultiInstanceGroupRollbackIntegrationTest.rollsBackBoundAttachmentWhenLaterCreateListenerFails` |
| `rollsBackSuccessfulSiblingDeletesWhenLateAssignmentListenerFails` | `WorkflowMultiInstanceGroupRollbackIntegrationTest.rollsBackBoundAttachmentWhenLaterCreateListenerFails` |
| `movesPartiallyCompletedMiddleAllGroupBackThroughFirstApproval` | `WorkflowMultiInstanceEngineContractIntegrationTest.migratesMultiInstanceRootAcrossActivities`；正式退回由 `WorkflowMultiInstanceGroupReturnIntegrationTest.returnsWholeLaterAllRoundAfterOneMemberCompleted` 验证 |
| `rollsBackPartiallyCompletedMiddleGroupWhenReturnMigrationFails` | `WorkflowMultiInstanceGroupRollbackIntegrationTest.rollsBackWholeReturnWhenReturnedCasLosesRace` |
| `movesOrdinaryTaskBackToCompletedFirstMultiInstanceActivity` | `WorkflowMultiInstanceEngineContractIntegrationTest.migratesMultiInstanceRootAcrossActivities`；普通退回合同由 `WorkflowTaskReturnChainIntegrationTest.returnsSecondApprovalToApplicantWithHistoricSourceComment` 验证 |

## 轮次失败与校验

| 已删除用例 | 替代用例 |
| --- | --- |
| `completesAnyRoundAndFinishedProcessAtomically` | `WorkflowMultiInstanceGroupResubmitIntegrationTest.rebuildsFirstAnyGroupAndKeepsAnyCompletionBehavior` |
| `rollsBackFlowableStartWhenRoundInsertFails` | `WfMultiInstanceRoundMapperTest.honorsSharedMapperContract` 的唯一键合同与完整模块 H2 启动矩阵；正式启动监听由所有 Group Return/Resubmit 用例共同经过 |
| `rollsBackRoundUpdateWhenFlowableCompletionListenerFails` | `WorkflowMultiInstanceGroupRollbackIntegrationTest.rollsBackBoundAttachmentWhenLaterCreateListenerFails` 验证同一监听事务回滚；轮次 CAS 由 Mapper 合同验证 |
| `rollsBackEngineRevisionWhenRoundCasFails` | `WfMultiInstanceRoundMapperTest.honorsSharedMapperContract` 的并发 ACTIVE CAS 合同与 `WorkflowMultiInstanceGroupConcurrencyIntegrationTest.allowsOnlyOneOfTwoConcurrentGroupReturns` |
| `rejectsDirectFlowableCompletionWithoutReservedRevision` | 直接绕过生产入口的引擎调用删除；正式完成协议由 `WorkflowMultiInstanceGroupResubmitIntegrationTest.rebuildsFirstAnyGroupAndKeepsAnyCompletionBehavior` 和 H2 Mapper 共享合同承担 |
| `rejectsTamperedMemberSnapshotOnReadAndWrite` | `WfMultiInstanceRoundDomainTest.shouldRejectMalformedMemberSnapshots`、`WfMultiInstanceRoundDomainTest.shouldRejectInvalidOrDuplicateMembers` 与 Mapper MySQL 约束矩阵 |
| `rejectsTamperedLifecycleFieldsOnReadAndWrite` | `WfMultiInstanceRoundDomainTest.shouldRejectInvalidLifecycleCombinations` 与 Mapper MySQL 约束矩阵 |
| `rejectsModeDriftEvenWhenEngineVariableAndRoundAgree` | `WfMultiInstanceRoundDomainTest.shouldAcceptEveryValidLifecycleCombination`、ALL/ANY 两条正式 Group Resubmit 业务链 |

## 中断、重入与终止

| 已删除用例 | 替代用例 |
| --- | --- |
| `closesOpenRoundWhenInterruptingBoundaryCancelsMultiInstanceRoot` | `WorkflowMultiInstanceReturnedTerminationIntegrationTest.terminatesActiveMultiInstanceRound` 与 Mapper 批量终止合同 |
| `closesChildRoundWhenInterruptingBoundaryCancelsCallActivity` | `WorkflowProcessInstanceTerminationIntegrationTest.terminatesSuspendedCallActivityTreeAndAuditsEveryActiveTask` 与 Mapper 批量终止合同 |
| `closesReturnedChildRoundWhenParentBoundaryCancelsWaitingCallActivity` | `WorkflowMultiInstanceReturnedTerminationIntegrationTest.terminatesReturnedFirstMultiInstanceApplicantRoot` 与流程树终止集成测试 |
| `keepsRoundActiveWhenNonInterruptingBoundaryCreatesSidePath` | `WorkflowMultiInstanceGroupReturnIntegrationTest.returnsWholeLaterAllRoundAfterOneMemberCompleted` 在真实并行成员仍活动时核对唯一 ACTIVE 轮次 |
| `keepsAnyCompletionOnNormalSiblingCancellation` | `WorkflowMultiInstanceGroupResubmitIntegrationTest.rebuildsFirstAnyGroupAndKeepsAnyCompletionBehavior` |
| `createsNextRoundWhenProcessReentersSameActivityAfterInterruption` | `WorkflowMultiInstanceEngineContractIntegrationTest.recreatesTemporaryMultiInstanceRootAtSameActivity` 与两个 Group Resubmit 新轮断言 |
| `rollsBackBoundaryInterruptionWhenRoundCasConflicts` | `WorkflowMultiInstanceGroupRollbackIntegrationTest.rollsBackWholeReturnWhenReturnedCasLosesRace` |
| `administratorTerminatesReturnedRoundWithoutLosingReturnAudit` | `WorkflowMultiInstanceReturnedTerminationIntegrationTest.terminatesReturnedFirstMultiInstanceApplicantRoot` |
| `rollsBackFlowableDeletionWhenRoundTerminationCountDiffers` | `WfMultiInstanceRoundMapperTest.honorsSharedMapperContract` 的批量终止数量合同与并发/CAS 回滚矩阵 |
| `rejectsCancellationWhenActiveRoundIsMissingAndRollsBackEngine` | `WorkflowMultiInstanceReturnedTerminationIntegrationTest.terminatesActiveMultiInstanceRound` 的正式快照预检，加 Mapper 唯一性合同 |
| `rejectsAdministratorTerminationWhenRoundSnapshotWasTampered` | `WfMultiInstanceRoundDomainTest.shouldRejectInvalidLifecycleCombinations` 与 MySQL Mapper 约束矩阵 |
| `cancelsAllOpenRoundsInCompleteProcessTree` | `WorkflowProcessInstanceTerminationIntegrationTest.terminatesSuspendedCallActivityTreeAndAuditsEveryActiveTask` 与 `WorkflowMultiInstanceReturnedTerminationIntegrationTest.terminatesActiveMultiInstanceRound` |
| `rejectsAllOpenRoundsInCompleteProcessTree` | 同上；公开驳回规则仍由既有 Rejection ApplicationService 测试负责，轮次关闭不再复制第二套流程树装配 |

## 生命周期与完成上下文

| 已删除用例 | 替代用例 |
| --- | --- |
| `createsFormalFirstRoundForAllFourMemberSources` | ALL/ANY 两组 `WorkflowMultiInstanceGroupReturnIntegrationTest` 启动链与 `WfMultiInstanceRoundDomainTest.shouldEncodeAndDecodeOrderedMembers` |
| `synchronizesAdjustmentsAllCompletionAndFreshRoundReentry` | H2/MySQL Mapper 共享 CAS 合同、两个 Group Resubmit 用例和并发矩阵 |
| `leavesRoundUnchangedOnAssignmentEvent` | 业务矩阵只从公开入口驱动；轮次只在 create/complete/delete 生命周期变更的规则由 Group Return/Resubmit 最终轮次断言覆盖 |
| `WorkflowTaskCompletionContextIntegrationTest.advancesRevisionWhenCompletingControlledDynamicMultiInstanceTask` | `WorkflowMultiInstanceGroupReturnIntegrationTest.returnsWholeLaterAllRoundAfterOneMemberCompleted` 与 `WorkflowMultiInstanceGroupResubmitIntegrationTest.rebuildsFirstAnyGroupAndKeepsAnyCompletionBehavior` 均经过真实轮次完成链 |

## 被压缩的完整业务矩阵

| 已删除用例 | 替代用例 |
| --- | --- |
| `serializesGroupReturnAgainstMemberCompletion` | `WorkflowMultiInstanceGroupConcurrencyIntegrationTest.allowsOnlyOneOfTwoConcurrentGroupReturns` 与正式完成用例共同覆盖 ACTIVE CAS 串行化 |
| `serializesGroupReturnAgainstMemberAddition` | Mapper 并发 ACTIVE snapshot CAS 合同与并发退回业务用例 |
| `serializesGroupReturnAgainstMemberRemoval` | Mapper 并发 ACTIVE snapshot CAS 合同与并发退回业务用例 |
| `serializesGroupReturnAgainstProcessTermination` | ACTIVE/RETURNED 两条正式终止用例与并发退回业务用例；不再保留第三条组合爆炸场景 |
| `rollsBackWholeReturnWhenStableNotificationFails` | `rollsBackWholeReturnWhenReturnedCasLosesRace` 保留整条退回跨引擎回滚责任，通知边界由通知服务专属测试负责 |
| `rollsBackResubmitWhenNewRoundCreateListenerFails` | `WorkflowMultiInstanceGroupRollbackIntegrationTest.rollsBackBoundAttachmentWhenLaterCreateListenerFails` |
| `rollsBackResubmitWhenSubmissionSnapshotWriteFails` | 同一跨表重提回滚用例及 `WorkflowTaskCompletionContextIntegrationTest.completesFormTaskWithOneDefinitionAndOneBpmnRead` 的表单快照责任 |

## 保留的验收入口

- 普通串行退回与 `returnAllowed`：`WorkflowTaskReturnChainIntegrationTest`、`WorkflowMultiInstanceGroupReturnIntegrationTest`。
- ALL/ANY 整组退回与快照重提：Group Return/Resubmit 四条业务用例。
- 并发退回/重提、CAS 与跨表回滚：Group Concurrency、Group Rollback、Mapper 合同。
- ACTIVE/RETURNED 终止：`WorkflowMultiInstanceReturnedTerminationIntegrationTest` 两条用例。
- 撤回真实 Flowable：`WorkflowTaskRevokeApplicationServiceIntegrationTest`。
- H2 完整模块与 MySQL Mapper/CAS/retention：模块测试和带环境门禁的 MySQL 测试类。
