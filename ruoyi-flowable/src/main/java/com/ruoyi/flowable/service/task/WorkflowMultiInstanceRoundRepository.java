package com.ruoyi.flowable.service.task;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Repository;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfMultiInstanceRound;
import com.ruoyi.flowable.domain.WorkflowMultiInstanceRoundStatus;
import com.ruoyi.flowable.mapper.WfMultiInstanceRoundMapper;

/**
 * 多实例轮次 Mapper 访问、实体转换和条件 CAS 的唯一持久化边界。
 */
@Repository
public class WorkflowMultiInstanceRoundRepository
{
    private final WfMultiInstanceRoundMapper roundMapper;

    /**
     * 创建轮次持久化边界。
     *
     * @param roundMapper WfMultiInstanceRoundMapper，正式轮次 Mapper
     * @return 无返回值，构造后由 Spring 管理
     */
    public WorkflowMultiInstanceRoundRepository(WfMultiInstanceRoundMapper roundMapper)
    {
        this.roundMapper = roundMapper;
    }

    /**
     * 创建 ACTIVE 轮次并返回 Mapper 回填主键后的不可变快照。
     *
     * @param root ControlledMultiInstanceRootSnapshot，已核验的引擎根事实
     * @param roundNo int，同实例同节点严格递增的轮次号
     * @return MultiInstanceRoundSnapshot，刚写入的 ACTIVE 轮次
     */
    public MultiInstanceRoundSnapshot insertActive(
            ControlledMultiInstanceRootSnapshot root, int roundNo)
    {
        if (root == null || roundNo < 1)
        {
            throw dataError();
        }
        WfMultiInstanceRound round = new WfMultiInstanceRound();
        round.setDeployId(root.deployId());
        round.setProcessDefinitionId(root.processDefinitionId());
        round.setProcessInstanceId(root.processInstanceId());
        round.setActivityId(root.activityId());
        round.setRootExecutionId(root.rootExecutionId());
        round.setRoundNo(roundNo);
        round.setMode(root.mode().name());
        round.setMembersJson(WfMultiInstanceRound.encodeMembers(root.members()));
        round.setRevisionNo(root.revision());
        round.setRoundStatus(WorkflowMultiInstanceRoundStatus.ACTIVE);
        if (roundMapper.insert(round) != 1 || round.getRoundId() == null)
        {
            throw dataError();
        }
        // create_time 使用数据库时钟，必须回读正式行后才能形成完整生命周期快照。
        MultiInstanceRoundSnapshot inserted = findByRootExecutionId(
                root.rootExecutionId());
        if (inserted == null || inserted.roundId() != round.getRoundId())
        {
            throw dataError();
        }
        return inserted;
    }

    /**
     * 按根 execution 读取唯一轮次。
     *
     * @param rootExecutionId String，多实例根 execution 主键
     * @return MultiInstanceRoundSnapshot，轮次不存在时返回 null
     */
    public MultiInstanceRoundSnapshot findByRootExecutionId(String rootExecutionId)
    {
        WfMultiInstanceRound row = roundMapper.selectByRootExecutionId(rootExecutionId);
        return row == null ? null : snapshot(row);
    }

    /**
     * 读取同实例同节点的全部开放轮次。
     *
     * @param processInstanceId String，流程实例主键
     * @param activityId String，多实例节点主键
     * @return List&lt;MultiInstanceRoundSnapshot&gt;，不可变且不含 null 的开放轮次
     */
    public List<MultiInstanceRoundSnapshot> findOpen(String processInstanceId,
            String activityId)
    {
        return snapshots(roundMapper.selectOpenByProcessInstanceAndActivity(
                processInstanceId, activityId));
    }

    /**
     * 读取同实例同节点的全部 ACTIVE 轮次。
     *
     * @param processInstanceId String，流程实例主键
     * @param activityId String，多实例节点主键
     * @return List&lt;MultiInstanceRoundSnapshot&gt;，不可变且不含 null 的 ACTIVE 轮次
     */
    public List<MultiInstanceRoundSnapshot> findActive(String processInstanceId,
            String activityId)
    {
        return snapshots(roundMapper.selectActiveByProcessInstanceAndActivity(
                processInstanceId, activityId));
    }

    /**
     * 按申请人待修改任务读取 RETURNED 轮次。
     *
     * @param applicantTaskId String，申请人待修改任务主键
     * @return List&lt;MultiInstanceRoundSnapshot&gt;，不可变且不含 null 的 RETURNED 轮次
     */
    public List<MultiInstanceRoundSnapshot> findReturned(String applicantTaskId)
    {
        return snapshots(roundMapper.selectReturnedByApplicantTaskId(applicantTaskId));
    }

    /**
     * 读取同实例同节点已使用的最大轮次号。
     *
     * @param processInstanceId String，流程实例主键
     * @param activityId String，多实例节点主键
     * @return Integer，尚无轮次时返回 null
     */
    public Integer findMaxRoundNo(String processInstanceId, String activityId)
    {
        return roundMapper.selectMaxRoundNo(processInstanceId, activityId);
    }

    /**
     * 读取指定实例的全部正式轮次，用于退回重提路径按节点选择最近审计事实。
     *
     * @param processInstanceId String，流程实例主键
     * @return List&lt;MultiInstanceRoundSnapshot&gt;，按 Mapper 稳定顺序返回的不可变轮次
     */
    public List<MultiInstanceRoundSnapshot> findByProcessInstanceId(
            String processInstanceId)
    {
        return snapshots(roundMapper.selectByProcessInstanceId(processInstanceId));
    }

    /**
     * 以旧 revision CAS 更新 ACTIVE 成员快照。
     *
     * @param roundId long，轮次主键
     * @param expectedRevision int，写前 revision
     * @param newRevision int，写后 revision
     * @param members List&lt;String&gt;，写后有序成员
     * @return 无返回值，CAS 竞争时抛出稳定 revision 冲突
     */
    public void compareAndSetActiveSnapshot(long roundId, int expectedRevision,
            int newRevision, List<String> members)
    {
        if (roundMapper.compareAndSetActiveSnapshot(roundId, expectedRevision,
                newRevision, WfMultiInstanceRound.encodeMembers(members)) != 1)
        {
            throw revisionConflict();
        }
    }

    /**
     * 以当前 revision CAS 将 ACTIVE 轮次关闭为 COMPLETED。
     *
     * @param roundId long，轮次主键
     * @param expectedRevision int，当前共同 revision
     * @param members List&lt;String&gt;，完成时有序成员快照
     * @return 无返回值，CAS 竞争时抛出稳定 revision 冲突
     */
    public void compareAndSetCompleted(long roundId, int expectedRevision,
            List<String> members)
    {
        if (roundMapper.compareAndSetCompletedStatus(roundId, expectedRevision,
                WfMultiInstanceRound.encodeMembers(members)) != 1)
        {
            throw revisionConflict();
        }
    }

    /**
     * 将 ACTIVE 轮次 CAS 为 RETURNED 并写入退回关联。
     *
     * @param round MultiInstanceRoundSnapshot，写前 ACTIVE 轮次
     * @param sourceTaskId String，退回来源任务主键
     * @param actorUserId String，正式操作人主键
     * @param applicantTaskId String，新申请人任务主键
     * @return 无返回值，CAS 竞争时抛出稳定 revision 冲突
     */
    public void compareAndSetReturned(MultiInstanceRoundSnapshot round,
            String sourceTaskId, String actorUserId, String applicantTaskId)
    {
        if (roundMapper.compareAndSetReturnedStatus(round.roundId(), round.revision(),
                sourceTaskId, actorUserId, applicantTaskId) != 1)
        {
            throw revisionConflict();
        }
    }

    /**
     * 将 RETURNED 轮次按完整退回关联 CAS 为 REOPENED。
     *
     * @param round MultiInstanceRoundSnapshot，写前 RETURNED 轮次
     * @return 无返回值，CAS 竞争时抛出稳定 revision 冲突
     */
    public void compareAndSetReopened(MultiInstanceRoundSnapshot round)
    {
        if (roundMapper.compareAndSetReopenedStatus(round.roundId(), round.revision(),
                round.applicantTaskId(), round.returnSourceTaskId(),
                round.returnActorUserId()) != 1)
        {
            throw revisionConflict();
        }
    }

    /**
     * 将单个开放轮次 CAS 为 TERMINATED。
     *
     * @param round MultiInstanceRoundSnapshot，写前 ACTIVE 或 RETURNED 轮次
     * @return 无返回值，状态竞争时抛出既有异常终止冲突
     */
    public void compareAndSetTerminated(MultiInstanceRoundSnapshot round)
    {
        if (roundMapper.compareAndSetTerminatedStatus(round.roundId(),
                round.revision(), round.status()) != 1)
        {
            throw terminationConflict(
                    "流程多实例轮次异常退出状态已发生变化，请刷新后重试");
        }
    }

    /**
     * 读取实例集合中全部开放轮次，不获取业务行锁。
     *
     * @param processInstanceIds Set&lt;String&gt;，流程树实例主键集合
     * @return List&lt;MultiInstanceRoundSnapshot&gt;，稳定顺序不可变快照
     */
    public List<MultiInstanceRoundSnapshot> findOpenByProcessInstanceIds(
            Set<String> processInstanceIds)
    {
        return snapshots(roundMapper.selectOpenByProcessInstanceIds(processInstanceIds));
    }

    /**
     * 锁定并读取实例集合中全部开放轮次。
     *
     * @param processInstanceIds Set&lt;String&gt;，流程树实例主键集合
     * @return List&lt;MultiInstanceRoundSnapshot&gt;，已取得业务行锁的不可变快照
     */
    public List<MultiInstanceRoundSnapshot> findOpenForUpdate(
            Set<String> processInstanceIds)
    {
        return snapshots(roundMapper.selectOpenForUpdateByProcessInstanceIds(
                processInstanceIds));
    }

    /**
     * 按轮次主键集合读取完整快照。
     *
     * @param roundIds Set&lt;Long&gt;，轮次主键集合
     * @return List&lt;MultiInstanceRoundSnapshot&gt;，稳定顺序不可变快照
     */
    public List<MultiInstanceRoundSnapshot> findByRoundIds(Set<Long> roundIds)
    {
        return snapshots(roundMapper.selectByRoundIds(roundIds));
    }

    /**
     * 批量关闭预检得到的全部开放轮次。
     *
     * @param roundIds Set&lt;Long&gt;，同一事务预检得到的轮次主键集合
     * @return 无返回值，实际关闭数量不一致时抛出既有终止冲突
     */
    public void terminateOpen(Set<Long> roundIds)
    {
        if (roundMapper.terminateOpenByRoundIds(roundIds) != roundIds.size())
        {
            throw terminationConflict("流程多实例轮次异常关闭数量不一致");
        }
    }

    /**
     * 规范 Mapper 列表并转换为不可变轮次快照。
     *
     * @param source List&lt;WfMultiInstanceRound&gt;，Mapper 原始列表
     * @return List&lt;MultiInstanceRoundSnapshot&gt;，不可变且不含 null 的领域快照
     */
    private List<MultiInstanceRoundSnapshot> snapshots(List<WfMultiInstanceRound> source)
    {
        if (source == null || source.stream().anyMatch(Objects::isNull))
        {
            throw dataError();
        }
        return source.stream().map(this::snapshot).toList();
    }

    /**
     * 把 Mapper 实体转换为不可变领域快照。
     *
     * @param entity WfMultiInstanceRound，Mapper 返回实体
     * @return MultiInstanceRoundSnapshot，校验完成的不可变轮次
     */
    private MultiInstanceRoundSnapshot snapshot(WfMultiInstanceRound entity)
    {
        try
        {
            return MultiInstanceRoundSnapshot.from(entity);
        }
        catch (RuntimeException exception)
        {
            ServiceException failure = dataError();
            failure.initCause(exception);
            throw failure;
        }
    }

    /**
     * 创建稳定的轮次 revision CAS 冲突。
     *
     * @return ServiceException，HTTP 409 且包含既有 revision 子码
     */
    private ServiceException revisionConflict()
    {
        return new ServiceException("工作流状态已发生变化，请刷新后重试",
                HttpStatus.CONFLICT).setSubCode(
                        WorkflowMultiInstanceService.REVISION_CONFLICT_SUB_CODE);
    }

    /**
     * 创建稳定的异常终止竞争错误。
     *
     * @param message String，既有终止冲突消息
     * @return ServiceException，HTTP 409 业务异常
     */
    private ServiceException terminationConflict(String message)
    {
        return new ServiceException(message, HttpStatus.CONFLICT);
    }

    /**
     * 创建稳定的轮次数据错误。
     *
     * @return ServiceException，HTTP 500 业务异常
     */
    private ServiceException dataError()
    {
        return new ServiceException("工作流多实例轮次状态不一致", HttpStatus.ERROR);
    }
}
