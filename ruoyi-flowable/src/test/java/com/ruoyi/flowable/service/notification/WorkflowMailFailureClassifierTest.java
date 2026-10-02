package com.ruoyi.flowable.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailAuthenticationException;

class WorkflowMailFailureClassifierTest
{
    @Test
    void administrationAndDeliveryShareAllSafeSmtpSummaries()
    {
        Map<String, String> expected = Map.of(
                "SMTP_NOT_CONFIGURED", "SMTP 邮件服务尚未配置",
                "MAIL_CREDENTIAL_DECRYPT_FAILED", "SMTP 授权码无法解密",
                "SMTP_CONNECT_FAILED", "SMTP 服务器无法连接",
                "SMTP_TIMEOUT", "SMTP 连接或发送超时",
                "SMTP_AUTH_FAILED", "SMTP 认证失败",
                "SMTP_TLS_FAILED", "SMTP TLS 或 SSL 协商失败",
                "SMTP_FROM_REJECTED", "SMTP 服务器拒绝发件邮箱",
                "SMTP_DELIVERY_FAILED", "SMTP 投递失败");
        List<Map<String, Object>> rows = new ArrayList<>();
        expected.forEach((code, summary) -> {
            assertThat(WorkflowMailFailureClassifier.safeSummary(code)).isEqualTo(summary);
            rows.add(row(code, "synthetic-secret@private-host"));
        });
        rows.add(row("UNRECOGNIZED", "synthetic-secret@private-host"));
        rows.add(row(null, null));
        rows.add(row("RECIPIENT_INVALID", "synthetic-secret@private-host"));
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn((long) rows.size());
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(rows);
        var result = new WorkflowNotificationAdminService(jdbc).listOutbox(null, 1, 20).rows();
        for (int index = 0; index < expected.size(); index++)
        {
            var record = result.get(index);
            assertThat(record.get("lastErrorSummary")).isEqualTo(expected.get(record.get("lastErrorCode")));
            assertThat(record.get("canCompensate")).isEqualTo(true);
        }
        assertThat(result.get(8).get("lastErrorSummary")).isEqualTo("通知投递失败，详细信息已隐藏");
        assertThat(result.get(9).get("lastErrorSummary")).isNull();
        assertThat(result.get(10).get("lastErrorSummary")).isEqualTo("通知接收人当前不可用");
        assertThat(result.toString()).doesNotContain("synthetic-secret", "private-host");
    }

    @Test
    void userGuidanceRemainsDistinctAndCausalCyclesRemainBounded()
    {
        var cause = new RuntimeException("synthetic-secret");
        var auth = new MailAuthenticationException("private-host", cause);
        cause.addSuppressed(auth);
        var classifier = new WorkflowMailFailureClassifier();
        assertThat(classifier.testFailure(auth).getMessage()).isEqualTo("SMTP 认证失败，请检查登录账号和授权码");
        assertThat(classifier.classify(auth).outboxSummary()).isEqualTo("SMTP 认证失败");
        assertThat(WorkflowMailFailureClassifier.safeSummary(null)).isNull();
        assertThat(WorkflowMailFailureClassifier.safeSummary("UNKNOWN")).isNull();
    }

    private Map<String, Object> row(String code, String summary)
    {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("lastErrorCode", code); row.put("lastErrorSummary", summary); row.put("canCompensate", 1);
        return row;
    }
}
