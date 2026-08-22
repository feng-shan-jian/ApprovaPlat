package com.ruoyi.flowable.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
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
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.flowable.spring.SpringProcessEngineConfiguration;
import com.ruoyi.flowable.domain.WfControlledLoopExecution;
import com.ruoyi.flowable.domain.WfDeployControlledLoop;
import com.ruoyi.flowable.domain.WfDeployForm;
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
        WorkflowMultiInstanceHandler multiInstanceHandler =
                new WorkflowMultiInstanceHandler(userSelectionValidator);

        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:completion-context-" + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        dataSource.setUser("sa");
        DataSourceTransactionManager transactionManager =
                new DataSourceTransactionManager(dataSource);

        SpringProcessEngineConfiguration configuration =
                new SpringProcessEngineConfiguration();
        configuration.setDataSource(dataSource);
        configuration.setTransactionManager(transactionManager);
        configuration.setDatabaseSchemaUpdate("true");
        configuration.setHistory("full");
        configuration.setBeans(Map.of("multiInstanceHandler", multiInstanceHandler));
        processEngine = configuration.buildProcessEngine();
        repositoryService = spy(processEngine.getRepositoryService());
        runtimeService = processEngine.getRuntimeService();
        taskService = processEngine.getTaskService();
        historyService = processEngine.getHistoryService();
        jdbcTemplate = new JdbcTemplate(dataSource);
        createLoopExecutionTable();
        loopExecutionMapper = createLoopMapper(dataSource);

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
        WorkflowEngineOperations engineOperations = transactionalProxy(
                operationsTarget, transactionManager);

        WorkflowAttachmentService attachmentService = mock(WorkflowAttachmentService.class);
        when(attachmentService.prepareTaskVariables(anyString(), anyString(), anyMap(), anyMap()))
                .thenAnswer(invocation -> Map.copyOf(
                        (Map<String, Object>) invocation.getArgument(2)));
        WorkflowNotificationService notificationService = mock(WorkflowNotificationService.class);
        WorkflowTaskCopyService taskCopyService = new WorkflowTaskCopyService(
                userSelectionValidator, mock(WfCopyMapper.class),
                mock(WorkflowRuntimeTaskMapper.class), repositoryService, runtimeService,
                mock(SysUserMapper.class), notificationService);
        WorkflowNextTaskAssignmentService nextTaskAssignmentService =
                new WorkflowNextTaskAssignmentService(userSelectionValidator,
                        taskService, runtimeService);
        WorkflowMultiInstanceService multiInstanceService = new WorkflowMultiInstanceService(
                engineOperations, identityResolver, userSelectionValidator,
                mock(WorkflowMultiInstanceUserMapper.class), repositoryService,
                runtimeService, taskService, historyService);
        WorkflowControlledLoopService controlledLoopService =
                new WorkflowControlledLoopService(runtimeService, taskService,
                        artifactRepository, loopExecutionMapper);

        lifecycleService = new WorkflowTaskLifecycleService(
                engineOperations, identityResolver, repositoryService, runtimeService,
                taskService, historyService, artifactRepository,
                new WorkflowStartVariableValidator(new WorkflowFormTemplateValidator()),
                attachmentService, mock(WorkflowTaskMovementPolicy.class), taskCopyService,
                nextTaskAssignmentService, multiInstanceService, controlledLoopService,
                notificationService, mock(WorkflowProcessInstanceService.class));
        clearInvocations(repositoryService);
    }

    /**
     * 关闭真实引擎，避免测试之间共享运行时、历史表和表达式 Bean。
     *
     * @return void，无返回值
     */
    @AfterEach
    void tearDown()
    {
        if (processEngine != null)
        {
            processEngine.close();
        }
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
     * 创建受控循环正式 Mapper 所需的 H2 业务表及双唯一并发约束。
     *
     * @return void，建表失败时终止测试装配
     */
    private void createLoopExecutionTable()
    {
        jdbcTemplate.execute("""
                create table wf_controlled_loop_execution (
                  execution_id bigint generated by default as identity primary key,
                  deploy_id varchar(64) not null,
                  process_definition_id varchar(64) not null,
                  process_instance_id varchar(64) not null,
                  activity_id varchar(255) not null,
                  task_id varchar(64) not null,
                  iteration_no int not null,
                  actor_user_id varchar(64) not null,
                  decision_value varchar(128) not null,
                  outcome varchar(16) not null,
                  create_time timestamp(3) not null,
                  constraint uk_loop_task unique (task_id),
                  constraint uk_loop_round unique (process_instance_id, activity_id, iteration_no)
                )
                """);
    }

    /**
     * 使用生产 XML 创建与 Spring 事务共享同一 H2 数据源的循环审计 Mapper。
     *
     * @param dataSource DataSource，Flowable 引擎和业务表共享的数据源
     * @return WfControlledLoopExecutionMapper，正式 MyBatis XML Mapper
     */
    private WfControlledLoopExecutionMapper createLoopMapper(DataSource dataSource)
    {
        Environment environment = new Environment("completion-context-it",
                new SpringManagedTransactionFactory(), dataSource);
        org.apache.ibatis.session.Configuration configuration =
                new org.apache.ibatis.session.Configuration(environment);
        configuration.addMapper(WfControlledLoopExecutionMapper.class);
        String resource = "mapper/flowable/WfControlledLoopExecutionMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource))
        {
            if (input == null)
            {
                throw new IllegalStateException("测试无法加载正式 Mapper: " + resource);
            }
            new XMLMapperBuilder(input, configuration, resource,
                    configuration.getSqlFragments()).parse();
        }
        catch (IOException | RuntimeException exception)
        {
            throw new IllegalStateException("测试解析正式 Mapper 失败: " + resource,
                    exception);
        }
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        return new SqlSessionTemplate(factory).getMapper(WfControlledLoopExecutionMapper.class);
    }

    /**
     * 为生产对象应用基于注解的真实 Spring 事务代理。
     *
     * @param target T，需要代理的生产服务
     * @param manager DataSourceTransactionManager，共享 H2 事务管理器
     * @return T，保留生产类型的 CGLIB 代理
     */
    @SuppressWarnings("unchecked")
    private <T> T transactionalProxy(T target, DataSourceTransactionManager manager)
    {
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice(new TransactionInterceptor(manager,
                new AnnotationTransactionAttributeSource()));
        return (T) proxyFactory.getProxy();
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
