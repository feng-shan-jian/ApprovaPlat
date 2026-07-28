package com.ruoyi.flowable.domain.vo;

import java.time.LocalDateTime;
import com.ruoyi.flowable.domain.WorkflowAttachmentStatus;

/**
 * 工作流附件对外安全元数据，不包含所有者主键、磁盘路径或可绕过授权的静态 URL。
 *
 * @param attachmentId String，客户端后续绑定或下载使用的附件 UUID
 * @param fieldName String，附件所属表单字段名
 * @param originalName String，规范化后的原始文件名
 * @param contentType String，服务端探测的 MIME 类型
 * @param fileSize long，服务端计算的实际文件大小
 * @param sha256 String，内容 SHA-256 摘要
 * @param status WorkflowAttachmentStatus，附件当前生命周期状态
 * @param expireTime LocalDateTime，临时附件失效时间
 * @param processInstanceId String，绑定的流程实例主键；临时附件为空
 * @param taskId String，提交附件的任务主键；开始节点附件为空
 * @param nodeKey String，提交附件的 BPMN 节点 key；临时附件为空
 */
public record WorkflowAttachmentView(
        String attachmentId,
        String fieldName,
        String originalName,
        String contentType,
        long fileSize,
        String sha256,
        WorkflowAttachmentStatus status,
        LocalDateTime expireTime,
        String processInstanceId,
        String taskId,
        String nodeKey)
{
}
