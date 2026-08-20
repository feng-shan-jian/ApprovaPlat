package com.ruoyi.flowable.service.notification;

import java.util.Set;

/**
 * 单个活动任务催办登记结果。
 *
 * @param outboxCount int，本次登记的通知通道记录数
 * @param recipientUserIds Set&lt;String&gt;，至少存在一个投递通道的接收用户
 */
public record WorkflowManualUrgeRegistrationResult(int outboxCount,
        Set<String> recipientUserIds)
{
}
