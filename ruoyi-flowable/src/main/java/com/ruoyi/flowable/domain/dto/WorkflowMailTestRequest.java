package com.ruoyi.flowable.domain.dto;

/**
 * 使用弹窗中尚未保存的 SMTP 参数发送测试邮件请求。
 *
 * @param smtpHost String，SMTP 服务器主机名或 IP
 * @param smtpPort Integer，SMTP 端口，范围 1 至 65535
 * @param encryptionMode String，NONE、STARTTLS 或 SSL
 * @param username String，SMTP 登录邮箱账号
 * @param credential String，可空新授权码；已有配置且认证身份不变时留空读取当前密文
 * @param fromAddress String，邮件 From 地址
 * @param senderName String，用户可见发件人名称
 * @param testRecipient String，测试邮件收件邮箱
 * @param expectedRevision Long，弹窗加载时读取的正式配置版本
 */
public record WorkflowMailTestRequest(String smtpHost, Integer smtpPort,
        String encryptionMode, String username, String credential,
        String fromAddress, String senderName, String testRecipient,
        Long expectedRevision)
{
}
