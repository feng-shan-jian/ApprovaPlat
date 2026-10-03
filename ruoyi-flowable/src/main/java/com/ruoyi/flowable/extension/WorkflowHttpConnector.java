package com.ruoyi.flowable.extension;

import com.ruoyi.flowable.service.integration.WorkflowHttpExecution;
import com.ruoyi.flowable.service.integration.WorkflowHttpExecution.FrozenConfig;
import com.ruoyi.flowable.service.integration.WorkflowHttpExecution.ParsedConfig;
import com.ruoyi.flowable.service.integration.WorkflowHttpExecution.ResponseTooLargeException;
import static com.ruoyi.flowable.service.integration.WorkflowHttpExecution.MAX_BODY_BYTES;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.http.HttpTimeoutException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.flowable.engine.delegate.DelegateExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.runtime.WorkflowConnectorMetrics;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Flowable HTTP 适配器：读取变量、调用一次共享发送能力、写回状态并记录 Job 尝试。 */
@Component
public class WorkflowHttpConnector
{
    public static final String IMPLEMENTATION_KEY = "HTTP_CONNECTOR_V1";
    private static final Logger log = LoggerFactory.getLogger(WorkflowHttpConnector.class);
    private final WorkflowHttpExecution httpExecution;
    private final WorkflowConnectorMetrics connectorMetrics;
    private final ObjectMapper objectMapper = JsonMapper.shared();

    public WorkflowHttpConnector(WorkflowHttpExecution httpExecution, WorkflowConnectorMetrics connectorMetrics)
    {
        this.httpExecution = httpExecution;
        this.connectorMetrics = connectorMetrics;
    }

    /**
     * 复核运行快照并执行一次 HTTP 请求；异常直接交回 Flowable Job 重试和死信。
     * @param execution DelegateExecution，Flowable 当前执行上下文
     * @param config JsonNode，冻结节点配置
     * @return void，成功时可写入一个 HTTP 状态变量
     */
    public void execute(DelegateExecution execution, JsonNode config)
    {
        FrozenConfig frozen = WorkflowHttpExecution.parseFrozen(config);
        byte[] requestBody = requestBody(execution, frozen.author());
        String payloadSha256 = payloadSha256(requestBody);
        String idempotencyKey = WorkflowExtensionChecksum.sha256(
                execution.getProcessInstanceId(), execution.getId(),
                execution.getCurrentActivityId(), payloadSha256);
        long started = System.nanoTime();
        Integer responseStatus = null;
        try
        {
            var response = httpExecution.send(frozen, idempotencyKey, requestBody);
            responseStatus = response.statusCode();
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

}
