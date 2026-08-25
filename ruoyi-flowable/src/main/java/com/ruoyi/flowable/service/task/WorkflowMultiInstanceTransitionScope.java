package com.ruoyi.flowable.service.task;

/**
 * GroupTransitionService 持有的单命令迁移作用域。
 */
public interface WorkflowMultiInstanceTransitionScope extends AutoCloseable
{
    /**
     * 清除当前线程的命令内迁移状态。
     *
     * @return void，成功或异常路径都必须调用
     */
    @Override
    void close();
}
