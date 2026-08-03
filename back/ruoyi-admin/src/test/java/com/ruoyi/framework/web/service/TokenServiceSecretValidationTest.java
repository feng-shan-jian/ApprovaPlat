package com.ruoyi.framework.web.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.crypto.SecretKey;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.DeserializationException;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.io.Encoders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.core.exc.StreamConstraintsException;

/**
 * Token HS512 密钥启动门禁测试。
 */
class TokenServiceSecretValidationTest
{
    /** 每个测试独立的密钥目录，避免读取开发者真实用户目录。 */
    @TempDir
    Path tempDirectory;

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
     * 验证未显式配置时会首次生成密钥文件，后续服务实例稳定复用同一签名字节。
     *
     * @return void，密钥未持久化或重启后发生漂移时测试失败
     * @throws Exception 文件读取失败时抛出
     */
    @Test
    void generatesAndReusesPersistentSecretWhenExplicitSecretIsAbsent() throws Exception
    {
        Path secretPath = tempDirectory.resolve("persistent/token-secret");
        TokenService first = localFileTokenService(secretPath);
        first.validateSecret();
        byte[] firstKey = signingKeyBytes(first);

        String persisted = Files.readString(secretPath, StandardCharsets.UTF_8).trim();
        assertThat(Decoders.BASE64.decode(persisted)).hasSize(64);

        TokenService second = localFileTokenService(secretPath);
        second.validateSecret();
        assertThat(signingKeyBytes(second)).containsExactly(firstKey);
    }

    /**
     * 验证显式环境密钥优先于自动文件，避免运维指定值被本地文件静默覆盖。
     *
     * @return void，自动文件错误抢占显式密钥时测试失败
     */
    @Test
    void explicitSecretTakesPriorityWithoutCreatingSecretFile()
    {
        Path secretPath = tempDirectory.resolve("priority/token-secret");
        TokenService tokenService = new TokenService();
        ReflectionTestUtils.setField(tokenService, "secret", base64Secret("e".repeat(64)));
        ReflectionTestUtils.setField(tokenService, "secretFileEnabled", true);
        ReflectionTestUtils.setField(tokenService, "secretFilePath", secretPath.toString());

        assertThatCode(tokenService::validateSecret).doesNotThrowAnyException();
        assertThat(secretPath).doesNotExist();
    }

    /**
     * 验证关闭文件生成且未注入环境密钥时快速失败，生产环境不会降级为弱默认值。
     *
     * @return void，缺失所有安全密钥来源却仍能启动时测试失败
     */
    @Test
    void rejectsMissingSecretWhenFileGenerationIsDisabled()
    {
        TokenService tokenService = new TokenService();
        ReflectionTestUtils.setField(tokenService, "secret", "");
        ReflectionTestUtils.setField(tokenService, "secretFileEnabled", false);

        assertThatThrownBy(tokenService::validateSecret)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RUOYI_TOKEN_SECRET 未配置，且 token.secret-file.enabled 未开启");
    }

    /**
     * 验证损坏的持久化文件会被拒绝且不会被自动覆盖，避免节点悄然更换签名密钥。
     *
     * @return void，非法文件被覆盖或接受时测试失败
     * @throws Exception 测试文件无法创建时抛出
     */
    @Test
    void rejectsCorruptedPersistentSecretWithoutReplacingIt() throws Exception
    {
        Path secretPath = tempDirectory.resolve("corrupt/token-secret");
        Files.createDirectories(secretPath.getParent());
        Files.writeString(secretPath, "not@base64", StandardCharsets.UTF_8);
        TokenService tokenService = localFileTokenService(secretPath);

        assertThatThrownBy(tokenService::validateSecret)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("RUOYI_TOKEN_SECRET 必须是合法 Base64 或 Base64URL，且解码后至少包含 64 个字节");
        assertThat(Files.readString(secretPath, StandardCharsets.UTF_8))
                .isEqualTo("not@base64");
    }

    /**
     * 验证同一节点并发首次启动时只形成一个稳定密钥，避免并发进程生成不同登录签名。
     *
     * @return void，并发调用获得不同密钥或发生锁冲突时测试失败
     * @throws Exception 并发任务执行失败时抛出
     */
    @Test
    void concurrentInitializersReuseOnePersistentSecret() throws Exception
    {
        Path secretPath = tempDirectory.resolve("concurrent/token-secret");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try
        {
            Future<String> first = executor.submit(() -> loadSecretAfter(start, secretPath));
            Future<String> second = executor.submit(() -> loadSecretAfter(start, secretPath));
            start.countDown();

            assertThat(first.get()).isEqualTo(second.get());
            assertThat(Decoders.BASE64.decode(first.get())).hasSize(64);
        }
        finally
        {
            executor.shutdownNow();
        }
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
     * 创建启用持久化文件密钥、但未显式注入环境密钥的 TokenService。
     *
     * @param secretPath Path，测试专用密钥文件路径
     * @return TokenService，可执行首次生成或稳定复用验证的服务实例
     */
    private TokenService localFileTokenService(Path secretPath)
    {
        TokenService tokenService = new TokenService();
        ReflectionTestUtils.setField(tokenService, "secret", "");
        ReflectionTestUtils.setField(tokenService, "secretFileEnabled", true);
        ReflectionTestUtils.setField(tokenService, "secretFilePath", secretPath.toString());
        return tokenService;
    }

    /**
     * 读取已经完成初始化的 TokenService 实际签名字节。
     *
     * @param tokenService TokenService，已调用 validateSecret 的服务实例
     * @return byte[]，当前实例使用的不可变签名密钥字节
     */
    private byte[] signingKeyBytes(TokenService tokenService)
    {
        SecretKey signingKey = (SecretKey) ReflectionTestUtils.getField(
                tokenService, "signingKey");
        return signingKey.getEncoded();
    }

    /**
     * 等待并发起跑信号后读取或创建同一路径密钥。
     *
     * @param start CountDownLatch，确保两个并发任务同时进入初始化
     * @param secretPath Path，共享的测试密钥文件路径
     * @return String，文件存储返回的 Base64 密钥
     * @throws Exception 等待中断或文件初始化失败时抛出
     */
    private String loadSecretAfter(CountDownLatch start, Path secretPath) throws Exception
    {
        start.await();
        return new TokenSecretFileStore().loadOrCreate(secretPath.toString());
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
