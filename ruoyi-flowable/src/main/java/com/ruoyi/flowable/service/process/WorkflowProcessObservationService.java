package com.ruoyi.flowable.service.process;

import java.time.Instant;
import org.springframework.stereotype.Service;
import com.ruoyi.flowable.domain.dto.WorkflowProcessDetailQueryDto;
import com.ruoyi.flowable.domain.vo.WorkflowProcessDetailView;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;

/** 详情和等待事实在同一可重复读事务中投影，避免轮询拼接两个时点的运行状态。 */
@Service
public class WorkflowProcessObservationService
{
    private final WorkflowEngineOperations operations;
    private final WorkflowProcessDetailService details;
    private final WorkflowProcessWaitingService waiting;

    public WorkflowProcessObservationService(WorkflowEngineOperations operations,
            WorkflowProcessDetailService details, WorkflowProcessWaitingService waiting)
    {
        this.operations = operations; this.details = details; this.waiting = waiting;
    }

    public record View(Instant observedAt, WorkflowProcessDetailView detail, WorkflowProcessWaitingService.View waiting) { }

    public View query(WorkflowProcessDetailQueryDto request)
    {
        return operations.read(() -> {
            var detail = details.getDetail(request);
            return new View(Instant.now(), detail, waiting.query(detail.processInstanceId()));
        });
    }
}
