package com.ruoyi.flowable.service.notification;

import com.ruoyi.common.testsupport.WorkflowMysqlTargetGuard;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.config.WorkflowNotificationProperties;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.runtime.WorkflowNotificationMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** 真实短事务与 MySQL 租约；只有 EMAIL/SMS 网络端口受控，不启动流程引擎。 */
@EnabledIfEnvironmentVariable(named = "WORKFLOW_MYSQL_TEST_URL", matches = ".+")
class WorkflowNotificationDeliveryMysqlTest
{
    private final long userId = 800_000_000L + Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 100_000_000L);
    private final List<Long> created = new ArrayList<>();
    private final AtomicInteger sends = new AtomicInteger();
    private final AtomicReference<java.util.function.Function<WorkflowNotificationOutboxRecord, WorkflowNotificationDeliveryResult>> delivery =
            new AtomicReference<>(row -> WorkflowNotificationDeliveryResult.delivered());
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private WorkflowNotificationOutboxService outbox;
    private WorkflowNotificationDeliveryCoordinator coordinator;
    private SimpleMeterRegistry metrics;

    @BeforeEach
    void setUp()
    {
        String url = System.getenv("WORKFLOW_MYSQL_TEST_URL");
        WorkflowMysqlTargetGuard.verify(url);
        var dataSource = new DriverManagerDataSource(url, System.getenv("WORKFLOW_MYSQL_TEST_USERNAME"), System.getenv("WORKFLOW_MYSQL_TEST_PASSWORD"));
        jdbc = new JdbcTemplate(dataSource);
        // The production claimant intentionally scans the whole queue: require a quiet isolated database.
        assertThat(jdbc.queryForObject("select count(*) from wf_notification_outbox where status in ('PENDING','RETRYING','DELIVERING')", Long.class)).isZero();
        jdbc.update("insert into sys_user(user_id,user_name,nick_name,email,status,del_flag) values(?,?,'delivery contract','test@example.invalid','0','0')",
                userId, "delivery" + userId);
        var manager = new DataSourceTransactionManager(dataSource);
        transaction = new TransactionTemplate(manager);
        var identity = mock(WorkflowIdentityResolver.class);
        when(identity.resolveCurrentIdentity()).thenReturn(new WorkflowCurrentIdentity(Long.toString(userId), Set.of()));
        var properties = new WorkflowNotificationProperties();
        properties.setBatchSize(1);
        properties.setMaxRetryDelay(Duration.ofSeconds(1));
        metrics = new SimpleMeterRegistry();
        var proxy = new ProxyFactory(new WorkflowNotificationOutboxService(jdbc, properties, new WorkflowNotificationMetrics(metrics), identity));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        outbox = (WorkflowNotificationOutboxService) proxy.getProxy();
        coordinator = new WorkflowNotificationDeliveryCoordinator(outbox, List.of(channel("EMAIL"), channel("SMS")));
    }

    @AfterEach
    void clean()
    {
        if (jdbc != null)
        {
            for (long id : created) jdbc.update("delete from wf_notification_outbox where outbox_id=?", id);
            jdbc.update("delete from sys_user where user_id=?", userId);
            for (long id : created)
                assertThat(jdbc.queryForObject("select count(*) from wf_notification_outbox where outbox_id=?", Long.class, id)).isZero();
        }
        if (metrics != null) metrics.close();
    }

    @Test
    void twoWorkersDeliverOneRowOnlyOnceOutsideAnyTransaction() throws Exception
    {
        long id = enqueue(3, "TASK_ARRIVED");
        var first = new WorkflowNotificationWorker(coordinator);
        var second = new WorkflowNotificationWorker(coordinator);
        var sending = new CountDownLatch(1);
        var secondPolled = new CountDownLatch(1);
        delivery.set(row -> {
            sending.countDown();
            await(secondPolled);
            return WorkflowNotificationDeliveryResult.delivered();
        });
        // Hold the first external send open so the second worker must observe
        // its committed lease, instead of racing two SKIP LOCKED eligibility scans.
        concurrently(first::deliverBatch, () -> {
            await(sending);
            try { second.deliverBatch(); }
            finally { secondPolled.countDown(); }
        });
        assertThat(sends).hasValue(1);
        assertState(id, "PROCESSED", 1, 1, 1);
    }

    @Test
    void expiredLeaseTakeoverRejectsBothOldSuccessAndOldFailure()
    {
        long id = enqueue(4, "TASK_ARRIVED");
        var old = outbox.claimNext("old");
        expire(id);
        var current = outbox.claimNext("new");
        conflict(() -> outbox.completeDelivery(old, "old", WorkflowNotificationDeliveryResult.delivered()));
        conflict(() -> outbox.completeDelivery(old, "old", WorkflowNotificationDeliveryResult.failure("IO", "retry", false)));
        assertState(id, "DELIVERING", 2, 2, 1);
        coordinator.deliverClaimed(current, "new");
        assertState(id, "PROCESSED", 2, 2, 1);
    }

    @Test
    void lastAttemptCrashBecomesDeadLetterAndCompensationRetainsTotalCount()
    {
        long id = enqueue(1, "TASK_ARRIVED");
        delivery.set(row -> { throw new IllegalStateException("controlled channel crash"); });
        new WorkflowNotificationWorker(coordinator).deliverBatch();
        assertState(id, "DELIVERING", 1, 1, 1);
        expire(id);
        assertThat(outbox.claimNext("recovery")).isNull();
        assertState(id, "DEAD_LETTER", 1, 1, 1);
        assertThat(jdbc.queryForObject("select last_error_code from wf_notification_outbox where outbox_id=?", String.class, id))
                .isEqualTo("LEASE_EXPIRED_AFTER_FINAL_ATTEMPT");
        outbox.compensate(id);
        assertState(id, "RETRYING", 0, 1, 2);
        delivery.set(row -> WorkflowNotificationDeliveryResult.delivered());
        new WorkflowNotificationWorker(coordinator).deliverBatch();
        assertState(id, "PROCESSED", 1, 2, 2);
        conflict(() -> outbox.compensate(id));
    }

    @Test
    void retryableAndPermanentFailuresFollowBoundedStateTransitions()
    {
        long id = enqueue(3, "TASK_ARRIVED");
        delivery.set(row -> WorkflowNotificationDeliveryResult.failure("TRANSIENT", "retry", false));
        new WorkflowNotificationWorker(coordinator).deliverBatch();
        assertState(id, "RETRYING", 1, 1, 1);
        jdbc.update("update wf_notification_outbox set next_attempt_at=date_sub(current_timestamp(3),interval 1 minute) where outbox_id=?", id);
        delivery.set(row -> WorkflowNotificationDeliveryResult.failure("PERMANENT", "stop", true));
        new WorkflowNotificationWorker(coordinator).deliverBatch();
        assertState(id, "DEAD_LETTER", 2, 2, 1);
        assertThat(outbox.claimNext("next")).isNull();
    }

    @Test
    void cancellationDuringExternalSendWinsOverStaleCompletion() throws Exception
    {
        long id = enqueue(3, "MANUAL_URGE");
        var entered = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        delivery.set(row -> {
            entered.countDown();
            await(cancelled);
            return WorkflowNotificationDeliveryResult.delivered();
        });
        concurrently(() -> new WorkflowNotificationWorker(coordinator).deliverBatch(), () -> {
            await(entered);
            try
            {
                transaction.executeWithoutResult(status -> assertThat(outbox.cancelPendingUrges("delivery-instance", "delivery-task", "completed")).isOne());
            }
            finally { cancelled.countDown(); }
        });
        assertState(id, "CANCELLED", 1, 1, 1);
        assertThat(sends).hasValue(1);
    }

    @Test
    void acceptedSendWithCompletionRollbackIsDeliveredAgainAfterLeaseExpiry()
    {
        long id = enqueue(3, "TASK_ARRIVED");
        String constraint = "it_delivery_" + userId;
        jdbc.execute("alter table wf_notification_outbox add constraint " + constraint + " check (recipient_user_id <> " + userId + " or status <> 'PROCESSED')");
        try
        {
            new WorkflowNotificationWorker(coordinator).deliverBatch();
            assertState(id, "DELIVERING", 1, 1, 1);
            assertThat(sends).hasValue(1);
        }
        finally { jdbc.execute("alter table wf_notification_outbox drop check " + constraint); }
        delivery.set(row -> WorkflowNotificationDeliveryResult.delivered());
        expire(id);
        new WorkflowNotificationWorker(coordinator).deliverBatch();
        assertState(id, "PROCESSED", 2, 2, 1);
        assertThat(sends).hasValue(2);
    }

    @Test
    void coordinatorRejectsSendingInsideCallerTransaction()
    {
        long id = enqueue(3, "TASK_ARRIVED");
        var row = outbox.claimNext("worker");
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> coordinator.deliverClaimed(row, "worker")))
                .isInstanceOfSatisfying(ServiceException.class, failure -> assertThat(failure.getCode()).isEqualTo(500));
        assertThat(sends).hasValue(0);
        assertState(id, "DELIVERING", 1, 1, 1);
    }

    private WorkflowNotificationChannel channel(String name)
    {
        return new WorkflowNotificationChannel()
        {
            public String channel() { return name; }
            public WorkflowNotificationDeliveryResult deliver(WorkflowNotificationOutboxRecord row)
            {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                assertThat(row.deliveryTarget()).isEqualTo("test@example.invalid");
                sends.incrementAndGet();
                return delivery.get().apply(row);
            }
        };
    }

    private long enqueue(int max, String event)
    {
        String hash = UUID.randomUUID().toString().replace("-", "").repeat(2);
        jdbc.update("insert into wf_notification_outbox(idempotency_key,source_type,source_id,event_type,channel,recipient_user_id,process_definition_key,process_instance_id,task_id,title,content,route_path,max_attempts,next_attempt_at) " +
                "values(?,'APPROVAL',?,?,'EMAIL',?,'delivery','delivery-instance','delivery-task','contract','body','/workflow',?,date_sub(current_timestamp(3),interval 1 minute))", hash, hash, event, userId, max);
        long id = jdbc.queryForObject("select outbox_id from wf_notification_outbox where idempotency_key=?", Long.class, hash);
        created.add(id);
        return id;
    }

    private void expire(long id)
    {
        jdbc.update("update wf_notification_outbox set lease_expires_at=date_sub(current_timestamp(3),interval 1 minute) where outbox_id=?", id);
    }

    private void assertState(long id, String state, int attempts, int total, int cycle)
    {
        var row = jdbc.queryForMap("select status,attempt_count,total_attempt_count,delivery_cycle from wf_notification_outbox where outbox_id=?", id);
        assertThat(row.get("status")).isEqualTo(state);
        assertThat(((Number) row.get("attempt_count")).intValue()).isEqualTo(attempts);
        assertThat(((Number) row.get("total_attempt_count")).intValue()).isEqualTo(total);
        assertThat(((Number) row.get("delivery_cycle")).intValue()).isEqualTo(cycle);
    }

    private void conflict(Runnable operation)
    {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(ServiceException.class, failure -> assertThat(failure.getCode()).isEqualTo(409));
    }

    private void concurrently(Runnable first, Runnable second) throws Exception
    {
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try
        {
            var a = executor.submit(() -> { await(start); first.run(); });
            var b = executor.submit(() -> { await(start); second.run(); });
            start.countDown();
            a.get(15, TimeUnit.SECONDS);
            b.get(15, TimeUnit.SECONDS);
        }
        finally { executor.shutdownNow(); assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }

    private void await(CountDownLatch latch)
    {
        try { assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
}
