package com.ruoyi.flowable.service.attachment;

/**
 * 私有存储完成一次文件写入后返回的可信元数据。
 *
 * @param storageKey String，私有根目录内的相对对象键
 * @param originalName String，服务端规范化后的原始文件名
 * @param contentType String，服务端探测的 MIME 类型
 * @param fileSize long，流式写入统计的实际字节数
 * @param sha256 String，文件内容 SHA-256 小写十六进制摘要
 */
public record StoredAttachmentFile(
        String storageKey,
        String originalName,
        String contentType,
        long fileSize,
        String sha256)
{
}
