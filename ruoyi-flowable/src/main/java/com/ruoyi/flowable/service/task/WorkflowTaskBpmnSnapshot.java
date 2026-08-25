package com.ruoyi.flowable.service.task;

import java.util.Objects;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.engine.repository.ProcessDefinition;

/**
 * 同一次用例读取的部署定义、BPMN 模型和同 key 主流程事实。
 *
 * @param definition ProcessDefinition，真实部署流程定义
 * @param model BpmnModel，定义对应的已部署模型
 * @param process Process，定义 key 对应的主流程
 */
public record WorkflowTaskBpmnSnapshot(ProcessDefinition definition,
        BpmnModel model, org.flowable.bpmn.model.Process process)
{
    /**
     * 校验 BPMN 快照三项事实均存在。
     *
     * @param definition ProcessDefinition，真实流程定义
     * @param model BpmnModel，已部署 BPMN 模型
     * @param process Process，同 key 主流程
     * @return 无返回值，构造后引用不可替换
     */
    public WorkflowTaskBpmnSnapshot
    {
        Objects.requireNonNull(definition);
        Objects.requireNonNull(model);
        Objects.requireNonNull(process);
    }
}
