package com.ruoyi.flowable.service.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.stream.Stream;
import javax.net.ssl.SSLHandshakeException;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;

/**
 * 验证 SMTP 失败分类只输出稳定子码和脱敏摘要，不传播供应商异常文本。
 */
class WorkflowMailFailureClassifierTest
{
    private static final String SENSITIVE_MESSAGE =
            "LEAK-ME smtp.internal user@example.com credential-token";

    /**
     * 覆盖认证、连接、超时、TLS 和通用投递失败，并同时验证 outbox 与测试接口脱敏。
     *
     * @return void，分类码、HTTP 状态或摘要泄露底层异常消息时测试失败
     */
    @Test
    void classifiesKnownFailuresWithoutLeakingOriginalMessages()
    {
        WorkflowMailFailureClassifier classifier = new WorkflowMailFailureClassifier();

        failureCases().forEach(testCase ->
        {
            WorkflowNotificationDeliveryResult delivery =
                    classifier.deliveryFailure(testCase.failure());
            ServiceException testFailure = classifier.testFailure(testCase.failure());

            assertThat(delivery.success()).as(testCase.name()).isFalse();
            assertThat(delivery.errorCode()).as(testCase.name())
                    .isEqualTo(testCase.expectedCode());
            assertThat(delivery.summary()).as(testCase.name())
                    .doesNotContain(SENSITIVE_MESSAGE, "smtp.internal",
                            "user@example.com", "credential-token");
            assertThat(testFailure.getCode()).as(testCase.name())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(testFailure.getSubCode()).as(testCase.name())
                    .isEqualTo(testCase.expectedCode());
            assertThat(testFailure.getMessage()).as(testCase.name())
                    .doesNotContain(SENSITIVE_MESSAGE, "smtp.internal",
                            "user@example.com", "credential-token");
        });
    }

    /**
     * 构造六类带敏感原始消息的代表性异常。
     *
     * @return Stream&lt;FailureCase&gt;，认证、连接、超时、两种 TLS 和通用失败样本
     */
    private Stream<FailureCase> failureCases()
    {
        return Stream.of(
                new FailureCase("authentication",
                        new AuthenticationFailedException(SENSITIVE_MESSAGE),
                        "SMTP_AUTH_FAILED"),
                new FailureCase("connection", new RuntimeException(SENSITIVE_MESSAGE,
                        new ConnectException(SENSITIVE_MESSAGE)), "SMTP_CONNECT_FAILED"),
                new FailureCase("timeout", new SocketTimeoutException(SENSITIVE_MESSAGE),
                        "SMTP_TIMEOUT"),
                new FailureCase("tls", new SSLHandshakeException(SENSITIVE_MESSAGE),
                        "SMTP_TLS_FAILED"),
                new FailureCase("jakarta-mail-tls-wrapper", new MessagingException(
                        "Could not convert socket to TLS: " + SENSITIVE_MESSAGE,
                        new java.io.IOException(SENSITIVE_MESSAGE)), "SMTP_TLS_FAILED"),
                new FailureCase("generic", new RuntimeException(SENSITIVE_MESSAGE),
                        "SMTP_DELIVERY_FAILED"));
    }

    /**
     * 单个 SMTP 异常分类样本。
     *
     * @param name String，断言失败时用于定位的样本名
     * @param failure Throwable，带敏感消息的底层异常
     * @param expectedCode String，期望稳定分类码
     */
    private record FailureCase(String name, Throwable failure, String expectedCode)
    {
    }
}
