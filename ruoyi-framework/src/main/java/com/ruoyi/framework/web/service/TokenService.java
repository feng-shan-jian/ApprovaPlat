package com.ruoyi.framework.web.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.ruoyi.common.constant.CacheConstants;
import com.ruoyi.common.constant.Constants;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.utils.ServletUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.http.UserAgentUtils;
import com.ruoyi.common.utils.ip.AddressUtils;
import com.ruoyi.common.utils.ip.IpUtils;
import com.ruoyi.common.utils.uuid.IdUtils;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Deserializer;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.io.DecodingException;
import io.jsonwebtoken.io.Serializer;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;

/**
 * token验证处理
 * 
 * @author ruoyi
 */
@Component
public class TokenService
{
    private static final Logger log = LoggerFactory.getLogger(TokenService.class);

    /** HS512 Token 密钥允许的最小解码字节数。 */
    private static final int MIN_TOKEN_SECRET_BYTES = 64;

    /** 从 RuoYi Token 根密钥派生业务子密钥时使用的固定协议前缀。 */
    private static final String KEY_DERIVATION_PREFIX = "ruoyi-purpose-key:v1:";

    /** JJWT 头和声明固定使用的 Jackson 3 流式编码器。 */
    private static final Serializer<Map<String, ?>> JWT_JSON_SERIALIZER =
            Jackson3JwtJsonCodec.SERIALIZER;

    /** JJWT 头和声明固定使用的 Jackson 3 流式解码器。 */
    private static final Deserializer<Map<String, ?>> JWT_JSON_DESERIALIZER =
            Jackson3JwtJsonCodec.DESERIALIZER;

    // 令牌自定义标识
    @Value("${token.header}")
    private String header;

    // 令牌秘钥
    @Value("${token.secret}")
    private String secret;

    /** 未显式注入密钥时，是否允许从持久化文件读取或首次生成。 */
    @Value("${token.secret-file.enabled:false}")
    private boolean secretFileEnabled;

    /** 自动生成密钥的持久化文件路径，必须位于运行账户可写的私有目录。 */
    @Value("${token.secret-file.path:}")
    private String secretFilePath;

    /** 由受控 Base64 密钥材料生成的 HS512 对称签名密钥。 */
    private SecretKey signingKey;

    // 令牌有效期（默认30分钟）
    @Value("${token.expireTime}")
    private int expireTime;

    protected static final long MILLIS_SECOND = 1000;

    protected static final long MILLIS_MINUTE = 60 * MILLIS_SECOND;

    private static final Long MILLIS_MINUTE_TWENTY = 20 * 60 * 1000L;

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private SysPermissionService permissionService;

    /**
     * 在应用对外提供登录能力前解析显式配置或持久化密钥文件，并初始化 HS512 密钥。
     *
     * @return void，无返回值；密钥来源不可用、编码非法或解码后不足 64 字节时中止应用启动
     */
    @PostConstruct
    public void validateSecret()
    {
        // 运维显式注入的密钥始终优先；空值才允许进入自动持久化分支。
        String effectiveSecret = resolveEffectiveSecret();
        byte[] secretBytes = decodeConfiguredSecret(effectiveSecret);
        if (secretBytes.length < MIN_TOKEN_SECRET_BYTES)
        {
            throw new IllegalStateException(
                    "RUOYI_TOKEN_SECRET 必须是合法 Base64 或 Base64URL，且解码后至少包含 64 个字节");
        }
        this.signingKey = Keys.hmacShaKeyFor(secretBytes);
    }

    /**
     * 解析当前进程唯一有效的 Token 密钥来源。
     *
     * @return String，显式配置或持久化文件中的 Base64/Base64URL 密钥
     */
    private String resolveEffectiveSecret()
    {
        String configuredSecret = secret == null ? "" : secret.trim();
        if (StringUtils.isNotEmpty(configuredSecret))
        {
            return configuredSecret;
        }
        if (!secretFileEnabled)
        {
            throw new IllegalStateException(
                    "RUOYI_TOKEN_SECRET 未配置，且 token.secret-file.enabled 未开启");
        }
        // 文件存储负责跨进程互斥、原子写入和权限收紧，确保首次启动后稳定复用。
        return new TokenSecretFileStore().loadOrCreate(secretFilePath);
    }

    /**
     * 解码既有标准 Base64 或 Base64URL Token 密钥配置，不对基础设施密钥做隐式重写。
     *
     * @param configuredSecret String，部署环境提供的编码密钥材料
     * @return byte[]，用于 HS512 签名和验签的原始密钥字节
     */
    private byte[] decodeConfiguredSecret(String configuredSecret)
    {
        String normalizedSecret = configuredSecret == null ? "" : configuredSecret.trim();
        if (normalizedSecret.indexOf('-') >= 0 || normalizedSecret.indexOf('_') >= 0)
        {
            try
            {
                // 标准解码器会宽松吞掉 URL 字母，必须先按显式特征选择正确字母表。
                return Decoders.BASE64URL.decode(normalizedSecret);
            }
            catch (DecodingException exception)
            {
                throw invalidEncodedSecret(exception);
            }
        }
        try
        {
            // 优先保持 JJWT 0.9.1 String API 的标准 Base64 语义。
            return Decoders.BASE64.decode(normalizedSecret);
        }
        catch (DecodingException standardException)
        {
            try
            {
                // 已有验收及部署材料可能使用 URL 安全字母表，兼容读取但不修改原配置。
                return Decoders.BASE64URL.decode(normalizedSecret);
            }
            catch (DecodingException urlException)
            {
                urlException.addSuppressed(standardException);
                throw invalidEncodedSecret(urlException);
            }
        }
    }

    /**
     * 为无法按两种受支持字母表解码的密钥创建稳定启动异常。
     *
     * @param cause DecodingException，底层编码解码失败原因
     * @return IllegalStateException，可直接中止 Spring 组件初始化的稳定异常
     */
    private IllegalStateException invalidEncodedSecret(DecodingException cause)
    {
        return new IllegalStateException(
                "RUOYI_TOKEN_SECRET 必须是合法 Base64 或 Base64URL，且解码后至少包含 64 个字节",
                cause);
    }

    /**
     * 获取用户身份信息
     * 
     * @return 用户信息
     */
    public LoginUser getLoginUser(HttpServletRequest request)
    {
        // 获取请求携带的令牌
        String token = getToken(request);
        if (StringUtils.isNotEmpty(token))
        {
            try
            {
                Claims claims = parseToken(token);
                // 解析对应的权限以及用户信息
                String uuid = (String) claims.get(Constants.LOGIN_USER_KEY);
                String userKey = getTokenKey(uuid);
                LoginUser user = redisCache.getCacheObject(userKey);
                return user;
            }
            catch (Exception e)
            {
                log.error("获取用户信息异常'{}'", e.getMessage());
            }
        }
        return null;
    }

    /**
     * 设置用户身份信息
     */
    public void setLoginUser(LoginUser loginUser)
    {
        if (StringUtils.isNotNull(loginUser) && StringUtils.isNotEmpty(loginUser.getToken()))
        {
            refreshIdentity(loginUser.getToken());
        }
    }

    /**
     * 删除用户身份信息
     */
    public void delLoginUser(String token)
    {
        if (StringUtils.isNotEmpty(token))
        {
            String userKey = getTokenKey(token);
            redisCache.deleteObject(userKey);
        }
    }

    /**
     * 创建令牌
     * 
     * @param loginUser 用户信息
     * @return 令牌
     */
    public String createToken(LoginUser loginUser)
    {
        String token = IdUtils.fastUUID();
        loginUser.setToken(token);
        setUserAgent(loginUser);
        renewTimes(loginUser);
        // 先注册不可猜测的会话标识，再读取身份；并发授权刷新才能看见这个新会话。
        redisCache.setCacheObject(getTokenKey(token), loginUser, expireTime, TimeUnit.MINUTES);
        try
        {
            LoginUser current = refreshIdentity(token);
            if (current == null)
            {
                throw new ServiceException("用户会话已失效，请重新登录", HttpStatus.UNAUTHORIZED);
            }
            loginUser = current;
        }
        catch (RuntimeException exception)
        {
            delLoginUser(token);
            throw exception;
        }

        Map<String, Object> claims = new HashMap<>();
        claims.put(Constants.LOGIN_USER_KEY, token);
        claims.put(Constants.JWT_USERNAME, loginUser.getUsername());
        return createToken(claims);
    }

    /**
     * 验证令牌有效期，相差不足20分钟，自动刷新缓存
     * 
     * @param loginUser 登录信息
     * @return 令牌
     */
    public void verifyToken(LoginUser loginUser)
    {
        long expireTime = loginUser.getExpireTime();
        long currentTime = System.currentTimeMillis();
        if (expireTime - currentTime <= MILLIS_MINUTE_TWENTY)
        {
            refreshToken(loginUser);
        }
    }

    /**
     * 刷新令牌有效期
     * 
     * @param loginUser 登录信息
     */
    public void refreshToken(LoginUser loginUser)
    {
        String userKey = getTokenKey(loginUser.getToken());
        for (int attempt = 0; attempt < 4; attempt++)
        {
            byte[] expected = redisCache.getCacheObjectBytes(userKey);
            LoginUser current = redisCache.deserializeCacheObject(expected);
            if (current == null)
            {
                return;
            }
            // 续期只延长最新缓存的时间，绝不把旧请求携带的身份写回。
            renewTimes(current);
            if (redisCache.compareAndSetCacheObject(userKey, expected, current, expireTime * MILLIS_MINUTE))
            {
                return;
            }
        }
        // 高频竞争时不改变当前身份；下一请求仍可继续尝试续期。
        log.warn("会话续期遇到连续并发更新，本次未延长有效期");
    }

    private void renewTimes(LoginUser loginUser)
    {
        loginUser.setLoginTime(System.currentTimeMillis());
        loginUser.setExpireTime(loginUser.getLoginTime() + expireTime * MILLIS_MINUTE);
    }

    /** 当前会话与批量授权刷新共用事实与条件写边界；返回 null 表示已失效。 */
    public LoginUser refreshIdentity(String token)
    {
        String key = getTokenKey(token);
        for (int attempt = 0; attempt < 4; attempt++)
        {
            byte[] expected = redisCache.getCacheObjectBytes(key);
            LoginUser current = redisCache.deserializeCacheObject(expected);
            if (current == null)
            {
                return null;
            }
            LoginUser identity = permissionService.loadIdentity(current.getUserId());
            if (identity != null)
            {
                identity.setToken(current.getToken());
                identity.setIpaddr(current.getIpaddr());
                identity.setLoginLocation(current.getLoginLocation());
                identity.setBrowser(current.getBrowser());
                identity.setOs(current.getOs());
                renewTimes(identity);
            }
            if (redisCache.compareAndSetCacheObject(key, expected, identity, expireTime * MILLIS_MINUTE))
            {
                return identity;
            }
        }
        // 无法确认刷新成功时让该会话重新登录，不留下可能过期的授权快照。
        redisCache.deleteObject(key);
        throw new IllegalStateException("在线身份刷新连续竞争，会话已失效");
    }

    /**
     * 数据库提交后刷新受影响用户的全部 Token。可使用同一入口重试；失败明确记录，
     * 不把已提交的授权修改伪装成数据库回滚。
     */
    public void refreshUsersAfterCommit(Collection<Long> userIds)
    {
        Set<Long> affected = new HashSet<>(userIds);
        affected.remove(null);
        if (affected.isEmpty())
        {
            return;
        }
        if (TransactionSynchronizationManager.isActualTransactionActive())
        {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization()
            {
                @Override
                public void afterCommit()
                {
                    refreshOnlineUsers(affected);
                }
            });
        }
        else
        {
            refreshOnlineUsers(affected);
        }
    }

    private void refreshOnlineUsers(Set<Long> userIds)
    {
        try
        {
            Collection<String> keys = redisCache.keys(CacheConstants.LOGIN_TOKEN_KEY + "*");
            if (keys == null)
            {
                return;
            }
            for (String key : keys)
            {
                LoginUser cached = redisCache.getCacheObject(key);
                if (cached != null && userIds.contains(cached.getUserId()))
                {
                    try
                    {
                        refreshIdentity(cached.getToken());
                    }
                    catch (RuntimeException exception)
                    {
                        log.error("授权已提交，但用户[{}]在线身份刷新失败；请重试 refreshUsersAfterCommit", cached.getUserId(), exception);
                        redisCache.deleteObject(key);
                    }
                }
            }
        }
        catch (RuntimeException exception)
        {
            log.error("授权已提交，但用户集合{}的在线会话刷新未完成；请重试 refreshUsersAfterCommit", userIds, exception);
        }
    }

    /**
     * 设置用户代理信息
     * 
     * @param loginUser 登录信息
     */
    public void setUserAgent(LoginUser loginUser)
    {
        String userAgent = ServletUtils.getRequest().getHeader("User-Agent");
        String ip = IpUtils.getIpAddr();
        loginUser.setIpaddr(ip);
        loginUser.setLoginLocation(AddressUtils.getRealAddressByIP(ip));
        loginUser.setBrowser(UserAgentUtils.getBrowser(userAgent));
        loginUser.setOs(UserAgentUtils.getOperatingSystem(userAgent));
    }

    /**
     * 从数据声明生成令牌
     *
     * @param claims Map&lt;String, Object&gt;，写入 JWT 载荷的受控登录声明
     * @return String，使用 HS512 签名且由 Jackson 3 编码的紧凑 JWT
     */
    private String createToken(Map<String, Object> claims)
    {
        return Jwts.builder()
                .claims(claims)
                .json(JWT_JSON_SERIALIZER)
                .signWith(requireSigningKey(), Jwts.SIG.HS512)
                .compact();
    }

    /**
     * 从令牌中获取数据声明
     *
     * @param token String，客户端提交的紧凑签名 JWT
     * @return Claims，通过 HS512 签名验证并由 Jackson 3 解码的数据声明
     */
    private Claims parseToken(String token)
    {
        return Jwts.parser()
                .verifyWith(requireSigningKey())
                .json(JWT_JSON_DESERIALIZER)
                // 解析端与签发端使用同一算法白名单，拒绝持有同密钥的低强度算法 Token。
                .sig().clear().add(Jwts.SIG.HS512).and()
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 获取已经通过启动门禁初始化的 HS512 签名密钥。
     *
     * @return SecretKey，可用于签发和验证当前应用 JWT 的不可变密钥
     */
    private SecretKey requireSigningKey()
    {
        if (signingKey == null)
        {
            throw new IllegalStateException("Token 签名密钥尚未初始化");
        }
        return signingKey;
    }

    /**
     * 从 RuoYi Token 根密钥派生指定用途的 AES-256 子密钥。
     *
     * 派生过程使用 HMAC-SHA-256 和固定协议前缀隔离用途，调用方不会取得 Token
     * 根密钥，也不能把派生出的邮件密钥用于伪造 JWT。根密钥变化时派生结果也会
     * 变化，因此部署必须继续遵守既有 Token 密钥持久化和多节点共享规则。
     *
     * @param purpose String，调用方代码内固定的非空用途标识，不得来自用户输入
     * @return SecretKey，长度固定为 32 字节且算法标记为 AES 的用途子密钥
     */
    public SecretKey deriveAes256Key(String purpose)
    {
        if (purpose == null || !purpose.matches("[A-Za-z0-9._-]{1,128}"))
        {
            throw new IllegalArgumentException("密钥派生用途不合法");
        }
        byte[] rootKey = requireSigningKey().getEncoded();
        if (rootKey == null || rootKey.length < MIN_TOKEN_SECRET_BYTES)
        {
            throw new IllegalStateException("Token 根密钥不可用于业务密钥派生");
        }
        try
        {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(rootKey, "HmacSHA256"));
            byte[] derived = mac.doFinal((KEY_DERIVATION_PREFIX + purpose)
                    .getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(derived, "AES");
        }
        catch (GeneralSecurityException exception)
        {
            throw new IllegalStateException("Token 业务密钥派生能力不可用", exception);
        }
        finally
        {
            Arrays.fill(rootKey, (byte) 0);
        }
    }

    /**
     * 获取请求token
     *
     * @param request
     * @return token
     */
    private String getToken(HttpServletRequest request)
    {
        String token = request.getHeader(header);
        if (StringUtils.isNotEmpty(token) && token.startsWith(Constants.TOKEN_PREFIX))
        {
            token = token.replace(Constants.TOKEN_PREFIX, "");
        }
        return token;
    }

    private String getTokenKey(String uuid)
    {
        return CacheConstants.LOGIN_TOKEN_KEY + uuid;
    }

}
