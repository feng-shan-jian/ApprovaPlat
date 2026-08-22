package com.ruoyi.flowable.service.notification;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.task.api.Task;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfCopy;
import com.ruoyi.flowable.mapper.WfCopyMapper;

/**
 * 工作流通知业务入口，负责冻结 Flowable 事实并协调 Planner 与 Writer。
 *
 * <p>本服务不直接写通知表：普通事件由 Planner 规划后交给 Writer 在当前事务内
 * 同时写站内信和外部投递 Outbox；抄送按自然键批量读取数据库 active 事实后再规划。</p>
 */
@Service
public class WorkflowNotificationService
{
    private final JdbcTemplate jdbcTemplate;
    private final RepositoryService repositoryService;
    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final WorkflowNotificationPlanner planner;
    private final WorkflowNotificationWriter writer;
    private final WorkflowNotificationOutboxService outboxService;
    private final WfCopyMapper copyMapper;

    /**
     * 创建工作流通知业务服务。
     * @param jdbcTemplate JdbcTemplate，读取 Flowable 监听审计序号和任务 revision
     * @param repositoryService RepositoryService，流程定义上下文查询入口
     * @param runtimeService RuntimeService，受控迁移标记查询入口
     * @param taskService TaskService，活动任务上下文查询入口
     * @param planner WorkflowNotificationPlanner，策略和身份规划入口
     * @param writer WorkflowNotificationWriter，通知事务写入入口
     * @param outboxService WorkflowNotificationOutboxService，催办外部记录取消入口
     * @param copyMapper WfCopyMapper，按抄送自然键批量读取 active 正式事实
     * @return void，构造后由 Spring 管理
     */
    public WorkflowNotificationService(JdbcTemplate jdbcTemplate,
            RepositoryService repositoryService, RuntimeService runtimeService,
            TaskService taskService, WorkflowNotificationPlanner planner,
            WorkflowNotificationWriter writer,
            WorkflowNotificationOutboxService outboxService, WfCopyMapper copyMapper)
    {
        this.jdbcTemplate = jdbcTemplate;
        this.repositoryService = repositoryService;
        this.runtimeService = runtimeService;
        this.taskService = taskService;
        this.planner = planner;
        this.writer = writer;
        this.outboxService = outboxService;
        this.copyMapper = copyMapper;
    }

    /**
     * 在 userTaskListener 当前 Flowable 命令事务中登记任务事件。
     * @param flowableEvent String，create、assignment 或 complete
     * @param taskId String，任务主键
     * @param processInstanceId String，流程实例主键
     * @param processDefinitionId String，流程定义主键
     * @param taskDefinitionKey String，BPMN 节点 key
     * @param taskName String，任务显示名称
     * @param assignee String，当前办理人
     * @return int，本次登记的通知通道记录数
     */
    public int onTaskEvent(String flowableEvent, String taskId, String processInstanceId,
            String processDefinitionId, String taskDefinitionKey, String taskName,
            String assignee)
    {
        requireWriteTransaction();
        if ("assignment".equals(flowableEvent))
        {
            // assignment 可能只是 Flowable 初始化中间态，稳定认领通知由任务动作服务显式登记。
            return 0;
        }
        Object controlledTransition = runtimeService.getVariable(processInstanceId,
                WorkflowNotificationConstants.CONTROLLED_TRANSITION_VARIABLE);
        if (controlledTransition != null)
        {
            if (!(controlledTransition instanceof String transition)
                    || !Set.of("RETURN", "RESUBMIT").contains(transition))
            {
                throw new ServiceException("通知受控迁移标记异常", HttpStatus.ERROR);
            }
            // 退回/重提的执行树恢复完成前，禁止使用中间归属生成通知。
            return 0;
        }
        String eventType = classifyTaskEvent(flowableEvent);
        if ("TASK_COMPLETED".equals(eventType))
        {
            // 任务动作与催办取消保持原有锁序，避免业务状态和外部记录出现半提交。
            outboxService.schedulePendingUrgeCancellation(processInstanceId, taskId,
                    "任务已完成，取消未投递催办");
        }
        Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
        Set<String> fallbackRecipients = task == null && StringUtils.hasText(assignee)
                ? Set.of(assignee) : Set.of();
        Integer ordinal = jdbcTemplate.queryForObject(
                "select count(*) from ACT_HI_COMMENT where TASK_ID_=? and TYPE_='USER_TASK_LISTENER'",
                Integer.class, taskId);
        String sourceEventKey = "TASK_ARRIVED".equals(eventType)
                ? "TASK:" + taskId + ":ARRIVED"
                : "TASK:" + taskId + ":" + flowableEvent + ":" + (ordinal == null ? 0 : ordinal);
        WorkflowNotificationPlanner.NotificationRequest request = requestForTask(eventType,
                sourceEventKey, processDefinitionId, processInstanceId, taskId,
                taskDefinitionKey, taskName, task, fallbackRecipients, null, true, null, true,
                route(processInstanceId, taskId), null);
        return writer.write(planner.plan(request)).channelRecordCount();
    }

    /**
     * 在受控退回或重提完成归属恢复后登记稳定任务通知。
     * @param eventType String，仅允许 TASK_RETURNED 或 TASK_RESUBMITTED
     * @param task Task，归属恢复后的真实活动任务
     * @return int，本次登记的通知通道记录数
     */
    public int onStableTaskEvent(String eventType, Task task)
    {
        requireWriteTransaction();
        if (task == null || !Set.of("TASK_RETURNED", "TASK_RESUBMITTED").contains(eventType))
            throw invalid("稳定任务通知事件不合法");
        Task stableTask = taskService.createTaskQuery().taskId(task.getId()).active().singleResult();
        if (stableTask == null) throw new ServiceException("稳定任务不存在", HttpStatus.CONFLICT);
        WorkflowNotificationPlanner.NotificationRequest request = requestForTask(eventType,
                "TASK:" + stableTask.getId() + ":" + eventType,
                stableTask.getProcessDefinitionId(), stableTask.getProcessInstanceId(),
                stableTask.getId(), stableTask.getTaskDefinitionKey(), stableTask.getName(),
                stableTask, Set.of(), null, true, null, true,
                route(stableTask.getProcessInstanceId(), stableTask.getId()), null);
        return writer.write(planner.plan(request)).channelRecordCount();
    }

    /**
     * 在认领、释放、委派、归还或转办完成后登记稳定任务动作通知。
     * @param eventType String，TASK_CLAIMED、TASK_UNCLAIMED、TASK_DELEGATED、TASK_DELEGATION_RESOLVED 或 TASK_TRANSFERRED
     * @param taskId String，活动任务主键
     * @return int，本次登记的通知通道记录数
     */
    public int onStableTaskAction(String eventType, String taskId)
    {
        requireWriteTransaction();
        if (!Set.of("TASK_CLAIMED", "TASK_UNCLAIMED", "TASK_DELEGATED",
                "TASK_DELEGATION_RESOLVED", "TASK_TRANSFERRED").contains(eventType))
            throw invalid("稳定任务动作通知事件不合法");
        String normalizedTaskId = normalized(taskId, 64, "任务主键不合法");
        Task stableTask = taskService.createTaskQuery().taskId(normalizedTaskId).active().singleResult();
        if (stableTask == null) throw new ServiceException("任务归属状态已变化", HttpStatus.CONFLICT);
        Integer revision = jdbcTemplate.queryForObject(
                "select REV_ from ACT_RU_TASK where ID_=?", Integer.class, normalizedTaskId);
        if (revision == null || revision < 1)
            throw new ServiceException("任务归属版本不存在", HttpStatus.ERROR);
        outboxService.cancelPendingUrges(stableTask.getProcessInstanceId(), stableTask.getId(),
                "任务办理关系已变化，取消旧接收人催办");
        WorkflowNotificationPlanner.NotificationRequest request = requestForTask(eventType,
                "TASK:" + stableTask.getId() + ":" + eventType + ":r" + revision,
                stableTask.getProcessDefinitionId(), stableTask.getProcessInstanceId(),
                stableTask.getId(), stableTask.getTaskDefinitionKey(), stableTask.getName(),
                stableTask, Set.of(), null, true, null, true,
                route(stableTask.getProcessInstanceId(), stableTask.getId()), null);
        return writer.write(planner.plan(request)).channelRecordCount();
    }

    /**
     * 按当前事务传入抄送的自然键批量读取 active 正式事实并登记通知。
     * @param copies Collection&lt;WfCopy&gt;，仅提供 copy_event_id 和 user_id 查询键
     * @return int，本次登记的通知通道记录数
     */
    public int onCopiesCreated(Collection<WfCopy> copies)
    {
        requireWriteTransaction();
        if (copies == null) throw new ServiceException("抄送通知事实不能为空", HttpStatus.ERROR);
        if (copies.isEmpty()) return 0;
        // 幂等重放可能拿到已经逻辑删除的旧事实；单次集合查询只保留当前有效自然键，绝不恢复或阻断主业务。
        List<WfCopy> queryKeys = copies.stream().filter(copy -> copy != null
                && StringUtils.hasText(copy.getCopyEventId()) && copy.getUserId() != null).toList();
        if (queryKeys.isEmpty()) return 0;
        copies = copyMapper.selectActiveByNaturalKeys(queryKeys);
        if (copies == null || copies.isEmpty()) return 0;
        LinkedHashMap<String, ProcessDefinition> definitions = new LinkedHashMap<>();
        for (WfCopy copy : copies)
        {
            if (copy == null || !StringUtils.hasText(copy.getProcessId()))
                throw new ServiceException("抄送流程定义主键不完整", HttpStatus.ERROR);
            String processDefinitionId = normalized(copy.getProcessId(), 64,
                    "抄送流程定义主键不合法");
            if (!definitions.containsKey(processDefinitionId))
            {
                // 即使定义不存在也冻结一次查询结果，避免同一无效主键被批次内重复读取。
                definitions.put(processDefinitionId,
                        repositoryService.getProcessDefinition(processDefinitionId));
            }
        }
        for (Map.Entry<String, ProcessDefinition> entry : definitions.entrySet())
        {
            if (entry.getValue() == null)
                throw new ServiceException("抄送流程定义不存在", HttpStatus.ERROR);
        }
        return writer.write(planner.planCopies(copies, definitions)).channelRecordCount();
    }

    /**
     * 在流程自然完成或显式终止事务内登记流程结果通知。
     * @param eventType String，PROCESS_COMPLETED、PROCESS_CANCELED、PROCESS_REJECTED 或 PROCESS_TERMINATED
     * @param processDefinitionId String，流程定义主键
     * @param processInstanceId String，根流程实例主键
     * @return int，本次登记的通知通道记录数
     */
    public int onProcessResult(String eventType, String processDefinitionId,
            String processInstanceId)
    {
        requireWriteTransaction();
        if (!Set.of("PROCESS_COMPLETED", "PROCESS_CANCELED", "PROCESS_REJECTED",
                "PROCESS_TERMINATED").contains(eventType))
            throw invalid("流程通知事件不受支持");
        outboxService.schedulePendingUrgeCancellation(processInstanceId, null,
                "流程已结束，取消未投递催办");
        WorkflowNotificationPlanner.NotificationRequest request = requestForTask(eventType,
                "PROCESS:" + processInstanceId + ":" + eventType, processDefinitionId,
                processInstanceId, null, null, null, null, Set.of(), null, true, null, true,
                route(processInstanceId, null), null);
        return writer.write(planner.plan(request)).channelRecordCount();
    }

    /**
     * 根据人工催办服务已锁定的任务事实登记一次最终正文通知。
     * @param registration WorkflowManualUrgeRegistration，权限和接收人冻结后的催办命令
     * @return WorkflowManualUrgeRegistrationResult，实际可登记接收人
     */
    public WorkflowManualUrgeRegistrationResult registerManualUrge(
            WorkflowManualUrgeRegistration registration)
    {
        requireWriteTransaction();
        if (registration == null || registration.recipientUserIds() == null
                || registration.recipientUserIds().isEmpty())
            throw invalid("催办登记事实不完整");
        String processDefinitionId = normalized(registration.processDefinitionId(), 64,
                "流程定义主键不合法");
        String processInstanceId = normalized(registration.processInstanceId(), 64,
                "流程实例主键不合法");
        String taskId = normalized(registration.taskId(), 64, "任务主键不合法");
        String reason = normalized(registration.reason(), 500, "催办原因不合法");
        WorkflowNotificationPlanner.NotificationRequest request = requestForTask(
                "MANUAL_URGE", normalizedSourceId(registration.sourceId()), processDefinitionId,
                processInstanceId, taskId, optional(registration.taskDefinitionKey(), 255),
                optional(registration.taskName(), 255), null,
                Collections.unmodifiableSet(new LinkedHashSet<>(registration.recipientUserIds())),
                registration.actorUserId(), false,
                registration.startUserId(), false, route(processInstanceId, taskId),
                "\n催办原因：" + reason);
        WorkflowNotificationWriter.WriteResult result = writer.write(planner.plan(request));
        return new WorkflowManualUrgeRegistrationResult(result.recipientUserIds());
    }

    /**
     * 构造带流程定义名称和业务路由的规划请求。
     * @param eventType String，通知事件类型
     * @param sourceId String，稳定来源键
     * @param processDefinitionId String，流程定义主键
     * @param processInstanceId String，流程实例主键
     * @param taskId String，可空任务主键
     * @param taskDefinitionKey String，可空节点 key
     * @param taskName String，可空任务名称
     * @param task Task，可空真实任务
     * @param taskRecipientUserIds Set&lt;String&gt;，无 Task 时的冻结任务接收人
     * @param actorUserId String，可空操作者
     * @param resolveActor boolean，是否从 Flowable 认证解析操作者
     * @param initiatorUserId String，可空发起人
     * @param resolveInitiator boolean，是否从流程实例解析发起人
     * @param routePath String，业务详情路由
     * @param contentSuffix String，可空正文后缀
     * @return WorkflowNotificationPlanner.NotificationRequest，冻结的规划上下文
     */
    private WorkflowNotificationPlanner.NotificationRequest requestForTask(String eventType,
            String sourceId, String processDefinitionId, String processInstanceId,
            String taskId, String taskDefinitionKey, String taskName, Task task,
            Set<String> taskRecipientUserIds, String actorUserId, boolean resolveActor,
            String initiatorUserId, boolean resolveInitiator, String routePath,
            String contentSuffix)
    {
        String definitionId = normalized(processDefinitionId, 64, "流程定义主键不合法");
        ProcessDefinition definition = repositoryService.getProcessDefinition(definitionId);
        if (definition == null) throw new ServiceException("流程定义不存在", HttpStatus.ERROR);
        return new WorkflowNotificationPlanner.NotificationRequest(eventType, sourceId,
                definition.getKey(), definition.getName(), processInstanceId, taskId,
                taskDefinitionKey, taskName, task, taskRecipientUserIds, actorUserId,
                resolveActor, initiatorUserId, resolveInitiator, routePath, contentSuffix);
    }

    /**
     * 将任务或流程实例转换为站内安全相对路由。
     * @param processInstanceId String，流程实例主键
     * @param taskId String，可空任务主键
     * @return String，流程详情相对路由
     */
    private String route(String processInstanceId, String taskId)
    {
        String source = taskId == null ? "own" : "todo";
        return "/workflow/process-detail/" + processInstanceId + "?source=" + source
                + (taskId == null ? "" : "&taskId=" + taskId);
    }

    /**
     * 将底层 Flowable 监听事件转换为稳定业务事件。
     * @param event String，Flowable create 或 complete
     * @return String，TASK_ARRIVED 或 TASK_COMPLETED
     */
    private String classifyTaskEvent(String event)
    {
        if ("create".equals(event)) return "TASK_ARRIVED";
        if ("complete".equals(event)) return "TASK_COMPLETED";
        throw invalid("任务通知事件不受支持");
    }

    /**
     * 规范化必填文本并拒绝控制字符或超长值。
     * @param value String，待校验文本
     * @param max int，最大字符长度
     * @param message String，稳定错误提示
     * @return String，去除首尾空白的合法文本
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
     * @param max int，最大字符长度
     * @return String，规范值；无内容时为 null
     */
    private String optional(String value, int max)
    {
        return StringUtils.hasText(value) ? normalized(value, max, "通知上下文字段不合法") : null;
    }

    /**
     * 规范化普通审批事件的稳定来源键并限制为可见 ASCII。
     * @param value String，业务事件来源键
     * @return String，可写入通知来源字段的规范键
     */
    private String normalizedSourceId(String value)
    {
        String sourceId = normalized(value, 191, "通知来源标识不合法");
        if (sourceId.chars().anyMatch(character -> character < 0x21 || character > 0x7e))
            throw invalid("通知来源标识不合法");
        return sourceId;
    }

    /**
     * 要求通知入口处于调用方当前可写事务。
     * @return void，缺少事务或当前事务只读时抛出服务端异常
     */
    private void requireWriteTransaction()
    {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new ServiceException("通知必须在 Flowable 写事务中登记", HttpStatus.ERROR);
    }

    /**
     * 构造参数错误异常。
     * @param message String，稳定错误提示
     * @return ServiceException，HTTP 400 业务异常
     */
    private ServiceException invalid(String message)
    {
        return new ServiceException(message, HttpStatus.BAD_REQUEST);
    }
}
