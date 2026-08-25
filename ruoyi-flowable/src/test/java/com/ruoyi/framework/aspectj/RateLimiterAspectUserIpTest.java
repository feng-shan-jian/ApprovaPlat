package com.ruoyi.framework.aspectj;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import com.ruoyi.common.annotation.RateLimiter;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.enums.LimitType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.ip.IpUtils;

/**
 * 验证 USER_IP 限流同时使用独立用户桶和独立 IP 桶，并确保请求正文不参与 Redis 键构造。
 */
@ExtendWith(MockitoExtension.class)
class RateLimiterAspectUserIpTest
{
    private static final int WINDOW_SECONDS = 60;

    private static final int LIMIT_COUNT = 5;

    private static final long USER_ID = 42L;

    private static final String CLIENT_IP = "203.0.113.8";

    private static final String CREDENTIAL = "smtp-secret-must-not-enter-rate-limit-key";

    private static final String RECIPIENT = "receiver@example.com";

    @Mock
    private RedisTemplate<Object, Object> redisTemplate;

    @Mock
    private RedisScript<Long> limitScript;

    @Captor
    private ArgumentCaptor<List<Object>> keysCaptor;

    private RateLimiterAspect aspect;

    /**
     * 为每个用例创建独立切面并注入 Redis 协作者，避免测试状态在用例之间泄漏。
     *
     * @return void，无返回值
     */
    @BeforeEach
    void setUp()
    {
        aspect = new RateLimiterAspect();
        aspect.setRedisTemplate1(redisTemplate);
        aspect.setLimitScript(limitScript);
    }

    /**
     * 验证一次合法请求分别消耗用户桶和 IP 桶，且凭据、收件人等请求参数不会进入缓存键。
     *
     * @return void，无返回值
     * @throws Throwable 切面前置通知声明的异常
     */
    @Test
    void userIpConsumesTwoIndependentBucketsWithoutRequestParameters() throws Throwable
    {
        JoinPoint point = joinPoint();
        RateLimiter rateLimiter = rateLimiter();
        when(redisTemplate.execute(same(limitScript), anyList(), eq(LIMIT_COUNT), eq(WINDOW_SECONDS)))
                .thenReturn(1L, 1L);

        try (MockedStatic<SecurityUtils> securityUtils = org.mockito.Mockito.mockStatic(SecurityUtils.class);
                MockedStatic<IpUtils> ipUtils = org.mockito.Mockito.mockStatic(IpUtils.class))
        {
            securityUtils.when(SecurityUtils::getUserId).thenReturn(USER_ID);
            ipUtils.when(IpUtils::getRateLimitIpAddr).thenReturn(CLIENT_IP);

            aspect.doBefore(point, rateLimiter);
        }

        verify(redisTemplate, org.mockito.Mockito.times(2))
                .execute(same(limitScript), keysCaptor.capture(), eq(LIMIT_COUNT), eq(WINDOW_SECONDS));
        String methodSuffix = CredentialEndpoint.class.getName() + "-send";
        assertEquals(List.of("mail-test:user-" + USER_ID + "-" + methodSuffix), keysCaptor.getAllValues().get(0));
        assertEquals(List.of("mail-test:ip-" + CLIENT_IP + "-" + methodSuffix), keysCaptor.getAllValues().get(1));
        assertFalse(keysCaptor.getAllValues().stream().flatMap(List::stream).map(String::valueOf)
                .anyMatch(key -> key.contains(CREDENTIAL) || key.contains(RECIPIENT)));
        verify(point, never()).getArgs();
    }

    /**
     * 验证用户桶或 IP 桶任意一个超过限额都会返回 429；IP 桶超限前仍需先通过用户桶。
     *
     * @param exceededBucketIndex int，0 表示用户桶超限，1 表示 IP 桶超限
     * @return void，无返回值
     * @throws Throwable 切面前置通知声明的异常
     */
    @ParameterizedTest(name = "第 {0} 个 USER_IP 配额桶超限")
    @ValueSource(ints = { 0, 1 })
    void eitherUserOrIpBucketExceededReturns429(int exceededBucketIndex) throws Throwable
    {
        JoinPoint point = joinPoint();
        RateLimiter rateLimiter = rateLimiter();
        AtomicInteger invocationIndex = new AtomicInteger();
        when(redisTemplate.execute(same(limitScript), anyList(), eq(LIMIT_COUNT), eq(WINDOW_SECONDS)))
                .thenAnswer(invocation -> invocationIndex.getAndIncrement() == exceededBucketIndex
                        ? (long) LIMIT_COUNT + 1L : 1L);

        ServiceException exception;
        try (MockedStatic<SecurityUtils> securityUtils = org.mockito.Mockito.mockStatic(SecurityUtils.class);
                MockedStatic<IpUtils> ipUtils = org.mockito.Mockito.mockStatic(IpUtils.class))
        {
            securityUtils.when(SecurityUtils::getUserId).thenReturn(USER_ID);
            ipUtils.when(IpUtils::getRateLimitIpAddr).thenReturn(CLIENT_IP);

            exception = assertThrows(ServiceException.class, () -> aspect.doBefore(point, rateLimiter));
        }

        assertEquals(Integer.valueOf(HttpStatus.TOO_MANY_REQUESTS), exception.getCode());
        assertEquals("访问过于频繁，请稍候再试", exception.getMessage());
        verify(redisTemplate, org.mockito.Mockito.times(exceededBucketIndex + 1))
                .execute(same(limitScript), keysCaptor.capture(), eq(LIMIT_COUNT), eq(WINDOW_SECONDS));
        assertFalse(keysCaptor.getAllValues().stream().flatMap(List::stream).map(String::valueOf)
                .anyMatch(key -> key.contains(CREDENTIAL) || key.contains(RECIPIENT)));
        verify(point, never()).getArgs();
    }

    /**
     * 创建携带敏感请求参数的 JoinPoint；测试必须证明切面不会读取这些参数。
     *
     * @return JoinPoint，指向测试端点 send 方法的切点
     * @throws NoSuchMethodException 测试端点方法签名不存在时抛出
     */
    private JoinPoint joinPoint() throws NoSuchMethodException
    {
        Method method = CredentialEndpoint.class.getDeclaredMethod("send", String.class, String.class);
        MethodSignature signature = org.mockito.Mockito.mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);
        JoinPoint point = org.mockito.Mockito.mock(JoinPoint.class, invocation -> {
            // 敏感参数可由切点读取，但若生产切面读取 getArgs，后续 never 校验会立即失败。
            if ("getArgs".equals(invocation.getMethod().getName()))
            {
                return new Object[] { CREDENTIAL, RECIPIENT };
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        when(point.getSignature()).thenReturn(signature);
        return point;
    }

    /**
     * 读取测试端点上的真实 RateLimiter 注解，避免手工模拟注解字段而遗漏运行时契约。
     *
     * @return RateLimiter，USER_IP 测试限流配置
     * @throws NoSuchMethodException 测试端点方法签名不存在时抛出
     */
    private RateLimiter rateLimiter() throws NoSuchMethodException
    {
        return CredentialEndpoint.class.getDeclaredMethod("send", String.class, String.class)
                .getAnnotation(RateLimiter.class);
    }

    /**
     * 提供含授权码与收件人参数的受保护测试端点。
     */
    private static final class CredentialEndpoint
    {
        /**
         * 模拟 SMTP 测试请求，仅用于提供真实方法签名和限流注解。
         *
         * @param credential String，敏感 SMTP 授权码
         * @param recipient String，测试收件邮箱
         * @return void，无返回值
         */
        @RateLimiter(key = "mail-test:", time = WINDOW_SECONDS, count = LIMIT_COUNT, limitType = LimitType.USER_IP)
        void send(String credential, String recipient)
        {
            // 测试夹具方法不执行真实业务。
        }
    }
}
