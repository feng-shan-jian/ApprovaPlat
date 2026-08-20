package com.ruoyi.flowable.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.common.engine.impl.identity.Authentication;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.identitylink.api.IdentityLinkType;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.jdbc.core.JdbcTemplate;
import com.ruoyi.flowable.domain.WfCopy;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;

class WorkflowNotificationPlannerTest
{
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final RuntimeService runtimeService = mock(RuntimeService.class, Answers.RETURNS_DEEP_STUBS);
    private final HistoryService historyService = mock(HistoryService.class, Answers.RETURNS_DEEP_STUBS);
    private final TaskService taskService = mock(TaskService.class);
    private final WorkflowIdentityResolver identityResolver = mock(WorkflowIdentityResolver.class);
    private WorkflowNotificationPlanner planner;
    private List<Map<String, Object>> policyRows;
    private List<Map<String, Object>> preferenceRows;

    /**
     * 初始化规划器的策略和用户偏好查询桩，保持测试只验证规划业务而不伪造写入成功。
     * @return void，无返回值
     */
    @BeforeEach
    void setUp()
    {
        planner = new WorkflowNotificationPlanner(jdbcTemplate, runtimeService,
                historyService, taskService, identityResolver);
        policyRows = new ArrayList<>();
        preferenceRows = new ArrayList<>();
        doAnswer(invocation ->
        {
            String sql = invocation.getArgument(0, String.class);
            if (sql.contains("wf_notification_policy")) return policyRows;
            if (sql.contains("from sys_user")) return preferenceRows;
            throw new AssertionError("unexpected planner query: " + sql);
        }).when(jdbcTemplate).queryForList(anyString(), any(Object[].class));
    }

    /**
     * 验证节点策略优先于流程和默认策略。
     * @return void，优先级漂移时测试失败
     */
    @Test
    void prefersNodePolicyOverProcessAndDefault()
    {
        policyRows.add(policy(1L, "DEFAULT", "TASK_RECIPIENT", "INBOX", "default"));
        policyRows.add(policy(2L, "PROCESS", "TASK_RECIPIENT", "INBOX", "process"));
        policyRows.add(policy(3L, "NODE", "TASK_RECIPIENT", "INBOX", "node"));
        preferenceRows.add(preference("7", true, true, false));

        NotificationPlan plan = planner.plan(request("TASK_ARRIVED", "TASK:t:ARRIVED",
                "purchase", "approve", Set.of("7"), null, null));

        assertThat(plan.notifications()).singleElement()
                .extracting(NotificationPlan.Notification::title).isEqualTo("node");
    }

    /**
     * 验证候选用户和 ROLE 组经过正式身份目录解析后再参与通知计划。
     * @return void，组解析或认领资格过滤错误时测试失败
     */
    @Test
    void resolvesCandidateUsersAndGroups()
    {
        policyRows.add(policy(1L, "DEFAULT", "TASK_RECIPIENT", "INBOX", "title"));
        preferenceRows.add(preference("7", true, true, false));
        Task task = mock(Task.class);
        when(task.getAssignee()).thenReturn(null);
        when(task.getId()).thenReturn("task-1");
        IdentityLink userLink = mock(IdentityLink.class);
        when(userLink.getType()).thenReturn(IdentityLinkType.CANDIDATE);
        when(userLink.getUserId()).thenReturn("7");
        when(userLink.getGroupId()).thenReturn(null);
        IdentityLink groupLink = mock(IdentityLink.class);
        when(groupLink.getType()).thenReturn(IdentityLinkType.CANDIDATE);
        when(groupLink.getUserId()).thenReturn(null);
        when(groupLink.getGroupId()).thenReturn("ROLE2");
        when(taskService.getIdentityLinksForTask("task-1"))
                .thenReturn(List.of(userLink, groupLink));
        when(identityResolver.resolveActiveUserIds(anyCollection(), anyCollection()))
                .thenReturn(Set.of("7"));
        when(identityResolver.resolveClaimEligibleUserIds(eq(Set.of("7"))))
                .thenReturn(Set.of("7"));

        NotificationPlan plan = planner.plan(new WorkflowNotificationPlanner.NotificationRequest(
                "TASK_ARRIVED", "TASK:task-1:ARRIVED", "purchase", "Purchase", "instance-1",
                "task-1", "approve", "Approve", task, Set.of(), null, false, null, false,
                "/workflow/process-detail/instance-1?source=todo&taskId=task-1", null));

        assertThat(plan.notifications()).extracting(NotificationPlan.Notification::recipientUserId)
                .containsExactly("7");
    }

    /**
     * 验证任务接收人、发起人和操作者重复时只生成一个接收人事实。
     * @return void，重复通知身份未合并时测试失败
     */
    @Test
    void deduplicatesRecipientsAcrossRules()
    {
        policyRows.add(policy(1L, "DEFAULT", "TASK_RECIPIENT,INITIATOR,ACTOR",
                "INBOX", "title"));
        preferenceRows.add(preference("7", true, true, false));
        NotificationPlan plan = planner.plan(request("TASK_COMPLETED", "TASK:t:complete",
                "purchase", null, Set.of("7"), "7", "7"));

        assertThat(plan.notifications()).hasSize(1);
    }

    /**
     * 验证普通策略按一次批量偏好读取结果过滤通道，并保留默认开关语义。
     * @return void，偏好过滤错误时测试失败
     */
    @Test
    void appliesUserChannelPreferences()
    {
        policyRows.add(policy(1L, "DEFAULT", "TASK_RECIPIENT", "INBOX,EMAIL,SMS", "title"));
        preferenceRows.add(preference("7", true, false, true));

        NotificationPlan plan = planner.plan(request("TASK_ARRIVED", "TASK:t:arrived",
                "purchase", null, Set.of("7"), null, null));

        assertThat(plan.notifications()).singleElement();
        assertThat(plan.notifications().get(0).channels())
                .containsExactly("INBOX", "SMS");
    }

    /**
     * 验证规划结果保持接收人首次出现顺序，并在策略不使用 ACTOR 收件规则时仍冻结操作者审计字段。
     * @return void，批量写入顺序或操作者上下文漂移时测试失败
     */
    @Test
    void preservesRecipientOrderAndActorAuditContext()
    {
        policyRows.add(policy(1L, "DEFAULT", "TASK_RECIPIENT",
                "INBOX,EMAIL,SMS", "title"));
        preferenceRows.add(preference("8", true, true, true));
        preferenceRows.add(preference("7", true, true, true));
        Authentication.setAuthenticatedUserId("9");
        try
        {
            NotificationPlan plan = planner.plan(new WorkflowNotificationPlanner.NotificationRequest(
                    "TASK_ARRIVED", "TASK:t:ordered", "purchase", "Purchase", "instance-1",
                    "task-1", "approve", "Approve", null,
                    new LinkedHashSet<>(List.of("8", "7")), null, true, null, false,
                    "/workflow/process-detail/instance-1?source=todo&taskId=task-1", null));

            assertThat(plan.notifications())
                    .extracting(NotificationPlan.Notification::recipientUserId)
                    .containsExactly("8", "7");
            assertThat(plan.notifications()).allSatisfy(notification ->
            {
                assertThat(notification.actorUserId()).isEqualTo("9");
                assertThat(notification.channels()).containsExactly("INBOX", "EMAIL", "SMS");
            });
        }
        finally
        {
            Authentication.setAuthenticatedUserId(null);
        }
    }

    /**
     * 验证催办原因在规划阶段只拼接一次并参与最终长度截断。
     * @return void，Writer 前仍需二次 UPDATE 时测试失败
     */
    @Test
    void appendsUrgeReasonOnce()
    {
        policyRows.add(policy(1L, "DEFAULT", "TASK_RECIPIENT", "INBOX", "正文"));
        preferenceRows.add(preference("7", true, true, false));
        NotificationPlan plan = planner.plan(new WorkflowNotificationPlanner.NotificationRequest(
                "MANUAL_URGE", "URGE:1", "purchase", "Purchase", "instance-1", "task-1",
                "approve", "Approve", null, Set.of("7"), "9", false, "8", false,
                "/workflow/process-detail/instance-1?source=todo&taskId=task-1",
                "\n催办原因：请尽快处理"));

        assertThat(plan.notifications()).singleElement()
                .extracting(NotificationPlan.Notification::content)
                .isEqualTo("正文\n催办原因：请尽快处理");
    }

    /**
     * 验证抄送自然来源键使用 copyEventId 和接收人，而不是 copy_id。
     * @return void，自然幂等键漂移时测试失败
     */
    @Test
    void buildsCopyNaturalIdempotencyKey()
    {
        policyRows.add(policy(1L, "DEFAULT", "TASK_RECIPIENT", "INBOX", "title"));
        preferenceRows.add(preference("7", true, true, false));
        ProcessDefinition definition = mock(ProcessDefinition.class);
        when(definition.getKey()).thenReturn("purchase");
        WfCopy copy = new WfCopy();
        copy.setCopyEventId("TASK_COMPLETED:task-1");
        copy.setProcessId("definition-1");
        copy.setProcessName("Purchase");
        copy.setInstanceId("instance-1");
        copy.setTaskId("task-1");
        copy.setUserId(7L);
        copy.setTitle("Approval copy");
        copy.setCreateBy("9");

        NotificationPlan plan = planner.planCopies(List.of(copy),
                Map.of("definition-1", definition));

        assertThat(plan.notifications()).singleElement()
                .extracting(NotificationPlan.Notification::sourceId)
                .isEqualTo("COPY:TASK_COMPLETED:task-1:7");
    }

    /**
     * 构造最小普通规划请求。
     * @param eventType String，事件类型
     * @param sourceId String，稳定来源键
     * @param processKey String，流程 key
     * @param taskKey String，可空节点 key
     * @param recipients Set&lt;String&gt;，任务接收人
     * @param initiator String，可空发起人
     * @param actor String，可空操作者
     * @return WorkflowNotificationPlanner.NotificationRequest，测试规划请求
     */
    private WorkflowNotificationPlanner.NotificationRequest request(String eventType,
            String sourceId, String processKey, String taskKey, Set<String> recipients,
            String initiator, String actor)
    {
        return new WorkflowNotificationPlanner.NotificationRequest(eventType, sourceId,
                processKey, "Purchase", "instance-1", "task-1", taskKey, "Approve", null,
                recipients, actor, false, initiator, false,
                "/workflow/process-detail/instance-1?source=todo&taskId=task-1", null);
    }

    /**
     * 构造策略查询行。
     * @param policyId long，策略主键
     * @param scope String，策略作用域
     * @param recipientRules String，接收人规则 CSV
     * @param channels String，策略通道 CSV
     * @param title String，标题模板
     * @return Map&lt;String,Object&gt;，模拟数据库策略投影
     */
    private Map<String, Object> policy(long policyId, String scope, String recipientRules,
            String channels, String title)
    {
        Map<String, Object> row = new HashMap<>();
        row.put("policyId", policyId);
        row.put("scopeType", scope);
        row.put("recipientRules", recipientRules);
        row.put("channels", channels);
        row.put("smsTemplateId", channels.contains("SMS") ? "sms-template" : null);
        row.put("titleTemplate", title);
        row.put("contentTemplate", title);
        row.put("maxAttempts", 6);
        return row;
    }

    /**
     * 构造用户状态和偏好查询行。
     * @param userId String，用户主键
     * @param inbox boolean，站内开关
     * @param email boolean，邮件开关
     * @param sms boolean，短信开关
     * @return Map&lt;String,Object&gt;，模拟数据库用户偏好投影
     */
    private Map<String, Object> preference(String userId, boolean inbox, boolean email,
            boolean sms)
    {
        return Map.of("userId", userId, "inboxEnabled", inbox ? 1 : 0,
                "emailEnabled", email ? 1 : 0, "smsEnabled", sms ? 1 : 0);
    }
}
