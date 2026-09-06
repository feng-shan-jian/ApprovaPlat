package com.ruoyi.flowable.service.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.authorization.WorkflowProcessAccessService;
import com.ruoyi.flowable.config.WorkflowAttachmentProperties;
import com.ruoyi.flowable.domain.WfAttachment;
import com.ruoyi.flowable.domain.WfDeployForm;
import com.ruoyi.flowable.domain.WfDeployParticipantRule;
import com.ruoyi.flowable.domain.WfProcessDraft;
import com.ruoyi.flowable.domain.WorkflowAttachmentQuotaUsage;
import com.ruoyi.flowable.domain.WorkflowAttachmentStatus;
import com.ruoyi.flowable.domain.WorkflowProcessDefinitionLockRow;
import com.ruoyi.flowable.domain.WorkflowProcessDraftStatus;
import com.ruoyi.flowable.domain.dto.WorkflowBpmnXmlQueryDto;
import com.ruoyi.flowable.domain.dto.WorkflowProcessDraftCreateRequest;
import com.ruoyi.flowable.domain.dto.WorkflowProcessDraftSaveRequest;
import com.ruoyi.flowable.domain.dto.WorkflowProcessDraftSubmitRequest;
import com.ruoyi.flowable.domain.dto.WorkflowProcessFormQueryDto;
import com.ruoyi.flowable.domain.vo.WorkflowProcessDraftSubmitView;
import com.ruoyi.flowable.domain.vo.WorkflowProcessDraftView;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.engine.WorkflowExceptionTranslator;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowAuthenticationContext;
import com.ruoyi.flowable.identity.WorkflowIdentityCodec;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.identity.WorkflowUserSelectionValidator;
import com.ruoyi.flowable.mapper.WfAttachmentMapper;
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
import com.ruoyi.flowable.testsupport.WorkflowFlowableEngineTestSupport;
import com.ruoyi.flowable.testsupport.WorkflowH2SchemaMapperSupport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 使用同一真实 H2 数据源、Flowable 8、Spring 事务和正式 MyBatis XML 验证草稿提交发起写链。
 */
class WorkflowProcessStartChainIntegrationTest
{
    /**
     * 观察真实模板和变量序列化器，验证各入口只解析一次并保留模板 400、快照 500。
     * @return void，重复读取相同 JSON 或错误分类、规范值、大小门禁漂移时测试失败
     */
    @Test
    void reusesValidatedSnapshotAndPreservesEntryErrorSemantics()
    {
        WorkflowFormTemplateValidator template = spy(new WorkflowFormTemplateValidator());
        ObjectMapper parser = spy((ObjectMapper) ReflectionTestUtils.getField(template, "objectMapper"));
        ReflectionTestUtils.setField(template, "objectMapper", parser);
        WorkflowStartVariableValidator validator = new WorkflowStartVariableValidator(template);
        ObjectMapper serializer = spy((ObjectMapper) ReflectionTestUtils.getField(validator, "objectMapper"));
        ReflectionTestUtils.setField(validator, "objectMapper", serializer);
        List<Runnable> entries = List.of(
                () -> assertThat(validator.validateForStart(FORM_CONTENT, VALID_VARIABLES).variables()).isEqualTo(VALID_VARIABLES),
                () -> assertThat(validator.validateForDraft(FORM_CONTENT, VALID_VARIABLES).variables()).isEqualTo(VALID_VARIABLES),
                () -> assertThat(validator.validatePatch(FORM_CONTENT, VALID_VARIABLES, Map.of()).variables()).isEqualTo(VALID_VARIABLES),
                () -> assertThat(validator.normalizeCallActivityStartValues(FORM_CONTENT,
                        Map.of("requester", "Alice", "initiator", "100"))).isEqualTo(VALID_VARIABLES));
        for (Runnable entry : entries)
        {
            clearInvocations(template, parser, serializer);
            entry.run();
            verify(template).validate(FORM_CONTENT);
            verify(parser).readTree(FORM_CONTENT);
            verify(serializer, never()).readTree(anyString());
            verify(serializer).writeValueAsBytes(VALID_VARIABLES);
        }
        List<ThrowingCallable> trustedEntries = List.of(
                () -> validator.validateForStart("{", Map.of()),
                () -> validator.validateForDraft("{", Map.of()),
                () -> validator.validatePatch("{", Map.of(), Map.of()),
                () -> validator.describeControlledLoopFields("{"));
        for (ThrowingCallable entry : trustedEntries)
        {
            assertThatThrownBy(entry).isInstanceOfSatisfying(ServiceException.class, exception ->
            {
                assertThat(exception.getCode()).isEqualTo(500);
                assertThat(exception.getMessage()).isEqualTo("流程部署表单快照结构异常");
                assertThat(exception.getCause()).isInstanceOf(ServiceException.class);
            });
        }
        for (ThrowingCallable entry : List.<ThrowingCallable>of(
                () -> validator.readableFieldNames("{"),
                () -> validator.normalizeCallActivityStartValues("{", Map.of())))
        {
            assertThatThrownBy(entry).isInstanceOfSatisfying(ServiceException.class, exception ->
            {
                assertThat(exception.getCode()).isEqualTo(400);
                assertThat(exception.getMessage()).isEqualTo("表单内容必须是合法且无重复键的 JSON 对象");
            });
        }
    }

    private static final String USER_ID = "100";
    private static final long OWNER_ID = 100L;
    private static final Map<String, Object> VALID_VARIABLES = Map.of("requester", "Alice");
    private static final String FORM_CONTENT = """
            {"fields":[
              {"vModel":"requester","layout":"colFormItem","tag":"el-input","required":true,"workflowHidden":false,"workflowReadable":true,"workflowWritable":true},
              {"vModel":"evidence","layout":"colFormItem","tag":"el-upload","limit":3,"workflowHidden":false,"workflowReadable":true,"workflowWritable":true}
            ]}
            """;

    @TempDir
    Path profileRoot;

    private ProcessEngine processEngine;
    /** 当前用例独占且在 teardown 显式关闭的引擎基础设施。 */
    private WorkflowFlowableEngineTestSupport engineInfrastructure;
    private RepositoryService repositoryService;
    private RuntimeService runtimeService;
    private JdbcTemplate jdbcTemplate;
    private WfProcessDraftMapper draftMapper;
    private WfAttachmentMapper attachmentMapper;
    private WorkflowProcessDefinitionLockMapper definitionLockMapper;
    private WorkflowIdentityResolver identityResolver;
    private WorkflowStartVariableValidator variableValidator;
    private WorkflowParticipantRuleRuntimeService participantRuntimeService;
    private SimpleMeterRegistry meterRegistry;
    private WorkflowAttachmentStorage attachmentStorage;
    /** 当前用例使用的正式附件配置，可由配额负例在独立夹具内收紧。 */
    private WorkflowAttachmentProperties attachmentProperties;
    /** 加入 Spring 事务代理的真实附件领域服务。 */
    private WorkflowAttachmentService attachmentService;
    /** 加入 Spring 事务代理的统一 Flowable 写边界。 */
    private WorkflowEngineOperations engineOperations;
    private WorkflowProcessDefinitionQueryService queryService;
    private WorkflowProcessStartService startService;
    private WorkflowProcessDraftService draftService;
    /** 当前真实部署制品仓库，用于为测试内新增定义写入不可变表单和发起规则。 */
    private WorkflowDeploymentArtifactRepository artifactRepository;
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
        engineInfrastructure = WorkflowFlowableEngineTestSupport.start(
                "start-chain", Map.of());
        DataSource dataSource = engineInfrastructure.dataSource();
        processEngine = engineInfrastructure.processEngine();
        repositoryService = spy(processEngine.getRepositoryService());
        runtimeService = spy(processEngine.getRuntimeService());

        jdbcTemplate = engineInfrastructure.jdbcTemplate();
        WorkflowH2SchemaMapperSupport.executeSchema(dataSource,
                WorkflowH2SchemaMapperSupport.PROCESS_DRAFT_SCHEMA);
        WorkflowH2SchemaMapperSupport.executeSchema(dataSource,
                WorkflowH2SchemaMapperSupport.ATTACHMENT_SCHEMA);
        draftMapper = spy(WorkflowH2SchemaMapperSupport.createSpringMapper(dataSource,
                "start-chain-draft-it", WfProcessDraftMapper.class,
                "mapper/flowable/WfProcessDraftMapper.xml"));
        attachmentMapper = spy(WorkflowH2SchemaMapperSupport.createSpringMapper(dataSource,
                "start-chain-attachment-it", WfAttachmentMapper.class,
                "mapper/flowable/WfAttachmentMapper.xml"));

        Deployment deployment = repositoryService.createDeployment()
                .addString("start-chain.bpmn20.xml", BPMN)
                .deploy();
        definition = repositoryService.createProcessDefinitionQuery()
                .deploymentId(deployment.getId()).singleResult();
        artifactRepository = new WorkflowDeploymentArtifactRepository(repositoryService);
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
        engineOperations =
                engineInfrastructure.transactionalProxy(operationsTarget);

        queryService = new WorkflowProcessDefinitionQueryService(
                engineOperations, repositoryService, identityResolver,
                mock(WorkflowProcessAccessService.class),
                mock(WorkflowDeploymentService.class), artifactRepository,
                participantRuntimeService);
        attachmentProperties = new WorkflowAttachmentProperties();
        attachmentProperties.setMinFreeBytes(0);
        attachmentStorage = new WorkflowAttachmentStorage(profileRoot,
                attachmentProperties.getMaxSize());
        WorkflowAttachmentService attachmentTarget = new WorkflowAttachmentService(
                attachmentMapper, attachmentStorage, attachmentProperties, identityResolver,
                mock(WorkflowProcessAccessService.class));
        attachmentService =
                engineInfrastructure.transactionalProxy(attachmentTarget);
        variableValidator = spy(new WorkflowStartVariableValidator(
                new WorkflowFormTemplateValidator()));
        definitionLockMapper = mock(WorkflowProcessDefinitionLockMapper.class);
        when(definitionLockMapper.selectDeploymentIdForUpdate(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(definitionLockMapper.selectLatestDefaultTenantDefinitionForUpdate(
                definition.getKey())).thenReturn(new WorkflowProcessDefinitionLockRow(
                        definition.getId(), deployment.getId(), 1));

        startService = new WorkflowProcessStartService(repositoryService, runtimeService,
                queryService, variableValidator, attachmentService,
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
        if (engineInfrastructure != null)
        {
            engineInfrastructure.close();
        }
        processEngine = null;
        engineInfrastructure = null;
    }

    /**
     * 验证草稿真实提交持久化规范变量，创建唯一实例并绑定附件；重复提交返回原实例。
     *
     * @return void，数据库、Flowable 变量、提交快照、唯一实例或附件状态不一致时失败
     */
    @Test
    void submitsDraftOncePersistsSameNormalizedFieldsAndReturnsOriginalOnRetry()
    {
        String attachmentId = insertTemporaryAttachment();
        Map<String, Object> submittedVariables = Map.of(
                "requester", "Alice", "evidence", List.of(attachmentId));
        WorkflowProcessDraftView draft = createDraft(submittedVariables);
        long instanceCountBefore = runtimeService.createProcessInstanceQuery().count();

        WorkflowProcessDraftSubmitView first = draftService.submit(draft.draftId(),
                new WorkflowProcessDraftSubmitRequest(draft.revisionNo(), " DRAFT-1 ",
                        submittedVariables, Map.of()));

        WfProcessDraft submitted = draftMapper.selectOwnedById(draft.draftId(), OWNER_ID);
        Map<String, Object> persisted = readJsonMap(submitted.formValues());
        assertThat(submitted.draftStatus()).isEqualTo(WorkflowProcessDraftStatus.SUBMITTED);
        assertThat(submitted.businessKey()).isEqualTo("DRAFT-1");
        assertThat(submitted.submittedProcessInstanceId()).isEqualTo(first.processInstanceId());
        assertThat(submitted.submittedTime()).isNotNull();
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from wf_process_draft
                where draft_id = ? and draft_status = 'SUBMITTED'
                  and submitted_process_instance_id = ?
                """, Long.class, draft.draftId(), first.processInstanceId())).isOne();
        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(first.processInstanceId()).count()).isOne();
        assertThat(runtimeService.createProcessInstanceQuery().count())
                .isEqualTo(instanceCountBefore + 1);
        Map<String, Object> engineVariables = runtimeService.getVariables(
                first.processInstanceId());
        assertThat(engineVariables).containsEntry("initiator", USER_ID)
                .containsEntry("processStatus", "running")
                .containsEntry("requester", persisted.get("requester"));
        WorkflowFormSubmissionSnapshotCodec.SubmissionSnapshot submissionSnapshot =
                WorkflowFormSubmissionSnapshotCodec.decode((String) engineVariables.get(
                        WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME));
        assertThat(submissionSnapshot.kind())
                .isEqualTo(WorkflowFormSubmissionSnapshotCodec.SnapshotKind.START);
        assertThat(submissionSnapshot.deploymentId()).isEqualTo(definition.getDeploymentId());
        assertThat(submissionSnapshot.formKey()).isEqualTo("startForm");
        assertThat(submissionSnapshot.nodeKey()).isEqualTo("start");
        assertThat(submissionSnapshot.values().get("requester").textValue()).isEqualTo("Alice");
        assertThat(submissionSnapshot.values().get("evidence").get(0)
                .get("attachmentId").textValue()).isEqualTo(attachmentId);
        WfAttachment bound = attachmentMapper.selectById(attachmentId);
        assertThat(bound.status()).isEqualTo(WorkflowAttachmentStatus.BOUND);
        assertThat(bound.processInstanceId()).isEqualTo(first.processInstanceId());
        assertThat(bound.draftId()).isNull();
        assertThat(bound.taskId()).isNull();
        assertThat(bound.nodeKey()).isEqualTo("start");
        assertThat(bound.boundTime()).isNotNull();

        WorkflowProcessDraftSubmitView repeated = draftService.submit(draft.draftId(),
                new WorkflowProcessDraftSubmitRequest(draft.revisionNo(), "ignored",
                        Map.of(), Map.of()));
        assertThat(repeated.processInstanceId()).isEqualTo(first.processInstanceId());
        assertThat(runtimeService.createProcessInstanceQuery().count())
                .isEqualTo(instanceCountBefore + 1);
    }

    /**
     * 验证非法正式变量通过真实草稿提交链被拒绝且不创建实例。
     *
     * @return void，非法变量产生实例或改变草稿正式状态时失败
     */
    @Test
    void rejectsInvalidDraftVariablesWithoutCreatingInstance()
    {
        WorkflowProcessDraftView draft = createDraft(VALID_VARIABLES);
        long before = runtimeService.createProcessInstanceQuery().count();
        assertThatThrownBy(() -> draftService.submit(draft.draftId(),
                new WorkflowProcessDraftSubmitRequest(draft.revisionNo(), null,
                        Map.of("unknown", "value"), Map.of())))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(runtimeService.createProcessInstanceQuery().count()).isEqualTo(before);
        WfProcessDraft unchanged = draftMapper.selectOwnedById(draft.draftId(), OWNER_ID);
        assertThat(unchanged.draftStatus()).isEqualTo(WorkflowProcessDraftStatus.ACTIVE);
        assertThat(unchanged.submittedProcessInstanceId()).isNull();
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

        WorkflowProcessDraftSubmitView submitted = draftService.submit(draft.draftId(),
                new WorkflowProcessDraftSubmitRequest(draft.revisionNo(), "LATE-TEMP",
                        submittedVariables, Map.of()));

        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(submitted.processInstanceId()).count()).isOne();
        WfAttachment bound = attachmentMapper.selectById(attachmentId);
        assertThat(bound.status()).isEqualTo(WorkflowAttachmentStatus.BOUND);
        assertThat(bound.processInstanceId()).isEqualTo(submitted.processInstanceId());
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
     * 验证 start 到 end 的同步流程在引擎执行前已原子写入 businessStatus。
     *
     * @return void，瞬时流程启动失败、残留活动实例或历史状态不是 running 时失败
     */
    @Test
    void startsSynchronousEndProcessWithBusinessStatusAlreadyPersisted()
    {
        Deployment deployment = repositoryService.createDeployment()
                .addString("instant-start-chain.bpmn20.xml", INSTANT_BPMN)
                .deploy();
        definition = repositoryService.createProcessDefinitionQuery()
                .deploymentId(deployment.getId()).singleResult();
        artifactRepository.persist(deployment.getId(), deploymentArtifacts(deployment.getId()));
        when(definitionLockMapper.selectLatestDefaultTenantDefinitionForUpdate(
                definition.getKey())).thenReturn(new WorkflowProcessDefinitionLockRow(
                        definition.getId(), deployment.getId(), 1));
        WorkflowProcessDraftView draft = createDraft(VALID_VARIABLES);

        WorkflowProcessDraftSubmitView submitted = draftService.submit(draft.draftId(),
                new WorkflowProcessDraftSubmitRequest(draft.revisionNo(), "INSTANT-1",
                        VALID_VARIABLES, Map.of()));

        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(submitted.processInstanceId()).count()).isZero();
        assertThat(processEngine.getHistoryService().createHistoricProcessInstanceQuery()
                .processInstanceId(submitted.processInstanceId()).singleResult()
                .getBusinessStatus()).isEqualTo(WorkflowProcessStartService.RUNNING_STATUS);
    }

    /**
     * 验证保存、删除和提交使用旧 revision 时只返回既有草稿版本冲突子码。
     *
     * @return void，任一旧 revision 被接受、错误子码漂移或提交产生实例时失败
     */
    @Test
    void rejectsStaleRevisionForSaveDeleteAndSubmitWithDraftVersionConflict()
    {
        WorkflowProcessDraftView saveDraft = createDraft(VALID_VARIABLES);
        WorkflowProcessDraftView saved = draftService.save(saveDraft.draftId(),
                new WorkflowProcessDraftSaveRequest(saveDraft.revisionNo(), "SAVE-NEW",
                        VALID_VARIABLES, Map.of()));
        assertThat(saved.revisionNo()).isEqualTo(saveDraft.revisionNo() + 1);
        assertServiceError(() -> draftService.save(saveDraft.draftId(),
                new WorkflowProcessDraftSaveRequest(saveDraft.revisionNo(), "SAVE-OLD",
                        VALID_VARIABLES, Map.of())), HttpStatus.CONFLICT,
                "DRAFT_VERSION_CONFLICT");

        WorkflowProcessDraftView deleteDraft = createDraft(VALID_VARIABLES);
        draftService.save(deleteDraft.draftId(), new WorkflowProcessDraftSaveRequest(
                deleteDraft.revisionNo(), null, VALID_VARIABLES, Map.of()));
        assertServiceError(() -> draftService.delete(deleteDraft.draftId(),
                deleteDraft.revisionNo()), HttpStatus.CONFLICT,
                "DRAFT_VERSION_CONFLICT");

        WorkflowProcessDraftView submitDraft = createDraft(VALID_VARIABLES);
        draftService.save(submitDraft.draftId(), new WorkflowProcessDraftSaveRequest(
                submitDraft.revisionNo(), null, VALID_VARIABLES, Map.of()));
        long instanceCountBefore = runtimeService.createProcessInstanceQuery().count();
        assertServiceError(() -> draftService.submit(submitDraft.draftId(),
                new WorkflowProcessDraftSubmitRequest(submitDraft.revisionNo(), null,
                        VALID_VARIABLES, Map.of())), HttpStatus.CONFLICT,
                "DRAFT_VERSION_CONFLICT");
        assertThat(runtimeService.createProcessInstanceQuery().count())
                .isEqualTo(instanceCountBefore);
    }

    /**
     * 验证新建草稿回调内的真实可重试并发异常不会误用已有草稿版本冲突子码。
     *
     * @return void，仅 DRAFT_CREATE_CONCURRENCY_CONFLICT 被接受且数据库不产生草稿
     */
    @Test
    void classifiesCreateConcurrencyFailureSeparatelyFromExistingDraftVersionConflict()
    {
        long draftCountBefore = jdbcTemplate.queryForObject(
                "select count(*) from wf_process_draft", Long.class);
        doThrow(new CannotAcquireLockException("test create lock conflict"))
                .when(draftMapper).insert(any(WfProcessDraft.class));

        assertServiceError(() -> createDraft(VALID_VARIABLES), HttpStatus.CONFLICT,
                "DRAFT_CREATE_CONCURRENCY_CONFLICT");

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from wf_process_draft", Long.class))
                .isEqualTo(draftCountBefore);
    }

    /**
     * 验证事务回调成功后、代理提交阶段才暴露的可重试并发异常仍由创建入口稳定分类。
     *
     * @return void，提交阶段异常逃逸、子码错误或事务没有回滚草稿行时失败
     */
    @Test
    void classifiesCreateConcurrencyFailureRaisedDuringTransactionCommit()
    {
        WorkflowAttachmentService commitFailingAttachmentService =
                mock(WorkflowAttachmentService.class);
        doAnswer(invocation ->
        {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization()
                    {
                        /**
                         * 在 Spring 正式提交回调中模拟数据库并发失败，验证代理外捕获边界。
                         *
                         * @param readOnly boolean，当前事务是否只读
                         * @return void，总是抛出可重试并发异常
                         */
                        @Override
                        public void beforeCommit(boolean readOnly)
                        {
                            throw new CannotAcquireLockException(
                                    "test commit-phase concurrency conflict");
                        }
                    });
            return null;
        }).when(commitFailingAttachmentService).reconcileDraftAttachments(
                eq(USER_ID), anyString(), any());
        WorkflowProcessDraftService commitFailingDraftService =
                new WorkflowProcessDraftService(engineOperations, identityResolver,
                        repositoryService, queryService, startService, variableValidator,
                        commitFailingAttachmentService, draftMapper, definitionLockMapper);
        long draftCountBefore = jdbcTemplate.queryForObject(
                "select count(*) from wf_process_draft", Long.class);

        assertServiceError(() -> commitFailingDraftService.create(
                new WorkflowProcessDraftCreateRequest(definition.getId(), null,
                        VALID_VARIABLES, Map.of())), HttpStatus.CONFLICT,
                "DRAFT_CREATE_CONCURRENCY_CONFLICT");

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from wf_process_draft", Long.class))
                .isEqualTo(draftCountBefore);
    }

    /**
     * 验证非最新版定义在查询源头赋予通用子码，并由草稿能力投影稳定关闭编辑与提交。
     *
     * @return void，查询子码、能力开关或展示原因依赖异常文案时失败
     */
    @Test
    void projectsDefinitionVersionExpiryFromStatusAndStableSubCode()
    {
        WorkflowProcessDraftView draft = createDraft(VALID_VARIABLES);
        deployNextVersion();

        assertServiceError(() -> queryService.getProcessForm(
                new WorkflowProcessFormQueryDto(definition.getId(),
                        definition.getDeploymentId(), null)), HttpStatus.CONFLICT,
                "PROCESS_DEFINITION_VERSION_EXPIRED");
        WorkflowProcessDraftView projected = draftService.get(draft.draftId());
        assertThat(projected.editable()).isFalse();
        assertThat(projected.submittable()).isFalse();
        assertThat(projected.statusReason()).isEqualTo("流程定义版本已过期");
    }

    /**
     * 验证挂起定义在查询源头返回不可用子码，草稿能力投影只消费状态码和子码。
     *
     * @return void，挂起定义仍可编辑提交或稳定子码缺失时失败
     */
    @Test
    void projectsSuspendedDefinitionAsUnavailableWithoutMessageClassification()
    {
        WorkflowProcessDraftView draft = createDraft(VALID_VARIABLES);
        repositoryService.suspendProcessDefinitionById(definition.getId());

        assertServiceError(() -> queryService.getProcessForm(
                new WorkflowProcessFormQueryDto(definition.getId(),
                        definition.getDeploymentId(), null)), HttpStatus.CONFLICT,
                "PROCESS_DEFINITION_UNAVAILABLE");
        WorkflowProcessDraftView projected = draftService.get(draft.draftId());
        assertThat(projected.editable()).isFalse();
        assertThat(projected.submittable()).isFalse();
        assertThat(projected.statusReason()).isEqualTo("流程定义当前不可用");
    }

    /**
     * 验证删除定义在查询源头返回 404 加不可用子码，并投影为已删除展示原因。
     *
     * @return void，删除后查询或草稿能力投影的状态、子码和原因不一致时失败
     */
    @Test
    void projectsDeletedDefinitionAsUnavailableWithNotFoundStatus()
    {
        WorkflowProcessDraftView draft = createDraft(VALID_VARIABLES);
        repositoryService.deleteDeployment(definition.getDeploymentId(), true);

        assertServiceError(() -> queryService.getProcessForm(
                new WorkflowProcessFormQueryDto(definition.getId(),
                        definition.getDeploymentId(), null)), HttpStatus.NOT_FOUND,
                "PROCESS_DEFINITION_UNAVAILABLE");
        WorkflowProcessDraftView projected = draftService.get(draft.draftId());
        assertThat(projected.editable()).isFalse();
        assertThat(projected.submittable()).isFalse();
        assertThat(projected.statusReason()).isEqualTo("流程定义已删除");
    }

    /**
     * 验证创建草稿读取到已删除定义时由草稿定义源头返回稳定不可用子码。
     *
     * @return void，Flowable 不存在异常逃逸为未知错误或错误携带创建并发子码时失败
     */
    @Test
    void rejectsDraftCreateAgainstDeletedDefinitionWithStableSubCode()
    {
        repositoryService.deleteDeployment(definition.getDeploymentId(), true);

        assertServiceError(() -> createDraft(VALID_VARIABLES), HttpStatus.NOT_FOUND,
                "DRAFT_DEFINITION_UNAVAILABLE");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from wf_process_draft", Long.class)).isZero();
    }

    /**
     * 验证表单查询声明的部署主键漂移在定义关系源头返回专用稳定子码。
     *
     * @return void，部署关系冲突仍是无子码普通 409 时失败
     */
    @Test
    void rejectsDefinitionDeploymentRelationDriftWithStableSubCode()
    {
        assertServiceError(() -> queryService.getProcessForm(
                new WorkflowProcessFormQueryDto(definition.getId(),
                        "drifted-deployment", null)), HttpStatus.CONFLICT,
                "PROCESS_DEFINITION_RELATION_CONFLICT");
    }

    /**
     * 验证草稿提交遇到真实最新版变化时返回草稿定义版本过期且不迁移附件或创建实例。
     *
     * @return void，错误子码、实例数量、草稿状态或附件状态发生漂移时失败
     */
    @Test
    void rejectsDraftSubmitAgainstNewerDefinitionWithoutSideEffects()
    {
        String attachmentId = insertTemporaryAttachment();
        Map<String, Object> variables = variablesWithAttachment("Version expired", attachmentId);
        WorkflowProcessDraftView draft = createDraft(variables);
        ProcessDefinition latest = deployNextVersion();
        when(definitionLockMapper.selectLatestDefaultTenantDefinitionForUpdate(
                definition.getKey())).thenReturn(new WorkflowProcessDefinitionLockRow(
                        latest.getId(), latest.getDeploymentId(), 1));
        long instanceCountBefore = runtimeService.createProcessInstanceQuery().count();

        assertServiceError(() -> submitDraft(draft, variables), HttpStatus.CONFLICT,
                "DRAFT_DEFINITION_VERSION_EXPIRED");

        assertDraftSubmitFailureHasNoSideEffects(draft.draftId(), attachmentId,
                instanceCountBefore);
    }

    /**
     * 验证草稿提交遇到挂起定义时返回草稿定义不可用且不迁移附件或创建实例。
     *
     * @return void，挂起错误分类或提交事务副作用没有完整回滚时失败
     */
    @Test
    void rejectsDraftSubmitAgainstSuspendedDefinitionWithoutSideEffects()
    {
        String attachmentId = insertTemporaryAttachment();
        Map<String, Object> variables = variablesWithAttachment("Suspended", attachmentId);
        WorkflowProcessDraftView draft = createDraft(variables);
        repositoryService.suspendProcessDefinitionById(definition.getId());
        when(definitionLockMapper.selectLatestDefaultTenantDefinitionForUpdate(
                definition.getKey())).thenReturn(new WorkflowProcessDefinitionLockRow(
                        definition.getId(), definition.getDeploymentId(), 2));
        long instanceCountBefore = runtimeService.createProcessInstanceQuery().count();

        assertServiceError(() -> submitDraft(draft, variables), HttpStatus.CONFLICT,
                "DRAFT_DEFINITION_UNAVAILABLE");

        assertDraftSubmitFailureHasNoSideEffects(draft.draftId(), attachmentId,
                instanceCountBefore);
    }

    /**
     * 验证草稿提交等待后发现定义已删除时返回草稿定义不可用且不产生业务副作用。
     *
     * @return void，删除错误分类、实例、草稿或附件状态不符合回滚契约时失败
     */
    @Test
    void rejectsDraftSubmitAgainstDeletedDefinitionWithoutSideEffects()
    {
        String attachmentId = insertTemporaryAttachment();
        Map<String, Object> variables = variablesWithAttachment("Deleted", attachmentId);
        WorkflowProcessDraftView draft = createDraft(variables);
        repositoryService.deleteDeployment(definition.getDeploymentId(), true);
        long instanceCountBefore = runtimeService.createProcessInstanceQuery().count();

        assertServiceError(() -> submitDraft(draft, variables), HttpStatus.NOT_FOUND,
                "DRAFT_DEFINITION_UNAVAILABLE");

        assertDraftSubmitFailureHasNoSideEffects(draft.draftId(), attachmentId,
                instanceCountBefore);
    }

    /**
     * 验证草稿提交的定义部署关系漂移返回专用关系冲突且不产生业务副作用。
     *
     * @return void，部署漂移被归为版本或不可用、或事务留下副作用时失败
     */
    @Test
    void rejectsDraftSubmitDeploymentDriftWithoutSideEffects()
    {
        String attachmentId = insertTemporaryAttachment();
        Map<String, Object> variables = variablesWithAttachment("Deployment drift", attachmentId);
        WorkflowProcessDraftView draft = createDraft(variables);
        when(definitionLockMapper.selectLatestDefaultTenantDefinitionForUpdate(
                definition.getKey())).thenReturn(new WorkflowProcessDefinitionLockRow(
                        definition.getId(), "drifted-deployment", 1));
        long instanceCountBefore = runtimeService.createProcessInstanceQuery().count();

        assertServiceError(() -> submitDraft(draft, variables), HttpStatus.CONFLICT,
                "PROCESS_DEFINITION_RELATION_CONFLICT");

        assertDraftSubmitFailureHasNoSideEffects(draft.draftId(), attachmentId,
                instanceCountBefore);
    }

    /**
     * 验证草稿冻结表单正文漂移返回快照不一致子码且不创建实例或迁移附件。
     *
     * @return void，快照漂移分类或提交事务副作用不符合契约时失败
     */
    @Test
    void rejectsDraftSnapshotDriftWithoutSideEffects()
    {
        String attachmentId = insertTemporaryAttachment();
        Map<String, Object> variables = variablesWithAttachment("Snapshot drift", attachmentId);
        WorkflowProcessDraftView draft = createDraft(variables);
        engineInfrastructure.transactionTemplate().executeWithoutResult(status ->
                assertThat(jdbcTemplate.update("""
                        update wf_process_draft set form_snapshot='{}'
                        where draft_id=? and owner_user_id=?
                        """, draft.draftId(), OWNER_ID)).isOne());
        long instanceCountBefore = runtimeService.createProcessInstanceQuery().count();

        assertServiceError(() -> submitDraft(draft, variables), HttpStatus.CONFLICT,
                "DRAFT_SNAPSHOT_MISMATCH");

        assertDraftSubmitFailureHasNoSideEffects(draft.draftId(), attachmentId,
                instanceCountBefore);
    }

    /**
     * 验证用户临时附件字节配额超限返回附件配额专用子码且不落库。
     *
     * @return void，配额异常无子码、被归为附件状态冲突或产生元数据时失败
     */
    @Test
    void rejectsAttachmentQuotaExceededWithStableSubCode()
    {
        attachmentProperties.setMaxTemporaryBytes(1L);
        // H2 不支持正式 Mapper 的 MySQL ON DUPLICATE KEY；仅替换配额 guard 与聚合读。
        doReturn(1).when(attachmentMapper).ensureOwnerQuotaGuard(OWNER_ID);
        doReturn(OWNER_ID).when(attachmentMapper).selectOwnerQuotaGuardForUpdate(OWNER_ID);
        doReturn(new WorkflowAttachmentQuotaUsage(0L, 0L)).when(attachmentMapper)
                .selectTemporaryQuotaUsage(OWNER_ID);
        long attachmentCountBefore = jdbcTemplate.queryForObject(
                "select count(*) from wf_attachment", Long.class);

        assertServiceError(() -> attachmentService.uploadTemporary("evidence",
                new MockMultipartFile("file", "quota.txt", "text/plain",
                        "too-large".getBytes())), HttpStatus.CONFLICT,
                "WORKFLOW_ATTACHMENT_QUOTA_EXCEEDED");

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from wf_attachment", Long.class))
                .isEqualTo(attachmentCountBefore);
    }

    /**
     * 验证实例创建后的附件条件更新竞争返回附件状态子码并回滚实例、草稿和附件。
     *
     * @return void，附件状态竞争被覆盖成草稿版本冲突或留下孤儿实例时失败
     */
    @Test
    void preservesAttachmentStateConflictAndRollsBackStartedInstance()
    {
        String attachmentId = insertTemporaryAttachment();
        Map<String, Object> variables = variablesWithAttachment("Attachment conflict", attachmentId);
        WorkflowProcessDraftView draft = createDraft(variables);
        doReturn(0).when(attachmentMapper).bindDraftStartAttachment(
                eq(attachmentId), eq(OWNER_ID), eq("evidence"), eq(draft.draftId()),
                anyString(), eq("start"));
        long instanceCountBefore = runtimeService.createProcessInstanceQuery().count();

        assertServiceError(() -> submitDraft(draft, variables), HttpStatus.CONFLICT,
                "WORKFLOW_ATTACHMENT_STATE_CONFLICT");

        assertDraftSubmitFailureHasNoSideEffects(draft.draftId(), attachmentId,
                instanceCountBefore);
    }

    /**
     * 验证已有业务子码即使携带可重试并发 cause，也不会被草稿并发入口覆盖。
     *
     * @return void，已有子码被替换为 DRAFT_VERSION_CONFLICT 或事务留下实例时失败
     */
    @Test
    void preservesExistingBusinessSubCodeAcrossDraftConcurrencyClassification()
    {
        WorkflowProcessDraftView draft = createDraft(VALID_VARIABLES);
        ServiceException existingBusinessConflict = new ServiceException(
                "附件状态竞争", HttpStatus.CONFLICT)
                .setSubCode("WORKFLOW_ATTACHMENT_STATE_CONFLICT");
        existingBusinessConflict.initCause(new CannotAcquireLockException(
                "test retryable cause"));
        doThrow(existingBusinessConflict).when(draftMapper).markSubmitted(
                eq(draft.draftId()), eq(OWNER_ID), eq(draft.revisionNo()),
                anyString(), anyString(), anyString(), nullable(String.class));
        long instanceCountBefore = runtimeService.createProcessInstanceQuery().count();

        assertServiceError(() -> submitDraft(draft, VALID_VARIABLES),
                HttpStatus.CONFLICT, "WORKFLOW_ATTACHMENT_STATE_CONFLICT");

        assertThat(runtimeService.createProcessInstanceQuery().count())
                .isEqualTo(instanceCountBefore);
        assertThat(draftMapper.selectOwnedById(draft.draftId(), OWNER_ID).draftStatus())
                .isEqualTo(WorkflowProcessDraftStatus.ACTIVE);
    }

    /**
     * 断言服务调用返回指定状态码和稳定机器子码。
     *
     * @param action ThrowingCallable，预期抛出 ServiceException 的服务调用
     * @param status int，预期业务状态码
     * @param subCode String，预期稳定机器子码
     * @return void，异常类型、状态码或子码不一致时由 AssertJ 报告
     */
    private void assertServiceError(ThrowingCallable action, int status, String subCode)
    {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(status);
                    assertThat(exception.getSubCode()).isEqualTo(subCode);
                });
    }

    /**
     * 使用当前草稿 revision 和正式变量调用唯一草稿提交入口。
     *
     * @param draft WorkflowProcessDraftView，当前活动草稿视图
     * @param variables Map&lt;String,Object&gt;，本次提交的完整正式变量
     * @return WorkflowProcessDraftSubmitView，真实提交成功结果
     */
    private WorkflowProcessDraftSubmitView submitDraft(WorkflowProcessDraftView draft,
            Map<String, Object> variables)
    {
        return draftService.submit(draft.draftId(), new WorkflowProcessDraftSubmitRequest(
                draft.revisionNo(), null, variables, Map.of()));
    }

    /**
     * 断言草稿提交失败同时保持实例数、草稿活动状态和附件草稿状态。
     *
     * @param draftId String，提交失败的草稿 UUID
     * @param attachmentId String，应继续属于草稿的附件 UUID
     * @param instanceCountBefore long，提交前真实 Flowable 活动实例总数
     * @return void，任一事务副作用未回滚时失败
     */
    private void assertDraftSubmitFailureHasNoSideEffects(String draftId,
            String attachmentId, long instanceCountBefore)
    {
        assertThat(runtimeService.createProcessInstanceQuery().count())
                .isEqualTo(instanceCountBefore);
        assertThat(draftMapper.selectOwnedById(draftId, OWNER_ID).draftStatus())
                .isEqualTo(WorkflowProcessDraftStatus.ACTIVE);
        WfAttachment attachment = attachmentMapper.selectById(attachmentId);
        assertThat(attachment.status()).isEqualTo(WorkflowAttachmentStatus.DRAFT);
        assertThat(attachment.processInstanceId()).isNull();
    }

    /**
     * 构造包含单个 evidence 附件引用的正式开始表单变量。
     *
     * @param requester String，开始表单申请人字段值
     * @param attachmentId String，服务端生成的附件 UUID
     * @return Map&lt;String,Object&gt;，可同时用于草稿创建与正式提交的变量
     */
    private Map<String, Object> variablesWithAttachment(String requester,
            String attachmentId)
    {
        return Map.of("requester", requester, "evidence", List.of(attachmentId));
    }

    /**
     * 部署同一流程 key 的下一真实 Flowable 版本，用于制造确定的最新版漂移。
     *
     * @return ProcessDefinition，新部署产生的当前最新版定义
     */
    private ProcessDefinition deployNextVersion()
    {
        Deployment latestDeployment = repositoryService.createDeployment()
                .addString("start-chain-next-" + UUID.randomUUID() + ".bpmn20.xml", BPMN)
                .deploy();
        return repositoryService.createProcessDefinitionQuery()
                .deploymentId(latestDeployment.getId()).singleResult();
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
        engineInfrastructure.transactionTemplate().executeWithoutResult(status ->
                assertThat(attachmentMapper.insert(attachment)).isOne());
        return attachmentId;
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
        rule.setProcessKey(definition.getKey());
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
     * 清空夹具阶段调用，避免只读授权测试读取初始化噪声。
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

    /** 同一表单契约下在 start 命令内同步结束的真实流程定义。 */
    private static final String INSTANT_BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
              xmlns:flowable="http://flowable.org/bpmn" targetNamespace="instant-start-chain-it">
              <process id="instantStartChain" name="Instant Start Chain" isExecutable="true">
                <startEvent id="start" flowable:formKey="startForm"/>
                <endEvent id="end"/>
                <sequenceFlow id="flow_start_end" sourceRef="start" targetRef="end"/>
              </process>
            </definitions>
            """;
}
