package com.ruoyi.flowable.service.notification;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flowable.engine.repository.ProcessDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfCopy;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Planner 只装配通知策略/偏好数据库，不装配任何 Flowable Service。 */
class WorkflowNotificationPlannerTest
{
    private JdbcTemplate jdbc;
    private WorkflowNotificationPlanner planner;
    private ProcessDefinition definition;

    @BeforeEach
    void setUp()
    {
        var source = new DriverManagerDataSource("jdbc:h2:mem:planner" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("notification-planning-schema.sql")).execute(source);
        jdbc = spy(new JdbcTemplate(source));
        planner = new WorkflowNotificationPlanner(jdbc);
        definition = mock(ProcessDefinition.class);
        when(definition.getKey()).thenReturn("approval"); when(definition.getName()).thenReturn("申请");
    }

    @org.junit.jupiter.api.AfterEach
    void closeDatabase() { jdbc.execute("shutdown"); }

    @Test
    void noPolicyStillValidatesRequestBoundaries()
    {
        assertThat(planner.plan(request("TASK_ARRIVED", "TASK:task:ARRIVED", "/detail")).isEmpty()).isTrue();
        assertThatThrownBy(() -> planner.plan(request("invalid", "TASK:task", "/detail"))).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> planner.plan(request("TASK_ARRIVED", "bad source", "/detail"))).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> planner.plan(request("TASK_ARRIVED", "TASK:task", " "))).isInstanceOf(ServiceException.class);
    }

    @Test
    void selectedPolicyIsReusedAndDisabledRecipientsAreFiltered()
    {
        policy("TASK_ARRIVED", "TASK_RECIPIENT");
        var selected = planner.selectPolicies(List.of(request("TASK_ARRIVED", "TASK:task", "/detail")));
        jdbc.update("update wf_notification_policy set title_template='changed',status='DISABLED'");
        clearInvocations(jdbc);
        var plan = planner.plan(selected);
        assertThat(plan.notifications()).extracting(NotificationPlan.Notification::recipientUserId).containsExactly("1");
        assertThat(plan.notifications().get(0).title()).isEqualTo("申请");
        assertThat(plan.notifications().get(0).actorUserId()).isEqualTo("2");
        verify(jdbc, never()).queryForList(contains("wf_notification_policy"), any(Object[].class));
    }

    @Test
    void copiesShareOnePolicyAndPreferenceReadAndKeepExplicitEmptyActor()
    {
        policy("COPY_CREATED", "TASK_RECIPIENT,ACTOR,INITIATOR");
        WfCopy first = copy(1L); WfCopy second = copy(2L);
        clearInvocations(jdbc);
        var plan = planner.planCopies(List.of(first, second), Map.of("definition", definition));
        assertThat(plan.notifications()).hasSize(2).allSatisfy(notification -> assertThat(notification.actorUserId()).isNull());
        assertThat(plan.notifications()).extracting(NotificationPlan.Notification::sourceId)
                .containsExactly("COPY:event:1", "COPY:event:2");
        verify(jdbc, times(1)).queryForList(contains("wf_notification_policy"), any(Object[].class));
        verify(jdbc, times(1)).queryForList(contains("wf_notification_preference"), any(Object[].class));
    }

    @Test
    void manualUrgeKeepsFrozenIdentityAndUserChannelPreferences()
    {
        policy("MANUAL_URGE", "TASK_RECIPIENT,ACTOR,INITIATOR");
        jdbc.update("insert into wf_notification_preference values (1,0,1,0)");
        var registration = new WorkflowManualUrgeRegistration("definition", "instance", null,
                "task", "review", "审批", null, Set.of("1"), "URGE:one", "请处理");
        var plan = planner.plan(WorkflowNotificationPlanner.NotificationRequest.manualUrge("URGE:one", definition,
                registration, "/detail", "\n催办原因：请处理"));
        assertThat(plan.notifications()).singleElement().satisfies(notification -> {
            assertThat(notification.actorUserId()).isNull();
            assertThat(notification.channels()).containsExactly("EMAIL");
            assertThat(notification.content()).endsWith("\n催办原因：请处理");
        });
    }

    private WorkflowNotificationPlanner.NotificationRequest request(String event, String source, String route)
    {
        return WorkflowNotificationPlanner.NotificationRequest.taskEvent(event, source, definition,
                "instance", "task", "review", "审批", Set.of("1", "3"), "2", null, route);
    }

    private void policy(String event, String rules)
    {
        jdbc.update("insert into wf_notification_policy values (1,?,'DEFAULT',null,null,'ENABLED',?,'INBOX,EMAIL',null,'{{processName}}','{{taskName}}',3)", event, rules);
    }

    private WfCopy copy(long user)
    {
        var copy = new WfCopy(); copy.setUserId(user); copy.setCopyEventId("event"); copy.setProcessId("definition");
        copy.setInstanceId("instance"); copy.setProcessName("申请"); copy.setTitle("审批"); copy.setTaskId("task");
        return copy;
    }
}
