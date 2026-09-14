package com.ruoyi.flowable.service.attachment;

/**
 * 附件物理存储明确可重试的 I/O 操作异常；安全校验、参数、程序和数据库异常不使用该类型。
 */
public class WorkflowAttachmentStorageOperationException extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    /**
     * 创建不包含物理路径的附件存储操作异常。
     *
     * 异常交由清理服务持久化退避
     */
    public WorkflowAttachmentStorageOperationException(String message, Throwable cause)
    {
        super(message, cause);
    }
}
