package com.ruoyi.framework.web.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.ruoyi.common.constant.CacheConstants;
import com.ruoyi.common.constant.Constants;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.core.redis.RedisCache;
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

    /**
     * 在应用对外提供登录能力前按既有 Base64/Base64URL 配置语义校验并初始化 HS512 密钥。
     *
     * @return void，无返回值；密钥编码非法或解码后不足 64 字节时中止应用启动
     */
    @PostConstruct
    public void validateSecret()
    {
        byte[] secretBytes = decodeConfiguredSecret(secret);
        if (secretBytes.length < MIN_TOKEN_SECRET_BYTES)
        {
            throw new IllegalStateException(
                    "RUOYI_TOKEN_SECRET 必须是合法 Base64 或 Base64URL，且解码后至少包含 64 个字节");
        }
        this.signingKey = Keys.hmacShaKeyFor(secretBytes);
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
            refreshToken(loginUser);
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
        refreshToken(loginUser);

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
        loginUser.setLoginTime(System.currentTimeMillis());
        loginUser.setExpireTime(loginUser.getLoginTime() + expireTime * MILLIS_MINUTE);
        // 根据uuid将loginUser缓存
        String userKey = getTokenKey(loginUser.getToken());
        redisCache.setCacheObject(userKey, loginUser, expireTime, TimeUnit.MINUTES);
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
     * 从令牌中获取用户名
     *
     * @param token 令牌
     * @return 用户名
     */
    public String getUsernameFromToken(String token)
    {
        Claims claims = parseToken(token);
        return claims.getSubject();
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

    /**
     * 角色权限变更后，刷新所有持有该角色的在线用户权限
     *
     * @param roleId            变更的角色ID
     * @param permissionService 权限服务
     */
    public void refreshPermissionByRoleId(Long roleId, SysPermissionService permissionService)
    {
        // 扫描所有在线 token
        String pattern = CacheConstants.LOGIN_TOKEN_KEY + "*";
        Collection<String> keys = redisCache.keys(pattern);
        if (keys == null || keys.isEmpty())
        {
            return;
        }
        for (String key : keys)
        {
            LoginUser loginUser = redisCache.getCacheObject(key);
            if (loginUser == null || loginUser.getUser() == null || loginUser.getUser().isAdmin())
            {
                // 管理员拥有所有权限，跳过
                continue;
            }
            // 判断该用户是否拥有此角色
            boolean hasRole = loginUser.getUser().getRoles() != null
                    && loginUser.getUser().getRoles().stream().anyMatch(r -> roleId.equals(r.getRoleId()));
            if (!hasRole)
            {
                continue;
            }
            // 刷新权限缓存
            loginUser.setPermissions(permissionService.getMenuPermission(loginUser.getUser()));
            refreshToken(loginUser);
            log.info("角色[{}]权限变更，已刷新在线用户[{}]的权限缓存", roleId, loginUser.getUsername());
        }
    }
}
