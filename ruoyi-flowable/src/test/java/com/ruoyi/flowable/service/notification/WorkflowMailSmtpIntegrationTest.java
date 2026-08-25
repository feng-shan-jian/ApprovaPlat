package com.ruoyi.flowable.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mail.SimpleMailMessage;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.dto.WorkflowMailConfigRequest;
import com.ruoyi.flowable.domain.dto.WorkflowMailTestRequest;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;

/**
 * 通过本机真实 TCP SMTP 会话验证测试邮件、正式通知投递和动态发送器切换。
 */
class WorkflowMailSmtpIntegrationTest
{
    private static final String SCHEMA_RESOURCE =
            "com/ruoyi/flowable/service/notification/workflow-mail-config-h2.sql";
    private static final String USERNAME = "mailer@example.com";
    private static final String CREDENTIAL = "smtp-integration-secret";
    private static final String FROM_ADDRESS = "sender@example.com";
    private static final String RECIPIENT = "recipient@example.com";

    private JdbcTemplate jdbcTemplate;
    private WorkflowMailFailureClassifier failureClassifier;
    private WorkflowMailConfigService service;
    /** 当前测试创建的接收器，统一在 tearDown 中关闭以覆盖断言提前失败分支。 */
    private final List<LocalSmtpReceiver> receivers = new ArrayList<>();

    /**
     * 为每个测试创建独立 H2 数据库，并装配真实生产 SMTP 配置服务。
     *
     * @return void，无返回值
     */
    @BeforeEach
    void setUp()
    {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:workflow_mail_smtp_" +
                UUID.randomUUID().toString().replace("-", "") +
                ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        initializeSchema(dataSource);
        jdbcTemplate = new JdbcTemplate(dataSource);

        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 0x3C);

        WorkflowIdentityResolver identityResolver = mock(WorkflowIdentityResolver.class);
        when(identityResolver.resolveCurrentIdentity())
                .thenReturn(new WorkflowCurrentIdentity("100", Set.of()));
        failureClassifier = new WorkflowMailFailureClassifier();
        service = new WorkflowMailConfigService(jdbcTemplate,
                new WorkflowMailCredentialCipher(new SecretKeySpec(key, "AES"),
                        new SecureRandom()), failureClassifier, identityResolver);
    }

    /**
     * 关闭全部监听 socket、已接入连接和工作线程，并删除测试独占 H2 对象。
     *
     * @return void，无返回值
     */
    @AfterEach
    void tearDown()
    {
        for (LocalSmtpReceiver receiver : receivers)
        {
            receiver.close();
        }
        receivers.clear();
        if (jdbcTemplate != null)
        {
            jdbcTemplate.execute("DROP ALL OBJECTS");
        }
    }

    /**
     * 验证弹窗首次配置可使用尚未保存的参数真实发送，且测试操作不写正式配置表。
     *
     * @return void，邮件未到达或测试产生配置副作用时失败
     * @throws Exception MIME 解析或接收等待失败时直接失败
     */
    @Test
    void sendsUnsavedDialogConfigurationWithoutPersistingIt() throws Exception
    {
        LocalSmtpReceiver receiver = startReceiver(SmtpBehavior.ACCEPT);

        var result = service.sendTest(testRequest(receiver.port(), CREDENTIAL, 0L));

        ReceivedMessage received = receiver.awaitMessage();
        MimeMessage mimeMessage = received.parse();
        assertThat(result).containsEntry("success", true).containsKey("testedAt");
        assertThat(mimeMessage.getSubject()).isEqualTo("ApprovaPlat SMTP 测试邮件");
        assertThat(mimeMessage.getAllRecipients()).extracting(Object::toString)
                .containsExactly(RECIPIENT);
        assertThat(received.mailFrom()).contains(FROM_ADDRESS);
        assertThat(received.recipient()).contains(RECIPIENT);
        assertThat(configurationRowCount()).isZero();
    }

    /**
     * 验证认证身份不变时，未保存测试可沿用正式授权码并使用新的 From 地址和发件人名称。
     *
     * @return void，非认证展示字段被错误要求新授权码或测试写回正式配置时失败
     * @throws Exception MIME 解析或接收等待失败时直接失败
     */
    @Test
    void reusesSavedCredentialForUnsavedFromIdentityChanges() throws Exception
    {
        LocalSmtpReceiver receiver = startReceiver(SmtpBehavior.ACCEPT);
        service.save(saveRequest(receiver.port(), CREDENTIAL, 0L));
        WorkflowMailTestRequest request = new WorkflowMailTestRequest(
                "127.0.0.1", receiver.port(), "NONE", USERNAME, "",
                "updated-sender@example.com", "更新后的审批通知", RECIPIENT, 1L);

        var result = service.sendTest(request);

        MimeMessage message = receiver.awaitMessage().parse();
        InternetAddress from = (InternetAddress) message.getFrom()[0];
        assertThat(result).containsEntry("success", true);
        assertThat(from.getAddress()).isEqualTo("updated-sender@example.com");
        assertThat(from.getPersonal()).isEqualTo("更新后的审批通知");
        assertThat(configurationRowCount()).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "select revision from sys_mail_config where config_id=1", Long.class))
                .isEqualTo(1L);
        assertThat(service.configuration().fromAddress()).isEqualTo(FROM_ADDRESS);
    }

    /**
     * 验证保存后的加密正式配置被 MailNotificationChannel 读取，并经真实 SMTP 到达。
     *
     * @return void，正式 outbox 快照未送达或投递结果不成功时失败
     * @throws Exception 接收等待失败时直接失败
     */
    @Test
    void deliversSavedConfigurationThroughMailNotificationChannel() throws Exception
    {
        LocalSmtpReceiver receiver = startReceiver(SmtpBehavior.ACCEPT);
        service.save(saveRequest(receiver.port(), CREDENTIAL, 0L));
        MailNotificationChannel channel =
                new MailNotificationChannel(service, failureClassifier);
        WorkflowNotificationOutboxRecord row = outboxRecord(
                "saved-config-event", "正式审批提醒", "审批任务已经到达。", RECIPIENT);

        WorkflowNotificationDeliveryResult delivery = channel.deliver(row);

        ReceivedMessage received = receiver.awaitMessage();
        MimeMessage mimeMessage = received.parse();
        assertThat(delivery.success()).isTrue();
        assertThat(delivery.errorCode()).isNull();
        assertThat(mimeMessage.getSubject()).isEqualTo("正式审批提醒");
        assertThat(mimeMessage.getHeader("X-ApprovaPlat-Idempotency-Key", null))
                .isEqualTo("saved-config-event");
        assertThat(configurationRowCount()).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "select revision from sys_mail_config where config_id=1", Long.class))
                .isEqualTo(1L);
    }

    /**
     * 验证 revision 更新端口后下一封邮件无需重启即切换，新旧快照可以各自完成已开始的投递。
     *
     * @return void，新发送器未切换或旧发送器被破坏时失败
     * @throws Exception 接收等待失败时直接失败
     */
    @Test
    void switchesPortAfterRevisionUpdateAndKeepsOldSnapshotUsable() throws Exception
    {
        LocalSmtpReceiver firstReceiver = startReceiver(SmtpBehavior.ACCEPT);
        LocalSmtpReceiver secondReceiver = startReceiver(SmtpBehavior.ACCEPT);
        service.save(saveRequest(firstReceiver.port(), CREDENTIAL, 0L));
        WorkflowMailConfigService.MailSenderSnapshot oldSnapshot = service.currentSender();

        // 端口属于认证身份，切换端点必须显式提交授权码，禁止旧凭据被静默转发。
        service.save(saveRequest(secondReceiver.port(), CREDENTIAL, 1L));
        WorkflowMailConfigService.MailSenderSnapshot newSnapshot = service.currentSender();
        MailNotificationChannel channel =
                new MailNotificationChannel(service, failureClassifier);
        WorkflowNotificationDeliveryResult newDelivery = channel.deliver(outboxRecord(
                "new-revision-event", "新配置邮件", "新配置正文", RECIPIENT));

        // 模拟更新发生前已经领取旧快照的发送线程，配置切换不能使它的在途邮件失败。
        SimpleMailMessage oldMessage = new SimpleMailMessage();
        oldMessage.setFrom(FROM_ADDRESS);
        oldMessage.setTo(RECIPIENT);
        oldMessage.setSubject("旧快照在途邮件");
        oldMessage.setText("该邮件应继续使用旧端口完成。");
        oldSnapshot.mailSender().send(oldMessage);

        MimeMessage newMimeMessage = secondReceiver.awaitMessage().parse();
        MimeMessage oldMimeMessage = firstReceiver.awaitMessage().parse();
        assertThat(newDelivery.success()).isTrue();
        assertThat(oldSnapshot.revision()).isEqualTo(1L);
        assertThat(newSnapshot.revision()).isEqualTo(2L);
        assertThat(newSnapshot).isNotSameAs(oldSnapshot);
        assertThat(newMimeMessage.getSubject()).isEqualTo("新配置邮件");
        assertThat(oldMimeMessage.getSubject()).isEqualTo("旧快照在途邮件");
    }

    /**
     * 验证真实 SMTP 认证拒绝被稳定分类，响应不得包含主机、账号或授权码。
     *
     * @return void，认证错误码或脱敏边界漂移时失败
     */
    @Test
    void classifiesAuthenticationFailureWithoutSensitiveDetails()
    {
        LocalSmtpReceiver receiver = startReceiver(SmtpBehavior.REJECT_AUTHENTICATION);

        assertThatThrownBy(() -> service.sendTest(
                testRequest(receiver.port(), CREDENTIAL, 0L)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getSubCode()).isEqualTo("SMTP_AUTH_FAILED");
                    assertThat(exception.getMessage()).isEqualTo(
                            "SMTP 认证失败，请检查登录账号和授权码");
                    assertThat(exception.getMessage()).doesNotContain(
                            USERNAME, CREDENTIAL, "127.0.0.1");
                });

        // 正式投递走同一分类器，outbox 可持久化的摘要同样不能带底层 SMTP 详情。
        service.save(saveRequest(receiver.port(), CREDENTIAL, 0L));
        WorkflowNotificationDeliveryResult delivery =
                new MailNotificationChannel(service, failureClassifier).deliver(
                        outboxRecord("auth-failure-event", "认证失败场景", "正文", RECIPIENT));
        assertThat(delivery.success()).isFalse();
        assertThat(delivery.errorCode()).isEqualTo("SMTP_AUTH_FAILED");
        assertThat(delivery.summary()).isEqualTo("SMTP 认证失败");
        assertThat(delivery.summary()).doesNotContain(
                USERNAME, CREDENTIAL, "127.0.0.1", RECIPIENT);
    }

    /**
     * 验证真实 STARTTLS 命令进入 TLS 握手后，远端中断会被稳定分类且不泄露连接参数。
     *
     * @return void，STARTTLS 未被要求、TLS 握手异常分类错误或敏感信息泄露时失败
     */
    @Test
    void classifiesStartTlsNegotiationFailureWithoutSensitiveDetails()
    {
        LocalSmtpReceiver receiver = startReceiver(
                SmtpBehavior.REJECT_STARTTLS_NEGOTIATION);
        WorkflowMailTestRequest request = new WorkflowMailTestRequest(
                "127.0.0.1", receiver.port(), "STARTTLS", USERNAME, CREDENTIAL,
                FROM_ADDRESS, "ApprovaPlat 审批通知", RECIPIENT, 0L);

        assertThatThrownBy(() -> service.sendTest(request))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getSubCode()).isEqualTo("SMTP_TLS_FAILED");
                    assertThat(exception.getMessage()).isEqualTo(
                            "SMTP TLS 或 SSL 协商失败");
                    assertThat(exception.getMessage()).doesNotContain(
                            USERNAME, CREDENTIAL, FROM_ADDRESS, RECIPIENT,
                            "127.0.0.1", String.valueOf(receiver.port()));
                });
        assertThat(receiver.awaitConnection()).isTrue();
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
     * 验证服务器在 MAIL FROM 阶段真实返回 550 时，测试接口和正式通道均稳定识别发件人拒绝。
     *
     * @return void，错误码、摘要或脱敏边界漂移时失败
     */
    @Test
    void classifiesMailFromRejectionWithoutSensitiveDetails()
    {
        LocalSmtpReceiver receiver = startReceiver(SmtpBehavior.REJECT_MAIL_FROM);

        assertThatThrownBy(() -> service.sendTest(
                testRequest(receiver.port(), CREDENTIAL, 0L)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getSubCode()).isEqualTo("SMTP_FROM_REJECTED");
                    assertThat(exception.getMessage()).isEqualTo(
                            "发件邮箱被 SMTP 服务器拒绝");
                    assertThat(exception.getMessage()).doesNotContain(
                            USERNAME, CREDENTIAL, FROM_ADDRESS, RECIPIENT,
                            "127.0.0.1");
                });

        // 正式通知必须保存同一稳定分类，不能把供应商 550 文本或邮件地址写入 outbox。
        service.save(saveRequest(receiver.port(), CREDENTIAL, 0L));
        WorkflowNotificationDeliveryResult delivery =
                new MailNotificationChannel(service, failureClassifier).deliver(
                        outboxRecord("from-rejected-event", "发件人拒绝场景", "正文",
                                RECIPIENT));
        assertThat(delivery.success()).isFalse();
        assertThat(delivery.errorCode()).isEqualTo("SMTP_FROM_REJECTED");
        assertThat(delivery.summary()).isEqualTo("SMTP 服务器拒绝发件邮箱");
        assertThat(delivery.summary()).doesNotContain(
                USERNAME, CREDENTIAL, FROM_ADDRESS, RECIPIENT, "127.0.0.1", "550");
    }

    /**
     * 验证服务器接收 TCP 后不发送欢迎语时，由生产读取超时终止并返回稳定超时分类。
     *
     * @return void，读取超时未生效、分类错误或敏感信息泄露时失败
     */
    @Test
    void classifiesGreetingReadTimeoutWithoutSensitiveDetails()
    {
        LocalSmtpReceiver receiver = startReceiver(SmtpBehavior.SUPPRESS_GREETING);
        long startedNanos = System.nanoTime();

        assertThatThrownBy(() -> service.sendTest(
                testRequest(receiver.port(), CREDENTIAL, 0L)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getSubCode()).isEqualTo("SMTP_TIMEOUT");
                    assertThat(exception.getMessage()).isEqualTo(
                            "SMTP 连接或发送超时");
                    assertThat(exception.getMessage()).doesNotContain(
                            USERNAME, CREDENTIAL, "127.0.0.1");
                });
        assertThat(receiver.awaitConnection()).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - startedNanos))
                .isBetween(Duration.ofSeconds(9), Duration.ofSeconds(14));
    }

    /**
     * 启动并登记一个测试独占的真实本地 SMTP 接收器。
     *
     * @param behavior SmtpBehavior，当前测试要求的服务器行为
     * @return LocalSmtpReceiver，已经开始监听的接收器
     */
    private LocalSmtpReceiver startReceiver(SmtpBehavior behavior)
    {
        LocalSmtpReceiver receiver = new LocalSmtpReceiver(
                behavior, USERNAME, CREDENTIAL);
        receivers.add(receiver);
        return receiver;
    }

    /**
     * 执行测试 H2 schema，确保生产服务使用的 SQL 字段全部真实存在。
     *
     * @param dataSource DataSource，当前测试独占 H2 数据源
     * @return void，无返回值
     */
    private void initializeSchema(DataSource dataSource)
    {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ClassPathResource(SCHEMA_RESOURCE));
        populator.execute(dataSource);
    }

    /**
     * 创建指向真实本地接收器的未保存测试邮件请求。
     *
     * @param port int，本地接收器端口
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

    /**
     * 创建指向真实本地接收器的正式配置保存请求。
     *
     * @param port int，本地接收器端口
     * @param credential String，新授权码；空串表示更新时沿用原密文
     * @param expectedRevision long，客户端配置版本
     * @return WorkflowMailConfigRequest，完整保存请求
     */
    private WorkflowMailConfigRequest saveRequest(int port, String credential,
            long expectedRevision)
    {
        return new WorkflowMailConfigRequest("127.0.0.1", port, "NONE", USERNAME,
                credential, FROM_ADDRESS, "ApprovaPlat 审批通知", expectedRevision);
    }

    /**
     * 创建一条 worker 已领取的真实邮件 outbox 快照。
     *
     * @param idempotencyKey String，跨重试稳定幂等键
     * @param title String，通知标题
     * @param content String，通知正文
     * @param recipient String，冻结收件邮箱
     * @return WorkflowNotificationOutboxRecord，邮件通道输入快照
     */
    private WorkflowNotificationOutboxRecord outboxRecord(String idempotencyKey,
            String title, String content, String recipient)
    {
        return new WorkflowNotificationOutboxRecord(1001L, idempotencyKey,
                "TASK_CREATED", "EMAIL", 100L, recipient, "process-1", "task-1",
                title, content, null, "/workflow/task", "PENDING", 0, 0, 0, 3, 1);
    }

    /**
     * 查询正式 SMTP 单例表当前行数。
     *
     * @return int，未配置为 0，已配置为 1
     */
    private int configurationRowCount()
    {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from sys_mail_config", Integer.class);
        return count == null ? 0 : count;
    }

    /** 测试接收器的受控响应行为。 */
    enum SmtpBehavior
    {
        ACCEPT,
        REJECT_AUTHENTICATION,
        REJECT_STARTTLS_NEGOTIATION,
        REJECT_MAIL_FROM,
        SUPPRESS_GREETING
    }

    /**
     * 最小但真实的本地 SMTP 接收器：执行 TCP、EHLO、AUTH、信封和 DATA 协议。
     */
    static final class LocalSmtpReceiver implements AutoCloseable
    {
        private final SmtpBehavior behavior;
        private final String expectedUsername;
        private final String expectedCredential;
        private final ServerSocket serverSocket;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final CountDownLatch connectionAccepted = new CountDownLatch(1);
        private final BlockingQueue<ReceivedMessage> messages =
                new LinkedBlockingQueue<>();
        /** 关闭时必须主动中断全部已接入 socket，不能只关闭监听 socket。 */
        private final List<Socket> clients = new CopyOnWriteArrayList<>();
        private final ExecutorService clientExecutor;
        private final Thread acceptThread;

        /**
         * 在系统回环地址随机端口启动接收器。
         *
         * @param behavior SmtpBehavior，服务器响应模式
         * @param expectedUsername String，允许认证的账号
         * @param expectedCredential String，允许认证的授权码
         * @return void，构造完成即开始监听
         */
        LocalSmtpReceiver(SmtpBehavior behavior, String expectedUsername,
                String expectedCredential)
        {
            this.behavior = behavior;
            this.expectedUsername = expectedUsername;
            this.expectedCredential = expectedCredential;
            try
            {
                serverSocket = new ServerSocket(
                        0, 16, InetAddress.getLoopbackAddress());
            }
            catch (IOException exception)
            {
                throw new IllegalStateException("无法创建本地 SMTP 测试接收器", exception);
            }
            clientExecutor = Executors.newFixedThreadPool(2, runnable ->
            {
                Thread thread = new Thread(runnable,
                        "workflow-mail-smtp-client-" + serverSocket.getLocalPort());
                thread.setDaemon(true);
                return thread;
            });
            acceptThread = new Thread(this::acceptConnections,
                    "workflow-mail-smtp-accept-" + serverSocket.getLocalPort());
            acceptThread.setDaemon(true);
            acceptThread.start();
        }

        /**
         * 返回接收器实际监听端口。
         *
         * @return int，操作系统分配的本地端口
         */
        int port()
        {
            return serverSocket.getLocalPort();
        }

        /**
         * 等待一封已经完成 SMTP DATA 交付的邮件。
         *
         * @return ReceivedMessage，信封与原始 MIME 数据
         * @throws InterruptedException 当前测试线程被中断时抛出
         */
        ReceivedMessage awaitMessage() throws InterruptedException
        {
            ReceivedMessage message = messages.poll(5, TimeUnit.SECONDS);
            if (message == null)
            {
                throw new AssertionError("本地 SMTP 接收器在期限内未收到邮件");
            }
            return message;
        }

        /**
         * 等待生产发送器已经建立 TCP 连接。
         *
         * @return boolean，五秒内接入为 true
         */
        private boolean awaitConnection()
        {
            try
            {
                return connectionAccepted.await(5, TimeUnit.SECONDS);
            }
            catch (InterruptedException exception)
            {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        /**
         * 接受客户端连接并交给独立工作线程，监听关闭属于正常退出。
         *
         * @return void，无返回值
         */
        private void acceptConnections()
        {
            while (running.get())
            {
                try
                {
                    Socket client = serverSocket.accept();
                    clients.add(client);
                    connectionAccepted.countDown();
                    clientExecutor.execute(() -> handleClient(client));
                }
                catch (IOException exception)
                {
                    if (running.get())
                    {
                        throw new IllegalStateException(
                                "本地 SMTP 接收器接受连接失败", exception);
                    }
                }
            }
        }

        /**
         * 执行一条完整 SMTP 会话，认证或协议失败只影响当前连接。
         *
         * @param client Socket，已经接受的 SMTP 客户端连接
         * @return void，无返回值
         */
        private void handleClient(Socket client)
        {
            try (client;
                    BufferedReader reader = new BufferedReader(new InputStreamReader(
                            client.getInputStream(), StandardCharsets.US_ASCII));
                    BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                            client.getOutputStream(), StandardCharsets.US_ASCII)))
            {
                if (behavior == SmtpBehavior.SUPPRESS_GREETING)
                {
                    // 故意保持 TCP 连接但不发送 220，验证生产 mail.smtp.timeout 读取超时。
                    while (running.get() && reader.read() != -1)
                    {
                        // SMTP 客户端在欢迎语前不会发送数据；关闭 socket 会解除该阻塞。
                    }
                    return;
                }
                writeResponse(writer, "220 localhost ApprovaPlat SMTP integration test");
                String mailFrom = null;
                String recipient = null;
                String line;
                while ((line = reader.readLine()) != null)
                {
                    String command = line.toUpperCase(java.util.Locale.ROOT);
                    if (command.startsWith("EHLO") || command.startsWith("HELO"))
                    {
                        writeResponse(writer, "250-localhost");
                        if (behavior == SmtpBehavior.REJECT_STARTTLS_NEGOTIATION)
                        {
                            writeResponse(writer, "250-STARTTLS");
                        }
                        writeResponse(writer, "250-AUTH LOGIN PLAIN");
                        writeResponse(writer, "250 8BITMIME");
                    }
                    else if (command.equals("STARTTLS"))
                    {
                        writeResponse(writer, "220 2.0.0 Ready to start TLS");
                        if (behavior == SmtpBehavior.REJECT_STARTTLS_NEGOTIATION)
                        {
                            // 故意在 TLS 握手阶段返回明文 SMTP 数据，稳定触发客户端 JSSE 协议异常。
                            writeResponse(writer, "550 TLS handshake rejected");
                            return;
                        }
                    }
                    else if (command.startsWith("AUTH LOGIN"))
                    {
                        if (!authenticateLogin(line, reader, writer)) return;
                    }
                    else if (command.startsWith("AUTH PLAIN"))
                    {
                        if (!authenticatePlain(line, reader, writer)) return;
                    }
                    else if (command.startsWith("MAIL FROM:"))
                    {
                        mailFrom = line.substring("MAIL FROM:".length()).trim();
                        if (behavior == SmtpBehavior.REJECT_MAIL_FROM)
                        {
                            writeResponse(writer, "550 5.7.1 Sender address rejected");
                        }
                        else
                        {
                            writeResponse(writer, "250 2.1.0 Sender accepted");
                        }
                    }
                    else if (command.startsWith("RCPT TO:"))
                    {
                        recipient = line.substring("RCPT TO:".length()).trim();
                        writeResponse(writer, "250 2.1.5 Recipient accepted");
                    }
                    else if (command.equals("DATA"))
                    {
                        writeResponse(writer, "354 End data with <CR><LF>.<CR><LF>");
                        String rawMessage = readMessageData(reader);
                        messages.add(new ReceivedMessage(rawMessage, mailFrom, recipient));
                        writeResponse(writer, "250 2.0.0 Message accepted");
                    }
                    else if (command.equals("RSET") || command.equals("NOOP"))
                    {
                        writeResponse(writer, "250 2.0.0 OK");
                    }
                    else if (command.equals("QUIT"))
                    {
                        writeResponse(writer, "221 2.0.0 Bye");
                        return;
                    }
                    else
                    {
                        writeResponse(writer, "502 5.5.2 Command not implemented");
                    }
                }
            }
            catch (IOException ignored)
            {
                // 客户端超时或测试清理关闭连接属于预期生命周期，不污染测试输出和业务日志。
            }
            finally
            {
                clients.remove(client);
            }
        }

        /**
         * 执行 AUTH LOGIN 账号和授权码交互。
         *
         * @param firstLine String，AUTH LOGIN 首行，可带初始账号响应
         * @param reader BufferedReader，SMTP 客户端输入
         * @param writer BufferedWriter，SMTP 服务响应
         * @return boolean，认证成功且会话可继续时为 true
         * @throws IOException 网络读写失败时抛出
         */
        private boolean authenticateLogin(String firstLine, BufferedReader reader,
                BufferedWriter writer) throws IOException
        {
            String[] parts = firstLine.trim().split("\\s+", 3);
            String usernameToken;
            if (parts.length == 3)
            {
                usernameToken = parts[2];
            }
            else
            {
                writeResponse(writer, "334 VXNlcm5hbWU6");
                usernameToken = reader.readLine();
            }
            writeResponse(writer, "334 UGFzc3dvcmQ6");
            String credentialToken = reader.readLine();
            String username = decodeBase64(usernameToken);
            String credential = decodeBase64(credentialToken);
            return finishAuthentication(username, credential, writer);
        }

        /**
         * 执行 AUTH PLAIN 账号和授权码校验。
         *
         * @param firstLine String，AUTH PLAIN 首行，可带初始响应
         * @param reader BufferedReader，SMTP 客户端输入
         * @param writer BufferedWriter，SMTP 服务响应
         * @return boolean，认证成功且会话可继续时为 true
         * @throws IOException 网络读写失败时抛出
         */
        private boolean authenticatePlain(String firstLine, BufferedReader reader,
                BufferedWriter writer) throws IOException
        {
            String[] parts = firstLine.trim().split("\\s+", 3);
            String token;
            if (parts.length == 3)
            {
                token = parts[2];
            }
            else
            {
                writeResponse(writer, "334 ");
                token = reader.readLine();
            }
            String plain = decodeBase64(token);
            String[] credentials = plain.split("\\u0000", -1);
            String username = credentials.length >= 2
                    ? credentials[credentials.length - 2] : "";
            String credential = credentials.length >= 1
                    ? credentials[credentials.length - 1] : "";
            return finishAuthentication(username, credential, writer);
        }

        /**
         * 按接收器行为和预期凭据完成认证响应。
         *
         * @param username String，客户端解码账号
         * @param credential String，客户端解码授权码
         * @param writer BufferedWriter，SMTP 服务响应
         * @return boolean，认证成功为 true
         * @throws IOException 网络写失败时抛出
         */
        private boolean finishAuthentication(String username, String credential,
                BufferedWriter writer) throws IOException
        {
            if (behavior == SmtpBehavior.REJECT_AUTHENTICATION
                    || !expectedUsername.equals(username)
                    || !expectedCredential.equals(credential))
            {
                writeResponse(writer, "535 5.7.8 Authentication credentials invalid");
                return false;
            }
            writeResponse(writer, "235 2.7.0 Authentication successful");
            return true;
        }

        /**
         * 读取 SMTP DATA 段直至单独句点，并还原点转义后的 MIME 行。
         *
         * @param reader BufferedReader，SMTP 客户端输入
         * @return String，不含 SMTP 终止句点的原始 MIME 数据
         * @throws IOException 网络读取失败时抛出
         */
        private String readMessageData(BufferedReader reader) throws IOException
        {
            StringBuilder rawMessage = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null && !".".equals(line))
            {
                if (line.startsWith("..")) line = line.substring(1);
                rawMessage.append(line).append("\r\n");
            }
            return rawMessage.toString();
        }

        /**
         * 解码 SMTP AUTH 的 Base64 字段，非法输入按空字符串处理并导致认证拒绝。
         *
         * @param value String，可空 Base64 字段
         * @return String，UTF-8 解码内容或空字符串
         */
        private String decodeBase64(String value)
        {
            if (value == null) return "";
            try
            {
                return new String(Base64.getDecoder().decode(value),
                        StandardCharsets.UTF_8);
            }
            catch (IllegalArgumentException exception)
            {
                return "";
            }
        }

        /**
         * 写入一行 CRLF 结尾的 SMTP 响应并立即刷新。
         *
         * @param writer BufferedWriter，SMTP 客户端输出
         * @param response String，不含行尾的 SMTP 响应
         * @return void，无返回值
         * @throws IOException 网络写失败时抛出
         */
        private void writeResponse(BufferedWriter writer, String response)
                throws IOException
        {
            writer.write(response);
            writer.write("\r\n");
            writer.flush();
        }

        /**
         * 关闭监听与全部在途连接，等待后台线程退出，避免污染后续测试。
         *
         * @return void，无返回值
         */
        @Override
        public void close()
        {
            if (!running.compareAndSet(true, false)) return;
            try
            {
                serverSocket.close();
            }
            catch (IOException ignored)
            {
                // 重复关闭或测试失败后的关闭异常不改变测试业务结论。
            }
            for (Socket client : clients)
            {
                try
                {
                    client.close();
                }
                catch (IOException ignored)
                {
                    // 继续关闭其余连接，确保清理完整。
                }
            }
            clientExecutor.shutdownNow();
            try
            {
                acceptThread.join(2_000);
                clientExecutor.awaitTermination(2, TimeUnit.SECONDS);
            }
            catch (InterruptedException exception)
            {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 本地 SMTP 接收器冻结的信封与原始 MIME 数据。
     *
     * @param rawMessage String，不含 DATA 终止句点的原始 MIME 内容
     * @param mailFrom String，MAIL FROM 信封参数
     * @param recipient String，RCPT TO 信封参数
     */
    record ReceivedMessage(String rawMessage, String mailFrom, String recipient)
    {
        /**
         * 使用 Jakarta Mail 解析接收器捕获的 MIME 内容。
         *
         * @return MimeMessage，可按主题、收件人和自定义头断言的消息
         * @throws Exception MIME 内容损坏时抛出
         */
        MimeMessage parse() throws Exception
        {
            return new MimeMessage(Session.getInstance(new Properties()),
                    new ByteArrayInputStream(rawMessage.getBytes(
                            StandardCharsets.US_ASCII)));
        }
    }
}
