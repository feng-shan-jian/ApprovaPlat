package com.ruoyi.flowable.domain.vo;

/**
 * 人工催办成功结果。
 *
 * @param urgeEventKey 本次催办意图与其全部通知通道记录共用的稳定业务事件键
 * @param recipientCount 实际写入至少一个通知通道的去重收件人数
 * @param outboxCount 本次登记的通知通道记录数，字段名保持 HTTP 兼容
 */
public record WorkflowManualUrgeView(String urgeEventKey, int recipientCount, int outboxCount) { }
