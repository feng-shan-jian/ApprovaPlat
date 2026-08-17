package com.ruoyi.flowable.domain;

import java.time.LocalDateTime;

/**
 * 工作流私有附件元数据，对应正式业务表 {@code wf_attachment}。
 *
 * @param attachmentId String，服务端生成的附件 UUID
 * @param ownerUserId Long，临时附件所属有效用户主键
 * @param fieldName String，上传时声明且绑定时复核的表单字段名
 * @param originalName String，服务端规范化后的原始文件名
 * @param storageKey String，私有存储根目录内的相对对象键，不得返回客户端
 * @param contentType String，服务端探测的 MIME 类型
 * @param fileSize long，服务端实际写入的文件字节数
 * @param sha256 String，文件内容 SHA-256 小写十六进制摘要
 * @param status WorkflowAttachmentStatus，附件生命周期状态
 * @param expireTime LocalDateTime，临时附件失效时间
 * @param draftId String，DRAFT 状态绑定的草稿 UUID，其他状态为空
 * @param processInstanceId String，绑定的 Flowable 流程实例主键
 * @param taskId String，可为空的 Flowable 任务主键
 * @param nodeKey String，附件实际提交时对应的 BPMN 节点 key
 * @param boundTime LocalDateTime，成功绑定流程对象的时间
 * @param storageDeletedTime LocalDateTime，私有文件完成物理删除的时间
 * @param cleanupRetryCount int，物理清理连续失败并已调度重试的次数
 * @param cleanupNextRetryTime LocalDateTime，下次允许进入清理候选的时间
 * @param cleanupLastErrorCode String，最近一次清理失败的稳定脱敏错误码
 * @param cleanupClaimToken String，当前清理领取批次 UUID；未领取时为空
 * @param cleanupLeaseUntil LocalDateTime，当前领取租约到期时间；未领取时为空
 * @param createTime LocalDateTime，附件元数据创建时间
 * @param updateTime LocalDateTime，最后状态更新时间
 */
public record WfAttachment(
        String attachmentId,
        Long ownerUserId,
        String fieldName,
        String originalName,
        String storageKey,
        String contentType,
        long fileSize,
        String sha256,
        WorkflowAttachmentStatus status,
        LocalDateTime expireTime,
        String draftId,
        String processInstanceId,
        String taskId,
        String nodeKey,
        LocalDateTime boundTime,
        LocalDateTime storageDeletedTime,
        int cleanupRetryCount,
        LocalDateTime cleanupNextRetryTime,
        String cleanupLastErrorCode,
        String cleanupClaimToken,
        LocalDateTime cleanupLeaseUntil,
        LocalDateTime createTime,
        LocalDateTime updateTime)
{
    /**
     * 兼容不包含清理领取租约字段的既有附件构造调用。
     *
     * @param attachmentId String，附件 UUID
     * @param ownerUserId Long，附件所有者
     * @param fieldName String，表单字段名
     * @param originalName String，原始文件名
     * @param storageKey String，私有存储键
     * @param contentType String，安全 MIME
     * @param fileSize long，文件字节数
     * @param sha256 String，内容摘要
     * @param status WorkflowAttachmentStatus，附件状态
     * @param expireTime LocalDateTime，临时失效时间
     * @param draftId String，草稿 UUID
     * @param processInstanceId String，流程实例主键
     * @param taskId String，任务主键
     * @param nodeKey String，节点 key
     * @param boundTime LocalDateTime，绑定时间
     * @param storageDeletedTime LocalDateTime，物理删除时间
     * @param cleanupRetryCount int，清理重试次数
     * @param cleanupNextRetryTime LocalDateTime，下次清理时间
     * @param cleanupLastErrorCode String，最近清理错误码
     * @param createTime LocalDateTime，创建时间
     * @param updateTime LocalDateTime，更新时间
     * @return 无返回值，清理领取令牌和租约固定为空
     */
    public WfAttachment(String attachmentId, Long ownerUserId, String fieldName,
            String originalName, String storageKey, String contentType, long fileSize,
            String sha256, WorkflowAttachmentStatus status, LocalDateTime expireTime,
            String draftId, String processInstanceId, String taskId, String nodeKey,
            LocalDateTime boundTime, LocalDateTime storageDeletedTime,
            int cleanupRetryCount, LocalDateTime cleanupNextRetryTime,
            String cleanupLastErrorCode, LocalDateTime createTime,
            LocalDateTime updateTime)
    {
        this(attachmentId, ownerUserId, fieldName, originalName, storageKey, contentType,
                fileSize, sha256, status, expireTime, draftId, processInstanceId, taskId,
                nodeKey, boundTime, storageDeletedTime, cleanupRetryCount,
                cleanupNextRetryTime, cleanupLastErrorCode, null, null, createTime,
                updateTime);
    }

    /**
     * 兼容不包含草稿关联字段的既有附件构造调用。
     *
     * @param attachmentId String，附件 UUID
     * @param ownerUserId Long，附件所有者
     * @param fieldName String，表单字段名
     * @param originalName String，原始文件名
     * @param storageKey String，私有存储键
     * @param contentType String，安全 MIME
     * @param fileSize long，文件字节数
     * @param sha256 String，内容摘要
     * @param status WorkflowAttachmentStatus，附件状态
     * @param expireTime LocalDateTime，临时失效时间
     * @param processInstanceId String，流程实例主键
     * @param taskId String，任务主键
     * @param nodeKey String，节点 key
     * @param boundTime LocalDateTime，绑定时间
     * @param storageDeletedTime LocalDateTime，物理删除时间
     * @param cleanupRetryCount int，清理重试次数
     * @param cleanupNextRetryTime LocalDateTime，下次清理时间
     * @param cleanupLastErrorCode String，最近清理错误码
     * @param createTime LocalDateTime，创建时间
     * @param updateTime LocalDateTime，更新时间
     * @return 无返回值，draftId 固定为空
     */
    public WfAttachment(String attachmentId, Long ownerUserId, String fieldName,
            String originalName, String storageKey, String contentType, long fileSize,
            String sha256, WorkflowAttachmentStatus status, LocalDateTime expireTime,
            String processInstanceId, String taskId, String nodeKey, LocalDateTime boundTime,
            LocalDateTime storageDeletedTime, int cleanupRetryCount,
            LocalDateTime cleanupNextRetryTime, String cleanupLastErrorCode,
            LocalDateTime createTime, LocalDateTime updateTime)
    {
        this(attachmentId, ownerUserId, fieldName, originalName, storageKey, contentType,
                fileSize, sha256, status, expireTime, null, processInstanceId, taskId,
                nodeKey, boundTime, storageDeletedTime, cleanupRetryCount,
                cleanupNextRetryTime, cleanupLastErrorCode, null, null, createTime,
                updateTime);
    }
}
