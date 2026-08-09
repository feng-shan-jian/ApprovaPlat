package com.ruoyi.flowable.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Answers.RETURNS_SELF;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.EndEvent;
import org.flowable.bpmn.model.ExtensionAttribute;
import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.repository.ProcessDefinitionQuery;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.engine.runtime.ProcessInstanceQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfCopy;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.mapper.WfCopyMapper;
import com.ruoyi.flowable.service.model.WorkflowAutoCopyRuleContract;
import com.ruoyi.system.mapper.SysUserMapper;
import com.ruoyi.flowable.service.notification.WorkflowNotificationService;

/**
 * 自动抄送运行时身份解析、事件幂等键和失败零写入测试。
 */
@ExtendWith(MockitoExtension.class)
class WorkflowAutomaticCopyServiceTest
{
    @Mock private RepositoryService repositoryService;
    @Mock private RuntimeService runtimeService;
    @Mock private HistoryService historyService;
    @Mock private WorkflowIdentityResolver identityResolver;
    @Mock private WfCopyMapper copyMapper;
    @Mock private SysUserMapper userMapper;
    @Mock private WorkflowNotificationService notificationService;

    private ProcessDefinitionQuery definitionQuery;
    private ProcessInstanceQuery instanceQuery;
    private WorkflowAutomaticCopyService service;

    /**
     * 建立真实 BPMN 模型之外的正式服务依赖查询链。
     * @return void，每个测试获得独立 mock 状态
     */
    @BeforeEach
    void setUp()
    {
        definitionQuery = mock(ProcessDefinitionQuery.class, RETURNS_SELF);
        instanceQuery = mock(ProcessInstanceQuery.class, RETURNS_SELF);
        service = new WorkflowAutomaticCopyService(repositoryService, runtimeService,
                historyService, identityResolver, copyMapper, userMapper);
        service.setNotificationService(notificationService);
    }

    /**
     * 验证当前定义没有任务级自动抄送规则时，即使运行时任务 key 无法解析也合法无副作用。
     * @return void，若访问运行实例、身份、数据库或通知依赖则测试失败
     */
    @Test
    void ignoresTaskEventWithoutTaskAutoCopyRulesBeforeRuntimeValidation()
    {
        stubDeployment(null);

        service.onTaskEvent("create", "missing-task", "missing-instance", "definition-1",
                "missing-node", "不存在的节点", Map.of());

        verify(runtimeService, never()).createProcessInstanceQuery();
        verifyNoInteractions(identityResolver, copyMapper, userMapper, notificationService);
    }

    /**
     * 验证当前定义存在任务级规则时，无法映射的运行时任务节点仍按部署不一致失败。
     * @return void，若错误被静默降级或产生业务副作用则测试失败
     */
    @Test
    void rejectsMissingRuntimeTaskNodeWhenDeploymentContainsTaskRules()
    {
        stubDeployment("""
                {"version":1,"rules":[{"id":"arrival","trigger":"NODE_ARRIVED",
                "recipients":[{"type":"USER","values":["2"]}]}]}
                """);

        assertThatThrownBy(() -> service.onTaskEvent("create", "task-12", "instance-1",
                "definition-1", "missing-node", "不存在的节点", Map.of()))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("自动抄送触发节点不存在");

        verify(runtimeService, never()).createProcessInstanceQuery();
        verifyNoInteractions(identityResolver, copyMapper, userMapper, notificationService);
    }

    /**
     * 验证当前定义存在任务级规则时，运行时 key 指向非 UserTask 元素必须按模型不一致失败。
     * @return void，若非用户任务被当作合法无规则节点或产生副作用则测试失败
     */
    @Test
    void rejectsNonUserTaskRuntimeNodeWhenDeploymentContainsTaskRules()
    {
        stubDeployment("""
                {"version":1,"rules":[{"id":"arrival","trigger":"NODE_ARRIVED",
                "recipients":[{"type":"USER","values":["2"]}]}]}
                """);

        assertThatThrownBy(() -> service.onTaskEvent("create", "task-12", "instance-1",
                "definition-1", "end", "结束", Map.of()))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("自动抄送触发节点不是用户任务");

        verify(runtimeService, never()).createProcessInstanceQuery();
        verifyNoInteractions(identityResolver, copyMapper, userMapper, notificationService);
    }

    /**
     * 验证固定用户、角色部门、发起人和表单字段合并后只写一次节点完成事件。
     * @return void，来源解析、事件键、快照或去重不符合契约时测试失败
     */
    @Test
    void writesResolvedRecipientsForNodeCompletionWithStableEventKey()
    {
        stubProcess("""
                {"version":1,"rules":[{"id":"complete","trigger":"NODE_COMPLETED",
                "recipients":[{"type":"USER","values":["2"]},
                {"type":"GROUP","values":["ROLE9","DEPT8"]},
                {"type":"INITIATOR"},
                {"type":"FORM_USER_FIELD","values":["reviewers"]}]}]}
                """);
        when(identityResolver.resolveCopyEligibleUserIds(
                eq(Set.of("2", "7", "3", "4")), eq(Set.of("ROLE9", "DEPT8"))))
                .thenReturn(Set.of("2", "3", "4", "7", "10"));
        when(copyMapper.insertBatchIdempotent(anyList())).thenReturn(5);

        service.onTaskEvent("complete", "task-11", "instance-1", "definition-1",
                "review", "复核", Map.of("reviewers", List.of(3.0D, "4")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WfCopy>> captor = ArgumentCaptor.forClass(List.class);
        verify(copyMapper).insertBatchIdempotent(captor.capture());
        verify(notificationService).onCopiesCreated(captor.getValue());
        assertThat(captor.getValue()).hasSize(5).allSatisfy(copy ->
        {
            assertThat(copy.getCopyEventId()).isEqualTo("TASK_COMPLETED:task-11");
            assertThat(copy.getSourceType()).isEqualTo("AUTO");
            assertThat(copy.getTriggerType()).isEqualTo("NODE_COMPLETED");
            assertThat(copy.getTriggerNodeId()).isEqualTo("review");
            assertThat(copy.getTaskId()).isEqualTo("task-11");
        });
    }

    /**
     * 验证表单数值字段的小数用户主键不会被 longValue 静默截断并抄送给错误用户。
     * @return void，无返回值；小数被规范成其他用户或产生抄送副作用时测试失败
     */
    @Test
    void rejectsFractionalFormUserIdWithoutCopySideEffects()
    {
        stubProcess("""
                {"version":1,"rules":[{"id":"complete","trigger":"NODE_COMPLETED",
                "recipients":[{"type":"FORM_USER_FIELD","values":["reviewer"]}]}]}
                """);

        assertThatThrownBy(() -> service.onTaskEvent("complete", "task-fraction",
                "instance-1", "definition-1", "review", "复核",
                Map.of("reviewer", 1.5D)))
                .isInstanceOf(ServiceException.class)
                .hasMessage("表单用户字段值不合法");

        verifyNoInteractions(identityResolver, copyMapper, notificationService);
    }

    /**
     * 验证重复监听、并行节点和退回重入都以 Flowable 任务主键形成稳定且互不冲突的事件键。
     * @return void，同一任务键漂移或不同任务键碰撞时测试失败
     */
    @Test
    void keepsStableKeysForDuplicateParallelAndReenteredTaskEvents()
    {
        stubProcess("""
                {"version":1,"rules":[{"id":"arrival","trigger":"NODE_ARRIVED",
                "recipients":[{"type":"USER","values":["2"]}]}]}
                """);
        when(identityResolver.resolveCopyEligibleUserIds(eq(Set.of("2")), eq(Set.of())))
                .thenReturn(Set.of("2"));
        when(copyMapper.insertBatchIdempotent(anyList())).thenReturn(1);

        // 第一次与重复监听使用同一任务主键；并行分支和退回重入由 Flowable 生成新的任务主键。
        for (String taskId : List.of("task-same", "task-same", "task-parallel", "task-reentered"))
        {
            service.onTaskEvent("create", taskId, "instance-1", "definition-1",
                    "review", "复核", Map.of());
        }

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WfCopy>> captor = ArgumentCaptor.forClass(List.class);
        verify(copyMapper, times(4)).insertBatchIdempotent(captor.capture());
        List<String> eventKeys = captor.getAllValues().stream()
                .map(copies -> copies.get(0).getCopyEventId())
                .toList();
        assertThat(eventKeys).containsExactly(
                "TASK_ARRIVED:task-same",
                "TASK_ARRIVED:task-same",
                "TASK_ARRIVED:task-parallel",
                "TASK_ARRIVED:task-reentered");
        assertThat(eventKeys.stream().distinct()).hasSize(3);
    }

    /**
     * 验证固定或表单用户失效、无抄送可见权限时中止生命周期且不写 wf_copy。
     * @return void，失效用户被静默遗漏或留下部分记录时测试失败
     */
    @Test
    void rejectsInactiveOrInvisibleDirectRecipientWithoutDatabaseWrite()
    {
        stubProcess("""
                {"version":1,"rules":[{"id":"arrival","trigger":"NODE_ARRIVED",
                "recipients":[{"type":"USER","values":["2","99"]}]}]}
                """);
        when(identityResolver.resolveCopyEligibleUserIds(
                eq(Set.of("2", "99")), eq(Set.of()))).thenReturn(Set.of("2"));

        assertThatThrownBy(() -> service.onTaskEvent("create", "task-12", "instance-1",
                "definition-1", "review", "复核", Map.of()))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("失效或无对象可见权限");

        verify(copyMapper, never()).insertBatchIdempotent(anyList());
    }

    /**
     * 配置活动实例、部署定义、发起人和带自动抄送属性的真实 BPMN 模型。
     * @param ruleJson String，用户任务规则 JSON
     * @return void，查询依赖可供生命周期方法执行
     */
    private void stubProcess(String ruleJson)
    {
        ProcessDefinition definition = stubDeployment(ruleJson);

        ProcessInstance instance = mock(ProcessInstance.class);
        when(instance.getProcessDefinitionId()).thenReturn("definition-1");
        when(instance.getStartUserId()).thenReturn("7");
        when(runtimeService.createProcessInstanceQuery()).thenReturn(instanceQuery);
        when(instanceQuery.singleResult()).thenReturn(instance);

        when(definition.getName()).thenReturn("抄送流程");
        when(definition.getCategory()).thenReturn("OA");
        when(definition.getDeploymentId()).thenReturn("deploy-1");

        SysUser initiator = new SysUser();
        initiator.setUserId(7L);
        initiator.setNickName("发起人");
        initiator.setUserName("starter");
        when(userMapper.selectUserById(7L)).thenReturn(initiator);
    }

    /**
     * 配置包含多个 process 的部署模型，并让目标流程由流程定义 key 精确定位。
     * @param ruleJson String，可空的目标用户任务规则 JSON；为空表示普通用户任务
     * @return ProcessDefinition，供需要运行时上下文的测试继续补充定义元数据
     */
    private ProcessDefinition stubDeployment(String ruleJson)
    {
        ProcessDefinition definition = mock(ProcessDefinition.class);
        when(definition.getKey()).thenReturn("copy_process");
        when(repositoryService.createProcessDefinitionQuery()).thenReturn(definitionQuery);
        when(definitionQuery.singleResult()).thenReturn(definition);

        Process unrelated = new Process();
        unrelated.setId("unrelated_process");
        UserTask unrelatedTask = new UserTask();
        unrelatedTask.setId("unrelatedReview");
        unrelated.addFlowElement(unrelatedTask);
        Process process = new Process();
        process.setId("copy_process");
        UserTask task = new UserTask();
        task.setId("review");
        task.setName("复核");
        if (ruleJson != null)
        {
            addRules(task, ruleJson.strip());
        }
        process.addFlowElement(task);
        EndEvent end = new EndEvent();
        end.setId("end");
        process.addFlowElement(end);
        BpmnModel model = new BpmnModel();
        model.addProcess(unrelated);
        model.addProcess(process);
        when(repositoryService.getBpmnModel("definition-1")).thenReturn(model);
        return definition;
    }

    /**
     * 写入标准 Flowable properties 扩展容器。
     * @param task UserTask，规则所属节点
     * @param json String，受控规则 JSON
     * @return void，扩展元素直接挂到任务模型
     */
    private void addRules(UserTask task, String json)
    {
        ExtensionElement property = new ExtensionElement();
        property.setName("property");
        property.setNamespace("http://flowable.org/bpmn");
        property.addAttribute(new ExtensionAttribute("name",
                WorkflowAutoCopyRuleContract.PROPERTY_NAME));
        property.addAttribute(new ExtensionAttribute("value", json));
        ExtensionElement container = new ExtensionElement();
        container.setName("properties");
        container.setNamespace("http://flowable.org/bpmn");
        Map<String, List<ExtensionElement>> children = new HashMap<>();
        children.put("property", List.of(property));
        container.setChildElements(children);
        task.addExtensionElement(container);
    }
}
