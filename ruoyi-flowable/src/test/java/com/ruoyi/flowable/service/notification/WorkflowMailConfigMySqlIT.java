package com.ruoyi.flowable.service.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.dto.WorkflowMailConfigRequest;
import com.ruoyi.flowable.domain.vo.WorkflowMailConfigView;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;

/**
 * 在显式指定的 approvaplat_it MySQL 库验证 SMTP 单例配置的正式表结构、加密落库和乐观锁合同。
 *
 * 测试只通过生产 WorkflowMailConfigService 写入 config_id=1，并在每个用例前后仅删除该行；
 * 未配置专用 MySQL 环境变量时不会尝试连接数据库。
 */
@EnabledIfEnvironmentVariable(named = "WORKFLOW_MYSQL_TEST_URL",
        matches = "jdbc:mysql:.*")
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
        dataSource = new MysqlDataSource();
        dataSource.setURL(requireEnvironment("WORKFLOW_MYSQL_TEST_URL", false));
        dataSource.setUser(requireEnvironment("WORKFLOW_MYSQL_TEST_USERNAME", false));
        dataSource.setPassword(requireEnvironment("WORKFLOW_MYSQL_TEST_PASSWORD", true));
        verifyTargetDatabase();
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
        mailConfigService = transactionalProxy(target,
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
     * 从 information_schema 核验正式表、全部列、主键和六个命名 CHECK 约束。
     *
     * @return void，物理结构与 8.0.1 正式迁移不一致时断言失败
     */
    @Test
    void exposesExpectedTableColumnsAndChecks()
    {
        Map<String, Object> table = jdbcTemplate.queryForMap(
                "select engine,table_collation from information_schema.tables "
                + "where table_schema=database() and table_name='sys_mail_config'");
        assertEquals("InnoDB", table.get("engine"));
        assertEquals("utf8mb4_unicode_ci", table.get("table_collation"));

        Map<String, ColumnShape> columns = jdbcTemplate.query(
                "select column_name,data_type,column_type,is_nullable,"
                + "character_maximum_length,collation_name "
                + "from information_schema.columns where table_schema=database() "
                + "and table_name='sys_mail_config' order by ordinal_position",
                result -> {
                    Map<String, ColumnShape> shapes = new LinkedHashMap<>();
                    while (result.next())
                    {
                        shapes.put(result.getString("column_name"), new ColumnShape(
                                result.getString("data_type"),
                                result.getString("column_type"),
                                result.getString("is_nullable"),
                                result.getObject("character_maximum_length", Long.class),
                                result.getString("collation_name")));
                    }
                    return shapes;
                });
        assertEquals(Set.of("config_id", "smtp_host", "smtp_port", "encryption_mode",
                "username", "credential_ciphertext", "credential_iv",
                "from_address", "sender_name", "revision", "create_by", "create_time",
                "update_by", "update_time"),
                columns.keySet());
        assertColumn(columns, "config_id", "bigint", "bigint", "NO", null, null);
        assertColumn(columns, "smtp_port", "int", "int", "NO", null, null);
        assertColumn(columns, "encryption_mode", "varchar", "varchar(20)", "NO", 20L,
                "ascii_bin");
        assertColumn(columns, "credential_ciphertext", "text", "text", "NO", 65535L,
                "utf8mb4_unicode_ci");
        assertColumn(columns, "credential_iv", "varbinary", "varbinary(12)", "NO", 12L,
                null);
        assertColumn(columns, "revision", "bigint", "bigint", "NO", null, null);

        String primaryKeyColumn = jdbcTemplate.queryForObject(
                "select column_name from information_schema.key_column_usage "
                + "where table_schema=database() and table_name='sys_mail_config' "
                + "and constraint_name='PRIMARY'", String.class);
        assertEquals("config_id", primaryKeyColumn);

        Map<String, String> checks = jdbcTemplate.query(
                "select tc.constraint_name,cc.check_clause "
                + "from information_schema.table_constraints tc "
                + "join information_schema.check_constraints cc "
                + "on cc.constraint_schema=tc.constraint_schema "
                + "and cc.constraint_name=tc.constraint_name "
                + "where tc.table_schema=database() and tc.table_name='sys_mail_config' "
                + "and tc.constraint_type='CHECK'",
                result -> {
                    Map<String, String> clauses = new LinkedHashMap<>();
                    while (result.next())
                    {
                        clauses.put(result.getString("constraint_name"),
                                normalizeClause(result.getString("check_clause")));
                    }
                    return clauses;
                });
        assertEquals(Set.of("chk_sys_mail_config_singleton",
                "chk_sys_mail_config_port", "chk_sys_mail_config_encryption",
                "chk_sys_mail_config_iv", "chk_sys_mail_config_ciphertext",
                "chk_sys_mail_config_revision"),
                checks.keySet());
        assertClauseContains(checks, "chk_sys_mail_config_singleton", "CONFIG_ID=1");
        assertClauseContains(checks, "chk_sys_mail_config_port",
                "SMTP_PORTBETWEEN1AND65535");
        assertClauseContains(checks, "chk_sys_mail_config_encryption",
                "ENCRYPTION_MODE", "NONE", "STARTTLS", "SSL");
        assertClauseContains(checks, "chk_sys_mail_config_iv",
                "LENGTH(CREDENTIAL_IV)=12");
        assertClauseContains(checks, "chk_sys_mail_config_ciphertext",
                "LENGTH(CREDENTIAL_CIPHERTEXT)>0");
        assertClauseContains(checks, "chk_sys_mail_config_revision", "REVISION>=1");
    }

    /**
     * 验证首次保存通过生产服务插入 revision 1，且授权码只以 GCM 密文和 12 字节 IV 落库。
     *
     * @return void，公开视图、数据库版本、操作者和加密存储任一不一致时断言失败
     */
    @Test
    void insertsRevisionOneWithEncryptedCredentialAndTwelveByteIv()
    {
        WorkflowMailConfigView empty = mailConfigService.configuration();
        assertFalse(empty.configured());
        assertEquals(0L, empty.revision());

        WorkflowMailConfigView saved = mailConfigService.save(
                request("smtp.mysql-it.invalid", "首次保存", credential, 0L));
        assertTrue(saved.configured());
        assertTrue(saved.credentialConfigured());
        assertEquals(1L, saved.revision());

        StoredCredential stored = storedCredential();
        assertNotNull(stored.ciphertext());
        assertFalse(stored.ciphertext().isBlank());
        assertNotNull(stored.iv());
        assertEquals(12, stored.iv().length);
        assertEquals(1L, stored.revision());
        assertEquals("1", stored.createBy());
        assertFalse(stored.ciphertext().contains(credential),
                "数据库密文字段不得包含授权码明文");
        assertEquals(0, plaintextOccurrenceCount(credential),
                "sys_mail_config 的文本列不得包含授权码明文");
    }

    /**
     * 验证认证身份不变时，后续保存空 credential 保留原密文与 IV 并更新发件人名称。
     *
     * @return void，留空语义或 revision 条件更新发生漂移时断言失败
     */
    @Test
    void keepsCiphertextAndIvWhenCredentialIsEmpty()
    {
        mailConfigService.save(request(
                "smtp.mysql-it.invalid", "初始配置", credential, 0L));
        StoredCredential before = storedCredential();

        WorkflowMailConfigView updated = mailConfigService.save(request(
                "smtp.mysql-it.invalid", "留空更新", "", 1L));
        StoredCredential after = storedCredential();

        assertEquals(2L, updated.revision());
        assertEquals("smtp.mysql-it.invalid", updated.smtpHost());
        assertEquals("留空更新", updated.senderName());
        assertEquals(2L, after.revision());
        assertTrue(before.ciphertext().equals(after.ciphertext()),
                "空 credential 更新必须保持原密文");
        assertTrue(Arrays.equals(before.iv(), after.iv()),
                "空 credential 更新必须保持原 IV");
        assertEquals(0, plaintextOccurrenceCount(credential),
                "更新后数据库仍不得包含授权码明文");
    }

    /**
     * 验证真实 MySQL 中变更认证主机却留空授权码会在写库前返回稳定 HTTP 400。
     *
     * @return void，拒绝后 revision、认证主机或密文发生变化时断言失败
     */
    @Test
    void rejectsBlankCredentialForChangedAuthenticationIdentityWithoutWriting()
    {
        mailConfigService.save(request(
                "smtp.mysql-it.invalid", "初始配置", credential, 0L));
        StoredCredential before = storedCredential();

        ServiceException rejected = assertThrows(ServiceException.class,
                () -> mailConfigService.save(request(
                        "smtp-other.mysql-it.invalid", "不应保存", "", 1L)));

        assertEquals(HttpStatus.BAD_REQUEST, rejected.getCode());
        assertEquals("MAIL_CREDENTIAL_REENTRY_REQUIRED", rejected.getSubCode());
        assertEquals("SMTP 服务器、端口、加密方式或登录账号已变更，请重新填写授权码或密码",
                rejected.getMessage());
        StoredCredential after = storedCredential();
        assertEquals(1L, after.revision());
        assertEquals(before.ciphertext(), after.ciphertext());
        assertTrue(Arrays.equals(before.iv(), after.iv()));
        assertEquals("smtp.mysql-it.invalid",
                mailConfigService.configuration().smtpHost());
    }

    /**
     * 验证使用已过期 revision 保存时返回稳定 HTTP 409 子码，且不覆盖已提交配置。
     *
     * @return void，陈旧写入未被拒绝或数据库版本继续增长时断言失败
     */
    @Test
    void rejectsStaleRevisionWithConflict()
    {
        mailConfigService.save(request(
                "smtp.mysql-it.invalid", "初始配置", credential, 0L));
        mailConfigService.save(request(
                "smtp.mysql-it.invalid", "当前配置", "", 1L));

        ServiceException conflict = assertThrows(ServiceException.class,
                () -> mailConfigService.save(request(
                        "smtp-stale.mysql-it.invalid", "陈旧配置", "", 1L)));
        assertEquals(HttpStatus.CONFLICT, conflict.getCode());
        assertEquals("MAIL_CONFIG_REVISION_CONFLICT", conflict.getSubCode());
        assertEquals(2L, storedCredential().revision());
        assertEquals("smtp.mysql-it.invalid",
                mailConfigService.configuration().smtpHost());
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
     * 查询当前单例行的加密存储快照；对象不会参与断言的 expected/actual 格式化。
     *
     * @return StoredCredential，密文、IV、revision 和创建人
     */
    private StoredCredential storedCredential()
    {
        StoredCredential stored = jdbcTemplate.queryForObject(
                "select credential_ciphertext,credential_iv,revision,create_by "
                + "from sys_mail_config where config_id=1",
                (result, rowNumber) -> new StoredCredential(
                        result.getString("credential_ciphertext"),
                        result.getBytes("credential_iv"),
                        result.getLong("revision"), result.getString("create_by")));
        assertNotNull(stored);
        return stored;
    }

    /**
     * 在数据库端检查单例行全部文本字段，不把待查授权码或命中字段输出到测试报告。
     *
     * @param plaintext String，当前用例内存中的随机授权码
     * @return int，任一文本列包含该明文时为 1，否则为 0
     */
    private int plaintextOccurrenceCount(String plaintext)
    {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from sys_mail_config where config_id=1 and "
                + "concat_ws('|',smtp_host,encryption_mode,username,credential_ciphertext,"
                + "from_address,sender_name,create_by,update_by) "
                + "like concat('%',?,'%')", Integer.class, plaintext);
        assertNotNull(count);
        return count;
    }

    /**
     * 严格对比一个 information_schema 列快照，避免迁移字段类型或排序规则静默漂移。
     *
     * @param columns Map&lt;String,ColumnShape&gt;，按列名索引的真实元数据
     * @param columnName String，当前核验列名
     * @param dataType String，期望基础数据类型
     * @param columnType String，期望完整 MySQL 列类型
     * @param nullable String，YES 或 NO
     * @param maximumLength Long，可空字符或二进制最大长度
     * @param collation String，可空字符排序规则
     * @return void，任一列物理属性不一致时断言失败
     */
    private void assertColumn(Map<String, ColumnShape> columns, String columnName,
            String dataType, String columnType, String nullable, Long maximumLength,
            String collation)
    {
        ColumnShape actual = columns.get(columnName);
        assertNotNull(actual, "缺少迁移列: " + columnName);
        assertEquals(dataType, actual.dataType(), columnName + " data_type 不一致");
        assertEquals(columnType, actual.columnType(), columnName + " column_type 不一致");
        assertEquals(nullable, actual.nullable(), columnName + " nullability 不一致");
        assertEquals(maximumLength, actual.maximumLength(),
                columnName + " maximum length 不一致");
        assertEquals(collation, actual.collation(), columnName + " collation 不一致");
    }

    /**
     * 断言命名 CHECK 的规范化子句包含全部关键业务片段。
     *
     * @param checks Map&lt;String,String&gt;，约束名到规范化 SQL 子句
     * @param constraintName String，正式迁移中的约束名
     * @param fragments String[]，必须全部存在的规范化业务片段
     * @return void，缺少约束或任一业务片段时断言失败
     */
    private void assertClauseContains(Map<String, String> checks, String constraintName,
            String... fragments)
    {
        String clause = checks.get(constraintName);
        assertNotNull(clause, "缺少 CHECK: " + constraintName);
        for (String fragment : fragments)
        {
            assertTrue(clause.contains(fragment), constraintName + " 未覆盖: " + fragment);
        }
    }

    /**
     * 移除 MySQL information_schema CHECK 表达式中的引号和空白差异，并统一大小写。
     *
     * @param clause String，数据库返回的 CHECK 子句
     * @return String，去除反引号和空白影响的全大写子句
     */
    private String normalizeClause(String clause)
    {
        return clause.replace("`", "").replaceAll("\\s+", "")
                .toUpperCase(Locale.ROOT);
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

    /**
     * 为生产服务应用 Spring @Transactional 语义，使保存与提交后发送器失效走真实事务边界。
     *
     * @param target WorkflowMailConfigService，使用真实 JdbcTemplate 的生产服务对象
     * @param manager DataSourceTransactionManager，绑定 approvaplat_it 数据源的事务管理器
     * @return WorkflowMailConfigService，可并发调用的 CGLIB 事务代理
     */
    private WorkflowMailConfigService transactionalProxy(WorkflowMailConfigService target,
            DataSourceTransactionManager manager)
    {
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice(new TransactionInterceptor(manager,
                new AnnotationTransactionAttributeSource()));
        return (WorkflowMailConfigService) proxyFactory.getProxy();
    }

    /**
     * 在进行任何 DELETE 前确认真实连接是 MySQL 8+、目标 schema 精确为 approvaplat_it 且表已迁移。
     *
     * @return void，只读验证数据库身份和 sys_mail_config 表存在性
     * @throws SQLException JDBC 元数据读取失败时向 JUnit 报告
     */
    private static void verifyTargetDatabase() throws SQLException
    {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement())
        {
            try (ResultSet environment = statement.executeQuery(
                    "select version(),database()"))
            {
                assertTrue(environment.next());
                int major = Integer.parseInt(environment.getString(1).split("\\.")[0]);
                assertTrue(major >= 8, "SMTP 配置 IT 只允许 MySQL 8+");
                assertEquals(REQUIRED_DATABASE, environment.getString(2),
                        "拒绝在非 approvaplat_it 数据库执行 SMTP 配置清理");
            }
            try (ResultSet table = statement.executeQuery(
                    "select count(*) from information_schema.tables "
                    + "where table_schema=database() and table_name='sys_mail_config'"))
            {
                assertTrue(table.next());
                assertEquals(1, table.getInt(1), "请先执行 8.0.1 SMTP 正式迁移");
            }
        }
    }

    /**
     * 读取真实 MySQL 验收环境变量，禁止使用默认账号、密码或数据库地址猜测。
     *
     * @param name String，环境变量名
     * @param allowEmpty boolean，是否允许调用方显式提供空密码
     * @return String，调用进程中已显式配置的环境变量值
     */
    private static String requireEnvironment(String name, boolean allowEmpty)
    {
        String value = System.getenv(name);
        if (value == null || (!allowEmpty && value.isBlank()))
        {
            throw new IllegalStateException("未显式配置真实 MySQL 验收变量: " + name);
        }
        return value;
    }

    /** information_schema 中与本合同相关的单列物理属性。 */
    private record ColumnShape(String dataType, String columnType, String nullable,
            Long maximumLength, String collation) { }

    /** 数据库中不向日志输出的授权码加密存储快照。 */
    private record StoredCredential(String ciphertext, byte[] iv, long revision,
            String createBy) { }

    /** 两个并发写者允许出现的完整结果集合。 */
    private enum UpdateOutcome
    {
        SUCCESS,
        CONFLICT
    }
}
