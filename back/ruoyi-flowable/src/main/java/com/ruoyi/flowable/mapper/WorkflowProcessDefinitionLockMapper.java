package com.ruoyi.flowable.mapper;

import org.apache.ibatis.annotations.Param;
import com.ruoyi.flowable.domain.WorkflowProcessDefinitionLockRow;

/**
 * 流程按 key 发起的定义版本串行化 Mapper。
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
}
