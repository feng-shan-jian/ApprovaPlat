package com.ruoyi.flowable.service.notification;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.page.PageResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.dto.WorkflowOperationsQuery;
import com.ruoyi.flowable.service.support.WorkflowPageSupport;

/**
 * 通知运维只读服务，负责脱敏 outbox 查询、筛选和物理分页。
 */
@Service
public class WorkflowNotificationAdminService
{
    private static final Set<String> STATUSES = Set.of(
            "PENDING", "RETRYING", "DELIVERING", "PROCESSED", "DEAD_LETTER", "CANCELLED");
    private static final Set<String> SOURCE_TYPES = Set.of("APPROVAL", "SLA", "BPMN_EVENT");
    private static final Set<String> CHANNELS = Set.of("EMAIL", "SMS");

    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建通知运维查询服务。
     * @param jdbcTemplate JdbcTemplate，通知 outbox 正式数据访问入口
     * @return void，构造后由 Spring 管理
     */
    public WorkflowNotificationAdminService(JdbcTemplate jdbcTemplate)
    {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按领域筛选条件分页查询脱敏 outbox，不返回通知正文、地址或凭据。
     * @param query WorkflowOperationsQuery.NotificationOutbox，状态、来源、事件、通道、时间和关键字筛选
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，每页记录数，最大 100
     * @return PageResult&lt;Map&lt;String,Object&gt;&gt;，当前页记录和筛选后总数
     */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> listOutbox(
            WorkflowOperationsQuery.NotificationOutbox query, int pageNum, int pageSize)
    {
        WorkflowOperationsQuery.NotificationOutbox normalized = query == null
                ? new WorkflowOperationsQuery.NotificationOutbox(null, null, null, null,
                        null, null, null)
                : query;
        validate(normalized);
        WorkflowPageSupport.requireTimeRange(normalized.beginTime(), normalized.endTime());
        SqlFilter filter = buildFilter(normalized);
        return WorkflowPageSupport.query(pageNum, pageSize,
                () -> count(filter),
                (offset, limit) -> loadRows(filter, offset, limit));
    }

    /**
     * 校验筛选枚举，避免绕过 Controller 后把无意义值带入正式运维查询。
     * @param query WorkflowOperationsQuery.NotificationOutbox，已经去除首尾空白的查询条件
     * @return void，枚举合法时正常返回
     */
    private void validate(WorkflowOperationsQuery.NotificationOutbox query)
    {
        if (query.status() != null && !STATUSES.contains(query.status()))
        {
            throw invalid("通知状态不受支持");
        }
        if (query.sourceType() != null && !SOURCE_TYPES.contains(query.sourceType()))
        {
            throw invalid("通知来源类型不受支持");
        }
        if (query.channel() != null && !CHANNELS.contains(query.channel()))
        {
            throw invalid("通知通道不受支持");
        }
        if (query.eventType() != null && !query.eventType().matches("[A-Z][A-Z0-9_]{1,39}"))
        {
            throw invalid("通知事件类型不受支持");
        }
        if (query.keyword() != null && query.keyword().length() > 128)
        {
            throw invalid("通知检索关键字过长");
        }
    }

    /**
     * 将领域筛选转换为参数化 SQL 片段，计数与当前页查询必须复用同一条件。
     * @param query WorkflowOperationsQuery.NotificationOutbox，已完成枚举和时间范围校验的筛选条件
     * @return SqlFilter，where 片段与按顺序绑定的参数
     */
    private SqlFilter buildFilter(WorkflowOperationsQuery.NotificationOutbox query)
    {
        StringBuilder where = new StringBuilder(" where 1=1");
        List<Object> args = new ArrayList<>();
        appendEquals(where, args, "outbox.status", query.status());
        appendEquals(where, args, "outbox.source_type", query.sourceType());
        appendEquals(where, args, "outbox.event_type", query.eventType());
        appendEquals(where, args, "outbox.channel", query.channel());
        if (query.keyword() != null)
        {
            where.append(" and (cast(outbox.outbox_id as char)=? or outbox.source_id like ? or ")
                    .append("outbox.process_instance_id like ? or outbox.task_id like ? or ")
                    .append("outbox.last_error_code like ? or user.user_name like ? or ")
                    .append("user.nick_name like ?)");
            String like = "%" + query.keyword() + "%";
            args.add(query.keyword());
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (query.beginTime() != null)
        {
            where.append(" and outbox.create_time>=?");
            args.add(query.beginTime());
        }
        if (query.endTime() != null)
        {
            where.append(" and outbox.create_time<=?");
            args.add(query.endTime());
        }
        return new SqlFilter(where.toString(), List.copyOf(args));
    }

    /**
     * 追加一个可选等值条件。
     * @param where StringBuilder，当前 where 片段
     * @param args List&lt;Object&gt;，当前参数列表
     * @param column String，代码内固定数据库列名
     * @param value String，可空筛选值
     * @return void，值为空时不改变 SQL
     */
    private void appendEquals(StringBuilder where, List<Object> args, String column, String value)
    {
        if (value == null) return;
        where.append(" and ").append(column).append("=?");
        args.add(value);
    }

    /**
     * 统计当前筛选条件的 outbox 总数。
     * @param filter SqlFilter，参数化查询条件
     * @return long，符合条件的正式 outbox 数量
     */
    private long count(SqlFilter filter)
    {
        Long total = jdbcTemplate.queryForObject(
                "select count(*) from wf_notification_outbox outbox " +
                "left join sys_user user on user.user_id=outbox.recipient_user_id" +
                filter.where(),
                Long.class, filter.args().toArray());
        return total == null ? 0 : total;
    }

    /**
     * 读取一页脱敏 outbox 运维投影。
     * @param filter SqlFilter，参数化查询条件
     * @param offset int，物理偏移量
     * @param pageSize int，当前页大小
     * @return List&lt;Map&lt;String,Object&gt;&gt;，按创建时间和主键稳定倒序的当前页
     */
    private List<Map<String, Object>> loadRows(SqlFilter filter, int offset, int pageSize)
    {
        List<Object> args = new ArrayList<>(filter.args());
        args.add(offset);
        args.add(pageSize);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select outbox.outbox_id as outboxId,outbox.source_type as sourceType," +
                "outbox.source_id as sourceId,outbox.event_type as eventType,outbox.channel," +
                "outbox.recipient_user_id as recipientUserId," +
                "coalesce(nullif(user.nick_name,''),nullif(user.user_name,'')," +
                "cast(outbox.recipient_user_id as char)) as recipientName," +
                "outbox.process_instance_id as processInstanceId,outbox.task_id as taskId," +
                "outbox.status,outbox.delivery_cycle as deliveryCycle," +
                "outbox.attempt_count as attemptCount," +
                "outbox.total_attempt_count as totalAttemptCount," +
                "outbox.max_attempts as maxAttempts,outbox.next_attempt_at as nextAttemptAt," +
                "outbox.last_error_code as lastErrorCode," +
                "outbox.last_error_summary as lastErrorSummary,outbox.revision," +
                "case when outbox.status='DEAD_LETTER' then 1 else 0 end as canCompensate," +
                "outbox.create_time as createTime,outbox.processed_time as processedTime " +
                "from wf_notification_outbox outbox left join sys_user user " +
                "on user.user_id=outbox.recipient_user_id" + filter.where() +
                " order by outbox.create_time desc,outbox.outbox_id desc limit ?,?",
                args.toArray());
        return rows.stream().map(this::sanitizeFailure).toList();
    }

    /**
     * 将数据库错误摘要替换为按稳定错误码生成的用户可见原因，历史脏数据不得直接返回前端。
     * @param row Map&lt;String,Object&gt;，数据库 outbox 运维投影
     * @return Map&lt;String,Object&gt;，不包含地址、主机、账号或供应商原始消息的投影
     */
    private Map<String, Object> sanitizeFailure(Map<String, Object> row)
    {
        LinkedHashMap<String, Object> sanitized = new LinkedHashMap<>(row);
        String code = row.get("lastErrorCode") == null ? null
                : String.valueOf(row.get("lastErrorCode"));
        Object originalSummary = row.get("lastErrorSummary");
        String summary = switch (code == null ? "" : code)
        {
            case "SMTP_NOT_CONFIGURED" -> "SMTP 邮件服务尚未配置";
            case "MAIL_CREDENTIAL_DECRYPT_FAILED" -> "SMTP 授权码无法解密";
            case "SMTP_CONNECT_FAILED" -> "SMTP 服务器无法连接";
            case "SMTP_TIMEOUT" -> "SMTP 连接或发送超时";
            case "SMTP_AUTH_FAILED" -> "SMTP 认证失败";
            case "SMTP_TLS_FAILED" -> "SMTP TLS 或 SSL 协商失败";
            case "SMTP_FROM_REJECTED" -> "SMTP 服务器拒绝发件邮箱";
            case "SMTP_DELIVERY_FAILED" -> "SMTP 投递失败";
            case "RECIPIENT_INVALID" -> "通知接收人当前不可用";
            case "BUSINESS_OBJECT_COMPLETED" -> "关联审批业务已经结束";
            case "LEASE_EXPIRED_AFTER_FINAL_ATTEMPT" -> "最终投递租约过期";
            default -> originalSummary == null ? null : "通知投递失败，详细信息已隐藏";
        };
        sanitized.put("lastErrorSummary", summary);
        return Collections.unmodifiableMap(sanitized);
    }

    /**
     * 构造通知运维查询的稳定参数异常。
     * @param message String，可返回调用方的错误提示
     * @return ServiceException，HTTP 400 业务异常
     */
    private ServiceException invalid(String message)
    {
        return new ServiceException(message, HttpStatus.BAD_REQUEST);
    }

    /** 参数化 SQL 条件及其不可变参数。 */
    private record SqlFilter(String where, List<Object> args) { }
}
