package com.ruoyi.framework.web.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import javax.crypto.SecretKey;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.DeserializationException;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.io.Encoders;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.core.exc.StreamConstraintsException;

/**
 * Token HS512 密钥启动门禁测试。
 */
class TokenServiceSecretValidationTest
{
    /**
     * 验证 Base64 解码后不足 64 字节的密钥会在组件初始化阶段被明确拒绝。
     * @return void，弱密钥未触发启动异常时测试失败
     */
    @Test
    void rejectsSecretShorterThanHs512Minimum()
    {
        TokenService tokenService = new TokenService();
        ReflectionTestUtils.setField(tokenService, "secret", base64Secret("short-secret"));

        assertThatThrownBy(tokenService::validateSecret)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RUOYI_TOKEN_SECRET 必须是合法 Base64 或 Base64URL，且解码后至少包含 64 个字节");
    }

    /**
     * 验证非法 Base64 密钥会在组件初始化阶段被明确拒绝。
     * @return void，非法 Base64 被错误接受时测试失败
     */
    @Test
    void rejectsSecretThatIsNotBase64()
    {
        TokenService tokenService = new TokenService();
        ReflectionTestUtils.setField(tokenService, "secret", "not@base64");

        assertThatThrownBy(tokenService::validateSecret)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RUOYI_TOKEN_SECRET 必须是合法 Base64 或 Base64URL，且解码后至少包含 64 个字节");
    }

    /**
     * 验证 Base64 解码后达到 64 字节的密钥可以通过启动门禁。
     * @return void，合格密钥被错误拒绝时测试失败
     */
    @Test
    void acceptsSecretAtHs512Minimum()
    {
        TokenService tokenService = new TokenService();
        String secret = base64Secret("a".repeat(64));
        ReflectionTestUtils.setField(tokenService, "secret", secret);

        assertThatCode(tokenService::validateSecret).doesNotThrowAnyException();
        SecretKey signingKey = (SecretKey) ReflectionTestUtils.getField(tokenService, "signingKey");
        assertThat(signingKey.getEncoded()).containsExactly(Decoders.BASE64.decode(secret));
    }

    /**
     * 验证既有 Base64URL 基础设施密钥无需改写即可通过启动门禁并保持原始签名字节。
     *
     * @return void，URL 安全字母表密钥被拒绝或解码字节漂移时测试失败
     */
    @Test
    void acceptsExistingBase64UrlSecretWithoutRewritingConfiguration()
    {
        TokenService tokenService = new TokenService();
        byte[] secretBytes = new byte[64];
        Arrays.fill(secretBytes, (byte) 0xff);
        String secret = Encoders.BASE64URL.encode(secretBytes);
        ReflectionTestUtils.setField(tokenService, "secret", secret);

        assertThat(secret).contains("_");
        assertThatCode(tokenService::validateSecret).doesNotThrowAnyException();
        SecretKey signingKey = (SecretKey) ReflectionTestUtils.getField(tokenService, "signingKey");
        assertThat(signingKey.getEncoded()).containsExactly(secretBytes);
    }

    /**
     * 验证 Jackson 3 编码的 HS512 Token 可以回读 subject，且签名篡改会被拒绝。
     *
     * @return void，签发解析不一致或篡改 Token 能通过验证时测试失败
     */
    @Test
    void signsAndParsesJackson3TokenAndRejectsTampering()
    {
        TokenService tokenService = initializedTokenService();
        String token = ReflectionTestUtils.invokeMethod(tokenService, "createToken",
                Map.of("sub", "workflow-admin", "login_user_key", "session-1"));

        assertThatCode(() -> tokenService.getUsernameFromToken(token))
                .doesNotThrowAnyException();
        assertThat(tokenService.getUsernameFromToken(token))
                .isEqualTo("workflow-admin");

        String[] segments = token.split("\\.");
        char replacement = segments[2].charAt(0) == 'A' ? 'B' : 'A';
        String tampered = segments[0] + "." + segments[1] + "." + replacement
                + segments[2].substring(1);
        assertThatThrownBy(() -> tokenService.getUsernameFromToken(tampered))
                .isInstanceOf(JwtException.class);
    }

    /**
     * 验证解析器只接受正式 HS512 算法，即使 HS256 Token 使用同一密钥也必须拒绝。
     *
     * @return void，较低强度算法能绕过解析 allowlist 时测试失败
     */
    @Test
    void rejectsTokenSignedWithNonAllowlistedAlgorithm()
    {
        TokenService tokenService = initializedTokenService();
        SecretKey signingKey = (SecretKey) ReflectionTestUtils.getField(
                tokenService, "signingKey");
        String hs256Token = Jwts.builder()
                .subject("workflow-admin")
                .json(Jackson3JwtJsonCodec.SERIALIZER)
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> tokenService.getUsernameFromToken(hs256Token))
                .isInstanceOf(JwtException.class);
    }

    /**
     * 验证 JWT Jackson 3 解析器拒绝重复字段，避免声明覆盖歧义。
     *
     * @return void，重复 subject 能被静默接受时测试失败
     */
    @Test
    void rejectsDuplicateJwtJsonFields()
    {
        StringReader duplicateClaims = new StringReader(
                "{\"sub\":\"first\",\"sub\":\"second\"}");

        assertThatThrownBy(() -> Jackson3JwtJsonCodec.DESERIALIZER
                .deserialize(duplicateClaims))
                .isInstanceOf(DeserializationException.class)
                .hasMessageContaining("JWT JSON 解析失败");
    }

    /**
     * 验证多个合法长度字符串组成的超大 JWT 文档仍会被总长度门禁拒绝。
     *
     * @return void，超过 128 KiB 的 JWT JSON 能进入声明映射时测试失败
     */
    @Test
    void rejectsJwtJsonExceedingDocumentLengthLimit()
    {
        // 每段字符串均低于单字符串上限，确保本用例真实命中文档总长度约束。
        String segment = "x".repeat(48 * 1024);
        String oversizedClaims = "{\"first\":\"" + segment
                + "\",\"second\":\"" + segment
                + "\",\"third\":\"" + segment + "\"}";

        assertThatThrownBy(() -> Jackson3JwtJsonCodec.DESERIALIZER
                .deserialize(new StringReader(oversizedClaims)))
                .isInstanceOf(DeserializationException.class)
                .hasMessageContaining("JWT JSON 解析失败")
                .hasRootCauseInstanceOf(StreamConstraintsException.class);
    }

    /**
     * 创建已经完成 HS512 密钥门禁的 TokenService。
     *
     * @return TokenService，可直接执行私有签发链和公开解析链的测试实例
     */
    private TokenService initializedTokenService()
    {
        TokenService tokenService = new TokenService();
        ReflectionTestUtils.setField(tokenService, "secret", base64Secret("x".repeat(64)));
        tokenService.validateSecret();
        return tokenService;
    }

    /**
     * 按旧 JJWT String API 使用的标准 Base64 语义生成测试密钥配置。
     * @param rawSecret String，待编码的原始 UTF-8 密钥材料
     * @return String，可写入 RUOYI_TOKEN_SECRET 的标准 Base64 文本
     */
    private String base64Secret(String rawSecret)
    {
        return Encoders.BASE64.encode(rawSecret.getBytes(StandardCharsets.UTF_8));
    }
}
