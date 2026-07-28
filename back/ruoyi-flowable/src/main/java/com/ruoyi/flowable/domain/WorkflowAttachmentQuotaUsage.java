package com.ruoyi.flowable.domain;

/**
 * 用户当前仍占用私有临时存储的附件聚合值。
 *
 * @param temporaryCount long，TEMP 及尚未物理清理终态附件数量
 * @param temporaryBytes long，上述附件的服务端实际文件字节总数
 */
public record WorkflowAttachmentQuotaUsage(long temporaryCount, long temporaryBytes)
{
    /**
     * 校验数据库聚合值不能为负数。
     *
     * @param temporaryCount long，数据库聚合的附件数量
     * @param temporaryBytes long，数据库聚合的文件总字节数
     * @return 无返回值，非法聚合值会拒绝构造
     */
    public WorkflowAttachmentQuotaUsage
    {
        if (temporaryCount < 0L || temporaryBytes < 0L)
        {
            throw new IllegalArgumentException("工作流附件配额聚合值不能为负数");
        }
    }
}
