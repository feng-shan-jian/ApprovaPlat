package com.ruoyi.flowable.service.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.MultiInstanceLoopCharacteristics;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.IdentityService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.repository.ProcessDefinitionQuery;
import org.flowable.engine.runtime.ProcessInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WorkflowProcessDefinitionLockRow;
import com.ruoyi.flowable.domain.WfDeployParticipantRule;
import com.ruoyi.flowable.domain.WfProcessDraft;
import com.ruoyi.flowable.domain.WorkflowProcessDraftStatus;
import com.ruoyi.flowable.domain.dto.StartProcessRequest;
import com.ruoyi.flowable.domain.dto.WorkflowProcessFormQueryDto;
import com.ruoyi.flowable.domain.vo.WorkflowProcessFormView;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.engine.WorkflowExceptionTranslator;
import com.ruoyi.flowable.engine.WorkflowProcessInstanceSnapshot;
import com.ruoyi.flowable.identity.WorkflowAuthenticationContext;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityCodec;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.identity.WorkflowUserSelectionValidator;
import com.ruoyi.flowable.mapper.WorkflowProcessDefinitionLockMapper;
import com.ruoyi.flowable.service.WorkflowFormTemplateValidator;
import com.ruoyi.flowable.service.attachment.WorkflowAttachmentService;
import com.ruoyi.flowable.service.identity.WorkflowParticipantRuleRuntimeService;
import com.ruoyi.flowable.service.task.WorkflowMultiInstanceModelContract;

class WorkflowProcessStartServiceTest
{
    private static final String DEFINITION_ID = "expense:3:12001";
    private static final String PROCESS_KEY = "expense";
    private static final String DEPLOYMENT_ID = "deployment-9";
    private static final String ATTACHMENT_ID =
            "d9428888-122b-4c6f-8f0c-9c3e1dbd3210";
    private static final String START_FORM = """
            {"fields":[
              {"__config__":{"layout":"colFormItem","tag":"el-input","required":true},
               "__vModel__":"reason","maxlength":100},
              {"__config__":{"layout":"colFormItem","tag":"el-input-number","required":true},
               "__vModel__":"amount","min":0,"max":10000},
              {"__config__":{"layout":"colFormItem","tag":"el-upload","required":false},
               "__vModel__":"files","limit":2}
            ]}
            """;

    private RepositoryService repositoryService;
    private RuntimeService runtimeService;
    private WorkflowProcessQueryService processQueryService;
    private WorkflowIdentityResolver identityResolver;
    private IdentityService identityService;
    private WorkflowAttachmentService attachmentService;
    private WorkflowProcessDefinitionLockMapper definitionLockMapper;
    private WorkflowParticipantRuleRuntimeService participantRuleRuntimeService;
    private WorkflowProcessStartService service;

    /**
     * 为每个测试创建真实认证/异常执行边界和隔离的 Flowable 公共 API 替身。
     *
     * @return void，无返回值
     */
    @BeforeEach
    void setUp()
    {
        // 单元测试不连接数据库，但真实写执行边界仍必须看见显式可重复读事务契约。
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
                Connection.TRANSACTION_REPEATABLE_READ);
        repositoryService = mock(RepositoryService.class);
        runtimeService = mock(RuntimeService.class);
        processQueryService = mock(WorkflowProcessQueryService.class);
        identityResolver = mock(WorkflowIdentityResolver.class);
        identityService = mock(IdentityService.class);
        attachmentService = mock(WorkflowAttachmentService.class);
        definitionLockMapper = mock(WorkflowProcessDefinitionLockMapper.class);
        participantRuleRuntimeService = mock(WorkflowParticipantRuleRuntimeService.class);
        // 普通发起夹具必须提供真实可执行 BPMN；空流程用于证明无发起时会签字段时不产生保留变量。
        BpmnModel bpmnModel = new BpmnModel();
        org.flowable.bpmn.model.Process process = new org.flowable.bpmn.model.Process();
        process.setId(PROCESS_KEY);
        process.setExecutable(true);
        bpmnModel.addProcess(process);
        when(repositoryService.getBpmnModel(anyString())).thenReturn(bpmnModel);
        when(identityResolver.resolveCurrentIdentity())
                .thenReturn(new WorkflowCurrentIdentity("7", Set.of("ROLE2", "DEPT3")));
        when(attachmentService.prepareStartVariables(anyString(), anyMap(), anyMap()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        service = service(engineOperations(identityService, identityResolver));
    }

    /**
     * 清理模拟事务特征，避免当前线程隔离级别影响后续测试。
     *
     * @return void，无返回值
     */
    @AfterEach
    void clearTransactionCharacteristics()
    {
        TransactionSynchronizationManager.clear();
    }

    /**
     * 验证成功发起使用服务端部署快照、当前身份和状态变量，并返回稳定实例快照。
     *
     * @return void，调用关系、变量或返回快照不符合契约时测试失败
     */
    @Test
    void startsAuthorizedActiveDefinitionWithServerManagedVariables()
    {
        ProcessDefinition definition = stubSelectedAndActiveDefinition();
        stubStartForm();
        ProcessInstance processInstance = mock(ProcessInstance.class);
        when(processInstance.getId()).thenReturn("instance-42");
        when(processInstance.getProcessDefinitionId()).thenReturn(DEFINITION_ID);
        when(processInstance.isSuspended()).thenReturn(false);
        when(runtimeService.startProcessInstanceById(eq(DEFINITION_ID), eq("expense-42"), anyMap()))
                .thenReturn(processInstance);

        WorkflowProcessInstanceSnapshot result = service.start(new StartProcessRequest(
                "  " + DEFINITION_ID + "  ", "  expense-42  ",
                Map.of("reason", "采购设备", "amount", 1280)));

        assertThat(result).isEqualTo(new WorkflowProcessInstanceSnapshot(
                "instance-42", DEFINITION_ID, "expense-42", false));
        verify(repositoryService).getProcessDefinition(DEFINITION_ID);
        verify(definition, times(2)).getDeploymentId();

        ArgumentCaptor<WorkflowProcessFormQueryDto> formQuery =
                ArgumentCaptor.forClass(WorkflowProcessFormQueryDto.class);
        verify(processQueryService).getProcessForm(formQuery.capture());
        assertThat(formQuery.getValue()).isEqualTo(new WorkflowProcessFormQueryDto(
                DEFINITION_ID, DEPLOYMENT_ID, null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> variables = ArgumentCaptor.forClass(Map.class);
        verify(runtimeService).startProcessInstanceById(
                eq(DEFINITION_ID), eq("expense-42"), variables.capture());
        assertThat(variables.getValue()).containsAllEntriesOf(Map.of(
                "reason", "采购设备",
                "amount", 1280,
                WorkflowProcessStartService.INITIATOR_VARIABLE, "7",
                WorkflowProcessStartService.PROCESS_STATUS_VARIABLE,
                WorkflowProcessStartService.RUNNING_STATUS));
        assertThat(variables.getValue()).hasSize(5);
        assertThat(variables.getValue().get(WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME))
                .isInstanceOf(String.class);
        var submission = WorkflowFormSubmissionSnapshotCodec.decode((String) variables.getValue()
                .get(WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME));
        assertThat(submission.deploymentId()).isEqualTo(DEPLOYMENT_ID);
        assertThat(submission.formId()).isEqualTo(8L);
        assertThat(submission.nodeKey()).isEqualTo("start");
        assertThat(submission.values()).containsOnlyKeys("reason", "amount");
        assertThat(submission.values().get("reason").textValue()).isEqualTo("采购设备");
        assertThatThrownBy(() -> variables.getValue().put("tamper", true))
                .isInstanceOf(UnsupportedOperationException.class);
        verify(definitionLockMapper, never())
                .selectLatestDefaultTenantDefinitionForUpdate(anyString());
        InOrder authentication = inOrder(identityService);
        authentication.verify(identityService).setAuthenticatedUserId("7");
        authentication.verify(identityService).setAuthenticatedUserId(null);
    }

    /**
     * 验证发起页选择的正式成员只经服务端校验后写入活动专属 Flowable 变量。
     *
     * @return 无返回值；客户端成员绕过审批资格校验或变量名发生漂移时测试失败。
     */
    @Test
    void startsWithValidatedStartMultiInstanceMembers()
    {
        stubSelectedAndActiveDefinition();
        stubStartForm();
        when(repositoryService.getBpmnModel(DEFINITION_ID))
                .thenReturn(startMultiInstanceModel("approve"));
        when(identityResolver.resolveApprovalEligibleUserIds(List.of("8", "9")))
                .thenReturn(new LinkedHashSet<>(List.of("8", "9")));
        ProcessInstance startedInstance = processInstance("instance-members-42");
        when(runtimeService.startProcessInstanceById(
                eq(DEFINITION_ID), eq("expense-members-42"), anyMap()))
                .thenReturn(startedInstance);

        service.start(new StartProcessRequest(DEFINITION_ID, "expense-members-42",
                Map.of("reason", "采购设备", "amount", 1280),
                Map.of("approve", List.of(8L, 9L))));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> variables = ArgumentCaptor.forClass(Map.class);
        verify(runtimeService).startProcessInstanceById(
                eq(DEFINITION_ID), eq("expense-members-42"), variables.capture());
        assertThat(variables.getValue())
                .containsEntry("wfMiUsers_approve", List.of(8L, 9L));
    }

    /**
     * 验证发起来源节点缺少成员时在 RuntimeService 写入前整体拒绝。
     *
     * @return 无返回值；非法发起产生 Flowable 实例或附件绑定时测试失败。
     */
    @Test
    void rejectsMissingStartMultiInstanceMembersBeforeEngineWrite()
    {
        stubSelectedAndActiveDefinition();
        stubStartForm();
        when(repositoryService.getBpmnModel(DEFINITION_ID))
                .thenReturn(startMultiInstanceModel("approve"));

        assertBusinessError(() -> service.start(new StartProcessRequest(
                DEFINITION_ID, "expense-members-42",
                Map.of("reason", "采购设备", "amount", 1280), Map.of())),
                HttpStatus.BAD_REQUEST, "发起时会签或或签成员配置不完整");

        verify(runtimeService, never()).startProcessInstanceById(any(), any(), anyMap());
        verify(attachmentService, never()).bindStartAttachments(
                anyString(), anyString(), anyString(), anyMap());
    }

    /**
     * 验证部署快照授权发生在引擎写入前，成功审计发生在实例与附件绑定后。
     *
     * @return void，人工发起授权或审计顺序变化时测试失败
     */
    @Test
    void authorizesHumanStartBeforeEngineAndAuditsAfterSuccessfulSideEffects()
    {
        ProcessDefinition definition = stubSelectedAndActiveDefinition();
        stubStartForm();
        WfDeployParticipantRule rule = new WfDeployParticipantRule();
        when(participantRuleRuntimeService.assertCanStart(any(), eq(definition)))
                .thenReturn(rule);
        ProcessInstance startedInstance = processInstance("instance-scope-42");
        when(runtimeService.startProcessInstanceById(
                eq(DEFINITION_ID), eq("expense-42"), anyMap()))
                .thenReturn(startedInstance);

        service.start(validRequest());

        InOrder lifecycle = inOrder(participantRuleRuntimeService, runtimeService,
                attachmentService);
        lifecycle.verify(participantRuleRuntimeService).assertCanStart(any(), eq(definition));
        lifecycle.verify(runtimeService).startProcessInstanceById(
                eq(DEFINITION_ID), eq("expense-42"), anyMap());
        lifecycle.verify(attachmentService).bindStartAttachments(
                "7", "instance-scope-42", "start", Map.of());
        lifecycle.verify(participantRuleRuntimeService).recordStartAllowed(
                rule, definition, "instance-scope-42", "7");
    }

    /**
     * 验证未命中部署发起范围时不创建实例、不绑定附件且不写成功审计。
     *
     * @return void，拒绝请求产生任何业务副作用时测试失败
     */
    @Test
    void rejectsHumanStartScopeBeforeAnyEngineSideEffect()
    {
        ProcessDefinition definition = stubSelectedAndActiveDefinition();
        stubStartForm();
        when(participantRuleRuntimeService.assertCanStart(any(), eq(definition)))
                .thenThrow(new ServiceException("当前用户不在流程发起范围内",
                        HttpStatus.FORBIDDEN).setSubCode("PROCESS_START_SCOPE_DENIED"));

        assertThatThrownBy(() -> service.start(validRequest()))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getSubCode())
                                .isEqualTo("PROCESS_START_SCOPE_DENIED"));

        verify(runtimeService, never()).startProcessInstanceById(any(), any(), anyMap());
        verify(attachmentService, never()).bindStartAttachments(
                anyString(), anyString(), anyString(), anyMap());
        verify(participantRuleRuntimeService, never()).recordStartAllowed(
                any(), any(), anyString(), anyString());
    }

    /**
     * 验证草稿提交按当前身份重新校验发起范围，拒绝时不产生引擎、附件或成功审计副作用。
     *
     * @return void，失权草稿仍创建实例或记录成功审计时测试失败
     */
    @Test
    void rejectsDraftStartScopeBeforeEngineWrite()
    {
        ProcessDefinition definition = stubDraftDefinition();
        when(participantRuleRuntimeService.assertCanStart(any(), eq(definition)))
                .thenThrow(new ServiceException("当前用户不在流程发起范围内",
                        HttpStatus.FORBIDDEN).setSubCode("PROCESS_START_SCOPE_DENIED"));

        assertThatThrownBy(() -> service.startDraft(activeDraft(), "expense-draft-42",
                Map.of("reason", "采购设备", "amount", 1280), Map.of()))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getSubCode())
                                .isEqualTo("PROCESS_START_SCOPE_DENIED"));

        verify(runtimeService, never()).startProcessInstanceById(any(), any(), anyMap());
        verify(attachmentService, never()).bindDraftStartAttachments(
                anyString(), anyString(), anyString(), anyString(), anyMap());
        verify(participantRuleRuntimeService, never()).recordStartAllowed(
                any(), any(), anyString(), anyString());
    }

    /**
     * 验证草稿提交成功审计严格发生在发起范围校验、实例创建和附件绑定之后。
     *
     * @return void，草稿成功审计提前或缺失时测试失败
     */
    @Test
    void auditsDraftStartOnlyAfterInstanceAndAttachmentBindingSucceed()
    {
        ProcessDefinition definition = stubDraftDefinition();
        WfDeployParticipantRule rule = new WfDeployParticipantRule();
        when(participantRuleRuntimeService.assertCanStart(any(), eq(definition)))
                .thenReturn(rule);
        ProcessInstance startedInstance = processInstance("instance-draft-42");
        when(runtimeService.startProcessInstanceById(
                eq(DEFINITION_ID), eq("expense-draft-42"), anyMap()))
                .thenReturn(startedInstance);

        service.startDraft(activeDraft(), "expense-draft-42",
                Map.of("reason", "采购设备", "amount", 1280), Map.of());

        InOrder lifecycle = inOrder(participantRuleRuntimeService, runtimeService,
                attachmentService);
        lifecycle.verify(participantRuleRuntimeService).assertCanStart(any(), eq(definition));
        lifecycle.verify(runtimeService).startProcessInstanceById(
                eq(DEFINITION_ID), eq("expense-draft-42"), anyMap());
        lifecycle.verify(attachmentService).bindDraftStartAttachments(
                "7", "draft-42", "instance-draft-42", "start", Map.of());
        lifecycle.verify(participantRuleRuntimeService).recordStartAllowed(
                rule, definition, "instance-draft-42", "7");
    }

    /**
     * 验证附件安全投影进入 RuntimeService，且真实实例主键用于后续附件绑定。
     * @return void，原始 UUID、内部存储字段或伪造实例主键进入绑定链路时测试失败
     */
    @Test
    void startsWithSafeAttachmentProjectionAndBindsRealInstanceId()
    {
        stubSelectedAndActiveDefinition();
        stubStartForm();
        ProcessInstance processInstance = mock(ProcessInstance.class);
        when(processInstance.getId()).thenReturn("instance-attachment-42");
        when(processInstance.getProcessDefinitionId()).thenReturn(DEFINITION_ID);
        when(processInstance.isSuspended()).thenReturn(false);
        when(runtimeService.startProcessInstanceById(
                eq(DEFINITION_ID), eq("expense-attachment-42"), anyMap()))
                .thenReturn(processInstance);

        ArrayNode safeAttachments = JsonNodeFactory.instance.arrayNode();
        safeAttachments.addObject()
                .put("attachmentId", ATTACHMENT_ID)
                .put("fieldName", "files")
                .put("originalName", "invoice.pdf")
                .put("contentType", "application/pdf")
                .put("fileSize", 128L)
                .put("sha256", "a".repeat(64));
        when(attachmentService.prepareStartVariables(
                eq("7"), anyMap(), eq(Map.of("files", List.of(ATTACHMENT_ID)))))
                .thenReturn(Map.of("reason", "采购设备", "amount", 1280,
                        "files", safeAttachments));

        WorkflowProcessInstanceSnapshot result = service.start(new StartProcessRequest(
                DEFINITION_ID, "expense-attachment-42",
                Map.of("reason", "采购设备", "amount", 1280,
                        "files", List.of(ATTACHMENT_ID))));

        assertThat(result.id()).isEqualTo("instance-attachment-42");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> variables = ArgumentCaptor.forClass(Map.class);
        verify(runtimeService).startProcessInstanceById(
                eq(DEFINITION_ID), eq("expense-attachment-42"), variables.capture());
        assertThat(variables.getValue().get("files")).isSameAs(safeAttachments);
        assertThat(variables.getValue().toString())
                .doesNotContain("storageKey", "ownerUserId", "workflow-attachments", "url");
        verify(attachmentService).bindStartAttachments(
                "7", "instance-attachment-42", "start",
                Map.of("files", List.of(ATTACHMENT_ID)));

        InOrder lifecycle = inOrder(attachmentService, runtimeService);
        lifecycle.verify(attachmentService).prepareStartVariables(
                eq("7"), anyMap(), eq(Map.of("files", List.of(ATTACHMENT_ID))));
        lifecycle.verify(runtimeService).startProcessInstanceById(
                eq(DEFINITION_ID), eq("expense-attachment-42"), anyMap());
        lifecycle.verify(attachmentService).bindStartAttachments(
                "7", "instance-attachment-42", "start",
                Map.of("files", List.of(ATTACHMENT_ID)));
    }

    /**
     * 验证 starter 用户或候选组不匹配时沿用查询门禁的 403，且绝不调用引擎发起。
     *
     * @return void，越权请求产生实例时测试失败
     */
    @Test
    void rejectsUserWithoutStarterIdentityLink()
    {
        stubSelectedDefinition();
        when(processQueryService.getProcessForm(any())).thenThrow(
                new ServiceException("当前用户无权发起该流程", HttpStatus.FORBIDDEN));

        assertBusinessError(() -> service.start(validRequest()), HttpStatus.FORBIDDEN,
                "当前用户无权发起该流程");

        verify(repositoryService, never()).createProcessDefinitionQuery();
        verify(runtimeService, never()).startProcessInstanceById(any(), any(), anyMap());
    }

    /**
     * 验证快照校验后定义被挂起时，写入前 active 二次查询返回 409 并阻止发起。
     *
     * @return void，并发挂起仍能产生实例时测试失败
     */
    @Test
    void rejectsDefinitionSuspendedBeforeEngineWrite()
    {
        ProcessDefinition selected = definition(false);
        ProcessDefinition suspended = definition(true);
        when(repositoryService.getProcessDefinition(DEFINITION_ID))
                .thenReturn(selected, suspended);
        stubStartForm();
        stubActiveQuery(null);

        assertBusinessError(() -> service.start(validRequest()), HttpStatus.CONFLICT,
                "流程定义已挂起");

        verify(runtimeService, never()).startProcessInstanceById(any(), any(), anyMap());
    }

    /**
     * 验证定义在首次服务端查询时不存在会稳定返回 404，且不读取表单或写入引擎。
     *
     * @return void，不存在定义未被拦截时测试失败
     */
    @Test
    void rejectsMissingDefinition()
    {
        when(repositoryService.getProcessDefinition(DEFINITION_ID)).thenReturn(null);

        assertBusinessError(() -> service.start(validRequest()), HttpStatus.NOT_FOUND,
                "流程定义不存在或已被删除");

        verify(processQueryService, never()).getProcessForm(any());
        verify(runtimeService, never()).startProcessInstanceById(any(), any(), anyMap());
    }

    /**
     * 验证非法或保留变量在 active 二次查询和 RuntimeService 写入前被拒绝。
     *
     * @return void，客户端能覆盖发起人或状态时测试失败
     */
    @Test
    void rejectsInvalidAndReservedVariablesBeforeEngineWrite()
    {
        stubSelectedDefinition();
        stubStartForm();
        StartProcessRequest request = new StartProcessRequest(DEFINITION_ID, null,
                Map.of("reason", "采购设备", "amount", 1280,
                        WorkflowProcessStartService.INITIATOR_VARIABLE, "999"));

        assertBusinessError(() -> service.start(request), HttpStatus.BAD_REQUEST,
                "客户端不能覆盖服务端保留流程变量");

        verify(repositoryService, never()).createProcessDefinitionQuery();
        verify(runtimeService, never()).startProcessInstanceById(any(), any(), anyMap());
    }

    /**
     * 验证真实写事务内的引擎异常会在认证清理后回滚，绝不提交半完成实例。
     *
     * @return void，异常事务被提交或身份未清理时测试失败
     */
    @Test
    void rollsBackSingleTransactionWhenRuntimeStartFails()
    {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(transactionStatus);
        WorkflowEngineOperations transactionalOperations = transactionalProxy(
                engineOperations(identityService, identityResolver), transactionManager);
        WorkflowProcessStartService transactionalService = service(transactionalOperations);

        stubSelectedAndActiveDefinition();
        stubStartForm();
        when(runtimeService.startProcessInstanceById(eq(DEFINITION_ID), eq("expense-42"), anyMap()))
                .thenThrow(new FlowableException("forced runtime failure"));

        assertBusinessError(() -> transactionalService.start(validRequest()), HttpStatus.ERROR,
                "工作流引擎执行失败");

        InOrder calls = inOrder(transactionManager, identityService, runtimeService);
        calls.verify(transactionManager).getTransaction(any(TransactionDefinition.class));
        calls.verify(identityService).setAuthenticatedUserId("7");
        calls.verify(runtimeService).startProcessInstanceById(
                eq(DEFINITION_ID), eq("expense-42"), anyMap());
        calls.verify(identityService).setAuthenticatedUserId(null);
        calls.verify(transactionManager).rollback(transactionStatus);
        verify(transactionManager, never()).commit(any());
    }

    /**
     * 验证实例已创建后附件绑定失败仍由同一写事务整体回滚。
     * @return void，绑定异常被提交或未使用真实实例主键时测试失败
     */
    @Test
    void rollsBackSingleTransactionWhenAttachmentBindingFails()
    {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(transactionStatus);
        WorkflowEngineOperations transactionalOperations = transactionalProxy(
                engineOperations(identityService, identityResolver), transactionManager);
        WorkflowProcessStartService transactionalService = service(transactionalOperations);

        stubSelectedAndActiveDefinition();
        stubStartForm();
        ProcessInstance processInstance = mock(ProcessInstance.class);
        when(processInstance.getId()).thenReturn("instance-binding-rollback");
        when(processInstance.getProcessDefinitionId()).thenReturn(DEFINITION_ID);
        when(processInstance.isSuspended()).thenReturn(false);
        when(runtimeService.startProcessInstanceById(
                eq(DEFINITION_ID), eq("expense-attachment-42"), anyMap()))
                .thenReturn(processInstance);
        Map<String, List<String>> references = Map.of(
                "files", List.of(ATTACHMENT_ID));
        doThrow(new ServiceException("工作流附件状态已变化或已过期",
                HttpStatus.CONFLICT))
                .when(attachmentService).bindStartAttachments(
                        "7", "instance-binding-rollback", "start", references);

        StartProcessRequest request = new StartProcessRequest(
                DEFINITION_ID, "expense-attachment-42",
                Map.of("reason", "采购设备", "amount", 1280,
                        "files", List.of(ATTACHMENT_ID)));
        assertBusinessError(() -> transactionalService.start(request),
                HttpStatus.CONFLICT, "工作流附件状态已变化或已过期");

        InOrder calls = inOrder(transactionManager, runtimeService,
                attachmentService, identityService);
        calls.verify(transactionManager).getTransaction(any(TransactionDefinition.class));
        calls.verify(identityService).setAuthenticatedUserId("7");
        calls.verify(runtimeService).startProcessInstanceById(
                eq(DEFINITION_ID), eq("expense-attachment-42"), anyMap());
        calls.verify(attachmentService).bindStartAttachments(
                "7", "instance-binding-rollback", "start", references);
        calls.verify(identityService).setAuthenticatedUserId(null);
        calls.verify(transactionManager).rollback(transactionStatus);
        verify(transactionManager, never()).commit(any());
    }

    /**
     * 验证 definitionId 与 definitionKey 两条入口使用同一变量、身份、快照和附件绑定链。
     *
     * @return void，两条入口产生不同引擎变量或 key 入口绕过标准链时测试失败
     */
    @Test
    void startsLatestDefaultTenantDefinitionByKeyWithSameStandardPipeline()
    {
        ProcessDefinition definition = stubSelectedDefinition();
        stubStartForm();

        ProcessDefinitionQuery idActiveQuery = activeQuery(definition);
        ProcessDefinitionQuery initialKeyQuery = keyQuery(definition);
        ProcessDefinitionQuery keyActiveQuery = activeQuery(definition);
        when(repositoryService.createProcessDefinitionQuery()).thenReturn(
                idActiveQuery, initialKeyQuery, keyActiveQuery);
        when(definitionLockMapper.selectLatestDefaultTenantDefinitionForUpdate(PROCESS_KEY))
                .thenReturn(definitionLockRow(DEFINITION_ID, DEPLOYMENT_ID, 1));

        ProcessInstance idInstance = processInstance("instance-by-id");
        ProcessInstance keyInstance = processInstance("instance-by-key");
        when(runtimeService.startProcessInstanceById(
                eq(DEFINITION_ID), eq("expense-compat-42"), anyMap()))
                .thenReturn(idInstance, keyInstance);

        WorkflowProcessInstanceSnapshot idResult = service.start(new StartProcessRequest(
                DEFINITION_ID, "expense-compat-42",
                Map.of("reason", "采购设备", "amount", 1280)));
        service.startProcessByDefKey("  " + PROCESS_KEY + "  ",
                "  expense-compat-42  ",
                Map.of("reason", "采购设备", "amount", 1280));

        assertThat(idResult.id()).isEqualTo("instance-by-id");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> variables = ArgumentCaptor.forClass(Map.class);
        verify(runtimeService, times(2)).startProcessInstanceById(
                eq(DEFINITION_ID), eq("expense-compat-42"), variables.capture());
        assertThat(variables.getAllValues()).hasSize(2);
        assertThat(variables.getAllValues().get(1))
                .containsExactlyInAnyOrderEntriesOf(variables.getAllValues().get(0));
        verify(attachmentService).bindStartAttachments(
                "7", "instance-by-id", "start", Map.of());
        verify(attachmentService).bindStartAttachments(
                "7", "instance-by-key", "start", Map.of());
        verify(initialKeyQuery).processDefinitionWithoutTenantId();
        InOrder keyWriteOrder = inOrder(definitionLockMapper, runtimeService);
        keyWriteOrder.verify(definitionLockMapper)
                .selectLatestDefaultTenantDefinitionForUpdate(PROCESS_KEY);
        keyWriteOrder.verify(runtimeService).startProcessInstanceById(
                eq(DEFINITION_ID), eq("expense-compat-42"), anyMap());
    }

    /**
     * 验证仓库外兼容方法保持精确 void 签名并使用统一可重复读写隔离级别。
     *
     * @return void，方法返回类型或事务隔离级别漂移时测试失败
     * @throws Exception 反射无法定位兼容方法时测试失败
     */
    @Test
    void preservesExactVoidCompatibilitySignatureAndIsolation() throws Exception
    {
        var method = WorkflowProcessStartService.class.getMethod(
                "startProcessByDefKey", String.class, Map.class);
        Transactional transactional = method.getAnnotation(Transactional.class);

        assertThat(method.getReturnType()).isEqualTo(void.class);
        assertThat(transactional).isNotNull();
        assertThat(transactional.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
        assertThat(transactional.rollbackFor()).containsExactly(Exception.class);
    }

    /**
     * 验证默认租户下不存在指定 key 时稳定返回 404 且不进入标准定义或引擎写链。
     *
     * @return void，无定义被误报或产生副作用时测试失败
     */
    @Test
    void rejectsMissingDefaultTenantDefinitionByKey()
    {
        ProcessDefinitionQuery missingQuery = keyQuery(null);
        when(repositoryService.createProcessDefinitionQuery()).thenReturn(missingQuery);

        assertBusinessError(() -> service.startProcessByDefKey(
                PROCESS_KEY, Map.of("reason", "采购设备", "amount", 1280)),
                HttpStatus.NOT_FOUND, "流程定义不存在或已被删除");

        verify(repositoryService, never()).getProcessDefinition(anyString());
        verify(processQueryService, never()).getProcessForm(any());
        verify(runtimeService, never()).startProcessInstanceById(any(), any(), anyMap());
    }

    /**
     * 验证默认租户最新版已挂起时稳定返回 409，且不会回退到旧激活版本。
     *
     * @return void，挂起定义仍能发起或错误语义不稳定时测试失败
     */
    @Test
    void rejectsSuspendedLatestDefaultTenantDefinitionByKey()
    {
        ProcessDefinition suspended = definition(DEFINITION_ID, true);
        ProcessDefinitionQuery suspendedQuery = keyQuery(suspended);
        when(repositoryService.createProcessDefinitionQuery()).thenReturn(suspendedQuery);

        assertBusinessError(() -> service.startProcessByDefKey(
                PROCESS_KEY, Map.of("reason", "采购设备", "amount", 1280)),
                HttpStatus.CONFLICT, "流程定义已挂起");

        verify(repositoryService, never()).getProcessDefinition(anyString());
        verify(runtimeService, never()).startProcessInstanceById(any(), any(), anyMap());
    }

    /**
     * 验证变量校验后出现并发部署时 key 入口二次解析最新版并以 409 整体拒绝旧版本发起。
     *
     * @return void，并发部署期间旧定义产生实例或附件绑定时测试失败
     */
    @Test
    void rejectsConcurrentLatestDeploymentChangeByKey()
    {
        ProcessDefinition selected = stubSelectedDefinition();
        ProcessDefinition newer = definition("expense:4:13001", false);
        stubStartForm();
        ProcessDefinitionQuery initialKeyQuery = keyQuery(selected);
        ProcessDefinitionQuery activeQuery = activeQuery(selected);
        when(repositoryService.createProcessDefinitionQuery()).thenReturn(
                initialKeyQuery, activeQuery);
        String newerDefinitionId = newer.getId();
        when(definitionLockMapper.selectLatestDefaultTenantDefinitionForUpdate(PROCESS_KEY))
                .thenReturn(definitionLockRow(newerDefinitionId, DEPLOYMENT_ID, 1));

        assertBusinessError(() -> service.startProcessByDefKey(
                PROCESS_KEY, Map.of("reason", "采购设备", "amount", 1280)),
                HttpStatus.CONFLICT, "流程定义最新版已发生变化");

        verify(runtimeService, never()).startProcessInstanceById(any(), any(), anyMap());
        verify(attachmentService, never()).bindStartAttachments(
                anyString(), anyString(), anyString(), anyMap());
    }

    /**
     * 创建使用当前测试依赖和真实变量验证器的发起服务。
     *
     * @param operations WorkflowEngineOperations，普通或事务代理执行边界
     * @return WorkflowProcessStartService，待测发起服务
     */
    private WorkflowProcessStartService service(WorkflowEngineOperations operations)
    {
        WorkflowStartVariableValidator variableValidator = new WorkflowStartVariableValidator(
                new WorkflowFormTemplateValidator());
        WorkflowProcessStartService created = new WorkflowProcessStartService(
                operations, repositoryService, runtimeService,
                processQueryService, variableValidator, attachmentService,
                definitionLockMapper, new WorkflowUserSelectionValidator(identityResolver));
        created.setParticipantRuleRuntimeService(participantRuleRuntimeService);
        return created;
    }

    /**
     * 创建仅含一个发起时成员来源会签节点的可执行模型。
     *
     * @param activityId String，受控多实例用户任务节点标识。
     * @return BpmnModel，满足启动成员投影和变量生成契约的流程模型。
     */
    private BpmnModel startMultiInstanceModel(String activityId)
    {
        BpmnModel model = new BpmnModel();
        org.flowable.bpmn.model.Process process = new org.flowable.bpmn.model.Process();
        process.setId(PROCESS_KEY);
        process.setExecutable(true);
        UserTask task = new UserTask();
        task.setId(activityId);
        task.setName("审批会签");
        task.setAssignee(WorkflowMultiInstanceModelContract.ASSIGNEE_EXPRESSION);
        MultiInstanceLoopCharacteristics loop = new MultiInstanceLoopCharacteristics();
        loop.setInputDataItem(WorkflowMultiInstanceModelContract.START_COLLECTION_EXPRESSION);
        loop.setElementVariable(WorkflowMultiInstanceModelContract.ELEMENT_VARIABLE);
        loop.setCompletionCondition(
                WorkflowMultiInstanceModelContract.ALL_COMPLETION_CONDITION);
        task.setLoopCharacteristics(loop);
        process.addFlowElement(task);
        model.addProcess(process);
        return model;
    }

    /**
     * 创建真实认证上下文和异常翻译器组成的引擎执行边界。
     *
     * @param flowableIdentityService IdentityService，记录认证身份的 Flowable 服务替身
     * @param resolver WorkflowIdentityResolver，返回当前正式身份的解析器替身
     * @return WorkflowEngineOperations，可选择直接调用或安装事务代理的执行器
     */
    private WorkflowEngineOperations engineOperations(IdentityService flowableIdentityService,
            WorkflowIdentityResolver resolver)
    {
        WorkflowAuthenticationContext authenticationContext = new WorkflowAuthenticationContext(
                flowableIdentityService, new WorkflowIdentityCodec());
        return new WorkflowEngineOperations(authenticationContext,
                new WorkflowExceptionTranslator(), resolver);
    }

    /**
     * 为 WorkflowEngineOperations 安装声明式事务拦截器以验证真实回滚语义。
     *
     * @param target WorkflowEngineOperations，带 @Transactional 的执行器目标
     * @param transactionManager PlatformTransactionManager，记录事务生命周期的替身
     * @return WorkflowEngineOperations，应用 Spring 事务拦截器后的代理
     */
    private WorkflowEngineOperations transactionalProxy(WorkflowEngineOperations target,
            PlatformTransactionManager transactionManager)
    {
        TransactionInterceptor interceptor = new TransactionInterceptor(
                (TransactionManager) transactionManager,
                new AnnotationTransactionAttributeSource());
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.addAdvice(interceptor);
        return (WorkflowEngineOperations) proxyFactory.getProxy();
    }

    /**
     * 配置首次定义查询和写入前 active 二次查询均返回同一有效定义。
     *
     * @return ProcessDefinition，当前有效定义替身
     */
    private ProcessDefinition stubSelectedAndActiveDefinition()
    {
        ProcessDefinition definition = stubSelectedDefinition();
        stubActiveQuery(definition);
        return definition;
    }

    /**
     * 配置草稿提交所需的固定定义、最新版锁、部署快照和附件安全投影。
     *
     * @return ProcessDefinition，定义查询和 active 复核共用的有效定义替身
     */
    private ProcessDefinition stubDraftDefinition()
    {
        ProcessDefinition definition = stubSelectedAndActiveDefinition();
        when(definition.getVersion()).thenReturn(3);
        when(definitionLockMapper.selectLatestDefaultTenantDefinitionForUpdate(PROCESS_KEY))
                .thenReturn(definitionLockRow(DEFINITION_ID, DEPLOYMENT_ID, 1));
        stubStartForm();
        when(attachmentService.prepareDraftStartVariables(
                eq("7"), eq("draft-42"), anyMap(), anyMap()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        return definition;
    }

    /**
     * 创建与当前部署表单快照完全一致的本人活动草稿。
     *
     * @return WfProcessDraft，可进入正式提交链的持久化草稿快照
     */
    private WfProcessDraft activeDraft()
    {
        LocalDateTime now = LocalDateTime.of(2026, 8, 9, 10, 0);
        return new WfProcessDraft("draft-42", 7L, DEFINITION_ID, PROCESS_KEY, 3,
                DEPLOYMENT_ID, "报销流程", "TEMPLATE", 8L, "key_8", "start",
                "报销申请", "发起申请", now, START_FORM,
                WorkflowProcessDraftChecksum.sha256(START_FORM), "[]", "{}", "{}",
                "expense-draft-42", WorkflowProcessDraftStatus.ACTIVE, 1L,
                null, null, null, now, now);
    }

    /**
     * 配置首次定义查询返回有效定义和真实 deploymentId。
     *
     * @return ProcessDefinition，客户端选择的定义替身
     */
    private ProcessDefinition stubSelectedDefinition()
    {
        ProcessDefinition definition = definition(false);
        when(repositoryService.getProcessDefinition(DEFINITION_ID)).thenReturn(definition);
        return definition;
    }

    /**
     * 创建指定挂起状态且关联固定部署的定义替身。
     *
     * @param suspended boolean，定义是否挂起
     * @return ProcessDefinition，流程定义替身
     */
    private ProcessDefinition definition(boolean suspended)
    {
        return definition(DEFINITION_ID, suspended);
    }

    /**
     * 创建指定主键、固定 key、默认租户和挂起状态的流程定义替身。
     *
     * @param definitionId String，流程定义主键
     * @param suspended boolean，定义是否挂起
     * @return ProcessDefinition，流程定义替身
     */
    private ProcessDefinition definition(String definitionId, boolean suspended)
    {
        ProcessDefinition definition = mock(ProcessDefinition.class);
        when(definition.getId()).thenReturn(definitionId);
        when(definition.getKey()).thenReturn(PROCESS_KEY);
        when(definition.getTenantId()).thenReturn("");
        when(definition.getDeploymentId()).thenReturn(DEPLOYMENT_ID);
        when(definition.isSuspended()).thenReturn(suspended);
        return definition;
    }

    /**
     * 配置写入前按 ID 和 active 的流程定义查询。
     *
     * @param result ProcessDefinition，查询结果；允许为空以模拟挂起或删除竞争
     * @return void，无返回值
     */
    private void stubActiveQuery(ProcessDefinition result)
    {
        ProcessDefinitionQuery query = activeQuery(result);
        when(repositoryService.createProcessDefinitionQuery()).thenReturn(query);
    }

    /**
     * 创建写入前按定义主键和 active 状态查询的链式替身。
     *
     * @param result ProcessDefinition，查询返回定义；允许为空
     * @return ProcessDefinitionQuery，已经配置 active 链和单条结果的查询替身
     */
    private ProcessDefinitionQuery activeQuery(ProcessDefinition result)
    {
        ProcessDefinitionQuery query = mock(ProcessDefinitionQuery.class);
        when(query.processDefinitionId(DEFINITION_ID)).thenReturn(query);
        when(query.active()).thenReturn(query);
        when(query.singleResult()).thenReturn(result);
        return query;
    }

    /**
     * 创建按 key、默认租户和 latestVersion 解析定义的链式替身。
     *
     * @param result ProcessDefinition，最新定义；允许为空以模拟未部署
     * @return ProcessDefinitionQuery，已经配置 key 解析链和单条结果的查询替身
     */
    private ProcessDefinitionQuery keyQuery(ProcessDefinition result)
    {
        ProcessDefinitionQuery query = mock(ProcessDefinitionQuery.class);
        when(query.processDefinitionKey(PROCESS_KEY)).thenReturn(query);
        when(query.processDefinitionWithoutTenantId()).thenReturn(query);
        when(query.latestVersion()).thenReturn(query);
        when(query.singleResult()).thenReturn(result);
        return query;
    }

    /**
     * 创建最终定义锁定读的最小数据库投影。
     *
     * @param definitionId String，当前读看见的最新定义主键
     * @param deploymentId String，当前读看见的部署主键
     * @param suspensionState int，Flowable 定义状态；1 为激活，2 为挂起
     * @return WorkflowProcessDefinitionLockRow，供最终版本和状态复核使用的投影
     */
    private WorkflowProcessDefinitionLockRow definitionLockRow(String definitionId,
            String deploymentId, int suspensionState)
    {
        return new WorkflowProcessDefinitionLockRow(
                definitionId, deploymentId, suspensionState);
    }

    /**
     * 创建固定定义且未挂起的真实发起结果替身。
     *
     * @param instanceId String，新流程实例主键
     * @return ProcessInstance，RuntimeService 发起结果替身
     */
    private ProcessInstance processInstance(String instanceId)
    {
        ProcessInstance processInstance = mock(ProcessInstance.class);
        when(processInstance.getId()).thenReturn(instanceId);
        when(processInstance.getProcessDefinitionId()).thenReturn(DEFINITION_ID);
        when(processInstance.isSuspended()).thenReturn(false);
        return processInstance;
    }

    /**
     * 配置查询服务返回当前定义开始节点的不可变部署快照。
     *
     * @return void，无返回值
     */
    private void stubStartForm()
    {
        when(processQueryService.getProcessForm(any())).thenReturn(
                new WorkflowProcessFormView(DEFINITION_ID, DEPLOYMENT_ID, null,
                        8L, "key_8", "start", "报销申请", "发起申请",
                        START_FORM, null));
    }

    /**
     * 创建字段、业务主键均合法的标准发起请求。
     *
     * @return StartProcessRequest，供状态和事务测试复用的请求
     */
    private StartProcessRequest validRequest()
    {
        return new StartProcessRequest(DEFINITION_ID, "expense-42",
                Map.of("reason", "采购设备", "amount", 1280));
    }

    /**
     * 断言发起失败同时满足稳定 HTTP 状态和错误提示。
     *
     * @param action ThrowingCallable，预计失败的发起操作
     * @param expectedCode int，预期 HTTP 状态码
     * @param expectedMessage String，预期错误提示
     * @return void，异常契约不符时测试失败
     */
    private void assertBusinessError(ThrowingCallable action, int expectedCode,
            String expectedMessage)
    {
        assertThatThrownBy(action).isInstanceOfSatisfying(ServiceException.class, exception ->
        {
            assertThat(exception.getCode()).isEqualTo(expectedCode);
            assertThat(exception.getMessage()).isEqualTo(expectedMessage);
        });
    }
}
