package com.ruoyi.flowable.service.notification;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.config.WorkflowNotificationProperties;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.runtime.WorkflowNotificationMetrics;

/**
 * Notification outbox 唯一状态所有者，集中处理领取、完成、重试、死信、补偿和取消迁移。
 */
@Service
public class WorkflowNotificationOutboxService
{
    private static final Logger log = LoggerFactory.getLogger(WorkflowNotificationOutboxService.class);
    private static final int MAX_PROCESS_TREE_SIZE = 10_000;

    private final JdbcTemplate jdbcTemplate;
    private final WorkflowNotificationProperties properties;
    private final WorkflowNotificationMetrics metrics;
    private final WorkflowIdentityResolver identityResolver;

    /**
     * 创建 notification outbox 状态所有者。
     * @param jdbcTemplate JdbcTemplate，outbox、Flowable 历史树和租约数据访问入口
     * @param properties WorkflowNotificationProperties，批次、租约和退避配置
     * @param metrics WorkflowNotificationMetrics，固定状态动作指标
     * @param identityResolver WorkflowIdentityResolver，人工补偿操作者身份解析
     * @return void，构造后由 Spring 管理
     */
    public WorkflowNotificationOutboxService(JdbcTemplate jdbcTemplate,
            WorkflowNotificationProperties properties, WorkflowNotificationMetrics metrics,
            WorkflowIdentityResolver identityResolver)
    {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.metrics = metrics;
        this.identityResolver = identityResolver;
    }

    /**
     * 返回 worker 单轮最多处理的 outbox 数量。
     * @return int，配置的有界批次大小
     */
    public int batchSize()
    {
        return properties.getBatchSize();
    }

    /**
     * 在独立短事务中领取一条到期或租约过期的 outbox。
     * @param workerId String，当前节点 worker 标识
     * @return WorkflowNotificationOutboxRecord，领取结果；没有到期记录时为 null
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public WorkflowNotificationOutboxRecord claimNext(String workerId)
    {
        String normalizedWorkerId = normalizedWorkerId(workerId);
        deadLetterOneExhausted(normalizedWorkerId);
        List<WorkflowNotificationOutboxRecord> rows = jdbcTemplate.query(
                "select outbox.outbox_id,outbox.idempotency_key,outbox.event_type,outbox.channel," +
                "outbox.recipient_user_id,case " +
                "when outbox.channel='EMAIL' and user.status='0' and user.del_flag='0' " +
                "and coalesce(preference.email_enabled,1)=1 then nullif(user.email,'') " +
                "when outbox.channel='SMS' and user.status='0' and user.del_flag='0' " +
                "and coalesce(preference.sms_enabled,0)=1 then nullif(user.phonenumber,'') end delivery_target," +
                "outbox.process_instance_id,outbox.task_id,outbox.title,outbox.content," +
                "outbox.sms_template_id,outbox.route_path,outbox.status,outbox.delivery_cycle," +
                "outbox.attempt_count,outbox.total_attempt_count,outbox.max_attempts,outbox.revision " +
                "from wf_notification_outbox outbox left join sys_user user " +
                "on user.user_id=outbox.recipient_user_id left join wf_notification_preference preference " +
                "on preference.user_id=outbox.recipient_user_id where " +
                "((outbox.status in ('PENDING','RETRYING') and outbox.next_attempt_at<=current_timestamp(3)) " +
                "or (outbox.status='DELIVERING' and outbox.lease_expires_at<current_timestamp(3))) " +
                "and outbox.attempt_count<outbox.max_attempts " +
                "order by outbox.outbox_id limit 1 for update skip locked",
                (result, rowNum) -> new WorkflowNotificationOutboxRecord(result.getLong("outbox_id"),
                        result.getString("idempotency_key"), result.getString("event_type"),
                        result.getString("channel"), result.getLong("recipient_user_id"),
                        result.getString("delivery_target"),
                        result.getString("process_instance_id"), result.getString("task_id"),
                        result.getString("title"), result.getString("content"),
                        result.getString("sms_template_id"), result.getString("route_path"),
                        result.getString("status"), result.getInt("delivery_cycle"),
                        result.getInt("attempt_count") + 1,
                        result.getInt("total_attempt_count") + 1,
                        result.getInt("max_attempts"), result.getInt("revision") + 1));
        if (rows.isEmpty()) return null;
        WorkflowNotificationOutboxRecord row = rows.get(0);
        int updated = jdbcTemplate.update("update wf_notification_outbox set status='DELIVERING'," +
                "attempt_count=attempt_count+1,total_attempt_count=total_attempt_count+1," +
                "lease_owner=?,lease_expires_at=date_add(current_timestamp(3),interval ? second)," +
                "revision=revision+1 where outbox_id=? and revision=? and status=?",
                normalizedWorkerId, properties.getLeaseDuration().toSeconds(), row.outboxId(),
                row.revision() - 1, row.previousStatus());
        if (updated != 1) return null;
        recordTransition(row.outboxId(), "CLAIM", row.attemptCount(), row.previousStatus(),
                "DELIVERING", "SYSTEM", normalizedWorkerId, null, "worker 已领取投递租约");
        return row;
    }

    /**
     * 提交一次已执行通道副作用的失败结果，供 worker 未知异常和测试入口复用。
     * @param row WorkflowNotificationOutboxRecord，领取快照
     * @param workerId String，租约持有者
     * @param outcome WorkflowNotificationDeliveryResult，稳定且脱敏的投递结果
     * @return void，成功结果提交为 PROCESSED，失败结果进入 RETRYING 或 DEAD_LETTER
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void completeDelivery(WorkflowNotificationOutboxRecord row, String workerId,
            WorkflowNotificationDeliveryResult outcome)
    {
        if (row == null || outcome == null)
        {
            throw new ServiceException("通知投递结果不能为空", HttpStatus.CONFLICT);
        }
        if (outcome.success())
        {
            complete(row, workerId, "通知通道已接受请求");
            return;
        }
        boolean exhausted = outcome.permanent() || row.attemptCount() >= row.maxAttempts();
        String target = exhausted ? "DEAD_LETTER" : "RETRYING";
        long delay = exhausted ? 0L : Math.min(properties.getMaxRetryDelay().toSeconds(),
                1L << Math.min(20, row.attemptCount()));
        int updated = jdbcTemplate.update("update wf_notification_outbox set status=?," +
                "next_attempt_at=date_add(current_timestamp(3),interval ? second)," +
                "lease_owner=null,lease_expires_at=null," +
                "processed_time=case when ?='DEAD_LETTER' then current_timestamp(3) else null end," +
                "last_error_code=?,last_error_summary=?,revision=revision+1 " +
                "where outbox_id=? and status='DELIVERING' and lease_owner=? and revision=?",
                target, delay, target, outcome.errorCode(), outcome.summary(), row.outboxId(),
                normalizedWorkerId(workerId), row.revision());
        if (updated != 1)
        {
            throw new ServiceException("通知投递租约已变化", HttpStatus.CONFLICT);
        }
        recordTransition(row.outboxId(), exhausted ? "DEAD_LETTER" : "RETRY",
                row.attemptCount(), "DELIVERING", target, "SYSTEM", workerId,
                outcome.errorCode(), exhausted ? "有界投递已进入死信" : "等待指数退避重试");
    }

    /**
     * 管理员重新开启单条死信的有界投递周期。
     * @param outboxId long，死信 outbox 主键
     * @return void，非死信状态返回 409
     */
    @Transactional(rollbackFor = Exception.class)
    public void compensate(long outboxId)
    {
        int updated = jdbcTemplate.update("update wf_notification_outbox set status='RETRYING'," +
                "delivery_cycle=delivery_cycle+1,attempt_count=0,next_attempt_at=current_timestamp(3)," +
                "lease_owner=null,lease_expires_at=null,processed_time=null," +
                "last_error_code=null,last_error_summary=null,revision=revision+1 " +
                "where outbox_id=? and status='DEAD_LETTER' and delivery_cycle<65535", outboxId);
        if (updated != 1)
        {
            throw new ServiceException("当前通知状态不允许补偿", HttpStatus.CONFLICT);
        }
        recordTransition(outboxId, "COMPENSATE", 0, "DEAD_LETTER", "RETRYING", "USER",
                currentUserId(), null, "管理员重新开启有界投递");
    }

    /**
     * 记录新 outbox 已在当前业务事务内登记的固定动作。
     * @param outboxId long，新建 outbox 主键
     * @param actorId String，Flowable 或业务通知来源
     * @param detail String，不含正文和地址的登记说明
     * @return void，记录结构化日志和低基数指标
     */
    public void recordEnqueued(long outboxId, String actorId, String detail)
    {
        recordTransition(outboxId, "ENQUEUE", 0, null, "PENDING", "SYSTEM",
                actorId, null, detail);
    }

    /**
     * 在当前业务事务提交前取消已失效催办，保持 Flowable execution/task 到 outbox 的固定锁序。
     * @param processInstanceId String，催办所属流程实例主键
     * @param taskId String，可空；非空时仅取消指定任务催办
     * @param detail String，不含业务正文的取消原因
     * @return void，缺少可写事务同步上下文时拒绝继续
     */
    public void schedulePendingUrgeCancellation(String processInstanceId, String taskId,
            String detail)
    {
        requireWriteTransaction();
        if (!TransactionSynchronizationManager.isSynchronizationActive())
        {
            throw new ServiceException("通知取消缺少事务同步上下文", HttpStatus.ERROR);
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization()
        {
            /**
             * 在 Flowable flush 后、数据库提交前锁定并取消催办。
             * @param readOnly boolean，当前事务是否声明为只读
             * @return void，只读事务拒绝取消
             */
            @Override
            public void beforeCommit(boolean readOnly)
            {
                if (readOnly)
                {
                    throw new ServiceException("只读事务不能取消审批催办", HttpStatus.ERROR);
                }
                cancelPendingUrges(processInstanceId, taskId, detail);
            }
        });
    }

    /**
     * 取消失去活动业务对象且尚未完成投递的人工催办 outbox。
     * @param processInstanceId String，催办所属流程实例主键
     * @param taskId String，可空；非空时仅取消指定任务催办
     * @param detail String，不含正文的取消原因
     * @return int，实际取消数量
     */
    public int cancelPendingUrges(String processInstanceId, String taskId, String detail)
    {
        List<String> processInstanceIds = taskId == null
                ? historicProcessTreeInstanceIds(processInstanceId)
                : List.of(processInstanceId);
        String placeholders = String.join(",",
                processInstanceIds.stream().map(ignored -> "?").toList());
        String taskFilter = taskId == null ? "" : " and task_id=?";
        List<Object> parameters = new ArrayList<>(processInstanceIds);
        if (taskId != null) parameters.add(taskId);
        List<CancelableOutbox> rows = jdbcTemplate.query(
                "select outbox_id,status,attempt_count,revision from wf_notification_outbox " +
                "where channel in ('EMAIL','SMS') and event_type='MANUAL_URGE' " +
                "and process_instance_id in (" + placeholders + ")" +
                taskFilter + " and status in ('PENDING','RETRYING','DELIVERING') " +
                "order by outbox_id for update",
                (result, rowNum) -> new CancelableOutbox(result.getLong("outbox_id"),
                        result.getString("status"), result.getInt("attempt_count"),
                        result.getInt("revision")), parameters.toArray());
        for (CancelableOutbox row : rows)
        {
            int updated = jdbcTemplate.update("update wf_notification_outbox set status='CANCELLED'," +
                    "lease_owner=null,lease_expires_at=null,processed_time=current_timestamp(3)," +
                    "last_error_code='BUSINESS_OBJECT_COMPLETED',last_error_summary=?,revision=revision+1 " +
                    "where outbox_id=? and status=? and revision=?", detail,
                    row.outboxId(), row.status(), row.revision());
            if (updated != 1)
            {
                throw new ServiceException("催办取消状态已变化", HttpStatus.CONFLICT);
            }
            recordTransition(row.outboxId(), "CANCEL", row.attemptCount(), row.status(),
                    "CANCELLED", "SYSTEM", "flowable", "BUSINESS_OBJECT_COMPLETED", detail);
        }
        return rows.size();
    }

    /**
     * 以租约持有者和版本条件将投递中的 outbox 原子提交为成功。
     * @param row WorkflowNotificationOutboxRecord，当前 worker 领取快照
     * @param workerId String，正式租约持有者
     * @param detail String，不包含敏感地址或正文的成功说明
     * @return void，租约漂移时返回 409
     */
    private void complete(WorkflowNotificationOutboxRecord row, String workerId, String detail)
    {
        int updated = jdbcTemplate.update("update wf_notification_outbox set status='PROCESSED'," +
                "processed_time=current_timestamp(3),lease_owner=null,lease_expires_at=null," +
                "last_error_code=null,last_error_summary=null,revision=revision+1 where outbox_id=? " +
                "and status='DELIVERING' and lease_owner=? and revision=?",
                row.outboxId(), normalizedWorkerId(workerId), row.revision());
        if (updated != 1)
        {
            throw new ServiceException("通知投递租约已变化", HttpStatus.CONFLICT);
        }
        recordTransition(row.outboxId(), "DELIVER", row.attemptCount(), "DELIVERING",
                "PROCESSED", "SYSTEM", workerId, null, detail);
    }

    /**
     * 收敛一条已耗尽次数的到期或过期租约为死信。
     * @param workerId String，执行收敛的当前 worker 标识
     * @return boolean，存在并成功收敛一条记录时为 true
     */
    private boolean deadLetterOneExhausted(String workerId)
    {
        List<ExhaustedOutbox> rows = jdbcTemplate.query(
                "select outbox_id,status,attempt_count,revision from wf_notification_outbox where " +
                "((status in ('PENDING','RETRYING') and next_attempt_at<=current_timestamp(3)) or " +
                "(status='DELIVERING' and lease_expires_at<current_timestamp(3))) " +
                "and attempt_count>=max_attempts order by outbox_id limit 1 for update skip locked",
                (result, rowNum) -> new ExhaustedOutbox(result.getLong("outbox_id"),
                        result.getString("status"), result.getInt("attempt_count"),
                        result.getInt("revision")));
        if (rows.isEmpty()) return false;
        ExhaustedOutbox row = rows.get(0);
        int updated = jdbcTemplate.update("update wf_notification_outbox set status='DEAD_LETTER'," +
                "lease_owner=null,lease_expires_at=null,processed_time=current_timestamp(3)," +
                "last_error_code='LEASE_EXPIRED_AFTER_FINAL_ATTEMPT'," +
                "last_error_summary='最终投递租约过期，已停止再次领取',revision=revision+1 " +
                "where outbox_id=? and status=? and revision=? and attempt_count>=max_attempts",
                row.outboxId(), row.status(), row.revision());
        if (updated != 1)
        {
            throw new ServiceException("耗尽通知状态已变化", HttpStatus.CONFLICT);
        }
        recordTransition(row.outboxId(), "DEAD_LETTER", row.attemptCount(), row.status(),
                "DEAD_LETTER", "SYSTEM", workerId, "LEASE_EXPIRED_AFTER_FINAL_ATTEMPT",
                "最终投递租约过期，原子转入死信");
        return true;
    }

    /**
     * 从历史父子关系解析根实例和全部 CallActivity 后代实例。
     * @param rootProcessInstanceId String，根流程实例主键
     * @return List&lt;String&gt;，按主键排序且至少包含根实例的历史流程树
     */
    private List<String> historicProcessTreeInstanceIds(String rootProcessInstanceId)
    {
        LinkedHashSet<String> processInstanceIds = new LinkedHashSet<>();
        processInstanceIds.add(rootProcessInstanceId);
        List<String> frontier = List.of(rootProcessInstanceId);
        while (!frontier.isEmpty())
        {
            String placeholders = String.join(",",
                    frontier.stream().map(ignored -> "?").toList());
            List<String> children = jdbcTemplate.queryForList(
                    "select PROC_INST_ID_ from ACT_HI_PROCINST where SUPER_PROCESS_INSTANCE_ID_ in (" +
                    placeholders + ") order by PROC_INST_ID_", String.class, frontier.toArray());
            List<String> next = new ArrayList<>();
            for (String child : children)
            {
                if (!StringUtils.hasText(child))
                {
                    throw new ServiceException("历史流程树实例数据不完整", HttpStatus.ERROR);
                }
                if (processInstanceIds.add(child)) next.add(child);
            }
            if (processInstanceIds.size() > MAX_PROCESS_TREE_SIZE)
            {
                throw new ServiceException("历史流程树实例数量超过催办取消上限", HttpStatus.CONFLICT);
            }
            frontier = List.copyOf(next);
        }
        return processInstanceIds.stream().sorted().toList();
    }

    /**
     * 记录不含通知正文、地址或凭据的 outbox 状态动作日志与指标。
     * @param outboxId Long，正式 notification outbox 主键
     * @param action String，固定状态动作
     * @param attempt int，当前投递周期尝试序号
     * @param from String，可空原状态
     * @param to String，目标状态
     * @param actorType String，SYSTEM 或 USER
     * @param actorId String，worker、Flowable、来源或用户主键
     * @param errorCode String，可空稳定错误码
     * @param detail String，可空脱敏说明
     * @return void，未知动作视为编程错误
     */
    public void recordTransition(Long outboxId, String action, int attempt, String from,
            String to, String actorType, String actorId, String errorCode, String detail)
    {
        if (outboxId == null)
        {
            throw new ServiceException("通知 outbox 主键缺失", HttpStatus.ERROR);
        }
        metrics.recordDeliveryTransition(action);
        log.info("operation=workflowNotificationDelivery traceId={} source={} actorId={} " +
                        "outboxId={} action={} attemptNo={} fromStatus={} toStatus={} " +
                        "resultCode={} detail={}",
                safeTraceId(), safe(actorType), safe(actorId), outboxId, action, attempt,
                safe(from), safe(to), safe(errorCode), safe(detail));
    }

    /**
     * 规范化 worker 标识并拒绝控制字符和超长值。
     * @param workerId String，worker 原始标识
     * @return String，可用于租约比较的正式标识
     */
    private String normalizedWorkerId(String workerId)
    {
        if (!StringUtils.hasText(workerId))
        {
            throw new ServiceException("通知 worker 标识不合法", HttpStatus.BAD_REQUEST);
        }
        String normalized = workerId.trim();
        if (normalized.length() > 128 || normalized.chars().anyMatch(Character::isISOControl))
        {
            throw new ServiceException("通知 worker 标识不合法", HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    /**
     * 读取当前用户主键供人工补偿审计使用。
     * @return String，当前正式用户主键
     */
    private String currentUserId()
    {
        try
        {
            return identityResolver.resolveCurrentIdentity().userId();
        }
        catch (RuntimeException exception)
        {
            throw new ServiceException("当前用户身份无效", HttpStatus.UNAUTHORIZED);
        }
    }

    /**
     * 要求催办取消登记运行在当前可写事务中。
     * @return void，缺少事务或只读事务时拒绝继续
     */
    private void requireWriteTransaction()
    {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
        {
            throw new ServiceException("通知 outbox 必须在 Flowable 写事务中登记", HttpStatus.ERROR);
        }
    }

    /** @return String，当前 traceId；未配置时为空字符串。 */
    private String safeTraceId()
    {
        String traceId = MDC.get("traceId");
        return traceId == null ? "" : traceId;
    }

    /** @param value String，可空日志字段；@return String，非空安全值。 */
    private String safe(String value)
    {
        return value == null ? "" : value;
    }

    private record CancelableOutbox(long outboxId, String status, int attemptCount, int revision) { }
    private record ExhaustedOutbox(long outboxId, String status, int attemptCount, int revision) { }
}
