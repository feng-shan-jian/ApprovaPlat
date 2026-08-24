package com.ruoyi.flowable.service.task;

/**
 * Flowable 多实例根的不可变计数快照。
 *
 * @param instances int，根的总实例数
 * @param active int，仍活动的实例数
 * @param completed int，已经完成但仍保留 child execution 的实例数
 */
public record MultiInstanceEngineCounts(int instances, int active, int completed)
{
    /**
     * 校验三个计数都是非负数且总数严格闭合。
     *
     * @param instances int，总实例数
     * @param active int，活动实例数
     * @param completed int，已完成实例数
     * @return 无返回值，非法计数拒绝构造
     */
    public MultiInstanceEngineCounts
    {
        if (instances < 0 || active < 0 || completed < 0
                || active + completed != instances)
        {
            throw new IllegalArgumentException("多实例根计数不合法");
        }
    }
}
