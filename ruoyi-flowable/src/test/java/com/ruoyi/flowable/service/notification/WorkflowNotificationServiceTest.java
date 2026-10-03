package com.ruoyi.flowable.service.notification;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.flowable.common.engine.impl.identity.Authentication;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.mapper.WfCopyMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 入口事实读取顺序通过真实 Planner/SQL 验证，外部写入计划在边界捕获。 */
class WorkflowNotificationServiceTest
{
    private final RuntimeService runtime = mock(RuntimeService.class);
    private final HistoryService history = mock(HistoryService.class);
    private final TaskService tasks = mock(TaskService.class);
    private final RepositoryService repository = mock(RepositoryService.class);
    private final WorkflowIdentityResolver identities = mock(WorkflowIdentityResolver.class);
    private final WorkflowNotificationOutboxService outbox = mock(WorkflowNotificationOutboxService.class);
    private final WfCopyMapper copies = mock(WfCopyMapper.class);
    private final Task task = mock(Task.class);
    private final TaskQuery query = mock(TaskQuery.class, RETURNS_SELF);
    private final AtomicReference<NotificationPlan> written = new AtomicReference<>();
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private WorkflowNotificationService service;

    @BeforeEach
    void setUp()
    {
        var source = new DriverManagerDataSource("jdbc:h2:mem:notification" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("notification-planning-schema.sql")).execute(source);
        jdbc = spy(new JdbcTemplate(source)); transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        var writer = mock(WorkflowNotificationWriter.class);
        when(writer.write(any())).thenAnswer(invocation -> {
            NotificationPlan plan = invocation.getArgument(0); written.set(plan);
            return new WorkflowNotificationWriter.WriteResult(plan.notifications().size(), Set.of());
        });
        service = new WorkflowNotificationService(jdbc, repository, runtime, tasks, history, identities,
                new WorkflowNotificationPlanner(jdbc), writer, outbox, copies);
        var definition = mock(ProcessDefinition.class);
        when(definition.getKey()).thenReturn("approval"); when(definition.getName()).thenReturn("申请");
        when(repository.getProcessDefinition("definition")).thenReturn(definition);
        when(tasks.createTaskQuery()).thenReturn(query); when(query.singleResult()).thenReturn(task);
        when(task.getId()).thenReturn("task"); when(task.getProcessInstanceId()).thenReturn("instance");
        when(task.getProcessDefinitionId()).thenReturn("definition"); when(task.getTaskDefinitionKey()).thenReturn("review");
        when(task.getName()).thenReturn("审批"); when(task.getAssignee()).thenReturn("1");
        when(identities.resolveActiveUserIds(List.of("1"), List.of())).thenReturn(Set.of("1"));
        Authentication.setAuthenticatedUserId("2");
    }

    @AfterEach
    void clearIdentity()
    {
        Authentication.setAuthenticatedUserId(null);
        jdbc.execute("shutdown");
    }

    @Test
    void noPolicyDoesNotReadRecipientsOrInitiatorAndInvalidSourceStillFails()
    {
        transaction.executeWithoutResult(status -> service.onStableTaskEvent("TASK_RETURNED", task));
        assertThat(written.get().isEmpty()).isTrue();
        verifyNoInteractions(identities, runtime, history);
        verify(tasks, never()).getIdentityLinksForTask(anyString());
        when(task.getId()).thenReturn("invalid task");
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> service.onStableTaskEvent("TASK_RETURNED", task)))
                .isInstanceOf(ServiceException.class).hasMessage("通知来源标识不合法");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"TASK_RETURNED", "TASK_RESUBMITTED"})
    void taskRecipientOnlyStillFreezesAuditActorAndReadsPolicyOnce(String event)
    {
        policy(event); clearInvocations(jdbc);
        transaction.executeWithoutResult(status -> service.onStableTaskEvent(event, task));
        assertThat(written.get().notifications()).singleElement().satisfies(notification -> {
            assertThat(notification.actorUserId()).isEqualTo("2");
            assertThat(notification.recipientUserId()).isEqualTo("1");
            assertThat(notification.sourceId()).isEqualTo("TASK:task:" + event);
        });
        verifyNoInteractions(runtime, history);
        verify(jdbc, times(1)).queryForList(contains("wf_notification_policy"), any(Object[].class));
    }

    @Test
    void unclaimedRecipientsKeepClaimEligibilityAndTransferRevision()
    {
        policy("TASK_TRANSFERRED"); when(task.getAssignee()).thenReturn(null);
        var link = mock(IdentityLink.class); when(link.getType()).thenReturn("candidate"); when(link.getUserId()).thenReturn("1");
        when(tasks.getIdentityLinksForTask("task")).thenReturn(List.of(link));
        when(identities.resolveActiveUserIds(Set.of("1"), Set.of())).thenReturn(Set.of("1", "2"));
        when(identities.resolveClaimEligibleUserIds(Set.of("1", "2"))).thenReturn(Set.of("1"));
        transaction.executeWithoutResult(status -> service.onStableTaskAction("TASK_TRANSFERRED", "task"));
        assertThat(written.get().notifications()).singleElement().satisfies(notification -> {
            assertThat(notification.recipientUserId()).isEqualTo("1");
            assertThat(notification.sourceId()).isEqualTo("TASK:task:TASK_TRANSFERRED:r7");
        });
        verify(outbox).cancelPendingUrges(eq("instance"), eq("task"), anyString());
    }

    @Test
    void completedTaskUsesFrozenAssigneeWhenTaskNoLongerExists()
    {
        policy("TASK_COMPLETED"); when(query.singleResult()).thenReturn(null);
        transaction.executeWithoutResult(status -> service.onTaskEvent("complete", "task", "instance", "definition", "review", "审批", "1"));
        assertThat(written.get().notifications()).singleElement().satisfies(notification -> {
            assertThat(notification.recipientUserId()).isEqualTo("1");
            assertThat(notification.sourceId()).isEqualTo("TASK:task:complete:0");
        });
        verifyNoInteractions(identities, history);
        verify(outbox).schedulePendingUrgeCancellation(eq("instance"), eq("task"), anyString());
    }

    @Test
    void manualUrgeEmptyActorNeverFallsBackToThreadIdentity()
    {
        policy("MANUAL_URGE");
        var registration = new WorkflowManualUrgeRegistration("definition", "instance", null, "task", "review", "审批",
                null, Set.of("1"), "URGE:task", "请处理");
        transaction.executeWithoutResult(status -> service.registerManualUrge(registration));
        assertThat(written.get().notifications()).singleElement().satisfies(notification -> assertThat(notification.actorUserId()).isNull());
        verifyNoInteractions(identities, runtime, history);
    }

    @Test
    void processResultReadsHistoricInitiatorOnlyWhenSelected()
    {
        policy("PROCESS_COMPLETED"); jdbc.update("update wf_notification_policy set recipient_rules='INITIATOR'");
        var runtimeQuery = mock(org.flowable.engine.runtime.ProcessInstanceQuery.class, RETURNS_SELF);
        when(runtime.createProcessInstanceQuery()).thenReturn(runtimeQuery);
        var historicQuery = mock(org.flowable.engine.history.HistoricProcessInstanceQuery.class, RETURNS_SELF);
        var historic = mock(org.flowable.engine.history.HistoricProcessInstance.class);
        when(history.createHistoricProcessInstanceQuery()).thenReturn(historicQuery);
        when(historicQuery.singleResult()).thenReturn(historic); when(historic.getStartUserId()).thenReturn("1");
        transaction.executeWithoutResult(status -> service.onProcessResult("PROCESS_COMPLETED", "definition", "instance"));
        assertThat(written.get().notifications()).singleElement().satisfies(notification -> {
            assertThat(notification.recipientUserId()).isEqualTo("1");
            assertThat(notification.actorUserId()).isEqualTo("2");
        });
        verifyNoInteractions(identities, tasks);
    }

    @Test
    void copiesReadOneDefinitionAndPreferenceBatchAndNeverResolveThreadIdentity()
    {
        policy("COPY_CREATED");
        var values = new java.util.ArrayList<com.ruoyi.flowable.domain.WfCopy>();
        for (long user : List.of(1L, 2L))
        {
            var copy = new com.ruoyi.flowable.domain.WfCopy(); copy.setUserId(user); copy.setCopyEventId("copy-event");
            copy.setProcessId("definition"); copy.setInstanceId("instance"); copy.setProcessName("申请"); copy.setTitle("审批");
            values.add(copy);
        }
        when(copies.selectActiveByNaturalKeys(anyList())).thenReturn(values);
        transaction.executeWithoutResult(status -> service.onCopiesCreated(values));
        assertThat(written.get().notifications()).hasSize(2).allSatisfy(notification -> assertThat(notification.actorUserId()).isNull());
        verify(repository, times(1)).getProcessDefinition("definition");
        verify(jdbc, times(1)).queryForList(contains("wf_notification_preference"), any(Object[].class));
        verifyNoInteractions(identities, tasks, runtime, history);
    }

    private void policy(String event)
    {
        jdbc.update("insert into wf_notification_policy values (1,?,'DEFAULT',null,null,'ENABLED','TASK_RECIPIENT','INBOX',null,'{{processName}}','{{taskName}}',3)", event);
    }
}
