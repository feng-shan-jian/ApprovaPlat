package com.ruoyi.flowable.service.task;

import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.delegate.TaskListener;
import org.flowable.engine.repository.Deployment;
import org.flowable.task.service.delegate.DelegateTask;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.FileSystemUtils;
import com.ruoyi.flowable.authorization.WorkflowProcessAccessService;
import com.ruoyi.flowable.config.WorkflowAttachmentProperties;
import com.ruoyi.flowable.domain.WfDeployForm;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.engine.WorkflowExceptionTranslator;
import com.ruoyi.flowable.identity.WorkflowAuthenticationContext;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityCodec;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.identity.WorkflowUserSelectionValidator;
import com.ruoyi.flowable.listener.WorkflowMultiInstanceRoundInterruptionListener;
import com.ruoyi.flowable.listener.WorkflowUserTaskListener;
import com.ruoyi.flowable.mapper.WfAttachmentMapper;
import com.ruoyi.flowable.mapper.WfControlledLoopExecutionMapper;
import com.ruoyi.flowable.mapper.WfCopyMapper;
import com.ruoyi.flowable.mapper.WfMultiInstanceRoundMapper;
import com.ruoyi.flowable.mapper.WfTaskSlaMapper;
import com.ruoyi.flowable.mapper.WorkflowMultiInstanceUserMapper;
import com.ruoyi.flowable.service.WorkflowFormTemplateValidator;
import com.ruoyi.flowable.service.attachment.WorkflowAttachmentService;
import com.ruoyi.flowable.service.attachment.WorkflowAttachmentStorage;
import com.ruoyi.flowable.service.identity.WorkflowParticipantRuleRuntimeService;
import com.ruoyi.flowable.service.model.WorkflowBusinessCalendarService;
import com.ruoyi.flowable.service.model.WorkflowDeploymentArtifactRepository;
import com.ruoyi.flowable.service.model.WorkflowDeploymentArtifacts;
import com.ruoyi.flowable.service.notification.WorkflowNotificationService;
import com.ruoyi.flowable.service.notification.WorkflowNotificationWriter;
import com.ruoyi.flowable.service.process.WorkflowProcessInstanceService;
import com.ruoyi.flowable.service.process.WorkflowStartVariableValidator;
import com.ruoyi.flowable.testsupport.WorkflowFlowableEngineTestSupport;
import com.ruoyi.flowable.testsupport.WorkflowH2SchemaMapperSupport;
import com.ruoyi.framework.web.service.PermissionService;

/**
 * 启动和关闭真实 Flowable/H2，并由 Spring 装配测试所需的生产 Bean。
 */
@Configuration(proxyBeanMethods = true)
@EnableTransactionManagement(proxyTargetClass = true)
@EnableAspectJAutoProxy(proxyTargetClass = true)
@Import({
        WorkflowMultiInstanceRoundRepository.class,
        WorkflowMultiInstanceRuntimeSnapshotReader.class,
        WorkflowMultiInstanceRoundLifecycleService.class,
        WorkflowMultiInstanceRoundTerminationService.class,
        WorkflowMultiInstanceService.class,
        WorkflowReturnedAssignmentCodec.class,
        WorkflowReturnedTaskStateService.class,
        WorkflowTaskMovementPolicy.class,
        WorkflowMultiInstanceGroupTransitionService.class,
        WorkflowTaskRequestValidator.class,
        WorkflowTaskRuntimeReader.class,
        WorkflowTaskBpmnReader.class,
        WorkflowTaskActionAuditWriter.class,
        WorkflowTaskConcurrencyExecutor.class,
        WorkflowTaskCompletionApplicationService.class,
        WorkflowTaskReturnApplicationService.class,
        WorkflowApplicationResubmitApplicationService.class,
        WorkflowTaskLifecycleService.class,
        WorkflowDeploymentArtifactRepository.class,
        WorkflowStartVariableValidator.class,
        WorkflowFormTemplateValidator.class,
        WorkflowProcessInstanceService.class
})
class WorkflowMultiInstanceEngineHarness
{
    /** 开始表单允许修改的业务字段和附件字段。 */
    private static final String START_FORM = """
            {"fields":[
              {"__vModel__":"requestTitle","__config__":{"layout":"colFormItem","tag":"el-input","required":true}},
              {"__vModel__":"evidence","limit":3,"__config__":{"layout":"colFormItem","tag":"el-upload"}}
            ]}
            """;

    /**
     * 创建线程隔离的当前用户容器。
     *
     * @return ThreadLocal&lt;String&gt;，默认首个审批成员
     */
    @Bean(destroyMethod = "remove")
    ThreadLocal<String> currentUserId()
    {
        return ThreadLocal.withInitial(() ->
                WorkflowMultiInstanceBusinessDriver.MEMBERS.get(0));
    }

    /**
     * 创建身份目录边界 mock，业务权限仍由生产服务执行。
     *
     * @param currentUserId ThreadLocal&lt;String&gt;，当前线程用户
     * @return WorkflowIdentityResolver，规范成员原样解析
     */
    @Bean
    @SuppressWarnings("unchecked")
    WorkflowIdentityResolver identityResolver(ThreadLocal<String> currentUserId)
    {
        WorkflowIdentityResolver resolver = mock(WorkflowIdentityResolver.class);
        when(resolver.resolveApprovalEligibleUserIds(anyCollection()))
                .thenAnswer(invocation -> new LinkedHashSet<>(
                        (Collection<String>) invocation.getArgument(0)));
        when(resolver.resolveCurrentIdentity()).thenAnswer(invocation ->
                new WorkflowCurrentIdentity(currentUserId.get(), Set.of()));
        return resolver;
    }

    /** @param resolver WorkflowIdentityResolver，身份目录；@return WorkflowUserSelectionValidator，正式成员校验器。 */
    @Bean
    WorkflowUserSelectionValidator userSelectionValidator(
            WorkflowIdentityResolver resolver)
    {
        return new WorkflowUserSelectionValidator(resolver);
    }

    /** @return WorkflowMultiInstanceTransitionCoordinator，命令内监听协议协调器。 */
    @Bean
    WorkflowMultiInstanceTransitionCoordinator transitionCoordinator()
    {
        return new WorkflowMultiInstanceTransitionCoordinator();
    }

    /** @return LateBindingTaskListener，引擎创建前注册的延迟任务监听器。 */
    @Bean
    LateBindingTaskListener lateBindingTaskListener()
    {
        return new LateBindingTaskListener();
    }

    /**
     * 启动独立 H2、Flowable 与表达式 Bean。
     *
     * @param validator WorkflowUserSelectionValidator，正式成员校验
     * @param coordinator WorkflowMultiInstanceTransitionCoordinator，迁移协议
     * @param listener LateBindingTaskListener，生产任务监听器占位
     * @return WorkflowFlowableEngineTestSupport，真实引擎 Harness
     */
    @Bean(destroyMethod = "close")
    WorkflowFlowableEngineTestSupport engineHarness(
            WorkflowUserSelectionValidator validator,
            WorkflowMultiInstanceTransitionCoordinator coordinator,
            LateBindingTaskListener listener)
    {
        WorkflowMultiInstanceHandler handler = new WorkflowMultiInstanceHandler(
                validator, coordinator);
        return WorkflowFlowableEngineTestSupport.start("mi-business",
                Map.of("multiInstanceHandler", handler,
                        "userTaskListener", listener));
    }

    /** @param harness WorkflowFlowableEngineTestSupport，引擎 Harness；@return DataSource，共享数据源。 */
    @Bean
    DataSource dataSource(WorkflowFlowableEngineTestSupport harness)
    {
        return harness.dataSource();
    }

    /** @param harness WorkflowFlowableEngineTestSupport，引擎 Harness；@return DataSourceTransactionManager，共享事务管理器。 */
    @Bean
    DataSourceTransactionManager transactionManager(
            WorkflowFlowableEngineTestSupport harness)
    {
        return harness.transactionManager();
    }

    /** @param harness WorkflowFlowableEngineTestSupport，引擎 Harness；@return TransactionTemplate，显式并发事务入口。 */
    @Bean
    TransactionTemplate transactionTemplate(WorkflowFlowableEngineTestSupport harness)
    {
        return harness.transactionTemplate();
    }

    /** @param harness WorkflowFlowableEngineTestSupport，引擎 Harness；@return ProcessEngine，真实 Flowable 引擎。 */
    @Bean
    ProcessEngine processEngine(WorkflowFlowableEngineTestSupport harness)
    {
        return harness.processEngine();
    }

    /** @param engine ProcessEngine，真实引擎；@return RepositoryService，部署读取服务。 */
    @Bean
    RepositoryService repositoryService(ProcessEngine engine)
    {
        return engine.getRepositoryService();
    }

    /**
     * 返回真实 RuntimeService。
     *
     * @param engine ProcessEngine，真实引擎
     * @return RuntimeService，真实运行服务
     */
    @Bean
    RuntimeService runtimeService(ProcessEngine engine)
    {
        return engine.getRuntimeService();
    }

    /** @param engine ProcessEngine，真实引擎；@return TaskService，真实任务服务。 */
    @Bean
    TaskService taskService(ProcessEngine engine)
    {
        return engine.getTaskService();
    }

    /** @param engine ProcessEngine，真实引擎；@return HistoryService，真实历史服务。 */
    @Bean
    HistoryService historyService(ProcessEngine engine)
    {
        return engine.getHistoryService();
    }

    /** @param harness WorkflowFlowableEngineTestSupport，引擎 Harness；@return JdbcTemplate，只读探针入口。 */
    @Bean
    JdbcTemplate jdbcTemplate(WorkflowFlowableEngineTestSupport harness)
    {
        return harness.jdbcTemplate();
    }

    /** @return WorkflowMultiInstanceFailureHook，唯一故障注入角色。 */
    @Bean
    WorkflowMultiInstanceFailureHook failureHook()
    {
        return new WorkflowMultiInstanceFailureHook();
    }

    /**
     * 创建正式轮次 Mapper 委托。
     *
     * @param dataSource DataSource，共享 H2
     * @param jdbcTemplate JdbcTemplate，兼容模式设置入口
     * @return WfMultiInstanceRoundMapper，正式 XML Mapper
     */
    @Bean("roundMapperDelegate")
    WfMultiInstanceRoundMapper roundMapperDelegate(DataSource dataSource,
            JdbcTemplate jdbcTemplate)
    {
        jdbcTemplate.execute("set mode MySQL");
        WorkflowH2SchemaMapperSupport.executeSchema(dataSource,
                WorkflowH2SchemaMapperSupport.MULTI_INSTANCE_ROUND_SCHEMA);
        return WorkflowH2SchemaMapperSupport.createSpringMapper(dataSource,
                "mi-business-round", WfMultiInstanceRoundMapper.class,
                "mapper/flowable/WfMultiInstanceRoundMapper.xml");
    }

    /**
     * 以正式 Mapper 为默认行为创建可注入 CAS 故障的代理。
     *
     * @param delegate WfMultiInstanceRoundMapper，正式 XML 委托
     * @param hook WorkflowMultiInstanceFailureHook，故障注入角色
     * @return WfMultiInstanceRoundMapper，生产 Bean 使用的主 Mapper
     */
    @Bean
    @Primary
    WfMultiInstanceRoundMapper roundMapper(
            @Qualifier("roundMapperDelegate") WfMultiInstanceRoundMapper delegate,
            WorkflowMultiInstanceFailureHook hook)
    {
        WfMultiInstanceRoundMapper mapper = mock(WfMultiInstanceRoundMapper.class,
                delegatesTo(delegate));
        hook.bind(mapper, delegate);
        return mapper;
    }

    /** @param dataSource DataSource，共享 H2；@return WfAttachmentMapper，正式附件 Mapper。 */
    @Bean
    WfAttachmentMapper attachmentMapper(DataSource dataSource)
    {
        WorkflowH2SchemaMapperSupport.executeSchema(dataSource,
                WorkflowH2SchemaMapperSupport.ATTACHMENT_SCHEMA);
        return WorkflowH2SchemaMapperSupport.createSpringMapper(dataSource,
                "mi-business-attachment", WfAttachmentMapper.class,
                "mapper/flowable/WfAttachmentMapper.xml");
    }

    /** @param dataSource DataSource，共享 H2；@return WfTaskSlaMapper，正式 SLA Mapper。 */
    @Bean
    WfTaskSlaMapper taskSlaMapper(DataSource dataSource)
    {
        WorkflowH2SchemaMapperSupport.executeSchema(dataSource,
                WorkflowH2SchemaMapperSupport.TASK_SLA_SCHEMA);
        return WorkflowH2SchemaMapperSupport.createSpringMapper(dataSource,
                "mi-business-sla", WfTaskSlaMapper.class,
                "mapper/flowable/WfTaskSlaMapper.xml");
    }

    /** @return WorkflowMultiInstanceUserMapper，展示用户名边界 mock。 */
    @Bean
    WorkflowMultiInstanceUserMapper multiInstanceUserMapper()
    {
        return mock(WorkflowMultiInstanceUserMapper.class);
    }

    /**
     * 创建正式引擎操作事务边界。
     *
     * @param engine ProcessEngine，真实引擎
     * @param resolver WorkflowIdentityResolver，当前身份
     * @return WorkflowEngineOperations，受 Spring 事务代理的生产对象
     */
    @Bean
    WorkflowEngineOperations engineOperations(ProcessEngine engine,
            WorkflowIdentityResolver resolver)
    {
        return new WorkflowEngineOperations(new WorkflowAuthenticationContext(
                engine.getIdentityService(), new WorkflowIdentityCodec()),
                new WorkflowExceptionTranslator(), resolver);
    }

    /** @return WorkflowTaskCopyService，当前矩阵不关心抄送持久化的边界 mock。 */
    @Bean
    WorkflowTaskCopyService taskCopyService(WorkflowMultiInstanceFailureHook hook)
    {
        WorkflowTaskCopyService service = mock(WorkflowTaskCopyService.class);
        when(service.prepare(any(WorkflowTaskCopyAction.class),
                any(org.flowable.task.api.Task.class),
                any(WorkflowCurrentIdentity.class), anyList()))
                .thenReturn(WorkflowTaskCopyService.CopyPlan.empty());
        hook.bindTaskCopyService(service);
        return service;
    }

    /** @return WorkflowNotificationService，当前矩阵不持久化通知的边界 mock。 */
    @Bean
    WorkflowNotificationService notificationService()
    {
        return mock(WorkflowNotificationService.class);
    }

    /**
     * 创建真实 SLA 运行服务。
     *
     * @return WorkflowTaskSlaRuntimeService，受 Spring 事务代理的生产对象
     */
    @Bean
    WorkflowTaskSlaRuntimeService taskSlaRuntimeService(ProcessEngine engine,
            RepositoryService repositoryService,
            WorkflowEngineOperations engineOperations, WfTaskSlaMapper mapper,
            WorkflowDeploymentArtifactRepository artifactRepository)
    {
        return new WorkflowTaskSlaRuntimeService(repositoryService,
                engine.getManagementService(), engine.getProcessEngineConfiguration(),
                mock(WorkflowBusinessCalendarService.class), engineOperations,
                mapper, artifactRepository, mock(WorkflowNotificationWriter.class));
    }

    /** @return WorkflowAttachmentService，使用正式 Mapper 与私有临时存储。 */
    @Bean
    WorkflowAttachmentService attachmentService(WfAttachmentMapper mapper,
            WorkflowIdentityResolver resolver, Path attachmentRoot)
    {
        WorkflowAttachmentProperties properties = new WorkflowAttachmentProperties();
        properties.setMinFreeBytes(0L);
        return new WorkflowAttachmentService(mapper,
                new WorkflowAttachmentStorage(attachmentRoot, properties.getMaxSize()),
                properties, resolver, mock(WorkflowProcessAccessService.class));
    }

    /** @return Path，当前 Spring 测试上下文独占附件目录。 */
    @Bean
    Path attachmentRoot() throws IOException
    {
        return Files.createTempDirectory("workflow-mi-business-");
    }

    /** @param attachmentRoot Path，私有附件目录；@return DisposableBean，上下文关闭时递归清理。 */
    @Bean
    DisposableBean attachmentRootCleanup(Path attachmentRoot)
    {
        return () -> FileSystemUtils.deleteRecursively(attachmentRoot);
    }

    /** @return WorkflowNextTaskAssignmentService，当前矩阵后继分配边界 mock。 */
    @Bean
    WorkflowNextTaskAssignmentService nextTaskAssignmentService()
    {
        return mock(WorkflowNextTaskAssignmentService.class);
    }

    /** @return WorkflowControlledLoopService，当前矩阵循环边界 mock。 */
    @Bean
    WorkflowControlledLoopService controlledLoopService()
    {
        return mock(WorkflowControlledLoopService.class);
    }

    /** @return WorkflowProcessCancelApplicationService，Lifecycle 非本矩阵入口 mock。 */
    @Bean
    WorkflowProcessCancelApplicationService cancelApplicationService()
    {
        return mock(WorkflowProcessCancelApplicationService.class);
    }

    /** @return WorkflowTaskRevokeApplicationService，Lifecycle 非本矩阵入口 mock。 */
    @Bean
    WorkflowTaskRevokeApplicationService revokeApplicationService()
    {
        return mock(WorkflowTaskRevokeApplicationService.class);
    }

    /** @return WorkflowTaskRejectionApplicationService，Lifecycle 非本矩阵入口 mock。 */
    @Bean
    WorkflowTaskRejectionApplicationService rejectionApplicationService()
    {
        return mock(WorkflowTaskRejectionApplicationService.class);
    }

    /** @return WfCopyMapper，流程终止非本矩阵抄送边界 mock。 */
    @Bean
    WfCopyMapper copyMapper()
    {
        return mock(WfCopyMapper.class);
    }

    /** @return WfControlledLoopExecutionMapper，流程终止非本矩阵循环边界 mock。 */
    @Bean
    WfControlledLoopExecutionMapper controlledLoopExecutionMapper()
    {
        return mock(WfControlledLoopExecutionMapper.class);
    }

    /** @return PermissionService，管理员终止权限边界 mock。 */
    @Bean
    PermissionService permissionService()
    {
        PermissionService service = mock(PermissionService.class);
        when(service.hasPermi("workflow:process:terminate")).thenReturn(true);
        return service;
    }

    /**
     * 绑定生产用户任务监听器并保留审计故障注入点。
     *
     * @return WorkflowUserTaskListener，真实生产监听器
     */
    @Bean
    WorkflowUserTaskListener userTaskListener(LateBindingTaskListener binding,
            WorkflowMultiInstanceFailureHook hook,
            WorkflowTaskSlaRuntimeService slaRuntimeService,
            WorkflowNotificationService notificationService,
            WorkflowMultiInstanceRoundLifecycleService lifecycleService)
    {
        WorkflowUserTaskAuditService audit = mock(WorkflowUserTaskAuditService.class);
        doAnswer(invocation ->
        {
            String event = invocation.getArgument(0);
            if (TaskListener.EVENTNAME_CREATE.equals(event)
                    && hook.consumeCreateAuditFailure())
            {
                throw new org.flowable.common.engine.api.FlowableException(
                        "injected task create audit failure");
            }
            return null;
        }).when(audit).recordAudit(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.nullable(String.class));
        WorkflowUserTaskListener listener = new WorkflowUserTaskListener(audit,
                slaRuntimeService, mock(WorkflowParticipantRuleRuntimeService.class),
                mock(WorkflowAutomaticCopyService.class), notificationService,
                lifecycleService);
        binding.bind(listener);
        return listener;
    }

    /**
     * 注册生产轮次中断监听器。
     *
     * @return Object，保证注册动作在上下文初始化期间执行
     */
    @Bean
    Object interruptionListenerRegistration(ProcessEngine engine,
            WorkflowMultiInstanceRoundTerminationService terminationService)
    {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("workflowMultiInstanceRoundTerminationService",
                terminationService);
        engine.getProcessEngineConfiguration().getEventDispatcher()
                .addEventListener(new WorkflowMultiInstanceRoundInterruptionListener(
                        factory.getBeanProvider(
                                WorkflowMultiInstanceRoundTerminationService.class)));
        return new Object();
    }

    /**
     * 部署真实轮次与组退回 BPMN，并持久化开始表单快照。
     *
     * @return String，部署主键
     */
    @Bean("workflowDeploymentId")
    @DependsOn({ "userTaskListener", "interruptionListenerRegistration" })
    String workflowDeploymentId(RepositoryService repositoryService,
            WorkflowDeploymentArtifactRepository artifactRepository)
    {
        Deployment deployment = repositoryService.createDeployment()
                .addClasspathResource(
                        "bpmn/workflow-multi-instance-round-lifecycle.bpmn20.xml")
                .deploy();
        List<WfDeployForm> forms = List.of(
                startForm(deployment.getId(), "firstAllReturnStart"),
                startForm(deployment.getId(), "firstAnyReturnStart"),
                startForm(deployment.getId(), "laterAllReturnStart"));
        artifactRepository.persist(deployment.getId(),
                new WorkflowDeploymentArtifacts(forms, List.of(), List.of(),
                        List.of(), List.of(), List.of(), List.of(), List.of()));
        return deployment.getId();
    }

    /**
     * 创建开始节点表单快照。
     *
     * @param deploymentId String，部署主键
     * @param startNodeId String，开始节点 ID
     * @return WfDeployForm，正式不可变表单制品
     */
    private WfDeployForm startForm(String deploymentId, String startNodeId)
    {
        WfDeployForm form = new WfDeployForm();
        form.setDeployId(deploymentId);
        form.setSourceType("TEMPLATE");
        form.setFormId(1L);
        form.setFormKey("startForm");
        form.setNodeKey(startNodeId);
        form.setFormName("测试申请表");
        form.setNodeName("开始");
        form.setContent(START_FORM);
        form.setCreateTime(new Date());
        return form;
    }

    /**
     * 创建只持有生产入口的业务驱动器。
     *
     * @return WorkflowMultiInstanceBusinessDriver，业务动作角色
     */
    @Bean
    WorkflowMultiInstanceBusinessDriver businessDriver(ProcessEngine engine,
            RuntimeService runtimeService, TransactionTemplate transactionTemplate,
            ThreadLocal<String> currentUserId,
            @Qualifier("workflowDeploymentId") String deploymentId,
            WorkflowTaskLifecycleService lifecycleService,
            WorkflowMultiInstanceService multiInstanceService,
            WorkflowAttachmentService attachmentService,
            WorkflowProcessInstanceService processInstanceService)
    {
        return new WorkflowMultiInstanceBusinessDriver(engine, runtimeService,
                transactionTemplate, currentUserId,
                deploymentId, lifecycleService, multiInstanceService,
                attachmentService, processInstanceService);
    }

    /**
     * 创建只读状态探针。
     *
     * @return WorkflowMultiInstanceStateProbe，状态读取角色
     */
    @Bean
    WorkflowMultiInstanceStateProbe stateProbe(RuntimeService runtimeService,
            TaskService taskService, HistoryService historyService,
            JdbcTemplate jdbcTemplate,
            WfMultiInstanceRoundMapper roundMapper,
            WfAttachmentMapper attachmentMapper)
    {
        return new WorkflowMultiInstanceStateProbe(runtimeService, taskService,
                historyService, jdbcTemplate, roundMapper, attachmentMapper);
    }

    /** 引擎启动时占位、上下文完成后绑定生产任务监听器。 */
    static final class LateBindingTaskListener implements TaskListener
    {
        private volatile WorkflowUserTaskListener delegate;

        /** @param listener WorkflowUserTaskListener，生产监听器；@return void，无返回值。 */
        void bind(WorkflowUserTaskListener listener)
        {
            if (listener == null || delegate != null)
            {
                throw new IllegalStateException("生产任务监听器绑定不合法");
            }
            delegate = listener;
        }

        /** @param task DelegateTask，Flowable 任务事件；@return void，无返回值。 */
        @Override
        public void notify(DelegateTask task)
        {
            WorkflowUserTaskListener listener = delegate;
            if (listener == null)
            {
                throw new IllegalStateException("生产任务监听器尚未绑定");
            }
            listener.notify(task);
        }
    }
}
