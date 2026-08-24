package com.ruoyi.flowable.service.task;

/**
 * 一次 Flowable 命令内全部 handler 和 listener 观察结果的不可变汇总。
 *
 * @param action MultiInstanceTransitionAction，RETURN 或 REOPEN
 * @param collectionResolved boolean，集合表达式是否按当前协议解析
 * @param cancellationObserved boolean，来源多实例根取消是否被观察
 * @param temporaryRootExecutionId String，RETURN 首节点场景的临时根
 * @param temporaryTaskId String，RETURN 首节点场景的临时任务
 * @param reopenedRootExecutionId String，REOPEN 创建的新审批根
 * @param reopenedTaskCount int，REOPEN 实际创建的成员任务数量
 */
public record MultiInstanceTransitionResult(MultiInstanceTransitionAction action,
        boolean collectionResolved, boolean cancellationObserved,
        String temporaryRootExecutionId, String temporaryTaskId,
        String reopenedRootExecutionId, int reopenedTaskCount)
{
    /**
     * 校验观察计数非负且动作存在。
     *
     * @return 无返回值，非法结果拒绝构造
     */
    public MultiInstanceTransitionResult
    {
        if (action == null || reopenedTaskCount < 0)
        {
            throw new IllegalArgumentException("多实例迁移观察结果不合法");
        }
    }
}
