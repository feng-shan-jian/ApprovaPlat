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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.config.WorkflowNotificationProperties;
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

    private static final Set<String> RECIPIENT_RULES = Set.of(
            "TASK_RECIPIENT", "INITIATOR", "ACTOR");
    private static final Set<String> CHANNELS = Set.of("INBOX", "EMAIL");
    private static final Set<String> SCOPES = Set.of("DEFAULT", "PROCESS", "NODE");
    private static final Set<String> STATUSES = Set.of("ENABLED", "DISABLED");
    private static final Pattern TEMPLATE_VARIABLE = Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9]*)}}" );
    private static final int MAX_TASKS_PER_URGE = 2_000;
    private static final int MAX_RECIPIENTS_PER_EVENT = 2_000;
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
        String eventType = classifyTaskEvent(flowableEvent, taskId, assignee, owner);
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
        EventContext context = context(eventType, processDefinitionId, processInstanceId,
                null, null, null, Set.of());
        return enqueue(context, "PROCESS:" + processInstanceId + ":" + eventType);
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
            ProcessInstance instance = runtimeService.createProcessInstanceQuery()
                    .processInstanceId(processInstanceId).singleResult();
            if (instance == null) throw new ServiceException("流程已结束，不能催办", HttpStatus.CONFLICT);
            if (instance.isSuspended()) throw new ServiceException("挂起流程不能催办", HttpStatus.CONFLICT);
            if (!actor.userId().equals(instance.getStartUserId())
                    && !permissionService.hasPermi(URGE_ANY_PERMISSION))
            {
                throw new ServiceException("无权催办当前流程", HttpStatus.FORBIDDEN);
            }

            List<Task> tasks = taskService.createTaskQuery().processInstanceId(processInstanceId)
                    .active().orderByTaskId().asc().list();
            if (tasks == null || tasks.isEmpty())
            {
                throw new ServiceException("流程没有可催办的活动待办", HttpStatus.CONFLICT);
            }
            if (tasks.size() > MAX_TASKS_PER_URGE)
            {
                throw new ServiceException("活动待办数量超过催办上限", HttpStatus.CONFLICT);
            }
            Timestamp frequencyBoundary = Timestamp.from(Instant.now().minus(properties.getUrgeInterval()));
            Integer recent = jdbcTemplate.queryForObject(
                    "select count(*) from wf_notification_urge_audit where process_instance_id=? and actor_user_id=? and create_time>?",
                    Integer.class, processInstanceId, Long.valueOf(actor.userId()), frequencyBoundary);
            if (recent != null && recent > 0)
            {
                throw new ServiceException("催办过于频繁，请稍后再试", HttpStatus.TOO_MANY_REQUESTS);
            }

            LinkedHashMap<Task, Set<String>> recipientsByTask = new LinkedHashMap<>();
            LinkedHashSet<String> allRecipients = new LinkedHashSet<>();
            for (Task task : tasks)
            {
                Set<String> recipients = resolveTaskRecipients(task);
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

            long urgeId = insertUrgeAudit(processInstanceId, actor, allRecipients.size(), reason);
            int outboxCount = 0;
            for (Map.Entry<Task, Set<String>> entry : recipientsByTask.entrySet())
            {
                Task task = entry.getKey();
                EventContext context = context("MANUAL_URGE", task.getProcessDefinitionId(),
                        processInstanceId, task.getId(), task.getTaskDefinitionKey(),
                        task.getName(), entry.getValue());
                outboxCount += enqueue(context, "URGE:" + urgeId + ":" + task.getId());
            }
            return Map.of("urgeId", urgeId, "recipientCount", allRecipients.size(),
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
            catch (DataAccessException exception)
            {
                throw new ServiceException("相同作用域和事件的通知策略已存在", HttpStatus.CONFLICT);
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
                "status,attempt_count as attemptCount,max_attempts as maxAttempts,next_attempt_at as nextAttemptAt," +
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
                "attempt_count=0,next_attempt_at=current_timestamp(3),lease_owner=null,lease_expires_at=null," +
                "last_error_code=null,last_error_summary=null,revision=revision+1 where outbox_id=? and status='DEAD_LETTER'",
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
        List<OutboxRow> rows = jdbcTemplate.query(
                "select outbox_id,idempotency_key,event_type,channel,recipient_user_id," +
                "process_instance_id,task_id,title,content,route_path,status,attempt_count,max_attempts,revision " +
                "from wf_notification_outbox where ((status in ('PENDING','RETRYING') and next_attempt_at<=current_timestamp(3)) " +
                "or (status='DELIVERING' and lease_expires_at<current_timestamp(3))) " +
                "order by outbox_id limit 1 for update skip locked",
                (result, rowNum) -> new OutboxRow(result.getLong("outbox_id"),
                        result.getString("idempotency_key"), result.getString("event_type"),
                        result.getString("channel"), result.getLong("recipient_user_id"),
                        result.getString("process_instance_id"), result.getString("task_id"),
                        result.getString("title"), result.getString("content"),
                        result.getString("route_path"), result.getString("status"),
                        result.getInt("attempt_count") + 1, result.getInt("max_attempts"),
                        result.getInt("revision") + 1));
        if (rows.isEmpty()) return null;
        OutboxRow row = rows.get(0);
        int updated = jdbcTemplate.update("update wf_notification_outbox set status='DELIVERING'," +
                "attempt_count=attempt_count+1,lease_owner=?,lease_expires_at=date_add(current_timestamp(3),interval ? second)," +
                "revision=revision+1 where outbox_id=? and revision=? and status=?",
                workerId, properties.getLeaseDuration().toSeconds(), row.outboxId(),
                row.revision() - 1, row.previousStatus());
        if (updated != 1) return null;
        audit(row.outboxId(), "CLAIM", row.attemptCount(), row.previousStatus(), "DELIVERING",
                "SYSTEM", workerId, null, "worker 已领取投递租约");
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
        jdbcTemplate.update("insert ignore into wf_notification_inbox " +
                "(outbox_id,recipient_user_id,event_type,title,content,process_instance_id,task_id,route_path,read_status,create_time) " +
                "select outbox_id,recipient_user_id,event_type,title,content,process_instance_id,task_id,route_path,'UNREAD',current_timestamp(3) " +
                "from wf_notification_outbox where outbox_id=?", row.outboxId());
        complete(row, workerId, "站内通知已持久化");
    }

    /**
     * 在事务外通过正式 SMTP 发送一次邮件，地址在发送前从正式用户目录实时读取。
     * @param row OutboxRow，持有租约的邮件记录
     * @return DeliveryOutcome，成功或脱敏失败结果
     */
    public DeliveryOutcome deliverEmail(OutboxRow row)
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
     * 在独立事务中提交邮件投递结果并执行有界退避或死信。
     * @param row OutboxRow，领取快照
     * @param workerId String，租约持有者
     * @param outcome DeliveryOutcome，事务外 SMTP 结果
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
                "last_error_code=?,last_error_summary=?,revision=revision+1 where outbox_id=? and status='DELIVERING' " +
                "and lease_owner=? and revision=?", target, delay, outcome.errorCode(), outcome.summary(),
                row.outboxId(), workerId, row.revision());
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

    private int enqueue(EventContext context, String sourceEventKey)
    {
        Policy policy = resolvePolicy(context);
        if (policy == null) return 0;
        Set<String> recipients = resolveRecipients(policy, context);
        if (recipients.isEmpty()) return 0;
        Map<String, String> variables = Map.of(
                "processName", safe(context.processName()),
                "processDefinitionKey", safe(context.processDefinitionKey()),
                "processInstanceId", safe(context.processInstanceId()),
                "taskName", safe(context.taskName()),
                "taskDefinitionKey", safe(context.taskDefinitionKey()),
                "eventType", context.eventType());
        String title = render(policy.titleTemplate(), variables);
        String content = render(policy.contentTemplate(), variables);
        int inserted = 0;
        for (String recipient : recipients)
        {
            for (String channel : csv(policy.channels()))
            {
                String preferenceColumn = "INBOX".equals(channel) ? "inbox_enabled" : "email_enabled";
                String idempotency = sha256(sourceEventKey, context.eventType(), recipient, channel);
                int count = jdbcTemplate.update("insert ignore into wf_notification_outbox " +
                        "(idempotency_key,event_type,channel,recipient_user_id,process_definition_key," +
                        "process_instance_id,task_id,task_definition_key,actor_user_id,title,content,route_path," +
                        "status,attempt_count,max_attempts,next_attempt_at,revision,create_time) " +
                        "select ?,?,?,?,?,?,?,?,?,?,?,?,'PENDING',0,?,current_timestamp(3),0,current_timestamp(3) " +
                        "from sys_user u left join wf_notification_preference p on p.user_id=u.user_id " +
                        "where u.user_id=? and u.status='0' and u.del_flag='0' and coalesce(p." + preferenceColumn + ",1)=1",
                        idempotency, context.eventType(), channel, Long.valueOf(recipient),
                        context.processDefinitionKey(), context.processInstanceId(), context.taskId(),
                        context.taskDefinitionKey(), context.actorUserId(), title, content,
                        context.routePath(), policy.maxAttempts(), Long.valueOf(recipient));
                if (count == 1)
                {
                    Long outboxId = jdbcTemplate.queryForObject(
                            "select outbox_id from wf_notification_outbox where idempotency_key=?",
                            Long.class, idempotency);
                    audit(outboxId, "ENQUEUE", 0, null, "PENDING", "SYSTEM", "flowable",
                            null, "审批事务内登记通知 outbox");
                    inserted++;
                }
            }
        }
        return inserted;
    }

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

    private String processInitiator(String processInstanceId)
    {
        ProcessInstance runtime = runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        if (runtime != null && StringUtils.hasText(runtime.getStartUserId())) return runtime.getStartUserId();
        HistoricProcessInstance historic = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        return historic == null ? null : historic.getStartUserId();
    }

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

    private String classifyTaskEvent(String event, String taskId, String assignee, String owner)
    {
        if ("create".equals(event)) return "TASK_ARRIVED";
        if ("complete".equals(event)) return "TASK_COMPLETED";
        if (!"assignment".equals(event)) throw invalid("任务通知事件不受支持");
        Object returnedApplicant = taskService.getVariableLocal(taskId,
                "__ruoyi_workflow_return_applicant");
        if (returnedApplicant != null && returnedApplicant.equals(assignee)) return "TASK_RETURNED";
        if (!StringUtils.hasText(assignee)) return "TASK_UNCLAIMED";
        if (StringUtils.hasText(owner) && !owner.equals(assignee)) return "TASK_DELEGATED";
        if (StringUtils.hasText(owner) && owner.equals(assignee)) return "TASK_DELEGATION_RESOLVED";
        String actor = Authentication.getAuthenticatedUserId();
        if (!StringUtils.hasText(actor)) return "TASK_ARRIVED";
        return assignee.equals(actor) ? "TASK_CLAIMED" : "TASK_TRANSFERRED";
    }

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
        String recipients = normalizedCsv(request.recipientRules(), RECIPIENT_RULES);
        String channels = normalizedCsv(request.channels(), CHANNELS);
        validateTemplate(request.titleTemplate());
        validateTemplate(request.contentTemplate());
        return new ValidatedPolicy(scope, processKey, taskKey, event, recipients, channels,
                request.titleTemplate().trim(), request.contentTemplate().trim(),
                request.maxAttempts(), status);
    }

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

    private void audit(Long outboxId, String action, int attempt, String from, String to,
            String actorType, String actorId, String errorCode, String detail)
    {
        if (outboxId == null) throw new ServiceException("通知 outbox 主键缺失", HttpStatus.ERROR);
        jdbcTemplate.update("insert ignore into wf_notification_delivery_audit " +
                "(outbox_id,action_type,attempt_no,from_status,to_status,actor_type,actor_id,error_code,detail,create_time) " +
                "values (?,?,?,?,?,?,?,?,?,current_timestamp(3))", outboxId, action, attempt,
                from, to, actorType, actorId, errorCode, detail);
    }

    private String render(String template, Map<String, String> variables)
    {
        Matcher matcher = TEMPLATE_VARIABLE.matcher(template);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find())
        {
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(variables.get(matcher.group(1))));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    private void validateTemplate(String template)
    {
        if (!StringUtils.hasText(template)) throw invalid("通知模板不能为空");
        Matcher matcher = TEMPLATE_VARIABLE.matcher(template);
        while (matcher.find())
        {
            if (!TEMPLATE_VARIABLES.contains(matcher.group(1)))
                throw invalid("通知模板包含非白名单变量: " + matcher.group(1));
        }
        String residue = TEMPLATE_VARIABLE.matcher(template).replaceAll("");
        if (residue.contains("{{") || residue.contains("}}")) throw invalid("通知模板变量格式不合法");
    }

    private String normalizedCsv(String value, Set<String> allowed)
    {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String item : csv(value))
        {
            String normalized = upper(item);
            if (!allowed.contains(normalized)) throw invalid("通知策略包含不支持的配置: " + item);
            values.add(normalized);
        }
        if (values.isEmpty()) throw invalid("通知策略列表不能为空");
        return String.join(",", values);
    }

    private List<String> csv(String value)
    {
        if (!StringUtils.hasText(value)) return List.of();
        return Arrays.stream(value.split(",")).map(String::trim).filter(item -> !item.isEmpty()).toList();
    }

    private String optional(String value, int max)
    {
        if (!StringUtils.hasText(value)) return null;
        return normalized(value, max, "通知策略标识不合法");
    }

    private String normalized(String value, int max, String message)
    {
        if (!StringUtils.hasText(value)) throw invalid(message);
        String normalized = value.trim();
        if (normalized.length() > max || normalized.chars().anyMatch(Character::isISOControl))
            throw invalid(message);
        return normalized;
    }

    private String upper(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
    private String safe(String value) { return value == null ? "" : value; }

    private long currentUserId()
    {
        try { return Long.parseLong(identityResolver.resolveCurrentIdentity().userId()); }
        catch (RuntimeException exception) { throw new ServiceException("当前用户身份无效", HttpStatus.UNAUTHORIZED); }
    }

    private void requireWriteTransaction()
    {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new ServiceException("通知 outbox 必须在 Flowable 写事务中登记", HttpStatus.ERROR);
    }

    private ServiceException invalid(String message)
    {
        return new ServiceException(message, HttpStatus.BAD_REQUEST);
    }

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
            int attemptCount, int maxAttempts, int revision) {}

    /** SMTP 投递结果，不携带异常栈、主机、账号或邮箱地址。 */
    public record DeliveryOutcome(boolean success, String errorCode, String summary,
            boolean permanent)
    {
        public static DeliveryOutcome delivered() { return new DeliveryOutcome(true, null, null, false); }
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
}
