package com.ruoyi.framework.web.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Token HS512 密钥启动门禁测试。
 */
class TokenServiceSecretValidationTest
{
    /**
     * 验证短密钥会在组件初始化阶段被明确拒绝。
     * @return void，弱密钥未触发启动异常时测试失败
     */
    @Test
    void rejectsSecretShorterThanHs512Minimum()
    {
        TokenService tokenService = new TokenService();
        ReflectionTestUtils.setField(tokenService, "secret", "short-secret");

        assertThatThrownBy(tokenService::validateSecret)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RUOYI_TOKEN_SECRET 必须至少包含 64 个 UTF-8 字节");
    }

    /**
     * 验证达到 64 字节的密钥可以通过启动门禁。
     * @return void，合格密钥被错误拒绝时测试失败
     */
    @Test
    void acceptsSecretAtHs512Minimum()
    {
        TokenService tokenService = new TokenService();
        ReflectionTestUtils.setField(tokenService, "secret", "a".repeat(64));

        assertThatCode(tokenService::validateSecret).doesNotThrowAnyException();
    }
}
