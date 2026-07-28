package com.ruoyi.flowable.service.attachment;

/**
 * 单轮附件清理的正式结果，用于审计调度效果和累计监控指标。
 *
 * @param cleaned int，确认物理文件不存在并写入完成时间的记录数
 * @param failures int，本轮保留待重试状态的单条清理失败数
 */
public record WorkflowAttachmentCleanupResult(int cleaned, int failures)
{
    /**
     * 校验清理计数不能为负，避免错误指标掩盖清理实现异常。
     *
     * @param cleaned int，本轮清理完成数
     * @param failures int，本轮单条失败数
     * @return 无返回值，参数非法时拒绝创建结果
     */
    public WorkflowAttachmentCleanupResult
    {
        if (cleaned < 0 || failures < 0)
        {
            throw new IllegalArgumentException("工作流附件清理结果不能为负数");
        }
    }
}
