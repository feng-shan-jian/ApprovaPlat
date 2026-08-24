package com.ruoyi.flowable.service.task;

/**
 * 只读快照读取器发现定义、变量、任务或 execution 漂移时使用的内部异常。
 */
final class WorkflowMultiInstanceSnapshotDriftException extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    /**
     * 创建不暴露内部字段的快照漂移异常。
     *
     * @return 无返回值，调用服务负责翻译为原有稳定外部错误
     */
    WorkflowMultiInstanceSnapshotDriftException()
    {
        super("controlled multi-instance runtime snapshot drift");
    }

    /**
     * 创建保留底层解析原因的快照漂移异常。
     *
     * @param cause Throwable，Flowable 定义或变量解析原始原因
     * @return 无返回值，调用服务负责稳定错误翻译
     */
    WorkflowMultiInstanceSnapshotDriftException(Throwable cause)
    {
        super("controlled multi-instance runtime snapshot drift", cause);
    }
}
