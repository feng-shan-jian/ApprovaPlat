package com.ruoyi.flowable.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.flowable.authorization.WorkflowProcessAccessService;
import com.ruoyi.flowable.config.WorkflowAttachmentProperties;
import com.ruoyi.flowable.domain.WfAttachment;
import com.ruoyi.flowable.domain.WfDeployForm;
import com.ruoyi.flowable.domain.WfMultiInstanceRound;
import com.ruoyi.flowable.domain.WfTaskSlaExecution;
import com.ruoyi.flowable.domain.WorkflowAttachmentStatus;
import com.ruoyi.flowable.domain.dto.WorkflowApplicationResubmitRequest;
import com.ruoyi.flowable.domain.dto.WorkflowTaskReturnRequest;
import com.ruoyi.flowable.mapper.WfAttachmentMapper;
import com.ruoyi.flowable.mapper.WfControlledLoopExecutionMapper;
import com.ruoyi.flowable.mapper.WfCopyMapper;
import com.ruoyi.flowable.mapper.WfTaskSlaMapper;
import com.ruoyi.flowable.mapper.WfMultiInstanceRoundMapper;
import com.ruoyi.flowable.mapper.param.WfTaskSlaAuditWriteParam;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.service.notification.WorkflowNotificationService;
import com.ruoyi.flowable.service.WorkflowFormTemplateValidator;
import com.ruoyi.flowable.service.attachment.StoredAttachmentFile;
import com.ruoyi.flowable.service.attachment.WorkflowAttachmentService;
import com.ruoyi.flowable.service.attachment.WorkflowAttachmentStorage;
import com.ruoyi.flowable.service.model.WorkflowBusinessCalendarService;
import com.ruoyi.flowable.service.model.WorkflowDeploymentArtifactRepository;
import com.ruoyi.flowable.service.model.WorkflowDeploymentArtifacts;
import com.ruoyi.flowable.service.notification.WorkflowNotificationWriter;
import com.ruoyi.flowable.service.process.WorkflowFormSubmissionSnapshotCodec;
import com.ruoyi.flowable.service.process.WorkflowProcessInstanceService;
import com.ruoyi.flowable.service.process.WorkflowProcessStartService;
import com.ruoyi.flowable.service.process.WorkflowStartVariableValidator;
import com.ruoyi.flowable.testsupport.WorkflowH2SchemaMapperSupport;
import com.ruoyi.framework.web.service.PermissionService;

/** 在轮次核心夹具上增加整组退回、重提、附件、SLA、通知及其回滚快照。
 */
final class WorkflowGroupReturnScenario implements AutoCloseable
{
    /** 轮次核心功能夹具；组迁移通过组合复用，不形成两层测试继承。 */
    private final WorkflowMultiInstanceRoundScenario roundFixture;

    /** 受控多实例默认成员，保持既有测试数据语义。 */
    static final List<String> MEMBERS = WorkflowMultiInstanceRoundScenario.MEMBERS;

    ProcessEngine processEngine;
    RepositoryService repositoryService;
    RuntimeService runtimeService;
    TaskService taskService;
    HistoryService historyService;
    JdbcTemplate jdbcTemplate;
    TransactionTemplate transactionTemplate;
    WfMultiInstanceRoundMapper roundMapper;
    WfMultiInstanceRoundMapper roundMapperDelegate;
    WorkflowMultiInstanceRoundRepository roundRepository;
    WorkflowMultiInstanceRuntimeSnapshotReader snapshotReader;
    WorkflowMultiInstanceRoundLifecycleService roundLifecycleService;
    WorkflowMultiInstanceRoundTerminationService roundTerminationService;
    WorkflowMultiInstanceTransitionCoordinator transitionCoordinator;
    WorkflowMultiInstanceService multiInstanceService;
    WorkflowIdentityResolver identityResolver;
    WorkflowEngineOperations engineOperations;
    WorkflowNotificationService notificationService;
    String deploymentId;

    /** 阶段三真实退回流程的发起人。 */
    protected static final String APPLICANT_ID = "100";

    /** 阶段三开始表单允许修改的业务字段和可选正式附件字段。 */
    protected static final String START_FORM = """
            {"fields":[
              {"__vModel__":"requestTitle","__config__":{"layout":"colFormItem","tag":"el-input","required":true}},
              {"__vModel__":"evidence","limit":3,"__config__":{"layout":"colFormItem","tag":"el-upload"}}
            ]}
            """;

    WfAttachmentMapper attachmentMapper;
    WfTaskSlaMapper taskSlaMapper;
    WorkflowTaskLifecycleService lifecycleService;
    WorkflowTaskCopyService taskCopyService;
    WorkflowTaskSlaRuntimeService taskSlaRuntimeService;
    WorkflowAttachmentService attachmentService;
    WorkflowProcessInstanceService processInstanceService;
    /** 组退回终止场景使用的实时流程权限依赖。 */
    PermissionService processPermissionService;

    /** JUnit 管理的独立私有附件存储根目录。 */
    final Path attachmentProfileRoot;

    /** 与正式附件元数据 Mapper 对账的私有文件存储。 */
    private WorkflowAttachmentStorage attachmentStorage;

    /** 生命周期稳定通知故障开关。 */
    private final AtomicBoolean failNextStableNotification = new AtomicBoolean();

    /** 在核心引擎装配完成后增加组退回专用表、正式 Mapper、服务和部署表单制品。
     * @return void，附件、SLA 与 Flowable、轮次共享同一数据源和事务 */
    WorkflowGroupReturnScenario(Path attachmentProfileRoot)
    {
        this.attachmentProfileRoot = attachmentProfileRoot;
        roundFixture = new WorkflowMultiInstanceRoundScenario();
        bindRoundFixture();
        DataSource dataSource = dataSource();
        WorkflowH2SchemaMapperSupport.executeSchema(dataSource,
                WorkflowH2SchemaMapperSupport.ATTACHMENT_SCHEMA);
        WorkflowH2SchemaMapperSupport.executeSchema(dataSource,
                WorkflowH2SchemaMapperSupport.TASK_SLA_SCHEMA);
        attachmentMapper = spy(WorkflowH2SchemaMapperSupport.createSpringMapper(dataSource,
                "mi-attachment-it", WfAttachmentMapper.class,
                "mapper/flowable/WfAttachmentMapper.xml"));
        taskSlaMapper = WorkflowH2SchemaMapperSupport.createSpringMapper(dataSource,
                "mi-task-sla-it", WfTaskSlaMapper.class,
                "mapper/flowable/WfTaskSlaMapper.xml");

        // 同一个通知 mock 同时被生产任务监听器和生命周期服务使用，便于精确验证事务回滚。
        doAnswer(invocation ->
        {
            if (failNextStableNotification.compareAndSet(true, false))
            {
                throw new IllegalStateException("injected stable notification failure");
            }
            return 0;
        }).when(notificationService).onStableTaskEvent(anyString(), any(Task.class));

        WorkflowDeploymentArtifactRepository artifactRepository =
                new WorkflowDeploymentArtifactRepository(repositoryService);
        artifactRepository.persist(deploymentId, lifecycleDeploymentArtifacts());
        WorkflowTaskSlaRuntimeService taskSlaTarget =
                new WorkflowTaskSlaRuntimeService(repositoryService,
                        processEngine.getManagementService(),
                        processEngine.getProcessEngineConfiguration(),
                        mock(WorkflowBusinessCalendarService.class), engineOperations,
                        taskSlaMapper, artifactRepository,
                        mock(WorkflowNotificationWriter.class));
        taskSlaRuntimeService = transactionalProxy(taskSlaTarget);
        WorkflowAttachmentProperties attachmentProperties =
                new WorkflowAttachmentProperties();
        attachmentProperties.setMinFreeBytes(0L);
        attachmentStorage = new WorkflowAttachmentStorage(attachmentProfileRoot,
                attachmentProperties.getMaxSize());
        WorkflowAttachmentService attachmentTarget = new WorkflowAttachmentService(
                attachmentMapper, attachmentStorage, attachmentProperties,
                identityResolver, mock(WorkflowProcessAccessService.class));
        attachmentService = transactionalProxy(attachmentTarget);
        taskCopyService = mock(WorkflowTaskCopyService.class);
        when(taskCopyService.prepare(any(WorkflowTaskCopyAction.class), any(Task.class),
                any(com.ruoyi.flowable.identity.WorkflowCurrentIdentity.class), anyList()))
                .thenReturn(WorkflowTaskCopyService.CopyPlan.empty());
        processPermissionService = mock(PermissionService.class);
        processInstanceService = new WorkflowProcessInstanceService(
                engineOperations, historyService, runtimeService, taskService,
                attachmentMapper, mock(WfCopyMapper.class),
                mock(WfControlledLoopExecutionMapper.class), roundMapper,
                roundTerminationService, processPermissionService, taskSlaRuntimeService,
                notificationService);
        WorkflowMultiInstanceGroupTransitionService groupTransitionService =
                new WorkflowMultiInstanceGroupTransitionService(roundRepository,
                        snapshotReader, roundLifecycleService,
                        transitionCoordinator, new WorkflowTaskRuntimeReader(
                                runtimeService, taskService, historyService),
                        runtimeService, taskSlaRuntimeService);
        WorkflowTaskRequestValidator requestValidator =
                new WorkflowTaskRequestValidator();
        WorkflowTaskRuntimeReader taskRuntimeReader = new WorkflowTaskRuntimeReader(
                runtimeService, taskService, historyService);
        WorkflowTaskBpmnReader taskBpmnReader =
                new WorkflowTaskBpmnReader(repositoryService);
        WorkflowTaskActionAuditWriter auditWriter =
                new WorkflowTaskActionAuditWriter(taskService);
        WorkflowTaskConcurrencyExecutor concurrencyExecutor =
                new WorkflowTaskConcurrencyExecutor();
        WorkflowReturnedTaskStateService returnedTaskStateService =
                new WorkflowReturnedTaskStateService(
                        new WorkflowReturnedAssignmentCodec(), runtimeService,
                        taskService);
        WorkflowProcessCancelApplicationService cancelApplicationService =
                new WorkflowProcessCancelApplicationService(engineOperations,
                        requestValidator, taskRuntimeReader, processInstanceService,
                        taskService, auditWriter);
        WorkflowTaskCompletionApplicationService completionApplicationService =
                new WorkflowTaskCompletionApplicationService(engineOperations,
                        requestValidator, taskRuntimeReader, taskBpmnReader,
                        artifactRepository,
                        new WorkflowStartVariableValidator(
                                new WorkflowFormTemplateValidator()),
                        attachmentService, taskCopyService,
                        mock(WorkflowNextTaskAssignmentService.class),
                        multiInstanceService,
                        mock(WorkflowControlledLoopService.class), auditWriter,
                        concurrencyExecutor, runtimeService, taskService);
        WorkflowTaskRejectionApplicationService rejectionApplicationService =
                new WorkflowTaskRejectionApplicationService(engineOperations,
                        requestValidator, taskRuntimeReader, processInstanceService,
                        taskCopyService, auditWriter, taskService);
        WorkflowTaskReturnApplicationService returnApplicationService =
                new WorkflowTaskReturnApplicationService(engineOperations,
                        requestValidator, taskRuntimeReader,
                        taskBpmnReader, new WorkflowTaskMovementPolicy(),
                        returnedTaskStateService, auditWriter,
                        concurrencyExecutor, groupTransitionService,
                        taskCopyService, notificationService, runtimeService);
        WorkflowApplicationResubmitApplicationService resubmitApplicationService =
                new WorkflowApplicationResubmitApplicationService(engineOperations,
                        requestValidator, taskRuntimeReader,
                        returnedTaskStateService, auditWriter,
                        concurrencyExecutor, groupTransitionService,
                        artifactRepository,
                        new WorkflowStartVariableValidator(
                                new WorkflowFormTemplateValidator()),
                        attachmentService, notificationService, runtimeService);
        lifecycleService = new WorkflowTaskLifecycleService(
                cancelApplicationService,
                mock(WorkflowTaskRevokeApplicationService.class),
                completionApplicationService, rejectionApplicationService,
                returnApplicationService, resubmitApplicationService);
    }

    /** 以真实发起人身份启动组退回流程，同时写入开始表单快照、运行状态和有序成员。
     * @param processKey String，阶段三流程定义 key
     * @param startNodeId String，部署开始表单绑定的 StartEvent 主键
     * @param activityId String，受控多实例活动主键
     * @param members List&lt;String&gt;，首轮完整有序成员
     * @return ProcessInstance，带发起人、运行双状态和正式 ACTIVE 轮次的实例 */
    protected final ProcessInstance startLifecycle(String processKey,
            String startNodeId, String activityId, List<String> members)
    {
        Map<String, Object> variables = new java.util.LinkedHashMap<>();
        variables.put(WorkflowMultiInstanceVariables.userCollectionName(activityId),
                members.stream().map(Long::valueOf).toList());
        variables.put("requestTitle", "原始申请");
        variables.put(WorkflowProcessStartService.PROCESS_STATUS_VARIABLE,
                WorkflowProcessStartService.RUNNING_STATUS);
        variables.put(WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME,
                WorkflowFormSubmissionSnapshotCodec.encodeStart(
                        deploymentId, "TEMPLATE", 1L, "startForm", startNodeId,
                        Map.of("requestTitle", "原始申请")));

        processEngine.getIdentityService().setAuthenticatedUserId(APPLICANT_ID);
        try
        {
            return transactionTemplate.execute(status ->
            {
                ProcessInstance instance = runtimeService.startProcessInstanceByKey(
                        processKey, variables);
                runtimeService.updateBusinessStatus(instance.getId(),
                        WorkflowProcessStartService.RUNNING_STATUS);
                return runtimeService.createProcessInstanceQuery()
                        .processInstanceId(instance.getId()).singleResult();
            });
        }
        finally
        {
            processEngine.getIdentityService().setAuthenticatedUserId(null);
        }
    }

    /** 完成后置多实例流程的普通首审批任务，使实例进入受控多实例节点。
     * @param processInstanceId String，真实流程实例主键
     * @param activityId String，普通首审批节点主键
     * @param assignee String，BPMN 固定办理人
     * @return void，任务不存在或办理人漂移时测试立即失败 */
    protected final void completeOrdinary(String processInstanceId,
            String activityId, String assignee)
    {
        Task current = task(processInstanceId, activityId, assignee);
        transactionTemplate.executeWithoutResult(
                status -> taskService.complete(current.getId(), assignee));
    }

    /** 通过生产生命周期服务执行受控多实例整组退回。
     * @param sourceTask Task，当前真实活动成员任务
     * @param actorUserId String，来源任务真实办理人
     * @return void，生产链路异常由 LifecycleService 保留稳定状态码并回滚 */
    protected final void returnGroup(Task sourceTask, String actorUserId)
    {
        setCurrentUser(actorUserId);
        lifecycleService.returnTask(new WorkflowTaskReturnRequest(
                sourceTask.getId(), "阶段三整组退回", List.of()));
    }

    /** 通过生产生命周期服务由发起人修改开始表单并重新提交。
     * @param applicantTask Task，RETURNED 轮次绑定的唯一待修改任务
     * @param requestTitle String，本次通过正式表单 schema 更新的申请标题
     * @return void，表单、附件、CAS、监听器或通知失败时整笔事务回滚 */
    protected final void resubmitGroup(Task applicantTask, String requestTitle)
    {
        resubmitGroup(applicantTask, Map.of("requestTitle", requestTitle));
    }

    /** 通过生产生命周期服务提交完整开始表单 patch，供真实附件和表单故障用例复用。
     * @param applicantTask Task，RETURNED 轮次绑定的唯一待修改任务
     * @param variables Map&lt;String,Object&gt;，已经部署表单 schema 校验的客户端 patch
     * @return void，任一写链失败时整笔事务回滚 */
    protected final void resubmitGroup(Task applicantTask, Map<String, Object> variables)
    {
        setCurrentUser(APPLICANT_ID);
        lifecycleService.resubmitApplication(new WorkflowApplicationResubmitRequest(
                applicantTask.getId(), variables));
    }

    /** 查询整组退回后唯一申请人待修改任务。
     * @param processInstanceId String，RETURNED 流程实例主键
     * @return Task，唯一活动且办理人为发起人的任务 */
    protected final Task returnedTask(String processInstanceId)
    {
        List<Task> tasks = taskService.createTaskQuery()
                .processInstanceId(processInstanceId).active().list();
        assertThat(tasks).singleElement();
        assertThat(tasks.get(0).getAssignee()).isEqualTo(APPLICANT_ID);
        return tasks.get(0);
    }

    /** 核对流程变量状态与 Flowable businessStatus 同步一致。
     * @param processInstanceId String，活动流程实例主键
     * @param expectedStatus String，预期业务状态
     * @return void，任一状态漂移时测试失败 */
    protected final void assertDoubleStatus(String processInstanceId,
            String expectedStatus)
    {
        assertThat(runtimeService.getVariable(processInstanceId,
                WorkflowProcessStartService.PROCESS_STATUS_VARIABLE))
                .isEqualTo(expectedStatus);
        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult()
                .getBusinessStatus()).isEqualTo(expectedStatus);
    }

    /** 冻结核心事实以及附件、SLA 运行状态和 SLA 审计。
     * @param processInstanceId String，活动流程实例主键
     * @return GroupTransitionSnapshot，可直接值比较的组退回事务快照 */
    protected final GroupTransitionSnapshot captureGroupTransition(
            String processInstanceId)
    {
        List<AttachmentFact> attachments = jdbcTemplate.query("""
                select attachment_id, attachment_status, draft_id,
                       process_instance_id, task_id, node_key, bound_time,
                       cleanup_retry_count, update_time
                from wf_attachment order by attachment_id
                """, (resultSet, rowNum) -> new AttachmentFact(
                        resultSet.getString("attachment_id"),
                        resultSet.getString("attachment_status"),
                        resultSet.getString("draft_id"),
                        resultSet.getString("process_instance_id"),
                        resultSet.getString("task_id"),
                        resultSet.getString("node_key"),
                        resultSet.getObject("bound_time", LocalDateTime.class),
                        resultSet.getInt("cleanup_retry_count"),
                        resultSet.getObject("update_time", LocalDateTime.class)));
        List<SlaFact> slas = jdbcTemplate.query("""
                select sla_execution_id, task_id, status, revision
                from wf_task_sla_execution where process_instance_id = ?
                order by sla_execution_id
                """, (resultSet, rowNum) -> new SlaFact(
                        resultSet.getLong("sla_execution_id"),
                        resultSet.getString("task_id"),
                        resultSet.getString("status"),
                        resultSet.getInt("revision")), processInstanceId);
        List<SlaAuditFact> audits = jdbcTemplate.query("""
                select a.sla_execution_id, a.action_type, a.action_ordinal,
                       a.actor_user_id, a.detail
                from wf_task_sla_audit a
                join wf_task_sla_execution e
                  on e.sla_execution_id = a.sla_execution_id
                where e.process_instance_id = ?
                order by a.audit_id
                """, (resultSet, rowNum) -> new SlaAuditFact(
                        resultSet.getLong("sla_execution_id"),
                        resultSet.getString("action_type"),
                        resultSet.getInt("action_ordinal"),
                        resultSet.getString("actor_user_id"),
                        resultSet.getString("detail")), processInstanceId);
        return new GroupTransitionSnapshot(roundFixture.captureCore(processInstanceId),
                attachments, slas, audits);
    }

    /** 令下一次生命周期稳定任务通知失败。
     * @return void，无返回值 */
    protected final void failNextStableNotification()
    {
        failNextStableNotification.set(true);
    }

    /** 令下一次开始表单提交快照 setVariable 精确失败。
     * @return void，RuntimeService 其他真实变量读写保持不变 */
    protected final void failNextSubmissionSnapshotWrite()
    {
        doThrow(new IllegalStateException(
                "injected submission snapshot write failure"))
                .when(runtimeService).setVariable(anyString(),
                        eq(WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME), any());
    }

    /** 在 JUnit 私有目录写入真实文件并通过正式 Mapper 创建本人 TEMP 附件。
     * @return String，新附件 UUID */
    protected final String insertTemporaryAttachment()
    {
        StoredAttachmentFile stored = attachmentStorage.store(new MockMultipartFile(
                "file", "evidence.txt", "text/plain", "phase3-evidence".getBytes()));
        String attachmentId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        WfAttachment attachment = new WfAttachment(attachmentId,
                Long.valueOf(APPLICANT_ID), "evidence", stored.originalName(),
                stored.storageKey(), stored.contentType(), stored.fileSize(),
                stored.sha256(), WorkflowAttachmentStatus.TEMP, now.plusHours(1),
                null, null, null, null, null, null, 0,
                null, null, null, null, now, now);
        transactionTemplate.executeWithoutResult(status ->
                assertThat(attachmentMapper.insert(attachment)).isOne());
        return attachmentId;
    }

    /** 使用正式 SLA Mapper 为真实活动任务建立最小完整运行事实和生命周期审计。
     * @param task Task，SLA 所属真实 Flowable 活动任务
     * @param status String，ACTIVE 或 ESCALATED
     * @return Long，正式数据库生成的 SLA 执行主键 */
    protected final Long insertTaskSla(Task task, String status)
    {
        if (!"ACTIVE".equals(status) && !"ESCALATED".equals(status))
        {
            throw new IllegalArgumentException("测试 SLA 状态无效");
        }
        return transactionTemplate.execute(transactionStatus ->
        {
            LocalDateTime startedAt = LocalDateTime.now().minusMinutes(1);
            WfTaskSlaExecution execution = new WfTaskSlaExecution();
            execution.setDeploymentId(deploymentId);
            execution.setProcessInstanceId(task.getProcessInstanceId());
            execution.setProcessDefinitionId(task.getProcessDefinitionId());
            execution.setTaskId(task.getId());
            execution.setTaskDefinitionKey(task.getTaskDefinitionKey());
            execution.setAssigneeUserId(task.getAssignee());
            execution.setStartedAt(startedAt);
            execution.setReminderDueAt(startedAt.plusMinutes(10));
            execution.setEscalationDueAt(startedAt.plusMinutes(20));
            assertThat(taskSlaMapper.insertExecution(execution)).isOne();
            assertThat(execution.getSlaExecutionId()).isNotNull();
            insertTaskSlaAudit(execution.getSlaExecutionId(), "CREATE", 0,
                    task.getAssignee(), "测试正式 SLA 时钟启动");
            if ("ESCALATED".equals(status))
            {
                assertThat(taskSlaMapper.markEscalated(
                        execution.getSlaExecutionId(), 0)).isOne();
                insertTaskSlaAudit(execution.getSlaExecutionId(), "ESCALATE", 0,
                        task.getAssignee(), "测试正式 SLA 已升级");
            }
            return execution.getSlaExecutionId();
        });
    }

    /** 核对受控迁移已关闭精确任务集合全部 SLA，且每行仅写一条固定 COMPLETE 审计。
     * @param processInstanceId String，流程实例主键
     * @param taskIds List&lt;String&gt;，迁移前建立 SLA 的精确任务主键
     * @param actorUserId String，预期 COMPLETE 审计操作人
     * @param detail String，预期服务端固定的受控撤销详情
     * @return void，开放行、遗漏行或审计漂移时测试失败 */
    protected final void assertTaskSlasWithdrawn(String processInstanceId,
            List<String> taskIds, String actorUserId, String detail)
    {
        String placeholders = String.join(",", taskIds.stream()
                .map(taskId -> "?").toList());
        Integer completed = jdbcTemplate.queryForObject("""
                select count(*) from wf_task_sla_execution
                where process_instance_id = ? and status = 'COMPLETED'
                  and task_id in (%s)
                """.formatted(placeholders), Integer.class,
                concat(processInstanceId, taskIds));
        assertThat(completed).isEqualTo(taskIds.size());
        Integer open = jdbcTemplate.queryForObject("""
                select count(*) from wf_task_sla_execution
                where process_instance_id = ? and status in ('ACTIVE', 'ESCALATED')
                  and task_id in (%s)
                """.formatted(placeholders), Integer.class,
                concat(processInstanceId, taskIds));
        assertThat(open).isZero();
        Integer audits = jdbcTemplate.queryForObject("""
                select count(*) from wf_task_sla_audit a
                join wf_task_sla_execution e
                  on e.sla_execution_id = a.sla_execution_id
                where e.process_instance_id = ? and e.task_id in (%s)
                  and a.action_type = 'COMPLETE' and a.action_ordinal = 0
                  and a.actor_user_id = ? and a.detail = ?
                """.formatted(placeholders), Integer.class,
                concat(processInstanceId, taskIds, actorUserId, detail));
        assertThat(audits).isEqualTo(taskIds.size());
    }

    /** 写入一条使用生产唯一键和 generated key 契约的正式 SLA 审计。
     * @param executionId Long，SLA 执行主键
     * @param action String，CREATE 或 ESCALATE
     * @param ordinal int，动作序号
     * @param actor String，操作人用户主键
     * @param detail String，测试准备阶段稳定详情
     * @return void，审计未生成稳定主键时测试失败 */
    private void insertTaskSlaAudit(Long executionId, String action, int ordinal,
            String actor, String detail)
    {
        WfTaskSlaAuditWriteParam param = new WfTaskSlaAuditWriteParam();
        param.setExecutionId(executionId);
        param.setActionType(action);
        param.setActionOrdinal(ordinal);
        param.setActorUserId(actor);
        param.setDetail(detail);
        assertThat(taskSlaMapper.insertAudit(param)).isOne();
        assertThat(param.getAuditId()).isNotNull();
    }

    /** 拼接流程实例和可变任务主键参数。
     * @param processInstanceId String，首个流程实例参数
     * @param values List&lt;String&gt;，后续任务主键参数
     * @return Object[]，按 SQL 占位符顺序排列的值副本 */
    private Object[] concat(String processInstanceId, List<String> values)
    {
        List<Object> parameters = new ArrayList<>();
        parameters.add(processInstanceId);
        parameters.addAll(values);
        return parameters.toArray();
    }

    /** 拼接任务主键之后的审计操作人和详情参数。
     * @param processInstanceId String，首个流程实例参数
     * @param taskIds List&lt;String&gt;，中间任务主键参数
     * @param actorUserId String，审计操作人参数
     * @param detail String，审计详情参数
     * @return Object[]，按 SQL 占位符顺序排列的值副本 */
    private Object[] concat(String processInstanceId, List<String> taskIds,
            String actorUserId, String detail)
    {
        List<Object> parameters = new ArrayList<>();
        parameters.add(processInstanceId);
        parameters.addAll(taskIds);
        parameters.add(actorUserId);
        parameters.add(detail);
        return parameters.toArray();
    }

    /** 为三个组退回流程创建与各自 StartEvent 精确绑定的不可变开始表单制品。
     * @return WorkflowDeploymentArtifacts，除开始表单外其余部署快照集合为空 */
    private WorkflowDeploymentArtifacts lifecycleDeploymentArtifacts()
    {
        List<WfDeployForm> forms = List.of(
                startForm("firstAllReturnStart"),
                startForm("firstAnyReturnStart"),
                startForm("laterAllReturnStart"));
        return new WorkflowDeploymentArtifacts(forms, List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());
    }

    /** 创建单个 StartEvent 对应的部署表单快照。
     * @param startNodeId String，真实 BPMN StartEvent 主键
     * @return WfDeployForm，使用统一模板但与部署和节点强绑定的快照 */
    private WfDeployForm startForm(String startNodeId)
    {
        WfDeployForm form = new WfDeployForm();
        form.setDeployId(deploymentId);
        form.setSourceType("TEMPLATE");
        form.setFormId(1L);
        form.setFormKey("startForm");
        form.setNodeKey(startNodeId);
        form.setFormName("阶段三申请表");
        form.setNodeName("开始");
        form.setContent(START_FORM);
        form.setCreateTime(new Date());
        return form;
    }

    /** 将组合的轮次夹具生产对象暴露给当前功能夹具，避免测试类形成两层继承。
     * @return void，字段只在每个用例的独立轮次夹具启动后绑定 */
    private void bindRoundFixture()
    {
        processEngine = roundFixture.processEngine;
        repositoryService = roundFixture.repositoryService;
        runtimeService = roundFixture.runtimeService;
        taskService = roundFixture.taskService;
        historyService = roundFixture.historyService;
        jdbcTemplate = roundFixture.jdbcTemplate;
        transactionTemplate = roundFixture.transactionTemplate;
        roundMapper = roundFixture.roundMapper;
        roundMapperDelegate = roundFixture.roundMapperDelegate;
        roundRepository = roundFixture.roundRepository;
        snapshotReader = roundFixture.snapshotReader;
        roundLifecycleService = roundFixture.roundLifecycleService;
        roundTerminationService = roundFixture.roundTerminationService;
        transitionCoordinator = roundFixture.transitionCoordinator;
        multiInstanceService = roundFixture.multiInstanceService;
        identityResolver = roundFixture.identityResolver;
        engineOperations = roundFixture.engineOperations;
        notificationService = roundFixture.notificationService;
        deploymentId = roundFixture.deploymentId;
    }

    /** 返回组合轮次夹具的独立数据源。
     * @return DataSource，Flowable、轮次、附件和 SLA 共用的数据源 */
    protected final DataSource dataSource()
    {
        return roundFixture.dataSource();
    }

    /** 将组迁移专用生产服务加入轮次夹具的真实共享事务。
     * @param target T，待代理生产服务
     * @param <T> 生产服务类型
     * @return T，CGLIB 事务代理 */
    protected final <T> T transactionalProxy(T target)
    {
        return roundFixture.transactionalProxy(target);
    }

    /** 切换下一次生产命令使用的正式当前用户。
     * @param userId String，规范用户主键
     * @return void，无返回值 */
    protected final void setCurrentUser(String userId)
    {
        roundFixture.setCurrentUser(userId);
    }

    /** 通过组合的动态多实例服务完成一个真实成员任务。
     * @param currentTask Task，活动成员任务
     * @param expectedRevision int，客户端预期 revision
     * @return void，完成、监听器和写后对账共用同一事务 */
    protected final void complete(Task currentTask, int expectedRevision)
    {
        roundFixture.complete(currentTask, expectedRevision);
    }

    /** 通过组合夹具执行真实动态加签。
     * @param currentTask Task，当前办理人任务
     * @param expectedRevision int，客户端预期 revision
     * @param userId long，新增成员主键
     * @return void，加签与轮次 CAS 在同一事务完成 */
    protected final void addMember(Task currentTask, int expectedRevision, long userId)
    {
        roundFixture.addMember(currentTask, expectedRevision, userId);
    }

    /** 通过组合夹具执行真实动态减签。
     * @param currentTask Task，当前办理人任务
     * @param targetTask Task，待移除成员任务
     * @param expectedRevision int，客户端预期 revision
     * @return void，减签与轮次 CAS 在同一事务完成 */
    protected final void removeMember(Task currentTask, Task targetTask,
            int expectedRevision)
    {
        roundFixture.removeMember(currentTask, targetTask, expectedRevision);
    }

    /** 查询指定办理人的唯一活动任务。
     * @param processInstanceId String，流程实例主键
     * @param activityId String，节点主键
     * @param assignee String，办理人主键
     * @return Task，唯一活动任务 */
    protected final Task task(String processInstanceId, String activityId,
            String assignee)
    {
        return roundFixture.task(processInstanceId, activityId, assignee);
    }

    /** 查询节点的全部活动任务。
     * @param processInstanceId String，流程实例主键
     * @param activityId String，节点主键
     * @return List&lt;Task&gt;，按办理人稳定排序的活动任务 */
    protected final List<Task> tasks(String processInstanceId, String activityId)
    {
        return roundFixture.tasks(processInstanceId, activityId);
    }

    /** 查询流程实例全部正式轮次。
     * @param processInstanceId String，流程实例主键
     * @return List&lt;WfMultiInstanceRound&gt;，按轮次主键稳定排序 */
    protected final List<WfMultiInstanceRound> rounds(String processInstanceId)
    {
        return roundFixture.rounds(processInstanceId);
    }

    /** 查询节点唯一 ACTIVE 轮次。
     * @param processInstanceId String，流程实例主键
     * @param activityId String，节点主键
     * @return WfMultiInstanceRound，唯一 ACTIVE 轮次 */
    protected final WfMultiInstanceRound activeRound(String processInstanceId,
            String activityId)
    {
        return roundFixture.activeRound(processInstanceId, activityId);
    }

    /** 令下一次任务 create 审计失败，用于验证整笔事务回滚。
     * @return void，无返回值 */
    protected final void failNextCreateAudit()
    {
        roundFixture.failNextCreateAudit();
    }

    /** 关闭组合的真实 Flowable 引擎并清理线程身份。
     * @return void，即使组迁移用例失败也释放独立测试资源 */
    @Override
    public void close()
    {
            roundFixture.close();
    }

    /** 组退回快照中的附件持久化事实。 */
    protected record AttachmentFact(String id, String status, String draftId,
            String processInstanceId, String taskId, String nodeKey,
            LocalDateTime boundTime, int cleanupRetryCount, LocalDateTime updateTime)
    {
    }

    /** 组退回快照中的 SLA 运行事实。 */
    protected record SlaFact(Long id, String taskId, String status, int revision)
    {
    }

    /** 组退回快照中的 SLA 不可变审计事实。 */
    protected record SlaAuditFact(Long executionId, String action, int ordinal,
            String actor, String detail)
    {
    }

    /** 故障前后可直接值比较的组退回完整事务快照。
     * @param core CoreRuntimeSnapshot，任务、execution、流程变量和轮次
     * @param attachments List&lt;AttachmentFact&gt;，全部附件元数据
     * @param slas List&lt;SlaFact&gt;，当前实例 SLA 运行状态
     * @param slaAudits List&lt;SlaAuditFact&gt;，当前实例 SLA 审计 */
    protected record GroupTransitionSnapshot(
            WorkflowMultiInstanceRoundScenario.CoreRuntimeSnapshot core,
            List<AttachmentFact> attachments, List<SlaFact> slas,
            List<SlaAuditFact> slaAudits)
    {
    }
}
