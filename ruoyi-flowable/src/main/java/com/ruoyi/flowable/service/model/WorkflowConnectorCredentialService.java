package com.ruoyi.flowable.service.model;

import java.util.List;
import org.springframework.stereotype.Service;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfConnectorEndpoint;
import com.ruoyi.flowable.domain.dto.WorkflowConnectorEndpointRequest;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.extension.WorkflowManagedConnectorSecrets;
import com.ruoyi.flowable.mapper.WfConnectorEndpointMapper;

@Service
public class WorkflowConnectorCredentialService
{
    private final WorkflowEngineOperations operations;
    private final WfConnectorEndpointMapper endpoints;
    private final WorkflowConnectorEndpointService endpointService;
    private final WorkflowManagedConnectorSecrets secrets;
    public record State(int endpointRevision, WorkflowManagedConnectorSecrets.Metadata credential) { }
    public WorkflowConnectorCredentialService(WorkflowEngineOperations operations, WfConnectorEndpointMapper endpoints,
            WorkflowConnectorEndpointService endpointService, WorkflowManagedConnectorSecrets secrets)
    { this.operations = operations; this.endpoints = endpoints; this.endpointService = endpointService; this.secrets = secrets; }

    public State state(long endpointId)
    {
        return operations.read(() -> {
            var row = endpoints.selectList().stream().filter(endpoint -> endpoint.endpointId() == endpointId).findFirst()
                    .orElseThrow(() -> new ServiceException("端点不存在", 404));
            return new State(row.revisionNo(), secrets.metadata(row.secretRef()));
        });
    }

    public State bind(long endpointId, int expectedEndpointRevision, int expectedCredentialRevision,
            String secret, String authType, String header)
    {
        return operations.writeAsCurrentUser(actor -> {
            WfConnectorEndpoint endpoint = requireEndpoint(endpointId, expectedEndpointRevision);
            var metadata = secrets.metadata(endpoint.getSecretRef());
            if (metadata.revision() != expectedCredentialRevision) throw new ServiceException("凭据修订已变化", 409);
            var next = metadata.revision() > 0 && "ACTIVE".equals(metadata.status())
                    ? secrets.rotate(endpoint.getSecretRef(), expectedCredentialRevision, secret, actor.userId())
                    : secrets.create(secret, actor.userId());
            var request = new WorkflowConnectorEndpointRequest(endpoint.getEndpointKey(), endpoint.getEndpointName(), endpoint.getBaseUrl(),
                    List.of(endpoint.getAllowedMethods().split(",")), endpoint.getPathPrefix(), authType, next.reference(),
                    "API_KEY".equals(authType) ? header : null, endpoint.getConnectTimeoutMs(), endpoint.getRequestTimeoutMs(), endpoint.getNetworkScope());
            int revision = endpointService.update(endpointId, request);
            return new State(revision, next);
        });
    }

    public void revoke(long endpointId, int expectedEndpointRevision, int expectedCredentialRevision)
    {
        operations.writeAsCurrentUser(actor -> {
            var endpoint = requireEndpoint(endpointId, expectedEndpointRevision);
            secrets.revoke(endpoint.getSecretRef(), expectedCredentialRevision, actor.userId());
            return null;
        });
    }

    private WfConnectorEndpoint requireEndpoint(long id, int revision)
    {
        var endpoint = endpoints.selectByIdForUpdate(id);
        if (endpoint == null) throw new ServiceException("端点不存在", 404);
        if (endpoint.getRevisionNo() != revision) throw new ServiceException("端点修订已变化", 409);
        return endpoint;
    }
}
