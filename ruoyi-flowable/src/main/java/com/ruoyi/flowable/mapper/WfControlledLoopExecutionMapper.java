package com.ruoyi.flowable.mapper;

import java.util.List;
import java.util.Collection;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.flowable.domain.WfControlledLoopExecution;

/**
 * 受控重复审批循环逐轮运行审计数据访问层。
 */
public interface WfControlledLoopExecutionMapper
{
    /**
     * 查询指定实例和循环节点已经完成的最大轮次。
     * @param processInstanceId String，流程实例主键
     * @param activityId String，循环用户任务标识
     * @return Integer，尚未完成任何轮次时返回 0
     */
    Integer selectMaxIteration(@Param("processInstanceId") String processInstanceId,
            @Param("activityId") String activityId);

    /**
     * 插入与任务完成共享事务的单轮审计记录。
     * @param execution WfControlledLoopExecution，任务、判断值、结果和操作人完整记录
     * @return int，成功时固定为 1
     */
    int insert(WfControlledLoopExecution execution);

    /**
     * 查询流程实例全部循环轮次记录。
     * @param processInstanceId String，流程实例主键
     * @return List&lt;WfControlledLoopExecution&gt;，按节点和轮次稳定排序的正式审计记录
     */
    List<WfControlledLoopExecution> selectByProcessInstanceId(
            @Param("processInstanceId") String processInstanceId);

    /**
     * 统计指定流程实例集合关联的受控循环审计记录。
     * @param processInstanceIds Collection&lt;String&gt;，已完成历史删除预检的实例主键集合
     * @return long，当前事务快照中待删除的循环审计记录数量
     */
    long countByProcessInstanceIds(
            @Param("processInstanceIds") Collection<String> processInstanceIds);

    /**
     * 删除指定流程实例集合关联的受控循环审计记录。
     * @param processInstanceIds Collection&lt;String&gt;，已完成历史删除预检的实例主键集合
     * @return int，当前事务中实际删除的循环审计记录数量
     */
    int deleteByProcessInstanceIds(
            @Param("processInstanceIds") Collection<String> processInstanceIds);
}
