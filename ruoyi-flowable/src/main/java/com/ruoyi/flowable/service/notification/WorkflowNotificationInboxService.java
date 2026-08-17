package com.ruoyi.flowable.service.notification;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;

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
     * 查询当前用户审批通知，支持未读和已读过滤。
     * @param readStatus String，ALL、UNREAD 或 READ
     * @param limit int，1 至 100
     * @return Map&lt;String,Object&gt;，items 和当前用户未读总数
     */
    @Transactional(readOnly = true)
    public Map<String, Object> inbox(String readStatus, int limit)
    {
        long userId = currentUserId();
        String status = readStatus == null ? "ALL"
                : readStatus.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("ALL", "UNREAD", "READ").contains(status) || limit < 1 || limit > 100)
        {
            throw new ServiceException("通知查询参数不合法", HttpStatus.BAD_REQUEST);
        }
        String filter = "ALL".equals(status) ? "" : " and read_status='" + status + "'";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select notification_id as notificationId,event_type as eventType,title,content," +
                "process_instance_id as processInstanceId,task_id as taskId,route_path as routePath," +
                "read_status as readStatus,create_time as createTime,read_time as readTime " +
                "from wf_notification_inbox where recipient_user_id=?" + filter +
                " order by notification_id desc limit ?", userId, limit);
        Integer unread = jdbcTemplate.queryForObject(
                "select count(*) from wf_notification_inbox " +
                "where recipient_user_id=? and read_status='UNREAD'", Integer.class, userId);
        return Map.of("items", rows, "unreadCount", unread == null ? 0 : unread);
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
        try
        {
            return Long.parseLong(identityResolver.resolveCurrentIdentity().userId());
        }
        catch (RuntimeException exception)
        {
            throw new ServiceException("当前用户身份无效", HttpStatus.UNAUTHORIZED);
        }
    }
}
