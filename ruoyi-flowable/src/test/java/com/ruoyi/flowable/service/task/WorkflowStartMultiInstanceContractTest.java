package com.ruoyi.flowable.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.MultiInstanceLoopCharacteristics;
import org.flowable.bpmn.model.UserTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.vo.WorkflowStartMultiInstanceAssignmentView;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.identity.WorkflowUserSelectionValidator;

class WorkflowStartMultiInstanceContractTest
{
    private WorkflowIdentityResolver identityResolver;
    private WorkflowUserSelectionValidator validator;

    /**
     * 创建使用正式审批资格解析协议的成员校验器。
     *
     * @return 无返回值；测试依赖初始化失败时测试失败。
     */
    @BeforeEach
    void setUp()
    {
        identityResolver = mock(WorkflowIdentityResolver.class);
        validator = new WorkflowUserSelectionValidator(identityResolver);
    }

    /**
     * 验证发起页面按 BPMN 顺序投影会签和或签字段及人数边界。
     *
     * @return 无返回值；节点名称、模式或人数约束丢失时测试失败。
     */
    @Test
    void describesAllAndAnyAssignmentsInModelOrder()
    {
        BpmnModel model = modelWithStartTasks(
                startTask("allReview", "财务会签", WorkflowMultiInstanceMode.ALL),
                startTask("anyReview", "主管或签", WorkflowMultiInstanceMode.ANY));

        assertThat(WorkflowStartMultiInstanceContract.describe(model, "expense"))
                .containsExactly(
                        new WorkflowStartMultiInstanceAssignmentView(
                                "allReview", "财务会签", "ALL", 1, 100),
                        new WorkflowStartMultiInstanceAssignmentView(
                                "anyReview", "主管或签", "ANY", 1, 100));
    }

    /**
     * 验证合法成员经正式审批资格校验后生成活动专属保留变量。
     *
     * @return 无返回值；成员顺序、变量名或正式资格校验发生漂移时测试失败。
     */
    @Test
    void preparesCanonicalVariablesForEveryRequiredActivity()
    {
        BpmnModel model = modelWithStartTasks(
                startTask("allReview", "财务会签", WorkflowMultiInstanceMode.ALL),
                startTask("anyReview", "主管或签", WorkflowMultiInstanceMode.ANY));
        when(identityResolver.resolveApprovalEligibleUserIds(anyList()))
                .thenAnswer(invocation -> new LinkedHashSet<>(invocation.getArgument(0)));

        Map<String, Object> variables = WorkflowStartMultiInstanceContract.prepareVariables(
                model, "expense", Map.of(
                        "allReview", List.of(8L, 9L),
                        "anyReview", List.of(10L)), validator);

        assertThat(variables).containsExactlyInAnyOrderEntriesOf(Map.of(
                "wfMiUsers_allReview", List.of(8L, 9L),
                "wfMiUsers_anyReview", List.of(10L)));
    }

    /**
     * 验证缺失、额外或空成员字段均整体拒绝，不能把不完整集合交给 Flowable。
     *
     * @return 无返回值；任一不完整请求被接受时测试失败。
     */
    @Test
    void rejectsMissingExtraAndEmptySelections()
    {
        BpmnModel model = modelWithStartTasks(
                startTask("approve", "审批会签", WorkflowMultiInstanceMode.ALL));

        assertBadRequest(() -> WorkflowStartMultiInstanceContract.prepareVariables(
                model, "expense", Map.of(), validator));
        assertBadRequest(() -> WorkflowStartMultiInstanceContract.prepareVariables(
                model, "expense", Map.of(
                        "approve", List.of(8L), "unknown", List.of(9L)), validator));
        assertBadRequest(() -> WorkflowStartMultiInstanceContract.prepareVariables(
                model, "expense", Map.of("approve", List.of()), validator));
    }

    /**
     * 验证重复成员和不具备审批资格的用户由正式身份校验器整批拒绝。
     *
     * @return 无返回值；重复或失效成员进入保留变量时测试失败。
     */
    @Test
    void rejectsDuplicateAndIneligibleUsers()
    {
        BpmnModel model = modelWithStartTasks(
                startTask("approve", "审批或签", WorkflowMultiInstanceMode.ANY));

        assertBadRequest(() -> WorkflowStartMultiInstanceContract.prepareVariables(
                model, "expense", Map.of("approve", List.of(8L, 8L)), validator));
        when(identityResolver.resolveApprovalEligibleUserIds(List.of("8")))
                .thenReturn(new LinkedHashSet<>());
        assertBadRequest(() -> WorkflowStartMultiInstanceContract.prepareVariables(
                model, "expense", Map.of("approve", List.of(8L)), validator));
    }

    /**
     * 创建包含指定发起来源多实例任务的可执行主流程模型。
     *
     * @param tasks UserTask[]，按页面投影顺序加入主流程的受控用户任务。
     * @return BpmnModel，process key 固定为 expense 的可执行模型。
     */
    private BpmnModel modelWithStartTasks(UserTask... tasks)
    {
        BpmnModel model = new BpmnModel();
        org.flowable.bpmn.model.Process process = new org.flowable.bpmn.model.Process();
        process.setId("expense");
        process.setExecutable(true);
        for (UserTask task : tasks)
        {
            process.addFlowElement(task);
        }
        model.addProcess(process);
        return model;
    }

    /**
     * 创建由发起页面提供成员的受控并行会签或或签用户任务。
     *
     * @param activityId String，稳定 BPMN 活动标识。
     * @param activityName String，设计者可见节点名称。
     * @param mode WorkflowMultiInstanceMode，ALL 会签或 ANY 或签。
     * @return UserTask，满足平台受控多实例模型契约的用户任务。
     */
    private UserTask startTask(String activityId, String activityName,
            WorkflowMultiInstanceMode mode)
    {
        UserTask task = new UserTask();
        task.setId(activityId);
        task.setName(activityName);
        task.setAssignee(WorkflowMultiInstanceModelContract.ASSIGNEE_EXPRESSION);
        MultiInstanceLoopCharacteristics loop = new MultiInstanceLoopCharacteristics();
        loop.setInputDataItem(WorkflowMultiInstanceModelContract.START_COLLECTION_EXPRESSION);
        loop.setElementVariable(WorkflowMultiInstanceModelContract.ELEMENT_VARIABLE);
        loop.setCompletionCondition(mode == WorkflowMultiInstanceMode.ALL
                ? WorkflowMultiInstanceModelContract.ALL_COMPLETION_CONDITION
                : WorkflowMultiInstanceModelContract.ANY_COMPLETION_CONDITION);
        task.setLoopCharacteristics(loop);
        return task;
    }

    /**
     * 断言发起成员请求以稳定 HTTP 400 业务异常失败。
     *
     * @param action Runnable，预计被成员契约拒绝的操作。
     * @return 无返回值；异常类型或状态码不符时测试失败。
     */
    private void assertBadRequest(Runnable action)
    {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ServiceException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
