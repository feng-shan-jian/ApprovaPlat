package com.ruoyi.flowable.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.flowable.domain.WfDeployCallActivitySnapshot;

/**
 * 调用活动部署依赖快照数据访问层。
 */
public interface WfDeployCallActivityMapper
{
    /**
     * 批量写入一次父流程部署的调用活动快照。
     * @param snapshots List&lt;WfDeployCallActivitySnapshot&gt;，已经冻结定义 ID 的快照
     * @return int，实际写入行数
     */
    int insertBatch(@Param("snapshots") List<WfDeployCallActivitySnapshot> snapshots);

    /**
     * 查询指定父流程部署的调用活动快照。
     * @param deployId String，父流程部署主键
     * @return List&lt;WfDeployCallActivitySnapshot&gt;，按流程和元素稳定排序的快照
     */
    List<WfDeployCallActivitySnapshot> selectByDeploymentId(@Param("deployId") String deployId);

    /**
     * 删除受控删除父流程部署对应的调用活动快照。
     * @param deployId String，父流程部署主键
     * @return int，实际删除行数
     */
    int deleteByDeploymentId(@Param("deployId") String deployId);
}
