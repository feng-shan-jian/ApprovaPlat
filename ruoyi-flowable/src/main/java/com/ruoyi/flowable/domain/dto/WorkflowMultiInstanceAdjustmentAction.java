package com.ruoyi.flowable.domain.dto;

/**
 * 动态多实例成员调整动作。
 */
public enum WorkflowMultiInstanceAdjustmentAction
{
    /** 为当前并行多实例根增加正式成员 execution。 */
    ADD,

    /** 删除同根且尚未完成的 sibling execution。 */
    REMOVE
}
