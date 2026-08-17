package com.ruoyi.flowable.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 工作流持久化数据生命周期和有界批处理配置。
 */
@Component
@ConfigurationProperties(prefix = "flowable.data-retention")
public class WorkflowDataRetentionProperties
{
    /** 单个数据域每轮最多领取的记录数，限制事务锁定范围。 */
    private int batchSize = 100;
    /** 成功或取消通知 outbox 的默认保留期。 */
    private Duration notificationOutboxRetention = Duration.ofDays(90);
    /** 已完成运行事件请求的默认保留期。 */
    private Duration runtimeEventRetention = Duration.ofDays(90);
    /** 已提交或已删除流程草稿的默认保留期。 */
    private Duration processDraftRetention = Duration.ofDays(30);
    /** 已完成协作消息和协作 outbox 的默认保留期。 */
    private Duration collaborationRetention = Duration.ofDays(90);
    /** 已完成物理删除的附件元数据默认保留期。 */
    private Duration attachmentMetadataRetention = Duration.ofDays(90);
    /** 已读站内通知的默认保留期。 */
    private Duration notificationInboxRetention = Duration.ofDays(180);
    /** 已结束流程的 BPMN 事件审计默认保留期。 */
    private Duration bpmnEventAuditRetention = Duration.ofDays(180);
    /** 已完成或已升级 SLA 执行及其审计默认保留期。 */
    private Duration taskSlaRetention = Duration.ofDays(180);
    /** 已结束流程的已读或逻辑删除抄送默认保留期。 */
    private Duration copyRetention = Duration.ofDays(180);
    /** 已结束流程的受控循环执行记录默认保留期。 */
    private Duration controlledLoopRetention = Duration.ofDays(180);
    /** 调度器首次执行前的等待时间。 */
    private Duration initialDelay = Duration.ofMinutes(5);
    /** 相邻两轮统一保留任务之间的固定等待时间。 */
    private Duration fixedDelay = Duration.ofHours(1);

    /**
     * 获取单域单轮领取上限。
     * @return int，1 至 5000 的批次大小
     */
    public int getBatchSize()
    {
        return batchSize;
    }

    /**
     * 设置单域单轮领取上限。
     * @param batchSize int，必须处于 1 至 5000 范围
     * @return void，无返回值
     */
    public void setBatchSize(int batchSize)
    {
        if (batchSize < 1 || batchSize > 5000)
        {
            throw new IllegalArgumentException("工作流数据保留批次必须处于1至5000范围");
        }
        this.batchSize = batchSize;
    }

    /** 获取通知 outbox 保留期。 @return Duration，成功或取消记录保留时长 */
    public Duration getNotificationOutboxRetention() { return notificationOutboxRetention; }
    /** 设置通知 outbox 保留期。 @param value Duration，正数且不超过 3650 天 @return void */
    public void setNotificationOutboxRetention(Duration value) { notificationOutboxRetention = requireRetention(value); }
    /** 获取运行事件保留期。 @return Duration，已完成请求保留时长 */
    public Duration getRuntimeEventRetention() { return runtimeEventRetention; }
    /** 设置运行事件保留期。 @param value Duration，正数且不超过 3650 天 @return void */
    public void setRuntimeEventRetention(Duration value) { runtimeEventRetention = requireRetention(value); }
    /** 获取终态草稿保留期。 @return Duration，SUBMITTED/DELETED 草稿保留时长 */
    public Duration getProcessDraftRetention() { return processDraftRetention; }
    /** 设置终态草稿保留期。 @param value Duration，正数且不超过 3650 天 @return void */
    public void setProcessDraftRetention(Duration value) { processDraftRetention = requireRetention(value); }
    /** 获取协作数据保留期。 @return Duration，已完成 message/outbox 保留时长 */
    public Duration getCollaborationRetention() { return collaborationRetention; }
    /** 设置协作数据保留期。 @param value Duration，正数且不超过 3650 天 @return void */
    public void setCollaborationRetention(Duration value) { collaborationRetention = requireRetention(value); }
    /** 获取附件元数据保留期。 @return Duration，物理删除后元数据保留时长 */
    public Duration getAttachmentMetadataRetention() { return attachmentMetadataRetention; }
    /** 设置附件元数据保留期。 @param value Duration，正数且不超过 3650 天 @return void */
    public void setAttachmentMetadataRetention(Duration value) { attachmentMetadataRetention = requireRetention(value); }
    /** 获取已读 inbox 保留期。 @return Duration，已读通知保留时长 */
    public Duration getNotificationInboxRetention() { return notificationInboxRetention; }
    /** 设置已读 inbox 保留期。 @param value Duration，正数且不超过 3650 天 @return void */
    public void setNotificationInboxRetention(Duration value) { notificationInboxRetention = requireRetention(value); }
    /** 获取 BPMN 事件审计保留期。 @return Duration，已结束流程事件审计保留时长 */
    public Duration getBpmnEventAuditRetention() { return bpmnEventAuditRetention; }
    /** 设置 BPMN 事件审计保留期。 @param value Duration，正数且不超过 3650 天 @return void */
    public void setBpmnEventAuditRetention(Duration value) { bpmnEventAuditRetention = requireRetention(value); }
    /** 获取 SLA 执行保留期。 @return Duration，COMPLETED/ESCALATED 执行保留时长 */
    public Duration getTaskSlaRetention() { return taskSlaRetention; }
    /** 设置 SLA 执行保留期。 @param value Duration，正数且不超过 3650 天 @return void */
    public void setTaskSlaRetention(Duration value) { taskSlaRetention = requireRetention(value); }
    /** 获取流程抄送保留期。 @return Duration，已结束流程可清理抄送保留时长 */
    public Duration getCopyRetention() { return copyRetention; }
    /** 设置流程抄送保留期。 @param value Duration，正数且不超过 3650 天 @return void */
    public void setCopyRetention(Duration value) { copyRetention = requireRetention(value); }
    /** 获取受控循环执行保留期。 @return Duration，已结束流程循环记录保留时长 */
    public Duration getControlledLoopRetention() { return controlledLoopRetention; }
    /** 设置受控循环执行保留期。 @param value Duration，正数且不超过 3650 天 @return void */
    public void setControlledLoopRetention(Duration value) { controlledLoopRetention = requireRetention(value); }
    /** 获取首次调度等待时间。 @return Duration，允许为零的首次等待时长 */
    public Duration getInitialDelay() { return initialDelay; }
    /** 设置首次调度等待时间。 @param value Duration，零至七天 @return void */
    public void setInitialDelay(Duration value) { initialDelay = requireScheduleDelay(value, true); }
    /** 获取固定调度间隔。 @return Duration，正数调度间隔 */
    public Duration getFixedDelay() { return fixedDelay; }
    /** 设置固定调度间隔。 @param value Duration，正数且不超过七天 @return void */
    public void setFixedDelay(Duration value) { fixedDelay = requireScheduleDelay(value, false); }

    /**
     * 校验数据保留期，避免零值误删刚进入终态的数据，也避免无限大配置掩盖积压。
     * @param value Duration，待校验保留期
     * @return Duration，通过校验的原值
     */
    private Duration requireRetention(Duration value)
    {
        if (value == null || value.isZero() || value.isNegative()
                || value.compareTo(Duration.ofDays(3650)) > 0)
        {
            throw new IllegalArgumentException("工作流数据保留期必须大于0且不能超过3650天");
        }
        return value;
    }

    /**
     * 校验调度等待时间，首次等待允许为零，固定间隔必须大于零。
     * @param value Duration，待校验调度时间
     * @param allowZero boolean，是否允许首次立即执行
     * @return Duration，通过校验的原值
     */
    private Duration requireScheduleDelay(Duration value, boolean allowZero)
    {
        if (value == null || value.isNegative() || (!allowZero && value.isZero())
                || value.compareTo(Duration.ofDays(7)) > 0)
        {
            throw new IllegalArgumentException("工作流数据保留调度间隔不合法");
        }
        return value;
    }
}
