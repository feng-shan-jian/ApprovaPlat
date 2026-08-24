package com.ruoyi.flowable.service.task;

import org.flowable.bpmn.model.BpmnModel;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.ProcessDefinition;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;

/**
 * 任务生命周期用例的部署定义、模型和主流程只读事实读取器。
 */
@Component
public class WorkflowTaskBpmnReader
{
    private final RepositoryService repositoryService;

    /**
     * 创建 BPMN 部署事实读取器。
     *
     * @param repositoryService RepositoryService，部署定义和模型查询服务
     * @return 无返回值，构造后由 Spring 管理
     */
    public WorkflowTaskBpmnReader(RepositoryService repositoryService)
    {
        this.repositoryService = repositoryService;
    }

    /**
     * 读取并核验流程定义、已部署模型和同 key 唯一主流程。
     *
     * @param processDefinitionId String，任务所属流程定义主键
     * @return WorkflowTaskBpmnSnapshot，同一次读取的部署 BPMN 事实
     */
    public WorkflowTaskBpmnSnapshot require(String processDefinitionId)
    {
        if (!StringUtils.hasText(processDefinitionId))
        {
            throw dataError();
        }
        ProcessDefinition definition = repositoryService.getProcessDefinition(
                processDefinitionId);
        if (definition == null)
        {
            throw notFound();
        }
        if (!StringUtils.hasText(definition.getKey())
                || !StringUtils.hasText(definition.getDeploymentId()))
        {
            throw dataError();
        }
        BpmnModel model = repositoryService.getBpmnModel(processDefinitionId);
        if (model == null)
        {
            throw dataError();
        }
        org.flowable.bpmn.model.Process process = model.getProcessById(
                definition.getKey());
        if (process == null)
        {
            throw dataError();
        }
        return new WorkflowTaskBpmnSnapshot(definition, model, process);
    }

    /**
     * 创建稳定流程定义不存在错误。
     *
     * @return ServiceException，既有 HTTP 404 错误
     */
    private ServiceException notFound()
    {
        return new ServiceException("工作流对象不存在或已被删除", HttpStatus.NOT_FOUND);
    }

    /**
     * 创建稳定 BPMN 关联数据错误。
     *
     * @return ServiceException，既有 HTTP 500 错误
     */
    private ServiceException dataError()
    {
        return new ServiceException("工作流对象关联数据异常", HttpStatus.ERROR);
    }
}
