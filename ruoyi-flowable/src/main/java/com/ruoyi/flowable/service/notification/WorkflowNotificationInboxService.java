package com.ruoyi.flowable.service.notification;

import java.util.Locale;
import java.util.Set;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.vo.WorkflowNotificationInboxResult;
import com.ruoyi.flowable.domain.vo.WorkflowNotificationItem;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.service.support.WorkflowPageSupport;

/**
 * 用户通知收件箱服务，唯一负责当前用户查询和已读状态迁移。
 */
@Service
public class WorkflowNotificationInboxService
{
    private final JdbcTemplate jdbcTemplate;
    private final WorkflowIdentityResolver identityResolver;

    /**
     * 创建用户通知收件箱服务。
     * @param jdbcTemplate JdbcTemplate，notification inbox 正式数据访问入口
     * @param identityResolver WorkflowIdentityResolver，当前用户身份解析入口
     * @return void，构造后由 Spring 管理
     */
    public WorkflowNotificationInboxService(JdbcTemplate jdbcTemplate,
            WorkflowIdentityResolver identityResolver)
    {
        this.jdbcTemplate = jdbcTemplate;
        this.identityResolver = identityResolver;
    }

    /**
     * 查询当前用户统一通知收件箱，支持状态筛选和物理分页。
     * @param readStatus String，ALL、UNREAD 或 READ
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，每页记录数，最大 100
     * @return WorkflowNotificationInboxResult，当前页、筛选总数和全局未读数
     */
    @Transactional(readOnly = true)
    public WorkflowNotificationInboxResult inbox(String readStatus, int pageNum, int pageSize)
    {
        long userId = currentUserId();
        String status = readStatus == null ? "ALL"
                : readStatus.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("ALL", "UNREAD", "READ").contains(status))
        {
            throw new ServiceException("通知查询参数不合法", HttpStatus.BAD_REQUEST);
        }
        String statusClause = "ALL".equals(status) ? "" : " and read_status=?";
        String countSql = "select count(*) from wf_notification_inbox "
                + "where recipient_user_id=?" + statusClause;
        String unreadSql = "select count(*) from wf_notification_inbox "
                + "where recipient_user_id=? and read_status=?";
        List<Object> filterArgs = "ALL".equals(status) ? List.of(userId) : List.of(userId, status);
        Long unreadValue = jdbcTemplate.queryForObject(unreadSql, Long.class, userId, "UNREAD");
        long unreadCount = unreadValue == null ? 0L : unreadValue;
        var page = WorkflowPageSupport.query(pageNum, pageSize,
                () -> {
                    Long value = jdbcTemplate.queryForObject(countSql, Long.class, filterArgs.toArray());
                    return value == null ? 0L : value;
                },
                (offset, size) -> loadItems(userId, status, offset, size));
        return new WorkflowNotificationInboxResult(page.rows(), page.total(), unreadCount);
    }

    /**
     * 从统一收件箱读取当前页，不连接 outbox、审计或 SLA 执行表。
     * @param userId long，已由身份解析器校验的当前用户主键
     * @param status String，ALL、UNREAD 或 READ
     * @param offset int，数据库分页偏移量
     * @param pageSize int，当前页大小
     * @return List&lt;WorkflowNotificationItem&gt;，当前页通知
     */
    private List<WorkflowNotificationItem> loadItems(long userId, String status, int offset, int pageSize)
    {
        String statusClause = "ALL".equals(status) ? "" : " and read_status=?";
        String sql = "select notification_id,event_type,title,content,process_instance_id,task_id,"
                + "route_path,read_status,create_time,read_time from wf_notification_inbox "
                + "where recipient_user_id=?" + statusClause
                + " order by notification_id desc limit ? offset ?";
        Object[] args = "ALL".equals(status) ? new Object[] { userId, pageSize, offset }
                : new Object[] { userId, status, pageSize, offset };
        return jdbcTemplate.query(sql, (rs, rowNum) -> new WorkflowNotificationItem(
                rs.getLong("notification_id"), rs.getString("event_type"), rs.getString("title"),
                rs.getString("content"), rs.getString("process_instance_id"), rs.getString("task_id"),
                rs.getString("route_path"), rs.getString("read_status"), toLocalDateTime(rs.getTimestamp("create_time")),
                toLocalDateTime(rs.getTimestamp("read_time"))), args);
    }

    /** @param timestamp Timestamp，可为空的数据库时间值；@return LocalDateTime，业务时间或 null */
    private LocalDateTime toLocalDateTime(Timestamp timestamp)
    {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    /**
     * 标记当前用户单条站内审批通知已读。
     * @param notificationId long，通知主键
     * @return void，不属于当前用户时返回 404
     */
    @Transactional(rollbackFor = Exception.class)
    public void markRead(long notificationId)
    {
        long userId = currentUserId();
        int updated = jdbcTemplate.update("update wf_notification_inbox set read_status='READ'," +
                "read_time=current_timestamp(3) where notification_id=? " +
                "and recipient_user_id=? and read_status='UNREAD'", notificationId, userId);
        if (updated == 0)
        {
            Integer exists = jdbcTemplate.queryForObject(
                    "select count(*) from wf_notification_inbox " +
                    "where notification_id=? and recipient_user_id=?",
                    Integer.class, notificationId, userId);
            if (exists == null || exists == 0)
            {
                throw new ServiceException("通知不存在", HttpStatus.NOT_FOUND);
            }
        }
    }

    /**
     * 标记当前用户全部审批通知已读。
     * @return int，实际变更数量
     */
    @Transactional(rollbackFor = Exception.class)
    public int markAllRead()
    {
        return jdbcTemplate.update("update wf_notification_inbox set read_status='READ'," +
                "read_time=current_timestamp(3) " +
                "where recipient_user_id=? and read_status='UNREAD'", currentUserId());
    }

    /**
     * 解析当前正式身份为正数用户主键。
     * @return long，当前有效用户主键
     */
    private long currentUserId()
    {
        return Long.parseLong(identityResolver.resolveCurrentIdentity().userId());
    }
}
