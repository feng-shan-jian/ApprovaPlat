package com.ruoyi.flowable.service.process;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.flowable.bpmn.model.ReceiveTask;
import org.flowable.engine.ManagementService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.job.api.Job;
import org.springframework.stereotype.Service;
import com.ruoyi.flowable.authorization.WorkflowProcessAccessService;
import com.ruoyi.flowable.authorization.WorkflowProcessAccessSnapshot;
import com.ruoyi.flowable.config.WorkflowRuntimeProperties;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.runtime.WorkflowRuntimeMetrics;

/** 对象授权后的实时等待事实；不返回变量、凭据或作业异常正文。 */
@Service
public class WorkflowProcessWaitingService
{
    private final WorkflowProcessAccessService access;
    private final WorkflowEngineOperations operations;
    private final RuntimeService runtime;
    private final RepositoryService repository;
    private final ManagementService management;
    private final WorkflowRuntimeMetrics metrics;
    private final WorkflowRuntimeProperties properties;

    public WorkflowProcessWaitingService(WorkflowProcessAccessService access, WorkflowEngineOperations operations,
            RuntimeService runtime, RepositoryService repository, ManagementService management,
            WorkflowRuntimeMetrics metrics, WorkflowRuntimeProperties properties)
    {
        this.access = access; this.operations = operations; this.runtime = runtime; this.repository = repository;
        this.management = management; this.metrics = metrics; this.properties = properties;
    }

    public record Waiting(String type, String nodeId, String eventName, String executionId,
            String jobId, Instant dueTime, Integer retries, String state) { }
    public record View(String processInstanceId, boolean localTimerExecutionReady, boolean truncated, List<Waiting> waits) { }

    public View query(String instanceId)
    {
        return operations.read(() -> project(access.requireReadableInstance(instanceId)));
    }

    /** 本次观察事务已经完成实例授权，继续查询新的等待事实。 */
    View project(WorkflowProcessAccessSnapshot authorized)
    {
        String instanceId = authorized.processInstanceId();
        List<Waiting> rows = new ArrayList<>();
        boolean truncated = false;
        if (authorized.endTime() == null)
        {
            var process = repository.getBpmnModel(authorized.processDefinitionId());
            var subscriptions = runtime.createEventSubscriptionQuery().processInstanceId(instanceId).listPage(0, 201);
            for (var subscription : subscriptions)
                rows.add(new Waiting(subscription.getEventType().toUpperCase(java.util.Locale.ROOT),
                        subscription.getActivityId(), subscription.getEventName(), subscription.getExecutionId(),
                        null, null, null, authorized.state()));
            var executions = runtime.createExecutionQuery().processInstanceId(instanceId).listPage(0, 201);
            truncated = executions.size() > 200;
            for (var execution : executions)
                if (execution.getActivityId() != null && process.getFlowElement(execution.getActivityId()) instanceof ReceiveTask)
                    rows.add(new Waiting("RECEIVE", execution.getActivityId(), execution.getActivityId(),
                            execution.getId(), null, null, null, authorized.state()));
            addJobs(rows, management.createTimerJobQuery().processInstanceId(instanceId).listPage(0, 201), "TIMER", "WAITING");
            addJobs(rows, management.createJobQuery().processInstanceId(instanceId).listPage(0, 201), "JOB", "EXECUTABLE");
            addJobs(rows, management.createSuspendedJobQuery().processInstanceId(instanceId).listPage(0, 201), "JOB", "SUSPENDED");
            addJobs(rows, management.createDeadLetterJobQuery().processInstanceId(instanceId).listPage(0, 201), "JOB", "DEAD_LETTER");
        }
        var snapshot = metrics.readHealthSnapshot();
        boolean ready = snapshot.available() && snapshot.ageMillis() <= properties.getMetricsSnapshotMaxAge().toMillis()
                && snapshot.asyncExecutorActive();
        return new View(instanceId, ready, truncated || rows.size() > 200,
                List.copyOf(rows.subList(0, Math.min(rows.size(), 200))));
    }

    private void addJobs(List<Waiting> rows, List<Job> jobs, String type, String state)
    {
        for (Job job : jobs) rows.add(new Waiting(type, job.getElementId(), null, job.getExecutionId(), job.getId(),
                job.getDuedate() == null ? null : job.getDuedate().toInstant(), job.getRetries(), state));
    }
}
