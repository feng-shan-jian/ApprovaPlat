package com.ruoyi.flowable.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.UserTask;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.delegate.TaskListener;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.flowable.task.api.Task;
import org.flowable.task.service.delegate.DelegateTask;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.flowable.domain.WfMultiInstanceRound;
import com.ruoyi.flowable.domain.WorkflowMultiInstanceRoundStatus;
import com.ruoyi.flowable.domain.WorkflowMultiInstanceUserRow;
import com.ruoyi.flowable.domain.dto.WorkflowMultiInstanceAdjustmentAction;
import com.ruoyi.flowable.domain.dto.WorkflowMultiInstanceAdjustmentRequest;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.engine.WorkflowExceptionTranslator;
import com.ruoyi.flowable.identity.WorkflowAuthenticationContext;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityCodec;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.identity.WorkflowUserSelectionValidator;
import com.ruoyi.flowable.listener.WorkflowMultiInstanceRoundInterruptionListener;
import com.ruoyi.flowable.listener.WorkflowUserTaskListener;
import com.ruoyi.flowable.mapper.WfMultiInstanceRoundMapper;
import com.ruoyi.flowable.mapper.WorkflowMultiInstanceUserMapper;
import com.ruoyi.flowable.service.identity.WorkflowParticipantRuleRuntimeService;
import com.ruoyi.flowable.service.notification.WorkflowNotificationService;

/**
 * 为多组轮次事务集成测试提供真实 Flowable 生命周期、正式 Mapper、查询和快照能力。
 */
abstract class WorkflowMultiInstanceRoundFlowableSupport
{
    /** 默认受控多实例成员。 */
    protected static final List<String> MEMBERS = List.of("201", "202");

    /** 测试注入的稳定监听故障消息。 */
    protected static final String AUDIT_FAILURE_MESSAGE =
            "injected round completion audit failure";

    protected ProcessEngine processEngine;
    protected RepositoryService repositoryService;
    protected RuntimeService runtimeService;
    protected TaskService taskService;
    protected HistoryService historyService;
    protected JdbcTemplate jdbcTemplate;
    protected TransactionTemplate transactionTemplate;
    protected WfMultiInstanceRoundMapper roundMapper;
    protected WorkflowMultiInstanceRoundService roundService;
    protected WorkflowMultiInstanceService multiInstanceService;
    protected WorkflowIdentityResolver identityResolver;
    protected WorkflowEngineOperations engineOperations;

    /** 当前动态接口操作人，测试在每次正式查询或调整前显式设置。 */
    private final AtomicReference<String> currentUserId =
            new AtomicReference<>(MEMBERS.get(0));

    /** complete 轮次 CAS 后的审计故障开关。 */
    private final AtomicBoolean failNextCompleteAudit = new AtomicBoolean();

    /**
     * 为每个用例创建共享 H2 数据源、真实 Flowable 引擎、正式 Mapper、
     * 生产任务监听器、全局中断监听器和领域服务。
     *
     * @return void，无返回值；无关身份、SLA、抄送和通知依赖使用显式 mock 隔离
     */
    @BeforeEach
    protected final void setUpRoundEngine()
    {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:mi-round-" + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        dataSource.setUser("sa");
        DataSourceTransactionManager transactionManager =
                new DataSourceTransactionManager(dataSource);
        transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setIsolationLevel(
                TransactionDefinition.ISOLATION_REPEATABLE_READ);
        jdbcTemplate = new JdbcTemplate(dataSource);
        createRoundTable();
        WfMultiInstanceRoundMapper realMapper = createRoundMapper(dataSource);
        // 可委托 mock 保留正式 XML 行为，同时允许单个用例在明确 Mapper 调用点注入故障。
        roundMapper = mock(WfMultiInstanceRoundMapper.class, delegatesTo(realMapper));

        identityResolver = mock(WorkflowIdentityResolver.class);
        when(identityResolver.resolveApprovalEligibleUserIds(anyCollection()))
                .thenAnswer(invocation ->
                {
                    Collection<String> requested = invocation.getArgument(0);
                    return new LinkedHashSet<>(requested);
                });
        when(identityResolver.resolveCurrentIdentity()).thenAnswer(invocation ->
                new WorkflowCurrentIdentity(currentUserId.get(), Set.of()));
        WorkflowUserSelectionValidator userSelectionValidator =
                new WorkflowUserSelectionValidator(identityResolver);
        WorkflowMultiInstanceHandler handler =
                new WorkflowMultiInstanceHandler(userSelectionValidator);
        LateBindingTaskListener listenerBinding = new LateBindingTaskListener();

        SpringProcessEngineConfiguration configuration =
                new SpringProcessEngineConfiguration();
        configuration.setDataSource(dataSource);
        configuration.setTransactionManager(transactionManager);
        configuration.setDatabaseSchemaUpdate("true");
        configuration.setHistory("full");
        configuration.setBeans(Map.of(
                "multiInstanceHandler", handler,
                "userTaskListener", listenerBinding));
        processEngine = configuration.buildProcessEngine();
        repositoryService = processEngine.getRepositoryService();
        runtimeService = processEngine.getRuntimeService();
        taskService = processEngine.getTaskService();
        historyService = processEngine.getHistoryService();

        roundService = new WorkflowMultiInstanceRoundService(roundMapper,
                repositoryService, runtimeService);
        // 所有轮次事务集成组都必须带上生产全局监听器，同时验证自然完成、显式终止和原生中断不双写。
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("workflowMultiInstanceRoundService", roundService);
        processEngine.getProcessEngineConfiguration().getEventDispatcher()
                .addEventListener(new WorkflowMultiInstanceRoundInterruptionListener(
                        beanFactory.getBeanProvider(
                                WorkflowMultiInstanceRoundService.class)));
        WorkflowUserTaskAuditService auditService = mock(WorkflowUserTaskAuditService.class);
        doAnswer(invocation ->
        {
            String eventName = invocation.getArgument(0);
            if (TaskListener.EVENTNAME_COMPLETE.equals(eventName)
                    && failNextCompleteAudit.compareAndSet(true, false))
            {
                throw new FlowableException(AUDIT_FAILURE_MESSAGE);
            }
            return null;
        }).when(auditService).recordAudit(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.nullable(String.class));
        WorkflowUserTaskListener productionListener = new WorkflowUserTaskListener(
                auditService, mock(WorkflowTaskSlaRuntimeService.class),
                mock(WorkflowParticipantRuleRuntimeService.class),
                mock(WorkflowAutomaticCopyService.class),
                mock(WorkflowNotificationService.class), roundService);
        listenerBinding.bind(productionListener);

        WorkflowAuthenticationContext authenticationContext =
                new WorkflowAuthenticationContext(processEngine.getIdentityService(),
                        new WorkflowIdentityCodec());
        engineOperations = new WorkflowEngineOperations(
                authenticationContext, new WorkflowExceptionTranslator(), identityResolver);
        WorkflowMultiInstanceUserMapper userMapper =
                mock(WorkflowMultiInstanceUserMapper.class);
        when(userMapper.selectUserNamesByIds(anyList())).thenAnswer(invocation ->
        {
            List<Long> ids = invocation.getArgument(0);
            return ids.stream().map(id -> new WorkflowMultiInstanceUserRow(
                    id, "用户" + id)).toList();
        });
        multiInstanceService = new WorkflowMultiInstanceService(engineOperations,
                identityResolver, userSelectionValidator, userMapper, repositoryService,
                runtimeService, taskService, historyService, roundService);
        repositoryService.createDeployment()
                .addClasspathResource(
                        "bpmn/workflow-multi-instance-round-lifecycle.bpmn20.xml")
                .deploy();
    }

    /**
     * 关闭真实引擎并清理线程身份，隔离各测试的任务、execution、变量与轮次。
     *
     * @return void，无返回值
     */
    @AfterEach
    protected final void tearDownRoundEngine()
    {
        if (processEngine != null)
        {
            processEngine.close();
        }
    }

    /**
     * 启动需要流程变量成员集合的受控多实例流程。
     *
     * @param processKey String，部署流程定义 key
     * @param activityId String，受控多实例活动 ID
     * @param members List&lt;String&gt;，有序成员用户主键
     * @param additionalVariables Map&lt;String,Object&gt;，循环等场景附加变量
     * @return ProcessInstance，已经创建正式首轮及全部成员任务的活动实例
     */
    protected final ProcessInstance start(String processKey, String activityId,
            List<String> members, Map<String, Object> additionalVariables)
    {
        Map<String, Object> variables = new LinkedHashMap<>(additionalVariables);
        variables.put(WorkflowMultiInstanceVariables.userCollectionName(activityId),
                members.stream().map(Long::valueOf).toList());
        return runtimeService.startProcessInstanceByKey(processKey, variables);
    }

    /**
     * 启动无需外部成员变量的固定或指定成员流程。
     *
     * @param processKey String，部署流程定义 key
     * @return ProcessInstance，已经创建正式首轮的活动实例
     */
    protected final ProcessInstance start(String processKey)
    {
        return runtimeService.startProcessInstanceByKey(processKey);
    }

    /**
     * 设置下一次生产服务命令使用的当前正式用户。
     *
     * @param userId String，规范数字用户主键；测试需与发起人或任务办理人一致
     * @return void，无返回值
     */
    protected final void setCurrentUser(String userId)
    {
        currentUserId.set(userId);
    }

    /**
     * 通过生产完成预留、真实 taskService.complete 和写后对账完成一个多实例成员任务。
     *
     * @param task Task，当前活动成员任务
     * @param expectedRevision int，客户端读取到的正式 revision
     * @return void，任一引擎或轮次事实不一致时整个事务回滚
     */
    protected final void complete(Task task, int expectedRevision)
    {
        transactionTemplate.executeWithoutResult(status ->
        {
            Task current = taskService.createTaskQuery().taskId(task.getId())
                    .active().singleResult();
            assertThat(current).isNotNull();
            WorkflowMultiInstanceService.CompletionRevision completion =
                    multiInstanceService.reserveCompletionRevision(current,
                            requireUserTask(current), (long) expectedRevision);
            taskService.complete(current.getId());
            multiInstanceService.verifyCompletionResult(
                    current.getProcessInstanceId(), completion);
        });
    }

    /**
     * 使用生产动态调整服务增加一个正式成员。
     *
     * @param currentTask Task，当前操作人的活动任务
     * @param expectedRevision int，客户端预期 revision
     * @param userId long，新增成员用户主键
     * @return void，调整和写后对账在同一可重复读事务内完成
     */
    protected final void addMember(Task currentTask, int expectedRevision, long userId)
    {
        currentUserId.set(currentTask.getAssignee());
        transactionTemplate.execute(status -> multiInstanceService.adjust(
                new WorkflowMultiInstanceAdjustmentRequest(currentTask.getId(),
                        WorkflowMultiInstanceAdjustmentAction.ADD,
                        (long) expectedRevision, "增加复核成员", List.of(userId), null)));
    }

    /**
     * 使用生产动态调整服务删除同根尚未完成的 sibling 成员。
     *
     * @param currentTask Task，当前操作人的活动任务
     * @param targetTask Task，待删除的 sibling 任务
     * @param expectedRevision int，客户端预期 revision
     * @return void，调整和写后对账在同一可重复读事务内完成
     */
    protected final void removeMember(Task currentTask, Task targetTask,
            int expectedRevision)
    {
        currentUserId.set(currentTask.getAssignee());
        transactionTemplate.execute(status -> multiInstanceService.adjust(
                new WorkflowMultiInstanceAdjustmentRequest(currentTask.getId(),
                        WorkflowMultiInstanceAdjustmentAction.REMOVE,
                        (long) expectedRevision, "移除重复成员", List.of(),
                        targetTask.getId())));
    }

    /**
     * 查询指定节点活动任务并按办理人稳定排序。
     *
     * @param processInstanceId String，流程实例主键
     * @param activityId String，受控多实例活动 ID
     * @return List&lt;Task&gt;，当前活动成员任务
     */
    protected final List<Task> tasks(String processInstanceId, String activityId)
    {
        return taskService.createTaskQuery().processInstanceId(processInstanceId)
                .taskDefinitionKey(activityId).active().list().stream()
                .sorted(Comparator.comparing(Task::getAssignee)).toList();
    }

    /**
     * 查询指定办理人的唯一活动任务。
     *
     * @param processInstanceId String，流程实例主键
     * @param activityId String，受控多实例活动 ID
     * @param assignee String，目标办理人主键
     * @return Task，唯一匹配任务
     */
    protected final Task task(String processInstanceId, String activityId,
            String assignee)
    {
        Task task = taskService.createTaskQuery().processInstanceId(processInstanceId)
                .taskDefinitionKey(activityId).taskAssignee(assignee)
                .active().singleResult();
        assertThat(task).isNotNull();
        return task;
    }

    /**
     * 查询实例全部轮次并按轮次主键稳定返回。
     *
     * @param processInstanceId String，流程实例主键
     * @return List&lt;WfMultiInstanceRound&gt;，正式 Mapper 返回的轮次列表
     */
    protected final List<WfMultiInstanceRound> rounds(String processInstanceId)
    {
        return roundMapper.selectByProcessInstanceId(processInstanceId).stream()
                .sorted(Comparator.comparing(WfMultiInstanceRound::getRoundId)).toList();
    }

    /**
     * 查询实例当前唯一 ACTIVE 轮次。
     *
     * @param processInstanceId String，流程实例主键
     * @param activityId String，受控多实例活动 ID
     * @return WfMultiInstanceRound，唯一 ACTIVE 轮次
     */
    protected final WfMultiInstanceRound activeRound(String processInstanceId,
            String activityId)
    {
        List<WfMultiInstanceRound> rounds = roundMapper
                .selectActiveByProcessInstanceAndActivity(processInstanceId, activityId);
        assertThat(rounds).singleElement();
        return rounds.get(0);
    }

    /**
     * 冻结当前流程的任务、execution、变量和轮次，用于故障前后严格回滚对账。
     *
     * @param processInstanceId String，活动流程实例主键
     * @return RuntimeSnapshot，可直接值比较的完整事务快照
     */
    protected final RuntimeSnapshot capture(String processInstanceId)
    {
        List<TaskFact> taskFacts = taskService.createTaskQuery()
                .processInstanceId(processInstanceId).active().list().stream()
                .map(task -> new TaskFact(task.getId(), task.getExecutionId(),
                        task.getTaskDefinitionKey(), task.getAssignee(),
                        taskService.getVariableLocal(task.getId(),
                                WorkflowMultiInstanceVariables
                                    .COMPLETION_REVISION_VARIABLE)))
                .sorted(Comparator.comparing(TaskFact::id)).toList();
        List<ExecutionFact> executionFacts = runtimeService.createExecutionQuery()
                .processInstanceId(processInstanceId).list().stream()
                .map(execution -> new ExecutionFact(execution.getId(),
                        execution.getParentId(), execution.getActivityId()))
                .sorted(Comparator.comparing(ExecutionFact::id)).toList();
        List<RoundFact> roundFacts = rounds(processInstanceId).stream()
                .map(round -> new RoundFact(round.getRoundId(), round.getRootExecutionId(),
                        round.getRoundNo(), round.getMembersJson(), round.getRevisionNo(),
                        round.getRoundStatus(), round.getCompleteTime(),
                        round.getTerminateTime()))
                .toList();
        return new RuntimeSnapshot(taskFacts, executionFacts,
                new TreeMap<>(runtimeService.getVariables(processInstanceId)), roundFacts);
    }

    /**
     * 开启下一次 complete 审计故障，故障发生在轮次监听写入之后。
     *
     * @return void，无返回值
     */
    protected final void failNextCompleteAudit()
    {
        failNextCompleteAudit.set(true);
    }

    /**
     * 从任务所属部署模型定位当前受控 UserTask。
     *
     * @param task Task，真实活动任务
     * @return UserTask，任务流程定义所属 Process 中的节点
     */
    private UserTask requireUserTask(Task task)
    {
        ProcessDefinition definition = repositoryService.getProcessDefinition(
                task.getProcessDefinitionId());
        BpmnModel model = repositoryService.getBpmnModel(task.getProcessDefinitionId());
        org.flowable.bpmn.model.Process process = model.getProcessById(definition.getKey());
        return (UserTask) process.getFlowElement(task.getTaskDefinitionKey(), true);
    }

    /**
     * 创建轮次正式 Mapper 在 H2 事务集成测试中使用的等价业务表。
     *
     * @return void，建表失败时终止测试装配
     */
    private void createRoundTable()
    {
        jdbcTemplate.execute("""
                create table wf_multi_instance_round (
                  round_id bigint generated by default as identity primary key,
                  deploy_id varchar(64) not null,
                  process_definition_id varchar(64) not null,
                  process_instance_id varchar(64) not null,
                  activity_id varchar(64) not null,
                  root_execution_id varchar(64) not null,
                  round_no int not null,
                  mode varchar(8) not null,
                  members_json varchar(4096) not null,
                  revision_no int not null,
                  round_status varchar(16) not null,
                  return_source_task_id varchar(64),
                  return_actor_user_id varchar(64),
                  applicant_task_id varchar(64),
                  create_time timestamp(3) not null,
                  return_time timestamp(3),
                  reopen_time timestamp(3),
                  complete_time timestamp(3),
                  terminate_time timestamp(3),
                  constraint uk_round_number unique(process_instance_id, activity_id, round_no),
                  constraint uk_round_root unique(root_execution_id)
                )
                """);
    }

    /**
     * 使用生产 XML 创建与 Flowable 共享同一 Spring 事务的数据 Mapper。
     *
     * @param dataSource DataSource，Flowable 引擎和轮次表共享的数据源
     * @return WfMultiInstanceRoundMapper，正式 MyBatis XML Mapper
     */
    private WfMultiInstanceRoundMapper createRoundMapper(DataSource dataSource)
    {
        Environment environment = new Environment("mi-round-it",
                new SpringManagedTransactionFactory(), dataSource);
        org.apache.ibatis.session.Configuration configuration =
                new org.apache.ibatis.session.Configuration(environment);
        configuration.addMapper(WfMultiInstanceRoundMapper.class);
        String resource = "mapper/flowable/WfMultiInstanceRoundMapper.xml";
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
        return new SqlSessionTemplate(factory).getMapper(WfMultiInstanceRoundMapper.class);
    }

    /**
     * 延迟绑定生产监听器，解决 Flowable 服务只能在引擎构建完成后取得的测试装配顺序。
     */
    private static final class LateBindingTaskListener implements TaskListener
    {
        /** 引擎启动后绑定且后续只读的生产监听器。 */
        private WorkflowUserTaskListener delegate;

        /**
         * 绑定本用例唯一生产监听器。
         *
         * @param listener WorkflowUserTaskListener，依赖真实引擎服务的监听器
         * @return void，重复绑定被拒绝
         */
        private void bind(WorkflowUserTaskListener listener)
        {
            if (delegate != null || listener == null)
            {
                throw new IllegalStateException("生产用户任务监听器绑定不合法");
            }
            delegate = listener;
        }

        /**
         * 把真实 Flowable 任务事件转发给已经绑定的生产监听器。
         *
         * @param delegateTask DelegateTask，真实 create、assignment 或 complete 事件
         * @return void，未绑定时中止流程命令
         */
        @Override
        public void notify(DelegateTask delegateTask)
        {
            if (delegate == null)
            {
                throw new IllegalStateException("生产用户任务监听器尚未绑定");
            }
            delegate.notify(delegateTask);
        }
    }

    /**
     * 活动任务事务快照。
     *
     * @param id String，任务主键
     * @param executionId String，任务 execution 主键
     * @param activityId String，任务活动 ID
     * @param assignee String，办理人主键
     * @param completionRevision Object，正式完成链 task-local revision 预留标记
     */
    protected record TaskFact(String id, String executionId, String activityId,
            String assignee, Object completionRevision)
    {
    }

    /**
     * execution 树事务快照。
     *
     * @param id String，execution 主键
     * @param parentId String，父 execution 主键
     * @param activityId String，活动 ID
     */
    protected record ExecutionFact(String id, String parentId, String activityId)
    {
    }

    /**
     * 轮次事务快照。
     *
     * @param id Long，轮次主键
     * @param rootExecutionId String，多实例根主键
     * @param roundNo Integer，轮次号
     * @param membersJson String，有序成员 JSON
     * @param revision Integer，轮次 revision
     * @param status WorkflowMultiInstanceRoundStatus，轮次状态
     * @param completeTime java.time.LocalDateTime，正常完成时间
     * @param terminateTime java.time.LocalDateTime，异常关闭时间
     */
    protected record RoundFact(Long id, String rootExecutionId, Integer roundNo,
            String membersJson, Integer revision,
            WorkflowMultiInstanceRoundStatus status,
            java.time.LocalDateTime completeTime,
            java.time.LocalDateTime terminateTime)
    {
    }

    /**
     * 故障前后可直接值比较的完整运行时快照。
     *
     * @param tasks List&lt;TaskFact&gt;，活动任务
     * @param executions List&lt;ExecutionFact&gt;，运行 execution 树
     * @param variables Map&lt;String,Object&gt;，流程实例变量
     * @param rounds List&lt;RoundFact&gt;，正式业务轮次
     */
    protected record RuntimeSnapshot(List<TaskFact> tasks,
            List<ExecutionFact> executions, Map<String, Object> variables,
            List<RoundFact> rounds)
    {
    }
}
