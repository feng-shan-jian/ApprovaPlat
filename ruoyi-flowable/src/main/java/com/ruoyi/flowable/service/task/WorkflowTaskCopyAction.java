package com.ruoyi.flowable.service.task;

/**
 * 会产生业务抄送记录的任务动作类型，枚举名称同时用于稳定抄送事件键和审计备注。
 */
public enum WorkflowTaskCopyAction
{
    COMPLETE,
    REJECT,
    RETURN,
    DELEGATE,
    RESOLVE,
    TRANSFER
}
