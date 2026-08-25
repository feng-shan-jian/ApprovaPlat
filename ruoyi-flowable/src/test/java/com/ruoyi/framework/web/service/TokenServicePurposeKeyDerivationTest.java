package com.ruoyi.framework.web.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Base64;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 验证 RuoYi Token 根密钥派生业务用途子密钥时的稳定性和用途隔离边界。
 */
class TokenServicePurposeKeyDerivationTest
{
    /**
     * 验证同一根密钥与用途得到稳定 AES-256 子密钥，不同用途不能得到相同结果。
     *
     * @return void，长度、算法、稳定性或用途隔离任一漂移时测试失败
     */
    @Test
    void derivesStableAndPurposeSeparatedAes256Keys()
    {
        TokenService tokenService = initializedTokenService((byte) 0x4D);

        SecretKey first = tokenService.deriveAes256Key("approvaplat-mail-credential");
        SecretKey second = tokenService.deriveAes256Key("approvaplat-mail-credential");
        SecretKey other = tokenService.deriveAes256Key("approvaplat-other-purpose");

        assertThat(first.getAlgorithm()).isEqualTo("AES");
        assertThat(first.getEncoded()).hasSize(32).isEqualTo(second.getEncoded());
        assertThat(other.getEncoded()).hasSize(32).isNotEqualTo(first.getEncoded());
    }

    /**
     * 验证用途只能是代码内固定机器标识，空值和可注入分隔符均被拒绝。
     *
     * @return void，非法用途未被拒绝时测试失败
     */
    @Test
    void rejectsInvalidPurposeLabels()
    {
        TokenService tokenService = initializedTokenService((byte) 0x2A);

        assertThatThrownBy(() -> tokenService.deriveAes256Key(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tokenService.deriveAes256Key("mail:credential"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 创建已通过现有启动门禁初始化的 RuoYi TokenService。
     *
     * @param fill byte，填充 64 字节测试根密钥的固定值
     * @return TokenService，可直接签名并派生用途子密钥的服务
     */
    private TokenService initializedTokenService(byte fill)
    {
        byte[] rootKey = new byte[64];
        Arrays.fill(rootKey, fill);
        TokenService tokenService = new TokenService();
        ReflectionTestUtils.setField(tokenService, "secret",
                Base64.getEncoder().encodeToString(rootKey));
        ReflectionTestUtils.setField(tokenService, "secretFileEnabled", false);
        tokenService.validateSecret();
        Arrays.fill(rootKey, (byte) 0);
        return tokenService;
    }
}
