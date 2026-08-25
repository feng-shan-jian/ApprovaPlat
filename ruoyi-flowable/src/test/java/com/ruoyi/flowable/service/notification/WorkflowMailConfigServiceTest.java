package com.ruoyi.flowable.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.RecordComponent;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.dto.WorkflowMailConfigRequest;
import com.ruoyi.flowable.domain.dto.WorkflowMailTestRequest;
import com.ruoyi.flowable.domain.vo.WorkflowMailConfigView;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import tools.jackson.databind.json.JsonMapper;

/**
 * 使用独立 H2 表和生产领域服务验证 SMTP 配置持久化、CAS 与动态发送器切换。
 */
class WorkflowMailConfigServiceTest
{
    private static final String CREDENTIAL = "formal-mail-credential";
    private static final String SCHEMA_RESOURCE =
            "com/ruoyi/flowable/service/notification/workflow-mail-config-h2.sql";

    private JdbcTemplate jdbcTemplate;
    private WorkflowIdentityResolver identityResolver;
    private WorkflowMailCredentialCipher credentialCipher;
    private WorkflowMailConfigService service;

    /**
     * 为每个测试创建独立 H2 MySQL 兼容库，并装配真实加密器和生产配置服务。
     *
     * @return void，无返回值
     */
    @BeforeEach
    void setUp()
    {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:workflow_mail_" +
                UUID.randomUUID().toString().replace("-", "") +
                ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        initializeSchema(dataSource);
        jdbcTemplate = new JdbcTemplate(dataSource);

        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 0x5A);

        identityResolver = mock(WorkflowIdentityResolver.class);
        when(identityResolver.resolveCurrentIdentity())
                .thenReturn(new WorkflowCurrentIdentity("42", Set.of()));
        credentialCipher = spy(new WorkflowMailCredentialCipher(
                new SecretKeySpec(key, "AES"), new SecureRandom()));
        service = new WorkflowMailConfigService(jdbcTemplate, credentialCipher,
                new WorkflowMailFailureClassifier(), identityResolver);
    }

    /**
     * 销毁当前测试专属 H2 对象，避免连接池或后续测试观察到残留配置。
     *
     * @return void，无返回值
     */
    @AfterEach
    void tearDown()
    {
        if (jdbcTemplate != null)
        {
            jdbcTemplate.execute("DROP ALL OBJECTS");
        }
    }

    /**
     * 验证首次保存固定插入 config_id=1/revision=1，数据库仅保存 AES-GCM 密文。
     *
     * @return void，单例、版本或密文落库规则漂移时测试失败
     */
    @Test
    void insertsFirstConfigurationWithEncryptedCredentialAndRevisionOne()
    {
        WorkflowMailConfigView view = service.save(validRequest(CREDENTIAL, 0L));

        assertThat(view.configured()).isTrue();
        assertThat(view.credentialConfigured()).isTrue();
        assertThat(view.revision()).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "select config_id from sys_mail_config", Long.class)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "select revision from sys_mail_config", Long.class)).isEqualTo(1L);
        String ciphertext = credentialCiphertext();
        assertThat(ciphertext).isNotBlank().isNotEqualTo(CREDENTIAL)
                .doesNotContain(CREDENTIAL);
        assertThat(credentialIv()).hasSize(12);
        assertThat(jdbcTemplate.queryForObject(
                "select create_by from sys_mail_config", String.class)).isEqualTo("42");
    }

    /**
     * 验证首次配置未提供授权码时失败，且数据库不会产生半成品单例行。
     *
     * @return void，首次授权码必填或原子性规则漂移时测试失败
     */
    @Test
    void rejectsFirstConfigurationWithoutCredential()
    {
        assertThatThrownBy(() -> service.save(validRequest("  ", 0L)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getSubCode()).isEqualTo("MAIL_CREDENTIAL_REQUIRED");
                    assertThat(exception.getMessage()).doesNotContain(CREDENTIAL);
                });
        assertThat(rowCount()).isZero();
    }

    /**
     * 验证认证身份未变时，后续保存留空授权码可更新 From 身份并保持原密文与 IV。
     *
     * @return void，留空授权码导致重加密、清空或版本不增长时测试失败
     */
    @Test
    void keepsCiphertextAndIvWhenCredentialIsBlankOnUpdate()
    {
        service.save(validRequest(CREDENTIAL, 0L));
        String originalCiphertext = credentialCiphertext();
        byte[] originalIv = credentialIv();

        WorkflowMailConfigView updated = service.save(new WorkflowMailConfigRequest(
                "smtp.initial.example.com", 587, "STARTTLS", "mailer@example.com", "",
                "updated-sender@example.com", "更新后的通知中心", 1L));

        assertThat(updated.revision()).isEqualTo(2L);
        assertThat(updated.smtpHost()).isEqualTo("smtp.initial.example.com");
        assertThat(updated.encryptionMode()).isEqualTo("STARTTLS");
        assertThat(updated.fromAddress()).isEqualTo("updated-sender@example.com");
        assertThat(credentialCiphertext()).isEqualTo(originalCiphertext);
        assertThat(credentialIv()).isEqualTo(originalIv);
        assertThat(jdbcTemplate.queryForObject(
                "select revision from sys_mail_config", Long.class)).isEqualTo(2L);
    }

    /**
     * 验证保存时任一 SMTP 认证身份字段变化都必须重新输入授权码，且拒绝发生在解密和写库之前。
     *
     * @return void，旧凭据可被沿用于新端点、新模式或新账号时测试失败
     */
    @Test
    void rejectsBlankCredentialWhenAuthenticationIdentityChangesOnSave()
    {
        service.save(validRequest(CREDENTIAL, 0L));
        String originalCiphertext = credentialCiphertext();
        byte[] originalIv = credentialIv();
        List<WorkflowMailConfigRequest> changedIdentities = List.of(
                new WorkflowMailConfigRequest("smtp.other.example.com", 587,
                        "STARTTLS", "mailer@example.com", "",
                        "sender@example.com", "通知中心", 1L),
                new WorkflowMailConfigRequest("smtp.initial.example.com", 465,
                        "STARTTLS", "mailer@example.com", "",
                        "sender@example.com", "通知中心", 1L),
                new WorkflowMailConfigRequest("smtp.initial.example.com", 587,
                        "SSL", "mailer@example.com", "",
                        "sender@example.com", "通知中心", 1L),
                new WorkflowMailConfigRequest("smtp.initial.example.com", 587,
                        "STARTTLS", "other@example.com", "",
                        "sender@example.com", "通知中心", 1L));

        for (WorkflowMailConfigRequest request : changedIdentities)
        {
            clearInvocations(credentialCipher);
            assertCredentialReentryRequired(() -> service.save(request));
            verify(credentialCipher, never()).decrypt(anyString(), any(byte[].class));
            assertThat(jdbcTemplate.queryForObject(
                    "select revision from sys_mail_config", Long.class)).isEqualTo(1L);
            assertThat(credentialCiphertext()).isEqualTo(originalCiphertext);
            assertThat(credentialIv()).isEqualTo(originalIv);
        }
    }

    /**
     * 验证未保存测试同样不能把旧授权码带到新 SMTP 认证身份，拒绝前不得解密或建立连接。
     *
     * @return void，测试接口绕过授权码重新输入边界时测试失败
     */
    @Test
    void rejectsBlankCredentialWhenAuthenticationIdentityChangesOnTest()
    {
        service.save(validRequest(CREDENTIAL, 0L));
        List<WorkflowMailTestRequest> changedIdentities = List.of(
                testRequest("smtp.other.example.com", 587, "STARTTLS",
                        "mailer@example.com"),
                testRequest("smtp.initial.example.com", 465, "STARTTLS",
                        "mailer@example.com"),
                testRequest("smtp.initial.example.com", 587, "SSL",
                        "mailer@example.com"),
                testRequest("smtp.initial.example.com", 587, "STARTTLS",
                        "other@example.com"));

        for (WorkflowMailTestRequest request : changedIdentities)
        {
            clearInvocations(credentialCipher);
            assertCredentialReentryRequired(() -> service.sendTest(request));
            verify(credentialCipher, never()).decrypt(anyString(), any(byte[].class));
            assertThat(jdbcTemplate.queryForObject(
                    "select revision from sys_mail_config", Long.class)).isEqualTo(1L);
        }
    }

    /**
     * 验证读取后发生并发 revision 变化时条件更新返回 409，不能覆盖并发写入。
     *
     * @return void，CAS 条件丢失或冲突子码漂移时测试失败
     */
    @Test
    void rejectsCompareAndSetUpdateAfterConcurrentRevisionChange()
    {
        service.save(validRequest(CREDENTIAL, 0L));
        when(identityResolver.resolveCurrentIdentity()).thenAnswer(invocation ->
        {
            // 模拟另一管理员在本次服务读取 revision=1 后先提交，迫使生产 UPDATE 的 CAS 条件落空。
            jdbcTemplate.update("update sys_mail_config set revision=revision+1, " +
                    "update_by='concurrent' where config_id=1");
            return new WorkflowCurrentIdentity("42", Set.of());
        });

        assertThatThrownBy(() -> service.save(new WorkflowMailConfigRequest(
                "smtp.stale.example.com", 587, "STARTTLS", "mailer@example.com", CREDENTIAL,
                "sender@example.com", "过期管理员输入", 1L)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getSubCode())
                            .isEqualTo("MAIL_CONFIG_REVISION_CONFLICT");
                });

        assertThat(jdbcTemplate.queryForObject(
                "select revision from sys_mail_config", Long.class)).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject(
                "select smtp_host from sys_mail_config", String.class))
                .isEqualTo("smtp.initial.example.com");
    }

    /**
     * 验证端口、加密枚举、登录邮箱和发件邮箱均在写库前严格校验。
     *
     * @return void，任一非法公开字段可以落库时测试失败
     */
    @Test
    void validatesPortEncryptionModeAndEmailFields()
    {
        List<WorkflowMailConfigRequest> invalidRequests = List.of(
                request("smtp.example.com", 0, "STARTTLS", "mailer@example.com",
                        "sender@example.com"),
                request("smtp.example.com", 65_536, "STARTTLS", "mailer@example.com",
                        "sender@example.com"),
                request("smtp.example.com", 587, "TLS", "mailer@example.com",
                        "sender@example.com"),
                request("smtp.example.com", 587, "STARTTLS", "bad@@example.com",
                        "sender@example.com"),
                request("smtp.example.com", 587, "STARTTLS", "mailer@example.com",
                        "Sender <sender@example.com>"));

        invalidRequests.forEach(request -> assertThatThrownBy(() -> service.save(request))
                .isInstanceOfSatisfying(ServiceException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo(HttpStatus.BAD_REQUEST)));
        assertThat(rowCount()).isZero();
    }

    /**
     * 验证查询视图和 JSON 响应仅含公开白名单字段，不携带授权码、密文或 IV。
     *
     * @return void，安全视图结构或序列化字段出现敏感材料时测试失败
     * @throws Exception Jackson 序列化异常会使测试直接失败
     */
    @Test
    void exposesOnlySafeConfigurationViewFields() throws Exception
    {
        WorkflowMailConfigView unconfigured = service.configuration();
        assertThat(unconfigured).isEqualTo(WorkflowMailConfigView.unconfigured());
        assertThat(JsonMapper.shared().writeValueAsString(unconfigured))
                .isEqualTo("{\"configured\":false,\"credentialConfigured\":false," +
                        "\"revision\":0}");

        WorkflowMailConfigView configured = service.save(validRequest(CREDENTIAL, 0L));
        String ciphertext = credentialCiphertext();
        String encodedIv = Base64.getEncoder().encodeToString(credentialIv());
        List<String> componentNames = Arrays.stream(
                WorkflowMailConfigView.class.getRecordComponents())
                .map(RecordComponent::getName).toList();
        String json = JsonMapper.shared().writeValueAsString(configured);

        assertThat(componentNames).containsExactly("configured", "smtpHost", "smtpPort",
                "encryptionMode", "username", "credentialConfigured", "fromAddress",
                "senderName", "revision");
        assertThat(json).doesNotContain(CREDENTIAL, ciphertext, encodedIv,
                "\"credential\":", "credentialCiphertext", "credentialIv");
    }

    /**
     * 验证数据库 revision 改变后下一次获取原子切换 sender，已取得旧快照仍保持可用。
     *
     * @return void，跨节点 revision 感知、快照替换或旧对象生命周期漂移时测试失败
     */
    @Test
    void switchesCurrentSenderOnDatabaseRevisionChangeAndKeepsOldSnapshotUsable()
    {
        service.save(validRequest(CREDENTIAL, 0L));
        WorkflowMailConfigService.MailSenderSnapshot first = service.currentSender();
        WorkflowMailConfigService.MailSenderSnapshot cached = service.currentSender();
        JavaMailSenderImpl firstSender = (JavaMailSenderImpl) first.mailSender();

        assertThat(cached).isSameAs(first);
        assertThat(first.revision()).isEqualTo(1L);
        assertThat(firstSender.getHost()).isEqualTo("smtp.initial.example.com");

        // 直接修改正式表模拟另一应用节点保存；本节点没有收到显式失效通知，只能依靠 revision 复核。
        jdbcTemplate.update("update sys_mail_config set smtp_host=?, smtp_port=?, " +
                "encryption_mode=?, sender_name=?, revision=revision+1 where config_id=1",
                "smtp.second.example.com", 465, "SSL", "第二发送身份");

        WorkflowMailConfigService.MailSenderSnapshot replacement = service.currentSender();
        JavaMailSenderImpl replacementSender =
                (JavaMailSenderImpl) replacement.mailSender();

        assertThat(replacement).isNotSameAs(first);
        assertThat(replacement.mailSender()).isNotSameAs(first.mailSender());
        assertThat(replacement.revision()).isEqualTo(2L);
        assertThat(replacementSender.getHost()).isEqualTo("smtp.second.example.com");
        assertThat(replacementSender.getPort()).isEqualTo(465);
        assertThat(replacement.senderName()).isEqualTo("第二发送身份");
        assertThat(firstSender.getHost()).isEqualTo("smtp.initial.example.com");
        assertThat(first.revision()).isEqualTo(1L);
        assertThatCode(first.mailSender()::createMimeMessage).doesNotThrowAnyException();
    }

    /**
     * 断言认证身份变化后的空授权码请求返回安全稳定的 HTTP 400，而不泄露任何身份或凭据值。
     *
     * @param operation ThrowingCallable，待执行的保存或未保存测试调用
     * @return void，错误状态、子码或脱敏消息漂移时测试失败
     */
    private void assertCredentialReentryRequired(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable operation)
    {
        assertThatThrownBy(operation)
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getSubCode())
                            .isEqualTo("MAIL_CREDENTIAL_REENTRY_REQUIRED");
                    assertThat(exception.getMessage()).isEqualTo(
                            "SMTP 服务器、端口、加密方式或登录账号已变更，请重新填写授权码或密码");
                    assertThat(exception.getMessage()).doesNotContain(CREDENTIAL,
                            "smtp.other.example.com", "other@example.com");
                });
    }

    /**
     * 创建一份留空授权码且改变指定认证身份字段的未保存测试请求。
     *
     * @param host String，待测试 SMTP 主机
     * @param port int，待测试 SMTP 端口
     * @param encryptionMode String，待测试加密模式
     * @param username String，待测试登录账号
     * @return WorkflowMailTestRequest，固定使用 revision 1 且不会真正连接的请求
     */
    private WorkflowMailTestRequest testRequest(String host, int port,
            String encryptionMode, String username)
    {
        return new WorkflowMailTestRequest(host, port, encryptionMode, username, "",
                "sender@example.com", "通知中心", "recipient@example.com", 1L);
    }

    /**
     * 执行测试专用 H2 schema，表结构与生产 Service 实际 SQL 字段保持一致。
     *
     * @param dataSource DataSource，当前测试独占的 H2 数据源
     * @return void，无返回值
     */
    private void initializeSchema(DataSource dataSource)
    {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ClassPathResource(SCHEMA_RESOURCE));
        populator.execute(dataSource);
    }

    /**
     * 创建一份完整有效的保存请求。
     *
     * @param credential String，可空或空白授权码
     * @param expectedRevision long，客户端期望版本
     * @return WorkflowMailConfigRequest，其他 SMTP 字段均有效的请求
     */
    private WorkflowMailConfigRequest validRequest(String credential, long expectedRevision)
    {
        return new WorkflowMailConfigRequest("smtp.initial.example.com", 587,
                "STARTTLS", "mailer@example.com", credential, "sender@example.com",
                "ApprovaPlat 审批通知", expectedRevision);
    }

    /**
     * 创建用于字段校验的首次保存请求。
     *
     * @param host String，SMTP 主机
     * @param port Integer，SMTP 端口
     * @param encryptionMode String，加密枚举
     * @param username String，登录邮箱
     * @param fromAddress String，发件邮箱
     * @return WorkflowMailConfigRequest，带固定测试授权码且 expectedRevision=0 的请求
     */
    private WorkflowMailConfigRequest request(String host, Integer port,
            String encryptionMode, String username, String fromAddress)
    {
        return new WorkflowMailConfigRequest(host, port, encryptionMode, username,
                CREDENTIAL, fromAddress, "ApprovaPlat 审批通知", 0L);
    }

    /**
     * 读取数据库中的授权码密文。
     *
     * @return String，Base64 编码的 AES-GCM 密文和认证标签
     */
    private String credentialCiphertext()
    {
        return jdbcTemplate.queryForObject(
                "select credential_ciphertext from sys_mail_config where config_id=1",
                String.class);
    }

    /**
     * 读取数据库中的授权码随机 IV。
     *
     * @return byte[]，固定 12 字节数据库副本
     */
    private byte[] credentialIv()
    {
        return jdbcTemplate.queryForObject(
                "select credential_iv from sys_mail_config where config_id=1",
                byte[].class);
    }

    /**
     * 查询平台 SMTP 单例表当前行数。
     *
     * @return int，未配置为 0，已配置为 1
     */
    private int rowCount()
    {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from sys_mail_config", Integer.class);
        return count == null ? 0 : count;
    }
}
