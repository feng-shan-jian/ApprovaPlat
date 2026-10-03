package com.ruoyi.flowable.service.notification;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;

/**
 * 使用 RuoYi Token 根密钥派生的用途子密钥执行 SMTP 授权码 AES-256-GCM 加解密。
 */
public class WorkflowMailCredentialCipher
{
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final byte[] AUTHENTICATED_CONTEXT =
            "approvaplat-mail-credential:v1".getBytes(StandardCharsets.UTF_8);

    private final SecretKey credentialKey;
    private final SecureRandom secureRandom;

    /**
     * 创建可注入派生子密钥和安全随机源的授权码加密服务，由应用装配层提供。
     *
     * 完成依赖保存
     */
    public WorkflowMailCredentialCipher(SecretKey credentialKey,
            SecureRandom secureRandom)
    {
        byte[] encodedKey = credentialKey == null ? null : credentialKey.getEncoded();
        if (encodedKey == null || encodedKey.length != 32 || secureRandom == null)
        {
            throw new IllegalArgumentException("邮件授权码加密依赖不能为空");
        }
        this.credentialKey = new SecretKeySpec(Arrays.copyOf(encodedKey, 32), "AES");
        Arrays.fill(encodedKey, (byte) 0);
        this.secureRandom = secureRandom;
    }

    /**
     * 使用当前 RuoYi Token 用途子密钥和随机 IV 加密一份 SMTP 授权码。
     *
     * @param credential String，已经过长度和控制字符校验的明文授权码
     * @return EncryptedCredential，仅包含 Base64 密文和 12 字节随机 IV
     */
    public EncryptedCredential encrypt(String credential)
    {
        if (!StringUtils.hasLength(credential))
        {
            throw invalidCredential();
        }
        byte[] iv = new byte[IV_BYTES];
        secureRandom.nextBytes(iv);
        try
        {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, credentialKey,
                    new GCMParameterSpec(TAG_BITS, iv));
            // 固定上下文作为 AAD，阻止其他用途的 AES-GCM 密文被替换到邮件配置中。
            cipher.updateAAD(AUTHENTICATED_CONTEXT);
            byte[] encrypted = cipher.doFinal(credential.getBytes(StandardCharsets.UTF_8));
            return new EncryptedCredential(Base64.getEncoder().encodeToString(encrypted), iv);
        }
        catch (GeneralSecurityException exception)
        {
            throw cryptoUnavailable();
        }
    }

    /**
     * 校验认证标签并解密数据库中的 SMTP 授权码。
     *
     * @param ciphertext String，数据库保存的 Base64 GCM 密文和 tag
     * @param iv byte[]，数据库保存的 12 字节随机 IV
     * @return String，供本次 SMTP sender 使用的明文授权码
     */
    public String decrypt(String ciphertext, byte[] iv)
    {
        if (!StringUtils.hasText(ciphertext) || iv == null || iv.length != IV_BYTES)
        {
            throw decryptionFailure();
        }
        try
        {
            byte[] encrypted = Base64.getDecoder().decode(ciphertext);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, credentialKey,
                    new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(AUTHENTICATED_CONTEXT);
            byte[] plaintext = cipher.doFinal(encrypted);
            return new String(plaintext, StandardCharsets.UTF_8);
        }
        catch (IllegalArgumentException | GeneralSecurityException exception)
        {
            throw decryptionFailure();
        }
    }

    /** @return ServiceException，HTTP 400 且不包含授权码的输入异常。 */
    private ServiceException invalidCredential()
    {
        return new ServiceException("SMTP 授权码不能为空", HttpStatus.BAD_REQUEST)
                .setSubCode("MAIL_CREDENTIAL_REQUIRED");
    }

    /** @return ServiceException，JCA 加密能力不可用的稳定异常。 */
    private ServiceException cryptoUnavailable()
    {
        return new ServiceException("邮件授权码加密服务不可用",
                HttpStatus.SERVICE_UNAVAILABLE).setSubCode("MAIL_CREDENTIAL_ENCRYPT_FAILED");
    }

    /** @return ServiceException，错误 Token 根密钥或损坏密文的统一异常。 */
    private ServiceException decryptionFailure()
    {
        return new ServiceException("SMTP 授权码解密失败，请重新保存邮件配置",
                HttpStatus.SERVICE_UNAVAILABLE).setSubCode("MAIL_CREDENTIAL_DECRYPT_FAILED");
    }

    /**
     * 加密结果在跨层传递时复制 IV，避免调用方修改已生成的认证参数。
     *
     * @param ciphertext String，Base64 编码的 GCM 密文和 tag
     * @param iv byte[]，12 字节随机 IV
     */
    public record EncryptedCredential(String ciphertext, byte[] iv)
    {
        /**
         * 复制可变 IV 并校验加密结果结构。
         */
        public EncryptedCredential
        {
            if (!StringUtils.hasText(ciphertext) || iv == null || iv.length != IV_BYTES)
            {
                throw new IllegalArgumentException("邮件授权码加密结果不完整");
            }
            iv = Arrays.copyOf(iv, iv.length);
        }

        /** @return byte[]，防御性复制的 12 字节随机 IV。 */
        @Override
        public byte[] iv()
        {
            return Arrays.copyOf(iv, iv.length);
        }
    }

}
