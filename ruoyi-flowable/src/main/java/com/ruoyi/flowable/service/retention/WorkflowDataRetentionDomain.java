package com.ruoyi.flowable.service.retention;

/**
 * 统一数据保留任务允许暴露的固定低基数数据域。
 */
public enum WorkflowDataRetentionDomain
{
    NOTIFICATION_OUTBOX("notification_outbox"),
    RUNTIME_EVENT("runtime_event"),
    PROCESS_DRAFT("process_draft"),
    COLLABORATION_MESSAGE("collaboration_message"),
    COLLABORATION_OUTBOX("collaboration_outbox"),
    ATTACHMENT_METADATA("attachment_metadata"),
    NOTIFICATION_INBOX("notification_inbox"),
    BPMN_EVENT_AUDIT("bpmn_event_audit"),
    TASK_SLA_EXECUTION("task_sla_execution"),
    COPY("copy"),
    CONTROLLED_LOOP_EXECUTION("controlled_loop_execution");

    /** 指标标签使用的稳定值，不包含主键等高基数数据。 */
    private final String metricTag;

    /**
     * 创建固定数据域。
     * @param metricTag String，Prometheus domain 标签值
     * @return 无返回值，枚举由 JVM 初始化
     */
    WorkflowDataRetentionDomain(String metricTag)
    {
        this.metricTag = metricTag;
    }

    /**
     * 获取固定指标标签。
     * @return String，低基数 domain 标签值
     */
    public String metricTag()
    {
        return metricTag;
    }
}
