package com.ruoyi.flowable.service.notification;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jakarta.mail.internet.MimeMessage;
import org.flowable.common.engine.impl.identity.Authentication;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.identitylink.api.IdentityLinkType;
import org.flowable.task.api.DelegationState;
import org.flowable.task.api.Task;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.config.WorkflowNotificationProperties;
import com.ruoyi.flowable.domain.WfCopy;
import com.ruoyi.flowable.domain.dto.WorkflowManualUrgeRequest;
import com.ruoyi.flowable.domain.dto.WorkflowNotificationPolicyRequest;
import com.ruoyi.flowable.domain.dto.WorkflowNotificationPreferenceRequest;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.framework.web.service.PermissionService;

/**
 * 普通审批生命周期通知领域服务，负责策略解析、事务 outbox、站内信、SMTP、偏好和人工催办。
 */
@Service
public class WorkflowNotificationService
{
    /** 服务端允许进入模板的固定变量，流程表单变量和意见不会进入通知。 */
    public static final Set<String> TEMPLATE_VARIABLES = Set.of(
            "processName", "processDefinitionKey", "processInstanceId",
            "taskName", "taskDefinitionKey", "eventType");

    /** 可以由策略管理接口配置的普通生命周期事件。 */
    public static final Set<String> EVENT_TYPES = Set.of(
            "TASK_ARRIVED", "TASK_CLAIMED", "TASK_UNCLAIMED", "TASK_DELEGATED",
            "TASK_DELEGATION_RESOLVED", "TASK_TRANSFERRED", "TASK_RETURNED",
            "TASK_RESUBMITTED", "TASK_COMPLETED", "PROCESS_COMPLETED",
            "PROCESS_CANCELED", "PROCESS_REJECTED", "PROCESS_TERMINATED", "MANUAL_URGE",
            "COPY_CREATED");

    private static final List<String> RECIPIENT_RULE_ORDER = List.of(
            "TASK_RECIPIENT", "INITIATOR", "ACTOR");
    private static final List<String> CHANNEL_ORDER = List.of("INBOX", "EMAIL");
    private static final Set<String> SCOPES = Set.of("DEFAULT", "PROCESS", "NODE");
    private static final Set<String> STATUSES = Set.of("ENABLED", "DISABLED");
    private static final Pattern TEMPLATE_VARIABLE = Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9]*)}}" );
    private static final int MAX_EXECUTIONS_PER_URGE = 10_000;
    private static final int MAX_TASKS_PER_URGE = 2_000;
    private static final int MAX_RECIPIENTS_PER_EVENT = 2_000;
    private static final int MAX_TITLE_LENGTH = 160;
    private static final int MAX_CONTENT_LENGTH = 700;
    private static final String URGE_ANY_PERMISSION = "workflow:notification:urge:any";

    /** 退回和重提在任务归属稳定前使用的流程变量，避免中间 assignment 产生错误通知。 */
    public static final String CONTROLLED_TRANSITION_VARIABLE =
            "__ruoyi_workflow_notification_transition";

    private final JdbcTemplate jdbcTemplate;
    private final RepositoryService repositoryService;
    private final RuntimeService runtimeService;
    private final HistoryService historyService;
    private final TaskService taskService;
    private final WorkflowIdentityResolver identityResolver;
    private final WorkflowEngineOperations engineOperations;
    private final PermissionService permissionService;
    private final JavaMailSender mailSender;
    private final WorkflowNotificationProperties properties;

    /**
     * 创建通知领域服务。
     * @param jdbcTemplate JdbcTemplate，正式通知表和用户目录访问
     * @param repositoryService RepositoryService，流程定义名称查询
     * @param runtimeService RuntimeService，运行实例状态查询
     * @param historyService HistoryService，流程发起人与历史状态查询
     * @param taskService TaskService，活动待办和候选身份查询
     * @param identityResolver WorkflowIdentityResolver，正式用户及候选组展开
     * @param engineOperations WorkflowEngineOperations，统一 Flowable 事务边界
     * @param permissionService PermissionService，管理员实时权限复核
     * @param mailSender JavaMailSender，正式 SMTP 出口
     * @param properties WorkflowNotificationProperties，租约、退避和发件配置
     * @return void，构造后由 Spring 管理
     */
    public WorkflowNotificationService(JdbcTemplate jdbcTemplate,
            RepositoryService repositoryService, RuntimeService runtimeService,
            HistoryService historyService, TaskService taskService,
            WorkflowIdentityResolver identityResolver, WorkflowEngineOperations engineOperations,
            PermissionService permissionService, JavaMailSender mailSender,
            WorkflowNotificationProperties properties)
    {
        this.jdbcTemplate = jdbcTemplate;
        this.repositoryService = repositoryService;
        this.runtimeService = runtimeService;
        this.historyService = historyService;
        this.taskService = taskService;
        this.identityResolver = identityResolver;
        this.engineOperations = engineOperations;
        this.permissionService = permissionService;
        this.mailSender = mailSender;
        this.properties = properties;
    }

    /**
     * 在 userTaskListener 当前 Flowable 命令事务中登记任务事件 outbox。
     * @param flowableEvent String，create、assignment 或 complete
     * @param taskId String，任务主键
     * @param processInstanceId String，流程实例主键
     * @param processDefinitionId String，流程定义主键
     * @param taskDefinitionKey String，BPMN 节点 key
     * @param taskName String，任务显示名称
     * @param assignee String，当前办理人
     * @param owner String，当前所有人
     * @return int，实际登记的通道 outbox 数量
     */
    public int onTaskEvent(String flowableEvent, String taskId, String processInstanceId,
            String processDefinitionId, String taskDefinitionKey, String taskName,
            String assignee, String owner)
    {
        requireWriteTransaction();
        if ("assignment".equals(flowableEvent))
        {
            // Flowable 在任务初始化、参与者规则解析和真实任务动作中都会发出 assignment。
            // 该底层事件无法可靠区分业务动作，认领等通知统一由动作服务在最终归属稳定后显式登记。
            return 0;
        }
        Object controlledTransition = runtimeService.getVariable(
                processInstanceId, CONTROLLED_TRANSITION_VARIABLE);
        if (controlledTransition != null)
        {
            if (!(controlledTransition instanceof String transition)
                    || !Set.of("RETURN", "RESUBMIT").contains(transition))
            {
                throw new ServiceException("通知受控迁移标记异常", HttpStatus.ERROR);
            }
            // 退回/重提会先改变执行树再恢复办理关系，中间态通知由生命周期服务显式抑制。
            return 0;
        }
        String eventType = classifyTaskEvent(flowableEvent);
        if ("TASK_COMPLETED".equals(eventType))
        {
            // Flowable 尚未 flush 任务状态；提交前再锁 outbox，保持 execution/task -> outbox 固定锁序。
            schedulePendingUrgeCancellation(processInstanceId, taskId,
                    "任务已完成，取消未投递催办");
        }
        Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
        Set<String> taskRecipients = task == null
                ? activeUsers(assignee == null ? List.of() : List.of(assignee), List.of(), false)
                : resolveTaskRecipients(task);
        EventContext context = context(eventType, processDefinitionId, processInstanceId,
                taskId, taskDefinitionKey, taskName, taskRecipients);

        // listener 审计已先写入当前事务，用同任务同事件的审计序号稳定区分再次转办回同一用户等合法重复动作。
        Integer ordinal = jdbcTemplate.queryForObject(
                "select count(*) from ACT_HI_COMMENT where TASK_ID_=? and TYPE_='USER_TASK_LISTENER'",
                Integer.class, taskId);
        String sourceEventKey = "TASK_ARRIVED".equals(eventType)
                ? "TASK:" + taskId + ":ARRIVED"
                : "TASK:" + taskId + ":" + flowableEvent + ":" + ordinal;
        return enqueue(context, sourceEventKey);
    }

    /**
     * 在受控退回或重提完成归属恢复后登记一次稳定任务通知。
     * @param eventType String，仅允许 TASK_RETURNED 或 TASK_RESUBMITTED
     * @param task Task，已经完成 assignee、owner 和候选关系恢复的真实活动任务
     * @return int，实际登记的通道 outbox 数量
     */
    public int onStableTaskEvent(String eventType, Task task)
    {
        requireWriteTransaction();
        if (task == null || !Set.of("TASK_RETURNED", "TASK_RESUBMITTED").contains(eventType))
        {
            throw invalid("稳定任务通知事件不合法");
        }
        Task stableTask = taskService.createTaskQuery().taskId(task.getId()).active().singleResult();
        if (stableTask == null) throw new ServiceException("稳定任务不存在", HttpStatus.CONFLICT);
        EventContext context = context(eventType, stableTask.getProcessDefinitionId(),
                stableTask.getProcessInstanceId(), stableTask.getId(),
                stableTask.getTaskDefinitionKey(), stableTask.getName(),
                resolveTaskRecipients(stableTask));
        return enqueue(context, "TASK:" + stableTask.getId() + ":" + eventType);
    }

    /**
     * 在认领、释放、委派、归还或转办完成并刷新最终归属后登记一次任务通知。
     * @param eventType String，TASK_CLAIMED、TASK_UNCLAIMED、TASK_DELEGATED、TASK_DELEGATION_RESOLVED 或 TASK_TRANSFERRED
     * @param taskId String，已经完成真实任务动作的活动任务主键
     * @return int，本次实际登记的通知 outbox 数量
     */
    public int onStableTaskAction(String eventType, String taskId)
    {
        requireWriteTransaction();
        if (!Set.of("TASK_CLAIMED", "TASK_UNCLAIMED", "TASK_DELEGATED",
                "TASK_DELEGATION_RESOLVED", "TASK_TRANSFERRED").contains(eventType))
        {
            throw invalid("稳定任务动作通知事件不合法");
        }
        String normalizedTaskId = normalized(taskId, 64, "任务主键不合法");
        Task stableTask = taskService.createTaskQuery().taskId(normalizedTaskId).active().singleResult();
        if (stableTask == null)
        {
            throw new ServiceException("任务归属状态已变化", HttpStatus.CONFLICT);
        }
        Integer revision = jdbcTemplate.queryForObject(
                "select REV_ from ACT_RU_TASK where ID_=?", Integer.class, normalizedTaskId);
        if (revision == null || revision < 1)
        {
            throw new ServiceException("任务归属版本不存在", HttpStatus.ERROR);
        }
        // 任何归属变化都使尚未投递的旧催办接收人失效；与任务动作同事务取消后再登记新归属通知。
        cancelPendingUrges(stableTask.getProcessInstanceId(), stableTask.getId(),
                "任务办理关系已变化，取消旧接收人催办");
        EventContext context = context(eventType, stableTask.getProcessDefinitionId(),
                stableTask.getProcessInstanceId(), stableTask.getId(),
                stableTask.getTaskDefinitionKey(), stableTask.getName(),
                resolveTaskRecipients(stableTask));
        // 同一业务动作的重复调用命中同一 revision；后续合法动作会推进 revision 并获得新的幂等键。
        return enqueue(context, "TASK:" + stableTask.getId() + ":" + eventType + ":r" + revision);
    }

    /**
     * 消费本次事务真实写入或幂等命中的抄送事实，并为每个真实 copy 身份登记通知。
     * @param copies Collection&lt;WfCopy&gt;，已经由抄送领域服务校验并写入 wf_copy 的事实身份
     * @return int，实际新增的通知 outbox 数量；幂等重放时可为零
     */
    public int onCopiesCreated(Collection<WfCopy> copies)
    {
        requireWriteTransaction();
        if (copies == null)
        {
            throw new ServiceException("抄送通知事实不能为空", HttpStatus.ERROR);
        }
        LinkedHashMap<String, LinkedHashSet<Long>> identities = new LinkedHashMap<>();
        for (WfCopy copy : copies)
        {
            if (copy == null || copy.getUserId() == null || copy.getUserId() <= 0
                    || !StringUtils.hasText(copy.getCopyEventId()))
            {
                throw new ServiceException("抄送通知事实身份不完整", HttpStatus.ERROR);
            }
            String eventId = normalized(copy.getCopyEventId(), 128, "抄送事件幂等键不合法");
            identities.computeIfAbsent(eventId, ignored -> new LinkedHashSet<>())
                    .add(copy.getUserId());
        }

        int outboxCount = 0;
        for (Map.Entry<String, LinkedHashSet<Long>> event : identities.entrySet())
        {
            List<Map<String, Object>> rows = selectPersistedCopies(event.getKey(), event.getValue());
            if (rows.size() != event.getValue().size())
            {
                throw new ServiceException("抄送事实写入数量与通知身份不一致", HttpStatus.ERROR);
            }
            LinkedHashSet<Long> persistedUsers = new LinkedHashSet<>();
            for (Map<String, Object> row : rows)
            {
                Number copyId = (Number) row.get("copy_id");
                Number userId = (Number) row.get("user_id");
                if (copyId == null || userId == null || !persistedUsers.add(userId.longValue()))
                {
                    throw new ServiceException("抄送事实身份存在重复或缺失", HttpStatus.ERROR);
                }
                outboxCount += onCopyCreated(copyId.longValue());
            }
            if (!persistedUsers.equals(event.getValue()))
            {
                throw new ServiceException("抄送事实接收人与通知身份不一致", HttpStatus.ERROR);
            }
        }
        return outboxCount;
    }

    /**
     * 消费已经正式落库的 wf_copy 事实并登记幂等通知，不创建或修改抄送业务记录。
     * @param copyId long，wf_copy 正式记录主键
     * @return int，重复调用返回 0，首次调用返回实际 outbox 数量
     */
    public int onCopyCreated(long copyId)
    {
        requireWriteTransaction();
        if (copyId <= 0) throw invalid("抄送记录主键不合法");
        List<Map<String, Object>> copies = jdbcTemplate.queryForList(
                "select copy_id,copy_event_id,process_id,process_name,instance_id,task_id,user_id,title,create_by " +
                "from wf_copy where copy_id=? and del_flag='0' for share", copyId);
        if (copies.size() != 1) throw new ServiceException("抄送记录不存在", HttpStatus.NOT_FOUND);
        Map<String, Object> copy = copies.get(0);
        // 只消费正式抄送事实；关键字段异常时拒绝产生无法追踪或发错人的通知。
        Object copyEventValue = copy.get("copy_event_id");
        Object processDefinitionValue = copy.get("process_id");
        Object processInstanceValue = copy.get("instance_id");
        Object recipientValue = copy.get("user_id");
        if (copyEventValue == null || processDefinitionValue == null
                || processInstanceValue == null || recipientValue == null)
        {
            throw new ServiceException("抄送记录关键字段不完整", HttpStatus.ERROR);
        }
        String copyEventId = normalized(String.valueOf(copyEventValue), 128, "抄送事件幂等键不合法");
        String processDefinitionId = normalized(String.valueOf(processDefinitionValue), 64,
                "抄送流程定义主键不合法");
        String processInstanceId = normalized(String.valueOf(processInstanceValue), 64,
                "抄送流程实例主键不合法");
        String taskId = copy.get("task_id") == null ? null
                : optional(String.valueOf(copy.get("task_id")), 64);
        String recipient = normalized(String.valueOf(recipientValue), 19, "抄送接收用户主键不合法");
        if (!recipient.matches("[1-9][0-9]{0,18}"))
        {
            throw new ServiceException("抄送接收用户主键不合法", HttpStatus.ERROR);
        }
        ProcessDefinition definition = repositoryService.getProcessDefinition(processDefinitionId);
        if (definition == null) throw new ServiceException("抄送流程定义不存在", HttpStatus.ERROR);
        String route = "/workflow/process-detail/" + processInstanceId + "?source=copy"
                + (taskId == null ? "" : "&taskId=" + taskId);
        EventContext context = new EventContext("COPY_CREATED", definition.getKey(),
                String.valueOf(copy.get("process_name")), processInstanceId, taskId, null,
                String.valueOf(copy.get("title")), String.valueOf(copy.get("create_by")),
                null, activeUsers(List.of(recipient), List.of(), false), route);
        return enqueue(context, "COPY:" + copyId + ":" + copyEventId);
    }

    /**
     * 在流程自然完成或显式终止的当前 Flowable 事务内登记流程结果通知。
     * @param eventType String，PROCESS_COMPLETED、PROCESS_CANCELED、PROCESS_REJECTED 或 PROCESS_TERMINATED
     * @param processDefinitionId String，流程定义主键
     * @param processInstanceId String，根流程实例主键
     * @return int，实际登记的 outbox 数量
     */
    public int onProcessResult(String eventType, String processDefinitionId,
            String processInstanceId)
    {
        requireWriteTransaction();
        if (!Set.of("PROCESS_COMPLETED", "PROCESS_CANCELED", "PROCESS_REJECTED",
                "PROCESS_TERMINATED").contains(eventType))
        {
            throw invalid("流程通知事件不受支持");
        }
        // Flowable 尚未 flush 执行树；提交前再锁 outbox，避免与并发催办形成反向锁序。
        schedulePendingUrgeCancellation(processInstanceId, null,
                "流程已结束，取消未投递催办");
        EventContext context = context(eventType, processDefinitionId, processInstanceId,
                null, null, null, Set.of());
        return enqueue(context, "PROCESS:" + processInstanceId + ":" + eventType);
    }

    /**
     * 在 CallActivity 子流程实例完成的当前 Flowable 写事务内，登记仅取消该实例及其后代催办的动作。
     * 子流程完成不代表根业务流程完成，因此本入口不得登记流程结果通知或触发其他根流程副作用。
     *
     * @param processInstanceId String，已经完成的 CallActivity 子流程实例主键
     * @return void，取消动作在同一事务的提交前阶段执行，失败会回滚 Flowable 完成事务
     */
    public void scheduleUrgeCancellationForCompletedProcessInstance(String processInstanceId)
    {
        requireWriteTransaction();
        String normalizedProcessInstanceId = normalized(processInstanceId, 64,
                "流程实例主键不合法");
        schedulePendingUrgeCancellation(normalizedProcessInstanceId, null,
                "子流程已结束，取消未投递催办");
    }

    /**
     * 由有对象权限的发起人或管理员催办运行实例的全部真实活动待办。
     * @param request WorkflowManualUrgeRequest，流程实例和催办原因
     * @return Map&lt;String,Object&gt;，催办审计主键、真实接收人数和 outbox 数量
     */
    public Map<String, Object> urge(WorkflowManualUrgeRequest request)
    {
        if (request == null) throw invalid("催办请求不能为空");
        String processInstanceId = normalized(request.processInstanceId(), 64, "流程实例主键不合法");
        String reason = normalized(request.reason(), 500, "催办原因不合法");
        return engineOperations.writeAsCurrentUser(actor ->
        {
            // 身份解析可能已经建立 RR 快照，因此流程、任务、候选关系和频率全部使用锁定 current-read 快照。
            RuntimeProcessSnapshot process = lockRuntimeProcessTree(processInstanceId);
            List<LockedTask> tasks = lockRuntimeTasks(process.processInstanceIds());
            if (process.suspensionState() != 1)
            {
                throw new ServiceException("挂起流程不能催办", HttpStatus.CONFLICT);
            }
            if (!actor.userId().equals(process.startUserId())
                    && !permissionService.hasPermi(URGE_ANY_PERMISSION))
            {
                throw new ServiceException("无权催办当前流程", HttpStatus.FORBIDDEN);
            }
            if (tasks.isEmpty())
            {
                throw new ServiceException("流程没有可催办的活动待办", HttpStatus.CONFLICT);
            }
            Timestamp frequencyBoundary = Timestamp.from(Instant.now().minus(properties.getUrgeInterval()));
            List<Long> recent = jdbcTemplate.queryForList(
                    "select urge_id from wf_notification_urge_audit where process_instance_id=? " +
                    "and actor_user_id=? and create_time>? order by urge_id desc limit 1 for update",
                    Long.class, processInstanceId, Long.valueOf(actor.userId()), frequencyBoundary);
            if (!recent.isEmpty())
            {
                throw new ServiceException("催办过于频繁，请稍后再试", HttpStatus.TOO_MANY_REQUESTS);
            }

            LinkedHashMap<LockedTask, Set<String>> recipientsByTask = new LinkedHashMap<>();
            LinkedHashSet<String> allRecipients = new LinkedHashSet<>();
            for (LockedTask task : tasks)
            {
                Set<String> recipients = resolveLockedTaskRecipients(task);
                if (!recipients.isEmpty())
                {
                    recipientsByTask.put(task, recipients);
                    allRecipients.addAll(recipients);
                }
            }
            if (allRecipients.isEmpty())
            {
                throw new ServiceException("活动待办没有有效接收人", HttpStatus.CONFLICT);
            }

            String urgeEventKey = "URGE:" + UUID.randomUUID();
            int outboxCount = 0;
            LinkedHashSet<String> deliveredRecipients = new LinkedHashSet<>();
            for (Map.Entry<LockedTask, Set<String>> entry : recipientsByTask.entrySet())
            {
                LockedTask task = entry.getKey();
                EventContext context = contextForUrge(process, task, entry.getValue());
                EnqueueResult result = enqueueDetailed(context,
                        urgeEventKey + ":" + task.taskId());
                outboxCount += result.outboxCount();
                deliveredRecipients.addAll(result.recipientUserIds());
            }
            if (outboxCount == 0 || deliveredRecipients.isEmpty())
            {
                // 没有策略、全部通道被关闭或接收人失效都不是成功催办；事务回滚保证不留空审计。
                throw new ServiceException("当前催办没有可投递通知", HttpStatus.CONFLICT);
            }
            long urgeId = insertUrgeAudit(processInstanceId, actor,
                    deliveredRecipients.size(), reason);
            return Map.of("urgeId", urgeId, "recipientCount", deliveredRecipients.size(),
                    "outboxCount", outboxCount);
        });
    }

    /**
     * 查询当前用户审批通知，支持未读和已读过滤。
     * @param readStatus String，ALL、UNREAD 或 READ
     * @param limit int，1 至 100
     * @return Map&lt;String,Object&gt;，通知列表和未读总数
     */
    @Transactional(readOnly = true)
    public Map<String, Object> inbox(String readStatus, int limit)
    {
        long userId = currentUserId();
        String status = readStatus == null ? "ALL" : readStatus.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("ALL", "UNREAD", "READ").contains(status) || limit < 1 || limit > 100)
        {
            throw invalid("通知查询参数不合法");
        }
        String filter = "ALL".equals(status) ? "" : " and read_status='" + status + "'";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select notification_id as notificationId,event_type as eventType,title,content," +
                "process_instance_id as processInstanceId,task_id as taskId,route_path as routePath," +
                "read_status as readStatus,create_time as createTime,read_time as readTime " +
                "from wf_notification_inbox where recipient_user_id=?" + filter +
                " order by notification_id desc limit ?", userId, limit);
        Integer unread = jdbcTemplate.queryForObject(
                "select count(*) from wf_notification_inbox where recipient_user_id=? and read_status='UNREAD'",
                Integer.class, userId);
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
                "read_time=current_timestamp(3) where notification_id=? and recipient_user_id=? and read_status='UNREAD'",
                notificationId, userId);
        if (updated == 0)
        {
            Integer exists = jdbcTemplate.queryForObject(
                    "select count(*) from wf_notification_inbox where notification_id=? and recipient_user_id=?",
                    Integer.class, notificationId, userId);
            if (exists == null || exists == 0) throw new ServiceException("通知不存在", HttpStatus.NOT_FOUND);
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
                "read_time=current_timestamp(3) where recipient_user_id=? and read_status='UNREAD'",
                currentUserId());
    }

    /**
     * 查询当前用户通知偏好，未保存时返回正式默认值。
     * @return Map&lt;String,Object&gt;，站内、邮件开关和 revision
     */
    @Transactional(readOnly = true)
    public Map<String, Object> preference()
    {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select inbox_enabled as inboxEnabled,email_enabled as emailEnabled,revision " +
                "from wf_notification_preference where user_id=?", currentUserId());
        return rows.isEmpty() ? Map.of("inboxEnabled", true, "emailEnabled", true, "revision", 0)
                : rows.get(0);
    }

    /**
     * 以乐观锁保存当前用户通知偏好。
     * @param request WorkflowNotificationPreferenceRequest，两个通道开关和期望版本
     * @return Map&lt;String,Object&gt;，保存后的偏好
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> savePreference(WorkflowNotificationPreferenceRequest request)
    {
        if (request == null || request.expectedRevision() == null || request.expectedRevision() < 0)
        {
            throw invalid("通知偏好版本不合法");
        }
        long userId = currentUserId();
        int updated = jdbcTemplate.update("update wf_notification_preference set inbox_enabled=?," +
                "email_enabled=?,revision=revision+1,update_time=current_timestamp(3) " +
                "where user_id=? and revision=?", request.inboxEnabled(), request.emailEnabled(),
                userId, request.expectedRevision());
        if (updated == 0 && request.expectedRevision() == 0)
        {
            try
            {
                updated = jdbcTemplate.update("insert into wf_notification_preference " +
                        "(user_id,inbox_enabled,email_enabled,revision,update_time) values (?,?,?,1,current_timestamp(3))",
                        userId, request.inboxEnabled(), request.emailEnabled());
            }
            catch (DataAccessException exception)
            {
                throw new ServiceException("通知偏好已变化，请刷新后重试", HttpStatus.CONFLICT);
            }
        }
        if (updated != 1) throw new ServiceException("通知偏好已变化，请刷新后重试", HttpStatus.CONFLICT);
        return preference();
    }

    /**
     * 查询通知策略供管理员维护。
     * @return List&lt;Map&lt;String,Object&gt;&gt;，全部策略及版本
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> policies()
    {
        return jdbcTemplate.queryForList("select policy_id as policyId,scope_type as scopeType," +
                "process_definition_key as processDefinitionKey,task_definition_key as taskDefinitionKey," +
                "event_type as eventType,recipient_rules as recipientRules,channels,title_template as titleTemplate," +
                "content_template as contentTemplate,max_attempts as maxAttempts,status,revision,update_time as updateTime " +
                "from wf_notification_policy order by event_type,field(scope_type,'DEFAULT','PROCESS','NODE'),policy_id");
    }

    /**
     * 新增或乐观锁更新流程/节点通知策略。
     * @param request WorkflowNotificationPolicyRequest，完整策略请求
     * @return Map&lt;String,Object&gt;，保存后的策略
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> savePolicy(WorkflowNotificationPolicyRequest request)
    {
        ValidatedPolicy policy = validatePolicy(request);
        String actor = String.valueOf(currentUserId());
        long policyId;
        if (request.policyId() == null)
        {
            KeyHolder holder = new GeneratedKeyHolder();
            try
            {
                jdbcTemplate.update(connection ->
                {
                    PreparedStatement statement = connection.prepareStatement(
                            "insert into wf_notification_policy (scope_type,process_definition_key,task_definition_key," +
                            "event_type,recipient_rules,channels,title_template,content_template,max_attempts,status," +
                            "revision,create_by,create_time) values (?,?,?,?,?,?,?,?,?,?,0,?,current_timestamp(3))",
                            Statement.RETURN_GENERATED_KEYS);
                    bindPolicy(statement, policy, actor, false);
                    return statement;
                }, holder);
            }
            catch (DuplicateKeyException exception)
            {
                throw new ServiceException("相同作用域和事件的通知策略已存在", HttpStatus.CONFLICT);
            }
            catch (DataAccessException exception)
            {
                throw new ServiceException("通知策略保存失败", HttpStatus.ERROR);
            }
            if (holder.getKey() == null) throw new ServiceException("通知策略保存失败", HttpStatus.ERROR);
            policyId = holder.getKey().longValue();
        }
        else
        {
            if (request.policyId() <= 0 || request.expectedRevision() == null
                    || request.expectedRevision() < 0) throw invalid("通知策略版本不合法");
            int updated = jdbcTemplate.update("update wf_notification_policy set scope_type=?," +
                    "process_definition_key=?,task_definition_key=?,event_type=?,recipient_rules=?,channels=?," +
                    "title_template=?,content_template=?,max_attempts=?,status=?,revision=revision+1," +
                    "update_by=?,update_time=current_timestamp(3) where policy_id=? and revision=?",
                    policy.scopeType(), policy.processDefinitionKey(), policy.taskDefinitionKey(),
                    policy.eventType(), policy.recipientRules(), policy.channels(), policy.titleTemplate(),
                    policy.contentTemplate(), policy.maxAttempts(), policy.status(), actor,
                    request.policyId(), request.expectedRevision());
            if (updated != 1) throw new ServiceException("通知策略已变化，请刷新后重试", HttpStatus.CONFLICT);
            policyId = request.policyId();
        }
        return jdbcTemplate.queryForMap("select policy_id as policyId,scope_type as scopeType," +
                "process_definition_key as processDefinitionKey,task_definition_key as taskDefinitionKey," +
                "event_type as eventType,recipient_rules as recipientRules,channels,title_template as titleTemplate," +
                "content_template as contentTemplate,max_attempts as maxAttempts,status,revision " +
                "from wf_notification_policy where policy_id=?", policyId);
    }

    /**
     * 管理员查询最近 outbox 状态，正文和邮箱地址不进入管理投影。
     * @return List&lt;Map&lt;String,Object&gt;&gt;，最近 500 条脱敏记录
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> outbox()
    {
        return jdbcTemplate.queryForList("select outbox_id as outboxId,event_type as eventType,channel," +
                "recipient_user_id as recipientUserId,process_instance_id as processInstanceId,task_id as taskId," +
                "status,delivery_cycle as deliveryCycle,attempt_count as attemptCount," +
                "total_attempt_count as totalAttemptCount,max_attempts as maxAttempts,next_attempt_at as nextAttemptAt," +
                "last_error_code as lastErrorCode,last_error_summary as lastErrorSummary,create_time as createTime," +
                "processed_time as processedTime from wf_notification_outbox order by outbox_id desc limit 500");
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
                "last_error_code=null,last_error_summary=null," +
                "revision=revision+1 where outbox_id=? and status='DEAD_LETTER' and delivery_cycle<65535",
                outboxId);
        if (updated != 1) throw new ServiceException("当前通知状态不允许补偿", HttpStatus.CONFLICT);
        audit(outboxId, "COMPENSATE", 0, "DEAD_LETTER", "RETRYING", "USER",
                String.valueOf(currentUserId()), null, "管理员重新开启有界投递");
    }

    /**
     * 在独立短事务中领取一条到期或租约过期的 outbox。
     * @param workerId String，当前节点 worker 标识
     * @return OutboxRow，领取结果；没有到期记录时为 null
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public OutboxRow claimNext(String workerId)
    {
        String normalizedWorkerId = normalized(workerId, 128, "通知 worker 标识不合法");
        // 最终尝试期间进程退出会留下过期 DELIVERING；必须先原子收敛死信，不能再次加次数。
        deadLetterOneExhausted(normalizedWorkerId);
        List<OutboxRow> rows = jdbcTemplate.query(
                "select outbox_id,idempotency_key,event_type,channel,recipient_user_id," +
                "process_instance_id,task_id,title,content,route_path,status,delivery_cycle," +
                "attempt_count,total_attempt_count,max_attempts,revision " +
                "from wf_notification_outbox where ((status in ('PENDING','RETRYING') and next_attempt_at<=current_timestamp(3)) " +
                "or (status='DELIVERING' and lease_expires_at<current_timestamp(3))) and attempt_count<max_attempts " +
                "order by outbox_id limit 1 for update skip locked",
                (result, rowNum) -> new OutboxRow(result.getLong("outbox_id"),
                        result.getString("idempotency_key"), result.getString("event_type"),
                        result.getString("channel"), result.getLong("recipient_user_id"),
                        result.getString("process_instance_id"), result.getString("task_id"),
                        result.getString("title"), result.getString("content"),
                        result.getString("route_path"), result.getString("status"),
                        result.getInt("delivery_cycle"), result.getInt("attempt_count") + 1,
                        result.getInt("total_attempt_count") + 1, result.getInt("max_attempts"),
                        result.getInt("revision") + 1));
        if (rows.isEmpty()) return null;
        OutboxRow row = rows.get(0);
        int updated = jdbcTemplate.update("update wf_notification_outbox set status='DELIVERING'," +
                "attempt_count=attempt_count+1,total_attempt_count=total_attempt_count+1," +
                "lease_owner=?,lease_expires_at=date_add(current_timestamp(3),interval ? second)," +
                "revision=revision+1 where outbox_id=? and revision=? and status=?",
                normalizedWorkerId, properties.getLeaseDuration().toSeconds(), row.outboxId(),
                row.revision() - 1, row.previousStatus());
        if (updated != 1) return null;
        audit(row.outboxId(), "CLAIM", row.attemptCount(), row.previousStatus(), "DELIVERING",
                "SYSTEM", normalizedWorkerId, null, "worker 已领取投递租约");
        return row;
    }

    /**
     * 在独立事务内幂等创建站内信并提交 outbox 成功状态。
     * @param row OutboxRow，持有租约的站内通道记录
     * @param workerId String，租约持有者
     * @return void，插入与状态提交原子完成
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void deliverInbox(OutboxRow row, String workerId)
    {
        // 先锁定并复核正式租约；终态取消若先提交，本事务不得再创建站内信副作用。
        lockClaimedOutbox(row, workerId);
        Integer eligible = jdbcTemplate.queryForObject(
                "select count(*) from sys_user u left join wf_notification_preference p on p.user_id=u.user_id " +
                "where u.user_id=? and u.status='0' and u.del_flag='0' and coalesce(p.inbox_enabled,1)=1",
                Integer.class, row.recipientUserId());
        if (eligible == null || eligible != 1)
        {
            completeDelivery(row, workerId, DeliveryOutcome.failure(
                    "RECIPIENT_INVALID", "接收人已失效或停用站内通知", true));
            return;
        }
        jdbcTemplate.update("insert into wf_notification_inbox " +
                "(outbox_id,recipient_user_id,event_type,title,content,process_instance_id,task_id,route_path,read_status,create_time) " +
                "select outbox_id,recipient_user_id,event_type,title,content,process_instance_id,task_id,route_path,'UNREAD',current_timestamp(3) " +
                "from wf_notification_outbox where outbox_id=? " +
                "on duplicate key update outbox_id=wf_notification_inbox.outbox_id", row.outboxId());
        List<InboxFact> facts = jdbcTemplate.query(
                "select recipient_user_id,event_type,title,content,process_instance_id,task_id,route_path " +
                "from wf_notification_inbox where outbox_id=? for share",
                (result, rowNum) -> new InboxFact(result.getLong("recipient_user_id"),
                        result.getString("event_type"), result.getString("title"),
                        result.getString("content"), result.getString("process_instance_id"),
                        result.getString("task_id"), result.getString("route_path")),
                row.outboxId());
        if (facts.size() != 1 || !facts.get(0).matches(row))
        {
            // 唯一键只能代表同一 outbox；任何旧数据投影漂移都必须阻止 outbox 假完成。
            throw new ServiceException("站内通知持久化事实与 outbox 不一致", HttpStatus.ERROR);
        }
        complete(row, workerId, "站内通知已持久化");
    }

    /**
     * 在独立事务中锁定正式租约、执行 SMTP 投递并提交成功或失败状态。
     *
     * @param row OutboxRow，持有租约的邮件记录
     * @param workerId String，必须与正式 outbox 租约一致的 worker 标识
     * @return void，投递结果在同一事务内进入成功、重试或死信状态
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void deliverEmail(OutboxRow row, String workerId)
    {
        // SMTP 是不可回滚的外部副作用，必须先持有 outbox 行锁，与任务或流程终态取消建立唯一顺序。
        lockClaimedOutbox(row, workerId);
        completeDelivery(row, workerId, sendEmail(row));
    }

    /**
     * 通过正式 SMTP 发送一次邮件，地址在发送前从正式用户目录实时读取。
     *
     * @param row OutboxRow，已经在当前事务中锁定租约的邮件记录
     * @return DeliveryOutcome，成功或脱敏失败结果
     */
    private DeliveryOutcome sendEmail(OutboxRow row)
    {
        if (!StringUtils.hasText(properties.getMailFrom()))
        {
            return DeliveryOutcome.failure("SMTP_NOT_CONFIGURED", "SMTP 发件配置未启用", false);
        }
        List<String> addresses = jdbcTemplate.queryForList(
                "select email from sys_user u left join wf_notification_preference p on p.user_id=u.user_id " +
                "where u.user_id=? and u.status='0' and u.del_flag='0' and u.email is not null and u.email<>'' " +
                "and coalesce(p.email_enabled,1)=1", String.class, row.recipientUserId());
        if (addresses.size() != 1)
        {
            return DeliveryOutcome.failure("RECIPIENT_INVALID", "接收人已失效、停用邮件或没有有效邮箱", true);
        }
        try
        {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(properties.getMailFrom().trim());
            helper.setTo(addresses.get(0));
            helper.setSubject(row.title());
            helper.setText(row.content(), false);
            message.setHeader("Message-ID", "<" + row.idempotencyKey() + "@approvaplat.notification>");
            message.setHeader("X-ApprovaPlat-Idempotency-Key", row.idempotencyKey());
            mailSender.send(message);
            return DeliveryOutcome.delivered();
        }
        catch (Exception exception)
        {
            // 异常类和服务端配置不进入持久化，避免 SMTP 主机、账号或地址泄露。
            return DeliveryOutcome.failure("SMTP_DELIVERY_FAILED", "SMTP 投递失败", false);
        }
    }

    /**
     * 提交当前通知通道的投递结果并执行有界退避或死信。
     * @param row OutboxRow，领取快照
     * @param workerId String，租约持有者
     * @param outcome DeliveryOutcome，当前通道的投递结果
     * @return void，租约漂移时返回冲突
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void completeDelivery(OutboxRow row, String workerId, DeliveryOutcome outcome)
    {
        if (outcome.success())
        {
            complete(row, workerId, "SMTP 已接受邮件");
            return;
        }
        boolean exhausted = outcome.permanent() || row.attemptCount() >= row.maxAttempts();
        String target = exhausted ? "DEAD_LETTER" : "RETRYING";
        long delay = exhausted ? 0L : Math.min(properties.getMaxRetryDelay().toSeconds(),
                1L << Math.min(20, row.attemptCount()));
        int updated = jdbcTemplate.update("update wf_notification_outbox set status=?," +
                "next_attempt_at=date_add(current_timestamp(3),interval ? second),lease_owner=null,lease_expires_at=null," +
                "processed_time=case when ?='DEAD_LETTER' then current_timestamp(3) else null end," +
                "last_error_code=?,last_error_summary=?,revision=revision+1 where outbox_id=? and status='DELIVERING' " +
                "and lease_owner=? and revision=?", target, delay, target,
                outcome.errorCode(), outcome.summary(), row.outboxId(), workerId, row.revision());
        if (updated != 1) throw new ServiceException("通知投递租约已变化", HttpStatus.CONFLICT);
        audit(row.outboxId(), exhausted ? "DEAD_LETTER" : "RETRY", row.attemptCount(),
                "DELIVERING", target, "SYSTEM", workerId, outcome.errorCode(),
                exhausted ? "有界投递已进入死信" : "等待指数退避重试");
    }

    /**
     * 判断当前是否仍有可领取通知，用于 worker 有界批次循环。
     * @return int，单轮批次上限
     */
    public int batchSize() { return properties.getBatchSize(); }

    /**
     * 按正式策略和稳定业务来源键登记通知 outbox。
     *
     * @param context EventContext，已经冻结的流程、任务、接收人与页面路由上下文
     * @param sourceEventKey String，业务事实用于幂等计算的稳定来源键
     * @return int，本次实际新增的通知通道 outbox 数量
     */
    private int enqueue(EventContext context, String sourceEventKey)
    {
        return enqueueDetailed(context, sourceEventKey).outboxCount();
    }

    /**
     * 按正式策略登记事件通道，并返回至少有一个真实 outbox 的唯一接收人。
     * @param context EventContext，已经冻结的业务事件上下文
     * @param sourceEventKey String，当前业务事实的稳定幂等来源键
     * @return EnqueueResult，新增 outbox 数量及真实写入接收人集合
     */
    private EnqueueResult enqueueDetailed(EventContext context, String sourceEventKey)
    {
        Policy policy = resolvePolicy(context);
        if (policy == null) return EnqueueResult.empty();
        Set<String> recipients = resolveRecipients(policy, context);
        if (recipients.isEmpty()) return EnqueueResult.empty();
        Map<String, String> variables = Map.of(
                "processName", safe(context.processName()),
                "processDefinitionKey", safe(context.processDefinitionKey()),
                "processInstanceId", safe(context.processInstanceId()),
                "taskName", safe(context.taskName()),
                "taskDefinitionKey", safe(context.taskDefinitionKey()),
                "eventType", context.eventType());
        String title = render(policy.titleTemplate(), variables, MAX_TITLE_LENGTH);
        String content = render(policy.contentTemplate(), variables, MAX_CONTENT_LENGTH);
        int inserted = 0;
        LinkedHashSet<String> insertedRecipients = new LinkedHashSet<>();
        for (String recipient : recipients)
        {
            for (String channel : csv(policy.channels()))
            {
                String preferenceColumn = "INBOX".equals(channel) ? "inbox_enabled" : "email_enabled";
                String idempotency = sha256(sourceEventKey, context.eventType(), recipient, channel);
                boolean newlyInserted;
                try
                {
                    int mutationCount = jdbcTemplate.update("insert into wf_notification_outbox " +
                            "(idempotency_key,event_type,channel,recipient_user_id,process_definition_key," +
                            "process_instance_id,task_id,task_definition_key,actor_user_id,title,content,route_path," +
                            "status,attempt_count,max_attempts,next_attempt_at,revision,create_time) " +
                            "select ?,?,?,?,?,?,?,?,?,?,?,?,'PENDING',0,?,current_timestamp(3),0,current_timestamp(3) " +
                            "from sys_user u left join wf_notification_preference p on p.user_id=u.user_id " +
                            "where u.user_id=? and u.status='0' and u.del_flag='0' " +
                            "and coalesce(p." + preferenceColumn + ",1)=1",
                            idempotency, context.eventType(), channel, Long.valueOf(recipient),
                            context.processDefinitionKey(), context.processInstanceId(), context.taskId(),
                            context.taskDefinitionKey(), context.actorUserId(), title, content,
                            context.routePath(), policy.maxAttempts(), Long.valueOf(recipient));
                    if (mutationCount < 0 || mutationCount > 1)
                    {
                        throw new ServiceException("通知 outbox 写入结果异常", HttpStatus.ERROR);
                    }
                    newlyInserted = mutationCount == 1;
                }
                catch (DuplicateKeyException exception)
                {
                    // MySQL 普通 INSERT 的唯一键异常不依赖 CLIENT_FOUND_ROWS，跨连接部署也能稳定识别重放。
                    newlyInserted = false;
                }
                List<OutboxFact> facts = jdbcTemplate.query(
                        "select outbox_id,idempotency_key,event_type,channel,recipient_user_id,process_definition_key," +
                        "process_instance_id,task_id,task_definition_key,route_path " +
                        "from wf_notification_outbox where idempotency_key=? for share",
                        (result, rowNum) -> new OutboxFact(result.getLong("outbox_id"),
                                result.getString("idempotency_key"),
                                result.getString("event_type"), result.getString("channel"),
                                result.getLong("recipient_user_id"),
                                result.getString("process_definition_key"),
                                result.getString("process_instance_id"), result.getString("task_id"),
                                result.getString("task_definition_key"),
                                result.getString("route_path")), idempotency);
                if (facts.isEmpty())
                {
                    // 用户或通道偏好在本事务已失效时 SELECT 不产生来源行，这是合法的无投递结果。
                    if (newlyInserted)
                    {
                        throw new ServiceException("通知 outbox 写入结果不一致", HttpStatus.ERROR);
                    }
                    continue;
                }
                if (facts.size() != 1 || !facts.get(0).matches(
                        idempotency, context, channel, recipient))
                {
                    // 幂等键只能重放完全相同的业务事实；碰撞或旧数据漂移必须回滚当前业务动作。
                    throw new ServiceException("通知 outbox 幂等事实不一致", HttpStatus.ERROR);
                }
                if (newlyInserted)
                {
                    audit(facts.get(0).outboxId(), "ENQUEUE", 0, null, "PENDING", "SYSTEM", "flowable",
                            null, "审批事务内登记通知 outbox");
                    inserted++;
                    insertedRecipients.add(recipient);
                }
            }
        }
        return new EnqueueResult(inserted, Set.copyOf(insertedRecipients));
    }

    /**
     * 从真实流程定义、流程实例和当前认证身份构建不可变通知事件上下文。
     *
     * @param eventType String，服务端归一化后的通知事件类型
     * @param processDefinitionId String，真实 Flowable 流程定义主键
     * @param processInstanceId String，通知所属根流程实例主键
     * @param taskId String，可空；通知关联的活动或历史任务主键
     * @param taskDefinitionKey String，可空；通知关联的 BPMN 任务节点 key
     * @param taskName String，可空；通知关联的任务显示名称
     * @param taskRecipients Set&lt;String&gt;，经过正式身份目录过滤的任务接收人
     * @return EventContext，包含发起人、操作者和稳定详情路由的事件快照
     */
    private EventContext context(String eventType, String processDefinitionId,
            String processInstanceId, String taskId, String taskDefinitionKey,
            String taskName, Set<String> taskRecipients)
    {
        ProcessDefinition definition = repositoryService.getProcessDefinition(processDefinitionId);
        if (definition == null) throw new ServiceException("流程定义不存在", HttpStatus.ERROR);
        String initiator = processInitiator(processInstanceId);
        String actor = Authentication.getAuthenticatedUserId();
        String source = taskId == null ? "own" : "todo";
        String route = "/workflow/process-detail/" + processInstanceId
                + "?source=" + source + (taskId == null ? "" : "&taskId=" + taskId);
        return new EventContext(eventType, definition.getKey(), definition.getName(),
                processInstanceId, taskId, taskDefinitionKey, taskName, actor, initiator,
                taskRecipients, route);
    }

    /**
     * 按抄送事件和真实接收人批量读取正式 wf_copy 主键，SQL 结构只由服务端占位符生成。
     * @param copyEventId String，抄送业务事件幂等键
     * @param userIds Set&lt;Long&gt;，本次预期存在的真实抄送用户主键
     * @return List&lt;Map&lt;String,Object&gt;&gt;，copy_id 与 user_id 正式事实
     */
    private List<Map<String, Object>> selectPersistedCopies(String copyEventId, Set<Long> userIds)
    {
        if (userIds.isEmpty())
        {
            return List.of();
        }
        String placeholders = String.join(",", userIds.stream().map(ignored -> "?").toList());
        List<Object> parameters = new ArrayList<>(userIds.size() + 1);
        parameters.add(copyEventId);
        parameters.addAll(userIds);
        return jdbcTemplate.queryForList("select copy_id,user_id from wf_copy " +
                "where copy_event_id=? and user_id in (" + placeholders + ") and del_flag='0' " +
                "order by copy_id", parameters.toArray());
    }

    /**
     * 先锁定并验证根流程实例，再冻结根实例下全部活动 execution 和 CallActivity 子实例。
     *
     * @param processInstanceId String，客户端提交且已规范化的根流程实例主键
     * @return RuntimeProcessSnapshot，根身份、挂起态及完整活动流程实例集合的 current-read 快照
     */
    private RuntimeProcessSnapshot lockRuntimeProcessTree(String processInstanceId)
    {
        List<RuntimeProcessSnapshot> locked = jdbcTemplate.query(
                "select e.PROC_DEF_ID_,e.SUSPENSION_STATE_,h.START_USER_ID_ " +
                "from ACT_RU_EXECUTION e join ACT_HI_PROCINST h on h.PROC_INST_ID_=e.PROC_INST_ID_ " +
                "where e.ID_=? and e.PROC_INST_ID_=? for update",
                (result, rowNum) -> new RuntimeProcessSnapshot(
                        result.getString("PROC_DEF_ID_"), result.getInt("SUSPENSION_STATE_"),
                        result.getString("START_USER_ID_"), List.of()),
                processInstanceId, processInstanceId);
        if (locked.size() != 1)
        {
            throw new ServiceException("流程状态已变化，不能催办", HttpStatus.CONFLICT);
        }
        RuntimeProcessSnapshot root = locked.get(0);
        if (!StringUtils.hasText(root.processDefinitionId())
                || !StringUtils.hasText(root.startUserId()))
        {
            throw new ServiceException("流程运行身份数据不完整", HttpStatus.ERROR);
        }

        // 根 execution 行已先锁定；随后按固定 ID 顺序锁完整树，令子流程创建、完成、终止与催办串行化。
        List<RuntimeExecutionSnapshot> executions = jdbcTemplate.query(
                "select ID_,PROC_INST_ID_,ROOT_PROC_INST_ID_,PROC_DEF_ID_,SUSPENSION_STATE_ " +
                "from ACT_RU_EXECUTION where ROOT_PROC_INST_ID_=? or PROC_INST_ID_=? " +
                "order by ID_ limit 10001 for update",
                (result, rowNum) -> new RuntimeExecutionSnapshot(result.getString("ID_"),
                        result.getString("PROC_INST_ID_"), result.getString("ROOT_PROC_INST_ID_"),
                        result.getString("PROC_DEF_ID_"), result.getInt("SUSPENSION_STATE_")),
                processInstanceId, processInstanceId);
        if (executions.size() > MAX_EXECUTIONS_PER_URGE)
        {
            throw new ServiceException("活动执行数量超过催办上限", HttpStatus.CONFLICT);
        }

        LinkedHashSet<String> processInstanceIds = new LinkedHashSet<>();
        LinkedHashSet<String> processInstanceRootRows = new LinkedHashSet<>();
        for (RuntimeExecutionSnapshot execution : executions)
        {
            if (!StringUtils.hasText(execution.executionId())
                    || !StringUtils.hasText(execution.processInstanceId())
                    || !StringUtils.hasText(execution.processDefinitionId()))
            {
                throw new ServiceException("活动流程树数据不完整", HttpStatus.ERROR);
            }
            String currentInstanceId = execution.processInstanceId();
            boolean rootInstance = processInstanceId.equals(currentInstanceId);
            String declaredRootId = execution.rootProcessInstanceId();
            if ((!rootInstance && !processInstanceId.equals(declaredRootId))
                    || (rootInstance && StringUtils.hasText(declaredRootId)
                            && !processInstanceId.equals(declaredRootId)))
            {
                throw new ServiceException("活动流程树根实例关系不一致", HttpStatus.ERROR);
            }
            if (execution.suspensionState() != 1)
            {
                throw new ServiceException("挂起流程不能催办", HttpStatus.CONFLICT);
            }
            processInstanceIds.add(currentInstanceId);
            if (execution.executionId().equals(currentInstanceId))
            {
                processInstanceRootRows.add(currentInstanceId);
            }
        }
        if (!processInstanceIds.contains(processInstanceId)
                || !processInstanceRootRows.equals(processInstanceIds))
        {
            throw new ServiceException("活动流程树实例边界不完整", HttpStatus.ERROR);
        }
        return new RuntimeProcessSnapshot(root.processDefinitionId(), root.suspensionState(),
                root.startUserId(), processInstanceIds.stream().sorted().toList());
    }

    /**
     * 锁定完整活动实例树内的全部真实任务，防止接收人与完成、认领或转办并发漂移。
     *
     * @param processInstanceIds List&lt;String&gt;，已持有 execution 行锁的根及 CallActivity 子实例主键
     * @return List&lt;LockedTask&gt;，按任务主键排序且与实际行锁一一对应的活动任务快照
     */
    private List<LockedTask> lockRuntimeTasks(List<String> processInstanceIds)
    {
        if (processInstanceIds == null || processInstanceIds.isEmpty())
        {
            throw new ServiceException("活动流程树实例为空", HttpStatus.ERROR);
        }
        String placeholders = String.join(",", processInstanceIds.stream().map(ignored -> "?").toList());
        List<LockedTask> lockedTasks = jdbcTemplate.query(
                "select ID_,PROC_DEF_ID_,PROC_INST_ID_,TASK_DEF_KEY_,NAME_,ASSIGNEE_,SUSPENSION_STATE_ " +
                "from ACT_RU_TASK where PROC_INST_ID_ in (" + placeholders + ") " +
                "order by ID_ limit 2001 for update",
                (result, rowNum) -> new LockedTask(result.getString("ID_"),
                        result.getString("PROC_DEF_ID_"), result.getString("PROC_INST_ID_"),
                        result.getString("TASK_DEF_KEY_"), result.getString("NAME_"),
                        result.getString("ASSIGNEE_"), result.getInt("SUSPENSION_STATE_")),
                processInstanceIds.toArray());
        if (lockedTasks.size() > MAX_TASKS_PER_URGE)
        {
            throw new ServiceException("活动待办数量超过催办上限", HttpStatus.CONFLICT);
        }
        Set<String> allowedProcessInstanceIds = Set.copyOf(processInstanceIds);
        if (lockedTasks.stream().anyMatch(task ->
                !allowedProcessInstanceIds.contains(task.processInstanceId())
                        || !StringUtils.hasText(task.taskId())
                        || !StringUtils.hasText(task.processDefinitionId())))
        {
            throw new ServiceException("活动待办锁定结果不一致", HttpStatus.ERROR);
        }
        if (lockedTasks.stream().anyMatch(task -> task.suspensionState() != 1))
        {
            throw new ServiceException("挂起任务不能催办", HttpStatus.CONFLICT);
        }
        return List.copyOf(lockedTasks);
    }

    /**
     * 使用锁定任务及候选关系的 current-read 快照解析催办接收人。
     * @param task LockedTask，已经持有 ACT_RU_TASK 行锁的任务快照
     * @return Set&lt;String&gt;，当前真实办理人或候选人集合
     */
    private Set<String> resolveLockedTaskRecipients(LockedTask task)
    {
        if (StringUtils.hasText(task.assignee()))
        {
            List<String> assigned = jdbcTemplate.queryForList(
                    "select cast(user_id as char) from sys_user where user_id=? " +
                    "and status='0' and del_flag='0' for share", String.class,
                    Long.valueOf(task.assignee()));
            return Set.copyOf(assigned);
        }
        List<String> candidates = jdbcTemplate.queryForList(
                "select cast(u.user_id as char) from sys_user u where u.status='0' and u.del_flag='0' " +
                "and (exists (select 1 from ACT_RU_IDENTITYLINK il where il.TASK_ID_=? " +
                "and il.TYPE_='candidate' and il.USER_ID_ regexp '^[1-9][0-9]{0,18}$' " +
                "and cast(il.USER_ID_ as unsigned)=u.user_id) " +
                "or exists (select 1 from ACT_RU_IDENTITYLINK il join sys_user_role ur on ur.user_id=u.user_id " +
                "join sys_role r on r.role_id=ur.role_id where il.TASK_ID_=? and il.TYPE_='candidate' " +
                "and il.GROUP_ID_=concat('ROLE',ur.role_id) and r.status='0' and r.del_flag='0') " +
                "or exists (select 1 from ACT_RU_IDENTITYLINK il join sys_dept d on d.dept_id=u.dept_id " +
                "where il.TASK_ID_=? and il.TYPE_='candidate' and il.GROUP_ID_=concat('DEPT',u.dept_id) " +
                "and d.status='0' and d.del_flag='0')) and (u.user_id=1 or 5=(select count(distinct m.perms) " +
                "from sys_user_role pur join sys_role pr on pr.role_id=pur.role_id " +
                "join sys_role_menu prm on prm.role_id=pr.role_id join sys_menu m on m.menu_id=prm.menu_id " +
                "where pur.user_id=u.user_id and pr.status='0' and pr.del_flag='0' and pr.role_id<>1 " +
                "and m.status='0' and m.perms in ('workflow:process:claimList','workflow:process:claim'," +
                "'workflow:process:todoList','workflow:process:query','workflow:process:approval'))) " +
                "order by u.user_id limit 2001 for share", String.class,
                task.taskId(), task.taskId(), task.taskId());
        if (candidates.size() > MAX_RECIPIENTS_PER_EVENT)
        {
            throw new ServiceException("通知接收人数超过单事件上限", HttpStatus.CONFLICT);
        }
        return Set.copyOf(candidates);
    }

    /**
     * 从同一 current-read 流程树和任务快照建立催办事件上下文。
     *
     * @param process RuntimeProcessSnapshot，已锁定的根身份和完整活动实例树快照
     * @param task LockedTask，已锁定的根流程或 CallActivity 子流程任务快照
     * @param recipients Set&lt;String&gt;，已解析当前接收人
     * @return EventContext，使用实际任务定义、子实例和详情路由的不可变通知上下文
     */
    private EventContext contextForUrge(RuntimeProcessSnapshot process, LockedTask task,
            Set<String> recipients)
    {
        if (!process.processInstanceIds().contains(task.processInstanceId()))
        {
            throw new ServiceException("催办任务不属于已锁定流程树", HttpStatus.ERROR);
        }
        // CallActivity 子任务必须按自己的定义解析节点策略，同时审计和授权仍使用根实例身份。
        ProcessDefinition definition = repositoryService.getProcessDefinition(task.processDefinitionId());
        if (definition == null) throw new ServiceException("流程定义不存在", HttpStatus.ERROR);
        return new EventContext("MANUAL_URGE", definition.getKey(), definition.getName(),
                task.processInstanceId(), task.taskId(), task.taskDefinitionKey(), task.taskName(),
                Authentication.getAuthenticatedUserId(), process.startUserId(), recipients,
                "/workflow/process-detail/" + task.processInstanceId() + "?source=todo&taskId=" + task.taskId());
    }

    /**
     * 将失效催办取消登记到当前写事务的提交前阶段，统一 Flowable 与通知表的加锁顺序。
     * Flowable 完成事件早于引擎实体 flush；若此时直接锁 outbox 空区间，会与先锁 execution 的并发催办死锁。
     *
     * @param processInstanceId String，催办所属流程实例主键
     * @param taskId String，可空；非空时仅取消指定任务催办
     * @param detail String，不含业务正文的取消原因
     * @return void，缺少事务同步器时拒绝继续，提交前取消失败会使整个业务事务回滚
     */
    private void schedulePendingUrgeCancellation(String processInstanceId, String taskId,
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
             * 在 Flowable 已 flush 执行树且数据库事务尚未提交时锁定并取消催办。
             *
             * @param readOnly boolean，当前事务是否声明为只读
             * @return void，只读事务或取消失败时抛出异常并阻止业务提交
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
     * 取消已经失去活动业务对象且尚未完成投递的人工催办 outbox，并逐条保存正式审计。
     * 活动投递与本方法使用同一 outbox 行锁串行化：先取得锁的一方决定投递或取消，另一方不得越过终态产生副作用。
     *
     * @param processInstanceId String，催办所属流程实例主键
     * @param taskId String，可空；非空时仅取消指定已完成任务的催办
     * @param detail String，不含业务正文的取消原因
     * @return int，实际取消数量
     */
    private int cancelPendingUrges(String processInstanceId, String taskId, String detail)
    {
        List<String> processInstanceIds = taskId == null
                ? historicProcessTreeInstanceIds(processInstanceId)
                : List.of(processInstanceId);
        String processPlaceholders = String.join(",",
                processInstanceIds.stream().map(ignored -> "?").toList());
        String taskFilter = taskId == null ? "" : " and task_id=?";
        List<Object> parameters = new ArrayList<>(processInstanceIds);
        if (taskId != null) parameters.add(taskId);
        List<CancelableOutbox> rows = jdbcTemplate.query(
                "select outbox_id,status,attempt_count,revision from wf_notification_outbox " +
                "where event_type='MANUAL_URGE' and process_instance_id in (" +
                processPlaceholders + ")" + taskFilter +
                " and status in ('PENDING','RETRYING','DELIVERING') " +
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
            audit(row.outboxId(), "CANCEL", row.attemptCount(), row.status(), "CANCELLED",
                    "SYSTEM", "flowable", "BUSINESS_OBJECT_COMPLETED", detail);
        }
        return rows.size();
    }

    /**
     * 从正式历史父子关系解析根业务实例及全部 CallActivity 后代实例，用于终态取消失效催办。
     *
     * @param rootProcessInstanceId String，已经进入终态处理的根流程实例主键
     * @return List&lt;String&gt;，按实例主键排序且至少包含根实例的完整历史流程树
     */
    private List<String> historicProcessTreeInstanceIds(String rootProcessInstanceId)
    {
        LinkedHashSet<String> processInstanceIds = new LinkedHashSet<>();
        processInstanceIds.add(rootProcessInstanceId);
        List<String> frontier = List.of(rootProcessInstanceId);
        while (!frontier.isEmpty())
        {
            String placeholders = String.join(",", frontier.stream().map(ignored -> "?").toList());
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
            if (processInstanceIds.size() > MAX_EXECUTIONS_PER_URGE)
            {
                throw new ServiceException("历史流程树实例数量超过催办取消上限", HttpStatus.CONFLICT);
            }
            frontier = List.copyOf(next);
        }
        return processInstanceIds.stream().sorted().toList();
    }

    /**
     * 将一条已耗尽次数的到期或过期租约原子收敛为死信，避免它阻塞后续可领取记录。
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
        if (rows.isEmpty())
        {
            return false;
        }
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
        audit(row.outboxId(), "DEAD_LETTER", row.attemptCount(), row.status(), "DEAD_LETTER",
                "SYSTEM", workerId, "LEASE_EXPIRED_AFTER_FINAL_ATTEMPT",
                "最终投递租约过期，原子转入死信");
        return true;
    }

    /**
     * 从运行实例或历史实例中解析流程的真实发起用户主键。
     *
     * @param processInstanceId String，根流程实例主键
     * @return String，发起用户主键；运行和历史均无事实时为 null
     */
    private String processInitiator(String processInstanceId)
    {
        ProcessInstance runtime = runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        if (runtime != null && StringUtils.hasText(runtime.getStartUserId())) return runtime.getStartUserId();
        HistoricProcessInstance historic = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        return historic == null ? null : historic.getStartUserId();
    }

    /**
     * 根据任务当前办理人或候选用户、候选组解析真实有效接收人。
     *
     * @param task Task，已从 Flowable 查询到的当前任务
     * @return Set&lt;String&gt;，去重后的有效办理人或具备认领权限的候选用户主键
     */
    private Set<String> resolveTaskRecipients(Task task)
    {
        if (StringUtils.hasText(task.getAssignee()))
        {
            return activeUsers(List.of(task.getAssignee()), List.of(), false);
        }
        List<IdentityLink> links = taskService.getIdentityLinksForTask(task.getId());
        LinkedHashSet<String> users = new LinkedHashSet<>();
        LinkedHashSet<String> groups = new LinkedHashSet<>();
        if (links != null)
        {
            for (IdentityLink link : links)
            {
                if (link == null || !IdentityLinkType.CANDIDATE.equals(link.getType())) continue;
                if (StringUtils.hasText(link.getUserId())) users.add(link.getUserId());
                if (StringUtils.hasText(link.getGroupId())) groups.add(link.getGroupId());
            }
        }
        return activeUsers(users, groups, true);
    }

    /**
     * 通过正式身份目录展开用户和组，并可选择继续过滤具备审批认领权限的用户。
     *
     * @param users Collection&lt;String&gt;，候选用户主键集合
     * @param groups Collection&lt;String&gt;，Flowable 候选组标识集合
     * @param requireClaimPermission boolean，是否要求接收人具备当前审批认领权限
     * @return Set&lt;String&gt;，有效且不超过单事件上限的正式用户主键集合
     */
    private Set<String> activeUsers(Collection<String> users, Collection<String> groups,
            boolean requireClaimPermission)
    {
        Set<String> active = identityResolver.resolveActiveUserIds(users, groups);
        Set<String> recipients = requireClaimPermission
                ? identityResolver.resolveClaimEligibleUserIds(active) : active;
        if (recipients.size() > MAX_RECIPIENTS_PER_EVENT)
        {
            throw new ServiceException("通知接收人数超过单事件上限", HttpStatus.CONFLICT);
        }
        return recipients;
    }

    /**
     * 将固定监听事件转换为不会依赖 assignment 中间态的业务通知事件。
     * @param event String，仅允许 create 或 complete
     * @return String，TASK_ARRIVED 或 TASK_COMPLETED
     */
    private String classifyTaskEvent(String event)
    {
        if ("create".equals(event)) return "TASK_ARRIVED";
        if ("complete".equals(event)) return "TASK_COMPLETED";
        throw invalid("任务通知事件不受支持");
    }

    /**
     * 按节点、流程、默认的优先级解析当前事件唯一启用通知策略。
     *
     * @param context EventContext，包含事件类型、流程 key 和任务节点 key 的冻结上下文
     * @return Policy，优先级最高的正式策略；没有启用策略时为 null
     */
    private Policy resolvePolicy(EventContext context)
    {
        List<Policy> policies = jdbcTemplate.query(
                "select policy_id,recipient_rules,channels,title_template,content_template,max_attempts " +
                "from wf_notification_policy where event_type=? and status='ENABLED' and (" +
                "(scope_type='NODE' and process_definition_key=? and task_definition_key=?) or " +
                "(scope_type='PROCESS' and process_definition_key=?) or scope_type='DEFAULT') " +
                "order by field(scope_type,'NODE','PROCESS','DEFAULT') limit 1",
                (result, rowNum) -> new Policy(result.getLong("policy_id"),
                        result.getString("recipient_rules"), result.getString("channels"),
                        result.getString("title_template"), result.getString("content_template"),
                        result.getInt("max_attempts")), context.eventType(),
                context.processDefinitionKey(), context.taskDefinitionKey(),
                context.processDefinitionKey());
        return policies.isEmpty() ? null : policies.get(0);
    }

    /**
     * 按策略接收人规则合并任务接收人、发起人和当前操作者，并过滤无效用户。
     *
     * @param policy Policy，已从正式策略表选中的启用策略
     * @param context EventContext，包含各类候选身份的冻结业务上下文
     * @return Set&lt;String&gt;，去重且有效的正式通知接收用户主键
     */
    private Set<String> resolveRecipients(Policy policy, EventContext context)
    {
        LinkedHashSet<String> recipients = new LinkedHashSet<>();
        for (String rule : csv(policy.recipientRules()))
        {
            if ("TASK_RECIPIENT".equals(rule)) recipients.addAll(context.taskRecipients());
            if ("INITIATOR".equals(rule) && StringUtils.hasText(context.initiatorUserId()))
                recipients.add(context.initiatorUserId());
            if ("ACTOR".equals(rule) && StringUtils.hasText(context.actorUserId()))
                recipients.add(context.actorUserId());
        }
        return activeUsers(recipients, List.of(), false);
    }

    /**
     * 在领域层校验通知策略作用域、枚举、模板、通道和有界重试配置。
     *
     * @param request WorkflowNotificationPolicyRequest，管理员提交的完整策略请求
     * @return ValidatedPolicy，可按正式字段顺序写入数据库的规范策略
     */
    private ValidatedPolicy validatePolicy(WorkflowNotificationPolicyRequest request)
    {
        if (request == null) throw invalid("通知策略不能为空");
        String scope = upper(request.scopeType());
        String event = upper(request.eventType());
        String status = upper(request.status());
        String processKey = optional(request.processDefinitionKey(), 255);
        String taskKey = optional(request.taskDefinitionKey(), 255);
        if (!SCOPES.contains(scope) || !EVENT_TYPES.contains(event) || !STATUSES.contains(status))
            throw invalid("通知策略枚举值不合法");
        if (("DEFAULT".equals(scope) && (processKey != null || taskKey != null))
                || ("PROCESS".equals(scope) && (processKey == null || taskKey != null))
                || ("NODE".equals(scope) && (processKey == null || taskKey == null)))
            throw invalid("通知策略作用域字段不一致");
        String recipients = normalizedCsv(request.recipientRules(), RECIPIENT_RULE_ORDER);
        String channels = normalizedCsv(request.channels(), CHANNEL_ORDER);
        String titleTemplate = validateTemplate(request.titleTemplate(), MAX_TITLE_LENGTH, "通知标题模板不合法");
        String contentTemplate = validateTemplate(request.contentTemplate(), MAX_CONTENT_LENGTH, "通知正文模板不合法");
        if (request.maxAttempts() == null || request.maxAttempts() < 1 || request.maxAttempts() > 20)
            throw invalid("通知最大投递次数必须为 1 至 20");
        return new ValidatedPolicy(scope, processKey, taskKey, event, recipients, channels,
                titleTemplate, contentTemplate,
                request.maxAttempts(), status);
    }

    /**
     * 按新增和更新 SQL 共用的字段顺序绑定规范通知策略及审计操作者。
     *
     * @param statement PreparedStatement，当前新增或更新策略的预编译语句
     * @param policy ValidatedPolicy，已经通过领域校验的规范策略
     * @param actor String，执行策略维护的管理员用户主键
     * @param update boolean，调用方是否执行更新语义；用于保留新增与更新调用契约
     * @return void，全部正式字段绑定完成后正常返回
     * @throws java.sql.SQLException JDBC 驱动拒绝参数绑定时抛出
     */
    private void bindPolicy(PreparedStatement statement, ValidatedPolicy policy,
            String actor, boolean update) throws java.sql.SQLException
    {
        statement.setString(1, policy.scopeType());
        statement.setString(2, policy.processDefinitionKey());
        statement.setString(3, policy.taskDefinitionKey());
        statement.setString(4, policy.eventType());
        statement.setString(5, policy.recipientRules());
        statement.setString(6, policy.channels());
        statement.setString(7, policy.titleTemplate());
        statement.setString(8, policy.contentTemplate());
        statement.setInt(9, policy.maxAttempts());
        statement.setString(10, policy.status());
        statement.setString(11, actor);
    }

    /**
     * 写入一次人工催办的正式业务审计，并返回数据库生成的审计主键。
     *
     * @param processInstanceId String，已通过对象权限和运行状态校验的流程实例主键
     * @param actor WorkflowCurrentIdentity，执行人工催办的当前正式身份
     * @param recipientCount int，本次锁定快照解析出的真实接收人数
     * @param reason String，已经规范化且满足长度限制的催办原因
     * @return long，wf_notification_urge_audit 生成的正式审计主键
     */
    private long insertUrgeAudit(String processInstanceId, WorkflowCurrentIdentity actor,
            int recipientCount, String reason)
    {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection ->
        {
            PreparedStatement statement = connection.prepareStatement(
                    "insert into wf_notification_urge_audit (process_instance_id,actor_user_id," +
                    "recipient_count,reason,create_time) values (?,?,?,?,current_timestamp(3))",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, processInstanceId);
            statement.setLong(2, Long.parseLong(actor.userId()));
            statement.setInt(3, recipientCount);
            statement.setString(4, reason);
            return statement;
        }, holder);
        if (holder.getKey() == null) throw new ServiceException("催办审计保存失败", HttpStatus.ERROR);
        return holder.getKey().longValue();
    }

    /**
     * 以租约持有者和版本条件将投递中的 outbox 原子提交为成功，并写入同周期审计。
     *
     * @param row OutboxRow，当前 worker 持有的不可变领取快照
     * @param workerId String，必须与正式 outbox 租约一致的 worker 标识
     * @param detail String，不包含敏感地址或正文的成功审计说明
     * @return void，租约漂移或审计写入失败时抛出异常并回滚
     */
    private void complete(OutboxRow row, String workerId, String detail)
    {
        int updated = jdbcTemplate.update("update wf_notification_outbox set status='PROCESSED'," +
                "processed_time=current_timestamp(3),lease_owner=null,lease_expires_at=null," +
                "last_error_code=null,last_error_summary=null,revision=revision+1 where outbox_id=? " +
                "and status='DELIVERING' and lease_owner=? and revision=?",
                row.outboxId(), workerId, row.revision());
        if (updated != 1) throw new ServiceException("通知投递租约已变化", HttpStatus.CONFLICT);
        audit(row.outboxId(), "DELIVER", row.attemptCount(), "DELIVERING", "PROCESSED",
                "SYSTEM", workerId, null, detail);
    }

    /**
     * 锁定并复核 worker 当前持有的正式 outbox 租约，作为通知副作用和业务终态取消之间的数据库栅栏。
     *
     * @param row OutboxRow，claimNext 返回的不可变领取快照
     * @param workerId String，当前 worker 标识
     * @return void，租约仍由当前 worker 持有时返回，否则抛出 409 且不得产生副作用
     */
    private void lockClaimedOutbox(OutboxRow row, String workerId)
    {
        if (row == null)
        {
            throw new ServiceException("通知投递快照不能为空", HttpStatus.CONFLICT);
        }
        String normalizedWorkerId = normalized(workerId, 128, "通知 worker 标识不合法");
        List<Long> locked = jdbcTemplate.queryForList(
                "select outbox_id from wf_notification_outbox where outbox_id=? " +
                "and status='DELIVERING' and lease_owner=? and revision=? for update",
                Long.class, row.outboxId(), normalizedWorkerId, row.revision());
        if (locked.size() != 1)
        {
            throw new ServiceException("通知投递租约已变化", HttpStatus.CONFLICT);
        }
    }

    /**
     * 复核 outbox 当前投递周期与尝试序号后，写入不可静默去重的状态机审计。
     *
     * @param outboxId Long，必须存在的正式通知 outbox 主键
     * @param action String，ENQUEUE、DELIVER、RETRY、DEAD_LETTER、CANCEL 或 COMPENSATE 动作
     * @param attempt int，当前投递周期的尝试序号
     * @param from String，可空；状态迁移前的 outbox 状态
     * @param to String，状态迁移后的 outbox 状态
     * @param actorType String，SYSTEM 或 USER 审计主体类型
     * @param actorId String，worker、Flowable 或正式用户主键
     * @param errorCode String，可空；脱敏后的稳定失败码
     * @param detail String，可空；不包含凭据、地址或通知正文的审计说明
     * @return void，序号不一致、重复审计或写入失败时抛出异常并回滚
     */
    private void audit(Long outboxId, String action, int attempt, String from, String to,
            String actorType, String actorId, String errorCode, String detail)
    {
        if (outboxId == null) throw new ServiceException("通知 outbox 主键缺失", HttpStatus.ERROR);
        List<AuditSequence> sequences = jdbcTemplate.query(
                "select delivery_cycle,attempt_count,total_attempt_count from wf_notification_outbox where outbox_id=?",
                (result, rowNum) -> new AuditSequence(result.getInt("delivery_cycle"),
                        result.getInt("attempt_count"), result.getInt("total_attempt_count")),
                outboxId);
        if (sequences.size() != 1 || attempt != sequences.get(0).attemptCount())
        {
            throw new ServiceException("通知投递审计序号与 outbox 状态不一致", HttpStatus.ERROR);
        }
        AuditSequence sequence = sequences.get(0);
        // 不使用 insert ignore；审计唯一键冲突表示状态机重复提交，必须回滚而不是静默丢失事实。
        int inserted = jdbcTemplate.update("insert into wf_notification_delivery_audit " +
                "(outbox_id,action_type,delivery_cycle,attempt_no,total_attempt_no,from_status,to_status," +
                "actor_type,actor_id,error_code,detail,create_time) values (?,?,?,?,?,?,?,?,?,?,?,current_timestamp(3))",
                outboxId, action, sequence.deliveryCycle(), attempt, sequence.totalAttemptCount(),
                from, to, actorType, actorId, errorCode, detail);
        if (inserted != 1)
        {
            throw new ServiceException("通知投递审计保存失败", HttpStatus.ERROR);
        }
    }

    /**
     * 渲染白名单通知模板，并按正式 VARCHAR 字符上限做 Unicode 安全截断。
     * @param template String，已通过策略校验的模板
     * @param variables Map&lt;String,String&gt;，固定白名单变量值
     * @param maxCodePoints int，outbox 正式字段允许的最大 Unicode 字符数
     * @return String，不超过数据库字符上限的确定性结果
     */
    private String render(String template, Map<String, String> variables, int maxCodePoints)
    {
        Matcher matcher = TEMPLATE_VARIABLE.matcher(template);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find())
        {
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(variables.get(matcher.group(1))));
        }
        matcher.appendTail(rendered);
        String result = rendered.toString();
        int codePoints = result.codePointCount(0, result.length());
        if (codePoints <= maxCodePoints) return result;
        return result.substring(0, result.offsetByCodePoints(0, maxCodePoints));
    }

    /**
     * 在领域服务内校验模板非空、原始长度和变量白名单，不依赖 Controller Bean Validation。
     * @param template String，客户端提交的原始模板
     * @param maxCodePoints int，正式策略字段字符上限
     * @param message String，稳定参数错误提示
     * @return String，去除首尾空白后的合法模板
     */
    private String validateTemplate(String template, int maxCodePoints, String message)
    {
        if (!StringUtils.hasText(template)) throw invalid("通知模板不能为空");
        String normalized = template.trim();
        if (normalized.codePointCount(0, normalized.length()) > maxCodePoints)
            throw invalid(message);
        Matcher matcher = TEMPLATE_VARIABLE.matcher(normalized);
        while (matcher.find())
        {
            if (!TEMPLATE_VARIABLES.contains(matcher.group(1)))
                throw invalid("通知模板包含非白名单变量: " + matcher.group(1));
        }
        String residue = TEMPLATE_VARIABLE.matcher(normalized).replaceAll("");
        if (residue.contains("{{") || residue.contains("}}")) throw invalid("通知模板变量格式不合法");
        return normalized;
    }

    /**
     * 校验 CSV 枚举并按服务端固定顺序输出，避免合法集合因客户端顺序触发 DDL CHECK。
     * @param value String，客户端 CSV
     * @param canonicalOrder List&lt;String&gt;，唯一合法值及正式保存顺序
     * @return String，去重且顺序稳定的 CSV
     */
    private String normalizedCsv(String value, List<String> canonicalOrder)
    {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String item : csv(value))
        {
            String normalized = upper(item);
            if (!canonicalOrder.contains(normalized)) throw invalid("通知策略包含不支持的配置: " + item);
            values.add(normalized);
        }
        if (values.isEmpty()) throw invalid("通知策略列表不能为空");
        return canonicalOrder.stream().filter(values::contains)
                .collect(java.util.stream.Collectors.joining(","));
    }

    /**
     * 将可空 CSV 文本拆分为去除首尾空白的非空条目列表。
     *
     * @param value String，可空；客户端提交的逗号分隔配置
     * @return List&lt;String&gt;，保持客户端顺序的非空条目；无有效内容时为空列表
     */
    private List<String> csv(String value)
    {
        if (!StringUtils.hasText(value)) return List.of();
        return Arrays.stream(value.split(",")).map(String::trim).filter(item -> !item.isEmpty()).toList();
    }

    /**
     * 规范化可选策略标识并执行长度和控制字符校验。
     *
     * @param value String，可空；流程或任务定义 key 原始值
     * @param max int，正式字段允许的最大字符数
     * @return String，规范后的策略标识；无有效内容时为 null
     */
    private String optional(String value, int max)
    {
        if (!StringUtils.hasText(value)) return null;
        return normalized(value, max, "通知策略标识不合法");
    }

    /**
     * 规范化必填文本，并拒绝超长或包含 ISO 控制字符的值。
     *
     * @param value String，可空；需要校验的原始文本
     * @param max int，正式字段允许的最大字符数
     * @param message String，校验失败时返回的稳定参数提示
     * @return String，去除首尾空白后的合法文本
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
     * 使用固定 Locale 将可空枚举文本规范为大写。
     *
     * @param value String，可空；客户端或 Flowable 提供的枚举文本
     * @return String，去除首尾空白的大写文本；输入为空时为空串
     */
    private String upper(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }

    /**
     * 将可空文本转换为可参与模板渲染和哈希计算的非空文本。
     *
     * @param value String，可空；业务快照中的原始文本
     * @return String，原始文本或空串
     */
    private String safe(String value) { return value == null ? "" : value; }

    /**
     * 解析当前正式身份为正数用户主键，并把身份异常稳定映射为未授权。
     *
     * @return long，当前有效用户的正式数字主键
     */
    private long currentUserId()
    {
        try { return Long.parseLong(identityResolver.resolveCurrentIdentity().userId()); }
        catch (RuntimeException exception) { throw new ServiceException("当前用户身份无效", HttpStatus.UNAUTHORIZED); }
    }

    /**
     * 要求通知 outbox 登记运行在当前 Flowable 可写事务中，保证业务与通知原子提交。
     *
     * @return void，缺少事务或处于只读事务时抛出服务端状态异常
     */
    private void requireWriteTransaction()
    {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new ServiceException("通知 outbox 必须在 Flowable 写事务中登记", HttpStatus.ERROR);
    }

    /**
     * 构造通知配置或操作参数不合法的 HTTP 400 业务异常。
     *
     * @param message String，可向调用方返回的稳定业务提示
     * @return ServiceException，HTTP 400 业务异常
     */
    private ServiceException invalid(String message)
    {
        return new ServiceException(message, HttpStatus.BAD_REQUEST);
    }

    /**
     * 以零字节分隔各业务字段并计算稳定 SHA-256 幂等键。
     *
     * @param values String[]，可含空值的业务身份字段序列
     * @return String，固定 64 位小写十六进制摘要
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

    /** worker 使用的不可变 outbox 领取快照。 */
    public record OutboxRow(long outboxId, String idempotencyKey, String eventType,
            String channel, long recipientUserId, String processInstanceId, String taskId,
            String title, String content, String routePath, String previousStatus,
            int deliveryCycle, int attemptCount, int totalAttemptCount,
            int maxAttempts, int revision) {}

    /** SMTP 投递结果，不携带异常栈、主机、账号或邮箱地址。 */
    public record DeliveryOutcome(boolean success, String errorCode, String summary,
            boolean permanent)
    {
        /**
         * 创建不携带失败信息的 SMTP 成功结果。
         *
         * @return DeliveryOutcome，成功且非永久失败的不可变结果
         */
        public static DeliveryOutcome delivered() { return new DeliveryOutcome(true, null, null, false); }

        /**
         * 创建只包含稳定失败码与脱敏摘要的 SMTP 失败结果。
         *
         * @param code String，稳定且不包含凭据的错误码
         * @param summary String，脱敏后的失败摘要
         * @param permanent boolean，是否应立即终止重试并进入死信
         * @return DeliveryOutcome，供事务内投递状态机提交的不可变失败结果
         */
        public static DeliveryOutcome failure(String code, String summary, boolean permanent)
        { return new DeliveryOutcome(false, code, summary, permanent); }
    }

    private record Policy(long policyId, String recipientRules, String channels,
            String titleTemplate, String contentTemplate, int maxAttempts) {}
    private record ValidatedPolicy(String scopeType, String processDefinitionKey,
            String taskDefinitionKey, String eventType, String recipientRules, String channels,
            String titleTemplate, String contentTemplate, int maxAttempts, String status) {}
    private record EventContext(String eventType, String processDefinitionKey, String processName,
            String processInstanceId, String taskId, String taskDefinitionKey, String taskName,
            String actorUserId, String initiatorUserId, Set<String> taskRecipients,
            String routePath) {}
    private record EnqueueResult(int outboxCount, Set<String> recipientUserIds)
    {
        /**
         * 创建没有新增 outbox 和接收人的稳定空登记结果。
         *
         * @return EnqueueResult，数量为零且接收人集合不可变的结果
         */
        private static EnqueueResult empty()
        {
            return new EnqueueResult(0, Set.of());
        }
    }
    private record AuditSequence(int deliveryCycle, int attemptCount, int totalAttemptCount) {}
    private record RuntimeProcessSnapshot(String processDefinitionId, int suspensionState,
            String startUserId, List<String> processInstanceIds) {}
    private record RuntimeExecutionSnapshot(String executionId, String processInstanceId,
            String rootProcessInstanceId, String processDefinitionId, int suspensionState) {}
    private record LockedTask(String taskId, String processDefinitionId, String processInstanceId,
            String taskDefinitionKey, String taskName, String assignee, int suspensionState) {}
    private record OutboxFact(long outboxId, String idempotencyKey, String eventType, String channel,
            long recipientUserId, String processDefinitionKey, String processInstanceId,
            String taskId, String taskDefinitionKey, String routePath)
    {
        /**
         * 核对幂等键关联 outbox 的稳定业务身份。
         * @param idempotency String，本次计算的 SHA-256 幂等键
         * @param context EventContext，当前业务事件冻结上下文
         * @param expectedChannel String，当前投递通道
         * @param recipient String，当前接收用户主键
         * @return boolean，幂等来源对应的事件、接收人和业务路由全部一致时为 true
         */
        private boolean matches(String idempotency, EventContext context, String expectedChannel,
                String recipient)
        {
            return Objects.equals(idempotencyKey, idempotency)
                    && Objects.equals(eventType, context.eventType())
                    && Objects.equals(channel, expectedChannel)
                    && recipientUserId == Long.parseLong(recipient)
                    && Objects.equals(processDefinitionKey, context.processDefinitionKey())
                    && Objects.equals(processInstanceId, context.processInstanceId())
                    && Objects.equals(taskId, context.taskId())
                    && Objects.equals(taskDefinitionKey, context.taskDefinitionKey())
                    && Objects.equals(routePath, context.routePath());
        }
    }
    private record InboxFact(long recipientUserId, String eventType, String title, String content,
            String processInstanceId, String taskId, String routePath)
    {
        /**
         * 核对已存在或新写入站内信的不可变业务投影。
         * @param row OutboxRow，当前持有租约的 outbox 冻结快照
         * @return boolean，接收人、事件、正文和业务路由全部一致时为 true
         */
        private boolean matches(OutboxRow row)
        {
            return recipientUserId == row.recipientUserId()
                    && Objects.equals(eventType, row.eventType())
                    && Objects.equals(title, row.title())
                    && Objects.equals(content, row.content())
                    && Objects.equals(processInstanceId, row.processInstanceId())
                    && Objects.equals(taskId, row.taskId())
                    && Objects.equals(routePath, row.routePath());
        }
    }
    private record CancelableOutbox(long outboxId, String status, int attemptCount, int revision) {}
    private record ExhaustedOutbox(long outboxId, String status, int attemptCount, int revision) {}
}
