package com.ruoyi.flowable.service.notification;

import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.integration.SmsSendRequest;
import com.ruoyi.system.service.integration.SysSmsService;
import com.ruoyi.system.service.integration.SysSmsService.SmsDeliveryResult;

/**
 * 短信通知通道，只负责对领取快照执行短信供应商副作用。
 */
@Component
public class SmsNotificationChannel implements WorkflowNotificationChannel
{
    private final SysSmsService smsService;

    /**
     * 创建短信通知通道。
     * @param smsService SysSmsService，短信供应商发送出口
     * @return void，构造后由 Spring 管理
     */
    public SmsNotificationChannel(SysSmsService smsService)
    {
        this.smsService = smsService;
    }

    /** @return String，固定为 SMS。 */
    @Override
    public String channel()
    {
        return "SMS";
    }

    /**
     * 使用领取时冻结的有效手机号调用带业务审计的短信供应商服务。
     * @param row WorkflowNotificationOutboxRecord，领取事务已经提交的短信快照
     * @return WorkflowNotificationDeliveryResult，供应商接受结果或脱敏失败分类
     */
    @Override
    public WorkflowNotificationDeliveryResult deliver(WorkflowNotificationOutboxRecord row)
    {
        if (!StringUtils.hasText(row.smsTemplateId()))
        {
            return WorkflowNotificationDeliveryResult.failure(
                    "SMS_TEMPLATE_MISSING", "短信模板配置缺失", true);
        }
        if (!StringUtils.hasText(row.deliveryTarget()))
        {
            return WorkflowNotificationDeliveryResult.failure(
                    "RECIPIENT_INVALID", "接收人已失效、停用短信或没有有效手机号", true);
        }
        try
        {
            SmsDeliveryResult result = smsService.sendBusiness(
                    new SmsSendRequest(row.deliveryTarget(), row.smsTemplateId(),
                            Map.of("content", row.content())), "WORKFLOW");
            return result.success() ? WorkflowNotificationDeliveryResult.delivered()
                    : WorkflowNotificationDeliveryResult.failure(
                            result.errorCode(), result.summary(), false);
        }
        catch (ServiceException exception)
        {
            return WorkflowNotificationDeliveryResult.failure(
                    "SMS_SERVICE_UNAVAILABLE", "短信服务不可用", false);
        }
    }
}
