package com.ruoyi.flowable.domain.vo;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 不包含授权码、密文或 IV 的 SMTP 配置视图。
 *
 * @param configured boolean，是否已经保存平台单例配置
 * @param smtpHost String，可空 SMTP 服务器
 * @param smtpPort Integer，可空 SMTP 端口
 * @param encryptionMode String，可空加密方式
 * @param username String，可空登录账号
 * @param credentialConfigured boolean，是否存在正式授权码密文
 * @param fromAddress String，可空发件邮箱
 * @param senderName String，可空发件人名称
 * @param revision long，正式配置乐观锁版本；未配置时为 0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowMailConfigView(boolean configured, String smtpHost,
        Integer smtpPort, String encryptionMode, String username,
        boolean credentialConfigured, String fromAddress, String senderName,
        long revision)
{
    /**
     * 构造未配置状态，确保 API 不携带任何空配置字段。
     *
     * @return WorkflowMailConfigView，configured=false 且 revision=0 的安全视图
     */
    public static WorkflowMailConfigView unconfigured()
    {
        return new WorkflowMailConfigView(false, null, null, null, null,
                false, null, null, 0L);
    }
}
