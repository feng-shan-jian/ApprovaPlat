package com.ruoyi.flowable.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.flowable.domain.WfDeployParticipantRule;

/**
 * 流程参与者规则不可变部署快照数据访问层。
 */
public interface WfDeployParticipantRuleMapper
{
    /**
     * 批量写入单次部署的发起范围和任务规则。
     * @param snapshots List&lt;WfDeployParticipantRule&gt;，已校验且已绑定部署主键的快照
     * @return int，实际写入行数
     */
    int insertBatch(@Param("snapshots") List<WfDeployParticipantRule> snapshots);

    /**
     * 查询指定部署和流程的唯一发起范围。
     * @param deployId String，部署主键
     * @param processKey String，流程定义 key
     * @return WfDeployParticipantRule，发起范围快照
     */
    WfDeployParticipantRule selectStartRule(@Param("deployId") String deployId,
            @Param("processKey") String processKey);

    /**
     * 查询指定部署和任务节点的唯一办理规则。
     * @param deployId String，部署主键
     * @param processKey String，流程定义 key
     * @param activityId String，任务定义 key
     * @return WfDeployParticipantRule，任务办理规则快照
     */
    WfDeployParticipantRule selectTaskRule(@Param("deployId") String deployId,
            @Param("processKey") String processKey, @Param("activityId") String activityId);

    /**
     * 查询部署全部规则，供删除一致性门禁使用。
     * @param deployId String，部署主键
     * @return List&lt;WfDeployParticipantRule&gt;，稳定排序的规则快照
     */
    List<WfDeployParticipantRule> selectByDeploymentId(@Param("deployId") String deployId);

    /**
     * 删除没有运行和历史实例引用的部署规则。
     * @param deployId String，部署主键
     * @return int，删除行数
     */
    int deleteByDeploymentId(@Param("deployId") String deployId);
}
