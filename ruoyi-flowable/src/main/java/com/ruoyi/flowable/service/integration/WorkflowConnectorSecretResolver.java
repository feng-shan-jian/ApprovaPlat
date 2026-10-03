package com.ruoyi.flowable.service.integration;

import com.ruoyi.flowable.mapper.WfConnectorEndpointMapper;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;

/**
 * HTTP 连接器外部密钥引用解析器。
 *
 * 支持固定环境变量命名空间和受管凭据引用；BPMN、日志和调用台账均不保存密钥正文。
 */
@Component
public class WorkflowConnectorSecretResolver
{
    private final WorkflowManagedConnectorSecrets managed;
    private final WfConnectorEndpointMapper endpointMapper;
    public WorkflowConnectorSecretResolver(WorkflowManagedConnectorSecrets managed, WfConnectorEndpointMapper endpointMapper)
    {
        this.managed = managed;
        this.endpointMapper = endpointMapper;
    }
    /** 连接器密钥环境变量命名空间。 */
    private static final Pattern SECRET_REF = Pattern.compile("WORKFLOW_CONNECTOR_SECRET_[A-Z0-9_]{1,96}");

    /**
     * 解析必须存在的连接器密钥。
     * @param secretRef String，数据库冻结的环境变量引用
     * @return String，当前进程注入的密钥正文；调用方不得记录
     */
    public String requireSecret(String secretRef)
    {
        if (secretRef != null && secretRef.matches(WorkflowManagedConnectorSecrets.REF_PATTERN))
            return managed.requireSecret(secretRef);
        if (secretRef == null || !SECRET_REF.matcher(secretRef).matches())
        {
            throw new ServiceException("连接器密钥引用不合法", HttpStatus.ERROR);
        }
        String secret = System.getenv(secretRef);
        if (secret == null || secret.isBlank() || secret.length() > 8192)
        {
            throw new ServiceException("连接器外部密钥未注入或长度不合法", HttpStatus.ERROR);
        }
        return secret;
    }
    /** 新受管 provider 每次投递核对当前端点状态；旧 ENV 冻结行为保持原合同。 */
    public void requireManagedRuntimeEndpoint(long endpointId, String reference)
    {
        if (reference == null || !reference.matches(WorkflowManagedConnectorSecrets.REF_PATTERN)) return;
        var endpoint = endpointMapper.selectByIdForUpdate(endpointId);
        if (endpoint == null || !"ENABLED".equals(endpoint.getStatus()) || !reference.equals(endpoint.getSecretRef()))
            throw new ServiceException("受管凭据端点已停用或绑定已变更", 409);
    }

}
