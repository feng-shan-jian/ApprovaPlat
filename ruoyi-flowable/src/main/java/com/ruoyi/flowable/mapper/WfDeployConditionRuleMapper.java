package com.ruoyi.flowable.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.flowable.domain.WfDeployConditionRule;

/**
 * 条件分支不可变部署快照数据访问层。
 */
public interface WfDeployConditionRuleMapper
{
    /**
     * 批量写入单次部署的完整条件分支快照。
     * @param snapshots List&lt;WfDeployConditionRule&gt;，已校验快照集合
     * @return int，实际写入行数
     */
    int insertBatch(@Param("snapshots") List<WfDeployConditionRule> snapshots);

    /**
     * 查询运行时同一网关的全部冻结分支。
     * @param deployId String，Flowable 部署主键
     * @param processKey String，流程定义 key
     * @param gatewayToken String，编译表达式中的稳定网关令牌
     * @return List&lt;WfDeployConditionRule&gt;，按分支主键稳定排序的完整快照
     */
    List<WfDeployConditionRule> selectRuntimeGateway(@Param("deployId") String deployId,
            @Param("processKey") String processKey,
            @Param("gatewayToken") String gatewayToken);

    /**
     * 查询部署内全部条件分支，供删除前一致性预检和详情读取。
     * @param deployId String，Flowable 部署主键
     * @return List&lt;WfDeployConditionRule&gt;，部署快照集合
     */
    List<WfDeployConditionRule> selectByDeploymentId(@Param("deployId") String deployId);

    /**
     * 删除没有实例引用的部署条件快照。
     * @param deployId String，Flowable 部署主键
     * @return int，实际删除行数
     */
    int deleteByDeploymentId(@Param("deployId") String deployId);
}
