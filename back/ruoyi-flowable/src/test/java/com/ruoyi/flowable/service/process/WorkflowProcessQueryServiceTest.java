package com.ruoyi.flowable.service.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.StartEvent;
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
import com.ruoyi.flowable.mapper.WfDeployFormMapper;
import com.ruoyi.flowable.service.model.WorkflowDeploymentService;
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
    private WfDeployFormMapper deployFormMapper;

    @Mock
    private WfCopyMapper copyMapper;

    @Mock
    private ISysUserService userService;

    @Mock
    private WorkflowTaskLifecycleService taskLifecycleService;

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
        when(identityResolver.resolveCurrentIdentity())
                .thenReturn(new WorkflowCurrentIdentity("7", Set.of("ROLE2", "DEPT3")));
        when(identityResolver.resolveClaimEligibleUserIds(List.of("7")))
                .thenReturn(Set.of("7"));
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
                deploymentService, deployFormMapper, copyMapper, userService,
                taskLifecycleService);
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
        when(deployment.getDeploymentTime()).thenReturn(Date.from(Instant.parse("2026-07-25T08:00:00Z")));
        when(deploymentQuery.singleResult()).thenReturn(deployment);

        WorkflowPageResult<WorkflowStartableDefinitionView> result =
                service.listStartable(null, 2, 1);

        assertThat(result.total()).isEqualTo(2);
        assertThat(result.rows()).singleElement()
                .extracting(WorkflowStartableDefinitionView::definitionId)
                .isEqualTo("definition-group");
        verify(definitionQuery).count();
        verify(definitionQuery).listPage(0, 3);
        verify(definitionQuery, never()).list();
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
        stubTaskContext("definition-1", "instance-1", "deploy-1");

        WorkflowPageResult<WorkflowAssignedTaskView> result =
                service.listAssigned(null, 1, 10);

        assertThat(result.rows()).singleElement().satisfies(row ->
        {
            assertThat(row.assigneeId()).isEqualTo("7");
            assertThat(row.claimedById()).isEqualTo("7");
            assertThat(row.claimTime()).isEqualTo(
                    Instant.parse("2026-07-25T10:20:00Z"));
        });
        verify(taskQuery).active();
        verify(taskQuery).taskAssignee("7");
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

        assertThat(result.rows()).singleElement()
                .extracting(WorkflowClaimableTaskView::taskId)
                .isEqualTo("task-claim");
        verify(taskQuery).taskUnassigned();
        verify(taskQuery).taskCandidateUser("7");
        verify(taskQuery).taskCandidateGroupIn(Set.of("ROLE2", "DEPT3"));
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
        assertThat(result.rows().get(0).revocable()).isTrue();
        verify(historicTaskQuery).taskCompletedBy("7");
        verify(historicTaskQuery, never()).taskAssignee("7");
        verify(taskLifecycleService).isProcessRevocable("instance-1", "task-finished");
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
     * 验证首次发起只返回 BPMN 开始节点对应的 wf_deploy_form 不可变快照。
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
        when(repositoryService.getBpmnModel("definition-1")).thenReturn(startFormModel("leave"));
        WfDeployForm snapshot = new WfDeployForm();
        snapshot.setDeployId("deploy-1");
        snapshot.setFormId(12L);
        snapshot.setFormKey("key_12");
        snapshot.setNodeKey("start");
        snapshot.setFormName("请假表单");
        snapshot.setContent("{\"fields\":[]}");
        when(deployFormMapper.selectByDeploymentId("deploy-1")).thenReturn(List.of(snapshot));

        WorkflowProcessFormView result = service.getProcessForm(
                new WorkflowProcessFormQueryDto("definition-1", "deploy-1", null));

        assertThat(result.formId()).isEqualTo(12L);
        assertThat(result.content()).isEqualTo("{\"fields\":[]}");
        verify(deployFormMapper).selectByDeploymentId("deploy-1");
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
        verify(deployFormMapper, never()).selectByDeploymentId(any());
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
     * @return 无返回值，后续任务列表可完成关联映射
     */
    private void stubTaskContext(String definitionId, String instanceId, String deploymentId)
    {
        ProcessDefinition definition = definition(definitionId, "leave", deploymentId, false);
        HistoricProcessInstance instance = historicInstance(instanceId, definitionId, deploymentId);
        when(repositoryService.getProcessDefinition(definitionId)).thenReturn(definition);
        when(processQuery.singleResult()).thenReturn(instance);
        SysUser user = new SysUser();
        user.setUserId(9L);
        user.setNickName("发起人");
        when(userService.selectUserById(9L)).thenReturn(user);
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
