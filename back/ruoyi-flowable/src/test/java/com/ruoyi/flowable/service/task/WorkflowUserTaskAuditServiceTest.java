package com.ruoyi.flowable.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.flowable.common.engine.impl.identity.Authentication;
import org.flowable.engine.TaskService;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.identitylink.api.IdentityLinkType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.identity.WorkflowIdentityCodec;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;

class WorkflowUserTaskAuditServiceTest
{
    private TaskService taskService;
    private WorkflowIdentityResolver identityResolver;
    private WorkflowUserTaskAuditService service;

    /**
     * 为每个测试创建 Flowable comment、正式身份解析器替身和真实用户主键规范器。
     *
     * @return void，无返回值
     */
    @BeforeEach
    void setUp()
    {
        taskService = mock(TaskService.class);
        identityResolver = mock(WorkflowIdentityResolver.class);
        service = new WorkflowUserTaskAuditService(taskService, identityResolver,
                new WorkflowIdentityCodec());
    }

    /**
     * 清理 Flowable ThreadLocal 认证身份，防止测试线程复用污染后续用例。
     *
     * @return void，无返回值
     */
    @AfterEach
    void clearAuthentication()
    {
        Authentication.setAuthenticatedUserId(null);
    }

    /**
     * 验证 complete 事件核验正式 assignee/owner 并写入不含业务正文和状态变量的结构化 comment。
     *
     * @return void，身份查询、审计字段或 Flowable comment 持久化调用不符合契约时测试失败
     * @throws Exception JSON 审计正文无法解析时测试失败
     */
    @Test
    void validatesUsersAndWritesStructuredCompletionAudit() throws Exception
    {
        LinkedHashSet<String> expectedUsers = new LinkedHashSet<>(List.of("7", "8", "9"));
        when(identityResolver.resolveApprovalEligibleUserIds(eq(expectedUsers)))
                .thenReturn(expectedUsers);
        Authentication.setAuthenticatedUserId("9");

        service.recordAudit("complete", "task-7", "instance-8",
                "expense:3:12001", "approveTask", "7", "8");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(taskService).addComment(eq("task-7"), eq("instance-8"),
                eq(WorkflowUserTaskAuditService.COMMENT_TYPE), body.capture());
        JsonNode audit = JsonMapper.shared().readTree(body.getValue());
        assertThat(audit.path("schemaVersion").intValue()).isEqualTo(1);
        assertThat(audit.path("action").textValue()).isEqualTo("USER_TASK_COMPLETE");
        assertThat(audit.path("event").textValue()).isEqualTo("complete");
        assertThat(audit.path("taskId").textValue()).isEqualTo("task-7");
        assertThat(audit.path("processInstanceId").textValue()).isEqualTo("instance-8");
        assertThat(audit.path("processDefinitionId").textValue())
                .isEqualTo("expense:3:12001");
        assertThat(audit.path("taskDefinitionKey").textValue()).isEqualTo("approveTask");
        assertThat(audit.path("actorUserId").textValue()).isEqualTo("9");
        assertThat(audit.path("assigneeUserId").textValue()).isEqualTo("7");
        assertThat(audit.path("ownerUserId").textValue()).isEqualTo("8");
        assertThat(audit.has("processStatus")).isFalse();
        assertThat(body.getValue()).doesNotContain("password", "token", "variables");
    }

    /**
     * 验证 candidateUser 和 candidateGroup 均存在真实可认领用户时允许创建任务并写入审计。
     *
     * @return void，候选身份未按完整 claim 资格核验或有效候选任务被误拒绝时测试失败
     */
    @Test
    void recordsCreateAuditForClaimEligibleCandidates()
    {
        IdentityLink candidateUser = mock(IdentityLink.class);
        when(candidateUser.getType()).thenReturn(IdentityLinkType.CANDIDATE);
        when(candidateUser.getUserId()).thenReturn("7");
        IdentityLink candidateGroup = mock(IdentityLink.class);
        when(candidateGroup.getType()).thenReturn(IdentityLinkType.CANDIDATE);
        when(candidateGroup.getGroupId()).thenReturn("ROLE104");
        when(taskService.getIdentityLinksForTask("task-7"))
                .thenReturn(List.of(candidateUser, candidateGroup));

        LinkedHashSet<String> directUsers = new LinkedHashSet<>(List.of("7"));
        LinkedHashSet<String> candidateGroups = new LinkedHashSet<>(List.of("ROLE104"));
        when(identityResolver.resolveClaimEligibleUserIds(eq(directUsers)))
                .thenReturn(directUsers);
        when(identityResolver.resolveClaimEligibleCandidateGroups(eq(candidateGroups)))
                .thenReturn(candidateGroups);

        service.recordAudit("create", "task-7", "instance-8",
                "expense:3:12001", "approveTask", null, null);

        verify(identityResolver, never()).resolveApprovalEligibleUserIds(any());
        verify(identityResolver).resolveClaimEligibleUserIds(directUsers);
        verify(identityResolver).resolveClaimEligibleCandidateGroups(candidateGroups);
        verify(taskService).addComment(eq("task-7"), eq("instance-8"),
                eq(WorkflowUserTaskAuditService.COMMENT_TYPE), anyString());
    }

    /**
     * 验证多个候选组中任一组无人具备完整 claim 资格时拒绝创建任务。
     *
     * @return void，无合格候选的组任务仍写入审计或未返回稳定身份错误时测试失败
     */
    @Test
    void rejectsCreateWhenAnyCandidateGroupHasNoClaimEligibleMember()
    {
        IdentityLink invalidCandidateGroup = mock(IdentityLink.class);
        when(invalidCandidateGroup.getType()).thenReturn(IdentityLinkType.CANDIDATE);
        when(invalidCandidateGroup.getGroupId()).thenReturn("ROLE104");
        IdentityLink validCandidateGroup = mock(IdentityLink.class);
        when(validCandidateGroup.getType()).thenReturn(IdentityLinkType.CANDIDATE);
        when(validCandidateGroup.getGroupId()).thenReturn("DEPT9");
        when(taskService.getIdentityLinksForTask("task-7"))
                .thenReturn(List.of(invalidCandidateGroup, validCandidateGroup));

        LinkedHashSet<String> candidateGroups = new LinkedHashSet<>(
                List.of("ROLE104", "DEPT9"));
        when(identityResolver.resolveClaimEligibleUserIds(eq(Set.of())))
                .thenReturn(Set.of());
        when(identityResolver.resolveClaimEligibleCandidateGroups(eq(candidateGroups)))
                .thenReturn(Set.of("DEPT9"));

        assertThatThrownBy(() -> service.recordAudit("create", "task-7", "instance-8",
                "expense:3:12001", "approveTask", null, null))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getMessage()).isEqualTo("用户任务办理身份无效");
                });

        verify(taskService, never()).addComment(anyString(), anyString(), anyString(), anyString());
    }

    /**
     * 验证动态候选表达式解析为空时拒绝创建任务，避免部署后产生无人可认领任务。
     *
     * @return void，空候选 identity link 被接受、触发无意义资格查询或写入审计时测试失败
     */
    @Test
    void rejectsCreateWhenDynamicCandidatesResolveEmpty()
    {
        when(taskService.getIdentityLinksForTask("task-7")).thenReturn(List.of());

        assertThatThrownBy(() -> service.recordAudit("create", "task-7", "instance-8",
                "expense:3:12001", "approveTask", null, null))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getMessage()).isEqualTo("用户任务办理身份无效");
                });

        verify(identityResolver, never()).resolveClaimEligibleUserIds(any());
        verify(identityResolver, never()).resolveClaimEligibleCandidateGroups(any());
        verify(taskService, never()).addComment(anyString(), anyString(), anyString(), anyString());
    }

    /**
     * 验证候选资格主数据查询损坏时保留 500 数据异常，不伪装成客户端身份参数错误。
     *
     * @return void；Mapper 一致性异常被降级为 400 或仍写入审计时测试失败
     */
    @Test
    void preservesMasterDataFailureWhenValidatingCandidates()
    {
        IdentityLink candidateGroup = mock(IdentityLink.class);
        when(candidateGroup.getType()).thenReturn(IdentityLinkType.CANDIDATE);
        when(candidateGroup.getGroupId()).thenReturn("ROLE104");
        when(taskService.getIdentityLinksForTask("task-7"))
                .thenReturn(List.of(candidateGroup));
        when(identityResolver.resolveClaimEligibleUserIds(eq(Set.of())))
                .thenReturn(Set.of());
        when(identityResolver.resolveClaimEligibleCandidateGroups(eq(Set.of("ROLE104"))))
                .thenThrow(new ServiceException("工作流身份主数据异常", HttpStatus.ERROR));

        assertThatThrownBy(() -> service.recordAudit("create", "task-7", "instance-8",
                "expense:3:12001", "approveTask", null, null))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.ERROR);
                    assertThat(exception.getMessage()).isEqualTo("用户任务监听数据异常");
                    assertThat(exception.getCause()).isInstanceOf(ServiceException.class);
                });
        verify(taskService, never()).addComment(anyString(), anyString(), anyString(), anyString());
    }

    /**
     * 验证 assignee 与 owner 为同一用户时只查询一次规范身份并正常写审计。
     *
     * @return void，重复身份导致重复查询、误拒绝或重复副作用时测试失败
     */
    @Test
    void deduplicatesSameAssigneeAndOwnerForValidation()
    {
        LinkedHashSet<String> expectedUsers = new LinkedHashSet<>(List.of("7"));
        when(identityResolver.resolveApprovalEligibleUserIds(eq(expectedUsers)))
                .thenReturn(expectedUsers);

        service.recordAudit("assignment", "task-7", "instance-8",
                "expense:3:12001", "approveTask", "7", "7");

        verify(identityResolver).resolveApprovalEligibleUserIds(expectedUsers);
        verify(taskService).addComment(eq("task-7"), eq("instance-8"),
                eq(WorkflowUserTaskAuditService.COMMENT_TYPE), anyString());
    }

    /**
     * 验证受控退回任务允许有效原发起人临时接管，同时仍实时核验退回操作人的审批资格。
     *
     * @return void，发起人被误要求审批权限或退回操作人未重新校验时测试失败
     */
    @Test
    void allowsActiveReturnedApplicantAssignedByEligibleApprover()
    {
        when(taskService.getVariableLocal("task-7",
                WorkflowTaskLifecycleService.RETURN_APPLICANT_VARIABLE)).thenReturn("7");
        when(identityResolver.resolveActiveUserIds(eq(List.of("7")), eq(List.of())))
                .thenReturn(Set.of("7"));
        LinkedHashSet<String> approver = new LinkedHashSet<>(List.of("9"));
        when(identityResolver.resolveApprovalEligibleUserIds(eq(approver)))
                .thenReturn(approver);
        Authentication.setAuthenticatedUserId("9");

        service.recordAudit("assignment", "task-7", "instance-8",
                "expense:3:12001", "approveTask", "7", null);

        verify(identityResolver).resolveActiveUserIds(List.of("7"), List.of());
        verify(identityResolver).resolveApprovalEligibleUserIds(approver);
        verify(taskService).addComment(eq("task-7"), eq("instance-8"),
                eq(WorkflowUserTaskAuditService.COMMENT_TYPE), anyString());
    }

    /**
     * 验证退回任务的原发起人停用或删除后不能接管审批任务。
     *
     * @return void，无效发起人仍完成 assignment 或写入监听审计时测试失败
     */
    @Test
    void rejectsInactiveReturnedApplicant()
    {
        when(taskService.getVariableLocal("task-7",
                WorkflowTaskLifecycleService.RETURN_APPLICANT_VARIABLE)).thenReturn("7");
        when(identityResolver.resolveActiveUserIds(eq(List.of("7")), eq(List.of())))
                .thenReturn(Set.of());
        Authentication.setAuthenticatedUserId("9");

        assertThatThrownBy(() -> service.recordAudit("assignment", "task-7", "instance-8",
                "expense:3:12001", "approveTask", "7", null))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getMessage()).isEqualTo("用户任务办理身份无效");
                });

        verify(taskService, never()).addComment(anyString(), anyString(), anyString(), anyString());
    }

    /**
     * 验证监听器拒绝前导零等非规范办理人，避免审计规范化后引擎仍保存不可查询身份。
     *
     * @return void，非规范 assignee 被写入任务或 comment 时测试失败
     */
    @Test
    void rejectsNonCanonicalTaskIdentityBeforeLookupOrComment()
    {
        assertThatThrownBy(() -> service.recordAudit("assignment", "task-7", "instance-8",
                "expense:3:12001", "approveTask", "007", "7"))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getMessage()).isEqualTo("用户任务办理身份无效");
                });

        verify(identityResolver, never()).resolveApprovalEligibleUserIds(any());
        verify(taskService, never()).addComment(anyString(), anyString(), anyString(), anyString());
    }

    /**
     * 验证任一 assignee/owner 已停用、删除或不存在时返回 400 且不写 comment。
     *
     * @return void，无效正式身份仍产生审计副作用时测试失败
     */
    @Test
    void rejectsInactiveOrMissingTaskUserBeforeComment()
    {
        LinkedHashSet<String> expectedUsers = new LinkedHashSet<>(List.of("7", "8"));
        when(identityResolver.resolveApprovalEligibleUserIds(eq(expectedUsers)))
                .thenReturn(Set.of("7"));

        assertThatThrownBy(() -> service.recordAudit("assignment", "task-7", "instance-8",
                "expense:3:12001", "approveTask", "7", "8"))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getMessage()).isEqualTo("用户任务办理身份无效");
                });

        verify(taskService, never()).addComment(anyString(), anyString(), anyString(), anyString());
    }

    /**
     * 验证领域服务自身也拒绝 delete 等未知事件，防止内部调用绕过监听器白名单。
     *
     * @return void，未知事件写入 comment 时测试失败
     */
    @Test
    void rejectsUnapprovedEventAtDomainBoundary()
    {
        assertThatThrownBy(() -> service.recordAudit("delete", "task-7", "instance-8",
                "expense:3:12001", "approveTask", null, null))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getMessage()).isEqualTo("用户任务监听事件不受支持");
                });

        verify(taskService, never()).addComment(anyString(), anyString(), anyString(), anyString());
    }

    /**
     * 验证 complete 事件缺失 Flowable 当前认证操作人时以数据异常回滚。
     *
     * @return void，匿名完成仍留下不完整审计时测试失败
     */
    @Test
    void rejectsCompletionWithoutAuthenticatedActor()
    {
        assertThatThrownBy(() -> service.recordAudit("complete", "task-7", "instance-8",
                "expense:3:12001", "approveTask", "7", null))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.ERROR);
                    assertThat(exception.getMessage()).isEqualTo("用户任务监听数据异常");
                });

        verify(taskService, never()).addComment(anyString(), anyString(), anyString(), anyString());
    }

    /**
     * 验证 complete 当前认证操作人已停用或删除时返回 400 并回滚 comment。
     *
     * @return void，无效操作人仍形成完成审计时测试失败
     */
    @Test
    void rejectsInactiveAuthenticatedCompletionActor()
    {
        LinkedHashSet<String> expectedUsers = new LinkedHashSet<>(List.of("7", "9"));
        when(identityResolver.resolveApprovalEligibleUserIds(eq(expectedUsers)))
                .thenReturn(Set.of("7"));
        Authentication.setAuthenticatedUserId("9");

        assertThatThrownBy(() -> service.recordAudit("complete", "task-7", "instance-8",
                "expense:3:12001", "approveTask", "7", null))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getMessage()).isEqualTo("用户任务办理身份无效");
                });

        verify(taskService, never()).addComment(anyString(), anyString(), anyString(), anyString());
    }
}
