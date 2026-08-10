package com.ruoyi.flowable.mapper;

import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.flowable.domain.WfDeployForm;

/**
 * 工作流部署表单快照数据访问层。
 */
public interface WfDeployFormMapper
{
    /**
     * 批量写入同一部署产生的不可变表单快照。
     * @param forms List&lt;WfDeployForm&gt;，完整部署表单快照集合
     * @return int，实际写入行数
     */
    int insertBatch(@Param("forms") List<WfDeployForm> forms);

    /**
     * 查询指定部署的有效表单快照，不回连当前表单模板。
     * @param deploymentId String，Flowable 部署主键
     * @return List&lt;WfDeployForm&gt;，按节点和表单键排序的快照列表
     */
    List<WfDeployForm> selectByDeploymentId(@Param("deploymentId") String deploymentId);

    /**
     * 统计指定表单主键关联的有效部署快照数量。
     * @param formIds Collection&lt;Long&gt;，表单主键集合
     * @return int，有效部署快照数量
     */
    int countByFormIds(@Param("formIds") Collection<Long> formIds);

    /**
     * 物理删除指定部署的有效快照，仅供部署事务失败回滚或受控部署删除。
     * @param deploymentId String，Flowable 部署主键
     * @return int，实际删除行数
     */
    int deleteByDeploymentId(@Param("deploymentId") String deploymentId);
}
