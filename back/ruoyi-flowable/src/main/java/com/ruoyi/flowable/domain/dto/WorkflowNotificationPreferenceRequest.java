package com.ruoyi.flowable.domain.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 当前用户通知通道偏好请求。
 * @param inboxEnabled 是否接收站内审批通知
 * @param emailEnabled 是否接收邮件审批通知
 * @param expectedRevision 当前偏好版本，首次保存为 0
 */
public record WorkflowNotificationPreferenceRequest(
        @NotNull Boolean inboxEnabled,
        @NotNull Boolean emailEnabled,
        @NotNull Integer expectedRevision)
{
}
