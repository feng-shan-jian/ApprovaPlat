package com.ruoyi.flowable.service.notification;

/**
 * 通知通道返回的稳定、脱敏投递结果。
 *
 * @param success boolean，外部系统或站内持久化是否接受本次投递
 * @param errorCode String，可空稳定错误码
 * @param summary String，可空脱敏错误摘要
 * @param permanent boolean，失败是否不可重试
 */
public record WorkflowNotificationDeliveryResult(boolean success, String errorCode,
        String summary, boolean permanent)
{
    /**
     * 创建成功结果。
     * @return WorkflowNotificationDeliveryResult，成功且无需错误信息的结果
     */
    public static WorkflowNotificationDeliveryResult delivered()
    {
        return new WorkflowNotificationDeliveryResult(true, null, null, false);
    }

    /**
     * 创建失败结果。
     * @param code String，稳定错误码
     * @param summary String，不含地址、凭据和异常栈的摘要
     * @param permanent boolean，是否直接进入死信
     * @return WorkflowNotificationDeliveryResult，失败结果
     */
    public static WorkflowNotificationDeliveryResult failure(String code, String summary,
            boolean permanent)
    {
        return new WorkflowNotificationDeliveryResult(false, code, summary, permanent);
    }
}
