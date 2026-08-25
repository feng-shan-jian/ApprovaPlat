package com.ruoyi.flowable.service.notification;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.config.WorkflowNotificationProperties;
import com.ruoyi.flowable.domain.dto.WorkflowManualUrgeRequest;
import com.ruoyi.flowable.domain.vo.WorkflowManualUrgeView;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.runtime.WorkflowNotificationMetrics;
import com.ruoyi.flowable.runtime.WorkflowRedisAtomicOperations;
import com.ruoyi.framework.web.service.PermissionService;

/**
 * 人工催办业务服务，唯一负责权限、运行状态、任务锁定、冷却频控和登记编排。
 */
@Service
public class WorkflowManualUrgeService
{
    private static final Logger log = LoggerFactory.getLogger(WorkflowManualUrgeService.class);
    private static final int MAX_EXECUTIONS_PER_URGE = 10_000;
    private static final int MAX_TASKS_PER_URGE = 2_000;
    private static final int MAX_RECIPIENTS_PER_EVENT = 2_000;
    private static final String URGE_ANY_PERMISSION = "workflow:notification:urge:any";

    private final JdbcTemplate jdbcTemplate;
    private final WorkflowEngineOperations engineOperations;
    private final PermissionService permissionService;
    private final WorkflowNotificationService notificationService;
    private final WorkflowRedisAtomicOperations redisAtomicOperations;
    private final WorkflowNotificationProperties properties;
    private final WorkflowNotificationMetrics metrics;

    /**
     * 创建人工催办业务服务。
     * @param jdbcTemplate JdbcTemplate，Flowable 运行树、任务和候选关系锁定入口
     * @param engineOperations WorkflowEngineOperations，当前用户写事务入口
     * @param permissionService PermissionService，跨实例催办权限实时复核入口
     * @param notificationService WorkflowNotificationService，策略解析和通知登记入口
     * @param redisAtomicOperations WorkflowRedisAtomicOperations，Redis 原子冷却操作
     * @param properties WorkflowNotificationProperties，催办冷却时长配置
     * @param metrics WorkflowNotificationMetrics，催办固定结果指标
     * @return void，构造后由 Spring 管理
     */
    public WorkflowManualUrgeService(JdbcTemplate jdbcTemplate,
            WorkflowEngineOperations engineOperations, PermissionService permissionService,
            WorkflowNotificationService notificationService,
            WorkflowRedisAtomicOperations redisAtomicOperations,
            WorkflowNotificationProperties properties, WorkflowNotificationMetrics metrics)
    {
        this.jdbcTemplate = jdbcTemplate;
        this.engineOperations = engineOperations;
        this.permissionService = permissionService;
        this.notificationService = notificationService;
        this.redisAtomicOperations = redisAtomicOperations;
        this.properties = properties;
        this.metrics = metrics;
    }

    /**
     * 由流程发起人或具备跨实例权限的管理员催办完整活动流程树。
     * @param request WorkflowManualUrgeRequest，根流程实例和催办原因
     * @return WorkflowManualUrgeView，真实接收人数
     */
    public WorkflowManualUrgeView urge(WorkflowManualUrgeRequest request)
    {
        if (request == null)
        {
            throw new ServiceException("催办请求不能为空", HttpStatus.BAD_REQUEST);
        }
        String processInstanceId = normalized(request.processInstanceId(), 64,
                "流程实例主键不合法");
        String reason = normalized(request.reason(), 500, "催办原因不合法");
        return engineOperations.writeAsCurrentUser(actor ->
        {
            RuntimeProcessSnapshot process = lockRuntimeProcessTree(processInstanceId);
            List<LockedTask> tasks = lockRuntimeTasks(process.processInstanceIds());
            if (!actor.userId().equals(process.startUserId())
                    && !permissionService.hasPermi(URGE_ANY_PERMISSION))
            {
                throw new ServiceException("无权催办当前流程", HttpStatus.FORBIDDEN);
            }
            if (tasks.isEmpty())
            {
                throw new ServiceException("流程没有可催办的活动待办", HttpStatus.CONFLICT);
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

            // urgeEventKey 只用于各任务通知的幂等登记，不再暴露给 HTTP 客户端。
            String urgeEventKey = "URGE:" + UUID.randomUUID();
            LinkedHashSet<String> deliveredRecipients = new LinkedHashSet<>();
            for (Map.Entry<LockedTask, Set<String>> entry : recipientsByTask.entrySet())
            {
                LockedTask task = entry.getKey();
                Set<String> recipientUserIds = notificationService.registerManualUrge(
                        new WorkflowManualUrgeRegistration(task.processDefinitionId(),
                                task.processInstanceId(), process.startUserId(), task.taskId(),
                                task.taskDefinitionKey(), task.taskName(), actor.userId(),
                                entry.getValue(), urgeEventKey + ":" + task.taskId(), reason));
                deliveredRecipients.addAll(recipientUserIds);
            }
            // 成功标准以 Writer 返回的真实可登记接收人为准，INBOX 已不再经过 Outbox。
            if (deliveredRecipients.isEmpty())
            {
                throw new ServiceException("当前催办没有可投递通知", HttpStatus.CONFLICT);
            }
            acquireCooldown(actor.userId(), processInstanceId);
            metrics.recordUrge("accepted");
            return new WorkflowManualUrgeView(deliveredRecipients.size());
        });
    }

    /**
     * 在业务校验和通知通道登记后原子建立催办冷却窗口。
     * @param actorUserId String，当前已授权操作者主键
     * @param processInstanceId String，运行中的根流程实例主键
     * @return void，冷却冲突返回 429，Redis 不可用返回 503
     */
    public void acquireCooldown(String actorUserId, String processInstanceId)
    {
        String key = "workflow:urge:cooldown:" + actorUserId + ":" + processInstanceId;
        WorkflowRedisAtomicOperations.ExpiringSetResult result;
        try
        {
            result = redisAtomicOperations.setIfAbsent(key, properties.getUrgeInterval());
        }
        catch (DataAccessException exception)
        {
            metrics.recordUrge("redis_unavailable");
            log.warn("operation=workflowManualUrge traceId={} source=REDIS " +
                            "processInstanceId={} resultCode=WORKFLOW_REDIS_UNAVAILABLE causeType={}",
                    traceId(), processInstanceId, exception.getClass().getSimpleName());
            throw new ServiceException("催办频控服务暂不可用", HttpStatus.SERVICE_UNAVAILABLE)
                    .setSubCode("WORKFLOW_REDIS_UNAVAILABLE");
        }
        if (!result.acquired())
        {
            metrics.recordUrge("cooldown_rejected");
            log.info("operation=workflowManualUrge traceId={} source=REDIS " +
                            "processInstanceId={} resultCode=WORKFLOW_URGE_COOLDOWN_ACTIVE " +
                            "remainingSeconds={}", traceId(), processInstanceId,
                    result.remainingSeconds());
            throw new ServiceException("催办冷却中，请 " + result.remainingSeconds() + " 秒后重试",
                    HttpStatus.TOO_MANY_REQUESTS)
                    .setSubCode("WORKFLOW_URGE_COOLDOWN_ACTIVE");
        }
    }

    /**
     * 锁定并验证根流程实例和完整活动执行树。
     * @param processInstanceId String，已规范化根流程实例主键
     * @return RuntimeProcessSnapshot，根身份和完整活动实例集合
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
        if (root.suspensionState() != 1)
        {
            throw new ServiceException("挂起流程不能催办", HttpStatus.CONFLICT);
        }

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
     * 锁定完整活动实例树内全部任务。
     * @param processInstanceIds List&lt;String&gt;，根及 CallActivity 子实例主键
     * @return List&lt;LockedTask&gt;，按任务主键排序的活动任务快照
     */
    private List<LockedTask> lockRuntimeTasks(List<String> processInstanceIds)
    {
        if (processInstanceIds == null || processInstanceIds.isEmpty())
        {
            throw new ServiceException("活动流程树实例为空", HttpStatus.ERROR);
        }
        String placeholders = String.join(",",
                processInstanceIds.stream().map(ignored -> "?").toList());
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
     * 使用锁定任务和候选关系的 current-read 快照解析催办接收人。
     * @param task LockedTask，已持有任务行锁的活动任务
     * @return Set&lt;String&gt;，有效办理人或具备认领权限的候选用户
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
     * 校验必填文本长度和控制字符。
     * @param value String，原始文本
     * @param max int，最大字符数
     * @param message String，非法时稳定提示
     * @return String，去除首尾空白后的合法文本
     */
    private String normalized(String value, int max, String message)
    {
        if (!StringUtils.hasText(value))
        {
            throw new ServiceException(message, HttpStatus.BAD_REQUEST);
        }
        String normalized = value.trim();
        if (normalized.length() > max || normalized.chars().anyMatch(Character::isISOControl))
        {
            throw new ServiceException(message, HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    /** @return String，当前 traceId；未配置时为空字符串。 */
    private String traceId()
    {
        String traceId = MDC.get("traceId");
        return traceId == null ? "" : traceId;
    }

    /** 根流程身份和完整活动实例集合的锁定快照。 */
    private record RuntimeProcessSnapshot(String processDefinitionId, int suspensionState,
            String startUserId, List<String> processInstanceIds) { }
    /** 活动 execution 的根关系和挂起态锁定快照。 */
    private record RuntimeExecutionSnapshot(String executionId, String processInstanceId,
            String rootProcessInstanceId, String processDefinitionId, int suspensionState) { }
    /** 活动任务和最终归属的锁定快照。 */
    private record LockedTask(String taskId, String processDefinitionId,
            String processInstanceId, String taskDefinitionKey, String taskName,
            String assignee, int suspensionState) { }
}
