package com.ruoyi.flowable.extension;

import static org.assertj.core.api.Assertions.*;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.testsupport.WorkflowFlowableEngineTestSupport;

class WorkflowManagedConnectorSecretsBusinessTest
{
    @Test void ciphertextSurvivesRestartAndRotationUsesCasWhileRevokeIsFinal()
    {
        try (var support = WorkflowFlowableEngineTestSupport.start("managed-secret", Map.of()))
        {
            support.jdbcTemplate().execute("CREATE TABLE wf_connector_managed_secret(secret_ref VARCHAR(128) PRIMARY KEY,revision INT,encrypted_value CLOB,status VARCHAR(16),update_by VARCHAR(64),updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            byte[] key = new byte[32]; new java.security.SecureRandom().nextBytes(key);
            String configured = Base64.getEncoder().encodeToString(key);
            var secrets = new WorkflowManagedConnectorSecrets(support.dataSource(), configured);
            var created = support.transactionTemplate().execute(status -> secrets.create("test-credential-one", "100"));
            assertThat(support.jdbcTemplate().queryForObject("SELECT encrypted_value FROM wf_connector_managed_secret", String.class)).doesNotContain("test-credential-one");
            var restarted = new WorkflowManagedConnectorSecrets(support.dataSource(), configured);
            assertThat(restarted.requireSecret(created.reference())).isEqualTo("test-credential-one");
            support.transactionTemplate().executeWithoutResult(status -> restarted.rotate(created.reference(), 1, "test-credential-two", "100"));
            assertThatThrownBy(() -> secrets.rotate(created.reference(), 1, "stale", "100")).isInstanceOf(ServiceException.class);
            assertThat(secrets.requireSecret(created.reference())).isEqualTo("test-credential-two");
            var missing = new WorkflowManagedConnectorSecrets(support.dataSource(), "");
            assertThat(missing.ready()).isFalse();
            assertThatThrownBy(() -> new WorkflowConnectorSecretResolver(missing).requireSecret(created.reference())).isInstanceOf(ServiceException.class);
            byte[] wrong = new byte[32]; new java.security.SecureRandom().nextBytes(wrong);
            assertThatThrownBy(() -> new WorkflowManagedConnectorSecrets(support.dataSource(), Base64.getEncoder().encodeToString(wrong))
                    .requireSecret(created.reference())).isInstanceOf(ServiceException.class);
            // 密文或修订被替换不能解密：AEAD 绑定稳定引用和实际修订。
            support.jdbcTemplate().update("UPDATE wf_connector_managed_secret SET revision=3 WHERE secret_ref=?", created.reference());
            assertThatThrownBy(() -> secrets.requireSecret(created.reference())).isInstanceOf(ServiceException.class);
            support.jdbcTemplate().update("UPDATE wf_connector_managed_secret SET revision=2 WHERE secret_ref=?", created.reference());
            secrets.revoke(created.reference(), 2, "100");
            assertThat(secrets.metadata(created.reference()).status()).isEqualTo("REVOKED");
            assertThatThrownBy(() -> restarted.requireSecret(created.reference())).isInstanceOf(ServiceException.class);
            assertThatThrownBy(() -> secrets.rotate(created.reference(), 3, "revival", "100")).isInstanceOf(ServiceException.class);
        }
    }

    @Test void failedBindingTransactionLeavesNoDecryptableCredential()
    {
        try (var support = WorkflowFlowableEngineTestSupport.start("managed-rollback", Map.of()))
        {
            support.jdbcTemplate().execute("CREATE TABLE wf_connector_managed_secret(secret_ref VARCHAR(128) PRIMARY KEY,revision INT,encrypted_value CLOB,status VARCHAR(16),update_by VARCHAR(64),updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            var secrets = new WorkflowManagedConnectorSecrets(support.dataSource(), Base64.getEncoder().encodeToString(new byte[32]));
            assertThatThrownBy(() -> support.transactionTemplate().executeWithoutResult(status -> {
                secrets.create("test-binding", "100");
                throw new ServiceException("端点 CAS 冲突", 409);
            })).isInstanceOf(ServiceException.class);
            assertThat(support.jdbcTemplate().queryForObject("SELECT COUNT(*) FROM wf_connector_managed_secret", Integer.class)).isZero();
        }
    }
}
