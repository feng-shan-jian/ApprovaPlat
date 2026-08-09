package com.ruoyi.flowable.service.model;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.MultiInstanceLoopCharacteristics;
import org.flowable.bpmn.model.ExtensionAttribute;
import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.UserTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.mapper.WorkflowIdentityMapper;
import com.ruoyi.flowable.service.task.WorkflowMultiInstanceModelContract;

class WorkflowBpmnIdentityValidatorTest
{
    private WorkflowIdentityMapper identityMapper;

    private WorkflowBpmnIdentityValidator validator;

    /**
     * 为每个场景创建隔离的身份 Mapper 替身和真实校验器。
     *
     * @return 无返回值；初始化失败时测试失败
     */
    @BeforeEach
    void setUp()
    {
        identityMapper = mock(WorkflowIdentityMapper.class);
        validator = new WorkflowBpmnIdentityValidator(identityMapper);
    }

    /**
     * 验证静态办理人、候选用户、角色和部门会批量查询正式有效主数据。
     *
     * @return 无返回值；任一身份类型未核验时测试失败
     */
    @Test
    void validatesAllStaticIdentityTypes()
    {
        UserTask task = task("7", "8", List.of("9"), List.of("ROLE2", "DEPT3"));
        when(identityMapper.selectActiveUserIdsByUserIds(List.of(7L, 8L, 9L)))
                .thenReturn(List.of(7L, 8L, 9L));
        when(identityMapper.selectActiveRoleIdsByRoleIds(List.of(2L))).thenReturn(List.of(2L));
        when(identityMapper.selectActiveDeptIdsByDeptIds(List.of(3L))).thenReturn(List.of(3L));
        when(identityMapper.selectApprovalEligibleUserIdsByUserIds(List.of(7L, 8L)))
                .thenReturn(List.of(7L, 8L));
        when(identityMapper.selectClaimEligibleUserIdsByUserIds(List.of(9L)))
                .thenReturn(List.of(9L));
        when(identityMapper.selectClaimEligibleRoleIdsByRoleIds(List.of(2L)))
                .thenReturn(List.of(2L));
        when(identityMapper.selectClaimEligibleDeptIdsByDeptIds(List.of(3L)))
                .thenReturn(List.of(3L));

        validator.validate(document(task));

        verify(identityMapper).selectActiveUserIdsByUserIds(List.of(7L, 8L, 9L));
        verify(identityMapper).selectActiveRoleIdsByRoleIds(List.of(2L));
        verify(identityMapper).selectActiveDeptIdsByDeptIds(List.of(3L));
        verify(identityMapper).selectApprovalEligibleUserIdsByUserIds(List.of(7L, 8L));
        verify(identityMapper).selectClaimEligibleUserIdsByUserIds(List.of(9L));
        verify(identityMapper).selectClaimEligibleRoleIdsByRoleIds(List.of(2L));
        verify(identityMapper).selectClaimEligibleDeptIdsByDeptIds(List.of(3L));
    }

    /**
     * 验证设计后被停用的静态角色会在部署前产生冲突，而不是生成无人可办定义。
     *
     * @return 无返回值；无效角色未被拒绝时测试失败
     */
    @Test
    void rejectsDisabledStaticGroup()
    {
        UserTask task = task(null, null, List.of(), List.of("ROLE2"));
        when(identityMapper.selectActiveRoleIdsByRoleIds(List.of(2L))).thenReturn(List.of());

        assertThatThrownBy(() -> validator.validate(document(task)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("角色不存在、已停用或已删除")
                .hasMessageContaining("2");
    }

    /**
     * 验证启用但没有完整认领资格成员的候选角色仍会被部署门禁拒绝。
     *
     * @return 无返回值；空办理角色被部署时测试失败
     */
    @Test
    void rejectsStaticGroupWithoutClaimEligibleMember()
    {
        UserTask task = task(null, null, List.of(), List.of("ROLE2"));
        when(identityMapper.selectActiveRoleIdsByRoleIds(List.of(2L)))
                .thenReturn(List.of(2L));
        when(identityMapper.selectClaimEligibleRoleIdsByRoleIds(List.of(2L)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> validator.validate(document(task)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("候选角色没有具备完整认领资格的有效成员")
                .hasMessageContaining("2");
    }

    /**
     * 验证静态直接办理人与候选用户分别使用办理和认领资格，不会混用宽松目录。
     *
     * @return 无返回值；不合格候选用户通过部署时测试失败
     */
    @Test
    void rejectsCandidateUserWithoutClaimEligibility()
    {
        UserTask task = task("7", null, List.of("9"), List.of());
        when(identityMapper.selectActiveUserIdsByUserIds(List.of(7L, 9L)))
                .thenReturn(List.of(7L, 9L));
        when(identityMapper.selectApprovalEligibleUserIdsByUserIds(List.of(7L)))
                .thenReturn(List.of(7L));
        when(identityMapper.selectClaimEligibleUserIdsByUserIds(List.of(9L)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> validator.validate(document(task)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("候选用户不具备完整认领资格")
                .hasMessageContaining("9");
    }

    /**
     * 验证静态用户和候选组必须使用与 Flowable 运行时精确匹配的规范编码。
     *
     * @return 无返回值；前导零或空白编码进入主数据查询或通过部署时测试失败
     */
    @Test
    void rejectsNonCanonicalStaticIdentityEncodings()
    {
        UserTask nonCanonicalUser = task("007", null, List.of(), List.of());
        assertThatThrownBy(() -> validator.validate(document(nonCanonicalUser)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("办理人必须使用正整数身份主键");

        UserTask nonCanonicalGroup = task(null, null, List.of(), List.of(" ROLE2"));
        assertThatThrownBy(() -> validator.validate(document(nonCanonicalGroup)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("候选组必须使用 ROLE<id> 或 DEPT<id> 编码");
        verifyNoInteractions(identityMapper);
    }

    /**
     * 验证没有任何办理身份的用户任务会在部署前被拒绝，避免产生所有列表均不可见的死任务。
     *
     * @return 无返回值；无人可办任务通过部署校验时测试失败
     */
    @Test
    void rejectsUserTaskWithoutAssignment()
    {
        UserTask task = task(null, null, List.of(), List.of());

        assertThatThrownBy(() -> validator.validate(document(task)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("审批")
                .hasMessageContaining("必须配置办理人、候选用户或候选组");

        verify(identityMapper, never()).selectActiveUserIdsByUserIds(
                org.mockito.ArgumentMatchers.anyList());
    }

    /**
     * 验证表达式身份仅由 BPMN 安全门禁处理，部署校验器不会伪造运行时解析结果。
     *
     * @return 无返回值；表达式触发静态主数据查询时测试失败
     */
    @Test
    void leavesExpressionsForRuntimeResolution()
    {
        UserTask task = task("${initiator}", null, List.of("${approvers}"),
                List.of("${candidateGroups}"));

        validator.validate(document(task));

        verify(identityMapper, never()).selectActiveUserIdsByUserIds(org.mockito.ArgumentMatchers.anyList());
        verify(identityMapper, never()).selectActiveRoleIdsByRoleIds(org.mockito.ArgumentMatchers.anyList());
        verify(identityMapper, never()).selectActiveDeptIdsByDeptIds(org.mockito.ArgumentMatchers.anyList());
    }

    /**
     * 验证固定会签成员作为直接办理人执行部署时存在性和审批资格校验。
     *
     * @return 无返回值；固定成员未进入正式身份门禁时测试失败。
     */
    @Test
    void validatesFixedMultiInstanceMembersAsApprovalUsers()
    {
        UserTask task = task(WorkflowMultiInstanceModelContract.ASSIGNEE_EXPRESSION,
                null, List.of(), List.of());
        MultiInstanceLoopCharacteristics loop = new MultiInstanceLoopCharacteristics();
        loop.setInputDataItem(
                "${multiInstanceHandler.getFixedUserIds(execution, '8,9')}");
        loop.setElementVariable(WorkflowMultiInstanceModelContract.ELEMENT_VARIABLE);
        loop.setCompletionCondition(WorkflowMultiInstanceModelContract.ALL_COMPLETION_CONDITION);
        task.setLoopCharacteristics(loop);
        when(identityMapper.selectActiveUserIdsByUserIds(List.of(8L, 9L)))
                .thenReturn(List.of(8L, 9L));
        when(identityMapper.selectApprovalEligibleUserIdsByUserIds(List.of(8L, 9L)))
                .thenReturn(List.of(8L, 9L));

        validator.validate(document(task));

        verify(identityMapper).selectActiveUserIdsByUserIds(List.of(8L, 9L));
        verify(identityMapper).selectApprovalEligibleUserIdsByUserIds(List.of(8L, 9L));
    }

    /**
     * 验证自动抄送固定用户、角色和部门使用独立 copy 可见性门禁，不会混入候选认领资格。
     *
     * @return 无返回值；任一自动抄送身份未通过正式可见性查询时测试失败
     */
    @Test
    void validatesAutoCopyIdentitiesWithCopyVisibility()
    {
        UserTask task = withAutoCopyRules(task("7", null, List.of(), List.of()), """
                {"version":1,"rules":[{"id":"copy-1","trigger":"NODE_COMPLETED","recipients":[
                  {"type":"USER","values":["8","9"]},
                  {"type":"GROUP","values":["ROLE2","DEPT3"]}
                ]}]}
                """);
        when(identityMapper.selectActiveUserIdsByUserIds(List.of(7L, 8L, 9L)))
                .thenReturn(List.of(7L, 8L, 9L));
        when(identityMapper.selectActiveRoleIdsByRoleIds(List.of(2L))).thenReturn(List.of(2L));
        when(identityMapper.selectActiveDeptIdsByDeptIds(List.of(3L))).thenReturn(List.of(3L));
        when(identityMapper.selectApprovalEligibleUserIdsByUserIds(List.of(7L)))
                .thenReturn(List.of(7L));
        when(identityMapper.selectCopyEligibleUserIdsByUserIds(List.of(8L, 9L)))
                .thenReturn(List.of(8L, 9L));
        when(identityMapper.selectCopyEligibleUserIdsByRoleIds(List.of(2L)))
                .thenReturn(List.of(20L));
        when(identityMapper.selectCopyEligibleUserIdsByDeptIds(List.of(3L)))
                .thenReturn(List.of(30L));

        validator.validate(document(task));

        verify(identityMapper).selectCopyEligibleUserIdsByUserIds(List.of(8L, 9L));
        verify(identityMapper).selectCopyEligibleUserIdsByRoleIds(List.of(2L));
        verify(identityMapper).selectCopyEligibleUserIdsByDeptIds(List.of(3L));
        verify(identityMapper, never()).selectClaimEligibleRoleIdsByRoleIds(List.of(2L));
        verify(identityMapper, never()).selectClaimEligibleDeptIdsByDeptIds(List.of(3L));
    }

    /**
     * 验证有效但缺少抄送列表或详情权限的固定用户会阻止部署。
     *
     * @return 无返回值；不可读取抄送对象的固定用户通过校验时测试失败
     */
    @Test
    void rejectsAutoCopyUserWithoutCopyVisibility()
    {
        UserTask task = withAutoCopyRules(task("7", null, List.of(), List.of()), """
                {"version":1,"rules":[{"id":"copy-user","trigger":"NODE_ARRIVED","recipients":[
                  {"type":"USER","values":["8"]}
                ]}]}
                """);
        when(identityMapper.selectActiveUserIdsByUserIds(List.of(7L, 8L)))
                .thenReturn(List.of(7L, 8L));
        when(identityMapper.selectApprovalEligibleUserIdsByUserIds(List.of(7L)))
                .thenReturn(List.of(7L));
        when(identityMapper.selectCopyEligibleUserIdsByUserIds(List.of(8L)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> validator.validate(document(task)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("自动抄送用户不具备抄送列表和流程详情权限")
                .hasMessageContaining("8");
    }

    /**
     * 验证每个自动抄送角色都必须独立解析出可见用户，不能由同批其他角色掩盖。
     *
     * @return 无返回值；空角色被另一有效角色掩盖并通过部署时测试失败
     */
    @Test
    void rejectsEachAutoCopyRoleWithoutVisibleMember()
    {
        UserTask task = withAutoCopyRules(task("7", null, List.of(), List.of()), """
                {"version":1,"rules":[{"id":"copy-roles","trigger":"NODE_COMPLETED","recipients":[
                  {"type":"GROUP","values":["ROLE2","ROLE3"]}
                ]}]}
                """);
        when(identityMapper.selectActiveUserIdsByUserIds(List.of(7L))).thenReturn(List.of(7L));
        when(identityMapper.selectActiveRoleIdsByRoleIds(List.of(2L, 3L)))
                .thenReturn(List.of(2L, 3L));
        when(identityMapper.selectApprovalEligibleUserIdsByUserIds(List.of(7L)))
                .thenReturn(List.of(7L));
        when(identityMapper.selectCopyEligibleUserIdsByRoleIds(List.of(2L)))
                .thenReturn(List.of(20L));
        when(identityMapper.selectCopyEligibleUserIdsByRoleIds(List.of(3L)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> validator.validate(document(task)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("自动抄送角色没有具备抄送列表和流程详情权限的有效成员")
                .hasMessageContaining("3");
    }

    /**
     * 验证自动抄送部门没有可读取抄送对象的有效成员时会阻止部署。
     *
     * @return 无返回值；不可见部门通过部署校验时测试失败
     */
    @Test
    void rejectsAutoCopyDeptWithoutVisibleMember()
    {
        UserTask task = withAutoCopyRules(task("7", null, List.of(), List.of()), """
                {"version":1,"rules":[{"id":"copy-dept","trigger":"NODE_ARRIVED","recipients":[
                  {"type":"GROUP","values":["DEPT3"]}
                ]}]}
                """);
        when(identityMapper.selectActiveUserIdsByUserIds(List.of(7L))).thenReturn(List.of(7L));
        when(identityMapper.selectActiveDeptIdsByDeptIds(List.of(3L))).thenReturn(List.of(3L));
        when(identityMapper.selectApprovalEligibleUserIdsByUserIds(List.of(7L)))
                .thenReturn(List.of(7L));
        when(identityMapper.selectCopyEligibleUserIdsByDeptIds(List.of(3L)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> validator.validate(document(task)))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("自动抄送部门没有具备抄送列表和流程详情权限的有效成员")
                .hasMessageContaining("3");
    }

    /**
     * 创建包含单个用户任务的 BPMN 文档。
     *
     * @param task UserTask，待加入流程的任务
     * @return WorkflowBpmnDocument，供静态身份校验使用的文档
     */
    private WorkflowBpmnDocument document(UserTask task)
    {
        BpmnModel model = new BpmnModel();
        Process process = new Process();
        process.setId("identity_process");
        process.addFlowElement(task);
        model.addProcess(process);
        return new WorkflowBpmnDocument(model, "<definitions/>", List.of());
    }

    /**
     * 创建带受控身份配置的用户任务。
     *
     * @param assignee String，静态或表达式办理人
     * @param owner String，静态或表达式任务所有人
     * @param candidateUsers List&lt;String&gt;，候选用户
     * @param candidateGroups List&lt;String&gt;，候选角色或部门
     * @return UserTask，完整测试任务
     */
    private UserTask task(String assignee, String owner, List<String> candidateUsers,
            List<String> candidateGroups)
    {
        UserTask task = new UserTask();
        task.setId("approve");
        task.setName("审批");
        task.setAssignee(assignee);
        task.setOwner(owner);
        task.setCandidateUsers(candidateUsers);
        task.setCandidateGroups(candidateGroups);
        return task;
    }

    /**
     * 给用户任务添加与设计器保存格式一致的自动抄送扩展属性。
     *
     * @param task UserTask，自动抄送规则所属用户任务
     * @param json String，符合 WorkflowAutoCopyRuleContract 的规则 JSON
     * @return UserTask，已经附加 flowable:property 的原任务
     */
    private UserTask withAutoCopyRules(UserTask task, String json)
    {
        ExtensionElement property = new ExtensionElement();
        property.setName("property");
        property.setNamespace("http://flowable.org/bpmn");
        property.addAttribute(new ExtensionAttribute("name",
                WorkflowAutoCopyRuleContract.PROPERTY_NAME));
        property.addAttribute(new ExtensionAttribute("value", json.strip()));

        ExtensionElement container = new ExtensionElement();
        container.setName("properties");
        container.setNamespace("http://flowable.org/bpmn");
        Map<String, List<ExtensionElement>> children = new HashMap<>();
        children.put("property", List.of(property));
        container.setChildElements(children);
        task.addExtensionElement(container);
        return task;
    }
}
