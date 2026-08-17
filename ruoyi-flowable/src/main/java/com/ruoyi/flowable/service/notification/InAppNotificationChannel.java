package com.ruoyi.flowable.service.notification;

import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;

/**
 * 站内通知通道，将 outbox 不可变投影幂等写入用户 inbox。
 */
@Component
public class InAppNotificationChannel implements WorkflowNotificationChannel
{
    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建站内通知通道。
     * @param jdbcTemplate JdbcTemplate，用户目录和 notification inbox 正式数据访问入口
     * @return void，构造后由 Spring 管理
     */
    public InAppNotificationChannel(JdbcTemplate jdbcTemplate)
    {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** @return String，固定为 INBOX。 */
    @Override
    public String channel()
    {
        return "INBOX";
    }

    /**
     * 校验接收人偏好并幂等创建站内信，任何投影漂移都拒绝 outbox 假完成。
     * @param row WorkflowNotificationOutboxRecord，领取事务已经提交的站内通知快照
     * @return WorkflowNotificationDeliveryResult，站内信落库成功或接收人永久失效结果
     */
    @Override
    public WorkflowNotificationDeliveryResult deliver(WorkflowNotificationOutboxRecord row)
    {
        Integer eligible = jdbcTemplate.queryForObject(
                "select count(*) from sys_user u left join wf_notification_preference p on p.user_id=u.user_id " +
                "where u.user_id=? and u.status='0' and u.del_flag='0' and coalesce(p.inbox_enabled,1)=1",
                Integer.class, row.recipientUserId());
        if (eligible == null || eligible != 1)
        {
            return WorkflowNotificationDeliveryResult.failure(
                    "RECIPIENT_INVALID", "接收人已失效或停用站内通知", true);
        }
        // notification_key 必须复用 8.1.0 存量回填算法，确保新写数据与升级后的唯一关联语义一致。
        // 站内信写入必须仍对应当前领取 revision；业务终态已经取消租约时不得新增用户可见通知。
        jdbcTemplate.update("insert into wf_notification_inbox " +
                "(outbox_id,notification_key,source_type,source_id,recipient_user_id,event_type," +
                "title,content,process_instance_id,task_id,route_path,read_status,create_time) " +
                "select outbox_id,sha2(concat_ws('|',source_type,source_id,event_type),256)," +
                "source_type,source_id,recipient_user_id,event_type,title,content," +
                "process_instance_id,task_id,route_path,'UNREAD',current_timestamp(3) " +
                "from wf_notification_outbox where outbox_id=? and status='DELIVERING' " +
                "and revision=? on duplicate key update " +
                "outbox_id=wf_notification_inbox.outbox_id", row.outboxId(), row.revision());
        List<InboxFact> facts = jdbcTemplate.query(
                "select inbox.notification_key,inbox.source_type,inbox.source_id," +
                "inbox.recipient_user_id,inbox.event_type,inbox.title,inbox.content," +
                "inbox.process_instance_id,inbox.task_id,inbox.route_path," +
                "sha2(concat_ws('|',outbox.source_type,outbox.source_id,outbox.event_type),256) " +
                "as expected_notification_key,outbox.source_type as expected_source_type," +
                "outbox.source_id as expected_source_id " +
                "from wf_notification_inbox inbox join wf_notification_outbox outbox " +
                "on outbox.outbox_id=inbox.outbox_id where inbox.outbox_id=? for share",
                (result, rowNum) -> new InboxFact(result.getString("notification_key"),
                        result.getString("source_type"), result.getString("source_id"),
                        result.getLong("recipient_user_id"), result.getString("event_type"),
                        result.getString("title"), result.getString("content"),
                        result.getString("process_instance_id"), result.getString("task_id"),
                        result.getString("route_path"),
                        result.getString("expected_notification_key"),
                        result.getString("expected_source_type"),
                        result.getString("expected_source_id")),
                row.outboxId());
        if (facts.size() != 1 || !facts.get(0).matches(row))
        {
            throw new ServiceException("站内通知持久化事实与 outbox 不一致", HttpStatus.ERROR);
        }
        return WorkflowNotificationDeliveryResult.delivered();
    }

    /** 站内信与 outbox 之间必须一致的不可变业务投影。 */
    private record InboxFact(String notificationKey, String sourceType, String sourceId,
            long recipientUserId, String eventType, String title, String content,
            String processInstanceId, String taskId, String routePath,
            String expectedNotificationKey, String expectedSourceType, String expectedSourceId)
    {
        /**
         * 核对站内信投影与领取快照。
         * @param row WorkflowNotificationOutboxRecord，当前领取快照
         * @return boolean，稳定关联、接收人、事件、正文和路由完全一致时为 true
         */
        private boolean matches(WorkflowNotificationOutboxRecord row)
        {
            return Objects.equals(notificationKey, expectedNotificationKey)
                    && Objects.equals(sourceType, expectedSourceType)
                    && Objects.equals(sourceId, expectedSourceId)
                    && recipientUserId == row.recipientUserId()
                    && Objects.equals(eventType, row.eventType())
                    && Objects.equals(title, row.title())
                    && Objects.equals(content, row.content())
                    && Objects.equals(processInstanceId, row.processInstanceId())
                    && Objects.equals(taskId, row.taskId())
                    && Objects.equals(routePath, row.routePath());
        }
    }
}
