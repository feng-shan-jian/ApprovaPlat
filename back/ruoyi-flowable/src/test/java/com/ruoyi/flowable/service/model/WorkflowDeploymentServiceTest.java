package com.ruoyi.flowable.service.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.util.Date;
import java.util.List;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.flowable.engine.HistoryService;
import org.flowable.engine.IdentityService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.history.HistoricProcessInstanceQuery;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.DeploymentQuery;
import org.flowable.engine.repository.Model;
import org.flowable.engine.repository.ModelQuery;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.repository.ProcessDefinitionQuery;
import org.flowable.engine.runtime.ProcessInstanceQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
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
import com.ruoyi.flowable.mapper.WfDeployFormMapper;

class WorkflowDeploymentServiceTest
{
    private static final String DEPLOYMENT_ID = "deployment-1";

    private RepositoryService repositoryService;

    private RuntimeService runtimeService;

    private HistoryService historyService;

    private WfDeployFormMapper deployFormMapper;

    private WorkflowIdentityResolver identityResolver;

    private IdentityService identityService;

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
        deployFormMapper = mock(WfDeployFormMapper.class);
        identityResolver = mock(WorkflowIdentityResolver.class);
        identityService = mock(IdentityService.class);
        WorkflowBpmnService bpmnService = mock(WorkflowBpmnService.class);
        WorkflowAuthenticationContext authenticationContext = new WorkflowAuthenticationContext(
                identityService, new WorkflowIdentityCodec());
        WorkflowEngineOperations engineOperations = new WorkflowEngineOperations(
                authenticationContext, new WorkflowExceptionTranslator(), identityResolver);
        service = new WorkflowDeploymentService(engineOperations, repositoryService,
                runtimeService, historyService, deployFormMapper, bpmnService);
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
        when(deployFormMapper.selectByDeploymentId(DEPLOYMENT_ID))
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
        when(deployFormMapper.selectByDeploymentId(DEPLOYMENT_ID)).thenReturn(null);

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
        verifyNoInteractions(deployFormMapper);
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

        verifyNoInteractions(repositoryService, runtimeService, historyService, deployFormMapper);
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
        verifyNoInteractions(deployFormMapper);
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
        ProcessInstanceQuery runtimeQuery = mock(ProcessInstanceQuery.class);
        when(runtimeService.createProcessInstanceQuery()).thenReturn(runtimeQuery);
        when(runtimeQuery.deploymentId(DEPLOYMENT_ID)).thenReturn(runtimeQuery);
        when(runtimeQuery.count()).thenReturn(1L);

        assertBusinessError(() -> service.deleteDeployments(List.of(DEPLOYMENT_ID)),
                HttpStatus.CONFLICT, "部署仍有运行中的流程实例");

        verifyNoInteractions(historyService, deployFormMapper);
        verify(repositoryService, never()).createModelQuery();
        verify(repositoryService, never()).deleteDeployment(anyString());
        verify(repositoryService, never()).deleteDeployment(anyString(), anyBoolean());
    }

    /**
     * 验证部署仍有任意历史实例时返回 409，不清理快照、模型或 Flowable 部署。
     *
     * @return 无返回值；断言失败时测试失败
     */
    @Test
    void rejectsDeletionWhenHistoricInstanceExists()
    {
        stubDeployment(DEPLOYMENT_ID, "finance", new Date());
        ProcessInstanceQuery runtimeQuery = mock(ProcessInstanceQuery.class);
        when(runtimeService.createProcessInstanceQuery()).thenReturn(runtimeQuery);
        when(runtimeQuery.deploymentId(DEPLOYMENT_ID)).thenReturn(runtimeQuery);
        when(runtimeQuery.count()).thenReturn(0L);
        HistoricProcessInstanceQuery historyQuery = mock(HistoricProcessInstanceQuery.class);
        when(historyService.createHistoricProcessInstanceQuery()).thenReturn(historyQuery);
        when(historyQuery.deploymentId(DEPLOYMENT_ID)).thenReturn(historyQuery);
        when(historyQuery.count()).thenReturn(1L);

        assertBusinessError(() -> service.deleteDeployments(List.of(DEPLOYMENT_ID)),
                HttpStatus.CONFLICT, "部署仍有流程历史记录");

        verifyNoInteractions(deployFormMapper);
        verify(repositoryService, never()).createModelQuery();
        verify(repositoryService, never()).deleteDeployment(anyString());
        verify(repositoryService, never()).deleteDeployment(anyString(), anyBoolean());
    }

    /**
     * 验证安全删除会二次检查实例、先按精确行数删快照、清空模型关联且绝不级联。
     *
     * @return 无返回值；断言失败时测试失败
     */
    @Test
    void deletesDeploymentWithoutCascadeAfterSnapshotAndModelCleanup()
    {
        stubDeployment(DEPLOYMENT_ID, "finance", new Date());
        InstanceQueries instanceQueries = stubNoInstanceReferences(DEPLOYMENT_ID);
        WfDeployForm firstSnapshot = snapshot(101L, "报销申请单");
        WfDeployForm secondSnapshot = snapshot(102L, "部门审批单");
        when(deployFormMapper.selectByDeploymentId(DEPLOYMENT_ID))
                .thenReturn(List.of(firstSnapshot, secondSnapshot));
        when(deployFormMapper.deleteByDeploymentId(DEPLOYMENT_ID)).thenReturn(2);
        Model linkedModel = mock(Model.class);
        stubLinkedModels(DEPLOYMENT_ID, List.of(linkedModel));

        service.deleteDeployments(List.of(DEPLOYMENT_ID));

        verify(instanceQueries.runtimeQuery(), times(2)).deploymentId(DEPLOYMENT_ID);
        verify(instanceQueries.runtimeQuery(), times(2)).count();
        verify(instanceQueries.historyQuery(), times(2)).deploymentId(DEPLOYMENT_ID);
        verify(instanceQueries.historyQuery(), times(2)).count();
        verify(deployFormMapper).deleteByDeploymentId(DEPLOYMENT_ID);
        verify(linkedModel).setDeploymentId(null);
        verify(repositoryService).saveModel(linkedModel);
        verify(repositoryService).deleteDeployment(DEPLOYMENT_ID);
        verify(repositoryService, never()).deleteDeployment(anyString(), anyBoolean());

        InOrder deletionOrder = inOrder(deployFormMapper, linkedModel, repositoryService);
        deletionOrder.verify(deployFormMapper).deleteByDeploymentId(DEPLOYMENT_ID);
        deletionOrder.verify(linkedModel).setDeploymentId(null);
        deletionOrder.verify(repositoryService).saveModel(linkedModel);
        deletionOrder.verify(repositoryService).deleteDeployment(DEPLOYMENT_ID);
    }

    /**
     * 验证预检后快照行数发生变化时返回 409，事务不再修改模型或删除部署。
     *
     * @return 无返回值；断言失败时测试失败
     */
    @Test
    void rejectsDeletionWhenSnapshotCountChangesConcurrently()
    {
        stubDeployment(DEPLOYMENT_ID, "finance", new Date());
        stubNoInstanceReferences(DEPLOYMENT_ID);
        when(deployFormMapper.selectByDeploymentId(DEPLOYMENT_ID))
                .thenReturn(List.of(snapshot(101L, "报销申请单"),
                        snapshot(102L, "部门审批单")));
        when(deployFormMapper.deleteByDeploymentId(DEPLOYMENT_ID)).thenReturn(1);
        Model linkedModel = mock(Model.class);
        stubLinkedModels(DEPLOYMENT_ID, List.of(linkedModel));

        assertBusinessError(() -> service.deleteDeployments(List.of(DEPLOYMENT_ID)),
                HttpStatus.CONFLICT, "部署表单快照状态已变化");

        verify(linkedModel, never()).setDeploymentId(null);
        verify(repositoryService, never()).saveModel(linkedModel);
        verify(repositoryService, never()).deleteDeployment(anyString());
        verify(repositoryService, never()).deleteDeployment(anyString(), anyBoolean());
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
        snapshot.setFormId(formId);
        snapshot.setFormName(formName);
        return snapshot;
    }

    /**
     * 构造两轮检查均无运行和历史实例引用的查询替身。
     *
     * @param deploymentId String，待删除部署主键
     * @return InstanceQueries，便于验证二次检查次数的查询替身集合
     */
    private InstanceQueries stubNoInstanceReferences(String deploymentId)
    {
        ProcessInstanceQuery runtimeQuery = mock(ProcessInstanceQuery.class);
        when(runtimeService.createProcessInstanceQuery()).thenReturn(runtimeQuery);
        when(runtimeQuery.deploymentId(deploymentId)).thenReturn(runtimeQuery);
        when(runtimeQuery.count()).thenReturn(0L);
        HistoricProcessInstanceQuery historyQuery = mock(HistoricProcessInstanceQuery.class);
        when(historyService.createHistoricProcessInstanceQuery()).thenReturn(historyQuery);
        when(historyQuery.deploymentId(deploymentId)).thenReturn(historyQuery);
        when(historyQuery.count()).thenReturn(0L);
        return new InstanceQueries(runtimeQuery, historyQuery);
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

    /**
     * 删除前运行实例和历史实例查询替身集合。
     *
     * @param runtimeQuery ProcessInstanceQuery，运行实例查询
     * @param historyQuery HistoricProcessInstanceQuery，历史实例查询
     */
    private record InstanceQueries(ProcessInstanceQuery runtimeQuery,
            HistoricProcessInstanceQuery historyQuery)
    {
    }
}
