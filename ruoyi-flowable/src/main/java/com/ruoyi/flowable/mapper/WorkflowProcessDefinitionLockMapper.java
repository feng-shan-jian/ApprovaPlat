package com.ruoyi.flowable.mapper;

import org.apache.ibatis.annotations.Param;
import com.ruoyi.flowable.domain.WorkflowProcessDefinitionLockRow;

/**
 * 流程定义版本与部署生命周期串行化 Mapper。
 */
public interface WorkflowProcessDefinitionLockMapper
{
    /**
     * 锁定默认租户指定 key 的最新定义行，并通过 MySQL 当前读看见已提交并发部署。
     *
     * @param processKey String，已经过业务校验的流程定义 key
     * @return WorkflowProcessDefinitionLockRow，最新定义锁投影；定义已删除时为 null
     */
    WorkflowProcessDefinitionLockRow selectLatestDefaultTenantDefinitionForUpdate(
            @Param("processKey") String processKey);

    /**
     * 按主键锁定 Flowable 部署行，串行化部署删除与活动申请草稿创建。
     *
     * @param deploymentId String，已经过长度和非空校验的 Flowable 部署主键
     * @return String，仍存在且已持有行锁的部署主键；部署已删除时为 null
     */
    String selectDeploymentIdForUpdate(@Param("deploymentId") String deploymentId);

    /**
     * 当前读锁定指定部署的一条运行实例引用，覆盖可重复读事务的历史快照。
     *
     * @param deploymentId String，已经持有 ACT_RE_DEPLOYMENT 行锁的部署主键
     * @return Integer，存在运行实例时返回 1，不存在时返回 null
     */
    Integer selectRuntimeInstanceReferenceForUpdate(
            @Param("deploymentId") String deploymentId);

    /**
     * 当前读锁定指定部署的一条历史实例引用，禁止删除仍有审计轨迹的部署。
     *
     * @param deploymentId String，已经持有 ACT_RE_DEPLOYMENT 行锁的部署主键
     * @return Integer，存在历史实例时返回 1，不存在时返回 null
     */
    Integer selectHistoricInstanceReferenceForUpdate(
            @Param("deploymentId") String deploymentId);
}
