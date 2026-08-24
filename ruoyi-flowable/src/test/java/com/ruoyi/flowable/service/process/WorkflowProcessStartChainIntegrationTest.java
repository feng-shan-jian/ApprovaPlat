package com.ruoyi.flowable.service.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import com.ruoyi.flowable.domain.dto.WorkflowBpmnXmlQueryDto;
import com.ruoyi.flowable.domain.dto.WorkflowProcessDraftCreateRequest;
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
import com.ruoyi.flowable.testsupport.WorkflowFlowableEngineTestSupport;
import com.ruoyi.flowable.testsupport.WorkflowH2SchemaMapperSupport;
import com.ruoyi.system.service.ISysUserService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 使用同一真实 H2 数据源、Flowable 8、Spring 事务和正式 MyBatis XML 验证草稿提交发起写链。
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
        engineInfrastructure = WorkflowFlowableEngineTestSupport.start(
                "start-chain", Map.of());
        DataSource dataSource = engineInfrastructure.dataSource();
        processEngine = engineInfrastructure.processEngine();
        repositoryService = spy(processEngine.getRepositoryService());
        runtimeService = spy(processEngine.getRuntimeService());
        HistoryService historyService = processEngine.getHistoryService();
        TaskService taskService = processEngine.getTaskService();

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
        WorkflowEngineOperations engineOperations =
                engineInfrastructure.transactionalProxy(operationsTarget);

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
        WorkflowAttachmentService attachmentService =
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
}
