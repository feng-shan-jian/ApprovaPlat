package com.ruoyi.flowable.service.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.Date;
import java.util.List;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.engine.HistoryService;
import org.flowable.engine.IdentityService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.DeploymentQuery;
import org.flowable.engine.repository.Model;
import org.flowable.engine.repository.ModelQuery;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.repository.ProcessDefinitionQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfDeployDmnSnapshot;
import com.ruoyi.flowable.domain.WfDeployForm;
import com.ruoyi.flowable.domain.dto.WorkflowDeploymentQueryDto;
import com.ruoyi.flowable.domain.vo.WorkflowDeploymentView;
import com.ruoyi.flowable.domain.vo.WorkflowPageResult;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.engine.WorkflowExceptionTranslator;
import com.ruoyi.flowable.identity.WorkflowAuthenticationContext;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityCodec;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.mapper.WfProcessDraftMapper;
import com.ruoyi.flowable.mapper.WorkflowProcessDefinitionLockMapper;

class WorkflowDeploymentServiceTest
{
    private static final String DEPLOYMENT_ID = "deployment-1";

    private RepositoryService repositoryService;

    private RuntimeService runtimeService;

    private HistoryService historyService;

    private WorkflowDeploymentArtifactRepository artifactRepository;

    private WfProcessDraftMapper processDraftMapper;

    private WorkflowProcessDefinitionLockMapper processDefinitionLockMapper;

    private WorkflowDmnDecisionService dmnDecisionService;

    private WorkflowIdentityResolver identityResolver;

    private IdentityService identityService;

    private WorkflowBpmnService bpmnService;

    private WorkflowDeploymentService service;

    /**
     * 为每个测试创建真实事务与认证执行边界，并替换 Flowable 和 Mapper 外部依赖。
     *
     * @return 无返回值；初始化失败时测试失败
     */
    @BeforeEach
    void setUp()
    {
        // 单元测试使用 Flowable 公共 API 替身，显式绑定生产写边界要求的可重复读事务特征。
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
                Connection.TRANSACTION_REPEATABLE_READ);
        repositoryService = mock(RepositoryService.class);
        runtimeService = mock(RuntimeService.class);
        historyService = mock(HistoryService.class);
        artifactRepository = mock(WorkflowDeploymentArtifactRepository.class);
        processDraftMapper = mock(WfProcessDraftMapper.class);
        processDefinitionLockMapper = mock(WorkflowProcessDefinitionLockMapper.class);
        dmnDecisionService = mock(WorkflowDmnDecisionService.class);
        identityResolver = mock(WorkflowIdentityResolver.class);
        identityService = mock(IdentityService.class);
        bpmnService = mock(WorkflowBpmnService.class);
        when(processDefinitionLockMapper.selectDeploymentIdForUpdate(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        // 生产 SQL 无活动草稿时不返回行；显式模拟 null，避免 Mockito 的 Integer 默认值 0 被误判为引用。
        when(processDraftMapper.selectActiveReferenceForUpdate(anyString()))
                .thenReturn(null);
        when(processDefinitionLockMapper.selectRuntimeInstanceReferenceForUpdate(anyString()))
                .thenReturn(null);
        when(processDefinitionLockMapper.selectHistoricInstanceReferenceForUpdate(anyString()))
                .thenReturn(null);
        WorkflowEngineOperations engineOperations = engineOperations();
        service = service(engineOperations);
        when(identityResolver.resolveCurrentIdentity())
                .thenReturn(new WorkflowCurrentIdentity("7", Set.of()));
    }

    /**
     * 清理当前测试线程绑定的事务特征，防止影响后续测试。
     *
     * @return 无返回值；清理后线程不再携带模拟事务状态
     */
    @AfterEach
    void clearTransactionCharacteristics()
    {
        TransactionSynchronizationManager.clear();
    }

    /**
     * 验证最新定义查询严格先 count 再按安全 offset 分页，并映射部署与首个表单快照。
     *
     * @return 无返回值；断言失败时测试失败
     */
    @Test
    void listsLatestWithExactPagingFiltersAndSnapshotMapping()
    {
        ProcessDefinitionQuery query = stubDefinitionQuery();
        WorkflowDeploymentQueryDto filter = new WorkflowDeploymentQueryDto();
        filter.setProcessKey(" expense ");
        filter.setProcessName(" 报销 ");
        filter.setCategory(" finance ");
        filter.setState(" active ");
        when(query.processDefinitionKey("expense")).thenReturn(query);
        when(query.processDefinitionNameLike("%报销%")).thenReturn(query);
        when(query.processDefinitionCategory("finance")).thenReturn(query);
        when(query.active()).thenReturn(query);
        when(query.latestVersion()).thenReturn(query);
        when(query.orderByProcessDefinitionKey()).thenReturn(query);
        when(query.asc()).thenReturn(query);
        when(query.count()).thenReturn(31L);

        ProcessDefinition definition = stubDefinition(
                "definition-1", DEPLOYMENT_ID, false, "", 3);
        when(query.listPage(20, 10)).thenReturn(List.of(definition));
        Date deploymentTime = new Date(1_753_478_400_000L);
        Deployment deployment = stubDeployment(DEPLOYMENT_ID, "finance-fallback", deploymentTime);
        WfDeployForm firstSnapshot = snapshot(101L, "报销申请单");
        WfDeployForm secondSnapshot = snapshot(102L, "部门审批单");
        when(artifactRepository.selectForms(DEPLOYMENT_ID))
                .thenReturn(List.of(firstSnapshot, secondSnapshot));

        WorkflowPageResult<WorkflowDeploymentView> result = service.listLatest(filter, 3, 10);

        assertThat(result.total()).isEqualTo(31L);
        assertThat(result.rows()).containsExactly(new WorkflowDeploymentView(
                "definition-1", "报销审批", "expense", "finance-fallback", 3,
                101L, "报销申请单", DEPLOYMENT_ID, false, deploymentTime));
        verify(repositoryService).createProcessDefinitionQuery();
        verify(query).processDefinitionKey("expense");
        verify(query).processDefinitionNameLike("%报销%");
        verify(query).processDefinitionCategory("finance");
        verify(query).active();
        verify(deployment).getCategory();
        InOrder pagingOrder = inOrder(query);
        pagingOrder.verify(query).count();
        pagingOrder.verify(query).listPage(20, 10);
    }

    /**
     * 验证发布历史按版本倒序分页，页偏移量准确且无快照时视图表单字段为空。
     *
     * @return 无返回值；断言失败时测试失败
     */
    @Test
    void listsPublishedVersionsWithExactOffsetAndSnapshotMapping()
    {
        ProcessDefinitionQuery query = stubDefinitionQuery();
        when(query.processDefinitionKey("expense")).thenReturn(query);
        when(query.orderByProcessDefinitionVersion()).thenReturn(query);
        when(query.desc()).thenReturn(query);
        when(query.count()).thenReturn(18L);
        ProcessDefinition definition = stubDefinition(
                "definition-2", DEPLOYMENT_ID, true, "finance", 2);
        when(query.listPage(15, 5)).thenReturn(List.of(definition));
        Date deploymentTime = new Date(1_753_564_800_000L);
        stubDeployment(DEPLOYMENT_ID, "ignored-category", deploymentTime);
        when(artifactRepository.selectForms(DEPLOYMENT_ID)).thenReturn(List.of());

        WorkflowPageResult<WorkflowDeploymentView> result =
                service.publishList(" expense ", 4, 5);

        assertThat(result.total()).isEqualTo(18L);
        assertThat(result.rows()).containsExactly(new WorkflowDeploymentView(
                "definition-2", "报销审批", "expense", "finance", 2,
                null, null, DEPLOYMENT_ID, true, deploymentTime));
        verify(query).processDefinitionKey("expense");
        InOrder pagingOrder = inOrder(query);
        pagingOrder.verify(query).count();
        pagingOrder.verify(query).listPage(15, 5);
    }

    /**
     * 验证 count 为零时直接返回不可变空页，不执行无意义的 listPage 和快照查询。
     *
     * @return 无返回值；断言失败时测试失败
     */
    @Test
    void skipsListPageAndMappingWhenCountIsZero()
    {
        ProcessDefinitionQuery query = stubDefinitionQuery();
        when(query.latestVersion()).thenReturn(query);
        when(query.orderByProcessDefinitionKey()).thenReturn(query);
        when(query.asc()).thenReturn(query);
        when(query.count()).thenReturn(0L);

        WorkflowPageResult<WorkflowDeploymentView> result = service.listLatest(null, 1, 20);

        assertThat(result.total()).isZero();
        assertThat(result.rows()).isEmpty();
        verify(query, never()).listPage(0, 20);
        verifyNoInteractions(artifactRepository);
    }

    /**
     * 验证未知状态编码和空目标枚举均在调用 Flowable 前返回稳定 400。
     *
     * @return 无返回值；断言失败时测试失败
     */
    @Test
    void rejectsIllegalDefinitionStateWithBadRequest()
    {
        assertBusinessError(() -> service.changeState("definition-1", "archived"),
                HttpStatus.BAD_REQUEST, "流程定义状态不合法");
        assertBusinessError(() -> service.changeState(
                "definition-1", (WorkflowDefinitionState) null),
                HttpStatus.BAD_REQUEST, "流程定义状态不能为空");

        verifyNoInteractions(repositoryService, runtimeService, historyService, artifactRepository);
    }

    /**
     * 验证定义已处于目标状态时返回 409，且不会重复调用激活或挂起命令。
     *
     * @return 无返回值；断言失败时测试失败
     */
    @Test
    void rejectsSameDefinitionStateWithConflict()
    {
        ProcessDefinition active = mock(ProcessDefinition.class);
        ProcessDefinition suspended = mock(ProcessDefinition.class);
        when(active.isSuspended()).thenReturn(false);
        when(suspended.isSuspended()).thenReturn(true);
        when(repositoryService.getProcessDefinition("active-definition")).thenReturn(active);
        when(repositoryService.getProcessDefinition("suspended-definition")).thenReturn(suspended);

        assertBusinessError(() -> service.changeState(
                "active-definition", WorkflowDefinitionState.ACTIVE),
                HttpStatus.CONFLICT, "流程定义已经是激活状态");
        assertBusinessError(() -> service.changeState(
                "suspended-definition", WorkflowDefinitionState.SUSPENDED),
                HttpStatus.CONFLICT, "流程定义已经是挂起状态");

        verify(repositoryService, never()).activateProcessDefinitionById(
                anyString(), anyBoolean(), org.mockito.ArgumentMatchers.any());
        verify(repositoryService, never()).suspendProcessDefinitionById(
                anyString(), anyBoolean(), org.mockito.ArgumentMatchers.any());
    }

    /**
     * 验证挂起与激活均显式包含运行实例，并在真实身份边界内使用用户 7 执行。
     *
     * @return 无返回值；断言失败时测试失败
     */
    @Test
    void changesDefinitionStateIncludingRunningInstances()
    {
        ProcessDefinition active = mock(ProcessDefinition.class);
        ProcessDefinition suspended = mock(ProcessDefinition.class);
        when(active.isSuspended()).thenReturn(false);
        when(suspended.isSuspended()).thenReturn(true);
        when(repositoryService.getProcessDefinition("definition-to-suspend")).thenReturn(active);
        when(repositoryService.getProcessDefinition("definition-to-activate")).thenReturn(suspended);

        service.changeState("definition-to-suspend", WorkflowDefinitionState.SUSPENDED);
        service.changeState("definition-to-activate", WorkflowDefinitionState.ACTIVE);

        verify(repositoryService).suspendProcessDefinitionById(
                "definition-to-suspend", true, null);
        verify(repositoryService).activateProcessDefinitionById(
                "definition-to-activate", true, null);
        verify(identityResolver, times(2)).resolveCurrentIdentity();
        verify(identityService, times(2)).setAuthenticatedUserId("7");
    }

    /**
     * 验证读取部署 XML 时使用编译资源校验入口，避免把已剥离作者字段的执行资源误判为非法。
     *
     * @return 无返回值；调用作者资源校验或返回内容不一致时测试失败
     */
    @Test
    void readsDeploymentXmlThroughCompiledResourceValidation()
    {
        String xml = "<definitions>compiled</definitions>";
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        ProcessDefinition definition = mock(ProcessDefinition.class);
        when(repositoryService.getProcessDefinition("definition-compiled"))
                .thenReturn(definition);
        when(repositoryService.getProcessModel("definition-compiled"))
                .thenReturn(new ByteArrayInputStream(bytes));
        WorkflowBpmnDocument document = new WorkflowBpmnDocument(
                new BpmnModel(), xml, List.of());
        when(bpmnService.validateCompiledDeployment(
                org.mockito.ArgumentMatchers.any(byte[].class))).thenReturn(document);

        assertThat(service.getBpmnXml("definition-compiled")).isEqualTo(xml);

        verify(bpmnService).validateCompiledDeployment(
                org.mockito.ArgumentMatchers.any(byte[].class));
        verify(bpmnService, never()).validate(
                org.mockito.ArgumentMatchers.any(byte[].class));
    }

    /**
     * 验证缺失流程定义和缺失部署分别返回 404，且不会执行任何状态或删除写入。
     *
     * @return 无返回值；断言失败时测试失败
     */
    @Test
    void rejectsMissingDefinitionAndDeploymentWithNotFound()
    {
        when(repositoryService.getProcessDefinition("missing-definition")).thenReturn(null);
        assertBusinessError(() -> service.changeState(
                "missing-definition", WorkflowDefinitionState.SUSPENDED),
                HttpStatus.NOT_FOUND, "流程定义不存在或已被删除");

        DeploymentQuery deploymentQuery = mock(DeploymentQuery.class);
        when(repositoryService.createDeploymentQuery()).thenReturn(deploymentQuery);
        when(deploymentQuery.deploymentId("missing-deployment")).thenReturn(deploymentQuery);
        when(deploymentQuery.singleResult()).thenReturn(null);
        assertBusinessError(() -> service.deleteDeployments(List.of("missing-deployment")),
                HttpStatus.NOT_FOUND, "流程部署不存在或已被删除");

        verify(repositoryService, never()).deleteDeployment(anyString());
        verify(repositoryService, never()).deleteDeployment(anyString(), anyBoolean());
        verifyNoInteractions(artifactRepository);
    }

    /**
     * 验证部署仍有运行实例时立即返回 409，不读取或删除表单快照和模型关联。
     *
     * @return 无返回值；断言失败时测试失败
     */
    @Test
    void rejectsDeletionWhenRuntimeInstanceExists()
    {
        stubDeployment(DEPLOYMENT_ID, "finance", new Date());
        when(processDefinitionLockMapper
                .selectRuntimeInstanceReferenceForUpdate(DEPLOYMENT_ID)).thenReturn(1);

        assertBusinessError(() -> service.deleteDeployments(List.of(DEPLOYMENT_ID)),
                HttpStatus.CONFLICT, "部署仍有运行中的流程实例");

        verify(processDefinitionLockMapper, never())
                .selectHistoricInstanceReferenceForUpdate(anyString());
        verifyNoInteractions(runtimeService, historyService, artifactRepository);
        verify(repositoryService, never()).createModelQuery();
        verify(repositoryService, never()).deleteDeployment(anyString());
        verify(repositoryService, never()).deleteDeployment(anyString(), anyBoolean());
    }

    /**
     * 验证同步快速结束且只剩历史记录的实例仍返回 409，不清理快照、模型或 Flowable 部署。
     *
     * @return 无返回值；断言失败时测试失败
     */
    @Test
    void rejectsDeletionWhenFastCompletedHistoricInstanceExists()
    {
        stubDeployment(DEPLOYMENT_ID, "finance", new Date());
        when(processDefinitionLockMapper
                .selectHistoricInstanceReferenceForUpdate(DEPLOYMENT_ID)).thenReturn(1);

        assertBusinessError(() -> service.deleteDeployments(List.of(DEPLOYMENT_ID)),
                HttpStatus.CONFLICT, "部署仍有流程历史记录");

        verifyNoInteractions(runtimeService, historyService, artifactRepository);
        verify(repositoryService, never()).createModelQuery();
        verify(repositoryService, never()).deleteDeployment(anyString());
        verify(repositoryService, never()).deleteDeployment(anyString(), anyBoolean());
    }

    /**
     * 验证部署仍被活动申请草稿引用时稳定返回 409，且不进入实例、快照或模型写链。
     *
     * @return 无返回值；活动草稿被级联删除或产生引擎、模型副作用时测试失败
     */
    @Test
    void rejectsDeletionWhenActiveProcessDraftExists()
    {
        stubDeployment(DEPLOYMENT_ID, "finance", new Date());
        when(processDraftMapper.selectActiveReferenceForUpdate(DEPLOYMENT_ID)).thenReturn(1);

        assertBusinessError(() -> service.deleteDeployments(List.of(DEPLOYMENT_ID)),
                HttpStatus.CONFLICT,
                WorkflowDeploymentService.ACTIVE_DRAFT_REFERENCE_MESSAGE);

        InOrder guardOrder = inOrder(processDefinitionLockMapper, processDraftMapper);
        guardOrder.verify(processDefinitionLockMapper)
                .selectDeploymentIdForUpdate(DEPLOYMENT_ID);
        guardOrder.verify(processDraftMapper).selectActiveReferenceForUpdate(DEPLOYMENT_ID);
        verifyNoMoreInteractions(processDraftMapper);
        verifyNoInteractions(runtimeService, historyService, artifactRepository);
        verify(repositoryService, never()).createModelQuery();
        verify(repositoryService, never()).saveModel(any(Model.class));
        verify(repositoryService, never()).deleteDeployment(anyString());
        verify(repositoryService, never()).deleteDeployment(anyString(), anyBoolean());
    }

    /**
     * 验证部署锁当前读发现目标已删除时返回 404，且不进入任何部署或草稿普通查询。
     *
     * @return 无返回值；缺失部署仍读取旧快照或产生删除副作用时测试失败
     */
    @Test
    void rejectsDeletionWhenDeploymentLifecycleLockCannotBeAcquired()
    {
        when(processDefinitionLockMapper.selectDeploymentIdForUpdate(DEPLOYMENT_ID))
                .thenReturn(null);

        assertBusinessError(() -> service.deleteDeployments(List.of(DEPLOYMENT_ID)),
                HttpStatus.NOT_FOUND, "流程部署不存在或已被删除");

        verify(processDefinitionLockMapper).selectDeploymentIdForUpdate(DEPLOYMENT_ID);
        verifyNoInteractions(repositoryService, runtimeService, historyService,
                processDraftMapper, artifactRepository);
    }

    /**
     * 验证反向批量请求仍先按部署主键升序取锁，防止两个批次以相反顺序互相等待。
     *
     * @return 无返回值；部署锁顺序取决于客户端输入顺序时测试失败
     */
    @Test
    void locksMultipleDeploymentsInStableSortedOrder()
    {
        String firstDeploymentId = "deployment-a";
        String secondDeploymentId = "deployment-z";
        when(processDefinitionLockMapper.selectDeploymentIdForUpdate(secondDeploymentId))
                .thenReturn(null);

        assertBusinessError(() -> service.deleteDeployments(
                List.of(secondDeploymentId, firstDeploymentId)),
                HttpStatus.NOT_FOUND, "流程部署不存在或已被删除");

        InOrder lockOrder = inOrder(processDefinitionLockMapper);
        lockOrder.verify(processDefinitionLockMapper)
                .selectDeploymentIdForUpdate(firstDeploymentId);
        lockOrder.verify(processDefinitionLockMapper)
                .selectDeploymentIdForUpdate(secondDeploymentId);
        verifyNoInteractions(repositoryService, runtimeService, historyService,
                processDraftMapper, artifactRepository);
    }

    /**
     * 验证预检后并发新增活动草稿会被写前二次检查拒绝，且不删除快照或修改模型。
     *
     * @return 无返回值；并发草稿绕过二次门禁或产生任一写副作用时测试失败
     */
    @Test
    void rejectsDeletionWhenActiveDraftAppearsAfterPreflight()
    {
        stubDeployment(DEPLOYMENT_ID, "finance", new Date());
        stubNoInstanceReferences(DEPLOYMENT_ID);
        when(processDraftMapper.selectActiveReferenceForUpdate(DEPLOYMENT_ID))
                .thenReturn(null, 1);
        Model linkedModel = mock(Model.class);
        stubLinkedModels(DEPLOYMENT_ID, List.of(linkedModel));

        assertBusinessError(() -> service.deleteDeployments(List.of(DEPLOYMENT_ID)),
                HttpStatus.CONFLICT,
                WorkflowDeploymentService.ACTIVE_DRAFT_REFERENCE_MESSAGE);

        verify(processDraftMapper, times(2))
                .selectActiveReferenceForUpdate(DEPLOYMENT_ID);
        verify(artifactRepository, never()).delete(anyString());
        verify(linkedModel, never()).setDeploymentId(null);
        verify(repositoryService, never()).saveModel(any(Model.class));
        verify(repositoryService, never()).deleteDeployment(anyString());
        verify(repositoryService, never()).deleteDeployment(anyString(), anyBoolean());
    }

    /**
     * 验证首次预检后才可见的快速完成历史实例会在写前当前读中拒绝删除并保持零写副作用。
     *
     * @return 无返回值；第二轮历史当前读缺失或实例审计被删除时测试失败
     */
    @Test
    void rejectsHistoricInstanceAppearingBeforeSecondPreflight()
    {
        stubDeployment(DEPLOYMENT_ID, "finance", new Date());
        when(processDefinitionLockMapper
                .selectHistoricInstanceReferenceForUpdate(DEPLOYMENT_ID))
                .thenReturn(null, 1);
        Model linkedModel = mock(Model.class);
        stubLinkedModels(DEPLOYMENT_ID, List.of(linkedModel));

        assertBusinessError(() -> service.deleteDeployments(List.of(DEPLOYMENT_ID)),
                HttpStatus.CONFLICT, "部署仍有流程历史记录");

        verify(processDefinitionLockMapper, times(2))
                .selectHistoricInstanceReferenceForUpdate(DEPLOYMENT_ID);
        verify(artifactRepository, never()).delete(anyString());
        verify(linkedModel, never()).setDeploymentId(null);
        verify(repositoryService, never()).saveModel(any(Model.class));
        verify(repositoryService, never()).deleteDeployment(anyString());
        verify(repositoryService, never()).deleteDeployment(anyString(), anyBoolean());
    }

    /**
     * 验证安全删除会二次检查实例、先删除统一资源子部署、清空模型关联且绝不级联主部署。
     *
     * @return 无返回值；断言失败时测试失败
     */
    @Test
    void deletesDeploymentWithoutCascadeAfterSnapshotAndModelCleanup()
    {
        stubDeployment(DEPLOYMENT_ID, "finance", new Date());
        stubNoInstanceReferences(DEPLOYMENT_ID);
        WfDeployDmnSnapshot dmnSnapshot = new WfDeployDmnSnapshot();
        dmnSnapshot.setSnapshotId(301L);
        dmnSnapshot.setFrozenDeploymentId("dmn-frozen-1");
        when(artifactRepository.selectDmnSnapshots(DEPLOYMENT_ID))
                .thenReturn(List.of(dmnSnapshot));
        when(artifactRepository.delete(DEPLOYMENT_ID)).thenReturn(1);
        Model linkedModel = mock(Model.class);
        stubLinkedModels(DEPLOYMENT_ID, List.of(linkedModel));

        service.deleteDeployments(List.of(DEPLOYMENT_ID));

        verify(processDefinitionLockMapper, times(2))
                .selectRuntimeInstanceReferenceForUpdate(DEPLOYMENT_ID);
        verify(processDefinitionLockMapper, times(2))
                .selectHistoricInstanceReferenceForUpdate(DEPLOYMENT_ID);
        verify(processDraftMapper, times(2))
                .selectActiveReferenceForUpdate(DEPLOYMENT_ID);
        verify(artifactRepository).delete(DEPLOYMENT_ID);
        verify(linkedModel).setDeploymentId(null);
        verify(repositoryService).saveModel(linkedModel);
        verify(repositoryService).deleteDeployment(DEPLOYMENT_ID);
        verify(dmnDecisionService).deleteFrozenDeployments(List.of(dmnSnapshot));
        verify(repositoryService, never()).deleteDeployment(anyString(), anyBoolean());

        InOrder deletionOrder = inOrder(artifactRepository, linkedModel,
                repositoryService, dmnDecisionService);
        deletionOrder.verify(artifactRepository).delete(DEPLOYMENT_ID);
        deletionOrder.verify(linkedModel).setDeploymentId(null);
        deletionOrder.verify(repositoryService).saveModel(linkedModel);
        deletionOrder.verify(repositoryService).deleteDeployment(DEPLOYMENT_ID);
        deletionOrder.verify(dmnDecisionService).deleteFrozenDeployments(List.of(dmnSnapshot));
    }

    /**
     * 验证前序快照和模型解绑后引擎删除失败会回滚同一 Spring 事务，绝不提交半完成删除。
     *
     * @return 无返回值；异常事务被提交或删除不在统一事务内时测试失败
     */
    @Test
    void rollsBackSingleTransactionWhenEngineDeploymentDeletionFails()
    {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(transactionStatus);
        WorkflowEngineOperations transactionalOperations = transactionalProxy(
                engineOperations(), transactionManager);
        WorkflowDeploymentService transactionalService = service(transactionalOperations);

        stubDeployment(DEPLOYMENT_ID, "finance", new Date());
        stubNoInstanceReferences(DEPLOYMENT_ID);
        when(artifactRepository.selectDmnSnapshots(DEPLOYMENT_ID)).thenReturn(List.of());
        when(artifactRepository.delete(DEPLOYMENT_ID)).thenReturn(1);
        Model linkedModel = mock(Model.class);
        stubLinkedModels(DEPLOYMENT_ID, List.of(linkedModel));
        doThrow(new FlowableException("forced deployment deletion failure"))
                .when(repositoryService).deleteDeployment(DEPLOYMENT_ID);

        assertBusinessError(() -> transactionalService.deleteDeployments(List.of(DEPLOYMENT_ID)),
                HttpStatus.CONFLICT, "流程部署状态已变化，请刷新后重试");

        InOrder calls = inOrder(transactionManager, artifactRepository,
                linkedModel, repositoryService, identityService);
        calls.verify(transactionManager).getTransaction(any(TransactionDefinition.class));
        calls.verify(artifactRepository).delete(DEPLOYMENT_ID);
        calls.verify(linkedModel).setDeploymentId(null);
        calls.verify(repositoryService).saveModel(linkedModel);
        calls.verify(repositoryService).deleteDeployment(DEPLOYMENT_ID);
        calls.verify(identityService).setAuthenticatedUserId(null);
        calls.verify(transactionManager).rollback(transactionStatus);
        verify(transactionManager, never()).commit(any());
        verifyNoInteractions(dmnDecisionService);
    }

    /**
     * 验证统一资源子部署删除失败时立即终止主部署删除和模型解绑。
     *
     * @return 无返回值；资源删除异常被吞掉或继续产生主部署副作用时测试失败
     */
    @Test
    void rejectsDeletionWhenArtifactCleanupFails()
    {
        stubDeployment(DEPLOYMENT_ID, "finance", new Date());
        stubNoInstanceReferences(DEPLOYMENT_ID);
        when(artifactRepository.selectDmnSnapshots(DEPLOYMENT_ID)).thenReturn(List.of());
        ServiceException failure = new ServiceException("部署业务资源损坏", HttpStatus.CONFLICT);
        doThrow(failure).when(artifactRepository).delete(DEPLOYMENT_ID);
        Model linkedModel = mock(Model.class);
        stubLinkedModels(DEPLOYMENT_ID, List.of(linkedModel));

        assertThatThrownBy(() -> service.deleteDeployments(List.of(DEPLOYMENT_ID)))
                .isSameAs(failure);

        verify(linkedModel, never()).setDeploymentId(null);
        verify(repositoryService, never()).saveModel(linkedModel);
        verify(repositoryService, never()).deleteDeployment(anyString());
        verify(repositoryService, never()).deleteDeployment(anyString(), anyBoolean());
        verifyNoInteractions(dmnDecisionService);
    }

    /**
     * 创建使用当前测试依赖和真实异常翻译器的引擎执行边界。
     *
     * @return WorkflowEngineOperations，可直接调用或安装声明式事务代理的执行器
     */
    private WorkflowEngineOperations engineOperations()
    {
        WorkflowAuthenticationContext authenticationContext = new WorkflowAuthenticationContext(
                identityService, new WorkflowIdentityCodec());
        return new WorkflowEngineOperations(
                authenticationContext, new WorkflowExceptionTranslator(), identityResolver);
    }

    /**
     * 创建并注入统一资源仓库和全部删除门禁 Mapper 的部署服务。
     *
     * @param operations WorkflowEngineOperations，普通或事务代理执行边界
     * @return WorkflowDeploymentService，待测部署服务
     */
    private WorkflowDeploymentService service(WorkflowEngineOperations operations)
    {
        WorkflowDeploymentService created = new WorkflowDeploymentService(
                operations, repositoryService, runtimeService, historyService,
                artifactRepository, dmnDecisionService, bpmnService);
        created.setProcessDraftMapper(processDraftMapper);
        created.setProcessDefinitionLockMapper(processDefinitionLockMapper);
        return created;
    }

    /**
     * 为引擎执行边界安装真实 @Transactional 拦截器，用事务管理器替身记录提交或回滚。
     *
     * @param target WorkflowEngineOperations，带事务注解的执行器目标
     * @param transactionManager PlatformTransactionManager，记录事务生命周期的替身
     * @return WorkflowEngineOperations，应用声明式事务后的代理
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
     * 构造支持条件、排序、count 和 listPage 的流程定义查询替身。
     *
     * @return ProcessDefinitionQuery，已注册到 RepositoryService 的查询替身
     */
    private ProcessDefinitionQuery stubDefinitionQuery()
    {
        ProcessDefinitionQuery query = mock(ProcessDefinitionQuery.class);
        when(repositoryService.createProcessDefinitionQuery()).thenReturn(query);
        return query;
    }

    /**
     * 构造用于列表映射的流程定义替身。
     *
     * @param definitionId String，流程定义主键
     * @param deploymentId String，所属部署主键
     * @param suspended boolean，是否挂起
     * @param category String，定义分类，允许为空并回退部署分类
     * @param version int，流程定义版本
     * @return ProcessDefinition，包含视图映射字段的定义替身
     */
    private ProcessDefinition stubDefinition(String definitionId, String deploymentId,
            boolean suspended, String category, int version)
    {
        ProcessDefinition definition = mock(ProcessDefinition.class);
        when(definition.getId()).thenReturn(definitionId);
        when(definition.getName()).thenReturn("报销审批");
        when(definition.getKey()).thenReturn("expense");
        when(definition.getCategory()).thenReturn(category);
        when(definition.getVersion()).thenReturn(version);
        when(definition.getDeploymentId()).thenReturn(deploymentId);
        when(definition.isSuspended()).thenReturn(suspended);
        return definition;
    }

    /**
     * 构造可按主键查询的 Flowable 部署替身。
     *
     * @param deploymentId String，部署主键
     * @param category String，部署分类
     * @param deploymentTime Date，部署时间
     * @return Deployment，已注册到 RepositoryService 的部署替身
     */
    private Deployment stubDeployment(String deploymentId, String category, Date deploymentTime)
    {
        Deployment deployment = mock(Deployment.class);
        when(deployment.getId()).thenReturn(deploymentId);
        when(deployment.getCategory()).thenReturn(category);
        when(deployment.getDeploymentTime()).thenReturn(deploymentTime);
        DeploymentQuery deploymentQuery = mock(DeploymentQuery.class);
        when(repositoryService.createDeploymentQuery()).thenReturn(deploymentQuery);
        when(deploymentQuery.deploymentId(deploymentId)).thenReturn(deploymentQuery);
        when(deploymentQuery.singleResult()).thenReturn(deployment);
        return deployment;
    }

    /**
     * 构造部署表单快照，用于验证首条映射和删除行数一致性。
     *
     * @param formId Long，来源表单主键
     * @param formName String，部署时固化的表单名称
     * @return WfDeployForm，包含映射字段的快照
     */
    private WfDeployForm snapshot(Long formId, String formName)
    {
        WfDeployForm snapshot = new WfDeployForm();
        snapshot.setDeployId(DEPLOYMENT_ID);
        snapshot.setSourceType("TEMPLATE");
        snapshot.setFormId(formId);
        snapshot.setFormName(formName);
        return snapshot;
    }

    /**
     * 构造两轮检查均无运行和历史实例引用的查询替身。
     *
     * @param deploymentId String，待删除部署主键
     * @return void，Mapper 当前读均返回无引用
     */
    private void stubNoInstanceReferences(String deploymentId)
    {
        when(processDefinitionLockMapper
                .selectRuntimeInstanceReferenceForUpdate(deploymentId)).thenReturn(null);
        when(processDefinitionLockMapper
                .selectHistoricInstanceReferenceForUpdate(deploymentId)).thenReturn(null);
    }

    /**
     * 构造部署关联模型查询，用于验证删除前解除 deploymentId 关联。
     *
     * @param deploymentId String，待删除部署主键
     * @param models List&lt;Model&gt;，查询返回的关联模型
     * @return 无返回值；查询替身直接注册到 RepositoryService
     */
    private void stubLinkedModels(String deploymentId, List<Model> models)
    {
        ModelQuery modelQuery = mock(ModelQuery.class);
        when(repositoryService.createModelQuery()).thenReturn(modelQuery);
        when(modelQuery.deploymentId(deploymentId)).thenReturn(modelQuery);
        when(modelQuery.list()).thenReturn(models);
    }

    /**
     * 断言业务异常同时满足稳定 HTTP 状态和对外消息契约。
     *
     * @param action ThrowingCallable，预期失败的服务调用
     * @param expectedCode int，预期 HTTP 状态码
     * @param expectedMessage String，预期对外业务消息
     * @return 无返回值；断言失败时测试失败
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
