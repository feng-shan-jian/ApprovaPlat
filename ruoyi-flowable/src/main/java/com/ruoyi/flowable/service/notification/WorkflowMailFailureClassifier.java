package com.ruoyi.flowable.service.notification;

import java.lang.reflect.Method;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.SSLException;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.stereotype.Component;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;

/**
 * 将 SMTP/Jakarta Mail 异常转换为固定错误码和脱敏摘要，禁止底层消息进入 API 或 outbox。
 */
@Component
public class WorkflowMailFailureClassifier
{
    private static final int MAX_CAUSES = 48;

    /**
     * 将一次运行时邮件投递失败转换为 outbox 可保存的稳定结果。
     *
     * @param failure Throwable，JavaMailSender 抛出的异常链
     * @return WorkflowNotificationDeliveryResult，固定错误码和不含地址、主机、账号的摘要
     */
    public WorkflowNotificationDeliveryResult deliveryFailure(Throwable failure)
    {
        Classification classification = classify(failure);
        return WorkflowNotificationDeliveryResult.failure(classification.code(),
                classification.outboxSummary(), false);
    }

    /**
     * 将测试邮件失败转换为可供前端精确提示的脱敏领域异常。
     *
     * @param failure Throwable，JavaMailSender 抛出的异常链
     * @return ServiceException，HTTP 503 和固定 SMTP 子码
     */
    public ServiceException testFailure(Throwable failure)
    {
        Classification classification = classify(failure);
        return new ServiceException(classification.userMessage(),
                HttpStatus.SERVICE_UNAVAILABLE).setSubCode(classification.code());
    }

    /**
     * 有界扫描 cause、Jakarta Mail nextException 和 Spring failedMessages，避免依赖供应商错误文本。
     *
     * @param failure Throwable，可空原始失败
     * @return Classification，优先级稳定的分类结果
     */
    Classification classify(Throwable failure)
    {
        Set<Throwable> causes = collectCauses(failure);
        for (Throwable cause : causes)
        {
            if (cause instanceof ServiceException serviceException)
            {
                if ("SMTP_NOT_CONFIGURED".equals(serviceException.getSubCode()))
                    return Classification.NOT_CONFIGURED;
                if ("MAIL_CREDENTIAL_DECRYPT_FAILED".equals(serviceException.getSubCode()))
                    return Classification.DECRYPTION;
            }
        }
        if (contains(causes, MailAuthenticationException.class)
                || contains(causes, AuthenticationFailedException.class))
        {
            return Classification.AUTHENTICATION;
        }
        if (contains(causes, SocketTimeoutException.class)
                || simpleNameContains(causes, "Timeout"))
        {
            return Classification.TIMEOUT;
        }
        if (contains(causes, SSLException.class)
                || contains(causes, CertificateException.class)
                || simpleNameContains(causes, "SSLHandshake")
                || containsJakartaMailTlsConversion(causes))
        {
            return Classification.TLS;
        }
        if (containsMailFromRejection(causes))
        {
            return Classification.FROM_REJECTED;
        }
        if (contains(causes, ConnectException.class)
                || contains(causes, NoRouteToHostException.class)
                || contains(causes, UnknownHostException.class)
                || simpleNameContains(causes, "MailConnect"))
        {
            return Classification.CONNECTION;
        }
        return Classification.DELIVERY;
    }

    /**
     * 收集邮件框架可能保存异常的全部标准位置，并以对象身份去重和限制数量。
     *
     * @param failure Throwable，可空异常
     * @return Set&lt;Throwable&gt;，有界且按发现顺序保存的异常集合
     */
    private Set<Throwable> collectCauses(Throwable failure)
    {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Throwable> pending = new ArrayDeque<>();
        if (failure != null) pending.add(failure);
        while (!pending.isEmpty() && visited.size() < MAX_CAUSES)
        {
            Throwable current = pending.removeFirst();
            if (current == null || !visited.add(current)) continue;
            if (current.getCause() != null) pending.addLast(current.getCause());
            for (Throwable suppressed : current.getSuppressed())
            {
                if (suppressed != null) pending.addLast(suppressed);
            }
            if (current instanceof MessagingException messaging
                    && messaging.getNextException() != null)
            {
                pending.addLast(messaging.getNextException());
            }
            if (current instanceof MailSendException sendException)
            {
                Map<Object, Exception> failedMessages = sendException.getFailedMessages();
                if (failedMessages != null)
                {
                    failedMessages.values().stream().filter(java.util.Objects::nonNull)
                            .forEach(pending::addLast);
                }
            }
        }
        return visited;
    }

    /**
     * 判断异常集合是否含目标类型。
     *
     * @param causes Set&lt;Throwable&gt;，已展开异常集合
     * @param type Class&lt;? extends Throwable&gt;，目标异常类型
     * @return boolean，存在目标类型时为 true
     */
    private boolean contains(Set<Throwable> causes, Class<? extends Throwable> type)
    {
        return causes.stream().anyMatch(type::isInstance);
    }

    /**
     * 按类名识别可选邮件供应商异常，避免编译期绑定 Angus 私有实现。
     *
     * @param causes Set&lt;Throwable&gt;，已展开异常集合
     * @param token String，稳定类名片段
     * @return boolean，任一异常类名包含片段时为 true
     */
    private boolean simpleNameContains(Set<Throwable> causes, String token)
    {
        return causes.stream().anyMatch(item -> item.getClass().getSimpleName().contains(token));
    }

    /**
     * 识别 Jakarta Mail 在 SocketFetcher 中对底层 TLS 握手异常使用的固定包装消息。
     *
     * 部分 JVM 在远端握手阶段断开时只保留 MessagingException 包装而丢弃 SSLException 类型；
     * 此处只匹配框架固定前缀，不读取、返回或持久化其后可能附带的服务器诊断文本。
     *
     * @param causes Set&lt;Throwable&gt;，已展开异常集合
     * @return boolean，存在 Jakarta Mail 固定 TLS 转换失败包装时为 true
     */
    private boolean containsJakartaMailTlsConversion(Set<Throwable> causes)
    {
        return causes.stream().filter(MessagingException.class::isInstance)
                .map(Throwable::getMessage).filter(java.util.Objects::nonNull)
                .anyMatch(message -> message.startsWith("Could not convert socket to TLS"));
    }

    /**
     * 从 SMTPAddressFailedException/SMTPSendFailedException 的命令字段识别 MAIL FROM 拒绝。
     *
     * @param causes Set&lt;Throwable&gt;，已展开异常集合
     * @return boolean，服务器在 MAIL FROM 阶段拒绝时为 true
     */
    private boolean containsMailFromRejection(Set<Throwable> causes)
    {
        for (Throwable cause : causes)
        {
            String simpleName = cause.getClass().getSimpleName();
            if (!"SMTPAddressFailedException".equals(simpleName)
                    && !"SMTPSendFailedException".equals(simpleName))
            {
                continue;
            }
            try
            {
                Method method = cause.getClass().getMethod("getCommand");
                Object command = method.invoke(cause);
                if (command instanceof String text
                        && text.trim().toUpperCase(java.util.Locale.ROOT).startsWith("MAIL"))
                {
                    return true;
                }
            }
            catch (ReflectiveOperationException | RuntimeException ignored)
            {
                // 供应商未公开命令时使用通用投递分类，不能读取可能包含邮箱的错误消息猜测。
            }
        }
        return false;
    }

    enum Classification
    {
        NOT_CONFIGURED("SMTP_NOT_CONFIGURED", "SMTP 邮件服务尚未配置", "SMTP 邮件服务尚未配置"),
        DECRYPTION("MAIL_CREDENTIAL_DECRYPT_FAILED", "SMTP 授权码无法解密", "SMTP 授权码解密失败"),
        CONNECTION("SMTP_CONNECT_FAILED", "SMTP 服务器无法连接", "无法连接 SMTP 服务器"),
        TIMEOUT("SMTP_TIMEOUT", "SMTP 连接或发送超时", "SMTP 连接或发送超时"),
        AUTHENTICATION("SMTP_AUTH_FAILED", "SMTP 认证失败", "SMTP 认证失败，请检查登录账号和授权码"),
        TLS("SMTP_TLS_FAILED", "SMTP TLS 或 SSL 协商失败", "SMTP TLS 或 SSL 协商失败"),
        FROM_REJECTED("SMTP_FROM_REJECTED", "SMTP 服务器拒绝发件邮箱", "发件邮箱被 SMTP 服务器拒绝"),
        DELIVERY("SMTP_DELIVERY_FAILED", "SMTP 投递失败", "SMTP 服务器拒绝发送测试邮件");

        private final String code;
        private final String outboxSummary;
        private final String userMessage;

        Classification(String code, String outboxSummary, String userMessage)
        {
            this.code = code;
            this.outboxSummary = outboxSummary;
            this.userMessage = userMessage;
        }

        String code() { return code; }
        String outboxSummary() { return outboxSummary; }
        String userMessage() { return userMessage; }
    }
}
