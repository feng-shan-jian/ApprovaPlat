package com.ruoyi.flowable.service.notification;

import com.ruoyi.common.testsupport.WorkflowMysqlTargetGuard;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.RETURNS_SELF;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.common.exception.ServiceException;

/** 在已初始化的隔离 MySQL 库验证来源键、唯一性及通知事务；全部测试写入均回滚。 */
@EnabledIfEnvironmentVariable(named = "WORKFLOW_MYSQL_TEST_URL", matches = ".+")
class WorkflowNotificationSourceIntegrationTest
{
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private WorkflowNotificationWriter writer;
    private String recipient;
    private final String prefix = "reuse-" + UUID.randomUUID();

    @BeforeEach
    void setUp()
    {
        String url = System.getenv("WORKFLOW_MYSQL_TEST_URL");
        WorkflowMysqlTargetGuard.verify(url);
        var dataSource = new DriverManagerDataSource(url,
                System.getenv("WORKFLOW_MYSQL_TEST_USERNAME"),
                System.getenv("WORKFLOW_MYSQL_TEST_PASSWORD"));
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        writer = new WorkflowNotificationWriter(jdbc, mock(WorkflowNotificationOutboxService.class));
        recipient = jdbc.queryForObject("select cast(min(user_id) as char) from sys_user "
                + "where status='0' and del_flag='0'", String.class);
        assertThat(recipient).isNotBlank();
    }

    @Test
    void requiredNotificationKeepsAsciiBoundariesAndIdempotencyInsideTransaction()
    {
        transaction.executeWithoutResult(status -> {
            status.setRollbackOnly();
            String source = prefix + "!~";
            Long first = writer.writeRequiredInbox(required(" \t" + source + "\r\n"));
            assertThat(first).isNotNull();
            assertThat(writer.writeRequiredInbox(required(source))).isEqualTo(first);
            assertThat(jdbc.queryForObject("select source_id from wf_notification_inbox where notification_id=?",
                    String.class, first)).isEqualTo(source);
            assertThat(writer.writeRequiredInbox(required(prefix + "x".repeat(191 - prefix.length())))).isNotNull();
            for (String invalid : List.of(" ", "x".repeat(192), "a b", "a\u007fb", "a\u0080b", "a\nb"))
            {
                assertThatThrownBy(() -> writer.writeRequiredInbox(required(invalid)))
                        .isInstanceOf(ServiceException.class).hasMessage("通知来源标识不合法");
            }
        });
        assertThat(countRows()).isZero();
    }

    @Test
    void manualUrgeReplayAndLaterFailureLeaveNoPartialNotification()
    {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            var notification = new NotificationPlan.Notification("APPROVAL", prefix,
                    "MANUAL_URGE", recipient, "reuse", prefix, null, null, recipient,
                    "催办", "测试事务", null, "/workflow/work/detail", Set.of("INBOX"), 3);
            var plan = new NotificationPlan(List.of(notification));
            assertThat(writer.write(plan).channelRecordCount()).isOne();
            assertThat(writer.write(plan).channelRecordCount()).isZero();
            assertThat(countRows()).isOne();
            writer.writeRequiredInbox(required("invalid source"));
        })).isInstanceOf(ServiceException.class).hasMessage("通知来源标识不合法");
        assertThat(countRows()).isZero();
    }

    @Test
    void withdrawalCancellationRunsBeforeCommitAndRollsBackWithTheBusinessAction()
    {
        var outbox = new WorkflowNotificationOutboxService(jdbc,
                new com.ruoyi.flowable.config.WorkflowNotificationProperties(),
                mock(com.ruoyi.flowable.runtime.WorkflowNotificationMetrics.class),
                mock(com.ruoyi.flowable.identity.WorkflowIdentityResolver.class));
        var actualWriter = new WorkflowNotificationWriter(jdbc, outbox);
        var taskService = mock(org.flowable.engine.TaskService.class);
        var query = mock(org.flowable.task.api.TaskQuery.class, RETURNS_SELF);
        var task = mock(org.flowable.task.api.Task.class);
        when(taskService.createTaskQuery()).thenReturn(query);
        when(query.singleResult()).thenReturn(task);
        when(task.getProcessInstanceId()).thenReturn(prefix);
        var service = new WorkflowNotificationService(jdbc, null, null, taskService, null, null,
                new WorkflowNotificationPlanner(jdbc), actualWriter, outbox, null);
        var notification = new NotificationPlan.Notification("APPROVAL", prefix,
                "MANUAL_URGE", recipient, "reuse", prefix, prefix, null, recipient,
                "催办", "beforeCommit 合同", null, "/workflow/work/detail", Set.of("EMAIL"), 3);
        try
        {
            transaction.executeWithoutResult(status -> actualWriter.write(new NotificationPlan(List.of(notification))));
            assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                service.onTasksWithdrawn(prefix, List.of(prefix));
                assertThat(outboxStatus()).isEqualTo("PENDING");
                throw new ServiceException("业务失败", 409);
            })).isInstanceOf(ServiceException.class);
            assertThat(outboxStatus()).isEqualTo("PENDING");
            transaction.executeWithoutResult(status -> {
                service.onTasksWithdrawn(prefix, List.of(prefix));
                assertThat(outboxStatus()).isEqualTo("PENDING");
            });
            assertThat(outboxStatus()).isEqualTo("CANCELLED");
        }
        finally
        {
            jdbc.update("delete from wf_notification_outbox where process_instance_id=? and source_id=?", prefix, prefix);
        }
        assertThat(jdbc.queryForObject("select count(*) from wf_notification_outbox where process_instance_id=?",
                Integer.class, prefix)).isZero();
    }

    private String outboxStatus()
    {
        return jdbc.queryForObject("select status from wf_notification_outbox where process_instance_id=? and source_id=?",
                String.class, prefix, prefix);
    }

    private WorkflowInboxNotification required(String source)
    {
        return new WorkflowInboxNotification("BPMN_EVENT", source, "SIGNAL_RECEIVED",
                recipient, prefix, null, "事件", "测试事务", "/workflow/work/detail");
    }

    private int countRows()
    {
        return jdbc.queryForObject("select count(*) from wf_notification_inbox where process_instance_id=?",
                Integer.class, prefix);
    }
}
