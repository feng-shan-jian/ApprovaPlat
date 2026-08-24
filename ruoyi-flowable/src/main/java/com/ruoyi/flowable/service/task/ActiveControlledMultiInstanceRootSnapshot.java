package com.ruoyi.flowable.service.task;

import java.util.List;

/**
 * 终止预检扫描到的活动受控根快照，允许 RETURNED 临时根计数与冻结成员数不同。
 *
 * @param root ControlledMultiInstanceRootSnapshot，定义和流程变量事实
 * @param counts MultiInstanceEngineCounts，根局部计数
 * @param activeTasks List&lt;MultiInstanceActiveTaskSnapshot&gt;，同根活动任务
 * @param childExecutionIds List&lt;String&gt;，根下全部 child execution
 */
public record ActiveControlledMultiInstanceRootSnapshot(
        ControlledMultiInstanceRootSnapshot root, MultiInstanceEngineCounts counts,
        List<MultiInstanceActiveTaskSnapshot> activeTasks,
        List<String> childExecutionIds)
{
    /**
     * 冻结集合并校验任务和 child execution 与根计数闭合。
     *
     * @return 无返回值，非法活动根拒绝构造
     */
    public ActiveControlledMultiInstanceRootSnapshot
    {
        if (root == null || counts == null || activeTasks == null
                || childExecutionIds == null)
        {
            throw new IllegalArgumentException("活动受控多实例根快照不完整");
        }
        activeTasks = List.copyOf(activeTasks);
        childExecutionIds = List.copyOf(childExecutionIds);
        if (counts.active() != activeTasks.size()
                || counts.instances() != childExecutionIds.size())
        {
            throw new IllegalArgumentException("活动受控多实例根快照不一致");
        }
    }
}
