package com.ruoyi.flowable.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.identitylink.api.IdentityLinkType;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfDeployForm;
import com.ruoyi.flowable.domain.dto.WorkflowApplicationResubmitRequest;
import com.ruoyi.flowable.domain.dto.WorkflowTaskReturnRequest;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.engine.WorkflowExceptionTranslator;
import com.ruoyi.flowable.identity.WorkflowAuthenticationContext;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityCodec;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.service.WorkflowFormTemplateValidator;
import com.ruoyi.flowable.service.attachment.WorkflowAttachmentService;
import com.ruoyi.flowable.service.model.WorkflowDeploymentArtifactRepository;
import com.ruoyi.flowable.service.model.WorkflowDeploymentArtifacts;
import com.ruoyi.flowable.service.notification.WorkflowNotificationService;
import com.ruoyi.flowable.service.process.WorkflowFormSubmissionSnapshotCodec;
import com.ruoyi.flowable.service.process.WorkflowProcessStartService;
import com.ruoyi.flowable.service.process.WorkflowStartVariableValidator;
import com.ruoyi.flowable.testsupport.WorkflowFlowableEngineTestSupport;

/**
 * 使用真实 Flowable 8、H2 和 Spring 事务验证退回发起人与重新提交完整状态机。
 */
class WorkflowTaskReturnChainIntegrationTest
{
    /** 流程发起人主键，退回后必须成为首审批任务唯一办理人。 */
    private static final String APPLICANT_ID = "100";

    /** 首审批节点原直接办理人主键。 */
    private static final String FIRST_APPROVER_ID = "200";

    /** 第二审批节点办理人及正式退回操作人主键。 */
    private static final String SECOND_APPROVER_ID = "300";

    /** 开始表单部署快照，重新提交时只允许更新申请标题。 */
    private static final String START_FORM = """
            {"fields":[
              {"vModel":"requestTitle","layout":"colFormItem","tag":"el-input","required":true,"workflowHidden":false,"workflowReadable":true,"workflowWritable":true}
            ]}
            """;

    /** 当前请求身份，可在第二审批人退回和发起人重新提交之间切换。 */
    private final AtomicReference<String> currentUserId =
            new AtomicReference<>(SECOND_APPROVER_ID);

    private ProcessEngine processEngine;
    /** 当前用例独占且在 teardown 显式关闭的引擎基础设施。 */
    private WorkflowFlowableEngineTestSupport engineInfrastructure;
    private RepositoryService repositoryService;
    private RuntimeService runtimeService;
    private TaskService taskService;
    private HistoryService historyService;
    private WorkflowTaskReturnApplicationService returnApplicationService;
    private WorkflowTaskReturnPreparation returnPreparation;
    private WorkflowApplicationResubmitApplicationService resubmitApplicationService;
    private WorkflowTaskCopyService taskCopyService;
    private WorkflowNotificationService notificationService;
    private WorkflowAttachmentService attachmentService;
    private String deploymentId;

    /**
     * 创建共享 H2 数据源上的真实引擎、部署制品和生产生命周期服务图。
     *
     * @return void，每个测试使用独立数据库和流程部署
     */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp()
    {
        WorkflowIdentityResolver identityResolver = mock(WorkflowIdentityResolver.class);
        when(identityResolver.resolveApprovalEligibleUserIds(org.mockito.ArgumentMatchers.anyCollection()))
                .thenAnswer(invocation -> new java.util.LinkedHashSet<>((java.util.Collection<String>) invocation.getArgument(0)));
        when(identityResolver.resolveActiveUserIds(org.mockito.ArgumentMatchers.anyCollection(), org.mockito.ArgumentMatchers.anyCollection()))
                .thenAnswer(invocation -> new java.util.LinkedHashSet<>((java.util.Collection<String>) invocation.getArgument(0)));
        when(identityResolver.resolveCurrentIdentity()).thenAnswer(invocation ->
                new WorkflowCurrentIdentity(currentUserId.get(), Set.of()));

        engineInfrastructure = WorkflowFlowableEngineTestSupport.start(
                "return-chain", Map.of());
        processEngine = engineInfrastructure.processEngine();
        repositoryService = processEngine.getRepositoryService();
        runtimeService = processEngine.getRuntimeService();
        taskService = processEngine.getTaskService();
        historyService = processEngine.getHistoryService();

        Deployment deployment = repositoryService.createDeployment()
                .addString("return-chain.bpmn20.xml", BPMN)
                .deploy();
        deploymentId = deployment.getId();
        WorkflowDeploymentArtifactRepository artifactRepository =
                new WorkflowDeploymentArtifactRepository(repositoryService);
        artifactRepository.persist(deploymentId, deploymentArtifacts());

        WorkflowAuthenticationContext authenticationContext =
                new WorkflowAuthenticationContext(processEngine.getIdentityService(),
                        new WorkflowIdentityCodec());
        WorkflowEngineOperations operationsTarget = new WorkflowEngineOperations(
                authenticationContext, new WorkflowExceptionTranslator(), identityResolver);
        WorkflowEngineOperations engineOperations =
                engineInfrastructure.transactionalProxy(operationsTarget);

        attachmentService = mock(WorkflowAttachmentService.class);
        when(attachmentService.prepareTaskVariables(
                anyString(), anyString(), anyMap(), anyMap()))
                .thenAnswer(invocation -> Map.copyOf(
                        (Map<String, Object>) invocation.getArgument(2)));
        taskCopyService = mock(WorkflowTaskCopyService.class);
        when(taskCopyService.prepare(any(WorkflowTaskCopyAction.class), any(Task.class),
                any(WorkflowCurrentIdentity.class), anyList()))
                .thenReturn(WorkflowTaskCopyService.CopyPlan.empty());
        notificationService = mock(WorkflowNotificationService.class);
        WorkflowMultiInstanceGroupTransitionService groupTransitionService =
                mock(WorkflowMultiInstanceGroupTransitionService.class);

        var reader = new WorkflowTaskRuntimeReader(runtimeService, taskService, historyService);
        var returnedState = new WorkflowReturnedTaskStateService(new WorkflowReturnedAssignmentCodec(),
                runtimeService, taskService, identityResolver);
        returnPreparation = new WorkflowTaskReturnPreparation(engineOperations, reader,
                new WorkflowTaskBpmnReader(repositoryService), new WorkflowTaskMovementPolicy(),
                returnedState, groupTransitionService);
        var resubmitPreparation = new WorkflowApplicationResubmitPreparation(engineOperations, reader,
                returnedState, groupTransitionService, artifactRepository, runtimeService);
        returnApplicationService = new WorkflowTaskReturnApplicationService(engineOperations,
                        returnPreparation, reader, returnedState,
                        new WorkflowTaskActionAuditWriter(taskService),
                        new WorkflowTaskConcurrencyExecutor(),
                        groupTransitionService, taskCopyService,
                        notificationService, runtimeService);
        resubmitApplicationService = new WorkflowApplicationResubmitApplicationService(engineOperations,
                        resubmitPreparation, reader, returnedState,
                        new WorkflowTaskActionAuditWriter(taskService),
                        new WorkflowTaskConcurrencyExecutor(),
                        groupTransitionService,
                        new WorkflowStartVariableValidator(
                                new WorkflowFormTemplateValidator()),
                        attachmentService, notificationService, runtimeService);
    }

    /**
     * 关闭真实引擎，避免测试之间共享运行时、历史表和认证状态。
     *
     * @return void，无返回值
     */
    @AfterEach
    void tearDown()
    {
        if (engineInfrastructure != null)
        {
            engineInfrastructure.close();
        }
        processEngine = null;
        engineInfrastructure = null;
    }

    /**
     * 验证发起人重新提交更新开始表单及快照，完整恢复首审办理配置和 running 业务状态。
     *
     * @return void，任一表单、身份、状态或局部变量事实不一致时失败
     */
    @Test
    void resubmitsApplicationAndRestoresOriginalAssignment()
    {
        ReturnScenario scenario = startAtSecondApproval();
        returnApplicationService.returnTask(new WorkflowTaskReturnRequest(
                scenario.secondTask().getId(), "请更新申请标题", List.of()));
        Task returnedTask = requireSingleTask(scenario.instance().getId(), "firstApproval");

        currentUserId.set(APPLICANT_ID);
        resubmitApplicationService.resubmitApplication(new WorkflowApplicationResubmitRequest(
                returnedTask.getId(), Map.of("requestTitle", "修改后申请")));

        Task resubmittedTask = requireSingleTask(
                scenario.instance().getId(), "firstApproval");
        assertThat(resubmittedTask.getId()).isEqualTo(returnedTask.getId());
        assertThat(resubmittedTask.getAssignee()).isEqualTo(FIRST_APPROVER_ID);
        assertThat(resubmittedTask.getOwner()).isNull();
        assertThat(taskService.getIdentityLinksForTask(resubmittedTask.getId()))
                .filteredOn(link -> IdentityLinkType.CANDIDATE.equals(link.getType()))
                .anySatisfy(link -> assertThat(link.getUserId()).isEqualTo("201"))
                .anySatisfy(link -> assertThat(link.getGroupId()).isEqualTo("finance"));
        assertThat(runtimeService.getVariable(
                scenario.instance().getId(), "requestTitle"))
                .isEqualTo("修改后申请");
        WorkflowFormSubmissionSnapshotCodec.SubmissionSnapshot snapshot =
                WorkflowFormSubmissionSnapshotCodec.decode((String) runtimeService.getVariable(
                        scenario.instance().getId(),
                        WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME));
        assertThat(snapshot.values().get("requestTitle").stringValue())
                .isEqualTo("修改后申请");
        assertBusinessStatus(scenario.instance().getId(),
                WorkflowProcessStartService.RUNNING_STATUS);
        assertThat(taskService.getVariableLocal(resubmittedTask.getId(),
                WorkflowReturnedApplicationProtocol.RETURN_ASSIGNMENT_VARIABLE)).isNull();
        assertThat(taskService.getVariableLocal(resubmittedTask.getId(),
                WorkflowReturnedApplicationProtocol.RETURN_APPLICANT_VARIABLE)).isNull();
        assertThat(runtimeService.getVariable(scenario.instance().getId(),
                WorkflowReturnedApplicationProtocol.CONTROLLED_TRANSITION_VARIABLE)).isNull();
        verify(attachmentService).bindTaskAttachments(APPLICANT_ID,
                scenario.instance().getId(), resubmittedTask.getId(), "start", Map.of());
    }

    /**
     * 验证并行活动任务的能力投影为 false，正式退回为 409 且没有部分副作用。
     *
     * @return void，任务、comment、状态、抄送或通知发生部分变更时失败
     */
    @Test
    void rejectsParallelReturnWithoutPartialSideEffects()
    {
        ProcessInstance instance = startProcess("parallelReturn");
        List<Task> beforeTasks = taskService.createTaskQuery()
                .processInstanceId(instance.getId()).active().list();
        assertThat(beforeTasks).hasSize(2);
        String firstHistoricNode = historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(instance.getId())
                .orderByHistoricTaskInstanceStartTime().asc().list().get(0)
                .getTaskDefinitionKey();
        Task sourceTask = beforeTasks.stream()
                .filter(task -> firstHistoricNode.equals(task.getTaskDefinitionKey()))
                .findFirst().orElseThrow();

        assertThat(returnPreparation.isTaskReturnAllowed(sourceTask, instance,
                new WorkflowCurrentIdentity(currentUserId.get(), Set.of()),
                new WorkflowTaskBpmnReader(repositoryService).require(instance.getProcessDefinitionId()), null,
                () -> new WorkflowTaskRuntimeReader(runtimeService, taskService, historyService)
                        .readHistoricTasksAscending(instance.getId(), 501), beforeTasks)).isFalse();
        assertThatThrownBy(() -> returnApplicationService.returnTask(new WorkflowTaskReturnRequest(
                sourceTask.getId(), "并行结构不允许退回", List.of())))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(taskService.createTaskQuery()
                .processInstanceId(instance.getId()).active().list())
                .extracting(Task::getId)
                .containsExactlyInAnyOrderElementsOf(
                        beforeTasks.stream().map(Task::getId).toList());
        assertThat(taskService.getProcessInstanceComments(instance.getId(), "RETURN")).isEmpty();
        assertThat(runtimeService.getVariable(instance.getId(),
                "processStatus")).isNull();
        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(instance.getId()).singleResult()
                .getBusinessStatus()).isEqualTo(WorkflowProcessStartService.RUNNING_STATUS);
        assertThat(runtimeService.getVariable(instance.getId(),
                WorkflowReturnedApplicationProtocol.CONTROLLED_TRANSITION_VARIABLE)).isNull();
        verifyNoInteractions(taskCopyService, notificationService, attachmentService);
    }

    /**
     * 启动串行退回流程并真实完成首审，使实例停留在第二审批节点。
     *
     * @return ReturnScenario，实例、原首审任务和当前第二审任务
     */
    private ReturnScenario startAtSecondApproval()
    {
        ProcessInstance instance = startProcess("serialReturn");
        Task firstTask = requireSingleTask(instance.getId(), "firstApproval");
        taskService.complete(firstTask.getId(), FIRST_APPROVER_ID);
        Task secondTask = requireSingleTask(instance.getId(), "secondApproval");
        return new ReturnScenario(instance, firstTask, secondTask);
    }

    /**
     * 以真实 Flowable 认证发起人和 businessStatus 启动流程。
     *
     * @param processKey String，待启动的流程定义 key
     * @return ProcessInstance，真实活动流程实例
     */
    private ProcessInstance startProcess(String processKey)
    {
        String snapshot = WorkflowFormSubmissionSnapshotCodec.encodeStart(
                deploymentId, "TEMPLATE", 1L, "startForm", "start",
                Map.of("requestTitle", "原始申请"));
        processEngine.getIdentityService().setAuthenticatedUserId(APPLICANT_ID);
        try
        {
            ProcessInstance instance = runtimeService.createProcessInstanceBuilder()
                    .processDefinitionKey(processKey).businessStatus("running").variables(Map.of("requestTitle", "原始申请",
                            WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME, snapshot,
                            "initiator", APPLICANT_ID)).start();
            return runtimeService.createProcessInstanceQuery()
                    .processInstanceId(instance.getId()).singleResult();
        }
        finally
        {
            processEngine.getIdentityService().setAuthenticatedUserId(null);
        }
    }

    /**
     * 查询实例当前唯一活动任务并校验预期节点。
     *
     * @param processInstanceId String，真实流程实例主键
     * @param taskDefinitionKey String，预期节点 key
     * @return Task，真实活动任务
     */
    private Task requireSingleTask(String processInstanceId, String taskDefinitionKey)
    {
        List<Task> tasks = taskService.createTaskQuery()
                .processInstanceId(processInstanceId).active().list();
        assertThat(tasks).singleElement();
        assertThat(tasks.get(0).getTaskDefinitionKey()).isEqualTo(taskDefinitionKey);
        return tasks.get(0);
    }

    /**
     * 核对唯一 businessStatus，并确认旧变量未被重新建立。
     *
     * @param processInstanceId String，真实流程实例主键
     * @param expectedStatus String，预期业务状态
     * @return void，任一状态不一致时失败
     */
    private void assertBusinessStatus(String processInstanceId, String expectedStatus)
    {
        assertThat(runtimeService.getVariable(processInstanceId,
                "processStatus")).isNull();
        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult().getBusinessStatus())
                .isEqualTo(expectedStatus);
    }

    /**
     * 创建重新提交所需的开始表单不可变部署制品。
     *
     * @return WorkflowDeploymentArtifacts，仅包含开始表单快照的完整制品集
     */
    private WorkflowDeploymentArtifacts deploymentArtifacts()
    {
        WfDeployForm form = new WfDeployForm();
        form.setDeployId(deploymentId);
        form.setSourceType("TEMPLATE");
        form.setFormId(1L);
        form.setFormKey("startForm");
        form.setNodeKey("start");
        form.setFormName("申请表");
        form.setNodeName("开始");
        form.setContent(START_FORM);
        form.setCreateTime(new Date());
        return new WorkflowDeploymentArtifacts(List.of(form), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    @Test
    void tiedHistoryStartTimesUseTheSameFirstNodeForCapabilityAndWrite()
    {
        processEngine.getProcessEngineConfiguration().getClock().setCurrentTime(new Date(1_800_000_000_000L));
        try
        {
            ReturnScenario scenario = startAtSecondApproval();
            var projectedHistory = historyService.createHistoricTaskInstanceQuery()
                    .processInstanceId(scenario.instance().getId()).includeIdentityLinks()
                    .orderByHistoricTaskInstanceStartTime().asc().orderByTaskId().asc().listPage(0, 501);
            assertThat(projectedHistory).hasSize(2);
            assertThat(projectedHistory.get(0).getStartTime()).isEqualTo(projectedHistory.get(1).getStartTime());
            var reader = new WorkflowTaskRuntimeReader(runtimeService, taskService, historyService);
            assertThat(reader.readHistoricTasksAscending(scenario.instance().getId(), 501))
                    .extracting(org.flowable.task.api.history.HistoricTaskInstance::getId)
                    .containsExactlyElementsOf(projectedHistory.stream().map(org.flowable.task.api.history.HistoricTaskInstance::getId).toList());
            assertThat(returnPreparation.isTaskReturnAllowed(scenario.secondTask(), scenario.instance(),
                    new WorkflowCurrentIdentity(SECOND_APPROVER_ID, Set.of()),
                    new WorkflowTaskBpmnReader(repositoryService).require(scenario.instance().getProcessDefinitionId()),
                    null, () -> projectedHistory, taskService.createTaskQuery().processInstanceId(scenario.instance().getId()).active().list()))
                    .isTrue();
            returnApplicationService.returnTask(new WorkflowTaskReturnRequest(scenario.secondTask().getId(), "同毫秒退回", List.of()));
            assertThat(taskService.createTaskQuery().processInstanceId(scenario.instance().getId()).singleResult().getTaskDefinitionKey())
                    .isEqualTo(projectedHistory.get(0).getTaskDefinitionKey());
        }
        finally
        {
            processEngine.getProcessEngineConfiguration().getClock().reset();
        }
    }

    /**
     * 串行退回夹具中的真实实例和前后任务。
     *
     * @param instance ProcessInstance，活动流程实例
     * @param firstTask Task，已完成的原首审任务
     * @param secondTask Task，当前第二审批任务
     */
    private record ReturnScenario(ProcessInstance instance, Task firstTask, Task secondTask)
    {
    }

    /** 真实串行退回与并行拒绝测试共用的两个可执行流程。 */
    private static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
              xmlns:flowable="http://flowable.org/bpmn" targetNamespace="return-chain-it">
              <process id="serialReturn" name="serialReturn" isExecutable="true">
                <startEvent id="start" flowable:formKey="startForm"/>
                <userTask id="firstApproval" name="首审" flowable:assignee="200"
                  flowable:candidateUsers="201" flowable:candidateGroups="finance"/>
                <userTask id="secondApproval" name="二审" flowable:assignee="300"/>
                <endEvent id="serialEnd"/>
                <sequenceFlow sourceRef="start" targetRef="firstApproval"/>
                <sequenceFlow sourceRef="firstApproval" targetRef="secondApproval"/>
                <sequenceFlow sourceRef="secondApproval" targetRef="serialEnd"/>
              </process>

              <process id="parallelReturn" name="parallelReturn" isExecutable="true">
                <startEvent id="parallelStart"/>
                <parallelGateway id="parallelSplit"/>
                <userTask id="parallelApprovalA" name="并行审批A" flowable:assignee="300"/>
                <userTask id="parallelApprovalB" name="并行审批B" flowable:assignee="300"/>
                <parallelGateway id="parallelJoin"/>
                <endEvent id="parallelEnd"/>
                <sequenceFlow sourceRef="parallelStart" targetRef="parallelSplit"/>
                <sequenceFlow sourceRef="parallelSplit" targetRef="parallelApprovalA"/>
                <sequenceFlow sourceRef="parallelSplit" targetRef="parallelApprovalB"/>
                <sequenceFlow sourceRef="parallelApprovalA" targetRef="parallelJoin"/>
                <sequenceFlow sourceRef="parallelApprovalB" targetRef="parallelJoin"/>
                <sequenceFlow sourceRef="parallelJoin" targetRef="parallelEnd"/>
              </process>
            </definitions>
            """;
}
