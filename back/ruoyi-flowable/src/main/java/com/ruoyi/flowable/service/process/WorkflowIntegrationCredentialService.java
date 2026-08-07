package com.ruoyi.flowable.service.process;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfIntegrationCredential;
import com.ruoyi.flowable.domain.dto.WorkflowIntegrationCredentialCreateRequest;
import com.ruoyi.flowable.domain.dto.WorkflowIntegrationCredentialRotateRequest;
import com.ruoyi.flowable.domain.vo.WorkflowIntegrationCredentialSecretView;
import com.ruoyi.flowable.domain.vo.WorkflowIntegrationCredentialView;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.mapper.WfIntegrationCredentialMapper;

/**
 * 集成账号生命周期、Token 哈希认证、范围校验和数据库原子限流服务。
 */
@Service
public class WorkflowIntegrationCredentialService
{
    private static final Pattern VARIABLE_NAME =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");
    private static final Set<String> SUPPORTED_SCOPES = Set.of("MESSAGE", "SIGNAL", "RECEIVE");
    private static final Duration RATE_WINDOW = Duration.ofMinutes(1);
    private static final Duration MINIMUM_EXPIRY = Duration.ofMinutes(1);
    private static final int TOKEN_RANDOM_BYTES = 32;
    private static final int TOKEN_PREFIX_LENGTH = 12;

    private final WorkflowEngineOperations engineOperations;
    private final WfIntegrationCredentialMapper credentialMapper;
    private final SecureRandom secureRandom;
    private final Clock clock;

    /**
     * 创建正式集成账号服务。
     * @param engineOperations WorkflowEngineOperations，管理操作身份和事务边界
     * @param credentialMapper WfIntegrationCredentialMapper，正式凭据表 Mapper
     * @return void，构造后由 Spring 管理
     */
    @Autowired
    public WorkflowIntegrationCredentialService(WorkflowEngineOperations engineOperations,
            WfIntegrationCredentialMapper credentialMapper)
    {
        this(engineOperations, credentialMapper, new SecureRandom(), Clock.systemUTC());
    }

    /**
     * 创建可注入随机源和时钟的服务实例，供边界测试稳定控制时间。
     * @param engineOperations WorkflowEngineOperations，管理操作身份和事务边界
     * @param credentialMapper WfIntegrationCredentialMapper，正式凭据表 Mapper
     * @param secureRandom SecureRandom，至少 256 位 Token 随机源
     * @param clock Clock，到期和限流时钟
     * @return void，构造完成后可执行业务方法
     */
    WorkflowIntegrationCredentialService(WorkflowEngineOperations engineOperations,
            WfIntegrationCredentialMapper credentialMapper, SecureRandom secureRandom,
            Clock clock)
    {
        this.engineOperations = engineOperations;
        this.credentialMapper = credentialMapper;
        this.secureRandom = secureRandom;
        this.clock = clock;
    }

    /**
     * 查询不包含 Token 哈希或正文的正式集成账号清单。
     * @return List&lt;WorkflowIntegrationCredentialView&gt;，脱敏管理视图
     */
    public List<WorkflowIntegrationCredentialView> list()
    {
        return engineOperations.read(() -> credentialMapper.selectList().stream()
                .map(this::toView).toList());
    }

    /**
     * 创建集成账号并只在本次响应返回一次明文 Token。
     * @param request WorkflowIntegrationCredentialCreateRequest，范围、变量白名单和限流配置
     * @return WorkflowIntegrationCredentialSecretView，包含仅此一次可见的明文 Token
     */
    public WorkflowIntegrationCredentialSecretView create(
            WorkflowIntegrationCredentialCreateRequest request)
    {
        NormalizedCredential normalized = normalize(request);
        return engineOperations.writeAsCurrentUser(identity ->
        {
            GeneratedToken generated = generateToken();
            WfIntegrationCredential credential = new WfIntegrationCredential();
            credential.setCredentialName(normalized.name());
            credential.setScopes(normalized.scopes());
            credential.setAllowedVariables(normalized.allowedVariables());
            credential.setRateLimitPerMinute(normalized.rateLimit());
            credential.setExpiresAt(normalized.expiresAt());
            credential.setTokenPrefix(generated.prefix());
            credential.setTokenHash(generated.hash());
            credential.setRevisionNo(1);
            credential.setCreateBy(identity.userId());
            if (credentialMapper.insert(credential) != 1 || credential.getCredentialId() == null)
            {
                throw new ServiceException("集成账号保存结果不完整", HttpStatus.CONFLICT);
            }
            return new WorkflowIntegrationCredentialSecretView(credential.getCredentialId(), 1,
                    generated.plaintext(), toView(credential));
        });
    }

    /**
     * 原子轮换 Token，旧 Token 在事务提交后立即失效，新正文只返回一次。
     * @param credentialId Long，待轮换凭据主键
     * @param request WorkflowIntegrationCredentialRotateRequest，可选的新到期时间
     * @return WorkflowIntegrationCredentialSecretView，新 Token 和脱敏凭据视图
     */
    public WorkflowIntegrationCredentialSecretView rotate(Long credentialId,
            WorkflowIntegrationCredentialRotateRequest request)
    {
        requireId(credentialId);
        return engineOperations.writeAsCurrentUser(identity ->
        {
            WfIntegrationCredential current = requireLocked(credentialId);
            requireActive(current);
            GeneratedToken generated = generateToken();
            Date expiry = request != null && request.expiresAt() != null
                    ? validateExpiry(request.expiresAt()) : current.getExpiresAt();
            current.setTokenPrefix(generated.prefix());
            current.setTokenHash(generated.hash());
            current.setExpiresAt(expiry);
            current.setRevisionNo(Math.addExact(current.getRevisionNo(), 1));
            current.setUpdateBy(identity.userId());
            if (credentialMapper.rotate(current, current.getRevisionNo() - 1) != 1)
            {
                throw new ServiceException("集成 Token 轮换发生并发冲突", HttpStatus.CONFLICT);
            }
            return new WorkflowIntegrationCredentialSecretView(current.getCredentialId(),
                    current.getRevisionNo(), generated.plaintext(), toView(current));
        });
    }

    /**
     * 吊销集成账号，重复吊销按非法状态返回 409。
     * @param credentialId Long，待吊销凭据主键
     * @return void，吊销成功后旧 Token 永久不可用
     */
    public void revoke(Long credentialId)
    {
        requireId(credentialId);
        engineOperations.writeAsCurrentUser(identity ->
        {
            WfIntegrationCredential current = requireLocked(credentialId);
            requireActive(current);
            if (credentialMapper.revoke(credentialId, identity.userId()) != 1)
            {
                throw new ServiceException("集成账号吊销发生并发冲突", HttpStatus.CONFLICT);
            }
            return null;
        });
    }

    /**
     * 使用数据库行锁完成 Token、吊销、到期、范围和分钟限流认证。
     * @param plaintextToken String，X-Integration-Token 原始值
     * @param requiredScope String，本次 MESSAGE、SIGNAL 或 RECEIVE 范围
     * @return AuthenticatedCredential，后续引擎事务使用的可信身份和变量白名单
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.REPEATABLE_READ)
    public AuthenticatedCredential authenticateAndConsume(String plaintextToken,
            String requiredScope)
    {
        if (plaintextToken == null || plaintextToken.length() < TOKEN_PREFIX_LENGTH)
        {
            throw unauthorized();
        }
        String scope = requiredScope == null ? "" : requiredScope.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_SCOPES.contains(scope))
        {
            throw new ServiceException("运行事件范围不受支持", HttpStatus.BAD_REQUEST);
        }
        String prefix = plaintextToken.substring(0, TOKEN_PREFIX_LENGTH);
        WfIntegrationCredential credential = credentialMapper.selectByPrefixForUpdate(prefix);
        if (credential == null || !constantTimeEquals(credential.getTokenHash(), sha256(plaintextToken)))
        {
            throw unauthorized();
        }
        requireActiveForAuthentication(credential);
        Set<String> scopes = split(credential.getScopes());
        if (!scopes.contains(scope))
        {
            throw new ServiceException("集成账号缺少运行事件范围", HttpStatus.FORBIDDEN)
                    .setSubCode("INTEGRATION_SCOPE_DENIED");
        }

        // 行锁内滚动固定一分钟窗口，多个节点和线程共享同一计数，不依赖本机内存。
        Instant now = clock.instant();
        Instant windowStart = credential.getRateWindowStart() == null
                ? now : credential.getRateWindowStart().toInstant();
        int count = credential.getRateWindowCount() == null ? 0
                : credential.getRateWindowCount();
        if (!now.isBefore(windowStart.plus(RATE_WINDOW)))
        {
            windowStart = now;
            count = 0;
        }
        if (count >= credential.getRateLimitPerMinute())
        {
            throw new ServiceException("集成账号请求频率超过限制", HttpStatus.TOO_MANY_REQUESTS)
                    .setSubCode("INTEGRATION_RATE_LIMITED");
        }
        Date nowDate = Date.from(now);
        if (credentialMapper.updateRateWindow(credential.getCredentialId(),
                Date.from(windowStart), count + 1, nowDate) != 1)
        {
            throw new ServiceException("集成账号限流状态更新失败", HttpStatus.CONFLICT);
        }
        return new AuthenticatedCredential(credential.getCredentialId(),
                credential.getCreateBy(), Set.copyOf(split(credential.getAllowedVariables())));
    }

    /**
     * 规范化管理请求并阻止重复范围、变量名和过近到期时间。
     * @param request WorkflowIntegrationCredentialCreateRequest，外部创建请求
     * @return NormalizedCredential，可直接落库的规范字段
     */
    private NormalizedCredential normalize(WorkflowIntegrationCredentialCreateRequest request)
    {
        if (request == null)
        {
            throw new ServiceException("集成账号请求不能为空", HttpStatus.BAD_REQUEST);
        }
        String name = request.credentialName() == null ? "" : request.credentialName().trim();
        if (name.isEmpty() || name.length() > 128)
        {
            throw new ServiceException("集成账号名称不合法", HttpStatus.BAD_REQUEST);
        }
        String scopes = normalizeScopes(request.scopes());
        String variables = normalizeVariables(request.allowedVariables());
        if (request.rateLimitPerMinute() == null || request.rateLimitPerMinute() < 1
                || request.rateLimitPerMinute() > 10000)
        {
            throw new ServiceException("集成账号限流配置不合法", HttpStatus.BAD_REQUEST);
        }
        Date expiry = request.expiresAt() == null ? null : validateExpiry(request.expiresAt());
        return new NormalizedCredential(name, scopes, variables,
                request.rateLimitPerMinute(), expiry);
    }

    /**
     * 规范事件范围并拒绝重复或未知值。
     * @param values List&lt;String&gt;，请求范围
     * @return String，字典序逗号分隔范围
     */
    private String normalizeScopes(List<String> values)
    {
        TreeSet<String> normalized = new TreeSet<>();
        for (String value : values == null ? List.<String>of() : values)
        {
            String scope = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
            if (!SUPPORTED_SCOPES.contains(scope) || !normalized.add(scope))
            {
                throw new ServiceException("集成账号范围不合法或重复", HttpStatus.BAD_REQUEST);
            }
        }
        if (normalized.isEmpty())
        {
            throw new ServiceException("集成账号至少需要一个范围", HttpStatus.BAD_REQUEST);
        }
        return String.join(",", normalized);
    }

    /**
     * 规范变量白名单并拒绝重复、非法名称或超量配置。
     * @param values List&lt;String&gt;，请求变量名
     * @return String，字典序逗号分隔变量名，允许空串
     */
    private String normalizeVariables(List<String> values)
    {
        TreeSet<String> normalized = new TreeSet<>();
        for (String value : values == null ? List.<String>of() : values)
        {
            String variable = value == null ? "" : value.trim();
            if (!VARIABLE_NAME.matcher(variable).matches() || !normalized.add(variable))
            {
                throw new ServiceException("集成变量白名单不合法或重复", HttpStatus.BAD_REQUEST);
            }
        }
        if (normalized.size() > 128)
        {
            throw new ServiceException("集成变量白名单超过数量限制", HttpStatus.BAD_REQUEST);
        }
        return String.join(",", normalized);
    }

    /**
     * 验证到期时间至少晚于当前时间一分钟，避免建表约束边界竞态。
     * @param expiresAt OffsetDateTime，外部到期时间
     * @return Date，可持久化 UTC 时刻
     */
    private Date validateExpiry(OffsetDateTime expiresAt)
    {
        Instant instant = expiresAt.toInstant();
        if (instant.isBefore(clock.instant().plus(MINIMUM_EXPIRY)))
        {
            throw new ServiceException("集成 Token 到期时间必须晚于当前时间一分钟",
                    HttpStatus.BAD_REQUEST);
        }
        return Date.from(instant);
    }

    /**
     * 使用 JDK SecureRandom 生成至少 256 位 URL-safe Token 和摘要。
     * @return GeneratedToken，明文、前缀和 SHA-256
     */
    private GeneratedToken generateToken()
    {
        byte[] random = new byte[TOKEN_RANDOM_BYTES];
        secureRandom.nextBytes(random);
        String plaintext = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        return new GeneratedToken(plaintext, plaintext.substring(0, TOKEN_PREFIX_LENGTH),
                sha256(plaintext));
    }

    /**
     * 计算 Token 的小写 SHA-256。
     * @param value String，Token 正文
     * @return String，64 位小写十六进制摘要
     */
    private String sha256(String value)
    {
        try
        {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException exception)
        {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", exception);
        }
    }

    /**
     * 使用常量时间比较摘要，避免普通字符串比较泄露有效哈希前缀。
     * @param expectedHex String，数据库 SHA-256
     * @param actualHex String，本次 Token SHA-256
     * @return boolean，摘要完全一致时为 true
     */
    private boolean constantTimeEquals(String expectedHex, String actualHex)
    {
        byte[] expected = expectedHex == null ? new byte[0]
                : expectedHex.getBytes(StandardCharsets.US_ASCII);
        byte[] actual = actualHex.getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual);
    }

    /**
     * 校验管理操作读取到的凭据仍未吊销且未到期。
     * @param credential WfIntegrationCredential，锁定后的凭据
     * @return void，非法状态抛出 409
     */
    private void requireActive(WfIntegrationCredential credential)
    {
        if (credential.getRevokedAt() != null)
        {
            throw new ServiceException("集成账号已经吊销", HttpStatus.CONFLICT);
        }
        if (credential.getExpiresAt() != null
                && !credential.getExpiresAt().toInstant().isAfter(clock.instant()))
        {
            throw new ServiceException("集成账号已经到期", HttpStatus.CONFLICT);
        }
    }

    /**
     * 校验外部认证状态并返回不泄露具体状态的 401。
     * @param credential WfIntegrationCredential，哈希已匹配的凭据
     * @return void，吊销或到期时抛出统一未授权异常
     */
    private void requireActiveForAuthentication(WfIntegrationCredential credential)
    {
        if (credential.getRevokedAt() != null || credential.getExpiresAt() != null
                && !credential.getExpiresAt().toInstant().isAfter(clock.instant()))
        {
            throw unauthorized();
        }
    }

    /**
     * 锁定并读取必须存在的凭据。
     * @param credentialId Long，凭据主键
     * @return WfIntegrationCredential，锁定后的正式实体
     */
    private WfIntegrationCredential requireLocked(Long credentialId)
    {
        WfIntegrationCredential credential = credentialMapper.selectByIdForUpdate(credentialId);
        if (credential == null)
        {
            throw new ServiceException("集成账号不存在", HttpStatus.NOT_FOUND);
        }
        return credential;
    }

    /**
     * 校验正数主键。
     * @param credentialId Long，凭据主键
     * @return void，非法时抛出 400
     */
    private void requireId(Long credentialId)
    {
        if (credentialId == null || credentialId <= 0)
        {
            throw new ServiceException("集成账号主键不合法", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 把逗号字段转换为不可变集合。
     * @param value String，排序后的逗号字段
     * @return Set&lt;String&gt;，空字段返回空集合
     */
    private Set<String> split(String value)
    {
        if (value == null || value.isBlank())
        {
            return Set.of();
        }
        return Set.copyOf(Arrays.asList(value.split(",")));
    }

    /**
     * 转换为不含 Token 哈希或正文的管理视图。
     * @param credential WfIntegrationCredential，正式数据库实体
     * @return WorkflowIntegrationCredentialView，脱敏视图
     */
    private WorkflowIntegrationCredentialView toView(WfIntegrationCredential credential)
    {
        return new WorkflowIntegrationCredentialView(credential.getCredentialId(),
                credential.getCredentialName(), credential.getTokenPrefix(),
                List.copyOf(split(credential.getScopes())),
                List.copyOf(split(credential.getAllowedVariables())),
                credential.getRateLimitPerMinute(), credential.getExpiresAt(),
                credential.getRevokedAt(), credential.getRevisionNo(),
                credential.getLastUsedAt(), credential.getCreateTime(),
                credential.getUpdateTime());
    }

    /** @return ServiceException，不泄露 Token 是否存在、吊销或到期的统一 401。 */
    private ServiceException unauthorized()
    {
        return new ServiceException("集成 Token 无效或已失效", HttpStatus.UNAUTHORIZED)
                .setSubCode("INTEGRATION_TOKEN_INVALID");
    }

    /** 规范创建字段。 */
    private record NormalizedCredential(String name, String scopes,
            String allowedVariables, int rateLimit, Date expiresAt) { }

    /** 一次性 Token 生成结果。 */
    private record GeneratedToken(String plaintext, String prefix, String hash) { }

    /**
     * 通过认证后供运行事件服务使用的最小可信上下文。
     * @param credentialId Long，正式凭据主键
     * @param actorUserId String，创建凭据的正式用户主键
     * @param allowedVariables Set&lt;String&gt;，允许写入 Flowable 的变量名
     */
    public record AuthenticatedCredential(Long credentialId, String actorUserId,
            Set<String> allowedVariables) { }
}
