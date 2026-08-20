package com.ruoyi.flowable.service.notification;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;

/**
 * 通知写入器，在调用方当前事务内直写站内信并仅为 EMAIL、SMS 登记可靠 Outbox。
 */
@Service
public class WorkflowNotificationWriter
{
    private static final Set<String> REQUIRED_INBOX_SOURCE_TYPES = Set.of("SLA", "BPMN_EVENT");
    private static final int MAX_TITLE_LENGTH = 160;
    private static final int MAX_CONTENT_LENGTH = 700;

    private static final String INSERT_INBOX_SQL =
            "insert into wf_notification_inbox " +
            "(notification_key,source_type,source_id,recipient_user_id,event_type,title," +
            "content,process_instance_id,task_id,route_path,read_status,create_time) " +
            "values (sha2(concat_ws('|',?,?,?),256),?,?,?,?,?,?,?,?,?,'UNREAD'," +
            "current_timestamp(3))";

    private static final String INSERT_OUTBOX_SQL =
            "insert into wf_notification_outbox " +
            "(idempotency_key,source_type,source_id,event_type,channel,recipient_user_id," +
            "process_definition_key,process_instance_id,task_id,task_definition_key," +
            "actor_user_id,title,content,sms_template_id,route_path,status,attempt_count," +
            "max_attempts,next_attempt_at,revision,create_time) values (?,?,?,?,?,?,?,?," +
            "?,?,?,?,?,?,?,'PENDING',0,?,current_timestamp(3),0,current_timestamp(3))";

    private final JdbcTemplate jdbcTemplate;
    private final WorkflowNotificationOutboxService outboxService;

    /**
     * 创建通知写入器。
     * @param jdbcTemplate JdbcTemplate，inbox 和外部 Outbox 批量写入入口
     * @param outboxService WorkflowNotificationOutboxService，新增外部投递审计入口
     * @return void，构造后由 Spring 管理
     */
    public WorkflowNotificationWriter(JdbcTemplate jdbcTemplate,
            WorkflowNotificationOutboxService outboxService)
    {
        this.jdbcTemplate = jdbcTemplate;
        this.outboxService = outboxService;
    }

    /**
     * 在调用方当前事务中原子写入计划内全部站内信和外部投递记录。
     * @param plan NotificationPlan，已经完成策略、身份、偏好和文案解析的计划
     * @return WriteResult，本次首次新增的通道记录数和实际可登记接收人
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public WriteResult write(NotificationPlan plan)
    {
        requireWriteTransaction();
        if (plan == null) throw invalid("通知写入计划不能为空");
        if (plan.isEmpty()) return WriteResult.empty();

        List<InboxRow> inboxRows = new ArrayList<>();
        List<OutboxRow> outboxRows = new ArrayList<>();
        LinkedHashSet<String> recipients = new LinkedHashSet<>();
        int insertedRecordCount = 0;
        for (NotificationPlan.Notification notification : plan.notifications())
        {
            if (notification == null || notification.channels().isEmpty())
            {
                throw invalid("通知写入计划不完整");
            }
            for (String channel : notification.channels())
            {
                if ("INBOX".equals(channel))
                {
                    inboxRows.add(new InboxRow(notification));
                }
                else if ("EMAIL".equals(channel) || "SMS".equals(channel))
                {
                    outboxRows.add(new OutboxRow(notification, channel,
                            sha256(notification.sourceId(), notification.eventType(),
                                    notification.recipientUserId(), channel)));
                }
                else
                {
                    throw invalid("通知写入计划包含不支持的通道");
                }
            }
        }

        if (!inboxRows.isEmpty())
        {
            for (InboxRow row : inboxRows)
            {
                KeyHolder keyHolder = new GeneratedKeyHolder();
                try
                {
                    jdbcTemplate.update(connection -> {
                    var statement = connection.prepareStatement(INSERT_INBOX_SQL,
                            java.sql.Statement.RETURN_GENERATED_KEYS);
                    Object[] parameters = row.parameters();
                    for (int i = 0; i < parameters.length; i++) statement.setObject(i + 1, parameters[i]);
                    return statement;
                    }, keyHolder);
                    if (keyHolder.getKey() == null)
                        throw new ServiceException("站内通知主键读取失败", HttpStatus.ERROR);
                    insertedRecordCount++;
                    recipients.add(row.notification().recipientUserId());
                }
                catch (DuplicateKeyException duplicate)
                {
                    recipients.add(row.notification().recipientUserId());
                }
            }
        }
        if (!outboxRows.isEmpty())
        {
            for (OutboxRow row : outboxRows)
            {
                KeyHolder keyHolder = new GeneratedKeyHolder();
                try
                {
                    jdbcTemplate.update(connection -> {
                    var statement = connection.prepareStatement(INSERT_OUTBOX_SQL,
                            java.sql.Statement.RETURN_GENERATED_KEYS);
                    Object[] parameters = row.parameters();
                    for (int i = 0; i < parameters.length; i++) statement.setObject(i + 1, parameters[i]);
                    return statement;
                    }, keyHolder);
                    if (keyHolder.getKey() == null)
                        throw new ServiceException("通知 Outbox 主键读取失败", HttpStatus.ERROR);
                    insertedRecordCount++;
                    recipients.add(row.notification().recipientUserId());
                    Number id = keyHolder.getKey();
                    outboxService.recordEnqueued(id.longValue(), "flowable", "审批事务内登记外部通知 Outbox");
                }
                catch (DuplicateKeyException duplicate)
                {
                    recipients.add(row.notification().recipientUserId());
                }
            }
        }
        return new WriteResult(insertedRecordCount, recipients);
    }

    /**
     * 在当前业务事务内直接写入 SLA 或 BPMN 必达站内通知，不读取用户 inbox 偏好。
     * @param notification WorkflowInboxNotification，调用方冻结的必达站内通知
     * @return Long，首次写入或幂等命中的通知主键；接收用户无效时为 null
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public Long writeRequiredInbox(WorkflowInboxNotification notification)
    {
        requireWriteTransaction();
        WorkflowInboxNotification fact = validateRequiredInbox(notification);
        long recipientUserId = Long.parseLong(fact.recipientUserId());
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("insert into wf_notification_inbox " +
                "(notification_key,source_type,source_id,recipient_user_id,event_type,title," +
                "content,process_instance_id,task_id,route_path,read_status,create_time) " +
                "select sha2(concat_ws('|',?,?,?),256),?,?,?,?,?,?,?,?,?,'UNREAD'," +
                "current_timestamp(3) from sys_user u where u.user_id=? " +
                "and u.status='0' and u.del_flag='0' on duplicate key update " +
                "notification_id=last_insert_id(notification_id)",
                java.sql.Statement.RETURN_GENERATED_KEYS);
            Object[] parameters = { fact.sourceType(), fact.sourceId(), fact.eventType(),
                    fact.sourceType(), fact.sourceId(), recipientUserId, fact.eventType(),
                    fact.title(), fact.content(), fact.processInstanceId(), fact.taskId(),
                    fact.routePath(), recipientUserId };
            for (int i = 0; i < parameters.length; i++) statement.setObject(i + 1, parameters[i]);
            return statement;
        }, keyHolder);
        Number id = keyHolder.getKey();
        return id == null ? null : id.longValue();
    }

    /**
     * 校验必达站内通知枚举、身份和数据库字段边界。
     * @param notification WorkflowInboxNotification，调用方原始通知事实
     * @return WorkflowInboxNotification，可直接持久化的规范事实
     */
    private WorkflowInboxNotification validateRequiredInbox(
            WorkflowInboxNotification notification)
    {
        if (notification == null) throw invalid("必达站内通知不能为空");
        String sourceType = upper(notification.sourceType());
        if (!REQUIRED_INBOX_SOURCE_TYPES.contains(sourceType))
            throw invalid("必达站内通知来源类型不合法");
        String eventType = upper(notification.eventType());
        if (!eventType.matches("[A-Z][A-Z0-9_]{1,39}"))
            throw invalid("必达站内通知事件类型不合法");
        String recipientUserId = normalized(notification.recipientUserId(), 19,
                "必达站内通知接收人不合法");
        if (!recipientUserId.matches("[1-9][0-9]{0,18}"))
            throw invalid("必达站内通知接收人不合法");
        return new WorkflowInboxNotification(sourceType,
                normalizedSourceId(notification.sourceId()), eventType, recipientUserId,
                normalized(notification.processInstanceId(), 64,
                        "必达站内通知流程实例主键不合法"),
                optional(notification.taskId(), 64),
                normalized(notification.title(), MAX_TITLE_LENGTH, "必达站内通知标题不合法"),
                normalized(notification.content(), MAX_CONTENT_LENGTH, "必达站内通知正文不合法"),
                normalized(notification.routePath(), 500, "必达站内通知业务路由不合法"));
    }

    /**
     * 要求写入加入调用方当前可写事务，确保业务状态与两张通知表原子提交。
     * @return void，缺少事务或只读事务时抛出服务端异常
     */
    private void requireWriteTransaction()
    {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
        {
            throw new ServiceException("通知必须在 Flowable 写事务中登记", HttpStatus.ERROR);
        }
    }

    /**
     * 规范化必填文本。
     * @param value String，待校验文本
     * @param max int，最大长度
     * @param message String，稳定错误提示
     * @return String，合法规范值
     */
    private String normalized(String value, int max, String message)
    {
        if (!StringUtils.hasText(value)) throw invalid(message);
        String normalized = value.trim();
        if (normalized.length() > max || normalized.chars().anyMatch(Character::isISOControl))
            throw invalid(message);
        return normalized;
    }

    /**
     * 规范化可选文本。
     * @param value String，可空文本
     * @param max int，最大长度
     * @return String，规范值；无内容时为 null
     */
    private String optional(String value, int max)
    {
        return StringUtils.hasText(value) ? normalized(value, max, "通知字段不合法") : null;
    }

    /**
     * 校验稳定来源键为可见 ASCII。
     * @param value String，业务来源键
     * @return String，可持久化来源键
     */
    private String normalizedSourceId(String value)
    {
        String sourceId = normalized(value, 191, "通知来源标识不合法");
        if (sourceId.chars().anyMatch(character -> character < 0x21 || character > 0x7e))
            throw invalid("通知来源标识不合法");
        return sourceId;
    }

    /**
     * 规范化枚举为固定 Locale 大写值。
     * @param value String，可空枚举文本
     * @return String，规范枚举
     */
    private String upper(String value)
    {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * 构造 HTTP 400 参数异常。
     * @param message String，稳定错误提示
     * @return ServiceException，参数异常
     */
    private ServiceException invalid(String message)
    {
        return new ServiceException(message, HttpStatus.BAD_REQUEST);
    }

    /**
     * 以零字节分隔业务身份字段并计算 Outbox 稳定 SHA-256 幂等键。
     * @param values String[]，来源、事件、接收人和外部通道
     * @return String，64 位小写十六进制摘要
     */
    private String sha256(String... values)
    {
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values)
            {
                digest.update((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        }
        catch (Exception exception)
        {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    /**
     * 通知写入结果。
     *
     * @param channelRecordCount int，本次首次新增的通知通道记录数，兼容 HTTP outboxCount 语义
     * @param recipientUserIds Set&lt;String&gt;，至少存在一个实际通道的接收用户
     */
    public record WriteResult(int channelRecordCount, Set<String> recipientUserIds)
    {
        /**
         * 冻结实际接收人集合。
         * @param recipientUserIds Set&lt;String&gt;，实际可登记接收人
         * @return void，构造后的集合不可修改
         */
        public WriteResult
        {
            recipientUserIds = recipientUserIds == null ? Set.of()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(recipientUserIds));
        }

        /**
         * 创建没有任何可登记通道的空结果。
         * @return WriteResult，数量为零且接收人为空
         */
        private static WriteResult empty()
        {
            return new WriteResult(0, Set.of());
        }
    }

    private record InboxRow(NotificationPlan.Notification notification)
    {
        /**
         * 按 INSERT_INBOX_SQL 顺序构造参数。
         * @return Object[]，参数化批量站内信写入值
         */
        private Object[] parameters()
        {
            return new Object[] { notification.sourceType(), notification.sourceId(),
                    notification.eventType(), notification.sourceType(), notification.sourceId(),
                    Long.valueOf(notification.recipientUserId()), notification.eventType(),
                    notification.title(), notification.content(),
                    notification.processInstanceId(), notification.taskId(),
                    notification.routePath() };
        }
    }

    private record OutboxRow(NotificationPlan.Notification notification,
            String channel, String idempotencyKey)
    {
        /**
         * 按 INSERT_OUTBOX_SQL 顺序构造参数。
         * @return Object[]，参数化批量外部投递写入值
         */
        private Object[] parameters()
        {
            return new Object[] { idempotencyKey, notification.sourceType(),
                    notification.sourceId(), notification.eventType(), channel,
                    Long.valueOf(notification.recipientUserId()),
                    notification.processDefinitionKey(), notification.processInstanceId(),
                    notification.taskId(), notification.taskDefinitionKey(),
                    notification.actorUserId(), notification.title(), notification.content(),
                    "SMS".equals(channel) ? notification.smsTemplateId() : null,
                    notification.routePath(), notification.maxAttempts() };
        }
    }
}
