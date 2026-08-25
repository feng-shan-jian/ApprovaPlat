package com.ruoyi.flowable.service.notification;

import java.nio.charset.StandardCharsets;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 邮件通知通道，只负责对领取快照执行 SMTP 副作用。
 */
@Component
public class MailNotificationChannel implements WorkflowNotificationChannel
{
    private final WorkflowMailConfigService mailConfigService;
    private final WorkflowMailFailureClassifier failureClassifier;

    /**
     * 创建邮件通知通道。
     * @param mailConfigService WorkflowMailConfigService，按当前 revision 获取动态发送器
     * @param failureClassifier WorkflowMailFailureClassifier，SMTP 失败脱敏分类器
     * @return void，构造后由 Spring 管理
     */
    public MailNotificationChannel(WorkflowMailConfigService mailConfigService,
            WorkflowMailFailureClassifier failureClassifier)
    {
        this.mailConfigService = mailConfigService;
        this.failureClassifier = failureClassifier;
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
        if (!StringUtils.hasText(row.deliveryTarget()))
        {
            return WorkflowNotificationDeliveryResult.failure(
                    "RECIPIENT_INVALID", "接收人已失效、停用邮件或没有有效邮箱", true);
        }
        try
        {
            // 每次投递都从数据库 revision 边界获取不可变快照，已经取得的旧快照允许完成本次发送。
            WorkflowMailConfigService.MailSenderSnapshot sender =
                    mailConfigService.currentSender();
            MimeMessage message = sender.mailSender().createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(
                    message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(sender.fromAddress(), sender.senderName());
            helper.setTo(row.deliveryTarget());
            helper.setSubject(row.title());
            helper.setText(row.content(), false);
            message.setHeader("Message-ID", "<" + row.idempotencyKey()
                    + "@approvaplat.notification>");
            message.setHeader("X-ApprovaPlat-Idempotency-Key", row.idempotencyKey());
            sender.mailSender().send(message);
            return WorkflowNotificationDeliveryResult.delivered();
        }
        catch (Exception exception)
        {
            // SMTP 主机、账号、底层异常消息和收件地址均不得进入 outbox 错误摘要。
            return failureClassifier.deliveryFailure(exception);
        }
    }
}
