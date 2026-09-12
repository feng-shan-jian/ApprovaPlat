package com.ruoyi.flowable.service.model;

import org.springframework.stereotype.Service;
import com.ruoyi.flowable.domain.dto.WorkflowIntegrationCredentialCreateRequest;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.service.process.WorkflowIntegrationCredentialService;

/** 新令牌生成、受管加密与端点绑定共享一个事务；明文不离开服务边界。 */
@Service
public class WorkflowIntegrationCredentialBindingService
{
    private final WorkflowEngineOperations operations;
    private final WorkflowIntegrationCredentialService integrations;
    private final WorkflowConnectorCredentialService connectors;

    public record Result(long credentialId, WorkflowConnectorCredentialService.State endpoint) { }

    public WorkflowIntegrationCredentialBindingService(WorkflowEngineOperations operations,
            WorkflowIntegrationCredentialService integrations, WorkflowConnectorCredentialService connectors)
    {
        this.operations = operations;
        this.integrations = integrations;
        this.connectors = connectors;
    }

    public Result create(long endpointId, int endpointRevision, int credentialRevision,
            WorkflowIntegrationCredentialCreateRequest request)
    {
        return operations.writeAsCurrentUser(actor -> {
            var credential = integrations.create(request);
            var bound = connectors.bind(endpointId, endpointRevision, credentialRevision,
                    credential.token(), "API_KEY", "X-Integration-Token");
            return new Result(credential.credentialId(), bound);
        });
    }
}
