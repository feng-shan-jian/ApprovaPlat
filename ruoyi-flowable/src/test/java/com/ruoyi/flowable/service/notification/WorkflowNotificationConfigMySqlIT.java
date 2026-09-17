package com.ruoyi.flowable.service.notification;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.security.SecureRandom;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.spec.SecretKeySpec;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.dto.*;
import com.ruoyi.flowable.identity.*;
import com.ruoyi.flowable.mapper.WorkflowIdentityMapper;
import com.ruoyi.flowable.testsupport.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 真实 SQL、身份 Mapper 与 Spring 事务证明配置竞争和系统故障的不同语义。 */
class WorkflowNotificationConfigMySqlIT
{
    private JdbcTemplate jdbc;
    private JdbcTemplate serviceJdbc;
    private WorkflowIdentityMapper mapper;
    private WorkflowNotificationPolicyService policies;
    private WorkflowMailConfigService mail;
    private TransactionTemplate transaction;
    private long userId;
    private Connection lock;

    @BeforeEach
    void setUp() throws Exception
    {
        var source = WorkflowMySqlITSupport.createDataSource();
        WorkflowMySqlITSupport.verifyIsolatedBaseline(source, "通知配置 IT", "approvaplat_it",
                List.of("sys_user", "sys_mail_config", "wf_notification_preference", "wf_notification_policy"));
        jdbc = new JdbcTemplate(source); serviceJdbc = spy(new JdbcTemplate(source));
        lock = source.getConnection();
        try (var statement = lock.createStatement(); var result = statement.executeQuery("select get_lock('workflow_notification_config_it',30)"))
        { assertThat(result.next()).isTrue(); assertThat(result.getInt(1)).isOne(); }
        assertThat(jdbc.queryForObject("select count(*) from sys_mail_config", Long.class)).as("专用 IT 库必须没有正式 SMTP 配置").isZero();
        userId = 50_000_000_000L + Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 1_000_000_000L);
        jdbc.update("insert into sys_user(user_id,user_name,nick_name,status,del_flag) values(?,?,?,'0','0')",
                userId, "c09config_" + userId, "Config test");
        mapper = spy(WorkflowH2SchemaMapperSupport.createSpringMapper(source, "notification-config",
                WorkflowIdentityMapper.class, "mapper/flowable/WorkflowIdentityMapper.xml"));
        var identity = new WorkflowIdentityResolver(mapper, new WorkflowIdentityCodec());
        var manager = new DataSourceTransactionManager(source);
        transaction = new TransactionTemplate(manager);
        // 无行 UPDATE 在 RR 的间隙锁竞争可以产生死锁；此例使用 RC 精确重现 INSERT 唯一键竞争。
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        mail = WorkflowMySqlITSupport.transactionalProxy(new WorkflowMailConfigService(serviceJdbc,
                new WorkflowMailCredentialCipher(new SecretKeySpec(new byte[32], "AES"), new SecureRandom()),
                new WorkflowMailFailureClassifier(), identity), manager);
        policies = WorkflowMySqlITSupport.transactionalProxy(new WorkflowNotificationPolicyService(serviceJdbc, identity,
                mock(WorkflowNotificationCatalogService.class), mail), manager);
        login();
    }

    @AfterEach
    void tearDown() throws Exception
    {
        SecurityContextHolder.clearContext();
        try
        {
            if (jdbc != null && userId != 0)
            {
                jdbc.update("delete from sys_mail_config where config_id=1 and create_by=?", Long.toString(userId));
                jdbc.update("delete from wf_notification_policy where create_by=?", Long.toString(userId));
                jdbc.update("delete from wf_notification_preference where user_id=?", userId);
                jdbc.update("delete from sys_user where user_id=?", userId);
                assertThat(jdbc.queryForObject("select count(*) from sys_mail_config", Long.class)).isZero();
                assertThat(jdbc.queryForObject("select count(*) from wf_notification_preference where user_id=?", Long.class, userId)).isZero();
            }
        }
        finally
        {
            if (lock != null) { try (var statement = lock.createStatement()) { statement.execute("select release_lock('workflow_notification_config_it')"); } lock.close(); }
        }
    }

    @Test
    void firstPreferenceWriteAndExpiredCasHaveDistinctOutcomes()
    {
        assertThat(policies.savePreference(preference(0))).containsEntry("revision", 1L);
        assertThat(policies.savePreference(preference(1))).containsEntry("revision", 2L);
        assertConflict(() -> policies.savePreference(preference(1)), false);
        assertConflict(() -> policies.savePreference(preference(0)), true);
        assertThat(jdbc.queryForObject("select revision from wf_notification_preference where user_id=?", Long.class, userId)).isEqualTo(2);
    }

    @Test
    void concurrentFirstPreferenceInsertProducesOneUniqueKeyConflict() throws Exception
    {
        CountDownLatch inserts = new CountDownLatch(2);
        doAnswer(i ->
        {
            inserts.countDown(); assertThat(inserts.await(10, TimeUnit.SECONDS)).isTrue();
            return i.callRealMethod();
        }).when(serviceJdbc).update(startsWith("insert into wf_notification_preference"), any(Object[].class));
        var executor = Executors.newFixedThreadPool(2);
        try
        {
            List<Future<Throwable>> futures = new ArrayList<>();
            for (int index = 0; index < 2; index++) futures.add(executor.submit(() ->
            {
                login();
                try { transaction.execute(status -> policies.savePreference(preference(0))); return null; }
                catch (Throwable exception) { return exception; }
                finally { SecurityContextHolder.clearContext(); }
            }));
            List<Throwable> failures = new ArrayList<>();
            for (var future : futures) { Throwable error = future.get(20, TimeUnit.SECONDS); if (error != null) failures.add(error); }
            assertThat(failures).singleElement().isInstanceOfSatisfying(ServiceException.class, error ->
            { assertThat(error.getCode()).isEqualTo(409); assertThat(error.getCause()).isInstanceOf(DuplicateKeyException.class); });
            assertThat(jdbc.queryForObject("select revision from wf_notification_preference where user_id=?", Long.class, userId)).isEqualTo(1);
        }
        finally { executor.shutdownNow(); assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test
    void preferenceInsertDatabaseFailureIsNotAConflictAndRollsBack()
    {
        doAnswer(i -> { i.callRealMethod(); return sqlFailure(); }).when(serviceJdbc)
                .update(startsWith("insert into wf_notification_preference"), any(Object[].class));
        assertThatThrownBy(() -> policies.savePreference(preference(0)))
                .isInstanceOf(DataAccessException.class).hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThat(jdbc.queryForObject("select count(*) from wf_notification_preference where user_id=?", Long.class, userId)).isZero();
    }

    @Test
    void identityDirectoryFailureDoesNotBecomeLoginExpiry()
    {
        doAnswer(i -> sqlFailure()).when(mapper).selectActiveUserIdsByUserIds(anyList());
        assertThatThrownBy(policies::preference).isInstanceOf(DataAccessException.class).hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThatThrownBy(() -> mail.save(mailRequest(0))).isInstanceOf(DataAccessException.class).hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThat(jdbc.queryForObject("select count(*) from sys_mail_config", Long.class)).isZero();
    }

    @Test
    void missingLoginAndInvalidArgumentsStillRemainBusinessFailures()
    {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(policies::preference).isInstanceOfSatisfying(ServiceException.class,
                error -> assertThat(error.getCode()).isEqualTo(401));
        assertThatThrownBy(() -> policies.savePreference(preference(-1))).isInstanceOfSatisfying(ServiceException.class,
                error -> assertThat(error.getCode()).isEqualTo(400));
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void policySqlFailuresKeepOriginalCauseAndRollback(boolean update)
    {
        Long id = update ? ((Number) policies.savePolicy(policyRequest(null, null)).get("policyId")).longValue() : null;
        if (update) doAnswer(i -> { i.callRealMethod(); return sqlFailure(); }).when(serviceJdbc)
                .update(startsWith("update wf_notification_policy"), any(Object[].class));
        else doAnswer(i -> { i.callRealMethod(); return sqlFailure(); }).when(serviceJdbc)
                .update(any(PreparedStatementCreator.class), any(KeyHolder.class));
        assertThatThrownBy(() -> policies.savePolicy(policyRequest(id, update ? 0 : null)))
                .isInstanceOf(DataAccessException.class).hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThat(jdbc.queryForObject("select count(*) from wf_notification_policy where create_by=?", Long.class, Long.toString(userId))).isEqualTo(update ? 1 : 0);
        if (update) assertThat(jdbc.queryForObject("select revision from wf_notification_policy where policy_id=?", Long.class, id)).isZero();
    }

    @Test
    void policyUniqueKeyAndRevisionConflictsKeepBusinessCodes()
    {
        long id = ((Number) policies.savePolicy(policyRequest(null, null)).get("policyId")).longValue();
        assertConflict(() -> policies.savePolicy(policyRequest(null, null)), true);
        assertConflict(() -> policies.savePolicy(policyRequest(id, 12)), false);
    }

    @ParameterizedTest
    @ValueSource(strings = { "read", "insert", "update" })
    void mailSqlFailuresPreserveSafeSubCodeCauseAndRollback(String operation)
    {
        if ("update".equals(operation)) mail.save(mailRequest(0));
        if ("read".equals(operation)) doAnswer(i -> sqlFailure()).when(serviceJdbc)
                .query(contains("from sys_mail_config"), any(RowMapper.class));
        else doAnswer(i -> { i.callRealMethod(); return sqlFailure(); }).when(serviceJdbc)
                .update(startsWith(operation + " " + ("insert".equals(operation) ? "into " : "") + "sys_mail_config"), any(Object[].class));
        assertThatThrownBy(() -> mail.save(mailRequest("update".equals(operation) ? 1 : 0)))
                .isInstanceOfSatisfying(ServiceException.class, error ->
                {
                    assertThat(error.getCode()).isEqualTo(500);
                    assertThat(error.getSubCode()).isEqualTo("MAIL_CONFIG_PERSISTENCE_FAILED");
                    assertThat(error.getCause()).isInstanceOf(DataAccessException.class);
                    assertThat(error).hasRootCauseInstanceOf(java.sql.SQLException.class);
                    assertThat(error.getMessage()).doesNotContain("c09_missing", "select", "password");
                });
        if ("update".equals(operation)) assertThat(jdbc.queryForObject("select revision from sys_mail_config where config_id=1", Long.class)).isEqualTo(1);
        else assertThat(jdbc.queryForObject("select count(*) from sys_mail_config", Long.class)).isZero();
    }

    private Object sqlFailure() { return jdbc.queryForList("select password from c09_missing_configuration_table"); }
    private void login()
    {
        var user = new LoginUser(); user.setUserId(userId);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
    private WorkflowNotificationPreferenceRequest preference(int revision) { return new WorkflowNotificationPreferenceRequest(true, true, false, revision); }
    private WorkflowNotificationPolicyRequest policyRequest(Long id, Integer revision)
    {
        return new WorkflowNotificationPolicyRequest(id, "PROCESS", "c09_config_" + userId, null,
                "COPY_CREATED", "TASK_RECIPIENT", "INBOX", null, "Title", "Body", 3, "ENABLED", revision);
    }
    private WorkflowMailConfigRequest mailRequest(long revision)
    {
        return new WorkflowMailConfigRequest("smtp.c09.invalid", 587, "STARTTLS", "c09@example.invalid", "local-test-only",
                "c09@example.invalid", "Test", revision);
    }
    private void assertConflict(Runnable action, boolean duplicate)
    {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ServiceException.class, error ->
        {
            assertThat(error.getCode()).isEqualTo(409);
            if (duplicate) assertThat(error.getCause()).isInstanceOf(DuplicateKeyException.class);
            else assertThat(error.getCause()).isNull();
        });
    }
}
