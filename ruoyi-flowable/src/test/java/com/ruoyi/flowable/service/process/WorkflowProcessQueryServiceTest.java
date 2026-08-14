package com.ruoyi.flowable.service.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.Function;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FormProperty;
import org.flowable.bpmn.model.MultiInstanceLoopCharacteristics;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.history.HistoricProcessInstanceQuery;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.DeploymentQuery;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.repository.ProcessDefinitionQuery;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.engine.runtime.ProcessInstanceQuery;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.task.api.history.HistoricTaskInstanceQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.authorization.WorkflowProcessAccessService;
import com.ruoyi.flowable.authorization.WorkflowProcessAccessSnapshot;
import com.ruoyi.flowable.domain.WfCopy;
import com.ruoyi.flowable.domain.WfDeployForm;
import com.ruoyi.flowable.domain.dto.WorkflowAssignedTaskQueryDto;
import com.ruoyi.flowable.domain.dto.WorkflowBpmnXmlQueryDto;
import com.ruoyi.flowable.domain.dto.WorkflowClaimableTaskQueryDto;
import com.ruoyi.flowable.domain.dto.WorkflowCompletedTaskQueryDto;
import com.ruoyi.flowable.domain.dto.WorkflowCopyQueryDto;
import com.ruoyi.flowable.domain.dto.WorkflowManagedProcessQueryDto;
import com.ruoyi.flowable.domain.dto.WorkflowOwnedProcessQueryDto;
import com.ruoyi.flowable.domain.dto.WorkflowProcessFormQueryDto;
import com.ruoyi.flowable.domain.dto.WorkflowStartableProcessQueryDto;
import com.ruoyi.flowable.domain.vo.WorkflowAssignedTaskView;
import com.ruoyi.flowable.domain.vo.WorkflowClaimableTaskView;
import com.ruoyi.flowable.domain.vo.WorkflowCompletedTaskView;
import com.ruoyi.flowable.domain.vo.WorkflowCopyView;
import com.ruoyi.flowable.domain.vo.WorkflowManagedProcessView;
import com.ruoyi.flowable.domain.vo.WorkflowOwnedProcessView;
import com.ruoyi.flowable.domain.vo.WorkflowPageResult;
import com.ruoyi.flowable.domain.vo.WorkflowProcessFormView;
import com.ruoyi.flowable.domain.vo.WorkflowStartableDefinitionView;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.mapper.WfCopyMapper;
import com.ruoyi.flowable.service.model.WorkflowDeploymentArtifactRepository;
import com.ruoyi.flowable.service.identity.WorkflowParticipantRuleRuntimeService;
import com.ruoyi.flowable.service.model.WorkflowDeploymentService;
import com.ruoyi.flowable.service.task.WorkflowMultiInstanceModelContract;
import com.ruoyi.flowable.service.task.WorkflowTaskLifecycleService;
import com.ruoyi.system.service.ISysUserService;

/**
 * WorkflowProcessQueryService 的身份范围、分页和对象关系单元测试。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkflowProcessQueryServiceTest
{
    @Mock
    private WorkflowEngineOperations engineOperations;

    @Mock
    private RepositoryService repositoryService;

    @Mock
    private HistoryService historyService;

    @Mock
    private RuntimeService runtimeService;

    @Mock
    private TaskService taskService;

    @Mock
    private WorkflowIdentityResolver identityResolver;

    @Mock
    private WorkflowProcessAccessService processAccessService;

    @Mock
    private WorkflowDeploymentService deploymentService;

    @Mock
    private WorkflowDeploymentArtifactRepository artifactRepository;

    @Mock
    private WfCopyMapper copyMapper;

    @Mock
    private ISysUserService userService;

    @Mock
    private WorkflowTaskLifecycleService taskLifecycleService;

    @Mock
    private WorkflowParticipantRuleRuntimeService participantRuleRuntimeService;

    private ProcessDefinitionQuery definitionQuery;

    private DeploymentQuery deploymentQuery;

    private HistoricProcessInstanceQuery processQuery;

    private ProcessInstanceQuery runtimeProcessQuery;

    private TaskQuery taskQuery;

    private HistoricTaskInstanceQuery historicTaskQuery;

    private WorkflowProcessQueryService service;

    /**
     * 为每个场景建立可执行只读边界、当前身份和可复用的 fluent Flowable 查询 mock。
     *
     * @return 无返回值，每个测试获得独立 Mockito 状态
     */
    @BeforeEach
    @SuppressWarnings({ "rawtypes", "unchecked" })
    void setUp()
    {
        definitionQuery = mock(ProcessDefinitionQuery.class, RETURNS_SELF);
        deploymentQuery = mock(DeploymentQuery.class, RETURNS_SELF);
        processQuery = mock(HistoricProcessInstanceQuery.class, RETURNS_SELF);
        runtimeProcessQuery = mock(ProcessInstanceQuery.class, RETURNS_SELF);
        taskQuery = mock(TaskQuery.class, RETURNS_SELF);
        historicTaskQuery = mock(HistoricTaskInstanceQuery.class, RETURNS_SELF);

        when(engineOperations.read(any(Supplier.class))).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(0)).get());
        when(engineOperations.writeAsCurrentUser(any(Function.class))).thenAnswer(invocation ->
                ((Function<WorkflowCurrentIdentity, ?>) invocation.getArgument(0)).apply(
                        new WorkflowCurrentIdentity("7", Set.of("ROLE2", "DEPT3"))));
        when(identityResolver.resolveCurrentIdentity())
                .thenReturn(new WorkflowCurrentIdentity("7", Set.of("ROLE2", "DEPT3")));
        when(identityResolver.resolveClaimEligibleUserIds(List.of("7")))
                .thenReturn(Set.of("7"));
        // 历史部署未托管时返回 null，继续覆盖既有 starter identity link 兼容链。
        when(participantRuleRuntimeService.canStartIfManaged(any(), any()))
                .thenReturn(null);
        when(repositoryService.createProcessDefinitionQuery()).thenReturn(definitionQuery);
        when(repositoryService.createDeploymentQuery()).thenReturn(deploymentQuery);
        when(historyService.createHistoricProcessInstanceQuery()).thenReturn(processQuery);
        when(historyService.createHistoricTaskInstanceQuery()).thenReturn(historicTaskQuery);
        when(runtimeService.createProcessInstanceQuery()).thenReturn(runtimeProcessQuery);
        when(runtimeProcessQuery.list()).thenReturn(List.of());
        when(taskService.createTaskQuery()).thenReturn(taskQuery);

        service = new WorkflowProcessQueryService(engineOperations, repositoryService,
                historyService, runtimeService, taskService, identityResolver,
                processAccessService,
                deploymentService, artifactRepository, copyMapper, userService,
                taskLifecycleService);
        service.setParticipantRuleRuntimeService(participantRuleRuntimeService);
    }

    /**
     * 验证公开定义和 ROLE 候选定义共同进入真实 total，未命中定义被过滤且第二页稳定返回。
     *
     * @return 无返回值，断言有界分块授权过滤和确定性分页
     */
    @Test
    void listsPublicAndGroupStartableDefinitionsWithAccurateTotal()
    {
        ProcessDefinition publicDefinition = definition("definition-public", "public", "deploy-public", false);
        ProcessDefinition groupDefinition = definition("definition-group", "group", "deploy-group", false);
        when(groupDefinition.getCategory()).thenReturn("http://ruoyi.example/workflow");
        ProcessDefinition deniedDefinition = definition("definition-denied", "denied", "deploy-denied", false);
        when(definitionQuery.count()).thenReturn(3L);
        when(definitionQuery.listPage(0, 3))
                .thenReturn(List.of(publicDefinition, groupDefinition, deniedDefinition));
        when(repositoryService.getIdentityLinksForProcessDefinition("definition-public"))
                .thenReturn(List.of());
        IdentityLink role2Link = groupLink("ROLE2");
        IdentityLink role9Link = groupLink("ROLE9");
        when(repositoryService.getIdentityLinksForProcessDefinition("definition-group"))
                .thenReturn(List.of(role2Link));
        when(repositoryService.getIdentityLinksForProcessDefinition("definition-denied"))
                .thenReturn(List.of(role9Link));
        Deployment deployment = mock(Deployment.class);
        when(deployment.getCategory()).thenReturn("business");
        when(deployment.getDeploymentTime()).thenReturn(Date.from(Instant.parse("2026-07-25T08:00:00Z")));
        when(deploymentQuery.singleResult()).thenReturn(deployment);

        WorkflowPageResult<WorkflowStartableDefinitionView> result =
                service.listStartable(null, 2, 1);

        assertThat(result.total()).isEqualTo(2);
        assertThat(result.rows()).singleElement().satisfies(row ->
        {
            assertThat(row.definitionId()).isEqualTo("definition-group");
            assertThat(row.category()).isEqualTo("business");
        });
        verify(definitionQuery).count();
        verify(definitionQuery).listPage(0, 3);
        verify(definitionQuery, never()).list();
        verify(participantRuleRuntimeService).canStartIfManaged(any(), eq(publicDefinition));
        verify(participantRuleRuntimeService).canStartIfManaged(any(), eq(groupDefinition));
        verify(participantRuleRuntimeService).canStartIfManaged(any(), eq(deniedDefinition));
    }

    /**
     * 验证可发起流程按正式部署分类筛选，即使定义分类是 BPMN targetNamespace 也能命中。
     *
     * @return 无返回值，定义分类参与业务筛选或部署分类未命中时测试失败
     */
    @Test
    void filtersStartableDefinitionsByDeploymentCategory()
    {
        Deployment deployment = stubCategoryDeployment("business", "deploy-business");
        ProcessDefinition definition = definition(
                "definition-business", "business-process", "deploy-business", false);
        when(definition.getCategory()).thenReturn("http://ruoyi.example/workflow");
        when(definitionQuery.count()).thenReturn(1L);
        when(definitionQuery.listPage(0, 1)).thenReturn(List.of(definition));
        when(repositoryService.getIdentityLinksForProcessDefinition("definition-business"))
                .thenReturn(List.of());
        when(deploymentQuery.singleResult()).thenReturn(deployment);

        WorkflowPageResult<WorkflowStartableDefinitionView> result = service.listStartable(
                new WorkflowStartableProcessQueryDto(null, null, "business"), 1, 10);

        assertThat(result.rows()).singleElement().satisfies(row ->
        {
            assertThat(row.definitionId()).isEqualTo("definition-business");
            assertThat(row.category()).isEqualTo("business");
        });
        verify(deploymentQuery).deploymentCategory("business");
        verify(definitionQuery).deploymentIds(Set.of("deploy-business"));
        verify(definitionQuery, never()).processDefinitionCategory(anyString());
    }

    /**
     * 验证新部署定义优先按不可变范围快照过滤，拒绝结果不会回退旧 starter link。
     *
     * @return 无返回值，快照被旧 identity link 绕过时测试失败
     */
    @Test
    void filtersStartableDefinitionsBySnapshotWithoutLegacyFallback()
    {
        ProcessDefinition allowed = definition(
                "definition-snapshot-allowed", "allowed", "deploy-allowed", false);
        ProcessDefinition denied = definition(
                "definition-snapshot-denied", "denied", "deploy-denied", false);
        when(definitionQuery.count()).thenReturn(2L);
        when(definitionQuery.listPage(0, 2)).thenReturn(List.of(allowed, denied));
        when(participantRuleRuntimeService.canStartIfManaged(any(), eq(allowed)))
                .thenReturn(true);
        when(participantRuleRuntimeService.canStartIfManaged(any(), eq(denied)))
                .thenReturn(false);
        Deployment deployment = mock(Deployment.class);
        when(deployment.getDeploymentTime())
                .thenReturn(Date.from(Instant.parse("2026-08-08T08:00:00Z")));
        when(deploymentQuery.singleResult()).thenReturn(deployment);

        WorkflowPageResult<WorkflowStartableDefinitionView> result =
                service.listStartable(null, 1, 10);

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.rows()).singleElement()
                .extracting(WorkflowStartableDefinitionView::definitionId)
                .isEqualTo("definition-snapshot-allowed");
        verify(repositoryService, never()).getIdentityLinksForProcessDefinition(anyString());
    }

    /**
     * 验证可发起基础定义超过扫描上限时明确失败，不截断结果伪造 total。
     *
     * @return 无返回值，断言超量分支和零分页读取
     */
    @Test
    void rejectsStartableScanAboveBound()
    {
        when(definitionQuery.count()).thenReturn(10_001L);

        assertCode(() -> service.listStartable(null, 1, 10), HttpStatus.BAD_REQUEST);

        verify(definitionQuery, never()).listPage(any(Integer.class), any(Integer.class));
    }

    /**
     * 验证我发起列表固定 startedBy 当前用户，并对子查询执行 count + listPage。
     *
     * @return 无返回值，断言流程状态、当前任务名称和分页契约
     */
    @Test
    void listsOnlyProcessesStartedByCurrentUser()
    {
        HistoricProcessInstance instance = historicInstance("instance-1", "definition-1", "deploy-1");
        when(instance.getProcessDefinitionKey()).thenReturn("leave");
        when(instance.getProcessDefinitionName()).thenReturn("请假流程");
        when(instance.getProcessDefinitionVersion()).thenReturn(3);
        when(instance.getProcessDefinitionCategory()).thenReturn("hr");
        when(instance.getState()).thenReturn("RUNNING");
        when(processQuery.count()).thenReturn(1L);
        when(processQuery.listPage(0, 10)).thenReturn(List.of(instance));
        Task first = mock(Task.class);
        Task second = mock(Task.class);
        when(first.getName()).thenReturn("部门审批");
        when(second.getName()).thenReturn("财务审批");
        when(taskQuery.count()).thenReturn(2L);
        when(taskQuery.listPage(0, 2)).thenReturn(List.of(first, second));

        WorkflowPageResult<WorkflowOwnedProcessView> result = service.listOwned(
                new WorkflowOwnedProcessQueryDto(null, null, null, null, null, null), 1, 10);

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.rows().get(0).processStatus()).isEqualTo("running");
        assertThat(result.rows().get(0).currentTaskNames())
                .containsExactly("部门审批", "财务审批");
        verify(processQuery).startedBy("7");
        verify(taskQuery).count();
        verify(taskQuery).listPage(0, 2);
    }

    /**
     * 验证管理员无发起人筛选时不会把当前用户注入 startedBy，从而保留跨用户实例范围。
     *
     * @return 无返回值，当前管理员被错误限制为本人发起实例时测试失败
     */
    @Test
    void listsManagedProcessesWithoutCurrentStarterRestriction()
    {
        when(processQuery.count()).thenReturn(0L);

        WorkflowPageResult<WorkflowManagedProcessView> result =
                service.listManaged(null, 1, 10);

        assertThat(result.total()).isZero();
        assertThat(result.rows()).isEmpty();
        verify(identityResolver).resolveCurrentIdentity();
        verify(processQuery, never()).startedBy(anyString());
        verify(processQuery, never()).listPage(any(Integer.class), any(Integer.class));
    }

    /**
     * 验证管理员实例查询完整下推实例、定义、分类、业务、发起人和时间条件，并返回用户名称与活动节点。
     *
     * @return 无返回值，任一筛选、分页或结果富化契约漂移时测试失败
     */
    @Test
    void filtersAndEnrichesManagedProcessesAtPageBoundary()
    {
        Instant startedAfter = Instant.parse("2026-07-25T08:00:00Z");
        Instant startedBefore = Instant.parse("2026-07-26T08:00:00Z");
        HistoricProcessInstance instance = historicInstance(
                "instance-9", "definition-9", "deployment-9");
        when(instance.getProcessDefinitionKey()).thenReturn("leave");
        when(instance.getProcessDefinitionName()).thenReturn("请假流程");
        when(instance.getProcessDefinitionVersion()).thenReturn(3);
        when(instance.getProcessDefinitionCategory()).thenReturn("hr");
        when(instance.getBusinessKey()).thenReturn("business-9");
        when(instance.getStartTime()).thenReturn(Date.from(
                Instant.parse("2026-07-25T09:00:00Z")));
        when(instance.getState()).thenReturn("RUNNING");
        when(processQuery.count()).thenReturn(11L);
        when(processQuery.listPage(10, 10)).thenReturn(List.of(instance));
        Task first = mock(Task.class);
        Task second = mock(Task.class);
        when(first.getName()).thenReturn("部门审批");
        when(second.getName()).thenReturn("财务审批");
        when(taskQuery.count()).thenReturn(2L);
        when(taskQuery.listPage(0, 2)).thenReturn(List.of(first, second));
        SysUser startUser = new SysUser();
        startUser.setUserId(9L);
        startUser.setNickName("跨用户发起人");
        when(userService.selectUserById(9L)).thenReturn(startUser);
        WorkflowManagedProcessQueryDto filter = new WorkflowManagedProcessQueryDto(
                "instance-9", "leave", "请假", "hr", "business-9", "009",
                startedAfter, startedBefore);

        WorkflowPageResult<WorkflowManagedProcessView> result =
                service.listManaged(filter, 2, 10);

        assertThat(result.total()).isEqualTo(11L);
        assertThat(result.rows()).singleElement().satisfies(row ->
        {
            assertThat(row.processInstanceId()).isEqualTo("instance-9");
            assertThat(row.definitionId()).isEqualTo("definition-9");
            assertThat(row.businessKey()).isEqualTo("business-9");
            assertThat(row.startUserId()).isEqualTo("9");
            assertThat(row.startUserName()).isEqualTo("跨用户发起人");
            assertThat(row.processStatus()).isEqualTo("running");
            assertThat(row.currentTaskNames()).containsExactly("部门审批", "财务审批");
        });
        verify(processQuery).processInstanceId("instance-9");
        verify(processQuery).processDefinitionKey("leave");
        verify(processQuery).processDefinitionNameLike("%请假%");
        verify(processQuery).processDefinitionCategory("hr");
        verify(processQuery).processInstanceBusinessKey("business-9");
        verify(processQuery).startedBy("9");
        verify(processQuery, never()).startedBy("7");
        verify(processQuery).startedAfter(Date.from(startedAfter));
        verify(processQuery).startedBefore(Date.from(startedBefore));
        verify(processQuery).listPage(10, 10);
        verify(taskQuery).processInstanceId("instance-9");
        verify(userService).selectUserById(9L);
    }

    /**
     * 验证实例挂起状态以运行时事实覆盖历史 RUNNING，且挂起任务仍作为当前环节返回。
     *
     * @return 无返回值，列表误报运行中或丢失挂起任务名称时测试失败
     */
    @Test
    void reportsRuntimeSuspensionAndKeepsSuspendedTaskName()
    {
        HistoricProcessInstance instance = historicInstance(
                "instance-suspended", "definition-9", "deployment-9");
        when(instance.getProcessDefinitionKey()).thenReturn("leave");
        when(instance.getProcessDefinitionName()).thenReturn("请假流程");
        when(instance.getProcessDefinitionVersion()).thenReturn(3);
        when(instance.getStartUserId()).thenReturn("9");
        when(instance.getState()).thenReturn("RUNNING");
        when(instance.getBusinessStatus()).thenReturn("running");
        when(processQuery.count()).thenReturn(1L);
        when(processQuery.listPage(0, 10)).thenReturn(List.of(instance));

        ProcessInstance runtimeInstance = mock(ProcessInstance.class);
        when(runtimeInstance.getId()).thenReturn("instance-suspended");
        when(runtimeInstance.isSuspended()).thenReturn(true);
        when(runtimeProcessQuery.list()).thenReturn(List.of(runtimeInstance));

        Task suspendedTask = mock(Task.class);
        when(suspendedTask.getName()).thenReturn("部门审批");
        when(taskQuery.count()).thenReturn(1L);
        when(taskQuery.listPage(0, 1)).thenReturn(List.of(suspendedTask));
        SysUser startUser = new SysUser();
        startUser.setUserId(9L);
        startUser.setNickName("流程发起人");
        when(userService.selectUserById(9L)).thenReturn(startUser);

        WorkflowPageResult<WorkflowManagedProcessView> result =
                service.listManaged(null, 1, 10);

        assertThat(result.rows()).singleElement().satisfies(row ->
        {
            assertThat(row.processStatus()).isEqualTo("suspended");
            assertThat(row.currentTaskNames()).containsExactly("部门审批");
        });
        verify(runtimeProcessQuery).processInstanceIds(Set.of("instance-suspended"));
        verify(taskQuery).processInstanceId("instance-suspended");
        verify(taskQuery, never()).active();
    }

    /**
     * 验证管理员发起人筛选只接受可规范化为正 Long 的若依用户主键。
     *
     * @return 无返回值，非法用户主键进入 Flowable 查询时测试失败
     */
    @Test
    void rejectsInvalidManagedStartUserIds()
    {
        for (String invalidUserId : List.of("abc", "0", "-1", "9223372036854775808"))
        {
            WorkflowManagedProcessQueryDto filter = new WorkflowManagedProcessQueryDto(
                    null, null, null, null, null, invalidUserId, null, null);
            assertThatThrownBy(() -> service.listManaged(filter, 1, 10))
                    .isInstanceOfSatisfying(ServiceException.class, exception ->
                    {
                        assertThat(exception.getCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(exception.getMessage()).isEqualTo("发起人主键不合法");
                    });
        }

        verify(processQuery, never()).count();
        verify(processQuery, never()).startedBy(anyString());
    }

    /**
     * 验证无待办时返回空页和真实零 total，且不执行无意义 listPage。
     *
     * @return 无返回值，断言空结果分支
     */
    @Test
    void returnsEmptyAssignedPageWithoutListingRows()
    {
        when(taskQuery.count()).thenReturn(0L);

        WorkflowPageResult<WorkflowAssignedTaskView> result =
                service.listAssigned(null, 1, 10);

        assertThat(result.total()).isZero();
        assertThat(result.rows()).isEmpty();
        verify(taskQuery).taskAssignee("7");
        verify(taskQuery, never()).listPage(any(Integer.class), any(Integer.class));
    }

    /**
     * 验证活动待办严格按当前 assignee 查询，并从服务端关联定义和实例。
     *
     * @return 无返回值，断言正常待办视图和身份范围
     */
    @Test
    void listsAssignedTaskUsingServerIdentity()
    {
        Task task = task("task-1", "definition-1", "instance-1");
        when(task.getAssignee()).thenReturn("7");
        when(task.getClaimedBy()).thenReturn("7");
        when(task.getClaimTime()).thenReturn(Date.from(
                Instant.parse("2026-07-25T10:20:00Z")));
        when(taskQuery.count()).thenReturn(1L);
        when(taskQuery.listPage(0, 10)).thenReturn(List.of(task));
        ProcessDefinition definition = stubTaskContext(
                "definition-1", "instance-1", "deploy-1");
        when(definition.getCategory()).thenReturn("http://ruoyi.example/workflow");
        Deployment deployment = mock(Deployment.class);
        when(deployment.getId()).thenReturn("deploy-1");
        when(deployment.getCategory()).thenReturn("business");
        when(deploymentQuery.singleResult()).thenReturn(deployment);

        WorkflowPageResult<WorkflowAssignedTaskView> result =
                service.listAssigned(null, 1, 10);

        assertThat(result.rows()).singleElement().satisfies(row ->
        {
            assertThat(row.assigneeId()).isEqualTo("7");
            assertThat(row.claimedById()).isEqualTo("7");
            assertThat(row.claimTime()).isEqualTo(
                    Instant.parse("2026-07-25T10:20:00Z"));
            assertThat(row.category()).isEqualTo("business");
        });
        verify(taskQuery).active();
        verify(taskQuery).taskAssignee("7");
    }

    /**
     * 验证待办按正式部署分类筛选，不使用保存命名空间的流程定义分类。
     *
     * @return 无返回值，待办部署范围未写入原生查询时测试失败
     */
    @Test
    void filtersAssignedTasksByDeploymentCategory()
    {
        stubCategoryDeployment("business", "deploy-1");
        Task task = task("task-business", "definition-1", "instance-1");
        when(task.getAssignee()).thenReturn("7");
        when(taskQuery.count()).thenReturn(1L);
        when(taskQuery.listPage(0, 10)).thenReturn(List.of(task));
        ProcessDefinition definition = stubTaskContext(
                "definition-1", "instance-1", "deploy-1");
        when(definition.getCategory()).thenReturn("http://ruoyi.example/workflow");

        WorkflowPageResult<WorkflowAssignedTaskView> result = service.listAssigned(
                new WorkflowAssignedTaskQueryDto(
                        null, null, "business", null, null, null), 1, 10);

        assertThat(result.rows()).singleElement()
                .extracting(WorkflowAssignedTaskView::category)
                .isEqualTo("business");
        verify(taskQuery).deploymentIdIn(Set.of("deploy-1"));
        verify(taskQuery, never()).processCategoryIn(any());
    }

    /**
     * 验证分类没有正式部署时四类列表都返回真实空页，不让空集合退化为全量查询。
     *
     * @return 无返回值，任一列表仍执行 count 或分页读取时测试失败
     */
    @Test
    void returnsEmptyPagesWhenCategoryHasNoDeployment()
    {
        when(deploymentQuery.list()).thenReturn(List.of());

        WorkflowPageResult<WorkflowStartableDefinitionView> startable = service.listStartable(
                new WorkflowStartableProcessQueryDto(null, null, "missing"), 1, 10);
        WorkflowPageResult<WorkflowAssignedTaskView> assigned = service.listAssigned(
                new WorkflowAssignedTaskQueryDto(
                        null, null, "missing", null, null, null), 1, 10);
        WorkflowPageResult<WorkflowClaimableTaskView> claimable = service.listClaimable(
                new WorkflowClaimableTaskQueryDto(
                        null, null, "missing", null, null, null), 1, 10);
        WorkflowPageResult<WorkflowCompletedTaskView> completed = service.listCompleted(
                new WorkflowCompletedTaskQueryDto(
                        null, null, "missing", null, null, null), 1, 10);

        assertThat(List.of(startable, assigned, claimable, completed)).allSatisfy(result ->
        {
            assertThat(result.total()).isZero();
            assertThat(result.rows()).isEmpty();
        });
        verify(deploymentQuery, times(4)).deploymentCategory("missing");
        verify(definitionQuery, never()).count();
        verify(taskQuery, never()).count();
        verify(historicTaskQuery, never()).count();
    }

    /**
     * 验证部署分类缺失时不回退定义或历史中的旧分类，保证展示与筛选口径一致。
     *
     * @return 无返回值，旧分类作为不可筛选的页面分类返回时测试失败
     */
    @Test
    void doesNotFallbackToLegacyCategoryWhenDeploymentCategoryIsMissing()
    {
        Task task = task("task-legacy", "definition-legacy", "instance-legacy");
        when(task.getAssignee()).thenReturn("7");
        when(taskQuery.count()).thenReturn(1L);
        when(taskQuery.listPage(0, 10)).thenReturn(List.of(task));
        ProcessDefinition definition = stubTaskContext(
                "definition-legacy", "instance-legacy", "deploy-legacy");
        when(definition.getCategory()).thenReturn("legacy-business");
        HistoricProcessInstance instance = historicInstance(
                "instance-legacy", "definition-legacy", "deploy-legacy");
        when(instance.getProcessDefinitionCategory()).thenReturn("legacy-business");
        when(processQuery.singleResult()).thenReturn(instance);
        Deployment deployment = mock(Deployment.class);
        when(deployment.getId()).thenReturn("deploy-legacy");
        when(deployment.getCategory()).thenReturn(" ");
        when(deploymentQuery.singleResult()).thenReturn(deployment);

        WorkflowPageResult<WorkflowAssignedTaskView> result =
                service.listAssigned(null, 1, 10);

        assertThat(result.rows()).singleElement()
                .extracting(WorkflowAssignedTaskView::category)
                .isNull();
    }

    /**
     * 验证不存在的分类不能绕过其他查询参数校验，非法请求必须稳定返回 400。
     *
     * @return 无返回值，任一非法过滤条件被空分类短路为成功响应时测试失败
     */
    @Test
    void validatesFiltersBeforeMissingCategoryShortCircuit()
    {
        String overlongName = "x".repeat(256);
        Instant later = Instant.parse("2026-07-26T10:00:00Z");
        Instant earlier = Instant.parse("2026-07-25T10:00:00Z");

        assertCode(() -> service.listStartable(
                new WorkflowStartableProcessQueryDto(null, overlongName, "missing"), 1, 10),
                HttpStatus.BAD_REQUEST);
        assertCode(() -> service.listAssigned(
                new WorkflowAssignedTaskQueryDto(
                        null, null, "missing", null, later, earlier), 1, 10),
                HttpStatus.BAD_REQUEST);
        assertCode(() -> service.listClaimable(
                new WorkflowClaimableTaskQueryDto(
                        null, null, "missing", null, later, earlier), 1, 10),
                HttpStatus.BAD_REQUEST);
        assertCode(() -> service.listCompleted(
                new WorkflowCompletedTaskQueryDto(
                        null, null, "missing", null, later, earlier), 1, 10),
                HttpStatus.BAD_REQUEST);

        verify(repositoryService, never()).createDeploymentQuery();
    }

    /**
     * 验证待签查询同时使用当前用户和有效 ROLE/DEPT 组，并拒绝返回已有办理人的任务。
     *
     * @return 无返回值，断言候选组并集和未分配状态
     */
    @Test
    void listsClaimableTaskForCurrentCandidateGroups()
    {
        Task task = task("task-claim", "definition-1", "instance-1");
        when(task.getAssignee()).thenReturn(null);
        when(taskQuery.count()).thenReturn(1L);
        when(taskQuery.listPage(0, 10)).thenReturn(List.of(task));
        stubTaskContext("definition-1", "instance-1", "deploy-1");

        WorkflowPageResult<WorkflowClaimableTaskView> result = service.listClaimable(
                new WorkflowClaimableTaskQueryDto(null, null, null, null, null, null), 1, 10);

        assertThat(result.rows()).singleElement().satisfies(row ->
        {
            assertThat(row.taskId()).isEqualTo("task-claim");
            assertThat(row.category()).isEqualTo("business");
        });
        verify(taskQuery).taskUnassigned();
        verify(taskQuery).taskCandidateUser("7");
        verify(taskQuery).taskCandidateGroupIn(Set.of("ROLE2", "DEPT3"));
    }

    /**
     * 验证待签任务使用正式部署分类范围，候选身份条件与业务分类条件同时生效。
     *
     * @return 无返回值，待签仍按流程定义分类筛选时测试失败
     */
    @Test
    void filtersClaimableTasksByDeploymentCategory()
    {
        stubCategoryDeployment("business", "deploy-1");
        Task task = task("task-claim-business", "definition-1", "instance-1");
        when(task.getAssignee()).thenReturn(null);
        when(taskQuery.count()).thenReturn(1L);
        when(taskQuery.listPage(0, 10)).thenReturn(List.of(task));
        ProcessDefinition definition = stubTaskContext(
                "definition-1", "instance-1", "deploy-1");
        when(definition.getCategory()).thenReturn("http://ruoyi.example/workflow");

        WorkflowPageResult<WorkflowClaimableTaskView> result = service.listClaimable(
                new WorkflowClaimableTaskQueryDto(
                        null, null, "business", null, null, null), 1, 10);

        assertThat(result.rows()).singleElement()
                .extracting(WorkflowClaimableTaskView::category)
                .isEqualTo("business");
        verify(taskQuery).deploymentIdIn(Set.of("deploy-1"));
        verify(taskQuery, never()).processCategoryIn(any());
    }

    /**
     * 验证只有待签菜单但缺少完整认领资格的用户得到真实空页，且不会扫描候选任务。
     *
     * @return 无返回值；不可执行任务泄漏到待签列表时测试失败
     */
    @Test
    void hidesClaimableTasksFromClaimIneligibleCurrentUser()
    {
        when(identityResolver.resolveClaimEligibleUserIds(List.of("7")))
                .thenReturn(Set.of());

        WorkflowPageResult<WorkflowClaimableTaskView> result = service.listClaimable(
                null, 1, 10);

        assertThat(result.total()).isZero();
        assertThat(result.rows()).isEmpty();
        verify(taskService, never()).createTaskQuery();
    }

    /**
     * 验证已办列表使用 Flowable completedBy，而不是可能在委派后失真的 assignee。
     *
     * @return 无返回值，断言真实完成人过滤和历史视图
     */
    @Test
    void listsTasksActuallyCompletedByCurrentUser()
    {
        HistoricTaskInstance task = mock(HistoricTaskInstance.class);
        when(task.getId()).thenReturn("task-finished");
        when(task.getName()).thenReturn("审批");
        when(task.getTaskDefinitionKey()).thenReturn("approve");
        when(task.getProcessDefinitionId()).thenReturn("definition-1");
        when(task.getProcessInstanceId()).thenReturn("instance-1");
        when(task.getCompletedBy()).thenReturn("7");
        when(task.getAssignee()).thenReturn("9");
        when(historicTaskQuery.count()).thenReturn(1L);
        when(historicTaskQuery.listPage(0, 10)).thenReturn(List.of(task));
        stubTaskContext("definition-1", "instance-1", "deploy-1");
        when(taskLifecycleService.isProcessRevocable("instance-1", "task-finished"))
                .thenReturn(true);

        WorkflowPageResult<WorkflowCompletedTaskView> result = service.listCompleted(
                new WorkflowCompletedTaskQueryDto(null, null, null, null, null, null), 1, 10);

        assertThat(result.rows().get(0).completedBy()).isEqualTo("7");
        assertThat(result.rows().get(0).assigneeId()).isEqualTo("9");
        assertThat(result.rows().get(0).category()).isEqualTo("business");
        assertThat(result.rows().get(0).revocable()).isTrue();
        verify(historicTaskQuery).taskCompletedBy("7");
        verify(historicTaskQuery, never()).taskAssignee("7");
        verify(taskLifecycleService).isProcessRevocable("instance-1", "task-finished");
    }

    /**
     * 验证已办任务按正式部署分类筛选，并保留真实完成人约束。
     *
     * @return 无返回值，历史任务仍按定义分类筛选时测试失败
     */
    @Test
    void filtersCompletedTasksByDeploymentCategory()
    {
        stubCategoryDeployment("business", "deploy-1");
        HistoricTaskInstance task = mock(HistoricTaskInstance.class);
        when(task.getId()).thenReturn("task-finished-business");
        when(task.getName()).thenReturn("审批");
        when(task.getProcessDefinitionId()).thenReturn("definition-1");
        when(task.getProcessInstanceId()).thenReturn("instance-1");
        when(task.getCompletedBy()).thenReturn("7");
        when(historicTaskQuery.count()).thenReturn(1L);
        when(historicTaskQuery.listPage(0, 10)).thenReturn(List.of(task));
        ProcessDefinition definition = stubTaskContext(
                "definition-1", "instance-1", "deploy-1");
        when(definition.getCategory()).thenReturn("http://ruoyi.example/workflow");

        WorkflowPageResult<WorkflowCompletedTaskView> result = service.listCompleted(
                new WorkflowCompletedTaskQueryDto(
                        null, null, "business", null, null, null), 1, 10);

        assertThat(result.rows()).singleElement()
                .extracting(WorkflowCompletedTaskView::category)
                .isEqualTo("business");
        verify(historicTaskQuery).taskCompletedBy("7");
        verify(historicTaskQuery).deploymentIdIn(Set.of("deploy-1"));
        verify(historicTaskQuery, never()).processCategoryIn(any());
    }

    /**
     * 验证抄送分页使用服务端当前用户、真实 offset 和允许字段，不接受客户端 userId。
     *
     * @return 无返回值，断言正式 Mapper 分页和筛选转换
     */
    @Test
    void listsCopiesForServerResolvedRecipientAtPageBoundary()
    {
        WfCopy copy = new WfCopy();
        copy.setCopyId(11L);
        copy.setUserId(7L);
        copy.setProcessName("请假流程");
        when(copyMapper.countListByUserId(eq(7L), any(WfCopy.class))).thenReturn(11L);
        when(copyMapper.selectPageByUserId(eq(7L), any(WfCopy.class), eq(10), eq(10)))
                .thenReturn(List.of(copy));

        WorkflowPageResult<WorkflowCopyView> result = service.listCopies(
                new WorkflowCopyQueryDto(null, "definition-1", "请假", "张三",
                        null, null, null, null),
                2, 10);

        assertThat(result.total()).isEqualTo(11);
        assertThat(result.rows()).singleElement()
                .extracting(WorkflowCopyView::userId)
                .isEqualTo(7L);
        ArgumentCaptor<WfCopy> filterCaptor = ArgumentCaptor.forClass(WfCopy.class);
        verify(copyMapper).countListByUserId(eq(7L), filterCaptor.capture());
        assertThat(filterCaptor.getValue().getUserId()).isNull();
        assertThat(filterCaptor.getValue().getProcessId()).isEqualTo("definition-1");
        assertThat(filterCaptor.getValue().getProcessName()).isEqualTo("请假");
        assertThat(filterCaptor.getValue().getOriginatorName()).isEqualTo("张三");
    }

    /**
     * 验证 Mapper 若意外返回其他接收人的抄送记录，服务层以数据异常停止而不泄露正文。
     *
     * @return 无返回值，断言防御性接收人复核
     */
    @Test
    void rejectsCopyRowBelongingToAnotherRecipient()
    {
        WfCopy copy = new WfCopy();
        copy.setUserId(8L);
        when(copyMapper.countListByUserId(eq(7L), any(WfCopy.class))).thenReturn(1L);
        when(copyMapper.selectPageByUserId(eq(7L), any(WfCopy.class), eq(0), eq(10)))
                .thenReturn(List.of(copy));

        assertCode(() -> service.listCopies(null, 1, 10), HttpStatus.ERROR);
    }

    /**
     * 验证首次阅读更新和回读始终携带当前认证用户，且返回数据库首次时间。
     * @return void，用户范围、状态或时间未形成闭环时测试失败
     */
    @Test
    void atomicallyMarksCurrentRecipientsFirstReadTime()
    {
        WfCopy copy = new WfCopy();
        copy.setCopyId(11L);
        copy.setUserId(7L);
        copy.setReadStatus("1");
        copy.setReadTime(Date.from(Instant.parse("2026-08-08T08:00:00Z")));
        when(copyMapper.markRead(11L, 7L, "7")).thenReturn(1);
        when(copyMapper.selectByIdAndUserId(11L, 7L)).thenReturn(copy);

        WorkflowCopyView result = service.markCopyRead(11L);

        assertThat(result.readStatus()).isEqualTo("1");
        assertThat(result.readTime()).isEqualTo(Instant.parse("2026-08-08T08:00:00Z"));
        verify(copyMapper).markRead(11L, 7L, "7");
        verify(copyMapper).selectByIdAndUserId(11L, 7L);
    }

    /**
     * 验证越权和不存在记录在所有者限定更新后返回同一 404，不泄露记录存在性。
     * @return void，越权查询扩大到无 userId 条件时测试失败
     */
    @Test
    void hidesForeignCopyWhenMarkingRead()
    {
        when(copyMapper.markRead(19L, 7L, "7")).thenReturn(0);
        when(copyMapper.selectByIdAndUserId(19L, 7L)).thenReturn(null);

        assertCode(() -> service.markCopyRead(19L), HttpStatus.NOT_FOUND);

        verify(copyMapper, never()).selectById(19L);
    }

    /**
     * 验证首次发起只返回 BPMN 开始节点对应的 Flowable 表单制品不可变快照。
     *
     * @return 无返回值，断言定义/部署/发起权限和快照内容关系
     */
    @Test
    void returnsStartFormFromImmutableDeploymentSnapshot()
    {
        ProcessDefinition definition = definition("definition-1", "leave", "deploy-1", false);
        when(repositoryService.getProcessDefinition("definition-1")).thenReturn(definition);
        when(definitionQuery.singleResult()).thenReturn(definition);
        when(repositoryService.getIdentityLinksForProcessDefinition("definition-1"))
                .thenReturn(List.of());
        BpmnModel model = startFormModel("leave");
        addStartMultiInstanceTask(model, "jointReview", "联合会签");
        when(repositoryService.getBpmnModel("definition-1")).thenReturn(model);
        WfDeployForm snapshot = new WfDeployForm();
        snapshot.setDeployId("deploy-1");
        snapshot.setSourceType("TEMPLATE");
        snapshot.setFormId(12L);
        snapshot.setFormKey("key_12");
        snapshot.setNodeKey("start");
        snapshot.setFormName("请假表单");
        snapshot.setContent("{\"fields\":[]}");
        when(artifactRepository.selectForms("deploy-1")).thenReturn(List.of(snapshot));

        WorkflowProcessFormView result = service.getProcessForm(
                new WorkflowProcessFormQueryDto("definition-1", "deploy-1", null));

        assertThat(result.formId()).isEqualTo(12L);
        assertThat(result.content()).isEqualTo("{\"fields\":[]}");
        assertThat(result.startMultiInstanceAssignments())
                .singleElement()
                .satisfies(assignment ->
                {
                    assertThat(assignment.activityId()).isEqualTo("jointReview");
                    assertThat(assignment.activityName()).isEqualTo("联合会签");
                    assertThat(assignment.mode()).isEqualTo("ALL");
                    assertThat(assignment.minUsers()).isEqualTo(1);
                    assertThat(assignment.maxUsers()).isEqualTo(100);
                });
        verify(artifactRepository).selectForms("deploy-1");
    }

    /**
     * 验证正式表单节点携带字段权限 FormProperty 时仍按模板来源读取部署快照。
     *
     * @return 无返回值，权限描述不得被误判为第二份 BPMN 内嵌表单
     */
    @Test
    void returnsTemplateStartFormWhenNodeContainsPermissionProperties()
    {
        ProcessDefinition definition = definition("definition-1", "leave", "deploy-1", false);
        when(repositoryService.getProcessDefinition("definition-1")).thenReturn(definition);
        when(definitionQuery.singleResult()).thenReturn(definition);
        when(repositoryService.getIdentityLinksForProcessDefinition("definition-1"))
                .thenReturn(List.of());
        BpmnModel model = startFormModel("leave");
        StartEvent start = (StartEvent) model.getProcessById("leave")
                .getFlowElement("start", false);
        FormProperty defaultPermission = new FormProperty();
        defaultPermission.setId("approva_permission_default");
        FormProperty fieldPermission = new FormProperty();
        fieldPermission.setId("approva_permission_field_1");
        fieldPermission.setVariable("requestTitle");
        start.setFormProperties(List.of(defaultPermission, fieldPermission));
        when(repositoryService.getBpmnModel("definition-1")).thenReturn(model);

        WfDeployForm snapshot = new WfDeployForm();
        snapshot.setDeployId("deploy-1");
        snapshot.setSourceType("TEMPLATE");
        snapshot.setFormId(12L);
        snapshot.setFormKey("key_12");
        snapshot.setNodeKey("start");
        snapshot.setFormName("请假表单");
        snapshot.setContent("{\"fields\":[]}");
        when(artifactRepository.selectForms("deploy-1")).thenReturn(List.of(snapshot));

        WorkflowProcessFormView result = service.getProcessForm(
                new WorkflowProcessFormQueryDto("definition-1", "deploy-1", null));

        assertThat(result.formId()).isEqualTo(12L);
        assertThat(result.content()).isEqualTo("{\"fields\":[]}");
    }

    /**
     * 验证正式表单节点混入普通 FormData 时继续拒绝双重表单来源。
     *
     * @return 无返回值，非法模型必须在读取部署快照前失败
     */
    @Test
    void rejectsTemplateStartFormMixedWithEmbeddedFormProperty()
    {
        ProcessDefinition definition = definition("definition-1", "leave", "deploy-1", false);
        when(repositoryService.getProcessDefinition("definition-1")).thenReturn(definition);
        when(definitionQuery.singleResult()).thenReturn(definition);
        when(repositoryService.getIdentityLinksForProcessDefinition("definition-1"))
                .thenReturn(List.of());
        BpmnModel model = startFormModel("leave");
        StartEvent start = (StartEvent) model.getProcessById("leave")
                .getFlowElement("start", false);
        FormProperty embeddedField = new FormProperty();
        embeddedField.setId("requestTitle");
        embeddedField.setVariable("requestTitle");
        start.setFormProperties(List.of(embeddedField));
        when(repositoryService.getBpmnModel("definition-1")).thenReturn(model);

        assertCode(() -> service.getProcessForm(
                new WorkflowProcessFormQueryDto("definition-1", "deploy-1", null)),
                HttpStatus.ERROR);

        verify(artifactRepository, never()).selectForms(any());
    }

    /**
     * 验证内嵌 FormData 后续发生模型修改时，发起页仍只返回部署时冻结的渲染快照。
     *
     * @return 无返回值，模型当前字段不得覆盖 forms-v1.json 中的不可变内容
     */
    @Test
    void returnsFrozenEmbeddedStartFormAfterModelPropertiesChange()
    {
        ProcessDefinition definition = definition("definition-1", "leave", "deploy-1", false);
        when(repositoryService.getProcessDefinition("definition-1")).thenReturn(definition);
        when(definitionQuery.singleResult()).thenReturn(definition);
        when(repositoryService.getIdentityLinksForProcessDefinition("definition-1"))
                .thenReturn(List.of());
        BpmnModel model = startFormModel("leave");
        StartEvent start = (StartEvent) model.getProcessById("leave")
                .getFlowElement("start", false);
        start.setFormKey(null);
        FormProperty changedProperty = new FormProperty();
        changedProperty.setId("changedAfterDeployment");
        changedProperty.setVariable("changedAfterDeployment");
        changedProperty.setType("string");
        start.setFormProperties(List.of(changedProperty));
        when(repositoryService.getBpmnModel("definition-1")).thenReturn(model);

        String frozenContent = "{\"fields\":[{\"__vModel__\":\"originalField\","
                + "\"__config__\":{\"layout\":\"colFormItem\",\"tag\":\"el-input\"}}]}";
        WfDeployForm snapshot = new WfDeployForm();
        snapshot.setDeployId("deploy-1");
        snapshot.setSourceType("EMBEDDED");
        snapshot.setFormId(null);
        snapshot.setFormKey("embedded");
        snapshot.setNodeKey("start");
        snapshot.setFormName("部署时内嵌表单");
        snapshot.setContent(frozenContent);
        when(artifactRepository.selectForms("deploy-1")).thenReturn(List.of(snapshot));

        WorkflowProcessFormView result = service.getProcessForm(
                new WorkflowProcessFormQueryDto("definition-1", "deploy-1", null));

        assertThat(result.sourceType()).isEqualTo("EMBEDDED");
        assertThat(result.formId()).isNull();
        assertThat(result.formKey()).isEqualTo("embedded");
        assertThat(result.content()).isEqualTo(frozenContent)
                .contains("originalField")
                .doesNotContain("changedAfterDeployment");
    }

    /**
     * 验证实例场景先复用对象授权，并拒绝客户端伪造实例与定义关联。
     *
     * @return 无返回值，断言 409 和不读取表单快照
     */
    @Test
    void rejectsForgedInstanceDefinitionRelationshipForForm()
    {
        ProcessDefinition definition = definition("definition-1", "leave", "deploy-1", true);
        when(repositoryService.getProcessDefinition("definition-1")).thenReturn(definition);
        when(processAccessService.requireReadableInstance("instance-1"))
                .thenReturn(accessSnapshot("instance-1", "definition-other", "deploy-1"));

        assertCode(() -> service.getProcessForm(
                new WorkflowProcessFormQueryDto("definition-1", "deploy-1", "instance-1")),
                HttpStatus.CONFLICT);

        verify(processAccessService).requireReadableInstance("instance-1");
        verify(artifactRepository, never()).selectForms(any());
    }

    /**
     * 验证详情 BPMN 在对象授权及真实定义关系通过后复用安全部署服务读取。
     *
     * @return 无返回值，断言对象可见性分支和安全 XML 服务
     */
    @Test
    void readsBpmnForAuthorizedInstanceThroughSafeService()
    {
        ProcessDefinition definition = definition("definition-1", "leave", "deploy-1", true);
        when(repositoryService.getProcessDefinition("definition-1")).thenReturn(definition);
        when(processAccessService.requireReadableInstance("instance-1"))
                .thenReturn(accessSnapshot("instance-1", "definition-1", "deploy-1"));
        when(deploymentService.getBpmnXml("definition-1")).thenReturn("<definitions/>");

        String xml = service.getBpmnXml(
                new WorkflowBpmnXmlQueryDto("definition-1", "instance-1"));

        assertThat(xml).isEqualTo("<definitions/>");
        verify(processAccessService).requireReadableInstance("instance-1");
        verify(deploymentService).getBpmnXml("definition-1");
        verify(identityResolver, never()).resolveCurrentIdentity();
    }

    /**
     * 验证发起场景没有 starter 身份命中时禁止读取 BPMN XML。
     *
     * @return 无返回值，断言 403 且不触达 XML 正文
     */
    @Test
    void deniesBpmnWhenDefinitionIsNotStartable()
    {
        ProcessDefinition definition = definition("definition-1", "leave", "deploy-1", false);
        when(repositoryService.getProcessDefinition("definition-1")).thenReturn(definition);
        when(definitionQuery.singleResult()).thenReturn(definition);
        IdentityLink deniedLink = groupLink("ROLE9");
        when(repositoryService.getIdentityLinksForProcessDefinition("definition-1"))
                .thenReturn(List.of(deniedLink));

        assertCode(() -> service.getBpmnXml(
                new WorkflowBpmnXmlQueryDto("definition-1", null)), HttpStatus.FORBIDDEN);

        verify(deploymentService, never()).getBpmnXml(any());
    }

    /**
     * 验证非法分页和反向时间范围返回 400，不把边界错误交给 Flowable。
     *
     * @return 无返回值，断言分页及时间异常分支
     */
    @Test
    void rejectsInvalidPaginationAndTimeRange()
    {
        assertCode(() -> service.listAssigned(null, 1, 201), HttpStatus.BAD_REQUEST);
        WorkflowAssignedTaskQueryDto reversed = new WorkflowAssignedTaskQueryDto(
                null, null, null, null,
                Instant.parse("2026-07-26T10:00:00Z"),
                Instant.parse("2026-07-25T10:00:00Z"));
        assertCode(() -> service.listAssigned(reversed, 1, 10), HttpStatus.BAD_REQUEST);
    }

    /**
     * 创建测试流程定义并固化关键关系字段。
     *
     * @param id String，流程定义主键
     * @param key String，流程定义标识
     * @param deploymentId String，部署主键
     * @param suspended boolean，是否挂起
     * @return ProcessDefinition，测试流程定义 mock
     */
    private ProcessDefinition definition(String id, String key, String deploymentId,
            boolean suspended)
    {
        ProcessDefinition definition = mock(ProcessDefinition.class);
        when(definition.getId()).thenReturn(id);
        when(definition.getKey()).thenReturn(key);
        when(definition.getName()).thenReturn(key + "流程");
        when(definition.getVersion()).thenReturn(1);
        when(definition.getCategory()).thenReturn("business");
        when(definition.getDeploymentId()).thenReturn(deploymentId);
        when(definition.getTenantId()).thenReturn("");
        when(definition.isSuspended()).thenReturn(suspended);
        return definition;
    }

    /**
     * 创建指定候选组的流程定义 identity link。
     *
     * @param groupId String，ROLE 或 DEPT 候选组编码
     * @return IdentityLink，候选组 identity link mock
     */
    private IdentityLink groupLink(String groupId)
    {
        IdentityLink link = mock(IdentityLink.class);
        when(link.getGroupId()).thenReturn(groupId);
        return link;
    }

    /**
     * 创建含唯一开始节点和表单键的 BPMN 公共模型。
     *
     * @param processKey String，BPMN Process 主键
     * @return BpmnModel，可用于开始表单关系核验的模型
     */
    private BpmnModel startFormModel(String processKey)
    {
        BpmnModel model = new BpmnModel();
        org.flowable.bpmn.model.Process process = new org.flowable.bpmn.model.Process();
        process.setId(processKey);
        StartEvent startEvent = new StartEvent();
        startEvent.setId("start");
        startEvent.setFormKey("key_12");
        process.addFlowElement(startEvent);
        model.addProcess(process);
        return model;
    }

    /**
     * 向开始表单测试模型加入由发起页选择成员的受控会签节点。
     *
     * @param model BpmnModel，已经包含可执行主流程的模型。
     * @param activityId String，会签用户任务节点标识。
     * @param activityName String，会签用户任务显示名称。
     * @return 无返回值；节点直接加入模型中的主流程。
     */
    private void addStartMultiInstanceTask(BpmnModel model, String activityId,
            String activityName)
    {
        UserTask task = new UserTask();
        task.setId(activityId);
        task.setName(activityName);
        task.setAssignee(WorkflowMultiInstanceModelContract.ASSIGNEE_EXPRESSION);
        MultiInstanceLoopCharacteristics loop = new MultiInstanceLoopCharacteristics();
        loop.setInputDataItem(WorkflowMultiInstanceModelContract.START_COLLECTION_EXPRESSION);
        loop.setElementVariable(WorkflowMultiInstanceModelContract.ELEMENT_VARIABLE);
        loop.setCompletionCondition(
                WorkflowMultiInstanceModelContract.ALL_COMPLETION_CONDITION);
        task.setLoopCharacteristics(loop);
        model.getProcesses().get(0).addFlowElement(task);
    }

    /**
     * 创建活动任务测试对象。
     *
     * @param taskId String，任务主键
     * @param definitionId String，流程定义主键
     * @param instanceId String，流程实例主键
     * @return Task，活动任务 mock
     */
    private Task task(String taskId, String definitionId, String instanceId)
    {
        Task task = mock(Task.class);
        when(task.getId()).thenReturn(taskId);
        when(task.getName()).thenReturn("审批");
        when(task.getTaskDefinitionKey()).thenReturn("approve");
        when(task.getProcessDefinitionId()).thenReturn(definitionId);
        when(task.getProcessInstanceId()).thenReturn(instanceId);
        return task;
    }

    /**
     * 创建历史流程实例测试对象并固化定义、部署和发起人关系。
     *
     * @param instanceId String，流程实例主键
     * @param definitionId String，流程定义主键
     * @param deploymentId String，部署主键
     * @return HistoricProcessInstance，历史流程实例 mock
     */
    private HistoricProcessInstance historicInstance(String instanceId, String definitionId,
            String deploymentId)
    {
        HistoricProcessInstance instance = mock(HistoricProcessInstance.class);
        when(instance.getId()).thenReturn(instanceId);
        when(instance.getProcessDefinitionId()).thenReturn(definitionId);
        when(instance.getDeploymentId()).thenReturn(deploymentId);
        when(instance.getStartUserId()).thenReturn("9");
        return instance;
    }

    /**
     * 配置任务映射需要的真实定义、历史实例和发起人名称。
     *
     * @param definitionId String，流程定义主键
     * @param instanceId String，流程实例主键
     * @param deploymentId String，部署主键
     * @return ProcessDefinition，后续任务列表可覆盖定义分类并完成关联映射
     */
    private ProcessDefinition stubTaskContext(String definitionId, String instanceId,
            String deploymentId)
    {
        ProcessDefinition definition = definition(definitionId, "leave", deploymentId, false);
        HistoricProcessInstance instance = historicInstance(instanceId, definitionId, deploymentId);
        when(repositoryService.getProcessDefinition(definitionId)).thenReturn(definition);
        when(processQuery.singleResult()).thenReturn(instance);
        Deployment deployment = mock(Deployment.class);
        when(deployment.getId()).thenReturn(deploymentId);
        when(deployment.getCategory()).thenReturn("business");
        when(deploymentQuery.singleResult()).thenReturn(deployment);
        SysUser user = new SysUser();
        user.setUserId(9L);
        user.setNickName("发起人");
        when(userService.selectUserById(9L)).thenReturn(user);
        return definition;
    }

    /**
     * 配置业务分类对应的正式 Flowable 部署查询结果。
     *
     * @param category String，分类目录使用的业务编码
     * @param deploymentId String，该分类下的正式流程部署主键
     * @return Deployment，可继续配置部署时间或作为单对象查询结果复用
     */
    private Deployment stubCategoryDeployment(String category, String deploymentId)
    {
        Deployment deployment = mock(Deployment.class);
        when(deployment.getId()).thenReturn(deploymentId);
        when(deployment.getCategory()).thenReturn(category);
        when(deploymentQuery.list()).thenReturn(List.of(deployment));
        return deployment;
    }

    /**
     * 创建已通过对象授权的实例快照。
     *
     * @param instanceId String，流程实例主键
     * @param definitionId String，流程定义主键
     * @param deploymentId String，部署主键
     * @return WorkflowProcessAccessSnapshot，不可变对象授权快照
     */
    private WorkflowProcessAccessSnapshot accessSnapshot(String instanceId,
            String definitionId, String deploymentId)
    {
        return new WorkflowProcessAccessSnapshot(instanceId, definitionId, deploymentId,
                null, "7", Instant.parse("2026-07-25T08:00:00Z"), null, null,
                null, "RUNNING");
    }

    /**
     * 执行业务调用并断言稳定 ServiceException 状态码。
     *
     * @param action Runnable，预期失败的业务调用
     * @param code int，预期 HTTP 状态码
     * @return 无返回值，异常类型或状态码不一致时测试失败
     */
    private void assertCode(Runnable action, int code)
    {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ServiceException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(code));
    }
}
