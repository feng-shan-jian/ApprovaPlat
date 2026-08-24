package com.ruoyi.flowable.service.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Set;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.HistoryService;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.flowable.identitylink.api.IdentityLink;
import org.springframework.jdbc.core.JdbcTemplate;
import com.ruoyi.flowable.domain.WfAttachment;
import com.ruoyi.flowable.domain.WfMultiInstanceRound;
import com.ruoyi.flowable.mapper.WfAttachmentMapper;
import com.ruoyi.flowable.mapper.WfMultiInstanceRoundMapper;
import com.ruoyi.flowable.service.process.WorkflowProcessStartService;

/**
 * 只读取任务、execution、轮次、附件、SLA 和审计事实，不执行任何业务写入。
 */
final class WorkflowMultiInstanceStateProbe
{
    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final HistoryService historyService;
    private final JdbcTemplate jdbcTemplate;
    private final WfMultiInstanceRoundMapper roundMapper;
    private final WfAttachmentMapper attachmentMapper;

    /**
     * 创建只读状态探针。
     *
     * @param runtimeService RuntimeService，运行实例与 execution 查询
     * @param taskService TaskService，任务、意见和局部变量查询
     * @param historyService HistoryService，历史实例查询
     * @param jdbcTemplate JdbcTemplate，跨表只读快照查询
     * @param roundMapper WfMultiInstanceRoundMapper，正式轮次读取
     * @param attachmentMapper WfAttachmentMapper，附件元数据读取
     * @return 无返回值，构造后由测试配置管理
     */
    WorkflowMultiInstanceStateProbe(RuntimeService runtimeService,
            TaskService taskService, HistoryService historyService,
            JdbcTemplate jdbcTemplate,
            WfMultiInstanceRoundMapper roundMapper,
            WfAttachmentMapper attachmentMapper)
    {
        this.runtimeService = runtimeService;
        this.taskService = taskService;
        this.historyService = historyService;
        this.jdbcTemplate = jdbcTemplate;
        this.roundMapper = roundMapper;
        this.attachmentMapper = attachmentMapper;
    }

    /**
     * 查询指定办理人的唯一活动任务。
     *
     * @param processInstanceId String，流程实例主键
     * @param activityId String，节点 ID
     * @param assignee String，办理人主键
     * @return Task，唯一活动任务
     */
    Task task(String processInstanceId, String activityId, String assignee)
    {
        Task task = taskService.createTaskQuery().processInstanceId(processInstanceId)
                .taskDefinitionKey(activityId).taskAssignee(assignee)
                .active().singleResult();
        assertThat(task).isNotNull();
        return task;
    }

    /**
     * 按主键读取活动任务。
     *
     * @param taskId String，任务主键
     * @return Task，任务已完成或不存在时为空
     */
    Task taskById(String taskId)
    {
        return taskService.createTaskQuery().taskId(taskId).active().singleResult();
    }

    /**
     * 查询节点全部活动任务。
     *
     * @param processInstanceId String，流程实例主键
     * @param activityId String，节点 ID
     * @return List&lt;Task&gt;，按办理人和主键稳定排序
     */
    List<Task> tasks(String processInstanceId, String activityId)
    {
        return taskService.createTaskQuery().processInstanceId(processInstanceId)
                .taskDefinitionKey(activityId).active().list().stream()
                .sorted(Comparator.comparing(Task::getAssignee,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(Task::getId))
                .toList();
    }

    /**
     * 查询整组退回后的唯一申请人任务。
     *
     * @param processInstanceId String，流程实例主键
     * @return Task，唯一活动申请人任务
     */
    Task returnedTask(String processInstanceId)
    {
        List<Task> tasks = taskService.createTaskQuery()
                .processInstanceId(processInstanceId).active().list();
        assertThat(tasks).singleElement();
        assertThat(tasks.get(0).getAssignee())
                .isEqualTo(WorkflowMultiInstanceBusinessDriver.APPLICANT_ID);
        return tasks.get(0);
    }

    /**
     * 查询流程全部正式轮次。
     *
     * @param processInstanceId String，流程实例主键
     * @return List&lt;WfMultiInstanceRound&gt;，按轮次主键稳定排序
     */
    List<WfMultiInstanceRound> rounds(String processInstanceId)
    {
        return roundMapper.selectByProcessInstanceId(processInstanceId).stream()
                .sorted(Comparator.comparing(WfMultiInstanceRound::getRoundId)).toList();
    }

    /**
     * 查询节点唯一 ACTIVE 轮次。
     *
     * @param processInstanceId String，流程实例主键
     * @param activityId String，节点 ID
     * @return WfMultiInstanceRound，唯一 ACTIVE 轮次
     */
    WfMultiInstanceRound activeRound(String processInstanceId, String activityId)
    {
        List<WfMultiInstanceRound> rounds = roundMapper
                .selectActiveByProcessInstanceAndActivity(
                        processInstanceId, activityId);
        assertThat(rounds).singleElement();
        return rounds.get(0);
    }

    /**
     * 查询实例集合全部开放轮次。
     *
     * @param processInstanceIds Set&lt;String&gt;，流程实例主键集合
     * @return List&lt;WfMultiInstanceRound&gt;，正式开放轮次
     */
    List<WfMultiInstanceRound> openRounds(Set<String> processInstanceIds)
    {
        return roundMapper.selectOpenByProcessInstanceIds(processInstanceIds);
    }

    /**
     * 读取流程作用域变量。
     *
     * @param processInstanceId String，流程实例主键
     * @param variableName String，变量名
     * @return Object，真实变量值
     */
    Object variable(String processInstanceId, String variableName)
    {
        return runtimeService.getVariable(processInstanceId, variableName);
    }

    /**
     * 读取任务局部变量。
     *
     * @param taskId String，任务主键
     * @param variableName String，变量名
     * @return Object，真实局部变量值
     */
    Object taskVariable(String taskId, String variableName)
    {
        return taskService.getVariableLocal(taskId, variableName);
    }

    /**
     * 读取任务身份链。
     *
     * @param taskId String，任务主键
     * @return List&lt;IdentityLink&gt;，真实身份链
     */
    List<IdentityLink> identityLinks(String taskId)
    {
        return taskService.getIdentityLinksForTask(taskId);
    }

    /**
     * 查询活动流程实例。
     *
     * @param processInstanceId String，流程实例主键
     * @return ProcessInstance，实例不存在时为空
     */
    ProcessInstance processInstance(String processInstanceId)
    {
        return runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
    }

    /**
     * 读取已结束流程的历史业务状态。
     *
     * @param processInstanceId String，流程实例主键
     * @return String，历史 businessStatus
     */
    String historicBusinessStatus(String processInstanceId)
    {
        return historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult()
                .getBusinessStatus();
    }

    /**
     * 查询 execution。
     *
     * @param executionId String，execution 主键
     * @return Execution，execution 不存在时为空
     */
    Execution execution(String executionId)
    {
        return runtimeService.createExecutionQuery()
                .executionId(executionId).singleResult();
    }

    /**
     * 查询实例全部 execution。
     *
     * @param processInstanceId String，流程实例主键
     * @return List&lt;Execution&gt;，真实 execution 列表
     */
    List<Execution> executions(String processInstanceId)
    {
        return runtimeService.createExecutionQuery()
                .processInstanceId(processInstanceId).list();
    }

    /**
     * 查询附件元数据。
     *
     * @param attachmentId String，附件主键
     * @return WfAttachment，正式附件行
     */
    WfAttachment attachment(String attachmentId)
    {
        return attachmentMapper.selectById(attachmentId);
    }

    /**
     * 核对流程变量状态和 Flowable businessStatus。
     *
     * @param processInstanceId String，流程实例主键
     * @param expected String，预期状态
     * @return void，无返回值
     */
    void assertDoubleStatus(String processInstanceId, String expected)
    {
        assertThat(variable(processInstanceId,
                WorkflowProcessStartService.PROCESS_STATUS_VARIABLE))
                .isEqualTo(expected);
        assertThat(processInstance(processInstanceId).getBusinessStatus())
                .isEqualTo(expected);
    }

    /**
     * 冻结任务、execution、流程变量和正式轮次。
     *
     * @param processInstanceId String，流程实例主键
     * @return CoreSnapshot，可直接值比较的核心事实
     */
    CoreSnapshot captureCore(String processInstanceId)
    {
        List<TaskFact> tasks = taskService.createTaskQuery()
                .processInstanceId(processInstanceId).active().list().stream()
                .map(task -> new TaskFact(task.getId(), task.getExecutionId(),
                        task.getTaskDefinitionKey(), task.getAssignee(), task.getOwner()))
                .sorted(Comparator.comparing(TaskFact::id)).toList();
        List<ExecutionFact> executions = runtimeService.createExecutionQuery()
                .processInstanceId(processInstanceId).list().stream()
                .map(execution -> new ExecutionFact(execution.getId(),
                        execution.getParentId(), execution.getActivityId()))
                .sorted(Comparator.comparing(ExecutionFact::id)).toList();
        Map<String, Map<String, Object>> locals = new TreeMap<>();
        for (ExecutionFact execution : executions)
        {
            locals.put(execution.id(), new TreeMap<>(
                    runtimeService.getVariablesLocal(execution.id())));
        }
        return new CoreSnapshot(tasks, executions,
                processInstance(processInstanceId) == null ? Map.of()
                        : new TreeMap<>(runtimeService.getVariables(processInstanceId)),
                locals, rounds(processInstanceId).stream()
                        .map(round -> new RoundFact(round.getRoundId(),
                                round.getProcessInstanceId(), round.getActivityId(),
                                round.getRoundStatus().name(), round.getMode(),
                                List.copyOf(round.getMembers()), round.getRevisionNo(),
                                round.getRootExecutionId(), round.getApplicantTaskId()))
                        .toList());
    }

    /**
     * 冻结核心事实、附件、SLA 状态和 SLA 审计。
     *
     * @param processInstanceId String，流程实例主键
     * @return GroupSnapshot，跨表事务对账快照
     */
    GroupSnapshot captureGroup(String processInstanceId)
    {
        List<AttachmentFact> attachments = jdbcTemplate.query("""
                select attachment_id, attachment_status, process_instance_id,
                       task_id, node_key, bound_time
                from wf_attachment order by attachment_id
                """, (rs, row) -> new AttachmentFact(rs.getString(1), rs.getString(2),
                        rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getObject(6, LocalDateTime.class)));
        List<SlaFact> slas = jdbcTemplate.query("""
                select sla_execution_id, task_id, status, revision
                from wf_task_sla_execution where process_instance_id = ?
                order by sla_execution_id
                """, (rs, row) -> new SlaFact(rs.getLong(1), rs.getString(2),
                        rs.getString(3), rs.getInt(4)), processInstanceId);
        List<SlaAuditFact> audits = jdbcTemplate.query("""
                select a.sla_execution_id, a.action_type, a.action_ordinal,
                       a.actor_user_id, a.detail
                from wf_task_sla_audit a join wf_task_sla_execution e
                  on e.sla_execution_id = a.sla_execution_id
                where e.process_instance_id = ? order by a.audit_id
                """, (rs, row) -> new SlaAuditFact(rs.getLong(1), rs.getString(2),
                        rs.getInt(3), rs.getString(4), rs.getString(5)),
                processInstanceId);
        return new GroupSnapshot(captureCore(processInstanceId),
                attachments, slas, audits);
    }

    /** @return long，当前全部活动任务数量。 */
    long activeTaskCount()
    {
        return taskService.createTaskQuery().active().count();
    }

    /** @return long，当前全部运行实例数量。 */
    long processInstanceCount()
    {
        return runtimeService.createProcessInstanceQuery().count();
    }

    private record TaskFact(String id, String executionId, String activityId,
            String assignee, String owner)
    {
    }

    private record ExecutionFact(String id, String parentId, String activityId)
    {
    }

    private record RoundFact(Long id, String processInstanceId,
            String activityId, String status, String mode, List<String> members,
            Integer revision, String rootExecutionId, String applicantTaskId)
    {
    }

    record CoreSnapshot(List<TaskFact> tasks, List<ExecutionFact> executions,
            Map<String, Object> processVariables,
            Map<String, Map<String, Object>> executionVariables,
            List<RoundFact> rounds)
    {
    }

    private record AttachmentFact(String id, String status,
            String processInstanceId, String taskId, String nodeKey,
            LocalDateTime boundTime)
    {
    }

    private record SlaFact(Long id, String taskId, String status, int revision)
    {
    }

    private record SlaAuditFact(Long executionId, String action, int ordinal,
            String actor, String detail)
    {
    }

    record GroupSnapshot(CoreSnapshot core, List<AttachmentFact> attachments,
            List<SlaFact> slas, List<SlaAuditFact> slaAudits)
    {
    }
}
