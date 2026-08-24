package com.ruoyi.flowable.service.task;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.util.StringUtils;
import com.ruoyi.flowable.domain.WfMultiInstanceRound;
import com.ruoyi.flowable.domain.WorkflowMultiInstanceRoundStatus;

/**
 * `wf_multi_instance_round` 一行正式事实的不可变领域投影。
 *
 * @param roundId long，轮次主键
 * @param deployId String，部署主键
 * @param processDefinitionId String，流程定义主键
 * @param processInstanceId String，流程实例主键
 * @param activityId String，受控节点主键
 * @param rootExecutionId String，原审批根 execution 主键
 * @param roundNo int，同实例同节点轮次号
 * @param mode WorkflowMultiInstanceMode，冻结 ALL/ANY 模式
 * @param members List&lt;String&gt;，冻结有序成员
 * @param revision int，冻结 revision
 * @param status WorkflowMultiInstanceRoundStatus，正式轮次状态
 * @param returnSourceTaskId String，可为空的退回来源任务
 * @param returnActorUserId String，可为空的退回操作人
 * @param applicantTaskId String，可为空的申请人待修改任务
 * @param createTime LocalDateTime，数据库创建时间
 * @param returnTime LocalDateTime，可为空的退回时间
 * @param reopenTime LocalDateTime，可为空的重开时间
 * @param completeTime LocalDateTime，可为空的完成时间
 * @param terminateTime LocalDateTime，可为空的终止时间
 */
public record MultiInstanceRoundSnapshot(long roundId, String deployId,
        String processDefinitionId, String processInstanceId, String activityId,
        String rootExecutionId, int roundNo, WorkflowMultiInstanceMode mode,
        List<String> members, int revision, WorkflowMultiInstanceRoundStatus status,
        String returnSourceTaskId, String returnActorUserId, String applicantTaskId,
        LocalDateTime createTime, LocalDateTime returnTime, LocalDateTime reopenTime,
        LocalDateTime completeTime, LocalDateTime terminateTime)
{
    /**
     * 校验正式轮次的稳定身份与快照字段，并冻结成员顺序。
     *
     * @return 无返回值，非法持久化事实拒绝进入服务间调用
     */
    public MultiInstanceRoundSnapshot
    {
        if (roundId <= 0 || !StringUtils.hasText(deployId)
                || !StringUtils.hasText(processDefinitionId)
                || !StringUtils.hasText(processInstanceId)
                || !StringUtils.hasText(activityId)
                || !StringUtils.hasText(rootExecutionId) || roundNo < 1
                || mode == null || members == null || members.isEmpty()
                || revision < 0 || status == null || createTime == null)
        {
            throw new IllegalArgumentException("多实例轮次快照不完整");
        }
        members = List.copyOf(members);
    }

    /**
     * 从已经完成实体生命周期校验的 Mapper 行构造不可变投影。
     *
     * @param round WfMultiInstanceRound，Mapper 返回的正式轮次实体
     * @return MultiInstanceRoundSnapshot，成员已经严格解码并冻结的领域事实
     */
    public static MultiInstanceRoundSnapshot from(WfMultiInstanceRound round)
    {
        Objects.requireNonNull(round, "round");
        round.requireValidLifecycle();
        WorkflowMultiInstanceMode parsedMode = WorkflowMultiInstanceMode.valueOf(
                round.getMode());
        return new MultiInstanceRoundSnapshot(round.getRoundId(), round.getDeployId(),
                round.getProcessDefinitionId(), round.getProcessInstanceId(),
                round.getActivityId(), round.getRootExecutionId(), round.getRoundNo(),
                parsedMode, WfMultiInstanceRound.decodeMembers(round.getMembersJson()),
                round.getRevisionNo(), round.getRoundStatus(),
                round.getReturnSourceTaskId(), round.getReturnActorUserId(),
                round.getApplicantTaskId(), round.getCreateTime(), round.getReturnTime(),
                round.getReopenTime(), round.getCompleteTime(), round.getTerminateTime());
    }

    /**
     * 比较状态迁移前后不允许变化的同一轮次身份和引擎快照。
     *
     * @param other MultiInstanceRoundSnapshot，状态写后重新读取的轮次
     * @return boolean，稳定事实完全一致时返回 true
     */
    public boolean sameRoundFacts(MultiInstanceRoundSnapshot other)
    {
        return other != null && roundId == other.roundId
                && Objects.equals(deployId, other.deployId)
                && Objects.equals(processDefinitionId, other.processDefinitionId)
                && Objects.equals(processInstanceId, other.processInstanceId)
                && Objects.equals(activityId, other.activityId)
                && Objects.equals(rootExecutionId, other.rootExecutionId)
                && roundNo == other.roundNo && mode == other.mode
                && members.equals(other.members) && revision == other.revision
                && Objects.equals(createTime, other.createTime);
    }

    /**
     * 比较旧轮与下一 ACTIVE 轮必须继承的部署、节点、成员、模式和 revision。
     *
     * @param other MultiInstanceRoundSnapshot，新创建的下一审批轮次
     * @return boolean，可继承事实完全一致时返回 true
     */
    public boolean sameReopenFacts(MultiInstanceRoundSnapshot other)
    {
        return other != null && Objects.equals(deployId, other.deployId)
                && Objects.equals(processDefinitionId, other.processDefinitionId)
                && Objects.equals(processInstanceId, other.processInstanceId)
                && Objects.equals(activityId, other.activityId) && mode == other.mode
                && members.equals(other.members) && revision == other.revision;
    }
}
