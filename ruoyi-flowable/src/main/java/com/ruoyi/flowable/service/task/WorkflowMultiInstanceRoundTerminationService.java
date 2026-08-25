package com.ruoyi.flowable.service.task;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.identitylink.api.IdentityLinkType;
import org.flowable.task.api.Task;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WorkflowMultiInstanceRoundStatus;

/**
 * 异常根取消、流程取消和管理员终止时的轮次预检与 CAS 关闭服务。
 */
@Service
public class WorkflowMultiInstanceRoundTerminationService
{
    /** Flowable 实例主键数据库列的最大长度。 */
    private static final int MAX_INSTANCE_ID_LENGTH = 64;

    /** 单次终止允许冻结的流程树及开放轮次上限。 */
    private static final int MAX_TERMINATION_SNAPSHOT_SIZE = 2_000;

    private final WorkflowMultiInstanceRoundRepository roundRepository;

    private final WorkflowMultiInstanceRuntimeSnapshotReader snapshotReader;

    private final WorkflowMultiInstanceTransitionObserver transitionObserver;

    private final RuntimeService runtimeService;

    private final TaskService taskService;

    /**
     * 创建轮次异常终止服务。
     *
     * @param roundRepository WorkflowMultiInstanceRoundRepository，轮次持久化边界
     * @param snapshotReader WorkflowMultiInstanceRuntimeSnapshotReader，唯一实时快照读取器
     * @param transitionObserver WorkflowMultiInstanceTransitionObserver，受控迁移取消观察器
     * @param runtimeService RuntimeService，仅查询流程发起人和运行实例身份
     * @param taskService TaskService，仅查询申请人局部标记和 candidate 关系
     * @return 无返回值，构造后由 Spring 管理
     */
    public WorkflowMultiInstanceRoundTerminationService(
            WorkflowMultiInstanceRoundRepository roundRepository,
            WorkflowMultiInstanceRuntimeSnapshotReader snapshotReader,
            WorkflowMultiInstanceTransitionObserver transitionObserver,
            RuntimeService runtimeService, TaskService taskService)
    {
        this.roundRepository = roundRepository;
        this.snapshotReader = snapshotReader;
        this.transitionObserver = transitionObserver;
        this.runtimeService = runtimeService;
        this.taskService = taskService;
    }

    /**
     * 在多实例根取消事件中关闭真实异常 ACTIVE 轮次，受控 RETURN/REOPEN 仅执行严格观察对账。
     *
     * @param event MultiInstanceRootCancellationEvent，监听器冻结的根取消事实
     * @return void，普通多实例无业务写入；漂移或 CAS 竞争回滚当前 Flowable 命令
     */
    public void onMultiInstanceRootCancelled(MultiInstanceRootCancellationEvent event)
    {
        if (event == null || !event.multiInstanceRoot() || event.suspended()
                || !event.executionId().equals(event.rootExecutionId())
                || !event.activityId().equals(event.rootActivityId())
                || !event.processInstanceId().equals(event.rootProcessInstanceId())
                || !event.processDefinitionId().equals(
                        event.rootProcessDefinitionId()))
        {
            throw dataError();
        }
        if (!"userTask".equals(event.activityType()))
        {
            return;
        }
        ControlledMultiInstanceRootSnapshot root = readCancelledRoot(event);
        if (root == null)
        {
            return;
        }
        MultiInstanceTransitionCancellation controlled =
                transitionObserver.observeControlledRootCancellation(
                        root.processInstanceId(), root.processDefinitionId(),
                        root.activityId(), root.rootExecutionId(),
                        event.authenticatedUserId());
        if (controlled != null)
        {
            requireControlledCancellation(root, controlled);
            return;
        }
        MultiInstanceRoundSnapshot round = roundRepository.findByRootExecutionId(
                root.rootExecutionId());
        if (round == null || round.status() != WorkflowMultiInstanceRoundStatus.ACTIVE)
        {
            throw dataError();
        }
        List<MultiInstanceRoundSnapshot> open = roundRepository.findOpen(
                root.processInstanceId(), root.activityId());
        if (open.size() != 1
                || open.get(0).roundId() != round.roundId())
        {
            throw dataError();
        }
        requireRootRound(root, round, WorkflowMultiInstanceRoundStatus.ACTIVE);
        roundRepository.compareAndSetTerminated(round);
    }

    /**
     * 在 Flowable 内部删除 CallActivity 子流程后关闭该实例全部残余开放轮次。
     *
     * @param event ProcessInstanceCancellationEventSnapshot，已删除流程实例取消事实
     * @return void，没有开放轮次时无写入；任一 CAS 失败回滚当前引擎事务
     */
    public void onProcessInstanceCancelled(
            ProcessInstanceCancellationEventSnapshot event)
    {
        if (event == null || !event.processInstanceType() || !event.deleted()
                || !event.executionId().equals(event.processInstanceId())
                || !event.entityId().equals(event.processInstanceId())
                || !event.entityProcessDefinitionId().equals(
                        event.processDefinitionId()))
        {
            throw dataError();
        }
        Set<String> instanceIds = Set.of(event.processInstanceId());
        List<MultiInstanceRoundSnapshot> open =
                roundRepository.findOpenByProcessInstanceIds(instanceIds);
        if (open.isEmpty())
        {
            return;
        }
        Map<Long, MultiInstanceRoundSnapshot> expected = freeze(open, instanceIds);
        Set<String> activities = new LinkedHashSet<>();
        long previousId = 0;
        for (MultiInstanceRoundSnapshot round : expected.values())
        {
            if (round.roundId() <= previousId || !activities.add(round.activityId())
                    || !round.processDefinitionId().equals(
                            event.processDefinitionId()))
            {
                throw dataError();
            }
            previousId = round.roundId();
            ControlledMultiInstanceDefinitionSnapshot definition =
                    WorkflowMultiInstanceSnapshotExceptionTranslator.asRoundDataError(
                            () -> snapshotReader.readDefinition(
                                    round.processDefinitionId(), round.activityId()));
            if (definition == null || !definition.deployId().equals(round.deployId())
                    || definition.mode() != round.mode())
            {
                throw dataError();
            }
        }
        for (MultiInstanceRoundSnapshot round : expected.values())
        {
            roundRepository.compareAndSetTerminated(round);
        }
    }

    /**
     * Flowable 写锁取得且执行树仍存在时冻结完整树开放轮次并对账全部活动受控根。
     *
     * @param processTreeInstanceIds Collection&lt;String&gt;，根及活动 CallActivity 子实例主键
     * @return MultiInstanceRoundTerminationPlan，删除前不可变预检计划
     */
    public MultiInstanceRoundTerminationPlan precheckTermination(
            Collection<String> processTreeInstanceIds)
    {
        Set<String> instanceIds = requireProcessInstanceIds(processTreeInstanceIds);
        Map<Long, MultiInstanceRoundSnapshot> open = freeze(
                roundRepository.findOpenByProcessInstanceIds(instanceIds), instanceIds);
        Map<String, ActiveControlledMultiInstanceRootSnapshot> roots =
                WorkflowMultiInstanceSnapshotExceptionTranslator.asRoundDataError(
                        () -> snapshotReader.readActiveRoots(instanceIds));
        Map<String, MultiInstanceRoundSnapshot> roundsByRoot = new LinkedHashMap<>();
        for (MultiInstanceRoundSnapshot round : open.values())
        {
            if (roundsByRoot.putIfAbsent(round.rootExecutionId(), round) != null)
            {
                throw dataError();
            }
        }
        Set<String> matchedActiveRoots = new LinkedHashSet<>();
        Set<Long> matchedReturnedRounds = new LinkedHashSet<>();
        for (ActiveControlledMultiInstanceRootSnapshot activeRoot : roots.values())
        {
            ControlledMultiInstanceRootSnapshot root = activeRoot.root();
            MultiInstanceRoundSnapshot round = roundsByRoot.get(root.rootExecutionId());
            if (round != null)
            {
                if (round.status() != WorkflowMultiInstanceRoundStatus.ACTIVE
                        || !matchedActiveRoots.add(round.rootExecutionId()))
                {
                    throw dataError();
                }
                requireActiveRoot(activeRoot, round);
                continue;
            }
            List<MultiInstanceRoundSnapshot> returned = open.values().stream()
                    .filter(candidate -> candidate.status()
                            == WorkflowMultiInstanceRoundStatus.RETURNED)
                    .filter(candidate -> candidate.processInstanceId().equals(
                            root.processInstanceId()))
                    .filter(candidate -> candidate.activityId().equals(root.activityId()))
                    .toList();
            if (returned.size() != 1
                    || !matchedReturnedRounds.add(returned.get(0).roundId()))
            {
                throw dataError();
            }
            requireReturnedApplicantRoot(activeRoot, returned.get(0));
        }
        for (MultiInstanceRoundSnapshot round : open.values())
        {
            if (round.status() == WorkflowMultiInstanceRoundStatus.ACTIVE
                    && !matchedActiveRoots.contains(round.rootExecutionId()))
            {
                throw dataError();
            }
            if (round.status() == WorkflowMultiInstanceRoundStatus.RETURNED
                    && roots.containsKey(round.rootExecutionId()))
            {
                throw dataError();
            }
        }
        return new MultiInstanceRoundTerminationPlan(instanceIds, open);
    }

    /**
     * Flowable 根删除后锁定预检集合并把全部 ACTIVE/RETURNED 原子关闭为 TERMINATED。
     *
     * @param plan MultiInstanceRoundTerminationPlan，同一事务删除前冻结的计划
     * @return void，集合或来源状态竞争时回滚整笔终止事务
     */
    public void terminatePrechecked(MultiInstanceRoundTerminationPlan plan)
    {
        if (plan == null)
        {
            throw dataError();
        }
        Map<Long, MultiInstanceRoundSnapshot> locked = freeze(
                roundRepository.findOpenForUpdate(plan.processInstanceIds()),
                plan.processInstanceIds());
        requireLockedSnapshot(plan.roundsById(), locked);
        if (!plan.roundsById().isEmpty())
        {
            roundRepository.terminateOpen(plan.roundsById().keySet());
        }
    }

    /**
     * 核对受控迁移取消授权与实时根及旧轮次完全一致。
     *
     * @param root ControlledMultiInstanceRootSnapshot，取消事件实时根事实
     * @param controlled MultiInstanceTransitionCancellation，Coordinator 授权事实
     * @return void，任一字段漂移时中止当前迁移
     */
    private void requireControlledCancellation(ControlledMultiInstanceRootSnapshot root,
            MultiInstanceTransitionCancellation controlled)
    {
        List<MultiInstanceRoundSnapshot> selected = roundRepository.findByRoundIds(
                Set.of(controlled.roundId()));
        if (selected.size() != 1)
        {
            throw dataError();
        }
        MultiInstanceRoundSnapshot round = selected.get(0);
        WorkflowMultiInstanceRoundStatus expectedStatus =
                controlled.action() == MultiInstanceTransitionAction.RETURN
                        ? WorkflowMultiInstanceRoundStatus.ACTIVE
                        : WorkflowMultiInstanceRoundStatus.REOPENED;
        if (round.roundId() != controlled.roundId()
                || !round.deployId().equals(controlled.deployId())
                || !round.processDefinitionId().equals(
                        controlled.processDefinitionId())
                || !round.processInstanceId().equals(
                        controlled.processInstanceId())
                || !round.activityId().equals(controlled.activityId())
                || !round.rootExecutionId().equals(
                        controlled.roundRootExecutionId())
                || round.mode() != controlled.mode()
                || round.revision() != controlled.revision()
                || !round.members().equals(controlled.members())
                || !root.deployId().equals(controlled.deployId())
                || !root.processDefinitionId().equals(
                        controlled.processDefinitionId())
                || !root.processInstanceId().equals(
                        controlled.processInstanceId())
                || !root.activityId().equals(
                        controlled.cancelledActivityId())
                || !root.rootExecutionId().equals(
                        controlled.cancelledRootExecutionId())
                || root.mode() != controlled.cancelledMode()
                || root.revision() != controlled.cancelledRevision()
                || !root.members().equals(controlled.cancelledMembers())
                || round.status() != expectedStatus
                || (controlled.action() == MultiInstanceTransitionAction.RETURN
                        && (!controlled.activityId().equals(
                                controlled.cancelledActivityId())
                                || !round.rootExecutionId().equals(
                                        root.rootExecutionId())))
                || (controlled.action() == MultiInstanceTransitionAction.REOPEN
                        && (!Objects.equals(round.returnSourceTaskId(),
                                controlled.sourceTaskId())
                                || !Objects.equals(round.applicantTaskId(),
                                        controlled.applicantTaskId()))))
        {
            throw dataError();
        }
    }

    /**
     * 对账正常 ACTIVE 根的计数、任务、成员和正式轮次。
     *
     * @param activeRoot ActiveControlledMultiInstanceRootSnapshot，实时活动根
     * @param round MultiInstanceRoundSnapshot，按根匹配的 ACTIVE 轮次
     * @return void，任一事实漂移时失败关闭
     */
    private void requireActiveRoot(ActiveControlledMultiInstanceRootSnapshot activeRoot,
            MultiInstanceRoundSnapshot round)
    {
        ControlledMultiInstanceRootSnapshot root = activeRoot.root();
        requireRootRound(root, round, WorkflowMultiInstanceRoundStatus.ACTIVE);
        if (activeRoot.counts().instances() != root.members().size()
                || activeRoot.counts().active() < 1
                || (root.mode() == WorkflowMultiInstanceMode.ANY
                        && activeRoot.counts().completed() != 0)
                || activeRoot.activeTasks().stream().anyMatch(
                        task -> !root.members().contains(task.assignee())))
        {
            throw dataError();
        }
    }

    /**
     * 校验 RETURNED 首节点临时单成员根及唯一申请人任务。
     *
     * @param activeRoot ActiveControlledMultiInstanceRootSnapshot，临时活动根
     * @param round MultiInstanceRoundSnapshot，唯一 RETURNED 旧轮次
     * @return void，根、任务、局部标记或候选关系漂移时失败
     */
    private void requireReturnedApplicantRoot(
            ActiveControlledMultiInstanceRootSnapshot activeRoot,
            MultiInstanceRoundSnapshot round)
    {
        ControlledMultiInstanceRootSnapshot root = activeRoot.root();
        if (round.status() != WorkflowMultiInstanceRoundStatus.RETURNED
                || !round.deployId().equals(root.deployId())
                || !round.processDefinitionId().equals(root.processDefinitionId())
                || !round.processInstanceId().equals(root.processInstanceId())
                || !round.activityId().equals(root.activityId())
                || round.rootExecutionId().equals(root.rootExecutionId())
                || round.mode() != root.mode() || round.revision() != root.revision()
                || !round.members().equals(root.members())
                || activeRoot.counts().instances() != 1
                || activeRoot.counts().active() != 1
                || activeRoot.counts().completed() != 0
                || activeRoot.activeTasks().size() != 1)
        {
            throw dataError();
        }
        MultiInstanceActiveTaskSnapshot taskSnapshot = activeRoot.activeTasks().get(0);
        Task task = taskService.createTaskQuery().taskId(round.applicantTaskId())
                .active().singleResult();
        var instance = runtimeService.createProcessInstanceQuery()
                .processInstanceId(root.processInstanceId()).singleResult();
        if (task == null || instance == null || task.isSuspended()
                || !taskSnapshot.taskId().equals(task.getId())
                || !Objects.equals(instance.getStartUserId(), task.getAssignee())
                || task.getOwner() != null || task.getDelegationState() != null
                || !Objects.equals(instance.getStartUserId(),
                        taskService.getVariableLocal(task.getId(),
                                WorkflowReturnedApplicationProtocol
                                        .RETURN_APPLICANT_VARIABLE))
                || hasCandidateIdentityLinks(task.getId()))
        {
            throw dataError();
        }
    }

    /**
     * 对账实时根与指定状态正式轮次。
     *
     * @param root ControlledMultiInstanceRootSnapshot，实时根事实
     * @param round MultiInstanceRoundSnapshot，正式轮次事实
     * @param status WorkflowMultiInstanceRoundStatus，当前动作要求的状态
     * @return void，部署、根或快照漂移时失败
     */
    private void requireRootRound(ControlledMultiInstanceRootSnapshot root,
            MultiInstanceRoundSnapshot round, WorkflowMultiInstanceRoundStatus status)
    {
        if (!round.deployId().equals(root.deployId())
                || !round.processDefinitionId().equals(root.processDefinitionId())
                || !round.processInstanceId().equals(root.processInstanceId())
                || !round.activityId().equals(root.activityId())
                || !round.rootExecutionId().equals(root.rootExecutionId())
                || round.mode() != root.mode() || round.revision() != root.revision()
                || !round.members().equals(root.members()) || round.status() != status)
        {
            throw dataError();
        }
    }

    /**
     * 规范并冻结终止流程树主键。
     *
     * @param rawIds Collection&lt;String&gt;，调用方解析的完整活动流程树
     * @return Set&lt;String&gt;，保持顺序、无重复的不可修改主键集合
     */
    private Set<String> requireProcessInstanceIds(Collection<String> rawIds)
    {
        if (rawIds == null || rawIds.isEmpty()
                || rawIds.size() > MAX_TERMINATION_SNAPSHOT_SIZE)
        {
            throw dataError();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (String rawId : rawIds)
        {
            if (!StringUtils.hasText(rawId))
            {
                throw dataError();
            }
            String id = rawId.trim();
            if (id.length() > MAX_INSTANCE_ID_LENGTH || !id.equals(rawId)
                    || !ids.add(id))
            {
                throw dataError();
            }
        }
        return Set.copyOf(ids);
    }

    /**
     * 把 Mapper 开放轮次查询冻结为按主键索引的不可变事实。
     *
     * @param rows List&lt;MultiInstanceRoundSnapshot&gt;，ACTIVE/RETURNED 查询结果
     * @param instanceIds Set&lt;String&gt;，本次流程树主键
     * @return Map&lt;Long,MultiInstanceRoundSnapshot&gt;，按 roundId 索引
     */
    private Map<Long, MultiInstanceRoundSnapshot> freeze(
            List<MultiInstanceRoundSnapshot> rows, Set<String> instanceIds)
    {
        if (rows.size() > MAX_TERMINATION_SNAPSHOT_SIZE)
        {
            throw dataError();
        }
        Map<Long, MultiInstanceRoundSnapshot> result = new LinkedHashMap<>();
        for (MultiInstanceRoundSnapshot round : rows)
        {
            if (!round.status().isOpen()
                    || !instanceIds.contains(round.processInstanceId())
                    || result.putIfAbsent(round.roundId(), round) != null)
            {
                throw dataError();
            }
        }
        return Map.copyOf(result);
    }

    /**
     * 比较删除前普通读计划与删除后加锁 current-read。
     *
     * @param expected Map&lt;Long,MultiInstanceRoundSnapshot&gt;，删除前冻结事实
     * @param locked Map&lt;Long,MultiInstanceRoundSnapshot&gt;，删除后加锁事实
     * @return void，来源状态竞争返回稳定 409，其他漂移返回数据错误
     */
    private void requireLockedSnapshot(
            Map<Long, MultiInstanceRoundSnapshot> expected,
            Map<Long, MultiInstanceRoundSnapshot> locked)
    {
        if (!expected.keySet().equals(locked.keySet()))
        {
            throw dataError();
        }
        for (Map.Entry<Long, MultiInstanceRoundSnapshot> entry : expected.entrySet())
        {
            MultiInstanceRoundSnapshot current = locked.get(entry.getKey());
            if (entry.getValue().equals(current))
            {
                continue;
            }
            if (current != null && entry.getValue().sameRoundFacts(current)
                    && entry.getValue().status() != current.status())
            {
                throw terminationConflict(
                        "流程多实例轮次开放状态已发生变化，请刷新后重试");
            }
            throw dataError();
        }
    }

    /**
     * 判断任务是否残留 candidate 用户或组关系。
     *
     * @param taskId String，申请人任务主键
     * @return boolean，存在任一 candidate 关系时返回 true
     */
    private boolean hasCandidateIdentityLinks(String taskId)
    {
        var links = taskService.getIdentityLinksForTask(taskId);
        if (links == null)
        {
            throw dataError();
        }
        return links.stream().anyMatch(link -> link != null
                && IdentityLinkType.CANDIDATE.equals(link.getType()));
    }

    /**
     * 读取取消事件根快照并翻译内部漂移异常。
     *
     * @param event MultiInstanceRootCancellationEvent，根取消事实
     * @return ControlledMultiInstanceRootSnapshot，普通多实例返回 null
     */
    private ControlledMultiInstanceRootSnapshot readCancelledRoot(
            MultiInstanceRootCancellationEvent event)
    {
        return WorkflowMultiInstanceSnapshotExceptionTranslator.asRoundDataError(
                () -> snapshotReader.readCancelledRoot(event.processDefinitionId(),
                        event.processInstanceId(), event.activityId(),
                        event.rootExecutionId()));
    }

    /**
     * 创建异常终止并发变化的稳定冲突。
     *
     * @param message String，既有稳定业务错误信息
     * @return ServiceException，HTTP 409
     */
    private ServiceException terminationConflict(String message)
    {
        return new ServiceException(message, HttpStatus.CONFLICT);
    }

    /**
     * 创建轮次状态不一致的稳定服务端错误。
     *
     * @return ServiceException，HTTP 500
     */
    private ServiceException dataError()
    {
        return new ServiceException("工作流多实例轮次状态不一致", HttpStatus.ERROR);
    }

}
