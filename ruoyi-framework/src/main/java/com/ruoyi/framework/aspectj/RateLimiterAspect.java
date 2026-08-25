package com.ruoyi.framework.aspectj;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import com.ruoyi.common.annotation.RateLimiter;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.enums.LimitType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.ip.IpUtils;

/**
 * 限流处理
 *
 * @author ruoyi
 */
@Aspect
@Component
public class RateLimiterAspect
{
    private static final Logger log = LoggerFactory.getLogger(RateLimiterAspect.class);

    private RedisTemplate<Object, Object> redisTemplate;

    private RedisScript<Long> limitScript;

    @Autowired
    public void setRedisTemplate1(RedisTemplate<Object, Object> redisTemplate)
    {
        this.redisTemplate = redisTemplate;
    }

    @Autowired
    public void setLimitScript(RedisScript<Long> limitScript)
    {
        this.limitScript = limitScript;
    }

    @Before("@annotation(rateLimiter)")
    public void doBefore(JoinPoint point, RateLimiter rateLimiter) throws Throwable
    {
        int time = rateLimiter.time();
        int count = rateLimiter.count();

        try
        {
            // USER_IP 使用两个独立桶，同时约束每用户和每来源 IP；任一超限即拒绝。
            for (String combineKey : getCombineKeys(rateLimiter, point))
            {
                List<Object> keys = Collections.singletonList(combineKey);
                Long number = redisTemplate.execute(limitScript, keys, count, time);
                if (StringUtils.isNull(number) || number.intValue() > count)
                {
                    throw new ServiceException("访问过于频繁，请稍候再试",
                            HttpStatus.TOO_MANY_REQUESTS);
                }
                log.info("限制请求'{}',当前请求'{}',缓存key'{}'", count,
                        number.intValue(), combineKey);
            }
        }
        catch (ServiceException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new RuntimeException("服务器限流异常，请稍候再试");
        }
    }

    /**
     * 按限流类型生成一个或多个独立配额键。
     *
     * @param rateLimiter RateLimiter，固定限流前缀、窗口和维度
     * @param point JoinPoint，当前受保护方法
     * @return List&lt;String&gt;，USER_IP 返回每用户键和每 IP 键，其他类型返回单键
     */
    private List<String> getCombineKeys(RateLimiter rateLimiter, JoinPoint point)
    {
        if (rateLimiter.limitType() != LimitType.USER_IP)
        {
            return List.of(getCombineKey(rateLimiter, point));
        }
        String suffix = methodSuffix(point);
        // 两个键只使用认证用户主键、可信代理解析 IP 和方法标识，绝不读取请求正文。
        return List.of(rateLimiter.key() + "user-" + SecurityUtils.getUserId() + "-" + suffix,
                rateLimiter.key() + "ip-" + IpUtils.getRateLimitIpAddr() + "-" + suffix);
    }

    /**
     * 按限流类型构造不包含请求正文的 Redis 键。
     *
     * @param rateLimiter RateLimiter，固定限流前缀、窗口和维度
     * @param point JoinPoint，当前受保护方法
     * @return String，全局或 IP 类型的稳定键；USER_IP 的兼容表示为用户与 IP 组合键
     */
    public String getCombineKey(RateLimiter rateLimiter, JoinPoint point)
    {
        StringBuffer stringBuffer = new StringBuffer(rateLimiter.key());
        if (rateLimiter.limitType() == LimitType.IP)
        {
            stringBuffer.append(IpUtils.getRateLimitIpAddr()).append("-");
        }
        if (rateLimiter.limitType() == LimitType.USER_IP)
        {
            // 用户主键和服务端解析 IP 同时参与配额，授权码、邮箱和其他请求字段不进入缓存键。
            stringBuffer.append("user-").append(SecurityUtils.getUserId())
                    .append("-ip-").append(IpUtils.getRateLimitIpAddr()).append("-");
        }
        stringBuffer.append(methodSuffix(point));
        return stringBuffer.toString();
    }

    /**
     * 生成当前受保护方法的稳定限流键后缀。
     *
     * @param point JoinPoint，当前受保护方法
     * @return String，声明类全名和方法名组成的后缀
     */
    private String methodSuffix(JoinPoint point)
    {
        MethodSignature signature = (MethodSignature) point.getSignature();
        Method method = signature.getMethod();
        Class<?> targetClass = method.getDeclaringClass();
        return targetClass.getName() + "-" + method.getName();
    }
}
