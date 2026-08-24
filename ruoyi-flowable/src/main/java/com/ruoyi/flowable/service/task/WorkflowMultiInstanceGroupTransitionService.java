package com.ruoyi.flowable.service.task;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WorkflowMultiInstanceRoundStatus;

/** 受控多实例 Flowable 迁移、轮次 CAS、SLA 和执行树对账的唯一边界。 */
@Service
public class WorkflowMultiInstanceGroupTransitionService
{
    private final WorkflowMultiInstanceRoundRepository roundRepository;
    private final WorkflowMultiInstanceRuntimeSnapshotReader snapshotReader;
    private final WorkflowMultiInstanceRoundLifecycleService roundLifecycleService;
    private final WorkflowMultiInstanceTransitionCoordinator transitionCoordinator;
    private final WorkflowTaskRuntimeReader runtimeReader;
    private final RuntimeService runtimeService;
    private final WorkflowTaskSlaRuntimeService taskSlaRuntimeService;

    /**
     * 创建整组迁移服务。
     * @param roundRepository WorkflowMultiInstanceRoundRepository，轮次持久化边界
     * @param snapshotReader WorkflowMultiInstanceRuntimeSnapshotReader，实时快照读取器
     * @param roundLifecycleService WorkflowMultiInstanceRoundLifecycleService，ACTIVE 轮次读取服务
     * @param transitionCoordinator WorkflowMultiInstanceTransitionCoordinator，单命令迁移协议协调器
     * @param runtimeReader WorkflowTaskRuntimeReader，任务和实例公共事实读取器
     * @param runtimeService RuntimeService，Flowable 执行树迁移和结构查询服务
     * @param taskSlaRuntimeService WorkflowTaskSlaRuntimeService，撤销任务 SLA 收口服务
     * @return 无返回值，构造后由 Spring 管理
     */
    public WorkflowMultiInstanceGroupTransitionService(
            WorkflowMultiInstanceRoundRepository roundRepository,
            WorkflowMultiInstanceRuntimeSnapshotReader snapshotReader,
            WorkflowMultiInstanceRoundLifecycleService roundLifecycleService,
            WorkflowMultiInstanceTransitionCoordinator transitionCoordinator,
            WorkflowTaskRuntimeReader runtimeReader, RuntimeService runtimeService,
            WorkflowTaskSlaRuntimeService taskSlaRuntimeService)
    {
        this.roundRepository = roundRepository;
        this.snapshotReader = snapshotReader;
        this.roundLifecycleService = roundLifecycleService;
        this.transitionCoordinator = transitionCoordinator;
        this.runtimeReader = runtimeReader;
        this.runtimeService = runtimeService;
        this.taskSlaRuntimeService = taskSlaRuntimeService;
    }

    /**
     * 为整组退回冻结实时根、活动任务、成员、模式、revision 和唯一 ACTIVE 轮次。
     * @param sourceTaskId String，已通过应用服务活动态校验的任务主键
     * @param actorUserId String，已通过应用服务核验的正式办理人主键
     * @return MultiInstanceGroupReturnPlan，受控多实例计划；普通任务返回 null
     */
    public MultiInstanceGroupReturnPlan prepareGroupReturn(String sourceTaskId,
            String actorUserId)
    {
        ControlledMultiInstanceSnapshot runtime = roundData(() ->
                snapshotReader.readTask(sourceTaskId));
        if (runtime == null)
        {
            return null;
        }
        if (!actorUserId.equals(runtime.sourceTask().assignee())
                || runtime.sourceTask().owner() != null
                || runtime.sourceTask().delegated())
        {
            throw transitionConflict("多实例活动任务已经发生变化，请刷新后重试");
        }
        ProcessInstance instance = runtimeReader.requireActiveProcessInstance(
                runtime.processInstanceId());
        if (!StringUtils.hasText(instance.getStartUserId())
                || StringUtils.hasText(instance.getSuperExecutionId())
                || (StringUtils.hasText(instance.getRootProcessInstanceId())
                        && !instance.getId().equals(instance.getRootProcessInstanceId()))
                || !runtimeService.createProcessInstanceQuery()
                        .superProcessInstanceId(instance.getId()).listPage(0, 1).isEmpty())
        {
            throw transitionConflict("当前流程结构不支持多实例整组退回");
        }
        requireNormalActiveRoot(runtime);
        if (runtime.activeTasks().stream().anyMatch(task -> task.owner() != null
                || task.delegated()))
        {
            throw transitionConflict("多实例活动任务已经发生变化，请刷新后重试");
        }
        List<String> activeActivityIds = runtimeService.getActiveActivityIds(
                runtime.processInstanceId());
        if (activeActivityIds == null || activeActivityIds.isEmpty()
                || activeActivityIds.stream().anyMatch(
                        activityId -> !runtime.activityId().equals(activityId)))
        {
            throw transitionConflict("当前存在组外并行活动，不能执行多实例整组退回");
        }
        return new MultiInstanceGroupReturnPlan(
                roundLifecycleService.requireActiveRound(runtime), runtime,
                instance.getStartUserId());
    }

    /**
     * 在 Coordinator 协议中执行 Flowable 整组迁移并关闭旧任务 SLA。
     * @param plan MultiInstanceGroupReturnPlan，写前冻结计划
     * @param targetActivityId String，服务端确定的首审批节点
     * @param actorUserId String，已核验真实办理人主键
     * @return GroupReturnMigration，新申请人任务和迁移观察结果
     */
    public GroupReturnMigration migrateReturn(MultiInstanceGroupReturnPlan plan,
            String targetActivityId, String actorUserId)
    {
        requireReturnCommand(plan, targetActivityId, actorUserId);
        try (WorkflowMultiInstanceTransitionScope scope =
                transitionCoordinator.beginReturn(plan, actorUserId,
                        targetActivityId))
        {
            runtimeService.createChangeActivityStateBuilder()
                    .processInstanceId(plan.round().processInstanceId())
                    .moveExecutionToActivityId(plan.runtime().rootExecutionId(),
                            targetActivityId).changeState();
            Task applicantTask = runtimeReader.requireSingleActiveTask(
                    plan.round().processInstanceId(), targetActivityId);
            if (plan.activeTaskIds().contains(applicantTask.getId()))
            {
                throw conflict();
            }
            taskSlaRuntimeService.completeControlledWithdrawal(
                    plan.round().processInstanceId(), plan.activeTaskIds(), actorUserId,
                    WorkflowTaskSlaRuntimeService.ControlledWithdrawal.GROUP_RETURN);
            MultiInstanceTransitionResult observed =
                    transitionCoordinator.requireReturnCompleted(scope,
                            applicantTask.getId(),
                            plan.round().activityId().equals(targetActivityId));
            if (!observed.cancellationObserved())
            {
                throw dataError();
            }
            return new GroupReturnMigration(applicantTask.getId(), targetActivityId);
        }
    }

    /**
     * 在申请人状态写入后完成 ACTIVE→RETURNED CAS 及轮次对账。
     * @param plan MultiInstanceGroupReturnPlan，写前冻结计划
     * @param migration GroupReturnMigration，真实 Flowable 迁移结果
     * @param sourceTaskId String，触发整组退回的来源任务主键
     * @param actorUserId String，真实办理人主键
     * @return GroupReturnResult，RETURNED 轮次和申请人任务主键
     */
    public GroupReturnResult completeReturn(MultiInstanceGroupReturnPlan plan,
            GroupReturnMigration migration, String sourceTaskId, String actorUserId)
    {
        if (plan == null || migration == null
                || !plan.runtime().sourceTaskId().equals(sourceTaskId)
                || !actorUserId.equals(plan.runtime().sourceTask().assignee()))
        {
            throw dataError();
        }
        roundRepository.compareAndSetReturned(plan.round(), sourceTaskId,
                actorUserId, migration.applicantTaskId());
        MultiInstanceRoundSnapshot returned = roundRepository.findByRootExecutionId(
                plan.round().rootExecutionId());
        if (returned.status() != WorkflowMultiInstanceRoundStatus.RETURNED
                || !Objects.equals(returned.returnSourceTaskId(), sourceTaskId)
                || !Objects.equals(returned.returnActorUserId(), actorUserId)
                || !Objects.equals(returned.applicantTaskId(),
                        migration.applicantTaskId())
                || returned.returnTime() == null || !plan.round().sameRoundFacts(returned))
        {
            throw dataError();
        }
        Task applicantTask = runtimeReader.requireActiveTask(
                migration.applicantTaskId());
        if (!migration.targetActivityId().equals(
                applicantTask.getTaskDefinitionKey()))
        {
            throw conflict();
        }
        return new GroupReturnResult(migration.applicantTaskId(), returned);
    }

    /**
     * 从唯一 RETURNED 轮次和申请人任务构建重建计划。
     * @param applicantTaskId String，发起人待修改任务主键
     * @param applicantUserId String，已核验流程发起人主键
     * @return MultiInstanceGroupReopenPlan，多实例计划；普通退回返回 null
     */
    public MultiInstanceGroupReopenPlan prepareGroupReopen(String applicantTaskId,
            String applicantUserId)
    {
        List<MultiInstanceRoundSnapshot> returnedRows =
                roundRepository.findReturned(applicantTaskId);
        if (returnedRows.isEmpty())
        {
            return null;
        }
        if (returnedRows.size() != 1)
        {
            throw dataError();
        }
        MultiInstanceRoundSnapshot round = returnedRows.get(0);
        Task applicantTask = runtimeReader.requireActiveTask(applicantTaskId);
        if (round.status() != WorkflowMultiInstanceRoundStatus.RETURNED
                || !Objects.equals(round.applicantTaskId(), applicantTaskId)
                || !round.processInstanceId().equals(applicantTask.getProcessInstanceId())
                || !round.processDefinitionId().equals(
                        applicantTask.getProcessDefinitionId())
                || !applicantUserId.equals(applicantTask.getAssignee())
                || !runtimeReader.requireOnlyActiveExecution(applicantTask)
                        .equals(applicantTask.getExecutionId()))
        {
            throw transitionConflict("待修改任务对应的多实例轮次已经发生变化");
        }
        ProcessInstance instance = runtimeReader.requireActiveProcessInstance(
                round.processInstanceId());
        ControlledMultiInstanceDefinitionSnapshot definition = roundData(() ->
                snapshotReader.readDefinition(
                        round.processDefinitionId(), round.activityId()));
        if (definition == null || !round.deployId().equals(definition.deployId())
                || definition.mode() != round.mode()
                || StringUtils.hasText(instance.getSuperExecutionId())
                || !runtimeService.createProcessInstanceQuery()
                        .superProcessInstanceId(instance.getId()).listPage(0, 1).isEmpty()
                || !round.members().contains(round.returnActorUserId()))
        {
            throw dataError();
        }
        roundData(() -> snapshotReader.requirePersistedSnapshot(
                round.processDefinitionId(), round.processInstanceId(),
                round.activityId(), round.rootExecutionId(), round.mode(),
                round.members(), round.revision()));
        ReturnedApplicationSnapshot.SourceKind sourceKind =
                ReturnedApplicationSnapshot.SourceKind.ORDINARY_EXECUTION;
        String sourceExecutionId = applicantTask.getExecutionId();
        if (round.activityId().equals(applicantTask.getTaskDefinitionKey()))
        {
            ActiveControlledMultiInstanceRootSnapshot temporary = roundData(() ->
                    snapshotReader.readActiveRoots(Set.of(round.processInstanceId())))
                    .values().stream().filter(root -> root.activeTasks().stream()
                            .anyMatch(task -> applicantTaskId.equals(task.taskId())))
                    .findFirst().orElseThrow(this::dataError);
            if (round.rootExecutionId().equals(temporary.root().rootExecutionId())
                    || temporary.counts().instances() != 1
                    || temporary.counts().active() != 1
                    || temporary.counts().completed() != 0)
            {
                throw dataError();
            }
            sourceKind = ReturnedApplicationSnapshot.SourceKind
                    .TEMPORARY_MULTI_INSTANCE_ROOT;
            sourceExecutionId = temporary.root().rootExecutionId();
        }
        ReturnedApplicationSnapshot application = new ReturnedApplicationSnapshot(
                applicantTaskId, applicantTask.getExecutionId(), sourceExecutionId,
                round.processInstanceId(), round.processDefinitionId(),
                applicantTask.getTaskDefinitionKey(), applicantUserId, sourceKind);
        return new MultiInstanceGroupReopenPlan(round, application);
    }

    /**
     * 把唯一 RETURNED 旧轮 CAS 为 REOPENED 并复核不可变事实。
     * @param plan MultiInstanceGroupReopenPlan，重提前冻结计划
     * @return MultiInstanceRoundSnapshot，写后 REOPENED 旧轮
     */
    public MultiInstanceRoundSnapshot reopenRound(MultiInstanceGroupReopenPlan plan)
    {
        if (plan == null)
        {
            throw dataError();
        }
        MultiInstanceRoundSnapshot source = plan.round();
        roundRepository.compareAndSetReopened(source);
        MultiInstanceRoundSnapshot reopened = roundRepository.findByRootExecutionId(
                source.rootExecutionId());
        if (reopened.status() != WorkflowMultiInstanceRoundStatus.REOPENED
                || reopened.reopenTime() == null || !source.sameRoundFacts(reopened)
                || !Objects.equals(source.returnSourceTaskId(),
                        reopened.returnSourceTaskId())
                || !Objects.equals(source.returnActorUserId(),
                        reopened.returnActorUserId())
                || !Objects.equals(source.applicantTaskId(),
                        reopened.applicantTaskId()))
        {
            throw dataError();
        }
        return reopened;
    }

    /**
     * 从 REOPENED 旧轮重建完整新根和任务组并完成 SLA、计数和协议对账。
     * @param plan MultiInstanceGroupReopenPlan，重提前冻结计划
     * @param reopenedRound MultiInstanceRoundSnapshot，刚完成 CAS 的旧轮
     * @param actorUserId String，已核验流程发起人主键
     * @return GroupReopenResult，旧轮、新轮、新根和按成员顺序任务主键
     */
    public GroupReopenResult migrateReopen(MultiInstanceGroupReopenPlan plan,
            MultiInstanceRoundSnapshot reopenedRound, String actorUserId)
    {
        if (plan == null || reopenedRound == null
                || !plan.round().sameRoundFacts(reopenedRound)
                || reopenedRound.status() != WorkflowMultiInstanceRoundStatus.REOPENED
                || !actorUserId.equals(plan.application().applicantUserId()))
        {
            throw dataError();
        }
        try (WorkflowMultiInstanceTransitionScope scope =
                transitionCoordinator.beginReopen(plan, actorUserId))
        {
            runtimeService.createChangeActivityStateBuilder()
                    .processInstanceId(plan.round().processInstanceId())
                    .moveExecutionToActivityId(plan.application().sourceExecutionId(),
                            plan.round().activityId()).changeState();
            taskSlaRuntimeService.completeControlledWithdrawal(
                    plan.round().processInstanceId(),
                    List.of(plan.application().taskId()), actorUserId,
                    WorkflowTaskSlaRuntimeService.ControlledWithdrawal.GROUP_RESUBMIT);
            GroupReopenResult result = requireReopenedGroup(plan, reopenedRound);
            MultiInstanceTransitionResult observed =
                    transitionCoordinator.requireReopenCompleted(scope,
                            result.newRootExecutionId(),
                            plan.application().sourceKind()
                                    == ReturnedApplicationSnapshot.SourceKind
                                            .TEMPORARY_MULTI_INSTANCE_ROOT);
            if (!observed.collectionResolved()
                    || observed.reopenedTaskCount() != plan.round().members().size())
            {
                throw dataError();
            }
            return result;
        }
    }

    /**
     * 核验旧轮、新轮、实时根、成员顺序和 Flowable 计数。
     * @param plan MultiInstanceGroupReopenPlan，重建计划
     * @param reopenedRound MultiInstanceRoundSnapshot，REOPENED 旧轮
     * @return GroupReopenResult，完整不可变写后事实
     */
    private GroupReopenResult requireReopenedGroup(
            MultiInstanceGroupReopenPlan plan,
            MultiInstanceRoundSnapshot reopenedRound)
    {
        List<MultiInstanceRoundSnapshot> activeRows = roundRepository.findActive(
                plan.round().processInstanceId(), plan.round().activityId());
        if (activeRows.size() != 1)
        {
            throw dataError();
        }
        MultiInstanceRoundSnapshot newRound = activeRows.get(0);
        if (newRound.status() != WorkflowMultiInstanceRoundStatus.ACTIVE
                || newRound.roundNo() != reopenedRound.roundNo() + 1
                || newRound.rootExecutionId().equals(reopenedRound.rootExecutionId())
                || newRound.rootExecutionId().equals(
                        plan.application().sourceExecutionId())
                || !reopenedRound.sameReopenFacts(newRound)
                || !newRound.members().equals(plan.round().members()))
        {
            throw dataError();
        }
        ActiveControlledMultiInstanceRootSnapshot runtime = roundData(() ->
                snapshotReader.readActiveRoots(Set.of(plan.round().processInstanceId())))
                .values().stream().filter(root ->
                        newRound.rootExecutionId().equals(
                                root.root().rootExecutionId()))
                .findFirst().orElseThrow(this::dataError);
        if (runtime.counts().instances() != plan.round().members().size()
                || runtime.counts().active() != plan.round().members().size()
                || runtime.counts().completed() != 0)
        {
            throw dataError();
        }
        Map<String, String> taskByAssignee = new LinkedHashMap<>();
        for (MultiInstanceActiveTaskSnapshot task : runtime.activeTasks())
        {
            if (task.owner() != null || task.delegated()
                    || taskByAssignee.putIfAbsent(task.assignee(), task.taskId()) != null)
            {
                throw dataError();
            }
        }
        if (!taskByAssignee.keySet().equals(
                new LinkedHashSet<>(plan.round().members())))
        {
            throw dataError();
        }
        return new GroupReopenResult(reopenedRound, newRound,
                newRound.rootExecutionId(), plan.round().members().stream()
                        .map(taskByAssignee::get).toList());
    }

    /**
     * 核验整组退回命令与冻结来源任务一致。
     * @param plan MultiInstanceGroupReturnPlan，写前计划
     * @param targetActivityId String，服务端目标节点
     * @param actorUserId String，真实办理人主键
     * @return 无返回值，不一致时失败关闭
     */
    private void requireReturnCommand(MultiInstanceGroupReturnPlan plan,
            String targetActivityId, String actorUserId)
    {
        if (plan == null || !StringUtils.hasText(targetActivityId)
                || !StringUtils.hasText(actorUserId)
                || !actorUserId.equals(plan.runtime().sourceTask().assignee()))
        {
            throw dataError();
        }
    }

    /**
     * 校验正常活动根计数、成员和任务严格闭合。
     * @param runtime ControlledMultiInstanceSnapshot，实时完整快照
     * @return 无返回值，ANY 残留完成 child 或活动数异常时失败
     */
    private void requireNormalActiveRoot(ControlledMultiInstanceSnapshot runtime)
    {
        if (runtime.counts().active() < 1
                || (runtime.mode() == WorkflowMultiInstanceMode.ANY
                        && runtime.counts().completed() != 0)
                || runtime.counts().completed()
                        != runtime.members().size() - runtime.activeTasks().size())
        {
            throw transitionConflict("多实例活动任务已经发生变化，请刷新后重试");
        }
    }

    /**
     * 按轮次数据错误契约执行快照读取 lambda。
     * @param action java.util.function.Supplier&lt;T&gt;，快照读取动作
     * @param <T> 不可变快照类型
     * @return T，读取结果
     */
    private <T> T roundData(java.util.function.Supplier<T> action)
    {
        return WorkflowMultiInstanceSnapshotExceptionTranslator
                .asRoundDataError(action);
    }

    /**
     * 按轮次数据错误契约执行无返回值快照核验 lambda。
     * @param action Runnable，快照核验动作
     * @return 无返回值
     */
    private void roundData(Runnable action)
    {
        WorkflowMultiInstanceSnapshotExceptionTranslator.asRoundDataError(action);
    }

    /** @param message String，稳定业务提示 @return ServiceException，HTTP 409。 */
    private ServiceException transitionConflict(String message)
    {
        return new ServiceException(message, HttpStatus.CONFLICT);
    }

    /** @return ServiceException，稳定 HTTP 409。 */
    private ServiceException conflict()
    {
        return new ServiceException("工作流状态已发生变化，请刷新后重试",
                HttpStatus.CONFLICT);
    }

    /** @return ServiceException，稳定轮次 HTTP 500。 */
    private ServiceException dataError()
    {
        return new ServiceException("工作流多实例轮次状态不一致",
                HttpStatus.ERROR);
    }

    /**
     * Flowable 整组退回命令刚完成的不可变事实。
     * @param applicantTaskId String，新申请人任务主键
     * @param targetActivityId String，服务端目标节点
     */
    public record GroupReturnMigration(String applicantTaskId,
            String targetActivityId)
    {
        /** @return 无返回值，构造时校验稳定主键。 */
        public GroupReturnMigration
        {
            if (!StringUtils.hasText(applicantTaskId)
                    || !StringUtils.hasText(targetActivityId))
            {
                throw new IllegalArgumentException("整组退回迁移结果不完整");
            }
        }
    }

    /**
     * 整组退回 CAS 和写后对账结果。
     * @param applicantTaskId String，唯一申请人任务主键
     * @param returnedRound MultiInstanceRoundSnapshot，RETURNED 旧轮
     */
    public record GroupReturnResult(String applicantTaskId,
            MultiInstanceRoundSnapshot returnedRound)
    {
    }

    /**
     * 整组重建后的完整不可变结果。
     * @param reopenedRound MultiInstanceRoundSnapshot，REOPENED 旧轮
     * @param activeRound MultiInstanceRoundSnapshot，新 ACTIVE 轮
     * @param newRootExecutionId String，新多实例根 execution 主键
     * @param activeTaskIds List&lt;String&gt;，按冻结成员顺序排列的新任务主键
     */
    public record GroupReopenResult(MultiInstanceRoundSnapshot reopenedRound,
            MultiInstanceRoundSnapshot activeRound, String newRootExecutionId,
            List<String> activeTaskIds)
    {
        /** @return 无返回值，构造时冻结任务主键列表。 */
        public GroupReopenResult
        {
            activeTaskIds = List.copyOf(activeTaskIds);
        }
    }
}
