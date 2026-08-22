package com.ruoyi.flowable.service.notification;

import java.util.Set;

/**
 * 单个活动任务催办登记结果。
 *
 * @param recipientUserIds Set&lt;String&gt;，至少存在一个投递通道的接收用户
 */
public record WorkflowManualUrgeRegistrationResult(Set<String> recipientUserIds)
{
}
