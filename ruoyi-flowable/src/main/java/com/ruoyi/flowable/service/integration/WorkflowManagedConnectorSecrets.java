package com.ruoyi.flowable.service.integration;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import com.ruoyi.common.exception.ServiceException;

/** MANAGED_V1_ 显式 provider。没有环境 provider 回退；主密钥缺失时仅此 provider 不可用。 */
@Component
public class WorkflowManagedConnectorSecrets
{
    public static final String REF_PATTERN = "MANAGED_V1_[0-9a-f]{32}";
    private final JdbcTemplate jdbc;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();
    public record Metadata(String reference, int revision, String status, boolean providerReady) { }

    public WorkflowManagedConnectorSecrets(DataSource source,
            @Value("${WORKFLOW_CONNECTOR_MASTER_KEY:}") String masterKey)
    {
        jdbc = new JdbcTemplate(source);
        SecretKeySpec configured = null;
        try
        {
            byte[] decoded = Base64.getDecoder().decode(masterKey);
            if (decoded.length == 32) configured = new SecretKeySpec(decoded, "AES");
            java.util.Arrays.fill(decoded, (byte) 0);
        }
        catch (IllegalArgumentException ignored) { /* 状态接口报告服务身份尚未配置。 */ }
        key = configured;
    }

    public boolean ready() { return key != null; }

    public Metadata metadata(String reference)
    {
        if (reference == null || !reference.matches(REF_PATTERN))
            return new Metadata(reference, 0, "UNBOUND", ready());
        var rows = jdbc.query("SELECT revision,status FROM wf_connector_managed_secret WHERE secret_ref=?",
                (rs, row) -> new Metadata(reference, rs.getInt(1), rs.getString(2), ready()), reference);
        return rows.isEmpty() ? new Metadata(reference, 0, "MISSING", ready()) : rows.get(0);
    }

    /** 调用者必须在正式写事务中完成密文与端点绑定；任何失败一并回滚。 */
    public Metadata create(String secret, String actor)
    {
        String ref = "MANAGED_V1_" + UUID.randomUUID().toString().replace("-", "");
        String encrypted = encrypt(ref, 1, secret);
        jdbc.update("INSERT INTO wf_connector_managed_secret (secret_ref,revision,encrypted_value,status,update_by) VALUES (?,1,?,'ACTIVE',?)", ref, encrypted, actor);
        return new Metadata(ref, 1, "ACTIVE", true);
    }

    public Metadata rotate(String ref, int expectedRevision, String secret, String actor)
    {
        requireReference(ref);
        if (expectedRevision <= 0 || expectedRevision == Integer.MAX_VALUE) throw conflict();
        String encrypted = encrypt(ref, expectedRevision + 1, secret);
        if (jdbc.update("UPDATE wf_connector_managed_secret SET revision=revision+1,encrypted_value=?,update_by=?,updated_at=CURRENT_TIMESTAMP WHERE secret_ref=? AND revision=? AND status='ACTIVE'",
                encrypted, actor, ref, expectedRevision) != 1) throw conflict();
        return new Metadata(ref, expectedRevision + 1, "ACTIVE", true);
    }

    public void revoke(String ref, int expectedRevision, String actor)
    {
        requireReference(ref);
        if (jdbc.update("UPDATE wf_connector_managed_secret SET revision=revision+1,encrypted_value=NULL,status='REVOKED',update_by=?,updated_at=CURRENT_TIMESTAMP WHERE secret_ref=? AND revision=? AND status='ACTIVE'",
                actor, ref, expectedRevision) != 1) throw conflict();
    }

    public String requireSecret(String ref)
    {
        requireReference(ref);
        requireKey();
        var values = jdbc.query("SELECT revision,encrypted_value FROM wf_connector_managed_secret WHERE secret_ref=? AND status='ACTIVE'",
                (rs, row) -> decrypt(ref, rs.getInt(1), rs.getString(2)), ref);
        if (values.size() != 1) throw unavailable();
        return values.get(0);
    }

    private String encrypt(String ref, int revision, String secret)
    {
        requireKey();
        if (secret == null || secret.isBlank() || secret.length() > 8192 || secret.indexOf('\r') >= 0 || secret.indexOf('\n') >= 0)
            throw new ServiceException("凭据不能为空、超过8192字符或包含换行", 400);
        byte[] plaintext = secret.getBytes(StandardCharsets.UTF_8);
        try
        {
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, ref, revision, nonce);
            byte[] encrypted = cipher.doFinal(plaintext);
            return Base64.getEncoder().encodeToString(nonce) + "." + Base64.getEncoder().encodeToString(encrypted);
        }
        catch (GeneralSecurityException exception) { throw unavailable(); }
        finally { java.util.Arrays.fill(plaintext, (byte) 0); }
    }

    private String decrypt(String ref, int revision, String value)
    {
        try
        {
            String[] parts = value.split("\\.", -1);
            if (parts.length != 2) throw unavailable();
            byte[] nonce = Base64.getDecoder().decode(parts[0]);
            if (nonce.length != 12) throw unavailable();
            byte[] plaintext = cipher(Cipher.DECRYPT_MODE, ref, revision, nonce).doFinal(Base64.getDecoder().decode(parts[1]));
            try { return new String(plaintext, StandardCharsets.UTF_8); }
            finally { java.util.Arrays.fill(plaintext, (byte) 0); }
        }
        catch (GeneralSecurityException | IllegalArgumentException exception) { throw unavailable(); }
    }

    private Cipher cipher(int mode, String ref, int revision, byte[] nonce) throws GeneralSecurityException
    {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD((ref + ":" + revision).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
    private void requireReference(String ref) { if (ref == null || !ref.matches(REF_PATTERN)) throw unavailable(); }
    private void requireKey() { if (!ready()) throw new ServiceException("受管凭据存储未配置服务身份主密钥", 409).setSubCode("CONNECTOR_SECRET_PROVIDER_NOT_READY"); }
    private ServiceException unavailable() { return new ServiceException("受管凭据不可用、已撤销或无法解密", 409).setSubCode("CONNECTOR_SECRET_UNAVAILABLE"); }
    private ServiceException conflict() { return new ServiceException("凭据修订或状态已变化，请刷新后重试", 409).setSubCode("CONNECTOR_SECRET_CONFLICT"); }
}
