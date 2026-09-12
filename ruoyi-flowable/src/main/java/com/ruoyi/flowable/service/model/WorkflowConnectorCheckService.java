package com.ruoyi.flowable.service.model;

import java.util.UUID;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Service;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.extension.WorkflowHttpConnector;
import com.ruoyi.flowable.mapper.WfConnectorEndpointMapper;
import tools.jackson.databind.json.JsonMapper;

@Service
public class WorkflowConnectorCheckService
{
    private final WorkflowEngineOperations operations;
    private final WfConnectorEndpointMapper endpoints;
    private final WorkflowHttpConnector http;
    public record Result(boolean configurationReady, boolean networkAuthenticated, String message) { }
    public WorkflowConnectorCheckService(WorkflowEngineOperations operations, WfConnectorEndpointMapper endpoints,
            WorkflowHttpConnector http)
    { this.operations = operations; this.endpoints = endpoints; this.http = http; }

    public Result check(long endpointId)
    {
        return operations.writeAsCurrentUser(actor -> {
            var endpoint = endpoints.selectByIdForUpdate(endpointId);
            if (endpoint == null) throw new ServiceException("端点不存在", 404);
            String frozen = http.freezePostEndpoint(endpoint.getEndpointKey(), "/workflow/runtime-event/auth-check");
            String nonce = UUID.randomUUID().toString();
            var response = http.postFrozenJson(frozen, "auth-check-" + nonce,
                    ("{\"nonce\":\"" + nonce + "\"}").getBytes(StandardCharsets.UTF_8));
            try
            {
                var body = JsonMapper.shared().readTree(response.body());
                if (response.statusCode() != 200 || body.path("code").asInt() != 200
                        || !body.path("data").path("authenticated").asBoolean()
                        || !"APPROVA_MESSAGE_AUTH_V1".equals(body.path("data").path("protocol").asText())
                        || !nonce.equals(body.path("data").path("nonce").asText())) throw failedCheck();
            }
            catch (tools.jackson.core.JacksonException exception) { throw failedCheck(); }
            return new Result(true, true, "配置与消息认证检查通过；尚未投递业务消息，请用隔离流程验证收发结果");
        });
    }

    private ServiceException failedCheck()
    {
        return new ServiceException("消息认证检查未通过，请核对令牌、MESSAGE 范围及接收服务是否支持认证检查协议", 400)
                .setSubCode("CONNECTOR_AUTH_CHECK_FAILED");
    }
}
