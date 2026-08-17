package com.ruoyi.flowable.service.task;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import org.flowable.bpmn.model.BaseElement;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.Process;
import org.flowable.engine.ManagementService;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.job.api.Job;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfDeployTaskSla;
import com.ruoyi.flowable.domain.WfTaskSlaExecution;
import com.ruoyi.flowable.domain.dto.WorkflowOperationsQuery;
import com.ruoyi.flowable.domain.vo.WorkflowTaskSlaAuditView;
import com.ruoyi.flowable.domain.vo.WorkflowTaskSlaExecutionView;
import com.ruoyi.flowable.domain.vo.WorkflowPageResult;
import com.ruoyi.flowable.domain.vo.WorkflowTaskSlaNotificationView;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.mapper.WfTaskSlaMapper;
import com.ruoyi.flowable.service.support.WorkflowPageSupport;
import com.ruoyi.flowable.service.model.WorkflowBusinessCalendarService;
import com.ruoyi.flowable.service.model.WorkflowDeploymentArtifactRepository;
import com.ruoyi.flowable.service.model.WorkflowTaskSlaDeploymentService;
import com.ruoyi.flowable.service.notification.WorkflowNotificationRegistrar;
import com.ruoyi.flowable.service.notification.WorkflowSynchronousNotification;

/**
 * 审批 SLA 任务生命周期、定时触发、通知、暂停恢复和查询服务。
 */
@Service
public class WorkflowTaskSlaRuntimeService
{
    private final RepositoryService repositoryService;
    private final ManagementService managementService;
    private final ProcessEngineConfiguration processEngineConfiguration;
    private final WorkflowBusinessCalendarService calendarService;
    private final WorkflowEngineOperations engineOperations;
    private final WfTaskSlaMapper slaMapper;
    private final WorkflowDeploymentArtifactRepository artifactRepository;
    private final WorkflowNotificationRegistrar notificationService;

    /**
     * 创建 SLA 运行服务。
     * @param repositoryService RepositoryService，定义和部署关系公共 API
     * @param managementService ManagementService，Flowable timer job 重排公共 API
     * @param processEngineConfiguration ProcessEngineConfiguration，真实引擎时钟
     * @param calendarService WorkflowBusinessCalendarService，业务日历到期计算
     * @param engineOperations WorkflowEngineOperations，查询和当前身份事务边界
     * @param slaMapper WfTaskSlaMapper，运行状态、审计和通知数据访问层
     * @param artifactRepository WorkflowDeploymentArtifactRepository，SLA 部署资源仓库
     * @param notificationService WorkflowNotificationRegistrar，统一 outbox、inbox 和投递审计服务
     * @return 无返回值，构造后由 Spring 管理
     */
    public WorkflowTaskSlaRuntimeService(RepositoryService repositoryService,
            ManagementService managementService,
            ProcessEngineConfiguration processEngineConfiguration,
            WorkflowBusinessCalendarService calendarService,
            WorkflowEngineOperations engineOperations, WfTaskSlaMapper slaMapper,
            WorkflowDeploymentArtifactRepository artifactRepository,
            WorkflowNotificationRegistrar notificationService)
    {
        this.repositoryService = repositoryService;
        this.managementService = managementService;
        this.processEngineConfiguration = processEngineConfiguration;
        this.calendarService = calendarService;
        this.engineOperations = engineOperations;
        this.slaMapper = slaMapper;
        this.artifactRepository = artifactRepository;
        this.notificationService = notificationService;
    }

    /**
     * 在固定 UserTask 监听事务内同步 SLA 生命周期。
     * @param eventName String，create、assignment 或 complete
     * @param taskId String，Flowable 任务主键
     * @param processInstanceId String，流程实例主键
     * @param processDefinitionId String，流程定义主键
     * @param taskDefinitionKey String，审批节点标识
     * @param assignee String，可空当前办理人
     * @return void，无 SLA 快照时不产生运行记录
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void onTaskEvent(String eventName, String taskId, String processInstanceId,
            String processDefinitionId, String taskDefinitionKey, String assignee)
    {
        if ("create".equals(eventName))
        {
            createExecution(taskId, processInstanceId, processDefinitionId,
                    taskDefinitionKey, assignee);
            return;
        }
        WfTaskSlaExecution execution = slaMapper.selectExecutionByTaskForUpdate(taskId);
        if (execution == null && "complete".equals(eventName))
        {
            // 中断升级会创建新任务；其完成事件必须回写原审批节点的 ESCALATED 执行。
            String sourceTaskDefinitionKey = resolveEscalationSourceTaskDefinitionKey(
                    processDefinitionId, taskDefinitionKey);
            if (sourceTaskDefinitionKey != null)
            {
                execution = slaMapper.selectActiveExecutionForUpdate(
                        processInstanceId, sourceTaskDefinitionKey);
                if (execution != null && !"ESCALATED".equals(execution.getStatus()))
                {
                    return;
                }
            }
        }
        if (execution == null)
        {
            return;
        }
        if ("assignment".equals(eventName))
        {
            if (!Objects.equals(execution.getAssigneeUserId(), assignee)
                    && "ACTIVE".equals(execution.getStatus()))
            {
                if (slaMapper.updateAssignee(execution.getSlaExecutionId(), assignee,
                        execution.getRevision()) != 1)
                {
                    throw conflict();
                }
                requireAudit(execution.getSlaExecutionId(), "ASSIGN",
                        execution.getRevision() + 1, assignee, "审批 SLA 办理人变更");
            }
            return;
        }
        if ("complete".equals(eventName)
                && !"COMPLETED".equals(execution.getStatus()))
        {
            if (slaMapper.completeExecution(execution.getSlaExecutionId(),
                    execution.getRevision()) != 1)
            {
                throw conflict();
            }
            requireAudit(execution.getSlaExecutionId(), "COMPLETE", 0,
                    assignee, "审批任务按业务路径完成");
        }
    }

    /**
     * 从部署后的 BPMN 读取生成升级任务冻结的原节点标识。
     * @param processDefinitionId String，当前流程定义主键
     * @param generatedTaskDefinitionKey String，完成事件对应的生成任务节点标识
     * @return String，原审批节点标识；普通任务或属性缺失时返回 null
     */
    private String resolveEscalationSourceTaskDefinitionKey(String processDefinitionId,
            String generatedTaskDefinitionKey)
    {
        BpmnModel model = repositoryService.getBpmnModel(processDefinitionId);
        if (model == null || generatedTaskDefinitionKey == null)
        {
            return null;
        }
        for (Process process : model.getProcesses())
        {
            FlowElement element = process.getFlowElement(generatedTaskDefinitionKey, true);
            String source = readExtensionProperty(element,
                    WorkflowTaskSlaDeploymentService.SOURCE_TASK_DEFINITION_KEY_PROPERTY);
            if (source != null && !source.isBlank())
            {
                return source;
            }
        }
        return null;
    }

    /**
     * 读取一个 Flowable properties 属性值。
     * @param element BaseElement，部署后 BPMN 元素
     * @param propertyName String，精确平台属性名
     * @return String，属性值；元素或属性不存在时返回 null
     */
    private String readExtensionProperty(BaseElement element, String propertyName)
    {
        if (element == null || element.getExtensionElements() == null)
        {
            return null;
        }
        for (ExtensionElement container : element.getExtensionElements()
                .getOrDefault("properties", List.of()))
        {
            for (ExtensionElement property : container.getChildElements()
                    .getOrDefault("property", List.of()))
            {
                if (propertyName.equals(property.getAttributeValue(null, "name")))
                {
                    return property.getAttributeValue(null, "value");
                }
            }
        }
        return null;
    }

    /**
     * 在 Flowable 定时作业事务内执行一次提醒或升级，业务写入失败会连同作业命令回滚并重试。
     * @param processInstanceId String，流程实例主键
     * @param processDefinitionId String，流程定义主键
     * @param taskDefinitionKey String，原审批节点标识
     * @param action String，REMINDER 或 ESCALATE
     * @param ordinal int，提醒从 1 开始，升级固定为 0
     * @param escalationRecipient String，可空冻结升级办理人
     * @return void，重复触发按审计唯一键和状态条件幂等返回
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void handleTimer(String processInstanceId, String processDefinitionId,
            String taskDefinitionKey, String action, int ordinal,
            String escalationRecipient)
    {
        WfTaskSlaExecution execution = slaMapper.selectActiveExecutionForUpdate(
                processInstanceId, taskDefinitionKey);
        if (execution == null)
        {
            // 任务在定时命令获取后已完成时，旧作业不得重新制造通知或审计副作用。
            return;
        }
        if (!"ACTIVE".equals(execution.getStatus()))
        {
            // 中断升级可能与已经获取的非中断提醒并发；升级提交后，晚到提醒必须幂等丢弃。
            return;
        }
        ProcessDefinition definition = requireDefinition(processDefinitionId);
        WfDeployTaskSla snapshot = requireSnapshot(execution, definition);
        if ("REMINDER".equals(action))
        {
            if (ordinal < 1 || ordinal > snapshot.getMaxReminders())
            {
                throw dataError("审批 SLA 提醒序号异常");
            }
            if (execution.getRemindersSent() >= ordinal)
            {
                return;
            }
            if (slaMapper.markReminder(execution.getSlaExecutionId(), ordinal,
                    execution.getRevision()) != 1)
            {
                throw conflict();
            }
            Long auditId = requireAudit(execution.getSlaExecutionId(), "REMINDER",
                    ordinal, null, "审批 SLA 自动催办第 " + ordinal + " 次");
            String recipient = execution.getAssigneeUserId() == null
                    ? snapshot.getEscalationAssignee() : execution.getAssigneeUserId();
            requireNotification(auditId, execution, definition.getKey(), recipient,
                    "REMINDER", "审批任务超时提醒",
                    "任务 " + execution.getTaskDefinitionKey() + " 已达到第 " + ordinal
                            + " 次 SLA 提醒时间");
            return;
        }
        if (!"ESCALATE".equals(action) || ordinal != 0)
        {
            throw dataError("审批 SLA 定时动作异常");
        }
        if ("ESCALATED".equals(execution.getStatus()))
        {
            return;
        }
        if (slaMapper.markEscalated(execution.getSlaExecutionId(),
                execution.getRevision()) != 1)
        {
            throw conflict();
        }
        String eventDetail = snapshot.getEscalationEventCode() == null ? ""
                : "，受控升级编码 " + snapshot.getEscalationEventCode();
        Long auditId = requireAudit(execution.getSlaExecutionId(), "ESCALATE", 0,
                null, "审批 SLA 已超时升级" + eventDetail);
        String recipient = escalationRecipient == null
                ? execution.getAssigneeUserId() : escalationRecipient;
        requireNotification(auditId, execution, definition.getKey(), recipient,
                "ESCALATE", "审批任务超时升级",
                "任务 " + execution.getTaskDefinitionKey() + " 已进入超时升级处理");
    }

    /**
     * 在实例挂起事务内冻结全部活动 SLA 时钟并写审计。
     * @param processInstanceId String，流程实例主键
     * @param actorUserId String，管理员操作人用户主键
     * @return void，无活动 SLA 时按幂等成功处理
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void pauseInstance(String processInstanceId, String actorUserId)
    {
        List<WfTaskSlaExecution> executions = slaMapper
                .selectActiveExecutionsForInstanceForUpdate(processInstanceId).stream()
                .filter(execution -> execution.getPausedAt() == null).toList();
        if (executions.isEmpty())
        {
            return;
        }
        LocalDateTime pausedAt = nowUtc();
        for (WfTaskSlaExecution execution : executions)
        {
            requireAudit(execution.getSlaExecutionId(), "PAUSE",
                    execution.getRevision() + 1, actorUserId, "流程挂起并冻结 SLA 时钟");
        }
        if (slaMapper.pauseInstance(processInstanceId, pausedAt) != executions.size())
        {
            throw conflict();
        }
    }

    /**
     * 在实例激活事务内平移真实 Flowable timer job 与正式 SLA 到期时间。
     * @param processInstanceId String，流程实例主键
     * @param actorUserId String，管理员操作人用户主键
     * @return void，无暂停 SLA 时按幂等成功处理
     */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void resumeInstance(String processInstanceId, String actorUserId)
    {
        List<WfTaskSlaExecution> executions = slaMapper
                .selectActiveExecutionsForInstanceForUpdate(processInstanceId).stream()
                .filter(execution -> execution.getPausedAt() != null).toList();
        if (executions.isEmpty())
        {
            return;
        }
        LocalDateTime resumedAt = nowUtc();
        long pauseMillis = Duration.between(executions.get(0).getPausedAt(), resumedAt).toMillis();
        if (pauseMillis < 0 || executions.stream().anyMatch(execution ->
                Duration.between(execution.getPausedAt(), resumedAt).toMillis() != pauseMillis))
        {
            throw dataError("审批 SLA 暂停时钟不一致");
        }
        List<Job> jobs = managementService.createTimerJobQuery()
                .processInstanceId(processInstanceId).list().stream()
                .filter(job -> job.getElementId() != null
                        && job.getElementId().startsWith(
                                WorkflowTaskSlaDeploymentService.TIMER_ELEMENT_PREFIX))
                .toList();
        for (Job job : jobs)
        {
            if (job.getDuedate() == null)
            {
                throw dataError("审批 SLA 定时作业到期时间缺失");
            }
            Instant shifted = job.getDuedate().toInstant().plusMillis(pauseMillis);
            managementService.rescheduleTimeDateJob(job.getId(),
                    DateTimeFormatter.ISO_INSTANT.format(shifted));
        }
        for (WfTaskSlaExecution execution : executions)
        {
            requireAudit(execution.getSlaExecutionId(), "RESUME",
                    execution.getRevision() + 1, actorUserId,
                    "流程恢复并平移 SLA 时钟 " + pauseMillis + " 毫秒");
        }
        if (slaMapper.resumeInstance(processInstanceId, resumedAt) != executions.size())
        {
            throw conflict();
        }
    }

    /**
     * 分页查询 SLA 当前执行状态。
     * @param query SlaExecution，状态、关键字和开始时间范围
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，每页记录数，最大 100
     * @return WorkflowPageResult&lt;WorkflowTaskSlaExecutionView&gt;，当前页和符合条件的总数
     */
    public WorkflowPageResult<WorkflowTaskSlaExecutionView> listExecutions(
            WorkflowOperationsQuery.SlaExecution query, int pageNum, int pageSize)
    {
        WorkflowPageSupport.requireTimeRange(query.beginTime(), query.endTime());
        return engineOperations.read(() -> WorkflowPageSupport.query(pageNum, pageSize,
                () -> slaMapper.countExecutions(query),
                (offset, size) -> slaMapper.selectExecutions(query, offset, size)));
    }

    /**
     * 分页查询 SLA 不可变生命周期审计。
     * @param query SlaAudit，动作、关键字和动作时间范围
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，每页记录数，最大 100
     * @return WorkflowPageResult&lt;WorkflowTaskSlaAuditView&gt;，当前页和符合条件的总数
     */
    public WorkflowPageResult<WorkflowTaskSlaAuditView> listAudits(
            WorkflowOperationsQuery.SlaAudit query, int pageNum, int pageSize)
    {
        WorkflowPageSupport.requireTimeRange(query.beginTime(), query.endTime());
        return engineOperations.read(() -> WorkflowPageSupport.query(pageNum, pageSize,
                () -> slaMapper.countAudits(query),
                (offset, size) -> slaMapper.selectAudits(query, offset, size)));
    }

    /** @return List&lt;WorkflowTaskSlaNotificationView&gt;，当前用户通知。 */
    public List<WorkflowTaskSlaNotificationView> myNotifications()
    {
        return engineOperations.read(() -> List.copyOf(slaMapper.selectNotifications(
                com.ruoyi.common.utils.SecurityUtils.getUserId().toString())));
    }

    /** @param notificationId Long，通知主键；@return void，仅当前接收人可首次标记已读。 */
    public void markNotificationRead(Long notificationId)
    {
        if (notificationId == null || notificationId <= 0)
        {
            throw new ServiceException("审批 SLA 通知主键不合法", HttpStatus.BAD_REQUEST);
        }
        engineOperations.writeAsCurrentUser(identity ->
        {
            if (slaMapper.markNotificationRead(notificationId, identity.userId()) != 1)
            {
                throw new ServiceException("审批 SLA 通知不存在或已处理", HttpStatus.NOT_FOUND);
            }
            return null;
        });
    }

    /**
     * 创建任务 SLA 执行和 CREATE 审计。
     * @param taskId String，任务主键
     * @param processInstanceId String，实例主键
     * @param processDefinitionId String，定义主键
     * @param taskDefinitionKey String，审批节点标识
     * @param assignee String，可空办理人
     * @return void，部署没有 SLA 快照时直接返回
     */
    private void createExecution(String taskId, String processInstanceId,
            String processDefinitionId, String taskDefinitionKey, String assignee)
    {
        ProcessDefinition definition = requireDefinition(processDefinitionId);
        WfDeployTaskSla snapshot = artifactRepository.selectTaskSlaSnapshot(
                definition.getDeploymentId(), definition.getKey(), taskDefinitionKey);
        if (snapshot == null)
        {
            return;
        }
        Instant start = currentInstant();
        WfTaskSlaExecution execution = new WfTaskSlaExecution();
        execution.setDeploymentId(definition.getDeploymentId());
        execution.setProcessInstanceId(processInstanceId);
        execution.setProcessDefinitionId(processDefinitionId);
        execution.setTaskId(taskId);
        execution.setTaskDefinitionKey(taskDefinitionKey);
        execution.setAssigneeUserId(assignee);
        execution.setStartedAt(LocalDateTime.ofInstant(start, ZoneOffset.UTC));
        execution.setReminderDueAt(LocalDateTime.ofInstant(calendarService.resolveDueAt(
                snapshot.getCalendarKey(), start, snapshot.getReminderMinutes()), ZoneOffset.UTC));
        execution.setEscalationDueAt(LocalDateTime.ofInstant(calendarService.resolveDueAt(
                snapshot.getCalendarKey(), start, snapshot.getEscalationMinutes()), ZoneOffset.UTC));
        int inserted = slaMapper.insertExecution(execution);
        WfTaskSlaExecution stored = slaMapper.selectExecutionByTaskForUpdate(taskId);
        if (stored == null)
        {
            throw dataError("审批 SLA 执行保存不完整");
        }
        if (inserted == 1)
        {
            requireAudit(stored.getSlaExecutionId(), "CREATE", 0, assignee,
                    "审批 SLA 时钟启动");
        }
    }

    /**
     * 校验 SLA 执行与真实流程定义属于同一部署，并读取精确资源快照。
     *
     * @param execution WfTaskSlaExecution，当前锁定的 SLA 运行执行
     * @param definition ProcessDefinition，已经校验字段完整的真实流程定义
     * @return WfDeployTaskSla，与部署、流程和任务节点完全匹配的 SLA 快照
     */
    private WfDeployTaskSla requireSnapshot(WfTaskSlaExecution execution,
            ProcessDefinition definition)
    {
        if (!execution.getDeploymentId().equals(definition.getDeploymentId()))
        {
            throw dataError("审批 SLA 执行与部署不一致");
        }
        WfDeployTaskSla snapshot = artifactRepository.selectTaskSlaSnapshot(
                definition.getDeploymentId(), definition.getKey(),
                execution.getTaskDefinitionKey());
        if (snapshot == null)
        {
            throw dataError("审批 SLA 部署快照不存在");
        }
        return snapshot;
    }

    /** @param processDefinitionId String，定义主键；@return ProcessDefinition，字段完整定义。 */
    private ProcessDefinition requireDefinition(String processDefinitionId)
    {
        ProcessDefinition definition = repositoryService.getProcessDefinition(processDefinitionId);
        if (definition == null || definition.getDeploymentId() == null || definition.getKey() == null)
        {
            throw dataError("审批 SLA 流程定义不存在");
        }
        return definition;
    }

    /** @param executionId Long，执行主键；@param action String，动作；@param ordinal int，序号；@param actor String，可空操作人；@param detail String，摘要；@return Long，稳定审计主键。 */
    private Long requireAudit(Long executionId, String action, int ordinal,
            String actor, String detail)
    {
        slaMapper.insertAudit(executionId, action, ordinal, actor, detail);
        Long auditId = slaMapper.selectAuditId(executionId, action, ordinal);
        if (auditId == null)
        {
            throw dataError("审批 SLA 审计保存不完整");
        }
        return auditId;
    }

    /**
     * 将 SLA 提醒或升级同步发布到统一通知模型，通知无效时回滚 SLA 状态和审计。
     *
     * @param auditId Long，已提交到当前事务的 SLA 审计主键
     * @param execution WfTaskSlaExecution，通知关联的 SLA 任务运行事实
     * @param processDefinitionKey String，部署时冻结的流程定义 key
     * @param recipient String，接收人正式用户主键
     * @param action String，REMINDER 或 ESCALATE
     * @param title String，通知标题
     * @param content String，脱敏通知正文
     * @return void，统一 outbox、inbox 或审计任一未完整写入时抛出异常
     */
    private void requireNotification(Long auditId, WfTaskSlaExecution execution,
            String processDefinitionKey, String recipient, String action,
            String title, String content)
    {
        if (recipient == null || recipient.isBlank())
        {
            // 通知是提醒和升级成功状态的一部分，接收人失效时不能提交部分业务状态。
            throw dataError("审批 SLA 通知接收人无效");
        }
        String routePath = "/workflow/process-detail/" + execution.getProcessInstanceId()
                + "?source=todo&taskId=" + execution.getTaskId();
        Long notificationId = notificationService.publishSynchronousInbox(
                new WorkflowSynchronousNotification("SLA", String.valueOf(auditId), action,
                        recipient, processDefinitionKey, execution.getProcessInstanceId(),
                        execution.getTaskId(), execution.getTaskDefinitionKey(), title, content,
                        routePath));
        if (notificationId == null)
        {
            // SLA 成功事实要求真实有效接收人，不能只提交超时状态而静默丢失通知。
            throw dataError("审批 SLA 通知接收人无效");
        }
    }

    /** @return Instant，Flowable 当前可测试引擎时钟。 */
    private Instant currentInstant()
    {
        Date now = processEngineConfiguration.getClock().getCurrentTime();
        return now == null ? Instant.now() : now.toInstant();
    }

    /** @return LocalDateTime，UTC 引擎当前时间。 */
    private LocalDateTime nowUtc()
    {
        return LocalDateTime.ofInstant(currentInstant(), ZoneOffset.UTC);
    }

    /** @return ServiceException，HTTP 409 并发状态漂移。 */
    private ServiceException conflict()
    {
        return new ServiceException("审批 SLA 状态已发生变化，请刷新后重试", HttpStatus.CONFLICT);
    }

    /** @param message String，稳定提示；@return ServiceException，HTTP 500 数据一致性异常。 */
    private ServiceException dataError(String message)
    {
        return new ServiceException(message, HttpStatus.ERROR);
    }
}
