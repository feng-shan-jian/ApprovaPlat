package com.ruoyi.flowable.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Base64;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;

/**
 * 验证 SMTP 授权码 AES-256-GCM 加密边界及失败关闭行为。
 */
class WorkflowMailCredentialCipherTest
{
    private static final String CREDENTIAL = "mail-credential-for-test";

    /**
     * 验证 32 字节用途子密钥可以完成 AES-GCM 往返，且加密结果不包含明文。
     *
     * @return void，解密结果、IV 长度或密文安全属性漂移时测试失败
     */
    @Test
    void encryptsAndDecryptsWithAes256Gcm()
    {
        WorkflowMailCredentialCipher cipher = cipher(filledKey((byte) 0x11));

        WorkflowMailCredentialCipher.EncryptedCredential encrypted =
                cipher.encrypt(CREDENTIAL);

        assertThat(encrypted.ciphertext()).doesNotContain(CREDENTIAL);
        assertThat(encrypted.iv()).hasSize(12);
        assertThat(cipher.decrypt(encrypted.ciphertext(), encrypted.iv()))
                .isEqualTo(CREDENTIAL);
    }

    /**
     * 验证同一授权码每次加密均使用独立随机 IV，不能产生可关联的相同密文。
     *
     * @return void，连续加密复用 IV 或产生相同密文时测试失败
     */
    @Test
    void usesRandomIvForEveryEncryption()
    {
        WorkflowMailCredentialCipher cipher = cipher(filledKey((byte) 0x22));

        WorkflowMailCredentialCipher.EncryptedCredential first = cipher.encrypt(CREDENTIAL);
        WorkflowMailCredentialCipher.EncryptedCredential second = cipher.encrypt(CREDENTIAL);

        assertThat(first.iv()).isNotEqualTo(second.iv());
        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
        assertThat(cipher.decrypt(first.ciphertext(), first.iv()))
                .isEqualTo(CREDENTIAL);
        assertThat(cipher.decrypt(second.ciphertext(), second.iv()))
                .isEqualTo(CREDENTIAL);
    }

    /**
     * 验证错误派生密钥和被篡改密文全部统一失败关闭，异常不泄露授权码。
     *
     * @return void，任一完整性校验被绕过或错误信息泄密时测试失败
     */
    @Test
    void rejectsWrongDerivedKeyAndDamagedCiphertext()
    {
        WorkflowMailCredentialCipher cipher = cipher(filledKey((byte) 0x33));
        WorkflowMailCredentialCipher.EncryptedCredential encrypted =
                cipher.encrypt(CREDENTIAL);
        WorkflowMailCredentialCipher wrongKeyCipher =
                cipher(filledKey((byte) 0x44));

        assertDecryptionFailure(() -> wrongKeyCipher.decrypt(encrypted.ciphertext(),
                encrypted.iv()));

        byte[] damagedBytes = Base64.getDecoder().decode(encrypted.ciphertext());
        damagedBytes[0] ^= 0x01;
        String damagedCiphertext = Base64.getEncoder().encodeToString(damagedBytes);
        assertDecryptionFailure(() -> cipher.decrypt(damagedCiphertext,
                encrypted.iv()));
    }

    /**
     * 构造使用指定用途子密钥的生产加密器。
     *
     * @param key byte[]，从 RuoYi Token 根密钥派生的固定 32 字节测试子密钥
     * @return WorkflowMailCredentialCipher，使用真实 JCA AES-GCM 的生产实现
     */
    private WorkflowMailCredentialCipher cipher(byte[] key)
    {
        return new WorkflowMailCredentialCipher(new SecretKeySpec(key, "AES"),
                new java.security.SecureRandom());
    }

    /**
     * 创建内容稳定但长度真实为 256 bit 的测试用途子密钥。
     *
     * @param value byte，填充用途子密钥的单字节值
     * @return byte[]，长度为 32 的独立字节数组
     */
    private byte[] filledKey(byte value)
    {
        byte[] key = new byte[32];
        Arrays.fill(key, value);
        return key;
    }

    /**
     * 断言解密失败采用统一 503 子码，且用户可见错误不含明文授权码。
     *
     * @param operation Runnable，预期解密失败的调用
     * @return void，错误状态、子码或脱敏属性不符合契约时测试失败
     */
    private void assertDecryptionFailure(Runnable operation)
    {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(exception.getSubCode())
                            .isEqualTo("MAIL_CREDENTIAL_DECRYPT_FAILED");
                    assertThat(exception.getMessage()).doesNotContain(CREDENTIAL);
                });
    }
}
