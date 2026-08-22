package com.ruoyi.flowable.service.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.flowable.spring.SpringProcessEngineConfiguration;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.authorization.WorkflowProcessAccessService;
import com.ruoyi.flowable.config.WorkflowAttachmentProperties;
import com.ruoyi.flowable.domain.WfAttachment;
import com.ruoyi.flowable.domain.WfDeployForm;
import com.ruoyi.flowable.domain.WfDeployParticipantRule;
import com.ruoyi.flowable.domain.WfProcessDraft;
import com.ruoyi.flowable.domain.WorkflowAttachmentStatus;
import com.ruoyi.flowable.domain.WorkflowProcessDefinitionLockRow;
import com.ruoyi.flowable.domain.WorkflowProcessDraftStatus;
import com.ruoyi.flowable.domain.dto.StartProcessRequest;
import com.ruoyi.flowable.domain.dto.WorkflowBpmnXmlQueryDto;
import com.ruoyi.flowable.domain.dto.WorkflowProcessDraftCreateRequest;
import com.ruoyi.flowable.domain.dto.WorkflowProcessDraftSubmitRequest;
import com.ruoyi.flowable.domain.dto.WorkflowProcessFormQueryDto;
import com.ruoyi.flowable.domain.vo.WorkflowProcessDraftSubmitView;
import com.ruoyi.flowable.domain.vo.WorkflowProcessDraftView;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.engine.WorkflowExceptionTranslator;
import com.ruoyi.flowable.engine.WorkflowProcessInstanceSnapshot;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowAuthenticationContext;
import com.ruoyi.flowable.identity.WorkflowIdentityCodec;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.identity.WorkflowUserSelectionValidator;
import com.ruoyi.flowable.mapper.WfAttachmentMapper;
import com.ruoyi.flowable.mapper.WfCopyMapper;
import com.ruoyi.flowable.mapper.WfProcessDraftMapper;
import com.ruoyi.flowable.mapper.WorkflowIdentityMapper;
import com.ruoyi.flowable.mapper.WorkflowProcessDefinitionLockMapper;
import com.ruoyi.flowable.runtime.WorkflowParticipantResolutionMetrics;
import com.ruoyi.flowable.service.WorkflowFormTemplateValidator;
import com.ruoyi.flowable.service.attachment.StoredAttachmentFile;
import com.ruoyi.flowable.service.attachment.WorkflowAttachmentService;
import com.ruoyi.flowable.service.attachment.WorkflowAttachmentStorage;
import com.ruoyi.flowable.service.identity.WorkflowParticipantRuleRuntimeService;
import com.ruoyi.flowable.service.model.WorkflowDeploymentArtifactRepository;
import com.ruoyi.flowable.service.model.WorkflowDeploymentArtifacts;
import com.ruoyi.flowable.service.model.WorkflowDeploymentService;
import com.ruoyi.flowable.service.task.WorkflowTaskLifecycleService;
import com.ruoyi.system.service.ISysUserService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 使用同一真实 H2 数据源、Flowable 8、Spring 事务和正式 MyBatis XML 验证流程发起写链。
 */
class WorkflowProcessStartChainIntegrationTest
{
    private static final String USER_ID = "100";
    private static final long OWNER_ID = 100L;
    private static final Map<String, Object> VALID_VARIABLES = Map.of("requester", "Alice");
    private static final String FORM_CONTENT = """
            {"fields":[
              {"__vModel__":"requester","__config__":{"layout":"colFormItem","tag":"el-input","required":true}},
              {"__vModel__":"evidence","limit":3,"__config__":{"layout":"colFormItem","tag":"el-upload"}}
            ]}
            """;

    @TempDir
    Path profileRoot;

    private ProcessEngine processEngine;
    private RepositoryService repositoryService;
    private RuntimeService runtimeService;
    private JdbcTemplate jdbcTemplate;
    private DataSourceTransactionManager transactionManager;
    private WfProcessDraftMapper draftMapper;
    private WfAttachmentMapper attachmentMapper;
    private WorkflowProcessDefinitionLockMapper definitionLockMapper;
    private WorkflowIdentityResolver identityResolver;
    private WorkflowStartVariableValidator variableValidator;
    private WorkflowParticipantRuleRuntimeService participantRuntimeService;
    private SimpleMeterRegistry meterRegistry;
    private WorkflowAttachmentStorage attachmentStorage;
    private WorkflowProcessQueryService queryService;
    private WorkflowProcessStartService startService;
    private WorkflowProcessDraftService draftService;
    private ProcessDefinition definition;
    private AtomicReference<WorkflowCurrentIdentity> currentIdentity;

    /**
     * 创建共享 H2 数据源上的真实 Flowable 8、业务表、正式 Mapper 和事务代理。
     *
     * @return void，每个测试使用独立数据库、附件目录和部署制品
     */
    @BeforeEach
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void setUp()
    {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:start-chain-" + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        dataSource.setUser("sa");
        transactionManager = new DataSourceTransactionManager(dataSource);

        SpringProcessEngineConfiguration configuration =
                new SpringProcessEngineConfiguration();
        configuration.setDataSource(dataSource);
        configuration.setTransactionManager(transactionManager);
        configuration.setDatabaseSchemaUpdate("true");
        configuration.setHistory("full");
        processEngine = configuration.buildProcessEngine();
        repositoryService = spy(processEngine.getRepositoryService());
        runtimeService = spy(processEngine.getRuntimeService());
        HistoryService historyService = processEngine.getHistoryService();
        TaskService taskService = processEngine.getTaskService();

        jdbcTemplate = new JdbcTemplate(dataSource);
        createBusinessTables();
        SqlSessionTemplate mapperTemplate = createMapperTemplate(dataSource);
        draftMapper = spy(mapperTemplate.getMapper(WfProcessDraftMapper.class));
        attachmentMapper = spy(mapperTemplate.getMapper(WfAttachmentMapper.class));

        Deployment deployment = repositoryService.createDeployment()
                .addString("start-chain.bpmn20.xml", BPMN)
                .deploy();
        definition = repositoryService.createProcessDefinitionQuery()
                .deploymentId(deployment.getId()).singleResult();
        WorkflowDeploymentArtifactRepository artifactRepository =
                new WorkflowDeploymentArtifactRepository(repositoryService);
        artifactRepository.persist(deployment.getId(), deploymentArtifacts(deployment.getId()));

        currentIdentity = new AtomicReference<>(new WorkflowCurrentIdentity(USER_ID, Set.of()));
        identityResolver = mock(WorkflowIdentityResolver.class);
        when(identityResolver.resolveCurrentIdentity()).thenAnswer(invocation ->
                currentIdentity.get());
        when(identityResolver.resolveApprovalEligibleUserIds(any()))
                .thenAnswer(invocation -> new java.util.LinkedHashSet<>(
                        (List<String>) invocation.getArgument(0)));
        WorkflowIdentityMapper identityMapper = mock(WorkflowIdentityMapper.class);
        meterRegistry = new SimpleMeterRegistry();
        participantRuntimeService = spy(new WorkflowParticipantRuleRuntimeService(
                repositoryService, runtimeService, artifactRepository, identityMapper,
                identityResolver,
                new WorkflowParticipantResolutionMetrics(meterRegistry)));

        WorkflowAuthenticationContext authenticationContext =
                new WorkflowAuthenticationContext(processEngine.getIdentityService(),
                        new WorkflowIdentityCodec());
        WorkflowEngineOperations operationsTarget = new WorkflowEngineOperations(
                authenticationContext, new WorkflowExceptionTranslator(), identityResolver);
        WorkflowEngineOperations engineOperations = transactionalProxy(
                operationsTarget, transactionManager);

        queryService = new WorkflowProcessQueryService(
                engineOperations, repositoryService, historyService, runtimeService, taskService,
                identityResolver, mock(WorkflowProcessAccessService.class),
                mock(WorkflowDeploymentService.class), artifactRepository,
                mock(WfCopyMapper.class), mock(ISysUserService.class),
                mock(WorkflowTaskLifecycleService.class), participantRuntimeService);
        WorkflowAttachmentProperties attachmentProperties = new WorkflowAttachmentProperties();
        attachmentProperties.setMinFreeBytes(0);
        attachmentStorage = new WorkflowAttachmentStorage(profileRoot,
                attachmentProperties.getMaxSize());
        WorkflowAttachmentService attachmentTarget = new WorkflowAttachmentService(
                attachmentMapper, attachmentStorage, attachmentProperties, identityResolver,
                mock(WorkflowProcessAccessService.class));
        WorkflowAttachmentService attachmentService = transactionalProxy(
                attachmentTarget, transactionManager);
        variableValidator = spy(new WorkflowStartVariableValidator(
                new WorkflowFormTemplateValidator()));
        definitionLockMapper = mock(WorkflowProcessDefinitionLockMapper.class);
        when(definitionLockMapper.selectDeploymentIdForUpdate(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(definitionLockMapper.selectLatestDefaultTenantDefinitionForUpdate(
                definition.getKey())).thenReturn(new WorkflowProcessDefinitionLockRow(
                        definition.getId(), deployment.getId(), 1));

        startService = new WorkflowProcessStartService(engineOperations, repositoryService,
                runtimeService, queryService, variableValidator, attachmentService,
                definitionLockMapper, new WorkflowUserSelectionValidator(identityResolver));
        draftService = new WorkflowProcessDraftService(engineOperations, identityResolver,
                repositoryService, queryService, startService, variableValidator,
                attachmentService, draftMapper, definitionLockMapper);
        clearObservedCalls();
    }

    /**
     * 关闭真实引擎；临时附件目录由 JUnit 自动清理。
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
     * 验证直接发起只解析一次身份、读取一次模型并执行一次真实引擎启动。
     *
     * @return void，实例数、保留变量、快照或调用次数不一致时失败
     */
    @Test
    void startsOneRealInstanceWithReservedVariablesAndSinglePreparation()
    {
        WorkflowProcessInstanceSnapshot snapshot = startService.start(
                new StartProcessRequest(definition.getId(), " DIRECT-1 ", VALID_VARIABLES));

        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(snapshot.id()).count()).isOne();
        assertThat(runtimeService.getVariable(snapshot.id(), "initiator")).isEqualTo(USER_ID);
        assertThat(runtimeService.getVariable(snapshot.id(), "processStatus"))
                .isEqualTo("running");
        assertThat(runtimeService.getVariable(snapshot.id(), "requester")).isEqualTo("Alice");
        assertThat(runtimeService.getVariable(snapshot.id(),
                WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME)).isInstanceOf(String.class);
        assertThat(snapshot.businessKey()).isEqualTo("DIRECT-1");

        verify(identityResolver, times(1)).resolveCurrentIdentity();
        verify(runtimeService, times(1)).startProcessInstanceById(
                eq(definition.getId()), eq("DIRECT-1"), anyMap());
        verify(repositoryService, times(1)).getBpmnModel(definition.getId());
        verify(variableValidator, times(1)).validateForStart(FORM_CONTENT, VALID_VARIABLES);
        verify(definitionLockMapper, times(1))
                .selectDeploymentIdForUpdate(definition.getDeploymentId());
        verify(participantRuntimeService, times(1))
                .assertCanStart(any(WorkflowCurrentIdentity.class),
                        any(ProcessDefinition.class));
    }

    /**
     * 验证草稿提交复用同一规范变量和锁定附件，重复提交不再触碰部署或引擎。
     *
     * @return void，草稿、实例、附件状态或单次调用契约不一致时失败
     */
    @Test
    void submitsDraftOncePersistsSameNormalizedFieldsAndReturnsOriginalOnRetry()
    {
        String attachmentId = insertTemporaryAttachment();
        Map<String, Object> submittedVariables = Map.of(
                "requester", "Alice", "evidence", List.of(attachmentId));
        WorkflowProcessDraftView draft = createDraft(submittedVariables);
        clearObservedCalls();

        WorkflowProcessDraftSubmitView first = draftService.submit(draft.draftId(),
                new WorkflowProcessDraftSubmitRequest(draft.revisionNo(), " DRAFT-1 ",
                        submittedVariables, Map.of()));

        WfProcessDraft submitted = draftMapper.selectOwnedById(draft.draftId(), OWNER_ID);
        Map<String, Object> persisted = readJsonMap(submitted.formValues());
        assertThat(submitted.draftStatus()).isEqualTo(WorkflowProcessDraftStatus.SUBMITTED);
        assertThat(submitted.businessKey()).isEqualTo("DRAFT-1");
        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(first.processInstanceId()).count()).isOne();
        assertThat(runtimeService.getVariables(first.processInstanceId()))
                .containsEntry("requester", persisted.get("requester"));
        WfAttachment bound = attachmentMapper.selectById(attachmentId);
        assertThat(bound.status()).isEqualTo(WorkflowAttachmentStatus.BOUND);
        assertThat(bound.processInstanceId()).isEqualTo(first.processInstanceId());

        verify(identityResolver, times(1)).resolveCurrentIdentity();
        verify(runtimeService, times(1)).startProcessInstanceById(
                eq(definition.getId()), eq("DRAFT-1"), anyMap());
        verify(variableValidator, times(1)).validateForStart(FORM_CONTENT,
                submittedVariables);
        verify(repositoryService, times(1)).getBpmnModel(definition.getId());
        verify(definitionLockMapper, times(1))
                .selectDeploymentIdForUpdate(definition.getDeploymentId());
        verify(draftMapper, times(1)).selectOwnedByIdForUpdate(draft.draftId(), OWNER_ID);
        verify(attachmentMapper, times(1)).selectByDraftIdForUpdate(
                draft.draftId(), OWNER_ID);
        verify(attachmentMapper, never()).selectByIdsForUpdate(any());

        clearObservedCalls();
        WorkflowProcessDraftSubmitView repeated = draftService.submit(draft.draftId(),
                new WorkflowProcessDraftSubmitRequest(draft.revisionNo(), "ignored",
                        Map.of(), Map.of()));
        assertThat(repeated.processInstanceId()).isEqualTo(first.processInstanceId());
        verify(identityResolver, times(1)).resolveCurrentIdentity();
        verify(runtimeService, never()).startProcessInstanceById(
                anyString(), nullable(String.class), anyMap());
        verify(definitionLockMapper, never()).selectDeploymentIdForUpdate(anyString());
        verify(draftMapper, never()).selectOwnedByIdForUpdate(anyString(), any());
        verify(attachmentMapper, never()).selectByDraftIdForUpdate(anyString(), any());
    }

    /**
     * 验证受管部署拒绝和非法变量均在真实引擎命令前失败。
     *
     * @return void，任一拒绝场景产生实例或历史权限兜底时失败
     */
    @Test
    void rejectsUnauthorizedAndInvalidVariablesWithoutCreatingInstances()
    {
        long before = runtimeService.createProcessInstanceQuery().count();
        currentIdentity.set(new WorkflowCurrentIdentity("999", Set.of()));
        assertThatThrownBy(() -> startService.start(new StartProcessRequest(
                definition.getId(), null, VALID_VARIABLES)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(exception.getSubCode())
                            .isEqualTo("PROCESS_START_SCOPE_DENIED");
                });
        assertThat(runtimeService.createProcessInstanceQuery().count()).isEqualTo(before);
        verify(repositoryService, never())
                .getIdentityLinksForProcessDefinition(definition.getId());
        assertThat(meterRegistry.get("workflow.participant.resolution.failures")
                .tag("error_code", "PROCESS_START_SCOPE_DENIED")
                .counter().count()).isEqualTo(1.0d);

        currentIdentity.set(new WorkflowCurrentIdentity(USER_ID, Set.of()));
        clearObservedCalls();
        assertThatThrownBy(() -> startService.start(new StartProcessRequest(
                definition.getId(), null, Map.of("unknown", "value"))))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(runtimeService.createProcessInstanceQuery().count()).isEqualTo(before);
        verify(runtimeService, never()).startProcessInstanceById(
                anyString(), nullable(String.class), anyMap());
    }

    /**
     * 验证只读表单和 BPMN 预览拒绝保持通用 403，且不调用正式授权或累计写入失败指标。
     *
     * @return void，异常消息、subCode、授权方法或失败指标发生漂移时失败
     */
    @Test
    void readOnlyPreviewDenialKeepsGenericContractWithoutWriteFailureMetric()
    {
        currentIdentity.set(new WorkflowCurrentIdentity("999", Set.of()));
        double before = meterRegistry.get("workflow.participant.resolution.failures")
                .tag("error_code", "PROCESS_START_SCOPE_DENIED")
                .counter().count();

        assertThatThrownBy(() -> queryService.getProcessForm(
                new WorkflowProcessFormQueryDto(definition.getId(),
                        definition.getDeploymentId(), null)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(exception.getMessage()).isEqualTo("当前用户无权发起该流程");
                    assertThat(exception.getSubCode()).isNull();
                });
        assertThatThrownBy(() -> queryService.getBpmnXml(
                new WorkflowBpmnXmlQueryDto(definition.getId(), null)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(exception.getMessage()).isEqualTo("当前用户无权发起该流程");
                    assertThat(exception.getSubCode()).isNull();
                });

        verify(participantRuntimeService, times(2)).canStartIfManaged(
                any(WorkflowCurrentIdentity.class), any(ProcessDefinition.class));
        verify(participantRuntimeService, never()).assertCanStart(
                any(WorkflowCurrentIdentity.class), any(ProcessDefinition.class));
        verify(repositoryService, never())
                .getIdentityLinksForProcessDefinition(definition.getId());
        assertThat(meterRegistry.get("workflow.participant.resolution.failures")
                .tag("error_code", "PROCESS_START_SCOPE_DENIED")
                .counter().count()).isEqualTo(before);
    }

    /**
     * 验证草稿创建后新上传的 TEMP 附件可在提交时首次引用并通过单次补充锁定绑定实例。
     *
     * @return void，TEMP 附件未迁移为 BOUND、查询重复或实例未创建时失败
     */
    @Test
    void submitsTemporaryAttachmentFirstReferencedAfterDraftCreation()
    {
        WorkflowProcessDraftView draft = createDraft(VALID_VARIABLES);
        String attachmentId = insertTemporaryAttachment();
        Map<String, Object> submittedVariables = Map.of(
                "requester", "Late upload", "evidence", List.of(attachmentId));
        clearObservedCalls();

        WorkflowProcessDraftSubmitView submitted = draftService.submit(draft.draftId(),
                new WorkflowProcessDraftSubmitRequest(draft.revisionNo(), "LATE-TEMP",
                        submittedVariables, Map.of()));

        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(submitted.processInstanceId()).count()).isOne();
        WfAttachment bound = attachmentMapper.selectById(attachmentId);
        assertThat(bound.status()).isEqualTo(WorkflowAttachmentStatus.BOUND);
        assertThat(bound.processInstanceId()).isEqualTo(submitted.processInstanceId());
        verify(attachmentMapper, times(1)).selectByDraftIdForUpdate(
                draft.draftId(), OWNER_ID);
        verify(attachmentMapper, times(1)).selectByIdsForUpdate(any());
        verify(runtimeService, times(1)).startProcessInstanceById(
                eq(definition.getId()), eq("LATE-TEMP"), anyMap());
    }

    /**
     * 验证草稿引用不存在附件时，真实附件门禁在实例启动前失败且草稿保持 ACTIVE。
     *
     * @return void，附件失败产生实例或改变草稿状态时失败
     */
    @Test
    void attachmentFailureLeavesNoInstanceAndKeepsDraftActive()
    {
        WorkflowProcessDraftView draft = createDraft(VALID_VARIABLES);
        long before = runtimeService.createProcessInstanceQuery().count();
        String missingAttachmentId = UUID.randomUUID().toString();
        Map<String, Object> invalidVariables = Map.of(
                "requester", "Alice", "evidence", List.of(missingAttachmentId));

        assertThatThrownBy(() -> draftService.submit(draft.draftId(),
                new WorkflowProcessDraftSubmitRequest(draft.revisionNo(), null,
                        invalidVariables, Map.of())))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(HttpStatus.NOT_FOUND));

        assertThat(runtimeService.createProcessInstanceQuery().count()).isEqualTo(before);
        assertThat(draftMapper.selectOwnedById(draft.draftId(), OWNER_ID).draftStatus())
                .isEqualTo(WorkflowProcessDraftStatus.ACTIVE);
        verify(runtimeService, never()).startProcessInstanceById(
                anyString(), nullable(String.class), anyMap());
    }

    /**
     * 验证草稿 SUBMITTED CAS 失败会回滚已经创建的 Flowable 实例和附件迁移。
     *
     * @return void，事务失败后留下实例、终态草稿或 BOUND 附件时失败
     */
    @Test
    void rollsBackInstanceAndAttachmentWhenDraftStateUpdateFails()
    {
        String attachmentId = insertTemporaryAttachment();
        Map<String, Object> variables = Map.of(
                "requester", "Rollback", "evidence", List.of(attachmentId));
        WorkflowProcessDraftView draft = createDraft(variables);
        long before = runtimeService.createProcessInstanceQuery().count();
        doReturn(0).when(draftMapper).markSubmitted(eq(draft.draftId()), eq(OWNER_ID),
                eq(draft.revisionNo()), anyString(), anyString(), anyString(),
                nullable(String.class));

        assertThatThrownBy(() -> draftService.submit(draft.draftId(),
                new WorkflowProcessDraftSubmitRequest(draft.revisionNo(), "ROLLBACK",
                        variables, Map.of())))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getSubCode()).isEqualTo("DRAFT_VERSION_CONFLICT");
                });

        assertThat(runtimeService.createProcessInstanceQuery().count()).isEqualTo(before);
        assertThat(draftMapper.selectOwnedById(draft.draftId(), OWNER_ID).draftStatus())
                .isEqualTo(WorkflowProcessDraftStatus.ACTIVE);
        WfAttachment attachment = attachmentMapper.selectById(attachmentId);
        assertThat(attachment.status()).isEqualTo(WorkflowAttachmentStatus.DRAFT);
        assertThat(attachment.processInstanceId()).isNull();
    }

    /**
     * 使用正式草稿创建入口生成 ACTIVE 草稿和 DRAFT 附件。
     *
     * @param variables Map&lt;String,Object&gt;，允许正式校验的草稿字段
     * @return WorkflowProcessDraftView，持久化后的本人活动草稿
     */
    private WorkflowProcessDraftView createDraft(Map<String, Object> variables)
    {
        return draftService.create(new WorkflowProcessDraftCreateRequest(
                definition.getId(), null, variables, Map.of()));
    }

    /**
     * 用真实私有存储和正式附件 Mapper 创建一个可由草稿对账的 TEMP 附件。
     *
     * @return String，新附件 UUID
     */
    private String insertTemporaryAttachment()
    {
        StoredAttachmentFile stored = attachmentStorage.store(new MockMultipartFile(
                "file", "evidence.txt", "text/plain", "evidence".getBytes()));
        String attachmentId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        WfAttachment attachment = new WfAttachment(attachmentId, OWNER_ID, "evidence",
                stored.originalName(), stored.storageKey(), stored.contentType(),
                stored.fileSize(), stored.sha256(), WorkflowAttachmentStatus.TEMP,
                now.plusHours(1), null, null, null, null, null, null, 0,
                null, null, null, null, now, now);
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                assertThat(attachmentMapper.insert(attachment)).isOne());
        return attachmentId;
    }

    /**
     * 创建测试所需的草稿和附件业务表；列名与生产 Mapper 契约一致。
     *
     * @return void，无返回值
     */
    private void createBusinessTables()
    {
        jdbcTemplate.execute("""
                create table wf_process_draft (
                  draft_id varchar(36) primary key,
                  owner_user_id bigint not null,
                  process_definition_id varchar(255) not null,
                  process_definition_key varchar(255) not null,
                  process_definition_version int not null,
                  deployment_id varchar(64) not null,
                  process_name varchar(255) not null,
                  source_type varchar(32) not null,
                  form_id bigint,
                  form_key varchar(255) not null,
                  start_node_key varchar(255) not null,
                  form_name varchar(255) not null,
                  node_name varchar(255) not null,
                  snapshot_create_time timestamp(3) not null,
                  form_snapshot clob not null,
                  form_snapshot_sha256 varchar(64) not null,
                  start_multi_instance_assignments clob not null,
                  form_values clob not null,
                  multi_instance_user_ids clob not null,
                  business_key varchar(255),
                  draft_status varchar(16) not null,
                  revision_no bigint not null,
                  submitted_process_instance_id varchar(64),
                  submitted_time timestamp(3),
                  deleted_time timestamp(3),
                  create_time timestamp(3) not null,
                  update_time timestamp(3) not null
                )
                """);
        jdbcTemplate.execute("""
                create table wf_attachment (
                  attachment_id varchar(36) primary key,
                  owner_user_id bigint not null,
                  field_name varchar(128) not null,
                  original_name varchar(255) not null,
                  storage_key varchar(512) not null,
                  content_type varchar(255) not null,
                  file_size bigint not null,
                  sha256 varchar(64) not null,
                  attachment_status varchar(16) not null,
                  expire_time timestamp(3) not null,
                  draft_id varchar(36),
                  process_instance_id varchar(64),
                  task_id varchar(64),
                  node_key varchar(255),
                  bound_time timestamp(3),
                  storage_deleted_time timestamp(3),
                  cleanup_retry_count int not null,
                  cleanup_next_retry_time timestamp(3),
                  cleanup_last_error_code varchar(128),
                  cleanup_claim_token varchar(36),
                  cleanup_lease_until timestamp(3),
                  create_time timestamp(3) not null,
                  update_time timestamp(3) not null
                )
                """);
    }

    /**
     * 从生产 XML 创建可参与 Spring 事务的正式草稿与附件 Mapper。
     *
     * @param dataSource DataSource，与真实 Flowable 8 共用的数据源
     * @return SqlSessionTemplate，使用 SpringManagedTransactionFactory 的 Mapper 会话
     */
    private SqlSessionTemplate createMapperTemplate(DataSource dataSource)
    {
        Environment environment = new Environment("start-chain-it",
                new SpringManagedTransactionFactory(), dataSource);
        org.apache.ibatis.session.Configuration configuration =
                new org.apache.ibatis.session.Configuration(environment);
        addMapper(configuration, WfProcessDraftMapper.class,
                "mapper/flowable/WfProcessDraftMapper.xml");
        addMapper(configuration, WfAttachmentMapper.class,
                "mapper/flowable/WfAttachmentMapper.xml");
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        return new SqlSessionTemplate(factory);
    }

    /**
     * 注册一个 Mapper 接口并完整解析对应生产 XML。
     *
     * @param configuration Configuration，当前测试 MyBatis 配置
     * @param mapperType Class&lt;T&gt;，正式 Mapper 接口类型
     * @param resource String，classpath 下生产 Mapper XML
     * @return void，资源缺失或解析失败时终止测试装配
     */
    private <T> void addMapper(org.apache.ibatis.session.Configuration configuration,
            Class<T> mapperType, String resource)
    {
        configuration.addMapper(mapperType);
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
    }

    /**
     * 为生产对象应用基于 @Transactional 的真实 Spring 事务代理。
     *
     * @param target T，需要代理的生产服务
     * @param manager DataSourceTransactionManager，共享 H2 事务管理器
     * @return T，保留原生产类型的 CGLIB 事务代理
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
     * 创建开始表单和 USERS 发起范围的真实业务资源子部署内容。
     *
     * @param deploymentId String，真实父部署主键
     * @return WorkflowDeploymentArtifacts，可由生产仓库持久化的完整资源集合
     */
    private WorkflowDeploymentArtifacts deploymentArtifacts(String deploymentId)
    {
        WfDeployForm form = new WfDeployForm();
        form.setDeployId(deploymentId);
        form.setSourceType("TEMPLATE");
        form.setFormId(1L);
        form.setFormKey("startForm");
        form.setNodeKey("start");
        form.setFormName("发起表单");
        form.setNodeName("开始");
        form.setContent(FORM_CONTENT);
        form.setCreateTime(new Date());

        WfDeployParticipantRule rule = new WfDeployParticipantRule();
        rule.setProcessKey("startChain");
        rule.setRuleScope("START");
        rule.setAssignmentMode("START");
        rule.setRuleType("USERS");
        rule.setTargetIds(USER_ID);
        rule.setNoMatchPolicy("FAIL");
        rule.setRuleVersion(1);
        rule.setChecksum("start-chain-users-100");
        rule.setCreateBy("1");
        return new WorkflowDeploymentArtifacts(List.of(form), List.of(), List.of(),
                List.of(rule), List.of(), List.of(), List.of(), List.of());
    }

    /**
     * 清空夹具阶段调用，只保留每个正式命令自己的调用次数证据。
     *
     * @return void，无返回值
     */
    private void clearObservedCalls()
    {
        clearInvocations(repositoryService, runtimeService, identityResolver,
                variableValidator, participantRuntimeService, definitionLockMapper,
                draftMapper, attachmentMapper);
    }

    /**
     * 将草稿正式 JSON 字段解析为受限 Map，用于与真实 Flowable 变量比较。
     *
     * @param json String，wf_process_draft.form_values 正文
     * @return Map&lt;String,Object&gt;，反序列化后的字段映射
     */
    private Map<String, Object> readJsonMap(String json)
    {
        return JsonMapper.shared().readValue(json,
                new TypeReference<Map<String, Object>>() { });
    }

    /** 真实定义停留在用户任务，便于断言活动实例和服务端保留变量。 */
    private static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
              xmlns:flowable="http://flowable.org/bpmn" targetNamespace="start-chain-it">
              <process id="startChain" name="Start Chain" isExecutable="true">
                <startEvent id="start" flowable:formKey="startForm"/>
                <userTask id="hold" name="Hold" flowable:assignee="100"/>
                <sequenceFlow sourceRef="start" targetRef="hold"/>
              </process>
            </definitions>
            """;
}
