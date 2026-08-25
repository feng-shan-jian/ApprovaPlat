package com.ruoyi.flowable.domain.dto;

/**
 * SMTP 邮件服务配置保存请求。
 *
 * @param smtpHost String，SMTP 服务器主机名或 IP
 * @param smtpPort Integer，SMTP 端口，范围 1 至 65535
 * @param encryptionMode String，NONE、STARTTLS 或 SSL
 * @param username String，SMTP 登录邮箱账号
 * @param credential String，可空新授权码；已有配置且认证身份不变时留空表示保持原密文
 * @param fromAddress String，邮件 From 地址
 * @param senderName String，用户可见发件人名称
 * @param expectedRevision Long，客户端读取的乐观锁版本，首次保存固定为 0
 */
public record WorkflowMailConfigRequest(String smtpHost, Integer smtpPort,
        String encryptionMode, String username, String credential,
        String fromAddress, String senderName, Long expectedRevision)
{
}
