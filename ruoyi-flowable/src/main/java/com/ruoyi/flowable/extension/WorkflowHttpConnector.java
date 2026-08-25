package com.ruoyi.flowable.extension;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.flowable.engine.delegate.DelegateExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfConnectorEndpoint;
import com.ruoyi.flowable.domain.WfDeployExtensionSnapshot;
import com.ruoyi.flowable.runtime.WorkflowConnectorMetrics;
import com.ruoyi.flowable.service.model.WorkflowConnectorEndpointService;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 部署冻结、运行复核和幂等审计一体化的受控 HTTP 连接器。
 */
@Component
public class WorkflowHttpConnector
{
    private static final Logger log = LoggerFactory.getLogger(WorkflowHttpConnector.class);
    /** ApprovaPlat 协作协议固定使用的集成认证请求头。 */
    private static final String COLLABORATION_TOKEN_HEADER = "X-Integration-Token";
    /** 固定实现键。 */
    public static final String IMPLEMENTATION_KEY = "HTTP_CONNECTOR_V1";
    /** 单次请求或响应正文上限。 */
    private static final int MAX_BODY_BYTES = 64 * 1024;
    /** 流程变量名格式。 */
    private static final Pattern VARIABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");
    /** 相对请求路径格式；不允许查询、片段和目录穿越。 */
    private static final Pattern REQUEST_PATH = Pattern.compile("/[A-Za-z0-9._~!$&'()*+,;=:@%/-]{0,511}");
    /** 作者配置允许字段。 */
    private static final Set<String> AUTHOR_FIELDS = Set.of(
            "endpointKey", "method", "path", "bodyVariable", "statusVariable");
    /** 冻结配置额外字段。 */
    private static final Set<String> FROZEN_FIELDS = Set.of(
            "endpointKey", "method", "path", "bodyVariable", "statusVariable", "endpointSnapshot");
    /** 配置 Schema 只描述产品可见作者字段；endpointSnapshot 由部署编译器自动加入。 */
    private static final String CONFIG_SCHEMA = """
            {
              "type":"object",
              "additionalProperties":false,
              "required":["endpointKey","method","path"],
              "properties":{
                "endpointKey":{"type":"string","pattern":"^[A-Za-z][A-Za-z0-9_.-]{0,127}$"},
                "method":{"type":"string","enum":["GET","POST","PUT","PATCH","DELETE"]},
                "path":{"type":"string","pattern":"^/[A-Za-z0-9._~!$&'()*+,;=:@%/-]{0,511}$"},
                "bodyVariable":{"type":"string","pattern":"^[A-Za-z_][A-Za-z0-9_]{0,127}$"},
                "statusVariable":{"type":"string","pattern":"^[A-Za-z_][A-Za-z0-9_]{0,127}$"}
              }
            }
            """;

    private final WorkflowConnectorEndpointService endpointService;
    private final WorkflowConnectorSecretResolver secretResolver;
    private final WorkflowConnectorMetrics connectorMetrics;
    private final ObjectMapper objectMapper = JsonMapper.shared();

    /**
     * 创建受控 HTTP 连接器。
     * @param endpointService WorkflowConnectorEndpointService，端点锁定服务
     * @param secretResolver WorkflowConnectorSecretResolver，外部密钥解析器
     * @param connectorMetrics WorkflowConnectorMetrics，单次 Flowable Job 尝试指标
     * @return 无返回值，构造后由 Spring 管理
     */
    public WorkflowHttpConnector(WorkflowConnectorEndpointService endpointService,
            WorkflowConnectorSecretResolver secretResolver,
            WorkflowConnectorMetrics connectorMetrics)
    {
        this.endpointService = endpointService;
        this.secretResolver = secretResolver;
        this.connectorMetrics = connectorMetrics;
    }

    /**
     * 返回 HTTP 节点作者配置 Schema。
     * @return String，字段顺序确定的规范 JSON
     */
    public String configSchema()
    {
        return WorkflowExtensionJsonCanonicalizer.canonicalize(CONFIG_SCHEMA);
    }

    /**
     * 校验作者配置并冻结当前启用端点修订。
     * @param config JsonNode，作者 BPMN 中的节点配置
     * @param asynchronous boolean，ServiceTask 是否已启用进入前异步
     * @return String，含端点不可变快照的规范 JSON
     */
    public String freezeConfig(JsonNode config, boolean asynchronous)
    {
        if (!asynchronous)
        {
            throw new ServiceException("HTTP 连接器必须启用进入前异步以承载重试", HttpStatus.BAD_REQUEST);
        }
        ParsedConfig parsed = parseAuthor(config, AUTHOR_FIELDS);
        WfConnectorEndpoint endpoint = endpointService.lockEnabledForDeployment(parsed.endpointKey());
        if (!Set.of(endpoint.getAllowedMethods().split(",")).contains(parsed.method()))
        {
            throw new ServiceException("HTTP 方法不在端点白名单中", HttpStatus.BAD_REQUEST);
        }
        requirePathWithinPrefix(parsed.path(), endpoint.getPathPrefix());
        if (!"https".equals(URI.create(endpoint.getBaseUrl()).getScheme())
                && !isLoopbackHost(URI.create(endpoint.getBaseUrl()).getHost()))
        {
            throw new ServiceException("非本机 HTTP 端点必须使用 HTTPS", HttpStatus.BAD_REQUEST);
        }
        try
        {
            ObjectNode frozen = normalizedAuthor(parsed);
            ObjectNode endpointNode = frozen.putObject("endpointSnapshot");
            endpointNode.put("endpointId", endpoint.getEndpointId());
            endpointNode.put("endpointName", endpoint.getEndpointName());
            endpointNode.put("revisionNo", endpoint.getRevisionNo());
            endpointNode.put("baseUrl", endpoint.getBaseUrl());
            endpointNode.put("allowedMethods", endpoint.getAllowedMethods());
            endpointNode.put("pathPrefix", endpoint.getPathPrefix());
            endpointNode.put("authType", endpoint.getAuthType());
            putOptional(endpointNode, "secretRef", endpoint.getSecretRef());
            putOptional(endpointNode, "apiKeyHeader", endpoint.getApiKeyHeader());
            endpointNode.put("connectTimeoutMs", endpoint.getConnectTimeoutMs());
            endpointNode.put("requestTimeoutMs", endpoint.getRequestTimeoutMs());
            endpointNode.put("networkScope", endpoint.getNetworkScope());
            endpointNode.put("checksum", endpoint.getChecksum());
            return WorkflowExtensionJsonCanonicalizer.canonicalize(
                    objectMapper.writeValueAsString(frozen));
        }
        catch (JacksonException exception)
        {
            throw new ServiceException("HTTP 连接器冻结配置无法序列化", HttpStatus.ERROR);
        }
    }

    /**
     * 为协作 outbox 冻结一个只允许 POST 的正式端点快照。
     * @param endpointKey String，端点目录稳定键
     * @param path String，协作消息接收路径
     * @return String，不含密钥正文的规范冻结配置
     */
    public String freezePostEndpoint(String endpointKey, String path)
    {
        ObjectNode author = objectMapper.createObjectNode();
        author.put("endpointKey", endpointKey);
        author.put("method", "POST");
        author.put("path", path);
        // 协作 outbox 自己持久化重试，因此这里只复用端点冻结和认证能力。
        String frozenConfig = freezeConfig(author, true);
        requireCollaborationAuthentication(readFrozenConfig(frozenConfig).endpoint());
        return frozenConfig;
    }

    /**
     * 使用 HTTP 连接器已经冻结的端点、网络范围和认证配置投递协作 JSON。
     * @param frozenConfigJson String，部署时冻结的 HTTP 端点配置
     * @param idempotencyKey String，出站消息稳定幂等键
     * @param body byte[]，有界协作请求 JSON
     * @return WorkflowHttpDeliveryResult，HTTP 状态和有界响应正文
     */
    public WorkflowHttpDeliveryResult postFrozenJson(String frozenConfigJson,
            String idempotencyKey, byte[] body)
    {
        if (body == null || body.length == 0 || body.length > MAX_BODY_BYTES)
        {
            throw new ServiceException("协作消息请求正文大小不合法", HttpStatus.ERROR);
        }
        try
        {
            JsonNode config = objectMapper.readTree(frozenConfigJson);
            FrozenConfig frozen = parseFrozen(config);
            if (!"POST".equals(frozen.author().method()))
            {
                throw new ServiceException("协作消息端点只允许 POST", HttpStatus.ERROR);
            }
            requireCollaborationAuthentication(frozen.endpoint());
            HttpResponse<InputStream> response = sendBytes(frozen, idempotencyKey, body);
            return new WorkflowHttpDeliveryResult(response.statusCode(),
                    readBounded(response.body(), MAX_BODY_BYTES));
        }
        catch (HttpTimeoutException exception)
        {
            throw new ServiceException("协作消息 HTTP 投递超时", HttpStatus.ERROR)
                    .setSubCode("COLLAB_OUTBOX_TIMEOUT");
        }
        catch (IOException exception)
        {
            throw new ServiceException("协作消息 HTTP 网络投递失败", HttpStatus.ERROR)
                    .setSubCode("COLLAB_OUTBOX_IO_ERROR");
        }
        catch (InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            throw new ServiceException("协作消息 HTTP 投递被中断", HttpStatus.ERROR)
                    .setSubCode("COLLAB_OUTBOX_INTERRUPTED");
        }
        catch (JacksonException exception)
        {
            throw new ServiceException("协作消息冻结端点配置损坏", HttpStatus.ERROR)
                    .setSubCode("COLLAB_OUTBOX_CONFIG_INVALID");
        }
    }

    /**
     * 解析刚冻结的协作端点配置，部署期也复用运行期的完整快照校验。
     * @param frozenConfigJson String，不含密钥正文的冻结配置 JSON
     * @return FrozenConfig，已经通过端点摘要、方法和路径校验的配置
     */
    private FrozenConfig readFrozenConfig(String frozenConfigJson)
    {
        try
        {
            return parseFrozen(objectMapper.readTree(frozenConfigJson));
        }
        catch (JacksonException exception)
        {
            throw new ServiceException("协作消息冻结端点配置损坏", HttpStatus.ERROR)
                    .setSubCode("COLLAB_OUTBOX_CONFIG_INVALID");
        }
    }

    /**
     * 强制协作协议使用接收端实际支持的跨系统认证方式，禁止部署无认证或不兼容 Bearer 端点。
     * @param endpoint EndpointSnapshot，已经冻结且通过摘要校验的端点快照
     * @return void，认证类型或请求头不兼容时拒绝部署和运行
     */
    private void requireCollaborationAuthentication(EndpointSnapshot endpoint)
    {
        if (!"API_KEY".equals(endpoint.authType())
                || !COLLABORATION_TOKEN_HEADER.equalsIgnoreCase(endpoint.apiKeyHeader()))
        {
            throw new ServiceException(
                    "协作消息端点必须使用 X-Integration-Token API Key 认证",
                    HttpStatus.BAD_REQUEST)
                    .setSubCode("COLLAB_OUTBOX_AUTH_REQUIRED");
        }
    }

    /**
     * 复核运行快照并执行一次 HTTP 请求；异常直接交回 Flowable Job 重试和死信。
     * @param execution DelegateExecution，Flowable 当前执行上下文
     * @param snapshot WfDeployExtensionSnapshot，已通过外层摘要校验的部署快照
     * @param config JsonNode，冻结节点配置
     * @return void，成功时可写入一个 HTTP 状态变量
     */
    public void execute(DelegateExecution execution, WfDeployExtensionSnapshot snapshot,
            JsonNode config)
    {
        FrozenConfig frozen = parseFrozen(config);
        byte[] requestBody = requestBody(execution, frozen.author());
        String payloadSha256 = payloadSha256(requestBody);
        String idempotencyKey = WorkflowExtensionChecksum.sha256(
                execution.getProcessInstanceId(), execution.getId(),
                execution.getCurrentActivityId(), payloadSha256);
        long started = System.nanoTime();
        Integer responseStatus = null;
        try
        {
            HttpResponse<InputStream> response = sendBytes(frozen, idempotencyKey, requestBody);
            responseStatus = response.statusCode();
            byte[] responseBytes = readBounded(response.body(), MAX_BODY_BYTES);
            if (response.statusCode() < 200 || response.statusCode() >= 300)
            {
                throw new ServiceException("HTTP 连接器返回非成功状态", HttpStatus.ERROR)
                        .setSubCode("WORKFLOW_CONNECTOR_HTTP_STATUS");
            }
            setStatusVariable(execution, frozen.author().statusVariable(), response.statusCode());
            recordAttempt(execution, started, true, String.valueOf(response.statusCode()));
        }
        catch (InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            recordAttempt(execution, started, false, "INTERRUPTED");
            throw new ServiceException("HTTP 连接器调用被中断", HttpStatus.ERROR)
                    .setSubCode("WORKFLOW_CONNECTOR_HTTP_INTERRUPTED");
        }
        catch (HttpTimeoutException exception)
        {
            recordAttempt(execution, started, false, "TIMEOUT");
            throw new ServiceException("HTTP 连接器调用超时", HttpStatus.ERROR)
                    .setSubCode("WORKFLOW_CONNECTOR_HTTP_TIMEOUT");
        }
        catch (IOException exception)
        {
            recordAttempt(execution, started, false, "IO_ERROR");
            throw new ServiceException("HTTP 连接器网络调用失败", HttpStatus.ERROR)
                    .setSubCode("WORKFLOW_CONNECTOR_HTTP_IO_ERROR");
        }
        catch (ResponseTooLargeException exception)
        {
            recordAttempt(execution, started, false, "RESPONSE_TOO_LARGE");
            throw new ServiceException(exception.getMessage(), HttpStatus.ERROR)
                    .setSubCode("WORKFLOW_CONNECTOR_HTTP_RESPONSE_TOO_LARGE");
        }
        catch (ServiceException exception)
        {
            recordAttempt(execution, started, false,
                    responseStatus == null ? "VALIDATION_ERROR" : String.valueOf(responseStatus));
            throw exception;
        }
        catch (RuntimeException exception)
        {
            recordAttempt(execution, started, false, "RUNTIME_ERROR");
            throw exception;
        }
    }

    /**
     * 复算冻结配置并返回规范文本，供 Delegate 进行三层漂移校验。
     * @param config JsonNode，部署快照配置
     * @return String，规范冻结配置
     */
    public String validateFrozenConfig(JsonNode config)
    {
        FrozenConfig frozen = parseFrozen(config);
        try
        {
            ObjectNode normalized = normalizedAuthor(frozen.author());
            normalized.set("endpointSnapshot", frozen.endpointNode().deepCopy());
            return WorkflowExtensionJsonCanonicalizer.canonicalize(
                    objectMapper.writeValueAsString(normalized));
        }
        catch (JacksonException exception)
        {
            throw new ServiceException("HTTP 冻结配置无法规范化", HttpStatus.ERROR);
        }
    }

    /**
     * 使用冻结端点发送可选的有界字节正文，连接器和协作 outbox 共用同一安全出口。
     * @param frozen FrozenConfig，已通过摘要复核的冻结端点
     * @param idempotencyKey String，稳定幂等键
     * @param body byte[]，可空请求正文
     * @return HttpResponse&lt;InputStream&gt;，未读取正文的原始响应
     * @throws IOException 网络失败
     * @throws InterruptedException 调用线程被中断
     */
    private HttpResponse<InputStream> sendBytes(FrozenConfig frozen, String idempotencyKey,
            byte[] body) throws IOException, InterruptedException
    {
        EndpointSnapshot endpoint = frozen.endpoint();
        URI base = URI.create(endpoint.baseUrl());
        requireNetworkScope(base.getHost(), endpoint.networkScope());
        URI target = base.resolve(frozen.author().path());
        if (!base.getScheme().equals(target.getScheme()) || !base.getHost().equals(target.getHost())
                || base.getPort() != target.getPort())
        {
            throw new ServiceException("HTTP 连接器目标越过冻结端点", HttpStatus.ERROR);
        }

        HttpRequest.Builder request = HttpRequest.newBuilder(target)
                .timeout(Duration.ofMillis(endpoint.requestTimeoutMs()))
                .header("Accept", "application/json")
                .header("Idempotency-Key", idempotencyKey);
        applyAuthentication(request, endpoint);
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body);
        if (body != null)
        {
            request.header("Content-Type", "application/json; charset=UTF-8");
        }
        request.method(frozen.author().method(), publisher);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(endpoint.connectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
    }

    /**
     * 读取显式白名单流程变量并序列化为有界 JSON 请求正文。
     * @param execution DelegateExecution，流程执行上下文
     * @param config ParsedConfig，作者配置
     * @return byte[]，无 bodyVariable 时返回 null
     */
    private byte[] requestBody(DelegateExecution execution, ParsedConfig config)
    {
        if (config.bodyVariable() == null)
        {
            return null;
        }
        if (!execution.hasVariable(config.bodyVariable()))
        {
            throw new ServiceException("HTTP 请求正文变量不存在", HttpStatus.ERROR);
        }
        try
        {
            AtomicInteger nodes = new AtomicInteger();
            JsonNode safeBody = toSafeJson(execution.getVariable(config.bodyVariable()), 0, nodes);
            byte[] body = objectMapper.writeValueAsBytes(safeBody);
            if (body.length > MAX_BODY_BYTES)
            {
                throw new ServiceException("HTTP 请求正文超过大小限制", HttpStatus.ERROR);
            }
            return body;
        }
        catch (JacksonException exception)
        {
            throw new ServiceException("HTTP 请求正文无法序列化", HttpStatus.ERROR);
        }
    }

    /**
     * 把流程变量转换为只包含 JSON 标量、数组和字符串键对象的安全树。
     * @param value Object，流程变量值
     * @param depth int，当前容器深度
     * @param nodes AtomicInteger，累计节点数
     * @return JsonNode，安全 JSON 树
     */
    private JsonNode toSafeJson(Object value, int depth, AtomicInteger nodes)
    {
        if (depth > 16 || nodes.incrementAndGet() > 1000)
        {
            throw new ServiceException("HTTP 请求正文结构超过限制", HttpStatus.ERROR);
        }
        if (value == null) return objectMapper.nullNode();
        if (value instanceof String text) return objectMapper.getNodeFactory().stringNode(text);
        if (value instanceof Boolean bool) return objectMapper.getNodeFactory().booleanNode(bool);
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
            return objectMapper.getNodeFactory().numberNode(((Number) value).longValue());
        if (value instanceof Float || value instanceof Double)
        {
            double numericValue = ((Number) value).doubleValue();
            // 外部 HTTP JSON 不允许 NaN 和 Infinity，必须在网络副作用发生前拒绝。
            if (!Double.isFinite(numericValue))
            {
                throw new ServiceException("HTTP 请求正文包含非有限数字", HttpStatus.ERROR);
            }
            return objectMapper.getNodeFactory().numberNode(numericValue);
        }
        if (value instanceof BigInteger integer) return objectMapper.getNodeFactory().numberNode(integer);
        if (value instanceof BigDecimal decimal) return objectMapper.getNodeFactory().numberNode(decimal);
        if (value instanceof List<?> list)
        {
            ArrayNode array = objectMapper.createArrayNode();
            list.forEach(item -> array.add(toSafeJson(item, depth + 1, nodes)));
            return array;
        }
        if (value instanceof Map<?, ?> map)
        {
            ObjectNode object = objectMapper.createObjectNode();
            List<String> keys = new ArrayList<>();
            for (Object key : map.keySet())
            {
                if (!(key instanceof String text) || text.length() > 128)
                {
                    throw new ServiceException("HTTP 请求正文对象键不合法", HttpStatus.ERROR);
                }
                keys.add(text);
            }
            keys.sort(String::compareTo);
            keys.forEach(key -> object.set(key, toSafeJson(map.get(key), depth + 1, nodes)));
            return object;
        }
        throw new ServiceException("HTTP 请求正文包含不受控对象类型", HttpStatus.ERROR);
    }

    /**
     * 解析并校验作者配置。
     * @param config JsonNode，作者或冻结配置
     * @param allowedFields Set&lt;String&gt;，当前阶段允许字段
     * @return ParsedConfig，规范作者字段
     */
    private ParsedConfig parseAuthor(JsonNode config, Set<String> allowedFields)
    {
        if (config == null || !config.isObject())
        {
            throw new ServiceException("HTTP 连接器配置必须是 JSON 对象", HttpStatus.BAD_REQUEST);
        }
        rejectUnknownFields(config, allowedFields, "HTTP 连接器配置");
        String endpointKey = requiredText(config, "endpointKey", 128);
        if (!endpointKey.matches("[A-Za-z][A-Za-z0-9_.-]{0,127}"))
            throw new ServiceException("HTTP 端点键不合法", HttpStatus.BAD_REQUEST);
        String method = requiredText(config, "method", 8).toUpperCase(Locale.ROOT);
        if (!Set.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(method))
            throw new ServiceException("HTTP 方法不合法", HttpStatus.BAD_REQUEST);
        String path = requiredText(config, "path", 512);
        String lowerPath = path.toLowerCase(Locale.ROOT);
        if (!REQUEST_PATH.matcher(path).matches() || path.contains("..") || path.contains("//")
                || lowerPath.contains("%2e") || lowerPath.contains("%2f")
                || lowerPath.contains("%5c"))
            throw new ServiceException("HTTP 请求路径不合法", HttpStatus.BAD_REQUEST);
        String bodyVariable = optionalVariable(config, "bodyVariable");
        String statusVariable = optionalVariable(config, "statusVariable");
        if (bodyVariable != null && ("GET".equals(method) || "DELETE".equals(method)))
            throw new ServiceException("GET 或 DELETE 连接器不能配置请求正文", HttpStatus.BAD_REQUEST);
        if (bodyVariable != null && bodyVariable.equals(statusVariable))
            throw new ServiceException("HTTP 状态变量不能覆盖请求正文变量", HttpStatus.BAD_REQUEST);
        return new ParsedConfig(endpointKey, method, path, bodyVariable, statusVariable);
    }

    /**
     * 解析冻结配置并复核端点摘要、方法和路径白名单。
     * @param config JsonNode，部署快照配置
     * @return FrozenConfig，运行所需不可变配置
     */
    private FrozenConfig parseFrozen(JsonNode config)
    {
        ParsedConfig author = parseAuthor(config, FROZEN_FIELDS);
        JsonNode endpointNode = config.get("endpointSnapshot");
        if (endpointNode == null || !endpointNode.isObject())
            throw new ServiceException("HTTP 端点部署快照不存在", HttpStatus.ERROR);
        Set<String> endpointFields = Set.of("endpointId", "endpointName", "revisionNo", "baseUrl", "allowedMethods",
                "pathPrefix", "authType", "secretRef", "apiKeyHeader", "connectTimeoutMs",
                "requestTimeoutMs", "networkScope", "checksum");
        rejectUnknownFields(endpointNode, endpointFields, "HTTP 端点部署快照");
        EndpointSnapshot endpoint = new EndpointSnapshot(
                requiredLong(endpointNode, "endpointId"), requiredText(endpointNode, "endpointName", 128),
                requiredInt(endpointNode, "revisionNo", 1, Integer.MAX_VALUE),
                requiredText(endpointNode, "baseUrl", 1024), requiredText(endpointNode, "allowedMethods", 64),
                requiredText(endpointNode, "pathPrefix", 512), requiredText(endpointNode, "authType", 16),
                optionalText(endpointNode, "secretRef", 128), optionalText(endpointNode, "apiKeyHeader", 128),
                requiredInt(endpointNode, "connectTimeoutMs", 100, 10000),
                requiredInt(endpointNode, "requestTimeoutMs", 500, 120000),
                requiredText(endpointNode, "networkScope", 16), requiredText(endpointNode, "checksum", 64));
        WfConnectorEndpoint checksumInput = endpoint.toEndpoint(author.endpointKey());
        if (!WorkflowConnectorEndpointService.endpointChecksum(checksumInput).equals(endpoint.checksum()))
            throw new ServiceException("HTTP 端点部署快照校验和不一致", HttpStatus.ERROR);
        if (!Set.of(endpoint.allowedMethods().split(",")).contains(author.method()))
            throw new ServiceException("HTTP 方法越过冻结端点白名单", HttpStatus.ERROR);
        requirePathWithinPrefix(author.path(), endpoint.pathPrefix());
        return new FrozenConfig(author, endpoint, (ObjectNode) endpointNode);
    }

    /**
     * 将作者字段按固定顺序写入 JSON 对象。
     * @param config ParsedConfig，规范作者配置
     * @return ObjectNode，稳定字段对象
     */
    private ObjectNode normalizedAuthor(ParsedConfig config)
    {
        ObjectNode normalized = objectMapper.createObjectNode();
        normalized.put("endpointKey", config.endpointKey());
        normalized.put("method", config.method());
        normalized.put("path", config.path());
        putOptional(normalized, "bodyVariable", config.bodyVariable());
        putOptional(normalized, "statusVariable", config.statusVariable());
        return normalized;
    }

    /**
     * 应用冻结认证配置，密钥只在当前请求对象中短暂存在。
     * @param builder HttpRequest.Builder，请求构造器
     * @param endpoint EndpointSnapshot，冻结端点配置
     * @return void，无认证时不修改请求
     */
    private void applyAuthentication(HttpRequest.Builder builder, EndpointSnapshot endpoint)
    {
        if ("NONE".equals(endpoint.authType())) return;
        String secret = secretResolver.requireSecret(endpoint.secretRef());
        if ("BEARER".equals(endpoint.authType()))
            builder.header("Authorization", "Bearer " + secret);
        else if ("API_KEY".equals(endpoint.authType()))
            builder.header(endpoint.apiKeyHeader(), secret);
        else
            throw new ServiceException("HTTP 端点认证类型不受支持", HttpStatus.ERROR);
    }

    /**
     * 解析 PUBLIC 网络范围并阻止 DNS 解析落到本机、私网或链路本地地址。
     * @param host String，冻结目标主机
     * @param networkScope String，PUBLIC 或 PRIVATE
     * @return void，PRIVATE 仅依赖管理员端点白名单
     */
    private void requireNetworkScope(String host, String networkScope)
    {
        if ("PRIVATE".equals(networkScope)) return;
        if (!"PUBLIC".equals(networkScope))
            throw new ServiceException("HTTP 端点网络范围不受支持", HttpStatus.ERROR);
        try
        {
            for (InetAddress address : InetAddress.getAllByName(host))
            {
                byte[] raw = address.getAddress();
                boolean uniqueLocalV6 = raw.length == 16 && (raw[0] & 0xfe) == 0xfc;
                if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                        || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                        || address.isMulticastAddress() || uniqueLocalV6)
                    throw new ServiceException("PUBLIC HTTP 端点解析到非公网地址", HttpStatus.ERROR);
            }
        }
        catch (IOException exception)
        {
            throw new ServiceException("HTTP 端点主机解析失败", HttpStatus.ERROR);
        }
    }

    /**
     * 校验请求路径处于冻结前缀边界内。
     * @param path String，请求绝对路径
     * @param prefix String，冻结允许前缀
     * @return void，越界时抛出业务异常
     */
    private void requirePathWithinPrefix(String path, String prefix)
    {
        if (!("/".equals(prefix) || path.equals(prefix) || path.startsWith(prefix + "/")))
            throw new ServiceException("HTTP 请求路径越过端点白名单", HttpStatus.BAD_REQUEST);
    }

    /**
     * 读取最多 limit 字节并在超限时失败关闭。
     * @param stream InputStream，响应正文流
     * @param limit int，最大字节数
     * @return byte[]，完整有界正文
     * @throws IOException 读取失败
     */
    private byte[] readBounded(InputStream stream, int limit) throws IOException
    {
        try (InputStream input = stream)
        {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit)
                throw new ResponseTooLargeException("HTTP 响应正文超过大小限制");
            return bytes;
        }
    }

    /**
     * 计算请求正文摘要；无正文按空字节数组计算，确保 GET/DELETE 重试键稳定。
     * @param body byte[]，可空受控请求正文
     * @return String，64 位小写 SHA-256
     */
    private String payloadSha256(byte[] body)
    {
        try
        {
            byte[] payload = body == null ? new byte[0] : body;
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(payload));
        }
        catch (NoSuchAlgorithmException exception)
        {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", exception);
        }
    }

    /**
     * 写入可选 HTTP 状态变量。
     * @param execution DelegateExecution，流程执行上下文
     * @param variable String，可空结果变量名
     * @param status Integer，可空 HTTP 状态
     * @return void，未配置变量时不写入
     */
    private void setStatusVariable(DelegateExecution execution, String variable, Integer status)
    {
        if (variable != null && status != null) execution.setVariable(variable, status.longValue());
    }

    /**
     * 记录单次 Flowable Job 尝试的结构化日志和指标，不输出端点密钥、请求头或正文。
     * @param execution DelegateExecution，当前 Flowable 执行上下文
     * @param started long，System.nanoTime 起点
     * @param success boolean，本次尝试是否成功
     * @param resultCode String，低敏稳定结果码或 HTTP 状态码
     * @return void，日志和指标失败不得改变连接器业务结果
     */
    private void recordAttempt(DelegateExecution execution, long started,
            boolean success, String resultCode)
    {
        long durationMs = elapsedMillis(started);
        connectorMetrics.record("HTTP", success, durationMs);
        log.info("operation=workflowConnector type=HTTP traceId={} processInstanceId={} "
                        + "executionId={} elementId={} resultCode={} durationMs={}",
                safeTraceId(), execution.getProcessInstanceId(), execution.getId(),
                execution.getCurrentActivityId(), resultCode, durationMs);
    }

    /**
     * 读取当前链路标识，未配置 MDC 时使用空字符串且不伪造业务值。
     * @return String，当前 traceId 或空字符串
     */
    private String safeTraceId()
    {
        String traceId = MDC.get("traceId");
        return traceId == null ? "" : traceId;
    }

    /**
     * 把纳秒起点转换为非负毫秒耗时。
     * @param started long，System.nanoTime 起点
     * @return long，非负毫秒数
     */
    private long elapsedMillis(long started)
    {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }

    /**
     * 读取必填文本字段。
     * @param object JsonNode，父对象
     * @param name String，字段名
     * @param max int，最大长度
     * @return String，去除首尾空白文本
     */
    private String requiredText(JsonNode object, String name, int max)
    {
        String value = optionalText(object, name, max);
        if (value == null) throw new ServiceException("HTTP 配置字段缺失: " + name, HttpStatus.BAD_REQUEST);
        return value;
    }

    /**
     * 读取可选文本字段。
     * @param object JsonNode，父对象
     * @param name String，字段名
     * @param max int，最大长度
     * @return String，规范文本或 null
     */
    private String optionalText(JsonNode object, String name, int max)
    {
        JsonNode node = object.get(name);
        if (node == null || node.isNull()) return null;
        if (!node.isTextual()) throw new ServiceException("HTTP 配置字段类型错误: " + name, HttpStatus.BAD_REQUEST);
        String value = node.asText().trim();
        if (value.isEmpty() || value.length() > max || value.chars().anyMatch(Character::isISOControl))
            throw new ServiceException("HTTP 配置字段内容不合法: " + name, HttpStatus.BAD_REQUEST);
        return value;
    }

    /**
     * 读取可选安全变量名。
     * @param object JsonNode，父对象
     * @param name String，字段名
     * @return String，变量名或 null
     */
    private String optionalVariable(JsonNode object, String name)
    {
        String value = optionalText(object, name, 128);
        if (value != null && !VARIABLE_NAME.matcher(value).matches())
            throw new ServiceException("HTTP 流程变量名不合法", HttpStatus.BAD_REQUEST);
        return value;
    }

    /**
     * 读取必填正整数。
     * @param object JsonNode，父对象
     * @param name String，字段名
     * @param min int，最小值
     * @param max int，最大值
     * @return int，范围内整数
     */
    private int requiredInt(JsonNode object, String name, int min, int max)
    {
        JsonNode node = object.get(name);
        if (node == null || !node.canConvertToInt())
            throw new ServiceException("HTTP 配置整数缺失: " + name, HttpStatus.BAD_REQUEST);
        int value = node.asInt();
        if (value < min || value > max)
            throw new ServiceException("HTTP 配置整数越界: " + name, HttpStatus.BAD_REQUEST);
        return value;
    }

    /**
     * 读取必填正长整数。
     * @param object JsonNode，父对象
     * @param name String，字段名
     * @return long，正长整数
     */
    private long requiredLong(JsonNode object, String name)
    {
        JsonNode node = object.get(name);
        if (node == null || !node.canConvertToLong() || node.asLong() <= 0)
            throw new ServiceException("HTTP 配置主键不合法: " + name, HttpStatus.BAD_REQUEST);
        return node.asLong();
    }

    /**
     * 拒绝 JSON 对象中的未知字段。
     * @param object JsonNode，待检查对象
     * @param allowed Set&lt;String&gt;，允许字段
     * @param label String，错误上下文
     * @return void，发现未知字段时失败关闭
     */
    private void rejectUnknownFields(JsonNode object, Set<String> allowed, String label)
    {
        for (String name : object.propertyNames())
        {
            if (!allowed.contains(name))
                throw new ServiceException(label + "包含未知字段: " + name, HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 写入可选字符串字段。
     * @param object ObjectNode，目标对象
     * @param name String，字段名
     * @param value String，可空值
     * @return void，空值不写入
     */
    private void putOptional(ObjectNode object, String name, String value)
    {
        if (value != null) object.put(name, value);
    }

    /**
     * 判断主机文本是否是显式本机地址。
     * @param host String，URI 主机
     * @return boolean，localhost 或回环 IP 返回 true
     */
    private boolean isLoopbackHost(String host)
    {
        if (host == null) return false;
        String normalized = host.toLowerCase(Locale.ROOT);
        return "localhost".equals(normalized) || "127.0.0.1".equals(normalized)
                || "::1".equals(normalized) || "[::1]".equals(normalized);
    }

    /** 作者可见节点配置。 */
    private record ParsedConfig(String endpointKey, String method, String path,
            String bodyVariable, String statusVariable) { }

    /** 端点不可变部署快照。 */
    private record EndpointSnapshot(long endpointId, String endpointName, int revisionNo, String baseUrl,
            String allowedMethods, String pathPrefix, String authType, String secretRef,
            String apiKeyHeader, int connectTimeoutMs, int requestTimeoutMs,
            String networkScope, String checksum)
    {
        /**
         * 转换为端点摘要输入对象。
         * @param endpointKey String，作者配置引用的稳定键
         * @return WfConnectorEndpoint，字段足以复算摘要的对象
         */
        private WfConnectorEndpoint toEndpoint(String endpointKey)
        {
            WfConnectorEndpoint endpoint = new WfConnectorEndpoint();
            endpoint.setEndpointId(endpointId);
            endpoint.setEndpointKey(endpointKey);
            endpoint.setEndpointName(endpointName);
            endpoint.setBaseUrl(baseUrl);
            endpoint.setAllowedMethods(allowedMethods);
            endpoint.setPathPrefix(pathPrefix);
            endpoint.setAuthType(authType);
            endpoint.setSecretRef(secretRef);
            endpoint.setApiKeyHeader(apiKeyHeader);
            endpoint.setConnectTimeoutMs(connectTimeoutMs);
            endpoint.setRequestTimeoutMs(requestTimeoutMs);
            endpoint.setNetworkScope(networkScope);
            endpoint.setRevisionNo(revisionNo);
            return endpoint;
        }
    }

    /** 完整运行配置和原始端点节点。 */
    private record FrozenConfig(ParsedConfig author, EndpointSnapshot endpoint,
            ObjectNode endpointNode) { }

    /**
     * 协作 outbox 使用的有界 HTTP 投递结果。
     * @param statusCode int，HTTP 状态码
     * @param body byte[]，最多 64 KiB 的响应正文
     */
    public record WorkflowHttpDeliveryResult(int statusCode, byte[] body)
    {
        /**
         * 防止调用方修改内部响应数组。
         * @return byte[]，响应正文副本
         */
        @Override
        public byte[] body()
        {
            return body == null ? new byte[0] : body.clone();
        }
    }

    /** 表示响应正文已越过安全上限，调用方必须记录独立稳定错误码。 */
    private static final class ResponseTooLargeException extends RuntimeException
    {
        private static final long serialVersionUID = 1L;

        /**
         * 创建响应正文超限异常。
         * @param message String，对外稳定错误消息
         * @return 无返回值，构造后由执行分支抛出
         */
        private ResponseTooLargeException(String message)
        {
            super(message);
        }
    }
}
