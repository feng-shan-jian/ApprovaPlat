package com.ruoyi.flowable.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.flowable.bpmn.model.ExtensionAttribute;
import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.bpmn.model.MultiInstanceLoopCharacteristics;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.delegate.DelegateExecution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.identity.WorkflowUserSelectionValidator;

class WorkflowMultiInstanceHandlerTest
{
    private WorkflowUserSelectionValidator userSelectionValidator;

    private WorkflowMultiInstanceHandler handler;

    /**
     * 为每个测试创建独立正式用户校验器替身和 handler。
     *
     * @return 无返回值；初始化失败时测试失败
     */
    @BeforeEach
    void setUp()
    {
        userSelectionValidator = mock(WorkflowUserSelectionValidator.class);
        handler = new WorkflowMultiInstanceHandler(userSelectionValidator);
    }

    /**
     * 验证正常集合保持首次出现顺序去重，并在流程实例作用域原子初始化三项服务端变量。
     *
     * @return 无返回值；用户顺序、主数据校验或初始化变量不符合契约时测试失败
     */
    @Test
    void resolvesUsersAndInitializesServerState()
    {
        DelegateExecution execution = execution(WorkflowMultiInstanceMode.ALL,
                List.of(8L, 8L, 9));
        when(userSelectionValidator.requireApprovalEligibleUserIds(List.of(8L, 9L)))
                .thenReturn(List.of("8", "9"));

        List<String> result = handler.getUserIds(execution);

        assertThat(result).containsExactly("8", "9");
        ArgumentCaptor<Map<String, Object>> variables = ArgumentCaptor.forClass(Map.class);
        verify(execution).setVariables(variables.capture());
        assertThat(variables.getValue())
                .containsEntry("_wfMiRevision_approveTask", 0)
                .containsEntry("_wfMiMode_approveTask", "ALL");
        assertThat(variables.getValue().get("_wfMiMembers_approveTask"))
                .isEqualTo(List.of("8", "9"));
    }

    /**
     * 验证引擎重求值完整状态时只读复用正式快照，已增长 revision 不会被重置。
     *
     * @return 无返回值；handler 二次写变量或返回旧集合时测试失败
     */
    @Test
    void reusesCompleteExistingStateWithoutResettingRevision()
    {
        DelegateExecution execution = execution(WorkflowMultiInstanceMode.ALL,
                List.of(8L, 9L, 10L));
        when(execution.getVariable("_wfMiMembers_approveTask"))
                .thenReturn(List.of("8", "9", "10"));
        when(execution.getVariable("_wfMiRevision_approveTask")).thenReturn(7);
        when(execution.getVariable("_wfMiMode_approveTask")).thenReturn("ALL");
        when(userSelectionValidator.requireApprovalEligibleUserIds(List.of(8L, 9L, 10L)))
                .thenReturn(List.of("8", "9", "10"));

        assertThat(handler.getUserIds(execution)).containsExactly("8", "9", "10");

        verify(execution, never()).setVariables(org.mockito.ArgumentMatchers.anyMap());
    }

    /**
     * 验证三项服务端变量仅部分存在时按数据一致性错误拒绝，禁止静默覆盖修复。
     *
     * @return 无返回值；部分状态被重新初始化或错误码不是 500 时测试失败
     */
    @Test
    void rejectsPartiallyInitializedServerState()
    {
        DelegateExecution execution = execution(WorkflowMultiInstanceMode.ALL,
                List.of(8L));
        when(execution.getVariable("_wfMiMembers_approveTask"))
                .thenReturn(List.of("8"));
        when(userSelectionValidator.requireApprovalEligibleUserIds(List.of(8L)))
                .thenReturn(List.of("8"));

        assertServiceError(() -> handler.getUserIds(execution), HttpStatus.ERROR,
                "工作流多实例执行上下文异常");
        verify(execution, never()).setVariables(org.mockito.ArgumentMatchers.anyMap());
    }

    /**
     * 验证完整保留状态与当前集合或 BPMN 模式不一致时拒绝重求值且不回退 revision。
     *
     * @return 无返回值；不一致状态被接受或变量被覆盖时测试失败
     */
    @Test
    void rejectsExistingStateThatDivergesFromCollectionOrMode()
    {
        DelegateExecution execution = execution(WorkflowMultiInstanceMode.ALL,
                List.of(8L, 9L));
        when(execution.getVariable("_wfMiMembers_approveTask"))
                .thenReturn(List.of("8", "10"));
        when(execution.getVariable("_wfMiRevision_approveTask")).thenReturn(4);
        when(execution.getVariable("_wfMiMode_approveTask")).thenReturn("ANY");
        when(userSelectionValidator.requireApprovalEligibleUserIds(List.of(8L, 9L)))
                .thenReturn(List.of("8", "9"));

        assertServiceError(() -> handler.getUserIds(execution), HttpStatus.ERROR,
                "工作流多实例执行上下文异常");
        verify(execution, never()).setVariables(org.mockito.ArgumentMatchers.anyMap());
    }

    /**
     * 验证非集合、空集合、浮点 ID 和超过 100 项的变量在主数据查询前返回参数错误。
     *
     * @return 无返回值；非法值进入主数据解析或错误码不稳定时测试失败
     */
    @Test
    void rejectsUnboundedOrNonIntegralCollectionsBeforeIdentityLookup()
    {
        assertServiceError(() -> handler.getUserIds(execution(
                WorkflowMultiInstanceMode.ALL, "8")), HttpStatus.BAD_REQUEST,
                "工作流多实例用户集合不合法");
        assertServiceError(() -> handler.getUserIds(execution(
                WorkflowMultiInstanceMode.ALL, List.of())), HttpStatus.BAD_REQUEST,
                "工作流多实例用户集合不合法");
        assertServiceError(() -> handler.getUserIds(execution(
                WorkflowMultiInstanceMode.ALL, List.of(8.0))), HttpStatus.BAD_REQUEST,
                "工作流多实例用户集合不合法");
        List<Long> overLimit = java.util.stream.LongStream.rangeClosed(1, 101)
                .boxed().toList();
        assertServiceError(() -> handler.getUserIds(execution(
                WorkflowMultiInstanceMode.ALL, overLimit)), HttpStatus.BAD_REQUEST,
                "工作流多实例用户集合不合法");
        verify(userSelectionValidator, never()).requireApprovalEligibleUserIds(
                org.mockito.ArgumentMatchers.anyList());
    }

    /**
     * 验证固定成员由 BPMN 参数提供、进入节点时复核审批资格并初始化统一正式成员快照。
     *
     * @return 无返回值；固定成员顺序、资格校验或正式状态初始化不符合契约时测试失败。
     */
    @Test
    void resolvesFixedUsersAndInitializesMemberState()
    {
        DelegateExecution execution = fixedExecution(WorkflowMultiInstanceMode.ANY, "8,9");
        when(userSelectionValidator.requireApprovalEligibleUserIds(List.of(8L, 9L)))
                .thenReturn(List.of("8", "9"));

        assertThat(handler.getFixedUserIds(execution, "8,9")).containsExactly("8", "9");

        verify(userSelectionValidator).requireApprovalEligibleUserIds(List.of(8L, 9L));
        ArgumentCaptor<Map<String, Object>> variables = ArgumentCaptor.forClass(Map.class);
        verify(execution).setVariables(variables.capture());
        assertThat(variables.getValue())
                .containsEntry("_wfMiRevision_approveTask", 0)
                .containsEntry("_wfMiMode_approveTask", "ANY");
        assertThat(variables.getValue().get("_wfMiMembers_approveTask"))
                .isEqualTo(List.of("8", "9"));
        verify(userSelectionValidator, never()).requireActiveUserIds(
                org.mockito.ArgumentMatchers.anyList());
    }

    /**
     * 验证发起时成员表达式复用正式审批资格和成员快照初始化，不接受仅启用但无办理权限用户。
     *
     * @return 无返回值；发起来源绕过资格校验或未初始化状态时测试失败。
     */
    @Test
    void resolvesStartUsersThroughApprovalEligibilityContract()
    {
        DelegateExecution execution = execution(WorkflowMultiInstanceMode.ALL,
                List.of(8L, 9L));
        ((UserTask) execution.getCurrentFlowElement()).getLoopCharacteristics()
                .setInputDataItem(WorkflowMultiInstanceModelContract.START_COLLECTION_EXPRESSION);
        when(userSelectionValidator.requireApprovalEligibleUserIds(List.of(8L, 9L)))
                .thenReturn(List.of("8", "9"));

        assertThat(handler.getStartUserIds(execution)).containsExactly("8", "9");

        verify(userSelectionValidator).requireApprovalEligibleUserIds(List.of(8L, 9L));
        verify(execution).setVariables(org.mockito.ArgumentMatchers.argThat(variables ->
                List.of("8", "9").equals(variables.get("_wfMiMembers_approveTask"))));
    }

    /**
     * 验证指定角色在进入节点时实时展开为独立办理用户，并初始化 ALL 成员快照。
     *
     * @return 无返回值；角色查询、成员顺序或正式状态初始化错误时测试失败
     */
    @Test
    void expandsConfiguredRolesIntoAssignedUsers()
    {
        DelegateExecution execution = configuredExecution(
                WorkflowMultiInstanceMode.ALL, "ROLE", "101,102");
        when(userSelectionValidator.requireApprovalEligibleUserIdsByRoleIds(
                List.of(101L, 102L))).thenReturn(List.of("81", "82", "83"));

        assertThat(handler.getConfiguredUserIds(execution))
                .containsExactly("81", "82", "83");

        verify(userSelectionValidator).requireApprovalEligibleUserIdsByRoleIds(
                List.of(101L, 102L));
        verify(execution).setVariables(org.mockito.ArgumentMatchers.argThat(variables ->
                List.of("81", "82", "83").equals(
                        variables.get("_wfMiMembers_approveTask"))
                && "ALL".equals(variables.get("_wfMiMode_approveTask"))));
    }

    /**
     * 验证指定部门在进入节点时实时展开为独立办理用户，并保留 ANY 完成语义。
     *
     * @return 无返回值；部门查询或或签成员状态错误时测试失败
     */
    @Test
    void expandsConfiguredDepartmentsForAnyMode()
    {
        DelegateExecution execution = configuredExecution(
                WorkflowMultiInstanceMode.ANY, "DEPT", "100");
        when(userSelectionValidator.requireApprovalEligibleUserIdsByDeptIds(
                List.of(100L))).thenReturn(List.of("81", "82", "83", "84"));

        assertThat(handler.getConfiguredUserIds(execution))
                .containsExactly("81", "82", "83", "84");

        verify(userSelectionValidator).requireApprovalEligibleUserIdsByDeptIds(
                List.of(100L));
        verify(execution).setVariables(org.mockito.ArgumentMatchers.argThat(variables ->
                "ANY".equals(variables.get("_wfMiMode_approveTask"))));
    }

    /**
     * 验证指定用户仍使用直接办理资格校验，不会降级为普通启用用户查询。
     *
     * @return 无返回值；指定用户绕过审批资格或调用错误身份分支时测试失败
     */
    @Test
    void validatesConfiguredUsersThroughApprovalEligibility()
    {
        DelegateExecution execution = configuredExecution(
                WorkflowMultiInstanceMode.ALL, "USER", "81,82");
        when(userSelectionValidator.requireApprovalEligibleUserIds(
                List.of(81L, 82L))).thenReturn(List.of("81", "82"));

        assertThat(handler.getConfiguredUserIds(execution)).containsExactly("81", "82");

        verify(userSelectionValidator).requireApprovalEligibleUserIds(
                List.of(81L, 82L));
        verify(userSelectionValidator, never()).requireActiveUserIds(
                org.mockito.ArgumentMatchers.anyList());
    }

    /**
     * 创建具有固定动态多实例模型和流程实例根语义的 DelegateExecution 替身。
     *
     * @param mode WorkflowMultiInstanceMode，ALL 或 ANY 完成模式
     * @param rawUsers Object，wfMiUsers_approveTask 原始变量值
     * @return DelegateExecution，可直接传给 handler 的流程实例执行替身
     */
    private DelegateExecution execution(WorkflowMultiInstanceMode mode, Object rawUsers)
    {
        UserTask userTask = dynamicUserTask(mode);
        DelegateExecution execution = mock(DelegateExecution.class);
        when(execution.getCurrentActivityId()).thenReturn("approveTask");
        when(execution.getCurrentFlowElement()).thenReturn(userTask);
        when(execution.getVariable("wfMiUsers_approveTask")).thenReturn(rawUsers);
        when(execution.getProcessInstanceId()).thenReturn("instance-1");
        when(execution.getId()).thenReturn("instance-1");
        when(execution.isProcessInstanceType()).thenReturn(true);
        return execution;
    }

    /**
     * 创建具有固定成员表达式的 DelegateExecution 替身。
     *
     * @param mode WorkflowMultiInstanceMode，ALL 或 ANY 完成模式。
     * @param fixedUserIdsText String，BPMN 集合表达式中的固定成员主键文本。
     * @return DelegateExecution，可直接传给固定成员 handler 的流程实例执行替身。
     */
    private DelegateExecution fixedExecution(WorkflowMultiInstanceMode mode,
            String fixedUserIdsText)
    {
        UserTask userTask = dynamicUserTask(mode);
        userTask.getLoopCharacteristics().setInputDataItem(
                "${multiInstanceHandler.getFixedUserIds(execution, '" + fixedUserIdsText + "')}");
        DelegateExecution execution = mock(DelegateExecution.class);
        when(execution.getCurrentActivityId()).thenReturn("approveTask");
        when(execution.getCurrentFlowElement()).thenReturn(userTask);
        when(execution.getProcessInstanceId()).thenReturn("instance-1");
        when(execution.getId()).thenReturn("instance-1");
        when(execution.isProcessInstanceType()).thenReturn(true);
        return execution;
    }

    /**
     * 创建带指定身份属性和受控集合表达式的运行时执行上下文。
     *
     * @param mode WorkflowMultiInstanceMode，ALL 或 ANY
     * @param type String，USER、ROLE 或 DEPT
     * @param ids String，逗号分隔的用户、角色或部门主键
     * @return DelegateExecution，可直接调用 getConfiguredUserIds 的测试上下文
     */
    private DelegateExecution configuredExecution(WorkflowMultiInstanceMode mode,
            String type, String ids)
    {
        UserTask userTask = dynamicUserTask(mode);
        userTask.getLoopCharacteristics().setInputDataItem(
                WorkflowMultiInstanceModelContract.CONFIGURED_COLLECTION_EXPRESSION);
        ExtensionElement typeProperty = property(
                WorkflowMultiInstanceModelContract.IDENTITY_TYPE_PROPERTY, type);
        ExtensionElement idsProperty = property(
                WorkflowMultiInstanceModelContract.IDENTITY_IDS_PROPERTY, ids);
        ExtensionElement container = new ExtensionElement();
        container.setName("properties");
        container.setNamespace("http://flowable.org/bpmn");
        Map<String, List<ExtensionElement>> children = new HashMap<>();
        children.put("property", List.of(typeProperty, idsProperty));
        container.setChildElements(children);
        userTask.addExtensionElement(container);

        DelegateExecution execution = mock(DelegateExecution.class);
        when(execution.getCurrentActivityId()).thenReturn("approveTask");
        when(execution.getCurrentFlowElement()).thenReturn(userTask);
        when(execution.getProcessInstanceId()).thenReturn("instance-1");
        when(execution.getId()).thenReturn("instance-1");
        when(execution.isProcessInstanceType()).thenReturn(true);
        return execution;
    }

    /**
     * 创建一条 Flowable 扩展属性。
     *
     * @param name String，平台保留属性名
     * @param value String，属性正文
     * @return ExtensionElement，properties 容器中的 property 子元素
     */
    private ExtensionElement property(String name, String value)
    {
        ExtensionElement property = new ExtensionElement();
        property.setName("property");
        property.setNamespace("http://flowable.org/bpmn");
        property.addAttribute(new ExtensionAttribute("name", name));
        property.addAttribute(new ExtensionAttribute("value", value));
        return property;
    }

    /**
     * 创建完整满足固定表达式契约的主流程并行用户任务。
     *
     * @param mode WorkflowMultiInstanceMode，ALL 或 ANY
     * @return UserTask，已挂接主流程 parentContainer 的测试节点
     */
    private UserTask dynamicUserTask(WorkflowMultiInstanceMode mode)
    {
        org.flowable.bpmn.model.Process process = new org.flowable.bpmn.model.Process();
        process.setId("process");
        UserTask userTask = new UserTask();
        userTask.setId("approveTask");
        userTask.setAssignee(WorkflowMultiInstanceModelContract.ASSIGNEE_EXPRESSION);
        MultiInstanceLoopCharacteristics loop = new MultiInstanceLoopCharacteristics();
        loop.setSequential(false);
        loop.setInputDataItem(WorkflowMultiInstanceModelContract.COLLECTION_EXPRESSION);
        loop.setElementVariable(WorkflowMultiInstanceModelContract.ELEMENT_VARIABLE);
        loop.setCompletionCondition(mode == WorkflowMultiInstanceMode.ALL
                ? WorkflowMultiInstanceModelContract.ALL_COMPLETION_CONDITION
                : WorkflowMultiInstanceModelContract.ANY_COMPLETION_CONDITION);
        userTask.setLoopCharacteristics(loop);
        process.addFlowElement(userTask);
        return userTask;
    }

    /**
     * 断言 handler 返回指定稳定业务错误。
     *
     * @param action Runnable，预期失败的 handler 调用
     * @param code int，预期业务状态码
     * @param message String，预期稳定提示
     * @return 无返回值；异常契约不一致时测试失败
     */
    private void assertServiceError(Runnable action, int code, String message)
    {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(
                ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(code);
                    assertThat(exception.getMessage()).isEqualTo(message);
                });
    }
}
