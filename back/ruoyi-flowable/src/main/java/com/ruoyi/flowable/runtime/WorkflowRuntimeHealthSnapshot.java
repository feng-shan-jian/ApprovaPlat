package com.ruoyi.flowable.runtime;

/**
 * 工作流 readiness 单次读取的原子运行快照，不包含业务标识或高基数数据。
 *
 * @param available boolean，是否至少完成过一轮全部依赖采集
 * @param ageMillis long，最近成功快照年龄毫秒；不可用时固定为 -1
 * @param attachmentUsableBytes long，采集时附件挂载点可用字节数
 * @param asyncExecutorActive boolean，采集时普通 executor 是否激活
 * @param asyncHistoryActive boolean，采集时历史 executor 是否激活
 */
public record WorkflowRuntimeHealthSnapshot(
        boolean available,
        long ageMillis,
        long attachmentUsableBytes,
        boolean asyncExecutorActive,
        boolean asyncHistoryActive)
{
    /**
     * 校验健康快照内部一致性，防止不可用状态伪装成刚采集或发布负容量。
     *
     * @return 无返回值，非法组合会拒绝创建快照
     */
    public WorkflowRuntimeHealthSnapshot
    {
        if ((available && ageMillis < 0L) || (!available && ageMillis != -1L)
                || attachmentUsableBytes < 0L)
        {
            throw new IllegalArgumentException("工作流健康快照状态不合法");
        }
    }
}
