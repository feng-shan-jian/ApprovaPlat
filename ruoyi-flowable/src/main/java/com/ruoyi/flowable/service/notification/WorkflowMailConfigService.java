package com.ruoyi.flowable.service.notification;

import java.net.IDN;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.dto.WorkflowMailConfigRequest;
import com.ruoyi.flowable.domain.dto.WorkflowMailTestRequest;
import com.ruoyi.flowable.domain.vo.WorkflowMailConfigView;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;

/**
 * 平台单例 SMTP 配置、测试发送和按 revision 动态邮件发送器的唯一领域服务。
 */
@Service
public class WorkflowMailConfigService
{
    private static final long CONFIG_ID = 1L;
    private static final Set<String> ENCRYPTION_MODES = Set.of("NONE", "STARTTLS", "SSL");
    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;
    private static final int READ_TIMEOUT_MILLIS = 10_000;
    private static final int WRITE_TIMEOUT_MILLIS = 10_000;
    private static final int MAX_CREDENTIAL_LENGTH = 1_024;
    private static final String CONFIG_COLUMNS =
            "config_id,smtp_host,smtp_port,encryption_mode,username," +
            "credential_ciphertext,credential_iv,from_address,sender_name,revision";

    private final JdbcTemplate jdbcTemplate;
    private final WorkflowMailCredentialCipher credentialCipher;
    private final WorkflowMailFailureClassifier failureClassifier;
    private final WorkflowIdentityResolver identityResolver;
    /** 发送器创建/读取与本节点事务提交后失效共用同一监视器，保证原子切换。 */
    private final Object senderMonitor = new Object();
    /** 当前节点最近一次完成解密并构建的不可变发送器快照。 */
    private volatile MailSenderSnapshot cachedSender;

    /**
     * 创建 SMTP 配置领域服务。
     *
     * @param jdbcTemplate JdbcTemplate，sys_mail_config 唯一正式数据源
     * @param credentialCipher WorkflowMailCredentialCipher，AES-256-GCM 授权码边界
     * @param failureClassifier WorkflowMailFailureClassifier，SMTP 异常脱敏分类器
     * @param identityResolver WorkflowIdentityResolver，配置修改人和测试人身份来源
     * @return void，构造后由 Spring 管理
     */
    public WorkflowMailConfigService(JdbcTemplate jdbcTemplate,
            WorkflowMailCredentialCipher credentialCipher,
            WorkflowMailFailureClassifier failureClassifier,
            WorkflowIdentityResolver identityResolver)
    {
        this.jdbcTemplate = jdbcTemplate;
        this.credentialCipher = credentialCipher;
        this.failureClassifier = failureClassifier;
        this.identityResolver = identityResolver;
    }

    /**
     * 查询不含任何授权码材料的 SMTP 配置视图。
     *
     * @return WorkflowMailConfigView，无配置时只表达 configured=false 和 revision=0
     */
    @Transactional(readOnly = true)
    public WorkflowMailConfigView configuration()
    {
        MailConfigRow row = findConfiguration();
        return row == null ? WorkflowMailConfigView.unconfigured() : toView(row);
    }

    /**
     * 首次插入或按 expectedRevision 条件更新平台 SMTP 单例配置。
     *
     * @param request WorkflowMailConfigRequest，弹窗完整字段和乐观锁版本
     * @return WorkflowMailConfigView，事务内写后安全配置视图
     */
    @Transactional(rollbackFor = Exception.class)
    public WorkflowMailConfigView save(WorkflowMailConfigRequest request)
    {
        ValidatedMailSettings settings = validateSettings(request == null ? null
                : new RawMailSettings(request.smtpHost(), request.smtpPort(),
                        request.encryptionMode(), request.username(), request.fromAddress(),
                        request.senderName()));
        long expectedRevision = requireRevision(request == null ? null
                : request.expectedRevision());
        MailConfigRow current = findConfiguration();
        String suppliedCredential = optionalCredential(request == null ? null
                : request.credential());
        WorkflowMailCredentialCipher.EncryptedCredential encrypted;

        if (current == null)
        {
            if (expectedRevision != 0)
            {
                throw conflict();
            }
            if (suppliedCredential == null)
            {
                throw credentialRequired();
            }
            encrypted = credentialCipher.encrypt(suppliedCredential);
            insertConfiguration(settings, encrypted, currentUserId());
        }
        else
        {
            if (expectedRevision != current.revision())
            {
                throw conflict();
            }
            if (suppliedCredential == null)
            {
                // 旧凭据只绑定原认证端点；认证身份变化时必须重新输入，且校验必须先于解密和写库。
                requireCredentialReuseAllowed(settings, current);
                // 留空保持原密文前仍执行一次真实解密，Token 根密钥失效时禁止保存出不可用配置。
                credentialCipher.decrypt(current.credentialCiphertext(),
                        current.credentialIv());
                encrypted = new WorkflowMailCredentialCipher.EncryptedCredential(
                        current.credentialCiphertext(), current.credentialIv());
            }
            else
            {
                encrypted = credentialCipher.encrypt(suppliedCredential);
            }
            updateConfiguration(settings, encrypted, expectedRevision, currentUserId());
        }
        invalidateSenderAfterCommit();
        return configuration();
    }

    /**
     * 使用未保存的弹窗参数发送真实测试邮件，绝不修改 sys_mail_config。
     *
     * @param request WorkflowMailTestRequest，当前弹窗字段、可选新授权码和测试收件人
     * @return Map&lt;String,Object&gt;，仅包含 success 和服务器完成时间
     */
    public Map<String, Object> sendTest(WorkflowMailTestRequest request)
    {
        ValidatedMailSettings settings = validateSettings(request == null ? null
                : new RawMailSettings(request.smtpHost(), request.smtpPort(),
                        request.encryptionMode(), request.username(), request.fromAddress(),
                        request.senderName()));
        String testRecipient = validateEmail(request == null ? null
                : request.testRecipient(), "测试收件邮箱格式错误");
        long expectedRevision = requireRevision(request == null ? null
                : request.expectedRevision());
        String suppliedCredential = optionalCredential(request == null ? null
                : request.credential());
        MailConfigRow current = findConfiguration();

        if (current == null && expectedRevision != 0)
        {
            throw conflict();
        }
        if (current != null && current.revision() != expectedRevision)
        {
            throw conflict();
        }
        String credential;
        if (suppliedCredential != null)
        {
            credential = suppliedCredential;
        }
        else if (current != null)
        {
            // revision 与认证身份均须匹配；失败时不得解密旧凭据或尝试连接草稿 SMTP。
            requireCredentialReuseAllowed(settings, current);
            credential = credentialCipher.decrypt(current.credentialCiphertext(),
                    current.credentialIv());
        }
        else
        {
            throw credentialRequired();
        }

        JavaMailSenderImpl sender = buildSender(settings, credential);
        try
        {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false,
                    StandardCharsets.UTF_8.name());
            helper.setFrom(settings.fromAddress(), settings.senderName());
            helper.setTo(testRecipient);
            helper.setSubject("ApprovaPlat SMTP 测试邮件");
            helper.setText("这是一封 SMTP 配置测试邮件。收到此邮件表示当前输入可以完成投递，配置尚未保存。",
                    false);
            message.setHeader("Message-ID", "<mail-test-" + UUID.randomUUID()
                    + "@approvaplat.notification>");
            sender.send(message);
        }
        catch (Exception exception)
        {
            throw failureClassifier.testFailure(exception);
        }
        return Map.of("success", true, "testedAt", LocalDateTime.now());
    }

    /**
     * 返回当前 revision 对应的动态发送器；每次获取都复核数据库版本以覆盖多节点更新。
     *
     * @return MailSenderSnapshot，发送器、From 身份和配置 revision 的不可变快照
     */
    public MailSenderSnapshot currentSender()
    {
        synchronized (senderMonitor)
        {
            MailConfigRow current = findConfiguration();
            if (current == null)
            {
                throw notConfigured();
            }
            MailSenderSnapshot cached = cachedSender;
            if (cached != null && cached.revision() == current.revision())
            {
                return cached;
            }
            String credential = credentialCipher.decrypt(current.credentialCiphertext(),
                    current.credentialIv());
            ValidatedMailSettings settings = validateSettings(new RawMailSettings(
                    current.smtpHost(), current.smtpPort(), current.encryptionMode(),
                    current.username(), current.fromAddress(), current.senderName()));
            MailSenderSnapshot replacement = new MailSenderSnapshot(
                    buildSender(settings, credential), settings.fromAddress(),
                    settings.senderName(), current.revision());
            // 只有完整解密和构建成功后才替换旧快照；失败时保持旧对象但本次调用直接失败。
            cachedSender = replacement;
            return replacement;
        }
    }

    /**
     * 判断邮件通道是否存在可解密的正式配置，供策略页面显示非敏感能力状态。
     *
     * @return boolean，无配置时为 false；损坏配置或 Token 根密钥不匹配时直接抛出脱敏异常
     */
    public boolean mailChannelAvailable()
    {
        if (findConfiguration() == null) return false;
        currentSender();
        return true;
    }

    /**
     * 要求启用邮件策略前已经存在可解密且可构建的 SMTP 正式配置。
     *
     * @return void，无配置返回明确 409，密钥或密文错误按原异常失败关闭
     */
    public void requireMailChannelAvailable()
    {
        if (!mailChannelAvailable())
        {
            throw new ServiceException("请先配置可用的 SMTP 邮件服务",
                    HttpStatus.CONFLICT).setSubCode("SMTP_NOT_CONFIGURED");
        }
    }

    /**
     * 插入 config_id=1 的首条配置，数据库唯一键负责并发首次创建仲裁。
     *
     * @param settings ValidatedMailSettings，已规范化公开 SMTP 字段
     * @param encrypted EncryptedCredential，密文和随机 IV
     * @param actor String，当前配置管理员用户主键
     * @return void，成功时 revision 固定从 1 开始
     */
    private void insertConfiguration(ValidatedMailSettings settings,
            WorkflowMailCredentialCipher.EncryptedCredential encrypted, String actor)
    {
        try
        {
            int inserted = jdbcTemplate.update("insert into sys_mail_config " +
                    "(config_id,smtp_host,smtp_port,encryption_mode,username," +
                    "credential_ciphertext,credential_iv,from_address,sender_name," +
                    "revision,create_by,create_time) " +
                    "values (1,?,?,?,?,?,?,?,?,1,?,current_timestamp(3))",
                    settings.smtpHost(), settings.smtpPort(), settings.encryptionMode(),
                    settings.username(), encrypted.ciphertext(), encrypted.iv(),
                    settings.fromAddress(), settings.senderName(), actor);
            if (inserted != 1) throw persistenceFailure();
        }
        catch (DuplicateKeyException exception)
        {
            throw conflict();
        }
        catch (DataAccessException exception)
        {
            throw persistenceFailure();
        }
    }

    /**
     * 以 config_id 和 revision 条件更新唯一配置，任何零行结果均视为并发冲突。
     *
     * @param settings ValidatedMailSettings，已规范化公开 SMTP 字段
     * @param encrypted EncryptedCredential，本次新密文或原密文快照
     * @param expectedRevision long，客户端期望版本
     * @param actor String，当前配置管理员用户主键
     * @return void，成功时数据库 revision 原子加一
     */
    private void updateConfiguration(ValidatedMailSettings settings,
            WorkflowMailCredentialCipher.EncryptedCredential encrypted,
            long expectedRevision, String actor)
    {
        try
        {
            int updated = jdbcTemplate.update("update sys_mail_config set smtp_host=?," +
                    "smtp_port=?,encryption_mode=?,username=?,credential_ciphertext=?," +
                    "credential_iv=?,from_address=?,sender_name=?," +
                    "revision=revision+1,update_by=?,update_time=current_timestamp(3) " +
                    "where config_id=1 and revision=?", settings.smtpHost(),
                    settings.smtpPort(), settings.encryptionMode(), settings.username(),
                    encrypted.ciphertext(), encrypted.iv(), settings.fromAddress(),
                    settings.senderName(), actor, expectedRevision);
            if (updated != 1) throw conflict();
        }
        catch (ServiceException exception)
        {
            throw exception;
        }
        catch (DuplicateKeyException exception)
        {
            throw conflict();
        }
        catch (DataAccessException exception)
        {
            throw persistenceFailure();
        }
    }

    /**
     * 从唯一正式表读取 config_id=1，并拒绝出现多行或损坏字段。
     *
     * @return MailConfigRow，可空正式配置快照
     */
    private MailConfigRow findConfiguration()
    {
        List<MailConfigRow> rows;
        try
        {
            rows = jdbcTemplate.query("select " + CONFIG_COLUMNS +
                    " from sys_mail_config where config_id=1",
                    (result, rowNumber) -> new MailConfigRow(
                            result.getLong("config_id"), result.getString("smtp_host"),
                            result.getInt("smtp_port"), result.getString("encryption_mode"),
                            result.getString("username"),
                            result.getString("credential_ciphertext"),
                            result.getBytes("credential_iv"),
                            result.getString("from_address"), result.getString("sender_name"),
                            result.getLong("revision")));
        }
        catch (DataAccessException exception)
        {
            throw persistenceFailure();
        }
        if (rows.size() > 1)
        {
            throw new ServiceException("SMTP 单例配置数据异常", HttpStatus.ERROR)
                    .setSubCode("MAIL_CONFIG_CORRUPTED");
        }
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 使用规范字段和明文授权码构建独立 JavaMailSenderImpl。
     *
     * @param settings ValidatedMailSettings，公开 SMTP 配置
     * @param credential String，本次已解密或弹窗提供的授权码
     * @return JavaMailSenderImpl，包含连接、读取和写入超时的发送器
     */
    private JavaMailSenderImpl buildSender(ValidatedMailSettings settings, String credential)
    {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setProtocol("smtp");
        sender.setHost(settings.smtpHost());
        sender.setPort(settings.smtpPort());
        sender.setUsername(settings.username());
        sender.setPassword(credential);
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        Properties mailProperties = sender.getJavaMailProperties();
        mailProperties.setProperty("mail.smtp.auth", "true");
        mailProperties.setProperty("mail.smtp.connectiontimeout",
                String.valueOf(CONNECT_TIMEOUT_MILLIS));
        mailProperties.setProperty("mail.smtp.timeout", String.valueOf(READ_TIMEOUT_MILLIS));
        mailProperties.setProperty("mail.smtp.writetimeout",
                String.valueOf(WRITE_TIMEOUT_MILLIS));
        mailProperties.setProperty("mail.smtp.starttls.enable",
                String.valueOf("STARTTLS".equals(settings.encryptionMode())));
        mailProperties.setProperty("mail.smtp.starttls.required",
                String.valueOf("STARTTLS".equals(settings.encryptionMode())));
        mailProperties.setProperty("mail.smtp.ssl.enable",
                String.valueOf("SSL".equals(settings.encryptionMode())));
        mailProperties.setProperty("mail.smtp.ssl.checkserveridentity",
                String.valueOf(!"NONE".equals(settings.encryptionMode())));
        mailProperties.setProperty("mail.debug", "false");
        return sender;
    }

    /**
     * 校验并规范化不含授权码的 SMTP 字段。
     *
     * @param raw RawMailSettings，可空原始配置
     * @return ValidatedMailSettings，可安全写库和构建 sender 的配置
     */
    private ValidatedMailSettings validateSettings(RawMailSettings raw)
    {
        if (raw == null) throw invalid("SMTP 配置不能为空");
        String smtpHost = validateHost(raw.smtpHost());
        if (raw.smtpPort() == null || raw.smtpPort() < 1 || raw.smtpPort() > 65_535)
        {
            throw invalid("SMTP 端口必须在 1 至 65535 范围内");
        }
        String encryptionMode = raw.encryptionMode() == null ? ""
                : raw.encryptionMode().trim().toUpperCase(Locale.ROOT);
        if (!ENCRYPTION_MODES.contains(encryptionMode))
        {
            throw invalid("SMTP 加密方式不受支持");
        }
        String username = validateEmail(raw.username(), "SMTP 登录账号格式错误");
        String fromAddress = validateEmail(raw.fromAddress(), "发件邮箱格式错误");
        String senderName = validateText(raw.senderName(), 255, "发件人名称不合法");
        return new ValidatedMailSettings(smtpHost, raw.smtpPort(), encryptionMode,
                username, fromAddress, senderName);
    }

    /**
     * 校验 SMTP 主机为不含空白、控制字符和 URL 分隔符的 ASCII/IDN 主机。
     *
     * @param value String，用户输入主机
     * @return String，规范化主机
     */
    private String validateHost(String value)
    {
        String host = validateText(value, 255, "SMTP 服务器不合法");
        if (host.chars().anyMatch(Character::isWhitespace)
                || host.indexOf('/') >= 0 || host.indexOf('@') >= 0)
        {
            throw invalid("SMTP 服务器不合法");
        }
        try
        {
            String ascii = IDN.toASCII(host);
            if (!StringUtils.hasText(ascii) || ascii.length() > 255)
                throw invalid("SMTP 服务器不合法");
            return ascii.toLowerCase(Locale.ROOT);
        }
        catch (IllegalArgumentException exception)
        {
            throw invalid("SMTP 服务器不合法");
        }
    }

    /**
     * 使用 Jakarta Mail 严格解析单一邮箱地址，并禁止显示名或地址列表。
     *
     * @param value String，待校验邮箱
     * @param message String，失败时稳定提示
     * @return String，规范化单一邮箱地址
     */
    private String validateEmail(String value, String message)
    {
        String email = validateText(value, 255, message);
        try
        {
            InternetAddress[] addresses = InternetAddress.parse(email, true);
            if (addresses.length != 1 || !email.equals(addresses[0].getAddress()))
                throw invalid(message);
            addresses[0].validate();
            return email;
        }
        catch (ServiceException exception)
        {
            throw exception;
        }
        catch (Exception exception)
        {
            throw invalid(message);
        }
    }

    /**
     * 校验普通公开文本，拒绝控制字符和超长内容。
     *
     * @param value String，待校验文本
     * @param maxLength int，最大 Java 字符长度
     * @param message String，失败时稳定提示
     * @return String，去除首尾空白的文本
     */
    private String validateText(String value, int maxLength, String message)
    {
        if (!StringUtils.hasText(value)) throw invalid(message);
        String normalized = value.trim();
        if (normalized.length() > maxLength
                || normalized.chars().anyMatch(Character::isISOControl))
        {
            throw invalid(message);
        }
        return normalized;
    }

    /**
     * 规范化可选新授权码；空白表示未提供，非空值保持字节语义不执行 trim。
     *
     * @param credential String，可空授权码
     * @return String，原始非空授权码或 null
     */
    private String optionalCredential(String credential)
    {
        if (credential == null || credential.isBlank()) return null;
        if (credential.length() > MAX_CREDENTIAL_LENGTH
                || credential.chars().anyMatch(Character::isISOControl))
        {
            throw invalid("SMTP 授权码不合法");
        }
        return credential;
    }

    /**
     * 校验 expectedRevision 为非负长整型。
     *
     * @param revision Long，可空客户端版本
     * @return long，合法版本
     */
    private long requireRevision(Long revision)
    {
        if (revision == null || revision < 0) throw invalid("SMTP 配置版本不合法");
        return revision;
    }

    /**
     * 限制旧授权码只能沿用于同一 SMTP 认证身份，防止凭据被静默转发到新端点或新账号。
     *
     * @param settings ValidatedMailSettings，本次已经规范化的弹窗 SMTP 配置
     * @param current MailConfigRow，expectedRevision 对应的正式配置快照
     * @return void，认证主机、端口、加密模式和登录账号全部不变时允许继续
     */
    private void requireCredentialReuseAllowed(ValidatedMailSettings settings,
            MailConfigRow current)
    {
        boolean sameAuthenticationIdentity = settings.smtpHost().equals(current.smtpHost())
                && settings.smtpPort() == current.smtpPort()
                && settings.encryptionMode().equals(current.encryptionMode())
                && settings.username().equals(current.username());
        if (!sameAuthenticationIdentity)
        {
            throw credentialReentryRequired();
        }
    }

    /**
     * 在当前事务成功提交后失效本节点发送器；其他节点通过每次数据库 revision 读取发现变化。
     *
     * @return void，仅清理内存引用，不执行可能失败的外部副作用
     */
    private void invalidateSenderAfterCommit()
    {
        if (TransactionSynchronizationManager.isSynchronizationActive())
        {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization()
                    {
                        /** 事务提交成功后原子失效旧发送器。 */
                        @Override
                        public void afterCommit()
                        {
                            invalidateSender();
                        }
                    });
            return;
        }
        invalidateSender();
    }

    /**
     * 在与 sender 获取相同的监视器下清理本节点缓存。
     *
     * @return void，已取得旧快照的投递线程不受影响
     */
    private void invalidateSender()
    {
        synchronized (senderMonitor)
        {
            cachedSender = null;
        }
    }

    /** @return String，当前真实登录用户主键。 */
    private String currentUserId()
    {
        try
        {
            return identityResolver.resolveCurrentIdentity().userId();
        }
        catch (RuntimeException exception)
        {
            throw new ServiceException("当前用户身份无效", HttpStatus.UNAUTHORIZED);
        }
    }

    /** @param row MailConfigRow，正式配置；@return WorkflowMailConfigView，安全白名单视图。 */
    private WorkflowMailConfigView toView(MailConfigRow row)
    {
        boolean credentialConfigured = StringUtils.hasText(row.credentialCiphertext())
                && row.credentialIv() != null && row.credentialIv().length == 12;
        return new WorkflowMailConfigView(true, row.smtpHost(), row.smtpPort(),
                row.encryptionMode(), row.username(), credentialConfigured,
                row.fromAddress(), row.senderName(), row.revision());
    }

    /** @param message String，稳定错误提示；@return ServiceException，HTTP 400。 */
    private ServiceException invalid(String message)
    {
        return new ServiceException(message, HttpStatus.BAD_REQUEST);
    }

    /** @return ServiceException，首次保存或测试缺少授权码的 HTTP 400。 */
    private ServiceException credentialRequired()
    {
        return new ServiceException("首次配置 SMTP 时必须填写授权码或密码",
                HttpStatus.BAD_REQUEST).setSubCode("MAIL_CREDENTIAL_REQUIRED");
    }

    /** @return ServiceException，认证身份变化但未提供新授权码的 HTTP 400。 */
    private ServiceException credentialReentryRequired()
    {
        return new ServiceException("SMTP 服务器、端口、加密方式或登录账号已变更，请重新填写授权码或密码",
                HttpStatus.BAD_REQUEST).setSubCode("MAIL_CREDENTIAL_REENTRY_REQUIRED");
    }

    /** @return ServiceException，配置已经由其他管理员修改的 HTTP 409。 */
    private ServiceException conflict()
    {
        return new ServiceException("SMTP 配置已被其他管理员修改，请重新加载",
                HttpStatus.CONFLICT).setSubCode("MAIL_CONFIG_REVISION_CONFLICT");
    }

    /** @return ServiceException，当前没有正式 SMTP 配置的失败关闭异常。 */
    private ServiceException notConfigured()
    {
        return new ServiceException("SMTP 邮件服务尚未配置",
                HttpStatus.SERVICE_UNAVAILABLE).setSubCode("SMTP_NOT_CONFIGURED");
    }

    /** @return ServiceException，不暴露 SQL 或字段内容的持久化异常。 */
    private ServiceException persistenceFailure()
    {
        return new ServiceException("SMTP 配置读取或保存失败", HttpStatus.ERROR)
                .setSubCode("MAIL_CONFIG_PERSISTENCE_FAILED");
    }

    /**
     * 每封邮件取得的动态发送器快照。
     *
     * @param mailSender JavaMailSender，按当前 revision 构建的发送器
     * @param fromAddress String，当前配置发件邮箱
     * @param senderName String，当前配置发件人名称
     * @param revision long，发送器对应数据库 revision
     */
    public record MailSenderSnapshot(JavaMailSender mailSender, String fromAddress,
            String senderName, long revision)
    {
    }

    private record RawMailSettings(String smtpHost, Integer smtpPort,
            String encryptionMode, String username, String fromAddress, String senderName) { }
    private record ValidatedMailSettings(String smtpHost, int smtpPort,
            String encryptionMode, String username, String fromAddress, String senderName) { }
    private record MailConfigRow(long configId, String smtpHost, int smtpPort,
            String encryptionMode, String username, String credentialCiphertext,
            byte[] credentialIv, String fromAddress, String senderName, long revision)
    {
        /** 复制 JDBC 返回的 IV，防止缓存和加密调用方共享可变数组。 */
        private MailConfigRow
        {
            credentialIv = credentialIv == null ? null
                    : Arrays.copyOf(credentialIv, credentialIv.length);
        }

        /** @return byte[]，可空且防御性复制的数据库 IV。 */
        @Override
        public byte[] credentialIv()
        {
            return credentialIv == null ? null : Arrays.copyOf(credentialIv,
                    credentialIv.length);
        }
    }
}
