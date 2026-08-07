package com.ruoyi.flowable.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.flowable.domain.WfDeployExtensionSnapshot;

/**
 * 部署扩展不可变快照数据访问层。
 */
public interface WfDeployExtensionSnapshotMapper
{
    /**
     * 批量写入单次部署的完整扩展快照。
     * @param snapshots List&lt;WfDeployExtensionSnapshot&gt;，已校验完整快照
     * @return int，实际写入行数
     */
    int insertBatch(@Param("snapshots") List<WfDeployExtensionSnapshot> snapshots);

    /**
     * 按部署和活动标识查询唯一执行快照。
     * @param deployId String，Flowable 部署主键
     * @param processKey String，当前 BPMN 可执行流程标识
     * @param elementId String，当前 BPMN 活动标识
     * @return WfDeployExtensionSnapshot，不存在时返回 null
     */
    WfDeployExtensionSnapshot selectRuntimeSnapshot(@Param("deployId") String deployId,
            @Param("processKey") String processKey,
            @Param("elementId") String elementId);

    /**
     * 查询部署的全部扩展快照。
     * @param deployId String，Flowable 部署主键
     * @return List&lt;WfDeployExtensionSnapshot&gt;，稳定排序后的不可变快照
     */
    List<WfDeployExtensionSnapshot> selectByDeploymentId(@Param("deployId") String deployId);

    /**
     * 删除尚无实例引用的受控部署快照。
     * @param deployId String，Flowable 部署主键
     * @return int，实际删除行数
     */
    int deleteByDeploymentId(@Param("deployId") String deployId);
}
