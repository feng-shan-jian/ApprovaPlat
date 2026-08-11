package com.ruoyi.flowable.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.BoundaryEvent;
import org.flowable.bpmn.model.CallActivity;
import org.flowable.bpmn.model.EndEvent;
import org.flowable.bpmn.model.FormProperty;
import org.flowable.bpmn.model.IntermediateCatchEvent;
import org.flowable.bpmn.model.MultiInstanceLoopCharacteristics;
import org.flowable.bpmn.model.ParallelGateway;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.bpmn.model.ServiceTask;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.bpmn.model.SubProcess;
import org.flowable.bpmn.model.TimerEventDefinition;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ChangeActivityStateBuilder;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ExecutionQuery;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.engine.runtime.ProcessInstanceQuery;
import org.flowable.engine.task.Comment;
import org.flowable.task.api.DelegationState;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.task.api.history.HistoricTaskInstanceQuery;
import org.flowable.engine.history.HistoricProcessInstanceQuery;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.engine.history.HistoricActivityInstanceQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfCopy;
import com.ruoyi.flowable.domain.WfDeployForm;
import com.ruoyi.flowable.domain.dto.WorkflowProcessCancelRequest;
import com.ruoyi.flowable.domain.dto.WorkflowProcessRevokeRequest;
import com.ruoyi.flowable.domain.dto.WorkflowApplicationResubmitRequest;
import com.ruoyi.flowable.domain.dto.WorkflowTaskCompleteRequest;
import com.ruoyi.flowable.domain.dto.WorkflowTaskRejectRequest;
import com.ruoyi.flowable.domain.dto.WorkflowTaskReturnRequest;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.service.model.WorkflowDeploymentArtifactRepository;
import com.ruoyi.flowable.service.attachment.WorkflowAttachmentService;
import com.ruoyi.flowable.service.process.WorkflowFormSubmissionSnapshotCodec;
import com.ruoyi.flowable.service.process.WorkflowStartVariableValidator;
import com.ruoyi.flowable.service.process.WorkflowValidatedStartVariables;

class WorkflowTaskLifecycleServiceTest
{
    private static final String ACTOR_ID = "7";

    private static final String TASK_ID = "task-1";

    private static final String INSTANCE_ID = "instance-1";

    private static final String DEFINITION_ID = "approval:1:10";

    private static final String PROCESS_KEY = "approval";

    private final ObjectMapper objectMapper = JsonMapper.shared();

    /** 单元测试中模拟同一事务可回查的 Flowable 结构化意见。 */
    private List<Comment> persistedComments;

    private WorkflowEngineOperations engineOperations;

    private WorkflowIdentityResolver identityResolver;

    private RepositoryService repositoryService;

    private RuntimeService runtimeService;

    private TaskService taskService;

    private HistoryService historyService;

    private TaskQuery taskQuery;

    private ProcessInstanceQuery processInstanceQuery;

    private ExecutionQuery executionQuery;

    private HistoricTaskInstanceQuery historicTaskQuery;

    private HistoricProcessInstanceQuery historicProcessQuery;

    private HistoricActivityInstanceQuery historicActivityQuery;

    private WorkflowDeploymentArtifactRepository artifactRepository;

    private WorkflowStartVariableValidator variableValidator;

    private WorkflowAttachmentService attachmentService;

    private WorkflowTaskCopyService taskCopyService;

    private WorkflowNextTaskAssignmentService nextTaskAssignmentService;

    private WorkflowMultiInstanceService multiInstanceService;

    private WorkflowControlledLoopService controlledLoopService;

    private WorkflowTaskLifecycleService lifecycleService;

    private WorkflowCurrentIdentity actor;

    /**
     * 为每个测试创建深桩 Flowable 公共服务，并让事务边界真实执行传入闭包。
     *
     * @return 无返回值，初始化失败时测试失败
     */
    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp()
    {
        engineOperations = mock(WorkflowEngineOperations.class);
        identityResolver = mock(WorkflowIdentityResolver.class);
        repositoryService = mock(RepositoryService.class);
        runtimeService = mock(RuntimeService.class);
        taskService = mock(TaskService.class);
        historyService = mock(HistoryService.class);
        taskQuery = mock(TaskQuery.class, RETURNS_SELF);
        processInstanceQuery = mock(ProcessInstanceQuery.class, RETURNS_SELF);
        executionQuery = mock(ExecutionQuery.class, RETURNS_SELF);
        historicTaskQuery = mock(HistoricTaskInstanceQuery.class, RETURNS_SELF);
        historicProcessQuery = mock(HistoricProcessInstanceQuery.class, RETURNS_SELF);
        historicActivityQuery = mock(HistoricActivityInstanceQuery.class, RETURNS_SELF);
        artifactRepository = mock(WorkflowDeploymentArtifactRepository.class);
        variableValidator = mock(WorkflowStartVariableValidator.class);
        when(variableValidator.readableFieldNames(anyString())).thenReturn(Set.of());
        attachmentService = mock(WorkflowAttachmentService.class);
        taskCopyService = mock(WorkflowTaskCopyService.class);
        nextTaskAssignmentService = mock(WorkflowNextTaskAssignmentService.class);
        multiInstanceService = mock(WorkflowMultiInstanceService.class);
        controlledLoopService = mock(WorkflowControlledLoopService.class);
        actor = new WorkflowCurrentIdentity(ACTOR_ID, Set.of());
        persistedComments = new ArrayList<>();

        when(taskService.createTaskQuery()).thenReturn(taskQuery);
        when(runtimeService.createProcessInstanceQuery()).thenReturn(processInstanceQuery);
        when(runtimeService.createExecutionQuery()).thenReturn(executionQuery);
        when(historyService.createHistoricTaskInstanceQuery()).thenReturn(historicTaskQuery);
        when(historyService.createHistoricProcessInstanceQuery()).thenReturn(historicProcessQuery);
        when(historyService.createHistoricActivityInstanceQuery()).thenReturn(historicActivityQuery);
        // Flowable 部分查询方法声明在泛型父接口中，显式返回自身可避免 Mockito 擦除后返回 null。
        when(taskQuery.taskId(any())).thenReturn(taskQuery);
        when(taskQuery.processInstanceId(any())).thenReturn(taskQuery);
        when(taskQuery.processInstanceIdIn(any())).thenReturn(taskQuery);
        when(taskQuery.active()).thenReturn(taskQuery);
        when(processInstanceQuery.processInstanceId(any())).thenReturn(processInstanceQuery);
        when(processInstanceQuery.processInstanceIds(any())).thenReturn(processInstanceQuery);
        when(processInstanceQuery.active()).thenReturn(processInstanceQuery);
        when(executionQuery.executionId(any())).thenReturn(executionQuery);
        when(executionQuery.rootProcessInstanceId(any())).thenReturn(executionQuery);
        when(historicTaskQuery.taskId(any())).thenReturn(historicTaskQuery);
        when(historicTaskQuery.processInstanceId(any())).thenReturn(historicTaskQuery);
        when(historicTaskQuery.finished()).thenReturn(historicTaskQuery);
        when(historicTaskQuery.orderByHistoricTaskInstanceEndTime()).thenReturn(historicTaskQuery);
        when(historicTaskQuery.desc()).thenReturn(historicTaskQuery);
        when(historicProcessQuery.processInstanceId(any())).thenReturn(historicProcessQuery);
        when(historicActivityQuery.processInstanceId(any())).thenReturn(historicActivityQuery);
        when(historicActivityQuery.activityType(any())).thenReturn(historicActivityQuery);
        when(historicActivityQuery.orderByHistoricActivityInstanceStartTime())
                .thenReturn(historicActivityQuery);
        when(historicActivityQuery.asc()).thenReturn(historicActivityQuery);
        when(taskService.getTaskAttachments(any())).thenReturn(List.of());
        when(taskService.getTaskComments(any())).thenReturn(List.of());
        when(taskService.getSubTasks(any())).thenReturn(List.of());
        when(taskService.addComment(any(), any(), any(), any())).thenAnswer(invocation ->
        {
            Comment comment = mock(Comment.class);
            when(comment.getTaskId()).thenReturn(invocation.getArgument(0));
            when(comment.getProcessInstanceId()).thenReturn(invocation.getArgument(1));
            when(comment.getType()).thenReturn(invocation.getArgument(2));
            when(comment.getFullMessage()).thenReturn(invocation.getArgument(3));
            persistedComments.add(comment);
            return comment;
        });
        when(taskService.getProcessInstanceComments(any(), any())).thenAnswer(invocation ->
        {
            String processInstanceId = invocation.getArgument(0);
            String commentType = invocation.getArgument(1);
            return persistedComments.stream()
                    .filter(comment -> processInstanceId.equals(comment.getProcessInstanceId()))
                    .filter(comment -> commentType.equals(comment.getType()))
                    .toList();
        });
        when(identityResolver.resolveCurrentIdentity()).thenReturn(actor);
        when(engineOperations.writeAsCurrentUser(any(Function.class))).thenAnswer(invocation ->
                ((Function<WorkflowCurrentIdentity, ?>) invocation.getArgument(0)).apply(actor));
        when(engineOperations.withConcurrencyConflictSubCode(
                any(RuntimeException.class), anyString())).thenAnswer(invocation ->
                        invocation.getArgument(0));
        when(engineOperations.read(any(Supplier.class))).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(0)).get());
        when(engineOperations.readWithServiceExceptionHandler(
                any(Supplier.class), any(Function.class))).thenAnswer(invocation ->
        {
            Supplier<?> action = invocation.getArgument(0);
            Function<ServiceException, ?> exceptionHandler = invocation.getArgument(1);
            try
            {
                return action.get();
            }
            catch (ServiceException exception)
            {
                return exceptionHandler.apply(exception);
            }
        });
        when(attachmentService.prepareTaskVariables(any(), any(), anyMap(), anyMap()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        when(taskCopyService.prepare(any(), any(), any(), any()))
                .thenReturn(WorkflowTaskCopyService.CopyPlan.empty());
        when(nextTaskAssignmentService.prepare(any(), any()))
                .thenReturn(WorkflowNextTaskAssignmentService.AssignmentPlan.empty());
        when(multiInstanceService.reserveCompletionRevision(any(), any(), any()))
                .thenReturn(WorkflowMultiInstanceService.CompletionRevision.none());
        lifecycleService = new WorkflowTaskLifecycleService(engineOperations, identityResolver,
                repositoryService, runtimeService, taskService, historyService,
                artifactRepository, variableValidator, attachmentService,
                 new WorkflowTaskMovementPolicy(), taskCopyService,
                 nextTaskAssignmentService, multiInstanceService, controlledLoopService);
    }

    /**
     * 验证完成任务使用部署快照校验后的变量且先写入结构化正常审批意见。
     *
     * @return 无返回值，变量、作用域或审计不符合约束时测试失败
     * @throws Exception 审计 JSON 解析失败时抛出
     */
    @Test
    void completesCurrentAssigneeTaskWithValidatedSnapshotVariables() throws Exception
    {
        Task task = activeTask(TASK_ID, "review", ACTOR_ID);
        stubActiveTask(task);
        stubActiveInstance(INSTANCE_ID, ACTOR_ID);
        BpmnFixture fixture = bpmnFixture("review", "审核", "key_20");
        FormProperty permissionDefault = new FormProperty();
        permissionDefault.setId("approva_permission_default");
        permissionDefault.setType("string");
        permissionDefault.setReadable(true);
        permissionDefault.setWriteable(true);
        permissionDefault.setRequired(false);
        // 正式模板节点携带权限描述时，运行表单来源仍必须解析为部署 formKey 快照。
        fixture.currentTask().setFormProperties(List.of(permissionDefault));
        stubDefinition(fixture.model());
        WfDeployForm snapshot = snapshot("review", "key_20", "{\"fields\":[]}");
        when(artifactRepository.selectForms("deployment-1")).thenReturn(List.of(snapshot));
        when(variableValidator.validateForStart(snapshot.getContent(), Map.of("approved", true)))
                .thenReturn(new WorkflowValidatedStartVariables(
                        Map.of("approved", true), Map.of()));
        when(variableValidator.readableFieldNames(snapshot.getContent()))
                .thenReturn(Set.of("readonlyCode", "approved"));
        when(runtimeService.getVariables(INSTANCE_ID, Set.of("readonlyCode", "approved")))
                .thenReturn(Map.of("readonlyCode", "R-001", "approved", false));
        WorkflowTaskCopyService.CopyPlan copyPlan = new WorkflowTaskCopyService.CopyPlan(
                List.of(new WfCopy()));
        WorkflowNextTaskAssignmentService.AssignmentPlan assignmentPlan =
                new WorkflowNextTaskAssignmentService.AssignmentPlan(
                        INSTANCE_ID, TASK_ID, "finalReview", List.of("8"));
        when(taskCopyService.prepare(WorkflowTaskCopyAction.COMPLETE, task, actor,
                List.of(9L))).thenReturn(copyPlan);
        when(nextTaskAssignmentService.prepare(task, List.of(8L)))
                .thenReturn(assignmentPlan);

        lifecycleService.completeTask(new WorkflowTaskCompleteRequest(
                TASK_ID, "同意", Map.of("approved", true), List.of(9L), List.of(8L)));

        verify(taskService).complete(TASK_ID, ACTOR_ID, Map.of("approved", true), false);
        verify(attachmentService).prepareTaskVariables(ACTOR_ID, INSTANCE_ID,
                Map.of("approved", true), Map.of());
        verify(attachmentService).bindTaskAttachments(ACTOR_ID, INSTANCE_ID,
                TASK_ID, "review", Map.of());
        ArgumentCaptor<Object> submissionCaptor = ArgumentCaptor.forClass(Object.class);
        verify(taskService).setVariableLocal(eq(TASK_ID),
                eq(WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME),
                submissionCaptor.capture());
        assertThat(submissionCaptor.getValue()).isInstanceOf(String.class);
        var submission = WorkflowFormSubmissionSnapshotCodec.decode(
                (String) submissionCaptor.getValue());
        assertThat(submission.taskId()).isEqualTo(TASK_ID);
        assertThat(submission.nodeKey()).isEqualTo("review");
        assertThat(submission.values().get("approved").booleanValue()).isTrue();
        assertThat(submission.values().get("readonlyCode").textValue()).isEqualTo("R-001");
        JsonNode audit = capturedAudit("1");
        assertThat(audit.path("action").asText()).isEqualTo("COMPLETE");
        assertThat(audit.path("actorUserId").asText()).isEqualTo(ACTOR_ID);
        assertThat(audit.path("opinion").asText()).isEqualTo("同意");
        verify(taskCopyService).prepare(WorkflowTaskCopyAction.COMPLETE, task, actor,
                List.of(9L));
        verify(nextTaskAssignmentService).prepare(task, List.of(8L));
        InOrder postCompleteOrder = inOrder(taskService, nextTaskAssignmentService,
                taskCopyService);
        postCompleteOrder.verify(taskService).complete(
                TASK_ID, ACTOR_ID, Map.of("approved", true), false);
        postCompleteOrder.verify(nextTaskAssignmentService).apply(assignmentPlan);
        postCompleteOrder.verify(taskCopyService).persist(copyPlan);
    }

    /**
     * 验证内嵌任务表单只按部署快照校验，模型字段变化不会改变在途实例的提交协议。
     *
     * @return 无返回值，冻结内容、来源类型和空 formId 任一漂移时测试失败
     */
    @Test
    void completesEmbeddedTaskAgainstFrozenSnapshotAfterModelPropertiesChange()
    {
        Task task = activeTask(TASK_ID, "review", ACTOR_ID);
        stubActiveTask(task);
        stubActiveInstance(INSTANCE_ID, ACTOR_ID);
        BpmnFixture fixture = bpmnFixture("review", "审核", null);
        FormProperty changedProperty = new FormProperty();
        changedProperty.setId("changedAfterDeployment");
        changedProperty.setVariable("changedAfterDeployment");
        changedProperty.setType("string");
        fixture.currentTask().setFormProperties(List.of(changedProperty));
        stubDefinition(fixture.model());

        String frozenContent = "{\"fields\":[{\"__vModel__\":\"originalField\","
                + "\"__config__\":{\"layout\":\"colFormItem\",\"tag\":\"el-input\","
                + "\"workflowReadable\":true,\"workflowWritable\":true}}]}";
        WfDeployForm snapshot = snapshot("review", "embedded", frozenContent);
        snapshot.setSourceType("EMBEDDED");
        snapshot.setFormId(null);
        when(artifactRepository.selectForms("deployment-1"))
                .thenReturn(List.of(snapshot));
        when(variableValidator.validateForStart(frozenContent,
                Map.of("originalField", "部署时字段值")))
                .thenReturn(new WorkflowValidatedStartVariables(
                        Map.of("originalField", "部署时字段值"), Map.of()));

        lifecycleService.completeTask(new WorkflowTaskCompleteRequest(
                TASK_ID, "同意", Map.of("originalField", "部署时字段值"),
                List.of(), List.of()));

        verify(variableValidator).validateForStart(frozenContent,
                Map.of("originalField", "部署时字段值"));
        ArgumentCaptor<Object> submissionCaptor = ArgumentCaptor.forClass(Object.class);
        verify(taskService).setVariableLocal(eq(TASK_ID),
                eq(WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME),
                submissionCaptor.capture());
        var submission = WorkflowFormSubmissionSnapshotCodec.decode(
                (String) submissionCaptor.getValue());
        assertThat(submission.sourceType()).isEqualTo("EMBEDDED");
        assertThat(submission.formId()).isNull();
        assertThat(submission.formKey()).isEqualTo("embedded");
        assertThat(submission.values().get("originalField").textValue())
                .isEqualTo("部署时字段值");
    }

    /**
     * 验证动态多实例完成先占用 expectedRevision，再写入带版本区间的审计并完成任务。
     *
     * @return 无返回值；revision CAS 顺序、审计字段或任务完成任一缺失时测试失败
     * @throws Exception 审计 JSON 解析失败时抛出
     */
    @Test
    void completesDynamicMultiInstanceTaskWithRevisionFirstAudit() throws Exception
    {
        Task task = activeTask(TASK_ID, "review", ACTOR_ID);
        stubActiveTask(task);
        stubActiveInstance(INSTANCE_ID, ACTOR_ID);
        BpmnFixture fixture = bpmnFixture("review", "审核", "key_20");
        stubDefinition(fixture.model());
        WfDeployForm snapshot = snapshot("review", "key_20", "{\"fields\":[]}");
        when(artifactRepository.selectForms("deployment-1"))
                .thenReturn(List.of(snapshot));
        when(variableValidator.validateForStart(snapshot.getContent(), Map.of()))
                .thenReturn(new WorkflowValidatedStartVariables(Map.of(), Map.of()));
        WorkflowMultiInstanceService.CompletionRevision revision =
                new WorkflowMultiInstanceService.CompletionRevision("review", 4, 5);
        when(multiInstanceService.reserveCompletionRevision(task, 4L, actor))
                .thenReturn(revision);

        lifecycleService.completeTask(new WorkflowTaskCompleteRequest(
                TASK_ID, "会签通过", Map.of(), List.of(), List.of(), 4L));

        InOrder order = inOrder(multiInstanceService, taskService);
        order.verify(multiInstanceService).reserveCompletionRevision(task, 4L, actor);
        order.verify(taskService).addComment(eq(TASK_ID), eq(INSTANCE_ID), eq("1"), any());
        order.verify(taskService).complete(TASK_ID, ACTOR_ID, Map.of(), false);
        JsonNode audit = capturedAudit("1");
        assertThat(audit.path("action").asText()).isEqualTo("COMPLETE");
        assertThat(audit.path("multiInstanceActivityId").asText()).isEqualTo("review");
        assertThat(audit.path("beforeRevision").asInt()).isEqualTo(4);
        assertThat(audit.path("afterRevision").asInt()).isEqualTo(5);
    }

    /**
     * 验证动态多实例完成在事务代理提交阶段失败时会调用专用 revision 冲突附码边界。
     *
     * @return 无返回值；提交异常未附码、被吞掉或换成无关异常时测试失败
     */
    @Test
    void tagsRetryableCommitFailureForDynamicMultiInstanceCompletion()
    {
        RuntimeException commitFailure = new RuntimeException("commit conflict");
        ServiceException taggedFailure = new ServiceException(
                "工作流状态已发生变化，请刷新后重试", HttpStatus.CONFLICT)
                .setSubCode(WorkflowMultiInstanceService.REVISION_CONFLICT_SUB_CODE);
        when(engineOperations.writeAsCurrentUser(any(Function.class)))
                .thenThrow(commitFailure);
        when(engineOperations.withConcurrencyConflictSubCode(commitFailure,
                WorkflowMultiInstanceService.REVISION_CONFLICT_SUB_CODE))
                .thenReturn(taggedFailure);

        WorkflowTaskCompleteRequest request = new WorkflowTaskCompleteRequest(
                TASK_ID, "并发完成", Map.of(), List.of(), List.of(), 4L);

        assertThatThrownBy(() -> lifecycleService.completeTask(request))
                .isSameAs(taggedFailure);
        verify(engineOperations).withConcurrencyConflictSubCode(commitFailure,
                WorkflowMultiInstanceService.REVISION_CONFLICT_SUB_CODE);
    }

    /**
     * 验证普通任务完成的提交失败保持原异常且不携带动态多实例 revision 子码。
     *
     * @return 无返回值；普通完成误用多实例冲突分类时测试失败
     */
    @Test
    void preservesCommitFailureForOrdinaryTaskCompletion()
    {
        RuntimeException commitFailure = new RuntimeException("ordinary commit failure");
        when(engineOperations.writeAsCurrentUser(any(Function.class)))
                .thenThrow(commitFailure);
        WorkflowTaskCompleteRequest request = new WorkflowTaskCompleteRequest(
                TASK_ID, "普通完成", Map.of(), List.of(), List.of());

        assertThatThrownBy(() -> lifecycleService.completeTask(request))
                .isSameAs(commitFailure);
        verify(engineOperations, never()).withConcurrencyConflictSubCode(any(), anyString());
    }

    /**
     * 验证带附件的任务先安全投影，再依次写意见、绑定附件并完成真实任务。
     * @return void，调用参数或事务内顺序不符合时测试失败
     */
    @Test
    void preparesBindsAndCompletesTaskAttachmentsInTransactionOrder()
    {
        String attachmentId = "d9428888-122b-4c6f-8f0c-9c3e1dbd3210";
        Task task = activeTask(TASK_ID, "review", ACTOR_ID);
        stubActiveTask(task);
        stubActiveInstance(INSTANCE_ID, ACTOR_ID);
        BpmnFixture fixture = bpmnFixture("review", "审核", "key_20");
        stubDefinition(fixture.model());
        WfDeployForm snapshot = snapshot("review", "key_20", "{\"fields\":[]}");
        when(artifactRepository.selectForms("deployment-1"))
                .thenReturn(List.of(snapshot));
        Map<String, List<String>> references = Map.of("files", List.of(attachmentId));
        Map<String, Object> normalized = Map.of("files", List.of(attachmentId));
        Map<String, Object> projected = Map.of("files", List.of(
                Map.of("attachmentId", attachmentId, "fieldName", "files")));
        when(variableValidator.validateForStart(snapshot.getContent(), normalized))
                .thenReturn(new WorkflowValidatedStartVariables(normalized, references));
        when(attachmentService.prepareTaskVariables(ACTOR_ID, INSTANCE_ID,
                normalized, references)).thenReturn(projected);

        lifecycleService.completeTask(new WorkflowTaskCompleteRequest(
                TASK_ID, "附件通过", normalized));

        InOrder order = inOrder(attachmentService, taskService);
        order.verify(attachmentService).prepareTaskVariables(
                ACTOR_ID, INSTANCE_ID, normalized, references);
        order.verify(taskService).addComment(eq(TASK_ID), eq(INSTANCE_ID), eq("1"), any());
        order.verify(attachmentService).bindTaskAttachments(
                ACTOR_ID, INSTANCE_ID, TASK_ID, "review", references);
        order.verify(taskService).setVariableLocal(eq(TASK_ID),
                eq(WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME), any());
        order.verify(taskService).complete(TASK_ID, ACTOR_ID, projected, false);
    }

    /**
     * 验证附件预检失败发生在意见和任务写入之前。
     * @return void，附件预检失败仍写入 Flowable 时测试失败
     */
    @Test
    void stopsBeforeFlowableWritesWhenTaskAttachmentPreparationFails()
    {
        Task task = activeTask(TASK_ID, "review", ACTOR_ID);
        stubActiveTask(task);
        stubActiveInstance(INSTANCE_ID, ACTOR_ID);
        BpmnFixture fixture = bpmnFixture("review", "审核", "key_20");
        stubDefinition(fixture.model());
        WfDeployForm snapshot = snapshot("review", "key_20", "{\"fields\":[]}");
        when(artifactRepository.selectForms("deployment-1"))
                .thenReturn(List.of(snapshot));
        when(variableValidator.validateForStart(snapshot.getContent(), Map.of()))
                .thenReturn(new WorkflowValidatedStartVariables(Map.of(), Map.of()));
        when(attachmentService.prepareTaskVariables(ACTOR_ID, INSTANCE_ID,
                Map.of(), Map.of())).thenThrow(new ServiceException(
                        "工作流附件状态已变化或已过期", HttpStatus.CONFLICT));

        assertBusinessError(() -> lifecycleService.completeTask(
                new WorkflowTaskCompleteRequest(TASK_ID, "失败", Map.of())),
                HttpStatus.CONFLICT);

        verify(taskService, never()).addComment(any(), any(), any(), any());
        verify(attachmentService, never()).bindTaskAttachments(
                any(), any(), any(), any(), anyMap());
        verify(taskService, never()).complete(any(), any(), anyMap(), anyBoolean());
    }

    /**
     * 验证附件绑定异常发生在完成任务之前，供外层事务回滚已写意见。
     * @return void，绑定失败后仍调用任务完成时测试失败
     */
    @Test
    void doesNotCompleteTaskWhenAttachmentBindingFails()
    {
        Task task = activeTask(TASK_ID, "review", ACTOR_ID);
        stubActiveTask(task);
        stubActiveInstance(INSTANCE_ID, ACTOR_ID);
        BpmnFixture fixture = bpmnFixture("review", "审核", "key_20");
        stubDefinition(fixture.model());
        WfDeployForm snapshot = snapshot("review", "key_20", "{\"fields\":[]}");
        when(artifactRepository.selectForms("deployment-1"))
                .thenReturn(List.of(snapshot));
        when(variableValidator.validateForStart(snapshot.getContent(), Map.of()))
                .thenReturn(new WorkflowValidatedStartVariables(Map.of(), Map.of()));
        doThrow(new ServiceException("工作流附件状态已变化或已过期",
                HttpStatus.CONFLICT)).when(attachmentService).bindTaskAttachments(
                        ACTOR_ID, INSTANCE_ID, TASK_ID, "review", Map.of());

        assertBusinessError(() -> lifecycleService.completeTask(
                new WorkflowTaskCompleteRequest(TASK_ID, "失败", Map.of())),
                HttpStatus.CONFLICT);

        verify(taskService).addComment(eq(TASK_ID), eq(INSTANCE_ID), eq("1"), any());
        verify(taskService, never()).complete(any(), any(), anyMap(), anyBoolean());
    }

    /**
     * 验证服务不会在完成前强制修改办理人，非当前 assignee 返回对象级拒绝。
     *
     * @return 无返回值，越权完成触发引擎写入时测试失败
     */
    @Test
    void rejectsCompleteForNonAssigneeWithoutChangingAssignee()
    {
        Task task = activeTask(TASK_ID, "review", "8");
        stubActiveTask(task);
        stubActiveInstance(INSTANCE_ID, ACTOR_ID);

        assertBusinessError(() -> lifecycleService.completeTask(
                new WorkflowTaskCompleteRequest(TASK_ID, "越权", Map.of())),
                HttpStatus.FORBIDDEN);

        verify(taskService, never()).setAssignee(any(), any());
        verify(taskService, never()).complete(any());
        verify(taskService, never()).complete(any(), any(), anyMap(), anyBoolean());
    }

    /**
     * 验证已结束任务的重复完成请求返回状态冲突而不是对象不存在。
     *
     * @return 无返回值，重复提交状态码错误时测试失败
     */
    @Test
    void mapsDuplicateCompleteToConflict()
    {
        HistoricTaskInstance historicTask = mock(HistoricTaskInstance.class);
        when(taskService.createTaskQuery().taskId(TASK_ID).active().singleResult()).thenReturn(null);
        when(taskService.createTaskQuery().taskId(TASK_ID).singleResult()).thenReturn(null);
        when(historyService.createHistoricTaskInstanceQuery().taskId(TASK_ID).singleResult())
                .thenReturn(historicTask);

        assertBusinessError(() -> lifecycleService.completeTask(
                new WorkflowTaskCompleteRequest(TASK_ID, "重复", Map.of())),
                HttpStatus.CONFLICT);
    }

    /**
     * 验证流程发起人取消时同时写入状态、所有活动任务意见和结构化历史删除原因。
     *
     * @return 无返回值，取消链路存在缺失写入时测试失败
     * @throws Exception 删除原因 JSON 解析失败时抛出
     */
    @Test
    void cancelsActiveProcessForStarterWithStructuredAudit() throws Exception
    {
        ProcessInstance instance = stubActiveInstance(INSTANCE_ID, ACTOR_ID);
        Task activeTask = activeTask(TASK_ID, "review", "8");
        when(executionQuery.list()).thenReturn(List.of());
        when(taskQuery.list()).thenReturn(List.of(activeTask));
        HistoricProcessInstance historicRoot = mock(HistoricProcessInstance.class);
        when(historicRoot.getEndTime()).thenReturn(new Date());
        when(historicRoot.getBusinessStatus()).thenReturn("canceled");
        when(historicProcessQuery.singleResult()).thenReturn(historicRoot);

        lifecycleService.cancelProcess(new WorkflowProcessCancelRequest(INSTANCE_ID, "申请有误"));

        verify(runtimeService).setVariable(INSTANCE_ID, "processStatus", "canceled");
        verify(runtimeService).updateBusinessStatus(INSTANCE_ID, "canceled");
        verify(taskService).addComment(eq(TASK_ID), eq(INSTANCE_ID), eq("6"), any());
        ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);
        verify(runtimeService).deleteProcessInstance(eq(INSTANCE_ID), reasonCaptor.capture());
        JsonNode audit = objectMapper.readTree(reasonCaptor.getValue());
        assertThat(audit.path("action").asText()).isEqualTo("CANCEL");
        assertThat(audit.path("actorUserId").asText()).isEqualTo(ACTOR_ID);
        assertThat(audit.path("opinion").asText()).isEqualTo("申请有误");
        assertThat(instance.getId()).isEqualTo(INSTANCE_ID);
    }

    /**
     * 验证直接传入 CallActivity 子实例时，取消会提升到根业务实例并清理完整执行树。
     *
     * @return 无返回值，仅删除子实例、根终态或子任务审计错误时测试失败
     */
    @Test
    void cancelsRootBusinessInstanceFromCallActivityChildRequest()
    {
        String childInstanceId = "child-instance-1";
        ProcessInstance childInstance = mock(ProcessInstance.class);
        when(childInstance.getId()).thenReturn(childInstanceId);
        when(childInstance.getRootProcessInstanceId()).thenReturn(INSTANCE_ID);
        when(childInstance.getSuperExecutionId()).thenReturn("call-execution-1");
        when(childInstance.isSuspended()).thenReturn(false);
        ProcessInstance rootInstance = mock(ProcessInstance.class);
        when(rootInstance.getId()).thenReturn(INSTANCE_ID);
        when(rootInstance.getStartUserId()).thenReturn(ACTOR_ID);
        when(rootInstance.getRootProcessInstanceId()).thenReturn(INSTANCE_ID);
        when(rootInstance.getSuperExecutionId()).thenReturn(null);
        when(rootInstance.isSuspended()).thenReturn(false);
        when(processInstanceQuery.singleResult()).thenReturn(childInstance, rootInstance);
        when(processInstanceQuery.list()).thenReturn(List.of(rootInstance, childInstance));

        // 执行查询中的子实例用于冻结完整树，不能被当作独立取消目标。
        Execution childExecution = mock(Execution.class);
        when(childExecution.getProcessInstanceId()).thenReturn(childInstanceId);
        when(childExecution.getRootProcessInstanceId()).thenReturn(INSTANCE_ID);
        when(executionQuery.list()).thenReturn(List.of(childExecution));
        Task childTask = activeTask(TASK_ID, "childReview", "8");
        when(childTask.getProcessInstanceId()).thenReturn(childInstanceId);
        when(taskQuery.list()).thenReturn(List.of(childTask));

        HistoricProcessInstance historicRoot = mock(HistoricProcessInstance.class);
        when(historicRoot.getEndTime()).thenReturn(new Date());
        when(historicRoot.getBusinessStatus()).thenReturn("canceled");
        when(historicProcessQuery.singleResult()).thenReturn(historicRoot);

        lifecycleService.cancelProcess(new WorkflowProcessCancelRequest(
                childInstanceId, "子流程入口取消整体"));

        verify(runtimeService).setVariable(INSTANCE_ID, "processStatus", "canceled");
        verify(runtimeService).updateBusinessStatus(INSTANCE_ID, "canceled");
        verify(taskService).addComment(eq(TASK_ID), eq(childInstanceId), eq("6"), any());
        verify(runtimeService).deleteProcessInstance(eq(INSTANCE_ID), any());
        verify(runtimeService, never()).deleteProcessInstance(eq(childInstanceId), any());
    }

    /**
     * 验证普通非发起人即使拥有接口权限也不能取消他人的流程实例。
     *
     * @return 无返回值，越权取消调用删除 API 时测试失败
     */
    @Test
    void rejectsCancelForUnrelatedUser()
    {
        stubActiveInstance(INSTANCE_ID, "8");

        assertBusinessError(() -> lifecycleService.cancelProcess(
                new WorkflowProcessCancelRequest(INSTANCE_ID, "越权取消")),
                HttpStatus.FORBIDDEN);

        verify(runtimeService, never()).deleteProcessInstance(any(), any());
    }

    /**
     * 验证退回执行前使用实例最早审批历史并直接把任务交给发起人修改。
     *
     * @return 无返回值，目标、意见或执行迁移不一致时测试失败
     * @throws Exception 审计 JSON 解析失败时抛出
     */
    @Test
    void returnsSingleExecutionToRecomputedHistoricNode() throws Exception
    {
        Date currentCreateTime = new Date(2_000L);
        Task task = activeTask(TASK_ID, "review", ACTOR_ID);
        when(task.getCreateTime()).thenReturn(currentCreateTime);
        stubActiveTask(task);
        stubActiveInstance(INSTANCE_ID, ACTOR_ID);
        stubSingleExecution(task);
        // 退回命令前返回原任务，写后对账必须只看到目标节点生成的全新活动任务。
        Task returnedTask = activeTask("returned-apply", "apply", ACTOR_ID,
                "execution-returned");
        when(taskService.createTaskQuery().processInstanceId(INSTANCE_ID).active().list())
                .thenReturn(List.of(task), List.of(returnedTask));
        BpmnFixture fixture = twoTaskFixture();
        stubDefinition(fixture.model());
        HistoricTaskInstance sourceHistory = historicTask("historic-apply", "apply",
                new Date(1_000L), ACTOR_ID, null);
        when(historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(INSTANCE_ID)
                .orderByHistoricTaskInstanceStartTime().asc().listPage(0, 500))
                .thenReturn(List.of(sourceHistory));
        when(taskService.getIdentityLinksForTask("returned-apply")).thenReturn(List.of());
        when(taskService.getVariableLocal("returned-apply",
                WorkflowTaskLifecycleService.RETURN_APPLICANT_VARIABLE)).thenReturn(ACTOR_ID);
        when(taskQuery.singleResult()).thenReturn(task, returnedTask);
        when(runtimeService.getVariable(INSTANCE_ID, "processStatus"))
                .thenReturn("running", "returned");
        ChangeActivityStateBuilder builder = stateBuilder();
        WorkflowTaskCopyService.CopyPlan copyPlan = new WorkflowTaskCopyService.CopyPlan(
                List.of(new WfCopy()));
        when(taskCopyService.prepare(WorkflowTaskCopyAction.RETURN, task, actor,
                List.of(9L))).thenReturn(copyPlan);

        lifecycleService.returnTask(new WorkflowTaskReturnRequest(
                TASK_ID, "资料需补充", List.of(9L)));

        verify(builder).moveExecutionToActivityId("execution-1", "apply");
        JsonNode audit = capturedAudit("2");
        assertThat(audit.path("action").asText()).isEqualTo("RETURN");
        assertThat(audit.path("targetNodeKey").asText()).isEqualTo("apply");
        assertThat(audit.has("sourceTaskId")).isFalse();
        verify(taskCopyService).persist(copyPlan);
        verify(taskService).setAssignee("returned-apply", ACTOR_ID);
        verify(runtimeService).updateBusinessStatus(INSTANCE_ID, "returned");
    }

    /**
     * 验证并行审批不能合并成单一首审任务，避免重新提交后丢失原审批分支。
     *
     * @return 无返回值，并行结构发生审计、复制或状态迁移副作用时测试失败
     */
    @Test
    void rejectsReturnWhenProcessHasParallelActiveTasks()
    {
        Task sourceTask = activeTask(TASK_ID, "review", ACTOR_ID);
        Task siblingTask = activeTask("task-2", "finance", "8");
        stubActiveTask(sourceTask);
        stubActiveInstance(INSTANCE_ID, ACTOR_ID);
        when(runtimeService.getVariable(INSTANCE_ID, "processStatus")).thenReturn("running");
        when(taskService.createTaskQuery().processInstanceId(INSTANCE_ID).active().list())
                .thenReturn(List.of(sourceTask, siblingTask));
        BpmnFixture fixture = twoTaskFixture();
        stubDefinition(fixture.model());
        HistoricTaskInstance sourceHistory = historicTask("historic-apply", "apply",
                new Date(1_000L), ACTOR_ID, null);
        when(historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(INSTANCE_ID)
                .orderByHistoricTaskInstanceStartTime().asc().listPage(0, 500))
                .thenReturn(List.of(sourceHistory));

        assertBusinessError(() -> lifecycleService.returnTask(
                new WorkflowTaskReturnRequest(TASK_ID, "资料需补充")),
                HttpStatus.CONFLICT);

        verify(runtimeService, never()).createChangeActivityStateBuilder();
        verify(taskCopyService, never()).prepare(any(), any(), any(), any());
        verify(taskService, never()).addComment(any(), any(), any(), any());
    }

    /**
     * 验证发起人重新提交会校验原开始表单、绑定附件并恢复首审批办理配置和运行状态。
     *
     * @return 无返回值，任一正式持久化、副作用或审计契约缺失时测试失败
     * @throws Exception 审计 JSON 解析失败时抛出
     */
    @Test
    void resubmitsReturnedApplicationAndRestoresFirstApprovalAssignment() throws Exception
    {
        String attachmentId = "47e812d0-ae8f-43d1-aea2-232b16244ad2";
        Task task = activeTask(TASK_ID, "apply", ACTOR_ID);
        when(task.getAssignee()).thenReturn(ACTOR_ID, "8");
        stubActiveTask(task);
        ProcessInstance instance = stubActiveInstance(INSTANCE_ID, ACTOR_ID);
        when(instance.getDeploymentId()).thenReturn("deployment-1");
        when(runtimeService.getVariable(INSTANCE_ID, "processStatus"))
                .thenReturn("returned", "running");
        when(taskService.getVariableLocal(TASK_ID, "__ruoyi_workflow_return_assignment"))
                .thenReturn("{\"assignee\":\"8\",\"owner\":\"6\","
                        + "\"candidateUserIds\":[\"9\"],"
                        + "\"candidateGroupIds\":[\"finance\"]}");
        when(taskService.getVariableLocal(TASK_ID,
                WorkflowTaskLifecycleService.RETURN_APPLICANT_VARIABLE)).thenReturn(ACTOR_ID);

        BpmnFixture fixture = bpmnFixture("apply", "申请", null);
        StartEvent startEvent = new StartEvent();
        startEvent.setId("start");
        fixture.process().addFlowElement(startEvent);
        stubDefinition(fixture.model());
        HistoricActivityInstance historicStart = mock(HistoricActivityInstance.class);
        when(historicStart.getActivityId()).thenReturn("start");
        when(historicActivityQuery.listPage(0, 2)).thenReturn(List.of(historicStart));
        WfDeployForm snapshot = snapshot("start", "start_form", "{\"fields\":[]}");
        when(artifactRepository.selectForms("deployment-1"))
                .thenReturn(List.of(snapshot));

        Map<String, Object> submitted = Map.of("amount", 1200,
                "files", List.of(attachmentId));
        // description 未在本次补丁出现，重新提交后必须继续保留其正式旧值。
        Map<String, Object> previous = Map.of("amount", 900, "description", "旧值");
        when(runtimeService.getVariable(INSTANCE_ID,
                WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME)).thenReturn(
                        WorkflowFormSubmissionSnapshotCodec.encodeStart(
                                "deployment-1", snapshot.getFormId(), snapshot.getFormKey(),
                                snapshot.getNodeKey(), previous));
        Map<String, List<String>> references = Map.of("files", List.of(attachmentId));
        Map<String, Object> projected = Map.of("amount", 1200,
                "files", List.of(Map.of("attachmentId", attachmentId)));
        when(variableValidator.validatePatch(eq(snapshot.getContent()), eq(submitted), anyMap()))
                .thenReturn(new WorkflowValidatedStartVariables(submitted, references));
        when(attachmentService.prepareTaskVariables(ACTOR_ID, INSTANCE_ID,
                submitted, references)).thenReturn(projected);
        when(taskQuery.singleResult()).thenReturn(task, task);

        lifecycleService.resubmitApplication(new WorkflowApplicationResubmitRequest(
                TASK_ID, submitted));

        verify(attachmentService).bindTaskAttachments(ACTOR_ID, INSTANCE_ID, TASK_ID,
                "start", references);
        verify(runtimeService).setVariables(INSTANCE_ID, projected);
        verify(runtimeService, never()).removeVariables(any(), any());
        verify(taskService).setOwner(TASK_ID, "6");
        verify(taskService).setAssignee(TASK_ID, "8");
        verify(taskService).addCandidateUser(TASK_ID, "9");
        verify(taskService).addCandidateGroup(TASK_ID, "finance");
        verify(taskService).removeVariableLocal(
                TASK_ID, "__ruoyi_workflow_return_assignment");
        verify(taskService).removeVariableLocal(
                TASK_ID, WorkflowTaskLifecycleService.RETURN_APPLICANT_VARIABLE);
        verify(runtimeService).updateBusinessStatus(INSTANCE_ID, "running");
        JsonNode audit = capturedAudit("1");
        assertThat(audit.path("action").asText()).isEqualTo("RESUBMIT");
        assertThat(audit.path("opinion").asText()).isEqualTo("申请人修改原表单后重新提交");
    }

    /**
     * 验证退回只能由原发起人重新提交，当前任务被误分配也不能越权修改原表单。
     *
     * @return 无返回值，非发起人未收到 403 或发生任何写入时测试失败
     */
    @Test
    void forbidsResubmitByNonApplicant()
    {
        Task task = activeTask(TASK_ID, "apply", ACTOR_ID);
        stubActiveTask(task);
        stubActiveInstance(INSTANCE_ID, "8");

        assertBusinessError(() -> lifecycleService.resubmitApplication(
                new WorkflowApplicationResubmitRequest(TASK_ID, Map.of())),
                HttpStatus.FORBIDDEN);

        verify(taskService, never()).getVariableLocal(any(), any());
        verify(runtimeService, never()).setVariables(any(), anyMap());
    }

    /**
     * 验证仅 returned 状态允许重新提交，防止重复提交或运行中任务绕过正常审批动作。
     *
     * @return 无返回值，非法状态未收到 409 或发生任何写入时测试失败
     */
    @Test
    void rejectsResubmitWhenProcessIsNotReturned()
    {
        Task task = activeTask(TASK_ID, "apply", ACTOR_ID);
        stubActiveTask(task);
        stubActiveInstance(INSTANCE_ID, ACTOR_ID);
        when(runtimeService.getVariable(INSTANCE_ID, "processStatus")).thenReturn("running");

        assertBusinessError(() -> lifecycleService.resubmitApplication(
                new WorkflowApplicationResubmitRequest(TASK_ID, Map.of())),
                HttpStatus.CONFLICT);

        verify(taskService, never()).getVariableLocal(any(), any());
        verify(runtimeService, never()).setVariables(any(), anyMap());
    }

    /**
     * 验证并行活动任务由当前办理人整实例驳回，并为全部 sibling 保留同一审计意见。
     *
     * @return 无返回值，存在活动任务残留、终态错误或使用单 execution 迁移时测试失败
     */
    @Test
    void rejectsWholeInstanceWhenProcessHasParallelActiveTasks()
    {
        Task task = activeTask(TASK_ID, "review", ACTOR_ID);
        Task parallelTask = activeTask("task-2", "finance", "8");
        stubActiveTask(task);
        stubActiveInstance(INSTANCE_ID, ACTOR_ID);
        when(taskService.createTaskQuery().processInstanceId(INSTANCE_ID).active().list())
                .thenReturn(List.of(task, parallelTask));
        HistoricProcessInstance historicInstance = mock(HistoricProcessInstance.class);
        when(historicInstance.getEndTime()).thenReturn(new Date());
        when(historicInstance.getBusinessStatus()).thenReturn("rejected");
        when(historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(INSTANCE_ID).singleResult()).thenReturn(historicInstance);

        lifecycleService.rejectTask(new WorkflowTaskRejectRequest(TASK_ID, "驳回"));

        verify(runtimeService).setVariable(INSTANCE_ID, "processStatus", "rejected");
        verify(runtimeService).updateBusinessStatus(INSTANCE_ID, "rejected");
        verify(taskService).addComment(eq(TASK_ID), eq(INSTANCE_ID), eq("3"), any());
        verify(taskService).addComment(eq("task-2"), eq(INSTANCE_ID), eq("3"), any());
        verify(runtimeService).deleteProcessInstance(eq(INSTANCE_ID), any());
        verify(runtimeService, never()).createChangeActivityStateBuilder();
    }

    /**
     * 验证在 CallActivity 子流程任务上驳回时删除根业务实例，而不是只删除被调用子实例。
     *
     * @return 无返回值，根实例、子任务意见或整树清理目标不符合契约时测试失败
     */
    @Test
    void rejectsRootBusinessInstanceFromCallActivityChildTask()
    {
        String childInstanceId = "child-instance-1";
        Task childTask = activeTask(TASK_ID, "childReview", ACTOR_ID);
        when(childTask.getProcessInstanceId()).thenReturn(childInstanceId);
        stubActiveTask(childTask);

        ProcessInstance childInstance = mock(ProcessInstance.class);
        when(childInstance.getId()).thenReturn(childInstanceId);
        when(childInstance.getRootProcessInstanceId()).thenReturn(INSTANCE_ID);
        when(childInstance.getSuperExecutionId()).thenReturn("call-execution-1");
        when(childInstance.isSuspended()).thenReturn(false);
        ProcessInstance rootInstance = mock(ProcessInstance.class);
        when(rootInstance.getId()).thenReturn(INSTANCE_ID);
        when(rootInstance.getRootProcessInstanceId()).thenReturn(INSTANCE_ID);
        when(rootInstance.getSuperExecutionId()).thenReturn(null);
        when(rootInstance.isSuspended()).thenReturn(false);
        when(processInstanceQuery.singleResult()).thenReturn(childInstance, rootInstance);
        when(processInstanceQuery.list()).thenReturn(List.of(rootInstance, childInstance));
        Execution childExecution = mock(Execution.class);
        when(childExecution.getProcessInstanceId()).thenReturn(childInstanceId);
        when(childExecution.getRootProcessInstanceId()).thenReturn(INSTANCE_ID);
        when(executionQuery.list()).thenReturn(List.of(childExecution));
        when(taskQuery.list()).thenReturn(List.of(childTask));

        HistoricProcessInstance historicRoot = mock(HistoricProcessInstance.class);
        when(historicRoot.getEndTime()).thenReturn(new Date());
        when(historicRoot.getBusinessStatus()).thenReturn("rejected");
        when(historicProcessQuery.singleResult()).thenReturn(historicRoot);

        lifecycleService.rejectTask(new WorkflowTaskRejectRequest(TASK_ID, "子流程整体驳回"));

        verify(runtimeService).setVariable(INSTANCE_ID, "processStatus", "rejected");
        verify(runtimeService).updateBusinessStatus(INSTANCE_ID, "rejected");
        verify(taskService).addComment(eq(TASK_ID), eq(childInstanceId), eq("3"), any());
        verify(runtimeService).deleteProcessInstance(eq(INSTANCE_ID), any());
        verify(runtimeService, never()).deleteProcessInstance(eq(childInstanceId), any());
    }

    /**
     * 验证已办列表能力使用正式撤回的完整授权、状态和执行树规则，但不产生任何写副作用。
     *
     * @return 无返回值，可撤回快照未返回 true 或能力查询发生写入时测试失败
     */
    @Test
    void reportsRevocableOnlyAfterFullReadOnlyPreparation()
    {
        HistoricTaskInstance completed = historicTask("historic-apply", "apply",
                new Date(1_000L), ACTOR_ID, null);
        when(completed.getProcessInstanceId()).thenReturn(INSTANCE_ID);
        when(historyService.createHistoricTaskInstanceQuery()
                .taskId("historic-apply").finished().singleResult()).thenReturn(completed);
        Task activeTask = activeTask(TASK_ID, "review", "8");
        stubActiveInstance(INSTANCE_ID, ACTOR_ID);
        stubSingleExecution(activeTask);
        when(taskService.createTaskQuery().processInstanceId(INSTANCE_ID).active().list())
                .thenReturn(List.of(activeTask));
        when(historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(INSTANCE_ID).finished().list()).thenReturn(List.of(completed));
        stubDefinition(twoTaskFixture().model());

        assertThat(lifecycleService.isProcessRevocable(INSTANCE_ID, "historic-apply")).isTrue();

        verify(taskService, never()).saveTask(any());
        verify(taskService, never()).addComment(any(), any(), any(), any());
        verify(runtimeService, never()).createChangeActivityStateBuilder();
    }

    /**
     * 验证正式撤回会返回 409 的已处理后继在列表中降级为不可撤回且保持零副作用。
     *
     * @return 无返回值，预期冲突泄漏到列表或错误显示撤回能力时测试失败
     */
    @Test
    void reportsTouchedSuccessorAsNotRevocableWithoutWrite()
    {
        Task successor = activeTask(TASK_ID, "review", "8");
        applyTouchedState(successor, TouchedSuccessorState.CLAIMED);
        stubRejectedRevokeAttempt(successor, twoTaskFixture().model(), List.of());

        assertThat(lifecycleService.isProcessRevocable(INSTANCE_ID, "historic-apply")).isFalse();

        verify(taskService, never()).saveTask(any());
        verify(taskService, never()).addComment(any(), any(), any(), any());
        verify(runtimeService, never()).createChangeActivityStateBuilder();
    }

    /**
     * 验证本人最近完成且后继未处理时，可将当前唯一执行撤回到来源任务。
     *
     * @return 无返回值，撤回目标或结构化意见错误时测试失败
     * @throws Exception 审计 JSON 解析失败时抛出
     */
    @Test
    void revokesLatestCompletedTaskWhenSuccessorIsUntouched() throws Exception
    {
        HistoricTaskInstance completed = historicTask("historic-apply", "apply",
                new Date(1_000L), ACTOR_ID, null);
        when(completed.getProcessInstanceId()).thenReturn(INSTANCE_ID);
        when(historyService.createHistoricTaskInstanceQuery()
                .taskId("historic-apply").finished().singleResult()).thenReturn(completed);
        Task activeTask = activeTask(TASK_ID, "review", "8");
        Task restoredTask = activeTask("restored-apply", "apply", ACTOR_ID,
                "execution-restored");
        Comment createAudit = listenerAuditComment(activeTask, "create");
        Comment assignmentAudit = listenerAuditComment(activeTask, "assignment");
        when(taskService.getTaskComments(TASK_ID))
                .thenReturn(List.of(createAudit, assignmentAudit));
        stubActiveInstance(INSTANCE_ID, ACTOR_ID);
        stubSingleExecution(activeTask);
        when(taskService.createTaskQuery().processInstanceId(INSTANCE_ID).active().list())
                .thenReturn(List.of(activeTask), List.of(activeTask), List.of(restoredTask));
        when(runtimeService.getActiveActivityIds(INSTANCE_ID))
                .thenReturn(List.of("review"), List.of("apply"));
        HistoricTaskInstance successorHistory = historicTask(TASK_ID, "review",
                new Date(3_000L), null, "Change activity to apply");
        when(historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(INSTANCE_ID).finished().list())
                .thenReturn(List.of(completed), List.of(completed, successorHistory));
        BpmnFixture fixture = twoTaskFixture();
        stubDefinition(fixture.model());
        ChangeActivityStateBuilder builder = stateBuilder();

        lifecycleService.revokeProcess(new WorkflowProcessRevokeRequest(
                INSTANCE_ID, "historic-apply", "撤回修改"));

        verify(builder).moveExecutionToActivityId("execution-1", "apply");
        JsonNode audit = capturedAudit("7");
        assertThat(audit.path("action").asText()).isEqualTo("REVOKE");
        assertThat(audit.path("sourceTaskId").asText()).isEqualTo("historic-apply");
    }

    /**
     * 验证多个未处理的直接并行后继使用一次 Flowable 命令原子合并回唯一来源任务。
     *
     * @return 无返回值，逐分支迁移、审计缺失或写后对账不完整时测试失败
     * @throws Exception 结构化撤回意见无法解析时抛出
     */
    @Test
    void mergesUntouchedParallelSuccessorsBackToSingleSource() throws Exception
    {
        HistoricTaskInstance completed = historicTask("historic-apply", "apply",
                new Date(1_000L), ACTOR_ID, null);
        when(historyService.createHistoricTaskInstanceQuery()
                .taskId("historic-apply").finished().singleResult()).thenReturn(completed);
        Task reviewTask = activeTask(TASK_ID, "review", "8", "execution-review");
        Task financeTask = activeTask("task-2", "finance", "9", "execution-finance");
        Task restoredTask = activeTask("restored-apply", "apply", ACTOR_ID,
                "execution-restored");
        stubActiveInstance(INSTANCE_ID, ACTOR_ID);
        when(taskService.createTaskQuery().processInstanceId(INSTANCE_ID).active().list())
                .thenReturn(List.of(reviewTask, financeTask),
                        List.of(reviewTask, financeTask), List.of(restoredTask));
        when(runtimeService.getActiveActivityIds(INSTANCE_ID))
                .thenReturn(List.of("review", "finance"), List.of("apply"));
        Execution financeExecution = execution(financeTask);
        Execution reviewExecution = execution(reviewTask);
        when(executionQuery.singleResult()).thenReturn(financeExecution, reviewExecution);
        HistoricTaskInstance reviewHistory = historicTask(TASK_ID, "review",
                new Date(3_000L), null, "Change activity to apply");
        HistoricTaskInstance financeHistory = historicTask("task-2", "finance",
                new Date(3_000L), null, "Change activity to apply");
        when(historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(INSTANCE_ID).finished().list())
                .thenReturn(List.of(completed),
                        List.of(completed, reviewHistory, financeHistory));
        ParallelRevokeFixture fixture = parallelRevokeFixture();
        stubDefinition(fixture.model());
        ChangeActivityStateBuilder builder = stateBuilder();

        lifecycleService.revokeProcess(new WorkflowProcessRevokeRequest(
                INSTANCE_ID, "historic-apply", "并行撤回修改"));

        verify(taskService).saveTask(reviewTask);
        verify(taskService).saveTask(financeTask);
        verify(builder).moveExecutionsToSingleActivityId(
                List.of("execution-finance", "execution-review"), "apply");
        verify(builder, never()).moveExecutionToActivityId(any(), any());
        verify(taskService).addComment(eq(TASK_ID), eq(INSTANCE_ID), eq("7"), any());
        verify(taskService).addComment(eq("task-2"), eq(INSTANCE_ID), eq("7"), any());
        assertThat(persistedComments).hasSize(2);
        JsonNode audit = objectMapper.readTree(persistedComments.get(0).getFullMessage());
        assertThat(audit.path("action").asText()).isEqualTo("REVOKE");
        assertThat(audit.path("targetNodeKey").asText()).isEqualTo("apply");
        assertThat(audit.path("sourceTaskId").asText()).isEqualTo("historic-apply");
    }

    /**
     * 验证已认领、已开始或已委派的后继任务稳定返回 409，且不写意见、不创建迁移命令。
     *
     * @param touchedState TouchedSuccessorState，待模拟的不可撤回任务状态
     * @return 无返回值，状态门禁或零副作用约束失效时测试失败
     */
    @ParameterizedTest(name = "touched successor {0}")
    @EnumSource(TouchedSuccessorState.class)
    void rejectsTouchedSuccessorWithoutAnyWrite(TouchedSuccessorState touchedState)
    {
        Task successor = activeTask(TASK_ID, "review", "8");
        applyTouchedState(successor, touchedState);
        stubRejectedRevokeAttempt(successor, twoTaskFixture().model(), List.of());

        assertBusinessError(() -> lifecycleService.revokeProcess(
                new WorkflowProcessRevokeRequest(INSTANCE_ID,
                        "historic-apply", "状态已变化")), HttpStatus.CONFLICT);

        verify(taskService, never()).addComment(any(), any(), any(), any());
        verify(runtimeService, never()).createChangeActivityStateBuilder();
    }

    /**
     * 验证非 listener 白名单的人工或业务 comment 会阻止撤回并保持零写入。
     *
     * @return 无返回值，人工处理痕迹被误判为系统审计时测试失败
     */
    @Test
    void rejectsBusinessCommentWhileAllowingOnlyListenerAudit()
    {
        Task successor = activeTask(TASK_ID, "review", "8");
        Comment businessComment = mock(Comment.class);
        when(businessComment.getTaskId()).thenReturn(TASK_ID);
        when(businessComment.getProcessInstanceId()).thenReturn(INSTANCE_ID);
        when(businessComment.getType()).thenReturn("1");
        when(businessComment.getFullMessage()).thenReturn("人工处理意见");
        stubRejectedRevokeAttempt(successor, twoTaskFixture().model(), List.of());
        when(taskService.getTaskComments(TASK_ID)).thenReturn(List.of(businessComment));

        assertBusinessError(() -> lifecycleService.revokeProcess(
                new WorkflowProcessRevokeRequest(INSTANCE_ID,
                        "historic-apply", "人工意见后禁止撤回")), HttpStatus.CONFLICT);

        verify(taskService, never()).addComment(any(), any(), any(), any());
        verify(runtimeService, never()).createChangeActivityStateBuilder();
    }

    /**
     * 验证超过 100 个活动后继时在逐任务查询前直接返回 409，避免放大数据库查询和锁竞争。
     *
     * @return 无返回值，超限请求进入 N+1 校验或产生写副作用时测试失败
     */
    @Test
    void rejectsMoreThanOneHundredSuccessorsBeforePerTaskQueries()
    {
        HistoricTaskInstance completed = historicTask("historic-apply", "apply",
                new Date(1_000L), ACTOR_ID, null);
        when(historyService.createHistoricTaskInstanceQuery()
                .taskId("historic-apply").finished().singleResult()).thenReturn(completed);
        stubActiveInstance(INSTANCE_ID, ACTOR_ID);
        List<Task> oversizedSuccessors = new ArrayList<>();
        for (int index = 0; index < 101; index++)
        {
            oversizedSuccessors.add(activeTask("task-" + index, "review-" + index,
                    "8", "execution-" + index));
        }
        when(taskService.createTaskQuery().processInstanceId(INSTANCE_ID).active().list())
                .thenReturn(oversizedSuccessors);

        assertBusinessError(() -> lifecycleService.revokeProcess(
                new WorkflowProcessRevokeRequest(INSTANCE_ID,
                        "historic-apply", "超限撤回")), HttpStatus.CONFLICT);

        verify(taskService, never()).getTaskAttachments(any());
        verify(taskService, never()).getTaskComments(any());
        verify(taskService, never()).addComment(any(), any(), any(), any());
        verify(runtimeService, never()).createChangeActivityStateBuilder();
    }

    /**
     * 验证来源完成后已有其他结束任务时撤回返回 409，历史竞态不会产生任何写副作用。
     *
     * @return 无返回值，已处理后继仍可迁移或产生意见时测试失败
     */
    @Test
    void rejectsWhenAnySuccessorHasAlreadyFinishedWithoutAnyWrite()
    {
        Task successor = activeTask(TASK_ID, "review", "8");
        HistoricTaskInstance finishedSuccessor = historicTask("finished-review", "review",
                new Date(2_500L), "8", null);
        stubRejectedRevokeAttempt(successor, twoTaskFixture().model(),
                List.of(finishedSuccessor));

        assertBusinessError(() -> lifecycleService.revokeProcess(
                new WorkflowProcessRevokeRequest(INSTANCE_ID,
                        "historic-apply", "后继已办")), HttpStatus.CONFLICT);

        verify(taskService, never()).addComment(any(), any(), any(), any());
        verify(runtimeService, never()).createChangeActivityStateBuilder();
    }

    /**
     * 验证多实例、子流程、CallActivity、timer、async、ServiceTask、边界事件和歧义分支均返回 409。
     *
     * @param topology UnsafeRevokeTopology，待验证的复杂或不可逆 BPMN 形态
     * @return 无返回值，任一禁止形态触发写副作用或未返回冲突时测试失败
     */
    @ParameterizedTest(name = "unsafe revoke topology {0}")
    @EnumSource(UnsafeRevokeTopology.class)
    void rejectsUnsafeTopologyWithoutAnyWrite(UnsafeRevokeTopology topology)
    {
        UnsafeRevokeFixture fixture = unsafeRevokeFixture(topology);
        stubRejectedRevokeAttempt(fixture.activeTasks().get(0), fixture.model(), List.of());
        when(taskService.createTaskQuery().processInstanceId(INSTANCE_ID).active().list())
                .thenReturn(fixture.activeTasks());

        assertBusinessError(() -> lifecycleService.revokeProcess(
                new WorkflowProcessRevokeRequest(INSTANCE_ID,
                        "historic-apply", "复杂执行树禁止撤回")), HttpStatus.CONFLICT);

        verify(taskService, never()).addComment(any(), any(), any(), any());
        verify(runtimeService, never()).createChangeActivityStateBuilder();
    }

    /**
     * 创建具有生命周期服务所需字段的活动任务替身。
     *
     * @param taskId String，任务主键
     * @param taskDefinitionKey String，BPMN 节点 key
     * @param assignee String，当前办理人主键
     * @return Task，未挂起且未委派的活动任务替身
     */
    private Task activeTask(String taskId, String taskDefinitionKey, String assignee)
    {
        return activeTask(taskId, taskDefinitionKey, assignee, "execution-1");
    }

    /**
     * 创建具有指定 execution 的活动任务替身，供并行执行树测试使用。
     *
     * @param taskId String，任务主键
     * @param taskDefinitionKey String，BPMN 节点 key
     * @param assignee String，当前办理人主键
     * @param executionId String，任务所属真实 execution 主键
     * @return Task，处于 CREATED 且没有认领、开始、挂起或委派痕迹的任务替身
     */
    private Task activeTask(String taskId, String taskDefinitionKey, String assignee,
            String executionId)
    {
        Task task = mock(Task.class);
        when(task.getId()).thenReturn(taskId);
        when(task.getProcessInstanceId()).thenReturn(INSTANCE_ID);
        when(task.getProcessDefinitionId()).thenReturn(DEFINITION_ID);
        when(task.getTaskDefinitionKey()).thenReturn(taskDefinitionKey);
        when(task.getExecutionId()).thenReturn(executionId);
        when(task.getAssignee()).thenReturn(assignee);
        when(task.getState()).thenReturn(Task.CREATED);
        when(task.getCreateTime()).thenReturn(new Date(2_000L));
        when(task.getClaimTime()).thenReturn(null);
        when(task.getClaimedBy()).thenReturn(null);
        when(task.getInProgressStartTime()).thenReturn(null);
        when(task.getInProgressStartedBy()).thenReturn(null);
        when(task.getSuspendedTime()).thenReturn(null);
        when(task.getSuspendedBy()).thenReturn(null);
        when(task.getOwner()).thenReturn(null);
        when(task.getDelegationState()).thenReturn(null);
        when(task.getParentTaskId()).thenReturn(null);
        when(task.isSuspended()).thenReturn(false);
        return task;
    }

    /**
     * 为按主键查询活动任务的链路设置结果。
     *
     * @param task Task，期望服务重新读取到的活动任务
     * @return 无返回值，后续 requireActiveTask 使用该任务
     */
    private void stubActiveTask(Task task)
    {
        when(taskService.createTaskQuery().taskId(task.getId()).active().singleResult())
                .thenReturn(task);
    }

    /**
     * 为活动流程实例查询链路设置未挂起实例。
     *
     * @param instanceId String，流程实例主键
     * @param startUserId String，实例发起用户主键
     * @return ProcessInstance，已经配置到 RuntimeService 的实例替身
     */
    private ProcessInstance stubActiveInstance(String instanceId, String startUserId)
    {
        ProcessInstance instance = mock(ProcessInstance.class);
        when(instance.getId()).thenReturn(instanceId);
        when(instance.getStartUserId()).thenReturn(startUserId);
        when(instance.isSuspended()).thenReturn(false);
        when(runtimeService.createProcessInstanceQuery()
                .processInstanceId(instanceId).active().singleResult()).thenReturn(instance);
        when(runtimeService.createProcessInstanceQuery()
                .processInstanceIds(Set.of(instanceId)).active().list()).thenReturn(List.of(instance));
        return instance;
    }

    /**
     * 为单活动任务设置任务列表、活动节点和真实执行对象。
     *
     * @param task Task，流程实例唯一活动任务
     * @return 无返回值，后续执行树门禁可通过
     */
    private void stubSingleExecution(Task task)
    {
        String taskDefinitionKey = task.getTaskDefinitionKey();
        when(taskService.createTaskQuery().processInstanceId(INSTANCE_ID).active().list())
                .thenReturn(List.of(task));
        when(runtimeService.getActiveActivityIds(INSTANCE_ID))
                .thenReturn(List.of(taskDefinitionKey));
        Execution execution = mock(Execution.class);
        when(execution.getId()).thenReturn("execution-1");
        when(execution.getProcessInstanceId()).thenReturn(INSTANCE_ID);
        when(execution.getActivityId()).thenReturn(taskDefinitionKey);
        when(execution.isEnded()).thenReturn(false);
        when(execution.isSuspended()).thenReturn(false);
        when(runtimeService.createExecutionQuery().executionId("execution-1").singleResult())
                .thenReturn(execution);
    }

    /**
     * 为预期返回 409 的撤回场景配置共同的来源历史、活动实例、活动后继和 BPMN 模型。
     *
     * @param successor Task，当前活动后继任务
     * @param model BpmnModel，待执行撤回门禁的已部署模型
     * @param finishedSuccessors List&lt;HistoricTaskInstance&gt;，来源结束后已结束的其他任务
     * @return 无返回值，后续撤回可直接验证稳定冲突和零副作用
     */
    private void stubRejectedRevokeAttempt(Task successor, BpmnModel model,
            List<HistoricTaskInstance> finishedSuccessors)
    {
        HistoricTaskInstance completed = historicTask("historic-apply", "apply",
                new Date(1_000L), ACTOR_ID, null);
        when(historyService.createHistoricTaskInstanceQuery()
                .taskId("historic-apply").finished().singleResult()).thenReturn(completed);
        stubActiveInstance(INSTANCE_ID, ACTOR_ID);
        when(taskService.createTaskQuery().processInstanceId(INSTANCE_ID).active().list())
                .thenReturn(List.of(successor));
        List<HistoricTaskInstance> finishedTasks = new ArrayList<>();
        finishedTasks.add(completed);
        finishedTasks.addAll(finishedSuccessors);
        when(historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(INSTANCE_ID).finished().list()).thenReturn(finishedTasks);
        stubDefinition(model);
    }

    /**
     * 将普通 CREATED 任务替身改造成已认领、已开始或已委派状态。
     *
     * @param task Task，待修改的活动后继任务替身
     * @param touchedState TouchedSuccessorState，目标不可撤回状态
     * @return 无返回值，后续状态冻结门禁会读取对应字段
     */
    private void applyTouchedState(Task task, TouchedSuccessorState touchedState)
    {
        switch (touchedState)
        {
            case CLAIMED ->
            {
                when(task.getState()).thenReturn(Task.CLAIMED);
                when(task.getClaimTime()).thenReturn(new Date(2_100L));
                when(task.getClaimedBy()).thenReturn("8");
            }
            case IN_PROGRESS ->
            {
                when(task.getState()).thenReturn(Task.IN_PROGRESS);
                when(task.getInProgressStartTime()).thenReturn(new Date(2_200L));
                when(task.getInProgressStartedBy()).thenReturn("8");
            }
            case DELEGATED ->
            {
                when(task.getOwner()).thenReturn("8");
                when(task.getDelegationState()).thenReturn(DelegationState.PENDING);
            }
        }
    }

    /**
     * 创建与指定任务严格关联的受控 userTaskListener create/assignment 审计 comment。
     *
     * @param task Task，系统审计关联的真实活动任务
     * @param event String，仅允许 create 或 assignment
     * @return Comment，字段与 WorkflowUserTaskAuditService 固定 schema 一致的 comment 替身
     */
    private Comment listenerAuditComment(Task task, String event)
    {
        String taskId = task.getId();
        String processInstanceId = task.getProcessInstanceId();
        String processDefinitionId = task.getProcessDefinitionId();
        String taskDefinitionKey = task.getTaskDefinitionKey();
        String action = "create".equals(event)
                ? "USER_TASK_CREATE" : "USER_TASK_ASSIGNMENT";
        var audit = objectMapper.createObjectNode();
        audit.put("schemaVersion", 1);
        audit.put("action", action);
        audit.put("event", event);
        audit.put("taskId", taskId);
        audit.put("processInstanceId", processInstanceId);
        audit.put("processDefinitionId", processDefinitionId);
        audit.put("taskDefinitionKey", taskDefinitionKey);
        Comment comment = mock(Comment.class);
        when(comment.getTaskId()).thenReturn(taskId);
        when(comment.getProcessInstanceId()).thenReturn(processInstanceId);
        when(comment.getType()).thenReturn(WorkflowUserTaskAuditService.COMMENT_TYPE);
        when(comment.getFullMessage()).thenReturn(audit.toString());
        return comment;
    }

    /**
     * 创建与指定活动任务一一对应的真实 execution 替身。
     *
     * @param task Task，execution 所承载的活动用户任务
     * @return Execution，同实例、同节点且未结束未挂起的执行替身
     */
    private Execution execution(Task task)
    {
        // 先从任务替身读取稳定值，避免在 Mockito 的 when/thenReturn 链中嵌套调用另一个 mock。
        String executionId = task.getExecutionId();
        String processInstanceId = task.getProcessInstanceId();
        String taskDefinitionKey = task.getTaskDefinitionKey();
        Execution execution = mock(Execution.class);
        when(execution.getId()).thenReturn(executionId);
        when(execution.getProcessInstanceId()).thenReturn(processInstanceId);
        when(execution.getRootProcessInstanceId()).thenReturn(processInstanceId);
        when(execution.getSuperExecutionId()).thenReturn(null);
        when(execution.getActivityId()).thenReturn(taskDefinitionKey);
        when(execution.isEnded()).thenReturn(false);
        when(execution.isSuspended()).thenReturn(false);
        return execution;
    }

    /**
     * 注册测试 BPMN 对应的流程定义和模型查询结果。
     *
     * @param model BpmnModel，定义应返回的模型
     * @return 无返回值，后续定义关联门禁可通过
     */
    private void stubDefinition(BpmnModel model)
    {
        ProcessDefinition definition = mock(ProcessDefinition.class);
        when(definition.getId()).thenReturn(DEFINITION_ID);
        when(definition.getKey()).thenReturn(PROCESS_KEY);
        when(definition.getDeploymentId()).thenReturn("deployment-1");
        when(repositoryService.getProcessDefinition(DEFINITION_ID)).thenReturn(definition);
        when(repositoryService.getBpmnModel(DEFINITION_ID)).thenReturn(model);
    }

    /**
     * 构造仅包含指定当前任务的 BPMN 模型。
     *
     * @param taskDefinitionKey String，用户任务节点 key
     * @param taskName String，用户任务名称
     * @param formKey String，可为空的部署表单 key
     * @return BpmnFixture，模型、流程和当前任务节点
     */
    private BpmnFixture bpmnFixture(String taskDefinitionKey, String taskName, String formKey)
    {
        BpmnModel model = new BpmnModel();
        org.flowable.bpmn.model.Process process = new org.flowable.bpmn.model.Process();
        process.setId(PROCESS_KEY);
        process.setExecutable(true);
        model.addProcess(process);
        UserTask task = userTask(process, taskDefinitionKey, taskName);
        task.setFormKey(formKey);
        return new BpmnFixture(model, process, task);
    }

    /**
     * 构造申请节点串行连接审核节点的 BPMN 模型。
     *
     * @return BpmnFixture，当前节点为审核任务的两节点模型
     */
    private BpmnFixture twoTaskFixture()
    {
        BpmnFixture fixture = bpmnFixture("review", "审核", null);
        UserTask apply = userTask(fixture.process(), "apply", "申请");
        connect(fixture.process(), apply, fixture.currentTask(), "flow-apply-review");
        EndEvent endEvent = new EndEvent();
        endEvent.setId("end");
        fixture.process().addFlowElement(endEvent);
        connect(fixture.process(), fixture.currentTask(), endEvent, "flow-review-end");
        return fixture;
    }

    /**
     * 构造来源任务通过单一并行网关直接拆分到两个普通用户任务的 BPMN 模型。
     *
     * @return ParallelRevokeFixture，包含来源、拆分网关和全部直接并行后继的模型
     */
    private ParallelRevokeFixture parallelRevokeFixture()
    {
        BpmnModel model = new BpmnModel();
        org.flowable.bpmn.model.Process process = new org.flowable.bpmn.model.Process();
        process.setId(PROCESS_KEY);
        process.setExecutable(true);
        model.addProcess(process);
        UserTask apply = userTask(process, "apply", "申请");
        ParallelGateway split = new ParallelGateway();
        split.setId("parallel-review-split");
        process.addFlowElement(split);
        UserTask finance = userTask(process, "finance", "财务审核");
        UserTask review = userTask(process, "review", "业务审核");
        connect(process, apply, split, "flow-apply-split");
        connect(process, split, finance, "flow-split-finance");
        connect(process, split, review, "flow-split-review");
        return new ParallelRevokeFixture(model, process, apply, split,
                List.of(finance, review));
    }

    /**
     * 按禁止类型构造撤回必须拒绝的复杂 BPMN 模型和对应活动任务快照。
     *
     * @param topology UnsafeRevokeTopology，多实例、作用域、异步或歧义拓扑类型
     * @return UnsafeRevokeFixture，模型和当前活动任务集合
     */
    private UnsafeRevokeFixture unsafeRevokeFixture(UnsafeRevokeTopology topology)
    {
        BpmnModel model = new BpmnModel();
        org.flowable.bpmn.model.Process process = new org.flowable.bpmn.model.Process();
        process.setId(PROCESS_KEY);
        process.setExecutable(true);
        model.addProcess(process);
        UserTask source = userTask(process, "apply", "申请");
        UserTask review = new UserTask();
        review.setId("review");
        review.setName("审核");
        List<Task> activeTasks = List.of(activeTask(TASK_ID, "review", "8"));

        switch (topology)
        {
            case MULTI_INSTANCE ->
            {
                MultiInstanceLoopCharacteristics loop =
                        new MultiInstanceLoopCharacteristics();
                loop.setSequential(false);
                review.setLoopCharacteristics(loop);
                process.addFlowElement(review);
                connect(process, source, review, "flow-apply-review");
            }
            case SUB_PROCESS ->
            {
                SubProcess subProcess = new SubProcess();
                subProcess.setId("review-sub-process");
                process.addFlowElement(subProcess);
                subProcess.addFlowElement(review);
                connect(process, source, subProcess, "flow-apply-sub-process");
            }
            case CALL_ACTIVITY ->
            {
                CallActivity callActivity = new CallActivity();
                callActivity.setId("review-call-activity");
                callActivity.setCalledElement("called-review-process");
                process.addFlowElement(callActivity);
                process.addFlowElement(review);
                connect(process, source, callActivity, "flow-apply-call");
                connect(process, callActivity, review, "flow-call-review");
            }
            case TIMER ->
            {
                IntermediateCatchEvent timer = new IntermediateCatchEvent();
                timer.setId("review-timer");
                TimerEventDefinition timerDefinition = new TimerEventDefinition();
                timerDefinition.setTimeDuration("PT1H");
                timer.getEventDefinitions().add(timerDefinition);
                process.addFlowElement(timer);
                process.addFlowElement(review);
                connect(process, source, timer, "flow-apply-timer");
                connect(process, timer, review, "flow-timer-review");
            }
            case ASYNC ->
            {
                review.setAsynchronous(true);
                process.addFlowElement(review);
                connect(process, source, review, "flow-apply-review");
            }
            case SERVICE_TASK ->
            {
                ServiceTask serviceTask = new ServiceTask();
                serviceTask.setId("prepare-review");
                serviceTask.setImplementation("${approvedServiceTask}");
                process.addFlowElement(serviceTask);
                process.addFlowElement(review);
                connect(process, source, serviceTask, "flow-apply-service");
                connect(process, serviceTask, review, "flow-service-review");
            }
            case BOUNDARY_EVENT ->
            {
                process.addFlowElement(review);
                BoundaryEvent boundaryEvent = new BoundaryEvent();
                boundaryEvent.setId("review-boundary");
                boundaryEvent.setAttachedToRef(review);
                boundaryEvent.setAttachedToRefId(review.getId());
                review.getBoundaryEvents().add(boundaryEvent);
                process.addFlowElement(boundaryEvent);
                connect(process, source, review, "flow-apply-review");
            }
            case AMBIGUOUS_BRANCH ->
            {
                process.addFlowElement(review);
                UserTask finance = userTask(process, "finance", "财务审核");
                connect(process, source, review, "flow-apply-review");
                connect(process, source, finance, "flow-apply-finance");
                activeTasks = List.of(
                        activeTask(TASK_ID, "review", "8", "execution-review"),
                        activeTask("task-2", "finance", "9", "execution-finance"));
            }
        }
        return new UnsafeRevokeFixture(model, activeTasks);
    }

    /**
     * 创建并注册普通用户任务。
     *
     * @param process Process，任务所属主流程
     * @param id String，任务节点 key
     * @param name String，任务名称
     * @return UserTask，已加入主流程的用户任务
     */
    private UserTask userTask(org.flowable.bpmn.model.Process process, String id, String name)
    {
        UserTask task = new UserTask();
        task.setId(id);
        task.setName(name);
        process.addFlowElement(task);
        return task;
    }

    /**
     * 使用完整双向引用连接两个 BPMN 节点。
     *
     * @param process Process，顺序流所属主流程
     * @param source FlowNode，来源节点
     * @param target FlowNode，目标节点
     * @param id String，顺序流 key
     * @return 无返回值，顺序流注册到流程和两个端点
     */
    private void connect(org.flowable.bpmn.model.Process process,
            org.flowable.bpmn.model.FlowNode source,
            org.flowable.bpmn.model.FlowNode target, String id)
    {
        SequenceFlow flow = new SequenceFlow(source.getId(), target.getId());
        flow.setId(id);
        flow.setSourceFlowElement(source);
        flow.setTargetFlowElement(target);
        source.getOutgoingFlows().add(flow);
        target.getIncomingFlows().add(flow);
        process.addFlowElement(flow);
    }

    /**
     * 创建部署表单快照领域对象。
     *
     * @param nodeKey String，BPMN 节点 key
     * @param formKey String，BPMN 表单 key
     * @param content String，不可变表单 JSON
     * @return WfDeployForm，具有变量校验所需字段的快照
     */
    private WfDeployForm snapshot(String nodeKey, String formKey, String content)
    {
        WfDeployForm snapshot = new WfDeployForm();
        snapshot.setDeployId("deployment-1");
        snapshot.setSourceType("TEMPLATE");
        snapshot.setFormId(20L);
        snapshot.setNodeKey(nodeKey);
        snapshot.setFormKey(formKey);
        snapshot.setContent(content);
        return snapshot;
    }

    /**
     * 创建撤回或退回测试使用的历史任务替身。
     *
     * @param taskId String，历史任务主键
     * @param taskDefinitionKey String，BPMN 节点 key
     * @param endTime Date，任务结束时间
     * @param completedBy String，真实完成人主键
     * @param deleteReason String，可为空的删除原因
     * @return HistoricTaskInstance，具有生命周期门禁所需字段的历史任务
     */
    private HistoricTaskInstance historicTask(String taskId, String taskDefinitionKey,
            Date endTime, String completedBy, String deleteReason)
    {
        HistoricTaskInstance task = mock(HistoricTaskInstance.class);
        when(task.getId()).thenReturn(taskId);
        when(task.getProcessInstanceId()).thenReturn(INSTANCE_ID);
        when(task.getProcessDefinitionId()).thenReturn(DEFINITION_ID);
        when(task.getTaskDefinitionKey()).thenReturn(taskDefinitionKey);
        when(task.getEndTime()).thenReturn(endTime);
        when(task.getCompletedBy()).thenReturn(completedBy);
        when(task.getAssignee()).thenReturn(completedBy);
        when(task.getDeleteReason()).thenReturn(deleteReason);
        return task;
    }

    /**
     * 创建并注册返回自身的 Flowable 状态迁移构建器。
     *
     * @return ChangeActivityStateBuilder，可供测试验证来源执行和目标节点
     */
    private ChangeActivityStateBuilder stateBuilder()
    {
        ChangeActivityStateBuilder builder = mock(ChangeActivityStateBuilder.class);
        when(runtimeService.createChangeActivityStateBuilder()).thenReturn(builder);
        when(builder.processInstanceId(any())).thenReturn(builder);
        when(builder.moveExecutionToActivityId(any(), any())).thenReturn(builder);
        when(builder.moveExecutionsToSingleActivityId(any(), any())).thenReturn(builder);
        return builder;
    }

    /**
     * 捕获指定 comment 类型的结构化审计正文。
     *
     * @param commentType String，期望写入的旧系统兼容 comment 类型
     * @return JsonNode，解析后的审计 JSON
     * @throws Exception JSON 正文无法解析时抛出
     */
    private JsonNode capturedAudit(String commentType) throws Exception
    {
        ArgumentCaptor<String> auditCaptor = ArgumentCaptor.forClass(String.class);
        verify(taskService).addComment(any(), eq(INSTANCE_ID), eq(commentType),
                auditCaptor.capture());
        return objectMapper.readTree(auditCaptor.getValue());
    }

    /**
     * 断言生命周期动作返回指定 HTTP 语义的业务异常。
     *
     * @param action Runnable，预期失败的服务动作
     * @param expectedCode int，期望的 HTTP 状态码
     * @return 无返回值，异常类型或状态码不匹配时测试失败
     */
    private void assertBusinessError(Runnable action, int expectedCode)
    {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ServiceException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(expectedCode));
    }

    /**
     * 生命周期测试使用的 BPMN 模型上下文。
     *
     * @param model BpmnModel，完整 BPMN 模型
     * @param process Process，定义 key 对应的主流程
     * @param currentTask UserTask，当前活动用户任务节点
     */
    private record BpmnFixture(BpmnModel model,
            org.flowable.bpmn.model.Process process, UserTask currentTask)
    {
    }

    /**
     * 安全并行撤回 BPMN 测试上下文。
     *
     * @param model BpmnModel，完整 BPMN 模型
     * @param process Process，模型中的主流程
     * @param sourceTask UserTask，待恢复的来源任务
     * @param splitGateway ParallelGateway，直接并行拆分网关
     * @param successorTasks List&lt;UserTask&gt;，全部普通直接并行后继
     */
    private record ParallelRevokeFixture(BpmnModel model,
            org.flowable.bpmn.model.Process process, UserTask sourceTask,
            ParallelGateway splitGateway, List<UserTask> successorTasks)
    {
    }

    /** 撤回前已经发生人工任务状态变化的测试类型。 */
    private enum TouchedSuccessorState
    {
        /** 已被某用户认领。 */
        CLAIMED,

        /** 已显式开始办理。 */
        IN_PROGRESS,

        /** 已进入 Flowable 委派状态机。 */
        DELEGATED
    }

    /** 撤回契约明确禁止的复杂 BPMN 拓扑类型。 */
    private enum UnsafeRevokeTopology
    {
        /** 多实例用户任务。 */
        MULTI_INSTANCE,

        /** 嵌套子流程。 */
        SUB_PROCESS,

        /** 调用外部流程定义。 */
        CALL_ACTIVITY,

        /** timer 中间捕获事件。 */
        TIMER,

        /** async 任务。 */
        ASYNC,

        /** 产生外部业务副作用的服务任务。 */
        SERVICE_TASK,

        /** 用户任务边界事件。 */
        BOUNDARY_EVENT,

        /** 未通过单一并行网关表达的多出边歧义。 */
        AMBIGUOUS_BRANCH
    }

    /**
     * 不安全撤回模型测试上下文。
     *
     * @param model BpmnModel，包含指定复杂边界的模型
     * @param activeTasks List&lt;Task&gt;，模型对应的当前活动任务快照
     */
    private record UnsafeRevokeFixture(BpmnModel model, List<Task> activeTasks)
    {
    }
}
