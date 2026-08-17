package com.ruoyi.flowable.service.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.ProcessDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfProcessDraft;
import com.ruoyi.flowable.domain.WorkflowProcessDraftStatus;
import com.ruoyi.flowable.domain.dto.WorkflowProcessDraftCreateRequest;
import com.ruoyi.flowable.domain.dto.WorkflowProcessDraftSubmitRequest;
import com.ruoyi.flowable.domain.vo.WorkflowProcessDraftView;
import com.ruoyi.flowable.domain.vo.WorkflowProcessFormView;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.engine.WorkflowProcessInstanceSnapshot;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.mapper.WfProcessDraftMapper;
import com.ruoyi.flowable.mapper.WorkflowProcessDefinitionLockMapper;
import com.ruoyi.flowable.service.attachment.WorkflowAttachmentService;

class WorkflowProcessDraftServiceTest
{
    private static final String DEFINITION_ID = "expense:3:12001";
    private static final String DEPLOYMENT_ID = "deployment-9";
    private static final String DRAFT_ID = "d9428888-122b-4c6f-8f0c-9c3e1dbd3210";
    private static final String FORM_JSON = "{\"fields\":[]}";

    private WorkflowEngineOperations engineOperations;
    private RepositoryService repositoryService;
    private WorkflowProcessQueryService processQueryService;
    private WorkflowProcessStartService processStartService;
    private WorkflowStartVariableValidator variableValidator;
    private WorkflowAttachmentService attachmentService;
    private WfProcessDraftMapper draftMapper;
    private WorkflowProcessDefinitionLockMapper processDefinitionLockMapper;
    private WorkflowProcessDraftService service;

    /**
     * 创建草稿服务依赖替身，并让统一写边界以内联方式执行当前用户回调。
     *
     * @return void，初始化完成后每个测试拥有隔离的正式 Mapper 和 Flowable API 替身
     */
    @BeforeEach
    @SuppressWarnings({ "rawtypes", "unchecked" })
    void setUp()
    {
        engineOperations = mock(WorkflowEngineOperations.class);
        repositoryService = mock(RepositoryService.class);
        processQueryService = mock(WorkflowProcessQueryService.class);
        processStartService = mock(WorkflowProcessStartService.class);
        variableValidator = mock(WorkflowStartVariableValidator.class);
        attachmentService = mock(WorkflowAttachmentService.class);
        draftMapper = mock(WfProcessDraftMapper.class);
        processDefinitionLockMapper = mock(WorkflowProcessDefinitionLockMapper.class);
        WorkflowIdentityResolver identityResolver = mock(WorkflowIdentityResolver.class);
        when(engineOperations.writeAsCurrentUser(any(Function.class))).thenAnswer(invocation ->
        {
            Function<WorkflowCurrentIdentity, Object> action = invocation.getArgument(0);
            return action.apply(new WorkflowCurrentIdentity("7", Set.of("ROLE2")));
        });

        service = new WorkflowProcessDraftService(engineOperations, identityResolver,
                repositoryService, processQueryService, processStartService,
                variableValidator, attachmentService, draftMapper,
                processDefinitionLockMapper);
        stubDefinition();
    }

    /**
     * 验证草稿创建先锁部署行，再读取部署表单并写入正式草稿，锁与删除服务使用同一主键。
     *
     * @return void，草稿可在未持部署锁时读取快照或写入时测试失败
     */
    @Test
    void locksDeploymentBeforeReadingSnapshotAndInsertingActiveDraft()
    {
        WorkflowProcessFormView form = processForm();
        when(processDefinitionLockMapper.selectDeploymentIdForUpdate(DEPLOYMENT_ID))
                .thenReturn(DEPLOYMENT_ID);
        when(processQueryService.getProcessForm(any())).thenReturn(form);
        when(variableValidator.validateForDraft(FORM_JSON, Map.of()))
                .thenReturn(new WorkflowValidatedStartVariables(Map.of(), Map.of()));
        AtomicReference<WfProcessDraft> persisted = new AtomicReference<>();
        when(draftMapper.insert(any(WfProcessDraft.class))).thenAnswer(invocation ->
        {
            persisted.set(invocation.getArgument(0));
            return 1;
        });
        when(draftMapper.selectOwnedById(anyString(), eq(7L)))
                .thenAnswer(invocation -> persisted.get());

        WorkflowProcessDraftView result = service.create(validRequest());

        assertThat(result.deploymentId()).isEqualTo(DEPLOYMENT_ID);
        assertThat(result.status()).isEqualTo("ACTIVE");
        InOrder writeOrder = inOrder(processDefinitionLockMapper, processQueryService,
                draftMapper);
        writeOrder.verify(processDefinitionLockMapper)
                .selectDeploymentIdForUpdate(DEPLOYMENT_ID);
        writeOrder.verify(processQueryService).getProcessForm(any());
        writeOrder.verify(draftMapper).insert(any(WfProcessDraft.class));
        verify(attachmentService).reconcileDraftAttachments(
                eq("7"), anyString(), eq(Map.of()));
    }

    /**
     * 验证定义查询后部署被并发删除时稳定返回 409，且不读取表单或写入草稿和附件。
     *
     * @return void，无法取得共享部署锁时仍产生任一草稿副作用则测试失败
     */
    @Test
    void rejectsCreateWhenDeploymentDisappearsBeforeLifecycleLock()
    {
        when(processDefinitionLockMapper.selectDeploymentIdForUpdate(DEPLOYMENT_ID))
                .thenReturn(null);

        assertThatThrownBy(() -> service.create(validRequest()))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getSubCode()).isEqualTo("DRAFT_DEFINITION_UNAVAILABLE");
                    assertThat(exception.getMessage())
                            .isEqualTo("流程定义部署状态已变化，请刷新后重试");
                });

        verify(processDefinitionLockMapper).selectDeploymentIdForUpdate(DEPLOYMENT_ID);
        verifyNoInteractions(processQueryService, variableValidator, draftMapper,
                attachmentService);
    }

    /**
     * 验证活动草稿严格按普通定位读、部署锁、草稿锁和实例创建的稳定顺序正式提交。
     *
     * @return void，ACTIVE 提交以 draft -> deployment 取锁或提前创建实例时测试失败
     */
    @Test
    void submitsActiveDraftWithDeploymentBeforeDraftLockOrder()
    {
        WfProcessDraft active = draft(WorkflowProcessDraftStatus.ACTIVE,
                DEPLOYMENT_ID, null, 1L);
        when(draftMapper.selectOwnedById(DRAFT_ID, 7L)).thenReturn(active);
        when(processDefinitionLockMapper.selectDeploymentIdForUpdate(DEPLOYMENT_ID))
                .thenReturn(DEPLOYMENT_ID);
        when(draftMapper.selectOwnedByIdForUpdate(DRAFT_ID, 7L)).thenReturn(active);
        WorkflowProcessInstanceSnapshot instance = new WorkflowProcessInstanceSnapshot(
                "instance-42", DEFINITION_ID, "expense-submit-42", false);
        when(processStartService.startDraft(active, "expense-submit-42", Map.of(), Map.of()))
                .thenReturn(instance);
        when(variableValidator.validateForStart(FORM_JSON, Map.of()))
                .thenReturn(new WorkflowValidatedStartVariables(Map.of(), Map.of()));
        when(draftMapper.markSubmitted(eq(DRAFT_ID), eq(7L), eq(1L),
                eq("instance-42"), anyString(), anyString(), eq("expense-submit-42")))
                .thenReturn(1);

        var result = service.submit(DRAFT_ID, submitRequest());

        assertThat(result.draftId()).isEqualTo(DRAFT_ID);
        assertThat(result.processInstanceId()).isEqualTo("instance-42");
        assertThat(result.revisionNo()).isEqualTo(2L);
        InOrder submitOrder = inOrder(draftMapper, processDefinitionLockMapper,
                processStartService);
        submitOrder.verify(draftMapper).selectOwnedById(DRAFT_ID, 7L);
        submitOrder.verify(processDefinitionLockMapper)
                .selectDeploymentIdForUpdate(DEPLOYMENT_ID);
        submitOrder.verify(draftMapper).selectOwnedByIdForUpdate(DRAFT_ID, 7L);
        submitOrder.verify(processStartService).startDraft(
                active, "expense-submit-42", Map.of(), Map.of());
        submitOrder.verify(draftMapper).markSubmitted(eq(DRAFT_ID), eq(7L), eq(1L),
                eq("instance-42"), anyString(), anyString(), eq("expense-submit-42"));
    }

    /**
     * 验证部署锁前后的草稿定义关系发生漂移时拒绝提交，且不产生实例或状态副作用。
     *
     * @return void，损坏的定义部署关系仍进入正式实例创建时测试失败
     */
    @Test
    void rejectsDraftRelationChangeAfterDeploymentLock()
    {
        WfProcessDraft located = draft(WorkflowProcessDraftStatus.ACTIVE,
                DEPLOYMENT_ID, null, 1L);
        WfProcessDraft changed = draft(WorkflowProcessDraftStatus.ACTIVE,
                "deployment-changed", null, 1L);
        when(draftMapper.selectOwnedById(DRAFT_ID, 7L)).thenReturn(located);
        when(processDefinitionLockMapper.selectDeploymentIdForUpdate(DEPLOYMENT_ID))
                .thenReturn(DEPLOYMENT_ID);
        when(draftMapper.selectOwnedByIdForUpdate(DRAFT_ID, 7L)).thenReturn(changed);

        assertThatThrownBy(() -> service.submit(DRAFT_ID, submitRequest()))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.ERROR);
                    assertThat(exception.getMessage())
                            .isEqualTo("流程申请草稿定义关系已损坏");
                });

        InOrder lockOrder = inOrder(draftMapper, processDefinitionLockMapper);
        lockOrder.verify(draftMapper).selectOwnedById(DRAFT_ID, 7L);
        lockOrder.verify(processDefinitionLockMapper)
                .selectDeploymentIdForUpdate(DEPLOYMENT_ID);
        lockOrder.verify(draftMapper).selectOwnedByIdForUpdate(DRAFT_ID, 7L);
        verifyNoInteractions(processStartService, attachmentService);
        verify(draftMapper, never()).markSubmitted(eq(DRAFT_ID), eq(7L), eq(1L),
                anyString(), anyString(), anyString(), anyString());
    }

    /**
     * 验证已提交草稿重复请求只返回持久化实例，不再依赖可能已删除的 Flowable 部署。
     *
     * @return void，SUBMITTED 重试再次取锁、创建实例或推进状态时测试失败
     */
    @Test
    void returnsSubmittedDraftIdempotentlyWithoutDeploymentLock()
    {
        WfProcessDraft submitted = draft(WorkflowProcessDraftStatus.SUBMITTED,
                DEPLOYMENT_ID, "instance-42", 2L);
        when(draftMapper.selectOwnedById(DRAFT_ID, 7L)).thenReturn(submitted);

        var result = service.submit(DRAFT_ID, submitRequest());

        assertThat(result.draftId()).isEqualTo(DRAFT_ID);
        assertThat(result.processInstanceId()).isEqualTo("instance-42");
        assertThat(result.processDefinitionId()).isEqualTo(DEFINITION_ID);
        assertThat(result.revisionNo()).isEqualTo(2L);
        verify(draftMapper).selectOwnedById(DRAFT_ID, 7L);
        verify(draftMapper, never()).selectOwnedByIdForUpdate(anyString(), eq(7L));
        verifyNoInteractions(processDefinitionLockMapper, processStartService,
                variableValidator, attachmentService);
    }

    /**
     * 配置可执行流程定义及其不可变部署关系。
     *
     * @return ProcessDefinition，已经注册到 RepositoryService 的定义替身
     */
    private ProcessDefinition stubDefinition()
    {
        ProcessDefinition definition = mock(ProcessDefinition.class);
        when(definition.getId()).thenReturn(DEFINITION_ID);
        when(definition.getKey()).thenReturn("expense");
        when(definition.getName()).thenReturn("费用报销");
        when(definition.getVersion()).thenReturn(3);
        when(definition.getDeploymentId()).thenReturn(DEPLOYMENT_ID);
        when(repositoryService.getProcessDefinition(DEFINITION_ID)).thenReturn(definition);
        return definition;
    }

    /**
     * 构造草稿创建采用的部署表单不可变快照。
     *
     * @return WorkflowProcessFormView，字段和发起成员均为空的合法模板快照
     */
    private WorkflowProcessFormView processForm()
    {
        return new WorkflowProcessFormView(DEFINITION_ID, DEPLOYMENT_ID, null,
                "TEMPLATE", 8L, "form_8", "start", "报销申请", "发起申请",
                FORM_JSON, Instant.parse("2026-08-09T04:00:00Z"), List.of());
    }

    /**
     * 构造字段、业务键和发起成员均合法的草稿创建请求。
     *
     * @return WorkflowProcessDraftCreateRequest，可进入部署锁和正式持久化链的请求
     */
    private WorkflowProcessDraftCreateRequest validRequest()
    {
        return new WorkflowProcessDraftCreateRequest(
                DEFINITION_ID, "expense-draft-42", Map.of(), Map.of());
    }

    /**
     * 构造正式提交使用的固定版本、业务键和空表单字段请求。
     *
     * @return WorkflowProcessDraftSubmitRequest，可进入 ACTIVE 或 SUBMITTED 提交分支
     */
    private WorkflowProcessDraftSubmitRequest submitRequest()
    {
        return new WorkflowProcessDraftSubmitRequest(
                1L, "expense-submit-42", Map.of(), Map.of());
    }

    /**
     * 构造指定生命周期和部署关系的本人持久化草稿。
     *
     * @param status WorkflowProcessDraftStatus，草稿生命周期状态
     * @param deploymentId String，草稿绑定的 Flowable 部署主键
     * @param submittedInstanceId String，SUBMITTED 草稿的真实实例主键；ACTIVE 时为空
     * @param revisionNo long，持久化乐观锁版本
     * @return WfProcessDraft，可直接进入提交服务的正式数据投影
     */
    private WfProcessDraft draft(WorkflowProcessDraftStatus status,
            String deploymentId, String submittedInstanceId, long revisionNo)
    {
        LocalDateTime now = LocalDateTime.of(2026, 8, 9, 12, 0);
        return new WfProcessDraft(DRAFT_ID, 7L, DEFINITION_ID, "expense", 3,
                deploymentId, "费用报销", "TEMPLATE", 8L, "form_8", "start",
                "报销申请", "发起申请", now, FORM_JSON,
                WorkflowProcessDraftChecksum.sha256(FORM_JSON), "[]", "{}", "{}",
                "expense-submit-42", status, revisionNo, submittedInstanceId,
                status == WorkflowProcessDraftStatus.SUBMITTED ? now : null,
                null, now, now);
    }
}
