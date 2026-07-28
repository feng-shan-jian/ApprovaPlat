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
 * @param processInstanceId String，绑定的 Flowable 流程实例主键
 * @param taskId String，可为空的 Flowable 任务主键
 * @param nodeKey String，附件实际提交时对应的 BPMN 节点 key
 * @param boundTime LocalDateTime，成功绑定流程对象的时间
 * @param storageDeletedTime LocalDateTime，私有文件完成物理删除的时间
 * @param cleanupRetryCount int，物理清理连续失败并已调度重试的次数
 * @param cleanupNextRetryTime LocalDateTime，下次允许进入清理候选的时间
 * @param cleanupLastErrorCode String，最近一次清理失败的稳定脱敏错误码
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
        String processInstanceId,
        String taskId,
        String nodeKey,
        LocalDateTime boundTime,
        LocalDateTime storageDeletedTime,
        int cleanupRetryCount,
        LocalDateTime cleanupNextRetryTime,
        String cleanupLastErrorCode,
        LocalDateTime createTime,
        LocalDateTime updateTime)
{
}
