package com.ruoyi.flowable.service.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
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
import com.ruoyi.flowable.domain.dto.WorkflowProcessDraftCreateRequest;
import com.ruoyi.flowable.domain.dto.WorkflowProcessDraftSaveRequest;
import com.ruoyi.flowable.domain.dto.WorkflowProcessDraftSubmitRequest;
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
import com.ruoyi.flowable.service.attachment.WorkflowAttachmentCleanupCoordinator;
import com.ruoyi.flowable.service.attachment.WorkflowAttachmentUploadJournal;
import com.ruoyi.flowable.service.attachment.WorkflowAttachmentStorage;
import com.ruoyi.flowable.service.identity.WorkflowParticipantRuleRuntimeService;
import com.ruoyi.flowable.service.model.WorkflowDeploymentArtifactRepository;
import com.ruoyi.flowable.service.model.WorkflowDeploymentArtifacts;
import com.ruoyi.flowable.service.model.WorkflowDeploymentService;
import com.ruoyi.flowable.testsupport.WorkflowFlowableEngineTestSupport;
import com.ruoyi.flowable.testsupport.WorkflowH2SchemaMapperSupport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 使用同一真实 H2 数据源、Flowable 8、Spring 事务和正式 MyBatis XML 验证草稿提交发起写链。
 */
class WorkflowProcessStartChainIntegrationTest
{
    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void nestedPermissionsSurviveFreezeDeploymentDraftAndRealStart(boolean hidden)
    {
        Deployment deployment = repositoryService.createDeployment()
                .addString("nested-start.bpmn20.xml", BPMN).deploy();
        definition = repositoryService.createProcessDefinitionQuery().deploymentId(deployment.getId()).singleResult();
        WorkflowDeploymentArtifacts artifacts = deploymentArtifacts(deployment.getId());
        var mapper = JsonMapper.shared();
        var source = mapper.readTree(FORM_CONTENT);
        var root = mapper.createObjectNode();
        var row = root.putArray("fields").addObject();
        row.put("layout", "rowFormItem").put("workflowHidden", hidden)
                .put("workflowReadable", !hidden).put("workflowWritable", false);
        row.set("children", source.get("fields"));
        var reference = new com.ruoyi.flowable.service.model.WorkflowBpmnFormReference(
                com.ruoyi.flowable.service.model.WorkflowFormSourceType.TEMPLATE, 1L,
                "startForm", "start", "开始", null, definition.getKey(),
                com.ruoyi.flowable.service.model.WorkflowFormFieldPermissionMode.REQUIRED,
                Map.of("requester", com.ruoyi.flowable.service.model.WorkflowFormFieldPermissionMode.REQUIRED,
                        "evidence", com.ruoyi.flowable.service.model.WorkflowFormFieldPermissionMode.EDITABLE));
        String frozen = com.ruoyi.flowable.service.model.WorkflowNodeFormPermissionSnapshot.apply(
                new WorkflowFormTemplateValidator().validate(root.toString()), reference).toString();
        artifacts.forms().get(0).setContent(frozen);
        artifactRepository.persist(deployment.getId(), artifacts);
        when(definitionLockMapper.selectLatestDefaultTenantDefinitionForUpdate(definition.getKey()))
                .thenReturn(new WorkflowProcessDefinitionLockRow(definition.getId(), deployment.getId(), 1));

        assertThatThrownBy(() -> createDraft(Map.of("requester", "forged"))).hasMessageContaining("只读");
        WorkflowProcessDraftView draft = createDraft(Map.of());
        assertThat(draftMapper.selectOwnedById(draft.draftId(), OWNER_ID).formSnapshot()).isEqualTo(frozen);
        WorkflowProcessDraftSubmitView submitted = submitDraft(draft, Map.of());
        assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(submitted.processInstanceId()).count()).isOne();
        assertThat(runtimeService.getVariables(submitted.processInstanceId())).doesNotContainKeys("requester", "evidence");
        assertThat(artifactRepository.openRead(deployment.getId()).forms().get(0).getContent()).isEqualTo(frozen);
        assertThat(draftMapper.selectOwnedById(draft.draftId(), OWNER_ID).draftStatus()).isEqualTo(WorkflowProcessDraftStatus.SUBMITTED);
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
    private WorkflowAttachmentService attachmentTarget;
    private WorkflowProcessAccessService processAccess;
    /** 加入 Spring 事务代理的统一 Flowable 写边界。 */
    private WorkflowEngineOperations engineOperations;
    private WorkflowProcessDefinitionQueryService queryService;
    private com.ruoyi.flowable.service.task.WorkflowAutomaticCopyService automaticCopies;
    private com.ruoyi.flowable.service.notification.WorkflowNotificationService notifications;
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
        var listenerBeans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        automaticCopies = mock(com.ruoyi.flowable.service.task.WorkflowAutomaticCopyService.class);
        notifications = mock(com.ruoyi.flowable.service.notification.WorkflowNotificationService.class);
        listenerBeans.registerSingleton("copies", automaticCopies);
        listenerBeans.registerSingleton("notifications", notifications);
        listenerBeans.registerSingleton("outbox", mock(com.ruoyi.flowable.service.notification.WorkflowNotificationOutboxService.class));
        processEngine.getRuntimeService().addEventListener(new WorkflowProcessCompletionStatusListener(
                listenerBeans.getBeanProvider(com.ruoyi.flowable.service.task.WorkflowAutomaticCopyService.class),
                listenerBeans.getBeanProvider(com.ruoyi.flowable.service.notification.WorkflowNotificationService.class),
                listenerBeans.getBeanProvider(com.ruoyi.flowable.service.notification.WorkflowNotificationOutboxService.class)));
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
        processAccess = spy(new WorkflowProcessAccessService(
                processEngine.getHistoryService(), runtimeService, processEngine.getTaskService(), engineOperations,
                identityResolver, mock(com.ruoyi.flowable.mapper.WfCopyMapper.class),
                mock(com.ruoyi.system.service.SysPermissionEvaluator.class),
                new com.ruoyi.flowable.service.task.WorkflowTaskUnclaimPolicy()));
        var uploadJournal = new WorkflowAttachmentUploadJournal(attachmentMapper, attachmentStorage,
                attachmentProperties, engineInfrastructure.transactionManager());
        var cleanupCoordinator = new WorkflowAttachmentCleanupCoordinator(attachmentMapper, attachmentStorage,
                attachmentProperties, uploadJournal, engineInfrastructure.transactionManager());
        attachmentTarget = spy(new WorkflowAttachmentService(
                attachmentMapper, attachmentStorage, attachmentProperties, identityResolver,
                processAccess, uploadJournal, cleanupCoordinator,
                engineInfrastructure.transactionManager()));
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
                .doesNotContainKey("processStatus")
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

    @Test
    void engineWriteOverloadsKeepTransactionGuardAndRestoreNestedIdentity()
    {
        var identityService = mock(org.flowable.engine.IdentityService.class);
        var target = new WorkflowEngineOperations(new WorkflowAuthenticationContext(identityService,
                new WorkflowIdentityCodec()), new WorkflowExceptionTranslator(), identityResolver);
        var operations = engineInfrastructure.transactionalProxy(target);
        java.util.function.Supplier<Integer> readIsolation = () -> jdbcTemplate.execute(
                (org.springframework.jdbc.core.ConnectionCallback<Integer>) java.sql.Connection::getTransactionIsolation);
        assertThat(operations.writeAsCurrentUser(readIsolation)).isEqualTo(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
        operations.writeAsCurrentUser((Runnable) () -> assertThat(readIsolation.get())
                .isEqualTo(java.sql.Connection.TRANSACTION_REPEATABLE_READ));
        clearInvocations(identityService);
        operations.writeAsCurrentUser(() -> {
            assertThat(operations.writeAsUser("200", () -> "nested")).isEqualTo("nested");
            return "outer";
        });
        var order = inOrder(identityService);
        order.verify(identityService).setAuthenticatedUserId(USER_ID);
        order.verify(identityService).setAuthenticatedUserId("200");
        order.verify(identityService).setAuthenticatedUserId(USER_ID);
        order.verify(identityService).setAuthenticatedUserId(null);
        clearInvocations(identityService, identityResolver);
        assertThatThrownBy(() -> operations.writeAsCurrentUser((Runnable) () -> { throw new IllegalStateException("failed"); }))
                .isInstanceOf(IllegalStateException.class);
        verify(identityService).setAuthenticatedUserId(null);
        clearInvocations(identityService, identityResolver);
        var weakerTransaction = new org.springframework.transaction.support.TransactionTemplate(engineInfrastructure.transactionManager());
        weakerTransaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        assertThatThrownBy(() -> weakerTransaction.execute(status -> operations.writeAsCurrentUser(readIsolation)))
                .isInstanceOfSatisfying(ServiceException.class, failure -> assertThat(failure.getCode()).isEqualTo(500));
        assertThatThrownBy(() -> target.writeAsCurrentUser((Runnable) () -> {}))
                .isInstanceOfSatisfying(ServiceException.class, failure -> assertThat(failure.getCode()).isEqualTo(500));
        verifyNoInteractions(identityService, identityResolver);
    }

    @Test
    void draftAttachmentSaveReusesRetainedRowsRemovesOldAndSubmitsLateUploads()
    {
        String retained = insertTemporaryAttachment();
        String removed = insertTemporaryAttachment();
        var draft = createDraft(Map.of("requester", "Alice", "evidence", List.of(retained, removed)));
        String added = insertTemporaryAttachment();
        clearInvocations(attachmentMapper);
        var saved = draftService.save(draft.draftId(), new WorkflowProcessDraftSaveRequest(
                draft.revisionNo(), null, Map.of("requester", "Alice", "evidence", List.of(retained, added)), Map.of()));
        assertThat(attachmentMapper.selectById(retained).status()).isEqualTo(WorkflowAttachmentStatus.DRAFT);
        assertThat(attachmentMapper.selectById(added).status()).isEqualTo(WorkflowAttachmentStatus.DRAFT);
        assertThat(attachmentMapper.selectById(removed).status()).isEqualTo(WorkflowAttachmentStatus.DELETED);
        var saveOrder = inOrder(attachmentMapper);
        saveOrder.verify(attachmentMapper).selectByDraftIdForUpdate(draft.draftId(), OWNER_ID);
        saveOrder.verify(attachmentMapper).selectByIdsForUpdate(argThat(ids -> ids.containsAll(List.of(retained, added))));
        verify(attachmentMapper, never()).bindDraftAttachment(eq(retained), anyLong(), anyString(), anyString());

        String lateUpload = insertTemporaryAttachment();
        clearInvocations(attachmentMapper);
        var submitted = submitDraft(saved, Map.of("requester", "Alice", "evidence", List.of(retained, added, lateUpload)));
        var submitOrder = inOrder(attachmentMapper);
        submitOrder.verify(attachmentMapper).selectByDraftIdForUpdate(draft.draftId(), OWNER_ID);
        submitOrder.verify(attachmentMapper).selectByIdsForUpdate(argThat(ids -> ids.size() == 1 && ids.contains(lateUpload)));
        for (String id : List.of(retained, added, lateUpload))
        {
            var row = attachmentMapper.selectById(id);
            assertThat(row.status()).isEqualTo(WorkflowAttachmentStatus.BOUND);
            assertThat(row.processInstanceId()).isEqualTo(submitted.processInstanceId());
        }
    }

    @Test
    void draftAttachmentCoordinationRejectsDuplicatesForeignOwnerFieldAndExpiredTemp()
    {
        var draft = createDraft(VALID_VARIABLES);
        String attachment = insertTemporaryAttachment();
        for (boolean submission : List.of(false, true))
        {
            java.util.function.Consumer<Map<String, List<String>>> coordinate = references -> {
                if (submission) attachmentService.prepareDraftSubmissionVariables(USER_ID, draft.draftId(), VALID_VARIABLES, references);
                else attachmentService.reconcileDraftAttachments(USER_ID, draft.draftId(), references);
            };
            assertThatThrownBy(() -> coordinate.accept(Map.of("evidence", List.of(attachment, attachment))))
                    .isInstanceOfSatisfying(ServiceException.class, failure -> assertThat(failure.getCode()).isEqualTo(400));
            assertThatThrownBy(() -> coordinate.accept(Map.of("other", List.of(attachment))))
                    .isInstanceOfSatisfying(ServiceException.class, failure -> assertThat(failure.getCode()).isEqualTo(400));
            jdbcTemplate.update("update wf_attachment set owner_user_id = ? where attachment_id = ?", OWNER_ID + 1, attachment);
            assertThatThrownBy(() -> coordinate.accept(Map.of("evidence", List.of(attachment))))
                    .isInstanceOfSatisfying(ServiceException.class, failure -> assertThat(failure.getCode()).isEqualTo(403));
            jdbcTemplate.update("update wf_attachment set owner_user_id = ?, expire_time = ? where attachment_id = ?",
                    OWNER_ID, java.sql.Timestamp.valueOf(LocalDateTime.now().minusMinutes(1)), attachment);
            assertThatThrownBy(() -> coordinate.accept(Map.of("evidence", List.of(attachment))))
                    .isInstanceOfSatisfying(ServiceException.class, failure -> assertThat(failure.getCode()).isEqualTo(409));
            jdbcTemplate.update("update wf_attachment set expire_time = ? where attachment_id = ?",
                    java.sql.Timestamp.valueOf(LocalDateTime.now().plusHours(1)), attachment);
            assertThat(attachmentMapper.selectById(attachment).status()).isEqualTo(WorkflowAttachmentStatus.TEMP);
        }
        assertThatThrownBy(() -> attachmentService.bindDraftStartAttachments(USER_ID, draft.draftId(),
                "instance", "start", Map.of("evidence", List.of(attachment, attachment))))
                .isInstanceOfSatisfying(ServiceException.class, failure -> assertThat(failure.getCode()).isEqualTo(400));
    }

    @Test
    void detailReusesAuthorizedInstanceAndStillRejectsInvalidAttachmentRelations()
    {
        String attachmentId = insertTemporaryAttachment();
        Map<String, Object> values = Map.of("requester", "Alice", "evidence", List.of(attachmentId));
        var submitted = submitDraft(createDraft(values), values);
        var access = processAccess.requireReadableInstance(submitted.processInstanceId());
        clearInvocations(processAccess, attachmentMapper);
        assertThat(attachmentService.projectReadableBoundMetadata(access, Map.of())).isEmpty();
        verifyNoInteractions(processAccess, attachmentMapper);
        var metadata = attachmentService.projectReadableBoundMetadata(access, Map.of(attachmentId, "evidence"));
        assertThat(metadata.get(attachmentId).get("attachmentId").asText()).isEqualTo(attachmentId);
        verify(attachmentMapper, times(1)).selectByIds(List.of(attachmentId));
        verifyNoInteractions(processAccess);
        assertThatThrownBy(() -> attachmentService.projectReadableBoundMetadata(access, Map.of(attachmentId, "wrongField")))
                .hasMessageContaining("字段或状态不一致");
        var other = submitDraft(createDraft(VALID_VARIABLES), VALID_VARIABLES);
        var otherAccess = processAccess.requireReadableInstance(other.processInstanceId());
        assertThatThrownBy(() -> attachmentService.projectReadableBoundMetadata(otherAccess, Map.of(attachmentId, "evidence")))
                .hasMessageContaining("字段或状态不一致");
        String temporaryId = insertTemporaryAttachment();
        assertThatThrownBy(() -> attachmentService.projectReadableBoundMetadata(access, Map.of(temporaryId, "evidence")))
                .hasMessageContaining("字段或状态不一致");
        assertThatThrownBy(() -> attachmentService.projectReadableBoundMetadata(access, Map.of(UUID.randomUUID().toString(), "evidence")))
                .hasMessageContaining("引用数据异常");

        var userMapper = mock(com.ruoyi.flowable.mapper.WorkflowMultiInstanceUserMapper.class);
        var history = new WorkflowProcessHistoryProjection(processEngine.getHistoryService(),
                processEngine.getTaskService(), repositoryService, userMapper);
        var variableProjection = new WorkflowProcessVariableProjection(WorkflowH2SchemaMapperSupport.createSpringMapper(
                engineInfrastructure.dataSource(), "detail-variables", com.ruoyi.flowable.mapper.WorkflowHistoricVariableMapper.class,
                "mapper/flowable/WorkflowHistoricVariableMapper.xml"));
        var projection = new WorkflowProcessFormDetailProjection(new WorkflowFormTemplateValidator(),
                variableProjection, attachmentService);
        clearInvocations(processAccess, attachmentTarget, attachmentMapper);
        var projected = projection.project(new WorkflowProcessFormDetailProjection.FormProjectionRequest(
                projection.loadSchemas(otherAccess.deploymentId(), artifactRepository.openRead(otherAccess.deploymentId()).forms()), history.loadHistory(otherAccess),
                repositoryService.getBpmnModel(definition.getId()).getProcessById(definition.getKey()),
                variableProjection.loadSubmissionSnapshots(other.processInstanceId(), otherAccess.deploymentId(), Set.of()),
                otherAccess, null, false, false));
        assertThat(projected.formDetail().applicationForm().values().get("requester").asText()).isEqualTo("Alice");
        verifyNoInteractions(processAccess, attachmentTarget, attachmentMapper);

        clearInvocations(processAccess);
        assertThat(attachmentService.getReadableMetadata(attachmentId).attachmentId()).isEqualTo(attachmentId);
        verify(processAccess, times(1)).requireReadableInstance(submitted.processInstanceId());
        currentIdentity.set(new WorkflowCurrentIdentity("200", Set.of()));
        assertServiceError(() -> attachmentService.getReadableMetadata(attachmentId), HttpStatus.FORBIDDEN, null);
    }

    @Test
    void detailSharesDeepCallActivityRelationsAndKeepsAncestorSnapshotScope()
    {
        var listenerBeans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        listenerBeans.registerSingleton("definitions", queryService);
        listenerBeans.registerSingleton("variables", variableValidator);
        runtimeService.addEventListener(new WorkflowCallActivityStartListener(
                listenerBeans.getBeanProvider(WorkflowProcessDefinitionQueryService.class),
                listenerBeans.getBeanProvider(WorkflowStartVariableValidator.class)));
        ProcessDefinition leafDefinition = deployRelationProcess("relationLeaf", """
                <userTask id="hold" flowable:assignee="100"/><endEvent id="end"/>
                <sequenceFlow sourceRef="start" targetRef="hold"/>
                <sequenceFlow sourceRef="hold" targetRef="end"/>
                """);
        ProcessDefinition middleDefinition = deployRelationProcess("relationMiddle", """
                <callActivity id="child" calledElement="relationLeaf" flowable:inheritVariables="true">
                  <extensionElements><flowable:in source="requester" target="requester"/></extensionElements>
                </callActivity><endEvent id="end"/>
                <sequenceFlow sourceRef="start" targetRef="child"/><sequenceFlow sourceRef="child" targetRef="end"/>
                """);
        definition = deployRelationProcess("relationRoot", """
                <parallelGateway id="split"/><parallelGateway id="join"/><endEvent id="end"/>
                <callActivity id="left" calledElement="relationMiddle" flowable:inheritVariables="true">
                  <extensionElements><flowable:in source="requester" target="requester"/></extensionElements>
                </callActivity>
                <callActivity id="right" calledElement="relationMiddle" flowable:inheritVariables="true">
                  <extensionElements><flowable:in source="requester" target="requester"/></extensionElements>
                </callActivity>
                <sequenceFlow sourceRef="start" targetRef="split"/>
                <sequenceFlow sourceRef="split" targetRef="left"/><sequenceFlow sourceRef="split" targetRef="right"/>
                <sequenceFlow sourceRef="left" targetRef="join"/><sequenceFlow sourceRef="right" targetRef="join"/>
                <sequenceFlow sourceRef="join" targetRef="end"/>
                """);
        var submitted = submitDraft(createDraft(VALID_VARIABLES), VALID_VARIABLES);
        var children = runtimeService.createProcessInstanceQuery().superProcessInstanceId(submitted.processInstanceId()).list();
        assertThat(children).hasSize(2);
        var leaf = runtimeService.createProcessInstanceQuery().superProcessInstanceId(children.get(0).getId()).singleResult();
        var access = processAccess.requireReadableInstance(leaf.getId());
        var historyService = spy(processEngine.getHistoryService());
        var history = new WorkflowProcessHistoryProjection(historyService, processEngine.getTaskService(),
                repositoryService, mock(com.ruoyi.flowable.mapper.WorkflowMultiInstanceUserMapper.class));
        clearInvocations(repositoryService);
        var relations = history.loadProcessRelations(access, leafDefinition);
        assertThat(relations.views()).hasSize(5).isSortedAccordingTo(java.util.Comparator
                .comparing(com.ruoyi.flowable.domain.vo.WorkflowProcessRelationView::startTime)
                .thenComparing(com.ruoyi.flowable.domain.vo.WorkflowProcessRelationView::processInstanceId));
        assertThat(relations.views()).filteredOn(row -> row.current()).singleElement()
                .satisfies(row -> assertThat(row.processInstanceId()).isEqualTo(leaf.getId()));
        assertThat(relations.views()).extracting(row -> row.rootProcessInstanceId()).containsOnly(submitted.processInstanceId());
        assertThat(relations.ancestorDeploymentIds()).containsExactlyInAnyOrder(
                definition.getDeploymentId(), middleDefinition.getDeploymentId());
        verify(historyService, times(7)).createHistoricProcessInstanceQuery(); // 2 ancestors + 5 BFS parents
        verify(repositoryService, times(1)).getProcessDefinition(definition.getId());
        verify(repositoryService, times(1)).getProcessDefinition(middleDefinition.getId());
        verify(repositoryService, org.mockito.Mockito.never()).createProcessDefinitionQuery();

        var mapper = WorkflowH2SchemaMapperSupport.createSpringMapper(engineInfrastructure.dataSource(),
                "relation-variables", com.ruoyi.flowable.mapper.WorkflowHistoricVariableMapper.class,
                "mapper/flowable/WorkflowHistoricVariableMapper.xml");
        var variables = new WorkflowProcessVariableProjection(mapper);
        assertThat(variables.loadSubmissionSnapshots(leaf.getId(), leafDefinition.getDeploymentId(),
                relations.ancestorDeploymentIds())).isNotNull();
        assertThatThrownBy(() -> variables.loadSubmissionSnapshots(leaf.getId(), leafDefinition.getDeploymentId(), Set.of()))
                .isInstanceOf(ServiceException.class);

        var rootAccess = processAccess.requireReadableInstance(submitted.processInstanceId());
        clearInvocations(historyService, repositoryService);
        assertThat(history.loadProcessRelations(rootAccess, definition).ancestorDeploymentIds()).isEmpty();
        verify(historyService, times(5)).createHistoricProcessInstanceQuery();
        verify(repositoryService, times(2)).createProcessDefinitionQuery(); // two distinct descendant definitions

        var json = JsonMapper.shared().valueToTree(access);
        assertThat(json.propertyNames()).containsExactlyInAnyOrder("processInstanceId", "processDefinitionId",
                "deploymentId", "businessKey", "startUserId", "startTime", "endTime", "deleteReason", "businessStatus", "state");

        for (String brokenParent : List.of("missing-parent", leaf.getId()))
        {
            engineInfrastructure.transactionTemplate().executeWithoutResult(status -> {
                status.setRollbackOnly();
                jdbcTemplate.update("update ACT_HI_PROCINST set SUPER_PROCESS_INSTANCE_ID_=? where ID_=?",
                        brokenParent, children.get(0).getId());
                assertThatThrownBy(() -> history.loadProcessRelations(access, leafDefinition))
                        .hasMessageContaining("missing-parent".equals(brokenParent) ? "父实例关系记录不存在" : "循环或规模");
            });
        }
        var corruptHistory = mock(org.flowable.engine.HistoryService.class);
        var query = mock(org.flowable.engine.history.HistoricProcessInstanceQuery.class, org.mockito.Answers.RETURNS_SELF);
        when(corruptHistory.createHistoricProcessInstanceQuery()).thenReturn(query);
        var projection = new WorkflowProcessHistoryProjection(corruptHistory, processEngine.getTaskService(),
                repositoryService, mock(com.ruoyi.flowable.mapper.WorkflowMultiInstanceUserMapper.class));
        var child = processEngine.getHistoryService().createHistoricProcessInstanceQuery().processInstanceId(children.get(0).getId()).singleResult();
        var capacityRows = java.util.stream.IntStream.range(0, 199).mapToObj(index -> {
            var row = spy(child);
            doReturn("capacity-" + index).when(row).getId();
            return row;
        }).toList();
        when(query.listPage(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(capacityRows).thenReturn(List.of());
        assertThat(projection.loadProcessRelations(rootAccess, definition).views()).hasSize(200);
        when(query.listPage(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(java.util.Collections.nCopies(200, child));
        assertThatThrownBy(() -> projection.loadProcessRelations(rootAccess, definition)).hasMessageContaining("规模超过详情上限");
        for (List<org.flowable.engine.history.HistoricProcessInstance> rows : java.util.Arrays.asList(
                null, List.of(child, child), java.util.Arrays.asList((org.flowable.engine.history.HistoricProcessInstance) null),
                List.of(access.historicInstance())))
        {
            when(query.listPage(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(rows);
            assertThatThrownBy(() -> projection.loadProcessRelations(rootAccess, definition)).isInstanceOf(ServiceException.class);
        }
        when(query.listPage(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());
        when(query.singleResult()).thenReturn(rootAccess.historicInstance());
        var middleAccess = processAccess.requireReadableInstance(children.get(0).getId());
        assertThatThrownBy(() -> projection.loadProcessRelations(middleAccess, middleDefinition)).hasMessageContaining("不属于返回的父子执行树");

        for (var task : processEngine.getTaskService().createTaskQuery().processDefinitionKey("relationLeaf").list())
        {
            processEngine.getTaskService().complete(task.getId());
        }
        var finished = processAccess.requireReadableInstance(leaf.getId());
        assertThat(history.loadProcessRelations(finished, leafDefinition).views())
                .extracting(row -> row.processStatus()).containsOnly("completed");
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "WORKFLOW_MYSQL_TEST_URL", matches = ".+")
    void mysqlDetailReadsStayInsideRepeatableReadAndLeaveNoFixtureAfterRollback()
    {
        String url = System.getenv("WORKFLOW_MYSQL_TEST_URL");
        assertThat(url).matches("jdbc:mysql://[^/]+/[A-Za-z0-9_]*_it(?:\\?.*)?");
        var dataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource(url,
                System.getenv("WORKFLOW_MYSQL_TEST_USERNAME"), System.getenv("WORKFLOW_MYSQL_TEST_PASSWORD"));
        var manager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource);
        var transaction = new org.springframework.transaction.support.TransactionTemplate(manager);
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        var configuration = new org.flowable.spring.SpringProcessEngineConfiguration();
        configuration.setDataSource(dataSource);
        configuration.setTransactionManager(manager);
        configuration.setDatabaseSchemaUpdate("false");
        configuration.setHistory("full");
        configuration.setDisableIdmEngine(true);
        configuration.setDisableEventRegistry(true);
        configuration.setAsyncExecutorActivate(false);
        configuration.setIdGenerator(new org.flowable.common.engine.impl.persistence.StrongUuidGenerator());
        var engine = configuration.buildProcessEngine();
        String key = "section5_" + UUID.randomUUID().toString().replace("-", "");
        AtomicReference<String> instanceId = new AtomicReference<>();
        AtomicReference<String> deploymentId = new AtomicReference<>();
        try
        {
            transaction.executeWithoutResult(status -> {
                status.setRollbackOnly();
                var repository = spy(engine.getRepositoryService());
                var historyService = spy(engine.getHistoryService());
                var deployment = repository.createDeployment().name(key)
                        .addString(key + ".bpmn20.xml", BPMN.replace("startChain", key)).deploy();
                deploymentId.set(deployment.getId());
                var deployed = repository.createProcessDefinitionQuery().deploymentId(deployment.getId()).singleResult();
                var artifacts = new WorkflowDeploymentArtifactRepository(repository);
                var snapshots = deploymentArtifacts(deployment.getId());
                snapshots.participantRules().get(0).setProcessKey(key);
                artifacts.persist(deployment.getId(), snapshots);
                var instance = engine.getRuntimeService().createProcessInstanceBuilder()
                        .processDefinitionId(deployed.getId()).businessStatus("running").start();
                instanceId.set(instance.getId());
                var accessService = new WorkflowProcessAccessService(historyService, engine.getRuntimeService(),
                        engine.getTaskService(), null, null, null, null,
                        new com.ruoyi.flowable.service.task.WorkflowTaskUnclaimPolicy());
                var access = accessService.requireReadableInstance(instance.getId(), new WorkflowCurrentIdentity("1", Set.of()));
                var history = new WorkflowProcessHistoryProjection(historyService, engine.getTaskService(), repository,
                        mock(com.ruoyi.flowable.mapper.WorkflowMultiInstanceUserMapper.class));
                clearInvocations(repository, historyService);
                var read = artifacts.openRead(deployment.getId());
                assertThat(read.forms()).hasSize(1);
                assertThat(read.forms()).isSameAs(read.forms());
                assertThat(read.controlledLoops(key)).isEmpty();
                assertThat(read.controlledLoops(key)).isEmpty();
                assertThat(history.loadProcessRelations(access, deployed).views()).singleElement()
                        .satisfies(row -> assertThat(row.processInstanceId()).isEqualTo(instance.getId()));
                verify(repository, times(1)).createDeploymentQuery();
                verify(repository, times(3)).getResourceAsStream(anyString(), anyString());
                verify(repository, org.mockito.Mockito.never()).createProcessDefinitionQuery();
                verify(historyService, times(1)).createHistoricProcessInstanceQuery();
                assertThat(new JdbcTemplate(dataSource).execute(
                        (org.springframework.jdbc.core.ConnectionCallback<Integer>) java.sql.Connection::getTransactionIsolation))
                        .isEqualTo(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
            });
            assertThat(engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(instanceId.get()).count()).isZero();
            assertThat(engine.getHistoryService().createHistoricProcessInstanceQuery().processInstanceId(instanceId.get()).count()).isZero();
            assertThat(engine.getRepositoryService().createDeploymentQuery().deploymentId(deploymentId.get()).count()).isZero();
            assertThat(engine.getRepositoryService().createDeploymentQuery().parentDeploymentId(deploymentId.get()).count()).isZero();
        }
        finally
        {
            engine.close();
        }
    }

    private ProcessDefinition deployRelationProcess(String key, String body)
    {
        var deployment = repositoryService.createDeployment().addString(key + ".bpmn20.xml", """
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="relations">
                <process id="%s" isExecutable="true"><startEvent id="start" flowable:formKey="startForm"/>%s</process></definitions>
                """.formatted(key, body)).deploy();
        var deployed = repositoryService.createProcessDefinitionQuery().deploymentId(deployment.getId()).singleResult();
        var artifacts = deploymentArtifacts(deployment.getId());
        artifacts.participantRules().get(0).setProcessKey(key);
        artifactRepository.persist(deployment.getId(), artifacts);
        when(definitionLockMapper.selectLatestDefaultTenantDefinitionForUpdate(key))
                .thenReturn(new WorkflowProcessDefinitionLockRow(deployed.getId(), deployment.getId(), 1));
        return deployed;
    }

    @Test
    void historyBatchesRawNicknamesAndPreservesLegacyIdentityFallbacks()
    {
        jdbcTemplate.execute("create table sys_user (user_id bigint primary key, nick_name varchar(100), user_name varchar(100), del_flag char(1))");
        jdbcTemplate.update("insert into sys_user values (100, '  发起人  ', 'login100', '0'), (200, '已删除用户', 'login200', '2'), (300, '   ', 'login300', '0'), (400, null, 'login400', '0')");
        var mapper = spy(WorkflowH2SchemaMapperSupport.createSpringMapper(engineInfrastructure.dataSource(),
                "history-names", com.ruoyi.flowable.mapper.WorkflowMultiInstanceUserMapper.class,
                "mapper/flowable/WorkflowMultiInstanceUserMapper.xml"));
        List<String> assignees = List.of("100", "200", "300", "400", "500", "+0100", " 100", "bad", "9223372036854775808");
        StringBuilder nodes = new StringBuilder("<startEvent id=\"s\"/>");
        for (int i = 0; i < assignees.size(); i++)
        {
            nodes.append("<userTask id=\"t").append(i).append("\" flowable:assignee=\"").append(assignees.get(i)).append("\"/>")
                    .append("<sequenceFlow sourceRef=\"").append(i == 0 ? "s" : "t" + (i - 1)).append("\" targetRef=\"t").append(i).append("\"/>");
        }
        nodes.append("<endEvent id=\"e\"/><sequenceFlow sourceRef=\"t8\" targetRef=\"e\"/>");
        repositoryService.createDeployment().addString("history.bpmn20.xml", """
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="names">
                <process id="names" isExecutable="true">%s</process></definitions>
                """.formatted(nodes)).deploy();
        var instance = engineOperations.writeAsCurrentUser(actor -> runtimeService.createProcessInstanceBuilder()
                .processDefinitionKey("names").businessStatus("running").start());
        var tasks = processEngine.getTaskService();
        for (int i = 0; i < assignees.size(); i++)
        {
            var task = tasks.createTaskQuery().processInstanceId(instance.getId()).singleResult();
            tasks.setAssignee(task.getId(), assignees.get(i));
            tasks.complete(task.getId(), "200", Map.of(), Map.of());
        }
        var access = processAccess.requireReadableInstance(instance.getId());
        var history = new WorkflowProcessHistoryProjection(processEngine.getHistoryService(), tasks, repositoryService, mapper);
        var presentation = history.projectPresentation(history.loadHistory(access), access, false);
        assertThat(presentation.startUserName()).isEqualTo("  发起人  ");
        assertThat(presentation.timeline().stream().filter(row -> "userTask".equals(row.activityType())).toList())
                .extracting(com.ruoyi.flowable.domain.vo.WorkflowProcessActivityView::assigneeName)
                .containsExactly("  发起人  ", "已删除用户", "300", "400", "500", "  发起人  ", " 100", "bad", "9223372036854775808");
        assertThat(presentation.timeline().stream().filter(row -> "userTask".equals(row.activityType())).toList())
                .extracting(com.ruoyi.flowable.domain.vo.WorkflowProcessActivityView::completedByName)
                .containsOnly("已删除用户");
        verify(mapper, times(1)).selectRawNickNamesByIds(org.mockito.ArgumentMatchers.argThat(ids ->
                ids.size() == 5 && Set.copyOf(ids).equals(Set.of(100L, 200L, 300L, 400L, 500L))));
        org.mockito.Mockito.verifyNoMoreInteractions(mapper);
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
        String lateUpload = insertTemporaryAttachment();
        Map<String, Object> submissionVariables = Map.of(
                "requester", "Rollback", "evidence", List.of(lateUpload));
        long before = runtimeService.createProcessInstanceQuery().count();
        doReturn(0).when(draftMapper).markSubmitted(eq(draft.draftId()), eq(OWNER_ID),
                eq(draft.revisionNo()), anyString(), anyString(), anyString(),
                nullable(String.class));

        assertThatThrownBy(() -> draftService.submit(draft.draftId(),
                new WorkflowProcessDraftSubmitRequest(draft.revisionNo(), "ROLLBACK",
                        submissionVariables, Map.of())))
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
        assertThat(attachmentMapper.selectById(lateUpload).status()).isEqualTo(WorkflowAttachmentStatus.TEMP);
        assertThat(attachmentMapper.selectById(lateUpload).draftId()).isNull();
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
