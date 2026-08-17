package com.ruoyi.flowable.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.repository.ProcessDefinitionQuery;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.engine.runtime.ProcessInstanceQuery;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfCopy;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowUserSelectionValidator;
import com.ruoyi.flowable.mapper.WfCopyMapper;
import com.ruoyi.flowable.mapper.WorkflowRuntimeTaskMapper;
import com.ruoyi.system.mapper.SysUserMapper;
import com.ruoyi.flowable.service.notification.WorkflowNotificationRegistrar;

class WorkflowTaskCopyServiceTest
{
    private WorkflowUserSelectionValidator userSelectionValidator;

    private WfCopyMapper copyMapper;

    private WorkflowRuntimeTaskMapper runtimeTaskMapper;

    private RepositoryService repositoryService;

    private RuntimeService runtimeService;

    private SysUserMapper sysUserMapper;

    private WorkflowNotificationRegistrar notificationService;

    private WorkflowTaskCopyService copyService;

    /**
     * 为每个测试创建独立的身份、Flowable 查询和 Mapper 替身。
     *
     * @return 无返回值；初始化失败时测试失败
     */
    @BeforeEach
    void setUp()
    {
        userSelectionValidator = mock(WorkflowUserSelectionValidator.class);
        copyMapper = mock(WfCopyMapper.class);
        runtimeTaskMapper = mock(WorkflowRuntimeTaskMapper.class);
        repositoryService = mock(RepositoryService.class);
        runtimeService = mock(RuntimeService.class);
        sysUserMapper = mock(SysUserMapper.class);
        notificationService = mock(WorkflowNotificationRegistrar.class);
        copyService = new WorkflowTaskCopyService(userSelectionValidator, copyMapper,
                runtimeTaskMapper, repositoryService, runtimeService, sysUserMapper);
        copyService.setNotificationService(notificationService);
    }

    /**
     * 验证动作前会冻结包含稳定事件键、流程快照、可信操作人与全部接收人的不可变计划。
     *
     * @return 无返回值；计划字段、接收顺序或不可变约束不符合时测试失败
     */
    @Test
    void preparesImmutableCopyPlanFromTrustedWorkflowState()
    {
        Task task = task();
        WorkflowCurrentIdentity actor = new WorkflowCurrentIdentity("7", Set.of("ROLE2"));
        when(userSelectionValidator.requireActiveUserIds(List.of(8L, 9L)))
                .thenReturn(List.of("8", "9"));
        stubWorkflowMetadata(task, 4);

        WorkflowTaskCopyService.CopyPlan plan = copyService.prepare(
                WorkflowTaskCopyAction.DELEGATE, task, actor, List.of(8L, 9L));

        assertThat(plan.copies()).extracting(WfCopy::getUserId).containsExactly(8L, 9L);
        WfCopy firstCopy = plan.copies().get(0);
        assertThat(firstCopy.getCopyEventId()).isEqualTo("DELEGATE:task-1:r4");
        assertThat(firstCopy.getTitle()).isEqualTo("请假流程-部门审批");
        assertThat(firstCopy.getProcessId()).isEqualTo("definition-1");
        assertThat(firstCopy.getProcessName()).isEqualTo("请假流程");
        assertThat(firstCopy.getCategoryId()).isEqualTo("HR");
        assertThat(firstCopy.getDeploymentId()).isEqualTo("deployment-1");
        assertThat(firstCopy.getInstanceId()).isEqualTo("instance-1");
        assertThat(firstCopy.getTaskId()).isEqualTo("task-1");
        assertThat(firstCopy.getOriginatorId()).isEqualTo(3L);
        assertThat(firstCopy.getOriginatorName()).isEqualTo("发起人");
        assertThat(firstCopy.getCreateBy()).isEqualTo("7");
        assertThat(firstCopy.getRemark()).isEqualTo("任务动作:DELEGATE");
        assertThatThrownBy(() -> plan.copies().add(new WfCopy()))
                .isInstanceOf(UnsupportedOperationException.class);
        verify(runtimeTaskMapper).selectActiveTaskRevision("task-1");
    }

    /**
     * 验证未选择抄送人时返回空计划，不读取任务、流程或业务表状态。
     *
     * @return 无返回值；空计划触发元数据访问或包含记录时测试失败
     */
    @Test
    void returnsEmptyPlanWithoutWorkflowMetadataLookup()
    {
        when(userSelectionValidator.requireActiveUserIds(null)).thenReturn(List.of());

        WorkflowTaskCopyService.CopyPlan plan = copyService.prepare(
                WorkflowTaskCopyAction.TRANSFER, null, null, null);

        assertThat(plan.copies()).isEmpty();
        verifyNoInteractions(copyMapper, repositoryService, runtimeService, sysUserMapper);
    }

    /**
     * 验证非法、重复或停用用户在任务 revision 与引擎元数据读取前直接拒绝。
     *
     * @return 无返回值；非法选择进入后续动作准备阶段时测试失败
     */
    @Test
    void rejectsInvalidRecipientsBeforeReadingActionState()
    {
        ServiceException invalidSelection = new ServiceException(
                "工作流用户选择不合法", HttpStatus.BAD_REQUEST);
        when(userSelectionValidator.requireActiveUserIds(List.of(8L, 8L)))
                .thenThrow(invalidSelection);

        assertThatThrownBy(() -> copyService.prepare(WorkflowTaskCopyAction.DELEGATE,
                task(), new WorkflowCurrentIdentity("7", Set.of()), List.of(8L, 8L)))
                .isSameAs(invalidSelection);

        verifyNoInteractions(copyMapper, repositoryService, runtimeService, sysUserMapper);
    }

    /**
     * 验证引擎动作成功后完整计划按一次批量写入持久化。
     *
     * @return 无返回值；写入记录集合或写入次数不符合时测试失败
     */
    @Test
    void persistsCompletePlanInOneBatch()
    {
        WorkflowTaskCopyService.CopyPlan plan = new WorkflowTaskCopyService.CopyPlan(
                List.of(new WfCopy(), new WfCopy()));
        when(copyMapper.insertBatch(plan.copies())).thenReturn(2);

        copyService.persist(plan);

        verify(copyMapper).insertBatch(plan.copies());
        verify(notificationService).onCopiesCreated(plan.copies());
    }

    /**
     * 验证 COPY_CREATED 登记失败会原样抛回外层事务，禁止只保留 wf_copy 半状态。
     * @return void，通知失败被吞掉时测试失败
     */
    @Test
    void propagatesCopyNotificationFailureForTransactionRollback()
    {
        WorkflowTaskCopyService.CopyPlan plan = new WorkflowTaskCopyService.CopyPlan(
                List.of(new WfCopy()));
        when(copyMapper.insertBatch(plan.copies())).thenReturn(1);
        ServiceException failure = new ServiceException("通知登记失败", HttpStatus.ERROR);
        when(notificationService.onCopiesCreated(plan.copies())).thenThrow(failure);

        assertThatThrownBy(() -> copyService.persist(plan)).isSameAs(failure);
    }

    /**
     * 验证批量写入数量与冻结计划不一致时抛出数据异常，供外层事务整体回滚引擎动作。
     *
     * @return 无返回值；部分写入被静默接受或错误契约变化时测试失败
     */
    @Test
    void rejectsPartialBatchWriteWithRollbackSignal()
    {
        WorkflowTaskCopyService.CopyPlan plan = new WorkflowTaskCopyService.CopyPlan(
                List.of(new WfCopy(), new WfCopy()));
        when(copyMapper.insertBatch(plan.copies())).thenReturn(1);

        assertDataError(() -> copyService.persist(plan));
    }

    /**
     * 验证空抄送计划不访问数据库，而 null 计划按关联数据异常拒绝。
     *
     * @return 无返回值；空计划产生写入或 null 被静默忽略时测试失败
     */
    @Test
    void handlesEmptyAndNullPersistencePlansSafely()
    {
        copyService.persist(WorkflowTaskCopyService.CopyPlan.empty());
        verifyNoInteractions(copyMapper);

        assertDataError(() -> copyService.persist(null));
    }

    /**
     * 创建具有完整工作流关系字段的活动任务替身。
     *
     * @return Task，任务、实例、定义和节点主键均有效的任务
     */
    private Task task()
    {
        Task task = mock(Task.class);
        when(task.getId()).thenReturn("task-1");
        when(task.getName()).thenReturn("部门审批");
        when(task.getProcessInstanceId()).thenReturn("instance-1");
        when(task.getProcessDefinitionId()).thenReturn("definition-1");
        when(task.getTaskDefinitionKey()).thenReturn("approve");
        return task;
    }

    /**
     * 配置抄送计划所需的任务 revision、活动实例、流程定义和发起人正式主数据。
     *
     * @param task Task，待产生抄送计划的活动任务
     * @param revision int，动作前 ACT_RU_TASK 持久化 revision
     * @return 无返回值；配置完成后服务可冻结完整抄送快照
     */
    private void stubWorkflowMetadata(Task task, int revision)
    {
        when(runtimeTaskMapper.selectActiveTaskRevision(task.getId())).thenReturn(revision);

        ProcessInstanceQuery instanceQuery = mock(ProcessInstanceQuery.class, RETURNS_SELF);
        ProcessInstance processInstance = mock(ProcessInstance.class);
        when(runtimeService.createProcessInstanceQuery()).thenReturn(instanceQuery);
        when(instanceQuery.singleResult()).thenReturn(processInstance);
        when(processInstance.getProcessDefinitionId()).thenReturn("definition-1");
        when(processInstance.getStartUserId()).thenReturn("3");

        ProcessDefinitionQuery definitionQuery = mock(ProcessDefinitionQuery.class, RETURNS_SELF);
        ProcessDefinition processDefinition = mock(ProcessDefinition.class);
        when(repositoryService.createProcessDefinitionQuery()).thenReturn(definitionQuery);
        when(definitionQuery.singleResult()).thenReturn(processDefinition);
        when(processDefinition.getName()).thenReturn("请假流程");
        when(processDefinition.getKey()).thenReturn("leave");
        when(processDefinition.getCategory()).thenReturn("HR");
        when(processDefinition.getDeploymentId()).thenReturn("deployment-1");

        SysUser originator = new SysUser();
        originator.setUserId(3L);
        originator.setUserName("starter");
        originator.setNickName("发起人");
        when(sysUserMapper.selectUserById(3L)).thenReturn(originator);
    }

    /**
     * 断言数据一致性失败返回稳定 HTTP 500，确保外层事务能够识别并回滚。
     *
     * @param action ThrowingCallable，预期触发抄送数据一致性异常的调用
     * @return 无返回值；异常类型、状态码或提示不匹配时测试失败
     */
    private void assertDataError(ThrowingCallable action)
    {
        assertThatThrownBy(action).isInstanceOfSatisfying(ServiceException.class, exception ->
        {
            assertThat(exception.getCode()).isEqualTo(HttpStatus.ERROR);
            assertThat(exception.getMessage()).isEqualTo("工作流对象关联数据异常");
        });
    }
}
