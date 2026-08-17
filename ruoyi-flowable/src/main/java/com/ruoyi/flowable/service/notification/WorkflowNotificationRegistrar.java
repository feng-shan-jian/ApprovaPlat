package com.ruoyi.flowable.service.notification;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfCopy;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;

/**
 * 通知登记服务，根据已提交到当前业务事务的流程事实解析策略、收件人并写入 outbox/inbox。
 */
@Service
public class WorkflowNotificationRegistrar
{
    private static final Set<String> EVENT_TYPES = WorkflowNotificationConstants.EVENT_TYPES;

    private static final Set<String> SYNCHRONOUS_SOURCE_TYPES = Set.of("SLA", "BPMN_EVENT");
    private static final Pattern TEMPLATE_VARIABLE = Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9]*)}}" );
    private static final int MAX_RECIPIENTS_PER_EVENT = 2_000;
    private static final int MAX_TITLE_LENGTH = 160;
    private static final int MAX_CONTENT_LENGTH = 700;

    private final JdbcTemplate jdbcTemplate;
    private final RepositoryService repositoryService;
    private final RuntimeService runtimeService;
    private final HistoryService historyService;
    private final TaskService taskService;
    private final WorkflowIdentityResolver identityResolver;
    private final WorkflowNotificationOutboxService outboxService;

    /**
     * 创建通知领域服务。
     * @param jdbcTemplate JdbcTemplate，正式通知表和用户目录访问
     * @param repositoryService RepositoryService，流程定义名称查询
     * @param runtimeService RuntimeService，运行实例状态查询
     * @param historyService HistoryService，流程发起人与历史状态查询
     * @param taskService TaskService，活动待办和候选身份查询
     * @param identityResolver WorkflowIdentityResolver，正式用户及候选组展开
     * @param outboxService WorkflowNotificationOutboxService，通知投递状态唯一所有者
     * @return void，构造后由 Spring 管理
     */
    public WorkflowNotificationRegistrar(JdbcTemplate jdbcTemplate,
            RepositoryService repositoryService, RuntimeService runtimeService,
            HistoryService historyService, TaskService taskService,
            WorkflowIdentityResolver identityResolver,
            WorkflowNotificationOutboxService outboxService)
    {
        this.jdbcTemplate = jdbcTemplate;
        this.repositoryService = repositoryService;
        this.runtimeService = runtimeService;
        this.historyService = historyService;
        this.taskService = taskService;
        this.identityResolver = identityResolver;
        this.outboxService = outboxService;
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
        return doOnTaskEvent(flowableEvent, taskId, processInstanceId, processDefinitionId,
                taskDefinitionKey, taskName, assignee, owner);
    }

    /**
     * 冻结 Flowable 任务监听事实并按稳定来源键登记通知。
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
    private int doOnTaskEvent(String flowableEvent, String taskId, String processInstanceId,
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
                processInstanceId, WorkflowNotificationConstants.CONTROLLED_TRANSITION_VARIABLE);
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
            outboxService.schedulePendingUrgeCancellation(processInstanceId, taskId,
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
        return doOnStableTaskEvent(eventType, task);
    }

    /**
     * 读取受控退回或重提后的最终任务归属并登记通知。
     * @param eventType String，仅允许 TASK_RETURNED 或 TASK_RESUBMITTED
     * @param task Task，归属恢复后的活动任务
     * @return int，实际登记的通道 outbox 数量
     */
    private int doOnStableTaskEvent(String eventType, Task task)
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
        return doOnStableTaskAction(eventType, taskId);
    }

    /**
     * 复核认领、释放、委派、归还或转办后的任务 revision 并登记通知。
     * @param eventType String，稳定任务动作事件类型
     * @param taskId String，活动任务主键
     * @return int，实际登记的通知 outbox 数量
     */
    private int doOnStableTaskAction(String eventType, String taskId)
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
        outboxService.cancelPendingUrges(stableTask.getProcessInstanceId(), stableTask.getId(),
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
        return doOnCopiesCreated(copies);
    }

    /**
     * 核对批量 wf_copy 正式事实身份并逐条登记通知。
     * @param copies Collection&lt;WfCopy&gt;，本次业务事务写入或幂等命中的抄送事实
     * @return int，实际新增的通知 outbox 数量
     */
    private int doOnCopiesCreated(Collection<WfCopy> copies)
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
                outboxCount += doOnCopyCreated(copyId.longValue());
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
        return doOnCopyCreated(copyId);
    }

    /**
     * 读取单条 wf_copy 正式事实并登记幂等通知。
     * @param copyId long，wf_copy 正式记录主键
     * @return int，首次登记的 outbox 数量；幂等重放为零
     */
    private int doOnCopyCreated(long copyId)
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
        return doOnProcessResult(eventType, processDefinitionId, processInstanceId);
    }

    /**
     * 根据人工催办服务已经锁定和授权的任务事实登记通知。
     * @param registration WorkflowManualUrgeRegistration，任务、身份、接收人和催办摘要
     * @return WorkflowManualUrgeRegistrationResult，实际 outbox 数和有效接收人
     */
    public WorkflowManualUrgeRegistrationResult registerManualUrge(
            WorkflowManualUrgeRegistration registration)
    {
        requireWriteTransaction();
        if (registration == null || registration.recipientUserIds() == null
                || registration.recipientUserIds().isEmpty())
        {
            throw invalid("催办登记事实不完整");
        }
        ProcessDefinition definition = repositoryService.getProcessDefinition(
                normalized(registration.processDefinitionId(), 64, "流程定义主键不合法"));
        if (definition == null)
        {
            throw new ServiceException("流程定义不存在", HttpStatus.ERROR);
        }
        String processInstanceId = normalized(registration.processInstanceId(), 64,
                "流程实例主键不合法");
        String taskId = normalized(registration.taskId(), 64, "任务主键不合法");
        EventContext context = new EventContext("MANUAL_URGE", definition.getKey(),
                definition.getName(), processInstanceId, taskId,
                optional(registration.taskDefinitionKey(), 255),
                optional(registration.taskName(), 255),
                normalized(registration.actorUserId(), 64, "催办用户主键不合法"),
                normalized(registration.startUserId(), 64, "流程发起人主键不合法"),
                Set.copyOf(registration.recipientUserIds()),
                "/workflow/process-detail/" + processInstanceId + "?source=todo&taskId=" + taskId);
        EnqueueResult result = enqueueDetailed(context,
                normalizedSourceId(registration.sourceId()));
        appendUrgeReason(registration.sourceId(), result.outboxCount(),
                normalized(registration.reason(), 500, "催办原因不合法"));
        return new WorkflowManualUrgeRegistrationResult(result.outboxCount(),
                result.recipientUserIds());
    }

    /**
     * 冻结流程终态事实、安排失效催办取消并登记结果通知。
     * @param eventType String，流程终态通知事件
     * @param processDefinitionId String，流程定义主键
     * @param processInstanceId String，根流程实例主键
     * @return int，实际登记的 outbox 数量
     */
    private int doOnProcessResult(String eventType, String processDefinitionId,
            String processInstanceId)
    {
        requireWriteTransaction();
        if (!Set.of("PROCESS_COMPLETED", "PROCESS_CANCELED", "PROCESS_REJECTED",
                "PROCESS_TERMINATED").contains(eventType))
        {
            throw invalid("流程通知事件不受支持");
        }
        // Flowable 尚未 flush 执行树；提交前再锁 outbox，避免与并发催办形成反向锁序。
        outboxService.schedulePendingUrgeCancellation(processInstanceId, null,
                "流程已结束，取消未投递催办");
        EventContext context = context(eventType, processDefinitionId, processInstanceId,
                null, null, null, Set.of());
        return enqueue(context, "PROCESS:" + processInstanceId + ":" + eventType);
    }

    /**
     * 在调用方当前业务事务中同步创建统一 outbox、投递审计和站内通知。
     *
     * @param notification WorkflowSynchronousNotification，已经冻结来源、接收人和业务路由的通知事实
     * @return Long，首次创建或幂等重放对应的统一通知主键；接收人不存在或已停用时返回 null
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public Long publishSynchronousInbox(WorkflowSynchronousNotification notification)
    {
        return doPublishSynchronousInbox(notification);
    }

    /**
     * 在当前业务事务内登记同步 outbox、inbox 并提交 outbox 终态。
     * @param notification WorkflowSynchronousNotification，冻结来源、接收人和业务路由的通知事实
     * @return Long，统一通知主键；接收用户失效时为 null
     */
    private Long doPublishSynchronousInbox(WorkflowSynchronousNotification notification)
    {
        requireWriteTransaction();
        WorkflowSynchronousNotification fact = validateSynchronousNotification(notification);
        long recipientUserId = Long.parseLong(fact.recipientUserId());
        String idempotency = sha256(fact.sourceType(), fact.sourceId(), fact.eventType(),
                fact.recipientUserId(), "INBOX");
        boolean newlyInserted;
        try
        {
            int inserted = jdbcTemplate.update("insert into wf_notification_outbox " +
                    "(idempotency_key,source_type,source_id,event_type,channel,recipient_user_id," +
                    "process_definition_key,process_instance_id,task_id,task_definition_key," +
                    "actor_user_id,title,content,sms_template_id,route_path,status,attempt_count," +
                    "max_attempts,next_attempt_at,revision,create_time) " +
                    "select ?,?,?,?,'INBOX',?,?,?,?,?,null,?,?,null,?,'PENDING',0,1," +
                    "current_timestamp(3),0,current_timestamp(3) from sys_user u " +
                    "where u.user_id=? and u.status='0' and u.del_flag='0'",
                    idempotency, fact.sourceType(), fact.sourceId(), fact.eventType(),
                    recipientUserId, fact.processDefinitionKey(), fact.processInstanceId(),
                    fact.taskId(), fact.taskDefinitionKey(), fact.title(), fact.content(),
                    fact.routePath(), recipientUserId);
            if (inserted < 0 || inserted > 1)
            {
                throw new ServiceException("同步通知 outbox 写入结果异常", HttpStatus.ERROR);
            }
            newlyInserted = inserted == 1;
        }
        catch (DuplicateKeyException exception)
        {
            // 并发或业务重放必须复用同一来源事实，后续会锁行并逐字段核对。
            newlyInserted = false;
        }

        // INSERT IGNORE 已按唯一幂等键串行化首次写入；重复事务可能持有唯一索引共享锁，
        // 此处若再升级为 FOR UPDATE 会形成锁升级死锁，一致性读即可核对已提交的不可变事实。
        List<SynchronousOutboxFact> outboxes = jdbcTemplate.query(
                "select outbox_id,idempotency_key,source_type,source_id,event_type,channel," +
                "recipient_user_id,process_definition_key,process_instance_id,task_id," +
                "task_definition_key,title,content,route_path,status " +
                "from wf_notification_outbox where idempotency_key=?",
                (result, rowNum) -> new SynchronousOutboxFact(result.getLong("outbox_id"),
                        result.getString("idempotency_key"), result.getString("source_type"),
                        result.getString("source_id"), result.getString("event_type"),
                        result.getString("channel"), result.getLong("recipient_user_id"),
                        result.getString("process_definition_key"),
                        result.getString("process_instance_id"), result.getString("task_id"),
                        result.getString("task_definition_key"), result.getString("title"),
                        result.getString("content"), result.getString("route_path"),
                        result.getString("status")), idempotency);
        if (outboxes.isEmpty())
        {
            if (newlyInserted)
            {
                throw new ServiceException("同步通知 outbox 写入结果不一致", HttpStatus.ERROR);
            }
            // 统一通知模型只为有效且启用的用户创建通知事实，避免业务来源与收件箱状态分裂。
            return null;
        }
        if (outboxes.size() != 1 || !outboxes.get(0).matches(idempotency, fact))
        {
            throw new ServiceException("同步通知 outbox 幂等事实不一致", HttpStatus.ERROR);
        }
        SynchronousOutboxFact outbox = outboxes.get(0);
        if (!newlyInserted)
        {
            if (!"PROCESSED".equals(outbox.status()))
            {
                throw new ServiceException("同步通知 outbox 状态不完整", HttpStatus.ERROR);
            }
            return requireSynchronousInbox(outbox);
        }

        outboxService.recordEnqueued(outbox.outboxId(), fact.sourceType(),
                "业务事务内登记统一站内通知");
        int inboxInserted = jdbcTemplate.update("insert into wf_notification_inbox " +
                "(outbox_id,notification_key,source_type,source_id,recipient_user_id,event_type," +
                "title,content,process_instance_id,task_id,route_path,read_status,create_time) " +
                "select outbox_id,sha2(concat_ws('|',source_type,source_id,event_type),256)," +
                "source_type,source_id,recipient_user_id,event_type,title,content,process_instance_id," +
                "task_id,route_path,'UNREAD',current_timestamp(3) " +
                "from wf_notification_outbox where outbox_id=?", outbox.outboxId());
        if (inboxInserted != 1)
        {
            throw new ServiceException("同步站内通知保存失败", HttpStatus.ERROR);
        }
        outboxService.completeSynchronous(outbox.outboxId(), fact.sourceType());
        return requireSynchronousInbox(outbox);
    }

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
        String sourceId = normalizedSourceId(sourceEventKey);
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
                String preferenceColumn = "INBOX".equals(channel) ? "inbox_enabled"
                        : ("EMAIL".equals(channel) ? "email_enabled" : "sms_enabled");
                int preferenceDefault = "SMS".equals(channel) ? 0 : 1;
                String idempotency = sha256(sourceId, context.eventType(), recipient, channel);
                boolean newlyInserted;
                try
                {
                    int mutationCount = jdbcTemplate.update("insert into wf_notification_outbox " +
                            "(idempotency_key,source_type,source_id,event_type,channel,recipient_user_id,process_definition_key," +
                            "process_instance_id,task_id,task_definition_key,actor_user_id,title,content," +
                            "sms_template_id,route_path," +
                            "status,attempt_count,max_attempts,next_attempt_at,revision,create_time) " +
                            "select ?,'APPROVAL',?,?,?,?,?,?,?,?,?,?,?,?,?,'PENDING',0,?,current_timestamp(3),0,current_timestamp(3) " +
                            "from sys_user u left join wf_notification_preference p on p.user_id=u.user_id " +
                            "where u.user_id=? and u.status='0' and u.del_flag='0' " +
                            "and coalesce(p." + preferenceColumn + "," + preferenceDefault + ")=1",
                            idempotency, sourceId, context.eventType(), channel, Long.valueOf(recipient),
                            context.processDefinitionKey(), context.processInstanceId(), context.taskId(),
                            context.taskDefinitionKey(), context.actorUserId(), title, content,
                            policy.smsTemplateId(), context.routePath(), policy.maxAttempts(), Long.valueOf(recipient));
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
                        "select outbox_id,idempotency_key,source_type,source_id,event_type,channel,recipient_user_id,process_definition_key," +
                        "process_instance_id,task_id,task_definition_key,route_path " +
                        "from wf_notification_outbox where idempotency_key=? for share",
                        (result, rowNum) -> new OutboxFact(result.getLong("outbox_id"),
                                result.getString("idempotency_key"),
                                result.getString("source_type"), result.getString("source_id"),
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
                        idempotency, sourceId, context, channel, recipient))
                {
                    // 幂等键只能重放完全相同的业务事实；碰撞或旧数据漂移必须回滚当前业务动作。
                    throw new ServiceException("通知 outbox 幂等事实不一致", HttpStatus.ERROR);
                }
                if (newlyInserted)
                {
                    recordDeliveryTransition(facts.get(0).outboxId(), "ENQUEUE", 0, null, "PENDING", "SYSTEM", "flowable",
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
     * 将人工填写的催办原因作为正式业务摘要写入本次新增 outbox，后续 INBOX 由同一内容生成。
     *
     * @param sourceId String，本次任务催办对应的精确来源键
     * @param expectedCount int，enqueueDetailed 返回的真实新增 outbox 数量
     * @param reason String，已经过长度和控制字符校验的催办原因
     * @return void，写入数量不一致时抛出错误并回滚整个催办事务
     */
    private void appendUrgeReason(String sourceId, int expectedCount, String reason)
    {
        if (expectedCount == 0) return;
        String summary = "\n催办原因：" + reason;
        int updated = jdbcTemplate.update("update wf_notification_outbox " +
                "set content=left(concat(content,?),?) where source_type='APPROVAL' " +
                "and source_id=? and event_type='MANUAL_URGE' and status='PENDING'",
                summary, MAX_CONTENT_LENGTH, sourceId);
        if (updated != expectedCount)
        {
            throw new ServiceException("催办通知业务摘要保存失败", HttpStatus.ERROR);
        }
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
                "select policy_id,recipient_rules,channels,sms_template_id,title_template,content_template,max_attempts " +
                "from wf_notification_policy where event_type=? and status='ENABLED' and (" +
                "(scope_type='NODE' and process_definition_key=? and task_definition_key=?) or " +
                "(scope_type='PROCESS' and process_definition_key=?) or scope_type='DEFAULT') " +
                "order by field(scope_type,'NODE','PROCESS','DEFAULT') limit 1",
                (result, rowNum) -> new Policy(result.getLong("policy_id"),
                        result.getString("recipient_rules"), result.getString("channels"),
                        result.getString("sms_template_id"), result.getString("title_template"),
                        result.getString("content_template"),
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
     * 记录一次不含通知正文、地址或凭据的 outbox 状态动作日志与指标。
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
     * @return void，指标动作未注册时抛出异常，防止新增状态动作绕过监控
     */
    private void recordDeliveryTransition(Long outboxId, String action, int attempt,
            String from, String to, String actorType, String actorId, String errorCode,
            String detail)
    {
        outboxService.recordTransition(outboxId, action, attempt, from, to, actorType,
                actorId, errorCode, detail);
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
     * 规范化统一通知的稳定来源标识，并限制为可写入 ascii_bin 字段的可见 ASCII。
     *
     * @param value String，普通审批事件键或 SLA、BPMN 事件审计主键
     * @return String，长度不超过 191 且不包含空白或控制字符的来源标识
     */
    private String normalizedSourceId(String value)
    {
        String sourceId = normalized(value, 191, "通知来源标识不合法");
        if (sourceId.chars().anyMatch(character -> character < 0x21 || character > 0x7e))
        {
            throw invalid("通知来源标识不合法");
        }
        return sourceId;
    }

    /**
     * 校验并冻结 SLA 或 BPMN 事件同步通知的正式字段。
     *
     * @param notification SynchronousNotification，调用方提供的同步通知事实
     * @return SynchronousNotification，可直接持久化且字段长度、枚举和用户主键均合法的通知事实
     */
    private WorkflowSynchronousNotification validateSynchronousNotification(
            WorkflowSynchronousNotification notification)
    {
        if (notification == null)
        {
            throw invalid("同步通知事实不能为空");
        }
        String sourceType = upper(notification.sourceType());
        if (!SYNCHRONOUS_SOURCE_TYPES.contains(sourceType))
        {
            throw invalid("同步通知来源类型不合法");
        }
        String eventType = upper(notification.eventType());
        if (!eventType.matches("[A-Z][A-Z0-9_]{1,39}"))
        {
            throw invalid("同步通知事件类型不合法");
        }
        String recipientUserId = normalized(notification.recipientUserId(), 19,
                "同步通知接收人不合法");
        if (!recipientUserId.matches("[1-9][0-9]{0,18}"))
        {
            throw invalid("同步通知接收人不合法");
        }
        String taskId = StringUtils.hasText(notification.taskId())
                ? normalized(notification.taskId(), 64, "同步通知任务主键不合法") : null;
        String taskDefinitionKey = StringUtils.hasText(notification.taskDefinitionKey())
                ? normalized(notification.taskDefinitionKey(), 255, "同步通知任务节点不合法") : null;
        return new WorkflowSynchronousNotification(sourceType,
                normalizedSourceId(notification.sourceId()), eventType, recipientUserId,
                normalized(notification.processDefinitionKey(), 255, "同步通知流程定义标识不合法"),
                normalized(notification.processInstanceId(), 64, "同步通知流程实例主键不合法"),
                taskId, taskDefinitionKey,
                normalized(notification.title(), MAX_TITLE_LENGTH, "同步通知标题不合法"),
                normalized(notification.content(), MAX_CONTENT_LENGTH, "同步通知正文不合法"),
                normalized(notification.routePath(), 500, "同步通知业务路由不合法"));
    }

    /**
     * 读取并核对同步 outbox 对应的唯一站内通知，防止幂等重放接受漂移数据。
     *
     * @param outbox SynchronousOutboxFact，已经锁定并核对的同步 outbox 事实
     * @return Long，字段与 outbox 完全一致的统一通知主键
     */
    private Long requireSynchronousInbox(SynchronousOutboxFact outbox)
    {
        List<SynchronousInboxFact> inboxes = jdbcTemplate.query(
                "select inbox.notification_id,inbox.notification_key,inbox.source_type,inbox.source_id," +
                "sha2(concat_ws('|',outbox.source_type,outbox.source_id,outbox.event_type),256) " +
                "as expected_notification_key,inbox.recipient_user_id,inbox.event_type,inbox.title," +
                "inbox.content,inbox.process_instance_id,inbox.task_id,inbox.route_path " +
                "from wf_notification_inbox inbox join wf_notification_outbox outbox " +
                "on outbox.outbox_id=inbox.outbox_id where inbox.outbox_id=? for share",
                (result, rowNum) -> new SynchronousInboxFact(result.getLong("notification_id"),
                        result.getString("notification_key"), result.getString("source_type"),
                        result.getString("source_id"), result.getString("expected_notification_key"),
                        result.getLong("recipient_user_id"), result.getString("event_type"),
                        result.getString("title"), result.getString("content"),
                        result.getString("process_instance_id"), result.getString("task_id"),
                        result.getString("route_path")), outbox.outboxId());
        if (inboxes.size() != 1 || !inboxes.get(0).matches(outbox))
        {
            throw new ServiceException("同步站内通知事实与 outbox 不一致", HttpStatus.ERROR);
        }
        return inboxes.get(0).notificationId();
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

    private record Policy(long policyId, String recipientRules, String channels, String smsTemplateId,
            String titleTemplate, String contentTemplate, int maxAttempts) {}
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
    private record OutboxFact(long outboxId, String idempotencyKey, String sourceType,
            String sourceId, String eventType, String channel,
            long recipientUserId, String processDefinitionKey, String processInstanceId,
            String taskId, String taskDefinitionKey, String routePath)
    {
        /**
         * 核对幂等键关联 outbox 的稳定业务身份。
         * @param idempotency String，本次计算的 SHA-256 幂等键
         * @param expectedSourceId String，当前普通审批业务事件来源键
         * @param context EventContext，当前业务事件冻结上下文
         * @param expectedChannel String，当前投递通道
         * @param recipient String，当前接收用户主键
         * @return boolean，幂等来源对应的事件、接收人和业务路由全部一致时为 true
         */
        private boolean matches(String idempotency, String expectedSourceId,
                EventContext context, String expectedChannel, String recipient)
        {
            return Objects.equals(idempotencyKey, idempotency)
                    && Objects.equals(sourceType, "APPROVAL")
                    && Objects.equals(sourceId, expectedSourceId)
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

    /** SLA 与 BPMN 事件同步写入时锁定的统一 outbox 不可变事实。 */
    private record SynchronousOutboxFact(long outboxId, String idempotencyKey,
            String sourceType, String sourceId, String eventType, String channel,
            long recipientUserId, String processDefinitionKey, String processInstanceId,
            String taskId, String taskDefinitionKey, String title, String content,
            String routePath, String status)
    {
        /**
         * 核对同步通知重放关联的来源、接收人和业务投影。
         *
         * @param expectedIdempotency String，本次计算的 SHA-256 幂等键
         * @param notification WorkflowSynchronousNotification，本次调用冻结的通知事实
         * @return boolean，所有不可变业务字段完全一致时为 true
         */
        private boolean matches(String expectedIdempotency,
                WorkflowSynchronousNotification notification)
        {
            return Objects.equals(idempotencyKey, expectedIdempotency)
                    && Objects.equals(sourceType, notification.sourceType())
                    && Objects.equals(sourceId, notification.sourceId())
                    && Objects.equals(eventType, notification.eventType())
                    && Objects.equals(channel, "INBOX")
                    && recipientUserId == Long.parseLong(notification.recipientUserId())
                    && Objects.equals(processDefinitionKey, notification.processDefinitionKey())
                    && Objects.equals(processInstanceId, notification.processInstanceId())
                    && Objects.equals(taskId, notification.taskId())
                    && Objects.equals(taskDefinitionKey, notification.taskDefinitionKey())
                    && Objects.equals(title, notification.title())
                    && Objects.equals(content, notification.content())
                    && Objects.equals(routePath, notification.routePath());
        }
    }

    /** 同步 outbox 对应的统一 inbox 不可变投影。 */
    private record SynchronousInboxFact(long notificationId, String notificationKey,
            String sourceType, String sourceId, String expectedNotificationKey,
            long recipientUserId, String eventType, String title, String content,
            String processInstanceId, String taskId, String routePath)
    {
        /**
         * 核对同步站内通知与来源 outbox 的业务字段。
         *
         * @param outbox SynchronousOutboxFact，已经确认的来源 outbox
         * @return boolean，站内投影与 outbox 完全一致时为 true
         */
        private boolean matches(SynchronousOutboxFact outbox)
        {
            return Objects.equals(notificationKey, expectedNotificationKey)
                    && Objects.equals(sourceType, outbox.sourceType())
                    && Objects.equals(sourceId, outbox.sourceId())
                    && recipientUserId == outbox.recipientUserId()
                    && Objects.equals(eventType, outbox.eventType())
                    && Objects.equals(title, outbox.title())
                    && Objects.equals(content, outbox.content())
                    && Objects.equals(processInstanceId, outbox.processInstanceId())
                    && Objects.equals(taskId, outbox.taskId())
                    && Objects.equals(routePath, outbox.routePath());
        }
    }
}
