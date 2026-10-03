package com.ruoyi.flowable.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import java.security.SecureRandom;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.testsupport.WorkflowH2SchemaMapperSupport;

class WorkflowMailConfigServiceTest
{
    private JdbcDataSource source;
    private JdbcTemplate jdbc;
    private WorkflowMailCredentialCipher cipher;
    private WorkflowMailConfigService service;

    @BeforeEach
    void setUp()
    {
        source = WorkflowH2SchemaMapperSupport.createDataSource("mail-" + UUID.randomUUID(), true, 5000);
        jdbc = spy(new JdbcTemplate(source));
        jdbc.execute("create table sys_mail_config (config_id bigint primary key, smtp_host varchar(255), "
                + "smtp_port int, encryption_mode varchar(16), username varchar(255), credential_ciphertext varchar(2048), "
                + "credential_iv varbinary(12), from_address varchar(255), sender_name varchar(255), revision bigint)");
        cipher = spy(new WorkflowMailCredentialCipher(new SecretKeySpec(new byte[32], "AES"), new SecureRandom()));
        service = new WorkflowMailConfigService(jdbc, cipher, new WorkflowMailFailureClassifier(),
                mock(WorkflowIdentityResolver.class));
    }

    @AfterEach
    void close() { WorkflowH2SchemaMapperSupport.shutdown(source); }

    @Test
    void availabilityReadsOneRevisionAndSenderChangesOnlyAfterRevisionChanges()
    {
        insert();
        clearInvocations(jdbc, cipher);
        assertThat(service.mailChannelAvailable()).isTrue();
        assertThat(mockingDetails(jdbc).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("query")
                        && call.getArgument(1) instanceof org.springframework.jdbc.core.RowMapper)).hasSize(1);
        var first = service.currentSender();
        assertThat(service.currentSender()).isSameAs(first);
        verify(cipher, times(1)).decrypt(anyString(), any());
        jdbc.update("update sys_mail_config set revision=2,smtp_host='next.example.com' where config_id=1");
        assertThat(service.mailChannelAvailable()).isTrue();
        var second = service.currentSender();
        assertThat(second).isNotSameAs(first);
        assertThat(second.revision()).isEqualTo(2);
        assertThat(((JavaMailSenderImpl) first.mailSender()).getHost()).isEqualTo("smtp.example.com");
        assertThat(((JavaMailSenderImpl) second.mailSender()).getHost()).isEqualTo("next.example.com");
    }

    @Test
    void absentConfigurationAndDamagedCipherKeepTheirSeparateContracts()
    {
        assertThat(service.mailChannelAvailable()).isFalse();
        assertThatThrownBy(service::currentSender).isInstanceOf(ServiceException.class);
        insert();
        jdbc.update("update sys_mail_config set credential_ciphertext='not-base64!' where config_id=1");
        assertThatThrownBy(service::mailChannelAvailable).isInstanceOf(ServiceException.class);
    }

    private void insert()
    {
        var encrypted = cipher.encrypt("synthetic-credential");
        jdbc.update("insert into sys_mail_config values(1,'smtp.example.com',587,'STARTTLS',"
                + "'sender@example.com',?,?,'sender@example.com','test',1)", encrypted.ciphertext(), encrypted.iv());
    }
}
