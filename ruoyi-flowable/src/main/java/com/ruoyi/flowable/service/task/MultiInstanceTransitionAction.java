package com.ruoyi.flowable.service.task;

/** 受控多实例命令内迁移动作。 */
public enum MultiInstanceTransitionAction
{
    /** 活动审批组整体退回发起人修改。 */
    RETURN,

    /** 发起人重提并重建完整审批组。 */
    REOPEN
}
