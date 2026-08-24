package com.ruoyi.flowable.service.task;

import java.util.List;
import org.springframework.stereotype.Service;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WorkflowMultiInstanceRoundStatus;

/**
 * ACTIVE 多实例轮次创建、完成、成员快照 CAS 和完成后对账服务。
 */
@Service
public class WorkflowMultiInstanceRoundLifecycleService
{
    private final WorkflowMultiInstanceRoundRepository roundRepository;

    private final WorkflowMultiInstanceRuntimeSnapshotReader snapshotReader;

    private final WorkflowMultiInstanceTransitionObserver transitionObserver;

    /**
     * 创建 ACTIVE 轮次生命周期服务。
     *
     * @param roundRepository WorkflowMultiInstanceRoundRepository，轮次持久化边界
     * @param snapshotReader WorkflowMultiInstanceRuntimeSnapshotReader，唯一实时快照读取器
     * @param transitionObserver WorkflowMultiInstanceTransitionObserver，命令内创建事件观察器
     * @return 无返回值，构造后由 Spring 管理
     */
    public WorkflowMultiInstanceRoundLifecycleService(
            WorkflowMultiInstanceRoundRepository roundRepository,
            WorkflowMultiInstanceRuntimeSnapshotReader snapshotReader,
            WorkflowMultiInstanceTransitionObserver transitionObserver)
    {
        this.roundRepository = roundRepository;
        this.snapshotReader = snapshotReader;
        this.transitionObserver = transitionObserver;
    }

    /**
     * 在任务 create 事件中创建首个 ACTIVE 轮次或核对同根任务复用。
     *
     * @param event WorkflowTaskEventSnapshot，监听器冻结的任务事件事实
     * @return void，普通任务不写轮次；漂移时中止当前 Flowable 事务
     */
    public void onTaskCreated(WorkflowTaskEventSnapshot event)
    {
        ControlledMultiInstanceRootSnapshot root = readEventRoot(event);
        if (root == null)
        {
            return;
        }
        if (transitionObserver.observeTemporaryTask(root.processInstanceId(),
                root.activityId(), root.rootExecutionId(), event.taskId(),
                event.assignee()))
        {
            return;
        }
        requireTaskMember(event.assignee(), root.members());
        MultiInstanceRoundSnapshot existing = roundRepository.findByRootExecutionId(
                root.rootExecutionId());
        if (existing != null)
        {
            requireCurrentActiveRound(root, existing);
            transitionObserver.observeReopenedTask(root.processInstanceId(),
                    root.activityId(), root.rootExecutionId(), event.assignee());
            return;
        }
        if (!roundRepository.findOpen(root.processInstanceId(), root.activityId()).isEmpty()
                || !roundRepository.findActive(
                        root.processInstanceId(), root.activityId()).isEmpty())
        {
            throw dataError();
        }
        Integer maxRoundNo = roundRepository.findMaxRoundNo(root.processInstanceId(),
                root.activityId());
        int roundNo = maxRoundNo == null ? 1 : nextRoundNo(maxRoundNo);
        roundRepository.insertActive(root, roundNo);
        requireCurrentActiveRound(root, roundRepository.findByRootExecutionId(
                root.rootExecutionId()));
        transitionObserver.observeReopenedTask(root.processInstanceId(),
                root.activityId(), root.rootExecutionId(), event.assignee());
    }

    /**
     * 在任务 complete 监听事件中核验预留 revision 并按 ALL/ANY 关闭整组。
     *
     * @param event WorkflowTaskEventSnapshot，含任务局部预留 revision 的完成事件
     * @return void，部分完成保持 ACTIVE，整组结束转为 COMPLETED
     */
    public void onTaskCompleted(WorkflowTaskEventSnapshot event)
    {
        ControlledMultiInstanceSnapshot runtime = readEvent(event);
        if (runtime == null)
        {
            return;
        }
        requireTaskMember(event.assignee(), runtime.members());
        requireActiveCounts(runtime);
        MultiInstanceRoundSnapshot round = requireActiveRound(runtime);
        if (event.completionRevision() == null
                || event.completionRevision() != runtime.revision()
                || round.revision() != runtime.revision())
        {
            throw dataError();
        }
        boolean completed = completesGroup(runtime);
        if (completed)
        {
            roundRepository.compareAndSetCompleted(round.roundId(),
                    runtime.revision(), runtime.members());
        }
        MultiInstanceRoundSnapshot updated = roundRepository.findByRootExecutionId(
                runtime.rootExecutionId());
        requireRoundIdentity(runtime, updated);
        WorkflowMultiInstanceRoundStatus expected = completed
                ? WorkflowMultiInstanceRoundStatus.COMPLETED
                : WorkflowMultiInstanceRoundStatus.ACTIVE;
        if (updated.status() != expected
                || updated.revision() != runtime.revision()
                || updated.mode() != runtime.mode()
                || !updated.members().equals(runtime.members()))
        {
            throw dataError();
        }
    }

    /**
     * 读取唯一 ACTIVE 轮次并与完整实时快照逐项对账。
     *
     * @param runtime ControlledMultiInstanceSnapshot，唯一读取器返回的实时事实
     * @return MultiInstanceRoundSnapshot，完整一致的正式 ACTIVE 轮次
     */
    public MultiInstanceRoundSnapshot requireActiveRound(
            ControlledMultiInstanceSnapshot runtime)
    {
        if (runtime == null)
        {
            throw dataError();
        }
        List<MultiInstanceRoundSnapshot> open = roundRepository.findOpen(
                runtime.processInstanceId(), runtime.activityId());
        List<MultiInstanceRoundSnapshot> active = roundRepository.findActive(
                runtime.processInstanceId(), runtime.activityId());
        if (open.size() != 1 || active.size() != 1
                || open.get(0).roundId() != active.get(0).roundId())
        {
            throw dataError();
        }
        MultiInstanceRoundSnapshot round = active.get(0);
        requireCurrentActiveRound(runtime, round);
        return round;
    }

    /**
     * Flowable revision 推进后以旧 revision CAS 更新 ACTIVE 轮次成员快照。
     *
     * @param round MultiInstanceRoundSnapshot，写前正式 ACTIVE 轮次
     * @param expectedRevision int，写前共同 revision
     * @param newRevision int，严格递增一的新 revision
     * @param members List&lt;String&gt;，动作后有序完整成员
     * @return void，CAS 输家返回稳定 409 子码
     */
    public void compareAndSetActiveSnapshot(MultiInstanceRoundSnapshot round,
            int expectedRevision, int newRevision, List<String> members)
    {
        if (round == null || round.status() != WorkflowMultiInstanceRoundStatus.ACTIVE
                || round.revision() != expectedRevision || expectedRevision < 0
                || expectedRevision == Integer.MAX_VALUE
                || newRevision != expectedRevision + 1 || members == null)
        {
            throw dataError();
        }
        roundRepository.compareAndSetActiveSnapshot(round.roundId(), expectedRevision,
                newRevision, members);
    }

    /**
     * taskService.complete 返回后复核完成监听器已经落库目标状态。
     *
     * @param rootExecutionId String，完成前冻结的多实例根主键
     * @param expectedRevision int，本次完成占用后的 revision
     * @param groupCompleted boolean，本次完成是否结束整组
     * @return MultiInstanceRoundSnapshot，监听器已经同步的目标轮次
     */
    public MultiInstanceRoundSnapshot requireCompletionPersisted(
            String rootExecutionId, int expectedRevision, boolean groupCompleted)
    {
        MultiInstanceRoundSnapshot round = roundRepository.findByRootExecutionId(
                rootExecutionId);
        WorkflowMultiInstanceRoundStatus expected = groupCompleted
                ? WorkflowMultiInstanceRoundStatus.COMPLETED
                : WorkflowMultiInstanceRoundStatus.ACTIVE;
        if (round.revision() != expectedRevision || round.status() != expected)
        {
            throw dataError();
        }
        return round;
    }

    /**
     * 对账 ACTIVE 轮次与实时身份、成员、模式和 revision。
     *
     * @param runtime ControlledMultiInstanceSnapshot，实时快照
     * @param round MultiInstanceRoundSnapshot，正式轮次快照
     * @return void，任一事实漂移时中止事务
     */
    private void requireCurrentActiveRound(ControlledMultiInstanceSnapshot runtime,
            MultiInstanceRoundSnapshot round)
    {
        requireRoundIdentity(runtime, round);
        if (round.status() != WorkflowMultiInstanceRoundStatus.ACTIVE
                || round.mode() != runtime.mode()
                || round.revision() != runtime.revision()
                || !round.members().equals(runtime.members()))
        {
            throw dataError();
        }
    }

    /**
     * 在 Flowable 仍逐个创建成员任务时，只使用已经稳定的根局部事实对账 ACTIVE 轮次。
     *
     * @param root ControlledMultiInstanceRootSnapshot，定义、根、成员、模式和 revision 快照
     * @param round MultiInstanceRoundSnapshot，已写入或已存在的正式轮次
     * @return void，任一稳定事实漂移时中止当前 Flowable 命令
     */
    private void requireCurrentActiveRound(ControlledMultiInstanceRootSnapshot root,
            MultiInstanceRoundSnapshot round)
    {
        if (round == null || round.status() != WorkflowMultiInstanceRoundStatus.ACTIVE
                || !round.deployId().equals(root.deployId())
                || !round.processDefinitionId().equals(root.processDefinitionId())
                || !round.processInstanceId().equals(root.processInstanceId())
                || !round.activityId().equals(root.activityId())
                || !round.rootExecutionId().equals(root.rootExecutionId())
                || round.mode() != root.mode()
                || round.revision() != root.revision()
                || !round.members().equals(root.members()))
        {
            throw dataError();
        }
    }

    /**
     * 对账轮次部署、定义、实例、节点和根身份。
     *
     * @param runtime ControlledMultiInstanceSnapshot，实时快照
     * @param round MultiInstanceRoundSnapshot，正式轮次快照
     * @return void，身份漂移时中止事务
     */
    private void requireRoundIdentity(ControlledMultiInstanceSnapshot runtime,
            MultiInstanceRoundSnapshot round)
    {
        if (round == null || !round.deployId().equals(runtime.deployId())
                || !round.processDefinitionId().equals(runtime.processDefinitionId())
                || !round.processInstanceId().equals(runtime.processInstanceId())
                || !round.activityId().equals(runtime.activityId())
                || !round.rootExecutionId().equals(runtime.rootExecutionId()))
        {
            throw dataError();
        }
    }

    /**
     * 校验活动根计数与 ALL/ANY 活动态规则。
     *
     * @param runtime ControlledMultiInstanceSnapshot，实时快照
     * @return void，空组或 ANY 残留已完成 child 时失败
     */
    private void requireActiveCounts(ControlledMultiInstanceSnapshot runtime)
    {
        if (runtime.counts().active() < 1
                || (runtime.mode() == WorkflowMultiInstanceMode.ANY
                        && runtime.counts().completed() != 0))
        {
            throw dataError();
        }
    }

    /**
     * 按固定 ALL/ANY 规则判断当前完成是否结束整组。
     *
     * @param runtime ControlledMultiInstanceSnapshot，本次完成应用前快照
     * @return boolean，ANY 恒结束；ALL 仅最后一个活动实例结束
     */
    private boolean completesGroup(ControlledMultiInstanceSnapshot runtime)
    {
        return runtime.mode() == WorkflowMultiInstanceMode.ANY
                || (runtime.counts().active() == 1
                        && runtime.counts().completed() + 1
                                == runtime.counts().instances());
    }

    /**
     * 校验任务办理人属于正式成员快照。
     *
     * @param assignee String，事件办理人
     * @param members List&lt;String&gt;，正式成员
     * @return void，空办理人或越权成员时失败
     */
    private void requireTaskMember(String assignee, List<String> members)
    {
        if (assignee == null || !members.contains(assignee))
        {
            throw dataError();
        }
    }

    /**
     * 计算下一轮编号并阻止溢出。
     *
     * @param current int，现有最大轮次号
     * @return int，严格递增一的新轮次号
     */
    private int nextRoundNo(int current)
    {
        if (current < 1 || current == Integer.MAX_VALUE)
        {
            throw dataError();
        }
        return current + 1;
    }

    /**
     * 调用唯一读取器并保持轮次服务原有稳定错误。
     *
     * @param event WorkflowTaskEventSnapshot，监听事件
     * @return ControlledMultiInstanceSnapshot，普通任务返回 null
     */
    private ControlledMultiInstanceSnapshot readEvent(WorkflowTaskEventSnapshot event)
    {
        return WorkflowMultiInstanceSnapshotExceptionTranslator.asRoundDataError(
                () -> snapshotReader.readEvent(event));
    }

    /**
     * 调用唯一读取器取得 create 事件根事实并保持轮次服务稳定错误。
     *
     * @param event WorkflowTaskEventSnapshot，监听事件
     * @return ControlledMultiInstanceRootSnapshot，普通任务返回 null
     */
    private ControlledMultiInstanceRootSnapshot readEventRoot(
            WorkflowTaskEventSnapshot event)
    {
        return WorkflowMultiInstanceSnapshotExceptionTranslator.asRoundDataError(
                () -> snapshotReader.readEventRoot(event));
    }

    /**
     * 创建轮次状态不一致的稳定服务端错误。
     *
     * @return ServiceException，HTTP 500 且当前事务必须回滚
     */
    private ServiceException dataError()
    {
        return new ServiceException("工作流多实例轮次状态不一致", HttpStatus.ERROR);
    }

}
