package com.ruoyi.flowable.domain;

/**
 * 流程按 key 发起时，对默认租户最新定义执行当前读锁得到的最小数据库投影。
 *
 * @param definitionId String，Flowable 流程定义主键
 * @param deploymentId String，定义所属 Flowable 部署主键
 * @param suspensionState Integer，Flowable 挂起状态；1 为激活，2 为挂起
 */
public record WorkflowProcessDefinitionLockRow(
        String definitionId, String deploymentId, Integer suspensionState)
{
}
