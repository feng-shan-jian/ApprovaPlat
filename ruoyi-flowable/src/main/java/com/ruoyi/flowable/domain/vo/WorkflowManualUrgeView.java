package com.ruoyi.flowable.domain.vo;

/**
 * 人工催办成功结果。
 *
 * @param urgeEventKey 本次催办意图与其全部 outbox 共用的稳定业务事件键
 * @param recipientCount 实际写入至少一个通知通道的去重收件人数
 * @param outboxCount 本次在业务事务内真实新增的 outbox 数量
 */
public record WorkflowManualUrgeView(String urgeEventKey, int recipientCount, int outboxCount) { }
