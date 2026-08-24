package com.ruoyi.flowable.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.engine.task.Comment;
import org.flowable.task.api.Task;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.variable.api.history.HistoricVariableInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import com.ruoyi.flowable.domain.WfControlledLoopExecution;
import com.ruoyi.flowable.domain.WfDeployControlledLoop;
import com.ruoyi.flowable.domain.WfDeployForm;
import com.ruoyi.flowable.domain.WfMultiInstanceRound;
import com.ruoyi.flowable.domain.WorkflowMultiInstanceRoundStatus;
import com.ruoyi.flowable.domain.dto.WorkflowTaskCompleteRequest;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.engine.WorkflowExceptionTranslator;
import com.ruoyi.flowable.identity.WorkflowAuthenticationContext;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityCodec;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.identity.WorkflowUserSelectionValidator;
import com.ruoyi.flowable.mapper.WfControlledLoopExecutionMapper;
import com.ruoyi.flowable.mapper.WfCopyMapper;
import com.ruoyi.flowable.mapper.WorkflowMultiInstanceUserMapper;
import com.ruoyi.flowable.mapper.WorkflowRuntimeTaskMapper;
import com.ruoyi.flowable.service.WorkflowFormTemplateValidator;
import com.ruoyi.flowable.service.attachment.WorkflowAttachmentService;
import com.ruoyi.flowable.service.model.WorkflowDeploymentArtifactRepository;
import com.ruoyi.flowable.service.model.WorkflowDeploymentArtifacts;
import com.ruoyi.flowable.service.notification.WorkflowNotificationService;
import com.ruoyi.flowable.service.process.WorkflowFormSubmissionSnapshotCodec;
import com.ruoyi.flowable.service.process.WorkflowProcessInstanceService;
import com.ruoyi.flowable.service.process.WorkflowStartVariableValidator;
import com.ruoyi.flowable.testsupport.WorkflowFlowableEngineTestSupport;
import com.ruoyi.flowable.testsupport.WorkflowH2SchemaMapperSupport;
import com.ruoyi.system.mapper.SysUserMapper;

/**
 * 使用同一真实 Flowable 8、H2、Spring 事务和正式循环 Mapper 验证任务完成单上下文写链。
 */
class WorkflowTaskCompletionContextIntegrationTest
{
    /** 当前真实完成人，同时是普通任务和多实例成员。 */
    private static final String CURRENT_USER_ID = "100";

    /** 普通任务表单部署快照，只允许提交一个必填文本字段。 */
    private static final String APPROVAL_FORM = """
            {"fields":[
              {"__vModel__":"approvalResult","__config__":{"layout":"colFormItem","tag":"el-input","required":true}}
            ]}
            """;

    /** 循环任务表单部署快照，判断值由受控循环服务精确解释。 */
    private static final String LOOP_FORM = """
            {"fields":[
              {"__vModel__":"loopDecision","__config__":{"layout":"colFormItem","tag":"el-input","required":true}}
            ]}
            """;

    private ProcessEngine processEngine;
    /** 当前用例独占且在 teardown 显式关闭的引擎基础设施。 */
    private WorkflowFlowableEngineTestSupport engineInfrastructure;
    private RepositoryService repositoryService;
    private RuntimeService runtimeService;
    private TaskService taskService;
    private HistoryService historyService;
    private JdbcTemplate jdbcTemplate;
    private WorkflowTaskLifecycleService lifecycleService;
    private WfControlledLoopExecutionMapper loopExecutionMapper;
    private Map<String, ProcessDefinition> definitions;

    /**
     * 创建共享 H2 数据源上的真实引擎、部署制品、循环审计表和生产任务完成服务图。
     *
     * @return void，每个测试使用独立数据库和流程部署
     */
    @BeforeEach
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void setUp()
    {
        WorkflowIdentityResolver identityResolver = mock(WorkflowIdentityResolver.class);
        when(identityResolver.resolveCurrentIdentity()).thenReturn(
                new WorkflowCurrentIdentity(CURRENT_USER_ID, Set.of()));
        when(identityResolver.resolveApprovalEligibleUserIds(anyList()))
                .thenAnswer(invocation -> new LinkedHashSet<>(
                        (List<String>) invocation.getArgument(0)));
        when(identityResolver.resolveClaimEligibleUserIds(anyList()))
                .thenAnswer(invocation -> new LinkedHashSet<>(
                        (List<String>) invocation.getArgument(0)));
        WorkflowUserSelectionValidator userSelectionValidator =
                new WorkflowUserSelectionValidator(identityResolver);
        WorkflowMultiInstanceTransitionCoordinator transitionCoordinator =
                new WorkflowMultiInstanceTransitionCoordinator();
        WorkflowMultiInstanceHandler multiInstanceHandler =
                new WorkflowMultiInstanceHandler(userSelectionValidator,
                        transitionCoordinator);

        engineInfrastructure = WorkflowFlowableEngineTestSupport.start(
                "completion-context",
                Map.of("multiInstanceHandler", multiInstanceHandler));
        DataSource dataSource = engineInfrastructure.dataSource();
        processEngine = engineInfrastructure.processEngine();
        repositoryService = spy(processEngine.getRepositoryService());
        runtimeService = processEngine.getRuntimeService();
        taskService = processEngine.getTaskService();
        historyService = processEngine.getHistoryService();
        jdbcTemplate = engineInfrastructure.jdbcTemplate();
        WorkflowH2SchemaMapperSupport.executeSchema(dataSource,
                WorkflowH2SchemaMapperSupport.CONTROLLED_LOOP_EXECUTION_SCHEMA);
        loopExecutionMapper = WorkflowH2SchemaMapperSupport.createSpringMapper(dataSource,
                "completion-context-it", WfControlledLoopExecutionMapper.class,
                "mapper/flowable/WfControlledLoopExecutionMapper.xml");

        Deployment deployment = repositoryService.createDeployment()
                .addString("completion-context.bpmn20.xml", BPMN)
                .deploy();
        definitions = repositoryService.createProcessDefinitionQuery()
                .deploymentId(deployment.getId()).list().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        ProcessDefinition::getKey, definition -> definition));
        WorkflowDeploymentArtifactRepository artifactRepository =
                new WorkflowDeploymentArtifactRepository(repositoryService);
        artifactRepository.persist(deployment.getId(), deploymentArtifacts(deployment.getId()));

        WorkflowAuthenticationContext authenticationContext =
                new WorkflowAuthenticationContext(processEngine.getIdentityService(),
                        new WorkflowIdentityCodec());
        WorkflowEngineOperations operationsTarget = new WorkflowEngineOperations(
                authenticationContext, new WorkflowExceptionTranslator(), identityResolver);
        WorkflowEngineOperations engineOperations =
                engineInfrastructure.transactionalProxy(operationsTarget);

        WorkflowAttachmentService attachmentService = mock(WorkflowAttachmentService.class);
        when(attachmentService.prepareTaskVariables(anyString(), anyString(), anyMap(), anyMap()))
                .thenAnswer(invocation -> Map.copyOf(
                        (Map<String, Object>) invocation.getArgument(2)));
        WorkflowNotificationService notificationService = mock(WorkflowNotificationService.class);
        WorkflowTaskCopyService taskCopyService = new WorkflowTaskCopyService(
                userSelectionValidator, mock(WfCopyMapper.class),
                mock(WorkflowRuntimeTaskMapper.class), repositoryService, runtimeService,
                mock(SysUserMapper.class), notificationService);
        WorkflowMultiInstanceRuntimeSnapshotReader snapshotReader =
                new WorkflowMultiInstanceRuntimeSnapshotReader(repositoryService,
                        runtimeService, taskService);
        WorkflowNextTaskAssignmentService nextTaskAssignmentService =
                new WorkflowNextTaskAssignmentService(userSelectionValidator,
                        taskService, runtimeService, snapshotReader);
        WorkflowMultiInstanceRoundLifecycleService roundLifecycleService =
                mock(WorkflowMultiInstanceRoundLifecycleService.class);
        AtomicReference<ControlledMultiInstanceSnapshot> controlledRuntime =
                new AtomicReference<>();
        when(roundLifecycleService.requireActiveRound(
                any(ControlledMultiInstanceSnapshot.class))).thenAnswer(invocation ->
        {
            ControlledMultiInstanceSnapshot runtime = invocation.getArgument(0);
            controlledRuntime.set(runtime);
            return roundSnapshot(runtime, runtime.revision());
        });
        when(roundLifecycleService.requireCompletionPersisted(anyString(), anyInt(),
                anyBoolean())).thenAnswer(invocation -> roundSnapshot(
                        controlledRuntime.get(), invocation.getArgument(1)));
        WorkflowMultiInstanceService multiInstanceService =
                new WorkflowMultiInstanceService(engineOperations, identityResolver,
                        userSelectionValidator,
                        mock(WorkflowMultiInstanceUserMapper.class), runtimeService,
                        taskService, historyService, snapshotReader,
                        roundLifecycleService);
        WorkflowMultiInstanceGroupTransitionService groupTransitionService =
                mock(WorkflowMultiInstanceGroupTransitionService.class);
        WorkflowControlledLoopService controlledLoopService =
                new WorkflowControlledLoopService(runtimeService, taskService,
                        artifactRepository, loopExecutionMapper);

        WorkflowTaskCompletionApplicationService completionApplicationService =
                new WorkflowTaskCompletionApplicationService(engineOperations,
                        new WorkflowTaskRequestValidator(),
                        new WorkflowTaskRuntimeReader(runtimeService, taskService,
                                historyService),
                        new WorkflowTaskBpmnReader(repositoryService),
                        artifactRepository,
                        new WorkflowStartVariableValidator(
                                new WorkflowFormTemplateValidator()),
                        attachmentService, taskCopyService,
                        nextTaskAssignmentService, multiInstanceService,
                        controlledLoopService,
                        new WorkflowTaskActionAuditWriter(taskService),
                        new WorkflowTaskConcurrencyExecutor(),
                        runtimeService, taskService);
        lifecycleService = new WorkflowTaskLifecycleService(
                mock(WorkflowProcessCancelApplicationService.class),
                mock(WorkflowTaskRevokeApplicationService.class),
                completionApplicationService,
                mock(WorkflowTaskRejectionApplicationService.class),
                mock(WorkflowTaskReturnApplicationService.class),
                mock(WorkflowApplicationResubmitApplicationService.class));
        clearInvocations(repositoryService);
    }

    /**
     * 为本测试的真实 Flowable 执行树构造对应的不可变 ACTIVE 轮次事实。
     *
     * @param runtime ControlledMultiInstanceSnapshot，真实读取器得到的受控根快照
     * @param revision int，预留前或写后应当持久化的 revision
     * @return MultiInstanceRoundSnapshot，与运行时身份、成员和模式一致的测试轮次
     */
    private MultiInstanceRoundSnapshot roundSnapshot(
            ControlledMultiInstanceSnapshot runtime, int revision)
    {
        if (runtime == null)
        {
            throw new IllegalStateException("受控多实例运行时快照尚未建立");
        }
        return new MultiInstanceRoundSnapshot(1L, runtime.deployId(),
                runtime.processDefinitionId(), runtime.processInstanceId(),
                runtime.activityId(), runtime.rootExecutionId(), 1, runtime.mode(),
                runtime.members(), revision, WorkflowMultiInstanceRoundStatus.ACTIVE,
                null, null, null, LocalDateTime.of(2026, 8, 24, 9, 0),
                null, null, null, null);
    }

    /**
     * 关闭真实引擎，避免测试之间共享运行时、历史表和表达式 Bean。
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
     * 验证带表单普通任务完成后的完成人、变量、意见、提交快照和单次定义/BPMN 读取。
     *
     * @return void，任一真实历史事实或单上下文调用次数不一致时失败
     */
    @Test
    void completesFormTaskWithOneDefinitionAndOneBpmnRead()
    {
        ProcessInstance instance = runtimeService.startProcessInstanceByKey("completionForm");
        Task task = requireSingleTask(instance.getId(), "formReview");
        clearInvocations(repositoryService);

        lifecycleService.completeTask(new WorkflowTaskCompleteRequest(task.getId(),
                "表单审批通过", Map.of("approvalResult", "approved"),
                List.of(), List.of(), null));

        HistoricTaskInstance historicTask = historyService.createHistoricTaskInstanceQuery()
                .taskId(task.getId()).finished().singleResult();
        assertThat(historicTask).isNotNull();
        assertThat(historicTask.getCompletedBy()).isEqualTo(CURRENT_USER_ID);
        assertThat(historyService.createHistoricVariableInstanceQuery()
                .processInstanceId(instance.getId()).variableName("approvalResult")
                .singleResult().getValue()).isEqualTo("approved");
        Comment completionComment = taskService.getProcessInstanceComments(
                instance.getId(), "1").stream()
                .filter(comment -> task.getId().equals(comment.getTaskId()))
                .findFirst().orElseThrow();
        assertThat(completionComment.getFullMessage())
                .contains("\"action\":\"COMPLETE\"")
                .contains("表单审批通过")
                .contains("\"actorUserId\":\"100\"");

        HistoricVariableInstance snapshotVariable = historyService
                .createHistoricVariableInstanceQuery().taskId(task.getId())
                .variableName(WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME)
                .singleResult();
        assertThat(snapshotVariable).isNotNull();
        WorkflowFormSubmissionSnapshotCodec.SubmissionSnapshot snapshot =
                WorkflowFormSubmissionSnapshotCodec.decode((String) snapshotVariable.getValue());
        assertThat(snapshot.taskId()).isEqualTo(task.getId());
        assertThat(snapshot.nodeKey()).isEqualTo("formReview");
        assertThat(snapshot.values().get("approvalResult").stringValue())
                .isEqualTo("approved");
        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(instance.getId()).count()).isZero();

        verify(repositoryService, times(1)).getProcessDefinition(
                definitions.get("completionForm").getId());
        verify(repositoryService, times(1)).getBpmnModel(
                definitions.get("completionForm").getId());
    }

    /**
     * 验证显式选择下一办理人后，真实唯一直接后继任务被改派且来源任务完成。
     *
     * @return void，后继任务、办理人或来源历史不符合计划时失败
     */
    @Test
    void appliesSelectedUserToRealDirectSuccessor()
    {
        ProcessInstance instance = runtimeService.startProcessInstanceByKey("completionNext");
        Task source = requireSingleTask(instance.getId(), "selectSource");

        lifecycleService.completeTask(new WorkflowTaskCompleteRequest(source.getId(),
                "指定下一办理人", Map.of(), List.of(), List.of(200L), null));

        Task target = requireSingleTask(instance.getId(), "selectedTarget");
        assertThat(target.getAssignee()).isEqualTo("200");
        assertThat(taskService.getIdentityLinksForTask(target.getId()))
                .noneMatch(link -> "candidate".equals(link.getType()));
        assertThat(historyService.createHistoricTaskInstanceQuery()
                .taskId(source.getId()).finished().singleResult().getCompletedBy())
                .isEqualTo(CURRENT_USER_ID);
    }

    /**
     * 验证受控动态并行多实例完成实时复核执行树并以 expectedRevision 推进正式版本。
     *
     * @return void，revision、活动兄弟任务、完成历史或结构化审计不一致时失败
     */
    @Test
    void advancesRevisionWhenCompletingControlledDynamicMultiInstanceTask()
    {
        String collectionVariable = WorkflowMultiInstanceVariables
                .userCollectionName("miReview");
        ProcessInstance instance = runtimeService.startProcessInstanceByKey(
                "completionMi", Map.of(collectionVariable, List.of(100L, 200L)));
        List<Task> initialTasks = taskService.createTaskQuery()
                .processInstanceId(instance.getId()).taskDefinitionKey("miReview")
                .active().list();
        assertThat(initialTasks).extracting(Task::getAssignee)
                .containsExactlyInAnyOrder("100", "200");
        Task currentTask = initialTasks.stream()
                .filter(task -> CURRENT_USER_ID.equals(task.getAssignee()))
                .findFirst().orElseThrow();

        lifecycleService.completeTask(new WorkflowTaskCompleteRequest(currentTask.getId(),
                "会签成员完成", Map.of(), List.of(), List.of(), 0L));

        assertThat(runtimeService.getVariable(instance.getId(),
                WorkflowMultiInstanceVariables.revisionName("miReview"))).isEqualTo(1);
        assertThat(taskService.createTaskQuery().processInstanceId(instance.getId())
                .taskDefinitionKey("miReview").active().list())
                .singleElement().extracting(Task::getAssignee).isEqualTo("200");
        HistoricTaskInstance historicTask = historyService.createHistoricTaskInstanceQuery()
                .taskId(currentTask.getId()).finished().singleResult();
        assertThat(historicTask.getCompletedBy()).isEqualTo(CURRENT_USER_ID);
        assertThat(taskService.getProcessInstanceComments(instance.getId(), "1"))
                .anySatisfy(comment -> assertThat(comment.getFullMessage())
                        .contains("\"multiInstanceActivityId\":\"miReview\"")
                        .contains("\"beforeRevision\":0")
                        .contains("\"afterRevision\":1"));
    }

    /**
     * 验证受控循环先 repeat 再 exit 时的真实任务重建、路由变量、逐轮数据库审计和 comment。
     *
     * @return void，任一轮状态、变量、数据库记录或历史审计不一致时失败
     */
    @Test
    void persistsRepeatAndExitLoopStateAndAudit()
    {
        ProcessInstance instance = runtimeService.startProcessInstanceByKey("completionLoop");
        Task firstRound = requireSingleTask(instance.getId(), "loopReview");

        lifecycleService.completeTask(new WorkflowTaskCompleteRequest(firstRound.getId(),
                "再次整改", Map.of("loopDecision", "repeat"),
                List.of(), List.of(), null));

        Task secondRound = requireSingleTask(instance.getId(), "loopReview");
        assertThat(secondRound.getId()).isNotEqualTo(firstRound.getId());
        assertThat(runtimeService.getVariable(instance.getId(), "loopRoute")).isEqualTo(true);
        assertThat(runtimeService.getVariable(instance.getId(), "loopIteration")).isEqualTo(1);
        List<WfControlledLoopExecution> repeated = loopExecutionMapper
                .selectByProcessInstanceId(instance.getId());
        assertThat(repeated).singleElement().satisfies(round ->
        {
            assertThat(round.getTaskId()).isEqualTo(firstRound.getId());
            assertThat(round.getIterationNo()).isEqualTo(1);
            assertThat(round.getDecisionValue()).isEqualTo("repeat");
            assertThat(round.getOutcome()).isEqualTo("REPEAT");
        });

        lifecycleService.completeTask(new WorkflowTaskCompleteRequest(secondRound.getId(),
                "退出循环", Map.of("loopDecision", "exit"),
                List.of(), List.of(), null));

        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(instance.getId()).count()).isZero();
        assertThat(historicVariable(instance.getId(), "loopRoute")).isEqualTo(false);
        assertThat(historicVariable(instance.getId(), "loopIteration")).isEqualTo(2);
        List<WfControlledLoopExecution> rounds = loopExecutionMapper
                .selectByProcessInstanceId(instance.getId());
        assertThat(rounds).extracting(WfControlledLoopExecution::getOutcome)
                .containsExactly("REPEAT", "EXIT");
        assertThat(rounds).extracting(WfControlledLoopExecution::getIterationNo)
                .containsExactly(1, 2);
        assertThat(taskService.getProcessInstanceComments(instance.getId(),
                WorkflowControlledLoopService.COMMENT_TYPE))
                .extracting(Comment::getTaskId)
                .containsExactlyInAnyOrder(firstRound.getId(), secondRound.getId());
    }

    /**
     * 查询实例当前唯一活动任务并校验预期节点，避免夹具错误掩盖生产行为。
     *
     * @param processInstanceId String，真实流程实例主键
     * @param taskDefinitionKey String，预期唯一活动节点 key
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
     * 读取已结束实例的最终历史变量值。
     *
     * @param processInstanceId String，已结束流程实例主键
     * @param variableName String，待查询变量名
     * @return Object，Flowable 完整历史中的最终变量值
     */
    private Object historicVariable(String processInstanceId, String variableName)
    {
        HistoricVariableInstance variable = historyService.createHistoricVariableInstanceQuery()
                .processInstanceId(processInstanceId).variableName(variableName).singleResult();
        assertThat(variable).isNotNull();
        return variable.getValue();
    }

    /**
     * 创建表单快照和受控循环配置组成的不可变部署业务制品。
     *
     * @param deploymentId String，真实父部署主键
     * @return WorkflowDeploymentArtifacts，可由生产仓库读取的完整制品集合
     */
    private WorkflowDeploymentArtifacts deploymentArtifacts(String deploymentId)
    {
        WfDeployForm form = form(deploymentId, 1L, "approvalForm",
                "formReview", "普通审批表单", APPROVAL_FORM);
        WfDeployForm loopForm = form(deploymentId, 2L, "loopForm",
                "loopReview", "循环审批表单", LOOP_FORM);

        WfDeployControlledLoop loop = new WfDeployControlledLoop();
        loop.setDeployId(deploymentId);
        loop.setProcessKey("completionLoop");
        loop.setActivityId("loopReview");
        loop.setActivityName("循环审批");
        loop.setDecisionVariable("loopDecision");
        loop.setRepeatValue("repeat");
        loop.setExitValue("exit");
        loop.setMaxIterations(3);
        loop.setRouteVariable("loopRoute");
        loop.setIterationVariable("loopIteration");
        loop.setCreateBy(CURRENT_USER_ID);
        loop.setCreateTime(new Date());
        return new WorkflowDeploymentArtifacts(List.of(form, loopForm), List.of(),
                List.of(loop), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    /**
     * 创建一个正式模板来源的节点表单部署快照。
     *
     * @param deploymentId String，真实父部署主键
     * @param formId Long，测试模板主键
     * @param formKey String，BPMN 表单 key
     * @param nodeKey String，BPMN 用户任务 key
     * @param nodeName String，节点展示名称
     * @param content String，部署时冻结的表单 JSON
     * @return WfDeployForm，字段完整的表单快照
     */
    private WfDeployForm form(String deploymentId, Long formId, String formKey,
            String nodeKey, String nodeName, String content)
    {
        WfDeployForm form = new WfDeployForm();
        form.setDeployId(deploymentId);
        form.setSourceType("TEMPLATE");
        form.setFormId(formId);
        form.setFormKey(formKey);
        form.setNodeKey(nodeKey);
        form.setFormName(nodeName);
        form.setNodeName(nodeName);
        form.setContent(content);
        form.setCreateTime(new Date());
        return form;
    }

    /** 真实任务完成测试共用的四个可执行流程。 */
    private static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
              xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
              xmlns:flowable="http://flowable.org/bpmn" targetNamespace="completion-context-it">
              <process id="completionForm" name="completionForm" isExecutable="true">
                <startEvent id="formStart"/>
                <userTask id="formReview" name="普通表单审批" flowable:assignee="100" flowable:formKey="approvalForm"/>
                <endEvent id="formEnd"/>
                <sequenceFlow sourceRef="formStart" targetRef="formReview"/>
                <sequenceFlow sourceRef="formReview" targetRef="formEnd"/>
              </process>

              <process id="completionNext" name="completionNext" isExecutable="true">
                <startEvent id="nextStart"/>
                <userTask id="selectSource" name="选择下一办理人" flowable:assignee="100"/>
                <userTask id="selectedTarget" name="动态后继" flowable:assignee="999"/>
                <endEvent id="nextEnd"/>
                <sequenceFlow sourceRef="nextStart" targetRef="selectSource"/>
                <sequenceFlow sourceRef="selectSource" targetRef="selectedTarget"/>
                <sequenceFlow sourceRef="selectedTarget" targetRef="nextEnd"/>
              </process>

              <process id="completionMi" name="completionMi" isExecutable="true">
                <startEvent id="miStart"/>
                <userTask id="miReview" name="动态会签" flowable:assignee="${assignee}">
                  <multiInstanceLoopCharacteristics isSequential="false"
                    flowable:collection="${multiInstanceHandler.getUserIds(execution)}"
                    flowable:elementVariable="assignee">
                    <completionCondition xsi:type="tFormalExpression"><![CDATA[${nrOfCompletedInstances == nrOfInstances}]]></completionCondition>
                  </multiInstanceLoopCharacteristics>
                </userTask>
                <endEvent id="miEnd"/>
                <sequenceFlow sourceRef="miStart" targetRef="miReview"/>
                <sequenceFlow sourceRef="miReview" targetRef="miEnd"/>
              </process>

              <process id="completionLoop" name="completionLoop" isExecutable="true">
                <startEvent id="loopStart"/>
                <userTask id="loopReview" name="循环审批" flowable:assignee="100" flowable:formKey="loopForm"/>
                <exclusiveGateway id="loopGateway" default="loopExit"/>
                <endEvent id="loopEnd"/>
                <sequenceFlow sourceRef="loopStart" targetRef="loopReview"/>
                <sequenceFlow sourceRef="loopReview" targetRef="loopGateway"/>
                <sequenceFlow id="loopRepeat" sourceRef="loopGateway" targetRef="loopReview">
                  <conditionExpression xsi:type="tFormalExpression"><![CDATA[${loopRoute}]]></conditionExpression>
                </sequenceFlow>
                <sequenceFlow id="loopExit" sourceRef="loopGateway" targetRef="loopEnd"/>
              </process>
            </definitions>
            """;
}
