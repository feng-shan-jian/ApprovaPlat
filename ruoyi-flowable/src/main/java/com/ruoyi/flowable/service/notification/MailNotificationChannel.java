package com.ruoyi.flowable.service.notification;

import java.nio.charset.StandardCharsets;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import com.ruoyi.flowable.config.WorkflowNotificationProperties;

/**
 * 邮件通知通道，只负责对领取快照执行 SMTP 副作用。
 */
@Component
public class MailNotificationChannel implements WorkflowNotificationChannel
{
    private final JavaMailSender mailSender;
    private final WorkflowNotificationProperties properties;

    /**
     * 创建邮件通知通道。
     * @param mailSender JavaMailSender，SMTP 发送出口
     * @param properties WorkflowNotificationProperties，发件配置
     * @return void，构造后由 Spring 管理
     */
    public MailNotificationChannel(JavaMailSender mailSender,
            WorkflowNotificationProperties properties)
    {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    /** @return String，固定为 EMAIL。 */
    @Override
    public String channel()
    {
        return "EMAIL";
    }

    /**
     * 使用领取时冻结的有效邮箱发送一次带稳定幂等标识的邮件。
     * @param row WorkflowNotificationOutboxRecord，领取事务已经提交的邮件快照
     * @return WorkflowNotificationDeliveryResult，SMTP 接受结果或脱敏失败分类
     */
    @Override
    public WorkflowNotificationDeliveryResult deliver(WorkflowNotificationOutboxRecord row)
    {
        if (!StringUtils.hasText(properties.getMailFrom()))
        {
            return WorkflowNotificationDeliveryResult.failure(
                    "SMTP_NOT_CONFIGURED", "SMTP 发件配置未启用", false);
        }
        if (!StringUtils.hasText(row.deliveryTarget()))
        {
            return WorkflowNotificationDeliveryResult.failure(
                    "RECIPIENT_INVALID", "接收人已失效、停用邮件或没有有效邮箱", true);
        }
        try
        {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(
                    message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(properties.getMailFrom().trim());
            helper.setTo(row.deliveryTarget());
            helper.setSubject(row.title());
            helper.setText(row.content(), false);
            message.setHeader("Message-ID", "<" + row.idempotencyKey()
                    + "@approvaplat.notification>");
            message.setHeader("X-ApprovaPlat-Idempotency-Key", row.idempotencyKey());
            mailSender.send(message);
            return WorkflowNotificationDeliveryResult.delivered();
        }
        catch (Exception exception)
        {
            // SMTP 主机、账号、异常栈和收件地址不得进入 outbox 错误摘要。
            return WorkflowNotificationDeliveryResult.failure(
                    "SMTP_DELIVERY_FAILED", "SMTP 投递失败", false);
        }
    }
}
