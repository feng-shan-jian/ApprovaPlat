package com.ruoyi.flowable.domain.vo;

/**
 * 人工催办成功结果。
 *
 * @param recipientCount 实际写入至少一个通知通道的去重收件人数
 */
public record WorkflowManualUrgeView(int recipientCount) { }
