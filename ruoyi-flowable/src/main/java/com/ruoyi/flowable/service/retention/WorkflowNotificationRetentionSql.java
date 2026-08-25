package com.ruoyi.flowable.service.retention;

/**
 * 通知领域保留批次 SQL；只删除明确终态历史，不拥有任何通知状态迁移。
 */
final class WorkflowNotificationRetentionSql
{
    static final String OUTBOX_SELECT = "select outbox_id from wf_notification_outbox "
            + "where status in ('PROCESSED','CANCELLED') and processed_time<=? "
            + "order by processed_time,outbox_id limit ? for update skip locked";
    static final String OUTBOX_DELETE_PREFIX = "delete from wf_notification_outbox where outbox_id in (";
    static final String OUTBOX_DELETE_SUFFIX = ") and status in ('PROCESSED','CANCELLED') and processed_time<=?";
    static final String OUTBOX_OLDEST = "select min(processed_time) from wf_notification_outbox "
            + "where status in ('PROCESSED','CANCELLED') and processed_time is not null";

    static final String INBOX_SELECT = "select notification_id from wf_notification_inbox "
            + "where read_status='READ' and read_time<=? "
            + "order by read_time,notification_id limit ? for update skip locked";
    static final String INBOX_DELETE_PREFIX = "delete from wf_notification_inbox where notification_id in (";
    static final String INBOX_DELETE_SUFFIX = ") and read_status='READ' and read_time<=?";
    static final String INBOX_OLDEST = "select min(read_time) from wf_notification_inbox "
            + "where read_status='READ' and read_time is not null";

    private WorkflowNotificationRetentionSql()
    {
    }
}
