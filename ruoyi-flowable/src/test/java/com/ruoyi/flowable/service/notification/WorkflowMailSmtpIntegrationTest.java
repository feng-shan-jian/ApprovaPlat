package com.ruoyi.flowable.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import javax.crypto.spec.SecretKeySpec;
import jakarta.mail.internet.MimeMessage;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.dto.WorkflowMailTestRequest;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.testsupport.LocalSmtpTestServer;
import com.ruoyi.flowable.testsupport.WorkflowH2SchemaMapperSupport;

/**
 * 通过本机真实 TCP SMTP 会话验证成功协议和稳定错误分类。
 */
class WorkflowMailSmtpIntegrationTest
{
    private static final String USERNAME = "mailer@example.com";
    private static final String CREDENTIAL = "smtp-integration-secret";
    private static final String FROM_ADDRESS = "sender@example.com";
    private static final String RECIPIENT = "recipient@example.com";

    private JdbcDataSource dataSource;
    private WorkflowMailConfigService service;
    /** 当前测试创建的服务，统一在 tearDown 中关闭以覆盖断言提前失败分支。 */
    private final List<LocalSmtpTestServer> servers = new ArrayList<>();

    /**
     * 为每个测试创建独立 H2 数据库，并装配真实生产 SMTP 配置服务。
     *
     * @return void，无返回值
     */
    @BeforeEach
    void setUp()
    {
        dataSource = WorkflowH2SchemaMapperSupport.createDataSource(
                "workflow_mail_smtp_" + System.nanoTime(), true, 5_000);
        WorkflowH2SchemaMapperSupport.executeSchema(dataSource,
                WorkflowH2SchemaMapperSupport.MAIL_CONFIG_SCHEMA);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 0x3C);
        WorkflowIdentityResolver identityResolver = mock(WorkflowIdentityResolver.class);
        when(identityResolver.resolveCurrentIdentity())
                .thenReturn(new WorkflowCurrentIdentity("100", Set.of()));
        service = new WorkflowMailConfigService(jdbcTemplate,
                new WorkflowMailCredentialCipher(new SecretKeySpec(key, "AES"),
                        new SecureRandom()),
                new WorkflowMailFailureClassifier(), identityResolver);
    }

    /**
     * 关闭全部监听 socket、已接入连接和工作线程，并销毁测试独占 H2 数据库。
     *
     * @return void，无返回值
     */
    @AfterEach
    void tearDown()
    {
        for (LocalSmtpTestServer server : servers)
        {
            server.close();
        }
        servers.clear();
        WorkflowH2SchemaMapperSupport.shutdown(dataSource);
    }

    /**
     * 验证测试邮件完成 AUTH、信封、DATA 和 MIME 交付的真实 SMTP 成功协议。
     *
     * @return void，邮件未到达或信封、主题、收件人漂移时失败
     * @throws Exception MIME 解析或接收等待失败时直接失败
     */
    @Test
    void sendsMimeThroughRealSmtpProtocol() throws Exception
    {
        LocalSmtpTestServer server = startServer(LocalSmtpTestServer.Behavior.ACCEPT);

        var result = service.sendTest(testRequest(server.port(), CREDENTIAL, 0L));

        LocalSmtpTestServer.ReceivedMessage received = server.awaitMessage();
        MimeMessage mimeMessage = received.parse();
        assertThat(result).containsEntry("success", true).containsKey("testedAt");
        assertThat(mimeMessage.getSubject()).isEqualTo("ApprovaPlat SMTP 测试邮件");
        assertThat(mimeMessage.getAllRecipients()).extracting(Object::toString)
                .containsExactly(RECIPIENT);
        assertThat(received.mailFrom()).contains(FROM_ADDRESS);
        assertThat(received.recipient()).contains(RECIPIENT);
    }

    /**
     * 验证真实 SMTP 认证拒绝被稳定分类，响应不得包含主机、账号或授权码。
     *
     * @return void，认证错误码或脱敏边界漂移时失败
     */
    @Test
    void classifiesAuthenticationFailureWithoutSensitiveDetails()
    {
        LocalSmtpTestServer server = startServer(
                LocalSmtpTestServer.Behavior.REJECT_AUTHENTICATION);

        assertThatThrownBy(() -> service.sendTest(
                testRequest(server.port(), CREDENTIAL, 0L)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getSubCode()).isEqualTo("SMTP_AUTH_FAILED");
                    assertThat(exception.getMessage()).isEqualTo(
                            "SMTP 认证失败，请检查登录账号和授权码");
                    assertThat(exception.getMessage()).doesNotContain(
                            USERNAME, CREDENTIAL, "127.0.0.1");
                });
    }

    /**
     * 验证 STARTTLS 命令进入 TLS 握手后，远端中断会被稳定分类且不泄露连接参数。
     *
     * @return void，STARTTLS 未被要求、TLS 握手异常分类错误或敏感信息泄露时失败
     */
    @Test
    void classifiesStartTlsNegotiationFailureWithoutSensitiveDetails()
    {
        LocalSmtpTestServer server = startServer(
                LocalSmtpTestServer.Behavior.REJECT_STARTTLS_NEGOTIATION);
        WorkflowMailTestRequest request = new WorkflowMailTestRequest(
                "127.0.0.1", server.port(), "STARTTLS", USERNAME, CREDENTIAL,
                FROM_ADDRESS, "ApprovaPlat 审批通知", RECIPIENT, 0L);

        assertThatThrownBy(() -> service.sendTest(request))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getSubCode()).isEqualTo("SMTP_TLS_FAILED");
                    assertThat(exception.getMessage()).isEqualTo(
                            "SMTP TLS 或 SSL 协商失败");
                    assertThat(exception.getMessage()).doesNotContain(
                            USERNAME, CREDENTIAL, FROM_ADDRESS, RECIPIENT,
                            "127.0.0.1", String.valueOf(server.port()));
                });
        assertThat(server.awaitConnection()).isTrue();
    }

    /**
     * 验证连接到已关闭的本地端口时返回稳定连接失败分类，且不泄露连接参数。
     *
     * @return void，连接异常分类或脱敏边界漂移时失败
     * @throws IOException 临时端口分配失败时直接失败
     */
    @Test
    void classifiesConnectionFailureWithoutSensitiveDetails() throws IOException
    {
        int closedPort;
        try (ServerSocket reservation = new ServerSocket(
                0, 1, InetAddress.getLoopbackAddress()))
        {
            closedPort = reservation.getLocalPort();
        }

        assertThatThrownBy(() -> service.sendTest(
                testRequest(closedPort, CREDENTIAL, 0L)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getSubCode()).isEqualTo("SMTP_CONNECT_FAILED");
                    assertThat(exception.getMessage()).isEqualTo("无法连接 SMTP 服务器");
                    assertThat(exception.getMessage()).doesNotContain(
                            USERNAME, CREDENTIAL, String.valueOf(closedPort));
                });
    }

    /**
     * 验证服务器在 MAIL FROM 阶段真实返回 550 时稳定识别发件人拒绝。
     *
     * @return void，错误码或脱敏边界漂移时失败
     */
    @Test
    void classifiesMailFromRejectionWithoutSensitiveDetails()
    {
        LocalSmtpTestServer server = startServer(
                LocalSmtpTestServer.Behavior.REJECT_MAIL_FROM);

        assertThatThrownBy(() -> service.sendTest(
                testRequest(server.port(), CREDENTIAL, 0L)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getSubCode()).isEqualTo("SMTP_FROM_REJECTED");
                    assertThat(exception.getMessage()).isEqualTo(
                            "发件邮箱被 SMTP 服务器拒绝");
                    assertThat(exception.getMessage()).doesNotContain(
                            USERNAME, CREDENTIAL, FROM_ADDRESS, RECIPIENT,
                            "127.0.0.1");
                });
    }

    /**
     * 验证服务器接收 TCP 后不发送欢迎语时，由生产读取超时终止并返回稳定超时分类。
     *
     * @return void，读取超时未生效、分类错误或敏感信息泄露时失败
     */
    @Test
    void classifiesGreetingReadTimeoutWithoutSensitiveDetails()
    {
        LocalSmtpTestServer server = startServer(
                LocalSmtpTestServer.Behavior.SUPPRESS_GREETING);
        long startedNanos = System.nanoTime();

        assertThatThrownBy(() -> service.sendTest(
                testRequest(server.port(), CREDENTIAL, 0L)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getSubCode()).isEqualTo("SMTP_TIMEOUT");
                    assertThat(exception.getMessage()).isEqualTo(
                            "SMTP 连接或发送超时");
                    assertThat(exception.getMessage()).doesNotContain(
                            USERNAME, CREDENTIAL, "127.0.0.1");
                });
        assertThat(server.awaitConnection()).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - startedNanos))
                .isBetween(Duration.ofSeconds(9), Duration.ofSeconds(14));
    }

    /**
     * 启动并登记一个测试独占的真实本地 SMTP 服务。
     *
     * @param behavior LocalSmtpTestServer.Behavior，当前测试要求的服务行为
     * @return LocalSmtpTestServer，已经开始监听的服务
     */
    private LocalSmtpTestServer startServer(LocalSmtpTestServer.Behavior behavior)
    {
        LocalSmtpTestServer server = new LocalSmtpTestServer(
                behavior, USERNAME, CREDENTIAL);
        servers.add(server);
        return server;
    }

    /**
     * 创建指向真实本地服务的未保存测试邮件请求。
     *
     * @param port int，本地 SMTP 服务端口
     * @param credential String，本次弹窗授权码
     * @param expectedRevision long，客户端配置版本
     * @return WorkflowMailTestRequest，完整测试邮件请求
     */
    private WorkflowMailTestRequest testRequest(int port, String credential,
            long expectedRevision)
    {
        return new WorkflowMailTestRequest("127.0.0.1", port, "NONE", USERNAME,
                credential, FROM_ADDRESS, "ApprovaPlat 审批通知", RECIPIENT,
                expectedRevision);
    }
}
