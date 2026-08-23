package com.ruoyi.flowable.service.task;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.UserTask;
import org.flowable.common.engine.api.FlowableObjectNotFoundException;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.delegate.event.FlowableActivityCancelledEvent;
import org.flowable.engine.delegate.event.FlowableCancelledEvent;
import org.flowable.engine.impl.persistence.entity.ExecutionEntity;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.flowable.task.service.delegate.DelegateTask;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfMultiInstanceRound;
import com.ruoyi.flowable.domain.WorkflowMultiInstanceRoundStatus;
import com.ruoyi.flowable.mapper.WfMultiInstanceRoundMapper;

/**
 * 受控多实例轮次生命周期服务，在 Flowable 当前事务内维护正式轮次快照并执行严格对账。
 */
@Service
public class WorkflowMultiInstanceRoundService
{
    /** Flowable 实例主键数据库列的最大长度。 */
    private static final int MAX_INSTANCE_ID_LENGTH = 64;

    /** 单次异常终止允许冻结的流程树及开放轮次数量上限。 */
    private static final int MAX_TERMINATION_SNAPSHOT_SIZE = 2_000;

    /** Flowable 多实例根维护的总实例数变量。 */
    private static final String NUMBER_OF_INSTANCES = "nrOfInstances";

    /** Flowable 多实例根维护的活动实例数变量。 */
    private static final String NUMBER_OF_ACTIVE_INSTANCES = "nrOfActiveInstances";

    /** Flowable 多实例根维护的已完成实例数变量。 */
    private static final String NUMBER_OF_COMPLETED_INSTANCES = "nrOfCompletedInstances";

    /** 动态多实例 CAS 冲突的稳定客户端子码。 */
    private static final String REVISION_CONFLICT_SUB_CODE =
            "WORKFLOW_MULTI_INSTANCE_REVISION_CONFLICT";

    private final WfMultiInstanceRoundMapper roundMapper;

    private final RepositoryService repositoryService;

    private final RuntimeService runtimeService;

    /**
     * 创建受控多实例轮次生命周期服务。
     *
     * @param roundMapper WfMultiInstanceRoundMapper，轮次插入、查询和 CAS Mapper
     * @param repositoryService RepositoryService，部署流程定义与 BPMN 模型查询服务
     * @param runtimeService RuntimeService，实时 execution、变量和根计数查询服务
     * @return 无返回值，构造后由 Spring 管理该领域服务
     */
    public WorkflowMultiInstanceRoundService(WfMultiInstanceRoundMapper roundMapper,
            RepositoryService repositoryService, RuntimeService runtimeService)
    {
        this.roundMapper = roundMapper;
        this.repositoryService = repositoryService;
        this.runtimeService = runtimeService;
    }

    /**
     * 在受控多实例任务 create 事件中创建首个轮次，或核对同根加签任务复用的既有轮次。
     *
     * @param delegateTask DelegateTask，参与者规则已经解析完成的 Flowable create 事件任务
     * @return void，普通任务不写轮次；受控任务缺行或漂移时中止当前 Flowable 事务
     */
    public void onTaskCreated(DelegateTask delegateTask)
    {
        ControlledTaskContext context = resolveControlledTask(delegateTask);
        if (context == null)
        {
            return;
        }
        EngineSnapshot engine = loadEngineSnapshot(context);
        requireTaskMember(delegateTask.getAssignee(), engine.members());

        WfMultiInstanceRound existing = roundMapper.selectByRootExecutionId(
                context.rootExecution().getId());
        if (existing != null)
        {
            // 加签创建的新任务必须复用同根轮次，禁止生成第二条业务事实。
            requireCurrentActiveRound(context, engine, existing);
            return;
        }

        List<WfMultiInstanceRound> openRounds = requireRows(
                roundMapper.selectOpenByProcessInstanceAndActivity(
                        context.processInstanceId(), context.activityId()));
        if (!openRounds.isEmpty())
        {
            // 新根只能在旧轮已关闭后建立；活动或退回中的旧轮说明执行树与业务事实漂移。
            throw dataError();
        }
        List<WfMultiInstanceRound> activeRounds = requireRows(
                roundMapper.selectActiveByProcessInstanceAndActivity(
                        context.processInstanceId(), context.activityId()));
        if (!activeRounds.isEmpty())
        {
            throw dataError();
        }

        Integer maxRoundNo = roundMapper.selectMaxRoundNo(
                context.processInstanceId(), context.activityId());
        int roundNo = requireNextRoundNo(maxRoundNo);
        WfMultiInstanceRound round = new WfMultiInstanceRound();
        round.setDeployId(context.deployId());
        round.setProcessDefinitionId(context.processDefinitionId());
        round.setProcessInstanceId(context.processInstanceId());
        round.setActivityId(context.activityId());
        round.setRootExecutionId(context.rootExecution().getId());
        round.setRoundNo(roundNo);
        round.setMode(engine.mode().name());
        round.setMembersJson(WfMultiInstanceRound.encodeMembers(engine.members()));
        round.setRevisionNo(engine.revision());
        round.setRoundStatus(WorkflowMultiInstanceRoundStatus.ACTIVE);
        // 生命周期时间统一由同一数据库时钟生成，避免应用节点与 MySQL 时钟偏差破坏时间 CHECK。
        if (roundMapper.insert(round) != 1 || round.getRoundId() == null)
        {
            throw dataError();
        }

        WfMultiInstanceRound inserted = roundMapper.selectByRootExecutionId(
                context.rootExecution().getId());
        requireCurrentActiveRound(context, engine, inserted);
    }

    /**
     * 在受控多实例任务 complete 监听事件中核验预留 revision，并在整组结束时关闭当前轮次。
     *
     * @param delegateTask DelegateTask，尚处于完成命令内且根计数尚未应用本次完成的任务
     * @return void，部分完成保持 ACTIVE，ALL 最后一人或 ANY 任一人完成时转为 COMPLETED
     */
    public void onTaskCompleted(DelegateTask delegateTask)
    {
        ControlledTaskContext context = resolveControlledTask(delegateTask);
        if (context == null)
        {
            return;
        }
        EngineSnapshot engine = loadEngineSnapshot(context);
        requireTaskMember(delegateTask.getAssignee(), engine.members());
        requireActiveRootCounts(engine);

        WfMultiInstanceRound round = requireSingleCurrentActiveRound(context);
        requireRoundIdentity(context, round);
        List<String> persistedMembers = decodeMembers(round);
        int reservedRevision = requireNonNegativeInteger(delegateTask.getVariableLocal(
                WorkflowMultiInstanceVariables.COMPLETION_REVISION_VARIABLE));
        if (!persistedMembers.equals(engine.members())
                || !Objects.equals(round.getMode(), engine.mode().name())
                || round.getRevisionNo() == null
                || engine.revision() <= 0 || reservedRevision != engine.revision()
                || round.getRevisionNo() != engine.revision())
        {
            // 正式完成链必须先同步引擎和业务 revision 并留下 task-local 标记，直接完成一律失败关闭。
            throw dataError();
        }

        boolean completesGroup = completesGroup(engine);
        if (completesGroup && roundMapper.compareAndSetCompletedStatus(
                round.getRoundId(), engine.revision(),
                WfMultiInstanceRound.encodeMembers(engine.members())) != 1)
        {
            // 业务表与 Flowable revision 使用固定锁顺序；业务 CAS 输家必须令整个引擎事务回滚。
            throw revisionConflict();
        }

        WfMultiInstanceRound updated = roundMapper.selectByRootExecutionId(
                context.rootExecution().getId());
        requireRoundIdentity(context, updated);
        requireRoundSnapshot(updated, engine.members(), engine.revision(), engine.mode(),
                completesGroup ? WorkflowMultiInstanceRoundStatus.COMPLETED
                        : WorkflowMultiInstanceRoundStatus.ACTIVE);
    }

    /**
     * 在 Flowable 多实例根被父作用域或 CallActivity 异常取消时关闭仍开放的正式轮次。
     *
     * <p>Flowable 通常以 MULTI_INSTANCE_ACTIVITY_CANCELLED 表示整个根异常退出，部分
     * 删除路径会对根发送普通 ACTIVITY_CANCELLED。全局监听器使用 CommandContext 的
     * isMultiInstanceRoot 判定根；ANY 正常完成只取消 sibling child，因此不会被误判。</p>
     *
     * @param event FlowableActivityCancelledEvent，当前引擎命令内的多实例根取消事实
     * @param rootExecution ExecutionEntity，CommandContext 已证明为多实例根的实时执行实体
     * @return void，普通多实例不写业务表；受控根缺行、漂移或 CAS 冲突会回滚当前引擎事务
     */
    public void onMultiInstanceActivityCancelled(
            FlowableActivityCancelledEvent event, ExecutionEntity rootExecution)
    {
        ControlledTaskContext context = resolveCancelledControlledRoot(event,
                rootExecution);
        if (context == null)
        {
            return;
        }

        // Flowable 在两类根取消事件派发前都可能已开始删除 child 或局部计数；这里只读取仍由
        // 流程实例保存的固定成员、模式和 revision，正式任务读写契约仍继续严格读取三个计数。
        EngineSnapshot engine = loadEngineSnapshot(context, false);
        WfMultiInstanceRound round = roundMapper.selectByRootExecutionId(
                context.rootExecution().getId());
        if (round == null || round.getRoundId() == null
                || round.getRevisionNo() == null || round.getRoundStatus() == null
                || round.getRoundStatus() != WorkflowMultiInstanceRoundStatus.ACTIVE
                || !round.getRoundStatus().canTransitionTo(
                        WorkflowMultiInstanceRoundStatus.TERMINATED))
        {
            throw dataError();
        }
        List<WfMultiInstanceRound> openRounds = requireRows(
                roundMapper.selectOpenByProcessInstanceAndActivity(
                        context.processInstanceId(), context.activityId()));
        if (openRounds.size() != 1
                || !Objects.equals(round.getRoundId(), openRounds.get(0).getRoundId()))
        {
            // 根唯一行与节点唯一开放行必须是同一事实，禁止在约束损坏时选中任意一条关闭。
            throw dataError();
        }
        requireRoundIdentity(context, round);
        requireRoundSnapshot(round, engine.members(), engine.revision(), engine.mode(),
                round.getRoundStatus());

        TerminationRoundFacts sourceFacts = TerminationRoundFacts.from(round);
        int affected = roundMapper.compareAndSetTerminatedStatus(round.getRoundId(),
                engine.revision(), WorkflowMultiInstanceRoundStatus.ACTIVE);
        if (affected != 1)
        {
            // 引擎根已取得当前命令写锁；业务行 CAS 输家必须令根取消和边界流转整体回滚。
            throw terminationConflict("流程多实例轮次异常退出状态已发生变化，请刷新后重试");
        }

        WfMultiInstanceRound terminated = roundMapper.selectByRootExecutionId(
                context.rootExecution().getId());
        requireRoundIdentity(context, terminated);
        if (!Objects.equals(round.getRoundId(), terminated.getRoundId())
                || !sourceFacts.matchesTerminated(terminated))
        {
            // 写后复核要求只新增 TERMINATED 与数据库时间，成员、revision 和退回审计均不得漂移。
            throw dataError();
        }
    }

    /**
     * 在 Flowable 内部取消并删除 CallActivity 子流程实例后，关闭该实例全部残余开放轮次。
     *
     * <p>显式 deleteProcessInstance 派发 PROCESS_CANCELLED 时实例实体尚未标记 deleted，
     * 全局监听器不会调用本方法；因此这里不会抢占既有 precheck/terminatePrechecked 终止链。</p>
     *
     * @param event FlowableCancelledEvent，已经删除的流程实例取消事实
     * @param processInstance ExecutionEntity，CommandContext 中 isDeleted=true 的流程实例实体
     * @return void，没有开放轮次时无写入；字段漂移或任一 CAS 失败会回滚当前引擎事务
     */
    public void onProcessInstanceCancelled(FlowableCancelledEvent event,
            ExecutionEntity processInstance)
    {
        if (event == null || processInstance == null
                || !processInstance.isProcessInstanceType()
                || !processInstance.isDeleted()
                || !StringUtils.hasText(event.getProcessInstanceId())
                || !StringUtils.hasText(event.getProcessDefinitionId())
                || !Objects.equals(event.getExecutionId(), event.getProcessInstanceId())
                || !Objects.equals(event.getProcessInstanceId(), processInstance.getId())
                || !Objects.equals(event.getProcessDefinitionId(),
                        processInstance.getProcessDefinitionId()))
        {
            throw dataError();
        }

        Set<String> processInstanceIds = Set.of(event.getProcessInstanceId());
        List<WfMultiInstanceRound> openRounds = requireRows(
                roundMapper.selectOpenByProcessInstanceIds(processInstanceIds));
        if (openRounds.isEmpty())
        {
            return;
        }
        OpenRoundSnapshot snapshot = freezeOpenRounds(openRounds, processInstanceIds);
        Set<String> openActivities = new LinkedHashSet<>();
        long previousRoundId = 0;
        for (WfMultiInstanceRound round : openRounds)
        {
            if (round.getRoundId() == null || round.getRoundId() <= previousRoundId
                    || !openActivities.add(round.getActivityId()))
            {
                // 查询必须按 round_id 稳定递增，同一实例节点不得出现两个开放轮次。
                throw dataError();
            }
            previousRoundId = round.getRoundId();
            requireCancelledProcessRound(event, round);
        }

        // Flowable 流程实例已取得删除写锁，再按 round_id 顺序逐行 CAS，固定跨表锁顺序。
        for (WfMultiInstanceRound round : openRounds)
        {
            int affected = roundMapper.compareAndSetTerminatedStatus(round.getRoundId(),
                    round.getRevisionNo(), round.getRoundStatus());
            if (affected != 1)
            {
                throw terminationConflict(
                        "流程多实例轮次异常退出状态已发生变化，请刷新后重试");
            }
        }
        requireTerminatedRows(snapshot.factsByRoundId(),
                requireRows(roundMapper.selectByRoundIds(
                        snapshot.factsByRoundId().keySet())));

        // current-read 锁定零开放结果，禁止并发新增或状态竞争绕过流程实例取消门禁。
        if (!requireRows(roundMapper.selectOpenForUpdateByProcessInstanceIds(
                processInstanceIds)).isEmpty())
        {
            throw dataError();
        }
    }

    /**
     * 为多实例正式读写装载唯一 ACTIVE 轮次，并与定义、部署、根、模式、成员和 revision 逐项对账。
     *
     * @param task Task，已经通过对象授权与活动态校验的真实任务
     * @param rootExecutionId String，当前任务解析出的唯一多实例根 execution 主键
     * @param mode WorkflowMultiInstanceMode，部署模型与 Flowable 变量一致的固定模式
     * @param members List&lt;String&gt;，Flowable 实时有序成员快照
     * @param revision int，Flowable 实时 revision
     * @return WfMultiInstanceRound，字段完整且与引擎事实一致的唯一 ACTIVE 轮次
     */
    public WfMultiInstanceRound requireActiveRound(Task task, String rootExecutionId,
            WorkflowMultiInstanceMode mode, List<String> members, int revision)
    {
        ControlledTaskContext context = resolveControlledTask(task, rootExecutionId);
        if (context == null)
        {
            throw dataError();
        }
        EngineSnapshot engine = new EngineSnapshot(mode, List.copyOf(members), revision,
                null);
        if (mode != context.modelMode())
        {
            throw dataError();
        }
        WfMultiInstanceRound round = requireSingleCurrentActiveRound(context);
        requireCurrentActiveRound(context, engine, round);
        return round;
    }

    /**
     * 在 Flowable revision 推进成功后，以 ACTIVE 与 expected revision 为条件更新轮次成员快照。
     *
     * @param round WfMultiInstanceRound，写前正式读取并完整对账的当前轮次
     * @param expectedRevision int，写前 Flowable 与业务表共同 revision
     * @param newRevision int，严格递增一的 Flowable 新 revision
     * @param members List&lt;String&gt;，动作后的完整有序成员快照
     * @return void，CAS 影响行数不为一时抛出带稳定子码的 HTTP 409
     */
    public void compareAndSetActiveSnapshot(WfMultiInstanceRound round,
            int expectedRevision, int newRevision, List<String> members)
    {
        if (round == null || round.getRoundId() == null
                || round.getRevisionNo() == null
                || round.getRevisionNo() != expectedRevision
                || expectedRevision < 0
                || expectedRevision == Integer.MAX_VALUE
                || newRevision != expectedRevision + 1)
        {
            throw dataError();
        }
        String membersJson = WfMultiInstanceRound.encodeMembers(members);
        int affected = roundMapper.compareAndSetActiveSnapshot(round.getRoundId(),
                expectedRevision, newRevision, membersJson);
        if (affected != 1)
        {
            throw revisionConflict();
        }
    }

    /**
     * 在 taskService.complete 返回后复核本轮业务状态已经由 complete 监听器可靠落库。
     *
     * @param rootExecutionId String，本次完成前冻结的多实例根 execution 主键
     * @param expectedRevision int，本次完成占用后的 Flowable revision
     * @param groupCompleted boolean，本次完成按固定 ALL/ANY 规则是否结束整组
     * @return WfMultiInstanceRound，完成监听器已经同步且字段合法的目标轮次
     */
    public WfMultiInstanceRound requireCompletionPersisted(String rootExecutionId,
            int expectedRevision,
            boolean groupCompleted)
    {
        WfMultiInstanceRound round = roundMapper.selectByRootExecutionId(rootExecutionId);
        if (round == null)
        {
            throw dataError();
        }
        requireValidRound(round);
        WorkflowMultiInstanceRoundStatus expectedStatus = groupCompleted
                ? WorkflowMultiInstanceRoundStatus.COMPLETED
                : WorkflowMultiInstanceRoundStatus.ACTIVE;
        if (round.getRevisionNo() == null || round.getRevisionNo() != expectedRevision
                || round.getRoundStatus() != expectedStatus)
        {
            throw dataError();
        }
        decodeMembers(round);
        return round;
    }

    /**
     * 在 Flowable 写锁已经取得且执行树仍存在时，冻结完整树开放轮次并严格对账全部活动受控根。
     *
     * 该阶段只执行普通 SELECT，不取得任何业务行锁；ACTIVE 轮次必须与实时受控多实例根一一
     * 对应，RETURNED 轮次允许根已离开但自身生命周期必须完整。返回令牌只能在同一事务的
     * Flowable 根删除之后交给 {@link #terminatePrechecked(TerminationPrecheck)} 使用。
     *
     * @param processTreeInstanceIds Collection&lt;String&gt;，根及全部活动 CallActivity 子实例主键
     * @return TerminationPrecheck，冻结开放轮次主键、来源状态和全部删除前事实的不透明令牌
     */
    public TerminationPrecheck precheckTermination(
            Collection<String> processTreeInstanceIds)
    {
        Set<String> processInstanceIds = requireProcessInstanceIds(
                processTreeInstanceIds);
        OpenRoundSnapshot openSnapshot = freezeOpenRounds(requireRows(
                roundMapper.selectOpenByProcessInstanceIds(processInstanceIds)),
                processInstanceIds);
        Map<String, ActiveControlledRoot> activeRoots =
                loadActiveControlledRoots(processInstanceIds);

        Set<String> matchedActiveRoots = new LinkedHashSet<>();
        for (ActiveControlledRoot activeRoot : activeRoots.values())
        {
            WfMultiInstanceRound round = openSnapshot.roundsByRootExecutionId()
                    .get(activeRoot.context().rootExecution().getId());
            if (round == null
                    || round.getRoundStatus() != WorkflowMultiInstanceRoundStatus.ACTIVE
                    || !matchedActiveRoots.add(round.getRootExecutionId()))
            {
                throw dataError();
            }
            // ACTIVE 行复用正式读写链相同的定义、根、模式、成员和 revision 严格规则。
            requireRoundIdentity(activeRoot.context(), round);
            requireRoundSnapshot(round, activeRoot.engine().members(),
                    activeRoot.engine().revision(), activeRoot.engine().mode(),
                    WorkflowMultiInstanceRoundStatus.ACTIVE);
        }
        for (WfMultiInstanceRound round : openSnapshot.roundsByRootExecutionId().values())
        {
            if (round.getRoundStatus() == WorkflowMultiInstanceRoundStatus.ACTIVE
                    && !matchedActiveRoots.contains(round.getRootExecutionId()))
            {
                // 额外 ACTIVE 行或缺少实时受控根都说明引擎与业务快照已经分叉。
                throw dataError();
            }
            if (round.getRoundStatus() == WorkflowMultiInstanceRoundStatus.RETURNED
                    && activeRoots.containsKey(round.getRootExecutionId()))
            {
                throw dataError();
            }
        }
        return new TerminationPrecheck(processInstanceIds, openSnapshot.factsByRoundId());
    }

    /**
     * Flowable 根删除成功后，锁定预检集合、校验无漂移并把 ACTIVE/RETURNED 原子关闭为 TERMINATED。
     *
     * @param precheck TerminationPrecheck，同一事务删除前由 precheckTermination 返回的不透明令牌
     * @return void，集合或字段漂移返回数据异常，来源状态竞争或更新数量漂移返回 HTTP 409
     */
    public void terminatePrechecked(TerminationPrecheck precheck)
    {
        if (precheck == null)
        {
            throw dataError();
        }
        OpenRoundSnapshot lockedSnapshot = freezeOpenRounds(requireRows(
                roundMapper.selectOpenForUpdateByProcessInstanceIds(
                        precheck.processInstanceIds)), precheck.processInstanceIds);
        requireLockedTerminationSnapshot(precheck.factsByRoundId,
                lockedSnapshot.factsByRoundId());

        Set<Long> roundIds = precheck.factsByRoundId.keySet();
        if (!roundIds.isEmpty())
        {
            int updated = roundMapper.terminateOpenByRoundIds(roundIds);
            if (updated != roundIds.size())
            {
                throw terminationConflict("流程多实例轮次异常关闭数量不一致");
            }
            requireTerminatedRows(precheck.factsByRoundId,
                    requireRows(roundMapper.selectByRoundIds(roundIds)));
        }

        // 再次使用 locking/current-read，避免 RR 旧 read-view 或预检后 phantom 绕过零开放门禁。
        List<WfMultiInstanceRound> remaining = requireRows(
                roundMapper.selectOpenForUpdateByProcessInstanceIds(
                        precheck.processInstanceIds));
        if (!remaining.isEmpty())
        {
            throw dataError();
        }
    }

    /**
     * 规范并冻结异常终止的完整运行流程树主键。
     *
     * @param rawInstanceIds Collection&lt;String&gt;，调用方在取得 Flowable 写锁前已解析的流程树
     * @return Set&lt;String&gt;，保持调用顺序、无重复且不可修改语义的实例主键集合
     */
    private Set<String> requireProcessInstanceIds(Collection<String> rawInstanceIds)
    {
        if (rawInstanceIds == null || rawInstanceIds.isEmpty()
                || rawInstanceIds.size() > MAX_TERMINATION_SNAPSHOT_SIZE)
        {
            throw dataError();
        }
        Set<String> instanceIds = new LinkedHashSet<>();
        for (String rawInstanceId : rawInstanceIds)
        {
            if (!StringUtils.hasText(rawInstanceId))
            {
                throw dataError();
            }
            String instanceId = rawInstanceId.trim();
            if (instanceId.length() > MAX_INSTANCE_ID_LENGTH
                    || !instanceId.equals(rawInstanceId)
                    || !instanceIds.add(instanceId))
            {
                throw dataError();
            }
        }
        return Set.copyOf(instanceIds);
    }

    /**
     * 校验并冻结一次普通查询或加锁查询返回的全部开放轮次。
     *
     * @param rows List&lt;WfMultiInstanceRound&gt;，完整树 ACTIVE/RETURNED 查询结果
     * @param processInstanceIds Set&lt;String&gt;，冻结的根及 CallActivity 子实例主键
     * @return OpenRoundSnapshot，按轮次主键和根 execution 双索引的不可变事实
     */
    private OpenRoundSnapshot freezeOpenRounds(List<WfMultiInstanceRound> rows,
            Set<String> processInstanceIds)
    {
        if (rows.size() > MAX_TERMINATION_SNAPSHOT_SIZE)
        {
            throw dataError();
        }
        Map<Long, TerminationRoundFacts> factsByRoundId = new LinkedHashMap<>();
        Map<String, WfMultiInstanceRound> roundsByRootExecutionId =
                new LinkedHashMap<>();
        for (WfMultiInstanceRound round : rows)
        {
            if (round == null || round.getRoundId() == null
                    || !StringUtils.hasText(round.getRootExecutionId())
                    || !processInstanceIds.contains(round.getProcessInstanceId())
                    || round.getRoundStatus() == null
                    || !round.getRoundStatus().isOpen())
            {
                throw dataError();
            }
            requireValidRound(round);
            decodeMembers(round);
            TerminationRoundFacts facts = TerminationRoundFacts.from(round);
            if (factsByRoundId.putIfAbsent(round.getRoundId(), facts) != null
                    || roundsByRootExecutionId.putIfAbsent(
                            round.getRootExecutionId(), round) != null)
            {
                throw dataError();
            }
        }
        return new OpenRoundSnapshot(Map.copyOf(factsByRoundId),
                Map.copyOf(roundsByRootExecutionId));
    }

    /**
     * 按实例和 activity 对 execution 图分组，识别唯一顶层根并仅保留部署模型声明的受控用户任务。
     *
     * @param processInstanceIds Set&lt;String&gt;，终止前仍活动的完整 Flowable 流程树
     * @return Map&lt;String,ActiveControlledRoot&gt;，以多实例根 execution 主键唯一索引的实时快照
     */
    private Map<String, ActiveControlledRoot> loadActiveControlledRoots(
            Set<String> processInstanceIds)
    {
        Map<String, ActiveControlledRoot> activeRoots = new LinkedHashMap<>();
        for (String processInstanceId : processInstanceIds)
        {
            ProcessInstance processInstance = runtimeService.createProcessInstanceQuery()
                    .processInstanceId(processInstanceId).singleResult();
            if (processInstance == null || processInstance.isSuspended()
                    || !StringUtils.hasText(processInstance.getProcessDefinitionId()))
            {
                throw dataError();
            }
            List<Execution> executions = runtimeService.createExecutionQuery()
                    .processInstanceId(processInstanceId).list();
            if (executions == null || executions.size() > MAX_TERMINATION_SNAPSHOT_SIZE)
            {
                throw dataError();
            }
            Map<String, List<Execution>> executionsByActivity = new LinkedHashMap<>();
            for (Execution execution : executions)
            {
                if (execution == null || !StringUtils.hasText(execution.getId())
                        || !Objects.equals(processInstanceId,
                                execution.getProcessInstanceId()))
                {
                    throw dataError();
                }
                if (StringUtils.hasText(execution.getActivityId()))
                {
                    executionsByActivity.computeIfAbsent(execution.getActivityId(),
                            key -> new java.util.ArrayList<>()).add(execution);
                }
            }
            for (Map.Entry<String, List<Execution>> entry
                    : executionsByActivity.entrySet())
            {
                ControlledNodeDefinition controlledNode = resolveControlledNode(
                        processInstance, entry.getKey());
                if (controlledNode == null)
                {
                    // 普通任务及非受控多实例由 Flowable 原生管理，不要求正式业务轮次。
                    continue;
                }
                Set<String> groupExecutionIds = entry.getValue().stream()
                        .map(Execution::getId).collect(java.util.stream.Collectors.toSet());
                List<Execution> rootCandidates = entry.getValue().stream()
                        .filter(execution -> !groupExecutionIds.contains(
                                execution.getParentId())).toList();
                if (rootCandidates.size() != 1)
                {
                    throw dataError();
                }
                Execution rootExecution = rootCandidates.get(0);
                for (Execution execution : entry.getValue())
                {
                    if (!Objects.equals(rootExecution.getId(), execution.getId())
                            && !Objects.equals(rootExecution.getId(),
                                    execution.getParentId()))
                    {
                        // 受控多实例 child 必须全部直接挂到唯一根，禁止嵌套或分叉 execution 冒充。
                        throw dataError();
                    }
                }
                ControlledTaskContext context = requireControlledRootContext(
                        processInstance, rootExecution, controlledNode);
                EngineSnapshot engine = loadEngineSnapshot(context);
                requireActiveRootCounts(engine);
                if (entry.getValue().size() - 1 != engine.counts().active())
                {
                    throw dataError();
                }
                ActiveControlledRoot previous = activeRoots.putIfAbsent(
                        rootExecution.getId(),
                        new ActiveControlledRoot(context, engine));
                if (previous != null)
                {
                    throw dataError();
                }
            }
        }
        return Map.copyOf(activeRoots);
    }

    /**
     * 从流程实例部署模型解析 activity 是否为受控多实例用户任务。
     *
     * @param processInstance ProcessInstance，activity 所属仍活动的流程实例
     * @param activityId String，execution 图分组的当前 BPMN 活动主键
     * @return ControlledNodeDefinition，受控节点部署与模式事实；普通节点返回 null
     */
    private ControlledNodeDefinition resolveControlledNode(
            ProcessInstance processInstance, String activityId)
    {
        if (processInstance == null
                || !StringUtils.hasText(processInstance.getId())
                || !StringUtils.hasText(processInstance.getProcessDefinitionId())
                || !StringUtils.hasText(activityId))
        {
            throw dataError();
        }
        return resolveControlledNode(processInstance.getProcessDefinitionId(),
                activityId);
    }

    /**
     * 从已知流程定义解析 activity 是否为受控多实例用户任务。
     *
     * @param processDefinitionId String，取消事件或活动实例关联的部署流程定义主键
     * @param activityId String，待解析的 BPMN 活动主键
     * @return ControlledNodeDefinition，受控节点部署与模式事实；普通节点返回 null
     */
    private ControlledNodeDefinition resolveControlledNode(String processDefinitionId,
            String activityId)
    {
        if (!StringUtils.hasText(processDefinitionId)
                || !StringUtils.hasText(activityId))
        {
            throw dataError();
        }
        ProcessDefinition definition;
        BpmnModel model;
        try
        {
            definition = repositoryService.getProcessDefinition(
                    processDefinitionId);
            model = repositoryService.getBpmnModel(
                    processDefinitionId);
        }
        catch (FlowableObjectNotFoundException exception)
        {
            throw dataError(exception);
        }
        if (definition == null || model == null
                || !StringUtils.hasText(definition.getDeploymentId())
                || !StringUtils.hasText(definition.getKey()))
        {
            throw dataError();
        }
        org.flowable.bpmn.model.Process process = model.getProcessById(definition.getKey());
        FlowElement element = process == null ? null
                : process.getFlowElement(activityId, true);
        if (!(element instanceof UserTask userTask)
                || !WorkflowMultiInstanceModelContract.usesControlledHandler(
                        userTask.getLoopCharacteristics()))
        {
            return null;
        }
        WorkflowMultiInstanceMode modelMode;
        try
        {
            modelMode = WorkflowMultiInstanceModelContract.requireMode(userTask);
        }
        catch (IllegalArgumentException exception)
        {
            throw dataError(exception);
        }
        return new ControlledNodeDefinition(definition.getDeploymentId(), modelMode);
    }

    /**
     * 从 Flowable 根取消事件定位部署模型和仍存在的多实例根 execution。
     *
     * @param event FlowableActivityCancelledEvent，引擎同步派发的普通或专用根取消事件
     * @param rootExecution ExecutionEntity，当前 CommandContext 解析出的多实例根
     * @return ControlledTaskContext，普通多实例返回 null；受控根返回可供最终快照对账的上下文
     */
    private ControlledTaskContext resolveCancelledControlledRoot(
            FlowableActivityCancelledEvent event, ExecutionEntity rootExecution)
    {
        if (event == null || rootExecution == null
                || !StringUtils.hasText(event.getExecutionId())
                || !StringUtils.hasText(event.getProcessInstanceId())
                || !StringUtils.hasText(event.getProcessDefinitionId())
                || !StringUtils.hasText(event.getActivityId())
                || !StringUtils.hasText(event.getActivityType()))
        {
            throw dataError();
        }
        if (!"userTask".equals(event.getActivityType()))
        {
            // 原生 CallActivity、SubProcess 等也可能是多实例根，全局监听不得要求它们存在业务轮次。
            return null;
        }
        ProcessInstance processInstance = runtimeService.createProcessInstanceQuery()
                .processInstanceId(event.getProcessInstanceId()).singleResult();
        if (processInstance == null || processInstance.isSuspended()
                || !Objects.equals(event.getProcessDefinitionId(),
                        processInstance.getProcessDefinitionId()))
        {
            throw dataError();
        }
        ControlledNodeDefinition controlledNode = resolveControlledNode(processInstance,
                event.getActivityId());
        if (controlledNode == null)
        {
            // 全局事件也覆盖未受控原生多实例；它们没有业务轮次，必须保持纯引擎语义。
            return null;
        }
        if (!rootExecution.isMultiInstanceRoot()
                || !Objects.equals(event.getExecutionId(), rootExecution.getId())
                || !Objects.equals(event.getActivityId(), rootExecution.getActivityId())
                || !Objects.equals(event.getProcessInstanceId(),
                        rootExecution.getProcessInstanceId())
                || !Objects.equals(event.getProcessDefinitionId(),
                        rootExecution.getProcessDefinitionId()))
        {
            throw dataError();
        }
        if (rootExecution.isSuspended())
        {
            throw dataError();
        }
        // 取消事件可能在根已标记 ended/deleted 后才派发；CommandContext 的
        // isMultiInstanceRoot 与事件关联是此处可靠身份，不能再套用“活动根”校验。
        return new ControlledTaskContext(controlledNode.deployId(),
                processInstance.getProcessDefinitionId(), processInstance.getId(),
                rootExecution.getActivityId(), rootExecution,
                controlledNode.modelMode());
    }

    /**
     * 校验已删除流程实例中的残余开放轮次仍属于其部署定义和受控节点。
     *
     * @param event FlowableCancelledEvent，已核对流程实例主键的 PROCESS_CANCELLED 事件
     * @param round WfMultiInstanceRound，准备按来源状态与 revision CAS 关闭的开放轮次
     * @return void，部署、定义、节点、模式、成员或生命周期漂移时抛出服务端数据异常
     */
    private void requireCancelledProcessRound(FlowableCancelledEvent event,
            WfMultiInstanceRound round)
    {
        requireValidRound(round);
        decodeMembers(round);
        if (round.getRoundStatus() == null || !round.getRoundStatus().isOpen()
                || !round.getRoundStatus().canTransitionTo(
                        WorkflowMultiInstanceRoundStatus.TERMINATED)
                || !Objects.equals(event.getProcessInstanceId(),
                        round.getProcessInstanceId())
                || !Objects.equals(event.getProcessDefinitionId(),
                        round.getProcessDefinitionId()))
        {
            throw dataError();
        }
        ControlledNodeDefinition controlledNode = resolveControlledNode(
                event.getProcessDefinitionId(), round.getActivityId());
        if (controlledNode == null
                || !Objects.equals(controlledNode.deployId(), round.getDeployId())
                || !Objects.equals(controlledNode.modelMode().name(), round.getMode()))
        {
            // 引擎根已删除后不能再猜运行变量，只接受与部署 BPMN 固定事实完全一致的正式快照。
            throw dataError();
        }
    }

    /**
     * 校验 execution 图解析出的唯一顶层执行可作为活动受控多实例根。
     *
     * @param processInstance ProcessInstance，根 execution 所属活动流程实例
     * @param rootExecution Execution，同 activity execution 图的唯一顶层执行
     * @param controlledNode ControlledNodeDefinition，部署模型解析出的受控节点事实
     * @return ControlledTaskContext，可供实时变量和正式轮次严格对账的根上下文
     */
    private ControlledTaskContext requireControlledRootContext(
            ProcessInstance processInstance, Execution rootExecution,
            ControlledNodeDefinition controlledNode)
    {
        if (rootExecution == null || controlledNode == null
                || !StringUtils.hasText(rootExecution.getId())
                || !StringUtils.hasText(rootExecution.getActivityId())
                || rootExecution.isEnded() || rootExecution.isSuspended()
                || !Objects.equals(processInstance.getId(),
                        rootExecution.getProcessInstanceId()))
        {
            throw dataError();
        }
        return new ControlledTaskContext(controlledNode.deployId(),
                processInstance.getProcessDefinitionId(), processInstance.getId(),
                rootExecution.getActivityId(), rootExecution,
                controlledNode.modelMode());
    }

    /**
     * 比较删除前普通读令牌与删除后加锁 current-read，禁止集合或事实静默漂移。
     *
     * @param expected Map&lt;Long,TerminationRoundFacts&gt;，删除前冻结事实
     * @param locked Map&lt;Long,TerminationRoundFacts&gt;，删除后 FOR UPDATE 当前事实
     * @return void，来源状态竞争返回 409，其他缺行、额外行或字段漂移返回 500
     */
    private void requireLockedTerminationSnapshot(
            Map<Long, TerminationRoundFacts> expected,
            Map<Long, TerminationRoundFacts> locked)
    {
        if (!expected.keySet().equals(locked.keySet()))
        {
            throw dataError();
        }
        for (Map.Entry<Long, TerminationRoundFacts> entry : expected.entrySet())
        {
            TerminationRoundFacts current = locked.get(entry.getKey());
            if (entry.getValue().equals(current))
            {
                continue;
            }
            if (current != null && entry.getValue().sameStableSnapshot(current)
                    && entry.getValue().sourceStatus() != current.sourceStatus())
            {
                throw terminationConflict("流程多实例轮次开放状态已发生变化，请刷新后重试");
            }
            throw dataError();
        }
    }

    /**
     * 校验异常关闭后的每一行均来自冻结集合，且除状态与 terminate_time 外事实完全保留。
     *
     * @param expected Map&lt;Long,TerminationRoundFacts&gt;，删除前冻结的全部开放轮次事实
     * @param terminated List&lt;WfMultiInstanceRound&gt;，批量更新后按主键重新读取的记录
     * @return void，缺行、重复、生命周期或不可变字段漂移时返回服务端数据异常
     */
    private void requireTerminatedRows(Map<Long, TerminationRoundFacts> expected,
            List<WfMultiInstanceRound> terminated)
    {
        if (terminated.size() != expected.size())
        {
            throw dataError();
        }
        Set<Long> verified = new LinkedHashSet<>();
        for (WfMultiInstanceRound round : terminated)
        {
            if (round == null || round.getRoundId() == null
                    || !verified.add(round.getRoundId())
                    || !expected.containsKey(round.getRoundId()))
            {
                throw dataError();
            }
            requireValidRound(round);
            if (!expected.get(round.getRoundId()).matchesTerminated(round))
            {
                throw dataError();
            }
        }
    }

    /**
     * 使用 DelegateTask 完整定位受控节点、部署定义和当前多实例根。
     *
     * @param task DelegateTask，create 或 complete 事件中的真实任务上下文
     * @return ControlledTaskContext，普通任务返回 null，受控任务返回不可变定义与根上下文
     */
    private ControlledTaskContext resolveControlledTask(DelegateTask task)
    {
        if (task == null || !StringUtils.hasText(task.getId())
                || !StringUtils.hasText(task.getProcessInstanceId())
                || !StringUtils.hasText(task.getProcessDefinitionId())
                || !StringUtils.hasText(task.getTaskDefinitionKey())
                || !StringUtils.hasText(task.getExecutionId()))
        {
            throw dataError();
        }
        Execution taskExecution = runtimeService.createExecutionQuery()
                .executionId(task.getExecutionId()).singleResult();
        if (taskExecution == null || !StringUtils.hasText(taskExecution.getParentId()))
        {
            // 普通任务没有多实例父根；是否受控必须先通过部署模型判断，避免把普通 create 当异常。
            return resolveControlledTask(task.getProcessInstanceId(),
                    task.getProcessDefinitionId(), task.getTaskDefinitionKey(), null,
                    task.getExecutionId());
        }
        return resolveControlledTask(task.getProcessInstanceId(),
                task.getProcessDefinitionId(), task.getTaskDefinitionKey(),
                taskExecution.getParentId(), task.getExecutionId());
    }

    /**
     * 使用正式 Task 和调用方已解析的根定位受控节点上下文。
     *
     * @param task Task，多实例正式查询或调整入口中的活动任务
     * @param rootExecutionId String，调用方从当前 task execution 验证出的根主键
     * @return ControlledTaskContext，受控任务的定义、部署、实例、活动和根上下文
     */
    private ControlledTaskContext resolveControlledTask(Task task, String rootExecutionId)
    {
        if (task == null || !StringUtils.hasText(task.getProcessInstanceId())
                || !StringUtils.hasText(task.getProcessDefinitionId())
                || !StringUtils.hasText(task.getTaskDefinitionKey())
                || !StringUtils.hasText(task.getExecutionId())
                || !StringUtils.hasText(rootExecutionId))
        {
            throw dataError();
        }
        return resolveControlledTask(task.getProcessInstanceId(),
                task.getProcessDefinitionId(), task.getTaskDefinitionKey(),
                rootExecutionId, task.getExecutionId());
    }

    /**
     * 解析部署 BPMN 节点并校验当前 execution 根；普通节点返回 null，畸形受控节点失败关闭。
     *
     * @param processInstanceId String，任务所属流程实例主键
     * @param processDefinitionId String，任务所属部署流程定义主键
     * @param activityId String，任务定义节点主键
     * @param rootExecutionId String，可为空的多实例根 execution 主键
     * @param taskExecutionId String，当前任务 execution 主键
     * @return ControlledTaskContext，受控节点上下文；普通节点为 null
     */
    private ControlledTaskContext resolveControlledTask(String processInstanceId,
            String processDefinitionId, String activityId, String rootExecutionId,
            String taskExecutionId)
    {
        ProcessDefinition definition;
        BpmnModel model;
        try
        {
            definition = repositoryService.getProcessDefinition(processDefinitionId);
            model = repositoryService.getBpmnModel(processDefinitionId);
        }
        catch (FlowableObjectNotFoundException exception)
        {
            throw dataError(exception);
        }
        if (definition == null || model == null
                || !StringUtils.hasText(definition.getDeploymentId())
                || !StringUtils.hasText(definition.getKey()))
        {
            throw dataError();
        }
        org.flowable.bpmn.model.Process process = model.getProcessById(definition.getKey());
        FlowElement element = process == null ? null : process.getFlowElement(activityId, true);
        if (!(element instanceof UserTask userTask))
        {
            throw dataError();
        }
        if (!WorkflowMultiInstanceModelContract.usesControlledHandler(
                userTask.getLoopCharacteristics()))
        {
            return null;
        }
        WorkflowMultiInstanceMode modelMode;
        try
        {
            modelMode = WorkflowMultiInstanceModelContract.requireMode(userTask);
        }
        catch (IllegalArgumentException exception)
        {
            throw dataError(exception);
        }
        if (!StringUtils.hasText(rootExecutionId))
        {
            throw dataError();
        }
        Execution taskExecution = runtimeService.createExecutionQuery()
                .executionId(taskExecutionId).singleResult();
        Execution rootExecution = runtimeService.createExecutionQuery()
                .executionId(rootExecutionId).singleResult();
        if (taskExecution == null || taskExecution.isEnded() || taskExecution.isSuspended()
                || !Objects.equals(rootExecutionId, taskExecution.getParentId())
                || !Objects.equals(processInstanceId, taskExecution.getProcessInstanceId())
                || !Objects.equals(activityId, taskExecution.getActivityId())
                || rootExecution == null || rootExecution.isEnded()
                || rootExecution.isSuspended()
                || !Objects.equals(processInstanceId, rootExecution.getProcessInstanceId())
                || !Objects.equals(activityId, rootExecution.getActivityId()))
        {
            throw dataError();
        }
        return new ControlledTaskContext(definition.getDeploymentId(),
                processDefinitionId, processInstanceId, activityId, rootExecution,
                modelMode);
    }

    /**
     * 从流程实例变量和多实例根局部变量读取实时成员、模式、revision 与计数。
     *
     * @param context ControlledTaskContext，已经验证部署模型和 execution 根的受控上下文
     * @return EngineSnapshot，严格类型化的实时 Flowable 状态
     */
    private EngineSnapshot loadEngineSnapshot(ControlledTaskContext context)
    {
        return loadEngineSnapshot(context, true);
    }

    /**
     * 从流程实例变量读取固定快照，并按事件时序选择是否读取多实例根局部计数。
     *
     * @param context ControlledTaskContext，已经验证部署模型和 execution 根的受控上下文
     * @param includeRootCounts boolean，根 execution 局部变量仍存在时为 true
     * @return EngineSnapshot，成员、模式、revision 完整，计数可按事件时序为空
     */
    private EngineSnapshot loadEngineSnapshot(ControlledTaskContext context,
            boolean includeRootCounts)
    {
        Object rawMembers = runtimeService.getVariable(context.processInstanceId(),
                WorkflowMultiInstanceVariables.memberSnapshotName(context.activityId()));
        List<String> members;
        try
        {
            members = WfMultiInstanceRound.decodeMembers(
                    WfMultiInstanceRound.encodeMembers(requireStringMembers(rawMembers)));
        }
        catch (IllegalArgumentException exception)
        {
            throw dataError(exception);
        }
        int revision = requireNonNegativeInteger(runtimeService.getVariable(
                context.processInstanceId(),
                WorkflowMultiInstanceVariables.revisionName(context.activityId())));
        WorkflowMultiInstanceMode mode = requireMode(runtimeService.getVariable(
                context.processInstanceId(),
                WorkflowMultiInstanceVariables.modeName(context.activityId())));
        if (mode != context.modelMode())
        {
            // 模式必须同时与部署模型、Flowable 变量和业务行一致，禁止成对篡改变量与业务快照。
            throw dataError();
        }
        EngineCounts counts = null;
        if (includeRootCounts)
        {
            counts = new EngineCounts(
                    requireNonNegativeInteger(runtimeService.getVariableLocal(
                            context.rootExecution().getId(), NUMBER_OF_INSTANCES)),
                    requireNonNegativeInteger(runtimeService.getVariableLocal(
                            context.rootExecution().getId(), NUMBER_OF_ACTIVE_INSTANCES)),
                    requireNonNegativeInteger(runtimeService.getVariableLocal(
                            context.rootExecution().getId(), NUMBER_OF_COMPLETED_INSTANCES)));
        }
        return new EngineSnapshot(mode, members, revision, counts);
    }

    /**
     * 把 Flowable 成员集合限定为字符串列表，具体规范性复用正式 JSON 编解码器校验。
     *
     * @param rawMembers Object，流程实例成员快照变量原值
     * @return List&lt;String&gt;，保持引擎顺序的成员文本列表
     */
    private List<String> requireStringMembers(Object rawMembers)
    {
        if (!(rawMembers instanceof List<?> values))
        {
            throw new IllegalArgumentException("多实例成员变量类型不合法");
        }
        return values.stream().map(value ->
        {
            if (!(value instanceof String member))
            {
                throw new IllegalArgumentException("多实例成员变量元素类型不合法");
            }
            return member;
        }).toList();
    }

    /**
     * 查询并校验同实例节点唯一开放且唯一 ACTIVE 的当前轮次。
     *
     * @param context ControlledTaskContext，当前受控实例和活动上下文
     * @return WfMultiInstanceRound，唯一开放 ACTIVE 轮次
     */
    private WfMultiInstanceRound requireSingleCurrentActiveRound(
            ControlledTaskContext context)
    {
        List<WfMultiInstanceRound> openRounds = requireRows(
                roundMapper.selectOpenByProcessInstanceAndActivity(
                        context.processInstanceId(), context.activityId()));
        List<WfMultiInstanceRound> activeRounds = requireRows(
                roundMapper.selectActiveByProcessInstanceAndActivity(
                        context.processInstanceId(), context.activityId()));
        if (openRounds.size() != 1 || activeRounds.size() != 1
                || !Objects.equals(openRounds.get(0).getRoundId(),
                        activeRounds.get(0).getRoundId()))
        {
            throw dataError();
        }
        requireValidRound(openRounds.get(0));
        requireValidRound(activeRounds.get(0));
        return activeRounds.get(0);
    }

    /**
     * 核对当前轮次静态标识、ACTIVE 状态、成员、revision 和模式。
     *
     * @param context ControlledTaskContext，部署、实例、活动和根事实
     * @param engine EngineSnapshot，实时 Flowable 成员、模式与 revision
     * @param round WfMultiInstanceRound，按根或当前活动查询到的业务轮次
     * @return void，任一字段漂移时抛出服务端数据异常
     */
    private void requireCurrentActiveRound(ControlledTaskContext context,
            EngineSnapshot engine, WfMultiInstanceRound round)
    {
        WfMultiInstanceRound current = requireSingleCurrentActiveRound(context);
        if (round == null || !Objects.equals(round.getRoundId(), current.getRoundId()))
        {
            throw dataError();
        }
        requireRoundIdentity(context, round);
        requireRoundSnapshot(round, engine.members(), engine.revision(), engine.mode(),
                WorkflowMultiInstanceRoundStatus.ACTIVE);
    }

    /**
     * 核对轮次不可变部署、流程、活动和根标识。
     *
     * @param context ControlledTaskContext，当前部署和执行树事实
     * @param round WfMultiInstanceRound，待核验的业务轮次
     * @return void，缺行或静态字段漂移时抛出服务端数据异常
     */
    private void requireRoundIdentity(ControlledTaskContext context,
            WfMultiInstanceRound round)
    {
        if (round == null)
        {
            throw dataError();
        }
        requireValidRound(round);
        if (!Objects.equals(context.deployId(), round.getDeployId())
                || !Objects.equals(context.processDefinitionId(),
                        round.getProcessDefinitionId())
                || !Objects.equals(context.processInstanceId(),
                        round.getProcessInstanceId())
                || !Objects.equals(context.activityId(), round.getActivityId())
                || !Objects.equals(context.rootExecution().getId(),
                        round.getRootExecutionId()))
        {
            throw dataError();
        }
    }

    /**
     * 核对轮次可变快照字段和生命周期状态。
     *
     * @param round WfMultiInstanceRound，待核验轮次
     * @param members List&lt;String&gt;，Flowable 实时有序成员
     * @param revision int，Flowable 实时 revision
     * @param mode WorkflowMultiInstanceMode，部署与变量一致的固定模式
     * @param status WorkflowMultiInstanceRoundStatus，动作阶段要求的状态
     * @return void，任一快照字段漂移时抛出服务端数据异常
     */
    private void requireRoundSnapshot(WfMultiInstanceRound round, List<String> members,
            int revision, WorkflowMultiInstanceMode mode,
            WorkflowMultiInstanceRoundStatus status)
    {
        if (!decodeMembers(round).equals(members)
                || round.getRevisionNo() == null || round.getRevisionNo() != revision
                || !Objects.equals(round.getMode(), mode.name())
                || round.getRoundStatus() != status)
        {
            throw dataError();
        }
    }

    /**
     * 严格解码轮次成员 JSON，并把格式异常统一为服务端数据异常。
     *
     * @param round WfMultiInstanceRound，含正式成员 JSON 的轮次
     * @return List&lt;String&gt;，保持持久化顺序的规范用户主键
     */
    private List<String> decodeMembers(WfMultiInstanceRound round)
    {
        try
        {
            return WfMultiInstanceRound.decodeMembers(round.getMembersJson());
        }
        catch (IllegalArgumentException exception)
        {
            throw dataError(exception);
        }
    }

    /**
     * 执行实体完整生命周期组合校验，并把非法持久化值统一为服务端数据异常。
     *
     * @param round WfMultiInstanceRound，待校验业务轮次
     * @return void，非法字段组合不会进入运行时决策
     */
    private void requireValidRound(WfMultiInstanceRound round)
    {
        try
        {
            round.requireValidLifecycle();
        }
        catch (IllegalArgumentException | IllegalStateException exception)
        {
            throw dataError(exception);
        }
    }

    /**
     * 校验 Mapper 列表查询没有返回 null 元素，并保持数据库顺序。
     *
     * @param rows List&lt;WfMultiInstanceRound&gt;，Mapper 查询结果
     * @return List&lt;WfMultiInstanceRound&gt;，不可修改且无 null 的轮次列表
     */
    private List<WfMultiInstanceRound> requireRows(List<WfMultiInstanceRound> rows)
    {
        if (rows == null || rows.stream().anyMatch(Objects::isNull))
        {
            throw dataError();
        }
        return List.copyOf(rows);
    }

    /**
     * 校验 create/complete 任务的最终办理人属于正式成员快照。
     *
     * @param assignee String，参与者规则解析后的真实办理人
     * @param members List&lt;String&gt;，本轮有序正式成员
     * @return void，空办理人或成员漂移时抛出服务端数据异常
     */
    private void requireTaskMember(String assignee, List<String> members)
    {
        if (!StringUtils.hasText(assignee) || !members.contains(assignee))
        {
            throw dataError();
        }
    }

    /**
     * 校验活动受控多实例根的三个实时计数与成员快照严格闭合。
     *
     * @param engine EngineSnapshot，完成监听或异常终止预检读取的实时 Flowable 状态
     * @return void，计数缺失、空组或数量漂移时抛出服务端数据异常
     */
    private void requireActiveRootCounts(EngineSnapshot engine)
    {
        EngineCounts counts = engine.counts();
        if (counts == null || counts.instances() != engine.members().size()
                || counts.active() < 1 || counts.completed() < 0
                || counts.active() + counts.completed() != counts.instances())
        {
            throw dataError();
        }
        if (engine.mode() == WorkflowMultiInstanceMode.ANY && counts.completed() != 0)
        {
            // ANY 在首个完成后立即离开整组，仍存在已完成 child 的活动根属于引擎漂移。
            throw dataError();
        }
    }

    /**
     * 按固定 ALL/ANY 规则判断当前 complete 是否结束整组。
     *
     * @param engine EngineSnapshot，本次完成尚未应用前的真实根计数和模式
     * @return boolean，ANY 恒在本次结束；ALL 仅最后一个活动实例结束
     */
    private boolean completesGroup(EngineSnapshot engine)
    {
        if (engine.mode() == WorkflowMultiInstanceMode.ANY)
        {
            return true;
        }
        EngineCounts counts = engine.counts();
        return counts.active() == 1
                && counts.completed() + 1 == counts.instances();
    }

    /**
     * 计算同实例同节点下一轮编号。
     *
     * @param maxRoundNo Integer，数据库现有最大轮次号；首次进入为空
     * @return int，首次为一，后续严格递增一且不溢出
     */
    private int requireNextRoundNo(Integer maxRoundNo)
    {
        if (maxRoundNo == null)
        {
            return 1;
        }
        if (maxRoundNo < 1 || maxRoundNo == Integer.MAX_VALUE)
        {
            throw dataError();
        }
        return maxRoundNo + 1;
    }

    /**
     * 精确读取非负 int 引擎变量，拒绝浮点、负数和溢出。
     *
     * @param value Object，revision 或 nrOf* 变量原值
     * @return int，未发生截断的非负整数
     */
    private int requireNonNegativeInteger(Object value)
    {
        if (!(value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long))
        {
            throw dataError();
        }
        long numeric = ((Number) value).longValue();
        if (numeric < 0 || numeric > Integer.MAX_VALUE)
        {
            throw dataError();
        }
        return (int) numeric;
    }

    /**
     * 严格读取 ALL/ANY 完成模式变量。
     *
     * @param value Object，流程实例模式变量原值
     * @return WorkflowMultiInstanceMode，合法固定模式
     */
    private WorkflowMultiInstanceMode requireMode(Object value)
    {
        if (!(value instanceof String mode))
        {
            throw dataError();
        }
        try
        {
            return WorkflowMultiInstanceMode.valueOf(mode);
        }
        catch (IllegalArgumentException exception)
        {
            throw dataError(exception);
        }
    }

    /**
     * 创建业务表 CAS 输家的稳定 revision 冲突。
     *
     * @return ServiceException，HTTP 409 且携带动态多实例 revision 子码
     */
    private ServiceException revisionConflict()
    {
        return new ServiceException("工作流状态已发生变化，请刷新后重试",
                HttpStatus.CONFLICT).setSubCode(REVISION_CONFLICT_SUB_CODE);
    }

    /**
     * 创建异常终止预检后业务轮次发生并发变化的稳定冲突。
     *
     * @param message String，来源状态或批量影响数发生漂移的服务端提示
     * @return ServiceException，HTTP 409 且当前事务必须回滚 Flowable 删除
     */
    private ServiceException terminationConflict(String message)
    {
        return new ServiceException(message, HttpStatus.CONFLICT);
    }

    /**
     * 创建轮次、部署、变量或 execution 漂移的稳定服务端数据异常。
     *
     * @return ServiceException，HTTP 500 且当前事务必须回滚
     */
    private ServiceException dataError()
    {
        return new ServiceException("工作流多实例轮次状态不一致", HttpStatus.ERROR);
    }

    /**
     * 创建保留原始原因的稳定服务端数据异常。
     *
     * @param cause Throwable，部署读取或持久化值解析的原始异常
     * @return ServiceException，HTTP 500 且 cause 仅供服务端日志定位
     */
    private ServiceException dataError(Throwable cause)
    {
        ServiceException failure = dataError();
        failure.initCause(cause);
        return failure;
    }

    /**
     * 异常终止删除前严格预检生成的不透明令牌，只允许同一服务完成后续加锁关闭。
     */
    public static final class TerminationPrecheck
    {
        /** 完整根及 CallActivity 子实例主键。 */
        private final Set<String> processInstanceIds;

        /** 以轮次主键索引、包含 sourceStatus 的删除前完整事实。 */
        private final Map<Long, TerminationRoundFacts> factsByRoundId;

        /**
         * 冻结异常终止预检事实，调用方只能传递令牌而不能修改内部集合。
         *
         * @param processInstanceIds Set&lt;String&gt;，完整活动流程树实例主键
         * @param factsByRoundId Map&lt;Long,TerminationRoundFacts&gt;，全部开放轮次事实
         * @return 无返回值，构造后的集合均为不可修改副本
         */
        private TerminationPrecheck(Set<String> processInstanceIds,
                Map<Long, TerminationRoundFacts> factsByRoundId)
        {
            this.processInstanceIds = Set.copyOf(processInstanceIds);
            this.factsByRoundId = Map.copyOf(factsByRoundId);
        }
    }

    /**
     * 删除前开放轮次的完整事实，sourceStatus 与退回字段共同参与删除后锁定比对。
     *
     * @param deployId String，部署主键
     * @param processDefinitionId String，流程定义主键
     * @param processInstanceId String，流程实例主键
     * @param activityId String，受控活动主键
     * @param rootExecutionId String，多实例根 execution 主键
     * @param roundNo Integer，节点轮次号
     * @param mode String，ALL/ANY 固定模式
     * @param membersJson String，有序成员 JSON
     * @param revisionNo Integer，实时同步 revision
     * @param sourceStatus WorkflowMultiInstanceRoundStatus，ACTIVE 或 RETURNED 来源状态
     * @param returnSourceTaskId String，可选退回来源任务主键
     * @param returnActorUserId String，可选退回操作人主键
     * @param applicantTaskId String，可选申请人任务主键
     * @param createTime LocalDateTime，数据库创建时间
     * @param returnTime LocalDateTime，可选退回时间
     * @param reopenTime LocalDateTime，开放轮次固定为空
     * @param completeTime LocalDateTime，开放轮次固定为空
     * @param terminateTime LocalDateTime，删除前固定为空
     */
    private record TerminationRoundFacts(String deployId,
            String processDefinitionId, String processInstanceId, String activityId,
            String rootExecutionId, Integer roundNo, String mode, String membersJson,
            Integer revisionNo, WorkflowMultiInstanceRoundStatus sourceStatus,
            String returnSourceTaskId, String returnActorUserId, String applicantTaskId,
            LocalDateTime createTime, LocalDateTime returnTime,
            LocalDateTime reopenTime, LocalDateTime completeTime,
            LocalDateTime terminateTime)
    {
        /**
         * 从已经通过开放生命周期校验的实体冻结全部删除前事实。
         *
         * @param round WfMultiInstanceRound，ACTIVE 或 RETURNED 正式轮次
         * @return TerminationRoundFacts，供普通读、加锁读和写后结果精确比较的值对象
         */
        private static TerminationRoundFacts from(WfMultiInstanceRound round)
        {
            return new TerminationRoundFacts(round.getDeployId(),
                    round.getProcessDefinitionId(), round.getProcessInstanceId(),
                    round.getActivityId(), round.getRootExecutionId(), round.getRoundNo(),
                    round.getMode(), round.getMembersJson(), round.getRevisionNo(),
                    round.getRoundStatus(), round.getReturnSourceTaskId(),
                    round.getReturnActorUserId(), round.getApplicantTaskId(),
                    round.getCreateTime(), round.getReturnTime(), round.getReopenTime(),
                    round.getCompleteTime(), round.getTerminateTime());
        }

        /**
         * 比较不会因 ACTIVE/RETURNED 合法转换而变化的引擎关联与快照字段。
         *
         * @param other TerminationRoundFacts，删除后加锁读取的当前开放事实
         * @return boolean，根、模式、成员、revision 和创建事实全部相同返回 true
         */
        private boolean sameStableSnapshot(TerminationRoundFacts other)
        {
            return other != null
                    && Objects.equals(deployId, other.deployId)
                    && Objects.equals(processDefinitionId, other.processDefinitionId)
                    && Objects.equals(processInstanceId, other.processInstanceId)
                    && Objects.equals(activityId, other.activityId)
                    && Objects.equals(rootExecutionId, other.rootExecutionId)
                    && Objects.equals(roundNo, other.roundNo)
                    && Objects.equals(mode, other.mode)
                    && Objects.equals(membersJson, other.membersJson)
                    && Objects.equals(revisionNo, other.revisionNo)
                    && Objects.equals(createTime, other.createTime);
        }

        /**
         * 校验 TERMINATED 写后行只新增异常关闭状态与数据库 terminate_time，其他字段完整保留。
         *
         * @param round WfMultiInstanceRound，批量异常关闭后重新读取的正式记录
         * @return boolean，状态、专用时间及全部保留字段符合冻结事实时返回 true
         */
        private boolean matchesTerminated(WfMultiInstanceRound round)
        {
            return round.getRoundStatus() == WorkflowMultiInstanceRoundStatus.TERMINATED
                    && round.getTerminateTime() != null
                    && Objects.equals(deployId, round.getDeployId())
                    && Objects.equals(processDefinitionId, round.getProcessDefinitionId())
                    && Objects.equals(processInstanceId, round.getProcessInstanceId())
                    && Objects.equals(activityId, round.getActivityId())
                    && Objects.equals(rootExecutionId, round.getRootExecutionId())
                    && Objects.equals(roundNo, round.getRoundNo())
                    && Objects.equals(mode, round.getMode())
                    && Objects.equals(membersJson, round.getMembersJson())
                    && Objects.equals(revisionNo, round.getRevisionNo())
                    && Objects.equals(returnSourceTaskId,
                            round.getReturnSourceTaskId())
                    && Objects.equals(returnActorUserId,
                            round.getReturnActorUserId())
                    && Objects.equals(applicantTaskId, round.getApplicantTaskId())
                    && Objects.equals(createTime, round.getCreateTime())
                    && Objects.equals(returnTime, round.getReturnTime())
                    && Objects.equals(reopenTime, round.getReopenTime())
                    && Objects.equals(completeTime, round.getCompleteTime());
        }
    }

    /**
     * 一次开放轮次查询的双索引冻结结果。
     *
     * @param factsByRoundId Map&lt;Long,TerminationRoundFacts&gt;，按轮次主键索引的完整事实
     * @param roundsByRootExecutionId Map&lt;String,WfMultiInstanceRound&gt;，按根 execution 索引的实体
     */
    private record OpenRoundSnapshot(Map<Long, TerminationRoundFacts> factsByRoundId,
            Map<String, WfMultiInstanceRound> roundsByRootExecutionId)
    {
    }

    /**
     * 部署模型解析出的受控多实例节点固定事实。
     *
     * @param deployId String，Flowable 部署主键
     * @param modelMode WorkflowMultiInstanceMode，BPMN 固定 ALL/ANY 模式
     */
    private record ControlledNodeDefinition(String deployId,
            WorkflowMultiInstanceMode modelMode)
    {
    }

    /**
     * 活动受控多实例根及其实时 Flowable 快照。
     *
     * @param context ControlledTaskContext，部署、定义、实例、活动与根 execution 事实
     * @param engine EngineSnapshot，模式、有序成员、revision 和根计数
     */
    private record ActiveControlledRoot(ControlledTaskContext context,
            EngineSnapshot engine)
    {
    }

    /**
     * 受控任务不可变定义和执行根上下文。
     *
     * @param deployId String，Flowable 部署主键
     * @param processDefinitionId String，流程定义主键
     * @param processInstanceId String，流程实例主键
     * @param activityId String，受控用户任务活动主键
     * @param rootExecution Execution，当前多实例根 execution
     * @param modelMode WorkflowMultiInstanceMode，部署 BPMN 固定的 ALL/ANY 模式
     */
    private record ControlledTaskContext(String deployId, String processDefinitionId,
            String processInstanceId, String activityId, Execution rootExecution,
            WorkflowMultiInstanceMode modelMode)
    {
    }

    /**
     * Flowable 实时多实例快照。
     *
     * @param mode WorkflowMultiInstanceMode，固定 ALL/ANY 模式
     * @param members List&lt;String&gt;，有序正式成员
     * @param revision int，实时 Flowable revision
     * @param counts EngineCounts，可为空的根计数
     */
    private record EngineSnapshot(WorkflowMultiInstanceMode mode,
            List<String> members, int revision, EngineCounts counts)
    {
    }

    /**
     * complete 事件发生前的 Flowable 多实例根计数。
     *
     * @param instances int，总实例数
     * @param active int，活动实例数
     * @param completed int，已完成实例数
     */
    private record EngineCounts(int instances, int active, int completed)
    {
    }
}
