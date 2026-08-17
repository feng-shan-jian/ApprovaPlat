package com.ruoyi.flowable.service.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfIntegrationCredential;
import com.ruoyi.flowable.domain.dto.WorkflowIntegrationCredentialCreateRequest;
import com.ruoyi.flowable.domain.dto.WorkflowIntegrationCredentialRotateRequest;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.mapper.WfIntegrationCredentialMapper;
import com.ruoyi.flowable.runtime.WorkflowCredentialRateLimitMetrics;
import com.ruoyi.flowable.runtime.WorkflowRedisAtomicOperations;

/**
 * 集成账号 Token 保密、生命周期、范围和 Redis 限流边界测试。
 */
class WorkflowIntegrationCredentialServiceTest
{
    private static final Instant NOW = Instant.parse("2026-08-05T00:00:00Z");

    private final WorkflowEngineOperations operations = mock(WorkflowEngineOperations.class);
    private final WfIntegrationCredentialMapper mapper = mock(WfIntegrationCredentialMapper.class);
    private final WorkflowRedisAtomicOperations redisOperations =
            mock(WorkflowRedisAtomicOperations.class);
    private final WorkflowCredentialRateLimitMetrics rateLimitMetrics =
            mock(WorkflowCredentialRateLimitMetrics.class);
    private final SecureRandom secureRandom = new SecureRandom(new byte[] {1, 2, 3, 4});
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private WorkflowIntegrationCredentialService service;

    /**
     * 让统一引擎边界在单元测试中执行回调，并固定当前管理用户。
     * @return void，初始化待测服务
     */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp()
    {
        WorkflowCurrentIdentity identity = new WorkflowCurrentIdentity("7", Set.of());
        when(operations.writeAsCurrentUser(any(Function.class))).thenAnswer(invocation ->
                ((Function<WorkflowCurrentIdentity, Object>) invocation.getArgument(0))
                        .apply(identity));
        when(operations.read(any(Supplier.class))).thenAnswer(invocation ->
                ((Supplier<Object>) invocation.getArgument(0)).get());
        service = new WorkflowIntegrationCredentialService(operations, mapper,
                redisOperations, rateLimitMetrics, secureRandom, clock);
    }

    /**
     * 验证创建只向响应返回一次明文，持久化实体仅包含前缀和 SHA-256。
     * @return void，明文进入实体或摘要不一致时失败
     */
    @Test
    void returnsPlaintextOnceWithoutPersistingIt()
    {
        when(mapper.insert(any())).thenAnswer(invocation ->
        {
            WfIntegrationCredential row = invocation.getArgument(0);
            row.setCredentialId(19L);
            return 1;
        });

        var result = service.create(new WorkflowIntegrationCredentialCreateRequest(
                "财务事件", List.of("SIGNAL", "MESSAGE"), List.of("approved", "amount"),
                60, OffsetDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC)));

        ArgumentCaptor<WfIntegrationCredential> captor =
                ArgumentCaptor.forClass(WfIntegrationCredential.class);
        verify(mapper).insert(captor.capture());
        WfIntegrationCredential stored = captor.getValue();
        assertThat(result.token()).hasSizeGreaterThanOrEqualTo(32);
        assertThat(stored.getTokenPrefix()).isEqualTo(result.token().substring(0, 12));
        assertThat(stored.getTokenHash()).isEqualTo(sha256(result.token()));
        assertThat(stored.toString()).doesNotContain(result.token());
        assertThat(stored.getScopes()).isEqualTo("MESSAGE,SIGNAL");
        assertThat(stored.getAllowedVariables()).isEqualTo("amount,approved");
    }

    /**
     * 验证同前缀错误 Token 仍执行完整摘要比较，并且绝不消耗限流次数。
     * @return void，错误 Token 被接受或写入限流状态时失败
     */
    @Test
    void rejectsWrongTokenWithSamePrefixWithoutConsumingRateLimit()
    {
        String valid = "same_prefix_" + "a".repeat(32);
        WfIntegrationCredential row = activeCredential(valid, 3);
        when(mapper.selectByPrefix(valid.substring(0, 12))).thenReturn(row);

        String invalid = valid.substring(0, 12) + "b".repeat(32);
        assertThatThrownBy(() -> service.authenticateAndConsume(invalid, "MESSAGE"))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(exception.getSubCode()).isEqualTo("INTEGRATION_TOKEN_INVALID");
                });
        verify(redisOperations, never()).incrementWithExpiry(any(), any());
        verify(mapper, never()).updateLastUsedAt(any(), any(), any(), any());
    }

    /**
     * 验证分钟窗口内最后一个名额成功，下一次请求稳定返回 429。
     * @return void，限流边界少算或多算时失败
     */
    @Test
    void enforcesLastAllowedRequestAndRateLimitBoundary()
    {
        String token = "rate_window_" + "c".repeat(32);
        WfIntegrationCredential lastAllowed = activeCredential(token, 2);
        lastAllowed.setLastUsedAt(Date.from(NOW.minus(Duration.ofMinutes(6))));
        WfIntegrationCredential limited = activeCredential(token, 2);
        when(mapper.selectByPrefix(token.substring(0, 12)))
                .thenReturn(lastAllowed, limited);
        when(redisOperations.incrementWithExpiry(
                "workflow:credential:rate:8:1:202608050000", Duration.ofMinutes(1)))
                .thenReturn(2L, 3L);

        var authenticated = service.authenticateAndConsume(token, "MESSAGE");
        assertThat(authenticated.credentialId()).isEqualTo(8L);
        assertThatThrownBy(() -> service.authenticateAndConsume(token, "MESSAGE"))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(exception.getSubCode()).isEqualTo("INTEGRATION_RATE_LIMITED");
                });
        verify(redisOperations, org.mockito.Mockito.times(2)).incrementWithExpiry(
                "workflow:credential:rate:8:1:202608050000", Duration.ofMinutes(1));
        verify(mapper).updateLastUsedAt(eq(8L), eq(1), eq(Date.from(NOW)),
                eq(Date.from(NOW.minus(Duration.ofMinutes(5)))));
        verify(rateLimitMetrics).record("allowed");
        verify(rateLimitMetrics).record("limited");
    }

    /**
     * 验证轮换原子递增修订号，并用新摘要完全替换旧摘要。
     * @return void，旧摘要保留或修订条件漂移时失败
     */
    @Test
    void rotatesTokenAndRevisionAtomically()
    {
        String oldToken = "old_token___" + "d".repeat(32);
        WfIntegrationCredential current = activeCredential(oldToken, 10);
        current.setRevisionNo(4);
        when(mapper.selectByIdForUpdate(8L)).thenReturn(current);
        when(mapper.rotate(any(), eq(4))).thenReturn(1);

        var result = service.rotate(8L, new WorkflowIntegrationCredentialRotateRequest(null));

        assertThat(result.revisionNo()).isEqualTo(5);
        assertThat(result.token()).isNotEqualTo(oldToken);
        assertThat(current.getTokenHash()).isEqualTo(sha256(result.token()));
        assertThat(current.getTokenHash()).isNotEqualTo(sha256(oldToken));
        verify(mapper).rotate(current, 4);
    }

    /**
     * 验证到期和吊销凭据统一返回 401，且都不会更新最近使用或限流窗口。
     * @return void，失效状态泄露或产生认证副作用时失败
     */
    @Test
    void rejectsExpiredAndRevokedCredentialsWithoutSideEffects()
    {
        String token = "inactive_tok" + "e".repeat(32);
        WfIntegrationCredential expired = activeCredential(token, 10);
        expired.setExpiresAt(Date.from(NOW));
        WfIntegrationCredential revoked = activeCredential(token, 10);
        revoked.setRevokedAt(Date.from(NOW.minusSeconds(1)));
        when(mapper.selectByPrefix(token.substring(0, 12)))
                .thenReturn(expired, revoked);

        assertUnauthorized(() -> service.authenticateAndConsume(token, "MESSAGE"));
        assertUnauthorized(() -> service.authenticateAndConsume(token, "MESSAGE"));
        verify(redisOperations, never()).incrementWithExpiry(any(), any());
        verify(mapper, never()).updateLastUsedAt(any(), any(), any(), any());
    }

    /**
     * 验证 Redis 故障保持安全失败并返回稳定 503，不继续更新最近使用时间。
     * @return void，Redis 故障被放行或映射为其他状态时失败
     */
    @Test
    void rejectsWhenRedisRateLimiterIsUnavailable()
    {
        String token = "redis_down__" + "f".repeat(32);
        when(mapper.selectByPrefix(token.substring(0, 12)))
                .thenReturn(activeCredential(token, 10));
        when(redisOperations.incrementWithExpiry(any(), eq(Duration.ofMinutes(1))))
                .thenThrow(new RedisConnectionFailureException("unavailable"));

        assertThatThrownBy(() -> service.authenticateAndConsume(token, "MESSAGE"))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(exception.getSubCode())
                            .isEqualTo("INTEGRATION_RATE_LIMIT_UNAVAILABLE");
                });
        verify(rateLimitMetrics).record("unavailable");
        verify(mapper, never()).updateLastUsedAt(any(), any(), any(), any());
    }

    /**
     * 验证最近使用时间在五分钟内不写库，超过窗口后才使用 revision 条件更新。
     * @return void，热点认证仍逐次写 MySQL 或丢失 revision 条件时失败
     */
    @Test
    void throttlesLastUsedAtWrites()
    {
        String token = "last_used___" + "g".repeat(32);
        WfIntegrationCredential recent = activeCredential(token, 10);
        recent.setRevisionNo(7);
        recent.setLastUsedAt(Date.from(NOW.minus(Duration.ofMinutes(4))));
        WfIntegrationCredential stale = activeCredential(token, 10);
        stale.setRevisionNo(7);
        stale.setLastUsedAt(Date.from(NOW.minus(Duration.ofMinutes(6))));
        when(mapper.selectByPrefix(token.substring(0, 12))).thenReturn(recent, stale);
        when(redisOperations.incrementWithExpiry(
                "workflow:credential:rate:8:7:202608050000", Duration.ofMinutes(1)))
                .thenReturn(1L, 2L);

        service.authenticateAndConsume(token, "MESSAGE");
        service.authenticateAndConsume(token, "MESSAGE");

        verify(mapper).updateLastUsedAt(eq(8L), eq(7), eq(Date.from(NOW)),
                eq(Date.from(NOW.minus(Duration.ofMinutes(5)))));
    }

    /**
     * 创建具备指定 Token、窗口和范围的有效凭据。
     * @param token String，测试原始 Token
     * @param limit int，每分钟上限
     * @return WfIntegrationCredential，可直接用于认证的实体
     */
    private WfIntegrationCredential activeCredential(String token, int limit)
    {
        WfIntegrationCredential row = new WfIntegrationCredential();
        row.setCredentialId(8L);
        row.setTokenPrefix(token.substring(0, 12));
        row.setTokenHash(sha256(token));
        row.setScopes("MESSAGE,RECEIVE,SIGNAL");
        row.setAllowedVariables("amount,approved");
        row.setRateLimitPerMinute(limit);
        row.setRevisionNo(1);
        row.setCreateBy("7");
        return row;
    }

    /**
     * 断言认证动作返回统一无效 Token 语义。
     * @param action Runnable，待执行认证动作
     * @return void，状态码或子码不一致时失败
     */
    private void assertUnauthorized(Runnable action)
    {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ServiceException.class,
                exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(exception.getSubCode()).isEqualTo("INTEGRATION_TOKEN_INVALID");
                });
    }

    /**
     * 计算与生产服务一致的小写 SHA-256。
     * @param value String，测试 Token 正文
     * @return String，64 位小写摘要
     */
    private String sha256(String value)
    {
        try
        {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (Exception exception)
        {
            throw new IllegalStateException(exception);
        }
    }
}
