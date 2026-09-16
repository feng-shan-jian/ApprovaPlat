package com.ruoyi.flowable.listener;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.flowable.bpmn.model.CallActivity;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.ExecutionListener;
import org.springframework.stereotype.Component;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.service.model.WorkflowDeploymentArtifactRepository;
import com.ruoyi.flowable.service.process.WorkflowStartVariableValidator;

/** 原生 CallActivity 输出已复制、离开活动前按父流程不可变表单再校验；失败回滚整个完成事务。 */
@Component("workflowSystemOutputListener")
public class WorkflowSystemOutputListener implements ExecutionListener
{
    public static final String EXPRESSION = "${workflowSystemOutputListener}";
    private final RepositoryService repository;
    private final WorkflowDeploymentArtifactRepository artifacts;
    private final WorkflowStartVariableValidator variables;

    public WorkflowSystemOutputListener(RepositoryService repository, WorkflowDeploymentArtifactRepository artifacts,
            WorkflowStartVariableValidator variables)
    { this.repository = repository; this.artifacts = artifacts; this.variables = variables; }

    @Override public void notify(DelegateExecution execution)
    {
        if (!"end".equals(execution.getEventName()) || !(execution.getCurrentFlowElement() instanceof CallActivity call))
            throw new ServiceException("系统结果校验调用位置不合法", 409);
        var definition = repository.getProcessDefinition(execution.getProcessDefinitionId());
        var process = repository.getBpmnModel(definition.getId()).getProcessById(definition.getKey());
        Set<String> targets = call.getOutParameters().stream().map(parameter -> parameter.getTarget()).collect(Collectors.toSet());
        boolean checked = false;
        for (var form : artifacts.selectForms(definition.getDeploymentId()))
        {
            if (process.getFlowElement(form.getNodeKey(), true) == null) continue;
            var root = variables.parseTrustedSnapshot(form.getContent());
            var fields = variables.systemOutputFields(root);
            Map<String, Object> patch = new LinkedHashMap<>();
            for (String name : fields.keySet())
                if (targets.contains(name)) patch.put(name, execution.getVariable(name));
            if (!patch.isEmpty())
            {
                variables.validateSystemOutputPatch(fields, patch);
                checked = true;
            }
        }
        if (!checked) throw new ServiceException("系统结果字段的部署快照不存在", 409);
    }
}
