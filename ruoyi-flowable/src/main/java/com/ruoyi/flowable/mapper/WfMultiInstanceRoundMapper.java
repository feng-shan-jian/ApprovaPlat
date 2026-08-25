package com.ruoyi.flowable.mapper;

import java.util.List;
import java.util.Set;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.flowable.domain.WfMultiInstanceRound;
import com.ruoyi.flowable.domain.WorkflowMultiInstanceRoundStatus;

/**
 * 多实例轮次快照、审计和 CAS 同步数据访问层。
 */
public interface WfMultiInstanceRoundMapper
{
    /**
     * 写入首次进入某一多实例根执行的 ACTIVE 轮次，创建时间固定使用数据库时钟。
     *
     * @param round WfMultiInstanceRound，关联、模式、成员和 revision 已校验的轮次快照；时间入参不参与写入
     * @return int，写入成功时固定返回 1
     */
    int insert(WfMultiInstanceRound round);

    /**
     * 按本轮多实例根 execution 唯一查询快照。
     *
     * @param rootExecutionId String，多实例根 execution 主键
     * @return WfMultiInstanceRound，根执行对应轮次或 null
     */
    WfMultiInstanceRound selectByRootExecutionId(
            @Param("rootExecutionId") String rootExecutionId);

    /**
     * 查询同实例同节点尚未关闭的 ACTIVE/RETURNED 轮次。
     *
     * 返回集合而非限制一行，便于在唯一约束被破坏时明确发现重复数据。
     *
     * @param processInstanceId String，Flowable 流程实例主键
     * @param activityId String，多实例 BPMN 节点标识
     * @return List&lt;WfMultiInstanceRound&gt;，按轮次号升序的开放轮次
     */
    List<WfMultiInstanceRound> selectOpenByProcessInstanceAndActivity(
            @Param("processInstanceId") String processInstanceId,
            @Param("activityId") String activityId);

    /**
     * 查询同实例同节点正在执行的 ACTIVE 轮次。
     *
     * @param processInstanceId String，Flowable 流程实例主键
     * @param activityId String，多实例 BPMN 节点标识
     * @return List&lt;WfMultiInstanceRound&gt;，正常约束下为空或单元素集合
     */
    List<WfMultiInstanceRound> selectActiveByProcessInstanceAndActivity(
            @Param("processInstanceId") String processInstanceId,
            @Param("activityId") String activityId);

    /**
     * 按申请人待修改任务主键查询唯一应处于 RETURNED 的轮次。
     *
     * 返回集合而非限制一行，确保历史脏数据或并发异常造成重复关联时由上层失败关闭，
     * 不会静默选择任意轮次继续重提。
     *
     * @param applicantTaskId String，Flowable 申请人待修改任务主键
     * @return List&lt;WfMultiInstanceRound&gt;，按轮次主键升序排列的 RETURNED 轮次
     */
    List<WfMultiInstanceRound> selectReturnedByApplicantTaskId(
            @Param("applicantTaskId") String applicantTaskId);

    /**
     * 查询同实例同节点已分配的最大轮次号。
     *
     * @param processInstanceId String，Flowable 流程实例主键
     * @param activityId String，多实例 BPMN 节点标识
     * @return Integer，尚无轮次时返回 null
     */
    Integer selectMaxRoundNo(@Param("processInstanceId") String processInstanceId,
            @Param("activityId") String activityId);

    /**
     * 在 Flowable revision 已成功推进后，用旧修订号 CAS 更新 ACTIVE 轮次成员快照。
     *
     * @param roundId long，已对账轮次主键
     * @param expectedRevision int，业务表应当持有的旧修订号
     * @param newRevision int，Flowable 已推进到的新修订号
     * @param membersJson String，与新 Flowable 变量一致的有序成员 JSON
     * @return int，仅精确 CAS 成功时返回 1，竞争或状态漂移返回 0
     */
    int compareAndSetActiveSnapshot(@Param("roundId") long roundId,
            @Param("expectedRevision") int expectedRevision,
            @Param("newRevision") int newRevision,
            @Param("membersJson") String membersJson);

    /**
     * 整组退回写链在引擎任务迁移完成后，将唯一 ACTIVE 轮次 CAS 为 RETURNED。
     *
     * 退回时间固定使用数据库时钟；revision 和成员快照保持不变，作为随后重建新轮次的
     * 冻结事实。状态或旧 revision 漂移时返回 0，由上层回滚同一 Flowable 命令。
     *
     * @param roundId long，已经完成 Flowable/业务对账的 ACTIVE 轮次主键
     * @param expectedRevision int，退回前 Flowable 与轮次表共同 revision
     * @param returnSourceTaskId String，触发整组退回的活动任务主键
     * @param returnActorUserId String，执行退回的规范用户主键
     * @param applicantTaskId String，迁移后唯一申请人待修改任务主键
     * @return int，仅主键、revision 和 ACTIVE 状态匹配时返回 1
     */
    int compareAndSetReturnedStatus(@Param("roundId") long roundId,
            @Param("expectedRevision") int expectedRevision,
            @Param("returnSourceTaskId") String returnSourceTaskId,
            @Param("returnActorUserId") String returnActorUserId,
            @Param("applicantTaskId") String applicantTaskId);

    /**
     * 发起人重提写链按完整退回关联，将唯一 RETURNED 轮次 CAS 为 REOPENED。
     *
     * 除主键、revision 和状态外，还必须精确匹配申请人任务、退回源任务及操作人，避免
     * 旧页面、串线任务或错误内部迁移标识关闭其他轮次。重开时间固定使用数据库时钟。
     *
     * @param roundId long，通过 applicantTaskId 唯一定位并完成对账的轮次主键
     * @param expectedRevision int，退回时冻结且重提时再次对账的 revision
     * @param applicantTaskId String，本次重提的唯一申请人待修改任务主键
     * @param returnSourceTaskId String，轮次冻结的原退回源任务主键
     * @param returnActorUserId String，轮次冻结的原退回操作人用户主键
     * @return int，仅全部严格条件匹配时返回 1，竞争或关联漂移返回 0
     */
    int compareAndSetReopenedStatus(@Param("roundId") long roundId,
            @Param("expectedRevision") int expectedRevision,
            @Param("applicantTaskId") String applicantTaskId,
            @Param("returnSourceTaskId") String returnSourceTaskId,
            @Param("returnActorUserId") String returnActorUserId);

    /**
     * 完成监听器在修订号已由前置链路推进后，仅 CAS 关闭 ACTIVE 轮次。
     *
     * @param roundId long，已对账轮次主键
     * @param expectedRevision int，前置链路已写入业务表的当前修订号
     * @param membersJson String，整组结束时再次对账的有序成员 JSON
     * @return int，仅修订号匹配且状态为 ACTIVE 时返回 1
     */
    int compareAndSetCompletedStatus(@Param("roundId") long roundId,
            @Param("expectedRevision") int expectedRevision,
            @Param("membersJson") String membersJson);

    /**
     * Flowable 确认多实例根被异常取消后，按来源状态与 revision 单行 CAS 关闭轮次。
     *
     * @param roundId long，已经与取消事件根 execution 对账的轮次主键
     * @param expectedRevision int，取消事件发生时 Flowable 与业务表共同 revision
     * @param expectedStatus WorkflowMultiInstanceRoundStatus，ACTIVE 或 RETURNED 来源状态
     * @return int，仅主键、revision 和开放来源状态全部匹配时返回 1
     */
    int compareAndSetTerminatedStatus(@Param("roundId") long roundId,
            @Param("expectedRevision") int expectedRevision,
            @Param("expectedStatus") WorkflowMultiInstanceRoundStatus expectedStatus);

    /**
     * 不加业务行锁读取完整运行流程树内全部 ACTIVE/RETURNED 开放轮次。
     *
     * 该查询仅用于已经取得 Flowable 写锁后的异常终止冻结；真正关闭前仍必须再次
     * `FOR UPDATE` 并与本次返回的完整事实精确比对。
     *
     * @param processInstanceIds Set&lt;String&gt;，根实例及全部活动 CallActivity 子实例主键
     * @return List&lt;WfMultiInstanceRound&gt;，按轮次主键排序且未取得业务行锁的开放轮次
     */
    List<WfMultiInstanceRound> selectOpenByProcessInstanceIds(
            @Param("processInstanceIds") Set<String> processInstanceIds);

    /**
     * 锁定完整运行流程树内所有 ACTIVE/RETURNED 开放轮次，作为异常终止预检快照。
     *
     * @param processInstanceIds Set&lt;String&gt;，根实例及全部活动 CallActivity 子实例主键
     * @return List&lt;WfMultiInstanceRound&gt;，按轮次主键排序且已持有行锁的开放轮次
     */
    List<WfMultiInstanceRound> selectOpenForUpdateByProcessInstanceIds(
            @Param("processInstanceIds") Set<String> processInstanceIds);

    /**
     * 将预检并锁定的开放轮次批量关闭为 TERMINATED，保留 RETURNED 的退回审计字段。
     *
     * @param roundIds Set&lt;Long&gt;，本事务预检得到的唯一轮次主键
     * @return int，实际由 ACTIVE/RETURNED 转为 TERMINATED 的轮次数
     */
    int terminateOpenByRoundIds(@Param("roundIds") Set<Long> roundIds);

    /**
     * 按轮次主键读取异常关闭后的完整记录，供写后生命周期复核。
     *
     * @param roundIds Set&lt;Long&gt;，本事务预检得到的唯一轮次主键
     * @return List&lt;WfMultiInstanceRound&gt;，按轮次主键稳定排序的持久化记录
     */
    List<WfMultiInstanceRound> selectByRoundIds(
            @Param("roundIds") Set<Long> roundIds);

    /**
     * 统计完整运行流程树中仍为 ACTIVE/RETURNED 的开放轮次。
     *
     * @param processInstanceIds Set&lt;String&gt;，根实例及全部活动 CallActivity 子实例主键
     * @return long，写后必须为零的开放轮次数
     */
    long countOpenByProcessInstanceIds(
            @Param("processInstanceIds") Set<String> processInstanceIds);

    /**
     * 查询指定流程实例的全部轮次快照。
     *
     * @param processInstanceId String，Flowable 流程实例主键
     * @return List&lt;WfMultiInstanceRound&gt;，按节点和轮次号稳定排序的记录
     */
    List<WfMultiInstanceRound> selectByProcessInstanceId(
            @Param("processInstanceId") String processInstanceId);

    /**
     * 统计历史删除预检实例集合关联的全部轮次。
     *
     * @param processInstanceIds Set&lt;String&gt;，同一历史删除事务中的去重实例主键
     * @return long，当前事务快照中待删除的轮次数
     */
    long countByProcessInstanceIds(
            @Param("processInstanceIds") Set<String> processInstanceIds);

    /**
     * 删除历史删除实例集合关联的全部轮次。
     *
     * @param processInstanceIds Set&lt;String&gt;，已完成数量预检的去重实例主键
     * @return int，当前事务实际删除的轮次数
     */
    int deleteByProcessInstanceIds(
            @Param("processInstanceIds") Set<String> processInstanceIds);
}
