package com.ruoyi.flowable.service.attachment;

import java.io.InputStream;

/**
 * 已完成对象授权的附件下载句柄，仅在 Controller 内使用且不得序列化为 JSON。
 *
 * @param content InputStream，已在同一打开通道完成大小与摘要校验的一次性文件流
 * @param originalName String，安全响应头使用的原始文件名
 * @param contentType String，服务端探测并持久化的 MIME 类型
 * @param fileSize long，可信文件大小
 * @param sha256 String，文件内容摘要，可作为强 ETag
 */
public record WorkflowAttachmentDownload(
        InputStream content,
        String originalName,
        String contentType,
        long fileSize,
        String sha256)
{
}
