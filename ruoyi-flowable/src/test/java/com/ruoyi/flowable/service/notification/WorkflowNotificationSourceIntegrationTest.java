package com.ruoyi.flowable.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

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
        assertThat(url).matches("jdbc:mysql://[^/]+/[A-Za-z0-9_]*_it(?:\\?.*)?");
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
