package com.ruoyi.flowable.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.flowable.domain.WfDeployDmnSnapshot;

/**
 * 流程 DMN 决策冻结快照数据访问层。
 */
public interface WfDeployDmnSnapshotMapper
{
    /**
     * 批量写入单次流程部署的 DMN 快照。
     * @param snapshots List&lt;WfDeployDmnSnapshot&gt;，已绑定 DMN 子部署的完整快照
     * @return int，实际写入行数
     */
    int insertBatch(@Param("snapshots") List<WfDeployDmnSnapshot> snapshots);

    /**
     * 查询流程部署全部 DMN 快照。
     * @param deployId String，Flowable 流程部署主键
     * @return List&lt;WfDeployDmnSnapshot&gt;，按流程和元素稳定排序的快照
     */
    List<WfDeployDmnSnapshot> selectByDeploymentId(@Param("deployId") String deployId);

    /**
     * 统计仍引用来源 DMN 部署的流程快照。
     * @param sourceDeploymentId String，DMN 来源部署主键
     * @return long，引用数量
     */
    long countBySourceDeploymentId(@Param("sourceDeploymentId") String sourceDeploymentId);

    /**
     * 删除没有运行或历史实例引用的流程部署快照。
     * @param deployId String，Flowable 流程部署主键
     * @return int，实际删除行数
     */
    int deleteByDeploymentId(@Param("deployId") String deployId);
}
