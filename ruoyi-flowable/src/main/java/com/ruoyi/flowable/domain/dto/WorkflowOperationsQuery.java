package com.ruoyi.flowable.domain.dto;

import java.time.LocalDateTime;

/**
 * 工作流运维列表的领域筛选契约。
 *
 * 各查询使用独立 record 表达真实字段含义，避免 Controller、Service 与 Mapper
 * 之间通过无语义 Map 传递筛选条件。
 */
public final class WorkflowOperationsQuery
{
    private WorkflowOperationsQuery()
    {
    }

    /**
     * BPMN 事件执行审计筛选条件。
     *
     * @param status String，CAPTURED 或 UNMATCHED，可空
     * @param eventType String，ERROR 或 ESCALATION，可空
     * @param sourceType String，事件产生来源类型，可空
     * @param keyword String，审计主键、流程实例、事件编码或节点关键字，可空
     * @param beginTime LocalDateTime，触发时间下界，可空
     * @param endTime LocalDateTime，触发时间上界，可空
     */
    public record BpmnEventAudit(String status, String eventType, String sourceType,
            String keyword, LocalDateTime beginTime, LocalDateTime endTime)
    {
        public BpmnEventAudit
        {
            status = normalize(status);
            eventType = normalize(eventType);
            sourceType = normalize(sourceType);
            keyword = normalize(keyword);
        }
    }

    /**
     * 协作入站消息或 outbox 筛选条件。
     *
     * @param status String，消息正式状态，可空
     * @param keyword String，消息、流程或关联键关键字，可空
     * @param beginTime LocalDateTime，创建时间下界，可空
     * @param endTime LocalDateTime，创建时间上界，可空
     */
    public record Collaboration(String status, String keyword,
            LocalDateTime beginTime, LocalDateTime endTime)
    {
        public Collaboration
        {
            status = normalize(status);
            keyword = normalize(keyword);
        }
    }

    /**
     * 运行事件请求台账筛选条件。
     *
     * @param status String，RECEIVED、PROCESSED 或 FAILED，可空
     * @param eventType String，MESSAGE、SIGNAL 或 RECEIVE，可空
     * @param sourceType String，关联条件类型，可空
     * @param keyword String，请求、事件、关联值或结果码关键字，可空
     * @param beginTime LocalDateTime，首次请求时间下界，可空
     * @param endTime LocalDateTime，首次请求时间上界，可空
     */
    public record RuntimeEvent(String status, String eventType, String sourceType,
            String keyword, LocalDateTime beginTime, LocalDateTime endTime)
    {
        public RuntimeEvent
        {
            status = normalize(status);
            eventType = normalize(eventType);
            sourceType = normalize(sourceType);
            keyword = normalize(keyword);
        }
    }

    /**
     * 通知 outbox 运维列表筛选条件。
     *
     * @param status String，PENDING、RETRYING、DELIVERING、PROCESSED、DEAD_LETTER 或 CANCELLED，可空
     * @param sourceType String，APPROVAL、SLA 或 BPMN_EVENT，可空
     * @param eventType String，通知业务事件类型，可空
     * @param channel String，EMAIL 或 SMS，可空
     * @param keyword String，outbox、来源、流程、任务或错误码关键字，可空
     * @param beginTime LocalDateTime，创建时间下界，可空
     * @param endTime LocalDateTime，创建时间上界，可空
     */
    public record NotificationOutbox(String status, String sourceType, String eventType,
            String channel, String keyword, LocalDateTime beginTime, LocalDateTime endTime)
    {
        public NotificationOutbox
        {
            status = normalize(status);
            sourceType = normalize(sourceType);
            eventType = normalize(eventType);
            channel = normalize(channel);
            keyword = normalize(keyword);
        }
    }

    /**
     * SLA 当前执行列表筛选条件。
     *
     * @param status String，ACTIVE、COMPLETED 或 ESCALATED，可空
     * @param keyword String，执行、实例、任务、节点或办理人关键字，可空
     * @param beginTime LocalDateTime，开始时间下界，可空
     * @param endTime LocalDateTime，开始时间上界，可空
     */
    public record SlaExecution(String status, String keyword,
            LocalDateTime beginTime, LocalDateTime endTime)
    {
        public SlaExecution
        {
            status = normalize(status);
            keyword = normalize(keyword);
        }
    }

    /**
     * SLA 生命周期审计筛选条件。
     *
     * @param actionType String，生命周期动作类型，可空
     * @param keyword String，审计、执行、实例、任务、节点或操作人关键字，可空
     * @param beginTime LocalDateTime，动作时间下界，可空
     * @param endTime LocalDateTime，动作时间上界，可空
     */
    public record SlaAudit(String actionType, String keyword,
            LocalDateTime beginTime, LocalDateTime endTime)
    {
        public SlaAudit
        {
            actionType = normalize(actionType);
            keyword = normalize(keyword);
        }
    }

    /**
     * 将可空查询文本规范为无首尾空白的值。
     * @param value String，用户查询文本
     * @return String，空白值返回 null，其他值返回 trim 后文本
     */
    private static String normalize(String value)
    {
        if (value == null || value.isBlank())
        {
            return null;
        }
        return value.trim();
    }
}
