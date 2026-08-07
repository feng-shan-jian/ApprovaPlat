package com.ruoyi.flowable.service.model;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfConnectorEndpoint;
import com.ruoyi.flowable.domain.dto.WorkflowConnectorEndpointRequest;
import com.ruoyi.flowable.domain.vo.WorkflowConnectorEndpointView;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.extension.WorkflowExtensionChecksum;
import com.ruoyi.flowable.mapper.WfConnectorEndpointMapper;

/**
 * HTTP 连接器端点白名单、修订和部署锁定服务。
 */
@Service
public class WorkflowConnectorEndpointService
{
    /** 端点稳定键格式。 */
    private static final Pattern ENDPOINT_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,127}");
    /** Secret 只允许引用受控环境变量命名空间。 */
    private static final Pattern SECRET_REF = Pattern.compile("WORKFLOW_CONNECTOR_SECRET_[A-Z0-9_]{1,96}");
    /** API Key 请求头必须是标准 HTTP token。 */
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}");
    /** 当前允许的出站 HTTP 方法。 */
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    /** 端点状态常量。 */
    private static final String ENABLED = "ENABLED";
    private static final String DISABLED = "DISABLED";

    private final WorkflowEngineOperations engineOperations;
    private final WfConnectorEndpointMapper endpointMapper;

    /**
     * 创建端点领域服务。
     * @param engineOperations WorkflowEngineOperations，统一身份与事务边界
     * @param endpointMapper WfConnectorEndpointMapper，端点数据访问层
     * @return 无返回值，构造后由 Spring 管理
     */
    public WorkflowConnectorEndpointService(WorkflowEngineOperations engineOperations,
            WfConnectorEndpointMapper endpointMapper)
    {
        this.engineOperations = engineOperations;
        this.endpointMapper = endpointMapper;
    }

    /**
     * 查询全部端点管理视图。
     * @return List&lt;WorkflowConnectorEndpointView&gt;，真实数据库清单
     */
    public List<WorkflowConnectorEndpointView> list()
    {
        return engineOperations.read(() -> List.copyOf(endpointMapper.selectList()));
    }

    /**
     * 查询设计器可选择的已启用端点。
     * @return List&lt;WorkflowConnectorEndpointView&gt;，已启用端点清单
     */
    public List<WorkflowConnectorEndpointView> listOptions()
    {
        return engineOperations.read(() -> List.copyOf(endpointMapper.selectEnabledOptions()));
    }

    /**
     * 新增端点白名单并发布修订 1。
     * @param request WorkflowConnectorEndpointRequest，端点业务配置
     * @return Long，数据库生成端点主键
     */
    public Long create(WorkflowConnectorEndpointRequest request)
    {
        WfConnectorEndpoint normalized = normalize(request, 1);
        return engineOperations.writeAsCurrentUser(identity ->
        {
            normalized.setStatus(ENABLED);
            normalized.setCreateBy(identity.userId());
            if (endpointMapper.insert(normalized) != 1 || normalized.getEndpointId() == null)
            {
                throw new ServiceException("连接器端点保存结果不完整", HttpStatus.CONFLICT);
            }
            return normalized.getEndpointId();
        });
    }

    /**
     * 在端点行锁内发布下一修订，稳定键和历史部署快照保持不变。
     * @param endpointId Long，端点主键
     * @param request WorkflowConnectorEndpointRequest，新修订配置
     * @return Integer，新修订号
     */
    public Integer update(Long endpointId, WorkflowConnectorEndpointRequest request)
    {
        requireId(endpointId);
        return engineOperations.writeAsCurrentUser(identity ->
        {
            WfConnectorEndpoint current = requireLocked(endpointId);
            int nextRevision = Math.addExact(current.getRevisionNo(), 1);
            WfConnectorEndpoint normalized = normalize(request, nextRevision);
            if (!current.getEndpointKey().equals(normalized.getEndpointKey()))
            {
                throw new ServiceException("连接器端点稳定键不允许修改", HttpStatus.CONFLICT);
            }
            normalized.setEndpointId(endpointId);
            normalized.setUpdateBy(identity.userId());
            if (endpointMapper.updateRevision(normalized, current.getRevisionNo()) != 1)
            {
                throw new ServiceException("连接器端点修订已发生变化", HttpStatus.CONFLICT);
            }
            return nextRevision;
        });
    }

    /**
     * 启用或停用端点，只影响后续设计和部署。
     * @param endpointId Long，端点主键
     * @param enabled boolean，目标启用状态
     * @return void，无返回值
     */
    public void changeStatus(Long endpointId, boolean enabled)
    {
        requireId(endpointId);
        engineOperations.writeAsCurrentUser(identity ->
        {
            WfConnectorEndpoint current = requireLocked(endpointId);
            String target = enabled ? ENABLED : DISABLED;
            if (target.equals(current.getStatus()))
            {
                throw new ServiceException("连接器端点已经是目标状态", HttpStatus.CONFLICT);
            }
            if (endpointMapper.updateStatus(endpointId, target, identity.userId()) != 1)
            {
                throw new ServiceException("连接器端点状态已发生变化", HttpStatus.CONFLICT);
            }
            return null;
        });
    }

    /**
     * 部署事务内锁定并复核已启用端点，调用方据此冻结完整配置。
     * @param endpointKey String，作者节点引用的稳定端点键
     * @return WfConnectorEndpoint，当前启用修订
     */
    public WfConnectorEndpoint lockEnabledForDeployment(String endpointKey)
    {
        if (endpointKey == null || !ENDPOINT_KEY.matcher(endpointKey.trim()).matches())
        {
            throw new ServiceException("HTTP 连接器端点键不合法", HttpStatus.BAD_REQUEST);
        }
        WfConnectorEndpoint endpoint = endpointMapper.selectEnabledByKeyForUpdate(endpointKey.trim());
        if (endpoint == null)
        {
            throw new ServiceException("HTTP 连接器端点不存在或已停用", HttpStatus.CONFLICT);
        }
        if (!endpointChecksum(endpoint).equals(endpoint.getChecksum()))
        {
            throw new ServiceException("HTTP 连接器端点校验和不一致", HttpStatus.CONFLICT);
        }
        return endpoint;
    }

    /**
     * 计算端点当前修订的稳定摘要，不包含审计字段和任何密钥正文。
     * @param endpoint WfConnectorEndpoint，字段完整的规范端点
     * @return String，64 位小写 SHA-256
     */
    public static String endpointChecksum(WfConnectorEndpoint endpoint)
    {
        return WorkflowExtensionChecksum.sha256(endpoint.getEndpointKey(), endpoint.getEndpointName(),
                endpoint.getBaseUrl(), endpoint.getAllowedMethods(), endpoint.getPathPrefix(),
                endpoint.getAuthType(), endpoint.getSecretRef(), endpoint.getApiKeyHeader(),
                String.valueOf(endpoint.getConnectTimeoutMs()),
                String.valueOf(endpoint.getRequestTimeoutMs()), endpoint.getNetworkScope(),
                String.valueOf(endpoint.getRevisionNo()));
    }

    /**
     * 校验并规范化端点请求，阻断任意 URL、明文密钥和不受控方法。
     * @param request WorkflowConnectorEndpointRequest，外部请求
     * @param revisionNo int，本次发布修订号
     * @return WfConnectorEndpoint，可直接持久化的规范端点
     */
    private WfConnectorEndpoint normalize(WorkflowConnectorEndpointRequest request, int revisionNo)
    {
        if (request == null)
        {
            throw new ServiceException("连接器端点请求不能为空", HttpStatus.BAD_REQUEST);
        }
        String endpointKey = trimToEmpty(request.endpointKey());
        String endpointName = trimToEmpty(request.endpointName());
        String baseUrl = normalizeBaseUrl(request.baseUrl());
        String methods = normalizeMethods(request.allowedMethods());
        String pathPrefix = normalizePath(request.pathPrefix());
        String authType = trimToEmpty(request.authType()).toUpperCase(Locale.ROOT);
        String secretRef = trimToNull(request.secretRef());
        String apiKeyHeader = trimToNull(request.apiKeyHeader());
        String networkScope = trimToEmpty(request.networkScope()).toUpperCase(Locale.ROOT);
        if (!ENDPOINT_KEY.matcher(endpointKey).matches() || endpointName.isEmpty()
                || endpointName.length() > 128)
        {
            throw new ServiceException("连接器端点基础信息不合法", HttpStatus.BAD_REQUEST);
        }
        if (!Set.of("PUBLIC", "PRIVATE").contains(networkScope))
        {
            throw new ServiceException("连接器端点网络范围不合法", HttpStatus.BAD_REQUEST);
        }
        if (request.connectTimeoutMs() == null || request.connectTimeoutMs() < 100
                || request.connectTimeoutMs() > 10000 || request.requestTimeoutMs() == null
                || request.requestTimeoutMs() < 500 || request.requestTimeoutMs() > 120000)
        {
            throw new ServiceException("连接器端点超时配置不合法", HttpStatus.BAD_REQUEST);
        }
        validateAuthentication(authType, secretRef, apiKeyHeader);

        WfConnectorEndpoint endpoint = new WfConnectorEndpoint();
        endpoint.setEndpointKey(endpointKey);
        endpoint.setEndpointName(endpointName);
        endpoint.setBaseUrl(baseUrl);
        endpoint.setAllowedMethods(methods);
        endpoint.setPathPrefix(pathPrefix);
        endpoint.setAuthType(authType);
        endpoint.setSecretRef(secretRef);
        endpoint.setApiKeyHeader(apiKeyHeader);
        endpoint.setConnectTimeoutMs(request.connectTimeoutMs());
        endpoint.setRequestTimeoutMs(request.requestTimeoutMs());
        endpoint.setNetworkScope(networkScope);
        endpoint.setRevisionNo(revisionNo);
        endpoint.setChecksum(endpointChecksum(endpoint));
        return endpoint;
    }

    /**
     * 规范基础 URL 并禁止用户信息、查询、片段和非根路径。
     * @param value String，外部基础 URL
     * @return String，规范 origin URL
     */
    private String normalizeBaseUrl(String value)
    {
        try
        {
            URI uri = new URI(trimToEmpty(value)).normalize();
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!("https".equals(scheme) || "http".equals(scheme)) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath())))
            {
                throw new ServiceException("连接器基础 URL 只能包含协议、主机和端口", HttpStatus.BAD_REQUEST);
            }
            int port = uri.getPort();
            return new URI(scheme, null, uri.getHost().toLowerCase(Locale.ROOT), port, null, null, null)
                    .toASCIIString();
        }
        catch (URISyntaxException | IllegalArgumentException exception)
        {
            throw new ServiceException("连接器基础 URL 不合法", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 规范允许方法并按字典序冻结，拒绝未知或重复方法。
     * @param values List&lt;String&gt;，请求方法清单
     * @return String，逗号分隔稳定清单
     */
    private String normalizeMethods(List<String> values)
    {
        TreeSet<String> methods = new TreeSet<>();
        for (String value : values == null ? List.<String>of() : values)
        {
            String method = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
            if (!METHODS.contains(method) || !methods.add(method))
            {
                throw new ServiceException("连接器允许方法不合法或重复", HttpStatus.BAD_REQUEST);
            }
        }
        if (methods.isEmpty())
        {
            throw new ServiceException("连接器至少需要一个允许方法", HttpStatus.BAD_REQUEST);
        }
        return String.join(",", methods);
    }

    /**
     * 规范请求路径前缀，禁止查询、片段、反斜杠和目录穿越。
     * @param value String，外部路径前缀
     * @return String，以斜杠开头且不以斜杠结尾的规范路径
     */
    private String normalizePath(String value)
    {
        String path = value == null ? "" : value.trim();
        String lowerPath = path.toLowerCase(Locale.ROOT);
        if (!path.startsWith("/") || path.contains("?") || path.contains("#")
                || path.contains("\\") || path.contains("..") || path.contains("//")
                || lowerPath.contains("%2e") || lowerPath.contains("%2f")
                || lowerPath.contains("%5c") || path.length() > 512)
        {
            throw new ServiceException("连接器路径前缀不合法", HttpStatus.BAD_REQUEST);
        }
        while (path.length() > 1 && path.endsWith("/"))
        {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    /**
     * 校验认证模式与外部密钥引用组合，不允许密钥正文进入数据库。
     * @param authType String，认证类型
     * @param secretRef String，可空外部引用
     * @param apiKeyHeader String，可空请求头
     * @return void，组合非法时抛出业务异常
     */
    private void validateAuthentication(String authType, String secretRef, String apiKeyHeader)
    {
        if ("NONE".equals(authType))
        {
            if (secretRef != null || apiKeyHeader != null)
            {
                throw new ServiceException("无认证端点不能配置密钥引用或认证请求头", HttpStatus.BAD_REQUEST);
            }
            return;
        }
        if (!("BEARER".equals(authType) || "API_KEY".equals(authType))
                || secretRef == null || !SECRET_REF.matcher(secretRef).matches())
        {
            throw new ServiceException("连接器密钥引用不合法", HttpStatus.BAD_REQUEST);
        }
        if ("API_KEY".equals(authType))
        {
            if (apiKeyHeader == null || !HEADER_NAME.matcher(apiKeyHeader).matches())
            {
                throw new ServiceException("API Key 请求头不合法", HttpStatus.BAD_REQUEST);
            }
        }
        else if (apiKeyHeader != null)
        {
            throw new ServiceException("Bearer 认证不能配置 API Key 请求头", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 查询并锁定必须存在的端点。
     * @param endpointId Long，端点主键
     * @return WfConnectorEndpoint，锁定后的端点
     */
    private WfConnectorEndpoint requireLocked(Long endpointId)
    {
        WfConnectorEndpoint endpoint = endpointMapper.selectByIdForUpdate(endpointId);
        if (endpoint == null)
        {
            throw new ServiceException("连接器端点不存在", HttpStatus.NOT_FOUND);
        }
        return endpoint;
    }

    /**
     * 校验端点主键。
     * @param endpointId Long，端点主键
     * @return void，非法时抛出业务异常
     */
    private void requireId(Long endpointId)
    {
        if (endpointId == null || endpointId <= 0)
        {
            throw new ServiceException("连接器端点主键不合法", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 去除可选文本首尾空白并把空串转换为空值。
     * @param value String，可选文本
     * @return String，规范文本或 null
     */
    private String trimToNull(String value)
    {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * 去除必填文本首尾空白，并把空值转换为空串供统一业务校验。
     * @param value String，可空外部文本
     * @return String，非空规范文本
     */
    private String trimToEmpty(String value)
    {
        return value == null ? "" : value.trim();
    }
}
