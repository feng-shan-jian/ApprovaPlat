package com.ruoyi.framework.web.service;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import com.ruoyi.common.constant.CacheConstants;
import com.ruoyi.common.exception.user.UserPasswordNotMatchException;
import com.ruoyi.common.exception.user.UserPasswordRetryLimitExceedException;

/**
 * 在 Spring Security 的密码认证生命周期中维护登录重试记录。
 * 
 * @author ruoyi
 */
@Component
public class SysPasswordService extends DaoAuthenticationProvider
{
    // 旧 Integer 序列化为十进制数字；INCR 保持相同键值格式。
    // 每次失败原子累加并刷新有效期，避免并发 GET/SET 丢失已完成的失败。
    private static final DefaultRedisScript<Long> RECORD_FAILURE = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1]) * 60)
            return count
            """, Long.class);

    private final RedisTemplate<Object, Object> redisTemplate;

    @Value(value = "${user.password.maxRetryCount}")
    private int maxRetryCount;

    @Value(value = "${user.password.lockTime}")
    private int lockTime;

    public SysPasswordService(UserDetailsService userDetailsService, BCryptPasswordEncoder passwordEncoder,
            RedisTemplate<Object, Object> redisTemplate)
    {
        super(userDetailsService);
        setPasswordEncoder(passwordEncoder);
        this.redisTemplate = redisTemplate;
    }

    /**
     * 登录账户密码错误次数缓存键名
     * 
     * @param username 用户名
     * @return 缓存键key
     */
    private String getCacheKey(String username)
    {
        return CacheConstants.PWD_ERR_CNT_KEY + username;
    }

    @Override
    protected void additionalAuthenticationChecks(UserDetails user, UsernamePasswordAuthenticationToken authentication)
    {
        String key = getCacheKey(authentication.getName());
        Integer retryCount = (Integer) redisTemplate.opsForValue().get(key);
        if ((retryCount == null ? 0 : retryCount) >= maxRetryCount)
        {
            var failure = new UserPasswordRetryLimitExceedException(maxRetryCount, lockTime);
            throw new InternalAuthenticationServiceException(failure.getMessage(), failure);
        }
        try
        {
            // 唯一的真实密码裁决，沿用 DaoAuthenticationProvider 与生产 BCrypt。
            super.additionalAuthenticationChecks(user, authentication);
        }
        catch (BadCredentialsException ex)
        {
            redisTemplate.execute(RECORD_FAILURE, List.of(key), lockTime);
            // 保留原装载阶段异常经 DaoAuthenticationProvider 包装后的登录错误映射。
            var failure = new UserPasswordNotMatchException();
            throw new InternalAuthenticationServiceException(failure.getMessage(), failure);
        }
    }

    @Override
    protected Authentication createSuccessAuthentication(Object principal, Authentication authentication, UserDetails user)
    {
        Authentication result = super.createSuccessAuthentication(principal, authentication, user);
        clearLoginRecordCache(authentication.getName());
        return result;
    }

    public void clearLoginRecordCache(String loginName)
    {
        redisTemplate.delete(getCacheKey(loginName));
    }
}
