package com.ruoyi.flowable.service.process;

import static org.mockito.Mockito.mock;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.flowable.engine.ProcessEngine;
import org.flowable.task.api.history.HistoricTaskInstance;
import com.ruoyi.flowable.authorization.WorkflowProcessAccessSnapshot;
import com.ruoyi.flowable.authorization.WorkflowTaskAccessSnapshot;
import com.ruoyi.flowable.domain.vo.WorkflowProcessFormDetailView;
import com.ruoyi.flowable.mapper.WorkflowHistoricVariableMapper;
import com.ruoyi.flowable.service.WorkflowFormTemplateValidator;
import com.ruoyi.flowable.service.attachment.WorkflowAttachmentService;
import com.ruoyi.flowable.service.model.WorkflowDeploymentArtifactRepository;
import com.ruoyi.flowable.testsupport.WorkflowH2SchemaMapperSupport;

/** 为任务写链测试装配正式详情投影；不伪造作用域、变量或历史提交。 */
public final class WorkflowTaskFormProjectionTestSupport
{
    private WorkflowTaskFormProjectionTestSupport() { }

    public static WorkflowProcessFormDetailView project(ProcessEngine engine,
            DataSource dataSource, String instanceId, String taskId)
    {
        var history = engine.getHistoryService();
        var historicTask = history.createHistoricTaskInstanceQuery().taskId(taskId).singleResult();
        var repository = engine.getRepositoryService();
        var definition = repository.createProcessDefinitionQuery()
                .processDefinitionId(historicTask.getProcessDefinitionId()).singleResult();
        var mapper = WorkflowH2SchemaMapperSupport.createSpringMapper(dataSource, "task-form-detail",
                WorkflowHistoricVariableMapper.class, "mapper/flowable/WorkflowHistoricVariableMapper.xml");
        var variables = new WorkflowProcessVariableProjection(mapper);
        var projection = new WorkflowProcessFormDetailProjection(
                new WorkflowFormTemplateValidator(),
                variables, mock(WorkflowAttachmentService.class));
        var runtimeTask = engine.getTaskService().createTaskQuery().taskId(taskId).singleResult();
        var access = new WorkflowTaskAccessSnapshot(taskId, instanceId, definition.getId(),
                historicTask.getTaskDefinitionKey(), historicTask.getName(), historicTask.getAssignee(),
                historicTask.getOwner(), null, runtimeTask != null, historicTask.getCreateTime().toInstant(),
                historicTask.getEndTime() == null ? null : historicTask.getEndTime().toInstant(),
                null, null, false, runtimeTask);
        var historyData = new WorkflowProcessHistoryProjection.HistoryData(
                history.createHistoricActivityInstanceQuery().processInstanceId(instanceId).list(),
                history.createHistoricTaskInstanceQuery().processInstanceId(instanceId).list().stream()
                        .collect(Collectors.toMap(HistoricTaskInstance::getId, Function.identity())), Map.of());
        return projection.project(new WorkflowProcessFormDetailProjection.FormProjectionRequest(
                projection.loadSchemas(definition.getDeploymentId(),
                        new WorkflowDeploymentArtifactRepository(repository).openRead(definition.getDeploymentId()).forms()), historyData,
                repository.getBpmnModel(definition.getId()).getProcessById(definition.getKey()),
                variables.loadSubmissionSnapshots(instanceId, definition.getDeploymentId(), Set.of()),
                new WorkflowProcessAccessSnapshot(instanceId, definition.getId(), definition.getDeploymentId(),
                        null, null, null, null, null, null, null, null,
                        history.createHistoricProcessInstanceQuery().processInstanceId(instanceId).singleResult()), access, false, false)).formDetail();
    }
}
