package com.ruoyi.flowable.service.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.crypto.spec.SecretKeySpec;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.dto.WorkflowMailConfigRequest;
import com.ruoyi.flowable.domain.vo.WorkflowMailConfigView;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.testsupport.WorkflowMySqlITSupport;

/**
 * 在显式指定的 approvaplat_it MySQL 库验证 SMTP 单例配置的真实并发合同。
 *
 * 测试只通过生产 WorkflowMailConfigService 写入 config_id=1，并在每个用例前后仅删除该行；
 * 未配置专用 MySQL 环境变量时不会尝试连接数据库。
 */
class WorkflowMailConfigMySqlIT
{
    /** 本集成测试唯一允许连接并清理的数据库名。 */
    private static final String REQUIRED_DATABASE = "approvaplat_it";
    /** 所有清理都必须带固定单例主键条件，禁止扩大删除范围。 */
    private static final String CLEANUP_SQL =
            "delete from sys_mail_config where config_id=1";

    private static MysqlDataSource dataSource;

    private JdbcTemplate jdbcTemplate;
    private WorkflowMailConfigService mailConfigService;
    /** 每个用例独立生成的 SMTP 授权码，永不写入日志或断言消息。 */
    private String credential;

    /**
     * 从显式环境变量建立真实 MySQL 数据源，并在任何清理前确认目标是隔离验收库。
     *
     * @return void，验证成功后保存共享 MysqlDataSource
     * @throws SQLException 连接或数据库身份核验失败时向 JUnit 报告
     */
    @BeforeAll
    static void setUpDataSource() throws SQLException
    {
        dataSource = WorkflowMySqlITSupport.createDataSource();
        WorkflowMySqlITSupport.verifyIsolatedBaseline(dataSource,
                "SMTP 配置 MySQL IT", REQUIRED_DATABASE,
                List.of("sys_mail_config"));
    }

    /**
     * 清理 config_id=1，生成当前用例专用 AES-256 子密钥，并创建带真实事务代理的生产服务。
     *
     * @return void，每个用例获得空单例表、独立密钥和固定 mock 操作者身份
     */
    @BeforeEach
    void setUpService()
    {
        jdbcTemplate = new JdbcTemplate(dataSource);
        cleanupSingleton();
        assertEquals(0, singletonRowCount(), "用例开始前 SMTP 单例行必须为空");

        byte[] keyBytes = new byte[32];
        new SecureRandom().nextBytes(keyBytes);
        WorkflowMailCredentialCipher credentialCipher = new WorkflowMailCredentialCipher(
                new SecretKeySpec(keyBytes, "AES"), new SecureRandom());
        Arrays.fill(keyBytes, (byte) 0);

        WorkflowIdentityResolver identityResolver = mock(WorkflowIdentityResolver.class);
        when(identityResolver.resolveCurrentIdentity())
                .thenReturn(new WorkflowCurrentIdentity("1", Set.of()));
        WorkflowMailConfigService target = new WorkflowMailConfigService(jdbcTemplate,
                credentialCipher, new WorkflowMailFailureClassifier(), identityResolver);
        mailConfigService = WorkflowMySqlITSupport.transactionalProxy(target,
                new DataSourceTransactionManager(dataSource));
        credential = "mysql-it-" + UUID.randomUUID();
    }

    /**
     * 无论用例成功或失败都只删除 config_id=1，并回读确认没有遗留正式配置。
     *
     * @return void，清理完成后 sys_mail_config 中不存在单例测试行
     */
    @AfterEach
    void tearDownSingleton()
    {
        cleanupSingleton();
        assertEquals(0, singletonRowCount(), "SMTP MySQL IT 不得遗留 config_id=1");
        credential = null;
    }

    /**
     * 让两个线程携带相同 revision 同时调用生产服务，验证 MySQL CAS 只允许一个更新成功。
     *
     * @return void，成功数、冲突数或最终数据库 revision 不符合单写者语义时断言失败
     * @throws Exception 并发任务未及时完成或返回非预期异常时向 JUnit 报告
     */
    @Test
    void permitsOnlyOneConcurrentUpdateForSameRevision() throws Exception
    {
        mailConfigService.save(request(
                "smtp.mysql-it.invalid", "并发基线", credential, 0L));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<UpdateOutcome>> futures;
        try
        {
            futures = List.of(
                    executor.submit(() -> concurrentSave(
                            "smtp-writer-a.mysql-it.invalid", ready, start)),
                    executor.submit(() -> concurrentSave(
                            "smtp-writer-b.mysql-it.invalid", ready, start)));
            assertTrue(ready.await(5, TimeUnit.SECONDS), "两个并发写者未进入起跑门");
            start.countDown();

            UpdateOutcome first = futures.get(0).get(15, TimeUnit.SECONDS);
            UpdateOutcome second = futures.get(1).get(15, TimeUnit.SECONDS);
            assertEquals(1L, List.of(first, second).stream()
                    .filter(UpdateOutcome.SUCCESS::equals).count());
            assertEquals(1L, List.of(first, second).stream()
                    .filter(UpdateOutcome.CONFLICT::equals).count());
        }
        finally
        {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS),
                    "并发 SMTP 配置测试线程未能及时退出");
        }

        WorkflowMailConfigView current = mailConfigService.configuration();
        assertEquals(2L, current.revision());
        assertTrue(Set.of("smtp-writer-a.mysql-it.invalid",
                "smtp-writer-b.mysql-it.invalid").contains(current.smtpHost()));
    }

    /**
     * 执行一个已经在起跑门同步的 revision 1 更新，并只把预期 409 转换为可计数结果。
     *
     * @param smtpHost String，当前并发写者的唯一 SMTP 主机字段
     * @param ready CountDownLatch，通知主线程当前写者已经就绪
     * @param start CountDownLatch，保证两个写者使用同一数据库基线开始竞争
     * @return UpdateOutcome，成功或稳定乐观锁冲突
     * @throws Exception 起跑等待超时或出现非预期领域异常时向 JUnit 报告
     */
    private UpdateOutcome concurrentSave(String smtpHost, CountDownLatch ready,
            CountDownLatch start) throws Exception
    {
        ready.countDown();
        assertTrue(start.await(5, TimeUnit.SECONDS), "并发写者等待起跑信号超时");
        try
        {
            // 两个写者都改变认证主机，因此必须显式提交新授权码后再进入 revision CAS。
            mailConfigService.save(request(smtpHost, "并发更新", credential, 1L));
            return UpdateOutcome.SUCCESS;
        }
        catch (ServiceException exception)
        {
            if (Integer.valueOf(HttpStatus.CONFLICT).equals(exception.getCode())
                    && "MAIL_CONFIG_REVISION_CONFLICT".equals(exception.getSubCode()))
            {
                return UpdateOutcome.CONFLICT;
            }
            throw exception;
        }
    }

    /**
     * 构造字段完整且不触发网络连接的生产 SMTP 保存请求。
     *
     * @param smtpHost String，用于区分当前写入者的合法 SMTP 主机
     * @param senderName String，本次保存的可见发件人名称
     * @param suppliedCredential String，首次保存或认证身份变化时提交的授权码；仅同身份更新可为空
     * @param expectedRevision long，客户端期望的数据库 revision
     * @return WorkflowMailConfigRequest，可交由生产服务校验并持久化的请求
     */
    private WorkflowMailConfigRequest request(String smtpHost, String senderName,
            String suppliedCredential, long expectedRevision)
    {
        return new WorkflowMailConfigRequest(smtpHost, 587, "STARTTLS",
                "notify@example.com", suppliedCredential, "notify@example.com",
                senderName, expectedRevision);
    }

    /**
     * 删除当前测试唯一有权管理的 config_id=1 行，不允许使用无条件 DELETE 或 TRUNCATE。
     *
     * @return void，删除零行或一行均视为幂等清理成功
     */
    private void cleanupSingleton()
    {
        int deleted = jdbcTemplate.update(CLEANUP_SQL);
        assertTrue(deleted == 0 || deleted == 1, "SMTP 单例清理命中行数异常");
    }

    /**
     * 查询正式单例主键当前行数，供前后清理门禁复核。
     *
     * @return int，config_id=1 的真实数据库行数
     */
    private int singletonRowCount()
    {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from sys_mail_config where config_id=1", Integer.class);
        assertNotNull(count);
        return count;
    }

    /** 两个并发写者允许出现的完整结果集合。 */
    private enum UpdateOutcome
    {
        SUCCESS,
        CONFLICT
    }
}
