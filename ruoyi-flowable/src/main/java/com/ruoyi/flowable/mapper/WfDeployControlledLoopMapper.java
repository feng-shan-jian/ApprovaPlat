package com.ruoyi.flowable.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.flowable.domain.WfDeployControlledLoop;

/**
 * 受控重复审批循环部署快照数据访问层。
 */
public interface WfDeployControlledLoopMapper
{
    /**
     * 批量写入单次流程部署的受控循环快照。
     * @param snapshots List&lt;WfDeployControlledLoop&gt;，已完成语义校验的不可变快照
     * @return int，实际写入行数
     */
    int insertBatch(@Param("snapshots") List<WfDeployControlledLoop> snapshots);

    /**
     * 查询部署内指定流程的全部受控循环配置。
     * @param deployId String，Flowable 部署主键
     * @param processKey String，流程定义 key
     * @return List&lt;WfDeployControlledLoop&gt;，按节点标识稳定排序的快照
     */
    List<WfDeployControlledLoop> selectByDeploymentAndProcess(
            @Param("deployId") String deployId, @Param("processKey") String processKey);

    /**
     * 查询指定部署和用户任务的唯一受控循环快照。
     * @param deployId String，Flowable 部署主键
     * @param processKey String，流程定义 key
     * @param activityId String，用户任务节点标识
     * @return WfDeployControlledLoop，匹配快照；普通任务返回 null
     */
    WfDeployControlledLoop selectByDeploymentAndActivity(
            @Param("deployId") String deployId, @Param("processKey") String processKey,
            @Param("activityId") String activityId);

    /**
     * 查询部署全部受控循环快照，供删除前一致性预检使用。
     * @param deployId String，Flowable 部署主键
     * @return List&lt;WfDeployControlledLoop&gt;，按流程和节点稳定排序的快照
     */
    List<WfDeployControlledLoop> selectByDeploymentId(@Param("deployId") String deployId);

    /**
     * 删除没有运行或历史实例引用的部署循环快照。
     * @param deployId String，Flowable 部署主键
     * @return int，实际删除行数
     */
    int deleteByDeploymentId(@Param("deployId") String deployId);
}
