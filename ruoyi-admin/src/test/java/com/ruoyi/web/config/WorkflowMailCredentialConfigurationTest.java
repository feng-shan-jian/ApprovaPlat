package com.ruoyi.web.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.service.notification.WorkflowMailCredentialCipher;
import com.ruoyi.framework.web.service.TokenService;
import com.ruoyi.framework.web.service.PermissionService;
import com.ruoyi.system.service.ISysMenuService;
import com.ruoyi.system.service.SysPermissionEvaluator;

class WorkflowMailCredentialConfigurationTest
{
    private static final String PLAINTEXT = "synthetic-smtp-credential";
    // 固定合成根密钥 00..3f；旧 TokenService + Cipher 协议向量，不使用部署秘密。
    private static final String OLD_CIPHERTEXT = "7LEkz86oh1PBJ7QfDmZwKkLAj0DdPuUK/kRoLoCqjYPJhj+JbwlOR1s=";
    private static final byte[] IV = HexFormat.of().parseHex("000102030405060708090a0b");
    private static final byte[] DERIVED_KEY = HexFormat.of().parseHex(
            "09d152bc823762b9be119398eb6cfbd8917779f32d131b37ed113526d2098268");

    @Test
    void oldCiphertextDecryptsWithUnchangedPurposeDerivedKey()
    {
        TokenService tokens = tokens();
        assertThat(tokens.deriveAes256Key("approvaplat-mail-credential").getEncoded()).isEqualTo(DERIVED_KEY);
        var cipher = cipher(tokens);
        assertThat(cipher.decrypt(OLD_CIPHERTEXT, IV)).isEqualTo(PLAINTEXT);
    }

    @Test
    void newOutputRemainsReadableByFrozenAesGcmProtocol() throws Exception
    {
        var encrypted = cipher(tokens()).encrypt(PLAINTEXT);
        assertThat(encrypted.iv()).hasSize(12);
        Cipher oldReader = Cipher.getInstance("AES/GCM/NoPadding");
        oldReader.init(Cipher.DECRYPT_MODE, new SecretKeySpec(DERIVED_KEY, "AES"),
                new GCMParameterSpec(128, encrypted.iv()));
        oldReader.updateAAD("approvaplat-mail-credential:v1".getBytes(StandardCharsets.UTF_8));
        assertThat(new String(oldReader.doFinal(Base64.getDecoder().decode(encrypted.ciphertext())),
                StandardCharsets.UTF_8)).isEqualTo(PLAINTEXT);
        byte[] firstIv = encrypted.iv();
        firstIv[0] ^= 1;
        assertThat(encrypted.iv()).isNotEqualTo(firstIv);
    }

    @Test
    void wrongKeyAadIvAndTagRetainStableDecryptionFailure() throws Exception
    {
        var cipher = cipher(tokens());
        byte[] changedIv = IV.clone();
        changedIv[0] ^= 1;
        rejected(cipher, OLD_CIPHERTEXT, changedIv);
        rejected(cipher, OLD_CIPHERTEXT, new byte[11]);
        byte[] changedTag = Base64.getDecoder().decode(OLD_CIPHERTEXT);
        changedTag[changedTag.length - 1] ^= 1;
        rejected(cipher, Base64.getEncoder().encodeToString(changedTag), IV);
        rejected(cipher, "not base64!", IV);
        Cipher foreign = Cipher.getInstance("AES/GCM/NoPadding");
        foreign.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(DERIVED_KEY, "AES"), new GCMParameterSpec(128, IV));
        foreign.updateAAD("another-purpose".getBytes(StandardCharsets.UTF_8));
        rejected(cipher, Base64.getEncoder().encodeToString(foreign.doFinal(PLAINTEXT.getBytes(StandardCharsets.UTF_8))), IV);
        TokenService differentRoot = tokens();
        ReflectionTestUtils.setField(differentRoot, "secret", Base64.getEncoder().encodeToString(new byte[64]));
        differentRoot.validateSecret();
        rejected(cipher(differentRoot), OLD_CIPHERTEXT, IV);
    }

    private WorkflowMailCredentialCipher cipher(TokenService tokens)
    {
        return new WorkflowMailCredentialConfiguration().workflowMailCredentialCipher(tokens);
    }

    @Test
    void applicationConfigurationRegistersOneCipherAndRetainsSsName()
    {
        try (var context = new AnnotationConfigApplicationContext())
        {
            context.getBeanFactory().registerSingleton("tokenService", tokens());
            context.registerBean(ISysMenuService.class, () -> org.mockito.Mockito.mock(ISysMenuService.class));
            context.register(WorkflowMailCredentialConfiguration.class, SysPermissionEvaluator.class, PermissionService.class);
            context.refresh();
            assertThat(context.getBeansOfType(WorkflowMailCredentialCipher.class)).hasSize(1)
                    .containsKey("workflowMailCredentialCipher");
            assertThat(context.getBean("ss")).isInstanceOf(PermissionService.class);
            assertThat(context.getBeansOfType(SecretKey.class)).isEmpty();
            assertThat(context.getBean(WorkflowMailCredentialCipher.class).decrypt(OLD_CIPHERTEXT, IV))
                    .isEqualTo(PLAINTEXT);
        }
        var scanner = new ClassPathScanningCandidateComponentProvider(true);
        assertThat(scanner.findCandidateComponents("com.ruoyi.flowable.service.notification"))
                .noneMatch(definition -> WorkflowMailCredentialCipher.class.getName().equals(definition.getBeanClassName()));
    }

    private void rejected(WorkflowMailCredentialCipher cipher, String ciphertext, byte[] iv)
    {
        assertThatThrownBy(() -> cipher.decrypt(ciphertext, iv))
                .isInstanceOfSatisfying(ServiceException.class, failure -> {
                    assertThat(failure.getCode()).isEqualTo(503);
                    assertThat(failure.getSubCode()).isEqualTo("MAIL_CREDENTIAL_DECRYPT_FAILED");
                });
    }

    private TokenService tokens()
    {
        byte[] root = new byte[64];
        for (int i = 0; i < root.length; i++) root[i] = (byte) i;
        var tokens = new TokenService();
        ReflectionTestUtils.setField(tokens, "secret", Base64.getEncoder().encodeToString(root));
        tokens.validateSecret();
        return tokens;
    }
}
