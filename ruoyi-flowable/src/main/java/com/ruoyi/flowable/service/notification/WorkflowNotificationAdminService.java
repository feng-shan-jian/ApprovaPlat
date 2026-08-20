package com.ruoyi.flowable.service.notification;

import java.util.ArrayList;
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
        appendEquals(where, args, "status", query.status());
        appendEquals(where, args, "source_type", query.sourceType());
        appendEquals(where, args, "event_type", query.eventType());
        appendEquals(where, args, "channel", query.channel());
        if (query.keyword() != null)
        {
            where.append(" and (cast(outbox_id as char)=? or source_id like ? or ")
                    .append("process_instance_id like ? or task_id like ? or last_error_code like ?)");
            String like = "%" + query.keyword() + "%";
            args.add(query.keyword());
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (query.beginTime() != null)
        {
            where.append(" and create_time>=?");
            args.add(query.beginTime());
        }
        if (query.endTime() != null)
        {
            where.append(" and create_time<=?");
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
                "select count(*) from wf_notification_outbox" + filter.where(),
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
        return jdbcTemplate.queryForList("select outbox_id as outboxId,source_type as sourceType," +
                "source_id as sourceId,event_type as eventType,channel," +
                "recipient_user_id as recipientUserId,process_instance_id as processInstanceId," +
                "task_id as taskId,status,delivery_cycle as deliveryCycle," +
                "attempt_count as attemptCount,total_attempt_count as totalAttemptCount," +
                "max_attempts as maxAttempts,next_attempt_at as nextAttemptAt," +
                "last_error_code as lastErrorCode,last_error_summary as lastErrorSummary," +
                "create_time as createTime,processed_time as processedTime " +
                "from wf_notification_outbox" + filter.where() +
                " order by create_time desc,outbox_id desc limit ?,?", args.toArray());
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
