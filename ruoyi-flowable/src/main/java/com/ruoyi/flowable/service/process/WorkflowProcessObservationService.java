package com.ruoyi.flowable.service.process;

import java.time.Instant;
import org.springframework.stereotype.Service;
import com.ruoyi.flowable.authorization.WorkflowProcessAccessService;
import com.ruoyi.flowable.domain.dto.WorkflowProcessDetailQueryDto;
import com.ruoyi.flowable.domain.vo.WorkflowProcessDetailView;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;

/** 详情和等待事实在同一可重复读事务中投影，共用本次身份与实例授权结果。 */
@Service
public class WorkflowProcessObservationService
{
    private final WorkflowEngineOperations operations;
    private final WorkflowProcessDetailService details;
    private final WorkflowProcessWaitingService waiting;
    private final WorkflowProcessAccessService access;
    private final WorkflowIdentityResolver identities;

    public WorkflowProcessObservationService(WorkflowEngineOperations operations,
            WorkflowProcessDetailService details, WorkflowProcessWaitingService waiting,
            WorkflowProcessAccessService access, WorkflowIdentityResolver identities)
    {
        this.operations = operations;
        this.details = details;
        this.waiting = waiting;
        this.access = access;
        this.identities = identities;
    }

    public record View(Instant observedAt, WorkflowProcessDetailView detail, WorkflowProcessWaitingService.View waiting) { }

    public record RuntimeView(Instant observedAt, WorkflowProcessDetailService.RuntimeState runtime,
            WorkflowProcessWaitingService.View waiting) { }

    public RuntimeView queryRuntime(WorkflowProcessDetailQueryDto request)
    {
        String instanceId = details.requireInstanceId(request);
        String taskId = details.requireTaskId(request);
        return operations.read(() -> {
            var actor = identities.resolveCurrentIdentity();
            var instance = access.requireReadableInstance(instanceId, actor);
            var runtime = details.readRuntime(instance, taskId, actor);
            return new RuntimeView(Instant.now(), runtime, waiting.project(instance, runtime.model()));
        });
    }

    public View query(WorkflowProcessDetailQueryDto request)
    {
        String instanceId = details.requireInstanceId(request);
        String taskId = details.requireTaskId(request);
        return operations.read(() -> {
            var actor = identities.resolveCurrentIdentity();
            var instance = access.requireReadableInstance(instanceId, actor);
            var detail = details.buildDetail(instance, taskId, actor);
            return new View(Instant.now(), detail.detail(), waiting.project(instance, detail.model()));
        });
    }
}
